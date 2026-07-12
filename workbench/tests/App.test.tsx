import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { act, fireEvent, render, screen, waitFor } from '@testing-library/react';
import { createMemoryRouter, MemoryRouter, RouterProvider } from 'react-router-dom';
import { beforeEach, expect, test, vi } from 'vitest';
import { App } from '../src/App';

const responses: Record<string, unknown> = {
  '/api/v5/auth/me': { userId: 'admin', roles: ['ROLE_ADMIN'] },
  '/api/v5/systems': [
    { systemId: 'alpha-system', name: 'Alpha System', repoPath: '/tmp/alpha', ownerUserId: 'admin', allowedPaths: '[]', forbiddenPaths: '[]', testCommands: '[]', agentConfig: '{"executionProvider":"claude_sdk","claudeMaxTurns":40,"executionTimeoutSeconds":900}', modelProviderConfig: '{"provider":"deepseek","model":"deepseek-chat","baseUrl":"https://api.deepseek.com","apiKey":"******","claudePreset":"deepseek","claudeModel":"deepseek-v4-pro","claudeBaseUrl":"https://api.deepseek.com/anthropic","claudeReuseBusinessApiKey":true}' },
    { systemId: 'prod-system', name: 'Prod System', repoPath: '/tmp/prod', ownerUserId: 'owner', allowedPaths: '[]', forbiddenPaths: '[]', testCommands: '[]', agentConfig: '{}', modelProviderConfig: '{}' },
  ],
  '/api/v5/users': [{ userId: 'admin', displayName: 'Admin', email: 'admin@local', enabled: true }],
  '/api/v5/systems/alpha-system/members': [{ systemId: 'alpha-system', userId: 'admin', displayName: 'Admin', role: 'owner' }],
  '/api/v5/work-items?systemId=alpha-system': [],
  '/api/v5/work-items?systemId=prod-system': [],
  '/api/v5/context-snapshots?systemId=alpha-system': {
    systemId: 'alpha-system',
    manifestId: null,
    approvedMemories: [{ memoryId: 'mem-1', content: '保留登录页样式' }],
  },
  '/api/v5/work-items/wi-1': {
    workItemId: 'wi-1',
    systemId: 'alpha-system',
    prdId: 'prd-1',
    caseId: 'case-1',
    title: '登录页错误提示',
    lifecycleStatus: 'modification_completed',
    approvalStatus: 'approved',
    executionAllowed: true,
    currentStage: '等待确认应用 patch',
    waitingFor: 'owner',
    ownerUserId: 'admin',
    canControl: true,
    availableActions: ['patch_apply_approved', 'patch_apply_rejected', 'cancel_case'],
  },
  '/api/v5/work-items/wi-1/events': [
    {
      sequence: 1,
      eventId: 'evt-plan',
      eventType: 'ExecutionPlanDrafted',
      payloadJson: JSON.stringify({
        contextManifestId: 'manifest-1',
        plan: {
          steps: ['按验收标准修改: 错误密码时显示提示'],
          target_files: ['src/login.tsx'],
          test_plan: ['npm test'],
          risks: ['登录态回归'],
        },
      }),
      createdAt: '2026-07-05T12:00:00Z',
      actorId: 'worker',
    },
    {
      sequence: 2,
      eventId: 'evt-stage',
      eventType: 'AgentStageCompleted',
      payloadJson: JSON.stringify({
        role: 'frontend', engine: 'claude_sdk', summary: '前端修改完成',
        changedPaths: ['src/login.tsx'], tokenUsage: { input_tokens: 100, output_tokens: 20 },
      }),
      createdAt: '2026-07-05T12:00:30Z',
      actorId: 'worker',
    },
    {
      sequence: 3,
      eventId: 'evt-modification',
      eventType: 'ModificationCompleted',
      payloadJson: JSON.stringify({
        executionProvider: 'claude_sdk',
        turns: 4,
        tokenUsage: { input_tokens: 320, output_tokens: 80 },
        diffPatch: 'diff --git a/src/login.tsx b/src/login.tsx\n+显示登录错误\n',
      }),
      createdAt: '2026-07-05T12:01:00Z',
      actorId: 'worker',
    },
  ],
};
const routerFuture = { v7_startTransition: true, v7_relativeSplatPath: true };
let candidateMemories: unknown[] = [];
let conversationMessages: unknown[] = [];
let workItems: unknown[] = [];
let agentConfiguration: any;
let prdPostCount = 0;

