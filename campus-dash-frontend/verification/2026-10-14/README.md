# 2026-10-14 产品功能验收

本轮功能验收通过。使用独立 MySQL、Redis、RocketMQ、应用进程和 worker，未连接原业务数据库。前端 `npm ci` 与生产构建、后端全量 `mvn install` 成功。

## 范围与证据

| 层次 | 结果与范围 | 证据 |
| --- | --- | --- |
| 前端 mock | 57 项通过；界面、响应式、交互、冲突/幂等反馈和迟到响应回归 | [ui-mock-report.json](ui-mock-report.json) |
| 真实浏览器 | 35 项通过；发布→抢单→确认→取货→送达→结算、取消退款、争议仲裁、钱包/信用/消息；业务写入全由 UI 点击，API 仅只读核对 | [ui-live-report.json](ui-live-report.json) |
| 真实 API/MQ | 29 项通过；并发抢单/结算/取消、越权拒绝、重复请求幂等、仲裁支持跑腿、超时转候选/回退及自动结算 | [api-live-report.json](api-live-report.json) |
| 定向 WebSocket | 3 项通过；真实握手、退款通知经 MQ 推送、通知落库；worker 未注册推送消费者 | [websocket-live-report.json](websocket-live-report.json) |
| 后端单元/本地测试 | 59 项通过，0 失败、0 错误、0 跳过 | [backend-unit-report.json](backend-unit-report.json) |
| 资金一致性 | 8 组只读 SQL 校验通过 | [查询](fund-invariants.sql)、[结果](fund-invariants-result.txt) |

全新库初始化已验证 15 张表、`errand.delivered_at` 及四个演示身份钱包，重复执行初始化保留已有余额。已有库不会因 `CREATE TABLE IF NOT EXISTS` 自动补字段，应按缺失项迁移；钱包只补不存在的账户。

资金校验中，9 个账户总额为 **210000 分**，等于本轮实际建数基线，冻结额为 0；逐业务借贷不平笔数为 0，系统账户快照异常为 0，托管余额与 HELD 总额均为 0；所有终态、对应流水和收款对象一致。结果文件中返回空集的异常查询不输出数据行。210000 分是本次测试账户初始化的合计，不是每次新库的固定预期值。

## MQ 时序与环境修正

确认超时配置 30 秒，自动结算配置 45 秒；新任务实际从送达到结算耗时 **51.353 秒**。本地消息每 10 秒派发一次，本次比到期时间晚约 6.35 秒。消费者日志明确记录新任务 `result=SETTLED`；后续手动重复结算才返回 `ALREADY_SETTLED`。见[自动结算时序](auto-settle-timing.json)及 API 报告的 `mqEvidence`。

确认超时及自动结算兜底扫描间隔均设为 600000 毫秒，本轮任务窗口内没有执行扫描；转候选、空候选回退和自动结算均有 MQ 消费者日志佐证。首轮隔离测试库遗漏时区配置，修正为 `Asia/Shanghai` 后使用新任务完整重跑通过。该问题属于本轮测试环境配置，不计为生产代码缺陷。

## 复跑

先启动专用隔离后端及中间件，应用与 worker 使用一致的 `Asia/Shanghai` 时区及数据库连接时区。MQ 验收使用 `dash.timeout.confirm-seconds=30`、`dash.settle.auto-settle-seconds=45`，worker 的 `dash.timeout.scan-interval-ms` 和 `dash.settle.scan-interval-ms` 均为 `600000`；保持 worker `dash.ws.enabled=false`。创建 UI 演示账户 `1001/2001/2002/9001` 与 API 测试账户 `11001/12001/12002`，确保两个发单账户有足够测试余额，并记录所有账户初始总额。不要把复跑脚本指向真实业务库或重置已有账户余额。

```bash
# 从仓库根目录运行；JDK 21、Maven 3.9+、Node 18+。
cd campus-dash-backend
mvn test '-Dtest=*Test,*Tests' -Dsurefire.failIfNoSpecifiedTests=false
mvn install -DskipTests

cd ../campus-dash-frontend
npm ci
npm run build
# 在另一终端保持 npm run dev 运行（默认 5173）。
python3 -m pip install playwright
python3 -m playwright install chromium
python3 tests/ui_smoke.py
python3 tests/live_smoke.py --isolated-backend-ready --base http://127.0.0.1:5173 --output verification/rerun/ui
python3 tests/live_api_smoke.py --isolated-backend-ready --base http://127.0.0.1:8080 --worker-log /absolute/path/to/isolated-worker.log --output verification/rerun/api
```

`--worker-log` 必须指向本轮运行的 worker 日志，并开启 `logging.level.com.campusdash.worker.TimeoutTransferConsumer=DEBUG`，以验证超时消息由消费者处理。两个真实联调脚本会创建测试任务和资金流水；运行结束待消费者稳定后，在隔离数据库只读执行 `fund-invariants.sql`，与运行前账户基线比对。定向 WebSocket 复验使用四个演示身份之一建立 `/ws` 会话，通过取消测试任务触发退款，确认收到 `notification.new`、通知已落库，且关闭 WebSocket 的 worker 没有注册推送消费者。

## 边界

- 本轮未重跑原有 **18 个 Java 集成测试类**，也未重跑历史大规模压测；报告中的小规模并发检查不代表历史容量指标复验。
- 资金回滚单测使用记录型事务管理器验证 Spring 事务回滚路径，并非 MySQL 故障注入。
- 演示认证仍按用户 ID 登录，属于教学模式。资金权限检查已回归，不代表已实现生产账号认证。
- 单次自动结算时序只证明本轮链路正常，不构成延迟分布或 P99 结论。
