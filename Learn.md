# 学习笔记：Redis Pub/Sub、Docker 与 Agent 的 LangGraph 结构

> 记录 Redis Pub/Sub、Docker 容器基础，以及 Agent 服务里 LangGraph 的图结构，
> 全部对照 CampusDash 实际源码（\`agent/app/agent/graph.py\` 等）。

---

# 一、Redis Pub/Sub

## 1. 模型

Redis Pub/Sub 是发布订阅模型：

```text
发布者 PUBLISH channel message
        ↓
Redis 把消息复制给所有订阅该 channel 的客户端
```

特点：

- 一个发布者可以对应多个订阅者；
- 发布者不知道订阅者是谁；
- 消息是广播，不是队列；
- 订阅者不在线时，消息直接丢失。

## 2. 常用命令

### 发布

```bash
PUBLISH ws:events '{"type":"hello"}'
```

返回值是收到消息的订阅者数量。没有订阅者时返回 `0`，消息不会保存。

### 订阅

```bash
SUBSCRIBE ws:events
```

进入订阅模式后，RESP2 连接只能接收订阅消息，不能继续执行普通 Redis 命令。

### 模式订阅

```bash
PSUBSCRIBE ws:*
```

匹配所有 `ws:` 开头的 channel。

### 取消订阅

```bash
UNSUBSCRIBE ws:events
PUNSUBSCRIBE ws:*
```

### 查看订阅情况

```bash
PUBSUB CHANNELS
PUBSUB NUMSUB ws:events
PUBSUB NUMPAT
```

## 3. 重要语义

### 不持久化

消息发出去就结束，不写磁盘、不补发、不保证订阅者收到。

### 没有 ACK

没有消费确认、消费位点和重试。

适合：

- 实时聊天广播；
- 在线状态通知；
- 缓存失效通知；
- 低价值实时提醒。

不适合：

- 支付、资金流水；
- 必须最终一致的业务消息；
- 不能丢的异步任务。

这些应该使用 MySQL 本地消息表、RocketMQ 或 Redis Streams。

### 监听器不能做重活

Redis 有客户端输出缓冲区限制，订阅者处理太慢可能被断开。

监听回调应该：

```text
收到消息 → 快速反序列化 → 投递给本地 WebSocket → 立即返回
```

不要在回调里查数据库、发 HTTP 或执行长任务。

## 4. Redis 7 Sharded Pub/Sub

Redis 7 增加了：

```text
SSUBSCRIBE
SPUBLISH
SUNSUBSCRIBE
```

普通 Pub/Sub 在 Redis Cluster 中通常会广播到整个集群；Sharded Pub/Sub 把 channel 绑定到某个 slot，只在对应分片传播。

单 Redis 实例不需要 Sharded Pub/Sub。

## 5. Spring Data Redis 写法

发布：

```java
stringRedisTemplate.convertAndSend("ws:events", json);
```

订阅：

```java
@Bean
RedisMessageListenerContainer wsListenerContainer(
        RedisConnectionFactory factory,
        WsSessionRegistry registry,
        ObjectMapper objectMapper) {

    RedisMessageListenerContainer container =
            new RedisMessageListenerContainer();
    container.setConnectionFactory(factory);

    container.addMessageListener((message, pattern) -> {
        String body = new String(message.getBody(), StandardCharsets.UTF_8);
        // 解析事件
        // 分发给本地 registry
    }, new ChannelTopic("ws:events"));

    return container;
}
```

注意：

- ListenerContainer 自己维护订阅连接；
- 回调运行在监听线程池；
- 回调不能阻塞；
- 异常要捕获，避免影响后续消息；
- 每个 app 实例都会收到同一条消息。

## 6. Pub/Sub、Stream、MQ 对比

| 能力 | Pub/Sub | Redis Streams | RocketMQ |
| --- | --- | --- | --- |
| 持久化 | 否 | 是 | 是 |
| ACK | 否 | 是 | 是 |
| 消费组 | 否 | 是 | 是 |
| 历史消息 | 否 | 是 | 是 |
| 适合 | 实时广播 | 可靠队列 | 业务消息 |
| 复杂度 | 低 | 中 | 高 |

聊天场景的可靠事实在 MySQL：

```text
聊天消息先落 DB
→ Pub/Sub 只做实时推送
→ 断线重连用 afterId 从 DB 补齐
```

所以 Pub/Sub 丢少量实时消息可以接受。

## 7. 多实例 WebSocket 广播

当前 `WsSessionRegistry` 只保存本 JVM 的 `WebSocketSession`。

多实例时：

```text
app-1 的广播只能到达 app-1 的连接
```

解决方式：

```text
所有 app 实例订阅同一个 Redis channel
任意实例 PUBLISH 一次
所有实例收到后，各自广播给本地连接
```

事件信封示例：

```json
{"kind":"BROADCAST","frame":"{\"type\":\"chat.message\",\"payload\":{...}}"}
{"kind":"USER","userId":"...","frame":"{...}"}
{"kind":"CLOSE_USER","userId":"..."}
```

原则：

```text
发布方只 publish
所有实例（包括发布方）通过订阅端本地广播
不要 publish + 本地广播同时做，否则会重复
```

---

# 二、Docker

## 1. 镜像和容器

```text
镜像 Image = 只读模板
容器 Container = 镜像运行起来的进程 + 可写层
```

例如：

```text
mysql:8.0.40     镜像
dash-mysql       容器
```

容器删除后，可写层数据默认也删除，因此数据库需要 Volume。

## 2. Dockerfile

后端镜像：

```dockerfile
FROM eclipse-temurin:21-jre
WORKDIR /app
COPY dash-bootstrap/target/dash-bootstrap-1.0.0-SNAPSHOT.jar /app/app.jar
COPY dash-worker/target/dash-worker-1.0.0-SNAPSHOT.jar /app/worker.jar
EXPOSE 8080
ENTRYPOINT ["java","-XX:MaxRAMPercentage=70","-jar","/app/app.jar"]
```

当前项目的 jar 是宿主机 Maven 构建后再 COPY 进去，不是多阶段构建。

- 优点：本地构建快、镜像简单；
- 缺点：构建依赖宿主机，CI 要先执行 Maven。

## 3. Docker Network

Compose 默认创建 bridge 网络，服务之间用服务名通信：

```text
app 容器内：
  mysql:3306
  redis:6379
  rmqbroker:8081
