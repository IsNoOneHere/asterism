import { fireEvent, screen, waitFor } from '@testing-library/react';
import { beforeEach, expect, test, vi } from 'vitest';
import { jsonResponse, renderApp, resetAppTestState } from './appTestHarness';

beforeEach(resetAppTestState);

test('new prd page renders two chat turns as four bubbles', async () => {
  renderApp('/work-items/new');

  expect(await screen.findByRole('heading', { name: '创建工作项' })).toBeInTheDocument();
  expect(screen.queryByRole('navigation', { name: '工作项中心' })).not.toBeInTheDocument();
  expect(screen.queryByLabelText('当前工作系统')).not.toBeInTheDocument();
  expect(screen.getByRole('heading', { name: '工作项预览' })).toBeInTheDocument();
  expect(screen.getByText('待输入')).toBeInTheDocument();
  expect(screen.getByRole('link', { name: '返回工作项中心' })).toHaveAttribute('href', '/work-items');
  const input = await screen.findByLabelText('需求描述');
  expect(await screen.findByLabelText('所属系统')).toHaveTextContent('Alpha System');
  fireEvent.change(input, { target: { value: '用户第一轮' } });
  fireEvent.click(screen.getByRole('button', { name: '发送' }));
  expect(await screen.findByText('助手第一轮')).toBeInTheDocument();
  expect(await screen.findByRole('heading', { name: '继续创建工作项' })).toBeInTheDocument();
  expect(screen.getByRole('button', { name: '确认并生成工作项' })).toBeInTheDocument();
  const savedUnload = new Event('beforeunload', { cancelable: true });
  window.dispatchEvent(savedUnload);
  expect(savedUnload.defaultPrevented).toBe(false);

  fireEvent.change(input, { target: { value: '用户第二轮' } });
  fireEvent.click(screen.getByRole('button', { name: '发送' }));

  expect(await screen.findByText('用户第一轮')).toBeInTheDocument();
  expect(await screen.findByText('助手第一轮')).toBeInTheDocument();
  expect(await screen.findByText('用户第二轮')).toBeInTheDocument();
  expect(await screen.findByText('助手第二轮')).toBeInTheDocument();
});

test('warns before leaving an unsaved work item description', async () => {
  const confirm = vi.spyOn(window, 'confirm').mockReturnValue(false);
  renderApp('/work-items/new');

  fireEvent.change(await screen.findByLabelText('需求描述'), { target: { value: '尚未保存的需求' } });
  const unload = new Event('beforeunload', { cancelable: true });
  window.dispatchEvent(unload);
  expect(unload.defaultPrevented).toBe(true);

  fireEvent.click(screen.getByRole('link', { name: '返回工作项中心' }));
  expect(confirm).toHaveBeenCalledWith('内容尚未保存，是否离开？');
  expect(screen.getByRole('heading', { name: '创建工作项' })).toBeInTheDocument();
  confirm.mockRestore();
});

test('prd draft list shows creator display name and account', async () => {
  const fallback = vi.mocked(fetch).getMockImplementation()!;
  vi.mocked(fetch).mockImplementation((input: RequestInfo | URL, init?: RequestInit) => {
    const path = String(input);
    if (path.startsWith('/api/v5/prd-sessions?')) {
      return jsonResponse([{
        prdId: 'prd-history-1',
        systemId: 'alpha-system',
        conversationId: 'conversation-history-1',
        title: '历史 PRD',
        status: 'waiting_user_confirm',
        createdBy: 'admin',
        creatorDisplayName: 'Admin',
        draft: {},
        missingFields: [],
        updatedAt: '2026-07-11T00:00:00Z',
      }]);
    }
    return fallback(input, init);
  });

  renderApp('/work-items/drafts');

  expect(await screen.findByText('Admin (admin)')).toBeInTheDocument();
  expect(await screen.findByText('待确认')).toBeInTheDocument();
});

