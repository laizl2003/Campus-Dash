# CampusDash 项目技术亮点

> 本文用于技术复盘与简历素材整理，覆盖当前已实现的功能、Redis / RocketMQ 的使用场景、
> 多账号登录切换，以及抢单与优惠券领取两处高并发设计。
> 所有描述均对应当前仓库代码，不包含未落地的规划。

---

## 一、项目概览

CampusDash 是一个校园跑腿与互助平台，覆盖「发布任务 → 托管资金 → 抢单 → 确认 → 取货 → 送达 → 结算 / 退款 / 仲裁」的完整闭环，并提供优惠券、信用分、消息中心、管理台与实时推送。

### 技术栈

| 层次 | 选型 |
| --- | --- |
| 语言 / 框架 | Java 21、Spring Boot 3.5.8 |
| 架构 | Maven 多模块、DDD 分层（domain / application / infrastructure / presentation / bootstrap / worker / bench） |
| 数据库 | MySQL 8，InnoDB，雪花 ID |
| 缓存 / 会话 | Redis（Lettuce / Spring Data Redis） |
| 消息 | RocketMQ 5.x（普通消息、事务消息、延迟消息） |
| 前端 | React 18、TypeScript、Vite、Hash Router |
| 网关 / 部署 | Nginx、Docker Compose、PowerShell 一键启动脚本 |
| 实时通信 | 原生 WebSocket（握手鉴权、心跳、重连） |

### 已实现功能

- 用户注册 / 登录 / 改密 / 注销，图形验证码，PBKDF2-SHA256 密码哈希。
- 任务全生命周期：发布托管、抢单、确认、取货、送达、结算、取消、超时流转、自动结算。
- 资金：托管账户、用户钱包、平台佣金 / 营销账户、复式记账流水、退款、仲裁分账。
- 优惠券：领取、锁券、使用、过期、库存管理、管理端上下架 / 删除 / 动态类型。
- 信用分：事件加减分、30 天滑动窗口、Redis 排行榜、实时推送。
- 消息中心：站内通知、未读计数、WebSocket 实时提醒、跳转来源任务。
- 管理台：仲裁工作台、平台资金流水、优惠券管理、平台配置、用户管理、强退 / 禁用。
- 多账号登录与切换、强制下线、登录状态心跳、在线状态展示。
- WebSocket 实时推送：任务状态、资金通知、信用变化、管理端争议提醒。

---

## 二、Redis 使用场景

### 1. 会话与认证

- access token / refresh token 存储与轮换；
- 登出撤销、refresh token 一次性消费（GETDEL）；
- 强制下线标记 `auth:forced:{userId}`；
- 用户 token 索引 `auth:user_tokens:{userId}`，支持按用户批量撤销；
- 图形验证码 `auth:captcha:{id}`，一次性消费；
- 登录状态 `user.login_status` + `last_seen_at` 心跳，90 秒无心跳由 worker 置离线。

### 2. 抢单名额（高并发场景一）

- Lua 原子判断任务是否仍可抢、扣减名额、登记抢中者；
- 避免 `GET → 判断 → DECR` 的竞态；
- DB 侧再用 CAS 与唯一索引兜底。

### 3. 优惠券库存（高并发场景二）

- `voucher:stock:{type}`：可售库存；
- `voucher:pending:count:{type}`：已预扣未落库数量；
- `voucher:pending:id:{voucherId}`：单条预扣凭证；
- `voucher:claimed:{type}:{userId}`：一人一单标记，使用/过期后释放；
- Lua 一次完成库存判断、扣减、pending、一人一单。

### 4. 其他

- 信用排行榜 ZSET `credit:rank:{campusId}`；
- 任务详情 / 列表缓存与延迟双删；
- 抢单热点限流（本地 / Sentinel 适配）；
- 在线状态、强制下线、管理端事件广播。

---

## 三、RocketMQ 使用场景

### 1. 事务消息：资金事件

结算 / 退款 / 仲裁完成后发布 `errand-fund-event`：

- 事务消息保证“资金动作提交成功则事件必达”；
- app 进程消费后做 WebSocket 推送；
- worker 进程消费后落站内通知；
- 两个消费组互不影响。

