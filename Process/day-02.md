# day-02 · worker 接入一键脚本 + 后端架构与并发机制梳理

> 返回目录：[Process.md](../Process.md)

## 1. 今日完成

- `dev.ps1` 接入 worker：默认随 `start` 一起启动，`start -NoWorker` 可跳过
- **改了 worker（或任何被监视模块）的代码，`start` 会自动重新编译打包再启动**，不再需要手动 `mvn install`
- `stop` / `restart` 会一并结束 worker 进程
- `status` 新增「worker 状态」与「编译状态」两块
- 修掉一个自己写出来的 PowerShell bug（见第 3 节，这个坑很典型）
- 系统梳理后端架构、功能清单与并发链路，并把结论写进第 5 节（状态机、五层抢单防线、资金三道闸门、缓存一致性、分片）

## 2. 关键实现

- **worker 没有 HTTP 端口**，没法像后端/前端那样"按端口"管理，只能靠命令行里的 jar 名匹配进程
- `Get-BuildReasons` 决定要不要编译：`dash-worker` 可执行 jar 缺失，或有被监视模块的源码比本地仓库的 jar 新 → `start` 直接自动 `mvn install -DskipTests`
- worker 启动用 **jdk21 的绝对路径**（`$JdkHome\bin\java.exe`），因为 PATH 上的 `java` 还是 jdk11，直接用 `java -jar` 会起不来
- 判断逻辑用时间戳，误判的代价只是多编译一次（无改动时约 5~12 秒），换来的是绝不会跑到旧代码，所以选择"自动重编"而不是"只提醒"

## 3. 踩的坑：PowerShell 函数返回数组会被展开

`Get-WorkerProcesses` 里虽然写了 `@(...)`，但**函数返回时 PowerShell 会把数组展开**。只有一个 worker 进程时，调用方拿到的是单个 `CimInstance` 标量——而 `CimInstance` 的 `.Count` 取不到值（返回空），于是：

- `status` 误报「worker 未运行」（其实在跑）
- `stop` 判定为「worker 本就未运行」，**杀不掉 worker**

多进程时反而是正常数组，所以这个 bug 只在**最常见的情况下**触发，很隐蔽。

排查方式：临时在输出里打印 `count` 和 `GetType().Name`，看到 `type=CimInstance` 但 `count=` 为空，就定位到了。

**修法**：调用方一律写成 `@(Get-WorkerProcesses)`，强制包成数组再用 `.Count`。同样的写法在 `Get-BuildReasons` 的调用处也一并加固了。

## 4. 验证记录（实测，非推测）

| 验证项 | 结果 |
| --- | --- |
| 触碰 worker 源码时间戳（不改内容，`git status` 干净） | 脚本识别出 `需要重新编译：dash-worker` |
| `start` 自动重编 | `BUILD SUCCESS`，Total time 5.27s |
| 三进程启动 | 后端 8080 / 前端 5173 / worker 先后启动，`/api/health` 返回 200 UP |
| **worker 是否真的在消费 MQ** | broker 侧 5 个订阅组挂上：`dash-timeout-consumer`、`dash-autosettle-consumer`、`dash-fund-event-consumer`、`dash-cache-evict-consumer`（worker）+ `dash-fund-event-push`（app 推送） |
| `stop` | 正确输出 `已停止 worker（PID 28356）` |
| 无改动时的 `start` | `源码与本地仓库的 jar 一致，跳过编译` |

## 5. 后端架构与并发机制（系统梳理）

> 口径：本节按 `campus-dash-1` 里的源码逐文件核对（HEAD `b79575f`），不是照 README 转述。压测数字来自仓库内 `bench/reports/`，环境是同机自测（应用、中间件、发压端同一台机器），只做基线，不外推。

### 5.1 八个 Maven 模块与依赖方向

