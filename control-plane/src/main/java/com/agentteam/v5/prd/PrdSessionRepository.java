package com.agentteam.v5.prd;

import org.springframework.data.repository.CrudRepository;

import java.util.List;

public interface PrdSessionRepository extends CrudRepository<PrdSession, String> {
    List<PrdSession> findBySystemIdOrderByUpdatedAtDesc(String systemId);
}
