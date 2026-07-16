package com.asterism.projection;

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
    waiting_merge,
    completed,
    cancelled,
    rejected
}
