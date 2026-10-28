package ai.aura.app.ui.screens.connections

import ai.aura.app.integration.InstallItem
import ai.aura.app.integration.InstallPlan
import ai.aura.app.integration.SizeKind
import ai.aura.app.offline.NetworkKind
import ai.aura.app.ui.components.AuraButtonPrimary
import ai.aura.app.ui.components.AuraButtonText
import ai.aura.app.ui.components.AuraCard
import ai.aura.app.ui.theme.AuraType
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.security.MessageDigest

/*
 * Consentimento antes de baixar ou instalar qualquer coisa (docs/FLUXOS_DE_USO.md, seção 7; contrato C4).
 *
 * Regra do app: NENHUM código que baixa ou instala roda antes de [InstallConsentGate.accept] devolver "aceito".
 * Quem precisa instalar apenas PROPÕE um plano ([InstallConsentGate.propose]) e entrega um [ConsentHandler];
 * só o handler, chamado pelo aceite, executa a instalação. Recusa, plano vencido, plano alterado e tamanho
 * desconhecido nunca chamam `onAccepted`. Toque duplo executa uma vez: o primeiro aceite consome o pedido.
 */

enum class RejectReason { NoPlan, Expired, SizeUnknown, Changed }

interface ConsentHandler {
    /** A pessoa tocou em Instalar com o plano válido, inteiro e de tamanho conhecido. Única porta para instalar. */
    fun onAccepted(plan: InstallPlan)

    /** Agora não: nada foi instalado. */
    fun onDeclined(plan: InstallPlan) {}

    /** Vencido, alterado ou de tamanho desconhecido: nada foi instalado. */
    fun onRejected(plan: InstallPlan, reason: RejectReason) {}

    /** Calcular tamanho: quem propôs refaz a consulta e propõe o plano novo. */
    fun onMeasure(plan: InstallPlan) {}
}

data class PendingInstall(val plan: InstallPlan, val handler: ConsentHandler)

class InstallConsentGate(val clock: () -> Long = System::currentTimeMillis) {
    private val mutable = MutableStateFlow<PendingInstall?>(null)
    val pending: StateFlow<PendingInstall?> = mutable.asStateFlow()

    fun now(): Long = clock()

    /** Mostra o pedido. Um pedido novo substitui o anterior (a decisão antiga não vale para o plano novo). */
    fun propose(plan: InstallPlan, handler: ConsentHandler) {
        mutable.value = PendingInstall(plan, handler)
    }

    /**
     * A pessoa tocou em Instalar vendo o plano [planId] com o hash [manifestHash]. Devolve null se aceitou;
     * senão o motivo da recusa. Só um aceite executa: o segundo toque encontra o pedido já consumido.
     */
    fun accept(planId: String, manifestHash: String): RejectReason? {
        val current = mutable.value ?: return RejectReason.NoPlan
        val plan = current.plan
        if (plan.planId != planId) return RejectReason.NoPlan
        if (!plan.sizeKnown) {
            current.handler.onRejected(plan, RejectReason.SizeUnknown) // continua na tela: dá para Calcular tamanho
            return RejectReason.SizeUnknown
        }
        if (manifestHash != plan.manifestHash) {
            if (mutable.compareAndSet(current, null)) current.handler.onRejected(plan, RejectReason.Changed)
            return RejectReason.Changed
        }
        if (clock() >= plan.expiresAtMillis) {
            if (mutable.compareAndSet(current, null)) current.handler.onRejected(plan, RejectReason.Expired)
            return RejectReason.Expired
        }
        if (!mutable.compareAndSet(current, null)) return RejectReason.NoPlan // outro toque chegou primeiro
        current.handler.onAccepted(plan)
        return null
    }

    fun decline(planId: String) {
        val current = mutable.value ?: return
        if (current.plan.planId != planId || !mutable.compareAndSet(current, null)) return
        current.handler.onDeclined(current.plan)
    }

    fun measure(planId: String) {
        val current = mutable.value ?: return
        if (current.plan.planId == planId) current.handler.onMeasure(current.plan)
    }

    /** Fecha sem decidir (a tela de quem propôs saiu). Nada é instalado. */
    fun clear() {
        mutable.value = null
    }
}

