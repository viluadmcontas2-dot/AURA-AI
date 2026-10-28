"""C4: instalação só com decisão do dono, vinculada a um plano imutável (docs/FLUXOS_DE_USO.md, seção 7).

A barreira tem três camadas, de fora para dentro:
1. Plano: `POST /v1/install-plans` só PREPARA o manifesto (nomes, origem, bytes). Consultas de tamanho são
   metadados (apt-get -s, curl -I) e não instalam nem baixam pacote. Nada é executado.
2. Decisão: `POST /v1/install-plans/{id}/decision`. Só `install` com o hash do manifesto que a pessoa viu, plano
   não vencido e tamanho conhecido inicia a instalação, uma única vez por plano.
3. Execução: o trabalho roda com uma "permissão" (Grant) presa à thread, que só vale para os comandos do plano.
   `adapters.run_process` recusa qualquer comando de instalação sem essa permissão (barreira única).

Limite honesto: comandos que o próprio Claude decide rodar dentro de uma conversa não passam por aqui. O que
existe é uma lista de bloqueio por prefixo (CLAUDE_DENY_RULES), que reduz o risco mas não o elimina.
"""
import hashlib
import json
import os
import pathlib
import re
import threading
import time
import uuid
from datetime import datetime, timezone

from . import adapters
from .config import atomic_write_json

PLAN_TTL = 600  # segundos: o plano vence e a pessoa precisa decidir de novo
TARGETS = ("engine", "claude", "reform_tools", "artifact", "cli")
FINAL = ("completed", "failed", "cancelled", "declined", "expired", "not_needed")
MB = 1024 * 1024

# Limites superiores DECLARADOS para pacotes npm (o registro não informa o tamanho real do download com
# as dependências). Honestos: são tetos, não medidas. Em MB.
NPM_UPPER_MB = {
    "@anthropic-ai/claude-code": 300,
    "@openai/codex": 200,
    "@google/gemini-cli": 200,
    "rea-agents": 60,
}
CLAUDE_GLIBC_UPPER_MB = 500  # glibc-runner + binário oficial linux-arm64 (teto declarado)

LABELS = {
    "node": "Node.js e Git", "npm": None, "jdk": "Java (OpenJDK 21)", "jadx": "JADX (leitor de APK)",
    "apktool": "Apktool", "adb": "Ferramentas ADB", "rea": "REA (reforma de apps)", "mcp": "Teste do REA",
    "glibc-runner": "Claude Code (caminho alternativo)", "agy": "Antigravity",
    "claude": "Claude Code", "codex": "Codex", "gemini": "Gemini CLI", "gh": "GitHub CLI", "rclone": "rclone",
}
PROVIDER_LABEL = {"claude": "Claude Code", "codex": "Codex", "gemini": "Gemini CLI", "agy": "Antigravity",
                  "gh": "GitHub CLI", "rclone": "rclone"}

# Regras de bloqueio por prefixo para o Claude Code (--disallowedTools). Reduzem o risco; NÃO são garantia.
CLAUDE_DENY_RULES = (
    "Bash(pkg install:*)", "Bash(pkg upgrade:*)", "Bash(apt install:*)", "Bash(apt-get install:*)",
    "Bash(npm install:*)", "Bash(npm i:*)", "Bash(pip install:*)", "Bash(pip3 install:*)",
    "Bash(pipx install:*)", "Bash(yarn add:*)", "Bash(pnpm add:*)", "Bash(cargo install:*)",
    "Bash(gem install:*)", "Bash(npx:*)", "Bash(curl:*)", "Bash(wget:*)",
)


def claude_guard_args():
    """Argumentos extras para o `claude -p`: bloqueio por prefixo dos comandos de instalação. Melhor esforço."""
    return ["--disallowedTools", ",".join(CLAUDE_DENY_RULES)]


