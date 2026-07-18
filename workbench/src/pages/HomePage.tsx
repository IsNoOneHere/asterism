import { useQuery } from '@tanstack/react-query';
import { Plus, RefreshCw } from 'lucide-react';
import { Link } from 'react-router-dom';
import { api } from '../api/client';
import type { SystemProfile, WorkItem } from '../api/client';
import { ErrorState, formatDateTime, StatusBadge } from '../components/Display';
import { useCurrentSystem } from '../SystemContext';

const activeStatuses = new Set(['activated', 'case_starting', 'modification_completed', 'patch_applied', 'validation_passed', 'waiting_merge']);
const attentionStatuses = new Set(['waiting_owner_approval', 'modification_completed', 'waiting_merge', 'worker_blocked', 'validation_failed', 'case_start_failed']);
const blockedStatuses = new Set(['worker_blocked', 'validation_failed', 'case_start_failed']);

const lifecycleGroups = [
  { label: '待审批', statuses: ['waiting_owner_approval'] },
  { label: '执行中', statuses: ['activated', 'case_starting'] },
  { label: '验证发布', statuses: ['modification_completed', 'patch_applied', 'validation_passed', 'waiting_merge'] },
  { label: '阻塞失败', statuses: [...blockedStatuses] },
  { label: '已完成', statuses: ['completed'] },
];

export function HomePage() {
  const { systems } = useCurrentSystem();
  const items = useQuery({
    queryKey: ['work-items', 'home', 'all'],
    queryFn: () => api.workItems({ scope: 'all', sort: 'updated_desc' }),
    retry: false,
    staleTime: Infinity,
    refetchOnWindowFocus: false,
    refetchOnReconnect: false,
  });

  const values = items.data ?? [];
  const lifecycle = buildLifecycle(values);
  const distribution = buildSystemDistribution(values, systems);
  const attention = values.filter((item) => attentionStatuses.has(item.lifecycleStatus)).slice(0, 5);
  const recent = values.slice(0, 5);
  const updatedAt = items.dataUpdatedAt ? formatDateTime(new Date(items.dataUpdatedAt).toISOString()) : '尚未更新';

  return (
    <div className="home-page">
      <header className="page-head home-page-head">
        <div>
          <h1>全局交付概览</h1>
          <p>当前账号可见范围 · {systems.length} 个系统 · 更新于 {updatedAt}</p>
        </div>
        <div className="home-head-actions">
          <button type="button" className="secondary icon-text-button" disabled={items.isFetching} onClick={() => items.refetch()}>
            <RefreshCw size={16} aria-hidden="true" />{items.isFetching ? '刷新中…' : '刷新'}
          </button>
          <Link className="primary-action-link" to="/work-items/new"><Plus size={16} aria-hidden="true" />创建工作项</Link>
        </div>
      </header>

      {items.isError && <ErrorState title="首页数据加载失败" error={items.error} onRetry={() => items.refetch()} />}
      {items.isLoading && <div className="panel home-loading">首页数据加载中…</div>}
      {items.isSuccess && <>
        <div className="home-metrics">
          <Metric label="工作项总数" value={values.length} detail={`覆盖 ${systems.length} 个系统`} />
          <Metric label="正在推进" value={countByStatus(values, activeStatuses)} detail="执行、验证与发布阶段" />
          <Metric label="待人工处理" value={countByStatus(values, attentionStatuses)} detail="审批、Patch 与合并确认" />
          <Metric label="阻塞 / 失败" value={countByStatus(values, blockedStatuses)} detail="需要优先关注" />
        </div>

        <div className="home-chart-grid">
          <section className="panel home-chart-panel" aria-labelledby="home-lifecycle-title">
            <div className="home-section-head">
              <div><h2 id="home-lifecycle-title">生命周期分布</h2><p>工作项数量</p></div>
              <span>{values.length} 条</span>
            </div>
            <LifecycleChart data={lifecycle} />
          </section>
          <section className="panel home-chart-panel" aria-labelledby="home-system-title">
            <div className="home-section-head">
              <div><h2 id="home-system-title">系统工作量分布</h2><p>等角度扇区，面积表示工作项数量</p></div>
              <span>{distribution.length} 个系统</span>
            </div>
            <RoseChart data={distribution} />
          </section>
        </div>

        <div className="home-list-grid">
          <WorkItemList title="需要关注" description="阻塞、待审批与等待合并" items={attention} systems={systems} empty="当前没有需要关注的工作项。" />
          <WorkItemList title="最近更新" description="按更新时间倒序" items={recent} systems={systems} empty="当前没有工作项。" />
        </div>
      </>}
    </div>
  );
}

function Metric({ label, value, detail }: { label: string; value: number; detail: string }) {
  return <section className="panel home-metric" aria-label={`${label} ${value}`}>
    <span>{label}</span><strong>{value}</strong><small>{detail}</small>
  </section>;
}

