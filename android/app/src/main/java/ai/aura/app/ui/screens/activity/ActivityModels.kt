package ai.aura.app.ui.screens.activity

import ai.aura.app.ui.components.StatusKind
import ai.aura.app.ui.engine.EngineEvent
import ai.aura.app.ui.engine.Job
import ai.aura.app.ui.engine.JobRisk
import ai.aura.app.ui.engine.JobStatus
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

enum class ActivityFilter(val label: String) {
    All("Tudo"), NeedsYou("Precisa de você"), Running("Rodando"), Done("Pronto"), Stopped("Parou"),
}

/** Número do selo laranja na aba Atividade: o que espera uma decisão do dono. */
fun activityBadgeCount(jobs: List<Job>): Int = jobs.count { it.status == JobStatus.AwaitingApproval }

fun Job.matches(f: ActivityFilter): Boolean = when (f) {
    ActivityFilter.All -> true
    ActivityFilter.NeedsYou -> status == JobStatus.AwaitingApproval
    ActivityFilter.Running -> status == JobStatus.Running || status == JobStatus.Queued || status == JobStatus.WaitingEngine
    ActivityFilter.Done -> status == JobStatus.Completed
    ActivityFilter.Stopped -> status == JobStatus.Failed || status == JobStatus.Cancelled ||
        status == JobStatus.Interrupted
}

fun Job.statusKind(): StatusKind = when (status) {
    JobStatus.Queued, JobStatus.Cancelled -> StatusKind.Neutral
    JobStatus.AwaitingApproval, JobStatus.Interrupted, JobStatus.WaitingEngine -> StatusKind.NeedsYou
    JobStatus.Running -> StatusKind.Info
    JobStatus.Completed -> StatusKind.Ready
    JobStatus.Failed -> StatusKind.Risk
}

fun Job.statusLabel(): String = when (status) {
    JobStatus.Queued -> "Na fila"
    JobStatus.WaitingEngine -> "Aguardando o motor"
    JobStatus.AwaitingApproval -> "Aprovar"
    JobStatus.Running -> "Rodando"
    JobStatus.Completed -> "Pronto"
    JobStatus.Failed -> "Falhou"
    JobStatus.Cancelled -> "Cancelado"
    JobStatus.Interrupted -> "Interrompido"
}

/** Consequência em português, visível ANTES de aprovar (invariante 6). */
fun JobRisk.consequence(): String = when (this) {
    JobRisk.Read -> "Só lê informações. Não altera nada."
    JobRisk.Write -> "Vai alterar arquivos ou o aparelho."
    JobRisk.Spend -> "Pode gastar dinheiro."
    JobRisk.Critical -> "Pode apagar algo e não dá para desfazer."
}

/** (o que aconteceu, o que fazer) para um error.code; código desconhecido nunca é adivinhado. */
fun friendlyError(code: String?): Pair<String, String> = when (code) {
    null -> "O AURA não informou o motivo da falha." to "Abra os detalhes técnicos e tente de novo."
    "auth_required" -> "O serviço recusou o acesso." to "Entre de novo na conta em Dispositivos > Conexões."
    "tool_unavailable", "tool_missing", "not_installed" ->
        "Uma ferramenta necessária não está disponível." to "Instale ou reconecte em Dispositivos > Conexões."
    "network_unavailable", "offline" -> "Sem internet." to "Conecte-se e tente de novo."
    "timeout" -> "A tarefa demorou demais e parou." to "Tente de novo; se repetir, veja os detalhes técnicos."
    "disk_full", "no_space" -> "Faltou espaço no celular." to "Libere espaço e tente de novo."
    "approval_denied" -> "A aprovação foi recusada." to "Nada foi feito."
    else -> "A tarefa falhou (código $code)." to "Veja os detalhes técnicos antes de tentar de novo."
}

/** Texto de evidência por estado terminal, sempre baseado em status/result/error do engine. */
fun Job.evidence(): Pair<String, String?> = when (status) {
    JobStatus.Completed -> (result ?: "Concluído, mas o AURA não devolveu texto de resultado.") to null
    JobStatus.Failed -> friendlyError(errorCode)
    JobStatus.Cancelled -> "Esta tarefa foi cancelada antes de terminar." to
        (result?.let { "Resultado parcial: $it" } ?: "Nada foi marcado como concluído.")
    JobStatus.Interrupted -> "O trabalho parou no meio (o Android encerrou o AURA ou o app reiniciou)." to
        "Nada foi repetido sozinho. Confira o que já foi feito e peça de novo se precisar."
    JobStatus.WaitingEngine -> "O pedido está guardado neste celular. O motor está desligado e ainda não recebeu." to
        "Ele segue sozinho quando o motor ligar. Se não quiser mais, cancele o pedido."
    else -> risk.consequence() to null
}

private val timeFmt = ThreadLocal.withInitial { SimpleDateFormat("HH:mm", Locale("pt", "BR")) }
private val dayFmt = ThreadLocal.withInitial { SimpleDateFormat("dd/MM HH:mm", Locale("pt", "BR")) }

private fun startOfDay(millis: Long): Long = Calendar.getInstance().run {
    timeInMillis = millis
    set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
    timeInMillis
}

fun formatTime(millis: Long, now: Long): String {
    val today = startOfDay(now)
    return when {
        millis >= today -> timeFmt.get()!!.format(Date(millis))
        millis >= today - 86_400_000L -> "ontem " + timeFmt.get()!!.format(Date(millis))
        else -> dayFmt.get()!!.format(Date(millis))
    }
}

/** Histórico agrupado: Hoje, Ontem, Antes (ordem já vem do mais novo). */
fun groupHistory(jobs: List<Job>, now: Long): List<Pair<String, List<Job>>> {
    val today = startOfDay(now)
    val groups = linkedMapOf<String, MutableList<Job>>("Hoje" to mutableListOf(), "Ontem" to mutableListOf(), "Antes" to mutableListOf())
    jobs.sortedByDescending { it.createdAtMillis }.forEach {
        val key = when {
            it.createdAtMillis >= today -> "Hoje"
            it.createdAtMillis >= today - 86_400_000L -> "Ontem"
            else -> "Antes"
        }
        groups.getValue(key).add(it)
    }
    return groups.filterValues { it.isNotEmpty() }.map { it.key to it.value }
}

/** Progresso real: só existe se o engine mandou step/total no último evento do job. */
data class StepProgress(val step: Int, val total: Int, val label: String?)

fun progressOf(jobId: String, events: List<EngineEvent>): StepProgress? {
    val p = events.lastOrNull { it.jobId == jobId && it.payload.containsKey("step") && it.payload.containsKey("total") }
        ?.payload ?: return null
    val step = p["step"]?.toIntOrNull() ?: return null
    val total = p["total"]?.toIntOrNull()?.takeIf { it > 0 } ?: return null
    return StepProgress(step.coerceIn(0, total), total, p["label"])
}

/** Linhas de log (detalhes técnicos), só mostradas atrás de expansão. */
fun technicalLines(job: Job, events: List<EngineEvent>, now: Long): List<String> {
    val head = buildList {
        add("job ${job.id}")
        add("request ${job.requestId}")
        add("kind ${job.kind} · status ${job.status.wire} · risk ${job.risk.wire}")
        job.errorCode?.let { add("error.code $it") }
    }
    val log = events.filter { it.jobId == job.id }.map {
        val extra = it.payload.entries.joinToString(" ") { e -> "${e.key}=${e.value}" }
        "#${it.id} ${formatTime(it.timestampMillis, now)} ${it.type} $extra".trim()
    }
    return head + log
}
