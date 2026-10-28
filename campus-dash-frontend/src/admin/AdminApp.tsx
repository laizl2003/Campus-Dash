import { useCallback, useEffect, useRef, useState } from 'react';
import { api, logout, yuan, getUsername, getUserId, listAccounts, switchAccount, type StoredAccount } from '../api';
import Icon from '../components/Icon';
import SquareChat from '../components/SquareChat';
import { subscribeWs } from '../ws';
import './admin.css';

/** 管理台时间格式：YYYY-MM-DD HH:mm:ss */
function formatDateTime(value: string): string {
  const date = new Date(value);
  if (Number.isNaN(date.getTime())) return '';
  const pad = (n: number) => String(n).padStart(2, '0');
  return `${date.getFullYear()}-${pad(date.getMonth() + 1)}-${pad(date.getDate())} ` +
    `${pad(date.getHours())}:${pad(date.getMinutes())}:${pad(date.getSeconds())}`;
}

interface Dispute {
  errandId: string; title: string; status: string; rewardCents: number;
  publisherId: string; publisherName?: string; grabberId: string; grabberName?: string; round: number;
  escrowAmountCents?: number; escrowStatus?: string;
  voucherDescription?: string; voucherDiscountCents?: number; runnerBearCents?: number;
}

/**
 * 管理台：仲裁工作台。
 *
 * 权限不在前端：即便有人把 dash_role 改成 Admin 进来，
 * 这里所有 /api/admin/** 请求都会被后端 AdminInterceptor 403 拦下，
 * 页面只会显示错误，拿不到任何数据。
 */
