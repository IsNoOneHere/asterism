import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { useRef, useState } from 'react';
import { KeyRound, Pencil, Plus, Trash2 } from 'lucide-react';
import { Agent, AgentConfiguration, api, ModelProfile } from '../api/client';
import { useCurrentSystem } from '../SystemContext';

type ProfileDraft = { name: string; provider: string; model: string; baseUrl: string; apiKey: string; supportsVision: boolean };
type AgentDraft = { name: string; engine: string; modelProfileRef: string; pathScope: string; prompt: string; maxTurns: number; timeoutSeconds: number };

const emptyProfile: ProfileDraft = { name: '', provider: 'openai-compat', model: '', baseUrl: '', apiKey: '', supportsVision: false };
const emptyAgent: AgentDraft = { name: '', engine: 'http', modelProfileRef: '', pathScope: '', prompt: '', maxTurns: 50, timeoutSeconds: 600 };
const builtinPurpose: Record<string, string> = { product: 'PRD 对话', planner: '执行规划', developer: '默认执行' };

export function AgentConfigPage({ section }: { section: 'models' | 'agents' }) {
  const { systemId } = useCurrentSystem();
  const queryClient = useQueryClient();
  const profileDialogRef = useRef<HTMLDialogElement>(null);
  const agentDialogRef = useRef<HTMLDialogElement>(null);
  const [profileId, setProfileId] = useState('');
  const [profile, setProfile] = useState<ProfileDraft>(emptyProfile);
  const [agentName, setAgentName] = useState('');
  const [agent, setAgent] = useState<AgentDraft>(emptyAgent);
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
    mutationFn: () => profileId
      ? api.updateModelProfile(systemId, profileId, profile)
      : api.createModelProfile(systemId, profile),
    onSuccess: (value) => {
      accept(value, '模型 Profile 保存成功');
      profileDialogRef.current?.close();
    },
  });
  const deleteProfile = useMutation({
    mutationFn: (id: string) => api.deleteModelProfile(systemId, id),
    onSuccess: (value) => accept(value, '模型 Profile 已删除'),
  });
  const saveAgent = useMutation({
    mutationFn: () => {
      const name = agentName || agent.name.trim();
      const modelOnly = name === 'product' || name === 'planner';
      const body = {
        ...agent,
        name,
        engine: modelOnly ? '' : agent.engine,
        pathScope: modelOnly ? [] : lines(agent.pathScope),
        prompt: modelOnly ? '' : agent.prompt,
        maxTurns: modelOnly ? undefined : agent.maxTurns,
        timeoutSeconds: modelOnly ? undefined : agent.timeoutSeconds,
      };
      return agentName ? api.updateAgent(systemId, agentName, body) : api.createAgent(systemId, body);
    },
    onSuccess: (value) => {
      accept(value, 'Agent 保存成功');
      agentDialogRef.current?.close();
    },
  });
  const deleteAgent = useMutation({
    mutationFn: (name: string) => api.deleteAgent(systemId, name),
    onSuccess: (value) => accept(value, 'Agent 已删除'),
  });

  const value = config.data;
  const modelSection = section === 'models';
  const modelOnly = agentName === 'product' || agentName === 'planner';
  const openProfile = (item?: ModelProfile) => {
    setProfileId(item?.id ?? '');
    setProfile(item
      ? { name: item.name, provider: item.provider, model: item.model, baseUrl: item.baseUrl, apiKey: '', supportsVision: Boolean(item.supportsVision) }
      : emptyProfile);
    // 原生 dialog 负责焦点约束和遮罩，不引入额外弹窗库。
    profileDialogRef.current?.showModal();
  };
  const openAgent = (item?: Agent) => {
    setAgentName(item?.name ?? '');
    setAgent(item
      ? { name: item.name, engine: item.engine || 'http', modelProfileRef: item.modelProfileRef,
        pathScope: item.pathScope.join('\n'), prompt: item.prompt, maxTurns: item.maxTurns ?? 50,
        timeoutSeconds: item.timeoutSeconds ?? 600 }
      : emptyAgent);
    agentDialogRef.current?.showModal();
  };
  const resetAgent = () => {
    setAgentName('');
    setAgent(emptyAgent);
  };

  if (!systemId) return <div className="empty">请先选择系统。</div>;
  return <section className="agent-config-page">
    <header className="page-head agent-page-head">
      <div><h1>{modelSection ? '模型配置' : 'Agent 配置'}</h1><p>{modelSection ? '维护模型接入地址和凭证' : '维护 Agent 使用的模型和执行范围'}</p></div>
      <span className="config-count">{modelSection ? `${value?.modelProfiles.length ?? 0} 个模型` : `${value?.agents.length ?? 0} 个 Agent`}</span>
    </header>

    {modelSection ? <div className="panel business-model-panel">
        <div className="config-section-head">
          <div><h2>模型列表</h2><p>API Key 只维护一次，页面不会回显明文。</p></div>
          <button type="button" className="icon-text-button" onClick={() => openProfile()}><Plus size={16} />新增 Profile</button>
        </div>
        <div className="table-frame"><table className="data-table model-profile-table"><thead><tr><th>名称</th><th>协议 / 模型</th><th>状态</th><th>操作</th></tr></thead><tbody>
          {(value?.modelProfiles ?? []).map((item) => <tr key={item.id}>
            <td><strong>{item.name || item.id}</strong><small>{item.baseUrl || '默认端点'}</small></td>
            <td>{providerName(item.provider)} · {item.model}</td>
            <td><span className={`key-status ${item.apiKeySet ? 'configured' : ''}`}><KeyRound size={14} aria-hidden="true" />{item.apiKeySet ? 'Key 已配置' : 'Key 未配置'}{item.supportsVision ? ' · Vision' : ''}</span></td>
            <td className="config-action-cell"><div className="button-row compact-actions">
              <button type="button" className="icon-button" title="编辑 Profile" aria-label={`编辑 ${item.name || item.id}`} onClick={() => openProfile(item)}><Pencil size={16} /></button>
              <button type="button" className="icon-button danger" title="删除 Profile" aria-label={`删除 ${item.name || item.id}`} onClick={() => deleteProfile.mutate(item.id)}><Trash2 size={16} /></button>
            </div></td>
          </tr>)}
          {value?.modelProfiles.length === 0 && <tr><td className="empty-cell" colSpan={4}>还没有模型连接</td></tr>}
        </tbody></table></div>
      </div> : <div className="panel execution-agent-panel">
        <div className="config-section-head">
          <div><h2>Agent 列表</h2><p>内置 Agent 固定置顶，自定义 Agent 供 Planner 按名称分配。</p></div>
          <button type="button" className="icon-text-button" onClick={() => openAgent()}><Plus size={16} />新增 Agent</button>
        </div>
        <div className="table-frame"><table className="data-table agent-role-table"><thead><tr><th>Agent</th><th>Engine / Profile</th><th>范围</th><th>操作</th></tr></thead><tbody>
          {(value?.agents ?? []).map((item) => <tr key={item.name}>
            <td><strong>{item.name}</strong>{item.kind === 'builtin' && <span className="default-badge">内置 · {builtinPurpose[item.name]}</span>}</td>
            <td>{item.engine ? `${item.engine} · ` : ''}{profileName(value?.modelProfiles ?? [], item.modelProfileRef)}</td>
            <td>{item.pathScope.join(', ') || (item.name === 'product' || item.name === 'planner' ? '不执行代码' : '跟随系统')}</td>
            <td className="config-action-cell"><div className="button-row compact-actions">
              <button type="button" className="icon-button" title="编辑 Agent" aria-label={`编辑 ${item.name}`} onClick={() => openAgent(item)}><Pencil size={16} /></button>
              {item.kind === 'custom' && <button type="button" className="icon-button danger" title="删除 Agent" aria-label={`删除 ${item.name}`} onClick={() => deleteAgent.mutate(item.name)}><Trash2 size={16} /></button>}
            </div></td>
          </tr>)}
        </tbody></table></div>
      </div>}

    {message && <div className="success-text">{message}</div>}
    {(config.error || (modelSection ? deleteProfile.error : deleteAgent.error)) && <div className="error-text">{errorMessage(config.error || (modelSection ? deleteProfile.error : deleteAgent.error))}</div>}

    {modelSection && <dialog ref={profileDialogRef} className="confirm-dialog config-dialog" aria-labelledby="profile-dialog-title" onClose={() => { setProfileId(''); setProfile(emptyProfile); }}>
      <form onSubmit={(event) => { event.preventDefault(); saveProfile.mutate(); }}>
        <div className="config-section-head compact"><div><h2 id="profile-dialog-title">{profileId ? '编辑 Model Profile' : '新增 Model Profile'}</h2><p>{profileId ? 'API Key 留空时保留已有值。' : 'API Key 只写入，不会在页面回显。'}</p></div></div>
        <div className="config-dialog-fields">
          <label>Profile 名称<input required value={profile.name} onChange={(event) => setProfile({ ...profile, name: event.target.value })} /></label>
          <label>Provider<select value={profile.provider} onChange={(event) => setProfile({ ...profile, provider: event.target.value })}><option value="openai-compat">OpenAI Compatible</option><option value="anthropic">Anthropic</option></select></label>
          <label>模型名称<input required value={profile.model} onChange={(event) => setProfile({ ...profile, model: event.target.value })} /></label>
          <label>Base URL<input value={profile.baseUrl} onChange={(event) => setProfile({ ...profile, baseUrl: event.target.value })} /></label>
          <label>API Key<input type="password" autoComplete="new-password" placeholder={profileId ? '留空保留现有 Key' : ''} value={profile.apiKey} onChange={(event) => setProfile({ ...profile, apiKey: event.target.value })} /></label>
          <label className="checkbox-field"><input type="checkbox" checked={profile.supportsVision} onChange={(event) => setProfile({ ...profile, supportsVision: event.target.checked })} /><span>支持图片理解</span></label>
        </div>
        {saveProfile.error && <div className="error-text">{errorMessage(saveProfile.error)}</div>}
        <div className="button-row"><button type="button" className="secondary" onClick={() => profileDialogRef.current?.close()}>取消</button><button type="submit" disabled={saveProfile.isPending}>保存 Profile</button></div>
      </form>
    </dialog>}

    {!modelSection && <dialog ref={agentDialogRef} className="confirm-dialog config-dialog agent-dialog" aria-labelledby="agent-dialog-title" onClose={resetAgent}>
      <form onSubmit={(event) => { event.preventDefault(); saveAgent.mutate(); }}>
        <div className="config-section-head compact"><div><h2 id="agent-dialog-title">{agentName ? `编辑 ${agentName}` : '新增 Agent'}</h2><p>{modelOnly ? '内置沟通 Agent 只选择 Model Profile。' : '执行参数在 Case 启动时固定。'}</p></div></div>
        <div className="agent-role-fields">
          <label>Agent 名称<input required disabled={Boolean(agentName)} value={agent.name} onChange={(event) => setAgent({ ...agent, name: event.target.value })} /></label>
          {!modelOnly && <label>执行内核<select value={agent.engine} onChange={(event) => setAgent({ ...agent, engine: event.target.value })}>{(value?.engines ?? ['claude_sdk', 'deepagents', 'http', 'fake']).map((engine) => <option key={engine} value={engine}>{engine}</option>)}</select></label>}
          <label>Model Profile<select value={agent.modelProfileRef} onChange={(event) => setAgent({ ...agent, modelProfileRef: event.target.value })}><option value="">回落部署默认</option>{profileOptions(value?.modelProfiles)}</select></label>
          {!modelOnly && <><label>Path Scope（每行一条）<textarea value={agent.pathScope} onChange={(event) => setAgent({ ...agent, pathScope: event.target.value })} /></label>
            <label className="wide-field">Agent 提示词<textarea value={agent.prompt} onChange={(event) => setAgent({ ...agent, prompt: event.target.value })} /></label>
            <label>最大轮次<input type="number" min="1" value={agent.maxTurns} onChange={(event) => setAgent({ ...agent, maxTurns: Number(event.target.value) })} /></label>
            <label>超时（秒）<input type="number" min="1" value={agent.timeoutSeconds} onChange={(event) => setAgent({ ...agent, timeoutSeconds: Number(event.target.value) })} /></label></>}
        </div>
        {saveAgent.error && <div className="error-text">{errorMessage(saveAgent.error)}</div>}
        <div className="button-row"><button type="button" className="secondary" onClick={() => agentDialogRef.current?.close()}>取消</button><button type="submit" disabled={saveAgent.isPending}>{agentName ? '保存 Agent' : '添加 Agent'}</button></div>
      </form>
    </dialog>}
  </section>;
}

function profileOptions(profiles: ModelProfile[] | undefined) {
  return (profiles ?? []).map((item) => <option key={item.id} value={item.id}>{item.name || item.id}</option>);
}

function profileName(profiles: ModelProfile[], id: string) {
  return profiles.find((item) => item.id === id)?.name || (id || '部署默认');
}

function providerName(provider: string) {
  return provider === 'openai-compat' ? 'OpenAI Compatible' : 'Anthropic';
}

function lines(value: string) {
  return value.split('\n').map((item) => item.trim()).filter(Boolean);
}

function errorMessage(error: unknown) {
  return error instanceof Error ? error.message : '配置保存失败';
}
