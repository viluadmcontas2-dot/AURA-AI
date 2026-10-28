package ai.aura.app.ui.screens.onboarding

import ai.aura.app.integration.termuxResultFrom
import ai.aura.app.offline.EngineLink
import ai.aura.app.offline.LinkState
import ai.aura.app.offline.OfflineReason
import ai.aura.app.offline.TermuxPort
import ai.aura.app.offline.TermuxResult
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/** Ponte falsa: o que o Termux responderia, sem Termux. Cada campo é um botão que o teste liga ou desliga. */
private class PortaFalsa : TermuxPort {
    var instalado = true
    var permissao = true
    var pareado = false
    var motorNoAr = false
    var pagina = 4096
    var sonda = TermuxResult.Ok
    var pareamento = TermuxResult.Ok
    /** null = o processo do motor fica vivo (o normal); senão ele acaba logo com este resultado. */
    var processoDoMotor: TermuxResult? = null
    var pareamentos = 0
    var instalacao = TermuxResult.Ok
    var instalacoes = 0
    var ordem = mutableListOf<String>()
    var sondagens = 0

    override fun installed() = instalado
    override fun hasPermission() = permissao
    override fun isPaired() = pareado
    override fun pageSize() = pagina
    override fun requestPermission() = Unit
    override fun openInstaller() = Unit
    override fun openTermux() = Unit
    override suspend fun probe(): TermuxResult {
        sondagens++
        return sonda
    }
    override suspend fun pairAndWait(): TermuxResult {
        pareamentos++
        ordem += "parear"
        if (pareamento == TermuxResult.Ok) pareado = true
        return pareamento
    }
    override suspend fun installEngine(): TermuxResult { instalacoes++; ordem += "instalar"; return instalacao }
    override suspend fun runEngine(): TermuxResult = processoDoMotor ?: awaitCancellation()
    override suspend fun engineUp() = motorNoAr
}

private class Cenario(scope: CoroutineScope, limite: Duration = 45.seconds) {
    val porta = PortaFalsa()
    val link = EngineLink(porta)
    val fluxo = TermuxFlow(porta, link, scope, limite)
}

class TermuxFlowTest {

    @Test fun `o proximo passo e o primeiro que falta e todo passo diz o porque citando o nome do botao`() {
        fun estado(t: Boolean, p: Boolean, e: Boolean, par: Boolean, up: Boolean) =
            TermuxSetupState(termuxFound = t, permissionGranted = p, externalAppsAllowed = e, paired = par, engineUp = up)

        assertEquals(Passo.InstalarTermux, proximoPasso(estado(false, true, true, true, true)))
        assertEquals(Passo.PedirPermissao, proximoPasso(estado(true, false, true, true, true)))
        assertEquals(Passo.ColarComando, proximoPasso(estado(true, true, false, true, true)))
        assertEquals(Passo.Parear, proximoPasso(estado(true, true, true, false, true)))
        assertEquals(Passo.IniciarMotor, proximoPasso(estado(true, true, true, true, false)))
        assertEquals(Passo.Pronto, proximoPasso(estado(true, true, true, true, true)))
        assertTrue(estado(true, true, true, true, true).ready)
        // O motor respondendo prova tudo: é o que o host antigo ainda usa.
        assertEquals(Passo.Pronto, proximoPasso(termuxSetupFrom(installed = false, permissionGranted = false, engineUp = true)))
        // "Continuar" desligado responde com o motivo do passo, e o motivo cita o botão que resolve.
        Passo.entries.filter { it != Passo.Pronto }.forEach { assertTrue(it.motivo.contains(it.botao!!)) }
    }

    @Test fun `com paginas de 16 KB e o Termux mudo o passo e incompativel, e some quando ele responde`() = runTest {
        val c = Cenario(backgroundScope)
        c.porta.pagina = 16_384
        c.porta.sonda = TermuxResult.NotAllowed // o Termux atual nem sobe nesse Android
        c.fluxo.refresh(); runCurrent()

        val e = c.fluxo.state.value
        assertEquals(Passo.Incompativel, proximoPasso(e))
        assertTrue(Passo.Incompativel.motivo.startsWith("Este Android usa páginas de 16 KB e o Termux atual não roda aqui"))
        assertEquals(Passo.ColarComando, proximoPasso(e.copy(pageSize = 4096))) // com 4 KB seria o passo normal

        c.porta.sonda = TermuxResult.Ok
        c.fluxo.refresh(); runCurrent()
        assertEquals(Passo.Parear, proximoPasso(c.fluxo.state.value)) // um Termux que responde nunca é "incompatível"
    }

