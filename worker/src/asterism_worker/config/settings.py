import socket

from pydantic import AliasChoices, Field
from pydantic import model_validator
from pydantic_settings import BaseSettings, SettingsConfigDict


class Settings(BaseSettings):
    """集中读取 worker 配置，避免到处散取环境变量。"""

    temporal_target: str = Field(default="127.0.0.1:7233")
    temporal_namespace: str = Field(default="default")
    temporal_task_queue: str = Field(default="asterism")
    control_plane_url: str = Field(default="http://127.0.0.1:8085")
    worker_callback_token: str = Field(default="dev-worker-token")
    profile: str = Field(default="local")
    workspace_root: str = Field(default="runtime/workspaces")
    artifacts_root: str = Field(default="runtime/artifacts")
    default_engine: str = Field(default="fake", validation_alias=AliasChoices("V5_EXECUTION_ENGINE", "V5_EXECUTION_PROVIDER"))
    execution_http_endpoint: str = Field(default="http://127.0.0.1:8090/execute")
    default_model_provider: str = Field(default="anthropic", validation_alias=AliasChoices("V5_MODEL_PROVIDER", "V5_AGENT_PROVIDER"))
    default_model: str = Field(default="", validation_alias=AliasChoices("V5_MODEL", "V5_ANTHROPIC_MODEL", "V5_AGENT_MODEL"))
    default_model_base_url: str = Field(default="", validation_alias=AliasChoices("V5_MODEL_BASE_URL", "V5_ANTHROPIC_BASE_URL", "V5_AGENT_BASE_URL"))
    default_model_api_key: str = Field(default="", validation_alias=AliasChoices("V5_MODEL_API_KEY", "V5_ANTHROPIC_API_KEY", "V5_AGENT_API_KEY"))  # gitleaks:allow 这里只包含环境变量名
    engine_max_turns: int = Field(default=50, validation_alias=AliasChoices("V5_ENGINE_MAX_TURNS", "V5_CLAUDE_MAX_TURNS"))
    engine_timeout_seconds: int = Field(default=600, validation_alias=AliasChoices("V5_ENGINE_TIMEOUT_SECONDS", "V5_EXECUTION_TIMEOUT_SECONDS"))
    engine_effort_level: str = Field(default="", validation_alias=AliasChoices("V5_ENGINE_EFFORT_LEVEL", "V5_CLAUDE_CODE_EFFORT_LEVEL"))
    planner_provider: str = Field(default="fake")
    planner_http_endpoint: str = Field(default="http://127.0.0.1:8090/plan")
    agent_service_url: str = Field(default="http://127.0.0.1:8090")
    worker_id: str = Field(default_factory=socket.gethostname)
    readiness_interval_seconds: int = Field(default=30)
    validation_timeout_seconds: int = Field(default=120)
    release_push: bool = Field(default=False)

    model_config = SettingsConfigDict(env_prefix="V5_", env_file=".env", populate_by_name=True)

    @model_validator(mode="after")
    def reject_default_token_outside_local(self) -> "Settings":
        if self.worker_callback_token == "dev-worker-token" and self.profile not in {"local", "dev", "test"}:
            raise ValueError("非 local/dev profile 不能使用默认 worker token")
        return self


def load_settings() -> Settings:
    return Settings()
