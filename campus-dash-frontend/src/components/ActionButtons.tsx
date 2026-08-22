import { useState } from 'react';
import { api, ACTION_TEXT, BizError } from '../api';
import type { ErrandCard } from '../api';

interface Props {
  card: ErrandCard;
  onChanged: () => void;
  compact?: boolean;
}

/**
 * 操作按钮完全由后端 availableActions 驱动。
 * 前端没有第二份状态机——这里没有任何 "status === 'xxx' && isPublisher" 的判断，
 * 按钮存在与否由 ErrandActionResolver（后端）决定，两边不可能漂移。
 */
export default function ActionButtons({ card, onChanged, compact }: Props) {
  const [busy, setBusy] = useState<string | null>(null);
  const [message, setMessage] = useState<string | null>(null);

  const run = async (action: string) => {
    setBusy(action);
    setMessage(null);
    try {
      if (action === 'GRAB') {
        await api.grab(card.id);
        setMessage('✅ 抢单成功！');
      } else if (action === 'ARBITRATE') {
        // 仲裁在详情页展开方向选择，卡片上不做
        setMessage('请进入详情页选择仲裁方向');
        location.hash = `#/detail/${card.id}`;
        return;
      } else {
        await api.action(card.id, action.toLowerCase() as never);
        setMessage(`✅ ${ACTION_TEXT[action]}成功`);
      }
      onChanged();
    } catch (e) {
      if (e instanceof BizError) {
        setMessage(`❌ ${e.message}（${e.code}）`);
      } else {
        setMessage('❌ 网络错误');
      }
    } finally {
      setBusy(null);
    }
  };

  if (card.availableActions.length === 0) return null;

  return (
    <div className={compact ? 'actions compact' : 'actions'}>
      {card.availableActions.map((a) => (
        <button key={a} disabled={busy !== null} onClick={() => run(a)}
                className={`btn btn-${a.toLowerCase()} ${busy === a ? 'busy' : ''}`}>
          {ACTION_TEXT[a] ?? a}
        </button>
      ))}
      {message && <span className="action-msg">{message}</span>}
    </div>
  );
}
