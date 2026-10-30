# 学习笔记：Redis Pub/Sub 与 Docker

> 记录 Redis Pub/Sub 基础、Docker 容器基础，以及它们在 CampusDash 项目中的实际用法。

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
