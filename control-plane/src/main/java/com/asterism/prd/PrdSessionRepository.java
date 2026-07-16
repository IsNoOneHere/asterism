package com.asterism.prd;

import org.springframework.data.repository.CrudRepository;
import org.springframework.data.jdbc.repository.query.Modifying;
import org.springframework.data.jdbc.repository.query.Query;

import java.time.Instant;
import java.util.List;

public interface PrdSessionRepository extends CrudRepository<PrdSession, String> {
    List<PrdSession> findBySystemIdOrderByUpdatedAtDesc(String systemId);

    @Modifying
    @Query("update prd_sessions set status = :status, updated_at = :updatedAt where work_item_id = :workItemId")
    int updateLifecycleStatus(String workItemId, String status, Instant updatedAt);
}