export default function AdminApp() {
  const [accounts, setAccounts] = useState<StoredAccount[]>(() => listAccounts());
  const [switchOpen, setSwitchOpen] = useState(false);
  const [switchBusy, setSwitchBusy] = useState(false);
  const [switchError, setSwitchError] = useState('');
  const switchRef = useRef<HTMLDivElement>(null);
  const [list, setList] = useState<Dispute[]>([]);
  const [arbitrations, setArbitrations] = useState<Awaited<ReturnType<typeof api.adminArbitrations>>['items']>([]);
  const [selected, setSelected] = useState<Dispute | null>(null);
  const [loading, setLoading] = useState(true);
  const [busy, setBusy] = useState(false);
  const [pendingFavor, setPendingFavor] = useState<'RUNNER' | 'PUBLISHER' | null>(null);
  const [message, setMessage] = useState<{ text: string; error: boolean } | null>(null);
  const [view, setView] = useState<'disputes' | 'ledger' | 'vouchers' | 'settings'>('disputes');
  const [netCents, setNetCents] = useState(0);
  const [ledgerAccount, setLedgerAccount] = useState<'COMMISSION' | 'MARKETING'>('COMMISSION');

  const load = useCallback(async () => {
    setLoading(true);
    try {
      const [disputes, history] = await Promise.all([api.adminDisputes(), api.adminArbitrations(0, 10)]);
      setList(disputes);
      setArbitrations(history.items);
      setMessage(null);
    } catch (e) {
      setMessage({ text: e instanceof Error ? e.message : '待仲裁任务加载失败', error: true });
    } finally {
      setLoading(false);
    }
  }, []);

  useEffect(() => {
    void load();
    // 与普通页面共用 App 建立的同一条 /ws 连接，不另开管理端连接
    return subscribeWs(event => { if (event.type === 'admin.dispute') void load(); });
  }, [load]);
  // 兜底轮询：WS 断线/事件丢失时，5 秒内也能看到新争议
  useEffect(() => {
    if (view !== 'disputes') return;
    const timer = window.setInterval(() => { void load(); }, 5000);
    return () => window.clearInterval(timer);
  }, [view, load]);
  useEffect(() => {
    if (view !== 'ledger') return;
    Promise.all([
      api.adminLedger('COMMISSION', 'ALL', 0, 1),
      api.adminLedger('MARKETING', 'ALL', 0, 1),
    ]).then(([commission]) => setNetCents(commission.netCents)).catch(() => {});
  }, [view]);

  const open = async (id: string) => {
    try {
      setPendingFavor(null);
      setSelected(await api.adminDispute(id));
    } catch (e) {
      setMessage({ text: e instanceof Error ? e.message : '详情加载失败', error: true });
    }
  };

  useEffect(() => {
    const sync = () => setAccounts(listAccounts());
    window.addEventListener('dash:accounts', sync);
    window.addEventListener('storage', sync);
    return () => { window.removeEventListener('dash:accounts', sync); window.removeEventListener('storage', sync); };
  }, []);
  useEffect(() => {
    if (!switchOpen) return;
    const onDown = (event: MouseEvent) => {
      if (switchRef.current && !switchRef.current.contains(event.target as Node)) setSwitchOpen(false);
    };
    document.addEventListener('mousedown', onDown);
    return () => document.removeEventListener('mousedown', onDown);
  }, [switchOpen]);
  const switchTo = async (id: string) => {
    if (switchBusy || id === getUserId()) { setSwitchOpen(false); return; }
    setSwitchBusy(true);
    setSwitchError('');
    try { await switchAccount(id); }
    catch (e) { setSwitchError(e instanceof Error ? e.message : '切换失败'); setSwitchOpen(false); }
    finally { setSwitchBusy(false); }
  };

  const arbitrate = async (favor: 'RUNNER' | 'PUBLISHER') => {
    if (!selected || busy) return;
    setBusy(true);
    setPendingFavor(null);
    try {
      const result = await api.adminArbitrate(selected.errandId, favor);
      setMessage({ text: `裁决完成：${result.result === 'SETTLED' ? '已结算给跑腿' : '已退款给发单人'}`, error: false });
      setSelected(null);
      await load();
    } catch (e) {
      setMessage({ text: e instanceof Error ? e.message : '裁决失败，请稍后重试', error: true });
    } finally {
      setBusy(false);
    }
  };

  return <div className="admin-shell">
    <aside className="admin-side">
      <div className="admin-brand"><span className="admin-brand-mark">C</span><div><strong>CampusDash</strong><small>平台管理台</small></div></div>
      <nav className="admin-nav" aria-label="管理导航">
        <button type="button" className={view === 'disputes' ? 'is-active' : ''} onClick={() => setView('disputes')}><Icon name="shield" size={16}/>仲裁工作台{list.length > 0 && <span className="admin-nav-count">{list.length}</span>}</button>
        <button type="button" className={view === 'ledger' ? 'is-active' : ''} onClick={() => setView('ledger')}><Icon name="receipt" size={16}/>资金流水</button>
        <button type="button" className={view === 'vouchers' ? 'is-active' : ''} onClick={() => setView('vouchers')}><Icon name="ticket" size={16}/>优惠券管理</button>
        <button type="button" className={view === 'settings' ? 'is-active' : ''} onClick={() => setView('settings')}><Icon name="wallet" size={16}/>平台配置</button>
      </nav>
      <div className="admin-chat-slot"><SquareChat compactBottom={false}/></div>
      <div className="admin-side-foot">
        <div className="admin-account" ref={switchRef}>
          <button type="button" className="admin-account-trigger" disabled={switchBusy}
                  aria-haspopup="menu" aria-expanded={switchOpen} onClick={() => setSwitchOpen(v => !v)}>
            <span className="menu-avatar">{(getUsername() ?? 'Admin').slice(0, 1).toUpperCase()}</span>
            <span className="menu-account"><strong>{getUsername() ?? 'Admin'}</strong><small>管理员</small></span>
            <span className="profile-caret">⌄</span>
          </button>
          {switchOpen && <div className="account-menu admin-account-menu" role="menu" aria-label="切换账号">
            <div className="account-menu-head"><small>切换账号</small></div>
            {accounts.map(a => <button key={a.userId} type="button" role="menuitem"
                    className={a.userId === getUserId() ? 'is-current' : ''}
                    onClick={() => void switchTo(a.userId)}>
              <span className="menu-avatar">{a.username.slice(0, 1).toUpperCase()}</span>
              <span className="menu-account"><strong>{a.username}</strong><small>{a.role === 'Admin' ? '管理员' : '普通用户'}{a.userId === getUserId() ? ' · 当前账号' : ''}</small></span>
              {a.userId === getUserId() && <Icon name="check" size={14}/>}
            </button>)}
            <a className="account-menu-link" href="#/login" onClick={() => setSwitchOpen(false)}><Icon name="arrow-left" size={14}/>登录其他账号</a>
          </div>}
          {switchError && <span className="admin-switch-error">{switchError}</span>}
        </div>
        <button type="button" className="admin-logout" onClick={logout}><Icon name="logout" size={14}/>退出</button>
      </div>
    </aside>

    <div className="admin-main">
      <header className="admin-top">
        <div><span className="eyebrow">ADMIN CONSOLE</span><h1>{view === 'disputes' ? '仲裁工作台' : view === 'vouchers' ? '优惠券管理' : view === 'settings' ? '平台配置' : '平台资金流水'}<span className="heading-dot">.</span></h1>
          <p>{view === 'disputes' ? '处理争议任务，决定托管资金流向跑腿或发单人。' : view === 'vouchers' ? '维护满减券的类型、库存与上下架状态。' : view === 'settings' ? '调整佣金与优惠券抽成比例，查看平台用户。' : '佣金账户与营销账户的每一笔入账/出账。'}</p></div>
        {view !== 'vouchers' && view !== 'settings' && <button type="button" className="btn btn-ghost" onClick={() => void load()} disabled={loading}>
          <Icon name="refresh" size={15} className={loading ? 'is-spinning' : ''}/>刷新
        </button>}
      </header>

      {message && <div className={`banner ${message.error ? 'warn' : 'ok'}`} role="status">
        <Icon name={message.error ? 'info' : 'check'} size={17}/><span>{message.text}</span>
        <button className="text-button" onClick={() => setMessage(null)}>关闭</button>
      </div>}

      {view === 'disputes' ? <div className="admin-body">
        <section className="admin-list" aria-label="待仲裁任务">
          <div className="admin-list-head"><h2>待仲裁 <span className="admin-pending-count">{list.length}</span></h2><small>按任务编号倒序</small></div>
          {loading && list.length === 0 && <div className="admin-empty">正在加载…</div>}
          {!loading && list.length === 0 && <div className="admin-empty">
            <Icon name="check" size={26}/><strong>没有待处理的争议</strong><p>任务送达后发单人确认前出现的争议会出现在这里。</p>
          </div>}
          <ul>
            {list.map(item => <li key={item.errandId}>
              <button type="button" className={`admin-row${selected?.errandId === item.errandId ? ' is-active' : ''}`} onClick={() => void open(item.errandId)}>
                <span className="admin-row-title">{item.title}</span>
                <span className="admin-row-meta">#{item.errandId} · 悬赏 {yuan(item.rewardCents)} · 第 {item.round} 轮</span>
                <span className="admin-row-parties">发单 {item.publisherName ?? item.publisherId} → 跑腿 {item.grabberName ?? item.grabberId ?? '—'}</span>
              </button>
            </li>)}
          </ul>
        </section>

        <section className="admin-detail" aria-label="争议详情">
          {!selected ? <div className="admin-empty tall"><Icon name="info" size={28}/><strong>选择左侧任务查看详情</strong><p>裁决会立即触发资金结算或退款，且不可撤销。</p></div>
          : <>
            <div className="admin-detail-head">
              <div><span className="eyebrow">DISPUTE #{selected.errandId}</span><h2>{selected.title}</h2></div>
              <span className={`admin-status is-${selected.status.toLowerCase()}`}>{selected.status}</span>
            </div>
            <dl className="admin-facts">
              <div><dt>悬赏</dt><dd>{yuan(selected.rewardCents)}</dd></div>
              <div><dt>托管金额</dt><dd>{selected.escrowAmountCents == null ? '—' : yuan(selected.escrowAmountCents)}</dd></div>
              <div><dt>托管状态</dt><dd>{selected.escrowStatus ?? '—'}</dd></div>
              <div><dt>发单人</dt><dd>{selected.publisherName ?? selected.publisherId}</dd></div>
              <div><dt>跑腿</dt><dd>{selected.grabberName ?? selected.grabberId ?? '—'}</dd></div>
              <div><dt>轮次</dt><dd>第 {selected.round} 轮</dd></div>
            </dl>
            {selected.voucherDescription && <div className="admin-voucher">
              <Icon name="ticket" size={15}/><span>本单使用 {selected.voucherDescription}，平台补贴 {yuan((selected.voucherDiscountCents ?? 0) - (selected.runnerBearCents ?? 0))}</span>
            </div>}
            <div className="admin-actions">
              <button type="button" className="btn btn-primary" disabled={busy}
                      onClick={() => setPendingFavor('RUNNER')}>
                <Icon name="check" size={16}/>支持跑腿 · 正常结算
              </button>
              <button type="button" className="btn" disabled={busy}
                      onClick={() => setPendingFavor('PUBLISHER')}>
                <Icon name="arrow-left" size={16}/>支持发单人 · 全额退款
              </button>
            </div>
            {pendingFavor && <div className="admin-confirm" role="alertdialog" aria-label="确认裁决">
              <p>
                {pendingFavor === 'RUNNER'
                  ? `确认支持跑腿？托管资金将按 8% 佣金结算给跑腿，任务变为 SETTLED。`
                  : `确认支持发单人？托管金额将全额退回，优惠券恢复可用（已过期则置为失效）。`}
              </p>
              <div>
                <button type="button" className="btn btn-primary" disabled={busy}
                        onClick={() => void arbitrate(pendingFavor)}>
                  {busy ? '处理中…' : '确认裁决'}
                </button>
                <button type="button" className="btn" disabled={busy}
                        onClick={() => setPendingFavor(null)}>取消</button>
              </div>
            </div>}
            <p className="admin-hint">裁决前请核对双方描述与送达凭证；资金动作在同一个事务里完成。</p>
          </>}
        </section>
        <section className="admin-arbitration-history" aria-label="仲裁记录">
          <div className="admin-list-head"><h2>仲裁记录 <span>{arbitrations.length}</span></h2><small>最近 10 条</small></div>
          {arbitrations.length === 0 ? <div className="admin-empty">暂无仲裁记录</div>
            : <div className="admin-ledger-scroll"><table>
              <thead><tr><th>时间</th><th>任务</th><th>裁决</th><th>金额</th><th>操作人</th><th>结果</th></tr></thead>
              <tbody>{arbitrations.map(row => <tr key={row.id}>
                <td>{formatDateTime(row.createdAt)}</td>
                <td>{row.errandTitle ?? row.errandId}</td>
                <td>{row.favor === 'RUNNER' ? '支持跑腿 · 结算' : row.favor === 'PUBLISHER' ? '支持发单人 · 退款' : '—'}</td>
                <td>{yuan(row.amountCents ?? 0)}</td>
                <td>{row.operatorName}</td>
                <td>{row.result === 'SUCCESS' ? '成功' : row.result}</td>
              </tr>)}</tbody>
            </table></div>}
        </section>
      </div> : view === 'vouchers' ? <VoucherManager/> : view === 'settings' ? <SettingsPanel/> : <div className="admin-ledger-view">
        <div className={`admin-net ${netCents >= 0 ? 'in' : 'out'}`}>
          <span>平台净收入</span>
          <strong>{yuan(netCents)}</strong>
          <small>Commission 收入 − Marketing 支出</small>
        </div>
        <div className="admin-account-tabs" role="tablist" aria-label="选择账户">
          <button type="button" role="tab" aria-selected={ledgerAccount === 'COMMISSION'}
                  className={ledgerAccount === 'COMMISSION' ? 'is-active' : ''}
                  onClick={() => setLedgerAccount('COMMISSION')}>佣金账户</button>
          <button type="button" role="tab" aria-selected={ledgerAccount === 'MARKETING'}
                  className={ledgerAccount === 'MARKETING' ? 'is-active' : ''}
                  onClick={() => setLedgerAccount('MARKETING')}>营销账户</button>
        </div>
        <LedgerPanel key={ledgerAccount} account={ledgerAccount}
                     title={ledgerAccount === 'COMMISSION' ? '佣金账户' : '营销账户'} />
      </div>}
    </div>
  </div>;
}

