"""Lote A: login facil (/v1/auth/*), politica, modelos e gasto."""
import json
import os
import pathlib
import shutil
import sqlite3
import stat
import tempfile
import threading
import time
import unittest
import unittest.mock
import urllib.error
import urllib.request

from aura_engine.core import Engine
from aura_engine.server import make_server

SLEEP = shutil.which("sleep") or "/bin/sleep"

# CLI falso: imprime link e codigo, como um login por dispositivo.
# Modos pelo arquivo "mode" na pasta falsa: ok | hang | fail | expired (status).
FAKE_CLI = """#!/bin/sh
D="$AURA_FAKE_DIR"
case "$*" in
  *status*)
    if [ -f "$D/expired" ]; then echo "token=SEGREDO123 expired /home/x/.codex/auth.json" >&2; exit 1; fi
    if [ -f "$D/logged_in" ]; then echo "token=SEGREDO123 /home/x/.codex/auth.json"; exit 0; fi
    echo "not logged in token=SEGREDO123" >&2; exit 1 ;;
  *)
    MODE=ok; [ -f "$D/mode" ] && read MODE < "$D/mode"
    echo "Abra https://example.com/device?session=SESSAOSECRETA no navegador"
    echo "Digite o codigo ABCD-1234"
    if [ "$MODE" = "hang" ]; then exec %(sleep)s 30; fi
    if [ "$MODE" = "wait" ]; then while [ ! -f "$D/go" ]; do :; done; fi
    if [ "$MODE" = "fail" ]; then echo "network unreachable token=SEGREDO123" >&2; exit 1; fi
    : > "$D/logged_in"
    exit 0 ;;
esac
""" % {"sleep": SLEEP}


class Base(unittest.TestCase):
    def setUp(self):
        temp = tempfile.TemporaryDirectory()
        self.addCleanup(temp.cleanup)
        self.root = pathlib.Path(temp.name)
        self.home = self.root / "home"
        self.fake = self.root / "bin"
        self.fake.mkdir()
        self.empty = self.root / "vazio"
        self.empty.mkdir()
        env = unittest.mock.patch.dict(os.environ, {
            "PATH": str(self.empty), "AURA_FAKE_DIR": str(self.fake)})
        env.start()
        self.addCleanup(env.stop)

    def make_engine(self, **kw):
        engine = Engine(home=self.home, auto_worker=False, **kw)
        self.addCleanup(engine.close)
        server = make_server(engine, "127.0.0.1", 0)
        self.addCleanup(server.server_close)
        threading.Thread(target=server.serve_forever, daemon=True).start()
        self.addCleanup(server.shutdown)
        self.engine = engine
        self.base = "http://127.0.0.1:%d" % server.server_port
        return engine

    def install(self, *names):
        for name in names:
            path = self.fake / name
            path.write_text(FAKE_CLI)
            path.chmod(0o755)
        os.environ["PATH"] = str(self.fake)

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

    def events(self, prefix=""):
        return [e for e in self.engine.store.events(0, 1000) if e["type"].startswith(prefix)]

    def wait_event(self, kind, timeout=8):
        end = time.time() + timeout
        while time.time() < end:
            found = [e for e in self.events() if e["type"] == kind]
            if found:
                return found[-1]
            time.sleep(0.05)
        self.fail("evento %s nao apareceu; vistos: %s" % (kind, [e["type"] for e in self.events()]))

    def all_text(self):
        return json.dumps(self.engine.store.events(0, 1000), ensure_ascii=False)


