package com.asterism.prd;

import org.springframework.data.annotation.Id;
import org.springframework.data.relational.core.mapping.Column;
import org.springframework.data.relational.core.mapping.Table;

import java.time.Instant;

@Table("product_agent_executions")
public record ProductAgentExecution(
        @Id @Column("execution_id") String executionId,
        @Column("prd_id") String prdId,
        ProductAgentExecutionStatus status,
        @Column("workflow_id") String workflowId,
        @Column("input_message_id") String inputMessageId,
        @Column("context_bundle_id") String contextBundleId,
        String stage,
        int attempt,
        @Column("failure_code") String failureCode,
        @Column("started_at") Instant startedAt,
        @Column("completed_at") Instant completedAt,
        @Column("last_heartbeat") Instant lastHeartbeat,
        @Column("result_message_id") String resultMessageId,
        @Column("created_at") Instant createdAt,
        @Column("updated_at") Instant updatedAt) {
}
