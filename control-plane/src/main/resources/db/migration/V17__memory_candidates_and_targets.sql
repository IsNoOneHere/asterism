set search_path to control_plane_v5, public;

alter table memory_items
    add column stable_candidate_id text,
    add column source_ref text not null default '',
    add column evidence_refs jsonb not null default '[]'::jsonb,
    add column normalized_content_hash text not null default '';

update memory_items
set stable_candidate_id = memory_id
where stable_candidate_id is null;

alter table memory_items
    alter column stable_candidate_id set not null;

create table memory_targets (
    memory_id text not null references memory_items(memory_id) on delete cascade,
    knowledge_entry_id text not null references system_knowledge(entry_id),
    created_at timestamptz not null default now(),
    primary key (memory_id, knowledge_entry_id)
);

create unique index uq_memory_stable_candidate
    on memory_items(stable_candidate_id);

create unique index uq_memory_candidate_source
    on memory_items(system_id, source_ref)
    where source_ref <> '';

create unique index uq_memory_normalized_content
    on memory_items(system_id, normalized_content_hash)
    where normalized_content_hash <> '';

create index idx_memory_targets_knowledge
    on memory_targets(knowledge_entry_id, memory_id);

comment on table memory_targets is '系统记忆与页面、路由、接口知识的轻量适用关系';
comment on column memory_items.stable_candidate_id is '候选从生成到审批全程不变的标识';
comment on column memory_items.source_ref is '候选来源，例如 prd 或 work-item';
comment on column memory_items.evidence_refs is '候选依据引用，仅保存标识，不保存日志或 Diff';
comment on column memory_items.normalized_content_hash is '规范化正文哈希，用于跨来源去重';
