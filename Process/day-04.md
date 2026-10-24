# day-04 · application 层源码详解：26 个文件

> 阅读范围：`dash-application` 全部 26 个类（17 个用例 + 9 个查询/支撑）。
> 沿用 day-03 的格式：类型、字段、构造器、方法签名、功能、关键源码。
> 这一层只做三件事：**编排领域对象、划事务边界、提交后触发副作用**。

## 0. 总览

| 分组 | 文件 | 一句话 |
| --- | ---: | --- |
| 抢单 | 2 | 五层防线编排 + 事务性落库 |
| 发布 | 1 | 托管与发布同一个本地事务 |
| 履约 | 3 | 确认 / 取货 / 送达 |
| 资金与争议 | 5 | 结算、退款、争议、仲裁、仲裁资金步骤 |
| 超时流转 | 4 | 主通道 + 兜底 + 幂等三件套 |
| 缓存与查询 | 9 | 详情读路径、列表、动作解析、三个查询、两个 job |
| 支撑 | 2 | 缓存失效统一入口、信用分校准 |

**依赖方向**：Controller → UseCase → domain 端口；UseCase 不知道 Redis/MySQL/MQ 的具体实现。

---

## 0.1 类名中文对照

| 类名 | 中文名 | 分组 |
| --- | --- | --- |
| `GrabErrandUseCase` | 抢单用例 | 抢单 |
| `GrabTransactionalStep` | 抢单事务步骤 | 抢单 |
| `PublishErrandUseCase` | 发布任务用例 | 发布 |
| `ConfirmErrandUseCase` | 确认接单用例 | 履约 |
| `PickUpErrandUseCase` | 取货用例 | 履约 |
| `DeliverErrandUseCase` | 送达用例 | 履约 |
| `SettleErrandUseCase` | 结算用例 | 资金 |
| `RefundErrandUseCase` | 退款用例 | 资金 |
| `DisputeErrandUseCase` | 发起争议用例 | 资金 |
| `ArbitrateErrandUseCase` | 仲裁用例 | 资金 |
| `ArbitrateSettleStep` | 仲裁结算步骤 | 资金 |
| `TimeoutTransferUseCase` | 超时流转用例 | 流转 |
| `TimeoutTransferStep` | 超时流转事务步骤 | 流转 |
| `TimeoutPolicy` | 超时策略（已废弃） | 流转 |
| `DelayTaskPolicy` | 延迟任务策略 | 流转 |
| `CacheEvictSupport` | 缓存失效支撑 | 缓存 |
| `GetErrandDetailUseCase` | 任务详情查询 | 查询 |
| `QueryErrandListUseCase` | 任务列表查询 | 查询 |
| `CursorPage` | 游标分页结果 | 查询 |
| `ErrandActionResolver` | 可执行动作解析器 | 查询 |
| `QueryCreditUseCase` | 信用查询 | 查询 |
| `QueryWalletUseCase` | 钱包查询 | 查询 |
| `QueryNotificationUseCase` | 站内消息查询 | 查询 |
| `CacheConsistencyCheckUseCase` | 缓存一致性校验 | 支撑 |
| `BloomRebuildUseCase` | 布隆重建 | 支撑 |
| `CreditCalibrationUseCase` | 信用分校准 | 支撑 |

---

## 1. 抢单（2）

### 1.1 `GrabErrandUseCase` · 抢单用例（class）

**功能**：编排抢单的五层防线，是"业务链路 + 并发控制"的汇合点。

**字段**

| 字段 | 类型 | 用途 |
| --- | --- | --- |
| `grabSlotPort` | `GrabSlotPort` | Redis Lua 原子占位 |
| `candidateQueue` | `CandidateQueuePort` | 失败者进候选队列 |
| `errandRepository` | `ErrandRepository` | CAS 落库 |
| `transactionalStep` | `GrabTransactionalStep` | 独立事务 |
| `idGenerator` | `SnowflakeIdGenerator` | 生成 grab_record id |
| `cacheEvict` | `CacheEvictSupport` | 提交后删缓存 |
| `timeoutTransferUseCase` | `TimeoutTransferUseCase` | 抢中后登记首轮超时 |
| `creditRepository` | `CreditRepository` | 资格校验与候选加权 |
| `errandQueryPort` | `ErrandQueryPort` | 在途任务数 |
| `minCreditScore` | `int` | 默认 40 |
| `maxOngoing` | `int` | 默认 5 |
| `notifier` | `RealtimeNotifier` | WebSocket 推送 |
| `rateLimiter` | `GrabRateLimiterPort` | L1 限流 |

