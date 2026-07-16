import { zodResolver } from '@hookform/resolvers/zod';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { useRef, useState } from 'react';
import { Pencil, Plus, Trash2 } from 'lucide-react';
import { useForm } from 'react-hook-form';
import { z } from 'zod';
import { api, SystemProfile } from '../api/client';
import { ActionConfirmDialog } from '../components/ActionConfirmDialog';
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
  systemId: '', name: '', description: '', repoPath: '', ownerUserId: '',
  allowedPaths: '', forbiddenPaths: '', testCommands: '',
};

export function SystemsPage() {
  const queryClient = useQueryClient();
  const users = useQuery({ queryKey: ['users'], queryFn: api.users, retry: false });
  const { systems, systemId, setSystemId } = useCurrentSystem();
  const dialogRef = useRef<HTMLDialogElement>(null);
  const [mode, setMode] = useState<'edit' | 'new'>('new');
  const [message, setMessage] = useState('');
  const [deleteTarget, setDeleteTarget] = useState<SystemProfile | null>(null);
  const enabledUsers = (users.data ?? []).filter((user) => user.enabled);
  const pagination = usePagination(systems, systems.map((system) => system.systemId).join(':'));
  const form = useForm<FormValue>({ resolver: zodResolver(schema), defaultValues: emptyForm });

  const save = useMutation({
    mutationFn: (value: FormValue) => mode === 'new'
      ? api.createSystem(toRequest(value))
      : api.updateSystemProfile(value.systemId, toRequest(value)),
    onSuccess: (saved) => {
      console.info('v5 workbench 保存系统', { systemId: saved.systemId });
      dialogRef.current?.close();
      setMessage('系统配置保存成功');
      setSystemId(saved.systemId);
      queryClient.invalidateQueries({ queryKey: ['systems'] });
    },
  });
  const remove = useMutation({
    mutationFn: api.deleteSystem,
    onSuccess: (_, deletedId) => {
      console.info('v5 workbench 删除系统', { systemId: deletedId });
      if (deletedId === systemId) {
        setSystemId(systems.find((system) => system.systemId !== deletedId)?.systemId || '');
      }
      setMessage('系统已删除');
      queryClient.invalidateQueries({ queryKey: ['systems'] });
    },
    onSettled: () => setDeleteTarget(null),
  });

  function openEditor(system?: SystemProfile) {
    setMode(system ? 'edit' : 'new');
    setMessage('');
    form.reset(system ? fromSystem(system) : emptyForm);
    // 复用浏览器原生 dialog，避免额外弹窗依赖。
    dialogRef.current?.showModal();
  }

  function openDelete(system: SystemProfile) {
    remove.reset();
    setMessage('');
    setDeleteTarget(system);
  }

  return (
    <section className="management-page">
      <header className="page-head management-head">
        <div><h1>系统配置</h1><p>管理代码仓库、负责人和自动执行边界。</p></div>
        <button type="button" className="icon-text-button" onClick={() => openEditor()}><Plus size={16} />新建系统</button>
      </header>

      {message && <div className="success-text" role="status">{message}</div>}

      <div className="panel management-panel">
        <div className="config-section-head">
          <div><h2>系统列表</h2><p>选择系统只影响当前工作上下文，编辑配置请使用行内操作。</p></div>
          <span className="config-count">{systems.length} 个系统</span>
        </div>
        <div className="table-frame"><table className="data-table management-table system-table"><thead><tr><th>系统</th><th>代码仓库</th><th>负责人</th><th>状态</th><th>操作</th></tr></thead><tbody>
          {pagination.pageItems.map((system) => <tr key={system.systemId}>
            <td><div className="table-title"><strong>{system.name}</strong><span>{system.systemId}{system.description ? ` · ${system.description}` : ''}</span></div></td>
            <td className="path-cell">{system.repoPath}</td>
            <td>{ownerName(enabledUsers, system.ownerUserId)}</td>
            <td>{system.systemId === systemId ? <span className="status-badge success">当前系统</span> : <span className="status-badge neutral">可用</span>}</td>
            <td><div className="button-row compact-actions">
              <button type="button" className="secondary icon-text-button" onClick={() => openEditor(system)}><Pencil size={15} />编辑</button>
              <button type="button" className="danger-outline icon-text-button" aria-label={`删除系统 ${system.systemId}`} disabled={remove.isPending} onClick={() => openDelete(system)}><Trash2 size={15} />删除</button>
            </div></td>
          </tr>)}
          {!systems.length && <tr><td className="empty-cell" colSpan={5}>还没有系统配置</td></tr>}
        </tbody></table></div>
        <Pagination total={systems.length} page={pagination.page} totalPages={pagination.totalPages} onPageChange={pagination.setPage} />
      </div>

      <ActionConfirmDialog
        open={Boolean(deleteTarget)}
        title={`删除“${deleteTarget?.name || ''}”？`}
        description="删除后系统配置不可恢复；存在 PRD、工作项等业务数据时，系统会阻止删除。"
        confirmLabel="删除系统"
        pending={remove.isPending}
        onClose={() => setDeleteTarget(null)}
        onConfirm={() => deleteTarget && remove.mutate(deleteTarget.systemId)}
      />
      <ActionConfirmDialog
        open={Boolean(remove.error)}
        title="删除失败"
        description={remove.error?.message || ''}
        confirmLabel="知道了"
        alert
        showCancel={false}
        onClose={() => remove.reset()}
        onConfirm={() => remove.reset()}
      />

      <dialog ref={dialogRef} className="confirm-dialog config-dialog system-config-dialog" aria-labelledby="system-dialog-title" onClose={() => form.reset(emptyForm)}>
        <form onSubmit={form.handleSubmit((value) => save.mutate(value))}>
          <div className="config-section-head compact"><div><h2 id="system-dialog-title">{mode === 'new' ? '新建系统' : '编辑系统配置'}</h2><p>仓库范围和测试命令会直接约束后续代码执行。</p></div></div>
          <div className="config-dialog-fields system-dialog-fields">
            <label>系统编号<input {...form.register('systemId')} readOnly={mode === 'edit'} /></label>
            <label>名称<input {...form.register('name')} /></label>
            <label className="wide-field">描述<textarea rows={2} {...form.register('description')} /></label>
            <label className="wide-field">代码仓库绝对路径<input {...form.register('repoPath')} /></label>
            <label>系统负责人<select {...form.register('ownerUserId')}><option value="">请选择用户</option>{enabledUsers.map((user) => <option key={user.userId} value={user.userId}>{user.displayName || user.userId}</option>)}</select></label>
            <label className="wide-field">允许修改路径<span className="field-note">每行一条相对于仓库根目录的路径前缀。</span><textarea rows={3} {...form.register('allowedPaths')} /></label>
            <label className="wide-field">禁止修改路径<span className="field-note">命中后直接拒绝应用代码变更。</span><textarea rows={3} {...form.register('forbiddenPaths')} /></label>
            <label className="wide-field">测试命令<span className="field-note">代码变更应用后依次自动执行。</span><textarea rows={3} {...form.register('testCommands')} /></label>
          </div>
          {save.isError && <div className="error-text">{String(save.error)}</div>}
          <div className="button-row"><button type="button" className="secondary" onClick={() => dialogRef.current?.close()}>取消</button><button type="submit" disabled={save.isPending}>保存系统</button></div>
        </form>
      </dialog>
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
  return { ...value, allowedPaths: lines(value.allowedPaths), forbiddenPaths: lines(value.forbiddenPaths), testCommands: lines(value.testCommands) };
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
  return value.split('\n').map((item) => item.trim()).filter(Boolean);
}

function ownerName(users: { userId: string; displayName: string }[], ownerUserId: string) {
  return users.find((user) => user.userId === ownerUserId)?.displayName || ownerUserId;
}
