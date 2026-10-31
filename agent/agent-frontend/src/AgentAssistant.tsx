import { useCallback, useEffect, useMemo, useRef, useState } from 'react';
import type { CSSProperties, PointerEvent as ReactPointerEvent } from 'react';
// Agent 前端源码放在 agent/agent-frontend，主前端通过 Vite alias @agent 引用，
// 这样 Agent 的 UI 可以独立演进，又不复制主项目的公共组件。
import Icon from '@app/components/Icon';
import './agentAssistant.css';

type Role = 'user' | 'assistant';

interface ChatMessage {
  id: string;
  role: Role;
  content: string;
  time: string;
  draft?: DraftPayload;
  /** 这张卡片对应的一单已经发布：不能继续被后续消息覆盖，也不能再点发布 */
  publishedId?: string;
  /** 这一单被取消了：卡片冻结成"已取消"，不会被新任务覆盖 */
  cancelled?: boolean;
}

interface ApiDraft {
  type?: string | null;
  title?: string | null;
  location_from?: string | null;
  location_to?: string | null;
  expired_at?: string | null;
  reward_cents?: number | null;
  description?: string | null;
}

interface ApiReply {
  status: 'ASKING' | 'READY';
  message: string;
  draft: ApiDraft;
  missing: string[];
  question: string | null;
  type_label?: string | null;
  /** READY 后识别出的意图：APPEND/FINISH/PUBLISH/EDIT/QUERY/CANCEL/CHITCHAT */
  intent?: string | null;
  can_publish?: boolean;
  /** 用户要求重新展示卡片 */
  show_draft?: boolean;
  /** 用户清空了任务内容 */
  cleared?: boolean;
}

interface DraftPayload {
  type: string;
  title: string;
  reward: string;
  pickup: string;
  delivery: string;
  deadline: string;
  description?: string;
  /** 后端原始草稿：发布时要用分值和 ISO 时间，不能用展示用的字符串 */
  raw: ApiDraft;
}

/** 可逐项编辑的字段 */
type EditableField = 'title' | 'reward' | 'pickup' | 'delivery' | 'deadline' | 'description';

interface HistoryItem {
  /** 面板 id：一段对话 = 一个面板，里面可以有好几单 */
  id: string;
  title: string;
  preview: string;
  time: string;
}

const POSITION_KEY = 'agent.fab.position';
// 一次聊天 = 一个 panel。一单发布后服务端自动换会话，前端不用再管 session。
const PANEL_KEY = 'agent.panel.id';
const FAB_SIZE = 60;
const EDGE = 16;
const DRAG_THRESHOLD = 5;
const AGENT_BASE = '/agent';

const nowLabel = () => new Date().toLocaleTimeString('zh-CN', { hour: '2-digit', minute: '2-digit' });

const welcomeMessage: ChatMessage = {
  id: 'welcome',
  role: 'assistant',
  content: '你好，我是校园助手。告诉我你想发布的任务，缺信息我会逐项问你，信息齐全后生成草稿，确认后再发布。',
  time: nowLabel(),
};

const TYPE_TEXT: Record<string, string> = {
  DELIVERY: '跑腿配送',
  BUY: '帮买',
  QUEUE: '代排队',
  OTHER: '其他帮忙',
};

function yuan(cents?: number | null) {
  if (!cents) return '待填写';
  return (cents / 100).toFixed(2);
}

function formatDateTime(value?: string | null) {
  if (!value) return '待填写';
  const date = new Date(value);
  return Number.isNaN(date.getTime()) ? value : date.toLocaleString('zh-CN', {
    month: '2-digit', day: '2-digit', hour: '2-digit', minute: '2-digit', hour12: false,
  });
}

function toDraftPayload(reply: ApiReply): DraftPayload {
  const d = reply.draft || {};
  return {
    type: reply.type_label || TYPE_TEXT[d.type ?? ''] || '校园任务',
    title: d.title || '待补充任务标题',
    reward: yuan(d.reward_cents),
    pickup: d.location_from || '待填写',
    delivery: d.location_to || '待填写',
    deadline: formatDateTime(d.expired_at),
    description: d.description || undefined,
    raw: d,
  };
}

/** 用现有草稿重新构造一个 ApiReply，供 toDraftPayload 复用展示逻辑 */
function apiReplyOf(draft: DraftPayload): ApiReply {
  return {
    status: 'READY',
    message: '',
    draft: draft.raw,
    missing: [],
    question: null,
    type_label: draft.type,
  };
}