```

不是：

```text
localhost:3307
localhost:6380
```

`localhost` 在容器里指容器自己。

宿主机访问才使用端口映射：

```text
宿主机 3307 → mysql 容器 3306
宿主机 6380 → redis 容器 6379
宿主机 8088 → web 容器 80
```

## 4. Volume

MySQL 使用命名卷：

```yaml
volumes:
  - dash-mysql-data:/var/lib/mysql
```

容器删除后数据仍在。

Redis 当前没有挂 Volume，虽然有 AOF，但容器删除/重建时数据可能丢失。

这就是项目需要启动预热器的原因：

```text
VoucherStockInitializer
VoucherClaimInitializer
GrabSlotInitializer
```

它们从 MySQL 重建关键 Redis 状态。

核心原则：

```text
Redis 不是事实源，MySQL 才是
缓存丢了必须能从 DB 恢复
```

## 5. Compose 服务编排

项目分两个文件：

```text
docker-compose.yaml        MySQL / Redis / RocketMQ
docker-compose.full.yaml   app / worker / web
```

启动：

```bash
docker compose \
  -f docker-compose.yaml \
  -f docker-compose.full.yaml \
  up -d --build
```

服务关系：

```text
web(nginx:8088)
  ↓
app(Spring Boot:8080)
  ├─ mysql:3306
  ├─ redis:6379
  └─ rmqbroker:8081

worker
  ├─ mysql
  ├─ redis
  └─ rocketmq
