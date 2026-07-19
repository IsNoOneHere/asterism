# Asterism 内网服务器部署评估与工作清单

评估日期：2026-07-15
目标服务器：`10.96.230.211`
结论：**有条件可部署**。硬件资源充足，需先完成发布冻结、容器生产化和数据迁移决策。

## 已验证现状

- Ubuntu 22.04 x86_64，88 核、125 GiB 内存、根盘约 7.7 TiB 可用。
- Docker 29.1.3、Docker Compose 5.3.1、Git、curl 已安装。
- 服务器已有 SurveyKing、Seafile 等 10 个容器，80/443 由 Seafile Caddy 占用；8080、8085、8090、7233、8233、55432 当前未占用。
- `sysadmin` 可使用 sudo，但不在 docker 组中，部署命令需使用 sudo。
- DeepSeek、Maven Central、npm、PyPI 可访问。
- GitHub 当前 DNS 解析失败；Docker Hub 直连超时。Docker 已配置多个镜像加速地址，其中部分可达，但正式部署前仍需做一次实际镜像拉取验证。
- 本地运行环境是 Apple Container arm64，服务器是 x86_64，不能直接复制本地镜像到服务器。
- 当前本地数据包括：7 个系统、10 个用户、37 个工作项、467 条已批准知识、1 个附件。
- 当前分支为 `bug/20260713-160143`，HEAD 为 `fb80d49`，代理修复和知识库分页仍在工作区中，尚未形成可发布版本。

## 推荐首期部署形态

- 应用目录：`/opt/asterism`，只保存 Compose、发布代码和 `.env`。
- Agent 可操作仓库目录：`/srv/asterism/repos`，设置 `V5_REPO_ROOT=/srv/asterism/repos`。
- 首期入口：`http://10.96.230.211:8080`。
- 只向宿主机发布 Workbench 的 8080 端口；PostgreSQL、Temporal、control-plane、agent-service 只走 Compose 内部网络。Temporal UI 如需排障，仅绑定 `127.0.0.1`。
- 暂不改动现有 Seafile Caddy。需要域名和 TLS 时，再把 Workbench 接入现有 Caddy 的 `seafile-net`，并配置内网 DNS。
- 使用全新的生产 PostgreSQL；重建系统和管理员账号，重新录入生产模型密钥，只迁移已批准知识。不要直接整库迁移本地开发数据。

## 上线前必须处理

- Temporal 当前使用 `start-dev`，数据库文件位于容器 `/tmp`，重建容器会丢失生命周期状态。首期至少改为固定版本并挂载持久化目录；正式长期运行应迁移到 PostgreSQL 持久化的 Temporal。
- 当前 Compose 会把 PostgreSQL、Temporal、control-plane 和 agent-service 端口发布到所有网卡。服务器版 Compose 需收紧端口。
- 当前 Compose 没有健康检查和重启策略。需补 `healthcheck`、就绪依赖和 `restart: unless-stopped`。
- `.env` 必须生成独立的数据库密码、Worker 回调 Token 和管理员初始密码，权限设为 `600`，不得提交 Git。
- Model Profile 的 API Key 存储在数据库配置中，数据库备份必须按敏感文件加密和限制访问。
- 必须设置专用 `V5_REPO_ROOT`，不能沿用示例中的 `/tmp`。所有系统的 `repoPath` 必须改成服务器可见的绝对路径。
- 当前发布版本未冻结。先提交代理修复、知识库分页和测试，再基于明确 commit/tag 发布。
- GitHub 不可达。长期方案是修复 DNS/代理或接入内网 Git 镜像；首期可用 `scp` 传输带 commit 校验值的发布包。
- 部署前实际验证所需基础镜像均能通过镜像加速拉取；不能拉取时，从可联网的 x86_64 环境导出镜像后在服务器 `docker load`。

## 数据迁移建议

推荐采用选择性迁移：

1. 生产库首次启动，创建新的管理员和系统。
2. 将系统的 `repoPath` 改成 `/srv/asterism/repos/<repo>`。
3. 在生产页面重新录入 Model Profile 和 Agent Role，避免把开发环境密钥、用户和历史工作项直接带入生产。
4. 按系统映射导入本地 467 条 `approved` 知识，不导入候选、会话、领域事件和开发工作项。
5. 检查本地 1 个附件是否仍被知识或 PRD 引用；确有引用时再迁移附件文件和元数据。

## 分阶段工作清单

