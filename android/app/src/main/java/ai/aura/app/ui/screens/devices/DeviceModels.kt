package ai.aura.app.ui.screens.devices

import ai.aura.app.ui.components.StatusKind
import ai.aura.app.ui.engine.Capability

/**
 * Estado de conexão mostrado na UI. Sempre derivado de [Capability] reais do engine:
 * sem capability = indisponível; nunca se presume login. Uma capability disponível só vira
 * "Conectado" se o detalhe não indicar login pendente.
 */
enum class ConnectionState(val label: String, val kind: StatusKind) {
    Connected("Conectado", StatusKind.Ready),
    NeedsLogin("Precisa de login", StatusKind.NeedsYou),
    NotInstalled("Não instalado", StatusKind.Neutral),
    Offline("Offline", StatusKind.Risk),

    /** O motor não respondeu: o estado de agora é desconhecido. Nunca se mostra o "Conectado" de uma vez que já passou. */
    Unknown("Estado desconhecido", StatusKind.Neutral),
}

enum class DeviceGroup { ThisPhone, Device, Tool }

enum class DeviceActionKind(val label: String) {
    SignIn("Abrir Conexões"),
    InstallTool("Instalar"),
    Reconnect("Reconectar"),
    Verify("Verificar conexão"),
    Capture("Capturar tela"),
    Battery("Ver bateria"),
    InstallApp("Instalar app"),
    Reboot("Reiniciar"),
    Disconnect("Desconectar");

    /** Texto do pedido enviado ao engine (que decide risco e aprovação). */
    fun prompt(device: String): String = when (this) {
        SignIn -> "Conectar $device"
        InstallTool -> "Instalar $device"
        Reconnect -> "Reconectar $device"
        Verify -> "Verificar conexão de $device"
        Capture -> "Capturar tela de $device"
        Battery -> "Ver bateria de $device"
        InstallApp -> "Instalar app em $device"
        Reboot -> "Reiniciar $device"
        Disconnect -> "Desconectar $device"
    }
}

data class DeviceEntry(
    val id: String,
    val label: String,
    val group: DeviceGroup,
    val state: ConnectionState,
    val detail: String?,
    /** Última vez que o motor confirmou este aparelho conectado (catálogo local). null = nunca. */
    val lastConnectedAtMillis: Long? = null,
) {
    /** Texto do chip: nunca só cor, nunca vazio. */
    val chipText: String
        get() = if (state == ConnectionState.Connected && !detail.isNullOrBlank() &&
            !detail.equals("Conectado", ignoreCase = true)
        ) "Conectado · $detail" else state.label

    /** Linha de apoio quando algo exige atenção. */
    val hint: String?
        get() = if (state == ConnectionState.Connected) null else detail?.takeIf { it.isNotBlank() }
}

fun classify(c: Capability): ConnectionState {
    val d = c.detail?.lowercase().orEmpty()
    val notInstalled = listOf("não instalado", "nao instalado", "not_installed", "not installed").any { it in d }
    val needsLogin = listOf("login", "auth_required", "entrar", "sessão expirada", "sessao expirada").any { it in d }
    return when {
        notInstalled -> ConnectionState.NotInstalled
        needsLogin -> ConnectionState.NeedsLogin
        c.available -> ConnectionState.Connected
        else -> ConnectionState.Offline
    }
}

/** Primeira capability cujo id contém [token] (ex.: "claude" casa com "claude_cli"). */
fun List<Capability>.findByToken(token: String): Capability? =
    firstOrNull { it.id.contains(token, ignoreCase = true) }

private fun Capability.toEntry(group: DeviceGroup, label: String = this.label) =
    DeviceEntry(id, label, group, classify(this), detail)

/** Este celular (Termux) sempre aparece; se o engine não o reporta, é "Não instalado". */
fun List<Capability>.phoneEntry(): DeviceEntry =
    findByToken("termux")?.toEntry(DeviceGroup.ThisPhone, "Este celular")
        ?: DeviceEntry("termux", "Este celular", DeviceGroup.ThisPhone, ConnectionState.NotInstalled,
            "O engine não encontrou o Termux neste aparelho.")

fun List<Capability>.deviceEntries(): List<DeviceEntry> =
    filter { it.id.startsWith("adb", true) || it.id.startsWith("device", true) }
        .map { it.toEntry(DeviceGroup.Device) }

/** Ação de reparo para um estado que não é Conectado. */
fun ConnectionState.repairAction(group: DeviceGroup): DeviceActionKind? = when (this) {
    ConnectionState.Connected -> null
    ConnectionState.NeedsLogin -> DeviceActionKind.SignIn
    ConnectionState.NotInstalled -> DeviceActionKind.InstallTool
    ConnectionState.Offline -> DeviceActionKind.Reconnect
    ConnectionState.Unknown -> DeviceActionKind.Verify
}

/** Ações disponíveis em aparelho conectado (grupo decide). */
fun DeviceGroup.connectedActions(): List<DeviceActionKind> = when (this) {
    DeviceGroup.Device -> listOf(
        DeviceActionKind.Capture, DeviceActionKind.InstallApp, DeviceActionKind.Battery,
        DeviceActionKind.Reboot, DeviceActionKind.Disconnect,
    )
    DeviceGroup.ThisPhone -> listOf(DeviceActionKind.Battery)
    DeviceGroup.Tool -> emptyList()
}

/** Tipo do job enviado ao engine para esta ação. */
fun DeviceActionKind.jobKind(group: DeviceGroup): String =
    if (group == DeviceGroup.Device && this != DeviceActionKind.InstallTool) "adb" else "system"
