import ipaddress
import os
import re
from collections.abc import Mapping
from urllib.parse import quote, urlsplit


_CREDENTIAL_URL = re.compile(r"(?i)(https?://)([^/\s:@]+):([^@\s/]+)@")
_SENSITIVE_VALUE = re.compile(
    r"(?i)((?:private[-_ ]token|access[-_ ]token|oauth[-_ ]token|authorization)\s*[:=]\s*)[^\s,;]+",
)


def endpoint_host(url: str) -> str:
    """只返回运行时 URL 的主机部分，不把凭据或路径带入日志。"""

    return (urlsplit(url).hostname or "").rstrip(".").lower()


def is_private_endpoint(url: str) -> bool:
    """识别不应经过宿主代理的本机、私网与链路本地端点。"""

    host = endpoint_host(url)
    if not host:
        return False
    if host == "localhost" or host.endswith((".localhost", ".local", ".internal")):
        return True
    try:
        address = ipaddress.ip_address(host)
    except ValueError:
        # 容器网络中的单标签服务名没有公网路由。
        return "." not in host
    return address.is_private or address.is_loopback or address.is_link_local


def subprocess_environment(url: str, environ: Mapping[str, str] | None = None) -> dict[str, str]:
    """为私网 Git 端点动态补齐大小写 NO_PROXY，且不修改进程全局环境。"""

    env = dict(os.environ if environ is None else environ)
    env["GIT_TERMINAL_PROMPT"] = "0"
    if not is_private_endpoint(url):
        return env
    host = endpoint_host(url)
    for name in ("NO_PROXY", "no_proxy"):
        values = [value.strip() for value in env.get(name, "").split(",") if value.strip()]
        if host not in {value.lower() for value in values}:
            values.append(host)
        env[name] = ",".join(values)
    return env


def redact_subprocess_stderr(stderr: str | None, *secrets: str) -> str:
    """保留可诊断的 Git stderr，同时移除原始与 URL 编码凭据。"""

    value = str(stderr or "").strip()
    for secret in filter(None, secrets):
        value = value.replace(secret, "[REDACTED]")
        value = value.replace(quote(secret, safe=""), "[REDACTED]")
    value = _CREDENTIAL_URL.sub(r"\1***:***@", value)
    value = _SENSITIVE_VALUE.sub(r"\1[REDACTED]", value)
    return value[-2000:] if value else "未返回 stderr"
