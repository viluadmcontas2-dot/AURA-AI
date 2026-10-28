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
