"""Lote C: politica aplicada aos jobs, teto de gasto, modelos na rota e historico com desfazer."""
import json
import os
import pathlib
import stat
import tempfile
import threading
import time
import unittest
import unittest.mock
import urllib.error
import urllib.request

from aura_engine.config import DEFAULT_POLICY
from aura_engine.core import Engine
from aura_engine.server import make_server


class Base(unittest.TestCase):
    def setUp(self):
        temp = tempfile.TemporaryDirectory()
        self.addCleanup(temp.cleanup)
        self.root = pathlib.Path(temp.name)
        self.home = self.root / "home"
        self.empty = self.root / "vazio"
        self.empty.mkdir()
        env = unittest.mock.patch.dict(os.environ, {"PATH": str(self.empty)})
        env.start()
        self.addCleanup(env.stop)
        os.environ.pop("AURA_CLAUDE_MODEL", None)
        self.engine = Engine(home=self.home, auto_worker=False)
        self.addCleanup(self.engine.close)
        # O padrao agora e autonomia total; estes testes exercitam a politica granular (policy.json).
        self.engine.config.set_setting("autonomy", "custom")
        server = make_server(self.engine, "127.0.0.1", 0)
        self.addCleanup(server.server_close)
        threading.Thread(target=server.serve_forever, daemon=True).start()
        self.addCleanup(server.shutdown)
        self.base = "http://127.0.0.1:%d" % server.server_port
        self.n = 0

    def call(self, method, path, body=None, auth=True):
        data = json.dumps(body).encode() if body is not None else (b"" if method != "GET" else None)
        headers = {"Content-Type": "application/json"}
        if auth:
            headers["Authorization"] = "Bearer " + self.engine.token
        req = urllib.request.Request(self.base + path, data=data, headers=headers, method=method)
        try:
            with urllib.request.urlopen(req, timeout=8) as r:
                return r.status, json.load(r)
        except urllib.error.HTTPError as err:
            return err.code, json.load(err)

    def put_policy(self, **patch):
        code, body = self.call("PUT", "/v1/policy", patch)
        self.assertEqual(code, 200, body)
        return body

    def submit(self, request):
        self.n += 1
        return self.call("POST", "/v1/jobs", {"request_id": "r-%d" % self.n, "request": request})

    def events(self, prefix=""):
        return [e for e in self.engine.store.events(0, 2000) if e["type"].startswith(prefix)]

    def fake_cli(self, name):
        path = self.empty / name
        path.write_text("#!/bin/sh\nexit 0\n")
        path.chmod(0o755)

    def finish(self, kind, cost, **extra):
        job = self.engine.submit(dict({"kind": kind, "prompt": "p"}, **extra))
        if job["status"] == "awaiting_approval":
            self.engine.approve(job["id"])
        raw = {"stdout": json.dumps({"result": "ok", "total_cost_usd": cost}), "stderr": "", "exit_code": 0}
        with unittest.mock.patch("aura_engine.adapters.run_process", return_value=raw) as proc:
            done = self.engine.run_once()
        self.assertEqual(done["status"], "completed", done)
        return done, proc


