# Matriz de cobertura: design → app

Toda tela e função do design precisa virar código com teste e evidência. O APK final só sai quando `python3 scripts/check_design_coverage.py --release` passar (tudo `feito` com evidência).

Status: `pendente`, `em andamento`, `feito`. Evidência = teste, captura de tela ou link de CI/dispositivo. `-` conta como sem evidência.

## Telas

| ID | Tela (arquivo) | Status | Evidência |
|---|---|---|---|
| UI-01 | 00a · Progresso de UI e UX — `Progresso.dc.html` | pendente | - |
| UI-02 | 00 · Fluxo do usuário — `Fluxo-do-usuario.dc.html` | pendente | - |
| UI-03 | 00b · Plano e contrato — `Contrato.dc.html` | pendente | - |
| UI-04 | 01 · Boas-vindas — `Onb-1-Boas-vindas.dc.html` | pendente | - |
| UI-05 | 02 · Conectar o Termux — `Onb-2-Termux.dc.html` | pendente | - |
| UI-06 | 03 · Conectar contas — `Onb-3-Contas.dc.html` | pendente | - |
| UI-07 | 04 · Início — `Main.dc.html` | pendente | - |
| UI-08 | 05 · Voz · ouvindo — `Voz-1-Ouvindo.dc.html` | pendente | - |
| UI-09 | 06 · Voz · interrompida — `Voz-2-Interrompida.dc.html` | pendente | - |
| UI-10 | 07 · Entendi + plano — `Chat-1-Entendi.dc.html` | pendente | - |
| UI-11 | 08 · Teclado + comandos — `Chat-2-Teclado-Comandos.dc.html` | pendente | - |
| UI-12 | 09 · Contar a ideia — `Projeto-Ideia.dc.html` | pendente | - |
| UI-13 | 10 · Ideia vira projeto — `Projeto-Novo.dc.html` | pendente | - |
| UI-14 | 11 · Voz compacta — `Voz-3-Compacta.dc.html` | pendente | - |
| UI-15 | 12 · Pedido novo no meio — `Voz-4-Triagem.dc.html` | pendente | - |
| UI-16 | 13 · Modos do microfone — `Voz-5-Modos.dc.html` | pendente | - |
| UI-17 | 10 · Executando — `Chat-3-Executando.dc.html` | pendente | - |
| UI-18 | 11 · Aprovação — `Chat-4-Aprovacao.dc.html` | pendente | - |
| UI-19 | 12 · Bastidores — `Bastidores.dc.html` | pendente | - |
| UI-20 | 13 · Resultado — `Chat-5-Resultado.dc.html` | pendente | - |
| UI-21 | 14 · Artefato — `Artefato-Detalhe.dc.html` | pendente | - |
| UI-22 | 15 · Abrir link — `Link-Aberto.dc.html` | pendente | - |
| UI-23 | 16 · Projetos — `Projetos.dc.html` | pendente | - |
| UI-24 | 17 · Projeto — `Projeto-Detalhe.dc.html` | pendente | - |
| UI-25 | 18 · Artefatos — `Artefatos.dc.html` | pendente | - |
| UI-26 | 19 · Dispositivos — `Dispositivos.dc.html` | pendente | - |
| UI-27 | 20 · Dispositivo — `Dispositivo-Detalhe.dc.html` | pendente | - |
| UI-28 | 21 · Atividade — `Atividade.dc.html` | pendente | - |
| UI-29 | 22 · Capacidades — `Capacidades.dc.html` | pendente | - |
| UI-30 | 23 · Capacidade — `Capacidade-Detalhe.dc.html` | pendente | - |
| UI-31 | 24 · Conexões — `Conexoes.dc.html` | pendente | - |
| UI-32 | 25 · Memória — `Memoria.dc.html` | pendente | - |
| UI-33 | 26 · Ajustes — `Ajustes.dc.html` | pendente | - |
| UI-34 | 27 · Checkup — `Checkup.dc.html` | pendente | - |
| UI-35 | 28 · Notificações do Android — `Notificacoes.dc.html` | pendente | - |
| UI-36 | 29 · Quando algo dá errado — `Intercorrencias.dc.html` | pendente | - |
| UI-37 | 30 · Telas vazias — `Vazios.dc.html` | pendente | - |
| UI-38 | 31 · Cores e tipografia — `Sistema-Cores-Tipo.dc.html` | pendente | - |
| UI-39 | 32 · Componentes — `Sistema-Componentes.dc.html` | pendente | - |
| UI-40 | 90 · Escolher o app — `Reformar-1-Apps.dc.html` | pendente | - |
| UI-41 | 91 · Travar o escopo — `Reformar-2-Escopo.dc.html` | pendente | - |
| UI-42 | 92 · Reversão e reconstrução — `Reformar-3-Reversao.dc.html` | pendente | - |
| UI-43 | 93 · Original e reformado — `Reformar-4-Fidelidade.dc.html` | pendente | - |
| UI-44 | 94 · Ferramentas automáticas — `Reformar-5-Ferramentas.dc.html` | pendente | - |
| UI-45 | 95 · Projeto da reforma — `Reformar-6-Projeto.dc.html` | pendente | - |

## Funções

| ID | Função | Status | Evidência |
|---|---|---|---|
| FN-01 | Voz em tela cheia só por toque; voz compacta; ouvir não interrompe | pendente | - |
| FN-02 | Modos do microfone (apertar, conversa, mãos livres) e retomada sozinha após interrupção | pendente | - |
| FN-03 | Recepção: skill de clareza interpreta a fala antes da triagem | pendente | - |
| FN-04 | Triagem de entradas (pergunta, ajuste, novo pedido, parar) e fila com salvo para retomar | pendente | - |
| FN-05 | Aprovação por risco (linha, cartão, sheet) e pedido de aprovação adiantado | pendente | - |
| FN-06 | Resultado em 4 blocos: Feito, Comprovado, Falta, Próximo passo | pendente | - |
| FN-07 | Bastidores: comandos, logs, mudanças | pendente | - |
| FN-08 | Trava de custo: nunca usar API paga sem aprovação | pendente | - |
| FN-09 | Cérebros: Claude Code, Codex, Gemini/Google, Antigravity (agy) com indisponível honesto | pendente | - |
| FN-10 | Onboarding do Termux (allow-external-apps) com teste automático | pendente | - |
| FN-11 | Checkup do ambiente e retomada após Android encerrar processo | pendente | - |
| FN-12 | Persistência: checkpoint por passo, retomada de sessão sem perder estado | pendente | - |
| FN-13 | Notificações (rodando, precisa de você, pronto, ouvindo) | pendente | - |
| FN-14 | Artefatos: instalar, compartilhar, provas, versões; abrir link com aviso de domínio | pendente | - |
| FN-15 | Reformar apps: escopo travado, aceite de permissão, apps protegidos, instala ao lado do original | pendente | - |
| FN-16 | REA: instalar Node, JDK, JADX, Apktool, adb, rea-agents e MCP automaticamente no Termux; medir Reversão, Reconstrução e Fidelidade (fidelidade só após teste real) | pendente | - |
| FN-17 | Memória editável (esquecer) e privacidade dos arquivos do celular e do Drive | pendente | - |
| FN-18 | Modo carro: alvos grandes e texto maior; Leitura fácil | pendente | - |
| FN-19 | Intercorrências: 11 casos com botão de continuar | pendente | - |
| FN-20 | Teclado: composer ancorado, barra some, texto preservado (insets) | pendente | - |
| FN-21 | Segredos nunca em Git, logs ou prompts | pendente | - |
