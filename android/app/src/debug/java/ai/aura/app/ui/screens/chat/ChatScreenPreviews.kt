package ai.aura.app.ui.screens.chat

import ai.aura.app.ui.components.AuraComposerField
import ai.aura.app.ui.components.EmptyState
import ai.aura.app.ui.engine.EngineGateway
import ai.aura.app.ui.engine.FakeEngineGateway
import ai.aura.app.ui.engine.JobStatus
import ai.aura.app.ui.theme.AuraColors
import ai.aura.app.ui.theme.AuraTheme
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp

@Preview(showBackground = true, backgroundColor = 0xFFF5F4F1, widthDp = 390, heightDp = 844)
@Composable
private fun ChatEmptyPreview() {
    AuraTheme { ChatScreen(FakeEngineGateway(), ChatEntry(open = true), {}, {}) }
}

@Preview(showBackground = true, backgroundColor = 0xFFF5F4F1, widthDp = 390, heightDp = 844)
@Composable
private fun ChatTrackingPreview() {
    AuraTheme { ChatScreen(FakeEngineGateway(), ChatEntry(open = true, jobId = "job_1"), {}, {}) }
}

@Preview(showBackground = true, backgroundColor = 0xFFF5F4F1, widthDp = 390, heightDp = 844)
@Composable
private fun ChatRequestPreview() {
    AuraTheme { ChatScreen(FakeEngineGateway(), ChatEntry(open = true, prompt = "Corrigir a tela de login"), {}, {}) }
}
