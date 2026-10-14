import { useCallback, useEffect, useRef, useState } from 'react';
import { api } from '../api';
import type { ErrandCard } from '../api';
import ErrandCardView from '../components/ErrandCard';
import Icon from '../components/Icon';
import { subscribeWs } from '../ws';
import './accountPages.css';

type MineRole = 'PUBLISHED_BY_ME' | 'GRABBED_BY_ME';
const FINISHED = new Set(['SETTLED', 'CLOSED', 'CANCELLED', 'REFUNDED']);

export default function Mine() {
  const [tab, setTab] = useState<MineRole>('PUBLISHED_BY_ME');
  const [cards, setCards] = useState<ErrandCard[] | null>(null);
  const [status, setStatus] = useState('ALL');
  const [query, setQuery] = useState('');
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState('');
  const requestId = useRef(0);
  const roleRef = useRef(tab);
  const load = useCallback(async () => {
    if (roleRef.current !== tab) return;
    const id = ++requestId.current;
    setLoading(true);
    setError('');
    try {
      const next = await api.mine(tab);
      // 切换角色后，上一轮返回的请求不能覆盖新列表。
      if (id === requestId.current) setCards(next);
    } catch (reason) {
      if (id === requestId.current) setError(reason instanceof Error ? reason.message : '任务加载失败，请稍后重试');
    } finally {
      if (id === requestId.current) setLoading(false);
    }
  }, [tab]);

  useEffect(() => {
    void load();
    const unsubscribe = subscribeWs((event) => { if (event.type === 'errand.status') void load(); });
    return () => { requestId.current++; unsubscribe(); };
  }, [load]);

  function changeTab(next: MineRole) {
    if (next === tab) return;
    requestId.current++;
    roleRef.current = next;
    setCards(null);
    setLoading(true);
    setError('');
    setStatus('ALL');
    setTab(next);
  }

  const active = cards?.filter((card) => !FINISHED.has(card.status) && card.status !== 'DISPUTED').length;
  const done = cards?.filter((card) => card.status === 'SETTLED').length;
  const disputed = cards?.filter((card) => card.status === 'DISPUTED').length;
  const filtered = (cards ?? []).filter((card) => {
    const matchesStatus = status === 'ALL' || (status === 'ACTIVE' ? !FINISHED.has(card.status) && card.status !== 'DISPUTED' : status === 'FINISHED' ? FINISHED.has(card.status) : card.status === status);
    const text = query.trim().toLocaleLowerCase();
    return matchesStatus && (!text || card.title.toLocaleLowerCase().includes(text) || String(card.id).includes(text));
  });

  return (
    <div className="account-page">
      <div className="page-head"><div><span className="eyebrow">MY TASKS</span><h1>每一份托付，心里有数</h1><p>跟进你发布和接下的任务，让校园里的小事顺利完成。</p></div><a className="btn btn-primary" href="#/publish"><Icon name="plus" size={16} />发布新任务</a></div>
      <div className="account-mine-switch"><div className="tabs" aria-label="我的任务角色"><button className={`tab${tab === 'PUBLISHED_BY_ME' ? ' active' : ''}`} aria-pressed={tab === 'PUBLISHED_BY_ME'} onClick={() => changeTab('PUBLISHED_BY_ME')}><Icon name="package" size={16} />我发布的</button><button className={`tab${tab === 'GRABBED_BY_ME' ? ' active' : ''}`} aria-pressed={tab === 'GRABBED_BY_ME'} onClick={() => changeTab('GRABBED_BY_ME')}><Icon name="user" size={16} />我接下的</button></div><button className="btn btn-ghost" disabled={loading} onClick={() => void load()}><Icon name="refresh" size={14} />{loading ? '更新中' : '刷新'}</button></div>
      {error && <div className="banner warn account-error" role="alert"><span>{error}</span><button className="btn btn-ghost" onClick={() => void load()} disabled={loading}>重试</button></div>}
      <div className="account-task-stats" aria-label="当前角色的任务概览">
        {[{ label: '当前加载任务', value: cards?.length, icon: 'package' }, { label: '进行中', value: active, icon: 'clock' }, { label: '已结算', value: done, icon: 'check' }, { label: '争议处理中', value: disputed, icon: 'shield' }].map((stat) => <div className="account-task-stat" key={stat.label}><span className="account-stat-symbol"><Icon name={stat.icon} size={18} /></span><div><span>{stat.label}</span><strong>{stat.value ?? '—'}<small> 项</small></strong></div></div>)}
      </div>
      <section className="account-mine-list">
        <div className="account-filter-bar account-mine-filter"><div className="tabs" aria-label="按任务状态筛选">{[['ALL', '全部'], ['ACTIVE', '进行中'], ['FINISHED', '已结束'], ['DISPUTED', '争议中']].map(([value, label]) => <button key={value} className={`tab${status === value ? ' active' : ''}`} aria-pressed={status === value} onClick={() => setStatus(value)}>{label}</button>)}</div><label className="account-search"><Icon name="search" size={16} /><input type="search" aria-label="搜索我的任务" placeholder="搜索任务名称或编号" value={query} onChange={(event) => setQuery(event.target.value)} /></label></div>
        {loading && cards === null ? <div className="account-surface account-state" role="status"><span className="account-loader" /><strong>正在整理你的任务</strong><p>稍等片刻，任务进展即将呈现。</p></div> : cards === null ? <div className="account-surface account-state"><Icon name="package" size={28} /><strong>暂时无法获取任务</strong><p>请重试以加载最新任务列表。</p></div> : filtered.length === 0 ? <div className="account-surface account-state"><div className="account-empty-icon"><Icon name="package" size={30} /></div><strong>{cards.length ? '没有找到匹配的任务' : tab === 'PUBLISHED_BY_ME' ? '把小事托付出去，把时间留给自己' : '顺路搭把手，从这里开始'}</strong><p>{cards.length ? '试试其他关键词，或切换任务状态。' : tab === 'PUBLISHED_BY_ME' ? '取快递、带一餐，发布你的第一个校园任务。' : '去任务大厅发现可以顺路完成的小事。'}</p>{cards.length ? <button className="btn btn-ghost" onClick={() => { setQuery(''); setStatus('ALL'); }}>清除筛选</button> : <a className="btn btn-primary" href={tab === 'PUBLISHED_BY_ME' ? '#/publish' : '#/'}>{tab === 'PUBLISHED_BY_ME' ? '发布第一个任务' : '浏览任务大厅'}<Icon name="arrow-right" size={15} /></a>}</div> : <><div className="account-list-caption"><span>{tab === 'PUBLISHED_BY_ME' ? '我发布的任务' : '我接下的任务'}</span><span>当前显示 {filtered.length} 项</span></div><div className="card-grid">{filtered.map((card) => <ErrandCardView key={card.id} card={card} onChanged={load} />)}</div></>}
      <div className="account-footnote account-mine-footnote"><Icon name="clock" size={13} /><span>默认加载最近 20 项任务，筛选仅作用于当前已加载记录。</span></div>
      </section>
    </div>
  );
}