# ---------------------------------------------------------------------------------------------------------------
# Detecção: este comando instala ou baixa alguma coisa?
# ---------------------------------------------------------------------------------------------------------------
_PKG_VERBS = {"install", "reinstall", "upgrade", "full-upgrade", "dist-upgrade", "add"}
_JS_VERBS = {"install", "i", "add", "update", "up", "upgrade", "ci", "exec", "dlx", "create"}
_SIMULATE = {"-s", "--simulate", "--dry-run", "--just-print", "--no-act", "--recon"}
_HEAD = {"-I", "--head", "--spider"}
_LOCAL_HOSTS = ("localhost", "127.0.0.1", "[::1]", "::1")
_SHELLS = {"sh", "bash", "dash", "zsh", "ash"}
_TEXT = re.compile(
    r"(?:^|[;&|(`\s])(?:pkg|apt|apt-get|aptitude|pip3?|pipx|npm|npx|pnpm|yarn|bun|cargo|gem|brew|go)\s+"
    r"(?:-\S+\s+)*(?:install|reinstall|i|add|upgrade|full-upgrade|dist-upgrade|update|ci|exec|dlx|get)\b"
    r"|(?:^|[;&|(`\s])(?:npx|bunx)\s+\S"
    r"|(?:^|[;&|(`\s])(?:curl|wget)\s[^|;&]*\bhttps?://(?!(?:localhost|127\.0\.0\.1|\[::1\]))"
    r"|\|\s*(?:ba|da|z|a)?sh\b")


def _is_local_url(args):
    urls = [a for a in args if re.match(r"https?://", a)]
    return bool(urls) and all(re.match(r"https?://(localhost|127\.0\.0\.1|\[::1\])([:/]|$)", u) for u in urls)


def _text_installs(text):
    """Texto de shell que, lido, instalaria ou baixaria algo. Comparação rasa e honesta: não executa nada."""
    return bool(_TEXT.search(text))


def install_reason(argv):
    """None se o comando não instala nem baixa nada; senão uma frase curta dizendo por quê."""
    if not isinstance(argv, (list, tuple)) or not argv or not isinstance(argv[0], str):
        return None
    name = os.path.basename(argv[0])
    args = [a for a in argv[1:] if isinstance(a, str)]
    if name in ("pkg", "apt", "apt-get", "aptitude"):
        if set(args) & _SIMULATE:
            return None  # simulação: só consulta metadados
        verbs = [a for a in args if not a.startswith("-")]
        return "instala pacote do Termux" if verbs and verbs[0] in _PKG_VERBS else None
    if name == "dpkg":
        return "instala pacote" if {"-i", "--install"} & set(args) else None
    if name in ("npm", "pnpm", "yarn", "bun"):
        verbs = [a for a in args if not a.startswith("-")]
        return "instala pacote npm" if verbs and verbs[0] in _JS_VERBS else None
    if name in ("npx", "bunx"):
        return "baixa e roda pacote npm"
    if name in ("pip", "pip3", "pipx", "uv"):
        verbs = [a for a in args if not a.startswith("-")]
        return "instala pacote Python" if verbs and verbs[0] in ("install", "add", "tool", "sync") else None
    if name in ("python", "python3") and "-m" in args:
        rest = args[args.index("-m") + 1:]
        if rest[:1] in (["pip"], ["ensurepip"]):
            return "instala pacote Python" if "install" in rest or rest[0] == "ensurepip" else None
    if name in ("cargo", "gem", "go", "brew") and args[:1] in (["install"], ["get"]):
        return "instala programa"
    if name == "gh" and args[:2] == ["extension", "install"]:
        return "instala extensão do gh"
    if name == "rclone" and args[:1] == ["selfupdate"]:
        return "atualiza o rclone"
    if name in ("curl", "wget"):
        if set(args) & _HEAD or _is_local_url(args):
            return None
        return "baixa arquivo da internet" if any(re.match(r"https?://", a) for a in args) else None
    if name in _SHELLS:
        if "-c" in args:
            i = args.index("-c")
            return "roda comando que instala" if i + 1 < len(args) and _text_installs(args[i + 1]) else None
        target = next((a for a in args if not a.startswith("-")), None)
        if target:
            try:
                path = pathlib.Path(target)
                if path.is_file() and path.stat().st_size <= 1_000_000:
                    if _text_installs(path.read_text("utf-8", errors="replace")):
                        return "roda script que instala"
            except OSError:
                return None
    return None


