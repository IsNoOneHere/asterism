import { expect, test } from 'vitest';
import { WorkItem, WorkItemEvent } from '../src/api/client';
import { buildWorkItemFlow, FlowStageId } from '../src/workItemFlow';

const workItem = (lifecycleStatus: string, values: Partial<WorkItem> = {}) => ({
  workItemId: 'WI202607170001', systemId: 'alpha-system', prdId: 'prd-1', caseId: 'case-1', title: '跨仓修改',
  lifecycleStatus, approvalStatus: 'approved', executionAllowed: true, currentStage: '', waitingFor: '',
  canControl: true, availableActions: [], ...values,
} as WorkItem);

const event = (sequence: number, eventType: string, payload: Record<string, unknown> = {}, values: Partial<WorkItemEvent> = {}) => ({
  sequence, eventId: `evt-${sequence}`, eventType, payloadJson: JSON.stringify(payload), createdAt: `2026-07-17T0${Math.min(sequence, 9)}:00:00Z`, ...values,
} as WorkItemEvent);

test('projects out-of-order multi-agent and multi-repository events into one seven-stage flow', () => {
  // 输入故意乱序，确保页面不会依赖调用方偶然保持的数组顺序。
  const flow = buildWorkItemFlow(workItem('waiting_merge', { waitingFor: 'gitlab' }), [
    event(11, 'MergeRequestMerged', { repo: 'backend', mrIid: 21, mrUrl: 'https://gitlab/backend/21' }),
    event(1, 'OwnerApprovalRequested'),
    event(3, 'CodingAttemptStarted', { architecture: 'claude_sdk_team', supervisor: { role: 'developer', engine: 'claude_sdk_team' }, repositories: ['backend', 'frontend'] }),
    event(2, 'WorkItemActivated'),
    event(4, 'AgentStageCompleted', { stageIndex: 1, role: 'backend-agent', repo: 'backend', engine: 'claude_sdk_team', summary: '后端完成', changedPaths: ['src/Api.java'] }),
    event(5, 'AgentStageCompleted', { stageIndex: 2, role: 'frontend-agent', repo: 'frontend', engine: 'claude_sdk_team', summary: '前端完成', changedPaths: ['src/App.tsx'] }),
    event(6, 'ModificationCompleted', { summary: '跨仓修改完成', repoDiffs: [{ repo: 'backend', diffPatch: 'diff --git a/src/Api.java b/src/Api.java\n+x' }, { repo: 'frontend', diffPatch: 'diff --git a/src/App.tsx b/src/App.tsx\n+y' }] }),
    event(7, 'PatchApplied'),
    event(8, 'ValidationPassed', { commands: [{ repo: 'backend', command: 'mvn test', exitCode: 0 }] }),
    event(9, 'MergeRequestCreated', { repo: 'backend', branch: 'wi/backend', commitHash: 'abc12345', mrIid: 21, mrUrl: 'https://gitlab/backend/21' }, { causationId: 'publish-1:mr:backend:21' }),
    event(10, 'MergeRequestCreated', { repo: 'frontend', branch: 'wi/frontend', commitHash: 'def67890', mrIid: 34, mrUrl: 'https://gitlab/frontend/34' }, { causationId: 'publish-1:mr:frontend:34' }),
    event(12, 'FutureEvent', { ignored: true }),
  ]);

  expect(flow.stages).toHaveLength(7);
  expect(flow.events.map((item) => item.sequence)).toEqual([1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12]);
  expect(flow.currentStageId).toBe('release');
  expect(flow.stages.find((stage) => stage.id === 'release')).toMatchObject({ status: 'waiting', waitingFor: 'GitLab' });
  expect(flow.stages.find((stage) => stage.id === 'execution')?.agents).toEqual(expect.arrayContaining([
    expect.objectContaining({ role: 'backend-agent', repo: 'backend', engine: 'claude_sdk_team', status: 'completed' }),
    expect.objectContaining({ role: 'frontend-agent', repo: 'frontend', engine: 'claude_sdk_team', status: 'completed' }),
  ]));
  expect(flow.repositories).toEqual(expect.arrayContaining([
    expect.objectContaining({ repo: 'backend', branch: 'wi/backend', commitHash: 'abc12345', mrIid: 21, status: 'merged' }),
    expect.objectContaining({ repo: 'frontend', branch: 'wi/frontend', commitHash: 'def67890', mrIid: 34, status: 'opened' }),
  ]));
  expect(flow.stages.flatMap((stage) => stage.events).some((item) => item.eventType === 'FutureEvent')).toBe(false);
});

