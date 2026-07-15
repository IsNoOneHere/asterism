# Asterism

Asterism 是一个以 Temporal 为生命周期权威、支持多执行内核和多 Agent 顺序 handoff 的开源 AI 代码开发工作台。

> Asterism is an open-source AI coding workbench with a Temporal-controlled lifecycle, pluggable execution engines, and artifact-based multi-agent handoff.

```mermaid
flowchart LR
  UI[Workbench] --> CP[Control Plane]
  CP --> T[Temporal]
  T --> W[Worker]
  W --> P[Model Profile]
  W --> E{Engine}
  E --> C[Claude SDK]
  E --> D[Deep Agents]
  E --> H[HTTP]
  W --> G[Git branch + commit]
```

## Quickstart

要求 Docker Compose、Java 21/Maven 3.9（开发测试）、Python 3.12 和 Node.js 20。

```bash
cp .env.example .env
# 编辑 .env：至少设置随机 V5_WORKER_CALLBACK_TOKEN 和 V5_DB_PASSWORD
make prod-up
make doctor
```

打开 `http://127.0.0.1:8080`。若未设置初始密码，从 control-plane 首次启动日志读取随机 admin 密码；登录后立即修改。

## 配置模型

1. 在“系统配置”创建系统并设置仓库、允许路径和测试命令。
2. 在“Agent / 模型配置”的模型 Tab 创建 Model Profile；API Key 只写入，页面只显示 `apiKeySet`。
3. 在同一页面的 Agent Tab 创建 Agent Role，选择 `claude_sdk`、`deepagents`、`http` 或 `fake`，绑定 Profile 和 path scope。
4. 设置默认角色。Planner 可生成有序 assignments，多角色阶段以 `AgentStageCompleted` 展示。

旧 `businessModels` 和单模型 JSON 由 Flyway 一次性迁入 Model Profile，运行期不再维护第二套模型池。

## 截图反馈

业务用户手工路径：

1. 在“创建工作项”中发一句需求并粘贴或选择截图，每条消息最多三张。
2. 立即看到自己的消息和“正在分析…”占位，等待 AI 追问。
3. 在右侧草稿直接填写验收标准，无需再组织一段对话。
4. 在最新回复的页面卡片点击“是这个”或“不是”。
5. 草稿完整后在聊天区点击“确认 PRD”，等待修改、验证和发布结果。

管理员首次使用也只需三步：

1. 在 Agent / 模型配置中勾选一个支持图片理解的 Model Profile。
2. 在“系统知识”中运行路由索引。
3. 审批 worker 提取的页面、路由和接口 candidate；只有 approved 条目参与截图匹配。

## 文档

- [架构与三层配置](docs/architecture.md)
- [事件契约](docs/event-contract.md)
- [部署与故障处理](docs/operations.md)
- [路线图](docs/roadmap.md)
- [贡献指南](CONTRIBUTING.md)

## License

Apache License 2.0，见 [LICENSE](LICENSE)。
