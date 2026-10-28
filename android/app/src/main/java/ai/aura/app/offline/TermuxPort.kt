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
    suspend fun engineUp(): Boolean
}

enum class TermuxResult { Ok, NotAllowed, Failed }
