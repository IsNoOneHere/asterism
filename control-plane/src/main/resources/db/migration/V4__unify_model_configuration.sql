set search_path to control_plane_v5, public;

-- 旧业务模型池按原 ID 搬入 Model Profile，routing 引用无需二次映射。
update systems
set model_provider_config = jsonb_set(
        model_provider_config,
        '{modelProfiles}',
        (select jsonb_agg(jsonb_build_object(
                'id', coalesce(nullif(item ->> 'modelId', ''), 'mp-migrated-' || ordinal),
                'name', coalesce(nullif(item ->> 'name', ''), '迁移模型 ' || ordinal),
                'provider', case when lower(coalesce(item ->> 'preset', item ->> 'provider', '')) in ('anthropic', 'claude')
                                 then 'anthropic' else 'openai-compat' end,
                'baseUrl', coalesce(item ->> 'baseUrl', ''),
                'apiKey', coalesce(item ->> 'apiKey', ''),
                'model', coalesce(item ->> 'model', '')
        ) order by ordinal)
         from jsonb_array_elements(model_provider_config -> 'businessModels') with ordinality old(item, ordinal)),
        true)
where jsonb_array_length(coalesce(model_provider_config -> 'modelProfiles', '[]'::jsonb)) = 0
  and jsonb_typeof(model_provider_config -> 'businessModels') = 'array'
  and jsonb_array_length(model_provider_config -> 'businessModels') > 0;

-- 最早期单模型字段也只迁移一次，不在运行时代码保留 legacy 分支。
update systems
set model_provider_config = jsonb_set(model_provider_config, '{modelProfiles}', jsonb_build_array(jsonb_build_object(
        'id', 'mp-migrated-default',
        'name', '默认模型',
        'provider', case when lower(coalesce(model_provider_config ->> 'provider', '')) in ('anthropic', 'claude')
                         then 'anthropic' else 'openai-compat' end,
        'baseUrl', coalesce(model_provider_config ->> 'baseUrl', model_provider_config ->> 'base_url', ''),
        'apiKey', coalesce(model_provider_config ->> 'apiKey', model_provider_config ->> 'api_key', ''),
        'model', coalesce(model_provider_config ->> 'model', '')
)), true)
where jsonb_array_length(coalesce(model_provider_config -> 'modelProfiles', '[]'::jsonb)) = 0
  and (model_provider_config ? 'model' or model_provider_config ? 'apiKey' or model_provider_config ? 'api_key');

-- 独立 Claude 配置变成普通 Anthropic Profile，旧执行角色可直接引用。
update systems
set model_provider_config = jsonb_set(
        model_provider_config,
        '{modelProfiles}',
        coalesce(model_provider_config -> 'modelProfiles', '[]'::jsonb) || jsonb_build_array(jsonb_build_object(
                'id', 'mp-migrated-claude',
                'name', 'Claude 迁移模型',
                'provider', 'anthropic',
                'baseUrl', coalesce(model_provider_config ->> 'claudeBaseUrl', ''),
                'apiKey', coalesce(nullif(model_provider_config ->> 'claudeApiKey', ''),
                    (select item ->> 'apiKey'
                     from jsonb_array_elements(coalesce(model_provider_config -> 'businessModels', '[]'::jsonb)) item
                     where item ->> 'modelId' = coalesce(nullif(model_provider_config ->> 'claudeBusinessModelId', ''),
                                                         model_provider_config #>> '{businessRouting,defaultModelId}')
                     limit 1),
                    model_provider_config ->> 'apiKey', ''),
                'model', model_provider_config ->> 'claudeModel'
        )),
        true)
where nullif(model_provider_config ->> 'claudeModel', '') is not null
  and not coalesce(model_provider_config -> 'modelProfiles', '[]'::jsonb)
          @> '[{"id":"mp-migrated-claude"}]'::jsonb;

update systems
set model_provider_config = jsonb_set(model_provider_config, '{modelRouting}', jsonb_build_object(
        'defaultProfileId', coalesce(nullif(model_provider_config #>> '{businessRouting,defaultModelId}', ''),
                                     model_provider_config #>> '{modelProfiles,0,id}', ''),
        'prdProfileId', coalesce(model_provider_config #>> '{businessRouting,prdModelId}', ''),
        'planningProfileId', coalesce(model_provider_config #>> '{businessRouting,planningModelId}', ''),
        'diffProfileId', coalesce(model_provider_config #>> '{businessRouting,diffModelId}', '')
), true)
where not model_provider_config ? 'modelRouting';

-- 旧 executionProvider 只在迁移时生成默认 Agent，运行期仍保留旧 workflow payload 回放兼容。
update systems
set model_provider_config = jsonb_set(
        jsonb_set(
                jsonb_set(model_provider_config, '{agentRoles}', jsonb_build_array(jsonb_strip_nulls(jsonb_build_object(
                        'id', 'role-migrated-default',
                        'name', '默认 Agent',
                        'engine', agent_config ->> 'executionProvider',
                        'modelProfileRef', case when agent_config ->> 'executionProvider' = 'fake' then ''
                                                when agent_config ->> 'executionProvider' = 'claude_sdk'
                                                     and model_provider_config -> 'modelProfiles' @> '[{"id":"mp-migrated-claude"}]'::jsonb
                                                then 'mp-migrated-claude'
                                                else coalesce(model_provider_config #>> '{modelRouting,defaultProfileId}', '') end,
                        'pathScope', '[]'::jsonb,
                        'prompt', '',
                        'maxTurns', (agent_config ->> 'claudeMaxTurns')::integer,
                        'timeoutSeconds', (agent_config ->> 'executionTimeoutSeconds')::integer
                ))), true),
                '{defaultAgentRoleId}', '"role-migrated-default"'::jsonb, true),
        '{executionMode}', '"single"'::jsonb, true)
where jsonb_array_length(coalesce(model_provider_config -> 'agentRoles', '[]'::jsonb)) = 0
  and nullif(agent_config ->> 'executionProvider', '') is not null;

update systems
set model_provider_config = model_provider_config - array[
        'businessModels', 'businessRouting', 'provider', 'model', 'baseUrl', 'base_url', 'apiKey', 'api_key',
        'claudePreset', 'claudeModel', 'claudeBaseUrl', 'claudeApiKey', 'claudeReuseBusinessApiKey',
        'claudeBusinessModelId'
];

comment on column systems.model_provider_config is
    '唯一模型配置：Model Profile、阶段 routing、Agent Role 与执行策略；普通 API 必须脱敏';
