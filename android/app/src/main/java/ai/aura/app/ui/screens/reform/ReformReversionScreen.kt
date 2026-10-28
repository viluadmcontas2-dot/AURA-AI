package ai.aura.app.ui.screens.reform

import ai.aura.app.ui.components.AuraButtonPrimary
import ai.aura.app.ui.components.AuraButtonSecondary
import ai.aura.app.ui.components.AuraButtonText
import ai.aura.app.ui.components.AuraProgressBar
import ai.aura.app.ui.components.EmptyState
import ai.aura.app.ui.components.Meter
import ai.aura.app.ui.components.StatusChip
import ai.aura.app.ui.components.StatusKind
import ai.aura.app.ui.components.auraFocusRing
import ai.aura.app.ui.screens.activity.IncidentBanner
import ai.aura.app.ui.screens.activity.IncidentLevel
import ai.aura.app.ui.theme.AuraColors
import ai.aura.app.ui.theme.AuraShapes
import ai.aura.app.ui.theme.AuraTheme
import ai.aura.app.ui.theme.AuraType
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** Limite atual do fluxo: a análise termina, a reconstrução ainda não existe aqui. Sem promessa de APK. */
internal const val REFORM_ANALYSIS_LIMIT = "Análise concluída. A reconstrução ainda não está disponível neste fluxo."

/** Dito quando o motor não manda o dado: a tela nunca preenche com número inventado. */
internal const val REFORM_PROGRESS_NOT_REPORTED = "O motor ainda não informa"

/** Cabeçalho das telas de reforma: voltar (44 dp) + título e legenda. Usado também por Fidelidade e Projeto. */
@Composable
internal fun ReformProgressHeader(title: String, subtitle: String?, backDescription: String, onBack: () -> Unit) {
    val source = remember { MutableInteractionSource() }
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Box(
            Modifier.size(44.dp).clip(AuraShapes.Circle)
                .auraFocusRing(source, AuraShapes.Circle)
                .clickable(interactionSource = source, indication = null, role = Role.Button, onClick = onBack)
                .semantics { contentDescription = backDescription },
            contentAlignment = Alignment.Center,
        ) { Text("‹", style = AuraType.TabTitle) }
        Column(Modifier.weight(1f)) {
            Text(
                title, style = AuraType.BodyStrong, maxLines = 1, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.semantics { heading() },
            )
            if (subtitle != null) Text(subtitle, style = AuraType.SecondarySmall)
        }
    }
}

/** Sem o que mostrar: erro (uma ação, "Tentar de novo") ou vazio (uma ação, "Escolher um app"). Usado pelas três telas. */
@Composable
internal fun ReformProgressBlank(
    error: String?,
    errorTitle: String,
    emptyTitle: String,
    emptyBody: String,
    onChooseApp: () -> Unit,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(modifier.fillMaxWidth().padding(horizontal = 20.dp), contentAlignment = Alignment.Center) {
        if (error != null) {
            IncidentBanner(IncidentLevel.Failed, errorTitle, error, actionText = "Tentar de novo", onAction = onRetry)
        } else {
            EmptyState(emptyTitle, emptyBody, "Escolher um app", onChooseApp)
        }
    }
}

/**
 * Reformar 3 · Reversão e reconstrução: três medidores, passos com "Prova:", checkpoint, Pausar.
 * Abre sem motor: sem eventos mostra o estado vazio (uma ação); com [ReformProgress.error] mostra o erro (uma ação).
 * Navegação por callbacks. [nowMillis] só serve para dizer "há quanto tempo" o checkpoint foi salvo.
 */
