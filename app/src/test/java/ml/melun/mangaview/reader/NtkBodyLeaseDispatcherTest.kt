package ml.melun.mangaview.reader

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class NtkBodyLeaseDispatcherTest {
    @Test
    fun exactlyThreePagesCanOpenAndEachPageHasOneLease() {
        val events = LinkedBlockingQueue<NtkBodyLeaseDispatcher.Event>()
        val entered = CountDownLatch(3)
        val releaseOpen = CountDownLatch(1)
        val released = AtomicInteger(0)
        val fixtures = fixtures(4, entered, releaseOpen, released)
        val dispatcher = NtkBodyLeaseDispatcher(
            eventSink = events::offer,
            setCurrentThreadPriority = {}
        )

        repeat(3) { page ->
            assertEquals(
                NtkBodyLeaseDispatcher.OfferResult.ACCEPTED,
                dispatcher.open(fixtures[page].request)
            )
        }
        assertEquals(
            NtkBodyLeaseDispatcher.OfferResult.DUPLICATE_PAGE,
            dispatcher.open(fixtures[0].request.copy(requestId = 99L))
        )
        assertEquals(
            NtkBodyLeaseDispatcher.OfferResult.CAPACITY_EXHAUSTED,
            dispatcher.open(fixtures[3].request)
        )
        assertTrue(entered.await(2, TimeUnit.SECONDS))
        assertEquals(3, dispatcher.snapshot().openingCount)

        releaseOpen.countDown()
        val opened = ArrayList<NtkBodyLeaseDispatcher.OpenedLease>()
        repeat(3) {
            val event = events.poll(2, TimeUnit.SECONDS)
            assertTrue(event is NtkBodyLeaseDispatcher.Event.LeaseOpened)
            val value = (event as NtkBodyLeaseDispatcher.Event.LeaseOpened).opened
            val accepted = dispatcher.acknowledgeOpened(value.request.requestId, value.leaseId)
            assertNotNull(accepted)
            opened += checkNotNull(accepted)
        }
        val full = dispatcher.snapshot()
        assertEquals(0, full.openingCount)
        assertEquals(3, full.openCount)
        assertEquals(3, full.maxOpeningOrOpen)
        assertEquals(setOf(0, 1, 2), full.activePageIndexes)

        opened.forEach { assertTrue(dispatcher.release(it.leaseId)) }
        assertEquals(3, released.get())
        assertTrue(dispatcher.snapshot().isDrained)
        val drained = CountDownLatch(1)
        dispatcher.shutdown { drained.countDown() }
        assertTrue(drained.await(2, TimeUnit.SECONDS))
        assertEquals(
            NtkBodyLeaseDispatcher.OfferResult.CLOSED,
            dispatcher.open(fixtures[3].request)
        )
    }

    @Test
    fun undeliverableOpenCompletionReleasesTheExactLease() {
        val released = AtomicInteger(0)
        val fixture = fixtures(
            count = 1,
            entered = CountDownLatch(0),
            releaseOpen = CountDownLatch(0),
            released = released
        ).single()
        val dispatcher = NtkBodyLeaseDispatcher(
            eventSink = { false },
            setCurrentThreadPriority = {}
        )

        assertEquals(NtkBodyLeaseDispatcher.OfferResult.ACCEPTED, dispatcher.open(fixture.request))
        val drained = CountDownLatch(1)
        dispatcher.shutdown { drained.countDown() }

        assertTrue(drained.await(3, TimeUnit.SECONDS))
        assertTrue(dispatcher.snapshot().isDrained)
        assertEquals(1, released.get())
        assertEquals(1L, dispatcher.snapshot().undeliverableEvents)
        dispatcher.close()
    }

    private data class Fixture(val request: NtkBodyLeaseDispatcher.OpenRequest)

    private fun fixtures(
        count: Int,
        entered: CountDownLatch,
        releaseOpen: CountDownLatch,
        released: AtomicInteger
    ): List<Fixture> {
        val assets = (0 until count).map { "https://images.example/body-${it + 1}.jpg" }
        val seal = NtkEpisodeManifestSeal.create("/manhwa/lease/fixture", 13L, assets)
        return assets.mapIndexed { pageIndex, asset ->
            val file = File.createTempFile("ntk-body-$pageIndex", ".bin").apply {
                writeBytes(byteArrayOf(1, 2, 3, 4))
            }
            val encodedLength = file.length()
            val authority = NtkSourceMetadataAuthority.createStrict(
                acquisition = NtkMetadataAcquisition.PRIMARY_BODY_TEE,
                responseIdentityDigest = digest("response-$pageIndex"),
                byteWitnessSha256 = digest("witness-$pageIndex"),
                byteWitnessLength = encodedLength,
                encodedLength = encodedLength,
                strongValidatorDigest = digest("validator-$pageIndex"),
                imageFormat = "jpeg"
            )
            val metadata = NtkSourceMetadata.createStrict(
                manifestRevision = seal.revision,
                manifestDigest = seal.digestSha256,
                pageIndex = pageIndex,
                canonicalAsset = asset,
                sourceWidth = 1,
                sourceHeight = 1,
                authority = authority
            )
            val proof = NtkEncodedOriginalProof.createStrict(
                metadata,
                digest("encoded-$pageIndex"),
                encodedLength
            )
            val descriptor = NtkStrictBodyDescriptor(
                descriptorId = pageIndex + 1L,
                sourceKey = metadata.strictSourceKey,
                metadata = metadata,
                proof = proof,
                openLease = {
                    entered.countDown()
                    assertTrue(releaseOpen.await(2, TimeUnit.SECONDS))
                    NtkStrictBodyLease(
                        sourceKey = metadata.strictSourceKey,
                        file = file,
                        sourceWidth = metadata.sourceWidth,
                        sourceHeight = metadata.sourceHeight,
                        metadata = metadata,
                        proof = proof,
                        release = {
                            if (file.delete()) released.incrementAndGet()
                        }
                    )
                }
            )
            Fixture(NtkBodyLeaseDispatcher.OpenRequest(
                requestId = pageIndex + 1L,
                pageIndex = pageIndex,
                canonicalAsset = asset,
                descriptor = descriptor
            ))
        }
    }

    private fun digest(value: String): String = NtkStripDigests.sha256Tokens(value)
}
