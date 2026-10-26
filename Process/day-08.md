# day-08 · 测试与压测：34 个测试类 + 5 个压测客户端

> 阅读范围：`dash-bootstrap/src/test`（18 个 IT + 4 个辅助类）、各模块单元测试（16 个）、`dash-bench`（5 个客户端）。
> 这一天不读业务实现，读**断言**——测试就是项目的不变式清单。

## 0. 总览

| 模块 | 测试类 | 内容 |
| --- | ---: | --- |
| dash-domain | 5 | 状态机、聚合、流转、Money、ArchUnit |
| dash-application | 4 | 限流短路、游标分页、信用校准、资金安全 |
| dash-infrastructure | 5 | 两种限流、条件装配、分片策略与规则 |
| dash-worker | 1 | 自动结算消费者 |
| dash-bootstrap | 18 | 并发、缓存、认证、事务、生命周期 |
| dash-bench | 1 | 压测客户端解析逻辑 |

合计 **34 个测试类、110 个 `@Test`**；另有 5 个压测客户端。

---

## 0.1 类名中文对照

| 类名 | 中文名 | 模块 |
| --- | --- | --- |
| `ArchitectureRuleTest` | 架构规则测试 | domain |
| `ErrandAggregateTest` | 任务聚合测试 | domain |
| `ErrandStatusMachineTest` | 状态机测试 | domain |
| `ErrandTransferTest` | 流转测试 | domain |
| `MoneyTest` | 金额测试 | domain |
| `GrabErrandUseCaseTest` | 抢单用例测试 | application |
| `QueryErrandListUseCaseTest` | 列表游标测试 | application |
| `CreditCalibrationUseCaseTest` | 信用校准用例测试 | application |
| `FundOperationSafetyTest` | 资金安全测试 | application |
| `LocalGrabRateLimiterAdapterTest` | 本地限流测试 | infrastructure |
| `SentinelGrabRateLimiterAdapterTest` | Sentinel 限流测试 | infrastructure |
| `FundEventPushConsumerConditionTest` | 推送消费者条件装配测试 | infrastructure |
| `CampusDashShardingPolicyTest` | 分片策略测试 | infrastructure |
| `CampusDashShardingRuleFactoryTest` | 分片规则工厂测试 | infrastructure |
| `AutoSettleConsumerTest` | 自动结算消费者测试 | worker |
| `ApiEndpointIT` | 端点集成测试 | bootstrap |
| `AuthModeIT` | 认证模式对照测试 | bootstrap |
| `AuthRefreshIT` | 刷新令牌测试 | bootstrap |
| `BackdoorDisabledIT` | 后门关闭测试 | bootstrap |
| `CacheCheckIT` | 缓存校验测试 | bootstrap |
| `CacheConsistencyIT` | 缓存一致性测试 | bootstrap |
| `CacheDoubleDeleteControlIT` | 双删对照测试 | bootstrap |
| `CacheEvictionOrderControlIT` | 失效顺序对照测试 | bootstrap |
| `CreditCalibrationIT` | 信用校准集成测试 | bootstrap |
| `CreditIT` | 信用链路集成测试 | bootstrap |
| `CursorPaginationIT` | 游标分页集成测试 | bootstrap |
| `GrabConcurrencyIT` | 抢单并发测试 | bootstrap |
| `OversellControlExperimentIT` | 超卖对照实验 | bootstrap |
| `PublishEscrowIT` | 发布托管测试 | bootstrap |
| `SettleConcurrencyIT` | 结算并发测试 | bootstrap |
| `SettlementIT` | 结算端到端测试 | bootstrap |
| `TimeoutTransferIT` | 超时流转测试 | bootstrap |
| `TransactionPitfallIT` | 事务陷阱测试 | bootstrap |
| `SpikeLoadClientTest` | 尖峰客户端测试 | bench |

---

## 1. 测试基础设施

### 1.1 `MiddlewareAvailable` · 中间件探测（class）