# ---------------------------------------------------------------------------------------------------------------
# Permissão (Grant): presa à thread que executa o plano aceito.
# ---------------------------------------------------------------------------------------------------------------
class Grant:
    """Autoriza só os comandos do plano aceito. Qualquer outro comando de instalação continua barrado."""

    def __init__(self, plan_id, allowed):
        self.plan_id = plan_id
        self.allowed = [list(c) for c in allowed]

    def permits(self, argv):
        return list(argv) in self.allowed


def current_grant(engine):
    """A permissão ativa nesta thread, ou None. Os testes de outras áreas trocam esta função por uma que libera."""
    return getattr(engine.context, "install_grant", None)


def require_grant(engine):
    if current_grant(engine) is None:
        raise adapters.ActionError(
            "consent_required",
            "Para instalar programas preciso da sua confirmação. Abra Conexões e toque em Instalar.")


def check_command(engine, argv):
    """Chamado por adapters.run_process antes de iniciar qualquer processo."""
    reason = install_reason(argv)
    if reason is None:
        return
    grant = current_grant(engine)
    if grant is None or not grant.permits(argv):
        raise adapters.ActionError(
            "consent_required",
            "Este comando %s e não está num plano que você aceitou. Nada foi instalado." % reason)


# ---------------------------------------------------------------------------------------------------------------
# Medição de tamanho (só metadados)
# ---------------------------------------------------------------------------------------------------------------
_NEED = re.compile(r"Need to get ([\d.,]+)\s*(B|kB|MB|GB)", re.I)
_EXTRA = re.compile(r"After this operation, ([\d.,]+)\s*(B|kB|MB|GB) of additional disk space", re.I)
_NEW = re.compile(r"The following NEW packages will be installed:\n((?:[ \t]+.*\n?)+)")
_UNIT = {"b": 1, "kb": 1024, "mb": MB, "gb": 1024 * MB}


def _bytes(number, unit):
    try:
        return int(float(number.replace(",", "")) * _UNIT[unit.lower()])
    except (ValueError, KeyError):
        return None


def _quiet(argv, engine):
    try:
        return adapters.run_process(argv, engine.home, engine, 120).get("stdout") or ""
    except adapters.ActionError:
        return None


def _url_host(url):
    match = re.match(r"https?://([^/]+)", url or "")
    return match.group(1) if match else "internet"


def measure_step(engine, name, commands):
    """Tamanho de um passo, só com consultas de metadados. Devolve um dict com download_bytes (None = não sei),
    installed_bytes, kind (exact|upper_bound|unknown), source, version, checksum, includes."""
    total, installed, kinds, sources, includes = 0, 0, [], [], []
    version = checksum = None
    for argv in commands:
        exe = os.path.basename(argv[0])
        if exe == "curl" and any(a.startswith("http") for a in argv):
            url = [a for a in argv if a.startswith("http")][-1]
            sources.append(_url_host(url))
            if name == "glibc-runner":
                total += CLAUDE_GLIBC_UPPER_MB * MB
                installed += CLAUDE_GLIBC_UPPER_MB * MB
                kinds.append("upper_bound")
                includes.append("glibc-runner e binário oficial do Claude Code")
                continue
            if name == "agy":
                kinds.append("unknown")  # o instalador baixa o programa e não informa quanto
                includes.append("instalador do Antigravity")
                continue
            head = _quiet(["curl", "-s", "-I", "-L", url], engine)
            sizes = re.findall(r"content-length:\s*(\d+)", head or "", re.I)
            if sizes:
                total += int(sizes[-1])
                installed += int(sizes[-1])
                kinds.append("exact")
            else:
                kinds.append("unknown")
            includes.append(os.path.basename(url))
        elif exe == "pkg" and "install" in argv:
            pkgs = [a for a in argv[argv.index("install") + 1:] if not a.startswith("-")]
            sources.append("Repositório de pacotes do Termux")
            out = _quiet(["apt-get", "-s", "install", "-y"] + pkgs, engine)
            need = _NEED.search(out or "")
            extra = _EXTRA.search(out or "")
            if need:
                total += _bytes(*need.groups()) or 0
                installed += _bytes(*extra.groups()) if extra else 0
                kinds.append("exact")
            elif out is not None and "newly installed" in out:
                kinds.append("exact")  # nada a baixar: já está tudo no Termux
            else:
                kinds.append("unknown")
            found = _NEW.search(out or "")
            includes.extend(found.group(1).split() if found else pkgs)
        elif exe == "npm" and "install" in argv:
            sources.append("registry.npmjs.org")
            names = [a.rsplit("@", 1)[0] if a.rfind("@") > 0 else a for a in argv[argv.index("install") + 1:]
                     if not a.startswith("-")]
            hits = [NPM_UPPER_MB[n] for n in names if n in NPM_UPPER_MB]
            if hits and len(hits) == len(names):
                total += sum(hits) * MB
                installed += sum(hits) * 2 * MB
                kinds.append("upper_bound")
            else:
                kinds.append("unknown")
            includes.extend(names)
            pinned = [a.rsplit("@", 1)[1] for a in argv if a.rfind("@") > 0 and not a.startswith("@")]
            version = pinned[0] if pinned else version
        elif exe == "bash" and name == "agy":
            sources.append("antigravity.google")
    if not kinds:
        return None  # passo sem download (verificação)
    kind = "unknown" if "unknown" in kinds else "upper_bound" if "upper_bound" in kinds else "exact"
    from . import cli_setup, rea_chain
    if name == "jadx":
        version, checksum = "0.7.1", "sha256:" + rea_chain.JAR_SHA256
    elif name == "rea":
        version = rea_chain.REA_VERSION
    elif name == "glibc-runner":
        version, checksum = "commit " + cli_setup.CLAUDE_ANDROID_COMMIT[:7], "sha256:" + cli_setup.CLAUDE_ANDROID_SHA256
    return {"kind": kind, "download_bytes": None if kind == "unknown" else total,
            "installed_bytes": None if kind == "unknown" else installed, "source": ", ".join(dict.fromkeys(sources)),
            "version": version, "checksum": checksum, "includes": list(dict.fromkeys(includes))}


