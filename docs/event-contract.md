# 事件契约

`domain_events` append-only；`sequence` 是唯一投影顺序，`idempotencyKey` 保证命令幂等。纯审计事件不进入生命周期映射。

| Event | 说明 |
| --- | --- |
| `PRDUpdated` | PRD 草稿已更新；手工编辑时 payload 含 `source: manual_edit` 和最新 `status` |
| `PRDConfirmed` / `MemoryCandidateCreated` | PRD 确认冻结需求上下文并创建唯一 Approved ProductArtifact；提交后从 Artifact 提取待确认业务事实候选，候选失败不影响 Case 启动 |
| `CodingPlanStarted` | Claude SDK Supervisor 开始只读规划，payload 含 planRevision、repositories、contextManifestId |
| `CodingPlanProposed` | 基于 Approved ProductArtifact 创建 PROPOSED PlanningArtifact；payload 含 planMarkdown、baseRevisions 和 Artifact 关系 |
| `CodingPlanApproved` / `CodingPlanRejected` | PlanningArtifact 被批准，或携带必填 note 被保留并打回；重新规划创建带 supersedes 关系的新版本 |
| `CodingPlanInvalidated` | 审批后发现仓库基线已变化，旧 PlanningArtifact 失效；Workflow 刷新 workspace 并创建新版本 |
| `CodingAttemptStarted` | Claude SDK Supervisor 已启动，payload 含 architecture、supervisor、repositories、contextManifestId |
| `AgentStageCompleted` | 可选子 Agent 的审计事件，payload 含 stageIndex、role、repo、engine、changedPaths、agentId，不参与生命周期完成判定 |
| `RevisionRequested` | 人工带意见请求第 N 轮修订，引用当前 CodingArtifact；新正式结果会创建 supersedes 版本 |
| `ModificationCompleted` | 创建 PROPOSED CodingArtifact；payload 含 executionOutcome、repoDiffs、revision、Artifact 父节点和版本关系 |
| `WorkerBlocked` | Activity、SDK 终态、权限、门禁或发布执行被阻塞；Coding blocked 若有局部正式结果则创建 PROPOSED CodingArtifact |
| `ModificationCheckpointRestored` / `PatchCheckpointRestored` / `ValidationCheckpointRestored` | Workflow 从 Approved Artifact 恢复执行检查点时，按原生命周期顺序同步控制面投影；只恢复过程状态，不创建、审批或替代 Artifact |
| `PatchApplied` / `PatchApplyBlocked` / `PatchRejected` | PatchApplied 只记录当前 CodingArtifact 的接受证据；PatchRejected 将当前 CodingArtifact 标记为 REJECTED |
| `ValidationPassed` / `ValidationFailed` | ValidationPassed 批准当前 CodingArtifact；ValidationFailed 记录失败证据并进入现有修订流程，不原地修改 Content |
| `RepositoryReleasePrepared` | 单仓提交、推送和 MR 元数据已准备 |
| `MergeRequestCreated` | 每仓 MR 已创建或复用；首个事件把状态推进到 waiting_merge |
| `MergeRequestMerged` | Temporal 轮询确认单仓 MR 已合并，不单独改状态 |
| `MergeRequestClosed` | Temporal 轮询确认 MR 未合并而关闭，状态转 worker_blocked |
| `ReleaseCompleted` | local 模式提交完成，或 gitlab 模式全部 MR 已合并 |
| `MemoryApproved` / `MemoryRejected` | Artifact Memory Candidate 经人工确认生成 ACTIVE Memory，或被保留审计并拒绝 |
| `MemoryOutdated` / `MemoryArchived` | 来源 Artifact 被有效新版本替代，或正式 Memory 被人工归档 |

`AgentStageCompleted` 使用 `caseId:AgentStageCompleted:<signalId>:subagent:<index>:<agentId>` 作为稳定幂等键；同一个 Coding Attempt 内的仓库子 Agent 不会互相去重。