| 模块 | 职责 | 依赖 |
| --- | --- | --- |
| `dash-shared` | `Money`（long 存分）、`Result`、`ErrorCode`、雪花 ID、`BizException` | 无 |
| `dash-domain` | 聚合根、状态机、端口接口（`ports` 包） | 只依赖 shared，零框架 |
| `dash-application` | 用例编排、事务边界、补偿步骤 | domain、shared |
| `dash-infrastructure` | JdbcTemplate 仓储、Redis Lua、RocketMQ、Redisson、Sentinel、ShardingSphere | application、domain |
| `dash-presentation` | Controller、`AuthInterceptor`、WebSocket、Jackson long→string | application |
| `dash-bootstrap` | Spring Boot 装配 + 集成测试 | 全部 |
| `dash-worker` | 独立进程：MQ 消费者 + 定时 job | application、infrastructure |
| `dash-bench` | 压测客户端，不进应用 classpath | 独立 |

设计约束：
- 依赖只能向内；`ArchitectureRuleTest` 用 ArchUnit 锁死（domain 层出现 Spring 注解就构建失败）。
- 端口定义在 domain（`GrabSlotPort` / `ErrandRepository` / `DelayMessagePort`…），实现留在 infrastructure。所以限流器可以一行配置从本地固定窗口切到 Sentinel。
- 事务边界画在应用层，领域对象不知道"事务"这个技术概念。

### 5.2 功能清单（后端实际实现了什么）

| 域 | 功能 | 主要入口 |
| --- | --- | --- |
| 任务 | 发布（同一事务内托管资金）、广场列表、游标分页、详情、我的任务、状态时间线 | `ErrandController` |
| 生命周期 | 抢单、确认、取货、送达、结算、取消、争议、仲裁、退款、超时流转、自动结算 | 9 个 UseCase |
| 资金 | 钱包余额/流水、托管、结算分账、退款、资金审计、三层对账 | `WalletController`、`ReconciliationJob` |
| 信用 | 当前分、事件流水、校区榜、每日校准（30 天滑窗） | `CreditController`、`CreditCalibrationJob` |
| 消息 | 站内消息、未读数、WebSocket 实时推送 | `NotificationController`、`RealtimePushService` |
| 查询加速 | 详情缓存、一致性校验、布隆重建、缓存统计 | `InternalController` + worker |
| 可靠投递 | 本地消息表重发、定时消息、事务消息回查 | `LocalMessageRetryJob` |
| 压测 | 尖峰/阶梯/结算三种客户端，结果写 `bench_run` 留痕 | `dash-bench` |

REST 端点共 27 个：认证 3、任务 13、钱包 2、信用 2、消息 2、健康 1、内部运维 4（缓存统计/重置/校验/布隆重建）。认证走 `Bearer token`，`X-User-Id` 后门由 `dash.auth.allow-header-identity` 控制，生产默认关闭。

### 5.3 状态机：唯一事实来源

~~~
DRAFT --publish(托管)--> PUBLISHED --grab--> LOCKED --confirm--> ACCEPTED
  |                        |                  |                    |
  | cancel                 | cancel           | 超时流转 round+1    | pickup
  v                        v                  v                    v
CANCELLED              CANCELLED          LOCKED(自环)         PICKED_UP
                                                                  |
                                                               deliver
                                                                  v
                                                              DELIVERED --settle--> SETTLED --> CLOSED
                                                                  |
                                                               dispute
                                                                  v
                                                              DISPUTED --arbitrate--> SETTLED / REFUNDED
~~~

- 允许的流转集中在 `ErrandStatus.allowedTargets()`，改状态只能走 `Errand.transitTo()`（状态字段没有 setter，编译期就堵死绕过）。
- `LOCKED -> LOCKED` 是合法自环：确认超时把名额换给候选人，靠 `round` 区分轮次。这是本项目最特殊的一个分支。
- `availableActions` 由 `ErrandActionResolver` 计算，复用 `allowedTargets()` 再叠加"谁有资格"，前端只渲染后端给的动作——状态机不会出现第二份副本。
- 解析器比状态机更严的一处：状态机允许 `LOCKED -> CANCELLED`，但"已有人抢中"不允许发单人无偿取消，所以 `CANCEL` 只在 DRAFT/PUBLISHED 下发。

