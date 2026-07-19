#!/usr/bin/env python3
"""smoke_real 的无网络协议自检。"""

import importlib.util
import unittest
from pathlib import Path


SPEC = importlib.util.spec_from_file_location("smoke_real", Path(__file__).with_name("smoke_real.py"))
smoke = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(smoke)


class PreparePrdTest(unittest.TestCase):
    def setUp(self):
        self.original_request = smoke.request
        self.original_wait = smoke.wait_prd_turn
        self.calls = []
        smoke.request = lambda method, path, body=None: self.calls.append((method, path, body)) or {}

    def tearDown(self):
        smoke.request = self.original_request
        smoke.wait_prd_turn = self.original_wait

    def test_accepts_first_turn_ready(self):
        smoke.wait_prd_turn = lambda _: {"prdId": "prd-1", "status": "waiting_user_confirm", "missingFields": []}

        result = smoke.prepare_prd("sys-1", "goal", "criteria")

        self.assertEqual("prd-1", result["prdId"])
        self.assertEqual(1, len(self.calls))

    def test_only_sends_second_turn_for_missing_acceptance_criteria(self):
        turns = iter([
            {"prdId": "prd-1", "status": "need_clarification", "missingFields": ["acceptance_criteria"]},
            {"prdId": "prd-1", "status": "waiting_user_confirm", "missingFields": []},
        ])
        smoke.wait_prd_turn = lambda _: next(turns)

        smoke.prepare_prd("sys-1", "goal", "criteria")

        self.assertEqual(2, len(self.calls))
        self.assertEqual({"prdId": "prd-1", "content": "criteria"}, self.calls[1][2])

    def test_configures_only_claude_sdk_team_developer(self):
        original_request = smoke.request
        calls = []
        try:
            smoke.request = lambda method, path, body=None: (
                calls.append((method, path, body))
                or ({"modelProfiles": [{"id": "mp-1"}]} if path.endswith("/model-profiles") else {})
            )
            profile_id = smoke.configure_developer("sys-1")
        finally:
            smoke.request = original_request

        self.assertEqual("mp-1", profile_id)
        update = next(body for method, path, body in calls if method == "PATCH" and path.endswith("/agents/developer"))
        self.assertEqual("claude_sdk_team", update["engine"])
        self.assertEqual([], update["pathScope"])


if __name__ == "__main__":
    unittest.main()