function jsonResponse(data: unknown, ok = true) {
  return Promise.resolve({
    ok,
    status: ok ? 200 : 401,
    json: () => Promise.resolve(data),
    text: () => Promise.resolve(typeof data === 'string' ? data : JSON.stringify(data)),
  } as Response);
}

beforeEach(() => {
  localStorage.clear();
  candidateMemories = [{ memoryId: 'mem-candidate', systemId: 'alpha-system', content: '保留登录页样式', status: 'candidate', createdAt: '2026-07-05T12:00:00Z' }];
  conversationMessages = [];
  workItems = [];
  agentConfiguration = {
    modelProfiles: [{ id: 'mp-1', name: 'Claude 主模型', provider: 'anthropic', model: 'claude-sonnet', baseUrl: '', apiKeySet: true }],
    agentRoles: [{ id: 'role-1', name: '前端 Agent', engine: 'claude_sdk', modelProfileRef: 'mp-1', pathScope: ['web'], prompt: '只改前端', maxTurns: 40, timeoutSeconds: 900 }],
    defaultRoleId: 'role-1',
    engines: ['claude_sdk', 'deepagents', 'http', 'fake'],
  };
  prdPostCount = 0;
  // 测试只关心前端请求路径，不启动真实控制面。
  vi.stubGlobal('fetch', vi.fn((path: string, init?: RequestInit) => {
    if (path.startsWith('/api/v5/work-items?')) return jsonResponse(workItems);
    if (path === '/api/v5/systems/alpha-system/agent-config' && !init?.method) return jsonResponse(agentConfiguration);
    if (path === '/api/v5/systems/alpha-system/model-profiles' && init?.method === 'POST') {
      const body = JSON.parse(String(init.body));
      agentConfiguration = { ...agentConfiguration, modelProfiles: [...agentConfiguration.modelProfiles, { ...body, id: 'mp-2', apiKeySet: Boolean(body.apiKey), apiKey: undefined }] };
      return jsonResponse(agentConfiguration);
    }
    if (path === '/api/v5/systems/alpha-system/agent-roles' && init?.method === 'POST') {
      const body = JSON.parse(String(init.body));
      agentConfiguration = { ...agentConfiguration, agentRoles: [...agentConfiguration.agentRoles, { ...body, id: 'role-2' }] };
      return jsonResponse(agentConfiguration);
    }
    if (path === '/api/v5/systems/alpha-system/default-agent-role' && init?.method === 'PATCH') {
      agentConfiguration = { ...agentConfiguration, defaultRoleId: JSON.parse(String(init.body)).roleId };
      return jsonResponse(agentConfiguration);
    }
    if (path.endsWith('/readiness')) return jsonResponse({ ready: true, stages: [], issues: [], effectiveExecutionProvider: 'claude_sdk' });
    if (path.startsWith('/api/v5/prd-sessions?')) return jsonResponse([]);
    if (path === '/api/v5/prd-sessions/prd-1') {
      return jsonResponse({
        prdId: 'prd-1', systemId: 'alpha-system', conversationId: 'conv-prd-1', status: prdPostCount === 1 ? 'need_clarification' : 'waiting_user_confirm',
        createdBy: 'admin', draft: { title: '登录页错误提示', goal: '改登录页', acceptanceCriteria: ['错误密码时提示'] }, missingFields: prdPostCount === 1 ? ['acceptanceCriteria'] : [],
      });
    }
    if (path === '/api/v5/memory?systemId=alpha-system&status=candidate') {
      return jsonResponse(candidateMemories);
    }
    if (path === '/api/v5/memory?systemId=alpha-system&status=approved') {
      return jsonResponse([]);
    }
    if (path === '/api/v5/memory/mem-candidate/approve' && init?.method === 'POST') {
      candidateMemories = [];
      return jsonResponse({ memoryId: 'mem-candidate', status: 'approved' });
    }
    if (path === '/api/v5/systems/alpha-system/prd/messages' && init?.method === 'POST') {
      prdPostCount += 1;
      conversationMessages = prdPostCount === 1
        ? [
          { messageId: 'm1', conversationId: 'conv-prd-1', senderType: 'user', content: '用户第一轮' },
          { messageId: 'm2', conversationId: 'conv-prd-1', senderType: 'assistant', content: '助手第一轮' },
        ]
        : [
          { messageId: 'm1', conversationId: 'conv-prd-1', senderType: 'user', content: '用户第一轮' },
          { messageId: 'm2', conversationId: 'conv-prd-1', senderType: 'assistant', content: '助手第一轮' },
          { messageId: 'm3', conversationId: 'conv-prd-1', senderType: 'user', content: '用户第二轮' },
          { messageId: 'm4', conversationId: 'conv-prd-1', senderType: 'assistant', content: '助手第二轮' },
        ];
      return jsonResponse({
        prdId: 'prd-1',
        conversationId: 'conv-prd-1',
        status: prdPostCount === 1 ? 'need_clarification' : 'waiting_user_confirm',
        missingFields: prdPostCount === 1 ? ['acceptanceCriteria'] : [],
        draft: { title: '登录页错误提示', goal: '改登录页', acceptanceCriteria: ['错误密码时提示'] },
      });
    }
    if (path.startsWith('/api/v5/systems/alpha-system/') && init?.method === 'PATCH') {
      return jsonResponse((responses['/api/v5/systems'] as unknown[])[0]);
    }
    if (path === '/api/v5/conversations/conv-prd-1') {
      return jsonResponse(conversationMessages);
    }
    return jsonResponse(responses[path]);
  }));
});

