# TODO

> 记录项目待办。完成一项就把 `[ ]` 改成 `[x]`，并补一句验证方式（命令 / 截图 / 测试名）。
> 新增待办按顺序编号（2、3……），沿用第 1 条的结构：目标 / 现状与差距 / 实现步骤 / 验收标准 / 参考文件。

## 1. 双 token 模式下的自动续期（已完成 2026-10-27）

**目标**：access token（默认 2 小时）过期后，前端自动用 refresh token（默认 7 天）换新的一对，用户无感知；只有 refresh 也失效时才跳登录页。

### 现状与差距（实施前）

- 后端已具备：
  - `POST /api/auth/refresh`（`AuthController.refresh`）
  - `JwtAuthAdapter.refresh`：查 Redis → 删旧 refresh（轮换）→ 发新的一对
  - `JwtAuthAdapter.revokeRefresh`：删除 refresh 映射
- 前端缺口：
  - `campus-dash-frontend/src/api.ts` 的 `login()` 只存 `token`，**丢弃了 `refreshToken`**
  - `request()` 收到 401 直接 `logout()` 跳登录，**不调用刷新接口**
- 后端缺口：
  - `AuthController.logout` 只把 access 的 `jti` 加入黑名单，**未调用 `revokeRefresh`**
  - 后果：登出后 refresh token 在 7 天内仍能换出新 access

### 实现步骤

1. `api.ts`：登录/刷新响应里的 `refreshToken` 持久化（如 `dash_refresh`），登出时清空。
2. 新增 `refreshOnce()`：**单飞（single-flight）**，用共享 Promise 保证并发 401 只触发一次刷新。
3. `request()` 的 401 分支：先 `refreshOnce()`；成功则保存新的一对并**重试原请求一次**；失败才 `logout()`。
4. 防死循环：刷新接口自身返回 401 不再递归；原请求最多重试一次（`retried` 标记）。
5. 轮换语义：刷新后必须保存**新的** `refreshToken`（旧 token 已被服务端删除）。
6. 后端补齐：登出接口接收 `refreshToken` 并调用 `revokeRefresh`（可放在 body 里）。
7. 并发验证：多个标签页/多个请求同时 401，只应产生一次 `/api/auth/refresh`。

### 验收标准

- [x] access 过期后任意业务请求自动恢复，页面不跳登录。
      → `node tests/refresh_single_flight.mjs` 场景 2
- [x] 刷新失败（refresh 过期/被撤销）时才跳登录，且本地 token 清空。
      → 同上场景 3
- [x] 并发 5 个 401 只发 1 次 `/api/auth/refresh`。
      → 同上场景 2、3；后端 `AuthRefreshIT.concurrent_refresh_on_same_token_has_single_winner`
- [x] 登出后 refresh token 立即失效（再次调用 refresh 返回 401）。
      → `AuthRefreshIT.logout_revokes_refresh_token`
- [x] 轮换后旧 refresh token 重放失败（返回 401）。
      → `AuthRefreshIT.refresh_token_rotates_and_old_token_is_invalidated`

### 完成情况

**后端**

- `JwtAuthAdapter.refresh`：由 `get` + `delete` 改为 `getAndDelete`（Redis GETDEL）。
  两步之间有窗口，同一张 refresh token 并发提交会换出两对凭证、轮换失效；GETDEL 只有一个线程能取到值。
  映射值非法时按失效处理，不再抛 `NumberFormatException` 变成 500。
- `AuthController.logout`：新增可选 body `{ "refreshToken": "..." }`，在拉黑 access 的 `jti` 之外调用 `revokeRefresh`；
  没有 body 的老客户端行为不变（Session 模式则忽略该字段）。
- `dash-infrastructure/pom.xml`：增加 `spring-boot-starter-test`（test 作用域），
  让 Redis 适配器"到底发了哪条命令"可以脱离中间件验证。

**前端**

- `api.ts`：`dash_refresh` 与 `dash_token` 分开持久化；登录、刷新、登出、切换身份统一走 `applyTokens` / `logout`。
- `refreshOnce()` 单飞：并发 401 共享同一个 Promise；再用 Web Locks（`navigator.locks`）在标签页之间串行化，
  拿锁后重新读取最新 refresh token。
- `request()` 的 401 分支：先刷新再重试原请求一次；`retried` 标记 + 跳过 `/api/auth/*` 双重防死循环。
- `sessionEpoch`：切换身份 / 登出后，旧请求的 401 既不会退出新会话，也不会以新身份重放。
- `logout()`：主动 `POST /api/auth/logout`（`keepalive: true`）提交 refresh token，服务端同步撤销。
- `adoptSharedRotation()`：刷新失败前先看别的标签页是否已轮换，避免把"别人换过了"误判成"会话过期"。

**验证**

| 命令 | 结果 |
| --- | --- |
| `mvn test -Dtest="JwtAuthAdapterTest,AuthControllerTest"` | 14 通过（8 + 6），0 失败 |
| `mvn test -Dtest=AuthRefreshIT`（需 docker 中间件） | 3 通过：轮换重放、登出撤销、并发单胜 |
| `mvn test`（全量，中间件已起） | 126 通过，0 失败，0 跳过 |
| `node tests/refresh_single_flight.mjs` | 22 项检查全部通过 |
| `npm run build` | `tsc -b` + vite 通过 |

**边界**

- 跨标签页单飞依赖 Web Locks（Chrome/Edge/Firefox、Safari 16.4+）。不支持时退化为标签页内单飞：
  两个标签页同时刷新可能各自请求一次，但 GETDEL 保证不会换出两对凭证，且 `adoptSharedRotation` 会采用先完成者的结果。
- 并发抢单演示用的一次性身份（`issueToken`）不保存 refresh，其 Redis 映射 7 天后自然过期。
- 生产仍需把 `dash.auth.jwt-secret` 换成环境变量注入；当前默认值仅供本地演示。

### 参考文件

- `campus-dash-backend/dash-infrastructure/.../auth/JwtAuthAdapter.java`：issue / refresh / revoke / logout
- `campus-dash-backend/dash-presentation/.../auth/AuthController.java`：登录、刷新、登出端点
- `campus-dash-backend/dash-presentation/.../WebConfig.java`：`/api/auth/refresh` 已排除拦截器
- `campus-dash-frontend/src/api.ts`：`request()` 的 401 分支、`login()` 的存储逻辑
- `campus-dash-backend/dash-infrastructure/src/test/.../auth/JwtAuthAdapterTest.java`：GETDEL 与撤销的单元测试
- `campus-dash-backend/dash-bootstrap/src/test/.../it/AuthRefreshIT.java`：轮换、登出撤销、并发单胜
- `campus-dash-frontend/tests/refresh_single_flight.mjs`：并发 401 单飞与失败登出的行为验证

