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

const ACCESS_KEY = 'dash_token';
const REFRESH_KEY = 'dash_refresh';
const USER_KEY = 'dash_user';
const NAME_KEY = 'dash_username';

let token: string | null = localStorage.getItem(ACCESS_KEY);
let refreshToken: string | null = localStorage.getItem(REFRESH_KEY);
// 用户 id 现在是字符串：注册用户是 UUID，仲裁账号是 001，演示账号是 "1001"
let currentUserId: string | null = null;
let currentUsername: string | null = localStorage.getItem(NAME_KEY);
try { currentUserId = token ? JSON.parse(localStorage.getItem(USER_KEY) || 'null') : null; } catch { /* 无效本地身份按未登录处理。 */ }

// 会话代号：登录、登出、切换身份都 +1。请求发出时记下代号，
// 401 回来发现代号变了，说明期间换了人——既不能拿新身份的 refresh 去续旧请求，
// 也不能用新身份把旧请求重放一遍（那会以另一个身份产生副作用）。
let sessionEpoch = 0;

export function getToken() { return token; }
export function getUserId() { return currentUserId; }
/** 展示用昵称：注册用户是用户名，演示账号退回身份名或 id */
export function getUsername() { return currentUsername ?? currentUserId; }
/** 登录身份角色：Admin 进管理台，其余进任务广场 */
export function getRole() { return localStorage.getItem('dash_role') || 'user'; }

interface TokenPair { accessToken: string; refreshToken: string | null; }

/** 只改凭证，不动 epoch：刷新是同一会话内的轮换，不是换人 */
function applyTokens(pair: TokenPair) {
  token = pair.accessToken;
  refreshToken = pair.refreshToken;
  localStorage.setItem(ACCESS_KEY, pair.accessToken);
  if (pair.refreshToken) localStorage.setItem(REFRESH_KEY, pair.refreshToken);
  else localStorage.removeItem(REFRESH_KEY);
}

// 多标签页同步：另一个标签页刷新或登出后，本页跟着换凭证，
// 否则本页会继续拿已经轮换掉的旧 refresh token 去换新，被服务端拒绝。
try {
  window.addEventListener('storage', event => {
    if (event.key === ACCESS_KEY) token = event.newValue;
    if (event.key === REFRESH_KEY) refreshToken = event.newValue;
  });
} catch { /* 非浏览器环境（Node 验证脚本）忽略 */ }

/** 登录后的身份：userId 来自后端查库结果，前端只做展示 */
export interface Profile {
  userId: string;
  username: string;
  role: string;
}

async function postJson<T>(path: string, payload: unknown): Promise<T> {
  let resp: Response;
  try {
    resp = await fetch(path, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify(payload),
    });
  } catch {
    throw new Error('连接不到服务，请确认后端已启动');
  }
  let body: ApiResult<T>;
  try {
    body = (await resp.json()) as ApiResult<T>;
  } catch {
    // 网关 502/504 返回的是 HTML 错误页，直接 JSON.parse 会得到这句困惑的提示
    throw new Error(resp.ok
      ? '服务返回了非 JSON 内容，请稍后重试'
      : `服务暂时不可用（HTTP ${resp.status}），请稍后重试`);
  }
  if (body.code !== 'OK') throw new BizError(body.code, body.message);
  return body.data;
}

/** 取一张图形验证码：答案只在服务端，这里拿到的是 captchaId 与 data URL */
export async function captchaImage(): Promise<{ captchaId: string; image: string; expireSeconds: number }> {
  const resp = await fetch('/api/auth/captcha');
  if (!resp.ok) throw new Error('验证码加载失败，请重试');
  const body = (await resp.json()) as ApiResult<{ captchaId: string; image: string; expireSeconds: number }>;
  if (body.code !== 'OK') throw new BizError(body.code, body.message);
  return body.data;
}

export interface RegisterInput {
  username: string;
  phone: string;
  password: string;
  confirmPassword: string;
  captchaId: string;
  captchaCode: string;
}

/** 注册：成功后不会自动登录，由调用方切回登录表单 */
export async function registerAccount(input: RegisterInput): Promise<{ userId: string; username: string }> {
  const data = await postJson<{ userId: string; username: string }>('/api/auth/register', input);
  return { userId: String(data.userId), username: data.username };
}

export interface LoginInput {
  username: string;
  password: string;
  captchaId: string;
  captchaCode: string;
}

