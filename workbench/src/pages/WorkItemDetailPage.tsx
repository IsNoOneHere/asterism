import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { useState } from 'react';
import { Link, useLocation, useParams } from 'react-router-dom';
import { api, MemoryDraft, WorkItemEvent } from '../api/client';
import { ActionConfirmDialog } from '../components/ActionConfirmDialog';
import { MemoryEditorDialog } from '../components/MemoryEditorDialog';
import { WorkItemNavigationState } from '../workItemListState';

type StageAction = {
  label: string;
  signalName?: string;
  ownerApproval?: boolean;
};
type PlanView = {
  steps: string[];
  targetFiles: string[];
  testPlan: string[];
  risks: string[];
  assignments: { role: string }[];
};
type StageProgressItem = { role: string; status: 'pending' | 'running' | 'completed' | 'failed' };

export function WorkItemDetailPage() {
  const { workItemId = '' } = useParams();
  const location = useLocation();
  const queryClient = useQueryClient();
  const [memoryOpen, setMemoryOpen] = useState(false);
  const [memoryMessage, setMemoryMessage] = useState('');
  const [confirmAction, setConfirmAction] = useState<StageAction | null>(null);
  const item = useQuery({
    queryKey: ['work-item', workItemId],
    queryFn: () => api.workItem(workItemId),
    enabled: Boolean(workItemId),
    refetchInterval: (query) => isTerminal(query.state.data?.lifecycleStatus) ? false : 3000,
    retry: false,
  });
  const events = useQuery({
    queryKey: ['work-item-events', workItemId],
    queryFn: () => api.workItemEvents(workItemId),
    enabled: Boolean(workItemId),
    refetchInterval: () => isTerminal(item.data?.lifecycleStatus) ? false : 3000,
    retry: false,
  });
  const runAction = useMutation({
    mutationFn: (action: StageAction) => (action.ownerApproval ? api.approveOwner(workItemId) : api.submitSignal(workItemId, action.signalName!)),
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: ['work-item', workItemId] });
      queryClient.invalidateQueries({ queryKey: ['work-item-events', workItemId] });
    },
    onSettled: () => setConfirmAction(null),
  });
  const createMemory = useMutation({
    mutationFn: (draft: MemoryDraft) => api.createMemory({ systemId: item.data!.systemId, workItemId, ...draft }),
    onSuccess: () => {
      console.info('v5 workbench 从工作项沉淀记忆', { workItemId });
      setMemoryOpen(false);
      setMemoryMessage('已加入系统记忆待审批');
    },
  });

  const workItem = item.data;
  const actions = workItem ? (workItem.availableActions ?? []).map(stageAction).filter((action): action is StageAction => Boolean(action)) : [];
  const canAct = workItem?.canControl ?? false;

  return (
    <section>
      <header className="page-head">
        <div>
          <Link className="secondary-action-link" state={location.state as WorkItemNavigationState | null} to="/work-items">← 返回工作项中心</Link>
          <h1>{workItem?.title || workItemId}</h1>
          <p>{workItem?.currentStage || '加载中'}</p>
        </div>
      </header>
      {workItem && (
        <div className="split">
          <div className="panel">
            <h2>当前状态与操作</h2>
            <dl className="summary-list">
              <dt>所属系统</dt>
              <dd>{workItem.systemId}</dd>
              <dt>生命周期</dt>
              <dd>{workItem.lifecycleStatus}</dd>
              <dt>等待角色</dt>
              <dd>{workItem.waitingFor || '-'}</dd>
              <dt>执行权限</dt>
              <dd>{workItem.executionAllowed ? '允许' : '关闭'}</dd>
              <dt>确认目标</dt>
              <dd>{workItem.targets?.map((target) => `${target.title}${target.apiEndpoints?.length ? `（${target.apiEndpoints.join('、')}）` : ''}`).join('；') || '-'}</dd>
            </dl>
            {canAct && actions.length > 0 ? (
              <div className="button-row wrap">
                {actions.map((action) => (
                  <button key={action.label} type="button" disabled={runAction.isPending} onClick={() => {
                    if (needsConfirmation(action)) {
                      runAction.reset();
                      setConfirmAction(action);
                    } else {
                      runAction.mutate(action);
                    }
                  }}>
                    {action.label}
                  </button>
                ))}
              </div>
            ) : (
              <div className="empty">当前用户仅可查看，或该阶段无需人工动作。</div>
            )}
            <div className="button-row memory-work-item-action"><button type="button" className="secondary" onClick={() => setMemoryOpen(true)}>沉淀为记忆</button></div>
            {memoryMessage && <div className="success-text">{memoryMessage}</div>}
          </div>
          <div className="panel">
            <h2>事件时间线</h2>
            <StageProgress stages={buildStageProgress(events.data ?? [], workItem.lifecycleStatus)} />
            {events.data?.map((event) => (
              <TimelineEvent key={event.eventId || event.sequence} event={event} />
            ))}
            {events.isError && <div className="empty">事件接口待后端补齐：/api/v5/work-items/{workItemId}/events。</div>}
            {!events.isError && (events.data ?? []).length === 0 && <div className="empty">暂无事件。</div>}
          </div>
        </div>
      )}
      {item.isError && <div className="empty">工作项不存在或无权限。</div>}
      <MemoryEditorDialog open={memoryOpen} title="从工作项沉淀记忆" submitLabel="加入待审批" workItemId={workItemId} pending={createMemory.isPending} onClose={() => setMemoryOpen(false)} onSubmit={(draft) => createMemory.mutate(draft)} />
      <ActionConfirmDialog
        open={Boolean(confirmAction)}
        title={`确认${confirmAction?.label || ''}？`}
        description={confirmAction ? confirmText(confirmAction).replace('，是否继续？', '。') : ''}
        confirmLabel={confirmAction?.label}
        pending={runAction.isPending}
        tone={confirmAction && ['拒绝', '取消'].includes(confirmAction.label) ? 'danger' : 'primary'}
        onClose={() => setConfirmAction(null)}
        onConfirm={() => confirmAction && runAction.mutate(confirmAction)}
      />
      <ActionConfirmDialog
        open={Boolean(runAction.error)}
        title="操作失败"
        description={String(runAction.error || '')}
        confirmLabel="知道了"
        alert
        showCancel={false}
        onClose={() => runAction.reset()}
        onConfirm={() => runAction.reset()}
      />
    </section>
  );
}

