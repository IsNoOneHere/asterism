import base64
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
    supports_vision: bool = False


class LlmClient(Protocol):
    def complete(self, prompt: str, config: ModelConfig, json_mode: bool = False) -> str:
        ...

    def complete_vision(self, prompt: str, image: bytes, content_type: str, config: ModelConfig) -> str:
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
        # ProductAgent 使用结构化 JSON 输出，代码执行不经过 agent-service。
        if json_mode:
            request["response_format"] = {"type": "json_object"}
        response = client.chat.completions.create(**request)
        return response.choices[0].message.content or ""

    def complete_vision(self, prompt: str, image: bytes, content_type: str, config: ModelConfig) -> str:
        client = OpenAI(api_key=config.api_key, **({"base_url": config.base_url} if config.base_url else {}))
        data_url = f"data:{content_type};base64,{base64.b64encode(image).decode()}"
        response = client.chat.completions.create(
            model=config.model or self.settings.model,
            messages=[{"role": "user", "content": [
                {"type": "text", "text": prompt},
                {"type": "image_url", "image_url": {"url": data_url}},
            ]}],
            response_format={"type": "json_object"},
            temperature=0,
        )
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
