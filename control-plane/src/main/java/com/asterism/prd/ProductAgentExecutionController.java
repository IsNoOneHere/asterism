package com.asterism.prd;

import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v5/internal/product-agent-executions")
public class ProductAgentExecutionController {
    private final ProductAgentExecutionService executions;

    public ProductAgentExecutionController(ProductAgentExecutionService executions) {
        this.executions = executions;
    }

    @PostMapping("/{executionId}/start")
    ProductAgentExecutionView start(@PathVariable("executionId") String executionId) {
        // 启动失败时复用同一 execution/workflowId 重试，不创建第二份业务事实。
        return ProductAgentExecutionView.from(executions.start(executionId));
    }

    @PostMapping("/{executionId}/events")
    ProductAgentExecutionView event(@PathVariable("executionId") String executionId,
                                    @RequestBody ProductAgentExecutionEvent event) {
        return ProductAgentExecutionView.from(executions.apply(executionId, event));
    }
}
