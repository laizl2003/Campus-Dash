# DONE

> 已完成需求的增量日志。每完成一项就**追加一条**（最新的放最上面），只记"做了什么 + 怎么验证"，
> 详细设计仍放在 `docs/` 与 `Process/`。格式：日期 / 目标 / 改动 / 验证。

## 2026-10-31 · 修复意图识别误判（"别的没什么要补充的了"被当成任务内容）

- **现象**：用户说结束语仍被追加到任务内容。
- **三个根因**：
  1. 意图分类复用了 `response_format=json_object`，与"只输出一个标签"冲突，推理模型吐出几百个 \r 的乱码；
  2. `normalize_intent` 遇到乱码直接返回 None，静默降级到规则；
  3. 规则兜底用**精确等于**匹配，"别的没什么要补充的了"这种带前后缀说法无法识别。
- **改动**：
  - `classify_intent` 关闭 JSON 模式（`_call_llm(json_mode=False)`）。
  - `normalize_intent` 增加控制字符清洗、JSON 解析、全文标签搜索三层容错。
  - 规则兜底改为子串匹配，补充"没什么/没别的/暂时没有/就这么多"等说法与"不用发/先别发"否定词。
  - 提示词强调只输出标签本身。
- **验证**：真实模型下"别的没什么要补充的了→FINISH"、"快递比较大→APPEND"、"发布吧→PUBLISH"全部正确；端到端对话中结束语不再污染任务内容；`pytest` 37 passed。


## 2026-10-31 · READY 后按意图分支处理，不再一律写入任务内容

- **问题**：信息齐全后，用户说什么都会被塞进任务内容，"发布吧""把金额改成12块"也变成了任务内容。
- **改动**：
  - 新增 `app/agent/intent.py`：7 类意图（APPEND/FINISH/PUBLISH/EDIT/QUERY/CANCEL/CHITCHAT）+ 提示词 + 关键词兜底 + 输出归一化。
  - `LLMExtractor.classify_intent()` 复用 `_call_llm`，只输出标签不改数据；失败返回 None 交给规则兜底。
  - `graph.py` READY 分支按意图执行：APPEND 并入描述、EDIT 走字段抽取、PUBLISH 只标记、CANCEL/QUERY/CHITCHAT 不改草稿。
  - 回复文案按意图区分；响应新增 `intent` 与 `can_publish` 字段。
  - 前端 CANCEL 时清空草稿卡片。
- **验证**：真实模型下 7 类意图全部判对（OK 7/7）；真实多轮对话中仅"快递比较大"进入 description，其余未污染；`pytest` 32 passed。


## 2026-10-31 · 修复 Agent 500：MySQL 长连接失效 + pycache 陈旧

- **现象**：`POST /agent/sessions` 间歇性 500，浏览器与命令行表现不一致。
- **两个根因**：
  1. MySQL 长连接被服务端回收后没有重连，后续请求全部失败。
  2. 镜像里残留 `__pycache__`，Python 加载旧字节码，"改了代码却跑旧逻辑"。
- **改动**：
  - `store_mysql.py` 新增 `_run()` 统一入口：每次取游标先 `ping(reconnect=True)`，异常时重连重试一次；所有方法改走该入口。
  - `Dockerfile` 构建期清理 `__pycache__`，运行命令加 `-B` 禁止写字节码。
  - 新增 `.dockerignore`，排除 `.venv`/`__pycache__`/`tests` 等。
- **验证**：主动 `KILL` MySQL 连接后请求仍 200；连续 30 次创建会话全部成功；浏览器对话正常。


## 2026-10-31 · Agent 会话持久化到 MySQL，修复刷新后 session not found

- **目标**：会话不再存进程内存，容器重启/页面刷新后历史对话仍可继续。
- **改动**：
  - 新增 `migrate-agent-session.sql`：`agent_session`（草稿/轮数/状态）+ `agent_message`（对话记录）。
  - 新增 `app/store_mysql.py`：`MySQLSessionStore` 实现 create/get/save/list/delete/messages。
  - `ChatService` 按 `AGENT_MYSQL_DSN` 自动切换 MySQL/内存，连不上时降级并打日志。
  - 新增 `GET /sessions`（按用户列历史）、`DELETE /sessions/{id}`、`GET /sessions/{id}/messages`。
  - 前端历史列表改为以后端为准，删除走 DELETE，打开历史时按需拉消息。
- **验证**：创建会话→对话→重启容器→ping alive=true→继续对话草稿保留；删除后 DB 中 session 与 message 均为 0 行；`pytest` 23 passed。
- **坑**：Docker 层缓存导致镜像内代码未更新，必须 `build --no-cache` 才能生效。


## 2026-10-31 · 悬浮球接入真实发布、逐项修改、信息完整后追加任务内容

- **目标**：把 Agent 草稿真正发布出去，并支持在卡片上改条目、完整后继续补内容。
- **改动**：
  - `agent/app/main.py` 新增 `POST /publish`，透传浏览器 Authorization 转发到主项目 `POST /api/errands`。
  - 前端 `apiPost` 带 `Authorization`；新增 `onPublish`，发布成功后显示任务编号并广播 `dash:changed`。
  - 草稿卡片支持「修改」逐项编辑（标题/悬赏/取货/送达/截止/任务内容），保存后写回 `raw` 保证发布用真实值。
  - 完整后继续对话不再新增卡片，而是原地更新同一张，避免多张草稿并存时操作错对象。
  - 后端：`required_slots` 为空时进入"追加描述"分支，用户补充内容并入 `description`；`is_no_more` 识别"没有了"。
- **验证**：浏览器完整走通 6 步（对话→补内容→修改金额→保存→发布），DB 中 title/location/start_time/reward/description 全部正确；`pytest` 22 passed。


## 2026-10-31 · LLM 抽取链路：脚手架完成，仅留 HTTP 调用待填

- **目标**：把接入模型后的逻辑全部写好，使用者只需填 API 调用。
- **改动**：
  - 新增 `agent/app/config.py`：`LLMSettings.from_env()` 读环境变量，`usable()` 判断配置是否齐全。
  - 新增 `agent/app/agent/prompts.py`：系统提示词与用户提示词模板（含当前草稿、待追问槽位、当前时间）。
  - 重写 `LLMExtractor`：JSON 容错解析、金额单位换算、时间解析、类型别名、字段校验、重试退避、失败降级到规则兜底；仅 `_call_llm` 留空。
  - 新增 `CircuitBreakerExtractor`：模型连续失败 5 次冷却 30 秒。
  - `default_extractor()` 按环境变量自动选择 LLM 或规则兜底；新增 `GET /agent/status` 排查配置。
  - compose 增加 LLM 环境变量；新增 `agent/LLM.md` 接入说明。
- **验证**：`pytest` 17 passed（含 12 个 LLM 链路测试）；`/agent/status` 未配置时返回 `RuleExtractor`，配置齐全时返回 `LLMExtractor` 并正确降级。


## 2026-10-31 · 悬浮球接入真实 Agent 后端

- **目标**：保留原悬浮球样式与交互，把 mock 数据源换成真实后端对话。
- **改动**：
  - `AgentAssistant.tsx` 重写数据层：调 `/agent/sessions`、`/agent/chat`，草稿由后端 TaskDraft 映射；历史会话存 localStorage；会话失效自动重建。
  - 新增 `agent/Dockerfile`，compose 增加 `agent` 服务（端口 8000，健康检查用 urllib）。
  - `nginx.conf` 增加 `/agent/` 反代到 `agent:8000`；vite dev 代理同步。
  - 修复回填误判：带任务动词的句子（"取快递到3号楼"）不再被当成取货地址。
- **验证**：浏览器中悬浮球可开合，6 轮对话由后端逐项追问，最终草稿字段全部正确；`pytest` 5 passed。


## 2026-10-31 · Agent 发单 Demo：LangGraph + 缺字段追问

- **目标**：先把对话式发单的流程与追问跑通，模型抽取留空，后续直接替换实现。
- **改动**：
  - 新建 `agent/app`：`models.py`（TaskDraft/槽位/必填规则/合并/追问文案）、`agent/extractor.py`（抽取接口 + 规则兜底 + LLMExtractor 空实现）、`agent/graph.py`（LangGraph 状态机 extract→check→ask/ready）、`service.py`（会话与编排）、`main.py`（FastAPI）。
  - `tests/test_flow.py` 覆盖：空抽取追问类型、逐项追问、OTHER 跳过地点时间、裸回答不破坏流程、规则抽取多轮合并。
  - 新增 `agent/README.md`，说明接口、源码位置、追问原理与接入真实模型的位置。
