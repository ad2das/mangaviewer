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

    private fun create(
        cookieJar: CookieJar,
        protocols: List<Protocol>,
    ): OkHttpSourceTransport {
        val dispatcher = Dispatcher().apply {
            // The engine coordinator owns admission. Its already admitted requests must not
            // wait behind a smaller transport queue inherited from the legacy page reader.
            maxRequests = parallelism + 2
            maxRequestsPerHost = parallelism
        }
        val dns = AndroidIpv4FirstDns(fixedAddressOffset = 0)
        val client = OkHttpClient.Builder()
            .dispatcher(dispatcher)
            .dns(dns)
            .cookieJar(cookieJar)
            .connectionPool(ConnectionPool(parallelism, 5L, TimeUnit.MINUTES))
            .protocols(protocols)
            .connectTimeout(10L, TimeUnit.SECONDS)
            .readTimeout(30L, TimeUnit.SECONDS)
            .writeTimeout(30L, TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)
            .build()
        return OkHttpSourceTransport(
            client,
            ioDispatcher,
            routeDns = { offset -> AndroidIpv4FirstDns(fixedAddressOffset = offset) },
        )
    }
}
