# day-03 · domain 层源码详解：35 个文件逐个拆

> 阅读范围：`dash-domain` 全部 7 个包、35 个类/接口/枚举/record。
> 每条包含：类型、字段、构造器/工厂、方法签名、职责、关键点。
> 图例：**class** = 聚合根/可变对象；**record** = 值对象；**enum** = 规则/编码；**interface** = 端口。

## 0. 总览

| 包 | 文件数 | 内容 |
| --- | ---: | --- |
| `auth` | 2 | 认证端口（Session / JWT+refresh） |
| `credit` | 5 | 信用分聚合、事件、类型、仓储、排行榜 |
| `errand` | 10 | 任务聚合、状态机、读写端口、缓存/消息/一致性端口 |
| `grab` | 6 | 抢单记录、Lua 返回码、名额/记录/候选/限流端口 |
| `wallet` | 8 | 账户、托管单、流水、资金仓储/查询/审计/事件 |
| `notify` | 3 | 站内消息读写、实时推送 |
| `recon` | 1 | 三层对账查询契约 |

---

## 1. auth（2）

### 1.1 `AuthPort`（interface）

**功能**：认证端口，只定义"登录 / 换 userId / 登出"，不关心底层是 Redis Session 还是 JWT。

| 方法 | 返回 | 说明 |
| --- | --- | --- |
| `login(long userId)` | `String` | 签发 token；同一用户可有多个 token（多端） |
| `resolve(String token)` | `Optional<Long>` | token → userId；不存在/过期返回 empty |
| `logout(String token)` | `void` | 删除 token |

### 1.2 `RefreshTokenPort`（interface，继承 AuthPort）

**功能**：在 AuthPort 上加 refresh token 能力，用于 JWT 模式下续期与吊销。

| 方法 | 返回 | 说明 |
| --- | --- | --- |
| `loginWithRefresh(long userId)` | `TokenPair` | 同时发 access + refresh |
| `refresh(String refreshToken)` | `Optional<TokenPair>` | 用 refresh 换新的一对 |
| `revokeRefresh(String refreshToken)` | `void` | 主动吊销 |

嵌套 **record**：`TokenPair(String accessToken, String refreshToken)`。

---

## 2. credit（5）

### 2.1 `CreditScore`（class，聚合根）

**功能**：用户信用分快照。分数由事件累加而来，但单独存一份，避免每次查询都 SUM 流水。

**字段**

| 字段 | 类型 | 说明 |
| --- | --- | --- |
| `userId` | `final long` | 主键 |
| `score` | `int` | 当前分，范围 [0, 100] |
| `version` | `long` | 乐观锁版本 |

**构造器**

~~~java
public CreditScore(long userId, int score, long version)
public static CreditScore initial(long userId)   // score = CreditEventType.BASE_SCORE (60)
~~~

**方法**

| 方法 | 返回 | 说明 |
| --- | --- | --- |
| `apply(int delta)` | `void` | `score = clamp(score + delta, 0, 100)`；`version++` |
| `userId()` / `score()` / `version()` | — | 只读访问器 |

**关键点**：上下限都夹住——下限防"一次失误打进负分深渊"，上限防"刷单攒分"。信用分的价值在约束，不在激励。

### 2.2 `CreditEvent`（record，值对象）

**功能**：一次加减分的事实记录，只增不改。

**组件**：`long id, String bizNo, long userId, CreditEventType type, int delta, String refType, long refId, Instant createdAt`

**静态工厂（幂等键）**

| 方法 | 生成 |
| --- | --- |
| `settleBizNo(errandId)` | `settle:{errandId}` |
| `revertBizNo(errandId, round)` | `revert:{errandId}:{round}` |
| `cancelAfterGrabBizNo(errandId)` | `cancelgrab:{errandId}` |
| `disputeLoseBizNo(errandId, loserId)` | `disputelose:{errandId}:{loserId}` |

