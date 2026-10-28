package ai.aura.app.ui.screens.settings

import ai.aura.app.ui.engine.AuthState
import ai.aura.app.ui.engine.AuthStatus
import ai.aura.app.ui.engine.Capability
import ai.aura.app.ui.engine.EngineEvent
import ai.aura.app.ui.engine.EngineGateway
import ai.aura.app.ui.engine.Job
import ai.aura.app.ui.engine.JobRequest
import ai.aura.app.ui.engine.RouteResult
import ai.aura.app.integration.PlanResult
import ai.aura.app.ui.engine.ToolActionResult
import ai.aura.app.ui.screens.connections.FakeSetupGateway
import ai.aura.app.ui.screens.connections.InstallConsentGate
import ai.aura.app.ui.screens.connections.InstallText
import ai.aura.app.ui.screens.connections.fakePlan
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.test.advanceTimeBy
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

/** Contas: o que o motor emite em tool.install (installing, ready, failed, waiting_wifi) e o Instalar sempre passando pelo consentimento. */
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

    @Test fun `Instalar nunca instala direto, pede o plano e espera a decisao`() = runTest {
        val gw = InstallGateway()
        val setup = FakeSetupGateway()
        val gate = InstallConsentGate { 1_000L }
        val auth = AccountsAuthState(gw, backgroundScope, setup = setup, consent = gate, installPollMillis = 1_000)

        auth.install("claude")
        auth.install("claude") // segundo toque enquanto o pedido está na tela
        runCurrent()

        assertEquals(emptyList<String>(), gw.installs)            // o caminho antigo nunca é usado
        assertEquals(listOf(Triple("claude", null, null)), setup.created) // um plano só
        assertEquals(emptyList<String>(), setup.decisions)        // zero instalação antes do aceite
        assertEquals("claude", auth.installing)
        assertTrue(gate.pending.value != null)

        val plano = gate.pending.value!!.plan
        gate.accept(plano.planId, plano.manifestHash); runCurrent()
        assertEquals(1, setup.decisions.size)
        advanceTimeBy(1_500); runCurrent()

        assertNull(auth.installing)
        assertTrue(auth.notice!!.ok)
        assertEquals("Claude instalado. Agora toque em Entrar no Claude.", auth.notice!!.text)
        auth.install("claude") // depois de acabar, pode pedir de novo
        runCurrent()
        assertEquals(2, setup.created.size)
    }

    @Test fun `recusar libera o botao e nada foi instalado`() = runTest {
        val gw = InstallGateway()
        val setup = FakeSetupGateway()
        val gate = InstallConsentGate { 1_000L }
        val auth = AccountsAuthState(gw, backgroundScope, setup = setup, consent = gate)
        auth.install("claude"); runCurrent()
        gate.decline("plan-1"); runCurrent()

        assertNull(auth.installing)
        assertEquals(InstallText.DECLINED, auth.notice!!.text)
        assertEquals(emptyList<String>(), setup.decisions)
    }

    @Test fun `sem como pedir a confirmacao nada e instalado`() = runTest {
        val gw = InstallGateway()
        val auth = AccountsAuthState(gw, backgroundScope) // sem setup nem gate
        auth.install("claude"); runCurrent()
        assertEquals(emptyList<String>(), gw.installs)
        assertNull(auth.installing)
        assertFalse(auth.notice!!.ok)
        assertTrue(auth.notice!!.text.startsWith(InstallText.NO_GATE))
    }

    @Test fun `falha na instalacao aparece em portugues com o que fazer`() = runTest {
        val gw = InstallGateway()
        val setup = FakeSetupGateway()
        setup.polls = mutableListOf(fakePlan(state = "failed", errorCode = "setup_failed", item = "Node.js e Git"))
        val gate = InstallConsentGate { 1_000L }
        val auth = AccountsAuthState(gw, backgroundScope, setup = setup, consent = gate)
        auth.install("claude"); runCurrent()
        val plano = gate.pending.value!!.plan
        gate.accept(plano.planId, plano.manifestHash); runCurrent()

        assertFalse(auth.notice!!.ok)
        assertEquals(
            "A instalação parou em Node.js e Git. Seu trabalho foi guardado. Toque em Instalar Claude para revisar e tentar de novo.",
            auth.notice!!.text,
        )
        assertNull(auth.installing)
    }

    @Test fun `caminho padrao do Claude que falhou pede um plano novo e outra confirmacao`() = runTest {
        val gw = InstallGateway()
        val setup = FakeSetupGateway()
        setup.plans = mutableListOf(PlanResult.Ok(fakePlan(id = "plan-1")), PlanResult.Ok(fakePlan(id = "plan-2", hash = "sha256:alt")))
        setup.polls = mutableListOf(fakePlan(state = "failed", errorCode = "needs_new_plan"))
        val gate = InstallConsentGate { 1_000L }
        val auth = AccountsAuthState(gw, backgroundScope, setup = setup, consent = gate)
        auth.install("claude"); runCurrent()
        gate.accept("plan-1", "sha256:h1"); runCurrent()

        // O caminho alternativo é outro plano: ainda não foi decidido nada sobre ele.
        assertEquals(listOf(Triple("claude", null, null), Triple("claude", null, "glibc-runner")), setup.created)
        assertEquals(listOf("plan-1:install:sha256:h1"), setup.decisions)
        assertEquals("plan-2", gate.pending.value!!.plan.planId)
    }

    @Test fun `cada conta pede o alvo certo ao motor`() {
        assertEquals("claude" to null, installTarget("claude"))
        assertEquals("cli" to "gh", installTarget("github"))
        assertEquals("cli" to "rclone", installTarget("gdrive"))
        assertEquals("cli" to "gemini", installTarget("gemini"))
    }
}
