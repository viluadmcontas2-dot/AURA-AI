package ai.aura.app.ui.screens.link

import android.content.Intent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import ai.aura.app.ui.components.AuraButtonPrimary
import ai.aura.app.ui.components.AuraButtonSecondary
import ai.aura.app.ui.theme.AuraColors
import ai.aura.app.ui.theme.AuraShapes
import ai.aura.app.ui.theme.AuraType
import java.net.URI

/** Sites que o AURA conhece (domínio e nome). shortcut: lista fixa; qualquer outro https abre, mas com aviso. */
private val KNOWN = listOf("github.com" to "GitHub", "claude.ai" to "Claude", "anthropic.com" to "Anthropic")

/**
 * Um endereço https já conferido. [url] é o que se copia, compartilha e abre (sem usuário@, porta 443 e ponto final).
 * [knownName] só existe para host exato (ou subdomínio) de um site conhecido; [resembles] diz com qual ele parece.
 * [openable] é falso quando o endereço não pôde ser lido com segurança (aí só dá para copiar e compartilhar).
 */
data class ParsedLink(
    val url: String,
    val host: String,
    val path: String,
    val knownName: String?,
    val resembles: String?,
    val openable: Boolean,
) {
    val warning: String
        get() = when {
            resembles != null -> "Parece com $resembles, mas não é. Não digite sua senha."
            !openable -> "Não consegui conferir este endereço. Não digite sua senha."
            else -> "Não conheço este endereço. Confira antes de abrir e não digite sua senha."
        }

    /** Caminho abreviado da tira do endereço: "/…/minhas-financas/pull/12". */
    val shortPath: String
        get() {
            val partes = path.split('/').filter { it.isNotEmpty() }
            return if (partes.size > 3) "/…/" + partes.takeLast(3).joinToString("/") else path
        }
}

/**
 * Porta única dos links: null = não é https (a tela recusa); qualquer https devolve um [ParsedLink] e passa pela sheet.
 * shortcut: o host vem do java.net.URI; letras fora do ASCII ou formato estranho viram "não abrível" (só copiar/compartilhar),
 * trocar por um parser próprio se for preciso abrir endereços internacionais.
 */
fun parseLink(raw: String?): ParsedLink? {
    val text = raw?.trim().orEmpty()
    if (!text.startsWith("https://", ignoreCase = true)) return null
    val uri = runCatching { URI(text) }.getOrNull()
    val host = uri?.host?.trimEnd('.')?.lowercase()
    if (uri == null || host == null || host.isEmpty() || uri.port !in -1..65535) {
        return ParsedLink(text, text, "", null, null, openable = false)
    }
    val porta = if (uri.port == -1 || uri.port == 443) "" else ":${uri.port}"
    val caminho = uri.rawPath.orEmpty().ifEmpty { "/" }
    val url = "https://$host$porta$caminho" + (uri.rawQuery?.let { "?$it" } ?: "") + (uri.rawFragment?.let { "#$it" } ?: "")
    val conhecido = KNOWN.firstOrNull { (dominio, _) -> host == dominio || host.endsWith(".$dominio") }
        ?.second?.takeIf { uri.userInfo == null && (uri.port == -1 || uri.port == 443) }
    val parecido = if (conhecido != null) null else {
        // O texto antes do @ também conta: "https://github.com@evil.com" tenta enganar com o usuário.
        val pedacos = (host + "." + uri.userInfo.orEmpty()).split('.', '-')
        KNOWN.firstOrNull { (dominio, _) ->
            pedacos.any { it.length >= 5 && distancia(it, dominio.substringBefore('.')) <= 1 }
        }?.first
    }
    return ParsedLink(url, host, caminho, conhecido, parecido, openable = true)
}

/** Distância de edição entre duas palavras (trocar, inserir ou apagar uma letra = 1). */
private fun distancia(a: String, b: String): Int {
    var anterior = IntArray(b.length + 1) { it }
    for (i in 1..a.length) {
        val atual = IntArray(b.length + 1).also { it[0] = i }
        for (j in 1..b.length) {
            atual[j] = minOf(anterior[j] + 1, atual[j - 1] + 1, anterior[j - 1] + if (a[i - 1] == b[j - 1]) 0 else 1)
        }
        anterior = atual
    }
    return anterior[b.length]
}

/**
 * Conteúdo da sheet de Link-Aberto.dc.html. Site conhecido: "Abrir aqui". Qualquer outro https: aviso antes,
 * "Cancelar" como botão principal e "Abrir mesmo assim" como escolha deliberada.
 * shortcut: sem a prévia do site (título, status) porque o motor não entrega esses dados; sem "Abrir no app do GitHub".
 */
