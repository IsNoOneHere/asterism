# 事件契约

`domain_events` append-only；`sequence` 是唯一投影顺序，`idempotencyKey` 保证命令幂等。纯审计事件不进入生命周期映射。

| Event | 说明 |
| --- | --- |
| `ExecutionPlanDrafted` | 计划与 assignments 已生成，不改状态 |
| `PRDUpdated` | PRD 草稿已更新；手工编辑时 payload 含 `source: manual_edit` 和最新 `status` |
| `AgentStageCompleted` | 单个 role 完成，payload 含 role、engine、摘要、changedPaths、tokenUsage，不含 Key |
| `ModificationCompleted` | 单 Agent diff 或多段无冲突合并 diff 完成 |
| `WorkerBlocked` | 执行被阻塞 |
| `ReleaseCompleted` | `wi/<workItemId>` 分支和 commit 已创建 |

`AgentStageCompleted` 使用 `caseId:AgentStageCompleted:<signalId>:stage:<index>:<role>` 作为稳定幂等键；causationId 使用相同 stage suffix。

`WorkerBlocked.payload.reason` 额外支持：

- `role_scope_violation`：role diff 越出自身 path scope。
- `handoff_conflict`：两个 assignment 修改同一文件。
- `execution_failed`：执行内核异常或阶段 diff 无效。

多段执行仍在原 `start_modification` 动作内，不新增生命周期状态、signal 或人工审批 gate。完整生命周期迁移见 [lifecycle-transitions.json](lifecycle-transitions.json)。
