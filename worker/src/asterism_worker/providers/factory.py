from asterism_worker.agent_config import ResolvedAgentConfig
from asterism_worker.providers.base import ExecutionProvider
from asterism_worker.providers.claude_sdk_team import ClaudeSdkTeamProvider
from asterism_worker.providers.fake import FakeExecutionProvider


def build_execution_provider(resolved: ResolvedAgentConfig) -> ExecutionProvider:
    """终态执行入口只保留测试基线与 Claude SDK Supervisor。"""

    selected = resolved.engine.name
    if selected == "fake":
        return FakeExecutionProvider()
    if selected == "claude_sdk_team":
        return ClaudeSdkTeamProvider(
            resolved.model_profile,
            resolved.engine,
            resolved.artifacts_root,
            resolved.constraints,
            resolved.callbacks,
        )
    raise ValueError(f"unsupported execution provider: {selected}")
