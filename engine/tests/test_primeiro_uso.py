"""Defeitos do PRIMEIRO USO no celular (auditoria adversarial): motor real por HTTP + `claude` falso."""
import hashlib
import json
import os
import pathlib
import re
import shutil
import subprocess
import time
import sys
import unittest
import unittest.mock

from aura_engine import adapters, cli_setup
from test_chat import FAKE_CLAUDE, ChatBase


class SemClaude(ChatBase):
    def test_PU1_sem_claude_pede_instalar_em_conexoes(self):
        _, started = self.call("POST", "/v1/chat", {"text": "Ola"})
        p = self.wait_final(started["chat_id"])["payload"]
        self.assertEqual(p["code"], "no_executor")
        self.assertEqual(p["message_pt"], "O Claude ainda não está instalado neste Termux.")
        self.assertEqual(p["action_pt"], "Instalar o Claude em Conexões")

    def test_PU1_executor_pedido_ausente_tambem_pede_instalar(self):
        _, started = self.call("POST", "/v1/chat", {"text": "Ola", "executor": "claude"})
        p = self.wait_final(started["chat_id"])["payload"]
        self.assertEqual(p["code"], "cli_missing")
        self.assertEqual(p["message_pt"], "O Claude ainda não está instalado neste Termux.")
        self.assertEqual(p["action_pt"], "Instalar o Claude em Conexões")


class ClaudeNoPathDoMotor(ChatBase):
    def test_PU2_claude_em_local_bin_e_achado_mesmo_fora_do_PATH(self):
        casa = self.root / "casa"
        (casa / ".local" / "bin").mkdir(parents=True)
        exe = casa / ".local" / "bin" / "claude"
        exe.write_text(FAKE_CLAUDE % {"py": sys.executable})
        exe.chmod(0o755)
        with unittest.mock.patch.dict(os.environ, {"HOME": str(casa)}):
            self.assertTrue(adapters.installed("claude"))
            _, started = self.call("POST", "/v1/chat", {"text": "Ola"})
            self.assertEqual(self.wait_final(started["chat_id"])["type"], "chat.done")

    def test_PU2_sem_o_diretorio_o_claude_continua_ausente(self):
        casa = self.root / "casa-vazia"
        casa.mkdir()
        with unittest.mock.patch.dict(os.environ, {"HOME": str(casa)}):
            self.assertFalse(adapters.installed("claude"))


STDIN_PRELUDE = '''import json, os, sys
open(os.environ["AURA_FAKE_DIR"] + "/stdin.log", "a").write(json.dumps(sys.stdin.read()) + "\\n")
'''
FAKE_GEMINI = """#!%(py)s
import json, os, sys
d = os.environ["AURA_FAKE_DIR"]
open(d + "/gemini-args.log", "a").write(json.dumps(sys.argv[1:]) + "\\n")
open(d + "/gemini-stdin.log", "a").write(json.dumps(sys.stdin.read()) + "\\n")
print("resposta do gemini")
"""


class TextoLongoEPrefixoTraco(ChatBase):
    def install_claude_stdin(self):
        path = self.fake / "claude"
        path.write_text((FAKE_CLAUDE % {"py": sys.executable}).replace(
            "import json, os, sys, time\n", "import json, os, sys, time\n" + STDIN_PRELUDE, 1))
        path.chmod(0o755)
        os.environ["PATH"] = str(self.fake) + os.pathsep + str(self.root / "vazio")

    def stdins(self, nome="stdin.log"):
        return [json.loads(x) for x in (self.fake / nome).read_text().splitlines()]

    def test_PU4_texto_de_30000_caracteres_vai_por_stdin_e_responde(self):
        self.install_claude_stdin()
        texto = ("ditado longo " * 3000)[:30000]
        _, started = self.call("POST", "/v1/chat", {"text": texto})
        self.assertEqual(self.wait_final(started["chat_id"])["type"], "chat.done")
        argv = self.args()[0]
        self.assertTrue(all(len(a) < 1000 for a in argv), [len(a) for a in argv])
        self.assertEqual(argv[0], "-p")
        self.assertEqual(self.stdins()[0], texto)

    def test_PU4_texto_que_comeca_com_traco_nao_vira_opcao(self):
        self.install_claude_stdin()
        texto = "--help e -x: o que e isso?"
        _, started = self.call("POST", "/v1/chat", {"text": texto})
        self.assertEqual(self.wait_final(started["chat_id"])["type"], "chat.done")
        self.assertNotIn(texto, self.args()[0])
        self.assertEqual(self.stdins()[0], texto)

    def test_PU4_texto_curto_continua_no_argv(self):
        self.install_claude_stdin()
        _, started = self.call("POST", "/v1/chat", {"text": "Ola"})
        self.wait_final(started["chat_id"])
        self.assertEqual(self.args()[0][:2], ["-p", "Ola"])
        self.assertEqual(self.stdins()[0], "")

    def test_PU4_gemini_com_texto_longo_tambem_usa_stdin(self):
        gemini = self.fake / "gemini"
        gemini.write_text(FAKE_GEMINI % {"py": sys.executable})
        gemini.chmod(0o755)
        os.environ["PATH"] = str(self.fake) + os.pathsep + str(self.root / "vazio")
        texto = "-" + "a" * 20000
        _, started = self.call("POST", "/v1/chat", {"text": texto})
        final = self.wait_final(started["chat_id"])
        self.assertEqual(final["type"], "chat.done")
        self.assertTrue(all(len(a) < 1000 for a in self.args("gemini-args.log")[0]))
        self.assertEqual(self.stdins("gemini-stdin.log")[0], texto)

    def args(self, nome="args.log"):
        return [json.loads(x) for x in (self.fake / nome).read_text().splitlines()]


