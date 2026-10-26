# 项目学习进度

> 记录约定：每个学习日一个文件，统一放在 `Process/` 目录下，文件名 `day-0x.md`（两位数字，从 01 开始）。当天的新内容只追加到当天文件，不覆盖历史。

## 目录

| 文件 | 主题 | 要点 |
| --- | --- | --- |
| [day-01.md](Process/day-01.md) | 环境搭建 + 项目启动 + 一键脚本 | JDK 21 / Docker / 前端依赖就绪；`dev.ps1` 启动停止；本机 6 个踩坑 |
| [day-02.md](Process/day-02.md) | worker 接入 + 后端架构梳理 | worker 进程管理与自动重编；PowerShell 数组展开坑；后端八模块、功能清单、抢单五层防线、资金三闸门、缓存与分片 |
| [day-03.md](Process/day-03.md) | domain 层详解（35 个文件） | 类名中英对照表；逐类列出字段、构造器/工厂、方法签名与职责；class/record/enum/interface 选型对比；19 个端口一览 |
| [day-04.md](Process/day-04.md) | application 层详解（26 个文件） | 用例编排、事务边界、提交后副作用；抢单五层、结算三道闸门、超时幂等；关键源码摘录与 6 道自测 |
| [day-05.md](Process/day-05.md) | infrastructure 层详解（37 个文件） | Lua 原子脚本与回滚、12 个 Jdbc CAS 适配器、定时/事务消息与回查、缓存与布隆、JWT/Session、分片算法 |
| [day-06.md](Process/day-06.md) | presentation 层详解（17 个文件） | 认证三件套、13 个任务端点与 toCard、全局异常映射、Jackson long→string、WebSocket 五件套与推送 |
| [day-07.md](Process/day-07.md) | worker / bootstrap / shared 详解（19 个文件） | 4 个 MQ 消费者的 ACK 策略、3 个兜底 job、4 个定时维护、进程装配与 workerId、shared 内核回顾 |
| [day-08.md](Process/day-08.md) | 测试与压测详解（34 测试类 + 5 客户端） | 四条并发不变式、超卖/缓存/事务对照实验、skip 策略、S1/S2/S4 压测数据与三种客户端 |

## 专题文档

| 文件 | 主题 |
| --- | --- |
| [MQandCache-process.md](Process/MQandCache-process.md) | MQ 四类功能（超时/结算/资金事件/延迟双删）+ 抢单 Redis 全流程收口 |

## 新增一天

1. 新建 `Process/day-0x.md`（序号递增，如 `day-03.md`）；
2. 首行写 `# day-0x · 当天主题`；
3. 正文按「今日完成 / 关键实现 / 踩的坑 / 验证记录 / 次日待办」组织；
4. 回到本文件，在上表末尾补一行链接。

## 约定

- **一天一个文件**：便于 git diff、便于按天回顾，不要把多天混进同一个文件。
- **文件名两位数字**：`day-01.md`、`day-02.md`……超过 99 天再考虑三位。
- **标题层级**：文件首行用 `#`，小节用 `##`，小节内细节用 `###`。
- **路径写法**：代码里引用仓库内文件时写相对仓库根目录的路径（如 `campus-dash-backend/docker/init.sql`），不要写本机绝对路径。
- **代码块**：围栏标注语言（`powershell` / `sql` / `lua` / `java`），方便渲染与复制。