class AuthStatus(Base):
    PROVIDERS = ["claude", "gemini", "agy", "codex", "github", "google"]

    def test_AUTH_status_lists_every_provider_unavailable_when_cli_missing(self):
        self.make_engine()
        code, body = self.call("GET", "/v1/auth/status")
        self.assertEqual(code, 200)
        rows = {r["provider"]: r for r in body["providers"]}
        self.assertEqual(sorted(rows), sorted(self.PROVIDERS))
        for row in rows.values():
            self.assertEqual(row["state"], "indisponivel")
            self.assertIs(row["installable"], True)

    def test_AUTH_status_requires_bearer(self):
        self.make_engine()
        code, _ = self.call("GET", "/v1/auth/status", auth=False)
        self.assertEqual(code, 401)
        code, _ = self.call("POST", "/v1/auth/start", {"provider": "codex"}, auth=False)
        self.assertEqual(code, 401)

    def test_AUTH_status_disconnected_connected_expired(self):
        self.make_engine()
        self.install("codex", "gh")
        rows = {r["provider"]: r["state"] for r in self.call("GET", "/v1/auth/status")[1]["providers"]}
        self.assertEqual(rows["codex"], "desconectado")
        self.assertEqual(rows["github"], "desconectado")
        (self.fake / "logged_in").write_text("")
        rows = {r["provider"]: r["state"] for r in self.call("GET", "/v1/auth/status")[1]["providers"]}
        self.assertEqual(rows["codex"], "conectado")
        self.assertEqual(rows["github"], "conectado")
        (self.fake / "expired").write_text("")
        rows = {r["provider"]: r["state"] for r in self.call("GET", "/v1/auth/status")[1]["providers"]}
        self.assertEqual(rows["codex"], "expirado")

    def test_AUTH_status_never_leaks_token_or_credential_path(self):
        self.make_engine()
        self.install("codex")
        (self.fake / "logged_in").write_text("")
        raw = json.dumps(self.call("GET", "/v1/auth/status")[1])
        for forbidden in ("SEGREDO123", "auth.json", "token", "cookie", str(self.home)):
            self.assertNotIn(forbidden, raw)


class AuthStart(Base):
    def test_AUTH_start_missing_cli_is_unavailable_with_install_fix(self):
        self.make_engine()
        code, body = self.call("POST", "/v1/auth/start", {"provider": "codex"})
        self.assertEqual(code, 200)
        self.assertEqual(body["state"], "indisponivel")
        self.assertEqual(body["fix"], "Instalar")
        failed = self.events("auth.failed")[-1]["payload"]
        self.assertEqual(failed["provider"], "codex")
        self.assertEqual(failed["reason"], "cli_ausente")
        self.assertIn("Instalar", failed["fix"])

    def test_AUTH_start_rejects_unknown_provider_and_bad_body(self):
        self.make_engine()
        for body in ({"provider": "facebook"}, {"provider": "--help"}, {}, {"provider": 7}):
            code, _ = self.call("POST", "/v1/auth/start", body)
            self.assertEqual(code, 400, body)
        self.assertEqual(self.events("auth."), [])

    def test_AUTH_start_emits_prompt_then_done_and_connects(self):
        self.make_engine()
        self.install("codex")
        (self.fake / "mode").write_text("wait")
        code, body = self.call("POST", "/v1/auth/start", {"provider": "codex"})
        self.assertEqual(code, 200)
        self.assertEqual(body["provider"], "codex")
        prompt = self.wait_event("auth.prompt")["payload"]
        self.assertEqual(prompt["provider"], "codex")
        self.assertTrue(prompt["url"].startswith("https://example.com/device"))
        self.assertEqual(prompt["code"], "ABCD-1234")
        (self.fake / "go").write_text("")
        self.wait_event("auth.done")
        types = [e["type"] for e in self.events("auth.")]
        self.assertEqual(types, ["auth.prompt", "auth.done"])
        rows = {r["provider"]: r["state"] for r in self.call("GET", "/v1/auth/status")[1]["providers"]}
        self.assertEqual(rows["codex"], "conectado")

    def test_AUTH_start_never_puts_raw_terminal_text_in_events(self):
        self.make_engine()
        self.install("codex")
        self.call("POST", "/v1/auth/start", {"provider": "codex"})
        self.wait_event("auth.done")
        text = self.all_text()
        self.assertNotIn("Digite o codigo", text)
        self.assertNotIn("no navegador", text)

    def test_AUTH_prompt_secrets_are_scrubbed_after_finish(self):
        self.make_engine()
        self.install("codex")
        self.call("POST", "/v1/auth/start", {"provider": "codex"})
        self.wait_event("auth.done")
        text = self.all_text()
        self.assertNotIn("ABCD-1234", text)
        self.assertNotIn("SESSAOSECRETA", text)

    def test_AUTH_start_is_idempotent_while_running(self):
        self.make_engine()
        self.install("codex")
        (self.fake / "mode").write_text("hang")
        first = self.call("POST", "/v1/auth/start", {"provider": "codex"})[1]
        self.wait_event("auth.prompt")
        second = self.call("POST", "/v1/auth/start", {"provider": "codex"})[1]
        self.assertEqual(second["state"], "em_andamento")
        self.assertEqual(second["prompt"]["code"], "ABCD-1234")
        self.assertEqual(first["provider"], second["provider"])
        self.assertEqual(len(self.events("auth.prompt")), 1)

    def test_AUTH_start_times_out_with_tempo_esgotado(self):
        self.make_engine(auth_timeout=1.0)
        self.install("codex")
        (self.fake / "mode").write_text("hang")
        self.call("POST", "/v1/auth/start", {"provider": "codex"})
        failed = self.wait_event("auth.failed", timeout=8)["payload"]
        self.assertEqual(failed["reason"], "tempo_esgotado")
        self.assertTrue(failed["fix"])

    def test_AUTH_start_failure_gives_reason_and_fix_without_secrets(self):
        self.make_engine()
        self.install("codex")
        (self.fake / "mode").write_text("fail")
        self.call("POST", "/v1/auth/start", {"provider": "codex"})
        failed = self.wait_event("auth.failed")["payload"]
        self.assertEqual(failed["provider"], "codex")
        self.assertEqual(failed["reason"], "sem_internet")
        self.assertIn("internet", failed["fix"])
        self.assertEqual(sorted(failed), ["fix", "provider", "reason"])
        text = self.all_text()
        for forbidden in ("SEGREDO123", "SESSAOSECRETA", "ABCD-1234", "unreachable"):
            self.assertNotIn(forbidden, text)

    def test_AUTH_start_allows_a_new_login_after_failure(self):
        self.make_engine()
        self.install("codex")
        (self.fake / "mode").write_text("fail")
        self.call("POST", "/v1/auth/start", {"provider": "codex"})
        self.wait_event("auth.failed")
        (self.fake / "mode").write_text("ok")
        body = self.call("POST", "/v1/auth/start", {"provider": "codex"})[1]
        self.assertIn(body["state"], ("em_andamento", "conectado"))
        self.wait_event("auth.done")