Artifact 相关事件必须携带类型化 Transition Command 和精确 `ArtifactRef`。Control Plane 在保存领域事件的同一事务中执行状态变化、写入 Transition/Evidence，并返回系统生成的 Artifact ID、版本、Root、Hash 和状态；Worker 验证完整返回引用后才推进阶段。`transitionId` 与事件 `idempotencyKey` 分别收敛 Artifact 命令和至少一次事件投递，模型不生成任何 Artifact 系统字段。

`parentArtifactId` 只表达 `Product → Planning → Coding`，`supersedesArtifactId` 只指向同 Root 的同类型旧版本。`PatchRejected` 和 `ValidationFailed` 都通过类型化 Reject Transition 保留当前 CodingArtifact 为 REJECTED；下一次正式代码结果创建带显式 supersedes 的新版本。

Memory 事件不是 Artifact 状态机的一部分。`PRDConfirmed` 提取 FACT，`CodingPlanApproved` 提取 DECISION / CONSTRAINT，`ModificationCompleted` 生成待验证的代码 EXPERIENCE，`ValidationFailed` 生成需人工补全根因和解法的问题 EXPERIENCE。候选确认前不创建正式 `memory_items`；CodingArtifact 未批准、候选含“待补充/待确认”占位或内容缺少来源 Artifact 时，确认请求必须失败。

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

`retry_current_phase` 根据 `failedPhase` 恢复对应 checkpoint，Workflow 候选丢失时从当前 CodingArtifact 恢复；恢复过程依次发送独立的 `*CheckpointRestored` 事件，使 PostgreSQL 投影与 Temporal 状态保持一致，但不会重复生成 Artifact 或 Evidence。`rework` 回到规划阶段生成新 PlanningArtifact；`rework_with_latest_config` 替换 Agent 配置快照后按 Approved Artifact 重建 Session；`rework_with_latest_context` 必须携带当前页面展示的有效 ProductArtifactRef，再创建新版 ProductArtifact 和 RequirementContextManifest。所有动作先产生业务事件，再以 `TemporalActionCompleted` 记录动作是否被接受。

`start_modification` 现在表示“生成执行计划”。计划生成后 Workflow 保持 `activated` 并等待 `coding_plan_approved | coding_plan_rejected`：批准后 Coding 只读取 Approved PlanningArtifact；Session 不可用时由该 Artifact、对应 ProductArtifact 和当前仓库事实重建。打回必须提供 note，并以 Approved ProductArtifact、上一版计划和人工意见创建新 PlanningArtifact/Session。人工等待不运行 Claude 进程，也不占 Activity。

计划审批可由 Temporal 无期限等待，但计划只对 `baseRevisions` 对应的仓库版本有效。批准后若源仓库已推进，Workflow 产生 `CodingPlanInvalidated`，原子刷新 Case workspace，并创建新 Planning Session 在最新代码上重新规划；不会绕过基线门禁执行旧计划。人工完整重做 `rework` 也会先刷新 workspace 并创建新规划 Session。

`patch_apply_rejected` 和 `waiting_merge` 下的 `rework` 必须提供非空 note。前者在一次 signal 中自动产生 `PatchRejected → ReworkStarted → RevisionRequested → CodingAttemptStarted`；后者的 `RevisionRequested.phase=merge`，其余为 `review`。`revisionMode` 只有 `incremental | full`；如果候选恢复在 Activity 中降级，最终值以 `ModificationCompleted.revisionMode` 为准。

Artifact Evidence 通过类型化 `AppendArtifactEvidence` 命令提交；Evidence 类型必须与 Domain Event 匹配，携带 transitionId 时还必须和同一 Artifact 的 Transition 对应。人工修订意见从已提交的 Transition/Evidence 汇总进 Artifact Context Snapshot，不从 Workflow 内存 plan、feedback 或 candidate 兜底。

完整生命周期迁移见 [lifecycle-transitions.json](lifecycle-transitions.json)。
