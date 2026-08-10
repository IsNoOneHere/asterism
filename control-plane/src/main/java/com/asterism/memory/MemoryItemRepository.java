package com.asterism.memory;

import org.springframework.data.repository.CrudRepository;

import java.util.List;
import java.util.Optional;

public interface MemoryItemRepository extends CrudRepository<MemoryItem, String> {
    List<MemoryItem> findBySystemIdAndStatus(String systemId, MemoryStatus status);

    List<MemoryItem> findTop100BySystemIdOrderByCreatedAtDesc(String systemId);

    List<MemoryItem> findTop100BySystemIdAndStatusOrderByCreatedAtDesc(String systemId, MemoryStatus status);

    Optional<MemoryItem> findBySystemIdAndSourceRef(String systemId, String sourceRef);

    Optional<MemoryItem> findByCandidateId(String candidateId);

    Optional<MemoryItem> findByArtifactSourceIdAndMemoryTypeAndNormalizedContentHash(
            String artifactSourceId, MemoryType memoryType, String normalizedContentHash);
}
