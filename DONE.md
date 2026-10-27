# DONE

> 已完成需求的增量日志。每完成一项就**追加一条**（最新的放最上面），只记"做了什么 + 怎么验证"，
> 详细设计仍放在 `docs/` 与 `Process/`。格式：日期 / 目标 / 改动 / 验证。

## 2026-10-27 · 广场任务金额改为跑腿实收

- **目标**：任务列表展示跑腿实际到手金额，而不是发布悬赏全额。
- **改动**：`ErrandController.toCard` 新增 `commissionCents / runnerBearCents / netRewardCents`，公式为 悬赏 − floor(悬赏×8%) − 0.35×券面；卡片标签由「悬赏」改为「实收」，广场一览的合计也改为实收合计。
- **验证**：15 元单 + 2 元券 → 卡片显示「实收 ¥13.10」（1500−120−70）；接口字段 reward=1500 / commission=120 / bear=70 / net=1310。


## 2026-10-27 · 详情返回文案、消息中心未读角标与「未读」筛选

- **目标**：返回文案统一为「返回」；消息中心数字只显示未读且为 0 时不渲染；新增未读筛选。
- **改动**：
  - 详情页两处返回链接文案改为「返回」，href 仍按来源（广场 / 消息中心）区分。
  - 消息中心标题角标改为数据库未读数（/api/notifications/unread），为 0 时不渲染；标记已读后本地递减。
  - 筛选栏新增「未读」，只渲染 read=false 的消息；点击后乐观置读并从「未读」列表移除。
- **验证**：页面显示「消息中心 3」，未读 tab 3 条全部高亮；点击一条后详情返回文案为「返回」，来源 ?from=notifications 保留。


## 2026-10-27 · 任务卡片显示用户名、通知金额改元、未读高亮与来源返回

- **目标**：广场卡片显示用户名；消息金额用元；未读高亮、点击已读；从消息进详情后返回消息中心。
- **改动**：
  - `ErrandController.toCard` 增加 `publisherName/grabberName`（查 user 表），卡片与详情不再显示 ID。
  - 结算/退款/仲裁通知文案由「分」改「元」（worker 与实时推送两处）；历史消息前端用正则把「1310 分」显示为「13.10 元」。
  - `NotificationView` 增加 `read`；新增 `POST /api/notifications/{id}/read`；未读消息加淡黄底，点击乐观置读并刷新侧栏角标。
  - 消息跳详情带 `?from=notifications`，详情页返回链接指向 `#/notifications`。
- **验证**：
  - 卡片 `publisherName=Admin`；新结算通知「你获得 9.20 元」；未读 5 → 标记一条 → 4。
  - 消息页 4 条未读高亮；点击后进入 `#/detail/…?from=notifications`，返回链接 `#/notifications`。


## 2026-10-27 · 清理测试任务 + 平台资金流水（佣金/营销）视图

- **目标**：删除库里测试发布的任务；让佣金户与营销户的每笔入账/出账可见、可核对。
- **改动**：
  - 删除全部非终态任务（DRAFT/PUBLISHED/LOCKED/ACCEPTED/PICKED_UP/DELIVERED/DISPUTED）及其 grab_record / status_log / escrow / escrow 流水；托管中的 22,200 分退回发单人钱包。
  - `WalletRepository.findPlatformLedger` + `GET /api/admin/ledger`：按账户名/类型返回 COMMISSION、MARKETING 的 `wallet_ledger` 记录。
  - 管理台新增「资金流水」页：时间、账户、方向、金额、余额、业务号、类型。
- **验证**：
  - 清理后非终态任务 0 条，全局 `SUM(DEBIT)-SUM(CREDIT)=0`。
  - 用券结算 1500 元单：同一 `bizNo` 下 4 条流水——托管 DEBIT 1300、跑腿 CREDIT 1310、佣金 CREDIT 120、营销 DEBIT 130；管理台接口返回佣金 120 / 营销 130 两条。


## 2026-10-27 · 账号下拉排除 Admin；登录页可一键返回工作台

- **目标**：普通用户的切换列表不出现管理员；从「返回登录页」进来后不用重新输密码就能回到广场。
- **改动**：
  - `TopBar` 过滤 `role === 'Admin'` 的账号，管理员不进入普通用户切换列表。
  - `App` 在 `#/login` 时把当前会话（用户名/角色）传给 `AuthPage`；`AuthPage` 在**登录按钮下方**显示「当前仍登录为 xxx + 返回任务广场/管理台」，点击只改 hash，不调用登录接口、不清 token；未登录过时整块不渲染。
- **验证**：无头浏览器实测下拉仅「用户：2001」（预置 Admin + 2001 两个账号）；登录页出现「当前仍登录为 2001 / 返回任务广场」，点击后 `location.hash = #/square`，全程无登录请求。

## 2026-10-27 · 侧栏账号下拉直接显示「用户：xxx」

- **目标**：左下角不再「用户名一行 + 下拉框一行」，下拉框本身就显示「用户：laizl」。
- **改动**：`TopBar` 去掉 `.profile-name` 文本行，把 `用户：` 前缀放进 option 文案；`.profile-select` 改为单行，select 字号/字重与原来的用户名一致。
- **验证**：构建通过；下拉选中项即显示「用户：<用户名>」，切换账号时整行文字随之变化。

