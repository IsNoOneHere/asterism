import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { useEffect, useRef, useState } from 'react';
import { KeyRound, Pencil, Plus, Trash2 } from 'lucide-react';
import { Agent, AgentConfiguration, api, ModelCapabilityTestResult, ModelConnectionTestResult, ModelProfile } from '../api/client';
import { ActionConfirmDialog } from '../components/ActionConfirmDialog';
import { errorMessage, ErrorState } from '../components/Display';
import { useCurrentSystem } from '../SystemContext';

type ProfileDraft = { name: string; provider: string; model: string; baseUrl: string; apiKey: string; imageInput: boolean; structuredOutput: 'json_schema' | 'json_object' | 'prompt_only' };
type AgentDraft = { name: string; engine: string; modelProfileRef: string; pathScope: string; prompt: string; maxTurns: number; timeoutSeconds: number };

const emptyProfile: ProfileDraft = { name: '', provider: 'openai-compat', model: '', baseUrl: '', apiKey: '', imageInput: false, structuredOutput: 'json_object' };
const emptyAgent: AgentDraft = { name: '', engine: 'claude_sdk_team', modelProfileRef: '', pathScope: '', prompt: '', maxTurns: 50, timeoutSeconds: 600 };
const builtinPurpose: Record<string, string> = { product: 'PRD 对话', vision: '图片理解', developer: 'Claude SDK 团队执行' };

