# CampusDash Agent TODO

> 目标：在 D:\Code\Campus_PaoTui\agent 下开发独立的 Python Agent 服务，作为 CampusDash 的第二个后端项目。
>
> 第一版做“对话式任务发布 Copilot”：自然语言 -> 任务草稿 -> 规则校验 -> 用户确认 -> 调用 Java 主项目发布。LLM 不直接操作数据库、钱包和资金。

---

## 0. 结论先行

### 推荐技术栈

| 层 | 选型 | 说明 |
| --- | --- | --- |
| 语言 | Python 3.12 | LLM / Agent 生态成熟 |
| Web | FastAPI + Uvicorn | 异步、Pydantic、SSE 友好 |
| 参数校验 | Pydantic v2 | 工具参数和结构化输出强校验 |
| Agent 编排 | LangGraph | 状态机、工具调用、checkpoint、human-in-the-loop |
| LLM 接入 | OpenAI 兼容 SDK | DeepSeek / Qwen / GPT 可切换 |
| 数据库 | MySQL 8，独立 schema campus_agent | 复用现有 MySQL，不写主业务表 |
| ORM/迁移 | SQLAlchemy 2.0 + Alembic | 迁移可追踪 |
| 会话状态 | Redis，独立 DB index | 不污染主项目 Redis |
| RAG | Qdrant（第二阶段） | 只用于规则/FAQ，不用于实时业务数据 |
| 可观测性 | structlog + Prometheus + OpenTelemetry | 日志、延迟、token、工具调用 |
| 测试 | pytest + respx + Testcontainers | 单测、API mock、集成测试 |
| 前端 | 复用现有 React，增加 Agent 面板 | 不单独造前端 |
| 部署 | Docker Compose 增加 agent 服务 | 同网络，nginx 增加 /agent 反代 |

### 关键决策

1. Agent 独立服务，不嵌入 Spring Boot。
2. Python 只调用 Java HTTP API，不直接连主项目 MySQL。
3. LLM 只生成草稿和建议；发布、扣款、领券、退款必须用户确认或人工操作。
4. 所有写操作必须有 action_id、幂等键和审计。
5. 第一版不做多 Agent、不做微调、不做自动资金操作。
6. RAG 延后，先把解析 -> 校验 -> 确认 -> 发布闭环做扎实。

---

## 1. 架构

    浏览器 / React
          |
          | POST /agent/chat
          v
    nginx :8088
          |
          v
    campus-agent :8000
      FastAPI + LangGraph
        |          |
        |          +--> LLM（OpenAI 兼容）
        |
        +--> 工具调用
               |
               v
        Spring Boot :8080
          /api/auth/me
          /api/errands
          /api/vouchers/mine
               |
               v
        MySQL / Redis / RocketMQ

Agent 数据：

    campus_agent schema
      agent_conversation
      agent_message
      agent_action
      agent_tool_call
      agent_run

Agent 短期状态：

    Redis
      agent:session:{conversationId}
      agent:checkpoint:{conversationId}
      agent:rate:{userId}

---

## 2. 第一版范围

### 必做

- 自然语言描述任务。
- 抽取：类型、标题、悬赏、取货点、送达点、截止时间、优惠券。
- 缺失字段追问。
- 生成结构化 TaskDraft。
- 调用 Java 规则接口校验。
- 前端展示确认卡片。
- 用户确认后调用 Java 发布。
- 发布失败给明确错误，不重复发布。
- 会话、工具调用、发布动作可审计。

### 不做

- Agent 自动发布。
- Agent 自动领券。
- Agent 操作钱包、托管、退款。
- Agent 执行 SQL 或任意 URL。
- 多 Agent 协作。
- 模型微调。

---

## 3. 前端交互

### 3.1 全局 AI 悬浮球

在现有 React 应用的用户页面增加一个全局 AI 入口：

- 圆形悬浮球，黄色背景；
- 内部使用 AI 机器人图标；
- 固定在页面右下角，可拖动；
- 拖动范围限制在视口内，不能被拖出屏幕；
- 位置保存到 localStorage，刷新后恢复；
- 桌面端直径约 56 到 64px，移动端不小于 48px；
- z-index 高于页面内容，但不能遮挡顶部导航和关键操作按钮；
- 点击后展开对话框，再次点击或按 ESC 收起；
- 登录页、Admin 后台默认不显示，具体范围可配置。

建议 localStorage key：

    agent.fab.position
    agent.lastConversationId

### 3.2 AI 对话框

