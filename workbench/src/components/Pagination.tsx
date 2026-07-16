import { useEffect, useMemo, useRef, useState } from 'react';

const PAGE_SIZE = 20;

export function usePagination<T>(items: T[], resetKey: string, initialPage = 1, ready = true, pageSize = PAGE_SIZE) {
  const [page, setPage] = useState(initialPage);
  const previousResetKey = useRef(resetKey);
  const totalPages = Math.max(1, Math.ceil(items.length / pageSize));

  useEffect(() => {
    if (previousResetKey.current === resetKey) return;
    previousResetKey.current = resetKey;
    setPage(1);
  }, [resetKey]);
  useEffect(() => {
    if (ready) setPage((current) => Math.min(current, totalPages));
  }, [ready, totalPages]);

  const pageItems = useMemo(() => {
    const start = (page - 1) * pageSize;
    return items.slice(start, start + pageSize);
  }, [items, page, pageSize]);

  return { page, pageItems, setPage, totalPages };
}

export function Pagination({ total, page, totalPages, onPageChange }: {
  total: number;
  page: number;
  totalPages: number;
  onPageChange: (page: number) => void;
}) {
  return (
    <div className="pagination" aria-label="分页">
      <span>共 {total} 条</span>
      <div className="pagination-actions">
        <button type="button" className="secondary" disabled={page <= 1} onClick={() => onPageChange(page - 1)}>上一页</button>
        <span>第 {page} / {totalPages} 页</span>
        <button type="button" className="secondary" disabled={page >= totalPages} onClick={() => onPageChange(page + 1)}>下一页</button>
      </div>
    </div>
  );
}
