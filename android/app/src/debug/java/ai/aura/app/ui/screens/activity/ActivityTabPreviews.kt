package ai.aura.app.ui.screens.activity

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import ai.aura.app.ui.components.AuraButtonDestructive
import ai.aura.app.ui.components.AuraButtonPrimary
import ai.aura.app.ui.components.AuraButtonSecondary
import ai.aura.app.ui.components.AuraButtonText
import ai.aura.app.ui.components.AuraCard
import ai.aura.app.ui.components.AuraFilterChip
import ai.aura.app.ui.components.AuraProgressBar
import ai.aura.app.ui.components.EmptyState
import ai.aura.app.ui.components.StatusChip
import ai.aura.app.ui.engine.EngineEvent
import ai.aura.app.ui.engine.EngineGateway
import ai.aura.app.ui.engine.FakeEngineGateway
import ai.aura.app.ui.engine.Job
import ai.aura.app.ui.engine.JobRisk
import ai.aura.app.ui.engine.JobStatus
import ai.aura.app.ui.screens.devices.SectionTitle
import ai.aura.app.ui.screens.devices.TabHeader
import ai.aura.app.ui.theme.AuraColors
import ai.aura.app.ui.theme.AuraShapes
import ai.aura.app.ui.theme.AuraSpacing
import ai.aura.app.ui.theme.AuraTheme
import ai.aura.app.ui.theme.AuraType
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

private fun previewJobs(now: Long) = FakeEngineGateway.sampleJobs(now) + listOf(
    Job("job_5", "req_5", "Sincronizar Drive", "system", JobStatus.Interrupted, JobRisk.Read, now - 8_000_000),
    Job("job_6", "req_6", "Apagar builds antigos", "files", JobStatus.Cancelled, JobRisk.Critical, now - 90_000_000),
)

@Preview(showBackground = true, backgroundColor = 0xFFF5F4F1, heightDp = 900)
@Composable
private fun ActivityPreview() {
    val now = 1_700_000_000_000L
    AuraTheme {
        ActivityContent(
            jobs = previewJobs(now), loadFailed = false,
            events = listOf(EngineEvent(1, "job.progress", "job_1", mapOf("step" to "3", "total" to "4", "label" to "Gerar o APK"), now)),
            now = now, actionError = null, onApprove = {}, onRefuse = {}, onCancel = {}, onReload = {},
            onRetry = {}, onStartRequest = {},
        )
    }
}
