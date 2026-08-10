import { act, fireEvent, screen, waitFor, within } from '@testing-library/react';
import { beforeEach, expect, test, vi } from 'vitest';
import { jsonResponse, renderApp, renderAppWithRouter, resetAppTestState, setApiResponse, setWorkItems } from './appTestHarness';

beforeEach(resetAppTestState);

const codingScenarioRefs = {
  product: {
    artifactId: 'scenario-product', artifactType: 'PRODUCT', version: 1, contentHash: 'scenario-product-hash',
    rootArtifactId: 'scenario-product', status: 'APPROVED',
  },
  planningV1: {
    artifactId: 'scenario-plan-1', artifactType: 'PLANNING', version: 1, contentHash: 'scenario-plan-1-hash',
    rootArtifactId: 'scenario-product', parentArtifactId: 'scenario-product', status: 'REJECTED',
  },
  planningV2: {
    artifactId: 'scenario-plan-2', artifactType: 'PLANNING', version: 2, contentHash: 'scenario-plan-2-hash',
    rootArtifactId: 'scenario-product', parentArtifactId: 'scenario-product',
    supersedesArtifactId: 'scenario-plan-1', status: 'APPROVED',
  },
  codingV1: {
    artifactId: 'scenario-code-1', artifactType: 'CODING', version: 1, contentHash: 'scenario-code-1-hash',
    rootArtifactId: 'scenario-product', parentArtifactId: 'scenario-plan-2', status: 'PROPOSED',
  },
  codingV2: {
    artifactId: 'scenario-code-2', artifactType: 'CODING', version: 2, contentHash: 'scenario-code-2-hash',
    rootArtifactId: 'scenario-product', parentArtifactId: 'scenario-plan-2',
    supersedesArtifactId: 'scenario-code-1', status: 'PROPOSED',
  },
};

function codingScenarioGraph(codingHead: 'v1' | 'v2' | null, planningHead: 'v1' | 'v2' = 'v2') {
  const codingV1 = {
    ...codingScenarioRefs.codingV1,
    status: codingHead === 'v1' ? 'APPROVED' : 'SUPERSEDED',
  };
  const codingV2 = {
    ...codingScenarioRefs.codingV2,
    status: codingHead === 'v2' ? 'APPROVED' : codingHead === 'v1' ? 'SUPERSEDED' : 'PROPOSED',
  };
  const nodes = [
    {
      ref: codingScenarioRefs.product, systemId: 'alpha-system', prdId: 'prd-1', workItemId: 'wi-1', caseId: 'case-1',
      content: { title: '场景需求' }, createdBy: 'admin', createdAt: '2026-07-05T11:00:00Z',
    },
    {
      ref: codingScenarioRefs.planningV1, systemId: 'alpha-system', prdId: 'prd-1', workItemId: 'wi-1', caseId: 'case-1',
      content: { planMarkdown: '# 旧计划' }, createdBy: 'worker', createdAt: '2026-07-05T11:20:00Z',
    },
    {
      ref: codingScenarioRefs.planningV2, systemId: 'alpha-system', prdId: 'prd-1', workItemId: 'wi-1', caseId: 'case-1',
      content: { planMarkdown: '# 当前计划' }, createdBy: 'worker', createdAt: '2026-07-05T11:30:00Z',
    },
    {
      ref: codingV1, systemId: 'alpha-system', prdId: 'prd-1', workItemId: 'wi-1', caseId: 'case-1',
      content: {
        summary: 'v1代码摘要',
        repoChanges: [{ repo: 'main', changedPaths: ['src/v1.ts'], diffPatch: 'diff --git a/src/v1.ts b/src/v1.ts\n+v1\n', summary: 'v1文件' }],
      }, createdBy: 'worker', createdAt: '2026-07-05T12:01:00Z',
    },
    {
      ref: codingV2, systemId: 'alpha-system', prdId: 'prd-1', workItemId: 'wi-1', caseId: 'case-1',
      content: {
        summary: 'v2代码摘要',
        repoChanges: [{ repo: 'main', changedPaths: ['src/v2.ts'], diffPatch: 'diff --git a/src/v2.ts b/src/v2.ts\n+v2\n', summary: 'v2文件' }],
      }, createdBy: 'worker', createdAt: '2026-07-05T12:02:00Z',
    },
  ];
  const effectiveHeads: Record<string, unknown> = {
    PRODUCT: codingScenarioRefs.product,
    PLANNING: planningHead === 'v1' ? codingScenarioRefs.planningV1 : codingScenarioRefs.planningV2,
  };
  if (codingHead) effectiveHeads.CODING = codingHead === 'v1' ? codingV1 : codingV2;
  const selectedCodingId = codingHead === 'v1'
    ? codingScenarioRefs.codingV1.artifactId
    : codingHead === 'v2' ? codingScenarioRefs.codingV2.artifactId : '';
  return {
    rootArtifactId: 'scenario-product', nodes,
    edges: [
      { fromArtifactId: 'scenario-product', toArtifactId: 'scenario-plan-1', edgeType: 'DERIVED_FROM' },
      { fromArtifactId: 'scenario-product', toArtifactId: 'scenario-plan-2', edgeType: 'DERIVED_FROM' },
      { fromArtifactId: 'scenario-plan-1', toArtifactId: 'scenario-plan-2', edgeType: 'SUPERSEDES' },
      { fromArtifactId: 'scenario-plan-2', toArtifactId: 'scenario-code-1', edgeType: 'DERIVED_FROM' },
      { fromArtifactId: 'scenario-plan-2', toArtifactId: 'scenario-code-2', edgeType: 'DERIVED_FROM' },
      { fromArtifactId: 'scenario-code-1', toArtifactId: 'scenario-code-2', edgeType: 'SUPERSEDES' },
    ],
    effectiveHeads,
    versionActions: Object.fromEntries(nodes.map((node) => {
      const codingHistorySelectable = node.ref.artifactType === 'CODING'
        && node.ref.artifactId !== selectedCodingId;
      return [node.ref.artifactId, {
        canSelect: codingHistorySelectable,
        selectDisabledReason: codingHistorySelectable ? ''
          : node.ref.artifactType === 'PLANNING'
            ? 'Coding 已开始，切换 Planning 必须先显式回退并重新执行'
            : '该版本已是当前有效版本',
        canContinue: false,
        continueDisabledReason: 'Coding 已开始，不能重复启动；如需换计划请走显式回退',
      }];
    })),
  };
}

function codingScenarioEvents() {
  return [
    {
      sequence: 816, eventId: 'scenario-code-v1', eventType: 'ModificationCompleted',
      payloadJson: JSON.stringify({ revision: 0, artifactRef: codingScenarioRefs.codingV1 }), createdAt: '2026-07-05T12:01:00Z',
    },
    {
      sequence: 822, eventId: 'scenario-revision-1', eventType: 'RevisionRequested',
      payloadJson: JSON.stringify({ revision: 1, revisionMode: 'incremental', note: 'v2修订意见', requestedBy: 'admin', phase: 'review' }),
      createdAt: '2026-07-05T12:01:30Z',
    },
    {
      sequence: 824, eventId: 'scenario-code-v2', eventType: 'ModificationCompleted',
      payloadJson: JSON.stringify({ revision: 1, revisionMode: 'incremental', artifactRef: codingScenarioRefs.codingV2 }),
      createdAt: '2026-07-05T12:02:00Z',
    },
  ];
}

const resultArtifactRefs = {
  product: {
    artifactId: 'result-product', artifactType: 'PRODUCT', version: 1, contentHash: 'result-product-hash',
    rootArtifactId: 'result-product', status: 'APPROVED',
  },
  planning: {
    artifactId: 'result-plan', artifactType: 'PLANNING', version: 1, contentHash: 'result-plan-hash',
    rootArtifactId: 'result-product', parentArtifactId: 'result-product', status: 'APPROVED',
  },
  coding: {
    artifactId: 'result-code', artifactType: 'CODING', version: 1, contentHash: 'result-code-hash',
    rootArtifactId: 'result-product', parentArtifactId: 'result-plan', status: 'APPROVED',
  },
  validation: {
    artifactId: 'result-validation', artifactType: 'VALIDATION', version: 2, contentHash: 'result-validation-hash',
    rootArtifactId: 'result-product', parentArtifactId: 'result-code', status: 'APPROVED',
  },
  release: {
    artifactId: 'result-release', artifactType: 'RELEASE', version: 1, contentHash: 'result-release-hash',
    rootArtifactId: 'result-product', parentArtifactId: 'result-validation', status: 'APPROVED',
  },
};

function resultArtifactGraph(validationResult = 'PASSED', includeRelease = true) {
  const nodes = [
    { ref: resultArtifactRefs.product, content: { title: '结果产物测试', goal: '验证交付链' } },
    { ref: resultArtifactRefs.planning, content: { planMarkdown: '# 结果产物计划' } },
    { ref: resultArtifactRefs.coding, content: { summary: '代码实现完成', repoChanges: [] } },
    { ref: resultArtifactRefs.validation, content: {
      validationRunId: 'validation-run-2', mode: 'AUTO', result: validationResult,
      commands: [{ repo: 'main', command: 'npm test', exitCode: validationResult === 'PASSED' ? 0 : 1 }],
      errorSummary: validationResult === 'PASSED' ? '' : '1 项测试失败',
      manualEvidence: 'CI 记录 #42', codingContentHash: 'result-code-hash',
    } },
    ...(includeRelease ? [{ ref: resultArtifactRefs.release, content: {
      releaseId: 'release-batch-1', releaseMode: 'local', targetKey: 'production',
      repositories: [{
        repo: 'main', branch: 'wi/result', commitHash: 'abc123', finalState: 'completed',
        changedPaths: ['src/login.tsx'],
      }],
    } }] : []),
  ].map((artifact) => ({
    ...artifact, systemId: 'alpha-system', prdId: 'prd-1', workItemId: 'wi-1', caseId: 'case-1',
    createdBy: 'worker', createdAt: '2026-08-05T12:01:00Z',
  }));
  return {
    rootArtifactId: 'result-product', nodes,
    edges: [
      { fromArtifactId: 'result-product', toArtifactId: 'result-plan', edgeType: 'DERIVED_FROM' },
      { fromArtifactId: 'result-plan', toArtifactId: 'result-code', edgeType: 'DERIVED_FROM' },
      { fromArtifactId: 'result-code', toArtifactId: 'result-validation', edgeType: 'DERIVED_FROM' },
      ...(includeRelease ? [{
        fromArtifactId: 'result-validation', toArtifactId: 'result-release', edgeType: 'DERIVED_FROM',
      }] : []),
    ],
    effectiveHeads: {
      PRODUCT: resultArtifactRefs.product,
      PLANNING: resultArtifactRefs.planning,
      CODING: resultArtifactRefs.coding,
      VALIDATION: resultArtifactRefs.validation,
      ...(includeRelease ? { RELEASE: resultArtifactRefs.release } : {}),
    },
  };
}

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
  { path: '/models', control: '测试 Claude 主模型' },
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
  const fullFlow = screen.getByText('查看完整执行流程').closest('details');
  expect(fullFlow).not.toHaveAttribute('open');
  expect(screen.getByRole('heading', { name: '代码确认' })).toBeInTheDocument();
  expect(screen.getByLabelText('当前阶段操作')).toBeInTheDocument();
  fireEvent.click(screen.getByText('查看完整执行流程'));
  fireEvent.click(screen.getByRole('button', { name: /计划审批与代码开发/ }));
  const agentDetail = screen.getByText('查看 Agent 执行详情').closest('details');
  expect(agentDetail).not.toHaveAttribute('open');
  fireEvent.click(screen.getByText('查看 Agent 执行详情'));
  const progress = await screen.findByRole('list', { name: 'Agent 执行进度' });
  expect(within(progress).getByText(/developer · Supervisor/)).toBeInTheDocument();
  expect(within(progress).getByText('frontend')).toBeInTheDocument();
  expect(within(progress).getByText('前端修改完成')).toBeInTheDocument();
});

