import { act, fireEvent, screen, waitFor } from '@testing-library/react';
import { beforeEach, expect, test, vi } from 'vitest';
import { jsonResponse, renderApp, renderAppWithRouter, resetAppTestState, setWorkItems } from './appTestHarness';

beforeEach(resetAppTestState);

test('renders workbench navigation after auth check', async () => {
  renderApp('/work-items');

  expect(await screen.findByText('Asterism')).toBeInTheDocument();
  expect(await screen.findByText('工作项中心')).toBeInTheDocument();
  expect(await screen.findByLabelText('当前工作系统')).toBeInTheDocument();
  expect(document.querySelector('.sidebar [aria-label="当前工作系统"]')).not.toBeInTheDocument();
  expect(document.querySelectorAll('.sidebar nav svg')).toHaveLength(6);
  expect(document.querySelectorAll('.page-tabs svg')).toHaveLength(2);
});

test.each(['/work-items/drafts', '/systems', '/models', '/agents', '/memory', '/users'])('shows global system context on %s', async (path) => {
  renderApp(path);

  expect(await screen.findByLabelText('当前工作系统')).toBeInTheDocument();
});

test('system configuration uses Chinese field labels', async () => {
  renderApp('/systems');

  expect(await screen.findByLabelText('系统编号')).toBeInTheDocument();
  expect(screen.getByLabelText('代码仓库绝对路径')).toBeInTheDocument();
  expect(screen.getByLabelText('系统负责人')).toBeInTheDocument();
  expect(screen.getByLabelText(/允许修改路径/)).toBeInTheDocument();
  expect(screen.getByLabelText(/禁止修改路径/)).toBeInTheDocument();
  expect(screen.getByLabelText(/测试命令/)).toBeInTheDocument();
});

test('shows login page when auth check fails', async () => {
  vi.mocked(fetch).mockImplementationOnce(() => jsonResponse('unauthorized', false));

  renderApp('/work-items');

  expect(await screen.findByRole('heading', { name: '登录' })).toBeInTheDocument();
});

test('work item page loads selected system from systems api', async () => {
  renderApp('/work-items');

  fireEvent.change(await screen.findByLabelText('范围'), { target: { value: 'system' } });
  fireEvent.click(await screen.findByLabelText('当前工作系统'));
  fireEvent.click(await screen.findByRole('option', { name: 'Prod System' }));

  await waitFor(() => {
    expect(fetch).toHaveBeenCalledWith('/api/v5/work-items?scope=system&systemId=prod-system&sort=updated_desc', expect.anything());
  });
});

test('work item table separates id and title and shows creator', async () => {
  setWorkItems([{
    workItemId: 'WI202607114827', title: '优化工作项列表', lifecycleStatus: 'activated', approvalStatus: 'approved',
    executionAllowed: true, updatedAt: '2026-07-11T03:00:00Z', createdBy: 'admin', canControl: false, availableActions: [],
  }]);

  renderApp('/work-items');

  expect(await screen.findByRole('columnheader', { name: '工作项 ID' })).toBeInTheDocument();
  expect(screen.getByRole('columnheader', { name: '标题' })).toBeInTheDocument();
  expect(screen.getByRole('columnheader', { name: '创建人' })).toBeInTheDocument();
  expect(await screen.findByRole('link', { name: 'WI202607114827' })).toHaveAttribute('href', '/work-items/WI202607114827');
  expect(await screen.findByText('优化工作项列表')).toBeInTheDocument();
  expect(screen.getByRole('cell', { name: 'admin' })).toBeInTheDocument();
});

