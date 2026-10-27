package ai.aura.app.ui.screens.onboarding

import org.junit.Assert.assertEquals
import org.junit.Test

class TermuxSetupRulesTest {

    @Test
    fun `termux instalado sem motor aparece como encontrado mas nao pronto`() {
        val state = termuxSetupFrom(installed = true, permissionGranted = false, engineUp = false)

        assertEquals(true, state.termuxFound)
        assertEquals(false, state.permissionGranted)
        assertEquals(false, state.externalAppsAllowed)
        assertEquals(false, state.ready)
    }

    @Test
    fun `sem termux instalado nada fica pronto mesmo com permissao`() {
        val state = termuxSetupFrom(installed = false, permissionGranted = true, engineUp = false)

        assertEquals(false, state.termuxFound)
        assertEquals(true, state.permissionGranted)
        assertEquals(false, state.ready)
    }

    @Test
    fun `motor respondendo prova comandos externos e Termux encontrado`() {
        val state = termuxSetupFrom(installed = false, permissionGranted = false, engineUp = true)

        assertEquals(true, state.termuxFound)
        assertEquals(true, state.permissionGranted)
        assertEquals(true, state.externalAppsAllowed)
        assertEquals(true, state.ready)
    }

    @Test
    fun `termux instalado com permissao e sem motor ainda nao libera comandos externos`() {
        val state = termuxSetupFrom(installed = true, permissionGranted = true, engineUp = false)

        assertEquals(false, state.externalAppsAllowed)
        assertEquals(false, state.ready)
    }
}
