# CampusDash 前端

校园跑腿抢单市场的前端项目，基于 React 18 + TypeScript + Vite 构建。

## 技术栈

- **React 18** - UI 框架
- **TypeScript** - 类型安全
- **Vite 5** - 构建工具
- **WebSocket API** - 实时推送

## 快速开始

```bash
# 安装依赖
npm install

# 启动开发服务器（5173 端口）
npm run dev

# 构建生产版本
npm run build

# 预览生产版本
npm run preview
```

访问 http://localhost:5173

## 演示身份

- **发单人** 1001
- **跑腿** 2001、2002
- **仲裁员** 9001

## 页面结构

- **任务广场** (`Square.tsx`) - 浏览可抢任务
- **发布任务** (`Publish.tsx`) - 发布新任务
- **我的任务** (`Mine.tsx`) - 查看自己发布/抢单的任务
- **任务详情** (`Detail.tsx`) - 任务详情与操作
- **钱包** (`Wallet.tsx`) - 查看余额与流水
- **信用分** (`Credit.tsx`) - 查看信用分与事件
- **消息** (`Notifications.tsx`) - 查看系统通知

## 核心特性

- **WebSocket 实时推送** - 任务状态变更实时通知
- **availableActions 驱动** - 操作按钮由后端计算，前端零状态机
- **长连接自动重连** - 网络断开后自动重连
- **雪花 ID 处理** - 后端 long 类型 ID 自动转为 string，避免精度丢失

## 代理配置

开发环境下，Vite 自动将 `/api` 请求代理到后端 `http://localhost:8080`。

配置见 `vite.config.ts`。

## 项目结构

```
src/
├── components/        # 通用组件
│   ├── TopBar.tsx     # 顶部导航栏
│   ├── ErrandCard.tsx # 任务卡片
│   └── ActionButtons.tsx # 操作按钮
├── pages/             # 页面组件
│   ├── Square.tsx     # 任务广场
│   ├── Publish.tsx    # 发布任务
│   ├── Mine.tsx       # 我的任务
│   ├── Detail.tsx     # 任务详情
│   ├── Wallet.tsx     # 钱包
│   ├── Credit.tsx     # 信用分
│   └── Notifications.tsx # 消息
├── api.ts             # API 请求封装
├── ws.ts              # WebSocket 连接管理
├── App.tsx            # 主应用组件
├── router.tsx         # 路由配置
├── main.tsx           # 入口文件
└── styles.css         # 全局样式
```

## 与后端集成

前端通过 REST API 与后端交互：

- **认证**：JWT Bearer Token
- **任务操作**：`/api/errands/*`
- **钱包查询**：`/api/wallet/*`
- **信用分查询**：`/api/credit/*`
- **消息查询**：`/api/notifications/*`

详细 API 文档见后端 README。

## 已知限制

- 演示身份（1001/2001/2002/9001）是临时身份，无真实钱包账户
- WebSocket 连接断开后，需要刷新页面才能恢复推送
- 前端不实现状态机，所有操作按钮由后端 `availableActions` 驱动

---

**这是教学项目的前端部分**，配合后端项目演示高并发场景下的完整业务流程。
