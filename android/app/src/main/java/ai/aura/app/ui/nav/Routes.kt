package ai.aura.app.ui.nav

import ai.aura.app.ui.AuraTab
import ai.aura.app.ui.OnboardingHost
import ai.aura.app.ui.engine.EngineGateway
import ai.aura.app.ui.screens.ActivityScreen
import ai.aura.app.ui.screens.DevicesScreen
import ai.aura.app.ui.screens.HomeScreen
import ai.aura.app.ui.screens.ProjectsScreen
import ai.aura.app.ui.screens.settings.SettingsScreen
import ai.aura.app.ui.screens.voice.VoiceCompactRoute
import ai.aura.app.ui.screens.voice.VoiceListeningRoute
import ai.aura.app.ui.screens.voice.VoiceModesRoute
import ai.aura.app.ui.screens.voice.VoiceTriageRoute
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.Modifier

/**
 * Uma tela do design. [screen] é null enquanto a tela não existe: a rota fica em `pending` e nenhum
 * botão leva até ela (sem tela "em construção"). Quem entrega uma tela troca o `null` pelo composable.
 * [design] = arquivo em docs/design/telas (sem `.dc.html`); [tab] = aba da barra inferior (null = fora
 * das abas, sem barra); [needsEngine] = a tela mostra dado ou age pelo motor (sem ele mostra o guardado
 * e o banner explica); [edges] = para onde ela pode levar.
 */
class Route(
    val id: String,
    val design: String?,
    val tab: AuraTab?,
    val needsEngine: Boolean,
    val edges: List<String>,
    val screen: (@Composable (ScreenEnv) -> Unit)? = null,
)

/** O que o shell entrega às telas: o que as telas de hoje já recebiam, agrupado. */
class ScreenEnv(
    val gateway: EngineGateway,
    val open: (String) -> Unit,
    val openTab: (AuraTab) -> Unit,
    val back: () -> Unit,
    val onOpenUrl: (String) -> Unit,
    // Início
    val onChatOpenChange: (Boolean) -> Unit,
    val openJobId: String?,
    val onOpenJobHandled: () -> Unit,
    val openJob: (String) -> Unit,
    val draft: String,
    val onDraftChange: (String) -> Unit,
    // Onboarding
    val onRequestTermuxPermission: () -> Unit,
    val onOpenTermux: () -> Unit,
    val termuxInstalled: () -> Boolean,
    val termuxPermissionGranted: () -> Boolean,
    val onOnboardingFinished: () -> Unit,
    // Ajustes e voz
    val settings: ai.aura.app.integration.SettingsGateway = ai.aura.app.integration.NoEngineSettingsGateway,
    /** Pedido falado que ainda vai virar mensagem no chat do Início. */
    val pendingPrompt: String? = null,
    val onPendingPromptHandled: () -> Unit = {},
    /** Cria o pedido no chat (mesmo caminho do envio do composer). */
    val sendPrompt: (String) -> Unit = {},
    /** Ideia vinda de "/projeto ideia": a aba Projetos abre o passo da ideia já preenchida. */
    val projectIdea: String = "",
    val onProjectIdeaHandled: () -> Unit = {},
    val startProjectIdea: (String) -> Unit = {},
    val queue: ai.aura.app.integration.QueueGateway = ai.aura.app.integration.NoEngineQueueGateway,
    /** Estado do motor e a ação por motivo (mesmas do banner "Motor desligado"): Conexões mostra e usa os dois. */
    val link: ai.aura.app.offline.LinkState = ai.aura.app.offline.LinkState.Unknown,
    val onEngineAction: (ai.aura.app.offline.OfflineReason) -> Unit = {},
    /** Pede o aviso fora do app (Android 13+) quando o dono chega ao passo Contas da abertura. */
    val onRequestNotifications: () -> Unit = {},
    /** Sessões: continuar uma conversa do Claude Code no chat do Início (o chat carrega o histórico dela). */
    val openSession: (String) -> Unit = {},
    val openSessionId: String? = null,
    val onOpenSessionHandled: () -> Unit = {},
)

