package com.asterism.memory;

import org.springframework.data.repository.CrudRepository;

import java.util.List;

public interface MemoryItemRepository extends CrudRepository<MemoryItem, String> {
    List<MemoryItem> findBySystemIdAndStatus(String systemId, String status);

    List<MemoryItem> findTop100BySystemIdOrderByCreatedAtDesc(String systemId);

    List<MemoryItem> findTop100BySystemIdAndStatusOrderByCreatedAtDesc(String systemId, String status);

}
