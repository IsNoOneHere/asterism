import json
import logging
import subprocess
from pathlib import Path
from typing import Any

from asterism_worker.agent_config import EngineConfig, ModelProfile
from asterism_worker.contracts import ExecutionRequest, ExecutionResult
from asterism_worker.providers.base import ExecutionProvider

log = logging.getLogger(__name__)


class DeepAgentsExecutionProvider(ExecutionProvider):
    """Deep Agents 在隔离工作区改码，worker 仍统一收集和校验 git diff。"""

    def __init__(self, model_profile: ModelProfile, engine_options: EngineConfig,
                 artifacts_root: str, callbacks: dict[str, Any] | None = None) -> None:
        if not model_profile.api_key:
            raise RuntimeError("deepagents 缺少模型 Profile API key")
        self.profile = model_profile
        self.options = engine_options
        self.artifacts_root = Path(artifacts_root)
        self.callbacks = callbacks or {}

    async def run(self, request: ExecutionRequest) -> ExecutionResult:
        workspace = Path(request.repo_path)
        if not (workspace / ".git").exists():
            raise RuntimeError("deepagents workspace 必须是 git 仓库")
        runner = self.callbacks.get("runner") or self._run_agent
        output = await runner(request, workspace)
        summary = _summary(output)
        usage = _usage(output)
        diff_patch = _git_diff(workspace)
        self._write_transcript(request, summary, usage)
        log.info("Deep Agents 执行完成", extra={
            "work_item_id": request.work_item_id,
            "diff_bytes": len(diff_patch.encode()),
        })
        return ExecutionResult(
            summary=summary,
            diff_patch=diff_patch,
            execution_provider="deepagents",
            token_usage=usage,
        )

    async def _run_agent(self, request: ExecutionRequest, workspace: Path) -> Any:
        # 依赖延迟导入，fake/http 基线不需要加载 agentic SDK。
        from deepagents import create_deep_agent
        from deepagents.backends import FilesystemBackend
        from langchain_openai import ChatOpenAI

        model = ChatOpenAI(
            model=self.profile.model,
            api_key=self.profile.api_key,
            base_url=self.profile.base_url or None,
            temperature=0,
        )
        agent = create_deep_agent(
            model=model,
            backend=FilesystemBackend(root_dir=str(workspace)),
            system_prompt=(
                "只使用文件读写工具在当前仓库完成代码修改，不运行 shell，不提交 git。"
                f"允许路径: {request.allowed_paths or ['全部']}；禁止路径: {request.forbidden_paths or ['无']}。"
                f"角色约束: {request.role_prompt or '无'}"
            ),
        )
        prompt = (
            f"目标: {request.goal}\n验收标准: {request.acceptance_criteria}\n"
            f"计划: {request.plan.steps}\n本阶段步骤引用: {request.step_refs or ['全部']}\n"
            f"前序交接: {request.handoff_summary or '无'}"
        )
        return await agent.ainvoke(
            {"messages": [{"role": "user", "content": prompt}]},
            config={"recursion_limit": max(10, self.options.max_turns * 4)},
        )

    def _write_transcript(self, request: ExecutionRequest, summary: str, usage: dict[str, Any]) -> None:
        safe_id = request.work_item_id.replace("/", "_").replace("\\", "_")
        safe_role = request.role_id.replace("/", "_").replace("\\", "_") or "default"
        path = self.artifacts_root / safe_id / f"deepagents-transcript-{request.assignment_index}-{safe_role}.jsonl"
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text(json.dumps({"type": "result", "summary": summary, "tokenUsage": usage}, ensure_ascii=False) + "\n")


def _git_diff(workspace: Path) -> str:
    subprocess.run(["git", "add", "-N", "."], cwd=workspace, check=True, capture_output=True, text=True)
    diff_patch = subprocess.run(
        ["git", "diff", "--no-ext-diff", "--binary"], cwd=workspace,
        check=True, capture_output=True, text=True,
    ).stdout
    subprocess.run(["git", "reset", "--hard", "HEAD"], cwd=workspace, check=True, capture_output=True, text=True)
    subprocess.run(["git", "clean", "-fd"], cwd=workspace, check=True, capture_output=True, text=True)
    return diff_patch


def _summary(output: Any) -> str:
    if isinstance(output, dict):
        messages = output.get("messages") or []
        if messages:
            content = getattr(messages[-1], "content", "")
            return str(content or "Deep Agents execution completed")
    return "Deep Agents execution completed"


def _usage(output: Any) -> dict[str, Any]:
    return dict(output.get("usage", {})) if isinstance(output, dict) else {}
