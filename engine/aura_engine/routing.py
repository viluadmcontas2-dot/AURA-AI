"""Escolha e fiscalizacao de modelo a partir de models.json (lido a cada job)."""
import fnmatch
import os
import shutil

CLAUDE_KINDS = ("claude_cli", "claude_api")
MODEL_KINDS = ("claude_cli", "claude_api", "gemini_cli", "gemini_api", "agy_cli")
FAMILY_RANK = {"haiku": 0, "sonnet": 1, "opus": 2}
LEVEL_RANK = {"low": 0, "medium": 1, "high": 2}
NOT_REPORTED = "não informado"


class ModelRefused(ValueError):
    pass


def family_of(model):
    lower = str(model).lower()
    for name in ("fable", "opus", "sonnet", "haiku"):
        if name in lower:
            return name
    return None


def _split(token):
    parts = [p for p in token.split(":") if p]
    if parts and parts[0] == "subagente":
        parts = parts[1:]
    return (parts[0] if parts else "", parts[1] if len(parts) > 1 else None)


def is_denied(model, models):
    lower = str(model).lower()
    family = family_of(model)
    for pattern in models.get("deny", []):
        pattern = pattern.lower()
        if fnmatch.fnmatchcase(lower, pattern) or (family and fnmatch.fnmatchcase(family, pattern)):
            return True
    return False


def check(kind, model, effort, models):
    """Recusa modelo proibido ou acima do teto. Nunca repete texto vindo do pedido."""
    if model is None:
        return
    if is_denied(model, models):
        raise ModelRefused("modelo proibido pela política (models.json)")
    if kind in CLAUDE_KINDS:
        family = family_of(model)
        if family not in FAMILY_RANK:
            raise ModelRefused("modelo desconhecido para o teto; use haiku ou sonnet")
        ceiling = models.get("ceiling", {}).get("claude")
        if ceiling:
            cfam, clevel = _split(ceiling)
            level = effort if effort in LEVEL_RANK else "medium"
            if (FAMILY_RANK[family], LEVEL_RANK[level]) > (
                    FAMILY_RANK.get(cfam, 1), LEVEL_RANK.get(clevel or "medium", 1)):
                raise ModelRefused("modelo acima do teto %s da política" % ceiling)


def requested_model(request):
    """Modelo pedido pelo job; para a API do Claude vale tambem o padrao do ambiente."""
    model = request.get("model")
    if model is None and request.get("kind") == "claude_api":
        model = os.environ.get("AURA_CLAUDE_MODEL") or None
    return model


def effort_of(request):
    effort = request.get("effort")
    return effort if isinstance(effort, str) else None


def as_model_request(request):
    """skills/run delega a um CLI: a politica de modelos vale para esse CLI."""
    if request.get("kind") == "skills" and request.get("action") == "run":
        return dict(request, kind=request.get("provider", "claude_cli"))
    return request


def validate_request(request, models):
    request = as_model_request(request)
    kind = request.get("kind")
    if kind in MODEL_KINDS:
        check(kind, requested_model(request), effort_of(request), models)


def resolve(request, models):
    """Devolve (model_para_injetar|None, {"model_used","why"}|None). Nao altera o pedido."""
    request = as_model_request(request)
    kind = request.get("kind")
    if kind not in MODEL_KINDS:
        return None, None
    explicit = requested_model(request)
    if explicit is not None:
        check(kind, explicit, effort_of(request), models)
        return None, {"model_used": str(explicit), "why": "pedido explícito do job"}
    if kind != "claude_cli":
        return None, {"model_used": NOT_REPORTED, "why": "padrão do provedor; sem escolha de modelo"}
    task = request.get("task")
    if isinstance(task, str):
        for route in models.get("routes", []):
            if task not in route["task"].split("|"):
                continue
            for token in route["use"]:
                family, level = _split(token)
                if family in FAMILY_RANK:
                    try:
                        check(kind, family, level, models)
                    except ModelRefused:
                        continue
                    return family, {"model_used": family,
                                    "why": "rota da tarefa %s: %s mais barato que cumpre" % (task, token)}
            return None, {"model_used": NOT_REPORTED,
                          "why": "rota da tarefa %s prefere %s; segui com o claude_cli pedido, "
                                 "sem trocar modelo" % (task, "/".join(route["use"]))}
    ceiling = models.get("ceiling", {}).get("claude")
    family = _split(ceiling)[0] if ceiling else "sonnet"
    if family not in FAMILY_RANK or is_denied(family, models):
        family = "haiku"
    return family, {"model_used": family,
                    "why": "sem rota para a tarefa: usei o teto %s, nunca o padrão do CLI" % (ceiling or "sonnet:medium")}


TOOL_KINDS = ("rea", "jadx", "apktool", "adb", "inventario", "cobertura", "decompilar")
CLAUDE_MIN_MAX_TOKENS = 400  # Claude so orquestra/decide: resposta curta, nunca texto longo


def choose_executor(task_kind, agy_available=None, gemini_key=None, cost_lock=True, gemini_free=False):
    """Quem executa a tarefa, do mais barato ao mais caro.

    "tool" (sem modelo) -> "agy" (assinatura) -> "gemini_api" (chave presente e trava de custo
    desligada ou plano gratuito) -> "claude_min" (so orquestrar/decidir, saida curta)."""
    if task_kind in TOOL_KINDS:
        return "tool"
    if agy_available is None:
        agy_available = shutil.which("agy") is not None
    if gemini_key is None:
        gemini_key = bool(os.environ.get("GEMINI_API_KEY"))
    if agy_available:
        return "agy"
    if gemini_key and (not cost_lock or gemini_free):
        return "gemini_api"
    return "claude_min"
