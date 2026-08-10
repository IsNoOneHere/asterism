import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { useMemo, useState } from 'react';
import { Link } from 'react-router-dom';
import {
  api,
  MemoryArtifactSource,
  MemoryCandidate,
  MemoryDraft,
  MemoryItem,
  MemoryType,
} from '../api/client';
import { ActionConfirmDialog } from '../components/ActionConfirmDialog';
import { errorMessage, ErrorState } from '../components/Display';
import { MemoryEditorDialog } from '../components/MemoryEditorDialog';
import { Pagination, usePagination } from '../components/Pagination';
import { useCurrentSystem } from '../SystemContext';

type Tab = 'pending' | 'active' | 'history';
type MemoryRowValue =
  | { kind: 'candidate'; item: MemoryCandidate }
  | { kind: 'memory'; item: MemoryItem };

export function MemoryPage() {
  const queryClient = useQueryClient();
  const { systemId, canManageCurrentSystem, systemAccessLoading, systemAccessError } = useCurrentSystem();
  const [tab, setTab] = useState<Tab>('pending');
  const [editing, setEditing] = useState<MemoryCandidate | null>(null);
  const [confirmAction, setConfirmAction] = useState<
    { type: 'reject'; item: MemoryCandidate } | { type: 'archive'; item: MemoryItem } | null
  >(null);
  const pending = useQuery({
    queryKey: ['memory-candidates', systemId, 'PENDING'],
    queryFn: () => api.memoryCandidates(systemId, 'PENDING'),
    enabled: Boolean(systemId),
    retry: false,
  });
  const active = useQuery({
    queryKey: ['memory', systemId, 'ACTIVE'],
    queryFn: () => api.memories(systemId, 'ACTIVE'),
    enabled: Boolean(systemId),
    retry: false,
  });
  const outdated = useQuery({
    queryKey: ['memory', systemId, 'OUTDATED'],
    queryFn: () => api.memories(systemId, 'OUTDATED'),
    enabled: Boolean(systemId),
    retry: false,
  });
  const archived = useQuery({
    queryKey: ['memory', systemId, 'ARCHIVED'],
    queryFn: () => api.memories(systemId, 'ARCHIVED'),
    enabled: Boolean(systemId),
    retry: false,
  });
  const rejectedCandidates = useQuery({
    queryKey: ['memory-candidates', systemId, 'REJECTED'],
    queryFn: () => api.memoryCandidates(systemId, 'REJECTED'),
    enabled: Boolean(systemId),
    retry: false,
  });
  const outdatedCandidates = useQuery({
    queryKey: ['memory-candidates', systemId, 'OUTDATED'],
    queryFn: () => api.memoryCandidates(systemId, 'OUTDATED'),
    enabled: Boolean(systemId),
    retry: false,
  });
  const knowledgeTargets = useQuery({
    queryKey: ['knowledge', systemId, 'approved'],
    queryFn: () => api.knowledge(systemId, 'approved'),
    enabled: Boolean(systemId) && Boolean(editing),
    retry: false,
  });

  const invalidate = () => {
    queryClient.invalidateQueries({ queryKey: ['memory', systemId] });
    queryClient.invalidateQueries({ queryKey: ['memory-candidates', systemId] });
    queryClient.invalidateQueries({ queryKey: ['context-snapshot', systemId] });
  };
  const approve = useMutation({
    mutationFn: ({ candidateId, draft }: { candidateId: string; draft: MemoryDraft }) =>
      api.approveMemory(candidateId, draft),
    onSuccess: () => {
      setEditing(null);
      invalidate();
    },
  });
  const reject = useMutation({
    mutationFn: (candidateId: string) => api.rejectMemory(candidateId),
    onSuccess: invalidate,
    onSettled: () => setConfirmAction(null),
  });
  const archive = useMutation({
    mutationFn: (memoryId: string) => api.archiveMemory(memoryId),
    onSuccess: invalidate,
    onSettled: () => setConfirmAction(null),
  });
  const items = useMemo<MemoryRowValue[]>(() => {
    if (tab === 'pending') {
      return (pending.data ?? []).map((item) => ({ kind: 'candidate', item }));
    }
    if (tab === 'active') {
      return (active.data ?? []).map((item) => ({ kind: 'memory', item }));
    }
    return [
      ...(outdated.data ?? []).map((item): MemoryRowValue => ({ kind: 'memory', item })),
      ...(archived.data ?? []).map((item): MemoryRowValue => ({ kind: 'memory', item })),
      ...(rejectedCandidates.data ?? []).map((item): MemoryRowValue => ({ kind: 'candidate', item })),
      ...(outdatedCandidates.data ?? []).map((item): MemoryRowValue => ({ kind: 'candidate', item })),
    ].sort((left, right) => time(right.item.createdAt) - time(left.item.createdAt));
  }, [
    active.data,
    archived.data,
    outdated.data,
    outdatedCandidates.data,
    pending.data,
    rejectedCandidates.data,
    tab,
  ]);
  const pagination = usePagination(items, `${systemId}:${tab}`);
  const activeQueries = tab === 'pending'
    ? [pending]
    : tab === 'active'
      ? [active]
      : [outdated, archived, rejectedCandidates, outdatedCandidates];
  const loadError = activeQueries.find((query) => query.error)?.error;
  const loading = activeQueries.some((query) => query.isLoading);
  const historyCount = (outdated.data?.length ?? 0)
    + (archived.data?.length ?? 0)
    + (rejectedCandidates.data?.length ?? 0)
    + (outdatedCandidates.data?.length ?? 0);

  return <section>
    <header className="page-head">
      <div>
        <h1>项目记忆</h1>
        <p>从已确认产物中提取、经人工确认后，为后续需求提供项目知识。</p>
      </div>
    </header>
    {!systemAccessLoading && !systemAccessError && !canManageCurrentSystem && (
      <div className="notice">当前账号在此系统中为只读成员，可查看项目记忆，但不能审核或归档。</div>
    )}
    <div className="notice memory-guidance">
      <span><strong>自动来源：</strong>Product / Planning / Coding Artifact，以及验证失败证据。</span>
      <span><strong>不会保存：</strong>Agent 推理、全量聊天、临时或被否定方案、未经确认的讨论。</span>
    </div>
    <div className="panel">
      <div className="tabs">
        <button type="button" className={tab === 'pending' ? 'active' : ''} onClick={() => setTab('pending')}>
          待确认 {pending.data?.length ?? 0}
        </button>
        <button type="button" className={tab === 'active' ? 'active' : ''} onClick={() => setTab('active')}>
          生效中 {active.data?.length ?? 0}
        </button>
        <button type="button" className={tab === 'history' ? 'active' : ''} onClick={() => setTab('history')}>
          历史记录 {historyCount}
        </button>
      </div>
      {loadError ? (
        <ErrorState
          title="项目记忆加载失败"
          error={loadError}
          onRetry={() => activeQueries.forEach((query) => query.refetch())}
        />
      ) : loading ? (
        <div className="empty" role="status">项目记忆加载中…</div>
      ) : (
        <>
          {pagination.pageItems.map((value) => <MemoryRow
            key={`${value.kind}:${value.kind === 'candidate' ? value.item.candidateId : value.item.memoryId}`}
            value={value}
            tab={tab}
            canManage={canManageCurrentSystem}
            onApprove={(item) => {
              approve.reset();
              setEditing(item);
            }}
            onReject={(item) => {
              reject.reset();
              setConfirmAction({ type: 'reject', item });
            }}
            onArchive={(item) => {
              archive.reset();
              setConfirmAction({ type: 'archive', item });
            }}
          />)}
          {!items.length && <div className="empty">
            {tab === 'pending' ? '暂无待确认候选。' : tab === 'active' ? '暂无生效中的项目记忆。' : '暂无历史记录。'}
          </div>}
          <Pagination
            total={items.length}
            page={pagination.page}
            totalPages={pagination.totalPages}
            onPageChange={pagination.setPage}
          />
        </>
      )}
    </div>
    <MemoryEditorDialog
      open={Boolean(editing)}
      initial={editing ? memoryDraft(editing) : undefined}
      allowedTypes={editing ? allowedMemoryTypes(editing) : undefined}
      knowledgeTargets={knowledgeTargets.data ?? []}
      sourceLabel={editing ? artifactSourceLabel(editing.artifactSource) : undefined}
      workItemId={editing?.artifactSource?.workItemId}
      pending={!canManageCurrentSystem || approve.isPending}
      error={approve.error}
      onClose={() => {
        setEditing(null);
        approve.reset();
      }}
      onSubmit={(draft) => {
        if (editing) approve.mutate({ candidateId: editing.candidateId, draft });
      }}
    />
    <ActionConfirmDialog
      open={Boolean(confirmAction)}
      title={confirmAction?.type === 'reject'
        ? `拒绝“${confirmAction.item.title}”？`
        : `归档“${confirmAction?.item.title || ''}”？`}
      description={confirmAction?.type === 'reject'
        ? '候选会保留在历史记录中，但不会生成正式项目记忆。'
        : '归档后不会再进入后续阶段的上下文召回。'}
      confirmLabel={confirmAction?.type === 'reject' ? '拒绝候选' : '归档记忆'}
      pending={reject.isPending || archive.isPending}
      onClose={() => setConfirmAction(null)}
      onConfirm={() => {
        if (confirmAction?.type === 'reject') reject.mutate(confirmAction.item.candidateId);
        if (confirmAction?.type === 'archive') archive.mutate(confirmAction.item.memoryId);
      }}
    />
    <ActionConfirmDialog
      open={Boolean(reject.error || archive.error)}
      title="操作失败"
      description={errorMessage(reject.error || archive.error, '项目记忆操作失败')}
      confirmLabel="知道了"
      alert
      showCancel={false}
      onClose={() => {
        reject.reset();
        archive.reset();
      }}
      onConfirm={() => {
        reject.reset();
        archive.reset();
      }}
    />
  </section>;
}

