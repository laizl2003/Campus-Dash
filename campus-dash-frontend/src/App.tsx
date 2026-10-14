import { useEffect, useRef, useState } from 'react';
import { useHashRoute } from './router';
import { getUserId, login, logout } from './api';
import TopBar from './components/TopBar';
import Icon from './components/Icon';
import CampusArt from './components/CampusArt';
import { IDENTITIES } from './identities';
import Square from './pages/Square';
import Detail from './pages/Detail';
import Publish from './pages/Publish';
import Mine from './pages/Mine';
import Wallet from './pages/Wallet';
import Notifications from './pages/Notifications';
import Credit from './pages/Credit';
import { connectWs, disconnectWs } from './ws';

export default function App() {
  const route = useHashRoute();
  const [userId, setUserId] = useState<number | null>(getUserId());
  const [loginBusy, setLoginBusy] = useState<number | null>(null);
  const loginLock = useRef(false);
  const [error, setError] = useState('');
  const [toast, setToast] = useState<{ text: string; error: boolean } | null>(null);
  useEffect(() => {
    const onNotice = (event: Event) => setToast((event as CustomEvent<{ text: string; error: boolean }>).detail);
    window.addEventListener('dash:notice', onNotice);
    return () => window.removeEventListener('dash:notice', onNotice);
  }, []);
  useEffect(() => { if (!toast) return; const timer = window.setTimeout(() => setToast(null), 4500); return () => window.clearTimeout(timer); }, [toast]);
  const switchIdentity = async (id: number) => {
    if (loginLock.current) return;
    loginLock.current = true;
    setLoginBusy(id); setError('');
    try { await login(id); setUserId(id); }
    catch (e) { setError(e instanceof Error ? e.message : '登录失败，请重试'); }
    finally { setLoginBusy(null); loginLock.current = false; }
  };
  useEffect(() => {
    const onLogout = () => { disconnectWs(); setUserId(null); };
    window.addEventListener('dash:logout', onLogout);
    return () => window.removeEventListener('dash:logout', onLogout);
  }, []);
  useEffect(() => {
    if (userId !== null) { connectWs(); return disconnectWs; }
  }, [userId]);
  useEffect(() => { window.scrollTo({ top: 0 }); }, [route]);

  if (userId === null) return <div className="login-page">
    <section className="login-story">
      <a className="brand" href="#/square"><span className="brand-mark"><Icon name="package" size={24}/></span><span>CampusDash<small>校园里的顺路好帮手</small></span></a>
      <div className="login-story-body"><span className="eyebrow">A LITTLE HELP, A BETTER DAY</span><h1>校园里的小事，<br/><em>交给顺路的同学。</em></h1><p>取件、带饭、买东西。把一件小事托付出去，<br/>让你的时间留给更重要的生活。</p><CampusArt/><div className="login-benefits"><span><Icon name="shield" size={16}/>悬赏资金托管</span><span><Icon name="clock" size={16}/>任务进度可追踪</span><span><Icon name="users" size={16}/>校园互助</span></div></div>
      <div className="login-copyright">CAMPUSDASH · 让每一次顺路，都有意义</div>
    </section>
    <section className="login-side"><div className="login-card"><span className="soft-label">体验工作台</span><h2>从一个身份开始</h2><p className="login-description">选择角色，体验一次完整的校园委托。</p>
      <div className="identity-list">{IDENTITIES.map(it => <button key={it.userId} className={`identity-btn${it.userId === 2001 ? ' identity-btn-personal' : ''}`} disabled={loginBusy !== null} onClick={() => void switchIdentity(it.userId)}><span className="identity-icon" aria-hidden="true">{it.userId === 2001 ? it.name : <Icon name={it.icon}/>}</span><span className="identity-copy"><strong>{it.name}<small>{it.userId === 2001 ? `${it.role} · ` : ''}#{it.userId}</small></strong><span>{loginBusy === it.userId ? '正在进入工作台…' : it.desc}</span></span><Icon name="arrow-right" size={20}/></button>)}</div>
      {error && <div className="banner warn" role="alert">{error}</div>}
      <div className="login-note"><Icon name="info" size={16}/><p>当前为项目演示环境。角色切换用于体验发布、接单与争议处理，数据由已连接的后端服务提供。</p></div>
    </div></section>
  </div>;

  let page;
  const path = route.split('?')[0];
  if (path.startsWith('/detail/')) page = <Detail key={path.split('/')[2]} errandId={path.split('/')[2]}/>;
  else if (path === '/publish') page = <Publish key={route}/>;
  else if (path === '/mine') page = <Mine/>;
  else if (path === '/wallet') page = <Wallet/>;
  else if (path === '/notifications') page = <Notifications/>;
  else if (path === '/credit') page = <Credit/>;
  else page = <Square/>;

  return <div className="app">
    <TopBar route={path} userId={userId} onSwitch={switchIdentity} onLogout={logout} switching={loginBusy !== null}/>
    <main className="main" id="main-content" tabIndex={-1}><div className="content" key={userId}>
      {error && <div className="banner warn" role="alert">{error}<button className="icon-button" aria-label="关闭提示" onClick={() => setError('')}><Icon name="close" size={16}/></button></div>}
      {page}
      <footer className="workspace-footer"><span>CampusDash</span><span>每一份托付，都认真送达。</span><span>校园互助工作台</span></footer>
    </div></main>
    {toast && <div className="toast" role="status"><Icon name={toast.error ? 'info' : 'check'} size={17}/>{toast.text}<button aria-label="关闭操作提示" onClick={() => setToast(null)}><Icon name="close" size={15}/></button></div>}
  </div>;
}
