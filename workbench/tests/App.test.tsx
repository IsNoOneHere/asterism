import { act, fireEvent, screen, waitFor, within } from '@testing-library/react';
import { beforeEach, expect, test, vi } from 'vitest';
import { jsonResponse, renderApp, renderAppWithRouter, resetAppTestState, setApiResponse, setWorkItems } from './appTestHarness';

beforeEach(resetAppTestState);

test('renders workbench navigation after auth check', async () => {
  renderApp('/work-items');

  expect(await screen.findByText('Asterism')).toBeInTheDocument();
  expect(await screen.findByText('工作项中心')).toBeInTheDocument();
  expect(await screen.findByLabelText('当前工作系统')).toBeInTheDocument();
  expect(document.querySelector('.sidebar [aria-label="当前工作系统"]')).not.toBeInTheDocument();
  expect(document.querySelectorAll('.sidebar nav svg')).toHaveLength(7);
  expect(document.querySelectorAll('.page-tabs svg')).toHaveLength(2);
});

test.each(['/work-items/drafts', '/systems', '/models', '/agents', '/memory', '/users'])('shows global system context on %s', async (path) => {
  renderApp(path);

  expect(await screen.findByLabelText('当前工作系统')).toBeInTheDocument();
});

test('system configuration uses Chinese field labels', async () => {
  renderApp('/systems');

  expect(await screen.findByLabelText('系统编号')).toBeInTheDocument();
  expect(screen.getByLabelText('系统负责人')).toBeInTheDocument();
  expect(screen.getByText('Git 与发布')).toBeInTheDocument();
  expect(screen.getByLabelText('仓库 1 本地路径')).toBeInTheDocument();
  expect(screen.getByLabelText('仓库 1 允许修改路径')).toBeInTheDocument();
  expect(screen.getByLabelText('仓库 1 禁止修改路径')).toBeInTheDocument();
  expect(screen.getByLabelText('仓库 1 测试命令')).toBeInTheDocument();
});

test('shows login page when auth check fails', async () => {
  vi.mocked(fetch).mockImplementationOnce(() => jsonResponse('unauthorized', false));

  renderApp('/work-items');

  expect(await screen.findByRole('heading', { name: '登录' })).toBeInTheDocument();
  expect(screen.getByRole('heading', { name: '星群' })).toBeInTheDocument();
  expect(document.querySelector('.star-field')).toBeInTheDocument();
});

test('auth service failure shows retry instead of pretending the user is logged out', async () => {
  vi.mocked(fetch).mockImplementationOnce(() => jsonResponse({ message: '认证服务暂时不可用' }, false, 503));
  renderApp('/work-items');

  const alert = await screen.findByRole('alert');
  expect(alert).toHaveTextContent('登录状态检查失败');
  expect(alert).toHaveTextContent('认证服务暂时不可用');
  expect(screen.queryByRole('heading', { name: '登录' })).not.toBeInTheDocument();
});

test('login shows a pending state and prevents duplicate submission', async () => {
  const fallback = vi.mocked(fetch).getMockImplementation()!;
  let initialAuthCheck = true;
  let completeLogin!: () => void;
  const loginResponse = new Promise<Response>((resolve) => {
    completeLogin = () => { void jsonResponse(undefined).then(resolve); };
  });
  vi.mocked(fetch).mockImplementation((input: RequestInfo | URL, init?: RequestInit) => {
    const path = String(input);
    if (path === '/api/v5/auth/me' && initialAuthCheck) {
      initialAuthCheck = false;
      return jsonResponse('unauthorized', false);
    }
    if (path === '/api/v5/auth/login') return loginResponse;
    return fallback(input, init);
  });
  renderApp('/work-items');

  fireEvent.change(await screen.findByLabelText('用户名'), { target: { value: 'admin' } });
  fireEvent.change(screen.getByLabelText('密码'), { target: { value: 'secret' } });
  fireEvent.click(screen.getByRole('button', { name: '进入星群' }));

  expect(await screen.findByRole('button', { name: '登录中…' })).toBeDisabled();
  expect(vi.mocked(fetch).mock.calls.filter(([path]) => path === '/api/v5/auth/login')).toHaveLength(1);
  await act(async () => completeLogin());
  expect(await screen.findByText('工作项中心')).toBeInTheDocument();
});

test('logout immediately returns to login page', async () => {
  renderApp('/work-items');

  fireEvent.click(await screen.findByRole('button', { name: '退出登录' }));

  expect(await screen.findByRole('heading', { name: '登录' })).toBeInTheDocument();
  expect(fetch).toHaveBeenCalledWith('/logout', expect.objectContaining({ method: 'POST' }));
});

