# DONE

> 已完成需求的增量日志。每完成一项就**追加一条**（最新的放最上面），只记"做了什么 + 怎么验证"，
> 详细设计仍放在 `docs/` 与 `Process/`。格式：日期 / 目标 / 改动 / 验证。

## 2026-10-28 · 多账号会话：登录页快速切换、Admin 切换

- **目标**：多用户登录信息按账号保存；只有显式「退出登录」才撤销服务端 token；登录页与管理台都能快速切换。
- **改动**：
  - 登录页顶部新增本机账号列表（头像/用户名/角色/当前标记），点击直接用 refresh 换新 access 进入，不重新输密码。
  - TopBar 切换菜单重新包含 Admin；切到 Admin 由 App 按角色跳转 #/admin，普通用户进管理台会被守卫送回广场。
  - Admin 管理台左下角新增同款账号切换菜单，可在 Admin 与普通用户间切换。
  - 显式退出才从账号簿移除并调用 /api/auth/logout 撤销 refresh/access；返回登录页不撤销。
- **验证**：登录页显示 stuhuceap + Admin 两项；点 Admin 落到 #/admin；管理台菜单 2 项，切回 stuhuceap 落到 #/square；退出后 refresh 返回 UNAUTHORIZED，重新密码登录正常。


## 2026-10-28 · 账号切换菜单固定浮层；用户分页顶部副本

- **目标**：点开切换菜单时原 div 不再跳动；平台用户表格右上角也放一份分页。
- **改动**：
  - 菜单改为 createPortal 到 body + fixed 定位（按触发器 rect 计算），不参与侧栏布局；加 140ms 淡入上移动画。
  - 外部点击/滚动/resize 自动收起；按钮位置实测点击前后 top 均为 837。
  - 平台用户卡片头部右侧新增「上一页 / 第 x / y 页 / 下一页」，与底部分页共用状态。
- **验证**：菜单 fixed、bottom=71px、位于触发块上方；头部与底部各一个 pager，均显示第 1 / 3 页。


## 2026-10-28 · 侧栏账号切换改为菜单式

- **目标**：切换用户的下拉与用户展示，与右上角「退出登录/注销账户」菜单同款。
- **改动**：
  - 侧栏原生 select 改为「头像 + 用户名 + 账号数 + 下拉箭头」触发器；
  - 点开显示与右上角一致的菜单卡片：当前账号打勾、其他账号点击切换、底部「登录其他账号」；
  - 支持点外部/切路由自动收起。
- **验证**：菜单向上展开，两项账号（stuhuceap 当前 / studpafna 点击切换），样式与右上角账号菜单一致。


## 2026-10-28 · 卡片操作同排；平台配置强制下线

- **目标**：库存与保存/上下架/删除同一行；在线用户可从管理台强制下线。
- **改动**：
  - 优惠券卡片库存行合并：库存输入 + 保存 + 上架/下架 + 删除。
  - 新增 ForceLogoutPort（Redis auth:forced:{userId}，12h）；强制下线关闭该用户全部 WS 并打标记。
  - AuthInterceptor 与 WS 握手检查标记，命中返回 401 / 拒绝握手；重新登录成功清除标记。
  - 平台配置用户表增加「操作」列：在线可点、离线置灰；成功后绿色 toast「用户XXX已下线」并立即回查列表。
- **验证**：在线 stuhuceap 强制下线后列表立即离线、旧 token 返回 401；重新登录后接口恢复 200；离线用户按钮全部 disabled。


## 2026-10-27 · 优惠券卡片按钮同排、提示居中偏下、广场券排序

- **目标**：卡片操作按钮放在同一行；新增/删除提示居中偏下；广场优惠券按满减金额降序、同额按优惠金额降序。
- **改动**：
  - 保存、上架/下架、删除统一放到卡片底部操作行；库存输入独占一行。
  - toast 改为 fixed 底部居中（bottom 52px，translateX(-50%)）。
  - VoucherController 对 ONLINE 券按 thresholdCents desc、discountCents desc 排序。
- **验证**：卡片操作行依次为「保存/下架/删除」；接口顺序 11(2500/1500) → 10(2500/1000) → 9(2000/800) → 8(2000/500) → …


