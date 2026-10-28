package ai.aura.app.ui.screens.settings

import ai.aura.app.ui.engine.AuthState
import ai.aura.app.ui.engine.AuthStatus
import ai.aura.app.ui.engine.Capability
import ai.aura.app.ui.engine.EngineEvent
import ai.aura.app.ui.engine.EngineGateway
import ai.aura.app.ui.engine.Job
import ai.aura.app.ui.engine.JobRequest
import ai.aura.app.ui.engine.RouteResult
import ai.aura.app.ui.engine.ToolActionResult
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

private class InstallGateway(val install: CompletableDeferred<ToolActionResult> = CompletableDeferred()) : EngineGateway {
    override val events: Flow<EngineEvent> = emptyFlow()
    val installs = mutableListOf<String>()
    val statusReads = AtomicInteger(0)
    override suspend fun authStatus(): List<AuthStatus> {
        statusReads.incrementAndGet()
        return listOf(AuthStatus("claude", AuthState.Disconnected))
    }
    override suspend fun installTool(provider: String): ToolActionResult {
        installs += provider
        return install.await()
    }
    override suspend fun jobs(): List<Job> = emptyList()
    override suspend fun approve(id: String): Job = error("não usado")
    override suspend fun cancel(id: String): Job = error("não usado")
    override suspend fun route(text: String): RouteResult = error("não usado")
    override suspend fun submit(requestId: String, request: JobRequest): Job = error("não usado")
    override suspend fun capabilities(): List<Capability> = emptyList()
}

private fun toolEvent(state: String) = EngineEvent(1, "tool.install", "job_1", mapOf("state" to state, "name" to "claude"), 0L)

/** Contas: o que o motor emite em tool.install (installing, ready, failed, waiting_wifi) e o toque duplo no Instalar. */
class AccountsInstallTest {
    @Test fun `ready recarrega o estado das contas e waiting_wifi avisa do Wi-Fi`() = runTest {
        val gw = InstallGateway()
        val auth = AccountsAuthState(gw, backgroundScope)

        auth.onEvent(toolEvent("ready"))
        assertEquals(1, gw.statusReads.get())

        auth.onEvent(toolEvent("waiting_wifi"))
        assertTrue(auth.notice!!.text.startsWith("Aguardando Wi-Fi"))

        auth.onEvent(toolEvent("failed"))
        assertEquals(2, gw.statusReads.get())

        auth.onEvent(toolEvent("installing")) // andamento: não recarrega nem some com o aviso
        assertEquals(2, gw.statusReads.get())
    }

    @Test fun `toque duplo em Instalar so instala uma vez e o resultado aparece ao fim`() = runTest {
        val gw = InstallGateway()
        val auth = AccountsAuthState(gw, backgroundScope)

        auth.install("claude")
        auth.install("claude") // segundo toque enquanto instala
        runCurrent()

        assertEquals(listOf("claude"), gw.installs)
        assertEquals("claude", auth.installing)
        assertTrue(auth.notice!!.text.contains("Instalando"))

        gw.install.complete(ToolActionResult.Ok("Instalado. Agora toque em Conectar."))
        runCurrent()

        assertNull(auth.installing)
        assertTrue(auth.notice!!.ok)
        assertTrue(auth.notice!!.text.contains("Instalado"))
        assertFalse(auth.notice!!.text.contains("Atividade"))
        auth.install("claude") // depois de acabar, pode instalar de novo
        runCurrent()
        assertEquals(2, gw.installs.size)
    }

    @Test fun `falha na instalacao aparece em portugues com o que fazer`() = runTest {
        val gw = InstallGateway()
        val auth = AccountsAuthState(gw, backgroundScope)
        auth.install("claude"); runCurrent()
        gw.install.complete(ToolActionResult.Failed("Não consegui instalar este programa.", "Confira a internet e toque em Instalar de novo."))
        runCurrent()
        assertFalse(auth.notice!!.ok)
        assertEquals("Não consegui instalar este programa. Confira a internet e toque em Instalar de novo.", auth.notice!!.text)
        assertNull(auth.installing)
    }
}
