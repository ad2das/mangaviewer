package ml.melun.mangaview.reader;

import android.graphics.Bitmap;

import androidx.test.ext.junit.runners.AndroidJUnit4;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;

import ml.melun.mangaview.mangaview.MTitle;
import ml.melun.mangaview.mangaview.Manga;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

@RunWith(AndroidJUnit4.class)
public class ReaderPreparedStoreLeaseInstrumentedTest {
    private final List<Bitmap> ownedBitmaps = new ArrayList<>();

    @Before
    public void setUp() {
        ReaderPreparedStore.clearAll();
    }

    @After
    public void tearDown() {
        ReaderPreparedStore.clearAll();
        for (Bitmap bitmap : ownedBitmaps) {
            if (!bitmap.isRecycled()) bitmap.recycle();
        }
        ownedBitmaps.clear();
    }

    @Test
    public void completeWindowIsClaimedOnceAndPreservesBitmapIdentityUntilClose() {
        String key = "lease:complete:0:720";
        Manga manga = manga(101);
        Bitmap first = bitmap();
        Bitmap second = bitmap();
        ReaderPreparedStore.Entry entry = readyEntry(key, manga, 720, first, second);

        ReaderPreparedStore.PreparedLease lease =
                ReaderPreparedStore.claimWindowReady(key, manga, 720);

        assertNotNull(lease);
        assertSame(entry, ReaderPreparedStore.get(key));
        assertEquals(720, lease.getEntryRequestedWidth());
        assertEquals(ReaderPreparedStore.Status.WINDOW_READY, lease.getSnapshot().getStatus());
        assertEquals(Arrays.asList("https://example.test/0", "https://example.test/1"),
                lease.getSnapshot().getImages());
        assertSame(manga, lease.getSnapshot().getManga());
        assertSame(first, lease.getSnapshot().getBitmaps().get(0));
        assertSame(second, lease.getSnapshot().getBitmaps().get(1));
        assertSnapshotCollectionsAreUnmodifiable(lease.getSnapshot(), first);
        assertNull(ReaderPreparedStore.claimWindowReady(key, manga, 720));

        lease.close();
        lease.close();

        assertNull(ReaderPreparedStore.get(key));
        assertNull(ReaderPreparedStore.claimWindowReady(key, manga, 720));
        assertFalse(first.isRecycled());
        assertFalse(second.isRecycled());
    }

    @Test
    public void rejectedClaimsDoNotConsumeEntry() {
        String key = "lease:mismatch:0:720";
        Manga manga = manga(102);
        Bitmap first = bitmap();
        Bitmap second = bitmap();
        readyEntry(key, manga, 720, first, second);

        assertNull(ReaderPreparedStore.claimWindowReady("lease:wrong:0:720", manga, 720));
        assertNull(ReaderPreparedStore.claimWindowReady(key, manga(999), 720));
        assertNull(ReaderPreparedStore.claimWindowReady(key, manga, 1080));

        ReaderPreparedStore.PreparedLease lease =
                ReaderPreparedStore.claimWindowReady(key, manga, 720);
        assertNotNull(lease);
        lease.close();
    }

    @Test
    public void activeLeaseDefersClearAndRemovalUntilClose() {
        String key = "lease:deferred-removal:0:720";
        Manga manga = manga(106);
        Bitmap first = bitmap();
        Bitmap second = bitmap();
        ReaderPreparedStore.Entry entry = readyEntry(key, manga, 720, first, second);
        ReaderPreparedStore.PreparedLease lease =
                ReaderPreparedStore.claimWindowReady(key, manga, 720);
        assertNotNull(lease);

        ReaderPreparedStore.clearBitmaps(key);
        ReaderPreparedStore.remove(key);
        ReaderPreparedStore.clearAll();

        assertSame(entry, ReaderPreparedStore.get(key));
        assertSame(first, entry.snapshot().getBitmaps().get(0));
        assertSame(second, entry.snapshot().getBitmaps().get(1));
        assertSame(first, lease.getSnapshot().getBitmaps().get(0));
        assertSame(second, lease.getSnapshot().getBitmaps().get(1));

        lease.close();

        assertNull(ReaderPreparedStore.get(key));
        assertFalse(first.isRecycled());
        assertFalse(second.isRecycled());
    }

