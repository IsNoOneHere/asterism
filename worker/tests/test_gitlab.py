import asyncio
import stat
from types import SimpleNamespace

from asterism_worker.activities.gitlab import _ensure_merge_request, _gitlab_client, _push_branch


class FakeResponse:
    def __init__(self, body, status_code=200):
        self.body = body
        self.status_code = status_code

    def json(self):
        return self.body

    def raise_for_status(self):
        if self.status_code >= 400:
            raise RuntimeError(self.status_code)


class FakeClient:
    def __init__(self, responses):
        self.responses = list(responses)
        self.posts = 0

    async def __aenter__(self):
        return self

    async def __aexit__(self, *_):
        return None

    async def get(self, *_args, **_kwargs):
        return self.responses.pop(0)

    async def post(self, *_args, **_kwargs):
        self.posts += 1
        return self.responses.pop(0)


def test_existing_open_merge_request_is_reused(monkeypatch):
    client = FakeClient([FakeResponse([{"iid": 7, "web_url": "https://gitlab/mr/7", "state": "opened"}])])
    monkeypatch.setattr("asterism_worker.activities.gitlab.httpx.AsyncClient", lambda **_kwargs: client)

    result = asyncio.run(_ensure_merge_request(
        "https://gitlab", "secret", "group/api", "wi/wi-1", "main", "title", "body", [], "backend",
    ))

    assert result.repo == "backend"
    assert result.mr_iid == 7
    assert client.posts == 0


def test_missing_merge_request_is_created_once(monkeypatch):
    client = FakeClient([
        FakeResponse([]),
        FakeResponse({"iid": 8, "web_url": "https://gitlab/mr/8", "state": "opened"}, 201),
    ])
    monkeypatch.setattr("asterism_worker.activities.gitlab.httpx.AsyncClient", lambda **_kwargs: client)

    result = asyncio.run(_ensure_merge_request(
        "https://gitlab", "secret", "group/api", "wi/wi-1", "main", "title", "body", ["asterism"], "backend",
    ))

    assert result.mr_iid == 8
    assert client.posts == 1


def test_gitlab_api_only_bypasses_environment_proxy_for_private_endpoint(monkeypatch):
    options: list[dict] = []

    def client(**kwargs):
        options.append(kwargs)
        return SimpleNamespace()

    monkeypatch.setattr("asterism_worker.activities.gitlab.httpx.AsyncClient", client)

    _gitlab_client("http://10.96.230.251:8080", "secret")
    _gitlab_client("https://gitlab.com", "secret")

    assert [item["trust_env"] for item in options] == [False, True]


def test_existing_branch_uses_force_with_lease_and_cleans_credentials(monkeypatch, tmp_path):
    token = "secret-token-value"
    workspace = tmp_path / "case-backend" / "repo"
    workspace.mkdir(parents=True)
    commands: list[list[str]] = []
    remote_environments: list[dict[str, str]] = []

    def fake_run(command, **_kwargs):
        commands.append(command)
        helpers = [value for value in command if value.startswith("credential.helper=")]
        if helpers:
            remote_environments.append(_kwargs["env"])
            credentials = workspace.parent / ".git-credentials"
            # 远端命令执行期间凭证存在且仅当前用户可读写。
            assert helpers[0].endswith(str(credentials))
            assert stat.S_IMODE(credentials.stat().st_mode) == 0o600
        if "ls-remote" in command:
            stdout = "abc123 refs/heads/wi/wi-1\n"
        elif command[-1] == "refs/heads/wi/wi-1":
            stdout = "def456\n"
        elif command[-1] == "refs/heads/wi/wi-1^{tree}":
            stdout = "new-tree\n"
        elif command[-1] == "FETCH_HEAD^{tree}":
            stdout = "old-tree\n"
        else:
            stdout = ""
        return SimpleNamespace(stdout=stdout)

    monkeypatch.setattr("asterism_worker.activities.gitlab.subprocess.run", fake_run)
    monkeypatch.setenv("NO_PROXY", "localhost")
    monkeypatch.setenv("no_proxy", "temporal")

    _push_branch(workspace, "http://10.96.230.251/group/api.git", token, "wi/wi-1", "abc123")

    assert any("--force-with-lease=refs/heads/wi/wi-1:abc123" in command for command in commands)
    assert remote_environments
    assert all(env["NO_PROXY"] == "localhost,10.96.230.251" for env in remote_environments)
    assert all(env["no_proxy"] == "temporal,10.96.230.251" for env in remote_environments)
    assert token not in str(commands)
    assert not (workspace.parent / ".git-credentials").exists()
