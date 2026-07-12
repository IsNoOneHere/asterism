#!/usr/bin/env python3
"""真实 LLM 端到端验收：API -> Temporal -> worker -> git commit。"""

from __future__ import annotations

import base64
import json
import os
import shutil
import subprocess
import sys
import time
import urllib.error
import urllib.request
from pathlib import Path
from typing import Optional, Union


CONTROL_URL = os.getenv("V5_SMOKE_CONTROL_URL", "http://127.0.0.1:8085").rstrip("/")
ADMIN_USER = os.getenv("V5_SMOKE_ADMIN_USER", "admin")
ADMIN_PASSWORD = os.getenv("V5_SMOKE_ADMIN_PASSWORD", "")
HOST_REPO_ROOT = Path(os.getenv("V5_SMOKE_REPO_ROOT", os.getenv("V5_REPO_ROOT", "/tmp"))).expanduser()
CONTAINER_REPO_ROOT = os.getenv("V5_SMOKE_CONTAINER_REPO_ROOT", "/repos").rstrip("/")
TIMEOUT_SECONDS = int(os.getenv("V5_SMOKE_TIMEOUT_SECONDS", "240"))
REQUEST_TIMEOUT_SECONDS = int(os.getenv("V5_SMOKE_REQUEST_TIMEOUT_SECONDS", "60"))
EXECUTION_PROVIDER = os.getenv("V5_SMOKE_EXECUTION_PROVIDER", "http").strip() or "http"


def log(message: str) -> None:
    print(f"[smoke-real] {message}", flush=True)


def auth_header() -> str:
    raw = f"{ADMIN_USER}:{ADMIN_PASSWORD}".encode()
    return "Basic " + base64.b64encode(raw).decode()


def request(method: str, path: str, body: Optional[dict] = None) -> Optional[Union[dict, list]]:
    data = None if body is None else json.dumps(body, ensure_ascii=False).encode()
    req = urllib.request.Request(
        CONTROL_URL + path,
        data=data,
        method=method,
        headers={"Authorization": auth_header(), "Content-Type": "application/json"},
    )
    try:
        with urllib.request.urlopen(req, timeout=REQUEST_TIMEOUT_SECONDS) as response:
            text = response.read().decode()
            return json.loads(text) if text else None
    except urllib.error.HTTPError as error:
        text = error.read().decode(errors="ignore")
        raise RuntimeError(f"{method} {path} -> {error.code}: {text}") from error


def sh(cmd: list[str], cwd: Optional[Path] = None, input_text: Optional[str] = None) -> str:
    result = subprocess.run(cmd, cwd=cwd, input=input_text, text=True, capture_output=True)
    if result.returncode != 0:
        raise RuntimeError(f"{' '.join(cmd)}\n{result.stderr or result.stdout}")
    return result.stdout.strip()


def make_repo(name: str) -> tuple[Path, str]:
    host_repo = HOST_REPO_ROOT / name
    if host_repo.exists():
        shutil.rmtree(host_repo)
    host_repo.mkdir(parents=True)
    (host_repo / "README.md").write_text("agent-team\n", encoding="utf-8")
    (host_repo / "app.py").write_text("print('hello')\n", encoding="utf-8")
    sh(["git", "init"], host_repo)
    sh(["git", "add", "."], host_repo)
    sh(["git", "-c", "user.name=smoke", "-c", "user.email=smoke@example.invalid", "commit", "-m", "init"], host_repo)
    return host_repo, f"{CONTAINER_REPO_ROOT}/{name}"


def wait_item(work_item_id: str, target: str) -> dict:
    deadline = time.time() + TIMEOUT_SECONDS
    last: Optional[dict] = None
    while time.time() < deadline:
        last = request("GET", f"/api/v5/work-items/{work_item_id}")  # type: ignore[assignment]
        status = str(last.get("lifecycleStatus"))
        if status == target:
            return last
        if status in {"worker_blocked", "cancelled", "rejected"}:
            dump_recent_events(work_item_id)
            raise RuntimeError(f"工作项提前进入 {status}")
        time.sleep(2)
    dump_recent_events(work_item_id)
    raise RuntimeError(f"等待 {target} 超时，最后状态={last.get('lifecycleStatus') if last else 'none'}")


def wait_event(work_item_id: str, event_type: str) -> dict:
    deadline = time.time() + TIMEOUT_SECONDS
    while time.time() < deadline:
        events = request("GET", f"/api/v5/work-items/{work_item_id}/events") or []
        for event in events:
            if event.get("eventType") == event_type:
                return event
        time.sleep(2)
    dump_recent_events(work_item_id)
    raise RuntimeError(f"等待事件 {event_type} 超时")


def event_payload(event: dict) -> dict:
    value = event.get("payloadJson") or "{}"
    return json.loads(value)


def dump_recent_events(work_item_id: str) -> None:
    try:
        events = request("GET", f"/api/v5/work-items/{work_item_id}/events") or []
        for event in events[-5:]:
            print(json.dumps(event, ensure_ascii=False), flush=True)
    except Exception as error:
        print(f"读取最近事件失败: {error}", flush=True)


