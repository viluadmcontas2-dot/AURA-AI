"""Motor de verdade num processo (python -m aura_engine, HOME temporario, porta livre) + HTTP com o token.

Falsos `claude`, `agy`, `codex`, `npm`... no PATH ecoam os argumentos recebidos: prova que os flags de
autonomia total chegam ao programa. A cadeia REA roda contra um falso MCP por stdio.
"""
import json
import os
import pathlib
import subprocess
import sys
import tempfile
import time
import unittest
import unittest.mock
import urllib.error
import urllib.request

from aura_engine import adapters
from test_lote_c import Base
from consent_helper import allow_installs

ENGINE_DIR = pathlib.Path(__file__).resolve().parent.parent

FAKE_CLAUDE = """#!/bin/sh
case "$1" in
  --version) echo "9.9.9"; exit 0;;
  auth) if [ "$2" = status ]; then exit 0; fi
        echo "Open https://claude.ai/oauth/authorize?x=1 and paste the code"
        read code; echo "$code" > "$AURA_TEST_DIR/code.txt"; exit 0;;
esac
printf '%s\\n' "$@" > "$AURA_TEST_DIR/claude.args"
echo '{"result":"ok","is_error":false}'
"""
FAKE_AGY = """#!/bin/sh
printf '%s\\n' "$@" > "$AURA_TEST_DIR/agy.args"
echo '{"status":"SUCCESS","result":"feito"}'
"""
FAKE_CODEX = """#!/bin/sh
case "$1" in --version) echo 1; exit 0;; esac
printf '%s\\n' "$@" > "$AURA_TEST_DIR/codex.args"
echo "codex pronto"
"""
FAKE_NPM = """#!/bin/sh
printf '%s\\n' "$@" > "$AURA_TEST_DIR/npm.args"
cp "$AURA_TEST_DIR/claude.src" "$AURA_TEST_DIR/bin/claude"; chmod +x "$AURA_TEST_DIR/bin/claude"
"""
SCRIPTS = {"agy": FAKE_AGY, "codex": FAKE_CODEX, "node": "#!/bin/sh\nexit 0\n",
           "git": "#!/bin/sh\nexit 0\n"}


