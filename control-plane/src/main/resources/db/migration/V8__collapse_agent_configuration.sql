set search_path to control_plane_v5, public;

-- 五套选择逻辑一次性折叠为三个内置 Agent 和已有自定义 Agent。
with legacy as (
    select system_id,
           model_provider_config as config,
           agent_config,
           coalesce(
               (select role
                from jsonb_array_elements(coalesce(model_provider_config -> 'agentRoles', '[]'::jsonb)) role
                where role ->> 'id' = model_provider_config ->> 'defaultAgentRoleId'
                limit 1),
               (select role
                from jsonb_array_elements(coalesce(model_provider_config -> 'agentRoles', '[]'::jsonb)) role
                limit 1),
               '{}'::jsonb
           ) as developer_role
    from systems
    where not model_provider_config ? 'agents'
), mapped as (
    select system_id,
           jsonb_build_array(
               jsonb_build_object(
                   'name', 'product',
                   'kind', 'builtin',
                   'engine', '',
                   'modelProfileRef', coalesce(
                       nullif(config #>> '{modelRouting,prdProfileId}', ''),
                       nullif(config #>> '{modelRouting,defaultProfileId}', ''), '') ,
                   'pathScope', '[]'::jsonb,
                   'prompt', ''
               ),
               jsonb_build_object(
                   'name', 'planner',
                   'kind', 'builtin',
                   'engine', '',
                   'modelProfileRef', coalesce(
                       nullif(config #>> '{modelRouting,planningProfileId}', ''),
                       nullif(config #>> '{modelRouting,defaultProfileId}', ''), ''),
                   'pathScope', '[]'::jsonb,
                   'prompt', ''
               ),
               jsonb_strip_nulls(jsonb_build_object(
                   'name', 'developer',
                   'kind', 'builtin',
                   'engine', coalesce(nullif(developer_role ->> 'engine', ''),
                                      nullif(agent_config ->> 'executionProvider', ''), 'http'),
                   'modelProfileRef', coalesce(
                       nullif(developer_role ->> 'modelProfileRef', ''),
                       nullif(config #>> '{modelRouting,diffProfileId}', ''),
                       nullif(config #>> '{modelRouting,defaultProfileId}', ''), ''),
                   'pathScope', coalesce(developer_role -> 'pathScope', '[]'::jsonb),
                   'prompt', coalesce(developer_role ->> 'prompt', ''),
                   'maxTurns', coalesce(developer_role -> 'maxTurns',
                       case when agent_config ->> 'claudeMaxTurns' ~ '^[0-9]+$'
                            then to_jsonb((agent_config ->> 'claudeMaxTurns')::integer) end),
                   'timeoutSeconds', coalesce(developer_role -> 'timeoutSeconds',
                       case when agent_config ->> 'executionTimeoutSeconds' ~ '^[0-9]+$'
                            then to_jsonb((agent_config ->> 'executionTimeoutSeconds')::integer) end)
               ))
           ) || coalesce((
               select jsonb_agg(jsonb_strip_nulls(jsonb_build_object(
                   'name', coalesce(nullif(role ->> 'id', ''), nullif(role ->> 'name', ''), 'agent-' || ordinal),
                   'kind', 'custom',
                   'engine', coalesce(role ->> 'engine', 'http'),
                   'modelProfileRef', coalesce(role ->> 'modelProfileRef', ''),
                   'pathScope', coalesce(role -> 'pathScope', '[]'::jsonb),
                   'prompt', coalesce(role ->> 'prompt', ''),
                   'maxTurns', role -> 'maxTurns',
                   'timeoutSeconds', role -> 'timeoutSeconds'
               )) order by ordinal)
               from jsonb_array_elements(coalesce(config -> 'agentRoles', '[]'::jsonb))
                    with ordinality roles(role, ordinal)
               where role is distinct from developer_role
           ), '[]'::jsonb) as agents
    from legacy
)
update systems
set model_provider_config = jsonb_set(model_provider_config, '{agents}', mapped.agents, true)
from mapped
where systems.system_id = mapped.system_id;

-- 迁移后运行期只读写 modelProfiles + agents，不再保留 legacy 清理分支。
update systems
set model_provider_config = model_provider_config - array[
        'modelRouting', 'agentRoles', 'defaultAgentRoleId', 'executionMode',
        'businessModels', 'businessRouting', 'provider', 'model', 'baseUrl', 'base_url', 'apiKey', 'api_key',
        'claudePreset', 'claudeModel', 'claudeBaseUrl', 'claudeApiKey', 'claudeReuseBusinessApiKey',
        'claudeBusinessModelId'
    ],
    agent_config = agent_config - array['executionProvider', 'claudeMaxTurns', 'executionTimeoutSeconds'];

comment on column systems.model_provider_config is
    '唯一模型配置：Model Profile 接入点与 Agent 调用者；普通 API 必须脱敏';
