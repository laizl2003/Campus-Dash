# CampusDash 校园跑腿抢单市场

DDD 洋葱架构的并发与一致性工程样本：发单人悬赏（资金托管）→ N 人同时抢、恰好一个成功
→ 限时未确认自动流转 → 送达确认后结算给跑腿与平台。业务链路短，但每个环节都踩在硬骨头上：
抢单正确性可验证（超卖就是错）、资金正确性可验证（借贷不平就是错）。

## 当前进度：P8（教程正文 + 面试题库）已完成

| 期 | 内容 | 状态 |
|---|---|---|
| P1 | 抢单最小闭环（Redis Lua + DB CAS 双保险，S1 尖峰 2000 并发零超卖） | ✅ |
| P2 | 超时流转（RocketMQ 定时消息 + 兜底扫描）、本地消息表、数据治理（run_id 分轮） | ✅ |
| P3 | 资金完整链路（结算/退款/仲裁/自动结算/三层对账）、事务消息、S4 压测、事务陷阱测试 | ✅ |
| P4 | 12 个 API 端点 + 简易登录 + availableActions + React 前端；ES/Canal 决策摘除 | ✅ |
| P5 | 缓存一致性（Cache Aside + Redisson 布隆 + 逻辑过期 + 校验 job）、信用分体系、WebSocket 实时推送、JWT 双实现 | ✅ |
| P6 | 分片键贯通、ShardingSphere 规则与 class-based 算法、游标分页、refresh token、信用分校准 | ✅ |
| P7 | S2 阶梯压测、800 并发调参复测、Sentinel 热点参数限流、P6/P7 完成报告 | ✅ |
| P8 | 12 篇 32 章教程正文、教程导读校准、面试题库与指标深挖 | ✅ |
| 2026-10-14 | 前端重设计、资金权限/事务与推送修复、初始化补齐 | ✅ 本轮功能验收通过，范围见文末 |

期次进度、功能清单、指标现状见 [docs/设计演进记录.md](docs/设计演进记录.md) 的「进度快照」；
决策与踩坑的完整记录同文件（八期进度 + 18 条踩坑档案）。
P8 完成报告见 [docs/P8-教程正文完成报告.md](docs/P8-教程正文完成报告.md)。
出版级教程后续完善计划见 [../校园跑腿后端-基础部分/教程文档撰写完成规划.md](../校园跑腿后端-基础部分/教程文档撰写完成规划.md)。

## 环境要求

- JDK 21、Maven 3.9+
- Docker（MySQL 8 + Redis 7 + RocketMQ 5.3.1，由 docker-compose 管理）
- Node 18+（仅前端 campus-dash-frontend 需要）


## 日常启动与关闭（本机 Windows 已验证）

前置：Docker Desktop 已启动（托盘鲸鱼图标变绿）；JDK 21、Maven 3.9+、Node 18+ 已安装。

### 启动

**一键启动/重启/停止（推荐）**

在仓库根目录执行：

```powershell
.\dev.ps1 start      # 也可以直接 .\dev.ps1（默认就是 start）
```

脚本按顺序做这些事：检查 Docker 引擎 / JDK 21 / npm → `docker compose up -d` 并等 4 个容器 healthy → 幂等初始化 topic 与消费组 → 在**独立窗口**分别启动后端(8080)与前端(5173) → 轮询 `/api/health` 直到 UP，最后打印访问地址。已经跑着的部分会自动跳过，不会重复启动。

`dev.ps1` 的其余用法：

```powershell
.\dev.ps1 restart    # 停服务 → 重新编译 → 再启动（改完后端业务代码用这个）
.\dev.ps1 status     # 看容器状态与端口健康
.\dev.ps1 stop       # 全部停止（含 Ctrl+C 没杀干净的残留进程）
.\dev.ps1 reset-mq   # 删除并重建 4 个 topic（broker 被重建、topic 类型不对时用）
```

两点说明：

- 脚本顶部的 `$JdkHome` 与 `$DockerBin` 是本机路径，换机器只改这两处；`$BackendPort` / `$FrontendPort` 也在同一段。
- 若提示「无法加载脚本，因为在此系统上禁止运行脚本」，是执行策略被限制了，改用 `powershell -ExecutionPolicy Bypass -File .\dev.ps1 start`。

**手动启动（与脚本等价的步骤，便于理解每一环）**

**1. 中间件**（MySQL 3307 / Redis 6380 / RocketMQ 8081）

```powershell
cd docker
docker compose up -d
docker compose ps        # 等 4 个容器都变成 (healthy)；首次拉镜像较慢
cd ..
```

初始化 topic 与消费组。`init-mq.sh` 需要 bash，PowerShell 下用等价的 `docker exec`：

