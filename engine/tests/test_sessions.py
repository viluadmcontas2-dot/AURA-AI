"""Sessoes do Claude Code, processos e melhoria de prompt: motor real por HTTP, HOME temporario."""
import http.server
import json
import os
import pathlib
import sys
import threading
import time
import unittest
import unittest.mock

from test_chat import ChatBase

SID_TERM = "11111111-1111-4111-8111-111111111111"
SID_AURA = "22222222-2222-4222-8222-222222222222"
SID_BAD = "33333333-3333-4333-8333-333333333333"

FAKE = """#!%(py)s
import json, os, sys, time
d = os.environ["AURA_FAKE_DIR"]
with open(d + "/args.log", "a") as f:
    f.write(json.dumps({"argv": sys.argv[1:], "cwd": os.getcwd()}) + "\\n")
sid = sys.argv[sys.argv.index("--session-id") + 1] if "--session-id" in sys.argv else sys.argv[sys.argv.index("--resume") + 1]
print(json.dumps({"type": "system", "subtype": "init", "session_id": sid}), flush=True)
if os.path.exists(d + "/hang"):
    open(d + "/pid", "w").write(str(os.getpid()))
    time.sleep(60)
print(json.dumps({"type": "result", "subtype": "success", "is_error": False, "session_id": sid, "result": "ok"}), flush=True)
"""


def jl(*events):
    return "\n".join(json.dumps(e) for e in events) + "\n"


class SessoesBase(ChatBase):
    def setUp(self):
        super().setUp()
        self.userhome = self.root / "usuario"
        patch = unittest.mock.patch.dict(os.environ, {"HOME": str(self.userhome)})
        patch.start()
        self.addCleanup(patch.stop)
        self.proj = self.userhome / ".claude" / "projects"
        self.workdir = self.root / "meu" / "projeto"
        self.workdir.mkdir(parents=True)
        d = self.proj / "-meu-projeto"
        d.mkdir(parents=True)
        self.term_file = d / (SID_TERM + ".jsonl")
        self.term_file.write_text(jl(
            {"type": "summary", "summary": "x"},
            {"type": "user", "isMeta": True, "message": {"role": "user", "content": "<system-reminder>ruido"}},
            {"type": "user", "cwd": str(self.workdir), "timestamp": "2026-10-01T10:00:00Z",
             "message": {"role": "user", "content": "Arrume o login\ndo app " + "x" * 200}},
            {"type": "assistant", "timestamp": "2026-10-01T10:00:05Z", "message": {"role": "assistant", "content": [
                {"type": "thinking", "thinking": "segredo interno"},
                {"type": "text", "text": "Vou olhar."},
                {"type": "tool_use", "name": "Read", "input": {"file_path": "/etc/senha"}}]}},
            {"type": "user", "message": {"role": "user", "content": [
                {"type": "tool_result", "tool_use_id": "t", "content": "RESULTADO_CRU"}]}},
            {"type": "assistant", "message": {"role": "assistant", "content": [{"type": "text", "text": "Pronto."}]}},
        ))
        old = time.time() - 5000
        os.utime(self.term_file, (old, old))
        self.aura_file = d / (SID_AURA + ".jsonl")
        self.aura_file.write_text(jl(
            {"type": "user", "cwd": str(self.workdir), "message": {"role": "user", "content": "Oi aura"}},
            {"type": "assistant", "message": {"role": "assistant", "content": [{"type": "text", "text": "Oi!"}]}}))
        (d / (SID_BAD + ".jsonl")).write_text('{"type": "user", "mess\n\x00\x01lixo\n[1,2]\n')
        (d / "nao-uuid.jsonl").write_text(jl({"type": "user", "message": {"content": "x"}}))
        # o motor conhece a sessao do AURA
        self.engine.store.chat_open(SID_AURA, "Oi aura", "default", started=True)
        self.engine.store.chat_add_message(SID_AURA, "c1", "user", "Oi aura")

    def install_fake(self):
        path = self.fake / "claude"
        path.write_text(FAKE % {"py": sys.executable})
        path.chmod(0o755)
        os.environ["PATH"] = str(self.fake) + os.pathsep + str(self.root / "vazio")

    def wait_for(self, fn, timeout=6):
        end = time.time() + timeout
        while time.time() < end:
            value = fn()
            if value:
                return value
            time.sleep(0.05)
        self.fail("condicao nao atendida")


