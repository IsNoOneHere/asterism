import { WorkItem, WorkItemEvent } from './api/client';

export type FlowStageId = 'created' | 'approval' | 'execution' | 'patch' | 'validation' | 'release' | 'completed';
export type FlowStageStatus = 'pending' | 'running' | 'waiting' | 'completed' | 'failed' | 'skipped' | 'cancelled';

export type AgentStageView = {
  index: number;
  role: string;
  repo: string;
  engine: string;
  status: 'pending' | 'running' | 'completed' | 'failed';
  summary: string;
  changedPaths: string[];
  tokenUsage: Record<string, unknown>;
};

export type ValidationCheckView = {
  repo: string;
  command: string;
  exitCode: number | null;
  stdout: string;
  stderr: string;
  passed: boolean;
};

export type RepositoryFlowView = {
  repo: string;
  diffPatch: string;
  changedPaths: string[];
  agentSummaries: string[];
  checks: ValidationCheckView[];
  branch: string;
  commitHash: string;
  mrIid: number | null;
  mrUrl: string;
  status: 'changed' | 'published' | 'opened' | 'merged' | 'closed' | 'released';
};

export type FlowStage = {
  id: FlowStageId;
  label: string;
  status: FlowStageStatus;
  waitingFor?: string;
  startedAt?: string;
  completedAt?: string;
  durationMs?: number;
  failureReason?: string;
  events: WorkItemEvent[];
  agents?: AgentStageView[];
  repositories?: RepositoryFlowView[];
  checks?: ValidationCheckView[];
};

export type FlowAttempt = {
  number: number;
  status: 'running' | 'completed' | 'failed' | 'cancelled';
  startedAt?: string;
  completedAt?: string;
  failureReason?: string;
  stageIds: FlowStageId[];
  events: WorkItemEvent[];
};

export type ModificationView = {
  summary: string;
  provider: string;
  turns: number | null;
  tokenUsage: Record<string, unknown>;
  diffPatch: string;
  revision: number;
  revisionMode: 'incremental' | 'full';
};

export type RevisionHistoryView = {
  id: string;
  revision: number;
  note: string;
  requestedBy: string;
  requestedAt?: string;
  completedAt?: string;
  diffSummary: string;
  revisionMode: 'incremental' | 'full';
  phase: 'review' | 'merge';
  status: 'running' | 'completed' | 'failed';
};

export type WorkItemFlow = {
  currentStageId: FlowStageId;
  stages: FlowStage[];
  attempts: FlowAttempt[];
  events: WorkItemEvent[];
  modification: ModificationView | null;
  repositories: RepositoryFlowView[];
  checks: ValidationCheckView[];
  revisions: RevisionHistoryView[];
  activeRevision: RevisionHistoryView | null;
};

export const FLOW_STAGES: ReadonlyArray<{ id: FlowStageId; label: string }> = [
  { id: 'created', label: '工作项创建' },
  { id: 'approval', label: '负责人审批' },
  { id: 'execution', label: '计划与执行' },
  { id: 'patch', label: '代码确认' },
  { id: 'validation', label: '验证' },
  { id: 'release', label: 'Git 提交与 MR' },
  { id: 'completed', label: '完成' },
];

const STAGE_INDEX = new Map(FLOW_STAGES.map((stage, index) => [stage.id, index]));
const FAILURE_EVENTS = new Set(['WorkerBlocked', 'PatchApplyBlocked', 'PatchRejected', 'ValidationFailed', 'MergeRequestClosed']);
const FAILURE_REASON_LABELS: Record<string, string> = {
  context_fetch_failed: '获取执行上下文失败',
  coding_attempt_failed: 'Claude SDK Coding Attempt 执行失败',
  execution_failed: 'Agent 执行失败',
  role_scope_violation: 'Agent 修改超出允许范围',
  unknown_repo: '未找到目标仓库',
  patch_apply_failed: 'Patch 应用失败',
  test_failed: '自动检查失败',
  mr_create_failed: 'Git 提交或 MR 创建失败',
  push_failed: 'Git 推送失败',
  release_failed: '发布失败',
  revision_limit_reached: '已达到最大修订轮次，等待负责人决定取消或完整重做',
};
const ATTEMPT_EVENTS = new Set([
  'WorkItemActivated', 'ReworkStarted', 'RevisionRequested', 'CodingAttemptStarted', 'AgentStageCompleted', 'ModificationCompleted',
  'WorkerBlocked', 'PatchApplied', 'PatchApplyBlocked', 'PatchRejected', 'ValidationPassed', 'ValidationFailed',
  'RepositoryReleasePrepared', 'MergeRequestCreated', 'MergeRequestMerged', 'MergeRequestClosed', 'ReleaseCompleted', 'CaseCancelled',
]);

