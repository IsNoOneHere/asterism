package com.agentteam.v5.event;

import org.springframework.data.annotation.Id;
import org.springframework.data.relational.core.mapping.Column;
import org.springframework.data.relational.core.mapping.Table;

import java.time.Instant;

@Table("domain_events")
public record DomainEventRecord(
        @Id Long sequence,
        @Column("event_id") String eventId,
        @Column("event_type") String eventType,
        @Column("schema_version") String schemaVersion,
        @Column("system_id") String systemId,
        @Column("case_id") String caseId,
        @Column("prd_id") String prdId,
        @Column("work_item_id") String workItemId,
        @Column("actor_id") String actorId,
        String source,
        @Column("payload_json") String payloadJson,
        @Column("correlation_id") String correlationId,
        @Column("causation_id") String causationId,
        @Column("idempotency_key") String idempotencyKey,
        @Column("created_at") Instant createdAt) {
}