class Sessoes(SessoesBase):
    def test_lista_mescla_ordena_e_ignora_corrompido(self):
        code, body = self.call("GET", "/v1/sessions")
        self.assertEqual(code, 200)
        rows = body["sessions"]
        self.assertEqual([r["id"] for r in rows], [SID_AURA, SID_TERM])  # recente primeiro; ruins fora
        term = rows[1]
        self.assertEqual(set(term), {"id", "title", "project", "updated_at", "messages", "source", "running"})
        self.assertEqual((term["source"], rows[0]["source"]), ("terminal", "aura"))
        self.assertEqual(len(term["title"]), 80)
        self.assertTrue(term["title"].startswith("Arrume o login do app"))
        self.assertEqual(term["project"], "meu/projeto")
        self.assertEqual(term["messages"], 3)  # user + 2 assistant; ferramenta nao conta
        self.assertFalse(term["running"])

    def test_mensagens_limpas(self):
        code, body = self.call("GET", "/v1/sessions/%s/messages" % SID_TERM)
        self.assertEqual(code, 200)
        msgs = body["messages"]
        self.assertEqual([m["role"] for m in msgs], ["user", "assistant", "tool", "assistant"])
        self.assertEqual(msgs[2]["text"], "Lendo arquivos…")
        blob = json.dumps(msgs)
        for ruido in ("RESULTADO_CRU", "segredo interno", "/etc/senha", "system-reminder"):
            self.assertNotIn(ruido, blob)
        self.assertEqual(set(msgs[0]), {"id", "role", "text", "created_at"})
        self.assertEqual(self.call("GET", "/v1/sessions/%s/messages" % SID_BAD)[0], 404)

    def test_rename_e_delete_nao_tocam_o_jsonl_do_terminal(self):
        before = self.term_file.read_bytes()
        code, body = self.call("POST", "/v1/sessions/%s/rename" % SID_TERM, {"title": "  Meu   login "})
        self.assertEqual((code, body["title"]), (200, "Meu login"))
        titles = {r["id"]: r["title"] for r in self.call("GET", "/v1/sessions")[1]["sessions"]}
        self.assertEqual(titles[SID_TERM], "Meu login")
        self.assertEqual(self.call("POST", "/v1/sessions/%s/rename" % SID_TERM, {"title": " "})[0], 400)
        code, body = self.call("DELETE", "/v1/sessions/%s" % SID_TERM)
        self.assertEqual((code, body["deleted"]), (200, True))
        self.call("DELETE", "/v1/sessions/%s" % SID_AURA)
        self.assertEqual(self.call("GET", "/v1/sessions")[1]["sessions"], [])
        self.assertEqual(self.term_file.read_bytes(), before)
        self.assertTrue(self.aura_file.exists())
        self.assertIsNone(self.engine.store.chat_conversation(SID_AURA))
        self.assertEqual(self.call("GET", "/v1/sessions/%s/messages" % SID_TERM)[0], 404)

    def test_resume_de_sessao_do_terminal_usa_o_cwd_gravado(self):
        self.install_fake()
        code, started = self.call("POST", "/v1/chat", {"text": "continue", "session_id": SID_TERM})
        self.assertEqual(code, 202)
        self.wait_final(started["chat_id"])
        entry = self.args()[0]
        self.assertEqual(entry["argv"][entry["argv"].index("--resume") + 1], SID_TERM)
        self.assertEqual(os.path.realpath(entry["cwd"]), os.path.realpath(self.workdir))
        self.assertNotIn("--session-id", entry["argv"])

    def test_stop_mata_o_processo_e_marca_running(self):
        self.install_fake()
        (self.fake / "hang").write_text("1")
        _, started = self.call("POST", "/v1/chat", {"text": "demora", "session_id": SID_TERM})
        pid = int(self.wait_for(lambda: (self.fake / "pid").exists() and (self.fake / "pid").read_text() or None))
        rows = {r["id"]: r for r in self.call("GET", "/v1/sessions")[1]["sessions"]}
        self.assertTrue(rows[SID_TERM]["running"])
        procs = self.call("GET", "/v1/processes")[1]["processes"]
        mine = [p for p in procs if p["pid"] == pid]
        self.assertEqual(len(mine), 1)
        self.assertEqual((mine[0]["kind"], mine[0]["session_id"]), ("claude", SID_TERM))
        self.assertNotIn("continue", mine[0]["cmd"])
        self.assertNotIn("demora", mine[0]["cmd"])
        self.assertIn("engine", [p["kind"] for p in procs])
        code, body = self.call("POST", "/v1/sessions/%s/stop" % SID_TERM)
        self.assertEqual((code, body["stopped"]), (200, True))
        self.assertEqual(self.wait_final(started["chat_id"])["payload"]["code"], "stopped")
        self.wait_for(lambda: not pathlib.Path("/proc/%d" % pid).exists() or
                      "Z" in pathlib.Path("/proc/%d/stat" % pid).read_text().split(")")[-1][:3])
        self.assertFalse(self.call("GET", "/v1/sessions")[1]["sessions"][0]["running"])
        self.assertEqual(self.call("POST", "/v1/sessions/%s/stop" % SID_TERM)[1]["stopped"], False)

    def test_processo_para_por_pid_e_motor_nao(self):
        self.install_fake()
        (self.fake / "hang").write_text("1")
        _, started = self.call("POST", "/v1/chat", {"text": "x"})
        pid = int(self.wait_for(lambda: (self.fake / "pid").exists() and (self.fake / "pid").read_text() or None))
        self.assertEqual(self.call("POST", "/v1/processes/%d/stop" % os.getpid())[0], 400)
        self.assertEqual(self.call("POST", "/v1/processes/999999/stop")[0], 404)
        self.assertEqual(self.call("POST", "/v1/processes/%d/stop" % pid)[1], {"pid": pid, "stopped": True})
        self.assertEqual(self.wait_final(started["chat_id"])["type"], "chat.error")


