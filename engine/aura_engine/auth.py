"""Login facil: dispara o login oficial de cada CLI e conta so o que o app precisa saber.

O motor nao implementa OAuth. Ele roda o comando oficial (modo codigo/link), le da saida
apenas o link https e o codigo curto, e emite eventos. Texto bruto do terminal, tokens,
cookies e caminhos de credenciais nunca saem daqui (nem para evento, nem para resposta).
"""
import concurrent.futures
import json
import os
import pathlib
import re
import shutil
import signal
import sqlite3
import subprocess
import threading
import time
from urllib.parse import urlsplit

from .config import atomic_write_json

PROVIDERS = {
    "claude": {"name": "Claude", "exe": "claude",
               "status": ["claude", "auth", "status"],
               "login": ["claude", "auth", "login"]},
    "gemini": {"name": "Gemini", "exe": "gemini", "status": None,
               "login": ["gemini"], "env": {"NO_BROWSER": "true"}},
    "agy": {"name": "Antigravity", "exe": "agy", "status": None,
            "login": ["agy", "login"]},
    "codex": {"name": "Codex", "exe": "codex",
              "status": ["codex", "login", "status"],
              "login": ["codex", "login", "--device-auth"]},
    "github": {"name": "GitHub", "exe": "gh",
               "status": ["gh", "auth", "status"],
               "login": ["gh", "auth", "login", "--web", "--hostname", "github.com",
                         "--git-protocol", "https"],
               "enter": True},
    "google": {"name": "Google Drive", "exe": "rclone",
               "status": None,  # rclone listremotes, tratado a parte
               "login": ["rclone", "config", "create", "gdrive", "drive"]},
}
ALIASES = {"gdrive": "google"}

FIX_RETRY = "Toque em Conectar para tentar de novo."
FIXES = {
    "sem_internet": "Conecte à internet e toque em Conectar de novo.",
    "codigo_recusado": "O código venceu. Toque em Conectar para gerar outro.",
    "tempo_esgotado": "O código venceu. Toque em Conectar para gerar outro.",
    "cancelado": "Login cancelado. Toque em Conectar quando quiser.",
    "nao_confirmado": "Não deu para confirmar o login. " + FIX_RETRY,
    "falhou": "Não deu certo. " + FIX_RETRY,
}

_ANSI = re.compile(r"\x1b\[[0-9;?]*[ -/]*[@-~]")
_URL = re.compile(r"https?://[^\s\"'<>)\]]+")
_CODE = re.compile(r"\b[A-Z0-9]{4}-[A-Z0-9]{4,5}\b")
_NET = ("network", "unreachable", "could not resolve", "no such host", "timed out",
        "connection refused", "connection reset", "offline", "enotfound", "no route")
_DENIED = ("expired", "expirou", "invalid", "denied", "incorrect", "not valid")
_CANCEL = ("cancel", "abort", "access_denied")
_EXPIRED = ("expired", "expirou", "refresh token", "reauth", "log in again", "login again")


def _is_loopback(host):
    return host in ("127.0.0.1", "localhost", "::1", "[::1]")


def _clean_url(raw):
    url = raw.rstrip(".,;:")
    parts = urlsplit(url)
    if parts.scheme == "https" and parts.hostname:
        return url
    if parts.scheme == "http" and parts.hostname and _is_loopback(parts.hostname):
        return url  # rclone abre um servidor local para o callback
    return None


