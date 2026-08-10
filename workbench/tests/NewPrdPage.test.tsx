import { act, fireEvent, render, screen, waitFor, within } from '@testing-library/react';
import { beforeEach, expect, test, vi } from 'vitest';
import { ExecutionProgressBubble, extractClarification } from '../src/pages/NewPrdPage';
import { jsonResponse, renderApp, renderAppWithRouter, resetAppTestState } from './appTestHarness';

beforeEach(() => {
  resetAppTestState();
  URL.createObjectURL = vi.fn((file: File) => `blob:${file.name}`);
  URL.revokeObjectURL = vi.fn();
});

function executionProjection(status: string, overrides: Record<string, unknown> = {}) {
  return {
    executionId: 'exec-1', prdId: 'prd-1', status,
    workflowId: 'product-agent-exec-1', inputMessageId: 'msg-1', contextBundleId: 'bundle-1',
    stage: status, attempt: 1, ...overrides,
  };
}

test('execution progress bubble announces the projected stage', () => {
  render(<ExecutionProgressBubble stage="ANALYZING_REQUIREMENT" />);

  expect(screen.getByRole('status')).toHaveTextContent('正在分析需求与相关图片…');
});

test('send start result alone keeps polling and disables composer before projection catches up', async () => {
  const fallback = vi.mocked(fetch).getMockImplementation()!;
  let conversationCalls = 0;
  vi.mocked(fetch).mockImplementation((input: RequestInfo | URL, init?: RequestInit) => {
    const path = String(input);
    if (path === '/api/v5/systems/alpha-system/prd/messages' && init?.method === 'POST') {
      return jsonResponse({
        executionId: 'exec-local-created', prdId: 'prd-local-created',
        conversationId: 'conv-local-created', status: 'CREATED',
      });
    }
    if (path === '/api/v5/prd-sessions/prd-local-created') return jsonResponse({
      prdId: 'prd-local-created', systemId: 'alpha-system', conversationId: 'conv-local-created',
      status: 'need_clarification', createdBy: 'admin', draft: {}, missingFields: ['title'],
    });
    if (path === '/api/v5/conversations/conv-local-created') {
      conversationCalls += 1;
      return jsonResponse({ messages: [], activeExecution: null, latestExecution: null });
    }
    return fallback(input, init);
  });

  renderApp('/work-items/new');
  fireEvent.change(await screen.findByLabelText('需求描述'), { target: { value: '仅返回 executionId 的发送' } });
  fireEvent.click(screen.getByRole('button', { name: '开始分析' }));

  expect(await screen.findByText('请求已进入队列，正在准备分析…')).toBeInTheDocument();
  expect(screen.getByLabelText('需求描述')).toBeDisabled();
  await waitFor(() => expect(conversationCalls).toBeGreaterThanOrEqual(2), { timeout: 3_000 });
});

test('page refresh restores running execution and polls projected stage', async () => {
  const fallback = vi.mocked(fetch).getMockImplementation()!;
  let conversationCalls = 0;
  vi.mocked(fetch).mockImplementation((input: RequestInfo | URL, init?: RequestInit) => {
    const path = String(input);
    if (path === '/api/v5/prd-sessions/prd-running') return jsonResponse({
      prdId: 'prd-running', systemId: 'alpha-system', conversationId: 'conv-running',
      status: 'need_clarification', createdBy: 'admin', draft: {}, missingFields: ['goal'],
    });
    if (path === '/api/v5/conversations/conv-running') {
      conversationCalls += 1;
      const running = executionProjection('RUNNING', {
        executionId: 'exec-running', prdId: 'prd-running', stage: 'ANALYZING_REQUIREMENT',
      });
      return jsonResponse({ messages: [], activeExecution: running, latestExecution: running });
    }
    return fallback(input, init);
  });

  renderApp('/work-items/new/prd-running');

  expect(await screen.findByText('正在分析需求与相关图片…')).toBeInTheDocument();
  expect(screen.getByLabelText('需求描述')).toBeDisabled();
  await waitFor(() => expect(conversationCalls).toBeGreaterThanOrEqual(2), { timeout: 3_000 });
});

