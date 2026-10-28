package ai.aura.app.ui.screens

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import ai.aura.app.ui.engine.EngineGateway
import ai.aura.app.ui.screens.projects.ProjectsHost
import ai.aura.app.ui.theme.AuraTheme

/**
 * Aba Projetos. Mantém a assinatura (gateway, modifier); o fluxo completo (lista, ideia, resumo,
 * detalhe, artefatos) vive em ui/screens/projects e navega por estado local.
 */
@Composable
fun ProjectsScreen(
    gateway: EngineGateway,
    modifier: Modifier = Modifier,
    onReformApp: (() -> Unit)? = null,
    /** Ideia vinda do chat ("/projeto ideia"): abre o passo da ideia já preenchida. */
    startIdea: String = "",
    onStartIdeaHandled: () -> Unit = {},
) {
    ProjectsHost(gateway, modifier, onReformApp = onReformApp, startIdea = startIdea, onStartIdeaHandled = onStartIdeaHandled)
}
