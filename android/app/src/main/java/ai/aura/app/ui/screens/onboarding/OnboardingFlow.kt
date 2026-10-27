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
) {
    val ready: Boolean get() = termuxFound && permissionGranted && externalAppsAllowed
}

enum class AccountId(val label: String, val subtitle: String, val isBrain: Boolean) {
    Claude("Claude Code", "Usa a sua assinatura do Claude", true),
    Codex("Codex (ChatGPT)", "Usa a sua assinatura do ChatGPT", true),
    Google("Google", "Gemini, Drive e Agenda", true),
    GitHub("GitHub", "Guardar e publicar código", false),
}

/** [connected] só vem de verificação real; [connecting] = navegador aberto, aguardando. */
data class AccountState(
    val id: AccountId,
    val connected: Boolean = false,
    val detail: String? = null,
    val connecting: Boolean = false,
)

fun defaultAccounts(): List<AccountState> = AccountId.entries.map { AccountState(it) }

/**
 * Onboarding em 3 telas por estado local: Boas-vindas, Conectar o Termux, Conectar contas.
 * Só UI + callbacks: a permissão RUN_COMMAND do Termux é pedida apenas por [onRequestTermuxPermission]
 * (toque explícito); login por navegador via [onSignIn]; nunca se pede token.
 */
@Composable
fun OnboardingFlow(
    termux: TermuxSetupState,
    accounts: List<AccountState>,
    onRequestTermuxPermission: () -> Unit,
    onOpenTermux: () -> Unit,
    onSignIn: (AccountId) -> Unit,
    onFinish: () -> Unit,
    modifier: Modifier = Modifier,
    onHelp: () -> Unit = {},
) {
    var step by rememberSaveable { mutableIntStateOf(0) }
    BackHandler(enabled = step > 0) { step -= 1 }
    when (step.coerceIn(0, 2)) {
        0 -> OnboardingWelcomeScreen(onStart = { step = 1 }, modifier = modifier)
        1 -> OnboardingTermuxScreen(
            state = termux, onRequestPermission = onRequestTermuxPermission, onOpenTermux = onOpenTermux,
            onContinue = { step = 2 }, onHelp = onHelp, modifier = modifier,
        )
        else -> OnboardingAccountsScreen(
            accounts = accounts, onSignIn = onSignIn, onFinish = onFinish, modifier = modifier,
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
) {
    val clipboard = LocalClipboardManager.current
    var copied by remember { mutableStateOf(false) }
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
                hint = if (state.termuxFound) null else "Instale o Termux (pela F-Droid) e volte aqui.")

            SetupStep(
                "Permitir que o AURA use o Termux", done = state.permissionGranted, doneLabel = "Permitido",
                hint = if (state.permissionGranted) null else "O Android vai pedir a sua permissão. Nada é liberado sem o seu toque.",
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
    onFinish: () -> Unit,
    modifier: Modifier = Modifier,
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
            Text("O login acontece no navegador. O AURA nunca vê sua senha.", style = AuraType.Secondary)
            Spacer(Modifier.height(4.dp))
            AccountId.entries.forEach { id ->
                AccountRow(accounts.firstOrNull { it.id == id } ?: AccountState(id), onSignIn)
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
            AuraButtonText("Conectar o resto depois", onFinish, enabled = hasBrain)
        }
    }
}

@Composable
private fun AccountRow(account: AccountState, onSignIn: (AccountId) -> Unit) {
    val id = account.id
    val subtitle = if (account.connected) (account.detail?.let { "Conectado · $it" } ?: "Conectado") else id.subtitle
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
                else -> AuraButtonSecondary("Entrar", { onSignIn(id) },
                    contentDescription = "Entrar no ${id.label} pelo navegador")
            }
        }
        if (account.connecting) {
            Text("Conclua o login no navegador e volte ao AURA.", style = AuraType.SecondarySmall)
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
            ), {}, {},
        )
    }
}
