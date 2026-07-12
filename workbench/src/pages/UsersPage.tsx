import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { FormEvent, useEffect, useState } from 'react';
import { api } from '../api/client';
import { Pagination, usePagination } from '../components/Pagination';
import { useCurrentSystem } from '../SystemContext';

const emptyUser = { userId: '', displayName: '', email: '', password: '' };

export function UsersPage() {
  const queryClient = useQueryClient();
  const users = useQuery({ queryKey: ['users'], queryFn: api.users, retry: false });
  const { systemId } = useCurrentSystem();
  const [userForm, setUserForm] = useState(emptyUser);
  const [editingUser, setEditingUser] = useState(false);
  const [membership, setMembership] = useState({ systemId, userId: '', role: 'requester' });
  const [resetForm, setResetForm] = useState({ userId: '', password: '', confirm: '' });
  const [message, setMessage] = useState('');
  const enabledUsers = (users.data ?? []).filter((user) => user.enabled);
  const members = useQuery({
    queryKey: ['members', membership.systemId],
    queryFn: () => api.members(membership.systemId),
    enabled: Boolean(membership.systemId),
    retry: false,
  });
  const userValues = users.data ?? [];
  const memberValues = members.data ?? [];
  const userPagination = usePagination(userValues, userValues.map((user) => user.userId).join(':'));
  const memberPagination = usePagination(memberValues, systemId + ':' + memberValues.map((member) => member.userId + member.role).join(':'));

  useEffect(() => {
    if (systemId && membership.systemId !== systemId) setMembership((value) => ({ ...value, systemId }));
  }, [membership.systemId, systemId]);

  const upsert = useMutation({
    mutationFn: () => api.upsertUser(blankToUndefined(userForm)),
    onSuccess: (saved) => {
      console.info('v5 workbench 保存用户', { userId: saved.userId });
      setUserForm(emptyUser);
      setEditingUser(false);
      setMessage('用户保存成功');
      queryClient.invalidateQueries({ queryKey: ['users'] });
    },
  });
  const disable = useMutation({
    mutationFn: api.disableUser,
    onSuccess: () => queryClient.invalidateQueries({ queryKey: ['users'] }),
  });
  const reset = useMutation({
    mutationFn: (value: { userId: string; password: string }) => api.resetPassword(value.userId, value.password),
    onSuccess: () => { setMessage('密码已重置'); setResetForm({ userId: '', password: '', confirm: '' }); },
  });
  const saveMembership = useMutation({
    mutationFn: () => api.upsertMembership(membership),
    onSuccess: () => {
      console.info('v5 workbench 保存系统成员', membership);
      setMessage('成员保存成功');
      queryClient.invalidateQueries({ queryKey: ['members', membership.systemId] });
    },
  });
  const removeMembership = useMutation({
    mutationFn: (value: { userId: string; role: string }) => api.deleteMember(membership.systemId, value.userId, value.role),
    onSuccess: () => queryClient.invalidateQueries({ queryKey: ['members', membership.systemId] }),
  });

  function submitUser(event: FormEvent) {
    event.preventDefault();
    upsert.mutate();
  }

  function submitMembership(event: FormEvent) {
    event.preventDefault();
    saveMembership.mutate();
  }

  return (
    <section className="split wide-left">
      <div className="panel">
        <h1>用户与成员</h1>
        <form onSubmit={submitUser}>
          <label>
            用户 ID
            <input value={userForm.userId} readOnly={editingUser} onChange={(event) => setUserForm({ ...userForm, userId: event.target.value })} />
          </label>
          <label>
            显示名
            <input value={userForm.displayName} onChange={(event) => setUserForm({ ...userForm, displayName: event.target.value })} />
          </label>
          <label>
            邮箱
            <input value={userForm.email} onChange={(event) => setUserForm({ ...userForm, email: event.target.value })} />
          </label>
          <label>
            密码
            <input type="password" value={userForm.password} placeholder={editingUser ? '留空则不改密码' : ''} onChange={(event) => setUserForm({ ...userForm, password: event.target.value })} />
          </label>
          <div className="button-row">
            <button type="submit" disabled={!userForm.userId || !userForm.displayName}>保存用户</button>
            {editingUser && <button type="button" className="secondary" onClick={() => { setEditingUser(false); setUserForm(emptyUser); }}>取消</button>}
          </div>
        </form>
        <form className="sub-form" onSubmit={submitMembership}>
          <h2>系统成员</h2>
          <label>
            用户
            <select value={membership.userId} onChange={(event) => setMembership({ ...membership, userId: event.target.value })}>
              <option value="">请选择用户</option>
              {enabledUsers.map((user) => <option key={user.userId} value={user.userId}>{user.displayName || user.userId}</option>)}
            </select>
          </label>
          <label>
            角色
            <select value={membership.role} onChange={(event) => setMembership({ ...membership, role: event.target.value })}>
              <option value="requester">requester</option>
              <option value="owner">owner</option>
              <option value="admin">admin</option>
            </select>
          </label>
          <button type="submit" disabled={!membership.systemId || !membership.userId}>保存成员</button>
        </form>
        {message && <div className="success-text">{message}</div>}
      </div>
      <div className="panel">
        <h2>用户列表</h2>
        {userPagination.pageItems.map((user) => (
          <div className="list-item action-item" key={user.userId}>
            <div>
              <strong>{user.displayName}</strong>
              <span>{user.userId} · {user.email || 'no-email'} · {user.enabled ? 'enabled' : 'disabled'}</span>
            </div>
            <div className="button-row">
              <button type="button" onClick={() => { setEditingUser(true); setUserForm({ userId: user.userId, displayName: user.displayName, email: user.email || '', password: '' }); }}>编辑</button>
              <button type="button" className="secondary" onClick={() => setResetForm({ userId: user.userId, password: '', confirm: '' })}>重置密码</button>
              <button type="button" className="secondary" onClick={() => { if (window.confirm(`确认禁用用户 ${user.userId}？`)) disable.mutate(user.userId); }} disabled={!user.enabled}>禁用</button>
            </div>
          </div>
        ))}
        {users.isError && <div className="empty">用户接口不可用或无管理员权限。</div>}
        <Pagination total={userValues.length} page={userPagination.page} totalPages={userPagination.totalPages} onPageChange={userPagination.setPage} />
        <h2>当前系统成员</h2>
        {memberPagination.pageItems.map((member) => (
          <div className="list-item action-item" key={member.userId + member.role}>
            <div>
              <strong>{member.displayName || member.userId}</strong>
              <span>{member.userId} · {member.role}</span>
            </div>
            <button type="button" className="secondary" onClick={() => { if (window.confirm(`确认移除 ${member.userId} 的 ${member.role} 角色？`)) removeMembership.mutate({ userId: member.userId, role: member.role }); }}>移除</button>
          </div>
        ))}
        <Pagination total={memberValues.length} page={memberPagination.page} totalPages={memberPagination.totalPages} onPageChange={memberPagination.setPage} />
      </div>
      {resetForm.userId && <dialog open className="confirm-dialog">
        <form onSubmit={(event) => { event.preventDefault(); reset.mutate({ userId: resetForm.userId, password: resetForm.password }); }}>
          <h2>重置 {resetForm.userId} 的密码</h2>
          <label>新密码<input type="password" autoComplete="new-password" value={resetForm.password} onChange={(event) => setResetForm({ ...resetForm, password: event.target.value })} /></label>
          <label>再次输入<input type="password" autoComplete="new-password" value={resetForm.confirm} onChange={(event) => setResetForm({ ...resetForm, confirm: event.target.value })} /></label>
          {resetForm.confirm && resetForm.password !== resetForm.confirm && <div className="error-text">两次密码不一致</div>}
          <div className="button-row"><button type="submit" disabled={!resetForm.password || resetForm.password !== resetForm.confirm || reset.isPending}>确认重置</button><button type="button" className="secondary" onClick={() => setResetForm({ userId: '', password: '', confirm: '' })}>取消</button></div>
        </form>
      </dialog>}
    </section>
  );
}

function blankToUndefined(value: { userId: string; displayName: string; email: string; password: string }) {
  // 空密码表示沿用后端默认策略；前端不接触 password_hash。
  return {
    userId: value.userId,
    displayName: value.displayName,
    email: value.email || undefined,
    password: value.password || undefined,
  };
}
