package ai.aura.app.integration

/** Reforma de mentira: cada chamada é registrada; o que responder é decidido por quem monta o teste. */
class FakeReformGateway : ReformGateway {
    val calls = mutableListOf<String>()
    var onCreate: (String, String, Int, Boolean) -> ReformCreated = { _, mode, _, _ -> ReformCreated("a1b2c3d4e5f6", "/sdcard/x.apk", mode) }
    var onStep: (ReformStep) -> ReformStepResult = { step -> ready(step.wire) }
    var onSummary: () -> ReformSummary = { summary() }
    var onNetwork: (Boolean, String) -> Unit = { _, _ -> }

    override suspend fun create(apkPath: String, mode: String, goalPercent: Int, carMode: Boolean): ReformCreated {
        calls += "create:$apkPath:$mode:$goalPercent:$carMode"
        return onCreate(apkPath, mode, goalPercent, carMode)
    }

    override suspend fun runStep(id: String, step: ReformStep, idempotencyKey: String): ReformStepResult {
        calls += "step:$id:${step.wire}"
        return onStep(step)
    }

    override suspend fun summary(id: String): ReformSummary {
        calls += "summary:$id"
        return onSummary()
    }

    override suspend fun ledger(id: String, since: Long): ReformLedgerPage {
        calls += "ledger:$id:$since"
        return ReformLedgerPage(emptyList(), since)
    }

    override suspend fun verify(id: String, testRunId: String, passed: Int, total: Int): ReformSummaryMeter? {
        calls += "verify:$id:$testRunId:$passed:$total"
        return ReformSummaryMeter(100 * passed / total, 9)
    }

    override suspend fun reportNetwork(metered: Boolean, transport: String) {
        calls += "network:$metered:$transport"
        onNetwork(metered, transport)
    }

    companion object {
        val ALL_TOOLS = listOf("node", "jdk", "jadx", "apktool", "adb", "rea", "mcp")

        fun ready(step: String) = ReformStepResult(step, false, 7, false, null, ALL_TOOLS, emptyList(), null)

        fun waiting(on: String, done: List<String>, notices: List<ReformDownloadNotice> = emptyList()) = ReformStepResult(
            "tools", false, null, true, on, done, notices,
            "Estou em dados móveis. Conecte ao Wi-Fi e toque em Tentar de novo: continuo do passo $on.",
        )

        fun summary(
            reversal: Int? = null, rebuild: Int? = null, fidelity: Int? = null,
            stepsDone: List<String> = emptyList(), chainOk: Boolean = true,
        ) = ReformSummary(
            "a1b2c3d4e5f6", chainOk, "/storage/emulated/0/Download/AURA/Player.apk", "visual_flows", 90,
            reversal?.let { ReformSummaryMeter(it, 3) }, rebuild?.let { ReformSummaryMeter(it, 4) },
            fidelity?.let { ReformSummaryMeter(it, 5) }, stepsDone, 0,
        )
    }
}
