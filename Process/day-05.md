# day-05 · infrastructure 层源码详解：37 个文件

> 阅读范围：`dash-infrastructure` 全部 37 个文件（35 个 Java + 2 个 Lua）。
> 沿用 day-03/day-04 的格式：类型、字段、构造器、方法签名、功能、关键源码。
> 这一层只做一件事：**把 domain 的端口翻译成 Redis / MySQL / RocketMQ 的具体操作**。

## 0. 总览

| 分组 | 文件 | 一句话 |
| --- | ---: | --- |
| 抢单与 Lua | 6 | 原子占位脚本、回滚脚本、两种限流 |
| 持久化 | 12 | 12 个 Jdbc 适配器，CAS SQL 都在这里 |
| 消息 | 6 | 定时消息、事务消息、回查、Noop 降级 |
| 缓存 | 6 | 详情缓存、候选队列、排行榜、Redisson |
| 认证 | 2 | Redis Session 与 JWT 两种实现 |
| 分片 | 3 | 分片策略、规则工厂、取模算法 |
| 实时 | 2 | WebSocket 推送消费者、Noop 实现 |

**装配原则**：每个适配器都实现一个 domain 端口；用 `@ConditionalOnProperty` 在"真实现 / Noop"之间切换，配置一改就换实现。

---

## 0.1 类名中文对照

| 类名 | 中文名 | 类型 |
| --- | --- | --- |
| `grab.lua` | 抢单原子脚本 | Lua |
| `rollback_slot.lua` | 名额回滚脚本 | Lua |
| `RedisLuaConfig` | Redis/Lua 装配 | class |
| `RedisGrabSlotAdapter` | 抢单名额适配器 | class |
| `LocalGrabRateLimiterAdapter` | 本地限流适配器 | class |
| `SentinelGrabRateLimiterAdapter` | Sentinel 限流适配器 | class |
| `JdbcErrandRepository` | 任务写仓储 | class |
| `JdbcErrandQueryAdapter` | 任务查询适配器 | class |
| `JdbcWalletRepository` | 钱包写仓储 | class |
| `JdbcWalletQueryAdapter` | 钱包查询适配器 | class |
| `JdbcGrabRecordRepository` | 抢单记录仓储 | class |
| `JdbcCreditRepository` | 信用分仓储 | class |
| `JdbcLocalMessageRepository` | 本地消息表仓储 | class |
| `JdbcNotificationRepository` | 站内消息写仓储 | class |
| `JdbcNotificationQueryAdapter` | 站内消息查询适配器 | class |
| `JdbcFundAuditAdapter` | 资金审计适配器 | class |
| `JdbcReconRepository` | 对账仓储 | class |
| `JdbcSyncDiffRepository` | 缓存差异仓储 | class |
| `RocketMqConfig` | RocketMQ 装配 | class |
| `RocketMqDelayMessageAdapter` | 定时消息适配器 | class |
| `RocketMqFundEventAdapter` | 资金事务消息适配器 | class |
| `RocketMqCacheEvictAdapter` | 延迟删缓存适配器 | class |
| `NoopDelayMessageAdapter` | 定时消息空实现 | class |
| `NoopFundEventAdapter` | 资金事件空实现 | class |
| `FundEventPushConsumer` | 资金事件推送消费者 | class |
| `NoopRealtimeNotifier` | 实时推送空实现 | class |
| `RedisErrandCacheAdapter` | 任务缓存适配器 | class |
| `RedisCandidateQueueAdapter` | 候选队列适配器 | class |
| `RedisCreditRankingAdapter` | 信用排行适配器 | class |
| `RedissonConfig` | Redisson 装配 | class |
| `NoopErrandCacheAdapter` | 缓存空实现 | class |
| `NoopCacheEvictAdapter` | 延迟删缓存空实现 | class |
| `RedisAuthAdapter` | Redis Session 认证 | class |
| `JwtAuthAdapter` | JWT 认证 | class |
| `CampusDashShardingPolicy` | 分片策略 | class |
| `CampusDashShardingRuleFactory` | 分片规则工厂 | class |
| `CampusDashModuloShardingAlgorithm` | 取模分片算法 | class |

---

## 1. 抢单与 Lua（6）

### 1.1 `grab.lua` · 抢单原子脚本（Lua）