export function AgentConfigPage({ section }: { section: 'models' | 'agents' }) {
  const { systemId, canManageCurrentSystem, systemAccessLoading, systemAccessError } = useCurrentSystem();
  const queryClient = useQueryClient();
  const profileDialogRef = useRef<HTMLDialogElement>(null);
  const agentDialogRef = useRef<HTMLDialogElement>(null);
  const [profileId, setProfileId] = useState('');
  const [profile, setProfile] = useState<ProfileDraft>(emptyProfile);
  const [agentName, setAgentName] = useState('');
  const [agent, setAgent] = useState<AgentDraft>(emptyAgent);
  const [message, setMessage] = useState('');
  const [maxRevisions, setMaxRevisions] = useState(5);
  const [connectionTests, setConnectionTests] = useState<Record<string, ModelConnectionTestResult>>({});
  const [capabilityTests, setCapabilityTests] = useState<Record<string, ModelCapabilityTestResult>>({});
  const [openTestMenu, setOpenTestMenu] = useState('');
  const [deleteTarget, setDeleteTarget] = useState<{ id: string; name: string } | null>(null);
  const config = useQuery({
    queryKey: ['agent-config', systemId],
    queryFn: () => api.agentConfiguration(systemId),
    enabled: Boolean(systemId),
    retry: false,
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
      setConnectionTests({});
      setCapabilityTests({});
      profileDialogRef.current?.close();
    },
  });
  const testCapability = useMutation({
    mutationFn: ({ id, capability }: { id: string; capability: 'structured_output' | 'image_input' }) =>
      api.testModelProfileCapability(systemId, id, capability),
    onSuccess: (result, input) => setCapabilityTests((current) => ({
      ...current, [`${systemId}:${input.id}:${input.capability}`]: result,
    })),
    onError: (error, input) => setCapabilityTests((current) => ({
      ...current, [`${systemId}:${input.id}:${input.capability}`]: { supported: false, message: errorMessage(error) },
    })),
  });
  const testProfile = useMutation({
    mutationFn: (id: string) => api.testModelProfileConnection(systemId, id),
    onSuccess: (result, id) => setConnectionTests((current) => ({ ...current, [`${systemId}:${id}`]: result })),
    onError: (error, id) => setConnectionTests((current) => ({
      ...current, [`${systemId}:${id}`]: { connected: false, message: errorMessage(error) },
    })),
  });
  const deleteProfile = useMutation({
    mutationFn: (id: string) => api.deleteModelProfile(systemId, id),
    onSuccess: (value) => accept(value, '模型 Profile 已删除'),
    onSettled: () => setDeleteTarget(null),
  });
  const saveAgent = useMutation({
    mutationFn: () => {
      const name = agentName;
      const modelOnly = name === 'product' || name === 'vision';
      const body = {
        ...agent,
        name,
        engine: modelOnly ? '' : agent.engine,
        pathScope: modelOnly ? [] : lines(agent.pathScope),
        prompt: modelOnly ? '' : agent.prompt,
        maxTurns: modelOnly ? undefined : agent.maxTurns,
        timeoutSeconds: modelOnly ? undefined : agent.timeoutSeconds,
      };
      return api.updateAgent(systemId, agentName, body);
    },
    onSuccess: (value) => {
      accept(value, 'Agent 保存成功');
      agentDialogRef.current?.close();
    },
  });
  const value = config.data;
  useEffect(() => {
    if (value?.maxRevisions) setMaxRevisions(value.maxRevisions);
  }, [value?.maxRevisions]);
  useEffect(() => {
    setOpenTestMenu('');
  }, [section, systemId]);
  const saveSettings = useMutation({
    mutationFn: () => api.updateExecutionSettings(systemId, { maxRevisions }),
    onSuccess: (next) => accept(next, '执行策略保存成功'),
  });
  const modelSection = section === 'models';
  const modelOnly = agentName === 'product' || agentName === 'vision';
  const openProfile = (item?: ModelProfile) => {
    saveProfile.reset();
    setProfileId(item?.id ?? '');
    setProfile(item
      ? { name: item.name, provider: item.provider, model: item.model, baseUrl: item.baseUrl, apiKey: '',
          imageInput: Boolean(item.imageInput ?? item.supportsVision), structuredOutput: item.structuredOutput || 'json_object' }
      : emptyProfile);
    // 原生 dialog 负责焦点约束和遮罩，不引入额外弹窗库。
    profileDialogRef.current?.showModal();
  };
  const openAgent = (item: Agent) => {
    saveAgent.reset();
    setAgentName(item.name);
    setAgent(
      { name: item.name, engine: item.engine || 'claude_sdk_team', modelProfileRef: item.modelProfileRef,
        pathScope: item.pathScope.join('\n'), prompt: item.prompt, maxTurns: item.maxTurns ?? 50,
        timeoutSeconds: item.timeoutSeconds ?? 600 });
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
    {!systemAccessLoading && !systemAccessError && !canManageCurrentSystem && <div className="notice">当前账号在此系统中为只读成员，配置操作已禁用。</div>}
    {/* 保存反馈固定在列表前，避免被长列表推到页面底部。 */}
    {message && <div className="success-text" role="status">{message}</div>}

    {config.isLoading ? <div className="panel empty" role="status">配置加载中…</div> : config.isError ?
    <ErrorState title="Agent 配置加载失败" error={config.error} onRetry={() => config.refetch()} /> : modelSection ? <div className="panel business-model-panel">
        <div className="config-section-head">
          <div><h2>模型列表</h2><p>API Key 只维护一次，页面不会回显明文。</p></div>
          <button type="button" className="icon-text-button" disabled={!canManageCurrentSystem} onClick={() => openProfile()}><Plus size={16} />新增 Profile</button>
        </div>
        <div className={`table-frame model-profile-table-frame${openTestMenu ? ' menu-open' : ''}`}><table className="data-table model-profile-table"><thead><tr><th>名称</th><th>协议 / 模型</th><th>状态</th><th>操作</th></tr></thead><tbody>
          {(value?.modelProfiles ?? []).map((item) => {
            const result = connectionTests[`${systemId}:${item.id}`];
            const pending = testProfile.isPending && testProfile.variables === item.id;
            const structure = capabilityTests[`${systemId}:${item.id}:structured_output`];
            const image = capabilityTests[`${systemId}:${item.id}:image_input`];
            return <tr key={item.id}>
            <td title={`${item.name || item.id} · ${item.baseUrl || '默认端点'}`}><strong>{item.name || item.id}</strong><small>{item.baseUrl || '默认端点'}</small></td>
            <td title={`${providerName(item.provider)} · ${item.model}`}>{providerName(item.provider)} · {item.model}</td>
            <td><span className={`key-status ${item.apiKeySet ? 'configured' : ''}`}><KeyRound size={14} aria-hidden="true" />{item.apiKeySet ? 'Key 已配置' : 'Key 未配置'}{item.imageInput ? ' · 图片' : ''} · {structuredOutputName(item.structuredOutput)}</span></td>
            <td className="config-action-cell table-action-cell"><div className="button-row compact-actions">
              {/* 多种诊断动作收进同一菜单，避免操作列被横向按钮挤满。 */}
              <div className="row-action-menu profile-test-menu" onBlur={(event) => {
                if (!event.currentTarget.contains(event.relatedTarget as Node | null)) setOpenTestMenu('');
              }} onKeyDown={(event) => {
                if (event.key === 'Escape') {
                  setOpenTestMenu('');
                  event.currentTarget.querySelector('button')?.focus();
                }
              }}>
                <button type="button" className="secondary profile-test-menu-trigger"
                  aria-label={`测试 ${item.name || item.id}`} aria-haspopup="menu"
                  aria-expanded={openTestMenu === item.id}
                  onClick={() => setOpenTestMenu((current) => current === item.id ? '' : item.id)}>测试</button>
                {openTestMenu === item.id && <div className="system-select-menu row-action-menu-panel profile-test-menu-panel"
                  role="menu" aria-label={`${item.name || item.id} 的测试操作`}>
                  <button type="button" role="menuitem"
                    className={`system-select-option row-action-menu-item connection-test ${result ? result.connected ? 'connected' : 'failed' : ''}`}
                    aria-label={`测试 ${item.name || item.id}连通性`} aria-live="polite"
                    title={result?.message || '测试模型连通性'}
                    disabled={!canManageCurrentSystem || testProfile.isPending}
                    onClick={() => testProfile.mutate(item.id)}>
                    {pending ? '测试中…' : result ? result.connected ? '连接正常' : '连接失败' : '测试连接'}
                  </button>
                  <CapabilityTestButton profile={item} capability="structured_output" result={structure}
                    pending={testCapability.isPending && testCapability.variables?.id === item.id && testCapability.variables.capability === 'structured_output'}
                    disabled={!canManageCurrentSystem || testCapability.isPending}
                    onClick={() => testCapability.mutate({ id: item.id, capability: 'structured_output' })} />
                  {item.imageInput && <CapabilityTestButton profile={item} capability="image_input" result={image}
                    pending={testCapability.isPending && testCapability.variables?.id === item.id && testCapability.variables.capability === 'image_input'}
                    disabled={!canManageCurrentSystem || testCapability.isPending}
                    onClick={() => testCapability.mutate({ id: item.id, capability: 'image_input' })} />}
                </div>}
              </div>
              <button type="button" className="icon-button" title="编辑 Profile" aria-label={`编辑 ${item.name || item.id}`} disabled={!canManageCurrentSystem} onClick={() => openProfile(item)}><Pencil size={16} /></button>
              <button type="button" className="icon-button danger" title="删除 Profile" aria-label={`删除 ${item.name || item.id}`} disabled={!canManageCurrentSystem} onClick={() => { deleteProfile.reset(); setDeleteTarget({ id: item.id, name: item.name || item.id }); }}><Trash2 size={16} /></button>
            </div></td>
          </tr>})}
          {value?.modelProfiles.length === 0 && <tr><td className="empty-cell" colSpan={4}>还没有模型连接</td></tr>}
        </tbody></table></div>
      </div> : <div className="agent-config-stack">
      <div className="panel execution-policy-panel">
        <div><h2>修订策略</h2><p>代码或 MR 被人工打回后，Agent 会自动带意见修订；达到上限后交由负责人决定取消或完整重做。</p></div>
        <label>最大修订轮次<input aria-label="最大修订轮次" type="number" min="1" max="20" value={maxRevisions} onChange={(event) => setMaxRevisions(Number(event.target.value))} /></label>
        <button type="button" disabled={!canManageCurrentSystem || saveSettings.isPending || maxRevisions < 1 || maxRevisions > 20} onClick={() => saveSettings.mutate()}>{saveSettings.isPending ? '保存中…' : '保存策略'}</button>
        {saveSettings.error && <div className="error-text">{errorMessage(saveSettings.error)}</div>}
      </div>
      <div className="panel execution-agent-panel">
        <div className="config-section-head">
          <div><h2>Agent 列表</h2><p>product 负责需求沟通；developer 通过 Claude SDK Supervisor 自动创建仓库子 Agent。</p></div>
        </div>
        {value?.migration?.migrated && <div className="notice">旧执行内核 {value.migration.from.join('、')} 已迁移为 claude_sdk_team。</div>}
        <div className="table-frame"><table className="data-table agent-role-table"><thead><tr><th>Agent</th><th>Engine / Profile</th><th>范围</th><th>操作</th></tr></thead><tbody>
          {(value?.agents ?? []).map((item) => <tr key={item.name}>
            <td title={item.name}><strong>{item.name}</strong>{item.kind === 'builtin' && <span className="default-badge">内置 · {builtinPurpose[item.name]}</span>}</td>
            <td title={agentRuntimeLabel(item, value?.modelProfiles ?? [])}>{agentRuntimeLabel(item, value?.modelProfiles ?? [])}</td>
            <td title={item.pathScope.join(', ') || (item.name !== 'developer' ? '不执行代码' : '跟随系统')}>{item.pathScope.join(', ') || (item.name !== 'developer' ? '不执行代码' : '跟随系统')}</td>
            <td className="config-action-cell table-action-cell"><div className="button-row compact-actions">
              <button type="button" className="icon-button" title="编辑 Agent" aria-label={`编辑 ${item.name}`} disabled={!canManageCurrentSystem} onClick={() => openAgent(item)}><Pencil size={16} /></button>
            </div></td>
          </tr>)}
        </tbody></table></div>
      </div></div>}

    {modelSection && <dialog ref={profileDialogRef} className="confirm-dialog config-dialog" aria-labelledby="profile-dialog-title" onClose={() => { setProfileId(''); setProfile(emptyProfile); }}>
      <form onSubmit={(event) => { event.preventDefault(); saveProfile.mutate(); }}>
        <div className="config-section-head compact"><div><h2 id="profile-dialog-title">{profileId ? '编辑 Model Profile' : '新增 Model Profile'}</h2><p>{profileId ? 'API Key 留空时保留已有值。' : 'API Key 只写入，不会在页面回显。'}</p></div></div>
        <div className="config-dialog-fields">
          <label>Profile 名称<input required value={profile.name} onChange={(event) => setProfile({ ...profile, name: event.target.value })} /></label>
          <label>Provider<select value={profile.provider} onChange={(event) => setProfile({ ...profile, provider: event.target.value })}><option value="openai-compat">OpenAI Compatible</option><option value="anthropic">Anthropic</option></select></label>
          <label>模型名称<input required value={profile.model} onChange={(event) => setProfile({ ...profile, model: event.target.value })} /></label>
          <label>Base URL<input value={profile.baseUrl} onChange={(event) => setProfile({ ...profile, baseUrl: event.target.value })} /></label>
          <label>API Key<input type="password" autoComplete="new-password" placeholder={profileId ? '留空保留现有 Key' : ''} value={profile.apiKey} onChange={(event) => setProfile({ ...profile, apiKey: event.target.value })} /></label>
          <label>结构化输出<select value={profile.structuredOutput} onChange={(event) => setProfile({ ...profile, structuredOutput: event.target.value as ProfileDraft['structuredOutput'] })}><option value="json_schema">JSON Schema</option><option value="json_object">JSON Object</option><option value="prompt_only">仅提示词</option></select></label>
          <label className="checkbox-field"><input type="checkbox" checked={profile.imageInput} onChange={(event) => setProfile({ ...profile, imageInput: event.target.checked })} /><span>支持图片输入</span></label>
        </div>
        {saveProfile.error && <div className="error-text">{errorMessage(saveProfile.error)}</div>}
        <div className="button-row"><button type="button" className="secondary" onClick={() => profileDialogRef.current?.close()}>取消</button><button type="submit" disabled={!canManageCurrentSystem || saveProfile.isPending}>保存 Profile</button></div>
      </form>
    </dialog>}

    {!modelSection && <dialog ref={agentDialogRef} className="confirm-dialog config-dialog agent-dialog" aria-labelledby="agent-dialog-title" onClose={resetAgent}>
      <form onSubmit={(event) => { event.preventDefault(); saveAgent.mutate(); }}>
        <div className="config-section-head compact"><div><h2 id="agent-dialog-title">编辑 {agentName}</h2><p>{modelOnly ? '内置沟通 Agent 只选择 Model Profile。' : '执行使用计划审批和 Temporal 恢复，不再按秒强制截断。'}</p></div></div>
        <div className="agent-role-fields">
          <label>Agent 名称<input required disabled value={agent.name} /></label>
          {!modelOnly && <label>执行内核<select value={agent.engine} onChange={(event) => setAgent({ ...agent, engine: event.target.value })}>{agentEngineOptions(value?.engines).map((engine) => <option key={engine} value={engine}>{engine}</option>)}</select></label>}
          <label>Model Profile<select required={agentName === 'vision'} value={agent.modelProfileRef} onChange={(event) => setAgent({ ...agent, modelProfileRef: event.target.value })}><option value="">{agentName === 'vision' ? '请选择图片模型' : '回落部署默认'}</option>{profileOptions(value?.modelProfiles, agentName === 'vision')}</select></label>
          {!modelOnly && <><label>Path Scope（每行一条）<textarea value={agent.pathScope} onChange={(event) => setAgent({ ...agent, pathScope: event.target.value })} /></label>
            <label className="wide-field">Supervisor 提示词<textarea value={agent.prompt} onChange={(event) => setAgent({ ...agent, prompt: event.target.value })} /></label>
            <label>最大轮次<input type="number" min="1" value={agent.maxTurns} onChange={(event) => setAgent({ ...agent, maxTurns: Number(event.target.value) })} /></label></>}
        </div>
        {saveAgent.error && <div className="error-text">{errorMessage(saveAgent.error)}</div>}
        <div className="button-row"><button type="button" className="secondary" onClick={() => agentDialogRef.current?.close()}>取消</button><button type="submit" disabled={!canManageCurrentSystem || saveAgent.isPending}>保存 Agent</button></div>
      </form>
    </dialog>}
    <ActionConfirmDialog
      open={Boolean(deleteTarget)}
      title="删除模型 Profile？"
      description={`“${deleteTarget?.name || ''}”删除后无法恢复。`}
      confirmLabel="确认删除"
      pending={deleteProfile.isPending}
      onClose={() => setDeleteTarget(null)}
      onConfirm={() => {
        if (deleteTarget) deleteProfile.mutate(deleteTarget.id);
      }}
    />
    <ActionConfirmDialog
      open={Boolean(deleteProfile.error)}
      title="删除失败"
      description={errorMessage(deleteProfile.error)}
      confirmLabel="知道了"
      alert
      showCancel={false}
      onClose={() => deleteProfile.reset()}
      onConfirm={() => deleteProfile.reset()}
    />
  </section>;
}

