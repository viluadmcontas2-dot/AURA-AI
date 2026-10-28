package ai.aura.app.offline

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

class EngineLinkTest {
    private class Probe(var installed: Boolean = true, var permission: Boolean = true) : TermuxProbe {
        override fun installed() = installed
        override fun hasPermission() = permission
    }

    private val semConexao = IOException("Termux não responde")

    @Test fun `comeca desconhecido, sem culpar o motor antes da primeira tentativa`() {
        assertEquals(LinkState.Unknown, EngineLink(Probe()).state.value)
    }

    @Test fun `uma chamada que deu certo marca online`() {
        val link = EngineLink(Probe())

        link.offline(semConexao)
        link.online()

        assertEquals(LinkState.Online, link.state.value)
    }

    @Test fun `com tudo instalado e autorizado, falha de rede significa motor parado`() {
        val link = EngineLink(Probe())

        link.offline(semConexao)

        assertEquals(LinkState.Offline(OfflineReason.EngineStopped), link.state.value)
    }

    @Test fun `sem o Termux instalado o motivo e NoTermux`() {
        val link = EngineLink(Probe(installed = false))

        link.offline(semConexao)

        assertEquals(LinkState.Offline(OfflineReason.NoTermux), link.state.value)
    }

    @Test fun `com o Termux mas sem a permissao de executar comandos o motivo e NoPermission`() {
        val link = EngineLink(Probe(permission = false))

        link.offline(semConexao)

        assertEquals(LinkState.Offline(OfflineReason.NoPermission), link.state.value)
    }

    @Test fun `sem credencial guardada o motivo e NoToken`() {
        val link = EngineLink(Probe())

        link.offline(EngineUnreachableException("Conecte o Termux em Conexões", OfflineReason.NoToken))

        assertEquals(LinkState.Offline(OfflineReason.NoToken), link.state.value)
    }

    @Test fun `a permissao vem antes da credencial, porque parear precisa dela`() {
        val link = EngineLink(Probe(permission = false))

        link.offline(EngineUnreachableException("sem credencial", OfflineReason.NoToken))

        assertEquals(LinkState.Offline(OfflineReason.NoPermission), link.state.value)
    }

    @Test fun `Termux ausente vence qualquer outro motivo`() {
        val link = EngineLink(Probe(installed = false, permission = false))

        link.offline(EngineUnreachableException("sem credencial", OfflineReason.NoToken))

        assertEquals(LinkState.Offline(OfflineReason.NoTermux), link.state.value)
    }

    @Test fun `enquanto o motor esta iniciando, falha de fundo nao derruba o aviso de iniciando`() {
        val link = EngineLink(Probe())

        link.starting()
        link.offline(semConexao)

        assertEquals(LinkState.Starting, link.state.value)
    }

    @Test fun `se a partida falha o aviso volta para offline com o motivo atual`() {
        val link = EngineLink(Probe(permission = false))

        link.starting()
        link.startFailed()

        assertEquals(LinkState.Offline(OfflineReason.NoPermission), link.state.value)
    }

    @Test fun `se o motor responde durante a partida fica online`() {
        val link = EngineLink(Probe())

        link.starting()
        link.online()

        assertEquals(LinkState.Online, link.state.value)
    }

    @Test fun `startFailed fora da partida nao muda nada`() {
        val link = EngineLink(Probe())

        link.online()
        link.startFailed()

        assertEquals(LinkState.Online, link.state.value)
    }

    @Test fun `a excecao com motivo e uma IOException, para os tratadores antigos continuarem valendo`() {
        assertTrue(EngineUnreachableException("x", OfflineReason.NoToken) is IOException)
    }
}
