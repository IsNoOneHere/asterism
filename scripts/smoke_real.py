#!/usr/bin/env python3
"""真实 LLM 端到端验收：API -> Temporal -> worker -> git commit。"""

from __future__ import annotations

import base64
import json
import os
import re
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
SCENARIO = os.getenv("V5_SMOKE_SCENARIO", "basic").strip() or "basic"


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
    if SCENARIO == "fullstack":
        (host_repo / "frontend").mkdir()
        (host_repo / "backend").mkdir()
        (host_repo / "frontend" / "page.txt").write_text("title=old\n", encoding="utf-8")
        (host_repo / "backend" / "service.py").write_text("MESSAGE = 'old'\n", encoding="utf-8")
    else:
        (host_repo / "README.md").write_text("asterism\n", encoding="utf-8")
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


def wait_prd_turn(accepted: dict) -> dict:
    deadline = time.time() + TIMEOUT_SECONDS
    while time.time() < deadline:
        conversation = request("GET", f"/api/v5/conversations/{accepted['conversationId']}") or {}
        if not conversation.get("pendingAssistant"):
            messages = conversation.get("messages", [])
            if messages and messages[-1].get("content") == "AI 暂时不可用，请重试":
                raise RuntimeError("PRD AI 回合失败")
            return request("GET", f"/api/v5/prd-sessions/{accepted['prdId']}")  # type: ignore[return-value]
        time.sleep(1)
    raise RuntimeError(f"等待 PRD AI 回合超时: {accepted['prdId']}")


def wait_system_ready(system_id: str) -> dict:
    deadline = time.time() + TIMEOUT_SECONDS
    last: Optional[dict] = None
    while time.time() < deadline:
        last = request("GET", f"/api/v5/systems/{system_id}/readiness")  # type: ignore[assignment]
        if last.get("ready"):
            return last
        time.sleep(2)
    raise RuntimeError(f"等待系统 readiness 超时: {last.get('issues') if last else 'none'}")


def configure_developer(system_id: str) -> str:
    """真实 smoke 显式配置唯一的 Claude SDK developer，不依赖旧执行字段。"""

    config = request("POST", f"/api/v5/systems/{system_id}/model-profiles", {
        "name": "Smoke Claude",
        "provider": "anthropic",
        "model": os.getenv("V5_MODEL", os.getenv("V5_AGENT_MODEL", "")),
        "baseUrl": os.getenv("V5_MODEL_BASE_URL", ""),
        "apiKey": os.getenv("V5_MODEL_API_KEY", ""),
        "supportsVision": False,
    }) or {}
    profile_id = config["modelProfiles"][-1]["id"]
    request("PATCH", f"/api/v5/systems/{system_id}/agents/developer", {
        "name": "developer",
        "engine": "claude_sdk_team",
        "modelProfileRef": profile_id,
        "pathScope": [],
        "prompt": "",
        "maxTurns": int(os.getenv("V5_SMOKE_CLAUDE_MAX_TURNS", "50")),
        "timeoutSeconds": int(os.getenv("V5_SMOKE_EXECUTION_TIMEOUT_SECONDS", "600")),
    })
    return profile_id


def changed_paths(diff_patch: str) -> set[str]:
    return set(re.findall(r"^diff --git a/(.+?) b/", diff_patch, re.MULTILINE))


