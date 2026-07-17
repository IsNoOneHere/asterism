import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { useEffect, useMemo, useState } from 'react';
import { Link, useLocation, useNavigate } from 'react-router-dom';
import { api, WorkItem } from '../api/client';
import { ActionConfirmDialog } from '../components/ActionConfirmDialog';
import { errorMessage, ErrorState, formatDateTime, StatusBadge } from '../components/Display';
import { Pagination, usePagination } from '../components/Pagination';
import { useCurrentSystem } from '../SystemContext';
import { readWorkItemListState, WorkItemNavigationState } from '../workItemListState';

export function WorkItemsPage() {
  const queryClient = useQueryClient();
  const { systemId } = useCurrentSystem();
  const location = useLocation();
  const navigate = useNavigate();
  const restored = useMemo(() => readWorkItemListState(location.state), []);
  const [scope, setScope] = useState(restored.scope);
  const [status, setStatus] = useState(restored.status);
  const [q, setQ] = useState(restored.q);
  const [sort, setSort] = useState(restored.sort);
  const [approvalTarget, setApprovalTarget] = useState<WorkItem | null>(null);

  const items = useQuery({
    queryKey: ['work-items', scope, systemId, status, q, sort],
    queryFn: () => api.workItems({ scope, systemId: scope === 'system' ? systemId : undefined, status, q, sort }),
    enabled: scope !== 'system' || Boolean(systemId),
    refetchInterval: 5000,
    retry: false,
  });
  const approve = useMutation({
    mutationFn: (id: string) => api.approveOwner(id),
    onSuccess: () => queryClient.invalidateQueries({ queryKey: ['work-items'] }),
    onSettled: () => setApprovalTarget(null),
  });
  const values = items.data ?? [];
  const pagination = usePagination(values, [scope, systemId, status, q, sort].join(':'), restored.page, items.isSuccess);
  const navigationState: WorkItemNavigationState = useMemo(() => ({
    workItemList: { scope, status, q, sort, page: pagination.page },
  }), [scope, status, q, sort, pagination.page]);

  useEffect(() => {
    navigate(location.pathname, { replace: true, state: navigationState });
  }, [location.pathname, navigate, navigationState]);

  function resetPage(update: () => void) {
    update();
    pagination.setPage(1);
  }

  return (
    <div className="center-view">
      <div className="filter-toolbar">
        <div className="filter-row">
          <select aria-label="范围" value={scope} onChange={(event) => resetPage(() => setScope(event.target.value))}>
            <option value="mine">待我处理</option><option value="system">当前系统</option><option value="all">全部可见</option>
          </select>
          <select aria-label="状态" value={status} onChange={(event) => resetPage(() => setStatus(event.target.value))}>
            <option value="">全部状态</option><option value="waiting_owner_approval">待审批</option><option value="activated">执行中</option><option value="waiting_merge">待合并</option><option value="worker_blocked">阻塞</option><option value="completed">已完成</option>
          </select>
          <input aria-label="搜索工作项" placeholder="搜索 ID 或标题" value={q} onChange={(event) => resetPage(() => setQ(event.target.value))} />
          <select aria-label="排序" value={sort} onChange={(event) => resetPage(() => setSort(event.target.value))}>
            <option value="updated_desc">最近更新</option><option value="created_desc">最新创建</option><option value="created_asc">最早创建</option>
          </select>
        </div>
      </div>
      <div className="table-frame">
        <table className="data-table work-items-table">
          <colgroup><col style={{ width: '17%' }} /><col style={{ width: '20%' }} /><col style={{ width: '12%' }} /><col style={{ width: '11%' }} /><col style={{ width: '8%' }} /><col style={{ width: '14%' }} /><col style={{ width: '8%' }} /><col style={{ width: '10%' }} /></colgroup>
          <thead><tr><th>工作项 ID</th><th>标题</th><th>生命周期</th><th>审批</th><th>执行</th><th>更新时间</th><th>创建人</th><th>动作</th></tr></thead>
          <tbody>
            {pagination.pageItems.map((item) => (
              <WorkItemRow
                key={item.workItemId}
                item={item}
                navigationState={navigationState}
                canAct={item.canControl}
                pending={approve.isPending}
                onApprove={() => { approve.reset(); setApprovalTarget(item); }}
              />
            ))}
            {items.isLoading && <tr><td className="empty-cell" colSpan={8}>工作项加载中…</td></tr>}
            {items.isError && <tr><td colSpan={8}><ErrorState title="工作项加载失败" error={items.error} onRetry={() => items.refetch()} /></td></tr>}
            {items.isSuccess && values.length === 0 && <tr><td className="empty-cell" colSpan={8}>暂无工作项。</td></tr>}
          </tbody>
        </table>
        <Pagination total={values.length} page={pagination.page} totalPages={pagination.totalPages} onPageChange={pagination.setPage} />
      </div>
      <ActionConfirmDialog
        open={Boolean(approvalTarget)}
        title="批准执行该工作项？"
        description={`“${approvalTarget?.title || approvalTarget?.workItemId || ''}”批准后将进入可执行状态。`}
        confirmLabel="批准执行"
        pending={approve.isPending}
        tone="primary"
        onClose={() => setApprovalTarget(null)}
        onConfirm={() => approvalTarget && approve.mutate(approvalTarget.workItemId)}
      />
      <ActionConfirmDialog
        open={Boolean(approve.error)}
        title="审批失败"
        description={errorMessage(approve.error, '工作项审批失败')}
        confirmLabel="知道了"
        alert
        showCancel={false}
        onClose={() => approve.reset()}
        onConfirm={() => approve.reset()}
      />
    </div>
  );
}

function WorkItemRow({ item, navigationState, canAct, pending, onApprove }: { item: WorkItem; navigationState: WorkItemNavigationState; canAct: boolean; pending: boolean; onApprove: () => void }) {
  return (
    <tr>
      <td><Link className="work-item-id-link" state={navigationState} to={'/work-items/' + item.workItemId}>{item.workItemId}</Link></td>
      <td className="work-item-title" title={item.title || '未命名工作项'}>{item.title || '未命名工作项'}</td>
      <td><StatusBadge value={item.lifecycleStatus} /></td>
      <td><StatusBadge value={item.approvalStatus} /></td>
      <td>{item.executionAllowed ? '允许' : '关闭'}</td>
      <td>{formatDateTime(item.updatedAt)}</td>
      <td className="work-item-creator">{item.createdBy || '-'}</td>
      <td className="table-action">{canAct && item.availableActions.includes('owner_approved') ? (
        <button type="button" disabled={pending} onClick={onApprove}>批准</button>
      ) : <Link className="action-link" state={navigationState} to={'/work-items/' + item.workItemId}>查看详情</Link>}</td>
    </tr>
  );
}
