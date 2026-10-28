package ai.aura.app.ui.screens.backstage

import ai.aura.app.ui.AuraTab
import ai.aura.app.ui.engine.EngineEvent
import ai.aura.app.ui.engine.Job
import ai.aura.app.ui.nav.ScreenEnv
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.foundation.layout.fillMaxSize
import kotlinx.coroutines.CancellationException

/** Bastidores no shell: pedidos e eventos reais do motor. Sem motor abre vazio (o vazio convida a pedir algo). */
@Composable
fun BackstageRoute(env: ScreenEnv) {
    var jobs by remember { mutableStateOf<List<Job>>(emptyList()) }
    var events by remember { mutableStateOf<List<EngineEvent>>(emptyList()) }
    val clipboard = LocalClipboardManager.current
    LaunchedEffect(env.gateway) {
        suspend fun load() {
            jobs = try {
                env.gateway.jobs()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                jobs
            }
        }
        load()
        try {
            env.gateway.events.collect { e ->
                events = (events + e).takeLast(200)
                load()
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // Motor fora do ar: fica o que já foi lido.
        }
    }
    BackstageScreen(
        jobs = jobs, events = events, onBack = env.back,
        onCopy = { clipboard.setText(AnnotatedString(it)) },
        onRerun = { env.sendPrompt(it.title) },
        onExplain = { env.sendPrompt("Explique o que você fez em: ${it.title}") },
        onAsk = { env.openTab(AuraTab.Home) },
        modifier = Modifier.fillMaxSize(),
    )
}