type Period = 'WEEK' | 'MONTH' | 'YEAR' | 'ALL';

const PERIOD_OPTIONS: [Period, string][] = [
  ['WEEK', '近一周'],
  ['MONTH', '一个月'],
  ['YEAR', '一年'],
  ['ALL', '历史'],
];

function LedgerPanel({ account, title }: { account: 'COMMISSION' | 'MARKETING'; title: string }) {
  const [period, setPeriod] = useState<Period>('WEEK');
  const [page, setPage] = useState(0);
  const [data, setData] = useState<Awaited<ReturnType<typeof api.adminLedger>> | null>(null);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState('');

  useEffect(() => {
    let active = true;
    setLoading(true); setError('');
    api.adminLedger(account, period, page)
      .then(next => { if (active) setData(next); })
      .catch(e => { if (active) setError(e instanceof Error ? e.message : '流水加载失败'); })
      .finally(() => { if (active) setLoading(false); });
    return () => { active = false; };
  }, [account, period, page]);

  const totalPages = data ? Math.max(1, Math.ceil(data.total / Math.max(data.size, 1))) : 1;

  return <section className="admin-ledger-panel" aria-label={title}>
    <div className="admin-ledger-panel-head">
      <div><h2>{title}</h2><small>{data ? `余额 ${yuan(data.account.balanceCents)}` : '—'}</small></div>
      <div className="admin-periods">{PERIOD_OPTIONS.map(([value, label]) =>
        <button key={value} type="button" className={period === value ? 'is-active' : ''}
                onClick={() => { setPeriod(value); setPage(0); }}>{label}</button>)}</div>
    </div>
    <div className="admin-ledger-summary">
      <span className="in">收入 +{yuan(data?.summary.incomeCents ?? 0)}</span>
      <span className="out">支出 -{yuan(data?.summary.expenseCents ?? 0)}</span>
      <span className="muted">共 {data?.total ?? 0} 条</span>
    </div>
    {error && <div className="banner warn" role="alert">{error}</div>}
    {loading && !data ? <div className="admin-empty">正在加载…</div>
      : !data || data.items.length === 0 ? <div className="admin-empty">该区间暂无流水</div>
      : <div className="admin-ledger-scroll"><table>
        <thead><tr><th>时间</th><th>方向</th><th>金额</th><th>余额</th><th>业务号</th><th>类型</th></tr></thead>
        <tbody>{data.items.map(row => <tr key={row.id}>
          <td>{new Date(row.createdAt).toLocaleString('zh-CN', { month: '2-digit', day: '2-digit', hour: '2-digit', minute: '2-digit' })}</td>
          <td className={row.direction === 'CREDIT' ? 'in' : 'out'}>{row.direction === 'CREDIT' ? '入账' : '出账'}</td>
          <td className={row.direction === 'CREDIT' ? 'in' : 'out'}>{row.direction === 'CREDIT' ? '' : '-'}{yuan(row.amountCents)}</td>
          <td>{yuan(row.balanceAfterCents)}</td>
          <td className="mono">{row.bizNo}</td>
          <td>{row.refType}</td>
        </tr>)}</tbody>
      </table></div>}
    <div className="admin-pager">
      <button type="button" className="btn btn-ghost" disabled={page <= 0 || loading} onClick={() => setPage(p => Math.max(0, p - 1))}>上一页</button>
      <span>第 {page + 1} / {totalPages} 页</span>
      <button type="button" className="btn btn-ghost" disabled={page + 1 >= totalPages || loading} onClick={() => setPage(p => p + 1)}>下一页</button>
    </div>
  </section>;
}

