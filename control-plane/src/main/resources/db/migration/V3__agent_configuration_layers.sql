set search_path to control_plane_v5, public;

-- 新三层配置复用 systems 的 JSON 列，旧 businessModels/Claude 字段继续保留用于平滑回落。
update systems
set model_provider_config = jsonb_set(model_provider_config, '{modelProfiles}', '[]'::jsonb, true)
where not model_provider_config ? 'modelProfiles';

update systems
set model_provider_config = jsonb_set(model_provider_config, '{agentRoles}', '[]'::jsonb, true)
where not model_provider_config ? 'agentRoles';

update systems
set model_provider_config = jsonb_set(model_provider_config, '{defaultAgentRoleId}', '""'::jsonb, true)
where not model_provider_config ? 'defaultAgentRoleId';

comment on column systems.model_provider_config is
    '模型 Profile、Agent 角色及旧版模型配置；普通 API 必须脱敏';