test('projects Claude SDK Supervisor and native subagents without a Planner node', () => {
  const flow = buildWorkItemFlow(workItem('modification_completed', { waitingFor: 'owner' }), [
    event(1, 'WorkItemActivated'),
    event(2, 'CodingAttemptStarted', {
      architecture: 'claude_sdk_team', supervisor: { role: 'developer', engine: 'claude_sdk_team' },
      repositories: ['backend', 'frontend'],
    }),
    event(3, 'AgentStageCompleted', {
      stageIndex: 1, role: 'backend-dev', repo: 'backend', engine: 'claude_sdk_team',
      summary: '后端完成', changedPaths: ['src/Api.java'], agentId: 'agent-back',
    }),
    event(4, 'AgentStageCompleted', {
      stageIndex: 2, role: 'frontend-dev', repo: 'frontend', engine: 'claude_sdk_team',
      summary: '前端完成', changedPaths: ['src/App.tsx'], agentId: 'agent-front',
    }),
    event(5, 'ModificationCompleted', {
      executionProvider: 'claude_sdk_team', sessionId: 'session-team',
      repoDiffs: [
        { repo: 'backend', diffPatch: 'diff --git a/src/Api.java b/src/Api.java\n+x' },
        { repo: 'frontend', diffPatch: 'diff --git a/src/App.tsx b/src/App.tsx\n+y' },
      ],
    }),
  ]);

  expect(flow.currentStageId).toBe('patch');
  expect(flow.stages.find((stage) => stage.id === 'execution')?.agents).toEqual([
    expect.objectContaining({ role: 'developer · Supervisor', engine: 'claude_sdk_team', status: 'completed' }),
    expect.objectContaining({ role: 'backend-dev', repo: 'backend', status: 'completed' }),
    expect.objectContaining({ role: 'frontend-dev', repo: 'frontend', status: 'completed' }),
  ]);
  expect(flow.repositories.map((repo) => repo.repo)).toEqual(['backend', 'frontend']);
});

test('projects the latest human-reviewable coding plan without turning evidence into scope', () => {
  const flow = buildWorkItemFlow(workItem('activated', { waitingFor: 'owner' }), [
    event(1, 'WorkItemActivated'),
    event(2, 'CodingPlanStarted', { planRevision: 1 }),
    event(3, 'CodingPlanProposed', {
      planRevision: 1,
      planMarkdown: '# 计划\n\n- 把错误提示放到输入框下方\n- 保持接口不变',
      baseRevisions: { frontend: 'abc123' },
    }),
  ]);

  expect(flow.codingPlan).toEqual(expect.objectContaining({
    revision: 1, status: 'proposed',
    planMarkdown: '# 计划\n\n- 把错误提示放到输入框下方\n- 保持接口不变',
    baseRevisions: { frontend: 'abc123' },
  }));
  expect(flow.stages.find((stage) => stage.id === 'execution')).toMatchObject({
    status: 'waiting', waitingFor: '系统负责人',
  });
});

test.each([
  ['coding_attempt_failed', 'execution', 'Claude SDK Coding Attempt 执行失败'],
  ['patch_apply_failed', 'patch', 'Patch 应用失败'],
  ['test_failed', 'validation', '自动检查失败'],
  ['mr_create_failed', 'release', 'Git 提交或 MR 创建失败'],
] as Array<[string, FlowStageId, string]>)('maps WorkerBlocked reason %s to %s', (reason, stageId, summary) => {
  const flow = buildWorkItemFlow(workItem('worker_blocked'), [event(1, 'WorkItemActivated'), event(2, 'WorkerBlocked', { reason, detail: '失败详情' })]);
  expect(flow.currentStageId).toBe(stageId);
  expect(flow.stages.find((stage) => stage.id === stageId)).toMatchObject({ status: 'failed', failureReason: summary, waitingFor: '系统负责人' });
  expect(flow.stages.find((stage) => stage.id === stageId)?.failureReason).not.toContain('失败详情');
});

test('falls back to the lifecycle phase opened by the latest boundary event', () => {
  const flow = buildWorkItemFlow(workItem('worker_blocked'), [
    event(1, 'OwnerApprovalRequested'), event(2, 'WorkItemActivated'), event(3, 'WorkerBlocked', { reason: 'unexpected_failure' }),
  ]);
  expect(flow.currentStageId).toBe('execution');
});

