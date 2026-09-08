package ml.melun.mangaview.data.network

import java.io.Closeable
import java.io.IOException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.ServerSocket
import java.net.Socket
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.Semaphore
import okhttp3.Dns

/** Private CONNECT relay on loopback; it never terminates TLS or handles decrypted HTTP. */
internal class LocalTlsRelay(private val dns: Dns) : Closeable {
    private val server = ServerSocket(0, 32, InetAddress.getByName("127.0.0.1"))
    private val workers = Executors.newCachedThreadPool { task -> Thread(task, "source-tls-relay").apply { isDaemon = true } }
    private val slots = Semaphore(32)
    private val sockets = ConcurrentHashMap.newKeySet<Socket>()
    val authorization = "Bearer ${UUID.randomUUID()}"
    val proxy = Proxy(Proxy.Type.HTTP, InetSocketAddress("127.0.0.1", server.localPort))

    init { workers.execute(::acceptConnections) }

    private fun acceptConnections() {
        while (!server.isClosed) {
            val incoming = try { server.accept() } catch (_: IOException) { break }
            if (!slots.tryAcquire()) { incoming.close(); continue }
            sockets.add(incoming)
            try { workers.execute { serve(incoming) } }
            catch (_: java.util.concurrent.RejectedExecutionException) { release(incoming); slots.release() }
        }
    }

    private fun serve(client: Socket) {
        var remote: Socket? = null
        try {
            client.soTimeout = 15_000
            client.tcpNoDelay = true
            val destination = destination(client)
            remote = connect(destination.first, destination.second)
            val upstream = remote
            client.getOutputStream().apply { write("HTTP/1.1 200 Connection Established\r\n\r\n".toByteArray()); flush() }
            workers.execute {
                try { upstream.getInputStream().copyTo(client.getOutputStream(), 32 * 1024) }
                catch (_: IOException) { /* The TLS owner closed or cancelled its connection. */ }
                finally { release(client); release(upstream) }
            }
            TlsClientHelloFragmenter.forwardFirstRecord(client.getInputStream(), upstream.getOutputStream())
            client.soTimeout = 0
            client.getInputStream().copyTo(upstream.getOutputStream(), 32 * 1024)
        } catch (_: Exception) {
            // Socket closure reaches OkHttp as the original connection failure; no synthetic success.
        } finally {
            release(client)
            remote?.let(::release)
            slots.release()
        }
    }

    private fun destination(client: Socket): Pair<String, Int> {
        val input = client.getInputStream()
        val header = StringBuilder()
        while (!header.endsWith("\r\n\r\n")) {
            require(header.length < 8192) { "CONNECT header is too large" }
            val value = input.read()
            require(value >= 0) { "CONNECT header is incomplete" }
            header.append(value.toChar())
        }
        val lines = header.toString().split("\r\n")
        require(lines.drop(1).any { it.equals("Proxy-Authorization: $authorization", ignoreCase = true) }) {
            "CONNECT is not authorized"
        }
        val request = lines.first().split(' ')
        require(request.size == 3 && request[0] == "CONNECT" && request[2] == "HTTP/1.1")
        val authority = request[1]
        val separator = authority.lastIndexOf(':')
        require(separator > 0)
        val host = authority.substring(0, separator).removeSurrounding("[", "]")
        val port = authority.substring(separator + 1).toInt()
        require(host.isNotBlank() && port in 1..65535)
        return host to port
    }

    private fun connect(host: String, port: Int): Socket {
        var last: IOException? = null
        for (address in dns.lookup(host)) {
            val socket = Socket()
            sockets.add(socket)
            try {
                socket.tcpNoDelay = true
                socket.connect(InetSocketAddress(address, port), 4000)
                return socket
            } catch (failure: IOException) { last = failure; release(socket) }
        }
        throw last ?: IOException("No address for $host")
    }

    private fun release(socket: Socket) {
        sockets.remove(socket)
        runCatching { socket.close() }
    }

    override fun close() {
        server.close()
        sockets.toList().forEach(::release)
        workers.shutdownNow()
    }
}
