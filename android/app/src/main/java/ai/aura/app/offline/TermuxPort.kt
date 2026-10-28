package ai.aura.app.offline

/** Comandos fixos e sinais verificados da ponte com o Termux. */
interface TermuxPort : TermuxProbe {
    fun isPaired(): Boolean
    fun pageSize(): Int
    fun requestPermission()

    /** O Android já recusou o pedido de permissão duas vezes: o botão passa a abrir os ajustes do app. */
    fun permissionBlocked(): Boolean = false
    fun openInstaller()
    fun openTermux()
    suspend fun probe(): TermuxResult
    suspend fun pairAndWait(): TermuxResult
    suspend fun runEngine(): TermuxResult
    /**
     * Copia o motor embutido no app para o Termux (e instala o Python se faltar). Idempotente.
     * Só o [ai.aura.app.ui.screens.onboarding.TermuxFlow] chama, e só depois do aceite do plano (C4): nunca ao voltar
     * ao app, ao abrir ou ao reconectar.
     */
    suspend fun installEngine(): TermuxResult = TermuxResult.Ok

    /** O app já preparou o motor neste Termux (marca local gravada depois de uma instalação que terminou bem). */
    fun engineInstalled(): Boolean = false

    /** Grava a marca de [engineInstalled]. Só depois de [installEngine] devolver Ok. */
    fun markEngineInstalled() = Unit

    /** Tamanho do motor embutido no APK (bytes): é o que o plano local declara. */
    fun engineAssetBytes(): Long = 0L

    /** Rede atual, para o plano mostrar "Conexão: Wi-Fi/dados móveis". Wi-Fi não é consentimento. */
    fun networkKind(): NetworkKind = NetworkKind.Unknown
    suspend fun engineUp(): Boolean

    /** Estado do motor com detalhe: fora do ar, vivo mas recusando o token (401) ou no ar (com a versão do código dele). */
    suspend fun engineProbe(): EngineProbe = if (engineUp()) EngineProbe.Up(expectedEngineBuild()) else EngineProbe.Down

    /** Versão (sha256 curto de aura_engine/) do motor embutido neste APK; null = não sei, então não compara. */
    fun expectedEngineBuild(): String? = null

    /** Pede ao motor que está rodando para sair (antes de trocar o código dele). */
    suspend fun stopEngine(): TermuxResult = TermuxResult.Ok

    /** Apaga a credencial guardada no Android (o token não bate com o do motor vivo). */
    fun forgetCredential() = Unit
}

enum class TermuxResult { Ok, NotAllowed, Failed }

/** Tipo de rede que o plano de instalação mostra. */
enum class NetworkKind { Wifi, Mobile, Unknown }

/** Resposta de GET /v1/health vista pelo app. */
sealed interface EngineProbe {
    data object Down : EngineProbe

    /** O motor está vivo, mas recusou o nosso token. */
    data object Unauthorized : EngineProbe

    /** [build] = engine_build que o motor informou; null = motor velho, que ainda não informa. */
    data class Up(val build: String?) : EngineProbe
}