```

`worker` 和 `app` 共用同一个镜像：

```yaml
image: dash-app:local
```

只是入口不同：

```yaml
worker:
  entrypoint: ["java","-jar","/app/worker.jar"]
  environment:
    DASH_WS_ENABLED: "false"
```

一个镜像，两种进程角色。

## 6. healthcheck 和 depends_on

```yaml
depends_on:
  mysql:
    condition: service_healthy
  redis:
    condition: service_healthy
  mq-init:
    condition: service_completed_successfully
```

含义：

```text
MySQL 健康后才启动 app
mq-init 成功后才启动 app/worker
```

没有 healthcheck 时，`depends_on` 只能保证容器启动，不能保证服务可用。

## 7. mq-init 一次性容器

RocketMQ broker 没有持久化 topic，容器重建后 topic/group 会丢。

项目中用 `mq-init`：

```text
等待 broker 注册
→ 创建 topic
→ 创建消费组
→ 校验
→ 退出
```

这是 Compose 中典型的初始化容器。

## 8. 常用命令

```bash
docker compose ps
docker compose logs -f app
docker compose exec app sh
docker compose restart app
docker compose down
docker compose up -d --build
docker stats
docker inspect dash-app
docker network ls
docker volume ls
```

## 9. 当前部署的局限

固定容器名：

```yaml
container_name: dash-app
```

会阻止：

```bash
docker compose up --scale app=3
```

要做多实例：

```text
去掉 container_name
或改成 app-1 / app-2 / app-3
nginx 增加 upstream
```

单机 Compose 不是生产编排；生产更常见 Kubernetes、Nomad、ECS。

## 10. 容器内端口与多实例

每个 app 容器内部都可以监听 `8080`，因为容器有独立网络命名空间，不会冲突。

外部只需要 nginx 暴露一个入口：

```text
浏览器 → nginx:8088
       ├─ app-1:8080
       ├─ app-2:8080
       └─ app-3:8080
```

不需要给每个 app 分配不同外部端口。

实例区分靠：

```text
INSTANCE_ID / HOSTNAME
```

用于日志、指标和排查，不用于决定用户连哪台。

## 11. Docker 与本项目 WebSocket 扩展

当前 `WsSessionRegistry` 是 JVM 内存，只能保存当前容器的连接。

多实例方案：

```text
浏览器
  ↓
nginx
  ├─ app-1
  ├─ app-2
  └─ app-3
       ↓
  Redis Pub/Sub ws:events
       ↓
所有 app 订阅并广播给本地连接
```

关键点：

- 每个实例只保存本地 Session；
- 所有实例订阅同一个 channel；
- 发布方只 publish 一次；
- 所有实例（包括发布方）通过订阅端本地广播；
- 在线人数、强制下线等跨实例操作也要走广播事件。

---

# 三、Agent 的 LangGraph 结构

> 对照源码：`agent/app/agent/graph.py`（图定义）、`agent/app/service.py`（调用入口）、
> `agent/app/models.py`（槽位与追问文案）、`agent/app/agent/intent.py`（意图）、
> `agent/app/agent/extractor.py`（抽取器）。

## 1. 先记住一句话

**图的每一次 `invoke` 只处理“一轮对话”，四条边走到 END 就结束；
“反复追问”不是图里的循环，而是外层对这张图的重复调用。**

这是理解整套设计的关键。下面逐层展开。

## 2. 整张图长什么样

```text
                        ┌──────────────────────────┐
                        │          START           │
                        └────────────┬─────────────┘
                                     │ add_edge
                                     ▼
                        ┌──────────────────────────┐
                        │        extract           │  ① 读这一句话，改草稿
                        │  （唯一的“大脑”节点）      │
                        └────────────┬─────────────┘
                                     │ add_edge
                                     ▼
                        ┌──────────────────────────┐
                        │         check            │  ② 重算“还缺什么”
                        └────────────┬─────────────┘
                                     │ add_conditional_edges
                          route_after_check(state)
                          ┌──────────┴───────────┐
                  missing 非空                 missing 为空
                          │                      │
                          ▼                      ▼
              ┌────────────────────┐   ┌────────────────────┐
              │     ask_user       │   │       ready        │
              │ status = ASKING    │   │ status = READY     │
              │ 生成追问文案         │   │ 清空 question      │
              └─────────┬──────────┘   └─────────┬──────────┘
                        │ add_edge               │ add_edge
                        ▼                        ▼
                     ┌─────────────────────────────┐
                     │            END              │
                     └─────────────────────────────┘
