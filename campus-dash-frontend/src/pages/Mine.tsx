import { useEffect, useState } from 'react';
import { api } from '../api';
import type { ErrandCard } from '../api';
import ErrandCardView from '../components/ErrandCard';

export default function Mine() {
  const [tab, setTab] = useState<'PUBLISHED_BY_ME' | 'GRABBED_BY_ME'>('PUBLISHED_BY_ME');
  const [cards, setCards] = useState<ErrandCard[]>([]);

  const load = () => api.mine(tab).then(setCards).catch(() => {});
  useEffect(() => { load(); }, [tab]);

  return (
    <div>
      <div className="page-head">
        <h2>我的任务</h2>
        <div className="tabs">
          <button className={tab === 'PUBLISHED_BY_ME' ? 'tab active' : 'tab'}
                  onClick={() => setTab('PUBLISHED_BY_ME')}>我发布的</button>
          <button className={tab === 'GRABBED_BY_ME' ? 'tab active' : 'tab'}
                  onClick={() => setTab('GRABBED_BY_ME')}>我抢的</button>
        </div>
      </div>
      {cards.length === 0 && <div className="empty">暂无任务</div>}
      <div className="card-grid">
        {cards.map((c) => <ErrandCardView key={c.id} card={c} onChanged={load} />)}
      </div>
    </div>
  );
}