/** 把编辑后的字符串写回原始草稿，保证发布时用的是真实值 */
function applyEdits(raw: ApiDraft, edits: Partial<Record<EditableField, string>>): ApiDraft {
  const next: ApiDraft = { ...raw };
  if (edits.title !== undefined) next.title = edits.title || null;
  if (edits.pickup !== undefined) next.location_from = edits.pickup || null;
  if (edits.delivery !== undefined) next.location_to = edits.delivery || null;
  if (edits.description !== undefined) next.description = edits.description || null;
  if (edits.reward !== undefined) {
    const cents = parseRewardCents(edits.reward);
    if (cents !== null) next.reward_cents = cents;
  }
  if (edits.deadline !== undefined) {
    // datetime-local 的值没有时区，转换后是本地时间对应的 ISO
    const time = new Date(edits.deadline);
    if (!Number.isNaN(time.getTime())) next.expired_at = time.toISOString();
  }
  return next;
}

function parseRewardCents(value: string): number | null {
  const match = /^(\d+)(?:\.(\d{1,2}))?$/.exec(value.trim());
  if (!match) return null;
  const cents = Number(match[1]) * 100 + Number((match[2] || '').padEnd(2, '0'));
  return Number.isSafeInteger(cents) && cents > 0 ? cents : null;
}

/** ISO 时间转成 datetime-local 需要的本地格式 */
function toLocalInput(iso?: string | null): string {
  if (!iso) return '';
  const date = new Date(iso);
  if (Number.isNaN(date.getTime())) return '';
  const pad = (n: number) => String(n).padStart(2, '0');
  return `${date.getFullYear()}-${pad(date.getMonth() + 1)}-${pad(date.getDate())}T${pad(date.getHours())}:${pad(date.getMinutes())}`;
}

async function apiPost<T>(path: string, body?: unknown): Promise<T> {
  // 带上主项目登录态：发布接口要靠它确认发单人身份
  const token = localStorage.getItem('dash_token');
  const resp = await fetch(AGENT_BASE + path, {
    method: 'POST',
    headers: {
      'Content-Type': 'application/json',
      ...(token ? { Authorization: 'Bearer ' + token } : {}),
    },
    body: body === undefined ? undefined : JSON.stringify(body),
  });
  if (!resp.ok) {
    let detail = '';
    try {
      const data = await resp.json();
      if (typeof data?.detail === 'string') detail = data.detail;
    } catch { /* 非 JSON 响应忽略 */ }
    if (detail) throw new Error(detail);
    throw new Error(resp.status === 404 ? '会话已失效，请新建对话' : '助手暂时不可用 HTTP ' + resp.status);
  }
  return await resp.json() as T;
}

async function createPanel(): Promise<string> {
  const data = await apiPost<{ panel_id: string }>('/panels');
  localStorage.setItem(PANEL_KEY, data.panel_id);
  return data.panel_id;
}

/** 当前对话的面板 id；没有就新建一个。 */
async function resolvePanel(): Promise<string> {
  return localStorage.getItem(PANEL_KEY) || await createPanel();
}

interface ApiPanel {
  panel_id: string;
  last_message?: string;
  last_role?: string;
  updated_at?: string;
}

/** 历史 = 面板列表（一次聊天一条），不是会话列表（一单一条）。 */
async function fetchPanels(): Promise<HistoryItem[]> {
  const token = localStorage.getItem('dash_token');
  try {
    const resp = await fetch(AGENT_BASE + '/panels', {
      headers: token ? { Authorization: 'Bearer ' + token } : {},
    });
    if (!resp.ok) return [];
    const data = await resp.json() as { panels?: ApiPanel[] };
    return (data.panels ?? []).map(panel => ({
      id: panel.panel_id,
      title: (panel.last_message || '新的对话').slice(0, 16),
      preview: panel.last_role === 'user' ? '等待助手回复' : '对话进行中',
      time: panel.updated_at
        ? new Date(panel.updated_at).toLocaleString('zh-CN', { month: '2-digit', day: '2-digit', hour: '2-digit', minute: '2-digit', hour12: false })
        : '',
    }));
  } catch {
    return [];
  }
}

/** 打开历史对话：整个面板的消息一次拉回来，跨会话也完整。 */
async function fetchPanelMessages(panelId: string): Promise<ChatMessage[]> {
  try {
    const resp = await fetch(AGENT_BASE + '/panels/' + panelId + '/messages');
    if (!resp.ok) return [];
    const data = await resp.json() as {
      messages?: {
        message_id: string;
        role: Role;
        content: string;
        time?: string;
        meta?: { draft?: ApiDraft; type_label?: string | null; publishedId?: string; cancelled?: boolean } | null;
      }[];
    };
    return (data.messages ?? []).map(item => ({
      // 直接用服务端的雪花 id：跨会话也全局有序
      id: item.message_id,
      role: item.role,
      content: item.content,
      time: item.time
        ? new Date(item.time).toLocaleTimeString('zh-CN', { hour: '2-digit', minute: '2-digit' })
        : '',
      // 卡片状态存在消息 meta 里，刷新后原样画回来
      draft: metaToDraft(item.meta),
      publishedId: item.meta?.publishedId,
      // 后端 JSON_SET 写布尔会落成 1，这里按真值判断，别用 === true
      cancelled: Boolean(item.meta?.cancelled),
    }));
  } catch {
    return [];
  }
}