test('work item polling keeps the stage selected by the user', async () => {
  renderApp('/work-items/wi-1');

  await screen.findByRole('heading', { name: '代码确认' });
  fireEvent.click(screen.getByText('查看完整执行流程'));
  fireEvent.click(screen.getByRole('button', { name: /计划审批与代码开发/ }));
  expect(screen.getByRole('heading', { name: '计划审批与代码开发' })).toBeInTheDocument();
  setApiResponse('/api/v5/work-items/wi-1', {
    workItemId: 'wi-1', systemId: 'alpha-system', title: '登录页错误提示', lifecycleStatus: 'validation_passed',
    currentStage: '等待上线确认', waitingFor: 'owner', canControl: true, availableActions: ['release_approved'],
  });

  await waitFor(() => expect(screen.getByRole('button', { name: /^验证/ })).toHaveAttribute('aria-current', 'step'), { timeout: 4500 });
  expect(screen.getByRole('heading', { name: '计划审批与代码开发' })).toBeInTheDocument();
}, 7000);

test('code confirmation presents the agent summary and system collected file count', async () => {
  setApiResponse('/api/v5/work-items/wi-1/events', [{
    sequence: 1,
    eventId: 'evt-modification',
    eventType: 'ModificationCompleted',
    payloadJson: JSON.stringify({
      summary: '完成数据库字段迁移和课程页面适配',
      executionProvider: 'claude_sdk_team',
      tokenUsage: { input_tokens: 320, output_tokens: 80 },
      repoDiffs: [
        { repo: 'backend', diffPatch: 'diff --git a/V23.sql b/V23.sql\n+alter table course;' },
        { repo: 'frontend', diffPatch: 'diff --git a/Course.tsx b/Course.tsx\n+export const Course = {};' },
      ],
    }),
    createdAt: '2026-07-05T12:01:00Z',
    actorId: 'worker',
  }]);

  renderApp('/work-items/wi-1');

  expect(await screen.findByRole('heading', { name: '代码确认' })).toBeInTheDocument();
  expect(screen.getByText('完成数据库字段迁移和课程页面适配，涉及 2 个文件，等待负责人确认。')).toBeInTheDocument();
  expect(screen.getAllByText('完成数据库字段迁移和课程页面适配').length).toBeGreaterThan(0);
  expect(document.querySelector('.stage-detail-body .change-summary')).toBeInTheDocument();
});

test('work item detail keeps the effective route visible and renders every visible version as its own card', async () => {
  renderApp('/work-items/wi-1');

  const chain = await screen.findByRole('region', { name: '产物链' });
  const currentRoute = within(chain).getByRole('region', { name: '当前已批准路线' });
  expect(within(chain).getByRole('heading', { name: '产品需求' })).toBeInTheDocument();
  expect(within(chain).getByRole('heading', { name: '执行计划' })).toBeInTheDocument();
  expect(within(chain).getByRole('heading', { name: '代码产物' })).toBeInTheDocument();
  expect(within(chain).getByRole('heading', { name: '验证报告' })).toBeInTheDocument();
  expect(within(chain).getByRole('heading', { name: '发布清单' })).toBeInTheDocument();
  expect(within(chain).getByText('尚未生成独立验证产物')).toBeInTheDocument();
  expect(within(chain).getByText('尚未生成独立发布产物')).toBeInTheDocument();
  expect(within(chain).getByText('已批准 Head 是当前业务事实，待审批版本不会提前替换它。')).toBeInTheDocument();
  expect(within(currentRoute).getByText('Coding')).toBeInTheDocument();
  expect(within(chain).getByText('Product v1')).toBeInTheDocument();
  expect(within(chain).getByText('Planning v2')).toBeInTheDocument();
  expect(within(chain).getByText('Planning v1')).toBeInTheDocument();
  expect(chain.querySelectorAll('.artifact-chain-stage')).toHaveLength(5);
  expect(chain.querySelectorAll('.artifact-chain-connector')).toHaveLength(4);
  const planningStage = chain.querySelectorAll('.artifact-chain-stage')[1] as HTMLElement;
  expect(planningStage.querySelectorAll('.artifact-version-card')).toHaveLength(2);
  expect(Array.from(planningStage.querySelectorAll('.artifact-version-heading h4'))
    .map((heading) => heading.textContent)).toEqual(['Planning v1', 'Planning v2']);
  expect(within(chain).getAllByText('基于 Product v1').length).toBeGreaterThan(0);
  expect(within(chain).getAllByText('当前使用').length).toBeGreaterThanOrEqual(3);
  expect(within(chain).getByRole('group', { name: '当前与历史版本关系' })).toBeInTheDocument();
  expect(within(chain).queryByRole('button', { name: /查看完整版本关系/ })).not.toBeInTheDocument();
  expect(screen.queryByRole('dialog', { name: '完整版本关系' })).not.toBeInTheDocument();
  expect(chain).not.toHaveTextContent('art-plan-1');
  expect(chain).not.toHaveTextContent('art-product-1');
});

test('artifact stage limits long version histories and expands them on demand', async () => {
  const productRef = {
    artifactId: 'art-product-1', artifactType: 'PRODUCT', version: 1, contentHash: 'product-hash',
    rootArtifactId: 'art-product-1', status: 'APPROVED',
  };
  const planningNodes = Array.from({ length: 6 }, (_, index) => {
    const version = index + 1;
    return {
      ref: {
        artifactId: `art-plan-${version}`, artifactType: 'PLANNING', version,
        contentHash: `plan-hash-${version}`, rootArtifactId: 'art-product-1',
        parentArtifactId: 'art-product-1', ...(version > 1 ? { supersedesArtifactId: `art-plan-${version - 1}` } : {}),
        status: version === 6 ? 'APPROVED' : 'SUPERSEDED',
      },
      systemId: 'alpha-system', prdId: 'prd-1', workItemId: 'wi-1', caseId: 'case-1',
      content: { planMarkdown: `# 执行计划 v${version}` },
      createdBy: 'worker', createdAt: `2026-07-05T11:${20 + version}:00Z`,
    };
  });
  setApiResponse('/api/v5/work-items/wi-1/artifacts', {
    rootArtifactId: 'art-product-1',
    nodes: [{
      ref: productRef,
      systemId: 'alpha-system', prdId: 'prd-1', workItemId: 'wi-1', caseId: 'case-1',
      content: {}, createdBy: 'admin', createdAt: '2026-07-05T11:00:00Z',
    }, ...planningNodes],
    edges: [
      ...planningNodes.map((node) => ({
        fromArtifactId: 'art-product-1', toArtifactId: node.ref.artifactId, edgeType: 'DERIVED_FROM',
      })),
      ...planningNodes.slice(1).map((node, index) => ({
        fromArtifactId: planningNodes[index].ref.artifactId,
        toArtifactId: node.ref.artifactId,
        edgeType: 'SUPERSEDES',
      })),
    ],
    effectiveHeads: { PRODUCT: productRef, PLANNING: planningNodes[5].ref },
  });

  renderApp('/work-items/wi-1');

  const chain = await screen.findByRole('region', { name: '产物链' });
  const planningStage = chain.querySelectorAll('.artifact-chain-stage')[1] as HTMLElement;
  expect(planningStage.querySelectorAll('.artifact-version-card')).toHaveLength(2);
  expect(Array.from(planningStage.querySelectorAll('.artifact-version-heading h4'))
    .map((heading) => heading.textContent)).toEqual(['Planning v1', 'Planning v6']);
  expect(within(planningStage).getByRole('heading', { name: 'Planning v6' })).toBeInTheDocument();
  expect(within(planningStage).getByRole('heading', { name: 'Planning v1' })).toBeInTheDocument();
  expect(within(planningStage).queryByRole('heading', { name: 'Planning v5' })).not.toBeInTheDocument();
  expect(vi.mocked(fetch).mock.calls.some(([path]) => String(path).startsWith('/api/v5/artifacts/'))).toBe(false);

  fireEvent.click(within(planningStage).getByRole('button', { name: '展开更多历史版本（4）' }));
  expect(planningStage.querySelectorAll('.artifact-version-card')).toHaveLength(6);
  expect(Array.from(planningStage.querySelectorAll('.artifact-version-heading h4'))
    .map((heading) => heading.textContent)).toEqual([
      'Planning v1', 'Planning v2', 'Planning v3', 'Planning v4', 'Planning v5', 'Planning v6',
    ]);
  expect(within(planningStage).getByRole('button', { name: '收起历史版本' })).toHaveAttribute('aria-expanded', 'true');
});

