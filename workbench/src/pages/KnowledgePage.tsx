import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { FormEvent, useEffect, useRef, useState } from 'react';
import { Plus, RefreshCw } from 'lucide-react';
import { api } from '../api/client';
import { ActionConfirmDialog } from '../components/ActionConfirmDialog';
import { ErrorState } from '../components/Display';
import { Pagination } from '../components/Pagination';
import { SearchField } from '../components/SearchField';
import { useCurrentSystem } from '../SystemContext';

type Status = 'candidate' | 'approved' | 'rejected' | 'disabled';

export function KnowledgePage() {
  const { systemId, canManageCurrentSystem, systemAccessLoading, systemAccessError } = useCurrentSystem();
  const queryClient = useQueryClient();
  const dialogRef = useRef<HTMLDialogElement>(null);
  const [status, setStatus] = useState<Status>('candidate');
  const [repo, setRepo] = useState('main');
  const [kind, setKind] = useState('page');
  const [title, setTitle] = useState('');
  const [anchors, setAnchors] = useState('');
  const [routePath, setRoutePath] = useState('');
  const [apiEndpoints, setApiEndpoints] = useState('');
  const [query, setQuery] = useState('');
  const [page, setPage] = useState(1);
  const [confirmAction, setConfirmAction] = useState<{ entryId: string; title: string; next: 'rejected' | 'disabled' } | null>(null);
  const entries = useQuery({
    queryKey: ['knowledge', systemId, status, page, query],
    queryFn: () => api.knowledgePage(systemId, status, page, query.trim()),
    enabled: Boolean(systemId),
    retry: false,
  });
  const gitConfig = useQuery({
    queryKey: ['git-config', systemId],
    queryFn: () => api.gitConfiguration(systemId),
    enabled: Boolean(systemId) && canManageCurrentSystem,
    retry: false,
  });
  const values = entries.data?.items ?? [];
  useEffect(() => {
    if (entries.data && entries.data.page !== page) setPage(entries.data.page);
  }, [entries.data, page]);
  const invalidate = () => queryClient.invalidateQueries({ queryKey: ['knowledge', systemId] });
  const create = useMutation({
    mutationFn: () => api.createKnowledge(systemId, {
      repo, kind, title, anchorTexts: lines(anchors), routePath, apiEndpoints: lines(apiEndpoints), codeRefs: [], sourceRef: '',
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
    onSettled: () => setConfirmAction(null),
  });
  const index = useMutation({ mutationFn: () => api.runRouteIndex(systemId) });

  function resetDraft() {
    setRepo(gitConfig.data?.repos[0]?.repoId || 'main');
    setKind('page');
    setTitle('');
    setAnchors('');
    setRoutePath('');
    setApiEndpoints('');
  }

  function openCreator() {
    create.reset();
    resetDraft();
    // 知识条目使用居中弹窗录入，首屏只保留可扫描的列表。
    dialogRef.current?.showModal();
  }

  function selectStatus(next: Status) {
    setStatus(next);
    setQuery('');
    setPage(1);
  }

  return <section className="management-page">
    <header className="page-head management-head">
      <div><h1>系统知识库</h1><p>维护页面、路由和接口知识；只有已批准条目会参与截图匹配。</p></div>
      <div className="button-row">
        <button type="button" className="secondary icon-text-button" onClick={() => index.mutate()} disabled={!systemId || !canManageCurrentSystem || index.isPending}><RefreshCw size={16} />运行路由索引</button>
        <button type="button" className="icon-text-button" onClick={openCreator} disabled={!systemId || !canManageCurrentSystem || gitConfig.isLoading || gitConfig.isError}><Plus size={16} />新增条目</button>
      </div>
    </header>
    {!systemAccessLoading && !systemAccessError && !canManageCurrentSystem && <div className="notice">当前账号在此系统中为只读成员，知识维护操作已禁用。</div>}
    {gitConfig.isError && <ErrorState title="仓库配置加载失败" error={gitConfig.error} onRetry={() => gitConfig.refetch()} />}

    {index.data && <div className="success-text">索引任务已启动：{index.data.workflowId}</div>}
    {index.error && <div className="error-text">{index.error.message}</div>}

    <div className="panel management-panel">
      <div className="tabs management-tabs" role="tablist" aria-label="知识条目状态">
        <button type="button" role="tab" aria-selected={status === 'candidate'} className={status === 'candidate' ? 'active' : ''} onClick={() => selectStatus('candidate')}>待审批</button>
        <button type="button" role="tab" aria-selected={status === 'approved'} className={status === 'approved' ? 'active' : ''} onClick={() => selectStatus('approved')}>已批准</button>
        <button type="button" role="tab" aria-selected={status === 'rejected'} className={status === 'rejected' ? 'active' : ''} onClick={() => selectStatus('rejected')}>已拒绝</button>
        <button type="button" role="tab" aria-selected={status === 'disabled'} className={status === 'disabled' ? 'active' : ''} onClick={() => selectStatus('disabled')}>已停用</button>
      </div>
      <div className="management-toolbar">
        <SearchField value={query} label="搜索知识条目" placeholder="搜索标题、路由、接口或锚点" onChange={(value) => { setQuery(value); setPage(1); }} />
        {entries.data && <span className="result-summary">共 {entries.data.total} 条</span>}
      </div>
      {entries.isLoading ? <div className="empty" role="status">知识条目加载中…</div> : entries.isError ?
      <ErrorState title="知识条目加载失败" error={entries.error} onRetry={() => entries.refetch()} /> : <>
      <div className="table-frame"><table className="data-table management-table knowledge-table"><thead><tr><th>知识条目</th><th>类型</th><th>路由 / 接口</th><th>来源</th><th>操作</th></tr></thead><tbody>
        {values.map((entry) => <tr key={entry.entryId}>
          <td><div className="table-title" title={entry.title}><strong>{entry.title}</strong><span>仓库：{entry.repo || 'main'} · {entry.anchorTexts.length ? `${entry.anchorTexts.length} 个文字锚点` : '未设置文字锚点'}</span></div></td>
          <td><span className="status-badge info">{kindName(entry.kind)}</span></td>
          <td><div className="table-title" title={`${entry.routePath || '未设置路由'} · ${entry.apiEndpoints.join('、') || '未设置接口'}`}><strong>{entry.routePath || '未设置路由'}</strong><span>{entry.apiEndpoints.join('、') || '未设置接口'}</span></div></td>
          <td title={entry.source || '手工录入'}>{entry.source || '手工录入'}</td>
          <td>{!canManageCurrentSystem ? <span className="status-badge neutral">仅查看</span> : status === 'candidate' ? <div className="button-row compact-actions"><button type="button" disabled={update.isPending} onClick={() => { update.reset(); update.mutate({ entryId: entry.entryId, next: 'approved' }); }}>批准</button><button type="button" className="secondary" disabled={update.isPending} onClick={() => { update.reset(); setConfirmAction({ entryId: entry.entryId, title: entry.title, next: 'rejected' }); }}>拒绝</button></div> : status === 'approved' ? <button type="button" className="danger-outline" disabled={update.isPending} onClick={() => { update.reset(); setConfirmAction({ entryId: entry.entryId, title: entry.title, next: 'disabled' }); }}>停用</button> : <span className="status-badge neutral">已归档</span>}</td>
        </tr>)}
        {!values.length && <tr><td className="empty-cell" colSpan={5}>{query ? '没有匹配的知识条目' : '当前状态下暂无知识条目'}</td></tr>}
      </tbody></table></div>
      <Pagination total={entries.data?.total ?? 0} page={entries.data?.page ?? page} totalPages={entries.data?.totalPages ?? 1} onPageChange={setPage} />
      </>}
    </div>

    <dialog ref={dialogRef} className="confirm-dialog config-dialog knowledge-dialog" aria-labelledby="knowledge-dialog-title" onClose={resetDraft}>
      <form onSubmit={(event: FormEvent) => { event.preventDefault(); create.mutate(); }}>
        <div className="config-section-head compact"><div><h2 id="knowledge-dialog-title">新增知识条目</h2><p>新条目会先进入待审批列表。</p></div></div>
        <label>所属仓库<select value={repo} onChange={(event) => setRepo(event.target.value)}>{(gitConfig.data?.repos || []).map((item) => <option key={item.repoId} value={item.repoId}>{item.name}</option>)}{!gitConfig.data?.repos.length && <option value="main">main</option>}</select></label>
        <label>类型<select value={kind} onChange={(event) => setKind(event.target.value)}><option value="page">页面</option><option value="route">路由</option><option value="api">接口</option></select></label>
        <label>标题<input required value={title} onChange={(event) => setTitle(event.target.value)} /></label>
        {kind !== 'api' && <label>可见文字锚点<span className="field-note">每行一条，用于定位页面。</span><textarea rows={4} value={anchors} onChange={(event) => setAnchors(event.target.value)} /></label>}
        {kind !== 'api' && <label>路由<input placeholder="例如 /work-items" value={routePath} onChange={(event) => setRoutePath(event.target.value)} /></label>}
        {kind !== 'route' && <label>接口<span className="field-note">每行一条，例如 GET /api/v5/work-items。</span><textarea rows={3} value={apiEndpoints} onChange={(event) => setApiEndpoints(event.target.value)} /></label>}
        {create.error && <div className="error-text">{create.error.message}</div>}
        <div className="button-row"><button type="button" className="secondary" onClick={() => dialogRef.current?.close()}>取消</button><button type="submit" disabled={!canManageCurrentSystem || !title.trim() || create.isPending}>加入待审批</button></div>
      </form>
    </dialog>
    <ActionConfirmDialog
      open={Boolean(confirmAction)}
      title={`${confirmAction?.next === 'rejected' ? '拒绝' : '停用'}“${confirmAction?.title || ''}”？`}
      description={confirmAction?.next === 'rejected' ? '拒绝后该条目不会参与页面和接口匹配。' : '停用后该条目不会再参与页面和接口匹配。'}
      confirmLabel={confirmAction?.next === 'rejected' ? '拒绝条目' : '停用条目'}
      pending={update.isPending}
      onClose={() => setConfirmAction(null)}
      onConfirm={() => confirmAction && update.mutate({ entryId: confirmAction.entryId, next: confirmAction.next })}
    />
    <ActionConfirmDialog
      open={Boolean(update.error)}
      title="知识条目更新失败"
      description={update.error?.message || ''}
      confirmLabel="知道了"
      alert
      showCancel={false}
      onClose={() => update.reset()}
      onConfirm={() => update.reset()}
    />
  </section>;
}

function lines(value: string) {
  return value.split('\n').map((item) => item.trim()).filter(Boolean);
}

function kindName(kind: string) {
  return { page: '页面', route: '路由', api: '接口' }[kind] || kind;
}