# ---------------------------------------------------------------------------------------------------------------
# Derivação dos passos de cada alvo
# ---------------------------------------------------------------------------------------------------------------
def _provider_of(target, resource_id):
    if target == "claude":
        return "claude"
    if target == "cli":
        from . import cli_setup
        if resource_id in cli_setup.EXE and resource_id != "claude":
            return resource_id
    raise adapters.ActionError("invalid_request", "Informe qual programa instalar (resource_id).")


def derive(engine, target, resource_id=None, variant=None):
    """(passos, rótulo) do alvo ou None se nada falta instalar. Não executa instalação nenhuma."""
    from . import cli_setup, rea_chain
    if target in ("claude", "cli"):
        provider = _provider_of(target, resource_id)
        if cli_setup._works(cli_setup.EXE[provider], engine.home, engine):
            return None
        return cli_setup.plan_steps(provider, engine.home, variant), PROVIDER_LABEL[provider]
    if target == "reform_tools":
        done = rea_chain._done(engine.home)
        pending = [(n, c) for n, c in rea_chain.steps(engine.home) if n not in done]
        if not any(rea_chain.step_downloads(c) for _, c in pending):
            return None
        return pending, "Ferramentas de reforma"
    raise adapters.ActionError(
        "unsupported_target", "Este tipo de instalação ainda não está disponível por aqui.")


def _canon(value):
    return json.dumps(value, ensure_ascii=False, sort_keys=True, separators=(",", ":"))


def _sha(value):
    return "sha256:" + hashlib.sha256(_canon(value).encode("utf-8")).hexdigest()