function LifecycleChart({ data }: { data: Array<{ label: string; count: number }> }) {
  const max = Math.max(...data.map((item) => item.count), 1);
  const description = data.map((item) => `${item.label} ${item.count} 条`).join('，');
  return <div className="home-bar-chart" role="img" aria-label={`生命周期柱形图：${description}`}>
    {data.map((item) => <div className="home-bar-column" key={item.label}>
      <div className="home-bar-plot"><strong>{item.count}</strong><i style={{ height: `${item.count ? Math.max(item.count / max * 174, 4) : 2}px` }} /></div>
      <span>{item.label}</span>
    </div>)}
  </div>;
}

type DistributionItem = { systemId: string; name: string; count: number };

function RoseChart({ data }: { data: DistributionItem[] }) {
  if (data.length === 0) return <div className="home-chart-empty">暂无系统工作量数据。</div>;
  const max = Math.max(...data.map((item) => item.count));
  return <div className="home-rose-layout">
    <svg className="home-rose-chart" viewBox="0 0 300 300" role="img" aria-labelledby="home-rose-title home-rose-description">
      <title id="home-rose-title">系统工作量南丁格尔玫瑰图</title>
      <desc id="home-rose-description">{data.map((item) => `${item.name} ${item.count} 条`).join('，')}</desc>
      <g className="home-rose-grid" aria-hidden="true"><circle cx="150" cy="150" r="36" /><circle cx="150" cy="150" r="72" /><circle cx="150" cy="150" r="108" /></g>
      <g>{data.map((item, index) => <path key={item.systemId} className={`home-rose-slice home-series-${index + 1}`}
        d={roseSectorPath(index, data.length, 108 * Math.sqrt(item.count / max))}><title>{item.name}：{item.count} 条</title></path>)}</g>
    </svg>
    <div className="home-rose-legend" aria-label="系统工作量图例">
      {data.map((item, index) => <span key={item.systemId}><i className={`home-series-${index + 1}`} aria-hidden="true" /><b title={item.name}>{item.name}</b><strong>{item.count}</strong></span>)}
    </div>
  </div>;
}

function WorkItemList({ title, description, items, systems, empty }: {
  title: string;
  description: string;
  items: WorkItem[];
  systems: SystemProfile[];
  empty: string;
}) {
  const names = new Map(systems.map((system) => [system.systemId, system.name]));
  return <section className="panel home-list-panel">
    <div className="home-section-head"><div><h2>{title}</h2><p>{description}</p></div><Link to="/work-items">查看全部</Link></div>
    {items.length === 0 ? <div className="home-list-empty">{empty}</div> : <ul>
      {items.map((item) => <li key={item.workItemId}>
        <Link to={`/work-items/${item.workItemId}`} title={item.title || item.workItemId}><strong>{item.title || '未命名工作项'}</strong><small>{item.workItemId}</small></Link>
        <span className="home-list-system" title={names.get(item.systemId) || item.systemId}>{names.get(item.systemId) || item.systemId}</span>
        <StatusBadge value={item.lifecycleStatus} />
        <time dateTime={item.updatedAt}>{formatDateTime(item.updatedAt)}</time>
      </li>)}
    </ul>}
  </section>;
}

function countByStatus(items: WorkItem[], statuses: Set<string>) {
  return items.filter((item) => statuses.has(item.lifecycleStatus)).length;
}

function buildLifecycle(items: WorkItem[]) {
  const known = new Set(lifecycleGroups.flatMap((group) => group.statuses));
  const values = lifecycleGroups.map((group) => ({ label: group.label, count: items.filter((item) => group.statuses.includes(item.lifecycleStatus)).length }));
  return [...values, { label: '其他 / 关闭', count: items.filter((item) => !known.has(item.lifecycleStatus)).length }];
}

function buildSystemDistribution(items: WorkItem[], systems: SystemProfile[]): DistributionItem[] {
  const names = new Map(systems.map((system) => [system.systemId, system.name]));
  const counts = new Map<string, number>();
  items.forEach((item) => counts.set(item.systemId, (counts.get(item.systemId) ?? 0) + 1));
  const ordered = [...counts].map(([systemId, count]) => ({ systemId, name: names.get(systemId) || systemId, count })).sort((left, right) => right.count - left.count);
  if (ordered.length <= 8) return ordered;
  return [...ordered.slice(0, 7), {
    systemId: 'other-systems',
    name: '其他系统',
    count: ordered.slice(7).reduce((total, item) => total + item.count, 0),
  }];
}

function roseSectorPath(index: number, total: number, radius: number) {
  // 南丁格尔图保持扇区等角，并用半径平方关系表达数量。
  const step = 360 / total;
  const gap = Math.min(2, step * 0.08);
  const start = index * step - 90 + gap;
  const end = (index + 1) * step - 90 - gap;
  const from = polarPoint(150, 150, radius, start);
  const to = polarPoint(150, 150, radius, end);
  return `M 150 150 L ${from.x} ${from.y} A ${radius.toFixed(2)} ${radius.toFixed(2)} 0 ${end - start > 180 ? 1 : 0} 1 ${to.x} ${to.y} Z`;
}

function polarPoint(cx: number, cy: number, radius: number, angle: number) {
  const radians = angle * Math.PI / 180;
  return { x: (cx + radius * Math.cos(radians)).toFixed(2), y: (cy + radius * Math.sin(radians)).toFixed(2) };
}
