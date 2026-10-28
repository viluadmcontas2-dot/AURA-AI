package ai.aura.app.ui.screens.projects

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import ai.aura.app.ui.components.AuraButtonPrimary
import ai.aura.app.ui.components.AuraButtonSecondary
import ai.aura.app.ui.components.AuraCard
import ai.aura.app.ui.components.AuraComposer
import ai.aura.app.ui.components.AuraFilterChip
import ai.aura.app.ui.components.StatusChip
import ai.aura.app.ui.components.StatusKind
import ai.aura.app.ui.engine.Job
import ai.aura.app.ui.engine.JobRisk
import ai.aura.app.ui.engine.JobStatus
import ai.aura.app.ui.theme.AuraColors
import ai.aura.app.ui.theme.AuraShapes
import ai.aura.app.ui.theme.AuraTheme
import ai.aura.app.ui.theme.AuraType

private enum class DetailTab(val label: String) { Summary("Resumo"), Tasks("Tarefas"), Files("Arquivos"), Artifacts("Artefatos"), Decisions("Decisões") }

/** Projeto-Detalhe.dc.html: onde estamos (feito/falta/próximo), abas, tarefas e caixa "Peça ao AURA". */
@Composable
fun ProjectDetailScreen(
    project: Project,
    phase: ProjectPhase,
    jobs: List<Job>,
    artifacts: List<Artifact>,
    busy: Boolean,
    error: String?,
    onBack: () -> Unit,
    onPrimary: () -> Unit,
    onSend: (String) -> Unit,
    onToggleArchive: () -> Unit,
    onOpenArtifact: (String) -> Unit,
    onOpenArtifacts: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var tabName by rememberSaveable { mutableStateOf(DetailTab.Summary.name) }
    var menu by rememberSaveable { mutableStateOf(false) }
    val tab = DetailTab.valueOf(tabName)
    Column(modifier.fillMaxSize()) {
        ProjHeader(project.name, onBack, backDescription = "Voltar para Projetos", subtitle = "${project.repo} · ${project.branch}") {
            Box {
                IconTap(PIcon.More, "Mais opções", { menu = true })
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    DropdownMenuItem(
                        text = { Text(if (project.archived) "Tirar do arquivo" else "Arquivar projeto", style = AuraType.Body) },
                        onClick = { menu = false; onToggleArchive() },
                    )
                }
            }
        }
        Column(
            Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            WhereWeAre(phase, busy, error, onPrimary)
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                DetailTab.entries.forEach { t -> AuraFilterChip(t.label, t == tab, { tabName = t.name }) }
            }
            when (tab) {
                DetailTab.Summary -> {
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        SectionLabel("Objetivo")
                        Text(project.goal, style = AuraType.Body)
                    }
                    if (project.doneWhen.isNotEmpty()) Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        SectionLabel("Pronto quando")
                        project.doneWhen.forEach { Text("• $it", style = AuraType.Secondary.copy(color = AuraColors.Ink)) }
                    }
                    TaskList(jobs)
                }
                DetailTab.Tasks -> TaskList(jobs)
                DetailTab.Files -> InlineEmpty("Nenhum arquivo ainda", "Os arquivos aparecem aqui quando o AURA mexer no código. Peça uma tarefa na caixa abaixo.")
                DetailTab.Decisions -> InlineEmpty("Nenhuma decisão registrada", "Quando você aprovar ou recusar algo, fica anotado aqui. Peça uma tarefa na caixa abaixo.")
                DetailTab.Artifacts -> if (artifacts.isEmpty()) {
                    InlineEmpty("Nenhum artefato ainda", "Quando o AURA gerar um APK, relatório ou imagem, ele aparece aqui. Peça um na caixa abaixo.")
                } else {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        artifacts.take(4).forEach { a ->
                            AuraCard(Modifier.fillMaxWidth(), onClick = { onOpenArtifact(a.id) }, contentDescription = "${a.name}, ${artifactLine(a)}") {
                                Text(a.name, style = AuraType.BodyStrong, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Text(artifactLine(a), style = AuraType.SecondarySmall)
                            }
                        }
                        AuraButtonSecondary("Ver todos os artefatos", onOpenArtifacts)
                    }
                }
            }
        }
        Column {
            Text("Sobre: ${project.name}", style = AuraType.Caption, modifier = Modifier.padding(start = 24.dp, top = 4.dp), maxLines = 1, overflow = TextOverflow.Ellipsis)
            AuraComposer(onSend = onSend, placeholder = "Peça algo ao AURA…")
        }
    }
}

