"""Endpoints HTTP v1 da reforma: escopo, passos com ledger, medidores calculados, verify honesto."""
import json
import os
import sys
import unittest
import unittest.mock

from test_lote_c import Base
from test_rea_chain import Fake

FAKE_MCP = """
import json, sys
MUTATE = "mutate_inventory" in sys.argv
TOOLS = [{"name": "inventory_artifact", "description": "x", "mutatesDevice": MUTATE},
         {"name": "build_reconstruction_obligation_ledger", "description": "x"},
         {"name": "evaluate_reconstruction_coverage", "description": "x"},
         {"name": "install_adb_package", "description": "x", "mutatesDevice": True}]
OUT = {"inventory_artifact": {"evidence_id": "ev-1", "files": 12},
       "build_reconstruction_obligation_ledger": {"obligations": 10},
       "evaluate_reconstruction_coverage": {"satisfied": 7, "total": 10}}
for line in sys.stdin:
    msg = json.loads(line)
    result = None
    if msg.get("method") == "initialize":
        result = {"protocolVersion": "2025-06-18", "capabilities": {"tools": {}},
                  "serverInfo": {"name": "fake-rea", "version": "0"}}
    elif msg.get("method") == "tools/list":
        result = {"tools": TOOLS}
    elif msg.get("method") == "tools/call":
        with open(sys.argv[1], "a") as log:
            log.write(msg["params"]["name"] + "\\n")
        result = {"content": [{"type": "text", "text": "ok"}],
                  "structuredContent": OUT[msg["params"]["name"]]}
    if result is not None:
        print(json.dumps({"jsonrpc": "2.0", "id": msg["id"], "result": result}), flush=True)
"""


