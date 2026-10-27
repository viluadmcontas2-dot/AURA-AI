package ai.aura.app.ui.theme

/**
 * Espelho de docs/design/tokens.json (fonte de verdade). Kotlin puro, sem Compose,
 * para poder ser conferido em teste JVM (TokensTest). Cores como ARGB opaco (0xFFRRGGBB).
 * Chaves de [colors] seguem o caminho do JSON sem o prefixo "color." (ex.: "status.ready.bg").
 */
object AuraTokens {
    val colors: Map<String, Long> = mapOf(
        "bg" to 0xFFF5F4F1, "surface" to 0xFFFFFFFF, "surfaceSunken" to 0xFFEEECE7,
        "line" to 0xFFE6E3DC, "lineStrong" to 0xFFD5D1C7,
        "ink" to 0xFF15141A, "ink2" to 0xFF4F4C57, "ink3" to 0xFF6A6772, "link" to 0xFF3340C9,
        "aura.periwinkle" to 0xFF8C9DFF, "aura.rose" to 0xFFFF9EB8,
        "aura.apricot" to 0xFFFFB48C, "aura.mint" to 0xFF79E6CF,
        "status.ready.bg" to 0xFFE3F4EA, "status.ready.fg" to 0xFF0F6B3C, "status.ready.dot" to 0xFF17A058,
        "status.needsYou.bg" to 0xFFFFF1D6, "status.needsYou.fg" to 0xFF8A4B00, "status.needsYou.dot" to 0xFFE08A00,
        "status.risk.bg" to 0xFFFDE4E4, "status.risk.fg" to 0xFFA31B2B, "status.risk.dot" to 0xFFD8344A,
        "status.risk.solid" to 0xFFC62C3F,
        "status.info.bg" to 0xFFE4ECFF, "status.info.fg" to 0xFF2848B8,
        "status.neutral.bg" to 0xFFECEAE5, "status.neutral.fg" to 0xFF4B4852,
        "category.engineering" to 0xFFE4E8FF, "category.clarity" to 0xFFFBEFC9,
        "category.android" to 0xFFD9F3E8, "category.organization" to 0xFFFADDE6,
        "category.lilac" to 0xFFEBE1FA, "category.apricot" to 0xFFFFE3D3,
        "dark.voice" to 0xFF0C0B12, "dark.backstage" to 0xFF0F0E14, "dark.panel" to 0xFF17161E,
        "dark.line" to 0xFF2A2833, "dark.text" to 0xFFF4F3F8, "dark.text2" to 0xFFA09EAA,
        "dark.focus" to 0xFF9AA9FF,
    )

    fun color(key: String): Long = colors.getValue(key)

    // radius (dp)
    const val RADIUS_CARD = 24
    const val RADIUS_CHIP = 16
    const val RADIUS_BUTTON = 22
    const val RADIUS_SHEET = 28
    const val RADIUS_PILL = 999

    /** space: [4, 8, 12, 16, 20, 24, 32] */
    val space: List<Int> = listOf(4, 8, 12, 16, 20, 24, 32)

    const val TOUCH_TARGET = 44
    const val ICON_SIZE = 24
    const val ICON_STROKE = 1.75f
}
