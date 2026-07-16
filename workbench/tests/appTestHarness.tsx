import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { render } from '@testing-library/react';
import { createMemoryRouter, MemoryRouter, RouterProvider } from 'react-router-dom';
import { vi } from 'vitest';
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

export function jsonResponse(data: unknown, ok = true) {
  return Promise.resolve({
    ok,
    status: ok ? 200 : 401,
    json: () => Promise.resolve(data),
    text: () => Promise.resolve(typeof data === 'string' ? data : JSON.stringify(data)),
  } as Response);
}

export function resetAppTestState() {
  localStorage.clear();
  candidateMemories = [{ memoryId: 'mem-candidate', systemId: 'alpha-system', content: '保留登录页样式', status: 'candidate', createdAt: '2026-07-05T12:00:00Z' }];
  conversationMessages = [];
  workItems = [];
  agentConfiguration = {
    modelProfiles: [{ id: 'mp-1', name: 'Claude 主模型', provider: 'anthropic', model: 'claude-sonnet', baseUrl: '', apiKeySet: true }],
    agents: [
      { name: 'product', kind: 'builtin', engine: '', modelProfileRef: 'mp-1', pathScope: [], prompt: '' },
      { name: 'planner', kind: 'builtin', engine: '', modelProfileRef: 'mp-1', pathScope: [], prompt: '' },
      { name: 'developer', kind: 'builtin', engine: 'claude_sdk', modelProfileRef: 'mp-1', pathScope: [], prompt: '', maxTurns: 40, timeoutSeconds: 900 },
      { name: 'frontend-dev', kind: 'custom', engine: 'claude_sdk', modelProfileRef: 'mp-1', pathScope: ['web'], prompt: '只改前端', maxTurns: 40, timeoutSeconds: 900 },
    ],
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
    if (path === '/api/v5/systems/alpha-system/agents' && init?.method === 'POST') {
      const body = JSON.parse(String(init.body));
      agentConfiguration = { ...agentConfiguration, agents: [...agentConfiguration.agents, { ...body, kind: 'custom' }] };
      return jsonResponse(agentConfiguration);
    }
    if (path.startsWith('/api/v5/systems/alpha-system/agents/') && init?.method === 'PATCH') {
      const body = JSON.parse(String(init.body));
      const name = decodeURIComponent(path.split('/').pop() || '');
      agentConfiguration = { ...agentConfiguration, agents: agentConfiguration.agents.map((item: any) => item.name === name ? { ...item, ...body, name } : item) };
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
        assistantPending: true,
        status: prdPostCount === 1 ? 'need_clarification' : 'waiting_user_confirm',
        missingFields: prdPostCount === 1 ? ['acceptanceCriteria'] : [],
        draft: { title: '登录页错误提示', goal: '改登录页', acceptanceCriteria: ['错误密码时提示'] },
      });
    }
    if (path === '/api/v5/prd-sessions/prd-1/draft' && init?.method === 'PATCH') {
      const body = JSON.parse(String(init.body));
      return jsonResponse({
        ...body,
        draft: body,
        missingFields: [],
        status: 'waiting_user_confirm',
      });
    }
    if (path === '/api/v5/prd-sessions/prd-1/targets/confirm' && init?.method === 'POST') {
      const { entryIds, accepted } = JSON.parse(String(init.body));
      const target = { entryId: entryIds[0], kind: 'page', title: '登录页', routePath: '/login', apiEndpoints: ['POST /api/login'], confidence: 0.9 };
      return jsonResponse({ draft: accepted ? { suspectedTargets: [target], targets: [target] } : { suspectedTargets: [] } });
    }
    if (path.startsWith('/api/v5/systems/alpha-system/') && init?.method === 'PATCH') {
      return jsonResponse((responses['/api/v5/systems'] as unknown[])[0]);
    }
    if (path === '/api/v5/conversations/conv-prd-1') {
      return jsonResponse({ messages: conversationMessages, pendingAssistant: false });
    }
    return jsonResponse(responses[path]);
  }));
}

export function setWorkItems(value: unknown[]) {
  workItems = value;
}

export function renderApp(path: string) {
  render(
    <QueryClientProvider client={new QueryClient({ defaultOptions: { queries: { retry: false } } })}>
      <MemoryRouter initialEntries={[path]} future={routerFuture}>
        <App />
      </MemoryRouter>
    </QueryClientProvider>,
  );
}

export function renderAppWithRouter(path: string) {
  const router = createMemoryRouter([{ path: '*', element: <App /> }], { initialEntries: [path] });
  render(
    <QueryClientProvider client={new QueryClient({ defaultOptions: { queries: { retry: false } } })}>
      <RouterProvider router={router} future={{ v7_startTransition: true }} />
    </QueryClientProvider>,
  );
  return router;
}
