# Registro (fonte única de estado)

Atualizado 28/10 00:01Z · branch `claude/telas-sem-termux` · SHA `0cba5ae` · gerado de `status.json` e `docs/design/COBERTURA.md`.

## Placar

- Tarefas: **0 de 20 feitas** · 8 rodando · 3 em revisão · 9 esperando
- COBERTURA do design: **0/66** `feito` com evidência (gate de entrega: `python -I scripts/check_design_coverage.py --release`, saída 0)
- Provas: **0 de 15** feitas, 2 parciais
- Bloqueio: Nenhum bloqueio. Memória do PC em vermelho (commit 36/28 GB): Gradle local só sob o cadeado, a CI remota é o portão.
- Falta para o APK: 20 tarefas (T1, T2, T3, T4, T5, T6, T7, T8, T9, T10, T11, T12, T13, T14, T15, T16, T17, T18, T19, T20); provas P1, P2, P3, P4, P5, P6, P7, P8, P9, P10, P11, P12, P13, P14, P15

## Tarefas

| # | Tarefa | Onda | Estado | Commits | Prova |
|---|---|---|---|---|---|
| T1 | Camada offline (LocalStore, EngineLink, fila) | 1 · Base | em revisão | d3adb86 | RED 134 erros de compilação → GREEN local 71/71 (offline 52/52); CI após o push |
| T2 | Manifesto enxuto, ícone próprio, release pequeno | 1 · Base | em revisão | 0c4ea21 | ManifestTest RED 5/6 → GREEN 6/6; permissões 23→4; release 1,3 MB |
| T3 | Motor: memória tipada e esquecer | 1 · Base | em revisão | e1a1a58 4110956 3547e0c | RED 19/20 → GREEN 20/20; suíte 131 (mesmas 12 falhas + 1 erro do Windows) |
| T4 | Gateway ampliado e tolerante | 2 · Gateway e navegação | rodando |  |  |
| T5 | Navegação, banner global, ligação do app | 2 · Gateway e navegação | rodando |  |  |
| T6 | Fluxo do Termux sem travar | 3 · Telas e fluxos | espera |  |  |
| T7 | Permissões sob demanda e tela | 3 · Telas e fluxos | espera |  |  |
| T8 | Checkup (autodiagnóstico) | 3 · Telas e fluxos | espera |  |  |
| T9 | Reformar telas 1, 2 e 5 | 3 · Telas e fluxos | rodando |  |  |
| T10 | Reformar telas 3, 4 e 6 | 3 · Telas e fluxos | rodando |  |  |
| T11 | Memória, Capacidades e Detalhe | 3 · Telas e fluxos | espera |  |  |
| T12 | Notificações (T12a), Conexões e Ajustes (T12b) | 3 · Telas e fluxos | rodando (T12a roda; T12b espera T4) |  |  |
| T13 | Regra nº 1 nas abas (primeiro da onda 3) | 3 · Telas e fluxos | espera |  |  |
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
| L1a | Motor real dentro do Termux (emulador S23) | rodando | - |
| L1b | Pareamento app→Termux e partida pelo app | espera | T6 |
| L2 | Cadeia REA no Termux (Node, JDK, JADX, Apktool, rea-agents) | espera | T20 |
| L3 | Retomada após o Android matar o app | espera | T13, T15 |
| F | Capturas do design (feitas) + teste que clica em tudo | espera | T17 |

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

## Provas

| # | Prova | Estado |
|---|---|---|
| P1 | Suíte JVM toda verde | pendente |
| P2 | Toda rota abre offline | pendente |
| P3 | Nenhum botão morto | pendente |
| P4 | Grafo completo, pending vazio | pendente |
| P5 | Manifesto sem risco | parcial (teste local verde; falta CI e revisão) |
| P6 | Fila offline: cada pedido uma vez | parcial (OfflineFirstGatewayTest verde local; falta CI) |
| P7 | v6 instala por cima do v5 | pendente |
| P8 | Passeio SEM Termux | pendente |
| P9 | Passeio COM Termux | pendente |
| P10 | Multimídia por ADB | pendente |
| P11 | Fidelidade 40 telas | pendente |
| P12 | Leve: tamanho, partida, memória | pendente |
| P13 | Motor no Linux (CI) | pendente |
| P14 | Cobertura 66/66 | pendente |
| P15 | Cadeia REA no Termux | pendente |

## Fases (3 linhas cada)

**até 23:37Z**
- Feito: CI remota revivida; plano T1–T20 reconciliado com branco e preto (5db02de); comentário vermelho nos PRs #2 e #3
- Provado: android-ci verde na base fe7c7b4 e design-coverage verde às 23:08Z
- Próximo: onda 1 (T1, T2, T3) e lote 2

**23:14Z–00:00Z**
- Feito: T1, T2, T3 implementadas e mescladas localmente (0cba5ae); lote 2 (T9, T10, T12a, T14, T16, T20) despachado 23:43Z; T4, T5 e L1a despachadas 23:53–23:54Z; 1ª delegação ao agy
- Provado: testes locais: T1 71/71, T2 25/25, T3 131 (mesmas falhas do Windows); a CI ainda não rodou nos merges
- Próximo: revisores de T1, T2, T3; push; CI verde
