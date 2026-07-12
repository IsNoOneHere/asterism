package com.agentteam.v5.event;

import org.springframework.data.repository.CrudRepository;
import org.springframework.data.jdbc.repository.query.Query;

import java.util.List;
import java.util.Optional;

public interface DomainEventRepository extends CrudRepository<DomainEventRecord, Long> {
    Optional<DomainEventRecord> findByIdempotencyKey(String idempotencyKey);

    List<DomainEventRecord> findByWorkItemIdOrderBySequenceAsc(String workItemId);

    @Query("""
            select count(*)
            from domain_events
            where work_item_id = :workItemId
              and event_type = 'TemporalSignalSubmitted'
              and payload_json ->> 'signalName' = :signalName
            """)
    long countSubmittedSignals(String workItemId, String signalName);

    @Query("""
            select coalesce(max(sequence), 0)
            from domain_events
            where work_item_id = :workItemId
              and event_type in ('OwnerApprovalSignalSubmitted', 'TemporalSignalSubmitted')
              and payload_json ->> 'signalId' = :signalId
            """)
    long latestSubmittedSignalSequence(String workItemId, String signalId);

    @Query("""
            select coalesce(max(sequence), 0)
            from domain_events
            where work_item_id = :workItemId
              and event_type = 'TemporalSignalFailed'
              and payload_json ->> 'signalId' = :signalId
            """)
    long latestFailedSignalSequence(String workItemId, String signalId);

    @Query("""
            select count(*)
            from domain_events
            where work_item_id = :workItemId
              and event_type = 'TemporalSignalFailed'
              and payload_json ->> 'signalId' = :signalId
            """)
    long countSignalFailures(String workItemId, String signalId);
}
