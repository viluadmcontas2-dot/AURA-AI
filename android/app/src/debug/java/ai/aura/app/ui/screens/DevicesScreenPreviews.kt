package ai.aura.app.ui.screens

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import ai.aura.app.ui.engine.EngineGateway
import ai.aura.app.ui.engine.FakeEngineGateway
import ai.aura.app.ui.screens.devices.DevicesTab
import ai.aura.app.ui.theme.AuraTheme

@Preview(showBackground = true, backgroundColor = 0xFFF5F4F1, heightDp = 800)
@Composable
private fun DevicesScreenPreview() {
    AuraTheme { DevicesScreen(FakeEngineGateway()) }
}
