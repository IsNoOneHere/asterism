alter table prd_sessions
    add column if not exists deleted boolean not null default false;

create index if not exists idx_prd_sessions_system_deleted_updated
    on prd_sessions (system_id, deleted, updated_at desc);

comment on column prd_sessions.deleted is '逻辑删除标记，保留对话及工作项审计关系';