@Composable
fun LinkSheetContent(
    link: ParsedLink,
    onOpenHere: () -> Unit,
    onOpenAnyway: () -> Unit,
    onCopy: () -> Unit,
    onShare: () -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
    notice: String? = null,
) {
    Column(
        modifier.fillMaxWidth().padding(horizontal = 20.dp).padding(bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text("ABRIR LINK", style = AuraType.Caption.copy(color = AuraColors.Ink3), modifier = Modifier.weight(1f))
            Box(
                Modifier.size(44.dp).clickable(role = Role.Button, onClick = onClose)
                    .semantics { contentDescription = "Fechar" },
                contentAlignment = Alignment.Center,
            ) { Text("×", style = AuraType.TabTitle) }
        }
        Box(
            Modifier.fillMaxWidth().heightIn(min = 44.dp).clip(AuraShapes.Chip)
                .background(AuraColors.SurfaceSunken).padding(horizontal = 14.dp),
            contentAlignment = Alignment.CenterStart,
        ) {
            Text(
                buildAnnotatedString {
                    withStyle(SpanStyle(fontWeight = FontWeight.Bold)) { append(link.host) }
                    withStyle(SpanStyle(color = AuraColors.Ink3)) { append(link.shortPath) }
                },
                style = AuraType.Secondary,
            )
        }
        if (link.knownName != null) {
            Text("Abrir este link?", style = AuraType.Section)
            AuraButtonPrimary("Abrir aqui", onOpenHere, Modifier.fillMaxWidth(), large = true)
        } else {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("Link externo: confira o endereço", style = AuraType.BodyStrong)
                Text(link.warning, style = AuraType.Secondary)
                Text(link.url, style = AuraType.Mono)
            }
            AuraButtonPrimary("Cancelar", onClose, Modifier.fillMaxWidth(), large = true)
            if (link.openable) AuraButtonSecondary("Abrir mesmo assim", onOpenAnyway, Modifier.fillMaxWidth())
        }
        AuraButtonSecondary("Copiar link", onCopy, Modifier.fillMaxWidth())
        AuraButtonSecondary("Compartilhar", onShare, Modifier.fillMaxWidth())
        notice?.let {
            Text(it, style = AuraType.SecondarySmall, modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
        }
        Text("Links desconhecidos mostram um aviso antes de abrir.", style = AuraType.SecondarySmall.copy(color = AuraColors.Ink3))
    }
}

/**
 * Coloque uma vez na raiz e passe o endereço bruto: `LinkSheetHost(link, onDismiss = { link = null })`.
 * [raw] null = fechada. Não é https = aviso "Não abro este link". Abrir/compartilhar usam o navegador do aparelho.
 * shortcut: "Abrir aqui" também usa o navegador do aparelho (sem visualizador dentro do AURA ainda).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LinkSheetHost(raw: String?, onDismiss: () -> Unit, modifier: Modifier = Modifier) {
    if (raw == null) return
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    val uriHandler = LocalUriHandler.current
    var notice by remember(raw) { mutableStateOf<String?>(null) }
    val link = parseLink(raw)
    fun tentar(falha: String, acao: () -> Unit) {
        notice = if (runCatching(acao).isSuccess) null else falha
    }
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        modifier = modifier,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        shape = AuraShapes.Sheet,
        containerColor = AuraColors.Surface,
        scrimColor = AuraColors.Ink.copy(alpha = 0.5f),
    ) {
        if (link == null) {
            Column(
                Modifier.fillMaxWidth().padding(horizontal = 20.dp).padding(bottom = 24.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text("Não abro este link", style = AuraType.Section)
                Text("Só abro endereços que começam com https://. Confira o endereço com quem enviou.", style = AuraType.Secondary)
                AuraButtonPrimary("Fechar aviso", onDismiss, Modifier.fillMaxWidth(), large = true)
            }
        } else {
            val abrir = { tentar("Não consegui abrir o link.") { uriHandler.openUri(link.url) } }
            LinkSheetContent(
                link,
                onOpenHere = abrir,
                onOpenAnyway = abrir,
                onCopy = {
                    clipboard.setText(AnnotatedString(link.url))
                    notice = "Link copiado."
                },
                onShare = {
                    tentar("Não consegui compartilhar.") {
                        val envio = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, link.url)
                        context.startActivity(Intent.createChooser(envio, null))
                    }
                },
                onClose = onDismiss,
                notice = notice,
            )
        }
    }
}
