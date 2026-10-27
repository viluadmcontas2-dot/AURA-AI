package ai.aura.app.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import ai.aura.app.ui.theme.AuraColors
import ai.aura.app.ui.theme.AuraShapes
import ai.aura.app.ui.theme.AuraTheme
import ai.aura.app.ui.theme.AuraType

/** Resultado sempre em 4 blocos (regra 3). Textos em português, sem logs. */
data class ResultBlocksData(
    val done: String,
    val proven: String,
    val missing: String,
    val nextStep: String,
)

@Composable
private fun ResultRow(
    label: String,
    text: String,
    glyph: String,
    badgeBg: Color,
    badgeFg: Color,
    modifier: Modifier = Modifier,
    trailing: @Composable (() -> Unit)? = null,
) {
    Row(modifier.fillMaxWidth().padding(vertical = 12.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Box(
            Modifier.size(28.dp).clip(AuraShapes.Circle).background(badgeBg)
                .clearAndSetSemantics { },
            contentAlignment = Alignment.Center,
        ) { Text(glyph, style = AuraType.Chip.copy(color = badgeFg, fontWeight = FontWeight.Bold)) }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(label, style = AuraType.Caption, modifier = Modifier.semantics { heading() })
            Text(text, style = AuraType.Secondary.copy(color = AuraColors.Ink))
            trailing?.invoke()
        }
    }
}

/**
 * FEITO / COMPROVADO / FALTA / PRÓXIMO PASSO. Verde = prova, âmbar = falta, tinta = próximo passo.
 * [onViewProofs] mostra o link "Ver provas" (alvo 44 dp) no bloco Comprovado.
 */
@Composable
fun ResultBlocks(
    data: ResultBlocksData,
    modifier: Modifier = Modifier,
    onViewProofs: (() -> Unit)? = null,
) {
    Column(
        modifier
            .fillMaxWidth()
            .clip(AuraShapes.Card)
            .background(AuraColors.Surface)
            .border(1.dp, AuraColors.Line, AuraShapes.Card)
            .padding(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 4.dp),
    ) {
        ResultRow("FEITO", data.done, "✓", AuraColors.Ready.bg, AuraColors.Ready.fg)
        Divider()
        ResultRow("COMPROVADO", data.proven, "i", AuraColors.Info.bg, AuraColors.Info.fg) {
            if (onViewProofs != null) AuraButtonText("Ver provas", onViewProofs)
        }
        Divider()
        ResultRow("FALTA", data.missing, "!", AuraColors.NeedsYou.bg, AuraColors.NeedsYou.fg)
        Divider()
        ResultRow("PRÓXIMO PASSO", data.nextStep, "→", AuraColors.Ink, Color.White)
    }
}

@Composable
private fun Divider() {
    Box(Modifier.fillMaxWidth().height(1.dp).background(AuraColors.Line))
}

@Preview(showBackground = true, backgroundColor = 0xFFF5F4F1)
@Composable
private fun ResultBlocksPreview() {
    AuraTheme {
        ResultBlocks(
            ResultBlocksData(
                done = "Layout corrigido e APK 0.3.2 gerado.",
                proven = "12 de 12 testes · build verde #48",
                missing = "Testar com biometria ligada.",
                nextStep = "Abrir o pull request com a correção.",
            ),
            Modifier.padding(16.dp),
            onViewProofs = {},
        )
    }
}
