# DONE

> 已完成需求的增量日志。每完成一项就**追加一条**（最新的放最上面），只记"做了什么 + 怎么验证"，
> 详细设计仍放在 `docs/` 与 `Process/`。格式：日期 / 目标 / 改动 / 验证。

## 2026-10-27 · 修复仲裁台乱码/点击无效，退款恢复优惠券

- **目标**：管理台争议内容正常显示、裁决按钮可用；支持发单人时把券退回。
- **原因**：
  - 列表里那条争议是早期用 PowerShell 造的测试数据，中文被写成 `????`，且它的 `escrow_order` 已被测试清库删掉，裁决必然 `ESCROW_NOT_FOUND`；
  - 裁决依赖 `window.confirm`，部分浏览器会静默拦截，表现成"按钮没绑定事件"；
  - 发布页把 `voucher.type` 当成 `voucherId` 提交，且 `Number()` 转换雪花 ID 丢精度，带券发布会 `VOUCHER_INVALID`。
- **改动**：
  - 删除脏争议数据；标题统一由 UTF-8 接口返回。
  - 裁决改为**内联二次确认**（不再用 `window.confirm`），按钮带"处理中"，失败显示横幅。
  - `RefundErrandUseCase` 退款事务里调用 `restoreByErrand`：未过期 → `available`，已过期 → `expired`。
  - 发布页提交真正的 `voucherId`（字符串），金额仍由后端按券类型计算。
- **验证**：
  - 新争议标题正确显示「仲裁退券联调：代取文件」。
  - 裁决支持发单人：发单人 +1300、任务 `REFUNDED`、券恢复 `available`（有效期不变）。
  - 带券发布：实付 1300 / 券 200 / 跑腿承担 70。

## 2026-10-27 · 修复仲裁点击无响应、发布用券失败与退款还券

- **目标**：管理台裁决可用；发单人胜诉时优惠券按有效期归还；发布页能用券。
- **原因**：
  - 待仲裁里是早期脚本造的测试单，托管记录已被测试清理，裁决返回 `ESCROW_NOT_FOUND`，表现为"点了没反应"；
  - 裁决用 `window.confirm`，被浏览器拦截时同样表现为无响应；
  - 发布页把券的 `type` 当作 `voucherId` 传，且雪花 ID 用 `Number` 会丢精度，用券发布必然 `VOUCHER_INVALID`；
  - 库里测试任务标题是 PowerShell 写坏的 `????`。
- **改动**：
  - `AdminApp` 改成内联二次确认 + "处理中…"，不依赖浏览器对话框；错误用顶部 banner 展示。
  - `restoreByErrand` 按 `expire_time` 决定：未过期 `available`，已过期 `expired`。
  - 发布页 `voucherId` 全程用字符串（雪花 ID 不丢精度），option value 改为真实 `voucherId`。
  - 清理乱码测试标题为「历史测试任务」。
- **验证**：
  - 15 元带券（D=200）发布 → 实付 1300 → 送达 → 争议 → 裁决支持发单人：状态 `REFUNDED`，发单人余额回到 9500，券 `locked → available` 且 `used_errand_id` 清空。
  - 标题中文在库与接口中均正常；全量 131 个测试通过。

## 2026-10-27 · 修复 Admin 登录仍落在 #/square

- **目标**：Admin 登录后必须进管理台，刷新也保持。
- **原因**：
  - 入口 `index.html` 没有缓存策略，浏览器按启发式缓存了旧 HTML，页面实际跑的是**没有角色分流**的旧 JS；
  - 旧会话的 localStorage 里没有 `dash_role`，刷新后前端只能按 `user` 处理。
- **改动**：
  - nginx 入口 HTML 加 `Cache-Control: no-cache`，发版后普通刷新即可拿到新 assets 哈希。
  - 新增 `GET /api/auth/me`：服务端确认 `userId/username/role`；演示身份不在 `user` 表时返回 `role=user`（不能返回 401，否则前端会把演示会话登出）。
  - `App` 启动时先用 `/me` 校准角色，校准完成前管理路由显示"正在校验管理员身份…"，不再瞬间误判跳广场。
- **验证**：
  - `/api/auth/me`：Admin → `role=Admin`；演示 2001 → `role=user`。
  - 响应头 `Cache-Control: no-cache`；bundle 含 `/api/auth/me` 与 `admin-boot`。
  - 全量 131 个测试通过。

## 2026-10-27 · 管理台骨架 + 仲裁工作台（按角色分流）

- **目标**：Admin 登录后进入管理台处理争议；同一登录接口按角色落地，权限由后端兜底。
- **改动**：
  - 新增 `AdminInterceptor`（order=2，只拦 `/api/admin/**`）：已登录但非 `role=Admin` 返回 403，未登录由 `AuthInterceptor` 返回 401。
  - 新增 `/api/admin/disputes` 列表、`/{id}` 详情、`/{id}/arbitrate` 裁决；裁决允许 `role=Admin` 或配置的仲裁员 9001。
  - 登录响应返回 `role`，前端写入 `dash_role`；`App` 按角色把 `#/admin` 或 `#/square` 作为落地页，非管理员访问 `#/admin` 直接弹回。
  - 新增 `src/admin/AdminApp.tsx`：独立管理台布局（侧栏、待仲裁列表、详情、两种裁决按钮）。
- **验证**：
  - Admin 登录返回 `role=Admin`；普通用户调 `/api/admin/disputes` → 403，无 token → 401。
  - 建争议单（800 元）→ 管理台列表/详情正常 → 裁决支持跑腿：状态 `SETTLED`，跑腿 +736、佣金 +64、托管 −800。
  - 全量 131 个测试通过。

