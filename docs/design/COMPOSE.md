# Fundação Compose (ai.aura.app.ui)

Código em `android/app/src/main/java/ai/aura/app/ui/`. Fonte de verdade visual: `tokens.json` e `telas/`.

- `theme/`: `AuraTokens` (espelho do JSON, testado em `TokensTest`), `AuraColors`, `AuraType`, `AuraShapes`, `AuraSpacing`, `AuraTheme`.
- `components/`: `AuraButtonPrimary/Secondary/Text/Destructive`, `StatusChip`, `AuraFilterChip`, `AuraCard`, `AuraProgressBar`, `ResultBlocks`, `AuraComposer`/`AuraComposerField`, `EmptyState`.
- `AuraApp.kt`: shell com 4 abas por estado (`rememberSaveable`), barra inferior some com teclado.
- `screens/`: `HomeScreen`, `ProjectsScreen`, `DevicesScreen`, `ActivityScreen` (placeholders; mantenha a assinatura `(gateway, modifier)`).
- `engine/UiEngine.kt`: `EngineGateway` + `FakeEngineGateway`, modelos conforme `docs/ENGINE_CONTRACT.md`.

Desvios e pendências
- Fontes: `SansSerif`/`Monospace` do sistema no lugar de Bricolage/Atkinson/JetBrains (sem dependência de fontes).
- Ícones desenhados em Canvas; sem material-icons-extended.
- Activity precisa de `enableEdgeToEdge()` e `adjustResize` (fora desta pasta).
- Não compilado nem testado em aparelho (sem SDK Android no ambiente); validar no CI e no Termux/Android real.
