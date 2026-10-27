package ai.aura.app.ui.screens.settings

import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import ai.aura.app.ui.engine.Capability
import ai.aura.app.ui.engine.EngineEvent
import ai.aura.app.ui.engine.EngineGateway
import ai.aura.app.ui.engine.Gate
import ai.aura.app.ui.engine.ModelsDoc
import ai.aura.app.ui.engine.ModelsResult
import ai.aura.app.ui.engine.POLICY_KIND_KEYS
import ai.aura.app.ui.engine.POLICY_RISK_KEYS
import ai.aura.app.ui.engine.PolicyDoc
import ai.aura.app.ui.engine.PolicyMode
import ai.aura.app.ui.engine.PolicyResult
import ai.aura.app.ui.engine.PolicyScope
import ai.aura.app.ui.engine.PolicyVersion
import ai.aura.app.ui.engine.SpendResult
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

internal enum class SettingsLoad { Loading, Ready, Unavailable }

internal data class SettingsNotice(val ok: Boolean, val text: String)

/** Última rota de modelo informada pelo motor (payload model_used / why). */
internal data class ModelUse(val model: String, val why: String?)

internal const val ENGINE_DOWN_HINT =
    "O motor não respondeu. Abra o Termux, inicie o AURA e tente de novo."

/**
 * Estado da tela Ajustes. Mantém o rascunho (o que o dono editou) separado do salvo (o que o motor
 * confirmou). Nada é dado como salvo sem a versão devolvida pelo motor.
 */
