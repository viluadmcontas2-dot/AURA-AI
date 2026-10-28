package ai.aura.app.ui.screens.permissions

import ai.aura.app.permissions.AppPermission
import ai.aura.app.permissions.AppPermissions
import ai.aura.app.permissions.PermissionState
import ai.aura.app.ui.components.AuraButtonPrimary
import ai.aura.app.ui.components.AuraButtonSecondary
import ai.aura.app.ui.components.AuraButtonText
import ai.aura.app.ui.components.AuraCard
import ai.aura.app.ui.components.StatusChip
import ai.aura.app.ui.components.StatusKind
import ai.aura.app.ui.components.auraFocusRing
import ai.aura.app.ui.theme.AuraColors
import ai.aura.app.ui.theme.AuraShapes
import ai.aura.app.ui.theme.AuraSpacing
import ai.aura.app.ui.theme.AuraType
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver

/** Uma linha da tela: a função e o estado dela agora. */
data class PermissionRow(val item: AppPermission, val state: PermissionState)

/**
 * Ajustes > Permissões. Não usa o motor: só mostra o que o Android já sabe.
 * Cada linha tem o estado e, se precisar de correção, UM botão: "Permitir" (abre a folha, o sistema só é
 * perguntado depois dela) ou "Abrir ajustes" (negada duas vezes: o Android não pergunta mais).
 */
