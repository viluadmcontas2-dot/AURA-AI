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
}