test('system list failure shows a retry action instead of an empty workspace', async () => {
  const fallback = vi.mocked(fetch).getMockImplementation()!;
  let failSystems = true;
  vi.mocked(fetch).mockImplementation((input: RequestInfo | URL, init?: RequestInit) => {
    if (String(input) === '/api/v5/systems' && failSystems) {
      failSystems = false;
      return jsonResponse({ message: '系统服务暂时不可用' }, false, 503);
    }
    return fallback(input, init);
  });
  renderApp('/systems');

  const alert = await screen.findByRole('alert');
  expect(alert).toHaveTextContent('系统列表加载失败');
  expect(alert).toHaveTextContent('系统服务暂时不可用');
  fireEvent.click(within(alert).getByRole('button', { name: '重新加载' }));

  expect(await screen.findByRole('heading', { name: '系统配置' })).toBeInTheDocument();
});

test('system page exposes owner list loading failures', async () => {
  const fallback = vi.mocked(fetch).getMockImplementation()!;
  vi.mocked(fetch).mockImplementation((input: RequestInfo | URL, init?: RequestInit) => {
    if (String(input) === '/api/v5/users') return jsonResponse({ message: '用户列表暂时不可用' }, false, 503);
    return fallback(input, init);
  });
  renderApp('/systems');

  expect(await screen.findByText('负责人列表加载失败')).toBeInTheDocument();
  expect(screen.getByText('用户列表暂时不可用')).toBeInTheDocument();
});

test('permission lookup failure is shown as an error instead of a read-only notice', async () => {
  setApiResponse('/api/v5/auth/me', { userId: 'reader', roles: [] });
  const fallback = vi.mocked(fetch).getMockImplementation()!;
  vi.mocked(fetch).mockImplementation((input: RequestInfo | URL, init?: RequestInit) => {
    if (String(input) === '/api/v5/systems/alpha-system/members') {
      return jsonResponse({ message: '权限服务暂时不可用' }, false, 503);
    }
    return fallback(input, init);
  });
  renderApp('/models');

  expect(await screen.findByText('系统权限加载失败')).toBeInTheDocument();
  expect(screen.getByText('权限服务暂时不可用')).toBeInTheDocument();
  expect(screen.queryByText('当前账号在此系统中为只读成员，配置操作已禁用。')).not.toBeInTheDocument();
});

test.each([
  { path: '/systems', button: '编辑', heading: '编辑系统信息' },
  { path: '/users', button: '新增用户', heading: '新增用户' },
  { path: '/knowledge', button: '新增条目', heading: '新增知识条目' },
])('$path keeps the list first and opens $heading in a dialog', async ({ path, button, heading }) => {
  renderApp(path);

  const actions = await screen.findAllByRole('button', { name: button });
  await waitFor(() => expect(actions[0]).toBeEnabled());
  fireEvent.click(actions[0]);

  expect(await screen.findByRole('dialog')).toHaveAttribute('open');
  expect(screen.getByRole('heading', { name: heading })).toBeInTheDocument();
});

test('knowledge list requests one server page instead of the full dataset', async () => {
  const firstPage = '/api/v5/systems/alpha-system/knowledge/page?status=candidate&page=1&pageSize=10&query=';
  const secondPage = '/api/v5/systems/alpha-system/knowledge/page?status=candidate&page=2&pageSize=10&query=';
  const entry = (entryId: string, title: string, anchorTexts: string[] = []) => ({
    entryId, systemId: 'alpha-system', repo: 'main', kind: 'page', title,
    anchorTexts, routePath: '/login', apiEndpoints: [], codeRefs: [],
    status: 'candidate', source: 'code_index', sourceRef: '',
  });
  setApiResponse(firstPage, { items: [entry('knowledge-1', '第一页知识', ['规则设置', '原始文件名'])], total: 21, page: 1, pageSize: 10, totalPages: 3 });
  setApiResponse(secondPage, { items: [entry('knowledge-11', '第二页知识')], total: 21, page: 2, pageSize: 10, totalPages: 3 });
  renderApp('/knowledge');

  expect(await screen.findByText('第一页知识')).toBeInTheDocument();
  expect(screen.getByText('仓库：main · 2 个文字锚点')).toBeInTheDocument();
  expect(screen.queryByText('规则设置')).not.toBeInTheDocument();
  expect(screen.getByRole('button', { name: '批准' }).closest('td')).toHaveClass('table-action-cell');
  expect(fetch).toHaveBeenCalledWith(firstPage, expect.anything());
  fireEvent.click(screen.getByRole('button', { name: '下一页' }));

  expect(await screen.findByText('第二页知识')).toBeInTheDocument();
  expect(fetch).toHaveBeenCalledWith(secondPage, expect.anything());
  expect(vi.mocked(fetch).mock.calls.some(([path]) => String(path).startsWith('/api/v5/systems/alpha-system/knowledge?'))).toBe(false);
});

test.each([
  { path: '/systems', control: 'Git 配置' },
  { path: '/users', control: '编辑用户 admin' },
  { path: '/models', control: '测试 Claude 主模型连通性' },
  { path: '/agents', control: '编辑 product' },
])('$path keeps row actions in the shared centered cell', async ({ path, control }) => {
  renderApp(path);

  const actions = await screen.findAllByRole('button', { name: control });
  expect(actions[0].closest('td')).toHaveClass('table-action-cell');
});

