package ai.aura.app.ui.screens.onboarding

import ai.aura.app.integration.InstallPlan
import ai.aura.app.offline.EngineLink
import ai.aura.app.offline.EngineProbe
import ai.aura.app.offline.TermuxPort
import ai.aura.app.offline.TermuxResult
import ai.aura.app.ui.screens.connections.ConsentHandler
import ai.aura.app.ui.screens.connections.InstallConsentGate
import ai.aura.app.ui.screens.connections.InstallText
import ai.aura.app.ui.screens.connections.LocalEnginePlan
import ai.aura.app.ui.screens.connections.RejectReason
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

val LocalTermuxFlow = androidx.compose.runtime.staticCompositionLocalOf<TermuxFlow?> { null }

enum class Passo(val botao: String?, val motivo: String) {
    InstalarTermux("Instalar Termux", "Toque em Instalar Termux e volte ao AURA."),
    PedirPermissao("Permitir acesso ao Termux", "Toque em Permitir acesso ao Termux e autorize o AURA."),
    ColarComando("Copiar comando", "Toque em Copiar comando, abra o Termux, cole e toque em Enter."),
    Incompativel("Abrir Termux", "Este Android usa páginas de 16 KB e o Termux atual não roda aqui. Toque em Abrir Termux para conferir; instale uma versão compatível ou pule por agora."),
    PrepararMotor("Preparar motor", "Toque em Preparar motor. Antes de instalar qualquer coisa eu peço a sua confirmação."),
    Parear("Conectar Termux", "Toque em Conectar Termux para guardar a autorização no aparelho."),
    IniciarMotor("Iniciar motor", "Toque em Iniciar motor e aguarde a resposta."),
    Pronto(null, "Termux conectado e motor respondendo."),
}

fun proximoPasso(state: TermuxSetupState): Passo = when {
    !state.termuxFound -> Passo.InstalarTermux
    !state.permissionGranted -> Passo.PedirPermissao
    !state.externalAppsAllowed && state.pageSize == 16_384 -> Passo.Incompativel
    !state.externalAppsAllowed -> Passo.ColarComando
    state.engineInstalled == false && !state.engineUp -> Passo.PrepararMotor
    !state.paired -> Passo.Parear
    !state.engineUp -> Passo.IniciarMotor
    else -> Passo.Pronto
}

/** Depois de duas recusas o botão "Permitir acesso ao Termux" abre os ajustes do app. */
const val PERMISSION_BLOCKED = "Toque para abrir os ajustes e permitir."

private const val SILENT = "O Termux ou o motor não respondeu. Abra o Termux, confira o comando e a instalação do motor e toque em Tentar de novo."

/**
 * Uma tentativa por vez; nenhuma espera fica sem prazo ou ignora cancelamento.
 *
 * Consentimento (docs/FLUXOS_DE_USO.md, seção 7): este fluxo NUNCA instala por conta própria. Se falta preparar
 * o motor (ou atualizá-lo), ele só propõe um plano em [consent]; a instalação roda dentro de `onAccepted`, depois do
 * toque em Instalar, com o plano ainda válido e igual ao que a pessoa viu. Voltar do Termux, reabrir o app e
 * reconectar (resume/refresh) nunca propõem nem instalam: só dizem o que falta.
 */
