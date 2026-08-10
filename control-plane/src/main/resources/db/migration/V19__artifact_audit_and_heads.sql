set search_path to control_plane_v5, public;

create table artifact_roots (
    root_artifact_id text primary key,
    system_id text not null references systems(system_id),
    prd_id text not null references prd_sessions(prd_id),
    created_at timestamptz not null default now(),
    unique (system_id, prd_id)
);

-- 先登记历史 Root，再补外键，保留 V18 已产生的 Artifact 链。
insert into artifact_roots(root_artifact_id, system_id, prd_id, created_at)
select root_artifact_id, min(system_id), min(prd_id), min(created_at)
from artifacts
group by root_artifact_id;

alter table artifacts
    add constraint artifacts_root_artifact_id_fkey
    foreign key (root_artifact_id) references artifact_roots(root_artifact_id);

alter table artifacts
    add column expected_head_artifact_id text references artifacts(artifact_id);

alter table artifact_version_locks
    rename column root_artifact_id to lock_id;

create table artifact_command_locks (
    lock_id text primary key
);

create table artifact_heads (
    root_artifact_id text not null references artifact_roots(root_artifact_id),
    artifact_type text not null check (artifact_type in ('PRODUCT', 'PLANNING', 'CODING')),
    artifact_id text not null references artifacts(artifact_id),
    version integer not null,
    content_hash text not null,
    updated_at timestamptz not null default now(),
    primary key (root_artifact_id, artifact_type),
    unique (artifact_id)
);

-- 旧数据没有 Head 投影，按每类最新的 Approved 版本恢复。
insert into artifact_heads(
    root_artifact_id, artifact_type, artifact_id, version, content_hash, updated_at)
select distinct on (root_artifact_id, artifact_type)
    root_artifact_id, artifact_type, artifact_id, version, content_hash,
    coalesce(reviewed_at, created_at)
from artifacts
where status = 'APPROVED'
order by root_artifact_id, artifact_type, version desc;

create table artifact_transitions (
    transition_id text primary key,
    artifact_id text not null references artifacts(artifact_id),
    from_status text check (from_status is null or from_status in ('PROPOSED', 'APPROVED', 'REJECTED', 'SUPERSEDED')),
    to_status text not null check (to_status in ('PROPOSED', 'APPROVED', 'REJECTED', 'SUPERSEDED')),
    actor text not null,
    note text,
    domain_event_id text not null references domain_events(event_id),
    command_hash text not null,
    created_at timestamptz not null default now()
);

-- V18 只保存当前状态，这里为历史 Artifact 建立可追溯的迁移快照。
insert into artifact_transitions(
    transition_id, artifact_id, from_status, to_status, actor, note,
    domain_event_id, command_hash, created_at)
select
    'migration:v19:transition:' || artifact.artifact_id,
    artifact.artifact_id,
    null,
    artifact.status,
    coalesce(artifact.reviewed_by, artifact.created_by),
    coalesce(artifact.review_note, 'V18 历史状态迁移'),
    event.event_id,
    artifact.content_hash,
    coalesce(artifact.reviewed_at, artifact.created_at)
from artifacts artifact
cross join lateral (
    select domain_event.event_id
    from domain_events domain_event
    where domain_event.system_id = artifact.system_id
      and domain_event.prd_id = artifact.prd_id
      and domain_event.work_item_id = artifact.work_item_id
      and domain_event.case_id = artifact.case_id
    order by abs(extract(epoch from domain_event.created_at - artifact.created_at)),
             domain_event.sequence
    limit 1
) event;

create table artifact_evidence (
    evidence_id text primary key,
    artifact_id text not null references artifacts(artifact_id),
    evidence_type text not null,
    payload_json jsonb not null default '{}'::jsonb,
    transition_id text references artifact_transitions(transition_id),
    domain_event_id text not null references domain_events(event_id),
    actor text not null,
    command_hash text not null,
    created_at timestamptz not null default now()
);

-- 将 V18 的字符串引用迁入 append-only Evidence，避免升级时丢失历史证据。
insert into artifact_evidence(
    evidence_id, artifact_id, evidence_type, payload_json, transition_id,
    domain_event_id, actor, command_hash, created_at)
select
    'migration:v19:evidence:' || artifact.artifact_id || ':' || reference.ordinality,
    artifact.artifact_id,
    'LEGACY_REFERENCE',
    jsonb_build_object('reference', reference.value),
    transition.transition_id,
    transition.domain_event_id,
    coalesce(artifact.reviewed_by, artifact.created_by),
    artifact.content_hash,
    artifact.created_at
from artifacts artifact
cross join lateral jsonb_array_elements_text(artifact.evidence_refs)
    with ordinality reference(value, ordinality)
join artifact_transitions transition
  on transition.artifact_id = artifact.artifact_id;

alter table artifacts drop column evidence_refs;

create index idx_artifacts_supersedes
    on artifacts(supersedes_artifact_id, artifact_type, version desc);

create index idx_artifact_transitions_artifact
    on artifact_transitions(artifact_id, created_at);

create index idx_artifact_evidence_artifact
    on artifact_evidence(artifact_id, created_at);

create or replace function reject_artifact_content_mutation()
returns trigger language plpgsql as $$
begin
    if new.artifact_id <> old.artifact_id
        or new.artifact_type <> old.artifact_type
        or new.root_artifact_id <> old.root_artifact_id
        or new.system_id <> old.system_id
        or new.prd_id <> old.prd_id
        or new.work_item_id <> old.work_item_id
        or new.case_id <> old.case_id
        or new.version <> old.version
        or new.parent_artifact_id is distinct from old.parent_artifact_id
        or new.supersedes_artifact_id is distinct from old.supersedes_artifact_id
        or new.expected_head_artifact_id is distinct from old.expected_head_artifact_id
        or new.content_json <> old.content_json
        or new.content_hash <> old.content_hash
        or new.idempotency_key <> old.idempotency_key
        or new.created_by <> old.created_by
        or new.created_at <> old.created_at then
        raise exception 'artifact content and lineage are immutable';
    end if;
    return new;
end $$;

create or replace function reject_append_only_mutation()
returns trigger language plpgsql as $$
begin
    raise exception '% is append-only', tg_table_name;
end $$;

create trigger artifact_transitions_append_only
before update or delete on artifact_transitions
for each row execute function reject_append_only_mutation();

create trigger artifact_evidence_append_only
before update or delete on artifact_evidence
for each row execute function reject_append_only_mutation();

comment on table artifacts is '跨阶段事实产物的当前状态投影';
comment on table artifact_heads is '每个 Root 和类型唯一的有效 Approved Head';
comment on table artifact_transitions is 'Artifact 状态变化的 append-only 审计';
comment on table artifact_evidence is '验证、Patch、Commit、MR、发布和阻塞结果的 append-only 证据';
comment on column artifacts.parent_artifact_id is 'DERIVED_FROM：Product -> Planning -> Coding';
comment on column artifacts.supersedes_artifact_id is 'SUPERSEDES：显式同类型修订关系';
comment on column artifacts.content_json is '由类型化 Content 契约序列化的不可变内容';
comment on column artifacts.content_hash is '规范化 Content JSON 的 SHA-256';
