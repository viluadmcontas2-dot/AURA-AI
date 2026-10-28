# Registro (fonte única de estado)

Atualizado 28/10 00:27Z · branch `claude/telas-sem-termux` · SHA `merge T5` · gerado de `status.json` e `docs/design/COBERTURA.md`.

## Placar

- Tarefas: **2 de 20 feitas** · 13 rodando · 1 em revisão · 4 esperando
- COBERTURA do design: **0/66** `feito` com evidência (gate de entrega: `python -I scripts/check_design_coverage.py --release`, saída 0)
- Provas: **1 de 15** feitas, 1 parciais
- Bloqueio: Nenhum bloqueio. Memória do PC em vermelho (commit 36/28 GB): Gradle local só sob o cadeado, a CI remota é o portão.
- Falta para o APK: 18 tarefas (T1, T4, T5, T6, T7, T8, T9, T10, T11, T12, T13, T14, T15, T16, T17, T18, T19, T20); provas P1, P2, P3, P4, P6, P7, P8, P9, P10, P11, P12, P13, P14, P15

## Tarefas

| # | Tarefa | Onda | Estado | Commits | Prova |
|---|---|---|---|---|---|
| T1 | Camada offline (LocalStore, EngineLink, fila) | 1 · Base | rodando (revisão 1: 2 Important (cancelar após a entrega; disco na thread Main) carregados pela T4, que edita o mesmo arquivo; 9 Minor anotados; CI verde no cf3ebdb) | d3adb86 | RED 134 erros de compilação → GREEN local 71/71 (offline 52/52); CI após o push |
| T2 | Manifesto enxuto, ícone próprio, release pequeno | 1 · Base | feita (revisão aprovada (0 Critical, 0 Important, 7 Minor); CI verde no cf3ebdb e no 4978e38) | 0c4ea21 | ManifestTest RED 5/6 → GREEN 6/6; permissões 23→4; release 1,3 MB; revisão conferiu aapt2/apksigner/R8 |
| T3 | Motor: memória tipada e esquecer | 1 · Base | feita (revisão ✓ + 3 correções Minor mescladas; CI verde no 4978e38 (android-ci 40607983520, Engine contract CI 40607983485 e 40607977399)) | e1a1a58 4110956 3547e0c 06e2855 1789333 d1a7c72 | RED 19/20 → GREEN; revisão sem falha grave; CI Linux verde (py3.12 e 3.14) com as correções |
| T4 | Gateway ampliado e tolerante | 2 · Gateway e navegação | rodando |  |  |
| T5 | Navegação, banner global, ligação do app | 2 · Gateway e navegação | em revisão (mesclada; conferida pelo coordenador; CI verde na branch (40608305338); falta CI do head integrado) | 99ae499 b158652 | 8 testes novos (NavGraphTest 5, EngineBannerTest 3); RED 40608038465 → GREEN 40608305338; 41 rotas, 35 pendentes |
| T6 | Fluxo do Termux sem travar | 3 · Telas e fluxos | rodando |  |  |
| T7 | Permissões sob demanda e tela | 3 · Telas e fluxos | rodando |  |  |
| T8 | Checkup (autodiagnóstico) | 3 · Telas e fluxos | rodando |  |  |
| T9 | Reformar telas 1, 2 e 5 | 3 · Telas e fluxos | rodando |  |  |
| T10 | Reformar telas 3, 4 e 6 | 3 · Telas e fluxos | rodando |  |  |
| T11 | Memória, Capacidades e Detalhe | 3 · Telas e fluxos | rodando |  |  |
| T12 | Notificações (T12a), Conexões e Ajustes (T12b) | 3 · Telas e fluxos | rodando (T12a roda; T12b espera T4) |  |  |
| T13 | Regra nº 1 nas abas (primeiro da onda 3) | 3 · Telas e fluxos | rodando |  |  |
| T14 | Bastidores, intercorrências, link, vazios | 3 · Telas e fluxos | rodando |  |  |
| T15 | Chat 1–5 e Artefatos completos | 3 · Telas e fluxos | espera |  |  |
| T16 | Voz 1–5 | 3 · Telas e fluxos | rodando |  |  |
| T17 | Matriz JVM + clicar em tudo + caça a bugs | 4 · Fechamento | espera |  |  |
| T18 | Teste real nos 2 emuladores (v5→v6, com e sem Termux, REA) | 4 · Fechamento | espera |  |  |
| T19 | Entrega: build final e PR | 4 · Fechamento | espera |  |  |
| T20 | Motor: triagem, fila e cadeia REA | 2 · Gateway e navegação | rodando |  |  |

