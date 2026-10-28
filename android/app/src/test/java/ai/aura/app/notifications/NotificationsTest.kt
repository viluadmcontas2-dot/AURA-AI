package ai.aura.app.notifications

import ai.aura.app.ui.engine.EngineGateway
import ai.aura.app.ui.engine.FakeEngineGateway
import ai.aura.app.ui.engine.Job
import ai.aura.app.ui.engine.JobRisk
import ai.aura.app.ui.engine.JobStatus
import ai.aura.app.ui.screens.settings.NotificationsScreen
import ai.aura.app.ui.theme.AuraTheme
import android.Manifest
import android.app.Application
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.os.Looper
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertWidthIsAtLeast
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import java.util.concurrent.TimeUnit

/** Motor falso que só anota o que foi pedido (o resto vem do FakeEngineGateway). */
private class SpyGateway : EngineGateway by FakeEngineGateway(seed = emptyList()) {
    val approved = mutableListOf<String>()
    val cancelled = mutableListOf<String>()
    var down = false

    private fun job(id: String) = Job(id, "r-$id", "Tarefa", "system", JobStatus.Running, JobRisk.Write, 0L)

    override suspend fun approve(id: String): Job {
        check(!down) { "motor fora do ar" }
        approved += id
        return job(id)
    }

    override suspend fun cancel(id: String): Job {
        check(!down) { "motor fora do ar" }
        cancelled += id
        return job(id)
    }
}

/**
 * Notificações (Tarefa 12a): canais, avisos e ações, serviço de primeiro plano e a tela de Ajustes.
 * Cobre UI-35 e FN-13 do design. Roda na JVM, sem emulador.
 */