**功能**：一次脚本完成"幂等检查 → 名额校验 → 一人一名额 → 扣名额 → 记抢中者 → 写幂等键"。

**KEYS / ARGV**

| 参数 | 含义 |
| --- | --- |
| `KEYS[1]` | `errand:slot:{taskId}` 剩余名额 |
| `KEYS[2]` | `errand:grabbed:{taskId}` 抢中者集合 |
| `KEYS[3]` | `errand:idem:{requestId}` 幂等键 |
| `ARGV[1]` | runnerId |
| `ARGV[2]` | 幂等键 TTL（300s） |

**返回码**：`1` 成功、`0` 名额满、`-1` 已抢过、`-2` 重复请求、`-3` 不可抢。

**关键源码**

~~~lua
local prev = redis.call('GET', KEYS[3])
if prev then return tonumber(prev) == 1 and -2 or 0 end
local slot = redis.call('GET', KEYS[1])
if not slot then return -3 end
if redis.call('SISMEMBER', KEYS[2], ARGV[1]) == 1 then return -1 end
if tonumber(slot) <= 0 then redis.call('SETEX', KEYS[3], ARGV[2], '0'); return 0 end
redis.call('DECR', KEYS[1])
redis.call('SADD', KEYS[2], ARGV[1])
redis.call('SETEX', KEYS[3], ARGV[2], '1')
return 1
~~~

**要点**：三条命令的序列必须原子，否则两个客户端都读到 1 就超卖。

### 1.2 `rollback_slot.lua` · 名额回滚脚本（Lua）

**功能**：Lua 占位成功但 DB 落库失败时补回名额。

**关键源码**

~~~lua
if redis.call('SREM', KEYS[2], ARGV[1]) == 1 then
    redis.call('INCR', KEYS[1])
    redis.call('DEL', KEYS[3])
    return 1
end
return 0
~~~

**要点**：先 `SREM` 判断"这个人确实占过"，只有删成功才 `INCR`——重复调用不会让名额虚增。

### 1.3 `RedisLuaConfig` · Redis/Lua 装配（class）

**功能**：提供 `StringRedisTemplate` 与两个 `RedisScript<Long>`。

**方法**

| 方法 | 返回 | 说明 |
| --- | --- | --- |
| `stringRedisTemplate(RedisConnectionFactory)` | `StringRedisTemplate` | 字符串序列化，redis-cli 可读 |
| `grabScript()` | `RedisScript<Long>` | 加载 `lua/grab.lua` |
| `rollbackSlotScript()` | `RedisScript<Long>` | 加载 `lua/rollback_slot.lua` |

**要点**：`RedisScript` 会自动走 `EVALSHA`，返回 `NOSCRIPT` 才回退 `EVAL`，省掉每次传脚本体的开销。

### 1.4 `RedisGrabSlotAdapter` · 抢单名额适配器（class）

**字段**：`StringRedisTemplate redis`、`RedisScript<Long> grabScript`、`RedisScript<Long> rollbackScript`；常量 `IDEM_TTL_SECONDS = 300`

**构造器**：`RedisGrabSlotAdapter(StringRedisTemplate, @Qualifier("grabScript") RedisScript<Long>, @Qualifier("rollbackSlotScript") RedisScript<Long>)`

**方法**

| 方法 | 返回 | 说明 |
| --- | --- | --- |
| `tryAcquire(errandId, runnerId, requestId)` | `SlotOutcome` | 执行脚本并 `SlotOutcome.fromCode` |
| `rollback(errandId, runnerId, requestId)` | `void` | 执行回滚脚本 |
| `initSlot(errandId, slotTotal, ttlSeconds)` | `void` | `SET errand:slot:{id} N EX ttl` |
| `remainingSlot(errandId)` | `long` | 读剩余名额（压测校验） |

**关键源码（hash tag）**

~~~java
Long code = redis.execute(grabScript,
        List.of(slotKey(errandId), grabbedKey(errandId), idemKey(errandId, requestId)),
        String.valueOf(runnerId), String.valueOf(IDEM_TTL_SECONDS));
~~~

**要点**：三个 key 都带 `{errandId}` 形式的 hash tag——Redis Cluster 下同一任务的 key 必须落同一 slot，否则 Lua 跨 slot 直接报错。

