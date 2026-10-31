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
}

interface ApiDraft {
  type?: string | null;
  title?: string | null;
  location_from?: string | null;
  location_to?: string | null;
  start_time?: string | null;
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
}

interface DraftPayload {
  type: string;
  title: string;
  reward: string;
  pickup: string;
  delivery: string;
  deadline: string;
  description?: string;
}

interface HistoryItem {
  id: string;
  title: string;
  preview: string;
  time: string;
  messages: ChatMessage[];
}

const POSITION_KEY = 'agent.fab.position';
const SESSION_KEY = 'agent.session.id';
const HISTORY_KEY = 'agent.history';
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
    deadline: formatDateTime(d.start_time),
    description: d.description || undefined,
  };
}

async function apiPost<T>(path: string, body?: unknown): Promise<T> {
  const resp = await fetch(AGENT_BASE + path, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: body === undefined ? undefined : JSON.stringify(body),
  });
  if (!resp.ok) {
    throw new Error(resp.status === 404 ? '会话已失效，请新建对话' : '助手暂时不可用 HTTP ' + resp.status);
  }
  return await resp.json() as T;
}

async function createSession(): Promise<string> {
  const data = await apiPost<{ session_id: string }>('/sessions');
  localStorage.setItem(SESSION_KEY, data.session_id);
  return data.session_id;
}

async function ensureSession(): Promise<string> {
  const saved = localStorage.getItem(SESSION_KEY);
  return saved || await createSession();
}

function readHistory(): HistoryItem[] {
  try {
    const raw = JSON.parse(localStorage.getItem(HISTORY_KEY) || '[]');
    return Array.isArray(raw) ? raw as HistoryItem[] : [];
  } catch {
    return [];
  }
}

