# 优惠券抢单 JMeter 压测报告

日期：2026-10-30
目标：15 号优惠券，库存 10，验证 200/500 并发下的库存安全与连接能力。

## 环境

- JMeter 5.6.3，非 GUI 模式
- 入口：`http://localhost:8088` → dash-web(nginx) → dash-app(Tomcat 8080)
- Tomcat：`threads.max=200`，`accept-count=500`
- 优惠券：type=15，初始 stock=10，voucher_record 初始为 0
- 500 并发：500 个 stu token，CSV 每线程一行，`SyncTimer groupSize=500`，ramp-up=1s，loop=1

## 200 并发结果

用户已执行 200 人抢 10 单；项目目录没有保存 `.jtl` 或 HTML 报告，以下为压测后数据库核对：

```text
voucher_record(type=15) = 10
distinct users             = 10
voucher_stock.stock        = 0
voucher:pending:count:15   = 0
```

结论：10 个成功，没有超卖；但原 JMX 的 CSV 设置了 `ignoreFirstLine=false`、`recycle=true`，首行表头会被当成 token，后续复测前必须修正。500 计划已修正。

## 500 并发结果

原始结果：

- JMeter samples：500
- HTTP 200：414
- 连接异常：86
- 失败率：17.2%
- 延迟：avg 555.1 ms，p50 619 ms，p90 803 ms，p95 828 ms，p99 850 ms，max 862 ms

失败全部是：

```text
org.apache.http.conn.HttpHostConnectException
```

不是业务返回 500，也不是优惠券超卖。

数据库核对：

```text
voucher_record(type=15) = 10
distinct users             = 10
voucher_stock.stock        = 0
voucher:pending:count:15   = 0
```

## 结论

1. 业务层并发安全：500 个请求中最终只有 10 个成功，库存 0，pending 0，无超卖。
2. 86 个失败发生在建立连接阶段，请求没有进入业务接口；这是入口连接能力边界，不是领取逻辑错误。
3. 当前入口是 nginx + Docker 端口映射，500 个线程同时建立新连接时，部分连接被拒绝。下一步应优先排查并调整：
   - nginx `listen ... backlog` 和 `worker_connections`；
   - Docker Desktop/WSL 端口代理的连接队列；
   - Tomcat `threads.max`、`accept-count` 与连接超时；
   - 多机 JMeter 或直接把压测客户端放进容器网络，排除 Docker 端口映射瓶颈。
4. 当前 414 个成功进入业务的请求中，响应时间 P95 约 828 ms；这个数据包含了连接排队，不代表纯业务耗时。

## 产物

- JMX：`优惠券抢单-500.jmx`
- JTL：`result-500.jtl`
- HTML：`report-500/index.html`
- token CSV：`jmeter-stu-tokens-500.csv`