test('historical planning version requires confirmation before switching the effective route', async () => {
  const product = {
    artifactId: 'safe-product', artifactType: 'PRODUCT', version: 1, contentHash: 'safe-product-hash',
    rootArtifactId: 'safe-product', status: 'APPROVED',
  };
  const planningV1Ref = {
    artifactId: 'art-plan-1', artifactType: 'PLANNING', version: 1, contentHash: 'plan-hash-1',
    rootArtifactId: 'safe-product', parentArtifactId: 'safe-product', status: 'REJECTED',
  };
  const planningV2Ref = {
    artifactId: 'art-plan-2', artifactType: 'PLANNING', version: 2, contentHash: 'plan-hash-2',
    rootArtifactId: 'safe-product', parentArtifactId: 'safe-product',
    supersedesArtifactId: 'art-plan-1', status: 'APPROVED',
  };
  setApiResponse('/api/v5/work-items/wi-1', {
    workItemId: 'wi-1', systemId: 'alpha-system', prdId: 'prd-1', caseId: 'case-1',
    title: '安全切换计划', lifecycleStatus: 'activated', currentStage: '等待计划确认',
    waitingFor: 'owner', canControl: true, availableActions: [], lastAppliedSequence: 5,
  });
  setApiResponse('/api/v5/work-items/wi-1/events', []);
  setApiResponse('/api/v5/work-items/wi-1/artifacts', {
    rootArtifactId: 'safe-product',
    nodes: [
      { ref: product, systemId: 'alpha-system', prdId: 'prd-1', workItemId: 'wi-1', caseId: 'case-1', content: {}, createdBy: 'admin', createdAt: '2026-07-05T11:00:00Z' },
      { ref: planningV1Ref, systemId: 'alpha-system', prdId: 'prd-1', workItemId: 'wi-1', caseId: 'case-1', content: { planMarkdown: '# 旧计划' }, createdBy: 'worker', createdAt: '2026-07-05T11:20:00Z' },
      { ref: planningV2Ref, systemId: 'alpha-system', prdId: 'prd-1', workItemId: 'wi-1', caseId: 'case-1', content: { planMarkdown: '# 当前计划' }, createdBy: 'worker', createdAt: '2026-07-05T11:30:00Z' },
    ],
    edges: [
      { fromArtifactId: 'safe-product', toArtifactId: 'art-plan-1', edgeType: 'DERIVED_FROM' },
      { fromArtifactId: 'safe-product', toArtifactId: 'art-plan-2', edgeType: 'DERIVED_FROM' },
      { fromArtifactId: 'art-plan-1', toArtifactId: 'art-plan-2', edgeType: 'SUPERSEDES' },
    ],
    effectiveHeads: { PRODUCT: product, PLANNING: planningV2Ref },
    versionActions: {
      'safe-product': { canSelect: false, selectDisabledReason: '该版本已是当前有效版本', canContinue: false },
      'art-plan-1': { canSelect: true, canContinue: false, continueDisabledReason: '请先切换到该执行计划' },
      'art-plan-2': { canSelect: false, selectDisabledReason: '该版本已是当前有效版本', canContinue: true },
    },
  });
  renderApp('/work-items/wi-1');

  const chain = await screen.findByRole('region', { name: '产物链' });
  const planningV1 = within(chain).getByRole('heading', { name: 'Planning v1' }).closest('.artifact-version-card') as HTMLElement;
  expect(planningV1).toHaveTextContent('历史版本');
  expect(planningV1).toHaveTextContent('已拒绝');
  fireEvent.click(within(planningV1).getByRole('button', { name: 'Planning v1 切换为当前版本' }));

  const dialog = screen.getByRole('dialog');
  expect(dialog).toHaveTextContent('切换为执行计划 v1？');
  expect(dialog).toHaveTextContent('只将执行计划 v1 设为当前版本，不会立即启动后续执行');
  expect(dialog).toHaveTextContent('本次只切换当前版本，不会启动 Worker');
  expect(dialog).toHaveTextContent('已生成的下游产物不会删除');
  expect(dialog).toHaveTextContent('全部历史版本和审核记录继续保留');
  fireEvent.click(within(dialog).getByRole('button', { name: '切换当前执行版本' }));

  await waitFor(() => {
    const call = vi.mocked(fetch).mock.calls.find(([path, init]) =>
      String(path).endsWith('/work-items/wi-1/artifacts/active') && init?.method === 'POST');
    expect(call).toBeDefined();
    expect(JSON.parse(String(call?.[1]?.body))).toMatchObject({
      artifact: {
        artifactId: 'art-plan-1', artifactType: 'PLANNING', version: 1, status: 'REJECTED',
      },
      expectedHeads: {
        PLANNING: {
          artifactId: 'art-plan-2', version: 2, status: 'APPROVED',
        },
      },
    });
  });
  expect(vi.mocked(fetch).mock.calls.some(([path]) =>
    String(path).endsWith('/work-items/wi-1/artifacts/continue'))).toBe(false);
});

test('selected planning version only starts Coding after explicit confirmation', async () => {
  const product = {
    artifactId: 'art-product-1', artifactType: 'PRODUCT', version: 1, contentHash: 'product-hash',
    rootArtifactId: 'art-product-1', status: 'APPROVED',
  };
  const planning = {
    artifactId: 'art-plan-1', artifactType: 'PLANNING', version: 1, contentHash: 'plan-hash-1',
    rootArtifactId: 'art-product-1', parentArtifactId: 'art-product-1', status: 'APPROVED',
  };
  setApiResponse('/api/v5/work-items/wi-1', {
    workItemId: 'wi-1', systemId: 'alpha-system', prdId: 'prd-1', caseId: 'case-1',
    title: '登录页错误提示', lifecycleStatus: 'activated', currentStage: 'Worker 已激活',
    waitingFor: 'owner', canControl: true, availableActions: [], lastAppliedSequence: 5,
  });
  setApiResponse('/api/v5/work-items/wi-1/artifacts', {
    rootArtifactId: 'art-product-1',
    nodes: [{
      ref: product,
      systemId: 'alpha-system', prdId: 'prd-1', workItemId: 'wi-1', caseId: 'case-1',
      content: { goal: '改登录页' }, createdBy: 'admin', createdAt: '2026-07-05T11:00:00Z',
    }, {
      ref: planning,
      systemId: 'alpha-system', prdId: 'prd-1', workItemId: 'wi-1', caseId: 'case-1',
      content: { planMarkdown: '# 已有计划' }, createdBy: 'worker', createdAt: '2026-07-05T11:20:00Z',
    }],
    edges: [{ fromArtifactId: 'art-product-1', toArtifactId: 'art-plan-1', edgeType: 'DERIVED_FROM' }],
    effectiveHeads: { PRODUCT: product, PLANNING: planning },
    versionActions: {
      'art-product-1': { canSelect: false, selectDisabledReason: '该版本已是当前有效版本', canContinue: false },
      'art-plan-1': { canSelect: false, selectDisabledReason: '该版本已是当前有效版本', canContinue: true },
    },
  });

  renderApp('/work-items/wi-1');

  const chain = await screen.findByRole('region', { name: '产物链' });
  fireEvent.click(within(chain).getByRole('button', { name: '基于 v1 继续开发' }));
  const dialog = screen.getByRole('dialog');
  expect(dialog).toHaveTextContent('Worker 将直接执行当前计划，不会重新生成 PlanningArtifact');
  expect(dialog).toHaveTextContent('不会重新调用计划生成');
  fireEvent.click(within(dialog).getByRole('button', { name: '基于 v1 继续开发' }));

  await waitFor(() => {
    const call = vi.mocked(fetch).mock.calls.find(([path, init]) =>
      String(path).endsWith('/work-items/wi-1/artifacts/continue') && init?.method === 'POST');
    expect(call).toBeDefined();
    expect(JSON.parse(String(call?.[1]?.body))).toMatchObject({
      artifact: { artifactId: 'art-plan-1', artifactType: 'PLANNING', version: 1, status: 'APPROVED' },
      expectedHeads: { PLANNING: planning },
    });
  });
});

test('code and artifact audit follow the effective Coding head after explicit version switching', async () => {
  const workItemPath = '/api/v5/work-items/wi-1';
  const eventsPath = `${workItemPath}/events`;
  const graphPath = `${workItemPath}/artifacts`;
  const v1Graph = codingScenarioGraph('v1');
  const v2Graph = codingScenarioGraph('v2');
  const codingV2 = v2Graph.nodes.find((node) => node.ref.artifactId === 'scenario-code-2');
  setApiResponse(workItemPath, {
    workItemId: 'wi-1', systemId: 'alpha-system', title: '代码版本路线', lifecycleStatus: 'modification_completed',
    currentStage: '等待确认应用 patch', waitingFor: 'owner', canControl: true, availableActions: [], lastAppliedSequence: 824,
  });
  setApiResponse(eventsPath, codingScenarioEvents());
  setApiResponse(graphPath, v1Graph);
  setApiResponse('/api/v5/artifacts/scenario-code-2', {
    artifact: codingV2, transitions: [], evidence: [],
  });
  const fallback = vi.mocked(fetch).getMockImplementation()!;
  vi.mocked(fetch).mockImplementation((input: RequestInfo | URL, init?: RequestInit) => {
    if (String(input).endsWith('/artifacts/active') && init?.method === 'POST') {
      setApiResponse(eventsPath, codingScenarioEvents());
      setApiResponse(graphPath, v2Graph);
      return jsonResponse({ status: 'submitted', effectiveHeads: v2Graph.effectiveHeads });
    }
    return fallback(input, init);
  });

  renderApp('/work-items/wi-1');
  const chain = await screen.findByRole('region', { name: '产物链' });
  const codingStage = chain.querySelectorAll('.artifact-chain-stage')[2] as HTMLElement;
  expect(Array.from(codingStage.querySelectorAll('.artifact-version-heading h4'))
    .map((heading) => heading.textContent)).toEqual(['Coding v1', 'Coding v2']);
  fireEvent.click(screen.getByRole('button', { name: '代码变更' }));
  let codePanel = document.querySelector('.code-change-list') as HTMLElement;
  expect(within(codePanel).getByText('v1代码摘要')).toBeInTheDocument();
  expect(within(codePanel).getByTitle('src/v1.ts')).toBeInTheDocument();
  expect(within(codePanel).queryByTitle('src/v2.ts')).not.toBeInTheDocument();
  expect(screen.queryByRole('heading', { name: '修订历史' })).not.toBeInTheDocument();

  fireEvent.click(within(chain).getByRole('button', { name: 'Coding v2 切换为当前版本' }));
  const dialog = screen.getByRole('dialog');
  fireEvent.click(within(dialog).getByRole('button', { name: '切换当前执行版本' }));

  await waitFor(() => {
    codePanel = document.querySelector('.code-change-list') as HTMLElement;
    expect(within(codePanel).getByText('v2代码摘要')).toBeInTheDocument();
  });
  expect(within(codePanel).getByTitle('src/v2.ts')).toBeInTheDocument();
  expect(within(codePanel).queryByTitle('src/v1.ts')).not.toBeInTheDocument();
  expect(screen.queryByRole('heading', { name: '修订历史' })).not.toBeInTheDocument();

  const codingV2Card = within(chain).getByRole('heading', { name: 'Coding v2' })
    .closest('.artifact-version-card') as HTMLElement;
  fireEvent.click(within(codingV2Card).getByRole('button', { name: '查看详情' }));
  const drawer = await screen.findByRole('dialog', { name: 'Coding v2' });
  expect(within(drawer).getByRole('heading', { name: '版本演进 / 审计' })).toBeInTheDocument();
  expect(drawer).toHaveTextContent('Coding v1');
  expect(drawer).toHaveTextContent('Coding v2');
  expect(drawer).toHaveTextContent('v2 替代 v1');
  expect(drawer).toHaveTextContent('v2修订意见');
  expect(drawer).toHaveTextContent('增量修订');
  expect(drawer).toHaveTextContent('修改人：worker');
  expect(drawer).toHaveTextContent('Diff 摘要');
  fireEvent.click(within(drawer).getByRole('button', { name: '关闭详情' }));
  await waitFor(() => expect(screen.queryByRole('dialog', { name: 'Coding v2' })).not.toBeInTheDocument());

  fireEvent.click(screen.getByRole('button', { name: '事件审计' }));
  expect(screen.getAllByText('ModificationCompleted')).toHaveLength(2);
  expect(screen.getByText('RevisionRequested')).toBeInTheDocument();
  expect(fetch).toHaveBeenCalledWith(eventsPath, expect.anything());
  expect(fetch).toHaveBeenCalledWith(graphPath, expect.anything());
});