test.each([
  { path: '/systems', menu: undefined, button: '删除系统 alpha-system', confirmButton: '删除系统', requestPath: '/api/v5/systems/alpha-system' },
  { path: '/users', menu: '更多操作 demo-user', button: '删除用户 demo-user', confirmButton: '删除用户', requestPath: '/api/v5/users/demo-user' },
])('$path uses the unified confirmation dialog and sends DELETE', async ({ path, menu, button, confirmButton, requestPath }) => {
  renderApp(path);

  if (menu) fireEvent.click(await screen.findByRole('button', { name: menu }));
  fireEvent.click(await screen.findByRole(menu ? 'menuitem' : 'button', { name: button }));
  const dialog = screen.getByRole('dialog');
  expect(dialog).toHaveAttribute('open');
  expect(fetch).not.toHaveBeenCalledWith(requestPath, expect.objectContaining({ method: 'DELETE' }));
  fireEvent.click(within(dialog).getByRole('button', { name: confirmButton }));

  await waitFor(() => expect(fetch).toHaveBeenCalledWith(requestPath, expect.objectContaining({ method: 'DELETE' })));
});

test('system deletion failure is shown in the unified alert dialog', async () => {
  const fallback = vi.mocked(fetch).getMockImplementation()!;
  vi.mocked(fetch).mockImplementation((input: RequestInfo | URL, init?: RequestInit) => {
    if (String(input) === '/api/v5/systems/alpha-system' && init?.method === 'DELETE') {
      return jsonResponse({ message: '系统已有业务数据，无法删除' }, false, 409);
    }
    return fallback(input, init);
  });
  renderApp('/systems');

  fireEvent.click(await screen.findByRole('button', { name: '删除系统 alpha-system' }));
  fireEvent.click(within(screen.getByRole('dialog')).getByRole('button', { name: '删除系统' }));

  const alert = await screen.findByRole('alertdialog');
  expect(alert).toHaveTextContent('删除失败');
  expect(alert).toHaveTextContent('系统已有业务数据，无法删除');
  fireEvent.click(within(alert).getByRole('button', { name: '知道了' }));
  await waitFor(() => expect(screen.queryByRole('alertdialog')).not.toBeInTheDocument());
});

test('disabled users require an explicit enable action and current user cannot disable itself', async () => {
  setApiResponse('/api/v5/users', [
    { userId: 'admin', displayName: 'Admin', enabled: true },
    { userId: 'disabled-user', displayName: 'Disabled User', enabled: false },
  ]);
  renderApp('/users');

  fireEvent.click(await screen.findByRole('button', { name: '更多操作 admin' }));
  expect(screen.getByRole('menuitem', { name: '禁用用户' })).toBeDisabled();
  fireEvent.click(screen.getByRole('button', { name: '更多操作 disabled-user' }));
  fireEvent.click(screen.getByRole('menuitem', { name: '启用用户' }));

  await waitFor(() => expect(fetch).toHaveBeenCalledWith('/api/v5/users/disabled-user/enable', expect.objectContaining({ method: 'POST' })));
});

test('user rows keep frequent actions visible and place destructive actions in the more menu', async () => {
  renderApp('/users');

  expect(await screen.findByRole('button', { name: '编辑用户 demo-user' })).toBeInTheDocument();
  expect(screen.getByRole('button', { name: '重置 demo-user 的密码' })).toBeInTheDocument();
  expect(screen.queryByRole('menuitem', { name: '删除用户 demo-user' })).not.toBeInTheDocument();

  fireEvent.click(screen.getByRole('button', { name: '更多操作 demo-user' }));
  expect(screen.getByRole('menuitem', { name: '删除用户 demo-user' })).toBeInTheDocument();
  expect(document.querySelector('.users-table-frame')).toHaveClass('menu-open');

  fireEvent.click(screen.getByRole('tab', { name: /当前系统成员/ }));
  expect(screen.getByRole('button', { name: '移除' }).closest('td')).toHaveClass('table-action-cell');
  fireEvent.click(screen.getByRole('tab', { name: /用户列表/ }));
  expect(screen.queryByRole('menuitem', { name: '删除用户 demo-user' })).not.toBeInTheDocument();
});

test('work item page loads selected system from systems api', async () => {
  renderApp('/work-items');

  fireEvent.change(await screen.findByLabelText('范围'), { target: { value: 'system' } });
  fireEvent.click(await screen.findByLabelText('当前工作系统'));
  fireEvent.click(await screen.findByRole('option', { name: 'Prod System' }));

  await waitFor(() => {
    expect(fetch).toHaveBeenCalledWith('/api/v5/work-items?scope=system&systemId=prod-system&sort=updated_desc', expect.anything());
  });
});

