package ai.aura.app.ui.screens.connections

import ai.aura.app.offline.LinkState
import ai.aura.app.ui.engine.AUTH_PROVIDER_IDS
import ai.aura.app.ui.engine.AuthStartResult
import ai.aura.app.ui.engine.AuthState
import ai.aura.app.ui.engine.AuthStatus
import ai.aura.app.ui.engine.Capability
import ai.aura.app.ui.engine.EngineEvent
import ai.aura.app.ui.engine.EngineGateway
import ai.aura.app.ui.engine.Job
import ai.aura.app.ui.engine.JobRequest
import ai.aura.app.ui.engine.RouteResult
import ai.aura.app.ui.engine.ToolActionResult
import ai.aura.app.ui.theme.AuraTheme
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Motor de mentira só para o login: guarda o que o app pediu e responde como o contrato (docs/ENGINE_CONTRACT.md, Login). */
private class LoginGateway(
    private val initial: AuthState = AuthState.Disconnected,
    private val installable: Boolean = false,
    private val startResult: AuthStartResult = AuthStartResult.Started,
    private val promptCode: String? = "ABCD-1234",
    private val installResult: ToolActionResult = ToolActionResult.Ok("Instalando. Acompanhe em Atividade."),
) : EngineGateway {
    val bus = MutableSharedFlow<EngineEvent>(extraBufferCapacity = 16)
    override val events: Flow<EngineEvent> = bus
    val started = mutableListOf<String>()
    val installed = mutableListOf<String>()
    val codes = mutableListOf<String>()
    val tokens = mutableListOf<String>()

    /** O dono terminou o login no Termux: a próxima conferência de estado devolve Conectado. */
    @Volatile var loggedIn = false

    override suspend fun authStatus(): List<AuthStatus> = AUTH_PROVIDER_IDS.map { id ->
        when {
            id == "claude" && loggedIn -> AuthStatus(id, AuthState.Connected)
            id == "claude" -> AuthStatus(id, initial, installable = installable && initial == AuthState.Unavailable)
            else -> AuthStatus(id, AuthState.Disconnected)
        }
    }

    override suspend fun startAuth(provider: String): AuthStartResult {
        started += provider
        if (startResult == AuthStartResult.Started) {
            bus.tryEmit(
                EngineEvent(
                    1, "auth.prompt", null,
                    mapOf("provider" to provider, "url" to "https://example.com/device") + (promptCode?.let { mapOf("code" to it) } ?: emptyMap()), 0L,
                ),
            )
        }
        return startResult
    }

    override suspend fun installTool(provider: String): ToolActionResult {
        installed += provider
        return installResult
    }

    override suspend fun submitAuthCode(provider: String, code: String): ToolActionResult {
        codes += code
        loggedIn = true
        return ToolActionResult.Ok("Código enviado. Conferindo o login…")
    }

    override suspend fun submitAuthToken(provider: String, token: String): ToolActionResult {
        tokens += token
        loggedIn = true
        return ToolActionResult.Ok("Conectado.")
    }

    override suspend fun submit(requestId: String, request: JobRequest): Job = error("não usado")
    override suspend fun jobs(): List<Job> = emptyList()
    override suspend fun approve(id: String): Job = error("não usado")
    override suspend fun cancel(id: String): Job = error("não usado")
    override suspend fun route(text: String): RouteResult = error("não usado")
    override suspend fun capabilities(): List<Capability> = emptyList()
}