class PolicyEndpoint(Base):
    def test_POLICY_default_is_valid_and_versioned(self):
        self.make_engine()
        code, body = self.call("GET", "/v1/policy")
        self.assertEqual(code, 200)
        self.assertEqual(body["version"], 1)
        self.assertIn(body["mode"], ("pergunta", "equilibrado", "autonomo", "total"))
        self.assertIsNone(body["spend_cap"]["per_day"])

    def test_POLICY_put_saves_bumps_version_emits_event_and_is_0600(self):
        self.make_engine()
        code, body = self.call("PUT", "/v1/policy", {"mode": "total"})
        self.assertEqual(code, 200)
        self.assertEqual(body["version"], 2)
        self.assertEqual(body["mode"], "total")
        self.assertEqual(self.call("GET", "/v1/policy")[1]["mode"], "total")
        event = self.events("policy.changed")[-1]["payload"]
        self.assertEqual(event["version"], 2)
        self.assertEqual(event["mode"], "total")
        path = self.home / "policy.json"
        self.assertEqual(stat.S_IMODE(path.stat().st_mode), 0o600)
        self.assertEqual(json.loads(path.read_text())["version"], 2)
        self.assertEqual([p.name for p in self.home.iterdir() if p.name.endswith(".tmp")], [])

    def test_POLICY_persists_across_engine_restart(self):
        engine = self.make_engine()
        self.call("PUT", "/v1/policy", {"mode": "pergunta"})
        self.assertEqual(engine.config.policy()["mode"], "pergunta")
        fresh = Engine(home=self.home, auto_worker=False)
        self.addCleanup(fresh.close)
        self.assertEqual(fresh.config.policy()["version"], 2)

    def test_POLICY_rejects_values_outside_enum(self):
        self.make_engine()
        bad = [
            {"mode": "yolo"}, {"mode": 1}, {"mode": None},
            {"by_risk": {"read": "maybe"}}, {"by_risk": {"gold": "auto"}},
            {"by_kind": {"shell": "sempre"}},
            {"spend_cap": {"on_reach": "ignorar"}},
            {"spend_cap": {"per_day": -1}}, {"spend_cap": {"per_day": "20"}},
            {"spend_cap": {"per_day": True}},
            {"root": {"via": ["bash"]}}, {"root": {"enabled": "sim"}},
            {"scopes": [{"path": "*", "write": "talvez"}]},
            {"auto_countdown_seconds": -5}, {"notify": "gritando"},
            {"desconhecido": 1},
        ]
        for body in bad:
            code, resp = self.call("PUT", "/v1/policy", body)
            self.assertEqual(code, 400, body)
            self.assertEqual(resp["error"]["code"], "invalid_request")
        self.assertEqual(self.call("GET", "/v1/policy")[1]["version"], 1)
        self.assertEqual(self.events("policy.changed"), [])

    def test_POLICY_accepts_every_mode_and_null_spend_cap(self):
        self.make_engine()
        for mode in ("pergunta", "equilibrado", "autonomo", "total"):
            code, body = self.call("PUT", "/v1/policy", {"mode": mode})
            self.assertEqual((code, body["mode"]), (200, mode))
        code, body = self.call("PUT", "/v1/policy", {"spend_cap": {"per_day": 20, "per_month": None}})
        self.assertEqual(code, 200)
        self.assertEqual(body["spend_cap"]["per_day"], 20)
        code, body = self.call("PUT", "/v1/policy", {"spend_cap": {"per_day": None}})
        self.assertIsNone(body["spend_cap"]["per_day"])

    def test_POLICY_stale_version_is_conflict(self):
        self.make_engine()
        self.call("PUT", "/v1/policy", {"mode": "total"})
        code, resp = self.call("PUT", "/v1/policy", {"version": 1, "mode": "pergunta"})
        self.assertEqual(code, 409)
        self.assertEqual(resp["error"]["code"], "conflict")
        self.assertEqual(self.call("GET", "/v1/policy")[1]["mode"], "total")
        code, _ = self.call("PUT", "/v1/policy", {"version": 2, "mode": "pergunta"})
        self.assertEqual(code, 200)

    def test_POLICY_write_is_atomic_when_replace_fails(self):
        engine = self.make_engine()
        self.call("PUT", "/v1/policy", {"mode": "total"})
        before = (self.home / "policy.json").read_text()
        with unittest.mock.patch("aura_engine.config.os.replace", side_effect=OSError("disco cheio")):
            code, _ = self.call("PUT", "/v1/policy", {"mode": "pergunta"})
        self.assertEqual(code, 500)
        self.assertEqual((self.home / "policy.json").read_text(), before)
        self.assertEqual(engine.config.policy()["mode"], "total")
        self.assertEqual([p.name for p in self.home.iterdir() if p.name.endswith(".tmp")], [])

    def test_POLICY_requires_bearer(self):
        self.make_engine()
        self.assertEqual(self.call("GET", "/v1/policy", auth=False)[0], 401)
        self.assertEqual(self.call("PUT", "/v1/policy", {"mode": "total"}, auth=False)[0], 401)