```

对应源码（`build_graph` 结尾，graph.py:242-253）：

```python
graph = StateGraph(AgentState)
graph.add_node("extract", extract_node)
graph.add_node("check", check_node)
graph.add_node("ask_user", ask_node)
graph.add_node("ready", ready_node)

graph.add_edge(START, "extract")
graph.add_edge("extract", "check")
graph.add_conditional_edges("check", route_after_check, {"ask": "ask_user", "ready": "ready"})
graph.add_edge("ask_user", END)
graph.add_edge("ready", END)
return graph.compile()
```

**结构性结论**：这是一张**有唯一分叉点的 DAG**，没有回边。
`check` 之后是整张图里**唯一的分支**，其余全是直线。

## 3. 状态：AgentState

图的状态是一个 `TypedDict`（graph.py:50-68），可以理解成“这一轮的数据包”：

| 字段 | 谁写 | 含义 |
| --- | --- | --- |
| `message` | 调用方（service） | 用户这一句话 |
| `draft` | extract | 任务草稿（`TaskDraft`），跨轮演进的唯一载体 |
| `missing` | check | 本轮还缺哪些必填槽位 |
| `question` | ask_user | 拼好的追问文案；READY 时为 None |
| `status` | ask / ready | `ASKING` 或 `READY` |
| `extracted` | extract | 本轮抽取到的原始 patch（排障用） |
| `appended` | extract | 本轮是否真的往任务内容里加了东西 |
| `intent` | extract | READY 阶段识别出的意图 |
| `cleared` / `show_draft` / `unclear` | extract | 三个给前端的信号位 |
| `updated_fields` | extract | 本轮被修改的字段名列表 |

注意 `AgentState` 是 `total=False`：**节点只返回自己负责的键**，
LangGraph 会把返回值**合并**进状态，没返回的键保持原值。
所以 `ready_node` 要显式把 `intent` / `appended` 带出来，否则会丢。

## 4. 四个节点分别做什么

### 4.1 `extract` —— 唯一的业务节点

这是整张图里唯一“有脑子”的节点，其余三个都是簿记。它内部按**草稿是否已完整**分成两条完全不同的路径。

**路径 A：必填已齐（READY 之后）**，graph.py:94-153

先判断“这句话想干什么”，再分派：

| 意图 | 行为 | 代码 |
| --- | --- | --- |
| `FINISH` | 不改草稿，回“保持现状” | :97 |
| `PUBLISH` | 只打标记，真正发单由前端按钮做 | :99 |
| `QUERY` / `CHITCHAT` / `CANCEL` / `UNCLEAR` | 一律不动草稿 | :102 |
| `SHOW_DRAFT` | 置 `show_draft=True`，让前端再插一张卡片 | :106 |
| `CLEAR_DESCRIPTION` | 只清空 `description` | :115 |
| `APPEND` / `EDIT` | **一次抽取同时拿“字段新值”和“剩余说明”** | :128-153 |

最后一条是重点：

```python
patch = _extract(extractor, message, current)
field_patch = {k: v for k, v in patch.items() if k in EDITABLE_FIELDS}
content = patch.get("description")
updated = merge_draft(current, field_patch, allow_locked=(intent == Intent.EDIT))
```

一句话里既能改字段、又能补内容，两者互不吞掉。
`EDIT` 允许覆盖标题，`APPEND` 不允许——防止“外卖有汤”这种顺口话把标题冲掉。

**路径 B：还有必填没齐（追问中）**，graph.py:157-210

三步：

1. `extractor.extract` 抽字段 → `merge_draft` 合并；
2. `_apply_pending_answer` 做“裸回答”兜底（用户只回“3 号楼 502”，模型可能什么都没抽到，就把它填进当前追问的槽位）；
3. 时间兜底 `_deadline_fallback`，首轮记 `title_base`，最后 `refresh_title`。

这里的顺序很讲究，代码里留了注释：**先用合并前的 `missing` 判断在问什么，再回填**，
否则“刚通过抽取填好类型”的这一轮会被误判成“正在问标题”。

### 4.2 `check` —— 重新计算缺口

```python
def check_node(state):
    draft = state["draft"]
    missing = required_slots(draft)
    return {"draft": draft, "missing": missing}
