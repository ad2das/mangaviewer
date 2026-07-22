package ml.melun.mangaview.reader

import android.os.SystemClock
import okhttp3.Dns
import java.net.InetAddress
import java.net.UnknownHostException
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutionException
import java.util.concurrent.FutureTask

/**
 * Process-local DNS single-flight for the finite click-owned image wave.
 *
 * The first post-click caller performs the real lookup. Concurrent clients for the same host join
 * that result instead of submitting dozens of identical requests to Android's resolver. A cold
 * process always starts with an empty map; this class never performs eager or pre-viewer lookup.
 */
internal class NtkSingleFlightDns(
    private val delegate: Dns,
    private val resultTtlMs: Long = 5L * 60L * 1_000L,
    private val clockMs: () -> Long = SystemClock::elapsedRealtime,
) : Dns {
    private data class Flight(
        val createdAtMs: Long,
        val task: FutureTask<List<InetAddress>>,
    )

    private val flights = ConcurrentHashMap<String, Flight>()

    init {
        require(resultTtlMs > 0L)
    }

    override fun lookup(hostname: String): List<InetAddress> {
        val key = hostname.trim().lowercase(Locale.ROOT)
        if (key.isEmpty()) throw UnknownHostException(hostname)
        while (true) {
            val now = clockMs()
            val current = flights[key]
            if (current != null) {
                if (now - current.createdAtMs < resultTtlMs) return await(key, current)
                flights.remove(key, current)
                continue
            }
            val task = FutureTask {
                delegate.lookup(hostname).toList().also { addresses ->
                    if (addresses.isEmpty()) throw UnknownHostException(hostname)
                }
            }
            val proposed = Flight(now, task)
            val winner = flights.putIfAbsent(key, proposed) ?: proposed
            winner.task.run()
            return await(key, winner)
        }
    }

    private fun await(key: String, flight: Flight): List<InetAddress> = try {
        flight.task.get().toList()
    } catch (failure: InterruptedException) {
        Thread.currentThread().interrupt()
        flights.remove(key, flight)
        throw UnknownHostException(key).apply { initCause(failure) }
    } catch (failure: ExecutionException) {
        flights.remove(key, flight)
        val cause = failure.cause ?: failure
        if (cause is UnknownHostException) throw cause
        throw UnknownHostException(key).apply { initCause(cause) }
    }
}