function profileOptions(profiles: ModelProfile[] | undefined, imageOnly = false) {
  return (profiles ?? []).filter((item) => !imageOnly || item.imageInput).map((item) => <option key={item.id} value={item.id}>{item.name || item.id}</option>);
}

function CapabilityTestButton({ profile, capability, result, pending, disabled, onClick }: {
  profile: ModelProfile;
  capability: 'structured_output' | 'image_input';
  result?: ModelCapabilityTestResult;
  pending: boolean;
  disabled: boolean;
  onClick: () => void;
}) {
  const label = capability === 'structured_output' ? '结构化' : '图片';
  return <button type="button" role="menuitem"
    className={`system-select-option row-action-menu-item connection-test ${result ? result.supported ? 'connected' : 'failed' : ''}`}
    aria-label={`测试 ${profile.name || profile.id}${label}能力`} aria-live="polite"
    title={result ? `${result.message}${result.checkedAt ? ` · ${result.checkedAt}` : ''}` : `测试${label}能力`}
    disabled={disabled} onClick={onClick}>
    {pending ? '测试中…' : result ? result.supported ? `${label}正常` : `${label}失败` : `测${label}`}
  </button>;
}

function profileName(profiles: ModelProfile[], id: string) {
  return profiles.find((item) => item.id === id)?.name || (id || '部署默认');
}

function agentRuntimeLabel(agent: Agent, profiles: ModelProfile[]) {
  return `${agent.engine ? `${agent.engine} · ` : ''}${profileName(profiles, agent.modelProfileRef)}`;
}

function agentEngineOptions(engines: string[] | undefined) {
  return engines ?? ['claude_sdk_team', 'fake'];
}

function providerName(provider: string) {
  return provider === 'openai-compat' ? 'OpenAI Compatible' : 'Anthropic';
}

function structuredOutputName(value: ModelProfile['structuredOutput']) {
  return value === 'json_schema' ? 'Schema' : value === 'prompt_only' ? 'Prompt' : 'JSON';
}

function lines(value: string) {
  return value.split('\n').map((item) => item.trim()).filter(Boolean);
}