    @Test
    public void unspecifiedStoredWidthAcceptsRequestedWidthAndReportsActualStoredWidth() {
        String key = "lease:unspecified-width:0:0";
        Manga manga = manga(107);
        Bitmap first = bitmap();
        Bitmap second = bitmap();
        readyEntry(key, manga, 0, first, second);

        ReaderPreparedStore.PreparedLease lease =
                ReaderPreparedStore.claimWindowReady(key, manga, 1080);

        assertNotNull(lease);
        assertEquals(0, lease.getEntryRequestedWidth());
        assertEquals(0, lease.getSnapshot().getRequestedWidth());
        lease.close();
    }

    @Test
    public void incompleteOrRecycledWindowCannotBeClaimed() {
        Manga missingPageManga = manga(103);
        String missingPageKey = "lease:missing:0:720";
        Bitmap first = bitmap();
        ReaderPreparedStore.Entry missingPage = ReaderPreparedStore.createOrGet(
                missingPageKey, missingPageManga, null, 0, 720);
        missingPage.setImages(Arrays.asList(
                "https://example.test/0", "https://example.test/1"), 0);
        missingPage.putBitmap(0, first, true, true);

        assertNull(ReaderPreparedStore.claimWindowReady(missingPageKey, missingPageManga, 720));
        assertSame(missingPage, ReaderPreparedStore.get(missingPageKey));

        Manga recycledManga = manga(104);
        String recycledKey = "lease:recycled:0:720";
        Bitmap recycled = bitmap();
        recycled.recycle();
        ReaderPreparedStore.Entry recycledEntry = ReaderPreparedStore.createOrGet(
                recycledKey, recycledManga, null, 0, 720);
        recycledEntry.setImages(Arrays.asList("https://example.test/0"), 0);
        recycledEntry.putBitmap(0, recycled, true, true);

        assertNull(ReaderPreparedStore.claimWindowReady(recycledKey, recycledManga, 720));
        assertSame(recycledEntry, ReaderPreparedStore.get(recycledKey));
    }

    @Test
    public void concurrentClaimsHaveExactlyOneWinner() throws Exception {
        String key = "lease:concurrent:0:720";
        Manga manga = manga(105);
        readyEntry(key, manga, 720, bitmap(), bitmap());
        int contenderCount = 8;
        CountDownLatch ready = new CountDownLatch(contenderCount);
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(contenderCount);
        List<Future<ReaderPreparedStore.PreparedLease>> futures = new ArrayList<>();
        try {
            for (int i = 0; i < contenderCount; i++) {
                futures.add(executor.submit(() -> {
                    ready.countDown();
                    start.await();
                    return ReaderPreparedStore.claimWindowReady(key, manga, 720);
                }));
            }
            ready.await();
            start.countDown();

            ReaderPreparedStore.PreparedLease winner = null;
            int winners = 0;
            for (Future<ReaderPreparedStore.PreparedLease> future : futures) {
                ReaderPreparedStore.PreparedLease candidate = future.get();
                if (candidate != null) {
                    winners++;
                    winner = candidate;
                }
            }
            assertEquals(1, winners);
            assertNotNull(winner);
            winner.close();
            assertNull(ReaderPreparedStore.get(key));
        } finally {
            start.countDown();
            executor.shutdownNow();
        }
    }

    @Test
    public void activeLeaseSurvivesEntryLimitTrimming() {
        String key = "lease:lru-protected:0:720";
        Manga manga = manga(108);
        Bitmap first = bitmap();
        Bitmap second = bitmap();
        ReaderPreparedStore.Entry entry = readyEntry(key, manga, 720, first, second);
        ReaderPreparedStore.PreparedLease lease =
                ReaderPreparedStore.claimWindowReady(key, manga, 720);
        assertNotNull(lease);

        for (int i = 0; i < 30; i++) {
            ReaderPreparedStore.createOrGet(
                    "lease:dummy-" + i + ":0:720",
                    manga(2000 + i),
                    null,
                    0,
                    720);
        }

        assertSame(entry, ReaderPreparedStore.get(key));
        assertSame(first, entry.snapshot().getBitmaps().get(0));
        assertSame(second, entry.snapshot().getBitmaps().get(1));
        assertSame(first, lease.getSnapshot().getBitmaps().get(0));
        assertSame(second, lease.getSnapshot().getBitmaps().get(1));

        lease.close();
        assertNull(ReaderPreparedStore.get(key));
    }

