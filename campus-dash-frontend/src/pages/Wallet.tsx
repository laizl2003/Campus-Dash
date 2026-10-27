import { useCallback, useEffect, useRef, useState } from 'react';
import { api, yuan } from '../api';
import Icon from '../components/Icon';
import { subscribeWs } from '../ws';
import './accountPages.css';

const VOUCHER_STATUS_TEXT: Record<string, string> = {
  available: '可使用', used: '已使用', expired: '已过期',
};

/** 毫秒 → "剩 X 天 Y 小时" / "剩 X 分 Y 秒"，到期返回已过期 */
function formatRemaining(expireTime: string, now: number): string {
  const remain = new Date(expireTime).getTime() - now;
  if (!Number.isFinite(remain) || remain <= 0) return '已过期';
  const total = Math.floor(remain / 1000);
  const days = Math.floor(total / 86400);
  const hours = Math.floor((total % 86400) / 3600);
  const minutes = Math.floor((total % 3600) / 60);
  const seconds = total % 60;
  if (days > 0) return `剩 ${days} 天 ${hours} 小时`;
  if (hours > 0) return `剩 ${hours} 小时 ${minutes} 分`;
  if (minutes > 0) return `剩 ${minutes} 分 ${seconds} 秒`;
  return `剩 ${seconds} 秒`;
}
interface MyVoucher {
  voucherId: string; type: number; description: string;
  thresholdCents?: number; discountCents?: number;
  expireTime: string; status: string;
}
const REF_TYPE_TEXT: Record<string, string> = {
  ESCROW: '任务托管', SETTLE: '任务结算', REFUND: '退款到账',
  RECHARGE: '余额充值', WITHDRAW: '余额提现',
};
const TASK_REF_TYPES = new Set(['ESCROW', 'SETTLE', 'REFUND']);

