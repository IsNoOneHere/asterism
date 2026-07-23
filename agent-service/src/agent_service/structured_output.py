import json
import logging
from collections.abc import Callable
from typing import Any, TypeVar

from pydantic import BaseModel, ValidationError

from agent_service.llm import LlmClient, ModelConfig, StructuredOutputFormat
from agent_service.model_errors import ModelCallError, ModelErrorCode

log = logging.getLogger(__name__)

OutputModel = TypeVar("OutputModel", bound=BaseModel)


class StructuredOutputRunner:
    def __init__(self, llm: LlmClient) -> None:
        self.llm = llm

    def run(
        self,
        prompt: str,
        config: ModelConfig,
        schema: type[OutputModel],
        normalizer: Callable[[Any], Any] | None = None,
        image: bytes | None = None,
        content_type: str = "",
    ) -> OutputModel:
        if not config.model or not config.api_key:
            raise ModelCallError(ModelErrorCode.CONNECTION_FAILED, "模型配置不完整", 502)
        json_schema = schema.model_json_schema()
        schema_text = json.dumps(json_schema, ensure_ascii=False, separators=(",", ":"))
        output_format = StructuredOutputFormat(config.structured_output, schema.__name__, json_schema)
        current_prompt = self._initial_prompt(prompt, schema_text, config.structured_output)

        for attempt in range(2):
            raw = self._complete(current_prompt, config, output_format, image, content_type)
            try:
                value = json.loads(raw)
                if normalizer is not None:
                    value = normalizer(value)
                result = schema.model_validate(value)
                log.info(
                    "结构化输出校验成功 provider=%s model=%s schema=%s attempt=%s",
                    config.provider, config.model, schema.__name__, attempt + 1,
                )
                return result
            except json.JSONDecodeError as error:
                validation_error = f"JSONDecodeError: {error}"
            except ValidationError as error:
                validation_error = error.json(include_url=False)

            log.warning(
                "结构化输出校验失败 provider=%s model=%s schema=%s attempt=%s",
                config.provider, config.model, schema.__name__, attempt + 1,
            )
            if attempt == 0:
                current_prompt = self._repair_prompt(prompt, schema_text, validation_error, raw)

        raise ModelCallError(ModelErrorCode.OUTPUT_INVALID, "模型输出不符合业务契约", 422)

    def _complete(
        self,
        prompt: str,
        config: ModelConfig,
        output_format: StructuredOutputFormat,
        image: bytes | None,
        content_type: str,
    ) -> str:
        if image is None:
            return self.llm.complete(prompt, config, output_format)
        return self.llm.complete_vision(prompt, image, content_type, config, output_format)

    def _initial_prompt(self, prompt: str, schema_text: str, mode: str) -> str:
        if mode == "json_schema":
            return prompt
        # json_object 和 prompt_only 都携带完整 Schema，避免只给字段名导致类型漂移。
        return f"{prompt}\nReturn only JSON matching this JSON Schema exactly:\n{schema_text}"

    def _repair_prompt(self, prompt: str, schema_text: str, validation_error: str, raw: str) -> str:
        # 修复轮次必须携带原输出和精确校验错误，禁止无信息原样重试。
        return (
            f"{prompt}\nReturn only JSON matching this JSON Schema exactly:\n{schema_text}\n"
            f"The previous output failed validation with these exact errors:\n{validation_error}\n"
            f"Previous output:\n{raw}\nCorrect the output and return JSON only."
        )
