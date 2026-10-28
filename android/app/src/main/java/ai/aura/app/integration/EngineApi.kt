package ai.aura.app.integration

import org.json.JSONObject

/** O que o gateway pede ao motor. [AuraEngineClient] fala HTTP de verdade; os testes usam um falso. */
interface EngineApi {
    suspend fun health(): JSONObject
    suspend fun capabilities(): JSONObject
    suspend fun jobs(): JSONObject
    suspend fun events(since: Long): JSONObject
    suspend fun route(text: String, workspace: String = "default"): JSONObject
    suspend fun submit(command: JSONObject, requestId: String): JSONObject
    suspend fun approve(id: String): JSONObject
    suspend fun cancel(id: String): JSONObject
    suspend fun authStatus(): JSONObject
    suspend fun startAuth(provider: String): JSONObject
    /** POST /v1/tools/install: instala a CLI no Termux (202 + job). */
    suspend fun installTool(provider: String): JSONObject = throw UnsupportedOperationException("installTool")
    /** GET /v1/jobs/{id}: acompanha uma tarefa até o fim (a instalação de uma CLI devolve 202 + job). */
    suspend fun job(id: String): JSONObject = throw UnsupportedOperationException("job")
    /** POST /v1/auth/code: o código que o dono copiou da página de login do Claude. */
    suspend fun authCode(provider: String, code: String): JSONObject = throw UnsupportedOperationException("authCode")
    /** POST /v1/auth/token: token colado pelo dono. Nunca é registrado nem mostrado depois. */
    suspend fun authToken(provider: String, token: String): JSONObject = throw UnsupportedOperationException("authToken")
    suspend fun policy(): JSONObject
    suspend fun putPolicy(doc: JSONObject): JSONObject
    suspend fun models(): JSONObject
    suspend fun putModels(doc: JSONObject): JSONObject
    suspend fun spend(): JSONObject
    suspend fun skills(): JSONObject
    suspend fun usage(): JSONObject
    suspend fun memory(workspace: String): JSONObject

    /** O motor responde 204 sem corpo: o resultado é um objeto vazio, nunca um erro. */
    suspend fun forgetMemory(id: String): JSONObject

    /** Só o workspace dado: sem ele o motor apagaria os de todos os workspaces. */
    suspend fun forgetAllMemory(workspace: String): JSONObject
    /** POST /v1/chat: 202 {chat_id, session_id}. Vazio em [sessionId] = conversa nova. */
    suspend fun chat(text: String, sessionId: String?, workspace: String): JSONObject =
        throw UnsupportedOperationException("chat")
    /** GET /v1/chat/{id}: {state: running|done|error, text, session_id, error?}. */
    suspend fun chatState(id: String): JSONObject = throw UnsupportedOperationException("chatState")
    /** POST /v1/chat/{id}/stop. */
    suspend fun chatStop(id: String): JSONObject = throw UnsupportedOperationException("chatStop")
    /** GET /v1/chats: lista {id, title, updated_at} (lista na raiz chega como {"items": [...]}). */
    suspend fun chats(): JSONObject = throw UnsupportedOperationException("chats")
    /** GET /v1/chats/{id}/messages. */
    suspend fun chatMessages(id: String): JSONObject = throw UnsupportedOperationException("chatMessages")
    /** POST /v1/intent {text}: {kind: chat|command|reform|project|task, ...}. */
    suspend fun intent(text: String): JSONObject = throw UnsupportedOperationException("intent")
}
