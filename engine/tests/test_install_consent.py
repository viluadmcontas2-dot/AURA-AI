"""C4: nada é baixado nem instalado sem a decisão do dono (docs/FLUXOS_DE_USO.md, seção 7).

Espiões: `subprocess.Popen` (todo processo que o motor inicia) e `urllib.request.urlopen` (toda rede do Python)
são trocados por falsos que só ANOTAM. Cada teste prova que, antes do aceite, depois da recusa, com plano vencido,
alterado ou de tamanho desconhecido, nenhum comando de instalação chegou a ser iniciado.
"""
import json
import os
import pathlib
import tempfile
import threading
import unittest
import unittest.mock
import urllib.error
import urllib.request

from aura_engine import adapters, cli_setup, install_consent, rea_chain
from aura_engine.core import Engine
from aura_engine.server import make_server

APT_OUT = ("The following NEW packages will be installed:\n  pacote-a pacote-b\n"
           "Need to get 10.0 MB/12 MB of archives.\n"
           "After this operation, 30.0 MB of additional disk space will be used.\n")
BASE_TOOLS = {"node", "npm", "git", "apt-get", "curl", "sh", "bash", "pkg", "javac", "apktool", "adb"}


class FakePopen:
    """Anota o comando e responde como o Termux responderia. Nunca inicia processo de verdade."""
    log = []
    present = set()
    apt_known = True
    on_run = None  # chamado com o argv de cada comando

    def __init__(self, argv, *args, **kwargs):
        self.argv, self.returncode, self.pid = list(argv), 0, 4242
        FakePopen.log.append(self.argv)
        if FakePopen.on_run:
            FakePopen.on_run(self.argv)

    def communicate(self, input=None, timeout=None):
        out = ""
        if self.argv[:3] == ["apt-get", "-s", "install"] and FakePopen.apt_known:
            out = APT_OUT
        elif self.argv[0] == "curl" and "-I" in self.argv:
            out = "HTTP/2 200\r\ncontent-length: 5242880\r\n"
        elif self.argv[-1] == "--version":
            out = "1.0.0\n"
        return out.encode(), b""

    def poll(self):
        return 0

    def terminate(self):
        pass

    def kill(self):
        pass


class ConsentBase(unittest.TestCase):
    def setUp(self):
        temp = tempfile.TemporaryDirectory()
        self.addCleanup(temp.cleanup)
        self.root = pathlib.Path(temp.name)
        FakePopen.log, FakePopen.present, FakePopen.apt_known, FakePopen.on_run = [], set(BASE_TOOLS), True, None
        self.network = []
        for target, fake in (
                ("aura_engine.adapters.subprocess.Popen", FakePopen),
                ("aura_engine.adapters.installed", lambda name: name in FakePopen.present),
                ("urllib.request.urlopen", lambda *a, **k: self.network.append(a) or self.fail("rede"))):
            patch = unittest.mock.patch(target, fake)
            patch.start()
            self.addCleanup(patch.stop)
        self.start_engine()

    def start_engine(self):
        self.now = getattr(self, "now", [1_000_000.0])
        self.engine = Engine(home=self.root / "home", auto_worker=False)
        self.engine.installs.clock = lambda: self.now[0]
        server = make_server(self.engine, "127.0.0.1", 0)
        threading.Thread(target=server.serve_forever, daemon=True).start()
        self.base = "http://127.0.0.1:%d" % server.server_port
        self.addCleanup(self.engine.close)
        self.addCleanup(server.server_close)
        self.addCleanup(server.shutdown)

    def restart_engine(self):
        self.engine.close()
        self.start_engine()

    def call(self, method, path, body=None):
        data = json.dumps(body).encode() if body is not None else (b"" if method != "GET" else None)
        req = urllib.request.Request(self.base + path, data=data, method=method, headers={
            "Authorization": "Bearer " + self.engine.token, "Content-Type": "application/json"})
        try:
            with _REAL_URLOPEN(req, timeout=10) as r:
                return r.status, json.load(r)
        except urllib.error.HTTPError as err:
            return err.code, json.load(err)

    # ---- espiões -------------------------------------------------------------------------------------------
    def installs(self):
        """Comandos de instalação/baixa que de fato foram iniciados (consultas de metadados não contam)."""
        return [a for a in FakePopen.log if install_consent.install_reason(a)]

    def assertNothingInstalled(self):
        self.assertEqual(self.installs(), [])
        self.assertEqual(self.network, [])

    # ---- atalhos do fluxo ----------------------------------------------------------------------------------
    def plan(self, target="claude", n=1, **extra):
        code, plan = self.call("POST", "/v1/install-plans", dict({
            "request_id": "pedido-plano-%d" % n, "target": target, "reason": "Responder aos seus pedidos"}, **extra))
        self.assertIn(code, (200, 201), plan)
        return plan

    def decide(self, plan, decision="install", n=1, **extra):
        return self.call("POST", "/v1/install-plans/%s/decision" % plan["plan_id"], dict({
            "request_id": "pedido-decisao-%d" % n, "manifest_hash": plan["manifest_hash"],
            "decision": decision}, **extra))

    def run_jobs(self):
        ran = []
        while True:
            job = self.engine.run_once()
            if job is None:
                return ran
            ran.append(job)

    def npm_installs_claude(self):
        """Quando o npm 'instala' o claude, ele passa a existir no PATH falso."""
        def hook(argv):
            if argv[0] == "npm" and "install" in argv:
                FakePopen.present.add("claude")
        FakePopen.on_run = hook


