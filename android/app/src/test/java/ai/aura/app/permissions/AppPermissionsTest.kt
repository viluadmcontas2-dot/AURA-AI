package ai.aura.app.permissions

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AppPermissionsTest {

    @Test
    fun `android 13 pede notificacao, midia, bluetooth, wifi proximo, microfone e camera`() {
        val p = AppPermissions.runtimeFor(sdk = 34)

        assertTrue("android.permission.POST_NOTIFICATIONS" in p)
        assertTrue("android.permission.READ_MEDIA_IMAGES" in p)
        assertTrue("android.permission.BLUETOOTH_CONNECT" in p)
        assertTrue("android.permission.BLUETOOTH_SCAN" in p)
        assertTrue("android.permission.NEARBY_WIFI_DEVICES" in p)
        assertTrue("android.permission.RECORD_AUDIO" in p)
        assertTrue("android.permission.CAMERA" in p)
        assertFalse("android.permission.READ_EXTERNAL_STORAGE" in p)
    }

    @Test
    fun `android 12 nao pede notificacao nem midia nova, pede bluetooth e armazenamento antigo`() {
        val p = AppPermissions.runtimeFor(sdk = 31)

        assertFalse("android.permission.POST_NOTIFICATIONS" in p)
        assertTrue("android.permission.BLUETOOTH_CONNECT" in p)
        assertTrue("android.permission.READ_EXTERNAL_STORAGE" in p)
        assertTrue("android.permission.ACCESS_FINE_LOCATION" in p)
    }

    @Test
    fun `android 9 usa bluetooth classico sem pedir permissao nova`() {
        val p = AppPermissions.runtimeFor(sdk = 28)

        assertFalse("android.permission.BLUETOOTH_CONNECT" in p)
        assertTrue("android.permission.WRITE_EXTERNAL_STORAGE" in p)
    }

    @Test
    fun `sem duplicados`() {
        val p = AppPermissions.runtimeFor(sdk = 34)
        assertEquals(p.size, p.toSet().size)
    }

    @Test
    fun `faltando lista so o que ainda nao foi concedido`() {
        val missing = AppPermissions.missing(sdk = 34) { it == "android.permission.CAMERA" }

        assertFalse("android.permission.CAMERA" in missing)
        assertTrue("android.permission.RECORD_AUDIO" in missing)
    }
}