### 5.4 技术栈与选型理由

| 组件 | 版本 | 用途 | 为什么是它 |
| --- | --- | --- | --- |
| Java | 21 | 语言 | 虚拟线程压测客户端、record、文本块写 SQL |
| Spring Boot | 3.5.8 | 容器 | 装配与配置管理 |
| JdbcTemplate | — | 仓储 | 抢单要精确控制 CAS SQL 与 `affectedRows`，ORM 会挡在中间 |
| MySQL | 8.0.40 | 事实来源 | 行锁 + 唯一索引 + CHECK，并发正确性的最后一道 |
| Redis | 7.4 | 名额/缓存/队列/幂等 | Lua 原子执行，把"读-判-写"合成一步 |
| RocketMQ | broker 5.3.1 / client 5.2.0 | 定时消息 + 事务消息 | 5.x 才支持任意时间 `setDeliveryTimestamp`；4.x 只有 18 个固定 level |
| Redisson | 3.22 | 布隆 + 重建锁 | 只做这两件事，不当分布式锁主方案 |
| Sentinel | 1.8.8 | 热点参数限流 | 资源 + 参数（errandId）双维度，爆款任务不误伤其他任务 |
| ShardingSphere | 5.5.2 | 分片规则 | class-based 算法可被单测直接实例化验证 |
| ArchUnit | 1.3 | 架构守护 | 把"依赖只能向内"变成测试而不是口头约定 |

### 5.5 抢单：五层防线（全项目最核心的一段）

~~~
POST /api/errands/{id}/grab
  │
  ├─ L1 Sentinel 热点参数限流（按 errandId）        → GRAB_RATE_LIMITED
  ├─ L2 资格前置：信用分 ≥ 40、在途任务 < 5          → CREDIT_TOO_LOW / TOO_MANY_ONGOING
  ├─ L3 Redis Lua：状态 + 名额 + 去重 + 幂等（原子）  → SLOT_FULL / ALREADY_GRABBED / NOT_GRABBABLE
  ├─ L4 MySQL CAS：status + version + slot_taken     → GRAB_CONFLICT
  └─ L5 数据库兜底：双唯一索引 + CHECK 约束
~~~

**L1 限流**（`SentinelGrabRateLimiterAdapter`）
- `ParamFlowRule(resource=campus_dash_grab, paramIdx=0)`，参数就是 `errandId`，默认单实例 500/s。
- 放在最前面：过热任务在进入 Redis 之前就快速失败，避免打穿 L3/L4。
- 可切 `dash.grab.limit.type=local` 回单机固定窗口，作为对照。

**L2 资格**（`GrabErrandUseCase.grab` 前半段）
- 信用分从 `credit_score` 表读，客户端传的 `creditScore` 自 P5 起被忽略（否则伪造高分就能绕过门槛、垄断流转）。
- 在途任务数 `countOngoingByRunner` 限制 5 单，避免一个人囤一堆单不做。
- 位置在 Lua 之前：没资格的人连名额裁决都不该参与。

**L3 Redis Lua**（`resources/lua/grab.lua`）
~~~lua
local prev = redis.call('GET', KEYS[3])          -- 幂等键
if prev then return tonumber(prev) == 1 and -2 or 0 end

local slot = redis.call('GET', KEYS[1])          -- 剩余名额
if not slot then return -3 end                    -- 任务不可抢
if redis.call('SISMEMBER', KEYS[2], ARGV[1]) == 1 then return -1 end  -- 一人一名额
if tonumber(slot) <= 0 then
  redis.call('SETEX', KEYS[3], tonumber(ARGV[2]), '0')
  return 0
end

