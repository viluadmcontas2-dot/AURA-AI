package ai.aura.app.ui

import ai.aura.app.ui.theme.AuraTokens
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Garante que AuraTokens espelha docs/design/tokens.json (parse manual por regex, só junit). */
class TokensTest {
    private fun tokensFile(): File {
        var dir: File? = File("").absoluteFile
        while (dir != null) {
            val f = File(dir, "docs/design/tokens.json")
            if (f.isFile) return f
            dir = dir.parentFile
        }
        error("docs/design/tokens.json não encontrado a partir de ${File("").absolutePath}")
    }

    /** Achata o bloco "color" em caminho -> ARGB, ignorando valores que não são #RRGGBB (ex.: gradiente). */
    private fun parseColors(json: String): Map<String, Long> {
        val start = json.indexOf("\"color\"")
        require(start >= 0) { "bloco color ausente" }
        val token = Regex("\"([A-Za-z0-9]+)\"\\s*:\\s*(\\{|\"(#[0-9A-Fa-f]{6})\"|\"[^\"]*\")|\\}")
        val path = ArrayList<String>()
        val out = LinkedHashMap<String, Long>()
        var depth = 0
        var started = false
        for (m in token.findAll(json, start)) {
            if (m.value == "}") {
                depth--
                if (started) { if (depth == 0) break; path.removeAt(path.lastIndex) }
                continue
            }
            val key = m.groupValues[1]
            when {
                m.groupValues[2] == "{" -> {
                    depth++
                    if (!started) started = true // abertura do próprio "color"
                    else path.add(key)
                }
                m.groupValues[3].isNotEmpty() -> {
                    val full = (path + key).joinToString(".")
                    out[full] = 0xFF000000L or m.groupValues[3].substring(1).toLong(16)
                }
            }
        }
        return out
    }

    @Test
    fun colorsMatchTokensJson() {
        val expected = parseColors(tokensFile().readText())
        assertTrue("parse deveria achar cores", expected.size > 30)
        assertEquals("mesmas chaves", expected.keys, AuraTokens.colors.keys)
        for ((k, v) in expected) {
            assertEquals("cor $k", v, AuraTokens.colors.getValue(k))
        }
    }

    @Test
    fun scalarTokensMatch() {
        val json = tokensFile().readText()
        fun num(name: String) = Regex("\"$name\"\\s*:\\s*(\\d+)").find(json)!!.groupValues[1].toInt()
        assertEquals(num("card"), AuraTokens.RADIUS_CARD)
        assertEquals(num("chip"), AuraTokens.RADIUS_CHIP)
        assertEquals(num("button"), AuraTokens.RADIUS_BUTTON)
        assertEquals(num("sheet"), AuraTokens.RADIUS_SHEET)
        assertEquals(num("pill"), AuraTokens.RADIUS_PILL)
        assertEquals(num("touchTarget"), AuraTokens.TOUCH_TARGET)
        val space = Regex("\"space\"\\s*:\\s*\\[([^\\]]*)]").find(json)!!.groupValues[1]
            .split(",").map { it.trim().toInt() }
        assertEquals(space, AuraTokens.space)
    }
}
