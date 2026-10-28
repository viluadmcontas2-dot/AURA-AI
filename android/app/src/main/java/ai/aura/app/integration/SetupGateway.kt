package ai.aura.app.integration

import ai.aura.app.offline.EngineUnreachableException
import ai.aura.app.offline.NetworkKind
import kotlinx.coroutines.CancellationException
import org.json.JSONObject
import java.time.Instant
import java.util.UUID

/** Como o tamanho do download foi obtido. [Unknown] bloqueia a instalação (docs/FLUXOS_DE_USO.md, seção 7). */
enum class SizeKind(val wire: String) {
    Exact("exact"), UpperBound("upper_bound"), Unknown("unknown");

    companion object {
        fun fromWire(value: String?): SizeKind = entries.firstOrNull { it.wire == value } ?: Unknown
    }
}

/** Um programa do manifesto. Bytes `null` = o motor ainda não sabe. */
data class InstallItem(
    val name: String,
    val version: String? = null,
    val source: String = "",
    val downloadBytes: Long? = null,
    val installedBytes: Long? = null,
    val checksum: String? = null,
    val includes: List<String> = emptyList(),
)

/**
 * Plano de instalação (C4): imutável. A decisão da pessoa vale só para este [manifestHash]; qualquer mudança
 * de conteúdo ou de tamanho gera outro plano e outra decisão.
 * [local] = plano empacotado no app, feito antes de existir motor (mesmo formato e mesma validade).
 */
data class InstallPlan(
    val planId: String,
    val manifestHash: String,
    val target: String,
    val reason: String,
    val items: List<InstallItem>,
    val downloadBytes: Long?,
    val installedBytes: Long?,
    val sizeKind: SizeKind,
    val expiresAtMillis: Long,
    val network: NetworkKind = NetworkKind.Unknown,
    val resourceId: String? = null,
    val local: Boolean = false,
    /** Estado que o motor informou: awaiting_consent, size_unknown, installing, completed, failed... */
    val state: String = "awaiting_consent",
    val errorCode: String? = null,
    val errorMessage: String? = null,
    val progressItem: String? = null,
) {
    /** Só um tamanho conhecido (exato ou limite superior) deixa a pessoa decidir. */
    val sizeKnown: Boolean get() = sizeKind != SizeKind.Unknown && downloadBytes != null
    val isFinal: Boolean get() = state in FINAL_STATES

    companion object {
        val FINAL_STATES = setOf("completed", "failed", "cancelled", "declined", "expired", "not_needed")
    }
}

sealed interface PlanResult {
    data class Ok(val plan: InstallPlan) : PlanResult

    /** Já está instalado: não há o que baixar nem o que perguntar. */
    data object NotNeeded : PlanResult
    data class Failed(val code: String, val message: String) : PlanResult
    data class Unavailable(val message: String) : PlanResult
}

sealed interface DecisionResult {
    /** `installing` (202), `declined` ou outro estado devolvido pelo motor. */
    data class Done(val state: String) : DecisionResult

    /** O motor recusou sem executar: plan_changed, plan_expired, size_unknown, consent_required... */
    data class Rejected(val code: String, val message: String) : DecisionResult
    data class Unavailable(val message: String) : DecisionResult
}

/** C3: o que o motor sabe do assistente, só leitura. Estados locais (Termux, vínculo) ficam fora deste JSON. */
data class SetupStatus(
    val claudeInstallation: String,
    val claudeAuth: String,
    val nextAction: String,
)

sealed interface StatusResult {
    data class Ok(val status: SetupStatus) : StatusResult
    data class Unavailable(val message: String) : StatusResult
}

/**
 * Ponte do app com o preparo do assistente: estado (C3) e instalação com decisão ligada ao plano (C4).
 * Preparar um plano NÃO instala nada; só [decide] com `install` pode iniciar uma instalação.
 */
interface SetupGateway {
    suspend fun status(): StatusResult
    suspend fun createPlan(target: String, reason: String, resourceId: String? = null, variant: String? = null): PlanResult
    suspend fun measure(planId: String): PlanResult
    suspend fun decide(plan: InstallPlan, install: Boolean): DecisionResult
    suspend fun plan(planId: String): PlanResult
    suspend fun cancel(planId: String): DecisionResult
}

/** Sem motor: toda chamada diz a verdade (indisponível) e nada é instalado. */
object NoEngineSetupGateway : SetupGateway {
    private const val MSG = "O motor não respondeu. Inicie o motor e tente de novo."
    override suspend fun status() = StatusResult.Unavailable(MSG)
    override suspend fun createPlan(target: String, reason: String, resourceId: String?, variant: String?) =
        PlanResult.Unavailable(MSG)
    override suspend fun measure(planId: String) = PlanResult.Unavailable(MSG)
    override suspend fun decide(plan: InstallPlan, install: Boolean) = DecisionResult.Unavailable(MSG)
    override suspend fun plan(planId: String) = PlanResult.Unavailable(MSG)
    override suspend fun cancel(planId: String) = DecisionResult.Unavailable(MSG)
}

