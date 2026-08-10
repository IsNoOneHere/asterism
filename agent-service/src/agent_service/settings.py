from pydantic import Field
from pydantic_settings import BaseSettings, SettingsConfigDict


class AgentSettings(BaseSettings):
    model: str = Field(default="gpt-4.1-mini")
    base_url: str = Field(default="")
    api_key: str = Field(default="")
    control_plane_url: str = Field(default="http://127.0.0.1:8085")
    worker_callback_token: str = Field(default="dev-worker-token")
    model_request_timeout_seconds: int = Field(default=600)

    model_config = SettingsConfigDict(env_prefix="V5_AGENT_", env_file=".env")