@RunWith(RobolectricTestRunner::class)
class NotificationsTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    private val app: Application get() = ApplicationProvider.getApplicationContext()
    private val manager: NotificationManager get() = app.getSystemService(NotificationManager::class.java)

    private fun spec(channel: AuraChannel, id: Int = 7, destructive: Boolean = false) =
        NotificationSpec(id, channel, "Corrigir tela de login", "Passo 3 de 4", "j1", destructive)

    @Test
    fun `os quatro canais nascem com o nome e o peso do design`() {
        NotificationChannels.ensure(app)
        val criados = AuraChannel.entries.map { manager.getNotificationChannel(it.id) }
        assertEquals(
            listOf("Enquanto trabalha", "Precisa de você", "Pronto", "Ouvindo em segundo plano"),
            criados.map { it.name.toString() },
        )
        assertEquals(
            listOf(
                NotificationManager.IMPORTANCE_LOW, NotificationManager.IMPORTANCE_HIGH,
                NotificationManager.IMPORTANCE_DEFAULT, NotificationManager.IMPORTANCE_LOW,
            ),
            criados.map { it.importance },
        )
    }

    @Test
    fun `cada canal monta o aviso do design com as acoes certas`() {
        val esperado = mapOf(
            AuraChannel.Working to listOf("Pausar", "Abrir"),
            AuraChannel.NeedsYou to listOf("Aprovar", "Ver"),
            AuraChannel.Done to listOf("Ver resultado"),
            AuraChannel.Listening to listOf("Pausar", "Parar"),
        )
        for ((canal, botoes) in esperado) {
            val aviso = AuraNotifier(app).build(spec(canal))
            assertEquals(canal.id, aviso.channelId)
            assertEquals("Corrigir tela de login", aviso.extras.getCharSequence(Notification.EXTRA_TITLE)?.toString())
            assertEquals(botoes, aviso.actions.map { it.title.toString() })
            // Fixo só enquanto o AURA trabalha ou escuta.
            val fixo = (aviso.flags and Notification.FLAG_ONGOING_EVENT) != 0
            assertEquals(canal == AuraChannel.Working || canal == AuraChannel.Listening, fixo)
        }
    }

    @Test
    fun `aprovar abre o app quando apaga ou gasta e Ver sempre abre o app`() {
        fun acao(destrutivo: Boolean, titulo: String) = shadowOf(
            AuraNotifier(app).build(spec(AuraChannel.NeedsYou, destructive = destrutivo))
                .actions.first { it.title.toString() == titulo }.actionIntent,
        )
        assertTrue("Aprovar sem risco vai ao receptor", acao(false, "Aprovar").isBroadcast)
        val apaga = acao(true, "Aprovar")
        assertTrue("Aprovar de algo que apaga abre o app", apaga.isActivity)
        assertEquals("approval", apaga.savedIntent.getStringExtra(NotificationIntents.EXTRA_DESTINATION))
        assertEquals("j1", apaga.savedIntent.getStringExtra(NotificationIntents.EXTRA_JOB_ID))
        assertTrue(acao(false, "Ver").isActivity)
    }

    @Test
    fun `o aviso so aparece com o interruptor ligado e liberado no Android, e o servico idem`() {
        val notifier = AuraNotifier(app)
        assertTrue(notifier.post(spec(AuraChannel.NeedsYou, id = 3)))
        assertEquals(1, shadowOf(manager).size())

        NotificationPrefs(app).setEnabled(AuraChannel.NeedsYou, false)
        assertFalse("interruptor desligado", notifier.post(spec(AuraChannel.NeedsYou, id = 4)))
        NotificationPrefs(app).setEnabled(AuraChannel.NeedsYou, true)

        // O dono bloqueou só este canal nos ajustes do Android.
        manager.createNotificationChannel(
            NotificationChannel(AuraChannel.NeedsYou.id, "Precisa de você", NotificationManager.IMPORTANCE_NONE),
        )
        assertEquals(SystemBlock.Channel, notifier.systemBlock(AuraChannel.NeedsYou))
        assertFalse(notifier.post(spec(AuraChannel.NeedsYou, id = 5)))

        // Microfone sem permissão: a escuta não começa.
        shadowOf(app).denyPermissions(Manifest.permission.RECORD_AUDIO)
        assertEquals(StartResult.NoMicrophone, AuraForegroundService.start(app, spec(AuraChannel.Listening)))
        // Avisos desligados no Android: nenhum serviço sobe.
        shadowOf(manager).setNotificationsEnabled(false)
        assertEquals(SystemBlock.App, notifier.systemBlock(AuraChannel.Done))
        assertEquals(StartResult.NotificationsOff, AuraForegroundService.start(app, spec(AuraChannel.Working)))
        assertNull(shadowOf(app).nextStartedService)
    }

    @Test
    fun `aprovar e parar chamam o motor, e o que apaga so e recusado`() {
        val motor = SpyGateway()
        var parouEscuta = false
        fun pedido(tipo: ActionKind, canal: AuraChannel, destrutivo: Boolean = false) =
            ActionRequest(tipo, "j1", 7, canal, destrutivo)

        runBlocking {
            assertTrue(runAction(pedido(ActionKind.Approve, AuraChannel.NeedsYou), motor) { parouEscuta = true })
            assertEquals(listOf("j1"), motor.approved)

            assertFalse(runAction(pedido(ActionKind.Approve, AuraChannel.NeedsYou, destrutivo = true), motor) {})
            assertEquals("recusado: não chamou o motor de novo", listOf("j1"), motor.approved)

            assertTrue(runAction(pedido(ActionKind.Pause, AuraChannel.Working), motor) {})
            assertEquals(listOf("j1"), motor.cancelled)

            assertTrue(runAction(pedido(ActionKind.Stop, AuraChannel.Listening), motor) { parouEscuta = true })
            assertTrue(parouEscuta)
            assertEquals("parar a escuta não toca no motor", listOf("j1"), motor.cancelled)

            motor.down = true
            assertFalse("sem motor: o aviso fica", runAction(pedido(ActionKind.Approve, AuraChannel.NeedsYou), motor) {})
        }
    }

    @Test
    fun `o servico sobe em primeiro plano com o aviso e para sozinho no tempo-limite`() {
        shadowOf(app).grantPermissions(Manifest.permission.RECORD_AUDIO)
        val aviso = spec(AuraChannel.Working, id = 9)
        val servico = Robolectric.buildService(
            AuraForegroundService::class.java, AuraForegroundService.intentFor(app, aviso),
        ).create().startCommand(0, 1).get()
        val sombra = shadowOf(servico)
        assertEquals(9, sombra.lastForegroundNotificationId)
        assertEquals(AuraChannel.Working.id, sombra.lastForegroundNotification.channelId)

        val relogio = shadowOf(Looper.getMainLooper())
        relogio.idleFor(WORKING_TIMEOUT_MILLIS - 1, TimeUnit.MILLISECONDS)
        assertFalse("ainda dentro do limite", sombra.isStoppedBySelf)
        relogio.idleFor(2, TimeUnit.MILLISECONDS)
        assertTrue("passou do limite: para sozinho", sombra.isStoppedBySelf)
    }

    @Test
    fun `a tela mostra os quatro canais, o interruptor guarda a escolha e os alvos tem 44 dp`() {
        compose.setContent { AuraTheme { NotificationsScreen(onBack = {}) } }
        AuraChannel.entries.forEach { compose.onNodeWithText(it.label, ignoreCase = true).assertExists() }
        compose.onNodeWithText("Ações que apagam algo sempre abrem o app para você aprovar.").assertExists()

        compose.onNodeWithText(AuraChannel.NeedsYou.switchTitle).performScrollTo().performClick()
        assertFalse(NotificationPrefs(app).isEnabled(AuraChannel.NeedsYou))
        compose.onNodeWithText(AuraChannel.NeedsYou.switchTitle).assertIsOff()

        compose.onNodeWithContentDescription("Voltar").assertHeightIsAtLeast(44.dp).assertWidthIsAtLeast(44.dp)
        compose.onNodeWithText(AuraChannel.Done.switchTitle).assertHeightIsAtLeast(44.dp)
    }

    @Test
    fun `bloqueado no Android mostra UMA acao que abre os ajustes`() {
        shadowOf(manager).setNotificationsEnabled(false)
        compose.setContent { AuraTheme { NotificationsScreen(onBack = {}) } }
        compose.onAllNodesWithText("Abrir ajustes do Android").assertCountEquals(1)
        compose.onNodeWithText("Abrir ajustes do Android").assertHeightIsAtLeast(44.dp).performClick()

        // O último pedido de tela é o do clique (a tela de teste pode ter registrado o próprio lançamento antes).
        val aberto = generateSequence { shadowOf(app).nextStartedActivity }.last()
        assertEquals(Settings.ACTION_APP_NOTIFICATION_SETTINGS, aberto.action)
        assertEquals(app.packageName, aberto.getStringExtra(Settings.EXTRA_APP_PACKAGE))
    }
}