- **验证**：`pytest -q` 5 passed；HTTP 多轮对话从"帮我取个快递"追问到补全取货地/送达地/时间/金额，最终返回 READY 草稿。


## 2026-10-31 · 任务结构化字段：取货地/送达地/开始时间/任务内容

- **目标**：补齐跑腿任务的路线与时间信息，让 Agent 和用户都能表达"取哪、送哪、几点做、注意什么"。
- **改动**：
  - 迁移 `docker/migrate-errand-detail.sql` 与 `init.sql`：errand 增加 `location_from`、`location_to`、`start_time`、`description`。
  - 领域模型/仓储/详情缓存/聊天卡片全部透传新字段；空值统一存 null 并输出 JSON null。
  - 发布页：DELIVERY/BUY/QUEUE 显示取货地、送达地、开始时间；OTHER 只保留标题 + 任务内容；原"任务内容"改名"任务标题"提交 title。
  - 详情页新增路线条与任务内容区块，预览卡同步展示。
- **验证**：DELIVERY 入库 4 字段且详情/卡片正确；OTHER 地点时间均为 NULL；发布页两类表单字段正确；测试任务已取消并清理。修复了 `findPublished` 显式列清单缺新列导致的启动失败。


## 2026-10-31 · Agent 前端目录独立到 agent/agent-frontend

- **目标**：把 AI 悬浮球前端从主前端拆到独立的 agent 目录，构建与启动脚本同步适配。
- **改动**：
  - 新建 `agent/agent-frontend/src`，迁入 `AgentAssistant.tsx`、`agentAssistant.css`，新增 `package.json`（peer react）。
  - 主前端 `vite.config.ts` 增加 `@agent` / `@app` 别名；`tsconfig.json` 增加 `paths` 与 include。
  - `App.tsx` 改为 `import AgentAssistant from '@agent/AgentAssistant'`。
  - `start.ps1` 增加 `agentFrontendDir` 校验，构建日志标明包含 Agent；脚本转为 UTF-8 BOM 修复 PowerShell 5.1 解析。
  - Agent 目录整体移入仓库 `campus-dash-1/agent`。
- **验证**：`npm run build` 通过；`start.ps1 -Build web` 成功构建镜像并部署；页面悬浮球、面板、草稿按钮（修改/取消/确认发布）均正常。


## 2026-10-30 · 发布卡片显示实收金额 + Redis/Docker 学习笔记

- **目标**：广场 ERRAND 卡片展示跑腿实收，而不是发布悬赏；并把 Redis Pub/Sub 与 Docker 基础沉淀到 Learn.md。
- **改动**：
  - PublishErrandUseCase.Result 增加 netRewardCents：悬赏 - 平台佣金 - 券面跑腿承担。
  - ChatErrandNoticeUseCase 的 ERRAND content 增加 netRewardCents。
  - SquareChat 卡片优先显示 netRewardCents，并标注“实收”；旧卡无字段时回退 rewardCents。
  - 新增 Learn.md：Redis Pub/Sub、Docker 容器/网络/卷/Compose、多实例 WS 广播。
- **验证**：测试发布 rewardCents=1000 的任务，API 返回 netRewardCents=900，chat_message content 为 `{"rewardCents":1000,"netRewardCents":900,...}`；测试任务已取消并清理。


## 2026-10-30 · 优惠券两类压测总结

- **目标**：用多用户抢券与同一用户重复抢券两类场景，分别验证防超卖和一人一单。
- **多用户抢券（200/500/1000 并发）**：三次最终均为 `voucher_record=10`、10 个不同用户、stock=0、pending=0；500/1000 分别有 86/591 个 `HttpHostConnectException`，属于入口连接被打满，不是超卖。防超卖通过。
- **同一用户并发（laizl 200 并发）**：200 条 HTTP 200、error=0、平均 100.2ms、P95≈160ms；DB 只有 1 条 laizl/type15 记录，stock 10→9，pending=0，claimed:15=1。一人一单通过。
- **结论**：两类场景都通过；JTL 的 `claim-UNKNOWN` 是 JSON Extractor/JSR223 作用域问题，不影响 DB 结果。后续更值得补的是入口连接容量和多实例 WebSocket，而不是继续加并发数字。


## 2026-10-30 · laizl 同一用户 200 并发一人一单验证

- **目标**：验证同一用户同时发起 200 个领取请求时，是否只会成功领取一张。
- **执行**：`laizl` 单 token，200 线程，Synchronizing Timer=200，POST `/api/vouchers/15/claim`。
- **结果**：JTL 200 条 HTTP 200，error=0，平均 100.2ms，P95≈160ms，max=167ms；DB 只有 1 条 laizl 的 type15 记录，distinct user=1，stock 10→9，Redis pending=0，claimed:15=1。
- **结论**：同一用户 200 并发下一人一单成立，没有重复领取；唯一缺口是 JTL 的 JSON 提取器/JSR223 作用域未生效，label 全为 claim-UNKNOWN，业务码统计需查 DB。
- **产物**：`一人一单.jtl`、`laizl-result/index.html`。


## 2026-10-30 · 优惠券压测数据核对与 2000 账号准备

- **目标**：核对 200/500/1000 并发抢券是否验证防止超卖与一人一单，并准备 2000 个聊天压测账号。
- **测试数据**：
  - 200 并发：DB `voucher_record=10`、distinct user=10、stock=0、pending=0；当时未保存 JTL/HTML。
  - 500 并发：500 请求，HTTP 200=414，连接拒绝=86；DB 仍为 10 条、10 个不同用户、stock=0、pending=0。
  - 1000 并发：1000 请求，HTTP 200=409，连接拒绝=591；DB 仍为 10 条、10 个不同用户、stock=0、pending=0。
- **结论**：防超卖已被 500/1000 并发验证；成功领取用户互不重复。但三组测试都是不同用户并发，没有单独构造“同一用户同时请求”，且 `voucher_record` 没有 `(user_id, voucher_type)` 唯一索引，一人一单目前靠 Redis claimed + DB countAvailable 双重校验，严格的同用户并发还需要单独补测。
- **2000 账号**：已登录 `stu-00001`~`stu-02000`，token 文件 `jmeter-stu-tokens-2000.csv`，供后续聊天广场 WebSocket/在线压测使用。


## 2026-10-30 · 重置测试用户为 stu-00001 ~ stu-10000

- **目标**：为聊天广场与优惠券压测准备 10000 个干净测试账号，仅保留 Admin 与 laizl。
- **改动**：
  - 重置前备份：`D:\Code\Campus_PaoTui\campus-dash-backup-20261030-125615.sql`。
  - 清空旧业务数据：errand、escrow、账本、优惠券记录、通知、聊天/历史、credit_event、local_message 等。
  - 新增 10000 个用户：用户名 `stu-00001`~`stu-10000`，密码统一 `88888888`（PBKDF2-SHA256），手机号 `13800000002`~`13800010001`，钱包 available=100000 分（1000.00 元），credit_score=60。
  - `USER.md` 覆盖为 10002 行账号总表。
- **验证**：`user=10002`、`stu-*=10000`、`wallet USER=10002`、credit=10002；errand/voucher/chat/history/ledger/local_message=0；`stu-00001 / 88888888` 登录成功；Admin 登录成功；Redis 启动后 14 个优惠券库存 key。


## 2026-10-30 · Redis 启动预热审计与已领取标记重建

- **目标**：排查重启后哪些 Redis key 必须从 DB 重建，补齐优惠券“一人一单”标记，避免重启后重复领取。
- **改动**：
  - 新增 `VoucherClaimInitializer`：启动时把 `voucher_record` 中 available/locked 且未过期的券写回 `voucher:claimed:{type}:{userId}`。
  - `VoucherClaimPort.seedClaimed` + `VoucherRepository.findActiveClaims`，TTL 取券过期时间 + 1 小时。
  - 现有预热：`VoucherStockInitializer`（ONLINE 库存）、`GrabSlotInitializer`（PUBLISHED 名额）、`CreditScoreInitializer`（信用分/榜单）。
- **验证**：app 启动日志“已领取标记已从 DB 重建 34 个”；DB active claims=34，Redis `voucher:claimed:*`=34；上架券库存 key 14 个齐备。


## 2026-10-30 · Admin 优惠券操作同步 Redis

