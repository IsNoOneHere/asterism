from dataclasses import dataclass
import logging

from asterism_worker.contracts import ExecutionResult, LifecycleStatus

log = logging.getLogger(__name__)

TRANSITIONS: dict[LifecycleStatus, set[LifecycleStatus]] = {
    LifecycleStatus.allocated: {LifecycleStatus.waiting_owner_approval, LifecycleStatus.cancelled},
    LifecycleStatus.waiting_owner_approval: {LifecycleStatus.activated, LifecycleStatus.rejected, LifecycleStatus.cancelled},
    LifecycleStatus.activated: {LifecycleStatus.modification_completed, LifecycleStatus.worker_blocked, LifecycleStatus.cancelled},
    LifecycleStatus.worker_blocked: {LifecycleStatus.activated, LifecycleStatus.cancelled},
    LifecycleStatus.modification_completed: {
        LifecycleStatus.patch_applied,
        LifecycleStatus.patch_rejected,
        LifecycleStatus.worker_blocked,
        LifecycleStatus.cancelled,
    },
    LifecycleStatus.patch_rejected: {LifecycleStatus.activated, LifecycleStatus.cancelled},
    LifecycleStatus.patch_applied: {LifecycleStatus.validation_passed, LifecycleStatus.validation_failed},
    LifecycleStatus.validation_failed: {LifecycleStatus.activated, LifecycleStatus.cancelled},
    LifecycleStatus.validation_passed: {LifecycleStatus.completed, LifecycleStatus.worker_blocked, LifecycleStatus.cancelled},
    LifecycleStatus.completed: set(),
    LifecycleStatus.cancelled: set(),
    LifecycleStatus.rejected: set(),
}

TERMINAL_STATUSES = frozenset(status for status, next_statuses in TRANSITIONS.items() if not next_statuses)


@dataclass(slots=True)
class CaseState:
    """Temporal workflow 内部状态；Java 投影只消费回调事件。"""

    status: LifecycleStatus = LifecycleStatus.waiting_owner_approval
    execution_allowed: bool = False
    diff_patch: str = ""

    def owner_approved(self) -> str | None:
        if self.status == LifecycleStatus.activated:
            log.info("忽略重复 owner_approved")
            return None
        if not self._move(LifecycleStatus.activated):
            return None
        self.execution_allowed = True
        return "WorkItemActivated"

    def modification_finished(self, result: ExecutionResult) -> str | None:
        if not result.passes_diff_gate:
            if not self._move(LifecycleStatus.worker_blocked):
                return None
            self.execution_allowed = False
            return "WorkerBlocked"
        if not self._move(LifecycleStatus.modification_completed):
            return None
        self.diff_patch = result.diff_patch
        return "ModificationCompleted"

    def patch_apply_approved(self) -> str | None:
        if not self._move(LifecycleStatus.patch_applied):
            return None
        return "PatchApplied"

    def patch_apply_blocked(self) -> str | None:
        if not self._move(LifecycleStatus.worker_blocked):
            return None
        self.execution_allowed = False
        return "PatchApplyBlocked"

    def patch_apply_rejected(self) -> str | None:
        if not self._move(LifecycleStatus.patch_rejected):
            return None
        self.execution_allowed = False
        return "PatchRejected"

    def validation_passed(self) -> str | None:
        if not self._move(LifecycleStatus.validation_passed):
            return None
        self.execution_allowed = False
        return "ValidationPassed"

    def validation_rejected(self) -> str | None:
        if not self._move(LifecycleStatus.validation_failed):
            return None
        self.execution_allowed = False
        return "ValidationFailed"

    def rework(self) -> str | None:
        # 统一先回到 activated；workflow 可直接续跑失败 stage，其它错误等待下一次 start_modification。
        if not self._move(LifecycleStatus.activated):
            return None
        self.execution_allowed = True
        return "ReworkStarted"

    def planner_failed(self) -> str | None:
        return self.worker_blocked_on("planner_failed")

    def worker_blocked_on(self, reason: str) -> str | None:
        # 所有执行面失败统一收敛到 worker_blocked，具体 reason 留在事件 payload。
        if not self._move(LifecycleStatus.worker_blocked):
            return None
        self.execution_allowed = False
        log.info("worker blocked", extra={"reason": reason})
        return "WorkerBlocked"

    def release_approved(self) -> str | None:
        if not self._move(LifecycleStatus.completed):
            return None
        self.execution_allowed = False
        return "ReleaseCompleted"

    def cancel_case(self) -> str | None:
        if not self._move(LifecycleStatus.cancelled):
            return None
        self.execution_allowed = False
        return "CaseCancelled"

    def owner_rejected(self) -> str | None:
        if not self._move(LifecycleStatus.rejected):
            return None
        self.execution_allowed = False
        return "WorkItemRejected"

    def _move(self, target: LifecycleStatus) -> bool:
        allowed = TRANSITIONS.get(self.status, set())
        if target not in allowed:
            log.warning("非法生命周期迁移，已忽略", extra={"from_status": self.status, "to_status": target})
            return False
        self.status = target
        return True
