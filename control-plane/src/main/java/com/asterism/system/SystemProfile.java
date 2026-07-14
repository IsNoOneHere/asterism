package com.asterism.system;

import org.springframework.data.annotation.Id;
import org.springframework.data.relational.core.mapping.Column;
import org.springframework.data.relational.core.mapping.Table;

import java.time.Instant;

@Table("systems")
public record SystemProfile(
        @Id @Column("system_id") String systemId,
        String name,
        String description,
        @Column("repo_path") String repoPath,
        @Column("owner_user_id") String ownerUserId,
        @Column("allowed_paths") String allowedPaths,
        @Column("forbidden_paths") String forbiddenPaths,
        @Column("test_commands") String testCommands,
        @Column("agent_config") String agentConfig,
        @Column("model_provider_config") String modelProviderConfig,
        @Column("created_by") String createdBy,
        @Column("created_at") Instant createdAt,
        @Column("updated_at") Instant updatedAt) {
}

