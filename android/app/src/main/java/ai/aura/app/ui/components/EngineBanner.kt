package ai.aura.app.ui.components

import ai.aura.app.offline.LinkState
import ai.aura.app.offline.OfflineReason
import ai.aura.app.ui.theme.AuraColors
import ai.aura.app.ui.theme.AuraShapes
import ai.aura.app.ui.theme.AuraType
import androidx.compose.foundation.background
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
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp

/** Palavra da ação para cada motivo: a mesma no banner e na tela Conexões. */
fun engineActionLabel(reason: OfflineReason): String = when (reason) {
    OfflineReason.NoTermux -> "Instalar o Termux"
    OfflineReason.NoPermission -> "Permitir"
    OfflineReason.NoToken -> "Conectar"
    OfflineReason.EngineStopped -> "Iniciar o motor"
}

/**
 * Aviso global "Motor desligado" com UMA ação pelo motivo (regra 1: nunca botão mudo). Some com o
 * motor online ou ainda não consultado. [onAction] recebe o motivo; a ação de verdade é do T6.
 */
@Composable
fun EngineBanner(
    state: LinkState,
    onAction: (OfflineReason) -> Unit,
    modifier: Modifier = Modifier,
    /** Quando o shell sabe abrir a tela Conexões, o banner leva até ela (Termux, contas e permissões num lugar só). */
    onOpenConnections: (() -> Unit)? = null,
) {
    if (state !is LinkState.Starting && state !is LinkState.Offline) return
    Column(modifier.fillMaxWidth().background(AuraColors.NeedsYou.bg)) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Box(Modifier.size(8.dp).clip(AuraShapes.Circle).background(AuraColors.NeedsYou.dot))
            Text(
                "Motor desligado",
                style = AuraType.BodyStrong.copy(color = AuraColors.NeedsYou.fg),
                modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
            )
        }
        if (state is LinkState.Offline) {
            AuraButtonSecondary(engineActionLabel(state.reason), { onAction(state.reason) })
        } else {
            Text("Iniciando…", style = AuraType.Secondary.copy(color = AuraColors.NeedsYou.fg))
        }
    }
    if (onOpenConnections != null) {
        AuraButtonText("Abrir Conexões", onOpenConnections, Modifier.padding(start = 8.dp))
    }
    }
}