    @Test
    public void launchRunwayRequiresViewportPlusAheadAndCommitConsumesEntry() {
        String key = "runway:coverage:0:100";
        Manga manga = manga(301);
        List<String> images = images(3);
        ReaderPreparedStore.Entry entry = ReaderPreparedStore.createOrGet(
                key, manga, null, 0, 100);
        entry.setImages(images, 0);

        Map<Integer, ReaderPreparedStore.PreparedTilePage> initial = new LinkedHashMap<>();
        initial.put(0, tilePage(100, 200));
        initial.put(1, tilePage(100, 199, 1));
        entry.putDrawableBatch(new LinkedHashMap<>(), initial, false);
        ReaderPreparedStore.LaunchRunwaySpec spec = runwaySpec(100, 200, 0, 50, 150);

        // 150px remain on page zero and page one contributes 199px: one physical pixel short.
        assertNull(ReaderPreparedStore.reserveLaunchRunway(key, manga, images, spec));

        Map<Integer, ReaderPreparedStore.PreparedTilePage> finalPixel = new LinkedHashMap<>();
        finalPixel.put(2, tilePage(100, 1, 2));
        entry.putDrawableBatch(new LinkedHashMap<>(), finalPixel, false);

        ReaderPreparedStore.LaunchRunwayLease lease =
                ReaderPreparedStore.reserveLaunchRunway(key, manga, images, spec);
        assertNotNull(lease);
        assertNotNull(lease.latestSnapshot());
        assertEquals(ReaderPreparedStore.Status.FIRST_BITMAP_READY,
                lease.latestSnapshot().getStatus());
        assertNull(ReaderPreparedStore.reserveLaunchRunway(key, manga, images, spec));
        assertNull(ReaderPreparedStore.claimWindowReady(key, manga, 100));

        assertTrue(lease.commit());
        assertTrue(lease.commit());
        lease.close();
        lease.close();

        assertNull(ReaderPreparedStore.get(key));
        assertAllOwnedBitmapsAlive();
    }

    @Test
    public void uncommittedRunwayCloseKeepsEntryAndAppliesDeferredBitmapClear() {
        String key = "runway:uncommitted:0:100";
        Manga manga = manga(302);
        List<String> images = images(2);
        ReaderPreparedStore.Entry entry = ReaderPreparedStore.createOrGet(
                key, manga, null, 0, 100);
        entry.setImages(images, 0);
        entry.putDrawableBatch(
                new LinkedHashMap<>(),
                singletonTilePage(0, tilePage(100, 400)),
                true);
        ReaderPreparedStore.LaunchRunwaySpec spec = runwaySpec(100, 200, 0, 0, 150);

        ReaderPreparedStore.LaunchRunwayLease lease =
                ReaderPreparedStore.reserveLaunchRunway(key, manga, images, spec);
        assertNotNull(lease);
        ReaderPreparedStore.clearBitmaps(key);
        assertSame(entry, ReaderPreparedStore.get(key));
        assertFalse(lease.latestSnapshot().getTilePages().isEmpty());

        lease.close();

        assertSame(entry, ReaderPreparedStore.get(key));
        assertTrue(entry.snapshot().getTilePages().isEmpty());
        assertAllOwnedBitmapsAlive();

        entry.putDrawableBatch(
                new LinkedHashMap<>(),
                singletonTilePage(0, tilePage(100, 400)),
                false);
        ReaderPreparedStore.LaunchRunwayLease second =
                ReaderPreparedStore.reserveLaunchRunway(key, manga, images, spec);
        assertNotNull(second);
        second.close();
        assertSame(entry, ReaderPreparedStore.get(key));
    }

