import { useCallback, useEffect, useState } from 'react';
import { captchaImage, login, loginAs, registerAccount, type Profile } from '../api';
import { IDENTITIES } from '../identities';
import Icon from '../components/Icon';
import CampusArt from '../components/CampusArt';

/**
 * 登录 / 注册。
 *
 * 默认是登录表单；"注册新账号"就在密码栏下面，点进去同一个卡片切成注册表单，
 * 注册成功后回到登录并预填用户名——不做"注册即登录"，避免验证码被复用。
 */
export default function AuthPage({ onAuthed }: { onAuthed: (profile: Profile) => void }) {
  const [mode, setMode] = useState<'login' | 'register'>('login');
  const [username, setUsername] = useState('');
  const [password, setPassword] = useState('');
  const [phone, setPhone] = useState('');
  const [confirmPassword, setConfirmPassword] = useState('');
  const [captchaCode, setCaptchaCode] = useState('');
  const [captcha, setCaptcha] = useState<{ captchaId: string; image: string } | null>(null);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState('');
  const [notice, setNotice] = useState('');

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

  const switchMode = (next: 'login' | 'register') => {
    setMode(next);
    setError('');
    setNotice('');
    setPassword('');
    setConfirmPassword('');
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

  const quickEnter = async (userId: number) => {
    if (busy) return;
    setBusy(true);
    setError('');
    try {
      onAuthed(await loginAs(String(userId)));
    } catch (e) {
      setError(e instanceof Error ? e.message : '演示身份登录失败');
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
      <span className="soft-label">{mode === 'login' ? '体验工作台' : '创建账号'}</span>
      <h2>{mode === 'login' ? '从一个身份开始' : '加入 CampusDash'}</h2>
      <p className="login-description">{mode === 'login' ? '用用户名与密码登录，验证码每次都不一样。' : '用户名与手机号各只能注册一次，注册后即可登录。'}</p>

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
          <span>密码</span>
          <input type="password" value={password} onChange={e => setPassword(e.target.value)}
                 placeholder={mode === 'login' ? '请输入密码' : '8-32 位，需同时含字母与数字'}
                 autoComplete={mode === 'login' ? 'current-password' : 'new-password'} required/>
        </label>

        {mode === 'login' && <button type="button" className="auth-switch" onClick={() => switchMode('register')}>
          还没有账号？<strong>注册新账号</strong>
        </button>}

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
            <button type="button" className="auth-captcha-image" onClick={() => void reloadCaptcha()}
                    title="看不清？换一张" aria-label="刷新验证码">
              {captcha ? <img src={captcha.image} alt="图形验证码"/> : <span>点击加载</span>}
            </button>
          </div>
        </div>

        {notice && <div className="banner ok" role="status">{notice}</div>}
        {error && <div className="banner warn" role="alert">{error}</div>}

        <button className="btn btn-primary auth-submit" disabled={busy} type="submit">
          {busy ? '处理中…' : mode === 'login' ? '登录' : '注册'}
        </button>

        {mode === 'register' && <button type="button" className="auth-switch" onClick={() => switchMode('login')}>
          已有账号？<strong>返回登录</strong>
        </button>}
      </form>

      {mode === 'login' && <div className="auth-demo">
        <span>演示身份</span>
        <div>{IDENTITIES.map(it => <button key={it.userId} type="button" disabled={busy}
              onClick={() => void quickEnter(it.userId)}>{it.name}</button>)}</div>
      </div>}
    </div></section>
  </div>;
}
