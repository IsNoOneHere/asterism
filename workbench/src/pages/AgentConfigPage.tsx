import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { useState } from 'react';
import { Pencil, Plus, Trash2 } from 'lucide-react';
import { AgentConfiguration, AgentRole, api, ModelProfile } from '../api/client';
import { useCurrentSystem } from '../SystemContext';

type RoleDraft = { name: string; engine: string; modelProfileRef: string; pathScope: string; prompt: string; maxTurns: number; timeoutSeconds: number };

const emptyRole: RoleDraft = { name: '', engine: 'http', modelProfileRef: '', pathScope: '', prompt: '', maxTurns: 50, timeoutSeconds: 600 };

export function AgentConfigPage() {
  const { systemId } = useCurrentSystem();
  const queryClient = useQueryClient();
  const [roleId, setRoleId] = useState('');
  const [role, setRole] = useState<RoleDraft>(emptyRole);
  const [message, setMessage] = useState('');
  const config = useQuery({
    queryKey: ['agent-config', systemId],
    queryFn: () => api.agentConfiguration(systemId),
    enabled: Boolean(systemId),
  });
  const accept = (value: AgentConfiguration, text: string) => {
    queryClient.setQueryData(['agent-config', systemId], value);
    setMessage(text);
  };
  const saveRole = useMutation({
    mutationFn: () => {
      const body = { ...role, pathScope: lines(role.pathScope), modelProfileRef: role.engine === 'fake' ? '' : role.modelProfileRef };
      return roleId ? api.updateAgentRole(systemId, roleId, body) : api.createAgentRole(systemId, body);
    },
    onSuccess: (value) => {
      accept(value, 'Agent 保存成功');
      setRoleId('');
      setRole(emptyRole);
    },
  });
  const deleteRole = useMutation({
    mutationFn: (id: string) => api.deleteAgentRole(systemId, id),
    onSuccess: (value) => accept(value, 'Agent 已删除'),
  });
  const savePolicy = useMutation({
    // 执行模式和默认 Agent 使用同一接口保存，页面不会出现半套策略。
    mutationFn: ({ mode, defaultRoleId }: { mode: 'single' | 'planner_select'; defaultRoleId: string }) =>
      api.updateExecutionPolicy(systemId, mode, defaultRoleId),
    onSuccess: (value) => accept(value, '执行策略已更新'),
  });

  const value = config.data;
  const executionMode = value?.executionMode ?? 'planner_select';
  const defaultRole = value?.agentRoles.find((item) => item.id === value.defaultRoleId);
  if (!systemId) return <div className="empty">请先选择系统。</div>;
  return <section className="agent-config-page">
    <header className="page-head agent-page-head">
      <div><h1>Agent 配置</h1><p>管理代码 Agent 及其协作方式</p></div>
      <span className="config-count">{value?.agentRoles.length ?? 0} 个 Agent</span>
    </header>

    <div className="agent-config-layout">
      <div className="panel execution-agent-panel">
        <div className="config-section-head"><div><h2>代码 Agent</h2><p>选择已有 Profile，配置角色职责和执行边界。</p></div></div>
        <div className="table-frame"><table className="data-table agent-role-table"><thead><tr><th>Agent</th><th>Engine / Profile</th><th>范围</th><th>操作</th></tr></thead><tbody>
          {(value?.agentRoles ?? []).map((item) => <tr key={item.id}>
            <td><strong>{item.name || item.id}</strong>{item.id === value?.defaultRoleId && <span className="default-badge">默认</span>}</td>
            <td>{item.engine} · {profileName(value?.modelProfiles ?? [], item.modelProfileRef)}</td>
            <td>{item.pathScope.join(', ') || '跟随系统'}</td>
            <td><div className="button-row compact-actions">
              <button type="button" className="icon-button" title="编辑 Agent" aria-label={`编辑 ${item.name || item.id}`} onClick={() => editRole(item, setRoleId, setRole)}><Pencil size={16} /></button>
              <button type="button" className="icon-button danger" title="删除 Agent" aria-label={`删除 ${item.name || item.id}`} onClick={() => deleteRole.mutate(item.id)}><Trash2 size={16} /></button>
            </div></td>
          </tr>)}
          {value?.agentRoles.length === 0 && <tr><td className="empty-cell" colSpan={4}>还没有代码 Agent</td></tr>}
        </tbody></table></div>

        <form className="agent-role-editor" onSubmit={(event) => { event.preventDefault(); saveRole.mutate(); }}>
          <div className="config-section-head compact"><div><h3>{roleId ? '编辑 Agent' : '新增 Agent'}</h3><p>Engine 决定执行内核，Profile 提供模型连接。</p></div></div>
          <div className="agent-role-fields">
            <label>Agent 名称<input required value={role.name} onChange={(event) => setRole({ ...role, name: event.target.value })} /></label>
            <label>执行内核<select value={role.engine} onChange={(event) => setRole({ ...role, engine: event.target.value })}>{(value?.engines ?? ['claude_sdk', 'deepagents', 'http', 'fake']).map((engine) => <option key={engine} value={engine}>{engine}</option>)}</select></label>
            {role.engine !== 'fake' && <label>Model Profile<select required value={role.modelProfileRef} onChange={(event) => setRole({ ...role, modelProfileRef: event.target.value })}><option value="">请选择</option>{(value?.modelProfiles ?? []).map((item) => <option key={item.id} value={item.id}>{item.name || item.id}</option>)}</select></label>}
            <label>Path Scope（每行一条）<textarea value={role.pathScope} onChange={(event) => setRole({ ...role, pathScope: event.target.value })} /></label>
            <label className="wide-field">角色提示词<textarea value={role.prompt} onChange={(event) => setRole({ ...role, prompt: event.target.value })} /></label>
            <label>最大轮次<input type="number" min="1" value={role.maxTurns} onChange={(event) => setRole({ ...role, maxTurns: Number(event.target.value) })} /></label>
            <label>超时（秒）<input type="number" min="1" value={role.timeoutSeconds} onChange={(event) => setRole({ ...role, timeoutSeconds: Number(event.target.value) })} /></label>
          </div>
          <div className="button-row"><button type="submit" disabled={saveRole.isPending}><Plus size={16} />{roleId ? '保存 Agent' : '添加 Agent'}</button>{roleId && <button type="button" className="secondary" onClick={() => { setRoleId(''); setRole(emptyRole); }}>取消</button>}</div>
        </form>
      </div>

      <aside className="agent-policy-column">
        <div className="panel agent-policy-panel">
          <div className="config-section-head compact"><div><h2>执行策略</h2><p>只控制代码 Agent 的选择。</p></div></div>
          <fieldset className="execution-options">
            <label className="execution-option"><input type="radio" name="execution-mode" checked={executionMode === 'single'} onChange={() => savePolicy.mutate({ mode: 'single', defaultRoleId: value?.defaultRoleId ?? '' })} /><span><strong>单 Agent</strong><small>始终由默认 Agent 完成代码任务</small></span></label>
            <label className="execution-option"><input type="radio" name="execution-mode" checked={executionMode === 'planner_select'} onChange={() => savePolicy.mutate({ mode: 'planner_select', defaultRoleId: value?.defaultRoleId ?? '' })} /><span><strong>Planner 选择</strong><small>Planner 根据任务分配一个或多个 Agent</small></span></label>
          </fieldset>
          <label>默认 Agent<select value={value?.defaultRoleId ?? ''} onChange={(event) => savePolicy.mutate({ mode: executionMode, defaultRoleId: event.target.value })}><option value="">未设置</option>{(value?.agentRoles ?? []).map((item) => <option key={item.id} value={item.id}>{item.name || item.id}</option>)}</select></label>
        </div>

        <div className="panel execution-path-panel">
          <div className="config-section-head compact"><div><h2>实际执行</h2><p>当前 Agent 路径</p></div></div>
          <div className="execution-path-step"><i /><span>Planning 完成</span></div>
          <div className="execution-path-step"><i /><span>{executionMode === 'single' ? (defaultRole?.name || '默认 Agent 未设置') : 'Planner 按任务选择 Agent'}</span></div>
          <div className="execution-path-step"><i /><span>Patch · 验证 · 发布</span></div>
        </div>
      </aside>
    </div>

    <div className="config-boundary">
      <div><strong>来自模型配置</strong><span>Profile 名称和可用状态</span></div>
      <b aria-hidden="true">引用 →</b>
      <div><strong>Agent 配置拥有</strong><span>Engine · 职责 · 路径 · 策略</span></div>
    </div>

    {message && <div className="success-text">{message}</div>}
    {(config.error || saveRole.error || deleteRole.error || savePolicy.error) && <div className="error-text">配置保存失败</div>}
  </section>;
}

function editRole(item: AgentRole, setId: (value: string) => void, setDraft: (value: RoleDraft) => void) {
  setId(item.id);
  setDraft({ name: item.name, engine: item.engine, modelProfileRef: item.modelProfileRef, pathScope: item.pathScope.join('\n'), prompt: item.prompt, maxTurns: item.maxTurns ?? 50, timeoutSeconds: item.timeoutSeconds ?? 600 });
}

function profileName(profiles: ModelProfile[], id: string) {
  return profiles.find((item) => item.id === id)?.name || (id || '无需模型');
}

function lines(value: string) {
  return value.split('\n').map((item) => item.trim()).filter(Boolean);
}
