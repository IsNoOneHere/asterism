import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import {
  ArrowRight, Ban, Bot, CalendarCheck2, CheckCircle2, Circle, Clock3, Code2, FileText, GitMerge, History,
  Image, Info, MinusCircle, PackageCheck, ShieldCheck, Workflow, X, XCircle,
} from 'lucide-react';
import { Fragment, useEffect, useRef, useState, type RefObject } from 'react';
import { Link, useLocation, useParams } from 'react-router-dom';
import {
  api, ApiError, ArtifactDetail, ArtifactGraph, ArtifactRef, ArtifactSummary, ArtifactType,
  WorkItem, WorkItemAttachment, WorkItemEvent,
} from '../api/client';
import { ActionConfirmDialog } from '../components/ActionConfirmDialog';
import { errorMessage, ErrorState, formatDateTime, StatusBadge } from '../components/Display';
import { MarkdownContent } from '../components/MarkdownContent';
import {
  AgentStageView, buildWorkItemFlow, CodingPlanView, eventName, eventPayload, failureReason, FlowAttempt, FlowStage, FlowStageId,
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
  artifactRef?: ArtifactRef;
};
type VersionSelection = { artifact: ArtifactSummary; requestId: string };
type CodingPresentation = {
  flow: WorkItemFlow;
};

const TAB_LABELS: Record<DetailTab, string> = { flow: '执行状态', code: '代码变更', audit: '事件审计' };
const ARTIFACT_CHAIN_TYPES: ArtifactType[] = ['PRODUCT', 'PLANNING', 'CODING', 'VALIDATION', 'RELEASE'];

