import { FormEvent, useState } from 'react';
import { api } from '../api/client';
import { BrandMark } from '../components/BrandMark';

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
          <BrandMark inverse />
          <span className="login-eyebrow">AGENT WORKBENCH</span>
          <h1>星群</h1>
          <p>让需求、知识与 Agent 在同一工作空间有序协作，从想法走向交付。</p>
          <div className="login-orbit" aria-hidden="true">
            <span /><span /><span /><span /><span />
          </div>
        </section>
        <form className="login-panel" onSubmit={submit}>
          <span className="login-panel-kicker">欢迎回来</span>
          <h2>登录</h2>
          <p>使用你的工作台账号继续。</p>
          <label>
            用户名
            <input autoFocus autoComplete="username" placeholder="请输入用户名" value={username} onChange={(event) => setUsername(event.target.value)} />
          </label>
          <label>
            密码
            <input type="password" autoComplete="current-password" placeholder="请输入密码" value={password} onChange={(event) => setPassword(event.target.value)} />
          </label>
          {error && <div className="login-error" role="alert"><strong>登录失败</strong><span>{error}</span></div>}
          <button type="submit" disabled={!username || !password}>进入星群</button>
          <small className="login-footnote">Asterism Workbench · 安全会话</small>
        </form>
      </div>
    </main>
  );
}
