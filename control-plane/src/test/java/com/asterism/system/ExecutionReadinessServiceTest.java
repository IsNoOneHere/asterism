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
    void requiresLiveWorkerAndReportsWarningForUnrestrictedPaths() {
        var service = new ExecutionReadinessService(new ObjectMapper());
        assertThat(service.readiness(profile("claude_sdk")).ready()).isFalse();

        service.report(new ExecutionReadinessService.WorkerReadinessReport(
                "worker-1", "asterism", "http", List.of("http", "claude_sdk"), true, false,
                Instant.now(), List.of(new ExecutionReadinessService.TargetReadiness(
                        "sys-1", true, true, true, "gpt-4.1-mini",
                        true, "deepseek-v4-pro", "system")), null));

        var result = service.readiness(profile("claude_sdk"));
        assertThat(result.ready()).isTrue();
        assertThat(result.issues()).extracting(ExecutionReadinessService.ReadinessIssue::code)
                .containsExactly("ALLOWED_PATHS_EMPTY");
    }

    @Test
    void fakeProviderCannotBecomeBusinessReady() {
        var service = new ExecutionReadinessService(new ObjectMapper());
        service.report(new ExecutionReadinessService.WorkerReadinessReport(
                "worker-1", "asterism", "fake", List.of("fake"), true, false,
                Instant.now(), List.of(new ExecutionReadinessService.TargetReadiness(
                        "sys-1", true, true, true, "model", false, "", "unconfigured")), null));

        assertThat(service.readiness(profile("fake")).issues()).extracting(ExecutionReadinessService.ReadinessIssue::code)
                .contains("FAKE_EXECUTION_FORBIDDEN");
    }

    @Test
    void warnsWhenClaudeUsesWorkerEnvironmentFallback() {
        var service = new ExecutionReadinessService(new ObjectMapper());
        service.report(new ExecutionReadinessService.WorkerReadinessReport(
                "worker-1", "asterism", "claude_sdk", List.of("claude_sdk"), false, false,
                Instant.now(), List.of(new ExecutionReadinessService.TargetReadiness(
                        "sys-1", true, true, true, "model", true, "legacy-model", "worker_env")), null));

        assertThat(service.readiness(profile("claude_sdk")).issues())
                .extracting(ExecutionReadinessService.ReadinessIssue::code)
                .contains("CLAUDE_CONFIG_FALLBACK");
    }

    @Test
    void httpExecutionRequiresDiffModelButClaudeDoesNot() {
        var service = new ExecutionReadinessService(new ObjectMapper());
        service.report(new ExecutionReadinessService.WorkerReadinessReport(
                "worker-1", "asterism", "http", List.of("http", "claude_sdk"), true, false,
                Instant.now(), List.of(new ExecutionReadinessService.TargetReadiness(
                        "sys-1", true, true, false, "需求模型", true, "claude-model", "system",
                        true, "需求模型", true, "规划模型", false, "Diff 模型")), null));

        var http = service.readiness(profile("http"));
        assertThat(http.ready()).isFalse();
        assertThat(http.issues()).extracting(ExecutionReadinessService.ReadinessIssue::code)
                .contains("DIFF_MODEL_NOT_READY");

        var claude = service.readiness(profile("claude_sdk"));
        assertThat(claude.issues()).extracting(ExecutionReadinessService.ReadinessIssue::code)
                .doesNotContain("DIFF_MODEL_NOT_READY");
        assertThat(claude.stages()).filteredOn(stage -> stage.name().equals("planning"))
                .singleElement().extracting(ExecutionReadinessService.ReadinessStage::detail)
                .isEqualTo("规划模型");
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
        service.report(new ExecutionReadinessService.WorkerReadinessReport(
                "worker-1", "asterism", "http", List.of("http"), true, false,
                Instant.now(), List.of(new ExecutionReadinessService.TargetReadiness(
                        "sys-1", false, false, true, "model", false, "", "system")), null));

        assertThat(service.readiness(profile("http")).issues())
                .extracting(ExecutionReadinessService.ReadinessIssue::code)
                .contains("GITLAB_PROJECT_NOT_READY")
                .doesNotContain("REPOSITORY_NOT_READY");
    }

    private SystemProfile profile(String provider) {
        var now = Instant.now();
        return new SystemProfile("sys-1", "系统", "", "/repo", "owner", "[]", "[]", "[\"mvn test\"]",
                "{}", "{\"agents\":[{\"name\":\"developer\",\"kind\":\"builtin\",\"engine\":\""
                + provider + "\",\"modelProfileRef\":\"\"}]}", "admin", now, now);
    }
}