~~~java
static boolean check() {
    return portOpen("127.0.0.1", 3307) && portOpen("127.0.0.1", 6380);
}
~~~

配合每个 IT 的 `@BeforeAll`：

~~~java
Assumptions.assumeTrue(MiddlewareAvailable.check(), "中间件未启动，跳过");
~~~

**要点**：没起 Docker 时测试是 **skip 而不是 fail**，保证任何环境 `mvn test` 都不会红。

### 1.2 `BenchRun` · 集成测试轮次登记（class）

封装 `BenchRunRecorder`，每个 IT 类开一个 run：`start(scenario)`、`track(errandId)`、`finish(status, summary)`、`close()`。

**要点**：测试数据挂到 `run_id` 上，可跨轮对比、可按轮清理，不再全表 DELETE。

### 1.3 `BenchRunRecorder` · 压测轮次登记（class）

字段：`Connection conn`；常量 JDBC URL / 账号
方法：`startRun(kind, scenario, concurrency, envNote)`、`trackErrand(runId, id)`、`trackErrands(...)`、`finishRun(runId, status, summaryJson)`、`close()`

**要点**：纯 JDBC，不依赖 Spring；压测客户端与集成测试共用一份实现。

### 1.4 `PitfallService` / `ManualNewService` · 事务陷阱被测服务

每个陷阱提供“错误写法 + 正确写法”一对方法，例如：

~~~java
public void selfInvokeWrong(String tag) {
    this.txInsertThenFail(tag);   // 绕过代理，不回滚
}
@Transactional(rollbackFor = Exception.class)
public void txInsertThenFail(String tag) { insert(tag); throw new IllegalStateException(); }
~~~

**要点**：只会通过的测试没有价值，必须证明错误写法**真的会错**。

---

## 2. 领域单元测试（5）

| 类 | 关键断言 | 对应不变式 |
| --- | --- | --- |
| `ErrandStatusMachineTest` | 只有 PUBLISHED 可抢；`LOCKED → LOCKED` 合法；跳跃流转被拒；终态无出边 | 状态机 |
| `ErrandAggregateTest` | 版本不匹配拒绝流转；名额用尽不能再抢；抢中后 version++/grabberId 落定；金额 > 0、名额 ≥ 1 | INV-1、乐观锁 |
| `ErrandTransferTest` | 流转后 round+1、名额不变；回退要还名额；只有当前跑腿能确认；多轮 round 累加 | 超时流转 |
| `MoneyTest` | 整数分无浮点误差；负数拒绝；元转分覆盖一位小数 | 金额安全 |
| `ArchitectureRuleTest` | ArchUnit：domain 不依赖框架、端口定义在 domain | 架构约束 |

---

## 3. 应用层单元测试（4）

| 类 | 关键断言 |
| --- | --- |
| `GrabErrandUseCaseTest` | 限流拒绝后直接返回，**不再查信用分和名额**（短路顺序） |
| `QueryErrandListUseCaseTest` | 取 `size + 1` 判断下一页；用最后一条生成 nextCursor |
| `CreditCalibrationUseCaseTest` | 窗口天数与批大小被规范化（≥1） |
| `FundOperationSafetyTest` | 结算/退款/仲裁的权限、状态与资金安全（13 个断言） |

---

## 4. 基础设施单元测试（5）

| 类 | 关键断言 |
| --- | --- |
| `LocalGrabRateLimiterAdapterTest` | 同一任务每秒超阈值后被限流；关闭时始终放行 |
| `SentinelGrabRateLimiterAdapterTest` | 按任务维度限流；关闭时放行 |
| `FundEventPushConsumerConditionTest` | MQ/WS 开关组合下消费者是否装配 |
| `CampusDashShardingPolicyTest` | 任务表按 campusId 共片、用户表按 userId；非法表名拒绝 |
| `CampusDashShardingRuleFactoryTest` | 规则工厂生成 8 张表与绑定表组；算法实例化后路由正确 |