/** Mesmo cliente HTTP autenticado do restante do app; o motor decide, o app só mostra e pergunta. */
class RealSetupGateway(
    private val request: suspend (path: String, method: String, body: JSONObject?) -> JSONObject,
    private val newId: () -> String = { UUID.randomUUID().toString() },
) : SetupGateway {
    constructor(client: AuraEngineClient) : this({ path, method, body ->
        client.request(path, method, body, readTimeoutMs = 20000)
    })

    override suspend fun status(): StatusResult = try {
        val raw = request("/v1/setup/status", "GET", null)
        val claude = raw.optJSONObject("claude") ?: JSONObject()
        StatusResult.Ok(
            SetupStatus(
                claude.optString("installation", "missing"), claude.optString("auth", "unknown"),
                raw.optString("next_action", "check_again"),
            ),
        )
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        StatusResult.Unavailable(unavailable(e))
    }

    override suspend fun createPlan(target: String, reason: String, resourceId: String?, variant: String?): PlanResult =
        planCall("/v1/install-plans", "POST", JSONObject().put("request_id", newId()).put("target", target)
            .put("reason", reason).also { b ->
                resourceId?.let { b.put("resource_id", it) }
                variant?.let { b.put("variant", it) }
            })

    override suspend fun measure(planId: String): PlanResult =
        planCall("/v1/install-plans/" + safe(planId) + "/measure", "POST", JSONObject())

    override suspend fun plan(planId: String): PlanResult = planCall("/v1/install-plans/" + safe(planId), "GET", null)

    override suspend fun decide(plan: InstallPlan, install: Boolean): DecisionResult = try {
        val raw = request(
            "/v1/install-plans/" + safe(plan.planId) + "/decision", "POST",
            JSONObject().put("request_id", newId()).put("manifest_hash", plan.manifestHash)
                .put("decision", if (install) "install" else "not_now"),
        )
        DecisionResult.Done(raw.optString("state", "installing"))
    } catch (e: CancellationException) {
        throw e
    } catch (e: AuraEngineException) {
        DecisionResult.Rejected(e.code ?: "rejected", e.message.orEmpty())
    } catch (e: Exception) {
        DecisionResult.Unavailable(unavailable(e))
    }

    override suspend fun cancel(planId: String): DecisionResult = try {
        val raw = request("/v1/install-plans/" + safe(planId) + "/cancel", "POST", JSONObject().put("request_id", newId()))
        DecisionResult.Done(raw.optString("state", "cancelling"))
    } catch (e: CancellationException) {
        throw e
    } catch (e: AuraEngineException) {
        DecisionResult.Rejected(e.code ?: "rejected", e.message.orEmpty())
    } catch (e: Exception) {
        DecisionResult.Unavailable(unavailable(e))
    }

    private suspend fun planCall(path: String, method: String, body: JSONObject?): PlanResult = try {
        val plan = parsePlan(request(path, method, body))
        if (plan.state == "not_needed") PlanResult.NotNeeded else PlanResult.Ok(plan)
    } catch (e: CancellationException) {
        throw e
    } catch (e: AuraEngineException) {
        PlanResult.Failed(e.code ?: "rejected", e.message.orEmpty())
    } catch (e: Exception) {
        PlanResult.Unavailable(unavailable(e))
    }

    private fun safe(id: String): String {
        require(SEGMENT.matches(id)) { "Invalid identifier" }
        return id
    }

    private fun unavailable(e: Exception): String =
        if (e is EngineUnreachableException || e is java.io.IOException) e.message ?: "O motor não respondeu."
        else "O motor respondeu algo inesperado."

    companion object {
        fun parsePlan(raw: JSONObject): InstallPlan {
            val items = raw.optJSONArray("items")?.let { arr ->
                (0 until arr.length()).mapNotNull { arr.optJSONObject(it) }.map { i ->
                    InstallItem(
                        name = i.optString("name"),
                        version = i.optString("version").ifBlank { null },
                        source = i.optString("source"),
                        downloadBytes = if (i.isNull("download_bytes")) null else i.optLong("download_bytes"),
                        installedBytes = if (i.isNull("installed_bytes")) null else i.optLong("installed_bytes"),
                        checksum = i.optString("checksum").ifBlank { null },
                        includes = i.optJSONArray("includes")?.let { inc -> (0 until inc.length()).map { inc.optString(it) } }
                            ?: emptyList(),
                    )
                }
            } ?: emptyList()
            val error = raw.optJSONObject("error")
            val progress = raw.optJSONObject("progress")
            return InstallPlan(
                planId = raw.getString("plan_id"),
                manifestHash = raw.optString("manifest_hash"),
                target = raw.optString("target"),
                reason = raw.optString("reason"),
                items = items,
                downloadBytes = if (raw.isNull("download_bytes")) null else raw.optLong("download_bytes"),
                installedBytes = if (raw.isNull("installed_bytes")) null else raw.optLong("installed_bytes"),
                sizeKind = SizeKind.fromWire(raw.optString("size_kind")),
                expiresAtMillis = runCatching { Instant.parse(raw.optString("expires_at")).toEpochMilli() }.getOrDefault(0L),
                network = when (raw.optString("network")) {
                    "wifi" -> NetworkKind.Wifi
                    "mobile" -> NetworkKind.Mobile
                    else -> NetworkKind.Unknown
                },
                resourceId = raw.optString("resource_id").ifBlank { null },
                state = raw.optString("state", "awaiting_consent"),
                errorCode = error?.optString("code")?.ifBlank { null },
                errorMessage = error?.optString("message_pt")?.ifBlank { null },
                progressItem = progress?.optString("item")?.ifBlank { null },
            )
        }
    }
}
