package ai.aura.app.ui.screens

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import ai.aura.app.ui.engine.EngineGateway
import ai.aura.app.ui.engine.FakeEngineGateway
import ai.aura.app.ui.screens.devices.DevicesTab
import ai.aura.app.ui.theme.AuraTheme

/** Aba Dispositivos (lista, detalhe e conexões em ui/screens/devices). Assinatura fixa (gateway, modifier). */
@Composable
fun DevicesScreen(gateway: EngineGateway, modifier: Modifier = Modifier) {
    DevicesTab(gateway, modifier)
}

@Preview(showBackground = true, backgroundColor = 0xFFF5F4F1, heightDp = 800)
@Composable
private fun DevicesScreenPreview() {
    AuraTheme { DevicesScreen(FakeEngineGateway()) }
}