_REAL_URLOPEN = urllib.request.urlopen


class Detector(unittest.TestCase):
    def test_comandos_que_instalam_ou_baixam_sao_reconhecidos(self):
        for argv in (["pkg", "install", "-y", "python"], ["npm", "install", "-g", "x"], ["pip", "install", "x"],
                     ["pip3", "install", "x"], ["python", "-m", "pip", "install", "x"], ["apt", "install", "x"],
                     ["curl", "-fsSL", "-o", "x.sh", "https://exemplo.com/x.sh"], ["wget", "https://exemplo.com/x"],
                     ["sh", "-c", "pkg install -y vim"], ["bash", "-c", "curl https://a.b/x | sh"],
                     ["npx", "cowsay"], ["cargo", "install", "x"], ["gh", "extension", "install", "a/b"]):
            self.assertTrue(install_consent.install_reason(argv), argv)

    def test_consulta_de_metadados_e_comandos_comuns_nao_sao_instalacao(self):
        for argv in (["apt-get", "-s", "install", "-y", "x"], ["curl", "-s", "-I", "-L", "https://a.b/x"],
                     ["curl", "http://127.0.0.1:8765/v1/health"], ["echo", "oi"], ["ls"], ["node", "--version"],
                     ["sh", "-c", "echo oi"], ["npm", "--version"], ["pkg", "list-installed"], ["git", "status"]):
            self.assertIsNone(install_consent.install_reason(argv), argv)

    def test_script_que_instala_e_reconhecido_pelo_conteudo(self):
        with tempfile.TemporaryDirectory() as tmp:
            ruim = pathlib.Path(tmp) / "ruim.sh"
            ruim.write_text("#!/bin/sh\nnpm install -g algo\n")
            bom = pathlib.Path(tmp) / "bom.sh"
            bom.write_text("#!/bin/sh\necho oi\n")
            self.assertTrue(install_consent.install_reason(["bash", str(ruim)]))
            self.assertIsNone(install_consent.install_reason(["bash", str(bom)]))

    def test_permissao_vale_so_para_os_comandos_do_plano(self):
        grant = install_consent.Grant("plan-x", [["npm", "install", "-g", "a"]])
        self.assertTrue(grant.permits(["npm", "install", "-g", "a"]))
        self.assertFalse(grant.permits(["npm", "install", "-g", "outro"]))
        self.assertFalse(grant.permits(["pkg", "install", "-y", "vim"]))


