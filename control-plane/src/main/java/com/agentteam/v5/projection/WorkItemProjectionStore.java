package com.agentteam.v5.projection;

import java.util.Optional;

public interface WorkItemProjectionStore {
    Optional<WorkItemProjection> findById(String workItemId);

    WorkItemProjection save(WorkItemProjection projection);
}

