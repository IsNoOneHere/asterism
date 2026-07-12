package com.agentteam.v5.projection;

public enum LifecycleStatus {
    allocated,
    waiting_owner_approval,
    activated,
    modification_completed,
    worker_blocked,
    patch_applied,
    patch_rejected,
    validation_passed,
    validation_failed,
    completed,
    cancelled,
    rejected
}

