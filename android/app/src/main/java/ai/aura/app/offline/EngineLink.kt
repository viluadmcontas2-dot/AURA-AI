package ai.aura.app.offline

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.io.IOException

/** Por que o motor não está disponível. A prioridade entre os motivos está em [EngineLink]. */
enum class OfflineReason { NoTermux, NoPermission, NoToken, EngineStopped }

/** O que o app sabe sobre o motor agora. */
sealed interface LinkState {
    /** Ainda não tentou falar com o motor: não culpa ninguém antes da primeira tentativa. */
    data object Unknown : LinkState
    data object Online : LinkState
    /** O app pediu ao Termux para ligar o motor e espera ele responder. */
    data object Starting : LinkState
    data class Offline(val reason: OfflineReason) : LinkState
}

/** Perguntas baratas e locais sobre o Termux. Quem responde de verdade é o `TermuxBridge`. */
interface TermuxProbe {
    fun installed(): Boolean
    fun hasPermission(): Boolean
}

/** O motor não pôde ser alcançado. É [IOException] para os tratadores antigos continuarem valendo. */
class EngineUnreachableException(message: String, val reason: OfflineReason) : IOException(message)

/**
 * Estado da conexão com o motor, para a tela mostrar "Motor desligado" com UMA ação certa.
 * Quem fala com o motor avisa o desfecho ([online], [offline]); quem liga o motor avisa a partida
 * ([starting], [startFailed]).
 */
class EngineLink(private val probe: TermuxProbe) {
    private val mutable = MutableStateFlow<LinkState>(LinkState.Unknown)
    val state: StateFlow<LinkState> = mutable.asStateFlow()

    // Último motivo informado por uma chamada. Uma partida que falha volta para ele.
    @Volatile private var lastReason = OfflineReason.EngineStopped

    fun online() {
        mutable.value = LinkState.Online
    }

    fun starting() {
        mutable.value = LinkState.Starting
    }

    /** Falha de fundo durante a partida não derruba o aviso de "iniciando", mas o motivo fica anotado. */
    fun offline(cause: Throwable) {
        lastReason = (cause as? EngineUnreachableException)?.reason ?: OfflineReason.EngineStopped
        val next = LinkState.Offline(resolve(lastReason))
        mutable.update { if (it == LinkState.Starting) it else next }
    }

    /** A partida falhou: volta para offline com o motivo atual. Fora da partida não muda nada. */
    fun startFailed() {
        val next = LinkState.Offline(resolve(lastReason))
        mutable.update { if (it == LinkState.Starting) next else it }
    }

    /** Termux ausente vence tudo; depois a permissão (parear precisa dela); só então o que a chamada disse. */
    private fun resolve(reported: OfflineReason): OfflineReason = when {
        !probe.installed() -> OfflineReason.NoTermux
        !probe.hasPermission() -> OfflineReason.NoPermission
        else -> reported
    }
}