## 2026-10-27 · 账号切换（本机账号簿）、返回登录页入口、右上角只显示用户名

- **目标**：左下角按钮改为回登录页；退出只在右上角；侧栏下拉可切换本机登录过的账号；右上角菜单不显示 id。
- **改动**：
  - `api.ts` 新增本机账号簿 `dash_accounts`（userId/username/role/accessToken/refreshToken），登录时 upsert，退出时移除；`switchAccount` 先用 refresh 换新 access，再恢复会话并重载。
  - `TopBar` 下拉改为账号簿列表（"xxx（当前）"），左下角退出按钮换成 `#/login` 链接；右上角菜单头只留用户名。
  - `App` 新增 `#/login` 路由：已登录也能回到登录页，不退出当前会话。
- **验证**：CDP 实测 2001 → 2002 切换成功（`dash_user=2002`，侧栏显示「用户：2002」）；菜单文本仅「2001 / 退出登录 / 注销账户」；全量 131 个测试通过。


## 2026-10-27 · 50 个种子账号、钱包 user_name、右上角账号菜单与注销

- **目标**：准备 50 个演示账号（各 500 元）；注册时同步建钱包并冗余用户名；右上角头像下拉支持退出与注销；注销把余额转入佣金账户并记流水。
- **改动**：
  - `wallet_account` 新增 `user_name`，迁移脚本回填历史数据；`WalletAccount` 增加字段并更新全部构造点。
  - `RegisterUserUseCase` 加 `@Transactional`，在同一事务内插入 `user` 与 `wallet_account`（余额 0）。
  - 新增 `DeleteAccountUseCase` + `DELETE /api/account`：禁止注销管理员、禁止有进行中任务；余额全额转佣金户（`CLOSE` 类型一借一贷），删除 user 与钱包行。
  - `TopBar` 头像点击弹出菜单（退出登录 / 注销账户），注销带二次确认与错误展示。
- **验证**：
  - 50 个种子账号写入 `USER.md`，余额合计 2,500,000 分；L1 借贷差 0，50 个钱包快照与流水完全一致。
  - 注册后立刻能查到同名钱包（余额 0）；充值 500 后注销：user 行 0 条、钱包 0 条、佣金户 +50000，`close:{userId}` 两条流水。
  - Admin 注销返回 `ACCOUNT_DELETE_FORBIDDEN`；全量 131 个测试通过。


## 2026-10-27 · 优惠券过期：确认消费者 + 新增兜底扫描 job

- **目标**：确认延迟消息确实有人消费；补一条不依赖 MQ 的兜底。
- **现状**：`dash-worker` 里已有 `VoucherExpireConsumer`，订阅 `voucher-expire`（group `dash-voucher-consumer`），收到消息调用 `markExpired(voucherId)`，SQL 只把 `status='available'` 改成 `expired`。
- **改动**：新增 `VoucherExpireScanJob`，每 60 秒（首轮 15 秒）扫描 `status='available' AND expire_time <= NOW()` 的券批量置 `expired`，每条最多 200 张；`VoucherRepository.expireOverdue`。
- **验证**：
  - 插一张已过期券 → 扫描日志「本次作废 1 张」，状态变 `expired`；
  - 插一张未过期券 + 到期本地消息 → 消费者日志「优惠券到期处理 changed=1」，状态变 `expired`；
  - 全量 131 个测试通过。


## 2026-10-27 · 登录页：注册/改密入口合并到验证码下方一行

- **目标**：两个入口同一行、放在验证码下方，且只有下划线方框本身可点击。
- **改动**：`AuthPage` 把「注册账号 / 修改密码」从密码框下方移到验证码下方，用 `auth-link-row` 横向排列（注册账号左对齐表单边框，两入口间距 28px）；`auth-link-box` 只包住下划线文字，行容器不绑定点击。登录态仍保持 Tab 只走三个输入框（入口 `tabIndex=-1`）。
- **验证**：无头浏览器截图确认两入口同排、位于验证码下方且带下划线；bundle 中旧 `auth-switch-row` 已移除。

## 2026-10-27 · 登录页「修改密码」

- **目标**：未登录也能改密码，凭用户名 + 原密码 + 新密码 + 图形验证码。
- **改动**：
  - 新增 `ChangePasswordUseCase`：先消费验证码，再查用户、校验原密码、校验新密码强度（8-32 位且含字母数字）、禁止与旧密码相同，最后更新哈希。
  - `POST /api/auth/password`（加入 `/api/auth/**` 免登录白名单）；`UserRepository.updatePassword` 只更新 `password_hash / password_updated_at / updated_at`。
  - 登录页新增「忘记密码？修改密码」入口，同一卡片切换到修改模式（用户名 / 原密码 / 新密码 / 验证码），成功后回登录并提示。
- **验证**：
  - 注册临时账号 `pwdcheck01` → 原密码错误返回 `BAD_CREDENTIALS` → 正确修改返回 `OK`；
  - 旧密码登录失败、新密码登录成功；库里哈希前缀 `pbkdf2-sha256$120000$`，长度 90。
  - 全量 131 个测试通过。


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
