import { fireEvent, screen, waitFor } from '@testing-library/react';
import { beforeEach, expect, test, vi } from 'vitest';
import { renderApp, resetAppTestState } from './appTestHarness';

beforeEach(resetAppTestState);

test('model and agent configuration share one page and data source', async () => {
  renderApp('/models');

  expect(await screen.findByRole('heading', { name: 'Agent / 模型配置' })).toBeInTheDocument();
  expect(screen.getByRole('heading', { name: '模型列表' })).toBeInTheDocument();
  expect(screen.getByRole('heading', { name: 'Agent 列表' })).toBeInTheDocument();
  expect((await screen.findAllByText('Claude 主模型')).length).toBeGreaterThan(0);
  expect(screen.getByText('Key 已配置')).toBeInTheDocument();
  expect(screen.getByText('内置 · PRD 对话')).toBeInTheDocument();
  expect(screen.queryByRole('radio')).not.toBeInTheDocument();
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

test('custom agent can select deepagents profile and path scope', async () => {
  renderApp('/agents');
  await screen.findAllByText('frontend-dev');
  expect(screen.getByRole('heading', { name: 'Agent 列表' })).toBeInTheDocument();
  fireEvent.change(screen.getByLabelText('Agent 名称'), { target: { value: 'backend-dev' } });
  fireEvent.change(screen.getByLabelText('执行内核'), { target: { value: 'deepagents' } });
  fireEvent.change(screen.getByLabelText('Model Profile'), { target: { value: 'mp-1' } });
  fireEvent.change(screen.getByLabelText(/Path Scope/), { target: { value: 'api\ndb' } });
  fireEvent.click(screen.getByRole('button', { name: '添加 Agent' }));

  expect((await screen.findAllByText('backend-dev')).length).toBeGreaterThan(0);
  const call = vi.mocked(fetch).mock.calls.find(([path]) => path === '/api/v5/systems/alpha-system/agents');
  expect(JSON.parse(String(call?.[1]?.body)).pathScope).toEqual(['api', 'db']);
});

test('builtin product only edits its profile and cannot be deleted', async () => {
  renderApp('/agents');
  await screen.findAllByText('product');
  expect(screen.queryByRole('button', { name: '删除 product' })).not.toBeInTheDocument();
  fireEvent.click(screen.getByRole('button', { name: '编辑 product' }));
  expect(screen.getByRole('heading', { name: '编辑 product' })).toBeInTheDocument();
  expect(screen.queryByLabelText('执行内核')).not.toBeInTheDocument();
  fireEvent.click(screen.getByRole('button', { name: '保存 Agent' }));
  await waitFor(() => expect(fetch).toHaveBeenCalledWith('/api/v5/systems/alpha-system/agents/product', expect.objectContaining({ method: 'PATCH' })));
});

test('git publishing configuration edits multiple repositories without rendering token', async () => {
  renderApp('/systems');
  fireEvent.click((await screen.findAllByRole('button', { name: '编辑' }))[0]);

  const dialog = await screen.findByRole('dialog');
  expect(dialog).toHaveTextContent('Git 与发布');
  expect(screen.getByLabelText('GitLab Token')).toHaveAttribute('placeholder', '留空保留现有 Token');
  fireEvent.change(screen.getByLabelText('发布模式'), { target: { value: 'gitlab' } });
  fireEvent.change(screen.getByLabelText('GitLab Token'), { target: { value: 'temporary-value' } });
  fireEvent.click(screen.getByRole('button', { name: '添加仓库' }));
  fireEvent.change(screen.getByLabelText('仓库 2 GitLab Project'), { target: { value: 'alpha/api' } });
  fireEvent.change(screen.getByLabelText('仓库 2 克隆方式'), { target: { value: 'gitlab' } });
  fireEvent.click(screen.getByRole('button', { name: '保存系统' }));

  await waitFor(() => expect(fetch).toHaveBeenCalledWith('/api/v5/systems/alpha-system/git-config', expect.objectContaining({ method: 'PUT' })));
  const call = vi.mocked(fetch).mock.calls.find(([path, init]) => path === '/api/v5/systems/alpha-system/git-config' && init?.method === 'PUT');
  const body = JSON.parse(String(call?.[1]?.body));
  expect(body.releaseMode).toBe('gitlab');
  expect(body.repos).toHaveLength(2);
  expect(body.repos[1].gitlabProject).toBe('alpha/api');
  expect(screen.queryByText('temporary-value')).not.toBeInTheDocument();
});
