package com.asterism.prd;

import org.springframework.data.annotation.Id;
import org.springframework.data.relational.core.mapping.Column;
import org.springframework.data.relational.core.mapping.Table;

import java.time.Instant;

@Table("conversation_messages")
public record ConversationMessage(
        @Id @Column("message_id") String messageId,
        @Column("conversation_id") String conversationId,
        @Column("system_id") String systemId,
        @Column("prd_id") String prdId,
        @Column("sender_type") String senderType,
        String content,
        @Column("created_by") String createdBy,
        @Column("created_at") Instant createdAt) {
}

