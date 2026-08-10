import base64
import logging
from dataclasses import dataclass
from typing import Any, Literal, Protocol

import httpx
from openai import APIConnectionError, APIError, APIStatusError, APITimeoutError, BadRequestError, OpenAI
from pydantic import BaseModel, model_validator

from agent_service.model_errors import ModelCallError, ModelErrorCode
from agent_service.settings import AgentSettings

log = logging.getLogger(__name__)

StructuredOutputMode = Literal["json_schema", "json_object", "prompt_only"]


class ModelConfig(BaseModel):
    managed: bool = False
    configured: bool = False
    model_id: str = ""
    name: str = ""
    provider: str = "openai-compat"
    model: str = ""
    base_url: str = ""
    api_key: str = ""
    image_input: bool = False
    structured_output: StructuredOutputMode = "json_object"
    supports_vision: bool = False

    @model_validator(mode="before")
    @classmethod
    def legacy_capabilities(cls, value: Any) -> Any:
        if not isinstance(value, dict):
            return value
        normalized = dict(value)
        # 老 Profile 只有 supports_vision，新字段存在时以新字段为准。
        if "image_input" not in normalized:
            normalized["image_input"] = bool(normalized.get("supports_vision", False))
        if "supports_vision" not in normalized:
            normalized["supports_vision"] = bool(normalized.get("image_input", False))
        if not normalized.get("structured_output"):
            normalized["structured_output"] = "json_object"
        return normalized


@dataclass(frozen=True)
class StructuredOutputFormat:
    mode: StructuredOutputMode
    name: str
    schema: dict[str, Any]


class ModelAdapter(Protocol):
    def complete(
        self,
        prompt: str,
        config: ModelConfig,
        output_format: StructuredOutputFormat | None = None,
        image: bytes | None = None,
        content_type: str = "",
        max_tokens: int | None = None,
    ) -> str:
        ...


class LlmClient(Protocol):
    def complete(
        self,
        prompt: str,
        config: ModelConfig,
        output_format: StructuredOutputFormat | None = None,
    ) -> str:
        ...

    def complete_vision(
        self,
        prompt: str,
        image: bytes,
        content_type: str,
        config: ModelConfig,
        output_format: StructuredOutputFormat | None = None,
    ) -> str:
        ...

    def test_connection(self, config: ModelConfig) -> None:
        ...


class OpenAICompatibleAdapter:
    def __init__(self, timeout_seconds: float = 600) -> None:
        self.timeout_seconds = timeout_seconds

    def complete(
        self,
        prompt: str,
        config: ModelConfig,
        output_format: StructuredOutputFormat | None = None,
        image: bytes | None = None,
        content_type: str = "",
        max_tokens: int | None = None,
    ) -> str:
        kwargs = {"api_key": config.api_key, "timeout": self.timeout_seconds}
        if config.base_url:
            kwargs["base_url"] = config.base_url
        client = OpenAI(**kwargs)
        content: Any = prompt
        if image is not None:
            data_url = f"data:{content_type};base64,{base64.b64encode(image).decode()}"
            content = [
                {"type": "text", "text": prompt},
                {"type": "image_url", "image_url": {"url": data_url}},
            ]
        request: dict[str, Any] = {
            "model": config.model,
            "messages": [{"role": "user", "content": content}],
            "temperature": 0,
        }
        if max_tokens is not None:
            request["max_tokens"] = max_tokens
        if output_format and output_format.mode == "json_schema":
            request["response_format"] = {
                "type": "json_schema",
                "json_schema": {
                    "name": output_format.name,
                    "schema": output_format.schema,
                },
            }
        elif output_format and output_format.mode == "json_object":
            request["response_format"] = {"type": "json_object"}
        try:
            response = client.chat.completions.create(**request)
            return response.choices[0].message.content or ""
        except (APIConnectionError, APITimeoutError) as error:
            raise ModelCallError(ModelErrorCode.CONNECTION_FAILED, "模型连接失败", 502) from error
        except BadRequestError as error:
            if image is not None or output_format is not None:
                raise ModelCallError(ModelErrorCode.CAPABILITY_UNSUPPORTED, "模型不支持声明的输入或输出能力", 422) from error
            raise ModelCallError(ModelErrorCode.PROVIDER_ERROR, "模型服务拒绝了请求", 502) from error
        except (APIStatusError, APIError, IndexError, AttributeError) as error:
            raise ModelCallError(ModelErrorCode.PROVIDER_ERROR, "模型服务调用失败", 502) from error


