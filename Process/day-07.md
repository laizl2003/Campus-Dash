# day-07 · worker / bootstrap / shared 源码详解：19 个文件

> 阅读范围：`dash-worker`（13）+ `dash-bootstrap`（1）+ `dash-shared`（5，回顾）。
> 沿用前几天的格式：类型、字段、构造器、方法签名、功能、关键源码。
> 这一天把前六天串起来：**消息怎么被消费、兜底怎么跑、进程怎么装配**。

## 0. 总览

| 分组 | 文件 | 一句话 |
| --- | ---: | --- |
| 进程装配 | 3 | worker 启动、workerId、应用组装根 |
| MQ 消费者 | 4 | 超时流转、自动结算、资金事件、延迟双删 |
| 兜底 job | 3 | 超时扫描、结算扫描、本地消息重发 |
| 定时维护 | 4 | 缓存校验、布隆重建、信用校准、资金对账 |
| 共享内核 | 5 | Money / ErrorCode / Result / BizException / 雪花 ID |

**核心思想**：worker 是独立 JVM，复用 application 层用例，只负责“触发”和“消费”。

---

## 0.1 类名中文对照

| 类名 | 中文名 | 分组 |
| --- | --- | --- |
| `WorkerApplication` | worker 启动类 | 装配 |
| `WorkerBeans` | worker Bean 配置 | 装配 |
| `CampusDashApplication` | 应用启动类（组装根） | 装配 |
| `TimeoutTransferConsumer` | 超时流转消费者 | 消费 |
| `AutoSettleConsumer` | 自动结算消费者 | 消费 |
| `FundEventConsumer` | 资金事件消费者 | 消费 |
| `CacheEvictConsumer` | 延迟删缓存消费者 | 消费 |
| `TimeoutScanJob` | 超时扫描兜底 | 兜底 |
| `AutoSettleScanJob` | 自动结算扫描兜底 | 兜底 |
| `LocalMessageRetryJob` | 本地消息重发 | 兜底 |
| `CacheConsistencyCheckScheduler` | 缓存一致性调度 | 维护 |
| `BloomRebuildJob` | 布隆重建 | 维护 |
| `CreditCalibrationJob` | 信用分校准 | 维护 |
| `ReconciliationJob` | 三层对账 | 维护 |
| `Money` | 金额值对象 | 共享 |
| `ErrorCode` | 业务错误码 | 共享 |
| `Result` | 统一返回结构 | 共享 |
| `BizException` | 业务异常 | 共享 |
| `SnowflakeIdGenerator` | 雪花 ID 生成器 | 共享 |

---

## 1. 进程装配（3）

### 1.1 `WorkerApplication` · worker 启动类（class）

**功能**：独立后台进程的组装根。

~~~java
@SpringBootApplication
@ComponentScan(basePackages = {
        "com.campusdash.worker",
        "com.campusdash.application",
        "com.campusdash.infrastructure"
})
@EnableScheduling
@EnableTransactionManagement
public class WorkerApplication {
    public static void main(String[] args) { SpringApplication.run(WorkerApplication.class, args); }
}
~~~

**要点**：

- 扫描范围**不含** `presentation`——worker 没有 Controller；
- `@EnableScheduling` 是 `@Scheduled` job 生效的前提；
- 独立进程的价值：对账 job 的 GC 抖动不会打到在线抢单的 P99。

### 1.2 `WorkerBeans` · worker Bean 配置（class）

**功能**：给 worker 一个独立的雪花 `workerId`。

~~~java
@Bean
public SnowflakeIdGenerator snowflakeIdGenerator(@Value(worker-id 配置, 默认 2) long workerId) {
    return new SnowflakeIdGenerator(workerId);
}
~~~

**要点**：在线服务默认 1、worker 默认 2，避免两个进程同一毫秒生成相同 ID。

### 1.3 `CampusDashApplication` · 应用启动类（class）

