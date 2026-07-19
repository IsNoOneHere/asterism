package com.asterism.workitem;

import com.asterism.event.DomainEventRecord;
import com.asterism.event.DomainEventService;
import com.asterism.git.GitIntegrationService;
import com.asterism.git.GitLabClient;
import com.asterism.identity.SystemAccessService;
import com.asterism.prd.PrdSessionRepository;
import com.asterism.projection.WorkItemProjection;
import com.asterism.projection.WorkItemProjectionRepository;
import com.asterism.system.AgentConfigurationService;
import com.asterism.temporal.TemporalCasePort;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionOperations;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class WorkItemMergeStatusTest {
    @Test
    void manualMergeCheckVerifiesGitLabBeforeSignallingTemporal() {
        var fixture = fixture("merged");

        var response = fixture.controller.checkMergeStatus("wi-1", null, fixture.actor);

        assertThat(response.status()).isEqualTo("submitted");
        verify(fixture.temporal).signalCase(argThat(command ->
                "check_merge_status".equals(command.signalName()) && "case-1".equals(command.caseId())));
    }

    @Test
    void manualMergeCheckRejectsUnmergedRequestWithoutSignal() {
        var fixture = fixture("opened");

        assertThatThrownBy(() -> fixture.controller.checkMergeStatus("wi-1", null, fixture.actor))
                .hasMessageContaining("仍有 MR 未合并");
        verify(fixture.temporal, never()).signalCase(any());
    }

    @Test
    void frozenProjectFromEventWinsOverCurrentRepositoryConfiguration() {
        var fixture = fixture("merged");

        fixture.controller.checkMergeStatus("wi-1", null, fixture.actor);

        verify(fixture.gitLab).mergeRequest("https://gitlab", "secret", "frozen/api", 9);
    }

    private Fixture fixture(String mrState) {
        var workItems = mock(WorkItemProjectionRepository.class);
        var temporal = mock(TemporalCasePort.class);
        var events = mock(DomainEventService.class);
        var access = mock(SystemAccessService.class);
        var git = mock(GitIntegrationService.class);
        var gitLab = mock(GitLabClient.class);
        var actor = new UsernamePasswordAuthenticationToken("owner", "n/a");
        var now = Instant.now();
        var item = new WorkItemProjection(
                "wi-1", "WI20260710001", "sys-1", "prd-1", "case-1", "任务", "waiting_merge", "approved", false,
                "等待 GitLab 合并", "gitlab", "owner", false, 6, now, null, "owner", now, now);
        when(workItems.findById("wi-1")).thenReturn(Optional.of(item));
        when(workItems.lockById("wi-1")).thenReturn(Optional.of(item));
        when(events.findByWorkItemId("wi-1")).thenReturn(List.of(new DomainEventRecord(
                6L, "evt-6", "MergeRequestCreated", "v5.0", "sys-1", "case-1", "prd-1", "wi-1",
                "worker", "worker", "{\"repo\":\"backend\",\"project\":\"frozen/api\",\"mrIid\":9}", "case-1",
                "patch-1:mr:backend:9", "key-6", now)));
        when(git.internal("sys-1")).thenReturn(new GitIntegrationService.InternalGitConfiguration(
                List.of(new GitIntegrationService.RepoConfig("backend", "API", "backend", "current/api", "main",
                        "gitlab", "", List.of(), List.of(), List.of("test"))),
                "gitlab", "auto", "main", List.of(), "https://gitlab", "secret"));
        when(gitLab.mergeRequest("https://gitlab", "secret", "frozen/api", 9))
                .thenReturn(new GitLabClient.MergeRequestStatus(9, mrState, "https://gitlab/mr/9"));
        var actionService = new WorkItemActionService(workItems, temporal, events, access,
                mock(AgentConfigurationService.class), new ObjectMapper(), directTransactions());
        var controller = new WorkItemController(workItems, events, actionService, access,
                mock(PrdSessionRepository.class), new ObjectMapper(), git, gitLab);
        return new Fixture(controller, temporal, gitLab, actor);
    }

    private TransactionOperations directTransactions() {
        return new TransactionOperations() {
            @Override
            public <T> T execute(TransactionCallback<T> action) {
                return action.doInTransaction(null);
            }
        };
    }

    private record Fixture(WorkItemController controller, TemporalCasePort temporal, GitLabClient gitLab,
                           UsernamePasswordAuthenticationToken actor) {
    }
}
