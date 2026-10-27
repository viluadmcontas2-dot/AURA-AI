# Plano: app autônomo com Termux como motor

Objetivo do dono: o app se autodiagnostica, instala o que falta no Termux sozinho,
corrige caminhos sem recompilar, e orquestra Claude Code, Gemini e Antigravity (agy)
com subagentes. Nenhum passo bloqueia o uso.

## Limites honestos (decidir antes de construir)
- "Descompilar e reconstruir 100% de qualquer APK" não é garantido: código ofuscado,
  nativo (.so) e APKs protegidos têm limite técnico. O app reporta o que conseguiu
  reconstruir, com nível de confiança, e o que ficou de fora.
- Engenharia reversa só em APK próprio ou com autorização. Apps de terceiros
  protegidos por licença ficam fora do escopo automático.
- Teste no aparelho (S23 e Taytech) é a prova; compilação sozinha não basta.

## Fases
1. **Autodiagnóstico (base)** — checklist no app: Termux instalado, permissão
   RUN_COMMAND, `allow-external-apps`, Python, motor respondendo, versão do motor.
   Cada item tem estado (ok / corrigível / manual) e ação. Nada bloqueia a tela.
2. **Auto-provisionamento no Termux** — comandos idempotentes via RUN_COMMAND
   (`pkg install python`, `git`, `android-tools` para ADB). Sem ação manual além
   da linha `allow-external-apps` (única limitação do Android).
3. **Configuração sem recompilar** — caminhos e perfis em arquivo no Termux
   (editável pelo app), com validação e correção de caminho inexistente.
4. **Orquestração** — Claude Code, Gemini (API) e agy com roteamento por custo
   (policy.json/models.json já existem no motor), subagentes e skills de processo
   (TDD, RED/GREEN, constraint-driven, subagent-driven).
5. **Engenharia reversa** — skill REA (verify/reconstruction) via motor no Termux,
   com relatório de confiança por parte do APK.
6. **ADB por USB** — rara, mas prevista: detectar host USB e usar adb do Termux
   quando houver OTG.

## Primeira entrega (esta rodada)
- Fase 1: modelo de autodiagnóstico com testes JVM (regra pura, sem emulador).
