"""Ajuda dos testes que NÃO são da barreira de instalação (cadeia REA, rede, ledger, autonomia).

Eles exercitam o que acontece DEPOIS do aceite; por isso liberam a permissão de instalação com um único ponto de
troca (`install_consent.current_grant`). A barreira em si é provada em test_install_consent.py, sem esta troca.
"""
import unittest.mock

from aura_engine import install_consent


class AllowAll(install_consent.Grant):
    def __init__(self):
        super().__init__("teste", [])

    def permits(self, argv):
        return True


def allow_installs(testcase):
    patch = unittest.mock.patch.object(install_consent, "current_grant", lambda engine: AllowAll())
    patch.start()
    testcase.addCleanup(patch.stop)
