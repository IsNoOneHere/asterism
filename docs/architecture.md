# 架构

## 三层配置模型

- **Model Profile**：`id/provider/baseUrl/apiKey/model`，只描述如何访问模型，不知道调用者。公开 API 删除 `apiKey` 字段，只返回 `apiKeySet`。
- **Engine**：`claude_sdk/deepagents/http/fake`，只描述执行框架，不持有 Key。
- **Agent Role**：绑定 Engine 与 Model Profile，并配置 `pathScope/prompt/maxTurns/timeoutSeconds`。

完整 Profile 只由 worker-token 保护的 internal API 返回，并且只在 activity 进程内解析。Temporal workflow/activity 入参、事件、普通日志和前端均不携带 Key。

`ModelProfile + modelRouting + AgentRole` 是唯一运行时结构。旧 `businessModels`、单模型字段和独立 Claude 字段由 Flyway 一次性迁移后删除；部署环境的 `V5_MODEL_*` 仅保留给没有系统角色的旧 workflow history 回放。

## 执行内核

| Engine | 模型协议 | 行为 |
| --- | --- | --- |
| `claude_sdk` | Anthropic / Claude-compatible | 在隔离 workspace 多轮读写，worker 收集 git diff |
| `deepagents` | OpenAI-compatible | Deep Agents 文件后端在隔离 workspace 改码 |
| `http` | OpenAI-compatible | agent-service 单次生成 unified diff |
| `fake` | 无 | 测试基线，不用于生产工作项 |

## Handoff

`ExecutionPlan.assignments[]` 包含 `role/scope_paths/step_refs`。Planner 的 role 元数据由 `plan_execution` activity 从 internal API 读取并剔除 Profile/Key 后加入 prompt，因此真实 planner 可以选择角色。

Workflow 在现有 `start_modification` 内顺序执行 assignments。每段收到自己的 `step_refs` 与前序摘要；段内越出 role scope 时产生 `WorkerBlocked(role_scope_violation)`，不同段修改同一文件时产生 `WorkerBlocked(handoff_conflict)`。不冲突的 diff 按文件拼接，最终仍只有一个 `ModificationCompleted`。

跨框架不共享 SDK 会话，只交接工件和 `AgentStageCompleted` 事件。阶段事件的 causation/idempotency suffix 为 `stage:<index>:<role>`，可 replay 且不会相互去重。

## Temporal 修改守则

- 不改变已有生命周期状态和 signal 顺序；新阶段是 `start_modification` 内部活动。
- 已上线 workflow 的确定性分支保持 replay 兼容；老 history 没有 assignments 时走单 Agent 路径。
- 对不兼容 workflow 修改使用 Temporal worker versioning / `workflow.patched`，补 replay 测试后再移除旧分支。
- `domain_events.sequence` 与 `work_items.last_applied_sequence` 的投影机制不得绕过。
