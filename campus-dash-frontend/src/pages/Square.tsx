import { useCallback, useEffect, useMemo, useRef, useState } from 'react';
import { api, TYPE_TEXT, TYPE_ICON, yuan } from '../api';
import type { ErrandCard } from '../api';
import { subscribeWs } from '../ws';
import ErrandCardView from '../components/ErrandCard';
import Icon from '../components/Icon';
import CampusArt from '../components/CampusArt';
const STARTERS = [
  { type: 'DELIVERY', icon: 'package', title: '快递帮我取', text: '把等待，变成顺路送达', template: '帮我从快递站取一个包裹送到宿舍' },
  { type: 'BUY', icon: 'bag', title: '好吃的帮我带', text: '食堂带饭，日常代买', template: '帮我从食堂带一份午餐到宿舍' },
  { type: 'OTHER', icon: 'receipt', title: '其他事帮个忙', text: '送份文件，解决小事', template: '帮我把一份文件送到教学楼' },
];
export default function Square() {
  const [cards, setCards] = useState<ErrandCard[]>([]);
  const [error, setError] = useState('');
  const [loading, setLoading] = useState(true);
  const [updatedAt, setUpdatedAt] = useState<Date | null>(null);
  const [query, setQuery] = useState('');
  const [category, setCategory] = useState('ALL');
  const [sort, setSort] = useState('default');
  const requestId = useRef(0);
  const load = useCallback(async () => {
    const id = ++requestId.current;
    setLoading(true); setError('');
    try { const result = await api.list(1); if (id === requestId.current) { setCards(result); setUpdatedAt(new Date()); } }
    catch (e) { if (id === requestId.current) setError(e instanceof Error ? e.message : '任务加载失败'); }
    finally { if (id === requestId.current) setLoading(false); }
  }, []);
  useEffect(() => {
    void load();
    const unsub = subscribeWs(event => { if (event.type === 'errand.status') void load(); });
    const timer = window.setInterval(() => { if (!document.hidden) void load(); }, 30000);
    return () => { requestId.current++; unsub(); clearInterval(timer); };
  }, [load]);
  const filtered = useMemo(() => {
    const result = cards.filter(c => (category === 'ALL' || c.type === category) && `${c.title} ${c.id}`.toLowerCase().includes(query.trim().toLowerCase()));
    if (sort === 'reward') result.sort((a, b) => b.rewardCents - a.rewardCents);
    return result;
  }, [cards, category, query, sort]);
  const available = cards.filter(c => c.status === 'PUBLISHED');
  const totalReward = cards.reduce((sum, c) => sum + c.rewardCents, 0);
  return <div className="square-page">
    <div className="page-head"><div><span className="eyebrow">CAMPUS LIFE, MADE EASIER</span><h1>任务广场<span className="heading-dot">.</span></h1><p>发现校园里的小委托，让每一段顺路都有价值。</p></div><button className="btn btn-ghost" onClick={() => void load()} disabled={loading}><Icon name="refresh" size={15} className={loading ? 'is-spinning' : ''}/>刷新任务</button></div>
    <section className="square-hero"><div className="hero-copy"><span className="soft-label"><Icon name="spark" size={13}/>把小事托付，把时间留给自己</span><h2>刚好顺路，<br/><em>刚好帮你。</em></h2><p>一份热饭、一个包裹、一件小事。<br/>在校园，让互相帮助变得简单。</p><a href="#/publish" className="btn btn-primary">发布我的委托<Icon name="arrow-right" size={15}/></a></div><CampusArt/><span className="hero-caption">THE LITTLE THINGS MATTER</span></section>
    <div className="service-shortcuts">{STARTERS.map(s => <a key={s.type} href={`#/publish?type=${s.type}&template=${encodeURIComponent(s.template)}`}><span className={`shortcut-icon type-${s.type}`}><Icon name={s.icon} size={20}/></span><span><strong>{s.title}</strong><small>{s.text}</small></span><Icon name="arrow-right" size={16}/></a>)}</div>
    <div className="square-workspace"><section className="task-market" aria-label="任务列表">
      <div className="section-title"><h2>发现任务 <span>{loading && !updatedAt ? '—' : cards.length}</span></h2><span className="update-time">{updatedAt ? `${updatedAt.toLocaleTimeString('zh-CN', { hour: '2-digit', minute: '2-digit' })} 更新` : '等待加载'}</span></div>
      <div className="market-toolbar"><div className="category-tabs" aria-label="任务分类">{[['ALL', '全部任务'], ...Object.entries(TYPE_TEXT)].map(([key, label]) => <button key={key} className={category === key ? 'active' : ''} aria-pressed={category === key} onClick={() => setCategory(key)}>{label}</button>)}</div><div className="market-search-row"><label className="search-field"><Icon name="search" size={16}/><input aria-label="搜索任务" placeholder="搜索任务名称或编号" value={query} onChange={e => setQuery(e.target.value)}/>{query && <button aria-label="清除搜索" onClick={() => setQuery('')}><Icon name="close" size={14}/></button>}</label><select aria-label="任务排序" value={sort} onChange={e => setSort(e.target.value)}><option value="default">默认排序</option><option value="reward">悬赏从高到低</option></select></div></div>
      {error && <div className="banner warn" role="alert"><Icon name="info" size={17}/><span>{error}{updatedAt && '。下方保留上次加载的任务。'}</span><button className="text-button" onClick={() => void load()}>重试</button></div>}
      {loading && !updatedAt ? <div className="card-grid" aria-label="正在加载任务">{[0, 1, 2, 3].map(n => <div key={n} className="skeleton-card"><i/><i/><i/><i/></div>)}</div> : filtered.length > 0 ? <div className="card-grid">{filtered.map(c => <ErrandCardView key={c.id} card={c} onChanged={() => void load()}/>)}</div> : !error && <div className="empty-state"><span className="empty-icon"><Icon name={query || category !== 'ALL' ? 'search' : 'package'} size={30}/></span><h3>{query || category !== 'ALL' ? '没有找到匹配的任务' : '新的委托，正在路上'}</h3><p>{query || category !== 'ALL' ? '换个关键词，或者看看其他类型的委托。' : '暂时还没有可浏览的任务，你也可以发布第一份委托。'}</p>{query || category !== 'ALL' ? <button className="btn" onClick={() => { setQuery(''); setCategory('ALL'); }}>清除筛选</button> : <a className="btn btn-primary" href="#/publish"><Icon name="plus" size={16}/>发布任务</a>}</div>}
      {updatedAt && cards.length > 0 && <p className="list-footnote">{query || category !== 'ALL' ? `匹配 ${filtered.length} 条 · ` : ''}展示当前加载的 {cards.length} 条任务 · 名额与操作以最新状态为准</p>}
    </section><aside className="market-aside">
      <section className="overview-panel"><div className="section-title"><h3>广场一览</h3><Icon name="grid" size={16}/></div><p>当前已加载任务</p><div className="overview-numbers"><div><strong>{updatedAt ? available.length : '—'}</strong><span>等待接单</span></div><div><strong>{updatedAt ? yuan(totalReward) : '—'}</strong><span>悬赏合计</span></div></div><div className="overview-types">{Object.entries(TYPE_TEXT).map(([type, label]) => <div key={type}><span><Icon name={TYPE_ICON[type]} size={14}/>{label}</span><strong>{updatedAt ? cards.filter(c => c.type === type).length : '—'}</strong></div>)}</div></section>
      <section className="how-it-works"><span className="soft-label"><Icon name="shield" size={14}/>安心托付</span><h3>每一份委托，<br/>都有清晰的下一步。</h3><ol><li><span>01</span><div><strong>发布与托管</strong><p>说清需求，悬赏由平台托管。</p></div></li><li><span>02</span><div><strong>接单与送达</strong><p>及时确认，任务进度随时可查。</p></div></li><li><span>03</span><div><strong>确认与结算</strong><p>完成后确认，悬赏结算给同学。</p></div></li></ol><a href="#/mine">查看我的任务<Icon name="arrow-right" size={14}/></a></section>
    </aside></div>
  </div>;
}