// id, arquivo do design, aba, needsEngine, arestas. Arestas = Fluxo-do-usuario + grafo do plano; as que
// o plano não traz (voz cai, chat pelo teclado, chip do Início, Ferramentas da reforma, estados vazios)
// existem para toda tela ter caminho de entrada.
val ROUTES: List<Route> = listOf(
    Route("onb-boas-vindas", "Onb-1-Boas-vindas", null, false, listOf("onb-termux")) { env ->
        OnboardingHost(
            env.gateway, env.onOpenUrl, env.onRequestTermuxPermission, env.onOpenTermux,
            env.termuxInstalled, env.termuxPermissionGranted, env.onOnboardingFinished, Modifier.fillMaxSize(),
            onRequestNotifications = env.onRequestNotifications,
        )
    },
    Route("onb-termux", "Onb-2-Termux", null, false, listOf("onb-contas")),
    Route("onb-contas", "Onb-3-Contas", null, true, listOf("inicio")),

    Route("inicio", "Main", AuraTab.Home, true, listOf("chat-entendi", "chat-teclado", "voz-ouvindo", "projetos", "dispositivos", "atividade", "ajustes", "conexoes", "reformar-apps", "sessoes")) { env ->
        HomeScreen(
            env.gateway, Modifier.fillMaxSize(),
            onChatOpenChange = env.onChatOpenChange,
            onOpenSettings = { env.open("ajustes") },
            openJobId = env.openJobId,
            onOpenJobHandled = env.onOpenJobHandled,
            draft = env.draft,
            onDraftChange = env.onDraftChange,
            pendingPrompt = env.pendingPrompt,
            onPendingPromptHandled = env.onPendingPromptHandled,
            onOpenVoice = { env.open("voz-ouvindo") },
            onOpenReform = { env.open("reformar-apps") },
            link = env.link,
            onEngineAction = env.onEngineAction,
            onNavigate = { target, rest -> openChatTarget(env, target, rest) },
            openSessionId = env.openSessionId,
            onOpenSessionHandled = env.onOpenSessionHandled,
        )
    },
    Route("chat-entendi", "Chat-1-Entendi", AuraTab.Home, true, listOf("chat-executando")),
    Route("chat-teclado", "Chat-2-Teclado-Comandos", AuraTab.Home, true, listOf("chat-entendi")),
    Route("chat-executando", "Chat-3-Executando", AuraTab.Home, true, listOf("chat-aprovacao", "chat-resultado", "bastidores")),
    Route("chat-aprovacao", "Chat-4-Aprovacao", AuraTab.Home, true, listOf("chat-resultado")),
    Route("chat-resultado", "Chat-5-Resultado", AuraTab.Home, true, listOf("link-aberto", "artefato-detalhe", "dispositivo-detalhe")),
    Route("voz-ouvindo", "Voz-1-Ouvindo", AuraTab.Home, false, listOf("voz-compacta", "voz-interrompida", "chat-entendi")) { env ->
        VoiceListeningRoute(env, Modifier.fillMaxSize())
    },
    Route("voz-interrompida", "Voz-2-Interrompida", AuraTab.Home, false, listOf("voz-ouvindo")) { env ->
        VoiceListeningRoute(env, Modifier.fillMaxSize())
    },
    Route("voz-compacta", "Voz-3-Compacta", AuraTab.Home, false, listOf("voz-triagem", "voz-ouvindo")) { env ->
        VoiceCompactRoute(env, Modifier.fillMaxSize())
    },
    Route("voz-triagem", "Voz-4-Triagem", AuraTab.Home, true, listOf("voz-ouvindo")) { env ->
        VoiceTriageRoute(env, Modifier.fillMaxSize())
    },
    Route("voz-modos", "Voz-5-Modos", AuraTab.Home, false, emptyList()) { env ->
        VoiceModesRoute(env, Modifier.fillMaxSize())
    },

    Route("projetos", "Projetos", AuraTab.Projects, true, listOf("projeto-ideia", "projeto-detalhe", "vazios", "reformar-apps")) { env ->
        ProjectsScreen(
            env.gateway, Modifier.fillMaxSize(), onReformApp = { env.open("reformar-apps") },
            startIdea = env.projectIdea, onStartIdeaHandled = env.onProjectIdeaHandled,
        )
    },
    Route("projeto-ideia", "Projeto-Ideia", AuraTab.Projects, false, listOf("projeto-novo", "reformar-apps")),
    Route("projeto-novo", "Projeto-Novo", AuraTab.Projects, false, listOf("projeto-detalhe")),
    Route("projeto-detalhe", "Projeto-Detalhe", AuraTab.Projects, true, listOf("artefatos")),
    Route("artefatos", "Artefatos", AuraTab.Projects, true, listOf("artefato-detalhe", "vazios")),
    Route("artefato-detalhe", "Artefato-Detalhe", AuraTab.Projects, true, emptyList()),

    Route("dispositivos", "Dispositivos", AuraTab.Devices, true, listOf("dispositivo-detalhe", "conexoes", "vazios")) { env ->
        DevicesScreen(env.gateway, Modifier.fillMaxSize(), onOpenConnections = { env.open("conexoes") })
    },
    Route("dispositivo-detalhe", "Dispositivo-Detalhe", AuraTab.Devices, true, listOf("reformar-apps")),
    // Reformar apps: assistente em tela cheia (sem barra de abas), aberto pelo Início, por Projetos e por Dispositivos.
    // Ordem: Apps (escolher APK) -> Escopo -> Reversão -> Projeto -> Fidelidade; Ferramentas é a página de apoio.
    // Os corpos moram em ui/screens/reform/ReformRoutes.kt.
    Route("reformar-apps", "Reformar-1-Apps", null, true, listOf("reformar-escopo", "reformar-ferramentas")) { env ->
        ai.aura.app.ui.screens.reform.ReformAppsRoute(env)
    },
    Route("reformar-escopo", "Reformar-2-Escopo", null, true, listOf("reformar-reversao")) { env ->
        ai.aura.app.ui.screens.reform.ReformScopeRoute(env)
    },
    Route("reformar-reversao", "Reformar-3-Reversao", null, true, listOf("reformar-projeto", "bastidores")) { env ->
        ai.aura.app.ui.screens.reform.ReformReversionRoute(env)
    },
    Route("reformar-projeto", "Reformar-6-Projeto", null, true, listOf("reformar-fidelidade", "reformar-ferramentas")) { env ->
        ai.aura.app.ui.screens.reform.ReformProjectRoute(env)
    },
    Route("reformar-fidelidade", "Reformar-4-Fidelidade", null, true, listOf("reformar-projeto")) { env ->
        ai.aura.app.ui.screens.reform.ReformFidelityRoute(env)
    },
    Route("reformar-ferramentas", "Reformar-5-Ferramentas", null, true, emptyList()) { env ->
        ai.aura.app.ui.screens.reform.ReformToolsRoute(env)
    },

    Route("atividade", "Atividade", AuraTab.Activity, true, listOf("chat-aprovacao", "intercorrencias", "vazios")) { env ->
        ActivityScreen(
            env.gateway, Modifier.fillMaxSize(),
            onStartRequest = { env.openTab(AuraTab.Home) },
            // Reabre a tarefa no chat: o Início restaura por jobId.
            onOpenJob = env.openJob,
        )
    },

    // Ajustes abre pelo avatar do Início; nada aqui exige o motor para abrir.
    Route("ajustes", "Ajustes", null, false, listOf("capacidades", "memoria", "checkup", "notificacoes", "voz-modos", "conexoes", "permissoes", "bastidores", "sessoes")) { env ->
        SettingsScreen(env.gateway, onClose = env.back, modifier = Modifier.fillMaxSize(), settings = env.settings, onOpenScreen = { env.open(it) },
            onOpenConnections = { env.open("conexoes") })
    },
    Route("capacidades", "Capacidades", null, true, listOf("capacidade-detalhe")),
    Route("capacidade-detalhe", "Capacidade-Detalhe", null, true, emptyList()),
    Route("memoria", "Memoria", null, true, emptyList()),
    Route("checkup", "Checkup", null, false, listOf("intercorrencias")) { env ->
        ai.aura.app.ui.screens.checkup.CheckupRoute(
            env.gateway, env.termuxInstalled, env.termuxPermissionGranted, env.onRequestTermuxPermission,
            { env.open("ajustes") }, env.onOpenUrl, env.back,
        )
    },
    Route("notificacoes", "Notificacoes", null, false, emptyList()) { env ->
        ai.aura.app.ui.screens.settings.NotificationsScreen(onBack = env.back, modifier = Modifier.fillMaxSize())
    },
    // Termux e motor, contas e permissões do Android numa tela só. Abre sempre, com ou sem motor.
    Route("conexoes", "Conexoes", null, false, emptyList()) { env ->
        ai.aura.app.ui.screens.connections.ConnectionsScreen(
            env.gateway, env.link, env.onEngineAction, env.onOpenUrl, env.back, Modifier.fillMaxSize(),
        )
    },
    Route("permissoes", null, null, false, emptyList()),
    // Sessões do Claude Code no Termux: tela cheia, abre sempre (sem motor mostra o aviso e as conversas já vistas).
    // Sem arquivo de design: nasceu depois das 45 telas.
    Route("sessoes", null, null, false, emptyList()) { env ->
        ai.aura.app.ui.screens.sessions.SessionsScreen(
            env.gateway, env.link, env.onEngineAction,
            onOpenSession = env.openSession, onNewChat = { env.openTab(AuraTab.Home) }, onBack = env.back,
            modifier = Modifier.fillMaxSize(),
        )
    },

    Route("intercorrencias", "Intercorrencias", null, false, emptyList()),
    Route("vazios", "Vazios", null, false, emptyList()),
    Route("link-aberto", "Link-Aberto", null, false, emptyList()),
    Route("bastidores", "Bastidores", null, true, emptyList()) { env ->
        ai.aura.app.ui.screens.backstage.BackstageRoute(env)
    },
)