test('keeps retry history while the main graph shows only the latest attempt', () => {
  const flow = buildWorkItemFlow(workItem('modification_completed', { waitingFor: 'owner' }), [
    event(1, 'WorkItemActivated'), event(2, 'CodingAttemptStarted', { supervisor: { role: 'developer', engine: 'claude_sdk_team' } }),
    event(3, 'ModificationCompleted', { diffPatch: 'diff --git a/a.ts b/a.ts\n+x' }), event(4, 'PatchApplied'),
    event(5, 'ValidationFailed', { failedCommand: 'npm test', stderrTail: '断言失败' }),
    event(6, 'ReworkStarted'), event(7, 'CodingAttemptStarted', { supervisor: { role: 'developer', engine: 'claude_sdk_team' } }),
    event(8, 'ModificationCompleted', { diffPatch: 'diff --git a/a.ts b/a.ts\n+y' }),
  ]);

  expect(flow.stages).toHaveLength(7);
  expect(flow.currentStageId).toBe('patch');
  expect(flow.attempts).toHaveLength(2);
  expect(flow.attempts[0]).toMatchObject({ status: 'failed', failureReason: '自动检查未通过：npm test' });
  expect(flow.attempts[1]).toMatchObject({ status: 'running' });
  expect(flow.repositories[0].diffPatch).toContain('+y');
  expect(flow.repositories[0].diffPatch).not.toContain('+x');
  expect(flow.stages.find((stage) => stage.id === 'validation')).toMatchObject({ events: [] });
  expect(flow.stages.find((stage) => stage.id === 'validation')?.failureReason).toBeUndefined();
});

test('uses only the newest merge-request attempt and inherits created metadata', () => {
  const flow = buildWorkItemFlow(workItem('waiting_merge', { waitingFor: 'gitlab' }), [
    event(1, 'MergeRequestCreated', { repo: 'frontend', branch: 'old', commitHash: 'oldhash', mrIid: 1, mrUrl: 'https://gitlab/1' }, { causationId: 'old:mr:frontend:1' }),
    event(2, 'MergeRequestClosed', { repo: 'frontend', mrIid: 1, mrUrl: 'https://gitlab/1', reason: 'mr_closed' }),
    event(3, 'ReworkStarted'),
    event(4, 'MergeRequestCreated', { repo: 'frontend', branch: 'new', commitHash: 'newhash', mrIid: 2, mrUrl: 'https://gitlab/2' }, { causationId: 'new:mr:frontend:2' }),
    event(5, 'MergeRequestMerged', { repo: 'frontend', mrIid: 2, mrUrl: 'https://gitlab/2' }),
  ]);

  expect(flow.repositories).toEqual([expect.objectContaining({ repo: 'frontend', branch: 'new', commitHash: 'newhash', mrIid: 2, mrUrl: 'https://gitlab/2', status: 'merged' })]);
  expect(flow.stages.find((stage) => stage.id === 'release')?.failureReason).toBeUndefined();
  expect(flow.attempts[0].failureReason).toBe('GitLab 合并请求已关闭（frontend）');
});

test('uses lifecycle boundaries for exact stage times', () => {
  const flow = buildWorkItemFlow(workItem('completed', {
    createdAt: '2026-07-17T00:00:00Z', updatedAt: '2026-07-17T08:00:00Z',
  }), [
    event(1, 'OwnerApprovalRequested'),
    event(2, 'WorkItemActivated'),
    event(3, 'CodingAttemptStarted', { supervisor: { role: 'developer', engine: 'claude_sdk_team' } }),
    event(4, 'ModificationCompleted', { diffPatch: 'diff --git a/a.ts b/a.ts\n+x' }),
    event(5, 'PatchApplied'),
    event(6, 'ValidationPassed', { commands: [{ command: 'npm test', exitCode: 0 }] }),
    event(7, 'MergeRequestCreated', { repo: 'main', mrIid: 1 }),
    event(8, 'ReleaseCompleted', { repo: 'main' }),
  ]);

  expect(flow.stages.map(({ id, startedAt, completedAt, durationMs }) => ({ id, startedAt, completedAt, durationMs }))).toEqual([
    { id: 'created', startedAt: '2026-07-17T00:00:00Z', completedAt: '2026-07-17T01:00:00Z', durationMs: 3_600_000 },
    { id: 'approval', startedAt: '2026-07-17T01:00:00Z', completedAt: '2026-07-17T02:00:00Z', durationMs: 3_600_000 },
    { id: 'execution', startedAt: '2026-07-17T02:00:00Z', completedAt: '2026-07-17T04:00:00Z', durationMs: 7_200_000 },
    { id: 'patch', startedAt: '2026-07-17T04:00:00Z', completedAt: '2026-07-17T05:00:00Z', durationMs: 3_600_000 },
    { id: 'validation', startedAt: '2026-07-17T05:00:00Z', completedAt: '2026-07-17T06:00:00Z', durationMs: 3_600_000 },
    { id: 'release', startedAt: '2026-07-17T06:00:00Z', completedAt: '2026-07-17T08:00:00Z', durationMs: 7_200_000 },
    { id: 'completed', startedAt: '2026-07-17T08:00:00Z', completedAt: '2026-07-17T08:00:00Z', durationMs: 0 },
  ]);
});

