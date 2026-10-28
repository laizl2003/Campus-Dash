import { useState } from 'react';

const chatIcon = <svg width="15" height="15" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.65" strokeLinecap="round" strokeLinejoin="round" aria-hidden="true"><path d="M21 11.5a8.5 8.5 0 0 1-8.5 8.5H7l-4 3v-5.6A8.5 8.5 0 1 1 21 11.5Z"/><path d="M8.5 10.5h.01M12 10.5h.01M15.5 10.5h.01"/></svg>;
const sendIcon = <svg width="15" height="15" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.8" strokeLinecap="round" strokeLinejoin="round" aria-hidden="true"><path d="m22 2-7 20-4-9-9-4 20-7Z"/><path d="M22 2 11 13"/></svg>;

/**
 * 广场左侧栏下半部分的聊天框 UI。
 * 只负责这一块的布局；消息收发在后续接后端，不改其他页面布局。
 */
export default function SquareChat() {
  const [text, setText] = useState('');
  const [hint, setHint] = useState('');

  const submit = (event: React.FormEvent) => {
    event.preventDefault();
    if (!text.trim()) return;
    setHint('聊天功能即将接入');
    window.setTimeout(() => setHint(''), 2200);
  };

  return <section aria-label="广场聊天" style={{
    display: 'flex', flex: '0 0 auto', flexDirection: 'column',
    height: 'clamp(220px, 28vh, 280px)', marginTop: 18, paddingTop: 12,
    borderTop: '1px solid var(--line)', minHeight: 0,
  }}>
    <div style={{ display: 'flex', alignItems: 'center', gap: 7, padding: '0 2px 7px' }}>
      <span style={{ display: 'inline-flex', alignItems: 'center', gap: 5, color: '#555', fontSize: 11, fontWeight: 700 }}>
        {chatIcon}广场聊天
      </span>
      <span style={{ display: 'inline-flex', alignItems: 'center', gap: 4, marginLeft: 'auto', color: '#9a9a9a', fontSize: 9 }}>
        <i style={{ width: 5, height: 5, borderRadius: '50%', background: '#2fa060' }}/>即将开放
      </span>
    </div>

    <div role="log" aria-live="polite" aria-label="聊天消息" style={{
      flex: '1 1 auto', minHeight: 0, overflowY: 'auto', overscrollBehavior: 'contain',
      display: 'flex', flexDirection: 'column', gap: 8, padding: '4px 2px 8px',
    }}>
      <div style={{ alignSelf: 'flex-start', maxWidth: '94%', padding: '7px 9px', borderRadius: '4px 10px 10px 10px', background: '#f7f7f4', border: '1px solid #ecebe4' }}>
        <div style={{ marginBottom: 2, color: '#9a9a9a', fontSize: 8 }}>CampusDash</div>
        <div style={{ color: '#666', fontSize: 10, lineHeight: 1.55 }}>聊天室正在搭建，很快就能和广场上的同学打招呼了。</div>
      </div>
      {hint && <div style={{ alignSelf: 'center', color: '#9a7b00', background: '#fff7cc', borderRadius: 6, padding: '4px 8px', fontSize: 9 }}>{hint}</div>}
    </div>

    <form onSubmit={submit} style={{ display: 'flex', gap: 5, alignItems: 'center', padding: '7px 2px 0', borderTop: '1px solid #f0f0f0' }}>
      <input value={text} onChange={event => setText(event.target.value)} maxLength={500}
             placeholder="说点什么…" aria-label="聊天输入框"
             style={{ flex: '1 1 auto', minWidth: 0, height: 30, padding: '0 9px', borderRadius: 7, border: '1px solid #ecebe4', background: '#fafafa', fontSize: 10, outline: 'none' }}/>
      <button type="submit" disabled={!text.trim()} aria-label="发送消息"
              style={{ display: 'grid', placeItems: 'center', flex: '0 0 auto', width: 30, height: 30, padding: 0, border: 0, borderRadius: 7, background: 'var(--primary)', color: '#3c3400', opacity: text.trim() ? 1 : .45, cursor: text.trim() ? 'pointer' : 'not-allowed' }}>
        {sendIcon}
      </button>
    </form>
  </section>;
}
