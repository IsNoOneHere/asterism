create schema if not exists control_plane_v5;
set search_path to control_plane_v5, public;

create table systems (
    system_id text primary key,
    name text not null,
    description text,
    repo_path text not null,
    owner_user_id text not null,
    allowed_paths jsonb not null default '[]'::jsonb,
    forbidden_paths jsonb not null default '[]'::jsonb,
    test_commands jsonb not null default '[]'::jsonb,
    agent_config jsonb not null default '{}'::jsonb,
    model_provider_config jsonb not null default '{}'::jsonb,
    created_by text not null default current_user,
    created_at timestamptz not null default now(),
    updated_at timestamptz not null default now()
);

create table users (
    user_id text primary key,
    display_name text not null,
    email text,
    password_hash text not null,
    enabled boolean not null default true,
    created_by text not null default current_user,
    created_at timestamptz not null default now(),
    updated_at timestamptz not null default now()
);

create table system_memberships (
    system_id text not null references systems(system_id),
    user_id text not null references users(user_id),
    role text not null,
    created_by text not null default current_user,
    created_at timestamptz not null default now(),
    primary key (system_id, user_id, role)
);

create table prd_sessions (
    prd_id text primary key,
    system_id text not null references systems(system_id),
    conversation_id text not null,
    work_item_id text,
    case_id text,
    title text,
    goal text,
    draft_json jsonb not null default '{}'::jsonb,
    missing_fields jsonb not null default '[]'::jsonb,
    status text not null,
    created_by text not null,
    confirmed_by text,
    confirmed_at timestamptz,
    created_at timestamptz not null default now(),
    updated_at timestamptz not null default now()
);

create table work_items (
    work_item_id text primary key,
    system_id text not null references systems(system_id),
    prd_id text references prd_sessions(prd_id),
    case_id text,
    title text,
    lifecycle_status text not null,
    approval_status text not null,
    execution_allowed boolean not null default false,
    current_stage text,
    waiting_for text,
    owner_user_id text,
    deleted boolean not null default false,
    last_applied_sequence bigint not null default 0,
    activated_at timestamptz,
    completed_at timestamptz,
    created_by text not null default current_user,
    created_at timestamptz not null default now(),
    updated_at timestamptz not null default now()
);

create table domain_events (
    sequence bigserial primary key,
    event_id text not null unique,
    event_type text not null,
    schema_version text not null,
    system_id text,
    case_id text,
    prd_id text,
    work_item_id text,
    actor_id text,
    source text not null,
    payload_json jsonb not null default '{}'::jsonb,
    correlation_id text,
    causation_id text,
    idempotency_key text unique,
    created_at timestamptz not null default now()
);

create table memory_items (
    memory_id text primary key,
    system_id text not null references systems(system_id),
    content text not null,
    status text not null,
    source_event_id text,
    approved_by text,
    metadata_json jsonb not null default '{}'::jsonb,
    created_by text not null default current_user,
    created_at timestamptz not null default now(),
    approved_at timestamptz
);

create table context_manifests (
    manifest_id text primary key,
    system_id text not null references systems(system_id),
    work_item_id text references work_items(work_item_id),
    approved_memory_refs jsonb not null default '[]'::jsonb,
    rejected_memory_refs jsonb not null default '[]'::jsonb,
    summary text,
    created_by text not null default current_user,
    created_at timestamptz not null default now()
);

create or replace function reject_domain_event_mutation()
returns trigger language plpgsql as $$
begin
    raise exception 'domain_events is append-only';
end $$;

create trigger domain_events_no_update
before update on domain_events
for each row execute function reject_domain_event_mutation();

create trigger domain_events_no_delete
before delete on domain_events
for each row execute function reject_domain_event_mutation();

comment on table systems is '系统配置表';
comment on table users is '用户账号表';
comment on table system_memberships is '系统成员角色表';
comment on table prd_sessions is 'PRD 会话表';
comment on table work_items is '工作项投影表';
comment on table domain_events is 'append-only 领域事件表';
comment on table memory_items is '记忆治理条目表';
comment on table context_manifests is 'Worker 上下文快照审计表';

comment on column domain_events.sequence is '全局单调事件序号，投影只应用更大的序号';
comment on column domain_events.payload_json is '事件 JSON payload，必须由 Jackson/Pydantic 读写';
comment on column work_items.last_applied_sequence is '该投影最后消费的事件序号';
comment on column work_items.execution_allowed is '只有 WorkItemActivated 投影后才允许执行';

