import { act, fireEvent, render, screen, waitFor, within } from '@testing-library/react';
import { beforeEach, expect, test, vi } from 'vitest';
import { PendingAssistantBubble } from '../src/pages/NewPrdPage';
import { jsonResponse, renderApp, renderAppWithRouter, resetAppTestState } from './appTestHarness';

beforeEach(resetAppTestState);

test('pending assistant bubble announces analysis progress', () => {
  render(<PendingAssistantBubble />);

  expect(screen.getByRole('status')).toHaveTextContent('正在分析…');
});

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
  renderApp('/work-items/new');

  fireEvent.change(await screen.findByLabelText('需求描述'), { target: { value: '尚未保存的需求' } });
  const unload = new Event('beforeunload', { cancelable: true });
  window.dispatchEvent(unload);
  expect(unload.defaultPrevented).toBe(true);

  fireEvent.click(screen.getByRole('link', { name: '返回工作项中心' }));
  const dialog = await screen.findByRole('dialog');
  expect(dialog).toHaveTextContent('内容尚未保存，是否离开？');
  fireEvent.click(within(dialog).getByRole('button', { name: '取消' }));
  expect(screen.getByRole('heading', { name: '创建工作项' })).toBeInTheDocument();
});

test('browser back is blocked for unsent content on an existing draft', async () => {
  const router = renderAppWithRouter('/work-items');
  await act(async () => { await router.navigate('/work-items/new/prd-1'); });
  fireEvent.change(await screen.findByLabelText('需求描述'), { target: { value: '草稿里尚未发送的补充' } });

  act(() => { void router.navigate(-1); });

  const dialog = await screen.findByRole('dialog');
  expect(dialog).toHaveTextContent('离开当前页面？');
  expect(screen.getByRole('heading', { name: '继续创建工作项' })).toBeInTheDocument();
});

test('manual draft edits are protected until saved', async () => {
  renderApp('/work-items/new/prd-1');
  const title = await screen.findByLabelText('PRD 标题');
  fireEvent.change(title, { target: { value: '尚未保存的新标题' } });
  fireEvent.click(screen.getByRole('link', { name: '返回需求草稿' }));

  expect(await screen.findByRole('dialog')).toHaveTextContent('内容尚未保存，是否离开？');
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
      return jsonResponse({ messages: [{ messageId: 'resume-message', conversationId: 'conv-resume', senderType: 'assistant', content: '请补充验收标准' }], pendingAssistant: false });
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

test('draft editor highlights and saves missing acceptance criteria', async () => {
  const fallback = vi.mocked(fetch).getMockImplementation()!;
  vi.mocked(fetch).mockImplementation((input: RequestInfo | URL, init?: RequestInit) => {
    const path = String(input);
    if (path === '/api/v5/prd-sessions/prd-1' && !init?.method) {
      return jsonResponse({
        prdId: 'prd-1', systemId: 'alpha-system', conversationId: 'conv-prd-1', title: '登录提示', goal: '说明失败原因',
        status: 'need_clarification', createdBy: 'admin', draft: { title: '登录提示', goal: '说明失败原因', acceptanceCriteria: [] }, missingFields: ['acceptance_criteria'],
      });
    }
    return fallback(input, init);
  });
  renderApp('/work-items/new/prd-1');

  expect(await screen.findByText('可以直接在这里填写，不用打字描述')).toBeInTheDocument();
  fireEvent.click(screen.getByRole('button', { name: '添加验收标准' }));
  fireEvent.change(screen.getByLabelText('验收标准 1'), { target: { value: '错误密码时显示中文提示' } });
  fireEvent.click(screen.getByRole('button', { name: '保存草稿' }));

  await waitFor(() => expect(fetch).toHaveBeenCalledWith('/api/v5/prd-sessions/prd-1/draft', expect.objectContaining({
    method: 'PATCH',
    body: JSON.stringify({ title: '登录提示', goal: '说明失败原因', acceptanceCriteria: ['错误密码时显示中文提示'] }),
  })));
});

test.each([
  ['是这个', true],
  ['不是', false],
])('target confirmation card sends %s decision', async (button, accepted) => {
  const fallback = vi.mocked(fetch).getMockImplementation()!;
  vi.mocked(fetch).mockImplementation((input: RequestInfo | URL, init?: RequestInit) => {
    const path = String(input);
    if (path === '/api/v5/prd-sessions/prd-1' && !init?.method) {
      return jsonResponse({
        prdId: 'prd-1', systemId: 'alpha-system', conversationId: 'conv-prd-1', status: 'need_clarification', createdBy: 'admin', missingFields: ['acceptance_criteria'],
        draft: { suspectedTargets: [{ entryId: 'page-login', kind: 'page', title: '登录页', routePath: '/login', apiEndpoints: ['POST /api/login'], confidence: 0.9 }] },
      });
    }
    if (path === '/api/v5/conversations/conv-prd-1') {
      return jsonResponse({ messages: [{ messageId: 'assistant-target', conversationId: 'conv-prd-1', senderType: 'assistant', content: '请确认页面' }], pendingAssistant: false });
    }
    return fallback(input, init);
  });
  renderApp('/work-items/new/prd-1');

  expect(await screen.findByText('POST /api/login')).toBeInTheDocument();
  fireEvent.click(screen.getByRole('button', { name: button }));

  await waitFor(() => expect(fetch).toHaveBeenCalledWith('/api/v5/prd-sessions/prd-1/targets/confirm', expect.objectContaining({
    method: 'POST',
    body: JSON.stringify({ entryIds: ['page-login'], accepted }),
  })));
});

test('waiting draft exposes inline PRD confirmation in chat', async () => {
  renderApp('/work-items/new/prd-1');

  expect(await screen.findByRole('button', { name: '确认 PRD' })).toBeInTheDocument();
});

test('legacy draft editor route opens the independent creation workspace', async () => {
  const fallback = vi.mocked(fetch).getMockImplementation()!;
  vi.mocked(fetch).mockImplementation((input: RequestInfo | URL, init?: RequestInit) => {
    const path = String(input);
    if (path === '/api/v5/prd-sessions/prd-legacy') {
      return jsonResponse({ prdId: 'prd-legacy', systemId: 'alpha-system', conversationId: 'conv-legacy', status: 'need_clarification', createdBy: 'admin', draft: {}, missingFields: [] });
    }
    if (path === '/api/v5/conversations/conv-legacy') return jsonResponse({ messages: [], pendingAssistant: false });
    return fallback(input, init);
  });

  renderApp('/work-items/drafts/prd-legacy');

  expect(await screen.findByRole('heading', { name: '继续创建工作项' })).toBeInTheDocument();
  expect(screen.queryByRole('navigation', { name: '工作项中心' })).not.toBeInTheDocument();
});
