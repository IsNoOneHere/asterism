import { NavLink, Navigate, Route, Routes, useLocation } from 'react-router-dom';
import { useQuery, useQueryClient } from '@tanstack/react-query';
import { useEffect } from 'react';
import { Bot, BrainCircuit, ClipboardList, Layers3, Map, Settings2, Users } from 'lucide-react';
import { api, CurrentUser } from './api/client';
import { AgentConfigPage } from './pages/AgentConfigPage';
import { LoginPage } from './pages/LoginPage';
import { NewPrdPage } from './pages/NewPrdPage';
import { PrdDraftsPage } from './pages/PrdDraftsPage';
import { WorkItemCenterLayout } from './pages/WorkItemCenterLayout';
import { WorkItemsPage } from './pages/WorkItemsPage';
import { WorkItemDetailPage } from './pages/WorkItemDetailPage';
import { SystemsPage } from './pages/SystemsPage';
import { MemoryPage } from './pages/MemoryPage';
import { UsersPage } from './pages/UsersPage';
import { KnowledgePage } from './pages/KnowledgePage';
import { SystemProvider, useCurrentSystem } from './SystemContext';
import { SystemSelect } from './components/SystemSelect';

const links = [
  { to: '/work-items', label: '工作项', icon: ClipboardList },
  { to: '/systems', label: '系统配置', icon: Settings2 },
  { to: '/agents', label: 'Agent / 模型配置', icon: Bot },
  { to: '/memory', label: '系统记忆', icon: BrainCircuit },
  { to: '/knowledge', label: '系统知识', icon: Map },
  { to: '/users', label: '用户与成员', icon: Users, adminOnly: true },
];

export function App() {
  const queryClient = useQueryClient();
  const me = useQuery({ queryKey: ['auth', 'me'], queryFn: api.me, retry: false });
  const systems = useQuery({ queryKey: ['systems'], queryFn: api.systems, enabled: Boolean(me.data), retry: false });

  useEffect(() => {
    const expired = () => queryClient.setQueryData(['auth', 'me'], null);
    window.addEventListener('v5:auth-expired', expired);
    return () => window.removeEventListener('v5:auth-expired', expired);
  }, [queryClient]);

  if (me.isLoading) {
    return <div className="center-note">登录状态检查中...</div>;
  }

  if (me.isError || !me.data) {
    return <LoginPage onLoggedIn={() => queryClient.invalidateQueries({ queryKey: ['auth', 'me'] })} />;
  }

  const user = me.data;
  const visibleLinks = links.filter((link) => !link.adminOnly || isAdmin(user));

  if (systems.isLoading) {
    return <div className="center-note">系统加载中...</div>;
  }

  return (
    <SystemProvider systems={systems.data ?? []}>
      <AuthenticatedShell user={user} visibleLinks={visibleLinks} onLogout={async () => {
        await api.logout();
        queryClient.clear();
      }} />
    </SystemProvider>
  );
}

function AuthenticatedShell({ user, visibleLinks, onLogout }: { user: CurrentUser; visibleLinks: typeof links; onLogout: () => Promise<void> }) {
  const { systems, systemId, setSystemId } = useCurrentSystem();
  const { pathname } = useLocation();
  const showSystemContext = shouldShowSystemContext(pathname);
  return (
    <div className="shell">
      <aside className="sidebar">
        <div className="brand">Asterism</div>
        <div className="user-chip">{user.userId}</div>
        <nav>
          {visibleLinks.map((link) => {
            const Icon = link.icon;
            return (
              <NavLink key={link.to} to={link.to} className={({ isActive }) => (isActive ? 'active' : '')}>
                <Icon size={18} aria-hidden="true" />
                <span>{link.label}</span>
              </NavLink>
            );
          })}
        </nav>
        <button type="button" className="logout-button" onClick={() => void onLogout()}>退出登录</button>
      </aside>
      <div className="workspace-shell">
        {showSystemContext && systems.length > 0 && (
          <header className="workspace-context-bar">
            <Layers3 size={18} aria-hidden="true" />
            <SystemSelect systems={systems} value={systemId} label="当前工作系统" onChange={setSystemId} />
          </header>
        )}
        <main className="main">
          <Routes>
          <Route path="/" element={<Navigate to="/work-items" replace />} />
          <Route element={<WorkItemCenterLayout />}>
            <Route path="/work-items" element={<WorkItemsPage />} />
            <Route path="/work-items/drafts" element={<PrdDraftsPage />} />
          </Route>
          <Route path="/work-items/new" element={<NewPrdPage />} />
          <Route path="/work-items/new/:prdId" element={<NewPrdPage />} />
          <Route path="/work-items/drafts/:prdId" element={<NewPrdPage />} />
          <Route path="/work-items/:workItemId" element={<WorkItemDetailPage />} />
          <Route path="/new" element={<Navigate to="/work-items/new" replace />} />
          <Route path="/systems" element={<SystemsPage />} />
          <Route path="/models" element={<Navigate to="/agents" replace />} />
          <Route path="/agents" element={<AgentConfigPage />} />
          <Route path="/memory" element={<MemoryPage />} />
          <Route path="/knowledge" element={<KnowledgePage />} />
          <Route path="/users" element={isAdmin(user) ? <UsersPage /> : <Navigate to="/work-items" replace />} />
          </Routes>
        </main>
      </div>
    </div>
  );
}

function shouldShowSystemContext(pathname: string) {
  // 创建和详情页由业务对象自身决定所属系统。
  if (pathname === '/work-items' || pathname === '/work-items/drafts') return true;
  return !pathname.startsWith('/work-items/');
}

export function isAdmin(user: CurrentUser) {
  // 后端用 Spring Security 角色名，前端只做显示控制，权限仍以后端为准。
  return user.roles.includes('ROLE_ADMIN');
}
