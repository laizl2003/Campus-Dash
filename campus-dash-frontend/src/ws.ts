// WebSocket 客户端：连接、断线重连、心跳、事件分发、降级回退。
//
// 设计要点：
//   1. 连不上 / 断开时自动退回轮询模式（页面交互不中断）——
//      WS 是体验优化，不是可用性依赖
//   2. 重连用指数退避（1s -> 2s -> 4s -> 8s 封顶），避免服务端故障时重连风暴
//   3. 心跳 ping/pong 防中间设备掐断空闲连接

import { getToken } from './api';

export interface WsEvent {
  type: 'errand.status' | 'notification.new' | 'credit.changed';
  payload: Record<string, unknown>;
}

type Handler = (event: WsEvent) => void;

const handlers = new Set<Handler>();
let socket: WebSocket | null = null;
let reconnectDelay = 1000;
let heartbeatTimer: number | undefined;
let reconnectTimer: number | undefined;
let wsAvailable = true; // false = 已降级为轮询模式

export function isWsAvailable() {
  return wsAvailable;
}

export function subscribeWs(handler: Handler): () => void {
  handlers.add(handler);
  return () => handlers.delete(handler);
}

export function connectWs() {
  const token = getToken();
  if (!token) return;

  // 与页面同源（vite 代理不转发 ws 到后端时直连 8080）
  const proto = location.protocol === 'https:' ? 'wss' : 'ws';
  const url = `${proto}://${location.hostname}:8080/ws?token=${encodeURIComponent(token)}`;

  try {
    socket = new WebSocket(url);
  } catch {
    wsAvailable = false;
    return;
  }

  socket.onopen = () => {
    wsAvailable = true;
    reconnectDelay = 1000; // 连上了，重置退避
    // 心跳：30s 一次 ping
    heartbeatTimer = window.setInterval(() => {
      if (socket?.readyState === WebSocket.OPEN) {
        socket.send('ping');
      }
    }, 30000);
  };

  socket.onmessage = (msg) => {
    if (msg.data === 'pong') return;
    try {
      const event = JSON.parse(msg.data) as WsEvent;
      handlers.forEach((h) => h(event));
    } catch {
      // 非 JSON 消息忽略
    }
  };

  socket.onclose = () => {
    if (heartbeatTimer) window.clearInterval(heartbeatTimer);
    socket = null;
    // 指数退避重连；超过 8s 仍失败就标记降级（页面转轮询），但继续尝试重连
    reconnectTimer = window.setTimeout(() => {
      reconnectDelay = Math.min(reconnectDelay * 2, 8000);
      if (reconnectDelay >= 8000) wsAvailable = false;
      connectWs();
    }, reconnectDelay);
  };

  socket.onerror = () => {
    socket?.close();
  };
}

export function disconnectWs() {
  if (heartbeatTimer) window.clearInterval(heartbeatTimer);
  if (reconnectTimer) window.clearTimeout(reconnectTimer);
  socket?.close();
  socket = null;
}
