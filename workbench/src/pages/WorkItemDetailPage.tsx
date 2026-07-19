import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import {
  Ban, Bot, CheckCircle2, Circle, Clock3, Code2, GitMerge, History, MinusCircle, UserRound, Workflow, XCircle,
} from 'lucide-react';
import { useEffect, useState } from 'react';
import { Link, useLocation, useParams } from 'react-router-dom';
import { api, MemoryDraft, WorkItem, WorkItemEvent } from '../api/client';
import { ActionConfirmDialog } from '../components/ActionConfirmDialog';
import { errorMessage, ErrorState, formatDateTime, StatusBadge } from '../components/Display';
import { MemoryEditorDialog } from '../components/MemoryEditorDialog';
import {
  AgentStageView, buildWorkItemFlow, eventName, eventPayload, failureReason, FlowAttempt, FlowStage, FlowStageId,
  FlowStageStatus, RepositoryFlowView, ValidationCheckView, WorkItemFlow,
} from '../workItemFlow';
import { WorkItemNavigationState } from '../workItemListState';

type DetailTab = 'flow' | 'code' | 'audit';
type StageAction = {
  code: string;
  label: string;
  stageId: FlowStageId;
  signalName?: string;
  ownerApproval?: boolean;
  mergeCheck?: boolean;
  danger?: boolean;
  noteRequired?: boolean;
  requestId?: string;
  expectedStatus?: string;
  expectedProjectionSequence?: number;
};

const TAB_LABELS: Record<DetailTab, string> = { flow: '流程', code: '代码变更', audit: '事件审计' };

