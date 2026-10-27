import { ErrandCard as Card, STATUS_TEXT, TYPE_TEXT, TYPE_ICON, yuan } from '../api';
import { displayUserName } from '../identities';
import ActionButtons from './ActionButtons';
import Icon from './Icon';
export default function ErrandCardView({ card, onChanged }: { card: Card; onChanged: () => void }) {
  return <article className="errand-card">
    <div className="card-top">
      <div className="card-category"><span className={`category-label type-${card.type}`}><Icon name={TYPE_ICON[card.type] ?? 'package'} size={15}/>{TYPE_TEXT[card.type] ?? card.type}</span><span className="card-number" title={`任务编号 ${card.id}`}>#{String(card.id).slice(-6)}</span></div>
      <span className={`status status-${card.status}`}><i/>{STATUS_TEXT[card.status] ?? card.status}</span>
    </div>
    <a href={`#/detail/${card.id}`} className="card-title">{card.title}</a>
    <div className="card-meta">
      <span className="card-publisher"><Icon name="user" size={13}/>{card.publisherName || displayUserName(card.publisherId)}</span>
      <span className="card-slots"><Icon name="users" size={13}/>{card.slotTaken} / {card.slotTotal} 已接</span>
      {card.round > 0 && <span className="round">第 {card.round + 1} 轮</span>}
    </div>
    <div className="card-bottom">
      <div className="card-reward" title={`悬赏 ${yuan(card.rewardCents)}，佣金 ${yuan(card.commissionCents ?? 0)}，券面承担 ${yuan(card.runnerBearCents ?? 0)}`}><span className="reward-label">实收</span><strong className="reward">{yuan(card.netRewardCents ?? card.rewardCents)}</strong></div>
      <div className="card-controls"><a className="card-detail" href={`#/detail/${card.id}`}>详情<Icon name="arrow-right" size={13}/></a><ActionButtons card={card} onChanged={onChanged} compact/></div>
    </div>
  </article>;
}
