package ai.aura.app.ui.screens.reform

import ai.aura.app.ui.components.AuraButtonPrimary
import ai.aura.app.ui.components.AuraButtonSecondary
import ai.aura.app.ui.components.AuraFilterChip
import ai.aura.app.ui.components.AuraProgressBar
import ai.aura.app.ui.components.Meter
import ai.aura.app.ui.components.StatusChip
import ai.aura.app.ui.components.StatusKind
import ai.aura.app.ui.screens.activity.IncidentBanner
import ai.aura.app.ui.screens.activity.IncidentLevel
import ai.aura.app.ui.theme.AuraColors
import ai.aura.app.ui.theme.AuraTheme
import ai.aura.app.ui.theme.AuraType
import androidx.compose.foundation.Image
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp

/** Captura de uma tela do app, nas duas versões. Sem imagem a tela mostra "Sem captura ainda". */
data class ReformFidelityShot(val title: String, val original: ImageBitmap? = null, val reformed: ImageBitmap? = null)

/** Diferença entre original e reformado. [missing] = ainda não refeito ("Falta"); senão é só informação. */
data class ReformFidelityDiff(val text: String, val missing: Boolean = false)

/** Detalhe do teste real. O motor ainda não emite estes campos: o que ficar nulo/vazio a tela diz que o motor não informa. */
data class ReformFidelityDetails(
    val visualPct: Int? = null,
    val flowPct: Int? = null,
    val functionsPct: Int? = null,
    val flowsPassed: Int? = null,
    val flowsTotal: Int? = null,
    val shots: List<ReformFidelityShot> = emptyList(),
    val differences: List<ReformFidelityDiff> = emptyList(),
)

/**
 * Reformar 4 · Original e reformado. Fidelidade só mostra número depois do teste real
 * ([ReformProgress.fidelityPct] nulo = "Ainda não medida" e UMA ação: testar). Abre sem motor (vazio e erro com uma ação).
 */
@Composable
fun ReformFidelityScreen(
    progress: ReformProgress,
    details: ReformFidelityDetails,
    onBack: () -> Unit,
    onRunTest: () -> Unit,
    onInstall: () -> Unit,
    onFixDifferences: () -> Unit,
    onChooseApp: () -> Unit,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val pct = progress.fidelityPct
    val error = progress.error
    Column(modifier.fillMaxSize()) {
        ReformProgressHeader("Original e reformado", progress.app, "Voltar para a reforma", onBack)
        if (!progress.started) {
            ReformProgressBlank(
                error, "A comparação parou", "Nenhuma reforma para comparar",
                "Faça uma reforma e volte aqui para ver a fidelidade.", onChooseApp, onRetry, Modifier.weight(1f),
            )
            return@Column
        }
        Column(
            Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (pct == null) {
                Meter(
                    "Fidelidade", null, Modifier.fillMaxWidth(),
                    caption = "A fidelidade só aparece depois do teste real no aparelho.",
                )
            } else {
                if (details.shots.isNotEmpty()) ReformFidelityCompare(details.shots)
                Meter("Fidelidade", pct, Modifier.fillMaxWidth(), caption = "medida no teste real no aparelho")
                val rows = listOf("Visual" to details.visualPct, "Fluxo" to details.flowPct, "Funções" to details.functionsPct)
                    .mapNotNull { (label, value) -> value?.let { label to it.coerceIn(0, 100) } }
                if (rows.isEmpty()) Text("$REFORM_PROGRESS_NOT_REPORTED o detalhe por área.", style = AuraType.Secondary)
                rows.forEach { (label, value) ->
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text(label, Modifier.width(64.dp), style = AuraType.SecondarySmall)
                        AuraProgressBar(value / 100f, Modifier.weight(1f), contentDescription = "$label: $value por cento")
                        Text("$value%", style = AuraType.SecondarySmall.copy(fontWeight = FontWeight.Bold, color = AuraColors.Ink))
                    }
                }
                val passed = details.flowsPassed
                val total = details.flowsTotal
                if (passed != null && total != null) {
                    StatusChip(
                        if (passed == total) StatusKind.Ready else StatusKind.NeedsYou,
                        "Teste automático: $passed de $total fluxos passaram",
                    )
                }
                Text("Diferenças", style = AuraType.Section, modifier = Modifier.semantics { heading() })
                if (details.differences.isEmpty()) Text("$REFORM_PROGRESS_NOT_REPORTED as diferenças.", style = AuraType.Secondary)
                details.differences.forEach {
                    Row(Modifier.fillMaxWidth().heightIn(min = 44.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text(it.text, Modifier.weight(1f), style = AuraType.Secondary)
                        if (it.missing) StatusChip(StatusKind.NeedsYou, "Falta") else StatusChip(StatusKind.Info, "Info")
                    }
                }
            }
        }
        Column(Modifier.padding(horizontal = 20.dp).padding(bottom = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (error != null) {
                IncidentBanner(IncidentLevel.Failed, "A comparação parou", error, actionText = "Tentar de novo", onAction = onRetry)
            } else if (pct == null) {
                AuraButtonPrimary("Testar no aparelho", onRunTest, Modifier.fillMaxWidth(), large = true)
            } else {
                AuraButtonPrimary("Instalar ao lado do original", onInstall, Modifier.fillMaxWidth(), large = true)
                AuraButtonSecondary("Corrigir diferenças", onFixDifferences, Modifier.fillMaxWidth())
            }
        }
    }
}

/** Comparador: escolhe a tela nos chips e mostra Original × Reformado lado a lado. */
@Composable
private fun ReformFidelityCompare(shots: List<ReformFidelityShot>) {
    var selected by rememberSaveable { mutableStateOf(0) }
    val index = selected.coerceIn(0, shots.lastIndex)
    val shot = shots[index]
    Row(Modifier.horizontalScroll(rememberScrollState())) {
        shots.forEachIndexed { i, s -> AuraFilterChip(s.title, i == index, { selected = i }) }
    }
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        listOf("Original" to shot.original, "Reformado" to shot.reformed).forEach { (caption, image) ->
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                val shape = RoundedCornerShape(20.dp)
                Box(
                    Modifier.fillMaxWidth().height(116.dp).clip(shape).background(AuraColors.SurfaceSunken).border(1.dp, AuraColors.LineStrong, shape),
                    contentAlignment = Alignment.Center,
                ) {
                    if (image != null) {
                        Image(image, "$caption, ${shot.title}", Modifier.fillMaxSize())
                    } else {
                        Text("Sem captura ainda", style = AuraType.SecondarySmall, textAlign = TextAlign.Center)
                    }
                }
                Text(caption, style = AuraType.Caption, textAlign = TextAlign.Center)
            }
        }
    }
}

@Preview(showBackground = true, backgroundColor = 0xFFF5F4F1)
@Composable
private fun ReformFidelityPreview() {
    AuraTheme {
        ReformFidelityScreen(
            progress = ReformProgress(started = true, app = "Player de música", fidelityPct = 91),
            details = ReformFidelityDetails(
                visualPct = 94, flowPct = 88, functionsPct = 91, flowsPassed = 9, flowsTotal = 11,
                shots = listOf(ReformFidelityShot("Tocando agora"), ReformFidelityShot("Lista")),
                differences = listOf(ReformFidelityDiff("Capa do álbum maior"), ReformFidelityDiff("Equalizador ainda não refeito", missing = true)),
            ),
            onBack = {}, onRunTest = {}, onInstall = {}, onFixDifferences = {}, onChooseApp = {}, onRetry = {},
        )
    }
}
