Entendi: terminar o app AURA AI hoje. Repo: viluadmcontas2-dot/AURA-AI, branch claude/telas-sem-termux (parte da main já com o PR #1). Leia docs/design/README.md, docs/design/telas/*.dc.html (design completo, 45 telas), docs/ENGINE_CONTRACT.md e docs/design/plano-autonomia-termux.md antes de mexer.

REGRA Nº 1 (inegociável): o app NUNCA fica morto. Sem Termux, sem motor ou sem internet, TODAS as abas, telas e botões abrem. O que depende do motor falha com mensagem clara e uma ação ("Iniciar o motor", "Abrir o Termux", "Tentar de novo"). Projetos, Atividade, Dispositivos, Ajustes, histórico e rascunhos são locais: guarde em disco (Room ou arquivo) e mostre sempre, mesmo offline. Nunca tela em branco, nunca spinner infinito, nunca botão que não faz nada, nunca fechar sozinho.

CONTEXTO
- Kotlin + Compose, applicationId ai.aura.app, minSdk 26. APK: scripts/build-apk.sh N gera auraai-vN.apk (versionCode N). Assine com a mesma chave de depuração para instalar por cima sem perder dados. Hoje a versão é a 5; a próxima é a 6.
- Motor: Python em engine/aura_engine, HTTP em 127.0.0.1:8765 com token. Roda no Termux via RUN_COMMAND (precisa allow-external-apps=true em ~/.termux/termux.properties).
- Já feito: permissões pedidas na abertura (AppPermissions), voz pelo reconhecimento do Android (LocalVoiceInput), tela de erro após crash, correção do estado do Termux (termuxSetupFrom).
- Faltam telas do design: Reformar app (4 telas), Checkup, Memória, Notificações, Capacidades (lista e detalhe), Conexões, Intercorrências, Bastidores completo. Veja o que existe em android/app/src/main/java/ai/aura/app/ui/screens.

TAREFAS (rode em paralelo com subagentes; cada uma com teste RED antes do código e GREEN depois)
1. Modo offline-first: camada local (repositório) para projetos, tarefas, rascunhos e histórico; gateway com estado Online/Offline; banner global "Motor desligado. Iniciar o motor".
2. Autodiagnóstico (tela Checkup): Termux instalado? permissão RUN_COMMAND? allow-external-apps? motor responde? contas? permissões do Android? cada linha com estado e um botão de correção; corrigir sozinho o que for possível (provisionar dependências no Termux, escrever termux.properties quando permitido, subir o motor).
3. Fluxo do Termux sem travar: botão "Iniciar o motor" via RUN_COMMAND, comando de colar com cópia automática, estados explícitos em cada passo, motor persistente.
4. Telas faltantes do design, fiéis aos .dc.html (tokens em docs/design/tokens.json), com transições e acessibilidade (alvo 44 dp, foco visível, reduzir movimento).
5. Reformar app (4 telas) integrada à skill de engenharia reversa (REA): escolher APK, escopo, reversão, fidelidade. Sem limitar o que o dono quer reverter.
6. Permissões: tela dentro do app listando o que falta, com atalho para as configurações quando o Android parar de perguntar.
7. Voz: depois de reconhecer, enviar ao chat; tratar aparelho sem reconhecedor e permissão negada.
8. Caça a bugs: rode a matriz docs/design/matriz-fluxos-falhas.md e auditoria-fluxo.md; corrija cada falha; teste rotação, voltar, teclado, tela cheia, app em segundo plano e retomada.
9. Teste real: emulador ou aparelho via adb (Android CLI do PC): instale auraai-v6.apk por cima do v5, confirme que dados e login permanecem, percorra todas as abas SEM Termux e com Termux, capture screenshots e logcat.

DELEGAÇÃO
- Use o Antigravity CLI (agy -p "<pedido>", só headless, pasta sem segredos) para segunda opinião, revisão e trabalho mecânico em massa. Nunca --dangerously-skip-permissions e nunca "Use a Google Cloud project" no login.
- Use Superpowers: test-driven-development, subagent-driven-development, systematic-debugging, verification-before-completion. Depois de cada bloco, rode UMA vez os testes do que mudou; suíte completa antes do PR.
- CI do GitHub está falhando por cota/runner (zero passos), então a prova é local: ./gradlew :app:testDebugUnitTest e build real.

SAÍDA
- Gere auraai-v6.apk e copie para C:\Users\hugov\Desktop\DESKTOP LIVE (pasta oficial; não me mande o APK pelo chat).
- Commits em português, na branch claude/telas-sem-termux; PR para a main quando a suíte e o build estiverem verdes.
- Termine com FEITO / COMPROVADO (saída dos testes) / PENDENTE / PRÓXIMO PASSO.

LIMITES
- Não reinicie nem altere o Claude Code do PC. Nunca me peça para colar segredo. Sem API paga: só as assinaturas. Apagar branch e force-push só com meu ok explícito (a branch claude/aura-ai-interface-design-6l7maw já foi mergeada e pode ser apagada quando eu autorizar).
- Não pare para perguntar "posso continuar?". Pare só para login no navegador, algo destrutivo ou algo impossível sem mim.
