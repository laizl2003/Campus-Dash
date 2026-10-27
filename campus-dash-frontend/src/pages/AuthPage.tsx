import { useCallback, useEffect, useRef, useState } from 'react';
import { captchaImage, changePassword, login, registerAccount, type Profile, type StoredAccount } from '../api';
import Icon from '../components/Icon';
import CampusArt from '../components/CampusArt';

/**
 * 登录 / 注册。
 *
 * 默认是登录表单；"注册新账号"就在密码栏下面，点进去同一个卡片切成注册表单，
 * 注册成功后回到登录并预填用户名——不做"注册即登录"，避免验证码被复用。
 */
export default function AuthPage({ onAuthed, onResume, accounts = [], currentUserId, onQuickLogin }: {
  onAuthed: (profile: Profile) => void;
  /** 已登录时从 #/login 进来：展示"继续以该身份进入"，不需要重新输密码 */
  onResume?: () => void;
  /** 本机已登录过的账号，用于快速切换（不需要重新输密码） */
  accounts?: StoredAccount[];
  currentUserId?: string | null;
  onQuickLogin?: (userId: string) => Promise<void> | void;
}) {
  const [mode, setMode] = useState<'login' | 'register' | 'change'>('login');
  const [username, setUsername] = useState('');
  const [password, setPassword] = useState('');
  const [phone, setPhone] = useState('');
  const [confirmPassword, setConfirmPassword] = useState('');
  const [newPassword, setNewPassword] = useState('');
  const [captchaCode, setCaptchaCode] = useState('');
  const [captcha, setCaptcha] = useState<{ captchaId: string; image: string } | null>(null);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState('');
  const [notice, setNotice] = useState('');
  const [quickBusy, setQuickBusy] = useState<string | null>(null);
  const [quickOpen, setQuickOpen] = useState(false);
  const [quickUp, setQuickUp] = useState(false);
  const quickRef = useRef<HTMLDivElement>(null);

  useEffect(() => {
    if (!quickOpen) return;
    const onDown = (event: MouseEvent) => {
      if (quickRef.current && !quickRef.current.contains(event.target as Node)) setQuickOpen(false);
    };
    document.addEventListener('mousedown', onDown);
    return () => document.removeEventListener('mousedown', onDown);
  }, [quickOpen]);

  const quickLogin = async (id: string) => {
    if (!onQuickLogin || quickBusy) return;
    if (id === currentUserId) { setQuickOpen(false); onResume?.(); return; }
    setQuickBusy(id);
    setError('');
    try { await onQuickLogin(id); }
    catch (e) { setError(e instanceof Error ? e.message : '快速登录失败，请重新输入密码'); }
    finally { setQuickBusy(null); }
  };

  const reloadCaptcha = useCallback(async () => {
    try {
      const next = await captchaImage();
      setCaptcha({ captchaId: next.captchaId, image: next.image });
      setCaptchaCode('');
    } catch {
      setCaptcha(null);
      setError('验证码加载失败，请检查后端是否已启动');
    }
  }, []);

  useEffect(() => { void reloadCaptcha(); }, [reloadCaptcha]);

  const switchMode = (next: 'login' | 'register' | 'change') => {
    setMode(next);
    setError('');
    setNotice('');
    setPassword('');
    setConfirmPassword('');
    setNewPassword('');
    void reloadCaptcha();
  };

  const submit = async (event: React.FormEvent) => {
    event.preventDefault();
    if (busy) return;
    if (!captcha) { setError('验证码未加载，请点击图片刷新'); return; }
    setBusy(true);
    setError('');
    setNotice('');
    try {
      if (mode === 'login') {
        const profile = await login({
          username: username.trim(), password, captchaId: captcha.captchaId, captchaCode,
        });
        onAuthed(profile);
        return;
      }
      if (mode === 'change') {
        await changePassword({
          username: username.trim(), oldPassword: password, newPassword,
          captchaId: captcha.captchaId, captchaCode,
        });
        setMode('login');
        setPassword('');
        setNewPassword('');
        setNotice('密码已修改，请用新密码登录');
        await reloadCaptcha();
        return;
      }
      if (password !== confirmPassword) {
        setError('两次输入的密码不一致');
        return;
      }
      await registerAccount({
        username: username.trim(), phone: phone.trim(), password, confirmPassword,
        captchaId: captcha.captchaId, captchaCode,
      });
      setMode('login');
      setPassword('');
      setConfirmPassword('');
      setPhone('');
      setNotice('注册成功，请用新账号登录');
      await reloadCaptcha();
    } catch (e) {
      setError(e instanceof Error ? e.message : '操作失败，请重试');
      // 验证码一次性，失败后必须换一张，否则再提交一定失败
      await reloadCaptcha();
    } finally {
      setBusy(false);
    }
  };

  return <div className="login-page">
    <section className="login-story">
      <a className="brand" href="#/square"><span className="brand-mark"><Icon name="package" size={24}/></span><span>CampusDash<small>校园里的顺路好帮手</small></span></a>
      <div className="login-story-body"><span className="eyebrow">A LITTLE HELP, A BETTER DAY</span><h1>校园里的小事，<br/><em>交给顺路的同学。</em></h1><p>取件、带饭、买东西。把一件小事托付出去，<br/>让你的时间留给更重要的生活。</p><CampusArt/><div className="login-benefits"><span><Icon name="shield" size={16}/>悬赏资金托管</span><span><Icon name="clock" size={16}/>任务进度可追踪</span><span><Icon name="users" size={16}/>校园互助</span></div></div>
      <div className="login-copyright">CAMPUSDASH · 让每一次顺路，都有意义</div>
    </section>

    <section className="login-side"><div className="login-card">
      <h2 className="auth-title">{mode === 'login' ? '从一个身份开始' : mode === 'register' ? '创建你的账号' : '修改登录密码'}</h2>



      <form className="auth-form" onSubmit={submit}>
        <label className="auth-field">
          <span>用户名</span>
          <input value={username} onChange={e => setUsername(e.target.value)} placeholder="3-20 位字母、数字或下划线"
                 autoComplete="username" maxLength={20} required/>
        </label>

        {mode === 'register' && <label className="auth-field">
          <span>手机号</span>
          <input value={phone} onChange={e => setPhone(e.target.value)} placeholder="11 位手机号"
                 inputMode="numeric" maxLength={11} required/>
        </label>}

        <label className="auth-field">
          <span>{mode === 'change' ? '原密码' : '密码'}</span>
          <input type="password" value={password} onChange={e => setPassword(e.target.value)}
                 placeholder={mode === 'register' ? '8-32 位，需同时含字母与数字' : mode === 'change' ? '请输入当前密码' : '请输入密码'}
                 autoComplete={mode === 'register' ? 'new-password' : 'current-password'} required/>
        </label>

        {mode === 'change' && <label className="auth-field">
          <span>新密码</span>
          <input type="password" value={newPassword} onChange={e => setNewPassword(e.target.value)}
                 placeholder="8-32 位，需同时含字母与数字" autoComplete="new-password" required/>
        </label>}

        {mode === 'register' && <label className="auth-field">
          <span>确认密码</span>
          <input type="password" value={confirmPassword} onChange={e => setConfirmPassword(e.target.value)}
                 placeholder="再输入一次" autoComplete="new-password" required/>
        </label>}

        <div className="auth-field">
          <span>图形验证码</span>
          <div className="auth-captcha">
            <input value={captchaCode} onChange={e => setCaptchaCode(e.target.value)}
                   placeholder="输入右侧字符" maxLength={4} autoComplete="off" required/>
            <button type="button" className="auth-captcha-image" tabIndex={-1}
                    onClick={() => void reloadCaptcha()}
                    title="看不清？换一张" aria-label="刷新验证码">
              {captcha ? <img src={captcha.image} alt="图形验证码"/> : <span>点击加载</span>}
            </button>
          </div>
        </div>

        {/* 注册 / 修改密码：同一行、位于验证码下方；只有带下划线的方框本身可点 */}
        {mode === 'login' && <div className="auth-link-row">
          <button type="button" className="auth-link-box" tabIndex={-1}
                  onClick={() => switchMode('register')}>注册账号</button>
          <button type="button" className="auth-link-box" tabIndex={-1}
                  onClick={() => switchMode('change')}>修改密码</button>
        </div>}

        {notice && <div className="banner ok" role="status">{notice}</div>}
        {error && <div className="banner warn" role="alert">{error}</div>}

        {/* 登录态只让 Tab 在三个输入框之间走：其余控件用鼠标点，或用回车提交 */}
        <button className="btn btn-primary auth-submit" disabled={busy} type="submit"
                tabIndex={mode === 'login' ? -1 : 0}>
          {busy ? '处理中…' : mode === 'login' ? '登录' : mode === 'register' ? '注册' : '确认修改'}
        </button>


        {mode !== 'login' && <button type="button" className="auth-switch" tabIndex={-1}
                onClick={() => switchMode('login')}>
          返回<strong>登录</strong>
        </button>}
      </form>

      {mode === 'login' && accounts.length > 0 && <div className="auth-quick" ref={quickRef}>
        <button type="button" className="auth-quick-toggle" aria-haspopup="menu" aria-expanded={quickOpen}
                onClick={() => {
                  const rect = quickRef.current?.getBoundingClientRect();
                  setQuickUp(rect ? window.innerHeight - rect.bottom < 280 : false);
                  setQuickOpen(v => !v);
                }}>
          <span><strong>本机账号</strong><small>{accounts.length} 个已登录账号 · 点击快速进入</small></span>
          <span className="auth-quick-caret" aria-hidden="true">⌄</span>
        </button>
        {quickOpen && <div className={`auth-quick-menu${quickUp ? ' is-up' : ''}`} role="menu" aria-label="本机账号">
          <div className="auth-quick-list">
            {accounts.map(a => <button key={a.userId} type="button" role="menuitem"
                    className={`auth-account-item${a.userId === currentUserId ? ' is-current' : ''}`}
                    disabled={quickBusy !== null} onClick={() => void quickLogin(a.userId)}>
              <span className="menu-avatar">{a.username.slice(0, 1).toUpperCase()}</span>
              <span className="menu-account"><strong>{a.username}</strong><small>{a.role === 'Admin' ? '管理员' : '普通用户'}{a.userId === currentUserId ? ' · 当前账号' : ''}</small></span>
              {a.userId === currentUserId ? <Icon name="check" size={14}/> : <Icon name="arrow-right" size={14}/>}
            </button>)}
          </div>
          <span className="auth-accounts-hint">切换不会退出原账号；只有右上角「退出登录」才会撤销令牌。</span>
        </div>}
      </div>}
    </div></section>
  </div>;
}