@Composable
fun ReformReversionScreen(
    progress: ReformProgress,
    nowMillis: Long,
    onBack: () -> Unit,
    onPause: () -> Unit,
    onResume: () -> Unit,
    /** Nulo = a tela de bastidores ainda não existe: o botão some em vez de ficar mudo. */
    onOpenBackstage: (() -> Unit)?,
    onOpenProject: () -> Unit,
    onChooseApp: () -> Unit,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier,
    /** Limite atual: depois da análise a reconstrução ainda não roda aqui; o resto segue no chat. */
    onContinueInChat: () -> Unit = {},
) {
    Column(modifier.fillMaxSize()) {
        ReformProgressHeader(
            title = progress.app?.let { "Reformando $it" } ?: "Reforma do app",
            subtitle = "Reforma ao lado do original",
            backDescription = "Voltar para o escopo",
            onBack = onBack,
        )
        if (!progress.started) {
            ReformProgressBlank(
                progress.error, "A reforma parou", "Nenhuma reforma em andamento",
                "Escolha um app do aparelho e trave o escopo. O progresso aparece aqui.",
                onChooseApp, onRetry, Modifier.weight(1f),
            )
            return@Column
        }
        Column(
            Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (progress.locked) StatusChip(StatusKind.Neutral, listOfNotNull("Escopo travado", progress.mode).joinToString(" · "))
            Row(Modifier.fillMaxWidth().height(IntrinsicSize.Max), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Meter(
                    "Reversão", progress.reversalPct, Modifier.weight(1f).fillMaxHeight(),
                    caption = "quanto do app foi entendido", unknown = REFORM_PROGRESS_NOT_REPORTED, barColor = AuraColors.Periwinkle,
                )
                Meter(
                    "Reconstrução", progress.rebuildPct, Modifier.weight(1f).fillMaxHeight(),
                    caption = "quanto da tela nova está pronta", unknown = REFORM_PROGRESS_NOT_REPORTED, barColor = AuraColors.Rose,
                )
                Meter("Fidelidade", progress.fidelityPct, Modifier.weight(1f).fillMaxHeight(), caption = "medida no teste real")
            }
            if (progress.steps.isEmpty()) {
                Text("$REFORM_PROGRESS_NOT_REPORTED os passos.", style = AuraType.Secondary)
            }
            progress.steps.forEachIndexed { i, step -> ReformReversionStep(i + 1, step) }
        }
        Column(
            Modifier.padding(horizontal = 20.dp).padding(bottom = 12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            val error = progress.error
            if (error != null) {
                IncidentBanner(IncidentLevel.Failed, "A reforma parou", error, actionText = "Tentar de novo", onAction = onRetry)
                return@Column
            }
            val minutes = progress.checkpointAtMillis?.let { ((nowMillis - it) / 60_000).coerceAtLeast(0) }
            val ago = minutes?.let { if (it < 1) "há instantes" else if (it < 60) "há $it min" else "há ${it / 60} h" }
            Text(
                when {
                    ago == null -> if (progress.paused) "Pausada. O motor ainda não informa o checkpoint." else "Checkpoint: o motor ainda não informa."
                    progress.paused -> "Pausada. Checkpoint salvo $ago. Toque em Retomar etapas para continuar daqui."
                    else -> "Checkpoint salvo $ago. Se o Android pausar, eu retomo daqui."
                },
                style = AuraType.SecondarySmall.copy(fontWeight = FontWeight.SemiBold, color = AuraColors.Info.fg),
                modifier = Modifier.fillMaxWidth().clip(AuraShapes.Chip).background(AuraColors.Info.bg).padding(horizontal = 12.dp, vertical = 10.dp),
            )
            progress.blocked?.let { blocked ->
                Text(
                    blocked, style = AuraType.SecondarySmall.copy(fontWeight = FontWeight.SemiBold, color = AuraColors.NeedsYou.fg),
                    modifier = Modifier.fillMaxWidth().clip(AuraShapes.Chip).background(AuraColors.NeedsYou.bg).padding(horizontal = 12.dp, vertical = 10.dp),
                )
            }
            if (progress.analysisDone) {
                Text(
                    REFORM_ANALYSIS_LIMIT, style = AuraType.SecondarySmall.copy(fontWeight = FontWeight.SemiBold, color = AuraColors.Info.fg),
                    modifier = Modifier.fillMaxWidth().clip(AuraShapes.Chip).background(AuraColors.Info.bg).padding(horizontal = 12.dp, vertical = 10.dp),
                )
                AuraButtonPrimary("Continuar no chat", onContinueInChat, Modifier.fillMaxWidth(), large = true)
                AuraButtonSecondary("Ver o projeto", onOpenProject, Modifier.fillMaxWidth())
            } else {
                AuraButtonPrimary("Ver o projeto", onOpenProject, Modifier.fillMaxWidth(), large = true)
            }
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                if (progress.paused) AuraButtonSecondary("Retomar etapas", onResume) else AuraButtonSecondary("Pausar próximas etapas", onPause)
                if (onOpenBackstage != null) AuraButtonText("Ver bastidores", onOpenBackstage)
            }
            // Pausar só impede NOVAS etapas: a que já começou pode seguir no motor. Nunca se declara parada sem confirmação.
            Text(
                if (progress.paused) "Próximas etapas pausadas. A etapa que já começou pode continuar no motor."
                else "A etapa já iniciada pode continuar no motor.",
                style = AuraType.SecondarySmall,
            )
        }
    }
}

