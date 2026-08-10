package com.asterism.prd;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;

@Component
public class ProductAgentExecutionRecovery {
    private static final Logger log = LoggerFactory.getLogger(ProductAgentExecutionRecovery.class);

    private final ProductAgentExecutionRepository executions;
    private final ProductAgentExecutionService executionService;
    private final Duration staleAfter;

    public ProductAgentExecutionRecovery(
            ProductAgentExecutionRepository executions,
            ProductAgentExecutionService executionService,
            @Value("${asterism.product-agent.recovery.stale-after:PT30S}") Duration staleAfter) {
        this.executions = executions;
        this.executionService = executionService;
        this.staleAfter = staleAfter;
    }

    @Scheduled(
            initialDelayString = "${asterism.product-agent.recovery.interval:PT30S}",
            fixedDelayString = "${asterism.product-agent.recovery.interval:PT30S}")
    void recover() {
        for (var execution : executions.findCreatedBefore(Instant.now().minus(staleAfter))) {
            try {
                // 复用确定性的 workflowId；已启动的 Temporal workflow 会按幂等成功处理。
                executionService.start(execution.executionId());
            } catch (RuntimeException error) {
                log.warn("Product Agent CREATED execution 恢复失败 executionId={} type={}",
                        execution.executionId(), error.getClass().getSimpleName());
            }
        }
    }
}
