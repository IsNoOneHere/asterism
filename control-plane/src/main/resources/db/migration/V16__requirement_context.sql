set search_path to control_plane_v5, public;

alter table memory_items
    add column audience text not null default 'both'
        check (audience in ('product', 'execution', 'both'));

alter table conversation_messages
    add column context_bundle_id text,
    add column used_context_refs jsonb not null default '[]'::jsonb,
    add column citations_json jsonb not null default '{}'::jsonb;

alter table prd_sessions
    add column requirement_manifest_id text;

create table context_bundles (
    bundle_id text primary key,
    system_id text not null references systems(system_id),
    prd_id text references prd_sessions(prd_id),
    phase text not null check (phase in ('product', 'execution')),
    query_hash text not null,
    items_json jsonb not null,
    created_by text not null,
    created_at timestamptz not null default now()
);

alter table context_manifests
    drop constraint if exists context_manifests_work_item_id_fkey,
    drop column approved_memory_refs,
    drop column rejected_memory_refs,
    drop column summary,
    add column prd_id text references prd_sessions(prd_id),
    add column phase text not null default 'requirement',
    add column query_hash text not null default '',
    add column items_json jsonb not null default '[]'::jsonb;

alter table prd_sessions
    add constraint fk_prd_requirement_manifest
        foreign key (requirement_manifest_id) references context_manifests(manifest_id);

create index idx_context_bundles_prd_created
    on context_bundles(prd_id, created_at desc);

create unique index uq_context_manifest_requirement_refresh
    on context_manifests(prd_id, phase, query_hash)
    where phase = 'requirement';

comment on column memory_items.audience is '记忆适用阶段：product、execution 或 both';
comment on table context_bundles is '单轮可追踪的结构化上下文，保存 Product Agent 当时实际看到的内容';
comment on column context_manifests.items_json is '已确认 PRD 实际引用内容的不可变快照与哈希';
comment on column prd_sessions.requirement_manifest_id is '该 PRD 确认后冻结的需求上下文清单';
