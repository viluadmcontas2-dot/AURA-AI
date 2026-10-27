package ai.aura.app.ui.screens.settings

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.unit.dp
import ai.aura.app.ui.components.AuraButtonText
import ai.aura.app.ui.components.AuraCard
import ai.aura.app.ui.theme.AuraColors
import ai.aura.app.ui.theme.AuraTheme
import ai.aura.app.ui.theme.AuraType

/**
 * Cartão para o chat: "Mudei: <antes> → <depois>" + a frase do dono + Desfazer.
 * [quemDisse] é a frase original dita ou digitada pelo dono (policy.changed guarda a mesma).
 * [onUndo] deve gravar a versão anterior no motor (a UI do chat decide como).
 */
@Composable
fun PolicyChangeCard(
    antes: String,
    depois: String,
    quemDisse: String,
    onUndo: () -> Unit,
    modifier: Modifier = Modifier,
) {
    AuraCard(modifier.fillMaxWidth(), tint = AuraColors.Surface) {
        Text(
            buildAnnotatedString {
                withStyle(SpanStyle(fontWeight = FontWeight.Bold)) { append("Mudei: ") }
                append(antes)
                append(" → ")
                withStyle(SpanStyle(fontWeight = FontWeight.Bold)) { append(depois) }
            },
            style = AuraType.Body,
        )
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(
                "Você disse: “$quemDisse”",
                style = AuraType.SecondarySmall,
                modifier = Modifier.weight(1f),
            )
            AuraButtonText("Desfazer", onUndo, contentDescription = "Desfazer a mudança de política")
        }
    }
}

@Preview(showBackground = true, backgroundColor = 0xFFF5F4F1)
@Composable
private fun PolicyChangeCardPreview() {
    AuraTheme {
        Column(Modifier.padding(16.dp)) {
            PolicyChangeCard("sem limite de gasto por dia", "limite de US$ 20 por dia", "pode gastar até 20 por dia", {})
        }
    }
}
