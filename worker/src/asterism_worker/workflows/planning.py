from datetime import timedelta

from temporalio import workflow
from temporalio.common import RetryPolicy
from temporalio.exceptions import ActivityError, ApplicationError

from asterism_worker.contracts import CodingPlanDraft, ContextSnapshot, LifecycleStatus
from asterism_worker.workflows.coding import ExecutionPhase


class PlanningWorkflow:
    """编排只读规划、人工审批，以及计划对应 Claude Session 的切换。"""

    async def _plan_approved_action(
        self, _action: str, _spec, signal_id: str, context: dict,
    ) -> bool:
        if self.coding_plan is None:
            workflow.logger.warning("没有可批准的 Coding Plan")
            return False
        await self._emit("CodingPlanApproved", signal_id, {
            "planRevision": self.coding_plan.revision,
            "approvedBy": str(context.get("actor_id", "")),
        })
        self.rework_feedback = ""
        await self._start_supervised_modification(signal_id, reuse_context=True)
        return True

    async def _plan_rejected_action(
        self, _action: str, _spec, signal_id: str, context: dict,
    ) -> bool:
        if self.coding_plan is None:
            workflow.logger.warning("没有可打回的 Coding Plan")
            return False
        await self._emit("CodingPlanRejected", signal_id, {
            "planRevision": self.coding_plan.revision,
            "note": str(context.get("note", "")).strip(),
            "rejectedBy": str(context.get("actor_id", "")),
        })
        # 人工打回意味着上一轮规划上下文已失效；带着计划和意见创建干净的新 Session。
        await self._propose_coding_plan(signal_id, reuse_context=True, new_session=True)
        return True

    async def _retry_planning_phase(self, signal_id: str) -> None:
        await self._propose_coding_plan(signal_id, reuse_context=True)

    async def _start_modification(self, signal_id: str) -> None:
        await self._propose_coding_plan(signal_id)

    async def _propose_coding_plan(
        self,
        signal_id: str,
        reuse_context: bool = False,
        refresh_workspace: bool = False,
        new_session: bool = False,
    ) -> None:
        """规划完成后释放 Claude 进程，由 Temporal 无期限等待人工决定。"""

        case_input = self._case_input()
        if self.state.status != LifecycleStatus.activated:
            workflow.logger.warning("非法 Coding Plan 请求", extra={"status": self.state.status.value})
            return
        snapshot = self.context_snapshot if reuse_context else None
        if snapshot is None:
            try:
                payload = await workflow.execute_activity(
                    "fetch_context",
                    {"system_id": case_input.system_id, "work_item_id": case_input.work_item_id},
                    start_to_close_timeout=timedelta(seconds=20),
                    retry_policy=RetryPolicy(maximum_attempts=3),
                )
            except (ActivityError, ApplicationError) as error:
                await self._block_worker(
                    signal_id, "context_fetch_failed", error, phase=ExecutionPhase.planning,
                )
                return
            snapshot = ContextSnapshot.model_validate(payload)
        self.context_snapshot = snapshot
        self.plan_revision += 1
        await self._emit("CodingPlanStarted", signal_id, {
            "planRevision": self.plan_revision,
            "repositories": [repo.repo_id for repo in case_input.effective_repos()],
            "contextManifestId": snapshot.manifest_id,
        })
        request = {
            "case_id": case_input.case_id,
            "work_item_id": case_input.work_item_id,
            "system_id": case_input.system_id,
            "repos": [repo.model_dump() for repo in case_input.effective_repos()],
            "goal": case_input.prd.goal,
            "acceptance_criteria": case_input.prd.acceptance_criteria,
            "feedback": self.rework_feedback,
            "memories": snapshot.approved_memories,
            "context_manifest_id": snapshot.manifest_id,
            "plan_revision": self.plan_revision,
            "resume_session_id": "" if new_session else self.coding_session_id,
            "refresh_workspace": refresh_workspace,
        }
        if self.coding_plan is not None:
            request["previous_plan"] = self.coding_plan.model_dump()
        if case_input.agent_config_snapshot is not None:
            request["agent_config_snapshot"] = case_input.agent_config_snapshot.model_dump()
        try:
            payload = await workflow.execute_activity(
                "generate_coding_plan",
                request,
                start_to_close_timeout=timedelta(minutes=45),
                heartbeat_timeout=timedelta(minutes=10),
                retry_policy=RetryPolicy(maximum_attempts=2),
            )
            plan = CodingPlanDraft.model_validate(payload)
        except (ActivityError, ApplicationError) as error:
            await self._block_worker(
                signal_id,
                "coding_plan_failed",
                error,
                {"planRevision": self.plan_revision},
                phase=ExecutionPhase.planning,
            )
            return
        self.coding_plan = plan
        self.coding_session_id = plan.session_id
        self.failed_phase = ""
        await self._emit("CodingPlanProposed", signal_id, {
            "summary": plan.summary,
            "tasks": [{
                "taskId": task.task_id,
                "repo": task.repo,
                "objective": task.objective,
                "acceptanceCriteriaRefs": task.acceptance_criteria_refs,
                "evidence": task.evidence,
            } for task in plan.tasks],
            "risks": plan.risks,
            "openQuestions": plan.open_questions,
            "planRevision": plan.revision,
            "baseRevisions": plan.base_revisions,
            "sessionId": plan.session_id,
        })
