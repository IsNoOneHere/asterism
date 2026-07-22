import asyncio
import fcntl
import hashlib
import json
import logging
import os
import re
import tempfile
from collections.abc import Awaitable, Callable
from datetime import UTC, datetime
from pathlib import Path
from typing import Any, TypeVar

from temporalio import activity

from asterism_worker.contracts import CodingAttemptRequest, CodingAttemptResult

log = logging.getLogger(__name__)
T = TypeVar("T")


def coding_input_fingerprint(request: CodingAttemptRequest) -> str:
    """内容指纹只描述业务输入，attempt_id 不参与，便于 Activity retry 复用。"""

    payload = request.model_dump(mode="json", exclude={"attempt_id"})
    encoded = json.dumps(payload, ensure_ascii=False, sort_keys=True, separators=(",", ":"))
    return hashlib.sha256(encoded.encode()).hexdigest()


def resolve_attempt_id(request: CodingAttemptRequest, fingerprint: str) -> str:
    """Temporal activity_id 在自动重试间稳定；直接调用时退回内容寻址身份。"""

    if request.attempt_id.strip():
        return request.attempt_id.strip()
    try:
        info = activity.info()
        identity = ":".join((info.workflow_id, info.workflow_run_id, info.activity_id))
    except RuntimeError:
        identity = f"{request.case_id}:{fingerprint}"
    return "attempt-" + hashlib.sha256(identity.encode()).hexdigest()[:24]


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


class CaseExecutionLease:
    """持有文件锁期间同一 Case 只有一个协作执行者，并用 generation 做写前 fencing。"""

    def __init__(self, case_root: Path, owner_id: str, descriptor: int, generation: int) -> None:
        self.case_root = case_root
        self.owner_id = owner_id
        self.descriptor = descriptor
        self.generation = generation
        self.state_path = case_root / "execution-lease.json"
        self.active = True

    @classmethod
    async def acquire(
        cls, artifacts_root: str | Path, case_id: str, owner_id: str,
    ) -> "CaseExecutionLease":
        case_root = _case_root(artifacts_root, case_id)
        case_root.mkdir(parents=True, exist_ok=True)
        lock_path = case_root / "execution-lease.lock"
        descriptor = os.open(lock_path, os.O_RDWR | os.O_CREAT, 0o600)
        try:
            while True:
                try:
                    fcntl.flock(descriptor, fcntl.LOCK_EX | fcntl.LOCK_NB)
                    break
                except BlockingIOError:
                    await asyncio.sleep(0.2)
            state_path = case_root / "execution-lease.json"
            generation = int(_read_json(state_path).get("generation", 0)) + 1
            _write_json_atomic(state_path, {
                "ownerId": owner_id,
                "generation": generation,
                "acquiredAt": datetime.now(UTC).isoformat(),
            })
            log.info(
                "Case 执行租约已获取 case=%s owner=%s generation=%s",
                case_id, owner_id, generation,
            )
            return cls(case_root, owner_id, descriptor, generation)
        except BaseException:
            os.close(descriptor)
            raise

    def assert_owner(self) -> None:
        """每个写入边界核对 fencing token，过期 Attempt 不得继续落盘。"""

        if not self.active:
            raise RuntimeError("Case 执行租约已释放")
        state = _read_json(self.state_path)
        if state.get("ownerId") != self.owner_id or state.get("generation") != self.generation:
            raise RuntimeError("Case 执行租约已被新 Attempt 接管")

    def release(self) -> None:
        if not self.active:
            return
        self.active = False
        fcntl.flock(self.descriptor, fcntl.LOCK_UN)
        os.close(self.descriptor)
        log.info(
            "Case 执行租约已释放 owner=%s generation=%s", self.owner_id, self.generation,
        )


class CandidateCheckpointStore:
    """仅持久化一次 Coding Attempt 的候选结果，不引入通用 Artifact 平台。"""

    def __init__(self, artifacts_root: str | Path) -> None:
        self.artifacts_root = Path(artifacts_root)

    def load(
        self, case_id: str, attempt_id: str, fingerprint: str,
    ) -> CodingAttemptResult | None:
        path = self._path(case_id, attempt_id, fingerprint)
        if not path.exists():
            return None
        payload = _read_json(path)
        if (
            payload.get("attemptId") != attempt_id
            or payload.get("inputFingerprint") != fingerprint
        ):
            raise RuntimeError("Candidate checkpoint 身份或输入指纹不匹配")
        result = CodingAttemptResult.model_validate(payload.get("result"))
        log.info("复用 Candidate checkpoint case=%s attempt=%s", case_id, attempt_id)
        return result

    def save(
        self,
        case_id: str,
        attempt_id: str,
        fingerprint: str,
        result: CodingAttemptResult,
    ) -> None:
        path = self._path(case_id, attempt_id, fingerprint)
        _write_json_atomic(path, {
            "version": 1,
            "caseId": case_id,
            "attemptId": attempt_id,
            "inputFingerprint": fingerprint,
            "createdAt": datetime.now(UTC).isoformat(),
            "result": result.model_dump(mode="json"),
        })
        log.info("Candidate checkpoint 已持久化 case=%s attempt=%s", case_id, attempt_id)

    def discard(self, case_id: str, attempt_id: str, fingerprint: str) -> None:
        self._path(case_id, attempt_id, fingerprint).unlink(missing_ok=True)

    def _path(self, case_id: str, attempt_id: str, fingerprint: str) -> Path:
        attempt_key = hashlib.sha256(attempt_id.encode()).hexdigest()[:24]
        return _case_root(self.artifacts_root, case_id) / "attempts" / attempt_key / f"{fingerprint}.json"


def _case_root(artifacts_root: str | Path, case_id: str) -> Path:
    safe_case = re.sub(r"[^a-zA-Z0-9_.-]", "-", case_id) or "case"
    return Path(artifacts_root) / "cases" / safe_case


def _read_json(path: Path) -> dict[str, Any]:
    if not path.exists():
        return {}
    return json.loads(path.read_text(encoding="utf-8"))


def _write_json_atomic(path: Path, payload: dict[str, Any]) -> None:
    """同目录写临时文件再 replace，崩溃时只能看到旧版或完整新版。"""

    path.parent.mkdir(parents=True, exist_ok=True)
    descriptor, temporary = tempfile.mkstemp(prefix=f".{path.name}.", dir=path.parent)
    temporary_path = Path(temporary)
    try:
        with os.fdopen(descriptor, "w", encoding="utf-8") as stream:
            json.dump(payload, stream, ensure_ascii=False, separators=(",", ":"))
            stream.write("\n")
            stream.flush()
            os.fsync(stream.fileno())
        os.replace(temporary_path, path)
    finally:
        temporary_path.unlink(missing_ok=True)
