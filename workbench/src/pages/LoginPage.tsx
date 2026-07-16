import { FormEvent, useState } from 'react';
import { api } from '../api/client';

type Props = {
  onLoggedIn: () => void;
};

export function LoginPage({ onLoggedIn }: Props) {
  const [username, setUsername] = useState('');
  const [password, setPassword] = useState('');
  const [error, setError] = useState('');

  async function submit(event: FormEvent) {
    event.preventDefault();
    setError('');
    try {
      // Spring Security form login 写入 session cookie，后续接口复用同一登录态。
      await api.login(username, password);
      await api.me();
      console.info('v5 workbench 登录成功', { username });
      onLoggedIn();
    } catch (loginError) {
      setError(loginError instanceof Error ? loginError.message : '登录失败');
    }
  }

  return (
    <main className="login-screen">
      <div className="star-field" aria-hidden="true"><i /><i /><i /></div>
      <div className="login-layout">
        <section className="login-hero">
          <div className="login-brand"><span className="brand-star">✦</span> ASTERISM</div>
          <h1>星群</h1>
          <p>让每个 Agent 找到自己的轨道，协同完成从需求到交付的全过程。</p>
          <div className="constellation" aria-hidden="true">
            <svg viewBox="0 0 440 220"><path d="M30 158 L108 94 L180 132 L257 50 L330 86 L405 30" /><path d="M180 132 L236 188 L330 86" /></svg>
            <i /><i /><i /><i /><i /><i /><i />
          </div>
        </section>
        <form className="login-panel" onSubmit={submit}>
          <span className="login-eyebrow">ASTERISM WORKBENCH</span>
          <h2>登录</h2>
          <p>进入星群控制台</p>
          <label>
            用户名
            <input autoComplete="username" value={username} onChange={(event) => setUsername(event.target.value)} />
          </label>
          <label>
            密码
            <input type="password" autoComplete="current-password" value={password} onChange={(event) => setPassword(event.target.value)} />
          </label>
          {error && <div className="login-error" role="alert">{error}</div>}
          <button type="submit" disabled={!username || !password}>进入星群</button>
        </form>
      </div>
    </main>
  );
}