## 2026-10-27 · 优惠券卡片化展示；用户列表在线优先

- **目标**：优惠券管理改为卡片式（每行最多 3 个）但保留全部操作；平台用户把在线用户排到最前。
- **改动**：
  - 优惠券列表改为卡片网格：编号/满减规则/库存输入+保存/状态/上架下架/删除全部保留；响应式 3→2→1 列。
  - 用户列表按在线优先排序（先取全量、按 WsSessionRegistry 在线状态排序后分页），删除“仅 role=user，每页 20 人”描述。
- **验证**：卡片前 3 个同一行、第 4 个换行；在线种子用户 stuhuceap 排在第一位 online=true，离线用户紧随其后；优惠券恢复 11 条。


## 2026-10-27 · 平台配置：佣金/券抽成比例与用户列表

- **目标**：Admin 可在线调整佣金比例与优惠券抽成比例；查看普通用户列表（在线状态、上次登录、余额）。
- **改动**：
  - sys_config 表 + JdbcPlatformConfig；结算、仲裁、发布改为读该配置，PUT 后立即生效。
  - GET/PUT /api/admin/config；GET /api/admin/users（role=user，20/页，联查钱包）。
  - 在线状态取 WsSessionRegistry 中该用户的 WebSocket 连接数；前端绿/红点。
  - 管理台「平台配置」页：比例表单、收入公式 I = R₁×x + R₂×y、用户分页表。
- **验证**：配置 8%/35%；用户总数 51、首页 20 条、余额 ¥500；把券比例改为 40% 后发布 15 元券单，runner_bear=80（200×40%）；修改回 8%/35%。


## 2026-10-27 · 优惠券管理列表样式与删除交互

- **目标**：优惠券列表沿用资金流水表格样式，修复删除无响应，补充成功提示。
- **改动**：
  - 列表表头/行分隔/斑马纹/淡色表头与资金流水一致；列名「类型」改「编号」，去掉 # 前缀；表格满宽。
  - 删除改为内联二次确认（不再用 window.confirm，浏览器拦截时表现为无反应）；确认后调用删除接口并重新加载。
  - 新增成功用绿色 toast「新增成功」，删除成功用红色 toast「删除成功」。
  - 新增区左上角描述改为「金额单位：元」。
- **验证**：UI 新增后行数 +1、绿色「新增成功」；点删除出现确认条，确认后行数 -1、红色「删除成功」；列表回到 11 条。


## 2026-10-27 · 管理端优惠券管理

- **目标**：Admin 可查看/新增/改库存/上下架/删除优惠券。
- **改动**：
  - voucher_stock 新增 status(ONLINE/OFFLINE)、threshold_cents、discount_cents；迁移脚本 + init.sql 同步。
  - 券的规则改为读数据库（type 不再受 VoucherType 枚举限制）：领取与发布校验 threshold/discount、上下架状态；删除同时清理 voucher_record。
  - 管理接口：GET/POST /api/admin/vouchers、PUT /{type}/stock、POST /{type}/status、DELETE /{type}。
  - 管理台新增「优惠券管理」页：新增表单（满减/优惠/库存）、列表展示类型/规则/库存/状态，行内保存库存、上下架、删除。
- **验证**：新增 type=12（满12元减3元、库存5）→ 用户端可见并可领取；改库存 8；下架后用户端不可见且领取返回 VOUCHER_NOT_AVAILABLE；删除后 stock 记录与该类型 voucher_record 全部清除，列表回到 11 条。


## 2026-10-27 · 优惠券使用时间改为左对齐末行

- **目标**：使用时间放在卡片最后一行，与金额/规则左对齐。
- **改动**：`wallet-voucher-used-at` 从右对齐改为 `justify-self:start; text-align:left`，占卡片整行、位于规则行下方。
- **验证**：截图确认「使用时间:2026-10-27-22:11:27」与「满10元可用」左边缘对齐，位于卡片最下方。


## 2026-10-27 · 优惠券使用时间与「未使用 / 已使用」切换

