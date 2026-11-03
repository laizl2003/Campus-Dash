import { useCallback, useEffect, useRef, useState } from 'react';
import { api } from '../api';
import Icon from '../components/Icon';
import { subscribeWs } from '../ws';
import './accountPages.css';

const TYPE_LABELS: Record<string, string> = { SETTLED: '结算通知', REFUNDED: '退款通知', EXPIRED: '过期退款' };

/** 每次加载五条；列表固定高度、滚到底再拉下一批 */
const PAGE_SIZE = 5;

/**
 * 历史消息里存的是"1310 分"（分），统一显示为"13.10 元"；新消息本身已是元。
 *
 * 但"信用分被扣 5 分"里的 5 是**分值**，不是金额——早期版本无脑替换，
 * 结果把扣 5 分显示成"扣 0.05 元"。所以这里加两道闸：
 *   1. 信用相关的消息整条不做换算；
 *   2. "扣/加/减"后面跟的数字也不换算。
 */
function formatContent(content: string) {
  if (!content) return content;
  if (content.includes('信用')) return content;
  return content.replace(/(\d+)\s*分/g, (match: string, fen: string, offset: number, whole: string) => {
    const before = whole.slice(Math.max(0, offset - 3), offset);
    if (/[扣加减]/.test(before)) return match;
    return `${(Number(fen) / 100).toFixed(2)} 元`;
  });
}