    @Test
    public void committedRunwayPinsEntryAndReceivesLiveTilePublications() {
        String key = "runway:live:0:100";
        Manga manga = manga(303);
        List<String> images = images(3);
        ReaderPreparedStore.Entry entry = ReaderPreparedStore.createOrGet(
                key, manga, null, 0, 100);
        entry.setImages(images, 0);
        entry.putDrawableBatch(
                new LinkedHashMap<>(),
                singletonTilePage(0, tilePage(100, 400)),
                false);
        ReaderPreparedStore.LaunchRunwayLease lease = ReaderPreparedStore.reserveLaunchRunway(
                key, manga, images, runwaySpec(100, 200, 0, 0, 150));
        assertNotNull(lease);

        AtomicInteger tileCallbacks = new AtomicInteger();
        ReaderPreparedStore.Listener listener = countingTileListener(tileCallbacks);
        ReaderPreparedStore.Snapshot subscribed = lease.subscribe(listener);
        assertNotNull(subscribed);
        assertEquals(1, subscribed.getTilePages().size());

        entry.putDrawableBatch(
                new LinkedHashMap<>(),
                singletonTilePage(1, tilePage(100, 200, 1)),
                false);
        assertEquals(1, tileCallbacks.get());
        assertEquals(2, lease.latestSnapshot().getTilePages().size());

        lease.unsubscribe(listener);
        entry.putDrawableBatch(
                new LinkedHashMap<>(),
                singletonTilePage(2, tilePage(100, 200, 2)),
                false);
        assertEquals(1, tileCallbacks.get());

        AtomicInteger closeUnsubscribeCallbacks = new AtomicInteger();
        assertNotNull(lease.subscribe(countingTileListener(closeUnsubscribeCallbacks)));

        assertTrue(lease.commit());
        ReaderPreparedStore.clearBitmaps(key);
        ReaderPreparedStore.remove(key);
        ReaderPreparedStore.clearAll();
        assertSame(entry, ReaderPreparedStore.get(key));
        assertEquals(3, lease.latestSnapshot().getTilePages().size());

        lease.close();
        assertNull(ReaderPreparedStore.get(key));
        entry.putDrawableBatch(
                new LinkedHashMap<>(),
                singletonTilePage(2, tilePage(100, 200, 2)),
                false);
        assertEquals(0, closeUnsubscribeCallbacks.get());
        assertAllOwnedBitmapsAlive();
    }

    @Test
    public void launchRunwayWatermarkStopsAtFirstSparseHole() {
        String key = "runway:sparse-watermark:0:100";
        Manga manga = manga(1303);
        List<String> images = images(8);
        ReaderPreparedStore.Entry entry = ReaderPreparedStore.createOrGet(
                key, manga, null, 0, 100);
        entry.setImages(images, 0);
        LinkedHashMap<Integer, ReaderPreparedStore.PreparedTilePage> sparse = new LinkedHashMap<>();
        sparse.put(0, tilePage(100, 400, 0));
        sparse.put(7, tilePage(100, 400, 7));
        entry.putDrawableBatch(new LinkedHashMap<>(), sparse, false);

        ReaderPreparedStore.LaunchRunwaySpec spec = runwaySpec(100, 200, 0, 0, 150);
        assertEquals(0, entry.contiguousLaunchRunwayTileLast(spec));

        entry.putDrawableBatch(
                new LinkedHashMap<>(),
                singletonTilePage(1, tilePage(100, 400, 1)),
                false);
        assertEquals(1, entry.contiguousLaunchRunwayTileLast(spec));
    }