function MemoryRow({
  value,
  tab,
  canManage,
  onApprove,
  onReject,
  onArchive,
}: {
  value: MemoryRowValue;
  tab: Tab;
  canManage: boolean;
  onApprove: (item: MemoryCandidate) => void;
  onReject: (item: MemoryCandidate) => void;
  onArchive: (item: MemoryItem) => void;
}) {
  const item = value.item;
  const source = item.artifactSource;
  return <article className="list-item action-item memory-card">
    <div>
      <div className="memory-card-title">
        <span className={`memory-category ${item.memoryType.toLowerCase()}`}>
          {memoryTypeLabel(item.memoryType)}
        </span>
        <strong>{item.title}</strong>
        <span className={`memory-status ${item.status.toLowerCase()}`}>{statusLabel(item.status)}</span>
      </div>
      <p>{item.content}</p>
      <span>
        {applicabilityLabel(item.applicability)}
        {' · '}置信度 {Math.round(item.confidence * 100)}%
        {item.targetRefs?.length ? ` · 关联 ${item.targetRefs.length} 个知识目标` : ''}
        {item.expiresAt ? ` · ${formatTime(item.expiresAt)} 到期` : ''}
      </span>
      <span>
        {source?.workItemId ? (
          <Link className="action-link" to={`/work-items/${source.workItemId}`}>
            来源 {artifactSourceLabel(source)}
          </Link>
        ) : artifactSourceLabel(source)}
        {' · '}{formatTime(item.createdAt)}
      </span>
      {'sourceKind' in item && <span>{sourceKindLabel(item.sourceKind)}</span>}
      {'reviewNote' in item && item.reviewNote && <span>审核说明：{item.reviewNote}</span>}
    </div>
    {canManage && tab === 'pending' && value.kind === 'candidate' && (
      <div className="button-row">
        <button type="button" onClick={() => onApprove(value.item)}>审核并确认</button>
        <button type="button" className="secondary" onClick={() => onReject(value.item)}>拒绝</button>
      </div>
    )}
    {canManage && tab === 'active' && value.kind === 'memory' && (
      <button type="button" className="secondary" onClick={() => onArchive(value.item)}>归档</button>
    )}
  </article>;
}

