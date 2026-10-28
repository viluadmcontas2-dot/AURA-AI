package ai.aura.app.ui.screens.projects

import ai.aura.app.offline.LocalStore
import ai.aura.app.ui.engine.Job
import ai.aura.app.ui.engine.JobStatus
import ai.aura.app.ui.screens.readList
import ai.aura.app.ui.screens.writeList
import org.json.JSONArray
import org.json.JSONObject

/*
 * Modelos locais da aba Projetos. O engine ainda não tem o conceito de "projeto" (só jobs), então
 * o projeto vive no aparelho e liga-se a jobs pelo id; o status vem SEMPRE do job (nunca inventado).
 */

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

internal fun Project.toJson(): JSONObject = JSONObject()
    .put("id", id).put("name", name).put("goal", goal).put("platform", platform)
    .put("screens", JSONArray(screens)).put("doneWhen", JSONArray(doneWhen)).put("deliveries", JSONArray(deliveries))
    .put("repo", repo).put("branch", branch).put("jobIds", JSONArray(jobIds)).put("archived", archived)

/** Tolerante: campo ausente ou de tipo errado vira o padrão; sem id o projeto é descartado. Nunca lança. */
internal fun projectFromJson(o: JSONObject): Project? {
    fun list(key: String): List<String> {
        val a = o.optJSONArray(key) ?: return emptyList()
        return (0 until a.length()).map { a.optString(it, "") }.filter { it.isNotEmpty() }
    }
    val id = o.optString("id", "")
    if (id.isEmpty()) return null
    return Project(
        id = id, name = o.optString("name", ""), goal = o.optString("goal", ""), platform = o.optString("platform", ""),
        screens = list("screens"), doneWhen = list("doneWhen"), deliveries = list("deliveries"),
        repo = o.optString("repo", ""), branch = o.optString("branch", "main"),
        jobIds = list("jobIds"), archived = o.optBoolean("archived", false),
    )
}

/** Projetos moram no disco (FN-12): sobrevivem à morte do processo e funcionam sem motor. */
internal fun LocalStore.loadProjects(): List<Project> = readList("projects", ::projectFromJson)

internal fun LocalStore.saveProjects(projects: List<Project>) = writeList("projects", projects.map { it.toJson() })

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