function renderApp(path: string) {
  render(
    <QueryClientProvider client={new QueryClient({ defaultOptions: { queries: { retry: false } } })}>
      <MemoryRouter initialEntries={[path]} future={routerFuture}>
        <App />
      </MemoryRouter>
    </QueryClientProvider>,
  );
}

function renderAppWithRouter(path: string) {
  const router = createMemoryRouter([{ path: '*', element: <App /> }], { initialEntries: [path] });
  render(
    <QueryClientProvider client={new QueryClient({ defaultOptions: { queries: { retry: false } } })}>
      <RouterProvider router={router} future={{ v7_startTransition: true }} />
    </QueryClientProvider>,
  );
  return router;
}

test('renders workbench navigation after auth check', async () => {
  renderApp('/work-items');

  expect(await screen.findByText('agent-team v5')).toBeInTheDocument();
  expect(await screen.findByText('工作项中心')).toBeInTheDocument();
  expect(await screen.findByLabelText('当前工作系统')).toBeInTheDocument();
  expect(document.querySelector('.sidebar [aria-label="当前工作系统"]')).not.toBeInTheDocument();
  expect(document.querySelectorAll('.sidebar nav svg')).toHaveLength(5);
  expect(document.querySelectorAll('.page-tabs svg')).toHaveLength(2);
});

test.each(['/work-items/drafts', '/systems', '/agents', '/memory', '/users'])('shows global system context on %s', async (path) => {
  renderApp(path);

  expect(await screen.findByLabelText('当前工作系统')).toBeInTheDocument();
});

test('system configuration uses Chinese field labels', async () => {
  renderApp('/systems');

  expect(await screen.findByLabelText('系统编号')).toBeInTheDocument();
  expect(screen.getByLabelText('代码仓库绝对路径')).toBeInTheDocument();
  expect(screen.getByLabelText('系统负责人')).toBeInTheDocument();
  expect(screen.getByLabelText(/允许修改路径/)).toBeInTheDocument();
  expect(screen.getByLabelText(/禁止修改路径/)).toBeInTheDocument();
  expect(screen.getByLabelText(/测试命令/)).toBeInTheDocument();
});

test('shows login page when auth check fails', async () => {
  vi.mocked(fetch).mockImplementationOnce(() => jsonResponse('unauthorized', false));

  renderApp('/work-items');

  expect(await screen.findByRole('heading', { name: '登录' })).toBeInTheDocument();
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
  workItems = [{
    workItemId: 'WI202607114827', title: '优化工作项列表', lifecycleStatus: 'activated', approvalStatus: 'approved',
    executionAllowed: true, updatedAt: '2026-07-11T03:00:00Z', createdBy: 'admin', canControl: false, availableActions: [],
  }];

  renderApp('/work-items');

  expect(await screen.findByRole('columnheader', { name: '工作项 ID' })).toBeInTheDocument();
  expect(screen.getByRole('columnheader', { name: '标题' })).toBeInTheDocument();
  expect(screen.getByRole('columnheader', { name: '创建人' })).toBeInTheDocument();
  expect(await screen.findByRole('link', { name: 'WI202607114827' })).toHaveAttribute('href', '/work-items/WI202607114827');
  expect(await screen.findByText('优化工作项列表')).toBeInTheDocument();
  expect(screen.getByRole('cell', { name: 'admin' })).toBeInTheDocument();
});

