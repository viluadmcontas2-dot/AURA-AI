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
import ai.aura.app.ui.components.AuraButtonPrimary
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

/**
 * As 11 intercorrências de Intercorrencias.dc.html, em 3 níveis. [actions]: o primeiro rótulo é o botão principal
 * (um só), os outros são secundários. [detail] é a linha extra do design (fila, endereço, opções, passos).
 * shortcut: sem a barra de passos, as opções com seleção e a lista feito/falta do design; entram como texto em [detail].
 */
enum class IncidentCase(
    val level: IncidentLevel,
    val title: String,
    val body: String?,
    val actions: List<String>,
    val detail: String? = null,
) {
    NoInternet(IncidentLevel.Info, "Sem internet", "Continuo o que dá para fazer aqui. O resto fica na fila.", listOf("Ver a fila"), "2 na fila"),
    ModelLimit(IncidentLevel.NeedsYou, "O Claude atingiu o limite desta janela.", "Volta às 18:40.", listOf("Usar outro cérebro", "Esperar")),
    CliMissing(
        IncidentLevel.NeedsYou, "O Gemini CLI não está instalado.", "Posso instalar no Termux. Leva cerca de 2 min.",
        listOf("Instalar agora · 2 min", "Usar o Claude"),
    ),
    AdbLost(IncidentLevel.Failed, "Perdi contato com a Multimídia do carro.", "Seu trabalho está salvo.", listOf("Reconectar")),
    ProcessKilled(
        IncidentLevel.NeedsYou, "O Android pausou o AURA em segundo plano.", "Retomei do passo 3.",
        listOf("Continuar", "Evitar isso"), "Passos 1 e 2 feitos, passo 3 em andamento",
    ),
    FileChanged(
        IncidentLevel.NeedsYou, "Você mudou login_screen.kt enquanto eu trabalhava.", "O que você prefere guardar?",
        listOf("Juntar os dois", "Manter o meu", "Usar o do AURA"),
    ),
    GithubDenied(
        IncidentLevel.Failed, "O GitHub recusou o acesso.", "Sua tarefa está salva. Entre de novo para enviar o pull request.",
        listOf("Entrar de novo"),
    ),
    UnknownLink(
        IncidentLevel.Failed, "Link externo: confira o endereço", "Parece com github.com, mas não é. Não digite sua senha.",
        listOf("Cancelar", "Abrir mesmo assim"), "https://gihub-login.com/entrar",
    ),
    Ambiguity(
        IncidentLevel.Info, "Qual tela? Achei duas parecidas.", null, listOf("Usar a sugerida"),
        "login_screen.kt · Tela de login antiga (sugerida)\nLoginV2Screen.kt · Tela de entrada nova",
    ),
    Partial(
        IncidentLevel.NeedsYou, "Terminei 2 de 4 passos.", null, listOf("Continuar de onde parou"),
        "Feito: Ler o código da tela; Corrigir o layout.\n" +
            "Falta: Gerar o APK (o build parou: faltou espaço no celular); Instalar na multimídia (espera o APK ficar pronto).",
    ),
    NoSpace(IncidentLevel.NeedsYou, "Faltam 300 MB.", "Posso limpar builds antigos?", listOf("Ver o que será apagado")),
}

/** Cartão branco de uma intercorrência: nível + título + o que fazer, e os botões. [onAction] recebe o rótulo tocado. */
@Composable
fun IncidentCard(incident: IncidentCase, onAction: (String) -> Unit, modifier: Modifier = Modifier) {
    val colors = incident.level.colors
    Column(
        modifier.fillMaxWidth().clip(AuraShapes.Card).background(AuraColors.Surface)
            .border(1.dp, AuraColors.Line, AuraShapes.Card).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        // Só o texto é mesclado e anunciado; os botões ficam fora para continuarem clicáveis um a um.
        Column(
            Modifier.semantics(mergeDescendants = true) { liveRegion = LiveRegionMode.Polite },
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Box(Modifier.size(8.dp).clip(AuraShapes.Circle).background(colors.dot))
                Text(incident.level.tag.uppercase(), style = AuraType.Caption.copy(color = colors.fg))
            }
            Text(incident.title, style = AuraType.BodyStrong)
            incident.body?.let { Text(it, style = AuraType.Secondary) }
            incident.detail?.let { Text(it, style = AuraType.SecondarySmall) }
        }
        incident.actions.forEachIndexed { i, label ->
            if (i == 0) AuraButtonPrimary(label, { onAction(label) }, Modifier.fillMaxWidth())
            else AuraButtonSecondary(label, { onAction(label) }, Modifier.fillMaxWidth())
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