---

## 5. 集成测试（18）

### 5.1 并发正确性（3）

**`GrabConcurrencyIT`**（2000 并发抢 1 名额）：

| 测试 | 断言 |
| --- | --- |
| INV-1 | 有且只有 1 人成功，零超卖 |
| INV-2 | 同一用户换 requestId 再抢 → `ALREADY_GRABBED` |
| INV-3 | 同一 requestId 重复提交只生效一次 |
| INV-4 | 失败者进入候选队列 |

**`OversellControlExperimentIT`**（500 并发，故意用错误实现）：

- 实验 A：Redis 改成非原子（GET 判断 DECR 三次调用）→ Redis 判错，但 DB CAS + 唯一索引兜住，**仍不超卖**；
- 实验 B：Redis 非原子 + DB 去掉 CAS → **真正超卖**（多人都“抢中”同一名额）。

**`SettleConcurrencyIT`**：

- S4-a：500 个任务并发结算，三层对账全过、零资金差异；
- S4-b：200 并发结算**同一任务**，恰好一次成功，跑腿余额只增一次。

### 5.2 生命周期与端点（4）

| 类 | 关键断言 |
| --- | --- |
| `ApiEndpointIT` | 无 token 401；完整生命周期；权限拒绝；availableActions 与状态机一致；列表/我的/消息端点可用 |
| `TimeoutTransferIT` | 流转 round+1 名额不变；空队列回退还名额；已确认不被流转；同消息重投 5 次只流转一次；旧轮次消息丢弃；确认与流转并发必有一方失败 |
| `SettlementIT` | 跑腿 + 佣金 == 托管额；重复结算幂等；退款闭环回到初始余额 |
| `PublishEscrowIT` | 发布时借贷双写金额相等、`frozen` 仍为 0；余额不足整单回滚 |

### 5.3 缓存（4）

| 类 | 关键断言 |
| --- | --- |
| `CacheConsistencyIT` | 穿透回填；1000 个不存在 id 零回源（布隆）；50 并发逻辑过期，回源远少于并发（防击穿）；写后读到新值 |
| `CacheCheckIT` | 正常零差异；脏缓存检出、落 `sync_diff`、删缓存修正 |
| `CacheEvictionOrderControlIT` | 对照实验：先删缓存再更新 DB → 慢读把旧值写回且无人再删 |
| `CacheDoubleDeleteControlIT` | 双删接线（约 500ms 第二次删除）；没有第二次删除时旧值残留到 TTL |

### 5.4 认证（3）

| 类 | 关键断言 |
| --- | --- |
| `AuthModeIT` | Session：映射丢失即失效、登出立即失效；JWT：状态丢失仍有效、黑名单未吊销前合法、伪造签名被拒 |
| `AuthRefreshIT` | 刷新后轮换，旧 refresh 立即失效 |
| `BackdoorDisabledIT` | 后门关闭时 `X-User-Id` 直传 401，token 仍可用 |

### 5.5 事务与查询（3）

| 类 | 关键断言 |
| --- | --- |
| `TransactionPitfallIT` | 6 个陷阱（自调用、非 public、吞异常、checked、手动 new、MyISAM）+ 连接池死锁，全部“错误不回滚 / 正确回滚”成对验证 |
| `CreditIT` | 结算 +2、重复结算不重复计分、低信用被拦 |
| `CreditCalibrationIT` | 30 天窗口重算，移除过期事件贡献 |
| `CursorPaginationIT` | 连续翻页不重复不遗漏 |

---

## 6. 压测客户端（5）

### 6.1 `SpikeLoadClient` · S1 尖峰客户端（class）

**入口**：`main(baseUrl, concurrency, slotTotal)`，默认 2000 并发、1 名额。

**关键实现**：

- `CountDownLatch` 让 N 个虚拟线程**同一时刻**放行；
- 先 20 并发预热 30 轮（JIT、连接池）；
- 逐请求分类：`classifyResponse`（成功/名额满/冲突/限流/错误）；
- 结果写 `bench_run` 并输出 P50/P95/P99/Max。

