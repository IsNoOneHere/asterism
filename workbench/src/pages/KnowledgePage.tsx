import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { useState } from 'react';
import { api } from '../api/client';
import { useCurrentSystem } from '../SystemContext';

type Status = 'candidate' | 'approved' | 'rejected';

export function KnowledgePage() {
  const { systemId } = useCurrentSystem();
  const queryClient = useQueryClient();
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
  const invalidate = () => queryClient.invalidateQueries({ queryKey: ['knowledge', systemId] });
  const create = useMutation({
    mutationFn: () => api.createKnowledge(systemId, {
      kind, title, anchorTexts: lines(anchors), routePath, apiEndpoints: lines(apiEndpoints), codeRefs: [], sourceRef: '',
    }),
    onSuccess: () => { setTitle(''); setAnchors(''); setRoutePath(''); setApiEndpoints(''); invalidate(); },
  });
  const update = useMutation({
    mutationFn: ({ entryId, next }: { entryId: string; next: string }) => api.updateKnowledgeStatus(systemId, entryId, next),
    onSuccess: invalidate,
  });
  const index = useMutation({ mutationFn: () => api.runRouteIndex(systemId) });

  return <section className="split wide-left">
    <div className="panel">
      <h1>系统知识库</h1>
      <div className="notice">路由、页面和接口先进入 candidate；只有 approved 会参与截图匹配。</div>
      <div className="button-row"><button type="button" onClick={() => index.mutate()} disabled={!systemId || index.isPending}>运行路由索引</button></div>
      {index.data && <div className="success-text">索引任务已启动：{index.data.workflowId}</div>}
      <h2>手工新增</h2>
      <label>类型<select value={kind} onChange={(event) => setKind(event.target.value)}><option value="page">页面</option><option value="route">路由</option><option value="api">接口</option></select></label>
      <label>标题<input value={title} onChange={(event) => setTitle(event.target.value)} /></label>
      <label>可见文字锚点（每行一条）<textarea rows={4} value={anchors} onChange={(event) => setAnchors(event.target.value)} /></label>
      <label>路由<input value={routePath} onChange={(event) => setRoutePath(event.target.value)} /></label>
      <label>接口（每行一条）<textarea rows={3} value={apiEndpoints} onChange={(event) => setApiEndpoints(event.target.value)} /></label>
      <button type="button" disabled={!systemId || !title.trim() || create.isPending} onClick={() => create.mutate()}>加入待审批</button>
    </div>
    <div className="panel">
      <div className="tabs">
        <button type="button" className={status === 'candidate' ? 'active' : ''} onClick={() => setStatus('candidate')}>待审批</button>
        <button type="button" className={status === 'approved' ? 'active' : ''} onClick={() => setStatus('approved')}>已批准</button>
        <button type="button" className={status === 'rejected' ? 'active' : ''} onClick={() => setStatus('rejected')}>已拒绝</button>
      </div>
      {(entries.data ?? []).map((entry) => <div className="list-item action-item" key={entry.entryId}>
        <div><strong>{entry.title}</strong><span>{entry.kind} · {entry.routePath || '-'} · {entry.source}</span><small>{entry.anchorTexts.join('、')}</small><small>{entry.apiEndpoints.join('、')}</small></div>
        {status === 'candidate' && <div className="button-row"><button type="button" onClick={() => update.mutate({ entryId: entry.entryId, next: 'approved' })}>批准</button><button type="button" className="secondary" onClick={() => update.mutate({ entryId: entry.entryId, next: 'rejected' })}>拒绝</button></div>}
        {status === 'approved' && <button type="button" className="secondary" onClick={() => update.mutate({ entryId: entry.entryId, next: 'disabled' })}>停用</button>}
      </div>)}
      {!entries.data?.length && <div className="empty">暂无知识条目。</div>}
    </div>
  </section>;
}

function lines(value: string) {
  return value.split('\n').map((item) => item.trim()).filter(Boolean);
}