## 2. 用户身份从 long 切到 UUID + 用户名密码登录（已完成 2026-10-27）

**目标**：登录改成"用户名 + 密码 + 图形验证码"，校验通过后用数据库里查到的 user_id 放进拦截器的 ThreadLocal；
注册用户（UUID）与系统仲裁账号（`001`）都能正常走业务接口。

### 现状与差距

- 已完成（2026-10-27）：`user` 表（UUID 主键 + 唯一索引 + 冗余字段）、验证码、注册接口、Admin 种子账号。
- 未完成：登录仍是演示用的 `{userId}`，因为身份类型还是 `long`：
  - 130 处 `long userId` / `publisherId` / `operatorId` 声明（含测试）
  - 8 个 `BIGINT` 用户外键列：`errand.publisher_id/grabber_id`、`escrow_order.publisher_id`、
    `wallet_account.owner_id`、`wallet_ledger.user_id`、`credit_*`、`notification.user_id`、`errand_status_log.operator_id`
  - 126 个测试用 `1001L` 这类数字身份
- 两条路：
  - **A（推荐）**：全系统 `userId` 换成 `String`，系统账户用 `"-1"`/`"-2"`；`JWT sub`、ThreadLocal、MQ 事件一起改
  - **B**：`user` 表 UUID 主键 + `numeric_id BIGINT` 代理键，业务表不动；登录返回 UUID，拦截器查库后把 `numeric_id` 放进 ThreadLocal

### 实现步骤

1. 选 A 或 B，先把结论写进 `docs/注册与验证码.md`。
2. 按 A：domain 模型/端口 → application 用例 → 基础设施 JDBC → presentation DTO 依次改 `long` 为 `String`，每层编译通过再下一层。
3. 改 `AuthPort` / `RefreshTokenPort`：`login(String userId)`、`resolve` 返回 `Optional<String>`；JWT `sub` 存原样字符串。
4. 新增 `POST /api/auth/login`（username + password + captchaId + captchaCode）：查账号 → 校验密码 → 校验 status → 签发 token；失败统一 `BAD_CREDENTIALS`，`login_fail_count` 累加。
5. 拦截器把查到的 `user_id` 放进 ThreadLocal（替换现在的 request attribute 或与之并存）；`CurrentUser.get()` 返回 `String`。
6. 前端登录页改成用户名/密码/验证码，加注册页；`getUserId()` 类型从 `number` 改 `string`，演示身份映射同步调整。
7. 保留 `dash.auth.demo-login` 开关（默认 true，生产 false）给旧测试与压测用。

### 验收标准

- [x] 用户名 + 密码 + 验证码登录成功，token 的 sub 等于数据库 `user_id`
      → 实测：注册返回 UUID `01b8a239-…`，登录返回同一个 id
- [x] 密码错误 / 账号不存在都返回 `BAD_CREDENTIALS`，不泄露账号是否存在
      → `AuthControllerTest.username_login_rejects_wrong_password`
- [x] 验证码错误或重复使用返回 `CAPTCHA_INVALID`
      → `AuthControllerTest` + 「验证码重放第二次」实测
- [x] `status=0` 的账号返回 `ACCOUNT_DISABLED`
      → `LoginUserUseCase` 中 `!user.enabled()` 分支
- [x] 用注册用户（UUID）登录后能调业务接口
      → `GET /api/wallet` 返回 OK
- [x] Admin（`001`）能登录并拿到 `role=Admin`
      → 实测 `admin -> OK userId=001 role=Admin`
- [x] 旧接口 `{userId}` 在 `demo-login=false` 时返回 401
      → `AuthControllerTest.demo_login_disabled_returns_unauthorized`
- [x] 全量测试通过 → **131 通过**（23 + 20 + 22 + 7 + 2 + 56 + 1）

### 完成情况

**身份类型**
- `long userId / publisherId / grabberId / operatorId / ownerId / runnerId` 全部改为 `String`；
  系统账户从 `-1L/-2L` 变成 `"-1"/"-2"`，`Errand.SYSTEM_OPERATOR = "-1"`。
- 字符串比较全部改成 `equals`：`Errand`（settle/cancel/dispute/runner 校验）、
  `SettleErrandUseCase`、`RefundErrandUseCase`、`ErrandActionResolver`。
  这是本次改造最容易漏的地方——`!=` 比引用，UUID 永远不相等。
- 详情缓存 JSON 的 `publisherId/grabberId` 从 `%d` 改成 `%s`，资金事件 JSON 同步加引号。
- 数据库 11 个用户外键列 `BIGINT → VARCHAR(36)`：
  `errand.publisher_id/grabber_id`、`errand_status_log.operator_id`、`escrow_order.publisher_id`、
  `grab_record.runner_id`、`wallet_account.owner_id`、`wallet_ledger.user_id`、
  `credit_score.user_id`、`credit_event.user_id`、`notification.user_id`、`fund_audit_log.operator_id`。
  迁移脚本：`docker/migrate-user-id-string.sql`；全新库由 `init.sql` 直接建。

**登录**
- `POST /api/auth/login`：`{username, password, captchaId, captchaCode}` → 查库 → 校验密码/状态 →
  签发 token（sub = 数据库 `user_id`）→ 返回 `{userId, username, role, accessToken, refreshToken}`。
- 拦截器 `CurrentUser` 改为 `ThreadLocal<String>`，`afterCompletion` 清理；
  取到的就是 token 里的 user_id（登录时由查库得来）。
- 演示后门保留：`{userId:"1001"}` + `dash.auth.demo-login`（默认 true，生产置 false），
  仅供 TopBar 身份切换与压测。

**前端**
- 登录页默认是登录表单（用户名 / 密码 / 图形验证码），"注册新账号"就在密码栏下面；
  点进去同一张卡片切成注册表单（用户名 / 手机号 / 密码 / 确认密码 / 验证码），
  注册成功回到登录并预填用户名，不自动登录（验证码一次性，复用会失败）。
- 验证码图片点击可刷新；提交失败自动换一张。
- `userId` 全链路改为 `string`（`App` / `TopBar` / `getUserId()`）。

### 参考文件

- `campus-dash-backend/docker/init.sql`、`docker/migrate-user.sql`、`docker/migrate-user-id-string.sql`：`user` 表与 id 类型迁移
- `campus-dash-backend/dash-application/.../usecase/LoginUserUseCase.java`：用户名密码登录
- `campus-dash-backend/dash-application/.../usecase/RegisterUserUseCase.java`：注册校验顺序
- `campus-dash-backend/dash-infrastructure/.../auth/Pbkdf2PasswordHasher.java`：密码哈希
- `campus-dash-backend/dash-infrastructure/.../auth/ImageCaptchaService.java`：验证码
- `campus-dash-backend/dash-presentation/.../auth/RegistrationController.java`：`/captcha`、`/register`
- `campus-dash-backend/dash-bootstrap/.../bootstrap/AdminAccountInitializer.java`：Admin 种子
- `campus-dash-backend/docs/注册与验证码.md`：表结构与接口说明

