import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { useEffect, useRef, useState } from 'react';
import { KeyRound, Pencil, Plus, Trash2 } from 'lucide-react';
import { AgentConfiguration, api, ModelProfile, ModelRouting } from '../api/client';
import { useCurrentSystem } from '../SystemContext';

type ProfileDraft = { name: string; provider: string; model: string; baseUrl: string; apiKey: string };

const emptyProfile: ProfileDraft = { name: '', provider: 'openai-compat', model: '', baseUrl: '', apiKey: '' };
const emptyRouting: ModelRouting = { defaultProfileId: '', prdProfileId: '', planningProfileId: '', diffProfileId: '' };

export function ModelConfigPage() {
  const { systemId } = useCurrentSystem();
  const queryClient = useQueryClient();
  const dialogRef = useRef<HTMLDialogElement>(null);
  const [profileId, setProfileId] = useState('');
  const [profile, setProfile] = useState<ProfileDraft>(emptyProfile);
  const [routing, setRouting] = useState<ModelRouting>(emptyRouting);
  const [message, setMessage] = useState('');
  const config = useQuery({
    queryKey: ['agent-config', systemId],
    queryFn: () => api.agentConfiguration(systemId),
    enabled: Boolean(systemId),
  });

  useEffect(() => {
    setRouting(config.data?.modelRouting ?? emptyRouting);
  }, [config.data?.modelRouting]);

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
      dialogRef.current?.close();
    },
  });
  const deleteProfile = useMutation({
    mutationFn: (id: string) => api.deleteModelProfile(systemId, id),
    onSuccess: (value) => accept(value, '模型 Profile 已删除'),
  });
  const saveRouting = useMutation({
    mutationFn: () => api.updateModelRouting(systemId, routing),
    onSuccess: (value) => accept(value, '阶段默认模型已更新'),
  });

  const value = config.data;
  const openEditor = (item?: ModelProfile) => {
    setProfileId(item?.id ?? '');
    setProfile(item
      ? { name: item.name, provider: item.provider, model: item.model, baseUrl: item.baseUrl, apiKey: '' }
      : emptyProfile);
    // 原生 dialog 负责居中、焦点约束和遮罩，不引入额外弹窗库。
    dialogRef.current?.showModal();
  };

  if (!systemId) return <div className="empty">请先选择系统。</div>;
  return <section className="agent-config-page">
    <header className="page-head agent-page-head">
      <div><h1>模型配置</h1><p>统一管理模型连接和内置阶段默认值</p></div>
      <span className="config-count">{value?.modelProfiles.length ?? 0} 个模型连接</span>
    </header>

    <div className="model-config-layout">
      <div className="panel business-model-panel">
        <div className="config-section-head">
          <div><h2>模型连接</h2><p>API Key 只在这里维护一次，页面不会回显明文。</p></div>
          <button type="button" className="icon-text-button" onClick={() => openEditor()}><Plus size={16} />新增 Profile</button>
        </div>
        <div className="table-frame"><table className="data-table model-profile-table"><thead><tr><th>名称</th><th>协议 / 模型</th><th>端点</th><th>状态</th><th>操作</th></tr></thead><tbody>
          {(value?.modelProfiles ?? []).map((item) => <tr key={item.id}>
            <td><strong>{item.name || item.id}</strong></td>
            <td>{providerName(item.provider)} · {item.model}</td>
            <td>{item.baseUrl || '默认端点'}</td>
            <td><span className={`key-status ${item.apiKeySet ? 'configured' : ''}`}><KeyRound size={14} aria-hidden="true" />{item.apiKeySet ? 'Key 已配置' : 'Key 未配置'}</span></td>
            <td><div className="button-row compact-actions">
              <button type="button" className="icon-button" title="编辑 Profile" aria-label={`编辑 ${item.name || item.id}`} onClick={() => openEditor(item)}><Pencil size={16} /></button>
              <button type="button" className="icon-button danger" title="删除 Profile" aria-label={`删除 ${item.name || item.id}`} onClick={() => deleteProfile.mutate(item.id)}><Trash2 size={16} /></button>
            </div></td>
          </tr>)}
          {value?.modelProfiles.length === 0 && <tr><td className="empty-cell" colSpan={5}>还没有模型连接</td></tr>}
        </tbody></table></div>
      </div>

      <aside className="panel model-routing-panel">
        <div className="config-section-head compact"><div><h2>阶段默认模型</h2><p>内置服务只引用 Profile。</p></div></div>
        <label>系统默认<select value={routing.defaultProfileId} onChange={(event) => setRouting({ ...routing, defaultProfileId: event.target.value })}>
          <option value="">未设置</option>{profileOptions(value?.modelProfiles)}
        </select></label>
        <label>PRD 沟通<select value={routing.prdProfileId} onChange={(event) => setRouting({ ...routing, prdProfileId: event.target.value })}>
          <option value="">跟随系统默认</option>{profileOptions(value?.modelProfiles)}
        </select></label>
        <label>方案规划<select value={routing.planningProfileId} onChange={(event) => setRouting({ ...routing, planningProfileId: event.target.value })}>
          <option value="">跟随系统默认</option>{profileOptions(value?.modelProfiles)}
        </select></label>
        <label>代码 Diff<select value={routing.diffProfileId} onChange={(event) => setRouting({ ...routing, diffProfileId: event.target.value })}>
          <option value="">跟随系统默认</option>{profileOptions(value?.modelProfiles)}
        </select></label>
        <button type="button" disabled={saveRouting.isPending} onClick={() => saveRouting.mutate()}>保存阶段配置</button>
      </aside>
    </div>

    <div className="config-boundary">
      <div><strong>模型配置拥有</strong><span>模型 · 端点 · API Key</span></div>
      <b aria-hidden="true">Profile ID →</b>
      <div><strong>Agent 配置引用</strong><span>不重复保存模型和密钥</span></div>
    </div>

    {message && <div className="success-text">{message}</div>}
    {(config.error || deleteProfile.error || saveRouting.error) && <div className="error-text">{errorMessage(config.error || deleteProfile.error || saveRouting.error)}</div>}

    <dialog ref={dialogRef} className="confirm-dialog config-dialog" aria-labelledby="profile-dialog-title" onClose={() => { setProfileId(''); setProfile(emptyProfile); }}>
      <form onSubmit={(event) => { event.preventDefault(); saveProfile.mutate(); }}>
        <div className="config-section-head compact">
          <div><h2 id="profile-dialog-title">{profileId ? '编辑 Model Profile' : '新增 Model Profile'}</h2><p>{profileId ? 'API Key 留空时保留已有值。' : 'API Key 只写入，不会在页面回显。'}</p></div>
        </div>
        <div className="config-dialog-fields">
          <label>Profile 名称<input required value={profile.name} onChange={(event) => setProfile({ ...profile, name: event.target.value })} /></label>
          <label>Provider<select value={profile.provider} onChange={(event) => setProfile({ ...profile, provider: event.target.value })}><option value="openai-compat">OpenAI Compatible</option><option value="anthropic">Anthropic</option></select></label>
          <label>模型名称<input required value={profile.model} onChange={(event) => setProfile({ ...profile, model: event.target.value })} /></label>
          <label>Base URL<input value={profile.baseUrl} onChange={(event) => setProfile({ ...profile, baseUrl: event.target.value })} /></label>
          <label>API Key<input type="password" autoComplete="new-password" placeholder={profileId ? '留空保留现有 Key' : ''} value={profile.apiKey} onChange={(event) => setProfile({ ...profile, apiKey: event.target.value })} /></label>
        </div>
        {saveProfile.error && <div className="error-text">{errorMessage(saveProfile.error)}</div>}
        <div className="button-row"><button type="button" className="secondary" onClick={() => dialogRef.current?.close()}>取消</button><button type="submit" disabled={saveProfile.isPending}>保存 Profile</button></div>
      </form>
    </dialog>
  </section>;
}

function profileOptions(profiles: ModelProfile[] | undefined) {
  return (profiles ?? []).map((item) => <option key={item.id} value={item.id}>{item.name || item.id}</option>);
}

function providerName(provider: string) {
  return provider === 'openai-compat' ? 'OpenAI Compatible' : 'Anthropic';
}

function errorMessage(error: unknown) {
  return error instanceof Error ? error.message : '配置保存失败';
}
