package com.asterism.prd;

import com.asterism.artifact.Artifact;
import com.asterism.artifact.ArtifactService;
import com.asterism.artifact.ArtifactType;
import com.asterism.context.ContextBundle;
import com.asterism.context.ContextRecallService;
import com.asterism.context.RequirementContextManifestService;
import com.asterism.event.DomainEventService;
import com.asterism.event.DomainEventType;
import com.asterism.git.GitIntegrationService;
import com.asterism.identity.SystemAccessService;
import com.asterism.memory.ArtifactMemoryLifecycleService;
import com.asterism.system.SystemProfile;
import com.asterism.system.SystemProfileRepository;
import com.asterism.system.AgentConfigurationService;
import com.asterism.system.ExecutionReadinessService;
import com.asterism.temporal.TemporalCasePort;
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
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class PrdControllerTransactionBoundaryTest {
    @Test
    void messageCommitsExecutionBeforeStartingTemporal() {
        var inTransaction = new AtomicBoolean();
        var order = new ArrayList<String>();
        var sessions = mock(PrdSessionRepository.class);
        var messages = mock(ConversationMessageRepository.class);
        var executionRepository = mock(ProductAgentExecutionRepository.class);
        var executionService = mock(ProductAgentExecutionService.class);
        var events = mock(DomainEventService.class);
        var recall = mock(ContextRecallService.class);
        var aggregate = mock(JdbcAggregateTemplate.class);
        var attachments = mock(com.asterism.attachment.AttachmentService.class);
        var savedExecution = new AtomicReference<ProductAgentExecution>();
        var createdEvent = new AtomicReference<DomainEventService.AppendEvent>();
        when(recall.recall(any())).thenAnswer(call -> {
            assertThat(inTransaction).isTrue();
            order.add("recall");
            var query = (com.asterism.context.ContextRecallQuery) call.getArgument(0);
            return new ContextBundle("bundle-1", query.systemId(), query.prdId(), query.phase(), "hash",
                    List.of(), Instant.now());
        });
        when(aggregate.insert(any())).thenAnswer(call -> {
            assertThat(inTransaction).isTrue();
            var value = call.getArgument(0);
            if (value instanceof ProductAgentExecution execution) {
                savedExecution.set(execution);
                order.add("execution-created");
            }
            return value;
        });
        when(events.append(any())).thenAnswer(call -> {
            var event = (DomainEventService.AppendEvent) call.getArgument(0);
            if (event.eventType() == DomainEventType.ProductAgentExecutionCreated) {
                createdEvent.set(event);
                order.add("event:execution-created");
            }
            return null;
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
        when(executionService.start(any())).thenAnswer(call -> {
            assertThat(inTransaction).isFalse();
            order.add("temporal-start");
            return savedExecution.get();
        });
        var service = new PrdConversationService(sessions, messages, executionRepository, executionService,
                events,
                new ObjectMapper(), new PrdDraftCodec(new ObjectMapper()), transactions, mock(SystemAccessService.class),
                recall, aggregate, attachments);

        service.message("sys-1", new PrdConversationService.PrdMessageRequest(null, "登录提示", List.of("att-1")),
                new UsernamePasswordAuthenticationToken("user", "n/a"));

        assertThat(order).containsExactly(
                "tx-start", "recall", "event:execution-created", "execution-created", "tx-end", "temporal-start");
        assertThat(createdEvent.get().eventId()).isEqualTo(
                "evt-product-agent-created-" + savedExecution.get().executionId());
        assertThat(createdEvent.get().idempotencyKey()).isEqualTo(
                "ProductAgentExecutionCreated:" + savedExecution.get().executionId());
    }

    @Test
    void confirmStartsTemporalAfterDatabaseTransaction() {
        var order = new ArrayList<String>();
        var controller = controller(order, null);

        controller.confirm("prd-1", new UsernamePasswordAuthenticationToken("requester", "n/a"));

        assertThat(order.indexOf("tx-end")).isLessThan(order.indexOf("temporal-start"));
        assertThat(order).containsSubsequence("tx-start", "save:case_starting", "event:PRDConfirmed", "tx-end", "temporal-start");
        assertThat(order).contains("event:OwnerApprovalRequested", "memory-scheduled");
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
        assertThat(holder.command.prd().requirementManifestId()).isEqualTo("manifest-1");
        assertThat(holder.command.prd().productArtifact().artifactId()).isEqualTo("art-product-1");
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
        assertThat(order).contains("memory-scheduled");
    }

    @Test
    void confirmCanRetryAfterCaseStartFailed() {
        var order = new ArrayList<String>();
        var controller = controller(order, null, null, "case_start_failed");

        var response = controller.confirm("prd-1", new UsernamePasswordAuthenticationToken("requester", "n/a"));

        assertThat(response.lifecycleStatus()).isEqualTo("waiting_owner_approval");
        assertThat(response.workItemId()).isEqualTo("WI202607114827");
        assertThat(order).contains("temporal-start", "event:OwnerApprovalRequested");
        assertThat(order).doesNotContain("event:PRDConfirmed");
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
        when(manifests.requirementItems(any(), any(), any(), any())).thenReturn(List.of());
        var memoryLifecycle = mock(ArtifactMemoryLifecycleService.class);
        var artifactService = mock(ArtifactService.class);
        var artifactTransitions = mock(com.asterism.artifact.ArtifactTransitionService.class);
        // Artifact 是不可变 record，测试直接构造事实数据，避免依赖 final 类型 mock。
        var productArtifact = new Artifact(
                "art-product-1", ArtifactType.PRODUCT, "art-product-1",
                "sys-1", "prd-1", "WI202607114827", "case-prd-1",
                1, com.asterism.artifact.ArtifactStatus.APPROVED,
                null, null, null,
                new ObjectMapper().valueToTree(Map.of("requirementManifestId", "manifest-1")),
                "hash-product-1", "prd-1:product", "requester", Instant.EPOCH,
                "requester", Instant.EPOCH, "");
        var productRef = com.asterism.artifact.ArtifactRef.from(productArtifact);
        when(artifactService.require("art-product-1")).thenReturn(productArtifact);
        when(artifactService.requireCurrentProduct(any(), any())).thenReturn(productArtifact);
        when(artifactTransitions.confirmProduct(any(), any(), any(), any(), any(), any(), any()))
                .thenAnswer(call -> {
                    order.add("event:PRDConfirmed");
                    return new com.asterism.artifact.ArtifactTransitionService.Result(
                            null, productRef, null, null);
                });
        org.mockito.Mockito.doAnswer(call -> {
            order.add("memory-extract");
            return null;
        }).when(memoryLifecycle).schedule(any());
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
                readiness, git, manifests, new PrdCitationService(),
                memoryLifecycle, artifactService, artifactTransitions);
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
