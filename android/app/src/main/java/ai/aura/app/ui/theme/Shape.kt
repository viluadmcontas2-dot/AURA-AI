package ai.aura.app.ui.theme

import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

object AuraShapes {
    val Card = RoundedCornerShape(AuraTokens.RADIUS_CARD.dp)
    val Chip = RoundedCornerShape(AuraTokens.RADIUS_CHIP.dp)
    val Button = RoundedCornerShape(AuraTokens.RADIUS_BUTTON.dp)
    val Sheet = RoundedCornerShape(topStart = AuraTokens.RADIUS_SHEET.dp, topEnd = AuraTokens.RADIUS_SHEET.dp)
    val Pill = RoundedCornerShape(percent = 50)
    val Circle = CircleShape
}

/** space: 4, 8, 12, 16, 20, 24, 32 */
object AuraSpacing {
    val S1: Dp = AuraTokens.space[0].dp
    val S2: Dp = AuraTokens.space[1].dp
    val S3: Dp = AuraTokens.space[2].dp
    val S4: Dp = AuraTokens.space[3].dp
    val S5: Dp = AuraTokens.space[4].dp
    val S6: Dp = AuraTokens.space[5].dp
    val S7: Dp = AuraTokens.space[6].dp
    val TouchTarget: Dp = AuraTokens.TOUCH_TARGET.dp
    val ScreenGutter: Dp = S5
}
