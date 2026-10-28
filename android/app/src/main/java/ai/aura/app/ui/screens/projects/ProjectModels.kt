package ai.aura.app.ui.screens.projects

import ai.aura.app.ui.engine.Job
import ai.aura.app.ui.engine.JobStatus

/*
 * Modelos locais da aba Projetos. O engine ainda não tem o conceito de "projeto" (só jobs), então
 * o projeto vive no aparelho e liga-se a jobs pelo id; o status vem SEMPRE do job (nunca inventado).
 */

private const val SEP = "\u001F"

data class Project(
    val id: String,
    val name: String,
    val goal: String,
    val platform: String,
    val screens: List<String> = emptyList(),
    val doneWhen: List<String> = emptyList(),
    val deliveries: List<String> = emptyList(),
    val repo: String,
    val branch: String = "main",
    val jobIds: List<String> = emptyList(),
    val archived: Boolean = false,
)

/** Tudo em String para sobreviver a rotação via rememberSaveable (sem Parcelable/serialização extra). */
internal fun Project.toSaved(): List<String> = listOf(
    id, name, goal, platform, screens.joinToString(SEP), doneWhen.joinToString(SEP),
    deliveries.joinToString(SEP), repo, branch, jobIds.joinToString(SEP), if (archived) "1" else "0",
)

internal fun projectFromSaved(s: List<Any?>): Project {
    fun list(i: Int) = (s[i] as String).split(SEP).filter { it.isNotEmpty() }
    return Project(
        id = s[0] as String, name = s[1] as String, goal = s[2] as String, platform = s[3] as String,
        screens = list(4), doneWhen = list(5), deliveries = list(6), repo = s[7] as String,
        branch = s[8] as String, jobIds = list(9), archived = s[10] == "1",
    )
}

enum class ProjectTab(val label: String) { Now("Agora"), Triage("Triagem"), Archive("Arquivo") }

enum class PhaseKind { Running, NeedsYou, Ready, Failed, Queued, Idle, Stopped }

/** Estado exibível do projeto, derivado do job mais recente ligado a ele. */
data class ProjectPhase(val kind: PhaseKind, val label: String, val subtitle: String, val job: Job?)

internal fun phaseOf(project: Project, jobs: Map<String, Job>): ProjectPhase {
    val job = project.jobIds.mapNotNull { jobs[it] }.maxByOrNull { it.createdAtMillis }
        ?: return ProjectPhase(PhaseKind.Idle, "Sem tarefas", "Peça a primeira tarefa ao AURA", null)
    return when (job.status) {
        JobStatus.Running -> ProjectPhase(PhaseKind.Running, "Rodando", job.title, job)
        JobStatus.AwaitingApproval -> ProjectPhase(PhaseKind.NeedsYou, "Precisa de você", "Aprovar: ${job.title}", job)
        JobStatus.Completed -> ProjectPhase(PhaseKind.Ready, "Pronto", job.result ?: job.title, job)
        JobStatus.Failed -> ProjectPhase(PhaseKind.Failed, "Falhou", "Toque para ver o que fazer", job)
        JobStatus.Queued -> ProjectPhase(PhaseKind.Queued, "Na fila", job.title, job)
        JobStatus.WaitingEngine -> ProjectPhase(PhaseKind.Queued, "Aguardando o motor", job.title, job)
        JobStatus.Cancelled, JobStatus.Interrupted -> ProjectPhase(PhaseKind.Stopped, "Interrompido", job.title, job)
    }
}

internal fun tabOf(project: Project, phase: ProjectPhase): ProjectTab = when {
    project.archived -> ProjectTab.Archive
    phase.kind == PhaseKind.Idle || phase.kind == PhaseKind.Queued -> ProjectTab.Triage
    else -> ProjectTab.Now
}

/** Mensagem que diz o que fazer (nunca o código cru do engine). */
internal fun friendlyError(code: String?): String = when (code) {
    "auth_required" -> "Entre na sua conta do GitHub nos Ajustes e tente de novo."
    "tool_unavailable" -> "Esta ferramenta não está instalada neste aparelho. Instale pelo Termux e tente de novo."
    else -> "Não deu certo. Tente de novo; se repetir, veja os detalhes na aba Atividade."
}

// ---- Artefatos ----

enum class ArtifactKind(val filter: String) {
    Apk("APKs"), Document("Documentos"), Image("Imagens"), Report("Relatórios"),
}

/** Prova ligada a um resultado (regra: resultado sem prova não é mostrado como pronto). */
data class Proof(val text: String, val ok: Boolean = true)

data class ArtifactVersion(val label: String, val current: Boolean)

data class Artifact(
    val id: String,
    val projectId: String?,
    val name: String,
    val kind: ArtifactKind,
    val subtitle: String,
    /** 0..1 enquanto o AURA ainda gera; null quando já existe. */
    val generating: Float? = null,
    val proofs: List<Proof> = emptyList(),
    val sizeLabel: String? = null,
    val whenLabel: String? = null,
    val sha256: String? = null,
    val sourceUrl: String? = null,
    val origin: List<Pair<String, String>> = emptyList(),
    val versions: List<ArtifactVersion> = emptyList(),
)

internal fun String.shortHash(): String = if (length <= 12) this else take(4) + "…" + takeLast(4)
