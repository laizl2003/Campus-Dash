import { useEffect, useRef, useState } from 'react';
import { useHashRoute } from './router';
import { api, getRole, getUserId, loginAs, logout } from './api';
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
import AdminApp from './admin/AdminApp';
import { connectWs, disconnectWs } from './ws';

/** 非管理员误入管理路由：静默替换 hash，不渲染任何管理内容 */
function RedirectToSquare() {
  useEffect(() => { window.location.hash = '#/square'; }, []);
  return null;
}

export default function App() {
  const route = useHashRoute();
  // userId 现在是字符串：UUID / 001 / 演示账号 "1001"
  const [userId, setUserId] = useState<string | null>(getUserId());
  // 角色由后端登录响应给出（Admin / user），只影响落地页与路由守卫；
  // 真正的权限校验在后端 AdminInterceptor。
  const [role, setRole] = useState<string>(getRole());
  // 刷新页面时先向服务端确认角色：本地 dash_role 可能缺失（旧会话）或被手动改过
  const [roleReady, setRoleReady] = useState<boolean>(() => getUserId() === null);
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
      setRole(profile.role);
    }
    catch (e) { setError(e instanceof Error ? e.message : '切换身份失败，请重试'); }
    finally { setLoginBusy(null); loginLock.current = false; }
  };
  useEffect(() => {
    if (getUserId() === null) { setRoleReady(true); return; }
    let active = true;
    api.me()
      .then(profile => {
        if (!active) return;
        setRole(profile.role);
        localStorage.setItem('dash_role', profile.role);
      })
      .catch(() => { /* 身份查询失败时沿用本地角色，权限仍由后端兜底 */ })
      .finally(() => { if (active) setRoleReady(true); });
    return () => { active = false; };
  }, []);
  useEffect(() => {
    const onLogout = () => { disconnectWs(); setUserId(null); setRole('user'); };
    window.addEventListener('dash:logout', onLogout);
    return () => window.removeEventListener('dash:logout', onLogout);
  }, []);
  useEffect(() => {
    if (userId !== null) { connectWs(); return disconnectWs; }
  }, [userId]);
  useEffect(() => { window.scrollTo({ top: 0 }); }, [route]);

  if (userId === null) return <AuthPage onAuthed={profile => {
    setUserId(profile.userId);
    setRole(profile.role);
    // 同一张登录表单，按角色决定落地页：Admin -> 管理台，其余 -> 任务广场
    window.location.hash = profile.role === 'Admin' ? '#/admin' : '#/square';
  }} />;

  let page;
  const path = route.split('?')[0];
  if (path.startsWith('/admin')) {
    // 路由守卫：先等服务端确认角色，再决定放行还是弹回，避免刷新瞬间误判
    if (!roleReady) return <div className="admin-boot">正在校验管理员身份…</div>;
    if (role !== 'Admin') return <RedirectToSquare />;
    return <AdminApp />;
  }
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