test('code review shows the exact proposed Coding artifact before an effective head exists', async () => {
  const graph = codingScenarioGraph(null);
  setApiResponse('/api/v5/work-items/wi-1', {
    workItemId: 'wi-1', systemId: 'alpha-system', title: '确认待审核代码',
    lifecycleStatus: 'modification_completed', currentStage: '等待确认应用 patch', waitingFor: 'owner',
    canControl: true, availableActions: ['patch_apply_approved', 'patch_apply_rejected'], lastAppliedSequence: 824,
  });
  setApiResponse('/api/v5/work-items/wi-1/events', codingScenarioEvents());
  setApiResponse('/api/v5/work-items/wi-1/artifacts', graph);

  renderApp('/work-items/wi-1');

  fireEvent.click(await screen.findByRole('button', { name: '代码变更' }));
  const codePanel = document.querySelector('.code-change-list') as HTMLElement;
  expect(within(codePanel).getByText('v2代码摘要')).toBeInTheDocument();
  expect(within(codePanel).getByTitle('src/v2.ts')).toBeInTheDocument();
  expect(within(codePanel).queryByTitle('src/v1.ts')).not.toBeInTheDocument();
  expect(within(codePanel).queryByText('当前路线没有有效的代码产物，未展示历史版本代码。')).not.toBeInTheDocument();

  fireEvent.click(within(codePanel).getByRole('button', { name: '应用 Patch' }));
  fireEvent.click(within(screen.getByRole('dialog')).getByRole('button', { name: '应用 Patch' }));
  await waitFor(() => {
    const call = vi.mocked(fetch).mock.calls.find(([path, init]) =>
      String(path).endsWith('/signals/patch_apply_approved') && init?.method === 'POST');
    expect(JSON.parse(String(call?.[1]?.body))).toMatchObject({ artifactRef: codingScenarioRefs.codingV2 });
  });
});

test('code review shows the exact proposed Coding artifact instead of an older effective head', async () => {
  const graph = codingScenarioGraph('v1');
  graph.nodes = graph.nodes.map((node) => node.ref.artifactId === codingScenarioRefs.codingV2.artifactId
    ? { ...node, ref: codingScenarioRefs.codingV2 }
    : node);
  setApiResponse('/api/v5/work-items/wi-1', {
    workItemId: 'wi-1', systemId: 'alpha-system', title: '确认新一轮待审核代码',
    lifecycleStatus: 'modification_completed', currentStage: '等待确认应用 patch', waitingFor: 'owner',
    canControl: true, availableActions: ['patch_apply_approved', 'patch_apply_rejected'], lastAppliedSequence: 824,
  });
  setApiResponse('/api/v5/work-items/wi-1/events', codingScenarioEvents());
  setApiResponse('/api/v5/work-items/wi-1/artifacts', graph);

  renderApp('/work-items/wi-1');

  const chain = await screen.findByRole('region', { name: '产物链' });
  const codingV1Card = within(chain).getByRole('heading', { name: 'Coding v1' })
    .closest('.artifact-version-card') as HTMLElement;
  expect(codingV1Card).toHaveTextContent('当前使用');
  fireEvent.click(screen.getByRole('button', { name: '代码变更' }));
  const codePanel = document.querySelector('.code-change-list') as HTMLElement;
  expect(within(codePanel).getByText('v2代码摘要')).toBeInTheDocument();
  expect(within(codePanel).getByTitle('src/v2.ts')).toBeInTheDocument();
  expect(within(codePanel).queryByTitle('src/v1.ts')).not.toBeInTheDocument();

  fireEvent.click(within(codePanel).getByRole('button', { name: '应用 Patch' }));
  fireEvent.click(within(screen.getByRole('dialog')).getByRole('button', { name: '应用 Patch' }));
  await waitFor(() => {
    const call = vi.mocked(fetch).mock.calls.find(([path, init]) =>
      String(path).endsWith('/signals/patch_apply_approved') && init?.method === 'POST');
    expect(JSON.parse(String(call?.[1]?.body))).toMatchObject({ artifactRef: codingScenarioRefs.codingV2 });
  });
});

test('code confirmation follows the approved Coding head selected from history', async () => {
  const selected = { ...codingScenarioRefs.codingV1, status: 'APPROVED' };
  const superseded = { ...codingScenarioRefs.codingV2, status: 'SUPERSEDED' };
  const graph = codingScenarioGraph('v1');
  graph.nodes = graph.nodes.map((node) => node.ref.artifactId === selected.artifactId
    ? { ...node, ref: selected }
    : node.ref.artifactId === superseded.artifactId ? { ...node, ref: superseded } : node);
  graph.effectiveHeads.CODING = selected;
  setApiResponse('/api/v5/work-items/wi-1', {
    workItemId: 'wi-1', systemId: 'alpha-system', title: '确认选中的代码版本',
    lifecycleStatus: 'modification_completed', currentStage: '等待确认应用 patch', waitingFor: 'owner',
    canControl: true, availableActions: ['patch_apply_approved', 'patch_apply_rejected', 'cancel_case'],
    lastAppliedSequence: 824,
  });
  setApiResponse('/api/v5/work-items/wi-1/events', codingScenarioEvents());
  setApiResponse('/api/v5/work-items/wi-1/artifacts', graph);

  renderApp('/work-items/wi-1');

  fireEvent.click(await screen.findByRole('button', { name: '应用 Patch' }));
  fireEvent.click(within(screen.getByRole('dialog')).getByRole('button', { name: '应用 Patch' }));
  await waitFor(() => {
    const call = vi.mocked(fetch).mock.calls.find(([path, init]) =>
      String(path).endsWith('/signals/patch_apply_approved') && init?.method === 'POST');
    expect(JSON.parse(String(call?.[1]?.body))).toMatchObject({ artifactRef: selected });
  });
});

test('code stage keeps Planning history locked and preserves the current Coding route', async () => {
  const workItemPath = '/api/v5/work-items/wi-1';
  const eventsPath = `${workItemPath}/events`;
  const graphPath = `${workItemPath}/artifacts`;
  setApiResponse(workItemPath, {
    workItemId: 'wi-1', systemId: 'alpha-system', title: '切换到旧计划', lifecycleStatus: 'modification_completed',
    currentStage: '等待确认应用 patch', waitingFor: 'owner', canControl: true, availableActions: [], lastAppliedSequence: 824,
  });
  setApiResponse(eventsPath, codingScenarioEvents());
  const graph = codingScenarioGraph('v2');
  const codingV2 = graph.nodes.find((node) => node.ref.artifactId === 'scenario-code-2');
  setApiResponse(graphPath, graph);
  setApiResponse('/api/v5/artifacts/scenario-code-2', {
    artifact: codingV2, transitions: [], evidence: [],
  });

  renderApp('/work-items/wi-1');
  const chain = await screen.findByRole('region', { name: '产物链' });
  const switchPlanning = within(chain).getByRole('button', { name: 'Planning v1 切换为当前版本' });
  expect(switchPlanning).toBeDisabled();
  expect(switchPlanning).toHaveAttribute(
    'title', 'Coding 已开始，切换 Planning 必须先显式回退并重新执行');
  fireEvent.click(switchPlanning);
  expect(screen.queryByRole('dialog')).not.toBeInTheDocument();
  expect(vi.mocked(fetch).mock.calls.some(([path, init]) =>
    String(path).endsWith('/artifacts/active') && init?.method === 'POST')).toBe(false);

  fireEvent.click(screen.getByRole('button', { name: '代码变更' }));
  const codePanel = document.querySelector('.code-change-list') as HTMLElement;
  expect(within(codePanel).getByText('v2代码摘要')).toBeInTheDocument();
  expect(within(codePanel).getByTitle('src/v2.ts')).toBeInTheDocument();
  const codingV2Card = within(chain).getByRole('heading', { name: 'Coding v2' })
    .closest('.artifact-version-card') as HTMLElement;
  fireEvent.click(within(codingV2Card).getByRole('button', { name: '查看详情' }));
  const drawer = await screen.findByRole('dialog', { name: 'Coding v2' });
  expect(within(drawer).getByRole('heading', { name: '版本演进 / 审计' })).toBeInTheDocument();
  expect(drawer).toHaveTextContent('v2修订意见');
});

test('artifact graph exposes business content, transitions and evidence without internal fields', async () => {
  renderApp('/work-items/wi-1');

  const chain = await screen.findByRole('region', { name: '产物链' });
  const productCard = within(chain).getByRole('heading', { name: 'Product v1' }).closest('.artifact-version-card') as HTMLElement;
  fireEvent.click(within(productCard).getByRole('button', { name: '查看详情' }));
  const productDrawer = screen.getByRole('dialog', { name: 'Product v1' });
  expect(await within(productDrawer).findByRole('region', { name: '内容与审计摘要' })).toBeInTheDocument();
  expect(within(productDrawer).getByRole('heading', { name: '完整内容' })).toBeInTheDocument();
  expect(await within(productDrawer).findByText('错误密码时提示')).toBeInTheDocument();
  expect(await within(productDrawer).findByText('CREATED → APPROVED')).toBeInTheDocument();
  fireEvent.click(within(productDrawer).getByRole('button', { name: '关闭详情' }));

  const codingCard = within(chain).getByRole('heading', { name: 'Coding v1' }).closest('.artifact-version-card') as HTMLElement;
  fireEvent.click(within(codingCard).getByRole('button', { name: '查看详情' }));
  const codingDrawer = screen.getByRole('dialog', { name: 'Coding v1' });
  const codingOverview = await within(codingDrawer).findByRole('region', { name: '内容与审计摘要' });
  expect(within(codingOverview).getByText(/登录提示已完成/)).toBeInTheDocument();
  expect(await within(codingDrawer).findByText('调整登录错误提示')).toBeInTheDocument();
  expect(await within(codingDrawer).findByText('PatchApplied')).toBeInTheDocument();
  expect(within(codingDrawer).queryByText('CodingExecution')).not.toBeInTheDocument();
  expect(within(codingDrawer).queryByText(/hidden-session/)).not.toBeInTheDocument();
  expect(within(codingDrawer).queryByText(/hidden-token/)).not.toBeInTheDocument();
  expect(within(codingDrawer).queryByText(/hidden-agent/)).not.toBeInTheDocument();
});

