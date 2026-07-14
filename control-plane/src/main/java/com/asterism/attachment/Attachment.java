package com.asterism.attachment;

import org.springframework.data.annotation.Id;
import org.springframework.data.relational.core.mapping.Column;
import org.springframework.data.relational.core.mapping.Table;

import java.time.Instant;

@Table("attachments")
public record Attachment(
        @Id @Column("attachment_id") String attachmentId,
        @Column("system_id") String systemId,
        String uploader,
        String filename,
        @Column("content_type") String contentType,
        @Column("size_bytes") long sizeBytes,
        String sha256,
        @Column("storage_path") String storagePath,
        @Column("created_at") Instant createdAt) {
}
