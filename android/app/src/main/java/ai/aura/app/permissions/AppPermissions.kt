package ai.aura.app.permissions

/** Permissões que o AURA pede em tempo de execução, conforme a versão do Android. */
object AppPermissions {
    private const val P = "android.permission."

    fun runtimeFor(sdk: Int): List<String> = buildList {
        add(P + "RECORD_AUDIO")
        add(P + "CAMERA")
        if (sdk >= 33) add(P + "POST_NOTIFICATIONS")
        // Armazenamento: mídia granular no 13+, leitura/escrita antiga abaixo.
        if (sdk >= 33) {
            add(P + "READ_MEDIA_IMAGES"); add(P + "READ_MEDIA_VIDEO"); add(P + "READ_MEDIA_AUDIO")
        } else {
            add(P + "READ_EXTERNAL_STORAGE")
            if (sdk <= 28) add(P + "WRITE_EXTERNAL_STORAGE")
        }
        // Bluetooth: permissões novas no 12+.
        if (sdk >= 31) { add(P + "BLUETOOTH_CONNECT"); add(P + "BLUETOOTH_SCAN") }
        // Wi-Fi: dispositivos próximos no 13+, localização antes disso.
        if (sdk >= 33) add(P + "NEARBY_WIFI_DEVICES") else add(P + "ACCESS_FINE_LOCATION")
    }.distinct()

    fun missing(sdk: Int, isGranted: (String) -> Boolean): List<String> =
        runtimeFor(sdk).filterNot(isGranted)
}