/** 把消息 meta 还原成卡片展示结构，复用 toDraftPayload 的展示规则 */
function metaToDraft(
  meta?: { draft?: ApiDraft; type_label?: string | null } | null,
): DraftPayload | undefined {
  if (!meta?.draft) return undefined;
  return toDraftPayload({
    status: 'READY',
    message: '',
    draft: meta.draft,
    missing: [],
    question: null,
    type_label: meta.type_label ?? null,
  });
}

async function deleteRemotePanel(panelId: string): Promise<void> {
  try {
    await fetch(AGENT_BASE + '/panels/' + panelId, { method: 'DELETE' });
  } catch { /* 删除失败时本地仍会移除，下次刷新会重新出现 */ }
}
interface Point { x: number; y: number }

function readPosition(): Point | null {
  try {
    const raw = localStorage.getItem(POSITION_KEY);
    if (!raw) return null;
    const parsed = JSON.parse(raw) as Point;
    if (!Number.isFinite(parsed.x) || !Number.isFinite(parsed.y)) return null;
    return parsed;
  } catch {
    return null;
  }
}

function clampPosition(point: Point): Point {
  const maxX = Math.max(EDGE, window.innerWidth - FAB_SIZE - EDGE);
  const maxY = Math.max(EDGE, window.innerHeight - FAB_SIZE - EDGE);
  return {
    x: Math.min(Math.max(EDGE, point.x), maxX),
    y: Math.min(Math.max(EDGE, point.y), maxY),
  };
}

interface DraftCardProps {
  draft: DraftPayload;
  /** 正在编辑：null 表示展示态 */
  editing: Partial<Record<EditableField, string>> | null;
  publishing: boolean;
  publishedId?: string;
  cancelled?: boolean;
  onEditStart: () => void;
  onChange: (field: EditableField, value: string) => void;
  onEditSave: () => void;
  onEditCancel: () => void;
  onCancel: () => void;
  onPublish: () => void;
}

function DraftCard({
  draft, editing, publishing, publishedId, cancelled,
  onEditStart, onChange, onEditSave, onEditCancel, onCancel, onPublish,
}: DraftCardProps) {
  // 其他帮忙类型没有地点和时间，只展示标题与任务内容
  const structured = draft.raw.type === 'DELIVERY' || draft.raw.type === 'BUY' || draft.raw.type === 'QUEUE';

  const field = (label: string, key: EditableField, value: string, className?: string) => (
    <div>
      <dt>{label}</dt>
      <dd className={className}>
        {editing ? (
          <input
            className="agent-draft-input"
            value={editing[key] ?? ''}
            placeholder={key === 'reward' ? '元，如 8' : ''}
            onChange={event => onChange(key, event.target.value)}
          />
        ) : value}
      </dd>
    </div>
  );

  return <div className="agent-draft" role="group" aria-label="任务草稿">
    <div className="agent-draft-head">
      <span className="agent-draft-badge"><Icon name="sparkles" size={13}/>任务草稿</span>
      <span className="agent-draft-type">{draft.type}</span>
    </div>

    {editing ? (
      <label className="agent-draft-title-edit">
        <span>标题</span>
        <input className="agent-draft-input" value={editing.title ?? ''} maxLength={64}
               onChange={event => onChange('title', event.target.value)}/>
      </label>
    ) : <strong className="agent-draft-title">{draft.title}</strong>}

    <dl className="agent-draft-fields">
      {field('悬赏', 'reward', draft.reward, 'agent-draft-reward')}
      {/* 代排队只有一个排队地点（存在 location_from），没有送达地 */}
      {structured && (draft.raw.type === 'QUEUE'
        ? field('排队地点', 'pickup', draft.pickup)
        : field('取货', 'pickup', draft.pickup))}
      {structured && draft.raw.type !== 'QUEUE' && field('送达', 'delivery', draft.delivery)}
      {structured && editing
        ? <div>
            <dt>截止</dt>
            <dd><input className="agent-draft-input" type="datetime-local"
                       value={editing.deadline ?? ''}
                       onChange={event => onChange('deadline', event.target.value)}/></dd>
          </div>
        : structured && <div><dt>截止</dt><dd>{draft.deadline}</dd></div>}
    </dl>

    {(editing || draft.description) && (
      <div className="agent-draft-desc">
        <span>任务内容</span>
        {editing
          ? <textarea className="agent-draft-input" rows={2} maxLength={300}
                      value={editing.description ?? ''}
                      onChange={event => onChange('description', event.target.value)}/>
          : <p>{draft.description}</p>}
      </div>
    )}

    {cancelled ? (
      <div className="agent-draft-cancelled">
        <Icon name="close" size={14}/>已取消 · 本次任务未发布
      </div>
    ) : publishedId ? (
      <div className="agent-draft-published">
        <Icon name="check" size={14}/>已发布 · 编号 {publishedId}
      </div>
    ) : editing ? (
      <div className="agent-draft-actions">
        <button type="button" className="agent-btn agent-btn-ghost" onClick={onEditCancel}>放弃修改</button>
        <button type="button" className="agent-btn agent-btn-primary" onClick={onEditSave}>保存修改</button>
      </div>
    ) : (
      <div className="agent-draft-actions">
        <button type="button" className="agent-btn agent-btn-ghost" onClick={onEditStart}>修改</button>
        <button type="button" className="agent-btn agent-btn-cancel" onClick={onCancel}>取消</button>
        <button type="button" className="agent-btn agent-btn-primary" disabled={publishing} onClick={onPublish}>
          {publishing ? '发布中…' : '确认发布'}
        </button>
      </div>
    )}
  </div>;
}

