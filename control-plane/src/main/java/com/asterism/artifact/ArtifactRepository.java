package com.asterism.artifact;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

@Repository
public class ArtifactRepository {
    private static final String COLUMNS = """
            artifact_id, artifact_type, root_artifact_id, system_id, prd_id, work_item_id, case_id,
            version, status, parent_artifact_id, supersedes_artifact_id, expected_head_artifact_id,
            content_json::text,
            content_hash, idempotency_key, created_by, created_at, reviewed_by, reviewed_at, review_note
            """;
    private static final String JOIN_COLUMNS = """
            a.artifact_id, a.artifact_type, a.root_artifact_id, a.system_id, a.prd_id, a.work_item_id,
            a.case_id, a.version, a.status, a.parent_artifact_id, a.supersedes_artifact_id,
            a.expected_head_artifact_id, a.content_json::text, a.content_hash, a.idempotency_key,
            a.created_by, a.created_at,
            a.reviewed_by, a.reviewed_at, a.review_note
            """;

    private final JdbcClient jdbc;
    private final ObjectMapper objectMapper;

    public ArtifactRepository(JdbcClient jdbc, ObjectMapper objectMapper) {
        this.jdbc = jdbc;
        this.objectMapper = objectMapper;
    }

    public Optional<Artifact> findById(String artifactId) {
        return jdbc.sql("select " + COLUMNS + " from artifacts where artifact_id = :artifactId")
                .param("artifactId", artifactId)
                .query(this::map)
                .optional();
    }

    public Optional<Artifact> findByIdempotencyKey(String idempotencyKey) {
        return jdbc.sql("select " + COLUMNS + " from artifacts where idempotency_key = :idempotencyKey")
                .param("idempotencyKey", idempotencyKey)
                .query(this::map)
                .optional();
    }

    /** latest 仅供查询界面使用，任何写路径和 Context 都不得调用。 */
    public Optional<Artifact> findLatestForDisplay(String workItemId, ArtifactType type) {
        return jdbc.sql("""
                        select %s from artifacts
                        where work_item_id = :workItemId and artifact_type = :type
                        order by version desc limit 1
                        """.formatted(COLUMNS))
                .param("workItemId", workItemId)
                .param("type", type.name())
                .query(this::map)
                .optional();
    }

    public Optional<String> findRoot(String systemId, String prdId) {
        return jdbc.sql("""
                        select root_artifact_id from artifact_roots
                        where system_id = :systemId and prd_id = :prdId
                        """)
                .param("systemId", systemId)
                .param("prdId", prdId)
                .query(String.class)
                .optional();
    }

    public void insertRoot(String rootArtifactId, String systemId, String prdId, Instant createdAt) {
        jdbc.sql("""
                        insert into artifact_roots(root_artifact_id, system_id, prd_id, created_at)
                        values (:rootArtifactId, :systemId, :prdId, :createdAt)
                        """)
                .param("rootArtifactId", rootArtifactId)
                .param("systemId", systemId)
                .param("prdId", prdId)
                .param("createdAt", Timestamp.from(createdAt))
                .update();
    }

    public Optional<Artifact> findHead(String rootArtifactId, ArtifactType type) {
        return jdbc.sql("""
                        select %s from artifact_heads h
                        join artifacts a on a.artifact_id = h.artifact_id
                        where h.root_artifact_id = :rootArtifactId and h.artifact_type = :type
                        """.formatted(JOIN_COLUMNS))
                .param("rootArtifactId", rootArtifactId)
                .param("type", type.name())
                .query(this::map)
                .optional();
    }

    public List<Artifact> findHeads(String rootArtifactId) {
        return jdbc.sql("""
                        select %s from artifact_heads h
                        join artifacts a on a.artifact_id = h.artifact_id
                        where h.root_artifact_id = :rootArtifactId
                        order by h.artifact_type
                        """.formatted(JOIN_COLUMNS))
                .param("rootArtifactId", rootArtifactId)
                .query(this::map)
                .list();
    }

    public List<Artifact> findByPrd(String prdId, ArtifactType type) {
        return jdbc.sql("""
                        select %s from artifacts
                        where prd_id = :prdId and artifact_type = :type
                        order by version
                        """.formatted(COLUMNS))
                .param("prdId", prdId)
                .param("type", type.name())
                .query(this::map)
                .list();
    }

