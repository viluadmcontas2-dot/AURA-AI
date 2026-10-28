package ai.aura.app.ui.screens.onboarding

import ai.aura.app.integration.termuxResultFrom
import ai.aura.app.offline.EngineLink
import ai.aura.app.offline.EngineProbe
import ai.aura.app.offline.LinkState
import ai.aura.app.offline.NetworkKind
import ai.aura.app.offline.OfflineReason
import ai.aura.app.offline.TermuxPort
import ai.aura.app.offline.TermuxResult
import ai.aura.app.ui.screens.connections.InstallConsentGate
import ai.aura.app.ui.screens.connections.InstallText
import ai.aura.app.ui.screens.connections.LocalEnginePlan
import ai.aura.app.ui.screens.connections.RejectReason
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
    /** engine_build que o motor vivo informa (null = motor velho, que não informa). */
    var buildNoMotor: String? = null
    /** Hash do motor embutido no APK (null = não sei, não compara). */
    var buildEsperado: String? = null
    var recusaToken = false
    var saudeFalhasAntes = 0
    var paradas = 0
    var credencialApagada = 0
    var instalacaoDemora = 0L
    var permissaoBloqueada = false
    var sondasFalhas = 0
    var ligaAoRodar = false
    /** O app já preparou o motor neste Termux (marca local). Pareado também prova que ele foi instalado. */
    var motorInstalado = false
    var marcasInstalado = 0
    var tamanhoMotor = 123_456L
    var rede = NetworkKind.Wifi

    override fun engineInstalled() = motorInstalado || pareado
    override fun markEngineInstalled() { motorInstalado = true; marcasInstalado++ }
    override fun engineAssetBytes() = tamanhoMotor
    override fun networkKind() = rede
    override fun installed() = instalado
    override fun hasPermission() = permissao
    override fun isPaired() = pareado
    override fun pageSize() = pagina
    override fun requestPermission() = Unit
    override fun openInstaller() = Unit
    override fun openTermux() = Unit
    override fun permissionBlocked() = permissaoBloqueada
    override suspend fun probe(): TermuxResult {
        sondagens++
        if (sondasFalhas > 0) { sondasFalhas--; return TermuxResult.Failed }
        return sonda
    }
    override suspend fun pairAndWait(): TermuxResult {
        pareamentos++
        ordem += "parear"
        if (pareamento == TermuxResult.Ok) { pareado = true; recusaToken = false }
        return pareamento
    }
    override suspend fun installEngine(): TermuxResult {
        instalacoes++; ordem += "instalar"
        delay(instalacaoDemora)
        return instalacao
    }
    override suspend fun runEngine(): TermuxResult {
        ordem += "ligar"
        processoDoMotor?.let { return it }
        if (ligaAoRodar) { motorNoAr = true; buildNoMotor = buildEsperado } // o motor novo sobe com o código novo
        awaitCancellation()
    }
    override suspend fun engineUp() = motorNoAr && !recusaToken
    override suspend fun engineProbe(): EngineProbe {
        if (recusaToken) return EngineProbe.Unauthorized
        if (saudeFalhasAntes > 0) { saudeFalhasAntes--; return EngineProbe.Down }
        return if (motorNoAr) EngineProbe.Up(buildNoMotor) else EngineProbe.Down
    }
    override fun expectedEngineBuild() = buildEsperado
    override suspend fun stopEngine(): TermuxResult { paradas++; ordem += "parar"; motorNoAr = false; return TermuxResult.Ok }
    override fun forgetCredential() { credencialApagada++; pareado = false }
}

private class Cenario(scope: CoroutineScope, limite: Duration = 45.seconds) {
    val porta = PortaFalsa()
    val link = EngineLink(porta)
    /** Relógio do aceite: o teste avança à mão para provar plano vencido. */
    var agora = 1_000_000L
    val gate = InstallConsentGate { agora }
    val fluxo = TermuxFlow(porta, link, scope, limite, consent = gate)

    /** Toca em Instalar vendo o plano que está na tela. null = aceitou; senão o motivo da recusa. */
    fun tocarInstalar(): RejectReason? {
        val plano = gate.pending.value!!.plan
        return gate.accept(plano.planId, plano.manifestHash)
    }
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
        // um Termux que responde nunca é "incompatível": o próximo passo é preparar o motor (com confirmação)
        assertEquals(Passo.PrepararMotor, proximoPasso(c.fluxo.state.value))
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
        c.fluxo.startEngine(); advanceTimeBy(4_000); runCurrent()
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