- **目标**：新增加缓存、保存重建缓存、下架删库存缓存、上架重建缓存、删除清全部缓存；DB 先写，Redis 后同步，同一事务方法内失败可回滚 DB。
- **改动**：
  - VoucherClaimPort 增加 rebuildStock / evictStock / evictAll。
  - RedisVoucherClaimAdapter pending key 带 type，支持按类型删 pending；evictStock 保留 claimed，evictAll 连 claimed 一起删。
  - AdminController 新增 rebuildStock，库存保存 rebuildStock，下架 evictStock，上架 rebuildStock，删除 evictAll；四个方法加 @Transactional。
  - VoucherStockInitializer 启动时只初始化 ONLINE 券，OFFLINE 券主动清缓存。
- **验证**：新建 stock=12 → Redis 12；保存 12 → Redis 12；下架 → stock/pending key 均不存在；上架 → Redis 12；删除 → stock 不存在且 claimed=0；启动后 14 个 ONLINE 券缓存齐备。


## 2026-10-30 · Admin 优惠券按钮/弹窗配色

- **目标**：下架橙色、上架绿色、删除红色；保存/上架/下架/删除成功弹窗颜色分别绿/绿/橙/红；下架聊天卡片改橙色。
- **改动**：
  - 优惠券操作按钮新增 admin-voucher-offline / -online / -delete 样式。
  - 保存库存、切换状态成功后 setToast；toast 增加 warning 类型。
  - floating-notice 增加 is-warning（浅橙底、橙图标）。
  - SquareChat 中 VOUCHER OFFLINE 卡片使用橙色，DELETED 仍红色。
- **验证**：按钮背景 rgb(217,119,6)/rgb(47,107,74)/rgb(178,59,50)；下架弹窗 is-warning 背景 rgb(255,247,230)，上架 is-success、删除 is-error；测试数据已清理。


## 2026-10-30 · Admin 优惠券库存/上下架/删除聊天卡片

- **目标**：Admin 修改库存、上架、下架、删除完成后推送 VOUCHER 聊天卡片；修改/上架绿色，下架/删除红色。
- **改动**：
  - ChatVoucherNoticeUseCase 增加 STOCK / ONLINE / OFFLINE / DELETED 事件，content 带 event/description/stock；有事务时 afterCommit 再写消息。
  - AdminController 四个操作落库后调用通知。
  - SquareChat VOUCHER 卡片按 event 显示标题和绿/红配色。
- **验证**：创建/改库存/下架/上架/删除连续操作生成 5 条 VOUCHER 消息，event 分别为 CREATED/STOCK/OFFLINE/ONLINE/DELETED；UI 库存更新/上架背景 rgb(232,245,233)，下架/删除 rgb(253,236,234)；测试数据已清理。


## 2026-10-30 · 归档时同步 ERRAND 当前状态

- **目标**：避免历史卡片在归档/任务状态变化时停留在旧状态。
- **改动**：JdbcChatArchiveRepository 的 INSERT SELECT 关联 errand，ERRAND 行的 errand_status 使用 COALESCE(e.status, cm.errand_status)；appendStatusLog 已同时更新 chat_message 与 history_message。
- **验证**：worker 启动归档 10025 条后，live ERRAND 卡片 6 条、history ERRAND 卡片 2 条，关联 errand 查询无状态不一致。


## 2026-10-30 · 固定消息中心外层列宽

- **目标**：消息中心外层 border/列宽不随筛选栏内容或右侧条数变化。
- **改动**：account-message-layout/account-surface 设置 width:100%、min-width:0；消息筛选 tabs 改为 flex:0 1 auto + width:max-content，不再撑满；account-count 限制 45% 并省略。
- **验证**：1280/600 视口下，右侧条数正常/超长时 account-surface 宽度分别保持 691/541 不变；tabs 宽度保持 300，5 个按钮同一行。


## 2026-10-30 · 消息中心筛选栏固定一行

- **目标**：未读/全部消息/结算/退款/其他五个按钮始终一行，不被右侧条数挤到第二行。
- **改动**：消息中心容器增加 account-message-filter；CSS 设 flex-wrap:nowrap，tabs 禁止换行并在超宽时横向滚动，右侧条数 flex-shrink:0。
- **验证**：600px 视口、右侧条数改成长文本，5 个按钮 top 均为 295，未换行；tabs 可横向滚动。


## 2026-10-30 · Admin 平台配置/优惠券管理增加刷新按钮

- **目标**：平台配置、优惠券管理右上角用户名左侧增加刷新按钮，样式与资金流水刷新一致。
- **改动**：AdminApp 顶部 actions 始终渲染 btn btn-ghost + refresh 图标；vouchers/settings 点击执行 window.location.reload()，其他视图保持原 load()。
- **验证**：页面检查两个视图顶部按钮顺序为“刷新 → 头像”，accountNext=刷新，按钮样式与其他视图一致。


## 2026-10-30 · 修复 PUBLISHED 任务全部不可抢

- **现象**：DB 中任务是 PUBLISHED，但点击抢单返回 ERRAND_NOT_GRABBABLE。
- **根因**：Redis 重启/清空后 errand:slot:{errandId} 全部丢失；抢单 Lua 发现名额键不存在返回 -3，未从 DB 重建。
- **修复**：ErrandRepository 增加 findPublished；新增 GrabSlotInitializer，启动时按 PUBLISHED 任务的 slot_total-slot_taken 重建缺失名额键，TTL 7 天。
- **验证**：启动日志“抢单名额已从 DB 重建 6 个”，Redis slot key=6；stutwr7xe 抢单成功返回 OK，DB 变为 LOCKED、slot_taken=1。


## 2026-10-30 · 修复优惠券领取 INTERNAL_ERROR

- **现象**：优惠券库存 Redis 键存在且与 DB 一致（例如 type8=47），但领取返回 INTERNAL_ERROR。
- **根因**：RocketMQ broker 重启后 topic 丢失；mq-init 的 topic 清单没有 voucher-claim，publisher 发送失败，ClaimVoucherAsyncUseCase 回滚并返回 INTERNAL_ERROR。不是 Redis 库存未加载。
- **修复**：mq-init 增加 voucher-claim 的创建、快速检查、校验，以及 dash-voucher-claim 消费组；当前 broker 手动补建 topic/group。
- **验证**：laizl 领取 type8 返回 OK，remaining=46；3 秒内 voucher_record 新增 available 记录，pending=0，Redis/DB 库存同步为 46。


## 2026-10-29 · 聊天最近消息缓存扩到 10000

- **目标**：把聊天 ZSET/Hash 热缓存上限从 2000 提高到 10000。
- **改动**：RedisChatHistoryCache.MAX_SIZE=10000；QueryChatHistoryUseCase.CACHE_WARM_SIZE=10000；HighLight 文档同步。
- **验证**：清空缓存后 GET history 预热 ZCARD=10000、HLEN=10000，耗时约 1.8s；再发送一条消息后仍保持 10000，最旧 id 被挤出、payload 同步淘汰。
- **说明**：ZSET 仍然按 Lua 中的 ZCARD/ZRANGE/ZREMRANGEBYRANK 规则裁剪，不依赖 TTL 控制条数。


## 2026-10-29 · 聊天归档 history_message + afterId 补洞

- **目标**：chat_message 只保留 24 小时；worker 每 4 小时归档到结构一致的 history_message；前后端支持跨表 beforeId 上翻和 afterId 断线补洞。
- **实现**：
  - 新增 history_message（CREATE TABLE LIKE chat_message）及迁移脚本。
  - 新增 ChatArchivePort / JdbcChatArchiveRepository，分批事务 INSERT IGNORE + DELETE。
  - 新增 ChatArchiveJob：每 4 小时 + worker 启动补一次；Redis 锁 chat:{archive}:lock 防重复。
  - QueryChatHistoryUseCase 合并实时表/历史表，beforeId、afterId、latest 均兼容。
  - appendStatusLog 同时更新 chat_message 和 history_message 的 ERRAND 状态。
  - ws.ts 新增 ws.open；SquareChat 维护 newestIdRef，重连后循环 afterId 拉取直到补齐。
- **验证**：
  - worker 启动自动归档 12 条；重启归档 0 条，无重复。
  - beforeId 从实时最小 id 查询返回历史 12 条；afterId 从历史最大 id 查询返回实时 50 条。
  - 停 app 期间插入 RECONNECT_AFTERID_OK，恢复后页面自动补出该消息。
  - 前端构建、后端编译通过。
- **说明**：归档 cutoff 为 NOW()-24h；如果改按自然日归档，只需调整 cutoff。


## 2026-10-29 · 修复广场实收合计科学计数法 + 清理测试日志

