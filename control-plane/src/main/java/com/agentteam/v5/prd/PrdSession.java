package com.agentteam.v5.prd;

import org.springframework.data.annotation.Id;
import org.springframework.data.relational.core.mapping.Column;
import org.springframework.data.relational.core.mapping.Table;

import java.time.Instant;

@Table("prd_sessions")
public record PrdSession(
        @Id @Column("prd_id") String prdId,
        @Column("system_id") String systemId,
        @Column("conversation_id") String conversationId,
        @Column("work_item_id") String workItemId,
        @Column("case_id") String caseId,
        String title,
        String goal,
        @Column("draft_json") String draftJson,
        @Column("missing_fields") String missingFields,
        String status,
        @Column("created_by") String createdBy,
        @Column("confirmed_by") String confirmedBy,
        @Column("confirmed_at") Instant confirmedAt,
        @Column("created_at") Instant createdAt,
        @Column("updated_at") Instant updatedAt) {
}

