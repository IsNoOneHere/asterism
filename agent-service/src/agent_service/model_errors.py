from enum import StrEnum


class ModelErrorCode(StrEnum):
    CONNECTION_FAILED = "MODEL_CONNECTION_FAILED"
    CAPABILITY_UNSUPPORTED = "MODEL_CAPABILITY_UNSUPPORTED"
    OUTPUT_INVALID = "MODEL_OUTPUT_INVALID"
    PROVIDER_ERROR = "MODEL_PROVIDER_ERROR"


class ModelCallError(RuntimeError):
    def __init__(self, code: ModelErrorCode, message: str, status_code: int) -> None:
        super().__init__(message)
        self.code = code
        self.message = message
        self.status_code = status_code