function StageProgress({ stages }: { stages: StageProgressItem[] }) {
  if (stages.length === 0) return null;
  const labels = { pending: '待执行', running: '执行中', completed: '完成', failed: '失败' };
  return (
    <ol className="stage-progress" aria-label="Agent Stage 进度">
      {stages.map((stage, index) => (
        <li className={`stage-progress-item ${stage.status}`} key={`${index}-${stage.role}`}>
          <strong>{stage.role}</strong>
          <span>{labels[stage.status]}</span>
        </li>
      ))}
    </ol>
  );
}

function TimelineEvent({ event }: { event: WorkItemEvent }) {
  const plan = event.eventType === 'ExecutionPlanDrafted' ? parsePlanPayload(event.payloadJson) : null;
  const modification = event.eventType === 'ModificationCompleted' ? parseModificationPayload(event.payloadJson) : null;
  const release = event.eventType === 'ReleaseCompleted' ? parseReleasePayload(event.payloadJson) : null;
  const agentStage = event.eventType === 'AgentStageCompleted' ? parseAgentStagePayload(event.payloadJson) : null;
  return (
    <div className="timeline-item">
      <div>
        <strong>{event.eventType}</strong>
        <span>{formatTime(event.createdAt)} · {event.actorId || event.source || 'system'}</span>
      </div>
      {plan ? <ExecutionPlanView plan={plan} /> : agentStage ? <AgentStageView stage={agentStage} /> : modification ? <ModificationView modification={modification} /> : release ? <ReleaseView release={release} /> : event.payloadJson && (
        <details>
          <summary>原始数据</summary>
          <pre>{formatPayload(event.payloadJson)}</pre>
        </details>
      )}
    </div>
  );
}

function AgentStageView({ stage }: { stage: { role: string; engine: string; summary: string; changedPaths: string[]; tokenUsage: Record<string, unknown> } }) {
  return <dl className="summary-list compact">
    <dt>Agent 角色</dt><dd>{stage.role || '-'}</dd>
    <dt>执行内核</dt><dd>{stage.engine || '-'}</dd>
    <dt>摘要</dt><dd>{stage.summary || '-'}</dd>
    <dt>修改文件</dt><dd>{stage.changedPaths.join(', ') || '-'}</dd>
    <dt>Token</dt><dd>{formatTokenUsage(stage.tokenUsage)}</dd>
  </dl>;
}

