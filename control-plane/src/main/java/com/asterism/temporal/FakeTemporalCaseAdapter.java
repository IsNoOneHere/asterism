package com.asterism.temporal;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

@Component
@Profile("!temporal")
public class FakeTemporalCaseAdapter implements TemporalCasePort {
    private static final Logger log = LoggerFactory.getLogger(FakeTemporalCaseAdapter.class);

    @Override
    public String startCase(StartCaseCommand command) {
        log.info("fake Temporal start caseId={} workItem={}", command.caseId(), command.workItemId());
        return command.caseId();
    }

    @Override
    public void signalCase(SignalCaseCommand command) {
        log.info("fake Temporal signal caseId={} signal={} signalId={}",
                command.caseId(), command.signalName(), command.signalId());
    }

    @Override
    public String startRouteIndex(RouteIndexCommand command) {
        var workflowId = "route-index-" + command.systemId();
        log.info("fake Temporal 路由索引 system={} repoPath={}", command.systemId(), command.repoPath());
        return workflowId;
    }
}