**构造器**：13 个参数，其中两个用 `@Value` 注入配置：

~~~java
GrabErrandUseCase(GrabSlotPort, CandidateQueuePort, ErrandRepository,
                  GrabTransactionalStep, SnowflakeIdGenerator, TimeoutTransferUseCase,
                  CacheEvictSupport, CreditRepository, ErrandQueryPort,
                  @Value("${dash.credit.min-score:40}") int minCreditScore,
                  @Value("${dash.credit.max-ongoing:5}") int maxOngoing,
                  RealtimeNotifier, GrabRateLimiterPort)
~~~

**Command / Result**

~~~java
record Command(long errandId, long runnerId, String requestId, int creditScore)
    // 3 参构造器把 creditScore 固定为 0（该参数自 P5 起被忽略）
record Result(ErrorCode code, boolean grabbed, Long candidateRank)
~~~

**方法**

| 方法 | 返回 | 说明 |
| --- | --- | --- |
| `grab(Command)` | `Result` | 主流程：限流 → 资格 → Lua → CAS → 事后动作 |
| `enqueueCandidate(Command)` | `Long` | 失败者入 ZSET，返回当前排队人数 |

**关键源码（顺序就是防线顺序）**

~~~java
// L1
if (!rateLimiter.tryPass(cmd.errandId(), cmd.runnerId())) return failed(GRAB_RATE_LIMITED);
// L2
int score = creditRepository.scoreOf(cmd.runnerId());
if (score < minCreditScore) return failed(CREDIT_TOO_LOW);
if (errandQueryPort.countOngoingByRunner(cmd.runnerId()) >= maxOngoing) return failed(TOO_MANY_ONGOING);
// L3
SlotOutcome outcome = grabSlotPort.tryAcquire(cmd.errandId(), cmd.runnerId(), cmd.requestId());
// L4
boolean ok = transactionalStep.lockAndRecord(campusId, errandId, runnerId,
                errand.version(), seq, errand.round(), nextId(), errand.status());
if (!ok) { grabSlotPort.rollback(...); return failed(GRAB_CONFLICT); }
// 事后：登记超时、删缓存、推送
timeoutTransferUseCase.scheduleFirstTimeout(id, round, version + 1);
cacheEvict.evictAfterCommit(id);
notifier.errandStatusChanged(...);
~~~

**要点**：L3 成功但 L4 失败必须回滚 Redis 名额；任何未预期异常也要回滚，否则名额永久泄漏。

### 1.2 `GrabTransactionalStep` · 抢单事务步骤（class）

**功能**：把"CAS + 写抢单记录 + 写状态日志"包进一个事务。单独成 Bean 是为了避开 `@Transactional` 同类自调用失效。

**字段**：`ErrandRepository errandRepository`、`GrabRecordRepository grabRecordRepository`
**构造器**：两参构造
**方法**

~~~java
@Transactional(rollbackFor = Exception.class)
boolean lockAndRecord(long campusId, long errandId, long runnerId,
                      long expectedVersion, int seq, int round, long recordId,
                      ErrandStatus fromStatus)
~~~

内部三步：`casLockForRunner` → `grabRecordRepository.insert`（撞唯一索引抛 `DuplicateKeyException` 让事务回滚）→ `appendStatusLog`。

---

## 2. 发布（1）

### 2.1 `PublishErrandUseCase` · 发布任务用例（class）

**功能**：一个本地事务里完成"冻结资金 → 复式记账 → 托管单 → 建任务 → 发布 → 初始化 Redis 名额 → 登记布隆"。

**字段**

| 字段 | 类型 |
| --- | --- |
| `errandRepository` | `ErrandRepository` |
| `walletRepository` | `WalletRepository` |
| `grabSlotPort` | `GrabSlotPort` |
| `idGenerator` | `SnowflakeIdGenerator` |
| `cache` | `ErrandCachePort` |

常量：`SLOT_TTL_SECONDS = 7 天`、`ESCROW_ACCOUNT_OWNER = -1`。

**Command / Result**：`Command(campusId, publisherId, type, title, rewardCents, slotTotal)`、`Result(errandId, status, frozenCents)`。

**方法**：`@Transactional Result publish(Command cmd)`

**关键源码（六步）**

~~~java
int frozen = walletRepository.casDebit(publisherAccount.id(), reward);   // 1
if (frozen == 0) throw new BizException(INSUFFICIENT_BALANCE, ...);

