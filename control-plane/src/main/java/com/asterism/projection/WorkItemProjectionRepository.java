package com.asterism.projection;

import org.springframework.data.repository.CrudRepository;
import org.springframework.data.jdbc.repository.query.Query;

import java.util.List;
import java.util.Optional;

public interface WorkItemProjectionRepository extends CrudRepository<WorkItemProjection, String> {
    List<WorkItemProjection> findBySystemIdAndDeletedFalse(String systemId);

    Optional<WorkItemProjection> findByDisplayWorkItemId(String displayWorkItemId);

    @Query("select * from work_items where work_item_id = :workItemId for update")
    Optional<WorkItemProjection> lockById(String workItemId);
}
