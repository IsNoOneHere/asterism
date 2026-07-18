import { NavLink, Navigate, Route, Routes, useLocation } from 'react-router-dom';
import { useQuery, useQueryClient } from '@tanstack/react-query';
import { useEffect } from 'react';
import { Bot, BrainCircuit, ClipboardList, Cpu, LogOut, Map, Settings2, Users } from 'lucide-react';
import type { LucideIcon } from 'lucide-react';
import { api, ApiError, CurrentUser } from './api/client';
import { BrandMark } from './components/BrandMark';
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
import { ErrorState } from './components/Display';

type NavigationItem = { to: string; label: string; icon: LucideIcon; adminOnly?: boolean };

const navigationGroups: { label: string; items: NavigationItem[] }[] = [
  { label: '工作空间', items: [
    { to: '/work-items', label: '工作项', icon: ClipboardList },
  ] },
  { label: '知识与上下文', items: [
    { to: '/memory', label: '系统记忆', icon: BrainCircuit },
    { to: '/knowledge', label: '系统知识', icon: Map },
  ] },
  { label: '系统管理', items: [
    { to: '/systems', label: '系统配置', icon: Settings2 },
    { to: '/models', label: '模型配置', icon: Cpu },
    { to: '/agents', label: 'Agent 配置', icon: Bot },
    { to: '/users', label: '用户与成员', icon: Users, adminOnly: true },
  ] },
];

export function App() {
  const queryClient = useQueryClient();
  const me = useQuery({ queryKey: ['auth', 'me'], queryFn: api.me, retry: false });
  const systems = useQuery({ queryKey: ['systems'], queryFn: api.systems, enabled: Boolean(me.data), retry: false });

  useEffect(() => {
    const expired = () => {
      // 会话失效时同步清理业务缓存，重新登录后不能看到上一账号的数据。
      queryClient.setQueryData(['auth', 'me'], null);
      queryClient.removeQueries({ predicate: (query) => query.queryKey[0] !== 'auth' });
    };
    window.addEventListener('v5:auth-expired', expired);
    return () => window.removeEventListener('v5:auth-expired', expired);
  }, [queryClient]);

  if (me.isLoading) {
    return <div className="center-note">登录状态检查中...</div>;
  }

  if (me.isError && (!(me.error instanceof ApiError) || me.error.status !== 401)) {
    return <div className="center-note"><ErrorState title="登录状态检查失败" error={me.error} onRetry={() => me.refetch()} /></div>;
  }

  if (me.isError || !me.data) {
    return <LoginPage onLoggedIn={() => queryClient.invalidateQueries({ queryKey: ['auth', 'me'] })} />;
  }

  const user = me.data;
  const visibleGroups = navigationGroups.map((group) => ({
    ...group,
    items: group.items.filter((item) => !item.adminOnly || isAdmin(user)),
  })).filter((group) => group.items.length > 0);

  if (systems.isLoading) {
    return <div className="center-note">系统加载中...</div>;
  }

  if (systems.isError) {
    return <div className="center-note"><ErrorState title="系统列表加载失败" error={systems.error} onRetry={() => systems.refetch()} /></div>;
  }

  return (
    <SystemProvider systems={systems.data ?? []} currentUser={user}>
      <AuthenticatedShell user={user} visibleGroups={visibleGroups} onLogout={() => {
        // 先清理本地登录态，让页面立即返回登录页，再异步结束服务端会话。
        queryClient.setQueryData(['auth', 'me'], null);
        queryClient.removeQueries({ predicate: (query) => query.queryKey[0] !== 'auth' });
        void api.logout().catch((error) => console.warn('v5 workbench 退出会话失败', error));
      }} />
    </SystemProvider>
  );
}

function AuthenticatedShell({ user, visibleGroups, onLogout }: { user: CurrentUser; visibleGroups: typeof navigationGroups; onLogout: () => void }) {
  const { systems, systemId, setSystemId, systemAccessError, retrySystemAccess } = useCurrentSystem();
  const { pathname } = useLocation();
  const showSystemContext = shouldShowSystemContext(pathname);
  const page = pageContext(pathname);
  return (
    <div className="shell">
      <aside className="sidebar">
        <BrandMark compact />
        <div className="sidebar-navigation">
          {visibleGroups.map((group) => (
            <section className="nav-group" key={group.label}>
              <span className="nav-group-label">{group.label}</span>
              <nav aria-label={group.label}>
                {group.items.map((item) => {
                  const Icon = item.icon;
                  return (
                    <NavLink key={item.to} to={item.to} className={({ isActive }) => (isActive ? 'active' : '')}>
                      <Icon size={18} aria-hidden="true" />
                      <span>{item.label}</span>
                    </NavLink>
                  );
                })}
              </nav>
            </section>
          ))}
        </div>
        <div className="sidebar-footer">
          <div className="user-identity">
            <span className="user-avatar" aria-hidden="true">{user.userId.slice(0, 1).toUpperCase()}</span>
            <span><strong>{user.userId}</strong><small>{isAdmin(user) ? '系统管理员' : '系统成员'}</small></span>
          </div>
          <button type="button" className="logout-button" aria-label="退出登录" title="退出登录" onClick={onLogout}><LogOut size={17} aria-hidden="true" /></button>
        </div>
      </aside>
      <div className="workspace-shell">
        <header className="workspace-context-bar">
          <div className="workspace-location">
            <span>{page.group}</span>
            <strong>{page.title}</strong>
          </div>
          {showSystemContext && systems.length > 0 && (
            <SystemSelect systems={systems} value={systemId} label="当前工作系统" onChange={setSystemId} />
          )}
        </header>
        <main className="main">
          {Boolean(systemAccessError) && <ErrorState title="系统权限加载失败" error={systemAccessError} onRetry={retrySystemAccess} />}
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
          <Route path="/models" element={<AgentConfigPage section="models" />} />
          <Route path="/agents" element={<AgentConfigPage section="agents" />} />
          <Route path="/memory" element={<MemoryPage />} />
          <Route path="/knowledge" element={<KnowledgePage />} />
          <Route path="/users" element={isAdmin(user) ? <UsersPage currentUserId={user.userId} /> : <Navigate to="/work-items" replace />} />
          <Route path="*" element={<Navigate to="/work-items" replace />} />
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

function pageContext(pathname: string) {
  if (pathname.startsWith('/work-items')) return { group: '工作空间', title: pathname.includes('/new') ? '创建工作项' : '工作项' };
  if (pathname === '/memory') return { group: '知识与上下文', title: '系统记忆' };
  if (pathname === '/knowledge') return { group: '知识与上下文', title: '系统知识库' };
  if (pathname === '/systems') return { group: '系统管理', title: '系统配置' };
  if (pathname === '/models') return { group: '系统管理', title: '模型配置' };
  if (pathname === '/agents') return { group: '系统管理', title: 'Agent 配置' };
  if (pathname === '/users') return { group: '系统管理', title: '用户与成员' };
  return { group: 'Asterism', title: 'Workbench' };
}

export function isAdmin(user: CurrentUser) {
  // 后端用 Spring Security 角色名，前端只做显示控制，权限仍以后端为准。
  return user.roles.includes('ROLE_ADMIN');
}
