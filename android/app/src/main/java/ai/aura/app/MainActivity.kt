package ai.aura.app

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.remember
import ai.aura.app.integration.AuraEngineClient
import ai.aura.app.integration.EngineCredentialStore
import ai.aura.app.integration.RealEngineGateway
import ai.aura.app.ui.AuraApp

/** Native AURA shell. No simulated provider results are ever shown in production. */
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        window.setSoftInputMode(android.view.WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
        setContent {
            val gateway = remember {
                RealEngineGateway(AuraEngineClient(EngineCredentialStore(applicationContext)))
            }
            AuraApp(
                gateway = gateway,
                onOpenUrl = { url ->
                    val uri = runCatching { Uri.parse(url) }.getOrNull()
                    if (uri?.scheme == "https" && !uri.host.isNullOrBlank()) {
                        startActivity(Intent(Intent.ACTION_VIEW, uri))
                    }
                },
            )
        }
    }
}
