# Matriz de fluxos e falhas (antes do APK)

Leitura estática, sem compilar. Base: HEAD 5fd09ad. Caminhos abreviados: `G` = integration/RealEngineGateway.kt, `C` = integration/AuraEngineClient.kt, `UI` = ui/.

## 1. Mapa: tela -> eventos e chamadas

- Gateway (G:147): UM polling compartilhado (`shareIn`, WhileSubscribed 5s). Cursor só na memória. Backoff 1s a 15s (EventBackoff).
- Onboarding (UI/AuraApp.kt, OnboardingHost): capabilities(), authStatus(), startAuth(). Eventos: auth.prompt (abre navegador), auth.done/failed (recarrega). Termux "ok" = capabilities alcançou o motor.
- Início (home/HomeContent.kt:78): jobs()+capabilities() a cada evento (qualquer tipo). Rascunho: onDraftChange -> prefs.
- Chat (chat/ChatController.kt): route(), capabilities(), submit(), jobs() (restaurar e atualizar), approve(), cancel(). Eventos só do próprio jobId, mais polling de 2s.
- Atividade (activity/ActivityTab.kt:86): jobs() a cada evento; approve()/cancel() com trava de toque duplo.
- Projetos (projects/ProjectsHost.kt:83): jobs() a cada evento; route() (prévia de risco), submit(), approve().
- Dispositivos (devices/DevicesTab.kt:83): capabilities() só ao abrir/recarregar (sem eventos); submit().
- Ajustes (settings/SettingsState.kt): policy(), models(), spendSummary(), capabilities(), jobs()+cancel() (Parar tudo). Evento policy/models.changed.
- Contas (settings/ConnectAccountsScreen.kt): authStatus(), startAuth(), submit(instalar). Eventos auth.*; plano B de 5 min.
- Quem chama o quê: jobs() = Início, Chat, Atividade, Projetos, Ajustes. route() = Chat, Projetos. approve() = Chat, Atividade, Projetos. cancel() = Chat, Atividade, Ajustes. authStatus()/startAuth() = Onboarding e Contas.

## 2. Matriz de falha

Formato: gravidade | onde | o que o usuário vê | correção mínima. (FEITO = corrigido neste commit)

### Alta
- Um job com status/risco desconhecido derruba a lista inteira | G:213-216 (`error()` em asJob, usado em jobs()) | Início/Atividade mostram "offline" para todos os jobs por causa de um | tolerar: pular o job inválido (GPT).
- Um evento malformado trava o polling para sempre | G:157,166 (getLong/getString dentro do try geral; cursor não avança) | telas param de atualizar, sem aviso | try/catch por evento, avançar o cursor (GPT).
- Voltar do Termux/navegador no onboarding mostra estado velho | UI/AuraApp.kt (tick só em toque/evento) | "Termux não encontrado" mesmo com tudo ligado | reconferir em ON_RESUME. FEITO.
- Duplo toque em pedido de Projetos cria 2 jobs | UI/projects/ProjectsHost.kt runAction (`busy` ligava dentro do launch; ask() usa UUID novo) | tarefa duplicada, 2 aprovações | trava síncrona. FEITO.
- Falha momentânea ao atualizar mata os eventos de Projetos | ProjectsHost.kt:88 (refreshJobs lançava dentro do collect) | status do projeto congela até reabrir | try/catch no collect. FEITO.
- Sem sinal de reconexão: telas que ficaram "offline" não voltam sozinhas | G:175-178 (erro só vira espera; nenhum evento "voltou") | Início fica Offline e Atividade com aviso até o toque em Tentar | emitir evento sintético `connection.restored` após falhas (GPT); telas já recarregam em qualquer evento.