```

只有 3 行，但它**是整张图的路由依据**。
`required_slots`（models.py）是“追问到什么程度算完”的唯一判定入口：

- 类型未知 → 只问类型；
- `QUEUE` → 只要一个排队地点（存 `location_from`）；
- `DELIVERY` / `BUY` → 取货地 + 送达地；
- `OTHER` → 不要地点；
- 悬赏始终必填；
- **标题永不追问**（自动生成）、**截止时间和任务内容始终选填**。

### 4.3 `ask_user` —— 生成追问

```python
return {
    "status": "ASKING",
    "question": next_question(missing, draft=state["draft"]),
    "missing": missing,
}
```

`next_question` 负责排版：必填清单（一次最多 3 条）→ “还有 N 项” → 截止时间（倒数第二行）
→ 任务细节（最后一行）。传 `draft` 是为了“已经说过截止时间就不再问那一行”。

### 4.4 `ready` —— 收口

```python
return {
    "status": "READY",
    "question": None,
    "missing": [],
    "appended": state.get("appended"),
    "intent": state.get("intent"),
    ...
}
```

为什么要把 `appended` 原样搬一遍？注释写得很清楚：
字段抽取分支**没设置** `appended` 表示“首次完整”，
如果这里默认成 `False`，文案会错成“保持现状”。

## 5. 边：五种，各司其职

| 边 | 类型 | 含义 |
| --- | --- | --- |
| `START → extract` | 普通边 | 每轮都从抽取开始 |
| `extract → check` | 普通边 | 改完草稿必须重新算缺口 |
| `check →?` | **条件边** | 整张图唯一的分支 |
| `ask_user → END` | 普通边 | 追问完这轮就结束 |
| `ready → END` | 普通边 | 信息齐了这轮也结束 |

条件边的判定函数只有一行（graph.py:239-240）：

```python
def route_after_check(state: AgentState) -> str:
    return "ready" if not state["missing"] else "ask"
```

映射表 `{"ask": "ask_user", "ready": "ready"}` 把返回值翻译成目标节点名。

## 6. “反复追问”的循环到底在哪

这是最容易误解的地方。**图里没有循环**，一张图从 START 到 END 只走一次。
“反复追问”体现在**跨轮**：同一个会话里，`ChatService.chat` 被反复调用，每次重新 `invoke` 这张图。

```text
第 1 轮  用户“帮我取快递”   → invoke → extract→check→ask_user→END
         状态存回 session（draft 里有 type/reward，还缺地点）
第 2 轮  用户“菜鸟驿站”     → invoke → extract→check→ask_user→END
         状态存回 session（多了一个 location_from）