// 只做前端投影：事件先排序，随后单次扫描生成阶段、Agent、仓库和尝试历史。
export function buildWorkItemFlow(workItem: WorkItem, inputEvents: WorkItemEvent[]): WorkItemFlow {
  const events = [...inputEvents].sort((left, right) => left.sequence - right.sequence);
  const stages = FLOW_STAGES.map<FlowStage>(({ id, label }) => ({ id, label, status: 'pending', events: [] }));
  const stageById = new Map(stages.map((stage) => [stage.id, stage]));
  stageById.get('created')!.startedAt = workItem.createdAt;
  const repositories = new Map<string, RepositoryFlowView>();
  const attempts: FlowAttempt[] = [];
  const attemptState: { active: FlowAttempt | null } = { active: null };
  let modification: ModificationView | null = null;
  let agents: AgentStageView[] = [];
  let checks: ValidationCheckView[] = [];
  let lastOperationalStage: FlowStageId = 'approval';
  let lastFailureStage: FlowStageId | null = null;
  let latestMrRoot = '';
  let validationSkipped = false;
  const revisions: RevisionHistoryView[] = [];

  const addAttemptEvent = (event: WorkItemEvent, stageIds: FlowStageId[]) => {
    if (!ATTEMPT_EVENTS.has(event.eventType)) return;
    if (event.eventType === 'ReworkStarted' && attemptState.active?.events.length) {
      attempts.push(attemptState.active);
      attemptState.active = null;
    }
    attemptState.active ??= { number: attempts.length + 1, status: 'running', stageIds: [], events: [] };
    const activeAttempt = attemptState.active;
    activeAttempt.events.push(event);
    activeAttempt.startedAt ??= event.createdAt;
    activeAttempt.stageIds = unique([...activeAttempt.stageIds, ...stageIds]);
    if (FAILURE_EVENTS.has(event.eventType)) {
      activeAttempt.status = 'failed';
      activeAttempt.completedAt = event.createdAt;
      activeAttempt.failureReason = failureReason(event);
    } else if (event.eventType === 'ReleaseCompleted') {
      activeAttempt.status = 'completed';
      activeAttempt.completedAt = event.createdAt;
    } else if (event.eventType === 'CaseCancelled') {
      activeAttempt.status = 'cancelled';
      activeAttempt.completedAt = event.createdAt;
    }
  };

  const resetOperationalStages = () => {
    stages.slice(STAGE_INDEX.get('execution')).forEach((stage) => {
      stage.events = [];
      delete stage.startedAt;
      delete stage.completedAt;
      delete stage.durationMs;
      delete stage.failureReason;
      delete stage.waitingFor;
    });
  };

  for (const event of events) {
    const payload = eventPayload(event);
    if (event.eventType === 'ReworkStarted') {
      // 主图只保留当前尝试；旧尝试仍完整保存在 attempts 和事件审计中。
      resetOperationalStages();
      lastFailureStage = null;
      latestMrRoot = '';
      validationSkipped = false;
      modification = null;
      checks = [];
      agents = [];
      repositories.clear();
    }
    const eventStages = stagesForEvent(event, lastOperationalStage);
    for (const stageId of eventStages) {
      const stage = stageById.get(stageId)!;
      stage.events.push(event);
      stage.startedAt ??= event.createdAt;
    }
    addAttemptEvent(event, eventStages);

    const operationalStage = openedStage(event.eventType)
      ?? [...eventStages].reverse().find((stageId) => !['created', 'completed'].includes(stageId));
    if (operationalStage && event.eventType !== 'WorkerBlocked') lastOperationalStage = operationalStage;
    if (FAILURE_EVENTS.has(event.eventType)) {
      lastFailureStage = eventStages[0] ?? lastOperationalStage;
      const stage = stageById.get(lastFailureStage);
      if (stage) stage.failureReason = failureReason(event);
    }

    switch (event.eventType) {
      case 'OwnerApprovalRequested':
        complete(stageById.get('created')!, event.createdAt);
        stageById.get('approval')!.startedAt ??= event.createdAt;
        break;
      case 'WorkItemActivated':
        complete(stageById.get('approval')!, event.createdAt);
        stageById.get('execution')!.startedAt ??= event.createdAt;
        break;
      case 'WorkItemRejected':
        stageById.get('approval')!.failureReason = failureReason(event) || '负责人已拒绝';
        break;
      case 'CodingAttemptStarted': {
        const supervisor = recordValue(payload?.supervisor);
        agents = [{
          index: 0,
          role: `${stringValue(supervisor?.role) || 'developer'} · Supervisor`,
          repo: '',
          engine: stringValue(supervisor?.engine) || 'claude_sdk_team',
          status: 'running',
          summary: '正在理解需求并调度仓库子 Agent',
          changedPaths: [],
          tokenUsage: {},
        }];
        repositories.clear();
        modification = null;
        checks = [];
        latestMrRoot = '';
        validationSkipped = false;
        break;
      }
      case 'RevisionRequested': {
        const revision = numberValue(payload?.revision) ?? revisions.length + 1;
        revisions.push({
          id: event.eventId || String(event.sequence),
          revision,
          note: stringValue(payload?.note),
          requestedBy: stringValue(payload?.requestedBy ?? payload?.requested_by),
          requestedAt: event.createdAt,
          diffSummary: revisionDiffSummary(payload?.diffSummary ?? payload?.diff_summary),
          revisionMode: revisionMode(payload?.revisionMode ?? payload?.revision_mode),
          phase: stringValue(payload?.phase) === 'merge' ? 'merge' : 'review',
          status: 'running',
        });
        break;
      }
      case 'AgentStageCompleted': {
        const completedAgent = parseAgent(payload);
        if (!completedAgent) break;
        const index = completedAgent.index >= 0
          ? completedAgent.index
          : agents.findIndex((agent) => agent.role === completedAgent.role && agent.status !== 'completed');
        if (index >= 0 && index < agents.length) agents[index] = { ...agents[index], ...completedAgent, index, status: 'completed' };
        else agents.push({ ...completedAgent, index: agents.length, status: 'completed' });
        const repo = ensureRepo(repositories, completedAgent.repo || defaultRepo(agents));
        repo.changedPaths = unique([...repo.changedPaths, ...completedAgent.changedPaths]);
        if (completedAgent.summary) repo.agentSummaries = unique([...repo.agentSummaries, completedAgent.summary]);
        break;
      }
      case 'ModificationCompleted': {
        modification = parseModification(payload);
        if (modification && modification.revision > 0) {
          const revision = [...revisions].reverse().find((item) =>
            item.revision === modification!.revision && item.status === 'running');
          if (revision) {
            revision.completedAt = event.createdAt;
            revision.diffSummary = modification.summary;
            revision.revisionMode = modification.revisionMode;
            revision.status = 'completed';
          }
        }
        agents = agents.map((agent) => ({ ...agent, status: 'completed' }));
        const repoDiffs = arrayRecords(payload?.repoDiffs ?? payload?.repo_diffs);
        if (repoDiffs.length) {
          for (const item of repoDiffs) setRepoDiff(repositories, stringValue(item.repo), stringValue(item.diffPatch ?? item.diff_patch));
        } else if (modification?.diffPatch) {
          setRepoDiff(repositories, defaultRepo(agents), modification.diffPatch);
        }
        complete(stageById.get('execution')!, event.createdAt);
        break;
      }
      case 'WorkerBlocked': {
        const revisionNumber = numberValue(payload?.revision);
        const revision = [...revisions].reverse().find((item) =>
          item.status === 'running' && (revisionNumber === null || item.revision === revisionNumber));
        if (revision) revision.status = 'failed';
        if (lastFailureStage === 'execution') {
          const index = agents.findIndex((agent) => agent.status === 'pending' || agent.status === 'running');
          if (index >= 0 && index < agents.length) agents[index] = { ...agents[index], status: 'failed' };
        }
        break;
      }
      case 'PatchApplied':
        complete(stageById.get('patch')!, event.createdAt);
        stageById.get('validation')!.startedAt ??= event.createdAt;
        break;
      case 'ValidationPassed':
      case 'ValidationFailed': {
        const passed = event.eventType === 'ValidationPassed';
        const nextChecks = parseChecks(payload, passed);
        checks = nextChecks;
        validationSkipped = passed && Boolean(payload?.skipped);
        for (const check of nextChecks) ensureRepo(repositories, check.repo || defaultRepo(agents)).checks.push(check);
        if (passed) {
          complete(stageById.get('validation')!, event.createdAt);
          stageById.get('release')!.startedAt ??= event.createdAt;
        }
        break;
      }
      case 'MergeRequestCreated': {
        const root = String(event.causationId ?? '').split(':mr:')[0];
        if (latestMrRoot && root && root !== latestMrRoot) clearMergeRequestState(repositories);
        if (root) latestMrRoot = root;
        updateMergeRequest(repositories, payload, 'opened');
        break;
      }
      case 'RepositoryReleasePrepared':
        applyPrepared(repositories, payload);
        break;
      case 'MergeRequestMerged':
        updateMergeRequest(repositories, payload, 'merged');
        break;
      case 'MergeRequestClosed':
        updateMergeRequest(repositories, payload, 'closed');
        break;
      case 'ReleaseCompleted':
        applyRelease(repositories, payload);
        complete(stageById.get('release')!, event.createdAt);
        complete(stageById.get('completed')!, event.createdAt);
        break;
    }
  }
  if (attemptState.active?.events.length) attempts.push(attemptState.active);

  const currentStageId = stageForLifecycle(workItem.lifecycleStatus, lastFailureStage, lastOperationalStage);
  applyStageStatuses(stages, workItem, currentStageId, lastOperationalStage, validationSkipped, Boolean(agents.length));
  applyAgentStatuses(agents, stages, currentStageId);
  const repositoryViews = [...repositories.values()];
  stageById.get('execution')!.agents = agents;
  stageById.get('validation')!.checks = checks;
  stageById.get('release')!.repositories = repositoryViews;
  for (const stage of stages) setDuration(stage);

  const activeRevision = [...revisions].reverse().find((revision) => revision.status === 'running') ?? null;
  return { currentStageId, stages, attempts, events, modification, repositories: repositoryViews, checks,
    revisions, activeRevision };
}

