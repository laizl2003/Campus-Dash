# MQ 与抢单缓存全流程

> 本文是 day-02 ~ day-07 的专题收口：把散在各层的 MQ 用法和抢单 Redis 链路串成两条完整流程。
> 涉及类名保留原样，路径相对仓库根。

---

# 一、MQ 在本项目实现的四类功能

## 1.1 总览

| 功能 | Topic | 类型 | 生产者 | 消费者（组） | 幂等手段 |
| --- | --- | --- | --- | --- | --- |
| 超时流转 | `errand-confirm-timeout` | DELAY | `TimeoutTransferUseCase.dispatch` / `LocalMessageRetryJob` | `dash-timeout-consumer` | round + version + msg_key |
| 自动结算 | `errand-auto-settle` | DELAY | `LocalMessageRetryJob` | `dash-autosettle-consumer` | 托管 CAS + 任务 CAS + biz_no |
| 资金事件 | `errand-fund-event` | TRANSACTION | `FundEventPort`（结算/退款/仲裁） | `dash-fund-event-consumer`（落库）+ `dash-fund-event-push`（推送） | uk_msg_user / ledger biz_no |
| 延迟双删 | `errand-cache-evict` | DELAY | `CacheEvictSupport` → `RocketMqCacheEvictAdapter` | `dash-cache-evict-consumer` | 删除本身幂等 |

四类消息的共同点：**at-least-once + 业务幂等 + 数据库兜底**。

## 1.2 超时流转（DELAY）

**目的**：抢中者 5 分钟未确认，自动流转给候选人或重新开放。

1. 抢中成功，事务内写 `local_message`（`msg_key = timeout:{errandId}:{round}`，`deliver_at = now + 300s`）；
2. 事务提交后 `dispatch` 调 `DelayMessagePort.send` 发定时消息；
3. 到期 `TimeoutTransferConsumer` 消费 → `handleTimeout(errandId, round)`；
4. 状态/轮次不匹配 → `SKIPPED`（ACK，不重投）；
5. 有候选人 → CAS 换人（`round + 1`）+ 登记下一轮消息；
6. 无候选人 → 回退 `PUBLISHED`、还名额、扣信用分。

兜底：`TimeoutScanJob` 每 5 秒扫库（宽限 2 秒让 MQ 先处理）。

## 1.3 自动结算（DELAY）

**目的**：送达后 24 小时未确认，自动打款给跑腿。

1. `DeliverErrandUseCase` 在事务内写 `local_message`（`autosettle:{errandId}`，`deliver_at = now + 86400s`）；
2. `LocalMessageRetryJob` 每 10 秒扫 `PENDING`，到点后发送延迟消息；
3. 到期 `AutoSettleConsumer` → `SettleErrandUseCase.settle(errandId, SYSTEM_OPERATOR)`；
4. 三道闸门保证只结算一次。

兜底：`AutoSettleScanJob` 每 5 秒扫 `DELIVERED` 超窗任务。

## 1.4 资金事件（TRANSACTION）

**目的**：结算/退款/仲裁完成后，通知双方并落站内信。

1. `FundEventPort.publishInTransaction`：发半消息 → 执行本地事务（改托管单、任务、流水、信用分）→ commit / rollback；
2. 进程崩溃 → broker 回查 `TransactionChecker`，按 `wallet_ledger` 里有没有 `bizNo` 判定；
3. 消费端两个组各收一份：
   - worker：`FundEventConsumer` 落 `notification`（`msg_key + ":pub"/":runner"` 区分收件人）；
   - app：`FundEventPushConsumer` 只做 WebSocket 推送，失败也 ACK。

## 1.5 延迟双删（DELAY）

**目的**：覆盖“慢读在第一次删缓存后写回旧值”的窗口。

1. 写用例提交后 `CacheEvictSupport.evictAfterCommit` 立即删缓存；
2. 同时发 500ms 延迟消息（`keys = evict:{errandId}`）；
3. `CacheEvictConsumer` 到期再删一次；
4. 删除失败只记日志，靠 TTL 与校验 job 兜底。

## 1.6 可靠性底座

| 组件 | 作用 |
| --- | --- |
| `local_message` 表 | 业务数据与“待发消息”同事务，状态可查 |
| `LocalMessageRetryJob` | 每 10 秒重发 `PENDING`；失败退避 `2^n` 秒（上限 300），6 次后 `DEAD` |
| `uk_msg_key` | 同一轮只登记一次（幂等三件套之一） |
| 扫描 job | MQ 丢失/消费失败时，从数据库兜底 |

**发送与标记不在同一事务**：先 `send` 再 `markSent`，崩溃窗口只会导致“重复发送”，不会“丢消息”；重复由消费端幂等消化。

## 1.7 降级开关