第 3 轮  用户“3号楼502”    → invoke → extract→check→ask_user→END
第 4 轮  用户“8块”         → invoke → extract→check→ready→END   ← 这时才 READY
```

**循环的载体不是图，而是 session**（service.py:105-141）：

```python
missing = required_slots(session.draft)          # 用上一轮的草稿算缺口
state = self.graph.invoke({
    "message": message,
    "draft": session.draft,                       # ← 上一轮的结果喂回去
    "missing": missing,
})
draft = state["draft"]
session.draft = draft                             # ← 本轮结果存回去
session.turns += 1
```

所以更准确的说法是：

- **图内是单轮、无环的 DAG**；
- **图外是“调用—存盘—再调用”的循环**，`session.draft` 是循环变量；
- 循环的**终止条件**是 `required_slots(draft)` 返回空列表，此时 `route_after_check` 走向 `ready`。

这种设计的好处：图是纯函数（同样的 state 进，同样的 state 出），
没有隐藏的循环状态，好测试、好重放；会话状态放在 MySQL/内存里，进程重启还能接上。

## 7. 一轮的完整数据流

```text
HTTP POST /agent/chat
      │  {session_id, message}
      ▼
ChatService.chat
      │  session = store.get(id)；missing = required_slots(session.draft)
      ▼
graph.invoke({message, draft, missing})
      │
      ├─ extract   ：抽字段 / 判意图 → 新的 draft
      ├─ check     ：required_slots(draft) → 新的 missing
      ├─ ask_user  ：next_question(missing) → question
      │   或 ready ：status=READY
      ▼
draft_to_reply(status, draft, missing, question, appended, intent, updated_fields)
      │  决定回话文案（追问 / 信息已完整 / 已更新：送达地点、悬赏 / …）
      ▼
{status, message, draft, missing, question, intent, can_publish, ...}
```

`draft_to_reply`（graph.py:493）是**纯展示层**：它不改草稿，只决定“这轮该说什么”。

## 8. 为什么 `extract` 要长成这样

它其实是“四个节点的活一个人干”，因为业务上这些判断耦合很紧：

- 要不要走意图分支，取决于 `required_slots(draft)` 是否为空；
- 意图分支和追问分支要共用同一套抽取与合并逻辑；
- 标题、时间、描述都有各自的兜底规则。

拆成多个图节点反而会因为“状态要不要落盘”反复横跳。
所以这里用**一个胖节点 + 三个瘦节点**：判断集中在 `extract`，路由集中在 `check`，
输出集中在 `ask` / `ready`。

## 9. 为 v2 留的扩展点

v1 只做“发单”，v2 要接查询工具、删除任务、RAG 检索。现有结构有个天然缺口：
**`check` 之后只有 ask / ready 两条路，没有“这句话跟发单无关”的出口。**

所以 v2 最自然的演进是在 `extract` 之前（或之内）加一个**意图路由**，把图变成这样：

```text
START → route_intent
          ├─ 发单  → extract → check → {ask_user | ready} → END      （现在的子图）
          ├─ 查询  → query_tools → answer → END
          ├─ 删除  → delete_tool → answer → END
          └─ 知识  → rag_retrieve → answer → END
```

落地方式有两种：

1. **子图（subgraph）**：把现在的四个节点包成一个 `publish_graph`，
   外层用 `add_node("publish", publish_graph)` 挂上去，`add_conditional_edges` 分派；
2. **工具节点**：查询/删除做成 LangChain Tool，用一个 `tool_node` 统一执行，意图决定选哪个工具。

无论哪种，**现有 `AgentState` 都要扩**：至少加 `route`（本轮走哪条路）
和 `tool_result`（工具返回），否则子图之间没法传结果。

还有两个已经存在、v2 会直接受益的东西：

- `Intent` 枚举（intent.py）已经是“这句话想干什么”的统一词汇表，路由可以直接复用；
- `_classify` 有多层防御（模型 → 解析容错 → 规则兜底），v2 的意图路由可以照抄这个模式。

## 10. 一句话总结

- **节点**：`extract` 干活、`check` 算缺口、`ask_user` 追问、`ready` 收口；
- **边**：四条直线 + 一个条件分叉（`check` 之后）；
- **循环**：图内没有；靠 `service.chat` 反复 `invoke` + `session.draft` 存盘实现多轮；
- **终止**：`required_slots` 为空 → `route_after_check` 走 `ready`。

