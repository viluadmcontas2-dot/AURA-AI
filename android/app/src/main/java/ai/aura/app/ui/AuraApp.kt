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
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.activity.compose.BackHandler
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.mutableStateOf
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
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
import ai.aura.app.offline.LinkState
import ai.aura.app.offline.OfflineReason
import ai.aura.app.ui.components.EngineBanner
import ai.aura.app.ui.components.auraFocusRing
import ai.aura.app.ui.engine.AuthState
import ai.aura.app.ui.engine.EngineGateway
import ai.aura.app.ui.nav.ROUTES
import ai.aura.app.ui.nav.ScreenEnv
import ai.aura.app.ui.nav.popRoute
import ai.aura.app.ui.nav.pushRoute
import ai.aura.app.ui.nav.tabStack
import ai.aura.app.ui.screens.ActivityScreen
import ai.aura.app.ui.screens.DevicesScreen
import ai.aura.app.ui.screens.HomeScreen
import ai.aura.app.ui.screens.ProjectsScreen
import ai.aura.app.ui.screens.onboarding.AccountId
import ai.aura.app.ui.screens.onboarding.AccountState
import ai.aura.app.ui.screens.onboarding.OnboardingFlow
import ai.aura.app.ui.screens.settings.AccountsPromptSlot
import ai.aura.app.ui.screens.settings.rememberAccountsAuth
import ai.aura.app.ui.screens.onboarding.LocalTermuxFlow
import ai.aura.app.ui.screens.onboarding.TermuxSetupState
import ai.aura.app.ui.screens.onboarding.termuxSetupFrom
import ai.aura.app.ui.theme.AuraColors
import ai.aura.app.ui.theme.AuraShapes
import ai.aura.app.ui.theme.AuraSpacing
import ai.aura.app.ui.theme.AuraTheme
import ai.aura.app.ui.theme.AuraType

/** Padrão de [AuraApp] sem motor real ligado: tudo online, sem banner. */
private val NoLink: StateFlow<LinkState> = MutableStateFlow(LinkState.Online)

enum class AuraTab(val label: String) {
    Home("Início"), Projects("Projetos"), Devices("Dispositivos"), Activity("Atividade"),
}