### 1. 发布冻结

- [ ] 确认代理修复与知识库分页是本次上线范围。
- [ ] 完成前端测试、Python 测试和 Workbench 构建；按项目约定不做 Maven 检查。
- [ ] 提交当前未提交文件，记录发布 commit，建议创建版本 tag。
- [ ] 生成发布包和 SHA-256 校验值。

### 2. Compose 生产化

- [ ] 固定 Temporal 镜像版本，增加 Temporal 数据卷。
- [ ] PostgreSQL、Temporal、control-plane、agent-service 取消公网卡端口发布。
- [ ] Workbench 保留 `8080:80`，作为首期唯一入口。
- [ ] 为核心服务增加健康检查、就绪依赖和自动重启策略。
- [ ] 设置 `COMPOSE_PROJECT_NAME=asterism`，避免影响现有 Compose 项目。
- [ ] 设置 `V5_REPO_ROOT=/srv/asterism/repos`。
- [ ] 核对 Worker 在宿主仓库中产生文件的属主，避免生成 root 属主文件。

### 3. 服务器准备

- [ ] 创建 `/opt/asterism` 和 `/srv/asterism/repos`，设置最小权限。
- [ ] 使用 sudo 执行 Docker 命令，不把普通账号直接加入 docker 组。
- [ ] 验证全部基础镜像可通过镜像加速拉取。
- [ ] 决定源码交付方式：修复 GitHub 访问，或使用带校验值的 `scp` 发布包。
- [ ] 将目标业务仓库放到 `/srv/asterism/repos`，验证 Worker 容器可读写且识别为 Git 仓库。

### 4. 生产配置

- [ ] 从 `.env.example` 创建服务器 `.env`，权限设置为 `600`。
- [ ] 生成随机 `V5_DB_PASSWORD`、`V5_WORKER_CALLBACK_TOKEN` 和 `V5_ADMIN_INITIAL_PASSWORD`。
- [ ] 配置 `product` 与 `developer`；PRD 对话使用 OpenAI 兼容 Profile，`developer` 使用 Anthropic 兼容 Profile 和 `claude_sdk_team`。
- [ ] 保持 `V5_RELEASE_PUSH=false`，首期只在服务器本地生成提交。
- [ ] 验证 DeepSeek OpenAI 兼容接口和 Anthropic 兼容接口均可从容器访问。

### 5. 数据迁移

- [ ] 备份本地数据库和附件目录，记录备份校验值。
- [ ] 在新生产库创建系统，建立本地 system ID 到生产 system ID 的映射。
- [ ] 导入 467 条已批准知识并核对总数、状态和系统归属。
- [ ] 按需迁移被引用的附件，不迁移开发用户、会话和工作项。
- [ ] 在服务器重新录入模型密钥，不把密钥写进脚本、日志或发布包。

### 6. 部署与验收

- [ ] 在服务器构建镜像并执行 `docker compose --profile prod up -d`。
- [ ] 检查所有容器状态和启动日志，确认 Flyway 迁移成功。
- [ ] 执行 `make doctor`，确认数据库、Temporal、control-plane、agent-service 和 Worker poller 正常。
- [ ] 验证 `http://10.96.230.211:8080` 可登录，模型配置和 Agent 配置可读取。
- [ ] 发起一次真实需求沟通，确认 PRD 模型调用成功。
- [ ] 发起一个最小工作项，确认 Coding Supervisor、仓库子 Agent、Git 提交和结果回传完整。
- [ ] 核对知识库分页每页 10 条，并确认 467 条知识可检索。

### 7. 备份与回滚

- [ ] 配置 PostgreSQL 定时备份、附件备份和 Temporal 数据备份，备份文件加密保存。
- [ ] 保留上一个发布包、镜像 tag、Compose 文件和 `.env` 备份。
- [ ] 回滚演练：停止新版本、恢复旧镜像和数据库备份、重新执行 doctor。
- [ ] 记录恢复目标：数据库可恢复时间、允许丢失的数据窗口和负责人。

### 8. 二期接入

- [ ] 确认内网域名后，将 Workbench 接入现有 Caddy 和 `seafile-net`。
- [ ] 配置 TLS，并关闭对外的 8080 端口。
- [ ] 修复服务器 GitHub 访问或接入内网 Git 镜像，形成可重复的发布流程。
- [ ] 将 Temporal 从持久化的 `start-dev` 升级为 PostgreSQL 后端的正式部署。
