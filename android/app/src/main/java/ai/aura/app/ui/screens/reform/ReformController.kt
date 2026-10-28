package ai.aura.app.ui.screens.reform

import ai.aura.app.integration.ApkSharer
import ai.aura.app.integration.AuraEngineException
import ai.aura.app.integration.ReformGateway
import ai.aura.app.integration.ReformStep
import ai.aura.app.integration.ReformStepResult
import ai.aura.app.integration.ReformSummary
import ai.aura.app.integration.SetupGateway
import ai.aura.app.offline.EngineUnreachableException
import ai.aura.app.ui.screens.connections.InstallConsentGate
import ai.aura.app.ui.screens.connections.InstallFlow
import ai.aura.app.ui.screens.connections.InstallText
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.IOException

/** Guarda o id da reforma em andamento (o Android pode matar o processo; o motor guarda o ledger). */
interface ReformIdStore {
    var reformId: String?
}

class MemoryReformIdStore : ReformIdStore {
    override var reformId: String? = null
}

/** Estado do botão "Travar escopo e começar". */
data class ReformScopeUi(val busy: Boolean = false, val error: String? = null, val engineDown: Boolean = false)

/** Id que o motor grava no ledger (contrato v1, em inglês e estável). */
fun ReformScope.wire(): String = when (this) {
    ReformScope.VisualOnly -> "visual_only"
    ReformScope.VisualAndFlows -> "visual_flows"
    ReformScope.FullRebuild -> "full_rebuild"
}

private fun modeTitle(wire: String?): String? = ReformScope.entries.firstOrNull { it.wire() == wire }?.title ?: wire

/** Passos que o app sabe pedir sozinhos, na ordem. `rebuild` e `model` precisam de dados que o app ainda não tem. */
private val AUTO_STEPS = listOf(ReformStep.Tools, ReformStep.Inventory, ReformStep.Coverage)

private val STEP_TITLES = mapOf(
    "tools" to "Preparar as ferramentas",
    "inventory" to "Inventariar o APK",
    "coverage" to "Medir a cobertura da reversão",
    "rebuild" to "Reconstruir as telas",
    "model" to "Nomear e redigir com modelo",
)

/** Resume o que o motor devolveu em [ReformProgress]. Número nulo continua nulo; nada é estimado aqui. */
fun reformProgressFrom(summary: ReformSummary, appLabel: String?, paused: Boolean = false): ReformProgress {
    val done = summary.stepsDone.toSet()
    return ReformProgress(
        started = true,
        locked = summary.apkPath != null,
        app = appLabel ?: summary.apkPath?.substringAfterLast('/')?.removeSuffix(".apk"),
        mode = modeTitle(summary.mode),
        reversalPct = summary.reversal?.pct,
        rebuildPct = summary.rebuild?.pct,
        fidelityPct = summary.fidelity?.pct,
        steps = STEP_TITLES.map { (id, title) ->
            ReformProgressStep(title, state = if (id in done) ReformProgressStepState.Done else ReformProgressStepState.Pending)
        },
        paused = paused,
        analysisDone = "coverage" in done,
        error = if (summary.chainOk) null else "O registro desta reforma foi alterado e não dá para confiar nos números. Comece uma nova reforma.",
    )
}

/**
 * Cola entre as telas Reformar e o motor. Vive no nível do app (uma instância), então o app escolhido e o id da reforma
 * sobrevivem à troca de tela. Só fala com o motor por [ReformGateway]; nada aqui calcula medidor.
 */
