package com.asterism.artifact;

import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

class ValidationReleaseArtifactMigrationContractTest {
    @Test
    void migrationOnlyExtendsArtifactTypesWithoutBackfillingHistoricalEvents() throws Exception {
        var resource = new ClassPathResource("db/migration/V22__validation_release_artifacts.sql");
        var sql = new String(resource.getInputStream().readAllBytes(), StandardCharsets.UTF_8)
                .replaceAll("\\s+", " ")
                .toLowerCase();

        assertThat(sql).contains("'product', 'planning', 'coding', 'validation', 'release'");
        assertThat(sql).contains("alter table artifacts", "alter table artifact_heads");
        assertThat(sql).doesNotContain("insert into artifacts", "insert into artifact_heads");
    }
}