### 2. 延迟消息 + 本地消息表

超时确认、自动结算、优惠券到期都采用：

```text
业务事务写 local_message(PENDING, deliver_at=到期时刻, next_retry_at=NOW)
→ LocalMessageRetryJob 在 10 秒内取出并立即投递
→ RocketMQ 按 deliveryTimestamp 持有到到期时刻
→ 消费成功后 markSent(SENT)
→ 发送失败按指数退避重试，超过上限转 DEAD
```

- local_message 是事务性 Outbox：业务提交与消息登记同事务；
- `next_retry_at=NOW` 让 job 立即投递，真正的等待由 RocketMQ 的 deliveryTimestamp 完成；
- RocketMQ 5.x 单条最大延迟 24h：适配器把超过 24h 的到期时间截断为 24h，
  优惠券到期消费者发现券还没到期时，再登记下一跳；48h 券 = 24h + 24h 两跳；
- 定时扫描 DB 作为最终兜底；
- 这样既是“可靠投递”，也是真正的 MQ 延迟队列，而不是 DB 到点调度。

### 3. 普通消息：优惠券异步领取

`voucher-claim`：

- Redis Lua 预扣成功后投递；
- worker 消费落库、扣 DB 库存、登记到期消息；
- 发送失败立即回滚 Redis；
- 重复消息由 `voucher_record` 主键与 `INSERT IGNORE` 幂等。

### 4. 延迟双删

任务状态变化后发送延迟消息再次删除缓存，降低“删缓存后旧值被回填”的概率。

---

## 四、高并发场景一：抢单

### 目标

同一任务多人同时抢，必须满足：

- 名额不超卖；
- 只有一个人抢中；
- 重复请求不重复占名额；
- 失败请求不留下脏状态。

### 分层防护

```text
① 资格前置：登录、信用分、任务状态、是否本人发布
② Redis Lua：原子判断名额并占位
③ MySQL CAS：errand 状态 / slot_taken 条件更新
④ 唯一索引：grab_record / 幂等键兜底
```

### 关键点

- Lua 把“判断 + 扣减 + 记录抢中者”合成一次原子操作；
- DB 更新使用 `WHERE status = ? AND version = ?` 形式的 CAS，受影响行数为 0 即失败；
- 抢单记录与状态流转在同一事务；
- 超时未确认走延迟消息 + 扫描，名额可回流；
- 失败路径统一回滚 Redis 占位，避免“锁死名额”；
- 热点任务可接限流，保护 Redis 与 DB。

### 简历表述

> 设计并实现抢单四层防护（资格校验、Redis Lua 原子占位、MySQL CAS、唯一索引兜底），
> 通过条件更新与幂等键保证高并发下零超卖、零重复抢中。

---

## 五、高并发场景二：优惠券领取

### 目标

- 库存不超卖；
- 一人一单；
- 峰值流量不打穿 DB；
- MQ / worker 失败可补偿；
- Redis 与 DB 最终一致。

### 链路

```text
用户领取
→ Redis Lua 原子预扣（库存 -1、pending +1、一人一单）
→ 投递 voucher-claim
→ worker 消费
   → INSERT IGNORE 领取记录
   → DB 库存 CAS -1
   → 登记到期 local_message
   → pending -1
```

### 一致性设计

- `pending` 计数使 Redis 可售库存 = DB 库存 - pending；
- 启动时 `SETNX` 从 DB 初始化库存，已有 key 不覆盖；
- 管理端改库存 write-through 同步；
- worker 每分钟对账：`Redis 库存 = max(0, DB 库存 - pending)`；
- MQ 发送失败立即 Lua 回滚；
- worker 业务失败删除已插入记录并回滚；
- 重试 5 次仍失败按 DB 是否落库决定 confirm / rollback；
- 一人一单标记在 used / expired 后释放，支持再次领取。

### 简历表述

> 基于 Redis Lua + RocketMQ 实现优惠券异步领取：Lua 原子预扣库存与一人一单，
> MQ 削峰后由 worker 幂等落库；通过 pending 计数、启动初始化、定时对账与失败补偿
> 保证缓存与数据库最终一致。