test('slow completion clears local execution and refreshes session and conversation once', async () => {
  const fallback = vi.mocked(fetch).getMockImplementation()!;
  let conversationCalls = 0;
  let sessionCalls = 0;
  vi.mocked(fetch).mockImplementation((input: RequestInfo | URL, init?: RequestInit) => {
    const path = String(input);
    if (path === '/api/v5/prd-sessions/prd-slow') {
      sessionCalls += 1;
      const completed = conversationCalls >= 2;
      return jsonResponse({
        prdId: 'prd-slow', systemId: 'alpha-system', conversationId: 'conv-slow',
        status: completed ? 'waiting_user_confirm' : 'need_clarification', createdBy: 'admin',
        draft: completed
          ? { title: '慢任务', goal: '等待完成', acceptanceCriteria: ['完成后刷新'] }
          : {},
        missingFields: completed ? [] : ['title', 'goal', 'acceptance_criteria'],
      });
    }
    if (path === '/api/v5/conversations/conv-slow') {
      conversationCalls += 1;
      if (conversationCalls === 1) {
        const running = executionProjection('RUNNING', {
          executionId: 'exec-slow', prdId: 'prd-slow', stage: 'GENERATING_RESPONSE',
        });
        return jsonResponse({ messages: [], activeExecution: running, latestExecution: running });
      }
      return jsonResponse({
        messages: [{
          messageId: 'assistant-slow', conversationId: 'conv-slow', senderType: 'assistant',
          content: '慢任务已完成',
        }],
        activeExecution: null,
        latestExecution: executionProjection('COMPLETED', {
          executionId: 'exec-slow', prdId: 'prd-slow', stage: 'COMPLETED',
        }),
      });
    }
    return fallback(input, init);
  });

  renderApp('/work-items/new/prd-slow');
  expect(await screen.findByText('正在生成回复和需求草稿…')).toBeInTheDocument();
  expect(await screen.findByText('慢任务已完成', {}, { timeout: 3_000 })).toBeInTheDocument();
  expect(await screen.findByRole('heading', { name: '需求确认' })).toBeInTheDocument();
  expect(screen.getByLabelText('需求描述')).toBeEnabled();
  await waitFor(() => expect(sessionCalls).toBe(2));
  await waitFor(() => expect(conversationCalls).toBe(3));
});

test.each([
  ['FAILED', 'AI 分析失败'],
  ['CANCELLED', 'AI 分析已取消'],
] as const)('%s execution shows warning without fabricating assistant message', async (status, warning) => {
  const fallback = vi.mocked(fetch).getMockImplementation()!;
  vi.mocked(fetch).mockImplementation((input: RequestInfo | URL, init?: RequestInit) => {
    const path = String(input);
    if (path === `/api/v5/prd-sessions/prd-${status.toLowerCase()}`) return jsonResponse({
      prdId: `prd-${status.toLowerCase()}`, systemId: 'alpha-system',
      conversationId: `conv-${status.toLowerCase()}`, status: 'need_clarification',
      createdBy: 'admin', draft: {}, missingFields: ['goal'],
    });
    if (path === `/api/v5/conversations/conv-${status.toLowerCase()}`) return jsonResponse({
      messages: [{
        messageId: `user-${status}`, conversationId: `conv-${status.toLowerCase()}`,
        senderType: 'user', content: '保留的用户输入',
      }],
      activeExecution: null,
      latestExecution: executionProjection(status, {
        executionId: `exec-${status.toLowerCase()}`, prdId: `prd-${status.toLowerCase()}`,
        stage: status,
      }),
    });
    return fallback(input, init);
  });

  renderApp(`/work-items/new/prd-${status.toLowerCase()}`);

  expect(await screen.findByRole('alert')).toHaveTextContent(warning);
  expect(screen.getByText('保留的用户输入')).toBeInTheDocument();
  expect(screen.getByLabelText('需求描述')).toBeEnabled();
  expect(screen.queryByText('AI 生成失败，本轮未修改草稿。你可以重试或手工编辑。')).not.toBeInTheDocument();
  expect(screen.queryByText('正在理解你的回答并整理下一个问题…')).not.toBeInTheDocument();
});

test('new prd page keeps two analysis turns in the creation workspace', async () => {
  renderApp('/work-items/new');

  expect(await screen.findByRole('heading', { name: '创建工作项' })).toBeInTheDocument();
  expect(screen.queryByRole('navigation', { name: '工作项中心' })).not.toBeInTheDocument();
  expect(screen.queryByLabelText('当前工作系统')).not.toBeInTheDocument();
  expect(screen.getByRole('heading', { name: 'AI 需求分析' })).toBeInTheDocument();
  expect(screen.queryByRole('heading', { name: '你想要实现什么？' })).not.toBeInTheDocument();
  expect(screen.queryByRole('heading', { name: '需求理解（实时预览）' })).not.toBeInTheDocument();
  expect(screen.queryByLabelText('PRD 标题')).not.toBeInTheDocument();
  expect(screen.getByText('待输入')).toBeInTheDocument();
  expect(screen.getByRole('link', { name: '返回工作项中心' })).toHaveAttribute('href', '/work-items');
  const input = await screen.findByLabelText('需求描述');
  expect(await screen.findByLabelText('所属系统')).toHaveTextContent('Alpha System');
  fireEvent.change(input, { target: { value: '用户第一轮' } });
  fireEvent.click(screen.getByRole('button', { name: '开始分析' }));
  expect(await screen.findByText('助手第一轮')).toBeInTheDocument();
  expect(await screen.findByRole('heading', { name: '继续创建工作项' })).toBeInTheDocument();
  expect(screen.queryByRole('heading', { name: '需求确认' })).not.toBeInTheDocument();
  const savedUnload = new Event('beforeunload', { cancelable: true });
  window.dispatchEvent(savedUnload);
  expect(savedUnload.defaultPrevented).toBe(false);

  fireEvent.change(input, { target: { value: '用户第二轮' } });
  fireEvent.click(screen.getByRole('button', { name: '继续分析' }));

  expect(await screen.findByText('用户第一轮')).toBeInTheDocument();
  expect(await screen.findByText('助手第一轮')).toBeInTheDocument();
  expect(await screen.findByText('用户第二轮')).toBeInTheDocument();
  expect(await screen.findByText('助手第二轮')).toBeInTheDocument();
  expect(await screen.findByRole('heading', { name: '需求确认' })).toBeInTheDocument();
  expect(screen.getAllByRole('button', { name: '确认并生成工作项' })).toHaveLength(1);
});