/** Passo da linha do tempo: marcador, título numerado, "Prova:" (a real se concluído, a prometida se não) e barra se rodando. */
@Composable
private fun ReformReversionStep(number: Int, step: ReformProgressStep) {
    val done = step.state == ReformProgressStepState.Done
    val running = step.state == ReformProgressStepState.Running
    val proof = (if (done) step.proof else step.promisedProof) ?: REFORM_PROGRESS_NOT_REPORTED.replaceFirstChar { it.lowercase() }
    Row(
        Modifier.fillMaxWidth().semantics(mergeDescendants = true) {
            stateDescription = if (done) "Feito" else if (running) "Em andamento" else "Na fila"
        },
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        val marker = Modifier.size(24.dp).clip(AuraShapes.Circle)
        Box(
            when {
                done -> marker.background(AuraColors.Ink)
                running -> marker.background(AuraColors.ProgressGradient)
                else -> marker.background(AuraColors.Bg).border(2.dp, AuraColors.LineStrong, AuraShapes.Circle)
            },
            contentAlignment = Alignment.Center,
        ) {
            if (done) Text("✓", Modifier.clearAndSetSemantics { }, style = AuraType.Chip.copy(color = Color.White, fontSize = 14.sp))
            if (running) Box(Modifier.size(16.dp).clip(AuraShapes.Circle).background(AuraColors.Bg))
        }
        Column(Modifier.weight(1f)) {
            Text(
                "$number · ${step.title}",
                style = AuraType.BodyStrong.copy(lineHeight = 22.sp, color = if (step.state == ReformProgressStepState.Pending) AuraColors.Ink2 else AuraColors.Ink),
            )
            Text("Prova: $proof", style = AuraType.SecondarySmall.copy(fontSize = 13.sp, lineHeight = 18.sp))
            if (running && step.pct != null) {
                AuraProgressBar(
                    step.pct / 100f, Modifier.padding(top = 6.dp), alive = true,
                    contentDescription = "Passo $number: ${step.pct} por cento",
                )
            }
        }
    }
}

@Preview(showBackground = true, backgroundColor = 0xFFF5F4F1)
@Composable
private fun ReformReversionPreview() {
    AuraTheme {
        ReformReversionScreen(
            progress = ReformProgress(
                started = true, locked = true, app = "Player de música", mode = "Visual + fluxos", reversalPct = 68, rebuildPct = 35,
                steps = listOf(
                    ReformProgressStep("Pegar o APK do aparelho", proof = "arquivo copiado", state = ReformProgressStepState.Done),
                    ReformProgressStep("Mapear telas e fluxos", promisedProof = "mapa de telas salvo", state = ReformProgressStepState.Running, pct = 64),
                    ReformProgressStep("Refazer a interface", promisedProof = "telas novas abrem no aparelho"),
                ),
                checkpointAtMillis = 0L,
            ),
            nowMillis = 120_000L, onBack = {}, onPause = {}, onResume = {}, onOpenBackstage = {}, onOpenProject = {},
            onChooseApp = {}, onRetry = {},
        )
    }
}