    @Test
    public void launchRunwayRejectsNonExactAndNonTileCandidatesWithoutConsumingEntry() {
        String key = "runway:strict:0:100";
        Manga manga = manga(304);
        List<String> images = images(2);
        ReaderPreparedStore.Entry entry = ReaderPreparedStore.createOrGet(
                key, manga, null, 0, 100);
        entry.setImages(images, 0);
        entry.putDrawableBatch(
                new LinkedHashMap<>(),
                singletonTilePage(0, tilePage(100, 400)),
                false);
        ReaderPreparedStore.LaunchRunwaySpec valid = runwaySpec(100, 200, 0, 0, 150);

        assertNull(ReaderPreparedStore.reserveLaunchRunway("runway:strict:0:101", manga, images, valid));
        assertNull(ReaderPreparedStore.reserveLaunchRunway(key, manga(9999), images, valid));
        assertNull(ReaderPreparedStore.reserveLaunchRunway(
                key, manga, Arrays.asList(images.get(0), "https://wrong.test/1"), valid));
        assertNull(ReaderPreparedStore.reserveLaunchRunway(
                key, manga, images, runwaySpec(101, 200, 0, 0, 150)));
        assertNull(ReaderPreparedStore.reserveLaunchRunway(
                key, manga, images, new ReaderPreparedStore.LaunchRunwaySpec(
                        100, 200, 1, 0, 0, 512, 150)));
        assertNull(ReaderPreparedStore.reserveLaunchRunway(
                key, manga, images, new ReaderPreparedStore.LaunchRunwaySpec(
                        100, 200, 0, 0, 1, 512, 150)));
        assertNull(ReaderPreparedStore.reserveLaunchRunway(
                key, manga, images, new ReaderPreparedStore.LaunchRunwaySpec(
                        100, 200, 0, 0, 0, 256, 150)));
        assertNull(ReaderPreparedStore.reserveLaunchRunway(
                key, manga, images, runwaySpec(100, 200, 0, 400, 150)));

        ReaderPreparedStore.LaunchRunwayLease validLease =
                ReaderPreparedStore.reserveLaunchRunway(key, manga, images, valid);
        assertNotNull(validLease);
        validLease.close();
        assertSame(entry, ReaderPreparedStore.get(key));

        String bitmapKey = "runway:bitmap-only:0:100";
        Manga bitmapManga = manga(305);
        ReaderPreparedStore.Entry bitmapEntry = ReaderPreparedStore.createOrGet(
                bitmapKey, bitmapManga, null, 0, 100);
        bitmapEntry.setImages(images, 0);
        bitmapEntry.putBitmap(0, immutableBitmap(100, 400), true, false);
        assertNull(ReaderPreparedStore.reserveLaunchRunway(
                bitmapKey, bitmapManga, images, valid));
        assertSame(bitmapEntry, ReaderPreparedStore.get(bitmapKey));
    }

    @Test
    public void launchRunwayRejectsMutableRecycledGappedAndWrongSpanTiles() {
        ReaderPreparedStore.LaunchRunwaySpec spec = runwaySpec(100, 200, 0, 0, 150);
        assertInvalidTilePageRejected("mutable", 306, mutableTilePage(100, 400), spec);

        Bitmap recycled = immutableBitmap(100, 400);
        recycled.recycle();
        assertInvalidTilePageRejected(
                "recycled",
                307,
                new ReaderPreparedStore.PreparedTilePage(
                        100,
                        400,
                        Arrays.asList(new ReaderTile(0, 400, 100, 400, recycled)),
                        originalProof("https://example.test/0", 100, 400, 1, false)),
                spec);

        assertInvalidTilePageRejected(
                "gap",
                308,
                tilePageWithSpans(100, 600, new int[][]{{0, 512}, {513, 600}}),
                spec);
        assertInvalidTilePageRejected(
                "wrong-span",
                309,
                tilePageWithSpans(100, 600, new int[][]{{0, 500}, {500, 600}}),
                spec);
    }