/** Comando "/" do chat: abre a tela certa. Nunca vai à IA. */
internal fun openChatTarget(env: ScreenEnv, target: ai.aura.app.ui.screens.chat.AppTarget, rest: String) {
    when (target) {
        ai.aura.app.ui.screens.chat.AppTarget.Projects ->
            if (rest.isNotBlank()) env.startProjectIdea(rest) else env.openTab(AuraTab.Projects)
        ai.aura.app.ui.screens.chat.AppTarget.Reform -> env.open("reformar-apps")
        ai.aura.app.ui.screens.chat.AppTarget.Connections -> env.open("conexoes")
        ai.aura.app.ui.screens.chat.AppTarget.Settings -> env.open("ajustes")
        ai.aura.app.ui.screens.chat.AppTarget.Activity -> env.openTab(AuraTab.Activity)
        ai.aura.app.ui.screens.chat.AppTarget.Voice -> env.open("voz-ouvindo")
        ai.aura.app.ui.screens.chat.AppTarget.Devices -> env.openTab(AuraTab.Devices)
        ai.aura.app.ui.screens.chat.AppTarget.Sessions -> env.open("sessoes")
    }
}

/** A rota existe e tem tela? Botão que leva a uma rota sem tela ficaria mudo: quem chama esconde o botão. */
fun routeHasScreen(id: String): Boolean = ROUTES.any { it.id == id && it.screen != null }

/** Pilha de uma aba: o Início é sempre a raiz, então Voltar numa aba leva ao Início. */
fun tabStack(tab: AuraTab): List<String> = when (tab) {
    AuraTab.Home -> listOf("inicio")
    AuraTab.Projects -> listOf("inicio", "projetos")
    AuraTab.Devices -> listOf("inicio", "dispositivos")
    AuraTab.Activity -> listOf("inicio", "atividade")
}

/** Abre [id] por cima. Rota sem tela (pending), desconhecida ou já no topo: a pilha não muda. */
fun pushRoute(stack: List<String>, id: String): List<String> =
    if (stack.last() == id || ROUTES.none { it.id == id && it.screen != null }) stack else stack + id

/** Voltar: tira o topo. Na raiz devolve null: só aí o sistema pode fechar o app. */
fun popRoute(stack: List<String>): List<String>? = if (stack.size > 1) stack.dropLast(1) else null
