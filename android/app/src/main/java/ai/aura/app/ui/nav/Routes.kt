package ai.aura.app.ui.nav

import ai.aura.app.ui.AuraTab
import ai.aura.app.ui.OnboardingHost
import ai.aura.app.ui.engine.EngineGateway
import ai.aura.app.ui.screens.ActivityScreen
import ai.aura.app.ui.screens.DevicesScreen
import ai.aura.app.ui.screens.HomeScreen
import ai.aura.app.ui.screens.ProjectsScreen
import ai.aura.app.ui.screens.settings.SettingsScreen
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
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
)

// id, arquivo do design, aba, needsEngine, arestas. Arestas = Fluxo-do-usuario + grafo do plano; as que
// o plano não traz (voz cai, chat pelo teclado, chip do Início, Ferramentas da reforma, estados vazios)
// existem para toda tela ter caminho de entrada.
val ROUTES: List<Route> = listOf(
    Route("onb-boas-vindas", "Onb-1-Boas-vindas", null, false, listOf("onb-termux")) { env ->
        OnboardingHost(
            env.gateway, env.onOpenUrl, env.onRequestTermuxPermission, env.onOpenTermux,
            env.termuxInstalled, env.termuxPermissionGranted, env.onOnboardingFinished, Modifier.fillMaxSize(),
        )
    },
    Route("onb-termux", "Onb-2-Termux", null, false, listOf("onb-contas")),
    Route("onb-contas", "Onb-3-Contas", null, true, listOf("inicio")),

    Route("inicio", "Main", AuraTab.Home, true, listOf("chat-entendi", "chat-teclado", "voz-ouvindo", "projetos", "dispositivos", "atividade", "ajustes", "conexoes")) { env ->
        HomeScreen(
            env.gateway, Modifier.fillMaxSize(),
            onChatOpenChange = env.onChatOpenChange,
            onOpenSettings = { env.open("ajustes") },
            openJobId = env.openJobId,
            onOpenJobHandled = env.onOpenJobHandled,
            draft = env.draft,
            onDraftChange = env.onDraftChange,
        )
    },
    Route("chat-entendi", "Chat-1-Entendi", AuraTab.Home, true, listOf("chat-executando")),
    Route("chat-teclado", "Chat-2-Teclado-Comandos", AuraTab.Home, true, listOf("chat-entendi")),
    Route("chat-executando", "Chat-3-Executando", AuraTab.Home, true, listOf("chat-aprovacao", "chat-resultado", "bastidores")),
    Route("chat-aprovacao", "Chat-4-Aprovacao", AuraTab.Home, true, listOf("chat-resultado")),
    Route("chat-resultado", "Chat-5-Resultado", AuraTab.Home, true, listOf("link-aberto", "artefato-detalhe", "dispositivo-detalhe")),
    Route("voz-ouvindo", "Voz-1-Ouvindo", AuraTab.Home, false, listOf("voz-compacta", "voz-interrompida", "chat-entendi")),
    Route("voz-interrompida", "Voz-2-Interrompida", AuraTab.Home, false, listOf("voz-ouvindo")),
    Route("voz-compacta", "Voz-3-Compacta", AuraTab.Home, false, listOf("voz-triagem")),
    Route("voz-triagem", "Voz-4-Triagem", AuraTab.Home, true, emptyList()),
    Route("voz-modos", "Voz-5-Modos", AuraTab.Home, false, emptyList()),

    Route("projetos", "Projetos", AuraTab.Projects, true, listOf("projeto-ideia", "projeto-detalhe", "vazios")) { env ->
        ProjectsScreen(env.gateway, Modifier.fillMaxSize())
    },
    Route("projeto-ideia", "Projeto-Ideia", AuraTab.Projects, false, listOf("projeto-novo")),
    Route("projeto-novo", "Projeto-Novo", AuraTab.Projects, false, listOf("projeto-detalhe")),
    Route("projeto-detalhe", "Projeto-Detalhe", AuraTab.Projects, true, listOf("artefatos")),
    Route("artefatos", "Artefatos", AuraTab.Projects, true, listOf("artefato-detalhe", "vazios")),
    Route("artefato-detalhe", "Artefato-Detalhe", AuraTab.Projects, true, emptyList()),

    Route("dispositivos", "Dispositivos", AuraTab.Devices, true, listOf("dispositivo-detalhe", "conexoes", "vazios")) { env ->
        DevicesScreen(env.gateway, Modifier.fillMaxSize())
    },
    Route("dispositivo-detalhe", "Dispositivo-Detalhe", AuraTab.Devices, true, listOf("reformar-apps")),
    Route("reformar-apps", "Reformar-1-Apps", AuraTab.Devices, true, listOf("reformar-escopo")),
    Route("reformar-escopo", "Reformar-2-Escopo", AuraTab.Devices, true, listOf("reformar-reversao")),
    Route("reformar-reversao", "Reformar-3-Reversao", AuraTab.Devices, true, listOf("reformar-fidelidade", "reformar-projeto", "bastidores")),
    Route("reformar-fidelidade", "Reformar-4-Fidelidade", AuraTab.Devices, true, listOf("reformar-projeto")),
    Route("reformar-ferramentas", "Reformar-5-Ferramentas", AuraTab.Devices, true, listOf("reformar-projeto")),
    Route("reformar-projeto", "Reformar-6-Projeto", AuraTab.Devices, true, listOf("reformar-ferramentas")),

    Route("atividade", "Atividade", AuraTab.Activity, true, listOf("chat-aprovacao", "intercorrencias", "vazios")) { env ->
        ActivityScreen(
            env.gateway, Modifier.fillMaxSize(),
            onStartRequest = { env.openTab(AuraTab.Home) },
            // Reabre a tarefa no chat: o Início restaura por jobId.
            onOpenJob = env.openJob,
        )
    },

    // Ajustes abre pelo avatar do Início; nada aqui exige o motor para abrir.
    Route("ajustes", "Ajustes", null, false, listOf("capacidades", "memoria", "checkup", "notificacoes", "voz-modos", "conexoes", "permissoes")) { env ->
        SettingsScreen(env.gateway, onClose = env.back, modifier = Modifier.fillMaxSize(), onOpenUrl = env.onOpenUrl)
    },
    Route("capacidades", "Capacidades", null, true, listOf("capacidade-detalhe")),
    Route("capacidade-detalhe", "Capacidade-Detalhe", null, true, emptyList()),
    Route("memoria", "Memoria", null, true, emptyList()),
    Route("checkup", "Checkup", null, false, listOf("intercorrencias")),
    Route("notificacoes", "Notificacoes", null, false, emptyList()) { env ->
        ai.aura.app.ui.screens.settings.NotificationsScreen(onBack = env.back, modifier = Modifier.fillMaxSize())
    },
    Route("conexoes", "Conexoes", null, true, emptyList()),
    Route("permissoes", null, null, false, emptyList()),

    Route("intercorrencias", "Intercorrencias", null, false, emptyList()),
    Route("vazios", "Vazios", null, false, emptyList()),
    Route("link-aberto", "Link-Aberto", null, false, emptyList()),
    Route("bastidores", "Bastidores", null, true, emptyList()),
)

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
