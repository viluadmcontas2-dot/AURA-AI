package ai.aura.app.ui.screens.chat

import ai.aura.app.ui.components.AuraButtonPrimary
import ai.aura.app.ui.components.AuraButtonSecondary
import ai.aura.app.ui.components.ResultBlocks
import ai.aura.app.ui.components.ResultBlocksData
import ai.aura.app.ui.components.StatusChip
import ai.aura.app.ui.components.StatusKind
import ai.aura.app.ui.engine.EngineEvent
import ai.aura.app.ui.engine.FakeEngineGateway
import ai.aura.app.ui.engine.Job
import ai.aura.app.ui.engine.JobRequest
import ai.aura.app.ui.engine.JobRisk
import ai.aura.app.ui.engine.JobStatus
import ai.aura.app.ui.engine.RouteResult
import ai.aura.app.ui.screens.home.AuraOrb
import ai.aura.app.ui.screens.home.rememberReducedMotion
import ai.aura.app.ui.theme.AuraColors
import ai.aura.app.ui.theme.AuraShapes
import ai.aura.app.ui.theme.AuraTheme
import ai.aura.app.ui.theme.AuraType
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

// ---- cabeçalho -----------------------------------------------------------------------------

@Composable
internal fun ChatHeader(
    title: String,
    subtitle: String,
    status: Job?,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier.fillMaxWidth().heightIn(min = 56.dp).padding(start = 8.dp, end = 20.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Box(
            Modifier.size(44.dp).clip(AuraShapes.Circle)
                .clickable(role = Role.Button, onClick = onBack)
                .semantics { contentDescription = "Voltar para o início" },
            contentAlignment = Alignment.Center,
        ) { Text("←", style = AuraType.Section.copy(fontSize = 22.sp)) }
        Column(Modifier.weight(1f)) {
            Text(title, style = AuraType.Section.copy(fontSize = 17.sp, lineHeight = 22.sp), maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                modifier = Modifier.semantics { heading() })
            Text(subtitle, style = AuraType.Secondary.copy(fontSize = 13.sp, lineHeight = 16.sp), maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
        }
        if (status != null) StatusChip(statusKind(status.status), statusText(status.status))
    }
}

// ---- mensagens -----------------------------------------------------------------------------

@Composable
internal fun MessageBubble(message: ChatMessage, modifier: Modifier = Modifier) {
    if (message.fromUser) {
        Box(modifier.fillMaxWidth(), contentAlignment = Alignment.CenterEnd) {
            Text(
                message.text,
                style = AuraType.Body.copy(color = Color.White, lineHeight = 23.sp),
                modifier = Modifier.widthIn(max = 310.dp)
                    .clip(RoundedCornerShape(22.dp, 22.dp, 6.dp, 22.dp))
                    .background(AuraColors.Ink)
                    .padding(horizontal = 16.dp, vertical = 12.dp)
                    .semantics { contentDescription = "Você: ${message.text}" },
            )
        }
    } else {
        Box(modifier.fillMaxWidth(), contentAlignment = Alignment.CenterStart) {
            Text(
                message.text,
                style = AuraType.Body.copy(lineHeight = 23.sp),
                modifier = Modifier.widthIn(max = 326.dp)
                    .clip(RoundedCornerShape(22.dp, 22.dp, 22.dp, 6.dp))
                    .background(AuraColors.Surface)
                    .border(1.dp, AuraColors.Line, RoundedCornerShape(22.dp, 22.dp, 22.dp, 6.dp))
                    .padding(horizontal = 16.dp, vertical = 12.dp)
                    .semantics { contentDescription = "AURA: ${message.text}" },
            )
        }
    }
}

@Composable
internal fun ThinkingRow(text: String, modifier: Modifier = Modifier) {
    Column(modifier.fillMaxWidth().semantics { liveRegion = LiveRegionMode.Polite }, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            AuraOrb(Modifier.size(24.dp), breathing = true)
            Text(text, style = AuraType.Secondary)
        }
        LiveBar(active = true, Modifier.padding(start = 34.dp))
    }
}

