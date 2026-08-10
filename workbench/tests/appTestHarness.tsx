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
    lastAppliedSequence: 3,
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
        changedPaths: ['src/features/authentication/components/LoginErrorMessageWithResponsiveLayout.tsx'], tokenUsage: { input_tokens: 100, output_tokens: 20 },
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
        sessionId: 'session-internal',
        token: 'token-internal',
        idempotencyKey: 'transition-internal',
        artifactRef: {
          artifactId: 'art-code-1', artifactType: 'CODING', version: 1, contentHash: 'code-hash',
          rootArtifactId: 'art-product-1', parentArtifactId: 'art-plan-2', status: 'PROPOSED',
        },
      }),
      createdAt: '2026-07-05T12:01:00Z',
      actorId: 'worker',
    },
  ],
  '/api/v5/work-items/wi-1/attachments': [],
  '/api/v5/work-items/wi-1/artifacts': {
    rootArtifactId: 'art-product-1',
    nodes: [
      {
        ref: {
          artifactId: 'art-product-1', artifactType: 'PRODUCT', version: 1, contentHash: 'product-hash',
          rootArtifactId: 'art-product-1', status: 'APPROVED',
        },
        systemId: 'alpha-system', prdId: 'prd-1', workItemId: 'wi-1', caseId: 'case-1',
        content: { title: '登录页错误提示', goal: '改登录页', acceptanceCriteria: ['错误密码时提示'] },
        createdBy: 'admin', createdAt: '2026-07-05T11:00:00Z',
        reviewedBy: 'admin', reviewedAt: '2026-07-05T11:00:00Z',
      },
      {
        ref: {
          artifactId: 'art-plan-1', artifactType: 'PLANNING', version: 1, contentHash: 'plan-hash-1',
          rootArtifactId: 'art-product-1', parentArtifactId: 'art-product-1', status: 'REJECTED',
        },
        systemId: 'alpha-system', prdId: 'prd-1', workItemId: 'wi-1', caseId: 'case-1',
        content: { planMarkdown: '# 旧执行计划', baseRevisions: { main: 'abc123' } },
        createdBy: 'worker', createdAt: '2026-07-05T11:20:00Z',
        reviewedBy: 'admin', reviewedAt: '2026-07-05T11:25:00Z', reviewNote: '补充验证步骤',
      },
      {
        ref: {
          artifactId: 'art-plan-2', artifactType: 'PLANNING', version: 2, contentHash: 'plan-hash-2',
          rootArtifactId: 'art-product-1', parentArtifactId: 'art-product-1',
          supersedesArtifactId: 'art-plan-1', status: 'APPROVED',
        },
        systemId: 'alpha-system', prdId: 'prd-1', workItemId: 'wi-1', caseId: 'case-1',
        content: { planMarkdown: '# 执行计划', baseRevisions: { main: 'abc123' } },
        createdBy: 'worker', createdAt: '2026-07-05T11:30:00Z',
        reviewedBy: 'admin', reviewedAt: '2026-07-05T11:35:00Z',
      },
      {
        ref: {
          artifactId: 'art-code-1', artifactType: 'CODING', version: 1, contentHash: 'code-hash',
          rootArtifactId: 'art-product-1', parentArtifactId: 'art-plan-2', status: 'PROPOSED',
        },
        systemId: 'alpha-system', prdId: 'prd-1', workItemId: 'wi-1', caseId: 'case-1',
        content: {
          summary: '登录提示已完成',
          repoChanges: [{
            repo: 'main',
            diffPatch: 'diff --git a/src/login.tsx b/src/login.tsx\n+显示登录错误\n',
            changedPaths: ['src/features/authentication/components/LoginErrorMessageWithResponsiveLayout.tsx'],
            summary: '调整登录错误提示',
          }],
          executionOutcome: { status: 'completed', blockers: [] },
          baseRevisions: { main: 'abc123' },
        },
        createdBy: 'worker', createdAt: '2026-07-05T12:01:00Z',
      },
    ],
    edges: [
      { fromArtifactId: 'art-product-1', toArtifactId: 'art-plan-1', edgeType: 'DERIVED_FROM' },
      { fromArtifactId: 'art-product-1', toArtifactId: 'art-plan-2', edgeType: 'DERIVED_FROM' },
      { fromArtifactId: 'art-plan-1', toArtifactId: 'art-plan-2', edgeType: 'SUPERSEDES' },
      { fromArtifactId: 'art-plan-2', toArtifactId: 'art-code-1', edgeType: 'DERIVED_FROM' },
    ],
    effectiveHeads: {
      PRODUCT: {
        artifactId: 'art-product-1', artifactType: 'PRODUCT', version: 1, contentHash: 'product-hash',
        rootArtifactId: 'art-product-1', status: 'APPROVED',
      },
      PLANNING: {
        artifactId: 'art-plan-2', artifactType: 'PLANNING', version: 2, contentHash: 'plan-hash-2',
        rootArtifactId: 'art-product-1', parentArtifactId: 'art-product-1',
        supersedesArtifactId: 'art-plan-1', status: 'APPROVED',
      },
      CODING: {
        artifactId: 'art-code-1', artifactType: 'CODING', version: 1, contentHash: 'code-hash',
        rootArtifactId: 'art-product-1', parentArtifactId: 'art-plan-2', status: 'PROPOSED',
      },
    },
    versionActions: {
      'art-product-1': {
        canSelect: false,
        selectDisabledReason: 'Planning 已开始，切换 Product 需要显式回退并重建执行上下文',
        canContinue: false,
        continueDisabledReason: '只有当前执行计划可以继续开发',
      },
      'art-plan-1': {
        canSelect: false,
        selectDisabledReason: 'Coding 已开始，切换 Planning 必须先显式回退并重新执行',
        canContinue: false,
        continueDisabledReason: '请先切换到该执行计划',
      },
      'art-plan-2': {
        canSelect: false,
        selectDisabledReason: '该版本已是当前有效版本',
        canContinue: false,
        continueDisabledReason: 'Coding 已开始，不能重复启动；如需换计划请走显式回退',
      },
      'art-code-1': {
        canSelect: false,
        selectDisabledReason: '该版本已是当前有效版本',
        canContinue: false,
        continueDisabledReason: '只有当前执行计划可以继续开发',
      },
    },
  },
  '/api/v5/artifacts/art-product-1': {
    artifact: {
      ref: {
        artifactId: 'art-product-1', artifactType: 'PRODUCT', version: 1, contentHash: 'product-hash',
        rootArtifactId: 'art-product-1', status: 'APPROVED',
      },
      systemId: 'alpha-system', prdId: 'prd-1', workItemId: 'wi-1', caseId: 'case-1',
      content: { title: '登录页错误提示', goal: '改登录页', acceptanceCriteria: ['错误密码时提示'] },
      createdBy: 'admin', createdAt: '2026-07-05T11:00:00Z',
    },
    transitions: [{
      transitionId: 'transition-product-1', fromStatus: null, toStatus: 'APPROVED', actor: 'admin',
      note: 'PRD 已确认', domainEventId: 'evt-product-1', createdAt: '2026-07-05T11:00:00Z',
    }],
    evidence: [],
  },
  '/api/v5/artifacts/art-plan-1': {
    artifact: {
      ref: {
        artifactId: 'art-plan-1', artifactType: 'PLANNING', version: 1, contentHash: 'plan-hash-1',
        rootArtifactId: 'art-product-1', parentArtifactId: 'art-product-1', status: 'REJECTED',
      },
      systemId: 'alpha-system', prdId: 'prd-1', workItemId: 'wi-1', caseId: 'case-1',
      content: { planMarkdown: '# 旧执行计划', baseRevisions: { main: 'abc123' } },
      createdBy: 'worker', createdAt: '2026-07-05T11:20:00Z',
    },
    transitions: [{
      transitionId: 'transition-plan-reject', fromStatus: 'PROPOSED', toStatus: 'REJECTED', actor: 'admin',
      note: '补充验证步骤', domainEventId: 'evt-plan-reject', createdAt: '2026-07-05T11:25:00Z',
    }],
    evidence: [],
  },
  '/api/v5/artifacts/art-plan-2': {
    artifact: {
      ref: {
        artifactId: 'art-plan-2', artifactType: 'PLANNING', version: 2, contentHash: 'plan-hash-2',
        rootArtifactId: 'art-product-1', parentArtifactId: 'art-product-1',
        supersedesArtifactId: 'art-plan-1', status: 'APPROVED',
      },
      systemId: 'alpha-system', prdId: 'prd-1', workItemId: 'wi-1', caseId: 'case-1',
      content: { planMarkdown: '# 执行计划', baseRevisions: { main: 'abc123' } },
      createdBy: 'worker', createdAt: '2026-07-05T11:30:00Z',
    },
    transitions: [{
      transitionId: 'transition-plan-approve', fromStatus: 'PROPOSED', toStatus: 'APPROVED', actor: 'admin',
      note: '', domainEventId: 'evt-plan-approve', createdAt: '2026-07-05T11:35:00Z',
    }],
    evidence: [],
  },
  '/api/v5/artifacts/art-code-1': {
    artifact: {
      ref: {
        artifactId: 'art-code-1', artifactType: 'CODING', version: 1, contentHash: 'code-hash',
        rootArtifactId: 'art-product-1', parentArtifactId: 'art-plan-2', status: 'PROPOSED',
      },
      systemId: 'alpha-system', prdId: 'prd-1', workItemId: 'wi-1', caseId: 'case-1',
      content: {
        summary: '登录提示已完成',
        repoChanges: [{
          repo: 'main', diffPatch: 'diff --git a/src/login.tsx b/src/login.tsx\n+显示登录错误\n',
          changedPaths: ['src/features/authentication/components/LoginErrorMessageWithResponsiveLayout.tsx'], summary: '调整登录错误提示',
        }],
        executionOutcome: { status: 'completed', blockers: [] },
        baseRevisions: { main: 'abc123' },
      },
      createdBy: 'worker', createdAt: '2026-07-05T12:01:00Z',
    },
    transitions: [{
      transitionId: 'transition-code-1', fromStatus: null, toStatus: 'PROPOSED', actor: 'worker',
      note: '', domainEventId: 'evt-modification', createdAt: '2026-07-05T12:01:00Z',
    }],
    evidence: [{
      evidenceId: 'evidence-code-execution-1', evidenceType: 'CodingExecution',
      payload: {
        sessionId: 'hidden-session', tokenUsage: { inputTokens: 100 }, turns: 3,
        executionProvider: 'claude_sdk_team', subagentRuns: [{ agentId: 'hidden-agent' }],
      },
      transitionId: 'transition-code-1', domainEventId: 'evt-modification', actor: 'worker',
      createdAt: '2026-07-05T12:01:00Z',
    }, {
      evidenceId: 'evidence-patch-1', evidenceType: 'PatchApplied',
      payload: { changedPaths: ['src/login.tsx'] },
      domainEventId: 'evt-patch', actor: 'worker', createdAt: '2026-07-05T12:02:00Z',
    }],
  },
};
let candidateMemories: unknown[] = [];
let activeMemories: unknown[] = [];
let conversationMessages: unknown[] = [];
let latestProductExecution: unknown = null;
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
  candidateMemories = [{
    candidateId: 'candidate-1',
    systemId: 'alpha-system',
    projectScope: 'alpha-system',
    memoryType: 'DECISION',
    artifactSourceId: 'art-plan-2',
    artifactSource: {
      artifactId: 'art-plan-2',
      artifactType: 'PLANNING',
      version: 2,
      status: 'APPROVED',
      workItemId: 'wi-1',
      prdId: 'prd-1',
      rootArtifactId: 'art-product-1',
    },
    sourceKind: 'ARTIFACT_APPROVED',
    targetRefs: [],
    evidenceRefs: ['evt-1'],
    title: '登录页样式决策',
    content: '已批准的技术路线：保留登录页现有样式组件。',
    confidence: 0.84,
    applicability: 'PROJECT',
    status: 'PENDING',
    createdAt: '2026-07-05T12:00:00Z',
  }];
  activeMemories = [];
  conversationMessages = [];
  latestProductExecution = null;
  workItems = [];
  agentConfiguration = {
    modelProfiles: [{ id: 'mp-1', name: 'Claude 主模型', provider: 'anthropic', model: 'claude-sonnet',
      baseUrl: '', apiKeySet: true, imageInput: true, supportsVision: true, structuredOutput: 'json_object' }],
    agents: [
      { name: 'product', kind: 'builtin', engine: '', modelProfileRef: 'mp-1', pathScope: [], prompt: '' },
      { name: 'vision', kind: 'builtin', engine: '', modelProfileRef: 'mp-1', pathScope: [], prompt: '' },
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
    if (path === '/api/v5/memory/candidates?systemId=alpha-system&status=PENDING') {
      return jsonResponse(candidateMemories);
    }
    if (path === '/api/v5/memory/candidates?systemId=alpha-system&status=REJECTED'
      || path === '/api/v5/memory/candidates?systemId=alpha-system&status=OUTDATED') {
      return jsonResponse([]);
    }
    if (path === '/api/v5/memory?systemId=alpha-system&status=ACTIVE') {
      return jsonResponse(activeMemories);
    }
    if (path === '/api/v5/memory?systemId=alpha-system&status=OUTDATED') {
      return jsonResponse([]);
    }
    if (path === '/api/v5/memory?systemId=alpha-system&status=ARCHIVED') {
      return jsonResponse([{
        memoryId: 'mem-legacy',
        systemId: 'alpha-system',
        projectScope: 'alpha-system',
        memoryType: 'EXPERIENCE',
        artifactSourceId: null,
        artifactSource: null,
        title: '旧记忆已归档',
        content: '旧系统记忆缺少 Artifact 来源，升级后仅保留审计记录。',
        confidence: 0,
        applicability: 'PROJECT',
        status: 'ARCHIVED',
        targetRefs: [],
        evidenceRefs: ['evt-legacy'],
        createdAt: '2026-07-04T12:00:00Z',
      }]);
    }
    if (path.startsWith('/api/v5/systems/alpha-system/knowledge/page?') && !init?.method) {
      return jsonResponse(Object.prototype.hasOwnProperty.call(responseOverrides, path) ? responseOverrides[path] : {
        items: [], total: 0, page: 1, pageSize: 10, totalPages: 1,
      });
    }
    if (path.startsWith('/api/v5/systems/alpha-system/knowledge?') && !init?.method) {
      return jsonResponse([{
        entryId: 'page-login', systemId: 'alpha-system', repo: 'frontend', kind: 'page', title: '登录页',
        anchorTexts: ['登录'], routePath: '/login', apiEndpoints: ['POST /api/login'], codeRefs: [],
        status: 'approved', source: 'manual', sourceRef: 'page-login',
      }]);
    }
    if (path === '/api/v5/memory/candidates/candidate-1/approve' && init?.method === 'POST') {
      const body = JSON.parse(String(init.body));
      candidateMemories = [];
      const memory = {
        memoryId: 'mem-1',
        candidateId: 'candidate-1',
        systemId: 'alpha-system',
        projectScope: 'alpha-system',
        artifactSourceId: 'art-plan-2',
        artifactSource: {
          artifactId: 'art-plan-2',
          artifactType: 'PLANNING',
          version: 2,
          status: 'APPROVED',
          workItemId: 'wi-1',
          prdId: 'prd-1',
          rootArtifactId: 'art-product-1',
        },
        ...body,
        status: 'ACTIVE',
        evidenceRefs: ['evt-1'],
        createdAt: '2026-07-05T12:00:00Z',
      };
      activeMemories = [memory];
      return jsonResponse(memory);
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
      latestProductExecution = {
        executionId: `prd-exec-${prdPostCount}`, prdId: 'prd-1', status: 'COMPLETED',
        workflowId: `product-agent-prd-exec-${prdPostCount}`, inputMessageId: `m${prdPostCount * 2 - 1}`,
        contextBundleId: `bundle-${prdPostCount}`, stage: 'COMPLETED', attempt: 1,
      };
      return jsonResponse({
        executionId: `prd-exec-${prdPostCount}`,
        prdId: 'prd-1',
        conversationId: 'conv-prd-1',
        status: 'CREATED',
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
      return jsonResponse({ messages: conversationMessages, activeExecution: null, latestExecution: latestProductExecution });
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
