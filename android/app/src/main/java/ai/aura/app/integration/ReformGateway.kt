package ai.aura.app.integration

import org.json.JSONObject
import kotlin.math.roundToInt

/**
 * Ponte do app com as rotas de reforma do motor (docs/REFORMAR_CONTRATO_LEDGER.md §5).
 *
 * Usa o MESMO cliente HTTP autenticado do resto do app ([AuraEngineClient.request]: token do cofre, 127.0.0.1,
 * erros do motor viram [AuraEngineException]; motor parado vira EngineUnreachableException). Nada de segundo cliente.
 * Os números (Reversão, Reconstrução, Fidelidade) vêm prontos do ledger do motor: o app nunca calcula nem estima;
 * `null` quer dizer "ainda não medido".
 */
interface ReformGateway {
    /** POST /v1/reform: trava o escopo sobre um APK que o Termux consiga ler (caminho de arquivo, não content://). */
    suspend fun create(apkPath: String, mode: String, goalPercent: Int, carMode: Boolean = false): ReformCreated

    /** POST /v1/reform/{id}/steps/{step}. [idempotencyKey] igual devolve o resultado anterior (retomada). */
    suspend fun runStep(id: String, step: ReformStep, idempotencyKey: String = "default"): ReformStepResult

    /** GET /v1/reform/{id}: medidores calculados do ledger. */
    suspend fun summary(id: String): ReformSummary

    /** GET /v1/reform/{id}/ledger?since=seq. */
    suspend fun ledger(id: String, since: Long = 0): ReformLedgerPage

    /** POST /v1/reform/{id}/verify: grava `fidelity_measured` a partir de um teste real. */
    suspend fun verify(id: String, testRunId: String, passed: Int, total: Int): ReformSummaryMeter?

    /** POST /v1/network: o motor só baixa pacote em rede não medida. */
    suspend fun reportNetwork(metered: Boolean, transport: String)
}

/** Passos aceitos pelo motor, na ordem do contrato. [wire] é o trecho da URL. */
enum class ReformStep(val wire: String) {
    Tools("tools"), Inventory("inventory"), Coverage("coverage"), Rebuild("rebuild"), Model("model"),
}

data class ReformCreated(val id: String, val apkPath: String?, val mode: String?)

/** Aviso de download grande (>100 MB). Informativo: nunca bloqueia. */
data class ReformDownloadNotice(val step: String, val mb: Int)

/**
 * Resultado de um passo. [waitingWifi] = o motor guardou onde parou porque a rede é medida (dados móveis): não é erro,
 * rodar de novo com Wi-Fi retoma. [doneSteps] = ferramentas já instaladas (checkpoint do motor).
 */
data class ReformStepResult(
    val step: String,
    val alreadyDone: Boolean,
    val seq: Long?,
    val waitingWifi: Boolean,
    val waitingStep: String?,
    val doneSteps: List<String>,
    val notices: List<ReformDownloadNotice>,
    val message: String?,
)

/** [pct] 0..100 já calculado pelo motor; [seq] aponta a entrada do ledger que sustenta o número. */
data class ReformSummaryMeter(val pct: Int?, val seq: Long?)

data class ReformSummary(
    val id: String,
    /** false = cadeia adulterada: o motor não afirma nenhum número. */
    val chainOk: Boolean,
    val apkPath: String?,
    val mode: String?,
    val goalPercent: Int?,
    val reversal: ReformSummaryMeter?,
    val rebuild: ReformSummaryMeter?,
    /** null enquanto não houver teste real (`fidelity_measured`). */
    val fidelity: ReformSummaryMeter?,
    val stepsDone: List<String>,
    val modelCalls: Int,
)

data class ReformLedgerEntry(val seq: Long, val kind: String, val payload: JSONObject)

data class ReformLedgerPage(val entries: List<ReformLedgerEntry>, val lastSeq: Long)

/** O que o gateway pede ao HTTP. [AuraEngineClient] cumpre; os testes usam um falso. */
fun interface ReformTransport {
    suspend fun request(path: String, method: String, body: JSONObject?, readTimeoutMs: Int): JSONObject
}

/** Liga o transporte ao cliente autenticado que o app já usa. */
fun AuraEngineClient.asReformTransport(): ReformTransport =
    ReformTransport { path, method, body, timeout -> request(path, method, body, timeout) }

class RealReformGateway(private val http: ReformTransport) : ReformGateway {

    override suspend fun create(apkPath: String, mode: String, goalPercent: Int, carMode: Boolean): ReformCreated {
        val body = JSONObject().put("apk_path", apkPath).put("mode", mode).put("goal", goalPercent)
        // car_mode: o motor de hoje ignora o campo extra; fica registrado no pedido para quando ele ler.
        if (carMode) body.put("car_mode", true)
        val reply = http.request("/v1/reform", "POST", body, SHORT_MS)
        val scope = reply.optJSONObject("scope")?.optJSONObject("payload") ?: reply.optJSONObject("scope")
        return ReformCreated(
            id = reply.getString("id"),
            apkPath = scope?.optString("apk_path")?.ifBlank { null },
            mode = scope?.optString("mode")?.ifBlank { null },
        )
    }