/**
 * Shell do app: pilha de rotas por estado (sem navigation-compose; `ui/nav/Routes.kt`), guardada com rememberSaveable.
 * Primeira abertura mostra o OnboardingFlow; Ajustes abre por cima da Home (engrenagem) e volta com Voltar.
 * [linkState] e [onEngineAction] alimentam o banner global "Motor desligado".
 * A barra inferior some com o teclado e com o chat aberto; o espaço do teclado é vazio de propósito.
 * [onOpenUrl] é ligado pela MainActivity ao navegador (login de contas).
 * Requer enableEdgeToEdge() + windowSoftInputMode=adjustResize na Activity (fora desta pasta).
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun AuraApp(
    gateway: EngineGateway,
    onOpenUrl: (String) -> Unit = {},
    onRequestTermuxPermission: () -> Unit = {},
    onRequestNotifications: () -> Unit = {},
    onOpenTermux: () -> Unit = {},
    termuxInstalled: () -> Boolean = { false },
    termuxPermissionGranted: () -> Boolean = { false },
    onboardingDone: Boolean = false,
    onOnboardingFinished: () -> Unit = {},
    initialDraft: String = "",
    onSaveDraft: (String) -> Unit = {},
    lastCrash: String? = null,
    onDismissCrash: () -> Unit = {},
    linkState: StateFlow<LinkState> = NoLink,
    onEngineAction: (OfflineReason) -> Unit = {},
    settingsGateway: ai.aura.app.integration.SettingsGateway = ai.aura.app.integration.NoEngineSettingsGateway,
    queueGateway: ai.aura.app.integration.QueueGateway = ai.aura.app.integration.NoEngineQueueGateway,
) {
    AuraTheme {
        if (lastCrash != null) {
            CrashReport(lastCrash, onDismissCrash)
            return@AuraTheme
        }
        // O valor de fora manda; rememberSaveable é só espelho para rotação.
        var onboardingDoneState by rememberSaveable(onboardingDone) { mutableStateOf(onboardingDone) }
        var draft by rememberSaveable(initialDraft) { mutableStateOf(initialDraft) }
        // Pilha de rotas salva como texto ("inicio,ajustes"). Só entram ids com tela; vazio cai no Início.
        var stackText by rememberSaveable { mutableStateOf("inicio") }
        var chatOpen by remember { mutableStateOf(false) }
        var pendingJobId by rememberSaveable { mutableStateOf<String?>(null) }
        var pendingPrompt by rememberSaveable { mutableStateOf<String?>(null) }
        val stack = stackText.split(",").filter { id -> ROUTES.any { it.id == id && it.screen != null } }.ifEmpty { listOf("inicio") }
        val route = ROUTES.first { it.id == stack.last() }
        val link by linkState.collectAsState()
        val imeVisible = WindowInsets.isImeVisible
        val go = { next: List<String> -> stackText = next.joinToString(",") }
        // "Compartilhar para o AURA": um .apk compartilhado abre direto no Escopo da reforma (Voltar volta à escolha).
        val reform = ai.aura.app.ui.screens.reform.LocalReformController.current
        val hostActivity = findActivity(LocalContext.current)
        var shareHandled by rememberSaveable { mutableStateOf(false) }
        LaunchedEffect(onboardingDoneState, reform) {
            if (!onboardingDoneState || shareHandled || reform == null || hostActivity == null) return@LaunchedEffect
            val shared = ai.aura.app.ui.screens.reform.sharedApkFromIntent(hostActivity, hostActivity.intent) ?: return@LaunchedEffect
            shareHandled = true
            reform.select(shared)
            go(listOf("inicio", "reformar-apps", "reformar-escopo"))
        }
        // Voltar desempilha; só na raiz (Início) o sistema fecha o app. Telas com Voltar próprio (chat,
        // etapas, Ajustes > Contas) vencem por serem compostas depois.
        BackHandler(enabled = onboardingDoneState && stack.size > 1) { popRoute(stack)?.let(go) }
        val env = ScreenEnv(
            gateway = gateway,
            open = { go(pushRoute(stack, it)) },
            openTab = { go(tabStack(it)) },
            back = { popRoute(stack)?.let(go) },
            onOpenUrl = onOpenUrl,
            onChatOpenChange = { chatOpen = it },
            openJobId = pendingJobId,
            onOpenJobHandled = { pendingJobId = null },
            openJob = { id -> pendingJobId = id; go(tabStack(AuraTab.Home)) },
            draft = draft,
            onDraftChange = { draft = it; onSaveDraft(it) },
            onRequestTermuxPermission = onRequestTermuxPermission,
            onRequestNotifications = onRequestNotifications,
            onOpenTermux = onOpenTermux,
            link = link,
            onEngineAction = onEngineAction,
            termuxInstalled = termuxInstalled,
            termuxPermissionGranted = termuxPermissionGranted,
            onOnboardingFinished = { onboardingDoneState = true; onOnboardingFinished() },
            settings = settingsGateway,
            queue = queueGateway,
            pendingPrompt = pendingPrompt,
            onPendingPromptHandled = { pendingPrompt = null },
            sendPrompt = { text -> pendingPrompt = text; go(tabStack(AuraTab.Home)) },
        )
        Column(
            Modifier
                .fillMaxSize()
                .background(AuraColors.Bg)
                .windowInsetsPadding(WindowInsets.statusBars),
        ) {
            // O banner vale para o app inteiro; o onboarding tem o próprio passo do Termux e não leva banner.
            // O banner também leva à tela Conexões (Termux, contas e permissões), exceto quando ela já está aberta.
            if (onboardingDoneState) {
                EngineBanner(
                    link, onEngineAction,
                    onOpenConnections = if (route.id == "conexoes") null else ({ env.open("conexoes") }),
                )
            }
            Box(Modifier.weight(1f).fillMaxWidth()) {
                // Sem pilha no onboarding: enquanto não termina, ele é a única tela.
                (if (onboardingDoneState) route else ROUTES.first { it.id == "onb-boas-vindas" }).screen?.invoke(env)
            }
            if (onboardingDoneState && route.tab != null && !imeVisible && !(chatOpen && route.tab == AuraTab.Home)) {
                AuraBottomBar(route.tab, onSelect = env.openTab)
            }
        }
    }
}

/** A Activity por trás do contexto do Compose (ou null em testes sem Activity). */
private fun findActivity(context: android.content.Context): android.app.Activity? {
    var c: android.content.Context? = context
    while (c is android.content.ContextWrapper) {
        if (c is android.app.Activity) return c
        c = c.baseContext
    }
    return null
}

