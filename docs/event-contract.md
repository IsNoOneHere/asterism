# 事件契约

`domain_events` append-only；`sequence` 是唯一投影顺序，`idempotencyKey` 保证命令幂等。纯审计事件不进入生命周期映射。

| Event | 说明 |
| --- | --- |
| `PRDUpdated` | PRD 草稿已更新；手工编辑时 payload 含 `source: manual_edit` 和最新 `status` |
| `CodingAttemptStarted` | Claude SDK Supervisor 已启动，payload 含 architecture、supervisor、repositories、contextManifestId |
| `AgentStageCompleted` | 仓库子 Agent 完成，payload 含 stageIndex、role、repo、engine、changedPaths、agentId，不含 Key |
| `RevisionRequested` | 人工带意见请求第 N 轮修订，payload 含 note、revision、requestedBy、phase、revisionMode 和上一轮 Diff 摘要 |
| `ModificationCompleted` | Coding Attempt 已生成有效 Diff；多仓 Diff 写入 `repoDiffs`，修订轮附带 revision 与最终 revisionMode |
| `WorkerBlocked` | Activity、模型、门禁或发布执行被阻塞，payload 含稳定 reason 与 failedPhase；修订期同时含 revision/revisionMode |
| `PatchApplied` / `PatchRejected` | 候选代码已应用或被人工打回 |
| `ValidationPassed` / `ValidationFailed` | 自动或人工验证结果 |
| `RepositoryReleasePrepared` | 单仓提交、推送和 MR 元数据已准备 |
| `MergeRequestCreated` | 每仓 MR 已创建或复用；首个事件把状态推进到 waiting_merge |
| `MergeRequestMerged` | Temporal 轮询确认单仓 MR 已合并，不单独改状态 |
| `MergeRequestClosed` | Temporal 轮询确认 MR 未合并而关闭，状态转 worker_blocked |
| `ReleaseCompleted` | local 模式提交完成，或 gitlab 模式全部 MR 已合并 |

`AgentStageCompleted` 使用 `caseId:AgentStageCompleted:<signalId>:subagent:<index>:<agentId>` 作为稳定幂等键；同一个 Coding Attempt 内的仓库子 Agent 不会互相去重。

`WorkerBlocked.payload.reason` 的主要值：

- `context_fetch_failed`：获取需求上下文失败。
- `coding_attempt_failed`：Claude SDK Supervisor 异常、未生成 Diff 或 Diff 门禁失败。
- `patch_apply_failed` / `patch_revert_failed`：本地 Patch 应用或回滚失败。
- `validation_activity_failed`：验证 Activity 本身异常；测试不通过使用 `ValidationFailed`。
- `mr_create_failed` / `mr_ready_failed`：GitLab 推送、MR 创建或 ready 操作失败。
- `release_failed` / `push_failed`：local 发布或推送失败。
- `recovery_artifact_missing`：阶段恢复缺少上下文或候选 Diff。
- `revision_limit_reached`：人工打回已达 `maxRevisions`，等待负责人取消或完整重做。

`retry_current_phase` 根据 `failedPhase` 恢复对应 checkpoint；`rework` 自动回到 Coding Attempt 完整重做；`rework_with_latest_config` 只替换 Agent 配置快照后重试 Coding。所有动作先产生业务事件，再以 `TemporalActionCompleted` 记录动作是否被接受。

`patch_apply_rejected` 和 `waiting_merge` 下的 `rework` 必须提供非空 note。前者在一次 signal 中自动产生 `PatchRejected → ReworkStarted → RevisionRequested → CodingAttemptStarted`；后者的 `RevisionRequested.phase=merge`，其余为 `review`。`revisionMode` 只有 `incremental | full`；如果候选恢复在 Activity 中降级，最终值以 `ModificationCompleted.revisionMode` 为准。

完整生命周期迁移见 [lifecycle-transitions.json](lifecycle-transitions.json)。