/** Barra indeterminada (o engine não informa percentual; não inventamos um). Gradiente = AURA vivo. */
@Composable
internal fun LiveBar(active: Boolean, modifier: Modifier = Modifier) {
    val reduced = rememberReducedMotion()
    val transition = rememberInfiniteTransition(label = "livebar")
    val t by transition.animateFloat(
        0f, 1f, infiniteRepeatable(tween(1600, easing = LinearEasing), RepeatMode.Restart), label = "sweep",
    )
    val colors = listOf(AuraColors.Periwinkle, AuraColors.Rose, AuraColors.Apricot)
    Canvas(
        modifier.fillMaxWidth().height(6.dp).clip(AuraShapes.Pill).background(AuraColors.SurfaceSunken)
            .clearAndSetSemantics { },
    ) {
        if (!active) return@Canvas
        val barW = size.width * 0.35f
        val x = if (reduced) (size.width - barW) / 2f else (size.width + barW) * t - barW
        drawRoundRect(
            brush = Brush.horizontalGradient(colors, startX = x, endX = x + barW),
            topLeft = Offset(x, 0f), size = Size(barW, size.height),
            cornerRadius = CornerRadius(size.height / 2f),
        )
    }
}

// ---- Entendi -------------------------------------------------------------------------------

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun UnderstoodCard(
    understood: ChatPhase.Understood,
    onStart: () -> Unit,
    onAdjust: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val route = understood.route
    val blocked = understood.blocked
    Column(
        modifier.fillMaxWidth().clip(AuraShapes.Card).background(AuraColors.Surface)
            .border(1.dp, AuraColors.Line, AuraShapes.Card).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            AuraOrb(Modifier.size(20.dp))
            Text("ENTENDI", style = AuraType.Caption, modifier = Modifier.semantics { heading() })
        }
        Text(
            "Vou usar ${kindLabel(route.request.kind)} para: ${route.request.prompt}",
            style = AuraType.Section.copy(fontSize = 17.sp, lineHeight = 24.sp),
        )
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            StatusChip(StatusKind.Info, kindLabel(route.request.kind))
            StatusChip(riskKind(route.risk), riskLabel(route.risk))
            if (route.requiresModel) StatusChip(StatusKind.Neutral, "Usa um modelo de IA")
        }
        if (route.reason.isNotBlank()) Text("Por quê: ${route.reason}", style = AuraType.Secondary.copy(fontSize = 13.sp, lineHeight = 18.sp))
        if (route.confidence < 0.6) {
            Text("Não tenho certeza de que entendi. Toque em Ajustar pedido se estiver errado.", style = AuraType.Secondary.copy(fontSize = 13.sp, lineHeight = 18.sp, color = AuraColors.NeedsYou.fg, fontWeight = FontWeight.SemiBold))
        }
        if (route.risk.needsApproval && blocked == null) {
            Text("Antes de rodar, vou mostrar exatamente o que muda e esperar a sua aprovação.", style = AuraType.Secondary.copy(fontSize = 13.sp, lineHeight = 18.sp))
        }
        if (blocked != null) {
            Text(
                "${blocked.label} não está disponível neste aparelho. ${blocked.detail.orEmpty()}".trim(),
                style = AuraType.Secondary.copy(fontSize = 14.sp, color = AuraColors.Risk.fg, fontWeight = FontWeight.SemiBold),
                modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(AuraColors.Risk.bg).padding(12.dp),
            )
        }
        Row(Modifier.padding(top = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            AuraButtonPrimary("Começar", onStart, Modifier.weight(1f), enabled = blocked == null, large = true)
            AuraButtonSecondary("Ajustar pedido", onAdjust)
        }
    }
}

// ---- tarefa em andamento / resultado -------------------------------------------------------

private enum class StepState { Done, Active, Waiting, Failed, Pending }
private class Step(val label: String, val detail: String, val state: StepState)

