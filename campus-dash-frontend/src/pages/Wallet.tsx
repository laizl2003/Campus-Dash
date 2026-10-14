import { useCallback, useEffect, useRef, useState } from 'react';
import { api, yuan } from '../api';
import Icon from '../components/Icon';
import { subscribeWs } from '../ws';
import './accountPages.css';

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
  const requestId = useRef(0);

  const load = useCallback(async () => {
    const id = ++requestId.current;
    setLoading(true);
    setError('');
    const [walletResult, ledgerResult] = await Promise.allSettled([api.wallet(), api.ledger()]);
    if (id !== requestId.current) return;
    const errors: string[] = [];
    if (walletResult.status === 'fulfilled') setBalance(walletResult.value);
    else errors.push(`余额加载失败：${walletResult.reason instanceof Error ? walletResult.reason.message : '请稍后重试'}`);
    if (ledgerResult.status === 'fulfilled') setLedger(ledgerResult.value);
    else errors.push(`流水加载失败：${ledgerResult.reason instanceof Error ? ledgerResult.reason.message : '请稍后重试'}`);
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
        <button className="btn btn-ghost" onClick={() => void load()} disabled={loading}><Icon name="refresh" size={15} />{loading ? '更新中' : '刷新账单'}</button>
      </div>
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
