package ai.aura.app.ui.screens.reform

import ai.aura.app.ui.components.AuraButtonPrimary
import ai.aura.app.ui.components.AuraButtonSecondary
import ai.aura.app.ui.components.AuraButtonText
import ai.aura.app.ui.components.AuraCard
import ai.aura.app.ui.components.Meter
import ai.aura.app.ui.components.StatusChip
import ai.aura.app.ui.components.StatusKind
import ai.aura.app.ui.screens.activity.IncidentBanner
import ai.aura.app.ui.screens.activity.IncidentLevel
import ai.aura.app.ui.theme.AuraColors
import ai.aura.app.ui.theme.AuraShapes
import ai.aura.app.ui.theme.AuraTheme
import ai.aura.app.ui.theme.AuraType
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp

/** Uma linha de "Artefatos" (vem de quem lê a pasta do projeto, não do motor). [badge] = selo curto, ex.: "Gerando". */
data class ReformProjectArtifact(val id: String, val title: String, val subtitle: String, val badge: String? = null)

/**
 * Reformar 6 · Projeto da reforma: situação, três medidores (Fidelidade abre a comparação), artefatos, peças
 * reaproveitáveis e estilo. [pieces], [piecesUsedBy] e [style] nulos = "O motor ainda não informa" (nada inventado).
 * Abre sem motor: vazio e erro têm uma ação cada.
 */
@Composable
fun ReformProjectScreen(
    progress: ReformProgress,
    artifacts: List<ReformProjectArtifact>,
    pieces: String?,
    piecesUsedBy: String?,
    style: String?,
    onBack: () -> Unit,
    onOpenFidelity: () -> Unit,
    onOpenArtifact: (String) -> Unit,
    onChangeStyle: () -> Unit,
    onOpenTools: () -> Unit,
    onContinue: () -> Unit,
    onChooseApp: () -> Unit,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val error = progress.error
    Column(modifier.fillMaxSize()) {
        ReformProgressHeader(progress.app?.let { "$it · Reforma" } ?: "Projeto de reforma", null, "Voltar aos projetos", onBack)
        if (!progress.started && artifacts.isEmpty() && pieces == null && style == null) {
            ReformProgressBlank(
                error, "A reforma parou", "Nenhuma reforma neste projeto",
                "Escolha um app do aparelho para reformar. O que for gerado fica guardado aqui.",
                onChooseApp, onRetry, Modifier.weight(1f),
            )
            return@Column
        }
        Column(
            Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            val steps = progress.steps
            val phase = when {
                !progress.started -> null
                progress.paused -> StatusKind.NeedsYou to "Pausada"
                steps.isNotEmpty() && steps.all { it.state == ReformProgressStepState.Done } -> StatusKind.Ready to "Passos concluídos"
                steps.any { it.state == ReformProgressStepState.Running } || progress.reversalPct != null -> StatusKind.Info to "Rodando"
                else -> StatusKind.Neutral to "Na fila"
            }
            if (progress.locked || phase != null) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    if (progress.locked) StatusChip(StatusKind.Neutral, "Escopo travado")
                    if (phase != null) StatusChip(phase.first, phase.second)
                }
            }
            Row(Modifier.fillMaxWidth().height(IntrinsicSize.Max), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Meter(
                    "Reversão", progress.reversalPct, Modifier.weight(1f).fillMaxHeight(),
                    unknown = REFORM_PROGRESS_NOT_REPORTED, barColor = AuraColors.Periwinkle,
                )
                Meter(
                    "Reconstrução", progress.rebuildPct, Modifier.weight(1f).fillMaxHeight(),
                    unknown = REFORM_PROGRESS_NOT_REPORTED, barColor = AuraColors.Rose,
                )
                Meter("Fidelidade", progress.fidelityPct, Modifier.weight(1f).fillMaxHeight(), onClick = onOpenFidelity)
            }
            // Também dá para tocar no medidor, mas o caminho para a Fidelidade tem que estar à vista.
            AuraButtonSecondary("Ver fidelidade", onOpenFidelity)
            Text("Artefatos", style = AuraType.Section, modifier = Modifier.semantics { heading() })
            if (artifacts.isEmpty()) Text("$REFORM_PROGRESS_NOT_REPORTED os artefatos.", style = AuraType.Secondary)
            artifacts.forEach { a ->
                AuraCard(Modifier.fillMaxWidth(), onClick = { onOpenArtifact(a.id) }) {
                    Text(a.title, style = AuraType.BodyStrong)
                    Text(a.subtitle, style = AuraType.SecondarySmall)
                    if (a.badge != null) {
                        Text(
                            a.badge, style = AuraType.Chip.copy(color = AuraColors.Info.fg),
                            modifier = Modifier.padding(top = 4.dp).clip(AuraShapes.Chip).background(AuraColors.Info.bg)
                                .padding(horizontal = 12.dp, vertical = 6.dp),
                        )
                    }
                }
            }
            AuraCard(Modifier.fillMaxWidth()) {
                Text("Peças reaproveitáveis", style = AuraType.Section, modifier = Modifier.semantics { heading() })
                Text(pieces ?: "$REFORM_PROGRESS_NOT_REPORTED as peças.", style = AuraType.Secondary)
                if (pieces != null && piecesUsedBy != null) Text(piecesUsedBy, style = AuraType.SecondarySmall)
            }
            AuraCard(Modifier.fillMaxWidth()) {
                Text("Estilo aplicado", style = AuraType.Section, modifier = Modifier.semantics { heading() })
                if (style != null) Text(style, style = AuraType.BodyStrong)
                else Text("$REFORM_PROGRESS_NOT_REPORTED o estilo.", style = AuraType.Secondary)
                AuraButtonSecondary("Mudar estilo", onChangeStyle, Modifier.padding(top = 4.dp))
            }
        }
        Column(Modifier.padding(horizontal = 20.dp).padding(bottom = 12.dp)) {
            if (error != null) {
                IncidentBanner(IncidentLevel.Failed, "A reforma parou", error, actionText = "Tentar de novo", onAction = onRetry)
            } else {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    AuraButtonText("Ferramentas", onOpenTools)
                    AuraButtonPrimary("Continuar reforma", onContinue, Modifier.weight(1f), large = true)
                }
            }
        }
    }
}

@Preview(showBackground = true, backgroundColor = 0xFFF5F4F1)
@Composable
private fun ReformProjectPreview() {
    AuraTheme {
        ReformProjectScreen(
            progress = ReformProgress(
                started = true, locked = true, app = "Player de música", reversalPct = 68, rebuildPct = 35,
                steps = listOf(ReformProgressStep("Mapear telas e fluxos", state = ReformProgressStepState.Running, pct = 64)),
            ),
            artifacts = listOf(
                ReformProjectArtifact("a1", "Relatório da engenharia reversa", "PDF"),
                ReformProjectArtifact("a3", "APK reformado", "Em andamento", badge = "Gerando"),
            ),
            pieces = "12 ícones · paleta · tipografia", piecesUsedBy = "Usadas também em Navegador GPS",
            style = "AURA Premium · modo carro",
            onBack = {}, onOpenFidelity = {}, onOpenArtifact = {}, onChangeStyle = {}, onOpenTools = {}, onContinue = {},
            onChooseApp = {}, onRetry = {},
        )
    }
}
