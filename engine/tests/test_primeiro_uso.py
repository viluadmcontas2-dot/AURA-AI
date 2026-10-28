"""Defeitos do PRIMEIRO USO no celular (auditoria adversarial): motor real por HTTP + `claude` falso."""
import json
import os
import unittest

from test_chat import ChatBase


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


if __name__ == "__main__":
    unittest.main()
