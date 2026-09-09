package ml.melun.mangaview.data.network

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.io.DataInputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import javax.net.ssl.SSLPeerUnverifiedException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import ml.melun.mangaview.source.*
import okhttp3.Dns
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.tls.HandshakeCertificates
import okhttp3.tls.HeldCertificate
import org.junit.Assert.*
import org.junit.Test

class SniRecoveryTransportTest {
    @Test fun browserProxyAuthenticatesBeforeOpeningTlsAndPreservesPostBytes() = runBlocking {
        val certificate = HeldCertificate.Builder().addSubjectAlternativeName("blocked.test").build()
        val serverTrust = HandshakeCertificates.Builder().heldCertificate(certificate).build()
        val clientTrust = HandshakeCertificates.Builder().addTrustedCertificate(certificate.certificate).build()
        val server = MockWebServer().apply {
            useHttps(serverTrust.sslSocketFactory(), false)
            protocols = listOf(Protocol.HTTP_1_1)
            enqueue(MockResponse().setBody("provider response"))
            start()
        }
        val filter = HelloFilter(server.port)
        val lookups = AtomicInteger()
        val relay = LocalTlsRelay(object : Dns {
            override fun lookup(hostname: String): List<InetAddress> {
                lookups.incrementAndGet()
                return listOf(InetAddress.getByName("127.0.0.1"))
            }
        }, basicAuthentication = true)
        var challenges = 0
        val client = OkHttpClient.Builder().proxy(relay.proxy)
            .sslSocketFactory(clientTrust.sslSocketFactory(), clientTrust.trustManager)
            .protocols(listOf(Protocol.HTTP_1_1)).callTimeout(5, TimeUnit.SECONDS)
            .proxyAuthenticator { _, response ->
                if (response.header("Proxy-Authenticate") == "OkHttp-Preemptive") return@proxyAuthenticator null
                assertEquals(407, response.code)
                assertEquals(0, lookups.get())
                challenges++
                response.request.newBuilder().header("Proxy-Authorization",
                    okhttp3.Credentials.basic(relay.username, relay.password)).build()
            }.build()
        val transport = OkHttpSourceTransport(client, Dispatchers.IO)
        val payload = "{\"token\":\"unchanged\",\"한글\":true}".toByteArray()
        try {
            val response = transport.execute(SourceRequest("https://blocked.test:${filter.port}/api/manifest",
                method = SourceHttpMethod.POST, body = payload, bodyMediaType = "application/json"))
            assertEquals("provider response", response.readBytes(1024).toString(Charsets.UTF_8))
            val received = requireNotNull(server.takeRequest(2, TimeUnit.SECONDS))
            assertArrayEquals(payload, received.body.readByteArray())
            assertNull(received.getHeader("Proxy-Authorization"))
            assertEquals(1, challenges)
            assertEquals(0, filter.blocked.get())
            assertEquals(1, filter.passed.get())
        } finally { transport.close(); relay.close(); filter.close(); server.shutdown() }
    }

    @Test fun aBlockedClientHelloRecoversWithValidTlsAndExactResponseBytes() = runBlocking {
        val certificate = HeldCertificate.Builder().addSubjectAlternativeName("blocked.test").build()
        val serverTrust = HandshakeCertificates.Builder().heldCertificate(certificate).build()
        val clientTrust = HandshakeCertificates.Builder().addTrustedCertificate(certificate.certificate).build()
        val server = MockWebServer()
        server.useHttps(serverTrust.sslSocketFactory(), false)
        server.protocols = listOf(Protocol.HTTP_1_1)
        val payload = "exact response bytes · 원본 유지\n".repeat(2048)
        server.enqueue(MockResponse().setBody(payload))
        server.start()
        val filter = HelloFilter(server.port)
        val relay = LocalTlsRelay(object : Dns { override fun lookup(hostname: String) = listOf(InetAddress.getByName("127.0.0.1")) })
        val builder = OkHttpClient.Builder().dns(object : Dns { override fun lookup(hostname: String) = listOf(InetAddress.getByName("127.0.0.1")) })
            .sslSocketFactory(clientTrust.sslSocketFactory(), clientTrust.trustManager)
            .protocols(listOf(Protocol.HTTP_1_1)).retryOnConnectionFailure(false)
            .callTimeout(5, TimeUnit.SECONDS)
        val primary = OkHttpSourceTransport(builder.build(), Dispatchers.IO)
        val fallback = OkHttpSourceTransport(builder.proxy(relay.proxy)
            .proxyAuthenticator { _, response ->
                response.request.newBuilder().header("Proxy-Authorization", relay.authorization).build()
            }.build(), Dispatchers.IO)
        val protected = SniRecoveryTransport(primary, { fallback })
        try {
            val response = protected.execute(SourceRequest("https://blocked.test:${filter.port}/", totalTimeoutMillis = 10_000))
            assertEquals(payload, response.readBytes(512 * 1024).toString(Charsets.UTF_8))
            assertEquals(1, filter.blocked.get())
            assertEquals(1, filter.passed.get())
            val request = server.takeRequest(2, TimeUnit.SECONDS)!!
            assertNull("Relay authorization must never reach the remote origin", request.getHeader("Proxy-Authorization"))
        } finally { protected.close(); relay.close(); filter.close(); server.shutdown() }
    }