### 1.5 `LocalGrabRateLimiterAdapter` · 本地限流适配器（class）

**字段**：`boolean enabled`、`int permitsPerSecond`、`ConcurrentHashMap<Long, WindowCounter> counters`

**构造器**：`LocalGrabRateLimiterAdapter(boolean enabled, int permitsPerSecond)`，两个参数分别由 `dash.grab.limit.enabled` 与 `dash.grab.limit.per-second` 注入，默认 true / 500。

**方法**：`tryPass(errandId, runnerId)`——按秒窗口计数，超限返回 false；每 64 秒顺手清理过期桶。

**要点**：单机实现，用 `compute` 保证同一 errandId 的计数器原子创建。

### 1.6 `SentinelGrabRateLimiterAdapter` · Sentinel 限流适配器（class）

**字段**：`boolean enabled`、`String resourceName`

**构造器**：三参（enabled / permitsPerSecond / resource），构造时调用 `installRule` 注册 `ParamFlowRule`。

**方法**：`tryPass(errandId, runnerId)`——`SphU.entry(resource, EntryType.IN, 1, errandId)`，`BlockException` 返回 false。

**要点**：`paramIdx = 0` 表示按第一个参数（errandId）限流，爆款任务不误伤其他任务。

---

## 2. 持久化（12）

### 2.1 `JdbcErrandRepository` · 任务写仓储（class）

**字段**：`JdbcTemplate jdbc`；静态 `RowMapper<Errand> MAPPER`（`Errand.rehydrate`）

**方法（全部是 CAS，返回 `int` 影响行数）**

| 方法 | SQL 关键条件 |
| --- | --- |
| `casLockForRunner` | `status='PUBLISHED' AND version=? AND slot_taken < slot_total` |
| `casPublish` | `status='DRAFT' AND version=?` |
| `casAccept` | `status='LOCKED' AND grabber_id=? AND version=?` |
| `casTransferToNext` | `status='LOCKED' AND version=? AND round=?` |
| `casRevertToPublished` | 同上，且 `slot_taken = GREATEST(slot_taken-1,0)` |
| `casPickUp` / `casDeliver` | 带 `grabber_id` 与 `version` |
| `casSettle` | 只带 `status='DELIVERED'`（版本参数不参与 SQL） |
| `casDispute` / `casCancel` / `casRefundFromDispute` / `casSettleFromDispute` | 只带状态条件 |
| `findConfirmTimeout` / `findAutoSettleDue` | 走 `idx_timeout_scan` / `idx_autosettle_scan` |

**关键源码**

~~~java
return jdbc.update("""
        UPDATE errand
           SET status = 'LOCKED', grabber_id = ?, slot_taken = slot_taken + 1,
               locked_at = NOW(3), version = version + 1
         WHERE id = ? AND status = 'PUBLISHED' AND version = ? AND slot_taken < slot_total
        """, runnerId, errandId, expectedVersion);
~~~

### 2.2 `JdbcErrandQueryAdapter` · 任务查询适配器（class）

**字段**：`JdbcTemplate jdbc`、共享 `MAPPER`

**方法**：`list`、`listByCursor`、`listByPublisher`、`listByRunner`、`statusLog`、`sampleIds`（`ORDER BY RAND()`）、`countOngoingByRunner`

**要点**：游标分页按 `(created_at DESC, id DESC)` 稳定排序；在途计数只算 `LOCKED/ACCEPTED/PICKED_UP`。

### 2.3 `JdbcWalletRepository` · 钱包写仓储（class）

**方法**

| 方法 | 说明 |
| --- | --- |
| `casDebit(accountId, amount)` | `WHERE available >= ?`，一条语句完成判断与扣减 |
| `casCredit(accountId, amount)` | 直接加 |
| `insertLedger(LedgerEntry)` | 唯一索引冲突抛异常 |
| `insertEscrow / escrowExists / findEscrowByErrandId` | 托管单 |
| `casEscrowStatus(campusId, errandId, from, to)` | `WHERE status = from` |
| `ledgerExists(bizNo)` | 事务消息回查 |

### 2.4 `JdbcWalletQueryAdapter` · 钱包查询适配器（class）

**关键源码**