## Fila do preto (dispositivo)

| # | Item | Estado | Depende |
|---|---|---|---|
| L1a | Motor real dentro do Termux (emulador Taytech 5556; o 5554 é Android 17 de 16 KB e o Termux atual não roda lá) | rodando | - |
| L1b | Pareamento app→Termux (vira passo do passeio COM Termux no T18) | dobrada | T6 |
| L2 | Cadeia REA no Termux (Node, JDK, JADX, Apktool, rea-agents) | espera | T20 |
| L3 | Retomada após o Android matar o app (vira passo do passeio no T18) | dobrada | T13, T15 |
| F | Capturas do design (feitas) + clicar em tudo (vira o ClickEverythingTest do T17) | dobrada | T17 |

## Pedidos de processo (branco e preto)

| De | Pedido | Estado | Prova |
|---|---|---|---|
| branco | Registro único docs/REGISTRO.md atualizado a cada fase | feito | este arquivo |
| branco | Só main + branches com PR aberto; apagar a branch ao mesclar e avisar | em uso | PRs rascunho das branches de tarefa → claude/telas-sem-termux; apago após o merge (dono autorizou apagar as inúteis) |
| branco | Reconciliar main e PR #3 antes de cada tarefa e antes de cada push | em uso | git fetch + merge antes do push |
| branco | Um commit limpo por tarefa, RED e GREEN no mesmo PR | em uso | merge --no-ff por tarefa com 1 commit |
| branco | Comentário de 3 linhas no fim de cada fase (feito, provado, próximo) | em uso | PR #2 |
| branco | Delegar trabalho mecânico ao agy e listar por onda | em andamento | delegação nº 1 em docs/agy/2026-10-27-revisao-textos-t1.md: 3 de 25 aceitas |
| branco | Hook novo para o agy (hooks e settings são do dono) | aguarda o dono | já existe delegar.ps1 + guarda; criar hook novo muda configuração do dono e pede o OK dele no chat |
| preto | Tarefas longas L1–L3 em segundo plano, lotes A–F em paralelo | em andamento | L1a rodando; L1b, L2, L3 esperam T6, T20, T13/T15 |
| preto | Menos teste repetido: um teste JVM que clica em tudo; teste só do que mudou | em uso | regra nos briefs; ClickEverythingTest na T17 |
| dono | Simplicidade total: tarefas curtas, economia e otimização (28/10) | em uso | common-simples.md enviado a 15 agentes (código mínimo, ~8 testes, sem esperar a CI do RED, prazo 15 min); revisão por subagente só em dados/permissões/motor; L1b, L3 e F dobradas em T17/T18 |

## Provas

| # | Prova | Estado |
|---|---|---|
| P1 | Suíte JVM toda verde | pendente |
| P2 | Toda rota abre offline | pendente |
| P3 | Nenhum botão morto | pendente |
| P4 | Grafo completo, pending vazio | pendente |
| P5 | Manifesto sem risco | feito (ManifestTest + revisão da T2 + CI verde (cf3ebdb e 4978e38)) |
| P6 | Fila offline: cada pedido uma vez | parcial (OfflineFirstGatewayTest verde local e na CI (cf3ebdb); falta a revisão da T1) |
| P7 | v6 instala por cima do v5 | pendente |
| P8 | Passeio SEM Termux | pendente |
| P9 | Passeio COM Termux | pendente |
| P10 | Multimídia por ADB | pendente |
| P11 | Fidelidade 40 telas | pendente |
| P12 | Leve: tamanho, partida, memória | pendente |
| P13 | Motor no Linux (CI) | pendente (Engine contract CI verde (py3.12 e 3.14) no cf3ebdb; falta a suíte inteira comparada com T20) |
| P14 | Cobertura 66/66 | pendente |
| P15 | Cadeia REA no Termux | pendente |

## Fases (3 linhas cada)

**até 23:37Z**
- Feito: CI remota revivida; plano T1–T20 reconciliado com branco e preto (5db02de); comentário vermelho nos PRs #2 e #3
- Provado: android-ci verde na base fe7c7b4 e design-coverage verde às 23:08Z
- Próximo: onda 1 (T1, T2, T3) e lote 2

