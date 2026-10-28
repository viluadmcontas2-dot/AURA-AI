package ai.aura.app.ui.screens.activity

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import ai.aura.app.ui.components.AuraButtonDestructive
import ai.aura.app.ui.components.AuraButtonPrimary
import ai.aura.app.ui.components.AuraButtonSecondary
import ai.aura.app.ui.components.AuraButtonText
import ai.aura.app.ui.components.AuraCard
import ai.aura.app.ui.components.AuraFilterChip
import ai.aura.app.ui.components.AuraProgressBar
import ai.aura.app.ui.components.EmptyState
import ai.aura.app.ui.components.StatusChip
import ai.aura.app.ui.engine.EngineEvent
import ai.aura.app.ui.engine.EngineGateway
import ai.aura.app.ui.engine.FakeEngineGateway
import ai.aura.app.ui.engine.Job
import ai.aura.app.ui.engine.JobRequest
import ai.aura.app.ui.engine.JobRisk
import ai.aura.app.ui.engine.JobStatus
import ai.aura.app.ui.screens.devices.SectionTitle
import ai.aura.app.ui.screens.devices.TabHeader
import ai.aura.app.ui.theme.AuraColors
import ai.aura.app.ui.theme.AuraShapes
import ai.aura.app.ui.theme.AuraSpacing
import ai.aura.app.ui.theme.AuraTheme
import ai.aura.app.ui.theme.AuraType
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import java.util.UUID

/**
 * Aba Atividade: aprovações, o que está rodando e histórico (concluído, falhou, cancelado,
 * interrompido) com evidência do engine. Logs só atrás de "Ver detalhes técnicos".
 * [onRetry] é opcional: sem ele, "Tentar de novo" manda um pedido novo com o tipo e o título do job.
 * [onStartRequest] leva o usuário a pedir algo (aba Início), usado na tela vazia.
 */
