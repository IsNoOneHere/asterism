package com.asterism.memory;

import org.springframework.data.annotation.Id;
import org.springframework.data.relational.core.mapping.Column;
import org.springframework.data.relational.core.mapping.Table;

import java.time.Instant;

@Table("memory_items")
public record MemoryItem(
        @Id @Column("memory_id") String memoryId,
        @Column("system_id") String systemId,
        String content,
        String status,
        @Column("source_event_id") String sourceEventId,
        @Column("approved_by") String approvedBy,
        @Column("metadata_json") String metadataJson,
        @Column("created_by") String createdBy,
        @Column("created_at") Instant createdAt,
        @Column("approved_at") Instant approvedAt) {
}