function ModificationView({ modification }: { modification: { provider: string; turns: number | null; tokenUsage: Record<string, unknown>; diffPatch: string } }) {
  return (
    <>
      <dl className="summary-list compact">
        <dt>执行内核</dt>
        <dd>{modification.provider || '-'}</dd>
        <dt>轮次</dt>
        <dd>{modification.turns ?? '-'}</dd>
        <dt>Token</dt>
        <dd>{formatTokenUsage(modification.tokenUsage)}</dd>
      </dl>
      {modification.diffPatch && (
        <details open>
          <summary>代码 diff</summary>
          <pre>{modification.diffPatch}</pre>
        </details>
      )}
    </>
  );
}

function ReleaseView({ release }: { release: { branch: string; commitHash: string; pushFailed: string } }) {
  return (
    <dl className="summary-list compact">
      <dt>分支</dt>
      <dd>{release.branch || '-'}</dd>
      <dt>提交</dt>
      <dd>{release.commitHash || '-'}</dd>
      {release.pushFailed && (
        <>
          <dt>推送</dt>
          <dd>{release.pushFailed}</dd>
        </>
      )}
    </dl>
  );
}

function ExecutionPlanView({ plan }: { plan: PlanView }) {
  return (
    <div className="plan-view">
      <PlanList title="执行步骤" items={plan.steps} />
      <PlanList title="目标文件" items={plan.targetFiles} />
      <PlanList title="测试计划" items={plan.testPlan} />
      <PlanList title="风险" items={plan.risks} />
    </div>
  );
}

function PlanList({ title, items }: { title: string; items: string[] }) {
  if (items.length === 0) {
    return null;
  }
  return (
    <div>
      <strong>{title}</strong>
      <ul>
        {items.map((item) => (
          <li key={item}>{item}</li>
        ))}
      </ul>
    </div>
  );
}

function stageAction(code: string): StageAction | null {
  const labels: Record<string, string> = {
    owner_approved: '批准执行', owner_rejected: '拒绝', cancel_case: '取消', start_modification: '开始修改',
    rework: '重新执行', patch_apply_approved: '应用 Patch', patch_apply_rejected: '重做',
    validation_passed: '测试通过', validation_rejected: '重做', release_approved: '创建发布提交',
  };
  if (!labels[code]) return null;
  return code === 'owner_approved' ? { label: labels[code], ownerApproval: true } : { label: labels[code], signalName: code };
}

function needsConfirmation(action: StageAction) {
  return action.ownerApproval || ['owner_rejected', 'cancel_case', 'patch_apply_approved', 'release_approved'].includes(action.signalName || '');
}

function confirmText(action: StageAction) {
  return ({
    owner_approved: '批准后工作项将进入可执行状态，是否继续？', owner_rejected: '拒绝后工作项将结束，是否继续？',
    cancel_case: '取消后工作项将结束，是否继续？', patch_apply_approved: '该操作会修改真实仓库，是否继续？',
    release_approved: '该操作会创建发布分支和提交，是否继续？',
  } as Record<string, string>)[action.ownerApproval ? 'owner_approved' : action.signalName || ''] || '是否继续？';
}

function isTerminal(status?: string) {
  return ['completed', 'cancelled', 'rejected'].includes(status || '');
}

function formatPayload(payload: string) {
  try {
    return JSON.stringify(JSON.parse(payload), null, 2);
  } catch {
    return payload;
  }
}

function parsePlanPayload(payload?: string): PlanView | null {
  if (!payload) {
    return null;
  }
  try {
    // ExecutionPlanDrafted 是高频审阅事件，单独展开；其它事件继续走原 JSON。
    const parsed = JSON.parse(payload) as { plan?: Record<string, unknown> };
    const plan = parsed.plan;
    if (!plan) {
      return null;
    }
    return {
      steps: stringList(plan.steps),
      targetFiles: stringList(plan.target_files ?? plan.targetFiles),
      testPlan: stringList(plan.test_plan ?? plan.testPlan),
      risks: stringList(plan.risks),
      assignments: Array.isArray(plan.assignments)
        ? plan.assignments.flatMap((item) => item && typeof item === 'object' && typeof (item as Record<string, unknown>).role === 'string'
          ? [{ role: String((item as Record<string, unknown>).role) }]
          : [])
        : [],
    };
  } catch {
    return null;
  }
}

