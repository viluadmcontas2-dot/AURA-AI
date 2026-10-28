package ai.aura.app.offline

import ai.aura.app.integration.AuraEngineException
import ai.aura.app.ui.engine.Capability
import ai.aura.app.ui.engine.EngineEvent
import ai.aura.app.ui.engine.EngineGateway
import ai.aura.app.ui.engine.Job
import ai.aura.app.ui.engine.JobRequest
import ai.aura.app.ui.engine.JobRisk
import ai.aura.app.ui.engine.JobStatus
import ai.aura.app.ui.engine.RouteResult
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.buffer
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.EmptyCoroutineContext

/**
 * Gateway "offline primeiro" (AGENTS.md, regra 1): o app nunca fica morto. Envolve o gateway real e,
 * quando o motor não responde, usa o que está guardado neste celular.
 *
 * - Leituras: com o motor no ar devolve a resposta dele e guarda a última boa; sem ele devolve a
 *   última guardada. Não lança e não inventa dado novo.
 * - Pedidos: sem o motor, [route] devolve uma interpretação provisória e [submit] guarda o pedido
 *   numa fila em disco (job `local_<requestId>`, status [JobStatus.WaitingEngine]).
 * - Fila: sai quando o motor volta (em [jobs] ou no evento `connection.restored`), um pedido de cada
 *   vez e na ordem. Cada um é roteado de novo e enviado com o mesmo requestId, que o motor trata como
 *   idempotente. O que o motor recusa de vez vira "Falhou" no histórico local e sai da fila.
 * - [link] recebe o desfecho de cada chamada, para a tela mostrar "Motor desligado" com a ação certa.
 *
 * O que não é tratado aqui (política, modelos, gastos, contas) passa direto para [delegate].
 * shortcut: o disco é lido e gravado na thread de quem chama (arquivos pequenos); mover para
 * Dispatchers.IO se virar gargalo.
 */