PRELUDE_MODOS = """if mode == "noresume" and "--resume" in sys.argv and sid.startswith("11111111-"):
    out({"type": "result", "subtype": "error", "is_error": True, "session_id": sid,
         "result": "No conversation found with session ID: " + sid})
    sys.exit(1)
if mode == "partial":
    out({"type": "stream_event", "session_id": sid, "event": {"type": "message_start"}})
    out({"type": "stream_event", "session_id": sid, "event": {"type": "content_block_delta", "index": 0,
         "delta": {"type": "text_delta", "text": "Resposta pela metade"}}})
    time.sleep(60)
"""
UUID_DESCONHECIDO = "11111111-2222-4333-8444-555555555555"


class SessaoDesconhecidaETimeout(ChatBase):
    def install_claude_modos(self):
        path = self.fake / "claude"
        path.write_text((FAKE_CLAUDE % {"py": sys.executable}).replace(
            'if mode == "hang":', PRELUDE_MODOS + 'if mode == "hang":', 1))
        path.chmod(0o755)
        os.environ["PATH"] = str(self.fake) + os.pathsep + str(self.root / "vazio")

    def test_PU5_resume_de_sessao_desconhecida_recomeca_uma_vez_como_conversa_nova(self):
        self.install_claude_modos()
        (self.fake / "mode").write_text("noresume")
        _, started = self.call("POST", "/v1/chat", {"text": "Ola", "session_id": UUID_DESCONHECIDO})
        final = self.wait_final(started["chat_id"])
        self.assertEqual(final["type"], "chat.done", final)
        self.assertTrue(final["payload"]["new_conversation"])
        self.assertEqual(final["payload"]["session_id"], UUID_DESCONHECIDO)
        primeira, segunda = self.args()
        self.assertIn("--resume", primeira)
        self.assertIn("--session-id", segunda)
        self.assertNotIn("--resume", segunda)
        # a proxima mensagem na mesma conversa retoma a sessao nova da CLI, sem novo recomeco
        _, again = self.call("POST", "/v1/chat", {"text": "E agora?", "session_id": UUID_DESCONHECIDO})
        done = self.wait_final(again["chat_id"])
        self.assertEqual(done["type"], "chat.done", done)
        self.assertNotIn("new_conversation", done["payload"])
        terceira = self.args()[2]
        self.assertEqual(terceira[terceira.index("--resume") + 1], segunda[segunda.index("--session-id") + 1])

    def test_PU6_timeout_padrao_1h_e_configuravel(self):
        from aura_engine import chat
        os.environ.pop("AURA_CHAT_TIMEOUT", None)
        self.assertEqual(chat.chat_timeout(), 3600)
        with unittest.mock.patch.dict(os.environ, {"AURA_CHAT_TIMEOUT": "120"}):
            self.assertEqual(chat.chat_timeout(), 120)
        with unittest.mock.patch.dict(os.environ, {"AURA_CHAT_TIMEOUT": "lixo"}):
            self.assertEqual(chat.chat_timeout(), 3600)

    def test_PU6_timeout_guarda_o_texto_parcial_no_historico(self):
        self.install_claude_modos()
        (self.fake / "mode").write_text("partial")
        with unittest.mock.patch.dict(os.environ, {"AURA_CHAT_TIMEOUT": "1"}):
            _, started = self.call("POST", "/v1/chat", {"text": "demora"})
            final = self.wait_final(started["chat_id"], timeout=10)
        self.assertEqual((final["type"], final["payload"]["code"]), ("chat.error", "timeout"))
        _, msgs = self.call("GET", "/v1/chats/%s/messages" % started["session_id"])
        self.assertEqual([(m["role"], m["text"]) for m in msgs["messages"]],
                         [("user", "demora"), ("assistant", "Resposta pela metade")])
        _, state = self.call("GET", "/v1/chat/" + started["chat_id"])
        self.assertEqual(state["text"], "Resposta pela metade")

    def test_PU6_parar_guarda_o_texto_parcial_no_historico(self):
        self.install_claude_modos()
        (self.fake / "mode").write_text("partial")
        _, started = self.call("POST", "/v1/chat", {"text": "demora"})
        end = time.time() + 6
        while time.time() < end and "Resposta pela metade" not in (self.call("GET", "/v1/chat/" + started["chat_id"])[1].get("text") or ""):
            time.sleep(0.05)
        self.call("POST", "/v1/chat/%s/stop" % started["chat_id"])
        self.assertEqual(self.wait_final(started["chat_id"])["payload"]["code"], "stopped")
        _, msgs = self.call("GET", "/v1/chats/%s/messages" % started["session_id"])
        self.assertEqual(msgs["messages"][-1]["text"], "Resposta pela metade")
        self.assertEqual(msgs["messages"][-1]["role"], "assistant")


