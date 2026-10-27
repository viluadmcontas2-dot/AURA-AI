package ai.aura.app.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import ai.aura.app.ui.theme.AuraColors
import ai.aura.app.ui.theme.AuraShapes
import ai.aura.app.ui.theme.AuraSpacing
import ai.aura.app.ui.theme.AuraTheme
import ai.aura.app.ui.theme.AuraType
import ai.aura.app.ui.theme.StatusColors
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.ExperimentalLayoutApi

enum class StatusKind(internal val colors: StatusColors, internal val spoken: String) {
    Ready(AuraColors.Ready, "Pronto"),
    NeedsYou(AuraColors.NeedsYou, "Precisa de você"),
    Risk(AuraColors.Risk, "Risco"),
    Info(AuraColors.Info, "Informação"),
    Neutral(AuraColors.Neutral, "Neutro"),
}

/** Status = ponto + texto (nunca só cor). 32 dp de altura, raio 16. Não é interativo. */
@Composable
fun StatusChip(kind: StatusKind, text: String, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier
            .heightIn(min = 32.dp)
            .clip(AuraShapes.Chip)
            .background(kind.colors.bg)
            .padding(horizontal = 12.dp)
            .semantics(mergeDescendants = true) { contentDescription = "${kind.spoken}: $text" },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Box(Modifier.size(8.dp).clip(AuraShapes.Circle).background(kind.colors.dot))
        Text(text, style = AuraType.Chip.copy(color = kind.colors.fg))
    }
}

/** Filtro: 36 dp visuais, área de toque de 44 dp. */
@Composable
fun AuraFilterChip(
    text: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val source = remember { MutableInteractionSource() }
    Box(
        modifier = modifier
            .heightIn(min = AuraSpacing.TouchTarget)
            .clickable(interactionSource = source, indication = null, role = Role.Tab, onClick = onClick)
            .semantics { this.selected = selected },
        contentAlignment = Alignment.Center,
    ) {
        Box(
            Modifier
                .heightIn(min = 36.dp)
                .clip(AuraShapes.Pill)
                .background(if (selected) AuraColors.Ink else AuraColors.Surface)
                .border(1.dp, if (selected) AuraColors.Ink else AuraColors.LineStrong, AuraShapes.Pill)
                .auraFocusRing(source, AuraShapes.Pill)
                .padding(horizontal = 14.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text,
                style = AuraType.SecondarySmall.copy(
                    color = if (selected) androidx.compose.ui.graphics.Color.White else AuraColors.Ink,
                    fontWeight = if (selected) FontWeight.Bold else FontWeight.SemiBold,
                ),
            )
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Preview(showBackground = true, backgroundColor = 0xFFF5F4F1)
@Composable
private fun ChipsPreview() {
    AuraTheme {
        FlowRow(Modifier.padding(16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            StatusChip(StatusKind.Ready, "Pronto")
            StatusChip(StatusKind.NeedsYou, "Aprovar")
            StatusChip(StatusKind.Risk, "Risco alto")
            StatusChip(StatusKind.Info, "Rodando")
            StatusChip(StatusKind.Neutral, "Na fila")
            AuraFilterChip("Todos", true, {})
            AuraFilterChip("Projetos", false, {})
        }
    }
}
