package com.asterism.prd;

import java.time.Instant;

public record ProductAgentExecutionView(
        String executionId,
        String prdId,
        ProductAgentExecutionStatus status,
        String workflowId,
        String inputMessageId,
        String contextBundleId,
        String stage,
        int attempt,
        String failureCode,
        Instant startedAt,
        Instant completedAt,
        Instant lastHeartbeat,
        String resultMessageId,
        Instant createdAt,
        Instant updatedAt) {

    public static ProductAgentExecutionView from(ProductAgentExecution execution) {
        if (execution == null) return null;
        return new ProductAgentExecutionView(
                execution.executionId(), execution.prdId(), execution.status(), execution.workflowId(),
                execution.inputMessageId(), execution.contextBundleId(), execution.stage(), execution.attempt(),
                execution.failureCode(), execution.startedAt(), execution.completedAt(), execution.lastHeartbeat(),
                execution.resultMessageId(), execution.createdAt(), execution.updatedAt());
    }
}
