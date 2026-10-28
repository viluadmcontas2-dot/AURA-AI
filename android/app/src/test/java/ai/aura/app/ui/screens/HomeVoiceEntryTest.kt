package ai.aura.app.ui.screens

import ai.aura.app.ui.screens.home.HomeData
import ai.aura.app.ui.screens.home.HomeView
import ai.aura.app.ui.theme.AuraTheme
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class HomeVoiceEntryTest {
    @get:Rule val compose = createComposeRule()

    @Test fun `microfone do composer e orbe abrem a voz em tela cheia`() {
        var opened = 0
        compose.setContent {
            AuraTheme { HomeView(HomeData.Loading, "", {}, {}, {}, {}, onOpenVoice = { opened++ }) }
        }
        compose.onNodeWithContentDescription("Falar um pedido pelo microfone").performClick()
        compose.onNodeWithContentDescription("Falar com o AURA").performClick()
        compose.onNodeWithText("Toque para falar").assertExists()
        assertEquals(2, opened)
    }

    @Test fun `sem a rota de voz nao ha microfone no composer`() {
        compose.setContent { AuraTheme { HomeView(HomeData.Loading, "", {}, {}, {}, {}) } }
        compose.onNodeWithContentDescription("Falar um pedido pelo microfone").assertDoesNotExist()
    }
}
