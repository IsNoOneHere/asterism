#!/usr/bin/env python3
"""真实 GitLab 验收：创建项目、发布 MR、合并并等待 Temporal 轮询完成。"""

from __future__ import annotations

import json
import os
import sys
import time
import urllib.error
import urllib.request
from typing import Optional

import smoke_real as smoke


GITLAB_URL = os.getenv("ASTERISM_GITLAB_BASE_URL", "").rstrip("/")
GITLAB_TOKEN = os.getenv("ASTERISM_GITLAB_TOKEN", "")
NAMESPACE_ID = os.getenv("V5_SMOKE_GITLAB_NAMESPACE_ID", "").strip()
CLEANUP = os.getenv("V5_SMOKE_GITLAB_CLEANUP", "no").lower() == "yes"


def log(message: str) -> None:
    print(f"[smoke-gitlab] {message}", flush=True)


def gitlab_request(method: str, path: str, body: Optional[dict] = None):
    data = None if body is None else json.dumps(body).encode()
    request = urllib.request.Request(
        GITLAB_URL + path,
        data=data,
        method=method,
        headers={"PRIVATE-TOKEN": GITLAB_TOKEN, "Content-Type": "application/json"},
    )
    try:
        with urllib.request.urlopen(request, timeout=smoke.REQUEST_TIMEOUT_SECONDS) as response:
            text = response.read().decode()
            return json.loads(text) if text else None
    except urllib.error.HTTPError as error:
        detail = error.read().decode(errors="ignore")
        raise RuntimeError(f"GitLab {method} {path} -> {error.code}: {detail}") from error


def create_project(name: str) -> dict:
    payload: dict[str, object] = {"name": name, "path": name, "visibility": "private"}
    if NAMESPACE_ID:
        payload["namespace_id"] = int(NAMESPACE_ID)
    project = gitlab_request("POST", "/api/v4/projects", payload)
    gitlab_request("POST", f"/api/v4/projects/{project['id']}/repository/commits", {
        "branch": "main",
        "commit_message": "chore: initialize smoke project",
        "actions": [
            {"action": "create", "file_path": "README.md", "content": "asterism\n"},
            {"action": "create", "file_path": "app.py", "content": "print('hello')\n"},
        ],
    })
    return project


def configure_asterism(system_id: str, project_path: str) -> None:
    smoke.request("POST", "/api/v5/systems", {
        "systemId": system_id,
        "name": "Smoke GitLab",
        "description": "GitLab MR polling smoke",
        "repoPath": f"/tmp/{system_id}",
        "ownerUserId": smoke.ADMIN_USER,
        "allowedPaths": ["README.md", "app.py"],
        "forbiddenPaths": [],
        "testCommands": [],
    })
    provider = os.getenv("V5_AGENT_PROVIDER", "openai-compat")
    if provider == "openai":
        provider = "openai-compat"
    config = smoke.request("POST", f"/api/v5/systems/{system_id}/model-profiles", {
        "name": "Smoke Model",
        "provider": provider,
        "model": os.getenv("V5_AGENT_MODEL", "gpt-4.1-mini"),
        "baseUrl": os.getenv("V5_AGENT_BASE_URL", ""),
        "apiKey": os.getenv("V5_AGENT_API_KEY", ""),
        "supportsVision": False,
    }) or {}
    profile_id = config["modelProfiles"][-1]["id"]
    for name in ("product", "planner"):
        smoke.request("PATCH", f"/api/v5/systems/{system_id}/agents/{name}", {
            "name": name, "engine": "", "modelProfileRef": profile_id,
            "pathScope": [], "prompt": "", "maxTurns": 50, "timeoutSeconds": 600,
        })
    smoke.request("PATCH", f"/api/v5/systems/{system_id}/agents/developer", {
        "name": "developer", "engine": smoke.EXECUTION_PROVIDER, "modelProfileRef": profile_id,
        "pathScope": ["README.md", "app.py"], "prompt": "", "maxTurns": 50, "timeoutSeconds": 600,
    })
    smoke.request("PUT", f"/api/v5/systems/{system_id}/git-config", {
        "repos": [{
            "repoId": "app", "name": "Smoke App", "kind": "other",
            "gitlabProject": project_path, "defaultBranch": "main", "cloneMode": "gitlab",
            "localPath": "", "allowedPaths": ["README.md", "app.py"], "forbiddenPaths": [],
            "testCommands": ["python -c \"from pathlib import Path; assert 'Asterism smoke' in Path('README.md').read_text()\""],
        }],
        "releaseMode": "gitlab", "validationMode": "auto", "mrTargetBranch": "main",
        # smoke 与运行服务共用全局连接，不在临时系统里重复保存 token。
        "mrLabels": ["asterism-smoke"], "gitlabBaseUrl": "", "gitlabToken": "",
    })


