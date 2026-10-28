package ai.aura.app.integration

import ai.aura.app.offline.EngineUnreachableException
import ai.aura.app.offline.OfflineReason
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.ConnectException
import java.net.HttpURLConnection
import java.net.SocketTimeoutException
import java.net.URL

/** Typed HTTP transport for local Termux engine. UI consumes state, never terminal transcripts. */
class AuraEngineClient(private val credentials: EngineCredentialStore) : EngineApi {
    private val endpoint = "http://127.0.0.1:8765"
    private fun segment(value: String): String {
        require(SEGMENT.matches(value)) { "Invalid identifier" }
        return value
    }

    suspend fun request(
        path: String,
        method: String = "GET",
        body: JSONObject? = null,
        readTimeoutMs: Int = 15000,
    ): JSONObject =
        withContext(Dispatchers.IO) {
            require(path.startsWith("/v1/") && !path.contains("..")) { "Invalid API route" }
            val token = credentials.read()
                ?: throw EngineUnreachableException("Conecte o Termux em Conexões", OfflineReason.NoToken)
            val conn = (URL(endpoint + path).openConnection() as HttpURLConnection)
            try {
                conn.connectTimeout = 3000
                conn.readTimeout = readTimeoutMs
                conn.requestMethod = method
                conn.setRequestProperty("Authorization", "Bearer " + token)
                conn.setRequestProperty("Accept", "application/json")
                if (body != null) {
                    conn.doOutput = true
                    conn.setRequestProperty("Content-Type", "application/json")
                    conn.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
                } else if (method == "POST") {
                    conn.doOutput = true
                    conn.setRequestProperty("Content-Type", "application/json")
                    conn.outputStream.use { it.write("{}".toByteArray(Charsets.UTF_8)) }
                }
                val status = conn.responseCode
                val stream = if (status in 200..299) conn.inputStream else conn.errorStream
                val payload = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty()
                if (status !in 200..299) {
                    // O motor manda {"error":{"code","message"}} ou, em job não encontrado, {"error":"texto"}.
                    val error = runCatching { JSONObject(payload).opt("error") }.getOrNull()
                    val reason = (if (error is JSONObject) error.optString("message") else error as? String).orEmpty()
                    val code = (error as? JSONObject)?.optString("code")?.ifBlank { null }
                    throw AuraEngineException(status, reason.ifBlank { "AURA Engine HTTP " + status }, code)
                }
                when {
                    payload.isBlank() -> JSONObject() // 204: sem corpo
                    // Alguns endpoints (ex.: /v1/auth/status) devolvem uma lista na raiz.
                    payload.trimStart().startsWith("[") -> JSONObject().put("items", JSONArray(payload))
                    else -> JSONObject(payload)
                }
            } catch (_: ConnectException) {
                throw EngineUnreachableException("Termux não responde. Abra o Termux e inicie o motor.", OfflineReason.EngineStopped)
            } catch (_: SocketTimeoutException) {
                throw EngineUnreachableException("O motor demorou a responder. Tente de novo.", OfflineReason.EngineStopped)
            } catch (_: IOException) {
                // O servidor fecha a conexão sem ler o corpo em 401/404: para o app, é "sem resposta".
                throw EngineUnreachableException("O motor não respondeu direito. Tente de novo.", OfflineReason.EngineStopped)
            } finally {
                conn.disconnect()
            }
        }

    override suspend fun health() = request("/v1/health")
    override suspend fun capabilities() = request("/v1/capabilities")
    override suspend fun jobs() = request("/v1/jobs")
    override suspend fun events(since: Long) = request("/v1/events?since=" + since, readTimeoutMs = 4000)
    override suspend fun route(text: String, workspace: String): JSONObject =
        request("/v1/route", "POST", JSONObject().put("text", text).put("workspace", workspace))
    override suspend fun submit(command: JSONObject, requestId: String): JSONObject =
        request("/v1/jobs", "POST", JSONObject().put("request_id", requestId).put("request", command))
    suspend fun job(id: String) = request("/v1/jobs/" + segment(id))
    override suspend fun approve(id: String) = request("/v1/jobs/" + segment(id) + "/approve", "POST")
    override suspend fun cancel(id: String) = request("/v1/jobs/" + segment(id) + "/cancel", "POST")
    /** Login contract: state only; tokens/cookies never cross this boundary. */
    override suspend fun authStatus() = request("/v1/auth/status")
    override suspend fun installTool(provider: String) =
        request("/v1/tools/install", "POST", JSONObject().put("provider", segment(provider)), readTimeoutMs = 8000)
    override suspend fun authCode(provider: String, code: String) =
        request("/v1/auth/code", "POST", JSONObject().put("provider", segment(provider)).put("code", code), readTimeoutMs = 15000)
    override suspend fun authToken(provider: String, token: String) =
        request("/v1/auth/token", "POST", JSONObject().put("provider", segment(provider)).put("token", token), readTimeoutMs = 15000)
    override suspend fun startAuth(provider: String) =
        request("/v1/auth/start", "POST", JSONObject().put("provider", segment(provider)),
            readTimeoutMs = 8000)

    /** Política, modelos e gastos: leituras curtas; PUT um pouco mais folgado (o motor grava em disco). */
    override suspend fun policy() = request("/v1/policy", readTimeoutMs = 5000)
    override suspend fun putPolicy(doc: JSONObject) = request("/v1/policy", "PUT", doc, readTimeoutMs = 8000)
    override suspend fun models() = request("/v1/models", readTimeoutMs = 5000)
    override suspend fun putModels(doc: JSONObject) = request("/v1/models", "PUT", doc, readTimeoutMs = 8000)
    override suspend fun spend() = request("/v1/spend", readTimeoutMs = 5000)

    override suspend fun skills() = request("/v1/skills", readTimeoutMs = 5000)
    override suspend fun usage() = request("/v1/usage", readTimeoutMs = 5000)
    override suspend fun memory(workspace: String) =
        request("/v1/memory?workspace=" + segment(workspace), readTimeoutMs = 5000)
    override suspend fun forgetMemory(id: String) = request("/v1/memory/" + segment(id), "DELETE", readTimeoutMs = 5000)
    override suspend fun forgetAllMemory(workspace: String) =
        request("/v1/memory?workspace=" + segment(workspace), "DELETE", readTimeoutMs = 8000)
}

/** Identificador que cabe num trecho de URL: letras, números, `_` e `-`. */
internal val SEGMENT = Regex("[a-zA-Z0-9_-]{1,100}")

/** code = error.code do contrato, quando o motor enviou (nunca contém token). */
class AuraEngineException(val httpStatus: Int, message: String, val code: String? = null) : Exception(message)