    public List<Artifact> findByWorkItem(String workItemId) {
        return jdbc.sql("""
                        select %s from artifacts
                        where work_item_id = :workItemId
                        order by created_at, artifact_type, version
                        """.formatted(COLUMNS))
                .param("workItemId", workItemId)
                .query(this::map)
                .list();
    }

    public List<Artifact> findByRootAndType(String rootArtifactId, ArtifactType type) {
        return jdbc.sql("""
                        select %s from artifacts
                        where root_artifact_id = :rootArtifactId and artifact_type = :type
                        order by version
                        """.formatted(COLUMNS))
                .param("rootArtifactId", rootArtifactId)
                .param("type", type.name())
                .query(this::map)
                .list();
    }

    public List<Artifact> findByRoot(String rootArtifactId) {
        return jdbc.sql("""
                        select %s from artifacts
                        where root_artifact_id = :rootArtifactId
                        order by artifact_type, version
                        """.formatted(COLUMNS))
                .param("rootArtifactId", rootArtifactId)
                .query(this::map)
                .list();
    }

    public List<Artifact> findChildren(String artifactId) {
        return jdbc.sql("""
                        select %s from artifacts
                        where parent_artifact_id = :artifactId
                        order by artifact_type, version
                        """.formatted(COLUMNS))
                .param("artifactId", artifactId)
                .query(this::map)
                .list();
    }

    public void lockVersion(String lockId, ArtifactType type) {
        jdbc.sql("""
                        insert into artifact_version_locks(lock_id, artifact_type)
                        values (:lockId, :type)
                        on conflict do nothing
                        """)
                .param("lockId", lockId)
                .param("type", type.name())
                .update();
        jdbc.sql("""
                        select lock_id from artifact_version_locks
                        where lock_id = :lockId and artifact_type = :type
                        for update
                        """)
                .param("lockId", lockId)
                .param("type", type.name())
                .query(String.class)
                .single();
    }

    public void lockCommand(String lockId) {
        jdbc.sql("""
                        insert into artifact_command_locks(lock_id)
                        values (:lockId)
                        on conflict do nothing
                        """)
                .param("lockId", lockId)
                .update();
        jdbc.sql("""
                        select lock_id from artifact_command_locks
                        where lock_id = :lockId
                        for update
                        """)
                .param("lockId", lockId)
                .query(String.class)
                .single();
    }

    public int nextVersion(String rootArtifactId, ArtifactType type) {
        return jdbc.sql("""
                        select coalesce(max(version), 0) + 1 from artifacts
                        where root_artifact_id = :rootArtifactId and artifact_type = :type
                        """)
                .param("rootArtifactId", rootArtifactId)
                .param("type", type.name())
                .query(Integer.class)
                .single();
    }

    public void insert(InsertArtifact value) {
        jdbc.sql("""
                        insert into artifacts(
                            artifact_id, artifact_type, root_artifact_id, system_id, prd_id, work_item_id,
                            case_id, version, status, parent_artifact_id, supersedes_artifact_id,
                            expected_head_artifact_id, content_json, content_hash, idempotency_key, created_by, created_at,
                            reviewed_by, reviewed_at, review_note)
                        values (
                            :artifactId, :artifactType, :rootArtifactId, :systemId, :prdId, :workItemId,
                            :caseId, :version, :status, :parentArtifactId, :supersedesArtifactId,
                            :expectedHeadArtifactId, cast(:contentJson as jsonb), :contentHash,
                            :idempotencyKey, :createdBy, :createdAt,
                            :reviewedBy, :reviewedAt, :reviewNote)
                        """)
                .param("artifactId", value.artifactId())
                .param("artifactType", value.artifactType().name())
                .param("rootArtifactId", value.rootArtifactId())
                .param("systemId", value.systemId())
                .param("prdId", value.prdId())
                .param("workItemId", value.workItemId())
                .param("caseId", value.caseId())
                .param("version", value.version())
                .param("status", value.status().name())
                .param("parentArtifactId", value.parentArtifactId())
                .param("supersedesArtifactId", value.supersedesArtifactId())
                .param("expectedHeadArtifactId", value.expectedHeadArtifactId())
                .param("contentJson", value.contentJson())
                .param("contentHash", value.contentHash())
                .param("idempotencyKey", value.idempotencyKey())
                .param("createdBy", value.createdBy())
                .param("createdAt", Timestamp.from(value.createdAt()))
                .param("reviewedBy", value.reviewedBy())
                .param("reviewedAt", value.reviewedAt() == null ? null : Timestamp.from(value.reviewedAt()))
                .param("reviewNote", value.reviewNote())
                .update();
    }