redis.call('DECR', KEYS[1])                       -- INV-1 名额守恒
redis.call('SADD', KEYS[2], ARGV[1])              -- INV-2 记录抢中者
redis.call('SETEX', KEYS[3], tonumber(ARGV[2]), '1')
return 1
~~~
- 为什么必须 Lua："GET 名额 → 判 >0 → DECR" 三条命令之间会被别的客户端插入，两个人都读到 1 就超卖。Lua 在 Redis 里整体执行，中途不被打断。
- 返回码 `1/0/-1/-2/-3` 分别对应：占位成功 / 名额满 / 已抢过 / 重复请求 / 不可抢。
- 幂等键 `errand:idem:{requestId}` 缓存结果：同一 requestId 重放直接返回上次结论，不重复扣名额。

**L4 MySQL CAS**（`JdbcErrandRepository.casLockForRunner`）
~~~sql
UPDATE errand
   SET status='LOCKED', grabber_id=?, slot_taken=slot_taken+1,
       locked_at=NOW(3), version=version+1
 WHERE id=? AND status='PUBLISHED' AND version=? AND slot_taken < slot_total
~~~
- `affectedRows == 0` 就是失败，不需要再查一次判断原因。
- 条件里同时有状态、版本、名额：任何一个被别人改过，这次 CAS 就不生效。

**L5 数据库兜底**
- `uk_errand_round_seq(errand_id, round, seq)`：同一轮同一名额只能被占一次。
- `uk_errand_round_user(errand_id, round, runner_id)`：同一轮同一人只能占一个名额。
- `CHECK (slot_taken >= 0 AND slot_taken <= slot_total)`：代码写错也超不过总名额。
- `round` 必须进索引——P2 踩过的坑：索引只有 `(errand_id, seq)` 时，回退成 PUBLISHED 的任务，新一轮抢单会撞上旧轮次遗留记录。

**补偿：L3 成功、L4 失败怎么办**
- `rollback_slot.lua`：只有该用户确实在 `grabbed` 集合里才 `SREM + INCR + DEL`，重复回滚不会让名额虚增。
- 补偿必须在事务外执行——事务已经回滚，补偿是独立动作。
- 事务边界单独拆成 `GrabTransactionalStep` 这个 Bean，就是为了避开 `@Transactional` 同类自调用绕过代理的经典失效场景。
- 抢中后还有三步"事后动作"：登记首轮超时消息（事务内写本地消息表，提交后发送）、失效详情缓存（afterCommit）、WebSocket 推送。

**候选队列**（`RedisCandidateQueueAdapter`）
- ZSET key `errand:candidates:{id}`，`score = now - min(credit,100) * 10`，越小越优先。
- 信用加权有上限，避免高信用用户完全垄断流转机会；抢单失败者自动入队。

**实测证据**
| 场景 | 结果 |
| --- | --- |
| S1 尖峰：2000 并发抢 1 个名额（三轮） | 成功数严格 = 1，超卖 0 |
| `GrabConcurrencyIT`（应用层 2000 并发） | 成功 1、名额满 1999、冲突 0、Redis 剩余名额 0，7.6s |
| `OversellControlExperimentIT` 拆掉防线 | Redis 名额变负（-307/-499）、158 人抢中同一名额 |
| Sentinel 热点限流（1000 并发 1 名额） | 成功 1、名额满 99、被限流 900、超卖 0、P99 340ms |

### 5.6 超时流转：主通道 + 兜底 + 幂等三件套

