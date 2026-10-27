# Auditoria de fluxo (UX / Design Systems)

Leitura de código, sem SDK, sem rodar. Caminhos relativos a `android/app/src/main/java/ai/aura/app/`.
Legenda: **[corrigido]** já aplicado em `ui/**` neste commit; **[GPT]** fora de `ui/**`.

## Contrato de ganchos para a MainActivity

Assinatura final (todos com default; chamadas antigas continuam compilando):

```kotlin
fun AuraApp(
    gateway: EngineGateway = remember { FakeEngineGateway() },
    onOpenUrl: (String) -> Unit = {},
    onRequestTermuxPermission: () -> Unit = {},
    onOpenTermux: () -> Unit = {},
    onboardingDone: Boolean = false,
    onOnboardingFinished: () -> Unit = {},
    initialDraft: String = "",
    onSaveDraft: (String) -> Unit = {},
)
```

Como ligar (exemplo na `MainActivity`, `prefs = getSharedPreferences("aura_ui", MODE_PRIVATE)`):

```kotlin
onRequestTermuxPermission = { TermuxBridge(this).askPermission(this) },   // depois a UI reconfere capabilities
onOpenTermux = { packageManager.getLaunchIntentForPackage("com.termux")?.let(::startActivity) },
onboardingDone = prefs.getBoolean("onboarding_done", false),
onOnboardingFinished = { prefs.edit().putBoolean("onboarding_done", true).apply() },
initialDraft = prefs.getString("draft", "") ?: "", onSaveDraft = { prefs.edit().putString("draft", it).apply() },
```

Notas: `onboardingDone` vem de fora; `rememberSaveable` na UI é só espelho (rotação). Sem a ligação, o onboarding repete a cada abertura do app. O rascunho cobre o campo da aba Início (inclui o texto devolvido ao sair do chat com mensagem não enviada). Se `onSaveDraft` grava texto digitado, trate-o como dado do usuário (não logar).

## 1. Voltar / avançar

| Sev | Achado | Onde |
|---|---|---|
| Média | Voltar numa aba que não é Início fechava o app. **[corrigido]** Voltar agora leva ao Início | `ui/AuraApp.kt` (BackHandler novo) |
| Média | Voltar em Ajustes > Contas fechava os Ajustes inteiros (pulava Contas). **[corrigido]** | `ui/screens/settings/SettingsScreen.kt` (BackHandler + `showAccounts` saveable) |
| Média | Onboarding sem saída: Termux ausente travava "Continuar"; sem cérebro conectado, "Entrar" e "resto depois" ficavam desabilitados. **[corrigido]** "Pular por agora" nos dois passos (voltar um passo já existia) | `ui/screens/onboarding/OnboardingFlow.kt` |
| Média | Sair do chat (Voltar/cabeçalho) com texto digitado perdia o texto. **[corrigido]** vira rascunho da Início | `ui/screens/chat/ChatScreen.kt` |
| Baixa | Ajustes com alterações não salvas: Voltar descarta sem aviso (`dirty` existe em `SettingsState`). Sugestão: confirmar "Descartar alterações?" | `ui/screens/settings/SettingsScreen.kt` |
| OK | Chat, ApprovalSheet (fecha antes do chat), Projetos (rotas), Dispositivos (rota) têm BackHandler correto; 1 ação primária por tela no onboarding | `ChatScreen.kt:64`, `ProjectsHost.kt:133`, `DevicesTab.kt:94` |

## 2. Persistência

| Sev | Achado | Onde |
|---|---|---|
| Alta | "Onboarding concluído" só em `rememberSaveable`: sobrevive à rotação, mas toda abertura nova do app repete o onboarding. **[corrigido na UI]** com gancho `onboardingDone`/`onOnboardingFinished`; **[GPT]** falta ligar | `ui/AuraApp.kt`, `MainActivity.kt` |
| Média | Rascunho da Início se perdia ao trocar de aba (a tela sai da composição). **[corrigido]** estado sobe para o `AuraApp` e vai a `onSaveDraft`; **[GPT]** persistir em SharedPreferences | `ui/screens/home/HomeContent.kt`, `AuraApp.kt` |
| Média | Histórico de mensagens do chat vive no `ChatController` (`remember`), perde-se na rotação; só pedido/jobId voltam (o job é restaurado pelo engine). Aceitável por ora, risco baixo; futura persistência leve de `messages` em `ChatEntry` | `ChatScreen.kt:50` |
| Baixa | Aba atual, chat aberto, passo do onboarding, filtros de Atividade/Projetos, rota de Projetos e Dispositivos: já `rememberSaveable` (rotação e morte de processo ok). Nada persiste entre aberturas frias (aba volta a Início, desejável) | vários |
| Baixa | Sugestão: DataStore não é necessário; SharedPreferences via os ganchos acima basta | n/a |

## 3. Não bloquear

