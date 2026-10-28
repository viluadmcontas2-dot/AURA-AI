package ai.aura.app.ui.screens.chat

import ai.aura.app.ui.components.AuraButtonPrimary
import ai.aura.app.ui.components.AuraButtonSecondary
import ai.aura.app.ui.components.ResultBlocks
import ai.aura.app.ui.components.ResultBlocksData
import ai.aura.app.ui.components.StatusChip
import ai.aura.app.ui.components.StatusKind
import ai.aura.app.ui.engine.EngineEvent
import ai.aura.app.ui.engine.FakeEngineGateway
import ai.aura.app.ui.engine.Job
import ai.aura.app.ui.engine.JobRequest
import ai.aura.app.ui.engine.JobRisk
import ai.aura.app.ui.engine.JobStatus
import ai.aura.app.ui.engine.RouteResult
import ai.aura.app.ui.screens.home.AuraOrb
import ai.aura.app.ui.screens.home.rememberReducedMotion
import ai.aura.app.ui.theme.AuraColors
import ai.aura.app.ui.theme.AuraShapes
import ai.aura.app.ui.theme.AuraTheme
import ai.aura.app.ui.theme.AuraType
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

// ---- previews ------------------------------------------------------------------------------

private val sample = FakeEngineGateway.sampleJobs()

@Preview(showBackground = true, backgroundColor = PreviewBg)
@Composable
private fun RunningPreview() {
    AuraTheme { Column(Modifier.padding(20.dp)) { JobPanel(sample[0], emptyList(), false, false, {}, {}, {}, {}, {}) } }
}

@Preview(showBackground = true, backgroundColor = PreviewBg)
@Composable
private fun AwaitingPreview() {
    AuraTheme { Column(Modifier.padding(20.dp)) { JobPanel(sample[1], emptyList(), true, false, {}, {}, {}, {}, {}) } }
}

@Preview(showBackground = true, backgroundColor = PreviewBg)
@Composable
private fun CompletedPreview() {
    AuraTheme { Column(Modifier.padding(20.dp)) { JobPanel(sample[2], emptyList(), false, false, {}, {}, {}, {}, {}) } }
}

@Preview(showBackground = true, backgroundColor = PreviewBg)
@Composable
private fun FailedPreview() {
    AuraTheme { Column(Modifier.padding(20.dp)) { JobPanel(sample[3], emptyList(), false, false, {}, {}, {}, {}, {}) } }
}
