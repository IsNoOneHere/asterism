import { useQuery } from '@tanstack/react-query';
import { ClipboardList, FileText, Plus } from 'lucide-react';
import { Link, Outlet, useLocation } from 'react-router-dom';
import { api } from '../api/client';
import { isResumablePrd } from '../prd';
import { useCurrentSystem } from '../SystemContext';

export function WorkItemCenterLayout() {
  const { systemId } = useCurrentSystem();
  const { pathname } = useLocation();
  const drafts = useQuery({
    queryKey: ['prd-sessions', systemId],
    queryFn: () => api.prdSessions(systemId),
    enabled: Boolean(systemId),
    retry: false,
  });
  const draftCount = (drafts.data ?? []).filter(isResumablePrd).length;
  const draftActive = pathname === '/work-items/drafts';

  return (
    <section className="work-item-center">
      <header className="page-head">
        <div>
          <h1>工作项中心</h1>
          <p>统一创建需求、跟进草稿并处理工作项。</p>
        </div>
        <Link className="primary-action-link" to="/work-items/new">
          <Plus size={17} aria-hidden="true" />
          创建工作项
        </Link>
      </header>
      <nav className="page-tabs" aria-label="工作项中心">
        <Link className={pathname === '/work-items' ? 'active' : ''} to="/work-items" aria-current={pathname === '/work-items' ? 'page' : undefined}>
          <ClipboardList size={16} aria-hidden="true" />
          工作项
        </Link>
        <Link className={draftActive ? 'active' : ''} to="/work-items/drafts" aria-current={draftActive ? 'page' : undefined}>
          <FileText size={16} aria-hidden="true" />
          需求草稿
          {draftCount > 0 && <span>{draftCount}</span>}
        </Link>
      </nav>
      <Outlet />
    </section>
  );
}
