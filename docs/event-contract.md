# 事件契约

`domain_events` append-only；`sequence` 是唯一投影顺序，`idempotencyKey` 保证命令幂等。纯审计事件不进入生命周期映射。

| Event | 说明 |
| --- | --- |
| `PRDUpdated` | PRD 草稿已更新；手工编辑时 payload 含 `source: manual_edit` 和最新 `status` |
| `PRDConfirmed` / `MemoryCandidateCreated` | PRD 确认冻结需求上下文；确认成功后独立提取记忆候选，候选失败不影响 Case 启动 |
| `CodingPlanStarted` | Claude SDK Supervisor 开始只读规划，payload 含 planRevision、repositories、contextManifestId |
| `CodingPlanProposed` | 可人工审批的 Markdown 计划已生成，payload 含 planMarkdown、baseRevisions、sessionId；模型文本不参与权限裁决 |
| `CodingPlanApproved` / `CodingPlanRejected` | 计划已批准，或携带必填 note 被人工打回；打回后同一 signal 自动触发下一版规划 |
| `CodingPlanInvalidated` | 审批后发现仓库基线已变化，旧计划自动失效；Workflow 刷新持久 workspace 并创建新 Planning Session |
| `CodingAttemptStarted` | Claude SDK Supervisor 已启动，payload 含 architecture、supervisor、repositories、contextManifestId |
| `AgentStageCompleted` | 可选子 Agent 的审计事件，payload 含 stageIndex、role、repo、engine、changedPaths、agentId，不参与生命周期完成判定 |
| `RevisionRequested` | 人工带意见请求第 N 轮修订，payload 含 note、revision、requestedBy、phase、revisionMode 和上一轮 Diff 摘要 |
| `ModificationCompleted` | SDK 正常结束，且有效 Diff、路径门禁和 apply 检查全部通过；payload 含系统生成的 executionOutcome、repoDiffs、revision 与 revisionMode |
| `WorkerBlocked` | Activity、SDK 终态、权限、门禁或发布执行被阻塞，payload 含稳定 reason 与 failedPhase；Coding blocked 另含 executionOutcome 和局部 changedPaths |
| `PatchApplied` / `PatchApplyBlocked` / `PatchRejected` | 候选代码已应用、应用被阻塞或被人工打回；阻塞事件 payload 含 reason、repo、`failedPhase: patch`，可原位重试 |
| `ValidationPassed` / `ValidationFailed` | 自动或人工验证结果 |
| `RepositoryReleasePrepared` | 单仓提交、推送和 MR 元数据已准备 |
| `MergeRequestCreated` | 每仓 MR 已创建或复用；首个事件把状态推进到 waiting_merge |
| `MergeRequestMerged` | Temporal 轮询确认单仓 MR 已合并，不单独改状态 |
| `MergeRequestClosed` | Temporal 轮询确认 MR 未合并而关闭，状态转 worker_blocked |
| `ReleaseCompleted` | local 模式提交完成，或 gitlab 模式全部 MR 已合并 |

`AgentStageCompleted` 使用 `caseId:AgentStageCompleted:<signalId>:subagent:<index>:<agentId>` 作为稳定幂等键；同一个 Coding Attempt 内的仓库子 Agent 不会互相去重。

`WorkerBlocked.payload.reason` 的主要值：

- `context_fetch_failed`：获取需求上下文失败。
- `coding_plan_failed`：只读规划 Activity 异常或没有产生可审批计划文本。
- `coding_attempt_failed`：Claude SDK Supervisor 异常、未生成 Diff 或 Diff 门禁失败。
- `coding_attempt_blocked`：SDK 终态异常、权限请求被拒、工具被延迟或未生成有效 Diff；Session 与局部候选保留供续跑。
- `patch_apply_failed` / `patch_revert_failed`：本地 Patch 应用或回滚失败。
- `validation_activity_failed`：验证 Activity 本身异常；测试不通过使用 `ValidationFailed`。
- `mr_create_failed` / `mr_ready_failed`：GitLab 推送、MR 创建或 ready 操作失败。
- `release_failed` / `push_failed`：local 发布或推送失败。
- `recovery_artifact_missing`：阶段恢复缺少上下文或候选 Diff。
- `revision_limit_reached`：人工打回已达 `maxRevisions`，等待负责人取消或完整重做。

`retry_current_phase` 根据 `failedPhase` 恢复对应 checkpoint；`rework` 回到规划阶段生成新计划；`rework_with_latest_config` 只替换 Agent 配置快照后重试 Planning/Coding。所有动作先产生业务事件，再以 `TemporalActionCompleted` 记录动作是否被接受。

`start_modification` 现在表示“生成执行计划”。计划生成后 Workflow 保持 `activated` 并等待 `coding_plan_approved | coding_plan_rejected`：批准后优先恢复该计划的 Claude Session，Session 不可用时由已批准计划和持久 Case 上下文重建；打回必须提供 note，并以“上一版计划 + 人工意见”创建新 Planning Session，自动产生下一版 `CodingPlanStarted → CodingPlanProposed`。人工等待不运行 Claude 进程，也不占 Activity。

计划审批可由 Temporal 无期限等待，但计划只对 `baseRevisions` 对应的仓库版本有效。批准后若源仓库已推进，Workflow 产生 `CodingPlanInvalidated`，原子刷新 Case workspace，并创建新 Planning Session 在最新代码上重新规划；不会绕过基线门禁执行旧计划。人工完整重做 `rework` 也会先刷新 workspace 并创建新规划 Session。

`patch_apply_rejected` 和 `waiting_merge` 下的 `rework` 必须提供非空 note。前者在一次 signal 中自动产生 `PatchRejected → ReworkStarted → RevisionRequested → CodingAttemptStarted`；后者的 `RevisionRequested.phase=merge`，其余为 `review`。`revisionMode` 只有 `incremental | full`；如果候选恢复在 Activity 中降级，最终值以 `ModificationCompleted.revisionMode` 为准。

完整生命周期迁移见 [lifecycle-transitions.json](lifecycle-transitions.json)。
