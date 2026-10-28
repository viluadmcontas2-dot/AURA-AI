package ai.aura.app.e2e

import ai.aura.app.integration.AuraEngineClient
import ai.aura.app.integration.RealEngineGateway
import ai.aura.app.offline.LinkState
import ai.aura.app.offline.LocalStore
import ai.aura.app.ui.screens.chat.ChatEntry
import ai.aura.app.ui.screens.chat.ChatScreen
import ai.aura.app.ui.theme.AuraTheme
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import java.io.IOException
import java.net.ServerSocket
import java.nio.file.Files
import java.util.concurrent.TimeUnit

/**
 * ENTREGA 2 do plano mestre, provada de ponta a ponta: a pessoa digita na TELA do app, a mensagem passa pelo
 * cliente e gateway reais, pelo motor Python de verdade e por um `claude` falso (stream-json igual ao real),
 * e a resposta aparece no MESMO chat, em texto. Sem mock do motor. Sem python3 o teste é pulado.
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w390dp-h844dp")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ChatScreenE2ETest {
    @get:Rule val rule = createComposeRule()
    @get:Rule val tmp = TemporaryFolder()

    private var process: Process? = null
    private lateinit var home: File
    private lateinit var gateway: RealEngineGateway

    private fun engineDir(): File {
        var dir: File? = File("").absoluteFile
        while (dir != null) {
            if (File(dir, "engine/aura_engine/__main__.py").exists()) return File(dir, "engine")
            dir = dir.parentFile
        }
        error("Pasta engine/ não encontrada")
    }

    private fun hasPython() = try {
        val p = ProcessBuilder("python3", "--version").redirectErrorStream(true).start()
        p.inputStream.readBytes(); p.waitFor(10, TimeUnit.SECONDS) && p.exitValue() == 0
    } catch (_: IOException) { false }

    /** `claude` falso: mesmo JSONL do real. Responde com o que recebeu e diz se a conversa continuou (--resume). */
    private fun installFakeClaude(dir: File) {
        val script = File(dir, "claude")
        script.writeText(
            """#!/usr/bin/env python3
import json, sys
a = sys.argv[1:]
prompt = a[a.index("-p") + 1]
resumed = "--resume" in a
sid = a[a.index("--resume") + 1] if resumed else a[a.index("--session-id") + 1]
def out(o): print(json.dumps(o), flush=True)
text = "Ola! Recebi: " + prompt + (" (continuando a conversa)" if resumed else " (conversa nova)")
out({"type": "system", "subtype": "init", "session_id": sid})
for piece in (text[:6], text[6:]):
    out({"type": "stream_event", "session_id": sid, "event": {"type": "content_block_delta", "index": 0, "delta": {"type": "text_delta", "text": piece}}})
out({"type": "assistant", "session_id": sid, "message": {"content": [{"type": "text", "text": text}]}})
out({"type": "result", "subtype": "success", "is_error": False, "session_id": sid, "result": text, "subagent_stats": {"spawned": 0}, "ttft_ms": 5})
""",
        )
        script.setExecutable(true)
    }

    @Before
    fun start() {
        assumeTrue("python3 ausente: teste do motor real pulado", hasPython())
        home = Files.createTempDirectory("aura-chat-e2e").toFile()
        val bin = File(home, "bin").also { it.mkdirs() }
        installFakeClaude(bin)
        fun python(vararg args: String) = ProcessBuilder(listOf("python3") + args).directory(engineDir()).also {
            it.environment()["HOME"] = home.absolutePath
            it.environment()["PYTHONDONTWRITEBYTECODE"] = "1"
            it.environment()["PATH"] = bin.absolutePath + File.pathSeparator + System.getenv("PATH")
        }
        val pair = python("-m", "aura_engine.pair", "--emit-token").start()
        val token = JSONObject(pair.inputStream.bufferedReader().readText()).getString("token")
        pair.waitFor(20, TimeUnit.SECONDS)
        val port = ServerSocket(0).use { it.localPort }
        process = python("-m", "aura_engine", "--port", port.toString())
            .redirectErrorStream(true).redirectOutput(File(home, "engine.log")).start()
        val client = AuraEngineClient({ token }, "http://127.0.0.1:$port")
        gateway = RealEngineGateway(client)
        val deadline = System.currentTimeMillis() + 20_000
        while (true) {
            try { runBlocking { client.health() }; break } catch (_: IOException) {
                check(System.currentTimeMillis() < deadline) { "motor não subiu: " + File(home, "engine.log").readText().take(1500) }
                Thread.sleep(150)
            }
        }
    }

    @After
    fun stop() { process?.destroyForcibly(); home.deleteRecursively() }

    private fun has(text: String) = rule.onAllNodesWithText(text, substring = true).fetchSemanticsNodes().isNotEmpty()

    private fun waitText(text: String) {
        val end = System.currentTimeMillis() + 30_000
        while (!has(text)) {
            check(System.currentTimeMillis() < end) { "não apareceu na tela: $text" }
            rule.waitForIdle(); Thread.sleep(30)
        }
    }

    /** Uma pessoa só manda a próxima mensagem quando a resposta termina (o botão deixa de ser "Parar resposta"). */
    private fun waitAnswerFinished() {
        val end = System.currentTimeMillis() + 30_000
        while (rule.onAllNodesWithContentDescription("Parar resposta").fetchSemanticsNodes().isNotEmpty()) {
            check(System.currentTimeMillis() < end) { "a resposta não terminou" }
            rule.waitForIdle(); Thread.sleep(30)
        }
    }

    private fun send(text: String) {
        rule.onNodeWithContentDescription("Mensagem para o AURA").performTextInput(text)
        rule.onNodeWithContentDescription("Enviar mensagem").performClick()
    }

    @Test
    fun `digitar Ola mostra a resposta do Claude no mesmo chat e a conversa continua na segunda mensagem`() {
        rule.setContent {
            AuraTheme {
                ChatScreen(
                    gateway = gateway, entry = ChatEntry(open = true), onEntryChange = {}, onClose = {},
                    link = LinkState.Online, store = LocalStore(tmp.root),
                )
            }
        }
        send("Ola")
        waitText("Ola! Recebi: Ola (conversa nova)")
        for (lixo in listOf("Começar", "Ajustar pedido", "Por quê", "ENTENDI", "FALTA", "PRÓXIMO PASSO", "subagent_stats", "ttft_ms", "{\"type\"")) {
            assertFalse("não deveria aparecer: $lixo", has(lixo))
        }

        waitAnswerFinished()
        send("e agora")
        waitText("Ola! Recebi: e agora (continuando a conversa)")
        assertTrue(has("Ola! Recebi: Ola (conversa nova)")) // a primeira resposta continua no histórico
    }
}