| Sev | Achado | Onde |
|---|---|---|
| Alta | `gateway.events.collect` sem try/catch em Atividade, Contas, Ajustes e onboarding: se o motor cai, o `Flow` real lança `IOException` e o app fecha (crash). **[corrigido]** try/catch em todos (Home, Projetos e Chat já tinham) | `ActivityTab.kt:96`, `ConnectAccountsScreen.kt:103`, `SettingsScreen.kt:77`, `AuraApp.kt` |
| Média | Toque duplo: `approve/cancel` do chat e "Começar" marcavam `busy`/`Submitting` dentro do `launch` (janela para 2 toques). **[corrigido]** marcação síncrona. O `requestId` já dá idempotência no submit | `ChatController.kt` (`act`, `submit`) |
| Média | Atividade: Aprovar/Recusar sem trava de reentrada. **[corrigido]** `actionBusy` ignora 2º toque | `ActivityTab.kt` (`launchAction`) |
| Média | Dispositivos: `requestId = "ui-${System.nanoTime()}"` não é chave de idempotência estável; `busy` protege o toque duplo, mas a tela ainda não mostra o botão desabilitado (só ignora). Sugestão: passar `busy` aos botões | `DevicesTab.kt:~100` |
| Média | Chat: polling a cada 2 s fixo, sem backoff quando `connectionLost` (continua tentando para sempre). Cancela ao sair da tela (scope da composição), ok. Sugestão: dobrar o intervalo até 15 s se falhar | `ChatController.kt` (`track`) |
| Baixa | Cada evento recarrega `gateway.jobs()` inteiro (Home, Atividade, Projetos): custo cresce com histórico; sugestão de debounce de 300 ms | `HomeContent.kt:78`, `ActivityTab.kt` |
| Baixa | `System.currentTimeMillis()` em recomposição de Atividade (`now`): só relativo de tempo, sem custo relevante | `ActivityTab.kt` |
| OK | Estados de carregando/erro/vazio/offline existem em Home, Atividade, Dispositivos, Ajustes, Projetos; todo IO fica no gateway (suspend), nenhuma chamada de rede na thread principal na UI; login com limite de 5 min cancelável | n/a |

## 4. Integration e MainActivity (só apontar)

| Sev | Achado | Onde |
|---|---|---|
| Alta | `events` reinicia com `cursor = 0` em cada coletor: cada tela que abre reprocessa todo o histórico e, nas telas, cada evento dispara `jobs()` (tempestade de requisições). Sugestão: iniciar no cursor atual (`since` = último id, ou endpoint "latest"), e/ou `shareIn` único | `integration/RealEngineGateway.kt:~149` |
| Alta | O `flow` de eventos não trata erro: uma `IOException` encerra o fluxo com exceção. Sugestão: `try/catch` dentro do `while`, com backoff (1,2s até 15s) e reconexão, sem emitir erro | `RealEngineGateway.kt:~149` |
| Média | Sem Termux/token: `error("Conecte o Termux...")` vira `IllegalStateException`; a UI mostra "Não deu certo" em vez de "Sem conexão com o Termux" (que só aparece para `IOException`). Sugestão: lançar `IOException` (ou subclasse) nesse caso | `integration/AuraEngineClient.kt:~19` |
| Média | `startActivity(ACTION_VIEW)` sem try/catch: sem navegador, `ActivityNotFoundException` fecha o app | `MainActivity.kt:~29` |
| Média | `HttpURLConnection` bloqueante não responde ao cancelamento da coroutine (até 15 s de leitura); sem retry/backoff nas chamadas. Sugestão: `readTimeout` menor para `/events` e `/health` | `AuraEngineClient.kt:~21` |
| Baixa | `routedRequests` em memória do gateway; `remember` na `MainActivity` recria o gateway na rotação (o código já refaz o route, ok). Sugestão: `ViewModel` ou instância no `Application` | `MainActivity.kt:~19` |
| OK | Nenhum log de token encontrado; IO em `Dispatchers.IO`; token cifrado no Keystore; Termux ausente retorna erro tratado (não crash) | `AuraEngineClient.kt`, `EngineCredentialStore.kt` |

## Para o GPT (resumo executável)

1. Ligar os 7 ganchos do `AuraApp` (exemplo no topo), em especial `onboardingDone`/`onOnboardingFinished` (Alta).
2. `RealEngineGateway.events`: iniciar no cursor atual, capturar erro, backoff e reconexão (Alta).
3. `AuraEngineClient.request`: lançar `IOException` quando falta token/Termux; timeouts menores em `/events`.
4. `MainActivity`: `runCatching { startActivity(...) }` para abrir o link.
5. Persistir `initialDraft`/`onSaveDraft` em SharedPreferences.

Sem prova de execução: nada foi compilado nem rodado (sem SDK). Validar no CI e no aparelho (Termux).