walletRepository.insertLedger(...DEBIT...);                              // 2
walletRepository.insertLedger(...CREDIT...);
walletRepository.casCredit(escrowAccount.id(), reward);
walletRepository.insertEscrow(EscrowOrder.held(...));                    // 3

errandRepository.insert(errand);                                         // 4
errand.publish(versionBeforePublish);
if (errandRepository.casPublish(errandId, versionBeforePublish) == 0) throw ...;

grabSlotPort.initSlot(errandId, slotTotal, SLOT_TTL_SECONDS);           // 5
cache.registerExisting(errandId);                                        // 6
~~~

**要点**：Redis 初始化放在事务最后——前面失败则整体回滚，不会出现"库无任务、Redis 有名额"。

---

## 3. 履约（3）

### 3.1 `ConfirmErrandUseCase` · 确认接单用例（class）

**字段**：`CacheEvictSupport`、`ErrandRepository`、`RealtimeNotifier`
**构造器**：三参
**Command**：`record Command(long errandId, long runnerId)`
**方法**：`@Transactional void confirm(Command)`

流程：查任务 → 已是 ACCEPTED 直接返回（幂等）→ `errand.acceptByRunner(runnerId, errand.version())` → `casAccept(id, runnerId, errand.version() - 1)` → 状态日志 → 推送 → 删缓存。

**要点**：`errand.version() - 1` 是因为领域方法内部已经 `version++`，CAS 要用流转前的版本。确认成功让该轮超时消息的 CAS 失败，**"已确认不会被流转"靠数据库保证，不靠时间判断**。

### 3.2 `PickUpErrandUseCase` · 取货用例（class）

**字段**：同上三件
**方法**：`@Transactional void pickUp(long errandId, long runnerId)`
流转 ACCEPTED → PICKED_UP；`casPickUp(id, runnerId, versionBefore)`；日志、推送、删缓存。

### 3.3 `DeliverErrandUseCase` · 送达用例（class）

**字段**：`ErrandRepository`、`LocalMessageRepository`、`SnowflakeIdGenerator`、`CacheEvictSupport`、`RealtimeNotifier`、`long autoSettleSeconds`（默认 86400）

**方法**：`@Transactional void deliver(long errandId, long runnerId)`

**关键源码（多了一步登记延迟消息）**

~~~java
errand.deliver(runnerId, errand.version(), Instant.now());
if (errandRepository.casDeliver(errandId, runnerId, errand.version() - 1) == 0) throw ...;
cacheEvict.evictAfterCommit(errandId);
notifier.errandStatusChanged(...);
String msgKey = DelayTaskPolicy.autoSettleKey(errandId);
localMessageRepository.enqueue(idGenerator.nextId(), msgKey,
        DelayTaskPolicy.TOPIC_AUTO_SETTLE,
        DelayTaskPolicy.autoSettlePayload(errandId),
        Instant.now().plusSeconds(autoSettleSeconds));
~~~

**要点**：自动结算需要"24 小时后投递"，事务消息不支持延迟，所以必须走本地消息表 + 定时消息。

---

## 4. 资金与争议（5）

### 4.1 `SettleErrandUseCase` · 结算用例（class）

**字段**

| 字段 | 类型 |
| --- | --- |
| `errandRepository` | `ErrandRepository` |
| `walletRepository` | `WalletRepository` |
| `auditPort` | `FundAuditPort` |
| `fundEventPort` | `FundEventPort` |
| `idGenerator` | `SnowflakeIdGenerator` |
| `cacheEvict` | `CacheEvictSupport` |
| `creditRepository` | `CreditRepository` |
| `creditRankingPort` | `CreditRankingPort` |
| `transactionTemplate` | `TransactionTemplate` |
| `notifier` | `RealtimeNotifier` |
| `commissionRate` | `double`（默认 0.05） |

**枚举**：`Result { SETTLED, ALREADY_SETTLED, CONFLICT }`

**方法**

| 方法 | 返回 | 说明 |
| --- | --- | --- |
| `settle(long errandId, long operatorId)` | `Result` | 鉴权 → 计算佣金 → 事务消息包裹事务 |
| `doSettle(...)` | `boolean` | 包内可见，事务体 |

**关键源码（三道闸门）**

