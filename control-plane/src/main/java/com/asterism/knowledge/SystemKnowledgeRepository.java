package com.asterism.knowledge;

import org.springframework.data.repository.CrudRepository;

import java.util.List;
import java.util.Optional;

public interface SystemKnowledgeRepository extends CrudRepository<SystemKnowledge, String> {
    List<SystemKnowledge> findBySystemIdOrderByCreatedAtDesc(String systemId);

    List<SystemKnowledge> findBySystemIdAndStatusOrderByCreatedAtDesc(String systemId, String status);

    Optional<SystemKnowledge> findBySystemIdAndRepoIdAndRoutePath(String systemId, String repoId, String routePath);

    Optional<SystemKnowledge> findBySystemIdAndRepoIdAndSourceAndSourceRef(String systemId, String repoId,
                                                                           String source, String sourceRef);
}
