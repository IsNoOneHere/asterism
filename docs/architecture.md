# 架构

## 四服务部署边界

发布包只运行四个业务容器：

- `server`：Spring Boot Control Plane，同时托管 Workbench 静态资源并对外提供 `8080`。
- `runner`：在一个 asyncio 生命周期内运行 agent-service HTTP 接口和 Temporal Worker；源码模块保持独立。
- `postgres`：业务数据、配置、附件元数据和投影。
- `temporal`：生命周期、人工等待、重试与恢复。

Runner 的 `8090` 只在容器网络内使用，所有业务接口通过内部 Token 鉴权；`/healthz` 仅用于容器健康检查。PostgreSQL、Temporal、附件和 Worker artifacts 均使用独立持久卷，`upgrade` 只能替换 server 与 runner。

## 当前版本边界

生命周期只注册一个 Temporal workflow type：`AsterismCaseWorkflow`。仓库不保留 V5/V6 Workflow 类、旧 Planner 执行链或 replay 分支。`/api/v5` 与 PostgreSQL schema `control_plane_v5` 是现有接口和存储命名，不代表存在多套 Workflow 实现。

Temporal history 与业务数据库一样持久化，升级不得清理。任何 replay 可见的修改都必须先建立 Worker Versioning 或 `workflow.patched` 迁移方案，普通发布只替换无状态 Server 与 Runner。

## 两层配置模型

```mermaid
flowchart LR
    P["ModelProfile\nprovider/baseUrl/apiKey/model"] --> U["product\nPRD 对话"]
    P --> D["developer\nCoding Supervisor"]
    D --> E["claude_sdk_team"]
    E --> R["Root Supervisor\n直接写 + 可选 subagents"]
```

- **ModelProfile**：模型接入点，只描述 `name/provider/baseUrl/apiKey/model`；公开 API 只返回 `apiKeySet`。
- **Agent**：只保留内置 `product` 与 `developer`。`product` 负责需求对话；`developer` 负责一个完整 Coding Attempt。
- **Repository**：仓库配置持有真实的 `allowedPaths`、`forbiddenPaths` 和验证命令，是子 Agent 权限的唯一业务来源。

完整 Profile 只由 Worker Token 保护的 internal API 返回，并且只在 Activity 进程内解析。Temporal 入参、事件、普通日志和前端均不携带 API Key。Case 启动时固定不含 Key 的 `agents + modelProfiles` 快照；Activity 每次执行实时读取所选 Profile 的 Key，因此换 Key 不需要重建 Case。

## 执行内核与扩展点

| Engine | 用途 | 行为 |
| --- | --- | --- |
| `claude_sdk_team` | 生产代码执行 | 一个 Claude SDK Root Supervisor 对整个 Coding Attempt 负责，原生子 Agent 仅作可选加速 |
| `fake` | 测试基线 | 返回确定性结果，不允许用于业务工作项 |

Worker 内部保留 `ExecutionProvider` 协议作为开源扩展点。新执行内核应实现统一的 `CodingPlanRequest → CodingPlanDraft` 与 `CodingAttemptRequest → CodingAttemptResult` 协议并在 factory 注册；生命周期 Workflow 不感知厂商协议，也不为新 Provider 增加一套 Planner 或状态机。

## Coding Attempt

```mermaid
flowchart LR
    T["Temporal 生命周期"] --> P["Claude SDK Planning Turn\n真实仓库只读"]
    P --> H["人工批准 / 带意见打回"]
    H -->|"批准"| S["优先恢复计划 Session\n不可用则重建"]
    H -->|"打回，新 Session"| P
    S --> A["Root Supervisor\n路径门禁内直接写"]
    A --> O["SDK 自然语言终态"]
    O --> D["按仓库收集 Git Diff"]
    D --> G["路径门禁 + git apply --check"]
    G --> H["人工代码确认 / 验证 / 发布"]
```

Workflow 固定执行 `fetch_context → generate_coding_plan → 人工等待 → run_coding_attempt → ModificationCompleted | WorkerBlocked`。Planning Turn 属于同一个 `claude_sdk_team` Provider，不恢复旧 `/plan`、assignments 或独立 Planner Profile。模型只返回供人阅读的 Markdown 计划，不生成 `taskId`、路径权限或状态机字段；`revision/sessionId/baseRevisions` 由 Worker 确定性生成。Root Supervisor 可在仓库门禁内直接写；每仓原生子 Agent 只继承同一模型和受限路径，是否使用由 Root 决定，不形成外部 Stage 或 handoff。

