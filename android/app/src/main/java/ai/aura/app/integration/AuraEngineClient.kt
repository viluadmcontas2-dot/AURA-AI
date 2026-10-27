package ai.aura.app.integration

import ai.aura.app.offline.EngineUnreachableException
import ai.aura.app.offline.OfflineReason
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.ConnectException
import java.net.HttpURLConnection
import java.net.SocketTimeoutException
import java.net.URL
import java.util.UUID

/** Typed HTTP transport for local Termux engine. UI consumes state, never terminal transcripts. */
class AuraEngineClient(private val credentials: EngineCredentialStore) {
    private val endpoint = "http://127.0.0.1:8765"
    private fun segment(value: String): String {
        require(Regex("[a-zA-Z0-9_-]{1,100}").matches(value)) { "Invalid identifier" }
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
                val payload = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() } ?: "{}"
                if (status !in 200..299) {
                    val error = runCatching { JSONObject(payload).optJSONObject("error") }.getOrNull()
                    val reason = error?.optString("message").orEmpty()
                    val code = error?.optString("code")?.ifBlank { null }
                    throw AuraEngineException(status, reason.ifBlank { "AURA Engine HTTP " + status }, code)
                }
                // Alguns endpoints (ex.: /v1/auth/status) devolvem uma lista na raiz.
                if (payload.trimStart().startsWith("[")) {
                    JSONObject().put("items", JSONArray(payload))
                } else {
                    JSONObject(payload)
                }
            } catch (_: ConnectException) {
                throw EngineUnreachableException("Termux não responde. Abra o Termux e inicie o motor.", OfflineReason.EngineStopped)
            } catch (_: SocketTimeoutException) {
                throw EngineUnreachableException("O motor demorou a responder. Tente de novo.", OfflineReason.EngineStopped)
            } finally {
                conn.disconnect()
            }
        }

    suspend fun health() = request("/v1/health")
    suspend fun capabilities() = request("/v1/capabilities")
    suspend fun jobs() = request("/v1/jobs")
    suspend fun events(since: Long) = request("/v1/events?since=" + since, readTimeoutMs = 4000)
    suspend fun route(text: String, workspace: String = "default"): JSONObject =
        request("/v1/route", "POST", JSONObject().put("text", text).put("workspace", workspace))
    suspend fun submit(command: JSONObject, requestId: String = UUID.randomUUID().toString()): JSONObject =
        request("/v1/jobs", "POST", JSONObject().put("request_id", requestId).put("request", command))
    suspend fun job(id: String) = request("/v1/jobs/" + segment(id))
    suspend fun approve(id: String) = request("/v1/jobs/" + segment(id) + "/approve", "POST")
    suspend fun cancel(id: String) = request("/v1/jobs/" + segment(id) + "/cancel", "POST")
    /** Login contract: state only; tokens/cookies never cross this boundary. */
    suspend fun authStatus() = request("/v1/auth/status")
    suspend fun startAuth(provider: String) =
        request("/v1/auth/start", "POST", JSONObject().put("provider", segment(provider)),
            readTimeoutMs = 8000)

    /** Política, modelos e gastos: leituras curtas; PUT um pouco mais folgado (o motor grava em disco). */
    suspend fun policy() = request("/v1/policy", readTimeoutMs = 5000)
    suspend fun putPolicy(doc: JSONObject) = request("/v1/policy", "PUT", doc, readTimeoutMs = 8000)
    suspend fun models() = request("/v1/models", readTimeoutMs = 5000)
    suspend fun putModels(doc: JSONObject) = request("/v1/models", "PUT", doc, readTimeoutMs = 8000)
    suspend fun spend() = request("/v1/spend", readTimeoutMs = 5000)
}

/** code = error.code do contrato, quando o motor enviou (nunca contém token). */
class AuraEngineException(val httpStatus: Int, message: String, val code: String? = null) : Exception(message)
