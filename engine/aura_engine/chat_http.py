"""Rotas HTTP do chat (contrato C1): enviar com request_id, acompanhar, consultar por request_id e parar.

Erros usam o formato comum {"error": {"code", "message", "message_pt", "action", "retryable"}}: `message` fica
para clientes antigos, `message_pt` e `action` para a tela.
"""
from . import chat
from .chat import UUID_RE


def error(status, code, message_pt, action=None, retryable=False):
    return status, {"error": {"code": code, "message": message_pt, "message_pt": message_pt,
                              "action": action, "retryable": retryable}}


def not_found(what="Chat"):
    return error(404, "not_found", "%s não encontrado." % what)


def http(engine, method, path, body, query=""):
    parts = path.split("/")
    if parts[:3] != ["", "v1", "chat"]:
        return None
    if len(parts) == 3 and method == "POST":
        data = body()
        try:
            started = engine.chat.start(
                data.get("text"), session_id=data.get("session_id"), workspace=data.get("workspace", "default"),
                executor=data.get("executor"), request_id=data.get("request_id"), project_id=data.get("project_id"))
        except chat.RequestConflict:
            return error(409, "request_conflict", "Este pedido já foi usado com outro conteúdo.", "review_request")
        except chat.SessionNotFound:
            return error(404, "session_not_found", "Não encontrei esta conversa no Termux.", "back_to_sessions")
        return 202, started
    if len(parts) == 5 and parts[3] == "requests" and method == "GET":
        if not UUID_RE.fullmatch(parts[4]):
            return error(404, "request_not_found", "Este pedido não foi aceito pelo motor.")
        snapshot = engine.chat.by_request(parts[4])
        if snapshot is None:
            return error(404, "request_not_found", "Este pedido não foi aceito pelo motor.")
        return 200, snapshot
    if len(parts) == 4 and method == "GET":
        snapshot = engine.chat.state(parts[3])
        return not_found() if snapshot is None else (200, snapshot)
    if len(parts) == 5 and parts[4] == "stop" and method == "POST":
        try:
            data = body()  # request_id da parada: parar duas vezes é inofensivo, a chave só identifica o pedido
        except ValueError:
            data = {}  # corpo vazio (cliente antigo) também vale
        rid = data.get("request_id")
        if rid is not None and (not isinstance(rid, str) or not UUID_RE.fullmatch(rid)):
            raise ValueError("request_id must be a UUID")
        result = engine.chat.request_stop(parts[3])
        if result is not None:
            return (200 if result["state"] == "interrupted" else 202), result
        state = engine.store.chat_run(parts[3])
        if state is None:
            return not_found()
        # já terminou: devolve o estado final, sem desfazer nada
        return 200, {"chat_id": parts[3], "state": state["state"], "stopped": state["state"] == "interrupted"}
    return None
