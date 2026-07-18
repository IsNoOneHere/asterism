import { fireEvent, screen, waitFor } from '@testing-library/react';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { renderApp, resetAppTestState, setWorkItems } from './appTestHarness';

describe('首页', () => {
  beforeEach(() => resetAppTestState());

  it('展示全局数据和南丁格尔玫瑰图，并只在手动刷新时重新查询', async () => {
    setWorkItems([
      { workItemId: 'WI20260718001', systemId: 'alpha-system', title: '登录页优化', lifecycleStatus: 'worker_blocked', updatedAt: '2026-07-18T06:00:00Z' },
      { workItemId: 'WI20260718002', systemId: 'alpha-system', title: '系统配置整理', lifecycleStatus: 'activated', updatedAt: '2026-07-18T05:00:00Z' },
      { workItemId: 'WI20260718003', systemId: 'prod-system', title: '知识库分页', lifecycleStatus: 'completed', updatedAt: '2026-07-18T04:00:00Z' },
    ]);

    renderApp('/');

    expect(await screen.findByRole('heading', { name: '全局交付概览' })).toBeInTheDocument();
    expect(screen.getByRole('img', { name: /系统工作量南丁格尔玫瑰图/ })).toBeInTheDocument();
    expect(screen.getByLabelText('工作项总数 3')).toBeInTheDocument();
    expect(screen.getAllByText('登录页优化')).toHaveLength(2);
    expect(screen.getByRole('link', { name: '返回首页' })).toHaveAttribute('href', '/');
    expect(fetch).toHaveBeenCalledWith('/api/v5/work-items?scope=all&sort=updated_desc', expect.anything());

    const before = vi.mocked(fetch).mock.calls.filter(([path]) => String(path).includes('/api/v5/work-items?scope=all')).length;
    fireEvent.click(screen.getByRole('button', { name: '刷新' }));
    await waitFor(() => expect(vi.mocked(fetch).mock.calls.filter(([path]) => String(path).includes('/api/v5/work-items?scope=all'))).toHaveLength(before + 1));
  });
});
