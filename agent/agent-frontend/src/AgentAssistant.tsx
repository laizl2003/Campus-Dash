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

interface DraftPayload {
  type: string;
  title: string;
  reward: string;
  pickup: string;
  delivery: string;
  deadline: string;
}

interface HistoryItem {
  id: string;
  title: string;
  preview: string;
  time: string;
  messages: ChatMessage[];
}

const POSITION_KEY = 'agent.fab.position';
const FAB_SIZE = 60;
const EDGE = 16;
const DRAG_THRESHOLD = 5;

const nowLabel = () => new Date().toLocaleTimeString('zh-CN', { hour: '2-digit', minute: '2-digit' });

const welcomeMessage: ChatMessage = {
  id: 'welcome',
  role: 'assistant',
  content: '你好，我是校园助手。告诉我你想发布的任务，我会帮你整理成草稿，确认后再发布。',
  time: nowLabel(),
};

const sampleDraft: DraftPayload = {
  type: '跑腿配送',
  title: '菜鸟驿站取快递送到 3 号楼 502',
  reward: '8.00 元',
  pickup: '菜鸟驿站（东门）',
  delivery: '3 号楼 502',
  deadline: '明天 15:00',
};

const sampleConversation: ChatMessage[] = [
  {
    id: 'h1',
    role: 'assistant',
    content: '你好，我是校园助手。告诉我你想发布的任务，我会帮你整理成草稿，确认后再发布。',
    time: '昨天 20:12',
  },
  {
    id: 'h2',
    role: 'user',
    content: '明天下午三点帮我去菜鸟驿站取快递，送到 3 号楼 502，悬赏 8 块',
    time: '昨天 20:13',
  },
  {
    id: 'h3',
    role: 'assistant',
    content: '好的，我把关键信息整理成下面这单。确认无误后我就帮你发布。',
    time: '昨天 20:13',
    draft: sampleDraft,
  },
  {
    id: 'h4',
    role: 'user',
    content: '把送达地点改成 3 号楼 501',
    time: '昨天 20:14',
  },
  {
    id: 'h5',
    role: 'assistant',
    content: '已更新送达地点。其他信息保持不变，确认后即可发布。',
    time: '昨天 20:14',
    draft: { ...sampleDraft, delivery: '3 号楼 501' },
  },
];

const historyItems: HistoryItem[] = [
  { id: 'c1', title: '取快递到 3 号楼', preview: '已更新送达地点。其他信息保持不变…', time: '昨天' , messages: sampleConversation },
  {
    id: 'c2',
    title: '帮买奶茶', preview: '好的，我把关键信息整理成下面这单。', time: '10 月 29 日',
    messages: [
      { id: 'b1', role: 'user', content: '帮我买一杯少冰的珍珠奶茶，送到 2 号楼 301', time: '10 月 29 日 14:02' },
      { id: 'b2', role: 'assistant', content: '收到。请确认悬赏金额，我会为你生成草稿。', time: '10 月 29 日 14:02' },
    ],
  },
];

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

function buildReply(text: string): ChatMessage[] {
  const lower = text.trim();
  if (!lower) return [];
  const isErrand = /快递|取|送|买|排队|帮/.test(lower);
  if (!isErrand) {
    return [{
      id: Math.random().toString(36).slice(2),
      role: 'assistant',
      content: '我可以帮你发布校园任务。比如：明天下午三点帮我从菜鸟驿站取快递，送到 3 号楼 502，悬赏 8 块。',
      time: nowLabel(),
    }];
  }
  return [
    {
      id: Math.random().toString(36).slice(2),
      role: 'assistant',
      content: '收到，我先按你描述的信息整理了一版草稿。确认前不会发布。',
      time: nowLabel(),
    },
    {
      id: Math.random().toString(36).slice(2),
      role: 'assistant',
      content: '请核对以下内容，确认无误后点击「确认发布」。',
      time: nowLabel(),
      draft: sampleDraft,
    },
  ];
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

  const send = (raw?: string) => {
    const text = (raw ?? input).trim();
    if (!text || busy) return;
    setInput('');
    setMessages(prev => [...prev, { id: Math.random().toString(36).slice(2), role: 'user', content: text, time: nowLabel() }]);
    setBusy(true);
    timerRef.current = window.setTimeout(() => {
      setMessages(prev => [...prev, ...buildReply(text)]);
      setBusy(false);
    }, 700);
  };

  const onDraftAction = (action: string) => {
    setNotice(action === '确认发布' ? '预览版暂未接入发布接口'
      : action === '取消' ? '已取消本次任务草稿'
      : '预览版暂未接入修改接口');
  };

  const startNewChat = () => {
    setMessages([welcomeMessage]);
    setHistoryOpen(false);
    setInput('');
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
          <small><i className="agent-dot"/>对话式发单 · 预览版</small>
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
        {historyItems.map(item => <button type="button" key={item.id} className="agent-history-item" onClick={() => loadHistory(item)}>
          <span className="agent-history-icon"><Icon name="history" size={15}/></span>
          <span className="agent-history-copy"><strong>{item.title}</strong><small>{item.preview}</small></span>
          <time>{item.time}</time>
        </button>)}
        <p className="agent-history-hint">预览版示例数据，接入后端后展示真实会话。</p>
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
        <p className="agent-composer-hint">Enter 发送 · Shift + Enter 换行 · 预览版未连接后端</p>
      </div>

      {notice && <div className="agent-notice" role="status">{notice}<button type="button" aria-label="关闭提示" onClick={dismissNotice}><Icon name="close" size={13}/></button></div>}
    </section>}
  </>;
}
