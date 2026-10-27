import { useEffect, useRef, useState } from 'react';
import { api, getUsername, listAccounts, type StoredAccount, yuan } from '../api';
import { subscribeWs } from '../ws';
import Icon from './Icon';

const NAV = [
  { path: '/square', label: '任务广场', icon: 'grid' },
  { path: '/mine', label: '我的任务', icon: 'receipt' },
  { path: '/wallet', label: '我的钱包', icon: 'wallet' },
  { path: '/credit', label: '信用中心', icon: 'shield' },
  { path: '/notifications', label: '消息中心', icon: 'bell' },
];
interface Props { route: string; userId: string; onSwitch: (id: string) => void; onLogout: () => void; switching: boolean }
export default function TopBar({ route, userId, onSwitch, onLogout, switching }: Props) {
  const [unread, setUnread] = useState<number | null>(null);
  const [balance, setBalance] = useState<number | null>(null);
  const [open, setOpen] = useState(false);
  const [accountMenu, setAccountMenu] = useState(false);
  const [switchMenu, setSwitchMenu] = useState(false);
  const [accounts, setAccounts] = useState<StoredAccount[]>(() => listAccounts());
  const [confirmDelete, setConfirmDelete] = useState(false);
  const [deleting, setDeleting] = useState(false);
  const [deleteError, setDeleteError] = useState('');
  const accountRef = useRef<HTMLDivElement>(null);
  const profileRef = useRef<HTMLDivElement>(null);
  const sidebarRef = useRef<HTMLElement>(null);
  const menuRef = useRef<HTMLButtonElement>(null);
  useEffect(() => {
    let active = true;
    let version = 0;
    setUnread(null); setBalance(null);
    const load = () => {
      const requestVersion = ++version;
      api.unread().then(r => { if (active && requestVersion === version) setUnread(r.count); }).catch(() => {});
      api.wallet().then(w => { if (active && requestVersion === version) setBalance(w.availableCents); }).catch(() => {});
    };
    load();
    const unsub = subscribeWs(load);
    window.addEventListener('dash:changed', load);
    const timer = window.setInterval(load, 30000);
    return () => { active = false; unsub(); clearInterval(timer); window.removeEventListener('dash:changed', load); };
  }, [userId, route]);
  useEffect(() => { setOpen(false); setAccountMenu(false); setSwitchMenu(false); }, [route]);
  useEffect(() => {
    const sync = () => setAccounts(listAccounts());
    window.addEventListener('dash:accounts', sync);
    window.addEventListener('storage', sync);
    return () => { window.removeEventListener('dash:accounts', sync); window.removeEventListener('storage', sync); };
  }, []);
  useEffect(() => {
    if (!accountMenu) return;
    const onDown = (event: MouseEvent) => {
      if (accountRef.current && !accountRef.current.contains(event.target as Node)) setAccountMenu(false);
    };
    document.addEventListener('mousedown', onDown);
    return () => document.removeEventListener('mousedown', onDown);
  }, [accountMenu]);
  useEffect(() => {
    if (!switchMenu) return;
    const onDown = (event: MouseEvent) => {
      if (profileRef.current && !profileRef.current.contains(event.target as Node)) setSwitchMenu(false);
    };
    document.addEventListener('mousedown', onDown);
    return () => document.removeEventListener('mousedown', onDown);
  }, [switchMenu]);
  const doDelete = async () => {
    if (deleting) return;
    setDeleting(true); setDeleteError('');
    try {
      await api.deleteAccount();
      setConfirmDelete(false);
      onLogout();
    } catch (e) {
      setDeleteError(e instanceof Error ? e.message : '注销失败，请稍后重试');
    } finally {
      setDeleting(false);
    }
  };
  useEffect(() => {
    if (!open) return;
    const previousOverflow = document.body.style.overflow;
    document.body.style.overflow = 'hidden';
    const focusable = () => Array.from(sidebarRef.current?.querySelectorAll<HTMLElement>('a[href],button:not(:disabled),select:not(:disabled)') ?? []);
    // 抽屉从 visibility:hidden 切换后，等浏览器应用可见样式再移动焦点。
    let focusFrame = window.requestAnimationFrame(() => {
      focusFrame = window.requestAnimationFrame(() => focusable()[0]?.focus());
    });
    const onKey = (e: KeyboardEvent) => {
      if (e.key === 'Escape') setOpen(false);
      if (e.key !== 'Tab') return;
      const elements = focusable(), first = elements[0], last = elements[elements.length - 1];
      if (e.shiftKey && document.activeElement === first) { e.preventDefault(); last?.focus(); }
      if (!e.shiftKey && document.activeElement === last) { e.preventDefault(); first?.focus(); }
    };
    const onResize = () => { if (window.innerWidth > 800) setOpen(false); };
    window.addEventListener('keydown', onKey); window.addEventListener('resize', onResize);
    return () => { window.cancelAnimationFrame(focusFrame); document.body.style.overflow = previousOverflow; window.removeEventListener('keydown', onKey); window.removeEventListener('resize', onResize); menuRef.current?.focus(); };
  }, [open]);
  // 展示真实用户名；演示账号没有昵称时退回 id
  const userName = getUsername() ?? userId;
  // 账号切换下拉不列管理员账号：管理台走独立入口，避免把 001 混进普通用户切换列表
  const switchableAccounts = accounts.filter(a => a.role !== 'Admin');
  const title = NAV.find(n => n.path === route)?.label ?? (route === '/publish' ? '发布任务' : '任务详情');
  return <>
    <a className="skip-link" href="#main-content" onClick={event => { event.preventDefault(); const main = document.getElementById('main-content'); main?.focus(); main?.scrollIntoView({ block: 'start' }); }}>跳到主要内容</a>
    {open && <div className="nav-scrim" onClick={() => setOpen(false)} aria-hidden="true"/>}
    <aside ref={sidebarRef} className={`sidebar ${open ? 'is-open' : ''}`} aria-label="主导航">
      <button className="icon-button sidebar-close" aria-label="关闭导航" onClick={() => setOpen(false)}><Icon name="close"/></button>
      <a className="brand" href="#/square" onClick={() => setOpen(false)}><span className="brand-mark"><Icon name="package" size={22}/></span><span>CampusDash<small>校园里的顺路好帮手</small></span></a>
      <div className="campus-selector"><span className="campus-icon"><Icon name="pin" size={17}/></span><span><strong>校园服务站</strong><small>当前校区 · 01</small></span><span className="service-dot"/></div>
      <a className={`publish-nav ${route === '/publish' ? 'active' : ''}`} href="#/publish" onClick={() => setOpen(false)}><Icon name="plus" size={18}/>发布一个任务<Icon name="arrow-right" size={16}/></a>
      <div className="nav-label">我的工作台</div>
      <nav>{NAV.map(n => <a key={n.path} href={`#${n.path}`} onClick={() => setOpen(false)} className={`nav-item ${route === n.path ? 'active' : ''}`} aria-current={route === n.path ? 'page' : undefined}><Icon name={n.icon} size={18}/>{n.label}{n.path === '/notifications' && unread !== null && unread > 0 && <span className="nav-count">{unread > 99 ? '99+' : unread}</span>}</a>)}</nav>
      <div className="sidebar-guide"><Icon name="spark" size={23}/><strong>顺路，帮个小忙。</strong><p>一份刚好的委托，<br/>连接校园里的你和我。</p><a href="#/square" onClick={() => setOpen(false)}>看看大家需要什么<Icon name="arrow-right" size={13}/></a></div>
      <div className="sidebar-bottom"><a href="#/wallet" className="sidebar-wallet" onClick={() => setOpen(false)}><span><Icon name="wallet" size={16}/>可用余额</span><strong>{balance === null ? '—' : yuan(balance)}</strong></a><div className="profile" ref={profileRef}><span className="avatar" title={userName}>{userName.slice(0, 1).toUpperCase()}</span><button type="button" className="profile-trigger" aria-haspopup="menu" aria-expanded={switchMenu} disabled={switching} onClick={() => setSwitchMenu(v => !v)}><span><strong>{userName}</strong><small>{switchableAccounts.length > 1 ? `${switchableAccounts.length} 个账号` : '当前账号'}</small></span><span className="profile-caret" aria-hidden="true">⌄</span></button><a className="icon-button" href="#/login" title="返回登录页" aria-label="返回登录页"><Icon name="arrow-left" size={16}/></a>{switchMenu && <div className="account-menu profile-menu" role="menu" aria-label="切换账号">
        <div className="account-menu-head"><small>切换账号</small></div>
        {switchableAccounts.length === 0
          ? <button type="button" role="menuitem" className="is-current" onClick={() => setSwitchMenu(false)}><span className="menu-avatar">{userName.slice(0, 1).toUpperCase()}</span><span className="menu-account"><strong>{userName}</strong><small>当前账号</small></span><Icon name="check" size={14}/></button>
          : switchableAccounts.map(a => <button key={a.userId} type="button" role="menuitem"
              className={a.userId === userId ? 'is-current' : ''}
              onClick={() => { setSwitchMenu(false); if (a.userId !== userId) onSwitch(a.userId); }}>
              <span className="menu-avatar">{a.username.slice(0, 1).toUpperCase()}</span>
              <span className="menu-account"><strong>{a.username}</strong><small>{a.userId === userId ? '当前账号' : '点击切换'}</small></span>
              {a.userId === userId && <Icon name="check" size={14}/>}
            </button>)}
        <a className="account-menu-link" href="#/login" onClick={() => setSwitchMenu(false)}><Icon name="arrow-left" size={14}/>登录其他账号</a>
      </div>}</div></div>
    </aside>
    <header className="topbar"><div className="topbar-left"><button ref={menuRef} className="icon-button mobile-menu" aria-expanded={open} aria-label="打开导航" onClick={() => setOpen(true)}><Icon name="menu"/></button><span className="breadcrumb">工作台 <span>/</span> <strong>{title}</strong></span></div><div className="topbar-right"><span className="topbar-campus"><Icon name="pin" size={14}/>校园服务站</span><a className="icon-button notification-button" href="#/notifications" aria-label={`消息中心${unread ? `，${unread}条未读` : ''}`}><Icon name="bell" size={18}/>{!!unread && <i/>}</a><div className="account-menu-wrap" ref={accountRef}>
        <button type="button" className="topbar-avatar" title={userName} aria-label={userName}
                aria-haspopup="menu" aria-expanded={accountMenu}
                onClick={() => setAccountMenu(v => !v)}>{userName.slice(0, 1).toUpperCase()}</button>
        {accountMenu && <div className="account-menu" role="menu" aria-label="账号菜单">
          <div className="account-menu-head"><strong>{userName}</strong></div>
          <button type="button" role="menuitem" onClick={() => { setAccountMenu(false); onLogout(); }}>
            <Icon name="logout" size={15}/>退出登录
          </button>
          <button type="button" role="menuitem" className="danger"
                  onClick={() => { setAccountMenu(false); setDeleteError(''); setConfirmDelete(true); }}>
            <Icon name="close" size={15}/>注销账户
          </button>
        </div>}
      </div></div></header>
      {confirmDelete && <div className="modal-scrim" role="dialog" aria-modal="true" aria-label="注销账户">
        <div className="confirm-card">
          <h3>注销账户</h3>
          <p>注销后账号将从 <code>user</code> 表删除，钱包余额将全部转入平台佣金账户，操作不可恢复。</p>
          <p className="muted">如果还有进行中的任务，需要先完成或取消。</p>
          {deleteError && <p className="form-error" role="alert">{deleteError}</p>}
          <div className="confirm-actions">
            <button type="button" className="btn" disabled={deleting} onClick={() => setConfirmDelete(false)}>取消</button>
            <button type="button" className="btn btn-danger" disabled={deleting} onClick={() => void doDelete()}>
              {deleting ? '注销中…' : '确认注销'}
            </button>
          </div>
        </div>
      </div>}
  </>;
}
