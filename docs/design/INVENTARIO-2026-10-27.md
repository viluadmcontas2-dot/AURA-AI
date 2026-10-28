# Inventário design × código (27/10/2026)

Base: `fe7c7b4`, só leitura (comparou texto e estrutura; nada renderizado). Referência: `docs/design/telas/*.dc.html`.
"Sim" = texto e estrutura batem. **Sim não é verde**: a matriz D só fecha com a conferência por tela (print do emulador × print do design).

Contagem: 45 arquivos `.dc.html` = 5 de documentação (Sistema-Componentes, Sistema-Cores-Tipo, Contrato, Progresso, Fluxo-do-usuario) + 40 telas
(38 de fluxo + 2 catálogos: Intercorrências e Vazios). Placar inicial: 8 sim, 14 parcial, 18 não.
(A matriz dizia "45 telas" e "Reformar app (4 telas)": o certo é 40 telas e Reformar com 6.)

| Tela (design) | Hoje | O que falta | Tarefa |
|---|---|---|---|
| Onb-1 Boas-vindas | sim | pede 6 permissões em cima da tela: mover para "sob demanda" | T7 |
| Onb-2 Termux | sim (texto) | "Continuar" mudo sem Termux; pareamento nunca acontece; sem estados claros | T6 |
| Onb-3 Contas | sim | nada de texto ("Pular por agora" é extra) | — |
| Main | parcial | nome/avatar/Anexar; chip abre Conexões; atalhos; offline com "Iniciar o motor" | T5, T13 |
| Chat-1 Entendi | parcial | plano em 4 passos com "Prova:", "Trocar" | T15 |
| Chat-2 Comandos | sim | comandos viram texto para /v1/route; devem navegar | T15 |
| Chat-3 Executando | parcial | %, Pausar, Ver mudanças | T15 |
| Chat-4 Aprovação | parcial | lista de alvos, "Libera 184 MB", 3 níveis de risco | T15 |
| Chat-5 Resultado | parcial | Ver provas, cartão do arquivo, Abrir pull request, Instalar na TV | T15 |
| Bastidores | **não** | tela inteira | T14 |
| Projetos | sim | só em memória (Bundle): gravar em disco; criar offline | T13 |
| Projeto-Ideia | sim | "Falar a ideia" avisa que não há reconhecedor | T16 |
| Projeto-Novo | sim | — | — |
| Projeto-Detalhe | sim | abas Arquivos e Decisões vazias | T13 |
| Artefatos | parcial | lista sempre vazia (ler `artifacts/`) | T13 |
| Artefato-Detalhe | parcial | "Instalar na multimídia", SHA-256, versões | T15 |
| Atividade | parcial | "Tentar de novo" sem fio; badge na barra | T13 |
| Dispositivos | parcial | um item "adb" agregado; offline sem conteúdo | T13 |
| Dispositivo-Detalhe | parcial | última captura, Reformar apps, atividade do aparelho | T13 |
| Conexões | parcial | "Uso da semana"; entrada por Ajustes e pelo chip | T12 |
| Ajustes | parcial | menu do design (Perfil, Capacidades, Memória, Checkup, Aparência, Notificações, Microfone); "Parar tudo" está MORTO; nada abre offline | T12, T13 |
| Capacidades | **não** | tela inteira | T11 |
| Capacidade-Detalhe | **não** | tela inteira (modo por permissão) | T11 |
| Memória | **não** | tela inteira; motor não sabe esquecer | T3, T11 |
| Checkup | **não** | tela inteira (autodiagnóstico) | T8 |
| Notificações | **não** | canais e serviço de primeiro plano | T12 |
| Link-Aberto | **não** | sheet com aviso de link desconhecido | T14 |
| Intercorrências | parcial | faltam 5 casos (limite do modelo, ADB, arquivo alterado, link, ambiguidade) | T14 |
| Vazios | parcial | ilustração 148 dp; vazio de Dispositivos diverge | T14 |
| Reformar-1 Apps | **não** | tela inteira | T9 |
| Reformar-2 Escopo | **não** | tela inteira | T9 |
| Reformar-3 Reversão | **não** | tela inteira | T10 |
| Reformar-4 Fidelidade | **não** | tela inteira | T10 |
| Reformar-5 Ferramentas | **não** | tela inteira | T9 |
| Reformar-6 Projeto | **não** | tela inteira | T10 |
| Voz-1 Ouvindo | **não** | só o reconhecedor do Android, sem tela AURA | T16 |
| Voz-2 Interrompida | **não** | tela inteira | T16 |
| Voz-3 Compacta | **não** | tela inteira | T16 |
| Voz-4 Triagem | **não** | tela inteira (motor não emite os eventos) | T16 |
| Voz-5 Modos | **não** | tela inteira | T16 |