class RealEngine(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.temp = tempfile.TemporaryDirectory()
        root = pathlib.Path(cls.temp.name)
        cls.dir, cls.bin, cls.home = root, root / "bin", root / "home"
        cls.bin.mkdir()
        cls.home.mkdir()
        for name, text in SCRIPTS.items():
            cls.script(name, text)
        (root / "claude.src").write_text(FAKE_CLAUDE)  # o falso npm "instala" este
        cls.script("npm", FAKE_NPM)
        cls.script("claude", FAKE_CLAUDE)
        env = dict(os.environ, HOME=str(cls.home), AURA_TEST_DIR=str(root), PYTHONPATH=str(ENGINE_DIR),
                   PATH=str(cls.bin) + os.pathsep + "/usr/bin:/bin")
        env.pop("PREFIX", None)
        cls.proc = subprocess.Popen([sys.executable, "-m", "aura_engine", "--port", "0"], env=env,
                                    stdout=subprocess.PIPE, stderr=subprocess.DEVNULL, text=True, cwd=str(root))
        line = cls.proc.stdout.readline()
        cls.base = "http://127.0.0.1:%s" % line.rsplit(":", 1)[1].strip()
        cls.token = (cls.home / ".aura" / "engine" / "token").read_text().strip()

    @classmethod
    def script(cls, name, text):
        path = cls.bin / name
        path.write_text(text)
        path.chmod(0o755)

    @classmethod
    def tearDownClass(cls):
        cls.proc.terminate()
        cls.proc.wait(timeout=10)
        cls.proc.stdout.close()
        cls.temp.cleanup()

    def call(self, method, path, body=None, auth=True):
        data = json.dumps(body).encode() if body is not None else (b"" if method != "GET" else None)
        headers = {"Content-Type": "application/json"}
        if auth:
            headers["Authorization"] = "Bearer " + self.token
        req = urllib.request.Request(self.base + path, data=data, headers=headers, method=method)
        try:
            with urllib.request.urlopen(req, timeout=10) as r:
                return r.status, json.load(r)
        except urllib.error.HTTPError as err:
            return err.code, json.load(err)

    def job(self, request, timeout=20):
        code, made = self.call("POST", "/v1/jobs", {"request": request})
        self.assertEqual(code, 202, made)
        end = time.time() + timeout
        while time.time() < end:
            _, job = self.call("GET", "/v1/jobs/" + made["id"])
            if job["status"] in ("completed", "failed", "cancelled"):
                return job
            time.sleep(0.2)
        self.fail("job nao terminou")

    def args(self, name):
        return (self.dir / (name + ".args")).read_text().split("\n")

    def test_health_needs_the_token_and_auth_status_answers(self):
        self.assertEqual(self.call("GET", "/v1/health", auth=False)[0], 401)
        self.assertEqual(self.call("GET", "/v1/health")[1]["service"], "aura-engine")
        code, status = self.call("GET", "/v1/auth/status")
        self.assertEqual(code, 200)
        claude = next(p for p in status["providers"] if p["provider"] == "claude")
        self.assertEqual(claude["state"], "conectado")  # o falso `claude auth status` sai 0

    def test_shell_action_runs_for_real(self):
        job = self.job({"kind": "shell", "argv": ["echo", "oi-aura"]})
        self.assertEqual((job["status"], job["result"]["stdout"].strip()), ("completed", "oi-aura"))

    def test_claude_gets_full_autonomy_flags_and_optional_limits(self):
        job = self.job({"kind": "claude_cli", "prompt": "delegue ao agy", "allowed_tools": ["Bash", "Task"],
                        "max_turns": 7})
        self.assertEqual(job["status"], "completed", job)
        got = self.args("claude")
        self.assertIn("--dangerously-skip-permissions", got)
        self.assertEqual(got[got.index("--allowedTools") + 1], "Bash,Task")
        self.assertEqual(got[got.index("--max-turns") + 1], "7")
        self.job({"kind": "claude_cli", "prompt": "livre"})
        self.assertNotIn("--allowedTools", self.args("claude"))  # padrao: ferramentas sem restricao

    def test_ask_destructive_keeps_the_old_behaviour_without_bypass(self):
        self.call("POST", "/v1/settings/autonomy", {"autonomy": "ask_destructive"})
        try:
            self.job({"kind": "claude_cli", "prompt": "cuidado"})
            self.assertNotIn("--dangerously-skip-permissions", self.args("claude"))
        finally:
            self.call("POST", "/v1/settings/autonomy", {"autonomy": "total"})

    def test_agy_and_codex_get_their_autonomy_flags_and_agy_permissions_are_bootstrapped(self):
        self.assertEqual(self.job({"kind": "agy_cli", "prompt": "x"})["status"], "completed")
        got = self.args("agy")
        self.assertLess(got.index("--dangerously-skip-permissions"), got.index("-p"))
        rules = json.loads((self.home / ".gemini" / "antigravity-cli" / "settings.json").read_text())
        self.assertIn("command(*)", rules["permissions"]["allow"])
        done = self.job({"kind": "codex_cli", "prompt": "revise"})
        self.assertEqual(done["status"], "completed", done)
        got = self.args("codex")
        self.assertEqual(got[:2], ["exec", "--dangerously-bypass-approvals-and-sandbox"])
        self.assertEqual(done["result"]["response"]["text"].strip(), "codex pronto")

    def test_tools_install_runs_npm_and_the_cli_works_afterwards(self):
        (self.bin / "claude").unlink()
        # Sem plano aceito nada é instalado: a rota antiga só devolve o trabalho de um plano já decidido.
        code, refused = self.call("POST", "/v1/tools/install", {"provider": "claude"})
        self.assertEqual((code, refused["error"]["code"]), (403, "consent_required"))
        self.assertFalse((self.dir / "npm.args").exists())
        code, plan = self.call("POST", "/v1/install-plans", {
            "request_id": "req-plano-claude-1", "target": "claude", "reason": "Responder aos seus pedidos"})
        self.assertEqual((code, plan["state"]), (201, "awaiting_consent"), plan)
        self.assertFalse((self.dir / "npm.args").exists())  # preparar o plano não baixa nada
        code, decided = self.call("POST", "/v1/install-plans/%s/decision" % plan["plan_id"], {
            "request_id": "req-decisao-claude-1", "manifest_hash": plan["manifest_hash"], "decision": "install"})
        self.assertEqual((code, decided["state"]), (202, "installing"), decided)
        end = time.time() + 20
        while time.time() < end:
            plan = self.call("GET", "/v1/install-plans/" + plan["plan_id"])[1]
            if plan["state"] in ("completed", "failed", "cancelled"):
                break
            time.sleep(0.2)
        self.assertEqual(plan["state"], "completed", plan)
        self.assertEqual(self.args("npm")[:3], ["install", "-g", "@anthropic-ai/claude-code"])
        self.assertEqual(self.call("POST", "/v1/tools/install", {"provider": "vim"})[0], 400)

    def test_login_code_reaches_the_cli_stdin(self):
        code, started = self.call("POST", "/v1/auth/start", {"provider": "claude"})
        self.assertEqual(started["state"], "em_andamento")
        for _ in range(50):  # espera o link aparecer
            prompts = [e for e in self.call("GET", "/v1/events?since=0")[1]["events"] if e["type"] == "auth.prompt"]
            if prompts:
                break
            time.sleep(0.2)
        self.assertTrue(prompts and prompts[-1]["payload"]["url"].startswith("https://claude.ai/"))
        code, body = self.call("POST", "/v1/auth/code", {"provider": "claude", "code": "abc123-XYZ"})
        self.assertEqual((code, body["state"]), (200, "em_andamento"), body)
        self.assertNotIn("abc123", json.dumps(body))
        for _ in range(50):
            if (self.dir / "code.txt").exists():
                break
            time.sleep(0.2)
        self.assertEqual((self.dir / "code.txt").read_text().strip(), "abc123-XYZ")
        self.assertEqual(self.call("POST", "/v1/auth/code", {"provider": "claude", "code": "x"})[0], 400)


FAKE_REA_MCP = """
import json, sys
log = open(sys.argv[1], "a")
for line in sys.stdin:
    msg = json.loads(line)
    method = msg.get("method")
    if method == "initialize":
        result = {"protocolVersion": "2025-06-18", "capabilities": {"tools": {}},
                  "serverInfo": {"name": "fake-rea", "version": "0"}}
    elif method == "tools/list":
        result = {"tools": [{"name": n, "description": "x"} for n in (
            "binary_session", "inventory_artifact", "extract_artifact",
            "build_reconstruction_obligation_ledger", "evaluate_reconstruction_coverage")]}
    elif method == "tools/call":
        name = msg["params"]["name"]
        log.write(name + "\\n"); log.flush()
        out = {"satisfied": 3, "total": 4} if name == "evaluate_reconstruction_coverage" else {
            "ok": True, "session_id": "s1", "evidence_id": "ev-" + name}
        result = {"structuredContent": out, "content": []}
    else:
        continue
    print(json.dumps({"jsonrpc": "2.0", "id": msg["id"], "result": result}), flush=True)
"""


class ReverseEngineer(Base):
    def test_chain_runs_in_order_against_a_fake_mcp_and_fills_the_ledger(self):
        script, log = self.root / "mcp.py", self.root / "mcp.log"
        script.write_text(FAKE_REA_MCP)
        agy = self.empty / "agy"
        agy.write_text("#!/bin/sh\necho '{\"status\":\"SUCCESS\",\"result\":\"reconstruido\"}'\n")
        agy.chmod(0o755)
        apk = self.root / "a.apk"
        apk.write_bytes(b"PK")
        self.engine.config.set_setting("autonomy", "total")
        env = unittest.mock.patch.dict(os.environ, {"HOME": str(self.root)})
        env.start()
        self.addCleanup(env.stop)
        with unittest.mock.patch("aura_engine.rea_chain.MCP_ARGV", [sys.executable, str(script), str(log)]):
            code, made = self.submit({"kind": "rea", "action": "reverse_engineer", "apk_path": str(apk)})
            self.assertEqual(code, 202, made)
            done = self.engine.run_once()
        self.assertEqual(done["status"], "completed", done)
        self.assertEqual(log.read_text().split(), [
            "binary_session", "inventory_artifact", "extract_artifact",
            "build_reconstruction_obligation_ledger", "evaluate_reconstruction_coverage"])
        result = done["result"]
        self.assertEqual(result["steps_run"], [
            "binary_session", "inventory_artifact", "extract_artifact",
            "build_reconstruction_obligation_ledger", "rebuild", "evaluate_reconstruction_coverage"])
        self.assertEqual((result["chain_ok"], result["reversal"]["pct"]), (True, 75))
        _, ledger = self.call("GET", "/v1/reform/%s/ledger" % result["reform_id"])
        kinds = [e["kind"] for e in ledger["entries"]]
        self.assertEqual(kinds.count("tool_call"), 5)
        self.assertEqual(kinds.count("model_call"), 1)
        self.assertEqual(kinds[-2:], ["coverage_evaluated", "step_done"])
        # retomar com o mesmo reform_id nao repete nada
        with unittest.mock.patch("aura_engine.rea_chain.MCP_ARGV", [sys.executable, str(script), str(log)]):
            self.submit({"kind": "rea", "action": "reverse_engineer", "reform_id": result["reform_id"]})
            again = self.engine.run_once()
        self.assertEqual(again["result"]["steps_run"], [])


class CliSetup(Base):
    def setUp(self):
        super().setUp()
        allow_installs(self)
        self.engine.config.set_setting("autonomy", "total")

    def run_install(self, provider, fake, present=lambda name: False):
        self.submit({"kind": "tools", "action": "install", "provider": provider})
        with unittest.mock.patch("aura_engine.adapters.run_process", fake), \
                unittest.mock.patch("aura_engine.adapters.installed", present):
            return self.engine.run_once()

    def test_claude_never_falls_back_to_glibc_runner_without_a_new_plan(self):
        calls = []

        def fake(argv, cwd, engine, timeout=180, binary=False, env=None):
            calls.append(argv)
            if argv[0] == "npm":
                raise adapters.ActionError("process_failed", "npm exited 1")
            return {"exit_code": 0, "stdout": "", "stderr": "", "truncated": False}

        done = self.run_install("claude", fake)
        self.assertEqual((done["status"], done["error"]["code"]), ("failed", "needs_new_plan"), done)
        # o caminho alternativo baixa outras coisas: nem curl nem instalador rodaram
        self.assertFalse(any(c[0] in ("curl", "sh") for c in calls), calls)

    def test_claude_glibc_runner_runs_only_when_the_plan_asks_for_it(self):
        calls = []

        def fake(argv, cwd, engine, timeout=180, binary=False, env=None):
            calls.append(argv)
            return {"exit_code": 0, "stdout": "", "stderr": "", "truncated": False}

        self.submit({"kind": "tools", "action": "install", "provider": "claude", "variant": "glibc-runner"})
        # o `claude` so passa a existir depois do instalador glibc-runner
        with unittest.mock.patch("aura_engine.adapters.run_process", fake), \
                unittest.mock.patch("aura_engine.adapters.installed", lambda name: name == "claude" and any(
                    c[0] == "sh" for c in calls)):
            done = self.engine.run_once()
        self.assertEqual(done["status"], "completed", done)
        self.assertEqual(done["result"]["via"], "glibc-runner")
        self.assertTrue(any("claude-code-android" in " ".join(c) for c in calls))
        self.assertFalse(any(c[0] == "npm" for c in calls), calls)

    def test_codex_failure_is_reported_as_unavailable_on_termux(self):
        def fake(argv, cwd, engine, timeout=180, binary=False, env=None):
            if argv[0] == "npm":
                raise adapters.ActionError("process_failed", "npm exited 1")
            return {"exit_code": 0, "stdout": "", "stderr": "", "truncated": False}

        done = self.run_install("codex", fake)
        self.assertEqual((done["status"], done["error"]["code"]), ("failed", "indisponivel_no_termux"))

    def test_mobile_data_waits_for_wifi_without_downloading(self):
        self.engine.config.set_setting("network", {"metered": True, "transport": "cellular"})
        calls = []
        done = self.run_install("gh", lambda argv, *a, **k: calls.append(argv))
        self.assertEqual(done["result"]["state"], "waiting_wifi")
        self.assertEqual(calls, [])


if __name__ == "__main__":
    unittest.main()
