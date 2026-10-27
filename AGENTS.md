# Regras do projeto AURA AI (valem para qualquer agente: Claude, Antigravity, Codex)

## Dono e idioma
O dono é leigo em programação e acerta o "o quê". Fale português do Brasil, simples e curto. Termo técnico só com uma frase explicando.

## Regras que não mudam
1. **O app nunca fica morto.** Sem Termux, sem motor ou sem internet, TODAS as abas, telas e botões abrem. O que depende do motor falha com mensagem clara e uma ação ("Iniciar o motor", "Tentar de novo"). Projetos, Atividade, Dispositivos, Ajustes, histórico e rascunhos são locais e aparecem sempre. Nunca tela em branco, spinner infinito, botão mudo ou fechamento sozinho.
2. **Nenhum APK é entregue antes de estar 100%.** Não gere nem envie APK para o dono até a matriz de testes (docs/MATRIZ_DE_TESTES.md) estar toda verde. APK de teste interno fica fora do chat.
3. **Prova local, não GitHub.** A CI do GitHub Actions está sem runner. Não espere por ela e não tente consertá-la. Compile e teste localmente: `cd android && ./gradlew :app:testDebugUnitTest --offline`; engine: `cd engine && python -m unittest`.
4. **TDD de verdade.** Teste primeiro, veja falhar (RED), implemente, veja passar (GREEN). Cada botão, interação e estado tem teste. Use Compose UI Test com Robolectric na JVM para clicar em tudo sem emulador.
5. **Agent Skills.** Antes de cada tarefa, use as skills de ~/.claude/skills e do Superpowers que se aplicam: test-driven-development, systematic-debugging, frontend-ui-engineering, api-and-interface-design, security-and-hardening, verification-before-completion, code-simplification. Subagentes para tarefas independentes, em paralelo.
6. **Design é fiel.** As telas em docs/design/telas/*.dc.html e tokens em docs/design/tokens.json são a referência. Um botão principal por tela, verbo + objeto, erro diz o que fazer, tela vazia convida a agir, alvo >= 44 dp, foco visível.
7. **Sem segredo.** Nunca imprimir token, chave ou senha. Nunca pedir ao dono para colar segredo. Sem API paga.
8. **Sem travar.** Se um problema bloquear, registre em docs/REGISTRO.md, contorne e siga com as partes independentes. Depois de 2 tentativas sem avanço, mude de estratégia.
9. **Registro.** Ao fim de cada bloco, atualize docs/REGISTRO.md (objetivo, estado verificado, evidência, bloqueio, próximo item) e, se houver, a nota do projeto no cofre do Obsidian do dono.
10. Branch de trabalho: `claude/telas-sem-termux`. Commits em português, pequenos, push no remoto. Apagar branch e force-push só com ok explícito do dono.