**功能**：在线服务的组装根，唯一知道所有层的地方。

~~~java
@SpringBootApplication
@EnableTransactionManagement
public class CampusDashApplication {
    public static void main(String[] args) { SpringApplication.run(CampusDashApplication.class, args); }

    @Bean
    public SnowflakeIdGenerator snowflakeIdGenerator(@Value(worker-id 配置, 默认 1) long workerId) {
        return new SnowflakeIdGenerator(workerId);
    }
}
~~~

**要点**：领域层不认识 Spring，Bean 只在组装根创建——ArchUnit 的规则靠这个维持。

---

## 2. MQ 消费者（4）

### 2.1 `TimeoutTransferConsumer` · 超时流转消费者（class）

**字段**：`ClientServiceProvider`、`ClientConfiguration`、`TimeoutTransferUseCase`、`topic`、`group`（默认 `dash-timeout-consumer`）

**方法**：`@PostConstruct start()`、`@PreDestroy stop()`、`extractLong(body, field)`

**关键源码（ACK 策略）**

~~~java
var outcome = timeoutTransferUseCase.handleTimeout(errandId, round);
log.debug("超时消息处理完成 errandId={} round={} outcome={}", errandId, round, outcome);
// TRANSFERRED / REVERTED / SKIPPED 都 ACK：
// SKIPPED 是幂等生效的正常结果，不 ACK 会导致消息无限重投
return ConsumeResult.SUCCESS;
~~~

**要点**：只有真正的系统异常才 `FAILURE` 重试；业务失效（`SKIPPED`）必须 ACK。

### 2.2 `AutoSettleConsumer` · 自动结算消费者（class）

**字段**：provider、configuration、`SettleErrandUseCase`、topic（`errand-auto-settle`）、group（`dash-autosettle-consumer`）

**方法**：`start()` / `stop()`

**要点**：消费后调 `settleUseCase.settle(errandId, Errand.SYSTEM_OPERATOR)`；幂等完全交给结算的三道闸门。

### 2.3 `FundEventConsumer` · 资金事件消费者（class）

**字段**：provider、configuration、`NotificationRepository`、`SnowflakeIdGenerator`、topic（`errand-fund-event`）、group（`dash-fund-event-consumer`）

**方法**：`start()` / `stop()` / `extractLong`

**关键源码（两种视角 + 幂等键）**

~~~java
String pubContent = switch (tag) {
    case "SETTLED" -> String.format("任务已完成结算，跑腿获得 %d 分", amount);
    case "REFUNDED" -> String.format("任务已退款，%d 分已退回你的账户", amount);
    case "ARBITRATED" -> String.format("争议已裁决，涉及金额 %d 分", amount);
    default -> "任务 " + errandId + " 资金状态变动";
};
notificationRepository.insertIfAbsent(idGenerator.nextId(),
        msgKey + ":pub", publisherId, errandId, tag, pubContent);

if (runnerId > 0 && ("SETTLED".equals(tag) || "ARBITRATED".equals(tag))) {
    notificationRepository.insertIfAbsent(idGenerator.nextId(),
            msgKey + ":runner", runnerId, errandId, tag, runnerContent);
}
~~~

**要点**：

- `msgKey + ":pub" / ":runner"` 让同一事件对发单人和跑腿各生成一条；
- 幂等靠 `notification.uk_msg_user(msg_key, user_id)`；
- 失败返回 `FAILURE` 让 MQ 重试（落库是业务事实，不能丢）。

### 2.4 `CacheEvictConsumer` · 延迟删缓存消费者（class）

**字段**：provider、configuration、`ErrandCachePort`、group（`dash-cache-evict-consumer`）

**方法**：`start()` / `stop()`

**要点**：订阅 `CacheEvictDelayPort.TOPIC_CACHE_EVICT`，收到后执行第二次 `cache.evict`；删除本身幂等。

---

## 3. 兜底 job（3）

