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
CONFIRM=yes make prod-reset
make test-python
make test-web
```

`make smoke-real` 需要 `V5_AGENT_API_KEY` 和 `V5_SMOKE_ADMIN_PASSWORD`，缺失会明确失败，不会退回 fake。Java 验证使用 `make test-java`；该目标需要 Docker/Testcontainers。

## Release 语义

Release 只创建并提交 `wi/<workItemId>` 分支。PR、合并、CI/CD 和部署由仓库维护者决定。

## 故障表

| doctor 项 | 排查 |
| --- | --- |
| DB / Flyway | PostgreSQL 容器、`V5_DB_*`、control-plane 迁移日志 |
| Temporal | 7233 端口、Temporal 容器、task queue poller |
| control-plane | `/healthz`、worker token、数据库连接 |
| agent-service | `/healthz`、系统 Profile 或 `V5_AGENT_*` 旧配置回落 |
| worker | task queue、repo 挂载、role Profile 完整性 |

macOS Apple Container 可设置 `V5_CONTAINER_RUNTIME=apple`，再运行同一组 Make 目标。出站代理使用 `.env.example` 中的显式变量，示例不包含真实 IP 或内网端点。

## DeepSeek 与 Claude SDK

DeepSeek 官方提供 Anthropic 兼容接口。给 `claude_sdk` 角色选择 `anthropic` Profile，`baseUrl` 填 `https://api.deepseek.com/anthropic`，模型可填 `deepseek-v4-pro[1m]`，API key 仍使用同一 DeepSeek key。worker 会按 Claude Code 约定注入 `ANTHROPIC_AUTH_TOKEN`，密钥不会进入 Temporal payload、前端响应或 transcript。详见 [DeepSeek Anthropic API](https://api-docs.deepseek.com/guides/anthropic_api)。