export function eventName(eventType: string) {
  return ({
    OwnerApprovalRequested: '已请求负责人审批', OwnerApprovalSignalSubmitted: '负责人已提交审批',
    WorkItemActivated: '工作项已激活', WorkItemRejected: '负责人已拒绝', ReworkStarted: '已开始重新执行',
    RevisionRequested: '已请求人工意见修订',
    CodingAttemptStarted: 'Claude SDK Coding Attempt 已启动',
    AgentStageCompleted: 'Agent 阶段已完成', ModificationCompleted: '修改已完成',
    WorkerBlocked: '执行已阻塞', PatchApplied: 'Patch 已应用', PatchApplyBlocked: 'Patch 应用被阻塞', PatchRejected: 'Patch 已打回',
    ValidationPassed: '验证已通过', ValidationFailed: '验证未通过', MergeRequestCreated: '合并请求已创建',
    MergeRequestMerged: '合并请求已合并', MergeRequestClosed: '合并请求已关闭', ReleaseCompleted: '发布已完成',
    RepositoryReleasePrepared: '仓库发布结果已准备', TemporalActionCompleted: '手动动作已完成', CaseCancelled: '工作项已取消',
  } as Record<string, string>)[eventType] || eventType;
}

export function eventPayload(event: WorkItemEvent): Record<string, unknown> | null {
  try {
    const value = JSON.parse(event.payloadJson || '{}');
    return value && typeof value === 'object' && !Array.isArray(value) ? value as Record<string, unknown> : null;
  } catch {
    return null;
  }
}

