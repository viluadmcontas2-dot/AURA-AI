package ai.aura.app.ui.screens.chat

import ai.aura.app.ui.components.AuraButtonDestructive
import ai.aura.app.ui.components.AuraButtonPrimary
import ai.aura.app.ui.components.AuraButtonSecondary
import ai.aura.app.ui.components.AuraButtonText
import ai.aura.app.ui.engine.Job
import ai.aura.app.ui.engine.JobRisk
import ai.aura.app.ui.engine.JobStatus
import ai.aura.app.ui.theme.AuraColors
import ai.aura.app.ui.theme.AuraShapes
import ai.aura.app.ui.theme.AuraTheme
import ai.aura.app.ui.theme.AuraType
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Aprovação (Chat-4-Aprovacao): mostra o pedido imutável em linguagem humana. O botão Aprovar só
 * habilita com o status real awaiting_approval vindo do engine; sem isso, a tela só informa.
 * Tocar no fundo ou em "Decidir depois" fecha sem aprovar nem recusar.
 */
@Composable
internal fun ApprovalSheet(
    job: Job,
    busy: Boolean,
    onApprove: () -> Unit,
    onDecline: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val canApprove = job.status == JobStatus.AwaitingApproval && !busy
    val risky = job.risk == JobRisk.Spend || job.risk == JobRisk.Critical
    val scrimSource = remember { MutableInteractionSource() }
    Box(modifier.fillMaxSize()) {
        Box(
            Modifier.fillMaxSize().background(AuraColors.Ink.copy(alpha = 0.5f))
                .clickable(interactionSource = scrimSource, indication = null, onClickLabel = "Fechar sem decidir", onClick = onDismiss)
                .clearAndSetSemantics { },
        )
        Column(
            Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .heightIn(max = 640.dp)
                .clip(AuraShapes.Sheet)
                .background(AuraColors.Surface)
                .pointerInput(Unit) { detectTapGestures { } }
                .padding(start = 20.dp, end = 20.dp, top = 10.dp, bottom = 20.dp)
                .semantics { paneTitle = "Aprovar pedido" },
        ) {
            Box(Modifier.align(Alignment.CenterHorizontally).width(36.dp).height(4.dp).clip(AuraShapes.Pill).background(AuraColors.LineStrong).clearAndSetSemantics { })
            Column(Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState())) {
                val tone = if (job.risk == JobRisk.Critical) AuraColors.Risk else AuraColors.NeedsYou
                Box(Modifier.padding(top = 14.dp).size(44.dp).clip(AuraShapes.Circle).background(tone.bg).clearAndSetSemantics { }, Alignment.Center) {
                    Text("!", style = AuraType.Section.copy(color = tone.fg))
                }
                Text("Aprovar este pedido?", style = AuraType.TabTitle.copy(fontSize = 24.sp, lineHeight = 28.sp), modifier = Modifier.padding(top = 10.dp).semantics { heading() })
                Text(job.title, style = AuraType.Section, modifier = Modifier.padding(top = 4.dp))

                Column(
                    Modifier.padding(top = 12.dp).fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(AuraColors.SurfaceSunken).padding(horizontal = 14.dp, vertical = 4.dp),
                ) {
                    DetailRow("Ferramenta", kindLabel(job.kind))
                    DetailRow("Nível", riskLabel(job.risk))
                    DetailRow("Código do pedido", job.requestId, mono = true)
                }

                Text(
                    riskWarning(job.risk),
                    style = AuraType.SecondarySmall.copy(color = tone.fg, fontWeight = FontWeight.Bold),
                    modifier = Modifier.padding(top = 10.dp).fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(tone.bg).padding(horizontal = 14.dp, vertical = 10.dp),
                )

                Column(
                    Modifier.padding(top = 10.dp).fillMaxWidth().clip(RoundedCornerShape(16.dp)).border(1.dp, AuraColors.Line, RoundedCornerShape(16.dp)).padding(horizontal = 14.dp, vertical = 12.dp),
                ) {
                    Text("O QUE ISSO FAZ?", style = AuraType.Caption)
                    Text(
                        "Você aprova exatamente este pedido, como está acima. Ele não muda depois da aprovação; se precisar mudar, o AURA pede de novo.",
                        style = AuraType.SecondarySmall.copy(color = AuraColors.Ink),
                        modifier = Modifier.padding(top = 6.dp),
                    )
                }
                if (job.status != JobStatus.AwaitingApproval) {
                    Text(
                        "Este pedido não está mais esperando aprovação (${statusText(job.status).lowercase()}).",
                        style = AuraType.SecondarySmall.copy(fontWeight = FontWeight.SemiBold),
                        modifier = Modifier.padding(top = 10.dp),
                    )
                }
            }
            Column(Modifier.padding(top = 12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (risky) {
                    AuraButtonDestructive("Aprovar pedido", onApprove, Modifier.fillMaxWidth(), enabled = canApprove)
                } else {
                    AuraButtonPrimary("Aprovar pedido", onApprove, Modifier.fillMaxWidth(), enabled = canApprove, large = true)
                }
                AuraButtonSecondary("Recusar pedido", onDecline, Modifier.fillMaxWidth(), enabled = job.status == JobStatus.AwaitingApproval && !busy)
                AuraButtonText("Decidir depois", onDismiss, Modifier.align(Alignment.CenterHorizontally))
            }
        }
    }
}

@Composable
private fun DetailRow(label: String, value: String, mono: Boolean = false) {
    Row(
        Modifier.fillMaxWidth().heightIn(min = 36.dp).semantics(mergeDescendants = true) { contentDescription = "$label: $value" },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(label, style = AuraType.SecondarySmall.copy(fontSize = 13.sp), modifier = Modifier.weight(1f))
        Text(
            value,
            style = if (mono) AuraType.Mono.copy(color = AuraColors.Ink) else AuraType.SecondarySmall.copy(color = AuraColors.Ink, fontWeight = FontWeight.SemiBold),
        )
    }
}
