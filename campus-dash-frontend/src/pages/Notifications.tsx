import { useCallback, useEffect, useRef, useState } from 'react';
import { api } from '../api';
import Icon from '../components/Icon';
import { subscribeWs } from '../ws';
import './accountPages.css';

const TYPE_LABELS: Record<string, string> = { SETTLED: '结算通知', REFUNDED: '退款通知' };

export default function Notifications() {
  const [items, setItems] = useState<Awaited<ReturnType<typeof api.notifications>> | null>(null);
  const [filter, setFilter] = useState('ALL');
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState('');
  const requestId = useRef(0);
  const load = useCallback(async () => {
    const id = ++requestId.current;
    setLoading(true);
    setError('');
    try {
      const next = await api.notifications();
      if (id === requestId.current) setItems(next);
    } catch (reason) {
      if (id === requestId.current) setError(reason instanceof Error ? reason.message : '消息加载失败，请稍后重试');
    } finally {
      if (id === requestId.current) setLoading(false);
    }
  }, []);

  useEffect(() => {
    void load();
    const unsubscribe = subscribeWs((event) => { if (event.type === 'notification.new') void load(); });
    return () => { requestId.current++; unsubscribe(); };
  }, [load]);

  const filtered = (items ?? []).filter((item) => filter === 'ALL' || (filter === 'OTHER' ? !(item.type in TYPE_LABELS) : item.type === filter));
  const groups = new Map<string, typeof filtered>();
  [...filtered].sort((a, b) => new Date(b.time).getTime() - new Date(a.time).getTime()).forEach((item) => {
    const key = new Date(item.time).toLocaleDateString('zh-CN', { year: 'numeric', month: 'long', day: 'numeric' });
    groups.set(key, [...(groups.get(key) ?? []), item]);
  });
  const hasOther = items?.some((item) => !(item.type in TYPE_LABELS));

  return (
    <div className="account-page">
      <div className="page-head"><div><span className="eyebrow">NOTIFICATION CENTER</span><h1>不错过每一个进展</h1><p>任务结算、退款到账，重要动态集中在这里。</p></div><button className="btn btn-ghost" disabled={loading} onClick={() => void load()}><Icon name="refresh" size={15} />{loading ? '更新中' : '刷新消息'}</button></div>
      {error && <div className="banner warn account-error" role="alert"><span>{error}</span><button className="btn btn-ghost" onClick={() => void load()} disabled={loading}>重试</button></div>}
      <div className="account-message-layout">
        <section className="account-surface">
          <div className="account-section-head"><div><h2>消息中心 <span className="account-heading-count">{items?.length ?? '—'}</span></h2><p>点击通知，查看对应任务的最新状态</p></div><div className="account-heading-icon"><Icon name="bell" size={20} /></div></div>
          <div className="account-filter-bar"><div className="tabs" aria-label="按消息类型筛选">{[['ALL', '全部消息'], ['SETTLED', '结算'], ['REFUNDED', '退款'], ...(hasOther ? [['OTHER', '其他']] : [])].map(([value, label]) => <button key={value} className={`tab${filter === value ? ' active' : ''}`} aria-pressed={filter === value} onClick={() => setFilter(value)}>{label}</button>)}</div><span className="account-count">{items === null ? '等待加载' : `${filtered.length} 条`}</span></div>
          {loading && items === null ? <div className="account-state" role="status"><span className="account-loader" /><strong>正在获取最新消息</strong></div> : items === null ? <div className="account-state"><Icon name="bell" size={27} /><strong>暂时无法加载消息</strong><p>请重试以获取最新动态。</p></div> : filtered.length === 0 ? <div className="account-state account-message-empty"><div className="account-empty-icon"><Icon name="bell" size={30} /></div><strong>{items.length ? '暂时没有这类消息' : '一切安静，等待好消息'}</strong><p>{items.length ? '你可以切换分类，查看其他任务动态。' : '任务结算或退款后，我们会在这里告诉你。'}</p>{items.length === 0 && <a href="#/" className="btn btn-ghost">去任务大厅看看 <Icon name="arrow-right" size={14} /></a>}</div> : <div className="account-message-groups">{[...groups.entries()].map(([date, messages]) => <div key={date}><h3 className="account-date-divider">{date}</h3>{messages.map((item) => <a className="account-message" key={item.id} href={`#/detail/${item.errandId}`}><span className={`account-message-icon ${item.type === 'REFUNDED' ? 'account-message-refund' : ''}`}><Icon name={item.type === 'SETTLED' ? 'check' : item.type === 'REFUNDED' ? 'wallet' : 'bell'} size={18} /></span><span className="account-message-copy"><span className="account-message-title">{TYPE_LABELS[item.type] ?? '任务通知'}<time dateTime={item.time}>{new Date(item.time).toLocaleTimeString('zh-CN', { hour: '2-digit', minute: '2-digit' })}</time></span><span className="account-message-body">{item.content}</span><span className="account-message-ref">关联任务 #{String(item.errandId).slice(-8)}</span></span><Icon name="arrow-right" size={16} /></a>)}</div>)}</div>}
        </section>
        <aside className="account-message-aside"><div className="account-aside-icon"><Icon name="bell" size={26} /></div><span className="eyebrow">STAY IN THE LOOP</span><h2>一份托付<br />始终有回应</h2><p>每一次结算，每一笔退款，<br />都在这里留下清晰的记录。</p><div className="account-aside-divider" /><a href="#/mine">查看我的任务 <Icon name="arrow-right" size={15} /></a><a href="#/wallet">查看记账流水 <Icon name="arrow-right" size={15} /></a></aside>
      </div>
    </div>
  );
}
