# P6/P7 完成报告

时间：2026-08-22

## 结论

- P6 已完成：分片键贯通、旧库迁移验证、ShardingSphere JDBC 依赖接入、class-based 分片算法与规则工厂可运行验证、游标分页、JWT refresh token、信用分校准均纳入全量测试。
- P7 已完成：S2 阶梯压测拉满 50/100/200/400/800 五档，完成 800 并发调参复测；抢单热点限流已从本地固定窗口替换为 Sentinel 热点参数限流，并完成 1000 并发真实 HTTP 链路验证。
- 最终验证：`mvn test` 全量通过，9 个模块全部 SUCCESS，累计 93 个测试。

## P6 验证

### 分片键与迁移

已执行：

```bash
docker exec -i dash-mysql mysql -uroot -pdash123456 campus_dash < docker/migrate-p6.sql
```

验证结果：

| 项 | 结果 |
|---|---|
| `grab_record.campus_id` | `NOT NULL`，空值 0 |
| `escrow_order.campus_id` | `NOT NULL`，空值 0 |
| `errand_status_log.campus_id` | `NOT NULL`，空值 0 |
| `grab_record` 索引 | `idx_campus_errand(campus_id, errand_id)` 已存在 |
| `escrow_order` 索引 | `idx_campus_errand(campus_id, errand_id)` 已存在 |
| `errand_status_log` 索引 | `idx_campus_errand_log(campus_id, errand_id, id)` 已存在 |

### ShardingSphere runtime 接入点

- 新增 `CampusDashShardingRuleFactory`，产出 ShardingSphere `ShardingRuleConfiguration`。
- 新增 `CampusDashModuloShardingAlgorithm`，实现 ShardingSphere `StandardShardingAlgorithm`。
- 任务链路表 `errand/grab_record/escrow_order/errand_status_log` 绑定表组按 `campus_id` 路由。
- 用户维度表 `wallet_account/wallet_ledger/credit_score/credit_event` 按 `user_id` 路由。
- `CampusDashShardingRuleFactoryTest` 使用 ShardingSphere 内置 `ClassBasedShardingAlgorithm` 实例化并调用本项目算法，验证 `campus_id=7` 路由到 `campus_dash_3`。

### P6 边界

本期保留 JdbcTemplate 仓储，不强制替换 MyBatis-Plus。原因是抢单、托管、结算的核心路径依赖手写 CAS SQL 与 affectedRows 语义，当前已通过分片键 SQL 约束测试防止按 `errand_id` 单键广播；后续如果切换 MyBatis-Plus，只应作为仓储实现替换，不应改变 domain/application 契约。

## P7 S2 阶梯压测

环境口径：同一台 Mac 上运行 app、MySQL、Redis、RocketMQ 与发压客户端；worker 在主报告轮次中停止，避免历史超时/结算消息干扰查询压测。数字仅作同机容量基线，不外推为生产容量承诺。

命令：

```bash
java -cp "dash-bench/target/classes:$(cat /tmp/campus-dash-bench.cp)" \
  com.campusdash.bench.RampLoadClient http://127.0.0.1:8080 50,100,200,400,800 60
```

默认配置主报告：

| run_id | 并发 | 时长 | 请求数 | OK | Errors | QPS | P50 | P95 | P99 | Max |
|---|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|
| 20260822-005714 | 50 | 60s | 398363 | 398363 | 0 | 6639 | 6ms | 13ms | 18ms | 77ms |
| 20260822-005814 | 100 | 60s | 409648 | 409648 | 0 | 6826 | 13ms | 24ms | 31ms | 244ms |
| 20260822-005914 | 200 | 60s | 396229 | 396229 | 0 | 6598 | 27ms | 54ms | 77ms | 276ms |
| 20260822-010014 | 400 | 60s | 387798 | 387798 | 0 | 6455 | 55ms | 113ms | 151ms | 396ms |
| 20260822-010114 | 800 | 60s | 381188 | 381042 | 146 | 6346 | 103ms | 255ms | 390ms | 701ms |

结论：默认配置稳定容量线到 400 并发；800 并发是边界/超压档，出现 146 个 IOException。

## P7 调参复测

调参项：

- `server.tomcat.threads.max=400`
- `server.tomcat.accept-count=1000`
- `spring.datasource.hikari.maximum-pool-size=40`
- `spring.data.redis.lettuce.pool.max-active=128`
- `dash.auth.allow-header-identity=true`
- `dash.grab.limit.type=sentinel`
- `dash.grab.limit.per-second=100`

800 并发复测：

| run_id | 并发 | 时长 | 请求数 | OK | Errors | QPS | P50 | P95 | P99 | Max |
|---|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|
| 20260822-011733 | 800 | 60s | 313800 | 313800 | 0 | 5205 | 134ms | 330ms | 472ms | 1006ms |

