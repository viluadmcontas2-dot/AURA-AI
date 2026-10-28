package ai.aura.app.ui.screens.chat

import ai.aura.app.ui.components.AuraButtonSecondary
import ai.aura.app.ui.components.AuraButtonText
import ai.aura.app.ui.engine.ChatAction
import ai.aura.app.ui.engine.EngineGateway
import ai.aura.app.ui.engine.ImproveResult
import ai.aura.app.ui.theme.AuraColors
import ai.aura.app.ui.theme.AuraShapes
import ai.aura.app.ui.theme.AuraType
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/** "Melhorar pedido" só aparece quando o texto passa disto: pedido curto não precisa. */
internal const val IMPROVE_MIN_CHARS = 160

internal fun canImprove(text: String): Boolean = text.trim().length > IMPROVE_MIN_CHARS

/**
 * Melhorar pedido (POST /v1/prompt/improve): troca o texto do campo pelo melhorado e guarda o original para
 * Desfazer. Nunca envia sozinho. Se a pessoa mexeu no texto enquanto o motor trabalhava, o resultado é descartado.
 */
@Stable
internal class PromptImprover(
    private val gateway: EngineGateway,
    private val scope: CoroutineScope,
) {
    var working by mutableStateOf(false); private set
    var failure by mutableStateOf<ImproveResult.Failed?>(null); private set

    /** Texto de antes e de depois; Desfazer vale enquanto o campo ainda tem o texto melhorado. */
    var before by mutableStateOf<String?>(null); private set
    var after by mutableStateOf<String?>(null); private set

    fun improve(text: String, current: () -> String, apply: (String) -> Unit) {
        if (working) return
        failure = null
        before = null
        after = null
        working = true
        scope.launch {
            val result = try {
                gateway.improvePrompt(text.trim())
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                ImproveResult.Failed("failed", "Não consegui melhorar o pedido agora.", ChatAction.Retry)
            }
            working = false
            when (result) {
                is ImproveResult.Failed -> failure = result
                is ImproveResult.Improved ->
                    if (current().trim() == text.trim()) {
                        before = text
                        after = result.text
                        apply(result.text)
                    }
            }
        }
    }

    fun undo(apply: (String) -> Unit) {
        before?.let(apply)
        before = null
        after = null
    }

    fun clearFailure() {
        failure = null
    }
}

/**
 * Faixa acima do campo de mensagem: "Melhorar pedido" (com mais de 160 caracteres), "Melhorando…" enquanto
 * espera, "Desfazer" depois e, se falhar, o aviso com UMA ação (Tentar de novo ou Abrir Conexões).
 * Vale para qualquer texto do campo, inclusive o que veio da voz.
 */
@Composable
internal fun ImprovePromptBar(
    gateway: EngineGateway,
    text: String,
    onTextChange: (String) -> Unit,
    onOpenConnections: (() -> Unit)?,
    modifier: Modifier = Modifier,
) {
    val scope = rememberCoroutineScope { Dispatchers.Main.immediate }
    val improver = remember(gateway) { PromptImprover(gateway, scope) }
    val latest by rememberUpdatedState(text)
    val long = canImprove(text)
    val undoable = improver.after != null && text == improver.after
    LaunchedEffect(long) { if (!long) improver.clearFailure() }
    val failure = improver.failure.takeIf { long }
    if (!improver.working && !undoable && failure == null && !long) return

    Column(modifier.fillMaxWidth().padding(horizontal = 20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (failure != null) {
            ImproveNotice(
                failure,
                onAction = {
                    when (failure.action) {
                        ChatAction.OpenConnections -> onOpenConnections?.invoke()
                        else -> improver.improve(text, { latest }, onTextChange)
                    }
                },
                canOpenConnections = onOpenConnections != null,
            )
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            when {
                improver.working -> AuraButtonSecondary("Melhorando…", {}, enabled = false)
                undoable -> {
                    AuraButtonText("Desfazer", { improver.undo(onTextChange) })
                    Text(
                        "Pedido melhorado. Confira antes de enviar.", style = AuraType.SecondarySmall,
                        modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                    )
                }
                long && (failure == null || failure.action == null) -> AuraButtonSecondary("Melhorar pedido", { improver.improve(text, { latest }, onTextChange) })
            }
        }
    }
}

@Composable
private fun ImproveNotice(failure: ImproveResult.Failed, onAction: () -> Unit, canOpenConnections: Boolean) {
    Column(
        Modifier.fillMaxWidth().clip(AuraShapes.Card).background(AuraColors.NeedsYou.bg).padding(horizontal = 16.dp, vertical = 12.dp)
            .semantics { liveRegion = LiveRegionMode.Polite },
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            failure.message,
            style = AuraType.SecondarySmall.copy(color = AuraColors.NeedsYou.fg, fontWeight = FontWeight.SemiBold),
        )
        when (failure.action) {
            ChatAction.Retry -> AuraButtonSecondary("Tentar de novo", onAction)
            ChatAction.OpenConnections -> if (canOpenConnections) AuraButtonSecondary("Abrir Conexões", onAction)
            else -> Unit
        }
    }
}