- **原因**：后端 Long 金额字段统一序列化为字符串，Square 的 reduce 从 0 开始做字符串拼接，出现 9.07e+24。
- **修复**：totalReward 求和前 Number(...) 转换；实测广场一览显示 ¥751.52。
- **清理**：删除 backend 下 67 个本地测试/编译 .log；.gitignore 增加 *.log，后续不再入库。


## 2026-10-29 · 聊天缓存 key 加 Redis Cluster hash tag

- **目标**：聊天缓存 ZSET/HASH/Lua 多 key 在 Cluster 下同 slot；所有相关 key 统一 hash tag。
- **改动**：key 改为 chat:{room}:recent / payload / errand / message-errand；HighLight 增加方案 B 说明与简历表述。
- **验证**：清理旧 key 后 history 回填 ZCARD=2000、HLEN=2000；HMGET 最新 payload 正常；worker 正常启动。


## 2026-10-29 · 聊天缓存方案 B：ZSET+Hash+HMGET+Lua

- **目标**：ZSET 只存 messageId，Hash 存 payload；任务状态只更新对应 ERRAND 卡片，不再清空全部缓存。
- **结构**：chat:recent:{room} ZSET、chat:payload:{room} Hash、chat:errand:{room} Hash（errandId→messageId）、chat:message-errand:{room} Hash（反向索引）。
- **一致性**：ZADD+HSET+索引+淘汰+TTL 用 Lua ADD；状态更新用 Lua 校验索引和 payload 后 HSET；读取 ZREVRANGE + HMGET。
- **验证**：2000 条时 ZCARD/HLEN 均 2000；抢单后仅对应 payload errandStatus PUBLISHED→LOCKED，ZSET 不变，其他消息保留。


## 2026-10-29 · 发布成功/再发一个任务时移除已用优惠券

- **目标**：发布使用过的券后，“再发一个任务”不再显示上一单已锁定的券。
- **原因**：Publish 页面只在 mount 时加载一次我的券；发布成功后后端已把券置 locked，但前端 vouchers 状态和 voucherId 未清理。
- **改动**：发布成功立即从本地 vouchers 删除该 voucherId、setVoucherId(null)，并 refreshVouchers()；“再发一个任务”也清券并重新拉取。
- **验证**：前端构建通过；已用券不会留在下拉列表，服务端仍以 locked 状态为准。


## 2026-10-29 · 修复优惠券下拉菜单滚动即关闭

- **目标**：优惠券 SelectMenu 在滚轮/拖动菜单滚动条时不关闭，仅页面滚动时关闭。
- **改动**：SelectMenu 的 capture scroll 监听判断 event.target 是否在菜单内，菜单自身滚动忽略。
- **验证**：打开优惠券菜单 7 项；菜单内 dispatch scroll 后仍打开；window 滚动后关闭。


## 2026-10-29 · 修复优惠券异步领取丢单/已持有状态

- **根因**：聊天缓存新增 ObjectMapper 注入后，worker 没有该 Bean，启动即退出；领取接口只做 Redis 预扣 + 发 MQ 就返回成功，worker 未消费导致 voucher_record 不落库，claimed/pending 键残留。
- **修复**：
  - RedisChatHistoryCache 自带 ObjectMapper，不再依赖 Web 容器 Bean。
  - worker 增加 restart: unless-stopped。
  - ClaimVoucherAsyncUseCase 预扣前先查 DB countAvailable，避免 Redis 键缺失时假领取成功。
- **验证**：worker 正常启动且 VoucherClaimConsumer 启动；pending:id=0，Redis/DB 库存一致；laizl/stuf4cpvh 历史 pending 全部落库，30-6(type14) 已在钱包；新生效 type1 领取后 3 秒内 DB/我的券可见；重复领取返回 VOUCHER_ALREADY_CLAIMED。


## 2026-10-29 · 聊天缓存扩到 2000 条

- **目标**：最近消息缓存从 200 条提高到 2000 条，验证新消息是否即时入缓存。
- **改动**：RedisChatHistoryCache.MAX_SIZE=2000；QueryChatHistoryUseCase.CACHE_WARM_SIZE=2000。
- **验证**：清空缓存后 GET history 回填 ZCARD=2000；MEMORY USAGE≈791592 B（约 773 KiB，约 396 B/条）；发送一条消息后 ZREVRANGE 第一条即为该消息，ZCARD 仍 2000。


## 2026-10-29 · Admin WS 灌入 1 万条雪花 ID 消息

- **目标**：删除 seed-chat 伪 ID 数据，改由 Admin 通过 WS 发送 10000 条消息，验证真实雪花 ID 排序与缓存。
- **操作**：临时把 chat rate 提到 100000，Admin WS 连续发送 content 1..10000；完成后恢复 rate=3。脚本 bench/scripts/seed-chat-ws.mjs。
- **结果**：chat_message 共 10000 条，id 241234599940722688..241234930367991808，sender Admin；Redis chat:recent:square ZCARD=200，MEMORY USAGE=80936 B（约 79 KiB，404.7 B/条）。
- **说明**：需求中“1 到 100000 共 10000 条”上限与条数矛盾，按“共 10000 条”执行；若需 100000 条可再跑脚本扩大。


## 2026-10-29 · 回到最新按钮位置调整 + 发送即贴底

- **目标**：按钮左移上移，避开右侧滚动条；用户发送消息立即滚到最新。
- **改动**：按钮 right 8→20、bottom 50→58；send() 开头调用 jumpLatest()。
- **验证**：前端构建通过；按钮位于滚动条左侧；发送时列表立即回到底部，不等 WS 回显。


## 2026-10-29 · 聊天回到最新消息按钮

- **目标**：聊天框右侧靠下增加一键回到底部最新消息按钮，上翻后可见。
- **改动**：SquareChat 增加 atBottom 状态/ref；滚动在底部 40px 内自动隐藏；按钮 absolute right:8 bottom:50，点击滚到底；新消息仅在底部时自动跟随。
- **验证**：列表 scrollTop=2417 在底部按钮隐藏；滚动到 100 后按钮出现；点击后 scrollTop 回到 2417（scrollHeight=2623）。


## 2026-10-28 · 聊天上翻历史 + 1 万条测试消息

- **目标**：前端支持向上滚动加载更早历史；插入 10000 条消息验证 Redis 200 条缓存和分页加载。
- **改动**：
  - SquareChat 监听列表 scrollTop<=20，使用 beforeId 每次加载 50 条；单飞 ref 防重复；prepend 后恢复滚动位置。
  - 保留 hasMore/loadingOlder 状态，顶部显示加载提示。
  - 生成 10000 条 TEXT 消息（id 900000000000000001..+10000，content 1..10000），脚本放 bench/scripts/seed-chat-10000.sql。
- **验证**：首屏 50 条为 9951..10000，Redis ZCARD=200；上翻一次后追加 9901..9950，列表滚动位置保持，ZCARD 仍为 200。


## 2026-10-28 · 聊天最近 200 条 Redis ZSET 缓存

- **目标**：首页进入聊天时缓存最近 200 条，降低重复 DB 查询。
- **改动**：
  - ChatHistoryCachePort + RedisChatHistoryCache：chat:recent:square ZSET，score=message id，ZREM range 保留 200，TTL 24h。
  - JdbcChatMessageRepository.save 后写入缓存（失败不影响消息）。
  - QueryChatHistoryUseCase 最近消息先读缓存，不足回源 DB 并回填 200；beforeId/afterId 仍走 DB。
  - 任务状态变化时 invalidate 缓存，避免 ERRAND 卡片状态过期。
- **验证**：清空缓存后 GET history 回填 12 条；发送一条后 ZCARD 13；最新 member 为本次消息。


## 2026-10-28 · 在线用户列表浮层

- **目标**：点击聊天右上角在线人数，弹出刚好覆盖聊天框的在线列表；当前用户第一且浅黄，其余按 auth:online score 排序。
- **改动**：
  - AuthPort 增加 onlineUserIds()，RedisAuthAdapter 用 ZRANGE auth:online 0 -1（先裁过期）。
  - ChatController 增加 GET /api/chat/online-users，当前用户置顶并补 username/role。
  - SquareChat 在线人数变按钮；absolute inset:0 浮层；当前行 #fff7cc。
- **验证**：API 首项当前用户；浮层 rect 191×283.7 覆盖聊天框 193×285.7；当前行浅黄，列表含 Admin/普通用户。


## 2026-10-28 · 两类消息卡片显示发布时间

