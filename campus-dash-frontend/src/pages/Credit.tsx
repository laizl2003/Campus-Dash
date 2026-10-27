import { useCallback, useEffect, useRef, useState } from 'react';
import { api, getUserId } from '../api';
import Icon from '../components/Icon';
import { subscribeWs } from '../ws';
import { displayUserName } from '../identities';
import './accountPages.css';

export default function Credit() {
  const [self, setSelf] = useState<Awaited<ReturnType<typeof api.creditSelf>> | null>(null);
  const [ranking, setRanking] = useState<Awaited<ReturnType<typeof api.creditRanking>> | null>(null);
  const [filter, setFilter] = useState('ALL');
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState('');
  const requestId = useRef(0);

  const load = useCallback(async () => {
    const id = ++requestId.current;
    setLoading(true);
    setError('');
    const [selfResult, rankResult] = await Promise.allSettled([api.creditSelf(), api.creditRanking()]);
    if (id !== requestId.current) return;
    const errors: string[] = [];
    if (selfResult.status === 'fulfilled') setSelf(selfResult.value);
    else errors.push(`信用记录加载失败：${selfResult.reason instanceof Error ? selfResult.reason.message : '请稍后重试'}`);
    if (rankResult.status === 'fulfilled') setRanking(rankResult.value);
    else errors.push(`排行榜加载失败：${rankResult.reason instanceof Error ? rankResult.reason.message : '请稍后重试'}`);
    setError(errors.join('；'));
    setLoading(false);
  }, []);

  useEffect(() => {
    void load();
    const unsubscribe = subscribeWs((event) => { if (event.type === 'credit.changed' || event.type === 'errand.status') void load(); });
    return () => { requestId.current++; unsubscribe(); };
  }, [load]);

  const events = self?.events.filter((event) => filter === 'ALL' || (filter === 'POSITIVE' ? event.delta > 0 : event.delta < 0)) ?? [];
  const userId = String(getUserId());

  return (
    <div className="account-page">
      <div className="page-head"><div><span className="eyebrow">CAMPUS CREDIT</span><h1>靠谱，值得被看见</h1><p>每一次认真履约，都在积累校园里的信任。</p></div><button className="btn btn-ghost" onClick={() => void load()} disabled={loading}><Icon name="refresh" size={15} />{loading ? '更新中' : '刷新信用'}</button></div>
      {error && <div className="banner warn account-error" role="alert"><span>{error}</span><button className="btn btn-ghost" onClick={() => void load()} disabled={loading}>重试</button></div>}
      <section className="account-credit-overview" aria-label="我的信用概览">
        <div className="account-credit-score"><div className="account-credit-score-title"><Icon name="shield" size={18} />我的信用分</div><div className="account-score-number">{self?.score ?? '—'}<span>分</span></div><span className="account-score-window">{self ? `最近 ${self.windowDays} 天的履约记录` : loading ? '正在获取信用记录' : '信用记录暂不可用'}</span><p>信用来自每一次约定的兑现</p></div>
        <div className="account-credit-rules"><span className="eyebrow">HOW IT WORKS</span><h2>信用如何积累？</h2><p>分数根据近期事件滚动计算，履约记录清晰可查。</p><div className="account-rule-grid"><div><span className="account-rule-symbol account-positive">+2</span><strong>完成结算</strong><small>认真完成每一份托付</small></div><div><span className="account-rule-symbol account-negative">−5</span><strong>超时未确认</strong><small>接单后记得及时确认</small></div><div><span className="account-rule-symbol account-negative">−8</span><strong>争议败诉</strong><small>按约定履行任务责任</small></div></div><details className="account-credit-more"><summary>了解完整计分规则</summary><p>初始信用分为 60 分，分数范围为 0—100 分；被抢单后取消扣 10 分，送达超时扣 3 分。分数按最近 {self?.windowDays ?? 30} 天的事件滚动计算。</p></details></div>
      </section>
      <div className="account-credit-layout">
        <section className="account-surface"><div className="account-section-head"><div><h2>信用足迹</h2><p>{self ? `最近 ${self.windowDays} 天的分数变化` : '每一次变化都有据可查'}</p></div><span className="account-count">{self ? `${self.events.length} 条事件` : '等待加载'}</span></div><div className="account-filter-bar"><div className="tabs" aria-label="按信用变化筛选">{[['ALL', '全部记录'], ['POSITIVE', '加分记录'], ['NEGATIVE', '扣分记录']].map(([value, label]) => <button className={`tab${filter === value ? ' active' : ''}`} key={value} aria-pressed={filter === value} onClick={() => setFilter(value)}>{label}</button>)}</div></div>
          {loading && !self ? <div className="account-state" role="status"><span className="account-loader" /><strong>正在加载信用记录</strong></div> : !self ? <div className="account-state"><Icon name="shield" size={27} /><strong>暂时无法获取信用记录</strong><p>请重试以获取最新分数。</p></div> : events.length === 0 ? <div className="account-state"><div className="account-empty-icon"><Icon name="shield" size={30} /></div><strong>{self.events.length ? '暂无这类信用记录' : '信任，从一次履约开始'}</strong><p>{self.events.length ? '切换筛选，查看其他信用变化。' : '完成任务后，你的信用足迹会记录在这里。'}</p></div> : <div className="account-credit-events">{events.map((event, index) => <div key={`${event.time}-${event.refId}-${index}`} className="account-credit-event"><span className={`account-event-dot ${event.delta < 0 ? 'account-event-dot-negative' : ''}`}><Icon name={event.delta < 0 ? 'clock' : 'check'} size={14} /></span><div><strong>{event.description}</strong><span><time dateTime={event.time}>{new Date(event.time).toLocaleString('zh-CN', { month: '2-digit', day: '2-digit', hour: '2-digit', minute: '2-digit' })}</time>{event.refId && event.refId !== '0' && <a href={`#/detail/${event.refId}`}>任务 #{String(event.refId).slice(-8)}</a>}</span></div><b className={event.delta < 0 ? 'account-negative' : 'account-positive'}>{event.delta > 0 ? '+' : ''}{event.delta}</b></div>)}</div>}
        </section>
        <section className="account-surface account-ranking"><div className="account-section-head"><div><span className="eyebrow">CAMPUS RANKING</span><h2>校园信用榜</h2><p>当前校区 · 前 20 名</p></div><span className="account-heading-icon"><Icon name="shield" size={21} /></span></div>{loading && ranking === null ? <div className="account-state" role="status"><span className="account-loader" /><strong>正在加载榜单</strong></div> : ranking === null ? <div className="account-state"><strong>榜单暂不可用</strong><p>请稍后刷新重试。</p></div> : ranking.length === 0 ? <div className="account-state"><Icon name="user" size={28} /><strong>等待第一位上榜同学</strong><p>完成一次结算，留下你的履约记录。</p></div> : <div className="account-rank-list">{ranking.map((entry, index) => <div className={`account-rank-row${String(entry.userId) === userId ? ' account-rank-self' : ''}`} key={entry.userId}><span className={`account-rank-number${index < 3 ? ' account-rank-leading' : ''}`}>{String(index + 1).padStart(2, '0')}</span><span className="account-rank-avatar" aria-hidden="true">{String(entry.userId) === '2001' ? '泡' : <Icon name="user" size={16} />}</span><span className="account-rank-user">{entry.username || displayUserName(entry.userId)}{String(entry.userId) === userId && <small>我</small>}</span><strong>{entry.score}<small> 分</small></strong></div>)}</div>}<div className="account-footnote"><Icon name="shield" size={13} /><span>用行动，让校园里的信任多一点。</span></div></section>
      </div>
    </div>
  );
}
