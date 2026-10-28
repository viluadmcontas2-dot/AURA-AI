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
import androidx.compose.ui.platform.testTag
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
    /** Abre a tela Sessões (as conversas do Claude Code); nulo = sem o botão. */
    onSessions: (() -> Unit)? = null,
    /** Mostra/esconde os detalhes técnicos (diagnóstico); nulo = sem o botão. */
    onDetails: (() -> Unit)? = null,
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
        if (onDetails != null) AuraButtonText("Ver detalhes", onDetails)
        if (onSessions != null) AuraButtonText("Sessões", onSessions)
        if (onNew != null) AuraButtonText("Nova conversa", onNew)
    }
}

// ---- mensagens -----------------------------------------------------------------------------

@Composable
internal fun MessageBubble(message: ChatMessage, modifier: Modifier = Modifier) {
    if (message.tool) {
        // Histórico de uma sessão: o que o AURA fez entre as respostas, em uma linha discreta e sem balão.
        Text(
            message.text,
            style = AuraType.SecondarySmall.copy(color = AuraColors.Ink3),
            modifier = modifier.fillMaxWidth().padding(horizontal = 4.dp)
                .semantics { contentDescription = "AURA usou uma ferramenta: ${message.text}" },
        )
    } else if (message.fromUser) {
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

/** Ponto que pulsa enquanto o AURA trabalha. Parado quando o aparelho pede menos movimento. */
@Composable
internal fun ResponseIndicator(modifier: Modifier = Modifier) {
    val reduced = rememberReducedMotion()
    val transition = rememberInfiniteTransition(label = "resposta")
    val pulse by transition.animateFloat(
        0.35f, 1f, infiniteRepeatable(tween(700), RepeatMode.Reverse), label = "ponto",
    )
    Box(
        modifier.testTag("indicador-resposta").size(10.dp).alpha(if (reduced) 0.8f else pulse)
            .clip(AuraShapes.Circle).background(AuraColors.Ink2).clearAndSetSemantics { },
    )
}

/** Linha de estado, sem balão: "Pensando…" entre o envio e a primeira palavra (e a frase da ferramenta, depois). */
@Composable
internal fun ThinkingLine(label: String, modifier: Modifier = Modifier) {
    Row(
        modifier.fillMaxWidth().heightIn(min = 32.dp).padding(horizontal = 4.dp)
            .semantics(mergeDescendants = true) { liveRegion = LiveRegionMode.Polite },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        ResponseIndicator()
        Text(label, style = AuraType.Secondary.copy(color = AuraColors.Ink2))
    }
}

@Composable
private fun ToolLine(text: String, busy: Boolean, modifier: Modifier = Modifier) {
    Row(
        modifier.semantics(mergeDescendants = true) {
            contentDescription = "AURA está usando uma ferramenta: $text"
            if (busy) liveRegion = LiveRegionMode.Polite
        },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        if (busy) ResponseIndicator()
        Text(text, style = AuraType.SecondarySmall.copy(color = AuraColors.Ink3))
    }
}

/**
 * Resposta que chega aos poucos, estilo Claude no celular: "Pensando…" até a primeira palavra; frases de
 * ferramenta ("Lendo arquivos…") entre os trechos; o indicador só fica enquanto a última linha é uma ferramenta,
 * então some quando o texto começa a crescer. Ao terminar, nada de aviso: sobra a resposta.
 */
@Composable
internal fun LiveBubble(live: LiveReply, modifier: Modifier = Modifier, status: String = "Pensando…") {
    val parts = remember(live.text, live.tools.size) { liveParts(live) }
    if (parts.isEmpty()) {
        ThinkingLine(status, modifier)
        return
    }
    val busy = parts.last().tool
    if (live.text.isBlank()) {
        Column(modifier.fillMaxWidth().padding(horizontal = 4.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            parts.forEachIndexed { i, part -> ToolLine(part.text, busy = busy && i == parts.lastIndex) }
        }
        return
    }
    Box(modifier.fillMaxWidth(), contentAlignment = Alignment.CenterStart) {
        Column(
            Modifier.widthIn(max = 326.dp).clip(AuraShape).background(AuraColors.Surface)
                .border(1.dp, AuraColors.Line, AuraShape).padding(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            parts.forEachIndexed { i, part ->
                if (part.tool) ToolLine(part.text, busy = busy && i == parts.lastIndex)
                else Text(part.text, style = AuraType.Body.copy(lineHeight = 23.sp))
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

/** Aviso do app no meio da conversa: o que houve, em português, e o que fazer (um botão, ou as escolhas do aviso). */
@Composable
internal fun NoticeBubble(
    notice: ChatNotice,
    onAction: () -> Unit,
    modifier: Modifier = Modifier,
    onPress: (NoticeKind) -> Unit = {},
) {
    Box(modifier.fillMaxWidth(), contentAlignment = Alignment.CenterStart) {
        Column(
            Modifier.widthIn(max = 326.dp).clip(AuraShape).background(AuraColors.NeedsYou.bg)
                .padding(horizontal = 16.dp, vertical = 12.dp)
                .semantics { liveRegion = LiveRegionMode.Polite },
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(notice.message, style = AuraType.Body.copy(lineHeight = 23.sp, color = AuraColors.NeedsYou.fg, fontWeight = FontWeight.SemiBold))
            if (notice.buttons.isNotEmpty()) {
                notice.buttons.forEachIndexed { i, b ->
                    if (i == 0) AuraButtonSecondary(b.label, onClick = { onPress(b.kind) }) else AuraButtonText(b.label, onClick = { onPress(b.kind) })
                }
            } else {
                notice.action?.let { AuraButtonSecondary(actionLabel(it), onAction) }
            }
        }
    }
}

/** Aviso de demora/contato/parada da resposta em andamento (mesma cara do aviso do app, com as escolhas dele). */
@Composable
internal fun HealthNotice(health: LiveHealth, onPress: (NoticeKind) -> Unit, modifier: Modifier = Modifier) {
    val notice = when (health) {
        LiveHealth.Slow -> ChatNotice(
            "Está demorando mais que o normal.", null,
            listOf(NoticeButton("Continuar esperando", NoticeKind.KeepWaiting), NoticeButton("Parar", NoticeKind.Stop)),
        )
        LiveHealth.Lost -> ChatNotice(
            "Perdi o contato com o motor.", null,
            listOf(NoticeButton("Tentar de novo", NoticeKind.CheckProgress)),
        )
        LiveHealth.StopUnconfirmed -> ChatNotice(
            "Ainda não confirmei a parada.", null,
            listOf(NoticeButton("Verificar andamento", NoticeKind.CheckProgress)),
        )
        else -> return
    }
    NoticeBubble(notice, onAction = {}, modifier = modifier, onPress = onPress)
}

/** Mensagem da pessoa que ainda não saiu: bolha escura com a etiqueta e as duas ações. */
@Composable
internal fun QueuedBubble(
    item: QueuedMessage,
    onSendNow: () -> Unit,
    onCancel: () -> Unit,
    modifier: Modifier = Modifier,
    /** Só faz sentido "Enviar agora" quando há algo a enviar (não para uma mensagem que falhou). */
    canSendNow: Boolean = true,
) {
    val label = when {
        item.failed -> "Não enviada"
        item.waitingEngine -> "Aguardando o motor"
        else -> "Na fila"
    }
    Column(modifier.fillMaxWidth(), horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(
            item.text,
            style = AuraType.Body.copy(color = Color.White, lineHeight = 23.sp),
            modifier = Modifier.widthIn(max = 310.dp)
                .clip(UserShape)
                .background(AuraColors.Ink2)
                .padding(horizontal = 16.dp, vertical = 12.dp)
                .semantics { contentDescription = "Você, $label: ${item.text}" },
        )
        Text(label, style = AuraType.SecondarySmall.copy(color = AuraColors.Ink3), modifier = Modifier.padding(end = 6.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            if (canSendNow && !item.failed) AuraButtonText("Enviar agora", onSendNow)
            AuraButtonText("Cancelar", onCancel)
        }
    }
}

/** "A fila está parada (2 mensagens)." com as duas saídas. */
@Composable
internal fun QueuePausedBubble(count: Int, onContinue: () -> Unit, onClear: () -> Unit, modifier: Modifier = Modifier) {
    val notice = if (count == 1) "A fila está parada (1 mensagem)." else "A fila está parada ($count mensagens)."
    Box(modifier.fillMaxWidth(), contentAlignment = Alignment.CenterStart) {
        Column(
            Modifier.widthIn(max = 326.dp).clip(AuraShape).background(AuraColors.NeedsYou.bg)
                .padding(horizontal = 16.dp, vertical = 12.dp)
                .semantics { liveRegion = LiveRegionMode.Polite },
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(notice, style = AuraType.Body.copy(lineHeight = 23.sp, color = AuraColors.NeedsYou.fg, fontWeight = FontWeight.SemiBold))
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                AuraButtonSecondary("Continuar", onContinue)
                AuraButtonText("Limpar fila", onClear)
            }
        }
    }
}

/** Pílula "Mensagens novas ↓": chegou coisa embaixo enquanto a pessoa lia mais acima. */
@Composable
internal fun NewMessagesPill(onClick: () -> Unit, modifier: Modifier = Modifier) {
    Box(
        modifier.heightIn(min = 44.dp).clip(AuraShapes.Circle).background(AuraColors.Ink)
            .clickable(role = Role.Button, onClick = onClick)
            .padding(horizontal = 18.dp)
            .semantics { contentDescription = "Mensagens novas" },
        contentAlignment = Alignment.Center,
    ) { Text("Mensagens novas ↓", style = AuraType.BodyStrong.copy(color = Color.White)) }
}

/** Detalhes técnicos da conversa (atrás de "Ver detalhes"): o estado em frases e o diagnóstico copiável. */
@Composable
internal fun DetailsPanel(lines: List<String>, copied: Boolean, onCopy: () -> Unit, modifier: Modifier = Modifier) {
    Column(
        modifier.fillMaxWidth().clip(AuraShapes.Card).background(AuraColors.Surface)
            .border(1.dp, AuraColors.Line, AuraShapes.Card).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text("Detalhes", style = AuraType.Caption)
        lines.forEach { Text(it, style = AuraType.SecondarySmall) }
        AuraButtonSecondary("Copiar diagnóstico", onCopy)
        if (copied) Text("Diagnóstico copiado.", style = AuraType.SecondarySmall.copy(color = AuraColors.Ink3))
    }
}

/** Linha pequena e calma no meio da conversa (ex.: "Atualizando conversa…"). */
@Composable
internal fun QuietLine(text: String, modifier: Modifier = Modifier) {
    Text(
        text, style = AuraType.SecondarySmall.copy(color = AuraColors.Ink3),
        modifier = modifier.fillMaxWidth().padding(horizontal = 4.dp).semantics { liveRegion = LiveRegionMode.Polite },
    )
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
            ThinkingLine("Pensando…")
            NoticeBubble(ChatNotice("O motor está desligado.", ChatAction.StartEngine), {})
            QueuedBubble(QueuedMessage("r1", "Outra pergunta", false), {}, {})
            CommandsPanel(ChatCommands.all, {})
        }
    }
}