class PolicyApplied(Base):
    SHELL = {"kind": "shell", "argv": ["echo", "oi"]}
    FILE_WRITE = {"kind": "files", "action": "write", "path": "a.txt", "text": "x"}
    FILE_DELETE = {"kind": "files", "action": "delete", "path": "a.txt"}

    def status(self, request):
        code, job = self.submit(request)
        self.assertIn(code, (200, 202), job)
        return job["status"]

    def test_RISK_default_keeps_previous_behaviour(self):
        self.assertEqual(self.status({"kind": "system", "action": "health"}), "queued")
        self.assertEqual(self.status(self.SHELL), "awaiting_approval")
        self.assertEqual(self.status(self.FILE_WRITE), "awaiting_approval")

    def test_RISK_total_needs_no_approval_and_records_policy_version(self):
        self.put_policy(mode="total")
        code, job = self.submit(self.SHELL)
        self.assertEqual(job["status"], "queued")
        self.assertEqual(job["risk"], "critical")
        created = [e for e in self.events("job.created") if e["payload"]["id"] == job["id"]][-1]["payload"]
        self.assertEqual(created["decision"], "auto")
        self.assertEqual(created["policy_version"], 2)
        self.assertIn("v2", created["why"])
        self.assertNotIn("echo", json.dumps(created))

    def test_RISK_pergunta_asks_for_everything_but_reads_even_if_by_risk_says_auto(self):
        self.put_policy(mode="pergunta", by_risk={"write": "auto", "critical": "auto", "spend": "auto"})
        self.assertEqual(self.status({"kind": "system", "action": "health"}), "queued")
        self.assertEqual(self.status(self.FILE_WRITE), "awaiting_approval")
        self.assertEqual(self.status(self.SHELL), "awaiting_approval")

    def test_RISK_autonomo_follows_by_risk_and_by_kind(self):
        self.put_policy(mode="autonomo", by_risk={"write": "auto"}, by_kind={"files": "auto"})
        self.assertEqual(self.status(self.FILE_WRITE), "queued")
        self.assertEqual(self.status(self.FILE_DELETE), "awaiting_approval")
        self.put_policy(by_kind={"files": "ask"})
        self.assertEqual(self.status(self.FILE_WRITE), "awaiting_approval")

    def test_RISK_by_kind_never_blocks_read_only_jobs(self):
        self.put_policy(mode="autonomo", by_kind={"adb": "ask", "github": "ask"})
        self.assertEqual(self.status({"kind": "adb", "action": "devices"}), "queued")
        self.assertEqual(self.status({"kind": "github", "action": "status"}), "queued")

    def test_RISK_equilibrado_keeps_spend_and_critical_behind_approval(self):
        self.put_policy(mode="equilibrado", by_risk={"write": "auto", "critical": "auto", "spend": "auto"},
                        by_kind={"files": "auto", "shell": "auto"})
        self.assertEqual(self.status(self.FILE_WRITE), "queued")
        self.assertEqual(self.status(self.FILE_DELETE), "awaiting_approval")
        self.assertEqual(self.status(self.SHELL), "awaiting_approval")
        self.assertEqual(self.status({"kind": "claude_api", "prompt": "oi", "model": "haiku"}),
                         "awaiting_approval")

    def test_RISK_policy_file_is_reread_for_every_job(self):
        self.assertEqual(self.status(self.SHELL), "awaiting_approval")
        doc = self.engine.config.policy()
        doc["mode"] = "total"
        doc["version"] += 10
        (self.home / "policy.json").write_text(json.dumps(doc))
        self.assertEqual(self.status(self.SHELL), "queued")
        doc["mode"] = "pergunta"
        (self.home / "policy.json").write_text(json.dumps(doc))
        self.assertEqual(self.status(self.SHELL), "awaiting_approval")

    def test_RISK_broken_policy_file_falls_back_to_the_safe_default(self):
        (self.home / "policy.json").write_text("{nao e json")
        self.assertEqual(self.status(self.SHELL), "awaiting_approval")

    def test_RISK_approval_still_works_when_policy_asks(self):
        code, job = self.submit(self.SHELL)
        code, done = self.call("POST", "/v1/jobs/%s/approve" % job["id"])
        self.assertEqual(done["status"], "queued")

    def test_RISK_root_is_open_by_default_even_when_disabled_or_outside_via(self):
        self.put_policy(mode="total", root={"enabled": False, "via": ["su"]})
        code, resp = self.submit({"kind": "shell", "argv": ["su", "-c", "id"]})
        self.assertEqual((code, resp["status"]), (202, "queued"))
        code, resp = self.submit({"kind": "shell", "argv": ["tsu"]})
        self.assertEqual((code, resp["status"]), (202, "queued"))

    def test_RISK_root_only_asks_when_the_owner_asks_via_mode(self):
        self.put_policy(mode="total", root={"enabled": True, "via": ["su"]})
        self.assertEqual(self.submit({"kind": "shell", "argv": ["su", "-c", "id"]})[1]["status"], "queued")
        self.put_policy(mode="pergunta")
        self.assertEqual(self.submit({"kind": "shell", "argv": ["su", "-c", "id"]})[1]["status"],
                         "awaiting_approval")

    def test_RISK_root_by_kind_ask_still_asks_outside_total(self):
        self.put_policy(mode="autonomo", root={"enabled": True, "via": ["su"]},
                        by_risk={"critical": "auto"}, by_kind={"shell": "auto", "root": "ask"})
        self.assertEqual(self.submit({"kind": "shell", "argv": ["su", "-c", "id"]})[1]["status"],
                         "awaiting_approval")
        self.assertEqual(self.submit({"kind": "shell", "argv": ["echo", "x"]})[1]["status"], "queued")

    def test_RISK_idempotent_replay_does_not_repeat_the_decision(self):
        self.put_policy(mode="total")
        body = {"request_id": "same", "request": self.SHELL}
        first = self.call("POST", "/v1/jobs", body)[1]
        self.put_policy(mode="pergunta")
        second = self.call("POST", "/v1/jobs", body)[1]
        self.assertEqual(first["id"], second["id"])
        self.assertEqual(second["status"], "queued")
        self.assertEqual(len([e for e in self.events("job.created") if e["payload"]["id"] == first["id"]]), 1)


