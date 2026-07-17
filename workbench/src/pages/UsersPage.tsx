import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { FormEvent, useEffect, useMemo, useRef, useState } from 'react';
import { Ellipsis, KeyRound, Pencil, Plus, Trash2, UserCheck, UserPlus, UserX } from 'lucide-react';
import { api, UserAccount } from '../api/client';
import { ActionConfirmDialog } from '../components/ActionConfirmDialog';
import { errorMessage, ErrorState } from '../components/Display';
import { Pagination, usePagination } from '../components/Pagination';
import { SearchField } from '../components/SearchField';
import { useCurrentSystem } from '../SystemContext';

const emptyUser = { userId: '', displayName: '', email: '', password: '' };
type UserConfirmAction =
  | { type: 'disable'; userId: string; name: string }
  | { type: 'delete'; userId: string; name: string }
  | { type: 'remove-member'; userId: string; role: string };

export function UsersPage({ currentUserId }: { currentUserId: string }) {
  const queryClient = useQueryClient();
  const users = useQuery({ queryKey: ['users'], queryFn: api.users, retry: false });
  const { systemId } = useCurrentSystem();
  const userDialogRef = useRef<HTMLDialogElement>(null);
  const memberDialogRef = useRef<HTMLDialogElement>(null);
  const resetDialogRef = useRef<HTMLDialogElement>(null);
  const [tab, setTab] = useState<'users' | 'members'>('users');
  const [userForm, setUserForm] = useState(emptyUser);
  const [editingUser, setEditingUser] = useState(false);
  const [membership, setMembership] = useState({ systemId, userId: '', role: 'requester' });
  const [resetForm, setResetForm] = useState({ userId: '', password: '', confirm: '' });
  const [message, setMessage] = useState('');
  const [query, setQuery] = useState('');
  const [openActionMenu, setOpenActionMenu] = useState('');
  const [confirmAction, setConfirmAction] = useState<UserConfirmAction | null>(null);
  const enabledUsers = (users.data ?? []).filter((user) => user.enabled);
  const members = useQuery({
    queryKey: ['members', systemId],
    queryFn: () => api.members(systemId),
    enabled: Boolean(systemId),
    retry: false,
  });
  const userValues = users.data ?? [];
  const memberValues = members.data ?? [];
  const filteredUsers = useMemo(() => {
    const keyword = query.trim().toLowerCase();
    if (!keyword) return userValues;
    return userValues.filter((user) => [user.userId, user.displayName, user.email]
      .some((value) => value?.toLowerCase().includes(keyword)));
  }, [query, userValues]);
  const filteredMembers = useMemo(() => {
    const keyword = query.trim().toLowerCase();
    if (!keyword) return memberValues;
    return memberValues.filter((member) => [member.userId, member.displayName, member.role, roleName(member.role)]
      .some((value) => value?.toLowerCase().includes(keyword)));
  }, [query, memberValues]);
  const userPagination = usePagination(filteredUsers, `${query}:${filteredUsers.map((user) => user.userId).join(':')}`);
  const memberPagination = usePagination(filteredMembers, `${systemId}:${query}:${filteredMembers.map((member) => member.userId + member.role).join(':')}`);

  useEffect(() => {
    setMembership((value) => ({ ...value, systemId }));
  }, [systemId]);

  const upsert = useMutation({
    mutationFn: () => api.upsertUser(blankToUndefined(userForm)),
    onSuccess: (saved) => {
      console.info('v5 workbench 保存用户', { userId: saved.userId });
      userDialogRef.current?.close();
      setMessage('用户保存成功');
      queryClient.invalidateQueries({ queryKey: ['users'] });
    },
  });
  const disable = useMutation({
    mutationFn: api.disableUser,
    onSuccess: () => {
      setMessage('用户已禁用');
      queryClient.invalidateQueries({ queryKey: ['users'] });
    },
    onSettled: () => setConfirmAction(null),
  });
  const enable = useMutation({
    mutationFn: api.enableUser,
    onSuccess: () => {
      setMessage('用户已启用');
      queryClient.invalidateQueries({ queryKey: ['users'] });
    },
  });
  const reset = useMutation({
    mutationFn: (value: { userId: string; password: string }) => api.resetPassword(value.userId, value.password),
    onSuccess: () => {
      console.info('v5 workbench 重置用户密码', { userId: resetForm.userId });
      resetDialogRef.current?.close();
      setMessage('密码已重置');
    },
  });
  const saveMembership = useMutation({
    mutationFn: () => api.upsertMembership(membership),
    onSuccess: () => {
      console.info('v5 workbench 保存系统成员', membership);
      memberDialogRef.current?.close();
      setMessage('成员保存成功');
      queryClient.invalidateQueries({ queryKey: ['members', systemId] });
    },
  });
  const removeMembership = useMutation({
    mutationFn: (value: { userId: string; role: string }) => api.deleteMember(systemId, value.userId, value.role),
    onSuccess: () => {
      setMessage('成员角色已移除');
      queryClient.invalidateQueries({ queryKey: ['members', systemId] });
    },
    onSettled: () => setConfirmAction(null),
  });
  const removeUser = useMutation({
    mutationFn: api.deleteUser,
    onSuccess: (_, userId) => {
      console.info('v5 workbench 删除用户', { userId });
      setMessage('用户已删除');
      queryClient.invalidateQueries({ queryKey: ['users'] });
      queryClient.invalidateQueries({ queryKey: ['members'] });
    },
    onSettled: () => setConfirmAction(null),
  });
  const operationError = removeUser.error || disable.error || enable.error || removeMembership.error;
  const confirmation = confirmAction ? confirmationCopy(confirmAction) : null;
  const operationPending = removeUser.isPending || disable.isPending || enable.isPending || removeMembership.isPending;

  function openUserEditor(user?: UserAccount) {
    setOpenActionMenu('');
    upsert.reset();
    setEditingUser(Boolean(user));
    setUserForm(user
      ? { userId: user.userId, displayName: user.displayName, email: user.email || '', password: '' }
      : emptyUser);
    setMessage('');
    // 原生 dialog 直接提供居中展示、遮罩和键盘关闭能力。
    userDialogRef.current?.showModal();
  }

  function openMemberEditor() {
    saveMembership.reset();
    setMembership({ systemId, userId: '', role: 'requester' });
    setMessage('');
    memberDialogRef.current?.showModal();
  }

  function openPasswordReset(userId: string) {
    setOpenActionMenu('');
    reset.reset();
    setResetForm({ userId, password: '', confirm: '' });
    resetDialogRef.current?.showModal();
  }

  function openConfirmation(action: UserConfirmAction) {
    setOpenActionMenu('');
    removeUser.reset();
    disable.reset();
    enable.reset();
    removeMembership.reset();
    setMessage('');
    setConfirmAction(action);
  }

  function runConfirmedAction() {
    if (!confirmAction) return;
    if (confirmAction.type === 'disable') disable.mutate(confirmAction.userId);
    if (confirmAction.type === 'delete') removeUser.mutate(confirmAction.userId);
    if (confirmAction.type === 'remove-member') removeMembership.mutate(confirmAction);
  }

  function clearOperationError() {
    removeUser.reset();
    disable.reset();
    enable.reset();
    removeMembership.reset();
  }

  return (
    <section className="management-page">
      <header className="page-head management-head">
        <div><h1>用户与成员</h1><p>管理登录账号，以及当前系统中的成员角色。</p></div>
        {tab === 'users' ? (
          <button type="button" className="icon-text-button" onClick={() => openUserEditor()}><Plus size={16} />新增用户</button>
        ) : (
          <button type="button" className="icon-text-button" disabled={!systemId} onClick={openMemberEditor}><UserPlus size={16} />添加成员</button>
        )}
      </header>

      {message && <div className="success-text" role="status">{message}</div>}

      <div className="panel management-panel">
        <div className="tabs management-tabs" role="tablist" aria-label="用户与成员列表">
          <button type="button" role="tab" aria-selected={tab === 'users'} className={tab === 'users' ? 'active' : ''} onClick={() => { setTab('users'); setQuery(''); setOpenActionMenu(''); }}>用户列表 <span>{userValues.length}</span></button>
          <button type="button" role="tab" aria-selected={tab === 'members'} className={tab === 'members' ? 'active' : ''} onClick={() => { setTab('members'); setQuery(''); setOpenActionMenu(''); }}>当前系统成员 <span>{memberValues.length}</span></button>
        </div>
        <div className="management-toolbar">
          <SearchField
            value={query}
            label={tab === 'users' ? '搜索用户' : '搜索成员'}
            placeholder={tab === 'users' ? '搜索姓名、账号或邮箱' : '搜索姓名、账号或角色'}
            onChange={(value) => { setQuery(value); setOpenActionMenu(''); }}
          />
          <span className="result-summary">显示 {tab === 'users' ? filteredUsers.length : filteredMembers.length} / {tab === 'users' ? userValues.length : memberValues.length}</span>
        </div>

        {tab === 'users' ? users.isLoading ? <div className="empty" role="status">用户加载中…</div> : users.isError ?
          <ErrorState title="用户列表加载失败" error={users.error} onRetry={() => users.refetch()} /> : <>
          <div className={`table-frame users-table-frame${openActionMenu ? ' menu-open' : ''}`}><table className="data-table management-table users-table"><thead><tr><th>用户</th><th>邮箱</th><th>状态</th><th>操作</th></tr></thead><tbody>
            {userPagination.pageItems.map((user) => <tr key={user.userId}>
              <td><div className="table-title" title={`${user.displayName} · ${user.userId}`}><strong>{user.displayName}</strong><span>{user.userId}</span></div></td>
              <td title={user.email || '未设置'}>{user.email || '未设置'}</td>
              <td><span className={`status-badge ${user.enabled ? 'success' : 'neutral'}`}>{user.enabled ? '已启用' : '已禁用'}</span></td>
              <td><div className="user-row-actions">
                {/* 高频操作直接显示，低频和危险操作统一收进更多菜单。 */}
                <button type="button" className="secondary icon-button" aria-label={`编辑用户 ${user.userId}`} title="编辑用户" onClick={() => openUserEditor(user)}><Pencil size={16} /></button>
                <button type="button" className="secondary icon-button" aria-label={`重置 ${user.userId} 的密码`} title="重置密码" onClick={() => openPasswordReset(user.userId)}><KeyRound size={16} /></button>
                <div className="row-action-menu" onBlur={(event) => {
                  if (!event.currentTarget.contains(event.relatedTarget as Node | null)) setOpenActionMenu('');
                }} onKeyDown={(event) => {
                  if (event.key === 'Escape') {
                    setOpenActionMenu('');
                    event.currentTarget.querySelector('button')?.focus();
                  }
                }}>
                  <button type="button" className="secondary icon-button" aria-label={`更多操作 ${user.userId}`} title="更多操作"
                    aria-haspopup="menu" aria-expanded={openActionMenu === user.userId}
                    onClick={() => setOpenActionMenu((value) => value === user.userId ? '' : user.userId)}><Ellipsis size={17} /></button>
                  {openActionMenu === user.userId && <div className="system-select-menu row-action-menu-panel" role="menu" aria-label={`${user.displayName || user.userId} 的更多操作`}>
                    {user.enabled ? <button type="button" role="menuitem" className="system-select-option row-action-menu-item" title={user.userId === currentUserId ? '不能禁用当前登录用户' : undefined}
                      onClick={() => openConfirmation({ type: 'disable', userId: user.userId, name: user.displayName || user.userId })} disabled={user.userId === currentUserId}><UserX size={16} /><span>禁用用户</span></button>
                      : <button type="button" role="menuitem" className="system-select-option row-action-menu-item" disabled={enable.isPending}
                        onClick={() => { setOpenActionMenu(''); clearOperationError(); setMessage(''); enable.mutate(user.userId); }}><UserCheck size={16} /><span>启用用户</span></button>}
                    <button type="button" role="menuitem" className="system-select-option row-action-menu-item danger" aria-label={`删除用户 ${user.userId}`}
                      title={user.userId === currentUserId ? '不能删除当前登录用户' : undefined}
                      disabled={removeUser.isPending || user.userId === currentUserId} onClick={() => openConfirmation({ type: 'delete', userId: user.userId, name: user.displayName || user.userId })}><Trash2 size={16} /><span>删除用户</span></button>
                  </div>}
                </div>
              </div></td>
            </tr>)}
            {!filteredUsers.length && <tr><td className="empty-cell" colSpan={4}>{query ? '没有匹配的用户' : '暂无用户'}</td></tr>}
          </tbody></table></div>
          <Pagination total={filteredUsers.length} page={userPagination.page} totalPages={userPagination.totalPages} onPageChange={(page) => { setOpenActionMenu(''); userPagination.setPage(page); }} />
        </> : members.isLoading ? <div className="empty" role="status">成员加载中…</div> : members.isError ?
          <ErrorState title="成员列表加载失败" error={members.error} onRetry={() => members.refetch()} /> : <>
          <div className="table-frame"><table className="data-table management-table"><thead><tr><th>成员</th><th>角色</th><th>所属系统</th><th>操作</th></tr></thead><tbody>
            {memberPagination.pageItems.map((member) => <tr key={member.userId + member.role}>
              <td><div className="table-title" title={`${member.displayName || member.userId} · ${member.userId}`}><strong>{member.displayName || member.userId}</strong><span>{member.userId}</span></div></td>
              <td><span className="status-badge info">{roleName(member.role)}</span></td>
              <td title={systemId}>{systemId}</td>
              <td><button type="button" className="danger-outline" onClick={() => openConfirmation({ type: 'remove-member', userId: member.userId, role: member.role })}>移除</button></td>
            </tr>)}
            {!filteredMembers.length && <tr><td className="empty-cell" colSpan={4}>{query ? '没有匹配的成员' : '当前系统暂无成员'}</td></tr>}
          </tbody></table></div>
          <Pagination total={filteredMembers.length} page={memberPagination.page} totalPages={memberPagination.totalPages} onPageChange={memberPagination.setPage} />
        </>}
      </div>

      <ActionConfirmDialog
        open={Boolean(confirmAction)}
        title={confirmation?.title || ''}
        description={confirmation?.description || ''}
        confirmLabel={confirmation?.confirmLabel}
        pending={operationPending}
        onClose={() => setConfirmAction(null)}
        onConfirm={runConfirmedAction}
      />
      <ActionConfirmDialog
        open={Boolean(operationError)}
        title="操作失败"
        description={operationError?.message || ''}
        confirmLabel="知道了"
        alert
        showCancel={false}
        onClose={clearOperationError}
        onConfirm={clearOperationError}
      />

      <dialog ref={userDialogRef} className="confirm-dialog config-dialog" aria-labelledby="user-dialog-title" onClose={() => { setEditingUser(false); setUserForm(emptyUser); }}>
        <form onSubmit={(event: FormEvent) => { event.preventDefault(); upsert.mutate(); }}>
          <div className="config-section-head compact"><div><h2 id="user-dialog-title">{editingUser ? '编辑用户' : '新增用户'}</h2><p>{editingUser ? '用户 ID 不可修改，密码留空则保持原值。' : '创建可登录星群的新账号。'}</p></div></div>
          <div className="config-dialog-fields">
            <label>用户 ID<input required value={userForm.userId} readOnly={editingUser} onChange={(event) => setUserForm({ ...userForm, userId: event.target.value })} /></label>
            <label>显示名<input required value={userForm.displayName} onChange={(event) => setUserForm({ ...userForm, displayName: event.target.value })} /></label>
            <label>邮箱<input type="email" value={userForm.email} onChange={(event) => setUserForm({ ...userForm, email: event.target.value })} /></label>
            <label>密码<input type="password" autoComplete="new-password" placeholder={editingUser ? '留空则不修改' : ''} value={userForm.password} onChange={(event) => setUserForm({ ...userForm, password: event.target.value })} /></label>
          </div>
          {upsert.error && <div className="error-text">{upsert.error.message}</div>}
          <div className="button-row"><button type="button" className="secondary" onClick={() => userDialogRef.current?.close()}>取消</button><button type="submit" disabled={!userForm.userId || !userForm.displayName || (!editingUser && !userForm.password) || upsert.isPending}>保存用户</button></div>
        </form>
      </dialog>

      <dialog ref={memberDialogRef} className="confirm-dialog config-dialog" aria-labelledby="member-dialog-title">
        <form onSubmit={(event: FormEvent) => { event.preventDefault(); saveMembership.mutate(); }}>
          <div className="config-section-head compact"><div><h2 id="member-dialog-title">添加系统成员</h2><p>成员将加入当前系统 {systemId}。</p></div></div>
          <label>用户<select required value={membership.userId} onChange={(event) => setMembership({ ...membership, userId: event.target.value })}><option value="">请选择用户</option>{enabledUsers.map((user) => <option key={user.userId} value={user.userId}>{user.displayName || user.userId}</option>)}</select></label>
          <label>角色<select value={membership.role} onChange={(event) => setMembership({ ...membership, role: event.target.value })}><option value="requester">需求方</option><option value="owner">负责人</option><option value="admin">管理员</option></select></label>
          {saveMembership.error && <div className="error-text" role="alert">{errorMessage(saveMembership.error, '成员保存失败')}</div>}
          <div className="button-row"><button type="button" className="secondary" onClick={() => memberDialogRef.current?.close()}>取消</button><button type="submit" disabled={!membership.userId || saveMembership.isPending}>保存成员</button></div>
        </form>
      </dialog>

      <dialog ref={resetDialogRef} className="confirm-dialog config-dialog" aria-labelledby="reset-dialog-title" onClose={() => setResetForm({ userId: '', password: '', confirm: '' })}>
        <form onSubmit={(event: FormEvent) => { event.preventDefault(); reset.mutate({ userId: resetForm.userId, password: resetForm.password }); }}>
          <div className="config-section-head compact"><div><h2 id="reset-dialog-title">重置 {resetForm.userId} 的密码</h2><p>保存后旧密码立即失效。</p></div></div>
          <label>新密码<input type="password" autoComplete="new-password" value={resetForm.password} onChange={(event) => setResetForm({ ...resetForm, password: event.target.value })} /></label>
          <label>再次输入<input type="password" autoComplete="new-password" value={resetForm.confirm} onChange={(event) => setResetForm({ ...resetForm, confirm: event.target.value })} /></label>
          {resetForm.confirm && resetForm.password !== resetForm.confirm && <div className="error-text">两次密码不一致</div>}
          {reset.error && <div className="error-text" role="alert">{errorMessage(reset.error, '密码重置失败')}</div>}
          <div className="button-row"><button type="button" className="secondary" onClick={() => resetDialogRef.current?.close()}>取消</button><button type="submit" disabled={!resetForm.password || resetForm.password !== resetForm.confirm || reset.isPending}>确认重置</button></div>
        </form>
      </dialog>
    </section>
  );
}

function blankToUndefined(value: { userId: string; displayName: string; email: string; password: string }) {
  // 空密码表示沿用后端默认策略；前端不接触 password_hash。
  return { ...value, email: value.email || undefined, password: value.password || undefined };
}

function roleName(role: string) {
  return { requester: '需求方', owner: '负责人', admin: '管理员' }[role] || role;
}

function confirmationCopy(action: UserConfirmAction) {
  if (action.type === 'disable') return {
    title: `禁用“${action.name}”？`,
    description: '禁用后该用户将无法登录，账号和历史数据仍会保留。',
    confirmLabel: '禁用用户',
  };
  if (action.type === 'delete') return {
    title: `删除“${action.name}”？`,
    description: '删除后账号不可恢复；系统负责人需要先转移负责人。',
    confirmLabel: '删除用户',
  };
  return {
    title: `移除 ${action.userId}？`,
    description: `将从当前系统移除其“${roleName(action.role)}”角色。`,
    confirmLabel: '移除成员',
  };
}