### 2.3 `CreditEventType`（enum）

**功能**：事件类型 + 分值规则 + 全局常量。

| 常量 | delta | 说明 |
| --- | ---: | --- |
| `SETTLE` | +2 | 完成结算 |
| `GRAB_TIMEOUT_REVERT` | -5 | 抢中后超时未确认 |
| `CANCEL_AFTER_GRAB` | -10 | 被抢单后取消 |
| `DELIVERY_LATE` | -3 | 送达超时 |
| `DISPUTE_LOSE` | -8 | 争议败诉 |

**字段**：`private final int delta`、`private final String description`
**构造**：`CreditEventType(int delta, String description)`
**方法**：`delta()`、`description()`
**常量**：`WINDOW_DAYS = 30`、`BASE_SCORE = 60`、`MIN_SCORE = 0`、`MAX_SCORE = 100`

### 2.4 `CreditRepository`（interface）

| 方法 | 返回 | 说明 |
| --- | --- | --- |
| `scoreOf(long userId)` | `int` | 查分数；没有记录返回初始 60（不落库） |
| `find(long userId)` | `Optional<CreditScore>` | 查聚合 |
| `applyEvent(CreditEvent)` | `boolean` | 插事件 + 更新快照；`biz_no` 冲突返回 false |
| `recentEvents(userId, days, limit)` | `List<CreditEvent>` | 最近事件 |
| `windowDelta(userId, windowDays)` | `int` | 窗口内分值总和（校准用） |
| `calibrateScores(windowDays, limit)` | `int` | 按窗口重算快照，返回修正人数 |

### 2.5 `CreditRankingPort`（interface）

| 方法 | 返回 | 说明 |
| --- | --- | --- |
| `update(campusId, userId, score)` | `void` | ZADD，天然幂等 |
| `top(campusId, limit)` | `List<Entry>` | ZREVRANGE 取 Top N |

嵌套 **record**：`Entry(long userId, int score)`。

---

## 3. errand（10）

### 3.1 `Errand`（class，聚合根）

**功能**：任务聚合。所有状态变更必须走 `transitTo`，没有 setter。

**字段**

| 字段 | 类型 | 说明 |
| --- | --- | --- |
| `id` | `final long` | 雪花 ID |
| `campusId` | `final long` | 校区，分片键 |
| `publisherId` | `final long` | 发单人 |
| `type` | `final ErrandType` | 任务类型 |
| `title` | `final String` | 标题 |
| `reward` | `final Money` | 悬赏金额 |
| `slotTotal` | `final int` | 名额总数 |
| `grabberId` | `Long` | 当前抢中者，可空 |
| `status` | `ErrandStatus` | 状态 |
| `slotTaken` | `int` | 已占名额 |
| `round` | `int` | 流转轮次 |
| `version` | `long` | 乐观锁版本 |
| `lockedAt` | `Instant` | 本轮抢中时间（超时扫描用） |
| `deliveredAt` | `Instant` | 送达时间（自动结算扫描用） |
| `changes` | `final List<ErrandStatusChanged>` | 本次加载期间的状态变更 |
| `SYSTEM_OPERATOR` | `static final long` | = -1，系统操作者 |

**构造器**：私有 `Errand(...)`，只允许通过工厂创建。

**工厂方法**

| 方法 | 说明 |
| --- | --- |
| `draft(id, campusId, publisherId, type, title, reward, slotTotal)` | 新建草稿；校验 reward 非 0、slotTotal ≥ 1；初始 DRAFT/0/0/0 |
| `rehydrate(...13 参数)` | 从存储重建（不带 deliveredAt） |
| `rehydrate(...14 参数)` | 从存储重建（带 deliveredAt） |

**业务方法**