test('pasted images use the compact upload tray and follow the three-image backend limit', async () => {
  const fallback = vi.mocked(fetch).getMockImplementation()!;
  let uploadCount = 0;
  vi.mocked(fetch).mockImplementation((input: RequestInfo | URL, init?: RequestInit) => {
    const path = String(input);
    if (path === '/api/v5/attachments' && init?.method === 'POST') {
      uploadCount += 1;
      return jsonResponse({ attachmentId: `attachment-${uploadCount}` });
    }
    return fallback(input, init);
  });
  renderApp('/work-items/new');
  const input = await screen.findByLabelText('需求描述');
  const images = Array.from({ length: 8 }, (_, index) =>
    new File([`image-${index}`], `image-${index + 1}.png`, { type: 'image/png' }));

  fireEvent.paste(input, { clipboardData: { files: images } });

  expect(await screen.findByLabelText('已上传图片 3 张')).toBeInTheDocument();
  await waitFor(() => expect(screen.getByText('本次最多 3 张图片')).toBeInTheDocument());
  expect(screen.getAllByRole('button', { name: /^预览 image-/ })).toHaveLength(3);
  expect(uploadCount).toBe(3);
  fireEvent.click(screen.getByRole('button', { name: '预览 image-1.png' }));

  const dialog = screen.getByRole('dialog', { name: '图片预览' });
  expect(dialog).toHaveAttribute('open');
  expect(within(dialog).getByRole('img', { name: '需求截图预览' })).toHaveAttribute('src', 'blob:image-1.png');
  fireEvent.click(within(dialog).getByRole('button', { name: '关闭预览' }));
  expect(dialog).not.toHaveAttribute('open');
});

test('dragging images uses the same upload tray and backend limit', async () => {
  const fallback = vi.mocked(fetch).getMockImplementation()!;
  let uploadCount = 0;
  vi.mocked(fetch).mockImplementation((input: RequestInfo | URL, init?: RequestInit) => {
    if (String(input) === '/api/v5/attachments' && init?.method === 'POST') {
      uploadCount += 1;
      return jsonResponse({ attachmentId: `drop-${uploadCount}` });
    }
    return fallback(input, init);
  });
  renderApp('/work-items/new');
  const input = await screen.findByLabelText('需求描述');
  const composer = input.closest('form')!;
  const images = Array.from({ length: 6 }, (_, index) =>
    new File([`drop-${index}`], `drop-${index + 1}.png`, { type: 'image/png' }));

  fireEvent.drop(composer, { dataTransfer: { files: images } });

  expect(await screen.findByLabelText('已上传图片 3 张')).toBeInTheDocument();
  await waitFor(() => expect(uploadCount).toBe(3));
  expect(screen.getAllByRole('button', { name: /^预览 drop-/ })).toHaveLength(3);
});

test('clarification parser accepts one question, splits numbered questions and preserves ordinary markdown', () => {
  expect(extractClarification('我先确认一个问题：\n这个能力主要解决什么业务场景？')).toEqual({
    intro: '我先确认一个问题：',
    questions: ['这个能力主要解决什么业务场景？'],
    suggestions: {},
  });
  expect(extractClarification('先确认：\n**业务方如何判断需求已经完成？**')).toEqual({
    intro: '先确认：',
    questions: ['业务方如何判断需求已经完成？'],
    suggestions: {},
  });
  expect(extractClarification('我还需要确认：\n1. 目标用户是谁？\n2. 失败时如何提示？')).toEqual({
    intro: '我还需要确认：',
    questions: ['目标用户是谁？', '失败时如何提示？'],
    suggestions: {},
  });
  expect(extractClarification('请补充：\n- 哪些用户需要看到结果？\n- 这次需求不包含哪些场景？')).toEqual({
    intro: '请补充：',
    questions: ['哪些用户需要看到结果？', '这次需求不包含哪些场景？'],
    suggestions: {},
  });
  expect(extractClarification('## 建议\n先完成登录页，再补充验收标准。')).toEqual({
    intro: '## 建议\n先完成登录页，再补充验收标准。',
    questions: [],
    suggestions: {},
  });
  expect(extractClarification('1. 谁来使用？\n推荐答案：管理员和系统运维人员。')).toEqual({
    intro: '',
    questions: ['谁来使用？'],
    suggestions: { '谁来使用？': '管理员和系统运维人员。' },
  });
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
  await screen.findByText('草稿 prd-1');
  fireEvent.change(await screen.findByLabelText('需求描述'), { target: { value: '草稿里尚未发送的补充' } });

  act(() => { void router.navigate(-1); });

  const dialog = await screen.findByRole('dialog');
  expect(dialog).toHaveTextContent('离开当前页面？');
  expect(screen.getByRole('heading', { name: '继续创建工作项' })).toBeInTheDocument();
});

