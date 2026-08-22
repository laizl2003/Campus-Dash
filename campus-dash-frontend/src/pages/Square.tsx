import { useCallback, useEffect, useRef, useState } from 'react';
import { api, BizError, yuan, STATUS_TEXT } from '../api';
import type { ErrandCard } from '../api';
import ErrandCardView from '../components/ErrandCard';
import { login, getUserId, getToken, restoreIdentity } from '../api';

interface GrabResultRow {
  label: string;
  ok: boolean;
  code: string;
  ms: number;
}

/**
 * 任务广场：列表 + 抢单 + "模拟 N 人同时抢"演示。
 *
 * 并发抢单演示的实现方式：当前浏览器以当前身份发 N 个抢单请求。
 * 注意浏览器对同域并发连接有限制（HTTP/1.1 约 6 条），所以这只能演示
 * "少量并发下名额不超发"；真实的 2000 并发压测看后端的 SpikeLoadClient 报告，
 * 页面上对此做了如实标注——不把演示当压测。
 *
 * 另一个诚实的边界：同一用户重复抢会被幂等拦成 ALREADY_GRABBED，
 * 所以演示"多个不同跑腿抢"需要用不同的 token——这里通过"临时登录 N 个跑腿身份
 * 各拿一个 token、然后并发发请求"实现，与真实多人抢单等价。
 */
export default function Square() {
  const [cards, setCards] = useState<ErrandCard[]>([]);
  const [error, setError] = useState<string | null>(null);
  const [grabLog, setGrabLog] = useState<GrabResultRow[]>([]);
  const [grabbing, setGrabbing] = useState(false);
  const targetRef = useRef<string | null>(null);

  const load = useCallback(() => {
    api.list(1).then(setCards).catch((e) => setError(e.message));
  }, []);

  useEffect(() => { load(); }, [load]);

  /** 并发抢单演示：临时登录 N 个跑腿身份，同时发抢单请求 */
  const spikeGrab = async (errandId: string, n: number) => {
    targetRef.current = errandId;
    setGrabbing(true);
    setGrabLog([]);
    setError(null);
    const myToken = getToken();
    const myUserId = getUserId();
    try {
      // 给 N 个临时跑腿身份（30000+i）各登录拿 token。
      // 临时身份在 wallet_account 没有记录，抢单资格校验会通过（本项目未强制要求账户存在），
      // 抢中与否只取决于名额裁决——这正是演示想展示的
      const tokens: string[] = [];
      for (let i = 0; i < n; i++) {
        tokens.push(await login(30000 + i));
      }

      // 对齐释放：所有请求同时发出
      const t0 = performance.now();
      const results = await Promise.all(
        tokens.map(async (tok, i) => {
          const start = performance.now();
          try {
            const resp = await fetch(`/api/errands/${errandId}/grab`, {
              method: 'POST',
              headers: { 'Authorization': `Bearer ${tok}` },
            });
            const body = await resp.json();
            return {
              label: `跑腿 ${30000 + i}`,
              ok: body.code === 'OK',
              code: body.code,
              ms: Math.round(performance.now() - start),
            };
          } catch {
            return { label: `跑腿 ${30000 + i}`, ok: false, code: 'NETWORK', ms: 0 };
          }
        })
      );
      setGrabLog(results.sort((a, b) => Number(b.ok) - Number(a.ok)));
      const winners = results.filter((r) => r.ok).length;
      if (winners === 1) {
        setError(`✅ 并发演示完成：${n} 人同时抢，恰好 1 人成功（总耗时 ${Math.round(performance.now() - t0)}ms）。名额守恒，零超卖。`);
      } else {
        setError(`⚠️ ${n} 人抢单成功 ${winners} 人——若非 1，请检查后端防护！`);
      }
      load();
    } finally {
      // 恢复当前用户的 token 与身份
      restoreIdentity(myToken, myUserId);
      setGrabbing(false);
    }
  };

  return (
    <div>
      <div className="page-head">
        <h2>任务广场</h2>
        <button className="btn" onClick={load}>刷新</button>
      </div>

      {error && <div className={`banner ${error.startsWith('⚠️') ? 'warn' : 'info'}`}>{error}</div>}

      {cards.length === 0 && (
        <div className="empty">
          暂无可抢任务。<a href="#/publish">去发布一个</a>，
          或切到"发单人 1001"身份发布后再切回来抢。
        </div>
      )}

      <div className="card-grid">
        {cards.map((c) => (
          <div key={c.id} className="card-wrap">
            <ErrandCardView card={c} onChanged={load} />
            {c.status === 'PUBLISHED' && (
              <div className="spike-zone">
                <button className="btn btn-spike" disabled={grabbing}
                        onClick={() => spikeGrab(c.id, 6)}>
                  ⚡ 模拟 6 人同时抢
                </button>
                <button className="btn btn-spike" disabled={grabbing}
                        onClick={() => spikeGrab(c.id, 12)}>
                  ⚡ 模拟 12 人同时抢
                </button>
              </div>
            )}
            {targetRef.current === c.id && grabLog.length > 0 && (
              <div className="grab-log">
                <p className="grab-log-hint">
                  并发结果（浏览器同域并发连接有限，此为小并发演示；
                  2000 并发零超卖见后端 SpikeLoadClient 压测报告）：
                </p>
                {grabLog.map((r, i) => (
                  <div key={i} className={`grab-row ${r.ok ? 'win' : 'lose'}`}>
                    <span>{r.ok ? '🏆' : '✗'}</span>
                    <span>{r.label}</span>
                    <span>{r.ok ? '抢中' : r.code}</span>
                    <span className="muted">{r.ms}ms</span>
                  </div>
                ))}
              </div>
            )}
          </div>
        ))}
      </div>
    </div>
  );
}
