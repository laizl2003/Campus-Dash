import { useEffect, useState } from 'react';
import { api, yuan } from '../api';
import { subscribeWs, isWsAvailable } from '../ws';
import { IDENTITIES } from '../App';

interface Props {
  route: string;
  userId: number;
  onSwitch: (id: number) => void;
  onLogout: () => void;
}

export default function TopBar({ route, userId, onSwitch, onLogout }: Props) {
  const [unread, setUnread] = useState(0);
  const [balance, setBalance] = useState<number | null>(null);

  useEffect(() => {
    api.unread().then((r) => setUnread(r.count)).catch(() => {});
    api.wallet().then((w) => setBalance(w.availableCents)).catch(() => {});

    // P5：未读数改由 WS 推送驱动；WS 不可用时退回 5s 轮询（降级不断交互）
    const unsub = subscribeWs((event) => {
      if (event.type === 'notification.new') {
        setUnread((n) => n + 1);
      }
    });
    const t = setInterval(() => {
      if (!isWsAvailable()) {
        api.unread().then((r) => setUnread(r.count)).catch(() => {});
      }
    }, 5000);
    return () => { unsub(); clearInterval(t); };
  }, [userId, route]);

  const current = IDENTITIES.find((i) => i.userId === userId);

  return (
    <header className="topbar">
      <div className="topbar-left">
        <a className="brand" href="#/square">🛵 CampusDash</a>
        <nav>
          <a href="#/square" className={route.startsWith('/square') ? 'active' : ''}>任务广场</a>
          <a href="#/publish" className={route === '/publish' ? 'active' : ''}>发布任务</a>
          <a href="#/mine" className={route === '/mine' ? 'active' : ''}>我的任务</a>
          <a href="#/wallet" className={route === '/wallet' ? 'active' : ''}>钱包</a>
          <a href="#/credit" className={route === '/credit' ? 'active' : ''}>信用分</a>
          <a href="#/notifications" className={route === '/notifications' ? 'active' : ''}>
            消息{unread > 0 && <span className="badge">{unread}</span>}
          </a>
        </nav>
      </div>
      <div className="topbar-right">
        {balance !== null && <span className="balance">余额 {yuan(balance)}</span>}
        {/* 身份切换器：一键切换 = 重新登录换 token */}
        <select value={userId} onChange={(e) => onSwitch(Number(e.target.value))}>
          {IDENTITIES.map((i) => (
            <option key={i.userId} value={i.userId}>{i.name}</option>
          ))}
        </select>
        <button className="link-btn" onClick={onLogout}>退出</button>
      </div>
      {current && <div className="identity-hint">当前身份：{current.name} — {current.desc}</div>}
    </header>
  );
}
