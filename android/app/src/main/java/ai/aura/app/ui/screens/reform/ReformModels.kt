package ai.aura.app.ui.screens.reform

import java.text.Normalizer

/** Meta de fidelidade da reforma (Escopo mostra, Fidelidade mede). */
const val REFORM_FIDELITY_TARGET_PERCENT = 90

/** A UMA ação que resolve um problema; vira o botão (verbo + objeto) e volta ao dono da rota por callback. */
enum class ReformFix(val label: String) {
    Search("Procurar apps"),
    Retry("Tentar de novo"),
    StartEngine("Iniciar o motor"),
    OpenTools("Instalar ferramentas"),
}

/** O que deu errado, dito em português simples, com a única correção. */
data class ReformProblem(val title: String, val body: String, val fix: ReformFix)

// ---------- Reformar 1 · Apps ----------

/** Só decide o ícone; nunca muda regra. */
enum class ReformAppKind { Music, Navigation, Radio, Camera, Generic }

/** App listado no aparelho ou APK escolhido pelo usuário. Sem dado, sem etiqueta. */
data class ReformApp(
    val id: String,
    val label: String,
    val version: String? = null,
    val year: Int? = null,
    /** 0..1; null = o motor ainda não estimou (a linha "Potencial" some). */
    val potential: Float? = null,
    val apkUri: String? = null,
) {
    val details: String?
        get() = listOfNotNull(
            version?.takeIf { it.isNotBlank() }?.let { if (it.startsWith("v", ignoreCase = true)) it else "v$it" },
            year?.toString(),
        ).takeIf { it.isNotEmpty() }?.joinToString(" · ")

    val kind: ReformAppKind
        get() {
            val name = plain(label)
            return when {
                "camera" in name -> ReformAppKind.Camera
                "radio" in name || "fm" in tokens(name) -> ReformAppKind.Radio
                "musica" in name || "music" in name || "player" in name -> ReformAppKind.Music
                "gps" in name || "navega" in name || "mapa" in name -> ReformAppKind.Navigation
                else -> ReformAppKind.Generic
            }
        }

    /** 10 anos ou mais = "Muito antigo"; 5 a 9 = "Antigo"; mais novo ou sem ano = sem etiqueta. */
    fun ageLabel(currentYear: Int): String? {
        val age = year?.let { currentYear - it } ?: return null
        return when {
            age >= 10 -> "Muito antigo"
            age >= 5 -> "Antigo"
            else -> null
        }
    }
}

fun reformPotentialLabel(potential: Float): String = when {
    potential >= 0.7f -> "Alto"
    potential >= 0.4f -> "Médio"
    else -> "Baixo"
}

private val NOT_ALNUM = Regex("[^a-z0-9]+")
private val ACCENTS = Regex("\\p{Mn}+")

private fun plain(text: String) = ACCENTS.replace(Normalizer.normalize(text.lowercase(), Normalizer.Form.NFD), "")
private fun tokens(plain: String) = plain.split(NOT_ALNUM)

/** Estado da tela 1. [selectedId] pré-seleciona um app ao voltar do Escopo. */
data class ReformAppsState(
    val apps: List<ReformApp> = emptyList(),
    val problem: ReformProblem? = null,
    val selectedId: String? = null,
)

// ---------- Reformar 2 · Escopo ----------

enum class ReformRisk(val label: String) {
    Low("Risco baixo"),
    Medium("Risco médio"),
    High("Risco maior"),
}

enum class ReformScope(
    val title: String,
    val minutes: Int,
    val risk: ReformRisk,
    val recommended: Boolean = false,
    val note: String? = null,
) {
    VisualOnly("Só visual", 10, ReformRisk.Low, note = "Rápido"),
    VisualAndFlows("Visual + fluxos", 25, ReformRisk.Medium, recommended = true),
    FullRebuild("Reconstrução completa", 90, ReformRisk.High, note = "Demora mais");

    val durationLabel: String get() = reformDurationLabel(minutes)
    val estimate: String get() = "Cerca de $durationLabel · usa só a sua assinatura"

    companion object {
        val default = VisualAndFlows
    }
}

fun reformDurationLabel(minutes: Int): String = when {
    minutes < 60 -> "$minutes min"
    minutes % 60 == 0 -> "${minutes / 60} h"
    else -> "${minutes / 60} h ${(minutes % 60).toString().padStart(2, '0')}"
}

/** O que o Escopo entrega à tela 3, incluindo o APK quando escolhido por arquivo. */
data class ReformPlan(
    val appId: String,
    val appLabel: String,
    val scope: ReformScope,
    val carMode: Boolean,
    val fidelityTargetPercent: Int,
    val apkUri: String? = null,
)

// ---------- Reformar 5 · Ferramentas ----------

enum class ReformToolStatus { Unknown, Ready, Installing, Queued, Missing, Failed }

data class ReformTool(
    val id: String,
    val label: String,
    val status: ReformToolStatus = ReformToolStatus.Unknown,
    val percent: Int? = null,
) {
    val chipText: String
        get() = when (status) {
            ReformToolStatus.Unknown -> "Não verificado"
            ReformToolStatus.Ready -> "Pronto"
            ReformToolStatus.Installing -> percent?.let { "Instalando $it%" } ?: "Instalando"
            ReformToolStatus.Queued -> "Na fila"
            ReformToolStatus.Missing -> "Falta instalar"
            ReformToolStatus.Failed -> "Falhou"
        }
}

/** As seis ferramentas da cadeia de reforma; a lista nunca encolhe, só muda o estado de cada uma. */
val REFORM_REQUIRED_TOOLS: List<ReformTool> = listOf(
    ReformTool("node", "Node.js 22"),
    ReformTool("jdk", "JDK 21"),
    ReformTool("jadx", "JADX sem tela"),
    ReformTool("apktool", "Apktool"),
    ReformTool("adb", "adb"),
    ReformTool("rea", "REA, agentes e servidor MCP"),
)

data class ReformSessionEntry(val time: String, val text: String, val active: Boolean = false)

/** O botão principal da tela 5 nunca fica mudo: instalar, esperar (desligado, dizendo o quê) ou voltar. */
enum class ReformToolsAction(val label: String) {
    Install("Instalar o que falta"),
    Busy("Instalando ferramentas"),
    Done("Voltar ao projeto"),
}

data class ReformToolsState(
    val tools: List<ReformTool> = emptyList(),
    val session: List<ReformSessionEntry> = emptyList(),
    val problem: ReformProblem? = null,
) {
    /** As seis exigidas (com o que o motor contou, por id) e depois as extras que ele trouxer. */
    val rows: List<ReformTool>
        get() {
            val reported = tools.associateBy { it.id }
            val required = REFORM_REQUIRED_TOOLS.map { reported[it.id] ?: it }
            return required + tools.filter { t -> REFORM_REQUIRED_TOOLS.none { it.id == t.id } }
        }

    val action: ReformToolsAction
        get() {
            val all = rows
            return when {
                all.any { it.status == ReformToolStatus.Installing || it.status == ReformToolStatus.Queued } ->
                    ReformToolsAction.Busy
                all.all { it.status == ReformToolStatus.Ready } -> ReformToolsAction.Done
                else -> ReformToolsAction.Install
            }
        }
}
