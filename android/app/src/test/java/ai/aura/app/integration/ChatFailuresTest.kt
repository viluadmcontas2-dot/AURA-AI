package ai.aura.app.integration

import ai.aura.app.ui.engine.ChatAction
import org.junit.Assert.assertEquals
import org.junit.Test

/** Cada código de erro de conversa do motor leva ao botão certo (por código, não por pedaço do texto). */
class ChatFailuresTest {
    @Test
    fun `no_executor cli_missing e not_logged_in levam a Abrir Conexoes com a frase do motor`() {
        listOf(
            "no_executor" to "Nenhum assistente está instalado neste aparelho.",
            "cli_missing" to "O Claude não está instalado neste aparelho.",
            "not_logged_in" to "O Claude não está conectado à sua conta.",
        ).forEach { (code, msg) ->
            val f = chatFailureFor(code, msg)
            assertEquals(code, ChatAction.OpenConnections, f.action)
            assertEquals(msg, f.message)
            assertEquals(code, f.code)
        }
    }

    @Test
    fun `timeout network interrupted e failed levam a Tentar de novo`() {
        listOf("timeout", "network", "interrupted", "failed").forEach { code ->
            val f = chatFailureFor(code, "Frase do motor em português.")
            assertEquals(code, ChatAction.Retry, f.action)
            assertEquals("Frase do motor em português.", f.message)
        }
    }

    @Test
    fun `stopped fica calmo e so tenta de novo`() {
        val f = chatFailureFor("stopped", null)
        assertEquals(ChatAction.Retry, f.action)
        assertEquals("Resposta interrompida.", f.message)
    }

    @Test
    fun `codigo parecido mas fora da lista nao abre Conexoes`() {
        assertEquals(ChatAction.Retry, chatFailureFor("auth_timeout", null).action)
        assertEquals(ChatAction.Retry, chatFailureFor("missing_field", null).action)
        assertEquals(ChatAction.Retry, chatFailureFor(null, null).action)
    }

    @Test
    fun `engine_down inicia o motor`() {
        assertEquals(ChatAction.StartEngine, chatFailureFor("engine_down", null).action)
    }
}
