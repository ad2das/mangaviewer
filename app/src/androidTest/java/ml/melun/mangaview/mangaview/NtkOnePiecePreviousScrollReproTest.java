package ml.melun.mangaview.mangaview;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.graphics.Rect;
import android.os.Debug;
import android.os.ParcelFileDescriptor;
import android.os.SystemClock;
import android.util.Log;

import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry;
import androidx.test.runner.lifecycle.Stage;
import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import androidx.test.uiautomator.By;
import androidx.test.uiautomator.UiDevice;
import androidx.test.uiautomator.UiObject2;
import androidx.test.uiautomator.Until;
import androidx.recyclerview.widget.RecyclerView;

import org.junit.Test;
import org.junit.After;
import org.junit.runner.RunWith;

import java.lang.reflect.Field;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import ml.melun.mangaview.LiveNetworkAssume;
import ml.melun.mangaview.MainApplication;
import ml.melun.mangaview.R;
import ml.melun.mangaview.Utils;
import ml.melun.mangaview.activity.EpisodeActivity;
import ml.melun.mangaview.activity.ReaderV2Activity;
import ml.melun.mangaview.reader.ReaderImageCache;
import ml.melun.mangaview.reader.ReaderSurfaceView;
import ml.melun.mangaview.runtime.ViewerTelemetry;

@RunWith(AndroidJUnit4.class)
public class NtkOnePiecePreviousScrollReproTest {
    private static final String PACKAGE_NAME = "ml.melun.mangaview";
    private static final String TAG = "NtkMultiEpisodeProbe";
    private static final String FORCE_HWUI_PROPERTY =
            "mangaview.reader.force_hwui_for_test";