test('work item filters and second page survive detail return', async () => {
  workItems = Array.from({ length: 21 }, (_, index) => ({
    workItemId: index === 20 ? 'wi-1' : `wi-${index + 2}`,
    title: index === 20 ? '登录页错误提示' : `工作项 ${index + 1}`,
    lifecycleStatus: 'completed', approvalStatus: 'approved', executionAllowed: false,
    updatedAt: '2026-07-11T03:00:00Z', createdBy: 'admin', canControl: false, availableActions: [],
  }));
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
  workItems = [{
    workItemId: 'wi-1', title: '登录页错误提示', lifecycleStatus: 'completed', approvalStatus: 'approved',
    executionAllowed: false, updatedAt: '2026-07-11T03:00:00Z', createdBy: 'admin', canControl: false, availableActions: [],
  }];
  const router = renderAppWithRouter('/work-items');

  fireEvent.change(await screen.findByLabelText('搜索工作项'), { target: { value: '登录' } });
  await waitFor(() => expect(router.state.location.state).toMatchObject({ workItemList: { q: '登录' } }));
  fireEvent.click(await screen.findByRole('link', { name: 'wi-1' }));
  expect(await screen.findByText('ExecutionPlanDrafted')).toBeInTheDocument();

  await act(async () => { await router.navigate(-1); });
  expect(await screen.findByLabelText('搜索工作项')).toHaveValue('登录');
});

