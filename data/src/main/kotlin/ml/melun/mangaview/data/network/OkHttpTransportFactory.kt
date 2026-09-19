package ml.melun.mangaview.data.network

import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CoroutineDispatcher
import okhttp3.ConnectionPool
import okhttp3.CookieJar
import okhttp3.Dispatcher
import okhttp3.OkHttpClient
import okhttp3.Protocol

class OkHttpTransportFactory(
    private val ioDispatcher: CoroutineDispatcher,
    private val parallelism: Int = 6,
) {
    init { require(parallelism in 1..32) }

    fun create(cookieJar: CookieJar = CookieJar.NO_COOKIES): OkHttpSourceTransport =
        create(cookieJar, listOf(Protocol.HTTP_2, Protocol.HTTP_1_1))

    fun protect(
        primary: ml.melun.mangaview.source.SourceTransport,
        cookieJar: CookieJar = CookieJar.NO_COOKIES,
        headers: Map<String, String> = emptyMap(),
    ): SniRecoveryTransport =
        SniRecoveryTransport(
            primary,
            createRecovery = { createRecovery(cookieJar, headers) },
            sharedRecovery = true,
        )

    private fun createRecovery(
        cookieJar: CookieJar,
        headers: Map<String, String> = emptyMap(),
    ): ml.melun.mangaview.source.SourceTransport {
        val dns = EncryptedSourceDns()
        val relay = LocalTlsRelay(dns)
        val dispatcher = Dispatcher().apply { maxRequestsPerHost = 16 }
        val builder = OkHttpClient.Builder()
            .dispatcher(dispatcher)
            .proxy(relay.proxy)
            .proxyAuthenticator { _, response ->
                if (response.request.header("Proxy-Authorization") != null) null
                else response.request.newBuilder().header("Proxy-Authorization", relay.authorization).build()
            }
            .cookieJar(cookieJar)
            .connectionPool(ConnectionPool(parallelism, 5L, TimeUnit.MINUTES))
            .connectTimeout(10L, TimeUnit.SECONDS)
            .readTimeout(30L, TimeUnit.SECONDS)
            .writeTimeout(30L, TimeUnit.SECONDS)
        builder.addBrowserIdentity(headers)
        val client = builder.build()
        val transport = OkHttpSourceTransport(client, ioDispatcher)
        return object : ml.melun.mangaview.source.SourceTransport by transport, java.io.Closeable {
            override fun close() { transport.close(); relay.close(); dns.close() }
        }
    }

    /**
     * A client for provider image CDNs whose chain the platform cannot build. It is never used for
     * a host outside [ProviderImageTrust]; [ProviderImageTransport] enforces that routing.
     */
    fun createForProviderImages(cookieJar: CookieJar = CookieJar.NO_COOKIES): OkHttpSourceTransport {
        val dispatcher = Dispatcher().apply { maxRequestsPerHost = 16 }
        val client = OkHttpClient.Builder()
            .dispatcher(dispatcher)
            .dns(ProviderImageDns())
            .cookieJar(cookieJar)
            .sslSocketFactory(ProviderImageTrust.socketFactory(), ProviderImageTrust.trustManager())
            .connectionPool(ConnectionPool(parallelism, 5L, TimeUnit.MINUTES))
            .connectTimeout(10L, TimeUnit.SECONDS)
            .readTimeout(30L, TimeUnit.SECONDS)
            .writeTimeout(30L, TimeUnit.SECONDS)
            .build()
        return OkHttpSourceTransport(client, ioDispatcher)
    }

    /**
     * A browser-identity client for origins whose clearance cookie is bound to the client hints
     * the solving WebView sent; OkHttp adds none of them on its own.
     */
    fun createBrowserLike(cookieJar: CookieJar, clientHints: String): OkHttpSourceTransport =
        create(cookieJar, listOf(Protocol.HTTP_2, Protocol.HTTP_1_1), browserHeaders(clientHints))

    private fun create(
        cookieJar: CookieJar,
        protocols: List<Protocol>,
        headers: Map<String, String> = emptyMap(),
    ): OkHttpSourceTransport {
        val dispatcher = Dispatcher().apply {
            // The engine coordinator owns admission. Its already admitted requests must not
            // wait behind a smaller transport queue inherited from the legacy page reader.
            maxRequests = parallelism + 2
            maxRequestsPerHost = parallelism
        }
        val dns = AndroidIpv4FirstDns(fixedAddressOffset = 0)
        val builder = OkHttpClient.Builder()
            .dispatcher(dispatcher)
            .dns(dns)
            .cookieJar(cookieJar)
            .connectionPool(ConnectionPool(parallelism, 5L, TimeUnit.MINUTES))
            .protocols(protocols)
            .connectTimeout(10L, TimeUnit.SECONDS)
            .readTimeout(30L, TimeUnit.SECONDS)
            .writeTimeout(30L, TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)
        builder.addBrowserIdentity(headers)
        val client = builder.build()
        return OkHttpSourceTransport(
            client,
            ioDispatcher,
            routeDns = { offset -> AndroidIpv4FirstDns(fixedAddressOffset = offset) },
        )
    }

    companion object {
        /**
         * The client hints the solving WebView sends. OkHttp adds none of them, and a clearance
         * cookie that was issued to that WebView can be refused when they are missing.
         */
        fun browserHeaders(clientHints: String): Map<String, String> = linkedMapOf(
            "Accept" to "text/html,application/xhtml+xml,*/*;q=0.8",
            "Accept-Language" to "ko-KR,ko;q=0.9,en-US;q=0.8,en;q=0.7",
            "sec-ch-ua" to clientHints,
            "sec-ch-ua-mobile" to "?1",
            "sec-ch-ua-platform" to "\"Android\"",
            "upgrade-insecure-requests" to "1",
        )
    }
}

/** Fills in headers a request does not carry itself; source-set values always win. */
private fun OkHttpClient.Builder.addBrowserIdentity(headers: Map<String, String>): OkHttpClient.Builder = apply {
    if (headers.isEmpty()) return@apply
    addInterceptor { chain ->
        val request = chain.request()
        val enriched = request.newBuilder().apply {
            headers.forEach { (name, value) ->
                if (request.header(name) == null) header(name, value)
            }
        }
        chain.proceed(enriched.build())
    }
}
