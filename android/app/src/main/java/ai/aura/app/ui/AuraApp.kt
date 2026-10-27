package ai.aura.app.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.isImeVisible
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.Text
import androidx.activity.compose.BackHandler
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.coroutines.launch
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import ai.aura.app.ui.components.auraFocusRing
import ai.aura.app.ui.engine.AuthState
import ai.aura.app.ui.engine.AuthStartResult
import ai.aura.app.ui.engine.EngineGateway
import ai.aura.app.ui.engine.FakeEngineGateway
import ai.aura.app.ui.screens.ActivityScreen
import ai.aura.app.ui.screens.DevicesScreen
import ai.aura.app.ui.screens.HomeScreen
import ai.aura.app.ui.screens.ProjectsScreen
import ai.aura.app.ui.screens.onboarding.AccountId
import ai.aura.app.ui.screens.onboarding.AccountState
import ai.aura.app.ui.screens.onboarding.OnboardingFlow
import ai.aura.app.ui.screens.onboarding.TermuxSetupState
import ai.aura.app.ui.screens.onboarding.defaultAccounts
import ai.aura.app.ui.screens.settings.SettingsScreen
import ai.aura.app.ui.theme.AuraColors
import ai.aura.app.ui.theme.AuraShapes
import ai.aura.app.ui.theme.AuraSpacing
import ai.aura.app.ui.theme.AuraTheme
import ai.aura.app.ui.theme.AuraType

enum class AuraTab(val label: String) {
    Home("Início"), Projects("Projetos"), Devices("Dispositivos"), Activity("Atividade"),
}

/**
 * Shell do app: 4 abas por estado (sem navigation-compose), aba guardada com rememberSaveable.
 * Primeira abertura mostra o OnboardingFlow; Ajustes abre por cima da Home (engrenagem) e volta com Voltar.
 * A barra inferior some com o teclado e com o chat aberto; o espaço do teclado é vazio de propósito.
 * [onOpenUrl] é ligado pela MainActivity ao navegador (login de contas).
 * Requer enableEdgeToEdge() + windowSoftInputMode=adjustResize na Activity (fora desta pasta).
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun AuraApp(
    gateway: EngineGateway = remember { FakeEngineGateway() },
    onOpenUrl: (String) -> Unit = {},
) {
    AuraTheme {
        var onboardingDone by rememberSaveable { mutableStateOf(false) }
        var showSettings by rememberSaveable { mutableStateOf(false) }
        var selected by rememberSaveable { mutableIntStateOf(0) }
        var chatOpen by remember { mutableStateOf(false) }
        var pendingJobId by rememberSaveable { mutableStateOf<String?>(null) }
        val tab = AuraTab.entries[selected.coerceIn(0, AuraTab.entries.lastIndex)]
        val imeVisible = WindowInsets.isImeVisible
        BackHandler(enabled = showSettings && onboardingDone) { showSettings = false }
        Column(
            Modifier
                .fillMaxSize()
                .background(AuraColors.Bg)
                .windowInsetsPadding(WindowInsets.statusBars),
        ) {
            if (!onboardingDone) {
                OnboardingHost(gateway, onOpenUrl, onFinished = { onboardingDone = true }, Modifier.weight(1f).fillMaxWidth())
            } else if (showSettings) {
                SettingsScreen(gateway, onClose = { showSettings = false }, modifier = Modifier.weight(1f).fillMaxWidth(), onOpenUrl = onOpenUrl)
            } else {
                Box(Modifier.weight(1f).fillMaxWidth()) {
                    when (tab) {
                        AuraTab.Home -> HomeScreen(
                            gateway, Modifier.fillMaxSize(),
                            onChatOpenChange = { chatOpen = it },
                            onOpenSettings = { showSettings = true },
                            openJobId = pendingJobId,
                            onOpenJobHandled = { pendingJobId = null },
                        )
                        AuraTab.Projects -> ProjectsScreen(gateway, Modifier.fillMaxSize())
                        AuraTab.Devices -> DevicesScreen(gateway, Modifier.fillMaxSize())
                        AuraTab.Activity -> ActivityScreen(
                            gateway, Modifier.fillMaxSize(),
                            onStartRequest = { selected = AuraTab.Home.ordinal },
                            // Reabre a tarefa no chat: HomeScreen restaura por jobId (ChatEntry.jobId).
                            onOpenJob = { id -> pendingJobId = id; selected = AuraTab.Home.ordinal },
                        )
                    }
                }
                if (!imeVisible && !(chatOpen && tab == AuraTab.Home)) {
                    AuraBottomBar(tab, onSelect = { selected = it.ordinal })
                }
            }
        }
    }
}

/**
 * Cola do onboarding com o motor: Termux pelo estado real (capabilities) e contas por authStatus.
 * Login só pelo navegador: o link vem do evento auth.prompt e sai por [onOpenUrl]; nunca pede token.
 * TODO(MainActivity): onRequestTermuxPermission / onOpenTermux dependem da Activity (RUN_COMMAND, intent do Termux).
 */
