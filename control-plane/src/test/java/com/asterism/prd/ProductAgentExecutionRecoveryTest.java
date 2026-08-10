package com.asterism.prd;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ProductAgentExecutionRecoveryTest {
    @Test
    void staleCreatedExecutionsAreRetriedIndependently() {
        var executions = mock(ProductAgentExecutionRepository.class);
        var service = mock(ProductAgentExecutionService.class);
        when(executions.findCreatedBefore(any())).thenReturn(List.of(created("exec-1"), created("exec-2")));
        doThrow(new IllegalStateException("temporal unavailable")).when(service).start("exec-1");
        var recovery = new ProductAgentExecutionRecovery(executions, service, Duration.ofSeconds(30));
        var before = Instant.now().minusSeconds(31);

        recovery.recover();

        var cutoff = ArgumentCaptor.forClass(Instant.class);
        verify(executions).findCreatedBefore(cutoff.capture());
        assertThat(cutoff.getValue()).isAfter(before).isBefore(Instant.now().minusSeconds(29));
        verify(service).start("exec-1");
        verify(service).start("exec-2");
    }

    private ProductAgentExecution created(String executionId) {
        var now = Instant.now().minusSeconds(60);
        return new ProductAgentExecution(
                executionId, "prd-1", ProductAgentExecutionStatus.CREATED,
                "product-agent-" + executionId, "msg-user", "bundle-1", "CREATED", 0,
                null, null, null, null, null, now, now);
    }
}
