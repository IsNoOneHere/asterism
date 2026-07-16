import { zodResolver } from '@hookform/resolvers/zod';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { useMemo, useRef, useState } from 'react';
import { Pencil, Plus, Trash2 } from 'lucide-react';
import { useFieldArray, useForm } from 'react-hook-form';
import { z } from 'zod';
import { api, GitConfiguration, SystemProfile } from '../api/client';
import { ActionConfirmDialog } from '../components/ActionConfirmDialog';
import { ErrorState } from '../components/Display';
import { Pagination, usePagination } from '../components/Pagination';
import { SearchField } from '../components/SearchField';
import { useCurrentSystem } from '../SystemContext';

const repoSchema = z.object({
  repoId: z.string().min(1, '请输入仓库编号'), name: z.string().min(1, '请输入仓库名称'), kind: z.enum(['frontend', 'backend', 'other']),
  gitlabProject: z.string(), defaultBranch: z.string().min(1, '请输入默认分支'), cloneMode: z.enum(['gitlab', 'local']),
  localPath: z.string(), allowedPaths: z.string(), forbiddenPaths: z.string(), testCommands: z.string(),
});

const schema = z.object({
  systemId: z.string().min(1, '请输入系统编号'), name: z.string().min(1, '请输入系统名称'), description: z.string(), ownerUserId: z.string().min(1, '请选择系统负责人'),
  releaseMode: z.enum(['local', 'gitlab']), validationMode: z.enum(['auto', 'skip']),
  mrTargetBranch: z.string(), mrLabels: z.string(), gitlabBaseUrl: z.string(), gitlabToken: z.string(),
  repos: z.array(repoSchema).min(1, '至少配置一个代码仓库'),
});

type FormValue = z.infer<typeof schema>;

const emptyRepo: FormValue['repos'][number] = {
  repoId: 'main', name: '主仓库', kind: 'other', gitlabProject: '', defaultBranch: 'main', cloneMode: 'local',
  localPath: '', allowedPaths: '', forbiddenPaths: '', testCommands: '',
};
const emptyForm: FormValue = {
  systemId: '', name: '', description: '', ownerUserId: '', releaseMode: 'local', validationMode: 'auto',
  mrTargetBranch: 'main', mrLabels: '', gitlabBaseUrl: '', gitlabToken: '', repos: [emptyRepo],
};

