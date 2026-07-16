#!/usr/bin/env python3
"""smoke_gitlab 的无网络配置自检。"""

import importlib.util
import os
import sys
import unittest
from pathlib import Path


SCRIPTS = Path(__file__).parent
sys.path.insert(0, str(SCRIPTS))
SPEC = importlib.util.spec_from_file_location("smoke_gitlab", SCRIPTS / "smoke_gitlab.py")
smoke = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(smoke)


class ConfigureAsterismTest(unittest.TestCase):
    def test_configures_gitlab_release_without_webhook(self):
        calls = []
        original_request = smoke.smoke.request
        original_url, original_token = smoke.GITLAB_URL, smoke.GITLAB_TOKEN

        def request(method, path, body=None):
            calls.append((method, path, body))
            if path.endswith("/model-profiles"):
                return {"modelProfiles": [{"id": "mp-1"}]}
            return {}

        try:
            smoke.smoke.request = request
            smoke.GITLAB_URL, smoke.GITLAB_TOKEN = "https://gitlab.example", "test-token"
            os.environ["V5_AGENT_API_KEY"] = "model-key"
            smoke.configure_asterism("sys-1", "group/app")
        finally:
            smoke.smoke.request = original_request
            smoke.GITLAB_URL, smoke.GITLAB_TOKEN = original_url, original_token
            os.environ.pop("V5_AGENT_API_KEY", None)

        git_config = next(body for method, path, body in calls if method == "PUT" and path.endswith("/git-config"))
        self.assertEqual("gitlab", git_config["releaseMode"])
        self.assertEqual("gitlab", git_config["repos"][0]["cloneMode"])
        self.assertEqual("", git_config["gitlabToken"])
        self.assertNotIn("webhook", str(git_config).lower())


if __name__ == "__main__":
    unittest.main()
