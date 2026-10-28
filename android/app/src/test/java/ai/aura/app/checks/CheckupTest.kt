package ai.aura.app.checks

import ai.aura.app.offline.TermuxProbe
import ai.aura.app.ui.engine.AuthState
import ai.aura.app.ui.engine.AuthStatus
import ai.aura.app.ui.screens.checkup.CheckupScreen
import ai.aura.app.ui.screens.onboarding.TERMUX_ALLOW_EXTERNAL_COMMAND
import ai.aura.app.ui.theme.AuraTheme
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.IOException

private const val GB = 1024L * 1024 * 1024
private const val NOTICE = "Este Android usa páginas de 16 KB e o Termux atual não roda aqui"

/** O mundo de cada teste: o padrão é tudo certo; cada argumento estraga uma coisa. Sem motor, o que passa por ele falha de verdade. */
private fun world(
    installed: Boolean = true,
    permission: Boolean = true,
    engine: Boolean = true,
    accounts: List<AuthStatus> = listOf(AuthStatus("claude", AuthState.Connected)),
    adb: Boolean = true,
    notifications: Boolean = true,
    free: Long = 14 * GB,
    battery: Boolean = true,
    sdk: Int = 30,
    pageSize: Int = 4096,
): List<Check> = standardChecks(
    termux = object : TermuxProbe {
        override fun installed() = installed
        override fun hasPermission() = permission
    },
    engineUp = { engine },
    accounts = { if (engine) accounts else throw IOException("sem motor") },
    adbInstalled = { if (engine) adb else throw IOException("sem motor") },
    notificationsOn = { notifications },
    freeBytes = { free },
    ignoresBatteryLimits = { battery },
    sdk = sdk,
    pageSize = { pageSize },
)

class ChecksTest {
    private class Case(
        val id: String,
        val checks: List<Check>,
        val state: CheckState,
        val fix: String? = null,
        val command: String? = null,
        val has: String = "",
    )

    @Test fun `cada linha em cada estado devolve o estado, a mensagem e a unica acao certos`() = runTest {
        val cases = listOf(
            Case("termux", world(), CheckState.Ok),
            Case("termux", world(installed = false), CheckState.Failed, "Instalar o Termux"),
            Case("termux_compat", world(), CheckState.Ok),
            Case("termux_compat", world(pageSize = 16384), CheckState.Failed, "Copiar aviso", NOTICE, NOTICE),
            Case("run_command", world(), CheckState.Ok),
            Case("run_command", world(permission = false), CheckState.Failed, "Permitir o Termux"),
            Case("external_apps", world(), CheckState.Ok),
            Case("external_apps", world(engine = false), CheckState.Warning, "Copiar comando", TERMUX_ALLOW_EXTERNAL_COMMAND),
            Case("engine", world(), CheckState.Ok),
            Case("engine", world(engine = false), CheckState.Failed, "Iniciar o motor"),
            Case("accounts", world(), CheckState.Ok, has = "Claude"),
            Case("accounts", world(accounts = listOf(AuthStatus("claude", AuthState.Disconnected))), CheckState.Warning, "Conectar contas"),
            Case("accounts", world(engine = false), CheckState.Warning, RETRY),
            Case("notifications", world(), CheckState.Ok),
            Case("notifications", world(notifications = false), CheckState.Warning, "Permitir notificações"),
            Case("storage", world(), CheckState.Ok, has = "14 GB"),
            Case("storage", world(free = GB), CheckState.Warning, "Liberar espaço"),
            Case("battery", world(), CheckState.Ok),
            Case("battery", world(battery = false), CheckState.Warning, "Liberar bateria"),
            Case("adb", world(), CheckState.Ok),
            Case("adb", world(adb = false), CheckState.Warning, "Copiar comando", "pkg install -y android-tools"),
            Case("adb", world(engine = false), CheckState.Warning, RETRY),
            Case("long_tasks", world(sdk = 30), CheckState.Ok),
            Case("long_tasks", world(sdk = 34), CheckState.Warning, "Ver como evitar"),
        )
        for (c in cases) {
            val r = c.checks.first { it.id == c.id }.safeRun()
            val onde = "${c.id} / ${c.state}"
            assertEquals("$onde: estado", c.state, r.state)
            assertEquals("$onde: unica acao", c.fix, r.fix)
            assertEquals("$onde: comando", c.command, r.command)
            assertTrue("$onde: mensagem", r.message.isNotBlank() && r.message.contains(c.has))
        }
    }

