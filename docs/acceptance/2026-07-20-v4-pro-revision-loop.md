# V4 Pro 修订闭环回归记录

- 日期：2026-07-20（Asia/Shanghai）
- 来源工作项：`WI202607194360`
- 回归工作项：`WI202607201345`
- 系统：`sys-af331b5e7827`
- 执行内核：`claude_sdk_team`
- Product / Worker 模型：`deepseek-v4-pro`
- Anthropic 兼容入口：`https://api.deepseek.com/anthropic`

## 前置问题与修复

1. Route Index 的 Java `RepoSnapshot` 被直接放入通用 `Map`，未经过已配置的 snake_case `ObjectMapper`，Python Workflow 收到 `repoId/localPath` 后无法按 `repo_id/local_path` 解析。边界改为显式 `convertValue` 后，落库 115 条页面候选和 467 条 API 候选，共 582 条。
2. Claude SDK 后台子 Agent 不能处理交互式授权，原来的 `permission_mode=default` 会让内置 Explore / 仓库 Agent 在请求工具时被拒，最终表现为 `Coding Attempt 未生成代码变更`。现在只预授权固定 `TEAM_TOOLS`，使用 `dontAsk`，继续由 `PreToolUse` Hook 做仓库路径门禁；Bash 与网络工具不进入 Coding 工具面，验证命令由外层 Workflow 执行。这是 SDK 接入权限语义问题，不是 V4 Pro 模型能力问题。
3. 同一 Case 多轮修订原来会清空同名 JSONL，导致上一轮 SDK 轨迹丢失。轨迹现改为追加写入，每次执行先写 `attempt_start`，记录时间、Case、修订轮次和模式；初始执行、Activity 重试和人工修订均使用同一机制。

## 真实流程

1. 修复前两次 Coding 分别在事件 22、27 以 `coding_attempt_failed` 阻塞，业务事件只记录到“未生成代码变更”。修复配置边界与 SDK 权限后，从事件 31 重新执行成功。
2. 初始候选在事件 36 完成。人工审查发现 Agent 把问卷中的部门题答案误当成人员所属部门，并扩大修改到 `AnswerServiceImpl.java`。
3. 事件 39 带意见打回，事件 41 自动发出 `RevisionRequested(revision=1, revisionMode=incremental)`，无需再次点击开始。第 1 轮修订在事件 47 完成。
4. 第 1 轮仍在 `AnswerServiceImpl.java` 留下一行无意义空白。事件 50 再次带意见打回，事件 52 自动发出 `RevisionRequested(revision=2, revisionMode=incremental)`；第 2 轮在事件 56 完成，并移除该文件的候选 Diff。
5. 事件 59 应用 Patch；事件 60 验证通过，前后端均执行 `git diff --check` 且退出码为 0；事件 65 完成本地发布，工作项最终状态为 `completed`。

最终只保留三个目标文件：

- Backend：`ExamScoreExcelVO.java`
- Frontend：`types.ts`
- Frontend：`MemberScore.tsx`

本地发布结果：Backend `wi/WI202607201345` / `ac2896e`，Frontend `wi/WI202607201345` / `e1f9666`。两个业务仓库均未推送，原工作区分支和已有未提交改动保持不变。

## 页面验收

- 详情页显示“已完成”，执行内核为 `claude_sdk_team`。
- “修订历史”按轮次显示两轮意见、时间与“增量修订”。
- “代码变更”仅显示最终三个文件。
- Backend / Frontend 自动检查均显示 `git diff --check`。

结论：V4 Pro 可以通过 Claude SDK Supervisor 完成多仓开发；两轮人工带意见打回均自动增量修订并最终完成发布。首轮业务理解偏差需要人工审查纠正，但修订闭环、候选复用、范围收敛和验证发布链路均符合设计。