/** Preparo do assistente (C3/C4) pelo motor. O padrão não tem motor: toda chamada diz "indisponível" e nada se instala. */
val LocalSetupGateway = staticCompositionLocalOf<ai.aura.app.integration.SetupGateway> { ai.aura.app.integration.NoEngineSetupGateway }

/** O gate do app inteiro: quem propõe e o diálogo usam o mesmo. null = sem como pedir confirmação, então nada se instala. */
val LocalInstallConsent = staticCompositionLocalOf<InstallConsentGate?> { null }

// ----------------------------------------------------------------------------------------------- textos

object InstallText {
    const val TITLE = "Instalar programas necessários?"
    const val UNKNOWN = "Ainda não consegui calcular o tamanho. Nada será instalado."
    const val DECLINED = "Nada foi instalado. Você pode continuar usando o app."
    const val NO_GATE = "Não consegui pedir a sua confirmação agora. Nada foi instalado."

    private fun trim(value: Double): String =
        if (value >= 10 || value == Math.floor(value)) Math.round(value).toString()
        else String.format(java.util.Locale("pt", "BR"), "%.1f", value)

    fun bytes(value: Long): String = when {
        value < 1024 -> "$value B"
        value < 1024 * 1024 -> trim(value / 1024.0) + " KB"
        value < 1024L * 1024 * 1024 -> trim(value / 1048576.0) + " MB"
        else -> trim(value / 1073741824.0) + " GB"
    }

    fun download(plan: InstallPlan): String = when {
        plan.downloadBytes == null -> "ainda não calculado"
        plan.downloadBytes == 0L -> "nenhum"
        plan.sizeKind == SizeKind.UpperBound -> "até " + bytes(plan.downloadBytes)
        else -> bytes(plan.downloadBytes)
    }

    fun space(plan: InstallPlan): String = when {
        plan.installedBytes == null -> "ainda não calculado"
        plan.sizeKind == SizeKind.UpperBound -> "até " + bytes(plan.installedBytes)
        else -> bytes(plan.installedBytes)
    }

    fun network(kind: NetworkKind): String = when (kind) {
        NetworkKind.Wifi -> "Wi-Fi"
        NetworkKind.Mobile -> "dados móveis"
        NetworkKind.Unknown -> "não identificada"
    }

    private fun names(items: List<InstallItem>): String {
        val list = items.map { it.name }.filter { it.isNotBlank() }
        return when (list.size) {
            0 -> "os programas necessários"
            1 -> list[0]
            else -> list.dropLast(1).joinToString(", ") + " e " + list.last()
        }
    }

    /** "Para {finalidade}, vou baixar {lista}. Download: {tamanho}. Espaço necessário: {espaço}. Conexão: {rede}." */
    fun body(plan: InstallPlan): String {
        val purpose = plan.reason.trim().trimEnd('.').replaceFirstChar { it.lowercase() }
        val verb = if (plan.downloadBytes == 0L) "vou instalar" else "vou baixar"
        return "Para $purpose, $verb ${names(plan.items)}. Download: ${download(plan)}. " +
            "Espaço necessário: ${space(plan)}. Conexão: ${network(plan.network)}."
    }

    fun rejected(reason: RejectReason): String = when (reason) {
        RejectReason.Expired -> "O pedido venceu e nada foi instalado. Peça de novo para ver o tamanho atualizado."
        RejectReason.Changed -> "O que seria instalado mudou e nada foi instalado. Confira o pedido novo."
        RejectReason.SizeUnknown -> UNKNOWN
        RejectReason.NoPlan -> "Este pedido já foi usado. Nada foi instalado de novo."
    }
}

// ------------------------------------------------------------------------------------------- plano local

/**
 * Plano do motor ANTES de existir motor: empacotado no app, mesmo formato e mesma validade do C4. O Python do
 * Termux é baixado só se faltar; o tamanho é um LIMITE SUPERIOR declarado (não medido). O motor do AURA já vem
 * dentro do APK: copiá-lo não baixa nada. Atualização tem plano e consentimento próprios.
 */
object LocalEnginePlan {
    const val TTL_MILLIS = 10 * 60_000L
    const val PYTHON_DOWNLOAD_UPPER_BYTES = 150L * 1024 * 1024
    const val PYTHON_INSTALLED_UPPER_BYTES = 400L * 1024 * 1024