- **目标**：ERRAND / VOUCHER 卡片上方显示消息发布时间。
- **改动**：SquareChat 增加 cardTimeLabel（MM/DD HH:mm），两类卡片顶部居中灰色时间。
- **验证**：前端构建通过；卡片时间位于卡片上方，不改变卡片内容。


## 2026-10-28 · 优惠券发布 VOUCHER 聊天卡片

- **目标**：Admin 新增优惠券后向聊天室推浅绿色卡片，标题“新的优惠券”，content 含 description/stock。
- **改动**：
  - ChatVoucherNoticeUseCase 写 msg_type=VOUCHER 消息并广播，content={"description":...,"stock":...}。
  - AdminController.createVoucher 插入库存后调用通知。
  - SquareChat 渲染 VOUCHER 卡片：浅绿 #e8f5e9、绿色文字。
- **验证**：创建满20减3库存25后，chat_message 为 VOUCHER；UI 卡片文本“新的优惠券 / 满20元减3元 / 库存 25”，背景 rgb(232,245,233)；测试数据已清理。


## 2026-10-28 · ERRAND 卡片显示发单人用户名

- **目标**：聊天卡片不再显示发单人 userId，改为 userName。
- **改动**：ChatErrandNoticeUseCase 查询 user 表写入 publisherName；前端优先展示 publisherName，缺失回退 publisherId。
- **验证**：卡片 content 含 publisherName="stuhuceap"；页面显示“发单人 stuhuceap”；测试任务/卡片已清理。


## 2026-10-28 · 发单聊天卡片

- **目标**：发单时向聊天室推 ERRAND 卡片，展示任务类型/标题、金额、发单人 userId；PUBLISHED 可点击，抢单后变灰不可点。
- **改动**：
  - chat_message 增加 errand_id / errand_status；migrate-chat-errand.sql。
  - ChatErrandNoticeUseCase 在发单成功后写 ERRAND 消息并广播；content 为 JSON。
  - appendStatusLog 同步 chat_message.errand_status；前端监听 errand.status 更新卡片。
  - 前端卡片：黄色可点击跳广场；非 PUBLISHED 灰色禁用。
- **验证**：发单后 card=ERRAND/PUBLISHED，content 含 type/title/rewardCents/publisherId；抢单后 DB=errand_status LOCKED；页面 PUBLISHED 卡片为 A 链接黄色，LOCKED 为 DIV 灰色无链接。


## 2026-10-28 · 清理禁言错误里的 sender 调试信息

- **目标**：禁言提示只显示“你已被管理员禁言”，不暴露 userId。
- **改动**：SendChatMessageUseCase 抛 CHAT_MUTED 时去掉 “sender=...” 详情。
- **验证**：禁言账号发送返回 {"code":"CHAT_MUTED","message":"你已被管理员禁言"}；测试后已恢复 message_status=1。


## 2026-10-28 · Admin 禁言/解禁与聊天系统通知

- **目标**：平台配置用户表最后一列增加禁言/解禁；禁言后不能发聊天消息；操作后聊天室显示灰色居中系统消息。
- **改动**：
  - user 增加 message_status TINYINT DEFAULT 1（migrate-message-status.sql）。
  - AdminController 增加 POST /api/admin/users/{id}/message-status；AdminUserQuery 返回 messageStatus。
  - SendChatMessageUseCase 校验 messageAllowed，禁言返回 CHAT_MUTED。
  - ChatNoticeUseCase 写 SYSTEM 消息并 WS 广播；ChatController/Broadcaster 返回 msgType。
  - Admin 表格按钮：禁言橙色 admin-mute，解禁绿色 btn-success；SquareChat 居中灰色系统消息。
- **验证**：禁言后 DB message_status=0、聊天发送 CHAT_MUTED；解禁后发送 OK；SYSTEM 内容为“stuhuceap已被禁言/已解除禁言”；系统消息样式 rgb(153) 灰、background #f0f0f0、align center；表格禁言按钮 rgb(217,119,6)。


## 2026-10-28 · 聊天在线点常绿 + HighLight 补 ZSET 亮点

- **目标**：聊天框在线提示不再先灰后绿，始终绿色；把 ZSET 多端登录/踢人/在线统计写进简历素材。
- **改动**：SquareChat 移除 isWsAvailable 驱动的 online 状态与 5 秒轮询，圆点固定 #2fa060；HighLight 增加 ZSET 会话/在线设计、简历 bullet 与面试表述。
- **验证**：前端构建通过；圆点无灰色初始态。ZSET 行为此前已验证：两 token 单端登出另一端仍有效，Admin 踢人全部 401。


## 2026-10-28 · Redis ZSET 登录态与在线人数

- **目标**：登录态用 Redis 两级索引；主动登出只登出当前浏览器；Admin 踢人撤销全部；在线人数/平台在线统一读 ZSET。
- **结构**：auth:token:{token}（2h）；auth:sessions:{userId} ZSET(token→expireAt)；auth:online ZSET(userId→maxExpireAt)。
- **改动**：
  - RedisAuthAdapter 实现 login/touch/logout/revokeAllSessions/onlineCount/isOnline；ZSET 用 score 表示成员级过期。
  - AuthInterceptor 每次请求 touch 续期 token 与 ZSET 分数。
  - AuthController.logout 仅 closeToken + 当前 token；Admin forceLogout 走 revokeAllSessions + closeUser。
  - ChatController 在线人数、AdminController 用户在线均读 auth:online。
- **验证**：同用户两 token sessions=2；登出1后 sessions=1、token2 仍 200；登出2后 0；Admin 踢人后两 token 均 401；WS A 登录出后 readyState 3，B 仍 1；chat_online=2 与 ZCARD 一致。


## 2026-10-28 · WS 注册表在线人数与登出关闭

- **目标**：后端直接查询所有 WS 连接 userId；登出关闭连接并删除 map key；在线人数用 WS map。
- **改动**：
  - WsSessionRegistry 增加 onlineUserIds() 与 closeUser()。
  - AuthController.logout 关闭该用户全部 WS；Admin forceLogout 复用 closeUser。
  - ChatController /online 改为 wsSessions.onlineUsers()，不再读 DB 标记。
- **验证**：初始在线 1 → 打开 user WS 变 2 → 调 logout 后变 1，且 WS readyState=3（已关闭）。


## 2026-10-28 · 修复 worker 启动失败导致在线状态不清理

- **根因**：worker 组件扫描加载 SendChatMessageUseCase，依赖 ChatBroadcaster（实现只在 presentation），worker 启动失败；UserPresenceSweepJob 停摆，login_status 残留为 1。
- **改动**：worker 增加 WorkerChatConfig，提供 no-op ChatBroadcaster；不影响 app 的真实 WebSocket 广播。
- **验证**：worker 已启动；启动后清理 3 个心跳超时账号；DB login_status=1 从 4 降为 1（仅 Admin）。
- **说明**：平台配置的 online = login_status=1 且 last_seen_at 在 90 秒内；DB 裸查 login_status 会包含未清理的残留，因此此前不一致。聊天在线人数查询也补上 90 秒心跳条件，与管理台一致。


## 2026-10-28 · 聊天框右上角在线人数

- **目标**：两个聊天框右上角显示在线人数，数据来自 user.login_status。
- **改动**：
  - UserRepository 增加 countOnline：SELECT COUNT(1) FROM user WHERE login_status=1。
  - ChatController 新增 GET /api/chat/online。
  - SquareChat 每 30 秒拉取并显示“在线 N”，两个端共用。
- **验证**：/api/chat/online count=4，与 DB login_status=1 的 4 一致。


## 2026-10-28 · 平台收入浅灰边框 + 用户聊天下间距

- **目标**：Admin 平台收入上下边框改浅灰、文字纯白；用户端聊天框底部与钱包留出与 Admin 相近的间距。
- **改动**：admin-wallet 边框 #c8c8c8、文字 #fff、hover 无高亮；SquareChat 用户端 marginBottom -24 → -15，留 9px。
- **验证**：Admin color/borders = #fff/#c8c8c8；用户 frame bottom 785.4、wallet top 794.4，间距 9px。


## 2026-10-28 · 平台收入行白字灰边框、无 hover 高亮

- **目标**：平台收入文字纯白，上下边框灰色，鼠标悬停不高亮。
- **改动**：admin-wallet 的 label/金额改 #fff；border-top/bottom 改 1px #777；hover 保持透明背景。
- **验证**：Computed color 白色，边框灰色，hover 无背景变化。


## 2026-10-28 · 聊天发送按钮内嵌 + Admin 平台收入