private fun stepsFor(job: Job, events: List<EngineEvent>): List<Step> = buildList {
    val approvedSeen = events.any { it.type == "job.approved" }
    add(Step("Pedido recebido", "Registrado no motor · ${kindLabel(job.kind)}", StepState.Done))
    if (job.risk.needsApproval) {
        add(
            when (job.status) {
                JobStatus.AwaitingApproval -> Step("Sua aprovação", "Aguardando a sua aprovação", StepState.Waiting)
                JobStatus.Cancelled -> if (approvedSeen) Step("Sua aprovação", "Aprovado por você", StepState.Done)
                else Step("Sua aprovação", "Não aprovado", StepState.Pending)
                else -> Step("Sua aprovação", "Aprovado por você", StepState.Done)
            },
        )
    }
    add(
        when (job.status) {
            JobStatus.Queued -> Step(job.title, "Na fila do AURA", StepState.Pending)
            JobStatus.AwaitingApproval -> Step(job.title, "Começa depois da aprovação", StepState.Pending)
            JobStatus.Running -> Step(job.title, "Em andamento", StepState.Active)
            JobStatus.Completed -> Step(job.title, "Concluído", StepState.Done)
            JobStatus.Failed -> Step(job.title, "Falhou", StepState.Failed)
            JobStatus.Cancelled -> Step(job.title, "Cancelado por você", StepState.Pending)
            JobStatus.Interrupted -> Step(job.title, "Interrompido antes de terminar", StepState.Waiting)
        },
    )
}

@Composable
private fun StepMarker(state: StepState) {
    val mod = Modifier.size(24.dp).clip(AuraShapes.Circle).clearAndSetSemantics { }
    when (state) {
        StepState.Done -> Box(mod.background(AuraColors.Ink), Alignment.Center) { Text("✓", style = AuraType.Chip.copy(color = Color.White)) }
        StepState.Active -> Box(mod.background(Brush.linearGradient(listOf(AuraColors.Periwinkle, AuraColors.Rose, AuraColors.Apricot))), Alignment.Center) {
            Box(Modifier.size(16.dp).clip(AuraShapes.Circle).background(AuraColors.Bg))
        }
        StepState.Waiting -> Box(mod.border(3.dp, AuraColors.NeedsYou.dot, AuraShapes.Circle).background(AuraColors.Bg))
        StepState.Failed -> Box(mod.background(AuraColors.RiskSolid), Alignment.Center) { Text("!", style = AuraType.Chip.copy(color = Color.White)) }
        StepState.Pending -> Box(mod.background(AuraColors.SurfaceSunken).border(1.dp, AuraColors.LineStrong, AuraShapes.Circle))
    }
}

private fun stateWord(s: StepState) = when (s) {
    StepState.Done -> "feito"
    StepState.Active -> "em andamento"
    StepState.Waiting -> "esperando você"
    StepState.Failed -> "falhou"
    StepState.Pending -> "pendente"
}

@Composable
private fun Timeline(steps: List<Step>) {
    Column(
        Modifier.fillMaxWidth().drawBehind {
            drawLine(AuraColors.Line, Offset(11.dp.toPx(), 12.dp.toPx()), Offset(11.dp.toPx(), size.height - 12.dp.toPx()), 2.dp.toPx())
        },
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        steps.forEach { s ->
            Row(
                Modifier.fillMaxWidth().semantics(mergeDescendants = true) { contentDescription = "${s.label}: ${stateWord(s.state)}. ${s.detail}" },
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                StepMarker(s.state)
                Column(Modifier.weight(1f)) {
                    Text(s.label, style = AuraType.BodyStrong)
                    Text(
                        s.detail,
                        style = AuraType.SecondarySmall.copy(
                            color = if (s.state == StepState.Waiting) AuraColors.NeedsYou.fg else AuraColors.Ink2,
                            fontWeight = if (s.state == StepState.Waiting) FontWeight.SemiBold else FontWeight.Normal,
                        ),
                    )
                }
            }
        }
    }
}

