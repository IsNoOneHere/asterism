# 事件契约

`domain_events` append-only；`sequence` 是唯一投影顺序，`idempotencyKey` 保证命令幂等。纯审计事件不进入生命周期映射。

| Event | 说明 |
| --- | --- |
| `ExecutionPlanDrafted` | 计划与 assignments 已生成，不改状态 |
| `PRDUpdated` | PRD 草稿已更新；手工编辑时 payload 含 `source: manual_edit` 和最新 `status` |
| `AgentStageCompleted` | 单个 role 完成，payload 含 stageIndex、role、engine、摘要、changedPaths、tokenUsage，不含 Key |
| `ModificationCompleted` | 单 Agent diff 或多段无冲突合并 diff 完成 |
| `WorkerBlocked` | 执行被阻塞 |
| `ReleaseCompleted` | `wi/<workItemId>` 分支和 commit 已创建 |

`AgentStageCompleted` 使用 `caseId:AgentStageCompleted:<signalId>:stage:<index>:<role>` 作为稳定幂等键；causationId 使用相同 stage suffix。

相邻 Agent 通过 `HandoffContext{role,summary,diff_patch,interface_notes}` 列表交接。单段 diff 不超过 32KB 时完整传递；超出后 `diff_patch` 只保留 changed paths、`diff --git` 行和 hunk 头，且最终仍限制在 32KB。

`WorkerBlocked.payload.reason` 额外支持：

- `role_scope_violation`：role diff 越出自身 path scope。
- `handoff_conflict`：两个 assignment 修改同一文件。
- `execution_failed`：执行内核异常或阶段 diff 无效。

新快照 Case 的多 assignment 执行失败时，`WorkerBlocked.payload` 还包含：

- `completed_stages`：已完成段的 role、summary、changed_paths。
- `failed_stage`：失败段的 index、role。

此时 `rework` 先发出 `ReworkStarted`，随后直接从 `failed_stage.index` 续跑；已完成段的结果和 handoff 复用，不重新抓上下文、规划或执行。其它失败仍保持原语义：`rework` 回到 `activated`，等待新的 `start_modification`。全新重跑使用 cancel + 新工作项。

多段执行仍不新增生命周期状态、signal 或人工审批 gate。完整生命周期迁移见 [lifecycle-transitions.json](lifecycle-transitions.json)。