    @Test fun `ja colei recusado fica no passo de colar, e depois de colar confere sozinho ate o motor responder`() = runTest {
        val c = Cenario(backgroundScope)
        c.fluxo.refresh(); runCurrent()
        c.porta.sonda = TermuxResult.NotAllowed

        c.fluxo.confirmPasted(); runCurrent()

        assertEquals(Passo.ColarComando, proximoPasso(c.fluxo.state.value))
        assertTrue(c.fluxo.state.value.notice != null)
        assertEquals(0, c.porta.pareamentos)

        c.porta.sonda = TermuxResult.Ok
        c.porta.motorNoAr = true
        c.fluxo.confirmPasted(); runCurrent()

        assertEquals(Passo.Pronto, proximoPasso(c.fluxo.state.value))
        assertEquals(1, c.porta.pareamentos)
        assertEquals(LinkState.Online, c.link.state.value)
    }

    @Test fun `iniciar o motor mostra iniciando e termina quando ele responde`() = runTest {
        val c = Cenario(backgroundScope)
        c.porta.pareado = true
        c.fluxo.refresh(); runCurrent()
        backgroundScope.launch { delay(3_000); c.porta.motorNoAr = true }

        c.fluxo.startEngine(); runCurrent()
        assertEquals(LinkState.Starting, c.link.state.value)
        assertTrue(c.fluxo.state.value.testing)

        advanceTimeBy(5_000); runCurrent()
        assertEquals(Passo.Pronto, proximoPasso(c.fluxo.state.value))
        assertFalse(c.fluxo.state.value.testing)
        assertEquals(LinkState.Online, c.link.state.value)
    }

    @Test fun `motor que morre ao iniciar falha na hora e motor mudo falha no tempo limite, nunca fica iniciando`() = runTest {
        val c = Cenario(backgroundScope, limite = 10.seconds)
        c.porta.pareado = true
        c.fluxo.refresh(); runCurrent()

        c.porta.processoDoMotor = TermuxResult.Failed // ex.: o Python do Termux não está instalado
        c.fluxo.startEngine(); advanceTimeBy(2_000); runCurrent()
        assertFalse(c.fluxo.state.value.testing)
        assertTrue("falhou antes do limite", currentTime < 10_000)
        assertEquals(LinkState.Offline(OfflineReason.EngineStopped), c.link.state.value)

        c.porta.processoDoMotor = null // agora o processo fica vivo, mas o motor nunca responde
        c.fluxo.startEngine(); runCurrent()
        advanceTimeBy(11_000); runCurrent()

        val e = c.fluxo.state.value
        assertFalse(e.testing)
        assertTrue(e.notice!!.contains("não respondeu"))
        assertEquals(Passo.IniciarMotor, proximoPasso(e))
        assertEquals(LinkState.Offline(OfflineReason.EngineStopped), c.link.state.value)
    }

    @Test fun `cancelar para na hora e o motor que responder depois nao muda mais nada`() = runTest {
        val c = Cenario(backgroundScope)
        c.porta.pareado = true
        c.fluxo.refresh(); runCurrent()
        c.fluxo.startEngine(); runCurrent()

        c.fluxo.cancel(); runCurrent()
        c.porta.motorNoAr = true
        advanceTimeBy(5_000); runCurrent()

        val e = c.fluxo.state.value
        assertFalse(e.testing)
        assertTrue(e.notice!!.contains("Cancelado"))
        assertFalse(e.engineUp)
        assertEquals(LinkState.Offline(OfflineReason.EngineStopped), c.link.state.value)
    }

    @Test fun `se o Termux some no meio a espera acaba logo e o passo volta a ser instalar`() = runTest {
        val c = Cenario(backgroundScope)
        c.porta.pareado = true
        c.fluxo.refresh(); runCurrent()
        c.fluxo.startEngine(); advanceTimeBy(2_000); runCurrent()

        c.porta.instalado = false
        advanceTimeBy(2_000); runCurrent()

        val e = c.fluxo.state.value
        assertEquals(Passo.InstalarTermux, proximoPasso(e))
        assertFalse(e.testing)
        assertTrue("acabou antes do limite", currentTime < 10_000)
        assertEquals(LinkState.Offline(OfflineReason.NoTermux), c.link.state.value)
    }

