package ai.aura.app.ui.screens.reform

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Regras puras das telas Reformar 1, 2 e 5: sem Compose, sem Robolectric. */
class ReformModelsTest {

    @Test
    fun `camera de re e app do sistema ficam protegidos e app comum nao`() {
        val protegidos = listOf(
            ReformApp("com.exemplo.cam", "Câmera de ré"),
            ReformApp("com.syu.camera360", "Visão 360"),
            ReformApp("com.exemplo.avm", "Panorâmica"),
            ReformApp("com.exemplo.x", "Marcha à ré"),
            ReformApp("com.android.settings", "Configurações", system = true),
        )
        protegidos.forEach { assertNotNull(it.label, it.protectedReason) }
        assertEquals("Segurança do carro: o AURA não mexe", protegidos[0].protectedReason)
        assertEquals("App do sistema: o AURA não mexe", protegidos.last().protectedReason)

        val comuns = listOf(
            ReformApp("com.exemplo.player", "Player de música"),
            ReformApp("com.exemplo.camada", "Camadas"),
            ReformApp("com.exemplo.resumo", "Resumo do dia"),
            ReformApp("com.exemplo.radio", "Rádio FM"),
        )
        comuns.forEach { assertNull(it.label, it.protectedReason) }
    }

    @Test
    fun `idade e potencial seguem os limites e nada e inventado sem dado`() {
        assertEquals("Muito antigo", ReformApp("a", "A", year = 2016).ageLabel(2026))
        assertEquals("Antigo", ReformApp("a", "A", year = 2017).ageLabel(2026))
        assertNull(ReformApp("a", "A", year = 2024).ageLabel(2026))
        assertNull(ReformApp("a", "A").ageLabel(2026))

        assertEquals("Alto", reformPotentialLabel(0.88f))
        assertEquals("Alto", reformPotentialLabel(0.78f))
        assertEquals("Médio", reformPotentialLabel(0.52f))
        assertEquals("Baixo", reformPotentialLabel(0.2f))

        assertEquals("v2.1 · 2016", ReformApp("a", "A", version = "2.1", year = 2016).details)
        assertEquals("v3", ReformApp("a", "A", version = "v3").details)
        assertEquals("2016", ReformApp("a", "A", year = 2016).details)
        assertNull(ReformApp("a", "A").details)
    }

    @Test
    fun `tipo do app vem so do nome e so enfeita o icone`() {
        assertEquals(ReformAppKind.Music, ReformApp("com.exemplo.player", "Player de música").kind)
        assertEquals(ReformAppKind.Navigation, ReformApp("com.exemplo.gps", "Navegador GPS").kind)
        assertEquals(ReformAppKind.Radio, ReformApp("com.exemplo.radio", "Rádio FM").kind)
        assertEquals(ReformAppKind.Camera, ReformApp("com.exemplo.cam", "Câmera de ré").kind)
        assertEquals(ReformAppKind.Generic, ReformApp("com.exemplo.agenda", "Agenda").kind)
    }

    @Test
    fun `escopos trazem tempo risco e uma unica recomendacao`() {
        assertEquals(listOf("10 min", "25 min", "1 h 30"), ReformScope.entries.map { it.durationLabel })
        assertEquals(listOf("Risco baixo", "Risco médio", "Risco maior"), ReformScope.entries.map { it.risk.label })
        assertEquals(listOf(ReformScope.VisualAndFlows), ReformScope.entries.filter { it.recommended })
        assertEquals(ReformScope.VisualAndFlows, ReformScope.default)
        assertEquals("Cerca de 1 h 30 · usa só a sua assinatura", ReformScope.FullRebuild.estimate)
        assertEquals("1 h", reformDurationLabel(60))
        assertEquals("1 h 05", reformDurationLabel(65))
        assertEquals(90, REFORM_FIDELITY_TARGET_PERCENT)
    }

    @Test
    fun `ferramentas sempre listam as seis exigidas e so mudam o que o motor contou`() {
        val vazio = ReformToolsState().rows
        assertEquals(
            listOf("Node.js 22", "JDK 21", "JADX sem tela", "Apktool", "adb", "REA, agentes e servidor MCP"),
            vazio.map { it.label },
        )
        assertTrue(vazio.all { it.status == ReformToolStatus.Unknown && it.chipText == "Não verificado" })

        val rows = ReformToolsState(
            tools = listOf(
                ReformTool("jadx", "JADX sem tela", ReformToolStatus.Installing, percent = 63),
                ReformTool("node", "Node.js 22", ReformToolStatus.Ready),
                ReformTool("extra", "Outra", ReformToolStatus.Queued),
            ),
        ).rows
        assertEquals(7, rows.size)
        assertEquals(ReformToolStatus.Ready, rows[0].status)
        assertEquals(ReformToolStatus.Unknown, rows[1].status)
        assertEquals("Instalando 63%", rows[2].chipText)
        assertEquals("Outra", rows.last().label)
        assertEquals("Instalando", ReformTool("j", "J", ReformToolStatus.Installing).chipText)
        assertEquals("Pronto", ReformTool("j", "J", ReformToolStatus.Ready, percent = 40).chipText)
    }

    @Test
    fun `botao principal das ferramentas segue o estado e nunca fica mudo`() {
        fun action(vararg s: ReformToolStatus) = ReformToolsState(
            tools = s.mapIndexed { i, st -> ReformTool(REFORM_REQUIRED_TOOLS[i].id, "t$i", st) },
        ).action

        assertEquals(ReformToolsAction.Install, ReformToolsState().action)
        assertEquals(ReformToolsAction.Install, action(ReformToolStatus.Ready, ReformToolStatus.Failed))
        assertEquals(ReformToolsAction.Install, action(ReformToolStatus.Missing))
        assertEquals(ReformToolsAction.Busy, action(ReformToolStatus.Ready, ReformToolStatus.Installing))
        assertEquals(ReformToolsAction.Busy, action(ReformToolStatus.Queued))
        val todasProntas = REFORM_REQUIRED_TOOLS.map { ReformTool(it.id, it.label, ReformToolStatus.Ready) }
        assertEquals(ReformToolsAction.Done, ReformToolsState(tools = todasProntas).action)
        assertEquals("Instalar o que falta", ReformToolsAction.Install.label)
        assertEquals("Voltar ao projeto", ReformToolsAction.Done.label)
    }
}
