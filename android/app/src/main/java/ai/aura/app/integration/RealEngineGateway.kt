package ai.aura.app.integration

import ai.aura.app.offline.EngineUnreachableException
import ai.aura.app.offline.OfflineReason
import ai.aura.app.ui.engine.AUTH_PROVIDER_IDS
import ai.aura.app.ui.engine.AuthStartResult
import ai.aura.app.ui.engine.AuthState
import ai.aura.app.ui.engine.AuthStatus
import ai.aura.app.ui.engine.Capability
import ai.aura.app.ui.engine.EngineEvent
import ai.aura.app.ui.engine.EngineGateway
import ai.aura.app.ui.engine.EngineHealth
import ai.aura.app.ui.engine.EngineResult
import ai.aura.app.ui.engine.EngineUsage
import ai.aura.app.ui.engine.Job
import ai.aura.app.ui.engine.JobRequest
import ai.aura.app.ui.engine.JobRisk
import ai.aura.app.ui.engine.JobStatus
import ai.aura.app.ui.engine.MemoryEntry
import ai.aura.app.ui.engine.ModelsDoc
import ai.aura.app.ui.engine.ModelsResult
import ai.aura.app.ui.engine.PolicyDoc
import ai.aura.app.ui.engine.PolicyResult
import ai.aura.app.ui.engine.RouteResult
import ai.aura.app.ui.engine.SkillInfo
import ai.aura.app.ui.engine.SpendResult
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.shareIn
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import java.util.Collections
import java.util.concurrent.atomic.AtomicLong

