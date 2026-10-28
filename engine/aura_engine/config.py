"""policy.json e models.json: configuracao e dado, nao codigo.

Gravacao atomica (arquivo temporario + os.replace), modo 0600, version+1 a cada PUT.
Valores fora do enum sao recusados sem gravar nada. Nenhum segredo entra aqui.
"""
import copy
import fnmatch
import json
import os
import pathlib
import re
import tempfile
import threading
import time

MODES = ("pergunta", "equilibrado", "autonomo", "total")
AUTO_ASK = ("auto", "ask")
RISKS = ("read", "write", "spend", "critical")
ROOT_VIA = ("su", "tsu")
ON_REACH = ("avisar", "pausar")
NOTIFY = ("so_se_precisar", "sempre", "nunca")
API_FALLBACK = ("never", "ask")
FAMILY_RANK = {"haiku": 0, "sonnet": 1, "opus": 2}
LEVEL_RANK = {"low": 0, "medium": 1, "high": 2}
CLAUDE_CEILING_MAX = (FAMILY_RANK["sonnet"], LEVEL_RANK["medium"])

DEFAULT_POLICY = {
    "version": 1,
    "mode": "autonomo",
    "by_risk": {"read": "auto", "write": "ask", "spend": "ask", "critical": "ask"},
    "by_kind": {"shell": "ask", "adb": "ask", "files": "auto", "github": "ask", "root": "ask"},
    "root": {"enabled": False, "via": ["su", "tsu"]},
    "scopes": [{"path": "*", "write": "ask"}],
    "spend_cap": {"per_job": None, "per_day": None, "per_month": None, "on_reach": "avisar"},
    "auto_countdown_seconds": 0,
    "notify": "so_se_precisar",
}

AUTONOMY = ("total", "ask_destructive", "ask_all", "custom")  # custom = policy.json (avancado)
TRANSPORT_MAX = 40
DEFAULT_SETTINGS = {
    "autonomy": "total",  # decisao do dono: sem aprovacao por padrao
    "cost_lock": True,    # interruptor do dono: dinheiro nao volta, entao comeca ligado
    "network": {"metered": False, "transport": "unknown"},  # desconhecido = Wi-Fi
}

DEFAULT_MODELS = {
    "version": 1,
    "deny": ["fable*"],
    "ceiling": {"claude": "sonnet:medium"},
    "routes": [
        {"task": "triagem|classificar|resumir|status", "use": ["haiku:medium"]},
        {"task": "codigo_mecanico|testes|refatoracao_simples",
         "use": ["codex_cli", "agy_cli", "haiku:high"]},
        {"task": "ui_compose|planejar|revisar", "use": ["sonnet:medium"]},
        {"task": "pesquisa_ampla", "use": ["subagente:haiku:medium"]},
    ],
    "prefer_subscription": True,
    "api_fallback": "never",
}


def atomic_write_json(path, doc):
    """Temporario no mesmo diretorio, fsync, os.replace: o arquivo antigo nunca fica pela metade."""
    path = pathlib.Path(path)
    fd, temp = tempfile.mkstemp(dir=str(path.parent), prefix=path.name + ".", suffix=".tmp")
    try:
        os.fchmod(fd, 0o600)
        with os.fdopen(fd, "w", encoding="utf-8") as stream:
            json.dump(doc, stream, ensure_ascii=False, indent=2, sort_keys=True)
            stream.flush()
            os.fsync(stream.fileno())
        os.replace(temp, str(path))
    except BaseException:
        try:
            os.unlink(temp)
        except OSError:
            pass
        raise


class ConfigError(ValueError):
    """Entrada recusada. A mensagem nunca repete o valor recebido."""


class VersionConflict(ConfigError):
    pass


def _enum(value, options, name):
    if not isinstance(value, str) or value not in options:
        raise ConfigError("%s deve ser um de: %s" % (name, ", ".join(options)))
    return value


def _obj(value, name):
    if not isinstance(value, dict):
        raise ConfigError("%s deve ser um objeto" % name)
    return value


