package ai.aura.app.ui.screens.reform

import ai.aura.app.ui.engine.EngineEvent
import org.json.JSONArray
import kotlin.math.roundToInt

enum class ReformProgressStepState { Done, Running, Pending }

/** [promisedProof] = "Prova:" do plano (só promessa). [proof] = a que o motor anexou ao concluir; sem ela, a tela diz que o motor não informa. */
data class ReformProgressStep(
    val title: String,
    val promisedProof: String? = null,
    val proof: String? = null,
    val state: ReformProgressStepState = ReformProgressStepState.Pending,
    val pct: Int? = null,
)

/**
 * Estado das telas Reformar 3, 4 e 6. Campo nulo = o motor ainda não informou (a tela não inventa).
 * [error] vem do chamador (mensagem do que houve e o que fazer); [started] = chegou algum evento da reforma.
 */
data class ReformProgress(
    val started: Boolean = false,
    val locked: Boolean = false,
    val app: String? = null,
    val mode: String? = null,
    val reversalPct: Int? = null,
    val rebuildPct: Int? = null,
    val fidelityPct: Int? = null,
    val steps: List<ReformProgressStep> = emptyList(),
    val checkpointAtMillis: Long? = null,
    val paused: Boolean = false,
    val error: String? = null,
    /** A análise terminou (cobertura medida). A reconstrução ainda não existe neste fluxo: a tela diz isso. */
    val analysisDone: Boolean = false,
    /** Algo impede a próxima etapa sem ser erro (ex.: falta decidir sobre instalar). Texto com a ação. */
    val blocked: String? = null,
)

private fun Map<String, String>.text(key: String): String? =
    this[key]?.trim()?.takeIf { it.isNotEmpty() && it != "null" }

/** 0..100 ou nulo: texto ilegível ou fora da faixa é "não informado", nunca zero. */
private fun Map<String, String>.pct(key: String): Int? =
    text(key)?.toDoubleOrNull()?.takeIf { it.isFinite() }?.roundToInt()?.takeIf { it in 0..100 }

private fun List<ReformProgressStep>.change(index: Int, edit: (ReformProgressStep) -> ReformProgressStep) =
    mapIndexed { i, step -> if (i == index) edit(step) else step }

private fun List<ReformProgressStep>.runningOr(fallback: ReformProgressStepState?) =
    indexOfFirst { it.state == ReformProgressStepState.Running }.takeIf { it >= 0 }
        ?: fallback?.let { s -> indexOfFirst { it.state == s } } ?: -1

private fun planSteps(raw: String?): List<ReformProgressStep> {
    val array = raw?.let { runCatching { JSONArray(it) }.getOrNull() } ?: return emptyList()
    return (0 until array.length()).mapNotNull { i ->
        val item = array.optJSONObject(i)
        val title = (item?.optString("title") ?: array.optString(i)).trim().takeIf { it.isNotEmpty() && it != "null" }
        title?.let { ReformProgressStep(it, promisedProof = item?.optString("proof")?.trim()?.takeIf { p -> p.isNotEmpty() && p != "null" }) }
    }
}

/**
 * Lê os eventos do contrato (Contrato.dc.html) na ordem em que chegaram: reform.scope.locked, reform.progress,
 * plan.proposed, step.started/progress/done e task.interrupted. O que o motor não mandar fica nulo.
 * Passe só os eventos do job da reforma.
 */
fun reformProgressFrom(events: List<EngineEvent>): ReformProgress = events.fold(ReformProgress()) { s, e ->
    val p = e.payload
    when (e.type) {
        "reform.scope.locked" -> s.copy(started = true, locked = true, app = p.text("app"), mode = p.text("mode"))
        "reform.progress" -> s.copy(
            started = true, paused = false,
            reversalPct = p.pct("reversalPct"), rebuildPct = p.pct("rebuildPct"), fidelityPct = p.pct("fidelityPct"),
            checkpointAtMillis = if (p.text("checkpointId") != null) e.timestampMillis else s.checkpointAtMillis,
        )
        "plan.proposed" -> s.copy(started = true, steps = planSteps(p["steps"]))
        "step.started" -> {
            val title = p.text("title")
            val byTitle = s.steps.indexOfFirst { it.title == title && it.state != ReformProgressStepState.Done }
            val at = if (byTitle >= 0) byTitle else s.steps.indexOfFirst { it.state == ReformProgressStepState.Pending }
            s.copy(
                started = true, paused = false,
                steps = when {
                    at >= 0 -> s.steps.change(at) { it.copy(state = ReformProgressStepState.Running, pct = null) }
                    title != null -> s.steps + ReformProgressStep(title, state = ReformProgressStepState.Running)
                    else -> s.steps
                },
            )
        }
        "step.progress" -> s.copy(
            started = true, paused = false,
            steps = s.steps.runningOr(null).let { at -> if (at >= 0) s.steps.change(at) { it.copy(pct = p.pct("pct")) } else s.steps },
        )
        "step.done" -> s.copy(
            started = true,
            steps = s.steps.runningOr(ReformProgressStepState.Pending).let { at ->
                if (at >= 0) s.steps.change(at) { it.copy(state = ReformProgressStepState.Done, pct = null, proof = p.text("proof")) } else s.steps
            },
        )
        "task.interrupted" -> s.copy(
            started = true, paused = true,
            checkpointAtMillis = if (p.text("checkpointId") != null) e.timestampMillis else s.checkpointAtMillis,
        )
        else -> s
    }
}
