package ml.melun.mangaview.data.network

import java.io.Closeable
import java.net.InetSocketAddress

/** Authenticated loopback transport for an isolated WebView process; Chromium still owns TLS. */
class BrowserTlsRelay : Closeable {
    private val dns = EncryptedSourceDns()
    private val relay = LocalTlsRelay(dns, basicAuthentication = true)
    private val address = relay.proxy.address() as InetSocketAddress
    val proxyUrl: String = "http://127.0.0.1:${address.port}"

    fun credentials(host: String, realm: String): Pair<String, String>? =
        if (host in setOf("127.0.0.1", "127.0.0.1:${address.port}") && realm == relay.realm)
            relay.username to relay.password else null

    override fun close() {
        try { relay.close() } finally { dns.close() }
    }
}
