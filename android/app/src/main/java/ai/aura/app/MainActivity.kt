package ai.aura.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import ai.aura.app.integration.AuraEngineClient
import ai.aura.app.integration.EngineCredentialStore
import ai.aura.app.integration.PairState
import ai.aura.app.integration.TermuxBridge
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.json.JSONObject

/** Temporary integration harness. Claude owns the final Compose AURA + lifeOS screens. */
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.setSoftInputMode(android.view.WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
        val bridge = TermuxBridge(this)
        val client = AuraEngineClient(EngineCredentialStore(this))
        setContent {
            MaterialTheme(colorScheme = lightColorScheme(
                background = Color(0xFFF5F4F1), surface = Color.White,
                onBackground = Color(0xFF15141A), primary = Color(0xFF15141A))) {
                IntegrationHarness(bridge, client, onPermission = { bridge.askPermission(this) })
            }
        }
    }
}

@Composable
private fun IntegrationHarness(
    bridge: TermuxBridge,
    client: AuraEngineClient,
    onPermission: () -> Unit
) {
    val scope = rememberCoroutineScope()
    var prompt by remember { mutableStateOf("") }
    var status by remember { mutableStateOf("Motor não verificado") }
    var activeJob by remember { mutableStateOf("") }
    val pairStatus by PairState.message.collectAsState()

    LaunchedEffect(activeJob) {
        if (activeJob.isBlank()) return@LaunchedEffect
        repeat(120) {
            delay(1000)
            val state = runCatching { client.job(activeJob) }.getOrNull() ?: return@repeat
            status = state.optString("status", "desconhecido")
            if (status in listOf("completed", "cancelled", "failed", "interrupted")) {
                val outcome = state.optJSONObject("result")
                val failure = state.optJSONObject("error")
                status = if (status == "completed") {
                    "Concluído. " + (outcome?.optString("stdout", outcome.toString()) ?: "")
                } else {
                    "Falha: " + (failure?.optString("message", status) ?: status)
                }
                return@LaunchedEffect
            }
        }
    }
    Surface(modifier = Modifier.fillMaxSize(), color = Color(0xFFF5F4F1)) {
        Column(
            modifier = Modifier
                .fillMaxSize().imePadding().verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp, vertical = 24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text("aura", style = MaterialTheme.typography.headlineMedium)
            Text("Integração Android + Termux", style = MaterialTheme.typography.titleLarge)
            Text("Interface de diagnóstico. O design final está sendo integrado a partir da PR #1.")
            Text("Pareamento: " + pairStatus)
            Button(onClick = onPermission, modifier = Modifier.fillMaxWidth()) {
                Text("Autorizar acesso ao Termux")
            }
            Button(onClick = {
                runCatching { bridge.pair() }.onFailure { status = it.message ?: "Erro no pareamento" }
            }, modifier = Modifier.fillMaxWidth()) { Text("Conectar motor local") }
            Button(onClick = {
                runCatching { bridge.startEngine() }
                    .onFailure { status = it.message ?: "Falha ao iniciar" }
            }, modifier = Modifier.fillMaxWidth()) { Text("Iniciar AURA Engine") }
            Button(onClick = {
                scope.launch {
                    status = runCatching { client.health().toString() }
                        .getOrElse { it.message ?: "Conexão indisponível" }
                }
            }, modifier = Modifier.fillMaxWidth()) { Text("Verificar conexão real") }
            OutlinedTextField(
                value = prompt,
                onValueChange = { prompt = it },
                label = { Text("Sua ideia ou comando") },
                modifier = Modifier.fillMaxWidth(),
                minLines = 2
            )
            Button(
                enabled = prompt.isNotBlank(),
                onClick = {
                    scope.launch {
                        status = "Interpretando..."
                        try {
                            val routing = client.route(prompt)
                            val submitted = client.submit(routing.getJSONObject("request"))
                            activeJob = submitted.getString("id")
                            status = submitted.optString("status")
                        } catch (e: Exception) {
                            status = e.message ?: "Erro ao enviar"
                        }
                    }
                },
                modifier = Modifier.fillMaxWidth()
            ) { Text("Enviar ao motor") }
            if (activeJob.isNotBlank() && status == "awaiting_approval") {
                Button(onClick = {
                    scope.launch { status = runCatching {
                        client.approve(activeJob).optString("status")
                    }.getOrElse { it.message ?: "Erro na autorização" } }
                }) { Text("Autorizar tarefa selecionada") }
            }
            Text("Estado real: " + status)
            Spacer(Modifier.height(20.dp))
        }
    }
}