@Stable
internal class SettingsState(
    private val gateway: EngineGateway,
    private val scope: CoroutineScope,
) {
    var load by mutableStateOf(SettingsLoad.Loading); private set
    var unavailableHint by mutableStateOf(ENGINE_DOWN_HINT); private set

    var savedPolicy by mutableStateOf<PolicyDoc?>(null); private set
    var policy by mutableStateOf<PolicyDoc?>(null); private set
    var history by mutableStateOf<List<PolicyVersion>>(emptyList()); private set

    var savedModels by mutableStateOf<ModelsDoc?>(null); private set
    var models by mutableStateOf<ModelsDoc?>(null); private set

    var spend by mutableStateOf<SpendResult?>(null); private set
    var capabilities by mutableStateOf<List<Capability>>(emptyList()); private set
    var lastModelUse by mutableStateOf<ModelUse?>(null); private set

    var notice by mutableStateOf<SettingsNotice?>(null); private set
    var saving by mutableStateOf(false); private set
    var confirmAllowAll by mutableStateOf(false)

    val dirty: Boolean
        get() = (policy != null && policy != savedPolicy) || (models != null && models != savedModels)

    val rootCapability: Capability? get() = capabilities.firstOrNull { it.id == "root" }

    fun reload() {
        scope.launch {
            load = SettingsLoad.Loading
            val p = safe { gateway.policy() }
            val m = safe { gateway.models() }
            if (p is PolicyResult.Ok && m is ModelsResult.Ok) {
                applyPolicy(p)
                applyModels(m)
                load = SettingsLoad.Ready
                spend = safe { gateway.spendSummary() }
                capabilities = safe { gateway.capabilities() } ?: emptyList()
            } else {
                unavailableHint = (p as? PolicyResult.Unavailable)?.reason
                    ?: (m as? ModelsResult.Unavailable)?.reason ?: ENGINE_DOWN_HINT
                load = SettingsLoad.Unavailable
            }
        }
    }

    fun onEvent(e: EngineEvent) {
        e.payload["model_used"]?.let { lastModelUse = ModelUse(it, e.payload["why"]) }
        if ((e.type == "policy.changed" || e.type == "models.changed") && !dirty && !saving && load == SettingsLoad.Ready) {
            scope.launch {
                (safe { gateway.policy() } as? PolicyResult.Ok)?.let { if (it.doc != savedPolicy) applyPolicy(it) }
                (safe { gateway.models() } as? ModelsResult.Ok)?.let { if (it.doc != savedModels) applyModels(it) }
            }
        }
    }

    fun editPolicy(f: (PolicyDoc) -> PolicyDoc) {
        policy?.let { policy = f(it); notice = null }
    }

    fun editModels(f: (ModelsDoc) -> ModelsDoc) {
        models?.let { models = f(it); notice = null }
    }

    fun save() {
        if (saving || !dirty) return
        scope.launch { persist() }
    }

    /** Aplica "Permitir tudo" no rascunho e salva já (a confirmação do diálogo é a autorização). */
    fun confirmAllowAllAndSave() {
        confirmAllowAll = false
        editPolicy { allowAll(it) }
        save()
    }

    fun disallowAll() = editPolicy { disallowAll(it) }

    fun revertTo(v: PolicyVersion) {
        val snap = v.snapshot ?: return
        if (saving) return
        scope.launch {
            saving = true
            when (val r = safe { gateway.savePolicy(snap) }) {
                is PolicyResult.Ok -> {
                    applyPolicy(r)
                    notice = SettingsNotice(true, "Voltei para a versão ${v.version}. O motor aplicou a versão ${r.doc.version}.")
                }
                else -> notice = SettingsNotice(false, failText(r))
            }
            saving = false
        }
    }

    fun stopAll() {
        scope.launch {
            val jobs = safe { gateway.jobs() }
            if (jobs == null) {
                notice = SettingsNotice(false, ENGINE_DOWN_HINT)
                return@launch
            }
            val running = jobs.filter { !it.status.isTerminal }
            var stopped = 0
            for (j in running) if (safe { gateway.cancel(j.id) } != null) stopped++
            notice = when {
                running.isEmpty() -> SettingsNotice(true, "Nada estava rodando.")
                stopped == running.size -> SettingsNotice(true, "Parei $stopped ${if (stopped == 1) "tarefa" else "tarefas"}.")
                else -> SettingsNotice(false, "Parei $stopped de ${running.size}. As outras não responderam; tente de novo.")
            }
        }
    }

    private suspend fun persist() {
        saving = true
        notice = null
        val done = mutableListOf<String>()
        var failure: String? = null
        val p = policy
        if (p != null && p != savedPolicy) {
            val r = safe { gateway.savePolicy(p) }
            if (r is PolicyResult.Ok) {
                applyPolicy(r)
                done.add("política, versão ${r.doc.version}")
            } else {
                failure = failText(r)
            }
        }
        val m = models
        if (failure == null && m != null && m != savedModels) {
            val r = safe { gateway.saveModels(m) }
            if (r is ModelsResult.Ok) {
                applyModels(r)
                done.add("modelos, versão ${r.doc.version}")
            } else {
                failure = failText(r)
            }
        }
        val f = failure
        notice = if (f != null) SettingsNotice(false, f) else SettingsNotice(true, "Salvo. O motor aplicou: ${done.joinToString(" e ")}.")
        saving = false
    }

    private fun applyPolicy(r: PolicyResult.Ok) {
        savedPolicy = r.doc
        policy = r.doc
        if (r.history.isNotEmpty()) history = r.history
    }

    private fun applyModels(r: ModelsResult.Ok) {
        savedModels = r.doc
        models = r.doc
    }

    private fun failText(r: Any?): String = when (r) {
        is PolicyResult.Rejected -> "O motor recusou a política (${r.detail ?: r.code}). Corrija o campo e salve de novo."
        is ModelsResult.Rejected -> "O motor recusou os modelos (${r.detail ?: r.code}). Corrija o campo e salve de novo."
        is PolicyResult.Unavailable -> r.reason ?: ENGINE_DOWN_HINT
        is ModelsResult.Unavailable -> r.reason ?: ENGINE_DOWN_HINT
        else -> ENGINE_DOWN_HINT
    }

    private suspend fun <T> safe(block: suspend () -> T): T? =
        try { block() } catch (c: CancellationException) { throw c } catch (_: Exception) { null }
}

internal fun PolicyDoc.isAllowAll(): Boolean =
    mode == PolicyMode.Total && root.enabled &&
        POLICY_RISK_KEYS.all { byRisk[it] == Gate.Auto } &&
        POLICY_KIND_KEYS.all { byKind[it] == Gate.Auto }

/** Tudo automático, root ligado, qualquer caminho. Não mexe nos limites de gasto do dono. */
internal fun allowAll(p: PolicyDoc): PolicyDoc = p.copy(
    mode = PolicyMode.Total,
    byRisk = POLICY_RISK_KEYS.associateWith { Gate.Auto },
    byKind = POLICY_KIND_KEYS.associateWith { Gate.Auto },
    root = p.root.copy(enabled = true),
    scopes = listOf(PolicyScope("*", Gate.Auto)),
)

/** Desligar volta para um ponto de partida Equilibrado; o dono ajusta cada categoria depois. */
internal fun disallowAll(p: PolicyDoc): PolicyDoc = p.copy(
    mode = PolicyMode.Equilibrado,
    byRisk = p.byRisk + mapOf("spend" to Gate.Ask, "critical" to Gate.Ask),
    byKind = p.byKind + mapOf("root" to Gate.Ask),
    root = p.root.copy(enabled = false),
)
