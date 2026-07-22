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
        @Column("attachment_ids") String attachmentIds,
        @Column("observations_json") String observationsJson,
        @Column("context_bundle_id") String contextBundleId,
        @Column("used_context_refs") String usedContextRefs,
        @Column("citations_json") String citationsJson,
        @Column("created_by") String createdBy,
        @Column("created_at") Instant createdAt) {

    public ConversationMessage(String messageId, String conversationId, String systemId, String prdId,
                               String senderType, String content, String attachmentIds, String observationsJson,
                               String createdBy, Instant createdAt) {
        this(messageId, conversationId, systemId, prdId, senderType, content, attachmentIds, observationsJson,
                null, "[]", "{}", createdBy, createdAt);
    }
}
