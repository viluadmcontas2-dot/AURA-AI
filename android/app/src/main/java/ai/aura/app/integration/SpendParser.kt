package ai.aura.app.integration

import ai.aura.app.ui.engine.SpendLine
import ai.aura.app.ui.engine.SpendSummary

/**
 * Converte as linhas de GET /v1/spend em [SpendSummary]. Função pura (sem org.json, sem Android),
 * para rodar em teste JVM. Valores ausentes, não numéricos, negativos ou não finitos viram null
 * ("não informado"): nunca inventa zero.
 */
object SpendParser {
    /** Chaves aceitas para a lista de linhas, na ordem de preferência. */
    val ROW_KEYS: List<String> = listOf("lines", "providers", "spend", "items")

    fun parse(rows: List<Map<String, Any?>>): SpendSummary {
        val lines = rows.mapNotNull { row ->
            val provider = text(row["provider"] ?: row["name"]) ?: return@mapNotNull null
            SpendLine(
                provider = provider,
                model = text(row["model"]),
                todayUsd = usd(row["today_usd"] ?: row["today"]),
                monthUsd = usd(row["month_usd"] ?: row["month"]),
            )
        }
        return SpendSummary(lines)
    }

    private fun text(value: Any?): String? =
        (value as? String)?.trim()?.takeIf { it.isNotEmpty() }?.take(80)

    private fun usd(value: Any?): Double? {
        val number = when (value) {
            is Number -> value.toDouble()
            is String -> value.trim().toDoubleOrNull()
            else -> null
        } ?: return null
        return number.takeIf { it.isFinite() && it >= 0.0 }
    }
}
