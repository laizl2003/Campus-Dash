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

## 快速开始

```bash
# 1. 起中间件并初始化（表、topic、消费组）
cd docker
docker compose up -d && sleep 15 && ./init-mq.sh
cd ..

# 2. 跑单元与集成测试（中间件没起时集成测试 skip 而不是 fail）
mvn clean test

# 3. 启动应用（默认 8080，后门关闭，只认 Bearer token）
mvn -pl dash-bootstrap spring-boot:run

# 4. 启动 worker（超时流转 / 自动结算 / 资金事件消费 / 消息重发）
mvn install -DskipTests
java -jar dash-worker/target/dash-worker-1.0.0-SNAPSHOT.jar

# 5. 启动前端（5173，vite 代理 /api -> 8080）
cd ../campus-dash-frontend && npm install && npm run dev

# 6. 浏览器打开 http://localhost:5173，选择演示身份登录
#    发单人 1001 / 跑腿 2001、2002 / 仲裁员 9001
```

### API 快速体验（不走前端）

```bash
TOKEN=$(curl -s -X POST localhost:8080/api/auth/login \
  -H 'Content-Type: application/json' -d '{"userId":1001}' \
  | python3 -c "import sys,json;print(json.load(sys.stdin)['data']['token'])")

# 发布（资金同步托管）、抢单、全生命周期操作
curl -s -X POST localhost:8080/api/errands -H "Authorization: Bearer $TOKEN" \
  -H 'Content-Type: application/json' \
  -d '{"title":"帮我带份饭","rewardCents":1500,"slotTotal":1}'
# 详情响应含 availableActions：前端按钮完全由它驱动，不实现第二份状态机
```

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
