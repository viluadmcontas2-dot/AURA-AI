package ai.aura.app.ui.screens.chat

import ai.aura.app.ui.components.AuraButtonSecondary
import ai.aura.app.ui.components.AuraButtonText
import ai.aura.app.ui.engine.ChatAction
import ai.aura.app.ui.screens.home.rememberReducedMotion
import ai.aura.app.ui.theme.AuraColors
import ai.aura.app.ui.theme.AuraShapes
import ai.aura.app.ui.theme.AuraTheme
import ai.aura.app.ui.theme.AuraType
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

private val UserShape = RoundedCornerShape(22.dp, 22.dp, 6.dp, 22.dp)
private val AuraShape = RoundedCornerShape(22.dp, 22.dp, 22.dp, 6.dp)

// ---- cabeçalho -----------------------------------------------------------------------------

@Composable
internal fun ChatHeader(
    title: String,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    /** Nulo = sem o botão (conversa em branco). */
    onNew: (() -> Unit)? = null,
) {
    Row(
        modifier.fillMaxWidth().heightIn(min = 56.dp).padding(start = 8.dp, end = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Box(
            Modifier.size(44.dp).clip(AuraShapes.Circle)
                .clickable(role = Role.Button, onClick = onBack)
                .semantics { contentDescription = "Voltar para o início" },
            contentAlignment = Alignment.Center,
        ) { Text("←", style = AuraType.Section.copy(fontSize = 22.sp)) }
        Text(
            title, style = AuraType.Section.copy(fontSize = 17.sp, lineHeight = 22.sp),
            maxLines = 1, overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f).semantics { heading() },
        )
        if (onNew != null) AuraButtonText("Nova conversa", onNew)
    }
}

// ---- mensagens -----------------------------------------------------------------------------

@Composable
internal fun MessageBubble(message: ChatMessage, modifier: Modifier = Modifier) {
    if (message.fromUser) {
        Box(modifier.fillMaxWidth(), contentAlignment = Alignment.CenterEnd) {
            Text(
                message.text,
                style = AuraType.Body.copy(color = Color.White, lineHeight = 23.sp),
                modifier = Modifier.widthIn(max = 310.dp)
                    .clip(UserShape)
                    .background(AuraColors.Ink)
                    .padding(horizontal = 16.dp, vertical = 12.dp)
                    .semantics { contentDescription = "Você: ${message.text}" },
            )
        }
    } else {
        Box(modifier.fillMaxWidth(), contentAlignment = Alignment.CenterStart) {
            Text(
                message.text,
                style = AuraType.Body.copy(lineHeight = 23.sp),
                modifier = Modifier.widthIn(max = 326.dp)
                    .clip(AuraShape)
                    .background(AuraColors.Surface)
                    .border(1.dp, AuraColors.Line, AuraShape)
                    .padding(horizontal = 16.dp, vertical = 12.dp)
                    .semantics { contentDescription = "AURA: ${message.text}" },
            )
        }
    }
}

/** Três pontinhos: o AURA está escrevendo. Parados quando o aparelho pede menos movimento. */
@Composable
internal fun TypingBubble(modifier: Modifier = Modifier) {
    val reduced = rememberReducedMotion()
    val transition = rememberInfiniteTransition(label = "typing")
    val pulse by transition.animateFloat(
        0.35f, 1f, infiniteRepeatable(tween(700), RepeatMode.Reverse), label = "dots",
    )
    Box(modifier.fillMaxWidth(), contentAlignment = Alignment.CenterStart) {
        Row(
            Modifier.clip(AuraShape).background(AuraColors.Surface).border(1.dp, AuraColors.Line, AuraShape)
                .padding(horizontal = 18.dp, vertical = 16.dp)
                .semantics { contentDescription = "AURA está escrevendo"; liveRegion = LiveRegionMode.Polite },
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            repeat(3) { i ->
                val a = if (reduced) 0.7f else if (i == 1) 1.35f - pulse else pulse
                Box(Modifier.size(8.dp).alpha(a.coerceIn(0.2f, 1f)).clip(AuraShapes.Circle).background(AuraColors.Ink2))
            }
        }
    }
}