### Média
- Motor offline no meio do envio | ChatController.kt:178-183 | "Sem conexão com o Termux", texto mantido, retry com mesmo requestId (idempotente) | OK; faltou teste de device.
- Evento fora de ordem / resposta velha de jobs() sobrescreve estado novo | ChatController.kt:284-292 (refresh) | após Aprovar, sheet de aprovação pode reaparecer; 2º Aprovar erra | não regredir de terminal/Running para AwaitingApproval (ui, próximo lote).
- Aprovar job já cancelado/expirado | ActivityTab.kt:134-150; ChatController.kt:110 | mensagem genérica "Toque de novo" (não ajuda) | tela recarrega (tick++), mas a mensagem deveria dizer "a tarefa mudou" (usar message do 409).
- Reinstalar CLI após falha não cria job novo | ConnectAccountsScreen.kt:162 (requestId fixo `install_cli_x`) | toque em Instalar "cria pedido" mas é o job antigo | requestId com sufixo único + trava.
- Duplo toque em Conectar abre 2 navegadores | AuraApp.kt onSignIn / ConnectAccounts connect | 2 abas de login, código do 1º invalida | ignorar se já `connecting`.
- Cursor volta a 0 ao reiniciar a escuta | G:183-193 (currentCursor percorre o histórico inteiro) | abertura lenta com histórico grande; falha no meio recomeça do zero (não reprocessa, mas atrasa) | endpoint `last_id` no motor (GPT).
- Motor reiniciado com ids menores que o cursor | G:158 (`id <= cursor` descarta) | eventos novos ignorados até reabrir o app | motor expõe `boot_id`; gateway zera cursor se mudar (GPT).
- Eventos perdidos entre saídas da aba (>5s sem assinante) | G:181 | tela só atualiza ao reabrir (Início/Atividade recarregam ao assinar) | OK; Dispositivos/Ajustes não recarregam por evento.
- Chat acompanha job apagado para sempre | ChatController.kt:284-292 (null ignorado, loop de 2s) | "em andamento" eterno | tratar null repetido como "tarefa não existe mais".
- Rotação no onboarding | AuraApp.kt `remember` (termux/accounts) | spinner "conectando" some; recarrega do motor | trocar por saveable ou aceitar (Baixa).
- Ajustes com alteração não salva | AuraApp.kt BackHandler (showSettings=false) / SettingsScreen.kt:93 | sair descarta em silêncio (aviso só dentro da tela) | diálogo "Descartar alterações?" (design).
- Termux sem permissão | MainActivity.kt:369 (runCatching) + AuraApp.kt | permissão negada = mesmo estado "não encontrado", sem dizer por quê | distinguir "motor não responde" de "sem permissão" (GPT/TermuxBridge).
- Sem navegador | MainActivity.kt:366 | nada acontece ao tocar em conectar; sem aviso | callback de falha para mostrar o link/código na tela.
- Token ausente | C:266 (IOException) -> G:175 | polling em silêncio a cada 15s; telas mostram "sem conexão" | tela de Conexões deve ser o destino do erro (mensagem já aponta).
- SharedPreferences na main thread | MainActivity.kt:378,380 (`apply()` por tecla) | sem travamento visível; risco de ANR leve no onStop | debounce de 500ms no rascunho; ler prefs fora do onCreate.

### Baixa
- Vazamento de coroutine ao sair da tela: sem vazamento relevante. Telas usam LaunchedEffect/rememberCoroutineScope (cancelam ao sair); o escopo do gateway só vive com assinantes (+5s). Gateway não é cancelado na rotação (G:144) mas fica ocioso.
- Backoff não zera após sucesso: zera (G:154). Teto 15s; a primeira espera é 1s.
- Morte do processo com rascunho: Início salvo em prefs por tecla; texto digitado dentro do Chat só vai para prefs ao sair (ChatScreen.kt:67) e fica no Bundle (perde se o usuário fecha o app pelo recentes).
- Rascunho em texto puro no prefs (allowBackup=false, sandbox do app): aceitável; não colar segredo no chat.
- `routedRequests` cresce sem limite (G:29): uma entrada por route() sem submit.
- Lista de eventos do Chat sem limite (ChatController.kt:262).

## 3. Lista de correções

### Feitas agora (ui/**, sem mudar contrato)
- AuraApp.kt: reconferir Termux/contas ao voltar ao app (ON_RESUME).
- ProjectsHost.kt: trava síncrona em runAction (duplo toque) e collect de eventos não morre por falha pontual.

### Para o GPT (motor/gateway)
1. G:213: `asJob` tolerante; `jobs()` ignora job inválido e segue.
2. G:150-171: try/catch por evento, avançar cursor mesmo em evento ruim.
3. G:175: emitir `EngineEvent("connection.restored")` na primeira resposta boa após falhas; mesmo para "connection.lost" na primeira falha (jobId null, id sintético que não colida).
4. Motor: `GET /v1/events?since=latest` (ou `last_id` em /v1/health) para evitar varrer o histórico; `boot_id` para detectar reinício.
5. Mensagem de erro do 409/404 em approve/cancel chegar até a UI (já vem em AuraEngineException.message).
6. MainActivity: debounce do rascunho; TermuxBridge: distinguir sem-permissão de motor-parado; callback quando não há navegador.
7. Teste no aparelho: matar o Termux durante o envio, rotacionar no onboarding, negar a permissão, abrir sem navegador.

### Próximo lote ui/** (Média)
- ChatController.refresh não regredir estado; mensagem "a tarefa mudou"; requestId único em instalar CLI; trava no Conectar; diálogo de descartar em Ajustes.

Risco residual: nada compilado nem testado em aparelho; as duas correções feitas precisam de CI e de prova no Android.