- **目标**：两个前端发送按钮改为输入框右侧小方形纸飞机；Admin 用户切换上方显示平台收入（佣金账户余额）。
- **改动**：
  - SquareChat 发送按钮移入 form，30×30、黄色方块、纸飞机；移除整行发送按钮。
  - AdminApp 新增 platformIncomeCents，读取 adminLedger(COMMISSION,ALL,0,1).account.balanceCents；侧栏新增“平台收入”行，点击进入资金流水。
  - admin.css 增加 admin-wallet 深色主题与侧栏底部纵向排列；移动端行布局。
- **验证**：用户/Admin send 均在 form 内 30×30；Admin 显示“平台收入 ¥488.10”，wallet 位于 profile 上方；用户 wallet top 794，frame bottom 793，互不重叠。


## 2026-10-28 · Admin 聊天输入样式 + 用户栏与右上退出

- **目标**：Admin 聊天点击/输入/发送样式与广场一致；左侧用户栏改成广场 profile 结构但保留绿色；侧栏按钮改为去登录页；退出移到右上头像菜单且不提供注销。
- **改动**：
  - SquareChat 输入框显式 color=#333 + focus ring；发送按钮禁用态改浅黄色，避免深色侧栏下变暗。
  - AdminApp 左侧使用 .profile/.avatar/.profile-trigger/.icon-button，绿色主题；移除旧“退出”按钮，箭头改为 href="#/login"。
  - admin-top 增加绿色头像下拉，仅“退出登录”，无注销；账号切换逻辑不变。
- **验证**：input color rgb(51,51,51)；send bottom 816 与 foot top 824 留 8px 不重叠；左下/右上/下拉头像均为绿色 rgb(47,107,74)，左下 A 居中；admin-return href #/login；右上菜单仅 1 个退出登录按钮。


## 2026-10-28 · Admin 聊天框对齐任务广场间距

- **目标**：Admin 发送按钮紧贴用户切换栏上方，聊天框上下间距与任务广场一致；不改切换逻辑/显示。
- **改动**：仅 admin-chat-slot：flex:1 填满空白，margin-top/bottom 抵消 admin-side 的 22px gap；聊天框自身 14px 上间距，发送按钮 bottom 与切换栏 top 对齐。
- **验证**：nav bottom=265.97，chat top=279.97（14px）；send bottom=840，foot top=840；聊天框高度 560。


## 2026-10-28 · Admin 左侧栏固定（v1.0 基线之上）

- **目标**：参照任务广场，Admin 左侧栏固定，滚轮只滚动右侧主界面；不改主界面尺寸与登录页。
- **改动**：仅 admin.css：.admin-side 改为 fixed 236px + 100vh + overflow:hidden，.admin-main 保持 grid-column:2；移动端恢复 static/auto。
- **验证**：资金流水页滚动到 48 时，sideTop 仍为 0、mainTop=-48；侧栏无内部滚动；1280×900 左侧栏 0~236px。


## 2026-10-28 · 前端回滚到聊天 v1.0

- **目标**：撤销后续 Admin 布局/分页/滚动改动，恢复聊天 v1.0 前端，此后先不动左侧聊天栏。
- **改动**：AdminApp、admin.css、TopBar、styles.css 全部恢复到 `072c748`；保留聊天 v1.0 的 SquareChat 与后端。
- **验证**：Admin 聊天 256px、无分页、侧栏 static、待仲裁 420px；TopBar 非 sticky；主界面/登录布局恢复上一版本。


## 2026-10-28 · 广场聊天 v1.0

- **目标**：广场左侧栏支持公共聊天，Admin 与普通用户共用同一房间，消息落库并实时广播。
- **后端**：
  - `chat_message` 表（雪花 id、room_id、sender_name/role 快照、client_msg_id 幂等）。
  - `SendChatMessageUseCase` / `QueryChatHistoryUseCase`、JDBC 仓储、Redis 限流。
  - `GET/POST /api/chat/messages`；`WsHandler` 增加 `chat.send`；`ChatWebSocketBroadcaster` 广播 `chat.message`。
- **前端**：`SquareChat` 接历史、WS 实时消息、HTTP 兜底、发送失败提示；Admin 侧栏复用同一组件，Admin 绿色头像、普通用户黄色头像。
- **验证**：HTTP 发送 200，A/B 两个 WS 均收到；同一 clientMsgId 重发返回同一 id；Admin 消息 senderRole=Admin；历史接口返回消息。


## 2026-10-28 · 广场左侧栏聊天框 UI

- **目标**：只替换广场左侧栏下半部分，做出聊天框 + 输入框 + 发送按钮。
- **改动**：
  - 新增自包含的 `SquareChat`（内联样式，无全局 CSS）；TopBar 仅把 `.sidebar-guide` 替换为 `<SquareChat/>`。
  - 聊天区加可见边框；输入框留在框内，发送按钮移到框下方、可用余额正上方。
- **验证**：`npm run build` 通过；浏览器实测发送按钮 bottom 与 `.sidebar-wallet` top 均为 794.4px，紧挨无间隙；其他区域未变。


## 2026-10-28 · 回滚广场聊天 UI 壳

- **目标**：撤回聊天 UI 对 TopBar / Icon / 全局样式的改动，恢复其他页面原布局。
- **改动**：TopBar、Icon、styles.css 恢复到聊天改动前；删除 SquareChat；保留 TODO #7（JMeter 优惠券压测）。
- **验证**：`npm run build` 通过；浏览器 `.sidebar-chat=false`、`.sidebar-guide` 恢复。


## 2026-10-28 · Admin 离线状态点改红

- **目标**：在线状态列的“离线”圆点由灰色改为红色；强制下线按钮保持透明/白底。
- **改动**：admin-online-dot.is-offline 背景 #c9c3b8 → #c0392b，并加同色 ring；强制下线恢复 btn btn-ghost admin-danger。
- **验证**：前端构建通过；离线点红色，按钮样式恢复。


## 2026-10-28 · 广场锁定券显示已领取 + 有效期文案改为 1 天

- **目标**：locked 券在广场也显示“已领取”不可点击；领取提示改为 1 天。
- **改动**：Square claimedTypes 过滤 available || locked；Square/Wallet 文案 2 天→1 天。
- **验证**：持有 locked 券账号打开广场，对应按钮“已领取”且 disabled。


## 2026-10-28 · 修复 locked 券导致优惠券接口 500

- **根因**：发布任务锁券后 voucher_record.status='locked'，但 VoucherStatus 枚举缺少 LOCKED，
  RowMapper 的 valueOf("LOCKED") 抛 IllegalArgumentException，导致 /api/vouchers/mine 500。
- **影响**：广场 Promise.all(api.vouchers, api.myVouchers) 整体失败 → 广场优惠券区消失；
  钱包、发布页券列表同样依赖 mine → 不显示/不可用。结算后状态变 used，枚举可解析，所以恢复正常。
- **修复**：VoucherStatus 增加 LOCKED（dbValue=locked）。
- **验证**：存在 2 张 locked 券时 /api/vouchers/mine 返回 OK、6 条记录。


## 2026-10-28 · 优惠券统一 24h 过期，单跳延迟消息

- **目标**：去掉 48h 两跳，改为 24h 一跳；清空所有用户存量券。
- **改动**：
  - dash.voucher.expire-hours 默认 24；Claim 两个用例默认值 24。
  - VoucherExpireConsumer 删除续投下一跳逻辑，24h 直接 markExpired + release。
  - Delay adapter 保留 24h 上限作为安全边界。
  - 删除 voucher_record 全量、voucher-expire 本地消息、Redis claimed/pending/stock，重启初始化库存。
- **验证**：领取后 expire_time ≈24h，local_message 立即 SENT；重复领取 ALREADY_CLAIMED；DB voucher_record=0。


## 2026-10-28 · 真延迟队列：立即投递 + 24h 分跳

- **目标**：local_message 登记后立即投 MQ，由 broker 负责延迟，而不是 DB 到点调度。
- **改动**：
  - enqueue 的 next_retry_at 改为 NOW(3)。
  - RocketMQ 单条最大延迟 24h：DelayMessageAdapter 对更远时间截断为 24h。
  - VoucherExpireConsumer 收到未到期消息时登记下一跳；48h 券拆成 24h + 24h。
- **验证**：local_message 状态 PENDING→SENT，deliver_at 仍为 48h 后。


## 2026-10-28 · 过期兜底扫描也释放一人一单

- **目标**：MQ 延迟消息丢失时，扫描兜底过期也能让用户重新领取。
- **改动**：VoucherExpireScanJob 改为逐张 markExpired，成功则 release claimed；新增 findExpiredAvailable(limit)。
- **验证**：编译通过；过期兜底与 MQ 消费者行为一致。