展开后的对话框：

- 桌面端固定在右下角，宽约 380 到 420px，高约 600px；
- 移动端改为全屏或底部抽屉；
- 顶部显示标题、当前会话、清空/新建按钮；
- 中间是消息列表，支持多轮对话；
- 底部输入框、发送按钮、停止生成按钮；
- 流式输出时显示“正在输入”；
- 支持 Markdown 渲染，但必须做安全过滤；
- 草稿消息渲染成可确认卡片，不直接把 JSON 丢给用户；
- 发布失败显示可读错误和重试入口；
- 键盘 focus trap、ESC 关闭、aria-label 完整；
- 不遮挡页面主要操作，滚动时保持悬浮。

流程：

    输入自然语言
      -> Agent 流式返回
      -> 缺字段则追问
      -> 展示任务草稿卡片
      -> 用户点击确认发布
      -> Agent 调 Java 发布接口
      -> 返回 errandId 或可读错误

前端只传用户自己的 accessToken：

    Authorization: Bearer <user accessToken>

不要把 token 放进 prompt。

### 3.3 多轮历史

对话框必须支持历史对话，而不是关掉就丢：

- 打开面板时加载最近一次会话；
- 支持“新建对话”；
- 支持查看历史会话列表；
- 消息按时间正序展示，向上滚动加载更早消息；
- 每条消息有 role、content、created_at、status；
- 用户消息先落库，再调用 LLM；
- 助手流式输出时先落一条 STREAMING 消息，完成后更新为 COMPLETED；
- 流中断时保留 FAILED 状态，允许重试；
- 刷新页面后能恢复当前会话；
- 用户只能读取自己的会话，不能通过修改 conversationId 读别人。

### 3.4 历史保存策略

会话和消息必须保存到 Agent 自己的数据库：

    campus_agent.agent_conversation
    campus_agent.agent_message

不要只存在 localStorage。

localStorage 只保存：

    conversationId
    悬浮球位置
    当前面板开关状态

数据库保存：

    会话标题
    全部消息
    消息角色
    工具调用摘要
    草稿引用
    消息状态
    创建/更新时间

不要把完整 accessToken、密码、手机号明文写入消息表。

---

## 4. API 草案

### 创建会话

    POST /agent/sessions

请求：

    {"title":"取快递任务"}

### 对话

    POST /agent/chat

请求：

    {"conversationId":"uuid","message":"明天下午3点帮我取快递..."}

响应使用 SSE：

    event: message
    data: {"type":"assistant","content":"..."}

    event: draft
    data: {"type":"draft","draftId":"...","payload":{}}

### 确认发布

    POST /agent/actions/{actionId}/confirm

请求：

    {"idempotencyKey":"uuid"}

### 查询动作

    GET /agent/actions/{actionId}

### 查询会话列表

    GET /agent/conversations?cursor=...&limit=20

### 查询历史消息

    GET /agent/conversations/{conversationId}/messages?beforeId=...&limit=50

### 新建会话

    POST /agent/conversations

### 删除/归档会话

    DELETE /agent/conversations/{conversationId}

服务端必须从 accessToken 推导 user_id，不接受前端传 userId 读取别人的会话。

---

## 5. LangGraph 状态机

    START
      -> parse_intent
      -> fill_slots
      -> validate_draft
      -> wait_for_confirmation
      -> publish_task
      -> DONE

异常分支：

    LLM 不可用 -> fallback_to_form
    槽位缺失 -> ask_user
    校验失败 -> explain_error
    发布失败 -> mark_action_failed
    用户取消 -> CANCELLED

每个节点都能单独测试。

---

## 6. 工具设计

只读工具：

- get_current_user
- get_my_vouchers
- list_my_errands
- get_platform_rules
- estimate_reward（如果 Java 有对应接口）

写工具：

- create_task_draft
- publish_task

publish_task 前置条件：

    用户已明确确认
    action 状态是 CONFIRMED
    idempotencyKey 未使用
    accessToken 有效
    Java 校验通过

工具统一返回：

    {"ok":true,"data":{},"errorCode":null,"message":null}

不要把 Java 500 堆栈直接交给模型。

---

## 7. 数据模型

agent_conversation：

    id, user_id, title, status, last_message_at, message_count,
    created_at, updated_at, deleted_at

索引：

    (user_id, last_message_at)
    (user_id, status)

agent_message：

    id, conversation_id, user_id, role, content, content_type,
    tool_calls_json, metadata_json, status, client_message_id,
    token_count, created_at, updated_at