    @Test fun `verificacao que quebra ou demora vira nao consegui verificar e nunca lanca`() = runTest {
        val quebra = Check("a", "Termux") { error("falhou") }
        val demora = Check("b", "Motor") { delay(60_000); CheckResult(CheckState.Ok, "tarde demais") }
        for (c in listOf(quebra, demora)) {
            val r = c.safeRun()
            assertEquals(CheckState.Warning, r.state)
            assertEquals(RETRY, r.fix)
        }
    }
}

@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w390dp-h1800dp")
class CheckupScreenTest {
    @get:Rule val rule = createComposeRule()

    private val fixed = mutableListOf<String>()

    private fun show(checks: List<Check>) {
        rule.setContent { AuraTheme { CheckupScreen(checks, onFix = { fixed.add(it) }, onBack = {}) } }
        rule.waitForIdle()
    }

    private fun tap(label: String, expectedId: String) {
        rule.onNodeWithText(label).performScrollTo().performClick()
        assertEquals(expectedId, fixed.last())
    }

    private fun copied(): String? {
        val clipboard = ApplicationProvider.getApplicationContext<Context>().getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        return clipboard.primaryClip?.getItemAt(0)?.text?.toString()
    }

    @Test fun `abre sem motor e mostra o placar e cada linha`() {
        show(world(engine = false, battery = false, sdk = 34))

        rule.onNodeWithText("Checkup do ambiente").assertExists()
        rule.onNodeWithText("5 de 11 ok").assertExists()
        rule.onNodeWithText("Resolva 6 alertas para o AURA trabalhar sem pausas.").assertExists()
        rule.onNodeWithText("Termux instalado").assertExists()
        rule.onNodeWithText("O motor do AURA está desligado.").assertExists()
        rule.onNodeWithText("Bateria: o Android pode pausar o AURA em segundo plano").assertExists()
        assertTrue(rule.onAllNodesWithText("Verificar de novo").fetchSemanticsNodes().isNotEmpty())
    }

    @Test fun `cada botao de correcao leva ao destino certo e os de copiar copiam`() {
        show(world(installed = false, permission = false, engine = false, notifications = false, free = GB, battery = false, sdk = 34, pageSize = 16384))

        tap("Instalar o Termux", "termux")
        tap("Permitir o Termux", "run_command")
        tap("Iniciar o motor", "engine")
        tap("Permitir notificações", "notifications")
        tap("Liberar espaço", "storage")
        tap("Liberar bateria", "battery")
        tap("Ver como evitar", "long_tasks")

        rule.onNodeWithText("Copiar comando").performScrollTo().performClick()
        assertEquals(TERMUX_ALLOW_EXTERNAL_COMMAND, copied())
        rule.onNodeWithText("Copiar aviso").performScrollTo().performClick()
        assertEquals(NOTICE, copied())
        assertEquals(2, rule.onAllNodesWithText("Copiado").fetchSemanticsNodes().size)
    }

    @Test fun `com o motor no ar, contas levam ao destino e o ADB copia o comando`() {
        show(world(accounts = emptyList(), adb = false))

        rule.onNodeWithText("9 de 11 ok").assertExists()
        tap("Conectar contas", "accounts")
        rule.onNodeWithText("Copiar comando").performScrollTo().performClick()
        assertEquals("pkg install -y android-tools", copied())
    }
}
