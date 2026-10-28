import { api, getUserId } from './api';

/** 跑腿在途：抢单锁定、已确认、取货中；送达后不再算在途 */
const RUNNER_ACTIVE = new Set(['LOCKED', 'ACCEPTED', 'PICKED_UP']);

export interface TaskCounts {
  runner: number;
  publisher: number;
  total: number;
}

export function readTaskCounts(): TaskCounts {
  const uid = getUserId();
  if (!uid) return { runner: 0, publisher: 0, total: 0 };
  try {
    const raw = JSON.parse(localStorage.getItem(`dash_task_counts:${uid}`) || 'null');
    if (raw && typeof raw.runner === 'number' && typeof raw.publisher === 'number') return raw;
  } catch { /* 忽略损坏数据 */ }
  return { runner: 0, publisher: 0, total: 0 };
}

/** 同时拉取两种角色，计算侧栏/我的任务各处的数量；任何页面都可调用 */
export async function refreshTaskCounts(): Promise<TaskCounts> {
  const uid = getUserId();
  if (!uid) return { runner: 0, publisher: 0, total: 0 };
  const [runnerCards, publisherCards] = await Promise.all([
    api.mine('GRABBED_BY_ME'),
    api.mine('PUBLISHED_BY_ME'),
  ]);
  const runner = runnerCards.filter(card => RUNNER_ACTIVE.has(card.status)).length;
  const publisher = publisherCards.filter(card => card.status === 'DELIVERED').length;
  const counts = { runner, publisher, total: runner + publisher };
  localStorage.setItem(`dash_task_counts:${uid}`, JSON.stringify(counts));
  window.dispatchEvent(new CustomEvent('dash:task-counts', { detail: counts }));
  return counts;
}
