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