~~~java
// 闸门 1
int escrowUpdated = walletRepository.casEscrowStatus(campusId, errandId, HELD, RELEASED);
if (escrowUpdated == 0) return false;
// 闸门 2
int errandUpdated = errandRepository.casSettle(errandId, errand.version());
if (errandUpdated == 0) return false;
// 资金转移 + 三条流水
walletRepository.casDebit(escrowAccount.id(), escrow.amount());
walletRepository.casCredit(runnerAccount.id(), runnerAmount);
walletRepository.casCredit(commissionAccount.id(), commissionAmount);
walletRepository.insertLedger(...escrow DEBIT...);
walletRepository.insertLedger(...runner CREDIT...);
walletRepository.insertLedger(...commission CREDIT...);   // 闸门 3：biz_no 唯一
~~~

佣金 `floor(total × rate)`，跑腿 = 总额 − 佣金，保证"跑腿 + 佣金 == 托管"。

### 4.2 `RefundErrandUseCase` · 退款用例（class）

**字段**：`ErrandRepository`、`WalletRepository`、`FundAuditPort`、`FundEventPort`、`SnowflakeIdGenerator`、`CacheEvictSupport`、`TransactionTemplate`、`RealtimeNotifier`；常量 `ESCROW_ACCOUNT_OWNER = -1`

**枚举**：`Result { REFUNDED, ALREADY_REFUNDED, CONFLICT }`

**方法**

| 方法 | 说明 |
| --- | --- |
| `cancelAndRefund(errandId, publisherId)` | 发单人取消：DRAFT/PUBLISHED → CANCELLED |
| `arbitrateRefund(errandId, operatorId)` | 仲裁支持发单人：DISPUTED → REFUNDED |
| `doRefund(...)`（private） | 事务消息 + 程序化事务 |
| `doRefundInTx(...)` | 托管 CAS、任务 CAS、两条流水 |

**关键源码（一个注释里的真实 bug）**

~~~java
// from 必须在流转前取——领域对象流转后 status() 已是 CANCELLED，
// 那时再记日志会得到 CANCELLED->CANCELLED 的脏记录（P4 实测）
ErrandStatus from = errand.status();
errand.cancelByPublisher(publisherId, errand.version());
return doRefund(errandId, errand, publisherId, from, CANCELLED, errand.version() - 1);
~~~

### 4.3 `DisputeErrandUseCase` · 发起争议用例（class）

**字段**：`CacheEvictSupport`、`ErrandRepository`、`RealtimeNotifier`
**方法**：`@Transactional void raise(long errandId, long operatorId)`
只改状态、**不碰资金**——托管资金在 DISPUTED 原地冻住，是资金安全阀。

### 4.4 `ArbitrateErrandUseCase` · 仲裁用例（class）

**字段**：`ErrandRepository`、`WalletRepository`、`RefundErrandUseCase`、`ArbitrateSettleStep`、`FundAuditPort`、`FundEventPort`、`CacheEvictSupport`、`CreditRepository`、`SnowflakeIdGenerator`、`RealtimeNotifier`、`arbitratorId`（默认 9001）

**枚举**：`Favor { RUNNER, PUBLISHER }`、`Result { SETTLED_TO_RUNNER, REFUNDED_TO_PUBLISHER, ALREADY_DONE, CONFLICT }`

**方法**：`arbitrate(errandId, Favor, operatorId)`

- 校验操作者必须是仲裁员
- 已终态返回 `ALREADY_DONE`（幂等），非 DISPUTED 抛 `NOT_ARBITRABLE`
- PUBLISHER 分支复用 `refundUseCase.arbitrateRefund` + 扣跑腿信用分（`DISPUTE_LOSE`）
- RUNNER 分支走 `settleStep.settleFromDispute`

### 4.5 `ArbitrateSettleStep` · 仲裁结算步骤（class）

**字段**：`ErrandRepository`、`WalletRepository`、`SnowflakeIdGenerator`、`CreditRepository`、`commissionRate`
**方法**：`@Transactional boolean settleFromDispute(errandId, errand, escrow, bizNo, operatorId)`

与 `SettleErrandUseCase.doSettle` 同构：托管 CAS → 任务 CAS（DISPUTED → SETTLED）→ 资金转移 → 三条流水 → 信用 +2。单独成 Bean 同样是为了避开自调用。

---

## 5. 超时流转（4）

### 5.1 `TimeoutTransferUseCase` · 超时流转用例（class）

**字段**：`ErrandRepository`、`CandidateQueuePort`、`GrabSlotPort`、`DelayMessagePort`、`LocalMessageRepository`、`TimeoutTransferStep`、`CacheEvictSupport`、`CreditRepository`、`SnowflakeIdGenerator`、`confirmTimeoutSeconds`（300）、`maxTransferRounds`（5）