~~~java
SELECT created_at, direction, amount, ref_type, ref_id, biz_no
  FROM wallet_ledger
 WHERE user_id = ?          -- 注意：不是 account_id
 ORDER BY created_at DESC, id DESC LIMIT ? OFFSET ?
~~~

**要点**：流水按 `user_id` 归属，不是账户 owner——否则会漏掉"发单人名下的托管借"。

### 2.5 `JdbcGrabRecordRepository` · 抢单记录仓储（class）

**方法**：`insert(GrabRecord)`、`countGrabbed(campusId, errandId)`

**要点**：普通 `INSERT`，**不是 INSERT IGNORE**——撞唯一索引必须抛，让上层感知"被数据库兜底拦下"。

### 2.6 `JdbcCreditRepository` · 信用分仓储（class）

**方法**：`scoreOf`、`find`、`applyEvent`、`recentEvents`、`windowDelta`、`calibrateScores`

**要点**：`scoreOf` 查不到返回 `BASE_SCORE(60)` 且不落库；`applyEvent` 捕获 `DuplicateKeyException` 返回 false（幂等）。

### 2.7 `JdbcLocalMessageRepository` · 本地消息表仓储（class）

**方法**：`enqueue`、`markSent`、`findPending`、`markRetry`

**关键源码**

~~~java
try {
    jdbc.update("INSERT INTO local_message (...) VALUES (?,?,?,?,?,'PENDING',?)", ...);
    return true;
} catch (DuplicateKeyException e) {
    return false;   // 这一轮已登记过，是预期情况，不能让事务回滚
}
~~~

### 2.8 `JdbcNotificationRepository` · 站内消息写仓储（class）

**方法**：`insertIfAbsent(...)`——靠 `uk_msg_user` 唯一索引，冲突返回 false。

### 2.9 `JdbcNotificationQueryAdapter` · 站内消息查询适配器（class）

**方法**：`list(userId, page, size)`（按 `created_at DESC, id DESC`）、`unreadCount(userId)`（`read_flag = 0`）。

### 2.10 `JdbcFundAuditAdapter` · 资金审计适配器（class）

**方法**：`@Transactional(propagation = REQUIRES_NEW) record(...)`

**要点**：独立事务，资金回滚审计仍在；内部吞异常，审计失败不拖垮资金。代价占第二个连接，连接池太小会死锁（`TransactionPitfallIT` 用池=2 复现）。

### 2.11 `JdbcReconRepository` · 对账仓储（class）

**方法**：`debitMinusCredit`、`findSnapshotDiffs`、`findEscrowClosureDiffs`、`countDiffs`

**关键源码（L1）**

~~~sql
SELECT COALESCE(SUM(CASE WHEN direction='DEBIT'  THEN amount ELSE 0 END),0)
     - COALESCE(SUM(CASE WHEN direction='CREDIT' THEN amount ELSE 0 END),0)
  FROM wallet_ledger
~~~

**要点**：三层都下推到 SQL，不把流水捞进内存（只增不减的表迟早 OOM）。

### 2.12 `JdbcSyncDiffRepository` · 缓存差异仓储（class）

**方法**：`record(checkTime, errandId, field, dbValue, cacheValue, fixed)`、`countSince(since)`

---

## 3. 消息（6）

### 3.1 `RocketMqConfig` · RocketMQ 装配（class）

**条件**：`@ConditionalOnProperty(name="dash.mq.enabled", havingValue="true")`

**方法**：`rocketMqProvider()`、`rocketMqClientConfiguration(endpoints)`、`timeoutProducer()` 等

**要点**：Producer 是线程安全的重对象，全局单例复用；**不预绑定 topic**，否则 topic 不存在时启动就失败。

### 3.2 `RocketMqDelayMessageAdapter` · 定时消息适配器（class）

**字段**：`ClientServiceProvider provider`、`Producer producer`
**方法**：`send(topic, msgKey, payload, deliverAt)`——`setDeliveryTimestamp` + `setKeys(msgKey)`
**要点**：topic 必须是 `DELAY` 类型，否则 broker 拒收。

### 3.3 `RocketMqFundEventAdapter` · 资金事务消息适配器（class）

**字段**：`Producer producer`、`ClientServiceProvider provider`、`WalletRepository walletRepository`

**方法**

