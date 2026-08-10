package com.asterism.prd;

import org.springframework.data.repository.CrudRepository;

import java.util.List;

public interface ConversationMessageRepository extends CrudRepository<ConversationMessage, String> {
    List<ConversationMessage> findByConversationIdOrderByCreatedAtAsc(String conversationId);

    List<ConversationMessage> findByPrdIdOrderByCreatedAtAsc(String prdId);
}