export function WorkItemDetailPage() {
  const { workItemId = '' } = useParams();
  const location = useLocation();
  const queryClient = useQueryClient();
  const [activeTab, setActiveTab] = useState<DetailTab>('flow');
  const [selectedStageId, setSelectedStageId] = useState<FlowStageId | null>(null);
  const [confirmAction, setConfirmAction] = useState<StageAction | null>(null);
  const [actionNote, setActionNote] = useState('');
  const [actionEvidence, setActionEvidence] = useState('');
  const [versionSelection, setVersionSelection] = useState<VersionSelection | null>(null);
  const [continueSelection, setContinueSelection] = useState<VersionSelection | null>(null);
  const [previewAttachment, setPreviewAttachment] = useState<WorkItemAttachment | null>(null);
  const previewDialogRef = useRef<HTMLDialogElement>(null);

  useEffect(() => {
    setActiveTab('flow');
    setSelectedStageId(null);
    setVersionSelection(null);
    setContinueSelection(null);
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
  const attachments = useQuery({
    queryKey: ['work-item-attachments', workItemId],
    queryFn: () => api.workItemAttachments(workItemId),
    enabled: Boolean(workItemId),
    retry: false,
  });
  const artifacts = useQuery({
    queryKey: ['work-item-artifacts', workItemId],
    queryFn: () => api.workItemArtifacts(workItemId),
    enabled: Boolean(workItemId),
    refetchInterval: () => isTerminal(item.data?.lifecycleStatus) ? false : 3000,
    retry: false,
  });
  useEffect(() => {
    // 终态首次出现后再补拉一次事件，避免状态投影先返回而漏掉最后的发布事件。
    if (isTerminal(item.data?.lifecycleStatus) && events.isFetched) {
      void queryClient.refetchQueries({ queryKey: ['work-item-events', workItemId], exact: true });
      void queryClient.refetchQueries({ queryKey: ['work-item-artifacts', workItemId], exact: true });
    }
  }, [events.isFetched, item.data?.lifecycleStatus, queryClient, workItemId]);
  const runAction = useMutation({
    mutationFn: (action: StageAction) => {
      const body = {
        requestId: action.requestId!, expectedStatus: action.expectedStatus!,
        expectedProjectionSequence: action.expectedProjectionSequence!,
        ...(actionNote.trim() ? { note: actionNote.trim() } : {}),
        ...(actionEvidence.trim() ? { evidence: actionEvidence.trim() } : {}),
        ...(action.artifactRef ? { artifactRef: action.artifactRef } : {}),
      };
      return action.ownerApproval
        ? api.approveOwner(workItemId, body)
        : action.mergeCheck ? api.checkMergeStatus(workItemId, body) : api.submitSignal(workItemId, action.signalName!, body);
    },
    onSuccess: () => {
      setActionNote('');
      queryClient.invalidateQueries({ queryKey: ['work-item', workItemId] });
      queryClient.invalidateQueries({ queryKey: ['work-item-events', workItemId] });
      queryClient.invalidateQueries({ queryKey: ['work-item-artifacts', workItemId] });
      queryClient.invalidateQueries({ queryKey: ['artifact-detail'] });
    },
    onError: () => {
      // 操作失败也立即刷新，避免过期按钮继续诱导用户重复提交。
      return Promise.all([
        queryClient.refetchQueries({ queryKey: ['work-item', workItemId], exact: true }),
        queryClient.refetchQueries({ queryKey: ['work-item-events', workItemId], exact: true }),
        queryClient.refetchQueries({ queryKey: ['work-item-artifacts', workItemId], exact: true }),
        queryClient.refetchQueries({ queryKey: ['artifact-detail'] }),
      ]);
    },
    onSettled: () => setConfirmAction(null),
  });
  const selectVersion = useMutation({
    mutationFn: (selection: VersionSelection) => {
      if (!artifacts.data) throw new Error('产物链尚未加载完成');
      return api.selectArtifactVersion(workItemId, {
        requestId: selection.requestId,
        artifact: selection.artifact.ref,
        expectedHeads: artifacts.data.effectiveHeads,
      });
    },
    onSuccess: () => {
      setVersionSelection(null);
      queryClient.invalidateQueries({ queryKey: ['work-item', workItemId] });
      queryClient.invalidateQueries({ queryKey: ['work-item-events', workItemId] });
      queryClient.invalidateQueries({ queryKey: ['work-item-artifacts', workItemId] });
      queryClient.invalidateQueries({ queryKey: ['artifact-detail'] });
    },
    onError: () => Promise.all([
      queryClient.refetchQueries({ queryKey: ['work-item', workItemId], exact: true }),
      queryClient.refetchQueries({ queryKey: ['work-item-events', workItemId], exact: true }),
      queryClient.refetchQueries({ queryKey: ['work-item-artifacts', workItemId], exact: true }),
      queryClient.refetchQueries({ queryKey: ['artifact-detail'] }),
    ]),
  });
  const continueVersion = useMutation({
    mutationFn: (selection: VersionSelection) => {
      if (!artifacts.data) throw new Error('产物链尚未加载完成');
      return api.continueWithArtifact(workItemId, {
        requestId: selection.requestId,
        artifact: selection.artifact.ref,
        expectedHeads: artifacts.data.effectiveHeads,
      });
    },
    onSuccess: () => {
      setContinueSelection(null);
      queryClient.invalidateQueries({ queryKey: ['work-item', workItemId] });
      queryClient.invalidateQueries({ queryKey: ['work-item-events', workItemId] });
      queryClient.invalidateQueries({ queryKey: ['work-item-artifacts', workItemId] });
      queryClient.invalidateQueries({ queryKey: ['artifact-detail'] });
    },
    onError: () => Promise.all([
      queryClient.refetchQueries({ queryKey: ['work-item', workItemId], exact: true }),
      queryClient.refetchQueries({ queryKey: ['work-item-events', workItemId], exact: true }),
      queryClient.refetchQueries({ queryKey: ['work-item-artifacts', workItemId], exact: true }),
      queryClient.refetchQueries({ queryKey: ['artifact-detail'] }),
    ]),
  });
  const workItem = item.data;
  const artifactGraph = artifacts.data;
  const artifactEvents = eventsWithArtifactContent(events.data ?? [], artifactGraph?.nodes ?? []);
  const flow = workItem ? buildWorkItemFlow(workItem, artifactEvents) : null;
  // null 表示自动跟随当前阶段；用户选择历史节点后，轮询只更新数据而不抢回选择。
  const selectedStage = flow?.stages.find((stage) => stage.id === (selectedStageId ?? flow.currentStageId));
  const actions = workItem && flow
    ? (workItem.availableActions ?? [])
      .map((code) => stageAction(code, flow.currentStageId, workItem))
      .filter((action): action is StageAction => Boolean(action))
      .map((action) => bindActionArtifact(action, artifactEvents, artifactGraph, workItem.lifecycleStatus))
      .filter((action): action is StageAction => Boolean(action))
    : [];
  const codingReviewRef = actions.find((action) =>
    ['patch_apply_approved', 'patch_apply_rejected'].includes(action.code))?.artifactRef;
  const codingPresentation = flow ? projectCodingPresentation(flow, artifactGraph, codingReviewRef) : null;
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
        {workItem && <Link className="secondary-action-link" to="/memory">查看项目记忆</Link>}
      </header>

      {workItem && flow && (
        <>
          <WorkItemOverview workItem={workItem} flow={flow} />
          <ArtifactChain
            graph={artifactGraph}
            flow={flow}
            lifecycleStatus={workItem.lifecycleStatus}
            loading={artifacts.isLoading}
            selectingId={selectVersion.isPending ? versionSelection?.artifact.ref.artifactId : undefined}
            continuingId={continueVersion.isPending ? continueSelection?.artifact.ref.artifactId : undefined}
            onSelect={(artifact) => {
              selectVersion.reset();
              setVersionSelection({ artifact, requestId: crypto.randomUUID() });
            }}
            onContinue={(artifact) => {
              continueVersion.reset();
              setContinueSelection({ artifact, requestId: crypto.randomUUID() });
            }}
          />
          {artifacts.isError && <ErrorState title="产物链加载失败" error={artifacts.error} onRetry={() => artifacts.refetch()} />}
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
          {attachments.data?.length ? <RequirementAttachments attachments={attachments.data} onPreview={(attachment) => {
            setPreviewAttachment(attachment);
            if (!previewDialogRef.current?.open) previewDialogRef.current?.showModal();
          }} /> : null}
          {attachments.isError && <ErrorState title="需求附件加载失败" error={attachments.error} onRetry={() => attachments.refetch()} />}
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
              <details className="work-item-basic panel execution-flow-details">
                <summary>查看完整执行流程</summary>
                <FlowGraph flow={flow} selectedStageId={selectedStage.id} onSelect={(stageId) => setSelectedStageId(stageId === flow.currentStageId ? null : stageId)} />
              </details>
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
                flow={codingPresentation?.flow ?? flow}
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
      <dialog ref={previewDialogRef} className="confirm-dialog image-preview-dialog" aria-label="需求附件预览" onClose={() => setPreviewAttachment(null)}>
        {previewAttachment && <img src={api.attachmentUrl(previewAttachment.attachmentId)} alt={`${previewAttachment.filename} 预览`} />}
        <button type="button" className="secondary" onClick={() => previewDialogRef.current?.close()}>关闭预览</button>
      </dialog>
      <ActionConfirmDialog
        open={Boolean(versionSelection)}
        title={versionSelection ? `切换为${artifactTypeMeta(versionSelection.artifact.ref.artifactType).label} v${versionSelection.artifact.ref.version}？` : ''}
        description={versionSelection ? selectionDescription(versionSelection.artifact) : ''}
        confirmLabel="切换当前执行版本"
        pending={selectVersion.isPending}
        tone="primary"
        fields={versionSelection ? <div className="artifact-selection-notes">
          <ul>
            <li>本次只切换当前版本，不会启动 Worker</li>
            <li>后续执行只读取这条有效路线</li>
            <li>已生成的下游产物不会删除</li>
            <li>全部历史版本和审核记录继续保留</li>
          </ul>
          {selectVersion.error && <p role="alert">{errorMessage(selectVersion.error, '版本切换失败，请重试')}</p>}
        </div> : undefined}
        onClose={() => { if (!selectVersion.isPending) { setVersionSelection(null); selectVersion.reset(); } }}
        onConfirm={() => versionSelection && selectVersion.mutate(versionSelection)}
      />
      <ActionConfirmDialog
        open={Boolean(continueSelection)}
        title={continueSelection ? `基于执行计划 v${continueSelection.artifact.ref.version} 继续开发？` : ''}
        description="Worker 将直接执行当前计划，不会重新生成 PlanningArtifact。"
        confirmLabel={continueSelection ? `基于 v${continueSelection.artifact.ref.version} 继续开发` : '继续开发'}
        pending={continueVersion.isPending}
        tone="primary"
        fields={continueSelection ? <div className="artifact-selection-notes">
          <ul>
            <li>直接读取当前计划正文和对应业务需求</li>
            <li>从 Coding 阶段开始生成代码产物</li>
            <li>不会重新调用计划生成</li>
          </ul>
          {continueVersion.error && <p role="alert">{errorMessage(continueVersion.error, '继续开发失败，请重试')}</p>}
        </div> : undefined}
        onClose={() => { if (!continueVersion.isPending) { setContinueSelection(null); continueVersion.reset(); } }}
        onConfirm={() => continueSelection && continueVersion.mutate(continueSelection)}
      />
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
        title={actionErrorTitle(runAction.error)}
        description={actionErrorMessage(runAction.error)}
        confirmLabel="知道了"
        alert
        showCancel={false}
        onClose={() => runAction.reset()}
        onConfirm={() => runAction.reset()}
      />
    </section>
  );
}

const REFRESHED_ACTION_ERRORS = new Set([
  'STALE_WORK_ITEM', 'STALE_ARTIFACT', 'ARTIFACT_REF_REQUIRED', 'ACTION_NOT_AVAILABLE', 'ACTION_PENDING',
]);

function actionErrorTitle(error: unknown) {
  return error instanceof ApiError && REFRESHED_ACTION_ERRORS.has(error.code) ? '工作项已更新' : '操作失败';
}

function actionErrorMessage(error: unknown) {
  if (error instanceof ApiError && REFRESHED_ACTION_ERRORS.has(error.code)) {
    return '工作项或产物版本已更新，页面已刷新，请按当前展示的版本继续。';
  }
  if (error instanceof ApiError && error.code === 'TEMPORAL_SIGNAL_FAILED') {
    return '工作流服务暂时不可用，请稍后重试。';
  }
  return errorMessage(error, '工作项操作失败');
}

function RequirementAttachments({ attachments, onPreview }: { attachments: WorkItemAttachment[]; onPreview: (attachment: WorkItemAttachment) => void }) {
  return (
    <section className="panel requirement-attachments" aria-labelledby="requirement-attachments-title">
      <header>
        <div>
          <h2 id="requirement-attachments-title">需求附件</h2>
          <p>需求沟通时保留的原始截图，点击可放大查看。</p>
        </div>
        <span>{attachments.length} 张</span>
      </header>
      <div className="requirement-attachment-grid">
        {attachments.map((attachment) => (
          <button type="button" key={attachment.attachmentId} className="requirement-attachment-card" onClick={() => onPreview(attachment)}>
            <img src={api.attachmentUrl(attachment.attachmentId)} alt={attachment.filename} />
            <span><Image size={15} aria-hidden="true" /><strong title={attachment.filename}>{attachment.filename}</strong><small>{formatFileSize(attachment.sizeBytes)}</small></span>
          </button>
        ))}
      </div>
    </section>
  );
}

function formatFileSize(bytes: number) {
  if (bytes < 1024) return `${bytes} B`;
  if (bytes < 1024 * 1024) return `${Math.round(bytes / 1024)} KB`;
  return `${(bytes / 1024 / 1024).toFixed(1)} MB`;
}

function WorkItemOverview({ workItem, flow }: { workItem: WorkItem; flow: WorkItemFlow }) {
  const current = flow.stages.find((stage) => stage.id === flow.currentStageId)!;
  return (
    <div className="work-item-overview panel" aria-label="工作项摘要">
      <div><span>当前状态</span><StatusBadge value={workItem.lifecycleStatus} /></div>
      <div><span>当前阶段</span><strong>{flow.activeRevision ? `第 ${flow.activeRevision.revision} 轮修订中`
        : flow.codingPlan?.status === 'proposed' ? '等待计划审批'
          : flow.codingPlan?.status === 'planning' ? 'Supervisor 正在规划' : current.label}</strong></div>
      <div><span>等待角色</span><strong>{current.waitingFor || waitingRoleName(workItem.waitingFor)}</strong></div>
      <div><span>创建时间</span><strong>{formatDateTime(workItem.createdAt)}</strong></div>
      <div><span>已用时</span><strong>{elapsedTime(workItem.createdAt, isTerminal(workItem.lifecycleStatus) ? workItem.updatedAt : undefined)}</strong></div>
    </div>
  );
}

function ArtifactChain({
  graph, flow, lifecycleStatus, loading, selectingId, continuingId, onSelect, onContinue,
}: {
  graph?: ArtifactGraph;
  flow: WorkItemFlow;
  lifecycleStatus: string;
  loading: boolean;
  selectingId?: string;
  continuingId?: string;
  onSelect: (artifact: ArtifactSummary) => void;
  onContinue: (artifact: ArtifactSummary) => void;
}) {
  const [detailArtifact, setDetailArtifact] = useState<ArtifactSummary | null>(null);
  const chainRef = useRef<HTMLDivElement>(null);
  if (loading) return <section className="panel artifact-chain-panel" aria-label="产物链">产物链加载中…</section>;
  const artifacts = graph?.nodes ?? [];
  if (artifacts.length === 0) return null;
  const types = ARTIFACT_CHAIN_TYPES;
  const artifactsById = new Map(artifacts.map((artifact) => [artifact.ref.artifactId, artifact]));
  const pendingRouteChange = findPendingRouteChange(graph!, artifactsById);
  return (
    <section className="panel artifact-chain-panel" aria-label="产物链">
      <header>
        <div className="artifact-chain-heading">
          <h2 id="artifact-chain-title">Artifact 链路</h2>
          <p>当前已批准路线与历史版本统一展示</p>
        </div>
        <CurrentArtifactRoute graph={graph!} artifactsById={artifactsById} />
      </header>
      {pendingRouteChange && <div className="notice" role="status">
        {pendingRouteChange}
      </div>}
      <div className="artifact-chain-scroll">
        <div className="artifact-chain" ref={chainRef} role="group" aria-label="当前与历史版本关系">
          <ArtifactRouteLines containerRef={chainRef} graph={graph!} />
          {types.map((type, index) => {
            const versions = artifacts.filter((artifact) => artifact.ref.artifactType === type)
              .sort((left, right) => left.ref.version - right.ref.version);
            const effective = versions.find((artifact) =>
              graph?.effectiveHeads[type]?.artifactId === artifact.ref.artifactId);
            const previousType = types[index - 1];
            return <Fragment key={type}>
              {index > 0 && <span
                className={`artifact-chain-connector ${graph?.effectiveHeads[previousType] ? 'active' : 'pending'}`}
                aria-hidden="true"
              />}
              <ArtifactStage
                type={type}
                versions={versions}
                effective={effective}
                graph={graph!}
                artifactsById={artifactsById}
                selectingId={selectingId}
                continuingId={continuingId}
                onSelect={onSelect}
                onContinue={onContinue}
                onOpenDetail={setDetailArtifact}
              />
            </Fragment>;
          })}
        </div>
      </div>
      <div className="artifact-route-legend" aria-label="路线说明">
        <div className="artifact-route-legend-items">
          <span><i className="active" />当前已批准路线</span>
          <span><i />历史路线</span>
        </div>
        <p><Info size={16} aria-hidden="true" />已批准 Head 是当前业务事实，待审批版本不会提前替换它。</p>
      </div>
      {lifecycleStatus === 'completed' && !graph?.effectiveHeads.VALIDATION && !graph?.effectiveHeads.RELEASE && (
        <div className="notice" role="status">
          历史兼容链路：该工作项完成于 ReleaseManifest 上线前，系统不会伪造或回填发布产物。
        </div>
      )}
      {lifecycleStatus === 'completed' && graph?.effectiveHeads.VALIDATION && !graph?.effectiveHeads.RELEASE && (
        <div className="notice danger" role="alert">
          发布清单缺失：工作项已完成但未找到 ReleaseManifest，请检查结果产物物化状态。
        </div>
      )}
      {detailArtifact && <ArtifactDrawer
        artifact={detailArtifact}
        graph={graph!}
        flow={flow}
        onClose={() => setDetailArtifact(null)}
      />}
    </section>
  );
}

const DEFAULT_VISIBLE_ARTIFACT_VERSIONS = 2;

type ArtifactRoutePath = {
  key: string;
  active: boolean;
  d: string;
};

function ArtifactRouteLines({
  containerRef, graph,
}: {
  containerRef: RefObject<HTMLDivElement | null>;
  graph: ArtifactGraph;
}) {
  const [paths, setPaths] = useState<ArtifactRoutePath[]>([]);

  useEffect(() => {
    const container = containerRef.current;
    if (!container) return undefined;

    const updatePaths = () => {
      const containerRect = container.getBoundingClientRect();
      const artifactCards = new Map<string, HTMLElement>();
      container.querySelectorAll<HTMLElement>('.artifact-version-node[data-artifact-id]').forEach((node) => {
        const artifactId = node.dataset.artifactId;
        const card = node.querySelector<HTMLElement>('.artifact-version-card');
        if (artifactId && card) artifactCards.set(artifactId, card);
      });
      const emptyCards = new Map<ArtifactType, HTMLElement>();
      container.querySelectorAll<HTMLElement>('.artifact-empty-card[data-artifact-type]').forEach((card) => {
        emptyCards.set(card.dataset.artifactType as ArtifactType, card);
      });

      const nextPaths: ArtifactRoutePath[] = [];
      const activeEdges = new Set<string>();
      const types = ARTIFACT_CHAIN_TYPES;

      // 当前路线始终连接实际生效卡片，版本切换不再依赖卡片排序。
      for (let index = 1; index < types.length; index += 1) {
        const source = graph.effectiveHeads[types[index - 1]];
        if (!source) continue;
        const target = graph.effectiveHeads[types[index]];
        const sourceCard = artifactCards.get(source.artifactId);
        const targetCard = target
          ? artifactCards.get(target.artifactId)
          : emptyCards.get(types[index]);
        if (!sourceCard || !targetCard) continue;
        if (target) activeEdges.add(`${source.artifactId}:${target.artifactId}`);
        nextPaths.push({
          key: `active-${types[index - 1]}-${types[index]}`,
          active: true,
          d: artifactRoutePath(containerRect, sourceCard, targetCard),
        });
      }

      graph.edges
        .filter((edge) => edge.edgeType === 'DERIVED_FROM')
        .forEach((edge) => {
          if (activeEdges.has(`${edge.fromArtifactId}:${edge.toArtifactId}`)) return;
          const sourceCard = artifactCards.get(edge.fromArtifactId);
          const targetCard = artifactCards.get(edge.toArtifactId);
          if (!sourceCard || !targetCard) return;
          nextPaths.push({
            key: `history-${edge.fromArtifactId}-${edge.toArtifactId}`,
            active: false,
            d: artifactRoutePath(containerRect, sourceCard, targetCard),
          });
        });

      setPaths((current) =>
        JSON.stringify(current) === JSON.stringify(nextPaths) ? current : nextPaths);
    };

    updatePaths();
    const resizeObserver = typeof ResizeObserver === 'undefined'
      ? undefined
      : new ResizeObserver(updatePaths);
    resizeObserver?.observe(container);
    window.addEventListener('resize', updatePaths);
    return () => {
      resizeObserver?.disconnect();
      window.removeEventListener('resize', updatePaths);
    };
  }, [containerRef, graph]);

  return <svg className="artifact-route-lines" aria-hidden="true">
    <defs>
      <marker id="artifact-route-arrow-active" viewBox="0 0 8 8" refX="7" refY="4" markerWidth="7" markerHeight="7" orient="auto">
        <path d="M 1 1 L 7 4 L 1 7" />
      </marker>
      <marker id="artifact-route-arrow-history" viewBox="0 0 8 8" refX="7" refY="4" markerWidth="7" markerHeight="7" orient="auto">
        <path d="M 1 1 L 7 4 L 1 7" />
      </marker>
    </defs>
    {paths.map((path) => <path
      key={path.key}
      className={path.active ? 'active' : 'history'}
      d={path.d}
      markerEnd={`url(#artifact-route-arrow-${path.active ? 'active' : 'history'})`}
    />)}
  </svg>;
}

function artifactRoutePath(
  containerRect: DOMRect,
  sourceCard: HTMLElement,
  targetCard: HTMLElement,
) {
  const source = sourceCard.getBoundingClientRect();
  const target = targetCard.getBoundingClientRect();
  const startX = source.right - containerRect.left + 1;
  const endX = target.left - containerRect.left - 7;
  let startY = source.top - containerRect.top + source.height / 2;
  const endY = target.top - containerRect.top + target.height / 2;
  // 同一行卡片即使高度略有差异，也保持连接线水平，避免出现不必要的小台阶。
  if (Math.abs(source.top - target.top) < 18) {
    return `M ${startX} ${endY} H ${endX}`;
  }
  // 路径位置与当前版本无关，切换版本时只改变线条颜色，避免线路发生交叉。
  if (Math.abs(endY - startY) > 24) {
    startY += Math.sign(endY - startY) * Math.min(52, Math.abs(endY - startY) * .24);
  }
  if (Math.abs(endY - startY) < 3) return `M ${startX} ${startY} H ${endX}`;
  const elbowX = startX + Math.min(54, Math.max(28, (endX - startX) * .44));
  return `M ${startX} ${startY} H ${elbowX} V ${endY} H ${endX}`;
}

function ArtifactStage({
  type, versions, effective, graph, artifactsById, selectingId,
  continuingId, onSelect, onContinue, onOpenDetail,
}: {
  type: ArtifactType;
  versions: ArtifactSummary[];
  effective?: ArtifactSummary;
  graph: ArtifactGraph;
  artifactsById: Map<string, ArtifactSummary>;
  selectingId?: string;
  continuingId?: string;
  onSelect: (artifact: ArtifactSummary) => void;
  onContinue: (artifact: ArtifactSummary) => void;
  onOpenDetail: (artifact: ArtifactSummary) => void;
}) {
  const meta = artifactTypeMeta(type);
  const ordered = versions;
  const [expanded, setExpanded] = useState(false);
  const collapsed = ordered.slice(0, DEFAULT_VISIBLE_ARTIFACT_VERSIONS);
  // 折叠时保留首个版本与当前 Head，同时维持版本从小到大的阅读顺序。
  if (effective && !collapsed.some((artifact) => artifact.ref.artifactId === effective.ref.artifactId)) {
    collapsed[collapsed.length - 1] = effective;
  }
  const visible = expanded ? ordered : collapsed;
  const hiddenCount = ordered.length - visible.length;
  const systemManagedResult = type === 'VALIDATION' || type === 'RELEASE';
  return <article className="artifact-chain-stage">
    <header>
      <h3 aria-label={meta.label}>{meta.label}</h3>
    </header>
    {visible.length > 0 ? <>
      <ol className={`artifact-version-stack ${expanded ? 'expanded' : ''}`} aria-label={`${meta.label}版本`}>
        {visible.map((artifact, index) => {
          const current = artifact.ref.artifactId === effective?.ref.artifactId;
          const featured = !effective && index === 0;
          const replaced = graph.edges.some((edge) =>
            edge.edgeType === 'SUPERSEDES' && edge.fromArtifactId === artifact.ref.artifactId);
          const historical = !current && (replaced || artifact.ref.status !== 'PROPOSED');
          return <li
            key={artifact.ref.artifactId}
            className={`artifact-version-node ${current ? 'current-node' : featured ? 'featured-node' : historical ? 'history-node' : 'candidate-node'}`}
            data-artifact-id={artifact.ref.artifactId}
            data-artifact-type={artifact.ref.artifactType}
            data-effective={current || undefined}
          >
            <ArtifactVersionCard
              artifact={artifact}
              artifactsById={artifactsById}
              effective={current}
              historical={historical}
              versionAction={graph.versionActions?.[artifact.ref.artifactId]}
              selectionBusy={Boolean(selectingId)}
              selecting={selectingId === artifact.ref.artifactId}
              onSelect={onSelect}
              onOpenDetail={onOpenDetail}
              continueAction={type === 'PLANNING' && current ? {
                continuing: continuingId === artifact.ref.artifactId,
                onContinue,
              } : undefined}
            />
          </li>;
        })}
      </ol>
      {ordered.length > DEFAULT_VISIBLE_ARTIFACT_VERSIONS && <button
        type="button"
        className="artifact-version-more"
        aria-expanded={expanded}
        onClick={() => setExpanded((value) => !value)}
      >{expanded ? '收起历史版本' : `展开更多历史版本（${hiddenCount}）`}</button>}
      {type === 'CODING' && <p className="artifact-system-managed-note">
        代码页默认使用 Coding Head；仅代码确认时展示动作精确绑定的待审版本。
      </p>}
      {systemManagedResult && <p className="artifact-system-managed-note">
        最新执行结果由系统自动采用，历史版本仅供查看。
      </p>}
    </> : <div className="artifact-empty-card" data-artifact-type={type}>
      <ArtifactTypeIcon type={type} />
      <strong>尚未生成</strong>
      <span>{meta.emptyHint}</span>
    </div>}
  </article>;
}

function CurrentArtifactRoute({ graph, artifactsById }: {
  graph: ArtifactGraph;
  artifactsById: Map<string, ArtifactSummary>;
}) {
  const types = ARTIFACT_CHAIN_TYPES;
  return <section className="artifact-current-route" aria-label="当前已批准路线">
    <strong>当前已批准路线：</strong>
    <ol>
      {types.map((type, index) => {
        const reference = graph.effectiveHeads[type];
        const artifact = reference ? artifactsById.get(reference.artifactId) : undefined;
        const meta = artifactTypeMeta(type);
        return <Fragment key={type}>
          {index > 0 && <li className="artifact-route-arrow" aria-hidden="true"><ArrowRight size={14} /></li>}
          <li className={artifact ? 'active' : 'missing'} title={artifact ? artifactSummary(artifact) : meta.routeEmptyHint}>
            <span>{meta.shortLabel}</span>
            <strong>{artifact ? `v${artifact.ref.version}` : '待生成'}</strong>
          </li>
        </Fragment>;
    })}
    </ol>
  </section>;
}

// 返工候选不能提前覆盖业务事实，但界面必须说明批准后哪些下游会失效。
function findPendingRouteChange(
  graph: ArtifactGraph,
  artifactsById: Map<string, ArtifactSummary>,
) {
  const proposal = [...graph.nodes]
    .filter((artifact) => artifact.ref.status === 'PROPOSED'
      && artifact.ref.supersedesArtifactId === graph.effectiveHeads[artifact.ref.artifactType]?.artifactId)
    .sort((left, right) => right.ref.version - left.ref.version)[0];
  if (!proposal) return '';
  const typeIndex = ARTIFACT_CHAIN_TYPES.indexOf(proposal.ref.artifactType);
  const replaced = proposal.ref.supersedesArtifactId
    ? artifactsById.get(proposal.ref.supersedesArtifactId)
    : undefined;
  const affected = [replaced, ...ARTIFACT_CHAIN_TYPES.slice(typeIndex + 1)
    .map((type) => graph.effectiveHeads[type])
    .map((reference) => reference ? artifactsById.get(reference.artifactId) : undefined)]
    .filter((artifact): artifact is ArtifactSummary => Boolean(artifact));
  const affectedVersions = affected
    .map((artifact) => `${artifactTypeMeta(artifact.ref.artifactType).shortLabel} v${artifact.ref.version}`)
    .join('、');
  const proposalVersion = `${artifactTypeMeta(proposal.ref.artifactType).shortLabel} v${proposal.ref.version}`;
  return `${proposalVersion} 正在等待审批；当前已批准路线暂不改变。批准后 ${affectedVersions} 将转为历史，后续从 ${proposalVersion} 继续生成。`;
}

function ArtifactDrawer({ artifact, graph, flow, onClose }: {
  artifact: ArtifactSummary;
  graph: ArtifactGraph;
  flow: WorkItemFlow;
  onClose: () => void;
}) {
  const dialogRef = useRef<HTMLDialogElement>(null);
  const artifactId = artifact.ref.artifactId;
  const detail = useQuery({
    queryKey: ['artifact-detail', artifactId],
    queryFn: () => api.artifactDetail(artifactId),
    enabled: Boolean(artifactId),
    retry: false,
  });
  useEffect(() => {
    if (!dialogRef.current?.open) dialogRef.current?.showModal();
  }, [artifactId]);
  const title = `${artifactTypeMeta(artifact.ref.artifactType).shortLabel} v${artifact.ref.version}`;
  return <dialog
    ref={dialogRef}
    className="artifact-drawer"
    aria-labelledby="artifact-drawer-title"
    onClose={onClose}
  >
    <header>
      <div>
        <span>{artifactTypeMeta(artifact.ref.artifactType).label}</span>
        <h2 id="artifact-drawer-title">{title}</h2>
        <p>内容、版本演进和审核记录集中展示，不影响主链路浏览。</p>
      </div>
      <button type="button" aria-label="关闭详情" onClick={() => dialogRef.current?.close()}>
        <X size={19} aria-hidden="true" />
      </button>
    </header>
    <div className="artifact-drawer-body">
      <>
        {detail.isError && <ErrorState title="产物详情加载失败" error={detail.error} onRetry={() => detail.refetch()} />}
        <ArtifactDetailOverview artifact={artifact} detail={detail.data} />
        <section className="artifact-drawer-section">
          <header><h3>完整内容</h3></header>
          <ArtifactContentView artifact={artifact} />
        </section>
        <section className="artifact-drawer-section">
          <header><h3>版本演进 / 审计</h3></header>
          <ArtifactEvolutionAudit artifact={artifact} graph={graph} flow={flow} detail={detail.data} />
        </section>
      </>
    </div>
  </dialog>;
}

function ArtifactVersionCard({
  artifact, artifactsById, effective, historical, versionAction, selectionBusy, selecting,
  onSelect, onOpenDetail, continueAction,
}: {
  artifact: ArtifactSummary;
  artifactsById: Map<string, ArtifactSummary>;
  effective: boolean;
  historical: boolean;
  versionAction?: ArtifactGraph['versionActions'][string];
  selectionBusy: boolean;
  selecting: boolean;
  onSelect: (artifact: ArtifactSummary) => void;
  onOpenDetail: (artifact: ArtifactSummary) => void;
  continueAction?: {
    continuing: boolean;
    onContinue: (artifact: ArtifactSummary) => void;
  };
}) {
  const ref = artifact.ref;
  const parent = ref.parentArtifactId ? artifactsById.get(ref.parentArtifactId) : undefined;
  const supersedes = ref.supersedesArtifactId ? artifactsById.get(ref.supersedesArtifactId) : undefined;
  const meta = artifactTypeMeta(ref.artifactType);
  const digest = artifact.reviewNote || artifactContentDigest(artifact);
  const manuallySelectable = ['PRODUCT', 'PLANNING', 'CODING'].includes(ref.artifactType);
  return <article className={`artifact-version-card ${ref.status.toLowerCase()} ${effective ? 'effective' : ''}`}>
    <div className="artifact-card-head">
      <ArtifactTypeIcon type={ref.artifactType} />
      <div className="artifact-card-title">
        <div className="artifact-version-heading">
          <h4>{meta.shortLabel} v{ref.version}</h4>
        </div>
        <div className="artifact-version-badges">
          <StatusBadge value={ref.status.toLowerCase()} />
          {effective && <em>当前使用</em>}
          {historical && <em className="history">历史版本</em>}
        </div>
      </div>
    </div>
    <div className="artifact-version-content">
      <time>创建于 {formatDateTime(artifact.createdAt)}</time>
      <div className="artifact-version-meta">
        {parent && <span>基于 {artifactTypeMeta(parent.ref.artifactType).shortLabel} v{parent.ref.version}</span>}
        {supersedes && <span>修订自 {artifactTypeMeta(supersedes.ref.artifactType).label} v{supersedes.ref.version}</span>}
      </div>
      <p className="artifact-version-summary" title={digest}>{digest}</p>
    </div>
    <div className="artifact-version-actions">
      <button type="button" className="artifact-detail-trigger" onClick={() => onOpenDetail(artifact)}>查看详情</button>
      {continueAction ? <button
        type="button"
        className="artifact-card-continue-button"
        disabled={!versionAction?.canContinue || continueAction.continuing}
        title={!versionAction?.canContinue
          ? versionAction?.continueDisabledReason || '当前不能继续开发，请刷新产物链'
          : undefined}
        onClick={() => continueAction.onContinue(artifact)}
      >{continueAction.continuing ? '正在启动…' : `基于 v${ref.version} 继续开发`}</button> : !effective && manuallySelectable && <button
        type="button"
        className="artifact-use-version"
        aria-label={`${meta.shortLabel} v${ref.version} 切换为当前版本`}
        title={!versionAction?.canSelect
          ? versionAction?.selectDisabledReason || '当前不能切换该版本，请刷新产物链'
          : undefined}
        disabled={!versionAction?.canSelect || selectionBusy}
        onClick={() => onSelect(artifact)}
      >{selecting ? '切换中…' : '切换为当前版本'}</button>}
    </div>
    {continueAction && !versionAction?.canContinue && <small className="artifact-card-continue-note">
      {versionAction?.continueDisabledReason || '当前不能继续开发，请刷新产物链'}
    </small>}
  </article>;
}

function ArtifactTypeIcon({ type }: { type: ArtifactType }) {
  const Icon = type === 'PRODUCT' ? FileText
    : type === 'PLANNING' ? CalendarCheck2
      : type === 'CODING' ? Code2
        : type === 'VALIDATION' ? ShieldCheck : PackageCheck;
  return <span className={`artifact-type-icon ${type.toLowerCase()}`} aria-hidden="true"><Icon size={18} strokeWidth={2.3} /></span>;
}

function ArtifactDetailOverview({ artifact, detail }: {
  artifact: ArtifactSummary;
  detail?: ArtifactDetail;
}) {
  const transitions = detail?.transitions ?? [];
  const latest = transitions[transitions.length - 1];
  return <section className="artifact-detail-overview" aria-label="内容与审计摘要">
    <div>
      <strong>内容摘要</strong>
      <p>{artifactContentDigest(artifact)}</p>
    </div>
    <div>
      <strong>最近状态</strong>
      <p>{latest
        ? `${latest.fromStatus || 'CREATED'} → ${latest.toStatus} · ${formatDateTime(latest.createdAt)}`
        : '暂无状态流转'}</p>
    </div>
    <div>
      <strong>审计记录</strong>
      <p>{detail ? `${detail.transitions.length} 条流转 · ${visibleArtifactEvidence(detail).length} 条业务证据` : '加载中…'}</p>
    </div>
  </section>;
}

function ArtifactContentView({ artifact }: { artifact: ArtifactSummary }) {
  const content = artifact.content ?? {};
  if (artifact.ref.artifactType === 'PRODUCT') {
    const criteria = stringArray(content.acceptanceCriteria);
    return <section className="artifact-content-view">
      <h4>产品需求</h4>
      <dl>
        <div><dt>标题</dt><dd>{textValue(content.title) || '-'}</dd></div>
        <div><dt>目标</dt><dd>{textValue(content.goal) || '-'}</dd></div>
        <div><dt>范围</dt><dd>{readableValue(content.scope)}</dd></div>
      </dl>
      {criteria.length > 0 && <><h5>验收标准</h5><ul>{criteria.map((criterion) => <li key={criterion}>{criterion}</li>)}</ul></>}
    </section>;
  }
  if (artifact.ref.artifactType === 'PLANNING') {
    return <section className="artifact-content-view">
      <h4>执行计划</h4>
      <MarkdownContent markdown={textValue(content.planMarkdown) || '未返回计划正文'} />
      <ArtifactRevisions revisions={content.baseRevisions} />
    </section>;
  }
  if (artifact.ref.artifactType === 'VALIDATION') {
    const result = textValue(content.result) || 'UNKNOWN';
    const commands = arrayObjects(content.commands);
    return <section className="artifact-content-view artifact-validation-content">
      <h4>验证报告</h4>
      <dl>
        <div><dt>结果</dt><dd><strong className={`artifact-result ${result.toLowerCase()}`}>{validationResultLabel(result)}</strong></dd></div>
        <div><dt>模式</dt><dd>{textValue(content.mode) || '-'}</dd></div>
        <div><dt>验证批次</dt><dd><code>{textValue(content.validationRunId) || '-'}</code></dd></div>
        <div><dt>Coding Hash</dt><dd><code>{shortHash(textValue(content.codingContentHash)) || '-'}</code></dd></div>
      </dl>
      {commands.length > 0 && <><h5>验证命令</h5><ol className="artifact-command-results">{commands.map((command, index) => <li key={`${textValue(command.repo)}-${textValue(command.command)}-${index}`}>
        <strong>{textValue(command.repo) || '默认仓库'}</strong>
        <code>{textValue(command.command) || '-'}</code>
        <span>exit {numericValue(command.exitCode) ?? '-'}</span>
      </li>)}</ol></>}
      {textValue(content.errorSummary) && <><h5>错误摘要</h5><p>{textValue(content.errorSummary)}</p></>}
      {textValue(content.manualEvidence) && <><h5>人工证据</h5><p>{textValue(content.manualEvidence)}</p></>}
    </section>;
  }
  if (artifact.ref.artifactType === 'RELEASE') {
    const repositories = arrayObjects(content.repositories);
    return <section className="artifact-content-view artifact-release-content">
      <h4>发布清单</h4>
      <dl>
        <div><dt>发布批次</dt><dd><code>{textValue(content.releaseId) || '-'}</code></dd></div>
        <div><dt>发布方式</dt><dd>{textValue(content.releaseMode) || '-'}</dd></div>
        <div><dt>目标环境</dt><dd>{textValue(content.targetKey) || 'default'}</dd></div>
      </dl>
      {repositories.length > 0 ? <div className="artifact-release-repositories">{repositories.map((repository, index) => <article key={`${textValue(repository.repo)}-${index}`}>
        <h5>{textValue(repository.repo) || `仓库 ${index + 1}`}</h5>
        <dl>
          <div><dt>分支</dt><dd>{textValue(repository.branch) || '-'}</dd></div>
          <div><dt>Commit</dt><dd><code>{textValue(repository.commitHash) || '-'}</code></dd></div>
          <div><dt>MR</dt><dd>{numericValue(repository.mrIid) ?? '-'}</dd></div>
          <div><dt>最终状态</dt><dd>{textValue(repository.finalState) || '-'}</dd></div>
        </dl>
        {stringArray(repository.changedPaths).length > 0 && <ul className="changed-files">{stringArray(repository.changedPaths).map((path) => <li key={path}><code>{path}</code></li>)}</ul>}
      </article>)}</div> : <p className="empty-inline">未返回仓库发布结果</p>}
    </section>;
  }
  const changes = arrayObjects(content.repoChanges);
  return <section className="artifact-content-view">
    <h4>代码结果</h4>
    <p>{textValue(content.summary) || '未返回结果摘要'}</p>
    {changes.length > 0 ? <div className="artifact-repo-changes">{changes.map((change, index) => {
      const repo = textValue(change.repo) || `仓库 ${index + 1}`;
      const paths = stringArray(change.changedPaths);
      const diff = textValue(change.diffPatch);
      return <article key={`${repo}-${index}`}>
        <h5>{repo}</h5>
        {textValue(change.summary) && <p>{textValue(change.summary)}</p>}
        {paths.length > 0 && <ul className="changed-files">{paths.map((path) => <li key={path}><code title={path}>{path}</code></li>)}</ul>}
        {diff && <details className="code-diff"><summary>完整 Diff</summary>
          <div className="code-diff-scroll" role="region" aria-label={`${repo} Artifact Diff`} tabIndex={0}><pre><code>{diff}</code></pre></div>
        </details>}
      </article>;
    })}</div> : <p className="empty-inline">未返回正式仓库变更</p>}
    <ArtifactRevisions revisions={content.baseRevisions} />
  </section>;
}

function ArtifactRevisions({ revisions }: { revisions: unknown }) {
  const values = arrayEntries(revisions);
  if (values.length === 0) return null;
  return <dl className="artifact-revisions">
    {values.map(([repo, revision]) => <div key={repo}><dt>{repo}</dt><dd><code>{String(revision)}</code></dd></div>)}
  </dl>;
}

function ArtifactEvolutionAudit({ artifact, graph, flow, detail }: {
  artifact: ArtifactSummary;
  graph: ArtifactGraph;
  flow: WorkItemFlow;
  detail?: ArtifactDetail;
}) {
  const type = artifact.ref.artifactType;
  const meta = artifactTypeMeta(type);
  const currentId = graph.effectiveHeads[type]?.artifactId;
  const versions = graph.nodes.filter((node) => node.ref.artifactType === type)
    .sort((left, right) => left.ref.version - right.ref.version);
  const artifactsById = new Map(graph.nodes.map((node) => [node.ref.artifactId, node]));
  return <div className="artifact-evolution-audit">
    <div className="artifact-audit-view">
      <section>
        <h4>版本演进</h4>
        <ol className="artifact-evolution-list">
          {versions.map((version) => {
            const superseded = version.ref.supersedesArtifactId
              ? artifactsById.get(version.ref.supersedesArtifactId)
              : undefined;
            const revisions = codingArtifactRevisions(flow, graph, version);
            const validationFailed = type === 'VALIDATION'
              && textValue(version.content?.result) === 'FAILED';
            return <li key={version.ref.artifactId} className={validationFailed ? 'failed' : undefined}>
              <div className="artifact-evolution-title">
                <strong>{meta.shortLabel} v{version.ref.version}</strong>
                <em>{version.ref.artifactId === currentId ? '当前使用' : '历史版本'}</em>
              </div>
              <span>修改人：{version.createdBy || '-'} · {formatDateTime(version.createdAt)}</span>
              <p><b>版本变化：</b>{superseded ? `v${version.ref.version} 替代 v${superseded.ref.version}` : '初始版本'}</p>
              <p><b>{type === 'CODING' ? 'Diff 摘要' : '内容摘要'}：</b>{artifactVersionAuditSummary(version)}</p>
              {version.reviewNote && <p><b>审核 / 打回原因：</b>{version.reviewNote}</p>}
              {validationFailed && <p className="artifact-evolution-failure"><b>验证失败：</b>
                {textValue(version.content?.errorSummary) || '未记录失败摘要'}</p>}
              {version.reviewedBy && <span>审核人：{version.reviewedBy} · {formatDateTime(version.reviewedAt || undefined)}</span>}
              {revisions.length > 0 && <div className="artifact-revision-audit">
                {revisions.map((revision) => <div key={revision.id}>
                  <strong>第 {revision.revision} 轮 · {revisionStatusName(revision.status)} · {revision.revisionMode === 'incremental' ? '增量修订' : '全量修订'}</strong>
                  <span>修改人：{revision.requestedBy || '-'} · {formatDateTime(revision.requestedAt)}</span>
                  <p><b>打回原因：</b>{revision.note || '未记录修订意见'}</p>
                  <small><b>Diff 摘要：</b>{revision.diffSummary || '等待本轮修订产出'}</small>
                </div>)}
              </div>}
            </li>;
          })}
        </ol>
      </section>
    </div>
    <ArtifactAuditView detail={detail} />
  </div>;
}

function artifactVersionAuditSummary(artifact: ArtifactSummary) {
  if (artifact.ref.artifactType !== 'CODING') return artifactContentDigest(artifact);
  const changes = arrayObjects(artifact.content?.repoChanges ?? artifact.content?.repo_changes);
  const paths = new Set(changes.flatMap((change) =>
    stringArray(change.changedPaths ?? change.changed_paths)));
  const summary = textValue(artifact.content?.summary) || '未返回代码摘要';
  return `${summary} · ${changes.length} 个仓库变更 · ${paths.size} 个文件`;
}

function ArtifactAuditView({ detail }: { detail?: ArtifactDetail }) {
  if (!detail) return <p className="empty-inline">审计记录加载中…</p>;
  // 普通 Workbench 只展示业务 Evidence，执行 Session 和模型遥测保留在审计接口中。
  const visibleEvidence = visibleArtifactEvidence(detail);
  return <div className="artifact-audit-view">
    <section>
      <h4>状态流转</h4>
      {detail.transitions.length > 0 ? <ol>{detail.transitions.map((transition) => <li key={transition.transitionId}>
        <strong>{transition.fromStatus || 'CREATED'} → {transition.toStatus}</strong>
        <span>{transition.actor || '-'} · {formatDateTime(transition.createdAt)}</span>
        {transition.note && <p>{transition.note}</p>}
      </li>)}</ol> : <p className="empty-inline">暂无状态流转记录</p>}
    </section>
    <section>
      <h4>执行证据</h4>
      {visibleEvidence.length > 0 ? <ol>{visibleEvidence.map((evidence) => <li key={evidence.evidenceId}>
        <strong>{evidence.evidenceType}</strong>
        <span>{evidence.actor || '-'} · {formatDateTime(evidence.createdAt)}</span>
        <pre>{JSON.stringify(sanitizeAuditValue(evidence.payload), null, 2)}</pre>
      </li>)}</ol> : <p className="empty-inline">暂无执行证据</p>}
    </section>
  </div>;
}

function visibleArtifactEvidence(detail: ArtifactDetail) {
  return detail.evidence.filter((evidence) =>
    !['PlanningExecution', 'CodingExecution'].includes(evidence.evidenceType));
}

function artifactContentDigest(artifact: ArtifactSummary) {
  const content = artifact.content ?? {};
  if (artifact.ref.artifactType === 'PRODUCT') {
    return compactArtifactText(textValue(content.goal) || textValue(content.title) || '产品需求内容待补充');
  }
  if (artifact.ref.artifactType === 'PLANNING') {
    return compactArtifactText(textValue(content.planMarkdown) || '执行计划正文待补充');
  }
  if (artifact.ref.artifactType === 'VALIDATION') {
    const result = textValue(content.result) || 'UNKNOWN';
    const commands = arrayObjects(content.commands);
    return compactArtifactText(`${validationResultLabel(result)} · ${commands.length} 条验证命令`);
  }
  if (artifact.ref.artifactType === 'RELEASE') {
    const repositories = arrayObjects(content.repositories);
    return compactArtifactText(`${textValue(content.releaseMode) || '发布'} · ${repositories.length} 个仓库`);
  }
  const changes = arrayObjects(content.repoChanges);
  const summary = textValue(content.summary) || '代码结果摘要待补充';
  return compactArtifactText(`${summary}${changes.length ? ` · ${changes.length} 个仓库变更` : ''}`);
}

function compactArtifactText(value: string) {
  const normalized = value
    .replace(/^#{1,6}\s*/gm, '')
    .replace(/[*_`>-]/g, ' ')
    .replace(/\s+/g, ' ')
    .trim();
  return normalized.length > 120 ? `${normalized.slice(0, 120)}…` : normalized;
}

function validationResultLabel(result: string) {
  return ({ PASSED: '验证通过', FAILED: '验证失败', SKIPPED: '已跳过', ERROR: '验证异常' } as Record<string, string>)[result] || result;
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

      <div className="stage-detail-body">
        {stage.id === 'execution' && <>
          <CodingExecutionPhases plan={flow.codingPlan} agents={stage.agents ?? []} modificationReady={Boolean(flow.modification)} />
          {flow.codingPlan && <CodingPlanDetail plan={flow.codingPlan} />}
          <ExecutionDetail agents={stage.agents ?? []} plan={flow.codingPlan} />
        </>}
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
      </div>
    </section>
  );
}

function CodingExecutionPhases({ plan, agents, modificationReady }: {
  plan: CodingPlanView | null;
  agents: AgentStageView[];
  modificationReady: boolean;
}) {
  const planningCompleted = plan?.status === 'approved' || agents.length > 0 || modificationReady;
  const planningState = planningCompleted ? 'completed' : plan?.status === 'proposed' ? 'waiting' : plan ? 'running' : 'pending';
  const codingState = modificationReady ? 'completed' : planningCompleted ? 'running' : 'pending';
  const planningText = planningCompleted ? '已批准' : plan?.status === 'proposed' ? '等待负责人' : plan ? 'Supervisor 规划中' : '未开始';
  const codingText = modificationReady ? '修改已生成' : agents.length > 0 ? 'Supervisor / Agent 执行中'
    : planningCompleted ? '准备启动' : '等待计划批准';

  return <ol className="coding-phase-overview" aria-label="计划审批与代码开发进度">
    <li className={planningState}><span>1</span><div><strong>计划审批</strong><small>{planningText}</small></div></li>
    <li className={codingState}><span>2</span><div><strong>代码开发</strong><small>{codingText}</small></div></li>
  </ol>;
}

function CodingPlanDetail({ plan }: { plan: CodingPlanView }) {
  return <section className={`coding-plan ${plan.status}`} aria-labelledby="coding-plan-title">
    <header><h3 id="coding-plan-title">Coding Plan · 第 {plan.revision} 版</h3><span>{planStatusName(plan.status)}</span></header>
    <MarkdownContent className="coding-plan-markdown" markdown={plan.planMarkdown} />
    <p className="coding-plan-boundary">计划中的文件位置只作为证据，实际写权限仍以仓库允许/禁止路径为准。</p>
  </section>;
}

function ExecutionDetail({ agents, plan }: { agents: AgentStageView[]; plan: CodingPlanView | null }) {
  if (agents.length === 0) return <div className="empty stage-empty">{plan?.status === 'proposed' ? '等待负责人审批计划。' : '等待 Coding Supervisor 启动。'}</div>;
  return (
    <details className="execution-detail">
      <summary>查看 Agent 执行详情</summary>
      <ol className="agent-lane" aria-label="Agent 执行进度">
        {agents.map((agent) => (
          <li key={`${agent.index}-${agent.role}`} className={agent.status}>
            <span className="agent-icon"><Bot size={17} /></span>
            <div><strong>{agent.role || 'Developer Agent'}{agent.repo && <small> · {agent.repo}</small>}</strong><p>{agent.summary || agent.engine || '等待执行'}</p></div>
            <em>{agentStatusName(agent.status)}{agent.changedPaths.length ? ` · ${agent.changedPaths.length} 个文件` : ''}</em>
          </li>
        ))}
      </ol>
    </details>
  );
}

function PatchDetail({ flow }: { flow: WorkItemFlow }) {
  if (!flow.modification) return null;
  const result = modificationPresentation(flow);
  return (
    <section className="change-summary" aria-labelledby="change-summary-title">
      <header>
        <div>
          <span>修改结果</span>
          <strong id="change-summary-title">{result.title}</strong>
        </div>
        <span>{result.changedPathCount} 个文件</span>
      </header>
      {result.detail && <p className="change-summary-detail">{result.detail}</p>}
      <p className="change-summary-note">完整文件列表和 Diff 请在“代码变更”Tab 中查看。</p>
    </section>
  );
}

function ValidationChecks({ checks, status }: { checks: ValidationCheckView[]; status: FlowStageStatus }) {
  const result = status === 'skipped' ? '已跳过'
    : checks.length === 0 ? '等待验证'
      : checks.every((check) => check.passed) ? '验证通过' : '验证未通过';
  return (
    <section className="validation-report" aria-label="验证报告">
      <header><h3>验证报告</h3><span>{result}</span></header>
      {status === 'skipped' ? <div className="notice">未配置测试命令，本次自动检查已跳过。</div>
        : checks.length === 0 ? <div className="empty stage-empty">暂无自动检查结果，等待当前验证动作。</div>
          : <ul className="validation-checks">
            {checks.map((check, index) => <li key={`${check.repo}-${check.command}-${index}`} className={check.passed ? 'passed' : 'failed'}>
              {check.passed ? <CheckCircle2 size={17} aria-hidden="true" /> : <XCircle size={17} aria-hidden="true" />}
              <div><code>{check.command}</code>{check.repo && <span>{check.repo}</span>}{check.stderr && <p>{check.stderr}</p>}</div>
            </li>)}
          </ul>}
    </section>
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
  const hasChanges = Boolean(flow.modification) || flow.repositories.length > 0;
  if (!hasChanges && actions.length === 0) return <div className="code-change-list"><div className="panel empty">当前路线没有有效的代码产物，未展示历史版本代码。</div></div>;
  const agents = flow.stages.find((stage) => stage.id === 'execution')?.agents ?? [];
  return (
    <div className="code-change-list">
      {!hasChanges && <div className="panel empty">当前路线没有有效的代码产物，未展示历史版本代码。</div>}
      {flow.modification && <div className="panel code-change-summary">
        <div><span>执行摘要</span><strong>{modificationPresentation(flow).title}</strong></div>
      </div>}
      <CodeReviewActions actions={actions} pending={pending} note={reviewNote} onNoteChange={onReviewNoteChange} onAction={onAction} />
      {flow.repositories.map((repo) => {
        const repoAgents = agents.filter((agent) => !agent.repo || agent.repo === repo.repo);
        const repoChecks = repo.checks.length ? repo.checks : flow.checks.filter((check) => check.repo === repo.repo);
        return <article className="panel repository-change" key={repo.repo}>
          <header><div><GitMerge size={18} aria-hidden="true" /><h2 title={repo.repo}>{repo.repo}</h2></div><span className={`flow-status-label ${repo.status === 'closed' ? 'failed' : repo.status === 'merged' || repo.status === 'released' ? 'completed' : 'waiting'}`}>{repositoryStatusName(repo.status)}</span></header>
          {repoAgents.length > 0 && <section><h3>Agent 摘要</h3><ul>{repoAgents.map((agent) => <li key={`${agent.index}-${agent.role}`}><strong>{agent.role}</strong><span>{agent.summary || agent.engine || '-'}</span></li>)}</ul></section>}
          <section><h3>修改文件</h3>{repo.changedPaths.length ? <ul className="changed-files">{repo.changedPaths.map((path) => <li key={path}><code title={path}>{path}</code></li>)}</ul> : <p className="empty-inline">未返回文件列表</p>}</section>
          {repo.diffPatch && <details className="code-diff" open>
            <summary><span>完整 Diff</span><small>代码窗内滚动查看</small></summary>
            {/* 超长代码只在独立窗口内滚动，不能继续撑宽工作项详情页。 */}
            <div className="code-diff-scroll" role="region" aria-label={`${repo.repo} 完整 Diff`} tabIndex={0}><pre><code>{repo.diffPatch}</code></pre></div>
          </details>}
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
    start_modification: { label: '生成执行计划', stageId: 'execution', signalName: code },
    coding_plan_approved: { label: '批准计划并执行', stageId: 'execution', signalName: code },
    coding_plan_rejected: { label: '打回重新规划', stageId: 'execution', signalName: code, noteRequired: true, danger: true },
    interrupt_attempt: { label: '停止本轮', stageId: 'execution', signalName: code, danger: true },
    retry_current_phase: { label: '重试失败阶段', stageId: currentStageId, signalName: code },
    rework: { label: workItem.lifecycleStatus === 'waiting_merge' ? '打回修订' : '完整重做', stageId: currentStageId, signalName: code,
      noteRequired: workItem.lifecycleStatus === 'waiting_merge', danger: workItem.lifecycleStatus === 'waiting_merge' },
    rework_with_latest_config: { label: '刷新配置并重试失败阶段', stageId: currentStageId, signalName: code },
    rework_with_latest_context: { label: '刷新需求上下文并重新规划', stageId: currentStageId, signalName: code },
    patch_apply_approved: { label: workItem.releaseMode === 'gitlab' ? (workItem.validationMode === 'manual' ? '创建候选 MR' : '发布 MR') : '应用 Patch', stageId: 'patch', signalName: code },
    patch_apply_rejected: { label: '打回修订', stageId: 'patch', signalName: code, noteRequired: true, danger: true },
    validation_passed: { label: workItem.validationMode === 'manual' ? '人工验证通过' : '验证通过', stageId: 'validation', signalName: code },
    validation_rejected: { label: workItem.validationMode === 'manual' ? '人工验证不通过' : '重做', stageId: 'validation', signalName: code },
    validation_retry: { label: '重新验证', stageId: 'validation', signalName: code },
    validation_rework_coding: { label: '打回 Coding', stageId: 'validation', signalName: code, noteRequired: true, danger: true },
    validation_rework_planning: { label: '打回 Planning', stageId: 'validation', signalName: code, noteRequired: true, danger: true },
    release_approved: { label: '进入发布', stageId: 'validation', signalName: code },
    release_retry: { label: '重试发布', stageId: 'release', signalName: code },
    release_revalidate: { label: '退回 Validation', stageId: 'release', signalName: code, danger: true },
    release_rework_coding: { label: '打回 Coding', stageId: 'release', signalName: code, noteRequired: true, danger: true },
    check_merge_status: { label: '检查合并状态', stageId: 'release', mergeCheck: true },
  };
  return values[code] ? { code, ...values[code] } : null;
}

const ARTIFACT_ACTIONS: Partial<Record<string, {
  type: ArtifactType;
  status: ArtifactRef['status'];
  selectedApproved?: boolean;
}>> = {
  coding_plan_approved: { type: 'PLANNING', status: 'PROPOSED' },
  coding_plan_rejected: { type: 'PLANNING', status: 'PROPOSED' },
  patch_apply_approved: { type: 'CODING', status: 'PROPOSED', selectedApproved: true },
  patch_apply_rejected: { type: 'CODING', status: 'PROPOSED', selectedApproved: true },
  validation_passed: { type: 'CODING', status: 'PROPOSED', selectedApproved: true },
  validation_rejected: { type: 'CODING', status: 'PROPOSED', selectedApproved: true },
  validation_retry: { type: 'VALIDATION', status: 'APPROVED' },
  validation_rework_coding: { type: 'VALIDATION', status: 'APPROVED' },
  validation_rework_planning: { type: 'VALIDATION', status: 'APPROVED' },
  release_approved: { type: 'VALIDATION', status: 'APPROVED' },
  release_retry: { type: 'VALIDATION', status: 'APPROVED' },
  release_revalidate: { type: 'VALIDATION', status: 'APPROVED' },
  release_rework_coding: { type: 'VALIDATION', status: 'APPROVED' },
  rework_with_latest_context: { type: 'PRODUCT', status: 'APPROVED' },
};

function bindActionArtifact(action: StageAction, events: WorkItemEvent[], graph?: ArtifactGraph,
                            lifecycleStatus?: string): StageAction | null {
  const legacyValidationAction = lifecycleStatus === 'validation_passed'
    && ['release_approved', 'validation_rework_coding', 'validation_rework_planning'].includes(action.code);
  // V22 前在途 Case 没有独立 ValidationArtifact，通过后的发布或返工继续绑定当前 Coding Head。
  const rule = legacyValidationAction && graph && !graph.effectiveHeads.VALIDATION
    ? { type: 'CODING' as ArtifactType, status: 'APPROVED' as const, selectedApproved: true }
    : ARTIFACT_ACTIONS[action.code];
  if (!rule) return action;
  if (!graph) return null;
  let reference = rule.status === 'APPROVED'
    ? graph.effectiveHeads[rule.type]
    : displayedProposedRef(events, graph, rule.type);
  // 版本切换会把选中的 CodingArtifact 提升为有效 Head；审核动作必须跟随当前展示版本。
  if (!reference && rule.selectedApproved) {
    const selected = graph.effectiveHeads[rule.type];
    if (selected?.status === 'APPROVED') reference = selected;
  }
  if (!reference || (reference.status !== rule.status
    && !(rule.selectedApproved && reference.status === 'APPROVED'))) return null;
  const displayed = graph.nodes.find((node) => sameArtifactRef(node.ref, reference));
  if (!displayed) return null;
  return { ...action, artifactRef: displayed.ref };
}

function displayedProposedRef(events: WorkItemEvent[], graph: ArtifactGraph, type: ArtifactType) {
  for (let index = events.length - 1; index >= 0; index -= 1) {
    const payload = eventPayload(events[index]);
    const candidate = payload?.artifactRef;
    if (!isArtifactRef(candidate) || candidate.artifactType !== type || candidate.status !== 'PROPOSED') continue;
    const displayed = graph.nodes.find((node) => sameArtifactRef(node.ref, candidate));
    // 事件已经声明审核版本时必须严格匹配，不能退回其他仍处于待审批状态的版本。
    return displayed?.ref;
  }
  // 兼容 ArtifactRef 上线前已产生的旧事件；唯一 Proposal 仍可安全绑定审核动作。
  const proposals = graph.nodes.filter((node) =>
    node.ref.artifactType === type && node.ref.status === 'PROPOSED');
  return proposals.length === 1 ? proposals[0].ref : undefined;
}

function isArtifactRef(value: unknown): value is ArtifactRef {
  if (!value || typeof value !== 'object') return false;
  const ref = value as Partial<ArtifactRef>;
  return typeof ref.artifactId === 'string'
    && typeof ref.artifactType === 'string'
    && typeof ref.version === 'number'
    && typeof ref.contentHash === 'string'
    && typeof ref.rootArtifactId === 'string'
    && typeof ref.status === 'string';
}

function sameArtifactRef(left: ArtifactRef, right: ArtifactRef) {
  return left.artifactId === right.artifactId
    && left.artifactType === right.artifactType
    && left.version === right.version
    && left.contentHash === right.contentHash
    && left.rootArtifactId === right.rootArtifactId
    && (left.parentArtifactId ?? null) === (right.parentArtifactId ?? null)
    && (left.supersedesArtifactId ?? null) === (right.supersedesArtifactId ?? null)
    && left.status === right.status;
}

function actionContextKind(code: string): 'none' | 'note' | 'evidence' {
  if (['validation_passed', 'validation_rejected'].includes(code)) return 'evidence';
  if (['owner_rejected', 'cancel_case', 'interrupt_attempt', 'rework', 'rework_with_latest_config', 'rework_with_latest_context',
    'coding_plan_rejected', 'patch_apply_rejected', 'validation_rework_coding', 'validation_rework_planning',
    'release_rework_coding'].includes(code)) return 'note';
  return 'none';
}

function actionLabel(code: string) {
  return ({ owner_approved: '批准执行', owner_rejected: '拒绝', cancel_case: '取消', start_modification: '生成执行计划',
    coding_plan_approved: '批准计划并执行', coding_plan_rejected: '打回重新规划', interrupt_attempt: '停止本轮',
    artifact_version_selected: '基于当前计划继续开发',
    retry_current_phase: '重试失败阶段', rework: '完整重做', rework_with_latest_config: '刷新配置并重试失败阶段',
    rework_with_latest_context: '刷新需求上下文并重新规划',
    patch_apply_approved: '代码确认', patch_apply_rejected: '打回修订',
    validation_passed: '验证通过', validation_rejected: '验证不通过', validation_retry: '重新验证',
    validation_rework_coding: '打回 Coding', validation_rework_planning: '打回 Planning',
    release_approved: '发布', release_retry: '重试发布', release_revalidate: '退回 Validation',
    release_rework_coding: '发布打回 Coding',
    check_merge_status: '检查合并状态' } as Record<string, string>)[code] || code;
}

function confirmText(action: StageAction) {
  return ({
    owner_approved: '批准后工作项将进入可执行状态，是否继续？', owner_rejected: '拒绝后工作项将结束，是否继续？',
    cancel_case: '取消后工作项将结束，是否继续？', patch_apply_approved: '该操作会修改真实仓库，是否继续？',
    start_modification: '确认后 Supervisor 会先只读检查真实仓库并生成计划，不会修改代码，是否继续？',
    coding_plan_approved: '批准后 Supervisor 会基于 Approved PlanningArtifact 启动仓库 Agent；Session 不可用时会自动重建，是否继续？',
    coding_plan_rejected: '提交意见后 Supervisor 会保留旧版本，并基于 ProductArtifact 创建新计划，是否继续？',
    interrupt_attempt: '将停止当前 Claude SDK Coding Attempt，保留现场并进入可恢复阻塞，是否继续？',
    retry_current_phase: '将复用已完成成果，只重试失败阶段，是否继续？',
    rework: '将放弃当前执行断点并完整重做，是否继续？',
    rework_with_latest_config: '将刷新 Agent 与模型配置，保留已有计划并重试失败阶段，是否继续？',
    rework_with_latest_context: '将显式读取当前有效的需求引用，生成新的冻结清单，并重新生成执行计划；已停用的引用会被移除，是否继续？',
    patch_apply_rejected: '确认后 Agent 会带着意见自动开始增量修订，是否继续？', validation_passed: '确认验证已通过并进入下一阶段，是否继续？',
    validation_rejected: '确认后将退回修改阶段重新处理，是否继续？', release_approved: '该操作会创建发布分支和提交，是否继续？',
    validation_retry: '将沿用当前 CodingArtifact 重新执行验证，并生成新的 ValidationArtifact 版本，是否继续？',
    validation_rework_coding: '将保留现有产物并生成新的 CodingArtifact；完成后必须重新验证，是否继续？',
    validation_rework_planning: '将保留现有产物并生成新的 PlanningArtifact，之后重新 Coding 和 Validation，是否继续？',
    release_retry: '将沿用当前已通过的 ValidationArtifact 重试发布，是否继续？',
    release_revalidate: '将对同一 CodingArtifact 重新验证；新验证结果会使旧发布路线失效，是否继续？',
    release_rework_coding: '将保留现有发布记录并生成新的 CodingArtifact；重新验证通过前不能再次发布，是否继续？',
    check_merge_status: '后端将实时核验所有 GitLab MR，只有确实合并后才会完成工作项，是否继续？',
  } as Record<string, string>)[action.code] || '是否继续？';
}

function stageSummary(stage: FlowStage, workItem: WorkItem, flow: WorkItemFlow) {
  if (stage.failureReason) return stage.failureReason;
  if (stage.id === 'created') return `${workItem.createdBy || '系统'} 创建了“${workItem.title}”。`;
  if (stage.id === 'approval') return stage.status === 'waiting' ? '等待系统负责人确认是否进入执行。' : '负责人审批结果已记录。';
  if (stage.id === 'execution') {
    if (flow.codingPlan?.status === 'planning') return 'Supervisor 正在只读检查真实仓库并生成可审批计划。';
    if (flow.codingPlan?.status === 'proposed') return `第 ${flow.codingPlan.revision} 版计划已生成，等待负责人批准或带意见打回。`;
    if (flow.codingPlan?.status === 'approved' && !stage.agents?.length) return '计划已批准，正在按 PlanningArtifact 启动执行。';
    if (stage.agents?.length) return `Claude SDK Supervisor 正在调度 ${Math.max(0, stage.agents.length - 1)} 个仓库子 Agent。`;
    return '等待 Coding Supervisor 启动。';
  }
  if (stage.id === 'patch') {
    if (!flow.modification) return '等待 Agent 生成代码修改。';
    const result = modificationPresentation(flow);
    return `${result.title}${result.changedPathCount ? `，涉及 ${result.changedPathCount} 个文件` : ''}，等待负责人确认。`;
  }
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

function modificationPresentation(flow: WorkItemFlow) {
  const rawSummary = flow.modification?.summary?.trim() || '';
  const fallbackChangedPaths = uniqueStrings(flow.repositories.flatMap((repo) => repo.changedPaths));
  return summarizeModification(rawSummary, fallbackChangedPaths);
}

function summarizeModification(rawSummary: string, fallbackChangedPaths: string[]) {
  return {
    title: compactText(rawSummary || 'Agent 修改已生成', 180),
    detail: '',
    changedPathCount: fallbackChangedPaths.length,
  };
}

function compactText(value: string, maxLength: number) {
  const text = value.replace(/\s+/g, ' ').trim();
  return text.length > maxLength ? `${text.slice(0, maxLength)}…` : text;
}

function stringArray(value: unknown) {
  return Array.isArray(value) ? value.filter((item): item is string => typeof item === 'string') : [];
}

function textValue(value: unknown) {
  return typeof value === 'string' ? value : '';
}

function numericValue(value: unknown) {
  if (typeof value === 'number' && Number.isFinite(value)) return value;
  if (typeof value === 'string' && value.trim() && Number.isFinite(Number(value))) return Number(value);
  return null;
}

function arrayObjects(value: unknown) {
  return Array.isArray(value)
    ? value.filter((item): item is Record<string, unknown> => Boolean(item) && typeof item === 'object' && !Array.isArray(item))
    : [];
}

function arrayEntries(value: unknown): [string, unknown][] {
  return value && typeof value === 'object' && !Array.isArray(value)
    ? Object.entries(value as Record<string, unknown>)
    : [];
}

function readableValue(value: unknown) {
  if (value == null || value === '') return '-';
  if (typeof value === 'string' || typeof value === 'number' || typeof value === 'boolean') return String(value);
  return JSON.stringify(sanitizeAuditValue(value), null, 2);
}

const HIDDEN_AUDIT_KEYS = new Set([
  'contenthash', 'snapshothash', 'commandhash', 'idempotencykey',
  'sessionid', 'sessiontoken', 'token', 'tokenusage', 'inputtokens', 'outputtokens',
  'turns', 'subagentruns', 'agentid', 'executionprovider', 'executionarchitecture',
  'executionbundleid', 'executioncontextbundleid',
]);

function sanitizeAuditValue(value: unknown): unknown {
  if (Array.isArray(value)) return value.map(sanitizeAuditValue);
  if (!value || typeof value !== 'object') return value;
  return Object.fromEntries(Object.entries(value as Record<string, unknown>)
    .filter(([key]) => !HIDDEN_AUDIT_KEYS.has(key.replace(/[_-]/g, '').toLowerCase()))
    .map(([key, item]) => [key, sanitizeAuditValue(item)]));
}

function uniqueStrings(values: string[]) {
  return [...new Set(values.filter(Boolean))];
}

function eventSummary(event: WorkItemEvent) {
  const payload = eventPayload(event);
  const failed = failureReason(event);
  if (failed) return failed;
  if (event.eventType === 'AgentStageCompleted') return String(payload?.summary || `${payload?.role || 'Agent'} 执行完成`);
  if (event.eventType === 'ModificationCompleted') {
    const summary = typeof payload?.summary === 'string' ? payload.summary : '';
    return summarizeModification(summary, stringArray(payload?.changedPaths ?? payload?.changed_paths)).title;
  }
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

function planStatusName(status: CodingPlanView['status']) {
  return ({ planning: '规划中', proposed: '等待审批', approved: '已批准', rejected: '已打回' } as const)[status];
}

function waitingRoleName(role?: string) {
  return ({ owner: '系统负责人', worker: 'Agent', gitlab: 'GitLab' } as Record<string, string>)[role || ''] || role || '-';
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
    return JSON.stringify(sanitizeAuditValue(JSON.parse(payload)), null, 2);
  } catch {
    return payload;
  }
}

// 普通查看只认 Coding Head；代码确认时仅展示操作精确绑定的待审版本。
function projectCodingPresentation(
  flow: WorkItemFlow, graph?: ArtifactGraph, reviewArtifact?: ArtifactRef,
): CodingPresentation {
  if (!graph) return { flow: emptyCodingFlow(flow) };
  const reference = reviewArtifact?.artifactType === 'CODING' && reviewArtifact.status === 'PROPOSED'
    ? reviewArtifact
    : graph.effectiveHeads.CODING;
  const artifact = reference ? graph.nodes.find((node) => node.ref.artifactType === 'CODING'
    && sameArtifactRef(node.ref, reference)) : undefined;
  if (!artifact) return { flow: emptyCodingFlow(flow) };

  const completion = [...flow.events].reverse().find((event) => {
    if (event.eventType !== 'ModificationCompleted') return false;
    const reference = eventPayload(event)?.artifactRef;
    return isArtifactRef(reference) && reference.artifactId === artifact.ref.artifactId;
  });
  const completionPayload = completion ? eventPayload(completion) : null;
  const content = artifact.content ?? {};
  const repositories: RepositoryFlowView[] = arrayObjects(content.repoChanges ?? content.repo_changes).map((change, index) => ({
    repo: textValue(change.repo) || `仓库 ${index + 1}`,
    diffPatch: textValue(change.diffPatch ?? change.diff_patch),
    changedPaths: stringArray(change.changedPaths ?? change.changed_paths),
    agentSummaries: [],
    checks: [],
    branch: '',
    commitHash: '',
    mrIid: null,
    mrUrl: '',
    status: 'changed',
  }));
  const projectedFlow = emptyCodingFlow(flow);
  projectedFlow.modification = {
    summary: textValue(content.summary),
    provider: textValue(completionPayload?.executionProvider ?? completionPayload?.execution_provider),
    turns: numericValue(completionPayload?.turns),
    tokenUsage: {},
    diffPatch: textValue(content.diffPatch ?? content.diff_patch),
    revision: numericValue(content.revision) ?? numericValue(completionPayload?.revision) ?? 0,
    revisionMode: textValue(content.revisionMode ?? content.revision_mode ?? completionPayload?.revisionMode
      ?? completionPayload?.revision_mode) === 'incremental' ? 'incremental' : 'full',
  };
  projectedFlow.repositories = repositories;
  return { flow: projectedFlow };
}

function emptyCodingFlow(flow: WorkItemFlow): WorkItemFlow {
  return {
    ...flow,
    modification: null,
    repositories: [],
    checks: [],
    stages: flow.stages.map((stage) => stage.id === 'execution' ? { ...stage, agents: [] } : stage),
  };
}

function revisionsForCodingArtifact(
  flow: WorkItemFlow, graph: ArtifactGraph, artifact: ArtifactSummary, completion: WorkItemEvent,
) {
  const sameRoute = (node: ArtifactSummary) => node.ref.artifactType === 'CODING'
    && (node.ref.parentArtifactId ?? null) === (artifact.ref.parentArtifactId ?? null);
  const previousArtifactIds = new Set(graph.nodes.filter((node) => sameRoute(node)
    && node.ref.version < artifact.ref.version).map((node) => node.ref.artifactId));
  const previousCompletionSequence = Math.max(-1, ...flow.events
    .filter((event) => event.eventType === 'ModificationCompleted')
    .filter((event) => {
      const reference = eventPayload(event)?.artifactRef;
      return isArtifactRef(reference) && previousArtifactIds.has(reference.artifactId);
    })
    .map((event) => event.sequence));
  const requestedEvents = flow.events.filter((event) => event.eventType === 'RevisionRequested'
    && event.sequence > previousCompletionSequence && event.sequence <= completion.sequence);
  const requestedIds = new Set(requestedEvents.map((event) => event.eventId || String(event.sequence)));
  const completedRevision = numericValue(eventPayload(completion)?.revision);
  return flow.revisions.filter((revision) => {
    if (!requestedIds.has(revision.id)) return false;
    if (completedRevision == null) return true;
    if (completedRevision <= 0) return false;
    const requested = requestedEvents.find((event) => (event.eventId || String(event.sequence)) === revision.id);
    const requestedRevision = numericValue(eventPayload(requested ?? {} as WorkItemEvent)?.revision);
    return requestedRevision == null || requestedRevision <= completedRevision;
  });
}

function codingArtifactRevisions(flow: WorkItemFlow, graph: ArtifactGraph, artifact: ArtifactSummary) {
  if (artifact.ref.artifactType !== 'CODING') return [];
  const completion = [...flow.events].reverse().find((event) => {
    if (event.eventType !== 'ModificationCompleted') return false;
    const reference = eventPayload(event)?.artifactRef;
    return isArtifactRef(reference) && reference.artifactId === artifact.ref.artifactId;
  });
  return completion ? revisionsForCodingArtifact(flow, graph, artifact, completion) : [];
}

function eventsWithArtifactContent(events: WorkItemEvent[], artifacts: ArtifactSummary[]) {
  const byId = new Map(artifacts.map((artifact) => [artifact.ref.artifactId, artifact]));
  return events.map((event) => {
    const payload = eventPayload(event);
    const reference = payload?.artifactRef;
    const artifact = isArtifactRef(reference) ? byId.get(reference.artifactId) : undefined;
    if (!artifact) return event;
    const content = artifact.content || {};
    const artifactFields = artifact.ref.artifactType === 'PLANNING'
      ? { planMarkdown: content.planMarkdown, baseRevisions: content.baseRevisions }
      : artifact.ref.artifactType === 'CODING'
        ? {
            summary: content.summary,
            repoDiffs: content.repoChanges,
            executionOutcome: content.executionOutcome,
          }
        : {};
    return {
      ...event,
      payloadJson: JSON.stringify({ ...(payload || {}), ...artifactFields }),
    };
  });
}

function artifactTypeMeta(type: ArtifactType) {
  return ({
    PRODUCT: {
      label: '产品需求', shortLabel: 'Product', code: 'ProductArtifact',
      emptyHint: '需求确认后开始生成', routeEmptyHint: '等待需求确认',
    },
    PLANNING: {
      label: '执行计划', shortLabel: 'Planning', code: 'PlanningArtifact',
      emptyHint: '产品需求批准后开始生成', routeEmptyHint: '等待选择计划',
    },
    CODING: {
      label: '代码产物', shortLabel: 'Coding', code: 'CodingArtifact',
      emptyHint: '计划批准后开始生成', routeEmptyHint: '等待代码产出',
    },
    VALIDATION: {
      label: '验证报告', shortLabel: 'Validation', code: 'ValidationReportArtifact',
      emptyHint: '尚未生成独立验证产物', routeEmptyHint: '等待验证结果',
    },
    RELEASE: {
      label: '发布清单', shortLabel: 'Release', code: 'ReleaseManifestArtifact',
      emptyHint: '尚未生成独立发布产物', routeEmptyHint: '等待发布完成',
    },
  } as const)[type];
}

function selectionDescription(artifact: ArtifactSummary) {
  const meta = artifactTypeMeta(artifact.ref.artifactType);
  return `只将${meta.label} v${artifact.ref.version} 设为当前版本，不会立即启动后续执行。`;
}

function artifactSummary(artifact: ArtifactSummary) {
  if (artifact.ref.artifactType === 'PRODUCT') return artifact.ref.status === 'APPROVED' ? 'PRD 已确认' : '产品需求待确认';
  if (artifact.ref.artifactType === 'PLANNING') return artifact.ref.status === 'APPROVED' ? '计划已批准' : '计划待负责人确认';
  if (artifact.ref.artifactType === 'CODING') return artifact.ref.status === 'APPROVED' ? '代码产物已批准' : '代码变更待确认';
  if (artifact.ref.artifactType === 'VALIDATION') return validationResultLabel(textValue(artifact.content?.result));
  return '发布结果已物化';
}

function isTerminal(status?: string) {
  return ['completed', 'cancelled', 'rejected'].includes(status || '');
}
