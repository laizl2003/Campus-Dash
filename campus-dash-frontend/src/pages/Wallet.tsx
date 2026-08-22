import { useEffect, useState } from 'react';
import { api, yuan } from '../api';

const REF_TYPE_TEXT: Record<string, string> = {
  ESCROW: '发布托管', SETTLE: '结算', REFUND: '退款',
  RECHARGE: '充值', WITHDRAW: '提现',
};

export default function Wallet() {
  const [balance, setBalance] = useState<{ availableCents: number; frozenCents: number } | null>(null);
  const [ledger, setLedger] = useState<Awaited<ReturnType<typeof api.ledger>>>([]);

  useEffect(() => {
    api.wallet().then(setBalance).catch(() => {});
    api.ledger().then(setLedger).catch(() => {});
  }, []);

  return (
    <div>
      <h2>钱包</h2>
      {balance && (
        <div className="balance-panel">
          <div><span className="muted">可用余额</span><strong>{yuan(balance.availableCents)}</strong></div>
          <div><span className="muted">冻结</span><strong>{yuan(balance.frozenCents)}</strong></div>
        </div>
      )}
      <h3>资金流水（复式记账：每笔动作一借一贷）</h3>
      <table className="ledger-table">
        <thead>
          <tr><th>时间</th><th>方向</th><th>金额</th><th>类型</th><th>关联任务</th></tr>
        </thead>
        <tbody>
          {ledger.map((l, i) => (
            <tr key={i}>
              <td>{new Date(l.time).toLocaleString()}</td>
              <td className={l.direction === 'CREDIT' ? 'credit' : 'debit'}>
                {l.direction === 'CREDIT' ? '收入' : '支出'}
              </td>
              <td className={l.direction === 'CREDIT' ? 'credit' : 'debit'}>
                {l.direction === 'CREDIT' ? '+' : '-'}{yuan(l.amountCents)}
              </td>
              <td>{REF_TYPE_TEXT[l.refType] ?? l.refType}</td>
              <td><a href={`#/detail/${l.refId}`}>#{String(l.refId).slice(-8)}</a></td>
            </tr>
          ))}
          {ledger.length === 0 && <tr><td colSpan={5} className="muted">暂无流水</td></tr>}
        </tbody>
      </table>
    </div>
  );
}
