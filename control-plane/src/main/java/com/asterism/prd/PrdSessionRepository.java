package com.asterism.prd;

import org.springframework.data.repository.CrudRepository;
import org.springframework.data.jdbc.repository.query.Modifying;
import org.springframework.data.jdbc.repository.query.Query;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

public interface PrdSessionRepository extends CrudRepository<PrdSession, String> {
    @Override
    @Query("select * from prd_sessions where prd_id = :prdId and deleted = false")
    Optional<PrdSession> findById(String prdId);

    @Query("select * from prd_sessions where system_id = :systemId and deleted = false order by updated_at desc")
    List<PrdSession> findBySystemIdOrderByUpdatedAtDesc(String systemId);

    @Modifying
    @Query("update prd_sessions set status = :status, updated_at = :updatedAt where work_item_id = :workItemId and deleted = false")
    int updateLifecycleStatus(String workItemId, String status, Instant updatedAt);

    @Modifying
    @Query("update prd_sessions set deleted = true, updated_at = :updatedAt where prd_id = :prdId and deleted = false")
    int markDeleted(String prdId, Instant updatedAt);
}
