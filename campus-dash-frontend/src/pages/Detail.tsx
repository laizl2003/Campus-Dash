import { useCallback, useEffect, useRef, useState } from 'react';
import { api, yuan, STATUS_TEXT, TYPE_TEXT, TYPE_ICON } from '../api';
import type { ErrandCard, StatusChange } from '../api';
import ActionButtons from '../components/ActionButtons';
import Icon from '../components/Icon';
import { subscribeWs } from '../ws';
import { displayUserName } from '../identities';
import './taskPages.css';

interface Props { errandId: string; backHref?: string }

const TERMINAL = new Set(['SETTLED', 'CLOSED', 'CANCELLED', 'REFUNDED']);
const ROLE_TEXT: Record<string, string> = { PUBLISHER: '发单同学', CURRENT_RUNNER: '接单同学', VIEWER: '浏览任务', ARBITRATOR: '仲裁员' };
const STATUS_DESCRIPTION: Record<string, string> = {
  DRAFT: '任务尚未发布。', PUBLISHED: '任务已进入广场，等待一位顺路的同学。',
  LOCKED: '已有同学抢到任务，正在等待确认接单。', ACCEPTED: '对方已确认接单，接下来将前往取货。',
  PICKED_UP: '任务正在进行中，可在下方查看最新进展。', DELIVERED: '对方已标记送达，请发单同学核实完成情况。',
  SETTLED: '这份帮助已完成，悬赏已结算给接单同学。', CANCELLED: '任务已取消，可前往钱包查看资金记录。',
  CLOSED: '任务流程已结束，可在下方回顾处理记录。', DISPUTED: '任务正在处理争议，请等待仲裁结果。', REFUNDED: '任务已退款，可前往钱包查看资金记录。',
};

function formatTime(value: string) {
  const date = new Date(value);
  return Number.isNaN(date.getTime()) ? value : date.toLocaleString('zh-CN', { month: '2-digit', day: '2-digit', hour: '2-digit', minute: '2-digit', second: '2-digit', hour12: false });
}

/** 结构化任务字段只有在真的填了值时才显示 */
function hasRoute(card: ErrandCard) {
  return Boolean(card.locationFrom || card.locationTo || card.expiredAt || card.description);
}

function formatDeadline(value?: string | null) {
  if (!value) return '';
  const date = new Date(value);
  return Number.isNaN(date.getTime()) ? '' : date.toLocaleString('zh-CN', {
    month: '2-digit', day: '2-digit', hour: '2-digit', minute: '2-digit', hour12: false,
  });
}

function operatorName(id: string, card: ErrandCard) {
  if (id === '-1') return '平台自动处理';
  if (id === card.publisherId) return `${card.publisherName || displayUserName(id)} · 发单同学`;
  if (id === card.grabberId) return `${card.grabberName || displayUserName(id)} · 接单同学`;
  return displayUserName(id);
}

