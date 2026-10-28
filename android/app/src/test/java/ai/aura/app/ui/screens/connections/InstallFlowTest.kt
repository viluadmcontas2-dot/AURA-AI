package ai.aura.app.ui.screens.connections

import ai.aura.app.integration.DecisionResult
import ai.aura.app.integration.PlanResult
import ai.aura.app.integration.SizeKind
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** O ciclo inteiro: plano -> pedido na tela -> (só depois do aceite) decisão -> acompanhamento. */
class InstallFlowTest {
    private class Ambiente(scope: kotlinx.coroutines.CoroutineScope) {
        var agora = 1_000L
        val gate = InstallConsentGate { agora }
        val setup = FakeSetupGateway()
        val fluxo = InstallFlow(setup, gate, scope, pollMillis = 1_000)
        val resultados = mutableListOf<InstallFlow.Outcome>()
        val progresso = mutableListOf<String?>()
        fun iniciar(variant: String? = null) = fluxo.start("claude", "Responder aos seus pedidos", null, variant,
            onStatus = { progresso += it }) { resultados += it }
        fun tocarInstalar(): RejectReason? = gate.pending.value!!.plan.let { gate.accept(it.planId, it.manifestHash) }
    }

    @Test fun `o plano so e preparado e nada e decidido antes do aceite`() = runTest {
        val a = Ambiente(backgroundScope)
        a.iniciar(); runCurrent()
        assertEquals(listOf(Triple("claude", null, null)), a.setup.created)
        assertEquals(emptyList<String>(), a.setup.decisions)  // zero instalação: o motor nem recebeu "install"
        assertTrue(a.resultados.isEmpty())
        assertEquals("plan-1", a.gate.pending.value!!.plan.planId)
    }

    @Test fun `aceite decide uma vez e acompanha ate concluir`() = runTest {
        val a = Ambiente(backgroundScope)
        a.setup.polls = mutableListOf(fakePlan(state = "installing", item = "Claude Code"), fakePlan(state = "completed"))
        a.iniciar(); runCurrent()
        assertNull(a.tocarInstalar()); runCurrent()
        assertEquals(listOf("plan-1:install:sha256:h1"), a.setup.decisions)
        advanceTimeBy(1_500); runCurrent()
        assertEquals(listOf<InstallFlow.Outcome>(InstallFlow.Outcome.Completed), a.resultados)
        assertEquals(listOf<String?>("Claude Code"), a.progresso)
    }

    @Test fun `toque duplo decide uma vez`() = runTest {
        val a = Ambiente(backgroundScope)
        a.iniciar(); runCurrent()
        val plano = a.gate.pending.value!!.plan
        assertNull(a.gate.accept(plano.planId, plano.manifestHash))
        assertEquals(RejectReason.NoPlan, a.gate.accept(plano.planId, plano.manifestHash))
        advanceTimeBy(3_000); runCurrent()
        assertEquals(1, a.setup.decisions.size)
    }

    @Test fun `Agora nao nao decide nada`() = runTest {
        val a = Ambiente(backgroundScope)
        a.iniciar(); runCurrent()
        a.gate.decline("plan-1"); runCurrent()
        assertEquals(listOf<InstallFlow.Outcome>(InstallFlow.Outcome.Declined), a.resultados)
        assertEquals(emptyList<String>(), a.setup.decisions)
    }

    @Test fun `plano vencido nao decide nada`() = runTest {
        val a = Ambiente(backgroundScope)
        a.setup.plans = mutableListOf(PlanResult.Ok(fakePlan(expira = 2_000L)))
        a.iniciar(); runCurrent()
        a.agora = 2_000L
        assertEquals(RejectReason.Expired, a.tocarInstalar()); runCurrent()
        assertEquals(listOf<InstallFlow.Outcome>(InstallFlow.Outcome.Rejected(RejectReason.Expired)), a.resultados)
        assertEquals(emptyList<String>(), a.setup.decisions)
    }

    @Test fun `tamanho desconhecido nao decide, calcula e so entao deixa aceitar o plano novo`() = runTest {
        val a = Ambiente(backgroundScope)
        a.setup.plans = mutableListOf(PlanResult.Ok(fakePlan(kind = SizeKind.Unknown, download = null)))
        a.iniciar(); runCurrent()
        assertEquals(RejectReason.SizeUnknown, a.tocarInstalar())
        assertEquals(emptyList<String>(), a.setup.decisions)
        assertTrue(a.resultados.isEmpty()) // continua na tela, esperando Calcular tamanho

        a.gate.measure("plan-1"); runCurrent()
        assertEquals(1, a.setup.measures)
        assertEquals("sha256:h2", a.gate.pending.value!!.plan.manifestHash)
        assertNull(a.tocarInstalar()); runCurrent()
        assertEquals(listOf("plan-1:install:sha256:h2"), a.setup.decisions)
    }

    @Test fun `o motor pode recusar o aceite e nada e instalado`() = runTest {
        val a = Ambiente(backgroundScope)
        a.setup.decision = DecisionResult.Rejected("plan_changed", "mudou")
        a.iniciar(); runCurrent()
        a.tocarInstalar(); runCurrent()
        assertEquals(listOf<InstallFlow.Outcome>(InstallFlow.Outcome.Rejected(RejectReason.Changed)), a.resultados)
    }

    @Test fun `falha vira Failed e caminho padrao que falhou pede plano novo`() = runTest {
        val a = Ambiente(backgroundScope)
        a.setup.polls = mutableListOf(fakePlan(state = "failed", errorCode = "setup_failed", item = "Node.js e Git"))
        a.iniciar(); runCurrent(); a.tocarInstalar(); runCurrent()
        assertTrue(a.resultados.single() is InstallFlow.Outcome.Failed)

        val b = Ambiente(backgroundScope)
        b.setup.polls = mutableListOf(fakePlan(state = "failed", errorCode = "needs_new_plan"))
        b.iniciar(); runCurrent(); b.tocarInstalar(); runCurrent()
        assertTrue(b.resultados.single() is InstallFlow.Outcome.NeedsNewPlan)
    }

    @Test fun `dados moveis esperam o Wi-Fi sem baixar`() = runTest {
        val a = Ambiente(backgroundScope)
        a.setup.polls = mutableListOf(fakePlan(state = "waiting_wifi"))
        a.iniciar(); runCurrent(); a.tocarInstalar(); runCurrent()
        assertEquals(listOf<InstallFlow.Outcome>(InstallFlow.Outcome.WaitingWifi), a.resultados)
    }

    @Test fun `ja instalado e motor fora do ar nao abrem pedido nenhum`() = runTest {
        val a = Ambiente(backgroundScope)
        a.setup.plans = mutableListOf(PlanResult.NotNeeded)
        a.iniciar(); runCurrent()
        assertEquals(listOf<InstallFlow.Outcome>(InstallFlow.Outcome.NotNeeded), a.resultados)
        assertNull(a.gate.pending.value)

        val b = Ambiente(backgroundScope)
        b.setup.plans = mutableListOf(PlanResult.Unavailable("O motor não respondeu."))
        b.iniciar(); runCurrent()
        assertEquals(listOf<InstallFlow.Outcome>(InstallFlow.Outcome.Unavailable("O motor não respondeu.")), b.resultados)
        assertNull(b.gate.pending.value)
        assertEquals(emptyList<String>(), b.setup.decisions)
    }
}
