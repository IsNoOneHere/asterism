# 运维

## 启动

```bash
cp .env.example .env
make prod-up
make doctor
```

`.env` 至少设置随机 `V5_WORKER_CALLBACK_TOKEN` 和 `V5_DB_PASSWORD`。可设置 `V5_ADMIN_INITIAL_PASSWORD`；留空时 control-plane 只在首次创建 admin 时打印随机密码。生产不创建演示用户、演示系统或固定默认密码。

入口：Workbench `http://127.0.0.1:8080`，control-plane `:8085`，agent-service `:8090`，Temporal UI `:8233`。

## 常用命令

```bash
make doctor
make smoke-real
make smoke-gitlab
CONFIRM=yes make prod-reset
make test-python
make test-web
```

`make smoke-real` 需要 `V5_AGENT_API_KEY` 和 `V5_SMOKE_ADMIN_PASSWORD`，缺失会明确失败，不会退回 fake。Java 验证使用 `make test-java`；该目标需要 Docker/Testcontainers。

## Release 语义

`local` 模式保持原行为，只在本地仓库创建并提交 `wi/<workItemId>` 分支。`gitlab` 模式会为每个仓库推送同名分支并创建或复用 MR，全部 MR 合并后工作项才完成。合并后的 CI/CD、部署和服务重启始终由 GitLab Runner 负责，不属于 Asterism。

## GitLab 集成部署

1. 在 GitLab B 创建专用的 Project/Group Access Token，限定到业务项目或组，角色至少为 Developer。单 token 同时执行 clone、push 和 MR REST API 时使用 `api` scope；不要把 token 写进仓库 URL。
2. 在服务器 A 设置 `ASTERISM_GITLAB_BASE_URL`、`ASTERISM_GITLAB_TOKEN` 和用户可访问的 `V5_PUBLIC_URL`，然后重建 control-plane 与 worker。也可在系统“Git 与发布”配置中覆盖连接，读取接口只返回 `tokenSet`。
3. 系统配置选择 `releaseMode=gitlab`，逐仓填写 GitLab project、默认分支、路径门禁和测试命令。`validationMode=auto` 在 push 前运行测试，`skip` 把测试留给 MR CI 与人工。
4. 运行 `make doctor`：它会确认 worker 镜像含 Git，并从 worker 容器检查 GitLab API 的 A→B 可达性；系统 readiness 继续核验 token 与每个 project。

合并状态由服务器 A 上的 Temporal workflow 每 60 秒轮询 GitLab B。网络只需允许 `A → B`，不要求 `B → A`，不配置 webhook。部分仓已合并时继续等待；全部合并后完成；MR 未合并而关闭时转为阻塞。若 A 使用出站代理，把 GitLab B 的主机或 IP 加入 `V5_NO_PROXY`。

`make smoke-gitlab` 需要真实 GitLab、模型和管理员环境变量；缺失时会明确输出 `SKIP`。它会创建临时 GitLab 项目并跑到 MR 合并，再等待 Temporal 轮询把工作项推进为 completed。默认保留临时项目；只有在已确认允许删除时才设置 `V5_SMOKE_GITLAB_CLEANUP=yes`。

## 故障表

| doctor 项 | 排查 |
| --- | --- |
| DB / Flyway | PostgreSQL 容器、`V5_DB_*`、control-plane 迁移日志 |
| Temporal | 7233 端口、Temporal 容器、task queue poller |
| control-plane | `/healthz`、worker token、数据库连接 |
| agent-service | `/healthz`、系统 Profile 或 `V5_AGENT_*` 旧配置回落 |
| worker | task queue、repo 挂载、role Profile 完整性 |
| GitLab A→B | worker 容器出站路由、`V5_NO_PROXY`、GitLab 地址和防火墙 |

macOS Apple Container 可设置 `V5_CONTAINER_RUNTIME=apple`，再运行同一组 Make 目标。出站代理使用 `.env.example` 中的显式变量，示例不包含真实 IP 或内网端点。

## DeepSeek 与 Claude SDK

DeepSeek 官方提供 Anthropic 兼容接口。给 `claude_sdk` 角色选择 `anthropic` Profile，`baseUrl` 填 `https://api.deepseek.com/anthropic`，模型可填 `deepseek-v4-pro[1m]`，API key 仍使用同一 DeepSeek key。worker 会按 Claude Code 约定注入 `ANTHROPIC_AUTH_TOKEN`，密钥不会进入 Temporal payload、前端响应或 transcript。详见 [DeepSeek Anthropic API](https://api-docs.deepseek.com/guides/anthropic_api)。