@Composable
private fun OnboardingHost(
    gateway: EngineGateway,
    onOpenUrl: (String) -> Unit,
    onFinished: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val scope = rememberCoroutineScope()
    var termux by remember { mutableStateOf(TermuxSetupState()) }
    var accounts by remember { mutableStateOf(defaultAccounts()) }
    var tick by remember { mutableIntStateOf(0) }

    fun providerOf(id: AccountId) = when (id) {
        AccountId.Claude -> "claude"
        AccountId.Codex -> "codex"
        AccountId.Google -> "gemini"
        AccountId.GitHub -> "github"
    }

    LaunchedEffect(gateway, tick) {
        val caps = runCatching { gateway.capabilities() }.getOrNull()
        val up = caps?.any { it.id == "termux" && it.available } == true
        termux = TermuxSetupState(termuxFound = up, permissionGranted = up, externalAppsAllowed = up)
        val statuses = runCatching { gateway.authStatus() }.getOrDefault(emptyList())
        accounts = AccountId.entries.map { id ->
            val st = statuses.firstOrNull { it.provider == providerOf(id) }
            AccountState(id, connected = st?.state == AuthState.Connected, detail = st?.detail)
        }
    }
    LaunchedEffect(gateway) {
        gateway.events.collect { e ->
            when (e.type) {
                "auth.prompt" -> e.payload["url"]?.let(onOpenUrl)
                "auth.done", "auth.failed" -> tick++
            }
        }
    }

    OnboardingFlow(
        termux = termux,
        accounts = accounts,
        onRequestTermuxPermission = { tick++ },
        onOpenTermux = { tick++ },
        onSignIn = { id ->
            accounts = accounts.map { if (it.id == id) it.copy(connecting = true) else it }
            scope.launch {
                val r = runCatching { gateway.startAuth(providerOf(id)) }.getOrNull()
                if (r !is AuthStartResult.Started) tick++
            }
        },
        onFinish = onFinished,
        modifier = modifier,
    )
}

@Composable
fun AuraBottomBar(selected: AuraTab, onSelect: (AuraTab) -> Unit, modifier: Modifier = Modifier) {
    Row(
        modifier
            .fillMaxWidth()
            .background(AuraColors.Surface)
            .drawBehind { drawLine(AuraColors.Line, Offset.Zero, Offset(size.width, 0f), 1.dp.toPx()) }
            .windowInsetsPadding(WindowInsets.navigationBars)
            .padding(start = 8.dp, end = 8.dp, top = 8.dp, bottom = 12.dp)
            .semantics { contentDescription = "Navegação principal" },
    ) {
        AuraTab.entries.forEach { t ->
            val isSel = t == selected
            val source = remember { MutableInteractionSource() }
            Column(
                Modifier
                    .weight(1f)
                    .heightIn(min = 56.dp)
                    .clickable(interactionSource = source, indication = null, role = Role.Tab) { onSelect(t) }
                    .semantics { this.selected = isSel },
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Box(
                    Modifier
                        .size(width = 56.dp, height = 32.dp)
                        .clip(AuraShapes.Chip)
                        .background(if (isSel) AuraColors.Ink else Color.Transparent)
                        .auraFocusRing(source, AuraShapes.Chip),
                    contentAlignment = Alignment.Center,
                ) {
                    TabIcon(t, if (isSel) Color.White else AuraColors.Ink2)
                }
                Text(
                    t.label,
                    style = AuraType.Caption.copy(
                        color = if (isSel) AuraColors.Ink else AuraColors.Ink3,
                        fontWeight = if (isSel) FontWeight.Bold else FontWeight.Medium,
                    ),
                )
            }
        }
    }
}

/** Ícones 24 dp, traço 1.75, desenhados em Canvas (sem material-icons-extended). */
@Composable
private fun TabIcon(tab: AuraTab, tint: Color) {
    Canvas(Modifier.size(24.dp)) {
        val u = size.width / 24f
        val stroke = Stroke(width = 1.75f * u, cap = StrokeCap.Round, join = StrokeJoin.Round)
        when (tab) {
            AuraTab.Home -> drawPath(Path().apply {
                moveTo(4 * u, 11 * u); lineTo(12 * u, 4 * u); lineTo(20 * u, 11 * u)
                moveTo(6 * u, 10 * u); lineTo(6 * u, 20 * u); lineTo(18 * u, 20 * u); lineTo(18 * u, 10 * u)
            }, tint, style = stroke)
            AuraTab.Projects -> drawPath(Path().apply {
                moveTo(3 * u, 7 * u); lineTo(10 * u, 7 * u); lineTo(12 * u, 9 * u); lineTo(21 * u, 9 * u)
                lineTo(21 * u, 19 * u); lineTo(3 * u, 19 * u); close()
            }, tint, style = stroke)
            AuraTab.Devices -> {
                drawRoundRect(tint, Offset(7 * u, 3 * u), Size(10 * u, 18 * u), CornerRadius(2.5f * u), style = stroke)
                drawLine(tint, Offset(11 * u, 18 * u), Offset(13 * u, 18 * u), 1.75f * u, StrokeCap.Round)
            }
            AuraTab.Activity -> drawPath(Path().apply {
                moveTo(3 * u, 12 * u); lineTo(8 * u, 12 * u); lineTo(10.5f * u, 5 * u)
                lineTo(13.5f * u, 19 * u); lineTo(16 * u, 12 * u); lineTo(21 * u, 12 * u)
            }, tint, style = stroke)
        }
    }
}

@Preview(showBackground = true, widthDp = 390, heightDp = 844)
@Composable
private fun AuraAppPreview() {
    AuraApp(FakeEngineGateway())
}

@Preview(showBackground = true, widthDp = 390)
@Composable
private fun BottomBarPreview() {
    AuraTheme { AuraBottomBar(AuraTab.Projects, {}) }
}