| 方法 | 说明 |
| --- | --- |
| `publishInTransaction(event, localWork)` | 半消息 → 本地事务 → commit/rollback |
| `checker()` | `TransactionChecker`：回查本地事务 |
| `checkLocalTransaction(bizNo)` | 只查 `wallet_ledger` 有没有这个 bizNo |
| `attachProducer(producer)` / `close()` | 生命周期 |

**关键源码（回查三态）**

~~~java
if (exists) return COMMIT;
long age = System.currentTimeMillis() - msg.getBornTimestamp();
if (age > CHECK_GRACE_MILLIS) return ROLLBACK;   // 60s 宽限
return UNKNOWN;                                  // 让 broker 再问
~~~

### 3.4 `RocketMqCacheEvictAdapter` · 延迟删缓存适配器（class）

**字段**：`Producer producer`（复用普通 producer）、`ClientServiceProvider provider`
**方法**：`scheduleEvict(errandId, deliverAt)`——`TOPIC_CACHE_EVICT` + `keys("evict:"+id)`
**要点**：定时消息不需要事务 Producer，可以复用普通 Producer。

### 3.5 `NoopDelayMessageAdapter` · 定时消息空实现（class）

**条件**：`dash.mq.enabled=false` 或缺失
**方法**：`send(...)` 只打日志
**要点**：消息仍写进 `local_message`，由兜底扫描处理——主通道全挂只是变慢，不会卡死。

### 3.6 `NoopFundEventAdapter` · 资金事件空实现（class）

**方法**：`publishInTransaction(event, localWork)` 直接执行 `localWork`，不发消息
**要点**：资金正确性不依赖 MQ；通知丢了可接受，钱错不可接受。

---

## 4. 缓存（6）

### 4.1 `RedisErrandCacheAdapter` · 任务缓存适配器（class）

**字段**：`StringRedisTemplate redis`、`RedissonClient redisson`、`int shards`、`long ttlSeconds`、`long jitterSeconds`、`long emptyTtlSeconds`、`boolean bloomEnabled`

**方法**：`get`、`put`、`putEmpty`、`evict`、`tryAcquireRebuild`、`releaseRebuild`、`mightExist`、`registerExisting`

**关键源码（重建锁不等待）**

~~~java
RLock lock = redisson.getLock(REBUILD_LOCK_PREFIX + errandId);
return lock.tryLock(0, 10, TimeUnit.SECONDS);   // waitTime=0
~~~

**要点**：Key 是 `errand:detail:{id}:{shard}`，4 片随机读；物理 TTL 600s 加减 120s 抖动，逻辑过期在物理 TTL 的一半；任何 Redis 异常降级返回 empty。

### 4.2 `RedisCandidateQueueAdapter` · 候选队列适配器（class）

**字段**：`StringRedisTemplate redis`
**方法**：`offer` / `pollBest` / `size`
**关键源码**：ZSET `errand:candidates:{id}`，`score = now - min(credit,100)*10`，`pollBest` 用 `range(0,0)` + `remove`。

### 4.3 `RedisCreditRankingAdapter` · 信用排行适配器（class）

**字段**：`StringRedisTemplate redis`
**方法**：`update`（ZADD）、`top`（`reverseRangeWithScores`）
**要点**：key 按校区 `credit:rank:{campusId}`；Redis 故障返回空榜，不影响信用分事实。

### 4.4 `RedissonConfig` · Redisson 装配（class）

**方法**：`redissonClient(host, port, poolSize)`——单机模式，连接池默认 16
**要点**：与 Lettuce 并存但各用各的池；Redisson 只服务布隆与重建锁，P1 时刻意没引。

### 4.5 `NoopErrandCacheAdapter` · 缓存空实现（class）

**条件**：`dash.cache.enabled=false`
**方法**：全部空实现；`mightExist` 返 true、`tryAcquireRebuild` 返 true
**要点**：证明缓存可摘除，业务正确性不依赖它。

### 4.6 `NoopCacheEvictAdapter` · 延迟删缓存空实现（class）

**方法**：`scheduleEvict` 空实现——只保留 afterCommit 首删 + TTL 兜底。

---

## 5. 认证（2）

### 5.1 `RedisAuthAdapter` · Redis Session 认证（class）