/** 正门登录：用户名 + 密码 + 验证码，成功后 userId 来自数据库 */
export async function login(input: LoginInput): Promise<Profile> {
  const data = await postJson<{
    token?: string; accessToken?: string; refreshToken?: string;
    userId?: string; username?: string; role?: string;
  }>('/api/auth/login', input);
  const accessToken = data.accessToken ?? data.token;
  if (!accessToken) throw new Error('登录响应缺少 access token');
  const previousRefresh = refreshToken;
  applyTokens({ accessToken, refreshToken: data.refreshToken ?? null });
  currentUserId = String(data.userId);
  currentUsername = data.username ?? input.username;
  sessionEpoch++;
  localStorage.setItem(USER_KEY, JSON.stringify(currentUserId));
  localStorage.setItem(NAME_KEY, currentUsername);
  localStorage.setItem('dash_role', data.role ?? 'user');
  if (previousRefresh && previousRefresh !== (data.refreshToken ?? null)) revokeQuietly(previousRefresh, null);
  return {
    userId: currentUserId,
    username: currentUsername,
    role: data.role ?? 'user',
  };
}

/** 演示身份直登：调用后门的 userId 分支，只给本地演示/压测用 */
export async function loginAs(userId: string, displayName?: string): Promise<Profile> {
  const data = await postJson<{ token?: string; accessToken?: string; refreshToken?: string; userId?: string }>(
    '/api/auth/login',
    { userId },
  );
  const accessToken = data.accessToken ?? data.token;
  if (!accessToken) throw new Error('登录响应缺少 access token');
  applyTokens({ accessToken, refreshToken: data.refreshToken ?? null });
  currentUserId = String(data.userId ?? userId);
  currentUsername = displayName ?? currentUserId;
  sessionEpoch++;
  localStorage.setItem(USER_KEY, JSON.stringify(currentUserId));
  localStorage.setItem(NAME_KEY, currentUsername);
  localStorage.setItem('dash_role', 'user');
  return { userId: currentUserId, username: currentUsername, role: 'user' };
}

async function requestTokens(userId: string | number): Promise<TokenPair> {
  const data = await postJson<{ token?: string; accessToken?: string; refreshToken?: string }>(
    '/api/auth/login',
    { userId: String(userId) },
  );
  const accessToken = data.accessToken ?? data.token;
  if (!accessToken) throw new Error('登录响应缺少 access token');
  return { accessToken, refreshToken: data.refreshToken ?? null };
}

/** 并发抢单演示用的一次性身份：不写入本地会话。 */
export async function issueToken(userId: string | number): Promise<string> {
  return (await requestTokens(userId)).accessToken;
}

/** 并发抢单演示后恢复原身份：token、refresh 与模块内 userId 一起恢复，
 *  只写 localStorage 不恢复模块变量的话 getUserId() 会返回错的值（实测踩过）。
 *  演示身份没有 refresh token，第三个参数省略时按 null 处理。 */
export function restoreIdentity(access: string | null, userId: string | null, refresh: string | null = null) {
  token = access;
  refreshToken = refresh;
  currentUserId = userId;
  sessionEpoch++;
  if (access) localStorage.setItem(ACCESS_KEY, access); else localStorage.removeItem(ACCESS_KEY);
  if (refresh) localStorage.setItem(REFRESH_KEY, refresh); else localStorage.removeItem(REFRESH_KEY);
  if (userId !== null) localStorage.setItem(USER_KEY, JSON.stringify(userId));
}

export function logout() {
  const access = token;
  const refresh = refreshToken;
  token = null;
  refreshToken = null;
  currentUserId = null;
  sessionEpoch++;
  currentUsername = null;
  localStorage.removeItem(ACCESS_KEY);
  localStorage.removeItem(REFRESH_KEY);
  localStorage.removeItem(USER_KEY);
  localStorage.removeItem(NAME_KEY);
  localStorage.removeItem('dash_role');
  window.dispatchEvent(new Event('dash:logout'));
  // 只清本地等于把 refresh 留在服务端：登出必须让服务端也撤销，否则 7 天内仍可换新 access
  revokeQuietly(refresh, access);
}

/** 服务端撤销：不读响应、不抛错，撤销失败也不该阻塞前端退出 */
function revokeQuietly(refresh: string | null, access: string | null) {
  if (!refresh && !access) return;
  const headers: Record<string, string> = { 'Content-Type': 'application/json' };
  if (access) headers['Authorization'] = `Bearer ${access}`;
  void fetch('/api/auth/logout', {
    method: 'POST',
    headers,
    body: JSON.stringify({ refreshToken: refresh }),
    keepalive: true, // 关闭/刷新页面时请求也能发完
  }).catch(() => { /* 撤销失败只影响服务端残留，不影响本地登出 */ });
}

// ── 刷新：单飞 + 轮换 ─────────────────────────────────
// 并发 401 共享同一个 Promise，只发一次 /api/auth/refresh；
// 多标签页再用 Web Locks 串行化，锁内重新读取最新 refresh token。
let refreshInFlight: Promise<boolean> | null = null;

