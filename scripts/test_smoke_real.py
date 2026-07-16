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

    def test_legacy_create_config_uses_selected_provider(self):
        original = smoke.MODEL_PROVIDER
        try:
            smoke.MODEL_PROVIDER = "anthropic"
            config = smoke.legacy_model_config("deepseek-v4-pro[1m]", "https://api.deepseek.com/anthropic", "key")
        finally:
            smoke.MODEL_PROVIDER = original

        self.assertEqual("anthropic", config["provider"])


if __name__ == "__main__":
    unittest.main()