export function failureReason(event: WorkItemEvent) {
  const payload = eventPayload(event);
  const repo = stringValue(payload?.repo);
  const suffix = repo ? `（${repo}）` : '';
  if (event.eventType === 'WorkItemRejected') return '负责人已拒绝';
  if (event.eventType === 'PatchRejected') return '代码修改已被负责人打回';
  if (event.eventType === 'ValidationFailed') {
    const command = stringValue(payload?.failedCommand ?? payload?.failed_command);
    return `${command ? `自动检查未通过：${command}` : '验证未通过'}${suffix}`;
  }
  if (event.eventType === 'MergeRequestClosed') return `GitLab 合并请求已关闭${suffix}`;

  const reason = stringValue(payload?.reason);
  if (event.eventType === 'PatchApplyBlocked') {
    const summary = reason.startsWith('empty diff') ? '没有可应用的代码变更'
      : reason.startsWith('unsafe path') ? 'Patch 包含不安全路径'
        : reason.startsWith('forbidden path') ? 'Patch 修改了禁止路径'
          : reason.startsWith('outside allowed paths') ? 'Patch 超出允许路径'
            : 'Patch 无法应用';
    return `${summary}${suffix}`;
  }
  if (event.eventType === 'WorkerBlocked') {
    // 流程页只展示可读摘要，完整异常链保留在事件审计。
    return `${FAILURE_REASON_LABELS[reason] || '执行流程已阻塞'}${suffix}`;
  }
  return reason;
}

