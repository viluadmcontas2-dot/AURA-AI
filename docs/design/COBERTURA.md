# Matriz de cobertura: design → app

Toda tela e função do design precisa virar código com teste e evidência. O APK final só sai quando `python3 scripts/check_design_coverage.py --release` passar (tudo `feito` com evidência).

Status: `pendente`, `em andamento`, `feito`. Evidência = teste, captura de tela ou link de CI/dispositivo. `-` conta como sem evidência.

## Telas

| ID | Tela (arquivo) | Status | Evidência | Tarefa |
|---|---|---|---|---|
| UI-01 | 00a · Progresso de UI e UX — `Progresso.dc.html` | pendente | - | T17 |
| UI-02 | 00 · Fluxo do usuário — `Fluxo-do-usuario.dc.html` | em andamento | NavGraphTest (CI 40608305338); fecha no T17 | T5, T17 |
| UI-03 | 00b · Plano e contrato — `Contrato.dc.html` | pendente | - | T17 |
| UI-04 | 01 · Boas-vindas — `Onb-1-Boas-vindas.dc.html` | pendente | - | T7 |
| UI-05 | 02 · Conectar o Termux — `Onb-2-Termux.dc.html` | pendente | - | T6 |
| UI-06 | 03 · Conectar contas — `Onb-3-Contas.dc.html` | pendente | - | T18 |
| UI-07 | 04 · Início — `Main.dc.html` | em andamento | EngineBannerTest e NavGraphTest (CI 40608305338); T13 faz o resto | T5, T13 |
| UI-08 | 05 · Voz · ouvindo — `Voz-1-Ouvindo.dc.html` | pendente | - | T16 |
| UI-09 | 06 · Voz · interrompida — `Voz-2-Interrompida.dc.html` | pendente | - | T16 |
| UI-10 | 07 · Entendi + plano — `Chat-1-Entendi.dc.html` | pendente | - | T15 |
| UI-11 | 08 · Teclado + comandos — `Chat-2-Teclado-Comandos.dc.html` | pendente | - | T15 |
| UI-12 | 09 · Contar a ideia — `Projeto-Ideia.dc.html` | pendente | - | T16 |
| UI-13 | 10 · Ideia vira projeto — `Projeto-Novo.dc.html` | pendente | - | T13 |
| UI-14 | 11 · Voz compacta — `Voz-3-Compacta.dc.html` | pendente | - | T16 |
| UI-15 | 12 · Pedido novo no meio — `Voz-4-Triagem.dc.html` | pendente | - | T16 |
| UI-16 | 13 · Modos do microfone — `Voz-5-Modos.dc.html` | pendente | - | T16 |
| UI-17 | 10 · Executando — `Chat-3-Executando.dc.html` | pendente | - | T15 |
| UI-18 | 11 · Aprovação — `Chat-4-Aprovacao.dc.html` | pendente | - | T15 |
| UI-19 | 12 · Bastidores — `Bastidores.dc.html` | pendente | - | T14 |
| UI-20 | 13 · Resultado — `Chat-5-Resultado.dc.html` | pendente | - | T15 |
| UI-21 | 14 · Artefato — `Artefato-Detalhe.dc.html` | pendente | - | T15 |
| UI-22 | 15 · Abrir link — `Link-Aberto.dc.html` | pendente | - | T14 |
| UI-23 | 16 · Projetos — `Projetos.dc.html` | pendente | - | T13 |
| UI-24 | 17 · Projeto — `Projeto-Detalhe.dc.html` | pendente | - | T13 |
| UI-25 | 18 · Artefatos — `Artefatos.dc.html` | pendente | - | T13 |
| UI-26 | 19 · Dispositivos — `Dispositivos.dc.html` | pendente | - | T13 |
| UI-27 | 20 · Dispositivo — `Dispositivo-Detalhe.dc.html` | pendente | - | T13 |
| UI-28 | 21 · Atividade — `Atividade.dc.html` | pendente | - | T13 |
| UI-29 | 22 · Capacidades — `Capacidades.dc.html` | pendente | - | T11 |
| UI-30 | 23 · Capacidade — `Capacidade-Detalhe.dc.html` | pendente | - | T11 |
| UI-31 | 24 · Conexões — `Conexoes.dc.html` | pendente | - | T12 |
| UI-32 | 25 · Memória — `Memoria.dc.html` | pendente | - | T11 |
| UI-33 | 26 · Ajustes — `Ajustes.dc.html` | pendente | - | T12, T13 |
| UI-34 | 27 · Checkup — `Checkup.dc.html` | pendente | - | T8 |
| UI-35 | 28 · Notificações do Android — `Notificacoes.dc.html` | pendente | - | T12 |
| UI-36 | 29 · Quando algo dá errado — `Intercorrencias.dc.html` | pendente | - | T14 |
| UI-37 | 30 · Telas vazias — `Vazios.dc.html` | pendente | - | T14 |
| UI-38 | 31 · Cores e tipografia — `Sistema-Cores-Tipo.dc.html` | em andamento | NavGraphTest trata como documentação e TokensTest cobre os tokens; fecha no T17 | T5 |
| UI-39 | 32 · Componentes — `Sistema-Componentes.dc.html` | em andamento | NavGraphTest trata como documentação e TokensTest cobre os tokens; fecha no T17 | T5, T17 |
| UI-40 | 90 · Escolher o app — `Reformar-1-Apps.dc.html` | pendente | - | T9 |
| UI-41 | 91 · Travar o escopo — `Reformar-2-Escopo.dc.html` | pendente | - | T9 |
| UI-42 | 92 · Reversão e reconstrução — `Reformar-3-Reversao.dc.html` | pendente | - | T10 |
| UI-43 | 93 · Original e reformado — `Reformar-4-Fidelidade.dc.html` | pendente | - | T10 |
| UI-44 | 94 · Ferramentas automáticas — `Reformar-5-Ferramentas.dc.html` | pendente | - | T9 |
| UI-45 | 95 · Projeto da reforma — `Reformar-6-Projeto.dc.html` | pendente | - | T10 |

