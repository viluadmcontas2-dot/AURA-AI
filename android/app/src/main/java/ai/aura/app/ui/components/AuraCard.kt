package ai.aura.app.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import ai.aura.app.ui.theme.AuraColors
import ai.aura.app.ui.theme.AuraShapes
import ai.aura.app.ui.theme.AuraSpacing
import ai.aura.app.ui.theme.AuraTheme
import ai.aura.app.ui.theme.AuraType

/**
 * Cartão: raio 24, borda #E6E3DC. Com [onClick] vira alvo de toque (mín. 44 dp).
 * [tint] != Surface cria o cartão colorido de categoria (sem borda), como em Main.
 */
@Composable
fun AuraCard(
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    tint: Color = AuraColors.Surface,
    contentDescription: String? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    val source = remember { MutableInteractionSource() }
    var m = modifier
        .heightIn(min = AuraSpacing.TouchTarget)
        .clip(AuraShapes.Card)
        .background(tint)
    if (tint == AuraColors.Surface) m = m.border(1.dp, AuraColors.Line, AuraShapes.Card)
    if (onClick != null) {
        m = m.auraFocusRing(source, AuraShapes.Card)
            .clickable(interactionSource = source, indication = null, role = Role.Button, onClick = onClick)
    }
    if (contentDescription != null) m = m.semantics { this.contentDescription = contentDescription }
    Column(m.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp), content = content)
}

@Preview(showBackground = true, backgroundColor = 0xFFF5F4F1)
@Composable
private fun CardPreview() {
    AuraTheme {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            AuraCard(Modifier.fillMaxWidth()) {
                Text("Minhas Finanças", style = AuraType.BodyStrong)
                Text("Última alteração hoje", style = AuraType.Secondary)
            }
            AuraCard(Modifier.fillMaxWidth(), onClick = {}, tint = AuraColors.CategoryEngineering) {
                Text("Criar aplicativo", style = AuraType.BodyStrong)
                Text("Da ideia ao APK", style = AuraType.SecondarySmall)
            }
        }
    }
}