    @Test
    public void nativeWidthOriginalNarrowerThanViewportRequiresExactImmutableOriginalProof() {
        String key = "runway:native-original:0:1080";
        Manga manga = manga(311);
        List<String> images = images(1);
        ReaderPreparedStore.Entry entry = ReaderPreparedStore.createOrGet(
                key, manga, null, 0, 1080);
        entry.setImages(images, 0);
        entry.putDrawableBatch(
                new LinkedHashMap<>(),
                singletonTilePage(0, tilePage(764, 1200)),
                false);
        ReaderPreparedStore.LaunchRunwaySpec spec = runwaySpec(1080, 1000, 0, 0, 600);

        ReaderPreparedStore.LaunchRunwayLease lease =
                ReaderPreparedStore.reserveLaunchRunway(key, manga, images, spec);
        assertNotNull(lease);
        lease.close();
        assertSame(entry, ReaderPreparedStore.get(key));

        String sampledKey = "runway:sampled:0:1080";
        Manga sampledManga = manga(312);
        ReaderPreparedStore.Entry sampledEntry = ReaderPreparedStore.createOrGet(
                sampledKey, sampledManga, null, 0, 1080);
        sampledEntry.setImages(images, 0);
        Bitmap sampledBitmap = immutableBitmap(382, 256);
        ReaderPreparedStore.PreparedTilePage sampledPage =
                new ReaderPreparedStore.PreparedTilePage(
                        764,
                        512,
                        Arrays.asList(new ReaderTile(0, 512, 764, 512, sampledBitmap)),
                        originalProof("https://example.test/0", 764, 512, 2, false));
        sampledEntry.putDrawableBatch(
                new LinkedHashMap<>(),
                singletonTilePage(0, sampledPage),
                false);
        assertNull(ReaderPreparedStore.reserveLaunchRunway(
                sampledKey,
                sampledManga,
                images,
                runwaySpec(1080, 400, 0, 0, 200)));
        assertSame(sampledEntry, ReaderPreparedStore.get(sampledKey));

        ReaderPreparedStore.PreparedTilePage validPage = tilePage(764, 1200);
        assertInvalidTilePageRejected(
                "missing-proof",
                313,
                new ReaderPreparedStore.PreparedTilePage(
                        764, 1200, validPage.getTiles()),
                spec);
        assertInvalidTilePageRejected(
                "wrong-asset",
                314,
                tilePageWithProof(
                        764,
                        1200,
                        originalProof("https://wrong.test/p001.jpeg", 764, 1200, 1, false)),
                spec);
        assertInvalidTilePageRejected(
                "sample-two-proof",
                315,
                tilePageWithProof(
                        764,
                        1200,
                        originalProof("https://example.test/0", 764, 1200, 2, false)),
                spec);
        assertInvalidTilePageRejected(
                "upsampled-proof",
                316,
                tilePageWithProof(
                        764,
                        1200,
                        originalProof("https://example.test/0", 764, 1200, 1, true)),
                spec);
        assertInvalidTilePageRejected(
                "wrong-original-dimensions",
                317,
                tilePageWithProof(
                        764,
                        1200,
                        originalProof("https://example.test/0", 382, 600, 1, false)),
                spec);
        assertInvalidTilePageRejected(
                "preview-variant",
                318,
                tilePageWithProof(
                        764,
                        1200,
                        new ReaderPreparedStore.PreparedOriginalProof(
                                "https://example.test/0",
                                ReaderPreparedStore.PreparedAssetVariant.PREVIEW,
                                764,
                                1200,
                                1,
                                false)),
                spec);
    }

    @Test
    public void concurrentRunwayReservationsHaveExactlyOneWinner() throws Exception {
        String key = "runway:concurrent:0:100";
        Manga manga = manga(310);
        List<String> images = images(2);
        ReaderPreparedStore.Entry entry = ReaderPreparedStore.createOrGet(
                key, manga, null, 0, 100);
        entry.setImages(images, 0);
        entry.putDrawableBatch(
                new LinkedHashMap<>(),
                singletonTilePage(0, tilePage(100, 400)),
                false);
        ReaderPreparedStore.LaunchRunwaySpec spec = runwaySpec(100, 200, 0, 0, 150);
        int contenderCount = 8;
        CountDownLatch ready = new CountDownLatch(contenderCount);
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(contenderCount);
        List<Future<ReaderPreparedStore.LaunchRunwayLease>> futures = new ArrayList<>();
        try {
            for (int i = 0; i < contenderCount; i++) {
                futures.add(executor.submit(() -> {
                    ready.countDown();
                    start.await();
                    return ReaderPreparedStore.reserveLaunchRunway(key, manga, images, spec);
                }));
            }
            ready.await();
            start.countDown();
            int winners = 0;
            ReaderPreparedStore.LaunchRunwayLease winner = null;
            for (Future<ReaderPreparedStore.LaunchRunwayLease> future : futures) {
                ReaderPreparedStore.LaunchRunwayLease candidate = future.get();
                if (candidate != null) {
                    winners++;
                    winner = candidate;
                }
            }
            assertEquals(1, winners);
            assertNotNull(winner);
            winner.close();
            assertSame(entry, ReaderPreparedStore.get(key));
            ReaderPreparedStore.LaunchRunwayLease next =
                    ReaderPreparedStore.reserveLaunchRunway(key, manga, images, spec);
            assertNotNull(next);
            next.close();
        } finally {
            start.countDown();
            executor.shutdownNow();
        }
    }