/**
 * Cola do onboarding com o motor: Termux pelo estado real (capabilities) e contas por authStatus.
 * O login roda no Termux pelo motor (código e link na tela, [onOpenUrl] só abre o link); nunca pede token.
 * Os ganchos onRequestTermuxPermission / onOpenTermux vêm da MainActivity (RUN_COMMAND, intent do Termux).
 */
@Composable
internal fun OnboardingHost(
    gateway: EngineGateway,
    onOpenUrl: (String) -> Unit,
    onRequestTermuxPermission: () -> Unit,
    onOpenTermux: () -> Unit,
    termuxInstalled: () -> Boolean,
    termuxPermissionGranted: () -> Boolean,
    onFinished: () -> Unit,
    modifier: Modifier = Modifier,
    onRequestNotifications: () -> Unit = {},
) {
    val flow = LocalTermuxFlow.current
    var termux by remember { mutableStateOf(TermuxSetupState()) }
    LaunchedEffect(flow) { flow?.state?.collect { termux = it } }
    DisposableEffect(flow) { onDispose { flow?.cancel() } }
    var tick by remember { mutableIntStateOf(0) }
    // Contas pelo motor: o login roda no Termux; o app mostra link e código e confere o estado até "Conectado".
    val auth = rememberAccountsAuth(gateway)

    fun providerOf(id: AccountId) = when (id) {
        AccountId.Claude -> "claude"
        AccountId.Codex -> "codex"
        AccountId.Google -> "gemini"
        AccountId.GitHub -> "github"
    }

    // Voltou do Termux/navegador: reconfere (a permissão e o login terminam fora do app).
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_RESUME) tick++ }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    LaunchedEffect(gateway, tick) {
        if (flow != null) {
            flow.resume()
        } else {
            val caps = runCatching { gateway.capabilities() }.getOrNull()
            val engineUp = caps?.any { it.id == "termux" && it.available } == true
            termux = termuxSetupFrom(termuxInstalled(), termuxPermissionGranted(), engineUp)
        }
        auth.refresh()
    }

    OnboardingFlow(
        termux = termux,
        accounts = AccountId.entries.map { id ->
            val st = auth.statusOf(providerOf(id))
            AccountState(
                id, connected = st?.state == AuthState.Connected, detail = st?.detail,
                connecting = auth.starting == providerOf(id),
                state = st?.state, installable = st?.installable == true,
            )
        },
        // Depois do callback da Activity, reconfere capabilities (tick).
        onRequestTermuxPermission = { onRequestTermuxPermission(); tick++ },
        onOpenTermux = onOpenTermux,
        onCheckTermux = { flow?.confirmPasted() ?: onOpenTermux() },
        onInstallTermux = { onOpenUrl("https://f-droid.org/packages/com.termux/") },
        onSignIn = { id -> auth.connect(providerOf(id)) },
        onInstallAccount = { id -> auth.install(providerOf(id)) },
        onRequestNotifications = onRequestNotifications,
        promptSlot = { AccountsPromptSlot(auth, onOpenUrl) },
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

@Preview(showBackground = true, widthDp = 390)
@Composable
private fun BottomBarPreview() {
    AuraTheme { AuraBottomBar(AuraTab.Projects, {}) }
}

/** Mostra o último erro que fechou o app, com cópia. Some ao continuar. */
@Composable
private fun CrashReport(report: String, onContinue: () -> Unit) {
    val clipboard = LocalClipboardManager.current
    Column(
        modifier = Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.statusBars).padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("O AURA fechou sozinho na última vez", style = MaterialTheme.typography.titleMedium)
        Text("Copie o erro abaixo e envie para correção. Nada foi apagado.", style = MaterialTheme.typography.bodyMedium)
        Box(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState())) {
            Text(report, style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Button(onClick = { clipboard.setText(AnnotatedString(report)) }) { Text("Copiar erro") }
            Button(onClick = onContinue) { Text("Continuar") }
        }
    }
}