Planning Activity 正常结束后 Claude 进程退出，Temporal Workflow 可长期等待人工信号。恢复的权威来源是持久执行上下文：已批准计划与 `baseRevisions`、Case workspace、候选 Diff、人工反馈和轮次；Claude Session 只是优先复用的上下文加速项。本地单 Worker 直接恢复 artifacts 持久卷中的原生 Claude runtime，Session transcript 同时镜像到 Store 供审计和未来共享存储适配器使用。如果本机 runtime 不存在，Provider 会从持久执行上下文创建新 Session，不让工作项卡死，也不会为了读取父进程私有物化目录而取消 Claude CLI 的低权限隔离。计划打回或仓库基线失效时，系统把上一版计划与意见传入一个新的 Planning Session，避免连续打回造成上下文无限膨胀。执行阶段不再读取 Agent 的 15 分钟 `timeoutSeconds` 作为总时限，Temporal 仅保留 24 小时 Activity 失控保护，并通过心跳和一次自动重试恢复 Worker 中断。

SDK 顶层工具列表是整个 Attempt 的能力上限。Root Supervisor 的 Edit/Write 会先按目标文件定位所属仓库，再应用该仓库 `allowedPaths/forbiddenPaths`；未绑定仓库的内置 Agent 默认只读；仓库 Agent 也只能写自己负责的仓库。验证命令由外层 Workflow 执行，Coding 会话不开放 Bash。

Provider 不要求模型生成结构化终态。Claude SDK 的自然语言结果只用于展示；Worker 根据 `ResultMessage`、权限拒绝、延迟工具、真实 Git Diff 和 Session 元数据生成系统 `ExecutionOutcome`。`SubagentStart/Stop` 和后台任务终态只作遥测，不参与完成判定。SDK 受阻或没有有效 Diff 时进入 `WorkerBlocked` 并保留 Session、工作区和局部候选；只有 SDK 正常结束、存在真实 Diff、路径门禁和 `git apply --check` 全部通过才产生 `ModificationCompleted`。

## 阶段恢复

生命周期按 `planning → coding → patch → validation → release` 恢复。失败事件记录 `failedPhase`，恢复 runner 与 checkpoint 由注册表声明；新增阶段通过增加策略扩展，不按错误原因堆叠条件分支。

Workflow 按职责拆分为四个模块：`lifecycle.py` 只保留 signal、query、主循环与 `ActionSpec` 动作分发；`coding.py` 管理 Coding Attempt 与候选上下文；`publishing.py` 管理 Patch、MR 和 Release；`validation.py` 管理验证与回滚。只有 `lifecycle.py` 注册 Temporal Workflow type，其余模块不引入第二套状态机。

| 动作 | 语义 | 复用范围 |
| --- | --- | --- |
| `retry_current_phase` | 重试失败阶段 | 保留上下文、候选 Diff 与已完成阶段 |
| `rework` | 完整重做 | 回到 Planning Turn 生成新计划，保留人工反馈但不恢复旧候选 |
| `rework_with_latest_config` | 刷新配置后重试 Planning/Coding | 替换 Agent 配置快照，保留 Session、候选与上下文 |

Patch 恢复会复用 `ModificationCompleted`；Validation 额外恢复 `PatchApplied`；Release 再额外恢复 `ValidationPassed`。GitLab 临时 clone 目录属于 Activity 资源，不写入 Workflow history。

## 人工打回修订闭环

```mermaid
flowchart LR
    M["ModificationCompleted\n人工审查 Diff"] -->|"通过"| P["Patch / 验证 / 发布"]
    M -->|"必填意见打回"| R["RevisionRequested\nrevision=N"]
    R --> C["恢复上一版候选"]
    C -->|"可恢复"| I["增量修订"]
    C -->|"不可恢复"| F["带意见全量修订"]
    I --> M
    F --> M
    R -->|"达到 maxRevisions"| B["WorkerBlocked\nrevision_limit_reached"]
```

`patch_apply_rejected` 是一步闭环：Workflow 依次产生 `PatchRejected → ReworkStarted → RevisionRequested`，然后在同一手工动作中启动新 Coding Attempt。不新增生命周期状态，而是复用 `activated → modification_completed` 循环。

