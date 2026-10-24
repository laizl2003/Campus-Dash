# day-03 · domain 层源码通读（一）：auth / credit / errand / grab

> 阅读范围：`dash-domain` 的 4 个包、共 23 个文件（domain 总计 35 个，进度 66%）。
> 目标：读完之后能回答——领域层提供哪些能力、每个端口为什么存在、哪些是值对象、哪些是聚合根。

## 1. 进度地图

| 包 | 文件数 | 状态 | 一句话 |
| --- | ---: | --- | --- |
| `auth` | 2 | ✅ 已读 | 认证端口：Session 与 JWT+refresh 两种形态 |
| `credit` | 5 | ✅ 已读 | 信用分聚合 + 事件流水 + 排行榜端口 |
| `errand` | 10 | ✅ 已读 | 任务聚合、状态机、读写端口、缓存/消息/一致性端口 |
| `grab` | 6 | ✅ 已读 | 抢单记录、Lua 返回码、名额/候选/限流端口 |
| `wallet` | 8 | ⬜ 下一站 | 账户、托管单、流水、资金事件 |
| `notify` | 3 | ⬜ 下一站 | 站内消息读写 + 实时推送 |
| `recon` | 1 | ⬜ 下一站 | 三层对账查询 |

## 2. auth：两种认证形态的抽象

| 类型 | 名称 | 职责 | 关键点 |
| --- | --- | --- | --- |
| interface | `AuthPort` | `login` / `resolve` / `logout` | 同一用户可持有多个 token（多端）；`resolve` 返回 `Optional`，过期就是 empty |
| interface | `RefreshTokenPort extends AuthPort` | 增加 `loginWithRefresh` / `refresh` / `revokeRefresh` | 用继承表达"JWT 是 Session 的超集" |
| record | `TokenPair` | `accessToken + refreshToken` | 值对象，嵌在端口里 |

设计点：**领域层不定义"用 Redis 还是 JWT"**，只定义"能不能登录、能不能换出 userId、能不能登出"。P4 用 Redis Session，P5 加 JWT，只是换适配器（`RedisAuthAdapter` / `JwtAuthAdapter`）。

## 3. credit：分数是聚合，事件是事实

| 类型 | 名称 | 职责 | 关键点 |
| --- | --- | --- | --- |
| class | `CreditScore` | 信用分聚合 | `apply(delta)` 把结果夹在 `[0,100]`；`version++` 供 CAS；初始分来自枚举常量 |
| record | `CreditEvent` | 一次加减分的事实记录 | 只增不改；4 个 `xxxBizNo()` 工厂方法生成幂等键 |
| enum | `CreditEventType` | 事件类型 + 分值规则 | `SETTLE(+2)`、`GRAB_TIMEOUT_REVERT(-5)`…；`WINDOW_DAYS=30`、`BASE_SCORE=60`、`MIN/MAX` 都放这里 |
| interface | `CreditRepository` | `scoreOf` / `find` / `applyEvent` / `recentEvents` / `windowDelta` / `calibrateScores` | `applyEvent` 必须与业务同事务；`biz_no` 冲突返回 false（幂等） |
| interface | `CreditRankingPort` | 校区排行榜 | Redis ZSET：`ZADD` 天然幂等，`ZREVRANGE` 直接出 Top N |
| record | `Entry` | `userId + score` | 排行条目 |

两个值得记住的点：

- **分值写在枚举里而不是配置文件**：不同环境用不同分值会让"信用分"失去确定性。
- **滑动窗口 30 天**：分数不是终身累积，否则用户没有修复路径。

## 4. errand：聚合根 + 两组端口

### 4.1 模型

| 类型 | 名称 | 职责 | 关键点 |
| --- | --- | --- | --- |
| class | `Errand` | 任务聚合根 | 状态无 setter，只能走 `transitTo(target, expectedVersion)`；内存校验 version 后 `version++` |
| enum | `ErrandStatus` | 11 个状态 + 流转规则 | `allowedTargets()` 用 `EnumSet`；`LOCKED -> LOCKED` 是合法自环；`grabbable()` 单独暴露 |
| enum | `ErrandType` | DELIVERY / BUY / QUEUE / OTHER | 纯分类，无行为 |

### 4.2 端口

