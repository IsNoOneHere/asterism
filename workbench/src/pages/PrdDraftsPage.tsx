import { useQuery } from '@tanstack/react-query';
import { Clock3, FileText, UserRound } from 'lucide-react';
import { useState } from 'react';
import { Link } from 'react-router-dom';
import { api, PrdSession } from '../api/client';
import { ErrorState, formatDateTime, StatusBadge } from '../components/Display';
import { Pagination, usePagination } from '../components/Pagination';
import { isResumablePrd } from '../prd';
import { useCurrentSystem } from '../SystemContext';

export function PrdDraftsPage() {
  const { systemId } = useCurrentSystem();
  const [scope, setScope] = useState<'pending' | 'all'>('pending');
  const history = useQuery({
    queryKey: ['prd-sessions', systemId],
    queryFn: () => api.prdSessions(systemId),
    enabled: Boolean(systemId),
    retry: false,
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
        {pagination.pageItems.map((session) => <PrdRow key={session.prdId} session={session} />)}
        {history.isLoading && <div className="draft-list-empty" role="status">需求草稿加载中…</div>}
        {history.isError && <ErrorState title="需求草稿加载失败" error={history.error} onRetry={() => history.refetch()} />}
        {history.isSuccess && values.length === 0 && <div className="draft-list-empty">{scope === 'pending' ? '暂无待完善草稿。' : '暂无需求记录。'}</div>}
        <Pagination total={values.length} page={pagination.page} totalPages={pagination.totalPages} onPageChange={pagination.setPage} />
      </div>
    </div>
  );
}

function PrdRow({ session }: { session: PrdSession }) {
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
      <div className="draft-list-action">{isResumablePrd(session)
        ? <Link className="action-link" to={'/work-items/new/' + session.prdId}>继续完善</Link>
        : session.workItemId
          ? <Link className="action-link" to={'/work-items/' + session.workItemId}>查看工作项</Link>
          : '-'}</div>
    </article>
  );
}

function creator(session: PrdSession) {
  if (!session.creatorDisplayName || session.creatorDisplayName === session.createdBy) return session.createdBy;
  return `${session.creatorDisplayName} (${session.createdBy})`;
}