def _iso(epoch):
    return datetime.fromtimestamp(epoch, timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ")


def _item_name(step, label):
    return label if step == "npm" else (LABELS.get(step) or step)


class PlanManager:
    def __init__(self, engine, clock=time.time, measure=None, ttl=PLAN_TTL):
        self.engine = engine
        self.clock = clock
        self.measure = measure or (lambda step, commands: measure_step(engine, step, commands))
        self.ttl = ttl
        self.lock = threading.RLock()
        self.dir = engine.home / "install-plans"
        self._interrupted()

    # ---- persistência ------------------------------------------------------------------------------------
    def _path(self, plan_id):
        return self.dir / (plan_id + ".json")

    def _load(self, plan_id):
        if not isinstance(plan_id, str) or not re.fullmatch(r"plan-[0-9a-f]{12}", plan_id):
            return None
        try:
            data = json.loads(self._path(plan_id).read_text("utf-8"))
        except (OSError, ValueError):
            return None
        return data if isinstance(data, dict) else None

    def _save(self, plan):
        self.dir.mkdir(parents=True, exist_ok=True, mode=0o700)
        atomic_write_json(self._path(plan["plan_id"]), plan)

    def _interrupted(self):
        """Reinício do motor: o que estava instalando fica 'interrompido'. Nunca retoma sozinho."""
        if not self.dir.is_dir():
            return
        for path in self.dir.glob("plan-*.json"):
            plan = self._load(path.stem)
            if plan and plan.get("state") in ("installing", "cancelling"):
                plan["state"] = "failed"
                plan["error"] = {"code": "interrupted", "message_pt":
                                 "A instalação foi interrompida quando o motor reiniciou. Revise e tente de novo."}
                self._save(plan)

    # ---- visão pública -----------------------------------------------------------------------------------
    @staticmethod
    def public(plan):
        out = {k: v for k, v in plan.items() if not k.startswith("_")}
        out["progress"] = plan.get("_progress", {"item": None, "downloaded_bytes": None,
                                                    "elapsed_ms": 0, "error": plan.get("error")})
        return out

    def _expire(self, plan):
        if plan["state"] in ("awaiting_consent", "size_unknown", "waiting_wifi") and \
                self.clock() >= plan["_expires"]:
            plan["state"] = "expired"
            self._save(plan)
        return plan

    def _network(self):
        net = self.engine.config.settings()["network"]
        if net["metered"]:
            return "mobile"
        return "wifi" if net["transport"] in ("wifi", "wi-fi", "WIFI") else "unknown"

    def _manifest(self, plan):
        """Parte do plano que a pessoa vê e aceita. O hash cobre isto e os comandos."""
        return {"target": plan["target"], "resource_id": plan.get("resource_id"), "variant": plan.get("_variant"),
                "items": plan["items"], "download_bytes": plan["download_bytes"],
                "installed_bytes": plan["installed_bytes"], "size_kind": plan["size_kind"],
                "steps": plan["_steps_hash"]}

    def _build(self, plan, steps, label):
        items, total_d, total_i, kinds = [], 0, 0, []
        for step, commands in steps:
            info = self.measure(step, commands)
            if info is None:
                continue
            kinds.append(info["kind"])
            if info["download_bytes"] is None:
                total_d = None
            elif total_d is not None:
                total_d += info["download_bytes"]
            if info["installed_bytes"] is None:
                total_i = None
            elif total_i is not None:
                total_i += info["installed_bytes"]
            item = {"name": _item_name(step, label), "version": info["version"], "source": info["source"],
                    "download_bytes": info["download_bytes"], "installed_bytes": info["installed_bytes"],
                    "checksum": info["checksum"]}
            if info["includes"]:
                item["includes"] = info["includes"]
            items.append(item)
        kind = "unknown" if "unknown" in kinds else "upper_bound" if "upper_bound" in kinds else "exact"
        plan["_label"] = label
        plan["items"] = items
        plan["download_bytes"] = None if kind == "unknown" else total_d
        plan["installed_bytes"] = None if kind == "unknown" else total_i
        plan["size_kind"] = kind
        plan["state"] = "size_unknown" if kind == "unknown" else "awaiting_consent"
        plan["manifest_hash"] = _sha(self._manifest(plan))
        return plan

    # ---- criar / medir -----------------------------------------------------------------------------------
    def create(self, request_id, target, reason, resource_id=None, variant=None):
        if not isinstance(request_id, str) or not re.fullmatch(r"[A-Za-z0-9_-]{8,64}", request_id):
            raise adapters.ActionError("invalid_request", "Informe request_id (um identificador único).")
        if target not in TARGETS:
            raise adapters.ActionError("invalid_request", "target deve ser um de: " + ", ".join(TARGETS))
        if not isinstance(reason, str) or not 1 <= len(reason.strip()) <= 200:
            raise adapters.ActionError("invalid_request", "Informe reason: para que serve esta instalação.")
        if variant not in (None, "default", "glibc-runner"):
            raise adapters.ActionError("invalid_request", "variant desconhecida.")
        if variant == "default":
            variant = None
        fingerprint = _sha({"target": target, "resource_id": resource_id, "reason": reason, "variant": variant})
        with self.lock:
            existing = self._by_request(request_id)
            if existing is not None:
                if existing["_fingerprint"] != fingerprint:
                    raise adapters.ActionError("request_conflict", "Este pedido já foi usado com outro conteúdo.")
                return False, self._expire(existing)
            derived = derive(self.engine, target, resource_id, variant)
            now = self.clock()
            plan = {"plan_id": "plan-" + uuid.uuid4().hex[:12], "target": target, "reason": reason.strip(),
                    "created_at": _iso(now), "expires_at": _iso(now + self.ttl), "network": self._network(),
                    "_request_id": request_id, "_fingerprint": fingerprint, "_created": now,
                    "_expires": now + self.ttl, "_variant": variant}
            if resource_id:
                plan["resource_id"] = resource_id
            if derived is None:
                plan.update(state="not_needed", items=[], download_bytes=0, installed_bytes=0,
                            size_kind="exact", _steps=[], _steps_hash=_sha([]))
                plan["manifest_hash"] = _sha(self._manifest(plan))
            else:
                steps, label = derived
                plan["_steps"] = [[n, c] for n, c in steps]
                plan["_steps_hash"] = _sha(plan["_steps"])
                self._build(plan, steps, label)
            self._save(plan)
            return True, plan

    def _by_request(self, request_id):
        if not self.dir.is_dir():
            return None
        for path in self.dir.glob("plan-*.json"):
            plan = self._load(path.stem)
            if plan and plan.get("_request_id") == request_id:
                return plan
        return None

    def get(self, plan_id):
        with self.lock:
            plan = self._load(plan_id)
            return self._expire(plan) if plan else None

    def remeasure(self, plan_id):
        """'Calcular tamanho': repete a consulta de metadados. Se algo mudar, o hash muda e a decisão antiga cai."""
        with self.lock:
            plan = self.get(plan_id)
            if plan is None:
                raise adapters.ActionError("plan_not_found", "Não encontrei este plano. Crie um novo.")
            if plan["state"] not in ("awaiting_consent", "size_unknown"):
                return plan
            derived = derive(self.engine, plan["target"], plan.get("resource_id"), plan.get("_variant"))
            if derived is None:
                plan["state"] = "not_needed"
                self._save(plan)
                return plan
            steps, label = derived
            plan["_steps"] = [[n, c] for n, c in steps]
            plan["_steps_hash"] = _sha(plan["_steps"])
            plan["network"] = self._network()
            self._build(plan, steps, label)
            now = self.clock()
            plan["expires_at"], plan["_expires"] = _iso(now + self.ttl), now + self.ttl
            plan["manifest_hash"] = _sha(self._manifest(plan))
            self._save(plan)
            return plan

    # ---- decisão -----------------------------------------------------------------------------------------
    def decide(self, plan_id, request_id, manifest_hash, decision):
        """(código HTTP, corpo). Só 'install' com tudo em ordem executa; o resto nunca toca no sistema."""
        if decision not in ("install", "not_now"):
            raise adapters.ActionError("invalid_request", "decision deve ser install ou not_now.")
        if not isinstance(request_id, str) or not re.fullmatch(r"[A-Za-z0-9_-]{8,64}", request_id):
            raise adapters.ActionError("invalid_request", "Informe request_id (um identificador único).")
        with self.lock:
            plan = self.get(plan_id)
            if plan is None:
                raise adapters.ActionError("plan_not_found", "Não encontrei este plano. Crie um novo.")
            seen = plan.get("_decisions", {})
            if request_id in seen:
                return seen[request_id]["code"], seen[request_id]["body"]  # repetição: o resultado original
            state = plan["state"]
            if decision == "not_now":
                if state in ("awaiting_consent", "size_unknown", "waiting_wifi"):
                    plan["state"] = "declined"
                    return self._remember(plan, request_id, 200, {"plan_id": plan_id, "state": "declined"})
                return 200, {"plan_id": plan_id, "state": state}
            if state in ("installing", "completed"):
                # toque duplo ou nova tentativa com outro request_id: o plano roda uma vez só
                return (202 if state == "installing" else 200), {"plan_id": plan_id, "state": state}
            if state == "expired":
                raise adapters.ActionError("plan_expired", "Este plano venceu. Crie um novo para decidir de novo.")
            if state == "size_unknown":
                raise adapters.ActionError("size_unknown", "Ainda não consegui calcular o tamanho. Nada será instalado.")
            if state not in ("awaiting_consent", "waiting_wifi"):
                raise adapters.ActionError("plan_changed", "Este plano não está mais aguardando decisão.")
            if manifest_hash != plan["manifest_hash"]:
                raise adapters.ActionError("plan_changed", "O plano mudou depois que você o viu. Revise de novo.")
            derived = derive(self.engine, plan["target"], plan.get("resource_id"), plan.get("_variant"))
            if derived is None or _sha([[n, c] for n, c in derived[0]]) != plan["_steps_hash"]:
                raise adapters.ActionError("plan_changed", "O que falta instalar mudou. Revise o plano de novo.")
            plan["_consent"] = {"request_id": request_id, "at": _iso(self.clock()),
                                "manifest_hash": plan["manifest_hash"]}
            plan["state"] = "installing"
            plan["_started"] = False
            plan["_progress"] = {"item": None, "downloaded_bytes": None, "elapsed_ms": 0, "error": None}
            self._save(plan)
            try:
                job = self._submit(plan)
            except Exception:
                plan["state"] = "failed"
                plan["error"] = {"code": "not_started", "message_pt": "Não consegui iniciar a instalação. Nada foi instalado."}
                self._save(plan)
                raise adapters.ActionError("not_started", "Não consegui iniciar a instalação. Nada foi instalado.")
            plan["_job_id"] = job["id"]
            return self._remember(plan, request_id, 202, {"plan_id": plan_id, "state": "installing"})

    def _remember(self, plan, request_id, code, body):
        plan.setdefault("_decisions", {})[request_id] = {"code": code, "body": body}
        self._save(plan)
        return code, body

    def _submit(self, plan):
        """Cria o trabalho de instalação, preso ao plano aceito. A decisão do dono já foi a aprovação."""
        engine = self.engine
        base = {"workspace": "default", "plan_id": plan["plan_id"]}
        if plan["target"] in ("claude", "cli"):
            provider = _provider_of(plan["target"], plan.get("resource_id"))
            request = dict(base, kind="tools", action="install", provider=provider)
            if plan.get("_variant"):
                request["variant"] = plan["_variant"]
        else:
            request = dict(base, kind="rea", action="setup")
        job = engine.submit(request)
        if job["status"] == "awaiting_approval":
            job = engine.approve(job["id"])
        return job

    def cancel(self, plan_id, request_id):
        with self.lock:
            plan = self.get(plan_id)
            if plan is None:
                raise adapters.ActionError("plan_not_found", "Não encontrei este plano. Crie um novo.")
            job_id = None
            if plan["state"] in ("awaiting_consent", "size_unknown", "waiting_wifi", "expired"):
                plan["state"] = "cancelled"
                self._save(plan)
                return 200, {"plan_id": plan_id, "state": "cancelled"}
            if plan["state"] == "installing":
                plan["state"] = "cancelling"
                self._save(plan)
                job_id = plan.get("_job_id")
        if plan["state"] == "cancelling" and job_id:
            try:
                self.engine.cancel(job_id)
            except KeyError:
                pass
            return 202, {"plan_id": plan_id, "state": "cancelling"}
        return 200, {"plan_id": plan_id, "state": plan["state"]}

    def accepted_job(self, plan_id):
        plan = self.get(plan_id)
        if plan and plan.get("_consent") and plan["state"] in ("installing", "completed"):
            return self.engine.store.job(plan.get("_job_id"))
        return None

    # ---- execução (lado do trabalho) ---------------------------------------------------------------------
    def run_planned(self, request, target, resource_id, work):
        """Roda `work()` sob a permissão do plano aceito (ou sem permissão nenhuma, se não há plano)."""
        plan_id = request.get("plan_id")
        if not plan_id:
            return work()
        with self.lock:
            plan = self._load(plan_id)
            if (plan is None or plan.get("state") != "installing" or not plan.get("_consent")
                    or plan.get("_started") or plan["target"] != target
                    or (plan.get("resource_id") or None) != (resource_id or None)):
                raise adapters.ActionError(
                    "consent_required", "Este plano não está aceito ou já foi usado. Revise a instalação.")
            derived = derive(self.engine, target, resource_id, plan.get("_variant"))
            steps = [[n, c] for n, c in derived[0]] if derived else None
            if steps is None or _sha(steps) != plan["_steps_hash"]:
                self._finish(plan, "failed", {"code": "plan_changed", "message_pt":
                                              "O que falta instalar mudou. Revise o plano de novo."})
                raise adapters.ActionError("plan_changed", "O que falta instalar mudou. Revise o plano de novo.")
            plan["_started"] = True
            plan["_t0"] = self.clock()
            self._save(plan)
            commands = [c for _, cmds in plan["_steps"] for c in cmds]
        self._emit(plan, "installing", None)
        self.engine.context.install_grant = Grant(plan_id, commands)
        self.engine.context.install_plan = plan_id
        try:
            result = work()
        except adapters.ActionError as err:
            code = "cancelled" if err.code == "cancelled" else "failed"
            self._finish(plan, code, {"code": err.code, "message_pt": adapters.redact(str(err))[:300]})
            raise
        except BaseException:
            self._finish(plan, "failed", {"code": "unexpected", "message_pt": "A instalação parou de forma inesperada."})
            raise
        finally:
            self.engine.context.install_grant = None
            self.engine.context.install_plan = None
        waiting = isinstance(result, dict) and result.get("state") == "waiting_wifi"
        self._finish(plan, "waiting_wifi" if waiting else "completed", None)
        return result

    def note_item(self, name):
        """Chamado a cada passo: alimenta o progresso do plano em execução (install.status)."""
        plan_id = getattr(self.engine.context, "install_plan", None)
        if not plan_id:
            return
        with self.lock:
            plan = self._load(plan_id)
            if plan is None:
                return
            plan["_progress"] = {"item": name, "downloaded_bytes": None,
                                 "elapsed_ms": int((self.clock() - plan.get("_t0", self.clock())) * 1000),
                                 "error": None}
            self._save(plan)
        self._emit(plan, "installing", name)

    def check_step_size(self, name, commands):
        """Antes de baixar um passo: se ficou maior que o plano aceito, para e exige nova escolha."""
        plan_id = getattr(self.engine.context, "install_plan", None)
        plan = self._load(plan_id) if plan_id else None
        if plan is None:
            return
        info = self.measure(name, commands)
        accepted = next((i for i in plan["items"] if i["name"] == _item_name(name, plan.get("_label"))), None)
        if info is None or accepted is None or accepted["download_bytes"] is None:
            return
        if info["download_bytes"] is not None and info["download_bytes"] > accepted["download_bytes"] + MB:
            raise adapters.ActionError(
                "plan_changed", "O download de %s ficou maior do que o plano que você aceitou. "
                "Nada mais foi baixado; revise o plano de novo." % accepted["name"])

    def _finish(self, plan, state, error):
        with self.lock:
            fresh = self._load(plan["plan_id"]) or plan
            fresh["state"] = state
            fresh["error"] = error
            fresh["_progress"] = {"item": (fresh.get("_progress") or {}).get("item"), "downloaded_bytes": None,
                                  "elapsed_ms": int((self.clock() - fresh.get("_t0", self.clock())) * 1000),
                                  "error": error}
            self._save(fresh)
        self._emit(fresh, state, fresh["_progress"]["item"], error)

    def _emit(self, plan, state, item, error=None):
        try:
            self.engine.store.emit("install.status", {
                "plan_id": plan["plan_id"], "state": state, "item": item, "downloaded_bytes": None,
                "elapsed_ms": int((self.clock() - plan.get("_t0", self.clock())) * 1000), "error": error})
        except Exception:
            pass  # evento nunca derruba a instalação


def note_item(engine, name):
    manager = getattr(engine, "installs", None)
    if manager is not None:
        manager.note_item(name)


def check_step_size(engine, name, commands):
    manager = getattr(engine, "installs", None)
    if manager is not None:
        manager.check_step_size(name, commands)
