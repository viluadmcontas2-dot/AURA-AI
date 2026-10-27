package ai.aura.app.ui.screens

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import ai.aura.app.ui.components.EmptyState
import ai.aura.app.ui.engine.EngineGateway
import ai.aura.app.ui.engine.FakeEngineGateway
import ai.aura.app.ui.theme.AuraTheme

/** Placeholder: outro agente preenche esta aba. Mantenha a assinatura (gateway, modifier). */
@Composable
fun ActivityScreen(gateway: EngineGateway, modifier: Modifier = Modifier) {
    Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        EmptyState(
            title = "Nada aconteceu ainda",
            body = "Tudo que o AURA fizer aparece aqui, com prova.",
            actionText = "Pedir uma tarefa",
            onAction = {},
        )
    }
}

@Preview(showBackground = true, backgroundColor = 0xFFF5F4F1)
@Composable
private fun ActivityScreenPreview() {
    AuraTheme { ActivityScreen(FakeEngineGateway()) }
}