**23:14Z–00:00Z**
- Feito: T1, T2, T3 implementadas e mescladas localmente (0cba5ae); lote 2 (T9, T10, T12a, T14, T16, T20) despachado 23:43Z; T4, T5 e L1a despachadas 23:53–23:54Z; 1ª delegação ao agy
- Provado: testes locais: T1 71/71, T2 25/25, T3 131 (mesmas falhas do Windows); CI do cf3ebdb VERDE: android-ci 40606139883 e 40606136046, Engine contract CI 40606139873 e 40606136105, design-coverage 40606139870
- Próximo: revisões de T1 e T2; correções Minor da T3; lote 3 (T6, T7, T8, T11, T13) rodando

## 2026-10-28 — Termux, contas e teclado (claude/aura-enxuto)
- Objetivo: corrigir os três defeitos relatados no APK, sem gerar outro APK.
- Estado verificado: revisão estática do contrato T6, imports e chamadas; `currentTime` importado; fluxo ligado à ponte real e ao retorno do Android, sondagem a cada 2 s limitada a 60 s. Login abre páginas oficiais sem depender do motor. Composer com imePadding/navigationBarsPadding; Activity e Manifest já configurados, abas ocultas com teclado.
- Evidência: arquivos relidos e `git diff --check` sem erros; testes adicionados para retorno/limite do Termux e login com motor indisponível. Isso não comprova compilação nem testes verdes.
- Bloqueio: sem SDK Android; Gradle não executado por instrução explícita do dono.
- Próximo item: CI compilar e executar testes; confirmar os três fluxos no aparelho. Nenhum APK gerado ou entregue.

## 2026-10-28 — ReformLedger: contrato local sem travas jurídicas
- Objetivo: ledger JSONL encadeado com índice SQLite, métricas auditáveis, retomada idempotente e roteamento de modelos, somente stdlib.
- Estado verificado: lock_scope(apk_path, mode, goal) aceita qualquer APK sem aceite; ferramentas são apenas registradas, sem aprovação especial; custo pago mantém aprovação específica. Resumo vincula métricas ao seq; fidelidade exige test_run_id.
- Evidência TDD: testes alterados antes da implementação; RED: 17 testes, 6 falhas e 14 erros (assinatura antiga e aceite obrigatório). GREEN: 17 testes do ledger passaram, incluindo adulteração, concorrência, recuperação do índice e 16 combinações de choose_model. Comando: cd engine && python3 -m unittest discover -s tests -p test_reform_ledger.py.
- Bloqueio: suíte completa executada (166 testes), com 119 erros PermissionError ao criar sockets locais no ambiente restrito; saída em /tmp/aura-engine-tests.log. Sem rede externa, Gradle, APK ou alterações Android nesta tarefa. Branch encontrada: claude/aura-enxuto; sem push por instrução de não usar rede. Cofre Obsidian não disponibilizado no workspace.
- Próximo item: executar a suíte completa em ambiente que permita sockets locais. A validação completa não está verde.

## 2026-10-28 — Reformar apps sem travas de proteção ou confirmação
- Objetivo: permitir qualquer app e APK escolhido pelo dono nas telas Apps e Escopo.
- Estado verificado: removidos bloqueios, confirmação, garantias e ícone de cadeado sem uso; seletor OpenDocument com filtro APK entrega URI ao escopo e ao plano. Modo carro e meta preservados.
- Evidência: revisão dos arquivos alterados, buscas por símbolos removidos e `git diff --check`; testes ajustados para seleção livre, início sem confirmação, escolha de APK e cancelamento.
- Bloqueio: ambiente sem SDK; Gradle e testes Android não executados por instrução do dono. A revisão estática não comprova compilação nem testes verdes.
- Próximo item: CI compilar e executar os testes. Nenhum APK gerado.

## 2026-10-28 — Autonomia nos Ajustes, voz em tela cheia e triagem com fila real
- Objetivo: Ajustes com Autonomia (Total/Perguntar antes de apagar/Perguntar tudo) e Trava de custo ligadas ao motor; microfone e orbe abrem `voz-ouvindo`; Enviar na voz cria o pedido no chat; Triagem usa GET /v1/queue e POST /v1/inputs.
- Estado verificado: só leitura estática; testes escritos antes (SettingsGatewayTest, AutonomyStateTest, AutonomySectionTest, QueueGatewayTest, HomeVoiceEntryTest). Sem SDK: Gradle não rodado, RED/GREEN real fica para a CI.
- Bloqueio: nenhuma compilação nem teste Android executado neste ambiente.
- Próximo item: CI compilar e rodar os testes novos; conferir no aparelho a troca offline/online da autonomia.

