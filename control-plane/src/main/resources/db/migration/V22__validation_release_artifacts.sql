set search_path to control_plane_v5, public;

-- 只扩展既有约束，不回填历史事件，避免把旧 Evidence 伪造成新产物。
alter table artifacts
    drop constraint if exists artifacts_artifact_type_check;
alter table artifacts
    add constraint artifacts_artifact_type_check
    check (artifact_type in ('PRODUCT', 'PLANNING', 'CODING', 'VALIDATION', 'RELEASE'));

alter table artifact_version_locks
    drop constraint if exists artifact_version_locks_artifact_type_check;
alter table artifact_version_locks
    add constraint artifact_version_locks_artifact_type_check
    check (artifact_type in ('PRODUCT', 'PLANNING', 'CODING', 'VALIDATION', 'RELEASE'));

alter table artifact_heads
    drop constraint if exists artifact_heads_artifact_type_check;
alter table artifact_heads
    add constraint artifact_heads_artifact_type_check
    check (artifact_type in ('PRODUCT', 'PLANNING', 'CODING', 'VALIDATION', 'RELEASE'));

comment on column artifacts.parent_artifact_id is
    'DERIVED_FROM：Product -> Planning -> Coding -> Validation -> Release';
comment on table artifact_heads is
    '每个 Root 和 Artifact 类型唯一的有效 Approved Head；父链失效时不再属于当前路线';
