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
if mode == "stubborn":  # ignora o pedido educado de parar: so o SIGKILL acaba com ele
    import signal
    signal.signal(signal.SIGTERM, signal.SIG_IGN)
    open(d + "/pid", "w").write(str(os.getpid()))
    out({"type": "system", "subtype": "init", "session_id": sid})
    time.sleep(60)
if mode == "gone" and "--resume" in sys.argv:  # a CLI nao conhece mais a conversa
    out({"type": "result", "subtype": "error", "is_error": True, "session_id": sid,
         "result": "No conversation found with session ID: " + sid})
    sys.exit(1)
if mode == "work":  # trabalha um pouco antes de responder: le um arquivo e fica em silencio
    out({"type": "system", "subtype": "init", "session_id": sid})
    out({"type": "assistant", "session_id": sid, "message": {"content": [
        {"type": "tool_use", "name": "Read", "input": {"file_path": "/x/y/app.py"}}]}})
    time.sleep(1.0)
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
        self.assertEqual(set(started), {"chat_id", "session_id", "request_id", "state"})
        self.assertEqual((started["state"], started["request_id"]), ("running", None))
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
        # C1: o pedido de parada é aceito (202) e só vira "parado" quando o processo acabou de verdade
        self.assertEqual((code, body["state"], body["stopped"]), (202, "stopping", False))
        self.assertEqual(self.wait_final(started["chat_id"])["payload"]["code"], "stopped")
        self.assertEqual(self.call("GET", "/v1/chat/" + started["chat_id"])[1]["state"], "interrupted")
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


RID_A = "11111111-1111-4111-8111-111111111111"
RID_B = "22222222-2222-4222-8222-222222222222"
RID_STOP = "44444444-4444-4444-8444-444444444444"


