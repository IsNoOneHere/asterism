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
      <form className="login-panel" onSubmit={submit}>
        <h1>登录</h1>
        <label>
          用户名
          <input autoComplete="username" value={username} onChange={(event) => setUsername(event.target.value)} />
        </label>
        <label>
          密码
          <input type="password" autoComplete="current-password" value={password} onChange={(event) => setPassword(event.target.value)} />
        </label>
        {error && <div className="error-text">{error}</div>}
        <button type="submit" disabled={!username || !password}>
          登录
        </button>
      </form>
    </main>
  );
}
