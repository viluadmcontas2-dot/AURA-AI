package ai.aura.app.ui.screens.projects

import android.content.ActivityNotFoundException
import android.content.Intent
import androidx.activity.compose.BackHandler
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.tooling.preview.Preview
import ai.aura.app.offline.LocalStore
import ai.aura.app.ui.engine.EngineGateway
import ai.aura.app.ui.engine.Job
import ai.aura.app.ui.engine.JobRequest
import ai.aura.app.ui.screens.rememberAuraStore
import ai.aura.app.ui.theme.AuraTheme
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID

private object Route {
    const val List = "list"
    const val Idea = "idea"
    const val Summary = "summary"
    const val Detail = "detail"
    const val Artifacts = "artifacts"
    const val Artifact = "artifact"
}

private fun createPrompt(p: Project) = "Criar repositório privado ${p.repo} no GitHub"

/**
 * Navegação por estado local (sem navigation-compose) entre: lista, ideia, resumo, detalhe,
 * artefatos e detalhe do artefato. Dados: projetos locais ligados a jobs do [EngineGateway];
 * status SEMPRE vem do job. [initialArtifacts] é o ponto de ligação para artefatos reais
 * (o contrato do engine ainda não os expõe), por isso vazio em produção.
 */
@Composable
internal fun ProjectsHost(
    gateway: EngineGateway,
    modifier: Modifier = Modifier,
    initialProjects: List<Project> = emptyList(),
    initialArtifacts: List<Artifact> = emptyList(),
    store: LocalStore = rememberAuraStore(),
) {
    var route by rememberSaveable { mutableStateOf(Route.List) }
    var projectId by rememberSaveable { mutableStateOf("") }
    var artifactId by rememberSaveable { mutableStateOf("") }
    var artifactScope by rememberSaveable { mutableStateOf("") } // "" = todos os projetos
    var idea by rememberSaveable { mutableStateOf("") }
    var answer by rememberSaveable { mutableStateOf("") }
    var draftId by rememberSaveable { mutableStateOf(UUID.randomUUID().toString()) }
    var projects by remember { mutableStateOf(initialProjects) }
    var loaded by remember { mutableStateOf(false) }
    val artifacts = remember(initialArtifacts) { initialArtifacts }

    // Projetos moram no disco (FN-12), lidos e gravados fora da thread principal; sem motor continuam aqui.
    LaunchedEffect(store) {
        val saved = withContext(Dispatchers.IO) { store.loadProjects() }
        if (saved.isNotEmpty()) projects = saved
        loaded = true
    }
    LaunchedEffect(projects, loaded) {
        if (loaded) withContext(Dispatchers.IO + NonCancellable) { store.saveProjects(projects) }
    }

    var jobs by remember { mutableStateOf<Map<String, Job>>(emptyMap()) }
    var loadError by remember { mutableStateOf<String?>(null) }
    var reload by remember { mutableIntStateOf(0) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var notice by remember { mutableStateOf<String?>(null) }
    var needsApproval by remember { mutableStateOf(true) } // seguro por padrão: assume que pede aprovação

    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    val uriHandler = LocalUriHandler.current

    suspend fun refreshJobs() { jobs = gateway.jobs().associateBy { it.id } }

    LaunchedEffect(gateway, reload) {
        try {
            refreshJobs(); loadError = null
            gateway.events.collect {
                // Falha pontual ao atualizar não pode encerrar a escuta de eventos.
                try { refreshJobs(); loadError = null } catch (e: CancellationException) { throw e } catch (e: Exception) { /* próximo evento tenta de novo */ }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            loadError = "Não consegui falar com o AURA neste aparelho. Confira se o Termux está ligado e tente de novo."
        }
    }

    val fullIdea = if (answer.isBlank()) idea.trim() else idea.trim().trimEnd('.') + ". " + answer.trim()
    val draft = remember(fullIdea, draftId) { IdeaOrganizer.organize(fullIdea, draftId) }
    val question = if (answer.isBlank()) IdeaOrganizer.missingQuestion(idea) else null
    LaunchedEffect(draft.repo, route) {
        if (route == Route.Summary) {
            needsApproval = try { gateway.route(createPrompt(draft)).risk.needsApproval } catch (e: CancellationException) { throw e } catch (e: Exception) { true }
        }
    }

    fun go(r: String) { route = r; error = null; notice = null }

    fun runAction(failure: String, block: suspend () -> Unit) {
        if (busy) return // síncrono: o 2º toque antes do launch rodar não duplica o pedido
        busy = true; error = null
        scope.launch {
            try { block() } catch (e: CancellationException) { throw e } catch (e: Exception) { error = failure } finally { busy = false }
        }
    }

    /** Pede ao engine (route -> submit) e liga o job ao projeto. Nada roda sem aprovação quando há risco. */
    suspend fun ask(project: Project, prompt: String, kind: String? = null) {
        val request = if (kind != null) JobRequest(kind, prompt) else gateway.route(prompt).request
        val job = gateway.submit("req_" + UUID.randomUUID(), request)
        projects = projects.map { if (it.id == project.id) it.copy(jobIds = it.jobIds + job.id) else it }
        refreshJobs()
    }

    val project = projects.firstOrNull { it.id == projectId }
    val artifact = artifacts.firstOrNull { it.id == artifactId }

    fun back() = when (route) {
        Route.Idea -> go(Route.List)
        Route.Summary -> go(Route.Idea)
        Route.Detail -> go(Route.List)
        Route.Artifacts -> go(if (artifactScope.isNotEmpty() && project != null) Route.Detail else Route.List)
        Route.Artifact -> go(Route.Artifacts)
        else -> Unit
    }
    BackHandler(enabled = route != Route.List) { back() }

    when {
        route == Route.Idea -> ProjectIdeaScreen(
            idea = idea, onIdeaChange = { idea = it },
            onOrganize = { answer = ""; go(Route.Summary) }, onBack = ::back, modifier = modifier,
        )
        route == Route.Summary -> ProjectSummaryScreen(
            idea = idea.trim(), draft = draft, question = question, needsApproval = needsApproval, busy = busy, error = error,
            onAnswer = { answer = it },
            onCreate = {
                runAction("O projeto foi salvo neste celular, mas o pedido ao AURA não saiu. Escreva a primeira tarefa quando o motor voltar.") {
                    // Salva antes de falar com o motor: sem motor o projeto existe do mesmo jeito.
                    projects = projects.filter { it.id != draft.id } + draft
                    projectId = draft.id; idea = ""; answer = ""; draftId = UUID.randomUUID().toString()
                    go(Route.Detail)
                    // draft.id é a chave de idempotência: repetir o toque não duplica o job.
                    val job = gateway.submit("req_proj_" + draft.id, JobRequest("github", createPrompt(draft)))
                    projects = projects.map { if (it.id == draft.id) it.copy(jobIds = (it.jobIds + job.id).distinct()) else it }
                    refreshJobs()
                }
            },
            onAdjust = ::back, modifier = modifier,
        )
        route == Route.Detail && project != null -> {
            val phase = phaseOf(project, jobs)
            ProjectDetailScreen(
                project = project, phase = phase,
                jobs = project.jobIds.mapNotNull { jobs[it] }.sortedByDescending { it.createdAtMillis },
                artifacts = artifacts.filter { it.projectId == project.id },
                busy = busy, error = error, onBack = ::back,
                onPrimary = {
                    val job = phase.job ?: return@ProjectDetailScreen
                    if (phase.kind == PhaseKind.NeedsYou) runAction("Não consegui aprovar. Toque em Aprovar tarefa para tentar de novo.") { gateway.approve(job.id); refreshJobs() }
                    else runAction("Não consegui reenviar. Toque em Tentar tarefa de novo.") { ask(project, job.title, job.kind) }
                },
                onSend = { text -> runAction("Não consegui enviar o pedido. Escreva de novo e toque em enviar.") { ask(project, text) } },
                onToggleArchive = { projects = projects.map { if (it.id == project.id) it.copy(archived = !it.archived) else it } },
                onOpenArtifact = { artifactId = it; artifactScope = project.id; go(Route.Artifact) },
                onOpenArtifacts = { artifactScope = project.id; go(Route.Artifacts) },
                modifier = modifier,
            )
        }
        route == Route.Artifacts -> {
            val scoped = if (artifactScope.isEmpty()) artifacts else artifacts.filter { it.projectId == artifactScope }
            ArtifactsScreen(
                scopeLabel = if (artifactScope.isEmpty()) "Todos os projetos" else (project?.name ?: "Projeto"),
                artifacts = scoped, onBack = ::back,
                onOpen = { artifactId = it; go(Route.Artifact) },
                onAsk = { if (artifactScope.isNotEmpty() && project != null) go(Route.Detail) else go(Route.List) },
                modifier = modifier,
            )
        }
        route == Route.Artifact && artifact != null -> {
            val owner = projects.firstOrNull { it.id == artifact.projectId }
            ArtifactDetailScreen(
                artifact = artifact, projectName = owner?.name, notice = notice, error = error, busy = busy, onBack = ::back,
                onInstall = {
                    if (owner == null) { error = "Escolha um projeto para instalar este APK." } else
                        runAction("Não consegui pedir a instalação. Toque em Instalar APK para tentar de novo.") {
                            ask(owner, "Instalar ${artifact.name} no aparelho", "adb")
                            notice = "Pedido enviado. Aprove no projeto ou na aba Atividade para instalar."
                        }
                },
                onShare = {
                    val send = Intent(Intent.ACTION_SEND).apply {
                        type = "text/plain"
                        putExtra(Intent.EXTRA_TEXT, listOfNotNull(artifact.name, artifact.sha256?.let { "SHA-256 $it" }).joinToString("\n"))
                    }
                    try { context.startActivity(Intent.createChooser(send, null).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
                    catch (e: ActivityNotFoundException) { error = "Nenhum app para compartilhar foi encontrado neste aparelho." }
                },
                onOpenSource = { url -> try { uriHandler.openUri(url) } catch (e: Exception) { error = "Não consegui abrir o link. Copie o endereço e abra no navegador." } },
                onCopyHash = { clipboard.setText(AnnotatedString(it)); notice = "Hash copiado." },
                onRestore = { v ->
                    if (owner == null) { error = "Escolha um projeto para restaurar esta versão." } else
                        runAction("Não consegui pedir a restauração. Toque em Restaurar para tentar de novo.") {
                            ask(owner, "Restaurar a versão $v de ${artifact.name}", "files")
                            notice = "Pedido enviado. Aprove no projeto ou na aba Atividade para restaurar a versão $v."
                        }
                },
                modifier = modifier,
            )
        }
        else -> ProjectsListScreen(
            projects = projects, jobs = jobs, artifacts = artifacts, loadError = loadError,
            onRetry = { reload++ },
            onNewProject = { go(Route.Idea) },
            onOpenProject = { projectId = it; go(Route.Detail) },
            onOpenArtifacts = { artifactScope = ""; go(Route.Artifacts) },
            onOpenArtifact = { id -> artifactId = id; artifactScope = ""; go(Route.Artifact) },
            modifier = modifier,
        )
    }
}
