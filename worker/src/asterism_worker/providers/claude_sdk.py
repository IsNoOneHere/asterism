import json
import logging
import os
import shutil
import stat
import subprocess
from collections.abc import AsyncIterator
from pathlib import Path
from typing import Any

from claude_agent_sdk import AssistantMessage, ClaudeAgentOptions, ResultMessage, TextBlock, ToolUseBlock, query

from asterism_worker.agent_config import EngineConfig, ModelProfile
from asterism_worker.contracts import ExecutionRequest, ExecutionResult
from asterism_worker.providers.base import ExecutionProvider

log = logging.getLogger(__name__)
CLAUDE_TOOLS = ["Read", "Edit", "Write", "Glob", "Grep"]
CLAUDE_DISALLOWED_TOOLS = ["Bash", "WebSearch"]
CLAUDE_UID = 65534
CLAUDE_GID = 65534


class ClaudeSdkExecutionProvider(ExecutionProvider):
    """Claude Agent SDK 只负责在隔离副本内改码并产出 diff。"""

    def __init__(
        self,
        model_profile: ModelProfile,
        engine_options: EngineConfig,
        artifacts_root: str,
        callbacks: dict[str, Any] | None = None,
    ) -> None:
        if not model_profile.api_key:
            raise RuntimeError("claude_sdk 缺少模型 Profile API key")
        callbacks = callbacks or {}
        self.model_profile = model_profile
        model = model_profile.model.strip()
        self.model_env = {key: model for key in (
            "ANTHROPIC_MODEL", "ANTHROPIC_DEFAULT_OPUS_MODEL", "ANTHROPIC_DEFAULT_SONNET_MODEL",
            "ANTHROPIC_DEFAULT_HAIKU_MODEL", "CLAUDE_CODE_SUBAGENT_MODEL",
        ) if model}
        if engine_options.effort_level:
            self.model_env["CLAUDE_CODE_EFFORT_LEVEL"] = engine_options.effort_level
        self.max_turns = engine_options.max_turns
        self.artifacts_root = Path(artifacts_root)
        self.event_callback = callbacks.get("event")
        self.query = callbacks.get("query") or query

    async def run(self, request: ExecutionRequest) -> ExecutionResult:
        workspace = Path(request.repo_path)
        if not (workspace / ".git").exists():
            raise RuntimeError("claude_sdk workspace 必须是 git 仓库")
        runtime_dir = workspace.parent / ".claude-runtime"
        runtime_dir.mkdir(parents=True, exist_ok=True)
        for name in ("home", "config", "cache", "data", "state"):
            (runtime_dir / name).mkdir(exist_ok=True)
        settings_path = runtime_dir / "settings.json"
        settings_path.write_text("{}\n", encoding="utf-8")
        transcript = self._transcript_path(request)
        transcript.write_text("", encoding="utf-8")

        # 仓库自带的 hooks/settings 不参与 SDK 会话，只保留 CLAUDE.md 项目上下文。
        project_settings = workspace / ".claude"
        saved_project_settings = runtime_dir / "repository-claude-settings"
        if project_settings.exists():
            project_settings.rename(saved_project_settings)
        context_path = workspace / "CLAUDE.md"
        original_context = context_path.read_bytes() if context_path.exists() else None
        context_path.write_text(self._context(request, original_context), encoding="utf-8")
        sdk_user = self._prepare_sdk_user(workspace)
        summary = "Claude SDK execution completed"
        turns = 0
        token_usage: dict[str, Any] = {}
        result_error = ""
        try:
            options = ClaudeAgentOptions(
                tools=CLAUDE_TOOLS,
                allowed_tools=CLAUDE_TOOLS,
                disallowed_tools=CLAUDE_DISALLOWED_TOOLS,
                permission_mode="bypassPermissions",
                max_turns=self.max_turns,
                cwd=workspace,
                settings=str(settings_path),
                setting_sources=["project"],
                strict_mcp_config=True,
                skills=[],
                env=self._sdk_env(runtime_dir),
                user=sdk_user,
            )
            async for message in self.query(prompt=self._prompt(request), options=options):
                turns += int(isinstance(message, AssistantMessage))
                self._heartbeat(message)
                if isinstance(message, AssistantMessage):
                    for block in message.content:
                        if isinstance(block, TextBlock) and block.text.strip():
                            summary = block.text.strip()
                        elif isinstance(block, ToolUseBlock):
                            self._write(transcript, {
                                "type": "tool_use",
                                "tool": block.name,
                                "target": self._tool_target(block.input),
                            })
                elif isinstance(message, ResultMessage):
                    summary = message.result or summary
                    turns = message.num_turns
                    token_usage = dict(message.usage or {})
                    if message.is_error:
                        detail = "; ".join(message.errors or [])
                        result_error = f"{message.subtype}: {detail}".rstrip(": ")
        finally:
            if original_context is None:
                context_path.unlink(missing_ok=True)
            else:
                context_path.write_bytes(original_context)
            if project_settings.exists():
                if project_settings.is_dir():
                    shutil.rmtree(project_settings)
                else:
                    project_settings.unlink()
            if saved_project_settings.exists():
                saved_project_settings.rename(project_settings)
            if sdk_user is not None:
                self._set_tree_owner(workspace.parent, 0, 0)

        self._write(transcript, {
            "type": "result",
            "summary": summary,
            "turns": turns,
            "tokenUsage": token_usage,
        })
        if result_error:
            raise RuntimeError(f"Claude SDK execution failed: {result_error}")

        diff_patch = self._git_diff(workspace)
        log.info("Claude SDK 执行完成", extra={
            "work_item_id": request.work_item_id,
            "turns": turns,
            "diff_bytes": len(diff_patch.encode()),
        })
        return ExecutionResult(
            summary=summary,
            diff_patch=diff_patch,
            execution_provider="claude_sdk",
            turns=turns,
            token_usage=token_usage,
        )

    def _transcript_path(self, request: ExecutionRequest) -> Path:
        # 工作项 ID 来自控制面，仍压平路径分隔符，避免审计文件越界。
        safe_id = request.work_item_id.replace("/", "_").replace("\\", "_")
        safe_role = request.role_id.replace("/", "_").replace("\\", "_")
        suffix = f"-{request.assignment_index}-{safe_role}-claude_sdk" if safe_role else ""
        path = self.artifacts_root / safe_id / f"agent-transcript{suffix}.jsonl"
        path.parent.mkdir(parents=True, exist_ok=True)
        return path

    def _write(self, path: Path, record: dict[str, Any]) -> None:
        with path.open("a", encoding="utf-8") as stream:
            stream.write(json.dumps(record, ensure_ascii=False) + "\n")

    def _heartbeat(self, message: object) -> None:
        if self.event_callback:
            self.event_callback({"type": type(message).__name__})

    def _sdk_env(self, runtime_dir: Path) -> dict[str, str]:
        env = {
            "CLAUDE_CONFIG_DIR": str(runtime_dir),
            "CLAUDE_AGENT_SDK_CLIENT_APP": "asterism",
            "HOME": str(runtime_dir / "home"),
            "XDG_CONFIG_HOME": str(runtime_dir / "config"),
            "XDG_CACHE_HOME": str(runtime_dir / "cache"),
            "XDG_DATA_HOME": str(runtime_dir / "data"),
            "XDG_STATE_HOME": str(runtime_dir / "state"),
            **self.model_env,
        }
        # Claude Code 自定义端点使用 AUTH_TOKEN，官方 Anthropic 默认端点继续使用 API_KEY。
        if self.model_profile.base_url:
            env["ANTHROPIC_BASE_URL"] = self.model_profile.base_url
            env["ANTHROPIC_AUTH_TOKEN"] = self.model_profile.api_key
        else:
            env["ANTHROPIC_API_KEY"] = self.model_profile.api_key
        return env

    def _prepare_sdk_user(self, workspace: Path) -> int | None:
        if os.geteuid() != 0:
            return None
        # worker 保持 root，仅把单次 case 的隔离根交给 Debian nobody。
        self._set_tree_owner(workspace.parent, CLAUDE_UID, CLAUDE_GID, writable=True)
        log.info("Claude SDK 切换低权限用户", extra={"uid": CLAUDE_UID})
        return CLAUDE_UID

    def _set_tree_owner(self, root: Path, uid: int, gid: int, writable: bool = False) -> None:
        for path in (root, *root.rglob("*")):
            os.chown(path, uid, gid, follow_symlinks=False)
            if not writable or path.is_symlink():
                continue
            mode = path.stat().st_mode
            required = stat.S_IRUSR | stat.S_IWUSR | (stat.S_IXUSR if path.is_dir() else 0)
            path.chmod(mode | required)

    def _git_diff(self, workspace: Path) -> str:
        subprocess.run(["git", "add", "-N", "."], cwd=workspace, check=True, capture_output=True, text=True)
        diff_patch = subprocess.run(
            ["git", "diff", "--no-ext-diff", "--binary"],
            cwd=workspace,
            check=True,
            capture_output=True,
            text=True,
        ).stdout
        # activity 还要在同一隔离克隆执行 git apply --check，先恢复基线。
        subprocess.run(["git", "reset", "--hard", "HEAD"], cwd=workspace, check=True, capture_output=True, text=True)
        subprocess.run(["git", "clean", "-fd"], cwd=workspace, check=True, capture_output=True, text=True)
        return diff_patch

    def _context(self, request: ExecutionRequest, original: bytes | None) -> str:
        memories = "\n".join(
            f"- {item.get('content', '')}" for item in request.memories if item.get("content")
        ) or "- 无"
        existing = original.decode(errors="ignore") if original else ""
        return (
            "# Asterism 临时执行上下文\n\n"
            "本文件由 worker 生成，只对本次隔离工作区会话有效。生命周期、权限、patch、验证和发布由外部系统负责。\n\n"
            f"Context manifest: {request.context_manifest_id or 'none'}\n\n"
            "## 路径约束\n"
            f"- 允许路径: {self._paths(request.allowed_paths)}\n"
            f"- 禁止路径: {self._paths(request.forbidden_paths)}\n\n"
            "## 已批准记忆\n"
            f"{memories}\n"
            + (f"\n## 仓库原有 CLAUDE.md\n{existing}\n" if existing else "")
        )

    def _prompt(self, request: ExecutionRequest) -> str:
        criteria = "\n".join(f"- {item}" for item in request.acceptance_criteria) or "- 无"
        steps = "\n".join(f"- {item}" for item in request.plan.steps) or "- 无"
        return (
            "请在当前隔离工作区自主读取并编辑文件，完成目标。不要提交 git commit；完成后给出简短摘要。\n\n"
            f"目标:\n{request.goal}\n\n"
            f"验收标准:\n{criteria}\n\n"
            f"计划步骤:\n{steps}\n\n"
            f"本阶段步骤引用: {request.step_refs or ['全部']}\n"
            f"允许路径: {self._paths(request.allowed_paths)}\n"
            f"禁止路径: {self._paths(request.forbidden_paths)}\n"
            f"角色补充约束: {request.role_prompt or '无'}\n"
            f"前序交接摘要: {request.handoff_summary or '无'}\n"
        )

    def _paths(self, paths: list[str]) -> str:
        return ", ".join(paths) if paths else "未限制"

    def _tool_target(self, tool_input: dict[str, Any]) -> str:
        for key in ("file_path", "path", "pattern"):
            if tool_input.get(key):
                return str(tool_input[key])
        return ""
