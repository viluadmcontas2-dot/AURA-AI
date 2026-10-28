package ai.aura.app.integration

import ai.aura.app.offline.EngineUnreachableException
import ai.aura.app.offline.OfflineReason
import org.json.JSONObject

/** Fila do motor (GET /v1/queue): o que roda, o que espera e o que ficou salvo para retomar. */
data class QueueSnapshot(val runningTitle: String?, val queued: List<String>, val saved: List<String>)

/** Resposta de POST /v1/inputs: o motor classifica o pedido falado. */
data class VoiceInput(val id: String, val kind: String, val clean: String)

interface QueueGateway {
    suspend fun queue(): QueueSnapshot
    suspend fun receive(text: String, source: String): VoiceInput

    /** [mode]: later, merge ou now (now exige [confirm]). */
    suspend fun apply(taskId: String, mode: String, confirm: Boolean)
}

class RealQueueGateway(
    private val request: suspend (path: String, method: String, body: JSONObject?) -> JSONObject,
) : QueueGateway {
    constructor(client: AuraEngineClient) : this({ path, method, body -> client.request(path, method, body, readTimeoutMs = 8000) })

    override suspend fun queue(): QueueSnapshot {
        val doc = request("/v1/queue", "GET", null)
        val queued = doc.optJSONArray("queued")
        val saved = doc.optJSONArray("saved")
        return QueueSnapshot(
            runningTitle = doc.optJSONObject("running")?.optString("title")?.ifBlank { "Trabalho atual" },
            queued = (0 until (queued?.length() ?: 0)).mapNotNull { queued!!.optJSONObject(it)?.optString("text") },
            saved = (0 until (saved?.length() ?: 0)).mapNotNull { saved!!.optJSONObject(it)?.optString("title") },
        )
    }

    override suspend fun receive(text: String, source: String): VoiceInput {
        val doc = request("/v1/inputs", "POST", JSONObject().put("text", text).put("source", source))
        require(SEGMENT.matches(doc.optString("id"))) { "Resposta sem identificador" }
        return VoiceInput(doc.getString("id"), doc.optString("kind"), doc.optString("clean", text))
    }

    override suspend fun apply(taskId: String, mode: String, confirm: Boolean) {
        require(SEGMENT.matches(taskId)) { "Invalid identifier" }
        request("/v1/inputs/$taskId/apply", "POST", JSONObject().put("mode", mode).put("confirm", confirm))
    }
}

/** Falso para testes e prévias. [received] guarda o que foi enviado; [down] simula motor desligado. */
class FakeQueueGateway(
    private val snapshot: QueueSnapshot,
    private val input: VoiceInput = VoiceInput("fake", "new_task", ""),
) : QueueGateway {
    var down = false
    val received = mutableListOf<String>()
    val applied = mutableListOf<Triple<String, String, Boolean>>()
    private fun check() {
        if (down) throw EngineUnreachableException("Termux não responde. Abra o Termux e inicie o motor.", OfflineReason.EngineStopped)
    }

    override suspend fun queue(): QueueSnapshot { check(); return snapshot }
    override suspend fun receive(text: String, source: String): VoiceInput { check(); received += text; return input }
    override suspend fun apply(taskId: String, mode: String, confirm: Boolean) { check(); applied += Triple(taskId, mode, confirm) }
}

/** Padrão sem motor ligado: tudo falha como motor parado. */
object NoEngineQueueGateway : QueueGateway {
    private fun down(): Nothing =
        throw EngineUnreachableException("Termux não responde. Abra o Termux e inicie o motor.", OfflineReason.EngineStopped)

    override suspend fun queue() = down()
    override suspend fun receive(text: String, source: String) = down()
    override suspend fun apply(taskId: String, mode: String, confirm: Boolean) = down()
}