    private ReaderPreparedStore.LaunchRunwaySpec runwaySpec(
            int viewportWidth,
            int viewportHeight,
            int startPage,
            int startOffsetPx,
            int requiredAheadPx
    ) {
        return new ReaderPreparedStore.LaunchRunwaySpec(
                viewportWidth,
                viewportHeight,
                startPage,
                startOffsetPx,
                0,
                ReaderStrictPerformanceContract.ORIGINAL_TILE_SOURCE_HEIGHT_PX,
                requiredAheadPx);
    }

    private Map<Integer, ReaderPreparedStore.PreparedTilePage> singletonTilePage(
            int index,
            ReaderPreparedStore.PreparedTilePage page
    ) {
        Map<Integer, ReaderPreparedStore.PreparedTilePage> pages = new LinkedHashMap<>();
        pages.put(index, page);
        return pages;
    }

    private ReaderPreparedStore.PreparedTilePage tilePage(int pageWidth, int pageHeight) {
        return tilePage(pageWidth, pageHeight, 0);
    }

    private ReaderPreparedStore.PreparedTilePage tilePage(
            int pageWidth,
            int pageHeight,
            int imageIndex
    ) {
        return tilePageWithProof(
                pageWidth,
                pageHeight,
                originalProof(
                        "https://example.test/" + imageIndex,
                        pageWidth,
                        pageHeight,
                        1,
                        false));
    }

    private ReaderPreparedStore.PreparedTilePage tilePageWithProof(
            int pageWidth,
            int pageHeight,
            ReaderPreparedStore.PreparedOriginalProof proof
    ) {
        List<ReaderTile> tiles = new ArrayList<>();
        int top = 0;
        while (top < pageHeight) {
            int bottom = Math.min(
                    pageHeight,
                    top + ReaderStrictPerformanceContract.ORIGINAL_TILE_SOURCE_HEIGHT_PX);
            tiles.add(new ReaderTile(
                    top,
                    bottom,
                    pageWidth,
                    pageHeight,
                    immutableBitmap(pageWidth, Math.max(1, bottom - top))));
            top = bottom;
        }
        return new ReaderPreparedStore.PreparedTilePage(
                pageWidth,
                pageHeight,
                tiles,
                proof);
    }

    private ReaderPreparedStore.PreparedTilePage tilePageWithSpans(
            int pageWidth,
            int pageHeight,
            int[][] spans
    ) {
        List<ReaderTile> tiles = new ArrayList<>();
        for (int[] span : spans) {
            tiles.add(new ReaderTile(
                    span[0],
                    span[1],
                    pageWidth,
                    pageHeight,
                    immutableBitmap(pageWidth, Math.max(1, span[1] - span[0]))));
        }
        return new ReaderPreparedStore.PreparedTilePage(
                pageWidth,
                pageHeight,
                tiles,
                originalProof("https://example.test/0", pageWidth, pageHeight, 1, false));
    }

    private ReaderPreparedStore.PreparedTilePage mutableTilePage(int pageWidth, int pageHeight) {
        Bitmap mutable = Bitmap.createBitmap(pageWidth, pageHeight, Bitmap.Config.ARGB_8888);
        ownedBitmaps.add(mutable);
        return new ReaderPreparedStore.PreparedTilePage(
                pageWidth,
                pageHeight,
                Arrays.asList(new ReaderTile(0, pageHeight, pageWidth, pageHeight, mutable)),
                originalProof("https://example.test/0", pageWidth, pageHeight, 1, false));
    }