```powershell
$inner = 'cd /home/rocketmq/rocketmq-*/bin && sh mqadmin'
foreach ($t in 'errand-confirm-timeout','errand-auto-settle','errand-cache-evict') {
  docker exec dash-rmqbroker sh -c "$inner updateTopic -n rmqnamesrv:9876 -c DefaultCluster -t $t -a +message.type=DELAY"
}
docker exec dash-rmqbroker sh -c "$inner updateTopic -n rmqnamesrv:9876 -c DefaultCluster -t errand-fund-event -a +message.type=TRANSACTION"
foreach ($g in 'dash-timeout-consumer','dash-autosettle-consumer','dash-fund-event-consumer','dash-cache-evict-consumer') {
  docker exec dash-rmqbroker sh -c "$inner updateSubGroup -n rmqnamesrv:9876 -c DefaultCluster -g $g"
}
```

> Git Bash / WSL 下可直接 `./init-mq.sh`。
> broker 容器被重建后（改过 compose 配置、或 `down` 后再 `up`）容器内存储会清空，需重跑上面这段。

**2. 后端**（8080）

```powershell
$env:JAVA_HOME='D:\develop\jdk21'   # 本机默认 JAVA_HOME 是 jdk11，必须显式切换，否则编译失败
mvn -pl dash-bootstrap spring-boot:run
```

**3. worker**（可选：超时流转兜底扫描 / 自动结算 / 资金事件落库 / 消息重发）

worker 是独立进程，需要先打出可执行 jar 再启动：

```powershell
mvn install -DskipTests        # 首次、或改过代码后需要；这一步只是为了产出 jar
java -jar dash-worker/target/dash-worker-1.0.0-SNAPSHOT.jar
```

**4. 前端**（5173）

```powershell
cd ../campus-dash-frontend
npm install
npm run dev
```

### 启动后访问

| 入口 | 地址 | 说明 |
|---|---|---|
| **前端页面** | **http://localhost:5173** | 选择演示身份登录：发单人 `1001`、跑腿 `2001` / `2002`、仲裁员 `9001` |
| 后端健康检查 | http://localhost:8080/api/health | 无需认证，返回 `{"code":"OK","data":{"status":"UP","project":"campus-dash"}}` |
| 后端 API | http://localhost:8080/api/errands | 需请求头 `Authorization: Bearer <token>` |

Vite 已把 `/api` 与 `/ws` 代理到 8080，前端不用处理跨域；后端也配了 CORS（`dash.web.cors-origins`）。

### 关闭

最省事的是根目录执行 `.\dev.ps1 stop`：按端口停掉后端/前端，再停中间件。下面是手工做法，便于理解每一步在做什么。

**1. 前台终端启动的（后端 / worker / 前端）**

在运行它的那个终端按 `Ctrl+C`。`spring-boot:run` 会 fork 子 JVM，正常情况下一次就能连带退出。

**2. 验证是否真的停了**

Windows 上 `Ctrl+C`（或直接关终端窗口）有时只杀掉 Maven 父进程，fork 出来的 `java.exe` 会继续占着 8080 —— 这就是 `Port 8080 was already in use` 最常见的原因。所以关完要验一下：

```powershell
try { (Invoke-WebRequest http://localhost:8080/api/health -TimeoutSec 3 -UseBasicParsing).StatusCode } catch { "后端已停止" }
try { (Invoke-WebRequest http://localhost:5173/ -TimeoutSec 3 -UseBasicParsing).StatusCode } catch { "前端已停止" }
```

**3. 还有残留就按端口兜底杀**

```powershell
Get-NetTCPConnection -LocalPort 8080 -State Listen -ErrorAction SilentlyContinue |
  ForEach-Object { "kill PID $($_.OwningProcess)"; Stop-Process -Id $_.OwningProcess -Force }
```

把 `8080` 换成 `5173` 即可停前端。

> 变量名别用 `$pid`：PowerShell 里 `$PID` 是只读自动变量，赋值会直接报错，用管道 `$_` 避开。
> 能 `Ctrl+C` 就别用 `-Force`：优雅退出会走 `@PreDestroy`（关闭 RocketMQ 消费者、连接池），强杀只是兜底手段。

**4. 中间件**

```powershell
cd docker
docker compose stop      # 停容器、保留数据；下次 docker compose start 恢复
docker compose ps        # 看状态
```

`docker compose down` 会删容器：MySQL 数据在具名卷 `dash-mysql-data` 里不会丢，但 **RocketMQ 的 topic 会丢**（broker 没挂卷），下次启动必须重跑上面的 topic 初始化。所以日常用 `stop` 就够。

Docker 引擎本身：托盘鲸鱼图标右键 → Quit Docker Desktop。

**5. 一键全停**