function withRefreshLock<T>(task: () => Promise<T>): Promise<T> {
  const locks = navigator.locks;
  if (!locks?.request) return task(); // 老浏览器/Node：退化为标签页内单飞
  return locks.request('dash:refresh', () => task()) as Promise<T>;
}

async function doRefresh(accessSeenBeforeLock: string | null): Promise<boolean> {
  // 等锁期间别的标签页已经换了新 access：直接用它的结果，不必再换一次
  if (token !== accessSeenBeforeLock) return true;
  const presented = refreshToken;
  if (!presented) return false;
  const epoch = sessionEpoch;
  try {
    const resp = await fetch('/api/auth/refresh', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ refreshToken: presented }),
    });
    const body = resp.ok
      ? ((await resp.json()) as ApiResult<{ token?: string; accessToken?: string; refreshToken?: string }>)
      : null;
    const access = body?.code === 'OK' ? body.data?.accessToken ?? body.data?.token : null;
    if (!body || body.code !== 'OK' || !access) {
      // 可能是别的标签页抢先把这张 refresh 用掉了；先看共享存储再判过期
      return adoptSharedRotation(presented);
    }
    // 刷新期间用户切了身份：丢弃这次结果，绝不覆盖新会话
    if (epoch !== sessionEpoch) return false;
    // 期间别的标签页已经轮换过：用它的结果
    if (refreshToken !== presented) return token !== null;
    // 必须保存服务端新发的 refresh：旧的那张已被 GETDEL 删除，不存就等于自断续期
    applyTokens({ accessToken: access, refreshToken: body.data?.refreshToken ?? null });
    return true;
  } catch {
    return adoptSharedRotation(presented);
  }
}

/** 刷新失败时先看别的标签页是否已经轮换过，是就采用它的凭证，避免把"别人换过了"误判成"会话过期" */
function adoptSharedRotation(presented: string): boolean {
  const sharedRefresh = localStorage.getItem(REFRESH_KEY);
  const sharedAccess = localStorage.getItem(ACCESS_KEY);
  if (sharedAccess && sharedRefresh && sharedRefresh !== presented) {
    token = sharedAccess;
    refreshToken = sharedRefresh;
    return true;
  }
  return false;
}

/** 成功刷新返回 true；refresh 缺失/过期/被撤销返回 false */
function refreshOnce(): Promise<boolean> {
  if (refreshInFlight) return refreshInFlight;
  if (!refreshToken) return Promise.resolve(false);
  const accessAtStart = token;
  const run = async (): Promise<boolean> => {
    try {
      const refreshed = await withRefreshLock(() => doRefresh(accessAtStart));
      // 等锁期间别的标签页已经换了新 access：直接重试原请求即可
      return refreshed || token !== accessAtStart;
    } finally {
      refreshInFlight = null;
    }
  };
  refreshInFlight = run();
  return refreshInFlight;
}

export class BizError extends Error {
  code: string;
  constructor(code: string, message: string) {
    super(message);
    this.code = code;
  }
}

/** 发请求并返回 data；code 非 OK 时抛 BizError（带业务码）
 *  retried：原请求是否已经重试过。只允许重试一次，否则刷新失败会变成死循环。 */
