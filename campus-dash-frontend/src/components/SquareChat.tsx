import { useState } from 'react';
import Icon from './Icon';

/**
 * 广场聊天室 UI 壳。
 * 目前只完成布局与折叠交互；消息收发、历史加载在后续接入。
 */
export default function SquareChat() {
  const [collapsed, setCollapsed] = useState(false);

  return <section className={`sidebar-chat ${collapsed ? 'is-collapsed' : ''}`}
                  style={{ display: 'flex', flex: '0 0 auto', flexDirection: 'column',
                           height: collapsed ? 'auto' : 'clamp(220px, 30vh, 320px)' }}
                  aria-label="广场聊天">
    <div className="chat-head">
      <span className="chat-title"><Icon name="chat" size={15}/>广场聊天</span>
      <span className="chat-status"><i/>即将开放</span>
      <button type="button" className="chat-collapse" aria-label={collapsed ? '展开聊天' : '收起聊天'}
              aria-expanded={!collapsed} onClick={() => setCollapsed(v => !v)}>
        <Icon name={collapsed ? 'chevron-up' : 'chevron-down'} size={14}/>
      </button>
    </div>
    {!collapsed && <>
      <div className="chat-list" style={{ flex: '1 1 auto', minHeight: 0, overflowY: 'auto' }}
           role="log" aria-live="polite" aria-label="聊天消息">
        <div className="chat-empty" style={{ height: '100%', display: 'flex', flexDirection: 'column',
                                             alignItems: 'center', justifyContent: 'center', textAlign: 'center' }}>
          <span className="chat-empty-icon"><Icon name="chat" size={18}/></span>
          <strong>聊天室正在搭建</strong>
          <p>很快就能和广场上的同学打招呼了。</p>
        </div>
      </div>
      <form className="chat-composer" style={{ display: 'flex', gap: 5, alignItems: 'center' }}
            onSubmit={event => event.preventDefault()}>
        <input disabled placeholder="聊天功能即将开放" aria-label="聊天输入框"
               style={{ flex: '1 1 auto', minWidth: 0, height: 30, padding: '0 9px', fontSize: 10 }}/>
        <button type="submit" disabled aria-label="发送消息"
                style={{ flex: '0 0 auto', width: 30, height: 30, padding: 0 }}><Icon name="send" size={15}/></button>
      </form>
    </>}
  </section>;
}
