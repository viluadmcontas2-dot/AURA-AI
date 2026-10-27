package ai.aura.app.ui.screens

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import ai.aura.app.ui.engine.EngineGateway
import ai.aura.app.ui.engine.FakeEngineGateway
import ai.aura.app.ui.screens.projects.ProjectsHost
import ai.aura.app.ui.theme.AuraTheme

/**
 * Aba Projetos. Mantém a assinatura (gateway, modifier); o fluxo completo (lista, ideia, resumo,
 * detalhe, artefatos) vive em ui/screens/projects e navega por estado local.
 */
@Composable
fun ProjectsScreen(gateway: EngineGateway, modifier: Modifier = Modifier) {
    ProjectsHost(gateway, modifier)
}

@Preview(showBackground = true, backgroundColor = 0xFFF5F4F1)
@Composable
private fun ProjectsScreenPreview() {
    AuraTheme { ProjectsScreen(FakeEngineGateway()) }
}
