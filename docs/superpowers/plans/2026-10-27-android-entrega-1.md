# Entrega 1 — App Android compilável (interface + integração)

> Processo: Superpowers (TDD, verification-before-completion). Fonte visual: `docs/design/` (tokens.json + telas/*.dc.html). Fonte do motor: `docs/ENGINE_CONTRACT.md`.

**Meta:** APK debug que abre, mostra as 4 abas (Início, Projetos, Dispositivos, Atividade), fala com o motor em `127.0.0.1:8765` e cumpre o fluxo principal: pedir → entendi → executando → aprovação → resultado (Feito/Comprovado/Falta/Próximo passo).

**Stack:** Kotlin, Jetpack Compose (Material3 só como base), Gradle KTS, minSdk 26, OkHttp + kotlinx.serialization, Keystore para o token. Pasta `android/`.

## Divisão
- **GPT (Codex) — motor:** `engine/`, contrato HTTP, `pair --emit-token`, provas no Termux. Mudança de contrato = PR/commit com aviso aqui.
- **Claude — interface e integração:** `android/` inteiro, cliente HTTP do contrato, pareamento, estados reais (nunca "sucesso" só por job enfileirado).
- **Delegado (subagentes/Codex/agy):** gerar tema a partir do tokens.json, componentes repetitivos, testes unitários básicos. Claude revisa e integra.

## Fases (cada uma: teste RED → GREEN → commit)
1. **Esqueleto:** projeto Gradle, tema `AuraTheme` (cores, tipo, raios, espaço do tokens.json), teste que compara tema × tokens.json. CI `android-ci.yml` (só pull_request) compila `assembleDebug` + testes unitários.
2. **Navegação:** 4 abas com barra inferior (alvo ≥44dp, foco visível), estado preservado ao trocar de aba.
3. **Cliente do motor:** modelos do contrato, `EngineClient` (jobs, approve, cancel, events?since, capabilities), reconciliação ao voltar do segundo plano. Testado com servidor falso (MockWebServer) + contrato do GPT.
4. **Composer:** teclado com `imePadding`, barra inferior some, texto preservado, rolagem ancorada na última mensagem.
5. **Fluxo principal:** telas Chat-1..5 (Entendi, Comandos, Executando, Aprovação com payload imutável, Resultado em 4 blocos).
6. **Pareamento Termux:** RUN_COMMAND com permissão explícita, token no Keystore, estados Conectado / Precisa de login / Não instalado / Offline.

## Fora desta entrega
Voz, Reformar apps (REA), skill de recepção (dyslex.ai), Conexões/Ajustes completos, onboarding completo.

## Prova
Cada fase: saída de teste + CI verde. Interface: renderizar e comparar com o `.dc.html` (captura). Aparelho real (DEVICE-01) é prova separada; nada é "pronto" sem ela.

## Riscos
- Nomes de intent do Termux vêm de memória: conferir na doc oficial antes da fase 6.
- Sem SDK Android local garantido: compilar pelo GitHub Actions.
- Codex pode estar sem cota; agy ainda sem login: usar subagentes nesses casos.
