package ai.aura.app.permissions

import ai.aura.app.MainActivity
import ai.aura.app.ui.screens.permissions.PermissionRow
import ai.aura.app.ui.screens.permissions.PermissionSheet
import ai.aura.app.ui.screens.permissions.PermissionsScreen
import ai.aura.app.ui.theme.AuraTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onLast
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.io.File

private fun item(name: String) = AppPermissions.all.first { it.permission.endsWith(name) }

/** Tarefa 7: a tabela, o estado, a tela "Permissões" e a folha que explica antes de pedir. Sem motor e sem rede. */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w390dp-h844dp")
class PermissionsTest {
    @get:Rule
    val rule = createComposeRule()

    @Test
    fun `tabela - cada funcao tem permissao no manifesto, motivo de uma linha e o que acontece se negar`() {
        val manifesto = File(checkNotNull(System.getProperty("user.dir")), "src/main/AndroidManifest.xml").readText()
        assertEquals(
            setOf("android.permission.RECORD_AUDIO", "android.permission.POST_NOTIFICATIONS", "com.termux.permission.RUN_COMMAND"),
            AppPermissions.all.map { it.permission }.toSet(),
        )
        for (p in AppPermissions.all) {
            assertTrue("${p.function}: texto vazio ou em mais de uma linha", listOf(p.function, p.why, p.ifDenied).all { it.isNotBlank() && '\n' !in it })
            assertTrue("${p.permission} não está no manifesto", "\"${p.permission}\"" in manifesto)
        }
        // O aviso só vira pedido a partir do Android 13.
        assertEquals(2, AppPermissions.forSdk(32).size)
        assertEquals(3, AppPermissions.forSdk(33).size)
    }

    @Test
    fun `estado - concedida, pode pedir ou bloqueada depois de negar duas vezes`() {
        assertEquals(PermissionState.Granted, AppPermissions.state(granted = true, denials = 5))
        assertEquals(PermissionState.CanAsk, AppPermissions.state(granted = false, denials = 0))
        assertEquals(PermissionState.CanAsk, AppPermissions.state(granted = false, denials = 1))
        assertEquals(PermissionState.Blocked, AppPermissions.state(granted = false, denials = 2))
    }

    @Test
    fun `tela - estado em cada linha e UM botao que abre a folha ou os ajustes, nunca o pedido direto`() {
        val asked = mutableListOf<String>()
        var settings = 0
        rule.setContent {
            AuraTheme {
                PermissionsScreen(
                    rows = listOf(
                        PermissionRow(item("RECORD_AUDIO"), PermissionState.Granted),
                        PermissionRow(item("POST_NOTIFICATIONS"), PermissionState.CanAsk),
                        PermissionRow(item("RUN_COMMAND"), PermissionState.Blocked),
                    ),
                    onAsk = { asked += it.permission },
                    onOpenSettings = { settings++ },
                    onBack = {},
                )
            }
        }
        rule.onNodeWithText("Permitido").assertIsDisplayed()
        rule.onNodeWithText("Ainda não permitido").assertIsDisplayed()
        rule.onNodeWithText("Bloqueado").assertIsDisplayed()
        rule.onAllNodes(hasClickAction()).assertCountEquals(3) // Voltar + um botão por linha que precisa de correção
        rule.onNodeWithText("Abrir ajustes").performClick()
        assertEquals(1, settings)
        rule.onNodeWithText("Permitir").performClick()
        rule.onNodeWithText(item("POST_NOTIFICATIONS").why).assertIsDisplayed() // a folha explica antes
        assertTrue("pedido do sistema antes do Permitir da folha", asked.isEmpty())
        rule.onAllNodesWithText("Permitir").onLast().performClick()
        assertEquals(listOf("android.permission.POST_NOTIFICATIONS"), asked)
    }

    @Test
    fun `folha de cada permissao - explica, Agora nao nao pede e Permitir pede uma vez`() {
        var current by mutableStateOf(AppPermissions.all.first())
        val asked = mutableListOf<String>()
        var notNow = 0
        rule.setContent {
            AuraTheme { PermissionSheet(current, onAllow = { asked += current.permission }, onNotNow = { notNow++ }) }
        }
        for ((i, p) in AppPermissions.all.withIndex()) {
            current = p
            rule.waitForIdle()
            rule.onNodeWithText(p.function).assertIsDisplayed()
            rule.onNodeWithText(p.why).assertIsDisplayed()
            rule.onNodeWithText("Agora não").performClick()
            assertEquals(i + 1, notNow)
            assertTrue("Agora não não pode pedir nada", asked.isEmpty())
            rule.onNodeWithText("Permitir").performClick()
            assertEquals(listOf(p.permission), asked)
            asked.clear()
        }
    }
}

/** Boas-vindas (UI-04) abre sozinha: nenhum pedido do sistema ao abrir nem no primeiro toque. */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w390dp-h844dp")
class FirstLaunchTest {
    @get:Rule
    val rule = createAndroidComposeRule<MainActivity>()

    private fun assertNothingAsked() {
        val activity = shadowOf(rule.activity)
        assertNull("pedido de permissão do sistema", activity.lastRequestedPermission)
        assertNull("tela de ajustes aberta sem o usuário pedir", activity.nextStartedActivity)
    }

    @Test
    fun `ao abrir mostra Boas-vindas e, depois de Comecar, segue sem pedir nada`() {
        rule.onNodeWithText("Começar").assertIsDisplayed()
        rule.onNodeWithText("Seus arquivos ficam no seu celular.").assertIsDisplayed()
        assertNothingAsked()
        rule.onNodeWithText("Começar").performClick()
        rule.onNodeWithText("Conectar o Termux").assertIsDisplayed()
        assertNothingAsked()
    }
}
