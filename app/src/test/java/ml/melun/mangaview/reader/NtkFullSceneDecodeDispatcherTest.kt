package ml.melun.mangaview.reader

import android.os.Process
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class NtkFullSceneDecodeDispatcherTest {
    @Test
    fun threePhysicalNormalLanesDrainByCompletionBoundary() {
        val events = LinkedBlockingQueue<NtkFullSceneDecodeDispatcher.Event>()
        val entered = CountDownLatch(3)
        val release = CountDownLatch(1)
        val priorities = LinkedBlockingQueue<Int>()
        val dispatcher = NtkFullSceneDecodeDispatcher(
            eventSink = events::offer,
            setCurrentThreadPriority = priorities::offer,
            beforeDecode = {
                entered.countDown()
                assertTrue(release.await(2, TimeUnit.SECONDS))
                throw ExpectedDecodeStop()
            }
        )
        val requests = requests(3)

        requests.forEach { assertTrue(dispatcher.start(it)) }
        assertTrue(entered.await(2, TimeUnit.SECONDS))
        val active = dispatcher.snapshot()
        assertEquals(3, active.activeTasks)
        assertEquals(3, active.occupiedLanes)
        assertEquals(3, active.activeMax)
        assertEquals(1L, active.threeWideEntryCount)
        assertFalse(active.isDrained)

        val drained = CountDownLatch(1)
        dispatcher.shutdown { drained.countDown() }
        assertFalse(drained.await(50, TimeUnit.MILLISECONDS))
        release.countDown()

        repeat(3) {
            var failed: NtkFullSceneDecodeDispatcher.Event.Failed? = null
            while (failed == null) {
                when (val event = events.poll(2, TimeUnit.SECONDS)) {
                    is NtkFullSceneDecodeDispatcher.Event.Failed -> failed = event
                    is NtkFullSceneDecodeDispatcher.Event.Started -> Unit
                    else -> throw AssertionError("Missing exact decode failure event: $event")
                }
            }
            assertTrue(failed.error is ExpectedDecodeStop)
        }
        assertTrue(drained.await(2, TimeUnit.SECONDS))
        val terminal = dispatcher.snapshot()
        assertFalse(terminal.accepting)
        assertTrue(terminal.isDrained)
        assertEquals(3, terminal.workerThreadIds.size)
        assertEquals(3L, terminal.normalPriorityTaskStarts)
        assertEquals(0L, terminal.backgroundPriorityTaskStarts)
        assertTrue(terminal.threeWideOverlapNanos > 0L)
        assertEquals(
            List(3) { Process.THREAD_PRIORITY_DEFAULT }.sorted(),
            List(3) { priorities.poll(1, TimeUnit.SECONDS) }.sorted()
        )
    }

    @Test
    fun completionCannotObserveThreeWideBeforeItsStartTimestamp() {
        val events = LinkedBlockingQueue<NtkFullSceneDecodeDispatcher.Event>()
        val firstTwoEnteredDecode = CountDownLatch(2)
        val releaseFirstTwo = CountDownLatch(1)
        val thirdIncremented = CountDownLatch(1)
        val releaseThirdAccounting = CountDownLatch(1)
        val beforeDecodeOrdinal = AtomicInteger(0)
        val dispatcher = NtkFullSceneDecodeDispatcher(
            eventSink = events::offer,
            setCurrentThreadPriority = {},
            beforeDecode = {
                val ordinal = beforeDecodeOrdinal.incrementAndGet()
                if (ordinal <= 2) {
                    firstTwoEnteredDecode.countDown()
                    assertTrue(releaseFirstTwo.await(2, TimeUnit.SECONDS))
                }
                throw ExpectedDecodeStop()
            },
            afterActiveIncrementForTest = { active ->
                if (active == 3) {
                    thirdIncremented.countDown()
                    assertTrue(releaseThirdAccounting.await(2, TimeUnit.SECONDS))
                }
            }
        )
        val requests = requests(3)

        assertTrue(dispatcher.start(requests[0]))
        assertTrue(dispatcher.start(requests[1]))
        assertTrue(firstTwoEnteredDecode.await(2, TimeUnit.SECONDS))
        assertTrue(dispatcher.start(requests[2]))
        assertTrue(thirdIncremented.await(2, TimeUnit.SECONDS))

        val drained = CountDownLatch(1)
        dispatcher.shutdown { drained.countDown() }
        releaseFirstTwo.countDown()
        assertFalse(drained.await(50, TimeUnit.MILLISECONDS))
        releaseThirdAccounting.countDown()

        repeat(3) {
            var failed: NtkFullSceneDecodeDispatcher.Event.Failed? = null
            while (failed == null) {
                when (val event = events.poll(2, TimeUnit.SECONDS)) {
                    is NtkFullSceneDecodeDispatcher.Event.Failed -> failed = event
                    is NtkFullSceneDecodeDispatcher.Event.Started -> Unit
                    else -> throw AssertionError("Missing exact decode failure event: $event")
                }
            }
            assertTrue(failed.error is ExpectedDecodeStop)
        }
        assertTrue(drained.await(2, TimeUnit.SECONDS))
        val terminal = dispatcher.snapshot()
        assertTrue(terminal.isDrained)
        assertEquals(1L, terminal.threeWideEntryCount)
        assertTrue(terminal.threeWideOverlapNanos > 0L)
    }

    @Test
    fun initialCohortGuaranteesThreeWideEntryBeforeFastFailure() {
        val events = LinkedBlockingQueue<NtkFullSceneDecodeDispatcher.Event>()
        val dispatcher = NtkFullSceneDecodeDispatcher(
            eventSink = events::offer,
            setCurrentThreadPriority = {},
            beforeDecode = { throw ExpectedDecodeStop() }
        )

        assertTrue(dispatcher.startInitialThreeWideCohort(requests(3)))
        val drained = CountDownLatch(1)
        dispatcher.shutdown { drained.countDown() }

        repeat(3) {
            var failed: NtkFullSceneDecodeDispatcher.Event.Failed? = null
            while (failed == null) {
                when (val event = events.poll(2, TimeUnit.SECONDS)) {
                    is NtkFullSceneDecodeDispatcher.Event.Failed -> failed = event
                    is NtkFullSceneDecodeDispatcher.Event.Started -> Unit
                    else -> throw AssertionError("Missing cohort decode failure event: $event")
                }
            }
            assertTrue(failed.error is ExpectedDecodeStop)
        }
        assertTrue(drained.await(2, TimeUnit.SECONDS))
        val terminal = dispatcher.snapshot()
        assertEquals(3, terminal.activeMax)
        assertEquals(1L, terminal.threeWideEntryCount)
        assertTrue(terminal.threeWideOverlapNanos > 0L)
        assertEquals(3L, terminal.normalPriorityTaskStarts)
        assertEquals(0L, terminal.backgroundPriorityTaskStarts)
    }

    private fun requests(count: Int): List<NtkFullSceneDecodeDispatcher.Request> {
        val episode = NtkEpisodeToken(77L)
        val assets = (0 until count).map { "https://images.example/full-scene-$it.jpg" }
        val seal = NtkEpisodeManifestSeal.create("/manhwa/full-scene", 5L, assets)
        return assets.mapIndexed { pageIndex, asset ->
            val encodedLength = 4L
            val metadata = NtkSourceMetadata.createStrict(
                manifestRevision = seal.revision,
                manifestDigest = seal.digestSha256,
                pageIndex = pageIndex,
                canonicalAsset = asset,
                sourceWidth = 1,
                sourceHeight = 1,
                authority = NtkSourceMetadataAuthority.createStrict(
                    acquisition = NtkMetadataAcquisition.PRIMARY_BODY_TEE,
                    responseIdentityDigest = digest("response-$pageIndex"),
                    byteWitnessSha256 = digest("witness-$pageIndex"),
                    byteWitnessLength = encodedLength,
                    encodedLength = encodedLength,
                    strongValidatorDigest = digest("validator-$pageIndex"),
                    imageFormat = "jpeg"
                )
            )
            val encoded = NtkEncodedOriginalProof.createStrict(
                metadata,
                digest("encoded-$pageIndex"),
                encodedLength
            )
            val plan = NtkSourceTileLayout.create(episode, metadata)
            val artifact = NtkPreGeometryPageArtifact.create(plan, metadata, encoded)
            val tile = plan.tiles.single()
            val file = File.createTempFile("ntk-full-scene-$pageIndex", ".bin").apply {
                writeBytes(byteArrayOf(1, 2, 3, 4))
                deleteOnExit()
            }
            NtkFullSceneDecodeDispatcher.Request(
                admission = NtkPreparationAdmissionIdentity(
                    authority = episode.value,
                    key = tile.key,
                    admissionId = pageIndex + 1L,
                    pageArtifactDigest = artifact.artifactDigest
                ),
                leaseId = pageIndex + 1L,
                lease = NtkStrictBodyLease(
                    sourceKey = metadata.strictSourceKey,
                    file = file,
                    sourceWidth = 1,
                    sourceHeight = 1,
                    metadata = metadata,
                    proof = encoded,
                    release = {}
                ),
                plan = tile,
                expectedProof = NtkPreparedOriginalTileProof.create(artifact, tile)
            )
        }
    }

    private class ExpectedDecodeStop : RuntimeException()

    private fun digest(value: String): String = NtkStripDigests.sha256Tokens(value)
}
