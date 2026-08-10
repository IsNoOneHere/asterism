FROM python:3.12-slim

WORKDIR /app/worker
RUN apt-get update && apt-get install -y --no-install-recommends git && rm -rf /var/lib/apt/lists/*
RUN pip install --no-cache-dir uv==0.11.3

# Runner 同时安装 agent-service 与 Worker，源码仍保持独立模块。
COPY worker/pyproject.toml worker/uv.lock ./
COPY agent-service/pyproject.toml /app/agent-service/pyproject.toml
COPY agent-service/src /app/agent-service/src
# Agent 源码变化时复用 uv 下载缓存，只重建虚拟环境层。
RUN --mount=type=cache,target=/root/.cache/uv uv sync --frozen --no-dev --no-install-project

COPY worker/src ./src

ENV PATH="/app/worker/.venv/bin:$PATH" \
    PYTHONPATH="/app/worker/src:/app/agent-service/src"

EXPOSE 8090
CMD ["python", "-m", "asterism_worker", "runner"]