test('shows the supervisor running while completed subagents accumulate', () => {
  const flow = buildWorkItemFlow(workItem('activated', { waitingFor: 'worker' }), [
    event(1, 'WorkItemActivated'),
    event(2, 'CodingAttemptStarted', { supervisor: { role: 'developer', engine: 'claude_sdk_team' } }),
    event(3, 'AgentStageCompleted', { stageIndex: 1, role: 'frontend', summary: '前端完成' }),
  ]);

  expect(flow.stages.find((stage) => stage.id === 'execution')).toMatchObject({ status: 'running', waitingFor: 'Agent' });
  expect(flow.stages.find((stage) => stage.id === 'execution')?.agents).toEqual([
    expect.objectContaining({ role: 'developer · Supervisor', status: 'running' }),
    expect.objectContaining({ role: 'frontend', status: 'completed' }),
  ]);
});

test('starts a clean supervisor attempt after rework without retaining the old error', () => {
  const flow = buildWorkItemFlow(workItem('activated', { waitingFor: 'worker' }), [
    event(1, 'WorkItemActivated'),
    event(2, 'CodingAttemptStarted', { supervisor: { role: 'developer', engine: 'claude_sdk_team' } }),
    event(3, 'AgentStageCompleted', { stageIndex: 1, role: 'frontend', summary: '前端完成', changedPaths: ['web/app.ts'] }),
    event(4, 'WorkerBlocked', { reason: 'coding_attempt_failed', detail: 'ActivityError | internal trace' }),
    event(5, 'ReworkStarted'),
    event(6, 'CodingAttemptStarted', { supervisor: { role: 'developer', engine: 'claude_sdk_team' } }),
  ]);

  const execution = flow.stages.find((stage) => stage.id === 'execution')!;
  expect(execution).toMatchObject({ status: 'running', waitingFor: 'Agent' });
  expect(execution.failureReason).toBeUndefined();
  expect(execution.events.map((item) => item.eventType)).toEqual(['ReworkStarted', 'CodingAttemptStarted']);
  expect(execution.agents).toEqual([
    expect.objectContaining({ role: 'developer · Supervisor', status: 'running' }),
  ]);
  expect(flow.attempts).toHaveLength(2);
  expect(flow.attempts[0]).toMatchObject({ status: 'failed', failureReason: 'Claude SDK Coding Attempt 执行失败' });
});

test('projects completed and blocked revision rounds into auditable history', () => {
  const flow = buildWorkItemFlow(workItem('worker_blocked', { waitingFor: 'owner' }), [
    event(1, 'ModificationCompleted', { diffPatch: 'diff --git a/a.ts b/a.ts\n+first' }),
    event(2, 'PatchRejected', { note: '提示位置不对' }),
    event(3, 'ReworkStarted', { revision: 1 }),
    event(4, 'RevisionRequested', {
      revision: 1, revisionMode: 'incremental', note: '提示放到输入框下方', requestedBy: 'owner-1', phase: 'review',
      diffSummary: [{ repo: 'frontend', changedPaths: ['src/login.tsx'] }],
    }),
    event(5, 'CodingAttemptStarted', { supervisor: { role: 'developer', engine: 'claude_sdk_team' }, revision: 1 }),
    event(6, 'ModificationCompleted', {
      revision: 1, revisionMode: 'full', summary: '已调整错误提示', diffPatch: 'diff --git a/a.ts b/a.ts\n+second',
    }),
    event(7, 'PatchRejected', { note: '颜色不对' }),
    event(8, 'ReworkStarted', { revision: 2 }),
    event(9, 'RevisionRequested', {
      revision: 2, revisionMode: 'incremental', note: '使用现有错误色', requestedBy: 'owner-1', phase: 'merge',
    }),
    event(10, 'WorkerBlocked', { reason: 'coding_attempt_failed', revision: 2 }),
  ]);

  expect(flow.revisions).toEqual([
    expect.objectContaining({ revision: 1, status: 'completed', revisionMode: 'full', diffSummary: '已调整错误提示' }),
    expect.objectContaining({ revision: 2, status: 'failed', revisionMode: 'incremental', phase: 'merge' }),
  ]);
  expect(flow.revisions[0].requestedBy).toBe('owner-1');
  expect(flow.activeRevision).toBeNull();
});