## 3. 管理员后台：优惠券发布与抽成配置（未完成）

**目标**：Admin 登录后有一套管理端页面，可以新增/下架优惠券、调整库存与面额，并在线修改平台抽成比例。

### 现状与差距

- 券的类型、面额、初始库存在 `VoucherType` 枚举里写死，改一次要发版；
- 佣金率、跑腿承担比例在 `application.yaml`（`dash.settle.commission-rate` / `dash.voucher.runner-bear-ratio`），需要重启；
- 前端只有用户侧页面，Admin 登录后仍是普通广场。

### 实现步骤

1. 券定义落库：`voucher_type` 表（type / threshold / discount / 描述 / 上下架），`VoucherType` 改为读表 + 本地缓存；
2. 管理接口（`/api/admin/vouchers`，要求 `role=Admin`）：列表、新增、上下架、改库存；
3. 配置接口：读写 `commission-rate` 与 `runner-bear-ratio`，落 `sys_config` 表并加缓存，替换 `@Value`；
4. 管理端前端：路由守卫按 `role` 分流，券列表 + 编辑表单 + 抽成滑块；
5. 审计：每次改价/改库存写 `fund_audit_log` 或独立 `admin_audit`。

### 验收标准

- [ ] 非 Admin 访问管理接口返回 401/403
- [ ] 新增一种券后，用户端广场立即出现并可领取
- [ ] 把某券库存改为 0 后，用户端显示"已领完"
- [ ] 修改佣金率后，新结算按新比例分账（老订单不受影响）
- [ ] 所有管理操作有审计记录

### 参考文件

- `campus-dash-backend/dash-domain/.../voucher/model/VoucherType.java`
- `campus-dash-backend/dash-application/.../usecase/ClaimVoucherUseCase.java`
- `campus-dash-backend/dash-application/.../usecase/SettleErrandUseCase.java`
- `campus-dash-backend/dash-bootstrap/src/main/resources/application.yaml`

## 4. 跑腿侧展示"预计实收"（未完成）

**目标**：带券订单在抢单前就告诉跑腿实际到手金额，避免结算时收入低于预期。

### 现状与差距

- 列表/详情只展示悬赏 `reward`，券的 `-0.4D` 由跑腿承担，但没有任何提示。

### 实现步骤

1. 详情/列表接口返回 `discountCents`、`runnerBearCents`、`estimatedRunnerCents`；
2. 详情页在悬赏下方展示"预计实收 ¥x（含券分摊 -¥y）"；
3. 卡片上给带券任务加一个小标记。

### 验收标准

- [ ] 带券任务的预计实收与结算后实际到账完全一致
- [ ] 无券任务不显示额外信息

### 参考文件

- `campus-dash-backend/dash-presentation/.../ErrandController.java`
- `campus-dash-frontend/src/pages/Detail.tsx`、`components/ErrandCard.tsx`


## 5. 广场 WebSocket 聊天室（未完成）

**目标**：在广场左侧边栏下半部分新增公共聊天室，登录用户可实时收发消息，刷新后能加载最近历史消息。

### 现状与差距

- 已有：`/ws` 的 token 握手鉴权（`WsAuthHandshakeInterceptor`）、在线连接表 `WsSessionRegistry`、前端 `ws.ts` 的心跳/退避重连、按 userId 推送的 `RealtimePushService`。
- 缺口：`WsHandler.handleTextMessage` 只处理 `ping`，其他客户端消息一律忽略；注册表没有广播原语；没有 `chat_message` 表与历史接口；前端只有 `subscribeWs`，没有发送方法，也没有聊天组件。
- 设计已提前写好：`campus-dash-backend/docs/广场聊天室.md`（复用 `/ws`、MySQL 单表、`beforeId/afterId` 游标、先落库后广播）。

### 实现步骤

1. 建表 `chat_message`：雪花 id、campus_id、sender_id、content、created_at，索引 `(campus_id, id)`；不要复用 `notification` 或 `local_message`。
2. domain/application 层增加 `ChatMessage`、`ChatRepository`、`SendChatMessageUseCase`（空消息/超长/频率校验），历史查询按 `beforeId` 游标取最近 50 条。
3. 广播：给 `WsSessionRegistry` 增加 `broadcast(campusId, json)`（或抽 `ChatBroadcaster` 端口），处理慢消费者；`WsHandler` 增加入站 `chat` 分支，先落库再广播，保留 `ping` 兼容。
4. 历史接口：`GET /api/chat/messages?campusId=1&beforeId=&limit=50`，返回 senderName/avatarUrl/createdAt。
5. 前端：`ws.ts` 增加 `sendWs` 与发送队列；新增 `ChatPanel` 挂到 `TopBar` 侧栏 `.sidebar-guide` 位置，包含消息列表、上翻加载、输入框、发送失败重试。
6. 安全与限流：Redis 令牌桶（如 2 秒 1 条）、纯文本渲染防 XSS；单实例内存广播，多实例预留 Redis Pub/Sub 开关。
7. 测试：单元测试覆盖校验/限流；WS 集成测试覆盖两个客户端互收、刷新后历史、未登录拒绝、超长/刷屏拒绝，并回归现有 ping 与任务推送。

### 验收标准

- [ ] 两个浏览器分别登录 A/B，A 发送后 B 在 1 秒内收到
- [ ] 刷新页面能看到最近 50 条历史，上翻 `beforeId` 能继续加载更早消息
- [ ] WS 未连接/发送失败时前端有明确提示与重试，不静默丢消息
- [ ] 空消息、超长消息、高频刷屏被后端拒绝并返回可读错误
- [ ] 消息按纯文本渲染，不执行 HTML/脚本
- [ ] 现有 `ping/pong` 心跳与任务状态推送不受影响

### 参考文件

- `campus-dash-backend/docs/广场聊天室.md`：完整技术方案与协议设计
- `campus-dash-backend/dash-presentation/src/main/java/com/campusdash/presentation/realtime/WsHandler.java`
- `campus-dash-backend/dash-presentation/src/main/java/com/campusdash/presentation/realtime/WsSessionRegistry.java`
- `campus-dash-backend/dash-presentation/src/main/java/com/campusdash/presentation/realtime/WebSocketConfig.java`
- `campus-dash-frontend/src/ws.ts`、`campus-dash-frontend/src/components/TopBar.tsx`