## 2026-10-28 · 优惠券使用/过期释放一人一单标记

- **目标**：券使用或过期后可以再次领取同类型券。
- **改动**：
  - VoucherClaimPort 增加 release；Redis 删除 voucher:claimed:{type}:{userId}。
  - 结算/仲裁把券置 used 后释放；到期消费者把券置 expired 后释放。
  - VoucherUse 增加 userId，供释放使用。
- **验证**：券使用后 marker EXISTS 1→0，再次领取返回 OK；DB 同时存在 used 和新的 available。


## 2026-10-28 · 优惠券 Redis 预扣 + MQ 异步落库

- **目标**：高并发领取用 Redis Lua 原子预扣 + RocketMQ 异步落库，防超卖、一人一单、可补偿。
- **实现**：
  - VoucherClaimPort/RedisVoucherClaimAdapter：voucher:stock、pending:count、pending:id、claimed 四类 key，Lua 一次完成库存判断/扣减/pending/一人一单标记。
  - ClaimVoucherAsyncUseCase：Lua 预扣成功后投递 voucher-claim；发送失败立即 Lua 回滚，MQ 关闭时回退旧同步用例。
  - VoucherClaimConsumer：INSERT IGNORE + DB CAS 扣库存 + 到期 local_message；重复消息幂等；重试 5 次失败按落库结果 confirm/rollback。
  - VoucherStockInitializer 启动 SETNX 初始化；Admin 改库存 write-through；worker 每分钟 reconcile(DB - pending)。
- **验证**：领取成功 Redis 43/pending0、DB available、stock43；重复领取 VOUCHER_ALREADY_CLAIMED；MQ 发送失败库存回滚（INTERNAL_ERROR 后 stock 不变）。


## 2026-10-28 · 发单人仲裁胜诉 +2，胜负信用完全对称

- **目标**：判给发单人时发单人也 +2；与判给跑腿时跑腿 +2 对称。
- **改动**：ArbitrateErrandUseCase PUBLISHER 分支在退款提交后写 SETTLE +2（bizNo=settle-pub:{errandId}），刷新排行榜并 WS 推送。
- **验证**：判给发单人后发单人 score 48→50，credit_event 为 SETTLE +2；跑腿记 DISPUTE_LOSE -8。


## 2026-10-28 · 发单人仲裁败诉对称扣 8 分

- **目标**：判给跑腿时，发单人作为败诉方记 -8，净变化与跑腿败诉对称。
- **改动**：
  - ArbitrateErrandUseCase RUNNER 分支新增发单人 DISPUTE_LOSE（bizNo=disputelose:{errandId}:{publisherId}），推送 WS 并刷新排行榜。
  - ArbitrateSettleStep 不再给该分支的发单人记 +2，避免净效果变成 -6。
- **验证**：发单人 score 56 → 48，credit_event 仅一条 DISPUTE_LOSE -8；测试任务已清理。


## 2026-10-28 · Admin 仲裁 WS 推送与待处理角标

- **目标**：新争议实时显示在 Admin 侧栏/待仲裁数字；裁决后自动消失。
- **改动**：
  - RealtimeNotifier 增加 adminDisputeChanged（默认空实现），WsSessionRegistry 增加 broadcast。
  - 发起争议在事务提交后推送 admin.dispute；两种裁决完成后也推送。
  - AdminApp 复用 App 的 /ws，订阅 admin.dispute 后 reload；侧栏与「待仲裁」显示绿色数量，列表为空即消失；另加 5 秒轮询兜底。
- **验证**：存在 1 条 DISPUTED 时 Admin 页 nav=1、pending=1、row=1；裁决后 DISPUTED=0。


## 2026-10-28 · 任务计数与信用未读全局修正

- **目标**：抢单后立即有数量；我的任务/侧栏/进行中同步；信用中心未读推送稳定。
- **改动**：
  - 跑腿在途包含 LOCKED/ACCEPTED/PICKED_UP；新增 taskCounts 工具，同时拉取两种角色并广播 `dash:task-counts`。
  - TopBar「我的任务」显示总数并与 errand.status WS 联动；Mine 两个角色 tab 都显示数量，进行中/统计卡/高亮使用同一来源。
  - 新增 creditUnread 工具，TopBar 在 credit.changed 时主动拉取并更新未读，Credit 页复用同一已读/未读集合。
- **验证**：LOCKED 状态跑腿：侧栏我的任务=1、我接下的=1、进行中=1、highlight=1。


## 2026-10-28 · 我的任务在途/待确认计数与高亮

- **目标**：跑腿在途、发布人待确认的任务在“我的任务”显示数量，并通过 WS 实时刷新；对应卡片浅黄高亮。
- **改动**：
  - 跑腿：ACCEPTED/PICKED_UP 计为在途；发布人：DELIVERED 计为待确认。
  - “我发布的/我接下的”当前 tab 显示数量，子 tab“进行中”显示数量，统计卡“进行中”改用该数量。
  - 对应卡片加 `is-highlight` 浅黄背景，状态推进后自动消失；Mine 已有 errand.status WS 订阅驱动 reload。
- **验证**：跑腿 ACCEPTED 时 tab=1、进行中=1、highlight=1；送达后发布人 DELIVERED 时 tab=1、进行中=1、highlight=1；清理任务后广场=0。


## 2026-10-28 · 提示宽度随文字自适应；仲裁区底色与表格统一

- **目标**：短提示不再固定 560px 宽造成大片空白；待仲裁任务与仲裁记录表格有边框/浅底，风格同资金流水。
- **改动**：
  - `.toast/.banner/.floating-notice` 改为 `width: max-content` + `max-width: 560px`，长文本才换行。
  - `.admin-row` 默认浅色背景 + 边框；选中态保持绿色。
  - 仲裁记录表头加 `#faf7ef` 背景、单元格右边框、斑马纹与 hover 底色。
- **验证**：注入短提示实测宽度 toast=82px、banner=82px、floating=124px，不再出现空白。


## 2026-10-28 · 下拉浮层修复；信用未读高亮；仲裁记录与通知补全

- **目标**：优惠券下拉不被表单 03 区块裁切；信用分变化有未读光点与背景高亮；仲裁双方通知/积分推送/用户名/记录完善。
- **改动**：
  - SelectMenu 改为 portal + fixed：空间不足自动向上，避免父级 overflow 裁切。
  - 信用中心按事件 key 记录本机已读：新 credit.changed 事件显示未读计数、淡黄底高亮，悬停即已读；侧栏信用中心同步显示计数。
  - 仲裁支持发单人补 ARBITRATE 审计；退款事件也给跑腿落站内通知；支持跑腿时双方信用分推送并刷新榜单。
  - 管理台争议列表/详情返回用户名；新增仲裁记录表（复用 fund_audit_log，无新表）。
- **验证**：下拉菜单 fixed、打开在触发块上方且完整可见；模拟 1 条未读信用事件后行高亮、标题计数=1、侧栏信用中心计数=1；仲裁记录接口返回 1 条并正常渲染。


## 2026-10-28 · 通用下拉、仲裁通知/积分推送、仲裁记录

- **目标**：统一下拉样式；仲裁后双方都有站内通知与积分 WS；管理台显示用户名并新增仲裁记录。
- **改动**：
  - 新增 `SelectMenu`，替换发布页优惠券、广场排序、钱包交易类型三处原生 select，复用账号切换菜单样式。
  - 仲裁支持发单人时补 ARBITRATE 审计；跑腿/发单人信用分变化都推送 WS 并刷新排行榜；退款事件也给跑腿落站内通知。
  - 管理端争议列表/详情返回 publisherName/grabberName，前端优先显示用户名。
  - 新增 GET /api/admin/arbitrations，读取 fund_audit_log(action='ARBITRATE')，仲裁工作台底部用流水同款表格展示。
- **验证**：发布页优惠券下拉 2 个选项可展开；仲裁记录接口返回 1 条；管理台表格显示时间/任务/裁决/金额/操作人/结果。


## 2026-10-28 · 优惠券 tab 左对齐；Admin 头像字母居中

- **目标**：钱包「未使用/已使用」与标题文字左边对齐；管理台账号头像字母居中。
- **改动**：
  - `.wallet-voucher-tabs` 恢复左右 23px（窄屏 18px）内边距，使 tab 组与标题文字同一边线。
  - Admin 账号触发器/菜单的 `.menu-avatar` 改为 inline-flex + 水平垂直居中，line-height 1。