| 方法 | 流转 | 额外动作 |
| --- | --- | --- |
| `transitTo(target, expectedVersion)` | 任意合法流转 | 校验 version → 校验 allowedTargets → 改状态、version++、记 changes |
| `publish(expectedVersion)` | DRAFT → PUBLISHED | — |
| `lockBy(runnerId, expectedVersion, now)` | PUBLISHED → LOCKED | 校验可抢、名额未满；写 grabberId、slotTaken++、lockedAt |
| `acceptByRunner(runnerId, version)` | LOCKED → ACCEPTED | 校验是当前抢中者 |
| `transferToNextRunner(next, version, now)` | LOCKED → LOCKED | grabberId 换人、**round++**、lockedAt 更新 |
| `revertToPublished(version)` | LOCKED → PUBLISHED | grabberId 清空、slotTaken--、**round++**、lockedAt 清空 |
| `pickUp(runnerId, version)` | ACCEPTED → PICKED_UP | 校验当前跑腿 |
| `deliver(runnerId, version, now)` | PICKED_UP → DELIVERED | 写 deliveredAt |
| `settle(operatorId, version)` | DELIVERED → SETTLED | operator 必须是发单人或 SYSTEM_OPERATOR |
| `cancelByPublisher(operatorId, version)` | DRAFT/PUBLISHED → CANCELLED | 校验发单人；slotTaken = 0 |
| `raiseDispute(operatorId, version)` | ACCEPTED/PICKED_UP/DELIVERED → DISPUTED | 双方均可发起 |
| `arbitrateToRunner(version)` | DISPUTED → SETTLED | 必须 DISPUTED |
| `arbitrateToPublisher(version)` | DISPUTED → REFUNDED | 必须 DISPUTED |

**查询辅助**

| 方法 | 返回 | 说明 |
| --- | --- | --- |
| `autoSettleDue(now, seconds)` | `boolean` | DELIVERED 且超过窗口 |
| `confirmTimeout(now, seconds)` | `boolean` | LOCKED 且超过窗口 |
| `slotAvailable()` | `boolean` | slotTaken < slotTotal |
| 各 getter | — | `changes()` 返回 `List.copyOf` 防外部改 |

**嵌套 record**：`ErrandStatusChanged(long errandId, ErrandStatus from, ErrandStatus to, int round)`

### 3.2 `ErrandStatus`（enum）

**功能**：状态机。11 个状态 + 允许流转集合。

| 常量 | 允许去向 |
| --- | --- |
| DRAFT | PUBLISHED, CANCELLED |
| PUBLISHED | LOCKED, CANCELLED |
| LOCKED | ACCEPTED, **LOCKED（自环）**, PUBLISHED, CANCELLED |
| ACCEPTED | PICKED_UP, DISPUTED, CANCELLED |
| PICKED_UP | DELIVERED, DISPUTED |
| DELIVERED | SETTLED, DISPUTED |
| DISPUTED | SETTLED, REFUNDED |
| SETTLED | CLOSED |
| REFUNDED | CLOSED |
| CLOSED / CANCELLED | 终态 |

**方法**：`allowedTargets()` 返回 `EnumSet`；`canTransitTo(target)`；`grabbable()` 仅 PUBLISHED 为 true。

### 3.3 `ErrandType`（enum）

`DELIVERY`（代拿）、`BUY`（代买）、`QUEUE`（代排队）、`OTHER`。纯分类，无字段无方法。

### 3.4 `ErrandRepository`（interface，写端口）