export default function Wallet() {
  const [balance, setBalance] = useState<Awaited<ReturnType<typeof api.wallet>> | null>(null);
  const [ledger, setLedger] = useState<Awaited<ReturnType<typeof api.ledger>> | null>(null);
  const [direction, setDirection] = useState('ALL');
  const [refType, setRefType] = useState('ALL');
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState('');
  const [rechargeOpen, setRechargeOpen] = useState(false);
  const [amountYuan, setAmountYuan] = useState('10');
  const [recharging, setRecharging] = useState(false);
  const [rechargeMessage, setRechargeMessage] = useState('');
  const [vouchers, setVouchers] = useState<MyVoucher[]>([]);
  // 每秒刷新一次"现在"，用来驱动倒计时；到期的那张会在下一次渲染被过滤掉
  const [now, setNow] = useState(() => Date.now());
  const requestId = useRef(0);

  useEffect(() => {
    const timer = window.setInterval(() => setNow(Date.now()), 1000);
    return () => window.clearInterval(timer);
  }, []);

  // 只展示"还没到期"的券：状态已是 expired，或有效期已过（后端延迟消息可能还没到）都不再展示
  const visibleVouchers = vouchers.filter(voucher =>
    voucher.status !== 'expired' && new Date(voucher.expireTime).getTime() > now);

  /** 演示用固定面额（元） */
  const QUICK_AMOUNTS = [10, 20, 50, 100, 200, 500];

  const submitRecharge = async (event: React.FormEvent) => {
    event.preventDefault();
    if (recharging) return;
    const yuanValue = Number(amountYuan);
    if (!Number.isFinite(yuanValue) || yuanValue <= 0) {
      setRechargeMessage('请输入大于 0 的金额');
      return;
    }
    setRecharging(true);
    setRechargeMessage('');
    try {
      const result = await api.recharge(Math.round(yuanValue * 100));
      setBalance((prev) => (prev ? { ...prev, availableCents: result.availableCents } : prev));
      setRechargeMessage(`充值成功，当前可用余额 ${yuan(result.availableCents)}`);
      void load();
    } catch (e) {
      setRechargeMessage(e instanceof Error ? e.message : '充值失败，请稍后重试');
    } finally {
      setRecharging(false);
    }
  };

  const load = useCallback(async () => {
    const id = ++requestId.current;
    setLoading(true);
    setError('');
    const [walletResult, ledgerResult, voucherResult] = await Promise.allSettled([
      api.wallet(), api.ledger(), api.myVouchers(),
    ]);
    if (id !== requestId.current) return;
    const errors: string[] = [];
    if (walletResult.status === 'fulfilled') setBalance(walletResult.value);
    else errors.push(`余额加载失败：${walletResult.reason instanceof Error ? walletResult.reason.message : '请稍后重试'}`);
    if (ledgerResult.status === 'fulfilled') setLedger(ledgerResult.value);
    else errors.push(`流水加载失败：${ledgerResult.reason instanceof Error ? ledgerResult.reason.message : '请稍后重试'}`);
    if (voucherResult.status === 'fulfilled') setVouchers(voucherResult.value);
    setError(errors.join('；'));
    setLoading(false);
  }, []);

  useEffect(() => {
    void load();
    const unsubscribe = subscribeWs((event) => {
      if (event.type === 'errand.status' || event.type === 'notification.new') void load();
    });
    return () => { requestId.current++; unsubscribe(); };
  }, [load]);

  const filtered = (ledger ?? []).filter((entry) =>
    (direction === 'ALL' || entry.direction === direction) && (refType === 'ALL' || entry.refType === refType),
  );

  return (
    <div className="account-page">
      <div className="page-head">
        <div><span className="eyebrow">MY WALLET</span><h1>每一笔，都有着落</h1><p>账户余额与每一笔任务资金记录，在这里清楚查看。</p></div>
        <div className="wallet-head-actions">
          <button className="btn btn-primary" onClick={() => { setRechargeOpen((open) => !open); setRechargeMessage(''); }}>
            <Icon name="plus" size={15} />{rechargeOpen ? '收起充值' : '充值'}
          </button>
          <button className="btn btn-ghost" onClick={() => void load()} disabled={loading}><Icon name="refresh" size={15} />{loading ? '更新中' : '刷新账单'}</button>
        </div>
      </div>
      {rechargeOpen && <section className="account-surface recharge-panel" aria-label="余额充值">
        <div className="account-section-head"><div><h2>余额充值</h2><p>演示环境直接入账，不接真实支付网关</p></div></div>
        <form className="recharge-form" onSubmit={submitRecharge}>
          <label className="recharge-amount"><span>充值金额（元）</span>
            <input type="number" min="0.01" step="0.01" inputMode="decimal" value={amountYuan}
                   onChange={(event) => setAmountYuan(event.target.value)} placeholder="输入金额，例如 20"/>
          </label>
          <div className="recharge-quick" role="group" aria-label="快捷金额">
            {QUICK_AMOUNTS.map((value) => <button key={value} type="button"
              className={Number(amountYuan) === value ? 'active' : ''}
              onClick={() => setAmountYuan(String(value))}>{value} 元</button>)}
          </div>
          <div className="recharge-actions">
            <button className="btn btn-primary" type="submit" disabled={recharging}>{recharging ? '入账中…' : '确认充值'}</button>
            <span className="recharge-hint">单笔上限 1000 元，充值会记一条「余额充值」流水</span>
          </div>
          {rechargeMessage && <div className="banner ok" role="status">{rechargeMessage}</div>}
        </form>
      </section>}
      {error && <div className="banner warn account-error" role="alert"><span>{error}</span><button className="btn btn-ghost" onClick={() => void load()} disabled={loading}>重试</button></div>}
      <section className="account-wallet-overview" aria-label="钱包概览" aria-busy={loading}>
        <div className="account-balance-main">
          <div className="account-balance-label"><Icon name="wallet" size={18} /><span>可用余额</span><span className="account-currency">CNY</span></div>
          <strong className="account-balance-value">{balance ? yuan(balance.availableCents) : '—'}</strong>
          <p>用于发布任务，报酬在确认完成后结算。</p>
          <a href="#/publish" className="account-balance-link">发布一个新任务 <Icon name="arrow-right" size={15} /></a>
        </div>
        <div className="account-balance-secondary">
          <div className="account-stat-symbol"><Icon name="shield" size={20} /></div>
          <span>冻结余额</span><strong>{balance ? yuan(balance.frozenCents) : '—'}</strong><p>暂不可用于发布任务的账户金额</p>
        </div>
        <div className="account-balance-secondary">
          <div className="account-stat-symbol account-stat-symbol-blue"><Icon name="clock" size={20} /></div>
          <span>已加载记录</span><strong>{ledger === null ? '—' : ledger.length}<small> 笔</small></strong><p>当前返回的最近记账记录</p>
        </div>
      </section>
      <section className="account-surface wallet-vouchers" aria-label="我的优惠券">
        <div className="account-section-head">
          <div><h2>我的优惠券</h2><p>领取后 2 天内有效，过期自动作废</p></div>
          <span className="account-count">{visibleVouchers.filter(v => v.status === 'available').length} 张可用</span>
        </div>
        {visibleVouchers.length === 0
          ? <p className="wallet-voucher-empty">还没有可用的优惠券，去<a href="#/square">广场的「惊喜优惠」</a>看看。</p>
          : <div className="wallet-voucher-grid">
            {visibleVouchers.map(voucher => <div key={voucher.voucherId} className={`wallet-voucher is-${voucher.status}`}>
              <span className="wallet-voucher-amount"><small>¥</small><strong>{Number(voucher.discountCents ?? 0) / 100}</strong></span>
              <span className="wallet-voucher-rule">满{Number(voucher.thresholdCents ?? 0) / 100}元可用</span>
              <span className={`wallet-voucher-expire${voucher.status === 'available' && new Date(voucher.expireTime).getTime() - now < 6 * 3600_000 ? ' is-urgent' : ''}`}
                    title={`${new Date(voucher.expireTime).toLocaleString('zh-CN')} 到期`}>
                {voucher.status === 'used' ? '已使用' : formatRemaining(voucher.expireTime, now)}
              </span>
              <span className={`wallet-voucher-status is-${voucher.status}`}>{VOUCHER_STATUS_TEXT[voucher.status] ?? voucher.status}</span>
            </div>)}
          </div>}
      </section>
      <section className="account-surface">
        <div className="account-section-head"><div><h2>记账流水</h2><p>默认加载最近 20 条记录，同一业务可能包含多条记账</p></div><span className="account-count">{ledger === null ? '等待加载' : `${filtered.length} 笔记录`}</span></div>
        <div className="account-filter-bar">
          <div className="tabs" aria-label="按记账方向筛选">{[['ALL', '全部流水'], ['CREDIT', '贷记'], ['DEBIT', '借记']].map(([value, label]) => <button key={value} className={`tab${direction === value ? ' active' : ''}`} aria-pressed={direction === value} onClick={() => setDirection(value)}>{label}</button>)}</div>
          <label className="account-select-label"><span>交易类型</span><select value={refType} onChange={(event) => setRefType(event.target.value)}><option value="ALL">全部类型</option>{Object.entries(REF_TYPE_TEXT).map(([value, label]) => <option key={value} value={value}>{label}</option>)}</select></label>
        </div>
        {loading && ledger === null ? <div className="account-state" role="status"><span className="account-loader" /><strong>正在加载记账流水</strong><p>余额与交易记录即将呈现</p></div> : ledger === null ? <div className="account-state"><Icon name="wallet" size={28} /><strong>暂时无法获取流水</strong><p>请重试以获取最新账单。</p></div> : filtered.length === 0 ? <div className="account-state"><div className="account-empty-icon"><Icon name="wallet" size={27} /></div><strong>{ledger.length ? '没有符合条件的流水' : '你的账单，从第一笔开始'}</strong><p>{ledger.length ? '试试切换记账方向或交易类型。' : '发布或完成任务后，资金记录会出现在这里。'}</p>{ledger.length > 0 && <button className="btn btn-ghost" onClick={() => { setDirection('ALL'); setRefType('ALL'); }}>清除筛选</button>}</div> : (
          <div className="account-table-scroll"><table className="account-table"><thead><tr><th>交易类型</th><th>关联任务</th><th>交易时间</th><th className="account-align-right">金额</th></tr></thead><tbody>
            {filtered.map((entry, index) => <tr key={`${entry.bizNo}-${entry.direction}-${index}`}>
              <td><div className="account-transaction"><span className="account-transaction-icon"><Icon name={entry.direction === 'CREDIT' ? 'arrow-down' : 'arrow-up'} size={16} /></span><div><strong>{REF_TYPE_TEXT[entry.refType] ?? entry.refType}</strong><small>{entry.direction === 'CREDIT' ? '贷记' : entry.direction === 'DEBIT' ? '借记' : entry.direction}</small></div></div></td>
              <td>{TASK_REF_TYPES.has(entry.refType) && entry.refId && entry.refId !== '0' ? <a className="account-task-link" href={`#/detail/${entry.refId}`}>#{String(entry.refId).slice(-8)} <Icon name="arrow-right" size={12} /></a> : <span className="muted">—</span>}</td>
              <td className="account-time"><time dateTime={entry.time}>{new Date(entry.time).toLocaleString('zh-CN', { month: '2-digit', day: '2-digit', year: 'numeric', hour: '2-digit', minute: '2-digit' })}</time></td>
              <td className="account-align-right account-amount">{yuan(entry.amountCents)}</td>
            </tr>)}
          </tbody></table></div>
        )}
        <div className="account-footnote"><Icon name="shield" size={14} /><span>同一业务可能对应多条记账记录，不代表多次收支；账户余额以概览为准。</span></div>
      </section>
    </div>
  );
}