### 3.1 `TimeoutScanJob` · 超时扫描兜底（class）

**字段**：`ErrandRepository`、`TimeoutTransferUseCase`、`graceSeconds`（默认 2）

**方法**：`@Scheduled(fixedDelay = 5000) scan()`

**关键源码**

~~~java
long timeout = timeoutTransferUseCase.confirmTimeoutSeconds() + graceSeconds;
List<Errand> candidates = errandRepository.findConfirmTimeout(timeout, BATCH_LIMIT);
for (Errand errand : candidates) {
    switch (timeoutTransferUseCase.handleTimeout(errand.id(), errand.round())) {
        case TRANSFERRED -> transferred++;
        case REVERTED -> reverted++;
        case SKIPPED -> skipped++;
    }
}
~~~

**要点**：调用与 MQ 消费者**同一个** `handleTimeout`；宽限期让主通道先处理。

### 3.2 `AutoSettleScanJob` · 自动结算扫描兜底（class）

**字段**：`ErrandRepository`、`SettleErrandUseCase`、`autoSettleSeconds`（默认 86400）

**方法**：`@Scheduled(fixedDelay = 5000) scan()`

~~~java
List<Errand> due = errandRepository.findAutoSettleDue(autoSettleSeconds, 200);
for (Errand e : due) settleUseCase.settle(e.id(), Errand.SYSTEM_OPERATOR);
~~~

**要点**：与 `AutoSettleConsumer` 同一个用例，靠三道闸门防重复打钱。

### 3.3 `LocalMessageRetryJob` · 本地消息重发（class）

**字段**：`LocalMessageRepository`、`DelayMessagePort`；常量 `BATCH_LIMIT = 100`、`MAX_RETRY = 6`

**方法**：`@Scheduled(fixedDelay = 10000) retry()`

~~~java
for (var msg : pending) {
    try {
        delayMessagePort.send(msg.topic(), msg.msgKey(), msg.payload(), msg.deliverAt());
        localMessageRepository.markSent(msg.msgKey());
    } catch (RuntimeException e) {
        localMessageRepository.markRetry(msg.msgKey(), MAX_RETRY);
    }
}
~~~

**要点**：兜的是“业务已提交但消息没发出去”；失败走指数退避，超限转 `DEAD`。

---

## 4. 定时维护（4）

### 4.1 `CacheConsistencyCheckScheduler` · 缓存一致性调度（class）

**字段**：`CacheConsistencyCheckUseCase`
**方法**：`@Scheduled(fixedDelay = 300000) check()` → `checkUseCase.runOnce()`

**要点**：只是调度壳，业务在 application 层，集成测试可直接调 `runOnce()`。

### 4.2 `BloomRebuildJob` · 布隆重建（class）

**字段**：`JdbcTemplate`、`ErrandCachePort`、`RedissonClient`、`boolean bloomEnabled`
**方法**：`@EventListener(ApplicationReadyEvent) rebuildOnStartup()`

**关键源码**

~~~java
RLock lock = redisson.getLock("bloom:rebuild:lock");
if (!lock.tryLock(5, 300, TimeUnit.SECONDS)) { log.info("其他实例正在重建，跳过"); return; }
List<Long> ids = jdbc.queryForList("SELECT id FROM errand", Long.class);
for (Long id : ids) cache.registerExisting(id);
~~~

**要点**：启动时把存量 id 灌进布隆；多实例用 Redisson 锁互斥；失败不阻塞启动。

### 4.3 `CreditCalibrationJob` · 信用分校准（class）

**字段**：`CreditCalibrationUseCase`、`batchSize`（默认 1000）
**方法**：`@Scheduled(cron = "0 0 3 * * ?") calibrate()`

**要点**：只按最近 30 天的事件重算，把窗口外的历史贡献移除。

### 4.4 `ReconciliationJob` · 三层对账（class）

