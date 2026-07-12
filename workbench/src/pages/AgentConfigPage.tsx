import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { useState } from 'react';
import { KeyRound, Pencil, Plus, Trash2 } from 'lucide-react';
import { AgentConfiguration, AgentRole, api, ModelProfile } from '../api/client';
import { useCurrentSystem } from '../SystemContext';

type ProfileDraft = { name: string; provider: string; model: string; baseUrl: string; apiKey: string };
type RoleDraft = { name: string; engine: string; modelProfileRef: string; pathScope: string; prompt: string; maxTurns: number; timeoutSeconds: number };

const emptyProfile: ProfileDraft = { name: '', provider: 'openai-compat', model: '', baseUrl: '', apiKey: '' };
const emptyRole: RoleDraft = { name: '', engine: 'http', modelProfileRef: '', pathScope: '', prompt: '', maxTurns: 50, timeoutSeconds: 600 };

export function AgentConfigPage() {
  const { systemId } = useCurrentSystem();
  const queryClient = useQueryClient();
  const [profileId, setProfileId] = useState('');
  const [roleId, setRoleId] = useState('');
  const [profile, setProfile] = useState<ProfileDraft>(emptyProfile);
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
  const saveProfile = useMutation({
    mutationFn: () => profileId ? api.updateModelProfile(systemId, profileId, profile) : api.createModelProfile(systemId, profile),
    onSuccess: (value) => { accept(value, '模型 Profile 保存成功'); setProfileId(''); setProfile(emptyProfile); },
  });
  const deleteProfile = useMutation({
    mutationFn: (id: string) => api.deleteModelProfile(systemId, id),
    onSuccess: (value) => accept(value, '模型 Profile 已删除'),
  });
  const saveRole = useMutation({
    mutationFn: () => {
      const body = { ...role, pathScope: lines(role.pathScope), modelProfileRef: role.engine === 'fake' ? '' : role.modelProfileRef };
      return roleId ? api.updateAgentRole(systemId, roleId, body) : api.createAgentRole(systemId, body);
    },
    onSuccess: (value) => { accept(value, 'Agent 角色保存成功'); setRoleId(''); setRole(emptyRole); },
  });
  const deleteRole = useMutation({
    mutationFn: (id: string) => api.deleteAgentRole(systemId, id),
    onSuccess: (value) => accept(value, 'Agent 角色已删除'),
  });
  const setDefault = useMutation({
    mutationFn: (id: string) => api.updateDefaultAgentRole(systemId, id),
    onSuccess: (value) => accept(value, '默认角色已更新'),
  });

  const value = config.data;
  if (!systemId) return <div className="empty">请先选择系统。</div>;
  return <section className="agent-config-page">
    <header className="page-head"><div><h1>Agent 配置</h1><p>{systemId}</p></div></header>
    <div className="panel config-relation-note">
      <h2>三层关系</h2>
      <p>Model Profile 保存模型接入信息；Engine 决定执行内核；Agent Role 把二者与路径范围、提示词和执行限制组合起来。</p>
    </div>

    <div className="panel business-model-panel">
      <div className="config-section-head"><div><h2>Model Profiles</h2><p>API Key 只写入，页面仅显示配置状态。</p></div></div>
      <div className="table-frame"><table className="data-table"><thead><tr><th>名称</th><th>Provider / 模型</th><th>端点</th><th>Key</th><th>操作</th></tr></thead><tbody>
        {(value?.modelProfiles ?? []).map((item) => <tr key={item.id}><td>{item.name || item.id}</td><td>{item.provider} · {item.model}</td><td>{item.baseUrl || '默认'}</td><td><KeyRound size={14} aria-hidden="true" /> {item.apiKeySet ? '已配置' : '未配置'}</td><td><div className="button-row">
          <button type="button" className="icon-button" title="编辑 Profile" onClick={() => editProfile(item, setProfileId, setProfile)}><Pencil size={16} /></button>
          <button type="button" className="icon-button danger" title="删除 Profile" onClick={() => deleteProfile.mutate(item.id)}><Trash2 size={16} /></button>
        </div></td></tr>)}
      </tbody></table></div>
      <form className="form-grid" onSubmit={(event) => { event.preventDefault(); saveProfile.mutate(); }}>
        <label>Profile 名称<input required value={profile.name} onChange={(event) => setProfile({ ...profile, name: event.target.value })} /></label>
        <label>Provider<select value={profile.provider} onChange={(event) => setProfile({ ...profile, provider: event.target.value })}><option value="openai-compat">OpenAI Compatible</option><option value="anthropic">Anthropic</option></select></label>
        <label>模型名称<input required value={profile.model} onChange={(event) => setProfile({ ...profile, model: event.target.value })} /></label>
        <label>Base URL<input value={profile.baseUrl} onChange={(event) => setProfile({ ...profile, baseUrl: event.target.value })} /></label>
        <label>API Key<input type="password" placeholder={profileId ? '留空保留现有 Key' : ''} value={profile.apiKey} onChange={(event) => setProfile({ ...profile, apiKey: event.target.value })} /></label>
        <div className="button-row"><button type="submit"><Plus size={16} />{profileId ? '保存 Profile' : '添加 Profile'}</button>{profileId && <button type="button" className="secondary" onClick={() => { setProfileId(''); setProfile(emptyProfile); }}>取消</button>}</div>
      </form>
    </div>

    <div className="panel execution-agent-panel">
      <div className="config-section-head"><div><h2>Agent Roles</h2><p>Planner 可按顺序分配多个角色，角色之间只交接工件和事件。</p></div></div>
      <label>默认角色<select value={value?.defaultRoleId ?? ''} onChange={(event) => setDefault.mutate(event.target.value)}><option value="">旧配置 / 全局环境回落</option>{(value?.agentRoles ?? []).map((item) => <option key={item.id} value={item.id}>{item.name || item.id}</option>)}</select></label>
      <div className="table-frame"><table className="data-table"><thead><tr><th>角色</th><th>Engine</th><th>Model Profile</th><th>Path Scope</th><th>操作</th></tr></thead><tbody>
        {(value?.agentRoles ?? []).map((item) => <tr key={item.id}><td>{item.name || item.id}</td><td>{item.engine}</td><td>{profileName(value?.modelProfiles ?? [], item.modelProfileRef)}</td><td>{item.pathScope.join(', ') || '跟随系统'}</td><td><div className="button-row">
          <button type="button" className="icon-button" title="编辑角色" onClick={() => editRole(item, setRoleId, setRole)}><Pencil size={16} /></button>
          <button type="button" className="icon-button danger" title="删除角色" onClick={() => deleteRole.mutate(item.id)}><Trash2 size={16} /></button>
        </div></td></tr>)}
      </tbody></table></div>
      <form className="form-grid" onSubmit={(event) => { event.preventDefault(); saveRole.mutate(); }}>
        <label>角色名称<input required value={role.name} onChange={(event) => setRole({ ...role, name: event.target.value })} /></label>
        <label>执行内核<select value={role.engine} onChange={(event) => setRole({ ...role, engine: event.target.value })}>{(value?.engines ?? ['claude_sdk', 'deepagents', 'http', 'fake']).map((engine) => <option key={engine} value={engine}>{engine}</option>)}</select></label>
        {role.engine !== 'fake' && <label>Model Profile<select required value={role.modelProfileRef} onChange={(event) => setRole({ ...role, modelProfileRef: event.target.value })}><option value="">请选择</option>{(value?.modelProfiles ?? []).map((item) => <option key={item.id} value={item.id}>{item.name || item.id}</option>)}</select></label>}
        <label>Path Scope（每行一条）<textarea value={role.pathScope} onChange={(event) => setRole({ ...role, pathScope: event.target.value })} /></label>
        <label>角色提示词<textarea value={role.prompt} onChange={(event) => setRole({ ...role, prompt: event.target.value })} /></label>
        <label>最大轮次<input type="number" min="1" value={role.maxTurns} onChange={(event) => setRole({ ...role, maxTurns: Number(event.target.value) })} /></label>
        <label>超时（秒）<input type="number" min="1" value={role.timeoutSeconds} onChange={(event) => setRole({ ...role, timeoutSeconds: Number(event.target.value) })} /></label>
        <div className="button-row"><button type="submit"><Plus size={16} />{roleId ? '保存角色' : '添加角色'}</button>{roleId && <button type="button" className="secondary" onClick={() => { setRoleId(''); setRole(emptyRole); }}>取消</button>}</div>
      </form>
    </div>
    {message && <div className="success-text">{message}</div>}
    {(config.error || saveProfile.error || saveRole.error) && <div className="error-text">配置保存失败</div>}
  </section>;
}

function editProfile(item: ModelProfile, setId: (value: string) => void, setDraft: (value: ProfileDraft) => void) {
  setId(item.id);
  setDraft({ name: item.name, provider: item.provider, model: item.model, baseUrl: item.baseUrl, apiKey: '' });
}

function editRole(item: AgentRole, setId: (value: string) => void, setDraft: (value: RoleDraft) => void) {
  setId(item.id);
  setDraft({ name: item.name, engine: item.engine, modelProfileRef: item.modelProfileRef, pathScope: item.pathScope.join('\n'), prompt: item.prompt, maxTurns: item.maxTurns ?? 50, timeoutSeconds: item.timeoutSeconds ?? 600 });
}

function profileName(profiles: ModelProfile[], id: string) { return profiles.find((item) => item.id === id)?.name || (id || '-'); }
function lines(value: string) { return value.split('\n').map((item) => item.trim()).filter(Boolean); }
