package com.asterism.memory;

import org.springframework.data.jdbc.repository.query.Query;
import org.springframework.data.repository.CrudRepository;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface MemoryCandidateRepository extends CrudRepository<MemoryCandidate, String> {
    @Query("select * from memory_candidates where candidate_id = :candidateId for update")
    Optional<MemoryCandidate> lockById(@Param("candidateId") String candidateId);

    List<MemoryCandidate> findTop100BySystemIdOrderByCreatedAtDesc(String systemId);

    List<MemoryCandidate> findTop100BySystemIdAndStatusOrderByCreatedAtDesc(
            String systemId, MemoryCandidateStatus status);

    Optional<MemoryCandidate> findBySystemIdAndArtifactSourceIdAndSourceKindAndMemoryTypeAndNormalizedContentHash(
            String systemId, String artifactSourceId, String sourceKind, MemoryType memoryType,
            String normalizedContentHash);
}