class SpendCaps(Base):
    def setUp(self):
        super().setUp()
        self.put_policy(mode="total")

    def spent(self, amount):
        self.finish("claude_cli", amount, model="haiku")

    def test_CAP_null_means_no_limit(self):
        self.spent(500.0)
        code, job = self.submit({"kind": "claude_cli", "prompt": "oi", "model": "haiku"})
        self.assertEqual(job["status"], "queued")
        self.assertEqual(self.events("spend.cap_reached"), [])

    def test_CAP_below_limit_is_silent(self):
        self.put_policy(spend_cap={"per_day": 10})
        self.spent(1.0)
        job = self.submit({"kind": "claude_cli", "prompt": "oi", "model": "haiku"})[1]
        self.assertEqual(job["status"], "queued")
        self.assertEqual(self.events("spend.cap_reached"), [])

    def test_CAP_avisar_warns_and_lets_the_job_go(self):
        self.put_policy(spend_cap={"per_day": 1.0, "on_reach": "avisar"})
        self.spent(1.5)
        job = self.submit({"kind": "claude_cli", "prompt": "oi", "model": "haiku"})[1]
        self.assertEqual(job["status"], "queued")
        event = self.events("spend.cap_reached")[-1]["payload"]
        self.assertEqual((event["scope"], event["action"]), ("per_day", "avisar"))
        self.assertEqual(event["cap"], 1.0)
        self.assertAlmostEqual(event["spent"], 1.5)

    def test_CAP_pausar_holds_job_with_visible_reason_and_never_raises_the_limit(self):
        self.put_policy(spend_cap={"per_day": 1.0, "on_reach": "pausar"})
        before = self.call("GET", "/v1/policy")[1]
        self.spent(1.5)
        job = self.submit({"kind": "claude_cli", "prompt": "oi", "model": "haiku"})[1]
        self.assertEqual(job["status"], "awaiting_approval")
        self.assertIn("dia", job["note"])
        self.assertIn("1.5", job["note"])
        self.assertEqual(self.call("GET", "/v1/jobs/" + job["id"])[1]["note"], job["note"])
        self.assertEqual(self.events("spend.cap_reached")[-1]["payload"]["action"], "pausar")
        approved = self.call("POST", "/v1/jobs/%s/approve" % job["id"])[1]
        self.assertEqual(approved["status"], "queued")
        after = self.call("GET", "/v1/policy")[1]
        self.assertEqual(after["spend_cap"], before["spend_cap"])
        self.assertEqual(after["version"], before["version"])

    def test_CAP_pausar_does_not_touch_jobs_that_cannot_spend(self):
        self.put_policy(spend_cap={"per_day": 0, "on_reach": "pausar"})
        job = self.submit({"kind": "system", "action": "health"})[1]
        self.assertEqual(job["status"], "queued")
        self.assertIsNone(job.get("note"))

    def test_CAP_per_month(self):
        self.put_policy(spend_cap={"per_month": 2.0, "on_reach": "pausar"})
        self.spent(2.5)
        job = self.submit({"kind": "claude_cli", "prompt": "oi", "model": "haiku"})[1]
        self.assertEqual(job["status"], "awaiting_approval")
        self.assertIn("mês", job["note"])

    def test_CAP_per_job_uses_declared_max_cost(self):
        self.put_policy(spend_cap={"per_job": 2.0, "on_reach": "pausar"})
        ok = self.submit({"kind": "claude_cli", "prompt": "oi", "model": "haiku", "max_cost_usd": 1.0})[1]
        self.assertEqual(ok["status"], "queued")
        big = self.submit({"kind": "claude_cli", "prompt": "oi", "model": "haiku", "max_cost_usd": 5.0})[1]
        self.assertEqual(big["status"], "awaiting_approval")
        self.assertIn("tarefa", big["note"])
        self.assertEqual(self.events("spend.cap_reached")[-1]["payload"]["scope"], "per_job")

    def test_CAP_crossing_after_a_job_finishes_emits_a_warning(self):
        self.put_policy(spend_cap={"per_day": 1.0, "on_reach": "avisar"})
        self.finish("claude_cli", 1.5, model="haiku")
        event = self.events("spend.cap_reached")[-1]["payload"]
        self.assertEqual(event["scope"], "per_day")
        self.assertEqual(event["phase"], "depois")

    def test_CAP_after_finish_warns_only_when_the_job_crosses_the_line(self):
        self.put_policy(spend_cap={"per_day": 1.0, "on_reach": "avisar"})
        self.finish("claude_cli", 1.5, model="haiku")
        after = lambda: [e for e in self.events("spend.cap_reached") if e["payload"].get("phase") == "depois"]
        count = len(after())
        self.assertEqual(count, 1)
        self.finish("claude_cli", 0.5, model="haiku")
        self.assertEqual(len(after()), count)


