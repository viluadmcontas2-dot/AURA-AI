package ai.aura.app.ui.screens.connections

import ai.aura.app.offline.LinkState
import ai.aura.app.offline.OfflineReason
import ai.aura.app.permissions.AppPermission
import ai.aura.app.permissions.PermissionState
import ai.aura.app.ui.components.AuraButtonPrimary
import ai.aura.app.ui.components.AuraButtonText
import ai.aura.app.ui.components.AuraCard
import ai.aura.app.ui.components.StatusChip
import ai.aura.app.ui.components.StatusKind
import ai.aura.app.integration.SetupStatus
import ai.aura.app.integration.StatusResult
import ai.aura.app.ui.engine.EngineGateway
import ai.aura.app.ui.screens.onboarding.LocalTermuxFlow
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import kotlinx.coroutines.launch
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/**
 * Conexões: preparar o assistente, numa tela só (docs/FLUXOS_DE_USO.md, seção 6). Três grupos: "Assistente neste
 * celular", "Conta do Claude" e "Permissões", cada um com o estado REAL e UMA ação principal para o passo atual.
 * Entrar por Início, Ajustes ou Dispositivos abre esta mesma tela. Abre sempre: sem motor, a conta diz "Indisponível".
 * Nada aqui instala sozinho: Preparar motor e Instalar o Claude abrem o pedido "Instalar programas necessários?".
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
    val flow = LocalTermuxFlow.current
    val termux = flow?.state?.collectAsState()?.value
    val setup = LocalSetupGateway.current
    val scope = rememberCoroutineScope()
    var setupStatus by remember { mutableStateOf<SetupStatus?>(null) }
    var recheck by remember { mutableIntStateOf(0) }
    // C3: só leitura. Sem motor não há o que perguntar; o estado fica "desconhecido", nunca "desconectado".
    LaunchedEffect(setup, link, recheck) {
        setupStatus = if (link == LinkState.Online) (runCatching { setup.status() }.getOrNull() as? StatusResult.Ok)?.status else null
    }
    var sheetFor by rememberSaveable { mutableStateOf<String?>(null) }
    val sheetItem: AppPermission? = permissions.rows
        .firstOrNull { it.item.permission == sheetFor && it.state == PermissionState.CanAsk }?.item
    BackHandler(enabled = sheetItem != null) { sheetFor = null }

    val claudeStatus = if (auth.engineAnswered) auth.statusOf("claude") else null
    val signal = if (link == LinkState.Online) claudeSignal(claudeStatus, setupStatus, signingIn = auth.starting == "claude") else ClaudeSignal.Unknown
    val plan = connectionsPlan(link, termux, signal)
    val busy = auth.starting != null || auth.installing != null

    fun perform(action: ConnectionAction) = when (action) {
        ConnectionAction.InstallTermux -> onEngineAction(OfflineReason.NoTermux)
        ConnectionAction.AllowTermux -> onEngineAction(OfflineReason.NoPermission)
        ConnectionAction.PairTermux -> onEngineAction(OfflineReason.NoToken)
        ConnectionAction.PrepareEngine, ConnectionAction.StartEngine -> onEngineAction(OfflineReason.EngineStopped)
        ConnectionAction.InstallClaude -> auth.install("claude")
        ConnectionAction.SignInClaude, ConnectionAction.SignInAgain -> auth.connect("claude")
        ConnectionAction.CheckSignIn -> scope.launch { auth.refresh(); recheck++ }.let { }
    }

    Box(modifier.fillMaxSize().background(AuraColors.Bg)) {
        Column(Modifier.fillMaxSize().statusBarsPadding()) {
            BackHeader("Conexões", "Assistente, conta e permissões", onBack)
            Column(
                Modifier.weight(1f).verticalScroll(rememberScrollState())
                    .padding(horizontal = AuraSpacing.ScreenGutter, vertical = 4.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                SectionTitle("Assistente neste celular")
                GroupCard("Assistente", plan.assistant, plan.action.takeIf { plan.actionInAssistant }, !busy, ::perform)

                SectionTitle("Conta do Claude", Modifier.padding(top = 8.dp))
                AccountsPromptSlot(auth, onOpenUrl)
                GroupCard(
                    "Claude", plan.claude, plan.action.takeIf { !plan.actionInAssistant },
                    enabled = !busy && auth.prompt == null, onAction = ::perform,
                )

                SectionTitle("Permissões", Modifier.padding(top = 8.dp))
                Text("O AURA só pede quando você usa a função. Negar nunca trava o app.", style = AuraType.Secondary)
                permissions.rows.forEach { row ->
                    PermissionCard(row, onAllow = { sheetFor = row.item.permission }, onOpenSettings = permissions.openSettings)
                }

                Disclosure("Melhorar pedidos (opcional)", "Usa a sua chave do Gemini, só quando você toca em Melhorar pedido.") {
                    GeminiKeyCard(gateway)
                }
                Disclosure("Detalhes", "Outras contas e opções avançadas.") {
                    Text(
                        "Outras contas (Gemini, Codex, GitHub, Drive) são opcionais. O login roda dentro do Termux; aqui você só copia o código e abre a página.",
                        style = AuraType.Secondary,
                    )
                    AccountsList(auth, exclude = setOf("claude"))
                    Text(
                        "Chaves de API (ANTHROPIC_API_KEY, GEMINI_API_KEY) são opcionais. O AURA nunca instala nada sem a sua confirmação.",
                        style = AuraType.SecondarySmall,
                        modifier = Modifier.padding(horizontal = 4.dp),
                    )
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

/** Cartão de um grupo: nome, estado real, frase de apoio e (só no passo atual) o botão principal. */
@Composable
private fun GroupCard(
    name: String,
    line: GroupLine,
    action: ConnectionAction?,
    enabled: Boolean,
    onAction: (ConnectionAction) -> Unit,
) {
    AuraCard(Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(name, style = AuraType.BodyStrong, modifier = Modifier.weight(1f))
            StatusChip(line.kind, line.chip)
        }
        Text(line.hint, style = AuraType.SecondarySmall, modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
        if (action != null) {
            AuraButtonPrimary(action.label, { onAction(action) }, Modifier.fillMaxWidth().padding(top = 8.dp), enabled = enabled)
        }
    }
}

/** Seção recolhida: título + frase curta; abrir mostra o conteúdo. O estado sobrevive à rotação. */
@Composable
private fun Disclosure(title: String, summary: String, content: @Composable () -> Unit) {
    var open by rememberSaveable(title) { mutableStateOf(false) }
    Column(Modifier.padding(top = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        AuraButtonText(if (open) "Esconder: $title" else title, { open = !open }, Modifier.fillMaxWidth())
        if (!open) Text(summary, style = AuraType.SecondarySmall, modifier = Modifier.padding(horizontal = 4.dp))
        if (open) content()
    }
}
