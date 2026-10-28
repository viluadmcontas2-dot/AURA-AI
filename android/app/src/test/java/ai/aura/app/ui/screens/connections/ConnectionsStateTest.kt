package ai.aura.app.ui.screens.connections

import ai.aura.app.integration.SetupStatus
import ai.aura.app.offline.LinkState
import ai.aura.app.offline.OfflineReason
import ai.aura.app.ui.engine.AuthState
import ai.aura.app.ui.engine.AuthStatus
import ai.aura.app.ui.screens.onboarding.TermuxSetupState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** A tabela de estados de Conexões: cada situação real tem um chip, uma frase e UMA ação principal. */
class ConnectionsStateTest {
    private fun plano(link: LinkState, signal: ClaudeSignal = ClaudeSignal.Unknown, termux: TermuxSetupState? = null) =
        connectionsPlan(link, termux, signal)

    @Test fun `sem Termux, sem permissao e sem vinculo cada um tem o chip e a acao do passo`() {
        plano(LinkState.Offline(OfflineReason.NoTermux)).let {
            assertEquals("Sem Termux", it.assistant.chip); assertEquals(ConnectionAction.InstallTermux, it.action)
        }
        plano(LinkState.Offline(OfflineReason.NoPermission)).let {
            assertEquals("Sem permissão", it.assistant.chip); assertEquals(ConnectionAction.AllowTermux, it.action)
        }
        plano(LinkState.Offline(OfflineReason.NoToken)).let {
            assertEquals("Sem vínculo", it.assistant.chip); assertEquals(ConnectionAction.PairTermux, it.action)
            assertEquals("Falta conectar o Termux ao AURA.", it.assistant.hint)
        }
    }

    @Test fun `motor nunca preparado e Nao instalado e Preparar motor, motor so parado e Iniciar o motor`() {
        val nunca = TermuxSetupState(engineInstalled = false)
        plano(LinkState.Offline(OfflineReason.EngineStopped), termux = nunca).let {
            assertEquals("Não instalado", it.assistant.chip)
            assertEquals(ConnectionAction.PrepareEngine, it.action)
            assertTrue(it.assistant.hint.contains("peço a sua confirmação"))
        }
        val parado = TermuxSetupState(engineInstalled = true)
        plano(LinkState.Offline(OfflineReason.EngineStopped), termux = parado).let {
            assertEquals("Parado", it.assistant.chip); assertEquals(ConnectionAction.StartEngine, it.action)
        }
        // Sem saber (nenhum fluxo): não finge que falta instalar.
        assertEquals(ConnectionAction.StartEngine, plano(LinkState.Unknown).action)
        assertEquals(ConnectionAction.StartEngine, plano(LinkState.Offline(OfflineReason.EngineStopped), termux = null).action)
    }

    @Test fun `iniciando nao oferece acao nenhuma`() {
        plano(LinkState.Starting).let { assertNull(it.action); assertEquals("Iniciando", it.assistant.chip) }
    }

    @Test fun `ligado o proximo passo e a conta do Claude e so Pronto depois da verificacao`() {
        plano(LinkState.Online, ClaudeSignal.NotInstalled).let {
            assertEquals(ConnectionAction.InstallClaude, it.action); assertFalse(it.actionInAssistant); assertFalse(it.ready)
            assertEquals("Não instalado", it.claude.chip)
        }
        plano(LinkState.Online, ClaudeSignal.SignedOut).let {
            assertEquals(ConnectionAction.SignInClaude, it.action); assertEquals("Precisa entrar na conta", it.claude.chip)
        }
        plano(LinkState.Online, ClaudeSignal.Expired).let {
            assertEquals(ConnectionAction.SignInAgain, it.action); assertEquals("A entrada expirou.", it.claude.hint)
        }
        plano(LinkState.Online, ClaudeSignal.Unverified).let {
            assertEquals(ConnectionAction.CheckSignIn, it.action)
            assertEquals("Não consegui confirmar a entrada.", it.claude.hint)
            assertFalse("sem verificação não é Pronto", it.ready)
        }
        plano(LinkState.Online, ClaudeSignal.SigningIn).let {
            assertNull(it.action); assertEquals("Verificando entrada…", it.claude.hint); assertFalse(it.ready)
        }
        plano(LinkState.Online, ClaudeSignal.Unknown).let { assertNull(it.action); assertFalse(it.ready) }
        plano(LinkState.Online, ClaudeSignal.Verified).let {
            assertNull(it.action); assertTrue(it.ready); assertEquals("Pronto", it.assistant.chip)
        }
    }

    @Test fun `no maximo uma acao principal e Pronto so existe com motor ligado e conta verificada`() {
        val links = listOf(
            LinkState.Unknown, LinkState.Starting, LinkState.Online,
            LinkState.Offline(OfflineReason.NoTermux), LinkState.Offline(OfflineReason.NoPermission),
            LinkState.Offline(OfflineReason.NoToken), LinkState.Offline(OfflineReason.EngineStopped),
        )
        for (link in links) for (signal in ClaudeSignal.entries) for (termux in listOf(null, TermuxSetupState(engineInstalled = false))) {
            val p = plano(link, signal, termux)
            assertEquals("$link/$signal", link == LinkState.Online && signal == ClaudeSignal.Verified, p.ready)
            if (!p.ready && link != LinkState.Starting && !(link == LinkState.Online && signal in setOf(ClaudeSignal.Unknown, ClaudeSignal.SigningIn))) {
                assertTrue("sempre há um caminho: $link/$signal", p.action != null)
            }
            if (link != LinkState.Online) assertEquals("Indisponível", p.claude.chip) // sem motor, a conta é desconhecida
        }
    }

    @Test fun `o sinal do Claude so e verificado quando o motor confirma e falta de resposta nao vira desconectado`() {
        fun auth(state: AuthState, detail: String? = null) = AuthStatus("claude", state, detail = detail)
        assertEquals(ClaudeSignal.Verified, claudeSignal(auth(AuthState.Connected), null, false))
        assertEquals(ClaudeSignal.Verified, claudeSignal(null, SetupStatus("installed", "signed_in", "none"), false))
        assertEquals(ClaudeSignal.SignedOut, claudeSignal(auth(AuthState.Disconnected), null, false))
        assertEquals(ClaudeSignal.Expired, claudeSignal(auth(AuthState.Expired), null, false))
        assertEquals(ClaudeSignal.NotInstalled, claudeSignal(auth(AuthState.Unavailable), null, false))
        assertEquals(ClaudeSignal.NotInstalled, claudeSignal(null, SetupStatus("missing", "unknown", "review_installation"), false))
        assertEquals(ClaudeSignal.Unverified, claudeSignal(auth(AuthState.Disconnected, "Sem resposta do programa."), null, false))
        assertEquals(ClaudeSignal.Unverified, claudeSignal(null, SetupStatus("installed", "unknown", "check_again"), false))
        assertEquals(ClaudeSignal.Unknown, claudeSignal(null, null, false))
        assertEquals(ClaudeSignal.SigningIn, claudeSignal(auth(AuthState.Connected), null, signingIn = true))
    }
}