test('work item detail shows drafted execution plan from event timeline', async () => {
  renderApp('/work-items/wi-1');

  expect(await screen.findByText('登录页错误提示')).toBeInTheDocument();
  expect(screen.queryByLabelText('当前工作系统')).not.toBeInTheDocument();
  expect(await screen.findByText('alpha-system')).toBeInTheDocument();
  expect(await screen.findByText('ExecutionPlanDrafted')).toBeInTheDocument();
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

test('agent config shows three layer model and masked key state', async () => {
  renderApp('/agents');

  expect(await screen.findByRole('heading', { name: '三层关系' })).toBeInTheDocument();
  expect(screen.getByRole('heading', { name: 'Model Profiles' })).toBeInTheDocument();
  expect(screen.getByRole('heading', { name: 'Agent Roles' })).toBeInTheDocument();
  expect((await screen.findAllByText('Claude 主模型')).length).toBeGreaterThan(0);
  expect(screen.getByText('已配置')).toBeInTheDocument();
  expect(screen.getAllByText('前端 Agent').length).toBeGreaterThan(0);
  expect(screen.getByLabelText('默认角色')).toHaveValue('role-1');
});

test('model profile can be added without ever rendering its key', async () => {
  renderApp('/agents');
  await screen.findAllByText('Claude 主模型');
  fireEvent.change(screen.getByLabelText('Profile 名称'), { target: { value: 'OpenAI 兼容模型' } });
  fireEvent.change(screen.getByLabelText('模型名称'), { target: { value: 'gpt-4.1' } });
  fireEvent.change(screen.getByLabelText('API Key'), { target: { value: 'new-secret' } });
  fireEvent.click(screen.getByRole('button', { name: '添加 Profile' }));
  expect((await screen.findAllByText('OpenAI 兼容模型')).length).toBeGreaterThan(0);
  expect(screen.queryByText('new-secret')).not.toBeInTheDocument();
  expect(fetch).toHaveBeenCalledWith('/api/v5/systems/alpha-system/model-profiles', expect.objectContaining({ method: 'POST' }));
});

test('agent role can select deepagents profile and path scope', async () => {
  renderApp('/agents');
  await screen.findAllByText('前端 Agent');
  fireEvent.change(screen.getByLabelText('角色名称'), { target: { value: '后端 Agent' } });
  fireEvent.change(screen.getByLabelText('执行内核'), { target: { value: 'deepagents' } });
  fireEvent.change(screen.getByLabelText('Model Profile'), { target: { value: 'mp-1' } });
  fireEvent.change(screen.getByLabelText(/Path Scope/), { target: { value: 'api\ndb' } });
  fireEvent.click(screen.getByRole('button', { name: '添加角色' }));

  expect((await screen.findAllByText('后端 Agent')).length).toBeGreaterThan(0);
  const call = vi.mocked(fetch).mock.calls.find(([path]) => path === '/api/v5/systems/alpha-system/agent-roles');
  expect(JSON.parse(String(call?.[1]?.body)).pathScope).toEqual(['api', 'db']);
});

test('work item detail shows execution provider and token summary', async () => {
  renderApp('/work-items/wi-1');

  expect(await screen.findByText('ModificationCompleted')).toBeInTheDocument();
  expect((await screen.findAllByText('claude_sdk')).length).toBeGreaterThan(0);
  expect(await screen.findByText('输入 320 / 输出 80')).toBeInTheDocument();
  expect(await screen.findByText('代码 diff')).toBeInTheDocument();
  expect(await screen.findByText(/diff --git a\/src\/login.tsx/)).toBeInTheDocument();
});

test('work item detail shows agent stage handoff metadata', async () => {
  renderApp('/work-items/wi-1');

  expect(await screen.findByText('AgentStageCompleted')).toBeInTheDocument();
  expect(await screen.findByText('frontend')).toBeInTheDocument();
  expect(await screen.findByText('前端修改完成')).toBeInTheDocument();
  expect((await screen.findAllByText('src/login.tsx')).length).toBeGreaterThan(0);
});

test('memory approve moves candidate out of pending tab', async () => {
  renderApp('/memory');

  expect(await screen.findByText(/保留登录页样式/)).toBeInTheDocument();
  fireEvent.click(screen.getByRole('button', { name: '批准' }));

  await waitFor(() => {
    expect(screen.queryByText(/保留登录页样式/)).not.toBeInTheDocument();
  });
});

test('new prd page renders two chat turns as four bubbles', async () => {
  renderApp('/work-items/new');

  expect(await screen.findByRole('heading', { name: '创建工作项' })).toBeInTheDocument();
  expect(screen.queryByRole('navigation', { name: '工作项中心' })).not.toBeInTheDocument();
  expect(screen.queryByLabelText('当前工作系统')).not.toBeInTheDocument();
  expect(screen.getByRole('heading', { name: '工作项预览' })).toBeInTheDocument();
  expect(screen.getByText('待输入')).toBeInTheDocument();
  expect(screen.getByRole('link', { name: '返回工作项中心' })).toHaveAttribute('href', '/work-items');
  const input = await screen.findByLabelText('需求描述');
  expect(await screen.findByLabelText('所属系统')).toHaveTextContent('Alpha System');
  fireEvent.change(input, { target: { value: '用户第一轮' } });
  fireEvent.click(screen.getByRole('button', { name: '发送' }));
  expect(await screen.findByText('助手第一轮')).toBeInTheDocument();
  expect(await screen.findByRole('heading', { name: '继续创建工作项' })).toBeInTheDocument();
  expect(screen.getByRole('button', { name: '确认并生成工作项' })).toBeInTheDocument();
  const savedUnload = new Event('beforeunload', { cancelable: true });
  window.dispatchEvent(savedUnload);
  expect(savedUnload.defaultPrevented).toBe(false);

  fireEvent.change(input, { target: { value: '用户第二轮' } });
  fireEvent.click(screen.getByRole('button', { name: '发送' }));

  expect(await screen.findByText('用户第一轮')).toBeInTheDocument();
  expect(await screen.findByText('助手第一轮')).toBeInTheDocument();
  expect(await screen.findByText('用户第二轮')).toBeInTheDocument();
  expect(await screen.findByText('助手第二轮')).toBeInTheDocument();
});

test('warns before leaving an unsaved work item description', async () => {
  const confirm = vi.spyOn(window, 'confirm').mockReturnValue(false);
  renderApp('/work-items/new');

  fireEvent.change(await screen.findByLabelText('需求描述'), { target: { value: '尚未保存的需求' } });
  const unload = new Event('beforeunload', { cancelable: true });
  window.dispatchEvent(unload);
  expect(unload.defaultPrevented).toBe(true);

  fireEvent.click(screen.getByRole('link', { name: '返回工作项中心' }));
  expect(confirm).toHaveBeenCalledWith('内容尚未保存，是否离开？');
  expect(screen.getByRole('heading', { name: '创建工作项' })).toBeInTheDocument();
  confirm.mockRestore();
});

test('prd draft list shows creator display name and account', async () => {
  const fallback = vi.mocked(fetch).getMockImplementation()!;
  vi.mocked(fetch).mockImplementation((input: RequestInfo | URL, init?: RequestInit) => {
    const path = String(input);
    if (path.startsWith('/api/v5/prd-sessions?')) {
      return jsonResponse([{
        prdId: 'prd-history-1',
        systemId: 'alpha-system',
        conversationId: 'conversation-history-1',
        title: '历史 PRD',
        status: 'waiting_user_confirm',
        createdBy: 'admin',
        creatorDisplayName: 'Admin',
        draft: {},
        missingFields: [],
        updatedAt: '2026-07-11T00:00:00Z',
      }]);
    }
    return fallback(input, init);
  });

  renderApp('/work-items/drafts');

  expect(await screen.findByText('Admin (admin)')).toBeInTheDocument();
  expect(await screen.findByText('待确认')).toBeInTheDocument();
});

test('prd draft list defaults to resumable sessions and can show all records', async () => {
  const fallback = vi.mocked(fetch).getMockImplementation()!;
  vi.mocked(fetch).mockImplementation((input: RequestInfo | URL, init?: RequestInit) => {
    const path = String(input);
    if (path.startsWith('/api/v5/prd-sessions?')) {
      return jsonResponse([
        { prdId: 'prd-pending', systemId: 'alpha-system', conversationId: 'conv-pending', title: '待完善需求', status: 'need_clarification', createdBy: 'admin', draft: {}, missingFields: [] },
        { prdId: 'prd-finished', systemId: 'alpha-system', conversationId: 'conv-finished', workItemId: 'wi-finished', title: '已生成需求', status: 'confirmed', createdBy: 'admin', draft: {}, missingFields: [] },
      ]);
    }
    return fallback(input, init);
  });

  renderApp('/work-items/drafts');

  expect(await screen.findByText('待完善需求')).toBeInTheDocument();
  expect(screen.getByRole('link', { name: '继续完善' })).toHaveAttribute('href', '/work-items/new/prd-pending');
  expect(screen.queryByText('已生成需求')).not.toBeInTheDocument();
  fireEvent.click(screen.getByRole('button', { name: '全部记录' }));
  expect(await screen.findByText('已生成需求')).toBeInTheDocument();
  expect(screen.getByRole('link', { name: '查看工作项' })).toHaveAttribute('href', '/work-items/wi-finished');
});

test('draft editor restores session in the independent creation workspace', async () => {
  const fallback = vi.mocked(fetch).getMockImplementation()!;
  vi.mocked(fetch).mockImplementation((input: RequestInfo | URL, init?: RequestInit) => {
    const path = String(input);
    if (path === '/api/v5/prd-sessions/prd-resume') {
      return jsonResponse({
        prdId: 'prd-resume', systemId: 'prod-system', conversationId: 'conv-resume', title: '恢复的需求',
        status: 'need_clarification', createdBy: 'admin', draft: { title: '恢复的需求', goal: '继续完善' }, missingFields: ['acceptanceCriteria'],
      });
    }
    if (path === '/api/v5/conversations/conv-resume') {
      return jsonResponse([{ messageId: 'resume-message', conversationId: 'conv-resume', senderType: 'assistant', content: '请补充验收标准' }]);
    }
    return fallback(input, init);
  });

  renderApp('/work-items/new/prd-resume');

  expect(await screen.findByRole('heading', { name: '继续创建工作项' })).toBeInTheDocument();
  expect(screen.queryByRole('heading', { name: '工作项中心' })).not.toBeInTheDocument();
  expect(screen.getByRole('heading', { name: '工作项预览' })).toBeInTheDocument();
  expect(screen.getByRole('link', { name: '返回需求草稿' })).toHaveAttribute('href', '/work-items/drafts');
  expect(await screen.findByText('请补充验收标准')).toBeInTheDocument();
  expect(screen.getByLabelText('所属系统')).toBeDisabled();
  expect(screen.getByLabelText('所属系统')).toHaveTextContent('Prod System');
  await waitFor(() => expect(localStorage.getItem('agent-team-v5-system')).toBe('prod-system'));
  expect(screen.queryByRole('heading', { name: '需求记录' })).not.toBeInTheDocument();
});

test('legacy draft editor route opens the independent creation workspace', async () => {
  const fallback = vi.mocked(fetch).getMockImplementation()!;
  vi.mocked(fetch).mockImplementation((input: RequestInfo | URL, init?: RequestInit) => {
    const path = String(input);
    if (path === '/api/v5/prd-sessions/prd-legacy') {
      return jsonResponse({ prdId: 'prd-legacy', systemId: 'alpha-system', conversationId: 'conv-legacy', status: 'need_clarification', createdBy: 'admin', draft: {}, missingFields: [] });
    }
    if (path === '/api/v5/conversations/conv-legacy') return jsonResponse([]);
    return fallback(input, init);
  });

  renderApp('/work-items/drafts/prd-legacy');

  expect(await screen.findByRole('heading', { name: '继续创建工作项' })).toBeInTheDocument();
  expect(screen.queryByRole('navigation', { name: '工作项中心' })).not.toBeInTheDocument();
});