## 6. Admin 仲裁功能验收与补强（未完成）

**目标**：用可重复的测试覆盖“发起争议 → Admin 查看详情 → 裁决给跑腿/发单人”的完整链路，修复验收中发现的问题。

### 现状与差距

- 已有：`AdminController` 的 `GET /api/admin/disputes`、`GET /api/admin/disputes/{id}`、`POST /api/admin/disputes/{id}/arbitrate`；`ArbitrateErrandUseCase` / `ArbitrateSettleStep` 处理资金与券；管理台 `AdminApp` 有争议列表、详情与裁决按钮。
- 缺口：现有自动化主要覆盖权限（`ApiEndpointIT`），缺少两种裁决方向的资金、券、信用、通知断言；没有重复裁决/并发裁决测试；管理台详情字段、异常态和裁决后的列表刷新缺少系统验收。

### 实现步骤

1. 用种子账号构造 DISPUTED 订单（送达后发单人发起争议），记录 escrow、wallet_ledger、voucher_record、credit_score 基线。
2. 覆盖「判给跑腿」：断言 escrow RELEASED、佣金/营销/跑腿分录正确、券 used、双方信用 +2、双方收到通知。
3. 覆盖「判给发单人」：断言退款金额、券恢复 available、信用规则、通知内容。
4. 覆盖重复/并发裁决：同一订单只允许成功一次，第二次返回幂等或非法状态，不重复记账。
5. 覆盖权限与异常：非 Admin 调用 403；不存在的 id、非 DISPUTED 订单返回可读错误。
6. 管理台走查：列表→详情→选择方向→二次确认→成功提示→列表刷新；检查金额/券信息展示是否完整。
7. 把上述用例沉淀为 IT 测试类并纳入回归，修复验收中发现的 UI/接口问题。

### 验收标准

- [ ] 判给跑腿后，跑腿实收、佣金、营销补贴与 L1 借贷差均符合预期
- [ ] 判给发单人后，退款到账、券状态恢复、流水可追溯
- [ ] 同一订单重复/并发裁决只产生一次资金变动
- [ ] 非 Admin 调用仲裁接口返回 403/401
- [ ] 管理台可完成列表→详情→裁决→刷新，错误场景有明确提示
- [ ] 新增仲裁 IT 测试全部通过，并回归现有资金测试

### 参考文件

- `campus-dash-backend/dash-presentation/src/main/java/com/campusdash/presentation/AdminController.java`
- `campus-dash-backend/dash-application/src/main/java/com/campusdash/application/usecase/ArbitrateErrandUseCase.java`
- `campus-dash-backend/dash-application/src/main/java/com/campusdash/application/usecase/ArbitrateSettleStep.java`
- `campus-dash-backend/dash-bootstrap/src/test/java/com/campusdash/it/ApiEndpointIT.java`
- `campus-dash-frontend/src/admin/AdminApp.tsx`

## 7. JMeter 优惠券领取并发压测（未完成）

**目标**：用 JMeter 对同一优惠券类型发起高并发领取，验证库存不超卖、同一用户只能领一张、Redis 预扣与 DB 最终一致。

### 现状与差距

- 已有：`VoucherController` 的 `POST /api/vouchers/{type}/claim`、Redis Lua 预扣（`RedisVoucherClaimAdapter`）、`voucher-claim` MQ、worker 幂等落库、`VoucherStockReconcileJob` 对账、`cleanup-test-data.ps1`。
- 缺口：没有可重复执行的 JMeter 测试计划、批量账号 token 池、并发同步点与结果断言；没有把压测后的 Redis/DB 对账固化成脚本。

### 实现步骤

1. 选一个库存充足且无用户持有的券类型，记录 `voucher_stock.stock`、Redis `voucher:stock:{type}`、`voucher:pending:count:{type}` 基线。
2. 准备一批测试账号，用 JMeter CSV Data Set 读取用户名/密码/token；或先用登录接口换取 token 写入 CSV。
3. 建 JMeter Test Plan：线程组 + HTTP Cookie/Header Manager + Synchronizing Timer；请求 `POST /api/vouchers/{type}/claim`，断言 200/409 的业务 code 与响应时间。
4. 分别跑 200、500 并发；统计成功领取数、`VOUCHER_ALREADY_CLAIMED`、`VOUCHER_SOLD_OUT`、异常数。
5. 压测后校验：DB `voucher_record` 数量与 `stock` 变化一致；Redis 库存不为负；`pending` 归零；同一 `user_id + type` 最多一条 `available/locked`。
6. 故障场景：停 MQ 或 worker 后领取，确认发送失败回滚 Redis；恢复后确认 `pending`/库存可对账，不出现永久挂死。
7. 清理测试券/任务/Redis key，把 JMeter 脚本和校验 SQL 放进 `bench/scripts` 或 `tests/jmeter`。

### 验收标准

- [ ] 200/500 并发下 `voucher_record` 成功数不超过初始库存
- [ ] 同一用户并发请求最多成功一次，其余返回 `VOUCHER_ALREADY_CLAIMED`
- [ ] Redis 库存始终 `>= 0`，结束后 `pending = 0`
- [ ] DB 库存与领取记录数量满足 `初始库存 = 剩余库存 + 成功领取数`
- [ ] MQ/worker 故障时请求失败且 Redis 预扣回滚，恢复后无脏 pending
- [ ] JMeter 报告、校验 SQL、清理步骤可重复执行

### 参考文件

- `campus-dash-backend/dash-presentation/src/main/java/com/campusdash/presentation/VoucherController.java`
- `campus-dash-backend/dash-infrastructure/src/main/java/com/campusdash/infrastructure/cache/RedisVoucherClaimAdapter.java`
- `campus-dash-backend/dash-worker/src/main/java/com/campusdash/worker/VoucherClaimConsumer.java`
- `campus-dash-backend/bench/scripts/seed.sql`
- `cleanup-test-data.ps1`

## 8. 聊天消息归档到 history_message + 上翻历史分页（已完成 2026-10-29）

**目标**：`chat_message` 只保留最近 24 小时的聊天消息；worker 每 4 小时把更早的消息批量归档到结构完全一致的 `history_message`；广场上翻在实时表消息读完后，继续按 `beforeId` 从历史表分页加载。

### 现状与差距

