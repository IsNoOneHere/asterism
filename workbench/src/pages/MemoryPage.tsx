import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { useMemo, useState } from 'react';
import { Link } from 'react-router-dom';
import { api, MemoryCategory, MemoryDraft, MemoryItem } from '../api/client';
import { ActionConfirmDialog } from '../components/ActionConfirmDialog';
import { errorMessage, ErrorState } from '../components/Display';
import { MemoryEditorDialog } from '../components/MemoryEditorDialog';
import { Pagination, usePagination } from '../components/Pagination';
import { useCurrentSystem } from '../SystemContext';

type Tab = 'candidate' | 'approved' | 'closed';

export function MemoryPage() {
  const queryClient = useQueryClient();
  const { systemId, canManageCurrentSystem, systemAccessLoading, systemAccessError } = useCurrentSystem();
  const [tab, setTab] = useState<Tab>('candidate');
  const [creating, setCreating] = useState(false);
  const [editing, setEditing] = useState<MemoryItem | null>(null);
  const [confirmAction, setConfirmAction] = useState<{ type: 'reject' | 'disable'; item: MemoryItem } | null>(null);
  const candidate = useQuery({ queryKey: ['memory', systemId, 'candidate'], queryFn: () => api.memories(systemId, 'candidate'), enabled: Boolean(systemId), retry: false });
  const approved = useQuery({ queryKey: ['memory', systemId, 'approved'], queryFn: () => api.memories(systemId, 'approved'), enabled: Boolean(systemId), retry: false });
  const rejected = useQuery({ queryKey: ['memory', systemId, 'rejected'], queryFn: () => api.memories(systemId, 'rejected'), enabled: Boolean(systemId), retry: false });
  const disabled = useQuery({ queryKey: ['memory', systemId, 'disabled'], queryFn: () => api.memories(systemId, 'disabled'), enabled: Boolean(systemId), retry: false });
  const knowledgeTargets = useQuery({
    queryKey: ['knowledge', systemId, 'approved'],
    queryFn: () => api.knowledge(systemId, 'approved'),
    enabled: Boolean(systemId),
    retry: false,
  });

  const invalidate = () => {
    queryClient.invalidateQueries({ queryKey: ['memory', systemId] });
    queryClient.invalidateQueries({ queryKey: ['context-snapshot', systemId] });
  };
  const create = useMutation({
    mutationFn: (draft: MemoryDraft) => api.createMemory({ systemId, ...draft }),
    onSuccess: () => { setCreating(false); invalidate(); },
  });
  const approve = useMutation({
    mutationFn: ({ memoryId, draft }: { memoryId: string; draft: MemoryDraft }) => api.approveMemory(memoryId, draft),
    onSuccess: () => { setEditing(null); invalidate(); },
  });
  const reject = useMutation({ mutationFn: api.rejectMemory, onSuccess: invalidate, onSettled: () => setConfirmAction(null) });
  const disable = useMutation({ mutationFn: api.disableMemory, onSuccess: invalidate, onSettled: () => setConfirmAction(null) });
  const items = useMemo(() => {
    if (tab === 'candidate') return candidate.data ?? [];
    if (tab === 'approved') return approved.data ?? [];
    return [...(rejected.data ?? []), ...(disabled.data ?? [])];
  }, [approved.data, candidate.data, disabled.data, rejected.data, tab]);
  const pagination = usePagination(items, systemId + ':' + tab);
  const activeQueries = tab === 'candidate' ? [candidate] : tab === 'approved' ? [approved] : [rejected, disabled];
  const loadError = activeQueries.find((query) => query.error)?.error;
  const loading = activeQueries.some((query) => query.isLoading);

  return <section>
    <header className="page-head">
      <div><h1>系统记忆</h1><p>沉淀跨工作项长期有效的工程规则。</p></div>
      <button type="button" onClick={() => { create.reset(); setCreating(true); }} disabled={!systemId || !canManageCurrentSystem}>新增记忆</button>
    </header>
    {!systemAccessLoading && !systemAccessError && !canManageCurrentSystem && <div className="notice">当前账号在此系统中为只读成员，记忆维护操作已禁用。</div>}
    <div className="notice memory-guidance">
      <span><strong>应该沉淀：</strong>兼容性约束、工程约定、问题原因与已验证解法。</span>
      <span><strong>不要沉淀：</strong>一次性改动、完整 diff、运行日志和测试结果；权限类硬约束请放到系统配置。</span>
    </div>
    <div className="panel">
      <div className="tabs">
        <button type="button" className={tab === 'candidate' ? 'active' : ''} onClick={() => setTab('candidate')}>待审批 {candidate.data?.length ?? 0}</button>
        <button type="button" className={tab === 'approved' ? 'active' : ''} onClick={() => setTab('approved')}>生效中 {approved.data?.length ?? 0}</button>
        <button type="button" className={tab === 'closed' ? 'active' : ''} onClick={() => setTab('closed')}>已归档 {(rejected.data?.length ?? 0) + (disabled.data?.length ?? 0)}</button>
      </div>
      {loadError ? <ErrorState title="系统记忆加载失败" error={loadError} onRetry={() => activeQueries.forEach((query) => query.refetch())} /> :
      loading ? <div className="empty" role="status">系统记忆加载中…</div> : <>
      {pagination.pageItems.map((item) => <MemoryRow
        key={item.memoryId}
        item={item}
        tab={tab}
        canManage={canManageCurrentSystem}
        onApprove={() => { approve.reset(); setEditing(item); }}
        onReject={() => { reject.reset(); setConfirmAction({ type: 'reject', item }); }}
        onDisable={() => { disable.reset(); setConfirmAction({ type: 'disable', item }); }}
      />)}
      {!items.length && <div className="empty">暂无记忆。</div>}
      <Pagination total={items.length} page={pagination.page} totalPages={pagination.totalPages} onPageChange={pagination.setPage} />
      </>}
    </div>
    <MemoryEditorDialog
      open={creating || Boolean(editing)}
      title={editing ? '编辑并批准' : '新增系统记忆'}
      submitLabel={editing ? '批准并生效' : '加入待审批'}
      initial={editing ? memoryDraft(editing) : undefined}
      knowledgeTargets={knowledgeTargets.data ?? []}
      workItemId={editing?.workItemId ?? undefined}
      pending={!canManageCurrentSystem || (editing ? approve.isPending : create.isPending)}
      error={editing ? approve.error : create.error}
      onClose={() => { setCreating(false); setEditing(null); create.reset(); approve.reset(); }}
      onSubmit={(draft) => editing ? approve.mutate({ memoryId: editing.memoryId, draft }) : create.mutate(draft)}
    />
    <ActionConfirmDialog
      open={Boolean(confirmAction)}
      title={`${confirmAction?.type === 'reject' ? '拒绝' : '停用'}“${confirmAction?.item.title || ''}”？`}
      description={confirmAction?.type === 'reject' ? '拒绝后该记忆不会进入执行上下文。' : '停用后该记忆不会再进入执行上下文。'}
      confirmLabel={confirmAction?.type === 'reject' ? '拒绝记忆' : '停用记忆'}
      pending={reject.isPending || disable.isPending}
      onClose={() => setConfirmAction(null)}
      onConfirm={() => {
        if (confirmAction?.type === 'reject') reject.mutate(confirmAction.item.memoryId);
        if (confirmAction?.type === 'disable') disable.mutate(confirmAction.item.memoryId);
      }}
    />
    <ActionConfirmDialog
      open={Boolean(reject.error || disable.error)}
      title="操作失败"
      description={errorMessage(reject.error || disable.error, '记忆操作失败')}
      confirmLabel="知道了"
      alert
      showCancel={false}
      onClose={() => { reject.reset(); disable.reset(); }}
      onConfirm={() => { reject.reset(); disable.reset(); }}
    />
  </section>;
}

