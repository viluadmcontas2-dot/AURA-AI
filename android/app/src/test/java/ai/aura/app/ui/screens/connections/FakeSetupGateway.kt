package ai.aura.app.ui.screens.connections

import ai.aura.app.integration.DecisionResult
import ai.aura.app.integration.InstallItem
import ai.aura.app.integration.InstallPlan
import ai.aura.app.integration.PlanResult
import ai.aura.app.integration.SetupGateway
import ai.aura.app.integration.SetupStatus
import ai.aura.app.integration.SizeKind
import ai.aura.app.integration.StatusResult
import ai.aura.app.offline.NetworkKind

const val FAKE_MB = 1024L * 1024

fun fakePlan(
    id: String = "plan-1", hash: String = "sha256:h1", target: String = "claude", state: String = "awaiting_consent",
    kind: SizeKind = SizeKind.Exact, download: Long? = 12 * FAKE_MB, expira: Long = Long.MAX_VALUE,
    errorCode: String? = null, item: String? = null, rede: NetworkKind = NetworkKind.Wifi,
) = InstallPlan(
    planId = id, manifestHash = hash, target = target, reason = "Responder aos seus pedidos",
    items = listOf(InstallItem("Claude Code", null, "registry.npmjs.org", download, download?.times(3))),
    downloadBytes = download, installedBytes = download?.times(3), sizeKind = kind, expiresAtMillis = expira,
    network = rede, state = state, errorCode = errorCode, progressItem = item,
)

/** Motor de mentira para o preparo do assistente: anota cada chamada e responde o que o teste mandar. */
class FakeSetupGateway : SetupGateway {
    /** target, resourceId, variant de cada pedido de plano. */
    val created = mutableListOf<Triple<String, String?, String?>>()
    val decisions = mutableListOf<String>()
    var measures = 0
    var cancels = 0

    /** Respostas de createPlan, na ordem; a última se repete. */
    var plans: MutableList<PlanResult> = mutableListOf(PlanResult.Ok(fakePlan()))
    var measured: PlanResult = PlanResult.Ok(fakePlan(hash = "sha256:h2"))
    var decision: DecisionResult = DecisionResult.Done("installing")

    /** Respostas de plan(id) a cada consulta, na ordem; a última se repete. */
    var polls: MutableList<InstallPlan> = mutableListOf(fakePlan(state = "completed"))
    var status: StatusResult = StatusResult.Ok(SetupStatus("installed", "signed_in", "none"))
    private var pollIndex = 0

    override suspend fun status() = status
    override suspend fun createPlan(target: String, reason: String, resourceId: String?, variant: String?): PlanResult {
        created += Triple(target, resourceId, variant)
        return plans[minOf(created.size - 1, plans.size - 1)]
    }

    override suspend fun measure(planId: String): PlanResult {
        measures++
        return measured
    }

    override suspend fun decide(plan: InstallPlan, install: Boolean): DecisionResult {
        decisions += plan.planId + ":" + (if (install) "install" else "not_now") + ":" + plan.manifestHash
        return decision
    }

    override suspend fun plan(planId: String): PlanResult {
        val next = polls[minOf(pollIndex, polls.size - 1)]
        pollIndex++
        return PlanResult.Ok(next)
    }

    override suspend fun cancel(planId: String): DecisionResult {
        cancels++
        return DecisionResult.Done("cancelling")
    }
}
