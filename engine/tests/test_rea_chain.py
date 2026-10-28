"""T20 (c): cadeia REA no Termux com executor falso, checkpoint por etapa e tools/list do MCP."""
import json
import sys
import unittest
import unittest.mock

from aura_engine import adapters
from aura_engine.core import Engine
from test_lote_c import Base
from consent_helper import allow_installs

STEPS = ["node", "jdk", "jadx", "apktool", "adb", "rea", "mcp"]
MARK = {"node": "nodejs-lts", "jdk": "openjdk-21", "jadx": "curl", "apktool": "apktool",
        "adb": "android-tools", "rea": "rea-agents@6.8.0"}
SETUP = {"kind": "rea", "action": "setup"}
FAKE_MCP = """
import json, sys
TOOLS = [{"name": "list_adb_devices", "description": "Lists devices."},
         {"name": "install_adb_package", "description": "x", "mutatesDevice": True},
         {"name": "push_adb_file", "description": "x", "annotations": {"mutatesDevice": True}},
         {"name": "start_adb_app", "description": "A device-mutating tool."}]
for line in sys.stdin:
    msg = json.loads(line)
    result = None
    if msg.get("method") == "initialize":
        result = {"protocolVersion": "2025-06-18", "capabilities": {"tools": {}},
                  "serverInfo": {"name": "fake-rea", "version": "0"}}
    elif msg.get("method") == "tools/list":
        result = {"tools": TOOLS}
    if result is not None:
        print(json.dumps({"jsonrpc": "2.0", "id": msg["id"], "result": result}), flush=True)
"""


class Killed(BaseException):
    """Simula o Android matando o processo no meio de um passo."""


class Fake:
    """Executor falso: guarda cada comando e falha (ou 'mata') no passo pedido."""

    def __init__(self, fail=None, kill=None):
        self.calls, self.fail, self.kill = [], fail, kill

    def __call__(self, argv, cwd, engine, timeout=180, binary=False):
        self.calls.append(list(argv))
        for step, mark in MARK.items():
            if any(mark in part for part in argv):
                if step == self.kill:
                    raise Killed()
                if step == self.fail:
                    raise adapters.ActionError("process_failed", "%s exited 1: sem rede" % argv[0])
        return {"exit_code": 0, "stdout": "", "stderr": "", "truncated": False}

    def ran(self, step):
        return any(MARK[step] in part for argv in self.calls for part in argv)