    public int transitionStatus(String artifactId, ArtifactStatus from, ArtifactStatus to,
                                String reviewedBy, Instant reviewedAt, String note) {
        return jdbc.sql("""
                        update artifacts
                        set status = :toStatus, reviewed_by = :reviewedBy, reviewed_at = :reviewedAt,
                            review_note = :reviewNote
                        where artifact_id = :artifactId and status = :fromStatus
                        """)
                .param("artifactId", artifactId)
                .param("fromStatus", from.name())
                .param("toStatus", to.name())
                .param("reviewedBy", reviewedBy)
                .param("reviewedAt", Timestamp.from(reviewedAt))
                .param("reviewNote", note)
                .update();
    }

    public int compareAndSetHead(String rootArtifactId, ArtifactType type, String expectedArtifactId,
                                 Artifact next, Instant updatedAt) {
        if (expectedArtifactId == null) {
            return jdbc.sql("""
                            insert into artifact_heads(
                                root_artifact_id, artifact_type, artifact_id, version, content_hash, updated_at)
                            values (:rootArtifactId, :type, :artifactId, :version, :contentHash, :updatedAt)
                            on conflict do nothing
                            """)
                    .param("rootArtifactId", rootArtifactId)
                    .param("type", type.name())
                    .param("artifactId", next.artifactId())
                    .param("version", next.version())
                    .param("contentHash", next.contentHash())
                    .param("updatedAt", Timestamp.from(updatedAt))
                    .update();
        }
        return jdbc.sql("""
                        update artifact_heads
                        set artifact_id = :artifactId, version = :version, content_hash = :contentHash,
                            updated_at = :updatedAt
                        where root_artifact_id = :rootArtifactId and artifact_type = :type
                            and artifact_id = :expectedArtifactId
                        """)
                .param("rootArtifactId", rootArtifactId)
                .param("type", type.name())
                .param("artifactId", next.artifactId())
                .param("version", next.version())
                .param("contentHash", next.contentHash())
                .param("updatedAt", Timestamp.from(updatedAt))
                .param("expectedArtifactId", expectedArtifactId)
                .update();
    }

    public int clearHead(String rootArtifactId, ArtifactType type, String expectedArtifactId) {
        return jdbc.sql("""
                        delete from artifact_heads
                        where root_artifact_id = :rootArtifactId and artifact_type = :type
                            and artifact_id = :expectedArtifactId
                        """)
                .param("rootArtifactId", rootArtifactId)
                .param("type", type.name())
                .param("expectedArtifactId", expectedArtifactId)
                .update();
    }

    public Optional<ArtifactTransition> findTransition(String transitionId) {
        return jdbc.sql("""
                        select transition_id, artifact_id, from_status, to_status, actor, note,
                               domain_event_id, command_hash, created_at
                        from artifact_transitions where transition_id = :transitionId
                        """)
                .param("transitionId", transitionId)
                .query(this::mapTransition)
                .optional();
    }

    public List<ArtifactTransition> findTransitions(String artifactId) {
        return jdbc.sql("""
                        select transition_id, artifact_id, from_status, to_status, actor, note,
                               domain_event_id, command_hash, created_at
                        from artifact_transitions where artifact_id = :artifactId order by created_at
                        """)
                .param("artifactId", artifactId)
                .query(this::mapTransition)
                .list();
    }

    public void insertTransition(ArtifactTransition value) {
        jdbc.sql("""
                        insert into artifact_transitions(
                            transition_id, artifact_id, from_status, to_status, actor, note,
                            domain_event_id, command_hash, created_at)
                        values (:transitionId, :artifactId, :fromStatus, :toStatus, :actor, :note,
                            :domainEventId, :commandHash, :createdAt)
                        """)
                .param("transitionId", value.transitionId())
                .param("artifactId", value.artifactId())
                .param("fromStatus", value.fromStatus() == null ? null : value.fromStatus().name())
                .param("toStatus", value.toStatus().name())
                .param("actor", value.actor())
                .param("note", value.note())
                .param("domainEventId", value.domainEventId())
                .param("commandHash", value.commandHash())
                .param("createdAt", Timestamp.from(value.createdAt()))
                .update();
    }

    public Optional<ArtifactEvidence> findEvidence(String evidenceId) {
        return jdbc.sql("""
                        select evidence_id, artifact_id, evidence_type, payload_json::text,
                               transition_id, domain_event_id, actor, command_hash, created_at
                        from artifact_evidence where evidence_id = :evidenceId
                        """)
                .param("evidenceId", evidenceId)
                .query(this::mapEvidence)
                .optional();
    }