## 2026-10-27 · 券面分摊比例 40% → 35%，优惠券库存整体减半

- **目标**：降低跑腿承担的券面成本；同时把券的投放量砍半，控制营销支出。
- **改动**：
  - `dash.voucher.runner-bear-ratio`：0.40 → 0.35（平台补贴变为 0.65D）。
  - `VoucherType` 初始库存整体减半：50 / 25 / 12 / 6 四档。
  - `docker/migrate-voucher-stock-halve.sql` 重置线上库存；`init.sql`、`migrate-voucher.sql` 种子同步。
  - `docs/优惠券.md` 更新库存表与结算公式。
- **验证**：
  - 库存表：type1..11 = 50/25/12/50/25/12/6/50/25/50/25。
  - 实测 15 元单用 10-2 券（D=200）：实付 1300，跑腿 +1310（1500−120−70），佣金 +120，营销 −130，四笔合计 0。
  - 全量 131 个测试通过。

## 2026-10-27 · 优惠券接入结算（平台抽成 8%）

- **目标**：发布任务时可使用优惠券抵扣实付；券面成本由跑腿与平台分担，平台从佣金差价获利。
- **改动**：
  - 佣金率 5% → 8%（`dash.settle.commission-rate`）。
  - 发布时锁定券（`voucher_record.status = locked`），托管金额改为实付 `P = R - D`。
  - 结算拆四笔：托管户出 `P`、跑腿得 `R×0.92 - 0.4D`、佣金户得 `0.08R`、营销户（-4）出 `0.6D`。
  - 取消/退款把券退回 `available`；结算后 `locked → used`。
  - 发布页新增优惠券选择器与"实付/已减"展示。
- **验证**：全量 131 个测试通过；发布带券任务后核对托管金额、三账户余额与四条流水。

## 2026-10-27 · 优惠券：文案修复 + 钱包展示 + 秒级倒计时

- **目标**：修复满减描述乱码；钱包能看到持有券及有效期。
- **改动**：描述改由启动时的 `VoucherStockInitializer` 回填；`/api/vouchers/mine` 补满减数值；钱包新增「我的优惠券」，秒级倒计时（天 HH:mm:ss），到期自动隐藏。
- **验证**：库里 11 条描述为「满X元减Y元」；页面倒计时逐秒跳动；过期券不展示。

## 2026-10-27 · 惊喜优惠券（库存 / 领取 / 48h 过期）

- **目标**：广场可领取满减券，库存有限，领取后 2 天过期。
- **改动**：`voucher_stock` + `voucher_record` 两张表与 11 种券；扣库存用带条件 UPDATE；过期走本地消息表 + RocketMQ 延迟消息 `voucher-expire`；广场左侧新增「惊喜优惠」，已领取的券显示灰色「已领取」。
- **验证**：领取扣库存、重复领取被拒、手工把到期消息提前后状态变 `expired`。

## 2026-10-27 · 余额充值（双分录）

- **目标**：钱包可直接充值，供演示发布任务。
- **改动**：`POST /api/wallet/recharge`；新用户首次充值自动开户；充值记"外部资金池借记 + 用户贷记"两笔，保证 L1 借贷平衡；钱包页新增充值面板与 10/20/50/100/200/500 元快捷面额。
- **验证**：充值后余额与流水一致、全局 `SUM(借)-SUM(贷)=0`；全量测试通过。

## 2026-10-27 · 用户身份 String 化 + 用户名密码登录

- **目标**：注册用户的 UUID 能直接作为业务身份，登录改为用户名 + 密码 + 验证码。
- **改动**：130 处用户 id 改 `String`，11 个外键列改 `VARCHAR(36)`，字符串比较统一 `equals`；`LoginUserUseCase` 查库签发 token（sub = user_id），`CurrentUser` 改 `ThreadLocal<String>`；前端登录/注册页与 `userId` 全链路 string 化。
- **验证**：注册返回 UUID、登录返回同一 id、Admin 登录拿到 `role=Admin`；全量 131 测试通过。

## 2026-10-27 · 注册、图形验证码与 Admin 账号

- **目标**：用户名 + 手机号 + 两遍密码 + 验证码注册；固定仲裁账号。
- **改动**：`user` 表（UUID 主键、用户名/手机号唯一、角色、头像、密码修改时间等冗余字段）；`GET /api/auth/captcha`（Redis 5 分钟、GETDEL 一次性）；PBKDF2 存密码；启动种入 `001/Admin/12345678`。
- **验证**：8 条校验路径（验证码错误/重放、手机号非法、两次密码不一致、重名、重号）均返回预期错误码。

## 2026-10-27 · 全栈本地部署 + 一键脚本

- **目标**：nginx + 应用 + worker + 中间件一条命令起全套。
- **改动**：`docker-compose.full.yaml`（app/worker/web/mq-init）、nginx 反代 `/api` 与 `/ws`、`start.ps1`（构建 + 重启 + 健康检查 + 日志/停止）；修复 mq-init 只检查单个 topic、nginx `$host` 丢端口导致同源 POST 403。
- **验证**：`http://localhost:8088` 走通注册/登录/发布/抢单/结算；脚本 `-Status`、`-Build web/api/all`、`-Down` 均可用。

## 2026-10-27 · 双 token 自动续期

- **目标**：access 过期后无感续期，登出即失效。
- **改动**：后端 refresh 轮换改 `GETDEL`；登出撤销 refresh；前端单飞刷新（Web Locks 跨标签页）、401 重试一次、失败才跳登录。
- **验证**：`AuthRefreshIT` 3 项、`refresh_single_flight.mjs` 22 项检查。