def _only(data, allowed, name):
    extra = sorted(set(data) - set(allowed))
    if extra:
        raise ConfigError("%s: campo desconhecido" % name)


def _number_or_null(value, name):
    if value is None:
        return None
    if isinstance(value, bool) or not isinstance(value, (int, float)) or value < 0 \
            or value != value or value == float("inf"):
        raise ConfigError("%s deve ser null ou numero >= 0" % name)
    return value


def validate_policy(doc):
    _only(doc, DEFAULT_POLICY, "policy")
    out = {"version": doc["version"], "mode": _enum(doc["mode"], MODES, "mode")}
    by_risk = _obj(doc["by_risk"], "by_risk")
    _only(by_risk, RISKS, "by_risk")
    out["by_risk"] = {k: _enum(v, AUTO_ASK, "by_risk") for k, v in by_risk.items()}
    by_kind = _obj(doc["by_kind"], "by_kind")
    if len(by_kind) > 40:
        raise ConfigError("by_kind grande demais")
    for key in by_kind:
        if not isinstance(key, str) or not re.fullmatch(r"[a-z_]{1,30}", key):
            raise ConfigError("by_kind: nome de categoria invalido")
    out["by_kind"] = {k: _enum(v, AUTO_ASK, "by_kind") for k, v in by_kind.items()}
    root = _obj(doc["root"], "root")
    _only(root, ("enabled", "via"), "root")
    enabled = root.get("enabled", False)
    via = root.get("via", [])
    if not isinstance(enabled, bool):
        raise ConfigError("root.enabled deve ser verdadeiro ou falso")
    if not isinstance(via, list) or len(via) > 4:
        raise ConfigError("root.via deve ser uma lista")
    out["root"] = {"enabled": enabled, "via": [_enum(v, ROOT_VIA, "root.via") for v in via]}
    scopes = doc["scopes"]
    if not isinstance(scopes, list) or len(scopes) > 200:
        raise ConfigError("scopes deve ser uma lista")
    clean = []
    for scope in scopes:
        scope = _obj(scope, "scopes")
        _only(scope, ("path", "write"), "scopes")
        path = scope.get("path")
        if not isinstance(path, str) or not path or len(path) > 1000 or "\x00" in path:
            raise ConfigError("scopes.path invalido")
        clean.append({"path": path, "write": _enum(scope.get("write"), AUTO_ASK, "scopes.write")})
    out["scopes"] = clean
    cap = _obj(doc["spend_cap"], "spend_cap")
    _only(cap, DEFAULT_POLICY["spend_cap"], "spend_cap")
    merged = dict(DEFAULT_POLICY["spend_cap"])
    merged.update(cap)
    out["spend_cap"] = {
        "per_job": _number_or_null(merged["per_job"], "spend_cap.per_job"),
        "per_day": _number_or_null(merged["per_day"], "spend_cap.per_day"),
        "per_month": _number_or_null(merged["per_month"], "spend_cap.per_month"),
        "on_reach": _enum(merged["on_reach"], ON_REACH, "spend_cap.on_reach")}
    secs = doc["auto_countdown_seconds"]
    if isinstance(secs, bool) or not isinstance(secs, int) or not 0 <= secs <= 3600:
        raise ConfigError("auto_countdown_seconds deve ser inteiro entre 0 e 3600")
    out["auto_countdown_seconds"] = secs
    out["notify"] = _enum(doc["notify"], NOTIFY, "notify")
    return out


def _model_parts(token):
    """'subagente:haiku:medium' -> ('haiku', 'medium'); 'codex_cli' -> ('codex_cli', None)."""
    parts = token.split(":")
    if parts[0] == "subagente":
        parts = parts[1:]
    return (parts[0], parts[1] if len(parts) > 1 else None) if parts and parts[0] else ("", None)


def _denied(token, deny):
    family = _model_parts(token)[0]
    return any(fnmatch.fnmatchcase(token, p) or fnmatch.fnmatchcase(family, p) for p in deny)