class PlanoSemInstalar(ConsentBase):
    def test_preparar_o_plano_so_consulta_metadados_e_nao_instala_nada(self):
        plan = self.plan("reform_tools")
        self.assertEqual(plan["state"], "awaiting_consent")
        self.assertNothingInstalled()
        self.assertTrue(FakePopen.log, "a consulta de tamanho existe (apt-get -s / curl -I)")
        self.assertTrue(all(a[:3] == ["apt-get", "-s", "install"] or a[:2] == ["curl", "-s"] or a[-1] == "--version"
                            for a in FakePopen.log), FakePopen.log)

    def test_o_plano_traz_o_que_a_pessoa_precisa_ver(self):
        plan = self.plan("claude")
        self.assertEqual(plan["target"], "claude")
        self.assertEqual(plan["size_kind"], "upper_bound")  # npm: teto declarado, nunca medida disfarçada
        self.assertEqual(plan["download_bytes"], 300 * 1024 * 1024)
        self.assertEqual(plan["items"][0]["name"], "Claude Code")
        self.assertEqual(plan["items"][0]["source"], "registry.npmjs.org")
        self.assertRegex(plan["manifest_hash"], r"^sha256:[0-9a-f]{64}$")
        self.assertRegex(plan["expires_at"], r"^\d{4}-\d\d-\d\dT\d\d:\d\d:\d\dZ$")
        self.assertIn(plan["network"], ("wifi", "mobile", "unknown"))
        self.assertNotIn("_steps", json.dumps(plan))  # nenhum comando bruto sai do motor
        self.assertNotIn("npm", json.dumps(plan).replace("registry.npmjs.org", ""))
        self.assertNothingInstalled()

    def test_pacote_do_termux_mede_o_download_exato_e_lista_as_dependencias(self):
        plan = self.plan("reform_tools")
        node = next(i for i in plan["items"] if i["name"] == "Node.js e Git")
        self.assertEqual(node["download_bytes"], 10 * 1024 * 1024)
        self.assertEqual(node["installed_bytes"], 30 * 1024 * 1024)
        self.assertEqual(node["includes"], ["pacote-a", "pacote-b"])
        jadx = next(i for i in plan["items"] if i["name"].startswith("JADX"))
        self.assertEqual(jadx["download_bytes"], 5 * 1024 * 1024)
        self.assertEqual(jadx["checksum"], "sha256:" + rea_chain.JAR_SHA256)

    def test_programa_que_ja_funciona_nao_gera_plano_nem_download(self):
        FakePopen.present.add("claude")
        plan = self.plan("claude")
        self.assertEqual((plan["state"], plan["items"]), ("not_needed", []))
        self.assertNothingInstalled()

    def test_repetir_o_pedido_devolve_o_mesmo_plano_e_outro_corpo_e_conflito(self):
        first = self.plan("claude")
        again = self.plan("claude")
        self.assertEqual(first["plan_id"], again["plan_id"])
        code, body = self.call("POST", "/v1/install-plans", {
            "request_id": "pedido-plano-1", "target": "cli", "resource_id": "gh", "reason": "outro"})
        self.assertEqual((code, body["error"]["code"]), (409, "request_conflict"))

    def test_alvo_sem_suporte_ou_invalido_e_recusado_sem_instalar(self):
        code, body = self.call("POST", "/v1/install-plans", {
            "request_id": "pedido-plano-9", "target": "engine", "reason": "x"})
        self.assertEqual((code, body["error"]["code"]), (400, "unsupported_target"))
        self.assertEqual(self.call("POST", "/v1/install-plans", {
            "request_id": "pedido-plano-8", "target": "vim", "reason": "x"})[0], 400)
        self.assertNothingInstalled()