- **目标**：区分未使用与已使用券；发布锁券时记录使用时间，过期时记录结束时间；已使用券展示使用时间。
- **改动**：
  - voucher_record 新增 finish_at（迁移脚本 + init.sql）；发布 lockForErrand 时写入 NOW，markUsed/markExpired/expireOverdue 都维护该字段；refund 恢复 available 时清空。
  - /api/vouchers/mine 返回 finishAt；钱包新增「未使用 / 已使用」切换，默认未使用；已使用卡片右下角显示“使用时间:YYYY-MM-DD-hh:mm:ss”。
  - cleanup-test-data.sql 删除任务时把其锁定的券恢复为 available/expired，避免 locked 孤儿。
- **验证**：15 元券单发布时 finish_at=2026-10-27 22:11:27，结算后 status=used 保持不变；钱包已使用 tab 显示「使用时间:2026-10-27-22:11:27」；测试任务清理后 PUBLISHED=0。


## 2026-10-27 · Redis 缓存清理与排行榜 1 分钟重建

- **目标**：并发测试后清空 Redis 缓存；排行榜可通过后台 job 定期重建。
- **改动**：
  - 新增 CreditRankingRebuildJob，默认 60 秒按 credit_score 全量 ZADD（可配 dash.credit.ranking-rebuild-ms）。
  - 手动清理 errand/credit/captcha 缓存，保留 auth 会话与 errand:bloom；重启后初始器重建榜单 52 个账号。
  - cleanup-test-data.ps1 额外清理 errand:slot / grabbed / candidates。
- **验证**：DBSIZE 34（31 auth + credit:rank + bloom 2），ZCARD=52；worker 日志「排行榜缓存已重建 52 个账号」。


## 2026-10-27 · 测试规范：只用 USER.md 账号、标题统一「测试任务」

- **目标**：禁止再用 Admin 跑用户侧流程，测试数据统一命名并清理。
- **改动**：
  - cleanup-test-data.sql 增加 publisher_id/grabber_id = '001' 条件，Admin 发过/接过的一并清理。
  - 约定：业务动作只用 USER.md 的种子账号，Admin 仅用于管理端；测试任务标题统一为「测试任务」；中文 JSON 用 curl UTF-8 文件发送。
- **验证**：用 stuhuceap 发布「测试任务」、studpafna 抢单，卡片显示 publisherName=stuhuceap；清理后 PUBLISHED=0、Admin 相关任务=0。


## 2026-10-27 · 资金流水表格视觉优化

- **目标**：表格满宽、行列都有分隔线，表头与汇总行用淡色底，提升可读性。
- **改动**：表头 `#faf7ef` 背景 + 列分隔线；数据行横向/纵向分隔、偶数行 `#fcfaf5` 斑马纹、hover 淡黄；汇总行加边框与列分隔；固定列宽（时间/方向/金额/余额/类型），业务号自适应。
- **验证**：表格宽度 1070px 占满面板内容区；表头背景 rgb(250,247,239)，单元格右边框 rgb(244,239,227)。


## 2026-10-27 · 资金流水改为单账户切换

- **目标**：佣金/营销不再同屏，用按钮选择后单页展示，默认佣金账户。
- **改动**：管理台「资金流水」新增账户切换按钮（佣金账户 / 营销账户），只渲染一个 LedgerPanel，切换时重置到近一周、第一页；净收入横幅保留在外层。
- **验证**：默认选中佣金账户，面板 1 个、余额 ¥6.80；切到营销账户后仍 1 个面板、余额 ¥999990.40、支出 -¥6.50。


## 2026-10-27 · 系统账户名统一 Admin；资金流水按账户分页、分区间统计

- **目标**：修正 ESCROW/COMMISSION/EXTERNAL/MARKETING 的乱码 user_name；资金流水按佣金/营销分栏分页，并按周/月/年/历史统计收支，外层展示净收入。
- **改动**：
  - 数据库与 SystemAccountInitializer 统一四个系统户 user_name = Admin；新增 migrate-system-account-name.sql。
  - WalletRepository 新增 queryPlatformLedger(type, from, offset, limit) 与 netPlatformIncomeCents；GET /api/admin/ledger 改为 account/period/page/size，返回账户余额、区间收支、总数与净收入。
  - 管理台「资金流水」拆成两个面板，各自四个时间按钮 + 上一页/下一页；顶部显示净收入（Commission 收入 − Marketing 支出）；副标题删去“均来自 wallet_ledger”。
