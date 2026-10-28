package ai.aura.app.ui.screens.onboarding

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import ai.aura.app.ui.components.AuraButtonPrimary
import ai.aura.app.ui.components.AuraButtonSecondary
import ai.aura.app.ui.components.AuraButtonText
import ai.aura.app.ui.components.AuraCard
import ai.aura.app.ui.components.StatusChip
import ai.aura.app.ui.components.StatusKind
import ai.aura.app.ui.engine.AuthState
import ai.aura.app.ui.screens.devices.GlyphTile
import ai.aura.app.ui.theme.AuraColors
import ai.aura.app.ui.theme.AuraShapes
import ai.aura.app.ui.theme.AuraSpacing
import ai.aura.app.ui.theme.AuraTheme
import ai.aura.app.ui.theme.AuraType

/** Linha que o usuário cola no Termux (passo 3 do Termux). */
const val TERMUX_ALLOW_EXTERNAL_COMMAND = "echo \"allow-external-apps=true\" >> ~/.termux/termux.properties"

/**
 * Estado verificado do Termux. Quem preenche é a camada de integração (fora desta UI);
 * a UI nunca marca "Feito" por conta própria.
 */
data class TermuxSetupState(
    val termuxFound: Boolean = false,
    val permissionGranted: Boolean = false,
    val externalAppsAllowed: Boolean = false,
    val testing: Boolean = false,
    val paired: Boolean = false,
    val engineUp: Boolean = false,
    val pageSize: Int = 4096,
    val notice: String? = null,
    /** O motor está sendo copiado e instalado no Termux (leva minutos): nada recomeça nem cancela isso. */
    val installing: Boolean = false,
) {
    val ready: Boolean get() = termuxFound && permissionGranted && externalAppsAllowed && paired && engineUp
}

enum class AccountId(val label: String, val subtitle: String, val isBrain: Boolean) {
    Claude("Claude Code", "Usa a sua assinatura do Claude", true),
    Codex("Codex (ChatGPT)", "Usa a sua assinatura do ChatGPT", true),
    Google("Google", "Gemini, Drive e Agenda", true),
    GitHub("GitHub", "Guardar e publicar código", false),
}

/**
 * [connected] só vem de verificação real do motor; [connecting] = login em andamento no Termux, aguardando.
 * [state] é o estado que o motor informou (null = ainda não informou); [installable] = o programa falta e o motor sabe instalar.
 */
data class AccountState(
    val id: AccountId,
    val connected: Boolean = false,
    val detail: String? = null,
    val connecting: Boolean = false,
    val state: AuthState? = null,
    val installable: Boolean = false,
)

fun defaultAccounts(): List<AccountState> = AccountId.entries.map { AccountState(it) }

/**
 * Onboarding em 3 telas por estado local: Boas-vindas, Conectar o Termux, Conectar contas.
 * Só UI + callbacks. Os pedidos do Android saem no passo certo: RUN_COMMAND do Termux ao chegar no passo do
 * Termux (e de novo pelo botão), avisos ao chegar em Contas; negar não trava nada. Login das contas pelo motor,
 * dentro do Termux: [onSignIn] pede o código e o link, mostrados em [promptSlot]; nunca se pede token.
 */
@Composable
fun OnboardingFlow(
    termux: TermuxSetupState,
    accounts: List<AccountState>,
    onRequestTermuxPermission: () -> Unit,
    onOpenTermux: () -> Unit,
    onSignIn: (AccountId) -> Unit,
    onInstallAccount: (AccountId) -> Unit,
    onRequestNotifications: () -> Unit,
    onFinish: () -> Unit,
    modifier: Modifier = Modifier,
    onHelp: () -> Unit = {},
    onCheckTermux: () -> Unit = onOpenTermux,
    onInstallTermux: () -> Unit = onOpenTermux,
    promptSlot: @Composable () -> Unit = {},
) {
    var step by rememberSaveable { mutableIntStateOf(0) }
    BackHandler(enabled = step > 0) { step -= 1 }
    // Avisos: pedidos uma vez, quando o dono chega em Contas. Negar não muda nada além de "sem aviso fora do app".
    LaunchedEffect(step) { if (step == 2) onRequestNotifications() }
    when (step.coerceIn(0, 2)) {
        0 -> OnboardingWelcomeScreen(onStart = { step = 1 }, modifier = modifier)
        1 -> OnboardingTermuxScreen(
            state = termux, onRequestPermission = onRequestTermuxPermission, onOpenTermux = onOpenTermux,
            onContinue = { step = 2 }, onHelp = onHelp, modifier = modifier,
            onCheckTermux = onCheckTermux, onInstallTermux = onInstallTermux,
        )
        else -> OnboardingAccountsScreen(
            accounts = accounts, onSignIn = onSignIn, onInstall = onInstallAccount, onFinish = onFinish,
            modifier = modifier, promptSlot = promptSlot,
        )
    }
}