class ReformHttp(Base):
    def setUp(self):
        super().setUp()
        script = self.root / "fake_mcp.py"
        script.write_text(FAKE_MCP, "utf-8")
        self.script, self.calls_log = script, self.root / "mcp_calls.txt"
        self.apk = self.root / "app.apk"
        self.apk.write_bytes(b"PK\x03\x04")
        self.use_mcp()

    def use_mcp(self, *extra):
        patch = unittest.mock.patch("aura_engine.rea_chain.MCP_ARGV",
                                    [sys.executable, str(self.script), str(self.calls_log), *extra])
        patch.start()
        self.addCleanup(patch.stop)

    def mcp_calls(self):
        return self.calls_log.read_text("utf-8").split() if self.calls_log.exists() else []

    def create(self, **changes):
        body = dict(apk_path=str(self.apk), mode="rebuild", goal=95)
        body.update(changes)
        return self.call("POST", "/v1/reform", body)

    def project(self):
        code, body = self.create()
        self.assertEqual(code, 201, body)
        return body["id"]

    def step(self, rid, name, body=None):
        return self.call("POST", "/v1/reform/%s/steps/%s" % (rid, name), body or {})

    def kinds(self, rid):
        code, body = self.call("GET", "/v1/reform/%s/ledger" % rid)
        self.assertEqual(code, 200, body)
        return [e["kind"] for e in body["entries"]]

    def test_every_route_needs_the_token(self):
        for method, path in (("POST", "/v1/reform"), ("GET", "/v1/reform/abc"),
                             ("GET", "/v1/reform/abc/ledger"), ("POST", "/v1/reform/abc/steps/inventory"),
                             ("POST", "/v1/reform/abc/verify")):
            self.assertEqual(self.call(method, path, {}, auth=False)[0], 401, path)

    def test_create_locks_the_scope_in_a_ledger_under_home(self):
        code, body = self.create()
        self.assertEqual(code, 201, body)
        ledger = self.home / "reform" / body["id"] / "ledger.jsonl"
        self.assertTrue(ledger.exists())
        self.assertEqual((body["scope"]["mode"], body["scope"]["goal"], body["scope"]["seq"]),
                         ("rebuild", 95, 1))
        self.assertEqual(self.kinds(body["id"]), ["scope_locked"])

    def test_create_refuses_bad_scope_and_leaves_nothing_behind(self):
        for changes in (dict(goal=150), dict(mode=""), dict(apk_path=str(self.root / "nao-existe.apk")),
                        dict(apk_path=None)):
            code, body = self.create(**changes)
            self.assertEqual((code, body["error"]["code"]), (400, "invalid_request"), changes)
            self.assertTrue(body["error"]["message"])
        self.assertEqual(list((self.home / "reform").glob("*")) if (self.home / "reform").exists() else [], [])

    def test_unknown_project_or_step(self):
        for rid in ("zzz", "../../etc", "0" * 12):
            self.assertEqual(self.call("GET", "/v1/reform/" + rid)[0], 404, rid)
        rid = self.project()
        code, body = self.step(rid, "inexistente")
        self.assertEqual((code, body["error"]["code"]), (404, "not_found"))

    def test_fresh_project_has_no_numbers_and_fidelity_is_null(self):
        rid = self.project()
        code, body = self.call("GET", "/v1/reform/" + rid)
        self.assertEqual(code, 200, body)
        self.assertTrue(body["chain_ok"])
        self.assertEqual((body["reversal"], body["rebuild"], body["fidelity"]), (None, None, None))
        self.assertEqual(body["steps_done"], [])

    def test_inventory_step_records_tool_call_evidence_and_step_done(self):
        rid = self.project()
        code, body = self.step(rid, "inventory")
        self.assertEqual(code, 200, body)
        self.assertEqual(self.kinds(rid), ["scope_locked", "tool_call", "evidence", "step_done"])
        _, ledger = self.call("GET", "/v1/reform/%s/ledger" % rid)
        call, evidence = ledger["entries"][1]["payload"], ledger["entries"][2]["payload"]
        self.assertEqual((call["tool"], call["mutates_device"], call["cost_class"], call["risk"]),
                         ("inventory_artifact", False, "free", "read"))
        self.assertEqual(len(call["input_hash"]), 64)
        self.assertEqual(evidence["evidence_id"], "ev-1")
        self.assertEqual(len(evidence["sha256"]), 64)
        self.assertEqual(self.call("GET", "/v1/reform/" + rid)[1]["steps_done"], ["inventory"])

    def test_repeating_a_step_with_same_key_does_not_rerun_or_duplicate(self):
        rid = self.project()
        first = self.step(rid, "inventory", {"idempotency_key": "k1"})
        before = self.kinds(rid)
        again = self.step(rid, "inventory", {"idempotency_key": "k1"})
        self.assertEqual((first[0], again[0]), (200, 200))
        self.assertTrue(again[1]["already_done"])
        self.assertEqual(self.kinds(rid), before)
        self.assertEqual(self.mcp_calls(), ["inventory_artifact"])

    def test_coverage_step_computes_reversal_from_the_ledger_and_emits_progress(self):
        rid = self.project()
        code, body = self.step(rid, "coverage")
        self.assertEqual(code, 200, body)
        self.assertEqual(self.mcp_calls(), ["build_reconstruction_obligation_ledger",
                                            "evaluate_reconstruction_coverage"])
        summary = self.call("GET", "/v1/reform/" + rid)[1]
        self.assertEqual(summary["reversal"]["pct"], 70)
        self.assertIsNone(summary["fidelity"])
        self.assertEqual(self.events("reform.progress")[-1]["payload"]["reversalPct"], 70)

    def test_rebuild_step_records_progress_and_rejects_bad_counts(self):
        rid = self.project()
        code, body = self.step(rid, "rebuild", {"ready": 3, "total": 12})
        self.assertEqual(code, 200, body)
        self.assertEqual(self.call("GET", "/v1/reform/" + rid)[1]["rebuild"]["pct"], 25)
        code, body = self.step(rid, "rebuild", {"ready": 9, "total": 3, "idempotency_key": "x"})
        self.assertEqual((code, body["error"]["code"]), (400, "invalid_request"))

    def test_device_mutating_tool_runs_and_is_recorded(self):
        # Decisao do dono (docs/AUDITORIA_AUTONOMIA.md): sem recusas. A chamada roda e o ledger registra.
        self.use_mcp("mutate_inventory")
        rid = self.project()
        code, body = self.step(rid, "inventory")
        self.assertEqual(code, 200, body)
        self.assertEqual(self.kinds(rid), ["scope_locked", "tool_call", "evidence", "step_done"])
        _, ledger = self.call("GET", "/v1/reform/%s/ledger" % rid)
        self.assertEqual((ledger["entries"][1]["payload"]["mutates_device"],
                          ledger["entries"][1]["payload"]["risk"]), (True, "write"))
        self.assertEqual(self.mcp_calls(), ["inventory_artifact"])

    def test_tools_step_runs_setup_through_the_fake_executor(self):
        rid = self.project()
        fake = Fake()
        with unittest.mock.patch("aura_engine.adapters.run_process", fake):
            code, body = self.step(rid, "tools")
        self.assertEqual(code, 200, body)
        self.assertTrue(fake.ran("rea"))
        self.assertIn("tools", self.call("GET", "/v1/reform/" + rid)[1]["steps_done"])

    def test_tools_step_failure_names_the_step_and_does_not_mark_done(self):
        rid = self.project()
        with unittest.mock.patch("aura_engine.adapters.run_process", Fake(fail="jdk")):
            code, body = self.step(rid, "tools")
        self.assertEqual((code, body["error"]["code"]), (502, "rea_step_failed"))
        self.assertEqual(self.call("GET", "/v1/reform/" + rid)[1]["steps_done"], [])

    def test_ledger_since_returns_only_newer_entries(self):
        rid = self.project()
        self.step(rid, "inventory")
        code, body = self.call("GET", "/v1/reform/%s/ledger?since=2" % rid)
        self.assertEqual([e["seq"] for e in body["entries"]], [3, 4])
        self.assertEqual(body["last_seq"], 4)
        self.assertEqual(self.call("GET", "/v1/reform/%s/ledger?since=abc" % rid)[0], 400)

    def test_tampered_ledger_is_reported_invalid_not_trusted(self):
        rid = self.project()
        self.step(rid, "coverage")
        path = self.home / "reform" / rid / "ledger.jsonl"
        path.write_text(path.read_text("utf-8").replace('"satisfied":7', '"satisfied":10'), "utf-8")
        body = self.call("GET", "/v1/reform/" + rid)[1]
        self.assertFalse(body["chain_ok"])
        self.assertIsNone(body["reversal"])

    def test_verify_needs_a_real_test_run_id(self):
        rid = self.project()
        for body in ({}, {"passed": 9, "total": 11}, {"test_run_id": " ", "passed": 9, "total": 11}):
            code, err = self.call("POST", "/v1/reform/%s/verify" % rid, body)
            self.assertEqual((code, err["error"]["code"]), (400, "invalid_request"), body)
        self.assertIsNone(self.call("GET", "/v1/reform/" + rid)[1]["fidelity"])
        self.assertNotIn("fidelity_measured", self.kinds(rid))

    def test_verify_records_fidelity_measured_and_points_to_its_seq(self):
        rid = self.project()
        body = {"test_run_id": "run-42", "passed": 9, "total": 11}
        code, out = self.call("POST", "/v1/reform/%s/verify" % rid, body)
        self.assertEqual(code, 200, out)
        summary = self.call("GET", "/v1/reform/" + rid)[1]
        self.assertAlmostEqual(summary["fidelity"]["pct"], 100 * 9 / 11)
        self.assertEqual(summary["fidelity"]["seq"], out["seq"])
        again = self.call("POST", "/v1/reform/%s/verify" % rid, body)[1]
        self.assertEqual(again["seq"], out["seq"])  # mesmo teste = mesma entrada
        self.assertEqual(self.kinds(rid).count("fidelity_measured"), 1)

    def test_model_step_blocks_paid_and_records_nothing(self):
        rid = self.project()
        code, body = self.step(rid, "model", {"tokens": 100})
        self.assertEqual((code, body["error"]["code"]), (409, "cost_blocked"))
        self.assertNotIn("model_call", self.kinds(rid))

    def test_model_step_uses_agy_as_subscription(self):
        self.fake_cli("agy")
        rid = self.project()
        code, body = self.step(rid, "model", {"tokens": 321})
        self.assertEqual(code, 200, body)
        calls = self.call("GET", "/v1/reform/" + rid)[1]["model_calls"]
        self.assertEqual([(c["payload"]["provider"], c["payload"]["cost_class"], c["payload"]["tokens"])
                          for c in calls], [("agy", "subscription", 321)])

    def test_model_step_gemini_needs_free_key_and_approval(self):
        env = {"GEMINI_API_KEY": "k", "AURA_GEMINI_KEY_FREE": "1"}
        with unittest.mock.patch.dict(os.environ, env):
            rid = self.project()
            code, body = self.step(rid, "model", {"tokens": 5})
            self.assertEqual((code, body["error"]["code"]), (409, "cost_blocked"))
            code, body = self.step(rid, "model", {"tokens": 5, "approved": True, "idempotency_key": "b"})
            self.assertEqual(code, 200, body)
        calls = self.call("GET", "/v1/reform/" + rid)[1]["model_calls"]
        self.assertEqual([(c["payload"]["provider"], c["payload"]["cost_class"]) for c in calls],
                         [("gemini_api", "free")])
        self.assertIn("approval", self.kinds(rid))
        with unittest.mock.patch.dict(os.environ, {"GEMINI_API_KEY": "k"}):  # chave sem prova de plano gratis
            code, body = self.step(rid, "model", {"tokens": 5, "approved": True, "idempotency_key": "c"})
            self.assertEqual((code, body["error"]["code"]), (409, "cost_blocked"))


if __name__ == "__main__":
    unittest.main()