`dash.mq.enabled=false` 时装配 `NoopDelayMessageAdapter` / `NoopFundEventAdapter`：

- 超时/结算退化为纯扫描 job（变慢但不卡死）；
- 资金事件不发送（通知丢失，钱仍正确）；
- 延迟双删只保留第一次删除。

---

# 二、抢单 Redis 全流程

## 2.1 Key 清单

| Key | 类型 | TTL | 写入方 | 读取方 |
| --- | --- | --- | --- | --- |
| `errand:slot:{errandId}` | string | 7 天 | `initSlot` | `grab.lua`、`remainingSlot` |
| `errand:grabbed:{errandId}` | set | 无（初始化时删除） | `grab.lua` | `grab.lua` |
| `errand:idem:{errandId}:{requestId}` | string | 300 秒 | `grab.lua` | `grab.lua` |
| `errand:candidates:{errandId}` | zset | 无 | `enqueueCandidate` | `pollBest` |
| `errand:detail:{errandId}:{shard}` | string | 600s ± 120s | 详情回填 | 详情查询 |
| `errand:bloom` | bloom | 无 | `registerExisting` | `mightExist` |
| `errand:rebuild:{errandId}` | lock | 10 秒租约 | `tryAcquireRebuild` | 重建逻辑 |

三个抢单 key 都带 `{errandId}` hash tag，保证 Cluster 下同槽。

## 2.2 阶段 0：发布时初始化

`PublishErrandUseCase` 在本地事务最后一步：

~~~java
grabSlotPort.initSlot(errandId, slotTotal, 7 * 24 * 3600);
cache.registerExisting(errandId);
~~~

对应 Redis 操作：

~~~text
SET errand:slot:{id} <slotTotal> EX 604800
DEL errand:grabbed:{id}
BF.ADD errand:bloom <id>
~~~

**为什么放最后**：前面任何一步失败都会回滚事务，不会出现“库里没任务、Redis 有名额”。

## 2.3 阶段 1：抢单请求进入

~~~text
POST /api/errands/{id}/grab
  X-Request-Id: 客户端幂等键（缺省则服务端生成 UUID）
        │
        ▼
GrabErrandUseCase.grab(cmd)
  L1 Sentinel/local 限流        → GRAB_RATE_LIMITED
  L2 信用分 / 在途任务数          → CREDIT_TOO_LOW / TOO_MANY_ONGOING
  L3 Redis Lua 原子判定          → SLOT_FULL / ALREADY_GRABBED / ...
  L4 MySQL CAS + 唯一索引        → GRAB_CONFLICT
~~~

## 2.4 阶段 2：Lua 原子判定

KEYS：

- `KEYS[1] = errand:slot:{id}`
- `KEYS[2] = errand:grabbed:{id}`
- `KEYS[3] = errand:idem:{id}:{requestId}`

~~~lua
local prev = redis.call('GET', KEYS[3])
if prev then
    if tonumber(prev) == 1 then return -2 else return 0 end
end

local slot = redis.call('GET', KEYS[1])
if not slot then return -3 end
if redis.call('SISMEMBER', KEYS[2], ARGV[1]) == 1 then return -1 end
if tonumber(slot) <= 0 then
    redis.call('SETEX', KEYS[3], ARGV[2], '0')
    return 0
end

redis.call('DECR', KEYS[1])
redis.call('SADD', KEYS[2], ARGV[1])
redis.call('SETEX', KEYS[3], ARGV[2], '1')
return 1
~~~

| 返回 | 含义 | 后续 |
| ---: | --- | --- |
| 1 | 占位成功 | 进入 DB CAS |
| 0 | 名额满 | 进候选队列 |
| -1 | 同一用户重复抢 | 直接失败 |
| -2 | 同一 requestId 重放 | 按“首次成功”返回 |
| -3 | 任务不可抢 | 失败 |

## 2.5 阶段 3：DB 落库与补偿

Lua 成功后，`GrabTransactionalStep.lockAndRecord` 在一个事务里：

~~~sql
UPDATE errand
   SET status='LOCKED', grabber_id=?, slot_taken=slot_taken+1,
       locked_at=NOW(3), version=version+1
 WHERE id=? AND status='PUBLISHED' AND version=? AND slot_taken < slot_total;

INSERT INTO grab_record (...) VALUES (...);
INSERT INTO errand_status_log (...);
~~~

失败处理：

- CAS 返回 0 或唯一索引冲突 → `rollback_slot.lua`：

  ~~~lua
  if redis.call('SREM', KEYS[2], ARGV[1]) == 1 then
      redis.call('INCR', KEYS[1])
      redis.call('DEL', KEYS[3])
      return 1
  end
  return 0
  ~~~

- 任何未预期异常同样回滚，防止名额永久泄漏。

