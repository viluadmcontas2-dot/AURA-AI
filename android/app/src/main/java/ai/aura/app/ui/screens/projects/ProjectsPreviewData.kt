package ai.aura.app.ui.screens.projects

import ai.aura.app.ui.engine.Job
import ai.aura.app.ui.engine.JobRisk
import ai.aura.app.ui.engine.JobStatus

/** SÓ para @Preview: textos de exemplo, nunca usados em produção. */
internal object ExampleData {
    private const val NOW = 1_700_000_000_000L

    val jobs = listOf(
        Job("j1", "r1", "Corrigir tela de login", "claude_cli", JobStatus.Running, JobRisk.Write, NOW),
        Job("j2", "r2", "Instalar versão 0.3.2 no aparelho", "adb", JobStatus.AwaitingApproval, JobRisk.Write, NOW - 60_000),
        Job("j3", "r3", "Plano mestre aprovado", "system", JobStatus.Completed, JobRisk.Read, NOW - 3_600_000, result = "Plano mestre aprovado"),
        Job("j4", "r4", "Enviar alterações ao GitHub", "github", JobStatus.Failed, JobRisk.Write, NOW - 7_200_000, errorCode = "auth_required"),
    )
    val jobMap = jobs.associateBy { it.id }

    val financas = Project("p1", "Minhas Finanças", "Controlar gastos e metas do mês", "Android, Kotlin + Compose",
        screens = listOf("Entrar", "Resumo", "Lançamentos", "Metas"),
        doneWhen = listOf("Abre sem travar no celular", "Lançamento salvo em até 2 toques", "Testes passam no GitHub"),
        deliveries = listOf("Fundação", "Lançamentos", "Metas"),
        repo = "minhas-financas", jobIds = listOf("j1"))
    val tv = Project("p2", "Controle da TV", "Controlar a TV da sala", "Android, Kotlin + Compose", repo = "controle-da-tv", jobIds = listOf("j2"))
    val aura = Project("p3", "AURA AI", "Assistente de bolso", "Android, Kotlin + Compose", repo = "aura-ai", jobIds = listOf("j3"))
    val triagem = Project("p4", "Lista de tarefas", "Organizar tarefas do dia", "Android, Kotlin + Compose", repo = "lista-de-tarefas")
    val projects = listOf(financas, tv, aura, triagem)

    val artifacts = listOf(
        Artifact("a1", "p1", "APK 0.3.2", ArtifactKind.Apk, "Corrigir tela de login", generating = 0.7f),
        Artifact("a2", "p1", "APK 0.3.1", ArtifactKind.Apk, "Versão anterior", proofs = listOf(Proof("Instalado na multimídia")),
            sizeLabel = "18,4 MB", whenLabel = "há 12 min", sha256 = "a1f3c0de5b7e9c2e",
            origin = listOf("Commit" to "a3f9c1e", "Execução" to "#48"),
            versions = listOf(ArtifactVersion("0.3.1", true), ArtifactVersion("0.3.0", false)),
            sourceUrl = "https://github.com/exemplo/minhas-financas"),
        Artifact("a3", "p1", "Relatório de testes", ArtifactKind.Report, "PDF", proofs = listOf(Proof("12 de 12 passaram"))),
        Artifact("a4", "p1", "Captura da multimídia", ArtifactKind.Image, "Imagem · ontem 22:10", proofs = listOf(Proof("Prova: tela do carro"))),
        Artifact("a5", "p1", "Plano do projeto", ArtifactKind.Document, "Documento · 4 passos"),
    )
}
