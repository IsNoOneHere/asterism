import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { useMemo, useState } from 'react';
import { api, MemoryItem } from '../api/client';
import { Pagination, usePagination } from '../components/Pagination';
import { useCurrentSystem } from '../SystemContext';

type Tab = 'candidate' | 'approved' | 'closed';

export function MemoryPage() {
  const queryClient = useQueryClient();
  const [content, setContent] = useState('');
  const { systemId } = useCurrentSystem();
  const [tab, setTab] = useState<Tab>('candidate');
  const [message, setMessage] = useState('');
  const candidate = useQuery({ queryKey: ['memory', systemId, 'candidate'], queryFn: () => api.memories(systemId, 'candidate'), enabled: Boolean(systemId), retry: false });
  const approved = useQuery({ queryKey: ['memory', systemId, 'approved'], queryFn: () => api.memories(systemId, 'approved'), enabled: Boolean(systemId), retry: false });
  const rejected = useQuery({ queryKey: ['memory', systemId, 'rejected'], queryFn: () => api.memories(systemId, 'rejected'), enabled: Boolean(systemId) && tab === 'closed', retry: false });
  const disabled = useQuery({ queryKey: ['memory', systemId, 'disabled'], queryFn: () => api.memories(systemId, 'disabled'), enabled: Boolean(systemId) && tab === 'closed', retry: false });

  const create = useMutation({
    mutationFn: () => api.createMemory({ systemId, content }),
    onSuccess: () => {
      console.info('v5 workbench 手工沉淀记忆', { systemId });
      setContent('');
      setMessage('已进入待审批');
      invalidate(queryClient, systemId);
    },
  });
  const approve = useMutation({
    mutationFn: api.approveMemory,
    onSuccess: () => invalidate(queryClient, systemId),
  });
  const reject = useMutation({
    mutationFn: api.rejectMemory,
    onSuccess: () => invalidate(queryClient, systemId),
  });
  const disable = useMutation({
    mutationFn: api.disableMemory,
    onSuccess: () => invalidate(queryClient, systemId),
  });
  const items = useMemo(() => {
    if (tab === 'candidate') return candidate.data ?? [];
    if (tab === 'approved') return approved.data ?? [];
    return [...(rejected.data ?? []), ...(disabled.data ?? [])];
  }, [approved.data, candidate.data, disabled.data, rejected.data, tab]);
  const pagination = usePagination(items, systemId + ':' + tab);

  return (
    <section className="split wide-left">
      <div className="panel">
        <h1>系统记忆</h1>
        <div className="notice">记忆是系统级约束、经验教训和失败原因；自动来自 WorkerBlocked/ValidationFailed 等生命周期事件，也可手工录入。只有 approved 会进入 worker 执行上下文，candidate/rejected 不投喂。</div>
        <textarea
          rows={5}
          placeholder="本系统禁止修改 db/migration 下的历史文件"
          value={content}
          onChange={(event) => setContent(event.target.value)}
        />
        <button type="button" onClick={() => create.mutate()} disabled={!systemId || !content.trim()}>
          沉淀 candidate
        </button>
        {message && <div className="success-text">{message}</div>}
      </div>
      <div className="panel">
        <div className="tabs">
          <button type="button" className={tab === 'candidate' ? 'active' : ''} onClick={() => setTab('candidate')}>待审批</button>
          <button type="button" className={tab === 'approved' ? 'active' : ''} onClick={() => setTab('approved')}>已批准</button>
          <button type="button" className={tab === 'closed' ? 'active' : ''} onClick={() => setTab('closed')}>已拒绝·停用</button>
        </div>
        {pagination.pageItems.map((item) => (
          <MemoryRow
            key={item.memoryId}
            item={item}
            tab={tab}
            onApprove={() => approve.mutate(item.memoryId)}
            onReject={() => { if (window.confirm('拒绝后该记忆不会进入执行上下文，是否继续？')) reject.mutate(item.memoryId); }}
            onDisable={() => { if (window.confirm('停用后该记忆不会再进入执行上下文，是否继续？')) disable.mutate(item.memoryId); }}
          />
        ))}
        {!items.length && <div className="empty">暂无记忆。</div>}
        <Pagination total={items.length} page={pagination.page} totalPages={pagination.totalPages} onPageChange={pagination.setPage} />
      </div>
    </section>
  );
}

function MemoryRow({ item, tab, onApprove, onReject, onDisable }: {
  item: MemoryItem;
  tab: Tab;
  onApprove: () => void;
  onReject: () => void;
  onDisable: () => void;
}) {
  const [expanded, setExpanded] = useState(false);
  const long = item.content.length > 280;
  return (
    <div className="list-item action-item">
      <div>
        <strong>{expanded || !long ? item.content : item.content.slice(0, 280) + '...'}</strong>
        <span>{item.sourceEventId ? '自动沉淀' : '手工录入'} · {item.createdAt || '未知时间'} · {item.status}</span>
        {long && <button type="button" className="link-button" onClick={() => setExpanded(!expanded)}>{expanded ? '收起' : '展开'}</button>}
      </div>
      {tab === 'candidate' && (
        <div className="button-row">
          <button type="button" onClick={onApprove}>批准</button>
          <button type="button" className="secondary" onClick={onReject}>拒绝</button>
        </div>
      )}
      {tab === 'approved' && <button type="button" className="secondary" onClick={onDisable}>停用</button>}
    </div>
  );
}

function invalidate(queryClient: ReturnType<typeof useQueryClient>, systemId: string) {
  queryClient.invalidateQueries({ queryKey: ['memory', systemId] });
  queryClient.invalidateQueries({ queryKey: ['context-snapshot', systemId] });
}