function stagesForEvent(event: WorkItemEvent, fallback: FlowStageId): FlowStageId[] {
  switch (event.eventType) {
    case 'OwnerApprovalRequested': return ['created'];
    case 'OwnerApprovalSignalSubmitted':
    case 'WorkItemActivated':
    case 'WorkItemRejected': return ['approval'];
    case 'ReworkStarted':
    case 'RevisionRequested':
    case 'CodingAttemptStarted':
    case 'AgentStageCompleted': return ['execution'];
    case 'ModificationCompleted': return ['execution', 'patch'];
    case 'PatchApplied':
    case 'PatchApplyBlocked':
    case 'PatchRejected': return ['patch'];
    case 'ValidationPassed':
    case 'ValidationFailed': return ['validation'];
    case 'MergeRequestCreated':
    case 'RepositoryReleasePrepared':
    case 'MergeRequestMerged':
    case 'MergeRequestClosed': return ['release'];
    case 'ReleaseCompleted': return ['release', 'completed'];
    case 'CaseCancelled': return ['completed'];
    case 'WorkerBlocked': return [blockedStage(eventPayload(event), fallback)];
    default: return [];
  }
}

function blockedStage(payload: Record<string, unknown> | null, fallback: FlowStageId): FlowStageId {
  const reason = stringValue(payload?.reason).toLowerCase();
  if (/coding|execution|agent|context/.test(reason)) return 'execution';
  if (/patch|diff/.test(reason)) return 'patch';
  if (/validation|test/.test(reason)) return 'validation';
  if (/release|git|mr|merge|push/.test(reason)) return 'release';
  return fallback;
}

function openedStage(eventType: string): FlowStageId | null {
  return ({
    OwnerApprovalRequested: 'approval', WorkItemActivated: 'execution', ReworkStarted: 'execution',
    CodingAttemptStarted: 'execution', AgentStageCompleted: 'execution', ModificationCompleted: 'patch',
    PatchApplied: 'validation', ValidationPassed: 'release', RepositoryReleasePrepared: 'release', MergeRequestCreated: 'release',
    MergeRequestMerged: 'release', MergeRequestClosed: 'release',
  } as Partial<Record<string, FlowStageId>>)[eventType] ?? null;
}

