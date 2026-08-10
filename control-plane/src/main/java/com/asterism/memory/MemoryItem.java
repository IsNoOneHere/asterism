package com.asterism.memory;

import org.springframework.data.annotation.Id;
import org.springframework.data.relational.core.mapping.Column;
import org.springframework.data.relational.core.mapping.Table;

import java.time.Instant;

@Table("memory_items")
public record MemoryItem(
        @Id @Column("memory_id") String memoryId,
        @Column("system_id") String systemId,
        @Column("project_scope") String projectScope,
        @Column("memory_type") MemoryType memoryType,
        @Column("artifact_source_id") String artifactSourceId,
        String title,
        String content,
        double confidence,
        MemoryApplicability applicability,
        @Column("expires_at") Instant expiresAt,
        MemoryStatus status,
        @Column("candidate_id") String candidateId,
        @Column("stable_candidate_id") String stableCandidateId,
        @Column("source_ref") String sourceRef,
        @Column("evidence_refs") String evidenceRefs,
        @Column("normalized_content_hash") String normalizedContentHash,
        @Column("source_event_id") String sourceEventId,
        @Column("approved_by") String approvedBy,
        @Column("metadata_json") String metadataJson,
        @Column("created_by") String createdBy,
        @Column("created_at") Instant createdAt,
        @Column("approved_at") Instant approvedAt) {
}
