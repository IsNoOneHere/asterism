package com.asterism.prd;

import com.asterism.context.ContextBundle;
import com.asterism.context.ContextRecallService;
import com.asterism.context.RequirementContextManifestService;
import com.asterism.attachment.Attachment;
import com.asterism.attachment.AttachmentService;
import com.asterism.event.DomainEventService;
import com.asterism.git.GitIntegrationService;
import com.asterism.identity.SystemAccessService;
import com.asterism.knowledge.KnowledgeMatchService;
import com.asterism.memory.MemoryItemRepository;
import com.asterism.system.ExecutionReadinessService;
import com.asterism.system.AgentConfigurationService;
import com.asterism.system.SystemProfile;
import com.asterism.system.SystemProfileRepository;
import com.asterism.temporal.TemporalCasePort;
import com.asterism.vision.ImageAnalysisService;
import com.asterism.vision.UiObservation;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.jdbc.core.JdbcAggregateTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionOperations;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

class PrdScreenshotFlowTest {
    @Test
    void screenshotObservationMatchConfirmationEntersCasePayload() {
        var objectMapper = new ObjectMapper();
        var sessions = mock(PrdSessionRepository.class);
        var messages = mock(ConversationMessageRepository.class);
        var aggregate = mock(JdbcAggregateTemplate.class);
        var current = new AtomicReference<PrdSession>();
        when(sessions.findById(anyString())).thenAnswer(call -> Optional.ofNullable(current.get()));
        when(messages.countByConversationIdAndSenderType(anyString(), anyString())).thenReturn(0L);
        when(messages.completePending(anyString(), anyString())).thenReturn(1);
        when(aggregate.insert(any(PrdSession.class))).thenAnswer(call -> {
            var value = (PrdSession) call.getArgument(0);
            current.set(value);
            return value;
        });
        when(aggregate.update(any(PrdSession.class))).thenAnswer(call -> {
            var value = (PrdSession) call.getArgument(0);
            current.set(value);
            return value;
        });
        when(aggregate.insert(any(ConversationMessage.class))).thenAnswer(call -> call.getArgument(0));
        var recall = mock(ContextRecallService.class);
        when(recall.recall(any())).thenAnswer(call -> {
            var query = (com.asterism.context.ContextRecallQuery) call.getArgument(0);
            return new ContextBundle("bundle-1", query.systemId(), query.prdId(), query.phase(), "hash",
                    List.of(), Instant.now());
        });
        var attachments = mock(AttachmentService.class);
        var attachment = new Attachment("att-1", "sys-1", "user", "orders.png", "image/png", 4,
                "hash", "ha/hash", Instant.now());
        when(attachments.requireForSystem("att-1", "sys-1")).thenReturn(attachment);
        when(attachments.read(attachment)).thenReturn(new byte[]{1});
        var imageAnalysis = mock(ImageAnalysisService.class);
        when(imageAnalysis.analyze(eq("sys-1"), eq(attachment), any(byte[].class))).thenReturn(new UiObservation(
                "订单列表", List.of("待发货订单"), List.of("搜索按钮"), List.of(), "订单列表页"));
        var knowledge = mock(KnowledgeMatchService.class);
        var target = new KnowledgeMatchService.SuspectedTarget("knowledge-1", "page", "订单列表", "/orders",
                List.of("GET /api/orders"), List.of("src/orders.tsx"), 0.92);
        var secondTarget = new KnowledgeMatchService.SuspectedTarget("knowledge-2", "api", "订单详情", "/orders/{id}",
                List.of("GET /api/orders/{id}"), List.of("src/orders.tsx"), 0.81);
        var rejectedTarget = new KnowledgeMatchService.SuspectedTarget("knowledge-3", "page", "商品列表", "/products",
                List.of("GET /api/products"), List.of("src/products.tsx"), 0.62);
        when(knowledge.match(eq("sys-1"), any())).thenReturn(
                new KnowledgeMatchService.MatchResult(List.of(target, secondTarget, rejectedTarget), false));
        var systems = mock(SystemProfileRepository.class);
        var now = Instant.now();
        var system = new SystemProfile("sys-1", "订单系统", "", "/repo", "owner", "[\"src\"]", "[]",
                "[\"test\"]", "{}", "{}", "owner", now, now);
        when(systems.findById("sys-1")).thenReturn(Optional.of(system));
        var readiness = mock(ExecutionReadinessService.class);
        when(readiness.readiness(system)).thenReturn(new ExecutionReadinessService.SystemReadiness(
                "sys-1", true, now, "claude_sdk_team", List.of(), List.of()));
        var ids = mock(WorkItemIdGenerator.class);
        when(ids.nextId()).thenReturn("WI202607141234");
        var temporal = mock(TemporalCasePort.class);
        var git = mock(GitIntegrationService.class);
        when(git.internal("sys-1")).thenReturn(new GitIntegrationService.InternalGitConfiguration(
                List.of(new GitIntegrationService.RepoConfig("main", "订单系统", "other", "", "main",
                        "local", "/repo", List.of("src"), List.of(), List.of("test"))),
                "local", "auto", "main", List.of(), "", ""));
        var configurations = mock(AgentConfigurationService.class);
        when(configurations.internal("sys-1")).thenReturn(new AgentConfigurationService.InternalAgentConfiguration(
                List.of(), List.of(new AgentConfigurationService.Agent(
                        "developer", "builtin", "claude_sdk_team", "", List.of(), "", 50, 600)), 5));
        var transactions = new TransactionOperations() {
            @Override
            public <T> T execute(TransactionCallback<T> action) {
                return action.doInTransaction(null);
            }
        };
        var events = mock(DomainEventService.class);
        var access = mock(SystemAccessService.class);
        var conversations = new PrdConversationService(sessions, messages, new FakeProductAgentAdapter(), events,
                objectMapper, new PrdDraftCodec(objectMapper), transactions, access, recall,
                new PrdCitationService(), aggregate, attachments, imageAnalysis, knowledge,
                Runnable::run);
        var manifests = mock(RequirementContextManifestService.class);
        when(manifests.freeze(anyString(), anyString(), anyString(), any(), anyString(), anyString()))
                .thenReturn("manifest-1");
        var confirmations = new PrdConfirmationService(sessions, events, temporal, objectMapper,
                new PrdDraftCodec(objectMapper), transactions, access, systems, configurations, aggregate, ids,
                readiness, git, manifests, new PrdCitationService());
        var controller = new PrdController(conversations, confirmations);
        var actor = new UsernamePasswordAuthenticationToken("user", "n/a");

        var message = controller.message("sys-1",
                new PrdConversationService.PrdMessageRequest(null, "验收：订单列表可搜索", List.of("att-1")), actor);
        assertThat(message.assistantPending()).isTrue();
        verify(messages).completePending(anyString(), argThat(content ->
                content.contains("你反馈的是不是【订单列表】") && content.contains("GET /api/orders")));
        assertThat(current.get().draftJson()).contains("suspectedTargets");

        controller.confirmTargets(message.prdId(),
                new PrdController.TargetConfirmationRequest(List.of("knowledge-3"), false), actor);
        assertThat(current.get().draftJson()).doesNotContain("knowledge-3");
        controller.confirmTargets(message.prdId(), new PrdController.TargetConfirmationRequest(List.of("knowledge-1")), actor);
        controller.confirmTargets(message.prdId(), new PrdController.TargetConfirmationRequest(List.of("knowledge-2")), actor);
        controller.confirm(message.prdId(), actor);

        var command = ArgumentCaptor.forClass(TemporalCasePort.StartCaseCommand.class);
        verify(temporal).startCase(command.capture());
        @SuppressWarnings("unchecked")
        var targets = (List<Map<String, Object>>) command.getValue().prd().draftJson().get("targets");
        assertThat(targets).hasSize(2);
        assertThat(targets.getFirst()).satisfies(value -> {
            assertThat(value.get("title")).isEqualTo("订单列表");
            assertThat(value.get("apiEndpoints")).isEqualTo(List.of("GET /api/orders"));
        });
    }
}