class ModelsApplied(Base):
    def run_job(self, request):
        job = self.engine.submit(request)
        if job["status"] == "awaiting_approval":
            self.engine.approve(job["id"])
        raw = {"stdout": '{"result":"ok"}', "stderr": "", "exit_code": 0}
        with unittest.mock.patch("aura_engine.adapters.run_process", return_value=raw) as proc:
            done = self.engine.run_once()
        return job, done, proc

    def model_event(self, job):
        found = [e["payload"] for e in self.events("job.model") if e["payload"]["id"] == job["id"]]
        self.assertEqual(len(found), 1)
        return found[0]

    def test_MODEL_fable_is_refused_at_submit(self):
        for model in ("fable-5", "claude-fable", "Fable"):
            code, resp = self.submit({"kind": "claude_cli", "prompt": "oi", "model": model})
            self.assertEqual(code, 400, model)
            self.assertEqual(resp["error"]["code"], "invalid_request")
        self.assertEqual(self.events("job.created"), [])

    def test_MODEL_above_ceiling_is_refused(self):
        for request in ({"model": "opus"}, {"model": "claude-opus-4"},
                        {"model": "sonnet", "effort": "high"}, {"model": "mystery-9"}):
            code, _ = self.submit(dict({"kind": "claude_cli", "prompt": "oi"}, **request))
            self.assertEqual(code, 400, request)
        for request in ({"model": "sonnet"}, {"model": "haiku", "effort": "high"},
                        {"model": "claude-sonnet-4-5", "effort": "medium"}):
            code, _ = self.submit(dict({"kind": "claude_cli", "prompt": "oi"}, **request))
            self.assertEqual(code, 202, request)

    def test_MODEL_api_default_model_from_env_is_checked_too(self):
        os.environ["AURA_CLAUDE_MODEL"] = "opus"
        code, _ = self.submit({"kind": "claude_api", "prompt": "oi"})
        self.assertEqual(code, 400)

    def test_MODEL_custom_deny_pattern_applies_to_other_providers(self):
        self.call("PUT", "/v1/models", {"deny": ["fable*", "gemini-ultra*"]})
        code, _ = self.submit({"kind": "gemini_cli", "prompt": "oi", "model": "gemini-ultra-2"})
        self.assertEqual(code, 400)
        code, _ = self.submit({"kind": "gemini_cli", "prompt": "oi", "model": "gemini-flash"})
        self.assertEqual(code, 202)

    def test_MODEL_task_route_picks_the_cheap_model_and_explains(self):
        self.put_policy(mode="total")
        job, done, proc = self.run_job({"kind": "claude_cli", "prompt": "oi", "task": "triagem"})
        self.assertEqual(done["status"], "completed")
        argv = proc.call_args.args[0]
        self.assertEqual(argv[argv.index("--model") + 1], "haiku")
        info = self.model_event(job)
        self.assertEqual(info["model_used"], "haiku")
        self.assertIn("triagem", info["why"])

    def test_MODEL_without_task_uses_the_ceiling_never_the_cli_default(self):
        self.put_policy(mode="total")
        job, done, proc = self.run_job({"kind": "claude_cli", "prompt": "oi"})
        argv = proc.call_args.args[0]
        self.assertEqual(argv[argv.index("--model") + 1], "sonnet")
        info = self.model_event(job)
        self.assertEqual(info["model_used"], "sonnet")
        self.assertIn("teto", info["why"])

    def test_MODEL_explicit_request_is_respected_and_reported(self):
        self.put_policy(mode="total")
        job, done, proc = self.run_job({"kind": "claude_cli", "prompt": "oi", "model": "haiku"})
        argv = proc.call_args.args[0]
        self.assertEqual(argv.count("--model"), 1)
        self.assertEqual(self.model_event(job)["model_used"], "haiku")
        self.assertIn("pedido", self.model_event(job)["why"])

    def test_MODEL_provider_without_model_choice_reports_cli_default(self):
        self.put_policy(mode="total")
        job, done, proc = self.run_job({"kind": "agy_cli", "prompt": "oi"})
        self.assertNotIn("--model", proc.call_args.args[0])
        self.assertEqual(self.model_event(job)["model_used"], "não informado")

    def test_MODEL_non_model_jobs_have_no_model_event(self):
        job = self.engine.submit({"kind": "system", "action": "health"})
        self.engine.run_once()
        self.assertEqual(self.events("job.model"), [])

    def test_MODEL_deny_added_after_approval_stops_the_job_before_it_runs(self):
        job = self.engine.submit({"kind": "claude_cli", "prompt": "oi", "model": "sonnet"})
        self.engine.approve(job["id"])
        code, _ = self.call("PUT", "/v1/models", {"deny": ["fable*", "sonnet*"],
                                                  "ceiling": {"claude": "haiku:high"},
                                                  "routes": [{"task": "x", "use": ["haiku:medium"]}]})
        self.assertEqual(code, 200)
        with unittest.mock.patch("aura_engine.adapters.run_process") as proc:
            done = self.engine.run_once()
        proc.assert_not_called()
        self.assertEqual(done["status"], "failed")
        self.assertEqual(done["error"]["code"], "model_denied")

    def test_MODEL_route_reports_model_and_reason(self):
        code, body = self.call("POST", "/v1/route", {"text": "explique este erro", "preferred_provider": "claude_cli"})
        self.assertEqual(code, 200)
        self.assertEqual(body["model_used"], "sonnet")
        self.assertIn("teto", body["why"])

    def test_MODEL_route_prefers_subscription_cli_over_paid_api(self):
        self.fake_cli("claude")
        code, body = self.call("POST", "/v1/route", {"text": "oi", "preferred_provider": "claude_api"})
        self.assertEqual(body["request"]["kind"], "claude_cli")
        self.assertIn("assinatura", body["why"])
        self.call("PUT", "/v1/models", {"api_fallback": "ask"})
        body = self.call("POST", "/v1/route", {"text": "oi", "preferred_provider": "claude_api"})[1]
        self.assertEqual(body["request"]["kind"], "claude_api")

    def test_MODEL_route_has_no_model_for_local_requests(self):
        body = self.call("POST", "/v1/route", {"text": "listar dispositivos adb"})[1]
        self.assertNotIn("model_used", body)