**字段**：`ReconRepository`
**方法**：`@Scheduled(cron = "0 0 2 * * ?") runDaily()`、`int run(LocalDate date)`

**关键源码**

~~~java
long delta = reconRepository.debitMinusCredit();                 // L1
if (delta != 0) recordDiff(date, "DEBIT_CREDIT", "GLOBAL", 0L, delta, ...);

for (var d : reconRepository.findSnapshotDiffs()) {              // L2
    recordDiff(date, "SNAPSHOT", String.valueOf(d.accountId()), ...);
}
for (var d : reconRepository.findEscrowClosureDiffs()) {         // L3
    recordDiff(date, "ESCROW_CLOSURE", String.valueOf(d.errandId()), ...);
}
~~~

**要点**：`recon_diff` 正常必须为空；`run(date)` 公开是为了集成测试与压测手动触发。

---

## 5. 共享内核回顾（5）

### 5.1 `Money` · 金额值对象（record）

组件：`long cents`；常量 `ZERO`
构造：紧凑构造器校验 `cents >= 0`
方法：`ofCents`、`ofYuan`、`plus`、`minus`、`isZero`、`greaterThan`、`compareTo`、`toString`

**要点**：long 存分，不用 double/BigDecimal；运算返回新对象。

### 5.2 `ErrorCode` · 业务错误码（enum）

常量：约 29 个（OK / SLOT_FULL / INSUFFICIENT_BALANCE / …）
字段：`private final String message`
方法：`message()`；`name()` 继承自 Enum

**要点**：`name()` 给机器（接口 code），`message()` 给人（提示文案）。

### 5.3 `Result` · 统一返回结构（record）

组件：`String code, String message, T data`
方法：`ok(data)`、`fail(code)`、`fail(code, data)`、`success()`

**要点**：`success()` 里常量在前（`ErrorCode.OK.name().equals(code)`），避免 `code` 为 null 时 NPE。

### 5.4 `BizException` · 业务异常（class）

字段：`private final ErrorCode code`
构造：`BizException(code)`、`BizException(code, detail)`
方法：`code()`

**要点**：继承 `RuntimeException` 以配合事务默认回滚；消息拼上 detail 便于排查。

### 5.5 `SnowflakeIdGenerator` · 雪花 ID（class）

字段：`workerId`、`sequence`、`lastTimestamp`；常量 EPOCH / 位宽 / 掩码
构造：`SnowflakeIdGenerator(long workerId)`（校验 0..1023）
方法：`synchronized long nextId()`、`waitNextMillis`

**要点**：41+10+12 位；时钟回拨直接抛异常；单机 4096/ms。

---

## 6. 横向模式

| 模式 | 例子 | 为什么 |
| --- | --- | --- |
| **消费者只做协议解析** | 4 个 Consumer | 业务都在 UseCase，消费者只提取字段 + ACK |
| **ACK 策略分两类** | 落库 FAILURE、推送 SUCCESS | 事实必须重试，体验可以丢 |
| **兜底与主通道共用用例** | ScanJob ↔ Consumer | 幂等只写一份 |
| **job 只调度** | CacheConsistencyCheckScheduler | 业务放 application，便于测试 |
| **条件装配** | `@ConditionalOnProperty` | MQ/WS 可整体关闭 |
| **进程隔离** | worker vs app | 资源、部署、扩缩容互不影响 |

## 7. 自测

- [ ] worker 为什么要把 `presentation` 排除在 `@ComponentScan` 之外？
- [ ] 为什么 `TimeoutTransferConsumer` 对 `SKIPPED` 也要返回 `SUCCESS`？
- [ ] `FundEventConsumer` 的 `msgKey + ":pub"` 有什么作用？
- [ ] `LocalMessageRetryJob` 和 `TimeoutScanJob` 分别兜的是什么失败？
- [ ] `BloomRebuildJob` 为什么需要 Redisson 锁？
- [ ] 在线服务与 worker 的 `workerId` 为什么必须不同？
