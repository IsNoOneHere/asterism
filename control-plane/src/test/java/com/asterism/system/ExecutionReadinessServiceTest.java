package com.asterism.system;

import com.asterism.git.GitIntegrationService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ExecutionReadinessServiceTest {
    @Test
    void requiresLiveClaudeSdkTeamWorkerAndWarnsForUnrestrictedPaths() {
        var service = new ExecutionReadinessService(new ObjectMapper());
        assertThat(service.readiness(profile("claude_sdk_team")).ready()).isFalse();

        service.report(report(true, "system"));

        var result = service.readiness(profile("claude_sdk_team"));
        assertThat(result.ready()).isTrue();
        assertThat(result.issues()).extracting(ExecutionReadinessService.ReadinessIssue::code)
                .containsExactly("ALLOWED_PATHS_EMPTY");
        assertThat(result.stages()).extracting(ExecutionReadinessService.ReadinessStage::name)
                .containsExactly("prd", "codeExecution", "repository", "validation");
    }

    @Test
    void fakeEngineCannotBecomeBusinessReady() {
        var service = new ExecutionReadinessService(new ObjectMapper());
        service.report(new ExecutionReadinessService.WorkerReadinessReport(
                "worker-1", "asterism", "fake", List.of("fake"), false,
                Instant.now(), List.of(new ExecutionReadinessService.TargetReadiness(
                        "sys-1", true, true, true, "prd-model", false, "", "unconfigured")), null));

        assertThat(service.readiness(profile("fake")).issues())
                .extracting(ExecutionReadinessService.ReadinessIssue::code)
                .contains("FAKE_EXECUTION_FORBIDDEN");
    }

    @Test
    void warnsWhenDeveloperUsesWorkerEnvironmentProfile() {
        var service = new ExecutionReadinessService(new ObjectMapper());
        service.report(report(true, "worker_env"));

        assertThat(service.readiness(profile("claude_sdk_team")).issues())
                .extracting(ExecutionReadinessService.ReadinessIssue::code)
                .contains("CLAUDE_CONFIG_FALLBACK");
    }

    @Test
    void gitlabModeRequiresEveryConfiguredProject() {
        var git = mock(GitIntegrationService.class);
        var repo = new GitIntegrationService.RepoConfig("web", "Web", "frontend", "group/web", "main",
                "gitlab", "", List.of("src"), List.of(), List.of("npm test"));
        when(git.get("sys-1")).thenReturn(new GitIntegrationService.PublicGitConfiguration(
                List.of(repo), "gitlab", "auto", "main", List.of(), "", "http://gitlab.test", true, true));
        when(git.readiness("sys-1")).thenReturn(new GitIntegrationService.GitReadiness(false, List.of("group/web")));
        var service = new ExecutionReadinessService(new ObjectMapper(), git);
        service.report(report(true, "system"));

        assertThat(service.readiness(profile("claude_sdk_team")).issues())
                .extracting(ExecutionReadinessService.ReadinessIssue::code)
                .contains("GITLAB_PROJECT_NOT_READY")
                .doesNotContain("REPOSITORY_NOT_READY");
    }

    private ExecutionReadinessService.WorkerReadinessReport report(boolean teamReady, String source) {
        return new ExecutionReadinessService.WorkerReadinessReport(
                "worker-1", "asterism", "claude_sdk_team", List.of("fake", "claude_sdk_team"), false,
                Instant.now(), List.of(new ExecutionReadinessService.TargetReadiness(
                        "sys-1", true, true, true, "prd-model", teamReady, "claude-model", source)), null);
    }

    private SystemProfile profile(String engine) {
        var now = Instant.now();
        return new SystemProfile("sys-1", "系统", "", "/repo", "owner", "[]", "[]", "[\"mvn test\"]",
                "{}", "{\"agents\":[{\"name\":\"developer\",\"kind\":\"builtin\",\"engine\":\""
                + engine + "\",\"modelProfileRef\":\"\"}]}", "admin", now, now);
    }
}