/** Resultado em 4 blocos com o que o engine realmente informou; nunca "sucesso" fora de completed. */
internal fun resultBlocks(job: Job): ResultBlocksData = when (job.status) {
    JobStatus.Completed -> ResultBlocksData(
        done = "${job.title}: concluído.",
        proven = job.result ?: "O motor confirmou a conclusão, mas não enviou detalhes de prova.",
        missing = "O motor não informou pendências. Confira o resultado no aparelho.",
        nextStep = "Peça o próximo passo ao AURA ou explique o resultado em português simples.",
    )
    JobStatus.Failed -> ResultBlocksData(
        done = "Nada foi concluído: ${job.title}.",
        proven = "O motor registrou a falha.",
        missing = errorText(job.errorCode),
        nextStep = "Tente de novo ou ajuste o pedido.",
    )
    JobStatus.Cancelled -> ResultBlocksData(
        done = "Cancelado antes de terminar: ${job.title}.",
        proven = "O motor registrou o cancelamento.",
        missing = "O pedido não foi concluído.",
        nextStep = "Faça um novo pedido quando quiser.",
    )
    else -> ResultBlocksData(
        done = "Interrompido: ${job.title}.",
        proven = "O motor registrou a interrupção.",
        missing = "O pedido não foi concluído.",
        nextStep = "Tente de novo.",
    )
}

@Composable
internal fun JobPanel(
    job: Job,
    events: List<EngineEvent>,
    connectionLost: Boolean,
    busy: Boolean,
    onReviewApproval: () -> Unit,
    onStop: () -> Unit,
    onRetry: () -> Unit,
    onExplain: () -> Unit,
    onNew: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        if (job.status == JobStatus.Running) LiveBar(active = true)
        if (connectionLost) {
            Text(
                "Sem resposta do Termux agora. Continuo tentando; o que aparece abaixo pode estar desatualizado.",
                style = AuraType.SecondarySmall.copy(color = AuraColors.NeedsYou.fg, fontWeight = FontWeight.SemiBold),
                modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(AuraColors.NeedsYou.bg).padding(12.dp)
                    .semantics { liveRegion = LiveRegionMode.Polite },
            )
        }
        Timeline(stepsFor(job, events))
        if (job.status == JobStatus.AwaitingApproval) {
            Column(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp)).background(AuraColors.NeedsYou.bg)
                    .border(1.dp, Color(0xFFF0D49A), RoundedCornerShape(20.dp)).padding(14.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text("${job.title}?", style = AuraType.BodyStrong)
                Text("Preciso da sua aprovação. Nada roda antes disso.", style = AuraType.SecondarySmall.copy(color = AuraColors.NeedsYou.fg))
                AuraButtonSecondary("Revisar e aprovar", onReviewApproval, Modifier.fillMaxWidth(), enabled = !busy)
            }
        }
        if (!job.status.isTerminal) {
            AuraButtonSecondary("Parar tarefa", onStop, Modifier.fillMaxWidth(), enabled = !busy)
        } else {
            ResultBlocks(resultBlocks(job))
            when (job.status) {
                JobStatus.Completed -> Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    AuraButtonSecondary("Explicar simples", onExplain)
                    AuraButtonSecondary("Novo pedido", onNew)
                }
                else -> Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    AuraButtonPrimary("Tentar de novo", onRetry, Modifier.fillMaxWidth(), large = true)
                    AuraButtonSecondary("Novo pedido", onNew, Modifier.fillMaxWidth())
                }
            }
        }
    }
}

// ---- erro / comandos -----------------------------------------------------------------------

@Composable
internal fun ChatErrorCard(error: ChatError, modifier: Modifier = Modifier) {
    Column(
        modifier.fillMaxWidth().clip(AuraShapes.Card).background(AuraColors.Risk.bg).padding(16.dp)
            .semantics { liveRegion = LiveRegionMode.Assertive },
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text(error.title, style = AuraType.Section.copy(color = AuraColors.Risk.fg))
        Text(error.body, style = AuraType.Secondary.copy(color = AuraColors.Risk.fg))
        Box(Modifier.padding(top = 6.dp)) { AuraButtonSecondary("Tentar de novo", error.retry) }
    }
}

