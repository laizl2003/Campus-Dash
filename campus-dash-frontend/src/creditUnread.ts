import { api, getUserId } from './api';

export interface CreditEventLike {
  time: string;
  refId: string;
  type: string;
  delta: number;
}

export function creditEventKey(event: CreditEventLike): string {
  return `${event.time}|${event.refId}|${event.type}|${event.delta}`;
}

function seenKey(uid: string) { return `dash_credit_seen:${uid}`; }
function unreadKey(uid: string) { return `dash_credit_unread_keys:${uid}`; }

export function reportCreditUnread(uid: string, keys: Set<string>) {
  localStorage.setItem(unreadKey(uid), JSON.stringify([...keys]));
  localStorage.setItem(`dash_credit_unread:${uid}`, String(keys.size));
  window.dispatchEvent(new CustomEvent('dash:credit-unread', { detail: { userId: uid, count: keys.size } }));
}

/** 用当前信用事件同步未读集合；首次访问把历史事件视为已读 */
export function syncCreditUnread(events: CreditEventLike[]): Set<string> {
  const uid = String(getUserId());
  if (!uid) return new Set();
  const raw = localStorage.getItem(seenKey(uid));
  let seen: Set<string>;
  if (raw === null) {
    seen = new Set(events.map(creditEventKey));
    localStorage.setItem(seenKey(uid), JSON.stringify([...seen]));
  } else {
    try { seen = new Set(JSON.parse(raw)); } catch { seen = new Set(); }
  }
  const unread = new Set(events.filter(event => !seen.has(creditEventKey(event))).map(creditEventKey));
  reportCreditUnread(uid, unread);
  return unread;
}

/** 从服务端拉一次信用事件并同步未读（供侧栏全局使用） */
export async function refreshCreditUnread(): Promise<number> {
  const self = await api.creditSelf();
  return syncCreditUnread(self.events).size;
}

/** 悬停/查看某条信用事件后标记已读 */
export function markCreditRead(event: CreditEventLike): Set<string> {
  const uid = String(getUserId());
  if (!uid) return new Set();
  const key = creditEventKey(event);
  const seenRaw = localStorage.getItem(seenKey(uid));
  let seen: Set<string>;
  try { seen = new Set(seenRaw ? JSON.parse(seenRaw) : []); } catch { seen = new Set(); }
  seen.add(key);
  localStorage.setItem(seenKey(uid), JSON.stringify([...seen]));
  let unread: Set<string>;
  try { unread = new Set(JSON.parse(localStorage.getItem(unreadKey(uid)) || '[]')); } catch { unread = new Set(); }
  unread.delete(key);
  reportCreditUnread(uid, unread);
  return unread;
}
