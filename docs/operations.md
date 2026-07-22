# 运维

## 启动

```bash
./asterism install
```

首次安装执行 `./asterism install`。安装器会在 `.asterism/env` 生成数据库密码、内部 Token 和管理员初始密码，文件权限固定为 `0600`；重复执行不会覆盖已有配置和数据。生产不创建演示用户、演示系统或固定默认密码。

唯一用户入口是 `http://127.0.0.1:8080`。Server 容器内部使用 `8085`，Runner 内部使用 `8090`；Runner 端口不发布到宿主机。Temporal UI `8233`、Temporal gRPC `7233` 和 PostgreSQL `55432` 只绑定回环地址用于排障。

基础运维命令：

```bash
./asterism up                  # 幂等启动，不清数据
./asterism down                # 停止但保留容器与卷
./asterism doctor basic        # 首次安装基础检查
./asterism doctor full         # 包含模型与 GitLab 检查
./asterism upgrade             # 只替换 server/runner
./asterism backup              # 备份四类持久数据
./asterism restore <备份目录>  # 显式恢复
```

## 常用命令

```bash
./asterism doctor full
make smoke-real
make smoke-gitlab
CONFIRM=yes make prod-reset
make test-python
make test-web
```

`make smoke-real` 需要 `V5_AGENT_API_KEY`、`V5_MODEL_API_KEY` 和 `V5_SMOKE_ADMIN_PASSWORD`，缺失会明确失败，不会退回 fake。Java 验证使用 `make test-java`；该目标需要 Docker/Testcontainers。

`./asterism backup` 会短暂停止写入端以生成 PostgreSQL、Temporal、附件和 artifacts 的一致快照；Apple Container 因卷不能多挂载，会重建三个非数据库容器，期间有短暂不可用。备份含模型密钥等敏感数据库内容，目录权限为 `0700`，仍应放入加密存储。恢复是显式覆盖操作，执行前先保留当前备份。

Temporal history 是正式持久数据。普通升级只执行 `./asterism upgrade` 替换 Server 与 Runner；Workflow 不兼容变更必须使用 Worker Versioning 或 `workflow.patched` 迁移。`prod-reset` 只允许用于明确可丢弃的本地测试环境，不能作为升级手段。

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
2. 设置 `ASTERISM_GITLAB_BASE_URL`、`ASTERISM_GITLAB_TOKEN` 和用户可访问的 `V5_PUBLIC_URL`，然后执行 `./asterism upgrade` 重建 Server 与 Runner。也可在系统“Git 与发布”配置中覆盖连接，读取接口只返回 `tokenSet`。
3. 系统配置选择 `releaseMode=gitlab`，逐仓填写 GitLab project、默认分支、路径门禁和测试命令。`validationMode=auto` 在 push 前运行测试，`skip` 把测试留给 MR CI 与人工。
4. 运行 `./asterism doctor full`，确认 Runner 镜像含 Git、GitLab API 可达且系统 readiness 通过。

合并状态由 Temporal Workflow 每 60 秒轮询 GitLab。网络只需允许 Asterism 到 GitLab；部分仓已合并时继续等待，全部合并后完成，MR 未合并而关闭时转为阻塞。

`make smoke-gitlab` 会创建临时 GitLab 项目并跑到 MR 合并，再等待 Temporal 轮询完成。默认保留临时项目；只有确认允许删除时才设置 `V5_SMOKE_GITLAB_CLEANUP=yes`。

## 故障表

| doctor 项 | 排查 |
| --- | --- |
| DB / Flyway | PostgreSQL 容器、`V5_DB_*`、Server 迁移日志 |
| Temporal | 7233 端口、Temporal 容器、`asterism` task queue poller |
| server | `/healthz`、Workbench 首页、Worker Token、数据库连接 |
| runner | 内部 `/healthz`、`product` Profile 或 `V5_AGENT_*` 环境回落、Temporal poller |
| Worker | task queue、repo 挂载、`developer` Profile、Claude SDK 依赖 |
| GitLab | Worker 出站路由、`V5_NO_PROXY`、GitLab 地址、Token 和项目权限 |

macOS Apple Container 通过 `V5_CONTAINER_RUNTIME=apple ./asterism install` 使用同一四服务拓扑。安装器会继承旧六服务环境的现有密钥，并把旧 Temporal 容器内的 SQLite history 一次性迁入持久卷；迁移前归档保存在 `.asterism/backups/legacy-temporal-pre-four.tgz`。后续 `up/upgrade` 只替换无状态 server 与 runner。需要清除测试 history 时仍必须显式运行破坏性重置命令。

## 发布镜像

推送 `v*` tag 后，GitHub Actions 会构建 `linux/amd64`、`linux/arm64` 的 `asterism-server` 和 `asterism-runner` 固定版本镜像。发布任务最后会匿名读取两个多架构 manifest；GHCR package 未设为 Public、镜像缺少任一架构时都会失败。部署配置只使用版本号，不使用 `latest`。

## DeepSeek 与 Claude SDK

DeepSeek 的 Anthropic 兼容接口可用于 `developer`：创建 `anthropic` Profile，`baseUrl` 填 `https://api.deepseek.com/anthropic`，模型填写 DeepSeek 官方当前支持的 Claude Code 模型名，Agent engine 选择 `claude_sdk_team`。Worker 按 Claude Code 约定注入 `ANTHROPIC_AUTH_TOKEN`，密钥不会进入 Temporal payload、前端响应或 transcript。