async function request<T>(path: string, method = 'GET', body?: unknown, retried = false): Promise<T> {
  const epoch = sessionEpoch;
  const requestToken = token;
  const headers: Record<string, string> = {};
  if (requestToken) headers['Authorization'] = `Bearer ${requestToken}`;
  if (body !== undefined) headers['Content-Type'] = 'application/json';
  const resp = await fetch(path, {
    method,
    headers,
    body: body === undefined ? undefined : JSON.stringify(body),
  });
  if (resp.status === 401) {
    // 已切换身份 / 已登出：旧请求的 401 既不能退出新会话，也不能用新身份把旧请求重放一遍
    if (epoch !== sessionEpoch) throw new BizError('UNAUTHORIZED', '会话已过期，请重新登录');
    // 刷新接口自身返回 401 不再递归；业务请求最多重试一次
    if (!retried && !path.startsWith('/api/auth/')) {
      // 别的请求已经换过 token：直接重试；否则发起（或被复用的）刷新
      const refreshed = token !== requestToken ? true : await refreshOnce();
      if (refreshed) {
        if (epoch !== sessionEpoch) throw new BizError('UNAUTHORIZED', '会话已过期，请重新登录');
        return request<T>(path, method, body, true);
      }
    }
    logout();
    location.hash = '#/';
    throw new BizError('UNAUTHORIZED', '会话已过期，请重新登录');
  }
  if (!resp.ok) throw new BizError('HTTP_ERROR', '服务暂时不可用，请稍后重试');
  // 后端所有 long 都序列化为 string；仅金额转安全整数，ID 保留原始字符串。
  const result = JSON.parse(await resp.text(), (key, value) => {
    if (['rewardCents', 'amountCents', 'availableCents', 'frozenCents', 'discountCents', 'paidCents',
      'escrowAmountCents', 'voucherDiscountCents', 'runnerBearCents'].includes(key)) {
      const amount = Number(value);
      if (!Number.isSafeInteger(amount)) throw new BizError('INVALID_AMOUNT', '金额数据超出可显示范围');
      return amount;
    }
    return value;
  }) as ApiResult<T>;
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
  creditSelf: () => request<{ score: number; windowDays: number; events: { type: string; description: string; delta: number; refId: string; time: string }[] }>('/api/credit/self'),
  creditRanking: (campusId = 1, limit = 20) => request<{ userId: string; score: number }[]>(`/api/credit/ranking?campusId=${campusId}&limit=${limit}`),
  health: () => request<{ status: string }>('/api/health'),
  list: (campusId = 1, status?: string) =>
    request<ErrandCard[]>(`/api/errands?campusId=${campusId}${status ? `&status=${status}` : ''}`),
  mine: (role: 'PUBLISHED_BY_ME' | 'GRABBED_BY_ME') =>
    request<ErrandCard[]>(`/api/errands/mine?role=${role}`),
  detail: (id: string) => request<ErrandCard>(`/api/errands/${id}`),
  timeline: (id: string) => request<StatusChange[]>(`/api/errands/${id}/timeline`),
  publish: (req: { title: string; rewardCents: number; slotTotal: number; type?: string; voucherId?: number | null }) =>
    request<{
      errandId: string; status: string; frozenCents: number; discountCents: number; paidCents: number;
    }>('/api/errands', 'POST', req),
  grab: (id: string) =>
    request<{ code: string; grabbed: boolean }>(`/api/errands/${id}/grab`, 'POST'),
  action: (id: string, name: 'confirm' | 'pickup' | 'deliver' | 'settle' | 'cancel' | 'dispute') =>
    request<unknown>(`/api/errands/${id}/${name}`, 'POST'),
  arbitrate: (id: string, favor: 'RUNNER' | 'PUBLISHER') =>
    request<{ result: string }>(`/api/errands/${id}/arbitrate`, 'POST', { favor }),
  wallet: () => request<{ availableCents: number; frozenCents: number }>('/api/wallet'),
  recharge: (amountCents: number) =>
    request<{ availableCents: number }>('/api/wallet/recharge', 'POST', { amountCents }),
  ledger: () => request<{
    time: string; direction: string; amountCents: number;
    refType: string; refId: string; bizNo: string;
  }[]>('/api/wallet/ledger'),
  notifications: () => request<{
    id: string; errandId: string; type: string; content: string; time: string;
  }[]>('/api/notifications'),
  vouchers: () => request<{
    items: { type: number; stock: number; description: string; thresholdCents: number; discountCents: number }[];
  }>('/api/vouchers'),
  myVouchers: () => request<{
    voucherId: string; type: number; description: string; thresholdCents?: number; discountCents?: number;
    expireTime: string; status: string;
  }[]>('/api/vouchers/mine'),
  claimVoucher: (type: number) => request<{
    voucherId: string; type: number; description: string; expireTime: string; remainingStock: number;
  }>(`/api/vouchers/${type}/claim`, 'POST'),
  me: () => request<{ userId: string; username: string; role: string }>('/api/auth/me'),
  adminDisputes: () => request<{
    errandId: string; title: string; status: string; rewardCents: number;
    publisherId: string; grabberId: string; round: number;
  }[]>('/api/admin/disputes'),
  adminDispute: (id: string) => request<{
    errandId: string; title: string; status: string; rewardCents: number;
    publisherId: string; grabberId: string; round: number;
    escrowAmountCents?: number; escrowStatus?: string;
    voucherDescription?: string; voucherDiscountCents?: number; runnerBearCents?: number;
  }>(`/api/admin/disputes/${id}`),
  adminArbitrate: (id: string, favor: 'RUNNER' | 'PUBLISHER') =>
    request<{ result: string }>(`/api/admin/disputes/${id}/arbitrate`, 'POST', { favor }),
  unread: async () => {
    const result = await request<{ count: number | string }>('/api/notifications/unread');
    return { count: Number(result.count) };
  },
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

export const TYPE_TEXT: Record<string, string> = { DELIVERY: '跑腿配送', BUY: '帮我买', QUEUE: '代排队', OTHER: '其他帮忙' };
export const TYPE_ICON: Record<string, string> = { DELIVERY: 'package', BUY: 'bag', QUEUE: 'clock', OTHER: 'grid' };
