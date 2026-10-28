package ai.aura.app.ui.screens.onboarding

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** O comando que a pessoa cola no Termux: roda de verdade num bash, cria a pasta e não repete a linha. */
class TermuxCommandTest {
    @get:Rule val tmp = TemporaryFolder()

    private fun runPasted(home: File, bin: File): Int =
        ProcessBuilder("bash", "-c", TERMUX_ALLOW_EXTERNAL_COMMAND)
            .apply {
                environment()["HOME"] = home.path
                environment()["PATH"] = bin.path + ":" + System.getenv("PATH")
            }
            .redirectErrorStream(true).start().also { it.inputStream.readBytes() }.waitFor()

    @Test fun `o comando cria a pasta, escreve a linha uma vez so e recarrega os ajustes`() {
        val home = tmp.newFolder("home")
        val bin = tmp.newFolder("bin")
        val reloads = File(tmp.root, "reloads")
        File(bin, "termux-reload-settings").apply {
            writeText("#!/bin/sh\necho x >> '${reloads.path}'\n")
            setExecutable(true)
        }

        assertEquals(0, runPasted(home, bin)) // primeira vez: a pasta ~/.termux nem existe
        assertEquals(0, runPasted(home, bin)) // segunda vez: a linha já existe

        val lines = File(home, ".termux/termux.properties").readLines().filter { it.startsWith("allow-external-apps") }
        assertEquals(listOf("allow-external-apps=true"), lines)
        assertEquals(2, reloads.readLines().size)
    }

    @Test fun `o texto copiado cita pasta, linha e recarga`() {
        assertTrue(TERMUX_ALLOW_EXTERNAL_COMMAND.contains("mkdir -p ~/.termux"))
        assertTrue(TERMUX_ALLOW_EXTERNAL_COMMAND.contains("allow-external-apps=true"))
        assertTrue(TERMUX_ALLOW_EXTERNAL_COMMAND.contains("termux-reload-settings"))
    }
}
