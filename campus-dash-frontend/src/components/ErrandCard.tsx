import { ErrandCard as Card, STATUS_TEXT, yuan } from '../api';
import ActionButtons from './ActionButtons';

interface Props {
  card: Card;
  onChanged: () => void;
}

export default function ErrandCardView({ card, onChanged }: Props) {
  return (
    <div className="errand-card">
      <div className="card-head">
        <a href={`#/detail/${card.id}`} className="card-title">{card.title}</a>
        <span className={`status status-${card.status}`}>{STATUS_TEXT[card.status] ?? card.status}</span>
      </div>
      <div className="card-meta">
        <span className="reward">{yuan(card.rewardCents)}</span>
        <span>名额 {card.slotTaken}/{card.slotTotal}</span>
        <span>类型 {card.type}</span>
        {card.round > 0 && <span className="round">第 {card.round + 1} 轮</span>}
        <span className="muted">#{String(card.id).slice(-8)}</span>
      </div>
      <ActionButtons card={card} onChanged={onChanged} compact />
    </div>
  );
}