class AuthManager:
    def __init__(self, home, store, timeout=300):
        self.home = pathlib.Path(home)
        self.store = store
        self.timeout = float(timeout)
        self.lock = threading.RLock()
        self.active = {}
        self.closed = False

    # ---- estado ---------------------------------------------------------------
    def _marker_path(self):
        return self.home / "auth_state.json"

    def _markers(self):
        try:
            data = json.loads(self._marker_path().read_text("utf-8"))
            return data if isinstance(data, dict) else {}
        except (OSError, ValueError):
            return {}

    def _mark(self, provider, connected):
        with self.lock:
            data = self._markers()
            if connected:
                data[provider] = {"connected_at": int(time.time())}
            else:
                data.pop(provider, None)
            self.home.mkdir(parents=True, exist_ok=True, mode=0o700)
            atomic_write_json(self._marker_path(), data)

    @staticmethod
    def _run_check(argv):
        """Roda um comando de status e devolve so (codigo, expirado). Saida descartada."""
        try:
            done = subprocess.run(argv, stdin=subprocess.DEVNULL, stdout=subprocess.PIPE,
                                  stderr=subprocess.STDOUT, timeout=8)
        except (OSError, subprocess.TimeoutExpired):
            return None, False
        text = done.stdout.decode("utf-8", "replace").lower()
        return done.returncode, any(word in text for word in _EXPIRED)

    def _row(self, provider):
        spec = PROVIDERS[provider]
        if shutil.which(spec["exe"]) is None:
            return {"provider": provider, "state": "indisponivel", "installable": True,
                    "detail": "Falta o programa de %s." % spec["name"]}
        if provider == "google":
            try:
                done = subprocess.run(["rclone", "listremotes"], stdin=subprocess.DEVNULL,
                                      stdout=subprocess.PIPE, stderr=subprocess.DEVNULL, timeout=8)
                names = done.stdout.decode("utf-8", "replace").split()
                code = done.returncode
            except (OSError, subprocess.TimeoutExpired):
                code, names = None, []
            state = "conectado" if code == 0 and "gdrive:" in names else "desconectado"
            return {"provider": provider, "state": state}
        if spec["status"] is None:
            # Sem comando de status confiavel: vale so o login feito por este motor.
            if provider in self._markers():
                return {"provider": provider, "state": "conectado"}
            return {"provider": provider, "state": "desconectado",
                    "detail": "Estado conhecido só pelo login feito aqui."}
        code, expired = self._run_check(spec["status"])
        if code == 0:
            return {"provider": provider, "state": "conectado"}
        if code is None:
            return {"provider": provider, "state": "desconectado", "detail": "Sem resposta do programa."}
        return {"provider": provider, "state": "expirado" if expired else "desconectado"}

    def status(self):
        names = list(PROVIDERS)
        with concurrent.futures.ThreadPoolExecutor(max_workers=len(names)) as pool:
            return {"providers": list(pool.map(self._row, names))}

    # ---- login ----------------------------------------------------------------
    @staticmethod
    def normalize(provider):
        if not isinstance(provider, str):
            raise ValueError("provider invalido")
        provider = ALIASES.get(provider, provider)
        if provider not in PROVIDERS:
            raise ValueError("provider invalido")
        return provider

    def _fail(self, provider, reason):
        self.store.emit("auth.failed", {"provider": provider, "reason": reason, "fix": FIXES[reason]})

    def start(self, provider):
        provider = self.normalize(provider)
        spec = PROVIDERS[provider]
        with self.lock:
            run = self.active.get(provider)
            if run is not None:
                return {"provider": provider, "state": "em_andamento", "prompt": run["prompt"]}
            if self.closed:
                raise ValueError("motor encerrando")
            if shutil.which(spec["exe"]) is None:
                self.store.emit("auth.failed", {
                    "provider": provider, "reason": "cli_ausente",
                    "fix": "Falta o programa de %s. Toque em Instalar." % spec["name"]})
                return {"provider": provider, "state": "indisponivel", "fix": "Instalar"}
            argv = list(spec["login"])
            if provider == "google" and "gdrive:" in self._remotes():
                argv = ["rclone", "config", "reconnect", "gdrive:"]
            env = dict(os.environ)
            env["BROWSER"] = "true"
            env.update(spec.get("env", {}))
            try:
                proc = subprocess.Popen(
                    argv, stdin=subprocess.PIPE if spec.get("enter") else subprocess.DEVNULL,
                    stdout=subprocess.PIPE, stderr=subprocess.STDOUT, env=env,
                    start_new_session=True)
            except OSError:
                self._fail(provider, "falhou")
                return {"provider": provider, "state": "indisponivel", "fix": "Instalar"}
            run = {"proc": proc, "prompt": None, "url": None, "code": None,
                   "emitted": False, "timer": None, "flags": set(), "timed_out": False,
                   "lock": threading.Lock()}
            self.active[provider] = run
        run["reader"] = threading.Thread(target=self._read, args=(provider, run, spec), daemon=True,
                                         name="aura-auth-read-" + provider)
        run["reader"].start()
        threading.Thread(target=self._watch, args=(provider, run, spec), daemon=True,
                         name="aura-auth-watch-" + provider).start()
        return {"provider": provider, "state": "em_andamento", "prompt": None}

    @staticmethod
    def _remotes():
        try:
            done = subprocess.run(["rclone", "listremotes"], stdin=subprocess.DEVNULL,
                                  stdout=subprocess.PIPE, stderr=subprocess.DEVNULL, timeout=8)
            return done.stdout.decode("utf-8", "replace").split()
        except (OSError, subprocess.TimeoutExpired):
            return []

    def _emit_prompt(self, provider, run, spec):
        with run["lock"]:
            if run["emitted"] or not run["url"]:
                return
            run["emitted"] = True
            prompt = {"provider": provider, "url": run["url"]}
            if run["code"]:
                prompt["code"] = run["code"]
            run["prompt"] = prompt
            if run["timer"] is not None:
                run["timer"].cancel()
        self.store.emit("auth.prompt", prompt)
        if spec.get("enter"):
            try:  # gh espera Enter para "abrir o navegador"; BROWSER=true nao faz nada
                run["proc"].stdin.write(b"\n")
                run["proc"].stdin.flush()
            except (OSError, ValueError):
                pass

    def _read(self, provider, run, spec):
        proc = run["proc"]
        try:
            for raw in iter(proc.stdout.readline, b""):
                line = _ANSI.sub("", raw.decode("utf-8", "replace"))
                lower = line.lower()
                for flag, words in (("cancelled", _CANCEL), ("net", _NET), ("denied", _DENIED)):
                    if any(w in lower for w in words):
                        run["flags"].add(flag)
                if not run["emitted"]:
                    if run["url"] is None:
                        for found in _URL.findall(line):
                            url = _clean_url(found)
                            if url:
                                run["url"] = url
                                run["timer"] = threading.Timer(
                                    1.5, self._emit_prompt, (provider, run, spec))
                                run["timer"].daemon = True
                                run["timer"].start()
                                break
                    if run["code"] is None:
                        match = _CODE.search(_URL.sub("", line))
                        if match:
                            run["code"] = match.group(0)
                    if run["url"] and run["code"]:
                        self._emit_prompt(provider, run, spec)
        except (OSError, ValueError):
            pass

    def _kill(self, proc):
        try:
            os.killpg(proc.pid, signal.SIGKILL)
        except (OSError, ProcessLookupError):
            try:
                proc.kill()
            except OSError:
                pass

    def _watch(self, provider, run, spec):
        proc = run["proc"]
        try:
            proc.wait(timeout=self.timeout)
        except subprocess.TimeoutExpired:
            run["timed_out"] = True
            self._kill(proc)
            proc.wait()
        run["reader"].join(timeout=3)  # deixa o leitor drenar o que restou
        try:
            if self.closed:
                return
            self._emit_prompt(provider, run, spec)
            self.store.scrub_auth_prompts(provider)  # antes do evento final: quem ve o fim ja nao ve o codigo
            if run["timed_out"]:
                self._fail(provider, "tempo_esgotado")
            elif proc.returncode == 0:
                self._finish_ok(provider, spec)
            else:
                flags = run["flags"]
                reason = ("cancelado" if "cancelled" in flags else
                          "sem_internet" if "net" in flags else
                          "codigo_recusado" if "denied" in flags else "falhou")
                self._fail(provider, reason)
        except sqlite3.Error:
            pass  # banco fechado durante o encerramento do motor
        finally:
            if run["timer"] is not None:
                run["timer"].cancel()
            try:
                proc.stdout.close()
            except OSError:
                pass
            with self.lock:
                if self.active.get(provider) is run:
                    del self.active[provider]

    def _finish_ok(self, provider, spec):
        if spec["status"] is not None or provider == "google":
            row = self._row(provider)
            if row["state"] != "conectado":
                return self._fail(provider, "nao_confirmado")
        self._mark(provider, True)
        self.store.emit("auth.done", {"provider": provider})

    def close(self):
        with self.lock:
            self.closed = True
            runs = list(self.active.values())
        for run in runs:
            run["timed_out"] = False
            self._kill(run["proc"])
