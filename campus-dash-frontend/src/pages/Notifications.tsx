import { useEffect, useState } from 'react';
import { api } from '../api';

export default function Notifications() {
  const [items, setItems] = useState<Awaited<ReturnType<typeof api.notifications>>>([]);

  useEffect(() => { api.notifications().then(setItems).catch(() => {}); }, []);

  return (
    <div>
      <h2>站内消息</h2>
      <p className="muted">
        结算/退款完成后由 RocketMQ 事务消息推送；事务消息保证"资金动作提交成功则通知必达"。
      </p>
      {items.length === 0 && <div className="empty">暂无消息</div>}
      {items.map((n) => (
        <div key={n.id} className="notif-item">
          <span className={`notif-type notif-${n.type.toLowerCase()}`}>{n.type}</span>
          <a href={`#/detail/${n.errandId}`}>{n.content}</a>
          <span className="muted">{new Date(n.time).toLocaleString()}</span>
        </div>
      ))}
    </div>
  );
}
