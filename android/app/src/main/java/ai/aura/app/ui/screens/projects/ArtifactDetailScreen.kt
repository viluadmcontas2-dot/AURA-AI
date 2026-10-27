package ai.aura.app.ui.screens.projects

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import ai.aura.app.ui.components.AuraButtonPrimary
import ai.aura.app.ui.components.AuraButtonSecondary
import ai.aura.app.ui.components.AuraButtonText
import ai.aura.app.ui.components.StatusChip
import ai.aura.app.ui.components.StatusKind
import ai.aura.app.ui.theme.AuraColors
import ai.aura.app.ui.theme.AuraShapes
import ai.aura.app.ui.theme.AuraTheme
import ai.aura.app.ui.theme.AuraType

/**
 * Artefato-Detalhe.dc.html. Ação principal por tipo: APK = "Instalar APK" (vira um job que PEDE
 * aprovação; nada é instalado daqui), demais = "Compartilhar artefato". Restaurar versão também
 * vira job com aprovação. [notice] mostra o retorno honesto ("pedido enviado, aprove na Atividade").
 */
@Composable
@OptIn(ExperimentalLayoutApi::class)
fun ArtifactDetailScreen(
    artifact: Artifact,
    projectName: String?,
    notice: String?,
    error: String?,
    busy: Boolean,
    onBack: () -> Unit,
    onInstall: () -> Unit,
    onShare: () -> Unit,
    onOpenSource: (String) -> Unit,
    onCopyHash: (String) -> Unit,
    onRestore: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val isApk = artifact.kind == ArtifactKind.Apk
    val meta = listOfNotNull(artifact.sizeLabel, artifact.whenLabel, projectName).joinToString(" · ")
    Column(modifier.fillMaxSize()) {
        ProjHeader("Arquivo gerado", onBack, subtitle = artifact.subtitle, backDescription = "Voltar para Artefatos")
        Column(
            Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(80.dp).clip(RoundedCornerShape(26.dp)).background(kindTint(artifact.kind)), contentAlignment = Alignment.Center) {
                    PIconView(kindIcon(artifact.kind), size = 36.dp)
                }
                Column(Modifier.weight(1f)) {
                    Text(artifact.name, style = AuraType.TabTitle.copy(fontSize = 22.sp, lineHeight = 26.sp))
                    if (meta.isNotBlank()) Text(meta, style = AuraType.SecondarySmall, modifier = Modifier.padding(top = 4.dp))
                }
            }
            if (artifact.generating != null) {
                ArtifactStatus(artifact)
            } else if (artifact.proofs.isEmpty()) {
                StatusChip(StatusKind.Neutral, "Sem prova ainda")
            } else {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    artifact.proofs.forEach { StatusChip(if (it.ok) StatusKind.Ready else StatusKind.NeedsYou, it.text) }
                }
            }
            artifact.sha256?.let { hash ->
                Row(
                    Modifier.fillMaxWidth().heightIn(min = 48.dp).clip(AuraShapes.Chip).background(AuraColors.SurfaceSunken).padding(start = 14.dp, end = 4.dp),
                    verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text("SHA-256 ${hash.shortHash()}", style = AuraType.Mono.copy(fontSize = 13.sp, color = AuraColors.Ink), maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                    IconTap(PIcon.Copy, "Copiar hash SHA-256", { onCopyHash(hash) })
                }
            }
            if (notice != null) NoteBanner(notice, AuraColors.NeedsYou, PIcon.Lock)
            if (error != null) NoteBanner(error, AuraColors.Risk, PIcon.Lock)
            val ready = artifact.generating == null
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (isApk) AuraButtonPrimary("Instalar APK", onInstall, Modifier.fillMaxWidth(), enabled = ready && !busy, large = true)
                else AuraButtonPrimary("Compartilhar artefato", onShare, Modifier.fillMaxWidth(), enabled = ready, large = true)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (isApk) AuraButtonSecondary("Compartilhar", onShare, Modifier.weight(1f), enabled = ready)
                    artifact.sourceUrl?.let { url -> AuraButtonSecondary("Abrir no GitHub", { onOpenSource(url) }, Modifier.weight(1f)) }
                }
            }
            if (artifact.origin.isNotEmpty()) InfoCard("De onde veio") {
                artifact.origin.forEach { (k, v) ->
                    Row(Modifier.heightIn(min = 40.dp).fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Text(k, style = AuraType.Secondary.copy(color = AuraColors.Ink), modifier = Modifier.weight(1f))
                        Text(v, style = AuraType.Mono.copy(fontSize = 13.sp, color = AuraColors.Ink))
                    }
                }
            }
            if (artifact.versions.isNotEmpty()) InfoCard("Versões") {
                artifact.versions.forEach { v ->
                    Row(Modifier.heightIn(min = 44.dp).fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text(v.label, style = AuraType.Secondary.copy(color = AuraColors.Ink, fontWeight = if (v.current) FontWeight.Bold else FontWeight.Normal), modifier = Modifier.weight(1f))
                        if (v.current) StatusChip(StatusKind.Ready, "Atual")
                        else AuraButtonText("Restaurar", { onRestore(v.label) }, enabled = !busy, contentDescription = "Restaurar versão ${v.label}")
                    }
                }
            }
        }
    }
}

@Composable
private fun InfoCard(title: String, content: @Composable () -> Unit) {
    Column(Modifier.fillMaxWidth().clip(AuraShapes.Card).background(AuraColors.Surface).border(1.dp, AuraColors.Line, AuraShapes.Card).padding(horizontal = 16.dp, vertical = 12.dp)) {
        SectionLabel(title)
        content()
    }
}

@Preview(showBackground = true, backgroundColor = 0xFFF5F4F1, heightDp = 860)
@Composable
private fun ArtifactDetailPreview() {
    AuraTheme { ArtifactDetailScreen(ExampleData.artifacts[1], "Minhas Finanças", null, null, false, {}, {}, {}, {}, {}, {}) }
}

@Preview(showBackground = true, backgroundColor = 0xFFF5F4F1, heightDp = 560)
@Composable
private fun ArtifactDetailPlainPreview() {
    AuraTheme { ArtifactDetailScreen(ExampleData.artifacts[4], "Minhas Finanças", null, null, false, {}, {}, {}, {}, {}, {}) }
}
