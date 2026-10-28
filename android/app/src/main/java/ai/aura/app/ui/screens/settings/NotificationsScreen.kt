package ai.aura.app.ui.screens.settings

import ai.aura.app.notifications.AuraChannel
import ai.aura.app.notifications.AuraNotifier
import ai.aura.app.notifications.NotificationPrefs
import ai.aura.app.notifications.SystemBlock
import ai.aura.app.ui.components.AuraButtonPrimary
import ai.aura.app.ui.screens.projects.NoteBanner
import ai.aura.app.ui.screens.projects.PIcon
import ai.aura.app.ui.screens.projects.ProjHeader
import ai.aura.app.ui.theme.AuraColors
import ai.aura.app.ui.theme.AuraSpacing
import ai.aura.app.ui.theme.AuraType
import android.content.Context
import android.content.Intent
import android.provider.Settings
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver

/**
 * Ajustes, Notificações (UI-35): um interruptor por canal. Se o Android bloqueia os avisos, aparece UMA
 * ação que leva aos ajustes dele. Não usa o motor: abre sempre (regra nº 1).
 */
@Composable
fun NotificationsScreen(onBack: () -> Unit, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val notifier = remember { AuraNotifier(context) }
    val prefs = remember { NotificationPrefs(context) }
    val chosen = remember { mutableStateMapOf<AuraChannel, Boolean>() }

    // Voltou dos ajustes do Android: reconfere o que está bloqueado.
    var tick by remember { mutableIntStateOf(0) }
    val owner = LocalLifecycleOwner.current
    DisposableEffect(owner) {
        val observer = LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_RESUME) tick++ }
        owner.lifecycle.addObserver(observer)
        onDispose { owner.lifecycle.removeObserver(observer) }
    }
    val blocks = remember(tick) { AuraChannel.entries.associateWith { notifier.systemBlock(it) } }
    val blockedChannels = blocks.filterValues { it == SystemBlock.Channel }.keys
    val warning = when {
        blocks.values.any { it == SystemBlock.App } ->
            "Os avisos do AURA estão desligados no Android. Ligue lá para receber."
        blockedChannels.isNotEmpty() ->
            "O Android bloqueou: ${blockedChannels.joinToString { it.label }}. Ligue lá para voltar a receber."
        else -> null
    }

    Column(modifier.fillMaxSize().background(AuraColors.Bg).statusBarsPadding()) {
        ProjHeader("Notificações", onBack)
        Column(
            Modifier.weight(1f).verticalScroll(rememberScrollState())
                .padding(horizontal = AuraSpacing.ScreenGutter, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (warning != null) {
                NoteBanner(warning, AuraColors.NeedsYou, PIcon.Lock)
                AuraButtonPrimary("Abrir ajustes do Android", { openAndroidSettings(context) }, Modifier.fillMaxWidth())
            }
            AuraChannel.entries.forEach { channel ->
                SectionLabel(channel.label)
                SettingsGroup {
                    SwitchRow(
                        title = channel.switchTitle,
                        checked = chosen[channel] ?: prefs.isEnabled(channel),
                        onChange = { on ->
                            chosen[channel] = on
                            prefs.setEnabled(channel, on)
                        },
                        subtitle = channel.about,
                    )
                }
            }
            Text("Ações que apagam algo sempre abrem o app para você aprovar.", style = AuraType.SecondarySmall)
        }
    }
}

private fun openAndroidSettings(context: Context) {
    val intent = Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
        .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
    runCatching { context.startActivity(intent) }
}