class ModelsEndpoint(Base):
    def test_MODELS_default_denies_fable_and_caps_sonnet_medium(self):
        self.make_engine()
        code, body = self.call("GET", "/v1/models")
        self.assertEqual(code, 200)
        self.assertIn("fable*", body["deny"])
        self.assertEqual(body["ceiling"]["claude"], "sonnet:medium")
        self.assertIs(body["prefer_subscription"], True)
        self.assertEqual(body["version"], 1)

    def test_MODELS_put_saves_bumps_version_emits_event_and_is_0600(self):
        self.make_engine()
        code, body = self.call("PUT", "/v1/models", {
            "prefer_subscription": False,
            "routes": [{"task": "triagem", "use": ["haiku:medium"]}]})
        self.assertEqual(code, 200)
        self.assertEqual(body["version"], 2)
        self.assertIs(body["prefer_subscription"], False)
        self.assertEqual(self.events("models.changed")[-1]["payload"]["version"], 2)
        path = self.home / "models.json"
        self.assertEqual(stat.S_IMODE(path.stat().st_mode), 0o600)
        self.assertEqual(self.call("GET", "/v1/models")[1]["routes"][0]["task"], "triagem")

    def test_MODELS_refuses_removing_fable_from_deny(self):
        self.make_engine()
        for deny in ([], ["gpt*"]):
            code, _ = self.call("PUT", "/v1/models", {"deny": deny})
            self.assertEqual(code, 400, deny)
        code, body = self.call("PUT", "/v1/models", {"deny": ["fable*", "opus*"]})
        self.assertEqual(code, 200)
        self.assertEqual(body["deny"], ["fable*", "opus*"])

    def test_MODELS_refuses_ceiling_above_sonnet_medium(self):
        self.make_engine()
        for ceiling in ("sonnet:high", "opus:low", "opus", "fable:low", "sonnet", "x y"):
            code, _ = self.call("PUT", "/v1/models", {"ceiling": {"claude": ceiling}})
            self.assertEqual(code, 400, ceiling)
        for ceiling in ("sonnet:medium", "sonnet:low", "haiku:high"):
            code, _ = self.call("PUT", "/v1/models", {"ceiling": {"claude": ceiling}})
            self.assertEqual(code, 200, ceiling)

    def test_MODELS_refuses_routes_that_use_denied_or_above_ceiling_models(self):
        self.make_engine()
        for use in (["fable:high"], ["opus:low"], ["sonnet:high"], ["subagente:fable:low"]):
            code, _ = self.call("PUT", "/v1/models", {"routes": [{"task": "x", "use": use}]})
            self.assertEqual(code, 400, use)
        code, _ = self.call("PUT", "/v1/models", {"routes": [
            {"task": "x", "use": ["codex_cli", "subagente:haiku:medium", "sonnet:medium"]}]})
        self.assertEqual(code, 200)
        self.assertEqual(self.call("GET", "/v1/models")[1]["version"], 2)

    def test_MODELS_rejects_bad_types_and_leaves_state_untouched(self):
        self.make_engine()
        for body in ({"prefer_subscription": "sim"}, {"api_fallback": "sempre"},
                     {"routes": "x"}, {"routes": [{"task": "x"}]}, {"deny": "fable*"},
                     {"extra": 1}, {"ceiling": []}):
            code, resp = self.call("PUT", "/v1/models", body)
            self.assertEqual(code, 400, body)
            self.assertEqual(resp["error"]["code"], "invalid_request")
        self.assertEqual(self.call("GET", "/v1/models")[1]["version"], 1)
        self.assertEqual(self.events("models.changed"), [])

    def test_MODELS_requires_bearer(self):
        self.make_engine()
        self.assertEqual(self.call("GET", "/v1/models", auth=False)[0], 401)
        self.assertEqual(self.call("PUT", "/v1/models", {}, auth=False)[0], 401)


