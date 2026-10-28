package ai.aura.app.offline

/** Comandos fixos e sinais verificados da ponte com o Termux. */
interface TermuxPort : TermuxProbe {
    fun isPaired(): Boolean
    fun pageSize(): Int
    fun requestPermission()
    fun openInstaller()
    fun openTermux()
    suspend fun probe(): TermuxResult
    suspend fun pairAndWait(): TermuxResult
    suspend fun runEngine(): TermuxResult
    /** Copia o motor embutido no app para o Termux (e instala o Python se faltar). Idempotente. */
    suspend fun installEngine(): TermuxResult = TermuxResult.Ok
    suspend fun engineUp(): Boolean
}

enum class TermuxResult { Ok, NotAllowed, Failed }
