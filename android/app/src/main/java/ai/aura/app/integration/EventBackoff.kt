package ai.aura.app.integration

/** Espera entre tentativas do polling de eventos: 1s, 2s, 4s, 8s, 15s (teto). Função pura. */
object EventBackoff {
    const val POLL_MS = 1200L
    const val MIN_MS = 1000L
    const val MAX_MS = 15000L

    fun delayMillis(failures: Int): Long {
        if (failures <= 1) return MIN_MS
        val shift = (failures - 1).coerceAtMost(4)
        return (MIN_MS shl shift).coerceAtMost(MAX_MS)
    }
}
