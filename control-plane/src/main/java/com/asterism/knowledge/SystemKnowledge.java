package com.asterism.knowledge;

import org.springframework.data.annotation.Id;
import org.springframework.data.relational.core.mapping.Column;
import org.springframework.data.relational.core.mapping.Table;

import java.time.Instant;

@Table("system_knowledge")
public record SystemKnowledge(
        @Id @Column("entry_id") String entryId,
        @Column("system_id") String systemId,
        String kind,
        String title,
        @Column("anchor_texts") String anchorTexts,
        @Column("route_path") String routePath,
        @Column("api_endpoints") String apiEndpoints,
        @Column("code_refs") String codeRefs,
        String status,
        String source,
        @Column("source_ref") String sourceRef,
        @Column("created_by") String createdBy,
        @Column("created_at") Instant createdAt,
        @Column("approved_by") String approvedBy,
        @Column("approved_at") Instant approvedAt) {
}
