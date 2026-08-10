set search_path to control_plane_v5, public;

create table artifacts (
    artifact_id text primary key,
    artifact_type text not null check (artifact_type in ('PRODUCT', 'PLANNING', 'CODING')),
    root_artifact_id text not null,
    system_id text not null references systems(system_id),
    prd_id text not null references prd_sessions(prd_id),
    work_item_id text not null,
    case_id text not null,
    version integer not null check (version > 0),
    status text not null check (status in ('PROPOSED', 'APPROVED', 'REJECTED', 'SUPERSEDED')),
    parent_artifact_id text references artifacts(artifact_id),
    supersedes_artifact_id text references artifacts(artifact_id),
    content_json jsonb not null,
    content_hash text not null,
    evidence_refs jsonb not null default '[]'::jsonb,
    idempotency_key text not null unique,
    created_by text not null,
    created_at timestamptz not null default now(),
    reviewed_by text,
    reviewed_at timestamptz,
    review_note text,
    unique (root_artifact_id, artifact_type, version),
    check (parent_artifact_id is null or parent_artifact_id <> artifact_id),
    check (supersedes_artifact_id is null or supersedes_artifact_id <> artifact_id)
);

create table artifact_version_locks (
    root_artifact_id text not null,
    artifact_type text not null check (artifact_type in ('PRODUCT', 'PLANNING', 'CODING')),
    primary key (root_artifact_id, artifact_type)
);

create index idx_artifacts_prd_type_version
    on artifacts(prd_id, artifact_type, version desc);

create index idx_artifacts_work_item_created
    on artifacts(work_item_id, created_at, artifact_type, version);

create index idx_artifacts_parent
    on artifacts(parent_artifact_id, artifact_type, version desc);

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
        or new.content_json <> old.content_json
        or new.content_hash <> old.content_hash
        or new.evidence_refs <> old.evidence_refs
        or new.idempotency_key <> old.idempotency_key
        or new.created_by <> old.created_by
        or new.created_at <> old.created_at then
        raise exception 'artifact content and lineage are immutable';
    end if;
    return new;
end $$;

create trigger artifacts_content_immutable
before update on artifacts
for each row execute function reject_artifact_content_mutation();

comment on table artifacts is '跨阶段唯一可信的版本化工程产物';
comment on column artifacts.parent_artifact_id is '跨阶段派生关系：Product -> Planning -> Coding';
comment on column artifacts.supersedes_artifact_id is '同类型版本替代关系';
comment on column artifacts.content_json is '由类型化 Content 契约序列化的不可变内容';
comment on column artifacts.content_hash is '规范化 Content JSON 的 SHA-256';