test('artifact chain keeps an ungenerated stage aligned as a full placeholder card', async () => {
  setApiResponse('/api/v5/work-items/wi-1/artifacts', {
    rootArtifactId: 'art-product-1',
    nodes: [
      {
        ref: {
          artifactId: 'art-product-1', artifactType: 'PRODUCT', rootArtifactId: 'art-product-1',
          version: 1, status: 'APPROVED', contentHash: 'product-hash',
        },
        systemId: 'alpha-system', prdId: 'prd-1', workItemId: 'wi-1', caseId: 'case-1',
        content: {}, createdBy: 'admin', createdAt: '2026-07-05T11:00:00Z',
      },
      {
        ref: {
          artifactId: 'art-plan-1', artifactType: 'PLANNING', rootArtifactId: 'art-product-1',
          version: 1, status: 'PROPOSED', parentArtifactId: 'art-product-1', contentHash: 'plan-hash',
        },
        systemId: 'alpha-system', prdId: 'prd-1', workItemId: 'wi-1', caseId: 'case-1',
        content: {}, createdBy: 'worker', createdAt: '2026-07-05T11:20:00Z',
      },
    ],
    edges: [{ fromArtifactId: 'art-product-1', toArtifactId: 'art-plan-1', edgeType: 'DERIVED_FROM' }],
    effectiveHeads: {
      PRODUCT: {
        artifactId: 'art-product-1', artifactType: 'PRODUCT', rootArtifactId: 'art-product-1',
        version: 1, status: 'APPROVED', contentHash: 'product-hash',
      },
    },
  });

  renderApp('/work-items/wi-1');

  const chain = await screen.findByRole('region', { name: '产物链' });
  const stages = chain.querySelectorAll('.artifact-chain-stage');
  expect(stages).toHaveLength(5);
  const emptyCard = stages[2].querySelector('.artifact-empty-card') as HTMLElement;
  expect(within(emptyCard).getByText('尚未生成')).toBeInTheDocument();
  expect(within(emptyCard).getByText('计划批准后开始生成')).toBeInTheDocument();
  expect(emptyCard).toBeInTheDocument();
  expect(within(stages[3] as HTMLElement).getByText('尚未生成独立验证产物')).toBeInTheDocument();
  expect(within(stages[4] as HTMLElement).getByText('尚未生成独立发布产物')).toBeInTheDocument();
});

test('newer proposal never hides the older effective artifact head', async () => {
  const productRef = {
    artifactId: 'art-product-1', artifactType: 'PRODUCT', rootArtifactId: 'art-product-1',
    version: 1, status: 'APPROVED', contentHash: 'product-hash',
  };
  const planningV1 = {
    artifactId: 'art-plan-1', artifactType: 'PLANNING', rootArtifactId: 'art-product-1',
    parentArtifactId: 'art-product-1', version: 1, status: 'APPROVED', contentHash: 'plan-hash-1',
  };
  const codingV1 = {
    artifactId: 'art-code-1', artifactType: 'CODING', rootArtifactId: 'art-product-1',
    parentArtifactId: 'art-plan-1', version: 1, status: 'APPROVED', contentHash: 'code-hash-1',
  };
  const validationV1 = {
    artifactId: 'art-validation-1', artifactType: 'VALIDATION', rootArtifactId: 'art-product-1',
    parentArtifactId: 'art-code-1', version: 1, status: 'APPROVED', contentHash: 'validation-hash-1',
  };
  setApiResponse('/api/v5/work-items/wi-1/artifacts', {
    rootArtifactId: 'art-product-1',
    nodes: [
      {
        ref: productRef,
        systemId: 'alpha-system', prdId: 'prd-1', workItemId: 'wi-1', caseId: 'case-1',
        content: {}, createdBy: 'admin', createdAt: '2026-07-05T11:00:00Z',
      },
      {
        ref: planningV1,
        systemId: 'alpha-system', prdId: 'prd-1', workItemId: 'wi-1', caseId: 'case-1',
        content: { planMarkdown: '# 当前计划' }, createdBy: 'worker', createdAt: '2026-07-05T11:20:00Z',
      },
      {
        ref: {
          artifactId: 'art-plan-2', artifactType: 'PLANNING', rootArtifactId: 'art-product-1',
          parentArtifactId: 'art-product-1', supersedesArtifactId: 'art-plan-1',
          version: 2, status: 'PROPOSED', contentHash: 'plan-hash-2',
        },
        systemId: 'alpha-system', prdId: 'prd-1', workItemId: 'wi-1', caseId: 'case-1',
        content: { planMarkdown: '# 候选计划' }, createdBy: 'worker', createdAt: '2026-07-05T11:30:00Z',
      },
      {
        ref: codingV1,
        systemId: 'alpha-system', prdId: 'prd-1', workItemId: 'wi-1', caseId: 'case-1',
        content: { summary: '旧代码结果', repoChanges: [] }, createdBy: 'worker', createdAt: '2026-07-05T11:25:00Z',
      },
      {
        ref: validationV1,
        systemId: 'alpha-system', prdId: 'prd-1', workItemId: 'wi-1', caseId: 'case-1',
        content: { result: 'PASSED', mode: 'AUTO', commands: [] }, createdBy: 'worker', createdAt: '2026-07-05T11:26:00Z',
      },
    ],
    edges: [
      { fromArtifactId: 'art-product-1', toArtifactId: 'art-plan-1', edgeType: 'DERIVED_FROM' },
      { fromArtifactId: 'art-product-1', toArtifactId: 'art-plan-2', edgeType: 'DERIVED_FROM' },
      { fromArtifactId: 'art-plan-1', toArtifactId: 'art-plan-2', edgeType: 'SUPERSEDES' },
      { fromArtifactId: 'art-plan-1', toArtifactId: 'art-code-1', edgeType: 'DERIVED_FROM' },
      { fromArtifactId: 'art-code-1', toArtifactId: 'art-validation-1', edgeType: 'DERIVED_FROM' },
    ],
    effectiveHeads: {
      PRODUCT: productRef, PLANNING: planningV1, CODING: codingV1, VALIDATION: validationV1,
    },
    versionActions: {
      'art-product-1': { canSelect: false, selectDisabledReason: '该版本已是当前有效版本', canContinue: false },
      'art-plan-1': { canSelect: false, selectDisabledReason: '该版本已是当前有效版本', canContinue: true },
      'art-plan-2': { canSelect: true, canContinue: false, continueDisabledReason: '请先切换到该执行计划' },
    },
  });

  renderApp('/work-items/wi-1');

  const chain = await screen.findByRole('region', { name: '产物链' });
  const planningStage = chain.querySelectorAll('.artifact-chain-stage')[1] as HTMLElement;
  expect(within(planningStage).getByText('当前使用')).toBeInTheDocument();
  const currentCard = within(planningStage).getByRole('heading', { name: 'Planning v1' }).closest('.artifact-version-card') as HTMLElement;
  const proposalCard = within(planningStage).getByRole('heading', { name: 'Planning v2' }).closest('.artifact-version-card') as HTMLElement;
  expect(Array.from(planningStage.querySelectorAll('.artifact-version-heading h4'))
    .map((heading) => heading.textContent)).toEqual(['Planning v1', 'Planning v2']);
  expect(currentCard).toHaveTextContent('当前使用');
  expect(within(currentCard).queryByRole('button', { name: /切换为当前版本/ })).not.toBeInTheDocument();
  expect(proposalCard).toHaveTextContent('待审批');
  expect(within(proposalCard).getByRole('button', { name: 'Planning v2 切换为当前版本' })).toBeInTheDocument();
  expect(within(chain).getByRole('status')).toHaveTextContent(
    'Planning v2 正在等待审批；当前已批准路线暂不改变。批准后 Planning v1、Coding v1、Validation v1 将转为历史，后续从 Planning v2 继续生成。',
  );
});

test('work item detail keeps long Git content inside the code tab', async () => {
  renderApp('/work-items/wi-1');

  expect(await screen.findByRole('heading', { name: '代码确认' })).toBeInTheDocument();
  expect(screen.queryByText(/diff --git a\/src\/login.tsx/)).not.toBeInTheDocument();
  expect(screen.queryByText('原始 JSON')).not.toBeInTheDocument();

  fireEvent.click(screen.getByRole('button', { name: '代码变更' }));
  expect(screen.queryByText('claude_sdk_team')).not.toBeInTheDocument();
  expect(screen.queryByText('输入 320 / 输出 80')).not.toBeInTheDocument();
  expect(screen.queryByText('Token')).not.toBeInTheDocument();
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
    expect(JSON.parse(String(call?.[1]?.body))).toMatchObject({
      note: '错误提示应放在输入框下方',
      artifactRef: {
        artifactId: 'art-code-1', artifactType: 'CODING', version: 1,
        contentHash: 'code-hash', status: 'PROPOSED',
      },
    });
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
  expect(alert).toHaveTextContent('工作项或产物版本已更新，页面已刷新，请按当前展示的版本继续。');
  await waitFor(() => expect(screen.queryByRole('button', { name: '应用 Patch' })).not.toBeInTheDocument());
  expect(fetch).toHaveBeenCalledWith('/api/v5/work-items/wi-1/events', expect.anything());
  expect(fetch).toHaveBeenCalledWith('/api/v5/work-items/wi-1/artifacts', expect.anything());
});

test('work item cannot approve an artifact version that is not displayed by the graph', async () => {
  setApiResponse('/api/v5/work-items/wi-1/events', [{
    sequence: 3,
    eventId: 'evt-stale-modification',
    eventType: 'ModificationCompleted',
    payloadJson: JSON.stringify({
      summary: '旧页面代码结果',
      artifactRef: {
        artifactId: 'art-code-stale', artifactType: 'CODING', version: 1, contentHash: 'stale-hash',
        rootArtifactId: 'art-product-1', parentArtifactId: 'art-plan-2', status: 'PROPOSED',
      },
    }),
    createdAt: '2026-07-05T12:01:00Z',
  }]);

  renderApp('/work-items/wi-1');

  expect(await screen.findByRole('heading', { name: '代码确认' })).toBeInTheDocument();
  expect(screen.queryByRole('button', { name: '应用 Patch' })).not.toBeInTheDocument();
  fireEvent.click(screen.getByRole('button', { name: '代码变更' }));
  expect(screen.queryByRole('button', { name: '应用 Patch' })).not.toBeInTheDocument();
});