def prepare_prd(system_id: str, goal: str, acceptance: str) -> dict:
    first = wait_prd_turn(request("POST", f"/api/v5/systems/{system_id}/prd/messages", {"content": goal}))
    if first.get("status") == "waiting_user_confirm":
        log("第一轮 PRD 已直接就绪")
    elif "acceptance_criteria" in first.get("missingFields", []):
        first = wait_prd_turn(request("POST", f"/api/v5/systems/{system_id}/prd/messages", {
            "prdId": first["prdId"], "content": acceptance,
        }))
        log("补充验收标准后 PRD 已就绪")
    if first.get("status") != "waiting_user_confirm":
        raise RuntimeError(f"PRD 未进入确认态: {first}")
    return first


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
    if not os.getenv("V5_MODEL_API_KEY"):
        print("缺少 V5_MODEL_API_KEY，Claude SDK Supervisor 无法执行。", file=sys.stderr)
        return 2
    if not (os.getenv("V5_MODEL") or os.getenv("V5_AGENT_MODEL")):
        print("缺少 V5_MODEL，Claude SDK Supervisor 无法选择模型。", file=sys.stderr)
        return 2
    if SCENARIO not in {"basic", "fullstack"}:
        print(f"不支持的 V5_SMOKE_SCENARIO={SCENARIO}", file=sys.stderr)
        return 2
    compatible_url = os.getenv("V5_MODEL_BASE_URL", "").strip()
    auth_mode = "AUTH_TOKEN" if compatible_url else "API_KEY"
    log(f"Claude SDK 端点: {compatible_url or 'Anthropic 默认端点'}，鉴权变量: {auth_mode}")

    suffix = str(int(time.time()))
    system_id = f"smoke-system-{suffix}"
    repo_name = f"asterism-smoke-{suffix}"
    host_repo, repo_path = make_repo(repo_name)
    log(f"临时 repo: {host_repo}")
    fullstack = SCENARIO == "fullstack"
    allowed_paths = ["frontend/", "backend/"] if fullstack else ["README.md", "app.py"]
    test_commands = [
        "python -c \"from pathlib import Path; assert 'Asterism web' in Path('frontend/page.txt').read_text(); assert 'Asterism API' in Path('backend/service.py').read_text()\""
        if fullstack else
        "python -c \"from pathlib import Path; assert 'Asterism smoke' in Path('README.md').read_text()\""
    ]
    request("POST", "/api/v5/systems", {
        "systemId": system_id,
        "name": "Smoke Real",
        "description": "真实 LLM smoke",
        "repoPath": repo_path,
        "ownerUserId": ADMIN_USER,
        "allowedPaths": allowed_paths,
        "forbiddenPaths": [],
        "testCommands": test_commands,
        "gitConfiguration": {
            "repos": [{
                "repoId": "main", "name": "Smoke Repo", "kind": "fullstack" if fullstack else "other",
                "gitlabProject": "", "defaultBranch": "main", "cloneMode": "local",
                "localPath": repo_path, "allowedPaths": allowed_paths, "forbiddenPaths": [],
                "testCommands": test_commands,
            }],
            "releaseMode": "local", "validationMode": "auto", "mrTargetBranch": "", "mrLabels": [],
        },
    })
    log("系统已创建")
    configure_developer(system_id)
    wait_system_ready(system_id)
    log("系统 readiness 已通过")

    goal = ("同时修改前后端：frontend/page.txt 的标题改为 Asterism web，"
            "backend/service.py 的 MESSAGE 改为 Asterism API。" if fullstack
            else "把 README 里的 asterism 改成 Asterism smoke")
    acceptance = ("验收标准：frontend/page.txt 必须包含 Asterism web，"
                  "backend/service.py 必须包含 Asterism API。" if fullstack
                  else "验收标准：README 必须包含 Asterism smoke。")
    first = prepare_prd(system_id, goal, acceptance)

    confirmed = request("POST", f"/api/v5/prd-sessions/{first['prdId']}/confirm")
    work_item_id = confirmed["workItemId"]
    log(f"工作项已生成: {work_item_id}")

    wait_item(work_item_id, "waiting_owner_approval")
    request("POST", f"/api/v5/work-items/{work_item_id}/owner-approval")
    wait_item(work_item_id, "activated")
    request("POST", f"/api/v5/work-items/{work_item_id}/signals/start_modification")
    wait_item(work_item_id, "modification_completed")
    modification = event_payload(wait_event(work_item_id, "ModificationCompleted"))
    if modification.get("executionProvider") != "claude_sdk_team":
        raise RuntimeError(f"执行内核不一致: {modification.get('executionProvider')} != claude_sdk_team")
    if not modification.get("turns") or not modification.get("tokenUsage"):
        raise RuntimeError(f"Claude SDK 审计摘要不完整: {modification}")
    diff_patch = modification.get("diffPatch", "")
    if "diff --git" not in diff_patch:
        raise RuntimeError("ModificationCompleted 没有有效 diff")
    sh(["git", "apply", "--check"], host_repo, diff_patch)
    if fullstack:
        paths = changed_paths(diff_patch)
        expected = {"frontend/page.txt", "backend/service.py"}
        if not expected.issubset(paths):
            raise RuntimeError(f"前后端 diff 不完整: {sorted(paths)}")
    runs = modification.get("subagentRuns") or []
    if not any(item.get("repo") == "main" for item in runs):
        raise RuntimeError(f"缺少仓库子 Agent 审计记录: {runs}")
    log("claude_sdk_team diff 非空且 git apply --check 通过")

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
    if fullstack:
        if "Asterism web" not in (host_repo / "frontend" / "page.txt").read_text(encoding="utf-8"):
            raise RuntimeError("前端文件未包含真实改动")
        if "Asterism API" not in (host_repo / "backend" / "service.py").read_text(encoding="utf-8"):
            raise RuntimeError("后端文件未包含真实改动")
    elif "Asterism smoke" not in (host_repo / "README.md").read_text(encoding="utf-8"):
        raise RuntimeError("README 未包含真实改动")
    log(f"真实 smoke 通过: {branch} {commit_hash}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
