package ai.aura.app.integration

import ai.aura.app.offline.EngineUnreachableException
import ai.aura.app.offline.OfflineReason
import org.json.JSONObject

/** Quanto o AURA pergunta antes de agir. Padrão do motor: [Total]. */
enum class Autonomy(val wire: String, val label: String, val hint: String) {
    Total("total", "Total (padrão)", "Faz tudo sozinho, sem perguntar."),
    AskDestructive("ask_destructive", "Perguntar antes de apagar", "Faz sozinho; pede o seu ok só para apagar."),
    AskAll("ask_all", "Perguntar tudo", "Pede o seu ok antes de cada ação.");

    companion object {
        fun fromWire(value: String?): Autonomy = entries.firstOrNull { it.wire == value } ?: Total
    }
}

/**
 * Ajustes simples do motor: autonomia e trava de custo (GET/POST /v1/settings/autonomy e /cost_lock).
 * Falha com exceção quando o motor não responde; quem chama decide o que mostrar.
 */
interface SettingsGateway {
    suspend fun autonomy(): Autonomy
    suspend fun setAutonomy(value: Autonomy): Autonomy
    suspend fun costLock(): Boolean
    suspend fun setCostLock(on: Boolean): Boolean
}

/** Usa o mesmo cliente HTTP autenticado do restante do app ([AuraEngineClient.request]). */
class RealSettingsGateway(
    private val request: suspend (path: String, method: String, body: JSONObject?) -> JSONObject,
) : SettingsGateway {
    constructor(client: AuraEngineClient) : this({ path, method, body ->
        client.request(path, method, body, readTimeoutMs = 5000)
    })

    override suspend fun autonomy() = Autonomy.fromWire(request(AUTONOMY, "GET", null).optString("autonomy"))
    override suspend fun setAutonomy(value: Autonomy) =
        Autonomy.fromWire(request(AUTONOMY, "POST", JSONObject().put("autonomy", value.wire)).optString("autonomy"))

    override suspend fun costLock() = request(COST_LOCK, "GET", null).optBoolean("cost_lock", true)
    override suspend fun setCostLock(on: Boolean) =
        request(COST_LOCK, "POST", JSONObject().put("cost_lock", on)).optBoolean("cost_lock", on)

    private companion object {
        const val AUTONOMY = "/v1/settings/autonomy"
        const val COST_LOCK = "/v1/settings/cost_lock"
    }
}

/** Falso para testes e prévias: guarda em memória; [down] simula o motor desligado. */
class FakeSettingsGateway(
    private var autonomy: Autonomy = Autonomy.Total,
    private var costLock: Boolean = true,
) : SettingsGateway {
    var down = false
    private fun check() {
        if (down) throw EngineUnreachableException("Termux não responde. Abra o Termux e inicie o motor.", OfflineReason.EngineStopped)
    }

    override suspend fun autonomy(): Autonomy { check(); return autonomy }
    override suspend fun setAutonomy(value: Autonomy): Autonomy { check(); autonomy = value; return autonomy }
    override suspend fun costLock(): Boolean { check(); return costLock }
    override suspend fun setCostLock(on: Boolean): Boolean { check(); costLock = on; return costLock }
}

/** Sem motor ligado (padrão do AuraApp em prévias): tudo falha como motor parado e os Ajustes usam o valor local. */
object NoEngineSettingsGateway : SettingsGateway {
    private fun down(): Nothing =
        throw EngineUnreachableException("Termux não responde. Abra o Termux e inicie o motor.", OfflineReason.EngineStopped)

    override suspend fun autonomy() = down()
    override suspend fun setAutonomy(value: Autonomy) = down()
    override suspend fun costLock() = down()
    override suspend fun setCostLock(on: Boolean) = down()
}
