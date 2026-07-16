set search_path to control_plane_v5, public;

alter table system_knowledge add column repo_id text not null default 'main';

drop index uq_system_knowledge_route;
drop index uq_system_knowledge_source;

create unique index uq_system_knowledge_route
    on system_knowledge(system_id, repo_id, route_path)
    where route_path <> '';

create unique index uq_system_knowledge_source
    on system_knowledge(system_id, repo_id, source, source_ref)
    where source_ref <> '';

comment on column system_knowledge.repo_id is '知识所属系统仓库；旧数据迁移为 main';
