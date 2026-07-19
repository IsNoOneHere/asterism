set search_path to control_plane_v5, public;

-- 执行架构只保留 product 与 developer；旧 engine 在升级时一次性收敛。
with source as (
    select system_id,
           model_provider_config as config,
           coalesce((
               select agent
               from jsonb_array_elements(coalesce(model_provider_config -> 'agents', '[]'::jsonb)) agent
               where agent ->> 'name' = 'product'
               limit 1
           ), '{}'::jsonb) as product,
           coalesce((
               select agent
               from jsonb_array_elements(coalesce(model_provider_config -> 'agents', '[]'::jsonb)) agent
               where agent ->> 'name' = 'developer'
               limit 1
           ), '{}'::jsonb) as developer,
           coalesce((
               select jsonb_agg(to_jsonb(engine) order by engine)
               from (
                   select distinct agent ->> 'engine' as engine
                   from jsonb_array_elements(coalesce(model_provider_config -> 'agents', '[]'::jsonb)) agent
                   where agent ->> 'engine' in ('claude_sdk', 'deepagents', 'http')
               ) legacy
           ), '[]'::jsonb) as legacy_engines
    from systems
), normalized as (
    select system_id,
           jsonb_build_array(
               jsonb_build_object(
                   'name', 'product',
                   'kind', 'builtin',
                   'engine', '',
                   'modelProfileRef', coalesce(product ->> 'modelProfileRef', ''),
                   'pathScope', '[]'::jsonb,
                   'prompt', ''
               ),
               jsonb_strip_nulls(jsonb_build_object(
                   'name', 'developer',
                   'kind', 'builtin',
                   'engine', case when developer ->> 'engine' = 'fake' then 'fake' else 'claude_sdk_team' end,
                   'modelProfileRef', coalesce(developer ->> 'modelProfileRef', ''),
                   'pathScope', coalesce(developer -> 'pathScope', '[]'::jsonb),
                   'prompt', coalesce(developer ->> 'prompt', ''),
                   'maxTurns', developer -> 'maxTurns',
                   'timeoutSeconds', developer -> 'timeoutSeconds'
               ))
           ) as agents,
           jsonb_build_object(
               'migrated', jsonb_array_length(legacy_engines) > 0,
               'from', legacy_engines,
               'to', 'claude_sdk_team'
           ) as migration
    from source
)
update systems
set model_provider_config = jsonb_set(
        jsonb_set(model_provider_config, '{agents}', normalized.agents, true),
        '{executionMigration}', normalized.migration, true
    ),
    agent_config = '{}'::jsonb
from normalized
where systems.system_id = normalized.system_id;

comment on column systems.model_provider_config is
    '唯一模型配置：Model Profile 接入点、product 与 Claude SDK Supervisor developer；普通 API 必须脱敏';