## 2026-10-28 — Ponte da Reforma com o motor (ledger) e aviso de rede
- Objetivo: ligar as telas Reformar às rotas /v1/reform e /v1/network do motor; dados móveis nunca baixam pacote; aviso de download grande discreto.
- Estado verificado: só leitura estática. Novos: `integration/ReformGateway.kt` (interface + cliente real sobre o MESMO `AuraEngineClient.request`), `NetworkReporter.kt` (NetworkCallback, só ACCESS_NETWORK_STATE, documentada em docs/PERMISSOES.md), `SharedApkCopier.kt` (copia o content:// para Download/AURA, legível pelo Termux), `ReformController.kt` (cola das telas). Telas: Ferramentas mostra "Aguardando Wi-Fi" sem erro e o aviso >100 MB (toque mostra os MB, "Entendi" fecha, nada bloqueia); Escopo trava no motor; Reversão, Fidelidade e Projeto leem o resumo (Fidelidade nula = "Ainda não medida").
- Testes escritos antes (sem SDK, RED/GREEN real fica para a CI): ReformGatewayTest, NetworkReporterTest, ReformControllerTest, ReformToolsWifiNoticeTest.
- Bloqueio: nenhuma compilação nem teste Android executado neste ambiente. O passo `tools` exige uma reforma já criada (Escopo); antes disso "Instalar o que falta" cai no pedido pelo chat.
- Próximo item: CI compilar e rodar; motor aceitar `tools` sem reforma (ou rota própria); teste real que chame POST /verify.

## 2026-10-28 — Conexões numa tela só: login pelo motor, permissões no primeiro uso, atalhos
- Objetivo: logar as CLIs dentro do Termux (POST /v1/auth/start, GET /v1/auth/status, eventos auth.*) em vez de abrir o navegador; pedir permissões no passo certo; juntar Termux e motor, contas e permissões em Conexões e deixar Conexões a um toque.
- Estado (leitura estática, sem SDK): `AccountsAuth.kt` (estado do login, código e link, Copiar código, Abrir link, conferência a cada 2 s até Conectado, Instalar por job `system`); `ConnectionsScreen.kt` (rota `conexoes`); onboarding Onb-3 usa o mesmo estado; `OnboardingFlow` pede RUN_COMMAND ao chegar no passo do Termux e avisos ao chegar em Contas; `EngineBanner` ganhou "Abrir Conexões"; Ajustes e Dispositivos têm "Abrir Conexões"; `RealEngineGateway` mapeia `google` -> `gdrive` e trata `indisponivel`/`prompt` da resposta do start. Removidos: `ConnectAccountsScreen` e o login por página do navegador.
- Testes escritos: ConnectionsLoginTest, FirstUsePermissionsTest, ConnectionsReachabilityTest. RED/GREEN real fica para a CI (sem SDK nem gradlew aqui).
- Bloqueio: não há endpoint de instalação de CLI no motor; "Instalar" cria o job `system` "Instalar o programa de X" (aprovação em Atividade). Mensagens de erro do chat e da Reforma ainda dizem "Conexões" em texto; o atalho em botão existe no aviso global, em Ajustes e em Dispositivos.
- Próximo item: CI compilar e rodar; conferir no aparelho o diálogo do Termux e o login real de cada conta.

## 2026-10-28 — Conexões: instalar CLI, código e token do Claude pelo contrato novo do motor
- Objetivo: ligar Instalar a POST /v1/tools/install (ids gh/rclone mapeados), Claude com campo para colar o código (POST /v1/auth/code) e alternativa de token (POST /v1/auth/token), erro `indisponivel_no_termux` com ação, avisos download.notice sem bloquear e "Aguardando Wi-Fi".
- Estado (leitura estática, sem SDK): `EngineApi`/`AuraEngineClient`/`RealEngineGateway` ganharam installTool, submitAuthCode, submitAuthToken; `AccountsAuth.kt` mostra o campo e os botões Enviar código/Enviar token; token vai direto ao motor e nunca é exibido.
- Suposição não verificada: o formato dos payloads dos eventos tool.install e download.notice (usei `state`=aguardando_wifi e `waiting_wifi`); ajustar quando o contrato do motor estiver na branch.
- Testes: ConnectionsLoginTest (Instalar, não instala no Termux, código colado, token) e RealEngineGatewayTest. Não rodados (sem SDK).
