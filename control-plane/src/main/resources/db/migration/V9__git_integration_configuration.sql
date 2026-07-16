set search_path to control_plane_v5, public;

create table if not exists system_git_configs (
    system_id text primary key references systems(system_id) on delete cascade,
    repos_json jsonb not null default '[]'::jsonb,
    release_mode text not null default 'local' check (release_mode in ('local', 'gitlab')),
    validation_mode text not null default 'auto' check (validation_mode in ('auto', 'skip')),
    mr_target_branch text not null default '',
    mr_labels jsonb not null default '[]'::jsonb,
    gitlab_base_url text not null default '',
    gitlab_token text not null default '',
    updated_at timestamptz not null default now()
);

-- 旧单仓配置只迁移一次；原列保留给已启动 workflow replay。
insert into system_git_configs (system_id, repos_json)
select system_id,
       jsonb_build_array(jsonb_build_object(
           'repoId', 'main',
           'name', name,
           'kind', 'other',
           'gitlabProject', '',
           'defaultBranch', 'main',
           'cloneMode', 'local',
           'localPath', repo_path,
           'allowedPaths', allowed_paths,
           'forbiddenPaths', forbidden_paths,
           'testCommands', test_commands
       ))
from systems
on conflict (system_id) do nothing;

comment on table system_git_configs is '独立 Git 与发布配置；普通 API 不得返回 gitlab_token';
comment on column system_git_configs.repos_json is '工作项可用仓库列表及每仓路径门禁和测试命令';