- **验证**：1280px 下 h2 left=294、tabs 组 left=294；头像 computed display=flex、align/justify=center、22×22。


## 2026-10-28 · 修改密码后撤销原会话并移除免密账号

- **目标**：改密后原账号立即登出，且本机账号簿不再保留，不能免密快速登录。
- **改动**：
  - ChangePasswordUseCase 成功后调用 authPort.revokeAllSessions + markOffline，删除该用户全部 access/refresh token。
  - 前端新增 forgetAccount(username)：改密成功后从 dash_accounts 移除该账号；若是当前会话则同步本地登出。
- **验证**：改密后索引 SCARD=0、旧 token 401、旧密码登录 BAD_CREDENTIALS、新密码登录 OK；恢复原密码后登录正常。


## 2026-10-28 · 全站提示统一为悬浮卡片

- **目标**：所有前端提示弹窗统一为顶部悬浮、不占布局；成功浅绿、失败浅红。
- **改动**：
  - `.banner` 全局改为 fixed 顶部卡片：ok 浅绿、warn 浅红、info 浅黄。
  - 全局 `.toast` 从底部深色改为顶部浅绿/浅红卡片，按 `toast.error` 切换。
  - 登录错误、Admin 顶层消息、优惠券/平台配置 toast 全部使用 `.floating-notice` 或同款样式。
- **验证**：计算样式 banner position=fixed/top=24、ok 背景 rgb(242,251,245)/边框 rgb(191,227,204)；floating is-error 背景 rgb(253,242,241)；页面高度不受影响。


## 2026-10-28 · 登录异常统一改为悬浮提示

- **目标**：登录页所有后端异常（用户名/密码错误、验证码错误、禁用等）都不占布局，统一浮在页面上方。
- **改动**：
  - 删除表单内 `banner warn` 错误条，`error` / `disabledNotice` 统一渲染为 `.floating-notice`；
  - 提示 fixed 顶部居中、无遮罩、可关闭；后续弹窗沿用该结构。
- **验证**：提交错误验证码后提示出现，login-card 高度前后均 418px、scrollY=0，提示 position=fixed/top=24。


## 2026-10-28 · 红绿按钮 hover 保持本色

- **目标**：鼠标悬停禁用/启用按钮时不再变白。
- **改动**：提高 `.admin-user-actions .btn.btn-danger:hover:not(:disabled)` / `.btn-success` 优先级，hover 只加深底色并保持白字，边框同色。
- **验证**：悬停禁用按钮计算样式为 background rgb(156,50,42)、color 白、border 同色。


## 2026-10-28 · 弹窗统一为悬浮式；禁用/启用按钮实心红绿

- **目标**：登录异常提示悬浮在页面上方、不占布局；禁用红色、启用绿色。
- **改动**：
  - 新增通用 `.floating-notice`：fixed 顶部居中、无遮罩、卡片阴影，登录禁用提示改用它；后续弹窗沿用该结构。
  - 用户表操作按钮改为实心：禁用 `btn-danger` 红底、启用 `btn-success` 绿底。
- **验证**：注入提示前后 login-card 高度均 418px、scrollY=0，提示 position=fixed/top=24；按钮计算背景色分别为 rgb(178,59,50) 与 rgb(47,107,74)。


## 2026-10-28 · 平台配置用户启用状态与按钮配色

- **目标**：用户表增加启用状态列，登录时间显示完整到秒，禁用/启用按钮颜色区分。
- **改动**：
  - 在线状态后新增「启用状态」列：绿点启用 / 红点禁用。
  - 最近登录改为 YYYY-MM-DD HH:mm:ss。
  - 禁用按钮红色（admin-danger）、启用按钮绿色（admin-success）。
- **验证**：禁用 stuh8kzun 后行显示红点「禁用」+ 绿色「启用」按钮；其余用户绿点「启用」+ 红色「禁用」；laizl 最近登录显示 2026-10-28 01:43:19。


## 2026-10-28 · 管理员禁用/启用账号

- **目标**：平台配置可对每个普通账号禁用/启用；禁用后不能登录并立即失效。
- **改动**：
  - user.status 作为启用位（1 启用 / 0 禁用）；新增 POST /api/admin/users/{userId}/status。
  - 禁用时撤销该用户全部 token、打强制下线标记、置 login_status=0；启用时清除标记。
  - 登录（密码与演示）查 status，禁用抛 ErrorCode.ACCOUNT_DISABLED（“该账户已被禁用”）。
  - 管理台用户表增加「禁用/启用」按钮；登录页对 ACCOUNT_DISABLED 弹窗提示。
  - 管理员账号与当前操作者自身不可被禁用（ACCOUNT_DISABLE_FORBIDDEN）。
- **验证**：禁用 stuhuceap → 旧 token 401、登录返回 ACCOUNT_DISABLED；管理台 enabled=false；启用后登录恢复 OK；禁用 Admin 返回 ACCOUNT_DISABLE_FORBIDDEN。


## 2026-10-28 · 强制下线撤销全部 token（方案二：用户 token 索引）

- **目标**：踢人时一次性撤销该用户全部 access/refresh，旧 token 重新登录后也不复活；不给每次请求增加校验。
- **改动**：
  - AuthPort 新增 revokeAllSessions（默认空实现）。
  - Session：登录写 auth:user_tokens:{userId} 集合，登出移除；踢人遍历集合删除 auth:token:* 并删索引。
  - JWT：access/refresh 都挂到 auth:jwt:user_tokens:{userId}；踢人时 access 的 jti 加黑名单、refresh 删除，最后删索引。
  - 强制下线接口先 revokeAllSessions，再打 auth:forced 标记、关 WS、置 login_status=0。
- **验证**：登录后 SCARD=1；踢人后索引 EXISTS=0、旧 token=401；重新登录后旧 token 仍 401、新 token=200，索引恢复 1 条。


## 2026-10-28 · 登录账号下拉不跳动；管理端在线状态查库

- **目标**：登录页展开本机账号时不顶动页面；Admin 在线状态完全以 user 表为准并跨浏览器及时刷新。
- **改动**：
  - 登录账号菜单改绝对定位浮层，展开不改变登录卡片高度；下方空间不足时自动向上展开（is-up）。
  - /api/admin/users 返回 login_status、last_seen_at，online = login_status=1 且心跳在 90 秒内。
  - 平台配置用户表每 10 秒静默轮询，跨浏览器登录/下线/心跳超时能及时反映。
  - 强制下线跨浏览器：Redis auth:forced:{userId} 由所有实例的 HTTP/WS 拦截器共享，关闭本实例 WS 并 markOffline。
- **验证**：展开前后 login-card 高度均 487px、scrollY=0；菜单 is-up 且 top=381 < toggle=637；Admin 接口 laizl online=true、loginStatus=true、lastSeenAt 有值。


## 2026-10-28 · 登录状态入库与心跳兜底

- **目标**：把在线状态从“单浏览器 WebSocket”升级为数据库全局状态，Admin 在另一浏览器也能看到；解决直接关页面的离线判定。
- **改动**：
  - user 表新增 login_status(0/1)、last_seen_at；登录成功置 1，主动登出/强制下线置 0。
  - 新增 POST /api/auth/heartbeat，前端每 30 秒心跳；worker 每分钟把 90 秒无心跳的账号置 0。
  - 管理端用户列表改为读 login_status + last_seen_at 判定在线（不再依赖本机 WS 注册表）。
- **验证**：登录后 DB login_status=1；Admin 列表 online=true；登出后=0 且 refresh 返回 UNAUTHORIZED；把 last_seen_at 改到 5 分钟前后等待 70 秒，sweep 日志出现并置 0。


## 2026-10-28 · 登录页本机账号滚动下拉；Admin 切换菜单绿色主题

- **目标**：移除登录页「返回任务广场」块；本机账号移到登录按钮下方并做成可滚动下拉；修正 Admin 菜单方向与配色。
- **改动**：
  - 删除 auth-session 恢复块（返回任务广场/管理台按钮）。
  - 本机账号改为登录按钮下方的折叠面板，展开后就地撑开，列表 max-height 208px（约 4 行）可滚轮滚动。
  - Admin 左下角菜单改为向上展开、深绿底/浅色文字、当前项高亮，最大高度 280px。
  - 广场菜单保持 fixed 浮层向上展开。
- **验证**：5 个账号时列表 clientHeight=208、scrollHeight=266（4 行可见可滚）；Admin 菜单 bg=rgb(38,49,43)，top=665、bottom=830、footer=840（在触发块上方）；广场菜单 fixed 且 5 项正常。


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
