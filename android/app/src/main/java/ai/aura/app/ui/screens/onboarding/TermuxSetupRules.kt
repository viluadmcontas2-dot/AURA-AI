package ai.aura.app.ui.screens.onboarding

/**
 * Regra do passo "Conectar o Termux".
 * - Pacote instalado e permissão concedida são provas locais do próprio aparelho.
 * - "Comandos externos" só fica pronto quando o motor responde: só ele prova que um comando rodou.
 * Motor respondendo implica Termux encontrado e permissão concedida.
 */
fun termuxSetupFrom(installed: Boolean, permissionGranted: Boolean, engineUp: Boolean): TermuxSetupState =
    TermuxSetupState(
        termuxFound = installed || engineUp,
        permissionGranted = permissionGranted || engineUp,
        externalAppsAllowed = engineUp,
    )