function parseReleasePayload(payload?: string) {
  if (!payload) {
    return null;
  }
  try {
    const parsed = JSON.parse(payload) as Record<string, unknown>;
    return {
      branch: String(parsed.branch ?? ''),
      commitHash: String(parsed.commitHash ?? parsed.commit_hash ?? ''),
      pushFailed: String(parsed.pushFailed ?? parsed.push_failed ?? ''),
    };
  } catch {
    return null;
  }
}

function parseModificationPayload(payload?: string) {
  if (!payload) {
    return null;
  }
  try {
    const parsed = JSON.parse(payload) as Record<string, unknown>;
    const turns = typeof parsed.turns === 'number' ? parsed.turns : null;
    const usage = parsed.tokenUsage ?? parsed.token_usage;
    return {
      provider: String(parsed.executionProvider ?? parsed.execution_provider ?? ''),
      turns: turns !== null && Number.isFinite(turns) ? turns : null,
      tokenUsage: usage && typeof usage === 'object' ? usage as Record<string, unknown> : {},
      diffPatch: String(parsed.diffPatch ?? parsed.diff_patch ?? ''),
    };
  } catch {
    return null;
  }
}

function parseAgentStagePayload(payload?: string) {
  if (!payload) return null;
  try {
    const parsed = JSON.parse(payload) as Record<string, unknown>;
    const usage = parsed.tokenUsage ?? parsed.token_usage;
    const stageIndex = parsed.stageIndex ?? parsed.stage_index;
    return {
      stageIndex: typeof stageIndex === 'number' && Number.isInteger(stageIndex) ? stageIndex : null,
      role: String(parsed.role ?? ''),
      engine: String(parsed.engine ?? ''),
      summary: String(parsed.summary ?? ''),
      changedPaths: stringList(parsed.changedPaths ?? parsed.changed_paths),
      tokenUsage: usage && typeof usage === 'object' ? usage as Record<string, unknown> : {},
    };
  } catch {
    return null;
  }
}

function buildStageProgress(events: WorkItemEvent[], lifecycleStatus: string): StageProgressItem[] {
  let planIndex = -1;
  let assignments: { role: string }[] = [];
  events.forEach((event, index) => {
    if (event.eventType === 'ExecutionPlanDrafted') {
      planIndex = index;
      assignments = parsePlanPayload(event.payloadJson)?.assignments ?? [];
    }
  });
  if (assignments.length === 0) return [];

  const stages: StageProgressItem[] = assignments.map(({ role }) => ({ role, status: 'pending' }));
  let failedIndex: number | null = null;
  events.slice(planIndex + 1).forEach((event) => {
    if (event.eventType === 'AgentStageCompleted') {
      const completed = parseAgentStagePayload(event.payloadJson);
      const index = completed?.stageIndex ?? stages.findIndex((stage) => stage.role === completed?.role && stage.status === 'pending');
      if (index >= 0 && index < stages.length) stages[index].status = 'completed';
    }
    if (event.eventType === 'WorkerBlocked' && lifecycleStatus === 'worker_blocked') {
      failedIndex = null;
      try {
        const payload = JSON.parse(event.payloadJson || '{}') as Record<string, unknown>;
        const failed = payload.failed_stage ?? payload.failedStage;
        if (failed && typeof failed === 'object') {
          const index = (failed as Record<string, unknown>).index;
          failedIndex = typeof index === 'number' && Number.isInteger(index) ? index : null;
        }
      } catch {
        failedIndex = null;
      }
    }
  });
  if (failedIndex !== null && failedIndex >= 0 && failedIndex < stages.length) {
    stages[failedIndex].status = 'failed';
  } else if (lifecycleStatus === 'activated') {
    const running = stages.find((stage) => stage.status === 'pending');
    if (running) running.status = 'running';
  }
  return stages;
}

function formatTokenUsage(usage: Record<string, unknown>) {
  const input = Number(usage.input_tokens ?? usage.inputTokens);
  const output = Number(usage.output_tokens ?? usage.outputTokens);
  const parts = [];
  if (Number.isFinite(input)) parts.push(`输入 ${input}`);
  if (Number.isFinite(output)) parts.push(`输出 ${output}`);
  return parts.join(' / ') || '未返回';
}

function stringList(value: unknown) {
  return Array.isArray(value) ? value.filter((item): item is string => typeof item === 'string') : [];
}

function formatTime(value?: string) {
  if (!value) {
    return '-';
  }
  const date = new Date(value);
  return Number.isNaN(date.getTime()) ? value : date.toLocaleString();
}
