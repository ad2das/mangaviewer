package ml.melun.mangaview.data.engine

import java.io.File
import java.nio.file.Files
import java.util.Base64
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import ml.melun.mangaview.core.EpisodeId
import ml.melun.mangaview.core.PageId
import ml.melun.mangaview.core.SeriesId
import ml.melun.mangaview.core.SourceId
import ml.melun.mangaview.engine.api.EnginePositionPort
import ml.melun.mangaview.engine.api.SourceAnchor
import ml.melun.mangaview.engine.api.WorkCoordinatorPort
import ml.melun.mangaview.engine.api.WorkKey
import ml.melun.mangaview.engine.api.WorkPriority
import ml.melun.mangaview.engine.api.WorkRequest
import ml.melun.mangaview.engine.work.WorkCoordinator
import ml.melun.mangaview.source.OpenedPage
import ml.melun.mangaview.source.PageByteStream
import org.junit.Test

/**
 * Reported JVM benchmark (never asserted): before/after evidence for the storage admission split.
 * Page bodies are real 1.5 MB files (1x1 PNG header + padding) so every cold lookup pays a real
 * SHA-256 over the requested size, and the fake package fsyncs cost [FSYNC_MILLIS] each, matching the
 * device's p50 publish op. Run plain and with STORAGE_BENCH_LEGACY=1 and paste both outputs into the
 * report; the numbers are the decision input for the final storageRead limit.
 */
class StorageAdmissionBenchmarkTest {
    @Test fun storageAdmissionBenchmark() = runBlocking {
        val root = Files.createTempDirectory("storage-bench").toFile()
        val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
        try {
            val store = EngineRawStorage(
                root, MemoryIndex(), Dispatchers.IO, BenchPositions,
                FsyncLatencyOps(FSYNC_MILLIS), System::currentTimeMillis, {},
            )
            val corpus = List(16) { benchId(it) }
            corpus.forEach { store.publish(store.prepare(it, REVISION, BenchStream(BENCH_PAYLOAD).opened())).close() }
            println("[storage-bench] legacySingleLane=${StorageBenchmarkAdapter.isLegacy()} " +
                "fsyncMillis=$FSYNC_MILLIS payloadBytes=${BENCH_PAYLOAD.size}")

            val laneCoordinator = WorkCoordinator(scope, StorageBenchmarkAdapter.limits(4))
            lookupsWhilePublishesStream(laneCoordinator, store)
            publishThroughput(scope, laneCoordinator, store)
            laneCoordinator.close()

            visibleLookupScenario(scope, store)
        } finally {
            scope.cancel()
            root.deleteRecursively()
        }
    }

    /** (a) 32 lookups race 8 streaming publishes; report each lookup's submit-to-result latency. */
    private suspend fun lookupsWhilePublishesStream(coordinator: WorkCoordinatorPort, store: EngineRawStorage) {
        val staged = List(8) { index ->
            store.prepare(benchId(100 + index), REVISION, BenchStream(BENCH_PAYLOAD).opened())
        }
        val sequence = AtomicInteger()
        val publishes = staged.map { prepared ->
            coordinator.submit(request(sequence, "stream-publish", StorageBenchmarkAdapter.publishDomain()) {
                store.publish(prepared).close()
                "ok"
            })
        }
        val corpus = List(16) { benchId(it) }
        val starts = LongArray(32)
        val completions = LongArray(32)
        val lookups = (0 until 32).map { index ->
            val target = corpus[index % corpus.size]
            starts[index] = System.nanoTime()
            coordinator.submit(request(sequence, "stream-lookup", StorageBenchmarkAdapter.lookupDomain()) {
                store.find(target, REVISION)?.close()
                completions[index] = System.nanoTime()
                "ok"
            })
        }
        publishes.forEach { it.await(); it.close(); it.awaitReleased() }
        val latencies = ArrayList<Double>(lookups.size)
        lookups.forEachIndexed { index, subscription ->
            subscription.await()
            latencies += (completions[index] - starts[index]) / 1e6
            subscription.close()
            subscription.awaitReleased()
        }
        latencies.sort()
        println(
            "[storage-bench] (a) lookupsWhilePublishesStream n=${latencies.size} " +
                "p50=%.1fms p90=%.1fms max=%.1fms".format(
                    percentile(latencies, 0.50), percentile(latencies, 0.90), latencies.last(),
                ),
        )
    }

    /** (b) 32 fresh pages at 1/4/8 concurrent publishers; report wall time and pages/s. */
    private suspend fun publishThroughput(scope: CoroutineScope, coordinator: WorkCoordinatorPort, store: EngineRawStorage) {
        for (concurrency in intArrayOf(1, 4, 8)) {
            val pages = ConcurrentLinkedQueue(
                List(32) { index ->
                    store.prepare(benchId(1_000 + concurrency * 100 + index), REVISION, BenchStream(BENCH_PAYLOAD).opened())
                },
            )
            val sequence = AtomicInteger()
            val startedAt = System.nanoTime()
            List(concurrency) {
                scope.async {
                    while (true) {
                        val prepared = pages.poll() ?: break
                        val subscription = coordinator.submit(request(sequence, "throughput", StorageBenchmarkAdapter.publishDomain()) {
                            store.publish(prepared).close()
                            "ok"
                        })
                        try {
                            subscription.await()
                        } finally {
                            subscription.close()
                            subscription.awaitReleased()
                        }
                    }
                }
            }.awaitAll()
            val elapsedMillis = (System.nanoTime() - startedAt) / 1e6
            println(
                "[storage-bench] (b) publishThroughput publishers=$concurrency pages=32 " +
                    "wallMs=%.1f pagesPerSec=%.1f".format(elapsedMillis, 32 / (elapsedMillis / 1000.0)),
            )
        }
    }

