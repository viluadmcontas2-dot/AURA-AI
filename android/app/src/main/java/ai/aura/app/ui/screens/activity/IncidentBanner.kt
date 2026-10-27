package ai.aura.app.ui.screens.activity

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import ai.aura.app.ui.components.AuraButtonSecondary
import ai.aura.app.ui.theme.AuraColors
import ai.aura.app.ui.theme.AuraShapes
import ai.aura.app.ui.theme.AuraTheme
import ai.aura.app.ui.theme.AuraType
import ai.aura.app.ui.theme.StatusColors

/** Níveis de Intercorrencias.dc.html: Informação, Precisa de você, Falhou. */
enum class IncidentLevel(internal val colors: StatusColors, internal val border: Color, internal val tag: String) {
    Info(AuraColors.Info, Color(0xFFC9D8FF), "Informação"),
    NeedsYou(AuraColors.NeedsYou, Color(0xFFF0D9A8), "Precisa de você"),
    Failed(AuraColors.Risk, Color(0xFFF2BDC2), "Falhou"),
}

/**
 * AuraBanner: diz o que houve (1 frase), o que está salvo/o que fazer ([body]) e oferece UMA ação.
 * Ponto + texto, nunca só cor. Anunciado por leitores de tela (liveRegion).
 */
@Composable
fun IncidentBanner(
    level: IncidentLevel,
    title: String,
    body: String,
    modifier: Modifier = Modifier,
    actionText: String? = null,
    onAction: (() -> Unit)? = null,
) {
    Column(
        modifier
            .fillMaxWidth()
            .clip(AuraShapes.Card)
            .background(level.colors.bg)
            .border(1.dp, level.border, AuraShapes.Card)
            .padding(16.dp)
            .semantics(mergeDescendants = true) { liveRegion = LiveRegionMode.Polite },
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Box(Modifier.size(8.dp).clip(AuraShapes.Circle).background(level.colors.dot))
            Text(level.tag.uppercase(), style = AuraType.Caption.copy(color = level.colors.fg))
        }
        Text(title, style = AuraType.BodyStrong)
        Text(body, style = AuraType.Secondary)
        if (actionText != null && onAction != null) {
            AuraButtonSecondary(actionText, onAction)
        }
    }
}

@Preview(showBackground = true, backgroundColor = 0xFFF5F4F1)
@Composable
private fun IncidentBannerPreview() {
    AuraTheme {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            IncidentBanner(IncidentLevel.Info, "Sem internet", "Continuo o que dá para fazer aqui. O resto fica na fila.", actionText = "Ver a fila", onAction = {})
            IncidentBanner(IncidentLevel.NeedsYou, "O Claude atingiu o limite desta janela", "Volta às 18:40.", actionText = "Usar outro cérebro", onAction = {})
            IncidentBanner(IncidentLevel.Failed, "O GitHub recusou o acesso", "Sua tarefa está salva. Entre de novo para enviar.", actionText = "Entrar de novo", onAction = {})
        }
    }
}
