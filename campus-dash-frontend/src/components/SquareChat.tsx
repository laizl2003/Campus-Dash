import { useEffect, useRef, useState } from 'react';
import { api, getUserId, TYPE_TEXT, yuan, type ChatItem, type OnlineUser } from '../api';
import { sendWs, subscribeWs } from '../ws';

const chatIcon = <svg width="15" height="15" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.65" strokeLinecap="round" strokeLinejoin="round" aria-hidden="true"><path d="M21 11.5a8.5 8.5 0 0 1-8.5 8.5H7l-4 3v-5.6A8.5 8.5 0 1 1 21 11.5Z"/><path d="M8.5 10.5h.01M12 10.5h.01M15.5 10.5h.01"/></svg>;
const sendIcon = <svg width="15" height="15" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.8" strokeLinecap="round" strokeLinejoin="round" aria-hidden="true"><path d="m22 2-7 20-4-9-9-4 20-7Z"/><path d="M22 2 11 13"/></svg>;

function mergeMessages(a: ChatItem[], b: ChatItem[]) {
  const map = new Map<string, ChatItem>();
  [...a, ...b].forEach(item => map.set(item.id, item));
  return [...map.values()].sort((x, y) => {
    try {
      const left = BigInt(x.id);
      const right = BigInt(y.id);
      return left < right ? -1 : left > right ? 1 : 0;
    } catch {
      return x.id.localeCompare(y.id);
    }
  });
}

function timeLabel(value: string) {
  const date = new Date(value);
  return Number.isNaN(date.getTime()) ? '' : date.toLocaleTimeString('zh-CN', { hour: '2-digit', minute: '2-digit' });
}

function cardTimeLabel(value: string) {
  const date = new Date(value);
  return Number.isNaN(date.getTime()) ? '' : date.toLocaleString('zh-CN', {
    month: '2-digit', day: '2-digit', hour: '2-digit', minute: '2-digit',
  });
}

interface ErrandPayload {
  type?: string;
  title?: string;
  rewardCents?: number;
  publisherId?: string;
  publisherName?: string;
}

function parseErrand(content: string): ErrandPayload {
  try { return JSON.parse(content) as ErrandPayload; } catch { return {}; }
}

/**
 * 广场左侧栏下半部分的聊天框。
 * 只负责这一块；消息通过 /api/chat/messages + 现有 /ws 收发。
 */