/** Resposta que chega aos poucos: o texto cresce e as linhas de ferramenta ficam entre os trechos. */
@Composable
internal fun LiveBubble(live: LiveReply, modifier: Modifier = Modifier) {
    val parts = remember(live.text, live.tools.size) { liveParts(live) }
    Box(modifier.fillMaxWidth(), contentAlignment = Alignment.CenterStart) {
        Column(
            Modifier.widthIn(max = 326.dp).clip(AuraShape).background(AuraColors.Surface)
                .border(1.dp, AuraColors.Line, AuraShape).padding(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            parts.forEach { part ->
                if (part.tool) {
                    Text(
                        part.text,
                        style = AuraType.SecondarySmall.copy(color = AuraColors.Ink3),
                        modifier = Modifier.semantics { contentDescription = "AURA está usando uma ferramenta: ${part.text}" },
                    )
                } else {
                    Text(part.text, style = AuraType.Body.copy(lineHeight = 23.sp))
                }
            }
        }
    }
}

internal class LivePart(val text: String, val tool: Boolean)

/** Corta o texto nas posições das ferramentas. Função pura, sem tela. */
internal fun liveParts(live: LiveReply): List<LivePart> {
    val parts = mutableListOf<LivePart>()
    var from = 0
    for (mark in live.tools) {
        val at = mark.offset.coerceIn(from, live.text.length)
        val chunk = live.text.substring(from, at).trim()
        if (chunk.isNotEmpty()) parts += LivePart(chunk, false)
        val line = mark.summary.trim().trimEnd('.', '…')
        if (line.isNotEmpty()) parts += LivePart("$line…", true)
        from = at
    }
    val rest = live.text.substring(from).trim()
    if (rest.isNotEmpty()) parts += LivePart(rest, false)
    return parts
}

/** Ações úteis sob a última resposta: copiar e pedir para continuar. */
@Composable
internal fun AnswerActions(onCopy: () -> Unit, onContinue: () -> Unit, modifier: Modifier = Modifier) {
    Row(modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        AuraButtonText("Copiar", onCopy)
        AuraButtonText("Continuar", onContinue)
    }
}

/** Aviso do app no meio da conversa: o que houve, em português, e UM botão com o que fazer. */
@Composable
internal fun NoticeBubble(notice: ChatNotice, onAction: () -> Unit, modifier: Modifier = Modifier) {
    Box(modifier.fillMaxWidth(), contentAlignment = Alignment.CenterStart) {
        Column(
            Modifier.widthIn(max = 326.dp).clip(AuraShape).background(AuraColors.NeedsYou.bg)
                .padding(horizontal = 16.dp, vertical = 12.dp)
                .semantics { liveRegion = LiveRegionMode.Polite },
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(notice.message, style = AuraType.Body.copy(lineHeight = 23.sp, color = AuraColors.NeedsYou.fg, fontWeight = FontWeight.SemiBold))
            notice.action?.let { AuraButtonSecondary(actionLabel(it), onAction) }
        }
    }
}

internal fun actionLabel(action: ChatAction): String = when (action) {
    ChatAction.Retry -> "Tentar de novo"
    ChatAction.OpenConnections -> "Abrir Conexões"
    ChatAction.StartEngine -> "Iniciar o motor"
}

// ---- comandos ------------------------------------------------------------------------------

@Composable
internal fun CommandsPanel(
    commands: List<ChatCommand>,
    onPick: (ChatCommand) -> Unit,
    modifier: Modifier = Modifier,
) {
    val tints = listOf(AuraColors.CategoryEngineering, AuraColors.CategoryClarity, AuraColors.CategoryAndroid, AuraColors.CategoryApricot, AuraColors.CategoryLilac)
    Column(
        modifier.fillMaxWidth().padding(horizontal = 20.dp)
            .clip(AuraShapes.Card).background(AuraColors.Surface).border(1.dp, AuraColors.Line, AuraShapes.Card)
            .semantics { contentDescription = "Comandos" },
    ) {
        Row(
            Modifier.fillMaxWidth().height(40.dp).padding(horizontal = 16.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text("COMANDOS", style = AuraType.Caption)
            Text("Toque para escolher", style = AuraType.Secondary.copy(fontSize = 12.sp, lineHeight = 16.sp))
        }
        Box(Modifier.fillMaxWidth().height(1.dp).background(AuraColors.Line))
        commands.forEachIndexed { i, c ->
            val source = remember { MutableInteractionSource() }
            Row(
                Modifier.fillMaxWidth().heightIn(min = 56.dp)
                    .clickable(interactionSource = source, indication = null, role = Role.Button) { onPick(c) }
                    .padding(horizontal = 16.dp, vertical = 8.dp)
                    .semantics(mergeDescendants = true) { contentDescription = "${c.name}. ${c.description}" },
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Box(Modifier.size(36.dp).clip(RoundedCornerShape(12.dp)).background(tints[i % tints.size]).clearAndSetSemantics { }, Alignment.Center) {
                    Text("/", style = AuraType.BodyStrong)
                }
                Column(Modifier.weight(1f)) {
                    Text(c.name, style = AuraType.BodyStrong.copy(lineHeight = 20.sp))
                    Text(c.description, style = AuraType.SecondarySmall.copy(lineHeight = 18.sp))
                }
            }
        }
    }
}

// ---- previews ------------------------------------------------------------------------------

@Preview(showBackground = true, backgroundColor = 0xFFF5F4F1)
@Composable
private fun ChatPartsPreview() {
    AuraTheme {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            MessageBubble(ChatMessage(0, true, "Ola"))
            LiveBubble(LiveReply("Olá! Vou olhar o projeto. Achei três telas.", listOf(LiveReply.ToolMark(21, "Lendo arquivos"))))
            TypingBubble()
            NoticeBubble(ChatNotice("O motor está desligado.", ChatAction.StartEngine), {})
            CommandsPanel(ChatCommands.all, {})
        }
    }
}
