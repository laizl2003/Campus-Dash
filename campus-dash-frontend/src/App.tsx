import { useEffect, useState } from 'react';
import { useHashRoute } from './router';
import { getUserId, login, logout } from './api';
import TopBar from './components/TopBar';
import Square from './pages/Square';
import Detail from './pages/Detail';
import Publish from './pages/Publish';
import Mine from './pages/Mine';
import Wallet from './pages/Wallet';
import Notifications from './pages/Notifications';
import Credit from './pages/Credit';
import { connectWs, disconnectWs } from './ws';

/** 演示身份：一键切换，实质是重新登录换 token。
 *  1001 发单人 / 2001、2002 跑腿 / 9001 平台仲裁员（后端 dash.auth.arbitrator-id） */
export const IDENTITIES = [
  { userId: 1001, name: '发单人 1001', desc: '发布任务、托管资金、确认完成' },
  { userId: 2001, name: '跑腿 2001', desc: '抢单、执行、送达' },
  { userId: 2002, name: '跑腿 2002', desc: '另一个跑腿，用于演示多人抢单' },
  { userId: 9001, name: '仲裁员 9001', desc: '平台身份，裁决争议' },
];

export default function App() {
  const route = useHashRoute();
  const [userId, setUserId] = useState<number | null>(getUserId());

  const switchIdentity = async (id: number) => {
    await login(id);
    setUserId(id);
    // 换身份 = 换 token，重连 WS（旧连接属于旧身份）
    disconnectWs();
    connectWs();
  };

  // 已登录用户进入应用时建立 WS（WS 不可用时自动降级轮询，见 ws.ts）
  useEffect(() => {
    if (userId !== null) {
      connectWs();
      return () => disconnectWs();
    }
  }, [userId]);

  if (userId === null) {
    return (
      <div className="login-page">
        <div className="login-card">
          <h1>校园跑腿抢单市场</h1>
          <p className="login-sub">
            N 人抢 1 · 资金托管 · 超时自动流转 · 结算对账
          </p>
          <p>选择一个演示身份进入：</p>
          {IDENTITIES.map((it) => (
            <button key={it.userId} className="identity-btn"
                    onClick={() => switchIdentity(it.userId)}>
              <strong>{it.name}</strong>
              <span>{it.desc}</span>
            </button>
          ))}
          <p className="login-hint">
            演示环境直接选择身份登录；真实系统此处是账号密码 / 扫码。
          </p>
        </div>
      </div>
    );
  }

  let page;
  if (route.startsWith('/detail/')) {
    page = <Detail errandId={route.split('/')[2]} onIdentityChange={setUserId} />;
  } else if (route === '/publish') {
    page = <Publish />;
  } else if (route === '/mine') {
    page = <Mine />;
  } else if (route === '/wallet') {
    page = <Wallet />;
  } else if (route === '/notifications') {
    page = <Notifications />;
  } else if (route === '/credit') {
    page = <Credit />;
  } else {
    page = <Square />;
  }

  return (
    <div className="app">
      <TopBar route={route} userId={userId} onSwitch={switchIdentity}
              onLogout={() => { logout(); setUserId(null); }} />
      <main className="main">{page}</main>
    </div>
  );
}