修订 Activity 使用冻结的需求上下文，把上一版候选 Diff 恢复到新鲜的团队工作区，并向 Supervisor 注入结构化的 `revision_context`：轮次、人工意见、上一轮 Diff 摘要和“只修订意见涉及部分”指令。候选与当前仓库基线冲突时，Activity 先撤销已恢复的部分，再降级为 `full`，不将半应用工作区交给模型。

`maxRevisions` 是系统级执行策略，默认 5，Case 创建时冻结进 Temporal 入参。达到上限后用 `WorkerBlocked(reason=revision_limit_reached)` 停下，负责人只能取消或完整重做；完整重做会重置轮次。

`waiting_merge` 的“打回修订”共用同一套 `RevisionRequested`。Worker 保留原工作项分支的远端 commit 基线，用 `force-with-lease` 将新 commit 更新到原分支；GitLab 因此复用原 MR，同时会拒绝覆盖他人在远端的并行修改。

## Temporal 修改守则

- `local` 模式保持本地分支发布；`gitlab` 模式增加 `waiting_merge`。
- `domain_events.sequence` 与 `work_items.last_applied_sequence` 的投影机制不得绕过。
- Workflow 只编排确定性状态和 Activity 调用；网络、文件系统、Git 与模型调用只能在 Activity 内执行。
- 正式上线后，对 replay 不兼容的修改使用 Worker Versioning / `workflow.patched` 并补 replay 测试。

## GitLab 发布边界

`releaseMode=gitlab` 时，Patch 审批后由 Worker 在临时 shallow clone 中按仓验证、提交 `wi/<workItemId>`、用 `force-with-lease` 幂等推送并创建或复用 MR。Token 仅由 Activity 通过 internal API 实时读取，临时 `0600` credential store 在 Git 命令结束后删除，不进入 Temporal、事件、日志、前端或 `.git/config`。

全部 MR 创建后进入 `waiting_merge`。Temporal timer 主动调用 GitLab API：部分合并继续等待，全部合并产生 `ReleaseCompleted`，MR 被关闭则进入 `worker_blocked`。合并后的 CI/CD、部署和服务重启属于 GitLab Runner，不进入 Asterism 生命周期。

## Product Agent 契约边界

Product Agent 不返回完整 PRD 聚合，只返回严格 `PrdPatch`：`title`、`goal`、`scope`、`acceptanceCriteria`。Patch 中缺失或为 `null` 的字段保持原值；控制面使用闭合类型逐字段合并，不接受额外字段，也不使用通用 `Map.putAll`。

`targets`、`suspectedTargets`、`status`、`missingFields`、`usedContextRefs`、ID 和审计字段归控制面所有。`suspectedTargets` 只能由系统知识匹配产生，`targets` 只能由人工确认接口写入。模型只能为本轮修改的语义字段提出 citations；控制面校验字段键与召回 ref，并从最终 citations 求 `usedContextRefs`。

控制面发送给 Product Agent 的当前草稿同样只包含四个语义字段，避免系统状态被模型回显。记忆候选不属于 PRD 主响应：PRD 确认并成功启动 Case 后，控制面以冻结的需求上下文异步调用独立候选提取；失败仅记录日志，不回滚 PRD 或阻断工作项。

## 多模态截图管线

```mermaid
flowchart LR
    U["业务用户粘贴截图"] --> A["控制面鉴权附件"]
    A --> V["agent-service 视觉观察"]
    V --> O["UiObservation 可见锚点"]
    O --> K["PostgreSQL pg_trgm\napproved-only 检索"]
    K --> C["用户确认疑似页面"]
    C --> P["PRD targets hint"]
    P --> W["AsterismCaseWorkflow"]
    R["Worker 路由索引"] --> Q["system_knowledge candidate"]
    Q --> K
    W --> L["ReleaseCompleted changed paths"]
    L --> Q
```

控制面负责附件鉴权、短暂转发图片字节、知识检索和确认；agent-service 是唯一调用视觉模型的组件；Worker 读取 repo 并通过 `AsterismRouteIndexWorkflow` 回写 candidate，控制面不读取源码目录。

三条铁律：

1. 图片本体不进入 Temporal payload、domain event payload 或 memory，只流转附件 ID 与派生文本。
2. 接口和代码位置依赖系统知识检索与人工确认，视觉模型只描述画面，不直接猜测实现。
3. `system_knowledge` 只有 `approved` 条目参与匹配，candidate、rejected、disabled 均不投喂。
