import { useEffect, useRef, useState } from 'react';
import type { FormEvent } from 'react';
import { api, TYPE_ICON, TYPE_TEXT, yuan } from '../api';
import Icon from '../components/Icon';
import SelectMenu from '../components/SelectMenu';
import './taskPages.css';

const CATEGORIES = [
  { type: 'DELIVERY', description: '快递、餐食与日常物品', example: '帮我从三食堂带一份晚饭到 6 号楼' },
  { type: 'BUY', description: '顺路带回你需要的好物', example: '帮我从校园超市买两瓶矿泉水' },
  { type: 'QUEUE', description: '替你等一会儿，省下时间', example: '帮我在图书馆打印室排队取资料' },
  { type: 'OTHER', description: '校园里的其他小小帮忙', example: '帮我把借阅的书送还图书馆' },
];

function initialValues() {
  const params = new URLSearchParams(location.hash.split('?')[1] || '');
  const requestedType = params.get('type') || 'DELIVERY';
  const type = CATEGORIES.some((item) => item.type === requestedType) ? requestedType : 'DELIVERY';
  return { type, title: params.get('template') || '' };
}

/** 需要地点与时间的类型；OTHER 只填标题和任务内容 */
const STRUCTURED_TYPES = new Set(['DELIVERY', 'BUY', 'QUEUE']);

/** datetime-local 的值没有时区，按本地时区转成 ISO，后端按 Instant 存 */
function toIsoStartTime(value: string): string | null {
  const text = value.trim();
  if (!text) return null;
  const date = new Date(text);
  return Number.isNaN(date.getTime()) ? null : date.toISOString();
}

/** 使用十进制拆分，避免浮点数把分值转换出误差。 */
function parseReward(value: string): number | null {
  if (!/^\d+(?:\.\d{1,2})?$/.test(value.trim())) return null;
  const [whole, decimal = ''] = value.trim().split('.');
  const cents = Number(whole) * 100 + Number(decimal.padEnd(2, '0'));
  return Number.isSafeInteger(cents) && cents > 0 ? cents : null;
}

