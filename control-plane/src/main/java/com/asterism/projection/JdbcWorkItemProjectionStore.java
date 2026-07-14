package com.asterism.projection;

import org.springframework.data.jdbc.core.JdbcAggregateTemplate;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public class JdbcWorkItemProjectionStore implements WorkItemProjectionStore {
    private final WorkItemProjectionRepository repository;
    private final JdbcAggregateTemplate aggregate;

    public JdbcWorkItemProjectionStore(WorkItemProjectionRepository repository, JdbcAggregateTemplate aggregate) {
        this.repository = repository;
        this.aggregate = aggregate;
    }

    @Override
    public Optional<WorkItemProjection> findById(String workItemId) {
        return repository.findById(workItemId);
    }

    @Override
    public WorkItemProjection save(WorkItemProjection projection) {
        return repository.existsById(projection.workItemId()) ? aggregate.update(projection) : aggregate.insert(projection);
    }
}
