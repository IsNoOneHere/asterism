package com.asterism.git;

import org.springframework.data.annotation.Id;
import org.springframework.data.relational.core.mapping.Column;
import org.springframework.data.relational.core.mapping.Table;

import java.time.Instant;

@Table("system_git_configs")
public record SystemGitConfig(
        @Id @Column("system_id") String systemId,
        @Column("repos_json") String reposJson,
        @Column("release_mode") String releaseMode,
        @Column("validation_mode") String validationMode,
        @Column("mr_target_branch") String mrTargetBranch,
        @Column("mr_labels") String mrLabels,
        @Column("gitlab_base_url") String gitlabBaseUrl,
        @Column("gitlab_token") String gitlabToken,
        @Column("updated_at") Instant updatedAt) {
}
