package com.asterism.prd;

import com.asterism.context.ContextItem;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

public interface ProductAgentExecutionPort {
    String start(StartExecutionCommand command);

    record StartExecutionCommand(
            @JsonProperty("execution_id") String executionId,
            @JsonProperty("workflow_id") String workflowId,
            @JsonProperty("system_id") String systemId,
            @JsonProperty("prd_id") String prdId,
            @JsonProperty("conversation_id") String conversationId,
            @JsonProperty("input_message_id") String inputMessageId,
            @JsonProperty("context_bundle_id") String contextBundleId,
            String content,
            @JsonProperty("attachment_ids") List<String> attachmentIds,
            @JsonProperty("current_draft") ProductAgentPort.PrdContent currentDraft,
            @JsonProperty("missing_fields") List<String> missingFields,
            @JsonProperty("conversation_history") List<ConversationMessage> conversationHistory,
            @JsonProperty("context_items") List<ContextItem> contextItems,
            int attempt) {

        public StartExecutionCommand {
            attachmentIds = attachmentIds == null ? List.of() : List.copyOf(attachmentIds);
            missingFields = missingFields == null ? List.of() : List.copyOf(missingFields);
            conversationHistory = conversationHistory == null ? List.of() : List.copyOf(conversationHistory);
            contextItems = contextItems == null ? List.of() : List.copyOf(contextItems);
        }
    }
}
