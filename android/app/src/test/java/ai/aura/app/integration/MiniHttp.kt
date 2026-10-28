package ai.aura.app.integration

import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.URI
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.concurrent.thread

/**
 * Servidor HTTP mínimo só para testes JVM (com.sun.net.httpserver não existe no classpath do Android).
 * Mesma forma de uso: createContext(prefixo) { ex -> ... }, start(), stop(0), address.port.
 */
class MiniHttpExchange(
    val requestMethod: String,
    val requestURI: URI,
    val requestBody: InputStream,
    private val out: OutputStream,
) {
    class Headers { val all = linkedMapOf<String, String>(); fun add(k: String, v: String) { all[k] = v } }

    val responseHeaders = Headers()
    private var status = 200
    val responseBody: OutputStream = object : ByteArrayOutputStream() {
        override fun close() {
            super.close()
            val body = toByteArray()
            val head = StringBuilder("HTTP/1.1 $status OK\r\n")
            responseHeaders.all.forEach { (k, v) -> head.append("$k: $v\r\n") }
            head.append("Content-Length: ${body.size}\r\nConnection: close\r\n\r\n")
            out.write(head.toString().toByteArray(Charsets.UTF_8))
            out.write(body)
            out.flush()
        }
    }

    @Suppress("UNUSED_PARAMETER")
    fun sendResponseHeaders(code: Int, length: Long) { status = code }
}

class MiniHttpServer private constructor(private val socket: ServerSocket) {
    private val contexts = CopyOnWriteArrayList<Pair<String, (MiniHttpExchange) -> Unit>>()
    @Volatile private var running = true
    val address: InetSocketAddress get() = socket.localSocketAddress as InetSocketAddress

    fun createContext(prefix: String, handler: (MiniHttpExchange) -> Unit) { contexts += prefix to handler }

    fun start() {
        thread(isDaemon = true, name = "mini-http") {
            while (running) {
                val client = try { socket.accept() } catch (_: Exception) { break }
                thread(isDaemon = true) { serve(client) }
            }
        }
    }

    fun stop(@Suppress("UNUSED_PARAMETER") delay: Int) { running = false; runCatching { socket.close() } }

    private fun serve(client: Socket) = client.use { c ->
        try {
            val input = c.getInputStream().buffered()
            fun line(): String? {
                val sb = StringBuilder()
                while (true) {
                    val b = input.read()
                    if (b < 0) return if (sb.isEmpty()) null else sb.toString()
                    if (b == '\n'.code) return sb.toString().trimEnd('\r')
                    sb.append(b.toChar())
                }
            }
            val request = line() ?: return@use
            val (method, target) = request.split(" ").let { it[0] to it[1] }
            var length = 0
            while (true) {
                val h = line() ?: break
                if (h.isEmpty()) break
                if (h.lowercase().startsWith("content-length:")) length = h.substringAfter(":").trim().toInt()
            }
            val body = ByteArray(length).also { var n = 0; while (n < length) { val r = input.read(it, n, length - n); if (r < 0) break; n += r } }
            val uri = URI(target)
            val handler = contexts.filter { uri.path.startsWith(it.first) }.maxByOrNull { it.first.length }?.second
            val ex = MiniHttpExchange(method, uri, body.inputStream(), c.getOutputStream())
            if (handler == null) {
                ex.sendResponseHeaders(404, 0); ex.responseBody.use { it.write("{}".toByteArray()) }
            } else handler(ex)
        } catch (_: Exception) { /* conexão encerrada pelo teste */ }
    }

    companion object {
        fun create(): MiniHttpServer = MiniHttpServer(ServerSocket(0, 50, java.net.InetAddress.getByName("127.0.0.1")))
    }
}