function stageForLifecycle(status: string, failure: FlowStageId | null, fallback: FlowStageId): FlowStageId {
  return ({
    allocated: 'created', waiting_owner_approval: 'approval', activated: 'execution', modification_completed: 'patch',
    worker_blocked: failure ?? fallback, patch_applied: 'validation', patch_rejected: 'patch',
    validation_passed: 'release', validation_failed: 'validation', waiting_merge: 'release', completed: 'completed',
    cancelled: 'completed', rejected: 'approval',
  } as Record<string, FlowStageId>)[status] ?? fallback;
}

function applyStageStatuses(stages: FlowStage[], workItem: WorkItem, current: FlowStageId,
                            cancelledFrom: FlowStageId, validationSkipped: boolean, executionStarted: boolean) {
  const currentIndex = STAGE_INDEX.get(current)!;
  stages.forEach((stage, index) => {
    stage.status = index < currentIndex ? 'completed' : index > currentIndex ? 'pending' : 'running';
  });
  stages[0].status = 'completed';

  if (workItem.lifecycleStatus === 'rejected') {
    stages.forEach((stage, index) => { stage.status = index === 0 ? 'completed' : index === 1 ? 'failed' : 'cancelled'; });
  } else if (workItem.lifecycleStatus === 'cancelled') {
    const cancelledIndex = STAGE_INDEX.get(cancelledFrom) ?? 1;
    stages.forEach((stage, index) => { stage.status = index < cancelledIndex ? 'completed' : 'cancelled'; });
  } else if (['worker_blocked', 'patch_rejected', 'validation_failed'].includes(workItem.lifecycleStatus)) {
    stages[currentIndex].status = 'failed';
  } else if (workItem.lifecycleStatus === 'completed') {
    stages.forEach((stage) => { stage.status = 'completed'; });
  } else if (workItem.lifecycleStatus === 'activated' && executionStarted) {
    stages[currentIndex].status = 'running';
  } else if (workItem.waitingFor || ['allocated', 'waiting_owner_approval', 'modification_completed', 'patch_applied', 'validation_passed', 'waiting_merge'].includes(workItem.lifecycleStatus)) {
    stages[currentIndex].status = 'waiting';
  }

  if (validationSkipped && STAGE_INDEX.get('validation')! <= currentIndex) stages[STAGE_INDEX.get('validation')!].status = 'skipped';
  const currentStage = stages[currentIndex];
  if (currentStage.status === 'failed') currentStage.waitingFor = '系统负责人';
  else if (workItem.lifecycleStatus === 'activated' && !executionStarted) currentStage.waitingFor = '系统负责人';
  else if (currentStage.status === 'running' && current === 'execution') currentStage.waitingFor = 'Agent';
  else if (currentStage.status === 'waiting' || currentStage.status === 'running') currentStage.waitingFor = waitingRoleName(workItem.waitingFor);

  for (let index = 1; index < stages.length; index += 1) {
    if (stages[index - 1].status === 'completed' && !stages[index - 1].completedAt) {
      stages[index - 1].completedAt = stages[index].startedAt;
    }
  }
}

function applyAgentStatuses(agents: AgentStageView[], stages: FlowStage[], current: FlowStageId) {
  const execution = stages[STAGE_INDEX.get('execution')!];
  if (execution.status === 'completed') agents.forEach((agent) => { agent.status = 'completed'; });
  if (current === 'execution' && execution.status === 'running') {
    const running = agents.find((agent) => agent.status === 'pending');
    if (running) running.status = 'running';
  }
  if (current === 'execution' && execution.status === 'failed' && !agents.some((agent) => agent.status === 'failed')) {
    const failed = agents.find((agent) => agent.status === 'pending');
    if (failed) failed.status = 'failed';
  }
}