- **验证**：接口 COMMISSION 余额 680、周收入 680；MARKETING 余额 99999040、周支出 650；净收入 30。页面两面板、净收入 ¥0.30、分页正常。


## 2026-10-27 · 结算双方各 +2 信用分，榜单与信用足迹等高滚动

- **目标**：结算后发单人与跑腿都加分；榜单区域与左侧信用足迹对齐，超出 20 名在卡片内滚动。
- **改动**：
  - `CreditEvent.settlePublisherBizNo` 新增发单人 bizNo；`SettleErrandUseCase` 与 `ArbitrateSettleStep` 在结算/仲裁支持跑腿时给发单人也写 +2 事件。
  - 结算提交后同时刷新发单人与跑腿的 ZSET 分数，并推送双方 credit.changed。
  - 信用页两栏改为等高 620px，左右内容区各自 `overflow-y:auto`；移动端取消限高。
- **验证**：15 元单结算后 001 60→62、1001→62；credit_event 出现 settle: 与 settle-pub: 两条 +2；榜单左右高度均 618px，列表可视 464px、内容 1094px（20 行可滚轮滑动）。


## 2026-10-27 · 注册即建信用分并同步 Redis 榜单

- **目标**：所有用户（无论是否发生过结算）都有 credit_score 记录，并出现在 Redis 排行榜。
- **改动**：
  - `CreditRepository.ensureScore`：INSERT ... ON DUPLICATE KEY，缺记录时写入初始 60 分。
  - `RegisterUserUseCase` 在注册事务内建档，并 `ZADD credit:rank:{campusId}` 60 分。
  - 新增 `CreditScoreInitializer`：启动时遍历所有账号补 credit_score，并按 DB 分数全量同步 ZSET。
  - `UserRepository.findAll` 支撑初始化。
- **验证**：启动日志「信用分/榜单已同步 52 个账号」；注册 creditcheck01 后 credit_score=60、ZSCORE=60、ZCARD 52→53；榜单接口返回 53 条且 username 正常。测试账号已清理。


## 2026-10-27 · 信用榜单显示用户名、清理无关用户

- **目标**：榜单不再显示 userId；删掉测试注册的账号与缓存中的演示成员。
- **改动**：
  - `CreditController.ranking` 增加 `username`（查 user 表），前端优先显示用户名，找不到才回退到 id。
  - 删除 student_zhang / student_li / stu_46490 / probe2710 / probe6705 / pwdcheck01 六个测试账号及其钱包、流水、信用、消息、券记录。
  - `ZREM credit:rank:1` 移除 1001 / 2001 / 2002 演示身份，榜单只保留真实账号。
- **验证**：`GET /api/credit/ranking` 返回 `username=stukgv3rx`；Redis `TYPE credit:rank:1` 为 zset；用户表剩余 52 个（Admin + laizl + 50 个种子账号），L1 仍为 0。


## 2026-10-27 · 测试任务清理脚本

- **目标**：每次测试/联调结束后，把测试发布的任务清干净，避免广场和“我的任务”被脏数据污染。
- **改动**：
  - 新增 cleanup-test-data.ps1 + cleanup-test-data.sql。
  - 非终态任务：托管金额退回发单人，删除任务、escrow、grab、status_log、credit_event、notification 与 escrow 流水；终态测试任务只删业务行，保留 wallet_ledger（钱已结算）。
- **验证**：执行后 PUBLISHED=0、L1 借贷差=0；脚本可重复执行（幂等）。
- **约定**：以后每次跑完测试或联调，先执行 .\cleanup-test-data.ps1 再汇报完成。


## 2026-10-27 · 未读消息改为鼠标移入即已读

- **目标**：不需要点击，鼠标接触未读消息就置为已读。
- **改动**：仅前端。消息项从 onClick 置读改为 onMouseEnter（同时保留 onFocus 处理键盘），点击只负责跳转详情；仍走原 POST /api/notifications/{id}/read，乐观更新并把该条移出「未读」筛选。
- **验证**：无头浏览器触发 mouseover 后，未读角标 2 → 1，高亮消息 2 → 1。


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