**枚举**：`Outcome { TRANSFERRED, REVERTED, SKIPPED }`

**方法**

| 方法 | 说明 |
| --- | --- |
| `handleTimeout(errandId, expectedRound)` | 消费者入口 |
| `scheduleFirstTimeout(errandId, round, version)` | 抢中后登记首轮消息 |
| `revertAndReturnSlot(errand)`（private） | 回退 + 还名额 + 扣信用分 |
| `dispatch(PendingSend)`（private） | 事务提交后发 MQ |

**关键源码（幂等判断）**

~~~java
if (errand.status() != LOCKED || errand.round() != expectedRound) return SKIPPED;
if (errand.round() >= maxTransferRounds) return revertAndReturnSlot(errand);
Optional<Long> next = candidateQueue.pollBest(errandId);
if (next.isPresent()) {
    var result = step.transfer(errand, next.get(), confirmTimeoutSeconds);
    if (!result.applied()) { candidateQueue.offer(...); return SKIPPED; }
    dispatch(result.pendingSend());
    return TRANSFERRED;
}
return revertAndReturnSlot(errand);
~~~

### 5.2 `TimeoutTransferStep` · 超时流转事务步骤（class）

**字段**：`ErrandRepository`、`LocalMessageRepository`、`SnowflakeIdGenerator`
**record**：`StepResult(boolean applied, PendingSend pendingSend)`、`PendingSend(topic, msgKey, payload, deliverAt)`
**方法**

| 方法 | 说明 |
| --- | --- |
| `@Transactional StepResult transfer(errand, nextRunner, timeoutSeconds)` | CAS 换人 + 日志 + 下一轮消息 |
| `@Transactional StepResult revert(errand)` | CAS 回退 + 日志 |
| `@Transactional PendingSend enqueueTimeout(errandId, round, version, seconds)` | 事务内登记本地消息 |

**要点**：事务内只登记消息，**绝不在事务里发 MQ**；发送交给调用方在提交后做。

### 5.3 `TimeoutPolicy` · 超时策略（class，已废弃）

`@Deprecated(since = "P3")`。常量与两个方法全部委托给 `DelayTaskPolicy`，只为兼容旧引用。

### 5.4 `DelayTaskPolicy` · 延迟任务策略（class）

**常量**：`TOPIC_CONFIRM_TIMEOUT = "errand-confirm-timeout"`、`TOPIC_AUTO_SETTLE = "errand-auto-settle"`
**方法**

| 方法 | 生成 |
| --- | --- |
| `timeoutKey(errandId, round)` | `timeout:{id}:{round}` |
| `autoSettleKey(errandId)` | `autosettle:{id}` |
| `payload(errandId, round, version)` | `{"errandId":..,"round":..,"version":..}` |
| `autoSettlePayload(errandId)` | `{"errandId":..}` |

**要点**：两类延迟任务的 key 前缀必须不同，否则本地消息表唯一索引会互相顶掉。

---

## 6. 缓存、查询与支撑（11）

### 6.1 `CacheEvictSupport` · 缓存失效支撑（class）

**字段**：`ErrandCachePort cache`、`CacheEvictDelayPort delayPort`、`long doubleDeleteMillis`（500）、`String evictionOrder`（AFTER_COMMIT）、`boolean doubleDeleteEnabled`、`AtomicLong evictFailureCount`

**方法**

| 方法 | 说明 |
| --- | --- |
| `evictAfterCommit(long errandId)` | 事务提交后删 + 登记 500ms 双删 |
| `doEvict / scheduleDoubleDelete`（private） | 实际删除与登记 |
| `evictFailureCount()` | 失败计数（监控用） |

**要点**：`BEFORE_COMMIT` 是刻意留的错误实现开关，供一致性对照实验压出"缓存长期旧值"。

### 6.2 `GetErrandDetailUseCase` · 任务详情查询（class）

**字段**：`ErrandRepository`、`ErrandCachePort`、`AtomicLong dbLoadCount/cacheHitCount/requestCount`

**方法**：`Optional<String> detailJson(long errandId)`；内部 `reload`、`toJson`、`hitRate()`、`resetStats()`

读路径：缓存命中未过期 → 返回；逻辑过期 → 抢重建权，抢不到返回旧值；未命中 → 布隆判存在 → 回源 → 回填（查不到写空值缓存）。

### 6.3 `QueryErrandListUseCase` · 任务列表查询（class）