    @Test fun `nada e instalado antes do aceite e depois do aceite instala antes de parear`() = runTest {
        val c = Cenario(backgroundScope)
        c.fluxo.confirmPasted(); advanceTimeBy(5_000); runCurrent()

        // Zero instalação, zero pareamento, nenhum motor ligado: só existe o pedido na tela.
        assertEquals(0, c.porta.instalacoes)
        assertEquals(emptyList<String>(), c.porta.ordem)
        assertEquals(0, c.porta.marcasInstalado)
        assertTrue(c.fluxo.state.value.awaitingConsent)
        assertFalse(c.fluxo.state.value.testing)
        assertTrue(c.fluxo.state.value.notice!!.contains("Nada será instalado antes disso"))
        val plano = c.gate.pending.value!!.plan
        assertEquals("engine", plano.target)
        assertTrue(plano.local)

        assertEquals(null, c.tocarInstalar())
        advanceTimeBy(5_000); runCurrent()
        assertEquals(listOf("instalar", "parear"), c.porta.ordem.take(2))
        assertEquals(1, c.porta.marcasInstalado)
        assertFalse(c.fluxo.state.value.awaitingConsent)
    }

    @Test fun `o plano local diz a verdade sobre o tamanho e a rede`() = runTest {
        val c = Cenario(backgroundScope)
        c.porta.rede = NetworkKind.Mobile
        c.fluxo.confirmPasted(); advanceTimeBy(5_000); runCurrent()
        val plano = c.gate.pending.value!!.plan
        val texto = InstallText.body(plano)
        assertTrue(texto, texto.startsWith("Para executar o assistente neste celular, vou baixar Python do Termux e Motor do AURA."))
        assertTrue(texto, texto.contains("Download: até 150 MB."))      // limite superior declarado, nunca medida
        assertTrue(texto, texto.contains("Conexão: dados móveis."))
        assertTrue(plano.sizeKnown)
        assertEquals(c.agora + LocalEnginePlan.TTL_MILLIS, plano.expiresAtMillis)
    }

    @Test fun `recusar nao instala nada e o app segue usavel`() = runTest {
        val c = Cenario(backgroundScope)
        c.fluxo.confirmPasted(); advanceTimeBy(5_000); runCurrent()
        val plano = c.gate.pending.value!!.plan
        c.gate.decline(plano.planId)
        advanceTimeBy(60_000); runCurrent()

        assertEquals(0, c.porta.instalacoes)
        assertEquals(0, c.porta.pareamentos)
        assertEquals(null, c.gate.pending.value)
        assertEquals(InstallText.DECLINED, c.fluxo.state.value.notice)
        assertFalse(c.fluxo.state.value.testing)
    }

    @Test fun `plano vencido nao instala`() = runTest {
        val c = Cenario(backgroundScope)
        c.fluxo.confirmPasted(); advanceTimeBy(5_000); runCurrent()
        c.agora += LocalEnginePlan.TTL_MILLIS
        assertEquals(RejectReason.Expired, c.tocarInstalar())
        advanceTimeBy(5_000); runCurrent()
        assertEquals(0, c.porta.instalacoes)
        assertEquals(InstallText.rejected(RejectReason.Expired), c.fluxo.state.value.notice)
    }

    @Test fun `plano alterado depois de visto nao instala e pede nova escolha`() = runTest {
        val c = Cenario(backgroundScope)
        c.fluxo.confirmPasted(); advanceTimeBy(5_000); runCurrent()
        val visto = c.gate.pending.value!!.plan
        c.porta.tamanhoMotor += 1_000 // o motor embutido mudou entre ver e tocar
        assertEquals(null, c.tocarInstalar())
        advanceTimeBy(5_000); runCurrent()

        assertEquals(0, c.porta.instalacoes)
        assertTrue(c.fluxo.state.value.notice!!.contains("mudou"))
        val novo = c.gate.pending.value!!.plan
        assertTrue(novo.manifestHash != visto.manifestHash)
        c.tocarInstalar(); advanceTimeBy(5_000); runCurrent()
        assertEquals(1, c.porta.instalacoes) // agora sim: a pessoa viu e aceitou o plano novo
    }