export default function Publish() {
  const [initial] = useState(initialValues);
  const [title, setTitle] = useState(initial.title);
  const [description, setDescription] = useState('');
  const [locationFrom, setLocationFrom] = useState('');
  const [locationTo, setLocationTo] = useState('');
  const [expiredAt, setExpiredAt] = useState('');
  const [reward, setReward] = useState('15');
  const [type, setType] = useState(initial.type);
  const [balance, setBalance] = useState<number | null>(null);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [published, setPublished] = useState<{ id: string; title: string; rewardCents: number; paidCents: number; discountCents: number } | null>(null);
  const [vouchers, setVouchers] = useState<{ voucherId: string; type: number; thresholdCents?: number; discountCents?: number; status: string }[]>([]);
  // 雪花 ID 超过 2^53，必须用字符串保存和提交，转 number 会丢精度
  const [voucherId, setVoucherId] = useState<string | null>(null);
  const submitting = useRef(false);
  const rewardCents = parseReward(reward);
  const category = CATEGORIES.find((item) => item.type === type)!;
  const structured = STRUCTURED_TYPES.has(type);

  const refreshVouchers = async () => {
    try {
      const list = await api.myVouchers();
      setVouchers(list.filter((v) => v.status === 'available'));
    } catch { /* 券加载失败不影响发布 */ }
  };

  useEffect(() => {
    let active = true;
    api.wallet().then((wallet) => { if (active) setBalance(wallet.availableCents); }).catch(() => {});
    api.myVouchers()
      .then((list) => { if (active) setVouchers(list.filter((v) => v.status === 'available')); })
      .catch(() => { /* 券加载失败不影响发布 */ });
    return () => { active = false; };
  }, []);

  // 只有达到门槛的券可选；改小悬赏后要自动取消已选但不达标的券
  const eligibleVouchers = vouchers.filter((v) => Number(v.thresholdCents ?? 0) <= (rewardCents ?? 0));
  const selectedVoucher = eligibleVouchers.find((v) => v.voucherId === voucherId) ?? null;
  const discountCents = selectedVoucher ? Number(selectedVoucher.discountCents ?? 0) : 0;
  const paidCents = rewardCents === null ? null : Math.max(0, rewardCents - discountCents);
  useEffect(() => {
    if (voucherId !== null && !eligibleVouchers.some((v) => v.voucherId === voucherId)) setVoucherId(null);
  }, [voucherId, eligibleVouchers]);

  const submit = async (event: FormEvent) => {
    event.preventDefault();
    if (submitting.current) return;
    setError(null);
    if (!title.trim()) { setError('请填写任务标题。'); return; }
    if (title.trim().length > 64) { setError('请将任务标题控制在 64 字以内，方便同学快速了解需求。'); return; }
    if (structured) {
      if (!locationFrom.trim()) { setError('请填写取货地点。'); return; }
      if (!locationTo.trim()) { setError('请填写送达地点。'); return; }
      // 截止时间选填：不填表示永久有效
    if (expiredAt && new Date(expiredAt).getTime() <= Date.now()) {
      setError('截止时间需要晚于当前时间。');
      return;
    }
    }
    if (rewardCents === null) { setError('请输入大于 0 的金额，最多保留两位小数。'); return; }
    submitting.current = true;
    setBusy(true);
    try {
      const result = await api.publish({
        title: title.trim(),
        rewardCents,
        slotTotal: 1,
        type,
        voucherId,
        locationFrom: structured ? locationFrom.trim() : null,
        locationTo: structured ? locationTo.trim() : null,
        expiredAt: structured ? toIsoStartTime(expiredAt) : null,
        description: description.trim() || null,
      });
      // 券已被本次发布锁定：立即从本地可选列表移除，并清空选择，
      // 否则“再发一个任务”会继续展示上一单用过的券。
      if (voucherId) {
        setVouchers(prev => prev.filter(v => v.voucherId !== voucherId));
        setVoucherId(null);
      }
      setPublished({
        id: result.errandId, title: title.trim(), rewardCents,
        paidCents: Number(result.paidCents ?? result.frozenCents ?? rewardCents),
        discountCents: Number(result.discountCents ?? 0),
      });
      window.dispatchEvent(new Event('dash:changed'));
      void refreshVouchers();
      api.wallet().then((wallet) => setBalance(wallet.availableCents)).catch(() => {});
    } catch (err) {
      setError(err instanceof Error ? err.message : '暂时没有发布成功，请稍后再试。');
    } finally {
      submitting.current = false;
      setBusy(false);
    }
  };

  if (published) return (
    <div className="task-publish-page">
      <div className="page-head"><div><span className="eyebrow">CREATE AN ERRAND</span><h1>发布任务</h1><p>让校园里的顺路，成为你的便利。</p></div></div>
      <section className="panel task-publish-success" aria-live="polite">
        <div className="task-success-icon"><Icon name="check" size={30} /></div>
        <span className="eyebrow">READY TO GO</span>
        <h2>任务已发布，等一位顺路的同学</h2>
        <p>{published.title}</p>
        <div className="task-success-reward"><span>已托管实付</span><strong>{yuan(published.paidCents)}</strong></div>
        {published.discountCents > 0 && <p className="muted">已使用优惠券减 {yuan(published.discountCents)}，悬赏仍为 {yuan(published.rewardCents)}。</p>}
        <p className="muted">完成后确认结算，悬赏会转入跑腿同学的钱包。</p>
        <div className="task-success-actions"><a className="btn btn-primary" href={`#/detail/${published.id}`}>查看任务进度 <Icon name="arrow-right" size={16} /></a><button className="btn btn-ghost" onClick={() => { setPublished(null); setTitle(''); setDescription(''); setLocationFrom(''); setLocationTo(''); setExpiredAt(''); setVoucherId(null); void refreshVouchers(); }}>再发一个任务</button></div>
        <span className="task-order-id">任务编号 {published.id}</span>
      </section>
    </div>
  );

  return (
    <div className="task-publish-page">
      <div className="page-head"><div><span className="eyebrow">CREATE AN ERRAND</span><h1>小事交给同学，大事留给自己</h1><p>说清需求，设好悬赏。让顺路的同学来帮你。</p></div><a className="btn btn-ghost" href="#/square"><Icon name="arrow-left" size={16} /> 返回广场</a></div>
      <div className="task-publish-layout">
        <form className="panel task-compose" onSubmit={submit} noValidate>
          <fieldset disabled={busy}>
            <div className="task-form-section">
              <div className="task-section-heading"><span className="task-step-number">01</span><div><h2>需要什么帮助？</h2><p>选一个类型，让合适的同学更容易找到你。</p></div></div>
              <div className="task-type-grid" role="group" aria-label="任务类型">
                {CATEGORIES.map((item) => <button type="button" key={item.type} aria-pressed={type === item.type} className={`task-type-option ${type === item.type ? 'is-selected' : ''}`} onClick={() => { setType(item.type); setError(null); }}><span className="task-type-symbol"><Icon name={TYPE_ICON[item.type]} size={21} /></span><span><strong>{TYPE_TEXT[item.type]}</strong><small>{item.description}</small></span><span className="task-selection-dot">{type === item.type && <Icon name="check" size={11} />}</span></button>)}
              </div>
            </div>
            <div className="task-form-section">
              <div className="task-section-heading"><span className="task-step-number">02</span><div><h2>把需求说清楚</h2><p>{structured ? '标题写清做什么，地点和时间决定同学怎么跑。' : '写清标题和任务内容，同学才知道要帮你什么。'}</p></div></div>
              <label className="task-field-label" htmlFor="task-title">任务标题 <span>必填</span></label>
              <input id="task-title" type="text" value={title} maxLength={64} placeholder={`例如：${category.example}`} onChange={(event) => { setTitle(event.target.value); setError(null); }} aria-describedby="task-title-hint" />
              <div className="task-field-foot"><span id="task-title-hint">一句话说清这件事，方便同学快速判断。</span><span>{title.length} / 64 字</span></div>

              {structured && <div className="task-structured-fields">
                <div className="task-field-row">
                  <div>
                    <label className="task-field-label" htmlFor="task-location-from">取货地点 <span>必填</span></label>
                    <input id="task-location-from" type="text" value={locationFrom} maxLength={128} placeholder="例如：菜鸟驿站（东门）" onChange={(event) => { setLocationFrom(event.target.value); setError(null); }} />
                  </div>
                  <div>
                    <label className="task-field-label" htmlFor="task-location-to">送达地点 <span>必填</span></label>
                    <input id="task-location-to" type="text" value={locationTo} maxLength={128} placeholder="例如：3 号楼 502" onChange={(event) => { setLocationTo(event.target.value); setError(null); }} />
                  </div>
                </div>
                <div>
                  <label className="task-field-label" htmlFor="task-expired-at">截止时间 <span>选填</span></label>
                  <input id="task-expired-at" type="datetime-local" value={expiredAt} onChange={(event) => { setExpiredAt(event.target.value); setError(null); }} />
                  <div className="task-field-foot"><span>超过这个时间仍无人接单，任务会自动过期。不填则永久有效。</span></div>
                </div>
              </div>}

              <label className="task-field-label" htmlFor="task-description">任务内容 <span>选填</span></label>
              <textarea id="task-description" value={description} rows={3} maxLength={300} placeholder="补充物品大小、联系方式之外的注意事项，例如：快递比较大，需要小推车。" onChange={(event) => { setDescription(event.target.value); setError(null); }} />
              <div className="task-field-foot"><span>补充细节，减少来回确认。</span><span>{description.length} / 300 字</span></div>
            </div>
            <div className="task-form-section task-reward-section">
              <div className="task-section-heading"><span className="task-step-number">03</span><div><h2>给这份帮助一份悬赏</h2><p>按任务难度设置，发布时将从钱包托管。</p></div></div>
              <label className="task-field-label" htmlFor="task-reward">悬赏金额 <span>人民币</span></label>
              <div className="task-reward-input"><span>¥</span><input id="task-reward" type="text" inputMode="decimal" value={reward} onChange={(event) => { setReward(event.target.value); setError(null); }} autoComplete="off" aria-describedby="task-reward-hint" /><span>元</span></div>
              <div className="task-price-presets">{[5, 10, 15, 20, 30].map((amount) => <button type="button" className={rewardCents === amount * 100 ? 'is-selected' : ''} key={amount} onClick={() => { setReward(String(amount)); setError(null); }}>¥{amount}</button>)}</div>
              <div className="task-balance-line" id="task-reward-hint"><span><Icon name="wallet" size={15} /> {balance === null ? '发布时将校验钱包余额' : `钱包可用 ${yuan(balance)}`}</span><a href="#/wallet">查看钱包 <Icon name="arrow-right" size={13} /></a></div>
              <div className="task-voucher-picker">
                <label htmlFor="task-voucher"><Icon name="ticket" size={15} /> 优惠券</label>
                <SelectMenu value={voucherId ?? ''} onChange={value => setVoucherId(value || null)} ariaLabel="选择优惠券"
                            className="task-voucher-select"
                            options={[{ value: '', label: '不使用优惠券' }, ...eligibleVouchers.map(voucher => ({
                              value: voucher.voucherId,
                              label: `满${Number(voucher.thresholdCents ?? 0) / 100}减${Number(voucher.discountCents ?? 0) / 100}（减 ${yuan(Number(voucher.discountCents ?? 0))}）`,
                            }))]}/>
                {eligibleVouchers.length === 0 && <small>暂无满足当前悬赏门槛的可用券</small>}
              </div>
              {selectedVoucher && <div className="task-pay-line">
                实付 <strong>{yuan(paidCents ?? 0)}</strong><span>已减 {yuan(discountCents)}</span>
              </div>}
            </div>
          </fieldset>
          {error && <div className="banner warn" role="alert">{error}</div>}
          <div className="task-compose-footer"><span><Icon name="shield" size={17} /> 悬赏托管，确认完成后结算</span><button className="btn btn-primary task-submit" type="submit" disabled={busy}>{busy ? '正在发布…' : '发布并托管'}{!busy && <Icon name="arrow-right" size={17} />}</button></div>
        </form>
        <aside className="task-publish-aside">
          <section className="panel task-preview"><div className="task-aside-heading"><span>任务预览</span><span className="task-live-tag">实时预览</span></div><div className="task-preview-symbol"><Icon name={TYPE_ICON[type]} size={28} /></div><span className="task-category-label">{TYPE_TEXT[type]}</span><h2 className={!title.trim() ? 'task-placeholder' : ''}>{title.trim() || '你的下一件小事，从这里开始'}</h2>
          {structured && (locationFrom.trim() || locationTo.trim() || expiredAt) && <div className="task-preview-route">
            {locationFrom.trim() && <span><Icon name="pin" size={13} /> {locationFrom.trim()}</span>}
            {locationFrom.trim() && locationTo.trim() && <Icon name="arrow-right" size={12} />}
            {locationTo.trim() && <span><Icon name="pin" size={13} /> {locationTo.trim()}</span>}
            {expiredAt && <span className="task-preview-time"><Icon name="clock" size={13} /> 截止 {new Date(expiredAt).toLocaleString('zh-CN', { month: '2-digit', day: '2-digit', hour: '2-digit', minute: '2-digit', hour12: false })}</span>}
          </div>}
          {description.trim() && <p className="task-preview-desc">{description.trim()}</p>}
          <div className="task-preview-meta"><span><Icon name="user" size={14} /> 1 位同学协助</span><span>发布后等待接单</span></div><div className="task-preview-reward"><span>任务悬赏</span><strong>{rewardCents === null ? '待填写' : yuan(rewardCents)}</strong></div></section>
          <section className="task-publish-guide"><div className="task-guide-title"><Icon name="shield" size={18} /><h3>每一步，都有记录</h3></div><ol><li><span>1</span><div><strong>发布并托管</strong><p>悬赏由平台托管，等待同学接单。</p></div></li><li><span>2</span><div><strong>跟进任务进度</strong><p>接单、取货、送达，动态随时可查。</p></div></li><li><span>3</span><div><strong>完成后确认结算</strong><p>确认帮助已完成，再把悬赏交给对方。</p></div></li></ol><a href="#/mine">查看我发布的任务 <Icon name="arrow-right" size={14} /></a></section>
        </aside>
      </div>
    </div>
  );
}