test('work item table separates id and title and shows creator', async () => {
  setWorkItems([{
    workItemId: 'WI202607114827', title: '优化工作项列表', lifecycleStatus: 'activated', approvalStatus: 'approved',
    executionAllowed: true, updatedAt: '2026-07-11T03:00:00Z', createdBy: 'admin', canDelete: true, canControl: false, availableActions: [],
  }]);

  renderApp('/work-items');

  expect(await screen.findByRole('columnheader', { name: '工作项 ID' })).toBeInTheDocument();
  expect(screen.getByRole('columnheader', { name: '标题' })).toBeInTheDocument();
  expect(screen.getByRole('columnheader', { name: '创建人' })).toBeInTheDocument();
  expect(await screen.findByRole('link', { name: 'WI202607114827' })).toHaveAttribute('href', '/work-items/WI202607114827');
  expect(await screen.findByText('优化工作项列表')).toBeInTheDocument();
  expect(screen.getByText('优化工作项列表')).toHaveAttribute('title', '优化工作项列表');
  expect(screen.getByRole('cell', { name: 'admin' })).toBeInTheDocument();
  const deleteButton = screen.getByRole('button', { name: '删除工作项 WI202607114827' });
  expect(deleteButton).toBeEnabled();
  expect(deleteButton.closest('td')).toHaveClass('table-action-cell');
});

test('work item deletion is available regardless of lifecycle status', async () => {
  setWorkItems([
    { workItemId: 'WI-ACTIVE', title: '执行中工作项', lifecycleStatus: 'activated', approvalStatus: 'approved',
      executionAllowed: true, createdBy: 'admin', canDelete: true, canControl: false, availableActions: [] },
    { workItemId: 'WI-DONE', title: '已完成工作项', lifecycleStatus: 'completed', approvalStatus: 'approved',
      executionAllowed: false, createdBy: 'admin', canDelete: true, canControl: false, availableActions: [] },
  ]);
  renderApp('/work-items');

  expect(await screen.findByRole('button', { name: '删除工作项 WI-ACTIVE' })).toBeEnabled();
  fireEvent.click(screen.getByRole('button', { name: '删除工作项 WI-DONE' }));
  fireEvent.click(within(screen.getByRole('dialog')).getByRole('button', { name: '删除工作项' }));

  await waitFor(() => expect(screen.queryByText('已完成工作项')).not.toBeInTheDocument());
  expect(screen.getByText('执行中工作项')).toBeInTheDocument();
});

test('work item filters and second page survive detail return', async () => {
  setWorkItems(Array.from({ length: 21 }, (_, index) => ({
    workItemId: index === 20 ? 'wi-1' : `wi-${index + 2}`,
    title: index === 20 ? '登录页错误提示' : `工作项 ${index + 1}`,
    lifecycleStatus: 'completed', approvalStatus: 'approved', executionAllowed: false,
    updatedAt: '2026-07-11T03:00:00Z', createdBy: 'admin', canControl: false, availableActions: [],
  })));
  renderApp('/work-items');

  fireEvent.change(await screen.findByLabelText('范围'), { target: { value: 'all' } });
  fireEvent.change(screen.getByLabelText('状态'), { target: { value: 'completed' } });
  fireEvent.change(screen.getByLabelText('搜索工作项'), { target: { value: '登录' } });
  fireEvent.change(screen.getByLabelText('排序'), { target: { value: 'created_asc' } });
  fireEvent.click(await screen.findByRole('button', { name: '下一页' }));
  expect(await screen.findByText('第 2 / 2 页')).toBeInTheDocument();

  fireEvent.click(await screen.findByRole('link', { name: 'wi-1' }));
  fireEvent.click(await screen.findByRole('link', { name: '← 返回工作项中心' }));

  expect(await screen.findByLabelText('范围')).toHaveValue('all');
  expect(screen.getByLabelText('状态')).toHaveValue('completed');
  expect(screen.getByLabelText('搜索工作项')).toHaveValue('登录');
  expect(screen.getByLabelText('排序')).toHaveValue('created_asc');
  expect(await screen.findByText('第 2 / 2 页')).toBeInTheDocument();
});

test('browser back restores work item query state', async () => {
  setWorkItems([{
    workItemId: 'wi-1', title: '登录页错误提示', lifecycleStatus: 'completed', approvalStatus: 'approved',
    executionAllowed: false, updatedAt: '2026-07-11T03:00:00Z', createdBy: 'admin', canControl: false, availableActions: [],
  }]);
  const router = renderAppWithRouter('/work-items');

  fireEvent.change(await screen.findByLabelText('搜索工作项'), { target: { value: '登录' } });
  await waitFor(() => expect(router.state.location.state).toMatchObject({ workItemList: { q: '登录' } }));
  fireEvent.click(await screen.findByRole('link', { name: 'wi-1' }));
  expect(await screen.findByRole('heading', { name: '登录页错误提示' })).toBeInTheDocument();

  await act(async () => { await router.navigate(-1); });
  expect(await screen.findByLabelText('搜索工作项')).toHaveValue('登录');
});

