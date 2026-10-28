package ai.aura.app.ui.screens.connections

import ai.aura.app.offline.LinkState
import ai.aura.app.ui.engine.Capability
import ai.aura.app.ui.engine.EngineEvent
import ai.aura.app.ui.engine.EngineGateway
import ai.aura.app.ui.engine.EngineResult
import ai.aura.app.ui.engine.Job
import ai.aura.app.ui.engine.JobRequest
import ai.aura.app.ui.engine.RouteResult
import ai.aura.app.ui.theme.AuraTheme
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.util.concurrent.CopyOnWriteArrayList

/** Motor de mentira só para a chave do Gemini: guarda o que recebeu e responde como o contrato. */
private class KeyGateway(@Volatile var configured: Boolean = false, @Volatile var down: Boolean = false) : EngineGateway {
    val saved = CopyOnWriteArrayList<String>()
    @Volatile var removed = 0

    override val events: Flow<EngineEvent> = emptyFlow()

    override suspend fun geminiKeyConfigured(): EngineResult<Boolean> =
        if (down) EngineResult.Unavailable("Termux não responde") else EngineResult.Ok(configured)

    override suspend fun saveGeminiKey(key: String): EngineResult<Unit> {
        if (down) return EngineResult.Unavailable("Termux não responde")
        saved += key
        configured = true
        return EngineResult.Ok(Unit)
    }

    override suspend fun removeGeminiKey(): EngineResult<Unit> {
        removed++
        configured = false
        return EngineResult.Ok(Unit)
    }

    override suspend fun submit(requestId: String, request: JobRequest): Job = error("não usado")
    override suspend fun jobs(): List<Job> = emptyList()
    override suspend fun approve(id: String): Job = error("não usado")
    override suspend fun cancel(id: String): Job = error("não usado")
    override suspend fun route(text: String): RouteResult = error("não usado")
    override suspend fun capabilities(): List<Capability> = emptyList()
}

/** Conexões > Melhorar pedidos (Gemini): salvar, mostrar só "Chave salva" e remover. A chave nunca aparece. */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w390dp-h844dp")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class GeminiKeyCardTest {
    @get:Rule val rule = createComposeRule()

    private fun show(gw: EngineGateway) {
        rule.setContent {
            AuraTheme {
                ConnectionsScreen(gw, LinkState.Online, onEngineAction = {}, onOpenUrl = {}, onBack = {}, authPollMillis = 50)
            }
        }
        // "Melhorar pedidos" é opcional e fica recolhido até a pessoa abrir.
        rule.onNodeWithText("Melhorar pedidos (opcional)").performScrollTo().performClick()
    }

    private fun has(text: String, substring: Boolean = false) =
        rule.onAllNodesWithText(text, substring = substring).fetchSemanticsNodes().isNotEmpty()

    private fun waitText(text: String, substring: Boolean = false) {
        val end = System.currentTimeMillis() + 10_000
        while (!has(text, substring)) {
            check(System.currentTimeMillis() < end) { "não apareceu: $text" }
            rule.waitForIdle()
            Thread.sleep(20)
        }
    }

    private fun gone(text: String) {
        val end = System.currentTimeMillis() + 10_000
        while (has(text)) {
            check(System.currentTimeMillis() < end) { "não sumiu: $text" }
            rule.waitForIdle()
            Thread.sleep(20)
        }
    }

    @Test fun `o cartao explica para que serve a chave e pede o campo Chave do Gemini`() {
        show(KeyGateway())
        waitText("Melhorar pedidos (Gemini)")
        assertTrue(has("Usa a sua chave do Google AI Studio. Só é usada quando você toca em Melhorar pedido."))
        assertTrue(has("Chave do Gemini"))
        rule.onNodeWithText("Salvar chave").performScrollTo()
        assertFalse("ainda não há chave salva", has("Chave salva"))
    }

    @Test fun `salvar chama o gateway, mostra Chave salva e a chave nunca aparece na tela`() {
        val gw = KeyGateway()
        show(gw)
        waitText("Salvar chave")

        rule.onNode(hasSetTextAction() and hasText("Chave do Gemini")).performScrollTo().performTextInput("AIza-segredo-123")
        assertFalse("a chave digitada fica escondida", has("AIza-segredo-123", substring = true))
        rule.onNodeWithText("Salvar chave").performScrollTo().performClick()

        rule.waitUntil(10_000) { gw.saved.isNotEmpty() }
        assertEquals(listOf("AIza-segredo-123"), gw.saved.toList())
        waitText("Chave salva")
        assertFalse("depois de salvar, a chave não aparece em lugar nenhum", has("AIza-segredo-123", substring = true))
        assertTrue(has("Remover chave"))
        assertFalse("salva: o campo some", has("Salvar chave"))
    }

    @Test fun `remover apaga a chave no motor e volta a pedir uma`() {
        val gw = KeyGateway(configured = true)
        show(gw)
        waitText("Chave salva")
        assertFalse(has("Salvar chave"))

        rule.onNodeWithText("Remover chave").performScrollTo().performClick()
        rule.waitUntil(10_000) { gw.removed == 1 }
        waitText("Salvar chave")
        gone("Chave salva")
    }

    @Test fun `se o motor esta desligado a chave nao e guardada, o campo e limpo e o aviso diz o que fazer`() {
        val gw = KeyGateway(down = true)
        show(gw)
        waitText("Salvar chave")

        rule.onNode(hasSetTextAction() and hasText("Chave do Gemini")).performScrollTo().performTextInput("AIza-primeira")
        rule.onNodeWithText("Salvar chave").performScrollTo().performClick()
        waitText("não salvei a chave", substring = true)
        assertTrue(gw.saved.isEmpty())
        assertFalse(has("AIza-primeira", substring = true))

        // O campo foi limpo ao enviar: a segunda tentativa leva só o que foi digitado de novo.
        gw.down = false
        rule.onNode(hasSetTextAction() and hasText("Chave do Gemini")).performScrollTo().performTextInput("AIza-segunda")
        rule.onNodeWithText("Salvar chave").performScrollTo().performClick()
        rule.waitUntil(10_000) { gw.saved.isNotEmpty() }
        assertEquals(listOf("AIza-segunda"), gw.saved.toList())
    }
}