function writeHistory(items: HistoryItem[]) {
  try { localStorage.setItem(HISTORY_KEY, JSON.stringify(items.slice(0, 10))); } catch { /* ignore */ }
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

function DraftCard({ draft, onAction }: { draft: DraftPayload; onAction: (text: string) => void }) {
  return <div className="agent-draft" role="group" aria-label="任务草稿">
    <div className="agent-draft-head">
      <span className="agent-draft-badge"><Icon name="sparkles" size={13}/>任务草稿</span>
      <span className="agent-draft-type">{draft.type}</span>
    </div>
    <strong className="agent-draft-title">{draft.title}</strong>
    <dl className="agent-draft-fields">
      <div><dt>悬赏</dt><dd className="agent-draft-reward">¥ {draft.reward}</dd></div>
      <div><dt>取货</dt><dd>{draft.pickup}</dd></div>
      <div><dt>送达</dt><dd>{draft.delivery}</dd></div>
      <div><dt>截止</dt><dd>{draft.deadline}</dd></div>
    </dl>
    <div className="agent-draft-actions">
      <button type="button" className="agent-btn agent-btn-ghost" onClick={() => onAction('修改')}>修改</button>
      <button type="button" className="agent-btn agent-btn-cancel" onClick={() => onAction('取消')}>取消</button>
      <button type="button" className="agent-btn agent-btn-primary" onClick={() => onAction('确认发布')}>确认发布</button>
    </div>
  </div>;
}

export default function AgentAssistant() {
  const [open, setOpen] = useState(false);
  const [historyOpen, setHistoryOpen] = useState(false);
  const [messages, setMessages] = useState<ChatMessage[]>([welcomeMessage]);
  const [input, setInput] = useState('');
  const [busy, setBusy] = useState(false);
  const [notice, setNotice] = useState('');
  const [history, setHistory] = useState<HistoryItem[]>(() => readHistory());
  const [sessionId, setSessionId] = useState<string | null>(() => localStorage.getItem(SESSION_KEY));
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

  // 打开面板时确保后端会话存在，失败不阻塞输入，只提示用户
  useEffect(() => {
    if (!open || sessionId) return;
    let active = true;
    ensureSession()
      .then(id => { if (active) setSessionId(id); })
      .catch(() => { if (active) setNotice('暂时连不上助手服务，请稍后再试'); });
    return () => { active = false; };
  }, [open, sessionId]);

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
      const sid = sessionId ?? await ensureSession();
      if (!sessionId) setSessionId(sid);
      const reply = await apiPost<ApiReply>('/chat', { session_id: sid, message: text });
      const assistant: ChatMessage = {
        id: Math.random().toString(36).slice(2),
        role: 'assistant',
        content: reply.message,
        time: nowLabel(),
        // 只有信息齐全才展示草稿卡片；追问阶段只显示问题
        draft: reply.status === 'READY' ? toDraftPayload(reply) : undefined,
      };
      setMessages(prev => {
        const next = [...prev, assistant];
        writeHistory([{
          id: sid,
          title: next.find(item => item.role === 'user')?.content.slice(0, 16) || '新的任务对话',
          preview: assistant.content,
          time: nowLabel(),
          messages: next,
        }, ...readHistory().filter(item => item.id !== sid)]);
        setHistory(readHistory());
        return next;
      });
    } catch (reason) {
      const message = reason instanceof Error ? reason.message : '助手暂时不可用';
      // 会话失效时清掉本地 id，下次发送自动重建
      if (message.includes('会话已失效')) {
        localStorage.removeItem(SESSION_KEY);
        setSessionId(null);
      }
      setMessages(prev => [...prev, {
        id: Math.random().toString(36).slice(2), role: 'assistant', content: message, time: nowLabel(),
      }]);
    } finally {
      setBusy(false);
    }
  };

  const onDraftAction = (action: string) => {
    if (action === '确认发布') {
      setNotice('草稿已生成，发布接口将在下一步接入');
    } else if (action === '取消') {
      setNotice('已取消本次任务草稿');
      setMessages([welcomeMessage]);
      localStorage.removeItem(SESSION_KEY);
      setSessionId(null);
    } else {
      setNotice('继续补充或修改，直接发消息即可');
    }
  };

  const startNewChat = () => {
    setMessages([welcomeMessage]);
    setHistoryOpen(false);
    setInput('');
    localStorage.removeItem(SESSION_KEY);
    setSessionId(null);
    setNotice('已新建对话');
  };

  const loadHistory = (item: HistoryItem) => {
    setMessages(item.messages);
    setHistoryOpen(false);
  };

  const fabStyle: CSSProperties = position
    ? { left: position.x, top: position.y }
    : { visibility: 'hidden' };

  const suggestions = useMemo(() => [
    { icon: 'package', text: '取快递', prompt: '明天下午三点帮我去菜鸟驿站取快递，送到 3 号楼 502，悬赏 8 块' },
    { icon: 'bag', text: '帮买奶茶', prompt: '帮我买一杯少冰的珍珠奶茶，送到 2 号楼 301，悬赏 5 块' },
    { icon: 'clock', text: '代排队', prompt: '明天中午帮我在食堂二楼排队打饭，送到图书馆，悬赏 6 块' },
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

    {open && <section className="agent-panel" role="dialog" aria-modal="false" aria-label="AI 校园助手">
      <header className="agent-panel-head">
        <span className="agent-avatar"><Icon name="bot" size={20}/></span>
        <div className="agent-panel-title">
          <strong>CampusDash 智能助手</strong>
          <small><i className="agent-dot"/>{sessionId ? '对话式发单 · 已连接' : '对话式发单 · 连接中'}</small>
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
        {history.map(item => <button type="button" key={item.id} className="agent-history-item" onClick={() => loadHistory(item)}>
          <span className="agent-history-icon"><Icon name="history" size={15}/></span>
          <span className="agent-history-copy"><strong>{item.title}</strong><small>{item.preview}</small></span>
          <time>{item.time}</time>
        </button>)}

      </div>}

      <div className="agent-messages" ref={listRef} aria-live="polite">
        {messages.map(message => <div key={message.id} className={`agent-row is-${message.role}`}>
          {message.role === 'assistant' && <span className="agent-row-avatar"><Icon name="bot" size={15}/></span>}
          <div className="agent-row-body">
            <div className="agent-bubble">{message.content}</div>
            {message.draft && <DraftCard draft={message.draft} onAction={onDraftAction}/>}
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