| 端口 | 方向 | 方法 | 说明 |
| --- | --- | --- | --- |
| `ErrandRepository` | 写 | `insert` / `findById` / `cas*` × 9 / `findConfirmTimeout` / `findAutoSettleDue` | 大量方法返回 `int affectedRows`，让调用方用"影响行数"判定 CAS 成败 |
| `ErrandQueryPort` | 读 | `list` / `listByCursor` / `listByPublisher` / `listByRunner` / `statusLog` / `sampleIds` / `countOngoingByRunner` | 读写分离的最小形态：查询不经过聚合根 |
| `ErrandCachePort` | 缓存 | `get` / `put` / `putEmpty` / `evict` / `tryAcquireRebuild` / `mightExist` / `registerExisting` | 端口里没有"分片 / TTL / Lua"，实现细节全在适配器；`CachedErrand` 带逻辑过期时间 |
| `DelayMessagePort` | 消息 | `send(topic, msgKey, payload, deliverAt)` | 领域层只知道"某时刻收到一条消息"，不认识 RocketMQ |
| `CacheEvictDelayPort` | 消息 | `scheduleEvict(errandId, deliverAt)` | 延迟双删；TOPIC 常量也定义在端口里，避免 infra 反向依赖 application |
| `LocalMessageRepository` | 消息 | `enqueue` / `markSent` / `findPending` / `markRetry` | 本地消息表；`msg_key` 唯一索引就是"幂等三件套"的第三件 |
| `SyncDiffRepository` | 对账 | `record` / `countSince` | 缓存一致性差异，正常必须为空 |

## 5. grab：抢单所需的四类能力

| 类型 | 名称 | 职责 | 关键点 |
| --- | --- | --- | --- |
| record | `GrabRecord` | 一条抢单记录 | 两个唯一索引 `(errand_id, round, seq)` 与 `(errand_id, round, runner_id)`；`round` 必须进索引 |
| enum | `SlotOutcome` | Lua 返回码的 Java 映射 | `ACQUIRED(1)` / `SLOT_FULL(0)` / `ALREADY_GRABBED(-1)` / `DUPLICATE_REQUEST(-2)` / `NOT_GRABBABLE(-3)` |
| interface | `GrabSlotPort` | `tryAcquire` / `rollback` / `initSlot` / `remainingSlot` | 抽象"抢名额"这个语义，实现可以是 Lua、悲观锁、数据库 |
| interface | `GrabRecordRepository` | `insert` / `countGrabbed` | 撞唯一索引**必须抛异常**，绝不能用 `INSERT IGNORE` 吞掉 |
| interface | `CandidateQueuePort` | `offer` / `pollBest` / `size` | ZSET：按 score 取最优，又支持 O(log N) 删除 |
| interface | `GrabRateLimiterPort` | `tryPass(errandId, runnerId)` | 只关心"放不放行"，不绑定 Sentinel |

## 6. 五种反复出现的写法

| 模式 | 例子 | 为什么 |
| --- | --- | --- |
| **端口-适配器（依赖倒置）** | `GrabSlotPort` / `DelayMessagePort` / `ErrandCachePort` | 领域定义"要什么能力"，infra 提供"怎么实现"；换实现不改业务 |
| **值对象用 record** | `CreditEvent` / `GrabRecord` / `LedgerEntry` / `CachedErrand` / `TokenPair` | 无身份、不可变、按值相等 |
| **聚合根用 class** | `Errand` / `CreditScore` / `WalletAccount` | 有生命周期、有状态变更、需要校验 |
| **枚举承载规则** | `ErrandStatus` / `CreditEventType` / `SlotOutcome` | 规则集中、编译期穷尽、可读性好 |
| **幂等键用工厂方法** | `LedgerEntry.settleBizNo` / `CreditEvent.revertBizNo` | 键的格式只在一处定义，避免各处手拼 |

## 7. 读代码时问自己的四个问题

1. 这个接口的方法签名里，有没有泄露实现细节（Redis / MQ / SQL）？
2. 这个方法为什么返回 `int` 而不是 `boolean`？（提示：CAS 要看 affectedRows）
3. 这个字段是业务语义还是技术语义？（`round` vs `version`）
4. 这里该抛异常还是返回错误码？（资金失败抛异常，抢单失败返回码）

## 8. 下一站：wallet / notify / recon（还剩 12 个文件）

建议顺序：

1. `WalletAccount` → `EscrowOrder` → `LedgerEntry`（资金三件套）
2. `WalletRepository` → `WalletQueryPort`（写与读分开）
3. `FundAuditPort` → `FundEventPort`（审计与事务消息）
4. `NotificationRepository` / `NotificationQueryPort` / `RealtimeNotifier`
5. `ReconRepository`（三层对账的查询契约）

读完 domain 之后进入 application 层，第一站 `GrabErrandUseCase`——它是把 domain、infra、事务边界串起来的最短路径。

## 9. 自测

- [ ] 为什么 `Errand` 是 class，而 `EscrowOrder` 是 record？
- [ ] `ErrandRepository` 里大量方法返回 `int`，而不是 `boolean`，为什么？
- [ ] `CreditRepository.scoreOf` 对没有记录的用户返回 60（不落库），这个默认值放在仓储层合适吗？
- [ ] `GrabRecordRepository.insert` 为什么要求"撞唯一索引必须抛异常"？
- [ ] `CacheEvictDelayPort` 为什么定义在 domain，而不是 application？
