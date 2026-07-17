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
  expect(fetch).toHaveBeenCalledWith(firstPage, expect.anything());
  fireEvent.click(screen.getByRole('button', { name: '下一页' }));

  expect(await screen.findByText('第二页知识')).toBeInTheDocument();
  expect(fetch).toHaveBeenCalledWith(secondPage, expect.anything());
  expect(vi.mocked(fetch).mock.calls.some(([path]) => String(path).startsWith('/api/v5/systems/alpha-system/knowledge?'))).toBe(false);
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
    executionAllowed: true, updatedAt: '2026-07-11T03:00:00Z', createdBy: 'admin', canControl: false, availableActions: [],
  }]);

  renderApp('/work-items');

  expect(await screen.findByRole('columnheader', { name: '工作项 ID' })).toBeInTheDocument();
  expect(screen.getByRole('columnheader', { name: '标题' })).toBeInTheDocument();
  expect(screen.getByRole('columnheader', { name: '创建人' })).toBeInTheDocument();
  expect(await screen.findByRole('link', { name: 'WI202607114827' })).toHaveAttribute('href', '/work-items/WI202607114827');
  expect(await screen.findByText('优化工作项列表')).toBeInTheDocument();
  expect(screen.getByRole('cell', { name: 'admin' })).toBeInTheDocument();
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
  expect(await screen.findByText('执行计划已生成')).toBeInTheDocument();

  await act(async () => { await router.navigate(-1); });
  expect(await screen.findByLabelText('搜索工作项')).toHaveValue('登录');
});

test('work item detail shows drafted execution plan from event timeline', async () => {
  renderApp('/work-items/wi-1');

  expect(await screen.findByText('登录页错误提示')).toBeInTheDocument();
  expect(screen.queryByLabelText('当前工作系统')).not.toBeInTheDocument();
  expect(await screen.findByText('alpha-system')).toBeInTheDocument();
  expect(await screen.findByText('执行计划已生成')).toBeInTheDocument();
  expect(await screen.findByText('执行步骤')).toBeInTheDocument();
  expect(await screen.findByText(/错误密码时显示提示/)).toBeInTheDocument();
  expect(await screen.findByText('目标文件')).toBeInTheDocument();
  expect((await screen.findAllByText('src/login.tsx')).length).toBeGreaterThan(0);
  expect(await screen.findByText('测试计划')).toBeInTheDocument();
  expect(await screen.findByText('npm test')).toBeInTheDocument();
  expect(await screen.findByText('风险')).toBeInTheDocument();
  expect(await screen.findByText('登录态回归')).toBeInTheDocument();
  expect(screen.queryByText(/"steps"/)).not.toBeInTheDocument();
});

test('work item detail shows execution provider and token summary', async () => {
  renderApp('/work-items/wi-1');

  expect(await screen.findByText('修改已完成')).toBeInTheDocument();
  expect((await screen.findAllByText('claude_sdk')).length).toBeGreaterThan(0);
  expect(await screen.findByText('输入 320 / 输出 80')).toBeInTheDocument();
  expect(await screen.findByText('代码 diff')).toBeInTheDocument();
  expect(await screen.findByText(/diff --git a\/src\/login.tsx/)).toBeInTheDocument();
});

test('work item detail shows agent stage handoff metadata', async () => {
  renderApp('/work-items/wi-1');

  expect(await screen.findByText('Agent 阶段已完成')).toBeInTheDocument();
  expect(await screen.findByText('frontend')).toBeInTheDocument();
  expect(await screen.findByText('前端修改完成')).toBeInTheDocument();
  expect((await screen.findAllByText('src/login.tsx')).length).toBeGreaterThan(0);
});

test('work item detail translates waiting role and merge request status', async () => {
  setApiResponse('/api/v5/work-items/wi-1/events', [{
    sequence: 1,
    eventId: 'evt-mr',
    eventType: 'MergeRequestCreated',
    payloadJson: JSON.stringify({ repo: 'frontend', mrIid: 12, mrUrl: 'https://gitlab.example/mr/12', state: 'opened' }),
    causationId: 'attempt-1:mr:frontend',
    createdAt: '2026-07-05T12:00:00Z',
  }]);
  renderApp('/work-items/wi-1');

  expect(await screen.findByText('系统负责人')).toBeInTheDocument();
  expect(await screen.findByRole('heading', { name: 'GitLab 合并请求' })).toBeInTheDocument();
  expect(screen.getByText('已打开')).toBeInTheDocument();
});

test('work item detail shows completed and failed agent stages', async () => {
  setApiResponse('/api/v5/work-items/wi-1', {
    workItemId: 'wi-1', systemId: 'alpha-system', title: '跨端修改', lifecycleStatus: 'worker_blocked',
    currentStage: '执行被阻塞', canControl: true, availableActions: ['rework'],
  });
  setApiResponse('/api/v5/work-items/wi-1/events', [
    {
      sequence: 1, eventType: 'ExecutionPlanDrafted', payloadJson: JSON.stringify({
        plan: { steps: ['前端', '后端'], assignments: [{ role: 'frontend' }, { role: 'backend' }] },
      }),
    },
    {
      sequence: 2, eventType: 'AgentStageCompleted',
      payloadJson: JSON.stringify({ stageIndex: 0, role: 'frontend', summary: '前端完成' }),
    },
    {
      sequence: 3, eventType: 'WorkerBlocked',
      payloadJson: JSON.stringify({ reason: 'execution_failed', failed_stage: { index: 1, role: 'backend' } }),
    },
  ]);

  renderApp('/work-items/wi-1');

  const progress = await screen.findByRole('list', { name: 'Agent Stage 进度' });
  const stages = within(progress).getAllByRole('listitem');
  expect(stages[0]).toHaveTextContent('frontend');
  expect(stages[0]).toHaveTextContent('完成');
  expect(stages[1]).toHaveTextContent('backend');
  expect(stages[1]).toHaveTextContent('失败');
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
  expect(screen.getByRole('button', { name: '标记已合并' })).toBeInTheDocument();
  expect(screen.getByText('已合并')).toBeInTheDocument();
});

test('memory approve moves candidate out of pending tab', async () => {
  renderApp('/memory');

  expect(await screen.findByText('登录页样式约定')).toBeInTheDocument();
  expect(document.querySelector('.memory-category')).toHaveTextContent('约定');
  expect(screen.getByRole('link', { name: /来源工作项 wi-1/ })).toHaveAttribute('href', '/work-items/wi-1');
  fireEvent.click(screen.getByRole('button', { name: '编辑并批准' }));
  expect(screen.getByRole('dialog')).toHaveAttribute('open');
  fireEvent.change(screen.getByLabelText('标题'), { target: { value: '登录页视觉约定' } });
  fireEvent.click(screen.getByRole('button', { name: '批准并生效' }));

  await waitFor(() => {
    expect(screen.queryByText('登录页样式约定')).not.toBeInTheDocument();
  });
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