- 已有：`chat_message` 表（TEXT / SYSTEM / ERRAND / VOUCHER，含 `errand_id`、`errand_status`、`client_msg_id`）、`JdbcChatMessageRepository`、`QueryChatHistoryUseCase`、`ChatController` 的 `GET /api/chat/messages`、`SquareChat` 的 `beforeId` 上翻。
- 已有：Redis `chat:{square}:recent` 只缓存最近 10000 条 payload；它不负责历史归档。
- 缺口：`history_message` 尚未建表；worker 没有归档任务；`QueryChatHistoryUseCase` 只查 `chat_message`，实时表删空后无法继续上翻。
- 归档边界暂按“当前时间 - 24 小时”实现；如果必须按“上一个自然日”归档，再把 cutoff 改成 `CURDATE()`，不要同时用两套边界。
- 两个表要求结构一致，因此不额外加 `archived_at` 等字段；归档幂等依靠主键 `id`。

### 实现步骤

1. 建表：在 `init.sql` 增加 `history_message`，用 `CREATE TABLE history_message LIKE chat_message`（或等价 DDL），保证列、索引、默认值一致；增加迁移脚本 `migrate-history-message.sql`。
2. 在 worker 新增 `ChatMessageArchiveJob`：`@Scheduled(cron = "0 0 */4 * * *")`，每次以 `NOW() - INTERVAL 1 DAY` 为 cutoff；用 Redis `SETNX` 锁防止多 worker 重复执行。
3. 分页归档：每批 1000 条，按 `id` 升序读取 `chat_message WHERE created_at < cutoff`；同一事务内 `INSERT IGNORE INTO history_message SELECT ...` 后按 id `DELETE FROM chat_message`；失败回滚并记录日志。
4. 归档前后要处理 Redis 热缓存：归档只删 DB，不直接依赖缓存；至少保证 `chat:{square}:recent` 不会因归档出现“DB 已无、缓存仍有”的永久脏数据（必要时按归档 id 精确剔除或让缓存 TTL 自然淘汰）。
5. 扩展 `QueryChatHistoryUseCase`：`beforeId` 查询先查 `chat_message`，不足 limit 时继续查 `history_message WHERE id < beforeId`；`afterId` 同样兼容实时/历史，结果按 id 升序合并，返回给前端结构保持不变。
6. 前端 `SquareChat` 继续使用现有 `beforeId` 上翻；确认实时表读空时后端仍返回历史分页，不能因某次返回空就把 `hasMore` 永久置 false（除非历史表也无数据）。
7. 增加测试：24h 边界内/外数据、跨表 beforeId、跨表 afterId、重复归档幂等、归档失败回滚、两个 worker 并发只执行一次。
8. 兼容 ERRAND 卡片状态：归档后 `JdbcErrandRepository.appendStatusLog` 仍需同时更新 `history_message` 中对应的 ERRAND 行，或只归档任务已进入终态的卡片；否则历史卡片状态会停在 PUBLISHED。
9. 用真实账号验证：构造超过 24h 的消息，手动触发 job，确认 `chat_message` 数量减少、`history_message` 增加且 id/内容一致，页面可继续上翻。

### 验收标准

- [x] `history_message` 与 `chat_message` 的列、类型、索引一致
- [x] 每 4 小时 job 只归档 `created_at < NOW()-1天` 的消息，重复执行不产生重复行
- [x] 归档后 `chat_message` 保留最近 24h，`history_message` 内容与 id 完整
- [x] 广场先加载实时表，实时表读完后可用 `beforeId` 继续加载 `history_message`
- [x] `afterId` 断线补洞在实时/历史表边界上仍正确
- [x] 多 worker 并发时归档任务只执行一次
- [x] 超 24h 数据不会在聊天窗口丢失，刷新后仍能上翻查看
- [x] 已归档 ERRAND 卡片在任务状态变化后仍能更新为正确状态

**完成记录**：2026-10-29。worker 启动自动归档 12 条；beforeId 从实时最小 id 查询返回历史 12 条；afterId 从历史最大 id 查询返回实时 50 条；停 app 插入消息后重连，页面自动补出 RECONNECT_AFTERID_OK；worker 重启归档 0 条。history_message 由 CREATE TABLE LIKE chat_message 创建，ERRAND 状态同时更新两张表。

### 参考文件

- `campus-dash-backend/docker/init.sql`、`docker/migrate-chat-errand.sql`
- `campus-dash-backend/dash-domain/src/main/java/com/campusdash/domain/chat/model/ChatMessage.java`
- `campus-dash-backend/dash-application/src/main/java/com/campusdash/application/usecase/chat/QueryChatHistoryUseCase.java`
- `campus-dash-backend/dash-infrastructure/src/main/java/com/campusdash/infrastructure/persistence/JdbcChatMessageRepository.java`
- `campus-dash-backend/dash-worker/src/main/java/com/campusdash/worker`
- `campus-dash-frontend/src/components/SquareChat.tsx`


## 9. 聊天广场 1000→10000 在线用户与 WebSocket 压测（未完成）

**目标**：验证聊天广场在 1000、3000、5000、10000 个长连接同时在线时的连接成功率、广播延迟、消息丢失/重复、断线重连补洞与服务端资源边界，形成可复现的容量结论。

### 现状与差距

- 已有：`WsHandler` 的 `chat.send`、`ChatWebSocketBroadcaster`、`WsSessionRegistry`、Redis 聊天历史缓存（最近 10000 条）、`RedisChatRateLimiter`、`history_message` 跨表分页/断线 `afterId` 补洞。
- 缺口：没有批量建号与批量登录脚本，没有可配置并发的 WebSocket 压测客户端，没有统一的连接数/广播 P99/错误率/资源采样报告，也没有明确的最大安全在线数。
- 压测会同时打 Tomcat 线程、WebSocket session、Redis、MySQL；需要与 TODO #7 的优惠券 JMeter 分轮执行，避免互相污染指标。

### 实现步骤

1. 固定环境：单机 Docker Compose、记录 app/worker/MySQL/Redis 容器资源上限与 JVM 参数，压测前清空测试任务和聊天消息，记录基线。
2. 批量准备 1000/3000/5000/10000 个测试账号：优先直接写 `user` 表 + 批量登录拿 token；token 写入 CSV，避免压测过程中登录接口成为瓶颈。
3. 编写 WebSocket 压测客户端（Node `ws` 或 JMeter WebSocket Sampler）：支持 ramp-up、每连接鉴权、加入广场、定时 `chat.send`、记录 connect 成功率/首条消息延迟/广播延迟、断线重连。
4. 分档执行：1000 → 3000 → 5000 → 10000，每档稳定运行 5–10 分钟；每档采集 app CPU/内存/GC、Tomcat busy threads、WS session 数、Redis 内存与命令延迟、MySQL QPS/连接数。
5. 广播正确性：固定发送 N 条带序号消息，统计每个在线连接收到的条数、重复数、乱序数；验证发送者回显和他人广播一致。
6. 断线补洞：随机断开 5%–10% 连接后重连，验证 `afterId` 补回缺失消息且不重复；再验证上翻历史跨 `chat_message`/`history_message` 边界。
7. 找到边界：记录首次出现连接失败、广播 P99 超阈值、OOM/线程耗尽或 Redis/MySQL 饱和的并发档位；给出建议最大在线数和扩容方向。
8. 清理测试账号、聊天消息、Redis key 和测试任务，把脚本、CSV 生成器、采样 SQL 与报告放到 `bench/scripts` 或 `tests/ws-load`。