export function SystemsPage() {
  const queryClient = useQueryClient();
  const { systems, systemId, setSystemId, currentUser, isAdmin, systemMembers, canManageCurrentSystem } = useCurrentSystem();
  const users = useQuery({ queryKey: ['users'], queryFn: api.users, enabled: isAdmin, retry: false });
  const dialogRef = useRef<HTMLDialogElement>(null);
  const [mode, setMode] = useState<'edit' | 'new'>('new');
  const [message, setMessage] = useState('');
  const [query, setQuery] = useState('');
  const [deleteTarget, setDeleteTarget] = useState<SystemProfile | null>(null);
  const [tokenSet, setTokenSet] = useState(false);
  const [effectiveGitLabUrl, setEffectiveGitLabUrl] = useState('');
  const [editingSystem, setEditingSystem] = useState<SystemProfile | null>(null);
  const [editorLoading, setEditorLoading] = useState(false);
  const [editorError, setEditorError] = useState<unknown>();
  const enabledUsers = useMemo(() => {
    const values = isAdmin
      ? (users.data ?? []).filter((user) => user.enabled).map((user) => ({ userId: user.userId, displayName: user.displayName }))
      : systemMembers.map((member) => ({ userId: member.userId, displayName: member.displayName || member.userId }));
    values.push({ userId: currentUser.userId, displayName: currentUser.userId });
    if (editingSystem) values.push({ userId: editingSystem.ownerUserId, displayName: editingSystem.ownerUserId });
    return [...new Map(values.map((user) => [user.userId, user])).values()];
  }, [currentUser.userId, editingSystem, isAdmin, systemMembers, users.data]);
  const filteredSystems = useMemo(() => {
    const keyword = query.trim().toLowerCase();
    if (!keyword) return systems;
    return systems.filter((system) => [system.name, system.systemId, system.description, system.repoPath, system.ownerUserId]
      .some((value) => value?.toLowerCase().includes(keyword)));
  }, [query, systems]);
  const pagination = usePagination(filteredSystems, `${query}:${filteredSystems.map((system) => system.systemId).join(':')}`);
  const form = useForm<FormValue>({ resolver: zodResolver(schema), defaultValues: emptyForm });
  const repos = useFieldArray({ control: form.control, name: 'repos' });
  const releaseMode = form.watch('releaseMode');
  const validationError = firstError(form.formState.errors);

  const save = useMutation({
    mutationFn: async (value: FormValue) => {
      const body = { ...toProfileRequest(value), gitConfiguration: toGitRequest(value) };
      return mode === 'new' ? api.createSystem(body) : api.updateSystemProfile(value.systemId, body);
    },
    onSuccess: (saved) => {
      console.info('v5 workbench 保存系统', { systemId: saved.systemId });
      dialogRef.current?.close();
      setMessage('系统配置保存成功');
      setSystemId(saved.systemId);
      queryClient.invalidateQueries({ queryKey: ['systems'] });
      queryClient.invalidateQueries({ queryKey: ['git-config', saved.systemId] });
    },
  });
  const removeSystem = useMutation({
    mutationFn: api.deleteSystem,
    onSuccess: (_, deletedId) => {
      console.info('v5 workbench 删除系统', { systemId: deletedId });
      if (deletedId === systemId) setSystemId(systems.find((system) => system.systemId !== deletedId)?.systemId || '');
      setMessage('系统已删除');
      queryClient.invalidateQueries({ queryKey: ['systems'] });
    },
    onSettled: () => setDeleteTarget(null),
  });

  async function openEditor(system?: SystemProfile) {
    setMode(system ? 'edit' : 'new');
    setEditingSystem(system ?? null);
    setMessage('');
    save.reset();
    setEditorError(undefined);
    setEditorLoading(Boolean(system));
    if (!dialogRef.current?.open) dialogRef.current?.showModal();
    if (system) {
      form.reset({ ...emptyForm, systemId: system.systemId, name: system.name, ownerUserId: system.ownerUserId });
      try {
        const git = await queryClient.fetchQuery({
          queryKey: ['git-config', system.systemId], queryFn: () => api.gitConfiguration(system.systemId),
        });
        form.reset(fromSystem(system, git));
        setTokenSet(git.tokenSet);
        setEffectiveGitLabUrl(git.effectiveGitlabBaseUrl);
      } catch (error) {
        setEditorError(error);
      } finally {
        setEditorLoading(false);
      }
    } else {
      form.reset(emptyForm);
      setTokenSet(false);
      setEffectiveGitLabUrl('');
      setEditorLoading(false);
    }
  }

  return (
    <section className="management-page">
      <header className="page-head management-head">
        <div><h1>系统配置</h1><p>管理多仓代码边界、负责人以及 GitLab 发布方式。</p></div>
        <button type="button" className="icon-text-button" onClick={() => void openEditor()}><Plus size={16} />新建系统</button>
      </header>

      {message && <div className="success-text" role="status">{message}</div>}
      {users.isError && <ErrorState title="负责人列表加载失败" error={users.error} onRetry={() => users.refetch()} />}

      <div className="panel management-panel">
        <div className="config-section-head">
          <div><h2>系统列表</h2><p>选择系统只影响当前工作上下文，编辑配置请使用行内操作。</p></div>
          <span className="config-count">{systems.length} 个系统</span>
        </div>
        <div className="management-toolbar">
          <SearchField value={query} label="搜索系统" placeholder="搜索名称、编号、仓库或负责人" onChange={setQuery} />
          <span className="result-summary">显示 {filteredSystems.length} / {systems.length}</span>
        </div>
        <div className="table-frame"><table className="data-table management-table system-table"><thead><tr><th>系统</th><th>代码仓库</th><th>负责人</th><th>状态</th><th>操作</th></tr></thead><tbody>
          {pagination.pageItems.map((system) => {
            const canManage = isAdmin || system.ownerUserId === currentUser.userId || (system.systemId === systemId && canManageCurrentSystem);
            return <tr key={system.systemId}>
            <td><div className="table-title"><strong>{system.name}</strong><span>{system.systemId}{system.description ? ` · ${system.description}` : ''}</span></div></td>
            <td className="path-cell">{system.repoPath}</td>
            <td>{ownerName(enabledUsers, system.ownerUserId)}</td>
            <td>{system.systemId === systemId ? <span className="status-badge success">当前系统</span> : <span className="status-badge neutral">可用</span>}</td>
            <td><div className="button-row compact-actions">
              <button type="button" className="secondary icon-text-button" title={canManage ? undefined : '只有系统 owner/admin 可以编辑'} disabled={!canManage} onClick={() => void openEditor(system)}><Pencil size={15} />编辑</button>
              <button type="button" className="danger-outline icon-text-button" aria-label={`删除系统 ${system.systemId}`} title={canManage ? undefined : '只有系统 owner/admin 可以删除'} disabled={!canManage || removeSystem.isPending} onClick={() => setDeleteTarget(system)}><Trash2 size={15} />删除</button>
            </div></td>
          </tr>;})}
          {!filteredSystems.length && <tr><td className="empty-cell" colSpan={5}>{query ? '没有匹配的系统' : '还没有系统配置'}</td></tr>}
        </tbody></table></div>
        <Pagination total={filteredSystems.length} page={pagination.page} totalPages={pagination.totalPages} onPageChange={pagination.setPage} />
      </div>

      <ActionConfirmDialog open={Boolean(deleteTarget)} title={`删除“${deleteTarget?.name || ''}”？`}
        description="删除后系统配置不可恢复；存在 PRD、工作项等业务数据时，系统会阻止删除。"
        confirmLabel="删除系统" pending={removeSystem.isPending} onClose={() => setDeleteTarget(null)}
        onConfirm={() => deleteTarget && removeSystem.mutate(deleteTarget.systemId)} />
      <ActionConfirmDialog open={Boolean(removeSystem.error)} title="删除失败" description={removeSystem.error?.message || ''}
        confirmLabel="知道了" alert showCancel={false} onClose={() => removeSystem.reset()} onConfirm={() => removeSystem.reset()} />

      <dialog ref={dialogRef} className="confirm-dialog config-dialog system-config-dialog" aria-labelledby="system-dialog-title">
        <form onSubmit={form.handleSubmit((value) => save.mutate(value))}>
          <div className="config-section-head compact"><div><h2 id="system-dialog-title">{mode === 'new' ? '新建系统' : '编辑系统配置'}</h2><p>仓库范围和发布配置会随新工作项快照。</p></div></div>
          {editorLoading && <div className="notice" role="status">正在加载 Git 与发布配置…</div>}
          {Boolean(editorError) && <ErrorState title="系统配置加载失败" error={editorError} onRetry={() => void openEditor(editingSystem ?? undefined)} />}
          <fieldset className="dialog-fieldset" disabled={editorLoading || Boolean(editorError)}>
          <div className="config-dialog-fields system-dialog-fields">
            <label>系统编号<input {...form.register('systemId')} readOnly={mode === 'edit'} /></label>
            <label>名称<input {...form.register('name')} /></label>
            <label className="wide-field">描述<textarea rows={2} {...form.register('description')} /></label>
            <label>系统负责人<select {...form.register('ownerUserId')} disabled={isAdmin && users.isLoading}><option value="">{users.isLoading ? '用户加载中…' : '请选择用户'}</option>{enabledUsers.map((user) => <option key={user.userId} value={user.userId}>{user.displayName || user.userId}</option>)}</select></label>
          </div>

          <fieldset className="config-subsection"><legend>Git 与发布</legend>
            <div className="config-dialog-fields system-dialog-fields">
              <label>发布模式<select {...form.register('releaseMode')}><option value="local">Local</option><option value="gitlab">GitLab MR</option></select></label>
              <label>验证模式<select {...form.register('validationMode')}><option value="auto">自动运行仓库测试</option><option value="skip">交给 MR CI / 人工</option></select></label>
              <label>MR 目标分支<input {...form.register('mrTargetBranch')} placeholder="默认使用仓库 defaultBranch" /></label>
              <label>MR Labels<input {...form.register('mrLabels')} placeholder="每行一个" /></label>
              <label className="wide-field">GitLab 覆盖地址<input {...form.register('gitlabBaseUrl')} placeholder="留空使用全局 ASTERISM_GITLAB_BASE_URL" />
                {effectiveGitLabUrl && <span className="field-note">当前有效地址：{effectiveGitLabUrl}</span>}</label>
              <label className="wide-field">GitLab Token<input type="password" autoComplete="new-password" {...form.register('gitlabToken')}
                placeholder={tokenSet ? '留空保留现有 Token' : '留空使用全局 ASTERISM_GITLAB_TOKEN'} /></label>
            </div>

            <div className="config-section-head compact"><div><h3>仓库列表</h3><p>路径门禁和测试命令按仓库独立生效。</p></div>
              <button type="button" className="secondary icon-text-button" onClick={() => repos.append({ ...emptyRepo, repoId: `repo-${repos.fields.length + 1}`, name: `仓库 ${repos.fields.length + 1}` })}><Plus size={15} />添加仓库</button></div>
            <div className="repo-config-list">
              {repos.fields.map((field, index) => <section className="repo-config-card" key={field.id}>
                <div className="config-section-head compact"><h4>仓库 {index + 1}</h4>
                  <button type="button" className="danger-outline icon-text-button" disabled={repos.fields.length === 1} onClick={() => repos.remove(index)}><Trash2 size={14} />移除</button></div>
                <div className="config-dialog-fields system-dialog-fields">
                  <label>仓库编号<input aria-label={`仓库 ${index + 1} 编号`} {...form.register(`repos.${index}.repoId`)} /></label>
                  <label>名称<input aria-label={`仓库 ${index + 1} 名称`} {...form.register(`repos.${index}.name`)} /></label>
                  <label>类型<select aria-label={`仓库 ${index + 1} 类型`} {...form.register(`repos.${index}.kind`)}><option value="frontend">前端</option><option value="backend">后端</option><option value="other">其他</option></select></label>
                  <label>克隆方式<select aria-label={`仓库 ${index + 1} 克隆方式`} {...form.register(`repos.${index}.cloneMode`)}><option value="local">本地路径</option><option value="gitlab">GitLab</option></select></label>
                  <label>默认分支<input aria-label={`仓库 ${index + 1} 默认分支`} {...form.register(`repos.${index}.defaultBranch`)} /></label>
                  <label>GitLab Project<input aria-label={`仓库 ${index + 1} GitLab Project`} {...form.register(`repos.${index}.gitlabProject`)} placeholder="group/app-web" /></label>
                  <label className="wide-field">本地路径<input aria-label={`仓库 ${index + 1} 本地路径`} {...form.register(`repos.${index}.localPath`)} placeholder={releaseMode === 'local' ? '/srv/repos/app' : 'cloneMode=local 时填写'} /></label>
                  <label className="wide-field">允许修改路径<textarea aria-label={`仓库 ${index + 1} 允许修改路径`} rows={2} {...form.register(`repos.${index}.allowedPaths`)} /></label>
                  <label className="wide-field">禁止修改路径<textarea aria-label={`仓库 ${index + 1} 禁止修改路径`} rows={2} {...form.register(`repos.${index}.forbiddenPaths`)} /></label>
                  <label className="wide-field">测试命令<textarea aria-label={`仓库 ${index + 1} 测试命令`} rows={2} {...form.register(`repos.${index}.testCommands`)} /></label>
                </div>
              </section>)}
            </div>
          </fieldset>
          </fieldset>

          {validationError && <div className="error-text" role="alert">{validationError}</div>}
          {save.isError && <div className="error-text" role="alert">{save.error.message}</div>}
          <div className="button-row"><button type="button" className="secondary" onClick={() => dialogRef.current?.close()}>取消</button><button type="submit" disabled={editorLoading || Boolean(editorError) || save.isPending}>保存系统</button></div>
        </form>
      </dialog>
    </section>
  );
}