    @Test fun `toque duplo em Instalar instala uma vez`() = runTest {
        val c = Cenario(backgroundScope)
        c.fluxo.confirmPasted(); advanceTimeBy(5_000); runCurrent()
        val plano = c.gate.pending.value!!.plan
        assertEquals(null, c.gate.accept(plano.planId, plano.manifestHash))
        assertEquals(RejectReason.NoPlan, c.gate.accept(plano.planId, plano.manifestHash))
        advanceTimeBy(5_000); runCurrent()
        assertEquals(1, c.porta.instalacoes)
    }

    @Test fun `voltar ao app e reconectar nunca propoe nem instala`() = runTest {
        val c = Cenario(backgroundScope)
        c.fluxo.resume(); advanceTimeBy(5_000); runCurrent()
        assertTrue(c.fluxo.state.value.notice!!.contains("Preparar motor")) // diz o que falta, não faz
        repeat(3) { c.fluxo.resume(); advanceTimeBy(5_000); runCurrent(); c.fluxo.refresh(); runCurrent() }
        assertEquals(0, c.porta.instalacoes)
        assertEquals(0, c.porta.pareamentos)
        assertEquals(null, c.gate.pending.value) // nem o pedido aparece sozinho: quem pede é o toque da pessoa
        assertEquals(Passo.PrepararMotor, proximoPasso(c.fluxo.state.value))
    }

    @Test fun `motor ja preparado e so parado liga sem instalar`() = runTest {
        val c = Cenario(backgroundScope)
        c.porta.motorInstalado = true; c.porta.pareado = true
        c.porta.ligaAoRodar = true
        c.fluxo.startEngine(); advanceTimeBy(5_000); runCurrent()
        assertEquals(0, c.porta.instalacoes)
        assertEquals(null, c.gate.pending.value)
        assertEquals(listOf("ligar"), c.porta.ordem)
    }