| 方法 | 返回 | 说明 |
| --- | --- | --- |
| `insert(Errand)` | `void` | 新建 |
| `findById(long)` | `Optional<Errand>` | 查聚合 |
| `casLockForRunner(errandId, runnerId, expectedVersion)` | `int` | PUBLISHED→LOCKED，slot_taken+1 |
| `casPublish(errandId, expectedVersion)` | `int` | DRAFT→PUBLISHED |
| `appendStatusLog(errandId, from, to, round, operatorId)` | `void` | 写状态日志 |
| `casAccept(errandId, runnerId, version)` | `int` | LOCKED→ACCEPTED，校验 grabber_id |
| `casTransferToNext(errandId, nextRunnerId, version, expectedRound)` | `int` | LOCKED→LOCKED，round+1 |
| `casRevertToPublished(errandId, version, expectedRound)` | `int` | LOCKED→PUBLISHED，slot_taken--、round+1 |
| `findConfirmTimeout(timeoutSeconds, limit)` | `List<Errand>` | 兜底扫描 |
| `casPickUp(errandId, runnerId, version)` | `int` | ACCEPTED→PICKED_UP |
| `casDeliver(errandId, runnerId, version)` | `int` | PICKED_UP→DELIVERED，写 delivered_at |
| `casSettle(errandId, version)` | `int` | DELIVERED→SETTLED（版本号参数实际不参与 SQL） |
| `casRefundFromDispute(errandId, version)` | `int` | DISPUTED→REFUNDED |
| `casCancel(errandId, version)` | `int` | DRAFT/PUBLISHED→CANCELLED |
| `casDispute(errandId, version)` | `int` | →DISPUTED |
| `casSettleFromDispute(errandId, version)` | `int` | DISPUTED→SETTLED |
| `findAutoSettleDue(autoSettleSeconds, limit)` | `List<Errand>` | 自动结算扫描 |

**关键点**：CAS 方法全部返回 `int`，调用方用 `affectedRows` 判定，不额外查询。

### 3.5 `ErrandQueryPort`（interface，读端口）

| 方法 | 返回 | 说明 |
| --- | --- | --- |
| `list(campusId, status, page, size)` | `List<Errand>` | 广场分页 |
| `listByCursor(campusId, status, beforeCreatedAt, beforeId, size)` | `List<CursorItem>` | 游标分页 |
| `listByPublisher(publisherId, page, size)` | `List<Errand>` | 我发布的 |
| `listByRunner(runnerId, page, size)` | `List<Errand>` | 我抢的 |
| `statusLog(campusId, errandId)` | `List<StatusChange>` | 时间线 |
| `sampleIds(limit)` | `List<Long>` | 一致性校验抽样 |
| `countOngoingByRunner(runnerId)` | `int` | 在途任务数（资格校验） |

嵌套 record：`StatusChange(Instant time, String from, String to, int round, long operatorId)`、`CursorItem(Errand errand, Instant createdAt)`。

### 3.6 `ErrandCachePort`（interface）

| 方法 | 返回 | 说明 |
| --- | --- | --- |
| `get(errandId)` | `Optional<CachedErrand>` | 命中可能是逻辑过期的旧值 |
| `put(errandId, payloadJson)` | `void` | 回填全部分片 |
| `putEmpty(errandId)` | `void` | 空值缓存（防穿透） |
| `evict(errandId)` | `void` | 删全部分片 |
| `tryAcquireRebuild(errandId)` | `boolean` | 抢重建权（防击穿） |
| `releaseRebuild(errandId)` | `void` | 释放 |
| `mightExist(errandId)` | `boolean` | 布隆判存在 |
| `registerExisting(errandId)` | `void` | 登记布隆 |

嵌套 record：`CachedErrand(String payloadJson, long logicalExpireAt, boolean isEmpty)`，方法 `logicallyExpired()`。

### 3.7 `DelayMessagePort`（interface）

`void send(String topic, String msgKey, String payload, Instant deliverAt)`

### 3.8 `CacheEvictDelayPort`（interface）

常量 `TOPIC_CACHE_EVICT = "errand-cache-evict"`；方法 `void scheduleEvict(long errandId, Instant deliverAt)`。

### 3.9 `LocalMessageRepository`（interface）

