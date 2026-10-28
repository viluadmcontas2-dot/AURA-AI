package ai.aura.app.ui.screens.connections

import ai.aura.app.ui.components.AuraButtonSecondary
import ai.aura.app.ui.components.AuraCard
import ai.aura.app.ui.components.StatusChip
import ai.aura.app.ui.components.StatusKind
import ai.aura.app.ui.engine.EngineGateway
import ai.aura.app.ui.engine.EngineResult
import ai.aura.app.ui.screens.settings.SettingsNotice
import ai.aura.app.ui.theme.AuraColors
import ai.aura.app.ui.theme.AuraType
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * Estado da chave do Gemini (PUT/GET/DELETE /v1/settings/gemini_key). A chave só passa do campo para o motor:
 * não é guardada aqui, não vai a log e não aparece em aviso. Quem pergunta se existe recebe só sim ou não.
 */
@Stable
internal class GeminiKeyState(
    private val gateway: EngineGateway,
    private val scope: CoroutineScope,
) {
    /** null = o motor não respondeu (não dá para saber se há chave). */
    var configured by mutableStateOf<Boolean?>(null); private set
    var busy by mutableStateOf(false); private set
    var notice by mutableStateOf<SettingsNotice?>(null); private set

    suspend fun refresh() {
        configured = try {
            (gateway.geminiKeyConfigured() as? EngineResult.Ok)?.value
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            null
        }
    }

    fun save(key: String) {
        val value = key.trim()
        if (value.isEmpty() || busy) return
        busy = true
        notice = null
        scope.launch {
            val r = try {
                gateway.saveGeminiKey(value)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                EngineResult.Unavailable("sem motor")
            }
            busy = false
            when (r) {
                is EngineResult.Ok -> {
                    configured = true
                    notice = SettingsNotice(true, "Chave salva. Agora o botão Melhorar pedido funciona.")
                }
                is EngineResult.Rejected -> notice = SettingsNotice(false, r.message)
                is EngineResult.Unavailable -> notice = SettingsNotice(
                    false, "O motor está desligado, então não salvei a chave. Inicie o motor e cole a chave de novo.",
                )
            }
        }
    }

    fun remove() {
        if (busy) return
        busy = true
        notice = null
        scope.launch {
            val r = try {
                gateway.removeGeminiKey()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                EngineResult.Unavailable("sem motor")
            }
            busy = false
            when (r) {
                is EngineResult.Ok -> {
                    configured = false
                    notice = SettingsNotice(true, "Chave removida.")
                }
                is EngineResult.Rejected -> notice = SettingsNotice(false, r.message)
                is EngineResult.Unavailable -> notice = SettingsNotice(false, "O motor está desligado. Inicie o motor e tente de novo.")
            }
        }
    }
}

/** Conexões > "Melhorar pedidos (Gemini)": cola a chave uma vez; depois só mostra "Chave salva" e Remover chave. */
@Composable
internal fun GeminiKeyCard(gateway: EngineGateway, modifier: Modifier = Modifier) {
    val scope = rememberCoroutineScope { Dispatchers.Main.immediate }
    val state = remember(gateway) { GeminiKeyState(gateway, scope) }
    LaunchedEffect(state) { state.refresh() }
    // remember, nunca rememberSaveable: a chave não pode ir para o estado salvo do Android.
    var key by remember { mutableStateOf("") }

    AuraCard(modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(
                "Melhorar pedidos (Gemini)", style = AuraType.BodyStrong,
                modifier = Modifier.weight(1f).semantics { heading() },
            )
            if (state.configured == true) StatusChip(StatusKind.Ready, "Chave salva")
        }
        Text(
            "Usa a sua chave do Google AI Studio. Só é usada quando você toca em Melhorar pedido.",
            style = AuraType.SecondarySmall,
        )
        if (state.configured == true) {
            AuraButtonSecondary("Remover chave", state::remove, Modifier.padding(top = 8.dp), enabled = !state.busy)
        } else {
            OutlinedTextField(
                value = key, onValueChange = { key = it }, singleLine = true,
                label = { Text("Chave do Gemini") },
                visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
            )
            AuraButtonSecondary(
                "Salvar chave",
                { val typed = key; key = ""; state.save(typed) }, // o campo é limpo ao enviar
                Modifier.padding(top = 8.dp),
                enabled = key.isNotBlank() && !state.busy,
            )
        }
        state.notice?.let { n ->
            Text(
                n.text,
                style = AuraType.SecondarySmall.copy(color = if (n.ok) AuraColors.Ready.fg else AuraColors.Risk.fg),
                modifier = Modifier.padding(top = 8.dp).semantics { liveRegion = LiveRegionMode.Polite },
            )
        }
    }
}