链路：
1. 抢单成功 → 事务提交后 `scheduleFirstTimeout` → `TimeoutTransferStep.enqueueTimeout` 在事务里写 `local_message`（`msg_key = timeout:{errandId}:{round}`，唯一索引），返回待发送消息。
2. 事务提交后发 RocketMQ 定时消息（`setDeliveryTimestamp`，topic 必须是 `DELAY` 类型，否则 broker 拒收）。
3. `TimeoutTransferConsumer` 收到 → `handleTimeout(errandId, expectedRound)` → 状态或轮次不符直接 `SKIPPED`。
4. 轮次到上限（默认 5）→ 回退 PUBLISHED 并归还名额；否则从候选队列取最优 → `TimeoutTransferStep.transfer`：CAS 换人（`version + round` 双条件）+ 状态日志 + 登记下一轮超时消息，同一事务。
5. `TimeoutScanJob` 每 5 秒扫 `status='LOCKED' AND locked_at < now - 300s - 2s grace`，走 `idx_timeout_scan`，补 MQ 丢消息的情况。

幂等三件套：

| 机制 | 拦住什么 |
| --- | --- |
| `version` CAS | 任务被确认/取消/流转过，旧消息的 CAS 必然失败 |
| `round` 轮次 | A→B→A 轮回时，上一轮的旧消息不会误伤当前轮 |
| `local_message.msg_key` 唯一索引 | 同一轮的超时消息只登记一次 |
| `local_message` 状态机 + `LocalMessageRetryJob` | 发送失败的消息 10s 后重发，不会"业务成功但消息丢了" |

消费端语义：业务判定"已失效"的消息 ACK，只有真正的系统异常才 FAILURE 重试——把这两者搞混会让消息永远在重试队列里打转。

### 5.7 资金：本地事务 + 三道闸门 + 事务消息

**发布即托管**（`PublishErrandUseCase`）——一个数据库事务里做完 6 步：
1. `casDebit` 扣发单人可用余额（`WHERE available >= amount`，一条语句同时完成判断与扣减）。
2. 写两条流水（发单人 DEBIT、托管户 CREDIT，`biz_no = escrow:{errandId}`）。
3. `casCredit` 托管户。
4. 插托管单（`uk_errand`：一个任务只能有一张托管单）。
5. 任务 DRAFT 落库 → CAS 到 PUBLISHED（状态机在库里也有痕迹）。
6. 事务最后才初始化 Redis 名额、登记布隆——失败则整体回滚，不会出现"库里没任务、Redis 有名额"。

**结算三道闸门**（`SettleErrandUseCase.doSettle`）：

| 闸门 | 语句 | 作用 |
| --- | --- | --- |
| 1 | `UPDATE escrow_order SET status='RELEASED' WHERE status='HELD'` | 并发结算只有一个线程能改 |
| 2 | `UPDATE errand SET status='SETTLED' WHERE status='DELIVERED'` | 任务状态只允许成功一次 |
| 3 | `wallet_ledger.uk_biz_direction(biz_no, account_id, direction)` | 同一笔业务重复记账撞索引 |

佣金：`commission = floor(total * 0.05)`，`runner = total - commission`。这样"跑腿所得 + 佣金 == 托管额"恒成立，不会四舍五入丢 1 分。

**事务消息**（`RocketMqFundEventAdapter`）：资金事件（结算/退款/仲裁）不需要延迟，用半消息 + 本地事务 + commit/rollback，省掉本地消息表；进程在 commit 前崩溃则由 broker 回查，回查只认一个确定性事实——`wallet_ledger` 里有没有这个 `bizNo`（60s 宽限期内查不到先返回 UNKNOWN）。消费端落站内消息，靠 `notification.uk_msg_user(msg_key, user_id)` 幂等。

**审计**：`fund_audit_log` 用独立事务（REQUIRES_NEW）写，资金回滚了审计还在——排查问题要的正是这个。

**三层对账**（`ReconciliationJob`，每天 2 点）：
- L1 借贷平衡：`SUM(DEBIT) == SUM(CREDIT)`。
- L2 快照一致：每个账户 `available + frozen == 流水净额`。
- L3 托管闭环：RELEASED/REFUNDED 的托管单必须有对应结算/退款流水；任务已终结但托管单仍 HELD 也是差异。
- 差异写 `recon_diff`，正常必须为空——有记录就是代码有 bug。

