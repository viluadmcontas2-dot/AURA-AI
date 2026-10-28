package ai.aura.app.integration

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.shadows.ShadowNetwork
import org.robolectric.shadows.ShadowNetworkCapabilities

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class NetworkReporterTest {
    private val cm = ApplicationProvider.getApplicationContext<Context>()
        .getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager

    private fun caps(transport: Int, notMetered: Boolean): NetworkCapabilities {
        val c = ShadowNetworkCapabilities.newInstance()
        shadowOf(c).addTransportType(transport)
        if (notMetered) shadowOf(c).addCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED)
        return c
    }

    private val net: Network get() = ShadowNetwork.newInstance(100)
    private fun callback() = shadowOf(cm).networkCallbacks.single()

    @Test
    fun `Wi-Fi sem medicao vira metered false e transport wifi`() {
        assertEquals(NetworkSnapshot(false, "wifi"), snapshotOf(caps(NetworkCapabilities.TRANSPORT_WIFI, notMetered = true)))
    }

    @Test
    fun `dados moveis (sem NOT_METERED) viram metered true e transport cellular`() {
        assertEquals(NetworkSnapshot(true, "cellular"), snapshotOf(caps(NetworkCapabilities.TRANSPORT_CELLULAR, notMetered = false)))
    }

    @Test
    fun `Wi-Fi que o dono marcou como medido tambem e medido`() {
        assertEquals(NetworkSnapshot(true, "wifi"), snapshotOf(caps(NetworkCapabilities.TRANSPORT_WIFI, notMetered = false)))
    }

    @Test
    fun `sem informacao da rede o transporte e unknown e nao bloqueia`() {
        assertEquals(NetworkSnapshot(false, "unknown"), snapshotOf(null))
    }

    @Test
    fun `ao mudar a rede envia uma vez, e repetida igual nao reenvia`() {
        val scope = TestScope(UnconfinedTestDispatcher())
        val sent = mutableListOf<NetworkSnapshot>()
        val reporter = NetworkReporter(cm, scope, { sent += it })
        reporter.start()
        callback().onCapabilitiesChanged(net, caps(NetworkCapabilities.TRANSPORT_WIFI, true))
        callback().onCapabilitiesChanged(net, caps(NetworkCapabilities.TRANSPORT_WIFI, true))
        callback().onCapabilitiesChanged(net, caps(NetworkCapabilities.TRANSPORT_CELLULAR, false))
        scope.runCurrent()
        assertEquals(listOf(NetworkSnapshot(false, "wifi"), NetworkSnapshot(true, "cellular")), sent.filter { it.transport != "unknown" })
        assertEquals(NetworkSnapshot(true, "cellular"), reporter.current())
        reporter.stop()
        assertTrue(shadowOf(cm).networkCallbacks.isEmpty())
    }

    @Test
    fun `motor parado nao derruba o app e tenta de novo depois`() {
        val scope = TestScope(UnconfinedTestDispatcher())
        var attempts = 0
        val reporter = NetworkReporter(cm, scope, { attempts++; if (attempts < 3) throw java.io.IOException("motor parado") }, retryDelaysMs = listOf(1_000, 2_000))
        reporter.start()
        callback().onCapabilitiesChanged(net, caps(NetworkCapabilities.TRANSPORT_CELLULAR, false))
        scope.runCurrent()
        scope.advanceTimeBy(1_001)
        scope.advanceTimeBy(2_001)
        assertTrue("tentou de novo ate conseguir", attempts >= 3)
        reporter.stop()
    }

    @Test
    fun `sem nenhuma rede vista, current e nulo`() {
        val reporter = NetworkReporter(cm, TestScope(UnconfinedTestDispatcher()), { })
        assertNull(reporter.current())
    }
}
