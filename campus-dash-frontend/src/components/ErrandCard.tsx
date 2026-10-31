import { useEffect, useState } from 'react';
import { ErrandCard as Card, STATUS_TEXT, TYPE_TEXT, TYPE_ICON, yuan, formatDeadline, countdown } from '../api';
import { displayUserName } from '../identities';
import ActionButtons from './ActionButtons';
import Icon from './Icon';

/**
 * 截止倒计时：每秒刷新。
 *
 * 只有带 expiredAt 且还没结束的任务才需要计时器，
 * 所以单独抽成组件——外面几百张卡片不会都挂一个 setInterval。
 */
function DeadlineCountdown({ expiredAt }: { expiredAt: string }) {
  const target = new Date(expiredAt).getTime();
  const [now, setNow] = useState(() => Date.now());

  useEffect(() => {
    const timer = window.setInterval(() => setNow(Date.now()), 1000);
    return () => window.clearInterval(timer);
  }, [expiredAt]);

  const left = countdown(target, now);
  return <span className={`card-deadline-countdown${left.expired ? ' is-expired' : ''}`}>
    <Icon name="clock" size={13}/>{left.expired ? '已到截止时间' : `剩余 ${left.text}`}
  </span>;
}

export default function ErrandCardView({ card, onChanged, highlight = false }: { card: Card; onChanged: () => void; highlight?: boolean }) {
  return <article className={`errand-card${highlight ? ' is-highlight' : ''}`}>
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
    {card.expiredAt && <div className="card-deadline">
      <span className="card-deadline-at"><Icon name="clock" size={13}/>截止 {formatDeadline(card.expiredAt)}</span>
      <DeadlineCountdown expiredAt={card.expiredAt}/>
    </div>}
    <div className="card-bottom">
      <div className="card-reward" title={`悬赏 ${yuan(card.rewardCents)}，佣金 ${yuan(card.commissionCents ?? 0)}，券面承担 ${yuan(card.runnerBearCents ?? 0)}`}><span className="reward-label">实收</span><strong className="reward">{yuan(card.netRewardCents ?? card.rewardCents)}</strong></div>
      <div className="card-controls"><a className="card-detail" href={`#/detail/${card.id}`}>详情<Icon name="arrow-right" size={13}/></a><ActionButtons card={card} onChanged={onChanged} compact/></div>
    </div>
  </article>;
}