/** Production EngineGateway. Never substitutes a fake for a missing Termux process. */
class RealEngineGateway(
    private val client: EngineApi,
    /** Escopo do polling de eventos: sem assinantes ele para (WhileSubscribed), então nada fica vivo. */
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
) : EngineGateway {
    // Só os últimos MAX_ROUTED comandos interpretados ficam guardados; o mais velho sai primeiro.
    private val routedRequests = Collections.synchronizedMap(object : LinkedHashMap<String, String>() {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, String>) = size > MAX_ROUTED
    })
    private val syntheticIds = AtomicLong(0)
    private fun key(request: JobRequest): String =
        request.kind + "\u0000" + request.workspace + "\u0000" + request.prompt

    override suspend fun jobs(): List<Job> {
        val items = client.jobs().getJSONArray("jobs")
        // Um job malformado é descartado; a lista inteira não se perde por causa dele.
        return (0 until items.length()).mapNotNull { runCatching { asJob(items.getJSONObject(it)) }.getOrNull() }
    }

    override suspend fun approve(id: String): Job = asJob(client.approve(id))
    override suspend fun cancel(id: String): Job = asJob(client.cancel(id))

    override suspend fun route(text: String): RouteResult = route(text, "default")

    override suspend fun route(text: String, workspace: String): RouteResult {
        val response = client.route(text, workspace)
        val raw = response.getJSONObject("request")
        val command = JobRequest(
            kind = raw.getString("kind"),
            prompt = text,
            workspace = raw.optString("workspace", workspace)
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

    // ---- Política, modelos e gastos ------------------------------------------------------

    /** 404/501: este motor ainda não expõe o endpoint. Nada é simulado. */
    private fun notExposed(error: Exception): Boolean =
        error is AuraEngineException && (error.httpStatus == 404 || error.httpStatus == 501)

    /** 400/403/409/422: o motor entendeu e recusou; o código vem do contrato (error.code). */
    private fun refused(error: Exception): Boolean =
        error is AuraEngineException && error.httpStatus in listOf(400, 403, 409, 422)

    private fun reasonFor(error: Exception, what: String): String = when {
        notExposed(error) -> "Este AURA Engine ainda não expõe " + what + "."
        error is java.io.IOException -> error.message ?: "Termux não responde."
        error is AuraEngineException -> "O motor não conseguiu responder (" + error.httpStatus + ")."
        else -> "Resposta inesperada do motor."
    }

    override suspend fun policy(): PolicyResult = try {
        policyOk(client.policy())
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        policyFailure(e)
    }

    override suspend fun savePolicy(p: PolicyDoc): PolicyResult = try {
        val response = client.putPolicy(EnginePayloads.policyToJson(p))
        // Se o PUT só confirmou ("ok"), relê para mostrar o que o motor aplicou de fato.
        val applied = if ((response.optJSONObject("policy") ?: response).has("mode")) response else client.policy()
        policyOk(applied)
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        policyFailure(e)
    }

    private fun policyOk(raw: JSONObject): PolicyResult =
        PolicyResult.Ok(EnginePayloads.policyFromJson(raw), EnginePayloads.historyFromJson(raw))

    private fun policyFailure(e: Exception): PolicyResult =
        if (e is AuraEngineException && refused(e)) PolicyResult.Rejected(e.code ?: "rejected", e.message)
        else PolicyResult.Unavailable(reasonFor(e, "a política"))

    override suspend fun models(): ModelsResult = try {
        ModelsResult.Ok(EnginePayloads.modelsFromJson(client.models()))
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        modelsFailure(e)
    }

    override suspend fun saveModels(m: ModelsDoc): ModelsResult = try {
        val response = client.putModels(EnginePayloads.modelsToJson(m))
        val applied = if ((response.optJSONObject("models") ?: response).has("routes")) response else client.models()
        ModelsResult.Ok(EnginePayloads.modelsFromJson(applied))
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        modelsFailure(e)
    }

    private fun modelsFailure(e: Exception): ModelsResult =
        if (e is AuraEngineException && refused(e)) ModelsResult.Rejected(e.code ?: "rejected", e.message)
        else ModelsResult.Unavailable(reasonFor(e, "os modelos"))

    override suspend fun spendSummary(): SpendResult = try {
        SpendResult.Ok(SpendParser.parse(EnginePayloads.spendRows(client.spend())))
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        SpendResult.Unavailable(reasonFor(e, "o resumo de gastos"))
    }

    // ---- Login fácil ---------------------------------------------------------------------

    override suspend fun authStatus(): List<AuthStatus> {
        val known = try {
            parseAuth(client.authStatus())
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            val detail = if (notExposed(e)) "Este AURA Engine ainda não expõe login fácil."
                else if (e is java.io.IOException) (e.message ?: "Termux não responde.")
                else "Não foi possível consultar o estado desta conta."
            emptyMap<String, AuthStatus>() to detail
        }
        // Sempre uma linha por conta do contrato; o que o motor não informou fica indisponível.
        return AUTH_PROVIDER_IDS.map { id ->
            known.first[id] ?: AuthStatus(id, AuthState.Unavailable, detail = known.second)
        }
    }

    /** Lista na raiz chega como {"items": [...]} (ver AuraEngineClient). Nunca lê token/cookie. */
    private fun parseAuth(raw: JSONObject): Pair<Map<String, AuthStatus>, String?> {
        val array = raw.optJSONArray("items") ?: raw.optJSONArray("providers")
            ?: raw.optJSONArray("statuses") ?: raw.optJSONArray("accounts")
            ?: return emptyMap<String, AuthStatus>() to "O motor não informou o estado das contas."
        val map = LinkedHashMap<String, AuthStatus>()
        for (i in 0 until array.length()) {
            val item = array.optJSONObject(i) ?: continue
            val id = item.optString("provider")
            if (id.isBlank()) continue
            map[id] = AuthStatus(
                provider = id,
                state = AuthState.fromWire(item.optString("state")) ?: AuthState.Unavailable,
                installable = item.optBoolean("installable", false),
                detail = item.optString("detail").ifBlank { null }?.take(200),
            )
        }
        return map to "O motor não informou esta conta."
    }

    override suspend fun startAuth(provider: String): AuthStartResult {
        return try {
            client.startAuth(provider)
            AuthStartResult.Started
        } catch (e: CancellationException) {
            throw e
        } catch (e: IllegalArgumentException) {
            AuthStartResult.Failed("Conta desconhecida.", "Escolha outra conta.")
        } catch (e: Exception) {
            when {
                notExposed(e) -> AuthStartResult.Failed(
                    "Login fácil ainda não está disponível no motor.",
                    "Atualize o AURA Engine no Termux e tente de novo.",
                )
                e is java.io.IOException -> AuthStartResult.Failed(
                    e.message ?: "O Termux não respondeu.",
                    "Abra o Termux, inicie o motor e toque em Conectar novamente.",
                )
                else -> AuthStartResult.Failed(
                    "Não foi possível iniciar o login.",
                    "Confira o Termux e toque em Conectar novamente.",
                )
            }
        }
    }

    /** Um único polling compartilhado por todas as telas (evita N laços e tempestade de jobs()). */
    override val events: Flow<EngineEvent> = flow {
        var cursor = -1L // -1: ainda não sei; começa no último id que existe hoje
        var connected: Boolean? = null
        var failures = 0
        while (true) {
            val batch = try {
                if (cursor < 0) cursor = currentCursor()
                // Pede a partir do último id já visto: o motor devolve esse de novo, então lista vazia = banco novo.
                client.events(maxOf(cursor - 1, 0)).getJSONArray("events")
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // Termux fora do ar ou sem token: avisa uma vez e tenta de novo com espera crescente.
                failures++
                if (connected != false) emit(connection("connection.lost", e))
                connected = false
                delay(EventBackoff.delayMillis(failures))
                continue
            }
            failures = 0
            if (connected == false) emit(connection("connection.restored", null))
            connected = true
            // shortcut: se o banco novo já passou do cursor antigo, o reinício não é percebido; trocar por um id de instância do motor.
            if (batch.length() == 0 && cursor > 0) cursor = 0
            for (i in 0 until batch.length()) {
                val item = batch.optJSONObject(i) ?: continue
                val id = item.optLong("id", 0L)
                if (id <= cursor) continue
                cursor = id // o cursor sempre avança, mesmo se este evento estiver estragado
                eventFrom(item, id)?.let { emit(it) }
            }
            delay(EventBackoff.POLL_MS)
        }
    }.shareIn(scope, SharingStarted.WhileSubscribed(stopTimeoutMillis = 5000), replay = 0)

    /** Último id existente hoje: eventos antigos não são reemitidos como se fossem novos. */
    private suspend fun currentCursor(): Long {
        var cursor = 0L
        while (true) {
            val page = client.events(cursor).getJSONArray("events")
            if (page.length() == 0) return cursor
            val last = page.getJSONObject(page.length() - 1).getLong("id")
            if (last <= cursor) return cursor
            cursor = last
        }
    }

    /** Evento sintético da conexão: id negativo e único, para nunca colidir com os do motor. */
    private fun connection(type: String, cause: Exception?) = EngineEvent(
        id = -syntheticIds.incrementAndGet(),
        type = type,
        jobId = null,
        payload = if (cause == null) emptyMap() else mapOf("reason" to reasonOf(cause)),
        timestampMillis = System.currentTimeMillis(),
    )

    private fun reasonOf(e: Exception): String = when {
        e is EngineUnreachableException -> e.reason.name
        e is AuraEngineException && e.httpStatus == 401 -> OfflineReason.NoToken.name
        else -> OfflineReason.EngineStopped.name
    }

    /** null = evento estragado: é descartado e o polling segue. */
    private fun eventFrom(item: JSONObject, id: Long): EngineEvent? = try {
        val payload = item.optJSONObject("payload") ?: JSONObject()
        val fields = payload.keys().asSequence().filterNot { payload.isNull(it) }
            .associateWith { payload.get(it).toString() }
        EngineEvent(
            id = id,
            type = item.getString("type"),
            jobId = payload.text("id") ?: payload.text("job_id"),
            payload = fields,
            timestampMillis = (item.optDouble("created_at", 0.0) * 1000).toLong(),
        )
    } catch (_: JSONException) {
        null
    }

    // ---- Memória, skills, saúde e uso ----------------------------------------------------

    override suspend fun memory(): EngineResult<List<MemoryEntry>> = call("a memória") {
        client.memory(WORKSPACE).getJSONArray("entries").mapObjects { entryFrom(it) }
    }

    /** Um id fora do formato nem chega ao motor. */
    override suspend fun forgetMemory(id: String): EngineResult<Unit> {
        if (!SEGMENT.matches(id)) return EngineResult.Rejected("invalid_id", "Não encontrei essa lembrança.")
        return call("a memória", rejects = true) { client.forgetMemory(id); Unit }
    }

    /** Só o workspace que a tela mostra: sem `workspace` o motor apagaria o de todos. */
    override suspend fun forgetAllMemory(): EngineResult<Int> =
        call("a memória", rejects = true) { client.forgetAllMemory(WORKSPACE).optInt("deleted") }

    override suspend fun skills(): EngineResult<List<SkillInfo>> = call("as skills") {
        client.skills().getJSONArray("skills").mapObjects { skillFrom(it) }
    }

    override suspend fun health(): EngineResult<EngineHealth> = call("o estado do motor") {
        val raw = client.health()
        EngineHealth(raw.getString("service"), raw.optInt("version"))
    }

    override suspend fun usage(): EngineResult<EngineUsage> = call("o uso") {
        val raw = client.usage()
        EngineUsage(
            raw.optLong("input_tokens"), raw.optLong("output_tokens"), raw.optLong("cache_read_tokens"),
            raw.optInt("reported_jobs"), raw.optInt("unreported_jobs"),
        )
    }

    /** [rejects]: o motor recusar (404, 409...) é a resposta, e a mensagem dele chega à tela. */
    private suspend fun <T> call(what: String, rejects: Boolean = false, block: suspend () -> T): EngineResult<T> = try {
        EngineResult.Ok(block())
    } catch (e: CancellationException) {
        throw e
    } catch (e: AuraEngineException) {
        if (rejects) EngineResult.Rejected(e.code, e.message ?: "O motor recusou o pedido.")
        else EngineResult.Unavailable(reasonFor(e, what))
    } catch (e: Exception) {
        EngineResult.Unavailable(reasonFor(e, what))
    }

    /** Item malformado é descartado; a lista inteira não se perde por causa dele. */
    private fun <T : Any> JSONArray.mapObjects(parse: (JSONObject) -> T): List<T> =
        (0 until length()).mapNotNull { runCatching { parse(getJSONObject(it)) }.getOrNull() }

    private fun entryFrom(o: JSONObject): MemoryEntry {
        val id = o.getString("id")
        require(id.isNotBlank())
        return MemoryEntry(
            id = id,
            kind = o.text("kind") ?: "preference",
            text = o.getString("text"),
            source = o.text("source"),
            createdAtMillis = (o.optDouble("createdAt", 0.0) * 1000).toLong(), // o motor manda segundos
        )
    }

    private fun skillFrom(o: JSONObject): SkillInfo {
        val id = o.getString("id")
        return SkillInfo(id, o.text("name") ?: id, o.text("description").orEmpty(), o.text("origin") == "bundled")
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
        val wire = raw.getString("status")
        val known = JobStatus.fromWire(wire)
        // Estado novo que este app não conhece: aparece como falha, com o motivo, em vez de sumir.
        val status = known ?: JobStatus.Failed
        // Risco desconhecido: trata como o mais perigoso (pede aprovação).
        val risk = JobRisk.fromWire(raw.text("risk").orEmpty()) ?: JobRisk.Critical
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
            requestId = raw.text("request_id").orEmpty(),
            title = summary.take(100),
            kind = request.optString("kind", "system"),
            status = status,
            risk = risk,
            createdAtMillis = (raw.optDouble("created_at", 0.0) * 1000).toLong(),
            result = resultText?.take(5000),
            errorCode = if (known == null) "unknown_status:" + wire.take(40) else raw.optJSONObject("error")?.text("code")
        )
    }

    private companion object {
        const val MAX_ROUTED = 200
        const val WORKSPACE = "default"
    }
}

/** Texto do campo, ou null se ausente, JSON null ou vazio (no Android `optString` devolveria "null"). */
private fun JSONObject.text(key: String): String? = if (isNull(key)) null else optString(key).ifBlank { null }
