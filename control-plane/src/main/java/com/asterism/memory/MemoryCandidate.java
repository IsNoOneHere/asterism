package com.asterism.memory;

import org.springframework.data.annotation.Id;
import org.springframework.data.relational.core.mapping.Column;
import org.springframework.data.relational.core.mapping.Table;

import java.time.Instant;

@Table("memory_candidates")
public record MemoryCandidate(
        @Id @Column("candidate_id") String candidateId,
        @Column("system_id") String systemId,
        @Column("project_scope") String projectScope,
        @Column("memory_type") MemoryType memoryType,
        @Column("artifact_source_id") String artifactSourceId,
        @Column("source_kind") String sourceKind,
        String title,
        String content,
        double confidence,
        MemoryApplicability applicability,
        @Column("expires_at") Instant expiresAt,
        MemoryCandidateStatus status,
        @Column("target_refs") String targetRefs,
        @Column("evidence_refs") String evidenceRefs,
        @Column("normalized_content_hash") String normalizedContentHash,
        @Column("source_event_id") String sourceEventId,
        @Column("created_by") String createdBy,
        @Column("reviewed_by") String reviewedBy,
        @Column("review_note") String reviewNote,
        @Column("memory_id") String memoryId,
        @Column("created_at") Instant createdAt,
        @Column("reviewed_at") Instant reviewedAt) {
}
