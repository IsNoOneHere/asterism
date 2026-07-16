import { fireEvent, screen, waitFor, within } from '@testing-library/react';
import { beforeEach, expect, test, vi } from 'vitest';
import { jsonResponse, renderApp, resetAppTestState, setApiResponse } from './appTestHarness';

beforeEach(resetAppTestState);

test.each([
  { path: '/models', title: '模型配置', list: '模型列表', absent: 'Agent 列表' },
  { path: '/agents', title: 'Agent 配置', list: 'Agent 列表', absent: '模型列表' },
])('$path only renders its own configuration list', async ({ path, title, list, absent }) => {
  renderApp(path);

  expect(await screen.findByRole('heading', { name: title })).toBeInTheDocument();
  expect(await screen.findByRole('heading', { name: list })).toBeInTheDocument();
  expect(screen.queryByRole('heading', { name: absent })).not.toBeInTheDocument();
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
  fireEvent.click(screen.getByRole('button', { name: '新增 Agent' }));
  expect(screen.getByRole('dialog')).toHaveAttribute('open');
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
  expect(screen.getByRole('dialog')).toHaveAttribute('open');
  expect(screen.getByRole('heading', { name: '编辑 product' })).toBeInTheDocument();
  expect(screen.queryByLabelText('执行内核')).not.toBeInTheDocument();
  fireEvent.click(screen.getByRole('button', { name: '保存 Agent' }));
  await waitFor(() => expect(fetch).toHaveBeenCalledWith('/api/v5/systems/alpha-system/agents/product', expect.objectContaining({ method: 'PATCH' })));
});

test.each([
  { path: '/models', button: '删除 Claude 主模型', requestPath: '/api/v5/systems/alpha-system/model-profiles/mp-1' },
  { path: '/agents', button: '删除 frontend-dev', requestPath: '/api/v5/systems/alpha-system/agents/frontend-dev' },
])('$path asks for confirmation before deleting configuration', async ({ path, button, requestPath }) => {
  renderApp(path);
  fireEvent.click(await screen.findByRole('button', { name: button }));

  const dialog = screen.getByRole('dialog');
  expect(fetch).not.toHaveBeenCalledWith(requestPath, expect.objectContaining({ method: 'DELETE' }));
  fireEvent.click(within(dialog).getByRole('button', { name: '确认删除' }));

  await waitFor(() => expect(fetch).toHaveBeenCalledWith(requestPath, expect.objectContaining({ method: 'DELETE' })));
});

test.each([
  { path: '/models', createButton: '新增 Profile', editButton: '编辑 Claude 主模型' },
  { path: '/agents', createButton: '新增 Agent', editButton: '编辑 product' },
])('$path disables configuration changes for non-owner members', async ({ path, createButton, editButton }) => {
  setApiResponse('/api/v5/auth/me', { userId: 'reader', roles: [] });
  setApiResponse('/api/v5/systems/alpha-system/members', [
    { systemId: 'alpha-system', userId: 'reader', displayName: 'Reader', role: 'requester' },
  ]);
  renderApp(path);

  expect(await screen.findByText('当前账号在此系统中为只读成员，配置操作已禁用。')).toBeInTheDocument();
  expect(screen.getByRole('button', { name: createButton })).toBeDisabled();
  expect(screen.getByRole('button', { name: editButton })).toBeDisabled();
});

test('system admin members can manage configuration like the backend allows', async () => {
  setApiResponse('/api/v5/auth/me', { userId: 'system-admin', roles: [] });
  setApiResponse('/api/v5/systems/alpha-system/members', [
    { systemId: 'alpha-system', userId: 'system-admin', displayName: 'System Admin', role: 'admin' },
  ]);
  renderApp('/models');

  const create = await screen.findByRole('button', { name: '新增 Profile' });
  await waitFor(() => expect(create).toBeEnabled());
  expect(screen.getByRole('button', { name: '编辑 Claude 主模型' })).toBeEnabled();
  expect(screen.queryByText('当前账号在此系统中为只读成员，配置操作已禁用。')).not.toBeInTheDocument();
});

test('knowledge creation is disabled when repository configuration cannot load', async () => {
  const fallback = vi.mocked(fetch).getMockImplementation()!;
  vi.mocked(fetch).mockImplementation((input: RequestInfo | URL, init?: RequestInit) => {
    if (String(input) === '/api/v5/systems/alpha-system/git-config' && !init?.method) {
      return jsonResponse({ message: '仓库配置暂时不可用' }, false, 503);
    }
    return fallback(input, init);
  });
  renderApp('/knowledge');

  expect(await screen.findByText('仓库配置加载失败')).toBeInTheDocument();
  expect(screen.getByText('仓库配置暂时不可用')).toBeInTheDocument();
  expect(screen.getByRole('button', { name: '新增条目' })).toBeDisabled();
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

  await waitFor(() => expect(fetch).toHaveBeenCalledWith('/api/v5/systems/alpha-system/profile', expect.objectContaining({ method: 'PATCH' })));
  const call = vi.mocked(fetch).mock.calls.find(([path, init]) => path === '/api/v5/systems/alpha-system/profile' && init?.method === 'PATCH');
  const body = JSON.parse(String(call?.[1]?.body)).gitConfiguration;
  expect(body.releaseMode).toBe('gitlab');
  expect(body.repos).toHaveLength(2);
  expect(body.repos[1].gitlabProject).toBe('alpha/api');
  expect(vi.mocked(fetch).mock.calls.filter(([path, init]) => path === '/api/v5/systems/alpha-system/profile' && init?.method === 'PATCH')).toHaveLength(1);
  expect(fetch).not.toHaveBeenCalledWith('/api/v5/systems/alpha-system/git-config', expect.objectContaining({ method: 'PUT' }));
  expect(screen.queryByText('temporary-value')).not.toBeInTheDocument();
});
