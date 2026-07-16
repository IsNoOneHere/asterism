import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { FormEvent, useRef, useState } from 'react';
import { Plus, RefreshCw } from 'lucide-react';
import { api } from '../api/client';
import { Pagination, usePagination } from '../components/Pagination';
import { useCurrentSystem } from '../SystemContext';

type Status = 'candidate' | 'approved' | 'rejected';

export function KnowledgePage() {
  const { systemId } = useCurrentSystem();
  const queryClient = useQueryClient();
  const dialogRef = useRef<HTMLDialogElement>(null);
  const [status, setStatus] = useState<Status>('candidate');
  const [kind, setKind] = useState('page');
  const [title, setTitle] = useState('');
  const [anchors, setAnchors] = useState('');
  const [routePath, setRoutePath] = useState('');
  const [apiEndpoints, setApiEndpoints] = useState('');
  const entries = useQuery({
    queryKey: ['knowledge', systemId, status],
    queryFn: () => api.knowledge(systemId, status),
    enabled: Boolean(systemId),
  });
  const values = entries.data ?? [];
  // 知识库固定每页 10 条，切换状态时自动回到第一页。
  const pagination = usePagination(values, `${systemId}:${status}`, 1, entries.isSuccess, 10);
  const invalidate = () => queryClient.invalidateQueries({ queryKey: ['knowledge', systemId] });
  const create = useMutation({
    mutationFn: () => api.createKnowledge(systemId, {
      kind, title, anchorTexts: lines(anchors), routePath, apiEndpoints: lines(apiEndpoints), codeRefs: [], sourceRef: '',
    }),
    onSuccess: () => {
      console.info('v5 workbench 新增知识条目', { systemId, kind, title });
      dialogRef.current?.close();
      invalidate();
    },
  });
  const update = useMutation({
    mutationFn: ({ entryId, next }: { entryId: string; next: string }) => api.updateKnowledgeStatus(systemId, entryId, next),
    onSuccess: invalidate,
  });
  const index = useMutation({ mutationFn: () => api.runRouteIndex(systemId) });

  function resetDraft() {
    setKind('page');
    setTitle('');
    setAnchors('');
    setRoutePath('');
    setApiEndpoints('');
  }

  function openCreator() {
    resetDraft();
    // 知识条目使用居中弹窗录入，首屏只保留可扫描的列表。
    dialogRef.current?.showModal();
  }

  return <section className="management-page">
    <header className="page-head management-head">
      <div><h1>系统知识库</h1><p>维护页面、路由和接口知识；只有已批准条目会参与截图匹配。</p></div>
      <div className="button-row">
        <button type="button" className="secondary icon-text-button" onClick={() => index.mutate()} disabled={!systemId || index.isPending}><RefreshCw size={16} />运行路由索引</button>
        <button type="button" className="icon-text-button" onClick={openCreator} disabled={!systemId}><Plus size={16} />新增条目</button>
      </div>
    </header>

    {index.data && <div className="success-text">索引任务已启动：{index.data.workflowId}</div>}
    {index.error && <div className="error-text">路由索引启动失败。</div>}

    <div className="panel management-panel">
      <div className="tabs management-tabs" role="tablist" aria-label="知识条目状态">
        <button type="button" role="tab" aria-selected={status === 'candidate'} className={status === 'candidate' ? 'active' : ''} onClick={() => setStatus('candidate')}>待审批</button>
        <button type="button" role="tab" aria-selected={status === 'approved'} className={status === 'approved' ? 'active' : ''} onClick={() => setStatus('approved')}>已批准</button>
        <button type="button" role="tab" aria-selected={status === 'rejected'} className={status === 'rejected' ? 'active' : ''} onClick={() => setStatus('rejected')}>已拒绝</button>
      </div>
      <div className="table-frame"><table className="data-table management-table knowledge-table"><thead><tr><th>知识条目</th><th>类型</th><th>路由 / 接口</th><th>来源</th><th>操作</th></tr></thead><tbody>
        {pagination.pageItems.map((entry) => <tr key={entry.entryId}>
          <td><div className="table-title"><strong>{entry.title}</strong><span>{entry.anchorTexts.join('、') || '未设置可见文字锚点'}</span></div></td>
          <td><span className="status-badge info">{kindName(entry.kind)}</span></td>
          <td><div className="table-title"><strong>{entry.routePath || '未设置路由'}</strong><span>{entry.apiEndpoints.join('、') || '未设置接口'}</span></div></td>
          <td>{entry.source || '手工录入'}</td>
          <td>{status === 'candidate' ? <div className="button-row compact-actions"><button type="button" onClick={() => update.mutate({ entryId: entry.entryId, next: 'approved' })}>批准</button><button type="button" className="secondary" onClick={() => update.mutate({ entryId: entry.entryId, next: 'rejected' })}>拒绝</button></div> : status === 'approved' ? <button type="button" className="danger-outline" onClick={() => update.mutate({ entryId: entry.entryId, next: 'disabled' })}>停用</button> : <span className="status-badge neutral">已归档</span>}</td>
        </tr>)}
        {!values.length && <tr><td className="empty-cell" colSpan={5}>当前状态下暂无知识条目</td></tr>}
      </tbody></table></div>
      <Pagination total={values.length} page={pagination.page} totalPages={pagination.totalPages} onPageChange={pagination.setPage} />
      {(entries.error || update.error) && <div className="error-text">知识条目加载或更新失败。</div>}
    </div>

    <dialog ref={dialogRef} className="confirm-dialog config-dialog knowledge-dialog" aria-labelledby="knowledge-dialog-title" onClose={resetDraft}>
      <form onSubmit={(event: FormEvent) => { event.preventDefault(); create.mutate(); }}>
        <div className="config-section-head compact"><div><h2 id="knowledge-dialog-title">新增知识条目</h2><p>新条目会先进入待审批列表。</p></div></div>
        <label>类型<select value={kind} onChange={(event) => setKind(event.target.value)}><option value="page">页面</option><option value="route">路由</option><option value="api">接口</option></select></label>
        <label>标题<input required value={title} onChange={(event) => setTitle(event.target.value)} /></label>
        {kind !== 'api' && <label>可见文字锚点<span className="field-note">每行一条，用于定位页面。</span><textarea rows={4} value={anchors} onChange={(event) => setAnchors(event.target.value)} /></label>}
        {kind !== 'api' && <label>路由<input placeholder="例如 /work-items" value={routePath} onChange={(event) => setRoutePath(event.target.value)} /></label>}
        {kind !== 'route' && <label>接口<span className="field-note">每行一条，例如 GET /api/v5/work-items。</span><textarea rows={3} value={apiEndpoints} onChange={(event) => setApiEndpoints(event.target.value)} /></label>}
        {create.error && <div className="error-text">知识条目创建失败。</div>}
        <div className="button-row"><button type="button" className="secondary" onClick={() => dialogRef.current?.close()}>取消</button><button type="submit" disabled={!title.trim() || create.isPending}>加入待审批</button></div>
      </form>
    </dialog>
  </section>;
}

function lines(value: string) {
  return value.split('\n').map((item) => item.trim()).filter(Boolean);
}

function kindName(kind: string) {
  return { page: '页面', route: '路由', api: '接口' }[kind] || kind;
}
