package ai.aura.app.ui.screens.connections

import ai.aura.app.offline.LinkState
import ai.aura.app.offline.OfflineReason
import ai.aura.app.permissions.AppPermission
import ai.aura.app.permissions.PermissionState
import ai.aura.app.ui.components.AuraButtonSecondary
import ai.aura.app.ui.components.AuraCard
import ai.aura.app.ui.components.StatusChip
import ai.aura.app.ui.components.StatusKind
import ai.aura.app.ui.components.engineActionLabel
import ai.aura.app.ui.engine.EngineGateway
import ai.aura.app.ui.screens.devices.BackHeader
import ai.aura.app.ui.screens.devices.SectionTitle
import ai.aura.app.ui.screens.permissions.PermissionCard
import ai.aura.app.ui.screens.permissions.PermissionSheet
import ai.aura.app.ui.screens.permissions.rememberPermissionsController
import ai.aura.app.ui.screens.settings.AUTH_POLL_MILLIS
import ai.aura.app.ui.screens.settings.AccountsList
import ai.aura.app.ui.screens.settings.AccountsPromptSlot
import ai.aura.app.ui.screens.settings.rememberAccountsAuth
import ai.aura.app.ui.theme.AuraColors
import ai.aura.app.ui.theme.AuraSpacing
import ai.aura.app.ui.theme.AuraType
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/** Estado do Termux e do motor em uma linha, no vocabulário do banner "Motor desligado". */
internal data class EngineLine(val kind: StatusKind, val chip: String, val hint: String, val action: String?, val reason: OfflineReason?)

internal fun engineLine(link: LinkState): EngineLine = when (link) {
    LinkState.Online -> EngineLine(StatusKind.Ready, "Ligado", "O motor responde neste celular.", null, null)
    LinkState.Starting -> EngineLine(StatusKind.Info, "Iniciando", "Aguarde alguns segundos. Eu confiro sozinho.", null, null)
    LinkState.Unknown -> EngineLine(
        StatusKind.Neutral, "Verificando", "Toque em Iniciar o motor se ele não ligar sozinho.",
        engineActionLabel(OfflineReason.EngineStopped), OfflineReason.EngineStopped,
    )
    is LinkState.Offline -> EngineLine(
        StatusKind.NeedsYou, "Desligado",
        when (link.reason) {
            OfflineReason.NoTermux -> "O Termux não está instalado. Instale pela F-Droid e volte aqui."
            OfflineReason.NoPermission -> "O AURA ainda não tem permissão para falar com o Termux."
            OfflineReason.NoToken -> "Falta conectar o Termux ao AURA."
            OfflineReason.EngineStopped -> "O motor está parado. O que você pedir fica guardado até ele ligar."
        },
        engineActionLabel(link.reason), link.reason,
    )
}

/**
 * Conexões: tudo que liga o AURA ao que ele usa, numa tela só e com as mesmas palavras do banner
 * "Motor desligado". Termux e motor, contas (login feito pelo motor, dentro do Termux) e permissões do Android.
 * Abre sempre: sem motor, as contas dizem "Indisponível" e o Termux mostra a ação certa.
 */
@Composable
fun ConnectionsScreen(
    gateway: EngineGateway,
    link: LinkState,
    onEngineAction: (OfflineReason) -> Unit,
    onOpenUrl: (String) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    authPollMillis: Long = AUTH_POLL_MILLIS,
) {
    val auth = rememberAccountsAuth(gateway, authPollMillis)
    val permissions = rememberPermissionsController()
    var sheetFor by rememberSaveable { mutableStateOf<String?>(null) }
    val sheetItem: AppPermission? = permissions.rows
        .firstOrNull { it.item.permission == sheetFor && it.state == PermissionState.CanAsk }?.item
    BackHandler(enabled = sheetItem != null) { sheetFor = null }
    val engine = engineLine(link)

    Box(modifier.fillMaxSize().background(AuraColors.Bg)) {
        Column(Modifier.fillMaxSize().statusBarsPadding()) {
            BackHeader("Conexões", "Termux, contas e permissões", onBack)
            Column(
                Modifier.weight(1f).verticalScroll(rememberScrollState())
                    .padding(horizontal = AuraSpacing.ScreenGutter, vertical = 4.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                SectionTitle("Termux e motor")
                AuraCard(Modifier.fillMaxWidth()) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Text("Motor do AURA", style = AuraType.BodyStrong, modifier = Modifier.weight(1f))
                        StatusChip(engine.kind, engine.chip)
                    }
                    Text(engine.hint, style = AuraType.SecondarySmall)
                    val action = engine.action
                    val reason = engine.reason
                    if (action != null && reason != null) {
                        AuraButtonSecondary(action, { onEngineAction(reason) }, Modifier.padding(top = 8.dp))
                    }
                }

                SectionTitle("Contas", Modifier.padding(top = 8.dp))
                Text(
                    "Use as assinaturas que você já paga. O login roda dentro do Termux; aqui você só copia o código e abre o link.",
                    style = AuraType.Secondary,
                )
                AccountsPromptSlot(auth, onOpenUrl)
                AccountsList(auth)
                Text(
                    "Chaves de API (ANTHROPIC_API_KEY, GEMINI_API_KEY) são opcionais e ficam em Detalhes.",
                    style = AuraType.SecondarySmall,
                    modifier = Modifier.padding(horizontal = 4.dp),
                )

                SectionTitle("Permissões do Android", Modifier.padding(top = 8.dp))
                Text("O AURA só pede quando você usa a função. Negar nunca trava o app.", style = AuraType.Secondary)
                permissions.rows.forEach { row ->
                    PermissionCard(row, onAllow = { sheetFor = row.item.permission }, onOpenSettings = permissions.openSettings)
                }
            }
        }
        if (sheetItem != null) {
            PermissionSheet(
                sheetItem,
                onAllow = { sheetFor = null; permissions.ask(sheetItem) },
                onNotNow = { sheetFor = null },
            )
        }
    }
}
