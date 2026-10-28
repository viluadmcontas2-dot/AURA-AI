"""Chat nativo: motor real por HTTP com um `claude` falso (JSONL de stream-json) no PATH."""
import json
import os
import pathlib
import shutil
import sys
import tempfile
import threading
import time
import unittest
import unittest.mock
import urllib.error
import urllib.request

from aura_engine.core import Engine
from aura_engine.server import make_server

FAKE_CLAUDE = """#!%(py)s
import json, os, sys, time
d = os.environ["AURA_FAKE_DIR"]
with open(d + "/args.log", "a") as f:
    f.write(json.dumps(sys.argv[1:]) + "\\n")
mode = open(d + "/mode").read().strip() if os.path.exists(d + "/mode") else "ok"
def out(o):
    print(json.dumps(o), flush=True)
sid = sys.argv[sys.argv.index("--session-id") + 1] if "--session-id" in sys.argv else sys.argv[sys.argv.index("--resume") + 1]
if mode == "hang":
    open(d + "/pid", "w").write(str(os.getpid()))
    out({"type": "system", "subtype": "init", "session_id": sid})
    time.sleep(60)
if mode == "slow":
    time.sleep(0.6)
if mode == "login":
    out({"type": "result", "subtype": "success", "is_error": True, "session_id": sid,
         "result": "Invalid API key - Please run /login"})
    sys.exit(1)
out({"type": "system", "subtype": "init", "session_id": sid, "tools": ["Bash"], "uuid": "x"})
out({"type": "stream_event", "event": {"type": "message_start"}, "session_id": sid})
for piece in ("Ol", "a, ", "tudo bem?"):
    out({"type": "stream_event", "session_id": sid,
         "event": {"type": "content_block_delta", "index": 0, "delta": {"type": "text_delta", "text": piece}}})
out({"type": "assistant", "session_id": sid, "message": {"content": [
    {"type": "text", "text": "Ola, tudo bem?"},
    {"type": "tool_use", "name": "Edit", "input": {"file_path": "/x/y/app.py", "old_string": "SEGREDO"}}]}})
out({"type": "result", "subtype": "success", "is_error": False, "session_id": sid,
     "result": "Ola, tudo bem?", "ttft_ms": 5, "subagent_stats": {}, "total_cost_usd": 0.01})
"""


class ChatBase(unittest.TestCase):
    def setUp(self):
        temp = tempfile.TemporaryDirectory()
        self.addCleanup(temp.cleanup)
        self.root = pathlib.Path(temp.name)
        self.fake = self.root / "bin"
        self.fake.mkdir()
        patch = unittest.mock.patch.dict(os.environ, {
            "PATH": str(self.root / "vazio"), "AURA_FAKE_DIR": str(self.fake)})
        patch.start()
        self.addCleanup(patch.stop)
        self.start_engine()

    def start_engine(self):
        self.engine = Engine(home=self.root / "home", auto_worker=False)
        server = make_server(self.engine, "127.0.0.1", 0)
        threading.Thread(target=server.serve_forever, daemon=True).start()
        self.base = "http://127.0.0.1:%d" % server.server_port
        self.addCleanup(self.engine.close)
        self.addCleanup(server.server_close)
        self.addCleanup(server.shutdown)

    def install_claude(self):
        path = self.fake / "claude"
        path.write_text(FAKE_CLAUDE % {"py": sys.executable})
        path.chmod(0o755)
        os.environ["PATH"] = str(self.fake) + os.pathsep + str(self.root / "vazio")

    def call(self, method, path, body=None):
        data = json.dumps(body).encode() if body is not None else (b"" if method != "GET" else None)
        req = urllib.request.Request(self.base + path, data=data, method=method, headers={
            "Authorization": "Bearer " + self.engine.token, "Content-Type": "application/json"})
        try:
            with urllib.request.urlopen(req, timeout=8) as r:
                return r.status, json.load(r)
        except urllib.error.HTTPError as err:
            return err.code, json.load(err)

    def chat_events(self, chat_id):
        _, body = self.call("GET", "/v1/events?since=0")
        evs = self.engine.store.events(0, 1000)
        return [e for e in evs if e["type"].startswith("chat.") and e["payload"].get("chat_id") == chat_id]

    def wait_final(self, chat_id, timeout=8):
        end = time.time() + timeout
        while time.time() < end:
            for e in self.chat_events(chat_id):
                if e["type"] in ("chat.done", "chat.error"):
                    return e
            time.sleep(0.05)
        self.fail("chat nao terminou: %s" % self.chat_events(chat_id))

    def args(self):
        return [json.loads(x) for x in (self.fake / "args.log").read_text().splitlines()]