    public List<ArtifactEvidence> findEvidenceByArtifact(String artifactId) {
        return jdbc.sql("""
                        select evidence_id, artifact_id, evidence_type, payload_json::text,
                               transition_id, domain_event_id, actor, command_hash, created_at
                        from artifact_evidence where artifact_id = :artifactId order by created_at
                        """)
                .param("artifactId", artifactId)
                .query(this::mapEvidence)
                .list();
    }

    public void insertEvidence(ArtifactEvidence value) {
        jdbc.sql("""
                        insert into artifact_evidence(
                            evidence_id, artifact_id, evidence_type, payload_json, transition_id,
                            domain_event_id, actor, command_hash, created_at)
                        values (:evidenceId, :artifactId, :evidenceType, cast(:payload as jsonb),
                            :transitionId, :domainEventId, :actor, :commandHash, :createdAt)
                        """)
                .param("evidenceId", value.evidenceId())
                .param("artifactId", value.artifactId())
                .param("evidenceType", value.evidenceType())
                .param("payload", json(value.payload()))
                .param("transitionId", value.transitionId())
                .param("domainEventId", value.domainEventId())
                .param("actor", value.actor())
                .param("commandHash", value.commandHash())
                .param("createdAt", Timestamp.from(value.createdAt()))
                .update();
    }

    private Artifact map(ResultSet rs, int rowNum) throws SQLException {
        var reviewedAt = rs.getTimestamp("reviewed_at");
        try {
            return new Artifact(
                    rs.getString("artifact_id"),
                    ArtifactType.valueOf(rs.getString("artifact_type")),
                    rs.getString("root_artifact_id"),
                    rs.getString("system_id"),
                    rs.getString("prd_id"),
                    rs.getString("work_item_id"),
                    rs.getString("case_id"),
                    rs.getInt("version"),
                    ArtifactStatus.valueOf(rs.getString("status")),
                    rs.getString("parent_artifact_id"),
                    rs.getString("supersedes_artifact_id"),
                    rs.getString("expected_head_artifact_id"),
                    objectMapper.readTree(rs.getString("content_json")),
                    rs.getString("content_hash"),
                    rs.getString("idempotency_key"),
                    rs.getString("created_by"),
                    rs.getTimestamp("created_at").toInstant(),
                    rs.getString("reviewed_by"),
                    reviewedAt == null ? null : reviewedAt.toInstant(),
                    rs.getString("review_note"));
        } catch (JsonProcessingException error) {
            throw new SQLException("Artifact JSON 无法读取", error);
        }
    }

    private ArtifactTransition mapTransition(ResultSet rs, int rowNum) throws SQLException {
        var from = rs.getString("from_status");
        return new ArtifactTransition(
                rs.getString("transition_id"), rs.getString("artifact_id"),
                from == null ? null : ArtifactStatus.valueOf(from),
                ArtifactStatus.valueOf(rs.getString("to_status")),
                rs.getString("actor"), rs.getString("note"), rs.getString("domain_event_id"),
                rs.getString("command_hash"), rs.getTimestamp("created_at").toInstant());
    }

    private ArtifactEvidence mapEvidence(ResultSet rs, int rowNum) throws SQLException {
        try {
            return new ArtifactEvidence(
                    rs.getString("evidence_id"), rs.getString("artifact_id"),
                    rs.getString("evidence_type"), objectMapper.readTree(rs.getString("payload_json")),
                    rs.getString("transition_id"), rs.getString("domain_event_id"), rs.getString("actor"),
                    rs.getString("command_hash"), rs.getTimestamp("created_at").toInstant());
        } catch (JsonProcessingException error) {
            throw new SQLException("Artifact Evidence JSON 无法读取", error);
        }
    }

    private String json(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException error) {
            throw new IllegalArgumentException("Artifact JSON 不能序列化", error);
        }
    }

    public record InsertArtifact(
            String artifactId,
            ArtifactType artifactType,
            String rootArtifactId,
            String systemId,
            String prdId,
            String workItemId,
            String caseId,
            int version,
            ArtifactStatus status,
            String parentArtifactId,
            String supersedesArtifactId,
            String expectedHeadArtifactId,
            String contentJson,
            String contentHash,
            String idempotencyKey,
            String createdBy,
            Instant createdAt,
            String reviewedBy,
            Instant reviewedAt,
            String reviewNote) {
    }
}
