package com.agentteam.v5.projection;

import org.springframework.data.repository.CrudRepository;

import java.util.List;

public interface WorkItemProjectionRepository extends CrudRepository<WorkItemProjection, String> {
    List<WorkItemProjection> findBySystemIdAndDeletedFalse(String systemId);
}