@Composable
private fun WhereWeAre(phase: ProjectPhase, busy: Boolean, error: String?, onPrimary: () -> Unit) {
    val job = phase.job
    val done = when (phase.kind) {
        PhaseKind.Ready -> phase.subtitle
        PhaseKind.Running, PhaseKind.NeedsYou, PhaseKind.Failed, PhaseKind.Stopped, PhaseKind.Queued -> "Projeto criado a partir da sua ideia"
        PhaseKind.Idle -> "Projeto criado a partir da sua ideia"
    }
    val missing = when (phase.kind) {
        PhaseKind.Running, PhaseKind.Queued -> job?.title ?: ""
        PhaseKind.NeedsYou -> job?.title ?: ""
        PhaseKind.Failed, PhaseKind.Stopped -> job?.title ?: ""
        PhaseKind.Ready, PhaseKind.Idle -> ""
    }
    val next = when (phase.kind) {
        PhaseKind.NeedsYou -> "Aprovar esta tarefa"
        PhaseKind.Failed -> "Tentar a tarefa de novo"
        PhaseKind.Running -> "Acompanhar a execução"
        PhaseKind.Queued -> "Aguardar a vez na fila"
        PhaseKind.Stopped -> "Pedir a tarefa de novo"
        PhaseKind.Ready, PhaseKind.Idle -> "Pedir a próxima tarefa ao AURA"
    }
    val action = when (phase.kind) {
        PhaseKind.NeedsYou -> "Aprovar tarefa"
        PhaseKind.Failed -> "Tentar tarefa de novo"
        else -> null
    }
    Column(
        Modifier.fillMaxWidth().clip(AuraShapes.Card).background(AuraColors.Surface).border(1.dp, AuraColors.Line, AuraShapes.Card).padding(horizontal = 16.dp, vertical = 14.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
            Text("Onde estamos", style = AuraType.Section)
            PhaseChip(phase)
        }
        Step(PIcon.Check, "FEITO", done, AuraColors.Ready.bg, AuraColors.Ready.fg, false)
        if (missing.isNotBlank()) Step(PIcon.Spark, "FALTA", missing, AuraColors.SurfaceSunken, AuraColors.Ink2, false)
        Step(PIcon.ChevronRight, "PRÓXIMO PASSO", next, AuraColors.NeedsYou.bg, AuraColors.NeedsYou.fg, true)
        if (phase.kind == PhaseKind.NeedsYou && job != null) {
            Text(riskLine(job), style = AuraType.SecondarySmall)
        }
        if (phase.kind == PhaseKind.Failed && job != null) {
            NoteBanner(friendlyError(job.errorCode), AuraColors.Risk, PIcon.Lock)
        }
        if (error != null) NoteBanner(error, AuraColors.Risk, PIcon.Lock)
        if (action != null) AuraButtonPrimary(if (busy) "Enviando…" else action, onPrimary, Modifier.fillMaxWidth(), enabled = !busy)
    }
}

/** Alvo e consequência visíveis antes de aprovar (regra 6 do produto). */
private fun riskLine(job: Job): String = when (job.risk) {
    JobRisk.Read -> "Só lê informações. Alvo: ${job.title}."
    JobRisk.Write -> "Vai alterar arquivos ou contas. Alvo: ${job.title}."
    JobRisk.Spend -> "Pode gerar custo. Alvo: ${job.title}. Confira antes de aprovar."
    JobRisk.Critical -> "Ação crítica, difícil de desfazer. Alvo: ${job.title}. Confira antes de aprovar."
}

@Composable
private fun Step(icon: PIcon, label: String, text: String, bg: Color, fg: Color, strong: Boolean) {
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.Top) {
        Box(Modifier.size(24.dp).clip(AuraShapes.Circle).background(bg), contentAlignment = Alignment.Center) { PIconView(icon, tint = fg, size = 14.dp) }
        Column {
            Text(label, style = AuraType.Caption.copy(color = fg))
            Text(text, style = AuraType.Secondary.copy(color = AuraColors.Ink, fontWeight = if (strong) FontWeight.Bold else FontWeight.Normal))
        }
    }
}

@Composable
private fun TaskList(jobs: List<Job>) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        SectionLabel("Tarefas")
        if (jobs.isEmpty()) {
            Text("Nenhuma tarefa ainda. Peça a primeira na caixa abaixo.", style = AuraType.Secondary)
            return@Column
        }
        Column(Modifier.fillMaxWidth().clip(AuraShapes.Chip).background(AuraColors.Surface).border(1.dp, AuraColors.Line, AuraShapes.Chip)) {
            jobs.forEachIndexed { i, j ->
                Row(
                    Modifier.fillMaxWidth().heightIn(min = 44.dp).padding(horizontal = 14.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text(j.title, style = AuraType.Secondary.copy(color = AuraColors.Ink, fontWeight = FontWeight.SemiBold), maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                    JobChip(j)
                }
                if (i < jobs.lastIndex) Box(Modifier.fillMaxWidth().height(1.dp).background(AuraColors.Line))
            }
        }
    }
}

@Composable
internal fun JobChip(j: Job) = when (j.status) {
    JobStatus.Running -> RunningChip("Rodando")
    JobStatus.AwaitingApproval -> StatusChip(StatusKind.NeedsYou, "Aprovar")
    JobStatus.Completed -> StatusChip(StatusKind.Ready, "Pronto")
    JobStatus.Failed -> StatusChip(StatusKind.Risk, "Falhou")
    JobStatus.Queued -> StatusChip(StatusKind.Neutral, "Triagem")
    JobStatus.WaitingEngine -> StatusChip(StatusKind.NeedsYou, "Aguardando o motor")
    JobStatus.Cancelled -> StatusChip(StatusKind.Neutral, "Cancelado")
    JobStatus.Interrupted -> StatusChip(StatusKind.Neutral, "Interrompido")
}
