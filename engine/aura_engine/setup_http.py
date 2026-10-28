"""Rotas HTTP do preparo do assistente: C3 (estado) e C4 (instalação com decisão vinculada ao plano).

C3 `GET /v1/setup/status` é só leitura: não instala, não atualiza e não inicia login.
C4 `POST /v1/install-plans` prepara o manifesto; `.../decision` é a única porta para instalar; `.../measure`
refaz a consulta de tamanho; `.../cancel` para. `POST /v1/tools/install` só devolve o trabalho de um plano JÁ aceito.
"""
from datetime import datetime, timezone

from . import adapters, cli_setup

STATUS = {"invalid_request": 400, "unsupported_target": 400, "plan_not_found": 404, "plan_changed": 409,
          "plan_expired": 409, "size_unknown": 409, "request_conflict": 409, "consent_required": 403,
          "not_started": 502}
ACTIONS = {"plan_changed": "review_installation", "plan_expired": "create_plan", "size_unknown": "measure_size",
           "request_conflict": "review_request", "consent_required": "review_installation",
           "plan_not_found": "create_plan"}


def _error(error):
    code = error.code
    message = adapters.redact(str(error))[:300]
    return STATUS.get(code, 502), {"error": {
        "code": code, "message": message, "message_pt": message,
        "action": ACTIONS.get(code, "none"), "retryable": code in ("size_unknown", "not_started")}}


def status(engine):
    """C3: o que dá para saber SEM mexer em nada. Ausência de resposta é estado do app, não deste JSON."""
    exe = cli_setup.EXE["claude"]
    if not adapters.installed(exe):
        installation, auth = "missing", "unknown"
    elif not cli_setup._works(exe, engine.home, engine):
        installation, auth = "unsupported", "unknown"
    else:
        installation = "installed"
        if "claude" in engine.auth.active:
            auth = "pending"
        else:
            row = engine.auth._row("claude")
            if row["state"] == "conectado":
                auth = "signed_in"
            elif row["state"] == "expirado":
                auth = "expired"
            elif row.get("detail") == "Sem resposta do programa.":
                auth = "unknown"
            else:
                auth = "signed_out"
    nxt = ("review_installation" if installation != "installed" else
           "none" if auth == "signed_in" else "sign_in" if auth in ("signed_out", "expired") else "check_again")
    return {"engine": {"state": "ready", "update_available": False},
            "claude": {"installation": installation, "auth": auth}, "next_action": nxt,
            "checked_at": datetime.now(timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ")}


def http(engine, method, path, body, query=""):
    """Devolve (código, dados) ou None quando a rota não é daqui."""
    parts = path.split("/")
    try:
        if path == "/v1/setup/status" and method == "GET":
            return 200, status(engine)
        if path == "/v1/tools/install" and method == "POST":
            data = body()
            provider = data.get("provider")
            if provider not in cli_setup.EXE:
                raise ValueError("provider must be one of: " + ", ".join(cli_setup.PROVIDERS))
            job = engine.installs.accepted_job(data.get("plan_id")) if data.get("plan_id") else None
            if job is None or job["request"].get("provider") != provider:
                raise adapters.ActionError(
                    "consent_required", "Para instalar preciso da sua confirmação. Abra Conexões e toque em Instalar.")
            return 202, job
        if parts[:3] != ["", "v1", "install-plans"] or len(parts) > 5:
            return None
        manager = engine.installs
        if len(parts) == 3 and method == "POST":
            data = body()
            created, plan = manager.create(data.get("request_id"), data.get("target"), data.get("reason"),
                                           resource_id=data.get("resource_id"), variant=data.get("variant"))
            return (201 if created else 200), manager.public(plan)
        if len(parts) == 4 and method == "GET":
            plan = manager.get(parts[3])
            if plan is None:
                raise adapters.ActionError("plan_not_found", "Não encontrei este plano. Crie um novo.")
            return 200, manager.public(plan)
        if len(parts) == 5 and method == "POST":
            if parts[4] == "measure":
                body()
                return 200, manager.public(manager.remeasure(parts[3]))
            if parts[4] == "decision":
                data = body()
                return manager.decide(parts[3], data.get("request_id"), data.get("manifest_hash"),
                                      data.get("decision"))
            if parts[4] == "cancel":
                return manager.cancel(parts[3], body().get("request_id"))
    except adapters.ActionError as error:
        return _error(error)
    return None
