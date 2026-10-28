package ai.aura.app.ui.screens.reform

import androidx.compose.ui.test.assertTouchHeightIsAtLeast
import androidx.compose.ui.test.assertTouchWidthIsAtLeast
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.junit4.ComposeContentTestRule
import androidx.compose.ui.unit.dp

/** Regra do design e da CONSTRAINTS: todo clicável tem pelo menos 44 dp de alvo de toque. */
internal fun ComposeContentTestRule.assertEveryTapTargetIsAtLeast44dp() {
    val taps = onAllNodes(hasClickAction())
    val count = taps.fetchSemanticsNodes().size
    check(count > 0) { "a tela não tem nenhum alvo de toque" }
    for (i in 0 until count) taps[i].assertTouchHeightIsAtLeast(44.dp).assertTouchWidthIsAtLeast(44.dp)
}

/** Apps de exemplo só para os testes (nada disto vai para o APK). */
internal object ReformSamples {
    val player = ReformApp("com.exemplo.player", "Player de música", version = "2.1", year = 2016, potential = 0.88f)
    val gps = ReformApp("com.exemplo.gps", "Navegador GPS", version = "3.4", year = 2017, potential = 0.78f)
    val radio = ReformApp("com.exemplo.radio", "Rádio FM", version = "1.8", year = 2015, potential = 0.52f)
    val rearCamera = ReformApp("com.syu.camera360", "Câmera de ré")
}