class FakeGemini(http.server.BaseHTTPRequestHandler):
    seen = []
    mode = "ok"

    def log_message(self, *a):
        pass

    def do_POST(self):
        raw = self.rfile.read(int(self.headers["Content-Length"]))
        FakeGemini.seen.append({"path": self.path, "key": self.headers.get("x-goog-api-key"),
                                "body": json.loads(raw)})
        if FakeGemini.mode == "403":
            self.send_response(403)
            self.end_headers()
            return
        text = "Objetivo: arrumar o login. Chave eco: " + (self.headers.get("x-goog-api-key") or "")
        data = json.dumps({"candidates": [{"content": {"parts": [{"text": text}]}}]}).encode()
        self.send_response(200)
        self.send_header("Content-Length", str(len(data)))
        self.end_headers()
        self.wfile.write(data)


class MelhorarPrompt(SessoesBase):
    KEY = "AIzaSy-CHAVE-SECRETA-DE-TESTE-1234567890"

    def setUp(self):
        super().setUp()
        FakeGemini.seen, FakeGemini.mode = [], "ok"
        srv = http.server.ThreadingHTTPServer(("127.0.0.1", 0), FakeGemini)
        threading.Thread(target=srv.serve_forever, daemon=True).start()
        self.addCleanup(srv.server_close)
        self.addCleanup(srv.shutdown)
        patch = unittest.mock.patch.dict(os.environ, {
            "AURA_GEMINI_BASE_URL": "http://127.0.0.1:%d" % srv.server_port})
        patch.start()
        self.addCleanup(patch.stop)

    def test_sem_chave_erro_com_acao(self):
        self.assertEqual(self.call("GET", "/v1/settings/gemini_key"), (200, {"configured": False}))
        code, body = self.call("POST", "/v1/prompt/improve", {"text": "arruma o login"})
        self.assertEqual(code, 400)
        self.assertEqual(body["error"]["code"], "gemini_key_missing")
        self.assertEqual(body["error"]["action_pt"], "Colar a chave do Gemini em Conexões")
        self.assertEqual(FakeGemini.seen, [])

    def test_texto_melhorado_e_chave_nunca_vaza(self):
        self.assertEqual(self.call("PUT", "/v1/settings/gemini_key", {"key": "curta"})[0], 400)
        code, body = self.call("PUT", "/v1/settings/gemini_key", {"key": self.KEY})
        self.assertEqual((code, body), (200, {"configured": True}))
        self.assertEqual(self.call("GET", "/v1/settings/gemini_key"), (200, {"configured": True}))
        self.assertEqual(oct((self.engine.home / "gemini_key").stat().st_mode & 0o777), "0o600")
        code, body = self.call("POST", "/v1/prompt/improve", {"text": "arruma o login", "style": "curto"})
        self.assertEqual(code, 200)
        self.assertEqual(set(body), {"improved"})
        self.assertIn("arrumar o login", body["improved"])
        sent = FakeGemini.seen[0]
        self.assertEqual(sent["key"], self.KEY)  # a chave so vai no cabecalho para o Google
        self.assertIn("gemini-3.1-flash-lite:generateContent", sent["path"])
        self.assertEqual(sent["body"]["contents"][0]["parts"][0]["text"], "arruma o login")
        self.assertIn("NÃO invente", sent["body"]["systemInstruction"]["parts"][0]["text"])
        everything = json.dumps([body, self.engine.store.events(0, 10000), self.call("GET", "/v1/capabilities")[1]])
        self.assertNotIn(self.KEY, everything)
        self.assertIn("[REDACTED]", body["improved"])

    def test_chave_recusada_e_internet(self):
        self.call("PUT", "/v1/settings/gemini_key", {"key": self.KEY})
        FakeGemini.mode = "403"
        code, body = self.call("POST", "/v1/prompt/improve", {"text": "oi"})
        self.assertEqual((code, body["error"]["code"]), (400, "gemini_key_rejected"))
        os.environ["AURA_GEMINI_BASE_URL"] = "http://127.0.0.1:1"
        code, body = self.call("POST", "/v1/prompt/improve", {"text": "oi"})
        self.assertEqual((code, body["error"]["code"], body["error"]["action_pt"]), (503, "network", "Tentar de novo"))
        self.assertEqual(self.call("POST", "/v1/prompt/improve", {"text": "oi", "style": "x"})[0], 400)
        self.assertEqual(self.call("DELETE", "/v1/settings/gemini_key")[1], {"configured": False})


if __name__ == "__main__":
    unittest.main()
