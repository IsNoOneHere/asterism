package com.asterism.knowledge;

import com.asterism.event.DomainEventRecord;
import com.asterism.prd.ConversationMessage;
import com.asterism.prd.ConversationMessageRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class WorkItemKnowledgeLearningServiceTest {
    @Test
    void releaseCompletedCreatesCandidateFromObservationAndChangedPaths() {
        var messages = mock(ConversationMessageRepository.class);
        var knowledge = mock(SystemKnowledgeService.class);
        when(messages.findByPrdIdOrderByCreatedAtAsc("prd-1")).thenReturn(List.of(new ConversationMessage(
                "msg-1", "conv-1", "sys-1", "prd-1", "user", "页面报错", "[\"att-1\"]",
                "[{\"page_title\":\"订单列表\",\"text_anchors\":[\"待发货订单\"]}]", "user", Instant.now())));
        var service = new WorkItemKnowledgeLearningService(messages, knowledge, new ObjectMapper());
        var event = new DomainEventRecord(1L, "evt-1", "ReleaseCompleted", "v5.0", "sys-1", "case-1",
                "prd-1", "wi-1", "worker", "worker", "{\"changedPaths\":[\"src/orders.tsx\"]}",
                "case-1", null, "release-1", Instant.now());

        service.learn(event);

        @SuppressWarnings("unchecked")
        var candidates = ArgumentCaptor.forClass(List.class);
        verify(knowledge).writeCandidates(eq("sys-1"), candidates.capture(), eq("work_item_learning"), eq("asterism-worker"));
        var candidate = (SystemKnowledgeService.CandidateRequest) candidates.getValue().getFirst();
        assertThat(candidate.repo()).isEqualTo("main");
        assertThat(candidate.anchorTexts()).contains("订单列表", "待发货订单");
        assertThat(candidate.codeRefs()).containsExactly("src/orders.tsx");
        assertThat(candidate.sourceRef()).isEqualTo("wi-1");
    }

    @Test
    void releaseCompletedCreatesOneCandidatePerRepository() {
        var messages = mock(ConversationMessageRepository.class);
        var knowledge = mock(SystemKnowledgeService.class);
        when(messages.findByPrdIdOrderByCreatedAtAsc("prd-1")).thenReturn(List.of(new ConversationMessage(
                "msg-1", "conv-1", "sys-1", "prd-1", "user", "页面报错", "[]",
                "[{\"page_title\":\"订单列表\"}]", "user", Instant.now())));
        var service = new WorkItemKnowledgeLearningService(messages, knowledge, new ObjectMapper());
        var event = new DomainEventRecord(1L, "evt-1", "ReleaseCompleted", "v5.0", "sys-1", "case-1",
                "prd-1", "wi-1", "worker", "worker",
                "{\"repositories\":[{\"repo\":\"frontend\",\"changedPaths\":[\"src/app.ts\"]},"
                        + "{\"repo\":\"backend\",\"changedPaths\":[\"src/App.java\"]}]}",
                "case-1", null, "release-1", Instant.now());

        service.learn(event);

        @SuppressWarnings("unchecked")
        var candidates = ArgumentCaptor.forClass(List.class);
        verify(knowledge).writeCandidates(eq("sys-1"), candidates.capture(), eq("work_item_learning"),
                eq("asterism-worker"));
        assertThat((List<SystemKnowledgeService.CandidateRequest>) candidates.getValue())
                .extracting(SystemKnowledgeService.CandidateRequest::repo)
                .containsExactly("frontend", "backend");
    }
}
