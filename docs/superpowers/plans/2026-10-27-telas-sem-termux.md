# AURA AI v6 — plano "telas sem Termux"

Branch `claude/telas-sem-termux` (base `fe7c7b4`). Especificação: `AGENTS.md`, `docs/PROMPT_SESSAO_LOCAL.md`, `docs/MATRIZ_DE_TESTES.md`,
`docs/CONSTRAINTS.md` (seção "App Android"), `docs/design/INVENTARIO-2026-10-27.md` (o que falta, tela a tela).
Este arquivo é a lista de tarefas: ao concluir, marque `[x]` e escreva a evidência na linha de baixo. Registro de cada bloco em `docs/REGISTRO.md`.

## Objetivo e definição de "pronto"
APK v6 que **nunca fica morto** (regra nº 1) e instala por cima do v5 sem perder dados nem login. "Pronto" = matriz A–F de `docs/MATRIZ_DE_TESTES.md`
toda marcada, cada marca com evidência (saída de teste, print, logcat). Nenhum APK de entrega antes disso. APK de teste interno fica no emulador, nunca no chat.

## Premissas e decisões (Ruling)
1. Implementadores em paralelo, cada um em worktree próprio e com pacote próprio; merge serial pelo controlador; Gradle serializado por `scripts/gw.sh`.
   O dono pediu paralelo; o padrão da skill é sequencial. Custo se errado: conflito em `Routes.kt`/`AuraApp.kt` (resolvido no merge).
2. Sem tela "em construção". Rota ainda não feita fica em `pending` no teste do grafo (T5); o portão final (T17) exige `pending` vazio.
3. Tela que depende de dado que o motor não tem mostra o que existe e diz o que falta. Nunca inventa dado (nada de Fake em produção).
4. Permissões só sob demanda, com explicação antes do pedido do sistema. `MANAGE_EXTERNAL_STORAGE` sai do manifesto. Custo se errado: compartilhar arquivo com o Termux fica mais limitado.
5. Teste de motor Python: 12 falhas + 1 erro já existem no Windows (ambiente). T3 e T20 comparam o conjunto de falhas antes/depois; o motor completo roda no Linux pela CI (`Engine contract CI`) no PR.
6. Push na branch só depois de GREEN. PR para `main` só com suíte e build verdes. Desde 27/10 (~23:08Z) a CI do GitHub voltou (cobrança liberada pelo dono): é o portão da suíte completa e do build a cada push do controlador, em paralelo; o Gradle local fica para o teste focado. O APK que a CI guarda como artefato é interno, nunca entrega.
7. Os dois emuladores autorizados pelo dono: `emulator-5554` (Galaxy_S23_Auto, Android 17, 1080×2340) = celular com o AURA; `emulator-5556` (Taytech_M550Pro, Android 11, 1280×720) = multimídia controlada pelo AURA.
   O celular S23 físico e a central física não entram. Ao terminar: desinstalar AURA/Termux de teste e restaurar qualquer ajuste global mexido.