class VersaoDoMotor(ChatBase):
    def test_PU3_health_traz_engine_build_do_conteudo_de_aura_engine(self):
        code, body = self.call("GET", "/v1/health")
        self.assertEqual((code, body["version"]), (200, 1))
        self.assertRegex(body["engine_build"], r"^[0-9a-f]{12}$")
        pacote = pathlib.Path(adapters.__file__).parent
        digest = hashlib.sha256()
        arquivos = sorted((p for p in pacote.rglob("*") if p.is_file() and "__pycache__" not in p.parts
                           and p.suffix != ".pyc"), key=lambda p: p.relative_to(pacote).as_posix().encode())
        for p in arquivos:
            digest.update(p.relative_to(pacote).as_posix().encode() + b"\0" + p.read_bytes() + b"\0")
        self.assertEqual(body["engine_build"], digest.hexdigest()[:12])

    def test_PU3_build_muda_quando_o_codigo_muda(self):
        import tempfile
        from aura_engine.build import engine_build
        with tempfile.TemporaryDirectory() as tmp:
            raiz = pathlib.Path(tmp)
            (raiz / "a.py").write_text("x = 1\n")
            antes = engine_build(raiz)
            (raiz / "__pycache__").mkdir()
            (raiz / "__pycache__" / "a.pyc").write_bytes(b"lixo")
            self.assertEqual(engine_build(raiz), antes)  # cache de bytecode nao conta
            (raiz / "a.py").write_text("x = 2\n")
            self.assertNotEqual(engine_build(raiz), antes)


class InstaladorDeTerceiro(unittest.TestCase):
    def test_PU2_fallback_fixa_commit_e_sha256_nunca_a_branch(self):
        self.assertRegex(cli_setup.CLAUDE_ANDROID_COMMIT, r"^[0-9a-f]{40}$")
        self.assertRegex(cli_setup.CLAUDE_ANDROID_SHA256, r"^[0-9a-f]{64}$")
        self.assertIn(cli_setup.CLAUDE_ANDROID_COMMIT, cli_setup.CLAUDE_ANDROID_URL)
        self.assertNotIn("/main/", cli_setup.CLAUDE_ANDROID_URL)

    @unittest.skipUnless(shutil.which("sha256sum") and shutil.which("bash"), "precisa de sha256sum e bash")
    def test_PU2_script_so_roda_se_o_sha256_bater(self):
        import tempfile
        with tempfile.TemporaryDirectory() as tmp:
            home = pathlib.Path(tmp)
            (home / "tools").mkdir()
            marca = home / "rodou"
            script = home / "tools" / "claude-code-android-install.sh"
            script.write_text("#!/bin/bash\ntouch %s\n" % marca)
            certo = hashlib.sha256(script.read_bytes()).hexdigest()
            with unittest.mock.patch.object(cli_setup, "CLAUDE_ANDROID_SHA256", certo):
                comando = cli_setup._claude_fallback(home)[0][1][1]
            self.assertEqual(comando[:2], ["sh", "-c"])
            self.assertEqual(subprocess.run(comando, stdin=subprocess.DEVNULL).returncode, 0)
            self.assertTrue(marca.exists())
            marca.unlink()
            script.write_text("#!/bin/bash\ntouch %s\n# adulterado\n" % marca)  # conteudo mudou
            self.assertNotEqual(subprocess.run(comando, stdin=subprocess.DEVNULL, stderr=subprocess.DEVNULL,
                                               stdout=subprocess.DEVNULL).returncode, 0)
            self.assertFalse(marca.exists())


if __name__ == "__main__":
    unittest.main()
