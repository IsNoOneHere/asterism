from datetime import timedelta

from temporalio import workflow
from temporalio.common import RetryPolicy
from temporalio.exceptions import ActivityError, ApplicationError

from asterism_worker.contracts import (
    ArtifactEvidenceRequest,
    ArtifactTransitionRequest,
    CodingPlanDraft,
    ContextSnapshot,
    LifecycleStatus,
)
from asterism_worker.workflows.coding import ExecutionPhase


class PlanningWorkflow:
    """编排只读规划、人工审批，以及计划对应 Claude Session 的切换。"""

    async def _plan_approved_action(
        self, _action: str, _spec, signal_id: str, context: dict,
    ) -> bool:
        if not self.artifact_mode:
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
        if self.planning_artifact is None:
            workflow.logger.warning("没有可批准的 PlanningArtifact")
            return False
        await self._emit(
            "CodingPlanApproved",
            signal_id,
            {
                "planRevision": self.planning_artifact.version,
                "approvedBy": str(context.get("actor_id", "")),
            },
            artifact_transition=ArtifactTransitionRequest(
                kind="ApprovePlanningArtifact",
                transition_id=f"{self._case_input().case_id}:approve-planning:{signal_id}",
                artifact=self.planning_artifact,
                expected_head=self._head("PLANNING"),
                note=str(context.get("note", "")),
            ),
        )
        self.rework_feedback = ""
        await self._start_supervised_modification(signal_id, reuse_context=True)
        return True

    async def _plan_rejected_action(
        self, _action: str, _spec, signal_id: str, context: dict,
    ) -> bool:
        if not self.artifact_mode:
            if self.coding_plan is None:
                workflow.logger.warning("没有可打回的 Coding Plan")
                return False
            await self._emit("CodingPlanRejected", signal_id, {
                "planRevision": self.coding_plan.revision,
                "note": str(context.get("note", "")).strip(),
                "rejectedBy": str(context.get("actor_id", "")),
            })
            await self._propose_coding_plan(
                signal_id, reuse_context=True, new_session=True,
            )
            return True
        if self.planning_artifact is None:
            workflow.logger.warning("没有可打回的 PlanningArtifact")
            return False
        await self._emit(
            "CodingPlanRejected",
            signal_id,
            {
                "planRevision": self.planning_artifact.version,
                "note": str(context.get("note", "")).strip(),
                "rejectedBy": str(context.get("actor_id", "")),
            },
            artifact_transition=ArtifactTransitionRequest(
                kind="RejectPlanningArtifact",
                transition_id=f"{self._case_input().case_id}:reject-planning:{signal_id}",
                artifact=self.planning_artifact,
                expected_head=self._head("PLANNING"),
                note=self.rework_feedback or str(context.get("note", "")).strip(),
            ),
        )
        # 人工打回意味着上一轮规划上下文已失效；带着计划和意见创建干净的新 Session。
        await self._propose_coding_plan(
            signal_id, previous_artifact=self.planning_artifact, new_session=True,
        )
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
        previous_artifact=None,
    ) -> None:
        """规划完成后释放 Claude 进程，由 Temporal 无期限等待人工决定。"""

        if not self.artifact_mode:
            await self._propose_legacy_coding_plan(
                signal_id,
                reuse_context=reuse_context,
                refresh_workspace=refresh_workspace,
                new_session=new_session,
            )
            return
        case_input = self._case_input()
        if self.state.status != LifecycleStatus.activated:
            workflow.logger.warning("非法 Coding Plan 请求", extra={"status": self.state.status.value})
            return
        try:
            # 规划重做只从 Product、旧 Planning 和打回 Transition 重建，不读取 Workflow 内存计划。
            snapshot = await self._fetch_artifact_context("planning", previous_artifact)
        except (ActivityError, ApplicationError) as error:
            await self._block_worker(
                signal_id, "context_fetch_failed", error, phase=ExecutionPhase.planning,
            )
            return
        if snapshot.stale_references:
            await self._block_worker(
                signal_id, "context_stale",
                RuntimeError("需求上下文已变化: " + ",".join(snapshot.stale_references)),
                {"staleReferences": snapshot.stale_references}, phase=ExecutionPhase.planning,
            )
            return
        self.context_snapshot = snapshot
        self.plan_revision += 1
        await self._emit("CodingPlanStarted", signal_id, {
            "planRevision": self.plan_revision,
            "repositories": [repo.repo_id for repo in case_input.effective_repos()],
            "requirementManifestId": snapshot.requirement_manifest_id,
            "executionContextBundleId": snapshot.execution_bundle_id,
            "productArtifactId": snapshot.product_artifact.artifact_id,
        })
        product = snapshot.product_content
        request = {
            "case_id": case_input.case_id,
            "work_item_id": case_input.work_item_id,
            "system_id": case_input.system_id,
            "repos": [repo.model_dump() for repo in case_input.effective_repos()],
            "goal": str(product.get("goal", "")),
            "acceptance_criteria": list(product.get("acceptanceCriteria", [])),
            "feedback": self._planning_feedback(snapshot),
            "requirement_context": snapshot.requirement_items,
            "execution_context": snapshot.execution_items,
            "requirement_manifest_id": snapshot.requirement_manifest_id,
            "execution_bundle_id": snapshot.execution_bundle_id,
            "plan_revision": self.plan_revision,
            "resume_session_id": "" if new_session else self.coding_session_id,
            "refresh_workspace": refresh_workspace,
        }
        previous_plan = self._snapshot_plan(snapshot)
        if previous_plan is not None:
            request["previous_plan"] = previous_plan.model_dump()
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
        self.coding_session_id = plan.session_id
        self.failed_phase = ""
        transition_id = f"{case_input.case_id}:planning:{signal_id}:{self.plan_revision}"
        await self._emit(
            "CodingPlanProposed",
            signal_id,
            {
                "planMarkdown": plan.plan_markdown,
                "planRevision": plan.revision,
                "baseRevisions": plan.base_revisions,
                "acceptanceCriteriaRefs": plan.acceptance_criteria_refs,
                "repositories": plan.repositories,
                "evidenceRefs": plan.evidence_refs,
                "risks": plan.risks,
                "openQuestions": plan.open_questions,
                "parentArtifactId": snapshot.product_artifact.artifact_id,
                **({
                    "supersedesArtifactId": snapshot.previous_artifact.artifact_id,
                } if snapshot.previous_artifact else {}),
            },
            artifact_transition=ArtifactTransitionRequest(
                kind="ProposePlanningArtifact",
                transition_id=transition_id,
                parent=snapshot.product_artifact,
                supersedes=snapshot.previous_artifact,
                expected_head=self._head("PLANNING"),
                content={
                    "planMarkdown": plan.plan_markdown,
                    "baseRevisions": plan.base_revisions,
                    "acceptanceCriteriaRefs": plan.acceptance_criteria_refs,
                    "repositories": plan.repositories,
                    "evidenceRefs": plan.evidence_refs,
                    "risks": plan.risks,
                    "openQuestions": plan.open_questions,
                },
            ),
            # Session 和模型执行用量只作为执行证据，不进入计划事实或事件投影。
            artifact_evidence=ArtifactEvidenceRequest(
                evidence_id=f"{transition_id}:execution",
                evidence_type="PlanningExecution",
                transition_id=transition_id,
                payload={
                    "sessionId": plan.session_id,
                    "turns": plan.turns,
                    "tokenUsage": plan.token_usage,
                },
            ),
        )

    async def _propose_legacy_coding_plan(
        self,
        signal_id: str,
        reuse_context: bool = False,
        refresh_workspace: bool = False,
        new_session: bool = False,
    ) -> None:
        """按 Artifact Layer 上线前的命令顺序重放仍在运行的旧 Case。"""

        case_input = self._case_input()
        if self.state.status != LifecycleStatus.activated:
            workflow.logger.warning("非法 Coding Plan 请求", extra={"status": self.state.status.value})
            return
        snapshot = self.context_snapshot if reuse_context else None
        if snapshot is None:
            try:
                payload = await workflow.execute_activity(
                    "fetch_context",
                    {
                        "system_id": case_input.system_id,
                        "prd_id": case_input.prd_id,
                        "work_item_id": case_input.work_item_id,
                        "requirement_manifest_id": case_input.prd.requirement_manifest_id,
                        "goal": case_input.prd.goal,
                        "draft": case_input.prd.draft_json,
                    },
                    start_to_close_timeout=timedelta(seconds=20),
                    retry_policy=RetryPolicy(maximum_attempts=3),
                )
            except (ActivityError, ApplicationError) as error:
                await self._block_worker(
                    signal_id, "context_fetch_failed", error, phase=ExecutionPhase.planning,
                )
                return
            snapshot = ContextSnapshot.model_validate(payload)
        if snapshot.stale_references:
            await self._block_worker(
                signal_id,
                "context_stale",
                RuntimeError("需求上下文已变化: " + ",".join(snapshot.stale_references)),
                {"staleReferences": snapshot.stale_references},
                phase=ExecutionPhase.planning,
            )
            return
        self.context_snapshot = snapshot
        self.plan_revision += 1
        await self._emit("CodingPlanStarted", signal_id, {
            "planRevision": self.plan_revision,
            "repositories": [repo.repo_id for repo in case_input.effective_repos()],
            "requirementManifestId": snapshot.requirement_manifest_id,
            "executionContextBundleId": snapshot.execution_bundle_id,
        })
        request = {
            "case_id": case_input.case_id,
            "work_item_id": case_input.work_item_id,
            "system_id": case_input.system_id,
            "repos": [repo.model_dump() for repo in case_input.effective_repos()],
            "goal": case_input.prd.goal,
            "acceptance_criteria": case_input.prd.acceptance_criteria,
            "feedback": self.rework_feedback,
            "requirement_context": snapshot.requirement_items,
            "execution_context": snapshot.execution_items,
            "requirement_manifest_id": snapshot.requirement_manifest_id,
            "execution_bundle_id": snapshot.execution_bundle_id,
            "plan_revision": self.plan_revision,
            "resume_session_id": "" if new_session else self.coding_session_id,
            "refresh_workspace": refresh_workspace,
        }
        if self.coding_plan is not None:
            # 旧 Activity 历史只包含这四个字段，保持命令输入完全一致。
            request["previous_plan"] = {
                "plan_markdown": self.coding_plan.plan_markdown,
                "revision": self.coding_plan.revision,
                "session_id": self.coding_plan.session_id,
                "base_revisions": self.coding_plan.base_revisions,
            }
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
            "planMarkdown": plan.plan_markdown,
            "planRevision": plan.revision,
            "baseRevisions": plan.base_revisions,
            "sessionId": plan.session_id,
        })

    def _snapshot_plan(self, snapshot) -> CodingPlanDraft | None:
        content = snapshot.previous_content
        if snapshot.previous_artifact is None or snapshot.previous_artifact.artifact_type != "PLANNING":
            return None
        return CodingPlanDraft(
            plan_markdown=str(content.get("planMarkdown", "")),
            revision=snapshot.previous_artifact.version,
            base_revisions=dict(content.get("baseRevisions", {})),
            acceptance_criteria_refs=list(content.get("acceptanceCriteriaRefs", [])),
            repositories=list(content.get("repositories", [])),
            evidence_refs=list(content.get("evidenceRefs", [])),
            risks=list(content.get("risks", [])),
            open_questions=list(content.get("openQuestions", [])),
        )

    def _planning_feedback(self, snapshot) -> str:
        # 人工意见必须来自已提交的 Transition/Evidence，Temporal 字段只负责本轮投递缓存。
        return "\n".join(note for note in snapshot.feedback_notes if note.strip())
