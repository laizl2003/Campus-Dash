import { useCallback, useEffect, useState } from 'react';
import { api, logout, yuan, getUsername } from '../api';
import Icon from '../components/Icon';
import './admin.css';

interface Dispute {
  errandId: string; title: string; status: string; rewardCents: number;
  publisherId: string; grabberId: string; round: number;
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
  const [list, setList] = useState<Dispute[]>([]);
  const [selected, setSelected] = useState<Dispute | null>(null);
  const [loading, setLoading] = useState(true);
  const [busy, setBusy] = useState(false);
  const [pendingFavor, setPendingFavor] = useState<'RUNNER' | 'PUBLISHER' | null>(null);
  const [message, setMessage] = useState<{ text: string; error: boolean } | null>(null);

  const load = useCallback(async () => {
    setLoading(true);
    try {
      setList(await api.adminDisputes());
      setMessage(null);
    } catch (e) {
      setMessage({ text: e instanceof Error ? e.message : '待仲裁任务加载失败', error: true });
    } finally {
      setLoading(false);
    }
  }, []);

  useEffect(() => { void load(); }, [load]);

  const open = async (id: string) => {
    try {
      setPendingFavor(null);
      setSelected(await api.adminDispute(id));
    } catch (e) {
      setMessage({ text: e instanceof Error ? e.message : '详情加载失败', error: true });
    }
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
        <button type="button" className="is-active"><Icon name="shield" size={16}/>仲裁工作台</button>
        <button type="button" disabled title="规划中"><Icon name="ticket" size={16}/>优惠券管理</button>
        <button type="button" disabled title="规划中"><Icon name="wallet" size={16}/>平台配置</button>
      </nav>
      <div className="admin-side-foot">
        <span><Icon name="user" size={14}/>{getUsername()} · 001</span>
        <button type="button" className="admin-logout" onClick={logout}><Icon name="logout" size={14}/>退出</button>
      </div>
    </aside>

    <div className="admin-main">
      <header className="admin-top">
        <div><span className="eyebrow">ADMIN CONSOLE</span><h1>仲裁工作台<span className="heading-dot">.</span></h1>
          <p>处理争议任务，决定托管资金流向跑腿或发单人。</p></div>
        <button type="button" className="btn btn-ghost" onClick={() => void load()} disabled={loading}>
          <Icon name="refresh" size={15} className={loading ? 'is-spinning' : ''}/>刷新
        </button>
      </header>

      {message && <div className={`banner ${message.error ? 'warn' : 'ok'}`} role="status">
        <Icon name={message.error ? 'info' : 'check'} size={17}/><span>{message.text}</span>
        <button className="text-button" onClick={() => setMessage(null)}>关闭</button>
      </div>}

      <div className="admin-body">
        <section className="admin-list" aria-label="待仲裁任务">
          <div className="admin-list-head"><h2>待仲裁 <span>{list.length}</span></h2><small>按任务编号倒序</small></div>
          {loading && list.length === 0 && <div className="admin-empty">正在加载…</div>}
          {!loading && list.length === 0 && <div className="admin-empty">
            <Icon name="check" size={26}/><strong>没有待处理的争议</strong><p>任务送达后发单人确认前出现的争议会出现在这里。</p>
          </div>}
          <ul>
            {list.map(item => <li key={item.errandId}>
              <button type="button" className={`admin-row${selected?.errandId === item.errandId ? ' is-active' : ''}`} onClick={() => void open(item.errandId)}>
                <span className="admin-row-title">{item.title}</span>
                <span className="admin-row-meta">#{item.errandId} · 悬赏 {yuan(item.rewardCents)} · 第 {item.round} 轮</span>
                <span className="admin-row-parties">发单 {item.publisherId} → 跑腿 {item.grabberId || '—'}</span>
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
              <div><dt>发单人</dt><dd>{selected.publisherId}</dd></div>
              <div><dt>跑腿</dt><dd>{selected.grabberId || '—'}</dd></div>
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
      </div>
    </div>
  </div>;
}