test('draft summary is read-only and does not expose manual title editing', async () => {
  renderApp('/work-items/new/prd-1');

  expect(await screen.findByRole('heading', { name: '需求确认' })).toBeInTheDocument();
  expect(screen.queryByLabelText('PRD 标题')).not.toBeInTheDocument();
  expect(screen.queryByRole('button', { name: '保存草稿' })).not.toBeInTheDocument();
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
        { prdId: 'prd-pending', systemId: 'alpha-system', conversationId: 'conv-pending', title: '待完善需求', status: 'need_clarification', createdBy: 'admin', canDelete: true, draft: {}, missingFields: [] },
        { prdId: 'prd-imported', systemId: 'alpha-system', conversationId: 'conv-imported', workItemId: 'wi-imported', title: '导入待输入需求', status: 'waiting_input', createdBy: 'admin', canDelete: true, draft: {}, missingFields: [] },
        { prdId: 'prd-finished', systemId: 'alpha-system', conversationId: 'conv-finished', workItemId: 'wi-finished', title: '已生成需求', status: 'confirmed', createdBy: 'admin', canDelete: true, draft: {}, missingFields: [] },
      ]);
    }
    return fallback(input, init);
  });

  renderApp('/work-items/drafts');

  expect(await screen.findByText('待完善需求')).toBeInTheDocument();
  expect(screen.getAllByRole('link', { name: '继续完善' })).toHaveLength(2);
  expect(screen.getByText('导入待输入需求').closest('article')).toHaveTextContent('继续完善');
  expect(screen.getByRole('button', { name: '删除草稿 待完善需求' })).toBeEnabled();
  expect(screen.queryByText('已生成需求')).not.toBeInTheDocument();
  fireEvent.click(screen.getByRole('button', { name: '全部记录' }));
  expect(await screen.findByText('已生成需求')).toBeInTheDocument();
  expect(screen.getByRole('link', { name: '查看工作项' })).toHaveAttribute('href', '/work-items/wi-finished');
  expect(screen.getByRole('button', { name: '删除草稿 已生成需求' })).toBeEnabled();
});

test('imported waiting input stays available for conversation even with a reserved work item id', async () => {
  const fallback = vi.mocked(fetch).getMockImplementation()!;
  vi.mocked(fetch).mockImplementation((input: RequestInfo | URL, init?: RequestInit) => {
    const path = String(input);
    if (path === '/api/v5/prd-sessions/prd-imported') {
      return jsonResponse({
        prdId: 'prd-imported', systemId: 'alpha-system', conversationId: 'conv-imported', workItemId: 'wi-imported',
        title: '导入待输入需求', goal: '补全验收标准', status: 'waiting_input', createdBy: 'admin',
        draft: { title: '导入待输入需求', goal: '补全验收标准', acceptanceCriteria: [] }, missingFields: [],
      });
    }
    if (path === '/api/v5/conversations/conv-imported') return jsonResponse({ messages: [], activeExecution: null, latestExecution: null });
    return fallback(input, init);
  });

  renderApp('/work-items/new/prd-imported');

  expect(await screen.findByLabelText('需求描述')).toBeEnabled();
  expect(screen.queryByLabelText('PRD 标题')).not.toBeInTheDocument();
  expect(screen.queryByRole('heading', { name: '需求确认' })).not.toBeInTheDocument();
  expect(screen.queryByRole('heading', { name: '工作项已生成' })).not.toBeInTheDocument();
});

test('failed model turn keeps the conversation open for recovery', async () => {
  const fallback = vi.mocked(fetch).getMockImplementation()!;
  vi.mocked(fetch).mockImplementation((input: RequestInfo | URL, init?: RequestInit) => {
    const path = String(input);
    if (path === '/api/v5/prd-sessions/prd-failed') {
      return jsonResponse({
        prdId: 'prd-failed', systemId: 'alpha-system', conversationId: 'conv-failed',
        title: '登录提示', goal: '明确展示错误', status: 'turn_failed', createdBy: 'admin',
        draft: { title: '登录提示', goal: '明确展示错误', acceptanceCriteria: [] },
        missingFields: ['acceptance_criteria'],
      });
    }
    if (path === '/api/v5/conversations/conv-failed') return jsonResponse({
      messages: [{
        messageId: 'failed-message', conversationId: 'conv-failed', senderType: 'assistant',
        content: 'AI 生成失败，本轮未修改草稿。你可以重试或手工编辑。',
      }],
      activeExecution: null,
      latestExecution: null,
    });
    return fallback(input, init);
  });

  renderApp('/work-items/new/prd-failed');

  expect(await screen.findByText('AI 生成失败')).toBeInTheDocument();
  expect(screen.getByLabelText('需求描述')).toBeEnabled();
  expect(screen.queryByLabelText('PRD 标题')).not.toBeInTheDocument();
  expect(await screen.findByText('AI 生成失败，本轮未修改草稿。你可以重试或手工编辑。')).toBeInTheDocument();
});