/**
 * Conexões > Contas: tocar em Conectar chama o motor (não o navegador), mostra o link e o código que ele devolveu
 * e acompanha o estado até "Conectado". Clica nos botões reais, na JVM.
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w390dp-h844dp")
class ConnectionsLoginTest {
    @get:Rule val rule = createComposeRule()

    private fun show(gateway: EngineGateway, opened: MutableList<String> = mutableListOf()) {
        rule.setContent {
            AuraTheme {
                ConnectionsScreen(
                    gateway, LinkState.Online, onEngineAction = {}, onOpenUrl = { opened += it }, onBack = {},
                    authPollMillis = 50,
                )
            }
        }
    }

    private fun waitForText(text: String) {
        rule.waitUntil(5_000) { rule.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty() }
    }

    @Test
    fun conectarPedeAoMotorMostraCodigoELinkECompletaQuandoOEstadoViraConectado() {
        val gateway = LoginGateway()
        val opened = mutableListOf<String>()
        show(gateway, opened)
        waitForText("Conectar Claude")

        rule.onNodeWithText("Conectar Claude").performScrollTo().performClick()
        rule.waitUntil(5_000) { gateway.started == listOf("claude") }
        assertTrue("o login não pode abrir o navegador sozinho", opened.isEmpty())

        // O código e o link são os que o motor devolveu.
        waitForText("ABCD-1234")
        rule.onNodeWithText("Abra o link e digite o código.").assertIsDisplayed()
        rule.onNodeWithText("Copiar código").performScrollTo().performClick()
        rule.onNodeWithText("Código copiado").assertIsDisplayed()
        val clipboard = ApplicationProvider.getApplicationContext<Context>()
            .getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        assertEquals("ABCD-1234", clipboard.primaryClip?.getItemAt(0)?.text?.toString())
        rule.onNodeWithText("Abrir link").performScrollTo().performClick()
        assertEquals(listOf("https://example.com/device"), opened)

        // O dono termina no navegador; a conferência de estado (plano B do evento) vira Conectado.
        gateway.loggedIn = true
        waitForText("Claude conectado.")
        rule.onAllNodesWithText("ABCD-1234").assertCountEquals(0)
    }

    @Test
    fun semMotorOErroDizOQueFazerEOAppContinuaAberto() {
        val reason = "O Termux não respondeu."
        val fix = "Abra o Termux, inicie o motor e toque em Conectar novamente."
        val gateway = LoginGateway(startResult = AuthStartResult.Failed(reason, fix))
        show(gateway)
        waitForText("Conectar Claude")

        rule.onNodeWithText("Conectar Claude").performScrollTo().performClick()
        waitForText("$reason $fix")
        // Nada travou: os botões seguem ali para tentar de novo.
        rule.onNodeWithText("Conectar Claude").performScrollTo().assertIsDisplayed()
    }

    @Test
    fun programaAusenteOferecaInstalarEPedeAoMotor() {
        val gateway = LoginGateway(initial = AuthState.Unavailable, installable = true)
        show(gateway)
        waitForText("Instalar Claude")

        rule.onNodeWithText("Não instalado").assertIsDisplayed()
        rule.onNodeWithText("Instalar Claude").performScrollTo().performClick()
        waitForText("Claude: Instalando. Acompanhe em Atividade.")
        assertEquals(listOf("claude"), gateway.installed)
    }

    @Test
    fun programaQueNaoInstalaNoTermuxExplicaEDeixaTentarDeNovo() {
        val gateway = LoginGateway(
            initial = AuthState.Unavailable, installable = true,
            installResult = ToolActionResult.Failed("Este programa não instala no Termux.", "Use outra conta por enquanto."),
        )
        show(gateway)
        waitForText("Instalar Claude")
        rule.onNodeWithText("Instalar Claude").performScrollTo().performClick()
        waitForText("Este programa não instala no Termux. Use outra conta por enquanto.")
        rule.onNodeWithText("Instalar Claude").performScrollTo().assertIsDisplayed()
    }

    @Test
    fun claudeAbreOLinkEAceitaOCodigoColado() {
        val gateway = LoginGateway(promptCode = null)
        val opened = mutableListOf<String>()
        show(gateway, opened)
        waitForText("Conectar Claude")
        rule.onNodeWithText("Conectar Claude").performScrollTo().performClick()
        waitForText("Abra o link, entre com a sua conta e cole aqui o código que aparecer.")
        rule.onNodeWithText("Abrir link").performScrollTo().performClick()
        assertEquals(listOf("https://example.com/device"), opened)

        rule.onNode(hasSetTextAction() and hasText("Código do Claude")).performScrollTo().performTextInput("COD-123")
        rule.onNodeWithText("Enviar código").performScrollTo().performClick()
        waitForText("Claude conectado.")
        assertEquals(listOf("COD-123"), gateway.codes)
    }

    @Test
    fun claudeAceitaTokenColadoENuncaOMostraDepois() {
        val gateway = LoginGateway(promptCode = null)
        show(gateway)
        waitForText("Conectar Claude")
        rule.onNodeWithText("Conectar Claude").performScrollTo().performClick()
        waitForText("Prefiro colar um token")
        rule.onNodeWithText("Prefiro colar um token").performScrollTo().performClick()
        rule.onNode(hasSetTextAction() and hasText("Token do Claude")).performScrollTo().performTextInput("sk-segredo")
        rule.onNodeWithText("Enviar token").performScrollTo().performClick()
        waitForText("Claude conectado.")
        assertEquals(listOf("sk-segredo"), gateway.tokens)
        rule.onAllNodesWithText("sk-segredo").assertCountEquals(0)
    }
}
