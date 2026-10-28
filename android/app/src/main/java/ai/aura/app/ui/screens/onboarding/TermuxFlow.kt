package ai.aura.app.ui.screens.onboarding

import ai.aura.app.offline.EngineLink
import ai.aura.app.offline.TermuxPort
import ai.aura.app.offline.TermuxResult
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
import kotlin.time.Duration.Companion.seconds

val LocalTermuxFlow = androidx.compose.runtime.staticCompositionLocalOf<TermuxFlow?> { null }

enum class Passo(val botao: String?, val motivo: String) {
    InstalarTermux("Instalar Termux", "Toque em Instalar Termux e volte ao AURA."),
    PedirPermissao("Permitir acesso ao Termux", "Toque em Permitir acesso ao Termux e autorize o AURA."),
    ColarComando("Copiar comando", "Toque em Copiar comando, abra o Termux, cole e toque em Enter."),
    Incompativel("Abrir Termux", "Este Android usa páginas de 16 KB e o Termux atual não roda aqui. Toque em Abrir Termux para conferir; instale uma versão compatível ou pule por agora."),
    Parear("Conectar Termux", "Toque em Conectar Termux para guardar a autorização no aparelho."),
    IniciarMotor("Iniciar motor", "Toque em Iniciar motor e aguarde a resposta."),
    Pronto(null, "Termux conectado e motor respondendo."),
}

fun proximoPasso(state: TermuxSetupState): Passo = when {
    !state.termuxFound -> Passo.InstalarTermux
    !state.permissionGranted -> Passo.PedirPermissao
    !state.externalAppsAllowed && state.pageSize == 16_384 -> Passo.Incompativel
    !state.externalAppsAllowed -> Passo.ColarComando
    !state.paired -> Passo.Parear
    !state.engineUp -> Passo.IniciarMotor
    else -> Passo.Pronto
}

/** Uma tentativa por vez; nenhuma espera fica sem prazo ou ignora cancelamento. */
class TermuxFlow(
    private val port: TermuxPort,
    private val link: EngineLink,
    private val scope: CoroutineScope,
    private val limit: Duration = 60.seconds,
) {
    private val mutable = MutableStateFlow(TermuxSetupState())
    val state = mutable.asStateFlow()
    private var work: Job? = null

    fun refresh() = begin {
        if (localState()) checkProbe()
    }

    fun confirmPasted() = begin {
        if (localState() && checkProbe()) connectAndStart()
    }

    /** Chamado ao voltar do Termux: uma sonda a cada 2 s, por até 60 s. */
    fun resume() = begin {
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
            if (responded) {
                connectAndStart()
                return@begin
            }
        }
    }

    fun startEngine() = begin {
        if (localState()) waitForEngine()
    }

    fun cancel() {
        work?.cancel()
        work = null
        link.startFailed()
        mutable.value = mutable.value.copy(testing = false, notice = "Cancelado. Toque em Tentar de novo quando quiser.")
    }

    private fun begin(action: suspend () -> Unit) {
        work?.cancel()
        link.startFailed()
        work = scope.launch {
            mutable.value = mutable.value.copy(testing = true, notice = null)
            try {
                val finished = withTimeoutOrNull(limit.inWholeMilliseconds) { action(); true }
                if (finished == null) fail("O Termux ou o motor não respondeu. Abra o Termux, confira o comando e a instalação do motor e toque em Tentar de novo.")
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
            paired = port.isPaired(), pageSize = port.pageSize())
        if (!installed || !permission) {
            mutable.value = mutable.value.copy(externalAppsAllowed = false, engineUp = false)
            fail(proximoPasso(mutable.value).motivo)
            return false
        }
        return true
    }

    private suspend fun checkProbe(): Boolean {
        val result = port.probe()
        mutable.value = mutable.value.copy(externalAppsAllowed = result == TermuxResult.Ok,
            notice = when (result) {
                TermuxResult.Ok -> null
                TermuxResult.NotAllowed -> "Termux recusou comandos. Toque em Copiar comando, cole no Termux e pressione Enter."
                TermuxResult.Failed -> "Termux não respondeu. Abra o Termux e toque em Tentar de novo."
            })
        return result == TermuxResult.Ok
    }

    private suspend fun connectAndStart() {
        if (!port.isPaired()) {
            if (port.pairAndWait() != TermuxResult.Ok) {
                fail("Não foi possível conectar o Termux. Confira a instalação do motor no Termux e toque em Tentar de novo.")
                return
            }
            mutable.value = mutable.value.copy(paired = true)
        }
        waitForEngine()
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