一个值得记住的修正（P3）：原实现扣款时同时 `frozen += amount`，与"资金真的转入托管户"叠加，同一笔钱在快照里被算两次。托管是资金转移，只能减 `available`，与 DEBIT 流水一一对应。

### 5.8 缓存一致性

**读路径**（`GetErrandDetailUseCase`）：
1. 命中且未逻辑过期 → 直接返回。
2. 命中但逻辑过期 → 抢重建权；抢到的回源重建，抢不到的返回旧值（防击穿，代价是最多几百毫秒陈旧）。
3. 未命中 → 布隆判存在（防穿透）→ 回源 DB → 回填；查不到写空值缓存（60s）。

细节：key 是 `errand:detail:{id}:{shard}`，4 片随机读，把热 Key 压力散到多个 slot（代价是失效要删 4 片，写放大换读扩展）；物理 TTL 600s ± 120s 抖动，逻辑过期设在物理 TTL 的一半；重建锁 `tryLock(0, 10, SECONDS)` 不等待，拿不到就返回旧值——阻塞等待会把"击穿"变成"线程池打满"。

**写路径**（`CacheEvictSupport`）：事务 `afterCommit` 删缓存 + 500ms 后延迟双删（走 MQ 定时消息）。
- 为什么不能事务内删：删完到提交之间，读请求会把旧值写回缓存，造成长期不一致。
- 为什么还要双删：覆盖"提交前就开始的慢读，在第一次删除之后才写回旧值"的窗口。
- 双删失败只记日志，靠 TTL 和校验 job 兜底——它不是正确性保障，不值得为它加一次 DB 写。

**校验**：`CacheConsistencyCheckScheduler` 每 5 分钟比对 MySQL 与 Redis，差异写 `sync_diff` 并主动删缓存修正。

**对照实验开关**：`dash.cache.eviction-order=BEFORE_COMMIT` 是故意留的错误实现，`CacheEvictionOrderControlIT` 用它压出"缓存长期为旧值"。

### 5.9 分片与查询

- 任务链路表按 `campus_id` 共片：`errand`、`grab_record`、`escrow_order`、`errand_status_log`（绑定表，同一次路由）。
- 用户维度表按 `user_id`：`wallet_account`、`wallet_ledger`、`credit_score`、`credit_event`。
- `CampusDashShardingPolicy` 给出 `campus_{mod}` / `user_{mod}`；`CampusDashShardingRuleFactory` 产出 ShardingSphere 规则，`CampusDashModuloShardingAlgorithm` 实现 class-based 算法，单测直接实例化验证 `campus_id=7 -> campus_dash_3`。
- 诚实边界：运行态仍是**单库**（`campus_dash`），分片规则与算法已实现并被测试覆盖，属于"可切换"状态，不是已上分片集群。
- 跨分片查询是刻意规避的：所有查询都带 `campus_id` 或 `user_id`，列表走 `idx_campus_status`，深分页用 `(created_at, id)` 游标而不是 offset。

### 5.10 进程隔离与后台任务

- 在线服务（`dash-bootstrap`，worker-id 1）只做低延迟请求；worker 独立进程（worker-id 2）跑批处理。理由：对账 job 一跑，GC 抖动会直接打到抢单 P99。
- worker 里 `dash.ws.enabled=false`，装配 `NoopRealtimeNotifier`——WebSocket 连接只存在于 app 进程，否则 worker 会把本该推给用户的 MQ 消息抢走（P4 踩过的坑）。

| 任务 | 频率 | 作用 |
| --- | --- | --- |
| `TimeoutScanJob` | 5s | 超时流转兜底 |
| `AutoSettleScanJob` | 5s | 送达 24h 未确认自动结算兜底 |
| `LocalMessageRetryJob` | 10s | 重发 PENDING 本地消息 |
| `CacheConsistencyCheckScheduler` | 5min | 缓存一致性校验 |
| `BloomRebuildJob` | 启动时 | 灌入存量任务 id（Redisson 锁防多实例重复） |
| `CreditCalibrationJob` | 每天 3 点 | 30 天滑窗重算信用分 |
| `ReconciliationJob` | 每天 2 点 | 三层资金对账 |

