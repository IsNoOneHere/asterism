package com.asterism.knowledge;

import org.springframework.data.repository.CrudRepository;

import java.util.List;
import java.util.Optional;

public interface SystemKnowledgeRepository extends CrudRepository<SystemKnowledge, String> {
    List<SystemKnowledge> findBySystemIdOrderByCreatedAtDesc(String systemId);

    List<SystemKnowledge> findBySystemIdAndStatusOrderByCreatedAtDesc(String systemId, String status);

    Optional<SystemKnowledge> findBySystemIdAndRoutePath(String systemId, String routePath);

    Optional<SystemKnowledge> findBySystemIdAndSourceAndSourceRef(String systemId, String source, String sourceRef);
}