test('prd draft list defaults to resumable sessions and can show all records', async () => {
  const fallback = vi.mocked(fetch).getMockImplementation()!;
  vi.mocked(fetch).mockImplementation((input: RequestInfo | URL, init?: RequestInit) => {
    const path = String(input);
    if (path.startsWith('/api/v5/prd-sessions?')) {
      return jsonResponse([
        { prdId: 'prd-pending', systemId: 'alpha-system', conversationId: 'conv-pending', title: '待完善需求', status: 'need_clarification', createdBy: 'admin', draft: {}, missingFields: [] },
        { prdId: 'prd-finished', systemId: 'alpha-system', conversationId: 'conv-finished', workItemId: 'wi-finished', title: '已生成需求', status: 'confirmed', createdBy: 'admin', draft: {}, missingFields: [] },
      ]);
    }
    return fallback(input, init);
  });

  renderApp('/work-items/drafts');

  expect(await screen.findByText('待完善需求')).toBeInTheDocument();
  expect(screen.getByRole('link', { name: '继续完善' })).toHaveAttribute('href', '/work-items/new/prd-pending');
  expect(screen.queryByText('已生成需求')).not.toBeInTheDocument();
  fireEvent.click(screen.getByRole('button', { name: '全部记录' }));
  expect(await screen.findByText('已生成需求')).toBeInTheDocument();
  expect(screen.getByRole('link', { name: '查看工作项' })).toHaveAttribute('href', '/work-items/wi-finished');
});

test('draft editor restores session in the independent creation workspace', async () => {
  const fallback = vi.mocked(fetch).getMockImplementation()!;
  vi.mocked(fetch).mockImplementation((input: RequestInfo | URL, init?: RequestInit) => {
    const path = String(input);
    if (path === '/api/v5/prd-sessions/prd-resume') {
      return jsonResponse({
        prdId: 'prd-resume', systemId: 'prod-system', conversationId: 'conv-resume', title: '恢复的需求',
        status: 'need_clarification', createdBy: 'admin', draft: { title: '恢复的需求', goal: '继续完善' }, missingFields: ['acceptanceCriteria'],
      });
    }
    if (path === '/api/v5/conversations/conv-resume') {
      return jsonResponse([{ messageId: 'resume-message', conversationId: 'conv-resume', senderType: 'assistant', content: '请补充验收标准' }]);
    }
    return fallback(input, init);
  });

  renderApp('/work-items/new/prd-resume');

  expect(await screen.findByRole('heading', { name: '继续创建工作项' })).toBeInTheDocument();
  expect(screen.queryByRole('heading', { name: '工作项中心' })).not.toBeInTheDocument();
  expect(screen.getByRole('heading', { name: '工作项预览' })).toBeInTheDocument();
  expect(screen.getByRole('link', { name: '返回需求草稿' })).toHaveAttribute('href', '/work-items/drafts');
  expect(await screen.findByText('请补充验收标准')).toBeInTheDocument();
  expect(screen.getByLabelText('所属系统')).toBeDisabled();
  expect(screen.getByLabelText('所属系统')).toHaveTextContent('Prod System');
  await waitFor(() => expect(localStorage.getItem('asterism-system')).toBe('prod-system'));
  expect(screen.queryByRole('heading', { name: '需求记录' })).not.toBeInTheDocument();
});

test('legacy draft editor route opens the independent creation workspace', async () => {
  const fallback = vi.mocked(fetch).getMockImplementation()!;
  vi.mocked(fetch).mockImplementation((input: RequestInfo | URL, init?: RequestInit) => {
    const path = String(input);
    if (path === '/api/v5/prd-sessions/prd-legacy') {
      return jsonResponse({ prdId: 'prd-legacy', systemId: 'alpha-system', conversationId: 'conv-legacy', status: 'need_clarification', createdBy: 'admin', draft: {}, missingFields: [] });
    }
    if (path === '/api/v5/conversations/conv-legacy') return jsonResponse([]);
    return fallback(input, init);
  });

  renderApp('/work-items/drafts/prd-legacy');

  expect(await screen.findByRole('heading', { name: '继续创建工作项' })).toBeInTheDocument();
  expect(screen.queryByRole('navigation', { name: '工作项中心' })).not.toBeInTheDocument();
});