### 6.2 `RampLoadClient` · S2 阶梯客户端（class）

**入口**：`main(baseUrl, stageCsv, durationSeconds)`，默认 `50,100,200,400,800`。

- 每档独立登记 `bench_run`；
- 固定时长持续压，输出 QPS 与延迟分位；
- 压的是任务广场读接口（稳态吞吐），不混入抢单正确性。

### 6.3 `CacheLoadClient` · S3 缓存客户端（class）

**入口**：`main(mode, baseUrl)`，四个模式：

| mode | 场景 |
| --- | --- |
| a | 纯读、缓存关闭（基准） |
| b | 纯读、缓存开启（命中率/回源） |
| c | 读写混合 9:1 + 压后一致性校验 |
| d | 热 Key（90% 打同一个 id，验证分片） |

**要点**：用应用自己的 `dbLoads` 统计回源次数，而不是 MySQL `Questions`（噪声大）。

### 6.4 `DelayMessageProbe` · 定时消息探针（class）

**入口**：`main(endpoint, delaySeconds)`——发一条定时消息并等待消费，验证 broker/proxy/定时投递通路。

**要点**：写业务前先跑它，否则出问题分不清是业务错还是 MQ 没通。

### 6.5 `BenchRunRecorder` · 轮次登记器（见 1.3）

---

## 7. 压测数据（同机基线）

| 场景 | 并发 | 结果 |
| --- | ---: | --- |
| S1 尖峰 | 2000 | 成功 1、超卖 0 |
| S2 阶梯 | 400 | 6455 QPS、P99 151ms |
| S2 阶梯 | 800 | 146 个 IOException、P99 390ms |
| S2 调参后 | 800 | 0 错误、5205 QPS、P99 472ms |
| S4 结算 | 500 任务 | 三层对账全过 |
| Sentinel | 1000 | 成功 1、名额满 99、限流 900、超卖 0 |

口径：应用、中间件、发压端同机，数字只作同机基线。

---

## 8. 怎么跑

~~~bash
cd campus-dash-backend

# 全量测试（中间件没起时集成测试 skip）
mvn test

# 只跑某个并发测试
mvn test -Dtest=GrabConcurrencyIT
mvn test -Dtest=SettleConcurrencyIT

# S1 尖峰
java -cp "dash-bench/target/classes:$(cat /tmp/cp.txt)" \
  com.campusdash.bench.SpikeLoadClient http://127.0.0.1:8080 2000 1

# S2 阶梯
java -cp "dash-bench/target/classes:$(cat /tmp/cp.txt)" \
  com.campusdash.bench.RampLoadClient http://127.0.0.1:8080 50,100,200,400,800 60
~~~

---

## 9. 横向模式

| 模式 | 例子 | 为什么 |
| --- | --- | --- |
| **测试即不变式文档** | INV-1..4、三层对账 | 规则写在断言里，改坏就红 |
| **对照实验** | 超卖、缓存失效顺序、双删 | 证明“测试有效”，不是只会通过 |
| **skip 而非 fail** | `MiddlewareAvailable` | 任何环境都能跑 `mvn test` |
| **轮次留痕** | `BenchRunRecorder` | 数据可对比、可清理 |
| **自研压测** | 三种客户端 | 零外部依赖，能直接连库校验 |

## 10. 自测

- [ ] 为什么 `OversellControlExperimentIT` 要故意写错？
- [ ] 实验 A 里 Redis 已经判错了，为什么最终没有超卖？
- [ ] `GrabConcurrencyIT` 的 2000 并发为什么用 `CountDownLatch` 而不是直接启动线程？
- [ ] `TransactionPitfallIT` 的“错误写法不回滚”有什么价值？
- [ ] 为什么集成测试用 skip 而不是 fail？
- [ ] S2 压测为什么只压任务广场，不压抢单？