    /** (c) one visible lookup behind 12 horizon lookups (4 in flight) on a cold 1.5 MB corpus. */
    private suspend fun visibleLookupScenario(scope: CoroutineScope, store: EngineRawStorage) {
        for (storageRead in intArrayOf(1, 2, 4)) {
            val prepared = List(13) { index ->
                store.prepare(benchId(3_000 + storageRead * 100 + index), REVISION, BenchStream(BENCH_PAYLOAD).opened())
            }
            prepared.forEach { store.publish(it).close() }
            val ids = prepared.map { it.page.pageId }
            val coordinator = WorkCoordinator(scope, StorageBenchmarkAdapter.limits(storageRead))
            val entered = AtomicInteger()
            val release = CompletableDeferred<Unit>()
            val sequence = AtomicInteger()
            val horizon = (0 until 12).map { index ->
                val target = ids[index]
                coordinator.submit(request(sequence, "horizon", StorageBenchmarkAdapter.lookupDomain(), WorkPriority.NEXT_IMAGE) {
                    if (entered.incrementAndGet() <= 4) release.await()
                    store.find(target, REVISION)?.close()
                    "ok"
                })
            }
            val width = StorageBenchmarkAdapter.lookupWidth(storageRead)
            while (entered.get() < minOf(4, width)) delay(1)
            val visibleStart = System.nanoTime()
            val visible = coordinator.submit(
                request(sequence, "visible", StorageBenchmarkAdapter.lookupDomain(), WorkPriority.FOCUS) {
                    store.find(ids[12], REVISION)?.close()
                    "ok"
                },
            )
            release.complete(Unit)
            visible.await()
            val visibleMillis = (System.nanoTime() - visibleStart) / 1e6
            println(
                "[storage-bench] (c) visibleLookup storageRead=$storageRead inFlight=${entered.get()} " +
                    "latencyMs=%.1f".format(visibleMillis),
            )
            (horizon + visible).forEach { it.close(); it.awaitReleased() }
            coordinator.close()
        }
    }

    private fun percentile(sorted: List<Double>, fraction: Double): Double {
        if (sorted.isEmpty()) return Double.NaN
        val index = ((sorted.size - 1) * fraction).toInt()
        return sorted[index]
    }

    private fun request(
        sequence: AtomicInteger,
        tag: String,
        domain: ml.melun.mangaview.engine.api.WorkDomain,
        priority: WorkPriority = WorkPriority.VISIBLE,
        execute: suspend () -> String,
    ) = WorkRequest(
        WorkKey("storage-bench", "$tag-${sequence.incrementAndGet()}", "op", REVISION, String::class.java),
        domain, priority, execute = { execute() },
    )

    private fun benchId(index: Int) = PageId.at(EpisodeId(SeriesId(SourceId("wfwf"), "30001"), "9"), index)

    private class FsyncLatencyOps(private val fsyncMillis: Long) : EngineFilePublication {
        private val inner = LocalFileOps()
        override fun syncFile(file: File) {
            Thread.sleep(fsyncMillis)
            inner.syncFile(file)
        }
        override fun rename(staging: File, destination: File) = inner.rename(staging, destination)
        override fun syncDirectory(directory: File) {
            Thread.sleep(fsyncMillis)
            inner.syncDirectory(directory)
        }
    }

    private object BenchPositions : EnginePositionPort {
        override suspend fun save(anchor: SourceAnchor, legacyScreenOffsetUnits: Long) = Unit
        override suspend fun load(episodeId: EpisodeId): SourceAnchor? = null
    }

    private class BenchStream(private val payload: ByteArray) : PageByteStream {
        private var cursor = 0
        override suspend fun readAtMost(destination: ByteArray, offset: Int, byteCount: Int): Int {
            if (cursor == payload.size) return -1
            val count = minOf(byteCount, payload.size - cursor)
            payload.copyInto(destination, offset, cursor, cursor + count)
            cursor += count
            return count
        }
        override fun close() = Unit
        fun opened() = OpenedPage(this, payload.size.toLong(), "image/png", null, null)
    }

    private companion object {
        const val FSYNC_MILLIS = 30L
        const val REVISION = "bench"
        private val PNG = Base64.getDecoder().decode(
            "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+jZ1kAAAAASUVORK5CYII=",
        )
        val BENCH_PAYLOAD: ByteArray = ByteArray(1_500_000).also { PNG.copyInto(it) }
    }
}