export default function Detail({ errandId, backHref = '#/square' }: Props) {
  const [card, setCard] = useState<ErrandCard | null>(null);
  const [timeline, setTimeline] = useState<StatusChange[]>([]);
  const [error, setError] = useState<string | null>(null);
  const [timelineError, setTimelineError] = useState(false);
  const [loading, setLoading] = useState(true);
  const [refreshing, setRefreshing] = useState(false);
  const [arbitrating, setArbitrating] = useState(false);
  const [arbitrationBusy, setArbitrationBusy] = useState(false);
  const [notice, setNotice] = useState<string | null>(null);
  const [updatedAt, setUpdatedAt] = useState<Date | null>(null);
  const requestVersion = useRef(0);
  const arbitrationPending = useRef(false);

  const load = useCallback(async () => {
    const version = ++requestVersion.current;
    setRefreshing(true);
    const [detailResult, timelineResult] = await Promise.allSettled([api.detail(errandId), api.timeline(errandId)]);
    if (version !== requestVersion.current) return;
    if (detailResult.status === 'fulfilled') {
      setCard(detailResult.value);
      setError(null);
      setUpdatedAt(new Date());
    } else setError(detailResult.reason instanceof Error ? detailResult.reason.message : '任务暂时无法加载，请重试。');
    if (timelineResult.status === 'fulfilled') {
      setTimeline([...timelineResult.value].sort((a, b) => new Date(a.time).getTime() - new Date(b.time).getTime()));
      setTimelineError(false);
    } else setTimelineError(true);
    setLoading(false);
    setRefreshing(false);
  }, [errandId]);

  useEffect(() => {
    setCard(null);
    setTimeline([]);
    setLoading(true);
    setNotice(null);
    setArbitrating(false);
    void load();
    const unsubscribe = subscribeWs((event) => {
      if (event.type === 'errand.status' && String(event.payload.errandId) === errandId) void load();
    });
    return () => { unsubscribe(); requestVersion.current += 1; };
  }, [load, errandId]);

  useEffect(() => {
    if (!card || TERMINAL.has(card.status)) return;
    const timer = window.setInterval(() => { void load(); }, 10000);
    return () => window.clearInterval(timer);
  }, [load, card?.status]);

  const arbitrate = async (favor: 'RUNNER' | 'PUBLISHER') => {
    if (arbitrationPending.current) return;
    arbitrationPending.current = true;
    setArbitrationBusy(true);
    setNotice(null);
    setError(null);
    try {
      const result = await api.arbitrate(errandId, favor);
      const messages: Record<string, string> = {
        SETTLED_TO_RUNNER: '仲裁已完成：支持接单同学，悬赏已结算。',
        REFUNDED_TO_PUBLISHER: '仲裁已完成：支持发单同学，悬赏已退回。',
        ALREADY_DONE: '该争议已处理，请查看最新任务状态。',
        CONFLICT: '任务状态已发生变化，请查看最新记录后重试。',
      };
      setNotice(messages[result.result] || '处理结果已更新，请查看任务最新状态。');
      setArbitrating(false);
      window.dispatchEvent(new Event('dash:changed'));
      await load();
    } catch (err) { setError(err instanceof Error ? err.message : '仲裁提交失败，请重试。'); }
    finally { arbitrationPending.current = false; setArbitrationBusy(false); }
  };

  if (!card) return <div className="task-detail-page"><a className="task-back-link" href={backHref}><Icon name="arrow-left" size={16} /> 返回</a><section className="panel empty-state task-detail-empty"><Icon name={loading ? 'clock' : 'package'} size={32} /><h2>{loading ? '正在打开这份任务' : '暂时无法打开任务'}</h2><p>{loading ? '任务信息与最新进展即将呈现。' : error || '请稍后再试。'}</p>{!loading && <button className="btn btn-primary" onClick={() => void load()} disabled={refreshing}>{refreshing ? '正在重试…' : '重新加载'}</button>}</section></div>;

  const hasRunner = Boolean(card.grabberId && card.grabberId !== '-1');
  const ordinaryActions = card.availableActions.filter((action) => action !== 'ARBITRATE');
  const canArbitrate = card.availableActions.includes('ARBITRATE');
  const fundState = card.status === 'SETTLED' ? '已结算' : card.status === 'REFUNDED' || card.status === 'CANCELLED' ? '查看退款记录' : card.status === 'CLOSED' || card.status === 'DRAFT' ? '查看资金记录' : '平台托管中';

  return (
    <div className="task-detail-page">
      <div className="task-detail-toolbar"><a className="task-back-link" href={backHref}><Icon name="arrow-left" size={16} /> 返回</a><div><span className="task-updated-at">{updatedAt ? `${updatedAt.toLocaleTimeString('zh-CN', { hour: '2-digit', minute: '2-digit', hour12: false })} 更新` : ''}</span><button className="btn btn-ghost" onClick={() => void load()} disabled={refreshing}><Icon name="refresh" size={15} /> {refreshing ? '更新中…' : '刷新进度'}</button></div></div>
      <div className="page-head task-detail-head"><div><span className="eyebrow">ERRAND DETAILS</span><h1>每份帮助，都有迹可循</h1><p>任务信息、当前进度与悬赏状态，在这里一目了然。</p></div></div>
      {error && <div className="banner warn" role="alert">{error}<button className="task-inline-button" disabled={refreshing} onClick={() => void load()}>重试</button></div>}
      {notice && <div className="banner info" role="status">{notice}</div>}
      <div className="task-detail-layout">
        <div className="task-detail-main">
          <section className="panel task-overview">
            <div className="task-overview-top"><span className="task-detail-category"><Icon name={TYPE_ICON[card.type] || 'package'} size={18} /> {TYPE_TEXT[card.type] || '校园任务'}</span><span className={`status status-${card.status}`}>{STATUS_TEXT[card.status] || card.status}</span></div>
            <h2>{card.title}</h2><span className="task-order-id">任务编号 {card.id}</span>
            {hasRoute(card) && <div className="task-route">
              {/* 代排队没有取货/送达，只有一个排队地点 */}
              {card.type === 'QUEUE'
                ? card.locationFrom && <div className="task-route-line">
                    <span className="task-route-point"><Icon name="pin" size={14} /><b>排队地点</b>{card.locationFrom}</span>
                  </div>
                : (card.locationFrom || card.locationTo) && <div className="task-route-line">
                    <span className="task-route-point"><Icon name="pin" size={14} /><b>取货</b>{card.locationFrom || '未填写'}</span>
                    <Icon name="arrow-right" size={14} />
                    <span className="task-route-point"><Icon name="pin" size={14} /><b>送达</b>{card.locationTo || '未填写'}</span>
                  </div>}
              <div className="task-route-time"><Icon name="clock" size={14} /><b>截止时间</b><span>{card.expiredAt ? formatDeadline(card.expiredAt) : '永久有效'}</span></div>
            </div>}
            {card.description && <div className="task-description"><span className="task-description-label">任务内容</span><p>{card.description}</p></div>}
            <div className="task-status-note"><span className="task-status-indicator" /><p>{STATUS_DESCRIPTION[card.status] || '可查看下方进度记录，了解任务的最新变化。'}</p></div>
            <dl className="task-facts"><div><dt>发单同学</dt><dd><Icon name="user" size={15} /><span title={`用户 ID：${card.publisherId}`}>{card.publisherName || displayUserName(card.publisherId)}</span></dd></div><div><dt>接单同学</dt><dd><Icon name="user" size={15} /><span title={hasRunner ? `用户 ID：${card.grabberId}` : undefined}>{hasRunner ? (card.grabberName || displayUserName(card.grabberId!)) : '等待接单'}</span></dd></div><div><dt>接单名额</dt><dd>{card.slotTaken} / {card.slotTotal} 人</dd></div><div><dt>当前身份</dt><dd>{ROLE_TEXT[card.role] || '任务参与者'}</dd></div></dl>
          </section>
          <section className="panel task-progress"><div className="task-panel-title"><div><span className="task-section-icon"><Icon name="clock" size={18} /></span><h2>任务进展</h2></div><span>{timeline.length} 条记录</span></div>{timelineError && <div className="banner warn" role="status">进度记录暂未更新。<button className="task-inline-button" disabled={refreshing} onClick={() => void load()}>重新加载</button></div>}
            {timeline.length === 0 ? <div className="task-timeline-empty"><Icon name="clock" size={24} /><p>{timelineError ? '暂时无法读取进展记录' : '还没有进展记录'}</p><span>任务状态变化后，记录会显示在这里。</span></div> : <ol className="task-timeline">{timeline.map((item, index) => <li key={`${item.time}-${item.to}-${index}`} className={index === timeline.length - 1 ? 'is-latest' : ''}><div className="task-timeline-node">{index === timeline.length - 1 ? <span /> : <Icon name="check" size={11} />}</div><div className="task-timeline-content"><div className="task-timeline-title"><strong>{STATUS_TEXT[item.to] || item.to}</strong>{index === timeline.length - 1 && <span>最新进展</span>}<time dateTime={item.time}>{formatTime(item.time)}</time></div><p>{STATUS_TEXT[item.from] || item.from} <Icon name="arrow-right" size={12} /> {STATUS_TEXT[item.to] || item.to}</p><div className="task-timeline-meta"><span>{operatorName(item.operatorId, card)}</span>{item.round > 0 && <span>第 {item.round + 1} 轮接单</span>}</div></div></li>)}</ol>}
          </section>
        </div>
        <aside className="task-detail-aside">
          <section className="panel task-fund-card"><div className="task-panel-title"><div><Icon name="wallet" size={18} /><h2>任务悬赏</h2></div></div><div className="task-fund-value">{yuan(card.rewardCents)}</div><span className="task-fund-state"><Icon name="shield" size={14} />{fundState}</span><div className="task-fund-breakdown"><div><span>悬赏金额</span><strong>{yuan(card.rewardCents)}</strong></div><div><span>接单轮次</span><strong>第 {card.round + 1} 轮</strong></div></div><p>确认完成后结算给接单同学。任务如有争议，可在支持的阶段发起处理。</p><a href="#/wallet">查看我的钱包 <Icon name="arrow-right" size={14} /></a></section>
          <section className="panel task-action-card"><div className="task-panel-title"><div><Icon name="check" size={18} /><h2>下一步</h2></div></div><p>{canArbitrate ? '核实双方情况后，选择本次悬赏的归属。' : ordinaryActions.length ? '请根据实际完成情况，更新任务进度。' : TERMINAL.has(card.status) ? '这份任务已结束，感谢每一份校园里的互相帮助。' : '当前无需你操作，新的进展会在这里更新。'}</p>{ordinaryActions.length > 0 && <ActionButtons card={{ ...card, availableActions: ordinaryActions }} onChanged={() => void load()} />}
            {canArbitrate && <div className="task-arbitration">{!arbitrating ? <button className="btn btn-primary" onClick={() => setArbitrating(true)}>处理争议 <Icon name="arrow-right" size={16} /></button> : <div className="task-arbitration-choices"><div className="task-arbitration-note"><Icon name="shield" size={17} /><span>选择后将分配全部悬赏 {yuan(card.rewardCents)}，请确认已核实情况。</span></div><button className="btn btn-primary" disabled={arbitrationBusy} onClick={() => void arbitrate('RUNNER')}>支持接单同学 · 结算悬赏</button><button className="btn btn-ghost" disabled={arbitrationBusy} onClick={() => void arbitrate('PUBLISHER')}>支持发单同学 · 全额退款</button><button className="task-inline-button" disabled={arbitrationBusy} onClick={() => setArbitrating(false)}>{arbitrationBusy ? '正在提交处理结果…' : '暂不处理'}</button></div>}</div>}
          </section>
          <div className="task-trust-note"><Icon name="shield" size={19} /><p><strong>安心托管，进度可查</strong><span>任务操作与处理结果会保留在进展记录中。</span></p></div>
        </aside>
      </div>
    </div>
  );
}