class SemAceiteNadaInstala(ConsentBase):
    def test_agora_nao_recusa_e_nada_roda_nem_depois(self):
        plan = self.plan("claude")
        code, body = self.decide(plan, "not_now")
        self.assertEqual((code, body["state"]), (200, "declined"))
        self.assertEqual(self.run_jobs(), [])
        self.assertNothingInstalled()
        code, body = self.decide(plan, "install", n=2)
        self.assertEqual(code, 409)  # recusado é recusado: para instalar, um plano novo
        self.assertNothingInstalled()

    def test_plano_vencido_nao_instala(self):
        plan = self.plan("claude")
        self.now[0] += 601
        code, body = self.decide(plan)
        self.assertEqual((code, body["error"]["code"]), (409, "plan_expired"))
        self.assertEqual(self.run_jobs(), [])
        self.assertNothingInstalled()
        self.assertEqual(self.call("GET", "/v1/install-plans/" + plan["plan_id"])[1]["state"], "expired")

    def test_hash_diferente_do_que_a_pessoa_viu_nao_instala(self):
        plan = self.plan("claude")
        code, body = self.decide(plan, manifest_hash="sha256:" + "0" * 64)
        self.assertEqual((code, body["error"]["code"]), (409, "plan_changed"))
        self.assertEqual(self.run_jobs(), [])
        self.assertNothingInstalled()

    def test_manifesto_que_mudou_depois_do_plano_nao_instala(self):
        plan = self.plan("claude")
        with unittest.mock.patch.dict(cli_setup.NPM, {"claude": "@outro/pacote"}):
            code, body = self.decide(plan)
        self.assertEqual((code, body["error"]["code"]), (409, "plan_changed"))
        self.assertEqual(self.run_jobs(), [])
        self.assertNothingInstalled()

    def test_tamanho_desconhecido_bloqueia_ate_calcular(self):
        FakePopen.apt_known = False
        plan = self.plan("reform_tools")
        self.assertEqual((plan["state"], plan["size_kind"], plan["download_bytes"]), ("size_unknown", "unknown", None))
        code, body = self.decide(plan)
        self.assertEqual((code, body["error"]["code"]), (409, "size_unknown"))
        self.assertEqual(self.run_jobs(), [])
        self.assertNothingInstalled()
        FakePopen.apt_known = True  # a consulta passa a responder
        code, measured = self.call("POST", "/v1/install-plans/%s/measure" % plan["plan_id"], {})
        self.assertEqual((code, measured["state"]), (200, "awaiting_consent"))
        self.assertNotEqual(measured["manifest_hash"], plan["manifest_hash"])
        code, body = self.decide(plan, n=2)  # a decisão com o hash velho cai: o plano é outro
        self.assertEqual((code, body["error"]["code"]), (409, "plan_changed"))
        self.assertNothingInstalled()
        self.assertEqual(self.decide(measured, n=3)[0], 202)

    def test_rotas_antigas_e_trabalhos_diretos_sem_plano_falham_antes_de_baixar(self):
        code, body = self.call("POST", "/v1/tools/install", {"provider": "claude"})
        self.assertEqual((code, body["error"]["code"]), (403, "consent_required"))
        code, body = self.call("POST", "/v1/tools/install", {"provider": "claude", "plan_id": "plan-000000000000"})
        self.assertEqual(code, 403)
        for request in ({"kind": "tools", "action": "install", "provider": "claude"},
                        {"kind": "tools", "action": "install", "provider": "gh"},
                        {"kind": "rea", "action": "setup"}):
            self.engine.submit(request)
        for job in self.run_jobs():
            self.assertEqual((job["status"], job["error"]["code"]), ("failed", "consent_required"), job)
        self.assertNothingInstalled()

    def test_passo_tools_da_reforma_sem_aceite_pede_o_plano(self):
        script = self.root / "apk.apk"
        script.write_bytes(b"PK\x03\x04")
        code, made = self.call("POST", "/v1/reform", {"apk_path": str(script), "mode": "fiel", "goal": 80})
        self.assertEqual(code, 201, made)
        code, body = self.call("POST", "/v1/reform/%s/steps/tools" % made["id"], {})
        self.assertEqual((code, body["error"]["code"]), (403, "consent_required"))
        self.assertEqual(body["error"]["action"], "review_installation")
        self.assertEqual(body["error"]["target"], "reform_tools")
        self.assertNothingInstalled()

    def test_comandos_indiretos_pelo_shell_do_motor_sao_barrados(self):
        script = self.root / "instala.sh"
        script.write_text("#!/bin/sh\nnpm install -g algo\n")
        for argv in (["pkg", "install", "-y", "vim"], ["sh", "-c", "pkg install -y vim"],
                     ["bash", str(script)], ["curl", "-fsSL", "-o", "x", "https://exemplo.com/x"],
                     ["pip", "install", "x"]):
            self.engine.submit({"kind": "shell", "argv": argv})
        jobs = self.run_jobs()
        self.assertEqual(len(jobs), 5)
        for job in jobs:
            self.assertEqual((job["status"], job["error"]["code"]), ("failed", "consent_required"), job)
        self.assertNothingInstalled()

    def test_autonomia_total_nao_dispensa_o_consentimento(self):
        self.engine.config.set_setting("autonomy", "total")
        self.engine.submit({"kind": "tools", "action": "install", "provider": "claude"})
        self.assertEqual(self.run_jobs()[0]["error"]["code"], "consent_required")
        self.assertNothingInstalled()

    def test_claude_recebe_a_lista_de_bloqueio_de_instalacao(self):
        FakePopen.present.add("claude")
        self.engine.config.set_setting("autonomy", "total")
        self.engine.submit({"kind": "claude_cli", "prompt": "instale o vim"})
        FakePopen.log.clear()
        FakePopen.on_run = None
        with unittest.mock.patch.object(FakePopen, "communicate", lambda self, input=None, timeout=None: (
                b'{"result":"ok","is_error":false}', b"")):
            self.run_jobs()
        argv = next(a for a in FakePopen.log if a[0] == "claude")
        self.assertIn("--disallowedTools", argv)
        rules = argv[argv.index("--disallowedTools") + 1]
        self.assertIn("Bash(pkg install:*)", rules)
        self.assertIn("Bash(npm install:*)", rules)