    private ReaderPreparedStore.PreparedOriginalProof originalProof(
            String asset,
            int originalWidth,
            int originalHeight,
            int sample,
            boolean resized
    ) {
        return new ReaderPreparedStore.PreparedOriginalProof(
                asset,
                ReaderPreparedStore.PreparedAssetVariant.ORIGINAL,
                originalWidth,
                originalHeight,
                sample,
                resized);
    }

    private Bitmap immutableBitmap(int width, int height) {
        Bitmap mutable = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
        Bitmap immutable = mutable.copy(Bitmap.Config.ARGB_8888, false);
        mutable.recycle();
        ownedBitmaps.add(immutable);
        return immutable;
    }

    private List<String> images(int count) {
        List<String> images = new ArrayList<>();
        for (int index = 0; index < count; index++) {
            images.add("https://example.test/" + index);
        }
        return images;
    }

    private ReaderPreparedStore.Listener countingTileListener(AtomicInteger tileCallbacks) {
        return new ReaderPreparedStore.Listener() {
            @Override
            public void onUrlsReady(List<String> images, int startPage) {
            }

            @Override
            public void onBitmapReady(int index, Bitmap bitmap) {
            }

            @Override
            public void onBitmapBatchReady(Map<Integer, Bitmap> bitmaps) {
            }

            @Override
            public void onTilePageBatchReady(
                    Map<Integer, ReaderPreparedStore.PreparedTilePage> tilePages
            ) {
                tileCallbacks.incrementAndGet();
            }

            @Override
            public void onFailed() {
            }
        };
    }

    private void assertInvalidTilePageRejected(
            String suffix,
            int mangaId,
            ReaderPreparedStore.PreparedTilePage page,
            ReaderPreparedStore.LaunchRunwaySpec spec
    ) {
        String key = "runway:invalid-" + suffix + ":0:100";
        Manga manga = manga(mangaId);
        List<String> images = images(1);
        ReaderPreparedStore.Entry entry = ReaderPreparedStore.createOrGet(
                key, manga, null, 0, 100);
        entry.setImages(images, 0);
        entry.putDrawableBatch(
                new LinkedHashMap<>(),
                singletonTilePage(0, page),
                false);
        assertNull(ReaderPreparedStore.reserveLaunchRunway(key, manga, images, spec));
        assertSame(entry, ReaderPreparedStore.get(key));
    }

    private void assertAllOwnedBitmapsAlive() {
        for (Bitmap bitmap : ownedBitmaps) {
            assertFalse(bitmap.isRecycled());
        }
    }

    private ReaderPreparedStore.Entry readyEntry(
            String key,
            Manga manga,
            int width,
            Bitmap first,
            Bitmap second
    ) {
        ReaderPreparedStore.Entry entry = ReaderPreparedStore.createOrGet(
                key, manga, null, 0, width);
        entry.setImages(Arrays.asList(
                "https://example.test/0", "https://example.test/1"), 0);
        Map<Integer, Bitmap> bitmaps = new LinkedHashMap<>();
        bitmaps.put(0, first);
        bitmaps.put(1, second);
        entry.putBitmapBatch(bitmaps, true);
        return entry;
    }

    private Manga manga(int id) {
        return new Manga(id, "lease-test-" + id, "", MTitle.base_webtoon);
    }

    private Bitmap bitmap() {
        Bitmap bitmap = Bitmap.createBitmap(8, 8, Bitmap.Config.ARGB_8888);
        ownedBitmaps.add(bitmap);
        return bitmap;
    }

    private void assertSnapshotCollectionsAreUnmodifiable(
            ReaderPreparedStore.Snapshot snapshot,
            Bitmap bitmap
    ) {
        try {
            snapshot.getImages().add("https://example.test/mutated");
            fail("Prepared lease images must be unmodifiable");
        } catch (UnsupportedOperationException expected) {
            // Expected: the lease owns one immutable structural snapshot.
        }
        try {
            snapshot.getBitmaps().put(99, bitmap);
            fail("Prepared lease bitmap map must be unmodifiable");
        } catch (UnsupportedOperationException expected) {
            // Expected: bitmap identities are shared, but the map shape is fixed.
        }
    }
}