test('work item detail shows the Claude SDK supervisor and repository agent', async () => {
  renderApp('/work-items/wi-1');

  expect(await screen.findByText('登录页错误提示')).toBeInTheDocument();
  expect(screen.queryByLabelText('当前工作系统')).not.toBeInTheDocument();
  expect(await screen.findByText('alpha-system')).toBeInTheDocument();
  fireEvent.click(screen.getByRole('button', { name: /计划与执行/ }));
  const progress = await screen.findByRole('list', { name: 'Agent 执行进度' });
  expect(within(progress).getByText(/developer · Supervisor/)).toBeInTheDocument();
  expect(within(progress).getByText('frontend')).toBeInTheDocument();
  expect(within(progress).getByText('前端修改完成')).toBeInTheDocument();
});

test('work item polling keeps the stage selected by the user', async () => {
  renderApp('/work-items/wi-1');

  await screen.findByRole('heading', { name: '代码确认' });
  fireEvent.click(screen.getByRole('button', { name: /计划与执行/ }));
  expect(screen.getByRole('heading', { name: '计划与执行' })).toBeInTheDocument();
  setApiResponse('/api/v5/work-items/wi-1', {
    workItemId: 'wi-1', systemId: 'alpha-system', title: '登录页错误提示', lifecycleStatus: 'validation_passed',
    currentStage: '等待上线确认', waitingFor: 'owner', canControl: true, availableActions: ['release_approved'],
  });

  await waitFor(() => expect(screen.getByRole('button', { name: /Git 提交与 MR/ })).toHaveAttribute('aria-current', 'step'), { timeout: 4500 });
  expect(screen.getByRole('heading', { name: '计划与执行' })).toBeInTheDocument();
}, 7000);

test('work item detail keeps long Git content inside the code tab', async () => {
  renderApp('/work-items/wi-1');

  expect(await screen.findByRole('heading', { name: '代码确认' })).toBeInTheDocument();
  expect(screen.queryByText(/diff --git a\/src\/login.tsx/)).not.toBeInTheDocument();
  expect(screen.queryByText('原始 JSON')).not.toBeInTheDocument();

  fireEvent.click(screen.getByRole('button', { name: '代码变更' }));
  expect((await screen.findAllByText('claude_sdk_team')).length).toBeGreaterThan(0);
  expect(await screen.findByText('输入 320 / 输出 80')).toBeInTheDocument();
  expect(await screen.findByText('完整 Diff')).toBeInTheDocument();
  expect(screen.getByTitle('src/features/authentication/components/LoginErrorMessageWithResponsiveLayout.tsx')).toBeInTheDocument();
  expect(screen.getByRole('region', { name: 'main 完整 Diff' })).toHaveAttribute('tabindex', '0');
  expect(await screen.findByText(/diff --git a\/src\/login.tsx/)).toBeInTheDocument();
});

test('diff review requires a multiline note before submitting automatic revision', async () => {
  renderApp('/work-items/wi-1');

  fireEvent.click(await screen.findByRole('button', { name: '代码变更' }));
  const note = await screen.findByLabelText('修订意见（必填）');
  const reject = screen.getByRole('button', { name: '打回修订' });
  expect(note.tagName).toBe('TEXTAREA');
  expect(reject).toBeDisabled();

  fireEvent.change(note, { target: { value: '错误提示应放在输入框下方' } });
  expect(reject).toBeEnabled();
  fireEvent.click(reject);
  const dialog = screen.getByRole('dialog');
  expect(within(dialog).getByRole('button', { name: '打回修订' })).toBeEnabled();
  fireEvent.click(within(dialog).getByRole('button', { name: '打回修订' }));

  await waitFor(() => {
    const call = vi.mocked(fetch).mock.calls.find(([path, init]) =>
      String(path).endsWith('/signals/patch_apply_rejected') && init?.method === 'POST');
    expect(call).toBeDefined();
    expect(JSON.parse(String(call?.[1]?.body))).toMatchObject({ note: '错误提示应放在输入框下方' });
  });
});

test('stale patch action refreshes the completed work item and removes the old button', async () => {
  const fallback = vi.mocked(fetch).getMockImplementation()!;
  vi.mocked(fetch).mockImplementation((input: RequestInfo | URL, init?: RequestInit) => {
    const path = String(input);
    if (path.endsWith('/signals/patch_apply_approved') && init?.method === 'POST') {
      setApiResponse('/api/v5/work-items/wi-1', {
        workItemId: 'wi-1', systemId: 'alpha-system', title: '登录页错误提示', lifecycleStatus: 'completed',
        currentStage: '已完成', waitingFor: '', canControl: false, availableActions: [], lastAppliedSequence: 12,
      });
      return jsonResponse({ code: 'STALE_WORK_ITEM', message: '工作项状态已变化' }, false, 409);
    }
    return fallback(input, init);
  });
  renderApp('/work-items/wi-1');

  fireEvent.click(await screen.findByRole('button', { name: '应用 Patch' }));
  fireEvent.click(within(screen.getByRole('dialog')).getByRole('button', { name: '应用 Patch' }));

  const alert = await screen.findByRole('alertdialog');
  expect(alert).toHaveTextContent('工作项已更新');
  expect(alert).toHaveTextContent('工作项状态已更新，页面已刷新，请按当前状态继续。');
  await waitFor(() => expect(screen.queryByRole('button', { name: '应用 Patch' })).not.toBeInTheDocument());
  expect(fetch).toHaveBeenCalledWith('/api/v5/work-items/wi-1/events', expect.anything());
});