    override suspend fun runStep(id: String, step: ReformStep, idempotencyKey: String): ReformStepResult {
        require(SEGMENT.matches(id)) { "Invalid identifier" }
        val body = JSONObject().put("idempotency_key", idempotencyKey)
        // A instalação pode levar muitos minutos (pkg, JDK, npm): leitura longa. O motor responde só ao terminar.
        val timeout = if (step == ReformStep.Tools) INSTALL_MS else STEP_MS
        val reply = http.request("/v1/reform/$id/steps/${step.wire}", "POST", body, timeout)
        val result = reply.optJSONObject("result") ?: JSONObject()
        val state = result.optString("state")
        return ReformStepResult(
            step = reply.optString("step", step.wire),
            alreadyDone = reply.optBoolean("already_done", false),
            seq = if (reply.isNull("seq")) null else reply.optLong("seq"),
            waitingWifi = state == "waiting_wifi",
            waitingStep = result.optString("waiting_step").ifBlank { null },
            doneSteps = strings(result.optJSONArray("steps")),
            notices = notices(result.optJSONArray("download_notices")),
            message = result.optString("message").ifBlank { null },
        )
    }

    override suspend fun summary(id: String): ReformSummary {
        require(SEGMENT.matches(id)) { "Invalid identifier" }
        val reply = http.request("/v1/reform/$id", "GET", null, SHORT_MS)
        val scope = reply.optJSONObject("scope")
        return ReformSummary(
            id = reply.optString("id", id),
            chainOk = reply.optBoolean("chain_ok", true),
            apkPath = scope?.optString("apk_path")?.ifBlank { null },
            mode = scope?.optString("mode")?.ifBlank { null },
            goalPercent = scope?.takeIf { it.has("goal") && !it.isNull("goal") }?.optDouble("goal")?.roundToInt(),
            reversal = meter(reply.optJSONObject("reversal")),
            rebuild = meter(reply.optJSONObject("rebuild")),
            fidelity = meter(reply.optJSONObject("fidelity")),
            stepsDone = strings(reply.optJSONArray("steps_done")),
            modelCalls = reply.optJSONArray("model_calls")?.length() ?: 0,
        )
    }

    override suspend fun ledger(id: String, since: Long): ReformLedgerPage {
        require(SEGMENT.matches(id)) { "Invalid identifier" }
        require(since >= 0) { "since deve ser zero ou mais" }
        val reply = http.request("/v1/reform/$id/ledger?since=$since", "GET", null, SHORT_MS)
        val array = reply.optJSONArray("entries")
        val entries = (0 until (array?.length() ?: 0)).mapNotNull { i ->
            val e = array?.optJSONObject(i) ?: return@mapNotNull null
            ReformLedgerEntry(e.optLong("seq"), e.optString("kind"), e.optJSONObject("payload") ?: JSONObject())
        }
        return ReformLedgerPage(entries, reply.optLong("last_seq", since))
    }

    override suspend fun verify(id: String, testRunId: String, passed: Int, total: Int): ReformSummaryMeter? {
        require(SEGMENT.matches(id)) { "Invalid identifier" }
        val body = JSONObject().put("test_run_id", testRunId).put("passed", passed).put("total", total)
        return meter(http.request("/v1/reform/$id/verify", "POST", body, SHORT_MS).optJSONObject("fidelity"))
    }

    override suspend fun reportNetwork(metered: Boolean, transport: String) {
        http.request("/v1/network", "POST", JSONObject().put("metered", metered).put("transport", transport), SHORT_MS)
    }

    private fun meter(json: JSONObject?): ReformSummaryMeter? {
        json ?: return null
        val pct = if (json.isNull("pct") || !json.has("pct")) null else json.optDouble("pct").takeIf { it.isFinite() }?.roundToInt()
        return ReformSummaryMeter(pct?.takeIf { it in 0..100 }, if (json.has("seq")) json.optLong("seq") else null)
    }

    private fun strings(array: org.json.JSONArray?): List<String> =
        (0 until (array?.length() ?: 0)).mapNotNull { array?.optString(it)?.takeIf { s -> s.isNotBlank() } }

    private fun notices(array: org.json.JSONArray?): List<ReformDownloadNotice> =
        (0 until (array?.length() ?: 0)).mapNotNull { i ->
            val n = array?.optJSONObject(i) ?: return@mapNotNull null
            val mb = n.optDouble("mb").takeIf { it.isFinite() && it > 0 }?.roundToInt() ?: return@mapNotNull null
            ReformDownloadNotice(n.optString("step"), mb)
        }

    private companion object {
        const val SHORT_MS = 8_000
        const val STEP_MS = 120_000
        const val INSTALL_MS = 1_800_000
    }
}