export default function AgentAssistant() {
  const [open, setOpen] = useState(false);
  const [historyOpen, setHistoryOpen] = useState(false);
  const [messages, setMessages] = useState<ChatMessage[]>([welcomeMessage]);
  const [input, setInput] = useState('');
  const [busy, setBusy] = useState(false);
  const [notice, setNotice] = useState('');
  const [history, setHistory] = useState<HistoryItem[]>([]);
  // 删除是两步确认：先点 × 进入待删状态，再点"删除"
  const [deletingId, setDeletingId] = useState<string | null>(null);
  // 正在编辑的草稿消息 id 与编辑中的值
  const [editingId, setEditingId] = useState<string | null>(null);
  const [editing, setEditing] = useState<Partial<Record<EditableField, string>> | null>(null);
  const [publishingId, setPublishingId] = useState<string | null>(null);
  const [published, setPublished] = useState<Record<string, string>>({});
  const [panelId, setPanelId] = useState<string | null>(() => localStorage.getItem(PANEL_KEY));
  const [position, setPosition] = useState<Point | null>(null);
  const [dragging, setDragging] = useState(false);
  const fabRef = useRef<HTMLButtonElement>(null);
  const inputRef = useRef<HTMLTextAreaElement>(null);
  const listRef = useRef<HTMLDivElement>(null);
  const dragRef = useRef<{ startX: number; startY: number; originX: number; originY: number; moved: boolean } | null>(null);
  const timerRef = useRef<number | null>(null);

  useEffect(() => {
    const saved = readPosition();
    const initial = saved ?? { x: window.innerWidth - FAB_SIZE - 24, y: window.innerHeight - FAB_SIZE - 28 };
    setPosition(clampPosition(initial));
  }, []);

  useEffect(() => {
    const onResize = () => setPosition(prev => (prev ? clampPosition(prev) : prev));
    window.addEventListener('resize', onResize);
    return () => window.removeEventListener('resize', onResize);
  }, []);

  useEffect(() => {
    if (!position || dragging) return;
    try { localStorage.setItem(POSITION_KEY, JSON.stringify(position)); } catch { /* 忽略存储失败 */ }
  }, [position, dragging]);

  useEffect(() => {
    if (!open) return;
    const timer = window.setTimeout(() => inputRef.current?.focus(), 180);
    return () => window.clearTimeout(timer);
  }, [open]);

  useEffect(() => {
    if (!open) return;
    const onKey = (event: KeyboardEvent) => {
      if (event.key === 'Escape') {
        if (historyOpen) setHistoryOpen(false);
        else setOpen(false);
      }
    };
    window.addEventListener('keydown', onKey);
    return () => window.removeEventListener('keydown', onKey);
  }, [open, historyOpen]);

  useEffect(() => {
    if (!listRef.current) return;
    listRef.current.scrollTop = listRef.current.scrollHeight;
  }, [messages, busy]);

  useEffect(() => () => {
    if (timerRef.current) window.clearTimeout(timerRef.current);
  }, []);

  // 打开面板时确保后端有这段对话，并同步历史列表
  useEffect(() => {
    if (!open) return;
    let active = true;
    resolvePanel()
      .then(async id => {
        if (!active) return;
        setPanelId(id);
        // 刷新后自动把这段对话的消息和卡片补回来（还没开始聊才回填）
        const loaded = await fetchPanelMessages(id);
        if (!active || loaded.length === 0) return;
        setMessages(prev => (prev.length <= 1 ? loaded : prev));
      })
      .catch(() => { if (active) setNotice('暂时连不上助手服务，请稍后再试'); });
    // 历史以后端为准：换设备、清缓存都能看到完整记录
    fetchPanels()
      .then(items => { if (active) setHistory(items); })
      .catch(() => { /* 拉取失败就保持空列表 */ });
    return () => { active = false; };
  }, [open]);

  const dismissNotice = useCallback(() => setNotice(''), []);
  useEffect(() => {
    if (!notice) return;
    const timer = window.setTimeout(() => setNotice(''), 2600);
    return () => window.clearTimeout(timer);
  }, [notice]);

  const onFabPointerDown = (event: ReactPointerEvent<HTMLButtonElement>) => {
    if (event.button !== 0) return;
    const current = position;
    if (!current) return;
    dragRef.current = {
      startX: event.clientX,
      startY: event.clientY,
      originX: current.x,
      originY: current.y,
      moved: false,
    };
    setDragging(true);
    event.currentTarget.setPointerCapture(event.pointerId);
  };

  const onFabPointerMove = (event: ReactPointerEvent<HTMLButtonElement>) => {
    const drag = dragRef.current;
    if (!drag) return;
    const dx = event.clientX - drag.startX;
    const dy = event.clientY - drag.startY;
    if (!drag.moved && Math.hypot(dx, dy) > DRAG_THRESHOLD) drag.moved = true;
    if (!drag.moved) return;
    setPosition(clampPosition({ x: drag.originX + dx, y: drag.originY + dy }));
  };

  const onFabPointerUp = (event: ReactPointerEvent<HTMLButtonElement>) => {
    const drag = dragRef.current;
    dragRef.current = null;
    setDragging(false);
    if (event.currentTarget.hasPointerCapture(event.pointerId)) {
      event.currentTarget.releasePointerCapture(event.pointerId);
    }
    if (drag && !drag.moved) {
      // 点击和拖动共用指针事件；由 pointerup 决定开合，避免 click 紧随其后把刚打开的面板又关掉
      setOpen(value => !value);
    }
  };

  const send = async (raw?: string) => {
    const text = (raw ?? input).trim();
    if (!text || busy) return;
    setInput('');
    const userMessage: ChatMessage = {
      id: Math.random().toString(36).slice(2), role: 'user', content: text, time: nowLabel(),
    };
    setMessages(prev => [...prev, userMessage]);
    setBusy(true);
    try {
      // 只发 panel：用哪个会话由服务端决定（发布后自然换新会话）
      const pid = await resolvePanel();
      if (!panelId) setPanelId(pid);
      const reply = await apiPost<ApiReply>('/chat', { panel_id: pid, message: text });
      const assistant: ChatMessage = {
        id: Math.random().toString(36).slice(2),
        role: 'assistant',
        content: reply.message,
        time: nowLabel(),
        // 只有信息齐全才展示草稿卡片；追问阶段只显示问题
        draft: reply.status === 'READY' ? toDraftPayload(reply) : undefined,
      };

      // 用户说"取消"：清掉草稿卡片，回到全新对话
      if (reply.intent === 'CANCEL') {
        // 服务端已把会话置为 CANCELED 并标记卡片，这里同步冻结本地那张卡片
        setMessages(prev => {
          const index = prev
            .map(item => Boolean(item.draft) && !item.publishedId && !item.cancelled)
            .lastIndexOf(true);
          const next = index === -1
            ? prev
            : prev.map((item, i) => (i === index ? { ...item, cancelled: true } : item));
          return [...next, { ...assistant, draft: undefined }];
        });
        void refreshHistory();
        return;
      }

      // 用户要求"再看看"：在聊天窗底部再插一张当前草稿的卡片，
      // 而不是把旧卡片挪过去——用户就是想在当前位置再看一遍。
      if (reply.status === 'READY' && reply.show_draft) {
        setMessages(prev => {
          const latestDraft = [...prev].reverse()
            .find(item => item.draft && !item.publishedId)?.draft;
          return [...prev, { ...assistant, draft: latestDraft ?? assistant.draft }];
        });
        void refreshHistory();
        return;
      }

      // 已经出过草稿时，后续补充只更新那张卡片，不重复插入新卡片。
      // 否则用户会看到多张描述不同的草稿，点「修改」「发布」时容易操作错对象。
      if (reply.status === 'READY') {
        setMessages(prev => {
          // 只认"还没发布"的卡片。已发布的那张属于上一单，是历史记录，
          // 新任务必须另起一张，否则会把上一单的卡片改写成这一单的内容。
          const lastDraftIndex = prev
            .map(item => Boolean(item.draft) && !item.publishedId)
            .lastIndexOf(true);
          if (lastDraftIndex === -1) return [...prev, assistant];
          const next = prev.map((item, index) => index === lastDraftIndex
            ? { ...item, draft: assistant.draft }
            : item);
          // 补充说明本身作为一条普通消息追加，让用户看到自己说了什么
          return [...next, { ...assistant, draft: undefined }];
        });
        void refreshHistory();
        return;
      }
      setMessages(prev => [...prev, assistant]);
      void refreshHistory();
    } catch (reason) {
      const message = reason instanceof Error ? reason.message : '助手暂时不可用';
      // 面板失效（被删/换账号）时清掉本地 id，下次发送自动重建
      if (message.includes('会话已失效')) {
        localStorage.removeItem(PANEL_KEY);
        setPanelId(null);
      }
      setMessages(prev => [...prev, {
        id: Math.random().toString(36).slice(2), role: 'assistant', content: message, time: nowLabel(),
      }]);
    } finally {
      setBusy(false);
    }
  };

  /** 进入逐项修改：把当前草稿值填进编辑框 */
  const onEditStart = (messageId: string, draft: DraftPayload) => {
    setEditingId(messageId);
    setEditing({
      title: draft.raw.title ?? draft.title ?? '',
      reward: draft.raw.reward_cents ? String(draft.raw.reward_cents / 100) : '',
      pickup: draft.raw.location_from ?? draft.pickup ?? '',
      delivery: draft.raw.location_to ?? draft.delivery ?? '',
      deadline: toLocalInput(draft.raw.expired_at),
      // description 用 raw 与展示值兜底，避免 raw 未及时更新时被清空
      description: draft.raw.description ?? draft.description ?? '',
    });
  };

  const onEditCancel = () => {
    setEditingId(null);
    setEditing(null);
  };

  /** 保存修改：把编辑值写回消息里的草稿，卡片立即刷新 */
  const onEditSave = (messageId: string) => {
    if (!editing) return;
    setMessages(prev => prev.map(item => {
      if (item.id !== messageId || !item.draft) return item;
      const base = item.draft.raw;
      const raw = applyEdits(base, editing);
      // 发布必须有完整值：编辑时留空的地方回退到原值，避免空串把已有内容抹掉
      if (!raw.description) raw.description = base.description ?? item.draft.description ?? null;
      if (!raw.title) raw.title = base.title ?? null;
      if (!raw.reward_cents) raw.reward_cents = base.reward_cents ?? null;
      return { ...item, draft: toDraftPayload({ ...apiReplyOf(item.draft), draft: raw }) };
    }));
    setEditingId(null);
    setEditing(null);
    setNotice('已更新草稿');
  };

  /** 确认发布：调用主项目真实发单接口 */
  const onPublish = async (messageId: string, draft: DraftPayload) => {
    if (publishingId) return;
    const raw = draft.raw;
    if (!raw.type || !raw.title) { setNotice('标题或类型缺失，无法发布'); return; }
    if (!raw.reward_cents) { setNotice('请先填写悬赏金额'); return; }

    setPublishingId(messageId);
    // 只传面板：服务端会把该面板下进行中的会话标记为结束，下一次发言自动换新会话
    const pid = panelId ?? localStorage.getItem(PANEL_KEY) ?? null;
    try {
      const result = await apiPost<{ errandId: string }>('/publish', {
        campusId: 1,
        type: raw.type,
        title: raw.title,
        rewardCents: raw.reward_cents,
        slotTotal: 1,
        locationFrom: raw.location_from ?? null,
        locationTo: raw.location_to ?? null,
        expiredAt: raw.expired_at ?? null,
        description: raw.description ?? null,
        // 让后端把这条会话标记成 CLOSED，避免从历史点回来后继续用旧草稿
        panelId: pid,
      });
      setPublished({ [messageId]: result.errandId });
      setNotice('任务已发布');
      // 通知主项目刷新广场与钱包，和页面内发单保持一致
      window.dispatchEvent(new Event('dash:changed'));
      setMessages(prev => {
        const next = [
          // 把这张卡片标记成"已发布"：后续新任务不能再覆盖它，
          // 否则第二单会把第一单的卡片改掉，历史里就查不到上一单了。
          ...prev.map(item => item.id === messageId
            ? { ...item, publishedId: result.errandId }
            : item),
          {
          id: Math.random().toString(36).slice(2),
          role: 'assistant' as const,
          content: `任务已发布，编号 ${result.errandId}。可以在「我的任务」里查看进度。`,
          time: nowLabel(),
        }, {
          id: Math.random().toString(36).slice(2),
          role: 'assistant' as const,
          content: '这一单已经结束，接下来想发什么任务？直接告诉我就行。',
          time: nowLabel(),
        }];
        return next;
      });
      // 不用手动换会话：服务端已把这一单的会话置为 CLOSED，
      // 下一条消息会自动开一个新会话，第二单不会写进旧草稿。
      void refreshHistory();
    } catch (reason) {
      const text = reason instanceof Error ? reason.message : '发布失败';
      setNotice(text);
      setMessages(prev => [...prev, {
        id: Math.random().toString(36).slice(2), role: 'assistant', content: `发布没有成功：${text}`, time: nowLabel(),
      }]);
    } finally {
      setPublishingId(null);
    }
  };

  /** 取消这一单：和发布同款收尾——卡片冻结成"已取消"，会话结束，面板继续可用 */
  const onCancelTask = async (messageId: string) => {
    if (publishingId) return;
    setPublishingId(messageId);
    const pid = panelId ?? localStorage.getItem(PANEL_KEY);
    try {
      if (pid) await apiPost('/panels/' + pid + '/cancel', {});
      setNotice('已取消本次任务');
      setMessages(prev => [
        // 卡片保留在原地，只把状态改成"已取消"，和新任务互不干扰
        ...prev.map(item => item.id === messageId
          ? { ...item, cancelled: true, draft: item.draft ? { ...item.draft, raw: item.draft.raw } : item.draft }
          : item),
        {
          id: Math.random().toString(36).slice(2),
          role: 'assistant' as const,
          content: '任务已取消，这一单不会发布。',
          time: nowLabel(),
        }, {
          id: Math.random().toString(36).slice(2),
          role: 'assistant' as const,
          content: '接下来想发什么任务？直接告诉我就行。',
          time: nowLabel(),
        },
      ]);
      void refreshHistory();
    } catch (reason) {
      setNotice(reason instanceof Error ? reason.message : '取消失败');
    } finally {
      setPublishingId(null);
    }
  };

  const startNewChat = async () => {
    setMessages([welcomeMessage]);
    setHistoryOpen(false);
    setInput('');
    // 换一个面板就是换一段新对话；旧对话仍留在历史里
    localStorage.removeItem(PANEL_KEY);
    setPanelId(null);
    try {
      const pid = await createPanel();
      setPanelId(pid);
    } catch {
      /* 建面板失败就让下次发送时再建 */
    }
    void refreshHistory();
    setNotice('已新建对话');
  };

  const loadHistory = async (item: HistoryItem) => {
    setHistoryOpen(false);
    setDeletingId(null);
    // 消息一律从后端按 panel 拉：跨会话也完整，不依赖本地快照
    const loaded = await fetchPanelMessages(item.id);
    setMessages(loaded.length > 0 ? loaded : [welcomeMessage]);
    // 之后的发言都落在这个面板下；面板里若有"进行中"的会话，服务端会复用
    setPanelId(item.id);
    localStorage.setItem(PANEL_KEY, item.id);
  };

  const deleteHistory = async (id: string) => {
    await deleteRemotePanel(id);
    setHistory(prev => prev.filter(item => item.id !== id));
    setDeletingId(null);
    // 删掉的正是当前对话：清空界面并让下次发送自动新建
    if (id === panelId) {
      setMessages([welcomeMessage]);
      localStorage.removeItem(PANEL_KEY);
      setPanelId(null);
    }
    setNotice('已删除该对话');
  };

  /** 历史列表以后端为准，每次交互后刷新一次即可 */
  const refreshHistory = async () => {
    const items = await fetchPanels();
    setHistory(items);
  };

  const fabStyle: CSSProperties = position
    ? { left: position.x, top: position.y }
    : { visibility: 'hidden' };

  /**
   * 面板跟随悬浮球。
   *
   * 尺寸与 CSS 完全一致（min(400px, 100vw-32px) / min(620px, 100vh-140px)），
   * 这里只是算出它该出现在悬浮球的哪一侧，再把坐标夹回视口内，
   * 所以外观、大小都不变，只是位置跟着球走。
   */
  const panelStyle: CSSProperties = (() => {
    if (!position) return { visibility: 'hidden' };
    const vw = window.innerWidth;
    const vh = window.innerHeight;
    // 与 CSS 的 min() 相同的算法
    const panelW = Math.min(400, vw - 32);
    const panelH = Math.min(620, vh - 140);
    const gap = 12;

    // 水平：优先让面板左边对齐球；放不下就贴右；再不行贴左
    let left = position.x + FAB_SIZE - panelW;
    if (left + panelW > vw - gap) left = vw - gap - panelW;
    if (left < gap) left = gap;

    // 垂直：球在屏幕下半部时面板放上面，否则放下面
    const above = position.y + FAB_SIZE / 2 > vh / 2;
    let top = above ? position.y - panelH - gap : position.y + FAB_SIZE + gap;
    if (top + panelH > vh - gap) top = vh - gap - panelH;
    if (top < gap) top = gap;

    return { left, top, right: 'auto', bottom: 'auto', width: panelW, height: panelH };
  })();

  // 只发任务类型，具体信息交给后面追问，避免用户被预填内容带偏
  const suggestions = useMemo(() => [
    { icon: 'package', text: '取快递', prompt: '取快递' },
    { icon: 'bag', text: '帮买奶茶', prompt: '帮买奶茶' },
    { icon: 'clock', text: '代排队', prompt: '代排队' },
  ], []);

  return <>
    <button
      ref={fabRef}
      type="button"
      className={`agent-fab${dragging ? ' is-dragging' : ''}${open ? ' is-open' : ''}`}
      style={fabStyle}
      aria-label="打开 AI 校园助手"
      aria-expanded={open}
      title="AI 校园助手 · 可拖动"
      onPointerDown={onFabPointerDown}
      onPointerMove={onFabPointerMove}
      onPointerUp={onFabPointerUp}
      onPointerCancel={() => { dragRef.current = null; setDragging(false); }}
    >
      <span className="agent-fab-glow" aria-hidden="true"/>
      <Icon name="bot" size={27}/>
    </button>

    {open && <section className="agent-panel" style={panelStyle} role="dialog" aria-modal="false" aria-label="AI 校园助手">
      <header className="agent-panel-head">
        <span className="agent-avatar"><Icon name="bot" size={20}/></span>
        <div className="agent-panel-title">
          <strong>CampusDash 智能助手</strong>
          <small><i className="agent-dot"/>{panelId ? '对话式发单 · 已连接' : '对话式发单 · 连接中'}</small>
        </div>
        <button type="button" className="agent-icon-btn" aria-label="历史对话" title="历史对话"
                onClick={() => setHistoryOpen(value => !value)}><Icon name="history" size={17}/></button>
        <button type="button" className="agent-icon-btn" aria-label="新建对话" title="新建对话"
                onClick={startNewChat}><Icon name="plus" size={17}/></button>
        <button type="button" className="agent-icon-btn" aria-label="关闭助手" title="关闭"
                onClick={() => setOpen(false)}><Icon name="close" size={17}/></button>
      </header>

      {historyOpen && <div className="agent-history">
        <div className="agent-history-head">
          <strong>历史对话</strong>
          <button type="button" className="agent-icon-btn" aria-label="关闭历史" onClick={() => setHistoryOpen(false)}><Icon name="close" size={15}/></button>
        </div>
        {history.length === 0 && <p className="agent-history-hint">还没有历史对话，发一条消息就会记录在这里。</p>}
        {history.map(item => <div key={item.id} className={`agent-history-item${deletingId === item.id ? ' is-deleting' : ''}`}>
          <button type="button" className="agent-history-open" onClick={() => loadHistory(item)}>
            <span className="agent-history-icon"><Icon name="history" size={15}/></span>
            <span className="agent-history-copy"><strong>{item.title}</strong><small>{item.preview}</small></span>
          </button>
          <time className="agent-history-time">{item.time}</time>
          {deletingId === item.id ? (
            <span className="agent-history-confirm">
              <button type="button" className="agent-history-danger" onClick={() => deleteHistory(item.id)}>删除</button>
              <button type="button" className="agent-history-undo" onClick={() => setDeletingId(null)}>取消</button>
            </span>
          ) : (
            <button type="button" className="agent-history-delete" aria-label="删除这条对话"
                    title="删除这条对话" onClick={() => setDeletingId(item.id)}>
              <Icon name="close" size={13}/>
            </button>
          )}
        </div>)}

      </div>}

      <div className="agent-messages" ref={listRef} aria-live="polite">
        {messages.map(message => <div key={message.id} className={`agent-row is-${message.role}`}>
          {message.role === 'assistant' && <span className="agent-row-avatar"><Icon name="bot" size={15}/></span>}
          <div className="agent-row-body">
            <div className="agent-bubble">{message.content}</div>
            {message.draft && <DraftCard
          draft={message.draft}
          editing={editingId === message.id ? editing : null}
          publishing={publishingId === message.id}
          publishedId={message.publishedId ?? published[message.id]}
          cancelled={message.cancelled}
          onEditStart={() => onEditStart(message.id, message.draft!)}
          onChange={(field, value) => setEditing(prev => ({ ...(prev ?? {}), [field]: value }))}
          onEditSave={() => onEditSave(message.id)}
          onEditCancel={onEditCancel}
          onCancel={() => void onCancelTask(message.id)}
          onPublish={() => void onPublish(message.id, message.draft!)}
        />}
            <time className="agent-row-time">{message.time}</time>
          </div>
        </div>)}
        {busy && <div className="agent-row is-assistant">
          <span className="agent-row-avatar"><Icon name="bot" size={15}/></span>
          <div className="agent-typing" aria-label="正在输入"><i/><i/><i/></div>
        </div>}
        {messages.length === 1 && <div className="agent-suggest">
          <span className="agent-suggest-label">试试这样说</span>
          {suggestions.map(item => <button type="button" key={item.text} className="agent-suggest-chip"
                  onClick={() => send(item.prompt)}>
            <Icon name={item.icon} size={14}/>{item.text}
          </button>)}
        </div>}
      </div>

      <div className="agent-composer">
        <textarea
          ref={inputRef}
          rows={1}
          value={input}
          placeholder="描述你要发布的任务…"
          aria-label="输入任务描述"
          onChange={event => setInput(event.target.value)}
          onKeyDown={event => {
            if (event.key === 'Enter' && !event.shiftKey) {
              event.preventDefault();
              send();
            }
          }}
        />
        <button type="button" className="agent-send" aria-label="发送" title="发送"
                disabled={!input.trim() || busy} onClick={() => send()}>
          <Icon name="send" size={17}/>
        </button>
        <p className="agent-composer-hint">Enter 发送 · Shift + Enter 换行</p>
      </div>

      {notice && <div className="agent-notice" role="status">{notice}<button type="button" aria-label="关闭提示" onClick={dismissNotice}><Icon name="close" size={13}/></button></div>}
    </section>}
  </>;
}
