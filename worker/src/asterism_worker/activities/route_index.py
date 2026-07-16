import logging
import re
from pathlib import Path

import httpx
from temporalio import activity

from asterism_worker.config.settings import load_settings
from asterism_worker.contracts import KnowledgeCandidate, RouteIndexInput
from asterism_worker.repo_source import cleanup_repo_workspace, prepare_repo_workspace

log = logging.getLogger(__name__)

WEB_PATH = re.compile(r"\bpath\s*[:=]\s*['\"]([^'\"]+)['\"]")
CHINESE_TEXT = re.compile(r"['\"`]([^'\"`\n]{0,80}[\u4e00-\u9fff][^'\"`\n]{0,80})['\"`]")
SPRING_METHOD = re.compile(
    r"@(Get|Post|Put|Delete|Patch)Mapping(?:\s*\(\s*(?:(?:value|path)\s*=\s*)?['\"]([^'\"]*)['\"])?",
    re.MULTILINE,
)
SPRING_PREFIX = re.compile(r"@RequestMapping\s*\(\s*(?:(?:value|path)\s*=\s*)?['\"]([^'\"]+)['\"]")
FASTAPI_ROUTE = re.compile(r"@\w+\.(get|post|put|delete|patch)\s*\(\s*['\"]([^'\"]+)['\"]", re.IGNORECASE)
SKIPPED_PARTS = {".git", "node_modules", "target", "build", "dist", ".venv"}


def extract_route_candidates(repo_path: str, repo_id: str = "main") -> list[dict]:
    """轻量提取常见框架路由；候选仍需管理员审批后才可参与匹配。"""

    root = Path(repo_path).resolve()
    if not root.is_dir():
        raise RuntimeError(f"repo path does not exist: {repo_path}")
    candidates: dict[str, KnowledgeCandidate] = {}
    for path in root.rglob("*"):
        if path.is_symlink() or not path.is_file() or SKIPPED_PARTS.intersection(path.parts):
            continue
        if path.suffix.lower() not in {".js", ".jsx", ".ts", ".tsx", ".vue", ".java", ".py"}:
            continue
        try:
            content = path.read_text(encoding="utf-8")
        except (OSError, UnicodeDecodeError):
            continue
        relative = path.relative_to(root).as_posix()
        if path.suffix.lower() in {".js", ".jsx", ".ts", ".tsx", ".vue"}:
            anchors = _visible_text(content)
            for route in WEB_PATH.findall(content):
                _add_page(candidates, route, anchors, relative, repo_id)
        elif path.suffix.lower() == ".java":
            class_position = content.find(" class ")
            class_header = content[:class_position] if class_position >= 0 else ""
            prefix_match = SPRING_PREFIX.search(class_header)
            prefix = prefix_match.group(1) if prefix_match else ""
            for method, route in SPRING_METHOD.findall(content):
                full_route = _join_route(prefix, route)
                endpoint = f"{method.upper()} {full_route}"
                _add_api(candidates, full_route, endpoint, relative, repo_id)
        elif path.suffix.lower() == ".py":
            for method, route in FASTAPI_ROUTE.findall(content):
                endpoint = f"{method.upper()} {route}"
                _add_api(candidates, route, endpoint, relative, repo_id)
    return [candidate.model_dump() for candidate in candidates.values()]


@activity.defn
async def index_system_routes(request: dict) -> list[dict]:
    parsed = RouteIndexInput.model_validate(request)
    settings = load_settings()
    result = []
    for repo in parsed.effective_repos():
        temporary = repo.clone_mode == "gitlab"
        workspace = (await prepare_repo_workspace(repo, parsed.system_id, settings)
                     if temporary else Path(repo.local_path))
        try:
            result.extend(extract_route_candidates(str(workspace), repo.repo_id))
        finally:
            if temporary:
                cleanup_repo_workspace(workspace)
    log.info("系统路由索引已生成", extra={"system_id": parsed.system_id,
                                         "repo_count": len(parsed.effective_repos()),
                                         "candidate_count": len(result)})
    return result


@activity.defn
async def send_knowledge_candidates(request: dict) -> None:
    settings = load_settings()
    system_id = request["system_id"]
    url = f"{settings.control_plane_url.rstrip('/')}/api/v5/internal/systems/{system_id}/knowledge/candidates"
    headers = {"Authorization": f"Bearer {settings.worker_callback_token}"}
    async with httpx.AsyncClient(timeout=20) as client:
        response = await client.post(url, json={"entries": request.get("entries", [])}, headers=headers)
        response.raise_for_status()
    log.info("系统路由 candidate 已回调", extra={"system_id": system_id})


def _visible_text(content: str) -> list[str]:
    return list(dict.fromkeys(value.strip() for value in CHINESE_TEXT.findall(content) if value.strip()))[:20]


def _add_page(candidates: dict[str, KnowledgeCandidate], route: str, anchors: list[str], source: str,
              repo_id: str) -> None:
    candidate = candidates.get(route)
    if candidate is None:
        candidates[route] = KnowledgeCandidate(
            repo=repo_id, kind="page", title=_route_title(route), anchorTexts=[_route_title(route), route, *anchors],
            routePath=route, codeRefs=[source], sourceRef=route,
        )
        return
    candidate.kind = "page"
    candidate.title = _route_title(route)
    candidate.anchorTexts = list(dict.fromkeys([*candidate.anchorTexts, *anchors]))
    candidate.codeRefs = list(dict.fromkeys([*candidate.codeRefs, source]))


def _add_api(candidates: dict[str, KnowledgeCandidate], route: str, endpoint: str, source: str,
             repo_id: str) -> None:
    candidate = candidates.get(route)
    if candidate is None:
        candidates[route] = KnowledgeCandidate(
            repo=repo_id, kind="api", title=endpoint, anchorTexts=[_route_title(route), route], routePath=route,
            apiEndpoints=[endpoint], codeRefs=[source], sourceRef=route,
        )
        return
    candidate.apiEndpoints = list(dict.fromkeys([*candidate.apiEndpoints, endpoint]))
    candidate.codeRefs = list(dict.fromkeys([*candidate.codeRefs, source]))


def _route_title(route: str) -> str:
    segment = next((value for value in reversed(route.strip("/").split("/")) if value and not value.startswith(":") and not value.startswith("{")), "首页")
    return segment.replace("-", " ").replace("_", " ")


def _join_route(prefix: str, route: str) -> str:
    value = "/".join(part.strip("/") for part in (prefix, route) if part.strip("/"))
    return "/" + value if value else "/"
