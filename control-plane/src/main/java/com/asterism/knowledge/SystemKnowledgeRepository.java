package com.asterism.knowledge;

import org.springframework.data.jdbc.repository.query.Query;
import org.springframework.data.repository.CrudRepository;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface SystemKnowledgeRepository extends CrudRepository<SystemKnowledge, String> {
    List<SystemKnowledge> findBySystemIdOrderByCreatedAtDesc(String systemId);

    List<SystemKnowledge> findBySystemIdAndStatusOrderByCreatedAtDesc(String systemId, String status);

    @Query("""
            select *
            from system_knowledge
            where system_id = :systemId
              and status = :status
              and lower(concat_ws(' ', title, repo_id, kind, route_path, source, anchor_texts,
                                  cast(api_endpoints as text))) like :query
            order by created_at desc, entry_id
            limit :pageSize offset :rowOffset
            """)
    List<SystemKnowledge> findPage(@Param("systemId") String systemId, @Param("status") String status,
                                   @Param("query") String query, @Param("pageSize") int pageSize,
                                   @Param("rowOffset") long rowOffset);

    @Query("""
            select count(*)
            from system_knowledge
            where system_id = :systemId
              and status = :status
              and lower(concat_ws(' ', title, repo_id, kind, route_path, source, anchor_texts,
                                  cast(api_endpoints as text))) like :query
            """)
    long countPage(@Param("systemId") String systemId, @Param("status") String status,
                   @Param("query") String query);

    Optional<SystemKnowledge> findBySystemIdAndRepoIdAndRoutePath(String systemId, String repoId, String routePath);

    Optional<SystemKnowledge> findBySystemIdAndRepoIdAndSourceAndSourceRef(String systemId, String repoId,
                                                                           String source, String sourceRef);
}