export default function SquareChat({ compactBottom = true }: { compactBottom?: boolean }) {
  const [messages, setMessages] = useState<ChatItem[]>([]);
  const [text, setText] = useState('');
  const [error, setError] = useState('');
  const [loading, setLoading] = useState(true);
  const [loadingOlder, setLoadingOlder] = useState(false);
  const [hasMore, setHasMore] = useState(true);
  const [atBottom, setAtBottom] = useState(true);
  const [onlineCount, setOnlineCount] = useState<number | null>(null);
  const [onlineOpen, setOnlineOpen] = useState(false);
  const [onlineUsers, setOnlineUsers] = useState<OnlineUser[]>([]);
  const [onlineLoading, setOnlineLoading] = useState(false);
  const [onlineError, setOnlineError] = useState('');
  const listRef = useRef<HTMLDivElement>(null);
  const prependRef = useRef(false);
  const olderLoadingRef = useRef(false);
  const atBottomRef = useRef(true);
  const selfId = getUserId();

  useEffect(() => {
    let active = true;
    api.chatHistory({ limit: 50 })
      .then(result => {
        if (active) {
          setMessages(prev => mergeMessages(prev, result.items));
          setHasMore(result.items.length >= 50);
        }
      })
      .catch(() => { if (active) setError('聊天记录加载失败'); })
      .finally(() => { if (active) setLoading(false); });

    const unsubscribe = subscribeWs(event => {
      if (event.type === 'chat.message') {
        const message = event.payload as unknown as ChatItem;
        if (!message?.id) return;
        setMessages(prev => mergeMessages(prev, [message]));
        setError('');
      }
      if (event.type === 'chat.error') {
        setError(typeof event.payload.message === 'string' ? event.payload.message : '消息发送失败');
      }
      if (event.type === 'errand.status') {
        const errandId = String(event.payload.errandId ?? '');
        const status = String(event.payload.status ?? '');
        if (errandId) {
          setMessages(prev => prev.map(message =>
            message.errandId === errandId ? { ...message, errandStatus: status } : message));
        }
      }
    });
    return () => { active = false; unsubscribe(); };
  }, []);

  useEffect(() => {
    let active = true;
    const loadOnline = () => {
      api.chatOnline()
        .then(result => { if (active) setOnlineCount(result.count); })
        .catch(() => {});
    };
    loadOnline();
    const timer = window.setInterval(loadOnline, 30000);
    return () => { active = false; clearInterval(timer); };
  }, []);

  useEffect(() => {
    const list = listRef.current;
    if (!list) return;
    if (prependRef.current) {
      prependRef.current = false;
      return;
    }
    if (atBottomRef.current) {
      list.scrollTop = list.scrollHeight;
    }
  }, [messages.length]);

  const jumpLatest = () => {
    const list = listRef.current;
    if (!list) return;
    atBottomRef.current = true;
    setAtBottom(true);
    list.scrollTop = list.scrollHeight;
  };

  const onListScroll = () => {
    const list = listRef.current;
    if (!list) return;
    const nearBottom = list.scrollHeight - list.scrollTop - list.clientHeight < 40;
    atBottomRef.current = nearBottom;
    setAtBottom(nearBottom);
    if (list.scrollTop <= 20) void loadOlder();
  };

  const loadOlder = async () => {
    const list = listRef.current;
    if (!list || olderLoadingRef.current || loadingOlder || !hasMore || messages.length === 0) return;
    olderLoadingRef.current = true;
    setLoadingOlder(true);
    const beforeId = messages[0].id;
    try {
      const result = await api.chatHistory({ beforeId, limit: 50 });
      if (result.items.length === 0) {
        setHasMore(false);
        return;
      }
      const previousHeight = list.scrollHeight;
      prependRef.current = true;
      setMessages(prev => mergeMessages(result.items, prev));
      setHasMore(result.items.length >= 50);
      window.requestAnimationFrame(() => {
        window.requestAnimationFrame(() => {
          list.scrollTop = list.scrollHeight - previousHeight;
        });
      });
    } catch (reason) {
      setError(reason instanceof Error ? reason.message : '更早消息加载失败');
    } finally {
      olderLoadingRef.current = false;
      setLoadingOlder(false);
    }
  };

  const openOnline = async () => {
    setOnlineOpen(true);
    setOnlineLoading(true);
    setOnlineError('');
    try {
      setOnlineUsers(await api.chatOnlineUsers());
    } catch (reason) {
      setOnlineError(reason instanceof Error ? reason.message : '在线用户加载失败');
    } finally {
      setOnlineLoading(false);
    }
  };

  const send = async () => {
    const content = text.trim();
    if (!content) return;
    const clientMsgId = crypto.randomUUID();
    setError('');

    if (sendWs({ t: 'chat.send', clientMsgId, content })) {
      setText('');
      return;
    }

    // WS 暂不可用时走 HTTP 兜底；服务端仍会广播给其他人
    try {
      const sent = await api.sendChatMessage(clientMsgId, content);
      setMessages(prev => mergeMessages(prev, [sent]));
      setText('');
    } catch (reason) {
      setError(reason instanceof Error ? reason.message : '消息发送失败，请稍后重试');
    }
  };

  return <section aria-label="广场聊天" style={{
    display: 'flex', flex: '1 1 auto', flexDirection: 'column', gap: 6,
    minHeight: 190, marginTop: 14, marginBottom: compactBottom ? -15 : 0, minWidth: 0,
  }}>
    <div style={{
      position: 'relative',
      display: 'flex', flex: '1 1 auto', flexDirection: 'column', minHeight: 0,
      border: '1px solid #ddd6c8', borderRadius: 10, background: '#fbfaf7',
      overflow: 'hidden', boxShadow: '0 1px 2px #00000008',
    }}>
      <div style={{
        display: 'flex', alignItems: 'center', gap: 7, padding: '8px 9px 6px',
        borderBottom: '1px solid #f0ece2',
      }}>
        <span style={{ display: 'inline-flex', alignItems: 'center', gap: 5, color: '#555', fontSize: 11, fontWeight: 700 }}>
          {chatIcon}广场聊天
        </span>
        <button type="button" onClick={() => void openOnline()} aria-label="查看在线用户"
                style={{ display: 'inline-flex', alignItems: 'center', gap: 4, marginLeft: 'auto',
                         padding: 0, border: 0, background: 'transparent', color: '#9a9a9a',
                         fontSize: 9, cursor: 'pointer' }}>
          <i style={{ width: 5, height: 5, borderRadius: '50%', background: '#2fa060' }}/>
          {onlineCount === null ? '在线 —' : `在线 ${onlineCount}`}
        </button>
      </div>

      <div ref={listRef} role="log" aria-live="polite" aria-label="聊天消息"
           onScroll={onListScroll}
           style={{
             flex: '1 1 auto', minHeight: 0, overflowY: 'auto', overscrollBehavior: 'contain',
             display: 'flex', flexDirection: 'column', gap: 8, padding: '8px 9px',
           }}>
        {loadingOlder && <div style={{ alignSelf: 'center', color: '#aaa', fontSize: 9 }}>加载更早消息…</div>}
        {!hasMore && messages.length > 0 && <div style={{ alignSelf: 'center', color: '#bbb', fontSize: 8 }}>没有更早消息了</div>}
        {loading && <div style={{ alignSelf: 'center', color: '#aaa', fontSize: 9 }}>正在加载聊天…</div>}
        {!loading && messages.length === 0 && <div style={{ alignSelf: 'flex-start', maxWidth: '94%', padding: '7px 9px', borderRadius: '4px 10px 10px 10px', background: '#fff', border: '1px solid #ece7db' }}>
          <div style={{ marginBottom: 2, color: '#9a9a9a', fontSize: 8 }}>CampusDash</div>
          <div style={{ color: '#666', fontSize: 10, lineHeight: 1.55 }}>还没有消息，来和大家打个招呼吧。</div>
        </div>}
        {messages.map(message => {
          if (message.msgType === 'VOUCHER') {
            let voucher: { description?: string; stock?: number } = {};
            try { voucher = JSON.parse(message.content); } catch { /* 忽略非法内容 */ }
            return <div key={message.id} style={{ alignSelf: 'stretch' }}>
              <div style={{ marginBottom: 2, color: '#aaa', fontSize: 8, textAlign: 'center' }}>
                {cardTimeLabel(message.createdAt)}
              </div>
              <div style={{
                padding: '7px 8px', boxSizing: 'border-box',
                borderRadius: 8, border: '1px solid #b7d8b9', background: '#e8f5e9',
                color: '#2f6b4a',
              }}>
                <div style={{ fontSize: 9, fontWeight: 700 }}>新的优惠券</div>
                <div style={{ marginTop: 2, fontSize: 11, fontWeight: 600 }}>{voucher.description ?? '满减优惠券'}</div>
                <div style={{ marginTop: 1, fontSize: 9, opacity: .8 }}>库存 {voucher.stock ?? 0}</div>
              </div>
            </div>;
          }
          if (message.msgType === 'ERRAND') {
            const errand = parseErrand(message.content);
            const clickable = message.errandStatus === 'PUBLISHED';
            const card = <div style={{
              width: '100%', padding: '7px 8px', boxSizing: 'border-box',
              borderRadius: 8, border: `1px solid ${clickable ? '#ead48d' : '#e2e2e2'}`,
              background: clickable ? '#fff8e3' : '#f1f1f1',
              color: clickable ? '#5f4b16' : '#999999',
            }}>
              <div style={{ fontSize: 8, opacity: .75 }}>{TYPE_TEXT[errand.type ?? ''] ?? errand.type ?? '任务'}</div>
              <div style={{ marginTop: 1, fontSize: 10, fontWeight: 600, lineHeight: 1.35 }}>{errand.title ?? '校园任务'}</div>
              <div style={{ marginTop: 2, fontSize: 14, fontWeight: 800 }}>{yuan(errand.rewardCents ?? 0)}</div>
              <div style={{ marginTop: 1, fontSize: 8, opacity: .7 }}>发单人 {errand.publisherName ?? errand.publisherId ?? message.senderId}</div>
            </div>;
            return <div key={message.id} style={{ alignSelf: 'stretch', cursor: clickable ? 'pointer' : 'not-allowed' }}>
              <div style={{ marginBottom: 2, color: '#aaa', fontSize: 8, textAlign: 'center' }}>
                {cardTimeLabel(message.createdAt)}
              </div>
              {clickable
                ? <a href="#/square" style={{ display: 'block', textDecoration: 'none' }}>{card}</a>
                : card}
            </div>;
          }
          if (message.msgType === 'SYSTEM' || message.senderRole === 'system') {
            return <div key={message.id} style={{
              alignSelf: 'center', maxWidth: '92%', padding: '3px 9px',
              borderRadius: 999, background: '#f0f0f0', color: '#999999',
              fontSize: 9, textAlign: 'center', lineHeight: 1.6,
            }}>{message.content}</div>;
          }
          const mine = message.senderId === selfId;
          return <div key={message.id} style={{
            display: 'flex', gap: 6, alignItems: 'flex-start',
            flexDirection: mine ? 'row-reverse' : 'row',
          }}>
            <span title={message.senderName} style={{
              display: 'grid', placeItems: 'center', flex: '0 0 auto', width: 24, height: 24,
              borderRadius: '50%', fontSize: 10, fontWeight: 700,
              background: message.senderRole === 'Admin' ? '#2f6b4a' : 'var(--primary)',
              color: message.senderRole === 'Admin' ? '#fff' : '#3c3400',
            }}>{message.senderName.slice(0, 1).toUpperCase()}</span>
            <div style={{ maxWidth: '82%', minWidth: 0, textAlign: mine ? 'right' : 'left' }}>
              <div style={{ color: '#9a9a9a', fontSize: 8, marginBottom: 2 }}>
                {message.senderName}{message.senderRole === 'Admin' ? ' · 管理员' : ''} · {timeLabel(message.createdAt)}
              </div>
              <div style={{
                display: 'inline-block', padding: '6px 8px', borderRadius: mine ? '10px 4px 10px 10px' : '4px 10px 10px 10px',
                background: mine ? '#fff2bf' : '#fff', border: '1px solid #ece7db',
                color: '#555', fontSize: 10, lineHeight: 1.55, textAlign: 'left',
                whiteSpace: 'pre-wrap', wordBreak: 'break-word',
              }}>{message.content}</div>
            </div>
          </div>;
        })}
      </div>

      {!atBottom && <button type="button" onClick={jumpLatest} aria-label="回到最新消息"
              style={{
                position: 'absolute', right: 8, bottom: 50, zIndex: 15,
                display: 'grid', placeItems: 'center', width: 28, height: 28,
                border: '1px solid #e4d79c', borderRadius: '50%', background: 'var(--primary)',
                color: '#3c3400', fontSize: 14, cursor: 'pointer', boxShadow: '0 2px 8px #00000018',
              }}>↓</button>}

      {error && <div style={{ padding: '0 9px 5px', color: '#b23b32', fontSize: 9 }} role="alert">{error}</div>}

      <form onSubmit={event => { event.preventDefault(); void send(); }}
            style={{ display: 'flex', alignItems: 'center', gap: 5, padding: '6px 8px 8px', borderTop: '1px solid #f0ece2' }}>
        <input value={text} onChange={event => setText(event.target.value)} maxLength={500}
               placeholder="说点什么…" aria-label="聊天输入框"
               style={{ flex: '1 1 auto', minWidth: 0, boxSizing: 'border-box', height: 30, padding: '0 9px', borderRadius: 7, border: '1px solid #e4dfd2', background: '#fff', color: '#333', caretColor: '#333', fontSize: 10, outline: 'none' }}
               onFocus={event => { event.currentTarget.style.borderColor = '#d6b94a'; event.currentTarget.style.boxShadow = '0 0 0 2px #ffd10033'; }}
               onBlur={event => { event.currentTarget.style.borderColor = '#e4dfd2'; event.currentTarget.style.boxShadow = 'none'; }}/>
        <button type="submit" disabled={!text.trim()} aria-label="发送消息"
                style={{
                  display: 'grid', placeItems: 'center', flex: '0 0 auto',
                  width: 30, height: 30, padding: 0, border: 0, borderRadius: 7,
                  background: text.trim() ? 'var(--primary)' : '#f1e6ad',
                  color: text.trim() ? '#3c3400' : '#8b7d45',
                  opacity: 1, cursor: text.trim() ? 'pointer' : 'not-allowed',
                }}>
          {sendIcon}
        </button>
      </form>

      {onlineOpen && <div style={{
        position: 'absolute', inset: 0, zIndex: 20, display: 'flex', flexDirection: 'column',
        padding: '8px 9px', background: '#fbfaf7',
      }}>
        <div style={{ display: 'flex', alignItems: 'center', marginBottom: 6 }}>
          <strong style={{ color: '#555', fontSize: 11 }}>在线用户</strong>
          <button type="button" aria-label="关闭在线用户" onClick={() => setOnlineOpen(false)}
                  style={{ marginLeft: 'auto', border: 0, background: 'transparent', color: '#9a9a9a', fontSize: 15, cursor: 'pointer' }}>×</button>
        </div>
        {onlineLoading && <div style={{ color: '#aaa', fontSize: 9 }}>正在加载…</div>}
        {onlineError && <div style={{ color: '#b23b32', fontSize: 9 }}>{onlineError}</div>}
        {!onlineLoading && !onlineError && <div style={{ flex: '1 1 auto', minHeight: 0, overflowY: 'auto' }}>
          {onlineUsers.map(user => {
            const current = user.userId === selfId;
            return <div key={user.userId} style={{
              display: 'flex', alignItems: 'center', gap: 6, padding: '5px 6px',
              borderRadius: 6, background: current ? '#fff7cc' : 'transparent',
            }}>
              <span style={{
                display: 'grid', placeItems: 'center', width: 22, height: 22, flex: '0 0 auto',
                borderRadius: '50%', fontSize: 9, fontWeight: 700,
                background: user.role === 'Admin' ? '#2f6b4a' : 'var(--primary)',
                color: user.role === 'Admin' ? '#fff' : '#3c3400',
              }}>{user.username.slice(0, 1).toUpperCase()}</span>
              <span style={{ minWidth: 0 }}>
                <strong style={{ display: 'block', color: '#555', fontSize: 10 }}>{user.username}</strong>
                <small style={{ color: '#9a9a9a', fontSize: 8 }}>
                  {user.role === 'Admin' ? '管理员' : '普通用户'}{current ? ' · 当前用户' : ''}
                </small>
              </span>
            </div>;
          })}
        </div>}
      </div>}
    </div>
  </section>;
}
