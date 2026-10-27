package ai.aura.app.ui.screens.devices

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import ai.aura.app.ui.theme.AuraColors
import ai.aura.app.ui.theme.AuraShapes
import ai.aura.app.ui.theme.AuraSpacing
import ai.aura.app.ui.theme.AuraType

/** Cabeçalho de aba: título 28/32 e uma ação opcional à direita (alvo de 44 dp). */
@Composable
internal fun TabHeader(
    title: String,
    modifier: Modifier = Modifier,
    trailing: @Composable () -> Unit = {},
) {
    Row(
        modifier
            .fillMaxWidth()
            .heightIn(min = 64.dp)
            .padding(horizontal = AuraSpacing.ScreenGutter, vertical = AuraSpacing.S2),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(title, style = AuraType.TabTitle, modifier = Modifier.weight(1f).semantics { heading() })
        trailing()
    }
}

/** Cabeçalho de subtela: voltar (44 dp) + título e legenda. */
@Composable
internal fun BackHeader(title: String, subtitle: String?, onBack: () -> Unit, modifier: Modifier = Modifier) {
    val source = remember { MutableInteractionSource() }
    Row(
        modifier.fillMaxWidth().padding(horizontal = AuraSpacing.S2, vertical = AuraSpacing.S2),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(AuraSpacing.S1),
    ) {
        Box(
            Modifier
                .size(AuraSpacing.TouchTarget)
                .clip(AuraShapes.Circle)
                .clickable(interactionSource = source, indication = null, role = Role.Button, onClick = onBack)
                .semantics { contentDescription = "Voltar" },
            contentAlignment = Alignment.Center,
        ) { Text("‹", style = AuraType.TabTitle) }
        Column(Modifier.weight(1f)) {
            Text(title, style = AuraType.BodyStrong, modifier = Modifier.semantics { heading() })
            if (subtitle != null) Text(subtitle, style = AuraType.SecondarySmall)
        }
    }
}

@Composable
internal fun SectionTitle(text: String, modifier: Modifier = Modifier) {
    Text(text, style = AuraType.Section, modifier = modifier.semantics { heading() })
}

/** Legenda em caixa alta (12/16, 700, +.03em). */
@Composable
internal fun CaptionTitle(text: String, modifier: Modifier = Modifier) {
    Text(text.uppercase(), style = AuraType.Caption, modifier = modifier.semantics { heading() })
}

/** Quadrado colorido com uma inicial/glifo; decorativo (o texto ao lado diz tudo). */
@Composable
internal fun GlyphTile(glyph: String, tint: Color, modifier: Modifier = Modifier, size: Dp = 48.dp) {
    Box(
        modifier
            .size(size)
            .clip(RoundedCornerShape(16.dp))
            .background(tint)
            .clearAndSetSemantics { },
        contentAlignment = Alignment.Center,
    ) { Text(glyph, style = AuraType.Section) }
}

@Composable
internal fun Chevron() {
    Text("›", style = AuraType.TabTitle.copy(color = AuraColors.Ink3), modifier = Modifier.clearAndSetSemantics { })
}
