import json
import pathlib
import tempfile
import threading
import unittest
import unittest.mock
import urllib.error
import urllib.request

from aura_engine.core import Engine
from aura_engine.security import RiskPolicy, resolve_inside
from aura_engine.server import make_server


class EngineContracts(unittest.TestCase):
    def setUp(self):
        temp = tempfile.TemporaryDirectory()
        self.addCleanup(temp.cleanup)
        self.home = pathlib.Path(temp.name)
        self.engine = Engine(home=self.home, auto_worker=False)
        self.addCleanup(self.engine.close)

    def test_HTTP_approval_and_cancel_without_body(self):
        server = make_server(self.engine, "127.0.0.1", 0)
        self.addCleanup(server.server_close)
        t = threading.Thread(target=server.serve_forever, daemon=True)
        t.start()
        self.addCleanup(server.shutdown)
        j = self.engine.submit({"kind": "shell", "argv": ["echo", "hello"]})
        base = "http://127.0.0.1:%d/v1/jobs/" % server.server_port + j["id"]
        head = {"Authorization": "Bearer " + self.engine.token}
        approve = urllib.request.Request(base + "/approve", data=b"", headers=head, method="POST")
        with urllib.request.urlopen(approve, timeout=3) as r:
            self.assertEqual(json.load(r)["status"], "queued")
        cancel = urllib.request.Request(base + "/cancel", data=b"", headers=head, method="POST")
        with urllib.request.urlopen(cancel, timeout=3) as r:
            self.assertEqual(json.load(r)["status"], "cancelled")

    def test_PROVIDER_agy_headless_json_and_usage(self):
        job = self.engine.submit({"kind": "agy_cli", "prompt": "Explain caches"})
        self.assertEqual(job["status"], "awaiting_approval")
        self.engine.approve(job["id"])
        response = {"stdout": '{"status":"SUCCESS","response":"Done","usage":{"total_tokens":20}}',
                    "stderr": "", "exit_code": 0}
        with unittest.mock.patch("aura_engine.adapters.run_process", return_value=response) as proc:
            result = self.engine.run_once()
        self.assertEqual(result["status"], "completed")
        self.assertEqual(result["result"]["response"]["usage"]["total_tokens"], 20)
        self.assertEqual(proc.call_args.args[0][:2], ["agy", "-p"])

    def test_PROVIDER_agy_non_success_must_fail(self):
        job = self.engine.submit({"kind": "agy_cli", "prompt": "Build app"})
        self.engine.approve(job["id"])
        response = {"stdout": '{"status":"FAILED","error":"permission denied"}',
                    "stderr": "Permission required", "exit_code": 0}
        with unittest.mock.patch("aura_engine.adapters.run_process", return_value=response):
            finished = self.engine.run_once()
        self.assertEqual(finished["status"], "failed")

    def test_DRIVE_read_only_inventory_needs_no_approval(self):
        job = self.engine.submit({"kind": "drive", "action": "list", "remote": "gdrive"})
        self.assertEqual(job["risk"], "read")
        self.assertEqual(job["status"], "queued")

    def test_SEC_01_authentication_required(self):
        server = make_server(self.engine, "127.0.0.1", 0)
        self.addCleanup(server.server_close)
        t = threading.Thread(target=server.serve_forever, daemon=True)
        t.start()
        self.addCleanup(server.shutdown)
        url = "http://127.0.0.1:%d/v1/health" % server.server_port
        with self.assertRaises(urllib.error.HTTPError) as err:
            urllib.request.urlopen(url, timeout=4)
        self.assertEqual(err.exception.code, 401)
        req = urllib.request.Request(url, headers={"Authorization": "Bearer " + self.engine.token})
        with urllib.request.urlopen(req, timeout=4) as response:
            self.assertEqual(json.load(response)["service"], "aura-engine")

    def test_SEC_02_only_loopback(self):
        with self.assertRaises(ValueError):
            make_server(self.engine, "0.0.0.0", 0)

    def test_SEC_03_paths_and_symlinks(self):
        root = self.home / "workspaces"
        root.mkdir(exist_ok=True)
        (root / "link").symlink_to(self.home.parent, target_is_directory=True)
        for bad in ("../outside", "/etc/passwd", "link/private"):
            with self.subTest(bad=bad), self.assertRaises(ValueError):
                resolve_inside(root, bad)

    def test_SEC_04_shell_requires_approval(self):
        marker = self.home / "marker"
        job = self.engine.submit({"kind": "shell", "argv": [
            "python", "-c", "from pathlib import Path; Path(%r).write_text('ok')" % str(marker)]})
        self.assertEqual(job["status"], "awaiting_approval")
        self.engine.run_once()
        self.assertFalse(marker.exists())
        self.engine.approve(job["id"])
        self.engine.run_once()
        self.assertTrue(marker.exists())

    def test_SEC_05_token_permissions(self):
        token = self.home / "token"
        self.assertEqual(token.stat().st_mode & 0o777, 0o600)
        self.assertEqual(token.read_text().strip(), self.engine.token)

    def test_JOB_01_idempotency(self):
        req = {"kind": "system", "action": "health"}
        a = self.engine.submit(req, request_id="identical")
        b = self.engine.submit(req, request_id="identical")
        self.assertEqual(a["id"], b["id"])

    def test_JOB_02_idempotency_conflict(self):
        self.engine.submit({"kind": "system", "action": "health"}, request_id="same")
        with self.assertRaises(ValueError):
            self.engine.submit({"kind": "system", "action": "capabilities"}, request_id="same")

    def test_JOB_03_job_not_claimed_twice(self):
        j = self.engine.submit({"kind": "system", "action": "health"})
        self.engine.run_once()
        self.engine.run_once()
        self.assertEqual(self.engine.store.job(j["id"])["status"], "completed")
        self.assertEqual(len([x for x in self.engine.store.events(0)
                              if x["type"] == "job.completed"]), 1)

    def test_JOB_04_restart_marks_running_interrupted(self):
        j = self.engine.submit({"kind": "system", "action": "health"})
        self.engine.store.claim()
        self.engine.close()
        again = Engine(home=self.home, auto_worker=False)
        self.addCleanup(again.close)
        self.assertEqual(again.store.job(j["id"])["status"], "interrupted")

    def test_JOB_05_cancel_prevents_execution(self):
        j = self.engine.submit({"kind": "system", "action": "health"})
        self.engine.cancel(j["id"])
        self.engine.run_once()
        self.assertEqual(self.engine.store.job(j["id"])["status"], "cancelled")

    def test_EVT_01_events_durable_and_monotonic(self):
        self.engine.store.emit("first", {})
        self.engine.store.emit("second", {})
        entries = self.engine.store.events(0)
        self.assertLess(entries[0]["id"], entries[1]["id"])
        self.engine.close()
        again = Engine(home=self.home, auto_worker=False)
        self.addCleanup(again.close)
        self.assertEqual([x["type"] for x in again.store.events(0)], ["first", "second"])

    def test_ADB_01_missing_provider_truthful(self):
        with unittest.mock.patch("aura_engine.adapters.shutil.which", return_value=None):
            self.assertFalse(self.engine.capabilities()["providers"]["adb"]["installed"])

    def test_ROUTE_01_local_adb(self):
        routed = self.engine.route("Liste os dispositivos ADB")
        self.assertEqual(routed["request"]["kind"], "adb")
        self.assertEqual(routed["request"]["action"], "devices")
        self.assertFalse(routed["requires_model"])

    def test_ROUTE_02_complex_request_model(self):
        routed = self.engine.route("Quero criar um aplicativo de finanças")
        self.assertEqual(routed["request"]["kind"], "claude_cli")
        self.assertTrue(routed["requires_model"])

    def test_SCHEDULE_01_approval_not_bypassed(self):
        sch = self.engine.add_schedule({"every_seconds": 60, "request": {
            "kind": "shell", "argv": ["echo", "hello"]}})
        self.engine.trigger_schedule(sch["id"])
        self.assertEqual(self.engine.store.jobs()[0]["status"], "awaiting_approval")

    def test_FIN_01_api_needs_spend_approval(self):
        j = self.engine.submit({"kind": "claude_api", "prompt": "hello"})
        self.assertEqual(j["risk"], "spend")
        self.assertEqual(j["status"], "awaiting_approval")

    def test_FILE_01_delete_is_critical(self):
        self.assertEqual(RiskPolicy().classify(
            {"kind": "files", "action": "delete", "path": "doc"}), "critical")

    def test_memory_isolation(self):
        self.engine.store.remember("a", "style", "minimal")
        self.engine.store.remember("b", "style", "verbose")
        self.assertEqual(self.engine.store.recall("a", "style")[0]["value"], "minimal")
        self.assertEqual(self.engine.store.recall("a", "verbose"), [])


if __name__ == "__main__":
    unittest.main()