    @Test fun `voltar do Termux confere a cada dois segundos e avanca sozinho`() = runTest {
        val c = Cenario(backgroundScope, limite = 60.seconds)
        c.porta.sonda = TermuxResult.NotAllowed
        c.fluxo.resume(); runCurrent()
        assertEquals(1, c.porta.sondagens)
        advanceTimeBy(1_999); runCurrent()
        assertEquals(1, c.porta.sondagens)
        c.porta.sonda = TermuxResult.Ok
        c.porta.motorNoAr = true
        advanceTimeBy(1); runCurrent()
        assertEquals(2, c.porta.sondagens)
        assertEquals(Passo.Pronto, proximoPasso(c.fluxo.state.value))
        assertFalse(c.fluxo.state.value.testing)
    }

    @Test fun `voltar do Termux sem resposta encerra em sessenta segundos`() = runTest {
        val c = Cenario(backgroundScope, limite = 60.seconds)
        c.porta.sonda = TermuxResult.NotAllowed
        c.fluxo.resume(); runCurrent()
        advanceTimeBy(60_000); runCurrent()
        assertFalse(c.fluxo.state.value.testing)
        assertTrue(c.fluxo.state.value.notice!!.contains("não respondeu"))
        assertEquals(0, c.porta.pareamentos)
        val tentativas = c.porta.sondagens
        advanceTimeBy(4_000); runCurrent()
        assertEquals(tentativas, c.porta.sondagens)
    }

    @Test fun `resultado do Termux vira recusado, falha ou token guardado, e lixo nunca guarda nada`() {
        val token = "x".repeat(40) // token de mentira só para o teste
        var guardado: String? = null
        val salvar = { t: String -> guardado = t }
        val json = """{"service":"aura-engine","host":"127.0.0.1","port":8765,"token":"$token"}"""

        assertEquals(TermuxResult.Ok, termuxResultFrom("pair", 0, -1, null, json, salvar))
        assertEquals(token, guardado)
        assertEquals(TermuxResult.Ok, termuxResultFrom("probe", 0, -1, null, "aura-ok\n", salvar))
        assertEquals(
            TermuxResult.NotAllowed,
            termuxResultFrom("probe", null, 100, "requires allow-external-apps to be true in termux.properties", null, salvar),
        )
        assertEquals(TermuxResult.Failed, termuxResultFrom("pair", 1, -1, null, "", salvar))
        assertEquals(TermuxResult.Failed, termuxResultFrom("start", 0, -1, null, "", salvar)) // o motor acabou: falhou
        guardado = null
        assertEquals(TermuxResult.Failed, termuxResultFrom("pair", 0, -1, null, "lixo", salvar))
        assertNull(guardado)
    }

    @Test fun `instala o motor no Termux antes de parear e antes de ligar`() = runTest {
        val c = Cenario(backgroundScope)
        c.fluxo.confirmPasted(); advanceTimeBy(5_000); runCurrent()
        assertEquals(listOf("instalar", "parear"), c.porta.ordem.take(2))
    }

    @Test fun `falha ao instalar o motor diz o que fazer e nao deixa a tela iniciando`() = runTest {
        val c = Cenario(backgroundScope)
        c.porta.instalacao = TermuxResult.Failed
        c.fluxo.confirmPasted(); advanceTimeBy(5_000); runCurrent()
        assertFalse(c.fluxo.state.value.testing)
        assertTrue(c.fluxo.state.value.notice!!.contains("Tentar de novo"))
        assertEquals(0, c.porta.pareamentos)
    }

    @Test fun `motor que ja responde nao e reinstalado`() = runTest {
        val c = Cenario(backgroundScope)
        c.porta.pareado = true; c.porta.motorNoAr = true
        c.fluxo.startEngine(); runCurrent()
        assertEquals(0, c.porta.instalacoes)
    }

    @Test fun `instalacao so vale com a marca de sucesso e saida zero`() {
        val nada: (String) -> Unit = {}
        assertEquals(TermuxResult.Ok, termuxResultFrom("install", 0, -1, null, "x\nAURA_ENGINE_OK\n", nada))
        assertEquals(TermuxResult.Failed, termuxResultFrom("install", 0, -1, null, "sem marca", nada))
        assertEquals(TermuxResult.Failed, termuxResultFrom("install", 1, -1, null, "AURA_ENGINE_OK", nada))
    }
}