索引：

    (conversation_id, id)
    (conversation_id, created_at)

status 取值：

    STREAMING / COMPLETED / FAILED

约束：

    client_message_id 用于防重复提交
    content 不做物理覆盖，流式完成后只更新状态和最终内容
    只允许会话所有者读取/删除

agent_action：

    id, conversation_id, user_id, action_type, payload_json,
    status, idempotency_key, result_json, created_at, updated_at

agent_tool_call：

    id, run_id, tool_name, arguments_json, result_json, latency_ms,
    success, created_at

agent_run：

    id, conversation_id, user_id, model, prompt_tokens,
    completion_tokens, latency_ms, status, error, created_at

---

## 8. 安全与风控

- 用户 token 透传，Agent 不签发主项目 token。
- Agent 不存密码，不把完整 token 写进数据库。
- Prompt 中不出现手机号、完整地址、token。
- 工具白名单，不允许任意 HTTP 请求。
- 不允许 Agent 执行 SQL。
- 发布前必须用户确认。
- 写操作使用 idempotency_key。
- 每用户限流，限制对话次数和 token。
- 记录工具调用输入、输出、耗时、结果。
- Prompt Injection 测试：直接发布、取消别人任务、调用任意 URL、索要密码。
- 高危动作直接拒绝，不依赖模型自觉。

---

## 9. 可观测性

记录：

    agent_run.latency_ms
    prompt_tokens / completion_tokens
    tool_call.latency_ms / success
    LLM timeout
    fallback 次数
    发布成功/失败数
    每用户对话频率

指标：

    agent_request_total
    agent_llm_latency_seconds
    agent_tool_latency_seconds
    agent_tool_error_total
    agent_publish_success_total
    agent_publish_failure_total

日志使用 JSON，带 trace_id、user_id、conversation_id、action_id、instance_id。

---

## 10. 评测

数据集：100 条真实说法，覆盖：

- 完整信息
- 缺地点 / 缺时间
- 金额模糊 / 非法
- 非任务类问题
- 多任务混合
- Prompt Injection
- 过期或下架优惠券
- 用户取消

指标：

    JSON 合法率
    槽位准确率 / F1
    必填字段完整率
    工具调用正确率
    发布前确认率
    重复发布数
    P95 延迟
    平均 token 成本
    降级成功率

第一版门槛：

    JSON 合法率 >= 98%
    槽位准确率 >= 90%
    发布前确认率 = 100%
    重复发布数 = 0
    LLM 超时降级可用

---

## 11. 里程碑

### M0 项目骨架

- [ ] 创建 pyproject.toml、虚拟环境
- [ ] FastAPI 启动、GET /health
- [ ] 环境变量配置和 .env.example
- [ ] SQLAlchemy + Alembic
- [ ] campus_agent schema
- [ ] Redis 连接与健康检查
- [ ] pytest 基础测试
- [ ] 统一响应和异常处理

验收：

- [ ] GET /health 返回 200
- [ ] pytest 全绿
- [ ] 不启动 Java 也能跑单元测试

### M1 LLM 与任务草稿

- [ ] 接入 OpenAI 兼容 LLM
- [ ] 定义 TaskDraft Pydantic schema
- [ ] parse_intent
- [ ] fill_slots 追问
- [ ] 草稿 JSON 校验
- [ ] LLM 超时/非法 JSON 降级
- [ ] 20 条样本初步评测

验收：

- [ ] 完整描述生成合法 TaskDraft
- [ ] 缺字段会追问，不瞎填
- [ ] 非法 JSON 不写 action
- [ ] LLM 超时返回可读提示

### M2 Java 集成与发布闭环

- [ ] Java API 客户端：超时、重试、错误码
- [ ] get_current_user
- [ ] get_my_vouchers
- [ ] validate_draft
- [ ] create_task_draft
- [ ] confirm + publish_task
- [ ] action 幂等键
- [ ] 发布失败状态记录
- [ ] 联调真实测试任务并清理

验收：

- [ ] 用户确认前绝不调用发布接口
- [ ] 重复 confirm 不会发两次
- [ ] 发布成功返回 errandId
- [ ] 失败可读、可重试、可审计

### M3 Docker 与前端

