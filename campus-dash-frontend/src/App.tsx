import { useEffect, useRef, useState } from 'react';
import { useHashRoute } from './router';
import { getUserId, loginAs, logout } from './api';
import { IDENTITIES } from './identities';
import TopBar from './components/TopBar';
import Icon from './components/Icon';
import Square from './pages/Square';
import Detail from './pages/Detail';
import Publish from './pages/Publish';
import Mine from './pages/Mine';
import Wallet from './pages/Wallet';
import Notifications from './pages/Notifications';
import Credit from './pages/Credit';
import AuthPage from './pages/AuthPage';
import { connectWs, disconnectWs } from './ws';

export default function App() {
  const route = useHashRoute();
  // userId 现在是字符串：UUID / 001 / 演示账号 "1001"
  const [userId, setUserId] = useState<string | null>(getUserId());
  const [loginBusy, setLoginBusy] = useState<string | null>(null);
  const loginLock = useRef(false);
  const [error, setError] = useState('');
  const [toast, setToast] = useState<{ text: string; error: boolean } | null>(null);
  useEffect(() => {
    const onNotice = (event: Event) => setToast((event as CustomEvent<{ text: string; error: boolean }>).detail);
    window.addEventListener('dash:notice', onNotice);
    return () => window.removeEventListener('dash:notice', onNotice);
  }, []);
  useEffect(() => { if (!toast) return; const timer = window.setTimeout(() => setToast(null), 4500); return () => window.clearTimeout(timer); }, [toast]);
  const switchIdentity = async (id: string) => {
    if (loginLock.current) return;
    loginLock.current = true;
    setLoginBusy(id); setError('');
    try {
      const displayName = IDENTITIES.find(i => String(i.userId) === id)?.name;
      const profile = await loginAs(id, displayName);
      setUserId(profile.userId);
    }
    catch (e) { setError(e instanceof Error ? e.message : '切换身份失败，请重试'); }
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

  if (userId === null) return <AuthPage onAuthed={profile => {
    setUserId(profile.userId);
    // 登录/注册成功后直接回到任务广场，而不是停在登录页
    window.location.hash = '#/square';
  }} />;

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
