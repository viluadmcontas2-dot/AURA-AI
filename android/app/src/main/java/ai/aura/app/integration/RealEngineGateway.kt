package ai.aura.app.integration

import ai.aura.app.ui.engine.Capability
import ai.aura.app.ui.engine.EngineEvent
import ai.aura.app.ui.engine.EngineGateway
import ai.aura.app.ui.engine.Job
import ai.aura.app.ui.engine.JobRequest
import ai.aura.app.ui.engine.JobRisk
import ai.aura.app.ui.engine.JobStatus
import ai.aura.app.ui.engine.RouteResult
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap

/** Production EngineGateway. Never substitutes FakeEngineGateway for a missing Termux process. */
class RealEngineGateway(private val client: AuraEngineClient) : EngineGateway {
    private val routedRequests = ConcurrentHashMap<String, String>()
    private fun key(request: JobRequest): String =
        request.kind + "\u0000" + request.workspace + "\u0000" + request.prompt

    override suspend fun jobs(): List<Job> {
        val items = client.jobs().getJSONArray("jobs")
        return List(items.length()) { index -> asJob(items.getJSONObject(index)) }
    }

    override suspend fun approve(id: String): Job = asJob(client.approve(id))
    override suspend fun cancel(id: String): Job = asJob(client.cancel(id))

    override suspend fun route(text: String): RouteResult {
        val response = client.route(text)
        val raw = response.getJSONObject("request")
        val command = JobRequest(
            kind = raw.getString("kind"),
            prompt = text,
            workspace = raw.optString("workspace", "default")
        )
        // Retain the exact structured command. For ADB/files, "kind + prompt" is not enough.
        routedRequests[key(command)] = raw.toString()
        val needed = response.optBoolean("requires_model", true)
        return RouteResult(
            request = command,
            reason = response.optString("reason", "Pedido interpretado pelo motor"),
            confidence = response.optDouble("confidence", 0.0).coerceIn(0.0, 1.0),
            requiresModel = needed,
            risk = predictedRisk(raw)
        )
    }

    override suspend fun submit(requestId: String, request: JobRequest): Job {
        // If the Activity was recreated, route again instead of losing critical action fields.
        val raw = routedRequests.remove(key(request))?.let(::JSONObject)
            ?: client.route(request.prompt, request.workspace).getJSONObject("request")
        require(raw.getString("kind") == request.kind) {
            "O executor mudou. Refaça a interpretação antes de continuar."
        }
        return asJob(client.submit(raw, requestId))
    }

    override suspend fun capabilities(): List<Capability> {
        val data = client.capabilities()
        val providers = data.optJSONObject("providers") ?: JSONObject()
        val result = mutableListOf(Capability("termux", "Termux", true, "Motor local alcançável"))
        val ids = mapOf(
            "claude" to ("claude_cli" to "Claude Code"),
            "gemini" to ("gemini_cli" to "Gemini CLI"),
            "agy" to ("agy_cli" to "Antigravity CLI"),
            "adb" to ("adb" to "Android ADB"),
            "gh" to ("github" to "GitHub"),
            "git" to ("git" to "Git"),
            "rclone" to ("drive" to "Drive / rclone"),
            "python" to ("system" to "Python")
        )
        for ((providerName, pair) in ids) {
            val installed = providers.optJSONObject(providerName)?.optBoolean("installed") == true
            result += Capability(pair.first, pair.second, installed,
                if (installed) "Instalado; autenticação não verificada" else "Instalação pendente")
        }
        result += Capability("skills", "Agent Skills", data.optInt("skills", 0) > 0,
            "Capacidades locais")
        return result
    }

    override val events: Flow<EngineEvent> = flow {
        var cursor = 0L
        while (true) {
            val events = client.events(cursor).getJSONArray("events")
            for (i in 0 until events.length()) {
                val event = events.getJSONObject(i)
                val id = event.getLong("id")
                if (id <= cursor) continue
                val payload = event.optJSONObject("payload") ?: JSONObject()
                val map = mutableMapOf<String, String>()
                val fields = payload.keys()
                while (fields.hasNext()) {
                    val field = fields.next()
                    map[field] = payload.optString(field)
                }
                emit(EngineEvent(id = id, type = event.getString("type"),
                    jobId = payload.optString("id").ifBlank { null },
                    payload = map,
                    timestampMillis = (event.optDouble("created_at", 0.0) * 1000).toLong()))
                cursor = id
            }
            delay(1200)
        }
    }

    private fun predictedRisk(raw: JSONObject): JobRisk {
        val kind = raw.optString("kind")
        val action = raw.optString("action")
        return when {
            kind == "system" && action in listOf("health", "capabilities", "pwd", "doctor") -> JobRisk.Read
            kind == "files" && action in listOf("read", "list") -> JobRisk.Read
            kind == "adb" && action in listOf("devices", "getprop", "screenshot") -> JobRisk.Read
            kind == "github" && action in listOf("status", "diff", "list") -> JobRisk.Read
            kind == "drive" && action == "list" -> JobRisk.Read
            kind == "skills" && action in listOf("list", "read") -> JobRisk.Read
            kind in listOf("claude_api", "gemini_api") -> JobRisk.Spend
            kind in listOf("claude_cli", "gemini_cli", "agy_cli") -> JobRisk.Write
            else -> JobRisk.Critical
        }
    }

    private fun asJob(raw: JSONObject): Job {
        val request = raw.optJSONObject("request") ?: JSONObject()
        val status = JobStatus.fromWire(raw.getString("status"))
            ?: error("Estado desconhecido retornado pelo AURA Engine")
        val risk = JobRisk.fromWire(raw.getString("risk"))
            ?: error("Risco desconhecido retornado pelo AURA Engine")
        val summary = request.optString("prompt")
            .ifBlank { request.optString("action", "Tarefa local") }
        val outcome = raw.optJSONObject("result")
        val resultText = outcome?.let {
            it.optString("text").ifBlank {
                it.optString("stdout").ifBlank { it.toString() }
            }
        }
        return Job(
            id = raw.getString("id"),
            requestId = raw.optString("request_id"),
            title = summary.take(100),
            kind = request.optString("kind", "system"),
            status = status,
            risk = risk,
            createdAtMillis = (raw.optDouble("created_at", 0.0) * 1000).toLong(),
            result = resultText?.take(5000),
            errorCode = raw.optJSONObject("error")?.optString("code")
        )
    }
}
