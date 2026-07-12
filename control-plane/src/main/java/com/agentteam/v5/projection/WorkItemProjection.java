package com.agentteam.v5.projection;

import org.springframework.data.annotation.Id;
import org.springframework.data.relational.core.mapping.Column;
import org.springframework.data.relational.core.mapping.Table;

import java.time.Instant;

@Table("work_items")
public record WorkItemProjection(
        @Id @Column("work_item_id") String workItemId,
        @Column("system_id") String systemId,
        @Column("prd_id") String prdId,
        @Column("case_id") String caseId,
        String title,
        @Column("lifecycle_status") String lifecycleStatus,
        @Column("approval_status") String approvalStatus,
        @Column("execution_allowed") boolean executionAllowed,
        @Column("current_stage") String currentStage,
        @Column("waiting_for") String waitingFor,
        @Column("owner_user_id") String ownerUserId,
        boolean deleted,
        @Column("last_applied_sequence") long lastAppliedSequence,
        @Column("activated_at") Instant activatedAt,
        @Column("completed_at") Instant completedAt,
        @Column("created_by") String createdBy,
        @Column("created_at") Instant createdAt,
        @Column("updated_at") Instant updatedAt) {
}

