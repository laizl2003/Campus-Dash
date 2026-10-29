// 连接生命周期与主动断开分离，避免退出或切换身份后旧连接继续重试。
import { getToken } from './api';
export interface WsEvent {
  type: 'errand.status' | 'notification.new' | 'credit.changed' | 'admin.dispute' | 'chat.message' | 'chat.error' | 'ws.open';
  payload: Record<string, unknown>;
}
type Handler = (event: WsEvent) => void;
const handlers = new Set<Handler>();
let socket: WebSocket | null = null;
let reconnectDelay = 1000;
let heartbeatTimer: number | undefined;
let reconnectTimer: number | undefined;
let wsAvailable = false;
let generation = 0;
export function isWsAvailable() { return wsAvailable; }

/** 向当前 WebSocket 发送一帧；未连接时返回 false，由调用方提示重试。 */
export function sendWs(payload: unknown): boolean {
  if (!socket || socket.readyState !== WebSocket.OPEN) return false;
  socket.send(JSON.stringify(payload));
  return true;
}
export function subscribeWs(handler: Handler): () => void { handlers.add(handler); return () => { handlers.delete(handler); }; }
export function disconnectWs() {
  generation++;
  window.clearInterval(heartbeatTimer); window.clearTimeout(reconnectTimer);
  if (socket) { socket.onclose = null; socket.onopen = null; socket.onmessage = null; socket.onerror = null; socket.close(); }
  socket = null; wsAvailable = false;
}
export function connectWs() {
  disconnectWs();
  reconnectDelay = 1000;
  const current = generation;
  const open = () => {
    const token = getToken();
    if (!token || current !== generation) return;
    const proto = location.protocol === 'https:' ? 'wss' : 'ws';
    const connection = new WebSocket(`${proto}://${location.host}/ws?token=${encodeURIComponent(token)}`);
    socket = connection;
    connection.onopen = () => {
      if (current !== generation) { connection.close(); return; }
      wsAvailable = true; reconnectDelay = 1000;
      handlers.forEach(h => h({ type: 'ws.open', payload: {} }));
      heartbeatTimer = window.setInterval(() => { if (connection.readyState === WebSocket.OPEN) connection.send('ping'); }, 30000);
    };
    connection.onmessage = msg => {
      if (current !== generation || msg.data === 'pong') return;
      try { const event = JSON.parse(msg.data) as WsEvent; handlers.forEach(h => h(event)); } catch { /* 忽略非业务消息。 */ }
    };
    connection.onclose = () => {
      if (current !== generation) return;
      window.clearInterval(heartbeatTimer); socket = null; wsAvailable = false;
      reconnectTimer = window.setTimeout(open, reconnectDelay);
      reconnectDelay = Math.min(reconnectDelay * 2, 8000);
    };
    connection.onerror = () => connection.close();
  };
  open();
}
