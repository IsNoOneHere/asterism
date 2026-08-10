package com.asterism.system;

import org.springframework.data.jdbc.repository.query.Query;
import org.springframework.data.repository.CrudRepository;

public interface SystemProfileRepository extends CrudRepository<SystemProfile, String> {
    @Query("""
            select exists (select 1 from prd_sessions where system_id = :systemId)
                or exists (select 1 from work_items where system_id = :systemId)
                or exists (select 1 from domain_events where system_id = :systemId)
                or exists (select 1 from memory_items where system_id = :systemId)
                or exists (select 1 from memory_candidates where system_id = :systemId)
                or exists (select 1 from context_manifests where system_id = :systemId)
                or exists (select 1 from conversation_messages where system_id = :systemId)
                or exists (select 1 from attachments where system_id = :systemId)
                or exists (select 1 from system_knowledge where system_id = :systemId)
            """)
    boolean hasBusinessData(String systemId);
}