test('draft deletion requires confirmation and sends DELETE', async () => {
  const fallback = vi.mocked(fetch).getMockImplementation()!;
  let deleted = false;
  vi.mocked(fetch).mockImplementation((input: RequestInfo | URL, init?: RequestInit) => {
    const path = String(input);
    if (path === '/api/v5/prd-sessions/prd-delete' && init?.method === 'DELETE') {
      deleted = true;
      return jsonResponse(undefined);
    }
    if (path.startsWith('/api/v5/prd-sessions?')) return jsonResponse(deleted ? [] : [{
      prdId: 'prd-delete', systemId: 'alpha-system', conversationId: 'conv-delete', title: '待删除草稿',
      status: 'need_clarification', createdBy: 'admin', canDelete: true, draft: {}, missingFields: [],
    }]);
    return fallback(input, init);
  });
  renderApp('/work-items/drafts');

  fireEvent.click(await screen.findByRole('button', { name: '删除草稿 待删除草稿' }));
  expect(fetch).not.toHaveBeenCalledWith('/api/v5/prd-sessions/prd-delete', expect.objectContaining({ method: 'DELETE' }));
  fireEvent.click(within(screen.getByRole('dialog')).getByRole('button', { name: '删除草稿' }));

  await waitFor(() => expect(fetch).toHaveBeenCalledWith('/api/v5/prd-sessions/prd-delete', expect.objectContaining({ method: 'DELETE' })));
  expect(await screen.findByText('暂无待完善草稿。')).toBeInTheDocument();
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
      return jsonResponse({ messages: [{ messageId: 'resume-message', conversationId: 'conv-resume', senderType: 'assistant', content: '请补充验收标准' }], activeExecution: null, latestExecution: null });
    }
    return fallback(input, init);
  });

  renderApp('/work-items/new/prd-resume');

  expect(await screen.findByRole('heading', { name: '继续创建工作项' })).toBeInTheDocument();
  expect(screen.queryByRole('heading', { name: '工作项中心' })).not.toBeInTheDocument();
  expect(screen.getByRole('heading', { name: 'AI 需求分析' })).toBeInTheDocument();
  expect(screen.getByRole('link', { name: '返回需求草稿' })).toHaveAttribute('href', '/work-items/drafts');
  expect(await screen.findByText('请补充验收标准')).toBeInTheDocument();
  expect(screen.getByLabelText('所属系统')).toBeDisabled();
  expect(screen.getByLabelText('所属系统')).toHaveTextContent('Prod System');
  await waitFor(() => expect(localStorage.getItem('asterism-system')).toBe('prod-system'));
  expect(screen.queryByRole('heading', { name: '需求记录' })).not.toBeInTheDocument();
});

test('clarification state keeps confirmation hidden and guides the next answer', async () => {
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

  expect(await screen.findByText('AI 建议补充：验收标准')).toBeInTheDocument();
  expect(screen.queryByRole('heading', { name: '需求确认' })).not.toBeInTheDocument();
  expect(screen.queryByRole('button', { name: '保存草稿' })).not.toBeInTheDocument();
});

