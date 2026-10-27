package ai.aura.app.ui.components

import androidx.compose.foundation.border
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.dp
import ai.aura.app.ui.theme.AuraColors

/** Anel de foco visível (2 dp, cor de link), regra 5 do design. */
@Composable
internal fun Modifier.auraFocusRing(source: MutableInteractionSource, shape: Shape): Modifier {
    val focused by source.collectIsFocusedAsState()
    return if (focused) this.border(2.dp, AuraColors.Link, shape) else this
}
