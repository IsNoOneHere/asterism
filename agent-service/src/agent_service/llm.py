from typing import Protocol

from openai import OpenAI
from pydantic import BaseModel

from agent_service.settings import AgentSettings


class ModelConfig(BaseModel):
    managed: bool = False
    configured: bool = False
    model_id: str = ""
    name: str = ""
    provider: str = "openai"
    model: str = ""
    base_url: str = ""
    api_key: str = ""


class LlmClient(Protocol):
    def complete(self, prompt: str, config: ModelConfig, json_mode: bool = False) -> str:
        ...


class OpenAIChatClient:
    def __init__(self, settings: AgentSettings) -> None:
        self.settings = settings

    def complete(self, prompt: str, config: ModelConfig, json_mode: bool = False) -> str:
        kwargs = {"api_key": config.api_key}
        if config.base_url:
            kwargs["base_url"] = config.base_url
        # 每次请求按系统配置创建 client，避免不同系统 key 混用。
        client = OpenAI(**kwargs)
        request = {
            "model": config.model or self.settings.model,
            "messages": [{"role": "user", "content": prompt}],
            "temperature": 0,
        }
        # ProductAgent/Planner 使用模型 JSON Output，execute 仍返回纯 diff 文本。
        if json_mode:
            request["response_format"] = {"type": "json_object"}
        response = client.chat.completions.create(**request)
        return response.choices[0].message.content or ""


def default_model_config(settings: AgentSettings) -> ModelConfig:
    return ModelConfig(model=settings.model, base_url=settings.base_url, api_key=settings.api_key)


def merge_model_config(defaults: ModelConfig, override: ModelConfig) -> ModelConfig:
    if override.managed:
        return override
    return ModelConfig(
        provider=override.provider or defaults.provider,
        model=override.model or defaults.model,
        base_url=override.base_url or defaults.base_url,
        api_key=override.api_key or defaults.api_key,
    )