test('uses the ValidationFailed top-level repo for every command', () => {
  const flow = buildWorkItemFlow(workItem('validation_failed', { waitingFor: 'owner' }), [
    event(1, 'CodingAttemptStarted', { supervisor: { role: 'developer', engine: 'claude_sdk_team' } }),
    event(2, 'ModificationCompleted', { repoDiffs: [{ repo: 'frontend', diffPatch: 'diff --git a/web.ts b/web.ts\n+x' }] }),
    event(3, 'PatchApplied'),
    event(4, 'ValidationFailed', {
      repo: 'frontend', failedCommand: 'npm test',
      commands: [{ command: 'npm lint', exitCode: 0 }, { command: 'npm test', exitCode: 1 }],
    }),
  ]);

  expect(flow.checks).toEqual([
    expect.objectContaining({ repo: 'frontend', command: 'npm lint', passed: true }),
    expect.objectContaining({ repo: 'frontend', command: 'npm test', passed: false }),
  ]);
  expect(flow.repositories.map((repo) => repo.repo)).toEqual(['frontend']);
});

test.each([
  ['PatchRejected', 'patch'],
  ['MergeRequestClosed', 'release'],
] as Array<[string, FlowStageId]>)('clears stale %s state when rework starts', (eventType, stageId) => {
  const flow = buildWorkItemFlow(workItem('activated', { waitingFor: 'worker' }), [
    event(1, 'WorkItemActivated'),
    event(2, eventType, eventType === 'MergeRequestClosed' ? { reason: 'mr_closed', repo: 'frontend' } : {}),
    event(3, 'ReworkStarted'),
  ]);

  expect(flow.currentStageId).toBe('execution');
  expect(flow.stages.find((stage) => stage.id === stageId)).toMatchObject({ events: [] });
  expect(flow.stages.find((stage) => stage.id === stageId)?.failureReason).toBeUndefined();
});

test('marks validation skipped only when the event explicitly says so', () => {
  const flow = buildWorkItemFlow(workItem('validation_passed', { waitingFor: 'owner' }), [
    event(1, 'PatchApplied'), event(2, 'ValidationPassed', { skipped: true, commands: [] }),
  ]);
  expect(flow.stages.find((stage) => stage.id === 'validation')?.status).toBe('skipped');
});

test('keeps unknown and malformed events in audit without changing the graph', () => {
  const malformed = { ...event(2, 'FutureEvent'), payloadJson: '{bad json' };
  const flow = buildWorkItemFlow(workItem('waiting_owner_approval', { waitingFor: 'owner' }), [malformed, event(1, 'OwnerApprovalRequested')]);
  expect(flow.currentStageId).toBe('approval');
  expect(flow.events).toHaveLength(2);
  expect(flow.stages.find((stage) => stage.id === 'approval')?.status).toBe('waiting');
});

test.each([
  ['cancelled', 'completed', 'cancelled'],
  ['rejected', 'approval', 'failed'],
] as const)('projects %s as a terminal visible result', (lifecycle, currentStage, currentStatus) => {
  const events = lifecycle === 'cancelled'
    ? [event(1, 'WorkItemActivated'), event(2, 'CaseCancelled')]
    : [event(1, 'OwnerApprovalRequested'), event(2, 'WorkItemRejected')];
  const flow = buildWorkItemFlow(workItem(lifecycle), events);
  expect(flow.currentStageId).toBe(currentStage);
  expect(flow.stages.find((stage) => stage.id === currentStage)?.status).toBe(currentStatus);
});
