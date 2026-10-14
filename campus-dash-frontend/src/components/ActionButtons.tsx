import { useRef, useState } from 'react';
import { api, ACTION_TEXT, BizError } from '../api';
import type { ErrandCard } from '../api';
import Icon from './Icon';
interface Props { card: ErrandCard; onChanged: () => void; compact?: boolean }
/** 业务操作全部由后端 availableActions 决定，前端只负责呈现和反馈。 */
export default function ActionButtons({ card, onChanged, compact }: Props) {
  const [busy, setBusy] = useState<string | null>(null);
  const lock = useRef(false);
  const [message, setMessage] = useState<{ text: string; error: boolean } | null>(null);
  const [confirming, setConfirming] = useState<string | null>(null);
  const run = async (action: string) => {
    if (lock.current) return;
    lock.current = true; setBusy(action); setMessage(null);
    try {
      let text = `${ACTION_TEXT[action]}成功`;
      let error = false;
      if (action === 'GRAB') { await api.grab(card.id); text = '抢单成功，请及时确认接单'; }
      else if (action === 'ARBITRATE') { location.hash = `#/detail/${card.id}`; return; }
      else {
        const result = await api.action(card.id, action.toLowerCase() as 'confirm');
        if (action === 'CANCEL' || action === 'SETTLE') {
          const outcome = typeof result === 'object' && result !== null && 'result' in result ? result.result : undefined;
          const expected = action === 'CANCEL' ? 'REFUNDED' : 'SETTLED';
          if (outcome === `ALREADY_${expected}`) {
            text = action === 'CANCEL' ? '该任务已取消并退款，请查看最新状态。' : '该任务已完成结算，请查看最新状态。';
          } else if (outcome !== expected) {
            text = outcome === 'CONFLICT' ? '任务状态已发生变化，请查看最新记录后重试。' : '暂时无法确认处理结果，请查看最新任务状态后再操作。';
            error = true;
          }
        }
      }
      setMessage({ text, error });
      window.dispatchEvent(new CustomEvent('dash:notice', { detail: { text: action === 'GRAB' ? '抢单成功，请前往我的任务及时确认接单。' : text, error } }));
      window.dispatchEvent(new Event('dash:changed')); onChanged();
    } catch (e) { setMessage({ text: e instanceof BizError ? e.message : '网络连接失败，请重试', error: true }); }
    finally { setBusy(null); setConfirming(null); lock.current = false; }
  };
  if (card.availableActions.length === 0 && !message) return null;
  return <div className={`actions ${compact ? 'compact' : ''}`}>
    {card.availableActions.map(a => <button key={a} disabled={busy !== null} onClick={() => ['CANCEL', 'SETTLE', 'DISPUTE'].includes(a) ? setConfirming(a) : void run(a)} className={`btn btn-${a.toLowerCase()}`}>{busy === a ? '处理中…' : ACTION_TEXT[a] ?? a}{a === 'GRAB' && <Icon name="arrow-right" size={14}/>}</button>)}
    {confirming && <div className="action-confirm"><p>{confirming === 'SETTLE' ? '确认任务已经完成？悬赏将结算给跑腿同学。' : confirming === 'CANCEL' ? '确认取消任务？托管的悬赏将退回钱包。' : '确认对本次任务发起争议？后续由平台协助处理。'}</p><button className="btn btn-primary" disabled={busy !== null} onClick={() => void run(confirming)}>确认操作</button><button className="btn" disabled={busy !== null} onClick={() => setConfirming(null)}>暂不操作</button></div>}
    {message && <span className={`action-msg ${message.error ? 'is-error' : ''}`} role="status">{message.text}</span>}
  </div>;
}