class OfflineFirstGateway(
    private val delegate: EngineGateway,
    private val store: LocalStore,
    private val link: EngineLink,
    /** Onde o disco e o motor são tocados. Em produção: Dispatchers.IO (a UI chama da Main); o padrão não troca de thread. */
    private val io: CoroutineContext = EmptyCoroutineContext,
    private val clock: () -> Long = { System.currentTimeMillis() },
) : EngineGateway by delegate {
    // Protege a fila: enfileirar, cancelar e enviar nunca se atropelam, então ninguém sai duas vezes.
    private val lock = Mutex()

    // Última leitura boa gravada por esta instância; evita regravar (e forçar o disco) a cada consulta.
    @Volatile private var lastJobs: List<Job>? = null
    @Volatile private var lastCapabilities: List<Capability>? = null

    /**
     * O buffer na frente solta o polling compartilhado do motor: enviar a fila demora e não pode
     * atrasar os eventos das outras telas.
     */
    override val events: Flow<EngineEvent> = delegate.events.buffer().onEach {
        when (it.type) {
            "connection.restored" -> {
                link.online()
                flush()
            }
            "connection.lost" -> link.offline(
                EngineUnreachableException("O motor parou de responder.", OfflineReason.EngineStopped),
            )
        }
    }

    // ---- leitura ----

    override suspend fun jobs(): List<Job> {
        val live = attempt { delegate.jobs() } ?: return merged(readJobs(JOBS_CACHE))
        rememberJobs(live)
        val sent = if (readQueue().isEmpty()) emptyList() else flush()
        return merged(live, sent)
    }

    override suspend fun capabilities(): List<Capability> {
        val live = attempt { delegate.capabilities() }
        if (live != null) {
            rememberCapabilities(live)
            return live
        }
        val why = if (link.state.value == LinkState.Online) "Sem resposta do motor" else "Motor desligado"
        // Sem o motor não dá para saber se a ferramenta continua lá: nada fica "disponível".
        return readCapabilities().map {
            it.copy(available = false, detail = it.detail?.let { d -> "$why. Última leitura: $d" } ?: why)
        }
    }

    // ---- pedidos ----

    override suspend fun route(text: String): RouteResult = try {
        reach { delegate.route(text) }
    } catch (_: IOException) {
        RouteResult(
            request = JobRequest(AUTO, text),
            reason = "O motor está desligado. O pedido vai para a fila e o motor interpreta quando voltar.",
            confidence = 0.0,
            requiresModel = true,
            risk = PROVISIONAL_RISK,
        )
    }

    override suspend fun submit(requestId: String, request: JobRequest): Job = lock.withLock {
        readQueue().firstOrNull { it.requestId == requestId }?.let { return@withLock it.toJob() }
        // O que já espera sai primeiro: um pedido novo nunca passa na frente da fila.
        flushLocked()
        if (readQueue().isEmpty()) {
            try {
                // "auto" nasceu sem o motor: ele ainda precisa interpretar o pedido.
                val toSend = if (request.kind == AUTO) reach { delegate.route(request.prompt) }.request else request
                return@withLock reach { delegate.submit(requestId, toSend) }
            } catch (_: IOException) {
                // Sem motor (ou sem credencial): o pedido vai para a fila, logo abaixo.
            }
        }
        enqueue(requestId, request)
    }

    override suspend fun approve(id: String): Job = reach { delegate.approve(id) }

    override suspend fun cancel(id: String): Job {
        if (id.startsWith(LOCAL)) {
            val removed = lock.withLock {
                readQueue().firstOrNull { LOCAL + it.requestId == id }?.also { dequeue(it.requestId) }
            }
            // Ainda não saiu do celular: cancelar não precisa do motor.
            if (removed != null) return removed.toJob().copy(status = JobStatus.Cancelled)
        }
        return reach { delegate.cancel(id) }
    }

    // ---- conexão ----

    /**
     * Chama o motor e avisa o [link] do desfecho. Relança o erro original, exceto a 401, que vira
     * "sem credencial" (uma [IOException] para quem já trata falta de conexão).
     */
    private suspend fun <T> reach(call: suspend () -> T): T = try {
        call().also { link.online() }
    } catch (e: CancellationException) {
        throw e
    } catch (e: IOException) {
        link.offline(e)
        throw e
    } catch (e: AuraEngineException) {
        if (e.httpStatus != 401) {
            link.online()
            throw e
        }
        val noToken = EngineUnreachableException(
            "O motor não reconheceu o app. Conecte o Termux de novo em Conexões.", OfflineReason.NoToken,
        )
        link.offline(noToken)
        throw noToken
    } catch (e: Exception) {
        link.online() // o motor respondeu; só não deu para ler a resposta
        throw e
    }

    /** Como [reach], mas devolve null em vez de lançar. */
    private suspend fun <T> attempt(call: suspend () -> T): T? = try {
        reach(call)
    } catch (e: CancellationException) {
        throw e
    } catch (_: Exception) {
        null
    }

    // ---- fila ----

    private suspend fun flush(): List<Job> = lock.withLock { flushLocked() }

    /**
     * Envia a fila em ordem, um pedido de cada vez, e devolve o que o motor aceitou. Só lança
     * cancelamento: se o motor cair, a credencial falhar, a resposta vier ilegível ou o disco der
     * erro, o resto da fila espera. Reenviar é seguro: o motor devolve o mesmo job para o mesmo requestId.
     */
    private suspend fun flushLocked(): List<Job> {
        val accepted = mutableListOf<Job>()
        try {
            while (true) {
                val next = readQueue().firstOrNull() ?: break
                try {
                    val routed = reach { delegate.route(next.request.prompt) }.request
                    accepted += reach { delegate.submit(next.requestId, routed) }
                    dequeue(next.requestId)
                } catch (e: AuraEngineException) {
                    if (!e.isFinal()) break
                    reject(next, e)
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            // ver acima
        }
        return accepted
    }

    private fun enqueue(requestId: String, request: JobRequest): Job {
        val item = QueuedRequest(requestId, request, clock())
        writeQueue(readQueue() + item)
        return item.toJob()
    }

    private fun dequeue(requestId: String) = writeQueue(readQueue().filter { it.requestId != requestId })

    /** O motor disse não: guarda a falha no histórico (antes de sair da fila, para nunca sumir) e segue. */
    private fun reject(item: QueuedRequest, e: AuraEngineException) {
        val failed = item.toJob().copy(status = JobStatus.Failed, errorCode = e.code ?: "rejected", result = e.message)
        writeJobs(REJECTED, (readJobs(REJECTED).filter { it.id != failed.id } + failed).takeLast(MAX_REJECTED))
        dequeue(item.requestId)
    }

    // ---- o que está guardado ----

    /** Jobs do motor, mais o que só existe aqui (fila e recusados), do mais novo para o mais velho. */
    private fun merged(engine: List<Job>, accepted: List<Job> = emptyList()): List<Job> {
        val fromEngine = engine + accepted.filter { a -> engine.none { it.id == a.id } }
        // Se o motor já conhece o pedido (caiu depois de enviar, antes de sair da fila), não mostra duas vezes.
        val known = fromEngine.mapNotNullTo(HashSet()) { it.requestId.ifEmpty { null } }
        val waiting = readQueue().filter { it.requestId !in known }.map { it.toJob() }
        return (fromEngine + waiting + readJobs(REJECTED)).sortedByDescending { it.createdAtMillis }
    }

    private fun rememberJobs(jobs: List<Job>) {
        if (jobs == lastJobs) return
        try {
            writeJobs(JOBS_CACHE, jobs)
            lastJobs = jobs
        } catch (_: Exception) {
            // O cache é só conforto: disco com problema não derruba a leitura; tenta de novo na próxima.
        }
    }

    private fun rememberCapabilities(capabilities: List<Capability>) {
        if (capabilities == lastCapabilities) return
        try {
            store.write(
                CAPS_CACHE,
                JSONObject().put(
                    "items",
                    capabilities.map {
                        JSONObject().put("id", it.id).put("label", it.label).put("available", it.available)
                            .also { o -> it.detail?.let { d -> o.put("detail", d) } }
                    }.toJsonArray(),
                ),
            )
            lastCapabilities = capabilities
        } catch (_: Exception) {
            // idem
        }
    }

    private fun readCapabilities(): List<Capability> = store.read(CAPS_CACHE).objects("items").mapNotNull {
        val id = it.optString("id")
        if (id.isEmpty()) null else Capability(id, it.optString("label"), it.optBoolean("available"), it.optNullableString("detail"))
    }

    private fun readJobs(name: String): List<Job> = store.read(name).objects("jobs").mapNotNull(::jobFrom)

    private fun writeJobs(name: String, jobs: List<Job>) =
        store.write(name, JSONObject().put("jobs", jobs.map(::jobJson).toJsonArray()))

    private fun readQueue(): List<QueuedRequest> = store.read(QUEUE).objects("items").mapNotNull {
        val requestId = it.optString("request_id")
        if (requestId.isEmpty()) null else QueuedRequest(
            requestId,
            JobRequest(it.optString("kind", AUTO), it.optString("prompt"), it.optString("workspace", "default")),
            it.optLong("created_at"),
        )
    }

    private fun writeQueue(items: List<QueuedRequest>) = store.write(
        QUEUE,
        JSONObject().put(
            "items",
            items.map {
                JSONObject().put("request_id", it.requestId).put("kind", it.request.kind)
                    .put("prompt", it.request.prompt).put("workspace", it.request.workspace)
                    .put("created_at", it.createdAt)
            }.toJsonArray(),
        ),
    )

    private data class QueuedRequest(val requestId: String, val request: JobRequest, val createdAt: Long) {
        /** O risco real só o motor sabe; até lá, "altera algo" (pede aprovação). */
        fun toJob() = Job(
            id = LOCAL + requestId, requestId = requestId, title = request.prompt.take(100),
            kind = request.kind, status = JobStatus.WaitingEngine, risk = PROVISIONAL_RISK, createdAtMillis = createdAt,
        )
    }

    private companion object {
        const val LOCAL = "local_"
        /** Tipo provisório: o motor ainda vai interpretar o pedido. */
        const val AUTO = "auto"
        const val QUEUE = "offline-queue"
        const val REJECTED = "offline-rejected"
        const val JOBS_CACHE = "offline-jobs"
        const val CAPS_CACHE = "offline-capabilities"
        const val MAX_REJECTED = 50
        val PROVISIONAL_RISK = JobRisk.Write
    }
}

/** O motor entendeu e disse não (4xx): reenviar não adianta. 408 e 429 são "tente depois", como os 5xx. */
private fun AuraEngineException.isFinal() = httpStatus in 400..499 && httpStatus != 408 && httpStatus != 429

private fun jobJson(job: Job): JSONObject = JSONObject()
    .put("id", job.id).put("request_id", job.requestId).put("title", job.title).put("kind", job.kind)
    .put("status", job.status.wire).put("risk", job.risk.wire).put("created_at", job.createdAtMillis)
    .also { o ->
        job.result?.let { o.put("result", it) }
        job.errorCode?.let { o.put("error_code", it) }
    }

/** Registro de outra versão ou estragado vira null e é ignorado: ler o cache nunca derruba a tela. */
private fun jobFrom(o: JSONObject): Job? {
    val id = o.optString("id").ifEmpty { return null }
    val status = JobStatus.fromWire(o.optString("status")) ?: return null
    val risk = JobRisk.fromWire(o.optString("risk")) ?: return null
    return Job(
        id, o.optString("request_id"), o.optString("title"), o.optString("kind"), status, risk,
        o.optLong("created_at"), o.optNullableString("result"), o.optNullableString("error_code"),
    )
}

private fun JSONObject.optNullableString(key: String): String? = if (has(key) && !isNull(key)) getString(key) else null

private fun JSONObject?.objects(key: String): List<JSONObject> {
    val array = this?.optJSONArray(key) ?: return emptyList()
    return (0 until array.length()).mapNotNull { array.optJSONObject(it) }
}

private fun List<JSONObject>.toJsonArray(): JSONArray = JSONArray().also { array -> forEach { array.put(it) } }