### 验收标准

- [ ] 1000 在线连接成功率 ≥ 99%，广播 P99 可量化
- [ ] 3000/5000 在线连接成功率、错误率、资源曲线有记录
- [ ] 10000 在线档位明确“通过/不通过”，并给出首个瓶颈（线程/内存/Redis/MySQL）
- [ ] 每条测试消息在每个在线连接上恰好收到一次，无重复、无不可接受的乱序
- [ ] 断线重连后 `afterId` 补洞正确，跨历史表上翻不丢消息
- [ ] 压测报告包含环境、脚本、原始指标、结论与建议最大在线数
- [ ] 压测后测试账号/消息/Redis key 全部清理

### 参考文件

- `campus-dash-backend/dash-presentation/src/main/java/com/campusdash/presentation/realtime/WsHandler.java`
- `campus-dash-backend/dash-presentation/src/main/java/com/campusdash/presentation/realtime/ChatWebSocketBroadcaster.java`
- `campus-dash-backend/dash-presentation/src/main/java/com/campusdash/presentation/realtime/WsSessionRegistry.java`
- `campus-dash-backend/dash-infrastructure/src/main/java/com/campusdash/infrastructure/cache/RedisChatHistoryCache.java`
- `campus-dash-frontend/src/components/SquareChat.tsx`
- `campus-dash-backend/dash-bench`


## 10. v2 意图路由：发单 / 查询 / 接单 / 领券（已完成 2026-11-01）

**目标**：一条消息进来先判定走哪条链路——发单进现有状态机，查询/接单/领券进工具循环；路由判定可用单测覆盖。

### 现状与差距

