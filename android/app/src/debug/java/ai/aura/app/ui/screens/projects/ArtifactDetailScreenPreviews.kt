package ai.aura.app.ui.screens.projects

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import ai.aura.app.ui.components.AuraButtonPrimary
import ai.aura.app.ui.components.AuraButtonSecondary
import ai.aura.app.ui.components.AuraButtonText
import ai.aura.app.ui.components.StatusChip
import ai.aura.app.ui.components.StatusKind
import ai.aura.app.ui.theme.AuraColors
import ai.aura.app.ui.theme.AuraShapes
import ai.aura.app.ui.theme.AuraTheme
import ai.aura.app.ui.theme.AuraType

@Preview(showBackground = true, backgroundColor = 0xFFF5F4F1, heightDp = 860)
@Composable
private fun ArtifactDetailPreview() {
    AuraTheme { ArtifactDetailScreen(ExampleData.artifacts[1], "Minhas Finanças", null, null, false, {}, {}, {}, {}, {}, {}) }
}

@Preview(showBackground = true, backgroundColor = 0xFFF5F4F1, heightDp = 560)
@Composable
private fun ArtifactDetailPlainPreview() {
    AuraTheme { ArtifactDetailScreen(ExampleData.artifacts[4], "Minhas Finanças", null, null, false, {}, {}, {}, {}, {}, {}) }
}