```powershell
foreach ($port in 8080, 5173) {
  Get-NetTCPConnection -LocalPort $port -State Listen -ErrorAction SilentlyContinue |
    ForEach-Object { Stop-Process -Id $_.OwningProcess -Force; "已停端口 $port (PID $($_.OwningProcess))" }
}
cd d:\Code\Campus_PaoTui\campus-dash-1\campus-dash-backend\docker
docker compose stop
```

### 后端 API 示例（不走前端）

演示认证是教学模式：给 `userId` 即可登录并返回 Bearer token。以下示例已在本机实测。

```powershell
# 0. 登录换 token
$token = (Invoke-RestMethod -Method Post -Uri http://localhost:8080/api/auth/login `
  -ContentType 'application/json' -Body '{"userId":1001}').data.token
$h = @{ Authorization = "Bearer $token" }

# 1. 健康检查（无需 token）
Invoke-RestMethod http://localhost:8080/api/health
# => {"code":"OK","message":"成功","data":{"project":"campus-dash","status":"UP"}}

# 2. 发布任务：资金同步托管（1001 初始可用余额 1000 元）
Invoke-RestMethod -Method Post -Uri http://localhost:8080/api/errands -Headers $h `
  -ContentType 'application/json' -Body '{"title":"帮我带份饭","rewardCents":1500,"slotTotal":1}'
# => {"code":"OK","data":{"errandId":"236438927295123456","status":"PUBLISHED","frozenCents":"1500"}}

# 3. 任务广场（campusId 默认 1；返回体含 availableActions，前端按钮完全由它驱动）
Invoke-RestMethod -Uri 'http://localhost:8080/api/errands?campusId=1' -Headers $h

# 4. 我的钱包
Invoke-RestMethod http://localhost:8080/api/wallet -Headers $h
# => {"code":"OK","data":{"availableCents":"98500","frozenCents":"0"}}
```

等价的 curl（Git Bash / WSL / macOS）：

```bash
TOKEN=$(curl -s -X POST localhost:8080/api/auth/login -H 'Content-Type: application/json' \
  -d '{"userId":1001}' | python3 -c "import sys,json;print(json.load(sys.stdin)['data']['token'])")

curl -s -X POST localhost:8080/api/errands -H "Authorization: Bearer $TOKEN" \
  -H 'Content-Type: application/json' \
  -d '{"title":"帮我带份饭","rewardCents":1500,"slotTotal":1}'
```

全生命周期端点（除健康检查与登录外均需 Bearer token）：

| 方法 | 路径 | 说明 |
|---|---|---|
| POST | `/api/auth/login` | 演示登录，换 token |
| GET | `/api/errands?campusId=1` | 任务广场（游标分页） |
| GET | `/api/errands/mine?role=PUBLISHED_BY_ME` | 我发布的；`GRABBED_BY_ME` 为我接的 |
| GET | `/api/errands/{id}` | 详情（含 `availableActions`） |
| GET | `/api/errands/{id}/timeline` | 状态流转时间线 |
| POST | `/api/errands/{id}/grab` | 抢单 |
| POST | `/api/errands/{id}/pickup` | 取货 |
| POST | `/api/errands/{id}/deliver` | 送达 |
| POST | `/api/errands/{id}/confirm` | 确认签收（结算给跑腿与平台） |
| POST | `/api/errands/{id}/settle` | 结算 |
| POST | `/api/errands/{id}/cancel` | 取消并退款 |
| POST | `/api/errands/{id}/dispute` | 发起争议 |
| POST | `/api/errands/{id}/arbitrate` | 仲裁裁决 |
| GET | `/api/wallet` · `/api/wallet/ledger` | 余额 · 记账流水 |

### 压测与数据治理

```bash
# S1 尖峰：2000 并发抢 1 名额（应用层与数据库侧双验证）
# S4 资金：500 任务并发结算 / 200 并发结算同一任务（SettleConcurrencyIT）
mvn test -Dtest=SettleConcurrencyIT