function VoucherManager() {
  const [items, setItems] = useState<Awaited<ReturnType<typeof api.adminVouchers>>>([]);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState('');
  const [message, setMessage] = useState('');
  const [threshold, setThreshold] = useState('10');
  const [discount, setDiscount] = useState('2');
  const [stock, setStock] = useState('100');
  const [busy, setBusy] = useState(false);
  const [drafts, setDrafts] = useState<Record<number, string>>({});
  const [pendingDelete, setPendingDelete] = useState<{ type: number; description: string } | null>(null);
  const [toast, setToast] = useState<{ text: string; kind: 'ok' | 'danger' } | null>(null);

  useEffect(() => {
    if (!toast) return;
    const timer = window.setTimeout(() => setToast(null), 2600);
    return () => window.clearTimeout(timer);
  }, [toast]);

  const load = useCallback(async () => {
    setLoading(true); setError('');
    try { setItems(await api.adminVouchers()); }
    catch (e) { setError(e instanceof Error ? e.message : '优惠券加载失败'); }
    finally { setLoading(false); }
  }, []);

  useEffect(() => { void load(); }, [load]);

  const create = async () => {
    const t = Number(threshold), d = Number(discount), s = Number(stock);
    if (!Number.isFinite(t) || !Number.isFinite(d) || !Number.isFinite(s) || t <= 0 || d <= 0 || s < 0) {
      setError('请输入有效的满减金额、优惠金额与库存'); return;
    }
    if (d >= t) { setError('优惠金额必须小于满减金额'); return; }
    setBusy(true); setError(''); setMessage('');
    try {
      await api.adminCreateVoucher(Math.round(t * 100), Math.round(d * 100), Math.round(s));
      setMessage('');
      setToast({ text: '新增成功', kind: 'ok' });
      await load();
    } catch (e) { setError(e instanceof Error ? e.message : '新增失败'); }
    finally { setBusy(false); }
  };

  const toggle = async (type: number, status: string) => {
    setError('');
    try {
      await api.adminUpdateVoucherStatus(type, status === 'ONLINE' ? 'OFFLINE' : 'ONLINE');
      await load();
    } catch (e) { setError(e instanceof Error ? e.message : '状态更新失败'); }
  };

  const confirmDelete = async () => {
    if (!pendingDelete) return;
    setError('');
    try {
      await api.adminDeleteVoucher(pendingDelete.type);
      setPendingDelete(null);
      setToast({ text: '删除成功', kind: 'danger' });
      await load();
    } catch (e) {
      setError(e instanceof Error ? e.message : '删除失败');
      setPendingDelete(null);
    }
  };

  const saveStock = async (type: number, fallback: number) => {
    const value = Number(drafts[type] ?? fallback);
    if (!Number.isFinite(value) || value < 0) { setError('库存不能为负'); return; }
    setError('');
    try {
      await api.adminUpdateVoucherStock(type, Math.round(value));
      setDrafts(prev => { const next = { ...prev }; delete next[type]; return next; });
      await load();
    } catch (e) { setError(e instanceof Error ? e.message : '库存更新失败'); }
  };

  return <div className="admin-voucher-view">
    <section className="admin-voucher-create">
      <div className="admin-list-head"><div><h2>新增优惠券</h2><small>金额单位：元</small></div></div>
      <div className="admin-voucher-form">
        <label>满减金额<input type="number" min="0" step="0.01" value={threshold} onChange={e => setThreshold(e.target.value)}/></label>
        <label>优惠金额<input type="number" min="0" step="0.01" value={discount} onChange={e => setDiscount(e.target.value)}/></label>
        <label>库存<input type="number" min="0" step="1" value={stock} onChange={e => setStock(e.target.value)}/></label>
        <button type="button" className="btn btn-primary" disabled={busy} onClick={() => void create()}>
          {busy ? '提交中…' : '新增优惠券'}
        </button>
      </div>
      {message && <div className="banner ok" role="status">{message}</div>}
      {error && <div className="banner warn" role="alert">{error}</div>}
    </section>

    <section className="admin-voucher-list">
      <div className="admin-list-head"><h2>优惠券列表 <span>{items.length}</span></h2><small>删除会同时清除该类型的全部领取记录</small></div>
      {toast && <div className={`floating-notice ${toast.kind === 'ok' ? 'is-success' : 'is-error'}`} role="status">
        <div className="floating-notice-card">
          <span className="floating-notice-icon">{toast.kind === 'ok' ? '✓' : '!'}</span>
          <div className="floating-notice-body"><strong>{toast.text}</strong></div>
          <button type="button" className="floating-notice-close" aria-label="关闭提示" onClick={() => setToast(null)}>×</button>
        </div>
      </div>}
      {pendingDelete && <div className="admin-voucher-confirm" role="alertdialog">
        <span>确认删除「{pendingDelete.description}」？该类型全部领取记录会一并删除。</span>
        <button type="button" className="btn btn-danger" onClick={() => void confirmDelete()}>确认删除</button>
        <button type="button" className="btn" onClick={() => setPendingDelete(null)}>取消</button>
      </div>}
      {loading ? <div className="admin-empty">正在加载…</div>
        : items.length === 0 ? <div className="admin-empty">还没有优惠券，先在上方新增一张。</div>
        : <div className="admin-voucher-grid">
          {items.map(item => {
            const online = item.status === 'ONLINE';
            return <div key={item.type} className={`admin-voucher-card${online ? '' : ' is-offline'}`}>
              <div className="admin-voucher-card-top">
                <span className="admin-voucher-type">编号 {item.type}</span>
                <span className={`admin-voucher-status ${online ? 'is-online' : 'is-offline'}`}>{online ? '上架' : '下架'}</span>
              </div>
              <strong className="admin-voucher-rule">{item.description}</strong>
              <div className="admin-voucher-stock-cell">
                <span>库存</span>
                <input className="admin-stock-input" type="number" min="0" step="1"
                       value={drafts[item.type] ?? String(item.stock)}
                       onChange={e => setDrafts(prev => ({ ...prev, [item.type]: e.target.value }))}/>
                <button type="button" className="btn btn-ghost" onClick={() => void saveStock(item.type, item.stock)}>保存</button>
                <button type="button" className="btn btn-ghost" onClick={() => void toggle(item.type, item.status)}>
                  {online ? '下架' : '上架'}
                </button>
                <button type="button" className="btn btn-ghost admin-danger"
                        onClick={() => setPendingDelete({ type: item.type, description: item.description })}>删除</button>
              </div>
            </div>;
          })}
        </div>}
    </section>
  </div>;
}

