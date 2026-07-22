import asyncio
import base64
import hashlib
import json
import logging
from pathlib import Path
from typing import Any


log = logging.getLogger(__name__)


class JsonlSessionStore:
    """把 Claude SDK 会话镜像到持久卷，保留跨 Activity 审计与共享恢复扩展点。"""

    def __init__(self, root: str | Path) -> None:
        self.root = Path(root)
        self.root.mkdir(parents=True, exist_ok=True)
        self._locks: dict[Path, asyncio.Lock] = {}

    async def append(self, key: dict[str, str], entries: list[dict[str, Any]]) -> None:
        path = self._path(key)
        async with self._locks.setdefault(path, asyncio.Lock()):
            path.parent.mkdir(parents=True, exist_ok=True)
            known = self._known_uuids(path)
            with path.open("a", encoding="utf-8") as stream:
                for entry in entries:
                    entry_id = str(entry.get("uuid", ""))
                    if entry_id and entry_id in known:
                        continue
                    stream.write(json.dumps(entry, ensure_ascii=False, separators=(",", ":")) + "\n")
                    if entry_id:
                        known.add(entry_id)

    async def load(self, key: dict[str, str]) -> list[dict[str, Any]] | None:
        path = self._existing_path(key)
        if not path.exists():
            return None
        return [json.loads(line) for line in path.read_text(encoding="utf-8").splitlines() if line]

    async def list_subkeys(self, key: dict[str, str]) -> list[str]:
        directory = self._existing_subkey_dir(key)
        if not directory.exists():
            return []
        return [self._decode(path.stem) for path in directory.glob("*.jsonl")]

    def contains(self, session_id: str) -> bool:
        """按 SDK Session ID 查找持久 transcript，不依赖 Worker 上的绝对工作区路径。"""

        return any(
            (project / f"{session_id}.jsonl").is_file()
            for project in self.root.iterdir()
            if project.is_dir()
        )

    def _path(self, key: dict[str, str]) -> Path:
        project = self._project_dir(key["project_key"])
        session_id = key["session_id"]
        subpath = key.get("subpath")
        if not subpath:
            return project / f"{session_id}.jsonl"
        return project / f"{session_id}.sub" / f"{self._encode(subpath)}.jsonl"

    def _existing_path(self, key: dict[str, str]) -> Path:
        path = self._path(key)
        if path.exists():
            return path
        session_id = key["session_id"]
        subpath = key.get("subpath")
        for project in self.root.iterdir():
            candidate = (
                project / f"{session_id}.sub" / f"{self._encode(subpath)}.jsonl"
                if subpath else project / f"{session_id}.jsonl"
            )
            if candidate.exists():
                # project_key 含绝对 cwd；跨 Worker 路径变化时仍应按 Session ID 恢复。
                log.info("Claude SDK Session 跨工作区路径恢复 session_id=%s", session_id)
                return candidate
        return path

    def _existing_subkey_dir(self, key: dict[str, str]) -> Path:
        direct = self._project_dir(key["project_key"]) / f"{key['session_id']}.sub"
        if direct.exists():
            return direct
        for project in self.root.iterdir():
            candidate = project / f"{key['session_id']}.sub"
            if candidate.exists():
                return candidate
        return direct

    def _project_dir(self, project_key: str) -> Path:
        digest = hashlib.sha256(project_key.encode()).hexdigest()
        return self.root / digest

    def _known_uuids(self, path: Path) -> set[str]:
        if not path.exists():
            return set()
        values: set[str] = set()
        for line in path.read_text(encoding="utf-8").splitlines():
            if not line:
                continue
            entry_id = str(json.loads(line).get("uuid", ""))
            if entry_id:
                values.add(entry_id)
        return values

    def _encode(self, value: str) -> str:
        return base64.urlsafe_b64encode(value.encode()).decode().rstrip("=")

    def _decode(self, value: str) -> str:
        padding = "=" * (-len(value) % 4)
        return base64.urlsafe_b64decode(value + padding).decode()