## 2.6 阶段 4：成功后的三件事

1. **登记首轮超时**：事务内写 `local_message`，提交后发延迟消息；
2. **删详情缓存**：`cacheEvict.evictAfterCommit(id)`；
3. **WebSocket 推送**：通知发单人“有人抢了”。

## 2.7 阶段 5：失败者进候选队列

~~~java
double creditBonus = Math.min(credit, 100) * 10.0;
double score = System.currentTimeMillis() - creditBonus;
candidateQueue.offer(errandId, runnerId, score);
~~~

Redis：

~~~text
ZADD errand:candidates:{id} <score> <runnerId>
~~~

score 越小越优先；信用分最多抵 1000ms，避免高信用用户垄断。

## 2.8 阶段 6：超时流转与回退

~~~text
状态/轮次不匹配 → SKIPPED
round >= 5      → 回退
pollBest        → 有候选人：CAS 换人 + round+1 + 下一轮消息
                → 无候选人：回退 PUBLISHED
~~~

回退时 Redis 补偿：

~~~java
grabSlotPort.rollback(errand.id(), grabber, "revert:" + errand.round());
// SREM errand:grabbed:{id} <grabber>
// INCR errand:slot:{id}
// DEL  errand:idem:{id}:revert:{round}
~~~

数据库同时 `slot_taken - 1`、`round + 1`、`grabber_id = NULL`。

## 2.9 阶段 7：确认 / 结算

- 确认（`LOCKED → ACCEPTED`）：Redis 名额不变，`grabbed` 集合也不清理——同一用户不能抢同一个任务的第二个名额；
- 结算/退款：只删详情缓存，不碰抢单 key；
- 抢单 key 的清理靠 TTL（名额 7 天）和 `initSlot` 时的 `DEL`。

## 2.10 阶段 8：详情缓存的读路径

详情查询与抢单是两条独立的 Redis 链路：

~~~text
GET /api/errands/{id}
  → cache.get(id)                 // 随机读 4 个分片之一
      ├─ 命中且未逻辑过期 → 返回
      ├─ 命中但逻辑过期   → tryAcquireRebuild
      │       ├─ 抢到锁 → 回源 DB → 回填 → 释放
      │       └─ 抢不到 → 返回旧值
      └─ 未命中 → bloom.mightExist
              ├─ false → 直接 404（防穿透）
              └─ true  → 回源 DB → 回填 / 写空值缓存
~~~

细节：

- key：`errand:detail:{id}:{shard}`，4 片随机读，失效删 4 片；
- 逻辑过期时间 = 物理 TTL 的一半，物理 TTL 带 ±120s 抖动；
- 空值缓存 60 秒；
- 重建锁 `tryLock(0, 10, SECONDS)`，不阻塞，抢不到返回旧值。

## 2.11 一致性与降级

| 场景 | 行为 |
| --- | --- |
| Redis 挂 | 抢单直接失败（Lua 异常上抛）；详情缓存降级回源 DB |
| 名额泄漏（DB 失败） | `rollback_slot.lua` 补偿 |
| 名额与 DB 不一致 | 压测用 `remainingSlot` 与 DB `slot_taken` 对比；缓存用校验 job |
| 布隆误判 | 1% 假阳性 → 多查一次库，然后空值缓存 |
| MQ 挂 | 抢单仍成功；超时流转靠扫描 job |

**已知小问题**：`errand:grabbed:{id}` 没有单独 TTL，任务结束后不会主动删除，长期运行会有少量残留。要更严谨，可以在结算/退款后 `DEL`，或给它设和名额相同的 TTL。

## 2.12 一张时序图

~~~text
客户端        Controller        GrabUseCase        Redis           MySQL
  │  grab       │                  │                │               │
  ├────────────▶│                  │                │               │
  │             ├─────────────────▶│                │               │
  │             │                  ├─ L1 限流 ──────▶│               │
  │             │                  ├─ L2 信用 ──────────────────────▶│
  │             │                  ├─ EVAL grab.lua ▶│               │
  │             │                  │◀── 1 ──────────┤               │
  │             │                  ├─ CAS + 记录 ───────────────────▶│
  │             │                  │◀── 1 ───────────────────────────┤
  │             │                  ├─ 登记超时 / 删缓存 / 推送       │
  │◀────────────┴──────────────────┤                │               │
~~~

---

# 三、一句话总结

- **MQ**：四类消息分别解决“超时、结算、通知、缓存双删”，统一走“本地消息表/事务消息 + 幂等 + 扫描兜底”；
- **抢单 Redis**：`slot + grabbed + idem` 三个 key 用一段 Lua 原子裁决，DB 用 CAS + 唯一索引做最终防线，失败回滚名额，超时流转换人，详情缓存独立走 Cache Aside。