function parseAgent(payload: Record<string, unknown> | null): Omit<AgentStageView, 'status'> | null {
  if (!payload) return null;
  return {
    index: numberValue(payload.stageIndex ?? payload.stage_index) ?? -1,
    role: stringValue(payload.role), repo: stringValue(payload.repo), engine: stringValue(payload.engine),
    summary: stringValue(payload.summary), changedPaths: stringList(payload.changedPaths ?? payload.changed_paths),
    tokenUsage: recordValue(payload.tokenUsage ?? payload.token_usage) ?? {},
  };
}

function parseModification(payload: Record<string, unknown> | null): ModificationView | null {
  if (!payload) return null;
  return {
    summary: stringValue(payload.summary), provider: stringValue(payload.executionProvider ?? payload.execution_provider),
    turns: numberValue(payload.turns), tokenUsage: recordValue(payload.tokenUsage ?? payload.token_usage) ?? {},
    diffPatch: stringValue(payload.diffPatch ?? payload.diff_patch),
    revision: numberValue(payload.revision) ?? 0,
    revisionMode: revisionMode(payload.revisionMode ?? payload.revision_mode),
  };
}

function revisionMode(value: unknown): 'incremental' | 'full' {
  return stringValue(value) === 'incremental' ? 'incremental' : 'full';
}

function revisionDiffSummary(value: unknown): string {
  if (typeof value === 'string') return value;
  return arrayRecords(value).map((item) => {
    const repo = stringValue(item.repo) || '默认仓库';
    const paths = stringList(item.changedPaths ?? item.changed_paths);
    return paths.length ? `${repo}：${paths.join('、')}` : `${repo}：${stringValue(item.summary) || '已有候选修改'}`;
  }).join('；');
}

function parseChecks(payload: Record<string, unknown> | null, passed: boolean): ValidationCheckView[] {
  const payloadRepo = stringValue(payload?.repo);
  return arrayRecords(payload?.commands).map((command) => {
    const exitCode = numberValue(command.exitCode ?? command.exit_code);
    return {
      repo: stringValue(command.repo) || payloadRepo, command: stringValue(command.command), exitCode,
      stdout: stringValue(command.stdoutTail ?? command.stdout_tail), stderr: stringValue(command.stderrTail ?? command.stderr_tail),
      passed: exitCode === null ? passed : exitCode === 0,
    };
  }).filter((command) => command.command);
}

function ensureRepo(repositories: Map<string, RepositoryFlowView>, value: string) {
  const repo = value || 'main';
  if (!repositories.has(repo)) repositories.set(repo, {
    repo, diffPatch: '', changedPaths: [], agentSummaries: [], checks: [], branch: '', commitHash: '',
    mrIid: null, mrUrl: '', status: 'changed',
  });
  return repositories.get(repo)!;
}

function setRepoDiff(repositories: Map<string, RepositoryFlowView>, repoId: string, diffPatch: string) {
  const repo = ensureRepo(repositories, repoId);
  repo.diffPatch = diffPatch;
  repo.changedPaths = unique([...repo.changedPaths, ...diffPaths(diffPatch)]);
}

function updateMergeRequest(repositories: Map<string, RepositoryFlowView>, payload: Record<string, unknown> | null,
                            status: 'opened' | 'merged' | 'closed') {
  const repo = ensureRepo(repositories, stringValue(payload?.repo));
  const mrIid = numberValue(payload?.mrIid ?? payload?.mr_iid);
  if (status !== 'opened' && repo.mrIid !== null && mrIid !== null && repo.mrIid !== mrIid) return;
  repo.branch = stringValue(payload?.branch) || repo.branch;
  repo.commitHash = stringValue(payload?.commitHash ?? payload?.commit_hash) || repo.commitHash;
  repo.changedPaths = unique([...repo.changedPaths, ...stringList(payload?.changedPaths ?? payload?.changed_paths)]);
  repo.mrIid = mrIid ?? repo.mrIid;
  repo.mrUrl = stringValue(payload?.mrUrl ?? payload?.mr_url) || repo.mrUrl;
  repo.status = status;
}

