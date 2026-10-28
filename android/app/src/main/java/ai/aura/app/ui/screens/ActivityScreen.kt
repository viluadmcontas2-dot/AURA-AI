package ai.aura.app.ui.screens

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import ai.aura.app.ui.engine.EngineGateway
import ai.aura.app.ui.screens.activity.ActivityTab
import ai.aura.app.ui.theme.AuraTheme

/** Aba Atividade (implementação em ui/screens/activity). Assinatura fixa (gateway, modifier). */
@Composable
fun ActivityScreen(
    gateway: EngineGateway,
    modifier: Modifier = Modifier,
    onStartRequest: () -> Unit = {},
    onOpenJob: ((jobId: String) -> Unit)? = null,
) {
    ActivityTab(gateway, modifier, onStartRequest = onStartRequest, onOpenJob = onOpenJob)
}
