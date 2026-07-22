import socket

from pydantic import Field
from pydantic import model_validator
from pydantic_settings import BaseSettings, SettingsConfigDict


class Settings(BaseSettings):
    """集中读取 worker 配置，避免到处散取环境变量。"""

    temporal_target: str = Field(default="127.0.0.1:7233")
    temporal_namespace: str = Field(default="default")
    temporal_task_queue: str = Field(default="asterism")
    control_plane_url: str = Field(default="http://127.0.0.1:8085")
    public_url: str = Field(default="")
    worker_callback_token: str = Field(default="dev-worker-token")
    profile: str = Field(default="local")
    workspace_root: str = Field(default="runtime/workspaces")
    artifacts_root: str = Field(default="runtime/artifacts")
    default_engine: str = Field(default="fake", validation_alias="V5_EXECUTION_ENGINE")
    default_model_provider: str = Field(default="anthropic", validation_alias="V5_MODEL_PROVIDER")
    default_model: str = Field(default="", validation_alias="V5_MODEL")
    default_model_base_url: str = Field(default="", validation_alias="V5_MODEL_BASE_URL")
    default_model_api_key: str = Field(default="", validation_alias="V5_MODEL_API_KEY")  # gitleaks:allow 这里只包含环境变量名
    engine_max_turns: int = Field(default=50, validation_alias="V5_ENGINE_MAX_TURNS")
    engine_timeout_seconds: int = Field(default=600, validation_alias="V5_ENGINE_TIMEOUT_SECONDS")
    # Claude SDK 的默认 1 MiB 仅适合小仓库；团队探索结果可能通过单条协议消息返回。
    claude_sdk_max_buffer_size: int = Field(default=16 * 1024 * 1024, validation_alias="V5_CLAUDE_SDK_MAX_BUFFER_SIZE")
    engine_effort_level: str = Field(default="", validation_alias="V5_ENGINE_EFFORT_LEVEL")
    agent_service_url: str = Field(default="http://127.0.0.1:8090")
    worker_id: str = Field(default_factory=socket.gethostname)
    readiness_interval_seconds: int = Field(default=30)
    activity_heartbeat_interval_seconds: int = Field(default=30)
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