@Composable
fun PermissionsScreen(
    rows: List<PermissionRow>,
    onAsk: (AppPermission) -> Unit,
    onOpenSettings: () -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var sheetFor by rememberSaveable { mutableStateOf<String?>(null) } // nome da permissão com a folha aberta
    val sheetItem = rows.firstOrNull { it.item.permission == sheetFor && it.state == PermissionState.CanAsk }?.item
    BackHandler(enabled = sheetItem != null) { sheetFor = null }
    Box(modifier.fillMaxSize().background(AuraColors.Bg)) {
        Column(Modifier.fillMaxSize().statusBarsPadding()) {
            Header(onBack)
            Column(
                Modifier.weight(1f).verticalScroll(rememberScrollState())
                    .padding(horizontal = AuraSpacing.ScreenGutter, vertical = 4.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text("O AURA só pede quando você usa a função. Aqui você vê o que está liberado.", style = AuraType.Secondary)
                rows.forEach { row ->
                    PermissionCard(row, onAllow = { sheetFor = row.item.permission }, onOpenSettings = onOpenSettings)
                }
            }
        }
        if (sheetItem != null) {
            PermissionSheet(sheetItem, onAllow = { sheetFor = null; onAsk(sheetItem) }, onNotNow = { sheetFor = null })
        }
    }
}

@Composable
private fun Header(onBack: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().heightIn(min = 56.dp).padding(start = 8.dp, end = AuraSpacing.ScreenGutter),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        val source = remember { MutableInteractionSource() }
        Box(
            Modifier.size(AuraSpacing.TouchTarget).clip(AuraShapes.Circle)
                .auraFocusRing(source, AuraShapes.Circle)
                .clickable(interactionSource = source, indication = null, role = Role.Button, onClick = onBack)
                .semantics { contentDescription = "Voltar" },
            contentAlignment = Alignment.Center,
        ) {
            Canvas(Modifier.size(24.dp)) {
                val p = Path().apply {
                    moveTo(size.width * 0.625f, size.height * 0.75f)
                    lineTo(size.width * 0.375f, size.height * 0.5f)
                    lineTo(size.width * 0.625f, size.height * 0.25f)
                }
                drawPath(p, AuraColors.Ink, style = Stroke(1.75.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
            }
        }
        Text(
            "Permissões", style = AuraType.Section.copy(fontSize = 17.sp, lineHeight = 22.sp),
            modifier = Modifier.weight(1f).semantics { heading() },
        )
    }
}

@Composable
private fun PermissionCard(row: PermissionRow, onAllow: () -> Unit, onOpenSettings: () -> Unit) {
    val p = row.item
    val (kind, label) = when (row.state) {
        PermissionState.Granted -> StatusKind.Ready to "Permitido"
        PermissionState.CanAsk -> StatusKind.Neutral to "Ainda não permitido"
        PermissionState.Blocked -> StatusKind.NeedsYou to "Bloqueado"
    }
    AuraCard(Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(p.function, style = AuraType.BodyStrong, modifier = Modifier.weight(1f))
            StatusChip(kind, label)
        }
        Text(if (row.state == PermissionState.Granted) p.why else p.ifDenied, style = AuraType.SecondarySmall)
        when (row.state) {
            PermissionState.Granted -> Unit
            PermissionState.CanAsk ->
                AuraButtonSecondary("Permitir", onAllow, contentDescription = "Permitir ${p.function.lowercase()}")
            PermissionState.Blocked ->
                AuraButtonSecondary("Abrir ajustes", onOpenSettings, contentDescription = "Abrir ajustes para liberar ${p.function.lowercase()}")
        }
    }
}

/**
 * Folha do app que vem ANTES do pedido do sistema: o que é, por que, o que acontece se negar.
 * "Permitir" é o único caminho até o diálogo do Android; "Agora não" (ou tocar fora) não pede nada.
 * Quem usa uma função com permissão (voz, avisos) mostra esta folha e só então chama o sistema.
 */
@Composable
fun PermissionSheet(item: AppPermission, onAllow: () -> Unit, onNotNow: () -> Unit, modifier: Modifier = Modifier) {
    val scrim = remember { MutableInteractionSource() }
    Box(modifier.fillMaxSize()) {
        Box(
            Modifier.fillMaxSize().background(AuraColors.Ink.copy(alpha = 0.5f))
                .clickable(interactionSource = scrim, indication = null, onClickLabel = "Fechar sem permitir", onClick = onNotNow)
                .clearAndSetSemantics { },
        )
        Column(
            Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .background(AuraColors.Surface, AuraShapes.Sheet) // sem clip: o toque nos botoes nao passa por recorte
                .pointerInput(Unit) { } // segura o toque no vazio da folha, sem consumir o dos botoes
                .navigationBarsPadding()
                .padding(start = 20.dp, end = 20.dp, top = 10.dp, bottom = 20.dp)
                .semantics { paneTitle = "Permitir ${item.function.lowercase()}" },
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Box(
                Modifier.align(Alignment.CenterHorizontally).width(36.dp).height(4.dp)
                    .clip(AuraShapes.Pill).background(AuraColors.LineStrong).clearAndSetSemantics { },
            )
            Text(
                item.function, style = AuraType.TabTitle.copy(fontSize = 24.sp, lineHeight = 28.sp),
                modifier = Modifier.padding(top = 8.dp).semantics { heading() },
            )
            Text(item.why, style = AuraType.Body)
            Text("Se você não permitir: ${item.ifDenied}", style = AuraType.SecondarySmall)
            AuraButtonPrimary("Permitir", onAllow, Modifier.fillMaxWidth().padding(top = 8.dp), large = true)
            AuraButtonText("Agora não", onNotNow, Modifier.align(Alignment.CenterHorizontally))
        }
    }
}

private fun denialKey(p: AppPermission) = "perm_denied_" + p.permission

/**
 * Liga a tela ao Android: estado lido na hora (e de novo ao voltar das configurações), pedido ao sistema
 * só depois da folha, negativas contadas em aura_prefs e "Abrir ajustes" nas configurações do app.
 * Funciona sem motor e sem rede. Quem registra a rota (Routes.kt) só chama PermissionsRoute(onBack).
 */
@Composable
fun PermissionsRoute(onBack: () -> Unit, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val prefs = remember { context.getSharedPreferences("aura_prefs", Context.MODE_PRIVATE) }
    var reread by remember { mutableIntStateOf(0) }
    var asking by remember { mutableStateOf<AppPermission?>(null) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        asking?.takeIf { !granted }?.let { prefs.edit().putInt(denialKey(it), prefs.getInt(denialKey(it), 0) + 1).apply() }
        asking = null
        reread++
    }
    val owner = LocalLifecycleOwner.current
    DisposableEffect(owner) {
        val observer = LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_RESUME) reread++ }
        owner.lifecycle.addObserver(observer)
        onDispose { owner.lifecycle.removeObserver(observer) }
    }
    val rows = remember(reread) {
        AppPermissions.forSdk(Build.VERSION.SDK_INT)
            .filter { it.needsPackage == null || context.packageManager.getLaunchIntentForPackage(it.needsPackage) != null }
            .map { p ->
                val granted = context.checkSelfPermission(p.permission) == PackageManager.PERMISSION_GRANTED
                PermissionRow(p, AppPermissions.state(granted, prefs.getInt(denialKey(p), 0)))
            }
    }
    PermissionsScreen(
        rows,
        onAsk = { asking = it; launcher.launch(it.permission) },
        onOpenSettings = {
            runCatching {
                context.startActivity(
                    Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${context.packageName}")),
                )
            }
        },
        onBack = onBack,
        modifier = modifier,
    )
}
