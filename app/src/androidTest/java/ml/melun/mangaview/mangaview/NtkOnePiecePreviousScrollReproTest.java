package ml.melun.mangaview.mangaview;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.os.Debug;
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
            while (SystemClock.elapsedRealtime() - preattachStartedAt < 12000L &&
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
        boolean runwayReadyBeforeTail = reader.testHasReadyEpisodeRunway(next, 4);
        long tailReachedAt = -1L;
        long deadline = SystemClock.elapsedRealtime() + 30000L;
        String transitioned = current.getNtkEpisodePath();
        while (SystemClock.elapsedRealtime() < deadline) {
            device.swipe(x, fromY, x, toY, 8);
            if (reader.testHasReadyEpisodeRunway(next, 4) &&
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
        Log.i(
                TAG,
                "onePieceImmediateFastForwardBoundary source=" + current.getNtkEpisodePath()
                        + ",target=" + transitioned
                        + ",runwayReadyBeforeTail=" + runwayReadyBeforeTail
                        + ",tailWaitMs=" + tailWaitMs
                        + ",initialCount=" + initialCurrentPageCount);
        assertTrue(
                "The next One Piece runway must be physically attached before the current tail",
                runwayReadyBeforeTail);
        assertEquals(next.getNtkEpisodePath(), transitioned);
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
        while (SystemClock.elapsedRealtime() - preattachStartedAt < 12000L &&
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
                    segmentWorstSlowIntervalNanos >= 0L &&
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
        double aggregateMissedPercent =
                totalNativeSlowIntervals * 100.0 / Math.max(1L, totalNativeIntervals);
        assertTrue(
                "Expected at least 150 committed native intervals across the physical One Piece "
                        + "1185..1188 traversal; intervals=" + totalNativeIntervals,
                totalNativeIntervals >= 150L);
        assertTrue(
                "One Piece latest physical forward jank must remain below 1% across the complete "
                        + "1185..1188 traversal; slow=" + totalNativeSlowIntervals
                        + ",intervals=" + totalNativeIntervals
                        + ",percent=" + aggregateMissedPercent,
                aggregateMissedPercent < 1.0);
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
        ReaderImageCache.clearVolatileStateForTest();
        ReaderImageCache.clearPersistentReaderImageFilesForTest(
                ApplicationProvider.getApplicationContext());
        ReaderImageCache.clearPersistentNtkAuthoritativeManifestForTest(
                current.getNtkEpisodePath());
        MainApplication.getHttpClient().clearPageCache();
        current.setImgs(null);
        expectedNext.setImgs(null);

        Context context = ApplicationProvider.getApplicationContext();
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
        int width = device.getDisplayWidth();
        int height = device.getDisplayHeight();
        int x = width / 2;
        int fromY = Math.min(height - 160, height * 3 / 4);
        int toY = Math.max(120, height / 4);
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