    @Test fun certificateFailureIsNeverRetriedAsCircumvention() = runBlocking {
        var fallbackCreated = false
        val failure = SSLPeerUnverifiedException("Hostname verification failed")
        val protected = SniRecoveryTransport(SourceTransport { throw failure }, {
            fallbackCreated = true
            error("Must not run")
        })
        try { protected.execute(SourceRequest("https://blocked.test/")); fail("Expected certificate rejection") }
        catch (actual: SSLPeerUnverifiedException) { assertSame(failure, actual) }
        assertFalse(fallbackCreated)
        protected.close()
    }

    @Test fun fragmentationPreservesTheHandshakeTranscriptAndAllFollowingBytes() {
        val host = "blocked.test".toByteArray()
        val body = ByteArrayOutputStream().apply {
            write(byteArrayOf(1, 0, 0, 0, 3, 3)); write(ByteArray(32))
            write(0); write(byteArrayOf(0, 2, 0x13, 1)); write(byteArrayOf(1, 0))
            write(byteArrayOf(0, (9 + host.size).toByte()))
            write(byteArrayOf(0, 0, 0, (5 + host.size).toByte(), 0, (3 + host.size).toByte(), 0, 0, host.size.toByte()))
            write(host)
        }.toByteArray()
        val input = ByteArrayInputStream(byteArrayOf(22, 3, 1, (body.size ushr 8).toByte(), body.size.toByte()) + body + byteArrayOf(7, 8, 9))
        val output = ByteArrayOutputStream()
        TlsClientHelloFragmenter.forwardFirstRecord(input, output)
        val records = DataInputStream(ByteArrayInputStream(output.toByteArray()))
        val reconstructed = ByteArrayOutputStream()
        repeat(2) {
            assertEquals(22, records.readUnsignedByte()); assertEquals(3, records.readUnsignedByte()); assertEquals(1, records.readUnsignedByte())
            val bytes = ByteArray(records.readUnsignedShort()); records.readFully(bytes); reconstructed.write(bytes)
            assertFalse(bytes.toString(Charsets.US_ASCII).contains("blocked.test"))
        }
        assertArrayEquals(body, reconstructed.toByteArray())
        assertArrayEquals(byteArrayOf(7, 8, 9), input.readBytes())
        assertEquals(-1, records.read())
    }
}

/** Simulates an SNI blocker that rejects a hostname present in one ClientHello record. */
private class HelloFilter(private val tlsPort: Int) : Closeable {
    private val server = ServerSocket(0, 16, InetAddress.getByName("127.0.0.1"))
    private val workers = Executors.newCachedThreadPool()
    private val sockets = ConcurrentHashMap.newKeySet<Socket>()
    val port = server.localPort
    val blocked = AtomicInteger()
    val passed = AtomicInteger()
    init {
        workers.execute {
            while (!server.isClosed) {
                val socket = try { server.accept() } catch (_: Exception) { break }
                sockets.add(socket)
                workers.execute { forward(socket) }
            }
        }
    }
    private fun forward(client: Socket) {
        var remote: Socket? = null
        try {
            val input = DataInputStream(client.getInputStream())
            val header = ByteArray(5); input.readFully(header)
            val length = ((header[3].toInt() and 255) shl 8) or (header[4].toInt() and 255)
            val body = ByteArray(length); input.readFully(body)
            if (body.toString(Charsets.US_ASCII).contains("blocked.test")) { blocked.incrementAndGet(); return }
            passed.incrementAndGet()
            remote = Socket("127.0.0.1", tlsPort)
            val upstream = remote
            sockets.add(upstream)
            upstream.getOutputStream().apply { write(header); write(body); flush() }
            workers.execute {
                try { upstream.getInputStream().copyTo(client.getOutputStream()) }
                catch (_: Exception) { }
                finally { release(upstream); release(client) }
            }
            input.copyTo(upstream.getOutputStream())
        } catch (_: Exception) { }
        finally { release(client); remote?.let(::release) }
    }
    private fun release(socket: Socket) { sockets.remove(socket); runCatching { socket.close() } }
    override fun close() { server.close(); sockets.toList().forEach(::release); workers.shutdownNow() }
}
