set search_path to control_plane_v5, public;

create table memory_candidates (
    candidate_id text primary key,
    system_id text not null references systems(system_id),
    project_scope text not null,
    memory_type text not null check (memory_type in ('FACT', 'DECISION', 'CONSTRAINT', 'EXPERIENCE')),
    artifact_source_id text references artifacts(artifact_id),
    source_kind text not null,
    title text not null,
    content text not null,
    confidence numeric(4, 3) not null check (confidence between 0 and 1),
    applicability text not null check (applicability in ('PROJECT', 'ARTIFACT_LINEAGE')),
    expires_at timestamptz,
    status text not null check (status in ('PENDING', 'CONFIRMED', 'REJECTED', 'OUTDATED')),
    target_refs jsonb not null default '[]'::jsonb,
    evidence_refs jsonb not null default '[]'::jsonb,
    normalized_content_hash text not null,
    source_event_id text,
    created_by text not null,
    reviewed_by text,
    review_note text,
    memory_id text,
    created_at timestamptz not null default now(),
    reviewed_at timestamptz,
    check (status in ('REJECTED', 'OUTDATED') or artifact_source_id is not null)
);

-- 旧候选保留在独立候选表，但因缺少 Artifact 来源不能再进入 Project Memory。
insert into memory_candidates(
    candidate_id, system_id, project_scope, memory_type, artifact_source_id, source_kind,
    title, content, confidence, applicability, status, evidence_refs,
    normalized_content_hash, source_event_id, created_by, reviewed_by, review_note,
    created_at, reviewed_at
)
select stable_candidate_id,
       system_id,
       system_id,
       case metadata_json ->> 'category'
           when 'constraint' then 'CONSTRAINT'
           when 'lesson' then 'EXPERIENCE'
           else 'FACT'
       end,
       null,
       'LEGACY',
       coalesce(nullif(metadata_json ->> 'title', ''), left(content, 80)),
       content,
       0,
       'PROJECT',
       case when status = 'rejected' then 'REJECTED' else 'OUTDATED' end,
       evidence_refs,
       normalized_content_hash,
       source_event_id,
       created_by,
       approved_by,
       '旧候选缺少 Artifact 来源，已退出审核队列',
       created_at,
       approved_at
from memory_items
where status in ('candidate', 'rejected')
on conflict (candidate_id) do nothing;

delete from memory_targets
where memory_id in (
    select memory_id from memory_items where status in ('candidate', 'rejected')
);

delete from memory_items
where status in ('candidate', 'rejected');

alter table memory_items
    add column project_scope text,
    add column memory_type text,
    add column artifact_source_id text references artifacts(artifact_id),
    add column title text,
    add column confidence numeric(4, 3),
    add column applicability text,
    add column expires_at timestamptz,
    add column candidate_id text references memory_candidates(candidate_id);

update memory_items
set project_scope = system_id,
    memory_type = case metadata_json ->> 'category'
        when 'constraint' then 'CONSTRAINT'
        when 'lesson' then 'EXPERIENCE'
        else 'FACT'
    end,
    title = coalesce(nullif(metadata_json ->> 'title', ''), left(content, 80)),
    confidence = 0,
    applicability = 'PROJECT',
    status = 'ARCHIVED';

alter table memory_items
    alter column project_scope set not null,
    alter column memory_type set not null,
    alter column title set not null,
    alter column confidence set not null,
    alter column applicability set not null,
    add constraint ck_memory_item_type
        check (memory_type in ('FACT', 'DECISION', 'CONSTRAINT', 'EXPERIENCE')),
    add constraint ck_memory_item_confidence
        check (confidence between 0 and 1),
    add constraint ck_memory_item_applicability
        check (applicability in ('PROJECT', 'ARTIFACT_LINEAGE')),
    add constraint ck_memory_item_status
        check (status in ('ACTIVE', 'OUTDATED', 'ARCHIVED')),
    add constraint ck_memory_item_artifact_source
        check (status = 'ARCHIVED' or (artifact_source_id is not null and candidate_id is not null));

alter table memory_candidates
    add constraint fk_memory_candidate_item
        foreign key (memory_id) references memory_items(memory_id);

drop index if exists uq_memory_normalized_content;
drop index if exists uq_memory_candidate_source;

create unique index uq_memory_candidate_artifact_content
    on memory_candidates(system_id, artifact_source_id, source_kind, memory_type, normalized_content_hash)
    where artifact_source_id is not null;

create unique index uq_memory_item_candidate
    on memory_items(candidate_id)
    where candidate_id is not null;

create index idx_memory_project_recall
    on memory_items(project_scope, memory_type, status, created_at desc);

create index idx_memory_artifact_source
    on memory_items(artifact_source_id, status);

create index idx_memory_candidate_review
    on memory_candidates(system_id, status, created_at desc);

alter table context_bundles
    drop constraint if exists context_bundles_phase_check;

alter table context_bundles
    add constraint context_bundles_phase_check
        check (phase in ('product', 'planning', 'coding', 'execution'));

comment on table memory_candidates is 'Artifact 经 Memory Extractor 生成、等待人工确认的项目记忆候选';
comment on column memory_items.memory_type is '项目记忆类型：事实、决策、约束或经验';
comment on column memory_items.artifact_source_id is '生成该记忆的可追溯 Artifact';
comment on column memory_items.project_scope is '记忆所属项目范围，当前与 system_id 对齐';
comment on column memory_items.applicability is '适用于整个项目或仅当前 Artifact 链';
