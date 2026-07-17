package com.asterism.projection;

import org.springframework.data.repository.CrudRepository;

import java.util.List;
import java.util.Optional;

public interface WorkItemProjectionRepository extends CrudRepository<WorkItemProjection, String> {
    List<WorkItemProjection> findBySystemIdAndDeletedFalse(String systemId);

    Optional<WorkItemProjection> findByDisplayWorkItemId(String displayWorkItemId);
}