test('legacy in-flight release action binds the effective approved coding head', async () => {
  setApiResponse('/api/v5/work-items/wi-1', {
    workItemId: 'wi-1', systemId: 'alpha-system', title: '登录页发布', lifecycleStatus: 'validation_passed',
    currentStage: '等待上线确认', waitingFor: 'owner', canControl: true,
    availableActions: ['release_approved'], lastAppliedSequence: 5,
  });
  const approvedCodingRef = {
    artifactId: 'art-code-1', artifactType: 'CODING', version: 1, contentHash: 'code-hash',
    rootArtifactId: 'art-product-1', parentArtifactId: 'art-plan-2', status: 'APPROVED',
  };
  setApiResponse('/api/v5/work-items/wi-1/artifacts', {
    rootArtifactId: 'art-product-1',
    nodes: [{
      ref: approvedCodingRef,
      systemId: 'alpha-system', prdId: 'prd-1', workItemId: 'wi-1', caseId: 'case-1',
      content: { summary: '登录提示已完成', repoChanges: [] },
      createdBy: 'worker', createdAt: '2026-07-05T12:01:00Z',
    }],
    edges: [],
    effectiveHeads: { CODING: approvedCodingRef },
  });
  renderApp('/work-items/wi-1');

  fireEvent.click(await screen.findByRole('button', { name: '进入发布' }));
  fireEvent.click(within(screen.getByRole('dialog')).getByRole('button', { name: '进入发布' }));

  await waitFor(() => {
    const call = vi.mocked(fetch).mock.calls.find(([path, init]) =>
      String(path).endsWith('/signals/release_approved') && init?.method === 'POST');
    expect(JSON.parse(String(call?.[1]?.body))).toMatchObject({ artifactRef: approvedCodingRef });
  });
});

test('new release action binds the effective passed validation head', async () => {
  setApiResponse('/api/v5/work-items/wi-1', {
    workItemId: 'wi-1', systemId: 'alpha-system', title: '登录页发布', lifecycleStatus: 'validation_passed',
    currentStage: '等待上线确认', waitingFor: 'owner', canControl: true,
    availableActions: ['release_approved'], lastAppliedSequence: 9,
  });
  const product = {
    artifactId: 'release-product', artifactType: 'PRODUCT', version: 1, contentHash: 'product-hash',
    rootArtifactId: 'release-product', status: 'APPROVED',
  };
  const planning = {
    artifactId: 'release-plan', artifactType: 'PLANNING', version: 1, contentHash: 'plan-hash',
    rootArtifactId: 'release-product', parentArtifactId: 'release-product', status: 'APPROVED',
  };
  const coding = {
    artifactId: 'release-code', artifactType: 'CODING', version: 1, contentHash: 'code-hash',
    rootArtifactId: 'release-product', parentArtifactId: 'release-plan', status: 'APPROVED',
  };
  const validation = {
    artifactId: 'release-validation', artifactType: 'VALIDATION', version: 2, contentHash: 'validation-hash',
    rootArtifactId: 'release-product', parentArtifactId: 'release-code', status: 'APPROVED',
  };
  const summaries = [
    { ref: product, content: { title: '登录页发布' } },
    { ref: planning, content: { planMarkdown: '# 发布计划' } },
    { ref: coding, content: { summary: '代码完成', repoChanges: [] } },
    { ref: validation, content: { result: 'PASSED', mode: 'AUTO', commands: [] } },
  ].map((artifact) => ({
    ...artifact, systemId: 'alpha-system', prdId: 'prd-1', workItemId: 'wi-1', caseId: 'case-1',
    createdBy: 'worker', createdAt: '2026-07-05T12:01:00Z',
  }));
  setApiResponse('/api/v5/work-items/wi-1/artifacts', {
    rootArtifactId: 'release-product', nodes: summaries,
    edges: [
      { fromArtifactId: product.artifactId, toArtifactId: planning.artifactId, edgeType: 'DERIVED_FROM' },
      { fromArtifactId: planning.artifactId, toArtifactId: coding.artifactId, edgeType: 'DERIVED_FROM' },
      { fromArtifactId: coding.artifactId, toArtifactId: validation.artifactId, edgeType: 'DERIVED_FROM' },
    ],
    effectiveHeads: { PRODUCT: product, PLANNING: planning, CODING: coding, VALIDATION: validation },
  });

  renderApp('/work-items/wi-1');

  fireEvent.click(await screen.findByRole('button', { name: '进入发布' }));
  fireEvent.click(within(screen.getByRole('dialog')).getByRole('button', { name: '进入发布' }));

  await waitFor(() => {
    const call = vi.mocked(fetch).mock.calls.find(([path, init]) =>
      String(path).endsWith('/signals/release_approved') && init?.method === 'POST');
    expect(JSON.parse(String(call?.[1]?.body))).toMatchObject({ artifactRef: validation });
  });
});

test('validation and release artifact drawers render typed result details', async () => {
  const graph = resultArtifactGraph();
  setApiResponse('/api/v5/work-items/wi-1', {
    workItemId: 'wi-1', systemId: 'alpha-system', title: '结果产物详情', lifecycleStatus: 'completed',
    currentStage: '已完成', waitingFor: '', canControl: true, availableActions: [], lastAppliedSequence: 20,
  });
  setApiResponse('/api/v5/work-items/wi-1/artifacts', graph);
  for (const artifactId of ['result-validation', 'result-release']) {
    const artifact = graph.nodes.find((node) => node.ref.artifactId === artifactId);
    setApiResponse(`/api/v5/artifacts/${artifactId}`, {
      artifact, transitions: [], evidence: [],
    });
  }

  renderApp('/work-items/wi-1');

  const chain = await screen.findByRole('region', { name: '产物链' });
  expect(within(chain).getByText('Release v1')).toBeInTheDocument();
  const validationStage = chain.querySelectorAll('.artifact-chain-stage')[3] as HTMLElement;
  fireEvent.click(within(validationStage).getByRole('button', { name: '查看详情' }));
  const validationDialog = await screen.findByRole('dialog', { name: 'Validation v2' });
  expect(validationDialog).toHaveTextContent('验证通过');
  expect(validationDialog).toHaveTextContent('validation-run-2');
  expect(validationDialog).toHaveTextContent('npm test');
  expect(validationDialog).toHaveTextContent('CI 记录 #42');
  fireEvent.click(within(validationDialog).getByRole('button', { name: '关闭详情' }));
  await waitFor(() => expect(screen.queryByRole('dialog', { name: 'Validation v2' })).not.toBeInTheDocument());

  const releaseStage = chain.querySelectorAll('.artifact-chain-stage')[4] as HTMLElement;
  fireEvent.click(within(releaseStage).getByRole('button', { name: '查看详情' }));
  const releaseDialog = await screen.findByRole('dialog', { name: 'Release v1' });
  expect(releaseDialog).toHaveTextContent('release-batch-1');
  expect(releaseDialog).toHaveTextContent('production');
  expect(releaseDialog).toHaveTextContent('wi/result');
  expect(releaseDialog).toHaveTextContent('abc123');
  expect(releaseDialog).toHaveTextContent('src/login.tsx');
});

test('validation and release history stay read-only and keep failed results in artifact audit', async () => {
  const base = resultArtifactGraph();
  const validationV1Ref = {
    ...resultArtifactRefs.validation, artifactId: 'result-validation-1', version: 1,
    contentHash: 'result-validation-1-hash', status: 'SUPERSEDED',
  };
  const validationV2Ref = {
    ...resultArtifactRefs.validation, supersedesArtifactId: validationV1Ref.artifactId,
  };
  const releaseV1Ref = {
    ...resultArtifactRefs.release, artifactId: 'result-release-1', contentHash: 'result-release-1-hash',
    parentArtifactId: validationV1Ref.artifactId, status: 'SUPERSEDED',
  };
  const releaseV2Ref = {
    ...resultArtifactRefs.release, artifactId: 'result-release-2', version: 2,
    contentHash: 'result-release-2-hash', parentArtifactId: validationV2Ref.artifactId,
    supersedesArtifactId: releaseV1Ref.artifactId,
  };
  const validationV1 = {
    ...base.nodes.find((node) => node.ref.artifactType === 'VALIDATION')!,
    ref: validationV1Ref,
    content: {
      validationRunId: 'validation-run-1', mode: 'AUTO', result: 'FAILED',
      commands: [{ repo: 'main', command: 'npm test', exitCode: 1 }],
      errorSummary: '登录页测试失败', codingContentHash: 'result-code-hash',
    },
    createdAt: '2026-08-05T11:50:00Z',
  };
  const validationV2 = {
    ...base.nodes.find((node) => node.ref.artifactType === 'VALIDATION')!, ref: validationV2Ref,
  };
  const releaseV1 = {
    ...base.nodes.find((node) => node.ref.artifactType === 'RELEASE')!, ref: releaseV1Ref,
    createdAt: '2026-08-05T11:55:00Z',
  };
  const releaseV2 = {
    ...base.nodes.find((node) => node.ref.artifactType === 'RELEASE')!, ref: releaseV2Ref,
  };
  const graph = {
    ...base,
    nodes: [
      ...base.nodes.filter((node) => !['VALIDATION', 'RELEASE'].includes(node.ref.artifactType)),
      validationV1, validationV2, releaseV1, releaseV2,
    ],
    edges: [
      ...base.edges.filter((edge) => edge.toArtifactId === resultArtifactRefs.planning.artifactId
        || edge.toArtifactId === resultArtifactRefs.coding.artifactId),
      { fromArtifactId: resultArtifactRefs.coding.artifactId, toArtifactId: validationV1Ref.artifactId, edgeType: 'DERIVED_FROM' },
      { fromArtifactId: resultArtifactRefs.coding.artifactId, toArtifactId: validationV2Ref.artifactId, edgeType: 'DERIVED_FROM' },
      { fromArtifactId: validationV1Ref.artifactId, toArtifactId: validationV2Ref.artifactId, edgeType: 'SUPERSEDES' },
      { fromArtifactId: validationV1Ref.artifactId, toArtifactId: releaseV1Ref.artifactId, edgeType: 'DERIVED_FROM' },
      { fromArtifactId: validationV2Ref.artifactId, toArtifactId: releaseV2Ref.artifactId, edgeType: 'DERIVED_FROM' },
      { fromArtifactId: releaseV1Ref.artifactId, toArtifactId: releaseV2Ref.artifactId, edgeType: 'SUPERSEDES' },
    ],
    effectiveHeads: {
      ...base.effectiveHeads, VALIDATION: validationV2Ref, RELEASE: releaseV2Ref,
    },
    versionActions: {
      [validationV1Ref.artifactId]: { canSelect: true, canContinue: false },
      [releaseV1Ref.artifactId]: { canSelect: true, canContinue: false },
    },
  };
  setApiResponse('/api/v5/work-items/wi-1', {
    workItemId: 'wi-1', systemId: 'alpha-system', title: '结果历史只读', lifecycleStatus: 'completed',
    currentStage: '已完成', waitingFor: '', canControl: true, availableActions: [], lastAppliedSequence: 20,
  });
  setApiResponse('/api/v5/work-items/wi-1/artifacts', graph);
  setApiResponse('/api/v5/artifacts/result-validation-1', {
    artifact: validationV1, transitions: [], evidence: [],
  });

  renderApp('/work-items/wi-1');

  const chain = await screen.findByRole('region', { name: '产物链' });
  const validationStage = chain.querySelectorAll('.artifact-chain-stage')[3] as HTMLElement;
  const releaseStage = chain.querySelectorAll('.artifact-chain-stage')[4] as HTMLElement;
  expect(within(validationStage).getByText('最新执行结果由系统自动采用，历史版本仅供查看。')).toBeInTheDocument();
  expect(within(releaseStage).getByText('最新执行结果由系统自动采用，历史版本仅供查看。')).toBeInTheDocument();
  expect(within(validationStage).queryByRole('button', { name: 'Validation v1 切换为当前版本' })).not.toBeInTheDocument();
  expect(within(releaseStage).queryByRole('button', { name: 'Release v1 切换为当前版本' })).not.toBeInTheDocument();

  const validationV1Card = within(validationStage).getByRole('heading', { name: 'Validation v1' })
    .closest('.artifact-version-card') as HTMLElement;
  const releaseV1Card = within(releaseStage).getByRole('heading', { name: 'Release v1' })
    .closest('.artifact-version-card') as HTMLElement;
  expect(within(validationV1Card).getByRole('button', { name: '查看详情' })).toBeInTheDocument();
  expect(within(releaseV1Card).getByRole('button', { name: '查看详情' })).toBeInTheDocument();

  fireEvent.click(within(validationV1Card).getByRole('button', { name: '查看详情' }));
  const drawer = await screen.findByRole('dialog', { name: 'Validation v1' });
  expect(within(drawer).getByRole('heading', { name: '版本演进 / 审计' })).toBeInTheDocument();
  expect(drawer).toHaveTextContent('验证失败：登录页测试失败');
  expect(drawer).toHaveTextContent('Validation v2');
});

