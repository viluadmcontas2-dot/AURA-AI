# Mapa de telas (navegação real)

Fonte da verdade: `android/app/src/main/java/ai/aura/app/ui/nav/Routes.kt` (lista `ROUTES`) e os callbacks de cada tela.
`NavGraphTest` trava duas coisas: toda tela que existe é alcançável passando só por telas que existem, e a Reforma abre em um toque.

Legenda: **[aba]** barra inferior · **[tela]** rota com tela · *(interno)* tela que vive dentro de outra, sem rota própria.

## Árvore

```
Início [aba]
├─ Chat *(interno)*: composer, atalhos "Criar aplicativo" e "Controlar Android", retomar tarefa,
│   "Melhorar pedido" (texto > 160 caracteres), botão "Sessões" no cabeçalho
├─ Sessões (ícone de conversas no cabeçalho, botão "Sessões" do Chat, Ajustes > "Ver sessões")   [tela]
│   ├─ Conversas do Claude Code (AURA e terminal): tocar abre o Chat nela (volta ao Início com a sessão)
│   │   └─ Renomear · Esconder (confirma; no terminal só esconde no AURA) · Parar resposta (só se estiver respondendo)
│   └─ "No celular agora": programas que o motor iniciou, com Parar (o motor não tem botão)
├─ Voz → Ouvindo → Compacta → Triagem                       [tela x3]
├─ Ajustes (engrenagem)                                      [tela]
│   ├─ Contas *(interno)*: conectar Claude, Gemini, Codex, GitHub
│   ├─ Ver sessões → Sessões                                 [tela]
│   ├─ Abrir check-up → Check-up                             [tela]
│   ├─ Ajustar avisos → Notificações                         [tela]
│   ├─ Escolher modo de voz → Modos de voz                   [tela]
│   └─ Ver bastidores → Bastidores                           [tela]
└─ Reformar apps (cartão largo)  ──────────────┐
Projetos [aba]                                  │
├─ Cartão "Reformar apps" ──────────────────────┤
├─ Criar projeto → Ideia → Resumo → Detalhe *(interno)*
│   └─ "Reformar um app que já existe" ─────────┤   (na Ideia e no vazio da lista)
└─ Artefatos → Artefato *(interno)*               │
Dispositivos [aba]: lista, detalhe e conexões *(interno)*   (sem atalho para Reformar: ver Pendências)
Atividade [aba]: lista, aprovar, reabrir tarefa no chat *(interno)*
                                                  │
Reformar apps (assistente em tela cheia, sem barra de abas) ◄─┘
 1 Escolher APK   [tela]  Escolher APK (seletor) · Compartilhar para o AURA (abre direto no Escopo)
 │                        sem motor: aviso + "Iniciar o motor"; "Procurar apps" → Ferramentas
 2 Escopo         [tela]  "Travar escopo e começar": copia o APK, trava no motor, já pede as ferramentas
 3 Reversão       [tela]  medidores do motor · Pausar/Retomar · Ver bastidores
 4 Projeto        [tela]  Continuar reforma · Ferramentas · Ver fidelidade · Mudar estilo
 5 Fidelidade     [tela]  Testar no aparelho (relê o motor) · Instalar ao lado do original (pedido no chat)
 + Ferramentas    [tela]  Instalar o que falta (passo `tools` da reforma; sem reforma, pedido no chat)
```

## Alcançável hoje (tem tela e tem caminho)
Início, Voz (4 telas + Modos), Projetos, Dispositivos, Atividade, Ajustes, Check-up, Notificações, Bastidores,
Sessões (rota `sessoes`, sem arquivo de design), Conexões (com o cartão "Melhorar pedidos (Gemini)"),
Reformar (6 telas), Onboarding (só na primeira abertura).

## Órfãs: a tela existe, nada abria, e agora abre
- Reformar apps (6 telas): só eram citadas por `dispositivo-detalhe`, que não tem tela. Agora: Início, Projetos, Criar projeto e "Compartilhar para o AURA".
- Check-up, Notificações, Modos de voz: estavam em `edges` de Ajustes, mas Ajustes não tinha botão. Agora há o grupo "Mais do AURA".
- Bastidores: ligado em Ajustes e na Reversão (o botão "Ver bastidores" só aparece se a tela existe).

## Ainda órfãs (tela ou peça existe, falta ligar; fora do que esta frente podia tocar ou sem adaptador do motor)
- **Conexões** (`conexoes`): rota sem tela. TODO: quando a frente de Termux + contas + permissões (devices/, onboarding/) entregar a tela, trocar `null` por ela em `Routes.kt` e abrir de Início, Dispositivos e Ajustes (as arestas já existem).
- **Permissões** (`permissoes`): `PermissionsRoute(onBack)` existe, é da mesma frente. Mesmo TODO.
- **Capacidades** e **Detalhe da capacidade**, **Memória**: telas prontas (`CapabilitiesScreen`, `MemoryScreen`), mas falta o adaptador HTTP do motor (`CapabilitiesSource`, `MemoryActions`). Quando existir, criar `*Route(env)` e abrir de Ajustes.
- **Link aberto** (`LinkSheetHost`): folha, não rota; falta ligar aos links do chat.
- **Intercorrências**, **Vazios**: componentes usados dentro das telas; não são rotas de navegação.
- **Dispositivos → Reformar apps**: `devices/` é da outra frente. TODO: um atalho "Reformar apps" no detalhe do dispositivo chamando `env.open("reformar-apps")`.

## Palavras fixas
| Conceito | Palavra |
|---|---|
| Refazer um app existente | **Reformar apps** (atalho) · "Reformar um app que já existe" (opção ao criar projeto) |
| Entender o APK | **Reversão** |
| Interface nova | **Reconstrução** (medidor) · **Projeto** (tela) |
| Quanto faz igual | **Fidelidade** ("Ainda não medida" sem teste real) |
| Programas de apoio | **Ferramentas** |
| Termux + contas + permissões | **Conexões** (tela da outra frente) |
| Motor sem resposta | **Iniciar o motor** (ação única) |
| Conversas do Claude Code no Termux | **Sessões** (tela) · "No AURA" / "No terminal" · "Respondendo agora" |
| Programas rodando no celular | **No celular agora** (seção de Sessões) |
| Deixar o pedido mais claro | **Melhorar pedido** → "Melhorando…" → **Desfazer** (nunca envia sozinho) |
| Chave para Melhorar pedido | **Chave do Gemini** (Conexões: Salvar chave · Chave salva · Remover chave) |
| Dado que o motor não mandou | **O motor ainda não informa** (nunca número inventado) |

## Limites conhecidos do fluxo de Reforma
- "Instalar ao lado do original" e "Instalar o que falta" sem reforma ainda não têm endpoint próprio: viram pedido no chat do Início.
  Ponto único para trocar: `ReformToolsRoute.installWithoutReform` e `ReformFidelityRoute` em `ui/screens/reform/ReformRoutes.kt`.
- "Testar no aparelho" só relê o motor: a fidelidade nasce de `POST /v1/reform/{id}/verify`, e o app ainda não roda o teste real.
- A ordem pedida era APK → Ferramentas → Escopo, mas o passo `tools` mora dentro da reforma, que só nasce ao travar o escopo. Por isso as ferramentas são pedidas logo depois de travar, e a tela Ferramentas fica à mão (Procurar apps, Projeto).