class PolicyHistory(Base):
    def test_HIST_lists_versions_with_parent_and_changes(self):
        self.put_policy(mode="total")
        self.put_policy(mode="pergunta")
        code, body = self.call("GET", "/v1/policy/history")
        self.assertEqual(code, 200)
        rows = body["history"]
        self.assertEqual([r["version"] for r in rows], [1, 2, 3])
        self.assertEqual([r["parent"] for r in rows], [None, 1, 2])
        self.assertEqual(rows[1]["changed"], ["mode"])
        self.assertEqual(rows[2]["mode"], "pergunta")
        self.assertEqual(body["current"], 3)
        self.assertEqual(self.call("GET", "/v1/policy/history?limit=1")[1]["history"][0]["version"], 3)

    def test_HIST_files_are_private_and_hold_only_policy_fields(self):
        self.put_policy(mode="total")
        folder = self.home / "policy_history"
        self.assertEqual(stat.S_IMODE(folder.stat().st_mode), 0o700)
        files = sorted(folder.iterdir())
        self.assertTrue(files)
        for path in files:
            self.assertEqual(stat.S_IMODE(path.stat().st_mode), 0o600)
            doc = json.loads(path.read_text())
            self.assertLessEqual(set(doc["policy"]), set(DEFAULT_POLICY))
            self.assertLessEqual(set(doc), {"version", "parent", "at", "changed", "by", "restored_from", "policy"})
        raw = json.dumps(self.call("GET", "/v1/policy/history")[1])
        self.assertNotIn(self.engine.token, raw)
        self.assertNotIn(str(self.home), raw)

    def test_HIST_undo_restores_previous_content_as_a_new_version(self):
        self.put_policy(mode="total")
        self.put_policy(mode="pergunta")
        code, body = self.call("POST", "/v1/policy/undo", {})
        self.assertEqual(code, 200)
        self.assertEqual((body["mode"], body["version"]), ("total", 4))
        self.assertEqual(self.call("GET", "/v1/policy")[1]["mode"], "total")
        event = self.events("policy.changed")[-1]["payload"]
        self.assertEqual((event["version"], event["restored_from"]), (4, 2))

    def test_HIST_repeated_undo_walks_back_instead_of_toggling(self):
        self.put_policy(mode="total")
        self.put_policy(mode="pergunta")
        self.assertEqual(self.call("POST", "/v1/policy/undo", {})[1]["mode"], "total")
        self.assertEqual(self.call("POST", "/v1/policy/undo", {})[1]["mode"], DEFAULT_POLICY["mode"])
        code, resp = self.call("POST", "/v1/policy/undo", {})
        self.assertEqual(code, 400)
        self.assertEqual(self.call("GET", "/v1/policy")[1]["version"], 5)

    def test_HIST_undo_to_explicit_version(self):
        self.put_policy(mode="total")
        self.put_policy(mode="pergunta")
        self.put_policy(mode="equilibrado")
        body = self.call("POST", "/v1/policy/undo", {"to_version": 2})[1]
        self.assertEqual((body["mode"], body["version"]), ("total", 5))

    def test_HIST_undo_refuses_bad_targets_without_changing_anything(self):
        self.put_policy(mode="total")
        for target in (99, 0, -1, "x", True, 2, 1.5):
            code, resp = self.call("POST", "/v1/policy/undo", {"to_version": target})
            self.assertEqual(code, 400, target)
            self.assertEqual(resp["error"]["code"], "invalid_request")
        self.assertEqual(self.call("GET", "/v1/policy")[1]["version"], 2)

    def test_HIST_undo_nothing_to_undo_on_fresh_policy(self):
        code, _ = self.call("POST", "/v1/policy/undo", {})
        self.assertEqual(code, 400)

    def test_HIST_tampered_history_file_is_refused(self):
        self.put_policy(mode="total")
        path = self.home / "policy_history" / "policy.v1.json"
        doc = json.loads(path.read_text())
        doc["policy"]["mode"] = "yolo"
        path.write_text(json.dumps(doc))
        code, _ = self.call("POST", "/v1/policy/undo", {})
        self.assertEqual(code, 400)
        self.assertEqual(self.call("GET", "/v1/policy")[1]["mode"], "total")

    def test_HIST_undo_applies_to_the_next_job_immediately(self):
        self.put_policy(mode="total")
        job = self.submit({"kind": "shell", "argv": ["echo", "x"]})[1]
        self.assertEqual(job["status"], "queued")
        self.call("POST", "/v1/policy/undo", {})
        job = self.submit({"kind": "shell", "argv": ["echo", "y"]})[1]
        self.assertEqual(job["status"], "awaiting_approval")

    def test_HIST_requires_bearer(self):
        self.assertEqual(self.call("GET", "/v1/policy/history", auth=False)[0], 401)
        self.assertEqual(self.call("POST", "/v1/policy/undo", {}, auth=False)[0], 401)


if __name__ == "__main__":
    unittest.main()
