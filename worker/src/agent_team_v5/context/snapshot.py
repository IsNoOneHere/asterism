from typing import Iterable


def approved_memory_only(memories: Iterable[dict]) -> list[dict]:
    """candidate/rejected 不进入 worker 上下文。"""

    return [memory for memory in memories if memory.get("status") == "approved"]

