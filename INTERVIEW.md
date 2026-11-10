# CampusDash 面试准备手册

> 用法：面试前一天只看「速记卡」；面试当天按「项目讲述模板」开头；被追问时翻对应章节。
> 所有数字都来自本仓库真实实现与实测（评测脚本可复现）。

- [一、RAG](#一rag)
- [二、Agent 工程](#二agent-工程)
- [三、Java 后端](#三java-后端)
- [四、数据库与中间件原理](#四数据库与中间件原理)
- [五、系统设计题](#五系统设计题)
- [六、项目讲述模板](#六项目讲述模板)
- [七、抢单与领券：并发与一致性](#七抢单与领券并发与一致性重点)
- [八、事务消息与延迟消息](#八事务消息与延迟消息重点)
- [九、登录与账号管理](#九登录与账号管理)
- [十、WebSocket 与聊天广场](#十websocket-与聊天广场)
- [十一、速记卡](#十一速记卡)

---

## 一、RAG

### 1.1 一句话版本

> 校园跑腿助手的知识库：13 篇 Markdown（宿舍/食堂/商铺/场馆）按二级标题切成 125 块，
> BGE-small-zh 做 512 维向量，与 MySQL 的 ngram 全文索引做混合检索，融合排序后按置信度分档：
> 高置信直接答、中置信带提醒、低置信明确拒答。检索本身是 agent 的第 13 个 MCP 工具，由模型决定何时调用。

### 1.2 技术栈

| 层 | 选型 | 为什么 |
| --- | --- | --- |
| 存储 | MySQL 8 `FULLTEXT ... WITH PARSER ngram` + `BLOB` 向量列 | 已有中间件，零新增服务 |
| 向量模型 | bge-small-zh-v1.5（ONNX Runtime，90MB，512 维） | CPU 单条 2.3ms；DeepSeek 无 embedding 接口（实测 404） |
| 检索 | BM25 + 余弦，0.55/0.45 加权 | 专名靠 BM25，同义改写靠向量 |
| 接入 | MCP 工具 `search_knowledge` | 复用工具循环，不改图结构 |

### 1.3 离线建索引

```text
knowledge/*.md
  → loader    解析 YAML front-matter（id/title/category/campus/tags）+ 正文 hash
  → chunker   按 ## 切块，≤500 字，超长按句切并重叠 50 字，块首补「文档标题 / 小节：」
  → embedding ONNX：tokenize → 12 层 Transformer → [CLS] 取 512 维 → L2 归一化
  → store     写 kb_document（content_hash）+ kb_chunk（text + BLOB 向量 + 全文索引）
  → indexer   比对 hash 增量重建，先删后插保证幂等
```

实测：13 篇 → 125 块，平均 108 字，建库不到 1 秒。

### 1.4 在线检索

```text
问题 → 路由（词表命中「几点/驿站/在哪」→ QUERY）
     → 工具循环（只挂 7 个相关工具，模型选 search_knowledge）
     → MCP（stdio 子进程）
     → 关键词召回：MATCH ... AGAINST（ngram + BM25）top15
     → 向量召回：问题编码 512 维，与库内向量点积 top15，<0.35 丢弃
     → 融合：0.55×BM25归一 + 0.45×余弦 + 0.30 节标题 + 0.15 校区 − 0.20 跨校区
     → 置信度：0.6×余弦 + 0.4×(BM25/4)，≥0.62 高 / ≥0.57 中 / 否则拒答
     → 拼 prompt：[1] 标题/小节 + 正文，要求带出处
```

### 1.5 为什么必须混合检索

| 问题 | 知识库原文 | BM25 | 向量 |
| --- | --- | --- | --- |
| 301号楼有几层 | 东校区 301 号楼：5 层 | ✅ 专名精确 | ✅ |
| 菜鸟驿站几点关门 | 营业时间 08:00-20:00 | ❌ 零字重合 | ✅ 余弦 0.63 |

实测：「菜鸟驿站几点关门」vs「…营业时间 08:00 到 20:00」= 0.746；vs「一食堂牛肉面多少钱」= 0.347。

### 1.6 四个工程细节（加分项）

1. **块前缀**：块首补「校内食堂与餐厅 / 西区学生食堂：」，块离开原文也能自解释
2. **别名归一化**：知识库写 `- 别名：三食、三餐`，用户说「三食」→ 检索确认 → 草稿地点直接写「西区学生食堂」
3. **多路召回**：首轮不自信才展开（去口语/去疑问句/去校区前缀），且**只补候选、不改原句排序**
4. **置信度用绝对分**：归一化融合分不能判断可信度（第一名永远满分），必须用余弦 + 原始 BM25

### 1.7 评测

```bash
docker exec dash-agent python scripts/eval_kb.py
# Top1 = 48/50 = 96%   Hit@3 = 50/50 = 100%   拒答 = 5/5 = 100%
```

### 1.8 已知短板

「西区学专超市几点关门」Top1 仍是宿舍块：InnoDB 长查询相关度衰减——
同一块用「超市」查 1.47 分、用整句查 0.01 分。修法：本地自建 BM25 或换 ES/Qdrant。

### 1.9 模拟问答

**Q1：为什么不用 ES？**
> 125 个块，暴力余弦 5ms，ES 要 1.2GB 内存换一个用不上的分布式能力。留了接口，上万个块再换 Qdrant。

**Q2：为什么向量存 BLOB？**
> 512×float32=2048 字节；JSON 要 6~8KB（每个浮点带逗号和空格）。BLOB 读出来 `np.frombuffer` 直接算矩阵乘法。

**Q3：chunk 怎么切？为什么不用固定字数？**
> 按 Markdown 二级标题切，标题本身就是语义边界。按 500 字硬切会把店名和营业时间切到两个块里，检索到也没用。超长再按句子切、留 50 字重叠防切口。

**Q4：怎么防幻觉？**
> 三层：① 检索分数低于阈值直接拒答，不靠提示词「请不要编」；② 答案带引用序号可溯源；③ 资料里的指令不执行（防提示注入）。

**Q5：怎么衡量效果？**
> 50 条正例 + 5 条负例，量 Top1 / Hit@3 / 拒答率，现在 96%/100%/100%。改切块、换模型都跑一遍看趋势。

**Q6：遇到过什么坑？**
> ① 融合分不能当置信度（归一化后第一名永远满分，「今天天气怎么样」也能得 0.55）；② RRF 混合多路召回会把噪声顶上去，改成「只补召回不改排序」；③ MySQL 长查询相关度衰减，暂不可修，已记录替代方案。

**Q7：RAG 和微调怎么选？**
> 知识会变、需要出处 → RAG；风格格式这类能力 → 微调。营业时间天天变，微调不现实。

**Q8：检索结果还能怎么用？**
> 不只拼 prompt，还做回填：用户说「三食」，确认是别名后直接把草稿地点写成「西区学生食堂」。RAG 参与业务写操作，这是问答机器人和 agent 的分界线。

### 1.10 原理深挖：MySQL ngram 召回

**① 倒排索引**

普通索引是「行 → 内容」，倒排索引反过来：「词 → 出现在哪些行」。

```text
分词结果 →  posting list
菜鸟   → [块3, 块7, 块12]
驿站   → [块3, 块12, 块20]
营业   → [块1, 块3, 块9]
```

查询「菜鸟驿站」就是取交集 [块3, 块12]，再按相关度排序。

**② ngram 分词**

MySQL 默认分词器按空格和标点切，中文整句会被当成一个词——「西区菜鸟驿站」变成一个 token，搜「菜鸟」永远搜不到。

`WITH PARSER ngram` 改成按字符滑窗切：

```text
ngram_token_size = 2（默认）
西区菜鸟驿站 → 西区 / 区菜 / 菜鸟 / 鸟驿 / 驿站
```

查询侧用同一套切分，两边 token 对得上才能命中。代价是索引膨胀（每个字参与 2 个 token），中文场景一般可接受。

**③ 相关度打分（TF-IDF 家族）**

InnoDB 的 `MATCH ... AGAINST` 返回一个浮点分，思路是：

```text
score ≈ Σ  TF(词在文档中出现次数) × IDF(词在全库的稀有度)
```
- 词在本块出现越多越相关（TF）
- 词在全库越稀有越值钱（IDF）——「驿站」比「的」值钱
- 再做文档长度归一化（长文档不能白占便宜）

所以 `MATCH(text) AGAINST('超市')` 能给出 1.47，而 `AGAINST('西区学专超市')` 只有 0.01——
长查询里混进了大量常见 token，IDF 被摊薄，这是 InnoDB 的一个已知特性，也是本项目那个已知短板的根因。

**④ 两个坑**

- **50% 阈值**：某个词在超过一半的行里出现时会被当作停用词忽略；
- **BOOLEAN MODE + ngram**：不加引号时行为与 NATURAL 模式不同，本项目实测直接查不到结果，所以统一用 NATURAL LANGUAGE MODE。

### 1.11 原理深挖：向量召回

**① 从稀疏到稠密**

BM25 是稀疏表示：词表几万个，一句话只激活几个词，其余全是 0。
向量是稠密表示：512 个浮点数，每一位都不是 0，每一维都没有人类可读含义，但整体编码了语义。

**② 编码器怎么把句子变成 512 个数**

```text
文本 → Tokenizer（WordPiece）→ [CLS] 西 区 菜 鸟 … [SEP]
     → 词嵌入 + 位置嵌入（512 维）
     → 12 层 Transformer，每层做一次自注意力：
          Attention(Q,K,V) = softmax(QKᵀ/√d) V
     → 取 [CLS] 位置的输出（512 维）
     → L2 归一化
```

自注意力的本质：**每个 token 都去看一眼其他 token，按相关性加权融合**。
所以「几点关门」里的「关门」会注意到「几点」，整句的表示就带上了「询问营业时间」的语义。

**③ 为什么 [CLS] 能代表整句**

因为预训练目标（对比学习：同义句向量靠近、无关句向量远离）就是把 [CLS] 训练成整句摘要。
这也是为什么换模型必须整库重建——不同模型的向量空间不通用。

**④ 余弦相似度**

```text
cos(a,b) = (a·b) / (|a|·|b|)
```
两个向量都 L2 归一化后 |a|=|b|=1，于是 **cos = a·b**，一次点积搞定。

**⑤ 为什么它能把「几点关门」和「营业时间」连起来**

因为它们在海量语料里经常出现在相似上下文（同一段介绍店铺的文字），
编码器学到的表示就把它们放到了相近的方向。这不是关键词匹配，是分布语义。

**⑥ 暴力检索 vs 近似最近邻**

| 方式 | 复杂度 | 适用 |
| --- | --- | --- |
| 暴力（本项目） | O(N×d)，125×512 ≈ 6 万次乘加 | 万级以内，5ms |
| HNSW（Qdrant/ES） | 近似 O(log N)，图索引逐层跳转 | 十万级以上 |

HNSW 的直觉：把向量建成多层图，上层稀疏用于快速跳转，下层稠密用于精确定位，
查询时从顶层进入、逐层下降，牺牲一点召回率换几个数量级的速度。

本项目 125 个块用暴力检索：代码十行、没有索引维护成本、结果 100% 准确。这是**规模决定架构**，不是技术落后。

**⑦ 双塔 vs 交叉编码器**

- 双塔（bi-encoder）：问题和文档各自编码，可以离线建索引，但两者没有交互；
- 交叉编码器（cross-encoder，如 bge-reranker）：把问题和文档拼起来一起过模型，精度高但没法预计算；
- 工程上常用「双塔召回 top50 → 交叉编码器重排 top5」，本项目目前只做了召回。

---

## 二、Agent 工程

### 2.1 架构一句话

> FastAPI 接收消息 → 会话（panel → session → 草稿）从 MySQL 加载 → LangGraph 状态机分流：
> 发单走「抽取→校验→追问/出卡片」的确定性流程，查询类走 ReAct 工具循环 → 工具通过 MCP 子进程暴露。

### 2.2 关键设计点

| 设计点 | 做法 | 为什么 |
| --- | --- | --- |
| 入口分流 | 规则词表 → 模型兜底 → 保守降级 | 99% 请求不花额外延迟，且行为可预测 |
| 状态机 vs Agent | 发单流程固定用图，查询开放用循环 | 可测试、可兜底；开放场景才交给模型 |
| 槽位填充 | 草稿是结构化状态，每轮以 JSON 注入 prompt | 模型只输出增量补丁，状态由程序合并 |
| 工具注入 | MCP 客户端把 `user_token` 塞进参数，schema 里剔除 | 模型无法伪造身份 |
| 降级 | 模型不可用 → 按路由执行确定性计划 | 不让一次超时变成「助手不可用」 |
| 幂等 | bizNo / message_id / hash | MQ 重复消费不会重复扣钱 |
| 记忆 | 草稿存 DB，历史存消息表，每轮重新注入 | state 不持久化，记忆必须在外部 |

### 2.3 模拟问答

**Q1：为什么用 LangGraph 而不是直接写 if-else？**
> 状态机把「合法流转」固化成边，测试可以逐个节点驱动；草稿状态每轮可序列化落库；
> 条件边表达分支比嵌套 if 清楚，而且换节点顺序不用改业务代码。

**Q2：Agent 和 Workflow 怎么选？**
> 步骤固定、要求可预测的（填槽发单）用 Workflow；用户意图开放的（查数据、取消、结算）用 Agent。
> 我的判断标准：**错了要不要赔钱**。发单错了要赔钱，所以必须可测试、可兜底。

**Q3：ReAct 循环怎么防死循环？**
> 最多 3 轮；只有最后一轮才强制回答（提前摘掉 tools 会让模型把调用写成文本，这个坑踩过）；
> 同一工具同一参数不重复调用；模型失败时走确定性降级计划。

**Q4：工具很多时模型选错怎么办？**
> 按路由只挂相关工具（实测 1 个工具 23 秒、5 个工具 83 秒，工具越多越慢越容易错）；
> 提示词里写清楚「问接的单用 list_my_errands，不要用 get_wallet」这种反例。

**Q5：怎么保证模型不乱发单？**
> 发布要用户明确说「发布」；发布前校验截止时间在未来；幂等键防重复建单；
> 发出后会话置 CLOSED，同一张卡片冻结，防止一单发两次。

**Q6：多轮对话怎么记住上下文？**
> 两层：草稿是结构化状态（DB），每轮反序列化后以 JSON 注入 prompt；历史是纯文本（最近 20 条，每条截 120 字）。
> 分工：状态回答「现在这单是什么」，历史回答「用户之前说过什么」。

**Q7：怎么处理「用户说了一句不像原意的话」？**
> 例子：填槽问「送到哪」，用户答「8块」。规则判定不像地点 → 槽位保持空 → 回复里说明「这句不太像地点」，而不是硬写进去。
> 原则：**宁可多问一句，也不把错的东西写进状态**。

---

## 三、Java 后端

### 3.1 架构与分层

```text
dash-domain        聚合根 Errand、状态机、端口接口（无框架依赖）
dash-application   用例编排：Grab/Settle/Cancel/Expire/Arbitrate
dash-infrastructure JDBC 实现、Redis、MQ、分片
dash-presentation  Controller、鉴权拦截器
dash-bootstrap     启动、配置；dash-worker 定时任务；dash-shared 错误码/金额
```

**面试点**：依赖方向只能由外向内；domain 不 import Spring；换存储只改 infrastructure。

### 3.2 任务状态机

```text
DRAFT → PUBLISHED → LOCKED → ACCEPTED → PICKED_UP → DELIVERED → SETTLED
                      ↓ 流转下一位（同状态自环，靠 round 区分轮次）
任何 PUBLISHED..DELIVERED 都可以 → EXPIRED（超时作废 + 退款 + 追责）
```

**面试点**：状态流转只在 `Errand.transitTo()` 里做，字段没有 public setter——
「必须校验合法性」「必须带 version」在编译期就固化，新人绕不过去。

### 3.3 并发与一致性

| 手段 | 用法 |
| --- | --- |
| CAS 乐观锁 | `UPDATE errand SET status='LOCKED', version=version+1 WHERE id=? AND status='PUBLISHED' AND version=?`，返回 0 表示抢输了 |
| 唯一索引 | `active_key` 保证一个面板只有一个进行中会话；`biz_no` 保证信用事件幂等 |
| 托管单 CAS | `HELD → REFUNDED` 只能成功一次，防重复退款 |
| 事务边界 | 状态变更 + 退款 + 退券 + 信用事件在同一事务 |

**面试点**：抢单用 CAS 不用悲观锁——N 抢 1 本来就是高失败场景，行锁排队反而更慢。

### 3.4 资金

- 发单冻结：用户账户 → 托管账户（`escrow_order`），记两条 ledger（借/贷）；
- 结算：托管 → 跑腿人 + 平台佣金，双分录；
- 退款：托管 → 发单人，同样双分录；
- `biz_no` 唯一：同一业务只能记账一次。

**面试点**：金额用 `long` 存分，不用 double；账不平就是 bug，压测脚本会核对「借贷差 = 0」。

### 3.5 定时与异步

- **RocketMQ 延迟消息**：发单时投递一条「到期检查」，到点消费 → 查状态 → 该过期就过期；
- **抢单超时流转**：LOCKED 超过 N 分钟自动转下一位候选；
- **信用每日校准**：按最近 30 天事件重算分数（滑动窗口）。

**面试点**：延迟消息只做「到点提醒」，真正判断在消费端重新查状态——消息早到、重复到都不会出错。

### 3.6 模拟问答

**Q1：抢单怎么防超卖？**
> 三重：CAS 更新（status + version）+ 数据库唯一约束（一人一单）+ 名额计数原子扣减；失败返回具体原因（已满/重复/状态变了）。

**Q2：事务里发了 MQ 消息，消息发出去但事务回滚了怎么办？**
> 用「事务提交后发送」：`TransactionSynchronization.afterCommit` 里发；消费端做幂等（bizNo 唯一索引），重复也不出错。

**Q3：任务过期和结算并发怎么办？**
> 两边都是 CAS：过期要求 `status IN (...)` 且 version 匹配，结算要求 `status='DELIVERED'`。谁先成功另一个就返回 0，不会双花。

**Q4：为什么用雪花 ID？**
> 需要全局唯一、趋势递增（B+ 树友好）、且能在插入前就拿到 id（塞消息、做关联）。
> 前端 JS 精度问题：string 化传输。

---

## 四、数据库与中间件原理

### 4.1 MySQL

| 考点 | 要点 |
| --- | --- |
| 索引结构 | InnoDB 用 B+ 树：非叶子只存键、叶子存数据且相连，范围查询友好 |
| 聚簇索引 | 主键索引叶子是整行；二级索引叶子是主键值，查非索引列要回表 |
| 最左前缀 | 联合索引 (a,b,c) 能命中 a、a+b、a+b+c，跳过 a 就用不上 |
| 覆盖索引 | 查询列都在索引里，不用回表 |
| 事务隔离 | 默认 RR；MVCC 靠 undo log + ReadView 实现快照读，间隙锁防幻读 |
| 唯一索引 | 幂等的最后一道防线（本项目多处依赖它） |
| FULLTEXT | 倒排索引 + ngram 分词，见 RAG 章节 |

### 4.2 Redis

| 考点 | 要点 |
| --- | --- |
| 数据结构 | String / Hash / List / Set / ZSet；本项目排名用 ZSet（分 + userId） |
| 缓存一致性 | 先更新库再删缓存；删除失败靠过期兜底；不更新缓存是为了防并发写乱序 |
| 原子性 | INCR / SETNX / Lua 脚本；抢单名额计数用 DECR 保证原子 |
| 分布式锁 | SETNX + 过期时间 + 唯一 value，释放时校验 value（防误删别人的锁） |
| 击穿/雪崩 | 空值缓存、过期时间打散、互斥重建 |

### 4.3 RocketMQ

| 考点 | 要点 |
| --- | --- |
| 延迟消息 | 定时/延时投递；本项目用它做任务到期检查 |
| 顺序消息 | 同一队列按 key 分区；本项目用 bizNo 做幂等而非强顺序 |
| 可靠投递 | 生产端重试 + 消费端 ACK；消费失败进重试队列 |
| 幂等 | 唯一索引 / 状态判断；消息可能重复，业务必须幂等 |

---

## 五、系统设计题

**题：设计一个校园跑腿平台**

回答框架（按顺序讲，不要一上来讲技术）：

1. **业务边界**：发单、抢单、履约（取货/送达）、结算、退款、争议仲裁；角色：发单人、跑腿人、平台。
2. **核心模型**：任务（状态机）、托管单（钱）、候选队列（抢单）、信用事件（追责）。
3. **关键流程**：发单冻结资金 → 广场可见 → 抢单（CAS + 名额）→ 确认 → 取货 → 送达 → 结算（托管打款 + 双分录）→ 过期/取消退款。
4. **一致性**：状态 CAS、资金双分录 + 唯一 bizNo、定时任务补偿。
5. **高并发**：抢单热点用 CAS + Redis 计数 + 限流；读多写少的数据加缓存。
6. **可观测**：状态日志、资金流水审计、信用事件可回溯。

**加分点**：主动说「钱和状态必须同事务，消息只能放在提交后发」「过期需要区分无人接单和接单未送达，后者要追责」。

---

## 六、项目讲述模板

### 6.1 两分钟版本（自我介绍里用）

> 我做了一个校园跑腿平台 CampusDash，分三块：
> Java 后端负责交易与状态机——发单冻结资金、抢单用 CAS 防超卖、送达后结算走双分录，
> 过期由 RocketMQ 延迟消息触发，自动退款；
> React 前端做广场、详情、消息中心；
> Python Agent 服务做对话式发单和查询——
> 用 LangGraph 把发单拆成确定性流程，查询走 ReAct 工具循环，工具通过 MCP 暴露；
> 其中还接了一个 RAG 知识库，能回答食堂营业时间、宿舍楼栋这类校园事实。

### 6.2 深挖版本（被问「挑一个你最有成就感的点」）

> 最有成就感的是 RAG 那一块。一开始只有关键词检索，用户问「菜鸟驿站几点关门」永远搜不到——
> 因为知识库里写的是「营业时间 08:00-20:00」，一个字都不重合。
> 我加了本地 BGE 向量做语义召回，两路融合后 Top1 一路提到 96%。
> 但融合之后就发现新问题：宿舍块因为同时命中「西区」「东区」多个召回变体，把商店挤下去了。
> 最后改成「原句结果在前、变体只补召回」，回到 94%，并用 50 条评测集把这个数字固定下来。
> 整个过程中我量过的每一步都有数据，不是凭感觉调。

### 6.3 被问「你的贡献是什么」

> 后端的状态机、资金托管、过期任务是我写的；Agent 服务的路由、工具循环、RAG 也是我写的。
> 我重点负责的是「钱和状态」这一块——因为这里出错是真赔钱，所以我坚持用 CAS 和唯一索引做幂等，
> 并且每个资金操作都有 ledger 双分录可审计。

---

## 七、抢单与领券：并发与一致性（重点）

### 7.1 一句话版本

> 抢单和领券都是「N 抢 1」的高冲突场景：用 **Redis Lua 在内存里做一次原子裁决**挡住 99% 的无效请求，再用 **MySQL 的 CAS + 唯一索引**做最终裁决；Redis 成功但 DB 失败必须回滚名额，否则名额永久泄漏。

### 7.2 原子性到底来自哪

Redis 是**单线程事件循环**：同一时刻只有一个命令在执行。单条命令原子，但「读—判—写」三条命令之间会被别的客户端插进来——这就是竞态的根源。

`EVAL` 把整段脚本变成**一条命令**，脚本执行期间事件循环不会切去处理别的请求：

```text
朴素做法（有窗口）              Lua 做法（无窗口）
GET slot  ←── 这里能插队       ┌──────────────┐
判断 > 0                        │ GET slot     │
DECR slot                       │ 判断 > 0     │
                                │ DECR slot    │
                                └──────────────┘
```

**结论：原子性来自「单线程 + 脚本作为单个命令执行」，不是锁。**

必须记住的边界：**Lua 不是事务，不会回滚**。脚本中途报错，前面已执行的写操作仍然生效。所以脚本要「先校验、后写入」，并且回滚脚本自身要幂等。

### 7.3 三层幂等键

| 层 | 键 | 谁生成 | 存在哪 |
| --- | --- | --- | --- |
| 请求幂等 | `requestId` | 客户端 `X-Request-Id`，服务端兜底 UUID | `errand:idem:{errandId}:{requestId}`，TTL 300s |
| 业务幂等 | `(errandId, runnerId)` / `(userId, type)` | 服务端推导 | `errand:grabbed:{id}` 集合 / `voucher:claimed:{type}:{uid}` |
| 落库幂等 | 数据库唯一索引 | DDL | `uk_errand_round_user`、`uk_errand_round_seq` |

**为什么狂点不会重复**：

1. 同一个 `requestId` 再来 → Lua 里 `GET idem` 命中 → 返回 `-2`（幂等返回成功）；
2. 换了 `requestId` 但同一个人 → `SISMEMBER grabbed` 命中 → 返回 `-1`；
3. Redis 判断失效（重启、绕过）→ 唯一索引在 DB 层再拦一次。

> 现状说明：本项目前端 `api.grab()` **没有传** `X-Request-Id`，服务端每次随机 UUID，所以请求级幂等实际未生效，真正兜住的是业务幂等。这是可改进点，面试若被问「怎么保证重复请求」要先说清这层。

### 7.4 抢单链路：L1 → L4

```text
POST /api/errands/{id}/grab
  ├─ L1 限流            rateLimiter.tryPass(errandId, runnerId)      → GRAB_RATE_LIMITED
  ├─ L2 资格前置        creditScore < 40 / 在途单 >= 5               → CREDIT_TOO_LOW / TOO_MANY_ONGOING
  ├─ L3 Redis Lua       grab.lua（幂等 / 名额 / 已抢 / 扣减）        → SLOT_FULL / ALREADY_GRABBED / DUPLICATE_REQUEST
  └─ L4 事务落库        casLockForRunner + insert grab_record + status_log
        ├─ CAS 影响行数 0 → 回滚 Redis → GRAB_CONFLICT
        └─ 成功 → 登记确认超时消息 → 删详情缓存 → WebSocket 推送
```

`grab.lua` 返回码：

| 返回 | 含义 | 后续 |
| --- | --- | --- |
| `1` | 占位成功 | 继续 L4 |
| `0` | 名额已满 | 进候选队列 |
| `-1` | 同一用户已抢过 | 直接失败 |
| `-2` | 同一 requestId 重复 | 幂等返回成功 |
| `-3` | 名额键不存在（未发布/已结束） | 不可抢 |

**L3 与 L4 是同步串行的**：同一个线程、Redis 一次 RTT、L4 是独立事务，中间没有异步、没有排队。L4 成功之后才做三件事（登记超时、删缓存、推送）——都是外部 IO，不放进业务事务。

### 7.5 抢单失败的回滚：三件事 + 一个幂等开关

```lua
-- rollback_slot.lua
if redis.call('SREM', KEYS[2], ARGV[1]) == 1 then   -- ① 从 grabbed 集合移除
    redis.call('INCR', KEYS[1])                     -- ② 名额 +1
    redis.call('DEL', KEYS[3])                      -- ③ 删幂等键
    return 1
end
return 0
```

| 你的问题 | 答案 |
| --- | --- |
| 退回名额？ | ✅ `INCR slot` |
| 删 request 键？ | ✅ `DEL idem` |
| 删 grabber 成员？ | ✅ `SREM grabbed` |

**为什么先 `SREM` 再 `INCR`**：`SREM` 的返回值就是幂等开关，返回 0 说明没占过或已回滚，直接退出，**不会把名额加多**。

**什么时候触发**：`grab()` 内部同步执行，三个点——任务不存在、CAS/唯一索引冲突、任意运行时异常。`DUPLICATE_REQUEST` **不回滚**（本来就没扣）。

### 7.6 领券链路：先扣 Redis，再异步落库

```text
POST /api/vouchers/claim
  ├─ 前置校验   online / countAvailable                → NOT_AVAILABLE / ALREADY_CLAIMED
  ├─ Redis Lua  EXISTS claimed？stock<=0？             → -1 / -2
  │             DECR stock、INCR pending、SET claimed、SET pending:id
  ├─ 发送 MQ    失败 → 立即 rollback（不挂死名额）
  └─ Worker     findById 判重 → 事务：insertIfAbsent → decrementStock → 登记过期消息
                 成功 → confirm；失败 → rollback；重试 5 次补偿
```

**领券的 rollback 和 confirm 差别在哪**：

| | 删 pending 明细 | 减 pending 计数 | 删 claimed 标记 |
| --- | --- | --- | --- |
| **confirm**（落库成功） | ✅ | ✅ | ❌ 保留（已领过不能再领） |
| **rollback**（落库失败） | ✅ | ✅ | ✅ 删除（否则永久失去领取资格） |

rollback 脚本的幂等开关是 pending 明细键：`DEL` 返回 0 就退出，不会重复加库存。

### 7.7 为什么抢单同步、领券异步

| | 抢单 | 领券 |
| --- | --- | --- |
| 用户预期 | 必须**当场**知道抢没抢到 | 发到就行，慢 100ms 无感 |
| 裁决 | Redis 预筛 + DB 当场 CAS | Redis 预扣 + MQ 削峰 + Worker 落库 |
| 补偿 | `rollback_slot.lua` | Worker 重试 5 次后 rollback |

**一句话**：抢单的胜负必须实时，所以 DB 当场裁决；领券可以最终一致，所以用 MQ 换吞吐。

### 7.8 模拟问答

**Q：Lua 为什么能保证原子性？能回滚吗？**
> 单线程 + 脚本作为一条命令执行，执行期间不被打断，所以「检查 + 写入」之间没有窗口。但它不是事务，中途报错不会回滚已执行的写，所以校验必须放在写之前，回滚脚本还要做成幂等。

**Q：一人一单靠什么？**
> 三层：Lua 里 `SISMEMBER` 挡同一个人换 requestId 再抢；`uk_errand_round_user` 唯一索引挡绕过 Redis 的情况；`requestId` 幂等键挡同一次请求重发。

**Q：Redis 扣了名额、DB 写失败怎么办？**
> 立即调用回滚脚本：`SREM` 返回 1 才 `INCR`，所以重复回滚不会把名额加多。另外还有 Worker 的扫描与对账兜底。

**Q：领券为什么删掉 claimed 标记，confirm 为什么不删？**
> rollback 是「这次没领成」，必须让人能重试；confirm 是「确实领到了」，删了就能无限领。

---

## 八、事务消息与延迟消息（重点）

### 8.1 为什么需要两种消息

| 需求 | 方案 | 原因 |
| --- | --- | --- |
| 资金事件通知（立即发、不能丢） | **事务消息** | 消息与本地事务原子，省掉本地消息表和重发 job |
| 超时流转 / 自动结算 / 任务过期（要延迟） | **本地消息表 + 定时消息** | RocketMQ 里事务消息与定时消息**互斥**（事务消息不支持 `setDeliveryTimestamp`） |

### 8.2 事务消息的三段式，本地事务写在哪

```java
// RocketMqFundEventAdapter.publishInTransaction()
transaction = producer.beginTransaction();          // ① 开启事务
producer.send(half, transaction);                   // ② 发半消息（消费者看不见）
committed = localWork.execute();                    // ③ 本地事务 ← 这里
if (committed) transaction.commit(); else transaction.rollback();   // ④
```

**本地事务不在适配器里，而是以 lambda 从用例层传进来**：

```java
// SettleErrandUseCase:145
boolean committed = fundEventPort.publishInTransaction(event, () ->
        Boolean.TRUE.equals(transactionTemplate.execute(status -> {
            boolean settled = doSettle(errandId, errand, escrow, ...);
            if (!settled) status.setRollbackOnly();
            return settled;
        })));
```

> 为什么用 `TransactionTemplate` 而不是 `@Transactional`：lambda 里的 `this` 是原始对象、不是 Spring 代理，注解会**静默失效**（自调用陷阱）。

**顺序为什么是「先发半消息、再执行本地事务」**：消息一定存在，本地事务失败就 rollback 丢弃；反过来先动钱再发消息，发送失败就是「钱扣了、事件没了」，无法补偿。

### 8.3 回查写在哪：Producer 上，不是 topic 配置

```java
// RocketMqConfig
Producer txProducer = provider.newProducerBuilder()
        .setClientConfiguration(configuration)
        .setTransactionChecker(adapter.checker())    // ← 回查器挂在 Producer 上
        .build();
adapter.attachProducer(txProducer);
```

- topic 配置只决定 broker 侧属性（例如 `message.type=DELAY`），与回查无关；
- broker 按 **producer group** 找到客户端实例，再回调这个 checker；
- 5.x 的 `ProducerBuilder` **没有 setGroup**（`javap` 验证过 5.2.0 只有 setClientConfiguration / setTopics / setMaxAttempts / setTransactionChecker / build），group 由 SDK 管理。

**回查三态 + 60 秒宽限期**：

```java
if (exists)                        return COMMIT;     // 流水存在
if (age > 60_000ms)                return ROLLBACK;   // 等够了还没有 → 判失败
return UNKNOWN;                                       // 还在宽限期内 → 让 broker 再问
```

**判定依据只查 `wallet_ledger.bizNo`**：流水表只增不改，是确定性事实；任务状态会被后续操作改掉，不能用来判定。

### 8.4 Producer 是单例，而且刻意不暴露

```text
timeoutProducer   @Bean(destroyMethod="close")   单例，被 3 个适配器共享（普通/延迟消息）
txProducer        在 fundEventPort() 内部 new     单例，藏在 adapter 的 private 字段
```

- `txProducer` **不是 Bean**：局部变量 → `attachProducer()` → 私有字段，外部拿不到；
- 好处：容器里只有一个 `Producer` 类型的 Bean，按类型注入不会歧义；
- `fundEventPort()` 只暴露 `FundEventPort` 接口，业务代码只调 `publishInTransaction()`；
- 每一步 `send` 都用 `this.producer`（即 txProducer），`send(msg, transaction)` 这个重载才是事务消息；
- 生命周期：txProducer 由 adapter 的 `close()` 释放（`@Bean` 的 destroyMethod 找不到接口上的 close）。

**如果要有第二套回查逻辑**：checker 与 Producer 是 1:1，必须新建一个 Producer；推荐照抄现有模式——新的 `@Bean` 方法内 `new producer + setTransactionChecker + attach`，只暴露业务端口；如果非要暴露成 Bean，则必须命名 + `@Qualifier`，并且所有按类型注入 `Producer` 的类都要跟着改。

### 8.5 延迟消息的四个工程细节

1. **用 5.x 的 `setDeliveryTimestamp`**：4.x 只有 18 个固定 delay level，撑不住「用户任意选的截止时间」；
2. **topic 必须是 DELAY 类型**，否则 broker 拒绝；
3. **超过 24 小时先按 24 小时投一次**，消费端重查真实截止时间，没到就再投（分段续投）；
4. **消息体只带定位字段** `{errandId, round, version}`，不带业务快照——消息可能在队列里躺 24 小时。

幂等键带轮次 / 时间戳：

```text
timeout:{errandId}:{round}         超时流转，round 自增，旧轮次消息不能挡住新一轮
autosettle:{errandId}              自动结算，一个任务只送达一次
expire:{errandId}:{epochMillis}    过期，改了截止时间重新发布 → key 不同
```

### 8.6 本地消息表：发送和登记不在一个事务

```text
【事务内】 ① enqueue → local_message(status=PENDING)     和业务同生共死
             │ commit
【提交后】 ② send(topic, msgKey, payload, deliverAt)     外部 IO，不在事务里
           ③ markSent(msgKey) → SENT                     独立 UPDATE
             │ ②③ 失败
【兜底】   ④ RetryJob 扫 PENDING 重发
```

- `enqueue` 在业务事务里，业务回滚它跟着回滚；
- 发送注册在 `afterCommit`（`TransactionSynchronization`），提交后才发；
- `send` 与 `markSent` **不是原子的**：send 成功、markSent 前崩溃 → 消息重复，这是 **at-least-once**，靠消费端重查状态 + msgKey 幂等消化；
- `uk_msg_key` 唯一索引：重复登记返回 `false`（不抛异常、不回滚业务），调用方据此不再发送。

**RetryJob**：

```text
每 10 秒扫一次，一次 100 条
WHERE status='PENDING' AND next_retry_at <= NOW(3)
成功 → markSent；失败 → markRetry：retry_count+1，2^n 秒退避（封顶 300s）
第 6 次失败 → status='DEAD'，人工介入
```

### 8.7 三种消息路径对比

| | 事务消息 | 延迟消息 + 本地消息表 | 普通消息（领券） |
| --- | --- | --- | --- |
| 解决 | 消息与本地事务原子 | 到点执行 + 不丢 | 削峰异步 |
| 用在 | 资金事件 | 超时流转、自动结算、过期 | 领券落库 |
| 一致性 | 半消息 + broker 回查 | local_message + 重发 job | Redis 预扣 + 消费端幂等 |
| 幂等 | bizNo 查 ledger | msg_key 唯一索引 | voucherId / 唯一索引 |

### 8.8 模拟问答

**Q：事务消息本地事务写在哪？**
> 不写在 MQ 适配器里，而是做成 `LocalWork` 函数式接口，由用例层用 lambda 传进去（内部 `TransactionTemplate` 手动圈事务）。适配器只负责 begin → send half → 调 lambda → commit/rollback。

**Q：回查逻辑写在 topic 配置里吗？**
> 不是。checker 是在构建 Producer 时 `setTransactionChecker` 注册的，broker 按 producer group 找到客户端实例回调它。topic 配置只管 broker 侧属性。

**Q：发送消息和写 local_message 在同一个事务吗？**
> 不在同一事务。登记在业务事务内，发送在 `afterCommit`，`markSent` 是独立 UPDATE。所以是 at-least-once，重复由消费端幂等兜住。

---

## 九、登录与账号管理

### 9.1 登录链路（5 步）

```text
① 图形验证码  GETDEL 原子取用（一次性，5 分钟 TTL）
② 查库        findByUsername → user_id（后续身份只认这个 id）
③ 校验密码    失败 → login_fail_count + 1，统一返回 BAD_CREDENTIALS
④ 状态检查    enabled=false → ACCOUNT_DISABLED
⑤ 签发凭证    accessToken（+ refreshToken），sub = 数据库 user_id
```

**为什么账号不存在和密码错误返回同一句话**：否则接口变成用户名枚举器。

**为什么 token 的 sub 用数据库 id**：拦截器拿到的就是真实身份，不再相信请求里任何自报字段。

### 9.2 两种模式：session（默认）与 jwt

```yaml
dash:
  auth:
    mode: session          # session | jwt
    session-ttl-minutes: 120
    allow-header-identity: false   # 测试/压测后门，生产必须关
```

| | session 模式 | jwt 模式 |
| --- | --- | --- |
| 实现 | `RedisAuthAdapter` | `JwtAuthAdapter` |
| 服务端可撤销 | ✅ 删 key 立即失效 | ❌ 需要黑名单/强制下线兜底 |
| 多端管理 | ✅ 天然支持 | 需要额外状态 |

### 9.3 Redis Session 的三个 key

```text
auth:token:{token}       -> userId            TTL = 120min（滑动续期）
auth:sessions:{userId}   ZSET token -> 过期时间戳
auth:online              ZSET userId -> 最晚一个 token 的过期时间戳
```

**为什么用 ZSET 而不是 Set**：Set 不支持 member 级 TTL，没法让多个 token 各自过期；ZSET 用 score 存过期时间，读取和统计时按 score 裁剪。key 本身只是容器，真正过期靠 score。

### 9.4 续期、多端、登出、强制下线

| 场景 | 做法 |
| --- | --- |
| 滑动续期 | 每次请求 `touch(token)`：重置 token TTL、更新两个 ZSET 的 score、顺手裁掉过期 member |
| 多端登录 | 同一 userId 多个 token 并存；`WsSessionRegistry` 里一个 userId 对应多个 WebSocket 连接 |
| 单端登出 | 删 token key + 从 sessions 移除；如果该用户没别的 token，同时从 online 移除并删 sessions 容器 |
| 强制下线 | 写 `auth:forced:{userId}`（TTL 12h）+ `revokeAllSessions` + 关闭该用户全部 WS 连接；拦截器与 WS 握手都会检查这个标记，重新登录成功时清除 |
| 在线统计 | `auth:online` 按 score 裁剪后 `ZCARD`，score 过期即离线 |

### 9.5 管理端能力

| 能力 | 接口 |
| --- | --- |
| 用户列表 / 分页 | `GET /api/admin/users` |
| 启用 / 禁用账号 | `POST /api/admin/users/{id}/status` |
| 禁言（聊天） | `POST /api/admin/users/{id}/message-status` |
| 强制下线 | `POST /api/admin/users/{id}/force-logout` |
| 平台佣金 / 券分摊比例 | `GET/PUT /api/admin/config` |
| 优惠券管理 | `GET/POST /api/admin/vouchers`、`PUT /api/admin/vouchers/{type}/stock` |
| 争议仲裁 | `POST /api/admin/errands/{id}/arbitrate` |

**禁言怎么生效**：`users.messageAllowed(userId)` 在发送消息用例里检查，被禁言直接抛 `CHAT_MUTED`；不依赖前端。

### 9.6 安全细节（面试加分）

1. **验证码一次性**：`GETDEL` 原子取用，并发提交同一个 captchaId 只有一个能拿到答案；
2. **验证码答案不进响应、不进 Cookie**：前端只拿到 captchaId + PNG base64；
3. **后门开关**：`X-User-Id` 只在 `allow-header-identity=true` 时生效，默认关闭；
4. **强制下线双检查**：HTTP 拦截器 + WebSocket 握手都查 `auth:forced:`；
5. **登录失败计数**：`login_fail_count` 为后续锁定策略留数据。

### 9.7 模拟问答

**Q：token 存在哪？怎么防止被踢下线后还能用？**
> Session 模式存在 Redis：`auth:token:{token} -> userId`，TTL 120 分钟，每次请求滑动续期。踢人时删 token + 写 `auth:forced:` 标记，拦截器先查标记，所以即使有残留 token 也会 401。

**Q：多端登录怎么实现的？**
> 一个 userId 下允许多个 token：`auth:sessions:{userId}` 用 ZSET 存 token → 过期时间。WebSocket 侧用 `ConcurrentHashMap<userId, CopyOnWriteArraySet<session>>`，登出只关对应 token 的连接，不影响同用户其他端。

**Q：为什么用 ZSET 不用 Set？**
> Set 的 member 没有独立 TTL，多个 token 无法各自过期；ZSET 用 score 存过期时间戳，裁剪时 `ZREMRANGEBYSCORE` 就够。

---

## 十、WebSocket 与聊天广场

### 10.1 WebSocket 基础（30 秒版）

| 维度 | WebSocket | HTTP 轮询 | SSE |
| --- | --- | --- | --- |
| 方向 | 全双工 | 单向（客户端发起） | 服务端 → 客户端单向 |
| 连接 | 一次握手后长连 | 每次都新建 | 长连 |
| 协议 | `ws://` / `wss://`，握手用 HTTP Upgrade | HTTP | HTTP |
| 适用 | 聊天、协同、推送 + 上行 | 低频刷新 | 只下行（通知、日志流） |

**握手**：客户端发 `Upgrade: websocket` + `Sec-WebSocket-Key`，服务端回 `101 Switching Protocols`，之后就是帧（frame）通信。

**心跳**：长时间无数据会被中间设备断开，所以要有 ping/pong——本项目直接用文本帧 `ping`/`pong`。

**断线重连**：客户端指数退避重连，重连后靠「拉历史 + afterId 补洞」恢复丢失的消息——这是 at-least-once 推送的标准做法。

### 10.2 本项目装配

```java
registry.addHandler(wsHandler, "/ws")
        .addInterceptors(handshakeInterceptor)
        .setAllowedOrigins("*");     // 演示环境放开，生产要收敛白名单
```

**鉴权为什么用 query 参数**：浏览器原生 `WebSocket` 不能自定义请求头，所以 `?token=xxx`。握手时解析：

```java
String token = servletRequest.getServletRequest().getParameter("token");
Optional<String> userId = authPort.resolve(token);
if (userId.isPresent() && !forceLogoutPort.isForced(userId.get())) {
    attributes.put(ATTR_USER_ID, userId.get());
    attributes.put(ATTR_TOKEN, token);
    return true;
}
return false;   // 未认证：拒绝握手
```

> 生产改进：token 放 URL 会进日志和代理记录，应换成短时效一次性 ticket + WSS。

**连接注册表**：

```java
ConcurrentHashMap<String, Set<WebSocketSession>>   // userId -> 该用户的所有连接
CopyOnWriteArraySet                                 // 读多写少：推送遍历，增删低频
```

### 10.3 帧协议

```text
客户端 → 服务端
  "ping"                                   保活
  {"t":"chat.send","clientMsgId":"...","content":"..."}    发广场消息

服务端 → 客户端
  "pong"
  {"type":"chat.message","payload":{id,roomId,senderId,senderName,senderRole,
                                    content,msgType,errandId,errandStatus,createdAt,clientMsgId}}
  {"type":"chat.error","payload":{clientMsgId,code,message}}
```

### 10.4 发送链路：校验 → 幂等 → 限流 → 禁言 → 落库 → 广播

```java
String content = rawContent.trim();
if (content.isEmpty() || codePointCount > 500)  throw CHAT_INVALID_CONTENT;

// 断线重发：同一 sender + clientMsgId 直接返回已落库消息，不重复广播
var existing = messages.findBySenderAndClientId(senderId, key);
if (existing.isPresent()) return existing.get();

if (!rateLimiter.allow(senderId))               throw CHAT_RATE_LIMITED;   // 固定窗口 3 条/秒
if (!users.messageAllowed(senderId))            throw CHAT_MUTED;

messages.save(message);
try { broadcaster.broadcast(message); }
catch (RuntimeException e) { log.warn("广播失败，靠 history/afterId 补洞"); }   // 不回滚
```

**顺序不能乱**：先幂等再限流，避免重发被误判为刷屏；限流在禁言之前，避免被禁言的人还能消耗 Redis 计数。

### 10.5 存储：热表 + 历史表 + Redis 最近消息

| 层 | 内容 | 细节 |
| --- | --- | --- |
| `chat_message`（热表） | 最近 24 小时 | 主键雪花 ID（时间有序，兼作游标）；`UNIQUE (sender_id, client_msg_id)` 做幂等；`KEY (room_id, id)` 支持游标翻页 |
| `history_message`（历史表） | 超过 24 小时 | 由 `ChatArchiveJob` 每 4 小时归档，一次 500 条 |
| Redis 最近消息 | 最近 1 万条 | ZSET 存 id（排序）+ HASH 存 id → JSON；Lua 保证两张结构一致；TTL 24h |

**归档任务用 Redis 分布式锁**：

```text
chat:{archive}:lock   TTL 15 分钟，value 是实例 UUID
释放时用 Lua 比较 value 再 DEL —— 防止删掉别人的锁
```

**为什么要冷热分离**：广场消息是「读最近、写很多」的形态，热表一直增长会让索引变深、翻页变慢；每 4 小时把 24 小时前的挪走，热表规模恒定。

### 10.6 历史查询：游标而不是 OFFSET

```text
GET /api/chat/history?roomId=square&limit=50&beforeId=...   // 上翻
GET /api/chat/history?roomId=square&limit=50&afterId=...    // 补洞（断线重连）
```

- 首页直接从 Redis 缓存取最近 N 条，不足时回源热表并回填；
- 翻页要**跨两张表合并**（热表 + 历史表都查，再按 id 排序截断）；
- 用 `(room_id, id)` 索引 + id 游标，避免 `OFFSET` 越翻越慢。

### 10.7 单机限制与改进方向（主动说边界）

| 现状 | 问题 | 改进 |
| --- | --- | --- |
| `WsSessionRegistry` 是进程内 Map | 多实例部署时，A 实例推不到 B 实例的连接 | 用 Redis Pub/Sub 或 MQ 广播，各实例只推自己持有的连接 |
| 广播在发送线程同步执行 | 连接多时发送变慢 | 每连接一个有界队列 + 独立发送线程 |
| token 放 query | 可能进日志 | 一次性 ticket + WSS |
| 归档是定时任务 | 极端情况下热表会短暂变大 | 按时间分区表或定期归档 + 监控 |

### 10.8 模拟问答

**Q：为什么聊天用 WebSocket 不用轮询？**
> 广场消息是低频但要求低延迟的推送，轮询要么延迟高要么请求多。WebSocket 一次握手后全双工，服务端有消息立刻推，还能复用同一连接做上行发送。

**Q：怎么保证消息不丢、不重？**
> 不丢：消息先落库再广播，广播失败不影响落库，客户端重连后用 `afterId` 拉增量补洞。不重：`(sender_id, client_msg_id)` 唯一索引 + 发送前查重，断线重发直接返回已落库的消息。

**Q：怎么防刷屏？**
> Redis 固定窗口限流（`chat:rl:{userId}:{epochSecond}`，每秒 3 条），加上管理端禁言 `messageAllowed` 检查；限流在落库之前，被限流的请求不会碰数据库。

**Q：多实例部署会有什么问题？**
> 连接注册表在进程内存里，跨实例推不到。要么用 Redis Pub/Sub 广播，要么把在线连接信息放 Redis 做路由。这是当前实现的已知边界。

**Q：历史消息为什么分两张表？**
> 热表只留 24 小时，索引浅、翻页快；历史表归档老消息。查询时两边都查再合并，用 id 游标保证顺序和性能。

---

## 十一、速记卡

```text
【RAG】
  13 篇 → 125 块 → 512 维；bge-small-zh；2.3ms/条
  融合 0.55*BM25 + 0.45*cos + 0.30 节标题 + 0.15 校区 - 0.20 跨校区
  置信度 0.6*cos + 0.4*(BM25/4)：0.62 高 / 0.57 中 / 否则拒答
  评测 Top1 96%  Hit@3 100%  拒答 100%

【Agent】
  路由三层：词表 → 模型 → 保守降级
  ReAct 最多 3 轮，只有最后一轮强制回答
  工具按路由挂载（1 个 23s，5 个 83s）
  草稿存 DB、每轮注入；state 不持久化

【后端】
  状态机 DRAFT→PUBLISHED→LOCKED→ACCEPTED→PICKED_UP→DELIVERED→SETTLED
  EXPIRED 可从 PUBLISHED..DELIVERED 进入（含接单未送达，扣 5 分信用）
  CAS：UPDATE ... WHERE status=? AND version=?
  资金：托管 + 双分录 + bizNo 唯一
  过期：MQ 延迟消息 + 消费端重查状态

【抢单 / 领券】
  Lua 原子性 = 单线程 + 脚本作为一条命令；Lua 不回滚，先校验后写
  幂等三层：requestId(300s) → grabbed 集合 → 唯一索引
  grab.lua 返回：1 成功 / 0 满 / -1 已抢 / -2 重复 / -3 不可抢
  rollback：SREM==1 才 INCR slot + DEL idem（幂等开关）
  领券 rollback 删 claimed（可重领），confirm 保留（不能重领）
  抢单同步（要立刻知道结果），领券异步（MQ 削峰）

【消息】
  事务消息：begin → send half → localWork(lambda) → commit/rollback
  本地事务在用例层的 LocalWork 里（TransactionTemplate，避开自调用代理失效）
  回查 = Producer 上的 TransactionChecker（不是 topic 配置）；三态 + 60s 宽限
  判定只查 wallet_ledger.bizNo（只增不改）
  延迟消息：setDeliveryTimestamp，24h 分片续投，消息体只带 errandId/round/version
  local_message：enqueue 在事务内，send 在 afterCommit，markSent 独立 → at-least-once
  RetryJob：10s 一轮、100 条、6 次、指数退避封顶 300s、超限 DEAD
  Producer：timeoutProducer 单例 Bean；txProducer 藏在 adapter 私有字段（不是 Bean）

【登录 / 会话】
  验证码：GETDEL 一次性、5 分钟、4 位
  登录：查库拿 user_id → 校验密码（统一 BAD_CREDENTIALS 防枚举）→ 签发
  Redis：auth:token:{t}→userId(120min)；auth:sessions:{uid} ZSET；auth:online ZSET
  ZSET 而非 Set：member 级过期靠 score 裁剪
  强制下线：auth:forced:{uid} 12h + revokeAll + 关 WS；拦截器与握手都检查

【WebSocket / 聊天】
  /ws + ?token=xxx（浏览器 WS 不能带 header）；未认证拒绝握手
  registry：ConcurrentHashMap<userId, CopyOnWriteArraySet<session>>，多端
  帧：chat.send → 落库 → chat.message 广播；chat.error 回执；ping/pong
  发送：校验(500 码点) → clientMsgId 幂等 → 限流 3/s → 禁言 → 落库 → 广播(失败不回滚)
  存储：chat_message(24h 热) + history_message + Redis 最近 1w 条(ZSET+HASH+Lua)
  归档：每 4h、500 条/批、Redis 锁 chat:{archive}:lock 15min
  翻页：beforeId/afterId 游标跨两表合并，默认 50 最多 100
  已知边界：registry 在进程内，多实例推不到 → 需 Redis Pub/Sub
```

### 面试前最后三个提醒

1. **先讲业务再讲技术**：面试官不关心你用了什么，关心你为什么这么选。
2. **每个结论带数字**：不是「效果不错」，是「Top1 从 60% 到 96%」。
3. **主动说不足**：InnoDB 长查询衰减、RAG 只做了召回没做重排、消息中心还没做懒加载图片——
   能说清边界的人，比说「都挺好」的人可信得多。