class AceiteInstalaUmaVez(ConsentBase):
    def test_aceite_instala_uma_vez_e_o_toque_duplo_nao_repete(self):
        self.npm_installs_claude()
        plan = self.plan("claude")
        self.assertNothingInstalled()  # antes do aceite: zero
        code, body = self.decide(plan)
        self.assertEqual((code, body), (202, {"plan_id": plan["plan_id"], "state": "installing"}))
        self.assertNothingInstalled()  # aceitar só agenda; quem executa é o trabalho
        self.assertEqual(self.decide(plan, n=2), (202, {"plan_id": plan["plan_id"], "state": "installing"}))
        self.assertEqual(self.decide(plan, n=1), (202, {"plan_id": plan["plan_id"], "state": "installing"}))
        jobs = self.run_jobs()
        self.assertEqual([j["status"] for j in jobs], ["completed"], jobs)
        self.assertEqual(self.installs(), [["npm", "install", "-g", "@anthropic-ai/claude-code"]])
        self.assertEqual(self.decide(plan, n=3)[0], 200)  # depois de pronto: nada a repetir
        self.assertEqual(self.run_jobs(), [])
        self.assertEqual(len(self.installs()), 1)
        done = self.call("GET", "/v1/install-plans/" + plan["plan_id"])[1]
        self.assertEqual(done["state"], "completed")
        self.assertIn("progress", done)
        kinds = [(e["payload"]["state"]) for e in self.engine.store.events(0) if e["type"] == "install.status"]
        self.assertEqual(kinds[0], "installing")
        self.assertEqual(kinds[-1], "completed")

    def test_o_aceite_de_um_plano_nao_vale_para_outro_comando(self):
        self.npm_installs_claude()
        plan = self.plan("claude")
        self.decide(plan)
        # trabalho "de carona" no mesmo plano: tenta instalar outra coisa
        self.engine.submit({"kind": "shell", "argv": ["pkg", "install", "-y", "vim"]})
        by_kind = {j["request"]["kind"]: j for j in self.run_jobs()}
        self.assertEqual(by_kind["shell"]["error"]["code"], "consent_required")
        self.assertEqual(by_kind["tools"]["status"], "completed")
        self.assertNotIn(["pkg", "install", "-y", "vim"], FakePopen.log)

    def test_falha_no_caminho_padrao_pede_plano_novo_e_nunca_cai_no_alternativo(self):
        def hook(argv):
            if argv[0] == "npm" and "install" in argv:
                raise adapters.ActionError("process_failed", "npm exited 1")
        FakePopen.on_run = hook
        plan = self.plan("claude")
        self.decide(plan)
        job = self.run_jobs()[0]
        self.assertEqual((job["status"], job["error"]["code"]), ("failed", "needs_new_plan"), job)
        self.assertFalse(any(a[0] in ("curl", "sh") for a in FakePopen.log if install_consent.install_reason(a)),
                         FakePopen.log)
        failed = self.call("GET", "/v1/install-plans/" + plan["plan_id"])[1]
        self.assertEqual((failed["state"], failed["error"]["code"]), ("failed", "needs_new_plan"))
        # o caminho alternativo é OUTRO plano, com outro manifesto e outra decisão
        FakePopen.on_run = None
        alt = self.plan("claude", n=2, variant="glibc-runner")
        self.assertEqual((alt["state"], alt["size_kind"]), ("awaiting_consent", "upper_bound"))
        self.assertNotEqual(alt["plan_id"], plan["plan_id"])
        self.assertNotEqual(alt["manifest_hash"], plan["manifest_hash"])
        before = len(self.installs())
        self.assertEqual(self.run_jobs(), [])
        self.assertEqual(len(self.installs()), before)  # plano novo ainda sem aceite: nada roda

        def hook2(argv):
            if argv[0] == "sh":
                FakePopen.present.add("claude")
        FakePopen.on_run = hook2
        self.assertEqual(self.decide(alt, n=2)[0], 202)
        self.assertEqual(self.run_jobs()[0]["status"], "completed")
        self.assertTrue(any(a[0] == "curl" for a in self.installs()))

    def test_reinicio_do_motor_nunca_retoma_instalacao_sozinho(self):
        self.npm_installs_claude()
        plan = self.plan("claude")
        self.decide(plan)  # aceito, mas o motor cai antes de executar
        self.restart_engine()
        state = self.call("GET", "/v1/install-plans/" + plan["plan_id"])[1]
        self.assertEqual((state["state"], state["error"]["code"]), ("failed", "interrupted"))
        for job in self.run_jobs():  # o trabalho que ficou na fila não herda o aceite
            self.assertEqual(job["status"], "failed")
        self.assertNothingInstalled()

    def test_plano_nao_decidido_sobrevive_ao_reinicio_ate_vencer(self):
        plan = self.plan("claude")
        self.restart_engine()
        again = self.call("GET", "/v1/install-plans/" + plan["plan_id"])[1]
        self.assertEqual((again["state"], again["manifest_hash"]), ("awaiting_consent", plan["manifest_hash"]))
        self.assertNothingInstalled()

    def test_dados_moveis_esperam_o_wifi_e_o_aceite_continua_valendo(self):
        self.npm_installs_claude()
        self.engine.config.set_setting("network", {"metered": True, "transport": "cellular"})
        plan = self.plan("claude")
        self.assertEqual(plan["network"], "mobile")
        self.decide(plan)
        job = self.run_jobs()[0]
        self.assertEqual(job["result"]["state"], "waiting_wifi")
        self.assertNothingInstalled()
        self.assertEqual(self.call("GET", "/v1/install-plans/" + plan["plan_id"])[1]["state"], "waiting_wifi")
        self.engine.config.set_setting("network", {"metered": False, "transport": "wifi"})
        self.assertEqual(self.decide(plan, n=2)[0], 202)
        self.assertEqual(self.run_jobs()[0]["status"], "completed")
        self.assertEqual(len(self.installs()), 1)

    def test_cancelar_antes_de_aceitar_encerra_o_plano(self):
        plan = self.plan("claude")
        code, body = self.call("POST", "/v1/install-plans/%s/cancel" % plan["plan_id"], {"request_id": "cancelar-1"})
        self.assertEqual((code, body["state"]), (200, "cancelled"))
        self.assertEqual(self.decide(plan)[0], 409)
        self.assertNothingInstalled()

    def test_ferramentas_de_reforma_so_baixam_depois_do_aceite_e_o_passo_tools_segue(self):
        script = self.root / "apk.apk"
        script.write_bytes(b"PK\x03\x04")
        reform = self.call("POST", "/v1/reform", {"apk_path": str(script), "mode": "fiel", "goal": 80})[1]
        with unittest.mock.patch.object(rea_chain, "list_tools", lambda home, timeout=60: [{"name": "x"}]):
            plan = self.plan("reform_tools")
            self.assertGreater(len(plan["items"]), 3)
            self.assertNothingInstalled()
            self.assertEqual(self.decide(plan)[0], 202)
            self.assertNothingInstalled()
            self.assertEqual(self.run_jobs()[0]["status"], "completed")
            names = [a for a in self.installs() if a[0] in ("pkg", "npm", "curl")]
            self.assertIn(["npm", "install", "-g", "rea-agents@" + rea_chain.REA_VERSION], names)
            count = len(self.installs())
            code, step = self.call("POST", "/v1/reform/%s/steps/tools" % reform["id"], {})
            self.assertEqual(code, 200, step)  # tudo já instalado: o passo segue sem pedir nada
            self.assertEqual(len(self.installs()), count)


