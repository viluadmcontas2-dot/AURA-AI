package ai.aura.app.ui.screens.voice

import ai.aura.app.ui.screens.home.AuraOrb
import ai.aura.app.ui.screens.home.OrbState
import ai.aura.app.ui.theme.AuraColors
import ai.aura.app.ui.theme.AuraShapes
import ai.aura.app.ui.theme.AuraType
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Voz em tela cheia (Voz-1-Ouvindo e Voz-2-Interrompida). Só abre por toque e só ouve com a tela aberta.
 * Sem microfone liberado ou sem reconhecedor: uma mensagem e UMA ação. Interrompeu: guarda o texto e
 * volta a ouvir sozinha em 3 s (se o ajuste permitir). No modo Conversa, 1,5 s de silêncio envia.
 */
@Composable
fun VoiceScreen(
    recognizer: SpeechRecognizerPort,
    micGranted: Boolean,
    settings: VoiceSettings,
    onNeedMicrophone: () -> Unit,
    onKeyboard: (String) -> Unit,
    onSend: (String) -> Unit,
    onCancel: () -> Unit,
    onMinimize: () -> Unit,
    modifier: Modifier = Modifier,
    initialText: String = "",
    onHeard: (String) -> Unit = {},
) {
    val scope = rememberCoroutineScope()
    val latestSettings by rememberUpdatedState(settings)
    val latestSend by rememberUpdatedState(onSend)
    val session = remember(recognizer, micGranted) {
        VoiceSession(recognizer, scope, { latestSettings }, { text -> latestSend(text) }, initialText)
    }
    DisposableEffect(session) {
        if (micGranted && recognizer.available) session.start()
        onDispose { session.close() }
    }
    LaunchedEffect(session.heard) { onHeard(session.heard) }

    Column(
        modifier.fillMaxSize().background(AuraColors.DarkVoice).padding(horizontal = 20.dp, vertical = 12.dp),
    ) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            IconTap("Minimizar voz", onMinimize) { DownIcon(AuraColors.DarkText) }
        }
        when {
            !recognizer.available -> Notice(
                "Este aparelho não entende voz",
                "Escreva o pedido: o AURA faz do mesmo jeito.",
                "Escrever o pedido",
            ) { onKeyboard("") }
            !micGranted -> Notice(
                "Preciso do microfone",
                "Só ouço enquanto esta tela está aberta, e só depois do seu toque.",
                "Permitir microfone",
                onNeedMicrophone,
            )
            session.phase == VoicePhase.Interrupted -> InterruptedBody(session, onKeyboard)
            else -> ListeningBody(session, onKeyboard, onCancel)
        }
    }
}

@Composable
private fun ColumnScope.Notice(title: String, body: String, action: String, onAction: () -> Unit) {
    Column(
        Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        AuraOrb(Modifier.size(96.dp))
        Spacer(Modifier.height(24.dp))
        Text(title, style = VoiceTitle, textAlign = TextAlign.Center)
        Spacer(Modifier.height(8.dp))
        Text(body, style = VoiceBody.copy(color = AuraColors.DarkText2), textAlign = TextAlign.Center)
    }
    VoiceButton(action, onAction, Modifier.fillMaxWidth().padding(top = 16.dp), primary = true)
}