test('work item detail shows the active revision and revision history', async () => {
  setApiResponse('/api/v5/work-items/wi-1', {
    workItemId: 'wi-1', systemId: 'alpha-system', title: '登录页修订', lifecycleStatus: 'activated',
    waitingFor: 'worker', canControl: true, availableActions: [],
  });
  setApiResponse('/api/v5/work-items/wi-1/events', [
    { sequence: 1, eventId: 'rev-1', eventType: 'RevisionRequested', createdAt: '2026-07-05T12:00:00Z',
      payloadJson: JSON.stringify({ revision: 1, revisionMode: 'incremental', note: '提示放到输入框下方', requestedBy: 'admin', phase: 'review' }) },
    { sequence: 2, eventType: 'CodingAttemptStarted', payloadJson: JSON.stringify({ supervisor: { role: 'developer', engine: 'claude_sdk_team' }, revision: 1 }) },
  ]);

  renderApp('/work-items/wi-1');

  expect((await screen.findAllByText('第 1 轮修订中')).length).toBeGreaterThan(0);
  expect(screen.getByRole('heading', { name: '修订历史' })).toBeInTheDocument();
  expect(screen.getByText('提示放到输入框下方')).toBeInTheDocument();
  expect(screen.getByText('增量修订')).toBeInTheDocument();
});

test('work item detail reviews a coding plan and requires feedback when rejecting it', async () => {
  setApiResponse('/api/v5/work-items/wi-1', {
    workItemId: 'wi-1', systemId: 'alpha-system', title: '登录页规划', lifecycleStatus: 'activated',
    currentStage: '等待计划审批', waitingFor: 'owner', canControl: true,
    availableActions: ['coding_plan_approved', 'coding_plan_rejected'], lastAppliedSequence: 3,
  });
  setApiResponse('/api/v5/work-items/wi-1/events', [
    { sequence: 1, eventType: 'WorkItemActivated', payloadJson: '{}' },
    { sequence: 2, eventType: 'CodingPlanStarted', payloadJson: JSON.stringify({ planRevision: 1 }) },
    { sequence: 3, eventType: 'CodingPlanProposed', payloadJson: JSON.stringify({
      planRevision: 1, summary: '只调整登录错误提示',
      tasks: [{ taskId: 'task-01', repo: 'frontend', objective: '移动提示位置', acceptanceCriteriaRefs: ['AC-1'], evidence: ['src/login.tsx:LoginForm'] }],
      risks: ['保持后端接口不变'], openQuestions: [], baseRevisions: { frontend: 'abc123' },
    }) },
  ]);

  renderApp('/work-items/wi-1');

  expect(await screen.findByRole('heading', { name: 'Coding Plan · 第 1 版' })).toBeInTheDocument();
  expect(screen.getByText('任务：task-01')).toBeInTheDocument();
  expect(screen.getByText('src/login.tsx:LoginForm')).toBeInTheDocument();
  expect(screen.getByRole('button', { name: '批准计划并执行' })).toBeInTheDocument();
  fireEvent.click(screen.getByRole('button', { name: '打回重新规划' }));
  const dialog = screen.getByRole('dialog');
  const note = within(dialog).getByLabelText('修订意见（必填）');
  expect(within(dialog).getByRole('button', { name: '打回重新规划' })).toBeDisabled();
  fireEvent.change(note, { target: { value: '不要改接口，只调整提示位置' } });
  fireEvent.click(within(dialog).getByRole('button', { name: '打回重新规划' }));

  await waitFor(() => {
    const call = vi.mocked(fetch).mock.calls.find(([path, init]) =>
      String(path).endsWith('/signals/coding_plan_rejected') && init?.method === 'POST');
    expect(JSON.parse(String(call?.[1]?.body))).toMatchObject({ note: '不要改接口，只调整提示位置' });
  });
});

test('work item detail shows repository agent completion metadata', async () => {
  renderApp('/work-items/wi-1');

  await screen.findByRole('heading', { name: '登录页错误提示' });
  fireEvent.click(screen.getByRole('button', { name: /计划与执行/ }));
  expect(await screen.findByText('Agent 阶段已完成')).toBeInTheDocument();
  expect((await screen.findAllByText('frontend')).length).toBeGreaterThan(0);
  expect((await screen.findAllByText('前端修改完成')).length).toBeGreaterThan(0);
  expect(screen.getAllByText('frontend')[0].closest('li')).toHaveTextContent('1 个文件');
});

