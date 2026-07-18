import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { Trash2 } from 'lucide-react';
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
  const [deleteTarget, setDeleteTarget] = useState<WorkItem | null>(null);

  const items = useQuery({
    queryKey: ['work-items', scope, systemId, status, q, sort],
    queryFn: () => api.workItems({ scope, systemId: scope === 'system' ? systemId : undefined, status, q, sort }),
    enabled: scope !== 'system' || Boolean(systemId),
    refetchInterval: 5000,
    retry: false,
  });
  const approve = useMutation({
    mutationFn: (item: WorkItem) => api.approveOwner(item.workItemId, {
      requestId: crypto.randomUUID(),
      expectedStatus: item.lifecycleStatus,
      expectedProjectionSequence: item.lastAppliedSequence,
    }),
    onSuccess: () => queryClient.invalidateQueries({ queryKey: ['work-items'] }),
    onSettled: () => setApprovalTarget(null),
  });
  const remove = useMutation({
    mutationFn: (workItemId: string) => api.deleteWorkItem(workItemId),
    onSuccess: () => queryClient.invalidateQueries({ queryKey: ['work-items'] }),
    onSettled: () => setDeleteTarget(null),
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
          <colgroup><col style={{ width: '15%' }} /><col style={{ width: '20%' }} /><col style={{ width: '12%' }} /><col style={{ width: '11%' }} /><col style={{ width: '8%' }} /><col style={{ width: '14%' }} /><col style={{ width: '8%' }} /><col style={{ width: '12%' }} /></colgroup>
          <thead><tr><th>工作项 ID</th><th>标题</th><th>生命周期</th><th>审批</th><th>执行</th><th>更新时间</th><th>创建人</th><th>动作</th></tr></thead>
          <tbody>
            {pagination.pageItems.map((item) => (
              <WorkItemRow
                key={item.workItemId}
                item={item}
                navigationState={navigationState}
                canAct={item.canControl}
                approvalPending={approve.isPending}
                deletePending={remove.isPending}
                onApprove={() => { approve.reset(); setApprovalTarget(item); }}
                onDelete={() => { remove.reset(); setDeleteTarget(item); }}
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
        onConfirm={() => approvalTarget && approve.mutate(approvalTarget)}
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
      <ActionConfirmDialog
        open={Boolean(deleteTarget)}
        title="删除该工作项？"
        description={`“${deleteTarget?.title || deleteTarget?.workItemId || ''}”将从工作项列表移除，历史事件仍会保留。`}
        confirmLabel="删除工作项"
        pending={remove.isPending}
        tone="danger"
        onClose={() => setDeleteTarget(null)}
        onConfirm={() => deleteTarget && remove.mutate(deleteTarget.workItemId)}
      />
      <ActionConfirmDialog
        open={Boolean(remove.error)}
        title="删除失败"
        description={errorMessage(remove.error, '工作项删除失败')}
        confirmLabel="知道了"
        alert
        showCancel={false}
        onClose={() => remove.reset()}
        onConfirm={() => remove.reset()}
      />
    </div>
  );
}

function WorkItemRow({ item, navigationState, canAct, approvalPending, deletePending, onApprove, onDelete }: {
  item: WorkItem;
  navigationState: WorkItemNavigationState;
  canAct: boolean;
  approvalPending: boolean;
  deletePending: boolean;
  onApprove: () => void;
  onDelete: () => void;
}) {
  const title = item.title || '未命名工作项';
  return (
    <tr>
      <td><Link className="work-item-id-link" state={navigationState} to={'/work-items/' + item.workItemId}>{item.workItemId}</Link></td>
      <td className="work-item-title"><span title={title}>{title}</span></td>
      <td><StatusBadge value={item.lifecycleStatus} /></td>
      <td><StatusBadge value={item.approvalStatus} /></td>
      <td>{item.executionAllowed ? '允许' : '关闭'}</td>
      <td>{formatDateTime(item.updatedAt)}</td>
      <td className="work-item-creator">{item.createdBy || '-'}</td>
      <td className="table-action"><div className="row-actions compact-actions table-row-actions">
        {canAct && item.availableActions.includes('owner_approved') ? (
          <button type="button" disabled={approvalPending} onClick={onApprove}>批准</button>
        ) : <Link className="action-link" state={navigationState} to={'/work-items/' + item.workItemId}>查看详情</Link>}
        <button type="button" className="icon-button danger"
          title={item.canDelete ? '删除工作项' : '只有创建人或系统 owner/admin 可以删除'}
          aria-label={`删除工作项 ${item.workItemId}`} disabled={deletePending || !item.canDelete}
          onClick={onDelete}><Trash2 size={16} /></button>
      </div></td>
    </tr>
  );
}