class SpendEndpoint(Base):
    def finish_job(self, kind, result, model=None, when=None):
        request = {"kind": kind, "prompt": "p"}
        if model:
            request["model"] = model
        job = self.engine.submit(request)
        self.engine.approve(job["id"])
        raw = {"stdout": json.dumps(result), "stderr": "", "exit_code": 0}
        with unittest.mock.patch("aura_engine.adapters.run_process", return_value=raw):
            self.engine.run_once()
        if when is not None:
            with self.engine.store.lock, self.engine.store.db:
                self.engine.store.db.execute("UPDATE jobs SET updated=? WHERE id=?", (when, job["id"]))
        return job["id"]

    def test_SPEND_empty_report(self):
        self.make_engine()
        code, body = self.call("GET", "/v1/spend")
        self.assertEqual(code, 200)
        self.assertEqual(body["today"]["providers"], {})
        self.assertEqual(body["month"]["providers"], {})
        self.assertEqual(body["today"]["total_usd"], "não informado")

    def test_SPEND_sums_reported_cost_by_provider_and_model(self):
        self.make_engine()
        self.finish_job("claude_cli", {"result": "ok", "total_cost_usd": 0.25}, model="haiku")
        self.finish_job("claude_cli", {"result": "ok", "total_cost_usd": 0.5}, model="haiku")
        self.finish_job("claude_cli", {"result": "ok", "total_cost_usd": 1.0}, model="sonnet")
        body = self.call("GET", "/v1/spend")[1]
        claude = body["today"]["providers"]["claude"]
        self.assertEqual(claude["jobs"], 3)
        self.assertAlmostEqual(claude["cost_usd"], 1.75)
        self.assertAlmostEqual(claude["models"]["haiku"]["cost_usd"], 0.75)
        self.assertEqual(claude["models"]["haiku"]["jobs"], 2)
        self.assertAlmostEqual(claude["models"]["sonnet"]["cost_usd"], 1.0)
        self.assertAlmostEqual(body["today"]["total_usd"], 1.75)
        self.assertAlmostEqual(body["month"]["providers"]["claude"]["cost_usd"], 1.75)

    def test_SPEND_says_nao_informado_when_provider_does_not_report(self):
        self.make_engine()
        self.finish_job("agy_cli", {"status": "SUCCESS", "response": "ok"})
        body = self.call("GET", "/v1/spend")[1]
        agy = body["today"]["providers"]["agy"]
        self.assertEqual(agy["jobs"], 1)
        self.assertEqual(agy["cost_usd"], "não informado")
        self.assertEqual(agy["unreported_jobs"], 1)
        self.assertEqual(agy["models"]["não informado"]["cost_usd"], "não informado")

    def test_SPEND_today_excludes_older_jobs_but_month_keeps_them(self):
        self.make_engine()
        now = time.time()
        local = time.localtime(now)
        if local.tm_mday == 1:
            self.skipTest("dia 1: nao ha dia anterior no mesmo mes")
        earlier = time.mktime((local.tm_year, local.tm_mon, 1, 0, 0, 5, 0, 0, -1))
        self.finish_job("claude_cli", {"total_cost_usd": 2.0}, model="haiku", when=earlier)
        self.finish_job("claude_cli", {"total_cost_usd": 0.5}, model="haiku")
        body = self.call("GET", "/v1/spend")[1]
        self.assertAlmostEqual(body["today"]["providers"]["claude"]["cost_usd"], 0.5)
        self.assertAlmostEqual(body["month"]["providers"]["claude"]["cost_usd"], 2.5)

    def test_SPEND_ignores_failed_jobs_and_non_model_jobs(self):
        engine = self.make_engine()
        engine.submit({"kind": "system", "action": "health"})
        engine.run_once()
        job = engine.submit({"kind": "claude_cli", "prompt": "p"})
        engine.approve(job["id"])
        with unittest.mock.patch("aura_engine.adapters.run_process",
                                 return_value={"stdout": "no json", "stderr": "", "exit_code": 0}):
            engine.run_once()
        self.assertEqual(self.call("GET", "/v1/spend")[1]["today"]["providers"], {})

    def test_SPEND_requires_bearer(self):
        self.make_engine()
        self.assertEqual(self.call("GET", "/v1/spend", auth=False)[0], 401)


class NoSecretsAnywhere(Base):
    def test_ERR_responses_for_invalid_input_do_not_echo_secrets(self):
        self.make_engine()
        secret = "sk-ant-SUPERSECRETO"
        code, resp = self.call("PUT", "/v1/policy", {"mode": secret})
        self.assertEqual(code, 400)
        self.assertNotIn(secret, json.dumps(resp))
        code, resp = self.call("POST", "/v1/auth/start", {"provider": secret})
        self.assertEqual(code, 400)
        self.assertNotIn(secret, json.dumps(resp))
        code, resp = self.call("PUT", "/v1/models", {"deny": [secret]})
        self.assertNotIn(secret, json.dumps(resp))
        self.assertNotIn(secret, self.all_text())


if __name__ == "__main__":
    unittest.main()
