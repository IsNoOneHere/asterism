set search_path to control_plane_v5, public;

create extension if not exists pg_trgm with schema public;

create table attachments (
    attachment_id text primary key,
    system_id text not null references systems(system_id),
    uploader text not null,
    filename text not null,
    content_type text not null,
    size_bytes bigint not null,
    sha256 text not null,
    storage_path text not null,
    created_at timestamptz not null default now(),
    unique (system_id, sha256)
);

alter table conversation_messages
    add column attachment_ids jsonb not null default '[]'::jsonb,
    add column observations_json jsonb not null default '[]'::jsonb;

create table system_knowledge (
    entry_id text primary key,
    system_id text not null references systems(system_id),
    kind text not null check (kind in ('route', 'page', 'api')),
    title text not null,
    anchor_texts text not null default '',
    route_path text not null default '',
    api_endpoints jsonb not null default '[]'::jsonb,
    code_refs jsonb not null default '[]'::jsonb,
    status text not null check (status in ('candidate', 'approved', 'rejected', 'disabled')),
    source text not null check (source in ('code_index', 'manual', 'work_item_learning')),
    source_ref text not null default '',
    created_by text not null,
    created_at timestamptz not null default now(),
    approved_by text,
    approved_at timestamptz
);

create unique index uq_system_knowledge_route
    on system_knowledge(system_id, route_path)
    where route_path <> '';

create unique index uq_system_knowledge_source
    on system_knowledge(system_id, source, source_ref)
    where source_ref <> '';

create index idx_system_knowledge_title_trgm
    on system_knowledge using gin (title public.gin_trgm_ops);

create index idx_system_knowledge_anchor_trgm
    on system_knowledge using gin (anchor_texts public.gin_trgm_ops);

comment on table attachments is '鉴权图片附件元数据，图片本体仅存对象存储';
comment on column conversation_messages.attachment_ids is '消息关联的鉴权附件 ID，最多三项';
comment on column conversation_messages.observations_json is '图片派生的 UI 观察文本，不含图片字节';
comment on table system_knowledge is '系统路由、页面和接口知识治理表，仅 approved 参与匹配';