function applyPrepared(repositories: Map<string, RepositoryFlowView>, payload: Record<string, unknown> | null) {
  const repo = ensureRepo(repositories, stringValue(payload?.repo));
  repo.branch = stringValue(payload?.branch) || repo.branch;
  repo.commitHash = stringValue(payload?.commitHash ?? payload?.commit_hash) || repo.commitHash;
  repo.changedPaths = unique([...repo.changedPaths, ...stringList(payload?.changedPaths ?? payload?.changed_paths)]);
  repo.mrIid = numberValue(payload?.mrIid ?? payload?.mr_iid) ?? repo.mrIid;
  repo.mrUrl = stringValue(payload?.mrUrl ?? payload?.mr_url) || repo.mrUrl;
  repo.status = repo.mrIid ? 'opened' : 'published';
}

function clearMergeRequestState(repositories: Map<string, RepositoryFlowView>) {
  for (const repo of repositories.values()) {
    repo.branch = '';
    repo.commitHash = '';
    repo.mrIid = null;
    repo.mrUrl = '';
    repo.status = 'changed';
  }
}

function applyRelease(repositories: Map<string, RepositoryFlowView>, payload: Record<string, unknown> | null) {
  const releases = arrayRecords(payload?.repositories);
  if (releases.length) {
    for (const item of releases) {
      const repo = ensureRepo(repositories, stringValue(item.repo));
      repo.branch = stringValue(item.branch);
      repo.commitHash = stringValue(item.commitHash ?? item.commit_hash);
      repo.changedPaths = unique([...repo.changedPaths, ...stringList(item.changedPaths ?? item.changed_paths)]);
      repo.mrIid = numberValue(item.mrIid ?? item.mr_iid) ?? repo.mrIid;
      repo.mrUrl = stringValue(item.mrUrl ?? item.mr_url) || repo.mrUrl;
      repo.status = stringValue(item.state) === 'merged' ? 'merged' : 'released';
    }
    return;
  }
  const repo = ensureRepo(repositories, stringValue(payload?.repo));
  repo.branch = stringValue(payload?.branch);
  repo.commitHash = stringValue(payload?.commitHash ?? payload?.commit_hash);
  repo.changedPaths = unique([...repo.changedPaths, ...stringList(payload?.changedPaths ?? payload?.changed_paths)]);
  repo.status = 'released';
}

function defaultRepo(agents: AgentStageView[]) {
  const repos = unique(agents.map((agent) => agent.repo).filter(Boolean));
  return repos.length === 1 ? repos[0] : 'main';
}

function diffPaths(diff: string) {
  return unique([...diff.matchAll(/^diff --git a\/(.+?) b\/(.+)$/gm)].map((match) => match[2]));
}

function complete(stage: FlowStage, at?: string) {
  stage.completedAt = at || stage.completedAt;
}

function setDuration(stage: FlowStage) {
  if (!stage.startedAt || !stage.completedAt) return;
  const duration = new Date(stage.completedAt).getTime() - new Date(stage.startedAt).getTime();
  if (Number.isFinite(duration) && duration >= 0) stage.durationMs = duration;
}

function waitingRoleName(role?: string) {
  return ({ owner: '系统负责人', worker: 'Agent', gitlab: 'GitLab' } as Record<string, string>)[role || ''] || role || undefined;
}

function recordValue(value: unknown): Record<string, unknown> | null {
  return value && typeof value === 'object' && !Array.isArray(value) ? value as Record<string, unknown> : null;
}

function arrayRecords(value: unknown) {
  return Array.isArray(value) ? value.map(recordValue).filter((item): item is Record<string, unknown> => Boolean(item)) : [];
}

function stringList(value: unknown) {
  return Array.isArray(value) ? value.filter((item): item is string => typeof item === 'string') : [];
}

function stringValue(value: unknown) {
  return typeof value === 'string' ? value : value == null ? '' : String(value);
}

function numberValue(value: unknown) {
  const number = typeof value === 'number' ? value : typeof value === 'string' && value ? Number(value) : NaN;
  return Number.isFinite(number) ? number : null;
}

function unique<T>(values: T[]) {
  return [...new Set(values)];
}