- [ ] agent Dockerfile
- [ ] docker-compose.full.yaml 增加 agent
- [ ] nginx /agent 反向代理
- [ ] SSE 流式输出
- [ ] 黄色 AI 机器人圆形悬浮球
- [ ] 悬浮球可拖动并持久化位置
- [ ] 桌面端对话框 / 移动端全屏抽屉
- [ ] 新建会话、加载最近会话
- [ ] 多轮消息列表和历史分页
- [ ] 用户消息/助手消息落库
- [ ] 流式消息 STREAMING -> COMPLETED/FAILED
- [ ] 草稿确认卡片
- [ ] 错误提示和降级表单
- [ ] 会话仅本人可读

验收：

- [ ] docker compose 一条命令启动
- [ ] 浏览器通过 8088/agent 访问
- [ ] 悬浮球黄色、圆形、可拖动，刷新后位置保留
- [ ] 打开面板能加载上一次多轮对话
- [ ] 刷新/重新打开后历史消息仍在
- [ ] 用户 A 不能读取用户 B 的 conversation
- [ ] 流式中断后消息状态为 FAILED，可重试
- [ ] LLM 不可用时可回到普通表单

### M4 安全、评测、文档

- [ ] 50 到 100 条评测集
- [ ] Prompt Injection 测试
- [ ] 工具调用审计查询
- [ ] token 用量和延迟指标
- [ ] README、架构图、演示脚本
- [ ] 面试讲解稿

验收：

- [ ] 评测报告有数字
- [ ] 能演示对话 -> 草稿 -> 确认 -> 发布
- [ ] 能说明为什么 Agent 不能直接操作资金

### M5 可选增强

- [ ] Qdrant RAG：规则/FAQ
- [ ] 地图 POI 解析
- [ ] 任务推荐
- [ ] 多轮修改草稿
- [ ] 运营后台查看 Agent 失败案例

---

## 12. Docker 接入

agent 服务使用：

    build: ../../agent
    environment:
      JAVA_API_BASE_URL: http://app:8080
      MYSQL_URL: mysql+pymysql://root:dash123456@mysql:3306/campus_agent
      REDIS_URL: redis://redis:6379/1
      LLM_BASE_URL: <供应商地址>
      LLM_API_KEY: <密钥>
      LLM_MODEL: <模型名>
    expose:
      - "8000"

nginx：

    location /agent/ {
        proxy_pass http://agent:8000/;
        proxy_http_version 1.1;
        proxy_set_header Host $http_host;
        proxy_buffering off;
        proxy_read_timeout 300s;
    }

原则：

- Agent 使用独立 schema campus_agent。
- Redis 使用 DB 1。
- 不连接主项目业务表。
- LLM key 不提交 Git。

---

## 13. 风险与待决策

风险：

- LLM 工具调用不稳定：JSON Schema + 校验 + 重试 + 表单降级。
- 延迟高：流式输出 + 超时 + 小模型。
- 成本：用户限流、缓存规则、限制 token。
- Prompt Injection：工具白名单 + 人工确认 + 审计。
- Java API 契约变化：统一客户端 + contract test。
- 重复发布：action_id + idempotency_key + Java 唯一约束。
- 隐私：不把完整手机号/地址发给模型。

待决策：

- [ ] LLM 供应商：DeepSeek / Qwen / OpenAI 兼容网关
- [ ] 第一版是否做 Qdrant RAG
- [ ] 前端面板放任务广场还是独立页面
- [ ] Agent 是否复用主 MySQL 实例（推荐复用，独立 schema）
- [ ] Agent 是否加入现有 compose 还是独立 compose

---

## 14. 面试交付物

- [ ] 架构图：React -> nginx -> agent -> Java -> MySQL/Redis
- [ ] 一次完整对话演示
- [ ] 评测报告：合法率、槽位准确率、P95、成本
- [ ] 安全说明：为什么不能自动发布/自动扣款
- [ ] 幂等与审计设计
- [ ] 失败降级演示

---

## 15. 第一周执行顺序

1. 建项目骨架和 /health。
2. 定 TaskDraft schema。
3. 接一个 LLM，跑通单轮解析。
4. 加缺字段追问。
5. 写 10 条 pytest。
6. 再接 Java API。

不要第一周就做 RAG、多 Agent、复杂前端。

---

## 16. 完成定义

- [ ] 用户能通过对话生成任务草稿
- [ ] 缺字段会追问
- [ ] 用户确认前绝不发布
- [ ] 确认后只发布一次
- [ ] 发布失败有可读错误和审计
- [ ] LLM 不可用能降级到普通表单
- [ ] 有 50 条以上评测
- [ ] Docker 一条命令启动
- [ ] README 能让他人复现