class ReformController(
    private val gateway: ReformGateway,
    private val sharer: ApkSharer,
    private val store: ReformIdStore,
    private val scope: CoroutineScope,
    /** Roda antes de instalar: manda ao motor o tipo de rede de agora (dados móveis não baixam pacote). */
    private val beforeInstall: suspend () -> Unit = {},
    private val clockLabel: () -> String = { java.time.LocalTime.now().toString().take(5) },
    /**
     * Instalar as ferramentas só acontece depois do aceite em [consent] (C4). Sem os dois, o passo `tools` que o motor
     * recusa por falta de consentimento termina em "Nada foi instalado", com o escopo guardado.
     */
    setup: SetupGateway? = null,
    private val consent: InstallConsentGate? = null,
    installPollMillis: Long = 2_000,
) {
    private val installFlow: InstallFlow? =
        if (setup != null && consent != null) InstallFlow(setup, consent, scope, installPollMillis) else null
    private val selected = MutableStateFlow<ReformApp?>(null)
    private val scopeMutable = MutableStateFlow(ReformScopeUi())
    private val toolsMutable = MutableStateFlow(ReformToolsState())
    private val progressMutable = MutableStateFlow(ReformProgress())

    val selectedApp: StateFlow<ReformApp?> = selected.asStateFlow()
    val scopeUi: StateFlow<ReformScopeUi> = scopeMutable.asStateFlow()
    val tools: StateFlow<ReformToolsState> = toolsMutable.asStateFlow()
    val progress: StateFlow<ReformProgress> = progressMutable.asStateFlow()

    private var installing = false
    private var blockedNote: String? = null
    private var running: Job? = null
    private var paused = false

    val hasReform: Boolean get() = store.reformId != null

    fun select(app: ReformApp) {
        selected.value = app
        scopeMutable.value = ReformScopeUi()
    }

    // ---------- Escopo ----------

    /** Copia o APK para a pasta compartilhada, trava o escopo no motor e só então chama [onLocked]. */
    fun lockScope(plan: ReformPlan, onLocked: () -> Unit) {
        if (scopeMutable.value.busy) return
        val uri = plan.apkUri
        if (uri == null) {
            scopeMutable.value = ReformScopeUi(error = "Este app veio da lista e ainda não tem arquivo. Volte e use Escolher APK.")
            return
        }
        scopeMutable.value = ReformScopeUi(busy = true)
        scope.launch {
            try {
                val path = sharer.share(uri, plan.appLabel)
                val created = gateway.create(path, plan.scope.wire(), plan.fidelityTargetPercent, plan.carMode)
                store.reformId = created.id
                paused = false
                progressMutable.value = ReformProgress(started = true, locked = true, app = plan.appLabel, mode = plan.scope.title)
                scopeMutable.value = ReformScopeUi()
                onLocked()
                refreshProgress()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                scopeMutable.value = ReformScopeUi(error = messageFor(e, "travar o escopo"), engineDown = e is EngineUnreachableException)
            }
        }
    }

    // ---------- Ferramentas ----------

    /**
     * Instalar o que falta das ferramentas. Com reforma em andamento pede o passo `tools` (o motor recusa até a pessoa
     * aceitar o plano); sem reforma pede o plano direto. Nos dois casos nada é baixado antes do aceite (C4).
     * Devolve false só quando não há como pedir confirmação (sem [setup]/[consent]) nem reforma: quem chamou mostra
     * o caminho em Conexões. Nunca vira pedido de instalação ao chat: esse caminho não passaria pela barreira.
     */
    fun installTools(): Boolean {
        val id = store.reformId
        if (id == null && installFlow == null) return false
        scope.launch {
            if (id != null) {
                runInstall(id)
                loadProgress(id)
            } else if (!installing) {
                askToInstall(null, toolsMutable.value)
            }
        }
        return true
    }

    private suspend fun runInstall(id: String) {
        if (installing) return
        installing = true
        val before = toolsMutable.value
        toolsMutable.value = before.copy(problem = null, waitMessage = null, tools = runningTools(before))
        try {
            runCatching { beforeInstall() }
            toolsMutable.value = toolsFrom(gateway.runStep(id, ReformStep.Tools), before)
        } catch (e: CancellationException) {
            toolsMutable.value = before
            throw e
        } catch (e: AuraEngineException) {
            if (e.code == "consent_required") askToInstall(id, before) else toolsMutable.value = before.copy(problem = problemFor(e), waitMessage = null)
        } catch (e: Exception) {
            toolsMutable.value = before.copy(problem = problemFor(e), waitMessage = null)
        } finally {
            installing = false
        }
    }

    /**
     * O motor recusou o passo `tools` porque falta instalar e a pessoa ainda não decidiu. Pede o plano (C4) e mostra
     * "Instalar programas necessários?". Nada é baixado antes do aceite; recusar mantém o escopo e as telas.
     */
    private fun askToInstall(id: String?, before: ReformToolsState) {
        val flow = installFlow
        if (flow == null) {
            toolsMutable.value = before.copy(
                waitMessage = null,
                problem = ReformProblem("Nada foi instalado", InstallText.NO_GATE + " Seu escopo continua guardado.", ReformFix.Retry),
            )
            return
        }
        toolsMutable.value = before.copy(problem = null, waitMessage = "Falta a sua decisão para instalar as ferramentas. Nada foi baixado.")
        blockedNote = "Faltam as ferramentas de análise. Nada foi instalado; toque em Retomar etapas para ver o pedido."
        progressMutable.update { it.copy(blocked = blockedNote) }
        flow.start(
            target = "reform_tools", reason = "Preparar as ferramentas da reforma de apps",
            onStatus = { item ->
                toolsMutable.value = toolsMutable.value.copy(waitMessage = "Instalando as ferramentas" + (item?.let { " · $it" } ?: "") + ". Pode sair desta tela.")
            },
        ) { outcome -> scope.launch { afterInstall(id, before, outcome) } }
    }

    private suspend fun afterInstall(id: String?, before: ReformToolsState, outcome: InstallFlow.Outcome) {
        fun stop(title: String, body: String, fix: ReformFix = ReformFix.Retry) {
            toolsMutable.value = before.copy(waitMessage = null, problem = ReformProblem(title, body, fix))
        }
        when (outcome) {
            InstallFlow.Outcome.Completed, InstallFlow.Outcome.NotNeeded -> {
                blockedNote = null
                progressMutable.update { it.copy(blocked = null) }
                if (id != null) {
                    runInstall(id)
                    loadProgress(id)
                } else {
                    // Sem reforma ainda: o plano aceito cobriu tudo que faltava.
                    toolsMutable.value = before.copy(
                        tools = REFORM_REQUIRED_TOOLS.map { it.copy(status = ReformToolStatus.Ready, percent = 100) },
                        problem = null, waitMessage = null,
                    )
                }
            }
            InstallFlow.Outcome.Declined -> stop("Nada foi instalado", "Você pode continuar usando o app. Seu escopo continua guardado; toque em Instalar o que falta quando quiser.")
            is InstallFlow.Outcome.Rejected -> stop("Nada foi instalado", InstallText.rejected(outcome.reason))
            is InstallFlow.Outcome.Failed -> stop(
                "A instalação parou em ${outcome.plan.progressItem ?: "uma ferramenta"}",
                "Seu trabalho foi guardado. Toque em Instalar o que falta para revisar e tentar de novo.",
            )
            is InstallFlow.Outcome.NeedsNewPlan -> stop("A instalação precisa de outro caminho", "O caminho padrão não funcionou. Toque em Instalar o que falta para ver o pedido de novo.")
            InstallFlow.Outcome.WaitingWifi -> toolsMutable.value = before.copy(
                problem = null,
                waitMessage = "Estou em dados móveis. Conecte ao Wi-Fi e toque em Tentar de novo: nada foi baixado.",
            )
            InstallFlow.Outcome.Cancelled -> stop("Instalação cancelada", "O que já foi instalado continua no Termux. Toque em Instalar o que falta para continuar.")
            InstallFlow.Outcome.TimedOut -> stop("A instalação está demorando", "Confira em Conexões mais tarde ou toque em Tentar de novo.")
            is InstallFlow.Outcome.Unavailable -> stop("O motor está desligado", outcome.message, ReformFix.StartEngine)
        }
    }

    private fun runningTools(current: ReformToolsState): List<ReformTool> {
        var first = true
        return current.rows.filter { it.id in REQUIRED_IDS }.map { tool ->
            if (tool.status == ReformToolStatus.Ready) tool
            else tool.copy(status = if (first) ReformToolStatus.Installing else ReformToolStatus.Queued, percent = null)
                .also { first = false }
        }
    }

    private fun toolsFrom(result: ReformStepResult, before: ReformToolsState): ReformToolsState {
        val done = if (result.alreadyDone) REQUIRED_IDS.toSet() else result.doneSteps.toSet()
        val rows = REFORM_REQUIRED_TOOLS.map { tool ->
            when {
                tool.id in done -> tool.copy(status = ReformToolStatus.Ready, percent = 100)
                result.waitingWifi && tool.id == result.waitingStep -> tool.copy(status = ReformToolStatus.WaitingWifi)
                else -> tool.copy(status = ReformToolStatus.Missing)
            }
        }
        val note = if (result.waitingWifi) {
            "Aguardando Wi-Fi" + (result.waitingStep?.let { s -> REFORM_REQUIRED_TOOLS.firstOrNull { it.id == s }?.label }?.let { " no passo $it" } ?: "")
        } else "Ferramentas prontas"
        return ReformToolsState(
            tools = rows,
            session = listOf(ReformSessionEntry(clockLabel(), note, active = result.waitingWifi)) + before.session.take(4),
            problem = null,
            waitMessage = if (result.waitingWifi) result.message ?: "Estou em dados móveis. Conecte ao Wi-Fi e toque em Tentar de novo: continuo de onde parei." else null,
            downloadNotices = result.notices,
        )
    }

    // ---------- Progresso (Reversão / Projeto) ----------

    /** GET /v1/reform/{id}. Sem reforma: estado vazio. Falha: mantém o que já havia e mostra o erro com "Tentar de novo". */
    fun refreshProgress() {
        val id = store.reformId ?: run { progressMutable.value = ReformProgress(); return }
        scope.launch { loadProgress(id) }
    }

    private suspend fun loadProgress(id: String) {
        try {
            progressMutable.value = reformProgressFrom(gateway.summary(id), selected.value?.label, paused).copy(blocked = blockedNote)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            progressMutable.update { it.copy(started = true, error = messageFor(e, "ler a reforma")) }
        }
    }

    /** Roda o próximo passo que o app sabe pedir (tools, inventory, coverage). Repetir é seguro: o motor é idempotente. */
    fun continueReform() {
        val id = store.reformId ?: return
        paused = false
        running?.cancel()
        running = scope.launch {
            try {
                progressMutable.update { it.copy(paused = false, error = null) }
                val done = gateway.summary(id).stepsDone.toSet()
                val next = AUTO_STEPS.firstOrNull { it.wire !in done }
                if (next == ReformStep.Tools) runInstall(id) else if (next != null) gateway.runStep(id, next)
                loadProgress(id)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                progressMutable.update { it.copy(started = true, error = messageFor(e, "continuar a reforma")) }
            }
        }
    }

    /** Para de pedir passos novos. O que já foi feito fica no ledger; "Retomar" continua de onde parou. */
    fun pause() {
        paused = true
        running?.cancel()
        progressMutable.update { it.copy(paused = true) }
    }

    // ---------- Erros ----------

    private fun problemFor(e: Exception): ReformProblem = when (e) {
        is EngineUnreachableException ->
            ReformProblem("O motor está desligado", e.message ?: "Abra o Termux e inicie o motor.", ReformFix.StartEngine)
        else -> ReformProblem("Não deu para instalar", messageFor(e, "instalar as ferramentas"), ReformFix.Retry)
    }

    private fun messageFor(e: Exception, doing: String): String = when (e) {
        is EngineUnreachableException -> e.message ?: "O motor não responde. Abra o Termux e inicie o motor."
        is AuraEngineException -> e.message?.takeIf { it.isNotBlank() } ?: "O motor recusou o pedido ao $doing. Tente de novo."
        is IOException -> e.message?.takeIf { it.isNotBlank() } ?: "Falha ao $doing. Tente de novo."
        else -> "Não consegui $doing. Tente de novo."
    }

    private companion object {
        val REQUIRED_IDS = REFORM_REQUIRED_TOOLS.map { it.id }
    }
}

/** Entregue pela MainActivity. Nulo (previews e testes de tela) = as telas abrem como antes, sem motor. */
val LocalReformController = androidx.compose.runtime.staticCompositionLocalOf<ReformController?> { null }
