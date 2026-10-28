package ai.aura.app.ui.screens.projects

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import ai.aura.app.ui.components.AuraButtonPrimary
import ai.aura.app.ui.components.AuraButtonSecondary
import ai.aura.app.ui.components.AuraCard
import ai.aura.app.ui.components.AuraComposer
import ai.aura.app.ui.components.AuraFilterChip
import ai.aura.app.ui.components.StatusChip
import ai.aura.app.ui.components.StatusKind
import ai.aura.app.ui.engine.Job
import ai.aura.app.ui.engine.JobRisk
import ai.aura.app.ui.engine.JobStatus
import ai.aura.app.ui.theme.AuraColors
import ai.aura.app.ui.theme.AuraShapes
import ai.aura.app.ui.theme.AuraTheme
import ai.aura.app.ui.theme.AuraType

@Preview(showBackground = true, backgroundColor = 0xFFF5F4F1, heightDp = 820)
@Composable
private fun DetailNeedsYouPreview() {
    val p = ExampleData.tv
    AuraTheme { ProjectDetailScreen(p, phaseOf(p, ExampleData.jobMap), listOf(ExampleData.jobs[1]), emptyList(), false, null, {}, {}, {}, {}, {}, {}) }
}

@Preview(showBackground = true, backgroundColor = 0xFFF5F4F1, heightDp = 820)
@Composable
private fun DetailRunningPreview() {
    val p = ExampleData.financas
    AuraTheme { ProjectDetailScreen(p, phaseOf(p, ExampleData.jobMap), listOf(ExampleData.jobs[0], ExampleData.jobs[2]), ExampleData.artifacts, false, null, {}, {}, {}, {}, {}, {}) }
}
