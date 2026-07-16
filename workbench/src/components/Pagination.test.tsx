import { act, renderHook } from '@testing-library/react';
import { describe, expect, it } from 'vitest';
import { usePagination } from './Pagination';

describe('usePagination', () => {
  it('支持知识库每页 10 条', () => {
    const items = Array.from({ length: 21 }, (_, index) => index + 1);
    const { result } = renderHook(() => usePagination(items, 'knowledge', 1, true, 10));

    expect(result.current.pageItems).toEqual(items.slice(0, 10));
    expect(result.current.totalPages).toBe(3);

    act(() => result.current.setPage(2));
    expect(result.current.pageItems).toEqual(items.slice(10, 20));
  });
});