test('confirmable draft shows a read-only confirmation card and related images', async () => {
  const fallback = vi.mocked(fetch).getMockImplementation()!;
  vi.mocked(fetch).mockImplementation((input: RequestInfo | URL, init?: RequestInit) => {
    const path = String(input);
    if (path === '/api/v5/prd-sessions/prd-citations') {
      return jsonResponse({
        prdId: 'prd-citations', systemId: 'alpha-system', conversationId: 'conv-citations',
        status: 'waiting_user_confirm', createdBy: 'admin', missingFields: [],
        draft: {
          title: '登录页规则', goal: '保持错误提示一致', acceptanceCriteria: ['使用中文提示', '按钮可重试'],
          citations: { title: ['MSG:msg-1'], goal: ['MEM:mem-1'], 'AC-1': ['KN:page-login'] },
        },
      });
    }
    if (path === '/api/v5/conversations/conv-citations') return jsonResponse({
      activeExecution: null,
      latestExecution: null,
      messages: [{
        messageId: 'assistant-1', conversationId: 'conv-citations', senderType: 'assistant',
        content: '草稿已生成，还想确认：\n哪些业务用户需要看到心跳结果？',
        attachmentIds: ['attachment-1', 'attachment-2'],
        contextItems: [
          { refId: 'MSG:msg-1', type: 'user_message', title: '当前用户输入', sourceRef: 'prd-citations' },
          { refId: 'MEM:mem-1', type: 'memory', title: '登录约束', sourceRef: 'work-item:wi-1' },
          { refId: 'KN:page-login', type: 'system_knowledge', title: '登录页', sourceRef: 'manual' },
        ],
      }],
    });
    return fallback(input, init);
  });

  renderApp('/work-items/new/prd-citations');

  expect(await screen.findByRole('heading', { name: '需求确认' })).toBeInTheDocument();
  expect(screen.getByText('登录页规则')).toBeInTheDocument();
  expect(screen.getByText('保持错误提示一致')).toBeInTheDocument();
  expect(screen.getByText('使用中文提示')).toBeInTheDocument();
  expect(screen.getByText('按钮可重试')).toBeInTheDocument();
  expect(await screen.findAllByRole('button', { name: /^预览相关图片/ })).toHaveLength(2);
  expect(screen.getByRole('button', { name: '预览相关图片 1' }).querySelector('img'))
    .toHaveAttribute('src', '/api/v5/attachments/attachment-1');
  expect(screen.queryByText('来自用户')).not.toBeInTheDocument();
  expect(screen.queryByLabelText('需求问题进度')).not.toBeInTheDocument();
  expect(screen.queryByRole('heading', { name: '哪些业务用户需要看到心跳结果？' })).not.toBeInTheDocument();
  expect(screen.getByText('哪些业务用户需要看到心跳结果？')).toBeInTheDocument();
  expect(screen.getByLabelText('需求描述')).toBeEnabled();
  expect(screen.getByRole('button', { name: '确认并生成工作项' })).toBeInTheDocument();
  expect(screen.queryByRole('button', { name: '继续补充' })).not.toBeInTheDocument();
});

test('generated work item replaces the draft editor with focused follow-up actions', async () => {
  const fallback = vi.mocked(fetch).getMockImplementation()!;
  vi.mocked(fetch).mockImplementation((input: RequestInfo | URL, init?: RequestInit) => {
    const path = String(input);
    if (path === '/api/v5/prd-sessions/prd-complete') {
      return jsonResponse({
        prdId: 'prd-complete', systemId: 'alpha-system', conversationId: 'conv-complete', workItemId: 'wi-complete',
        title: '已生成需求', status: 'confirmed', createdBy: 'admin',
        draft: { title: '已生成需求', goal: '完成需求', acceptanceCriteria: ['通过验收'] }, missingFields: [],
      });
    }
    if (path === '/api/v5/conversations/conv-complete') return jsonResponse({ messages: [], activeExecution: null, latestExecution: null });
    return fallback(input, init);
  });

  renderApp('/work-items/new/prd-complete');

  expect(await screen.findByRole('heading', { name: '工作项创建成功' })).toBeInTheDocument();
  expect(screen.getByText('创建完成')).toBeInTheDocument();
  expect(screen.getByText('当前状态')).toBeInTheDocument();
  expect(screen.getByText(/审批通过后进入规划与执行/)).toBeInTheDocument();
  expect(screen.getByRole('link', { name: '查看工作项' })).toHaveAttribute('href', '/work-items/wi-complete');
  expect(screen.getByRole('button', { name: '创建另一项' })).toBeInTheDocument();
  expect(screen.queryByLabelText('PRD 标题')).not.toBeInTheDocument();
  expect(screen.queryByRole('button', { name: '保存草稿' })).not.toBeInTheDocument();
  expect(screen.queryByRole('button', { name: '确认并生成工作项' })).not.toBeInTheDocument();
});

test('suspected targets stay out of the streamlined PRD page', async () => {
  const fallback = vi.mocked(fetch).getMockImplementation()!;
  vi.mocked(fetch).mockImplementation((input: RequestInfo | URL, init?: RequestInit) => {
    const path = String(input);
    if (path === '/api/v5/prd-sessions/prd-1' && !init?.method) {
      return jsonResponse({
        prdId: 'prd-1', systemId: 'alpha-system', conversationId: 'conv-prd-1', status: 'need_clarification', createdBy: 'admin', missingFields: ['acceptance_criteria'],
        draft: { suspectedTargets: [{
          entryId: 'page-login', repo: 'workbench', kind: 'page', title: '登录页',
          routePath: '/login', anchorTexts: ['账号登录', '请输入密码'],
          apiEndpoints: ['POST /api/login'], codeRefs: ['src/pages/LoginPage.tsx'], confidence: 0.9,
        }] },
      });
    }
    if (path === '/api/v5/conversations/conv-prd-1') {
      return jsonResponse({ messages: [{ messageId: 'assistant-target', conversationId: 'conv-prd-1', senderType: 'assistant', content: 'AI 定位到可能相关的系统位置' }], activeExecution: null, latestExecution: null });
    }
    return fallback(input, init);
  });
  renderApp('/work-items/new/prd-1');

  expect(await screen.findByText('AI 定位到可能相关的系统位置')).toBeInTheDocument();
  expect(screen.queryByText('AI 定位线索')).not.toBeInTheDocument();
  expect(screen.queryByText('/login')).not.toBeInTheDocument();
  expect(screen.queryByText('POST /api/login')).not.toBeInTheDocument();
  expect(screen.queryByRole('button', { name: '是这个' })).not.toBeInTheDocument();
  expect(screen.queryByRole('button', { name: '不是' })).not.toBeInTheDocument();
});

