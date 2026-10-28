package ai.aura.app.ui.screens.connections

import ai.aura.app.integration.InstallItem
import ai.aura.app.integration.InstallPlan
import ai.aura.app.integration.SizeKind
import ai.aura.app.offline.NetworkKind
import ai.aura.app.ui.theme.AuraTheme
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

private const val MB = 1024L * 1024

private fun plano(
    id: String = "plan-1", hash: String = "sha256:a", kind: SizeKind = SizeKind.Exact,
    download: Long? = 12 * MB, installed: Long? = 40 * MB, expira: Long = 1_000L,
    rede: NetworkKind = NetworkKind.Wifi,
) = InstallPlan(
    planId = id, manifestHash = hash, target = "claude", reason = "Responder aos seus pedidos",
    items = listOf(InstallItem("Claude Code", "9.9.9", "registry.npmjs.org", download, installed, "sha256:pacote")),
    downloadBytes = download, installedBytes = installed, sizeKind = kind, expiresAtMillis = expira, network = rede,
)

private class Anotador : ConsentHandler {
    val aceitos = mutableListOf<InstallPlan>()
    val recusados = mutableListOf<InstallPlan>()
    val barrados = mutableListOf<RejectReason>()
    var medidas = 0
    override fun onAccepted(plan: InstallPlan) { aceitos += plan }
    override fun onDeclined(plan: InstallPlan) { recusados += plan }
    override fun onRejected(plan: InstallPlan, reason: RejectReason) { barrados += reason }
    override fun onMeasure(plan: InstallPlan) { medidas++ }
}

/** A porta única do consentimento: sem aceite válido, `onAccepted` (que é quem instala) nunca roda. */
class InstallConsentGateTest {
    @Test fun `aceite valido chama onAccepted uma vez e o toque duplo nao repete`() {
        val gate = InstallConsentGate { 500L }
        val quem = Anotador()
        gate.propose(plano(), quem)

        assertNull(gate.accept("plan-1", "sha256:a"))
        assertEquals(RejectReason.NoPlan, gate.accept("plan-1", "sha256:a"))
        assertEquals(1, quem.aceitos.size)
        assertNull(gate.pending.value)
    }

    @Test fun `agora nao recusa e nada aceita depois`() {
        val gate = InstallConsentGate { 500L }
        val quem = Anotador()
        gate.propose(plano(), quem)
        gate.decline("plan-1")
        assertEquals(1, quem.recusados.size)
        assertEquals(RejectReason.NoPlan, gate.accept("plan-1", "sha256:a"))
        assertTrue(quem.aceitos.isEmpty())
    }

    @Test fun `plano vencido nunca aceita`() {
        var agora = 500L
        val gate = InstallConsentGate { agora }
        val quem = Anotador()
        gate.propose(plano(expira = 1_000L), quem)
        agora = 1_000L
        assertEquals(RejectReason.Expired, gate.accept("plan-1", "sha256:a"))
        assertTrue(quem.aceitos.isEmpty())
        assertEquals(listOf(RejectReason.Expired), quem.barrados)
        assertNull(gate.pending.value)
    }

    @Test fun `hash diferente do que a pessoa viu nunca aceita`() {
        val gate = InstallConsentGate { 500L }
        val quem = Anotador()
        gate.propose(plano(hash = "sha256:novo"), quem)
        assertEquals(RejectReason.Changed, gate.accept("plan-1", "sha256:velho"))
        assertTrue(quem.aceitos.isEmpty())
    }

    @Test fun `plano de outro id nao aceita`() {
        val gate = InstallConsentGate { 500L }
        val quem = Anotador()
        gate.propose(plano(id = "plan-2"), quem)
        assertEquals(RejectReason.NoPlan, gate.accept("plan-1", "sha256:a"))
        assertTrue(quem.aceitos.isEmpty())
    }

    @Test fun `tamanho desconhecido bloqueia a instalacao e fica na tela para calcular`() {
        val gate = InstallConsentGate { 500L }
        val quem = Anotador()
        gate.propose(plano(kind = SizeKind.Unknown, download = null, installed = null), quem)
        assertEquals(RejectReason.SizeUnknown, gate.accept("plan-1", "sha256:a"))
        assertTrue(quem.aceitos.isEmpty())
        assertTrue(gate.pending.value != null) // continua perguntando; dá para Calcular tamanho
        gate.measure("plan-1")
        assertEquals(1, quem.medidas)
    }

