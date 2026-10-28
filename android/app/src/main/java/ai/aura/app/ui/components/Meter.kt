package ai.aura.app.ui.components

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
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** O que o leitor de tela lê: nome, valor (0..100) ou "ainda não medida", e a legenda. */
fun meterSpoken(label: String, percent: Int?, caption: String? = null, unknown: String = "Ainda não medida"): String {
    val value = percent?.coerceIn(0, 100)?.let { "$it por cento" } ?: unknown.replaceFirstChar { it.lowercase() }
    return "$label: $value" + (caption?.let { ", $it" } ?: "")
}

/**
 * Medidor (%) do design: legenda, número grande e barra. [percent] nulo = o motor não mediu:
 * mostra [unknown] e nunca um número inventado. Com [onClick] vira alvo de toque.
 */
@Composable
fun Meter(
    label: String,
    percent: Int?,
    modifier: Modifier = Modifier,
    caption: String? = null,
    unknown: String = "Ainda não medida",
    barColor: Color = AuraColors.Ink,
    onClick: (() -> Unit)? = null,
) {
    val value = percent?.coerceIn(0, 100)
    val source = remember { MutableInteractionSource() }
    var m = modifier.clip(AuraShapes.Card).background(AuraColors.Surface).border(1.dp, AuraColors.Line, AuraShapes.Card)
    if (onClick != null) {
        m = m.auraFocusRing(source, AuraShapes.Card)
            .clickable(interactionSource = source, indication = null, role = Role.Button, onClick = onClick)
    }
    Column(
        m.semantics(mergeDescendants = true) {
            contentDescription = meterSpoken(label, value, caption, unknown)
            if (value != null) progressBarRangeInfo = ProgressBarRangeInfo(value.toFloat(), 0f..100f)
        }.padding(horizontal = 8.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text(label, style = AuraType.Caption.copy(letterSpacing = 0.sp), maxLines = 1, overflow = TextOverflow.Ellipsis)
        Box(Modifier.heightIn(min = 40.dp), contentAlignment = Alignment.CenterStart) {
            if (value != null) {
                Text("$value%", style = AuraType.Display.copy(fontSize = 32.sp, lineHeight = 36.sp))
            } else {
                Text(unknown, style = AuraType.SecondarySmall.copy(fontWeight = FontWeight.Bold, color = AuraColors.Neutral.fg))
            }
        }
        Box(Modifier.fillMaxWidth().height(6.dp).clip(AuraShapes.Pill).background(AuraColors.Line)) {
            if (value != null && value > 0) {
                Box(Modifier.fillMaxHeight().fillMaxWidth(value / 100f).clip(AuraShapes.Pill).background(barColor))
            }
        }
        if (caption != null) Text(caption, style = AuraType.Caption.copy(fontWeight = FontWeight.Normal, letterSpacing = 0.sp))
    }
}

@Preview(showBackground = true, backgroundColor = 0xFFF5F4F1)
@Composable
private fun MeterPreview() {
    AuraTheme {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Meter("Reversão", 68, Modifier.fillMaxWidth(), caption = "quanto do app foi entendido", barColor = AuraColors.Periwinkle)
            Meter("Fidelidade", null, Modifier.fillMaxWidth(), caption = "medida no teste real")
        }
    }
}
