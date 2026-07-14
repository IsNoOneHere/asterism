import { fireEvent, screen, waitFor } from '@testing-library/react';
import { beforeEach, expect, test, vi } from 'vitest';
import { renderApp, resetAppTestState } from './appTestHarness';

beforeEach(resetAppTestState);

test('model and agent configuration share one page and data source', async () => {
  renderApp('/models');

  expect(await screen.findByRole('heading', { name: '模型配置' })).toBeInTheDocument();
  expect(screen.getByRole('heading', { name: '模型连接' })).toBeInTheDocument();
  expect(screen.getByRole('heading', { name: '阶段默认模型' })).toBeInTheDocument();
  expect((await screen.findAllByText('Claude 主模型')).length).toBeGreaterThan(0);
  expect(screen.getByText('Key 已配置')).toBeInTheDocument();
  expect(screen.queryByRole('heading', { name: '代码 Agent' })).not.toBeInTheDocument();
  fireEvent.click(screen.getByRole('button', { name: 'Agent 配置' }));
  expect(await screen.findByRole('heading', { name: '代码 Agent' })).toBeInTheDocument();
});

test('model profile edit opens a centered dialog instead of an inline form', async () => {
  renderApp('/models');
  await screen.findAllByText('Claude 主模型');
  fireEvent.click(screen.getByRole('button', { name: '编辑 Claude 主模型' }));

  expect(screen.getByRole('dialog')).toHaveAttribute('open');
  expect(screen.getByRole('heading', { name: '编辑 Model Profile' })).toBeInTheDocument();
  expect(screen.getByLabelText('Profile 名称')).toHaveValue('Claude 主模型');
  expect(screen.getByLabelText('API Key')).toHaveAttribute('placeholder', '留空保留现有 Key');
});

test('model profile can be added without ever rendering its key', async () => {
  renderApp('/models');
  await screen.findAllByText('Claude 主模型');
  fireEvent.click(screen.getByRole('button', { name: '新增 Profile' }));
  expect(screen.getByRole('dialog')).toBeInTheDocument();
  fireEvent.change(screen.getByLabelText('Profile 名称'), { target: { value: 'OpenAI 兼容模型' } });
  fireEvent.change(screen.getByLabelText('模型名称'), { target: { value: 'gpt-4.1' } });
  fireEvent.change(screen.getByLabelText('API Key'), { target: { value: 'new-secret' } });
  fireEvent.click(screen.getByRole('button', { name: '保存 Profile' }));
  expect((await screen.findAllByText('OpenAI 兼容模型')).length).toBeGreaterThan(0);
  expect(screen.queryByText('new-secret')).not.toBeInTheDocument();
  expect(fetch).toHaveBeenCalledWith('/api/v5/systems/alpha-system/model-profiles', expect.objectContaining({ method: 'POST' }));
});

test('agent role can select deepagents profile and path scope', async () => {
  renderApp('/agents');
  fireEvent.click(await screen.findByRole('button', { name: 'Agent 配置' }));
  await screen.findAllByText('前端 Agent');
  expect(screen.getByRole('heading', { name: '代码 Agent' })).toBeInTheDocument();
  expect(screen.getByLabelText('默认 Agent')).toHaveValue('role-1');
  fireEvent.change(screen.getByLabelText('Agent 名称'), { target: { value: '后端 Agent' } });
  fireEvent.change(screen.getByLabelText('执行内核'), { target: { value: 'deepagents' } });
  fireEvent.change(screen.getByLabelText('Model Profile'), { target: { value: 'mp-1' } });
  fireEvent.change(screen.getByLabelText(/Path Scope/), { target: { value: 'api\ndb' } });
  fireEvent.click(screen.getByRole('button', { name: '添加 Agent' }));

  expect((await screen.findAllByText('后端 Agent')).length).toBeGreaterThan(0);
  const call = vi.mocked(fetch).mock.calls.find(([path]) => path === '/api/v5/systems/alpha-system/agent-roles');
  expect(JSON.parse(String(call?.[1]?.body)).pathScope).toEqual(['api', 'db']);
});

test('agent execution policy switches between single and planner selection', async () => {
  renderApp('/agents');
  fireEvent.click(await screen.findByRole('button', { name: 'Agent 配置' }));
  await screen.findAllByText('前端 Agent');

  fireEvent.click(screen.getByRole('radio', { name: /Planner 选择/ }));

  await waitFor(() => expect(fetch).toHaveBeenCalledWith(
    '/api/v5/systems/alpha-system/execution-policy',
    expect.objectContaining({ method: 'PATCH', body: JSON.stringify({ mode: 'planner_select', defaultRoleId: 'role-1' }) }),
  ));
});