test('waiting draft exposes only one final confirmation action', async () => {
  renderApp('/work-items/new/prd-1');

  expect(await screen.findAllByRole('button', { name: '确认并生成工作项' })).toHaveLength(1);
  expect(screen.queryByRole('button', { name: '确认 PRD' })).not.toBeInTheDocument();
});

test('multiple clarification questions keep the full queue while focusing the first unanswered item', async () => {
  const fallback = vi.mocked(fetch).getMockImplementation()!;
  vi.mocked(fetch).mockImplementation((input: RequestInfo | URL, init?: RequestInit) => {
    const path = String(input);
    if (path === '/api/v5/prd-sessions/prd-questions') return jsonResponse({
      prdId: 'prd-questions', systemId: 'alpha-system', conversationId: 'conv-questions',
      status: 'need_clarification', createdBy: 'admin', draft: {}, missingFields: [],
    });
    if (path === '/api/v5/conversations/conv-questions') return jsonResponse({
      messages: [{
        messageId: 'assistant-questions', conversationId: 'conv-questions', senderType: 'assistant',
        content: '还需要确认：\n1. 谁来使用？\n2. 什么情况下算完成？\n3. 失败时如何提示？',
      }],
      activeExecution: null,
      latestExecution: null,
    });
    return fallback(input, init);
  });
  renderApp('/work-items/new/prd-questions');

  expect(await screen.findAllByText('谁来使用？')).toHaveLength(2);
  expect(screen.getByText('什么情况下算完成？')).toBeInTheDocument();
  expect(screen.getByText('失败时如何提示？')).toBeInTheDocument();
  expect(screen.getByLabelText('需求问题进度')).toHaveTextContent('问题 1/3');
  expect(screen.getByLabelText('需求问题进度')).toHaveTextContent('剩余 3 项');
  const input = screen.getByPlaceholderText('输入你的答案，或先采用 AI 建议再修改…');
  expect(document.querySelector('.prd-message-avatar')).not.toBeInTheDocument();
  fireEvent.change(input, { target: { value: '管理员' } });
  fireEvent.click(screen.getByRole('button', { name: '回答并进入下一题' }));

  await waitFor(() => expect(fetch).toHaveBeenCalledWith('/api/v5/systems/alpha-system/prd/messages', expect.objectContaining({
    method: 'POST',
    body: JSON.stringify({
      prdId: 'prd-questions',
      content: '针对问题「谁来使用？」的回答：\n管理员',
      attachmentIds: [],
    }),
  })));
});

test('AI recommendation is adopted into the editor but is not submitted automatically', async () => {
  const fallback = vi.mocked(fetch).getMockImplementation()!;
  vi.mocked(fetch).mockImplementation((input: RequestInfo | URL, init?: RequestInit) => {
    const path = String(input);
    if (path === '/api/v5/prd-sessions/prd-suggestion') return jsonResponse({
      prdId: 'prd-suggestion', systemId: 'alpha-system', conversationId: 'conv-suggestion',
      status: 'need_clarification', createdBy: 'admin',
      draft: { title: '心跳接口', goal: '统一服务健康检查', acceptanceCriteria: ['负责人能看到明确的服务状态'] },
      missingFields: [],
    });
    if (path === '/api/v5/conversations/conv-suggestion') return jsonResponse({
      messages: [{
        messageId: 'assistant-suggestion', conversationId: 'conv-suggestion', senderType: 'assistant',
        content: '还需要确认：\n1. 哪些用户需要看到心跳结果？\n推荐答案：值班负责人和系统负责人。\n2. 业务方如何判断需求已经完成？\n建议：负责人能看到明确、易懂的服务可用状态。',
      }],
      activeExecution: null,
      latestExecution: null,
    });
    return fallback(input, init);
  });
  renderApp('/work-items/new/prd-suggestion');

  const recommendation = await screen.findByLabelText('AI 推荐答案');
  expect(recommendation).toHaveTextContent('值班负责人和系统负责人。');
  expect(screen.getByRole('heading', { name: '需求摘要' }).closest('aside')).toHaveTextContent('心跳接口');
  const callCount = vi.mocked(fetch).mock.calls.length;
  fireEvent.click(within(recommendation).getByRole('button', { name: '采用建议' }));

  expect(screen.getByLabelText('回答 AI 当前问题')).toHaveValue('值班负责人和系统负责人。');
  expect(vi.mocked(fetch).mock.calls).toHaveLength(callCount);
  fireEvent.click(screen.getByRole('button', { name: '回答并进入下一题' }));
  await waitFor(() => expect(fetch).toHaveBeenCalledWith('/api/v5/systems/alpha-system/prd/messages', expect.objectContaining({
    method: 'POST',
    body: JSON.stringify({
      prdId: 'prd-suggestion',
      content: '针对问题「哪些用户需要看到心跳结果？」的回答：\n值班负责人和系统负责人。',
      attachmentIds: [],
    }),
  })));
});

