package com.asterism.prd;

import org.springframework.data.repository.CrudRepository;
import org.springframework.data.jdbc.repository.query.Modifying;
import org.springframework.data.jdbc.repository.query.Query;

import java.util.List;
import java.util.Optional;

public interface ConversationMessageRepository extends CrudRepository<ConversationMessage, String> {
    List<ConversationMessage> findByConversationIdOrderByCreatedAtAsc(String conversationId);

    long countByConversationIdAndSenderType(String conversationId, String senderType);

    List<ConversationMessage> findByPrdIdOrderByCreatedAtAsc(String prdId);

    Optional<ConversationMessage> findFirstByConversationIdAndSenderTypeOrderByCreatedAtAsc(
            String conversationId, String senderType);

    @Modifying
    @Query("""
            update conversation_messages
            set sender_type = 'assistant', content = :content
            where message_id = :messageId and sender_type = 'assistant_pending'
            """)
    int completePending(String messageId, String content);
}
