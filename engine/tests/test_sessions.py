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
        self.assertEqual(set(term), {"id", "title", "project", "updated_at", "messages", "source", "running",
                                     "canonical_id", "history_complete", "active_chat_id"})
        self.assertEqual((term["canonical_id"], term["history_complete"], term["active_chat_id"]),
                         (SID_TERM, True, None))
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


def msg_user(text, uid, ts="2026-10-01T10:00:00Z"):
    return {"type": "user", "uuid": uid, "timestamp": ts, "message": {"role": "user", "content": text}}


def msg_ai(text, uid, ts="2026-10-01T10:00:05Z"):
    return {"type": "assistant", "uuid": uid, "timestamp": ts,
            "message": {"role": "assistant", "content": [{"type": "text", "text": text}]}}


class SessoesVivas(SessoesBase):
    """Sincronização viva: /state barato, mensagens incrementais e paginadas, IDs estáveis, sem perder o final."""

    def append(self, path, *events):
        with open(str(path), "a", encoding="utf-8") as f:
            f.write(jl(*events))

    def new_file(self, sid, *events, folder="-grande"):
        d = self.proj / folder
        d.mkdir(parents=True, exist_ok=True)
        path = d / (sid + ".jsonl")
        path.write_text(jl(*events), encoding="utf-8")
        return path

    def state(self, sid):
        code, body = self.call("GET", "/v1/sessions/%s/state" % sid)
        self.assertEqual(code, 200, body)
        return body

    def msgs(self, sid, query=""):
        code, body = self.call("GET", "/v1/sessions/%s/messages%s" % (sid, query))
        self.assertEqual(code, 200, body)
        return body

    # ---- estado barato ----
    def test_state_traz_versao_contagem_e_muda_quando_o_jsonl_cresce(self):
        a = self.state(SID_AURA)
        self.assertEqual((a["session_id"], a["messages"], a["running"], a["active_chat_id"]), (SID_AURA, 2, False, None))
        for campo in ("version", "updated_at", "total", "history_complete", "external_running", "last_chat"):
            self.assertIn(campo, a)
        self.assertEqual(self.state(SID_AURA)["version"], a["version"], "sem mudança, mesma versão")
        self.append(self.aura_file, msg_user("Mais uma", "u2"), msg_ai("Feito", "a2"))
        b = self.state(SID_AURA)
        self.assertNotEqual(b["version"], a["version"])
        self.assertEqual((b["messages"], b["total"]), (4, 4))
        self.assertEqual(self.call("GET", "/v1/sessions/%s/state" % SID_BAD)[0], 404)
        self.assertEqual(self.call("GET", "/v1/sessions/%s/state" % "44444444-4444-4444-8444-444444444444")[0], 404)

    def test_mensagem_acrescentada_por_fora_vem_incremental_e_20_ciclos_nao_duplicam(self):
        full = self.msgs(SID_AURA)
        held = {m["id"]: m for m in full["messages"]}
        order = [m["id"] for m in full["messages"]]
        total = full["total"]
        for n in range(20):
            self.append(self.aura_file, msg_user("pergunta %d" % n, "ux%d" % n), msg_ai("resposta %d" % n, "ax%d" % n))
            page = self.msgs(SID_AURA, "?after=%d" % max(0, total - 2))  # o cliente pede com uma folga
            for m in page["messages"]:
                if m["id"] not in held:
                    order.append(m["id"])
                held[m["id"]] = m
            total = page["total"]
        self.assertEqual(len(order), len(set(order)), "nenhum id repetido")
        self.assertEqual(len(order), 2 + 40)
        truth = [m["id"] for m in self.msgs(SID_AURA, "?limit=2000")["messages"]]
        self.assertEqual(order, truth, "mesma lista e mesma ordem que o histórico completo")

    def test_ids_sao_estaveis_por_origem_e_nao_dependem_do_recorte(self):
        eventos = [e for n in range(30) for e in (msg_user("p%d" % n, "u%d" % n), msg_ai("r%d" % n, "a%d" % n))]
        self.new_file("55555555-5555-4555-8555-555555555555", *eventos)
        sid = "55555555-5555-4555-8555-555555555555"
        todas = self.msgs(sid, "?limit=2000")["messages"]
        cauda = self.msgs(sid, "?limit=10")["messages"]
        self.assertEqual([m["id"] for m in cauda], [m["id"] for m in todas[-10:]])
        self.assertEqual(todas[0]["id"], "claude:u0:0")
        self.assertTrue(all(isinstance(m["id"], str) for m in todas))

    # ---- arquivos grandes e paginação ----
    def test_arquivo_maior_que_8MiB_mostra_o_final_e_nao_marca_parcial(self):
        sid = "66666666-6666-4666-8666-666666666666"
        enorme = "x" * 4000
        eventos = [msg_user("antigo %d %s" % (n, enorme), "g%d" % n) for n in range(2600)]  # ~10,4 MiB
        eventos += [msg_user("PENULTIMA", "u-fim"), msg_ai("A ULTIMA RESPOSTA", "a-fim")]
        path = self.new_file(sid, *eventos)
        self.assertGreater(path.stat().st_size, 8 * 1024 * 1024)
        body = self.msgs(sid)
        self.assertEqual(body["messages"][-1]["text"], "A ULTIMA RESPOSTA")
        self.assertEqual(body["messages"][-2]["text"], "PENULTIMA")
        self.assertTrue(body["history_complete"])
        self.assertNotIn("warning_code", body)
        self.assertEqual(body["total"], 2602)
        self.assertEqual(self.state(sid)["messages"], 2602)

    def test_mais_de_500_mensagens_paginam_sem_repetir_nem_pular(self):
        sid = "77777777-7777-4777-8777-777777777777"
        eventos = [e for n in range(300) for e in (msg_user("p%d" % n, "u%d" % n), msg_ai("r%d" % n, "a%d" % n))]
        self.new_file(sid, *eventos)
        page = self.msgs(sid, "?limit=100")
        self.assertEqual((len(page["messages"]), page["has_more"], page["first_index"]), (100, True, 500))
        got = [m["id"] for m in page["messages"]]
        while page["has_more"]:
            page = self.msgs(sid, "?limit=100&before=" + page["previous_cursor"])
            got = [m["id"] for m in page["messages"]] + got
        self.assertEqual(len(got), 600)
        self.assertEqual(len(set(got)), 600)
        self.assertEqual(got[0], "claude:u0:0")
        self.assertEqual(got[-1], "claude:a299:0")
        self.assertIsNone(page["previous_cursor"])
        self.assertFalse(page["has_more"])
        self.assertEqual(set(page), {"session_id", "messages", "previous_cursor", "has_more", "history_complete",
                                     "source", "revision", "active_chat_id", "total", "first_index"})

    def test_pagina_anterior_continua_valida_mesmo_com_mensagens_novas_no_fim(self):
        sid = "77777777-7777-4777-8777-777777777778"
        path = self.new_file(sid, *[e for n in range(60) for e in (msg_user("p%d" % n, "u%d" % n), msg_ai("r%d" % n, "a%d" % n))])
        page = self.msgs(sid, "?limit=20")
        self.append(path, msg_user("nova", "u-nova"), msg_ai("nova resposta", "a-nova"))
        older = self.msgs(sid, "?limit=20&before=" + page["previous_cursor"])
        ids = [m["id"] for m in older["messages"] + page["messages"]]
        self.assertEqual(len(ids), len(set(ids)), "a atualização concorrente não duplica ids")
        truth = [m["id"] for m in self.msgs(sid, "?limit=2000")["messages"]]
        first = page["first_index"]
        self.assertEqual([m["id"] for m in older["messages"]], truth[first - 20:first], "a página anterior continua a mesma")

    def test_limite_de_leitura_da_fonte_marca_source_partial_sem_esconder(self):
        from aura_engine import sessions
        sid = "88888888-8888-4888-8888-888888888888"
        self.new_file(sid, *[e for n in range(200) for e in (msg_user("pergunta %d" % n, "u%d" % n), msg_ai("resposta %d" % n, "a%d" % n))])
        with unittest.mock.patch.object(sessions, "MAX_BYTES", 6000):
            body = self.msgs(sid, "?limit=2000")
        self.assertFalse(body["history_complete"])
        self.assertEqual(body["warning_code"], "source_partial")
        self.assertEqual(body["messages"][-1]["text"], "resposta 199", "o final nunca se perde")
        self.assertLess(len(body["messages"]), 400)
        row = [r for r in self.call("GET", "/v1/sessions")[1]["sessions"] if r["id"] == sid]
        self.assertTrue(row == [] or row[0]["history_complete"] in (True, False))

    def test_cursor_de_arquivo_reescrito_e_409_history_changed(self):
        sid = "99999999-9999-4999-8999-999999999999"
        path = self.new_file(sid, *[e for n in range(30) for e in (msg_user("p%d" % n, "u%d" % n), msg_ai("r%d" % n, "a%d" % n))])
        page = self.msgs(sid, "?limit=10")
        path.write_text(jl(msg_user("recomeço", "r0"), msg_ai("ok", "r1")), encoding="utf-8")  # arquivo foi reescrito
        code, body = self.call("GET", "/v1/sessions/%s/messages?limit=10&before=%s" % (sid, page["previous_cursor"]))
        self.assertEqual((code, body["error"]["code"]), (409, "history_changed"))
        self.assertTrue(body["error"]["retryable"])
        self.assertEqual(self.call("GET", "/v1/sessions/%s/messages?before=lixo" % sid)[0], 400)
        fresh = self.msgs(sid)
        self.assertEqual([m["text"] for m in fresh["messages"]], ["recomeço", "ok"])

    # ---- identidade ----
    def test_alias_de_cli_session_aparece_uma_vez_com_id_canonico(self):
        alias = "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa"
        canon = "bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb"
        self.new_file(alias, msg_user("conversa que a CLI renomeou", "u1"), msg_ai("ok", "a1"))
        self.engine.store.chat_open(canon, "conversa", "default", cli_session=alias, started=True)
        self.engine.store.chat_add_message(canon, "c1", "user", "conversa")
        rows = self.call("GET", "/v1/sessions")[1]["sessions"]
        ids = [r["id"] for r in rows]
        self.assertIn(canon, ids)
        self.assertNotIn(alias, ids, "o alias não vira uma segunda linha")
        row = [r for r in rows if r["id"] == canon][0]
        self.assertEqual(row["canonical_id"], canon)
        texts = [m["text"] for m in self.msgs(canon)["messages"]]
        self.assertEqual(texts, ["conversa que a CLI renomeou", "ok"])

    def test_continuar_sessao_do_terminal_no_aura_preserva_a_origem_terminal(self):
        self.install_fake()
        _, started = self.call("POST", "/v1/chat", {"text": "continue", "session_id": SID_TERM})
        self.wait_final(started["chat_id"])
        rows = {r["id"]: r for r in self.call("GET", "/v1/sessions")[1]["sessions"]}
        self.assertEqual(rows[SID_TERM]["source"], "terminal")
        self.assertEqual(self.msgs(SID_TERM)["source"], "terminal")
        self.assertEqual(self.msgs(SID_AURA)["source"], "aura")

    def test_sessao_sem_jsonl_usa_o_historico_do_motor_com_ids_proprios(self):
        sid = "cccccccc-cccc-4ccc-8ccc-cccccccccccc"
        self.engine.store.chat_open(sid, "so no motor", "default")
        self.engine.store.chat_add_message(sid, "c1", "user", "oi")
        self.engine.store.chat_add_message(sid, "c1", "assistant", "olá")
        body = self.msgs(sid)
        self.assertEqual([m["text"] for m in body["messages"]], ["oi", "olá"])
        self.assertTrue(all(m["id"].startswith("aura:") for m in body["messages"]))
        v1 = self.state(sid)["version"]
        self.engine.store.chat_add_message(sid, "c2", "user", "mais")
        self.assertNotEqual(self.state(sid)["version"], v1)

    # ---- o que acontece agora ----
    def test_terminal_escrevendo_agora_marca_external_running(self):
        self.assertFalse(self.state(SID_TERM)["external_running"])  # arquivo velho
        self.append(self.term_file, msg_ai("continuando no terminal", "t1"))  # mtime = agora
        st = self.state(SID_TERM)
        self.assertTrue(st["external_running"])
        self.assertFalse(st["running"], "running é só do que o motor executa")

    def test_estado_mostra_a_resposta_em_andamento_do_motor_e_o_ultimo_chat(self):
        self.install_fake()
        (self.fake / "hang").write_text("1")
        _, started = self.call("POST", "/v1/chat", {"text": "demora", "session_id": SID_TERM, "request_id": "11111111-1111-4111-8111-111111111111"})
        self.wait_for(lambda: (self.fake / "pid").exists())
        st = self.state(SID_TERM)
        self.assertTrue(st["running"])
        self.assertEqual(st["active_chat_id"], started["chat_id"])
        self.assertEqual(st["last_chat"]["chat_id"], started["chat_id"])
        self.assertEqual(st["last_chat"]["state"], "running")
        self.call("POST", "/v1/chat/%s/stop" % started["chat_id"])
        self.wait_final(started["chat_id"])
        st = self.state(SID_TERM)
        self.assertFalse(st["running"])
        self.assertEqual((st["last_chat"]["state"], st["last_chat"]["chat_id"]), ("interrupted", started["chat_id"]))
        self.assertIsNone(st["active_chat_id"])

    def test_watch_faz_o_motor_avisar_session_updated_quando_o_arquivo_muda(self):
        with unittest.mock.patch.dict(os.environ, {"AURA_SESSION_WATCH_SECS": "0.1"}):
            code, body = self.call("POST", "/v1/sessions/%s/watch" % SID_AURA, {})
            self.assertEqual((code, body["session_id"], body["expires_in"]), (200, SID_AURA, 60))
            time.sleep(0.4)
            before = [e for e in self.engine.store.events(0, 1000) if e["type"] == "session.updated"]
            self.assertEqual(before, [], "sem mudança, sem evento")
            self.append(self.aura_file, msg_user("de fora", "ux"), msg_ai("resposta de fora", "ax"))
            evs = self.wait_for(lambda: [e for e in self.engine.store.events(0, 1000) if e["type"] == "session.updated"])
        self.assertEqual(evs[0]["payload"]["session_id"], SID_AURA)
        self.assertEqual(evs[0]["payload"]["version"], self.state(SID_AURA)["version"])
        self.assertEqual(self.call("POST", "/v1/sessions/%s/watch" % SID_BAD, {})[0], 404)

    def test_watch_sem_renovacao_expira_e_para_de_avisar(self):
        from aura_engine import sessions_watch
        with unittest.mock.patch.dict(os.environ, {"AURA_SESSION_WATCH_SECS": "0.1"}), \
                unittest.mock.patch.object(sessions_watch, "TTL", 0.3):
            self.call("POST", "/v1/sessions/%s/watch" % SID_AURA, {})
            time.sleep(0.8)  # passou do prazo sem renovar
            self.append(self.aura_file, msg_user("tarde demais", "ux"))
            time.sleep(0.6)
        self.assertEqual([e for e in self.engine.store.events(0, 1000) if e["type"] == "session.updated"], [])

    def test_lista_pagina_com_limit_e_next_cursor(self):
        for n in range(5):
            self.new_file("dddddddd-dddd-4ddd-8ddd-ddddddddddd%d" % n, msg_user("s%d" % n, "u%d" % n), msg_ai("ok", "a%d" % n))
        first = self.call("GET", "/v1/sessions?limit=3")[1]
        self.assertEqual(len(first["sessions"]), 3)
        self.assertTrue(first["next_cursor"])
        rest = self.call("GET", "/v1/sessions?limit=3&cursor=" + first["next_cursor"])[1]
        ids = [r["id"] for r in first["sessions"] + rest["sessions"]]
        self.assertEqual(len(ids), len(set(ids)))
        everything = [r["id"] for r in self.call("GET", "/v1/sessions?limit=100")[1]["sessions"]]
        self.assertEqual(ids, everything[:len(ids)])
        self.assertIsNone(self.call("GET", "/v1/sessions?limit=100")[1]["next_cursor"])



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
