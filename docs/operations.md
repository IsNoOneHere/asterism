# 运维

## 启动

```bash
cp .env.example .env
make prod-up
make doctor
```

`.env` 至少设置随机 `V5_WORKER_CALLBACK_TOKEN` 和 `V5_DB_PASSWORD`。可设置 `V5_ADMIN_INITIAL_PASSWORD`；留空时 control-plane 只在首次创建 admin 时打印随机密码。生产不创建演示用户、演示系统或固定默认密码。

入口：Workbench `http://127.0.0.1:8080`，control-plane `:8085`，agent-service `:8090`，Temporal UI `:8233`。Docker Compose 生产 profile 只将 Workbench 绑定到所有网卡，其余入口绑定 `127.0.0.1`。

## 常用命令

```bash
make doctor
make smoke-real
make smoke-gitlab
CONFIRM=yes make prod-reset
make test-python
make test-web
```

`make smoke-real` 需要 `V5_AGENT_API_KEY`、`V5_MODEL_API_KEY` 和 `V5_SMOKE_ADMIN_PASSWORD`，缺失会明确失败，不会退回 fake。Java 验证使用 `make test-java`；该目标需要 Docker/Testcontainers。

当前系统尚未上线，不保留旧 Workflow history。切换执行架构或修改 Workflow type 后，使用 `CONFIRM=yes make prod-reset` 清理本地测试数据库与 Temporal 数据，再部署终态 Worker。正式上线后禁止采用该方式升级。

## 修订闭环验收

1. 创建需求，负责人审批并点击“生成执行计划”。
2. 核对计划中的仓库任务、验收标准引用、代码证据和风险；带意见打回一次，确认下一版计划自动生成，再批准计划。
3. 等待代码修改完成，进入“代码变更”审查 Diff。
4. 在“修订意见（必填）”中写明问题，点击“打回修订”。
5. 确认页面立即显示“第 1 轮修订中”，无需再提交“开始执行”。
6. 在“修订历史”核对意见、提交人、时间、Diff 摘要和 `incremental | full`。
7. 第二轮 Diff 通过后继续验证与发布。GitLab 模式另外在 `waiting_merge` 打回一次，确认原 MR 的 source branch 不变且 commit 已更新。

Planning Activity 最长 45 分钟，Coding Activity 的 24 小时仅是失控保护，不是 15 分钟业务截止时间。人工审批期间没有 Activity 在运行，Temporal Workflow 可持续等待。`worker-artifacts` 持久卷同时保存 Claude 原生 runtime、Session 镜像和 `cases/<caseId>/workspace`；不要把该卷改成容器临时目录，否则 Worker 重启后会丢失会话加速上下文与未回传的局部代码。本地 Worker 优先从原生 runtime 恢复；runtime 丢失或 Worker 工作区路径变化时，系统根据已批准计划、Case workspace、候选 Diff 和人工意见重建新 Session。多 Worker 的共享 Session 物化需要独立 Artifact Store 适配器，不在单 Worker 部署中假装支持。Session 与工作区的保留周期应覆盖工作项生命周期，终态清理由后续运维策略统一处理。

管理员在“Agent”页设置 `maxRevisions`，取值 1–20，默认 5。该值只对之后创建的 Case 生效。达到上限时事件应为 `WorkerBlocked(reason=revision_limit_reached)`；选择“完整重做”后轮次从 0 重新计算。

## Release 语义

`local` 模式只在本地仓库创建并提交 `wi/<workItemId>` 分支。`gitlab` 模式为每个仓库推送同名分支并创建或复用 MR，全部 MR 合并后工作项才完成。合并后的 CI/CD、部署和服务重启由 GitLab Runner 负责。

## GitLab 集成部署

1. 在 GitLab 创建专用的 Project/Group Access Token，限定到业务项目或组，角色至少为 Developer。单 token 同时执行 clone、push 和 MR REST API 时使用 `api` scope；不要把 token 写进仓库 URL。
2. 设置 `ASTERISM_GITLAB_BASE_URL`、`ASTERISM_GITLAB_TOKEN` 和用户可访问的 `V5_PUBLIC_URL`，然后重建 control-plane 与 Worker。也可在系统“Git 与发布”配置中覆盖连接，读取接口只返回 `tokenSet`。
3. 系统配置选择 `releaseMode=gitlab`，逐仓填写 GitLab project、默认分支、路径门禁和测试命令。`validationMode=auto` 在 push 前运行测试，`skip` 把测试留给 MR CI 与人工。
4. 运行 `make doctor`，确认 Worker 镜像含 Git、GitLab API 可达且系统 readiness 通过。

合并状态由 Temporal Workflow 每 60 秒轮询 GitLab。网络只需允许 Asterism 到 GitLab；部分仓已合并时继续等待，全部合并后完成，MR 未合并而关闭时转为阻塞。

`make smoke-gitlab` 会创建临时 GitLab 项目并跑到 MR 合并，再等待 Temporal 轮询完成。默认保留临时项目；只有确认允许删除时才设置 `V5_SMOKE_GITLAB_CLEANUP=yes`。

## 故障表

| doctor 项 | 排查 |
| --- | --- |
| DB / Flyway | PostgreSQL 容器、`V5_DB_*`、control-plane 迁移日志 |
| Temporal | 7233 端口、Temporal 容器、`asterism` task queue poller |
| control-plane | `/healthz`、Worker Token、数据库连接 |
| agent-service | `/healthz`、`product` Profile 或 `V5_AGENT_*` 环境回落 |
| Worker | task queue、repo 挂载、`developer` Profile、Claude SDK 依赖 |
| GitLab | Worker 出站路由、`V5_NO_PROXY`、GitLab 地址、Token 和项目权限 |

macOS Apple Container 可在当前 shell 设置 `V5_CONTAINER_RUNTIME=apple`，再运行同一组 Make 目标。Apple Container 的 `make prod-up` 只替换无状态应用容器，保留 PostgreSQL volume 与 Temporal 容器；需要清除测试 history 时显式运行 `prod-reset`。

## DeepSeek 与 Claude SDK

DeepSeek 的 Anthropic 兼容接口可用于 `developer`：创建 `anthropic` Profile，`baseUrl` 填 `https://api.deepseek.com/anthropic`，模型填写 DeepSeek 官方当前支持的 Claude Code 模型名，Agent engine 选择 `claude_sdk_team`。Worker 按 Claude Code 约定注入 `ANTHROPIC_AUTH_TOKEN`，密钥不会进入 Temporal payload、前端响应或 transcript。