**字段**：`ErrandQueryPort`、`ErrandRepository`
**方法**：`list`、`listByCursor`、`myPublished`、`myGrabbed`、`statusLog`；私有 `decode`/`encode` 处理 Base64 游标。

**要点**：默认只查 PUBLISHED；size 夹在 1..50。

### 6.4 `CursorPage` · 游标分页结果（record）

`record CursorPage<T>(List<T> items, String nextCursor)`。`nextCursor == null` 表示最后一页。

### 6.5 `ErrandActionResolver` · 可执行动作解析器（class）

**字段**：`long arbitratorId`（默认 9001）
**方法**：`Resolved resolve(Errand errand, long viewerId)`，返回 `Resolved(List<String> actions, String role)`

按状态 + 身份计算 `GRAB / CONFIRM / PICKUP / DELIVER / SETTLE / CANCEL / DISPUTE / ARBITRATE`；规则复用 `allowedTargets()`，但比状态机更严（例如 LOCKED 不给 CANCEL）。

### 6.6 `QueryCreditUseCase` · 信用查询（class）

字段：`CreditRepository`、`CreditRankingPort`
方法：`myScore(userId)`、`myEvents(userId)`（30 天窗口，50 条）、`ranking(campusId, limit)`（1..100）

### 6.7 `QueryWalletUseCase` · 钱包查询（class）

字段：`WalletQueryPort`
方法：`balance(userId)`（无账户返回 0/0）、`ledger(userId, page, size)`（size 1..50）

### 6.8 `QueryNotificationUseCase` · 站内消息查询（class）

字段：`NotificationQueryPort`
方法：`list(userId, page, size)`、`unread(userId)`

### 6.9 `CacheConsistencyCheckUseCase` · 缓存一致性校验（class）

**字段**：`ErrandQueryPort`、`ErrandRepository`、`ErrandCachePort`、`SyncDiffRepository`、`int sampleSize`（100）

**方法**：`int runOnce()`；私有 `compareAndFix`、`extract`

**关键源码（修正手段是删缓存，不是改缓存）**

~~~java
if (!db.status().name().equals(cacheStatus)) { record(...); diffs++; }
if (!String.valueOf(db.version()).equals(cacheVersion)) { record(...); diffs++; }
if (!String.valueOf(db.reward().cents()).equals(cacheReward)) { record(...); diffs++; }
if (diffs > 0) cache.evict(id);   // 下次读回源拿新值
~~~

### 6.10 `BloomRebuildUseCase` · 布隆重建（class）

字段：`ErrandQueryPort`、`ErrandCachePort`
方法：`int rebuild()`——取全量 id（`sampleIds(Integer.MAX_VALUE)`）逐个 `registerExisting`，返回灌入数量。

### 6.11 `CreditCalibrationUseCase` · 信用分校准（class）

字段：`CreditRepository`
方法：`int calibrate(int windowDays, int limit)`——按 30 天窗口重算快照，返回修正人数。

---

## 7. 五个反复出现的模式

| 模式 | 例子 | 为什么 |
| --- | --- | --- |
| **事务拆 Bean** | `GrabTransactionalStep`、`TimeoutTransferStep`、`ArbitrateSettleStep` | 避开 `@Transactional` 同类自调用失效 |
| **程序化事务** | `SettleErrandUseCase`、`RefundErrandUseCase` 的 `transactionTemplate` | lambda 里的 `this` 不是代理，注解不生效 |
| **提交后副作用** | `evictAfterCommit`、`dispatch`、`notifier` | 外部 IO 不进事务，失败不回滚业务 |
| **CAS 用流转前版本** | `errand.version() - 1` | 领域方法内部已 `version++` |
| **幂等靠唯一索引** | 结算/退款/仲裁/超时消息 | 先查再插有竞态，唯一索引是原子的 |

## 8. 自测

- [ ] 为什么 `ConfirmErrandUseCase` 里 CAS 用的是 `errand.version() - 1`？
- [ ] `SettleErrandUseCase` 为什么不用 `@Transactional` 而用 `TransactionTemplate`？
- [ ] `DeliverErrandUseCase` 为什么必须登记本地消息表，而不能直接发 MQ？
- [ ] `TimeoutTransferStep` 为什么把"发 MQ"留到事务外？
- [ ] `CacheEvictSupport` 的 `BEFORE_COMMIT` 开关是干什么用的？
- [ ] `ErrandActionResolver` 为什么复用 `allowedTargets()` 而不是自己写 if-else？