| 方法 | 返回 | 说明 |
| --- | --- | --- |
| `enqueue(id, msgKey, topic, payload, deliverAt)` | `boolean` | 事务内登记；唯一键冲突返回 false |
| `markSent(msgKey)` | `void` | 发送成功 |
| `findPending(limit)` | `List<PendingMessage>` | 待重发 |
| `markRetry(msgKey, maxRetry)` | `void` | 退避重试，超限转 DEAD |

嵌套 record：`PendingMessage(long id, String msgKey, String topic, String payload, Instant deliverAt, int retryCount)`。

### 3.10 `SyncDiffRepository`（interface）

`void record(Instant checkTime, long errandId, String field, String dbValue, String cacheValue, boolean fixed)`；`long countSince(Instant since)`。

---

## 4. grab（6）

### 4.1 `GrabRecord`（record，值对象）

**组件**：`long id, long campusId, long errandId, long runnerId, int seq, int round, GrabResultType result, Instant createdAt`

**工厂**

| 方法 | 说明 |
| --- | --- |
| `grabbed(id, campusId, errandId, runnerId, seq, round)` | seq 从 1 开始 |
| `candidate(id, campusId, errandId, runnerId, round)` | seq = 0 |

**嵌套 enum**：`GrabResultType { GRABBED, CANDIDATE, EXPIRED }`

**关键点**：两个唯一索引 `(errand_id, round, seq)`、`(errand_id, round, runner_id)`，`round` 必须进索引，否则多轮流转会互相挡住。

### 4.2 `SlotOutcome`（enum）

| 常量 | code | 含义 |
| --- | ---: | --- |
| `ACQUIRED` | 1 | 占位成功 |
| `SLOT_FULL` | 0 | 名额已满 |
| `ALREADY_GRABBED` | -1 | 同一用户重复抢 |
| `DUPLICATE_REQUEST` | -2 | 同一 requestId 重放 |
| `NOT_GRABBABLE` | -3 | 任务不可抢 |

**字段**：`private final long code`；**方法**：`code()`、`static fromCode(long)`（未知码抛 IllegalArgumentException）。

### 4.3 `GrabSlotPort`（interface）

| 方法 | 返回 | 说明 |
| --- | --- | --- |
| `tryAcquire(errandId, runnerId, requestId)` | `SlotOutcome` | Lua 原子判定 |
| `rollback(errandId, runnerId, requestId)` | `void` | 补偿回滚名额 |
| `initSlot(errandId, slotTotal, ttlSeconds)` | `void` | 发布时初始化 |
| `remainingSlot(errandId)` | `long` | 压测校验用 |

### 4.4 `GrabRecordRepository`（interface）

`void insert(GrabRecord record)`（撞唯一索引必须抛异常，禁止 INSERT IGNORE）；`int countGrabbed(long campusId, long errandId)`。

### 4.5 `CandidateQueuePort`（interface）

`void offer(errandId, runnerId, score)`；`Optional<Long> pollBest(errandId)`；`long size(errandId)`。

### 4.6 `GrabRateLimiterPort`（interface）

`boolean tryPass(long errandId, long runnerId)`。

---

## 5. wallet（8）

### 5.1 `AccountType`（enum）

`USER`（用户）、`ESCROW`（平台托管户）、`COMMISSION`（平台佣金户）。三类账户之间搬钱，总额恒定。

### 5.2 `WalletAccount`（class，聚合根）

**字段**

| 字段 | 类型 | 说明 |
| --- | --- | --- |
| `id` | `final long` | 账户 ID |
| `ownerId` | `final long` | 所属用户；系统户用 -1/-2 |
| `type` | `final AccountType` | 账户类型 |
| `available` | `Money` | 可用余额 |
| `frozen` | `Money` | 冻结余额（预留给提现） |
| `version` | `long` | 乐观锁 |

**构造器**：`public WalletAccount(long id, long ownerId, AccountType type, Money available, Money frozen, long version)`

**方法**

