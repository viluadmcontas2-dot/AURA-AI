package ai.aura.app.ui.screens.reform

import ai.aura.app.ui.AuraTab
import ai.aura.app.ui.nav.ScreenEnv
import ai.aura.app.ui.nav.routeHasScreen
import ai.aura.app.ui.screens.onboarding.LocalTermuxFlow
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.foundation.layout.fillMaxSize
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import kotlinx.coroutines.CancellationException

/**
 * Corpo das rotas `reformar-*` (a lista delas e os destinos ficam em ui/nav/Routes.kt).
 * Fluxo: Apps (escolher APK) -> Escopo -> Reversão -> Projeto -> Fidelidade; Ferramentas é a página de apoio
 * (o motor instala sozinho no passo "tools" ao começar; aqui o dono vê o que falta e toca em Instalar).
 * Nenhuma delas inventa número: o que o motor não informa aparece como "O motor ainda não informa".
 */

/** Pedido no chat do Início: o rascunho recebe a frase e o dono só confirma. Caminho de reserva sem endpoint. */
private fun requestInChat(env: ScreenEnv, text: String) {
    env.onDraftChange(if (env.draft.isBlank()) text else env.draft + "\n" + text)
    env.openTab(AuraTab.Home)
}

/** "Iniciar o motor": mesmo caminho do onboarding (TermuxFlow); sem ele, abre o Termux. */
private fun startEngine(flow: ai.aura.app.ui.screens.onboarding.TermuxFlow?, env: ScreenEnv) {
    if (flow != null) flow.startEngine() else env.onOpenTermux()
}

/** Reformar 1 · Escolher APK. Abre mesmo sem motor: só o aviso "Iniciar o motor" aparece junto do seletor. */
@Composable
fun ReformAppsRoute(env: ScreenEnv) {
    val reform = LocalReformController.current
    val flow = LocalTermuxFlow.current
    var attempt by remember { mutableIntStateOf(0) }
    var engineDown by remember { mutableStateOf(false) }
    LaunchedEffect(env.gateway, attempt) {
        engineDown = try {
            env.gateway.capabilities(); false
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            true
        }
    }
    // Voltou do Termux: reconfere o motor.
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_RESUME) attempt++ }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    val problem = if (engineDown) {
        ReformProblem(
            "O motor está desligado",
            "Você já pode escolher o APK. Para começar a reforma, abra o Termux e inicie o motor.",
            ReformFix.StartEngine,
        )
    } else null
    ReformAppsScreen(
        ReformAppsState(problem = problem), env.back,
        onNext = { app -> reform?.select(app); env.open("reformar-escopo") },
        onFix = { fix ->
            when (fix) {
                ReformFix.StartEngine -> startEngine(flow, env)
                ReformFix.Retry -> attempt++
                else -> env.open("reformar-ferramentas")
            }
        },
        modifier = Modifier.fillMaxSize(),
    )
}

/** Reformar 2 · Escopo. "Travar escopo e começar" copia o APK, trava o escopo no motor, abre a Reversão e já segue. */
@Composable
fun ReformScopeRoute(env: ScreenEnv) {
    val reform = LocalReformController.current
    val flow = LocalTermuxFlow.current
    val app = reform?.selectedApp?.collectAsState()?.value
    val ui = reform?.scopeUi?.collectAsState()?.value ?: ReformScopeUi()
    ReformScopeScreen(
        app, env.back,
        onStart = { plan ->
            reform?.lockScope(plan) {
                env.open("reformar-reversao")
                reform?.continueReform()
            }
        },
        modifier = Modifier.fillMaxSize(), busy = ui.busy, error = ui.error,
        onStartEngine = if (ui.engineDown) ({ startEngine(flow, env) }) else null,
    )
}

/** Reformar 3 · Reversão. */
@Composable
fun ReformReversionRoute(env: ScreenEnv) {
    val reform = LocalReformController.current
    LaunchedEffect(reform) { reform?.refreshProgress() }
    val progress = reform?.progress?.collectAsState()?.value ?: ReformProgress()
    ReformReversionScreen(
        progress, System.currentTimeMillis(), env.back,
        onPause = { reform?.pause() }, onResume = { reform?.continueReform() },
        // Sem tela de bastidores ainda: o botão some em vez de ficar mudo.
        onOpenBackstage = if (routeHasScreen("bastidores")) ({ env.open("bastidores") }) else null,
        onOpenProject = { env.open("reformar-projeto") },
        onChooseApp = { env.open("reformar-apps") }, onRetry = { reform?.refreshProgress() },
        modifier = Modifier.fillMaxSize(),
    )
}

/** Reformar 6 · Projeto (reconstrução). Artefatos, peças e estilo ainda não vêm do motor: a tela diz isso. */
@Composable
fun ReformProjectRoute(env: ScreenEnv) {
    val reform = LocalReformController.current
    LaunchedEffect(reform) { reform?.refreshProgress() }
    val progress = reform?.progress?.collectAsState()?.value ?: ReformProgress()
    ReformProjectScreen(
        progress, emptyList(), null, null, null, env.back,
        onOpenFidelity = { env.open("reformar-fidelidade") }, onOpenArtifact = {}, onChangeStyle = { env.open("reformar-escopo") },
        onOpenTools = { env.open("reformar-ferramentas") }, onContinue = { reform?.continueReform() },
        onChooseApp = { env.open("reformar-apps") }, onRetry = { reform?.refreshProgress() },
        modifier = Modifier.fillMaxSize(),
    )
}

/**
 * Reformar 4 · Fidelidade. O número só nasce de um teste real (POST /verify, que o app ainda não roda): "Testar"
 * relê o motor em vez de inventar. "Instalar ao lado do original" ainda não tem endpoint: vira pedido no chat.
 */
@Composable
fun ReformFidelityRoute(env: ScreenEnv) {
    val reform = LocalReformController.current
    LaunchedEffect(reform) { reform?.refreshProgress() }
    val progress = reform?.progress?.collectAsState()?.value ?: ReformProgress()
    ReformFidelityScreen(
        progress, ReformFidelityDetails(), env.back,
        onRunTest = { reform?.refreshProgress() },
        onInstall = { requestInChat(env, "Instalar o app reformado ao lado do original") },
        onFixDifferences = { reform?.continueReform() }, onChooseApp = { env.open("reformar-apps") },
        onRetry = { reform?.refreshProgress() },
        modifier = Modifier.fillMaxSize(),
    )
}

/**
 * Reformar 5 · Ferramentas (REA, JADX, Apktool, adb...). "Instalar o que falta" roda o passo `tools` da reforma atual
 * (o motor resolve Wi-Fi x dados móveis).
 * TODO(endpoint de instalação sem reforma): o passo mora num projeto de reforma, que nasce no Escopo. Quando o motor
 * tiver uma rota própria de instalação, troque SÓ [installWithoutReform] abaixo; hoje cai no pedido no chat.
 */
@Composable
fun ReformToolsRoute(env: ScreenEnv) {
    val reform = LocalReformController.current
    val flow = LocalTermuxFlow.current
    val state = reform?.tools?.collectAsState()?.value ?: ReformToolsState()
    val installWithoutReform = {
        requestInChat(env, "Instalar as ferramentas da reforma de apps que faltam no Termux")
    }
    val instalar = { if (reform?.installTools() != true) installWithoutReform() }
    ReformToolsScreen(
        state, env.back,
        onInstall = instalar, onResume = instalar,
        onFix = { fix -> if (fix == ReformFix.StartEngine) startEngine(flow, env) else instalar() },
        modifier = Modifier.fillMaxSize(),
    )
}
