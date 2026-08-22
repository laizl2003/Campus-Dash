// 后端请求封装：token 管理 + Result 统一处理。
//
// 后端响应结构是 Result<T> = { code, message, data }：
//   code === 'OK' 表示成功；其他 code（SLOT_FULL / NOT_CURRENT_GRABBER 等）
//   是业务结果而不是服务器错误——前端按 code 分支处理，绝不解析文案。

export interface ApiResult<T> {
  code: string;
  message: string;
  data: T;
}

let token: string | null = localStorage.getItem('dash_token');
let currentUserId: number | null = JSON.parse(localStorage.getItem('dash_user') || 'null');

export function getToken() { return token; }
export function getUserId() { return currentUserId; }

export async function login(userId: number): Promise<string> {
  const resp = await fetch('/api/auth/login', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ userId }),
  });
  const body = (await resp.json()) as ApiResult<{ token: string; userId: number }>;
  if (body.code !== 'OK') throw new Error(body.message);
  token = body.data.token;
  currentUserId = userId;
  localStorage.setItem('dash_token', token!);
  localStorage.setItem('dash_user', JSON.stringify(userId));
  return token!;
}

/** 并发抢单演示后恢复原身份：token 与模块内 userId 一起恢复，
 *  只写 localStorage 不恢复模块变量的话 getUserId() 会返回错的值（实测踩过） */
export function restoreIdentity(tok: string | null, userId: number | null) {
  token = tok;
  currentUserId = userId;
  if (tok) localStorage.setItem('dash_token', tok);
  if (userId !== null) localStorage.setItem('dash_user', JSON.stringify(userId));
}

export function logout() {
  token = null;
  currentUserId = null;
  localStorage.removeItem('dash_token');
  localStorage.removeItem('dash_user');
}

export class BizError extends Error {
  code: string;
  constructor(code: string, message: string) {
    super(message);
    this.code = code;
  }
}

/** 发请求并返回 data；code 非 OK 时抛 BizError（带业务码） */
async function request<T>(path: string, method = 'GET', body?: unknown): Promise<T> {
  const headers: Record<string, string> = {};
  if (token) headers['Authorization'] = `Bearer ${token}`;
  if (body !== undefined) headers['Content-Type'] = 'application/json';
  const resp = await fetch(path, {
    method,
    headers,
    body: body === undefined ? undefined : JSON.stringify(body),
  });
  if (resp.status === 401) {
    logout();
    location.hash = '#/';
    throw new BizError('UNAUTHORIZED', '会话已过期，请重新登录');
  }
  const result = (await resp.json()) as ApiResult<T>;
  if (result.code !== 'OK') throw new BizError(result.code, result.message);
  return result.data;
}

// ── 任务 ──────────────────────────────────────────────
export interface ErrandCard {
  /** 后端以字符串返回：雪花 ID 超过 JS 安全整数（2^53），
   *  按 number 解析会截断精度，全程必须按 string 处理 */
  id: string;
  title: string;
  status: string;
  type: string;
  rewardCents: number;
  slotTotal: number;
  slotTaken: number;
  publisherId: string;
  grabberId: string;
  round: number;
  role: string;
  availableActions: string[];
}

export interface StatusChange {
  time: string; from: string; to: string; round: number; operatorId: string;
}

export const api = {
  health: () => request<{ status: string }>('/api/health'),
  list: (campusId = 1, status?: string) =>
    request<ErrandCard[]>(`/api/errands?campusId=${campusId}${status ? `&status=${status}` : ''}`),
  mine: (role: 'PUBLISHED_BY_ME' | 'GRABBED_BY_ME') =>
    request<ErrandCard[]>(`/api/errands/mine?role=${role}`),
  detail: (id: string) => request<ErrandCard>(`/api/errands/${id}`),
  timeline: (id: string) => request<StatusChange[]>(`/api/errands/${id}/timeline`),
  publish: (req: { title: string; rewardCents: number; slotTotal: number; type?: string }) =>
    request<{ errandId: string; status: string }>('/api/errands', 'POST', req),
  grab: (id: string) =>
    request<{ code: string; grabbed: boolean }>(`/api/errands/${id}/grab`, 'POST'),
  action: (id: string, name: 'confirm' | 'pickup' | 'deliver' | 'settle' | 'cancel' | 'dispute') =>
    request<unknown>(`/api/errands/${id}/${name}`, 'POST'),
  arbitrate: (id: string, favor: 'RUNNER' | 'PUBLISHER') =>
    request<{ result: string }>(`/api/errands/${id}/arbitrate`, 'POST', { favor }),
  wallet: () => request<{ availableCents: number; frozenCents: number }>('/api/wallet'),
  ledger: () => request<{
    time: string; direction: string; amountCents: number;
    refType: string; refId: string; bizNo: string;
  }[]>('/api/wallet/ledger'),
  notifications: () => request<{
    id: string; errandId: string; type: string; content: string; time: string;
  }[]>('/api/notifications'),
  unread: () => request<{ count: number }>('/api/notifications/unread'),
};

/** 金额展示：分 -> 元 */
export function yuan(cents: number): string {
  return `¥${(cents / 100).toFixed(2)}`;
}

export const STATUS_TEXT: Record<string, string> = {
  DRAFT: '草稿', PUBLISHED: '待抢单', LOCKED: '待确认', ACCEPTED: '待取货',
  PICKED_UP: '配送中', DELIVERED: '待确认完成', SETTLED: '已结算',
  CLOSED: '已关闭', CANCELLED: '已取消', DISPUTED: '争议中', REFUNDED: '已退款',
};

export const ACTION_TEXT: Record<string, string> = {
  GRAB: '抢单', CONFIRM: '确认接单', PICKUP: '取货', DELIVER: '送达',
  SETTLE: '确认完成（结算）', CANCEL: '取消并退款', DISPUTE: '发起争议',
  ARBITRATE: '仲裁',
};