| 方法 | 返回 | 说明 |
| --- | --- | --- |
| `transferOut(Money amount)` | `void` | 校验余额 → `available.minus` → `version++` |
| `transferIn(Money amount)` | `void` | `available.plus` → `version++` |
| `total()` | `Money` | `available + frozen` |
| `id()` / `ownerId()` / `type()` / `available()` / `frozen()` / `version()` | — | 只读 |

**关键点**：托管是"转移"不是"冻结"（P3 修正），否则同一笔钱在快照里算两次。

### 5.3 `EscrowOrder`（record，值对象）

**组件**：`long id, long campusId, long errandId, long publisherId, Money amount, EscrowStatus status`

**工厂/方法**

| 方法 | 说明 |
| --- | --- |
| `static held(id, campusId, errandId, publisherId, amount)` | 新建 HELD |
| `release()` | HELD → RELEASED，返回新对象 |
| `refund()` | HELD → REFUNDED，返回新对象 |
| `requireHeld(action)` | 私有，非 HELD 抛 ESCROW_NOT_HELD |

**嵌套 enum**：`EscrowStatus { HELD, RELEASED, REFUNDED }`。RELEASED/REFUNDED 是终态，保证一笔钱只有一个去向。

### 5.4 `LedgerEntry`（record，值对象）

**组件**：`long id, String bizNo, long accountId, long userId, Direction direction, Money amount, Money balanceAfter, RefType refType, long refId`

**嵌套 enum**：`Direction { DEBIT, CREDIT }`、`RefType { ESCROW, SETTLE, REFUND, RECHARGE, WITHDRAW }`

**幂等键工厂**：`escrowBizNo(errandId)`、`settleBizNo(errandId)`、`refundBizNo(errandId)`。

**关键点**：一笔业务一借一贷两条，金额相等 → `SUM(DEBIT) == SUM(CREDIT)` 可验证。

### 5.5 `WalletRepository`（interface，写端口）

| 方法 | 返回 | 说明 |
| --- | --- | --- |
| `findByOwner(ownerId, type)` | `Optional<WalletAccount>` | 按归属查 |
| `findById(accountId)` | `Optional<WalletAccount>` | 按 ID 查 |
| `casDebit(accountId, amount)` | `int` | `WHERE available >= ?` 条件扣款 |
| `casCredit(accountId, amount)` | `int` | 收款 |
| `insertLedger(LedgerEntry)` | `void` | 唯一索引冲突抛异常 |
| `insertEscrow(EscrowOrder)` | `void` | 一个任务一张托管单 |
| `escrowExists(campusId, errandId)` | `boolean` | 幂等前置检查 |
| `findEscrowByErrandId(campusId, errandId)` | `Optional<EscrowOrder>` | 查托管单 |
| `casEscrowStatus(campusId, errandId, from, to)` | `int` | 并发结算第一道闸门 |
| `ledgerExists(bizNo)` | `boolean` | 事务消息回查 |

### 5.6 `WalletQueryPort`（interface，读端口）

`Optional<BalanceView> findBalance(long ownerId)`；`List<LedgerView> ledger(long ownerId, int page, int size)`。
嵌套 record：`BalanceView(long availableCents, long frozenCents)`、`LedgerView(Instant time, String direction, long amountCents, String refType, long refId, String bizNo)`。

### 5.7 `FundAuditPort`（interface）

`void record(String bizNo, String action, long errandId, long operatorId, String detailJson, boolean success, String message)`——实现必须 REQUIRES_NEW。

### 5.8 `FundEventPort`（interface）

`boolean publishInTransaction(FundEvent event, LocalWork localWork)`
嵌套 record：`FundEvent(String bizNo, String type, long errandId, long publisherId, long runnerId, long amountCents, long commissionCents)`
函数式接口：`LocalWork { boolean execute(); }`

---

## 6. notify（3）

### 6.1 `NotificationRepository`（interface）

`boolean insertIfAbsent(long id, String msgKey, long userId, long errandId, String type, String content)`——`(msg_key, user_id)` 唯一，重复消费返回 false。