def main() -> int:
    api_key = os.getenv("V5_AGENT_API_KEY")
    if not api_key:
        print("缺少 V5_AGENT_API_KEY，真实验收不能使用 fake 或空 key。", file=sys.stderr)
        return 2
    if not ADMIN_PASSWORD:
        print("缺少 V5_SMOKE_ADMIN_PASSWORD，请使用首次启动密码或显式配置的 admin 密码。", file=sys.stderr)
        return 2
    if EXECUTION_PROVIDER == "claude_sdk" and not (os.getenv("V5_MODEL_API_KEY") or os.getenv("V5_ANTHROPIC_API_KEY")):
        print("SKIP: Claude SDK 缺少 V5_MODEL_API_KEY（旧环境可继续用 V5_ANTHROPIC_API_KEY）。", file=sys.stderr)
        return 2
    if EXECUTION_PROVIDER not in {"http", "claude_sdk"}:
        print(f"不支持的 V5_SMOKE_EXECUTION_PROVIDER={EXECUTION_PROVIDER}", file=sys.stderr)
        return 2
    if EXECUTION_PROVIDER == "claude_sdk":
        compatible_url = os.getenv("V5_MODEL_BASE_URL", os.getenv("V5_ANTHROPIC_BASE_URL", "")).strip()
        auth_mode = "AUTH_TOKEN" if compatible_url else "API_KEY"
        log(f"Claude SDK 端点: {compatible_url or 'Anthropic 默认端点'}，鉴权变量: {auth_mode}")

    suffix = str(int(time.time()))
    system_id = f"smoke-system-{suffix}"
    repo_name = f"agent-team-v5-smoke-{suffix}"
    host_repo, repo_path = make_repo(repo_name)
    model = os.getenv("V5_AGENT_MODEL", "gpt-4.1-mini")
    base_url = os.getenv("V5_AGENT_BASE_URL", "")

    log(f"临时 repo: {host_repo}")
    request("POST", "/api/v5/systems", {
        "systemId": system_id,
        "name": "Smoke Real",
        "description": "真实 LLM smoke",
        "repoPath": repo_path,
        "ownerUserId": ADMIN_USER,
        "allowedPaths": ["README.md", "app.py"],
        "forbiddenPaths": [],
        "testCommands": [
            "python -c \"from pathlib import Path; assert 'agent-team v5 smoke' in Path('README.md').read_text()\""
        ],
        "agentConfig": {
            "executionProvider": EXECUTION_PROVIDER,
            "claudeMaxTurns": int(os.getenv("V5_SMOKE_CLAUDE_MAX_TURNS", "50")),
            "executionTimeoutSeconds": int(os.getenv("V5_SMOKE_EXECUTION_TIMEOUT_SECONDS", "600")),
        },
        "modelProviderConfig": {
            "provider": "openai",
            "model": model,
            "baseUrl": base_url,
            "apiKey": api_key,
        },
    })
    log("系统已创建")

    first = request("POST", f"/api/v5/systems/{system_id}/prd/messages", {
        "content": "把 README 里的 agent-team 改成 agent-team v5 smoke",
    })
    if "acceptance_criteria" not in first.get("missingFields", []):
        raise RuntimeError(f"第一轮未追问验收标准: {first}")
    log("第一轮已触发验收标准追问")

    second = request("POST", f"/api/v5/systems/{system_id}/prd/messages", {
        "prdId": first["prdId"],
        "content": "验收标准：README 必须包含 agent-team v5 smoke。",
    })
    if second.get("status") != "waiting_user_confirm":
        raise RuntimeError(f"第二轮未进入确认态: {second}")
    log("第二轮 PRD 已就绪")

    confirmed = request("POST", f"/api/v5/prd-sessions/{first['prdId']}/confirm")
    work_item_id = confirmed["workItemId"]
    log(f"工作项已生成: {work_item_id}")

    wait_item(work_item_id, "waiting_owner_approval")
    request("POST", f"/api/v5/work-items/{work_item_id}/owner-approval")
    wait_item(work_item_id, "activated")
    request("POST", f"/api/v5/work-items/{work_item_id}/signals/start_modification")
    wait_item(work_item_id, "modification_completed")
    modification = event_payload(wait_event(work_item_id, "ModificationCompleted"))
    if modification.get("executionProvider") != EXECUTION_PROVIDER:
        raise RuntimeError(f"执行内核不一致: {modification.get('executionProvider')} != {EXECUTION_PROVIDER}")
    if EXECUTION_PROVIDER == "claude_sdk" and (not modification.get("turns") or not modification.get("tokenUsage")):
        raise RuntimeError(f"Claude SDK 审计摘要不完整: {modification}")
    diff_patch = modification.get("diffPatch", "")
    if "diff --git" not in diff_patch:
        raise RuntimeError("ModificationCompleted 没有有效 diff")
    sh(["git", "apply", "--check"], host_repo, diff_patch)
    log(f"{EXECUTION_PROVIDER} diff 非空且 git apply --check 通过")

    request("POST", f"/api/v5/work-items/{work_item_id}/signals/patch_apply_approved")
    wait_item(work_item_id, "validation_passed")
    request("POST", f"/api/v5/work-items/{work_item_id}/signals/release_approved")
    wait_item(work_item_id, "completed")
    release = event_payload(wait_event(work_item_id, "ReleaseCompleted"))
    branch = release.get("branch", "")
    commit_hash = release.get("commitHash", "")
    if not branch.startswith("wi/") or not commit_hash:
        raise RuntimeError(f"ReleaseCompleted payload 不完整: {release}")
    branches = sh(["git", "branch", "--list", branch], host_repo)
    if branch not in branches:
        raise RuntimeError(f"repo 未出现分支 {branch}")
    if "agent-team v5 smoke" not in (host_repo / "README.md").read_text(encoding="utf-8"):
        raise RuntimeError("README 未包含真实改动")
    log(f"真实 smoke 通过: {branch} {commit_hash}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
