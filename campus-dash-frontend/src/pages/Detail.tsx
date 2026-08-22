import { useCallback, useEffect, useState } from 'react';
import { api, yuan, STATUS_TEXT, BizError } from '../api';
import type { ErrandCard, StatusChange } from '../api';
import ActionButtons from '../components/ActionButtons';
import { subscribeWs } from '../ws';

interface Props {
  errandId: string;
  onIdentityChange: (id: number) => void;
}

const TERMINAL = new Set(['SETTLED', 'CLOSED', 'CANCELLED', 'REFUNDED']);

export default function Detail({ errandId, onIdentityChange }: Props) {
  const [card, setCard] = useState<ErrandCard | null>(null);
  const [timeline, setTimeline] = useState<StatusChange[]>([]);
  const [error, setError] = useState<string | null>(null);
  const [arbitrating, setArbitrating] = useState(false);

  const load = useCallback(() => {
    api.detail(errandId).then(setCard).catch((e) => setError(e.message));
    api.timeline(errandId).then(setTimeline).catch(() => {});
  }, [errandId]);

  useEffect(() => {
    load();
    // P5：WS 推送驱动刷新（worker 触发的变更推不到，靠低频轮询兜底）
    const unsub = subscribeWs((event) => {
      if (event.type === 'errand.status'
          && String(event.payload.errandId) === String(errandId)) {
        load();
      }
    });
    // 非终态时保留低频轮询（10s）：覆盖 worker 触发的流转/自动结算
    let t: number | undefined;
    if (card && !TERMINAL.has(card.status)) {
      t = window.setInterval(load, 10000);
    }
    return () => { unsub(); if (t) window.clearInterval(t); };
  }, [load, card?.status, errandId]);

  const arbitrate = async (favor: 'RUNNER' | 'PUBLISHER') => {
    try {
      const r = await api.arbitrate(errandId, favor);
      setError(`✅ 仲裁完成：${r.result === 'SETTLED_TO_RUNNER' ? '支持跑腿，资金已结算' : '支持发单人，资金已退回'}`);
      setArbitrating(false);
      load();
    } catch (e) {
      setError(e instanceof BizError ? `❌ ${e.message}（${e.code}）` : '❌ 网络错误');
    }
  };

  if (!card) return <div className="empty">{error ?? '加载中…'}</div>;

  return (
    <div className="detail-page">
      <div className="page-head">
        <h2>{card.title}</h2>
        <span className={`status status-${card.status}`}>{STATUS_TEXT[card.status]}</span>
      </div>

      {error && <div className="banner info">{error}</div>}

      <div className="detail-grid">
        <section className="panel">
          <h3>任务信息</h3>
          <table className="kv">
            <tbody>
              <tr><td>悬赏金额</td><td className="reward">{yuan(card.rewardCents)}（发布时已托管）</td></tr>
              <tr><td>名额</td><td>{card.slotTaken}/{card.slotTotal}</td></tr>
              <tr><td>发单人</td><td>#{card.publisherId}</td></tr>
              <tr><td>当前跑腿</td><td>{card.grabberId && card.grabberId !== '-1' ? `#${String(card.grabberId).slice(-6)}` : '—'}</td></tr>
              <tr><td>流转轮次</td><td>第 {card.round + 1} 轮</td></tr>
              <tr><td>你的角色</td><td>{card.role}</td></tr>
            </tbody>
          </table>

          <h3>可执行操作（由后端 availableActions 驱动）</h3>
          <ActionButtons card={card} onChanged={load} />

          {card.availableActions.includes('ARBITRATE') && (
            <div className="arbitrate-zone">
              {!arbitrating ? (
                <button className="btn btn-dispute" onClick={() => setArbitrating(true)}>选择仲裁方向</button>
              ) : (
                <div className="arbitrate-choose">
                  <p>托管资金去向二选一（全额分账）：</p>
                  <button className="btn btn-deliver" onClick={() => arbitrate('RUNNER')}>支持跑腿 → 结算给跑腿</button>
                  <button className="btn btn-cancel" onClick={() => arbitrate('PUBLISHER')}>支持发单人 → 全额退款</button>
                </div>
              )}
            </div>
          )}
        </section>

        <section className="panel">
          <h3>状态时间线（事件溯源）</h3>
          <div className="timeline">
            {timeline.length === 0 && <p className="muted">暂无记录</p>}
            {timeline.map((s, i) => (
              <div key={i} className="timeline-item">
                <span className="timeline-time">{new Date(s.time).toLocaleTimeString()}</span>
                <span className="timeline-transit">
                  {STATUS_TEXT[s.from] ?? s.from} → <strong>{STATUS_TEXT[s.to] ?? s.to}</strong>
                </span>
                {s.round > 0 && <span className="round">第 {s.round + 1} 轮</span>}
                <span className="muted">操作者 {s.operatorId === '-1' ? '系统' : `#${String(s.operatorId).slice(-6)}`}</span>
              </div>
            ))}
          </div>
          <p className="timeline-hint">
            超时流转由 worker 的延迟消息驱动；把后端 dash.timeout.confirm-seconds
            调成 10 秒，就能看到"抢中者不确认 → 自动流转"的实时过程。
          </p>
        </section>
      </div>
    </div>
  );
}