export function WorkItemDetailPage() {
  const { workItemId = '' } = useParams();
  const location = useLocation();
  const queryClient = useQueryClient();
  const [activeTab, setActiveTab] = useState<DetailTab>('flow');
  const [selectedStageId, setSelectedStageId] = useState<FlowStageId | null>(null);
  const [memoryOpen, setMemoryOpen] = useState(false);
  const [memoryMessage, setMemoryMessage] = useState('');
  const [confirmAction, setConfirmAction] = useState<StageAction | null>(null);
  const [actionNote, setActionNote] = useState('');
  const [actionEvidence, setActionEvidence] = useState('');

  useEffect(() => {
    setActiveTab('flow');
    setSelectedStageId(null);
  }, [workItemId]);

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
  useEffect(() => {
    // 终态首次出现后再补拉一次事件，避免状态投影先返回而漏掉最后的发布事件。
    if (isTerminal(item.data?.lifecycleStatus) && events.isFetched) {
      void queryClient.refetchQueries({ queryKey: ['work-item-events', workItemId], exact: true });
    }
  }, [events.isFetched, item.data?.lifecycleStatus, queryClient, workItemId]);
  const runAction = useMutation({
    mutationFn: (action: StageAction) => {
      const body = {
        requestId: action.requestId!, expectedStatus: action.expectedStatus!,
        expectedProjectionSequence: action.expectedProjectionSequence!,
        ...(actionNote.trim() ? { note: actionNote.trim() } : {}),
        ...(actionEvidence.trim() ? { evidence: actionEvidence.trim() } : {}),
      };
      return action.ownerApproval
        ? api.approveOwner(workItemId, body)
        : action.mergeCheck ? api.checkMergeStatus(workItemId, body) : api.submitSignal(workItemId, action.signalName!, body);
    },
    onSuccess: () => {
      setActionNote('');
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
  const flow = workItem ? buildWorkItemFlow(workItem, events.data ?? []) : null;
  // null 表示自动跟随当前阶段；用户选择历史节点后，轮询只更新数据而不抢回选择。
  const selectedStage = flow?.stages.find((stage) => stage.id === (selectedStageId ?? flow.currentStageId));
  const actions = workItem && flow
    ? (workItem.availableActions ?? []).map((code) => stageAction(code, flow.currentStageId, workItem)).filter((action): action is StageAction => Boolean(action))
    : [];
  const prepareAction = (action: StageAction, note = '') => {
    runAction.reset();
    setActionNote(note);
    setActionEvidence('');
    setConfirmAction({ ...action, requestId: crypto.randomUUID(), expectedStatus: workItem!.lifecycleStatus,
      expectedProjectionSequence: workItem!.lastAppliedSequence });
  };

  return (
    <section className="work-item-detail">
      <header className="page-head work-item-detail-head">
        <div>
          <Link className="secondary-action-link" state={location.state as WorkItemNavigationState | null} to="/work-items">← 返回工作项中心</Link>
          <div className="work-item-heading">
            <h1>{workItem?.title || workItemId}</h1>
            <span>{workItem?.workItemId || workItemId}</span>
          </div>
        </div>
        {workItem && <button type="button" className="secondary" onClick={() => { createMemory.reset(); setMemoryOpen(true); }}>沉淀为记忆</button>}
      </header>

      {workItem && flow && (
        <>
          <WorkItemOverview workItem={workItem} flow={flow} />
          {flow.activeRevision && <div className="notice revision-progress" role="status">第 {flow.activeRevision.revision} 轮修订中：{flow.activeRevision.note}</div>}
          <details className="work-item-basic panel">
            <summary>基本信息</summary>
            <dl className="summary-list">
              <dt>所属系统</dt><dd>{workItem.systemId}</dd>
              <dt>执行权限</dt><dd>{workItem.executionAllowed ? '允许' : '关闭'}</dd>
              <dt>发布 / 验证</dt><dd>{workItem.releaseMode || '-'} / {workItem.validationMode || '-'}</dd>
              <dt>创建人</dt><dd>{workItem.createdBy || '-'}</dd>
              <dt>确认目标</dt>
              <dd>{workItem.targets?.map((target) => `${target.title}${target.apiEndpoints?.length ? `（${target.apiEndpoints.join('、')}）` : ''}`).join('；') || '-'}</dd>
            </dl>
          </details>
          {memoryMessage && <div className="success-text">{memoryMessage}</div>}
          {flow.revisions.length > 0 && <RevisionHistory revisions={flow.revisions} />}

          <nav className="page-tabs work-item-tabs" aria-label="工作项详情">
            {(Object.keys(TAB_LABELS) as DetailTab[]).map((tab) => {
              const Icon = tab === 'flow' ? Workflow : tab === 'code' ? Code2 : History;
              return (
                <button
                  key={tab}
                  type="button"
                  className={activeTab === tab ? 'active' : ''}
                  aria-pressed={activeTab === tab}
                  onClick={() => setActiveTab(tab)}
                >
                  <Icon size={16} aria-hidden="true" />{TAB_LABELS[tab]}
                </button>
              );
            })}
          </nav>

          {events.isLoading && <div className="panel empty" role="status">事件加载中…</div>}
          {events.isError && <ErrorState title="事件加载失败" error={events.error} onRetry={() => events.refetch()} />}
          {!events.isLoading && !events.isError && activeTab === 'flow' && selectedStage && (
            <div>
              <FlowGraph flow={flow} selectedStageId={selectedStage.id} onSelect={(stageId) => setSelectedStageId(stageId === flow.currentStageId ? null : stageId)} />
              <StageDetail
                stage={selectedStage}
                flow={flow}
                workItem={workItem}
                actions={selectedStage.id === flow.currentStageId ? actions.filter((action) => action.stageId === selectedStage.id) : []}
                pending={runAction.isPending || Boolean(workItem.pendingAction)}
                pendingAction={workItem.pendingAction?.action}
                onAction={(action) => {
                  prepareAction(action);
                }}
              />
            </div>
          )}
          {!events.isLoading && !events.isError && activeTab === 'code' && (
            <div>
              <CodeChanges
                flow={flow}
                actions={actions}
                pending={runAction.isPending || Boolean(workItem.pendingAction)}
                reviewNote={actionNote}
                onReviewNoteChange={setActionNote}
                onAction={(action) => prepareAction(action, actionNote)}
              />
            </div>
          )}
          {!events.isLoading && !events.isError && activeTab === 'audit' && (
            <div>
              <EventAudit events={flow.events} />
            </div>
          )}
        </>
      )}

      {item.isLoading && <div className="panel empty" role="status">工作项加载中…</div>}
      {item.isError && <ErrorState title="工作项加载失败" error={item.error} onRetry={() => item.refetch()} />}
      <MemoryEditorDialog open={memoryOpen} title="从工作项沉淀记忆" submitLabel="加入待审批" workItemId={workItemId} pending={createMemory.isPending} error={createMemory.error} onClose={() => { setMemoryOpen(false); createMemory.reset(); }} onSubmit={(draft) => createMemory.mutate(draft)} />
      <ActionConfirmDialog
        open={Boolean(confirmAction)}
        title={`确认${confirmAction?.label || ''}？`}
        description={confirmAction ? confirmText(confirmAction).replace('，是否继续？', '。') : ''}
        confirmLabel={confirmAction?.label}
        pending={runAction.isPending}
        confirmDisabled={Boolean(confirmAction?.noteRequired && !actionNote.trim())}
        tone={confirmAction?.danger ? 'danger' : 'primary'}
        fields={confirmAction && actionContextKind(confirmAction.code) !== 'none' ? <div className="action-context-fields">
          {actionContextKind(confirmAction.code) !== 'evidence' && <label>{confirmAction.noteRequired ? '修订意见（必填）' : '处理说明（可选）'}<textarea rows={3} maxLength={2000} required={confirmAction.noteRequired} value={actionNote} onChange={(event) => setActionNote(event.target.value)} /></label>}
          {actionContextKind(confirmAction.code) === 'evidence' && <label>验证证据（建议填写）<textarea rows={3} maxLength={4000} value={actionEvidence} onChange={(event) => setActionEvidence(event.target.value)} placeholder="测试环境、结果、截图或记录链接" /></label>}
        </div> : undefined}
        onClose={() => setConfirmAction(null)}
        onConfirm={() => confirmAction && runAction.mutate(confirmAction)}
      />
      <ActionConfirmDialog
        open={Boolean(runAction.error)}
        title="操作失败"
        description={errorMessage(runAction.error, '工作项操作失败')}
        confirmLabel="知道了"
        alert
        showCancel={false}
        onClose={() => runAction.reset()}
        onConfirm={() => runAction.reset()}
      />
    </section>
  );
}

function WorkItemOverview({ workItem, flow }: { workItem: WorkItem; flow: WorkItemFlow }) {
  const current = flow.stages.find((stage) => stage.id === flow.currentStageId)!;
  return (
    <div className="work-item-overview panel" aria-label="工作项摘要">
      <div><span>当前状态</span><StatusBadge value={workItem.lifecycleStatus} /></div>
      <div><span>当前阶段</span><strong>{flow.activeRevision ? `第 ${flow.activeRevision.revision} 轮修订中` : current.label}</strong></div>
      <div><span>等待角色</span><strong>{current.waitingFor || waitingRoleName(workItem.waitingFor)}</strong></div>
      <div><span>创建时间</span><strong>{formatDateTime(workItem.createdAt)}</strong></div>
      <div><span>已用时</span><strong>{elapsedTime(workItem.createdAt, isTerminal(workItem.lifecycleStatus) ? workItem.updatedAt : undefined)}</strong></div>
    </div>
  );
}

function RevisionHistory({ revisions }: { revisions: WorkItemFlow['revisions'] }) {
  return (
    <section className="panel revision-history" aria-labelledby="revision-history-title">
      <header><div><h2 id="revision-history-title">修订历史</h2><p>每轮都保留人工意见与修改方式，便于审计和对比。</p></div><span>{revisions.length} 轮</span></header>
      <ol>
        {revisions.map((revision) => <li key={revision.id} className={revision.status}>
          <div className="revision-index"><strong>第 {revision.revision} 轮</strong><span>{revisionStatusName(revision.status)}</span></div>
          <div className="revision-content"><p>{revision.note || '未记录修订意见'}</p><small>{revision.diffSummary || '等待本轮修订产出'}</small></div>
          <dl><div><dt>方式</dt><dd>{revision.revisionMode === 'incremental' ? '增量修订' : '全量修订'}</dd></div><div><dt>阶段</dt><dd>{revision.phase === 'merge' ? 'MR 审查' : 'Diff 审查'}</dd></div><div><dt>提交人</dt><dd>{revision.requestedBy || '-'}</dd></div></dl>
          <time>{formatDateTime(revision.requestedAt)}{revision.completedAt ? ` – ${formatDateTime(revision.completedAt)}` : ''}</time>
        </li>)}
      </ol>
    </section>
  );
}

function FlowGraph({ flow, selectedStageId, onSelect }: { flow: WorkItemFlow; selectedStageId: FlowStageId; onSelect: (id: FlowStageId) => void }) {
  return (
    <ol className="work-item-flow" aria-label="工作项流程">
      {flow.stages.map((stage) => (
        <li key={stage.id} className={`${stage.status}${selectedStageId === stage.id ? ' selected' : ''}`}>
          <button
            type="button"
            aria-current={stage.id === flow.currentStageId ? 'step' : undefined}
            aria-pressed={selectedStageId === stage.id}
            onClick={() => onSelect(stage.id)}
          >
            <StageStatusIcon status={stage.status} />
            <strong>{stage.label}</strong>
            <span>{stageStatusName(stage)}</span>
            {stage.completedAt && <small>{formatDateTime(stage.completedAt)}</small>}
          </button>
        </li>
      ))}
    </ol>
  );
}

function StageStatusIcon({ status }: { status: FlowStageStatus }) {
  if (status === 'completed') return <CheckCircle2 aria-hidden="true" />;
  if (status === 'failed') return <XCircle aria-hidden="true" />;
  if (status === 'cancelled') return <Ban aria-hidden="true" />;
  if (status === 'skipped') return <MinusCircle aria-hidden="true" />;
  if (status === 'running' || status === 'waiting') return <Clock3 aria-hidden="true" />;
  return <Circle aria-hidden="true" />;
}

function StageDetail({ stage, flow, workItem, actions, pending, pendingAction, onAction }: {
  stage: FlowStage;
  flow: WorkItemFlow;
  workItem: WorkItem;
  actions: StageAction[];
  pending: boolean;
  pendingAction?: string;
  onAction: (action: StageAction) => void;
}) {
  const stageAttempts = flow.attempts.filter((attempt) => attempt.stageIds.includes(stage.id));
  const primaryAction = actions.find((action) => !action.danger)?.code;
  return (
    <section className="stage-detail panel" aria-labelledby="selected-stage-title">
      <header className="stage-detail-head">
        <div>
          <span className={`flow-status-label ${stage.status}`}>{stageStatusName(stage)}</span>
          <h2 id="selected-stage-title">{stage.label}</h2>
          <p>{stageSummary(stage, workItem, flow)}</p>
        </div>
        {stage.failureReason && <div className="stage-failure"><XCircle size={18} aria-hidden="true" /><span>{stage.failureReason}</span></div>}
      </header>

      <dl className="stage-metadata">
        <div><dt>开始</dt><dd>{formatDateTime(stage.startedAt)}</dd></div>
        <div><dt>结束</dt><dd>{formatDateTime(stage.completedAt)}</dd></div>
        <div><dt>耗时</dt><dd>{formatDuration(stage.durationMs)}</dd></div>
        <div><dt>参与角色</dt><dd>{stageParticipants(stage, workItem)}</dd></div>
      </dl>

      {stage.id === 'execution' && <ExecutionDetail agents={stage.agents ?? []} />}
      {stage.id === 'patch' && <PatchDetail flow={flow} />}
      {stage.id === 'validation' && <ValidationChecks checks={stage.checks ?? []} status={stage.status} />}
      {stage.id === 'release' && <RepositoryLanes repositories={stage.repositories ?? []} />}

      {stage.events.length > 0 && (
        <div className="stage-events">
          <h3>关键事件</h3>
          <ol className="flow-event-list">
            {stage.events.map((event) => (
              <li key={event.eventId || event.sequence}>
                <span className="event-dot" aria-hidden="true" />
                <div><strong>{eventName(event.eventType)}</strong><p>{eventSummary(event)}</p></div>
                <time>{formatDateTime(event.createdAt)}</time>
              </li>
            ))}
          </ol>
        </div>
      )}

      {(stageAttempts.length > 1 || stageAttempts.some((attempt) => attempt.status === 'failed')) && <AttemptHistory attempts={stageAttempts} />}

      {pendingAction && <div className="notice action-pending" role="status">“{actionLabel(pendingAction)}”已提交，等待 Worker 完成；期间不能提交其它阶段操作。</div>}

      {workItem.canControl && actions.length > 0 && (
        <div className="stage-actions" aria-label="当前阶段操作">
          {actions.map((action) => (
            <button
              key={action.code}
              type="button"
              className={action.danger ? 'danger-action' : action.code === primaryAction ? undefined : 'secondary'}
              disabled={pending}
              onClick={() => onAction(action)}
            >
              {action.label}
            </button>
          ))}
        </div>
      )}
    </section>
  );
}

function ExecutionDetail({ agents }: { agents: AgentStageView[] }) {
  if (agents.length === 0) return <div className="empty stage-empty">等待 Coding Supervisor 启动。</div>;
  return (
    <div className="execution-detail">
      <ol className="agent-lane" aria-label="Agent 执行进度">
        {agents.map((agent) => (
          <li key={`${agent.index}-${agent.role}`} className={agent.status}>
            <span className="agent-icon"><Bot size={17} /></span>
            <div><strong>{agent.role || 'Developer Agent'}{agent.repo && <small> · {agent.repo}</small>}</strong><p>{agent.summary || agent.engine || '等待执行'}</p></div>
            <em>{agentStatusName(agent.status)}{agent.changedPaths.length ? ` · ${agent.changedPaths.length} 个文件` : ''}</em>
          </li>
        ))}
      </ol>
    </div>
  );
}

function PatchDetail({ flow }: { flow: WorkItemFlow }) {
  if (!flow.modification) return null;
  const changed = flow.repositories.reduce((total, repo) => total + repo.changedPaths.length, 0);
  return (
    <div className="change-summary">
      <strong>{flow.modification.summary || 'Agent 修改已生成'}</strong>
      <span>{flow.modification.provider || '-'} · {changed} 个文件 · {formatTokenUsage(flow.modification.tokenUsage)}</span>
      <p>完整文件列表和 diff 已移至“代码变更”Tab。</p>
    </div>
  );
}

function ValidationChecks({ checks, status }: { checks: ValidationCheckView[]; status: FlowStageStatus }) {
  if (status === 'skipped') return <div className="notice">未配置测试命令，本次自动检查已跳过。</div>;
  if (checks.length === 0) return <div className="empty stage-empty">暂无自动检查结果，等待当前验证动作。</div>;
  return (
    <ul className="validation-checks">
      {checks.map((check, index) => <li key={`${check.repo}-${check.command}-${index}`} className={check.passed ? 'passed' : 'failed'}>
        {check.passed ? <CheckCircle2 size={17} aria-hidden="true" /> : <XCircle size={17} aria-hidden="true" />}
        <div><code>{check.command}</code>{check.repo && <span>{check.repo}</span>}{check.stderr && <p>{check.stderr}</p>}</div>
      </li>)}
    </ul>
  );
}

function RepositoryLanes({ repositories }: { repositories: RepositoryFlowView[] }) {
  if (repositories.length === 0) return <div className="empty stage-empty">尚未生成提交或合并请求。</div>;
  return (
    <div className="repository-lanes" aria-label="仓库发布进度">
      {repositories.map((repo) => (
        <div className={`repository-lane ${repo.status}`} key={repo.repo}>
          <strong>{repo.repo}</strong>
          <span>{repo.branch || '等待分支'}</span>
          <span>{repo.commitHash ? shortHash(repo.commitHash) : '等待 commit'}</span>
          <span>{repo.mrIid ? repo.mrUrl ? <a aria-label={`${repo.repo} !${repo.mrIid}`} href={repo.mrUrl} target="_blank" rel="noreferrer">MR !{repo.mrIid}</a> : `MR !${repo.mrIid}` : '等待 MR'}</span>
          <em>{repositoryStatusName(repo.status)}</em>
        </div>
      ))}
    </div>
  );
}

function AttemptHistory({ attempts }: { attempts: FlowAttempt[] }) {
  return (
    <details className="attempt-history">
      <summary>尝试历史（{attempts.length} 次）</summary>
      <ol>{attempts.map((attempt) => <li key={attempt.number} className={attempt.status}>
        <div><strong>第 {attempt.number} 次</strong><span>{attemptStatusName(attempt.status)}</span></div>
        <p>{attempt.failureReason || `${attempt.events.length} 个关键事件`}</p>
        <time>{formatDateTime(attempt.startedAt)}{attempt.completedAt ? ` – ${formatDateTime(attempt.completedAt)}` : ''}</time>
      </li>)}</ol>
    </details>
  );
}

function CodeChanges({ flow, actions, pending, reviewNote, onReviewNoteChange, onAction }: {
  flow: WorkItemFlow;
  actions: StageAction[];
  pending: boolean;
  reviewNote: string;
  onReviewNoteChange: (note: string) => void;
  onAction: (action: StageAction) => void;
}) {
  if (!flow.modification && flow.repositories.length === 0) return <div className="panel empty">当前还没有代码变更。</div>;
  const agents = flow.stages.find((stage) => stage.id === 'execution')?.agents ?? [];
  return (
    <div className="code-change-list">
      {flow.modification && <div className="panel code-change-summary">
        <div><span>执行摘要</span><strong>{flow.modification.summary || '修改已完成'}</strong></div>
        <div><span>执行内核</span><strong>{flow.modification.provider || '-'}</strong></div>
        <div><span>轮次</span><strong>{flow.modification.turns ?? '-'}</strong></div>
        <div><span>Token</span><strong>{formatTokenUsage(flow.modification.tokenUsage)}</strong></div>
      </div>}
      <CodeReviewActions actions={actions} pending={pending} note={reviewNote} onNoteChange={onReviewNoteChange} onAction={onAction} />
      {flow.repositories.map((repo) => {
        const repoAgents = agents.filter((agent) => !agent.repo || agent.repo === repo.repo);
        const repoChecks = repo.checks.length ? repo.checks : flow.checks.filter((check) => check.repo === repo.repo);
        return <article className="panel repository-change" key={repo.repo}>
          <header><div><GitMerge size={18} aria-hidden="true" /><h2>{repo.repo}</h2></div><span className={`flow-status-label ${repo.status === 'closed' ? 'failed' : repo.status === 'merged' || repo.status === 'released' ? 'completed' : 'waiting'}`}>{repositoryStatusName(repo.status)}</span></header>
          {repoAgents.length > 0 && <section><h3>Agent 摘要</h3><ul>{repoAgents.map((agent) => <li key={`${agent.index}-${agent.role}`}><strong>{agent.role}</strong><span>{agent.summary || agent.engine || '-'}</span></li>)}</ul></section>}
          <section><h3>修改文件</h3>{repo.changedPaths.length ? <ul className="changed-files">{repo.changedPaths.map((path) => <li key={path}><code>{path}</code></li>)}</ul> : <p className="empty-inline">未返回文件列表</p>}</section>
          {repo.diffPatch && <details className="code-diff" open><summary>完整 diff</summary><pre>{repo.diffPatch}</pre></details>}
          {repoChecks.length > 0 && <section><h3>自动检查</h3><ValidationChecks checks={repoChecks} status="completed" /></section>}
          {(repo.branch || repo.commitHash || repo.mrIid) && <dl className="repo-release-meta">
            <div><dt>分支</dt><dd>{repo.branch || '-'}</dd></div>
            <div><dt>Commit</dt><dd>{repo.commitHash || '-'}</dd></div>
            <div><dt>MR</dt><dd>{repo.mrIid ? repo.mrUrl ? <a href={repo.mrUrl} target="_blank" rel="noreferrer">!{repo.mrIid}</a> : `!${repo.mrIid}` : '-'}</dd></div>
          </dl>}
        </article>;
      })}
    </div>
  );
}

function CodeReviewActions({ actions, pending, note, onNoteChange, onAction }: {
  actions: StageAction[];
  pending: boolean;
  note: string;
  onNoteChange: (note: string) => void;
  onAction: (action: StageAction) => void;
}) {
  const reviewActions = actions.filter((action) => ['patch_apply_approved', 'patch_apply_rejected'].includes(action.code)
    || (action.code === 'rework' && action.noteRequired));
  if (reviewActions.length === 0) return null;
  const rejection = reviewActions.find((action) => action.noteRequired);
  return <section className="panel code-review-actions" aria-labelledby="code-review-title">
    <div><h2 id="code-review-title">Diff 审查</h2><p>通过后继续发布；发现问题时填写具体意见，Agent 会自动开始下一轮修订。</p></div>
    {rejection && <label>修订意见（必填）<textarea rows={4} maxLength={2000} required value={note} onChange={(event) => onNoteChange(event.target.value)} placeholder="说明具体问题、期望结果和不应改动的部分" /></label>}
    <div className="code-review-buttons">
      {reviewActions.map((action) => <button key={action.code} type="button"
        className={action.noteRequired ? 'danger-action' : undefined}
        disabled={pending || Boolean(action.noteRequired && !note.trim())}
        onClick={() => onAction(action)}>{action.label}</button>)}
    </div>
  </section>;
}

function EventAudit({ events }: { events: WorkItemEvent[] }) {
  if (events.length === 0) return <div className="panel empty">暂无事件。</div>;
  return (
    <ol className="event-audit-list">
      {events.map((event) => <li className="panel" key={event.eventId || event.sequence}>
        <header><span>#{event.sequence}</span><div><strong>{eventName(event.eventType)}</strong><code>{event.eventType}</code></div><time>{formatDateTime(event.createdAt)}</time></header>
        <dl>
          <div><dt>Actor / Source</dt><dd>{event.actorId || '-'} / {event.source || '-'}</dd></div>
          <div><dt>Causation ID</dt><dd>{event.causationId || '-'}</dd></div>
        </dl>
        <details><summary>原始 JSON</summary><pre>{formatPayload(event.payloadJson || '')}</pre></details>
      </li>)}
    </ol>
  );
}

function stageAction(code: string, currentStageId: FlowStageId, workItem: WorkItem): StageAction | null {
  const values: Record<string, Omit<StageAction, 'code'>> = {
    owner_approved: { label: '批准执行', stageId: 'approval', ownerApproval: true },
    owner_rejected: { label: '拒绝', stageId: 'approval', signalName: code, danger: true },
    cancel_case: { label: '取消', stageId: currentStageId, signalName: code, danger: true },
    start_modification: { label: '开始执行', stageId: 'execution', signalName: code },
    retry_current_phase: { label: '重试失败阶段', stageId: currentStageId, signalName: code },
    rework: { label: workItem.lifecycleStatus === 'waiting_merge' ? '打回修订' : '完整重做', stageId: currentStageId, signalName: code,
      noteRequired: workItem.lifecycleStatus === 'waiting_merge', danger: workItem.lifecycleStatus === 'waiting_merge' },
    rework_with_latest_config: { label: '刷新配置并重试失败阶段', stageId: currentStageId, signalName: code },
    patch_apply_approved: { label: workItem.releaseMode === 'gitlab' ? (workItem.validationMode === 'manual' ? '创建候选 MR' : '发布 MR') : '应用 Patch', stageId: 'patch', signalName: code },
    patch_apply_rejected: { label: '打回修订', stageId: 'patch', signalName: code, noteRequired: true, danger: true },
    validation_passed: { label: workItem.validationMode === 'manual' ? '人工验证通过' : '验证通过', stageId: 'validation', signalName: code },
    validation_rejected: { label: workItem.validationMode === 'manual' ? '人工验证不通过' : '重做', stageId: 'validation', signalName: code },
    release_approved: { label: workItem.releaseMode === 'gitlab' ? '提交 MR' : '创建发布提交', stageId: 'release', signalName: code },
    check_merge_status: { label: '检查合并状态', stageId: 'release', mergeCheck: true },
  };
  return values[code] ? { code, ...values[code] } : null;
}

function actionContextKind(code: string): 'none' | 'note' | 'evidence' {
  if (['validation_passed', 'validation_rejected'].includes(code)) return 'evidence';
  if (['owner_rejected', 'cancel_case', 'rework', 'rework_with_latest_config', 'patch_apply_rejected'].includes(code)) return 'note';
  return 'none';
}

function actionLabel(code: string) {
  return ({ owner_approved: '批准执行', owner_rejected: '拒绝', cancel_case: '取消', start_modification: '开始执行',
    retry_current_phase: '重试失败阶段', rework: '完整重做', rework_with_latest_config: '刷新配置并重试失败阶段',
    patch_apply_approved: '代码确认', patch_apply_rejected: '打回修订',
    validation_passed: '验证通过', validation_rejected: '验证不通过', release_approved: '发布',
    check_merge_status: '检查合并状态' } as Record<string, string>)[code] || code;
}

function confirmText(action: StageAction) {
  return ({
    owner_approved: '批准后工作项将进入可执行状态，是否继续？', owner_rejected: '拒绝后工作项将结束，是否继续？',
    cancel_case: '取消后工作项将结束，是否继续？', patch_apply_approved: '该操作会修改真实仓库，是否继续？',
    start_modification: '确认后 Agent 将开始修改真实仓库，是否继续？',
    retry_current_phase: '将复用已完成成果，只重试失败阶段，是否继续？',
    rework: '将放弃当前执行断点并完整重做，是否继续？',
    rework_with_latest_config: '将刷新 Agent 与模型配置，保留已有计划并重试失败阶段，是否继续？',
    patch_apply_rejected: '确认后 Agent 会带着意见自动开始增量修订，是否继续？', validation_passed: '确认验证已通过并进入下一阶段，是否继续？',
    validation_rejected: '确认后将退回修改阶段重新处理，是否继续？', release_approved: '该操作会创建发布分支和提交，是否继续？',
    check_merge_status: '后端将实时核验所有 GitLab MR，只有确实合并后才会完成工作项，是否继续？',
  } as Record<string, string>)[action.code] || '是否继续？';
}

function stageSummary(stage: FlowStage, workItem: WorkItem, flow: WorkItemFlow) {
  if (stage.failureReason) return stage.failureReason;
  if (stage.id === 'created') return `${workItem.createdBy || '系统'} 创建了“${workItem.title}”。`;
  if (stage.id === 'approval') return stage.status === 'waiting' ? '等待系统负责人确认是否进入执行。' : '负责人审批结果已记录。';
  if (stage.id === 'execution') {
    if (stage.agents?.length) return `Claude SDK Supervisor 正在调度 ${Math.max(0, stage.agents.length - 1)} 个仓库子 Agent。`;
    return '等待 Coding Supervisor 启动。';
  }
  if (stage.id === 'patch') return flow.modification ? flow.modification.summary || '代码修改已生成，等待确认。' : '等待 Agent 生成代码修改。';
  if (stage.id === 'validation') return stage.status === 'skipped' ? '本次没有自动检查命令。' : flow.checks.length ? `${flow.checks.filter((check) => check.passed).length} / ${flow.checks.length} 项自动检查通过。` : '等待自动检查或人工验证。';
  if (stage.id === 'release') return flow.repositories.length ? `${flow.repositories.filter((repo) => ['merged', 'released'].includes(repo.status)).length} / ${flow.repositories.length} 个仓库已完成。` : '等待创建提交或合并请求。';
  return workItem.lifecycleStatus === 'cancelled' ? '工作项已取消。' : workItem.lifecycleStatus === 'rejected' ? '工作项已拒绝。' : '工作项生命周期已结束。';
}

function stageParticipants(stage: FlowStage, workItem: WorkItem) {
  if (stage.id === 'created') return workItem.createdBy || '系统';
  if (stage.id === 'approval' || stage.id === 'patch') return '系统负责人';
  if (stage.id === 'execution') return stage.agents?.map((agent) => agent.role).filter(Boolean).join('、') || 'Coding Supervisor / Agent';
  if (stage.id === 'validation') return 'Agent / 验证人员';
  if (stage.id === 'release') return 'GitLab / 发布流程';
  return '系统';
}

function eventSummary(event: WorkItemEvent) {
  const payload = eventPayload(event);
  const failed = failureReason(event);
  if (failed) return failed;
  if (event.eventType === 'AgentStageCompleted') return String(payload?.summary || `${payload?.role || 'Agent'} 执行完成`);
  if (event.eventType === 'ModificationCompleted') return String(payload?.summary || '已生成代码修改');
  if (event.eventType.startsWith('MergeRequest')) return `${payload?.repo || '仓库'}${payload?.mrIid ? ` · MR !${payload.mrIid}` : ''}`;
  if (event.eventType.startsWith('Validation')) return Array.isArray(payload?.commands) ? `${payload.commands.length} 项自动检查` : '验证结果已记录';
  return `${event.actorId || event.source || '系统'} · ${event.eventType}`;
}

function stageStatusName(stage: FlowStage) {
  return ({ pending: '未开始', running: '执行中', waiting: stage.waitingFor ? `等待${stage.waitingFor}` : '等待处理', completed: '已完成', failed: '失败', skipped: '已跳过', cancelled: '已取消' } as Record<FlowStageStatus, string>)[stage.status];
}

function agentStatusName(status: AgentStageView['status']) {
  return ({ pending: '待执行', running: '执行中', completed: '已完成', failed: '失败' } as Record<AgentStageView['status'], string>)[status];
}

function repositoryStatusName(status: RepositoryFlowView['status']) {
  return ({ changed: '已有修改', published: '已发布', opened: '等待合并', merged: '已合并', closed: '已关闭', released: '已完成' } as Record<RepositoryFlowView['status'], string>)[status];
}

function attemptStatusName(status: FlowAttempt['status']) {
  return ({ running: '进行中', completed: '已完成', failed: '失败', cancelled: '已取消' } as Record<FlowAttempt['status'], string>)[status];
}

function revisionStatusName(status: WorkItemFlow['revisions'][number]['status']) {
  return ({ running: '修订中', completed: '已完成', failed: '已阻塞' } as const)[status];
}

function waitingRoleName(role?: string) {
  return ({ owner: '系统负责人', worker: 'Agent', gitlab: 'GitLab' } as Record<string, string>)[role || ''] || role || '-';
}

function formatTokenUsage(usage: Record<string, unknown>) {
  const input = Number(usage.input_tokens ?? usage.inputTokens);
  const output = Number(usage.output_tokens ?? usage.outputTokens);
  const parts = [];
  if (Number.isFinite(input)) parts.push(`输入 ${input}`);
  if (Number.isFinite(output)) parts.push(`输出 ${output}`);
  return parts.join(' / ') || '未返回';
}

function elapsedTime(start?: string, end?: string) {
  if (!start) return '-';
  return formatDuration(Math.max(0, new Date(end || Date.now()).getTime() - new Date(start).getTime()));
}

function formatDuration(value?: number) {
  if (value == null || !Number.isFinite(value)) return '-';
  const minutes = Math.floor(value / 60000);
  if (minutes < 1) return '不到 1 分钟';
  if (minutes < 60) return `${minutes} 分钟`;
  const hours = Math.floor(minutes / 60);
  if (hours < 24) return `${hours} 小时 ${minutes % 60} 分钟`;
  return `${Math.floor(hours / 24)} 天 ${hours % 24} 小时`;
}

function shortHash(value: string) {
  return value.length > 8 ? value.slice(0, 8) : value;
}

function formatPayload(payload: string) {
  try {
    return JSON.stringify(JSON.parse(payload), null, 2);
  } catch {
    return payload;
  }
}

function isTerminal(status?: string) {
  return ['completed', 'cancelled', 'rejected'].includes(status || '');
}