function SettingsPanel() {
  const [config, setConfig] = useState<Awaited<ReturnType<typeof api.adminConfig>> | null>(null);
  const [commission, setCommission] = useState('');
  const [voucher, setVoucher] = useState('');
  const [saving, setSaving] = useState(false);
  const [message, setMessage] = useState('');
  const [error, setError] = useState('');
  const [users, setUsers] = useState<Awaited<ReturnType<typeof api.adminUsers>> | null>(null);
  const [page, setPage] = useState(0);
  const [loadingUsers, setLoadingUsers] = useState(true);
  const [toast, setToast] = useState<string>('');

  useEffect(() => {
    if (!toast) return;
    const timer = window.setTimeout(() => setToast(''), 2600);
    return () => window.clearTimeout(timer);
  }, [toast]);

  useEffect(() => {
    api.adminConfig().then(data => {
      setConfig(data);
      setCommission(String(Math.round(data.commissionRate * 100)));
      setVoucher(String(Math.round(data.voucherRunnerBearRatio * 100)));
    }).catch(e => setError(e instanceof Error ? e.message : '配置加载失败'));
  }, []);

  useEffect(() => {
    let active = true;
    setLoadingUsers(true);
    api.adminUsers(page, 20)
      .then(data => { if (active) setUsers(data); })
      .catch(e => { if (active) setError(e instanceof Error ? e.message : '用户加载失败'); })
      .finally(() => { if (active) setLoadingUsers(false); });
    // 跨浏览器在线状态：静默轮询，别人登录/下线/心跳超时能及时反映
    const timer = window.setInterval(() => {
      api.adminUsers(page, 20).then(data => { if (active) setUsers(data); }).catch(() => {});
    }, 10000);
    return () => { active = false; window.clearInterval(timer); };
  }, [page]);

  const save = async () => {
    const c = Number(commission), v = Number(voucher);
    if (!Number.isFinite(c) || !Number.isFinite(v) || c < 0 || c > 100 || v < 0 || v > 100) {
      setError('比例需在 0% - 100% 之间'); return;
    }
    setSaving(true); setError(''); setMessage('');
    try {
      const next = await api.adminUpdateConfig(c / 100, v / 100);
      setConfig(next);
      setMessage('保存成功');
    } catch (e) { setError(e instanceof Error ? e.message : '保存失败'); }
    finally { setSaving(false); }
  };

  const toggleUser = async (userId: string, enabled: boolean, username: string) => {
    setError('');
    try {
      await api.adminUpdateUserStatus(userId, !enabled);
      setToast(`${enabled ? '已禁用' : '已启用'}用户 ${username}`);
      setUsers(await api.adminUsers(page, 20));
    } catch (e) { setError(e instanceof Error ? e.message : '状态更新失败'); }
  };

  const forceLogout = async (userId: string, username: string) => {
    setError('');
    try {
      await api.adminForceLogout(userId);
      setToast(`用户${username}已下线`);
      // 立即回查，及时反映在线状态
      setUsers(await api.adminUsers(page, 20));
    } catch (e) { setError(e instanceof Error ? e.message : '强制下线失败'); }
  };

  const totalPages = users ? Math.max(1, Math.ceil(users.total / Math.max(users.size, 1))) : 1;

  return <div className="admin-settings-view">
    <section className="admin-settings-card">
      <div className="admin-list-head"><div><h2>佣金与抽成比例</h2><small>保存后立即生效，下一次结算开始使用新比例</small></div></div>
      <div className="admin-settings-form">
        <label>佣金比例（%）<input type="number" min="0" max="100" step="0.1" value={commission} onChange={e => setCommission(e.target.value)}/></label>
        <label>优惠券抽成比例（%）<input type="number" min="0" max="100" step="0.1" value={voucher} onChange={e => setVoucher(e.target.value)}/></label>
        <button type="button" className="btn btn-primary" disabled={saving} onClick={() => void save()}>{saving ? '保存中…' : '保存配置'}</button>
      </div>
      {message && <div className="banner ok" role="status">{message}</div>}
      {error && <div className="banner warn" role="alert">{error}</div>}
      <div className="admin-formula">
        <strong>佣金账户收入规则</strong>
        <p>I = R₁ × x + R₂ × y</p>
        <small>x：订单原价（悬赏金额）　y：优惠券优惠金额　R₁：佣金比例　R₂：优惠券抽成比例</small>
        <small>按当前配置：I = {(config ? (config.commissionRate * 100).toFixed(1) : '—')}% × x + {(config ? (config.voucherRunnerBearRatio * 100).toFixed(1) : '—')}% × y</small>
      </div>
    </section>

    <section className="admin-users-card">
      <div className="admin-list-head"><h2>平台用户 <span>{users?.total ?? '—'}</span></h2>
        <div className="admin-pager admin-pager-top">
          <button type="button" className="btn btn-ghost" disabled={page <= 0 || loadingUsers} onClick={() => setPage(p => Math.max(0, p - 1))}>上一页</button>
          <span>第 {page + 1} / {totalPages} 页</span>
          <button type="button" className="btn btn-ghost" disabled={page + 1 >= totalPages || loadingUsers} onClick={() => setPage(p => p + 1)}>下一页</button>
        </div>
      </div>
      {loadingUsers && !users ? <div className="admin-empty">正在加载…</div>
        : !users || users.items.length === 0 ? <div className="admin-empty">暂无普通用户</div>
        : <div className="admin-ledger-scroll"><table>
          <thead><tr><th>用户名</th><th>在线状态</th><th>启用状态</th><th>上次登录</th><th>余额</th><th>操作</th></tr></thead>
          <tbody>{users.items.map(user => <tr key={user.userId}>
            <td>{user.username}</td>
            <td><span className={`admin-online-dot ${user.online ? 'is-online' : 'is-offline'}`}/>{user.online ? '在线' : '离线'}</td>
            <td><span className={`admin-enabled-dot ${user.enabled === false ? 'is-disabled' : 'is-enabled'}`}/>{user.enabled === false ? '禁用' : '启用'}</td>
            <td>{user.lastLoginAt ? formatDateTime(user.lastLoginAt) : ''}</td>
            <td>{yuan(user.balanceCents)}</td>
            <td className="admin-user-actions">
              <button type="button" className={`btn ${user.enabled === false ? 'btn-success' : 'btn-danger'}`}
                      onClick={() => void toggleUser(user.userId, user.enabled !== false, user.username)}>
                {user.enabled === false ? '启用' : '禁用'}
              </button>
              <button type="button" className="btn btn-ghost admin-danger" disabled={!user.online}
                      title={user.online ? '强制该用户下线' : '用户已离线'}
                      onClick={() => void forceLogout(user.userId, user.username)}>强制下线</button>
            </td>
          </tr>)}</tbody>
        </table></div>}
      {toast && <div className="floating-notice is-success" role="status">
        <div className="floating-notice-card">
          <span className="floating-notice-icon">✓</span>
          <div className="floating-notice-body"><strong>{toast}</strong></div>
          <button type="button" className="floating-notice-close" aria-label="关闭提示" onClick={() => setToast('')}>×</button>
        </div>
      </div>}
      <div className="admin-pager">
        <button type="button" className="btn btn-ghost" disabled={page <= 0 || loadingUsers} onClick={() => setPage(p => Math.max(0, p - 1))}>上一页</button>
        <span>第 {page + 1} / {totalPages} 页</span>
        <button type="button" className="btn btn-ghost" disabled={page + 1 >= totalPages || loadingUsers} onClick={() => setPage(p => p + 1)}>下一页</button>
      </div>
    </section>
  </div>;
}
