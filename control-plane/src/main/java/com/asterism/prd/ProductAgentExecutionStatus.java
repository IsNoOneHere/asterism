package com.asterism.prd;

public enum ProductAgentExecutionStatus {
    CREATED,
    RUNNING,
    COMPLETED,
    FAILED,
    CANCELLED;

    public boolean active() {
        return this == CREATED || this == RUNNING;
    }

    public boolean terminal() {
        return this == COMPLETED || this == FAILED || this == CANCELLED;
    }
}