    fun build(update: Boolean, engineBytes: Long, network: NetworkKind, nowMillis: Long): InstallPlan {
        val engine = InstallItem(
            name = "Motor do AURA", version = null, source = "Dentro do próprio app (sem baixar)",
            downloadBytes = 0, installedBytes = engineBytes,
        )
        val python = InstallItem(
            name = "Python do Termux", version = null, source = "Repositório de pacotes do Termux",
            downloadBytes = PYTHON_DOWNLOAD_UPPER_BYTES, installedBytes = PYTHON_INSTALLED_UPPER_BYTES,
            includes = listOf("só se o Python ainda não estiver no Termux"),
        )
        val items = if (update) listOf(engine) else listOf(python, engine)
        val download = items.sumOf { it.downloadBytes ?: 0 }
        val installed = items.sumOf { it.installedBytes ?: 0 }
        val kind = if (update) SizeKind.Exact else SizeKind.UpperBound
        val id = if (update) "local-engine-update" else "local-engine"
        val canonical = listOf(id, kind.wire, download, installed, items.joinToString(";") { it.name }).joinToString("|")
        return InstallPlan(
            planId = id,
            manifestHash = "sha256:" + MessageDigest.getInstance("SHA-256").digest(canonical.toByteArray())
                .joinToString("") { "%02x".format(it) },
            target = "engine",
            reason = if (update) "atualizar o assistente neste celular" else "executar o assistente neste celular",
            items = items, downloadBytes = download, installedBytes = installed, sizeKind = kind,
            expiresAtMillis = nowMillis + TTL_MILLIS, network = network, local = true,
        )
    }
}

// ---------------------------------------------------------------------------------------------- diálogo

/** Mostra o pedido do gate, em qualquer tela. Fechar sem escolher é "Agora não": nunca instala. */
@Composable
fun InstallConsentHost(gate: InstallConsentGate, modifier: Modifier = Modifier) {
    val pending by gate.pending.collectAsState()
    val current = pending ?: return
    InstallConsentDialog(
        plan = current.plan,
        onInstall = { gate.accept(current.plan.planId, current.plan.manifestHash) },
        onNotNow = { gate.decline(current.plan.planId) },
        onMeasure = { gate.measure(current.plan.planId) },
        modifier = modifier,
    )
}

@Composable
fun InstallConsentDialog(
    plan: InstallPlan,
    onInstall: () -> Unit,
    onNotNow: () -> Unit,
    onMeasure: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var details by rememberSaveable(plan.planId) { mutableStateOf(false) }
    Dialog(onDismissRequest = onNotNow) {
        AuraCard(modifier.fillMaxWidth()) {
            Column(Modifier.padding(4.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(InstallText.TITLE, style = AuraType.Section, modifier = Modifier.semantics { heading() })
                if (plan.sizeKnown) {
                    Text(InstallText.body(plan), style = AuraType.Body)
                    AuraButtonPrimary("Instalar", onInstall, Modifier.fillMaxWidth())
                } else {
                    Text(InstallText.UNKNOWN, style = AuraType.Body)
                    AuraButtonPrimary("Calcular tamanho", onMeasure, Modifier.fillMaxWidth())
                }
                AuraButtonText("Agora não", onNotNow, Modifier.fillMaxWidth())
                AuraButtonText(if (details) "Esconder detalhes" else "Detalhes", { details = !details }, Modifier.fillMaxWidth())
                if (details) {
                    plan.items.forEach { item ->
                        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            Text(item.name, style = AuraType.BodyStrong)
                            Text("Versão: ${item.version ?: "a mais recente disponível"}", style = AuraType.SecondarySmall)
                            Text("Origem: ${item.source.ifBlank { "não informada" }}", style = AuraType.SecondarySmall)
                            Text(
                                "Verificação do arquivo: ${item.checksum ?: "sem soma de verificação"}",
                                style = AuraType.SecondarySmall,
                            )
                            if (item.includes.isNotEmpty()) {
                                Text("Inclui: ${item.includes.joinToString(", ")}", style = AuraType.SecondarySmall)
                            }
                        }
                    }
                    Text(
                        "Nada é baixado antes do seu toque em Instalar. Este pedido vale por poucos minutos.",
                        style = AuraType.SecondarySmall,
                    )
                }
            }
        }
    }
}
