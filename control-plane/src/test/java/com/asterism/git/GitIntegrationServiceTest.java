package com.asterism.git;

import com.asterism.system.SystemConfigLock;
import com.asterism.system.SystemProfile;
import com.asterism.system.SystemProfileRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.jdbc.core.JdbcAggregateTemplate;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class GitIntegrationServiceTest {
    private final SystemGitConfigRepository configs = mock(SystemGitConfigRepository.class);
    private final SystemProfileRepository systems = mock(SystemProfileRepository.class);
    private final JdbcAggregateTemplate aggregate = mock(JdbcAggregateTemplate.class);
    private final SystemConfigLock lock = mock(SystemConfigLock.class);
    private final GitLabClient gitLab = mock(GitLabClient.class);
    private final ObjectMapper mapper = new ObjectMapper();
    private final GitIntegrationService service = new GitIntegrationService(configs, systems, aggregate, mapper, lock,
            new GitLabProperties("http://gitlab.test", "global-credential"), gitLab);

    @Test
    void publicConfigOnlyReturnsTokenStateAndBlankUpdateKeepsStoredToken() throws Exception {
        var record = record("stored-credential");
        when(systems.findById("sys-1")).thenReturn(Optional.of(system()));
        when(configs.findById("sys-1")).thenReturn(Optional.of(record));

        var json = mapper.writeValueAsString(service.get("sys-1"));
        assertThat(json).contains("\"tokenSet\":true").doesNotContain("stored-credential");

        var request = new GitIntegrationService.UpdateGitConfiguration(repos(), "gitlab", "skip", "main",
                List.of("asterism"), "", "");
        service.update("sys-1", request);
        var saved = ArgumentCaptor.forClass(SystemGitConfig.class);
        verify(aggregate).update(saved.capture());
        assertThat(saved.getValue().gitlabToken()).isEqualTo("stored-credential");
    }

    @Test
    void readinessChecksEveryGitLabProject() throws Exception {
        when(systems.findById("sys-1")).thenReturn(Optional.of(system()));
        when(configs.findById("sys-1")).thenReturn(Optional.of(record("")));
        when(gitLab.projectAccessible(anyString(), anyString(), anyString())).thenAnswer(call ->
                "group/web".equals(call.getArgument(2)));

        var result = service.readiness("sys-1");

        assertThat(result.ready()).isFalse();
        assertThat(result.unavailableProjects()).containsExactly("group/api");
    }

    private SystemGitConfig record(String token) throws Exception {
        return new SystemGitConfig("sys-1", mapper.writeValueAsString(repos()), "gitlab", "auto", "main",
                "[]", "", token, Instant.now());
    }

    private List<GitIntegrationService.RepoConfig> repos() {
        return List.of(
                new GitIntegrationService.RepoConfig("web", "Web", "frontend", "group/web", "main", "gitlab", "",
                        List.of("src"), List.of(), List.of("npm test")),
                new GitIntegrationService.RepoConfig("api", "API", "backend", "group/api", "main", "gitlab", "",
                        List.of("src"), List.of(), List.of("mvn test")));
    }

    private SystemProfile system() {
        var now = Instant.now();
        return new SystemProfile("sys-1", "系统", "", "/repo", "owner", "[]", "[]", "[]", "{}", "{}",
                "admin", now, now);
    }
}
