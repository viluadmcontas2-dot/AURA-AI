"""Defeitos do PRIMEIRO USO no celular (auditoria adversarial): motor real por HTTP + `claude` falso."""
import hashlib
import json
import os
import pathlib
import re
import shutil
import subprocess
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
