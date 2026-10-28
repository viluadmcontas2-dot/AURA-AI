package ai.aura.app.ui.screens.connections

import ai.aura.app.integration.DecisionResult
import ai.aura.app.integration.InstallPlan
import ai.aura.app.integration.PlanResult
import ai.aura.app.integration.SetupGateway
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Instalação pedida ao motor (C4) do começo ao fim, com a pessoa decidindo no meio:
 *
 *  1. pede o PLANO (`createPlan`): o motor só prepara o manifesto, não baixa nada;
 *  2. mostra o pedido no [InstallConsentGate] (diálogo "Instalar programas necessários?");
 *  3. SÓ depois do aceite válido manda `decide(install)` e acompanha o plano até o fim.
 *
 * Recusa, plano vencido, plano alterado e tamanho desconhecido terminam aqui, sem nenhum `decide(install)`.
 * Quem usa (contas, reforma) só traduz o [Outcome] em texto e ação.
 */
class InstallFlow(
    private val setup: SetupGateway,
    private val consent: InstallConsentGate,
    private val scope: CoroutineScope,
    private val pollMillis: Long = 2_000,
    private val maxWaitMillis: Long = 30 * 60_000,
) {
    sealed interface Outcome {
        data object Completed : Outcome

        /** Já estava instalado: nada para baixar nem para perguntar. */
        data object NotNeeded : Outcome
        data object Declined : Outcome
        data class Rejected(val reason: RejectReason) : Outcome
        data class Failed(val plan: InstallPlan) : Outcome

        /** O caminho padrão falhou e existe um alternativo: precisa de plano novo e de nova confirmação. */
        data class NeedsNewPlan(val plan: InstallPlan) : Outcome

        /** Dados móveis: o motor guardou o aceite e espera o Wi-Fi. A pessoa precisa pedir de novo com Wi-Fi. */
        data object WaitingWifi : Outcome
        data object Cancelled : Outcome
        data object TimedOut : Outcome
        data class Unavailable(val message: String) : Outcome
    }

    fun start(
        target: String,
        reason: String,
        resourceId: String? = null,
        variant: String? = null,
        onStatus: (item: String?) -> Unit = {},
        onOutcome: (Outcome) -> Unit,
    ) {
        scope.launch {
            when (val made = guard { setup.createPlan(target, reason, resourceId, variant) }) {
                null -> onOutcome(Outcome.Unavailable("O motor não respondeu. Inicie o motor e tente de novo."))
                PlanResult.NotNeeded -> onOutcome(Outcome.NotNeeded)
                is PlanResult.Unavailable -> onOutcome(Outcome.Unavailable(made.message))
                is PlanResult.Failed -> onOutcome(Outcome.Unavailable(made.message.ifBlank { "O motor recusou o pedido." }))
                is PlanResult.Ok -> propose(made.plan, onStatus, onOutcome)
            }
        }
    }

    private fun propose(plan: InstallPlan, onStatus: (String?) -> Unit, onOutcome: (Outcome) -> Unit) {
        consent.propose(plan, object : ConsentHandler {
            override fun onAccepted(plan: InstallPlan) {
                scope.launch { runAccepted(plan, onStatus, onOutcome) }
            }

            override fun onDeclined(plan: InstallPlan) = onOutcome(Outcome.Declined)

            override fun onRejected(plan: InstallPlan, reason: RejectReason) {
                // Tamanho desconhecido continua na tela (dá para Calcular tamanho); os outros motivos encerram.
                if (reason != RejectReason.SizeUnknown) onOutcome(Outcome.Rejected(reason))
            }

            override fun onMeasure(plan: InstallPlan) {
                scope.launch {
                    when (val again = guard { setup.measure(plan.planId) }) {
                        is PlanResult.Ok -> propose(again.plan, onStatus, onOutcome)
                        PlanResult.NotNeeded -> onOutcome(Outcome.NotNeeded)
                        is PlanResult.Unavailable -> onOutcome(Outcome.Unavailable(again.message))
                        is PlanResult.Failed -> onOutcome(Outcome.Unavailable(again.message))
                        null -> onOutcome(Outcome.Unavailable("O motor não respondeu. Inicie o motor e tente de novo."))
                    }
                }
            }
        })
    }

    private suspend fun runAccepted(plan: InstallPlan, onStatus: (String?) -> Unit, onOutcome: (Outcome) -> Unit) {
        when (val decided = guard { setup.decide(plan, install = true) }) {
            null -> onOutcome(Outcome.Unavailable("O motor não respondeu. Nada foi instalado."))
            is DecisionResult.Unavailable -> onOutcome(Outcome.Unavailable(decided.message))
            is DecisionResult.Rejected -> onOutcome(
                Outcome.Rejected(
                    when (decided.code) {
                        "plan_expired" -> RejectReason.Expired
                        "plan_changed" -> RejectReason.Changed
                        "size_unknown" -> RejectReason.SizeUnknown
                        else -> RejectReason.NoPlan
                    },
                ),
            )
            is DecisionResult.Done -> when (decided.state) {
                "installing", "completed", "waiting_wifi" -> watch(plan.planId, onStatus, onOutcome)
                else -> onOutcome(Outcome.Declined)
            }
        }
    }

    private suspend fun watch(planId: String, onStatus: (String?) -> Unit, onOutcome: (Outcome) -> Unit) {
        var waited = 0L
        while (waited <= maxWaitMillis) {
            val current = guard { setup.plan(planId) }
            if (current is PlanResult.Ok) {
                val plan = current.plan
                when {
                    plan.state == "completed" -> return onOutcome(Outcome.Completed)
                    plan.state == "waiting_wifi" -> return onOutcome(Outcome.WaitingWifi)
                    plan.state == "cancelled" -> return onOutcome(Outcome.Cancelled)
                    plan.state == "failed" && plan.errorCode == "needs_new_plan" -> return onOutcome(Outcome.NeedsNewPlan(plan))
                    plan.state == "failed" || plan.state == "expired" -> return onOutcome(Outcome.Failed(plan))
                    else -> onStatus(plan.progressItem)
                }
            }
            delay(pollMillis)
            waited += pollMillis
        }
        onOutcome(Outcome.TimedOut)
    }

    private suspend fun <T> guard(block: suspend () -> T): T? = try {
        block()
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        null
    }
}