调参后 800 并发 0 错误，但吞吐从默认 6346 QPS 降为 5205 QPS，P99 从 390ms 升至 472ms。该档位说明连接错误可以通过队列/线程池/连接池承接住，但延迟代价明显。

## P7 Sentinel 热点限流验证

命令：

```bash
java -cp "dash-bench/target/classes:$(cat /tmp/campus-dash-bench.cp)" \
  com.campusdash.bench.SpikeLoadClient http://127.0.0.1:8080 1000 1
```

结果：

| run_id | 并发 | 名额 | success | slotFull | rateLimited | conflict | errors | oversold | P99 |
|---|---:|---:|---:|---:|---:|---:|---:|---:|---:|
| 20260822-011023 | 1000 | 1 | 1 | 99 | 900 | 0 | 0 | 0 | 340ms |

结论：Sentinel 热点参数限流在 Redis Lua 与 DB CAS 前生效，热点任务快速失败，真实 HTTP 链路仍保持零超卖。

## 观测

- MySQL `Slow_queries=0`。
- 压测后 MySQL `Threads_connected=1`，`Threads_running=2`。
- 本次未生成 async-profiler 火焰图；使用 S2 分档指标、MySQL status、JVM/容器观测替代。没有火焰图的结论不写成 CPU 火焰图证据。

## 最终测试

```bash
~/.m2/wrapper/dists/apache-maven-3.9.9-bin/4nf9hui3q3djbarqar9g711ggc/apache-maven-3.9.9/bin/mvn test
```

最终结果：BUILD SUCCESS，9 个模块全部 SUCCESS，累计 93 个测试。

## 追加复验

时间：2026-08-22 01:51-01:57。

复验口径：OrbStack 中 MySQL / Redis / RocketMQ 均为 healthy；应用以 P7 调参配置启动，
并额外设置 `dash.credit.max-ongoing=10000` 避免多轮压测复用固定 runnerId 触发在途单上限。
该额外配置只用于压测复验，生产默认仍为 5。

### 全量测试复验

```bash
~/.m2/wrapper/dists/apache-maven-3.9.9-bin/4nf9hui3q3djbarqar9g711ggc/apache-maven-3.9.9/bin/mvn test
```

结果：BUILD SUCCESS，9 个模块全部 SUCCESS，累计 93 个测试。

### P6 数据库复验

| 项 | 结果 |
|---|---|
| `grab_record.campus_id` | `NOT NULL`，空值 0，`idx_campus_errand(campus_id, errand_id)` 存在 |
| `escrow_order.campus_id` | `NOT NULL`，空值 0，`idx_campus_errand(campus_id, errand_id)` 存在 |
| `errand_status_log.campus_id` | `NOT NULL`，空值 0，`idx_campus_errand_log(campus_id, errand_id, id)` 存在 |

### P7 S2 短档复压

命令：

```bash
java -cp "dash-bench/target/classes:$(cat /tmp/campus-dash-bench.cp)" \
  com.campusdash.bench.RampLoadClient http://127.0.0.1:8080 50,100,200,400,800 15
```

| run_id | 并发 | 时长 | 请求数 | OK | Errors | QPS | P50 | P95 | P99 | Max |
|---|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|
| 20260822-015319 | 50 | 15s | 70972 | 70972 | 0 | 4730 | 9ms | 20ms | 30ms | 310ms |
| 20260822-015334 | 100 | 15s | 95278 | 95278 | 0 | 6305 | 14ms | 24ms | 33ms | 249ms |
| 20260822-015349 | 200 | 15s | 96488 | 96488 | 0 | 6385 | 28ms | 52ms | 71ms | 636ms |
| 20260822-015404 | 400 | 15s | 91408 | 91408 | 0 | 6067 | 58ms | 120ms | 253ms | 384ms |
| 20260822-015419 | 800 | 15s | 87060 | 87060 | 0 | 5770 | 117ms | 303ms | 393ms | 706ms |

### P7 Sentinel spike 复验

命令：

```bash
java -cp "dash-bench/target/classes:$(cat /tmp/campus-dash-bench.cp)" \
  com.campusdash.bench.SpikeLoadClient http://127.0.0.1:8080 1000 1
```

| run_id | 并发 | 名额 | success | slotFull | rateLimited | conflict | errors | oversold | P99 |
|---|---:|---:|---:|---:|---:|---:|---:|---:|---:|
| 20260822-015621 | 1000 | 1 | 1 | 99 | 900 | 0 | 0 | 0 | 313ms |

数据库侧 `verify_run.sql` 结果：超卖检测 PASS；同一用户重复抢空集 PASS；状态自洽 PASS；
本轮资金借贷平衡 PASS。MySQL 观测：`Slow_queries=0`，`Threads_connected=10`，`Threads_running=2`。
