package ai.aura.app.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import ai.aura.app.ui.theme.AuraColors
import ai.aura.app.ui.theme.AuraShapes
import ai.aura.app.ui.theme.AuraTheme
import ai.aura.app.ui.theme.AuraType

/**
 * Tela vazia que convida a agir: título curto, uma frase, UM botão principal (verbo + objeto).
 * Ex.: EmptyState("Nenhum projeto ainda", "Crie o primeiro a partir de uma ideia.", "Criar projeto", ::criar).
 */
@Composable
fun EmptyState(
    title: String,
    body: String,
    actionText: String,
    onAction: () -> Unit,
    modifier: Modifier = Modifier,
    glyph: String = "✦",
) {
    Column(
        modifier.fillMaxWidth().padding(horizontal = 32.dp, vertical = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterVertically),
    ) {
        Box(
            Modifier.size(56.dp).clip(AuraShapes.Circle).background(AuraColors.CategoryLilac)
                .clearAndSetSemantics { },
            contentAlignment = Alignment.Center,
        ) { Text(glyph, style = AuraType.TabTitle) }
        Text(title, style = AuraType.Section, textAlign = TextAlign.Center)
        Text(body, style = AuraType.Secondary, textAlign = TextAlign.Center)
        AuraButtonPrimary(actionText, onAction)
    }
}

@Preview(showBackground = true, backgroundColor = 0xFFF5F4F1)
@Composable
private fun EmptyStatePreview() {
    AuraTheme { EmptyState("Nenhum projeto ainda", "Crie o primeiro a partir de uma ideia.", "Criar projeto", {}) }
}
