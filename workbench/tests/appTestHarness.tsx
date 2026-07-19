import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { render } from '@testing-library/react';
import { createMemoryRouter, RouterProvider } from 'react-router-dom';
import { vi } from 'vitest';
import { App } from '../src/App';

const responses: Record<string, unknown> = {
  '/api/v5/auth/me': { userId: 'admin', roles: ['ROLE_ADMIN'] },
  '/api/v5/systems': [
    { systemId: 'alpha-system', name: 'Alpha System', repoPath: '/tmp/alpha', ownerUserId: 'admin', allowedPaths: '[]', forbiddenPaths: '[]', testCommands: '[]', agentConfig: '{}', modelProviderConfig: '{}' },
    { systemId: 'prod-system', name: 'Prod System', repoPath: '/tmp/prod', ownerUserId: 'owner', allowedPaths: '[]', forbiddenPaths: '[]', testCommands: '[]', agentConfig: '{}', modelProviderConfig: '{}' },
  ],
  '/api/v5/users': [
    { userId: 'admin', displayName: 'Admin', email: 'admin@local', enabled: true },
    { userId: 'demo-user', displayName: 'Demo User', email: 'demo@local', enabled: true },
  ],
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
      eventId: 'evt-attempt',
      eventType: 'CodingAttemptStarted',
      payloadJson: JSON.stringify({
        contextManifestId: 'manifest-1',
        architecture: 'claude_sdk_team',
        supervisor: { role: 'developer', engine: 'claude_sdk_team' },
      }),
      createdAt: '2026-07-05T12:00:00Z',
      actorId: 'worker',
    },
    {
      sequence: 2,
      eventId: 'evt-stage',
      eventType: 'AgentStageCompleted',
      payloadJson: JSON.stringify({
        stageIndex: 1, role: 'frontend', repo: 'main', engine: 'claude_sdk_team', summary: '前端修改完成',
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
        executionProvider: 'claude_sdk_team',
        turns: 4,
        tokenUsage: { input_tokens: 320, output_tokens: 80 },
        diffPatch: 'diff --git a/src/login.tsx b/src/login.tsx\n+显示登录错误\n',
      }),
      createdAt: '2026-07-05T12:01:00Z',
      actorId: 'worker',
    },
  ],
};
let candidateMemories: unknown[] = [];
let conversationMessages: unknown[] = [];
let workItems: unknown[] = [];
let agentConfiguration: any;
let gitConfiguration: any;
let prdPostCount = 0;
let responseOverrides: Record<string, unknown> = {};

export function jsonResponse(data: unknown, ok = true, status = ok ? 200 : 401) {
  return Promise.resolve({
    ok,
    status,
    json: () => Promise.resolve(data),
    text: () => Promise.resolve(typeof data === 'string' ? data : JSON.stringify(data)),
  } as Response);
}

