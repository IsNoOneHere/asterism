import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { Clock3, FileText, Trash2, UserRound } from 'lucide-react';
import { useState } from 'react';
import { Link } from 'react-router-dom';
import { api, PrdSession } from '../api/client';
import { ActionConfirmDialog } from '../components/ActionConfirmDialog';
import { errorMessage, ErrorState, formatDateTime, StatusBadge } from '../components/Display';
import { Pagination, usePagination } from '../components/Pagination';
import { isResumablePrd } from '../prd';
import { useCurrentSystem } from '../SystemContext';

export function PrdDraftsPage() {
  const queryClient = useQueryClient();
  const { systemId } = useCurrentSystem();
  const [scope, setScope] = useState<'pending' | 'all'>('pending');
  const [deleteTarget, setDeleteTarget] = useState<PrdSession | null>(null);
  const history = useQuery({
    queryKey: ['prd-sessions', systemId],
    queryFn: () => api.prdSessions(systemId),
    enabled: Boolean(systemId),
    retry: false,
  });
  const remove = useMutation({
    mutationFn: (prdId: string) => api.deletePrdDraft(prdId),
    onSuccess: () => queryClient.invalidateQueries({ queryKey: ['prd-sessions', systemId] }),
    onSettled: () => setDeleteTarget(null),
  });
  const sessions = history.data ?? [];
  const values = scope === 'pending' ? sessions.filter(isResumablePrd) : sessions;
  const pendingCount = sessions.filter(isResumablePrd).length;
  const pagination = usePagination(values, `${systemId}:${scope}`);

  return (
    <div className="center-view">
      <div className="draft-toolbar">
        <div>
          <h2>需求草稿</h2>
          <p>继续完善未完成需求，或查看历史需求记录。</p>
        </div>
        <div className="segmented-control" aria-label="草稿范围">
          <button type="button" className={scope === 'pending' ? 'active' : ''} aria-pressed={scope === 'pending'} onClick={() => setScope('pending')}>待完善 {pendingCount}</button>
          <button type="button" className={scope === 'all' ? 'active' : ''} aria-pressed={scope === 'all'} onClick={() => setScope('all')}>全部记录</button>
        </div>
      </div>
      <div className="draft-list" aria-label="需求草稿列表">
        {pagination.pageItems.map((session) => <PrdRow key={session.prdId} session={session} pending={remove.isPending} onDelete={() => { remove.reset(); setDeleteTarget(session); }} />)}
        {history.isLoading && <div className="draft-list-empty" role="status">需求草稿加载中…</div>}
        {history.isError && <ErrorState title="需求草稿加载失败" error={history.error} onRetry={() => history.refetch()} />}
        {history.isSuccess && values.length === 0 && <div className="draft-list-empty">{scope === 'pending' ? '暂无待完善草稿。' : '暂无需求记录。'}</div>}
        <Pagination total={values.length} page={pagination.page} totalPages={pagination.totalPages} onPageChange={pagination.setPage} />
      </div>
      <ActionConfirmDialog open={Boolean(deleteTarget)} title="删除需求草稿？"
        description={`“${deleteTarget?.title || deleteTarget?.prdId || ''}”将从草稿列表移除，历史对话和关联工作项仍会保留。`}
        confirmLabel="删除草稿" pending={remove.isPending} tone="danger"
        onClose={() => setDeleteTarget(null)} onConfirm={() => deleteTarget && remove.mutate(deleteTarget.prdId)} />
      <ActionConfirmDialog open={Boolean(remove.error)} title="删除失败"
        description={errorMessage(remove.error, '需求草稿删除失败')} confirmLabel="知道了" alert showCancel={false}
        onClose={() => remove.reset()} onConfirm={() => remove.reset()} />
    </div>
  );
}

function PrdRow({ session, pending, onDelete }: { session: PrdSession; pending: boolean; onDelete: () => void }) {
  return (
    <article className="draft-list-item">
      <div className="draft-list-icon"><FileText size={19} aria-hidden="true" /></div>
      <div className="draft-list-content">
        <div className="draft-list-title">
          <strong>{session.title || session.prdId}</strong>
          <StatusBadge value={session.status} />
        </div>
        <span className="draft-list-id">{session.prdId}</span>
        <div className="draft-list-meta">
          <span><UserRound size={14} aria-hidden="true" />{creator(session)}</span>
          <span><Clock3 size={14} aria-hidden="true" />{formatDateTime(session.updatedAt)}</span>
        </div>
      </div>
      <div className="draft-list-action">
        {isResumablePrd(session)
          ? <Link className="action-link" to={'/work-items/new/' + session.prdId}>继续完善</Link>
          : session.workItemId
            ? <Link className="action-link" to={'/work-items/' + session.workItemId}>查看工作项</Link>
            : '-'}
        <button type="button" className="icon-button danger"
          title={session.canDelete ? '删除草稿' : '只有创建人或系统 owner/admin 可以删除'}
          aria-label={`删除草稿 ${session.title || session.prdId}`} disabled={pending || !session.canDelete}
          onClick={onDelete}><Trash2 size={16} /></button>
      </div>
    </article>
  );
}

function creator(session: PrdSession) {
  if (!session.creatorDisplayName || session.creatorDisplayName === session.createdBy) return session.createdBy;
  return `${session.creatorDisplayName} (${session.createdBy})`;
}
