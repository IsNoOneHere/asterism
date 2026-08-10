package com.asterism.common;

import com.asterism.artifact.ArtifactConflictException;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

import static org.assertj.core.api.Assertions.assertThat;

class ApiExceptionHandlerTest {
    @Test
    void artifactConflictAlwaysReturnsHttp409() {
        // Artifact 并发和过期引用必须保留明确的 409 语义，不能落入通用 500。
        var response = new ApiExceptionHandler().artifactConflict(
                new ArtifactConflictException("Artifact Head 已变化"));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().code()).isEqualTo("ARTIFACT_CONFLICT");
    }
}
