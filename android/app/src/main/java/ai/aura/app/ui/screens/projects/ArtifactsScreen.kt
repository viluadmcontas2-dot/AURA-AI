package ai.aura.app.ui.screens.projects

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import ai.aura.app.ui.components.AuraCard
import ai.aura.app.ui.components.AuraFilterChip
import ai.aura.app.ui.components.AuraProgressBar
import ai.aura.app.ui.components.EmptyState
import ai.aura.app.ui.components.StatusChip
import ai.aura.app.ui.components.StatusKind
import ai.aura.app.ui.theme.AuraColors
import ai.aura.app.ui.theme.AuraShapes
import ai.aura.app.ui.theme.AuraTheme
import ai.aura.app.ui.theme.AuraType

internal fun kindTint(k: ArtifactKind): Color = when (k) {
    ArtifactKind.Apk -> AuraColors.CategoryAndroid
    ArtifactKind.Document -> AuraColors.CategoryEngineering
    ArtifactKind.Image -> AuraColors.CategoryOrganization
    ArtifactKind.Report -> AuraColors.CategoryClarity
}

internal fun kindIcon(k: ArtifactKind): PIcon = when (k) {
    ArtifactKind.Apk -> PIcon.Box
    ArtifactKind.Document, ArtifactKind.Report -> PIcon.Doc
    ArtifactKind.Image -> PIcon.Image
}

/** Artefatos.dc.html: filtros por tipo e grade de 2 colunas. Sem artefato real: vazio que convida a agir. */
@Composable
@OptIn(ExperimentalLayoutApi::class)
fun ArtifactsScreen(
    scopeLabel: String,
    artifacts: List<Artifact>,
    onBack: () -> Unit,
    onOpen: (String) -> Unit,
    onAsk: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var filter by rememberSaveable { mutableStateOf("") } // "" = Todos
    val shown = artifacts.filter { filter.isEmpty() || it.kind.name == filter }
    Column(modifier.fillMaxSize()) {
        ProjHeader("Artefatos", onBack, subtitle = scopeLabel)
        if (artifacts.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                EmptyState(
                    title = "Nenhum artefato ainda",
                    body = "Quando o AURA gerar um APK, relatório ou imagem, ele aparece aqui.",
                    actionText = "Pedir um artefato",
                    onAction = onAsk,
                    glyph = "▣",
                )
            }
            return@Column
        }
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 4.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                AuraFilterChip("Todos", filter.isEmpty(), { filter = "" })
                ArtifactKind.entries.forEach { k -> AuraFilterChip(k.filter, filter == k.name, { filter = k.name }) }
            }
            if (shown.isEmpty()) {
                Text("Nenhum item em ${ArtifactKind.entries.firstOrNull { it.name == filter }?.filter ?: "este filtro"}. Toque em Todos para ver o resto.", style = AuraType.Secondary, modifier = Modifier.padding(vertical = 16.dp))
            }
            shown.chunked(2).forEach { pair ->
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    pair.forEach { a -> ArtifactTile(a, { onOpen(a.id) }, Modifier.weight(1f)) }
                    if (pair.size == 1) Box(Modifier.weight(1f))
                }
            }
        }
    }
}

@Composable
private fun ArtifactTile(a: Artifact, onClick: () -> Unit, modifier: Modifier) {
    AuraCard(modifier, onClick = onClick, contentDescription = "${a.name}, ${artifactLine(a)}") {
        Box(Modifier.fillMaxWidth().height(80.dp).clip(AuraShapes.Chip).background(kindTint(a.kind)), contentAlignment = Alignment.Center) {
            PIconView(kindIcon(a.kind), size = 32.dp)
        }
        Text(a.name, style = AuraType.Secondary.copy(color = AuraColors.Ink, fontWeight = FontWeight.Bold), maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 4.dp))
        Text(a.subtitle, style = AuraType.Caption.copy(fontWeight = FontWeight.Normal), maxLines = 1, overflow = TextOverflow.Ellipsis)
        Box(Modifier.padding(top = 6.dp)) { ArtifactStatus(a) }
    }
}

/** Gerando = barra viva; com prova = verde; sem prova = neutro (nunca "pronto" sem evidência). */
@Composable
internal fun ArtifactStatus(a: Artifact) {
    val g = a.generating
    when {
        g != null -> Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("Gerando…", style = AuraType.Chip.copy(color = AuraColors.Info.fg))
            AuraProgressBar(g, alive = true, contentDescription = "Gerando ${a.name}")
        }
        a.proofs.isNotEmpty() -> StatusChip(if (a.proofs.first().ok) StatusKind.Ready else StatusKind.NeedsYou, a.proofs.first().text)
        else -> StatusChip(StatusKind.Neutral, "Sem prova ainda")
    }
}

@Preview(showBackground = true, backgroundColor = 0xFFF5F4F1, heightDp = 780)
@Composable
private fun ArtifactsPreview() {
    AuraTheme { ArtifactsScreen("Minhas Finanças", ExampleData.artifacts, {}, {}, {}) }
}

@Preview(showBackground = true, backgroundColor = 0xFFF5F4F1, heightDp = 500)
@Composable
private fun ArtifactsEmptyPreview() {
    AuraTheme { ArtifactsScreen("Minhas Finanças", emptyList(), {}, {}, {}) }
}