class EstadoDoAssistente(ConsentBase):
    def test_status_e_so_leitura_e_diz_o_proximo_passo(self):
        code, body = self.call("GET", "/v1/setup/status")
        self.assertEqual(code, 200)
        self.assertEqual(body["engine"], {"state": "ready", "update_available": False})
        self.assertEqual(body["claude"], {"installation": "missing", "auth": "unknown"})
        self.assertEqual(body["next_action"], "review_installation")
        self.assertRegex(body["checked_at"], r"^\d{4}-\d\d-\d\dT")
        self.assertNothingInstalled()

    def test_claude_instalado_e_sem_login_manda_entrar(self):
        FakePopen.present.add("claude")
        with unittest.mock.patch("aura_engine.auth.subprocess.run") as run:
            run.return_value = unittest.mock.Mock(returncode=1, stdout=b"not logged in")
            with unittest.mock.patch("aura_engine.auth.pathutil.which", lambda name: "/bin/" + name):
                body = self.call("GET", "/v1/setup/status")[1]
        self.assertEqual(body["claude"], {"installation": "installed", "auth": "signed_out"})
        self.assertEqual(body["next_action"], "sign_in")
        self.assertNothingInstalled()

    def test_login_conectado_so_aparece_pronto_quando_o_motor_confirma(self):
        FakePopen.present.add("claude")
        with unittest.mock.patch("aura_engine.auth.subprocess.run") as run:
            run.return_value = unittest.mock.Mock(returncode=0, stdout=b"ok")
            with unittest.mock.patch("aura_engine.auth.pathutil.which", lambda name: "/bin/" + name):
                body = self.call("GET", "/v1/setup/status")[1]
        self.assertEqual((body["claude"]["auth"], body["next_action"]), ("signed_in", "none"))


if __name__ == "__main__":
    unittest.main()