class TermuxFlow(
    private val port: TermuxPort,
    private val link: EngineLink,
    private val scope: CoroutineScope,
    private val limit: Duration = 60.seconds,
    private val installLimit: Duration = 10.minutes,
    /** Espera curta antes de perguntar de novo a saúde do motor: lento não é morto. */
    private val healthRetry: Duration = 1.seconds,
    /** Onde a pessoa decide sobre instalar. O mesmo gate vale para o app inteiro (diálogo no topo). */
    val consent: InstallConsentGate = InstallConsentGate(),
) {
    private val mutable = MutableStateFlow(TermuxSetupState())
    val state = mutable.asStateFlow()
    private var work: Job? = null

    fun refresh() = begin {
        if (localState()) checkProbe(retry = true)
    }

    fun confirmPasted() = begin(limit + installLimit) {
        if (localState() && checkProbe(retry = true)) connectAndStart(initiated = true)
    }

    /** "Preparar motor": o toque da pessoa. Se falta instalar, abre o pedido de confirmação; não instala sozinho. */
    fun prepareEngine(): Unit = startEngine()

    /**
     * Chamado ao voltar do Termux: uma sonda a cada 2 s, por até 60 s. Se o motor já está sendo
     * instalado, não faz nada: recomeçar cancelaria a instalação (minutos) e a segunda tentaria
     * instalar por cima da primeira.
     */
    fun resume() = begin(limit + installLimit) {
        val answered = withTimeoutOrNull(limit.inWholeMilliseconds) { waitForTermux() }
        if (answered == null) fail(SILENT)
        else if (answered) connectAndStart(initiated = false)
    }

    private suspend fun waitForTermux(): Boolean {
        while (localState()) {
            val responded = coroutineScope {
                val interval = async { delay(2_000) }
                if (checkProbe()) {
                    interval.cancel()
                    true
                } else {
                    interval.await()
                    false
                }
            }
            if (responded) return true
        }
        return false
    }

    fun startEngine() = begin(limit + installLimit) {
        if (localState()) {
            link.starting()
            if (ensureEngine(initiated = true) && ensurePaired()) bounded { waitForEngine() }
        }
    }

    fun cancel() {
        work?.cancel()
        work = null
        link.startFailed()
        mutable.value = mutable.value.copy(testing = false, notice = "Cancelado. Toque em Tentar de novo quando quiser.")
    }

    private fun begin(budget: Duration = limit, action: suspend () -> Unit) {
        if (mutable.value.installing) return // instalação em andamento: só observa (o botão Cancelar é o único que para)
        work?.cancel()
        link.startFailed()
        work = scope.launch {
            mutable.value = mutable.value.copy(testing = true, notice = null)
            try {
                val finished = withTimeoutOrNull(budget.inWholeMilliseconds) { action(); true }
                if (finished == null) fail(SILENT)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                fail("Não foi possível conectar. Abra o Termux, confira a instalação do motor e toque em Tentar de novo.")
            } finally {
                mutable.value = mutable.value.copy(testing = false)
            }
        }
    }

    private fun localState(): Boolean {
        val installed = port.installed()
        val permission = port.hasPermission()
        mutable.value = mutable.value.copy(termuxFound = installed, permissionGranted = permission,
            paired = port.isPaired(), pageSize = port.pageSize(), engineInstalled = port.engineInstalled())
        if (!installed || !permission) {
            mutable.value = mutable.value.copy(externalAppsAllowed = false, engineUp = false)
            // Depois de duas recusas o Android não pergunta mais: o botão passa a abrir os ajustes do app.
            fail(if (installed && port.permissionBlocked()) PERMISSION_BLOCKED else proximoPasso(mutable.value).motivo)
            return false
        }
        return true
    }

    /** [retry]: um Termux frio pode demorar mais que a sonda; se ela falhar, pergunta mais uma vez. */
    private suspend fun checkProbe(retry: Boolean = false): Boolean {
        var result = port.probe()
        if (retry && result == TermuxResult.Failed) result = port.probe()
        mutable.value = mutable.value.copy(externalAppsAllowed = result == TermuxResult.Ok,
            notice = when (result) {
                TermuxResult.Ok -> null
                TermuxResult.NotAllowed -> "Termux recusou comandos. Toque em Copiar comando, cole no Termux e pressione Enter."
                TermuxResult.Failed -> "Termux não respondeu. Abra o Termux e toque em Tentar de novo."
            })
        return result == TermuxResult.Ok
    }

    private suspend fun connectAndStart(initiated: Boolean) {
        if (!ensureEngine(initiated) || !ensurePaired()) return
        bounded { waitForEngine() }
    }

    private suspend fun ensurePaired(): Boolean {
        if (port.isPaired()) return true
        if (port.pairAndWait() != TermuxResult.Ok) {
            fail("Não foi possível conectar o Termux. Confira a instalação do motor no Termux e toque em Tentar de novo.")
            return false
        }
        mutable.value = mutable.value.copy(paired = true)
        return true
    }

    /**
     * Garante que o motor certo está no Termux SEM instalar nada por conta própria:
     * - motor vivo e da mesma versão do APK: não mexe;
     * - motor vivo mas sem engine_build (velho) ou com outro: atualizar é outro pedido de confirmação;
     * - motor vivo que recusa o token (401): token divergente, apaga a credencial e refaz o pareamento;
     * - sem resposta: pergunta duas vezes (lento não é morto); se o app já preparou o motor, só liga (sem instalar);
     *   se nunca preparou, falta a confirmação da pessoa.
     * Devolve true quando dá para seguir (parear/ligar); false quando falta a decisão de instalar.
     */
    private suspend fun ensureEngine(initiated: Boolean): Boolean {
        var probe = port.engineProbe()
        if (probe == EngineProbe.Down) {
            delay(healthRetry)
            probe = port.engineProbe()
        }
        when (probe) {
            EngineProbe.Unauthorized -> {
                port.forgetCredential()
                mutable.value = mutable.value.copy(paired = false)
                return true
            }
            is EngineProbe.Up -> {
                val expected = port.expectedEngineBuild()
                if (expected == null || probe.build == expected) return true
                // Atualizar é outro pedido. Sem a pessoa pedir, o motor que está no ar continua servindo.
                mutable.value = mutable.value.copy(updateAvailable = true)
                return if (initiated) requestInstall(update = true, initiated = true) else true
            }
            EngineProbe.Down -> if (port.engineInstalled()) return true
        }
        return requestInstall(update = false, initiated = initiated)
    }

    private fun localPlan(update: Boolean): InstallPlan =
        LocalEnginePlan.build(update, port.engineAssetBytes(), port.networkKind(), consent.now())

    /** Propõe o plano (se foi a pessoa que tocou) e para aqui. Nunca baixa nem instala. */
    private fun requestInstall(update: Boolean, initiated: Boolean): Boolean {
        mutable.value = mutable.value.copy(awaitingConsent = true, engineInstalled = update)
        if (update) {
            // O motor atual segue no ar: pedir a atualização não derruba a conexão.
            link.online()
            consent.propose(localPlan(true), consentHandler(true))
            mutable.value = mutable.value.copy(notice = "Falta a sua decisão para atualizar. Nada será alterado antes disso.")
            return false
        }
        mutable.value = mutable.value.copy(engineUp = false)
        if (!initiated) {
            fail("Falta preparar o assistente. Toque em Preparar motor; nada é instalado sem a sua confirmação.")
            return false
        }
        consent.propose(localPlan(false), consentHandler(false))
        fail("Falta a sua decisão para continuar. Nada será instalado antes disso.")
        return false
    }

    /** "Atualizar motor": a pessoa pede; abre o pedido de confirmação. Não troca nada antes do aceite. */
    fun updateEngine(): Unit = begin(limit) {
        if (localState()) requestInstall(update = true, initiated = true)
    }

    private fun consentHandler(update: Boolean): ConsentHandler = object : ConsentHandler {
        override fun onAccepted(plan: InstallPlan) = installAccepted(plan, update)

        override fun onDeclined(plan: InstallPlan) {
            mutable.value = mutable.value.copy(awaitingConsent = false, notice = InstallText.DECLINED)
        }

        override fun onRejected(plan: InstallPlan, reason: RejectReason) {
            mutable.value = mutable.value.copy(awaitingConsent = false, notice = InstallText.rejected(reason))
        }
    }

    /** Roda DEPOIS do aceite. Se o plano de agora não é igual ao aceito, não instala: propõe de novo. */
    private fun installAccepted(accepted: InstallPlan, update: Boolean): Unit = begin(limit + installLimit) {
        val current = localPlan(update)
        if (current.manifestHash != accepted.manifestHash) {
            consent.propose(current, consentHandler(update))
            fail("O que seria instalado mudou. Confira o pedido novo; nada foi instalado.")
            return@begin
        }
        mutable.value = mutable.value.copy(awaitingConsent = false, updateAvailable = false)
        link.starting()
        if (update) port.stopEngine()
        if (installEngine() && ensurePaired()) bounded { waitForEngine() }
    }

    private suspend fun installEngine(): Boolean {
        mutable.value = mutable.value.copy(installing = true, notice = "Instalando o motor no Termux. Pode levar alguns minutos na primeira vez.")
        val result = try {
            port.installEngine()
        } finally {
            mutable.value = mutable.value.copy(installing = false)
        }
        if (result == TermuxResult.Ok) {
            port.markEngineInstalled()
            mutable.value = mutable.value.copy(notice = null, engineInstalled = true)
            return true
        }
        fail("Não foi possível instalar o motor no Termux. Confira a internet e toque em Tentar de novo.")
        return false
    }

    private suspend fun bounded(action: suspend () -> Unit) {
        if (withTimeoutOrNull(limit.inWholeMilliseconds) { action(); true } == null) fail(SILENT)
    }

    private suspend fun waitForEngine() = coroutineScope {
        link.starting()
        if (port.engineUp()) { online(); return@coroutineScope }
        val process = async { port.runEngine() }
        try {
            while (localState()) {
                if (process.isCompleted) {
                    process.await()
                    fail("O motor parou ao iniciar. Abra o Termux, confira a instalação do motor e toque em Tentar de novo.")
                    return@coroutineScope
                }
                if (port.engineUp()) { online(); return@coroutineScope }
                delay(2_000)
            }
        } finally {
            process.cancel()
        }
    }

    private fun online() {
        mutable.value = mutable.value.copy(termuxFound = true, permissionGranted = true,
            externalAppsAllowed = true, paired = true, engineUp = true, notice = null)
        link.online()
    }

    private fun fail(message: String) {
        mutable.value = mutable.value.copy(engineUp = false, notice = message)
        link.offline(java.io.IOException(message))
        link.startFailed()
    }
}
