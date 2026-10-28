package ai.aura.app.ui.screens.reform

import ai.aura.app.integration.ApkSharer
import ai.aura.app.integration.AuraEngineException
import ai.aura.app.integration.FakeReformGateway
import ai.aura.app.integration.ReformDownloadNotice
import ai.aura.app.integration.sharedApkName
import ai.aura.app.offline.EngineUnreachableException
import ai.aura.app.offline.OfflineReason
import ai.aura.app.ui.screens.connections.FakeSetupGateway
import ai.aura.app.ui.screens.connections.InstallConsentGate
import ai.aura.app.ui.screens.connections.fakePlan
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runCurrent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ReformControllerTest {
    private val scope = TestScope(UnconfinedTestDispatcher())
    private val gateway = FakeReformGateway()
    private val store = MemoryReformIdStore()
    private val shared = mutableListOf<Pair<String, String>>()
    private var sharerFails: Exception? = null
    private val sharer = object : ApkSharer {
        override suspend fun share(uri: String, displayName: String): String {
            sharerFails?.let { throw it }
            shared += uri to displayName
            return "/storage/emulated/0/Download/AURA/" + sharedApkName(displayName)
        }
    }
    private var networkFlushes = 0

    private fun controller() = ReformController(
        gateway, sharer, store, scope, beforeInstall = { networkFlushes++ }, clockLabel = { "10:00" },
    )

    private val plan = ReformPlan(
        "content://x/1", "Player.apk", ReformScope.VisualAndFlows, carMode = true, fidelityTargetPercent = 90,
        apkUri = "content://x/1",
    )

    // ---------- Escopo ----------

    @Test
    fun `travar escopo copia o APK, cria a reforma no motor com o caminho compartilhado e so entao abre a Reversao`() {
        val c = controller()
        var opened = 0
        c.lockScope(plan) { opened++ }
        scope.runCurrent()
        assertEquals(listOf("content://x/1" to "Player.apk"), shared)
        assertEquals("create:/storage/emulated/0/Download/AURA/Player.apk:visual_flows:90:true", gateway.calls.first())
        assertEquals("a1b2c3d4e5f6", store.reformId)
        assertEquals(1, opened)
        assertEquals(ReformScopeUi(), c.scopeUi.value)
        assertTrue(c.hasReform)
    }

    @Test
    fun `APK que nao pode ser copiado mostra o motivo e nao chama o motor`() {
        sharerFails = java.io.IOException("Não consegui abrir o APK escolhido. Escolha o arquivo de novo.")
        val c = controller()
        var opened = 0
        c.lockScope(plan) { opened++ }
        scope.runCurrent()
        assertEquals(0, opened)
        assertEquals("Não consegui abrir o APK escolhido. Escolha o arquivo de novo.", c.scopeUi.value.error)
        assertFalse(c.scopeUi.value.busy)
        assertTrue(gateway.calls.none { it.startsWith("create") })
        assertNull(store.reformId)
    }

    @Test
    fun `app da lista sem arquivo explica em vez de ficar mudo`() {
        val c = controller()
        c.lockScope(plan.copy(apkUri = null)) { }
        scope.runCurrent()
        assertTrue(c.scopeUi.value.error!!.contains("Escolher APK"))
    }

    @Test
    fun `motor parado ao travar o escopo mostra a mensagem do motor e deixa tentar de novo`() {
        gateway.onCreate = { _, _, _, _ -> throw EngineUnreachableException("Termux não responde. Abra o Termux e inicie o motor.", OfflineReason.EngineStopped) }
        val c = controller()
        c.lockScope(plan) { }
        scope.runCurrent()
        assertEquals("Termux não responde. Abra o Termux e inicie o motor.", c.scopeUi.value.error)
        gateway.onCreate = { _, mode, _, _ -> ai.aura.app.integration.ReformCreated("a1b2c3d4e5f6", null, mode) }
        var opened = 0
        c.lockScope(plan) { opened++ }
        scope.runCurrent()
        assertEquals(1, opened)
    }

    // ---------- Ferramentas ----------

    @Test
    fun `sem reforma, instalar devolve false para o chat assumir (nunca fica mudo)`() {
        assertFalse(controller().installTools())
        assertTrue(gateway.calls.isEmpty())
    }

    @Test
    fun `instalar roda o passo tools, avisa a rede antes e marca tudo pronto`() {
        store.reformId = "a1b2c3d4e5f6"
        val c = controller()
        assertTrue(c.installTools())
        scope.runCurrent()
        assertEquals(1, networkFlushes)
        assertEquals("step:a1b2c3d4e5f6:tools", gateway.calls.first())
        assertTrue(c.tools.value.rows.all { it.status == ReformToolStatus.Ready })
        assertEquals(ReformToolsAction.Done, c.tools.value.action)
        assertNull(c.tools.value.problem)
    }

    @Test
    fun `dados moveis viram Aguardando Wi-Fi, sem erro, com o aviso de download`() {
        store.reformId = "a1b2c3d4e5f6"
        gateway.onStep = { FakeReformGateway.waiting("jdk", listOf("node"), listOf(ReformDownloadNotice("jdk", 180))) }
        val c = controller()
        c.installTools()
        scope.runCurrent()
        val rows = c.tools.value.rows.associateBy { it.id }
        assertEquals(ReformToolStatus.Ready, rows["node"]!!.status)
        assertEquals(ReformToolStatus.WaitingWifi, rows["jdk"]!!.status)
        assertEquals("Aguardando Wi-Fi", rows["jdk"]!!.chipText)
        assertNull("espera de Wi-Fi nao e problema", c.tools.value.problem)
        assertEquals(ReformToolsAction.Retry, c.tools.value.action)
        assertEquals(listOf(ReformDownloadNotice("jdk", 180)), c.tools.value.downloadNotices)
        assertTrue(c.tools.value.waitMessage!!.contains("Wi-Fi"))
    }

    @Test
    fun `com Wi-Fi, tentar de novo retoma e termina`() {
        store.reformId = "a1b2c3d4e5f6"
        var wifi = false
        gateway.onStep = { if (wifi) FakeReformGateway.ready("tools") else FakeReformGateway.waiting("jdk", listOf("node")) }
        val c = controller()
        c.installTools(); scope.runCurrent()
        wifi = true
        c.installTools(); scope.runCurrent()
        assertEquals(ReformToolsAction.Done, c.tools.value.action)
        assertNull(c.tools.value.waitMessage)
    }

    @Test
    fun `falha do passo vira problema com a unica correcao Tentar de novo`() {
        store.reformId = "a1b2c3d4e5f6"
        gateway.onStep = { throw AuraEngineException(502, "Passo jdk falhou: sem espaço. Rode de novo para continuar daqui.", "rea_step_failed") }
        val c = controller()
        c.installTools(); scope.runCurrent()
        val problem = c.tools.value.problem!!
        assertEquals(ReformFix.Retry, problem.fix)
        assertTrue(problem.body.contains("sem espaço"))
    }

    @Test
    fun `motor desligado oferece Iniciar o motor`() {
        store.reformId = "a1b2c3d4e5f6"
        gateway.onStep = { throw EngineUnreachableException("Termux não responde.", OfflineReason.EngineStopped) }
        val c = controller()
        c.installTools(); scope.runCurrent()
        assertEquals(ReformFix.StartEngine, c.tools.value.problem!!.fix)
    }

    // ---------- Progresso ----------

    @Test
    fun `sem reforma o progresso fica vazio`() {
        val c = controller()
        c.refreshProgress(); scope.runCurrent()
        assertFalse(c.progress.value.started)
    }

    @Test
    fun `medidores vem do motor e fidelidade nula continua nula`() {
        store.reformId = "a1b2c3d4e5f6"
        gateway.onSummary = { FakeReformGateway.summary(reversal = 68, rebuild = 35, stepsDone = listOf("tools")) }
        val c = controller()
        c.refreshProgress(); scope.runCurrent()
        val p = c.progress.value
        assertTrue(p.started && p.locked)
        assertEquals(68, p.reversalPct)
        assertEquals(35, p.rebuildPct)
        assertNull("Fidelidade: ainda nao medida", p.fidelityPct)
        assertEquals("Visual + fluxos", p.mode)
        assertEquals(ReformProgressStepState.Done, p.steps.first { it.title == "Preparar as ferramentas" }.state)
        assertEquals(ReformProgressStepState.Pending, p.steps.first { it.title == "Inventariar o APK" }.state)
    }

    @Test
    fun `cadeia adulterada nao mostra numero e explica`() {
        store.reformId = "a1b2c3d4e5f6"
        gateway.onSummary = { FakeReformGateway.summary(chainOk = false) }
        val c = controller()
        c.refreshProgress(); scope.runCurrent()
        assertTrue(c.progress.value.error!!.contains("nova reforma"))
        assertNull(c.progress.value.reversalPct)
    }

    @Test
    fun `falha ao ler o resumo mostra erro com acao e depois se recupera`() {
        store.reformId = "a1b2c3d4e5f6"
        gateway.onSummary = { throw EngineUnreachableException("Termux não responde.", OfflineReason.EngineStopped) }
        val c = controller()
        c.refreshProgress(); scope.runCurrent()
        assertEquals("Termux não responde.", c.progress.value.error)
        gateway.onSummary = { FakeReformGateway.summary(reversal = 10) }
        c.refreshProgress(); scope.runCurrent()
        assertNull(c.progress.value.error)
        assertEquals(10, c.progress.value.reversalPct)
    }

    @Test
    fun `continuar pede o proximo passo que falta e atualiza os numeros`() {
        store.reformId = "a1b2c3d4e5f6"
        gateway.onSummary = { FakeReformGateway.summary(stepsDone = listOf("tools")) }
        val c = controller()
        c.continueReform(); scope.runCurrent()
        assertTrue(gateway.calls.contains("step:a1b2c3d4e5f6:inventory"))
    }

    // ---------- Ferramentas pedem consentimento (C4) ----------

    private val setup = FakeSetupGateway()
    private val gate = InstallConsentGate { 1_000L }
    private fun controllerComConsentimento() = ReformController(
        gateway, sharer, store, scope, beforeInstall = { networkFlushes++ }, clockLabel = { "10:00" },
        setup = setup, consent = gate, installPollMillis = 1_000,
    )
    private val semAceite = AuraEngineException(403, "Falta a sua decisão.", "consent_required")

    @Test
    fun `o passo tools recusado por falta de consentimento abre o pedido e nao instala nada`() {
        store.reformId = "a1b2c3d4e5f6"
        var consentido = false
        gateway.onStep = { if (consentido) FakeReformGateway.ready("tools") else throw semAceite }
        val c = controllerComConsentimento()
        c.installTools(); scope.runCurrent()

        assertEquals(listOf(Triple("reform_tools", null, null)), setup.created)
        assertEquals(emptyList<String>(), setup.decisions)          // zero instalação antes do aceite
        assertEquals("plan-1", gate.pending.value!!.plan.planId)
        assertTrue(c.tools.value.waitMessage!!.contains("Nada foi baixado"))
        assertNull(c.tools.value.problem)
        assertEquals(1, gateway.calls.count { it.startsWith("step:") })

        consentido = true // o motor passa a aceitar depois que a pessoa aceita o plano
        val plano = gate.pending.value!!.plan
        gate.accept(plano.planId, plano.manifestHash); scope.runCurrent()
        assertEquals(listOf("plan-1:install:sha256:h1"), setup.decisions)
        assertTrue(c.tools.value.rows.all { it.status == ReformToolStatus.Ready })
        assertEquals(2, gateway.calls.count { it.startsWith("step:") }) // só repete o passo depois do aceite
    }

    @Test
    fun `recusar mantem o escopo e as telas e nada e instalado`() {
        store.reformId = "a1b2c3d4e5f6"
        gateway.onStep = { throw semAceite }
        gateway.onSummary = { FakeReformGateway.summary(stepsDone = emptyList()) }
        val c = controllerComConsentimento()
        c.continueReform(); scope.runCurrent()
        assertTrue(c.progress.value.blocked!!.contains("Nada foi instalado"))
        gate.decline("plan-1"); scope.runCurrent()

        assertEquals("a1b2c3d4e5f6", store.reformId)               // o escopo continua guardado
        assertEquals(emptyList<String>(), setup.decisions)
        assertEquals("Nada foi instalado", c.tools.value.problem!!.title)
        assertNull(c.progress.value.error)                          // não é erro: a pessoa decidiu
        assertTrue(c.hasReform)
    }

    @Test
    fun `sem reforma ainda, Instalar o que falta pede o plano direto e nunca vira pedido ao chat`() {
        val c = controllerComConsentimento()
        assertTrue(c.installTools()); scope.runCurrent()
        assertEquals(listOf(Triple("reform_tools", null, null)), setup.created)
        assertEquals(emptyList<String>(), setup.decisions)
        assertTrue(gateway.calls.isEmpty())
        val plano = gate.pending.value!!.plan
        gate.accept(plano.planId, plano.manifestHash); scope.runCurrent()
        assertEquals(1, setup.decisions.size)
        assertTrue(c.tools.value.rows.all { it.status == ReformToolStatus.Ready })
    }

    @Test
    fun `consultar o progresso nunca executa verificacao`() {
        store.reformId = "a1b2c3d4e5f6"
        val c = controllerComConsentimento()
        repeat(3) { c.refreshProgress(); scope.runCurrent() }
        assertTrue(gateway.calls.all { it.startsWith("summary:") })
        assertTrue(gateway.calls.none { it.startsWith("verify:") })
    }

    @Test
    fun `analise concluida diz o limite atual e nao promete APK`() {
        store.reformId = "a1b2c3d4e5f6"
        gateway.onSummary = { FakeReformGateway.summary(stepsDone = listOf("tools", "inventory", "coverage")) }
        val c = controllerComConsentimento()
        c.refreshProgress(); scope.runCurrent()
        assertTrue(c.progress.value.analysisDone)
        assertNull(c.progress.value.fidelityPct)
    }

    @Test
    fun `sharedApkName limpa o nome e termina em apk`() {
        assertEquals("Player_M_sica.apk", sharedApkName("Player Música.apk"))
        assertEquals("app.apk", sharedApkName("///"))
        assertEquals("x.apk", sharedApkName("../../x.APK"))
    }
}
