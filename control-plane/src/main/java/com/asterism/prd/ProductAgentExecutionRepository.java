package com.asterism.prd;

import org.springframework.data.jdbc.repository.query.Modifying;
import org.springframework.data.jdbc.repository.query.Query;
import org.springframework.data.repository.CrudRepository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

public interface ProductAgentExecutionRepository extends CrudRepository<ProductAgentExecution, String> {
    @Query("""
            select * from product_agent_executions
            where prd_id = :prdId and status in ('CREATED', 'RUNNING')
            order by created_at desc limit 1
            """)
    Optional<ProductAgentExecution> findActiveByPrdId(String prdId);

    @Query("""
            select * from product_agent_executions
            where prd_id = :prdId
            order by created_at desc limit 1
            """)
    Optional<ProductAgentExecution> findLatestByPrdId(String prdId);

    Optional<ProductAgentExecution> findByWorkflowId(String workflowId);

    @Query("""
            select * from product_agent_executions
            where status = 'CREATED' and updated_at < :cutoff
            order by updated_at asc
            limit 50
            """)
    List<ProductAgentExecution> findCreatedBefore(Instant cutoff);

    @Modifying
    @Query("""
            update product_agent_executions
            set attempt = attempt + 1, failure_code = null, updated_at = :updatedAt
            where execution_id = :executionId and status = 'CREATED'
            """)
    int recordStartAttempt(String executionId, Instant updatedAt);

    @Modifying
    @Query("""
            update product_agent_executions
            set failure_code = :failureCode, updated_at = :updatedAt
            where execution_id = :executionId and status = 'CREATED'
            """)
    int recordStartFailure(String executionId, String failureCode, Instant updatedAt);

    @Modifying
    @Query("""
            update product_agent_executions
            set status = 'RUNNING', stage = :stage, attempt = :attempt,
                failure_code = null, started_at = coalesce(started_at, :startedAt),
                last_heartbeat = :startedAt, updated_at = :startedAt
            where execution_id = :executionId and status = 'CREATED'
            """)
    int markStarted(String executionId, String stage, int attempt, Instant startedAt);

    @Modifying
    @Query("""
            update product_agent_executions
            set stage = :stage, attempt = :attempt,
                last_heartbeat = :heartbeatAt, updated_at = :heartbeatAt
            where execution_id = :executionId and status = 'RUNNING'
            """)
    int heartbeat(String executionId, String stage, int attempt, Instant heartbeatAt);

    @Modifying
    @Query("""
            update product_agent_executions
            set status = 'COMPLETED', stage = :stage, attempt = :attempt, completed_at = :completedAt,
                started_at = coalesce(started_at, :completedAt), last_heartbeat = :completedAt,
                failure_code = null, updated_at = :completedAt
            where execution_id = :executionId and status in ('CREATED', 'RUNNING')
            """)
    int markCompleted(String executionId, String stage, int attempt, Instant completedAt);

    @Modifying
    @Query("""
            update product_agent_executions
            set result_message_id = :messageId, updated_at = :updatedAt
            where execution_id = :executionId and status = 'COMPLETED' and result_message_id is null
            """)
    int attachResultMessage(String executionId, String messageId, Instant updatedAt);

    @Modifying
    @Query("""
            update product_agent_executions
            set status = 'FAILED', stage = :stage, failure_code = :failureCode,
                completed_at = :completedAt, updated_at = :completedAt
            where execution_id = :executionId and status in ('CREATED', 'RUNNING')
            """)
    int markFailed(String executionId, String stage, String failureCode, Instant completedAt);

    @Modifying
    @Query("""
            update product_agent_executions
            set status = 'CANCELLED', stage = :stage, failure_code = :failureCode,
                completed_at = :completedAt, updated_at = :completedAt
            where execution_id = :executionId and status in ('CREATED', 'RUNNING')
            """)
    int markCancelled(String executionId, String stage, String failureCode, Instant completedAt);
}
