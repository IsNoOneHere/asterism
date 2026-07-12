package com.agentteam.v5.projection;

import java.util.EnumMap;
import java.util.EnumSet;
import java.util.Set;

public final class LifecycleStateMachine {
    private static final EnumMap<LifecycleStatus, Set<LifecycleStatus>> NEXT = new EnumMap<>(LifecycleStatus.class);

    static {
        NEXT.put(LifecycleStatus.allocated, EnumSet.of(LifecycleStatus.waiting_owner_approval, LifecycleStatus.cancelled));
        NEXT.put(LifecycleStatus.waiting_owner_approval, EnumSet.of(LifecycleStatus.activated, LifecycleStatus.rejected, LifecycleStatus.cancelled));
        NEXT.put(LifecycleStatus.activated, EnumSet.of(LifecycleStatus.modification_completed, LifecycleStatus.worker_blocked, LifecycleStatus.cancelled));
        NEXT.put(LifecycleStatus.worker_blocked, EnumSet.of(LifecycleStatus.activated, LifecycleStatus.cancelled));
        NEXT.put(LifecycleStatus.modification_completed, EnumSet.of(LifecycleStatus.patch_applied, LifecycleStatus.patch_rejected, LifecycleStatus.worker_blocked, LifecycleStatus.cancelled));
        NEXT.put(LifecycleStatus.patch_rejected, EnumSet.of(LifecycleStatus.activated, LifecycleStatus.cancelled));
        NEXT.put(LifecycleStatus.patch_applied, EnumSet.of(LifecycleStatus.validation_passed, LifecycleStatus.validation_failed));
        NEXT.put(LifecycleStatus.validation_failed, EnumSet.of(LifecycleStatus.activated, LifecycleStatus.cancelled));
        NEXT.put(LifecycleStatus.validation_passed, EnumSet.of(LifecycleStatus.completed, LifecycleStatus.worker_blocked, LifecycleStatus.cancelled));
        NEXT.put(LifecycleStatus.completed, EnumSet.noneOf(LifecycleStatus.class));
        NEXT.put(LifecycleStatus.cancelled, EnumSet.noneOf(LifecycleStatus.class));
        NEXT.put(LifecycleStatus.rejected, EnumSet.noneOf(LifecycleStatus.class));
    }

    private LifecycleStateMachine() {
    }

    public static boolean canMove(LifecycleStatus from, LifecycleStatus to) {
        if (from == null || from == to) {
            return true;
        }
        return NEXT.getOrDefault(from, Set.of()).contains(to);
    }
}