test('validation passed stays in validation and exposes report plus three explicit choices', async () => {
  const graph = resultArtifactGraph('PASSED', false);
  setApiResponse('/api/v5/work-items/wi-1', {
    workItemId: 'wi-1', systemId: 'alpha-system', title: '验证通过待决策', lifecycleStatus: 'validation_passed',
    currentStage: '等待负责人决定', waitingFor: 'owner', canControl: true,
    availableActions: ['release_approved', 'validation_rework_coding', 'validation_rework_planning'],
    lastAppliedSequence: 13,
  });
  setApiResponse('/api/v5/work-items/wi-1/events', [{
    sequence: 13, eventId: 'validation-passed', eventType: 'ValidationPassed',
    payloadJson: JSON.stringify({
      artifactRef: resultArtifactRefs.validation,
      commands: [{ repo: 'main', command: 'npm test', exitCode: 0 }],
    }),
    createdAt: '2026-08-05T12:02:00Z',
  }]);
  setApiResponse('/api/v5/work-items/wi-1/artifacts', graph);
  renderApp('/work-items/wi-1');

  expect(await screen.findByRole('heading', { name: '验证' })).toBeInTheDocument();
  const detail = document.querySelector('.stage-detail') as HTMLElement;
  expect(within(detail).getByRole('heading', { name: '验证报告' })).toBeInTheDocument();
  expect(within(detail).getByText('验证通过')).toBeInTheDocument();
  expect(within(detail).getByText('npm test')).toBeInTheDocument();

  for (const [action, label, note] of [
    ['release_approved', '进入发布', ''],
    ['validation_rework_coding', '打回 Coding', '补充实现'],
    ['validation_rework_planning', '打回 Planning', '调整计划'],
  ]) {
    fireEvent.click(screen.getByRole('button', { name: label }));
    const dialog = screen.getByRole('dialog', { name: `确认${label}？` });
    if (note) fireEvent.change(within(dialog).getByLabelText('修订意见（必填）'), { target: { value: note } });
    fireEvent.click(within(dialog).getByRole('button', { name: label }));
    await waitFor(() => {
      const call = vi.mocked(fetch).mock.calls.find(([path, init]) =>
        String(path).endsWith(`/signals/${action}`) && init?.method === 'POST');
      expect(JSON.parse(String(call?.[1]?.body))).toMatchObject({
        artifactRef: resultArtifactRefs.validation,
        ...(note ? { note } : {}),
      });
    });
  }
});

test('pre V22 validation passed rework binds the approved coding head', async () => {
  const approvedCodingRef = {
    artifactId: 'legacy-result-code', artifactType: 'CODING', version: 3, contentHash: 'legacy-code-hash',
    rootArtifactId: 'legacy-result-product', parentArtifactId: 'legacy-result-plan', status: 'APPROVED',
  };
  setApiResponse('/api/v5/work-items/wi-1', {
    workItemId: 'wi-1', systemId: 'alpha-system', title: '旧链路验证返工', lifecycleStatus: 'validation_passed',
    currentStage: '等待负责人决定', waitingFor: 'owner', canControl: true,
    availableActions: ['validation_rework_coding', 'validation_rework_planning'], lastAppliedSequence: 8,
  });
  setApiResponse('/api/v5/work-items/wi-1/artifacts', {
    rootArtifactId: 'legacy-result-product',
    nodes: [{
      ref: approvedCodingRef, content: { summary: '旧链路代码结果', repoChanges: [] },
      systemId: 'alpha-system', prdId: 'prd-1', workItemId: 'wi-1', caseId: 'case-1',
      createdBy: 'worker', createdAt: '2026-08-05T12:01:00Z',
    }],
    edges: [], effectiveHeads: { CODING: approvedCodingRef },
  });
  renderApp('/work-items/wi-1');

  for (const [action, label] of [
    ['validation_rework_coding', '打回 Coding'],
    ['validation_rework_planning', '打回 Planning'],
  ]) {
    fireEvent.click(await screen.findByRole('button', { name: label }));
    const dialog = screen.getByRole('dialog', { name: `确认${label}？` });
    fireEvent.change(within(dialog).getByLabelText('修订意见（必填）'), { target: { value: '旧链路返工' } });
    fireEvent.click(within(dialog).getByRole('button', { name: label }));
    await waitFor(() => {
      const call = vi.mocked(fetch).mock.calls.find(([path, init]) =>
        String(path).endsWith(`/signals/${action}`) && init?.method === 'POST');
      expect(JSON.parse(String(call?.[1]?.body))).toMatchObject({ artifactRef: approvedCodingRef });
    });
  }
});

test('completed pre V22 case shows compatibility notice without fabricating release artifact', async () => {
  setApiResponse('/api/v5/work-items/wi-1', {
    workItemId: 'wi-1', systemId: 'alpha-system', title: '旧链路已完成', lifecycleStatus: 'completed',
    currentStage: '已完成', waitingFor: '', canControl: true, availableActions: [], lastAppliedSequence: 20,
  });
  renderApp('/work-items/wi-1');

  const chain = await screen.findByRole('region', { name: '产物链' });
  expect(within(chain).getByText(/历史兼容链路/)).toHaveTextContent('系统不会伪造或回填发布产物');
  expect(within(chain).queryByText(/Release v/)).not.toBeInTheDocument();
});

test('completed result chain reports a missing release manifest as an anomaly', async () => {
  setApiResponse('/api/v5/work-items/wi-1', {
    workItemId: 'wi-1', systemId: 'alpha-system', title: '发布产物异常', lifecycleStatus: 'completed',
    currentStage: '已完成', waitingFor: '', canControl: true, availableActions: [], lastAppliedSequence: 20,
  });
  setApiResponse('/api/v5/work-items/wi-1/artifacts', resultArtifactGraph('PASSED', false));
  renderApp('/work-items/wi-1');

  const chain = await screen.findByRole('region', { name: '产物链' });
  expect(within(chain).getByRole('alert')).toHaveTextContent('发布清单缺失');
  expect(within(chain).queryByText(/历史兼容链路/)).not.toBeInTheDocument();
});

test('validation result actions submit the exact validation artifact', async () => {
  const graph = resultArtifactGraph('FAILED', false);
  setApiResponse('/api/v5/work-items/wi-1', {
    workItemId: 'wi-1', systemId: 'alpha-system', title: '验证结果返工', lifecycleStatus: 'validation_failed',
    currentStage: '验证失败', waitingFor: 'owner', canControl: true,
    availableActions: ['validation_retry', 'validation_rework_coding', 'validation_rework_planning'],
    lastAppliedSequence: 12,
  });
  setApiResponse('/api/v5/work-items/wi-1/events', [{
    sequence: 12, eventId: 'validation-failed', eventType: 'ValidationFailed',
    payloadJson: JSON.stringify({ artifactRef: resultArtifactRefs.validation, failedCommand: 'npm test' }),
    createdAt: '2026-08-05T12:02:00Z',
  }]);
  setApiResponse('/api/v5/work-items/wi-1/artifacts', graph);
  renderApp('/work-items/wi-1');

  for (const [action, label, note] of [
    ['validation_retry', '重新验证', ''],
    ['validation_rework_coding', '打回 Coding', '修复测试失败'],
    ['validation_rework_planning', '打回 Planning', '调整验证方案'],
  ]) {
    fireEvent.click(await screen.findByRole('button', { name: label }));
    const dialog = screen.getByRole('dialog', { name: `确认${label}？` });
    if (note) fireEvent.change(within(dialog).getByLabelText('修订意见（必填）'), { target: { value: note } });
    fireEvent.click(within(dialog).getByRole('button', { name: label }));
    await waitFor(() => {
      const call = vi.mocked(fetch).mock.calls.find(([path, init]) =>
        String(path).endsWith(`/signals/${action}`) && init?.method === 'POST');
      expect(JSON.parse(String(call?.[1]?.body))).toMatchObject({
        artifactRef: resultArtifactRefs.validation,
        ...(note ? { note } : {}),
      });
    });
  }
});

