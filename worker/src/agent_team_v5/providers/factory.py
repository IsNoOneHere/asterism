from agent_team_v5.agent_config import ResolvedAgentConfig
from agent_team_v5.config.settings import Settings
from agent_team_v5.providers.base import ExecutionProvider
from agent_team_v5.providers.claude_sdk import ClaudeSdkExecutionProvider
from agent_team_v5.providers.deepagents import DeepAgentsExecutionProvider
from agent_team_v5.providers.fake import FakeExecutionProvider
from agent_team_v5.providers.http import HttpExecutionProvider, HttpPlannerProvider
from agent_team_v5.providers.planner import FakePlannerProvider, PlannerProvider


def build_execution_provider(resolved: ResolvedAgentConfig) -> ExecutionProvider:
    """单一已解析配置入口，factory 不再拼装模型密钥。"""

    selected = resolved.engine.name
    if selected == "http":
        return HttpExecutionProvider(resolved.engine.endpoint)
    if selected == "claude_sdk":
        return ClaudeSdkExecutionProvider(resolved.model_profile, resolved.engine, resolved.artifacts_root, resolved.callbacks)
    if selected == "deepagents":
        return DeepAgentsExecutionProvider(resolved.model_profile, resolved.engine, resolved.artifacts_root, resolved.callbacks)
    if selected == "fake":
        return FakeExecutionProvider()
    raise ValueError(f"unsupported execution provider: {selected}")


def build_planner_provider(settings: Settings) -> PlannerProvider:
    """按配置选择 planner provider，默认 fake 方便端到端验收。"""

    if settings.planner_provider == "http":
        return HttpPlannerProvider(settings.planner_http_endpoint)
    return FakePlannerProvider()