test('work item filters and second page survive detail return', async () => {
  setWorkItems(Array.from({ length: 21 }, (_, index) => ({
    workItemId: index === 20 ? 'wi-1' : `wi-${index + 2}`,
    title: index === 20 ? '登录页错误提示' : `工作项 ${index + 1}`,
    lifecycleStatus: 'completed', approvalStatus: 'approved', executionAllowed: false,
    updatedAt: '2026-07-11T03:00:00Z', createdBy: 'admin', canControl: false, availableActions: [],
  })));
  renderApp('/work-items');

  fireEvent.change(await screen.findByLabelText('范围'), { target: { value: 'all' } });
  fireEvent.change(screen.getByLabelText('状态'), { target: { value: 'completed' } });
  fireEvent.change(screen.getByLabelText('搜索工作项'), { target: { value: '登录' } });
  fireEvent.change(screen.getByLabelText('排序'), { target: { value: 'created_asc' } });
  fireEvent.click(await screen.findByRole('button', { name: '下一页' }));
  expect(await screen.findByText('第 2 / 2 页')).toBeInTheDocument();

  fireEvent.click(await screen.findByRole('link', { name: 'wi-1' }));
  fireEvent.click(await screen.findByRole('link', { name: '← 返回工作项中心' }));

  expect(await screen.findByLabelText('范围')).toHaveValue('all');
  expect(screen.getByLabelText('状态')).toHaveValue('completed');
  expect(screen.getByLabelText('搜索工作项')).toHaveValue('登录');
  expect(screen.getByLabelText('排序')).toHaveValue('created_asc');
  expect(await screen.findByText('第 2 / 2 页')).toBeInTheDocument();
});

test('browser back restores work item query state', async () => {
  setWorkItems([{
    workItemId: 'wi-1', title: '登录页错误提示', lifecycleStatus: 'completed', approvalStatus: 'approved',
    executionAllowed: false, updatedAt: '2026-07-11T03:00:00Z', createdBy: 'admin', canControl: false, availableActions: [],
  }]);
  const router = renderAppWithRouter('/work-items');

  fireEvent.change(await screen.findByLabelText('搜索工作项'), { target: { value: '登录' } });
  await waitFor(() => expect(router.state.location.state).toMatchObject({ workItemList: { q: '登录' } }));
  fireEvent.click(await screen.findByRole('link', { name: 'wi-1' }));
  expect(await screen.findByText('ExecutionPlanDrafted')).toBeInTheDocument();

  await act(async () => { await router.navigate(-1); });
  expect(await screen.findByLabelText('搜索工作项')).toHaveValue('登录');
});

test('work item detail shows drafted execution plan from event timeline', async () => {
  renderApp('/work-items/wi-1');

  expect(await screen.findByText('登录页错误提示')).toBeInTheDocument();
  expect(screen.queryByLabelText('当前工作系统')).not.toBeInTheDocument();
  expect(await screen.findByText('alpha-system')).toBeInTheDocument();
  expect(await screen.findByText('ExecutionPlanDrafted')).toBeInTheDocument();
  expect(await screen.findByText('执行步骤')).toBeInTheDocument();
  expect(await screen.findByText(/错误密码时显示提示/)).toBeInTheDocument();
  expect(await screen.findByText('目标文件')).toBeInTheDocument();
  expect((await screen.findAllByText('src/login.tsx')).length).toBeGreaterThan(0);
  expect(await screen.findByText('测试计划')).toBeInTheDocument();
  expect(await screen.findByText('npm test')).toBeInTheDocument();
  expect(await screen.findByText('风险')).toBeInTheDocument();
  expect(await screen.findByText('登录态回归')).toBeInTheDocument();
  expect(screen.queryByText(/"steps"/)).not.toBeInTheDocument();
});

test('work item detail shows execution provider and token summary', async () => {
  renderApp('/work-items/wi-1');

  expect(await screen.findByText('ModificationCompleted')).toBeInTheDocument();
  expect((await screen.findAllByText('claude_sdk')).length).toBeGreaterThan(0);
  expect(await screen.findByText('输入 320 / 输出 80')).toBeInTheDocument();
  expect(await screen.findByText('代码 diff')).toBeInTheDocument();
  expect(await screen.findByText(/diff --git a\/src\/login.tsx/)).toBeInTheDocument();
});

test('work item detail shows agent stage handoff metadata', async () => {
  renderApp('/work-items/wi-1');

  expect(await screen.findByText('AgentStageCompleted')).toBeInTheDocument();
  expect(await screen.findByText('frontend')).toBeInTheDocument();
  expect(await screen.findByText('前端修改完成')).toBeInTheDocument();
  expect((await screen.findAllByText('src/login.tsx')).length).toBeGreaterThan(0);
});

test('memory approve moves candidate out of pending tab', async () => {
  renderApp('/memory');

  expect(await screen.findByText(/保留登录页样式/)).toBeInTheDocument();
  fireEvent.click(screen.getByRole('button', { name: '批准' }));

  await waitFor(() => {
    expect(screen.queryByText(/保留登录页样式/)).not.toBeInTheDocument();
  });
});
