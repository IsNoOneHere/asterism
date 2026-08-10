import asyncio
from collections.abc import Awaitable, Callable
from typing import Any, TypeVar

from temporalio import activity

T = TypeVar("T")


async def run_with_periodic_heartbeat(
    operation: Awaitable[T],
    details: dict[str, Any],
    interval_seconds: float,
    heartbeat: Callable[[dict[str, Any]], None] = activity.heartbeat,
) -> T:
    """独立心跳不依赖模型输出；Activity 取消时同步取消 SDK 协程。"""

    stopped = asyncio.Event()

    async def heartbeat_loop() -> None:
        while not stopped.is_set():
            try:
                heartbeat(details)
            except RuntimeError:
                # 单元测试会直接调用 Activity 函数，此时没有 Temporal 上下文。
                pass
            try:
                await asyncio.wait_for(stopped.wait(), timeout=interval_seconds)
            except TimeoutError:
                continue

    operation_task = asyncio.create_task(operation)
    heartbeat_task = asyncio.create_task(heartbeat_loop())
    try:
        done, _ = await asyncio.wait(
            {operation_task, heartbeat_task}, return_when=asyncio.FIRST_COMPLETED,
        )
        if heartbeat_task in done and not operation_task.done():
            # heartbeat 只有被取消或出现异常才会提前结束；停止仍在运行的 SDK。
            operation_task.cancel()
            await asyncio.gather(operation_task, return_exceptions=True)
            if heartbeat_task.cancelled():
                raise asyncio.CancelledError
            error = heartbeat_task.exception()
            if error is not None:
                raise error
            raise RuntimeError("Activity heartbeat 意外停止")
        return await operation_task
    finally:
        stopped.set()
        if not operation_task.done():
            operation_task.cancel()
        if not heartbeat_task.done():
            heartbeat_task.cancel()
        await asyncio.gather(operation_task, heartbeat_task, return_exceptions=True)