def wait_status(work_item_id: str, target: str) -> dict:
    deadline = time.time() + smoke.TIMEOUT_SECONDS
    last: dict = {}
    while time.time() < deadline:
        last = smoke.request("GET", f"/api/v5/work-items/{work_item_id}") or {}
        status = str(last.get("lifecycleStatus", ""))
        if status == target:
            return last
        if status in {"worker_blocked", "validation_failed", "cancelled", "rejected"}:
            smoke.dump_recent_events(work_item_id)
            raise RuntimeError(f"工作项提前进入 {status}")
        time.sleep(2)
    smoke.dump_recent_events(work_item_id)
    raise RuntimeError(f"等待 {target} 超时，最后状态={last.get('lifecycleStatus', 'none')}")


def main() -> int:
    if not all((GITLAB_URL, GITLAB_TOKEN, smoke.ADMIN_PASSWORD, os.getenv("V5_AGENT_API_KEY"))):
        print("SKIP: smoke-gitlab 缺少 GitLab、模型或管理员环境变量", file=sys.stderr)
        return 0

    suffix = str(int(time.time()))
    project_name = f"asterism-smoke-{suffix}"
    system_id = f"smoke-gitlab-{suffix}"
    project = create_project(project_name)
    log(f"临时项目已创建: {project['path_with_namespace']}")
    configure_asterism(system_id, project["path_with_namespace"])
    smoke.wait_system_ready(system_id)

    prd = smoke.prepare_prd(system_id, "把 README 里的 asterism 改成 Asterism smoke", "验收标准：README 必须包含 Asterism smoke。")
    confirmed = smoke.request("POST", f"/api/v5/prd-sessions/{prd['prdId']}/confirm") or {}
    work_item_id = confirmed["workItemId"]
    wait_status(work_item_id, "waiting_owner_approval")
    smoke.request("POST", f"/api/v5/work-items/{work_item_id}/owner-approval")
    wait_status(work_item_id, "activated")
    smoke.request("POST", f"/api/v5/work-items/{work_item_id}/signals/start_modification")
    wait_status(work_item_id, "modification_completed")
    modification = smoke.event_payload(smoke.wait_event(work_item_id, "ModificationCompleted"))
    if "diff --git" not in modification.get("diffPatch", ""):
        raise RuntimeError("ModificationCompleted 没有有效 diff")

    smoke.request("POST", f"/api/v5/work-items/{work_item_id}/signals/patch_apply_approved")
    wait_status(work_item_id, "waiting_merge")
    created = smoke.event_payload(smoke.wait_event(work_item_id, "MergeRequestCreated"))
    merge = gitlab_request("PUT", f"/api/v4/projects/{project['id']}/merge_requests/{created['mrIid']}/merge", {
        "should_remove_source_branch": False,
    })
    if merge.get("state") != "merged":
        raise RuntimeError(f"GitLab MR 未合并: {merge.get('state')}")

    wait_status(work_item_id, "completed")
    smoke.wait_event(work_item_id, "ReleaseCompleted")
    log(f"轮询闭环通过: workItem={work_item_id} mr=!{created['mrIid']}")
    if CLEANUP:
        gitlab_request("DELETE", f"/api/v4/projects/{project['id']}")
        log("临时 GitLab 项目已按显式配置删除")
    else:
        log(f"临时项目保留供审计: {project['path_with_namespace']}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