## Funções

| ID | Função | Status | Evidência | Tarefa |
|---|---|---|---|---|
| FN-01 | Voz em tela cheia só por toque; voz compacta; ouvir não interrompe | pendente | - | T16 |
| FN-02 | Modos do microfone (apertar, conversa, mãos livres) e retomada sozinha após interrupção | pendente | - | T16 |
| FN-03 | Recepção: skill de clareza interpreta a fala antes da triagem | pendente | - | T20 |
| FN-04 | Triagem de entradas (pergunta, ajuste, novo pedido, parar) e fila com salvo para retomar | pendente | - | T16, T20 |
| FN-05 | Aprovação por risco (linha, cartão, sheet) e pedido de aprovação adiantado | pendente | - | T15 |
| FN-06 | Resultado em 4 blocos: Feito, Comprovado, Falta, Próximo passo | pendente | - | T15 |
| FN-07 | Bastidores: comandos, logs, mudanças | pendente | - | T14 |
| FN-08 | Trava de custo: nunca usar API paga sem aprovação | pendente | - | T12 |
| FN-09 | Cérebros: Claude Code, Codex, Gemini/Google, Antigravity (agy) com indisponível honesto | pendente | - | T12 |
| FN-10 | Onboarding do Termux (allow-external-apps) com teste automático | pendente | - | T6 |
| FN-11 | Checkup do ambiente e retomada após Android encerrar processo | pendente | - | T8, T13 |
| FN-12 | Persistência: checkpoint por passo, retomada de sessão sem perder estado | pendente | - | T1, T13, T15 |
| FN-13 | Notificações (rodando, precisa de você, pronto, ouvindo) | pendente | - | T12 |
| FN-14 | Artefatos: instalar, compartilhar, provas, versões; abrir link com aviso de domínio | pendente | - | T14, T15 |
| FN-15 | Reformar apps: escopo travado, aceite de permissão, apps protegidos, instala ao lado do original | pendente | - | T9, T10 |
| FN-16 | REA: instalar Node, JDK, JADX, Apktool, adb, rea-agents e MCP automaticamente no Termux; medir Reversão, Reconstrução e Fidelidade (fidelidade só após teste real) | pendente | - | T10, T20 |
| FN-17 | Memória editável (esquecer) e privacidade dos arquivos do celular e do Drive | pendente | - | T3, T11 |
| FN-18 | Modo carro: alvos grandes e texto maior; Leitura fácil | pendente | - | T12 |
| FN-19 | Intercorrências: 11 casos com botão de continuar | pendente | - | T14 |
| FN-20 | Teclado: composer ancorado, barra some, texto preservado (insets) | pendente | - | T15 |
| FN-21 | Segredos nunca em Git, logs ou prompts | pendente | - | T2, T17 |