function MemoryRow({ item, tab, canManage, onApprove, onReject, onDisable }: {
  item: MemoryItem;
  tab: Tab;
  canManage: boolean;
  onApprove: () => void;
  onReject: () => void;
  onDisable: () => void;
}) {
  const legacyEvent = !item.category && Boolean(item.sourceEventId);
  return <article className="list-item action-item memory-card">
    <div>
      <div className="memory-card-title"><span className={'memory-category ' + (item.category || 'legacy')}>{legacyEvent ? '旧事件' : categoryLabel(item.category)}</span><strong>{legacyEvent ? '旧生命周期事件候选' : item.title}</strong></div>
      <p>{legacyEvent ? '原始 JSON、diff 和日志仅保留在事件审计中，不作为系统记忆展示。' : item.content}</p>
      <span>适用：{audienceLabel(item.audience)}{item.targetRefs?.length ? ` · ${item.targetRefs.length} 个知识目标` : ' · 系统全局'}</span>
      <span>{item.workItemId ? <Link className="action-link" to={'/work-items/' + item.workItemId}>来源工作项 {item.workItemId}</Link> : legacyEvent ? '已归档至事件审计' : '手工录入'} · {formatTime(item.createdAt)}</span>
    </div>
    {canManage && tab === 'candidate' && <div className="button-row"><button type="button" onClick={onApprove}>编辑并批准</button><button type="button" className="secondary" onClick={onReject}>拒绝</button></div>}
    {canManage && tab === 'approved' && <button type="button" className="secondary" onClick={onDisable}>停用</button>}
  </article>;
}

function memoryDraft(item: MemoryItem): MemoryDraft {
  return { category: item.category || 'convention', audience: item.audience || 'both', title: item.title,
    content: item.content, targetRefs: item.targetRefs || [] };
}

function audienceLabel(audience?: string) {
  return ({ product: '产品 / PRD', execution: '规划与开发', both: '产品与执行' } as Record<string, string>)[audience || 'both'] || audience;
}

function categoryLabel(category: MemoryCategory | '') {
  return ({ constraint: '约束', convention: '约定', lesson: '经验' } as Record<string, string>)[category] ?? '未分类';
}

function formatTime(value?: string) {
  if (!value) return '未知时间';
  return new Date(value).toLocaleString('zh-CN', { hour12: false });
}