test('work item detail translates waiting role and merge request status', async () => {
  setApiResponse('/api/v5/work-items/wi-1', {
    workItemId: 'wi-1', systemId: 'alpha-system', title: '跨仓发布', lifecycleStatus: 'waiting_merge',
    currentStage: '等待 GitLab 合并', waitingFor: 'gitlab', canControl: true, availableActions: ['check_merge_status'],
  });
  setApiResponse('/api/v5/work-items/wi-1/events', [{
    sequence: 1,
    eventId: 'evt-mr',
    eventType: 'MergeRequestCreated',
    payloadJson: JSON.stringify({ repo: 'frontend', mrIid: 12, mrUrl: 'https://gitlab.example/mr/12', state: 'opened' }),
    causationId: 'attempt-1:mr:frontend',
    createdAt: '2026-07-05T12:00:00Z',
  }]);
  renderApp('/work-items/wi-1');

  expect((await screen.findAllByText('GitLab')).length).toBeGreaterThan(0);
  expect(screen.getByRole('link', { name: 'frontend !12' })).toHaveAttribute('href', 'https://gitlab.example/mr/12');
  expect(screen.getByText('等待合并')).toBeInTheDocument();
});

test('work item detail shows completed and failed agent stages', async () => {
  setApiResponse('/api/v5/work-items/wi-1', {
    workItemId: 'wi-1', systemId: 'alpha-system', title: '跨端修改', lifecycleStatus: 'worker_blocked',
    currentStage: '执行被阻塞', canControl: true,
    availableActions: ['retry_current_phase', 'rework', 'rework_with_latest_config'],
  });
  setApiResponse('/api/v5/work-items/wi-1/events', [
    {
      sequence: 1, eventType: 'CodingAttemptStarted', payloadJson: JSON.stringify({
        architecture: 'claude_sdk_team', supervisor: { role: 'developer', engine: 'claude_sdk_team' },
      }),
    },
    {
      sequence: 2, eventType: 'AgentStageCompleted',
      payloadJson: JSON.stringify({ stageIndex: 1, role: 'frontend', summary: '前端完成' }),
    },
    {
      sequence: 3, eventType: 'WorkerBlocked',
      payloadJson: JSON.stringify({ reason: 'coding_attempt_failed' }),
    },
  ]);

  renderApp('/work-items/wi-1');

  const progress = await screen.findByRole('list', { name: 'Agent 执行进度' });
  expect(within(progress).getByText('frontend').closest('li')).toHaveTextContent('已完成');
  expect(within(progress).getByText(/developer · Supervisor/).closest('li')).toHaveTextContent('失败');
  expect(screen.getByRole('button', { name: '重试失败阶段' })).toBeInTheDocument();
  expect(screen.getByRole('button', { name: '完整重做' })).toBeInTheDocument();
  expect(screen.getByRole('button', { name: '刷新配置并重试失败阶段' })).toBeInTheDocument();
});

test('waiting merge shows each GitLab MR and verified merge action', async () => {
  setApiResponse('/api/v5/work-items/wi-1', {
    workItemId: 'wi-1', systemId: 'alpha-system', title: '跨仓发布', lifecycleStatus: 'waiting_merge',
    currentStage: '等待 GitLab 合并', canControl: true, availableActions: ['check_merge_status', 'rework'],
  });
  setApiResponse('/api/v5/work-items/wi-1/events', [
    { sequence: 1, eventType: 'MergeRequestCreated', causationId: 'patch-1:mr:frontend:1',
      payloadJson: JSON.stringify({ repo: 'frontend', mrIid: 1, mrUrl: 'https://gitlab/web/1', state: 'opened' }) },
    { sequence: 2, eventType: 'MergeRequestCreated', causationId: 'patch-1:mr:backend:2',
      payloadJson: JSON.stringify({ repo: 'backend', mrIid: 2, mrUrl: 'https://gitlab/api/2', state: 'opened' }) },
    { sequence: 3, eventType: 'MergeRequestMerged',
      payloadJson: JSON.stringify({ repo: 'frontend', mrIid: 1, mrUrl: 'https://gitlab/web/1' }) },
  ]);

  renderApp('/work-items/wi-1');

  expect(await screen.findByRole('link', { name: 'frontend !1' })).toHaveAttribute('href', 'https://gitlab/web/1');
  expect(screen.getByRole('link', { name: 'backend !2' })).toHaveAttribute('href', 'https://gitlab/api/2');
  expect(screen.getByRole('button', { name: '检查合并状态' })).toBeInTheDocument();
  expect(screen.getByText('已合并')).toBeInTheDocument();
});