@Composable
internal fun CommandsPanel(
    commands: List<ChatCommand>,
    onPick: (ChatCommand) -> Unit,
    modifier: Modifier = Modifier,
) {
    val tints = listOf(AuraColors.CategoryEngineering, AuraColors.CategoryClarity, AuraColors.CategoryAndroid, AuraColors.CategoryApricot, AuraColors.CategoryLilac)
    Column(
        modifier.fillMaxWidth().padding(horizontal = 20.dp)
            .clip(AuraShapes.Card).background(AuraColors.Surface).border(1.dp, AuraColors.Line, AuraShapes.Card)
            .semantics { contentDescription = "Comandos" },
    ) {
        Row(
            Modifier.fillMaxWidth().height(40.dp).padding(horizontal = 16.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text("COMANDOS", style = AuraType.Caption)
            Text("Toque para escolher", style = AuraType.Secondary.copy(fontSize = 12.sp, lineHeight = 16.sp))
        }
        Box(Modifier.fillMaxWidth().height(1.dp).background(AuraColors.Line))
        commands.forEachIndexed { i, c ->
            val source = remember { MutableInteractionSource() }
            Row(
                Modifier.fillMaxWidth().heightIn(min = 56.dp)
                    .clickable(interactionSource = source, indication = null, role = Role.Button) { onPick(c) }
                    .padding(horizontal = 16.dp, vertical = 8.dp)
                    .semantics(mergeDescendants = true) { contentDescription = "${c.name}. ${c.description}" },
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Box(Modifier.size(36.dp).clip(RoundedCornerShape(12.dp)).background(tints[i % tints.size]).clearAndSetSemantics { }, Alignment.Center) {
                    Text("/", style = AuraType.BodyStrong)
                }
                Column(Modifier.weight(1f)) {
                    Text(c.name, style = AuraType.BodyStrong.copy(lineHeight = 20.sp))
                    Text(c.description, style = AuraType.SecondarySmall.copy(lineHeight = 18.sp))
                }
            }
        }
    }
}

// ---- previews ------------------------------------------------------------------------------

private val sample = FakeEngineGateway.sampleJobs()
private val route = RouteResult(JobRequest("claude_cli", "Corrigir tela de login"), "Pedido de alteração de código", 0.82, true, JobRisk.Write)
private val bg = 0xFFF5F4F1

@Preview(showBackground = true, backgroundColor = bg)
@Composable
private fun UnderstoodPreview() {
    AuraTheme { Column(Modifier.padding(20.dp)) { UnderstoodCard(ChatPhase.Understood(route, null), {}, {}) } }
}

@Preview(showBackground = true, backgroundColor = bg)
@Composable
private fun UnderstoodBlockedPreview() {
    AuraTheme {
        Column(Modifier.padding(20.dp)) {
            UnderstoodCard(
                ChatPhase.Understood(RouteResult(JobRequest("adb", "Instalar o app na TV"), "Instalação em aparelho", 0.4, false, JobRisk.Write),
                    ai.aura.app.ui.engine.Capability("adb", "Controle de Android (adb)", false, "Não instalado neste aparelho")),
                {}, {},
            )
        }
    }
}

@Preview(showBackground = true, backgroundColor = bg)
@Composable
private fun RunningPreview() {
    AuraTheme { Column(Modifier.padding(20.dp)) { JobPanel(sample[0], emptyList(), false, false, {}, {}, {}, {}, {}) } }
}

@Preview(showBackground = true, backgroundColor = bg)
@Composable
private fun AwaitingPreview() {
    AuraTheme { Column(Modifier.padding(20.dp)) { JobPanel(sample[1], emptyList(), true, false, {}, {}, {}, {}, {}) } }
}

@Preview(showBackground = true, backgroundColor = bg)
@Composable
private fun CompletedPreview() {
    AuraTheme { Column(Modifier.padding(20.dp)) { JobPanel(sample[2], emptyList(), false, false, {}, {}, {}, {}, {}) } }
}

@Preview(showBackground = true, backgroundColor = bg)
@Composable
private fun FailedPreview() {
    AuraTheme { Column(Modifier.padding(20.dp)) { JobPanel(sample[3], emptyList(), false, false, {}, {}, {}, {}, {}) } }
}

@Preview(showBackground = true, backgroundColor = bg)
@Composable
private fun ErrorPreview() {
    AuraTheme {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            MessageBubble(ChatMessage(0, true, "Corrigir tela de login"))
            MessageBubble(ChatMessage(1, false, "Não há nada em andamento para continuar."))
            ChatErrorCard(ChatError("Sem conexão com o Termux", "Abra o Termux, confirme que o AURA está ativo e toque em Tentar de novo.") {})
            CommandsPanel(ChatCommands.all, {})
        }
    }
}