function fromSystem(system: SystemProfile, git: GitConfiguration): FormValue {
  return {
    systemId: system.systemId, name: system.name, description: system.description || '', ownerUserId: system.ownerUserId,
    releaseMode: git.releaseMode, validationMode: git.validationMode, mrTargetBranch: git.mrTargetBranch,
    mrLabels: git.mrLabels.join('\n'), gitlabBaseUrl: git.gitlabBaseUrl || '', gitlabToken: '',
    repos: git.repos.map((repo) => ({ ...repo, allowedPaths: repo.allowedPaths.join('\n'),
      forbiddenPaths: repo.forbiddenPaths.join('\n'), testCommands: repo.testCommands.join('\n') })),
  };
}

function toProfileRequest(value: FormValue) {
  const repo = value.repos[0];
  return { systemId: value.systemId, name: value.name, description: value.description,
    repoPath: repo.localPath || repo.gitlabProject, ownerUserId: value.ownerUserId,
    allowedPaths: lines(repo.allowedPaths), forbiddenPaths: lines(repo.forbiddenPaths), testCommands: lines(repo.testCommands) };
}

function toGitRequest(value: FormValue) {
  return { releaseMode: value.releaseMode, validationMode: value.validationMode, mrTargetBranch: value.mrTargetBranch,
    mrLabels: lines(value.mrLabels), gitlabBaseUrl: value.gitlabBaseUrl, gitlabToken: value.gitlabToken,
    repos: value.repos.map((repo) => ({ ...repo, allowedPaths: lines(repo.allowedPaths),
      forbiddenPaths: lines(repo.forbiddenPaths), testCommands: lines(repo.testCommands) })) };
}

function lines(value: string) {
  return value.split('\n').map((item) => item.trim()).filter(Boolean);
}

function ownerName(users: { userId: string; displayName: string }[], ownerUserId: string) {
  return users.find((user) => user.userId === ownerUserId)?.displayName || ownerUserId;
}

function firstError(value: unknown): string {
  // 表单校验只展示首条可行动的信息，避免用户面对一串内部字段名。
  if (!value || typeof value !== 'object') return '';
  if ('message' in value && typeof value.message === 'string') return value.message;
  for (const child of Object.values(value)) {
    const message = firstError(child);
    if (message) return message;
  }
  return '';
}