export default function Notifications() {
  const [items, setItems] = useState<Awaited<ReturnType<typeof api.notifications>> | null>(null);
  // 未读总数走数据库 COUNT：列表只加载最近 20 条，不能拿列表长度当总数
  const [unreadTotal, setUnreadTotal] = useState(0);
  const [filter, setFilter] = useState('ALL');
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState('');
  // 已加载到第几页（0 开始）；滚到底加载下一批并追加
  const [page, setPage] = useState(0);
  // 拿到的条数等于页大小，就认为可能还有下一页（省一次 COUNT 查询）
  const [hasMore, setHasMore] = useState(false);
  const [loadingMore, setLoadingMore] = useState(false);
  const requestId = useRef(0);
  const scrollRef = useRef<HTMLDivElement | null>(null);

  /** append=false：重载第一页；append=true：加载下一页并追加到列表尾部 */
  const load = useCallback(async (targetPage: number, append = false) => {
    const id = ++requestId.current;
    if (append) setLoadingMore(true); else setLoading(true);
    setError('');
    try {
      const [next, unread] = await Promise.all([api.notifications(targetPage, PAGE_SIZE), api.unread()]);
      if (id === requestId.current) {
        setItems((prev) => (append && prev ? [...prev, ...next] : next));
        setUnreadTotal(unread.count);
        setHasMore(next.length === PAGE_SIZE);
        setPage(targetPage);
      }
    } catch (reason) {
      if (id === requestId.current) setError(reason instanceof Error ? reason.message : '消息加载失败，请稍后重试');
    } finally {
      if (id === requestId.current) { setLoading(false); setLoadingMore(false); }
    }
  }, []);

  // 首次进入只拉第一页
  useEffect(() => { void load(0); }, [load]);

  /** 滚到底部附近自动加载下一页；到底或正在加载时不再请求 */
  const handleScroll = () => {
    const node = scrollRef.current;
    if (!node || !hasMore || loadingMore || loading) return;
    if (node.scrollTop + node.clientHeight >= node.scrollHeight - 48) {
      void load(page + 1, true);
    }
  };

  useEffect(() => {
    // 新消息到达：停在第 1 页就原地刷新；在别的页则跳回第 1 页，避免看不到新消息
    const unsubscribe = subscribeWs((event) => {
      if (event.type !== 'notification.new') return;
      // 新消息统一回到第一页重拉，避免用户停在第 3 页看不到最新
      void load(0);
    });
    return () => { requestId.current++; unsubscribe(); };
  }, [load]);

  const unreadCount = unreadTotal;
  const filtered = (items ?? []).filter((item) => {
    if (filter === 'UNREAD') return !item.read;
    if (filter === 'ALL') return true;
    if (filter === 'OTHER') return !(item.type in TYPE_LABELS);
    return item.type === filter;
  });
  const groups = new Map<string, typeof filtered>();
  [...filtered].sort((a, b) => new Date(b.time).getTime() - new Date(a.time).getTime()).forEach((item) => {
    const key = new Date(item.time).toLocaleDateString('zh-CN', { year: 'numeric', month: 'long', day: 'numeric' });
    groups.set(key, [...(groups.get(key) ?? []), item]);
  });
  const hasOther = items?.some((item) => !(item.type in TYPE_LABELS));
  // 鼠标移入即视为已读（无需点击）；键盘 focus 同样处理，保证可访问性
  const markItemRead = (item: { id: string; read: boolean }) => {
    if (item.read) return;
    // 乐观更新：先取消高亮，再异步落库；失败也不阻塞跳转
    setItems((prev) => prev?.map((row) => row.id === item.id ? { ...row, read: true } : row) ?? prev);
    setUnreadTotal((value) => Math.max(0, value - 1));
    void api.markNotificationRead(item.id)
      .then(() => window.dispatchEvent(new Event('dash:changed')))
      .catch(() => {});
  };

  const markAllRead = () => {
    if (unreadTotal === 0) return;
    setItems((prev) => prev?.map((row) => ({ ...row, read: true })) ?? prev);
    setUnreadTotal(0);
    void api.markAllNotificationsRead()
      .then(() => window.dispatchEvent(new Event('dash:changed')))
      .catch(() => { void load(0); });
  };

  return (
    <div className="account-page">
      <div className="page-head"><div><span className="eyebrow">NOTIFICATION CENTER</span><h1>不错过每一个进展</h1><p>任务结算、退款到账，重要动态集中在这里。</p></div><div className="account-head-actions">{unreadCount > 0 && <button className="btn btn-primary" onClick={markAllRead}>全部已读（{unreadCount}）</button>}<button className="btn btn-ghost" disabled={loading} onClick={() => void load(0)}><Icon name="refresh" size={15} />{loading ? '更新中' : '刷新消息'}</button></div></div>
      {error && <div className="banner warn account-error" role="alert"><span>{error}</span><button className="btn btn-ghost" onClick={() => void load(page)} disabled={loading}>重试</button></div>}
      <div className="account-message-layout">
        <section className="account-surface">
          <div className="account-section-head"><div><h2>消息中心 {unreadCount > 0 && <span className="account-heading-count">{unreadCount}</span>}</h2><p>点击通知，查看对应任务的最新状态</p></div><div className="account-heading-icon"><Icon name="bell" size={20} /></div></div>
          <div className="account-filter-bar account-message-filter"><div className="tabs" aria-label="按消息类型筛选">{[['UNREAD', '未读'], ['ALL', '全部消息'], ['SETTLED', '结算'], ['REFUNDED', '退款'], ...(hasOther ? [['OTHER', '其他']] : [])].map(([value, label]) => <button key={value} className={`tab${filter === value ? ' active' : ''}`} aria-pressed={filter === value} onClick={() => setFilter(value)}>{label}</button>)}</div><span className="account-count">{items === null ? '等待加载' : `${filtered.length} 条`}</span></div>
          {loading && items === null ? <div className="account-state" role="status"><span className="account-loader" /><strong>正在获取最新消息</strong></div> : items === null ? <div className="account-state"><Icon name="bell" size={27} /><strong>暂时无法加载消息</strong><p>请重试以获取最新动态。</p></div> : filtered.length === 0 ? <div className="account-state account-message-empty"><div className="account-empty-icon"><Icon name="bell" size={30} /></div><strong>{items.length ? '暂时没有这类消息' : '一切安静，等待好消息'}</strong><p>{items.length ? '你可以切换分类，查看其他任务动态。' : '任务结算或退款后，我们会在这里告诉你。'}</p>{items.length === 0 && <a href="#/" className="btn btn-ghost">去任务大厅看看 <Icon name="arrow-right" size={14} /></a>}</div> : <div className="account-message-scroll" ref={scrollRef} onScroll={handleScroll}><div className="account-message-groups">{[...groups.entries()].map(([date, messages]) => <div key={date}><h3 className="account-date-divider">{date}</h3>{messages.map((item) => <a className={`account-message${item.read ? '' : ' is-unread'}`} key={item.id} href={`#/detail/${item.errandId}?from=notifications`}
               onClick={() => markItemRead(item)}
               onMouseEnter={() => markItemRead(item)}
               onFocus={() => markItemRead(item)}><span className={`account-message-icon ${item.type === 'REFUNDED' || item.type === 'EXPIRED' ? 'account-message-refund' : ''}`}><Icon name={item.type === 'SETTLED' ? 'check' : item.type === 'REFUNDED' || item.type === 'EXPIRED' ? 'wallet' : 'bell'} size={18} /></span><span className="account-message-copy"><span className="account-message-title">{TYPE_LABELS[item.type] ?? '任务通知'}<time dateTime={item.time}>{new Date(item.time).toLocaleTimeString('zh-CN', { hour: '2-digit', minute: '2-digit' })}</time></span><span className="account-message-body">{formatContent(item.content)}</span><span className="account-message-ref">关联任务 #{String(item.errandId).slice(-8)}</span></span><Icon name="arrow-right" size={16} /></a>)}</div>)}</div></div>}
          {loadingMore && <div className="account-scroll-foot">正在加载更多消息…</div>}
          {!loadingMore && hasMore && <div className="account-scroll-foot">继续下滑加载更多</div>}
        </section>
        <aside className="account-message-aside"><div className="account-aside-icon"><Icon name="bell" size={26} /></div><span className="eyebrow">STAY IN THE LOOP</span><h2>一份托付<br />始终有回应</h2><p>每一次结算，每一笔退款，<br />都在这里留下清晰的记录。</p><div className="account-aside-divider" /><a href="#/mine">查看我的任务 <Icon name="arrow-right" size={15} /></a><a href="#/wallet">查看记账流水 <Icon name="arrow-right" size={15} /></a></aside>
      </div>
    </div>
  );
}
