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

## 2. 用户身份从 long 切到 UUID + 用户名密码登录（未完成）

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

- [ ] 用户名 + 密码 + 验证码登录成功，token 的 sub 等于数据库 `user_id`
- [ ] 密码错误 / 账号不存在都返回 `BAD_CREDENTIALS`，不泄露账号是否存在
- [ ] 验证码错误或重复使用返回 `CAPTCHA_INVALID`
- [ ] `status=0` 的账号返回 `ACCOUNT_DISABLED`
- [ ] 用注册用户（UUID）完整走通发布→抢单→结算，资金与流水正确
- [ ] Admin（`001`）能登录并拿到 `role=Admin`
- [ ] 旧接口 `{userId}` 在 `demo-login=false` 时返回 401
- [ ] 全量测试通过

### 参考文件

- `campus-dash-backend/docker/init.sql`、`docker/migrate-user.sql`：`user` 表
- `campus-dash-backend/dash-application/.../usecase/RegisterUserUseCase.java`：注册校验顺序
- `campus-dash-backend/dash-infrastructure/.../auth/Pbkdf2PasswordHasher.java`：密码哈希
- `campus-dash-backend/dash-infrastructure/.../auth/ImageCaptchaService.java`：验证码
- `campus-dash-backend/dash-presentation/.../auth/RegistrationController.java`：`/captcha`、`/register`
- `campus-dash-backend/dash-bootstrap/.../bootstrap/AdminAccountInitializer.java`：Admin 种子
- `campus-dash-backend/docs/注册与验证码.md`：表结构与接口说明