### 5.11 量化证据（同机自测，只做基线）

| 档位 | 请求数 | 错误 | QPS | P50 | P95 | P99 |
| --- | --- | --- | --- | --- | --- | --- |
| 50 并发 | 398,363 | 0 | 6,639 | 6ms | 13ms | 18ms |
| 100 并发 | 409,648 | 0 | 6,826 | 13ms | 24ms | 31ms |
| 200 并发 | 396,229 | 0 | 6,598 | 27ms | 54ms | 77ms |
| 400 并发 | 387,798 | 0 | 6,455 | 55ms | 113ms | 151ms |
| 800 并发 | 381,188 | 146 | 6,346 | 103ms | 255ms | 390ms |
| 800 并发（调参后） | 313,800 | 0 | 5,205 | 134ms | 330ms | 472ms |

- 稳定线到 400 并发；800 是超压档，调大线程/连接池后错误归零但 P99 明显变差（吞吐换稳定）。
- 测试规模：34 个测试类、110 个 `@Test` 方法（README 的 93 是某次全量 `mvn test` 的口径）。集成测试在中间件没起时 skip 而不是 fail。
- 压测设施不侵入业务表：轮次信息写 `bench_run` / `bench_run_item`，清理按 `run_id` 级联。

### 5.12 前端是不是"演示模块"

结论：**是真实调用后端的前端，不是 mock 演示页；但产品定位确实是教学演示。**

- 真实的部分：`api.ts` 全部走 `/api/*`，没有内置假任务、假余额、假距离；7 个页面（广场/发布/我的/详情/钱包/信用/消息）都接真实接口；操作按钮完全由后端 `availableActions` 驱动；WebSocket 断开自动重连并降级轮询；长 ID 全程按字符串处理（雪花 ID 超过 2^53），金额在请求边界才转 number。
- 演示的部分：登录是"选一个演示身份"（1001/2001/2002/9001）而不是真实身份核验；广场的"模拟 N 人同时抢"只是浏览器端几次并发，不代表服务端压测；默认单校区 `campusId=1`；没有注册、支付、风控等生产必备件。
- 所以它是"能跑通完整业务闭环的教学前端"，写进简历可以说"独立实现 React 前端"，但不能说"生产级"。

### 5.13 取舍与已知边界

- **单体优先**：发布托管、结算分账都在同一个库的本地事务里，省掉分布式事务；拆微服务才需要 Seata/Saga。这是刻意的教学取舍。
- **JdbcTemplate 而非 ORM**：抢单核心是 CAS SQL 与 `affectedRows`，ORM 会把并发控制藏起来；代价是手写映射。
- **缓存不参与正确性**：名额判定和抢单裁决绝不读缓存，缓存只加速详情查询——所以缓存挂了只影响延迟，不影响超卖。
- **已知限制**：流转准时率 P99 缺自然到期时间轴的数据；压测无环境隔离；ShardingSphere 未真正切分片集群；演示身份无钱包的临时账号不能走完生命周期。
- **最值得反复看的三段代码**：`grab.lua`（原子判定）、`GrabTransactionalStep`（事务边界与自调用陷阱）、`SettleErrandUseCase`（三道闸门）。

## 6. 次日（day-3）待办

- [ ] 逐层走通抢单链路：Controller → Sentinel 限流 → Redis Lua → MySQL CAS → 双唯一索引
- [ ] 学习资金链路：复式记账、三道幂等闸门、三层对账
- [ ] 给 `Notification` 增加一个 MyBatis 适配器做对照实验（保留原 JdbcTemplate 实现，用配置切换）
- [ ] 借现在已经能正常启动的 worker，实测一次「发布后 5 分钟未确认自动流转」和「送达后自动结算」