def _above_claude_ceiling(token):
    family, level = _model_parts(token)
    if family not in FAMILY_RANK:
        return False
    rank = (FAMILY_RANK[family], LEVEL_RANK.get(level, 2))
    return rank > CLAUDE_CEILING_MAX


_TOKEN = re.compile(r"[A-Za-z0-9_.*-]{1,60}(:[A-Za-z0-9_.*-]{1,60}){0,2}")


def validate_models(doc):
    _only(doc, DEFAULT_MODELS, "models")
    out = {"version": doc["version"]}
    deny = doc["deny"]
    if not isinstance(deny, list) or len(deny) > 100 or any(
            not isinstance(p, str) or not re.fullmatch(r"[A-Za-z0-9_.*:-]{1,60}", p) for p in deny):
        raise ConfigError("deny deve ser uma lista de padroes")
    if "fable*" not in deny:
        raise ConfigError("deny deve conter fable*: modelo Fable nunca e permitido")
    out["deny"] = list(dict.fromkeys(deny))
    ceiling = _obj(doc["ceiling"], "ceiling")
    clean_ceiling = {}
    for provider, value in ceiling.items():
        if not isinstance(provider, str) or not re.fullmatch(r"[a-z_]{1,30}", provider):
            raise ConfigError("ceiling: nome de provedor invalido")
        if not isinstance(value, str) or not _TOKEN.fullmatch(value):
            raise ConfigError("ceiling: modelo invalido")
        if _denied(value, out["deny"]):
            raise ConfigError("ceiling: modelo proibido")
        if provider == "claude":
            family, level = _model_parts(value)
            if family not in FAMILY_RANK or level not in LEVEL_RANK or _above_claude_ceiling(value):
                raise ConfigError("ceiling.claude: teto maximo e sonnet:medium")
        clean_ceiling[provider] = value
    out["ceiling"] = clean_ceiling
    routes = doc["routes"]
    if not isinstance(routes, list) or len(routes) > 100:
        raise ConfigError("routes deve ser uma lista")
    clean_routes = []
    for route in routes:
        route = _obj(route, "routes")
        _only(route, ("task", "use"), "routes")
        task, use = route.get("task"), route.get("use")
        if not isinstance(task, str) or not re.fullmatch(r"[A-Za-z0-9_|-]{1,200}", task):
            raise ConfigError("routes.task invalido")
        if not isinstance(use, list) or not use or len(use) > 20:
            raise ConfigError("routes.use deve ser uma lista nao vazia")
        for token in use:
            if not isinstance(token, str) or not _TOKEN.fullmatch(token):
                raise ConfigError("routes.use: modelo invalido")
            if _denied(token, out["deny"]):
                raise ConfigError("routes.use: modelo proibido")
            if _above_claude_ceiling(token):
                raise ConfigError("routes.use: acima do teto sonnet:medium")
        clean_routes.append({"task": task, "use": list(use)})
    out["routes"] = clean_routes
    if not isinstance(doc["prefer_subscription"], bool):
        raise ConfigError("prefer_subscription deve ser verdadeiro ou falso")
    out["prefer_subscription"] = doc["prefer_subscription"]
    out["api_fallback"] = _enum(doc["api_fallback"], API_FALLBACK, "api_fallback")
    return out