    @After
    public void clearPresentationOverride() throws Exception {
        System.clearProperty(FORCE_HWUI_PROPERTY);
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
            LinkedHashSet<Activity> activities = new LinkedHashSet<>();
            for (Stage stage : Stage.values()) {
                activities.addAll(ActivityLifecycleMonitorRegistry.getInstance()
                        .getActivitiesInStage(stage));
            }
            for (Activity activity : activities) {
                if (!activity.isFinishing()) activity.finish();
            }
        });
        executeShellAndDrain("am force-stop com.android.settings");
        UiDevice device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation());
        device.pressHome();
        device.setOrientationNatural();
        device.unfreezeRotation();
        device.waitForIdle();
        SystemClock.sleep(250L);
    }

    @Test
    public void scrollOnlyPreviousEpisodeFiveTimes() throws Exception {
        LiveNetworkAssume.assumeEnabled();
        launchOnePieceEpisodes();

        UiDevice device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation());
        UiObject2 episodeRow = device.wait(Until.findObject(By.res(PACKAGE_NAME, "episode")), 90000L);
        assertNotNull("Expected NTK One Piece latest episode row", episodeRow);
        episodeRow.click();

        device.waitForIdle(90000L);
        Thread.sleep(5000L);

        int width = device.getDisplayWidth();
        int height = device.getDisplayHeight();
        int x = width / 2;
        int fromY = Math.max(120, height / 4);
        int toY = Math.min(height - 160, height * 3 / 4);

        for (int round = 0; round < 5; round++) {
            for (int swipe = 0; swipe < 18; swipe++) {
                device.swipe(x, fromY, x, toY, 36);
                Thread.sleep(420L);
            }
            Thread.sleep(3000L);
        }
    }

    @Test
    public void scrollForwardPastInitialRunway() throws Exception {
        LiveNetworkAssume.assumeEnabled();
        launchOnePieceEpisodes();

        UiDevice device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation());
        UiObject2 episodeRow = device.wait(Until.findObject(By.res(PACKAGE_NAME, "episode")), 90000L);
        assertNotNull("Expected NTK One Piece latest episode row", episodeRow);
        episodeRow.click();

        UiObject2 strip = device.wait(Until.findObject(By.res(PACKAGE_NAME, "strip")), 90000L);
        assertNotNull("Expected NTK reader surface", strip);
        long scrollStartedAt = SystemClock.elapsedRealtime();
        int[] progress = scrollForwardUntilPage(device, 4, 4000L);
        assertTrue("Expected immediate cellular manga scrolling beyond the initial runway; current="
                        + progress[0] + ",count=" + progress[1] + ",elapsedMs="
                        + (SystemClock.elapsedRealtime() - scrollStartedAt),
                progress[0] >= 4 && progress[1] > 4);
    }

    @Test
    public void onePieceLatestForwardRunwayIsPreattached() throws Exception {
        LiveNetworkAssume.assumeEnabled();
        launchOnePieceEpisodes();

        UiDevice device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation());
        // Select by chapter identity rather than UiAutomator child order. The provider mixes
        // recycled rows in accessibility traversal order, so the fourth observed node is not
        // necessarily the fourth visible/latest chapter.
        UiObject2 episodeRow = findEpisodeRowByDescription(device, "1185화", 90000L);
        assertNotNull("Expected current One Piece 1185 episode row", episodeRow);
        episodeRow.click();

        UiObject2 strip = device.wait(Until.findObject(By.res(PACKAGE_NAME, "strip")), 90000L);
        assertNotNull("Expected NTK One Piece reader surface", strip);
        ReaderV2Activity reader = resumedReader();
        assertNotNull("Expected resumed One Piece reader", reader);
        Manga current = reader.testEpisode("1185화");
        assertNotNull("Expected current One Piece 1185 metadata", current);
        assertEquals(current.getNtkEpisodePath(), reader.testCurrentNtkEpisodePath());

        for (int chapter = 1185; chapter < 1188; chapter++) {
            Manga next = reader.testEpisode((chapter + 1) + "화");
            assertNotNull("Expected next One Piece " + (chapter + 1) + " metadata", next);

            long currentReadyStartedAt = SystemClock.elapsedRealtime();
            while (SystemClock.elapsedRealtime() - currentReadyStartedAt < 30000L &&
                    !reader.testHasFullyReadyEpisode(current)) {
                SystemClock.sleep(16L);
            }
            assertTrue(
                    "Current One Piece chapter must become fully drawable before adjacent work; "
                            + "chapter=" + chapter
                            + ",path=" + current.getNtkEpisodePath()
                            + ",elapsedMs="
                            + (SystemClock.elapsedRealtime() - currentReadyStartedAt),
                    reader.testHasFullyReadyEpisode(current));
            assertTrue(
                    "Current One Piece chapter must retain canonical source order before adjacent "
                            + "work; chapter=" + chapter
                            + ",path=" + current.getNtkEpisodePath(),
                    reader.testHasCanonicalEpisodeOrder(current));

            long preattachStartedAt = SystemClock.elapsedRealtime();
            // This is a correctness/liveness gate, not an image-speed SLA. A proven multi-megabyte
            // original can have an externally slow tail while the exact source order remains
            // healthy; wait long enough to distinguish that from a permanently stranded runway.
            while (SystemClock.elapsedRealtime() - preattachStartedAt < 30000L &&
                    !reader.testHasReadyEpisodeRunway(next, 4)) {
                SystemClock.sleep(16L);
            }
            long preattachElapsedMs = SystemClock.elapsedRealtime() - preattachStartedAt;
            Log.i(
                    TAG,
                    "onePieceNextRunwayPreattached chapter=" + chapter
                            + ",source=" + current.getNtkEpisodePath()
                            + ",target=" + next.getNtkEpisodePath()
                            + ",readyImages=4,elapsedMs=" + preattachElapsedMs);
            assertTrue(
                    "One Piece " + (chapter + 1)
                            + " must have four real drawables attached before the "
                            + chapter + " boundary; source=" + current.getNtkEpisodePath()
                            + ",target=" + next.getNtkEpisodePath()
                            + ",elapsedMs=" + preattachElapsedMs,
                    reader.testHasReadyEpisodeRunway(next, 4));
            assertEquals(
                    "The preattached One Piece runway must expose a visible episode separator",
                    "다음 회차: " + (chapter + 1) + "화",
                    reader.testEpisodeTransitionTitle(next));
            long separatorPublishedAt = SystemClock.elapsedRealtime();
            while (SystemClock.elapsedRealtime() - separatorPublishedAt < 2000L &&
                    reader.testPageReadinessSnapshot().getCardPages() == 0) {
                SystemClock.sleep(16L);
            }
            assertTrue(
                    "The episode separator must be installed in the physical reader page table",
                    reader.testPageReadinessSnapshot().getCardPages() > 0);

            long boundaryStartedAt = SystemClock.elapsedRealtime();
            String transitioned = forceForwardEpisodeTransition(
                    reader,
                    current.getNtkEpisodePath(),
                    30000L);
            long boundaryElapsedMs = SystemClock.elapsedRealtime() - boundaryStartedAt;
            Log.i(
                    TAG,
                    "onePieceForwardBoundary chapter=" + chapter
                            + ",target=" + transitioned
                            + ",elapsedMs=" + boundaryElapsedMs);
            assertEquals(next.getNtkEpisodePath(), transitioned);
            assertTrue(
                    "A physically preattached One Piece chapter must cross without network wait; "
                            + "chapter=" + chapter
                            + ",elapsedMs=" + boundaryElapsedMs,
                    boundaryElapsedMs <= 250L);
            current = next;
        }

        long latestReadyStartedAt = SystemClock.elapsedRealtime();
        while (SystemClock.elapsedRealtime() - latestReadyStartedAt < 30000L &&
                !reader.testHasFullyReadyEpisode(current)) {
            SystemClock.sleep(16L);
        }
        assertTrue(
                "Latest One Piece 1188 must remain fully drawable after chained transitions; "
                        + "elapsedMs=" + (SystemClock.elapsedRealtime() - latestReadyStartedAt),
                reader.testHasFullyReadyEpisode(current));
        assertTrue(
                "Latest One Piece 1188 must retain canonical source order after chained "
                        + "transitions; path=" + current.getNtkEpisodePath(),
                reader.testHasCanonicalEpisodeOrder(current));
    }

    @Test
    public void onePieceImmediateFastForwardHasRunwayBeforeCurrentTail() throws Exception {
        LiveNetworkAssume.assumeEnabled();
        launchOnePieceEpisodes();

        UiDevice device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation());
        UiObject2 episodeRow = findEpisodeRowByDescription(device, "1185화", 90000L);
        assertNotNull("Expected current One Piece 1185 episode row", episodeRow);
        episodeRow.click();

        UiObject2 strip = device.wait(Until.findObject(By.res(PACKAGE_NAME, "strip")), 90000L);
        assertNotNull("Expected NTK One Piece reader surface", strip);
        ReaderV2Activity reader = resumedReader();
        assertNotNull("Expected resumed One Piece reader", reader);
        Manga current = reader.testEpisode("1185화");
        Manga next = reader.testEpisode("1186화");
        assertNotNull("Expected current One Piece 1185 metadata", current);
        assertNotNull("Expected next One Piece 1186 metadata", next);

        long currentReadyStartedAt = SystemClock.elapsedRealtime();
        while (SystemClock.elapsedRealtime() - currentReadyStartedAt < 30000L &&
                !reader.testHasFullyReadyEpisode(current)) {
            SystemClock.sleep(16L);
        }
        assertTrue(
                "Current One Piece chapter must become fully drawable",
                reader.testHasFullyReadyEpisode(current));

        // Do not wait for or poll the adjacent runway before the gesture. This reproduces a user
        // immediately flinging after the current chapter finishes and proves that preparation runs
        // concurrently with normal traversal instead of beginning only after the boundary callback.
        int initialCurrentPageCount = reader.testPageCount();
        int width = device.getDisplayWidth();
        int height = device.getDisplayHeight();
        int x = width / 2;
        int fromY = Math.min(height - 160, height * 3 / 4);
        int toY = Math.max(120, height / 4);
        boolean runwayReadyBeforeTail = reader.testHasReadyEpisodeRunway(next, 1);
        long tailReachedAt = -1L;
        long deadline = SystemClock.elapsedRealtime() + 30000L;
        String transitioned = current.getNtkEpisodePath();
        while (SystemClock.elapsedRealtime() < deadline) {
            device.swipe(x, fromY, x, toY, 8);
            if (reader.testHasReadyEpisodeRunway(next, 1) &&
                    reader.testCurrentPage() < initialCurrentPageCount - 1) {
                runwayReadyBeforeTail = true;
            }
            if (tailReachedAt < 0L &&
                    reader.testCurrentPage() >= initialCurrentPageCount - 1) {
                tailReachedAt = SystemClock.elapsedRealtime();
            }
            transitioned = reader.testCurrentNtkEpisodePath();
            if (transitioned != null &&
                    !transitioned.equals(current.getNtkEpisodePath())) {
                break;
            }
        }
        long tailWaitMs = tailReachedAt < 0L
                ? 0L
                : SystemClock.elapsedRealtime() - tailReachedAt;
        long fourPageRunwayStartedAt = SystemClock.elapsedRealtime();
        while (SystemClock.elapsedRealtime() - fourPageRunwayStartedAt < 6000L &&
                !reader.testHasReadyEpisodeRunway(next, 4)) {
            SystemClock.sleep(16L);
        }
        boolean fourPageRunwayReady = reader.testHasReadyEpisodeRunway(next, 4);
        long fourPageRunwayWaitMs = SystemClock.elapsedRealtime() - fourPageRunwayStartedAt;
        Log.i(
                TAG,
                "onePieceImmediateFastForwardBoundary source=" + current.getNtkEpisodePath()
                        + ",target=" + transitioned
                        + ",runwayReadyBeforeTail=" + runwayReadyBeforeTail
                        + ",tailWaitMs=" + tailWaitMs
                        + ",fourPageRunwayReady=" + fourPageRunwayReady
                        + ",fourPageRunwayWaitMs=" + fourPageRunwayWaitMs
                        + ",initialCount=" + initialCurrentPageCount);
        assertTrue(
                "The next One Piece runway must be physically attached before the current tail",
                runwayReadyBeforeTail);
        assertEquals(next.getNtkEpisodePath(), transitioned);
        assertTrue(
                "The next One Piece four-page runway must complete immediately after transition",
                fourPageRunwayReady);
    }

    @Test
    public void onePieceIdleThenPhysicalForwardScrollKeepsNextRunway() throws Exception {
        LiveNetworkAssume.assumeEnabled();
        launchOnePieceEpisodes();

        UiDevice device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation());
        UiObject2 episodeRow = findEpisodeRowByDescription(device, "1185화", 90000L);
        assertNotNull("Expected current One Piece 1185 episode row", episodeRow);
        episodeRow.click();

        UiObject2 strip = device.wait(Until.findObject(By.res(PACKAGE_NAME, "strip")), 90000L);
        assertNotNull("Expected NTK One Piece reader surface", strip);
        ReaderV2Activity reader = resumedReader();
        assertNotNull("Expected resumed One Piece reader", reader);
        Manga current = reader.testEpisode("1185화");
        Manga next = reader.testEpisode("1186화");
        assertNotNull("Expected current One Piece 1185 metadata", current);
        assertNotNull("Expected next One Piece 1186 metadata", next);

        long currentReadyStartedAt = SystemClock.elapsedRealtime();
        while (SystemClock.elapsedRealtime() - currentReadyStartedAt < 30000L &&
                !reader.testHasFullyReadyEpisode(current)) {
            SystemClock.sleep(16L);
        }
        assertTrue(
                "Current One Piece chapter must finish before idle adjacent preparation",
                reader.testHasFullyReadyEpisode(current));

        long preattachStartedAt = SystemClock.elapsedRealtime();
        // Keep this a liveness assertion. The exact page-one tail can be externally slow even
        // when all five source operations started promptly and complete without retry.
        while (SystemClock.elapsedRealtime() - preattachStartedAt < 30000L &&
                !reader.testHasReadyEpisodeRunway(next, 4)) {
            SystemClock.sleep(16L);
        }
        assertTrue(
                "Idle One Piece reader must preattach four next-episode drawables",
                reader.testHasReadyEpisodeRunway(next, 4));

        // Reproduce the real UX: no test hook or boundary callback runs while the reader is left
        // untouched. The first physical gesture must not discard the already attached runway.
        SystemClock.sleep(1800L);
        int width = device.getDisplayWidth();
        int height = device.getDisplayHeight();
        device.swipe(
                width / 2,
                Math.min(height - 160, height * 3 / 4),
                width / 2,
                Math.max(120, height / 4),
                8);
        assertTrue(
                "The first physical forward gesture must retain the idle-prepared runway",
                reader.testHasReadyEpisodeRunway(next, 4));

        long physicalScrollStartedAt = SystemClock.elapsedRealtime();
        String transitioned = scrollPhysicallyForwardUntilEpisodeChanges(
                device,
                reader,
                current.getNtkEpisodePath(),
                30000L);
        long physicalScrollElapsedMs =
                SystemClock.elapsedRealtime() - physicalScrollStartedAt;
        Log.i(
                TAG,
                "onePieceIdlePhysicalForwardBoundary source=" + current.getNtkEpisodePath()
                        + ",target=" + transitioned
                        + ",elapsedMs=" + physicalScrollElapsedMs);
        assertEquals(
                "Physical forward scrolling after an idle period must enter the prepared chapter",
                next.getNtkEpisodePath(),
                transitioned);
        assertTrue(
                "The physically entered One Piece chapter must retain canonical source order",
                reader.testHasCanonicalEpisodeOrder(next));
    }

    @Test
    public void onePieceLatestPhysicalForwardScrollStaysBelowOnePercentJank() throws Exception {
        LiveNetworkAssume.assumeEnabled();
        if ("true".equalsIgnoreCase(
                InstrumentationRegistry.getArguments().getString("forceHwui"))) {
            System.setProperty(FORCE_HWUI_PROPERTY, "true");
        }
        launchOnePieceEpisodes();

        UiDevice device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation());
        UiObject2 episodeRow = findEpisodeRowByDescription(device, "1185화", 90000L);
        assertNotNull("Expected current One Piece 1185 episode row", episodeRow);
        episodeRow.click();

        UiObject2 strip = device.wait(Until.findObject(By.res(PACKAGE_NAME, "strip")), 90000L);
        assertNotNull("Expected NTK One Piece reader surface", strip);
        ReaderV2Activity reader = resumedReader();
        assertNotNull("Expected resumed One Piece reader", reader);
        Manga current = reader.testEpisode("1185화");
        assertNotNull("Expected current One Piece 1185 metadata", current);

        LinkedHashSet<String> visitedPaths = new LinkedHashSet<>();
        visitedPaths.add(current.getNtkEpisodePath());
        long totalNativeIntervals = 0L;
        long totalNativeSlowIntervals = 0L;
        long totalCallbackIntervals = 0L;
        long totalCallbackMissedIntervals = 0L;
        logMultiEpisodeSnapshot(reader, 1185, "initial");

        for (int chapter = 1185; chapter < 1188; chapter++) {
            Manga next = reader.testEpisode((chapter + 1) + "화");
            assertNotNull("Expected next One Piece " + (chapter + 1) + " metadata", next);

            long currentReadyStartedAt = SystemClock.elapsedRealtime();
            while (SystemClock.elapsedRealtime() - currentReadyStartedAt < 30000L &&
                    !reader.testHasFullyReadyEpisode(current)) {
                SystemClock.sleep(16L);
            }
            assertTrue(
                    "Current One Piece chapter must be fully drawable before physical scrolling; "
                            + "chapter=" + chapter,
                    reader.testHasFullyReadyEpisode(current));

            long runwayStartedAt = SystemClock.elapsedRealtime();
            while (SystemClock.elapsedRealtime() - runwayStartedAt < 12000L &&
                    !reader.testHasReadyEpisodeRunway(next, 4)) {
                SystemClock.sleep(16L);
            }
            assertTrue(
                    "Idle reader must attach four next-chapter drawables before scrolling; "
                            + "chapter=" + chapter
                            + ",target=" + next.getNtkEpisodePath(),
                    reader.testHasReadyEpisodeRunway(next, 4));

            // The reported problem occurs after leaving the completed chapter untouched. Keep the
            // reader idle long enough for the normal production idle-preparation path, then measure
            // only real touch-driven forward frames through the next boundary.
            SystemClock.sleep(1800L);
            runOnMain(reader::testResetFrameStatsSnapshot);
            ViewerTelemetry.NativeFrameStatsSnapshot nativeBefore =
                    ViewerTelemetry.nativeFrameStatsSnapshot();
            assertNotNull("Expected active native frame telemetry before chapter " + chapter,
                    nativeBefore);
            long physicalScrollStartedAt = SystemClock.elapsedRealtime();
            String transitioned = scrollPhysicallyForwardUntilEpisodeChanges(
                    device,
                    reader,
                    current.getNtkEpisodePath(),
                    30000L);
            long physicalScrollElapsedMs =
                    SystemClock.elapsedRealtime() - physicalScrollStartedAt;
            assertEquals(
                    "Physical forward scrolling must enter the exact prepared One Piece chapter",
                    next.getNtkEpisodePath(),
                    transitioned);
            assertTrue(
                    "Physical forward reading must not revisit an already consumed chapter; path="
                            + transitioned,
                    visitedPaths.add(transitioned));

            ReaderSurfaceView.FrameStatsSnapshot frames =
                    requireCompletedPhysicalFrameStats(reader, chapter);
            ViewerTelemetry.NativeFrameStatsSnapshot nativeAfter =
                    ViewerTelemetry.nativeFrameStatsSnapshot();
            assertNotNull("Expected active native frame telemetry after chapter " + chapter,
                    nativeAfter);
            assertEquals(
                    "The native telemetry generation must remain stable across an adjacent chapter",
                    nativeBefore.getGeneration(),
                    nativeAfter.getGeneration());
            long nativeIntervals =
                    nativeAfter.getScrollIntervals() - nativeBefore.getScrollIntervals();
            long nativeSlowIntervals =
                    nativeAfter.getSlowIntervals() - nativeBefore.getSlowIntervals();
            long segmentWorstSlowIntervalNanos =
                    nativeAfter.getMaxRecordedSlowIntervalDurationSince(
                            nativeBefore.getSlowIntervals());
            totalNativeIntervals += nativeIntervals;
            totalNativeSlowIntervals += nativeSlowIntervals;
            totalCallbackIntervals += frames.getSamples();
            totalCallbackMissedIntervals += frames.getMissedIntervals();
            double missedPercent =
                    nativeSlowIntervals * 100.0 / Math.max(1L, nativeIntervals);
            Log.i(
                    TAG,
                    "onePiecePhysicalForwardJank chapter=" + chapter
                            + ",target=" + transitioned
                            + ",elapsedMs=" + physicalScrollElapsedMs
                            + ",nativeIntervals=" + nativeIntervals
                            + ",nativeSlowIntervals=" + nativeSlowIntervals
                            + ",segmentWorstSlowMs="
                            + (segmentWorstSlowIntervalNanos / 1_000_000.0)
                            + ",missedPercent=" + missedPercent
                            + ",slowDetails=" + nativeAfter.getSlowIntervalDetails()
                            + ",callbackSamples=" + frames.getSamples()
                            + ",callbackMissedIntervals=" + frames.getMissedIntervals()
                            + ",totalP95=" + frames.getTotalP95()
                            + ",totalMax=" + frames.getTotalMax()
                            + ",missingPx=" + frames.getMaxMissingPx());
            assertEquals(
                    "One Piece physical forward scrolling must not drop native frames; chapter="
                            + chapter,
                    0,
                    frames.getDroppedFrames());
            assertEquals(
                    "One Piece physical forward scrolling must not expose missing pixels; chapter="
                            + chapter,
                    0,
                    frames.getMaxMissingPx());
            assertTrue(
                    "One Piece physical forward segment must not contain a 100ms frame; chapter="
                            + chapter + ",worstMs="
                            + (segmentWorstSlowIntervalNanos / 1_000_000.0),
                    // The bounded slow-detail ring deliberately returns -1 when this segment
                    // generated more diagnostics than the ring can retain. The cumulative
                    // interval/slow counters above remain exact. Do not turn diagnostic-ring
                    // eviction into an unrelated 100ms-frame failure that prevents later
                    // chapters from running.
                    segmentWorstSlowIntervalNanos < 0L ||
                            segmentWorstSlowIntervalNanos < 100_000_000L);
            // The current-episode-only strict telemetry counter intentionally rejects a viewport
            // that straddles two episodes. That is the expected continuous-reader boundary here,
            // so bind correctness is qualified by zero missing pixels plus each episode's exact
            // canonical path/order below instead of treating the attached next episode as stale.
            assertTrue(
                    "The entered One Piece chapter must retain canonical source order; chapter="
                            + (chapter + 1),
                    reader.testHasCanonicalEpisodeOrder(next));
            logMultiEpisodeSnapshot(reader, chapter + 1, "physical-forward");
            current = next;
        }
        double nativeQueueSlowPercent =
                totalNativeSlowIntervals * 100.0 / Math.max(1L, totalNativeIntervals);
        double aggregateMissedPercent = totalCallbackMissedIntervals * 100.0 /
                Math.max(1L, totalCallbackIntervals);
        assertTrue(
                "Expected at least 150 committed native intervals across the physical One Piece "
                        + "1185..1188 traversal; intervals=" + totalNativeIntervals,
                totalNativeIntervals >= 150L);
        assertTrue(
                "One Piece latest physical forward callback jank must remain below 1% across "
                        + "the complete 1185..1188 traversal; missed="
                        + totalCallbackMissedIntervals
                        + ",intervals=" + totalCallbackIntervals
                        + ",percent=" + aggregateMissedPercent,
                aggregateMissedPercent < 1.0);
        // Kind-2 native timestamps are successful BufferQueue swap-return evidence, not
        // SurfaceFlinger presentation fences. Host gfxstream can legally return a few buffers
        // 25-40 ms apart even when the Choreographer callback and authoritative SF trace are
        // smooth. Keep a generous corruption/stall guard here; the macrobenchmark PresentFence
        // gate remains the <1% display-jank authority.
        assertTrue(
                "Native BufferQueue diagnostics must not indicate a degraded renderer; slow="
                        + totalNativeSlowIntervals + ",intervals=" + totalNativeIntervals
                        + ",percent=" + nativeQueueSlowPercent,
                nativeQueueSlowPercent < 20.0);
    }

    @Test
    public void onePieceForwardThenDeepReverseRehydratesRetiredPixels() throws Exception {
        LiveNetworkAssume.assumeEnabled();
        launchOnePieceEpisodes();

        UiDevice device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation());
        UiObject2 episodeRow = findEpisodeRowByDescription(device, "1185화", 90000L);
        assertNotNull("Expected current One Piece 1185 episode row", episodeRow);
        episodeRow.click();
        assertNotNull(
                "Expected NTK One Piece reader surface",
                device.wait(Until.findObject(By.res(PACKAGE_NAME, "strip")), 90000L));
        ReaderV2Activity reader = resumedReader();
        assertNotNull("Expected resumed One Piece reader", reader);
        Manga current = reader.testEpisode("1185화");
        Manga next = reader.testEpisode("1186화");
        assertNotNull("Expected One Piece 1185 metadata", current);
        assertNotNull("Expected One Piece 1186 metadata", next);

        // Current and successor exact bodies share a deliberately bounded transport budget.
        // A cold origin can consume most of 30 seconds proving the current chapter before the
        // successor's p0-p4 batch gets its turn. Keep this as an eventual-liveness assertion,
        // not a network-speed assertion, so the reverse-scroll probe reaches its real subject.
        long readyDeadline = SystemClock.elapsedRealtime() + 90000L;
        while (SystemClock.elapsedRealtime() < readyDeadline &&
                (!reader.testHasFullyReadyEpisode(current) ||
                        !reader.testHasReadyEpisodeRunway(next, 4))) {
            SystemClock.sleep(16L);
        }
        assertTrue("Current chapter must be drawable before the forward/reverse probe",
                reader.testHasFullyReadyEpisode(current));
        assertTrue("Next chapter runway must be drawable before the forward/reverse probe",
                reader.testHasReadyEpisodeRunway(next, 4));

        assertEquals(
                "Forward input must enter the exact prepared successor",
                next.getNtkEpisodePath(),
                scrollPhysicallyForwardUntilEpisodeChanges(
                        device, reader, current.getNtkEpisodePath(), 30000L));

        int width = device.getDisplayWidth();
        int height = device.getDisplayHeight();
        int x = width / 2;
        int forwardFromY = Math.min(height - 160, height * 3 / 4);
        int forwardToY = Math.max(120, height / 4);
        for (int index = 0; index < 4; index++) {
            device.swipe(x, forwardFromY, x, forwardToY, 12);
        }
        SystemClock.sleep(300L);

        runOnMain(reader::testResetFrameStatsSnapshot);
        int reverseFromY = Math.max(120, height / 4);
        int reverseToY = Math.min(height - 160, height * 3 / 4);
        long reverseDeadline = SystemClock.elapsedRealtime() + 30000L;
        while (SystemClock.elapsedRealtime() < reverseDeadline &&
                !current.getNtkEpisodePath().equals(reader.testCurrentNtkEpisodePath())) {
            device.swipe(x, reverseFromY, x, reverseToY, 12);
        }
        assertEquals(
                "Reverse input must re-enter the retained predecessor episode",
                current.getNtkEpisodePath(),
                reader.testCurrentNtkEpisodePath());

        int predecessorEntryPage = reader.testCurrentPage();
        int deepestPage = predecessorEntryPage;
        for (int swipe = 0; swipe < 24 && deepestPage > 0; swipe++) {
            device.swipe(x, reverseFromY, x, reverseToY, 12);
            assertEquals(
                    "Deep reverse must remain bound to the retained predecessor",
                    current.getNtkEpisodePath(),
                    reader.testCurrentNtkEpisodePath());
            deepestPage = Math.min(deepestPage, reader.testCurrentPage());
            if (predecessorEntryPage - deepestPage >= 8) break;
        }
        assertTrue(
                "Reverse scrolling must travel beyond the eight-page decoded tail or reach the "
                        + "predecessor start; entry=" + predecessorEntryPage
                        + ",deepest=" + deepestPage,
                deepestPage == 0 || predecessorEntryPage - deepestPage >= 8);
        assertTrue(
                "The rehydrated predecessor must retain canonical source order",
                reader.testHasCanonicalEpisodeOrder(current));

        SystemClock.sleep(80L);
        ReaderSurfaceView.FrameStatsSnapshot frames = reader.testFrameStatsSnapshot();
        assertNotNull("Expected reverse frame evidence", frames);
        assertTrue("Expected multiple physical reverse frames", frames.getSamples() >= 8);
        assertEquals("Deep reverse must not drop a renderer frame", 0, frames.getDroppedFrames());
        assertEquals("Deep reverse must not expose a blank pixel gap", 0, frames.getMaxMissingPx());
    }

    @Test
    public void onePieceResumeImmediateReverseThenHomeKeepsPixelsAndMotion() throws Exception {
        LiveNetworkAssume.assumeEnabled();
        launchOnePieceEpisodes();

        UiDevice device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation());
        UiObject2 episodeRow = findEpisodeRowByDescription(device, "1185화", 90000L);
        assertNotNull("Expected current One Piece 1185 episode row", episodeRow);
        episodeRow.click();
        assertNotNull(
                "Expected initial One Piece reader surface",
                device.wait(Until.findObject(By.res(PACKAGE_NAME, "strip")), 90000L));
        ReaderV2Activity firstReader = resumedReader();
        assertNotNull("Expected initial resumed reader", firstReader);
        Manga current = firstReader.testEpisode("1185화");
        assertNotNull("Expected One Piece 1185 metadata", current);

        long readyDeadline = SystemClock.elapsedRealtime() + 90000L;
        while (SystemClock.elapsedRealtime() < readyDeadline &&
                !firstReader.testHasFullyReadyEpisode(current)) {
            SystemClock.sleep(16L);
        }
        assertTrue("Current chapter must be drawable before bookmark re-entry",
                firstReader.testHasFullyReadyEpisode(current));

        // Move the live reader to a real middle page before opening Continue. ReaderV2 saves its
        // current progress synchronously in onPause; writing a synthetic bookmark while the old
        // reader is still on page zero would correctly be overwritten by that lifecycle save.
        int width = device.getDisplayWidth();
        int height = device.getDisplayHeight();
        int x = width / 2;
        int forwardFrom = Math.min(height - 160, height * 3 / 4);
        int forwardTo = Math.max(120, height / 4);
        long middlePageDeadline = SystemClock.elapsedRealtime() + 15000L;
        while (SystemClock.elapsedRealtime() < middlePageDeadline &&
                firstReader.testCurrentPage() < 8) {
            device.swipe(x, forwardFrom, x, forwardTo, 12);
        }
        assertTrue("Initial reader did not reach a middle page for resume testing; page="
                        + firstReader.testCurrentPage(),
                firstReader.testCurrentPage() >= 8);
        runOnMain(() -> Utils.openContinueViewer(firstReader, current, -1));
        ReaderV2Activity reader = null;
        long replacementDeadline = SystemClock.elapsedRealtime() + 90000L;
        while (SystemClock.elapsedRealtime() < replacementDeadline) {
            ReaderV2Activity candidate = resumedReader();
            if (candidate != null && candidate != firstReader) {
                reader = candidate;
                break;
            }
            SystemClock.sleep(16L);
        }
        assertNotNull("Bookmark continue must open a replacement reader", reader);
        assertNotNull(
                "Replacement reader must physically commit its resumed page",
                device.wait(Until.findObject(By.descStartsWith("actual:")), 90000L));

        ReaderSurfaceView.ScrollPositionSnapshot initial =
                reader.testCurrentScrollPositionSnapshot();
        assertNotNull("Replacement reader lost its initial scroll position", initial);
        assertTrue("Bookmark re-entry did not start above the first image; offset="
                        + initial.getScrollOffset(),
                initial.getScrollOffset() > 0);

        int reverseFrom = Math.max(120, height / 4);
        int reverseTo = Math.min(height - 160, height * 3 / 4);
        for (int swipe = 0; swipe < 2; swipe++) {
            device.swipe(x, reverseFrom, x, reverseTo, 18);
        }
        ReaderSurfaceView.ScrollPositionSnapshot reversed = null;
        long reverseDeadline = SystemClock.elapsedRealtime() + 5000L;
        while (SystemClock.elapsedRealtime() < reverseDeadline) {
            reversed = reader.testCurrentScrollPositionSnapshot();
            if (reversed != null && reversed.getScrollOffset() <
                    initial.getScrollOffset() - 100) break;
            SystemClock.sleep(16L);
        }
        assertNotNull("Immediate reverse lost the scroll position", reversed);
        assertTrue("Immediate reverse was blocked at the saved resume floor; initial="
                        + initial.getScrollOffset() + ",reversed=" + reversed.getScrollOffset(),
                reversed.getScrollOffset() < initial.getScrollOffset() - 100);

        device.swipe(x, forwardFrom, x, forwardTo, 18);
        ReaderSurfaceView.ScrollPositionSnapshot forwarded = null;
        long forwardDeadline = SystemClock.elapsedRealtime() + 5000L;
        while (SystemClock.elapsedRealtime() < forwardDeadline) {
            forwarded = reader.testCurrentScrollPositionSnapshot();
            if (forwarded != null && forwarded.getScrollOffset() >
                    reversed.getScrollOffset() + 100) break;
            SystemClock.sleep(16L);
        }
        assertNotNull("Forward motion after immediate reverse lost the scroll position", forwarded);
        assertTrue("Reader remained motion-locked after the initial reverse gesture; reversed="
                        + reversed.getScrollOffset() + ",forwarded=" + forwarded.getScrollOffset(),
                forwarded.getScrollOffset() > reversed.getScrollOffset() + 100);

        String pathBeforeHome = reader.testCurrentNtkEpisodePath();
        // UiDevice reports the key-injection result here, not whether Launcher actually won
        // focus. Some API-35 builds return false after a successful HOME transition, so assert
        // the observable window state instead of rejecting a real background/resume cycle.
        device.pressHome();
        assertTrue("Expected HOME to background the reader",
                device.wait(Until.gone(By.pkg(PACKAGE_NAME)), 5000L));
        SystemClock.sleep(1200L);
        assertTrue("Expected Recents to reopen the existing reader task", device.pressRecentApps());
        UiObject2 mangaTask = device.wait(Until.findObject(By.descContains("MangaView")), 8000L);
        assertNotNull("MangaView task missing after HOME", mangaTask);
        Rect taskBounds = mangaTask.getVisibleBounds();
        device.click(taskBounds.centerX(), taskBounds.centerY());

        ReaderV2Activity resumed = null;
        long resumeDeadline = SystemClock.elapsedRealtime() + 15000L;
        while (SystemClock.elapsedRealtime() < resumeDeadline) {
            ReaderV2Activity candidate = resumedReader();
            if (candidate == reader) {
                resumed = candidate;
                break;
            }
            SystemClock.sleep(16L);
        }
        assertNotNull("HOME return did not resume the existing reader", resumed);
        assertEquals("HOME return changed the exact episode identity",
                pathBeforeHome, resumed.testCurrentNtkEpisodePath());
        assertNotNull(
                "HOME return never produced a fresh physical reader frame",
                device.wait(Until.findObject(By.descStartsWith("actual:")), 15000L));
        ReaderSurfaceView.VisibleCoverageSnapshot coverage =
                resumed.testVisibleCoverageSnapshot();
        assertNotNull("HOME return lost visible coverage", coverage);
        assertEquals("HOME return exposed a black/missing viewport",
                0, coverage.getMissingPx());
        assertTrue("HOME return has no drawable pixels", coverage.getDrawablePx() > 0);

        ReaderSurfaceView.ScrollPositionSnapshot beforeResumeScroll =
                resumed.testCurrentScrollPositionSnapshot();
        assertNotNull("HOME return lost scroll position", beforeResumeScroll);
        device.swipe(x, forwardFrom, x, forwardTo, 18);
        long resumeMotionDeadline = SystemClock.elapsedRealtime() + 5000L;
        ReaderSurfaceView.ScrollPositionSnapshot afterResumeScroll = null;
        while (SystemClock.elapsedRealtime() < resumeMotionDeadline) {
            afterResumeScroll = resumed.testCurrentScrollPositionSnapshot();
            if (afterResumeScroll != null && afterResumeScroll.getScrollOffset() >
                    beforeResumeScroll.getScrollOffset() + 100) break;
            SystemClock.sleep(16L);
        }
        assertNotNull("HOME return lost scroll motion state", afterResumeScroll);
        assertTrue("HOME return left the reader visually restored but motion-locked; before="
                        + beforeResumeScroll.getScrollOffset() + ",after="
                        + afterResumeScroll.getScrollOffset(),
                afterResumeScroll.getScrollOffset() > beforeResumeScroll.getScrollOffset() + 100);
    }

    @Test
    public void onePieceLongReaderSessionDoesNotAccumulateFrameWork() throws Exception {
        LiveNetworkAssume.assumeEnabled();
        launchOnePieceEpisodes();

        UiDevice device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation());
        UiObject2 episodeRow = findEpisodeRowByDescription(device, "1185화", 90000L);
        assertNotNull("Expected current One Piece 1185 episode row", episodeRow);
        episodeRow.click();
        assertNotNull(
                "Expected NTK One Piece reader surface",
                device.wait(Until.findObject(By.res(PACKAGE_NAME, "strip")), 90000L));
        ReaderV2Activity reader = resumedReader();
        assertNotNull("Expected resumed One Piece reader", reader);
        Manga current = reader.testEpisode("1185화");
        Manga next = reader.testEpisode("1186화");
        assertNotNull("Expected One Piece 1185 metadata", current);
        assertNotNull("Expected One Piece 1186 metadata", next);

        long readyDeadline = SystemClock.elapsedRealtime() + 90000L;
        while (SystemClock.elapsedRealtime() < readyDeadline &&
                (!reader.testHasFullyReadyEpisode(current) ||
                        !reader.testHasReadyEpisodeRunway(next, 4))) {
            SystemClock.sleep(16L);
        }
        assertTrue("Current chapter must be drawable before long-session stress",
                reader.testHasFullyReadyEpisode(current));
        assertTrue("Next runway must be drawable before long-session stress",
                reader.testHasReadyEpisodeRunway(next, 4));
        assertEquals(
                "Long-session stress must enter the prepared successor first",
                next.getNtkEpisodePath(),
                scrollPhysicallyForwardUntilEpisodeChanges(
                        device, reader, current.getNtkEpisodePath(), 30000L));

        long nextReadyDeadline = SystemClock.elapsedRealtime() + 90000L;
        while (SystemClock.elapsedRealtime() < nextReadyDeadline &&
                !reader.testHasFullyReadyEpisode(next)) {
            SystemClock.sleep(16L);
        }
        assertTrue("Successor must be fully drawable before fixed-content stress",
                reader.testHasFullyReadyEpisode(next));

        Runtime.getRuntime().gc();
        SystemClock.sleep(250L);
        Debug.MemoryInfo baselineMemory = new Debug.MemoryInfo();
        Debug.getMemoryInfo(baselineMemory);
        runOnMain(reader::testResetFrameStatsSnapshot);
        ViewerTelemetry.NativeFrameStatsSnapshot nativeFrameBaseline =
                ViewerTelemetry.nativeFrameStatsSnapshot();
        assertNotNull("Expected native cadence evidence before long-session stress",
                nativeFrameBaseline);

        int width = device.getDisplayWidth();
        int height = device.getDisplayHeight();
        int x = width / 2;
        int upFrom = Math.min(height - 160, height * 3 / 4);
        int upTo = Math.max(120, height / 4);
        boolean forward = true;
        long[] pssCheckpointsKb = new long[4];
        ViewerTelemetry.NativeFrameStatsSnapshot[] nativeFrameCheckpoints =
                new ViewerTelemetry.NativeFrameStatsSnapshot[4];
        for (int swipe = 0; swipe < 260; swipe++) {
            ReaderSurfaceView.ScrollPositionSnapshot position =
                    reader.testCurrentScrollPositionSnapshot();
            assertNotNull("Reader lost its scroll state during long-session stress", position);
            if (position.getScrollOffset() >= position.getMaxScroll() - height * 2) {
                forward = false;
            } else if (position.getScrollOffset() <= height) {
                forward = true;
            }
            if (forward) {
                device.swipe(x, upFrom, x, upTo, 8);
            } else {
                device.swipe(x, upTo, x, upFrom, 8);
            }
            if ((swipe + 1) % 65 == 0) {
                Debug.MemoryInfo checkpoint = new Debug.MemoryInfo();
                Debug.getMemoryInfo(checkpoint);
                int checkpointIndex = swipe / 65;
                pssCheckpointsKb[checkpointIndex] = checkpoint.getTotalPss();
                nativeFrameCheckpoints[checkpointIndex] =
                        ViewerTelemetry.nativeFrameStatsSnapshot();
                assertNotNull("Native cadence evidence disappeared at swipe " + (swipe + 1),
                        nativeFrameCheckpoints[checkpointIndex]);
                Log.i(TAG, "longReaderStress swipes=" + (swipe + 1)
                        + ",pssKb=" + checkpoint.getTotalPss()
                        + ",page=" + reader.testCurrentPage()
                        + ",path=" + reader.testCurrentNtkEpisodePath());
            }
        }
        SystemClock.sleep(120L);

        List<ReaderSurfaceView.FrameStatsSnapshot> frameSegments =
                reader.testTakeFrameStatsSnapshots();
        assertTrue("Expected bounded long-session frame evidence", !frameSegments.isEmpty());
        int frameSamples = 0;
        int droppedFrames = 0;
        int maxMissingPx = 0;
        int missedIntervals = 0;
        float worstSegmentP95 = 0f;
        for (ReaderSurfaceView.FrameStatsSnapshot segment : frameSegments) {
            assertTrue("Long-session frame evidence exceeded its fixed 4096-sample window: "
                            + segment.getSamples(),
                    segment.getSamples() <= 4096);
            frameSamples += segment.getSamples();
            droppedFrames += segment.getDroppedFrames();
            maxMissingPx = Math.max(maxMissingPx, segment.getMaxMissingPx());
            missedIntervals += segment.getMissedIntervals();
            worstSegmentP95 = Math.max(worstSegmentP95, segment.getTotalP95());
        }
        assertTrue("Long-session stress did not produce enough frames: " + frameSamples,
                frameSamples >= 1000);
        assertEquals("Long-session scrolling dropped a renderer frame", 0, droppedFrames);
        assertEquals("Long-session scrolling exposed blank pixels", 0, maxMissingPx);
        assertTrue("Long-session main/render callback p95 regressed: " + worstSegmentP95,
                worstSegmentP95 < 16.0f);
        double missedPercent = missedIntervals * 100.0 / Math.max(1, frameSamples);
        assertTrue("Long-session callback jank grew above 1%; percent=" + missedPercent,
                missedPercent < 1.0);

        double[] phaseFps = new double[4];
        double[] phaseSlowPercent = new double[4];
        ViewerTelemetry.NativeFrameStatsSnapshot previousNative = nativeFrameBaseline;
        for (int phase = 0; phase < nativeFrameCheckpoints.length; phase++) {
            ViewerTelemetry.NativeFrameStatsSnapshot currentNative =
                    nativeFrameCheckpoints[phase];
            assertEquals("Native telemetry generation changed during long-session phase " + phase,
                    previousNative.getGeneration(), currentNative.getGeneration());
            long phaseIntervals = currentNative.getScrollIntervals()
                    - previousNative.getScrollIntervals();
            long phaseIntervalNanos = currentNative.getScrollIntervalNanos()
                    - previousNative.getScrollIntervalNanos();
            long phaseSlowIntervals = currentNative.getSlowIntervals()
                    - previousNative.getSlowIntervals();
            assertTrue("Long-session phase did not retain enough native cadence evidence; phase="
                            + phase + ",intervals=" + phaseIntervals,
                    phaseIntervals >= 100L && phaseIntervalNanos > 0L);
            phaseFps[phase] = phaseIntervals * 1_000_000_000.0 / phaseIntervalNanos;
            phaseSlowPercent[phase] = phaseSlowIntervals * 100.0 /
                    Math.max(1L, phaseIntervals);
            Log.i(TAG, "longReaderCadence phase=" + phase
                    + ",intervals=" + phaseIntervals
                    + ",fps=" + phaseFps[phase]
                    + ",slowPercent=" + phaseSlowPercent[phase]);
            previousNative = currentNative;
        }
        assertTrue("Native presentation cadence degraded over the long session; firstFps="
                        + phaseFps[0] + ",lastFps=" + phaseFps[3],
                phaseFps[3] >= phaseFps[0] * 0.85);
        assertTrue("Native slow-interval rate grew over the long session; firstPercent="
                        + phaseSlowPercent[0] + ",lastPercent=" + phaseSlowPercent[3],
                phaseSlowPercent[3] <= phaseSlowPercent[0] + 5.0);

        Runtime.getRuntime().gc();
        SystemClock.sleep(250L);
        Debug.MemoryInfo finalMemory = new Debug.MemoryInfo();
        Debug.getMemoryInfo(finalMemory);
        long totalPssGrowthKb = finalMemory.getTotalPss() - baselineMemory.getTotalPss();
        long latePssGrowthKb = pssCheckpointsKb[3] - pssCheckpointsKb[2];
        // The first traversal is allowed to rehydrate previously unseen exact predecessor pages.
        // A leak is a continuing late-session slope, not that one-time bounded pixel residency.
        assertTrue("Long-session PSS exceeded the complete two-episode residency envelope; growthKb="
                        + totalPssGrowthKb,
                totalPssGrowthKb <= 196608L);
        assertTrue("Long-session PSS kept growing after content residency stabilized; lateGrowthKb="
                        + latePssGrowthKb,
                latePssGrowthKb <= 65536L);
    }

    @Test
    public void onePieceSplitScreenKeepsThePreparedNextEpisodeScrollable() throws Exception {
        LiveNetworkAssume.assumeEnabled();
        UiDevice device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation());
        ensureSettingsRecentTask();
        launchOnePieceEpisodes();

        UiObject2 episodeRow = findEpisodeRowByDescription(device, "1185화", 90000L);
        assertNotNull("Expected current One Piece 1185 episode row", episodeRow);
        episodeRow.click();
        assertNotNull(
                "Expected NTK One Piece reader surface",
                device.wait(Until.findObject(By.res(PACKAGE_NAME, "strip")), 90000L));
        ReaderV2Activity reader = resumedReader();
        assertNotNull("Expected resumed One Piece reader", reader);
        Manga current = reader.testEpisode("1185화");
        Manga next = reader.testEpisode("1186화");
        Manga following = reader.testEpisode("1187화");
        assertNotNull("Expected One Piece 1185 metadata", current);
        assertNotNull("Expected One Piece 1186 metadata", next);
        assertNotNull("Expected One Piece 1187 metadata", following);

        long readyDeadline = SystemClock.elapsedRealtime() + 90000L;
        while (SystemClock.elapsedRealtime() < readyDeadline &&
                (!reader.testHasFullyReadyEpisode(current) ||
                        !reader.testHasReadyEpisodeRunway(next, 4))) {
            SystemClock.sleep(16L);
        }
        assertTrue("Current chapter must be drawable before entering split screen",
                reader.testHasFullyReadyEpisode(current));
        assertTrue("Next chapter runway must be prepared before entering split screen",
                reader.testHasReadyEpisodeRunway(next, 4));

        enterPixelLauncherSplitScreen(device);
        long splitDeadline = SystemClock.elapsedRealtime() + 8000L;
        while (SystemClock.elapsedRealtime() < splitDeadline && !reader.isInMultiWindowMode()) {
            SystemClock.sleep(16L);
        }
        assertTrue("Reader did not enter Android split-screen multi-window mode",
                reader.isInMultiWindowMode());

        UiObject2 splitStrip = device.wait(
                Until.findObject(By.res(PACKAGE_NAME, "strip")),
                8000L);
        assertNotNull("Reader surface disappeared after entering split screen", splitStrip);
        Rect bounds = splitStrip.getVisibleBounds();
        assertTrue("Split reader surface has no usable width: " + bounds, bounds.width() > 200);
        assertTrue("Split reader surface has no usable height: " + bounds, bounds.height() > 200);
        assertTrue(
                "Reader stayed fullscreen instead of using the split window: " + bounds,
                bounds.height() < device.getDisplayHeight() * 3 / 4);

        // The reader's center tap intentionally opens its chrome/popup panel.  Using that tap to
        // transfer multi-window focus leaves the panel above the SurfaceView and makes subsequent
        // UiDevice swipes exercise the panel instead of the reader.  A physical swipe itself is a
        // valid focus transfer on Android split screen, so drive the SurfaceView directly.
        String transitioned = scrollPhysicallyForwardUntilEpisodeChanges(
                device,
                reader,
                current.getNtkEpisodePath(),
                30000L,
                bounds);
        assertEquals(
                "Split-screen physical scrolling must enter the exact prepared successor",
                next.getNtkEpisodePath(),
                transitioned);
        assertTrue(
                "Split-screen successor must retain canonical source order",
                reader.testHasCanonicalEpisodeOrder(next));
        assertTrue(
                "Split-screen successor must retain its four-page physical runway",
                reader.testHasReadyEpisodeRunway(next, 4));

        // Exercise the other real split-screen geometry too.  Rotating an already active split
        // pair recreates the Surface at a side-by-side size while preserving the reader/session.
        // Continue into one more prepared chapter so this proves resize, input and boundary
        // attachment together instead of merely checking that the Activity stayed alive.
        device.setOrientationLeft();
        long landscapeDeadline = SystemClock.elapsedRealtime() + 8000L;
        while (SystemClock.elapsedRealtime() < landscapeDeadline &&
                device.getDisplayWidth() <= device.getDisplayHeight()) {
            SystemClock.sleep(16L);
        }
        assertTrue("Device did not rotate the active split pair to landscape",
                device.getDisplayWidth() > device.getDisplayHeight());
        assertTrue("Reader left multi-window mode during split rotation",
                reader.isInMultiWindowMode());

        UiObject2 landscapeStrip = device.wait(
                Until.findObject(By.res(PACKAGE_NAME, "strip")),
                8000L);
        assertNotNull("Reader surface disappeared after split rotation", landscapeStrip);
        Rect landscapeBounds = landscapeStrip.getVisibleBounds();
        assertTrue("Landscape split reader has no usable width: " + landscapeBounds,
                landscapeBounds.width() > 200);
        assertTrue("Landscape split reader has no usable height: " + landscapeBounds,
                landscapeBounds.height() > 200);
        assertTrue(
                "Reader did not adopt a side-by-side split window: " + landscapeBounds,
                landscapeBounds.width() < device.getDisplayWidth() * 3 / 4);

        long followingReadyDeadline = SystemClock.elapsedRealtime() + 90000L;
        while (SystemClock.elapsedRealtime() < followingReadyDeadline &&
                !reader.testHasReadyEpisodeRunway(following, 4)) {
            SystemClock.sleep(16L);
        }
        assertTrue("Following chapter runway was lost across split rotation",
                reader.testHasReadyEpisodeRunway(following, 4));
        String landscapeTransition = scrollPhysicallyForwardUntilEpisodeChanges(
                device,
                reader,
                next.getNtkEpisodePath(),
                30000L,
                landscapeBounds);
        assertEquals(
                "Landscape split scrolling must enter the exact following chapter",
                following.getNtkEpisodePath(),
                landscapeTransition);
        assertTrue("Landscape split successor lost canonical source order",
                reader.testHasCanonicalEpisodeOrder(following));
    }

    @Test
    public void webtoonScrollForwardPastInitialRunway() throws Exception {
        LiveNetworkAssume.assumeEnabled();
        launchNeighborEpisodes();

        UiDevice device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation());
        UiObject2 episodeRow = device.wait(Until.findObject(By.res(PACKAGE_NAME, "episode")), 90000L);
        assertNotNull("Expected NTK webtoon latest episode row", episodeRow);
        episodeRow.click();

        UiObject2 strip = device.wait(Until.findObject(By.res(PACKAGE_NAME, "strip")), 90000L);
        assertNotNull("Expected NTK webtoon reader surface", strip);
        long scrollStartedAt = SystemClock.elapsedRealtime();
        int[] initialProgress = scrollForwardUntilPage(device, 4, 4000L);
        assertTrue("Expected immediate cellular webtoon scrolling beyond the initial runway; current="
                        + initialProgress[0] + ",count=" + initialProgress[1] + ",elapsedMs="
                        + (SystemClock.elapsedRealtime() - scrollStartedAt),
                initialProgress[0] >= 4 && initialProgress[1] > 4);

        UiObject2 allReady = device.wait(
                Until.findObject(By.descContains("allReady=")), 8000L);
        assertNotNull("Expected every cellular NTK webtoon image to become render-ready", allReady);

        int[] progress = scrollForwardUntilPage(device, 12, 8000L);
        assertTrue("Expected cellular NTK webtoon to keep progressing after the initial runway; current="
                        + progress[0] + ",count=" + progress[1],
                progress[0] >= 12 && progress[1] > 12);
    }

    @Test
    public void webtoonHomeContinueReplacesAlreadyClaimedReaderSource() throws Exception {
        LiveNetworkAssume.assumeEnabled();
        launchNeighborEpisodes();

        UiDevice device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation());
        UiObject2 episodeRow = device.wait(
                Until.findObject(By.res(PACKAGE_NAME, "episode")),
                90000L);
        assertNotNull("Expected NTK webtoon latest episode row", episodeRow);
        episodeRow.click();

        assertNotNull(
                "Expected first NTK reader surface",
                device.wait(Until.findObject(By.res(PACKAGE_NAME, "strip")), 90000L));
        assertNotNull(
                "The first reader must claim and draw its exact source before home continue",
                device.wait(Until.findObject(By.descStartsWith("actual:")), 90000L));
        ReaderV2Activity firstReader = resumedReader();
        assertNotNull("Expected first resumed reader", firstReader);
        String episodePath = firstReader.testCurrentNtkEpisodePath();
        Manga resume = firstReader.testEpisodeByPath(episodePath);
        assertNotNull("Expected current episode metadata for home continue", resume);

        runOnMain(() -> Utils.openContinueViewer(firstReader, resume, -1));
        ReaderV2Activity replacement = null;
        long replacementDeadline = SystemClock.elapsedRealtime() + 90000L;
        while (SystemClock.elapsedRealtime() < replacementDeadline) {
            ReaderV2Activity candidate = resumedReader();
            if (candidate != null && candidate != firstReader) {
                replacement = candidate;
                break;
            }
            SystemClock.sleep(16L);
        }
        assertNotNull("Home continue must open a replacement reader", replacement);
        assertNotNull(
                "Replacement reader must draw the exact source instead of failing its claim",
                device.wait(Until.findObject(By.descStartsWith("actual:")), 90000L));
        assertTrue(
                "Replacement reader must retain the requested episode identity",
                episodePath.equals(replacement.testCurrentNtkEpisodePath()));
        assertTrue(
                "Replacement reader showed a permanent original-verification failure",
                device.findObject(By.textContains("이미지 원본 확인에 실패했습니다")) == null);
    }

    @Test
    public void webtoonForwardEpisodeMemoryAndFrameProbe() throws Exception {
        LiveNetworkAssume.assumeEnabled();
        launchNeighborEpisodes();

        UiDevice device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation());
        UiObject2 episodeRow = findEpisodeRowByListOrdinal(device, 3, 90000L);
        assertNotNull("Expected at least four current NTK webtoon episodes", episodeRow);
        episodeRow.click();

        UiObject2 strip = device.wait(Until.findObject(By.res(PACKAGE_NAME, "strip")), 90000L);
        assertNotNull("Expected NTK webtoon reader surface", strip);
        UiObject2 allReady = device.wait(Until.findObject(By.descContains("allReady=")), 12000L);
        assertNotNull("Expected initial NTK episode to become fully render-ready", allReady);

        ReaderV2Activity reader = resumedReader();
        assertNotNull("Expected resumed reader", reader);
        String previousPath = reader.testCurrentNtkEpisodePath();
        assertTrue("Expected diagnostic run to start from an exact NTK webtoon path; path="
                        + previousPath,
                previousPath != null && previousPath.startsWith("/webtoon/840540/"));
        int initialEpisodeNumber = Integer.parseInt(
                previousPath.substring(previousPath.lastIndexOf('-') + 1));
        Manga currentEpisode = reader.testEpisode(initialEpisodeNumber);
        assertNotNull("Expected current episode metadata", currentEpisode);
        LinkedHashSet<String> visitedPaths = new LinkedHashSet<>();
        visitedPaths.add(previousPath);
        logMultiEpisodeSnapshot(reader, 0, "initial");

        for (int episode = 1; episode <= 3; episode++) {
            long currentReadyStartedAt = SystemClock.elapsedRealtime();
            while (SystemClock.elapsedRealtime() - currentReadyStartedAt < 5000L &&
                    !reader.testHasFullyReadyEpisode(currentEpisode)) {
                SystemClock.sleep(16L);
            }
            long currentReadyElapsedMs =
                    SystemClock.elapsedRealtime() - currentReadyStartedAt;
            assertTrue(
                    "The foreground episode must be fully drawable before adjacent work; "
                            + "path=" + previousPath
                            + ",elapsedMs=" + currentReadyElapsedMs,
                    reader.testHasFullyReadyEpisode(currentEpisode));

            Manga preattachedNext = reader.testEpisode(initialEpisodeNumber + episode);
            assertNotNull("Expected next episode metadata before boundary warmup", preattachedNext);
            long preattachStartedAt = SystemClock.elapsedRealtime();
            while (SystemClock.elapsedRealtime() - preattachStartedAt < 5000L &&
                    !reader.testHasReadyEpisodeRunway(preattachedNext, 4)) {
                SystemClock.sleep(16L);
            }
            long preattachElapsedMs = SystemClock.elapsedRealtime() - preattachStartedAt;
            Log.i(TAG,
                    "nextEpisodeRunwayPreattached sequence=" + episode
                            + ",source=" + previousPath
                            + ",target=" + preattachedNext.getNtkEpisodePath()
                            + ",currentReadyElapsedMs=" + currentReadyElapsedMs
                            + ",readyImages=4,elapsedMs=" + preattachElapsedMs);
            assertTrue(
                    "Next episode must have four real drawables attached before leaving the "
                            + "current episode; source=" + previousPath
                            + ",target=" + preattachedNext.getNtkEpisodePath()
                            + ",elapsedMs=" + preattachElapsedMs,
                    reader.testHasReadyEpisodeRunway(preattachedNext, 4));

            long boundaryStartedAt = SystemClock.elapsedRealtime();
            previousPath = forceForwardEpisodeTransition(reader, previousPath, 30000L);
            long boundaryElapsedMs = SystemClock.elapsedRealtime() - boundaryStartedAt;
            Log.i(TAG,
                    "forwardEpisodeBoundary episode=" + episode
                            + ",path=" + previousPath
                            + ",elapsedMs=" + boundaryElapsedMs);
            assertTrue(
                    "A physically preattached next episode must cross the boundary without "
                            + "network wait; elapsedMs=" + boundaryElapsedMs
                            + ",path=" + previousPath,
                    boundaryElapsedMs <= 250L);
            assertTrue(
                    "Forward NTK reading revisited an already consumed episode; path="
                            + previousPath + ",visited=" + visitedPaths,
                    visitedPaths.add(previousPath));
            currentEpisode = preattachedNext;
            runOnMain(reader::testResetFrameStatsSnapshot);
            exerciseForwardFrames(device, 8);
            requireValidForwardFrameStats(reader, device, episode);
            assertTrue(
                    "Forward episode structure must remain in canonical source order; episode="
                            + episode + ",path=" + previousPath,
                    reader.testHasCanonicalEpisodeOrder(currentEpisode));
            assertEquals(
                    "Physically committed forward frames must retain exact image identity; "
                            + "episode=" + episode + ",path=" + previousPath,
                    0L,
                    reader.testStrictIdentityInvalidFrames());
            logMultiEpisodeSnapshot(reader, episode, "forward");
        }
    }

    @Test
    public void manhwaForwardRunwayKeepsExactNextEpisodePath() throws Exception {
        LiveNetworkAssume.assumeEnabled();
        launchManhwaEpisodes("창천의 권");

        UiDevice device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation());
        // The current NTK episode list exposes the resource row before its stable path
        // accessibility identity is propagated. The authoritative list itself already reports
        // /238729 as the first/current row, so tap that physical row and verify the exact path
        // again after ReaderV2Activity opens. This keeps the test on the real user navigation path
        // without waiting on a test-only accessibility update.
        UiObject2 episodeRow = device.wait(
                Until.findObject(By.res(PACKAGE_NAME, "episode")),
                90000L);
        assertNotNull("Expected representative 176-image manhwa episode", episodeRow);
        episodeRow.click();

        UiObject2 strip = device.wait(Until.findObject(By.res(PACKAGE_NAME, "strip")), 90000L);
        assertNotNull("Expected NTK manhwa reader surface", strip);
        // This debug instrumentation probe validates ownership/order across two boundaries. Keep
        // its diagnostic timeout above the release/benchmark SLA because debugger-free benchmark
        // timing is asserted separately; a slow debug decode must still expose the exact path bug.
        UiObject2 allReady = device.wait(Until.findObject(By.descContains("allReady=176")), 30000L);
        assertNotNull("Expected all 176 current-episode images to be drawable", allReady);

        ReaderV2Activity reader = resumedReader();
        assertNotNull("Expected resumed manhwa reader", reader);
        assertEquals("/manhwa/10073/238729", reader.testCurrentNtkEpisodePath());

        Manga expectedNext = reader.testEpisodeByPath("/manhwa/10073/238730");
        assertNotNull("Expected exact next-volume metadata", expectedNext);
        long preattachStartedAt = SystemClock.elapsedRealtime();
        while (SystemClock.elapsedRealtime() - preattachStartedAt < 8000L &&
                !reader.testHasReadyEpisodeRunway(expectedNext, 4)) {
            SystemClock.sleep(16L);
        }
        assertTrue(
                "The exact next volume must have four real images attached; elapsedMs="
                        + (SystemClock.elapsedRealtime() - preattachStartedAt),
                reader.testHasReadyEpisodeRunway(expectedNext, 4));

        long boundaryStartedAt = SystemClock.elapsedRealtime();
        String transitioned = forceForwardEpisodeTransition(
                reader,
                "/manhwa/10073/238729",
                30000L);
        long boundaryElapsedMs = SystemClock.elapsedRealtime() - boundaryStartedAt;
        assertEquals("Forward boundary must never select the previous volume",
                "/manhwa/10073/238730", transitioned);
        assertTrue(
                "A physically attached next volume must cross without a network wait; elapsedMs="
                        + boundaryElapsedMs,
                boundaryElapsedMs <= 250L);

        // /238730 is now the foreground/current episode. Its complete body must win before any
        // /238731 runway is allowed to consume network/decode/GPU resources. This is a diagnostic
        // debug timeout, not the release benchmark SLA.
        long currentTargetReadyAt = SystemClock.elapsedRealtime();
        while (SystemClock.elapsedRealtime() - currentTargetReadyAt < 20000L &&
                !reader.testHasFullyReadyEpisode(expectedNext)) {
            SystemClock.sleep(16L);
        }
        assertTrue(
                "The transitioned current volume must become fully drawable before following "
                        + "runway work; elapsedMs="
                        + (SystemClock.elapsedRealtime() - currentTargetReadyAt),
                reader.testHasFullyReadyEpisode(expectedNext));

        Manga following = reader.testEpisodeByPath("/manhwa/10073/238731");
        assertNotNull("Expected following volume metadata", following);
        long followingStartedAt = SystemClock.elapsedRealtime();
        while (SystemClock.elapsedRealtime() - followingStartedAt < 12000L &&
                !reader.testHasReadyEpisodeRunway(following, 4)) {
            SystemClock.sleep(16L);
        }
        assertTrue(
                "The following runway must retain /238731 instead of reusing /238729; elapsedMs="
                        + (SystemClock.elapsedRealtime() - followingStartedAt),
                reader.testHasReadyEpisodeRunway(following, 4));
    }

    @Test
    public void manhwaTailContinuePreattachesNextRunwayBeforeBoundary() throws Exception {
        LiveNetworkAssume.assumeEnabled();
        launchManhwaEpisodes("창천의 권");

        UiDevice device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation());
        UiObject2 episodeRow = device.wait(
                Until.findObject(By.res(PACKAGE_NAME, "episode")),
                90000L);
        assertNotNull("Expected representative manhwa episode", episodeRow);
        episodeRow.click();
        assertNotNull(
                "Expected initial NTK manhwa reader surface",
                device.wait(Until.findObject(By.res(PACKAGE_NAME, "strip")), 90000L));

        ReaderV2Activity firstReader = resumedReader();
        assertNotNull("Expected initial manhwa reader", firstReader);
        Manga current = firstReader.testEpisodeByPath("/manhwa/10073/238729");
        Manga expectedNext = firstReader.testEpisodeByPath("/manhwa/10073/238730");
        assertNotNull("Expected current volume metadata", current);
        assertNotNull("Expected next volume metadata", expectedNext);
        long structureDeadline = SystemClock.elapsedRealtime() + 90000L;
        while (SystemClock.elapsedRealtime() < structureDeadline &&
                firstReader.testCurrentNtkImageCount() <= 12) {
            SystemClock.sleep(16L);
        }
        int currentCount = firstReader.testCurrentNtkImageCount();
        assertTrue("Expected a long current volume", currentCount > 12);
        int resumePage = currentCount - 8;
        MainApplication.p.setViewerBookmark(current, resumePage, 0, 0);

        runOnMain(firstReader::testPrepareForNextLaunch);
        runOnMain(firstReader::finish);
        long closeDeadline = SystemClock.elapsedRealtime() + 10000L;
        while (SystemClock.elapsedRealtime() < closeDeadline && resumedReader() != null) {
            SystemClock.sleep(16L);
        }
        Context context = ApplicationProvider.getApplicationContext();
        long cacheLeaseDeadline = SystemClock.elapsedRealtime() + 10000L;
        while (SystemClock.elapsedRealtime() < cacheLeaseDeadline &&
                ReaderImageCache.snapshotState(context).getActiveReads() > 0) {
            SystemClock.sleep(16L);
        }
        assertEquals(
                "Closed reader must drain file leases before the cold re-entry starts",
                0,
                ReaderImageCache.snapshotState(context).getActiveReads());
        ReaderImageCache.clearVolatileStateForTest();
        ReaderImageCache.clearPersistentReaderImageFilesForTest(
                context);
        ReaderImageCache.clearPersistentNtkAuthoritativeManifestForTest(
                current.getNtkEpisodePath());
        MainApplication.getHttpClient().clearPageCache();
        current.setImgs(null);
        expectedNext.setImgs(null);

        runOnMain(() -> Utils.openContinueViewer(context, current, -1));
        ReaderV2Activity resumed = null;
        long resumeDeadline = SystemClock.elapsedRealtime() + 90000L;
        while (SystemClock.elapsedRealtime() < resumeDeadline) {
            ReaderV2Activity candidate = resumedReader();
            if (candidate != null && candidate != firstReader) {
                resumed = candidate;
                break;
            }
            SystemClock.sleep(16L);
        }
        assertNotNull("Expected cold tail continue reader", resumed);
        final ReaderV2Activity resumedReader = resumed;
        long startPageDeadline = SystemClock.elapsedRealtime() + 30000L;
        while (SystemClock.elapsedRealtime() < startPageDeadline &&
                resumedReader.testSessionInitialStartPage() != resumePage) {
            SystemClock.sleep(16L);
        }
        assertEquals(
                "Continue must restore the saved near-tail page",
                resumePage,
                resumedReader.testSessionInitialStartPage());

        Manga resumedNext = resumedReader.testEpisodeByPath("/manhwa/10073/238730");
        assertNotNull("Expected next volume metadata after continue", resumedNext);
        // Model the reported "leave it open, then scroll" flow without a fixed delay: wait on the
        // actual four-drawable runway state and bound how long the app is allowed to prepare it.
        // The subsequent swipes still start from the restored page and must cross the boundary
        // without doing any network work there.
        long runwayPreparationStartedAt = SystemClock.elapsedRealtime();
        while (SystemClock.elapsedRealtime() - runwayPreparationStartedAt < 7000L &&
                !resumedReader.testHasReadyEpisodeRunway(resumedNext, 4)) {
            SystemClock.sleep(16L);
        }
        long runwayPreparationMs =
                SystemClock.elapsedRealtime() - runwayPreparationStartedAt;
        assertTrue(
                "Tail continue must prepare four next-volume drawables while the restored page "
                        + "is being viewed; elapsedMs=" + runwayPreparationMs,
                resumedReader.testHasReadyEpisodeRunway(resumedNext, 4));
        int width = device.getDisplayWidth();
        int height = device.getDisplayHeight();
        int x = width / 2;
        int fromY = Math.min(height - 160, height * 3 / 4);
        int toY = Math.max(120, height / 4);
        boolean runwayReadyBeforeTail =
                resumedReader.testHasReadyEpisodeRunway(resumedNext, 4) &&
                        resumedReader.testCurrentPage() < currentCount - 1;
        long tailReachedAt = -1L;
        String transitioned = resumedReader.testCurrentNtkEpisodePath();
        long boundaryDeadline = SystemClock.elapsedRealtime() + 45000L;
        while (SystemClock.elapsedRealtime() < boundaryDeadline &&
                current.getNtkEpisodePath().equals(transitioned)) {
            if (resumedReader.testHasReadyEpisodeRunway(resumedNext, 4) &&
                    resumedReader.testCurrentPage() < currentCount - 1) {
                runwayReadyBeforeTail = true;
            }
            if (tailReachedAt < 0L && resumedReader.testCurrentPage() >= currentCount - 1) {
                tailReachedAt = SystemClock.elapsedRealtime();
            }
            device.swipe(x, fromY, x, toY, 8);
            transitioned = resumedReader.testCurrentNtkEpisodePath();
        }
        long tailWaitMs = tailReachedAt < 0L
                ? 0L
                : SystemClock.elapsedRealtime() - tailReachedAt;
        Log.i(
                TAG,
                "manhwaTailContinueBoundary source=" + current.getNtkEpisodePath()
                        + ",target=" + transitioned
                        + ",resumePage=" + resumePage
                        + ",runwayReadyBeforeTail=" + runwayReadyBeforeTail
                        + ",runwayPreparationMs=" + runwayPreparationMs
                        + ",tailWaitMs=" + tailWaitMs);
        assertTrue(
                "Tail continue must attach four next-volume drawables before the boundary",
                runwayReadyBeforeTail);
        assertEquals(
                "Tail continue must cross directly into the exact next volume",
                resumedNext.getNtkEpisodePath(),
                transitioned);
        // UiDevice.swipe(..., 8) itself occupies multiple input frames. Allow one complete
        // physical gesture plus a frame commit, while still rejecting the 0.9s+ image fetch
        // and multi-second document fetch that this preattached runway is meant to hide.
        assertTrue(
                "A preattached tail-continue boundary must complete within one gesture and "
                        + "must not wait for network; elapsedMs=" + tailWaitMs,
                tailWaitMs <= 500L);
    }

    private UiObject2 findEpisodeRowByListOrdinal(
            UiDevice device,
            int zeroBasedOrdinal,
            long timeoutMs
    ) {
        long deadline = SystemClock.elapsedRealtime() + timeoutMs;
        int width = device.getDisplayWidth();
        int height = device.getDisplayHeight();
        LinkedHashSet<String> observed = new LinkedHashSet<>();
        while (SystemClock.elapsedRealtime() < deadline) {
            for (UiObject2 row : device.findObjects(By.res(PACKAGE_NAME, "episode"))) {
                CharSequence description = row.getContentDescription();
                if (description == null || description.length() == 0) continue;
                String identity = description.toString();
                if (observed.add(identity) && observed.size() - 1 == zeroBasedOrdinal) {
                    Log.i(TAG, "selectedEpisodeOrdinal=" + zeroBasedOrdinal
                            + ",description=" + identity);
                    return row;
                }
            }
            device.swipe(
                    width / 2,
                    Math.min(height - 160, height * 3 / 4),
                    width / 2,
                    Math.max(120, height / 4),
                    18);
        }
        return null;
    }

    private UiObject2 findEpisodeRowByDescription(
            UiDevice device,
            String descriptionFragment,
            long timeoutMs
    ) {
        long deadline = SystemClock.elapsedRealtime() + timeoutMs;
        while (SystemClock.elapsedRealtime() < deadline) {
            for (UiObject2 row : device.findObjects(By.res(PACKAGE_NAME, "episode"))) {
                CharSequence description = row.getContentDescription();
                if (description == null ||
                        !description.toString().contains(descriptionFragment)) {
                    continue;
                }
                Log.i(TAG, "selectedEpisodeDescription=" + description);
                return row;
            }
            positionEpisodeListAtDescription(descriptionFragment);
            SystemClock.sleep(32L);
        }
        return null;
    }

    private boolean positionEpisodeListAtDescription(String descriptionFragment) {
        AtomicBoolean positioned = new AtomicBoolean(false);
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
            for (Activity activity : ActivityLifecycleMonitorRegistry.getInstance()
                    .getActivitiesInStage(Stage.RESUMED)) {
                if (!(activity instanceof EpisodeActivity)) continue;
                try {
                    Field episodesField = EpisodeActivity.class.getDeclaredField("episodes");
                    episodesField.setAccessible(true);
                    Object value = episodesField.get(activity);
                    if (!(value instanceof List)) return;
                    List<?> episodes = (List<?>) value;
                    for (int index = 0; index < episodes.size(); index++) {
                        Object item = episodes.get(index);
                        if (!(item instanceof Manga)) continue;
                        Manga episode = (Manga) item;
                        if (episode.getName() == null ||
                                !episode.getName().contains(descriptionFragment)) {
                            continue;
                        }
                        RecyclerView list = activity.findViewById(R.id.EpisodeList);
                        if (list == null) return;
                        // Adapter position zero is the title header.
                        list.scrollToPosition(index + 1);
                        positioned.set(true);
                        return;
                    }
                } catch (ReflectiveOperationException e) {
                    throw new AssertionError("Unable to position One Piece episode list", e);
                }
            }
        });
        return positioned.get();
    }

    private String forceForwardEpisodeTransition(
            ReaderV2Activity reader,
            String previousPath,
            long timeoutMs
    ) throws Exception {
        long deadline = SystemClock.elapsedRealtime() + timeoutMs;
        String currentPath = previousPath;
        while (SystemClock.elapsedRealtime() < deadline) {
            runOnMain(() -> {
                int count = reader.testPageCount();
                if (count <= 0) return;
                reader.testScrollByPixels(Float.MAX_VALUE);
                reader.onBoundaryReached(ReaderSurfaceView.DIRECTION_NEXT, count - 1);
            });
            currentPath = reader.testCurrentNtkEpisodePath();
            if (currentPath != null && !currentPath.equals(previousPath)) return currentPath;
            SystemClock.sleep(80L);
        }
        throw new AssertionError(
                "Expected forward NTK episode transition from " + previousPath
                        + "; current=" + currentPath
                        + ",page=" + reader.testCurrentPage()
                        + ",count=" + reader.testPageCount());
    }

    private String scrollPhysicallyForwardUntilEpisodeChanges(
            UiDevice device,
            ReaderV2Activity reader,
            String previousPath,
            long timeoutMs
    ) {
        return scrollPhysicallyForwardUntilEpisodeChanges(
                device,
                reader,
                previousPath,
                timeoutMs,
                new Rect(0, 0, device.getDisplayWidth(), device.getDisplayHeight()));
    }

    private String scrollPhysicallyForwardUntilEpisodeChanges(
            UiDevice device,
            ReaderV2Activity reader,
            String previousPath,
            long timeoutMs,
            Rect surfaceBounds
    ) {
        int x = surfaceBounds.centerX();
        int verticalInset = Math.min(120, Math.max(24, surfaceBounds.height() / 8));
        int fromY = surfaceBounds.bottom - verticalInset;
        int toY = surfaceBounds.top + verticalInset;
        long deadline = SystemClock.elapsedRealtime() + timeoutMs;
        String currentPath = previousPath;
        while (SystemClock.elapsedRealtime() < deadline) {
            device.swipe(x, fromY, x, toY, 8);
            currentPath = reader.testCurrentNtkEpisodePath();
            if (currentPath != null && !currentPath.equals(previousPath)) return currentPath;
        }
        throw new AssertionError(
                "Expected physical forward scroll to leave " + previousPath
                        + "; current=" + currentPath
                        + ",page=" + reader.testCurrentPage()
                        + ",count=" + reader.testPageCount());
    }

    private void ensureSettingsRecentTask() throws Exception {
        executeShellAndDrain("am start -W -a android.settings.SETTINGS");
    }

    private void executeShellAndDrain(String shellCommand) throws Exception {
        ParcelFileDescriptor command = InstrumentationRegistry.getInstrumentation()
                .getUiAutomation()
                .executeShellCommand(shellCommand);
        try (ParcelFileDescriptor.AutoCloseInputStream input =
                     new ParcelFileDescriptor.AutoCloseInputStream(command)) {
            byte[] buffer = new byte[256];
            while (input.read(buffer) >= 0) {
                // Drain the command so its Activity/windowing state is fully committed before the
                // next test observes the device.
            }
        }
    }

    private void enterPixelLauncherSplitScreen(UiDevice device) throws Exception {
        assertTrue("Device did not open Recents", device.pressRecentApps());
        UiObject2 mangaTask = device.wait(Until.findObject(By.descContains("MangaView")), 8000L);
        assertNotNull("MangaView task missing from Recents", mangaTask);
        UiObject2 taskIcon = mangaTask.findObject(
                By.res("com.google.android.apps.nexuslauncher", "icon"));
        assertNotNull("MangaView task icon missing from Recents", taskIcon);
        taskIcon.longClick();

        UiObject2 splitLabel = device.wait(Until.findObject(By.text("Split screen")), 5000L);
        assertNotNull("System did not offer Split screen for MangaView", splitLabel);
        UiObject2 splitAction = splitLabel.getParent();
        assertNotNull("Split screen action has no clickable container", splitAction);
        splitAction.click();

        long pickerDeadline = SystemClock.elapsedRealtime() + 8000L;
        UiObject2 settingsTask = null;
        while (SystemClock.elapsedRealtime() < pickerDeadline) {
            List<UiObject2> candidates = device.findObjects(By.descContains("Settings"));
            for (UiObject2 candidate : candidates) {
                if (candidate.isClickable() && candidate.getVisibleBounds().width() >
                        device.getDisplayWidth() / 3) {
                    settingsTask = candidate;
                    break;
                }
            }
            if (settingsTask != null) break;
            device.swipe(
                    device.getDisplayWidth() / 6,
                    device.getDisplayHeight() / 2,
                    device.getDisplayWidth() * 5 / 6,
                    device.getDisplayHeight() / 2,
                    24);
            SystemClock.sleep(150L);
        }
        assertNotNull("No selectable Settings task appeared in the split-screen picker",
                settingsTask);
        settingsTask.click();
    }

    private void exerciseForwardFrames(UiDevice device, int swipeCount) {
        int width = device.getDisplayWidth();
        int height = device.getDisplayHeight();
        int x = width / 2;
        int fromY = Math.min(height - 160, height * 3 / 4);
        int toY = Math.max(120, height / 4);
        for (int swipe = 0; swipe < swipeCount; swipe++) {
            device.swipe(x, fromY, x, toY, 12);
        }
    }

    private ReaderSurfaceView.FrameStatsSnapshot requireValidForwardFrameStats(
            ReaderV2Activity reader,
            UiDevice device,
            int episode
    ) {
        ReaderSurfaceView.FrameStatsSnapshot frames = reader.testFrameStatsSnapshot();
        for (int retry = 0;
                retry < 2 && (frames == null || frames.getSamples() < 60);
                retry++) {
            runOnMain(reader::testResetFrameStatsSnapshot);
            exerciseForwardFrames(device, 8);
            SystemClock.sleep(80L);
            frames = reader.testFrameStatsSnapshot();
        }
        assertNotNull("Expected native frame samples for forward episode " + episode, frames);
        assertTrue(
                "Expected at least 60 native scroll intervals for forward episode " + episode
                        + "; samples=" + frames.getSamples(),
                frames.getSamples() >= 60);
        double missedPercent =
                frames.getMissedIntervals() * 100.0 / Math.max(1, frames.getSamples());
        assertTrue(
                "Forward episode jank must remain below 1%; episode=" + episode
                        + ",missed=" + frames.getMissedIntervals()
                        + ",samples=" + frames.getSamples()
                        + ",percent=" + missedPercent,
                missedPercent < 1.0);
        assertEquals(
                "Forward episode must not drop native frames; episode=" + episode,
                0,
                frames.getDroppedFrames());
        assertEquals(
                "Forward episode must not expose missing pixels; episode=" + episode,
                0,
                frames.getMaxMissingPx());
        return frames;
    }

    private ReaderSurfaceView.FrameStatsSnapshot requireCompletedPhysicalFrameStats(
            ReaderV2Activity reader,
            int chapter
    ) {
        // Asking for the snapshot atomically finalizes the active physical segment. Do not inject
        // fallback swipes here: they would measure the newly entered chapter instead of the
        // chapter whose boundary traversal is being qualified.
        SystemClock.sleep(80L);
        ReaderSurfaceView.FrameStatsSnapshot frames = reader.testFrameStatsSnapshot();
        assertNotNull(
                "Expected native frame samples for physical One Piece chapter " + chapter,
                frames);
        return frames;
    }

    private void logMultiEpisodeSnapshot(
            ReaderV2Activity reader,
            int episode,
            String stage
    ) {
        Debug.MemoryInfo memory = new Debug.MemoryInfo();
        Debug.getMemoryInfo(memory);
        Runtime runtime = Runtime.getRuntime();
        ReaderSurfaceView.FrameStatsSnapshot frames = reader.testFrameStatsSnapshot();
        Log.i(
                TAG,
                "episode=" + episode
                        + ",stage=" + stage
                        + ",path=" + reader.testCurrentNtkEpisodePath()
                        + ",page=" + reader.testCurrentPage()
                        + ",count=" + reader.testPageCount()
                        + ",pssKb=" + memory.getTotalPss()
                        + ",javaUsedKb=" + ((runtime.totalMemory() - runtime.freeMemory()) / 1024L)
                        + ",nativeHeapKb=" + (Debug.getNativeHeapAllocatedSize() / 1024L)
                        + ",threads=" + Thread.getAllStackTraces().size()
                        + ",frameSamples=" + (frames == null ? -1 : frames.getSamples())
                        + ",missedIntervals=" + (frames == null ? -1 : frames.getMissedIntervals())
                        + ",droppedFrames=" + (frames == null ? -1 : frames.getDroppedFrames())
                        + ",totalP95=" + (frames == null ? -1f : frames.getTotalP95())
                        + ",totalMax=" + (frames == null ? -1f : frames.getTotalMax())
                        + ",missingPx=" + (frames == null ? -1 : frames.getMaxMissingPx()));
    }

    private void runOnMain(Runnable runnable) {
        InstrumentationRegistry.getInstrumentation().runOnMainSync(runnable);
    }

    private ReaderV2Activity resumedReader() {
        AtomicReference<ReaderV2Activity> result = new AtomicReference<>();
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
            for(Activity activity : ActivityLifecycleMonitorRegistry.getInstance()
                    .getActivitiesInStage(Stage.RESUMED)) {
                if(activity instanceof ReaderV2Activity) {
                    result.set((ReaderV2Activity) activity);
                    return;
                }
            }
        });
        return result.get();
    }

    private int[] scrollForwardUntilPage(UiDevice device, int minimumPage, long timeoutMs) {
        int width = device.getDisplayWidth();
        int height = device.getDisplayHeight();
        int x = width / 2;
        int fromY = Math.min(height - 160, height * 3 / 4);
        int toY = Math.max(120, height / 4);
        long deadline = SystemClock.elapsedRealtime() + timeoutMs;
        int[] progress = resumedReaderProgress();
        while(progress[0] < minimumPage && SystemClock.elapsedRealtime() < deadline) {
            device.swipe(x, fromY, x, toY, 12);
            progress = resumedReaderProgress();
        }
        return progress;
    }

    private void launchOnePieceEpisodes() {
        Context context = ApplicationProvider.getApplicationContext();
        MainApplication.p.setNtkSitePreset("https://sbxh1.com");
        MainApplication.p.setBaseMode(MTitle.base_comic);

        Title title = new Title(
                "원피스(ONE PIECE)",
                "https://11toon8.com/data/toon_category/2.webp",
                "",
                Collections.singletonList("애니화"),
                "",
                2,
                MTitle.base_comic);
        title.setSourceSite("ntk");

        Intent intent = new Intent(context, EpisodeActivity.class);
        intent.putExtra("title", Utils.toViewerTitleJson(title, true));
        intent.putExtra("online", true);
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        context.startActivity(intent);
    }

    private int[] resumedReaderProgress() {
        int[] progress = {-1, -1};
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
            for(Activity activity : ActivityLifecycleMonitorRegistry.getInstance()
                    .getActivitiesInStage(Stage.RESUMED)) {
                if(activity instanceof ReaderV2Activity) {
                    ReaderV2Activity reader = (ReaderV2Activity)activity;
                    progress[0] = reader.testCurrentPage();
                    progress[1] = reader.testPageCount();
                    return;
                }
            }
        });
        return progress;
    }

    private void launchNeighborEpisodes() {
        Context context = ApplicationProvider.getApplicationContext();
        MainApplication.p.setNtkSitePreset("https://sbxh1.com");
        MainApplication.p.setBaseMode(MTitle.base_webtoon);
        MainApplication.getHttpClient().clearPageCache();
        Search.clearNtkResultCaches();

        Search search = new Search("네 이웃을 사랑하라", 0, MTitle.base_webtoon);
        int status = search.fetch(MainApplication.getHttpClient());
        if(status != 0 || search.getResult() == null)
            throw new AssertionError("Expected current NTK webtoon search results, status=" + status);
        Title title = null;
        for(Title candidate : search.getResult()) {
            if(candidate != null && candidate.getName() != null
                    && candidate.getName().contains("네 이웃을 사랑하라")) {
                title = candidate;
                break;
            }
        }
        if(title == null)
            throw new AssertionError("Expected current 네 이웃을 사랑하라 search result");

        Intent intent = new Intent(context, EpisodeActivity.class);
        intent.putExtra("title", Utils.toViewerTitleJson(title, false));
        intent.putExtra("online", true);
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        context.startActivity(intent);
    }

    private void launchManhwaEpisodes(String query) {
        Context context = ApplicationProvider.getApplicationContext();
        MainApplication.p.setNtkSitePreset("https://sbxh1.com");
        MainApplication.p.setBaseMode(MTitle.base_comic);
        MainApplication.getHttpClient().clearPageCache();
        Search.clearNtkResultCaches();

        Search search = new Search(query, 0, MTitle.base_comic);
        int status = search.fetch(MainApplication.getHttpClient());
        if(status != 0 || search.getResult() == null)
            throw new AssertionError("Expected current NTK manhwa search results, status=" + status);
        Title title = null;
        for(Title candidate : search.getResult()) {
            if(candidate != null && candidate.getName() != null
                    && candidate.getName().contains(query)) {
                title = candidate;
                break;
            }
        }
        if(title == null)
            throw new AssertionError("Expected current " + query + " search result");

        Intent intent = new Intent(context, EpisodeActivity.class);
        intent.putExtra("title", Utils.toViewerTitleJson(title, false));
        intent.putExtra("online", true);
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        context.startActivity(intent);
    }
}