# 按轮次校验与清理（数据默认保留最近 10 轮，便于改动前后对比）
RUN_ID=<runId> bash bench/scripts/verify_run.sql   # 抢单侧五条不变式
bash bench/scripts/verify_fund.sql                  # 资金侧五条不变式
bash bench/scripts/cleanup.sh --keep-last 10
```

## 模块结构

```
campus-dash/                  # Maven 多模块（DDD 洋葱四层）
├── dash-shared/              # ErrorCode / Money（long 存分）/ Result / 雪花 ID
├── dash-domain/              # 领域层：聚合、状态机、ports（零框架依赖）
├── dash-application/         # 用例层：发布/抢单/确认/流转/结算/退款/仲裁/对账编排
├── dash-infrastructure/      # 适配器：JdbcTemplate、Redis Lua、RocketMQ、ShardingSphere 规则、Sentinel
├── dash-presentation/        # Controller 四拆分、AuthInterceptor、Jackson long→string
├── dash-worker/              # 独立进程：超时流转/自动结算/资金事件消费者、兜底扫描、对账 job
├── dash-bench/               # 压测客户端（SpikeLoadClient、RampLoadClient、SettleLoadClient、BenchRunRecorder）
├── dash-bootstrap/           # 应用装配 + 54 个集成测试
├── bench/                    # scripts（seed/verify/cleanup）与 reports（每期压测报告）
└── docs/设计演进记录.md       # 工程档案：分期决策 + 18 条踩坑
campus-dash-frontend/         # React + Vite 前端（与本项目平级）
```

## 核心机制速览

- **抢单五层防护**：Sentinel 热点参数限流 → 资格前置 → Redis Lua 原子判定（名额扣减+幂等）→ MySQL CAS → 双唯一索引兜底。
  对照实验证明：拆掉任一层都能压出超卖（名额 -499 / 158 人抢中同一名额）。
- **分片前置**：任务链路表按 `campus_id` 共片，账户/信用表按 `user_id` 分片；
  ShardingSphere 规则工厂与 class-based 取模算法已纳入测试。
- **超时流转 / 自动结算**：主通道（MQ 定时消息）追实时、兜底扫描追不丢，靠幂等三件套
  （version CAS + round 轮次 + msg_key 唯一索引）保证双通道重复执行无副作用。
- **两种消息一致性机制按"是否需要延迟"分工**：要延迟（确认超时、自动结算）用本地消息表 +
  定时消息；不要延迟（资金事件通知）用事务消息（半消息 + 回查）。
- **资金三道幂等闸门**：escrow 状态 CAS + errand 状态 CAS + wallet_ledger.biz_no 唯一索引。
  三层对账（L1 借贷平衡 / L2 快照一致 / L3 托管闭环）兜底。
- **availableActions**：状态机只存在于领域层，前端操作按钮由后端计算的动作集合驱动。
- **long→string 序列化**：雪花 ID 超过 JS 安全整数（2^53），Jackson 全局转 string。

## 已知边界（诚实清单）

- 单机默认稳定线到 400 并发：S2 60s 档位约 6455 QPS、P99 151ms；默认 800 并发有 146 个 IOException，调参后 800 并发 0 错误但 P99 472ms。
- 流转准时率 P99 仍为 `[待补充]`：需要自然到期时间轴重测。
- 压测环境无隔离（应用/中间件/发压端同机），数字仅作同机基线。
- 前端"模拟 N 人同时抢"用临时身份演示，临时身份无钱包账户，别用它走完生命周期。
- ES/Canal 已在 P4 摘除（校园场景不需要），一致性兜底是校验 job 而非 binlog 秒级纠正。
- 鉴权已支持 Session/JWT 双实现与 refresh token；测试配置保留 `X-User-Id` 后门仅供压测。

## 2026-10-14 产品验收与修复

前端更新为美团黄配色的校园工作台，任务卡更紧凑，登录组件放大，跑腿身份 `2001` 统一显示“泡”。本轮修复结算与仲裁权限、连续 CAS 冲突时的资金事务回滚、演示登录的内部系统身份冒用、新库缺失 `delivered_at` 及演示钱包的问题。worker 关闭 WebSocket 时不再注册推送消费者，避免无连接的 worker 抢走应用进程应接收的推送消息。

本轮全部通过：57 项前端 mock 检查、35 项真实浏览器流程检查、29 项真实 API/MQ 检查、3 项定向 WebSocket 检查、59 项后端单元/本地测试。真实浏览器流程的业务写入全部由 UI 点击产生；API 用于只读核对。资金 8 组只读 SQL 校验通过，9 个账户总额保持建数基线 210000 分，冻结额为 0，逐业务借贷平衡、系统账户快照、托管闭环、终态与收款对象均一致。前端 `npm ci` 与生产构建、后端全量 `mvn install` 成功。

新库初始化已验证 15 张表、`delivered_at` 和四个演示身份钱包，重复初始化保留已有余额。已有数据库不会因修改 `CREATE TABLE IF NOT EXISTS` 自动增加字段；升级前应检查表结构并按实际缺失项迁移。补齐演示钱包时只能插入不存在的账户，**不可用压测 seed 或更新余额的语句重置现有账户**。

本轮没有重跑原有 18 个 Java 集成测试类和历史大规模压测；资金回滚单测采用记录型事务管理器，并非 MySQL 故障注入。演示账号认证仍是教学模式：选择用户 ID 即可登录，不代表生产身份核验。完整范围、证据、MQ 时序与复跑命令见[本轮验收记录](../campus-dash-frontend/verification/2026-10-14/README.md)。
