import { useEffect, useRef, useState } from 'react';
import { api, getUserId, type ChatItem } from '../api';
import { isWsAvailable, sendWs, subscribeWs } from '../ws';

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

/**
 * 广场左侧栏下半部分的聊天框。
 * 只负责这一块；消息通过 /api/chat/messages + 现有 /ws 收发。
 */
export default function SquareChat({ compactBottom = true }: { compactBottom?: boolean }) {
  const [messages, setMessages] = useState<ChatItem[]>([]);
  const [text, setText] = useState('');
  const [error, setError] = useState('');
  const [loading, setLoading] = useState(true);
  const [online, setOnline] = useState(() => isWsAvailable());
  const listRef = useRef<HTMLDivElement>(null);
  const selfId = getUserId();

  useEffect(() => {
    let active = true;
    api.chatHistory({ limit: 50 })
      .then(result => {
        if (active) setMessages(prev => mergeMessages(prev, result.items));
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
    });
    const timer = window.setInterval(() => setOnline(isWsAvailable()), 5000);
    return () => { active = false; unsubscribe(); clearInterval(timer); };
  }, []);

  useEffect(() => {
    const list = listRef.current;
    if (list) list.scrollTop = list.scrollHeight;
  }, [messages.length]);

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
      setOnline(false);
    } catch (reason) {
      setError(reason instanceof Error ? reason.message : '消息发送失败，请稍后重试');
    }
  };

  return <section aria-label="广场聊天" style={{
    display: 'flex', flex: '1 1 auto', flexDirection: 'column', gap: 6,
    minHeight: 190, marginTop: 14, marginBottom: compactBottom ? -24 : 0, minWidth: 0,
  }}>
    <div style={{
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
        <span style={{ display: 'inline-flex', alignItems: 'center', gap: 4, marginLeft: 'auto', color: '#9a9a9a', fontSize: 9 }}>
          <i style={{ width: 5, height: 5, borderRadius: '50%', background: online ? '#2fa060' : '#c9c3b8' }}/>{online ? '在线' : '连接中'}
        </span>
      </div>

      <div ref={listRef} role="log" aria-live="polite" aria-label="聊天消息" style={{
        flex: '1 1 auto', minHeight: 0, overflowY: 'auto', overscrollBehavior: 'contain',
        display: 'flex', flexDirection: 'column', gap: 8, padding: '8px 9px',
      }}>
        {loading && <div style={{ alignSelf: 'center', color: '#aaa', fontSize: 9 }}>正在加载聊天…</div>}
        {!loading && messages.length === 0 && <div style={{ alignSelf: 'flex-start', maxWidth: '94%', padding: '7px 9px', borderRadius: '4px 10px 10px 10px', background: '#fff', border: '1px solid #ece7db' }}>
          <div style={{ marginBottom: 2, color: '#9a9a9a', fontSize: 8 }}>CampusDash</div>
          <div style={{ color: '#666', fontSize: 10, lineHeight: 1.55 }}>还没有消息，来和大家打个招呼吧。</div>
        </div>}
        {messages.map(message => {
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

      {error && <div style={{ padding: '0 9px 5px', color: '#b23b32', fontSize: 9 }} role="alert">{error}</div>}

      <form onSubmit={event => { event.preventDefault(); void send(); }} style={{ display: 'flex', alignItems: 'center', padding: '6px 8px 8px', borderTop: '1px solid #f0ece2' }}>
        <input value={text} onChange={event => setText(event.target.value)} maxLength={500}
               placeholder="说点什么…" aria-label="聊天输入框"
               style={{ flex: '1 1 auto', minWidth: 0, boxSizing: 'border-box', height: 29, padding: '0 9px', borderRadius: 7, border: '1px solid #e4dfd2', background: '#fff', fontSize: 10, outline: 'none' }}/>
      </form>
    </div>

    <button type="button" disabled={!text.trim()} onClick={() => void send()} aria-label="发送消息"
            style={{
              display: 'flex', alignItems: 'center', justifyContent: 'center', gap: 6,
              width: '100%', height: 32, padding: 0, border: 0, borderRadius: 8,
              background: 'var(--primary)', color: '#3c3400', fontSize: 11, fontWeight: 700,
              opacity: text.trim() ? 1 : .45, cursor: text.trim() ? 'pointer' : 'not-allowed',
            }}>
      {sendIcon}发送
    </button>
  </section>;
}
