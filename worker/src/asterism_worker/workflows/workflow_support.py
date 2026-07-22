def feedback_text(context: dict) -> str:
    """从动作上下文提取用户可审计的反馈。"""

    return "\n".join(
        str(context.get(key, "")).strip()
        for key in ("note", "evidence")
        if str(context.get(key, "")).strip()
    )


def error_detail(error: BaseException) -> str:
    """串联 Activity 异常因果链，避免只显示外层包装错误。"""

    messages: list[str] = []
    current: BaseException | None = error
    while current is not None:
        if text := str(current):
            messages.append(text)
        current = current.__cause__
    return " | ".join(messages)


def has_application_error_type(error: BaseException, expected: str) -> bool:
    """沿 Temporal 异常链识别稳定业务错误类型。"""

    current: BaseException | None = error
    while current is not None:
        if getattr(current, "type", "") == expected:
            return True
        current = current.__cause__
    return False