### 6.2 `NotificationQueryPort`（interface）

`List<NotificationView> list(long userId, int page, int size)`；`int unreadCount(long userId)`。
嵌套 record：`NotificationView(long id, long errandId, String type, String content, Instant time)`。

### 6.3 `RealtimeNotifier`（interface）

| 方法 | 说明 |
| --- | --- |
| `errandStatusChanged(errandId, publisherId, grabberId, status, round)` | 推给发单人与抢中者 |
| `notificationArrived(userId, errandId, type, content)` | 站内消息到达 |
| `creditChanged(userId, newScore, delta, reason)` | 信用分变化 |

**关键点**：推送失败不影响业务；worker 进程推不到 app 的连接（跨进程），前端轮询兜底。

---

## 7. recon（1）

### 7.1 `ReconRepository`（interface）

| 方法 | 返回 | 说明 |
| --- | --- | --- |
| `debitMinusCredit()` | `long` | L1：借贷差额，正常 0 |
| `findSnapshotDiffs()` | `List<AccountDiff>` | L2：快照 ≠ 流水净额 |
| `findEscrowClosureDiffs()` | `List<EscrowDiff>` | L3：托管不闭环 |
| `recordDiff(date, checkType, subject, expected, actual, detail)` | `void` | 写差异（唯一索引幂等） |
| `countDiffs(LocalDate date)` | `int` | 当日差异数 |

嵌套 record：`AccountDiff(long accountId, long ownerId, long snapshotTotal, long ledgerNet)`、`EscrowDiff(long errandId, String escrowStatus, String reason)`。

---

## 8. 横向对比：什么时候用哪种类型

| 场景 | 选择 | 依据 |
| --- | --- | --- |
| 有身份、有生命周期、会被修改 | **class**（Errand、WalletAccount、CreditScore） | 状态要变，需要校验与版本号 |
| 无身份、不可变、按值比较 | **record**（Money、LedgerEntry、EscrowOrder、GrabRecord、CreditEvent） | 值对象，自动 equals/hashCode |
| 固定集合 + 规则/编码 | **enum**（ErrandStatus、CreditEventType、SlotOutcome） | 穷尽、可读、可带字段 |
| 不关心实现的能力 | **interface**（35 个里的 19 个） | 依赖倒置，infrastructure 实现 |

## 9. 端口一览（19 个）

| 包 | 端口 | 方向 |
| --- | --- | --- |
| auth | AuthPort、RefreshTokenPort | 认证 |
| credit | CreditRepository（写）、CreditRankingPort（读/排行） | 信用 |
| errand | ErrandRepository（写）、ErrandQueryPort（读）、ErrandCachePort（缓存）、DelayMessagePort（延迟消息）、CacheEvictDelayPort（延迟双删）、LocalMessageRepository（本地消息表）、SyncDiffRepository（一致性差异） | 任务 |
| grab | GrabSlotPort（名额）、GrabRecordRepository（记录）、CandidateQueuePort（候选）、GrabRateLimiterPort（限流） | 抢单 |
| wallet | WalletRepository（写）、WalletQueryPort（读）、FundAuditPort（审计）、FundEventPort（事务消息） | 资金 |
| notify | NotificationRepository（写）、NotificationQueryPort（读）、RealtimeNotifier（推送） | 通知 |
| recon | ReconRepository（查询） | 对账 |

## 10. 自测

- [ ] Errand 的 14 个字段里，哪些是 final？为什么 grabberId 不是？
- [ ] CreditScore.apply 为什么要同时夹上下限？
- [ ] EscrowOrder.release() 为什么返回新对象，而 Errand.transitTo 是原地修改？
- [ ] GrabRecord 的两个唯一索引分别对应哪条不变式？
- [ ] WalletRepository 为什么没有 update 方法？
- [ ] FundAuditPort 为什么必须 REQUIRES_NEW？
