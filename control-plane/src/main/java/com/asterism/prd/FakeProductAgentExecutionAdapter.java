package com.asterism.prd;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

@Component
@Profile("!temporal")
public class FakeProductAgentExecutionAdapter implements ProductAgentExecutionPort {
    private static final Logger log = LoggerFactory.getLogger(FakeProductAgentExecutionAdapter.class);

    @Override
    public String start(StartExecutionCommand command) {
        // 测试与本地 fake 只确认启动边界，不在请求线程伪造完成结果。
        log.info("fake Product Agent workflow 已接收 executionId={} workflowId={}",
                command.executionId(), command.workflowId());
        return command.workflowId();
    }
}