@Composable
fun ActivityTab(
    gateway: EngineGateway,
    modifier: Modifier = Modifier,
    onRetry: ((Job) -> Unit)? = null,
    onStartRequest: () -> Unit = {},
    onOpenJob: ((jobId: String) -> Unit)? = null,
) {
    var jobs by remember { mutableStateOf<List<Job>?>(null) }
    var loadFailed by remember { mutableStateOf(false) }
    var events by remember { mutableStateOf<List<EngineEvent>>(emptyList()) }
    var tick by remember { mutableIntStateOf(0) }
    var actionError by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()

    LaunchedEffect(gateway, tick) {
        try {
            jobs = gateway.jobs()
            loadFailed = false
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            loadFailed = true
        }
    }
    LaunchedEffect(gateway) {
        try {
            gateway.events.collect { e ->
                events = (events + e).takeLast(300)
                tick++
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            loadFailed = true
        }
    }

    var actionBusy by remember { mutableStateOf(false) }
    fun launchAction(block: suspend () -> Unit) {
        if (actionBusy) return // toque duplo em Aprovar/Recusar
        actionBusy = true
        scope.launch {
            try {
                block()
                actionError = null
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                actionError = "Não consegui concluir. Toque de novo; se repetir, confira o Termux."
            } finally {
                actionBusy = false
            }
            tick++
        }
    }

    ActivityContent(
        jobs = jobs,
        loadFailed = loadFailed,
        events = events,
        now = System.currentTimeMillis(),
        actionError = actionError,
        onApprove = { launchAction { gateway.approve(it.id) } },
        onRefuse = { launchAction { gateway.cancel(it.id) } },
        onCancel = { launchAction { gateway.cancel(it.id) } },
        onReload = { tick++ },
        // Sem gancho de fora, "Tentar de novo" repete o pedido (kind e título, o que o Job guarda) com id novo.
        onRetry = onRetry ?: { job: Job -> launchAction { gateway.submit("req_retry_" + UUID.randomUUID(), JobRequest(job.kind, job.title)) } },
        onStartRequest = onStartRequest,
        onOpenJob = onOpenJob,
        modifier = modifier,
    )
}

/** Conteúdo sem estado de engine: testável e com @Preview. */
@Composable
fun ActivityContent(
    jobs: List<Job>?,
    loadFailed: Boolean,
    events: List<EngineEvent>,
    now: Long,
    actionError: String?,
    onApprove: (Job) -> Unit,
    onRefuse: (Job) -> Unit,
    onCancel: (Job) -> Unit,
    onReload: () -> Unit,
    onRetry: ((Job) -> Unit)?,
    onStartRequest: () -> Unit,
    modifier: Modifier = Modifier,
    onOpenJob: ((jobId: String) -> Unit)? = null,
) {
    var filterIndex by rememberSaveable { mutableIntStateOf(0) }
    var expandedId by rememberSaveable { mutableStateOf<String?>(null) }
    var techId by rememberSaveable { mutableStateOf<String?>(null) }
    val filter = ActivityFilter.entries[filterIndex.coerceIn(0, ActivityFilter.entries.lastIndex)]

    Column(modifier.fillMaxSize().background(AuraColors.Bg)) {
        TabHeader("Atividade")
        when {
            jobs == null && loadFailed -> Column(Modifier.padding(AuraSpacing.ScreenGutter)) {
                IncidentBanner(
                    IncidentLevel.Failed, "Não consegui carregar a atividade",
                    "Abra o Termux e confira se o AURA está ativo. Depois tente de novo.",
                    actionText = "Tentar de novo", onAction = onReload,
                )
            }
            jobs == null -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("Carregando atividade…", style = AuraType.Secondary)
            }
            jobs.isEmpty() -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                EmptyState(
                    title = "Nada por aqui ainda",
                    body = "Peça algo ao AURA e o histórico, as aprovações e as provas aparecem aqui.",
                    actionText = "Pedir algo ao AURA",
                    onAction = onStartRequest,
                )
            }
            else -> {
                FilterRow(jobs, filter) { filterIndex = it.ordinal }
                val shown = jobs.filter { it.matches(filter) }
                val needs = shown.filter { it.status == JobStatus.AwaitingApproval }
                val running = shown.filter {
                    it.status == JobStatus.Running || it.status == JobStatus.Queued || it.status == JobStatus.WaitingEngine
                }
                val history = groupHistory(shown.filter { it.status.isTerminal }, now)
                LazyColumn(
                    Modifier.fillMaxSize(),
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(
                        start = AuraSpacing.ScreenGutter, end = AuraSpacing.ScreenGutter,
                        top = AuraSpacing.S2, bottom = AuraSpacing.S6,
                    ),
                    verticalArrangement = Arrangement.spacedBy(AuraSpacing.S3),
                ) {
                    if (actionError != null) item(key = "action-error") {
                        IncidentBanner(IncidentLevel.Failed, "Ação não concluída", actionError)
                    }
                    if (shown.isEmpty()) item(key = "filter-empty") {
                        Column(verticalArrangement = Arrangement.spacedBy(AuraSpacing.S3)) {
                            Text("Nada em \"${filter.label}\".", style = AuraType.Secondary)
                            AuraButtonSecondary("Ver tudo", { filterIndex = 0 })
                        }
                    }
                    if (needs.isNotEmpty()) {
                        item(key = "h-needs") { SectionTitle("Precisa de você") }
                        items(needs, key = { it.id }) { job ->
                            ApprovalCard(
                                job, detailsOpen = expandedId == job.id,
                                onToggleDetails = { expandedId = if (expandedId == job.id) null else job.id },
                                onApprove = { onApprove(job) }, onRefuse = { onRefuse(job) },
                            )
                        }
                    }
                    if (running.isNotEmpty()) {
                        item(key = "h-running") { SectionTitle("Rodando") }
                        items(running, key = { it.id }) { job ->
                            RunningCard(job, progressOf(job.id, events), onCancel = { onCancel(job) })
                        }
                    }
                    history.forEach { (title, group) ->
                        item(key = "h-$title") { SectionTitle(title) }
                        item(key = "g-$title") {
                            HistoryGroup(
                                group, events, now, expandedId, techId,
                                onToggle = { id -> expandedId = if (expandedId == id) null else id; techId = null },
                                onToggleTech = { id -> techId = if (techId == id) null else id },
                                onRetry = onRetry,
                                onOpenJob = onOpenJob,
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun FilterRow(jobs: List<Job>, selected: ActivityFilter, onSelect: (ActivityFilter) -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = AuraSpacing.S4),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        ActivityFilter.entries.forEach { f ->
            val count = if (f == ActivityFilter.NeedsYou || f == ActivityFilter.Running) jobs.count { it.matches(f) } else 0
            val text = if (count > 0) "${f.label} ($count)" else f.label
            AuraFilterChip(text, selected = f == selected, onClick = { onSelect(f) })
        }
    }
}

@Composable
private fun ApprovalCard(
    job: Job,
    detailsOpen: Boolean,
    onToggleDetails: () -> Unit,
    onApprove: () -> Unit,
    onRefuse: () -> Unit,
) {
    Column(
        Modifier
            .fillMaxWidth()
            .clip(AuraShapes.Card)
            .background(AuraColors.NeedsYou.bg)
            .border(1.dp, androidx.compose.ui.graphics.Color(0xFFF0D9A8), AuraShapes.Card)
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text("APROVAÇÃO PENDENTE", style = AuraType.Caption.copy(color = AuraColors.NeedsYou.fg))
        Text(job.title, style = AuraType.BodyStrong)
        // Consequência SEMPRE visível antes de aprovar.
        Text(
            job.risk.consequence(),
            style = AuraType.SecondarySmall.copy(
                color = if (job.risk.needsApproval && job.risk != JobRisk.Write) AuraColors.Risk.fg else AuraColors.Ink2,
            ),
        )
        if (detailsOpen) {
            Text("Tipo: ${job.kind} · Risco: ${job.risk.wire}", style = AuraType.Mono)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            when (job.risk) {
                JobRisk.Read, JobRisk.Write -> AuraButtonPrimary("Aprovar", onApprove)
                JobRisk.Spend -> AuraButtonDestructive("Aprovar gasto", onApprove)
                JobRisk.Critical -> AuraButtonDestructive("Aprovar e apagar", onApprove)
            }
            AuraButtonSecondary("Recusar", onRefuse)
        }
        AuraButtonText(if (detailsOpen) "Ocultar detalhes" else "Ver detalhes", onToggleDetails)
    }
}

@Composable
private fun RunningCard(job: Job, progress: StepProgress?, onCancel: () -> Unit) {
    AuraCard(Modifier.fillMaxWidth()) {
        Text(job.title, style = AuraType.BodyStrong)
        val line = when {
            job.status == JobStatus.Queued -> "Na fila"
            job.status == JobStatus.WaitingEngine -> "Aguardando o motor ligar"
            progress != null -> "Passo ${progress.step} de ${progress.total}" +
                (progress.label?.let { " · $it" } ?: "")
            else -> "Rodando agora"
        }
        Text(line, style = AuraType.SecondarySmall)
        if (progress != null) {
            Spacer(Modifier.height(4.dp))
            AuraProgressBar(
                progress = progress.step.toFloat() / progress.total, alive = job.status == JobStatus.Running,
                contentDescription = "Passo ${progress.step} de ${progress.total}",
            )
        }
        AuraButtonText("Cancelar tarefa", onCancel)
    }
}

@Composable
private fun HistoryGroup(
    group: List<Job>,
    events: List<EngineEvent>,
    now: Long,
    expandedId: String?,
    techId: String?,
    onToggle: (String) -> Unit,
    onToggleTech: (String) -> Unit,
    onRetry: ((Job) -> Unit)?,
    onOpenJob: ((jobId: String) -> Unit)? = null,
) {
    Column(
        Modifier
            .fillMaxWidth()
            .clip(AuraShapes.Card)
            .background(AuraColors.Surface)
            .border(1.dp, AuraColors.Line, AuraShapes.Card),
    ) {
        group.forEachIndexed { i, job ->
            if (i > 0) Box(Modifier.fillMaxWidth().height(1.dp).background(AuraColors.Line))
            HistoryRow(job, events, now, expanded = expandedId == job.id, techOpen = techId == job.id,
                onToggle = { onToggle(job.id) }, onToggleTech = { onToggleTech(job.id) }, onRetry = onRetry,
                onOpenJob = onOpenJob)
        }
    }
}

@Composable
private fun HistoryRow(
    job: Job,
    events: List<EngineEvent>,
    now: Long,
    expanded: Boolean,
    techOpen: Boolean,
    onToggle: () -> Unit,
    onToggleTech: () -> Unit,
    onRetry: ((Job) -> Unit)?,
    onOpenJob: ((jobId: String) -> Unit)? = null,
) {
    val source = remember { MutableInteractionSource() }
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp)) {
        Row(
            Modifier
                .fillMaxWidth()
                .heightIn(min = 56.dp)
                .clickable(interactionSource = source, indication = null, role = Role.Button, onClick = onToggle)
                .semantics {
                    contentDescription = "${job.title}, ${job.statusLabel()}, ${formatTime(job.createdAtMillis, now)}"
                    stateDescription = if (expanded) "Aberto" else "Fechado"
                },
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Column(Modifier.weight(1f)) {
                Text(job.title, style = AuraType.BodyStrong)
                Text(formatTime(job.createdAtMillis, now), style = AuraType.SecondarySmall)
            }
            StatusChip(job.statusKind(), job.statusLabel())
        }
        if (expanded) {
            val (what, todo) = job.evidence()
            Column(Modifier.padding(bottom = 10.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(what, style = AuraType.Secondary)
                if (todo != null) Text(todo, style = AuraType.SecondarySmall)
                Text("Risco: ${job.risk.consequence()}", style = AuraType.SecondarySmall)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    if (onRetry != null && (job.status == JobStatus.Failed || job.status == JobStatus.Interrupted)) {
                        AuraButtonSecondary("Tentar de novo", { onRetry(job) })
                    }
                    if (onOpenJob != null) AuraButtonSecondary("Abrir no chat", { onOpenJob(job.id) })
                    AuraButtonText(if (techOpen) "Ocultar detalhes técnicos" else "Ver detalhes técnicos", onToggleTech)
                }
                if (techOpen) {
                    Column(
                        Modifier.fillMaxWidth().clip(AuraShapes.Chip).background(AuraColors.SurfaceSunken).padding(12.dp),
                        verticalArrangement = Arrangement.spacedBy(2.dp),
                    ) {
                        technicalLines(job, events, now).forEach { Text(it, style = AuraType.Mono) }
                    }
                }
            }
        }
    }
}

private fun previewJobs(now: Long) = FakeEngineGateway.sampleJobs(now) + listOf(
    Job("job_5", "req_5", "Sincronizar Drive", "system", JobStatus.Interrupted, JobRisk.Read, now - 8_000_000),
    Job("job_6", "req_6", "Apagar builds antigos", "files", JobStatus.Cancelled, JobRisk.Critical, now - 90_000_000),
)

@Preview(showBackground = true, backgroundColor = 0xFFF5F4F1, heightDp = 900)
@Composable
private fun ActivityPreview() {
    val now = 1_700_000_000_000L
    AuraTheme {
        ActivityContent(
            jobs = previewJobs(now), loadFailed = false,
            events = listOf(EngineEvent(1, "job.progress", "job_1", mapOf("step" to "3", "total" to "4", "label" to "Gerar o APK"), now)),
            now = now, actionError = null, onApprove = {}, onRefuse = {}, onCancel = {}, onReload = {},
            onRetry = {}, onStartRequest = {},
        )
    }
}

@Preview(showBackground = true, backgroundColor = 0xFFF5F4F1, heightDp = 700)
@Composable
private fun ActivityEmptyPreview() {
    AuraTheme {
        ActivityContent(emptyList(), false, emptyList(), 0L, null, {}, {}, {}, {}, null, {})
    }
}

@Preview(showBackground = true, backgroundColor = 0xFFF5F4F1, heightDp = 400)
@Composable
private fun ActivityErrorPreview() {
    AuraTheme {
        ActivityContent(null, true, emptyList(), 0L, null, {}, {}, {}, {}, null, {})
    }
}