test('work item detail audit tab contains all raw events without polluting the default flow', async () => {
  renderApp('/work-items/wi-1');

  const current = await screen.findByRole('button', { name: /代码确认/ });
  expect(current).toHaveAttribute('aria-current', 'step');
  expect(screen.queryByText('原始 JSON')).not.toBeInTheDocument();
  fireEvent.click(screen.getByRole('button', { name: '事件审计' }));
  expect((await screen.findAllByText('原始 JSON')).length).toBe(3);
  expect(screen.getByText('CodingAttemptStarted')).toBeInTheDocument();
});

test('work item detail shows requirement attachments and opens image preview', async () => {
  setApiResponse('/api/v5/work-items/wi-1/attachments', [{
    attachmentId: 'att-screen', systemId: 'alpha-system', filename: '登录错误.png', contentType: 'image/png', sizeBytes: 2048,
  }]);
  renderApp('/work-items/wi-1');

  expect(await screen.findByRole('heading', { name: '需求附件' })).toBeInTheDocument();
  expect(screen.getByText('1 张')).toBeInTheDocument();
  fireEvent.click(screen.getByRole('button', { name: /登录错误.png/ }));

  const preview = screen.getByRole('dialog', { name: '需求附件预览' });
  expect(preview).toHaveAttribute('open');
  expect(within(preview).getByRole('img')).toHaveAttribute('src', '/api/v5/attachments/att-screen');
});

test('terminal work item fetches final events once before polling stops', async () => {
  setApiResponse('/api/v5/work-items/wi-1', {
    workItemId: 'wi-1', systemId: 'alpha-system', title: '发布完成', lifecycleStatus: 'completed',
    currentStage: '已完成', waitingFor: '', canControl: false, availableActions: [],
  });
  const fallback = vi.mocked(fetch).getMockImplementation()!;
  let eventRequests = 0;
  vi.mocked(fetch).mockImplementation((input: RequestInfo | URL, init?: RequestInit) => {
    if (String(input) === '/api/v5/work-items/wi-1/events') {
      eventRequests += 1;
      const events = [{ sequence: 1, eventType: 'MergeRequestMerged', payloadJson: '{"repo":"frontend"}' }];
      if (eventRequests > 1) events.push({ sequence: 2, eventType: 'ReleaseCompleted', payloadJson: '{"repo":"frontend"}' });
      return jsonResponse(events);
    }
    return fallback(input, init);
  });

  renderApp('/work-items/wi-1');

  expect(await screen.findByText('发布已完成')).toBeInTheDocument();
  await waitFor(() => expect(eventRequests).toBe(2));
});

test('memory approve moves candidate out of pending tab', async () => {
  renderApp('/memory');

  expect(await screen.findByText('登录页样式约定')).toBeInTheDocument();
  expect(document.querySelector('.memory-category')).toHaveTextContent('约定');
  expect(screen.getByRole('link', { name: /来源工作项 wi-1/ })).toHaveAttribute('href', '/work-items/wi-1');
  fireEvent.click(screen.getByRole('button', { name: '编辑并批准' }));
  expect(screen.getByRole('dialog')).toHaveAttribute('open');
  expect(await screen.findByLabelText('适用阶段')).toHaveValue('both');
  fireEvent.change(screen.getByLabelText('适用阶段'), { target: { value: 'execution' } });
  fireEvent.click(await screen.findByRole('checkbox', { name: /登录页/ }));
  fireEvent.change(screen.getByLabelText('标题'), { target: { value: '登录页视觉约定' } });
  fireEvent.click(screen.getByRole('button', { name: '批准并生效' }));

  await waitFor(() => {
    expect(screen.queryByText('登录页样式约定')).not.toBeInTheDocument();
  });
  const call = vi.mocked(fetch).mock.calls.find(([path, init]) =>
    String(path).endsWith('/memory/mem-candidate/approve') && init?.method === 'POST');
  expect(JSON.parse(String(call?.[1]?.body))).toMatchObject({ audience: 'execution', targetRefs: ['page-login'] });
});

test('archived lifecycle memory does not expose raw event payload', async () => {
  renderApp('/memory');

  fireEvent.click(await screen.findByRole('button', { name: '已归档 1' }));

  expect(await screen.findByText('旧生命周期事件候选')).toBeInTheDocument();
  expect(screen.getByText(/仅保留在事件审计中/)).toBeInTheDocument();
  expect(screen.queryByText(/ModificationCompleted/)).not.toBeInTheDocument();
});

test('work item detail creates a structured memory candidate', async () => {
  renderApp('/work-items/wi-1');

  fireEvent.click(await screen.findByRole('button', { name: '沉淀为记忆' }));
  fireEvent.change(screen.getByLabelText('记忆类型'), { target: { value: 'lesson' } });
  fireEvent.change(screen.getByLabelText('标题'), { target: { value: '登录错误处理经验' } });
  fireEvent.change(screen.getByLabelText('正文'), { target: { value: '登录错误统一使用现有提示组件。' } });
  fireEvent.click(screen.getByRole('button', { name: '加入待审批' }));

  expect(await screen.findByText('已加入系统记忆待审批')).toBeInTheDocument();
});