class ChatContratoC1(ChatBase):
    """C1: envio idempotente, batimento chat.status, parada confirmada, sem troca silenciosa de conversa."""

    def statuses(self, chat_id):
        return [e["payload"] for e in self.chat_events(chat_id) if e["type"] == "chat.status"]

    def wait_for(self, fn, timeout=8):
        end = time.time() + timeout
        while time.time() < end:
            value = fn()
            if value:
                return value
            time.sleep(0.05)
        self.fail("condicao nao atendida")

    # ---- idempotencia ----
    def test_C1_mesmo_request_id_nao_roda_duas_vezes_e_devolve_o_original(self):
        self.install_claude()
        body = {"text": "Ola", "request_id": RID_A}
        code, first = self.call("POST", "/v1/chat", body)
        self.assertEqual(code, 202)
        self.assertEqual(first["request_id"], RID_A)
        self.wait_final(first["chat_id"])
        code, again = self.call("POST", "/v1/chat", body)  # reconexão / retry do app
        self.assertEqual(code, 202)
        self.assertEqual((again["chat_id"], again["session_id"]), (first["chat_id"], first["session_id"]))
        self.assertEqual(len(self.args()), 1, "a CLI não pode rodar de novo")
        _, msgs = self.call("GET", "/v1/chats/%s/messages" % first["session_id"])
        self.assertEqual([m["role"] for m in msgs["messages"]], ["user", "assistant"], "uma bolha por mensagem")

    def test_C1_mesmo_request_id_com_outro_texto_e_conflito(self):
        self.install_claude()
        _, first = self.call("POST", "/v1/chat", {"text": "Ola", "request_id": RID_A})
        self.wait_final(first["chat_id"])
        code, body = self.call("POST", "/v1/chat", {"text": "Outro texto", "request_id": RID_A})
        self.assertEqual((code, body["error"]["code"]), (409, "request_conflict"))
        self.assertEqual(body["error"]["action"], "review_request")
        self.assertFalse(body["error"]["retryable"])
        self.assertEqual(len(self.args()), 1)

    def test_C1_consulta_por_request_id_e_depois_de_reiniciar_nao_repete(self):
        self.install_claude()
        _, first = self.call("POST", "/v1/chat", {"text": "Ola", "request_id": RID_A})
        self.wait_final(first["chat_id"])
        self.engine.close()
        self.start_engine()
        code, snap = self.call("GET", "/v1/chat/requests/" + RID_A)
        self.assertEqual((code, snap["chat_id"], snap["state"]), (200, first["chat_id"], "done"))
        self.assertEqual(snap["text"], "Ola, tudo bem?")
        self.assertEqual(snap["request_id"], RID_A)
        code, again = self.call("POST", "/v1/chat", {"text": "Ola", "request_id": RID_A})
        self.assertEqual((code, again["chat_id"]), (202, first["chat_id"]))
        self.assertEqual(len(self.args()), 1, "depois de reiniciar, a mesma chave não executa de novo")
        self.assertEqual(self.call("GET", "/v1/chat/requests/" + RID_B)[0], 404)
        self.assertEqual(self.call("GET", "/v1/chat/requests/" + RID_B)[1]["error"]["code"], "request_not_found")

    def test_C1_request_id_invalido_e_recusado(self):
        self.install_claude()
        self.assertEqual(self.call("POST", "/v1/chat", {"text": "Ola", "request_id": "nao-e-uuid"})[0], 400)

    # ---- batimento e fases ----
    def test_C1_chat_status_bate_a_cada_intervalo_com_seq_e_para_no_terminal(self):
        self.install_claude()
        (self.fake / "mode").write_text("work")
        with unittest.mock.patch.dict(os.environ, {"AURA_CHAT_STATUS_SECS": "0.1"}):
            _, started = self.call("POST", "/v1/chat", {"text": "Trabalhe"})
            final = self.wait_final(started["chat_id"])
        self.assertEqual(final["type"], "chat.done")
        seen = self.statuses(started["chat_id"])
        self.assertGreaterEqual(len(seen), 6, "1 s de silêncio da CLI com batimento de 0,1 s")
        seqs = [s["seq"] for s in seen]
        self.assertEqual(seqs, sorted(seqs))
        self.assertEqual(len(set(seqs)), len(seqs), "seq nunca repete")
        self.assertEqual(seen[0]["state"], "running")
        self.assertEqual(seen[0]["phase"], "starting")  # imediato
        phases = {s["phase"] for s in seen}
        self.assertTrue({"starting", "waiting_claude", "reading_files", "responding", "editing_files"} <= phases, phases)
        for s in seen:
            self.assertEqual(set(s), {"chat_id", "session_id", "seq", "state", "phase", "elapsed_ms",
                                      "last_output_at", "heartbeat_at", "can_stop"})
        self.assertGreaterEqual(seen[-1]["elapsed_ms"], 800)
        # nada de status depois do terminal
        events = [e["type"] for e in self.chat_events(started["chat_id"])]
        self.assertEqual(events[-1], "chat.done")
        n = len(seen)
        time.sleep(0.4)
        self.assertEqual(len(self.statuses(started["chat_id"])), n)

    def test_C1_retrato_do_chat_traz_fase_tempo_e_pode_parar(self):
        self.install_claude()
        (self.fake / "mode").write_text("work")
        with unittest.mock.patch.dict(os.environ, {"AURA_CHAT_STATUS_SECS": "0.1"}):
            _, started = self.call("POST", "/v1/chat", {"text": "Trabalhe", "request_id": RID_A})
            snap = self.wait_for(lambda: (lambda s: s if s["phase"] == "reading_files" else None)(
                self.call("GET", "/v1/chat/" + started["chat_id"])[1]))
            self.assertEqual((snap["state"], snap["can_stop"], snap["request_id"]), ("running", True, RID_A))
            self.assertIsNotNone(snap["last_output_at"])
            self.assertIsNone(snap["error"])
            self.wait_final(started["chat_id"])
        done = self.call("GET", "/v1/chat/" + started["chat_id"])[1]
        self.assertEqual((done["state"], done["can_stop"]), ("done", False))

    # ---- parar ----
    def test_C1_parar_insistente_cai_no_sigkill_depois_do_prazo(self):
        from aura_engine import chat
        self.install_claude()
        (self.fake / "mode").write_text("stubborn")
        _, started = self.call("POST", "/v1/chat", {"text": "demora"})
        pid = int(self.wait_for(lambda: (self.fake / "pid").exists() and (self.fake / "pid").read_text() or None))
        with unittest.mock.patch.object(chat, "STOP_GRACE", 0.4):
            code, body = self.call("POST", "/v1/chat/%s/stop" % started["chat_id"], {"request_id": RID_STOP})
            self.assertEqual((code, body["state"], body["stopped"]), (202, "stopping", False))
            self.assertEqual(self.call("GET", "/v1/chat/" + started["chat_id"])[1]["state"], "stopping")
            final = self.wait_final(started["chat_id"])
        self.assertEqual(final["payload"]["code"], "stopped")
        self.assertEqual(self.call("GET", "/v1/chat/" + started["chat_id"])[1]["state"], "interrupted")
        end = time.time() + 5
        while time.time() < end:
            try:
                os.kill(pid, 0)
                time.sleep(0.05)
            except ProcessLookupError:
                break
        with self.assertRaises(ProcessLookupError):
            os.kill(pid, 0)

    def test_C1_parar_guarda_o_texto_parcial_e_parar_de_novo_e_inofensivo(self):
        self.install_claude()
        (self.fake / "mode").write_text("hang")
        _, started = self.call("POST", "/v1/chat", {"text": "demora"})
        self.wait_for(lambda: (self.fake / "pid").exists())
        self.call("POST", "/v1/chat/%s/stop" % started["chat_id"])
        self.wait_final(started["chat_id"])
        code, body = self.call("POST", "/v1/chat/%s/stop" % started["chat_id"])  # já terminou
        self.assertEqual((code, body["state"], body["stopped"]), (200, "interrupted", True))
        self.assertEqual(self.call("POST", "/v1/chat/inexistente/stop")[0], 404)

    def test_C1_parar_mensagem_da_fila_cancela_na_hora_sem_rodar(self):
        self.install_claude()
        (self.fake / "mode").write_text("slow")
        _, a = self.call("POST", "/v1/chat", {"text": "primeira"})
        _, b = self.call("POST", "/v1/chat", {"text": "segunda", "session_id": a["session_id"]})
        code, body = self.call("POST", "/v1/chat/%s/stop" % b["chat_id"])
        self.assertEqual((code, body["state"], body["stopped"]), (200, "interrupted", True))
        self.assertEqual(self.wait_final(a["chat_id"])["type"], "chat.done")
        self.assertEqual(len(self.args()), 1, "a segunda nunca chegou à CLI")

    # ---- conversa certa, sem trocas escondidas ----
    def test_C1_sessao_desconhecida_e_404_session_not_found_sem_conversa_nova(self):
        self.install_claude()
        code, body = self.call("POST", "/v1/chat", {"text": "Ola", "session_id": "99999999-2222-4333-8444-555555555555"})
        self.assertEqual((code, body["error"]["code"]), (404, "session_not_found"))
        self.assertEqual(body["error"]["action"], "back_to_sessions")
        self.assertFalse((self.fake / "args.log").exists(), "nada foi executado")
        self.assertEqual(self.call("GET", "/v1/chats")[1]["chats"], [])

    def test_C1_resume_que_falha_nao_troca_de_sessao_e_pede_decisao(self):
        self.install_claude()
        _, first = self.call("POST", "/v1/chat", {"text": "Ola"})
        self.wait_final(first["chat_id"])
        before = self.engine.store.chat_conversation(first["session_id"])
        (self.fake / "mode").write_text("gone")
        _, second = self.call("POST", "/v1/chat", {"text": "E agora?", "session_id": first["session_id"]})
        final = self.wait_final(second["chat_id"])
        self.assertEqual(final["type"], "chat.error")
        self.assertEqual(final["payload"]["code"], "resume_failed")
        self.assertEqual(final["payload"]["action_pt"], "Tentar retomar")
        self.assertIn("histórico foi preservado", final["payload"]["message_pt"])
        self.assertEqual(len(self.args()), 2, "uma tentativa só: nada de recomeçar escondido")
        after = self.engine.store.chat_conversation(first["session_id"])
        self.assertEqual(after["cli_session"], before["cli_session"])
        _, msgs = self.call("GET", "/v1/chats/%s/messages" % first["session_id"])
        self.assertEqual([m["text"] for m in msgs["messages"] if m["role"] == "user"], ["Ola", "E agora?"])
        # "Criar outra conversa" = enviar sem session_id: identidade nova, a antiga fica
        (self.fake / "mode").write_text("ok")
        _, fresh = self.call("POST", "/v1/chat", {"text": "recomeçando"})
        self.assertNotEqual(fresh["session_id"], first["session_id"])
        self.assertEqual(self.wait_final(fresh["chat_id"])["type"], "chat.done")

    def test_C1_so_o_claude_responde_o_chat_nunca_gemini_ou_agy(self):
        for name in ("gemini", "agy"):
            exe = self.fake / name
            exe.write_text("#!%s\nimport pathlib\npathlib.Path(%r).write_text('rodou')\nprint('resposta')\n"
                           % (sys.executable, str(self.root / ("rodou-" + name))))
            exe.chmod(0o755)
        os.environ["PATH"] = str(self.fake) + os.pathsep + str(self.root / "vazio")
        _, started = self.call("POST", "/v1/chat", {"text": "Ola"})
        final = self.wait_final(started["chat_id"])
        self.assertEqual((final["type"], final["payload"]["code"]), ("chat.error", "no_executor"))
        _, other = self.call("POST", "/v1/chat", {"text": "Ola", "executor": "gemini"})
        self.assertEqual(self.wait_final(other["chat_id"])["type"], "chat.error")
        for name in ("gemini", "agy"):
            self.assertFalse((self.root / ("rodou-" + name)).exists(), name + " não pode responder o chat")