---

## 六、多账号登录与切换

### 登录与凭证

- 用户名 / 密码 / 图形验证码登录；
- access token + refresh token 双 token；
- refresh token 一次性轮换，GETDEL 防并发重放；
- access token 短期有效，401 自动刷新；
- PBKDF2-SHA256（120k 次）存储密码。

### 多账号账号簿

前端维护 `dash_accounts`：

```text
userId / username / role / accessToken / refreshToken
```

- 登录成功写入账号簿；
- 登录页、侧栏、管理台都可以快速切换；
- 切换时用 refresh token 换新 access，不重新输密码；
- 只有主动「退出登录」才撤销服务端 token 并从账号簿移除；
- 返回登录页不清除账号簿。

### 角色分流

- 普通用户切换后进入任务广场；
- 切到 Admin 自动进入管理台；
- 普通用户访问管理路由被守卫拦回；
- 后端 AdminInterceptor 再做权限兜底。

### 强制下线

- Admin 关闭该用户全部 WebSocket；
- Redis 写 `auth:forced:{userId}`；
- HTTP 拦截器与 WS 握手都检查；
- 按 `auth:user_tokens:{userId}` 撤销全部 access / refresh；
- 用户重新登录成功后清除标记。

### 在线状态

- 登录置 `login_status=1`；
- 前端每 30 秒心跳；
- worker 每分钟清理 90 秒无心跳账号；
- 管理台按 DB 状态展示在线 / 离线。

---

## 七、资金与一致性设计

- 托管资金：发布时从发单人钱包转入托管户，结算 / 退款时再释放；
- 复式记账：每笔业务写多条 `wallet_ledger`，借贷平衡；
- 平台账户：托管、佣金、营销、外部；
- 佣金：按悬赏金额 8% 计算；
- 优惠券：跑腿承担 35%，其余由营销户补贴；
- 幂等：`(biz_no, account_id, direction)` 唯一索引；
- L1 校验：所有流水 `SUM(DEBIT) - SUM(CREDIT) = 0`；
- 仲裁：支持跑腿 / 支持发单人两种终态，双方信用分别 +2 / -8；
- 审计：`fund_audit_log` 记录资金动作，失败不回滚资金。

---

## 八、实时通信

- 每个登录用户一条 `/ws?token=...` 连接；
- 握手阶段校验 token，注册 `userId → Set<WebSocketSession>`；
- 事件类型：`errand.status`、`notification.new`、`credit.changed`、`admin.dispute`；
- WebSocket 只做变更信号，DB 是事实源；
- 前端收到事件后重新拉取列表 / 计数；
- 未读计数、角标与高亮均来自接口数据，不依赖消息副本；
- 多标签页共享同一账号的多个 session。

---

## 九、工程化与可测试性

- Maven 多模块，domain 不依赖 Spring；
- 端口 / 适配器隔离 Redis、MQ、DB、WebSocket；
- 本地消息表、幂等键、补偿、对账任务；
- 单元测试 + 集成测试 + 并发测试；
- bench 模块与 JMeter 脚本用于压测；
- Docker Compose + PowerShell 一键启动 / 停止 / 日志；
- 前端 Hash Router，管理台与用户端同一构建产物。

---

## 十、简历可用的精简 bullet

1. 基于 Spring Boot 3 + Java 21 构建校园跑腿平台，覆盖任务托管、抢单、结算、退款、仲裁、优惠券、信用分与管理台。
2. 设计抢单四层防护：资格校验 → Redis Lua 原子占位 → MySQL CAS → 唯一索引兜底，保证高并发零超卖。
3. 设计优惠券 Redis Lua 预扣 + RocketMQ 异步落库，支持一人一单、库存对账、失败补偿与缓存最终一致。
4. 实现复式记账与托管资金模型，佣金 8%、优惠券跑腿承担 35%，通过唯一索引与 L1 借贷平衡保证资金幂等。
5. 实现多账号登录切换、refresh token 轮换、强制下线与 token 索引撤销，支持跨浏览器在线状态。
6. 基于 WebSocket 实现任务、资金、信用、管理端争议的实时推送，并用 DB 作为事实源保证最终一致。