**条件**：`dash.auth.mode=session`（默认）
**字段**：`StringRedisTemplate redis`、`Duration ttl`
**方法**：`login(userId)`（UUID token + `auth:token:{token}` + TTL）、`resolve(token)`、`logout(token)`
**要点**：有状态 Session，登出即删 key；token 不含任何用户信息。

### 5.2 `JwtAuthAdapter` · JWT 认证（class）

**条件**：`dash.auth.mode=jwt`；实现 `RefreshTokenPort`
**字段**：`SecretKey key`、`StringRedisTemplate redis`
**方法**：`login`、`loginWithRefresh`、`refresh`、`revokeRefresh`、`resolve`、`logout`
**要点**：无状态验签；吊销靠 `auth:jwt:revoked:{jti}` 黑名单（TTL = token 剩余有效期）；refresh token 存 `auth:jwt:refresh:{token}`，刷新时用 `GETDEL` 原子取用（并发重放只有一次成功）。

---

## 6. 分片（3）

### 6.1 `CampusDashShardingPolicy` · 分片策略（class）

**字段**：`int shardCount`；两组表名常量
**方法**：`routeTaskTable(table, campusId)`、`routeTaskShard(campusId)`、`routeUserTable(table, userId)`
**要点**：任务表按 `campus_id` 共片，用户表按 `user_id`；`Math.floorMod` 处理负数。

### 6.2 `CampusDashShardingRuleFactory` · 分片规则工厂（class）

**方法**：`static ShardingRuleConfiguration create(int shardCount)`
**要点**：为 8 张表生成 `ShardingTableRuleConfiguration`，并用 `ShardingTableReferenceRuleConfiguration` 把 4 张任务表绑成绑定表组。

### 6.3 `CampusDashModuloShardingAlgorithm` · 取模分片算法（class）

**方法**：`init(Properties)`、`doSharding(availableTargetNames, preciseValue)`、`doSharding(..., rangeValue)`
**关键源码**

~~~java
int count = shardCount == 0 ? availableTargetNames.size() : shardCount;
int shard = Math.floorMod(toLong(shardingValue.getValue()), count);
return "campus_dash_" + shard;
~~~

**要点**：`shardCount` 未配置时退化为按可用目标数取模，便于单测直接实例化验证。

---

## 7. 实时（2）

### 7.1 `FundEventPushConsumer` · 资金事件推送消费者（class）

**条件**：`dash.mq.enabled=true` 且 `dash.ws.enabled=true`
**方法**：`start()` / `stop()`（`@PostConstruct`/`@PreDestroy`）
**要点**：app 进程用独立消费组再订一次资金事件，只推送不写库；worker 关 WS 时不会抢走消息。

### 7.2 `NoopRealtimeNotifier` · 实时推送空实现（class）

**条件**：`dash.ws.enabled=false`
**方法**：三个推送方法全空
**要点**：放 infrastructure 是因为 `@ConditionalOnProperty` 属于 Spring Boot，application 层不能依赖它。

---

## 8. 横向模式

| 模式 | 例子 | 为什么 |
| --- | --- | --- |
| **条件装配切换实现** | 真实现 / `Noop*` 成对出现 | 配置一改就换，测试无需 MQ/Redis |
| **端口 → 适配器** | 12 个 `Jdbc*`、6 个 `Redis*` | domain 不认识技术细节 |
| **唯一索引做幂等** | `insertIfAbsent`、`enqueue`、`applyEvent` | 捕获 `DuplicateKeyException` 返回 false |
| **失败降级** | 缓存/排行/推送的 `catch` | 非核心链路不能拖垮主流程 |
| **hash tag** | `{errandId}` | 为 Redis Cluster 预留，单机无害 |

## 9. 自测

- [ ] 为什么 `grab.lua` 的三个 key 要带 `{errandId}`？
- [ ] `JdbcGrabRecordRepository` 为什么不能用 `INSERT IGNORE`？
- [ ] `JdbcFundAuditAdapter` 的 `REQUIRES_NEW` 有什么代价？什么情况下会死锁？
- [ ] 事务消息回查为什么只查 `wallet_ledger`，不查任务状态？
- [ ] `JdbcWalletQueryAdapter` 的流水为什么按 `user_id` 查而不是 `account_id`？
- [ ] `NoopErrandCacheAdapter` 的存在证明了什么？