- 已有：\`agent/app/agent/intent.py\` 的 \`Intent\` 只覆盖发单（APPEND/EDIT/PUBLISH/CANCEL/QUERY 等，其中 QUERY 是"查看草稿"不是查数据）；\`agent/app/agent/graph.py\` 的 \`_classify\` 调模型 + 规则兜底
- 缺口：没有"查数据/接单/领券"三类意图；图里只有 \`check\` 一个分支点，没有入口路由；两套分类会重复调用模型

### 实现步骤

1. \`Intent\` 增加 \`QUERY_DATA\`、\`GRAB\`、\`COUPON\`（保留现有值不动，v1 行为不受影响）
2. \`AgentState\` 增加 \`route\`、\`tool_calls\`、\`tool_result\`
3. \`build_graph\` 在 \`extract\` 之前加 \`route_node\`，输出 \`route\`；新增 \`add_conditional_edges("route", route_after_route, {"task": "extract", "tool": "tool_node"})\`
4. 路由用一份独立的短 system prompt（5 类 + 各 2 个例子），命中不了时规则兜底（余额/几单/接单/领券关键词）
5. 未命中任何类别 → 默认 \`task\`，保持 v1 体验

### 验收标准

- [x] "我钱包还有多少钱" → \`route=query\`
- [x] "帮我接两单" → \`route=grab\`
- [x] "把能领的券都领了" → \`route=coupon\`
- [x] "帮我取个快递" → \`route=task\`
- [x] \`tests/test_router.py\` 覆盖 8 条样例且全绿

### 参考文件

- \`agent/app/agent/graph.py\`：图定义与条件边
- \`agent/app/agent/intent.py\`：意图枚举与规则兜底
- \`agent/app/agent/prompts.py\`：提示词模板

## 11. MCP 工具层 + function call 循环（已完成 2026-11-01）

**目标**：agent 能通过 MCP 发现并调用工具，模型用原生 \`tool_calls\` 决策；工具实现与 agent 进程解耦。

### 现状与差距

- 已有：\`extractor._call_llm\` 只发 \`messages\`，不传 \`tools\`；\`pyproject.toml\` 里没有 \`mcp\` 依赖；实测当前模型（deepseek-v4-flash-ascend1）**支持** OpenAI 兼容的 tool_calls
- 缺口：没有 MCP server/client，没有工具循环，没有把用户 token 安全传给工具的机制

### 实现步骤

1. \`pyproject.toml\` 增加 \`mcp>=1.0\`
2. 新建 \`agent/mcp_server/server.py\`，用 \`FastMCP\` 暴露工具（先放一个 \`ping\` 打通链路）
3. 新建 \`agent/app/tools/mcp_client.py\`：stdio 启动子进程 → \`initialize\` → \`tools/list\` 转 OpenAI tools schema → \`tools/call\` 执行
4. \`_call_llm\` 增加 \`tools\` / \`tool_choice\` 入参并解析 \`tool_calls\`（注意与 \`response_format\` 的兼容，必要时关掉 json_mode）
5. 新建 \`tool_node\`：模型返回 \`tool_calls\` → 逐个执行 → 以 \`role="tool"\` 回填 → 再请求，最多 3 轮防死循环
6. 鉴权：用户 token 由服务端注入工具参数（不让模型生成），工具内用 \`httpx\` 调后端

### 验收标准

- [ ] \`python -m agent.mcp_server.server\` 能启动，\`tools/list\` 返回 \`ping\`
- [x] 单测：假 MCP server + 假 LLM 返回 tool_calls，循环 2 轮后收敛
- [x] 日志可看到 tools schema 与实际调用参数
- [x] 连续两轮工具调用不会重复执行写操作（幂等键）

### 参考文件

- \`agent/app/agent/extractor.py\`：\`_call_llm\` 与 JSON 解析
- \`agent/app/agent/graph.py\`：图与节点组织方式
- \`agent/pyproject.toml\`：依赖

## 12. 发单工具化 + 最优优惠券（已完成 2026-11-01）

**目标**：READY 后说"发布吧"直接建单；任何状态说"取消"清空草稿并换新会话；每次给出悬赏金额后卡片展示当前可用的最大优惠券并自动使用。

### 现状与差距

- 已有：\`POST /api/errands\` 建单、前端"确认发布"按钮；\`service.record_published()\` 收尾（置 CLOSED + 卡片标记 + 补消息）；\`service.record_cancelled()\` 置 CANCELED 但**不清空 draft**
- 缺口：模型不能自己发单；取消后草稿仍在；没有券查询与"最优券"计算

### 实现步骤

1. MCP 工具 \`publish_errand\`：从 draft 取字段 + \`voucherId\`，透传 token 调 \`POST /api/errands\`
2. READY 状态下识别到 PUBLISH → 调用 \`publish_errand\` → 成功后复用 \`record_published\`
3. 新增工具 \`cancel_draft\`：\`session.draft = TaskDraft()\`、置 CANCELED、开新会话（历史卡片保留）
4. 新增工具 \`best_voucher(reward_cents)\`：调 \`GET /api/vouchers\`，筛 \`minAmountCents <= reward\`，按抵扣金额降序取第一张
5. \`reward_cents\` 每次变化后调用一次，结果写进消息 \`meta.voucher\`，卡片展示"可用券 -X 元，实付 Y"
6. 发布时带上 \`voucherId\`

### 验收标准

- [x] 说"帮我取快递 8 块，菜鸟驿站到 3 号楼 502" → 卡片显示可用券与实付
- [x] 说"发布吧" → 真实建单，session 变 CLOSED
- [x] 任意状态说"取消" → draft 清空、session CANCELED、卡片保留
- [ ] 连说两次"发布吧"不会建两单

### 参考文件

- \`agent/app/service.py\`：\`record_published\` / \`record_cancelled\`
- \`campus-dash-backend/dash-presentation/src/main/java/com/campusdash/presentation/ErrandController.java\`：建单、取消
- \`campus-dash-backend/dash-presentation/src/main/java/com/campusdash/presentation/VoucherController.java\`：券列表与领取

## 13. 查询意图：个人数据 + 平台数据（已完成 2026-11-01）

**目标**：一句话查钱包余额、我的任务、信用分与排名、我的优惠券。

### 现状与差距

- 已有：\`GET /api/wallet\`、\`GET /api/errands/mine\`、\`GET /api/credit/self\`、\`GET /api/credit/ranking\`、\`GET /api/vouchers/mine\`
- 缺口：agent 没有任何查询工具；也没有把多工具结果组织成自然语言的逻辑

### 实现步骤

1. 工具 \`get_wallet\` → \`GET /api/wallet\`
2. 工具 \`list_my_errands\` → \`GET /api/errands/mine\`（返回单数、各单标题与金额）
3. 工具 \`get_credit\` → \`GET /api/credit/self\`
4. 工具 \`get_ranking\` → \`GET /api/credit/ranking\`
5. 工具 \`list_my_vouchers\` → \`GET /api/vouchers/mine\`
6. 结果由模型组织成口语化回答，金额统一"元"，查不到时给明确原因

### 验收标准

- [x] "我还有多少钱" → 返回余额
- [x] "我发了哪些单" → 列表 + 合计金额
- [x] "我的信用分和排名" → 分数与名次
- [x] "余额和信用分分别多少" → 并行调两个工具

### 参考文件

- \`campus-dash-backend/dash-presentation/src/main/java/com/campusdash/presentation/WalletController.java\`
- \`campus-dash-backend/dash-presentation/src/main/java/com/campusdash/presentation/CreditController.java\`
- \`campus-dash-backend/dash-presentation/src/main/java/com/campusdash/presentation/ErrandController.java\`

## 14. 接单意图：批量自动接单（已完成 2026-11-01）

**目标**：说"接两单"就自动挑单并接单；单轮最多 5 单，未给数量默认 1 单，按实付金额从大到小。

### 现状与差距

- 已有：\`GET /api/errands\`（广场列表）、\`POST /api/errands/{errandId}/grab\`（接单）
- 缺口：没有排序/截断/并发失败的补偿逻辑；agent 不能发写请求

### 实现步骤

1. 工具 \`list_square_errands\` → \`GET /api/errands\` 过滤 PUBLISHED，带实付金额
2. 工具 \`grab_errand(errandId)\` → \`POST /api/errands/{errandId}/grab\`
3. 组合工具 \`grab_best(count)\`：按实付降序 → 截断 \`min(count, 5)\` → 逐单调用，收集成功/失败
4. 409（已被抢）时顺延下一单，并在回答里说明

### 验收标准

- [x] "接一单" → 接到金额最高的 1 单
- [x] "接 3 单" → 3 单且金额降序
- [x] "接 10 单" → 最多 5 单并提示上限
- [x] 部分单被抢时顺延并如实汇报

### 参考文件

- \`campus-dash-backend/dash-presentation/src/main/java/com/campusdash/presentation/ErrandController.java\`

## 15. 领券意图：一键领取（已完成 2026-11-01）

**目标**：一次把当前用户所有可领优惠券领完。

### 现状与差距

- 已有：\`GET /api/vouchers\`（可领列表）、\`POST /api/vouchers/{type}/claim\`（领取）
- 缺口：没有"查询可领 → 逐个领取 → 汇总"的组合工具

### 实现步骤

1. 工具 \`list_claimable_vouchers\` → \`GET /api/vouchers\`，过滤仍有库存且未领过的
2. 工具 \`claim_voucher(type)\` → \`POST /api/vouchers/{type}/claim\`
3. 组合工具 \`claim_all_vouchers\`：查询 → 逐个领取 → 汇总成功/失败与新增张数
4. 幂等：已领过的不重复领，再问时提示"暂无可领"

### 验收标准

- [x] "把能领的券都领了" → 领取数等于可领数
- [x] 紧接着再问一次 → 提示暂无可领
- [x] 部分失败时如实汇报失败原因

### 参考文件

- \`campus-dash-backend/dash-presentation/src/main/java/com/campusdash/presentation/VoucherController.java\`

## 16. RAG 知识库：扩展语料与检索优化（未完成）

**目标**：把知识库从「校园基础设施」扩到「项目说明书 / 常见问答」，并把检索质量做成可回归的指标。

### 现状与差距

- 已有：`agent/knowledge/` 13 篇（宿舍/商铺/食堂/驿站/教学楼/校门/场馆）、125 块，关键词 + 向量混合检索，`scripts/eval_kb.py` 15 条评测集 Top1 命中 15/15
- 缺口：没有项目说明书类语料；评测集太小（15 条）且是自造的；没有重排（rerank）；向量检索在块数上万后会变慢

### 实现步骤

1. 新增 `knowledge/manual/`：项目说明书、常见问答（如何发单、如何接单、仲裁规则）
2. 评测集扩到 50 条以上，覆盖每类知识；把 Hit@k / Top1 做成脚本输出
3. 检索加一层轻量重排（用模型对 top-8 打相关性分，取前 4）
4. 块数超过 2 万时切到 Qdrant，保留同一套 `search_knowledge` 接口

### 验收标准

- [ ] 评测集 ≥50 条，Hit@3 ≥ 90%、Top1 ≥ 80% 有记录
- [ ] 「怎么发单」这类项目问题能答到，且引用到说明书原文
- [ ] 加/改知识文件后重启即可生效，无需手工建表

### 参考文件

- `agent/app/knowledge/search.py`
- `agent/scripts/eval_kb.py`
- `agent/knowledge/README.md`

## 17. 检索排序：长查询相关度衰减（未完成）

**目标**：修掉「西区学专超市几点关门」这类长查询被宿舍块抢走 Top1 的问题。

### 现状与差距

- 现状：MySQL InnoDB 全文对长查询的相关度会衰减，同一块用「超市」查得 1.47 分、用「西区学专超市」查只有 0.19；评测集 Top1 48/50（2026-11-03 修好向量融合后从 47 涨到 48）
- 差距：换存储（ES / Qdrant）或自己算 BM25 才能真正解决

### 实现步骤

1. 在 `keyword_search` 里把查询切成 2~3 个核心词分别检索，按命中数加权合并
2. 或者本地算 BM25（语料才 125 块，纯 Python 毫秒级）
3. 用 `scripts/eval_kb.py` 回归，目标 Top1 ≥ 96%

### 验收标准

- [x] 拒答 = 100%（已达成）
- [x] Hit@3 = 100%（2026-11-03 达成）
- [x] Top1 = 96%（2026-11-03 达成，但靠的是修向量融合，不是解决 InnoDB 衰减）
- [ ] 「西区学专超市几点关门」能排到 Top1（目前 Hit@3 内，Top1 仍是 2 号楼）
- [ ] 「西区学专超市几点关门」「东区宿舍是哪几栋」都能命中正确块

### 参考文件

- `agent/app/knowledge/search.py`
- `agent/scripts/eval_kb.py`

## 18. 修 FundOperationSafetyTest 的既有失败（未完成）

**目标**：让后端全量测试回到全绿。

### 现状与差距

- 现状：`mvn test` 有 2 例失败——`settlement_accepts_publisher_and_internal_worker_and_balances_ledger`（期望 1 条 creditEvent，实际 2 条）、`arbitration_to_runner_commits_through_the_transactional_step_proxy`（第 2 条信用事件被判定为"事务外写入"）
- 已验证：回退 2026-11-03 的状态机改动后仍失败，说明与本次需求无关
- 疑似原因：665f4fd 给发单人也加了 +2 信用分，但该测试的期望序列没同步更新

### 实现步骤

1. 确认生产路径里两条 creditEvent 是否都在同一事务内（SettleErrandUseCase 中两处 applyEvent 看起来都在）
2. 若是测试未更新：把期望序列补成 2 条 creditEvent，并让仲裁用例的 fake 事务管理器接受第二条
3. 若是仲裁路径真的在事务外写：把发单人加分挪进 `ArbitrateSettleStep` 的事务范围

### 验收标准

- [ ] `mvn test` 全绿（34/34）
- [ ] 「事务外写入」断言不再触发

### 参考文件

- `campus-dash-backend/dash-application/src/test/java/com/campusdash/application/usecase/FundOperationSafetyTest.java`
- `campus-dash-backend/dash-application/src/main/java/com/campusdash/application/usecase/SettleErrandUseCase.java`


## 19. 抢单请求补上 X-Request-Id（未完成）

**目标**：让「同一次用户动作的重试」复用同一个请求幂等键，使 `errand:idem:` 这层真正生效。

### 现状与差距

- 现状：`campus-dash-frontend/src/api.ts` 的 `grab()` 只发 POST，不带 `X-Request-Id`；`ErrandController.grab` 兜底 `UUID.randomUUID()`，每次点击都是新键。
- 后果：请求级幂等形同虚设，目前只靠 `SISMEMBER grabbed` 与 `uk_errand_round_user` 兜底（业务正确，但重复请求仍会打到 Redis/DB）。

### 实现步骤

1. `api.ts`：`grab(id, requestId?)` 支持可选 header；`request()` 透传 `X-Request-Id`。
2. 详情页抢单按钮：点击时生成一次 UUID 存 ref，失败重试复用；成功后清空。
3. 压测脚本（`dash-bench`）保持每次新键，用于验证并发场景。

### 验收标准

- [ ] 同一按钮连续点击 N 次，Redis `errand:idem:` 只出现 1 个键，`GRAB` 返回 `DUPLICATE_REQUEST` 而非再次进入 L4。
- [ ] 换账号/换任务仍是新键，互不影响。

### 参考文件
- `campus-dash-frontend/src/api.ts`
- `campus-dash-backend/dash-presentation/src/main/java/com/campusdash/presentation/ErrandController.java`

## 20. WebSocket 多实例推送（未完成）

**目标**：后端多实例部署时，广场消息与站内通知能推到任意实例上的连接。

### 现状与差距

- 现状：`WsSessionRegistry` 是进程内 `ConcurrentHashMap`，A 实例广播只覆盖自己持有的连接。
- 差距：水平扩容后用户收到的消息取决于连到了哪个实例。

### 实现步骤

1. 方案 A：Redis Pub/Sub，频道 `ws:broadcast`，各实例订阅后只推本地连接（实现最轻）。
2. 方案 B：复用 RocketMQ 新增 `ws-fanout` topic，每实例一个消费者组。
3. 发送侧每连接一个有界队列 + 独立发送线程，避免慢连接拖住广播。

### 验收标准

- [ ] 起两个实例，客户端分别连不同实例，任一方发消息两边都能收到。
- [ ] 慢连接不阻塞其他连接（压测 1000 连接下 P99 可接受）。

### 参考文件
- `campus-dash-backend/dash-presentation/src/main/java/com/campusdash/presentation/realtime/WsSessionRegistry.java`

## 21. WS 握手 token 改为一次性 ticket（未完成）

**目标**：避免 access token 出现在 URL（网关日志、代理记录、浏览器历史）。

### 现状与差距

- 现状：`WsAuthHandshakeInterceptor` 从 `?token=` 取 token，原因是浏览器原生 WebSocket 不能自定义 header。
- 差距：token 生命周期 120 分钟，泄露面比短时效 ticket 大。

### 实现步骤

1. 新增 `POST /api/auth/ws-ticket`：用当前会话换一个 30 秒、一次性（`GETDEL`）的 ticket。
2. 握手拦截器改为校验 ticket，并映射回 userId。
3. 前端连接前先取 ticket，再拼到 URL。

### 验收标准

- [ ] ticket 用过一次即失效，重复使用拒绝握手。
- [ ] 日志中不再出现 access token。

### 参考文件
- `campus-dash-backend/dash-presentation/src/main/java/com/campusdash/presentation/realtime/WsAuthHandshakeInterceptor.java`