8. Portão de cobertura (PR #3, agente branco): `docs/design/COBERTURA.md` tem 66 linhas (45 de UI + 21 de função). Cada tarefa fecha as suas (`feito` + evidência: teste, arquivo ou print) no mesmo commit. `python -I scripts/check_design_coverage.py --release` com saída 0 é portão de entrega, junto com a matriz A–F. Pedido novo do branco ou do preto entra como linha `pendente` (VERMELHO) e só sai com prova. Só o dono muda o escopo de uma linha, registrado no PR. Custo se errado: a entrega espera por uma linha que talvez não valha.
9. Quem executa: o controlador (agente vermelho) despacha um implementador sonnet por tarefa (Gradle e integração). O agy (via `delegar.ps1`, uma vaga, `accept-edits`, sem shell, sem mexer em permissões) só faz leitura e trabalho mecânico sem Gradle; se RAM ou vaga não permitirem, segue com o sonnet. Custo se errado: onda mais lenta.
10. Ordem (agente branco, PR #3): primeiro "o app nunca fica morto sem Termux" (T13 abre a onda 3, com fumaça no emulador S23 logo após o merge), depois Termux e motor no aparelho (T6, T20), depois o resto. Custo se errado: telas bonitas antes de a regra nº 1 estar provada.

## Regras para toda tarefa (implementador e revisor)
- Trabalhe no worktree indicado. Commits pequenos, em português, terminando com a linha `Co-Authored-By: Claude Sonnet 5.5 <noreply@anthropic.com>`. Não faça `push`.
- TDD de verdade: escreva o teste, rode e veja falhar (RED, cole a saída no relatório), implemente, veja passar (GREEN). Compose UI Test com Robolectric na JVM para clicar em tudo.
- Gradle só por `bash scripts/gw.sh --dir <worktree>/android <tarefas> --offline` (um por vez na máquina; leia `EXIT=`/`BUILD` do log). Em iteração rode só o teste focado (`--tests`); a suíte completa uma vez antes de cada commit.
- Qualidade: `docs/CONSTRAINTS.md` seção "App Android" (sem novo `@Suppress`/`@Ignore`/`TODO()`, sem `onClick = {}`, nenhum segredo em log, alvo ≥ 44 dp). Não enfraqueça a barra para passar.
- Regra nº 1: sem Termux, sem motor ou sem internet, toda tela e todo botão abrem; o que depende do motor mostra mensagem clara e UMA ação. Nunca tela em branco, spinner infinito, botão mudo.
- Design é fiel: leia `docs/design/telas/<tela>.dc.html` e a imagem `.superpowers/design-png/<tela>.png` (390×844; gere com `scripts/render-design.sh <filtro>` se faltar). Um botão principal por tela, verbo + objeto, erro diz o que fazer, tela vazia convida a agir.
- Textos em português do Brasil, simples. Termo técnico só com uma frase explicando.
- Fique dentro do seu pacote. Os pontos compartilhados (`ui/nav/Routes.kt`, `AuraApp.kt`, `MainActivity.kt`, `UiEngine.kt`) só ganham linhas de registro; não reestruture.
- Não despache subagentes. Relatório completo no arquivo indicado; resposta final com no máximo 15 linhas.
- Feche as suas linhas em `docs/design/COBERTURA.md` (`feito` + evidência: teste, arquivo ou print) e as da `docs/MATRIZ_DE_TESTES.md` no mesmo commit. Sem evidência a linha fica `em andamento`; nunca marque `feito` por achar.

## Grafo (mapa de ligações)
Fonte de verdade em código: `ui/nav/Routes.kt` (T5); o teste `NavGraphTest` compara com os arquivos de `docs/design/telas` e com `Fluxo-do-usuario.dc.html`.

```mermaid
flowchart LR
  Onb1[Onb-1] --> Onb2[Onb-2 Termux] --> Onb3[Onb-3 Contas] --> Main
  Main[Início] --> Chat1[Chat-1 Entendi] --> Chat3[Chat-3 Executando] --> Chat4[Chat-4 Aprovação] --> Chat5[Chat-5 Resultado]
  Chat2[Chat-2 Comandos] -.-> Chat1
  Chat3 --> Bast[Bastidores]
  Chat5 --> Link[Link-Aberto]
  Chat5 --> ArtD[Artefato-Detalhe]
  Chat5 --> DispD[Dispositivo-Detalhe]
  Main --> Voz1[Voz-1] --> Voz3[Voz-3] --> Voz4[Voz-4]
  Voz2[Voz-2] --> Voz1
  Main --> Proj[Projetos] --> PIdeia[Projeto-Ideia] --> PNovo[Projeto-Novo] --> PDet[Projeto-Detalhe] --> Art[Artefatos] --> ArtD
  Main --> Disp[Dispositivos] --> DispD --> R1[Reformar-1] --> R2[Reformar-2] --> R3[Reformar-3] --> R4[Reformar-4] --> R6[Reformar-6]
  R5[Reformar-5] --> R6
  Disp --> Conx[Conexões]
  Main --> Atv[Atividade] --> Chat4
  Atv --> Inter[Intercorrências]
  Main -->|avatar| Aju[Ajustes] --> Cap[Capacidades] --> CapD[Capacidade-Detalhe]
  Aju --> Mem[Memória]
  Aju --> Chk[Checkup] --> Inter
  Aju --> Notif[Notificações]
  Aju --> Voz5[Voz-5 Modos]
  Aju --> Conx
  Aju --> Perm[Permissões]
```

Dependências entre tarefas: T1 → T4, T5, T13 · T2 → T7, T19 · T3 → T4, T11, T20 · T4 → T8, T11, T12, T13 · T5 → T6…T16 · T6 → T8 · T7 depende de T2 e T5 · T17 depois de T6…T16 · T18 depois de T17 e T20 · T19 depois de T18.
Ondas: **1** = T1, T2, T3 (em paralelo) · **2** = T4 e T5 (em seguida), com T20 (só Python) ao lado · **3** = T13 primeiro (+ fumaça no emulador S23 sem Termux), depois T6…T12 e T14…T16 (até 5 por vez) · **4** = T17 → T18 → T19.

## Quem fecha o quê (COBERTURA, 66 linhas)
Cada tarefa só é dada como completa depois de marcar as linhas abaixo (`feito` + evidência). `UI-nn` = tela do design, `FN-nn` = função. Fonte: coluna `Tarefa` de `docs/design/COBERTURA.md`.

| Tarefa | Linhas |
|---|---|
| T1 | FN-12 |
| T2 | FN-21 |
| T3 | FN-17 |
| T5 | UI-02, UI-07, UI-38, UI-39 |
| T6 | UI-05, FN-10 |
| T7 | UI-04 |
| T8 | UI-34, FN-11 |
| T9 | UI-40, UI-41, UI-44, FN-15 |
| T10 | UI-42, UI-43, UI-45, FN-15, FN-16 |
| T11 | UI-29, UI-30, UI-32, FN-17 |
| T12 | UI-31, UI-33, UI-35, FN-08, FN-09, FN-13, FN-18 |
| T13 | UI-07, UI-13, UI-23, UI-24, UI-25, UI-26, UI-27, UI-28, UI-33, FN-11, FN-12 |
| T14 | UI-19, UI-22, UI-36, UI-37, FN-07, FN-14, FN-19 |
| T15 | UI-10, UI-11, UI-17, UI-18, UI-20, UI-21, FN-05, FN-06, FN-12, FN-14, FN-20 |
| T16 | UI-08, UI-09, UI-12, UI-14, UI-15, UI-16, FN-01, FN-02, FN-04 |
| T17 | UI-01, UI-02, UI-03, UI-39, FN-21 |
| T18 | UI-06 |
| T20 | FN-03, FN-04, FN-16 |

## Checkpoints
- **CP1** (fim da onda 1): suíte JVM verde com a camada offline; manifesto novo montado; motor com `DELETE /v1/memory`. Matriz A (parte JVM) e C (manifesto).
- **CP2** (fim da onda 2): app abre em qualquer estado, banner "Motor desligado · Iniciar o motor" visível, grafo com todas as rotas registradas (`pending` = telas ainda não feitas).
- **CP2b** (T13 mergeada): fumaça no emulador S23 sem Termux — as 5 abas abrem, os projetos aparecem, `logcat` sem `FATAL`; print em `docs/REGISTRO.md`. É a primeira prova da regra nº 1.
- **CP3** (fim da onda 3): `pending` vazio; cada tela renderiza offline na JVM; matriz D.
- **CP4**: `OfflineMatrixTest` + `ClickEverythingTest` verdes; lint sem erro; matriz E.
- **CP5**: emulador S23 (v5 → v6 por cima; sem Termux; com Termux) e Taytech (alvo do ADB) com prints e logcat; matrizes B, C, F.
- **CP6**: build final, entrega em `C:\Users\hugov\Desktop\DESKTOP LIVE\auraai-v6.apk`, PR.

## O que falta provar (cada item tem comando ou lugar da evidência)
| # | Prova | Como | Estado |
|---|---|---|---|
| P1 | Suíte JVM toda verde | `bash scripts/gw.sh :app:testDebugUnitTest --offline` e CI `android-ci` verde no PR | ⏳ |
| P2 | Toda rota abre offline sem exceção | `OfflineMatrixTest` (T17) | ⏳ |
| P3 | Nenhum botão morto | `ClickEverythingTest` (T17) | ⏳ |
| P4 | Grafo completo: 40 telas, arestas, `pending` vazio | `NavGraphTest` (T5/T17) | ⏳ |
| P5 | Manifesto sem permissão de risco e sem cleartext global | `aapt2 dump permissions`, teste de manifesto, Lint | ⏳ |
| P6 | Fila offline: cada pedido enviado uma única vez | `OfflineFirstGatewayTest` (T1) | ⏳ |
| P7 | v6 instala por cima do v5 e mantém dados/login | emulador S23, marcador gravado antes | ⏳ |
| P8 | Passeio por todas as abas SEM Termux, sem fechar | emulador S23, prints + logcat sem `FATAL` | ⏳ |
| P9 | Passeio COM Termux: instalar, parear, motor online, tarefa roda | emulador S23 + `com.termux_1021.apk` | ⏳ |
| P10 | Controlar a multimídia por ADB (capturar tela, instalar app) | S23 → Taytech (`10.0.2.2:5557`) | ⏳ |
| P11 | Fidelidade: print do app × print do design, 40 telas | `docs/design/CONFERENCIA.md` (T17/T18) | ⏳ |
| P12 | Leve: tamanho do APK, partida a frio, memória | `am start -W`, `dumpsys meminfo`, tamanho | ⏳ |
| P13 | Motor completo no Linux | CI `Engine contract CI` verde no commit final do PR | ⏳ |
| P14 | Cobertura do design: 66/66 `feito` com evidência | `python -I scripts/check_design_coverage.py --release` (saída 0) e CI `design-coverage` | ⏳ |
| P15 | Cadeia REA no Termux: ferramentas instaladas, `tools/list` real, um app de teste reformado e medido | testes do T20 + emulador S23 com Termux (T18) | ⏳ |

## Tarefas

## Task 1: Camada offline (GREEN dos testes já escritos)
Descrição: os três arquivos de teste em `android/app/src/test/java/ai/aura/app/offline/` (`LocalStoreTest`, `EngineLinkTest`, `OfflineFirstGatewayTest`) são a especificação e hoje não compilam (RED). Crie o código de produção em `android/app/src/main/java/ai/aura/app/offline/`.
- `LocalStore(dir)`: JSON em disco, gravação atômica (arquivo temporário + rename), arquivo corrompido ou sobra de gravação interrompida devolve o último valor bom ou `null`, nomes validados (sem `/`, `..`, vazio).
- `EngineLink`: `StateFlow<LinkState>` (Unknown, Online, Starting, Offline(NoTermux | NoPermission | NoToken | EngineStopped)) com as prioridades que `EngineLinkTest` fixa; `TermuxProbe`; `EngineUnreachableException : IOException` com `OfflineReason`.
- `OfflineFirstGateway(delegate, store, link)`: leitura com cache (mostra o último dado bom do disco quando o motor não responde), fila local de pedidos (`JobStatus.WaitingEngine`, id `local_<requestId>`), envio em ordem e exatamente uma vez (Mutex; rotear e depois enviar), histórico de rejeitados, estado do link reagindo a `connection.lost` / `connection.restored`.
- Alterar: `ui/engine/UiEngine.kt` (`JobStatus.WaitingEngine("waiting_engine")`) e corrigir os `when` exaustivos que quebram: `ActivityModels.kt:26-31` e `:34-42`; `ChatModels.kt:104-110` e `:112-120`; `ChatParts.kt:240-248`; `ProjectModels.kt:52-59`; `ProjectDetailScreen.kt:220-228` (revise também os `when` com `else` de `ChatParts.kt:229-236, 304-329, 370-379` e `ActivityModels.kt:66-74`: o `else` não pode esconder o novo estado). `integration/TermuxBridge.kt` passa a implementar `TermuxProbe`. `integration/AuraEngineClient.kt` lança `EngineUnreachableException` (`NoToken` sem credencial; `EngineStopped` em recusa ou tempo esgotado), mantendo `IOException`.
- Fora de escopo: ligar na UI (T5) e emitir `connection.lost/restored` no `RealEngineGateway` (T4).
Aceite:
- [ ] `bash scripts/gw.sh :app:testDebugUnitTest --tests "ai.aura.app.offline.*" --offline` verde; saída do RED (falha de compilação) colada no relatório.
- [ ] Suíte completa `:app:testDebugUnitTest --offline` verde e `:app:assembleDebug --offline` compila.
- [ ] Nenhum novo `@Suppress`/`@Ignore`/`TODO()`.
Depende de: nada. Arquivos: `offline/*.kt` (novos), `ui/engine/UiEngine.kt`, os `when` listados, `TermuxBridge.kt`, `AuraEngineClient.kt`. Tamanho: M. Worktree: `wt-t1`.

## Task 2: Manifesto, ícone e segurança do APK
Descrição: o APK precisa parecer e ser um app sério para o Android e para o dono. Hoje: ícone padrão (robô), `usesCleartextTraffic="true"` global, permissões demais e a de "gerenciar todos os arquivos".
- Ícone adaptável com camada monocromática (`res/mipmap-anydpi-v26/ic_launcher.xml` e `ic_launcher_round.xml`) com o orbe do design; `label="AURA AI"`; `icon`/`roundIcon` no manifesto.
- `network_security_config.xml`: texto puro só para `127.0.0.1` e `localhost`; tirar `usesCleartextTraffic` global.
- `allowBackup="false"` com regras de extração (token é preso ao Keystore do aparelho).
- Permissões: listar o que o código usa de verdade (`grep`), remover o que não tem função (câmera, dispositivos próximos, `MANAGE_EXTERNAL_STORAGE` e o que mais não tiver uso), manter só o necessário (INTERNET para o motor em 127.0.0.1, `com.termux.permission.RUN_COMMAND`, microfone, notificações, instalar pacotes se o fluxo "Instalar APK" usar). Cada uma entra em `docs/PERMISSOES.md` (tabela: permissão, por que, quando pede, o que acontece se negar).
- Release: `isMinifyEnabled = true` + `isShrinkResources = true` com regras de manutenção; não depurável; MESMA chave de assinatura do v5 (assinar com a chave de debug que já existe) para instalar por cima. Não mude `applicationId`.
- `exported` correto; `queries` só para o pacote do Termux.
Aceite:
- [ ] Teste de manifesto (Robolectric ou parser) falha se aparecer permissão fora da lista de `docs/PERMISSOES.md`.
- [ ] `aapt2 dump permissions` do APK debug e do release bate com `docs/PERMISSOES.md`; `aapt2 dump badging` mostra ícone adaptável e não depurável (release).
- [ ] `bash scripts/gw.sh :app:lintDebug --offline` sem erro novo (se o Lint não estiver disponível, diga e registre).
- [ ] Suíte e `assembleDebug` verdes. O release só é montado para conferir (`assembleRelease`), nunca entregue.
Depende de: nada. Arquivos: `AndroidManifest.xml`, `res/xml/*`, `res/mipmap-anydpi-v26/*`, `res/drawable/ic_launcher_*.xml`, `app/build.gradle.kts` (bloco release), `proguard-rules.pro`, `docs/PERMISSOES.md`. Tamanho: M. Worktree: `wt-t2`.

## Task 3: Motor — esquecer a memória e dar tipo ao que ele lembra
Descrição (Python, `engine/`): hoje `/v1/memory` só lê e grava. A tela Memória (T11) precisa de "Esquecer" e "Esquecer tudo".
- `DELETE /v1/memory/{id}` → 204 (ou 404); `DELETE /v1/memory` → `{"deleted": n}`; ambos com token (401 sem ele, como as outras rotas).
- Item de memória: `{id, kind, text, source, createdAt}` com `kind ∈ preference|project|device|rule` (padrão `preference`); `POST` aceita `kind` e `source` opcionais; arquivo antigo (sem `id`/`kind`) é lido e completado sem perder nada (gravação atômica com o helper que já existe).
- Atualizar `docs/ENGINE_CONTRACT.md`. Teste novo em `engine/tests/`.
Aceite:
- [ ] `cd engine && python -m unittest tests.test_memory_forget -v` verde, com RED antes.
- [ ] Antes e depois: a lista de falhas do `python -m unittest` completo é a mesma (12 falhas + 1 erro de ambiente do Windows; não conserte, não esconda).
- [ ] Nenhum segredo em log ou fixture.
Depende de: nada. Arquivos: `engine/aura_engine/*.py` (memória e servidor), `engine/tests/test_memory_forget.py`, `docs/ENGINE_CONTRACT.md`. Tamanho: S. Worktree: `wt-t3`.

## Task 4: Gateway ampliado e tolerante
Descrição: a UI precisa de dados que a interface `EngineGateway` não expõe e o `RealEngineGateway` é frágil (auditoria 27/10).
- Interface + `RealEngineGateway` + `OfflineFirstGateway` (repassa/cacheia) + `FakeEngineGateway` (só para teste/preview, movido para `src/test`/`src/debug`): `memory()`, `forgetMemory(id)`, `forgetAllMemory()`, `skills()`, `health()`, `usage()`. Tipos novos em `ui/engine/UiEngine.kt`.
- `asJob` tolerante: status desconhecido → `Failed` com `errorCode = "unknown_status:<valor>"`; risco desconhecido → `Critical`; job malformado é descartado sem derrubar a lista. Eventos: `try/catch` por evento e o cursor SEMPRE avança; emitir `connection.lost` e `connection.restored` (ids negativos únicos); cursor refeito quando o motor reinicia (ids menores que o cursor).
- Erros do motor (`AuraEngineException`): 404/409 levam a mensagem do motor para a UI (hoje vira "erro genérico"); `requestId` único na instalação de CLI; `routedRequests` limitado (200).
- Para testar sem rede: extrair `interface EngineApi` (implementada por `AuraEngineClient`) e testar o `RealEngineGateway` com um falso.
- Remover `FakeEngineGateway()` como padrão de `AuraApp.kt:100`; `ExampleData` e Fake saem de `src/main`.
Aceite:
- [ ] Testes novos: job desconhecido não derruba a lista; evento ruim não trava o polling; reinício do motor refaz o cursor; 409 mostra a mensagem do motor; `connection.*` emitidos.
- [ ] Suíte completa e `assembleDebug` verdes; `grep -rn "FakeEngineGateway\|ExampleData" android/app/src/main` sem resultado.
Depende de: T1 (e do contrato de T3). Arquivos: `ui/engine/UiEngine.kt`, `integration/*`, `offline/OfflineFirstGateway.kt`, `AuraApp.kt` (só o default). Tamanho: M. Worktree: `wt-t4`.

## Task 5: Esqueleto de navegação, banner global e ligação do app
Descrição: uma rota para cada tela do design e um banner que mostra o estado do motor em qualquer tela.
- `ui/nav/Routes.kt`: registro (`id`, arquivo do design, aba, `needsEngine`, arestas de saída); `ScreenRegistry` (id → composable); pilha de navegação salva (`rememberSaveable`, só textos); botão Voltar do sistema desempilha. Abas do design: Início, Projetos, Dispositivos, Atividade; Ajustes abre pelo avatar. Telas ainda não feitas ficam em `pending` (sem "em construção").
- `NavGraphTest`: todo arquivo de `docs/design/telas` (menos os 5 de documentação) tem rota; toda aresta aponta para rota existente; tudo alcançável a partir das entradas; imprime o `pending`.
- `EngineBanner` global ("Motor desligado" + UMA ação por motivo: NoTermux → Instalar o Termux; NoPermission → Permitir; NoToken → Conectar; EngineStopped → Iniciar o motor; Starting → "Iniciando…" sem botão), ligado ao `EngineLink`; a ação chama um callback (a implementação real é do T6).
- `MainActivity`: `OfflineFirstGateway(RealEngineGateway(...), LocalStore(filesDir/"aura"), EngineLink(TermuxBridge))`. Teste Robolectric do banner (texto e ação por motivo).
Aceite:
- [ ] `NavGraphTest` e teste do banner verdes (RED primeiro). Suíte e `assembleDebug` verdes.
- [ ] App abre em qualquer estado de `LinkState`; Voltar nunca fecha por engano numa tela interna.
Depende de: T1 (e T4 para o gateway final). Arquivos: `ui/nav/*` (novos), `ui/AuraApp.kt`, `MainActivity.kt`, `ui/components/EngineBanner.kt`. Tamanho: M. Worktree: `wt-t5`.

## Task 6: Fluxo do Termux sem travar
Descrição: hoje `TermuxBridge.pair()` e `startEngine()` não têm chamador (o app nunca pareia) e o onboarding passo 2 não explica o que fazer. Ler `docs/design/plano-autonomia-termux.md` e `docs/design/matriz-fluxos-falhas.md`.
- Estados claros: Termux instalado? permissão `RUN_COMMAND` concedida? `allow-external-apps` ligado? Cada linha com estado e UM botão de correção (instalar, permitir, "Copiar comando" para colar no Termux, "Já colei"). O que o app consegue corrigir sozinho ele corrige (abrir o instalador, pedir a permissão, iniciar o motor); o que só o Termux pode fazer vira UM comando pronto para colar (`allow-external-apps=true`), e "Já colei" confere sozinho.
- Pareamento ponta a ponta: `pair()` com resultado (PendingIntent) → token cifrado → `health` responde; `startEngine()` com resultado e tempo limite; nunca ficar em "Iniciando…" para sempre; cancelar sempre possível.
- Ações do banner (T5) implementadas aqui; seção "Termux" em Conexões.
- Testes com ponte falsa: cada transição de estado, tempo esgotado, usuário cancela, Termux some no meio.
Aceite: testes de estado verdes; `Continuar` nunca fica mudo (se desligado, diz o porquê); suíte e `assembleDebug` verdes.
Depende de: T1, T5. Arquivos: `integration/TermuxBridge.kt`, `onboarding/*`, `TermuxSetupRules.kt`, `ui/screens/connections/*`. Tamanho: M. Worktree: `wt-t6`.

## Task 7: Permissões sob demanda e tela "Permissões"
Descrição: acabar com os 6 pedidos na primeira abertura (câmera, microfone, dispositivos próximos, notificações, música/áudio, fotos/vídeos) e com a ida à tela "Acesso a todos os arquivos". Cada permissão só é pedida quando a função é usada, antes com uma folha do próprio app (o que é, por que, "Permitir" / "Agora não") e sem repetir depois de negada duas vezes (leva às configurações). Tela Ajustes → Permissões: cada linha com estado e botão de correção. Seguir `docs/PERMISSOES.md` (T2).
Aceite: nenhum pedido do sistema antes da primeira ação do usuário (teste + emulador); teste de cada folha; tela renderiza offline; suíte verde.
Depende de: T2, T5. Arquivos: `AppPermissions.kt`, `MainActivity.kt`, `ui/screens/permissions/*`. Tamanho: M. Worktree: `wt-t7`.

## Task 8: Checkup (autodiagnóstico)
Descrição: tela `Checkup.dc.html` — Termux instalado, `RUN_COMMAND`, `allow-external-apps`, motor responde (`/v1/health`), contas (`authStatus`), permissões do Android (bateria, notificações, armazenamento), ADB. Cada linha com estado (ok/alerta/erro) e UM botão de correção — automática quando o app consegue (permissões do Android, abrir o Termux, iniciar o motor) ou "Copiar comando" quando só o Termux pode —; "6/8 ok" no topo; roda local e não bloqueia se o motor está desligado. Fecha FN-11 junto com T13 (retomada após o Android encerrar o processo).
Aceite: um teste por linha e por estado; toda correção leva à tela certa; tela abre sem motor; suíte verde.
Depende de: T5, T6. Arquivos: `ui/screens/checkup/*`, `checks/*`. Tamanho: M. Worktree: `wt-t8`.

## Task 9: Reformar app — telas 1 (Apps), 2 (Escopo) e 5 (Ferramentas)
Descrição: `Reformar-1-Apps`, `Reformar-2-Escopo`, `Reformar-5-Ferramentas`; ler `docs/design/reformar-apps.md` e `docs/PROMPT_SESSAO_LOCAL.md` (integração com a skill REA). Listar apps do aparelho alvo (job `adb`), escolher escopo (10 min / 25 min / 1h30, garantias, meta 90%), ferramentas necessárias (Node, JDK, JADX, Apktool, adb, REA) com "Instalar o que falta". O app de ré/câmera é "Protegido" (nunca mexer). Sem dado que o motor não tenha: mostrar o que existe.
Aceite: telas abrem offline com estado vazio útil; fluxo 1 → 2 → 3 navega; testes de cada estado; suíte verde.
Depende de: T5. Arquivos: `ui/screens/reform/*`. Tamanho: M. Worktree: `wt-t9`.

## Task 10: Reformar app — telas 3 (Reversão), 4 (Fidelidade) e 6 (Projeto)
Descrição: `Reformar-3-Reversao`, `Reformar-4-Fidelidade`, `Reformar-6-Projeto`: medidores (%), passos com "Prova:", checkpoint, Pausar, comparador Original × Reformado, peças reaproveitáveis, "Continuar reforma". Progresso derivado dos eventos/resultados dos jobs (o motor não emite `reform.*`).
Aceite: idem T9; medidores têm teste de valor e de acessibilidade (texto lido pelo leitor de tela); suíte verde.
Depende de: T5 (e T9 para a navegação 2 → 3). Arquivos: `ui/screens/reform/*`, `ui/components/Meter.kt`. Tamanho: M. Worktree: `wt-t10`.

## Task 11: Memória, Capacidades e Detalhe
Descrição: `Memoria`, `Capacidades`, `Capacidade-Detalhe`. Memória: filtros, "Esquecer" e "Esquecer tudo" (com confirmação) usando T3/T4. Capacidades: 4 grupos + "Em uso agora" (derivado dos jobs) + "Importar skill" (aviso "não libera comandos"); Detalhe: modo por permissão (sempre / perguntar antes / desligado) gravado por `PUT /v1/policy`, "Funciona com" Claude/Gemini/agy.
Aceite: testes de cada ação, estado vazio e erro; offline mostra o último dado salvo; suíte verde.
Depende de: T3, T4, T5. Arquivos: `ui/screens/memory/*`, `ui/screens/capabilities/*`. Tamanho: M. Worktree: `wt-t11`.

## Task 12: Notificações, Conexões e menu de Ajustes
Descrição: `Notificacoes` (canais e serviço de primeiro plano: Enquanto trabalha, Precisa de você, Pronto, Ouvindo; ações Aprovar/Ver/Pausar/Parar; ações que apagam abrem o app), `Conexoes` (uso da semana, entrada por Ajustes e pelo chip do Início), `Ajustes` (menu do design: Perfil, Capacidades, Memória, Checkup, Aparência, Notificações, Microfone, Permissões). Corrigir "Parar tudo" (hoje morto). Nenhuma seção de Ajustes pode exigir motor para abrir. Fecha também FN-08 (trava de custo: nada de API paga sem aprovação), FN-09 (cérebros Claude Code, Codex, Gemini/Google e agy, com "indisponível" honesto) e FN-18 (modo carro e Leitura fácil em Aparência).
Aceite: notificações aparecem no emulador (T18); testes de cada canal; "Parar tudo" com confirmação e resultado visível; suíte verde.
Depende de: T4, T5. Arquivos: `ui/screens/settings/*`, `ui/screens/connections/*`, `notifications/*`. Tamanho: M. Worktree: `wt-t12`.

## Task 13: Regra nº 1 nas abas (Projetos, Atividade, Dispositivos, Ajustes, histórico)
Descrição: tudo que é local aparece sempre. Projetos gravados em disco (criar, editar, arquivar, apagar, offline), histórico do chat em disco, rascunhos; Atividade com a fila local e "Tentar de novo" ligado (`onRetry`) e badge; Dispositivos com "Este celular" e lista local; Dispositivo-Detalhe, Artefatos (ler `artifacts/`), abas Arquivos/Decisões do projeto; restauração de Bundle sem `ClassCastException`/`IllegalArgumentException`; leituras de preferências fora da thread principal; `AuraApp`/Início com o estado "Motor desligado" e ação.
Aceite: testes por aba nos 3 estados (online, offline, vazio); matar e reabrir o processo mantém projetos e rascunhos (teste de persistência) e retoma o trabalho no ponto salvo (FN-12: o app relê disco e motor ao reabrir); suíte verde.
Depende de: T1, T4, T5. Arquivos: `ui/screens/projects/*`, `activity/*`, `devices/*`, `home/*`, `chat/ChatController.kt`. Tamanho: L (se passar de 8 arquivos, dividir e avisar). Worktree: `wt-t13`.

## Task 14: Bastidores, Intercorrências, Link-Aberto e Vazios
Descrição: `Bastidores` (comandos e saída do job a partir de `result`/eventos; Copiar, Rodar de novo, Explicar simples), `Intercorrencias` (os 11 casos em 3 níveis; faltam 5), `Link-Aberto` (sheet com aviso de link desconhecido; "Abrir aqui", Copiar, Compartilhar), `Vazios` (ilustração 148 dp, uma frase, um botão verbo + objeto).
Aceite: um teste por caso/estado; qualquer `https` passa pela sheet; suíte verde.
Depende de: T5. Arquivos: `ui/screens/backstage/*`, `activity/IncidentBanner.kt`, `ui/screens/link/*`, `ui/components/EmptyState.kt`. Tamanho: M. Worktree: `wt-t14`.

## Task 15: Chat 1–5 e Artefatos completos
Descrição: plano em 4 passos com "Prova:" e "Trocar" (Chat-1), comandos que navegam (Chat-2), %/Pausar/Ver mudanças (Chat-3), aprovação em 3 níveis com lista de alvos (Chat-4), resultado FEITO/COMPROVADO/FALTA/PRÓXIMO + cartão do arquivo + Abrir pull request + Instalar na multimídia (Chat-5), Artefato-Detalhe (SHA-256, versões, "Instalar na multimídia"). Só com dados que o motor entrega; o resto mostra "o motor ainda não informa". Checkpoint por passo na execução (FN-12): ao reabrir, o Chat-3 volta do ponto salvo. Teclado (FN-20): composer ancorado, barra de baixo some, texto preservado (insets).
Aceite: testes por tela e por nível de risco; suíte verde.
Depende de: T4, T5, T14. Arquivos: `ui/screens/chat/*`, `ui/screens/projects/Artifact*`. Tamanho: L (dividir se passar de 8 arquivos). Worktree: `wt-t15`.

## Task 16: Voz 1–5
Descrição: `Voz-1` (Ouvindo, transcrição ao vivo, Enviar/Teclado/Cancelar), `Voz-2` (Interrompida, volta em 3 s), `Voz-3` (Compacta, fila "POR VOZ"), `Voz-4` (Triagem; recomendação "Fazer depois do atual"), `Voz-5` (Modos: apertar e falar, conversa, mãos livres + 4 interruptores). Reconhecedor do Android; microfone só pedido aqui (T7); "Falar a ideia" em Projetos deixa de avisar "não disponível" onde há reconhecedor. Orbe com 4 estados.
Aceite: testes de cada estado com reconhecedor falso; sem microfone ou sem reconhecedor → mensagem e UMA ação; suíte verde.
Depende de: T5, T7. Arquivos: `ui/screens/voice/*`, `ui/screens/home/AuraOrb.kt`. Tamanho: L (dividir se passar de 8 arquivos). Worktree: `wt-t16`.

## Task 17: Matriz JVM e caça a bugs final
Descrição: `OfflineMatrixTest` (toda rota nos estados NoTermux, NoPermission, NoToken, EngineStopped, Starting, Online × vazio/com dado, sem exceção), `ClickEverythingTest` (percorre cada botão de cada rota; nenhum sem efeito), `NavGraphTest` com `pending` vazio, conferência de fidelidade tela a tela contra `.superpowers/design-png` (registrar em `docs/design/CONFERENCIA.md`), Android Lint, rodada contra `matriz-fluxos-falhas.md` e `auditoria-fluxo.md` (cada item: corrigido com teste, ou justificado).
Aceite: P1–P6 e P11 (parte JVM) verdes; `docs/MATRIZ_DE_TESTES.md` A, D, E marcadas com evidência; na COBERTURA, toda linha que a JVM prova está `feito` e as que dependem do emulador ficam `em andamento` com o motivo.
Depende de: T6…T16. Tamanho: M. Worktree: `wt-t17`.

## Task 18: Teste real nos dois emuladores
Descrição: `emulator-5554` (S23): instalar o v5 (`.superpowers/apk/aura-v5-baseline.apk`), gravar marcador/login, instalar o v6 por cima (`adb install -r`), conferir que o marcador permanece; passeio por todas as abas e botões SEM Termux; instalar o Termux (`com.termux_1021.apk` da pasta DESKTOP LIVE ou do Drive), preparar o motor, parear e repetir COM Termux; `emulator-5556` (Taytech): alvo do ADB — de dentro do S23, `adb connect 10.0.2.2:5557`, capturar tela e instalar um app. Evidência: prints, `logcat` sem `FATAL EXCEPTION`, `am start -W`, tamanho do APK, `dumpsys meminfo`. Com o Termux: rodar a cadeia REA do T20 (instalar Node, JDK, JADX, Apktool, adb e rea-agents/MCP, `tools/list` real, reformar um app de teste e medir Reversão, Reconstrução e Fidelidade). Ao final: desinstalar AURA e Termux dos dois, restaurar ajustes globais.
Aceite: P7–P10, P12 e P15 com evidência em `docs/REGISTRO.md`; matrizes B, C, F marcadas; todas as linhas restantes da COBERTURA `feito`.
Depende de: T17 e T20. Tamanho: L. Quem: controlador + 1 implementador.

## Task 19: Entrega (build final e PR)
Descrição: build de release assinado com a MESMA chave do v5, `aapt2`/Lint finais, copiar para `C:\Users\hugov\Desktop\DESKTOP LIVE\auraai-v6.apk` (nunca por chat), `git fetch` + rebase, `push`, PR para `main` com a lista das provas, conferir a rodada do motor no Linux na CI (P13), acrescentar ao `android-ci.yml` as etapas `:app:lintDebug`, `:app:assembleRelease` (tamanho do APK) e `aapt2 dump permissions` com a saída guardada como artefato, nota no Obsidian (`Projetos/AURA AI.md`) e `python -I _sistema/salvar.py`.
Aceite: matriz A–F toda marcada; `python -I scripts/check_design_coverage.py --release` com saída 0 (66/66); CI do PR verde (`android-ci`, `Engine contract CI`, `design-coverage`); PR aberto.
Depende de: T18. Tamanho: S.

## Task 20: Motor — recepção, triagem e cadeia REA no Termux
Descrição (Python, `engine/`): fecha FN-03, FN-04 (lado do motor) e FN-16 (código) da COBERTURA. Antes de despachar, investigação só de leitura (`docs/design/voz-e-fila.md`, `plano-autonomia-termux.md`, `reformar-apps.md`, `engine/aura_engine/*`, skills em `engine/skills/*`) que vira o brief; o que faltar de dado é registrado, nunca inventado.
- (a) Recepção: skill embutida `engine/skills/clareza/SKILL.md` (frontmatter `name`/`description`, regras e exemplos de `voz-e-fila.md`) e um passo no motor que interpreta a fala antes da triagem (transcrição → pedido reescrito; nunca executa nada; se falhar, segue com o texto original).
- (b) Triagem: classificar cada entrada em pergunta, ajuste, novo pedido ou parar; fila com "salvo para retomar"; eventos `input.received` e `queue.updated` em `/v1/events` (o T16 consome); ajuste e parar atuam no job em curso.
- (c) Cadeia REA no Termux: job que instala Node, JDK, JADX, Apktool, adb e rea-agents/MCP, com checkpoint por etapa e retomada (matar no meio e rodar de novo continua), `tools/list` real do MCP, ferramenta com `mutatesDevice` = risco médio/alto, e as medidas Reversão, Reconstrução e Fidelidade (Fidelidade só depois de teste real). Os comandos passam por um executor injetável: os testes usam um falso; a execução de verdade é do T18 (P15).
- Se (c) não couber hoje: FN-16 fica `pendente` com o bloqueio escrito na coluna Evidência e a entrega não sai (portão da COBERTURA).
Aceite:
- [ ] Testes Python por evento e por caso de triagem; (c) com executor falso, retomando após falha em cada etapa; RED antes.
- [ ] `docs/ENGINE_CONTRACT.md` descreve os eventos e os jobs novos; o conjunto de falhas do `python -m unittest` completo no Windows é o mesmo de antes e a CI Linux fica verde.
- [ ] FN-03, FN-04 (motor) e FN-16 (código) marcados na COBERTURA com a evidência; o restante de FN-16 fecha no T18.
Depende de: T3. Arquivos: `engine/aura_engine/*`, `engine/skills/clareza/*`, `engine/tests/*`, `docs/ENGINE_CONTRACT.md`. Tamanho: L (dividir (a)+(b) e (c) se passar de 8 arquivos). Worktree: `wt-t20`.
