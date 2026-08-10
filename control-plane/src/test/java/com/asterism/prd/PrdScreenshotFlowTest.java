package com.asterism.prd;

import com.asterism.artifact.Artifact;
import com.asterism.artifact.ArtifactService;
import com.asterism.artifact.ArtifactType;
import com.asterism.context.ContextBundle;
import com.asterism.context.ContextBundleStore;
import com.asterism.context.ContextRecallService;
import com.asterism.context.RequirementContextManifestService;
import com.asterism.attachment.Attachment;
import com.asterism.attachment.AttachmentService;
import com.asterism.event.DomainEventService;
import com.asterism.git.GitIntegrationService;
import com.asterism.identity.SystemAccessService;
import com.asterism.knowledge.KnowledgeMatchService;
import com.asterism.memory.ArtifactMemoryLifecycleService;
import com.asterism.system.ExecutionReadinessService;
import com.asterism.system.AgentConfigurationService;
import com.asterism.system.SystemProfile;
import com.asterism.system.SystemProfileRepository;
import com.asterism.temporal.TemporalCasePort;
import com.asterism.vision.UiObservation;
import com.asterism.vision.UiElement;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.jdbc.core.JdbcAggregateTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionOperations;

import java.time.Instant;
import java.util.List;
import java.util.ArrayList;
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
        var executions = mock(ProductAgentExecutionRepository.class);
        var aggregate = mock(JdbcAggregateTemplate.class);
        var current = new AtomicReference<PrdSession>();
        var currentExecution = new AtomicReference<ProductAgentExecution>();
        var savedMessages = new ArrayList<ConversationMessage>();
        when(sessions.findById(anyString())).thenAnswer(call -> Optional.ofNullable(current.get()));
        when(messages.findById(anyString())).thenAnswer(call -> savedMessages.stream()
                .filter(message -> message.messageId().equals(call.getArgument(0))).findFirst());
        when(messages.findByConversationIdOrderByCreatedAtAsc(anyString())).thenAnswer(call -> List.copyOf(savedMessages));
        when(executions.findById(anyString())).thenAnswer(call -> Optional.ofNullable(currentExecution.get()));
        when(executions.findActiveByPrdId(anyString())).thenAnswer(call -> Optional.ofNullable(currentExecution.get())
                .filter(execution -> execution.status().active()));
        when(executions.recordStartAttempt(anyString(), any())).thenReturn(1);
        when(executions.markStarted(anyString(), anyString(), anyInt(), any())).thenAnswer(call -> {
            currentExecution.set(withStatus(currentExecution.get(), ProductAgentExecutionStatus.RUNNING, null));
            return 1;
        });
        when(executions.markCompleted(anyString(), anyString(), anyInt(), any())).thenAnswer(call -> {
            currentExecution.set(withStatus(currentExecution.get(), ProductAgentExecutionStatus.COMPLETED, null));
            return 1;
        });
        when(executions.attachResultMessage(anyString(), anyString(), any())).thenAnswer(call -> {
            currentExecution.set(withStatus(
                    currentExecution.get(), ProductAgentExecutionStatus.COMPLETED, call.getArgument(1)));
            return 1;
        });
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
        when(aggregate.insert(any(ConversationMessage.class))).thenAnswer(call -> {
            savedMessages.add(call.getArgument(0));
            return call.getArgument(0);
        });
        when(aggregate.update(any(ConversationMessage.class))).thenAnswer(call -> call.getArgument(0));
        when(aggregate.insert(any(ProductAgentExecution.class))).thenAnswer(call -> {
            currentExecution.set(call.getArgument(0));
            return call.getArgument(0);
        });
        var recall = mock(ContextRecallService.class);
        when(recall.recall(any())).thenAnswer(call -> {
            var query = (com.asterism.context.ContextRecallQuery) call.getArgument(0);
            return new ContextBundle("bundle-1", query.systemId(), query.prdId(), query.phase(), "hash",
                    List.of(), Instant.now());
        });
        var bundles = mock(ContextBundleStore.class);
        when(bundles.find("bundle-1")).thenReturn(Optional.of(new ContextBundle(
                "bundle-1", "sys-1", null, "product", "hash", List.of(), Instant.now())));
        var attachments = mock(AttachmentService.class);
        var attachment = new Attachment("att-1", "sys-1", "user", "orders.png", "image/png", 4,
                "hash", "ha/hash", Instant.now());
        when(attachments.requireForSystem("att-1", "sys-1")).thenReturn(attachment);
        var observation = new UiObservation(
                "订单列表", List.of("待发货订单"), List.of(new UiElement("button", "搜索按钮")),
                List.of(), "订单列表页");
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
        var executionService = new ProductAgentExecutionService(
                executions, sessions, messages, bundles, new FakeProductAgentExecutionAdapter(), objectMapper,
                new PrdDraftCodec(objectMapper), new PrdCitationService(), aggregate, knowledge, events, transactions);
        var conversations = new PrdConversationService(
                sessions, messages, executions, executionService, events, objectMapper,
                new PrdDraftCodec(objectMapper), transactions, access, recall, aggregate, attachments);
        var manifests = mock(RequirementContextManifestService.class);
        when(manifests.freeze(anyString(), anyString(), anyString(), any(), anyString(), anyString()))
                .thenReturn("manifest-1");
        var artifactService = mock(ArtifactService.class);
        var artifactTransitions = mock(com.asterism.artifact.ArtifactTransitionService.class);
        var productArtifact = new Artifact(
                "art-product-1", ArtifactType.PRODUCT, "art-product-1",
                "sys-1", "prd-1", "WI20260701001", "case-prd-1", 1,
                com.asterism.artifact.ArtifactStatus.APPROVED, null, null,
                null, objectMapper.createObjectNode(), "hash-product-1", "product-1",
                "user", java.time.Instant.now(), "user", java.time.Instant.now(), "PRD 已确认");
        var productRef = com.asterism.artifact.ArtifactRef.from(productArtifact);
        when(artifactTransitions.confirmProduct(any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(new com.asterism.artifact.ArtifactTransitionService.Result(
                        null, productRef, null, null));
        when(artifactService.require("art-product-1")).thenReturn(productArtifact);
        var confirmations = new PrdConfirmationService(sessions, events, temporal, objectMapper,
                new PrdDraftCodec(objectMapper), transactions, access, systems, configurations, aggregate, ids,
                readiness, git, manifests, new PrdCitationService(),
                mock(ArtifactMemoryLifecycleService.class), artifactService, artifactTransitions);
        var controller = new PrdController(conversations, confirmations);
        var actor = new UsernamePasswordAuthenticationToken("user", "n/a");

        var message = controller.message("sys-1",
                new PrdConversationService.PrdMessageRequest(null, "验收：订单列表可搜索", List.of("att-1")), actor);
        assertThat(message.status()).isEqualTo(ProductAgentExecutionStatus.CREATED);
        executionService.apply(message.executionId(), new ProductAgentExecutionEvent(
                "evt-started-1", null,
                ProductAgentExecutionEvent.EventType.Started, "analyzing", 1, null,
                null, null, List.of(), false));
        executionService.apply(message.executionId(), new ProductAgentExecutionEvent(
                "evt-completed-1", null,
                ProductAgentExecutionEvent.EventType.Completed, "draft_completed", 1, null,
                new ProductAgentPort.DraftResult(
                        new ProductAgentPort.PrdPatch(
                                "订单搜索", "让用户查找订单", "code_change", List.of("订单列表可搜索")),
                        "PRD draft 已就绪，请确认。", Map.of()),
                null, List.of(observation), false));
        assertThat(savedMessages.stream()
                .filter(saved -> "assistant".equals(saved.senderType()))
                .map(ConversationMessage::content))
                .allMatch(content -> !content.contains("/orders")
                        && !content.contains("GET /api/orders")
                        && !content.contains("src/orders.tsx"));
        assertThat(current.get().draftJson()).contains("suspectedTargets");

        controller.confirmTargets(message.prdId(),
                new PrdController.TargetConfirmationRequest(List.of("knowledge-3"), false), actor);
        assertThat(current.get().draftJson()).doesNotContain("knowledge-3");
        controller.confirmTargets(message.prdId(), new PrdController.TargetConfirmationRequest(List.of("knowledge-1")), actor);
        controller.confirmTargets(message.prdId(), new PrdController.TargetConfirmationRequest(List.of("knowledge-2")), actor);
        controller.confirm(message.prdId(), actor);

        var content = ArgumentCaptor.forClass(com.asterism.artifact.ProductArtifactContent.class);
        verify(artifactTransitions).confirmProduct(
                any(), any(), any(), content.capture(), any(), any(), any());
        var targets = content.getValue().targets();
        assertThat(targets).hasSize(2);
        assertThat(targets.getFirst()).satisfies(value -> {
            assertThat(value.title()).isEqualTo("订单列表");
            assertThat(value.apiEndpoints()).isEqualTo(List.of("GET /api/orders"));
        });
        var command = ArgumentCaptor.forClass(TemporalCasePort.StartCaseCommand.class);
        verify(temporal).startCase(command.capture());
        assertThat(command.getValue().prd().productArtifact().artifactId()).isEqualTo("art-product-1");
    }

    private ProductAgentExecution withStatus(ProductAgentExecution current, ProductAgentExecutionStatus status,
                                             String resultMessageId) {
        var now = Instant.now();
        return new ProductAgentExecution(
                current.executionId(), current.prdId(), status, current.workflowId(), current.inputMessageId(),
                current.contextBundleId(), status.name(), Math.max(current.attempt(), 1), current.failureCode(),
                current.startedAt() == null ? now : current.startedAt(), status.terminal() ? now : null,
                now, resultMessageId, current.createdAt(), now);
    }
}
