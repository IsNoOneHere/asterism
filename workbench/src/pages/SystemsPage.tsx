import { zodResolver } from '@hookform/resolvers/zod';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { useEffect, useMemo, useState } from 'react';
import { useForm } from 'react-hook-form';
import { z } from 'zod';
import { api, SystemProfile } from '../api/client';
import { Pagination, usePagination } from '../components/Pagination';
import { useCurrentSystem } from '../SystemContext';

const schema = z.object({
  systemId: z.string().min(1),
  name: z.string().min(1),
  description: z.string(),
  repoPath: z.string().min(1),
  ownerUserId: z.string().min(1),
  allowedPaths: z.string(),
  forbiddenPaths: z.string(),
  testCommands: z.string(),
});

type FormValue = z.infer<typeof schema>;

const emptyForm: FormValue = {
  systemId: '',
  name: '',
  description: '',
  repoPath: '',
  ownerUserId: '',
  allowedPaths: '',
  forbiddenPaths: '',
  testCommands: '',
};

export function SystemsPage() {
  const queryClient = useQueryClient();
  const users = useQuery({ queryKey: ['users'], queryFn: api.users, retry: false });
  const { systems, systemId, setSystemId } = useCurrentSystem();
  const [mode, setMode] = useState<'edit' | 'new'>('edit');
  const [message, setMessage] = useState('');
  const selected = useMemo(() => systems.find((system) => system.systemId === systemId), [systems, systemId]);
  const enabledUsers = (users.data ?? []).filter((user) => user.enabled);
  const pagination = usePagination(systems, systems.map((system) => system.systemId).join(':'));
  const form = useForm<FormValue>({
    resolver: zodResolver(schema),
    defaultValues: emptyForm,
  });

  useEffect(() => {
    if (mode === 'edit' && selected) {
      form.reset(fromSystem(selected));
    }
  }, [form, mode, selected]);

  const save = useMutation({
    mutationFn: (value: FormValue) => mode === 'new'
      ? api.createSystem(toRequest(value))
      : api.updateSystemProfile(value.systemId, toRequest(value)),
    onSuccess: (saved) => {
      console.info('v5 workbench 保存系统', { systemId: saved.systemId });
      setMessage('保存成功');
      setMode('edit');
      setSystemId(saved.systemId);
      queryClient.invalidateQueries({ queryKey: ['systems'] });
    },
  });

  function newSystem() {
    setMode('new');
    setMessage('');
    form.reset(emptyForm);
  }

  return (
    <section className="split wide-left">
      <form className="panel" onSubmit={form.handleSubmit((value) => save.mutate(value))}>
        <div className="page-head">
          <h1>系统配置</h1>
          <button type="button" onClick={newSystem}>新建系统</button>
        </div>
        <label>系统编号<input {...form.register('systemId')} readOnly={mode === 'edit'} /></label>
        <label>名称<input {...form.register('name')} /></label>
        <label>描述<textarea rows={3} {...form.register('description')} /></label>
        <label>代码仓库绝对路径<input {...form.register('repoPath')} /></label>
        <label>
          系统负责人
          <select {...form.register('ownerUserId')}>
            <option value="">请选择用户</option>
            {enabledUsers.map((user) => (
              <option key={user.userId} value={user.userId}>{user.displayName || user.userId}</option>
            ))}
          </select>
        </label>
        <label>
          允许修改路径
          <span className="field-note">限制代码变更范围；每行一条相对于仓库根目录的路径前缀。</span>
          <textarea rows={4} {...form.register('allowedPaths')} />
        </label>
        <label>
          禁止修改路径
          <span className="field-note">每行一条相对于仓库根目录的路径前缀；命中后直接拒绝应用代码变更。</span>
          <textarea rows={4} {...form.register('forbiddenPaths')} />
        </label>
        <label>
          测试命令
          <span className="field-note">代码变更应用后依次自动执行，用于判断验证是否通过。</span>
          <textarea rows={4} {...form.register('testCommands')} />
        </label>
        <button type="submit" disabled={save.isPending}>保存</button>
        {message && <div className="success-text">{message}</div>}
        {save.isError && <div className="error-text">{String(save.error)}</div>}
      </form>
      <div className="panel">
        <h2>系统列表</h2>
        {pagination.pageItems.map((system) => (
          <div className="list-item action-item" key={system.systemId}>
            <div>
              <strong>{system.name}</strong>
              <span>{system.systemId} · {system.repoPath}</span>
            </div>
            <button type="button" onClick={() => { setMode('edit'); setSystemId(system.systemId); }}>
              编辑
            </button>
          </div>
        ))}
        <Pagination total={systems.length} page={pagination.page} totalPages={pagination.totalPages} onPageChange={pagination.setPage} />
      </div>
    </section>
  );
}

function fromSystem(system: SystemProfile): FormValue {
  return {
    systemId: system.systemId,
    name: system.name,
    description: system.description || '',
    repoPath: system.repoPath,
    ownerUserId: system.ownerUserId,
    allowedPaths: parseArray(system.allowedPaths).join('\n'),
    forbiddenPaths: parseArray(system.forbiddenPaths).join('\n'),
    testCommands: parseArray(system.testCommands).join('\n'),
  };
}

function toRequest(value: FormValue) {
  return {
    ...value,
    allowedPaths: lines(value.allowedPaths),
    forbiddenPaths: lines(value.forbiddenPaths),
    testCommands: lines(value.testCommands),
  };
}

function parseArray(value?: string) {
  try {
    const parsed = value ? JSON.parse(value) : [];
    return Array.isArray(parsed) ? parsed.map(String) : [];
  } catch {
    return [];
  }
}

function lines(value: string) {
  return value.split('\n').map((line) => line.trim()).filter(Boolean);
}
