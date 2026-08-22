import { useEffect, useState } from 'react';
import { api } from '../api';

interface CreditSelf {
  score: number;
  windowDays: number;
  events: { type: string; description: string; delta: number; refId: string; time: string }[];
}

interface RankEntry {
  userId: string;
  score: number;
}

/**
 * 信用分页：我的分数 + 30 天事件流水 + 校区排行榜。
 * 分数规则透明展示——让用户知道"为什么是这个分"。
 */
export default function Credit() {
  const [self, setSelf] = useState<CreditSelf | null>(null);
  const [ranking, setRanking] = useState<RankEntry[]>([]);

  useEffect(() => {
    fetch('/api/credit/self', { headers: authHeaders() })
      .then((r) => r.json())
      .then((r) => r.code === 'OK' && setSelf(r.data));
    fetch('/api/credit/ranking?campusId=1&limit=20', { headers: authHeaders() })
      .then((r) => r.json())
      .then((r) => r.code === 'OK' && setRanking(r.data));
  }, []);

  return (
    <div>
      <h2>信用分</h2>
      {self && (
        <div className="panel" style={{ marginTop: 12 }}>
          <div style={{ fontSize: 40, fontWeight: 700 }}>{self.score}</div>
          <p className="muted">
            按最近 {self.windowDays} 天事件滑动计算。完成结算 +2，超时未确认 -5，
            争议败诉 -8。分数决定抢单资格与流转优先级。
          </p>
          <h3>近期事件</h3>
          {self.events.length === 0 && <p className="muted">暂无事件</p>}
          {self.events.map((e, i) => (
            <div key={i} className="timeline-item">
              <span className={e.delta > 0 ? 'credit' : 'debit'}>
                {e.delta > 0 ? '+' : ''}{e.delta}
              </span>
              <span>{e.description}</span>
              <span className="muted">{new Date(e.time).toLocaleString()}</span>
            </div>
          ))}
        </div>
      )}
      <h3 style={{ marginTop: 20 }}>校区排行榜（Top 20）</h3>
      <table className="ledger-table">
        <thead>
          <tr><th>排名</th><th>用户</th><th>信用分</th></tr>
        </thead>
        <tbody>
          {ranking.map((r, i) => (
            <tr key={r.userId}>
              <td>{i + 1}</td>
              <td>#{r.userId.slice(-6)}</td>
              <td>{r.score}</td>
            </tr>
          ))}
          {ranking.length === 0 && (
            <tr><td colSpan={3} className="muted">暂无上榜（完成一次结算即可上榜）</td></tr>
          )}
        </tbody>
      </table>
    </div>
  );
}

function authHeaders(): HeadersInit {
  const token = localStorage.getItem('dash_token');
  return token ? { Authorization: `Bearer ${token}` } : {};
}