## Componentes do design que não existem em `ui/components`
AuraAvatar, AuraSwitch, AuraOrb com estados (Parado/Ouvindo/Pensando/Erro), AuraStep com "Prova:", AuraBanner (hoje em `activity/IncidentBanner.kt`),
AuraBottomBar com badge (hoje em `AuraApp.kt`), AuraListItem, AuraApproval (3 níveis), AuraCard por tipo, EmptyState com ilustração 148 dp,
medidor de %, mapa de telas, comparador Original × Reformado, cartão do arquivo gerado, sheet "Abrir link", notificações do sistema.

## O que o motor já entrega (e o que não)
- Existe: `/v1/health`, `/v1/capabilities`, `/v1/jobs`, `/v1/jobs/{id}`, `/v1/events`, `/v1/policy` (GET/PUT), `/v1/usage`, `/v1/skills`, `/v1/memory` (GET/POST), `/v1/route`, job `system/doctor`.
- NÃO existe: apagar memória (`DELETE`), tipo da memória, comandos por job, log ao vivo, `step.progress`/`plan.proposed`/`reform.*`, `input.received`/`queue.updated`, aparelhos individuais (bateria, última captura), pausar (só cancelar).
- Regra: tela que depende de dado que o motor não tem mostra o que existe e diz o que falta; nunca inventa dado.

## Descobertas da auditoria "motor desligado" (v5)
1. `TermuxBridge.pair()` e `startEngine()` não têm chamador: o app nunca pareia. Sem token, TODA chamada falha. Offline é o estado padrão de um APK limpo.
2. Persistência em disco: só `aura_prefs` (onboarding_done, draft, all_files_asked) e o token cifrado. Projetos só no Bundle; histórico do chat só em memória.
3. "Parar tudo" (Ajustes) é botão morto: o aviso só é desenhado numa barra que existe apenas em Ready. Nenhuma seção de Ajustes abre offline.
4. `AuraApp.kt:100` tem `FakeEngineGateway()` como padrão; `FakeEngineGateway` e `ExampleData` estão em `src/main` (vão no APK).
5. Restauração de Bundle sem checagem pode derrubar o app ao reabrir após o sistema matar o processo (ProjectModels.kt:34-38, ChatModels.kt:30, `valueOf` em 3 telas).
6. `asJob` lança `IllegalStateException` para status/risco desconhecido e derruba a lista inteira; evento malformado trava o polling (cursor só avança depois do emit).
7. `usesCleartextTraffic="true"` global; só 127.0.0.1 precisa.
8. Voz em Projetos sempre diz "não disponível", mesmo com reconhecedor.

## Evidência do v5 no emulador S23 (Android 17), 27/10/2026
- Primeira abertura: 6 pedidos de permissão seguidos (câmera, microfone, dispositivos próximos, notificações, música/áudio, fotos/vídeos) e a tela do sistema "Acesso a todos os arquivos".
- Abertura a frio: 10 s no emulador (`am start -W`, TotalTime 10171 ms).
- Ícone padrão do Android. Onboarding Termux: "Continuar" sem efeito quando o Termux não está instalado.
- Início sem motor: faixa "Termux sem resposta" + cartão "O AURA não alcança o Termux" só com "Tentar de novo".