export function resetAppTestState() {
  localStorage.clear();
  candidateMemories = [{ memoryId: 'mem-candidate', systemId: 'alpha-system', category: 'convention', title: '登录页样式约定', content: '保留登录页样式', status: 'candidate', workItemId: 'wi-1', createdAt: '2026-07-05T12:00:00Z' }];
  conversationMessages = [];
  workItems = [];
  agentConfiguration = {
    modelProfiles: [{ id: 'mp-1', name: 'Claude 主模型', provider: 'anthropic', model: 'claude-sonnet', baseUrl: '', apiKeySet: true }],
    agents: [
      { name: 'product', kind: 'builtin', engine: '', modelProfileRef: 'mp-1', pathScope: [], prompt: '' },
      { name: 'developer', kind: 'builtin', engine: 'claude_sdk_team', modelProfileRef: 'mp-1', pathScope: [], prompt: '', maxTurns: 40, timeoutSeconds: 900 },
    ],
    maxRevisions: 5,
    engines: ['claude_sdk_team', 'fake'],
    migration: { migrated: false, from: [] },
  };
  gitConfiguration = {
    repos: [{ repoId: 'main', name: 'Alpha Web', kind: 'frontend', gitlabProject: 'alpha/web',
      defaultBranch: 'main', cloneMode: 'local', localPath: '/tmp/alpha', allowedPaths: ['src'],
      forbiddenPaths: ['secrets'], testCommands: ['npm test'] }],
    releaseMode: 'local', validationMode: 'auto', mrTargetBranch: '', mrLabels: [],
    gitlabBaseUrl: '', effectiveGitlabBaseUrl: 'http://gitlab.internal', tokenSet: true, usingGlobalToken: true,
  };
  prdPostCount = 0;
  responseOverrides = {};
  // 测试只关心前端请求路径，不启动真实控制面。
  vi.stubGlobal('fetch', vi.fn((path: string, init?: RequestInit) => {
    if (path.startsWith('/api/v5/work-items?')) return jsonResponse(workItems);
    if (path.startsWith('/api/v5/work-items/') && init?.method === 'DELETE') {
      const workItemId = decodeURIComponent(path.split('/').pop() || '');
      workItems = workItems.filter((item: any) => item.workItemId !== workItemId);
      return jsonResponse(undefined);
    }
    if (path === '/api/v5/systems/alpha-system/agent-config' && !init?.method) return jsonResponse(agentConfiguration);
    if (path === '/api/v5/systems/alpha-system/git-config' && !init?.method) return jsonResponse(gitConfiguration);
    if (path === '/api/v5/systems/alpha-system/git-config' && init?.method === 'PUT') {
      const body = JSON.parse(String(init.body));
      gitConfiguration = { ...gitConfiguration, ...body, gitlabToken: undefined, tokenSet: true };
      return jsonResponse(gitConfiguration);
    }
    if (path === '/api/v5/systems/alpha-system/model-profiles' && init?.method === 'POST') {
      const body = JSON.parse(String(init.body));
      agentConfiguration = { ...agentConfiguration, modelProfiles: [...agentConfiguration.modelProfiles, { ...body, id: 'mp-2', apiKeySet: Boolean(body.apiKey), apiKey: undefined }] };
      return jsonResponse(agentConfiguration);
    }
    if (path.startsWith('/api/v5/systems/alpha-system/model-profiles/') && init?.method === 'DELETE') {
      const id = decodeURIComponent(path.split('/').pop() || '');
      agentConfiguration = { ...agentConfiguration, modelProfiles: agentConfiguration.modelProfiles.filter((item: any) => item.id !== id) };
      return jsonResponse(agentConfiguration);
    }
    if (path.startsWith('/api/v5/systems/alpha-system/agents/') && init?.method === 'PATCH') {
      const body = JSON.parse(String(init.body));
      const name = decodeURIComponent(path.split('/').pop() || '');
      agentConfiguration = { ...agentConfiguration, agents: agentConfiguration.agents.map((item: any) => item.name === name ? { ...item, ...body, name } : item) };
      return jsonResponse(agentConfiguration);
    }
    if (path === '/api/v5/systems/alpha-system/agent-config/settings' && init?.method === 'PATCH') {
      const body = JSON.parse(String(init.body));
      agentConfiguration = { ...agentConfiguration, ...body };
      return jsonResponse(agentConfiguration);
    }
    if (path.endsWith('/readiness')) return jsonResponse({ ready: true, stages: [], issues: [], effectiveExecutionProvider: 'claude_sdk_team' });
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
    if (path === '/api/v5/memory?systemId=alpha-system&status=rejected') {
      return jsonResponse([{ memoryId: 'mem-legacy', systemId: 'alpha-system', category: '', title: 'ModificationCompleted {\"diff\":\"raw\"}', content: 'ModificationCompleted {\"diff\":\"raw\"}', status: 'rejected', sourceEventId: 'evt-1', createdAt: '2026-07-04T12:00:00Z' }]);
    }
    if (path === '/api/v5/memory?systemId=alpha-system&status=disabled') {
      return jsonResponse([]);
    }
    if (path === '/api/v5/memory/candidates' && init?.method === 'POST') {
      const body = JSON.parse(String(init.body));
      candidateMemories = [...candidateMemories, { ...body, memoryId: 'mem-new', status: 'candidate', createdAt: '2026-07-05T13:00:00Z' }];
      return jsonResponse(candidateMemories[candidateMemories.length - 1]);
    }
    if (path.startsWith('/api/v5/systems/alpha-system/knowledge/page?') && !init?.method) {
      return jsonResponse(Object.prototype.hasOwnProperty.call(responseOverrides, path) ? responseOverrides[path] : {
        items: [], total: 0, page: 1, pageSize: 10, totalPages: 1,
      });
    }
    if (path.startsWith('/api/v5/systems/alpha-system/knowledge?') && !init?.method) {
      return jsonResponse([]);
    }
    if (path === '/api/v5/memory/mem-candidate/approve' && init?.method === 'POST') {
      const body = JSON.parse(String(init.body));
      candidateMemories = [];
      return jsonResponse({ memoryId: 'mem-candidate', ...body, status: 'approved' });
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
    return jsonResponse(Object.prototype.hasOwnProperty.call(responseOverrides, path) ? responseOverrides[path] : responses[path]);
  }));
}

export function setWorkItems(value: unknown[]) {
  workItems = value;
}

export function setApiResponse(path: string, value: unknown) {
  responseOverrides[path] = value;
}

export function renderApp(path: string) {
  return renderAppWithRouter(path);
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