    @Test fun `plano novo substitui o pedido antigo e a decisao antiga nao vale`() {
        val gate = InstallConsentGate { 500L }
        val quem = Anotador()
        gate.propose(plano(id = "plan-1"), quem)
        gate.propose(plano(id = "plan-2", hash = "sha256:b"), quem)
        assertEquals(RejectReason.NoPlan, gate.accept("plan-1", "sha256:a"))
        assertNull(gate.accept("plan-2", "sha256:b"))
        assertEquals(listOf("plan-2"), quem.aceitos.map { it.planId })
    }

    @Test fun `os textos seguem o documento`() {
        val p = plano()
        assertEquals(
            "Para responder aos seus pedidos, vou baixar Claude Code. Download: 12 MB. Espaço necessário: 40 MB. Conexão: Wi-Fi.",
            InstallText.body(p),
        )
        assertTrue(InstallText.body(plano(kind = SizeKind.UpperBound)).contains("Download: até 12 MB."))
        assertTrue(InstallText.body(plano(rede = NetworkKind.Mobile)).endsWith("Conexão: dados móveis."))
        assertEquals("Instalar programas necessários?", InstallText.TITLE)
        assertEquals("Ainda não consegui calcular o tamanho. Nada será instalado.", InstallText.UNKNOWN)
    }
}

@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w390dp-h844dp")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class InstallConsentDialogTest {
    @get:Rule val rule = createComposeRule()

    private fun show(gate: InstallConsentGate) = rule.setContent { AuraTheme { InstallConsentHost(gate) } }

    @Test fun `sem pedido nao aparece nada`() {
        show(InstallConsentGate { 500L })
        rule.onNodeWithText(InstallText.TITLE).assertDoesNotExist()
    }

    @Test fun `mostra o pedido com Instalar e Agora nao e so Instalar executa`() {
        val gate = InstallConsentGate { 500L }
        val quem = Anotador()
        show(gate)
        rule.runOnUiThread { gate.propose(plano(), quem) }
        rule.waitForIdle()

        rule.onNodeWithText("Instalar programas necessários?").assertIsDisplayed()
        rule.onNodeWithText("Para responder aos seus pedidos, vou baixar Claude Code. Download: 12 MB. Espaço necessário: 40 MB. Conexão: Wi-Fi.")
            .assertIsDisplayed()
        rule.onNodeWithText("Agora não").assertIsDisplayed()
        assertTrue(quem.aceitos.isEmpty()) // mostrar o pedido não instala nada

        rule.onNodeWithText("Instalar").performClick()
        rule.waitForIdle()
        assertEquals(1, quem.aceitos.size)
        rule.onNodeWithText(InstallText.TITLE).assertDoesNotExist()
    }

    @Test fun `Agora nao fecha sem instalar`() {
        val gate = InstallConsentGate { 500L }
        val quem = Anotador()
        show(gate)
        rule.runOnUiThread { gate.propose(plano(), quem) }
        rule.waitForIdle()
        rule.onNodeWithText("Agora não").performClick()
        rule.waitForIdle()
        assertTrue(quem.aceitos.isEmpty())
        assertEquals(1, quem.recusados.size)
        rule.onNodeWithText(InstallText.TITLE).assertDoesNotExist()
    }

    @Test fun `tamanho desconhecido nao oferece Instalar, so Calcular tamanho`() {
        val gate = InstallConsentGate { 500L }
        val quem = Anotador()
        show(gate)
        rule.runOnUiThread { gate.propose(plano(kind = SizeKind.Unknown, download = null, installed = null), quem) }
        rule.waitForIdle()

        rule.onNodeWithText(InstallText.UNKNOWN).assertIsDisplayed()
        rule.onNodeWithText("Instalar").assertDoesNotExist()
        rule.onNodeWithText("Agora não").assertIsDisplayed()
        rule.onNodeWithText("Calcular tamanho").performClick()
        rule.waitForIdle()
        assertEquals(1, quem.medidas)
        assertTrue(quem.aceitos.isEmpty())
    }

    @Test fun `Detalhes mostra versao, origem e verificacao do arquivo`() {
        val gate = InstallConsentGate { 500L }
        show(gate)
        rule.runOnUiThread { gate.propose(plano(), Anotador()) }
        rule.waitForIdle()
        rule.onNodeWithText("Origem: registry.npmjs.org").assertDoesNotExist()
        rule.onNodeWithText("Detalhes").performClick()
        rule.waitForIdle()
        rule.onNodeWithText("Versão: 9.9.9").assertIsDisplayed()
        rule.onNodeWithText("Origem: registry.npmjs.org").assertIsDisplayed()
        rule.onNodeWithText("Verificação do arquivo: sha256:pacote").assertIsDisplayed()
    }
}
