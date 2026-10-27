# 全栈本地部署（nginx + app + worker + 中间件）

一条命令把这套东西跑起来：

```bash
cd campus-dash-backend/docker
docker compose -f docker-compose.yaml -f docker-compose.full.yaml up -d --build
```

打开 <http://localhost:8088>。

## 和"只起中间件"的区别

| | 默认方式（开发） | 全栈方式（部署） |
| --- | --- | --- |
| 命令 | `docker compose up -d` | 上面那条带 `-f` 的 |
| 应用跑在哪 | 宿主机 `mvn spring-boot:run` | 容器 `dash-app` / `dash-worker` |
| 前端怎么访问 | vite 5173，代理到 8080 | nginx 8088，同源反代 |
| MySQL 地址 | `127.0.0.1:3307` | `mysql:3306`（容器网络） |
| Redis 地址 | `127.0.0.1:6380` | `redis:6379` |
| MQ 地址 | `127.0.0.1:8081` | `rmqbroker:8081` |
| broker 注册地址 | 靠 `docker-compose.override.yaml` 固定成 127.0.0.1 | 容器内网 IP（本文件覆盖了那条 command） |

两种方式可以并存：不带 `-f` 就是开发方式，带 `-f` 才是全栈。

## 请求怎么走

```
浏览器 ── http://localhost:8088
   │
   ├── /            → nginx 静态托管 前端 dist/
   ├── /api/**      → proxy_pass app:8080 → Spring Boot → MySQL / Redis / RocketMQ
   └── /ws          → Upgrade 透传    → Spring Boot WebSocket
```

因为前端和 API 同源，`dash.web.cors-origins` 在这条链路里不再起作用——
CORS 只在"前端 5173、后端 8080"的开发方式下才需要。

## 前置条件

镜像里不跑构建，用的是宿主机产物（本地迭代快，也不用在容器里重下依赖）：

```bash
# 1. 后端 fat jar
cd campus-dash-backend
mvn -pl dash-bootstrap,dash-worker -am package -DskipTests

# 2. 前端静态产物
cd ../campus-dash-frontend
npm run build
```

然后才是 `docker compose ... up -d --build`。

## 配置怎么覆盖（不改 application.yaml）

Spring Boot 的 relaxed binding 会把环境变量映射到配置项：

| 环境变量 | 对应配置 | 全栈里的值 |
| --- | --- | --- |
| `SPRING_DATASOURCE_URL` | `spring.datasource.url` | `jdbc:mysql://mysql:3306/campus_dash?...` |
| `SPRING_DATA_REDIS_HOST` | `spring.data.redis.host` | `redis` |
| `SPRING_DATA_REDIS_PORT` | `spring.data.redis.port` | `6379` |
| `DASH_MQ_ENDPOINTS` | `dash.mq.endpoints` | `rmqbroker:8081` |
| `DASH_WORKER_ID` | `dash.worker-id` | app=1 / worker=2 |
| `DASH_WS_ENABLED` | `dash.ws.enabled` | worker=false |
| `DASH_AUTH_MODE` | `dash.auth.mode` | 默认 Session；设 `jwt` 可验证双 token 续期 |

## 启动顺序（compose 的依赖链）

```
mysql/redis ──healthy──┐
rmqnamesrv ───────────┤
rmqbroker ──healthy──→ mq-init ──completed──→ app ──healthy──→ web
                                              └─→ worker
```

`mq-init` 是一次性容器：建 4 个 topic（3 个 DELAY + 1 个 TRANSACTION）和 5 个消费组，
跑完就退出。这条依赖不能省——**broker 没有挂数据卷，容器一重建，topic 和消费组就全没了**。

## 踩过的坑

1. **topic 丢了导致应用启动失败**
   `rmqbroker` 被重建后（换了 command），topic 元数据随可写层一起消失，
   app 启动时 `FundEventPushConsumer` 报
   `No topic route info in name server for the topic: errand-fund-event` 并退出。
   解决：把 `init-mq.sh` 改成 compose 里的 `mq-init` 服务，让 app/worker 等它跑完。

2. **compose 会吃掉 `$变量`**
   `sh -c` 里的 `$t` 会被 compose 当成插值，替换成空串（日志里是
   `The "t" variable is not set`），结果建出 `groupName=` 的空消费组。
   容器内 shell 变量必须写成 `$$t`。

3. **镜像拉不下来**
   本机 Docker 没有配 HTTPS 代理时，`docker.io` 直连会超时。
   已改用镜像站并打回标准标签：
   ```bash
   docker pull docker.m.daocloud.io/library/nginx:1.27-alpine
   docker tag  docker.m.daocloud.io/library/nginx:1.27-alpine nginx:1.27-alpine
   ```

4. **healthcheck 里没有 curl**
   `eclipse-temurin:21-jre` 镜像不带 curl/wget，用 bash 的 `/dev/tcp` 探端口：
   `bash -c 'exec 3<>/dev/tcp/127.0.0.1/8080'`。

5. **WebSocket 少一个头就不通**
   nginx 的 `/ws` 必须带 `Upgrade` 和 `Connection "upgrade"`，
   `proxy_read_timeout` 要大于前端 30 秒心跳，否则空闲连接会被 nginx 掐断。

## 常用命令

```bash
# 只看应用日志
docker logs -f dash-app
docker logs -f dash-worker

# 改了后端代码：重新打包 + 重建 app（worker 复用同一个镜像）
mvn -pl dash-bootstrap,dash-worker -am package -DskipTests
docker compose -f docker-compose.yaml -f docker-compose.full.yaml up -d --build app worker

# 改了前端：重新构建 + 重建 web
cd campus-dash-frontend && npm run build
cd ../campus-dash-backend/docker
docker compose -f docker-compose.yaml -f docker-compose.full.yaml up -d --build web

# 停止（保留 MySQL 数据卷）
docker compose -f docker-compose.yaml -f docker-compose.full.yaml down
```

## 验收清单

通过 8088 跑一遍真实链路（不经过任何 mock）：

- [x] `GET /api/health` → `{"code":"OK","data":{"status":"UP"}}`
- [x] 首页返回 `index.html`，`/assets/*.js` 200（223 KB）
- [x] `POST /api/auth/login` → token
- [x] 发布任务：发布者余额 99400 → 97900（1500 分进托管）
- [x] 抢单 → 确认 → 取货 → 送达 → 结算：状态到 `SETTLED`
- [x] 跑腿余额 +1425（1500 扣 5% 佣金 75）
- [x] `wallet_ledger` 三笔：-1500（托管户）、+1425（跑腿）、+75（佣金），**借贷合计 0**
- [x] `escrow_order` 状态 `RELEASED`
- [x] `local_message` 有 `autosettle:<id>`，`deliver_at` = 次日（24 小时后自动结算）
- [x] 资金事件经 MQ 落库：`notification` 两人各一条 `SETTLED`
- [x] `ws://localhost:8088/ws?token=...` 握手成功（状态 Open）
