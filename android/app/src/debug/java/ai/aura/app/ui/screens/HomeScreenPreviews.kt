package ai.aura.app.ui.screens

import ai.aura.app.ui.engine.EngineGateway
import ai.aura.app.ui.engine.FakeEngineGateway
import ai.aura.app.ui.screens.chat.ChatEntry
import ai.aura.app.ui.screens.chat.ChatScreen
import ai.aura.app.ui.screens.home.HomeContent
import ai.aura.app.ui.theme.AuraTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview

@Preview(showBackground = true, backgroundColor = 0xFFF5F4F1, widthDp = 390, heightDp = 844)
@Composable
private fun HomeScreenPreview() {
    AuraTheme { HomeScreen(FakeEngineGateway()) }
}