    @Test fun `falha ao instalar o motor diz o que fazer e nao deixa a tela iniciando`() = runTest {
        val c = Cenario(backgroundScope)
        c.porta.instalacao = TermuxResult.Failed
        c.fluxo.confirmPasted(); advanceTimeBy(5_000); runCurrent()
        c.tocarInstalar(); advanceTimeBy(5_000); runCurrent()
        assertFalse(c.fluxo.state.value.testing)
        assertTrue(c.fluxo.state.value.notice!!.contains("Tentar de novo"))
        assertEquals(0, c.porta.pareamentos)
        assertEquals(0, c.porta.marcasInstalado) // instalação que falhou não vira "preparado"
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

    @Test fun `voltar do Termux durante a instalacao so observa e nao cancela nem instala de novo`() = runTest {
        val c = Cenario(backgroundScope)
        c.porta.instalacaoDemora = 120_000
        c.fluxo.confirmPasted(); advanceTimeBy(1_500); runCurrent()
        c.tocarInstalar(); advanceTimeBy(1_500); runCurrent()
        assertTrue(c.fluxo.state.value.installing)

        repeat(5) { c.fluxo.resume(); c.fluxo.refresh(); advanceTimeBy(10_000); runCurrent() }

        assertEquals(1, c.porta.instalacoes)
        assertTrue("a instalacao segue de pe", c.fluxo.state.value.installing)
        c.porta.motorNoAr = true
        advanceTimeBy(80_000); runCurrent()
        assertFalse(c.fluxo.state.value.installing)
        assertEquals(1, c.porta.instalacoes)
        assertEquals(1, c.porta.pareamentos)
        assertEquals(Passo.Pronto, proximoPasso(c.fluxo.state.value))
    }

    @Test fun `motor vivo sem engine_build e velho, atualizar e outro pedido e so depois do aceite para, reinstala e religa`() = runTest {
        val c = Cenario(backgroundScope)
        c.porta.pareado = true; c.porta.motorNoAr = true
        c.porta.buildEsperado = "abc123"; c.porta.buildNoMotor = null; c.porta.ligaAoRodar = true
        c.fluxo.startEngine(); advanceTimeBy(5_000); runCurrent()

        // Antes do aceite o motor velho continua no ar: nada parou, nada foi instalado.
        assertEquals(emptyList<String>(), c.porta.ordem)
        assertEquals("engine", c.gate.pending.value!!.plan.target)
        assertEquals("local-engine-update", c.gate.pending.value!!.plan.planId)
        assertEquals(LinkState.Online, c.link.state.value)

        c.tocarInstalar(); advanceTimeBy(5_000); runCurrent()
        assertEquals(listOf("parar", "instalar", "ligar"), c.porta.ordem)
        assertEquals(Passo.Pronto, proximoPasso(c.fluxo.state.value))
    }

    @Test fun `motor vivo com engine_build diferente do APK so atualiza com aceite`() = runTest {
        val c = Cenario(backgroundScope)
        c.porta.pareado = true; c.porta.motorNoAr = true
        c.porta.buildEsperado = "novo"; c.porta.buildNoMotor = "antigo"; c.porta.ligaAoRodar = true
        c.fluxo.startEngine(); advanceTimeBy(5_000); runCurrent()
        assertEquals(0, c.porta.instalacoes)
        c.tocarInstalar(); advanceTimeBy(5_000); runCurrent()
        assertEquals(listOf("parar", "instalar", "ligar"), c.porta.ordem)
    }

    @Test fun `ao voltar ao app com motor desatualizado nao atualiza sozinho, so avisa que ha atualizacao`() = runTest {
        val c = Cenario(backgroundScope)
        c.porta.pareado = true; c.porta.motorNoAr = true
        c.porta.buildEsperado = "novo"; c.porta.buildNoMotor = "antigo"
        c.fluxo.resume(); advanceTimeBy(5_000); runCurrent()
        assertEquals(emptyList<String>(), c.porta.ordem)
        assertEquals(null, c.gate.pending.value)
        assertTrue(c.fluxo.state.value.updateAvailable)
        assertEquals(LinkState.Online, c.link.state.value)

        c.fluxo.updateEngine(); runCurrent()   // quem pede é o botão "Atualizar motor"
        assertEquals("local-engine-update", c.gate.pending.value!!.plan.planId)
        assertEquals(0, c.porta.instalacoes)
    }

    @Test fun `motor vivo com o mesmo engine_build nao e tocado`() = runTest {
        val c = Cenario(backgroundScope)
        c.porta.pareado = true; c.porta.motorNoAr = true
        c.porta.buildEsperado = "igual"; c.porta.buildNoMotor = "igual"
        c.fluxo.startEngine(); advanceTimeBy(5_000); runCurrent()
        assertEquals(emptyList<String>(), c.porta.ordem)
        assertEquals(0, c.porta.paradas)
    }

    @Test fun `saude lenta nao derruba o motor vivo, pergunta duas vezes antes de reinstalar`() = runTest {
        val c = Cenario(backgroundScope)
        c.porta.pareado = true; c.porta.motorNoAr = true
        c.porta.buildEsperado = "igual"; c.porta.buildNoMotor = "igual"
        c.porta.saudeFalhasAntes = 1
        c.fluxo.startEngine(); advanceTimeBy(5_000); runCurrent()
        assertEquals(0, c.porta.instalacoes)
        assertEquals(0, c.porta.paradas)
        assertEquals(Passo.Pronto, proximoPasso(c.fluxo.state.value))
    }

    @Test fun `motor vivo que recusa o token (401) refaz o pareamento e nao reinstala`() = runTest {
        val c = Cenario(backgroundScope)
        c.porta.pareado = true; c.porta.motorNoAr = true; c.porta.recusaToken = true
        c.fluxo.confirmPasted(); advanceTimeBy(5_000); runCurrent()

        assertEquals(1, c.porta.credencialApagada)
        assertEquals(1, c.porta.pareamentos)
        assertEquals(0, c.porta.instalacoes)
        assertEquals(0, c.porta.paradas)
        assertEquals(Passo.Pronto, proximoPasso(c.fluxo.state.value))
    }

    @Test fun `sonda que falha num Termux frio e repetida uma vez`() = runTest {
        val c = Cenario(backgroundScope)
        c.porta.sondasFalhas = 1
        c.fluxo.refresh(); runCurrent()
        assertEquals(2, c.porta.sondagens)
        assertTrue(c.fluxo.state.value.externalAppsAllowed)
    }

    @Test fun `permissao recusada duas vezes manda abrir os ajustes do app`() = runTest {
        val c = Cenario(backgroundScope)
        c.porta.permissao = false; c.porta.permissaoBloqueada = true
        c.fluxo.refresh(); runCurrent()
        assertEquals("Toque para abrir os ajustes e permitir.", c.fluxo.state.value.notice)
        assertEquals(Passo.PedirPermissao, proximoPasso(c.fluxo.state.value))
    }
}