@Composable
private fun ColumnScope.ListeningBody(session: VoiceSession, onKeyboard: (String) -> Unit, onCancel: () -> Unit) {
    Column(
        Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        AuraOrb(Modifier.size(160.dp), glow = true, state = OrbState.Ouvindo)
        Spacer(Modifier.height(24.dp))
        Text("Ouvindo", style = VoiceTitle)
        Spacer(Modifier.height(16.dp))
        if (session.heard.isBlank()) {
            Text("Pode falar. Eu escrevo aqui o que ouvir.", style = VoiceBody.copy(color = AuraColors.DarkText2), textAlign = TextAlign.Center)
        } else {
            Text(session.heard, style = VoiceBody.copy(fontSize = 20.sp, lineHeight = 28.sp), textAlign = TextAlign.Center)
        }
    }
    Row(Modifier.fillMaxWidth().padding(top = 16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        VoiceButton("Teclado", { session.pause(); onKeyboard(session.heard) }, Modifier.weight(1f))
        VoiceButton("Enviar", { session.sendNow() }, Modifier.weight(1f), enabled = session.heard.isNotBlank(), primary = true)
        VoiceButton("Cancelar", { session.close(); onCancel() }, Modifier.weight(1f))
    }
}

@Composable
private fun ColumnScope.InterruptedBody(session: VoiceSession, onKeyboard: (String) -> Unit) {
    Column(
        Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        AuraOrb(Modifier.size(96.dp), state = OrbState.Pensando)
        Spacer(Modifier.height(20.dp))
        Text(if (session.resumeIn > 0) "Voltando a ouvir…" else "Voz parada", style = VoiceTitle)
        if (session.reason.isNotBlank()) {
            Spacer(Modifier.height(4.dp))
            Text(session.reason, style = VoiceBody.copy(color = AuraColors.DarkText2), textAlign = TextAlign.Center)
        }
        if (session.resumeIn > 0) {
            Spacer(Modifier.height(4.dp))
            Text("Volto sozinho em ${session.resumeIn} s", style = VoiceBody.copy(color = AuraColors.DarkFocus))
        }
        Spacer(Modifier.height(24.dp))
        Column(
            Modifier.fillMaxWidth().clip(AuraShapes.Card).background(AuraColors.DarkPanel)
                .border(1.dp, AuraColors.DarkLine, AuraShapes.Card).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text("O que você já disse", style = AuraType.Caption.copy(color = AuraColors.DarkText2))
            Text(session.heard.ifBlank { "Nada ainda." }, style = VoiceBody)
            Text("Guardado. Nada se perde.", style = AuraType.SecondarySmall.copy(color = AuraColors.DarkText2))
        }
    }
    Column(Modifier.fillMaxWidth().padding(top = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        VoiceButton("Voltar a ouvir agora", { session.resumeNow() }, Modifier.fillMaxWidth(), primary = true)
        VoiceButton("Enviar assim mesmo", { session.sendNow() }, Modifier.fillMaxWidth(), enabled = session.heard.isNotBlank())
        VoiceButton("Editar texto", { session.pause(); onKeyboard(session.heard) }, Modifier.fillMaxWidth())
        Text(
            "Só peço um toque se o Android exigir.",
            style = AuraType.SecondarySmall.copy(color = AuraColors.DarkText2), textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
        )
    }
}

private val VoiceTitle = AuraType.Display.copy(color = AuraColors.DarkText, fontSize = 30.sp, lineHeight = 34.sp)
private val VoiceBody = AuraType.Body.copy(color = AuraColors.DarkText)

/** Botão do tema escuro da voz: pílula de 52 dp, anel de foco claro. */
@Composable
private fun VoiceButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    primary: Boolean = false,
) {
    val source = remember { MutableInteractionSource() }
    val focused by source.collectIsFocusedAsState()
    val container = if (primary && enabled) AuraColors.DarkText else AuraColors.DarkPanel
    val content = when {
        !enabled -> AuraColors.DarkText2
        primary -> AuraColors.DarkVoice
        else -> AuraColors.DarkText
    }
    Box(
        modifier.heightIn(min = 52.dp).clip(AuraShapes.Pill).background(container)
            .border(if (focused) 2.dp else 1.dp, if (focused) AuraColors.DarkFocus else AuraColors.DarkLine, AuraShapes.Pill)
            .clickable(interactionSource = source, indication = null, enabled = enabled, role = Role.Button, onClick = onClick)
            .padding(horizontal = 12.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(text, style = AuraType.Button.copy(color = content), textAlign = TextAlign.Center)
    }
}

@Preview(showBackground = true, backgroundColor = 0xFF0C0B12, heightDp = 700)
@Composable
private fun VoiceNoticePreview() {
    ai.aura.app.ui.theme.AuraTheme {
        VoiceScreen(
            recognizer = object : SpeechRecognizerPort {
                override val available = true
                override fun start(listener: SpeechRecognizerPort.Listener) = Unit
                override fun stop() = Unit
            },
            micGranted = false, settings = VoiceSettings(),
            onNeedMicrophone = {}, onKeyboard = {}, onSend = {}, onCancel = {}, onMinimize = {},
        )
    }
}
