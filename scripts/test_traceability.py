"""Testes adversariais do vínculo estruturado. Rodam só no GitHub Actions."""
import copy
import json
import tempfile
import unittest
from contextlib import redirect_stderr
from io import StringIO
from pathlib import Path
from unittest.mock import patch

import check_traceability as trace


class TraceabilityGateTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.good = json.loads(trace.MANIFEST.read_text(encoding="utf-8"))

    def assert_gate(self, payload, expected):
        with tempfile.TemporaryDirectory() as tmp:
            path = Path(tmp) / "manifest.json"
            path.write_text(json.dumps(payload), encoding="utf-8")
            with patch.object(trace, "MANIFEST", path), redirect_stderr(StringIO()):
                self.assertEqual(trace.check(), expected)

    def test_valid_manifest(self):
        self.assert_gate(self.good, 0)

    def test_missing_test_symbol_rejected(self):
        bad = copy.deepcopy(self.good)
        bad["requirements"][0]["test_symbol"] = "test_METHOD_NOT_PRESENT"
        self.assert_gate(bad, 1)

    def test_duplicate_requirement_rejected(self):
        bad = copy.deepcopy(self.good)
        bad["requirements"].append(copy.deepcopy(bad["requirements"][0]))
        self.assert_gate(bad, 1)

    def test_unproved_status_cannot_claim_green(self):
        bad = copy.deepcopy(self.good)
        bad["requirements"][0]["status"] = "implementado e comprovado por Actions"
        self.assert_gate(bad, 1)

    def test_manifest_list_is_rejected_without_crash(self):
        self.assert_gate([], 1)

    def test_device_requires_reason(self):
        bad = copy.deepcopy(self.good)
        bad["requirements"][-1]["reason"] = ""
        self.assert_gate(bad, 1)


if __name__ == "__main__":
    unittest.main()
