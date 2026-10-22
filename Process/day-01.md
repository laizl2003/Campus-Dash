# day-01 · 环境搭建 + 项目启动 + 一键脚本

> 返回目录：[Process.md](../Process.md)

## 1. 今日完成

- 通读 README 与配置，确认环境与端口占用
- 补齐环境：JDK 21、Docker Desktop（含 WSL2）、前端依赖
- 启动全链路并通过验证：中间件 4 容器 + 后端 8080 + 前端 5173
- 完成接口验证：`/api/health` 返回 UP、登录换 token、发布任务、任务广场、钱包
- 编写一键脚本 `dev.ps1`（启动 / 停止 / 重启 / 状态 / 重置 MQ）
- 更新后端 README：新增「日常启动与关闭」「改了代码之后」

## 2. 一键启动与停止（`dev.ps1`）

脚本位于仓库根目录，在仓库根目录执行：

```powershell
.\dev.ps1              # 一键启动（默认 start）
.\dev.ps1 start        # 中间件 + 后端 + 前端，等到健康检查通过后打印访问地址
.\dev.ps1 restart      # 停服务 → 重新编译 → 再启动（改完后端业务代码用这个）
.\dev.ps1 stop         # 一键停止（含 Ctrl+C 没杀干净的残留进程）
.\dev.ps1 status       # 查看容器状态与端口健康
.\dev.ps1 reset-mq     # 删除并重建 4 个 topic
```

`start` 内部顺序：检查 Docker 引擎 / JDK 21 / npm → `docker compose up -d` 并等 4 个容器 healthy → 幂等初始化 topic 与消费组 → 在独立窗口启动后端(8080)与前端(5173) → 轮询 `/api/health` 直到 UP。已在运行的组件会自动跳过。

`start` 还会做一致性检查：若某模块源码比本地仓库的 jar 新，会提示改用 `restart`，避免「代码改了但跑的是旧代码」。

## 3. 访问地址

| 入口 | 地址 |
| --- | --- |
| 前端页面 | http://localhost:5173 |
| 后端健康检查 | http://localhost:8080/api/health |
| 演示身份 | 发单人 `1001`、跑腿 `2001` / `2002`、仲裁员 `9001` |
| MySQL | `127.0.0.1:3307`（库 `campus_dash`，root / `dash123456`） |
| Redis | `127.0.0.1:6380`（无密码） |
| RocketMQ | gRPC proxy `127.0.0.1:8081`，namesrv `127.0.0.1:9876` |

## 4. 本机环境踩的坑

| 问题 | 表现 | 处理 |
| --- | --- | --- |
| JDK 版本不对 | 默认 `JAVA_HOME` 指向 jdk11，项目要求 21 | 运行时显式 `JAVA_HOME=D:\develop\jdk21`，未改系统变量 |
| Docker 引擎起不来 | 日志报 `Virtual Machine Platform not enabled` | 启用 WSL2 + 虚拟机平台后重启；Windows 家庭版无 Hyper-V，只能走 WSL2 |
| 镜像拉取被拒 | `registry-1.docker.io` 主动拒绝连接 | 改用 `docker.m.daocloud.io` 拉取后重新打 tag |
| 前端依赖装不上 | `package-lock.json` 锁定内网源 `registry.anpm.alibaba-inc.com` 超时 | `npm install --registry=https://registry.npmmirror.com/ --replace-registry-host=always` |
| 后端 MQ 启动失败 | `Failed to fetch topics after 3 attempts` / `ConnectTimeoutException: 172.18.0.5:8081` | broker 通告了容器内网 IP，宿主机不可达；新增 `docker/docker-compose.override.yaml` 显式 `brokerIP1=127.0.0.1`（原 `BROKER_IP1` 环境变量在该镜像里根本没被读取） |
| PowerShell 脚本语法报错 | 中文注释被截断，`Unexpected token` | PowerShell 5.1 按 GBK 解码无 BOM 的 UTF-8 脚本；`dev.ps1` 必须存为 **UTF-8 with BOM** |

## 5. 关键认知

- **`mvn -pl dash-bootstrap spring-boot:run` 只编译 dash-bootstrap 自己**，其余模块从本地仓库 `D:\develop\maven_repo` 取已安装的 jar（实测 `dependency:build-classpath` 路径为仓库 jar，不是各模块 `target\classes`）。所以**改了 domain / application / infrastructure / presentation / shared 后必须先 `mvn install -DskipTests`**，否则跑的是旧代码。
- 反过来，**没改代码就不用手动编译**：`spring-boot:run` 会跑 compile 但跳过（`Nothing to compile`），实测约 4 秒，基本是 Maven 启动开销。
- 无改动时全量 `mvn install -DskipTests` 仅 **12 秒**（跳过编译，只剩打 jar / repackage / install）。所以改完代码直接 `.\dev.ps1 restart` 成本很低。
- **`docker compose down` 会丢 RocketMQ 的 topic**：broker 没有挂数据卷，容器重建即清空存储，而 topic 被自动创建时类型是默认 `NORMAL`，会导致延迟消息失效。所以日常用 `docker compose stop`，`down` 之后必须重跑初始化（或 `.\dev.ps1 reset-mq`）。
- 本机另有一个原生 MySQL80 占着 **3306**，与本项目无关；项目用 3307。

## 6. 次日（day-2）待办

- [ ] 逐层走通抢单链路：Controller → Sentinel 限流 → Redis Lua → MySQL CAS → 双唯一索引
- [ ] 学习资金链路：复式记账、三道幂等闸门、三层对账
- [ ] 给 `Notification` 增加一个 MyBatis 适配器做对照实验（保留原 JdbcTemplate 实现，用配置切换）
- [ ] 启动 worker 进程，验证超时流转与自动结算