class ChatNativo(ChatBase):
    def test_CHAT_delta_em_ordem_e_done_sem_json(self):
        self.install_claude()
        code, started = self.call("POST", "/v1/chat", {"text": "Ola"})
        self.assertEqual(code, 202)
        self.assertEqual(set(started), {"chat_id", "session_id"})
        final = self.wait_final(started["chat_id"])
        self.assertEqual(final["type"], "chat.done")
        evs = self.chat_events(started["chat_id"])
        deltas = [e["payload"]["text"] for e in evs if e["type"] == "chat.delta"]
        self.assertEqual(deltas, ["Ol", "a, ", "tudo bem?"])
        self.assertEqual(final["payload"]["text"], "Ola, tudo bem?")
        self.assertEqual(final["payload"]["session_id"], started["session_id"])
        self.assertEqual(final["payload"]["cost"], 0.01)
        tools = [e["payload"]["summary"] for e in evs if e["type"] == "chat.tool"]
        self.assertEqual(tools, ["Editando app.py"])
        blob = json.dumps([e["payload"] for e in evs], ensure_ascii=False)
        for cru in ("ttft_ms", "subagent_stats", "SEGREDO", "stream_event"):
            self.assertNotIn(cru, blob)
        code, state = self.call("GET", "/v1/chat/" + started["chat_id"])
        self.assertEqual((state["state"], state["text"]), ("done", "Ola, tudo bem?"))
        args = self.args()[0]
        for flag in ("stream-json", "--verbose", "--include-partial-messages", "--session-id"):
            self.assertIn(flag, args)
        self.assertNotIn("--resume", args)

    def test_CHAT_segunda_mensagem_usa_resume_e_reabrir_lista_mensagens(self):
        self.install_claude()
        _, first = self.call("POST", "/v1/chat", {"text": "Ola"})
        self.wait_final(first["chat_id"])
        _, second = self.call("POST", "/v1/chat", {"text": "E agora?", "session_id": first["session_id"]})
        self.assertEqual(second["session_id"], first["session_id"])
        self.wait_final(second["chat_id"])
        args = self.args()[1]
        self.assertEqual(args[args.index("--resume") + 1], first["session_id"])
        # reiniciar o motor: a conversa continua la
        self.engine.close()
        self.start_engine()
        _, chats = self.call("GET", "/v1/chats")
        self.assertEqual([c["id"] for c in chats["chats"]], [first["session_id"]])
        self.assertEqual(chats["chats"][0]["title"], "Ola")
        _, msgs = self.call("GET", "/v1/chats/%s/messages" % first["session_id"])
        self.assertEqual([(m["role"], m["text"]) for m in msgs["messages"]], [
            ("user", "Ola"), ("assistant", "Ola, tudo bem?"),
            ("user", "E agora?"), ("assistant", "Ola, tudo bem?")])

    def test_CHAT_sem_claude_vira_erro_em_portugues_com_acao(self):
        _, started = self.call("POST", "/v1/chat", {"text": "Ola"})
        final = self.wait_final(started["chat_id"])
        self.assertEqual(final["type"], "chat.error")
        p = final["payload"]
        self.assertEqual(p["code"], "no_executor")
        self.assertIn("Instalar o Claude em Conexões", p["action_pt"])
        self.assertIn("instalado", p["message_pt"])
        _, state = self.call("GET", "/v1/chat/" + started["chat_id"])
        self.assertEqual(state["state"], "error")
        self.assertEqual(state["error"]["code"], "no_executor")

    def test_CHAT_nao_logado_pede_entrar_em_conexoes(self):
        self.install_claude()
        (self.fake / "mode").write_text("login")
        _, started = self.call("POST", "/v1/chat", {"text": "Ola"})
        p = self.wait_final(started["chat_id"])["payload"]
        self.assertEqual(p["code"], "not_logged_in")
        self.assertEqual(p["action_pt"], "Entrar no Claude em Conexões")
        self.assertNotIn("API key", json.dumps(p))

    def test_CHAT_parar_mata_o_processo(self):
        self.install_claude()
        (self.fake / "mode").write_text("hang")
        _, started = self.call("POST", "/v1/chat", {"text": "demora"})
        end = time.time() + 5
        while not (self.fake / "pid").exists() and time.time() < end:
            time.sleep(0.05)
        pid = int((self.fake / "pid").read_text())
        code, body = self.call("POST", "/v1/chat/%s/stop" % started["chat_id"])
        self.assertEqual((code, body["stopped"]), (200, True))
        self.assertEqual(self.wait_final(started["chat_id"])["payload"]["code"], "stopped")
        end = time.time() + 5
        while time.time() < end:
            try:
                os.kill(pid, 0)
                time.sleep(0.05)
            except ProcessLookupError:
                break
        with self.assertRaises(ProcessLookupError):
            os.kill(pid, 0)

    def test_CHAT_mensagem_nova_com_chat_rodando_entra_na_fila_em_ordem(self):
        self.install_claude()
        (self.fake / "mode").write_text("slow")
        _, a = self.call("POST", "/v1/chat", {"text": "primeira"})
        _, b = self.call("POST", "/v1/chat", {"text": "segunda", "session_id": a["session_id"]})
        self.assertEqual(self.call("GET", "/v1/chat/" + b["chat_id"])[1]["state"], "queued")
        self.wait_final(a["chat_id"])
        self.wait_final(b["chat_id"])
        order = [e["payload"]["chat_id"] for e in self.engine.store.events(0, 1000) if e["type"] == "chat.done"]
        self.assertEqual(order, [a["chat_id"], b["chat_id"]])


class Intencao(ChatBase):
    def test_INTENT_conversa_nao_pede_aprovacao_e_nao_expoe_reason(self):
        code, body = self.call("POST", "/v1/intent", {"text": "Ola"})
        self.assertEqual((code, body["kind"], body["needs_approval"]), (200, "chat", False))
        self.assertNotIn("reason", body)
        self.assertEqual(self.call("POST", "/v1/intent", {"text": "quero reformar o app"})[1]["kind"], "reform")
        self.assertEqual(self.call("POST", "/v1/intent", {"text": "liste os dispositivos conectados"})[1]["kind"],
                         "command")


if __name__ == "__main__":
    unittest.main()