// ---------------------------------------------------------------- 1. Boas-vindas

@Composable
fun OnboardingWelcomeScreen(onStart: () -> Unit, modifier: Modifier = Modifier) {
    Column(modifier.fillMaxSize().background(AuraColors.Bg).statusBarsPadding().navigationBarsPadding()) {
        Box(Modifier.weight(1f).fillMaxWidth().padding(top = 32.dp), contentAlignment = Alignment.Center) {
            AuraOrb()
        }
        val big = AuraType.Display.copy(fontSize = 34.sp, lineHeight = 38.sp)
        Column(Modifier.padding(horizontal = 24.dp).semantics { heading() }) {
            Text("Você diz\no que quer.", style = big)
            Text("O AURA descobre\ncomo fazer.", style = big.copy(color = AuraColors.Ink2))
            Text("Você acompanha\no que importa.", style = big.copy(color = Color(0xFF5E5B66)))
        }
        Column(
            Modifier.padding(start = 20.dp, end = 20.dp, top = 32.dp, bottom = 28.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            AuraButtonPrimary("Começar", onStart, Modifier.fillMaxWidth(), large = true)
            Text("Seus arquivos ficam no seu celular.", style = AuraType.SecondarySmall)
        }
    }
}

/** Esfera AURA: decorativa, respira devagar (6 s). */
@Composable
private fun AuraOrb(modifier: Modifier = Modifier) {
    val t = rememberInfiniteTransition(label = "orb")
    val scale by t.animateFloat(
        initialValue = 1f, targetValue = 1.04f,
        animationSpec = infiniteRepeatable(tween(3000, easing = FastOutSlowInEasing), RepeatMode.Reverse),
        label = "breathe",
    )
    Box(modifier.size(240.dp).clearAndSetSemantics { }) {
        Canvas(Modifier.fillMaxSize().graphicsLayer { scaleX = scale; scaleY = scale }) {
            val r = size.minDimension / 2f
            val c = Offset(size.width / 2f, size.height / 2f)
            drawCircle(
                Brush.radialGradient(
                    listOf(AuraColors.Periwinkle.copy(alpha = 0.5f), AuraColors.Rose.copy(alpha = 0.28f), Color.Transparent),
                    center = c, radius = r * 1.5f,
                ),
                radius = r * 1.5f, center = c,
            )
            drawCircle(Color(0xFFEADFFB), radius = r, center = c)
            fun blob(color: Color, fx: Float, fy: Float, rad: Float) = drawCircle(
                Brush.radialGradient(
                    listOf(color, color.copy(alpha = 0f)),
                    center = Offset(size.width * fx, size.height * fy), radius = r * rad,
                ),
                radius = r, center = c,
            )
            blob(Color(0xFFFFA98A), 0.80f, 0.78f, 1.2f)
            blob(AuraColors.Mint, 0.16f, 0.82f, 1.1f)
            blob(AuraColors.Periwinkle, 0.82f, 0.20f, 1.25f)
            blob(Color.White.copy(alpha = 0.96f), 0.30f, 0.24f, 0.64f)
        }
    }
}

// ---------------------------------------------------------------- 2. Termux

@Composable
fun OnboardingTermuxScreen(
    state: TermuxSetupState,
    onRequestPermission: () -> Unit,
    onOpenTermux: () -> Unit,
    onContinue: () -> Unit,
    onHelp: () -> Unit,
    modifier: Modifier = Modifier,
    onCheckTermux: () -> Unit = onOpenTermux,
    onInstallTermux: () -> Unit = onOpenTermux,
) {
    val clipboard = LocalClipboardManager.current
    var copied by remember { mutableStateOf(false) }
    // Termux encontrado e permissão pendente: o diálogo do Android abre sozinho, uma vez. Depois disso só pelo botão.
    var askedOnce by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(state.termuxFound, state.permissionGranted) {
        if (state.termuxFound && !state.permissionGranted && !askedOnce) {
            askedOnce = true
            onRequestPermission()
        }
    }
    Column(modifier.fillMaxSize().background(AuraColors.Bg).statusBarsPadding().navigationBarsPadding()) {
        Column(
            Modifier.weight(1f).verticalScroll(rememberScrollState())
                .padding(horizontal = AuraSpacing.ScreenGutter, vertical = AuraSpacing.S6),
            verticalArrangement = Arrangement.spacedBy(AuraSpacing.S3),
        ) {
            Text("PASSO 2 DE 3", style = AuraType.Caption)
            Text("Conectar o Termux", style = AuraType.TabTitle, modifier = Modifier.semantics { heading() })
            Text("É nele que o AURA trabalha, dentro do seu celular.", style = AuraType.Secondary)
            Spacer(Modifier.height(4.dp))

            SetupStep("Termux encontrado", done = state.termuxFound,
                hint = if (state.termuxFound) null else "Instale o Termux (pela F-Droid) e volte aqui.") {
                if (!state.termuxFound) AuraButtonSecondary("Instalar Termux", onInstallTermux)
            }

            SetupStep(
                "Permitir que o AURA use o Termux", done = state.permissionGranted, doneLabel = "Permitido",
                hint = if (state.permissionGranted) null else "O Android vai pedir a sua permissão agora. Se não aparecer, toque em Permitir acesso ao Termux.",
            ) {
                if (!state.permissionGranted) {
                    AuraButtonSecondary("Permitir acesso ao Termux", onRequestPermission, enabled = state.termuxFound)
                }
            }

            SetupStep("Aceitar comandos de outros apps", done = state.externalAppsAllowed, number = 3) {
                if (!state.externalAppsAllowed) {
                    Text(
                        "Cole esta linha no Termux e toque em Enter. Isso deixa o AURA enviar comandos para ele.",
                        style = AuraType.SecondarySmall,
                    )
                    Row(
                        Modifier.fillMaxWidth().clip(AuraShapes.Chip).background(AuraColors.SurfaceSunken).padding(12.dp),
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        Text("$", style = AuraType.Mono.copy(color = AuraColors.Ink3))
                        Text(TERMUX_ALLOW_EXTERNAL_COMMAND, style = AuraType.Mono.copy(color = AuraColors.Ink))
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        AuraButtonSecondary("Copiar comando", {
                            clipboard.setText(AnnotatedString(TERMUX_ALLOW_EXTERNAL_COMMAND))
                            copied = true
                        }, Modifier.weight(1f))
                        AuraButtonSecondary("Abrir Termux", onOpenTermux, Modifier.weight(1f), enabled = state.termuxFound)
                    }
                    if (copied) Text("Copiado. Cole no Termux e toque em Enter.", style = AuraType.SecondarySmall)
                }
            }

            if (!state.ready) {
                Text(state.notice ?: proximoPasso(state).motivo, style = AuraType.SecondarySmall,
                    modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
                if (state.termuxFound && state.permissionGranted && !state.testing) {
                    val label = when (proximoPasso(state)) {
                        Passo.Parear -> "Conectar Termux"
                        Passo.IniciarMotor -> "Iniciar motor"
                        else -> "Tentar de novo"
                    }
                    AuraButtonSecondary(label, onCheckTermux)
                }
            }

            if (state.testing && !state.ready) {
                Text("Testando a conexão sozinho…", style = AuraType.SecondarySmall,
                    modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
            }
        }
        Column(
            Modifier.padding(start = 20.dp, end = 20.dp, top = 8.dp, bottom = 16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            AuraButtonPrimary("Continuar", onContinue, Modifier.fillMaxWidth(), enabled = state.ready, large = true)
            AuraButtonText("Preciso de ajuda", onHelp)
            if (!state.ready) AuraButtonText("Pular por agora", onContinue) // sem beco sem saída
        }
    }
}

/** Cartão de passo: ponto de estado + texto (Feito/Pendente), conteúdo extra opcional. */
@Composable
private fun SetupStep(
    title: String,
    done: Boolean,
    modifier: Modifier = Modifier,
    number: Int? = null,
    doneLabel: String = "Feito",
    hint: String? = null,
    extra: @Composable () -> Unit = {},
) {
    AuraCard(modifier.fillMaxWidth(), contentDescription = null) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            if (number != null && !done) {
                Box(
                    Modifier.size(28.dp).clip(AuraShapes.Circle).background(AuraColors.SurfaceSunken)
                        .clearAndSetSemantics { },
                    contentAlignment = Alignment.Center,
                ) { Text("$number", style = AuraType.Chip) }
            }
            Text(title, style = AuraType.BodyStrong, modifier = Modifier.weight(1f))
            if (done) StatusChip(StatusKind.Ready, doneLabel)
            else StatusChip(StatusKind.Neutral, "Pendente")
        }
        if (hint != null) Text(hint, style = AuraType.SecondarySmall)
        Spacer(Modifier.height(4.dp))
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) { extra() }
    }
}

// ---------------------------------------------------------------- 3. Contas

@Composable
fun OnboardingAccountsScreen(
    accounts: List<AccountState>,
    onSignIn: (AccountId) -> Unit,
    onInstall: (AccountId) -> Unit,
    onFinish: () -> Unit,
    modifier: Modifier = Modifier,
    promptSlot: @Composable () -> Unit = {},
) {
    val hasBrain = accounts.any { it.connected && it.id.isBrain }
    Column(modifier.fillMaxSize().background(AuraColors.Bg).statusBarsPadding().navigationBarsPadding()) {
        Column(
            Modifier.weight(1f).verticalScroll(rememberScrollState())
                .padding(horizontal = AuraSpacing.ScreenGutter, vertical = AuraSpacing.S6),
            verticalArrangement = Arrangement.spacedBy(AuraSpacing.S3),
        ) {
            Text("PASSO 3 DE 3", style = AuraType.Caption)
            Text("Conectar suas contas", style = AuraType.TabTitle, modifier = Modifier.semantics { heading() })
            Text("O login roda dentro do Termux. Você só copia um código e abre um link. O AURA nunca vê sua senha.", style = AuraType.Secondary)
            Spacer(Modifier.height(4.dp))
            promptSlot()
            AccountId.entries.forEach { id ->
                AccountRow(accounts.firstOrNull { it.id == id } ?: AccountState(id), onSignIn, onInstall)
            }
            Text(
                if (hasBrain) "Sem API paga: o AURA usa só as suas assinaturas."
                else "Precisa de pelo menos um cérebro: Claude, Codex ou Gemini.",
                style = AuraType.SecondarySmall,
            )
        }
        Column(
            Modifier.padding(start = 20.dp, end = 20.dp, top = 8.dp, bottom = 16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            AuraButtonPrimary("Entrar no AURA", onFinish, Modifier.fillMaxWidth(), enabled = hasBrain, large = true)
            AuraButtonText(if (hasBrain) "Conectar o resto depois" else "Pular por agora", onFinish)
        }
    }
}

@Composable
private fun AccountRow(account: AccountState, onSignIn: (AccountId) -> Unit, onInstall: (AccountId) -> Unit) {
    val id = account.id
    val unavailable = account.state == AuthState.Unavailable
    val subtitle = when {
        account.connected -> account.detail?.let { "Conectado · $it" } ?: "Conectado"
        unavailable && !account.detail.isNullOrBlank() -> account.detail.orEmpty()
        else -> id.subtitle
    }
    AuraCard(Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            GlyphTile(id.label.take(1), when (id) {
                AccountId.Claude -> AuraColors.CategoryApricot
                AccountId.Codex -> AuraColors.CategoryAndroid
                AccountId.Google -> AuraColors.CategoryEngineering
                AccountId.GitHub -> AuraColors.CategoryLilac
            }, size = 44.dp)
            Column(Modifier.weight(1f)) {
                Text(id.label, style = AuraType.BodyStrong)
                Text(subtitle, style = AuraType.SecondarySmall)
            }
            when {
                account.connected -> StatusChip(StatusKind.Ready, "Conectado")
                account.connecting -> StatusChip(StatusKind.Info, "Aguardando")
                unavailable && account.installable -> AuraButtonSecondary("Instalar", { onInstall(id) },
                    contentDescription = "Instalar o programa de ${id.label}")
                unavailable -> StatusChip(StatusKind.Neutral, "Indisponível")
                else -> AuraButtonSecondary(if (account.state == AuthState.Expired) "Reconectar" else "Conectar", { onSignIn(id) },
                    contentDescription = "Conectar ${id.label}")
            }
        }
        if (account.connecting) {
            Text("Siga os passos no cartão acima e volte ao AURA.", style = AuraType.SecondarySmall)
        }
    }
}

// ---------------------------------------------------------------- Previews

@Preview(showBackground = true, backgroundColor = 0xFFF5F4F1, widthDp = 390, heightDp = 844)
@Composable
private fun WelcomePreview() { AuraTheme { OnboardingWelcomeScreen({}) } }

@Preview(showBackground = true, backgroundColor = 0xFFF5F4F1, widthDp = 390, heightDp = 844)
@Composable
private fun TermuxPreview() {
    AuraTheme {
        OnboardingTermuxScreen(
            TermuxSetupState(termuxFound = true, permissionGranted = true, testing = true), {}, {}, {}, {},
        )
    }
}

@Preview(showBackground = true, backgroundColor = 0xFFF5F4F1, widthDp = 390, heightDp = 844)
@Composable
private fun TermuxMissingPreview() {
    AuraTheme { OnboardingTermuxScreen(TermuxSetupState(), {}, {}, {}, {}) }
}

@Preview(showBackground = true, backgroundColor = 0xFFF5F4F1, widthDp = 390, heightDp = 844)
@Composable
private fun AccountsPreview() {
    AuraTheme {
        OnboardingAccountsScreen(
            listOf(
                AccountState(AccountId.Claude, connected = true, detail = "assinatura Max"),
                AccountState(AccountId.Codex), AccountState(AccountId.Google, connecting = true),
                AccountState(AccountId.GitHub),
            ), {}, {}, {},
        )
    }
}
