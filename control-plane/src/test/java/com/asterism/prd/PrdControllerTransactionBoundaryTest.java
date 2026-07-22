package com.asterism.prd;

import com.asterism.attachment.Attachment;
import com.asterism.context.ContextBundle;
import com.asterism.context.ContextRecallService;
import com.asterism.context.RequirementContextManifestService;
import com.asterism.event.DomainEventService;
import com.asterism.git.GitIntegrationService;
import com.asterism.identity.SystemAccessService;
import com.asterism.knowledge.KnowledgeMatchService;
import com.asterism.memory.MemoryItemRepository;
import com.asterism.system.SystemProfile;
import com.asterism.system.SystemProfileRepository;
import com.asterism.system.AgentConfigurationService;
import com.asterism.system.ExecutionReadinessService;
import com.asterism.temporal.TemporalCasePort;
import com.asterism.vision.ImageAnalysisService;
import com.asterism.vision.UiObservation;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.data.jdbc.core.JdbcAggregateTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionOperations;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class PrdControllerTransactionBoundaryTest {
    @Test
    void messageRunsVisionKnowledgeAndLlmBetweenShortTransactions() {
        var inTransaction = new AtomicBoolean();
        var order = new ArrayList<String>();
        var sessions = mock(PrdSessionRepository.class);
        var messages = mock(ConversationMessageRepository.class);
        var productAgent = mock(ProductAgentPort.class);
        var recall = mock(ContextRecallService.class);
        var aggregate = mock(JdbcAggregateTemplate.class);
        var attachments = mock(com.asterism.attachment.AttachmentService.class);
        var imageAnalysis = mock(ImageAnalysisService.class);
        var knowledge = mock(KnowledgeMatchService.class);
        var attachment = new Attachment("att-1", "sys-1", "user", "screen.png", "image/png", 1,
                "hash", "ha/hash", Instant.now());
        when(messages.countByConversationIdAndSenderType(any(), any())).thenReturn(0L);
        when(messages.completePending(any(), any())).thenReturn(1);
        when(recall.recall(any())).thenAnswer(call -> {
            assertThat(inTransaction).isTrue();
            order.add("recall");
            var query = (com.asterism.context.ContextRecallQuery) call.getArgument(0);
            return new ContextBundle("bundle-1", query.systemId(), query.prdId(), query.phase(), "hash",
                    List.of(), Instant.now());
        });
        when(attachments.requireForSystem("att-1", "sys-1")).thenReturn(attachment);
        when(attachments.read(attachment)).thenAnswer(call -> {
            assertThat(inTransaction).isFalse();
            order.add("image-read");
            return new byte[]{1};
        });
        when(imageAnalysis.analyze(any(), any(), any())).thenAnswer(call -> {
            assertThat(inTransaction).isFalse();
            order.add("vision");
            return new UiObservation("登录", List.of("错误提示"), List.of(), List.of(), "登录页");
        });
        when(knowledge.match(any(), any())).thenAnswer(call -> {
            assertThat(inTransaction).isFalse();
            order.add("knowledge");
            return new KnowledgeMatchService.MatchResult(List.of(), false);
        });
        when(productAgent.updateDraft(any(), any(), any(), any(), any(), any())).thenAnswer(call -> {
            assertThat(inTransaction).isFalse();
            order.add("llm");
            return new ProductAgentPort.DraftResult("登录提示", Map.of("title", "登录提示"), List.of(), "已完成");
        });
        when(aggregate.insert(any())).thenAnswer(call -> {
            assertThat(inTransaction).isTrue();
            return call.getArgument(0);
        });
        when(aggregate.update(any())).thenAnswer(call -> {
            assertThat(inTransaction).isTrue();
            return call.getArgument(0);
        });
        var transactions = new TransactionOperations() {
            @Override
            public <T> T execute(TransactionCallback<T> action) {
                order.add("tx-start");
                inTransaction.set(true);
                try {
                    return action.doInTransaction(null);
                } finally {
                    inTransaction.set(false);
                    order.add("tx-end");
                }
            }
        };
        var service = new PrdConversationService(sessions, messages, productAgent, mock(DomainEventService.class),
                new ObjectMapper(), new PrdDraftCodec(new ObjectMapper()), transactions, mock(SystemAccessService.class),
                recall, new PrdCitationService(), aggregate, attachments,
                imageAnalysis, knowledge, Runnable::run);

        service.message("sys-1", new PrdConversationService.PrdMessageRequest(null, "登录提示", List.of("att-1")),
                new UsernamePasswordAuthenticationToken("user", "n/a"));

        assertThat(order).containsExactly("tx-start", "recall", "tx-end", "image-read", "vision", "llm", "knowledge",
                "tx-start", "tx-end");
    }

    @Test
    void confirmStartsTemporalAfterDatabaseTransaction() {
        var order = new ArrayList<String>();
        var controller = controller(order, null);

        controller.confirm("prd-1", new UsernamePasswordAuthenticationToken("requester", "n/a"));

        assertThat(order.indexOf("tx-end")).isLessThan(order.indexOf("temporal-start"));
        assertThat(order).containsSubsequence("tx-start", "save:case_starting", "event:PRDConfirmed", "tx-end", "temporal-start");
        assertThat(order).contains("event:OwnerApprovalRequested");
    }

    @Test
    void confirmPassesSystemProfileAndAgentSnapshotToTemporalCaseInput() {
        var order = new ArrayList<String>();
        var holder = new AtomicTemporalCommand();
        var controller = controller(order, null, holder);

        controller.confirm("prd-1", new UsernamePasswordAuthenticationToken("requester", "n/a"));

        assertThat(holder.command.repoPath()).isEqualTo("/repo/demo");
        assertThat(holder.command.allowedPaths()).containsExactly("src", "README.md");
        assertThat(holder.command.forbiddenPaths()).containsExactly("secrets");
        assertThat(holder.command.testCommands()).containsExactly("mvn test");
        assertThat(holder.command.repos()).singleElement().satisfies(repo -> {
            assertThat(repo.repoId()).isEqualTo("main");
            assertThat(repo.localPath()).isEqualTo("/repo/demo");
            assertThat(repo.allowedPaths()).containsExactly("src", "README.md");
        });
        assertThat(holder.command.releaseMode()).isEqualTo("local");
        assertThat(holder.command.maxRevisions()).isEqualTo(7);
        assertThat(holder.command.toString()).doesNotContainIgnoringCase("token");
        assertThat(holder.command.agentConfigSnapshot().agents()).singleElement().satisfies(agent -> {
            assertThat(agent.name()).isEqualTo("developer");
            assertThat(agent.engine()).isEqualTo("claude_sdk_team");
            assertThat(agent.maxTurns()).isEqualTo(40);
            assertThat(agent.timeoutSeconds()).isEqualTo(900);
        });
        assertThat(holder.command.agentConfigSnapshot().modelProfiles()).singleElement().satisfies(profile -> {
            assertThat(profile.id()).isEqualTo("mp-1");
            assertThat(profile.model()).isEqualTo("claude-sonnet");
        });
        assertThat(holder.command.agentConfigSnapshot().toString()).doesNotContain("never-in-snapshot");
        assertThat(holder.command.prd().title()).isEqualTo("登录页错误提示");
        assertThat(holder.command.prd().goal()).isEqualTo("把登录页加错误提示");
        assertThat(holder.command.prd().acceptanceCriteria()).containsExactly("错误密码时显示提示");
        assertThat(holder.command.prd().draftJson()).containsEntry("goal", "把登录页加错误提示");
    }

    @Test
    void confirmRecordsRetryableStateWhenTemporalStartFails() {
        var order = new ArrayList<String>();
        var controller = controller(order, new RuntimeException("temporal down"));

        assertThatThrownBy(() -> controller.confirm("prd-1", new UsernamePasswordAuthenticationToken("requester", "n/a")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("可重试");

        assertThat(order).contains("save:case_start_failed", "event:TemporalCaseStartFailed");
        assertThat(order).doesNotContain("event:OwnerApprovalRequested");
    }

    @Test
    void confirmCanRetryAfterCaseStartFailed() {
        var order = new ArrayList<String>();
        var controller = controller(order, null, null, "case_start_failed");

        var response = controller.confirm("prd-1", new UsernamePasswordAuthenticationToken("requester", "n/a"));

        assertThat(response.lifecycleStatus()).isEqualTo("waiting_owner_approval");
        assertThat(response.workItemId()).isEqualTo("WI202607114827");
        assertThat(order).contains("temporal-start", "event:OwnerApprovalRequested");
    }

    @Test
    void confirmReturnsCurrentStateWhenAlreadyStarted() {
        var order = new ArrayList<String>();
        var controller = controller(order, null, null, "waiting_owner_approval");

        var response = controller.confirm("prd-1", new UsernamePasswordAuthenticationToken("requester", "n/a"));

        assertThat(response.lifecycleStatus()).isEqualTo("waiting_owner_approval");
        assertThat(response.workItemId()).isEqualTo("WI202607114827");
        assertThat(order).doesNotContain("temporal-start");
    }

    @Test
    void confirmReusesWorkItemAllocatedByConcurrentRequest() {
        var order = new ArrayList<String>();
        var controller = controller(order, null, null, "waiting_user_confirm", "case_starting");

        var response = controller.confirm("prd-1", new UsernamePasswordAuthenticationToken("requester", "n/a"));

        assertThat(response.workItemId()).isEqualTo("WI202607114827");
        assertThat(response.lifecycleStatus()).isEqualTo("case_starting");
        assertThat(order).doesNotContain("temporal-start", "save:case_starting", "event:PRDConfirmed");
    }

    private PrdController controller(List<String> order, RuntimeException startFailure) {
        return controller(order, startFailure, null, "waiting_user_confirm");
    }

    private PrdController controller(List<String> order, RuntimeException startFailure, AtomicTemporalCommand holder) {
        return controller(order, startFailure, holder, "waiting_user_confirm");
    }

    private PrdController controller(List<String> order, RuntimeException startFailure, AtomicTemporalCommand holder, String sessionStatus) {
        return controller(order, startFailure, holder, sessionStatus, sessionStatus);
    }

    private PrdController controller(List<String> order, RuntimeException startFailure, AtomicTemporalCommand holder,
                                     String visibleStatus, String lockedStatus) {
        var sessions = mock(PrdSessionRepository.class);
        var events = mock(DomainEventService.class);
        var temporal = mock(TemporalCasePort.class);
        var access = mock(SystemAccessService.class);
        var systems = mock(SystemProfileRepository.class);
        var configurations = mock(AgentConfigurationService.class);
        var aggregate = mock(JdbcAggregateTemplate.class);
        var workItemIds = mock(WorkItemIdGenerator.class);
        var readiness = mock(ExecutionReadinessService.class);
        var git = mock(GitIntegrationService.class);
        var manifests = mock(RequirementContextManifestService.class);
        when(manifests.freeze(any(), any(), any(), any(), any(), any())).thenReturn("manifest-1");
        when(manifests.requirementManifestId("prd-1")).thenReturn("manifest-1");
        when(readiness.readiness(any())).thenReturn(new ExecutionReadinessService.SystemReadiness(
                "sys-1", true, Instant.now(), "claude_sdk_team", List.of(), List.of()));
        when(workItemIds.nextId()).thenReturn("WI202607114827");
        when(sessions.findById("prd-1")).thenReturn(Optional.of(session(visibleStatus)), Optional.of(session(lockedStatus)));
        when(systems.findById("sys-1")).thenReturn(Optional.of(system()));
        when(git.internal("sys-1")).thenReturn(new GitIntegrationService.InternalGitConfiguration(
                List.of(new GitIntegrationService.RepoConfig("main", "Demo", "other", "", "main", "local",
                        "/repo/demo", List.of("src", "README.md"), List.of("secrets"), List.of("mvn test"))),
                "local", "auto", "main", List.of(), "", ""));
        when(configurations.internal("sys-1")).thenReturn(new AgentConfigurationService.InternalAgentConfiguration(
                List.of(new AgentConfigurationService.ModelProfile(
                        "mp-1", "Claude", "anthropic", "https://example.invalid", "never-in-snapshot",
                        "claude-sonnet", false)),
                List.of(new AgentConfigurationService.Agent(
                        "developer", "builtin", "claude_sdk_team", "mp-1", List.of("src"), "", 40, 900)), 7));
        when(aggregate.update(any(PrdSession.class))).thenAnswer(call -> {
            var session = (PrdSession) call.getArgument(0);
            order.add("save:" + session.status());
            return session;
        });
        when(events.append(any())).thenAnswer(call -> {
            var event = (DomainEventService.AppendEvent) call.getArgument(0);
            order.add("event:" + event.eventType().name());
            return null;
        });
        when(temporal.startCase(any())).thenAnswer(call -> {
            order.add("temporal-start");
            if (holder != null) {
                holder.command = call.getArgument(0);
            }
            if (startFailure != null) {
                throw startFailure;
            }
            return "case-prd-1";
        });
        TransactionOperations tx = new TransactionOperations() {
            @Override
            public <T> T execute(TransactionCallback<T> action) {
                order.add("tx-start");
                var result = action.doInTransaction(null);
                order.add("tx-end");
                return result;
            }
        };
        var objectMapper = new ObjectMapper();
        var confirmations = new PrdConfirmationService(sessions, events, temporal, objectMapper,
                new PrdDraftCodec(objectMapper), tx, access, systems, configurations, aggregate, workItemIds,
                readiness, git, manifests, new PrdCitationService());
        return new PrdController(mock(PrdConversationService.class), confirmations);
    }

    private PrdSession session(String status) {
        var now = Instant.now();
        var allocated = !"waiting_user_confirm".equals(status);
        return new PrdSession(
                "prd-1",
                "sys-1",
                "conv-prd-1",
                allocated ? "WI202607114827" : null,
                allocated ? "case-prd-1" : null,
                "登录页错误提示",
                "把登录页加错误提示",
                "{\"goal\":\"把登录页加错误提示\",\"acceptanceCriteria\":[\"错误密码时显示提示\"]}",
                "[]",
                status,
                "requester",
                allocated ? "requester" : null,
                allocated ? now : null,
                now,
                now);
    }

    private SystemProfile system() {
        var now = Instant.now();
        return new SystemProfile(
                "sys-1",
                "Demo",
                "demo",
                "/repo/demo",
                "owner",
                "[\"src\",\"README.md\"]",
                "[\"secrets\"]",
                "[\"mvn test\"]",
                "{}",
                "{}",
                "seed",
                now,
                now);
    }

    private static final class AtomicTemporalCommand {
        private TemporalCasePort.StartCaseCommand command;
    }
}