test('skipping a question is persisted as an explicit answer protocol', async () => {
  const fallback = vi.mocked(fetch).getMockImplementation()!;
  vi.mocked(fetch).mockImplementation((input: RequestInfo | URL, init?: RequestInit) => {
    const path = String(input);
    if (path === '/api/v5/prd-sessions/prd-skip') return jsonResponse({
      prdId: 'prd-skip', systemId: 'alpha-system', conversationId: 'conv-skip',
      status: 'need_clarification', createdBy: 'admin', draft: {}, missingFields: [],
    });
    if (path === '/api/v5/conversations/conv-skip') return jsonResponse({
      messages: [{
        messageId: 'assistant-skip', conversationId: 'conv-skip', senderType: 'assistant',
        content: '1. 谁来使用？\n2. 什么情况下算完成？',
      }],
      activeExecution: null,
      latestExecution: null,
    });
    return fallback(input, init);
  });
  renderApp('/work-items/new/prd-skip');

  fireEvent.click(await screen.findByRole('button', { name: '暂不回答' }));

  await waitFor(() => expect(fetch).toHaveBeenCalledWith('/api/v5/systems/alpha-system/prd/messages', expect.objectContaining({
    method: 'POST',
    body: JSON.stringify({
      prdId: 'prd-skip',
      content: '针对问题「谁来使用？」的回答：\n暂不回答',
      attachmentIds: [],
    }),
  })));
});

test('persisted answers hide the question protocol and advance to the next AI question', async () => {
  const fallback = vi.mocked(fetch).getMockImplementation()!;
  vi.mocked(fetch).mockImplementation((input: RequestInfo | URL, init?: RequestInit) => {
    const path = String(input);
    if (path === '/api/v5/prd-sessions/prd-answered') return jsonResponse({
      prdId: 'prd-answered', systemId: 'alpha-system', conversationId: 'conv-answered',
      status: 'need_clarification', createdBy: 'admin', draft: {}, missingFields: [],
    });
    if (path === '/api/v5/conversations/conv-answered') return jsonResponse({
      messages: [
        { messageId: 'assistant-scene', conversationId: 'conv-answered', senderType: 'assistant', content: '先确认：\n这个能力主要解决什么业务场景？' },
        { messageId: 'user-scene', conversationId: 'conv-answered', senderType: 'user', content: '针对问题「这个能力主要解决什么业务场景？」的回答：\n值班人员需要快速确认服务是否可用。' },
        { messageId: 'assistant-user', conversationId: 'conv-answered', senderType: 'assistant', content: '好的，已记录。接下来确认：\n哪些用户需要看到该能力的结果？' },
      ],
      activeExecution: null,
      latestExecution: null,
    });
    return fallback(input, init);
  });
  renderApp('/work-items/new/prd-answered');

  expect(await screen.findByText('值班人员需要快速确认服务是否可用。')).toBeInTheDocument();
  expect(screen.getAllByText('你的回答')).toHaveLength(2);
  expect(screen.queryByText(/针对问题「/)).not.toBeInTheDocument();
  expect(screen.getByPlaceholderText('输入你的答案，或先采用 AI 建议再修改…')).toBeInTheDocument();
  expect(screen.getByLabelText('需求问题进度')).toHaveTextContent('问题 1/1');
  expect(screen.getByLabelText('需求问题进度')).toHaveTextContent('已回答 0 项');
  expect(screen.getByText('这个能力主要解决什么业务场景？')).toBeInTheDocument();
  expect(screen.getAllByText('哪些用户需要看到该能力的结果？')).toHaveLength(2);
});

test('legacy draft editor route opens the independent creation workspace', async () => {
  const fallback = vi.mocked(fetch).getMockImplementation()!;
  vi.mocked(fetch).mockImplementation((input: RequestInfo | URL, init?: RequestInit) => {
    const path = String(input);
    if (path === '/api/v5/prd-sessions/prd-legacy') {
      return jsonResponse({ prdId: 'prd-legacy', systemId: 'alpha-system', conversationId: 'conv-legacy', status: 'need_clarification', createdBy: 'admin', draft: {}, missingFields: [] });
    }
    if (path === '/api/v5/conversations/conv-legacy') return jsonResponse({ messages: [], activeExecution: null, latestExecution: null });
    return fallback(input, init);
  });

  renderApp('/work-items/drafts/prd-legacy');

  expect(await screen.findByRole('heading', { name: '继续创建工作项' })).toBeInTheDocument();
  expect(screen.queryByRole('navigation', { name: '工作项中心' })).not.toBeInTheDocument();
});
