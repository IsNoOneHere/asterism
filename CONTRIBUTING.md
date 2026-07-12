# Contributing

## 开发环境

- Java 21、Maven 3.9、Docker（Java Testcontainers）
- Python 3.12
- Node.js 20 / npm

## 测试

```bash
make test-java
make test-python
make test-web
```

提交 PR 前保持 fake provider 测试基线，新增 provider 必须 mock 外部 API。禁止把 API Key 放进 Temporal payload、事件、日志、前端或 fixture。

PR 应只处理一个清晰问题，说明行为变化和验证命令；生命周期状态机、事件 sequence 或 workflow replay 行为的修改必须单独说明兼容策略。
