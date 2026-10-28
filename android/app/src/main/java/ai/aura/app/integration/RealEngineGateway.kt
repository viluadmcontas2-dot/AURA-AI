package ai.aura.app.integration

import ai.aura.app.offline.EngineUnreachableException
import ai.aura.app.offline.OfflineReason
import ai.aura.app.ui.engine.AUTH_PROVIDER_IDS
import ai.aura.app.ui.engine.AuthStartResult
import ai.aura.app.ui.engine.AuthState
import ai.aura.app.ui.engine.AuthStatus
import ai.aura.app.ui.engine.ToolActionResult
import ai.aura.app.ui.engine.Capability
import ai.aura.app.ui.engine.ChatAction
import ai.aura.app.ui.engine.ChatEvent
import ai.aura.app.ui.engine.ChatSnapshot
import ai.aura.app.ui.engine.ChatStatus
import ai.aura.app.ui.engine.ENGINE_NOT_EXPOSED
import ai.aura.app.ui.engine.HistoryLine
import ai.aura.app.ui.engine.HistoryPage
import ai.aura.app.ui.engine.LastChat
import ai.aura.app.ui.engine.SessionState
import ai.aura.app.ui.engine.ChatFailure
import ai.aura.app.ui.engine.ChatLine
import ai.aura.app.ui.engine.ChatStarted
import ai.aura.app.ui.engine.ChatSummary
import ai.aura.app.ui.engine.ChatUnsupportedException
import ai.aura.app.ui.engine.ImproveResult
import ai.aura.app.ui.engine.IntentOption
import ai.aura.app.ui.engine.IntentResult
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
import ai.aura.app.ui.engine.ProcessInfo
import ai.aura.app.ui.engine.RouteResult
import ai.aura.app.ui.engine.SessionInfo
import ai.aura.app.ui.engine.SessionLine
import ai.aura.app.ui.engine.SessionLineRole
import ai.aura.app.ui.engine.SkillInfo
import ai.aura.app.ui.engine.SpendResult
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.shareIn
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import java.util.Collections
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicLong

/** Production EngineGateway. Never substitutes a fake for a missing Termux process. */
class RealEngineGateway(
    private val client: EngineApi,
    /** Escopo do polling de eventos: sem assinantes ele para (WhileSubscribed), então nada fica vivo. */
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
    /** Espera entre duas leituras de GET /v1/chat/{id} enquanto o motor responde. */
    private val chatPollMillis: Long = 400L,
    /** Espera entre duas leituras de GET /v1/jobs/{id} enquanto uma CLI é instalada. */
    private val installPollMillis: Long = 2_000L,
    /** Quanto tempo o app acompanha uma instalação antes de mandar olhar a Atividade. */
    private val installWaitMillis: Long = 15 * 60_000L,
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
            // O motor chama o Drive de "google"; o app e o contrato de login usam "gdrive".
            val id = item.optString("provider").let { if (it == "google") "gdrive" else it }
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
            val answer = client.startAuth(provider)
            val prompt = answer.optJSONObject("prompt")
            val url = prompt?.optString("url")?.ifBlank { null }
            when {
                answer.optString("state") == "indisponivel" -> AuthStartResult.Failed(
                    "O programa desta conta não está instalado no Termux.",
                    "Toque em Instalar e depois em Conectar.",
                )
                // Login já em andamento: o motor devolve o link e o código na própria resposta.
                url != null -> AuthStartResult.Prompted(url, prompt?.optString("code")?.ifBlank { null })
                else -> AuthStartResult.Started
            }
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

    /** Ids do app -> ids do contrato de /v1/tools/install. */
    private fun toolId(provider: String) = when (provider) { "github" -> "gh"; "gdrive" -> "rclone"; else -> provider }

    /** Segue o job de instalação até um estado final; erro passageiro de leitura não derruba o acompanhamento. */
    private suspend fun followInstall(jobId: String): ToolActionResult {
        var waited = 0L
        var failures = 0
        while (waited <= installWaitMillis) {
            val job = try {
                client.job(jobId).also { failures = 0 }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (++failures >= 3) return ToolActionResult.Failed(
                    "Perdi a conexão com o motor durante a instalação.",
                    "Abra o Termux e toque em Instalar de novo.",
                )
                null
            }
            if (job != null) {
                val result = job.optJSONObject("result")
                when (job.optString("status")) {
                    "completed" -> return when {
                        result?.optString("state") == "waiting_wifi" -> ToolActionResult.Failed(
                            "Estou em dados móveis e esperei o Wi-Fi.", "Conecte ao Wi-Fi e toque em Instalar de novo.",
                        )
                        result?.optBoolean("already_installed") == true -> ToolActionResult.Ok("Já estava instalado.")
                        else -> ToolActionResult.Ok("Instalado. Agora toque em Conectar.")
                    }
                    "failed" -> return ToolActionResult.Failed(
                        "Não consegui instalar este programa.", "Confira a internet e toque em Instalar de novo.",
                    )
                    "cancelled", "interrupted" -> return ToolActionResult.Failed(
                        "A instalação foi interrompida.", "Toque em Instalar para tentar de novo.",
                    )
                }
            }
            delay(installPollMillis)
            waited += installPollMillis
        }
        return ToolActionResult.Failed(
            "A instalação está demorando mais que o normal.", "Veja o andamento na aba Atividade ou toque em Instalar de novo.",
        )
    }

    private fun toolFailure(e: Exception, doing: String): ToolActionResult.Failed = when {
        e is AuraEngineException && e.code == "indisponivel_no_termux" -> ToolActionResult.Failed(
            "Este programa não instala no Termux.",
            "Use outra conta por enquanto. Se quiser, toque em Tentar de novo.",
        )
        e is AuraEngineException -> ToolActionResult.Failed(
            e.message?.takeIf { it.isNotBlank() } ?: "O motor recusou $doing.", "Confira os dados e tente de novo.",
        )
        notExposed(e) -> ToolActionResult.Failed(
            "O motor ainda não sabe $doing.", "Atualize o AURA Engine no Termux e tente de novo.",
        )
        e is java.io.IOException -> ToolActionResult.Failed(
            e.message ?: "O Termux não respondeu.", "Abra o Termux, inicie o motor e tente de novo.",
        )
        else -> ToolActionResult.Failed("Não foi possível $doing.", "Tente de novo.")
    }

    /**
     * POST /v1/tools/install devolve 202 + job: o app acompanha o job até o fim e devolve o resultado em
     * português (instalado, sem Wi-Fi ou falhou), em vez de mandar a pessoa procurar na Atividade.
     */
    override suspend fun installTool(provider: String): ToolActionResult = try {
        val r = client.installTool(toolId(provider))
        val notices = r.optJSONArray("download_notices")?.length() ?: 0
        val jobId = r.text("id")
        when {
            r.optBoolean("already_installed") -> ToolActionResult.Ok("Já estava instalado.")
            jobId == null -> ToolActionResult.Ok(
                if (notices > 0) "Instalando. Há download grande (mais de 100 MB): prefira o Wi-Fi. Acompanhe em Atividade."
                else "Instalando. Acompanhe em Atividade.",
            )
            else -> followInstall(jobId)
        }
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        toolFailure(e, "instalar este programa")
    }

    override suspend fun submitAuthCode(provider: String, code: String): ToolActionResult = try {
        client.authCode(provider, code.trim())
        ToolActionResult.Ok("Código enviado. Conferindo o login…")
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        toolFailure(e, "enviar o código")
    }

    override suspend fun submitAuthToken(provider: String, token: String): ToolActionResult = try {
        val r = client.authToken(provider, token.trim())
        if (r.optString("state") == "conectado") ToolActionResult.Ok("Conectado.")
        else ToolActionResult.Failed("O motor não confirmou o login.", "Confira o token e tente de novo.")
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        toolFailure(e, "enviar o token")
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

    // ---- Conversa ------------------------------------------------------------------------

    override suspend fun startChat(text: String, sessionId: String?, workspace: String): ChatStarted =
        startChat(text, sessionId, workspace, null, null)

    override suspend fun startChat(
        text: String, sessionId: String?, workspace: String, requestId: String?, executor: String?,
    ): ChatStarted = try {
        val raw = client.chat(text, sessionId, workspace, requestId, executor)
        val id = raw.text("chat_id") ?: throw AuraEngineException(502, "O motor não informou o chat_id.")
        ChatStarted(id, raw.text("session_id"), raw.text("request_id"), raw.text("state"))
    } catch (e: AuraEngineException) {
        // 404 session_not_found é resposta do motor ("não conheço essa conversa"), não "motor sem a rota".
        if (e.code != "session_not_found" && notExposed(e)) throw ChatUnsupportedException() else throw e
    }

    // ---- C1/C2: retratos, estado da sessão e páginas ------------------------------------------

    private fun isoMillis(value: String?): Long? =
        value?.let { runCatching { java.time.Instant.parse(it).toEpochMilli() }.getOrNull() }

    private fun statusFrom(o: JSONObject, fallbackChatId: String = ""): ChatStatus = ChatStatus(
        chatId = o.text("chat_id") ?: fallbackChatId,
        sessionId = o.text("session_id"),
        seq = o.optLong("seq", 0L),
        state = o.text("state") ?: "running",
        phase = o.text("phase") ?: "unknown",
        elapsedMs = o.optLong("elapsed_ms", 0L),
        lastOutputAtMillis = isoMillis(o.text("last_output_at")),
        heartbeatAtMillis = isoMillis(o.text("heartbeat_at")),
        canStop = o.optBoolean("can_stop", false),
    )

    /** O payload de um evento chat.status chega como mapa de textos; reaproveita o mesmo leitor. */
    private fun statusFrom(payload: Map<String, String>): ChatStatus = statusFrom(JSONObject().also { o ->
        payload.forEach { (k, v) ->
            when (k) {
                "seq", "elapsed_ms" -> o.put(k, v.toLongOrNull() ?: 0L)
                "can_stop" -> o.put(k, v == "true")
                else -> o.put(k, v)
            }
        }
    })

    private fun snapshotFrom(raw: JSONObject, chatId: String = ""): ChatSnapshot {
        val status = statusFrom(raw, chatId)
        val err = raw.optJSONObject("error")
        val failure = when {
            status.state == "interrupted" -> chatFailureFor(err?.text("code") ?: "stopped", err?.text("message_pt"))
            err != null -> chatFailureFor(err.text("code"), err.text("message_pt"))
            status.state == "error" -> chatFailureFor(null, null)
            else -> null
        }
        return ChatSnapshot(status, raw.text("request_id"), raw.text("text").orEmpty(), failure)
    }

    override suspend fun chatSnapshot(chatId: String): EngineResult<ChatSnapshot> =
        engineCall("a resposta") { snapshotFrom(client.chatState(chatId), chatId) }

    override suspend fun chatByRequest(requestId: String): EngineResult<ChatSnapshot> =
        engineCall("o pedido") { snapshotFrom(client.chatByRequest(requestId)) }

    /** Rota que o motor antigo não tem: 404 "Unknown endpoint" vira "não expõe"; 404 com outra frase é a sessão que sumiu. */
    private suspend fun <T> sessionCall(what: String, block: suspend () -> T): EngineResult<T> = try {
        EngineResult.Ok(block())
    } catch (e: CancellationException) {
        throw e
    } catch (e: AuraEngineException) {
        when {
            e.httpStatus == 501 || (e.httpStatus == 404 && e.message == "Unknown endpoint") ->
                EngineResult.Unavailable(ENGINE_NOT_EXPOSED)
            e.code != null && e.httpStatus in 400..499 && e.httpStatus != 401 ->
                EngineResult.Rejected(if (e.httpStatus == 404) "session_not_found" else e.code, e.message ?: "O motor recusou o pedido.")
            else -> EngineResult.Unavailable(reasonFor(e, what))
        }
    } catch (e: Exception) {
        EngineResult.Unavailable(reasonFor(e, what))
    }

    private fun roleOf(raw: String): SessionLineRole = when (raw) {
        "user", "human" -> SessionLineRole.User
        "assistant" -> SessionLineRole.Assistant
        "tool" -> SessionLineRole.Tool
        else -> error("papel desconhecido")
    }

    override suspend fun sessionState(id: String): EngineResult<SessionState> = sessionCall("o estado da conversa") {
        val o = client.sessionState(id)
        val last = o.optJSONObject("last_chat")
        SessionState(
            sessionId = o.text("session_id") ?: id,
            version = o.text("version").orEmpty(),
            revision = o.text("revision").orEmpty(),
            messages = o.optInt("messages", 0),
            total = o.optInt("total", 0),
            updatedAtMillis = (o.optDouble("updated_at", 0.0) * 1000).toLong(),
            running = o.optBoolean("running", false),
            activeChatId = o.text("active_chat_id"),
            externalRunning = o.optBoolean("external_running", false),
            historyComplete = o.optBoolean("history_complete", true),
            fromTerminal = o.optString("source") == "terminal",
            active = o.optJSONObject("active")?.let { statusFrom(it) },
            lastChat = last?.let { LastChat(it.optString("chat_id"), it.optString("state"), it.text("request_id")) }
                ?.takeIf { it.chatId.isNotEmpty() },
        )
    }

    override suspend fun sessionPage(id: String, limit: Int, before: String?, after: Int?): EngineResult<HistoryPage> =
        sessionCall("a conversa") {
            val o = client.sessionPage(id, limit, before, after)
            val array = o.optJSONArray("messages") ?: JSONArray()
            HistoryPage(
                lines = array.mapObjects { HistoryLine(it.getString("id"), roleOf(it.optString("role")), it.text("text") ?: error("linha vazia")) },
                previousCursor = o.text("previous_cursor"),
                hasMore = o.optBoolean("has_more", false),
                historyComplete = o.optBoolean("history_complete", true),
                total = o.optInt("total", array.length()),
                firstIndex = o.optInt("first_index", 0),
                revision = o.text("revision").orEmpty(),
                activeChatId = o.text("active_chat_id"),
            )
        }

    override suspend fun watchSession(id: String): EngineResult<Unit> =
        sessionCall("a conversa") { client.watchSession(id); Unit }

    /**
     * Junta os eventos chat.delta/chat.tool/chat.done/chat.error com a leitura de GET /v1/chat/{id}:
     * o evento é mais rápido e traz as linhas de ferramenta; a leitura cobre evento perdido e
     * motor que só informa o estado. O texto sai sempre inteiro e nunca encolhe.
     */
    override fun chatStream(chatId: String): Flow<ChatEvent> = channelFlow {
        val pending = ConcurrentLinkedQueue<EngineEvent>()
        val listener = launch {
            try {
                events.collect { if (it.type.startsWith("chat.") && it.payload["chat_id"] == chatId) pending.add(it) }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                // A leitura de estado abaixo continua acompanhando.
            }
        }
        try {
            var acc = ""
            var lastSent = ""
            var failures = 0
            var lastSeq = 0L
            while (true) {
                while (true) {
                    val ev = pending.poll() ?: break
                    when (ev.type) {
                        "chat.status" -> send(ChatEvent.Status(statusFrom(ev.payload)))
                        "chat.delta" -> acc += ev.payload["text"].orEmpty()
                        "chat.tool" -> {
                            if (acc != lastSent) { send(ChatEvent.Text(acc)); lastSent = acc }
                            ev.payload["summary"]?.takeIf { it.isNotBlank() }?.let { send(ChatEvent.Tool(it)) }
                        }
                        "chat.done" -> {
                            send(ChatEvent.Done(ev.payload["text"]?.takeIf { it.isNotBlank() } ?: acc, ev.payload["session_id"]?.takeIf { it.isNotBlank() }))
                            return@channelFlow
                        }
                        "chat.error" -> {
                            send(ChatEvent.Failed(chatFailureFor(ev.payload["code"], ev.payload["message_pt"])))
                            return@channelFlow
                        }
                    }
                }
                val state = try {
                    client.chatState(chatId).also { failures = 0 }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    failures++
                    if ((e is AuraEngineException && e.httpStatus in 400..499) || failures >= MAX_CHAT_POLL_FAILURES) throw e
                    delay(EventBackoff.delayMillis(failures))
                    continue
                }
                val snapshot = state.text("text").orEmpty()
                if (state.optLong("seq", 0L) > lastSeq && state.optLong("seq", 0L) < TERMINAL_SEQ) {
                    lastSeq = state.optLong("seq", 0L)
                    send(ChatEvent.Status(statusFrom(state, chatId)))
                }
                when (state.optString("state")) {
                    "interrupted" -> {
                        val err = state.optJSONObject("error")
                        send(ChatEvent.Failed(chatFailureFor(err?.text("code") ?: "stopped", err?.text("message_pt"))))
                        return@channelFlow
                    }
                    "done" -> {
                        send(ChatEvent.Done(snapshot.ifBlank { acc }, state.text("session_id")))
                        return@channelFlow
                    }
                    "error" -> {
                        val err = state.optJSONObject("error")
                        send(ChatEvent.Failed(chatFailureFor(err?.text("code"), err?.text("message_pt"))))
                        return@channelFlow
                    }
                    else -> {
                        if (snapshot.length > acc.length) acc = snapshot
                        if (acc != lastSent) { send(ChatEvent.Text(acc)); lastSent = acc }
                    }
                }
                delay(chatPollMillis)
            }
        } finally {
            listener.cancel()
        }
    }

    override suspend fun stopChat(chatId: String) {
        client.chatStop(chatId)
    }

    override suspend fun chats(): List<ChatSummary> {
        val raw = client.chats()
        val array = raw.optJSONArray("items") ?: raw.optJSONArray("chats") ?: return emptyList()
        return array.mapObjects {
            ChatSummary(it.getString("id"), it.text("title").orEmpty(), (it.optDouble("updated_at", 0.0) * 1000).toLong())
        }.sortedByDescending { it.updatedAtMillis }
    }

    override suspend fun chatMessages(id: String): List<ChatLine> {
        val raw = client.chatMessages(id)
        val array = raw.optJSONArray("messages") ?: raw.optJSONArray("items") ?: return emptyList()
        return array.mapObjects {
            ChatLine(fromUser = it.optString("role") in listOf("user", "human"), text = it.getString("text"))
        }.filter { it.text.isNotBlank() }
    }

    /** 404/erro do motor = conversa normal. Só a falta de motor (IOException) sobe, e o chat trata. */
    override suspend fun intent(text: String): IntentResult = try {
        intentFrom(client.intent(text), text)
    } catch (e: CancellationException) {
        throw e
    } catch (e: AuraEngineException) {
        IntentResult.Chat
    } catch (e: JSONException) {
        IntentResult.Chat
    } catch (e: UnsupportedOperationException) {
        IntentResult.Chat
    }

    private fun intentFrom(raw: JSONObject, original: String): IntentResult {
        val question = raw.text("question")
        val options = raw.optJSONArray("options")?.mapObjects {
            IntentOption(it.getString("label"), it.getString("kind"))
        }.orEmpty()
        if (question != null && options.size >= 2) return IntentResult.Ask(question, options.take(2))
        val idea = raw.text("idea") ?: original
        return when (raw.optString("kind")) {
            "command" -> (raw.text("name") ?: raw.text("command"))?.let { IntentResult.Command(it) } ?: IntentResult.Chat
            "reform" -> IntentResult.Reform(idea)
            "project" -> IntentResult.Project(idea)
            "task" -> IntentResult.Task(idea)
            else -> IntentResult.Chat
        }
    }

    // ---- Sessões do Claude Code, processos, melhorar pedido e chave do Gemini ----------------

    /**
     * Como [call], para as rotas deste bloco: o motor recusar com um código (404 de sessão que sumiu, 400)
     * é a resposta e a mensagem dele chega à tela; sem motor, ou motor sem a rota, vira indisponível.
     */
    private suspend fun <T> engineCall(what: String, block: suspend () -> T): EngineResult<T> = try {
        EngineResult.Ok(block())
    } catch (e: CancellationException) {
        throw e
    } catch (e: AuraEngineException) {
        val code = e.code
        if (code != null && e.httpStatus in 400..499 && e.httpStatus != 401) {
            EngineResult.Rejected(code, e.message ?: "O motor recusou o pedido.")
        } else {
            EngineResult.Unavailable(reasonFor(e, what))
        }
    } catch (e: Exception) {
        EngineResult.Unavailable(reasonFor(e, what))
    }

    override suspend fun sessions(): EngineResult<List<SessionInfo>> = engineCall("as sessões") {
        val raw = client.sessions()
        val array = raw.optJSONArray("sessions") ?: raw.optJSONArray("items") ?: JSONArray()
        array.mapObjects { sessionFrom(it) }
    }

    private fun sessionFrom(o: JSONObject): SessionInfo {
        val id = o.getString("id")
        require(id.isNotBlank())
        return SessionInfo(
            id = id,
            title = o.text("title") ?: "Conversa sem título",
            project = o.text("project").orEmpty(),
            updatedAtMillis = (o.optDouble("updated_at", 0.0) * 1000).toLong(), // o motor manda segundos
            messages = o.optInt("messages", 0),
            fromTerminal = o.optString("source") == "terminal",
            running = o.optBoolean("running", false),
        )
    }

    override suspend fun sessionMessages(id: String): EngineResult<List<SessionLine>> = engineCall("a conversa") {
        val raw = client.sessionMessages(id)
        val array = raw.optJSONArray("messages") ?: raw.optJSONArray("items") ?: JSONArray()
        array.mapObjects {
            val role = when (it.optString("role")) {
                "user", "human" -> SessionLineRole.User
                "assistant" -> SessionLineRole.Assistant
                "tool" -> SessionLineRole.Tool
                else -> error("papel desconhecido")
            }
            SessionLine(role, it.text("text") ?: error("linha vazia"))
        }
    }

    override suspend fun renameSession(id: String, title: String): EngineResult<Unit> =
        engineCall("renomear a sessão") { client.renameSession(id, title.trim()); Unit }

    override suspend fun hideSession(id: String): EngineResult<Unit> =
        engineCall("esconder a sessão") { client.deleteSession(id); Unit }

    override suspend fun stopSession(id: String): EngineResult<Unit> =
        engineCall("parar a sessão") { client.stopSession(id); Unit }

    override suspend fun processes(): EngineResult<List<ProcessInfo>> = engineCall("os processos") {
        val raw = client.processes()
        val array = raw.optJSONArray("processes") ?: raw.optJSONArray("items") ?: JSONArray()
        array.mapObjects {
            ProcessInfo(
                pid = it.getLong("pid"),
                kind = it.text("kind") ?: "claude",
                sessionId = it.text("session_id"),
                startedAtMillis = if (it.isNull("started_at")) null else (it.optDouble("started_at") * 1000).toLong(),
            )
        }
    }

    override suspend fun stopProcess(pid: Long): EngineResult<Unit> =
        engineCall("parar o processo") { client.stopProcess(pid); Unit }

    override suspend fun improvePrompt(text: String): ImproveResult = try {
        val improved = client.improvePrompt(text).text("improved")
        if (improved == null) {
            ImproveResult.Failed("empty", "O Gemini não devolveu um pedido melhor. Tente de novo.", ChatAction.Retry)
        } else {
            ImproveResult.Improved(improved)
        }
    } catch (e: CancellationException) {
        throw e
    } catch (e: AuraEngineException) {
        improveFailure(e)
    } catch (e: java.io.IOException) {
        ImproveResult.Failed("engine_down", "O motor está desligado. Inicie o motor e toque em Tentar de novo.", ChatAction.Retry)
    } catch (e: Exception) {
        ImproveResult.Failed("failed", "Não consegui melhorar o pedido agora.", ChatAction.Retry)
    }

    private fun improveFailure(e: AuraEngineException): ImproveResult.Failed = when {
        e.code == "gemini_key_missing" -> ImproveResult.Failed(
            "gemini_key_missing", "Cole a chave do Gemini em Conexões para melhorar pedidos.", ChatAction.OpenConnections,
        )
        e.code == "gemini_key_rejected" -> ImproveResult.Failed(
            "gemini_key_rejected", "O Gemini não aceitou a chave. Cole uma chave nova em Conexões.", ChatAction.OpenConnections,
        )
        e.httpStatus == 401 -> ImproveResult.Failed(
            "no_token", "O AURA ainda não está conectado ao Termux. Abra Conexões.", ChatAction.OpenConnections,
        )
        notExposed(e) && e.code == null -> ImproveResult.Failed(
            "unsupported", "Este motor ainda não melhora pedidos. Atualize o AURA no Termux.", null,
        )
        else -> ImproveResult.Failed(
            e.code ?: "failed",
            e.message?.takeIf { it.isNotBlank() && !it.startsWith("AURA Engine HTTP") } ?: "Não consegui melhorar o pedido agora.",
            ChatAction.Retry,
        )
    }

    override suspend fun geminiKeyConfigured(): EngineResult<Boolean> =
        engineCall("a chave do Gemini") { client.geminiKey().optBoolean("configured", false) }

    /** A chave só passa daqui para o motor: nunca vai a log, evento nem mensagem de erro. */
    override suspend fun saveGeminiKey(key: String): EngineResult<Unit> =
        engineCall("a chave do Gemini") { client.putGeminiKey(key.trim()); Unit }

    override suspend fun removeGeminiKey(): EngineResult<Unit> =
        engineCall("a chave do Gemini") { client.deleteGeminiKey(); Unit }

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

    /** 401 vira `Rejected("unauthorized")`: o motor está vivo, só não aceita o nosso token. */
    override suspend fun health(): EngineResult<EngineHealth> = call("o estado do motor", rejectOn = setOf(401)) {
        val raw = client.health()
        EngineHealth(raw.getString("service"), raw.optInt("version"), raw.text("engine_build"))
    }

    override suspend fun usage(): EngineResult<EngineUsage> = call("o uso") {
        val raw = client.usage()
        EngineUsage(
            raw.optLong("input_tokens"), raw.optLong("output_tokens"), raw.optLong("cache_read_tokens"),
            raw.optInt("reported_jobs"), raw.optInt("unreported_jobs"),
        )
    }

    /** [rejects]: o motor recusar (404, 409...) é a resposta, e a mensagem dele chega à tela. */
    private suspend fun <T> call(
        what: String,
        rejects: Boolean = false,
        rejectOn: Set<Int> = emptySet(),
        block: suspend () -> T,
    ): EngineResult<T> = try {
        EngineResult.Ok(block())
    } catch (e: CancellationException) {
        throw e
    } catch (e: AuraEngineException) {
        if (e.httpStatus in rejectOn) EngineResult.Rejected("unauthorized", e.message ?: "O motor recusou o pedido.")
        else if (rejects) EngineResult.Rejected(e.code, e.message ?: "O motor recusou o pedido.")
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
        const val MAX_CHAT_POLL_FAILURES = 5
        const val TERMINAL_SEQ = 1_000_000_000L
        const val WORKSPACE = "default"
    }
}

/** Texto do campo, ou null se ausente, JSON null ou vazio (no Android `optString` devolveria "null"). */
private fun JSONObject.text(key: String): String? = if (isNull(key)) null else optString(key).ifBlank { null }