class ConfigStore:
    SPECS = {
        "policy": ("policy.json", DEFAULT_POLICY, validate_policy, "policy.changed"),
        "models": ("models.json", DEFAULT_MODELS, validate_models, "models.changed"),
    }

    def __init__(self, home, store):
        self.home = home
        self.store = store
        self.lock = threading.RLock()

    def _path(self, name):
        return self.home / self.SPECS[name][0]

    def _read(self, name):
        _, default, validate, _ = self.SPECS[name]
        path = self._path(name)
        try:
            doc = json.loads(path.read_text("utf-8"))
            if not isinstance(doc, dict) or type(doc.get("version")) is not int:
                raise ValueError
            merged = copy.deepcopy(default)
            merged.update(doc)
            validate(merged)
            return merged
        except (OSError, ValueError):
            return copy.deepcopy(default)

    def _write(self, name, doc):
        self.home.mkdir(parents=True, exist_ok=True, mode=0o700)
        atomic_write_json(self._path(name), doc)

    def get(self, name):
        with self.lock:
            return self._read(name)

    def policy(self):
        return self.get("policy")

    # ---- ajustes do dono (autonomia, trava de custo, rede): settings.json, sem versao ----
    def settings(self):
        with self.lock:
            merged = copy.deepcopy(DEFAULT_SETTINGS)
            try:
                doc = json.loads((self.home / "settings.json").read_text("utf-8"))
            except (OSError, ValueError):
                return merged
            if not isinstance(doc, dict):
                return merged
            if doc.get("autonomy") in AUTONOMY:
                merged["autonomy"] = doc["autonomy"]
            if isinstance(doc.get("cost_lock"), bool):
                merged["cost_lock"] = doc["cost_lock"]
            net = doc.get("network")
            if (isinstance(net, dict) and isinstance(net.get("metered"), bool)
                    and isinstance(net.get("transport"), str)):
                merged["network"] = {"metered": net["metered"], "transport": net["transport"][:TRANSPORT_MAX]}
            return merged

    def set_setting(self, key, value):
        """Valida e grava um ajuste. A mensagem nunca repete o valor recebido."""
        if key == "autonomy":
            value = _enum(value, AUTONOMY, "autonomy")
        elif key == "cost_lock":
            if not isinstance(value, bool):
                raise ConfigError("cost_lock deve ser verdadeiro ou falso")
        elif key == "network":
            if (not isinstance(value, dict) or set(value) != {"metered", "transport"}
                    or not isinstance(value["metered"], bool) or not isinstance(value["transport"], str)
                    or not 0 < len(value["transport"]) <= TRANSPORT_MAX):
                raise ConfigError("network precisa de metered (verdadeiro ou falso) e transport (texto curto)")
            value = {"metered": value["metered"], "transport": value["transport"]}
        else:
            raise ConfigError("ajuste desconhecido")
        with self.lock:
            current = self.settings()
            current[key] = value
            self.home.mkdir(parents=True, exist_ok=True, mode=0o700)
            atomic_write_json(self.home / "settings.json", current)
        self.store.emit("settings.changed", {"key": key, "value": value})
        return value

    def models(self):
        return self.get("models")

    # ---- historico de versoes da politica (so campos validados da politica, nunca segredo) ----
    HISTORY_LIMIT = 500

    def _hist_dir(self):
        return self.home / "policy_history"

    def _hist_path(self, version):
        return self._hist_dir() / ("policy.v%d.json" % version)

    def _hist_write(self, version, parent, changed, doc, restored_from=None, at=True):
        folder = self._hist_dir()
        folder.mkdir(parents=True, exist_ok=True, mode=0o700)
        os.chmod(folder, 0o700)
        record = {"version": version, "parent": parent, "at": time.time() if at else None,
                  "changed": changed, "by": "user" if at else "sistema",
                  "policy": {k: v for k, v in doc.items() if k in DEFAULT_POLICY}}
        if restored_from is not None:
            record["restored_from"] = restored_from
        atomic_write_json(self._hist_path(version), record)
        stale = sorted(int(p.name[len("policy.v"):-len(".json")]) for p in folder.glob("policy.v*.json")
                       if p.name[len("policy.v"):-len(".json")].isdigit())
        for old in stale[:-self.HISTORY_LIMIT]:
            try:
                self._hist_path(old).unlink()
            except OSError:
                pass

    def _hist_read(self, version):
        try:
            record = json.loads(self._hist_path(version).read_text("utf-8"))
            if not isinstance(record, dict) or record.get("version") != version:
                return None
            merged = copy.deepcopy(DEFAULT_POLICY)
            merged.update(record["policy"])
            merged["version"] = version
            validate_policy(merged)
            return record
        except (OSError, ValueError, KeyError, TypeError):
            return None

    def _hist_row(self, record):
        return {"version": record["version"], "parent": record.get("parent"), "at": record.get("at"),
                "changed": record.get("changed", []), "mode": record["policy"].get("mode"),
                "by": record.get("by", "user"), "restored_from": record.get("restored_from")}

    def policy_history(self, limit=50):
        with self.lock:
            current = self._read("policy")
            rows = []
            folder = self._hist_dir()
            versions = set()
            if folder.is_dir():
                for path in folder.glob("policy.v*.json"):
                    number = path.name[len("policy.v"):-len(".json")]
                    if number.isdigit() and int(number) <= current["version"]:
                        versions.add(int(number))
            for version in sorted(versions):
                record = self._hist_read(version)
                if record is not None:
                    rows.append(self._hist_row(record))
            if current["version"] not in versions:
                rows.append({"version": current["version"], "parent": None, "at": None, "changed": [],
                             "mode": current["mode"], "by": "sistema", "restored_from": None})
            limit = max(1, min(int(limit), 500))
            return {"current": current["version"], "history": rows[-limit:]}

    def _commit(self, name, current, clean, extra=None):
        """Grava clean como a proxima versao. Historico primeiro; o arquivo vivo por ultimo."""
        event = self.SPECS[name][3]
        clean = dict(clean)
        clean["version"] = current["version"] + 1
        changed = sorted(k for k in clean if k != "version" and clean[k] != current.get(k))
        if name == "policy":
            if self._hist_read(current["version"]) is None:
                self._hist_write(current["version"], None, [], current, at=False)
            parent = (extra or {}).get("parent", current["version"])
            self._hist_write(clean["version"], parent, changed, clean,
                             restored_from=(extra or {}).get("restored_from"))
        self._write(name, clean)
        payload = {"version": clean["version"], "changed": changed, "by": "user"}
        if name == "policy":
            payload["mode"] = clean["mode"]
        if extra and "restored_from" in extra:
            payload["restored_from"] = extra["restored_from"]
        self.store.emit(event, payload)
        return clean

    def undo_policy(self, to_version=None):
        with self.lock:
            current = self._read("policy")
            now = self._hist_read(current["version"])
            if to_version is None:
                to_version = now.get("parent") if now else None
                if to_version is None:
                    raise ConfigError("nada para desfazer")
            if isinstance(to_version, bool) or not isinstance(to_version, int) or to_version < 1:
                raise ConfigError("to_version deve ser um numero de versao existente")
            if to_version == current["version"]:
                raise ConfigError("essa já é a versão atual")
            target = self._hist_read(to_version)
            if target is None or to_version > current["version"]:
                raise ConfigError("versão %d não existe no histórico ou está corrompida" % to_version)
            merged = copy.deepcopy(DEFAULT_POLICY)
            merged.update(target["policy"])
            merged["version"] = current["version"]
            clean = validate_policy(merged)
            return self._commit("policy", current, clean, {
                "parent": target.get("parent"), "restored_from": to_version})

    def update(self, name, patch):
        _, default, validate, event = self.SPECS[name]
        if not isinstance(patch, dict):
            raise ConfigError("corpo deve ser um objeto JSON")
        with self.lock:
            current = self._read(name)
            patch = dict(patch)
            if "version" in patch:
                if isinstance(patch["version"], bool) or patch["version"] != current["version"]:
                    raise VersionConflict("versao desatualizada: leia de novo antes de salvar")
                del patch["version"]
            merged = copy.deepcopy(current)
            for key, value in patch.items():
                if name == "policy" and key in ("by_risk", "by_kind", "root", "spend_cap") \
                        and isinstance(value, dict) and isinstance(merged.get(key), dict):
                    merged[key].update(value)
                else:
                    merged[key] = value
            clean = validate(merged)
            return self._commit(name, current, clean)