class AnthropicAdapter:
    def __init__(self, timeout_seconds: float = 600) -> None:
        self.timeout_seconds = timeout_seconds

    def complete(
        self,
        prompt: str,
        config: ModelConfig,
        output_format: StructuredOutputFormat | None = None,
        image: bytes | None = None,
        content_type: str = "",
        max_tokens: int | None = None,
    ) -> str:
        content: list[dict[str, Any]] = [{"type": "text", "text": prompt}]
        if image is not None:
            content.append({
                "type": "image",
                "source": {
                    "type": "base64",
                    "media_type": content_type,
                    "data": base64.b64encode(image).decode(),
                },
            })
        payload: dict[str, Any] = {
            "model": config.model,
            "max_tokens": max_tokens or 4096,
            "messages": [{"role": "user", "content": content}],
        }
        if output_format and output_format.mode == "json_schema":
            payload["output_config"] = {
                "format": {"type": "json_schema", "schema": output_format.schema},
            }
        try:
            response = httpx.post(
                _anthropic_messages_url(config.base_url),
                headers={
                    "x-api-key": config.api_key,
                    "anthropic-version": "2023-06-01",
                    "content-type": "application/json",
                },
                json=payload,
                timeout=self.timeout_seconds,
            )
            if response.status_code in {400, 415, 422} and (image is not None or output_format is not None):
                raise ModelCallError(ModelErrorCode.CAPABILITY_UNSUPPORTED, "模型不支持声明的输入或输出能力", 422)
            response.raise_for_status()
            body = response.json()
            return "".join(
                str(block.get("text", "")) for block in body.get("content", [])
                if isinstance(block, dict) and block.get("type") == "text"
            )
        except ModelCallError:
            raise
        except (httpx.ConnectError, httpx.TimeoutException) as error:
            raise ModelCallError(ModelErrorCode.CONNECTION_FAILED, "模型连接失败", 502) from error
        except (httpx.HTTPError, ValueError, TypeError, AttributeError) as error:
            raise ModelCallError(ModelErrorCode.PROVIDER_ERROR, "模型服务调用失败", 502) from error


class AdapterRegistry:
    def __init__(
        self,
        adapters: dict[str, ModelAdapter] | None = None,
        timeout_seconds: float = 600,
    ) -> None:
        self.adapters = adapters or {
            "openai": OpenAICompatibleAdapter(timeout_seconds),
            "openai-compat": OpenAICompatibleAdapter(timeout_seconds),
            "anthropic": AnthropicAdapter(timeout_seconds),
        }

    def get(self, provider: str) -> ModelAdapter:
        adapter = self.adapters.get(provider)
        if adapter is None:
            raise ModelCallError(ModelErrorCode.CAPABILITY_UNSUPPORTED, f"不支持的模型协议: {provider}", 422)
        return adapter


class RoutedLlmClient:
    def __init__(self, settings: AgentSettings, registry: AdapterRegistry | None = None) -> None:
        self.settings = settings
        self.registry = registry or AdapterRegistry(
            timeout_seconds=settings.model_request_timeout_seconds,
        )

    def complete(
        self,
        prompt: str,
        config: ModelConfig,
        output_format: StructuredOutputFormat | None = None,
    ) -> str:
        return self.registry.get(config.provider).complete(prompt, config, output_format=output_format)

    def complete_vision(
        self,
        prompt: str,
        image: bytes,
        content_type: str,
        config: ModelConfig,
        output_format: StructuredOutputFormat | None = None,
    ) -> str:
        return self.registry.get(config.provider).complete(
            prompt, config, output_format=output_format, image=image, content_type=content_type,
        )

    def test_connection(self, config: ModelConfig) -> None:
        self.registry.get(config.provider).complete("ping", config, max_tokens=1)


def _anthropic_messages_url(base_url: str) -> str:
    base = (base_url or "https://api.anthropic.com").rstrip("/")
    return base + ("/messages" if base.endswith("/v1") else "/v1/messages")


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
        image_input=override.image_input or defaults.image_input,
        structured_output=override.structured_output or defaults.structured_output,
    )