function memoryDraft(item: MemoryCandidate): MemoryDraft {
  return {
    memoryType: item.memoryType,
    title: item.title,
    content: item.content,
    confidence: item.confidence,
    applicability: item.applicability,
    expiresAt: item.expiresAt,
    targetRefs: item.targetRefs || [],
  };
}

function allowedMemoryTypes(item: MemoryCandidate): MemoryType[] {
  if (item.artifactSource?.artifactType === 'PRODUCT') return ['FACT'];
  if (item.artifactSource?.artifactType === 'PLANNING') return ['DECISION', 'CONSTRAINT'];
  return ['EXPERIENCE'];
}

function memoryTypeLabel(memoryType: MemoryType) {
  return ({
    FACT: '事实',
    DECISION: '决策',
    CONSTRAINT: '约束',
    EXPERIENCE: '经验',
  } as Record<MemoryType, string>)[memoryType];
}

function applicabilityLabel(value: string) {
  return value === 'ARTIFACT_LINEAGE' ? '仅当前产物链' : '整个项目';
}

function statusLabel(status: string) {
  return ({
    PENDING: '待确认',
    CONFIRMED: '已确认',
    REJECTED: '已拒绝',
    ACTIVE: '生效中',
    OUTDATED: '已失效',
    ARCHIVED: '已归档',
  } as Record<string, string>)[status] || status;
}

function sourceKindLabel(sourceKind: string) {
  return ({
    ARTIFACT_APPROVED: '从已批准产物提取',
    CODING_COMPLETED: '从代码实现产物提取，验证通过后方可确认',
    VALIDATION_FAILED: '从验证失败证据提取，需补全根因与已验证解法',
  } as Record<string, string>)[sourceKind] || sourceKind;
}

function artifactSourceLabel(source?: MemoryArtifactSource | null) {
  if (!source) return '旧记忆（无 Artifact 来源）';
  const type = {
    PRODUCT: 'ProductArtifact',
    PLANNING: 'PlanningArtifact',
    CODING: 'CodingArtifact',
    VALIDATION: 'ValidationArtifact',
    RELEASE: 'ReleaseArtifact',
  }[source.artifactType];
  return `${type} v${source.version}`;
}

function formatTime(value?: string | null) {
  if (!value) return '未知时间';
  return new Date(value).toLocaleString('zh-CN', { hour12: false });
}

function time(value?: string) {
  return value ? new Date(value).getTime() : 0;
}