test('release recovery actions submit the exact validation artifact', async () => {
  const graph = resultArtifactGraph('PASSED', false);
  setApiResponse('/api/v5/work-items/wi-1', {
    workItemId: 'wi-1', systemId: 'alpha-system', title: '发布恢复', lifecycleStatus: 'worker_blocked',
    currentStage: '发布受阻', waitingFor: 'owner', canControl: true,
    availableActions: ['release_retry', 'release_revalidate', 'release_rework_coding'],
    lastAppliedSequence: 18,
  });
  setApiResponse('/api/v5/work-items/wi-1/events', [{
    sequence: 18, eventId: 'release-blocked', eventType: 'WorkerBlocked',
    payloadJson: JSON.stringify({ reason: 'release_failed', failedPhase: 'release' }),
    createdAt: '2026-08-05T12:03:00Z',
  }]);
  setApiResponse('/api/v5/work-items/wi-1/artifacts', graph);
  renderApp('/work-items/wi-1');

  for (const [action, label, note] of [
    ['release_retry', '重试发布', ''],
    ['release_revalidate', '退回 Validation', ''],
    ['release_rework_coding', '打回 Coding', '修复发布问题'],
  ]) {
    fireEvent.click(await screen.findByRole('button', { name: label }));
    const dialog = screen.getByRole('dialog', { name: `确认${label}？` });
    if (note) fireEvent.change(within(dialog).getByLabelText('修订意见（必填）'), { target: { value: note } });
    fireEvent.click(within(dialog).getByRole('button', { name: label }));
    await waitFor(() => {
      const call = vi.mocked(fetch).mock.calls.find(([path, init]) =>
        String(path).endsWith(`/signals/${action}`) && init?.method === 'POST');
      expect(JSON.parse(String(call?.[1]?.body))).toMatchObject({
        artifactRef: resultArtifactRefs.validation,
        ...(note ? { note } : {}),
      });
    });
  }
});

test('context refresh binds the effective approved product head', async () => {
  setApiResponse('/api/v5/work-items/wi-1', {
    workItemId: 'wi-1', systemId: 'alpha-system', title: '登录页上下文刷新',
    lifecycleStatus: 'worker_blocked', currentStage: '执行受阻', waitingFor: 'owner',
    canControl: true, availableActions: ['rework_with_latest_context'], lastAppliedSequence: 8,
  });
  setApiResponse('/api/v5/work-items/wi-1/events', [{
    sequence: 8,
    eventId: 'evt-context-stale',
    eventType: 'WorkerBlocked',
    payloadJson: JSON.stringify({
      reason: 'context_stale', failedPhase: 'planning',
    }),
    createdAt: '2026-07-05T12:01:00Z',
  }]);
  renderApp('/work-items/wi-1');

  fireEvent.click(await screen.findByRole('button', { name: '刷新需求上下文并重新规划' }));
  fireEvent.click(within(screen.getByRole('dialog')).getByRole(
    'button', { name: '刷新需求上下文并重新规划' },
  ));

  await waitFor(() => {
    const call = vi.mocked(fetch).mock.calls.find(([path, init]) =>
      String(path).endsWith('/signals/rework_with_latest_context') && init?.method === 'POST');
    expect(JSON.parse(String(call?.[1]?.body))).toMatchObject({
      artifactRef: {
        artifactId: 'art-product-1', artifactType: 'PRODUCT', version: 1,
        contentHash: 'product-hash', status: 'APPROVED',
      },
    });
  });
});

test('work item detail keeps the active revision while hiding unmatched revision history', async () => {
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
  expect(screen.getByText(/第 1 轮修订中：提示放到输入框下方/)).toBeInTheDocument();
  expect(screen.queryByRole('heading', { name: '修订历史' })).not.toBeInTheDocument();
  expect(screen.queryByText(/未找到带 artifactRef 的 ModificationCompleted/)).not.toBeInTheDocument();
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
      planRevision: 1,
      planMarkdown: '# 修改计划\n\n- 移动登录错误提示\n- 保持后端接口不变\n\n证据：src/login.tsx:LoginForm',
      baseRevisions: { frontend: 'abc123' },
    }) },
  ]);
  setApiResponse('/api/v5/work-items/wi-1/artifacts', {
    rootArtifactId: 'art-product-1',
    nodes: [{
      ref: {
        artifactId: 'art-plan-review', artifactType: 'PLANNING', version: 1, contentHash: 'plan-review-hash',
        rootArtifactId: 'art-product-1', parentArtifactId: 'art-product-1', status: 'PROPOSED',
      },
      systemId: 'alpha-system', prdId: 'prd-1', workItemId: 'wi-1', caseId: 'case-1',
      content: {
        planMarkdown: '# 修改计划\n\n- 移动登录错误提示\n- 保持后端接口不变\n\n证据：src/login.tsx:LoginForm',
        baseRevisions: { frontend: 'abc123' },
      },
      createdBy: 'worker', createdAt: '2026-07-05T11:20:00Z',
    }],
    edges: [],
    effectiveHeads: {},
  });
  setApiResponse('/api/v5/artifacts/art-plan-review', {
    artifact: {
      ref: {
        artifactId: 'art-plan-review', artifactType: 'PLANNING', version: 1, contentHash: 'plan-review-hash',
        rootArtifactId: 'art-product-1', parentArtifactId: 'art-product-1', status: 'PROPOSED',
      },
      systemId: 'alpha-system', prdId: 'prd-1', workItemId: 'wi-1', caseId: 'case-1',
      content: {
        planMarkdown: '# 修改计划\n\n- 移动登录错误提示\n- 保持后端接口不变\n\n证据：src/login.tsx:LoginForm',
        baseRevisions: { frontend: 'abc123' },
      },
      createdBy: 'worker', createdAt: '2026-07-05T11:20:00Z',
    },
    transitions: [],
    evidence: [],
  });

  renderApp('/work-items/wi-1');

  expect(await screen.findByRole('heading', { name: 'Coding Plan · 第 1 版' })).toBeInTheDocument();
  const phases = screen.getByRole('list', { name: '计划审批与代码开发进度' });
  expect(within(phases).getByText('计划审批')).toBeInTheDocument();
  expect(within(phases).getByText('等待负责人')).toBeInTheDocument();
  expect(within(phases).getByText('代码开发')).toBeInTheDocument();
  expect(within(phases).getByText('等待计划批准')).toBeInTheDocument();
  expect(screen.getByRole('heading', { level: 1, name: '修改计划' })).toBeInTheDocument();
  expect(screen.getAllByText(/移动登录错误提示/).length).toBeGreaterThan(0);
  expect(screen.getAllByText(/src\/login.tsx:LoginForm/).length).toBeGreaterThan(0);
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
    expect(JSON.parse(String(call?.[1]?.body))).toMatchObject({
      note: '不要改接口，只调整提示位置',
      artifactRef: {
        artifactId: 'art-plan-review', artifactType: 'PLANNING', version: 1,
        contentHash: 'plan-review-hash', status: 'PROPOSED',
      },
    });
  });
});

test('work item detail shows repository agent completion metadata', async () => {
  renderApp('/work-items/wi-1');

  await screen.findByRole('heading', { name: '登录页错误提示' });
  fireEvent.click(screen.getByText('查看完整执行流程'));
  fireEvent.click(screen.getByRole('button', { name: /计划审批与代码开发/ }));
  fireEvent.click(screen.getByText('查看 Agent 执行详情'));
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

  fireEvent.click(await screen.findByText('查看 Agent 执行详情'));
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

  expect((await screen.findByText('查看完整执行流程')).closest('details')).not.toHaveAttribute('open');
  expect(screen.getByRole('heading', { name: '代码确认' })).toBeInTheDocument();
  fireEvent.click(screen.getByText('查看完整执行流程'));
  const current = await screen.findByRole('button', { name: /代码确认/ });
  expect(current).toHaveAttribute('aria-current', 'step');
  expect(screen.queryByText('原始 JSON')).not.toBeInTheDocument();
  fireEvent.click(screen.getByRole('button', { name: '事件审计' }));
  expect((await screen.findAllByText('原始 JSON')).length).toBe(3);
  expect(screen.getByText('CodingAttemptStarted')).toBeInTheDocument();
  expect(screen.queryByText('Causation ID')).not.toBeInTheDocument();
  fireEvent.click(screen.getAllByText('原始 JSON')[2]);
  expect(screen.queryByText(/contentHash/)).not.toBeInTheDocument();
  expect(screen.queryByText(/session-internal/)).not.toBeInTheDocument();
  expect(screen.queryByText(/token-internal/)).not.toBeInTheDocument();
  expect(screen.queryByText(/transition-internal/)).not.toBeInTheDocument();
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

test('artifact memory candidate can be reviewed before it becomes active', async () => {
  renderApp('/memory');

  expect(await screen.findByText('登录页样式决策')).toBeInTheDocument();
  expect(document.querySelector('.memory-category')).toHaveTextContent('决策');
  expect(screen.getByRole('link', { name: /来源 PlanningArtifact v2/ })).toHaveAttribute('href', '/work-items/wi-1');
  fireEvent.click(screen.getByRole('button', { name: '审核并确认' }));
  expect(screen.getByRole('dialog')).toHaveAttribute('open');
  expect(within(screen.getByRole('dialog')).getAllByRole('option').map((option) => option.textContent))
    .toEqual(['项目决策', '项目约束', '整个项目', '仅当前产物链']);
  expect(await screen.findByLabelText('适用范围')).toHaveValue('PROJECT');
  fireEvent.change(screen.getByLabelText('适用范围'), { target: { value: 'ARTIFACT_LINEAGE' } });
  fireEvent.click(await screen.findByRole('checkbox', { name: /登录页/ }));
  fireEvent.change(screen.getByLabelText('标题'), { target: { value: '登录页视觉决策' } });
  fireEvent.click(screen.getByRole('button', { name: '确认并生效' }));

  await waitFor(() => {
    expect(screen.queryByText('登录页样式决策')).not.toBeInTheDocument();
  });
  const call = vi.mocked(fetch).mock.calls.find(([path, init]) =>
    String(path).endsWith('/memory/candidates/candidate-1/approve') && init?.method === 'POST');
  expect(JSON.parse(String(call?.[1]?.body))).toMatchObject({
    memoryType: 'DECISION',
    applicability: 'ARTIFACT_LINEAGE',
    targetRefs: ['page-login'],
  });
});

test('legacy memory remains archived without raw lifecycle payload', async () => {
  renderApp('/memory');

  fireEvent.click(await screen.findByRole('button', { name: '历史记录 1' }));

  expect(await screen.findByText('旧记忆已归档')).toBeInTheDocument();
  expect(screen.getByText(/缺少 Artifact 来源/)).toBeInTheDocument();
  expect(screen.queryByText(/ModificationCompleted/)).not.toBeInTheDocument();
});

test('work item detail links to artifact-driven project memory instead of manual creation', async () => {
  renderApp('/work-items/wi-1');

  const link = await screen.findByRole('link', { name: '查看项目记忆' });
  expect(link).toHaveAttribute('href', '/memory');
  expect(screen.queryByRole('button', { name: '沉淀为记忆' })).not.toBeInTheDocument();
});