class ReaChain(Base):
    def setUp(self):
        super().setUp()
        allow_installs(self)
        script = self.root / "fake_mcp.py"
        script.write_text(FAKE_MCP, "utf-8")
        self.mcp = [sys.executable, str(script)]

    def run_job(self, fake, request, engine=None, broken_mcp=False):
        engine = engine or self.engine
        job = engine.submit(request)
        if job["status"] == "awaiting_approval":
            engine.approve(job["id"])
        mcp = [sys.executable, "-c", "raise SystemExit(3)"] if broken_mcp else self.mcp
        with unittest.mock.patch("aura_engine.adapters.run_process", fake), \
                unittest.mock.patch("aura_engine.rea_chain.MCP_ARGV", mcp):
            return engine.run_once()

    def test_setup_installs_every_tool_in_order_with_a_checkpoint(self):
        fake = Fake()
        done = self.run_job(fake, SETUP)
        self.assertEqual(done["status"], "completed", done)
        self.assertEqual([a[3:] for a in fake.calls if a[:2] == ["pkg", "install"]],
                         [["nodejs-lts", "npm"], ["openjdk-21"], ["apktool"], ["android-tools"]])
        self.assertIn(["npm", "install", "-g", "rea-agents@6.8.0"], fake.calls)
        curl = next(a for a in fake.calls if a[0] == "curl")
        self.assertIn("https://github.com/1013503897/jadx-headless-mcp/releases/download/"
                      "v0.7.1/jadx-headless-mcp-0.7.1-all.jar", curl)
        check = next(a for a in fake.calls if a[0] == "sh")  # o jar so vale se o SHA-256 bater
        self.assertIn("6e5eacf500b64292bfb73c49797c1958f6ee44646e43e868039ae7feb573ff75", check[-1])
        saved = json.loads((self.home / "rea" / "checkpoint.json").read_text("utf-8"))
        self.assertEqual(sorted(saved["done"]), sorted(STEPS))
        events = [(e["payload"]["name"], e["payload"]["state"]) for e in self.events("tool.install")]
        self.assertEqual([name for name, state in events if state == "ready"], STEPS)
        self.assertIn(("node", "installing"), events)

    def test_a_failure_at_any_step_resumes_from_that_step(self):
        for index, step in enumerate(STEPS):
            with self.subTest(step=step):
                engine = Engine(home=self.root / ("home-%d" % index), auto_worker=False)
                self.addCleanup(engine.close)
                bad = self.run_job(Fake(fail=step), SETUP, engine, broken_mcp=(step == "mcp"))
                self.assertEqual(bad["status"], "failed", bad)
                self.assertEqual(bad["error"]["code"], "rea_step_failed")
                self.assertIn(step, bad["error"]["message"])
                file = engine.home / "rea" / "checkpoint.json"
                before = json.loads(file.read_text("utf-8"))["done"] if file.exists() else {}
                self.assertEqual(sorted(before), sorted(STEPS[:index]))
                retry = Fake()
                ok = self.run_job(retry, SETUP, engine)
                self.assertEqual(ok["status"], "completed", ok)
                for earlier in STEPS[:index]:
                    if earlier in MARK:
                        self.assertFalse(retry.ran(earlier), earlier)  # pulou o que ja tinha checkpoint

    def test_killed_midway_then_a_new_engine_continues(self):
        with self.assertRaises(Killed):
            self.run_job(Fake(kill="apktool"), SETUP)
        second = Engine(home=self.home, auto_worker=False)
        self.addCleanup(second.close)
        self.assertEqual(self.events("engine.recovered")[-1]["payload"], {"interrupted": 1})
        retry = Fake()
        done = self.run_job(retry, SETUP, second)
        self.assertEqual(done["status"], "completed", done)
        self.assertFalse(retry.ran("node") or retry.ran("jdk") or retry.ran("jadx"))
        self.assertTrue(retry.ran("apktool"))

    def test_tools_list_comes_from_the_mcp_and_device_mutating_tools_are_not_read(self):
        done = self.run_job(Fake(), {"kind": "rea", "action": "tools"})
        self.assertEqual(done["status"], "completed", done)
        self.assertEqual({t["name"]: t["risk"] for t in done["result"]["tools"]},
                         {"list_adb_devices": "read", "install_adb_package": "write",
                          "push_adb_file": "write", "start_adb_app": "write"})

    def test_fidelity_is_only_reported_after_a_real_test(self):
        ask = {"kind": "rea", "action": "measure", "reversal_pct": 68, "rebuild_pct": 35, "fidelity_pct": 91}
        before = self.run_job(Fake(), ask)
        self.assertEqual(before["result"], {"reversalPct": 68, "rebuildPct": 35, "fidelityPct": None})
        real = dict(ask, real_test={"device": "emulator-5556", "flows_passed": 9, "flows_total": 11})
        after = self.run_job(Fake(), real)
        self.assertEqual(after["result"]["fidelityPct"], 91)
        event = self.events("reform.progress")[-1]["payload"]
        self.assertEqual((event["reversalPct"], event["rebuildPct"], event["fidelityPct"]), (68, 35, 91))
        bad = self.run_job(Fake(), dict(ask, reversal_pct=150))
        self.assertEqual((bad["status"], bad["error"]["code"]), ("failed", "invalid_request"))

    def test_risk_of_each_rea_action(self):
        risks = {a: self.engine.policy.classify({"kind": "rea", "action": a})
                 for a in ("setup", "tools", "measure", "format")}
        self.assertEqual(risks, {"setup": "write", "tools": "read", "measure": "read", "format": "critical"})
        self.assertEqual(self.engine.submit(SETUP)["status"], "awaiting_approval")  # instalar pede 1 toque


if __name__ == "__main__":
    unittest.main()
