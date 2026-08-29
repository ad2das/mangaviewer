package ml.melun.mangaview.mangaview;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
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
import androidx.test.uiautomator.StaleObjectException;
import androidx.test.uiautomator.UiDevice;
import androidx.test.uiautomator.UiObject2;
import androidx.test.uiautomator.Until;
import androidx.recyclerview.widget.RecyclerView;

import org.junit.Test;
import org.junit.After;
import org.junit.runner.RunWith;

import java.lang.reflect.Field;
import java.util.ArrayList;
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
    private static final String PIXEL_LAUNCHER_PACKAGE =
            "com.google.android.apps.nexuslauncher";
    private static final int TEN_CHAPTER_START = 1178;
    private static final int TEN_CHAPTER_FORWARD_TRANSITIONS = 10;
    private static final float STRICT_SCROLL_P95_MS = 16.0f;
    private static final double STRICT_SCROLL_MISSED_PERCENT = 1.0;
    private static final int FAST_PHYSICAL_SWIPE_STEPS = 8;
    private static final int SUSTAINED_READING_SWIPE_STEPS = 64;
    private static final float COMMITTED_SCROLL_COHERENCE_PX = 1.0f;

    private static final class PhysicalViewportEvidence {
        final ReaderSurfaceView.ScrollPositionSnapshot position;
        final ReaderV2Activity.CleanPhysicalSourceSnapshot physical;
        final ReaderSurfaceView.VisibleCoverageSnapshot coverage;
        final ReaderSurfaceView.LifecycleViewportAnchorSnapshot committedAnchor;
        final boolean revealPending;

        PhysicalViewportEvidence(
                ReaderSurfaceView.ScrollPositionSnapshot position,
                ReaderV2Activity.CleanPhysicalSourceSnapshot physical,
                ReaderSurfaceView.VisibleCoverageSnapshot coverage,
                ReaderSurfaceView.LifecycleViewportAnchorSnapshot committedAnchor,
                boolean revealPending
        ) {
            this.position = position;
            this.physical = physical;
            this.coverage = coverage;
            this.committedAnchor = committedAnchor;
            this.revealPending = revealPending;
        }
    }

    private static final class ForwardScrollSample {
        final int currentPage;
        final int pageCount;
        final ReaderSurfaceView.ScrollPositionSnapshot position;

        ForwardScrollSample(
                int currentPage,
                int pageCount,
                ReaderSurfaceView.ScrollPositionSnapshot position
        ) {
            this.currentPage = currentPage;
            this.pageCount = pageCount;
            this.position = position;
        }
    }

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
        ReaderV2Activity reader = resumedReader();
        assertNotNull("Expected resumed cellular manga reader", reader);
        assertViewportRemainsStationaryThroughDeferredGeometrySettlement(reader);
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
    public void onePieceTailWaitAttachesNextWithoutASecondGesture() throws Exception {
        LiveNetworkAssume.assumeEnabled();
        launchOnePieceEpisodes();

        UiDevice device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation());
        UiObject2 episodeRow = findEpisodeRowByDescription(device, "1185화", 90000L);
        assertNotNull("Expected current One Piece 1185 episode row", episodeRow);
        episodeRow.click();
        assertNotNull(
                "Expected NTK reader surface",
                device.wait(Until.findObject(By.res(PACKAGE_NAME, "strip")), 90000L));

        ReaderV2Activity reader = resumedReader();
        assertNotNull("Expected resumed One Piece reader", reader);
        Manga current = reader.testEpisode("1185화");
        Manga next = reader.testEpisode("1186화");
        assertNotNull("Expected current One Piece 1185 metadata", current);
        assertNotNull("Expected next One Piece 1186 metadata", next);

        long structureDeadline = SystemClock.elapsedRealtime() + 15000L;
        while (SystemClock.elapsedRealtime() < structureDeadline && reader.testPageCount() <= 1) {
            SystemClock.sleep(16L);
        }
        int currentCount = reader.testPageCount();
        assertTrue("Expected a multi-page current episode", currentCount > 1);

        String transitioned = current.getNtkEpisodePath();
        boolean runwayReadyBeforeTail = false;
        // Publish exactly one edge interaction. UiAutomator swipes are intentionally not used:
        // they can be ignored while an emulator is still settling layout, which tests gesture
        // injection rather than the lost-wakeup this case owns. The production scroll setter and
        // production boundary callback below are the two halves of one physical edge event.
        long oneEdgeStartedAt = SystemClock.elapsedRealtime();
        runOnMain(() -> {
            reader.testScrollByPixels(Float.MAX_VALUE);
            reader.onBoundaryReached(ReaderSurfaceView.DIRECTION_NEXT, currentCount - 1);
        });
        long tailDeadline = SystemClock.elapsedRealtime() + 5000L;
        while (SystemClock.elapsedRealtime() < tailDeadline &&
                reader.testCurrentPage() < currentCount - 1) {
            SystemClock.sleep(16L);
        }
        boolean stoppedAtTail = reader.testCurrentPage() >= currentCount - 1;
        runwayReadyBeforeTail = reader.testHasReadyEpisodeRunway(next, 1) && !stoppedAtTail;
        assertTrue("The one edge interaction must park the reader at the old tail", stoppedAtTail);

        // Once the original physical tail is reached, send no more input. This is the reported
        // failure mode: maxScroll cannot emit another edge transition, so the retained request
        // itself must wake when current completion/manifest publication catches up.
        long oneEdgeProofDeadline = oneEdgeStartedAt + 90000L;
        ReaderSurfaceView.VisibleCoverageSnapshot oneEdgeCoverage = null;
        boolean exactNextPath = false;
        boolean fourPageRunwayReady = false;
        boolean nextP0PhysicallyPresented = false;
        boolean currentPageIsNextBody = false;
        boolean cleanNextBodyFrame = false;
        while (SystemClock.elapsedRealtime() < oneEdgeProofDeadline) {
            transitioned = reader.testCurrentNtkEpisodePath();
            exactNextPath = next.getNtkEpisodePath().equals(transitioned);
            fourPageRunwayReady = reader.testHasReadyEpisodeRunway(next, 4);
            nextP0PhysicallyPresented =
                    reader.testHasPhysicallyPresentedEpisodeSource(next, 0);
            // The first appended slot is the intentional episode-divider card; p0 starts at the
            // following display index. A sliver of that divider may remain visible while p0 is the
            // current page, so distinguish a real card stall by the physical current-page index.
            currentPageIsNextBody = reader.testCurrentPage() > currentCount;
            oneEdgeCoverage = reader.testVisibleCoverageSnapshot();
            cleanNextBodyFrame = oneEdgeCoverage != null &&
                    oneEdgeCoverage.getDrawablePx() > 0 &&
                    oneEdgeCoverage.getMissingPx() == 0 &&
                    oneEdgeCoverage.getPlaceholderPx() == 0 &&
                    oneEdgeCoverage.getVisibleLoading() == 0 &&
                    oneEdgeCoverage.getVisibleErrors() == 0 &&
                    oneEdgeCoverage.getVisibleCards() <= 1 &&
                    !reader.testNativeSurfaceRevealPending();
            if (exactNextPath && fourPageRunwayReady && nextP0PhysicallyPresented &&
                    currentPageIsNextBody && cleanNextBodyFrame) {
                break;
            }
            SystemClock.sleep(16L);
        }
        // Re-sample once at the deadline edge so a physical commit racing the final sleep cannot
        // leave the local path stale even though the compositor has already presented next p0.
        transitioned = reader.testCurrentNtkEpisodePath();
        exactNextPath = next.getNtkEpisodePath().equals(transitioned);
        fourPageRunwayReady = reader.testHasReadyEpisodeRunway(next, 4);
        nextP0PhysicallyPresented = reader.testHasPhysicallyPresentedEpisodeSource(next, 0);
        currentPageIsNextBody = reader.testCurrentPage() > currentCount;
        oneEdgeCoverage = reader.testVisibleCoverageSnapshot();
        cleanNextBodyFrame = oneEdgeCoverage != null &&
                oneEdgeCoverage.getDrawablePx() > 0 &&
                oneEdgeCoverage.getMissingPx() == 0 &&
                oneEdgeCoverage.getPlaceholderPx() == 0 &&
                oneEdgeCoverage.getVisibleLoading() == 0 &&
                oneEdgeCoverage.getVisibleErrors() == 0 &&
                oneEdgeCoverage.getVisibleCards() <= 1 &&
                !reader.testNativeSurfaceRevealPending();
        long idleAttachMs = SystemClock.elapsedRealtime() - oneEdgeStartedAt;

        Log.i(
                TAG,
                "onePieceTailWaitBoundary source=" + current.getNtkEpisodePath()
                        + ",target=" + transitioned
                        + ",runwayReadyBeforeTail=" + runwayReadyBeforeTail
                        + ",stoppedAtTail=" + stoppedAtTail
                        + ",idleAttachMs=" + idleAttachMs
                        + ",exactNextPath=" + exactNextPath
                        + ",fourPageRunwayReady=" + fourPageRunwayReady
                        + ",nextP0PhysicallyPresented=" + nextP0PhysicallyPresented
                        + ",currentPageIsNextBody=" + currentPageIsNextBody
                        + ",cleanNextBodyFrame=" + cleanNextBodyFrame
                        + ",currentCount=" + currentCount);
        assertEquals(
                "The exact next episode must attach without a second edge gesture",
                next.getNtkEpisodePath(),
                transitioned);
        assertTrue(
                "The attached next episode must expose four drawable runway pages",
                fourPageRunwayReady);
        assertTrue(
                "The one-edge transition must physically present the exact next p0",
                nextP0PhysicallyPresented);
        assertTrue(
                "The one-edge transition remained on the episode-divider card",
                currentPageIsNextBody);
        assertNotNull("The one-edge transition has no physical coverage snapshot", oneEdgeCoverage);
        assertTrue("The one-edge transition has no drawable pixels",
                oneEdgeCoverage.getDrawablePx() > 0);
        assertEquals("The one-edge transition has missing pixels", 0,
                oneEdgeCoverage.getMissingPx());
        assertEquals("The one-edge transition exposed placeholders", 0,
                oneEdgeCoverage.getPlaceholderPx());
        assertEquals("The one-edge transition is still loading", 0,
                oneEdgeCoverage.getVisibleLoading());
        assertEquals("The one-edge transition exposed an error", 0,
                oneEdgeCoverage.getVisibleErrors());
        assertTrue("The one-edge transition exposed more than its single episode divider",
                oneEdgeCoverage.getVisibleCards() <= 1);
        assertTrue("The one-edge transition never produced a clean next-body frame",
                cleanNextBodyFrame);
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
        UiObject2 episodeRow = findEpisodeRowByDescription(
                device,
                TEN_CHAPTER_START + "화",
                90000L);
        assertNotNull("Expected One Piece " + TEN_CHAPTER_START + " episode row", episodeRow);
        episodeRow.click();

        UiObject2 strip = device.wait(Until.findObject(By.res(PACKAGE_NAME, "strip")), 90000L);
        assertNotNull("Expected NTK One Piece reader surface", strip);
        ReaderV2Activity reader = resumedReader();
        assertNotNull("Expected resumed One Piece reader", reader);
        List<Manga> sequence = new ArrayList<>();
        LinkedHashSet<String> expectedPaths = new LinkedHashSet<>();
        for (int offset = 0; offset <= TEN_CHAPTER_FORWARD_TRANSITIONS; offset++) {
            int chapter = TEN_CHAPTER_START + offset;
            Manga episode = reader.testEpisodeInCurrentSequence(chapter + "화");
            if (episode == null) episode = reader.testEpisode(chapter + "화");
            assertNotNull("Expected consecutive One Piece " + chapter + " metadata", episode);
            assertNotNull("Expected exact One Piece " + chapter + " path",
                    episode.getNtkEpisodePath());
            assertTrue("Ten-chapter fixture contains a duplicate path at " + chapter + ": "
                            + episode.getNtkEpisodePath(),
                    expectedPaths.add(episode.getNtkEpisodePath()));
            sequence.add(episode);
        }
        assertEquals("Ten forward transitions require eleven distinct episode identities",
                TEN_CHAPTER_FORWARD_TRANSITIONS + 1, expectedPaths.size());

        Manga current = sequence.get(0);
        assertEquals("Reader did not launch the requested ten-chapter start",
                current.getNtkEpisodePath(), reader.testCurrentNtkEpisodePath());
        LinkedHashSet<String> visitedPaths = new LinkedHashSet<>();
        visitedPaths.add(current.getNtkEpisodePath());
        long totalNativeIntervals = 0L;
        long totalNativeSlowIntervals = 0L;
        long totalCallbackIntervals = 0L;
        long totalCallbackMissedIntervals = 0L;
        long worstNativeSlowIntervalNanos = 0L;
        float worstCallbackP95 = 0f;
        float[] phaseWorstP95 = new float[4];
        long[] phaseCallbackIntervals = new long[4];
        long[] phaseCallbackMissedIntervals = new long[4];
        ViewerTelemetry.NativeFrameStatsSnapshot[] nativePhaseStarts =
                new ViewerTelemetry.NativeFrameStatsSnapshot[4];
        ViewerTelemetry.NativeFrameStatsSnapshot[] nativePhaseEnds =
                new ViewerTelemetry.NativeFrameStatsSnapshot[4];
        long[] pssCheckpointsKb = new long[4];
        int homeRoundTrips = 0;

        long initialReadyDeadline = SystemClock.elapsedRealtime() + 90000L;
        while (SystemClock.elapsedRealtime() < initialReadyDeadline &&
                (!reader.testHasFullyReadyEpisode(current) ||
                        !reader.testHasReadyEpisodeRunway(sequence.get(1), 4))) {
            SystemClock.sleep(16L);
        }
        assertTrue("Initial ten-chapter episode must be fully drawable",
                reader.testHasFullyReadyEpisode(current));
        assertTrue("Initial ten-chapter successor must expose a four-page runway",
                reader.testHasReadyEpisodeRunway(sequence.get(1), 4));
        waitForLongSessionPhaseIdle(reader, 10000L);
        ReaderV2Activity.CleanPhysicalSourceSnapshot physical =
                waitForCleanPhysicalEpisode(reader, current.getNtkEpisodePath(), 0L, 15000L);
        assertNotNull("Ten-chapter start never produced a clean physical frame", physical);
        long lastPhysicalPresentation = physical.getPresentedUptimeNanos();

        Runtime.getRuntime().gc();
        waitForLongSessionPhaseIdle(reader, 10000L);
        Debug.MemoryInfo baselineMemory = new Debug.MemoryInfo();
        Debug.getMemoryInfo(baselineMemory);
        ReaderSurfaceView.NativeRetirementStatsSnapshot nativeRetirementBaseline =
                reader.testNativeRetirementStatsSnapshot();
        logMultiEpisodeSnapshot(reader, TEN_CHAPTER_START, "ten-chapter-initial");

        for (int transition = 0; transition < TEN_CHAPTER_FORWARD_TRANSITIONS; transition++) {
            int chapter = TEN_CHAPTER_START + transition;
            Manga next = sequence.get(transition + 1);
            int phase = transition < 3 ? 0 : transition < 6 ? 1 : transition < 8 ? 2 : 3;
            boolean phaseStart = transition == 0 || transition == 3 ||
                    transition == 6 || transition == 8;
            boolean phaseEnd = transition == 2 || transition == 5 ||
                    transition == 7 || transition == 9;
            if (phaseStart) {
                nativePhaseStarts[phase] = ViewerTelemetry.nativeFrameStatsSnapshot();
                assertNotNull("Missing native phase start for ten-chapter phase " + phase,
                        nativePhaseStarts[phase]);
            }

            assertEquals("Ten-chapter reader escaped the expected source before transition "
                            + transition,
                    current.getNtkEpisodePath(), reader.testCurrentNtkEpisodePath());
            runOnMain(reader::testResetFrameStatsSnapshot);
            ViewerTelemetry.NativeFrameStatsSnapshot nativeBefore =
                    ViewerTelemetry.nativeFrameStatsSnapshot();
            assertNotNull("Expected active native frame telemetry before chapter " + chapter,
                    nativeBefore);
            long physicalScrollStartedAt = SystemClock.elapsedRealtime();
            String transitioned = scrollEntireEpisodePhysicallyIntoNext(
                    device,
                    reader,
                    current,
                    90000L,
                    SUSTAINED_READING_SWIPE_STEPS);
            long physicalScrollElapsedMs =
                    SystemClock.elapsedRealtime() - physicalScrollStartedAt;
            assertEquals(
                    "Physical forward scrolling must enter the exact consecutive One Piece chapter",
                    next.getNtkEpisodePath(),
                    transitioned);
            assertTrue(
                    "Physical forward reading must not revisit an already consumed chapter; path="
                            + transitioned,
                    visitedPaths.add(transitioned));

            physical = scrollIntoCleanPhysicalEpisode(
                    device,
                    reader,
                    transitioned,
                    lastPhysicalPresentation,
                    30000L);
            assertNotNull("Physical transition never committed a clean body frame; chapter="
                    + (chapter + 1) + ",path=" + transitioned, physical);
            lastPhysicalPresentation = physical.getPresentedUptimeNanos();

            List<ReaderSurfaceView.FrameStatsSnapshot> frameSegments =
                    requireCompletedPhysicalFrameStats(reader, chapter);
            long chapterCallbackIntervals = 0L;
            long chapterCallbackMissedIntervals = 0L;
            int chapterDroppedFrames = 0;
            int chapterMissingPx = 0;
            float chapterWorstP95 = 0f;
            float chapterWorstMax = 0f;
            for (ReaderSurfaceView.FrameStatsSnapshot segment : frameSegments) {
                chapterCallbackIntervals += segment.getSamples();
                chapterCallbackMissedIntervals += segment.getMissedIntervals();
                chapterDroppedFrames += segment.getDroppedFrames();
                chapterMissingPx = Math.max(chapterMissingPx, segment.getMaxMissingPx());
                chapterWorstP95 = Math.max(chapterWorstP95, segment.getTotalP95());
                chapterWorstMax = Math.max(chapterWorstMax, segment.getTotalMax());
            }
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
            if (segmentWorstSlowIntervalNanos >= 0L) {
                worstNativeSlowIntervalNanos = Math.max(
                        worstNativeSlowIntervalNanos,
                        segmentWorstSlowIntervalNanos);
            }
            totalNativeIntervals += nativeIntervals;
            totalNativeSlowIntervals += nativeSlowIntervals;
            totalCallbackIntervals += chapterCallbackIntervals;
            totalCallbackMissedIntervals += chapterCallbackMissedIntervals;
            worstCallbackP95 = Math.max(worstCallbackP95, chapterWorstP95);
            phaseWorstP95[phase] = Math.max(phaseWorstP95[phase], chapterWorstP95);
            phaseCallbackIntervals[phase] += chapterCallbackIntervals;
            phaseCallbackMissedIntervals[phase] += chapterCallbackMissedIntervals;
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
                            + ",callbackSegments=" + frameSegments.size()
                            + ",callbackSamples=" + chapterCallbackIntervals
                            + ",callbackMissedIntervals=" + chapterCallbackMissedIntervals
                            + ",totalP95=" + chapterWorstP95
                            + ",totalMax=" + chapterWorstMax
                            + ",missingPx=" + chapterMissingPx);
            assertEquals(
                    "One Piece physical forward scrolling must not drop native frames; chapter="
                            + chapter,
                    0,
                    chapterDroppedFrames);
            assertEquals(
                    "One Piece physical forward scrolling must not expose missing pixels; chapter="
                            + chapter,
                    0,
                    chapterMissingPx);
            assertTrue(
                    "One Piece physical forward render p95 exceeded 16ms; chapter=" + chapter
                            + ",p95=" + chapterWorstP95,
                    chapterWorstP95 < STRICT_SCROLL_P95_MS);
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

            physical = performStrictHomeRoundTrip(
                    device,
                    reader,
                    current,
                    chapter + 1,
                    lastPhysicalPresentation);
            lastPhysicalPresentation = physical.getPresentedUptimeNanos();
            homeRoundTrips++;

            if (phaseEnd) {
                long completeDeadline = SystemClock.elapsedRealtime() + 90000L;
                while (SystemClock.elapsedRealtime() < completeDeadline &&
                        !reader.testHasFullyReadyEpisode(current)) {
                    SystemClock.sleep(16L);
                }
                assertTrue("Checkpoint episode never became fully ready; chapter="
                                + (chapter + 1),
                        reader.testHasFullyReadyEpisode(current));
                assertTrue("Checkpoint episode has no canonical source count; chapter="
                                + (chapter + 1),
                        reader.testCanonicalEpisodeSourceCount(current) > 0);
                waitForLongSessionPhaseIdle(reader, 10000L);
                nativePhaseEnds[phase] = ViewerTelemetry.nativeFrameStatsSnapshot();
                assertNotNull("Missing native phase end for ten-chapter phase " + phase,
                        nativePhaseEnds[phase]);
                Runtime.getRuntime().gc();
                waitForLongSessionPhaseIdle(reader, 10000L);
                Debug.MemoryInfo checkpoint = new Debug.MemoryInfo();
                Debug.getMemoryInfo(checkpoint);
                pssCheckpointsKb[phase] = checkpoint.getTotalPss();
                Log.i(TAG, "tenChapterCheckpoint phase=" + phase
                        + ",transitions=" + (transition + 1)
                        + ",path=" + reader.testCurrentNtkEpisodePath()
                        + ",pssKb=" + pssCheckpointsKb[phase]);
            }
        }
        assertEquals("Ten-chapter run did not complete ten physical forward transitions",
                TEN_CHAPTER_FORWARD_TRANSITIONS + 1, visitedPaths.size());
        assertEquals("Ten-chapter run did not complete one HOME round-trip per transition",
                TEN_CHAPTER_FORWARD_TRANSITIONS, homeRoundTrips);
        assertEquals("Ten-chapter run did not finish on its exact final chapter",
                sequence.get(TEN_CHAPTER_FORWARD_TRANSITIONS).getNtkEpisodePath(),
                reader.testCurrentNtkEpisodePath());
        double nativeQueueSlowPercent =
                totalNativeSlowIntervals * 100.0 / Math.max(1L, totalNativeIntervals);
        double aggregateMissedPercent = totalCallbackMissedIntervals * 100.0 /
                Math.max(1L, totalCallbackIntervals);
        assertTrue(
                "Expected at least 1000 committed callback intervals across ten physical One "
                        + "Piece transitions; intervals=" + totalCallbackIntervals,
                totalCallbackIntervals >= 1000L);
        assertTrue(
                "Expected at least 500 native intervals across ten physical One Piece "
                        + "transitions; intervals=" + totalNativeIntervals,
                totalNativeIntervals >= 500L);
        assertTrue("Ten-chapter physical scroll render p95 exceeded 16ms; p95="
                        + worstCallbackP95,
                worstCallbackP95 < STRICT_SCROLL_P95_MS);
        assertTrue(
                "One Piece physical forward callback jank must remain below 1% across all ten "
                        + "transitions; missed="
                        + totalCallbackMissedIntervals
                        + ",intervals=" + totalCallbackIntervals
                        + ",percent=" + aggregateMissedPercent,
                aggregateMissedPercent < STRICT_SCROLL_MISSED_PERCENT);
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
        assertTrue(
                "One Piece physical forward traversal contained a 100ms native frame; worstMs="
                        + (worstNativeSlowIntervalNanos / 1_000_000.0),
                worstNativeSlowIntervalNanos < 100_000_000L);

        double[] phaseFps = new double[4];
        double[] phaseSlowPercent = new double[4];
        for (int phase = 0; phase < 4; phase++) {
            assertNotNull("Ten-chapter native phase start disappeared; phase=" + phase,
                    nativePhaseStarts[phase]);
            assertNotNull("Ten-chapter native phase end disappeared; phase=" + phase,
                    nativePhaseEnds[phase]);
            assertEquals("Native generation changed during ten-chapter phase " + phase,
                    nativePhaseStarts[phase].getGeneration(),
                    nativePhaseEnds[phase].getGeneration());
            long phaseIntervals = nativePhaseEnds[phase].getScrollIntervals()
                    - nativePhaseStarts[phase].getScrollIntervals();
            long phaseIntervalNanos = nativePhaseEnds[phase].getScrollIntervalNanos()
                    - nativePhaseStarts[phase].getScrollIntervalNanos();
            long phaseSlowIntervals = nativePhaseEnds[phase].getSlowIntervals()
                    - nativePhaseStarts[phase].getSlowIntervals();
            assertTrue("Ten-chapter phase lacks native cadence evidence; phase=" + phase
                            + ",intervals=" + phaseIntervals,
                    phaseIntervals >= 100L && phaseIntervalNanos > 0L);
            phaseFps[phase] = phaseIntervals * 1_000_000_000.0 / phaseIntervalNanos;
            phaseSlowPercent[phase] = phaseSlowIntervals * 100.0 /
                    Math.max(1L, phaseIntervals);
            double phaseMissedPercent = phaseCallbackMissedIntervals[phase] * 100.0 /
                    Math.max(1L, phaseCallbackIntervals[phase]);
            assertTrue("Ten-chapter callback phase lacks evidence; phase=" + phase
                            + ",samples=" + phaseCallbackIntervals[phase],
                    phaseCallbackIntervals[phase] >= 100L);
            assertTrue("Ten-chapter callback phase exceeded 1% missed intervals; phase=" + phase
                            + ",percent=" + phaseMissedPercent,
                    phaseMissedPercent < STRICT_SCROLL_MISSED_PERCENT);
            assertTrue("Ten-chapter callback phase exceeded 16ms p95; phase=" + phase
                            + ",p95=" + phaseWorstP95[phase],
                    phaseWorstP95[phase] < STRICT_SCROLL_P95_MS);
            Log.i(TAG, "tenChapterCadence phase=" + phase
                    + ",nativeIntervals=" + phaseIntervals
                    + ",fps=" + phaseFps[phase]
                    + ",nativeSlowPercent=" + phaseSlowPercent[phase]
                    + ",callbackSamples=" + phaseCallbackIntervals[phase]
                    + ",callbackMissedPercent=" + phaseMissedPercent
                    + ",callbackP95=" + phaseWorstP95[phase]);
        }
        assertTrue("Native cadence degraded across ten chapters; firstFps=" + phaseFps[0]
                        + ",lastFps=" + phaseFps[3],
                phaseFps[3] >= phaseFps[0] * 0.85);
        assertTrue("Native slow intervals accumulated across ten chapters; firstPercent="
                        + phaseSlowPercent[0] + ",lastPercent=" + phaseSlowPercent[3],
                phaseSlowPercent[3] <= phaseSlowPercent[0] + 5.0);

        ReaderSurfaceView.NativeRetirementStatsSnapshot nativeRetirementFinal =
                reader.testNativeRetirementStatsSnapshot();
        assertEquals("Ten-chapter traversal observed a native presentation failure",
                0L, nativeRetirementFinal.getPresentFailed()
                        - nativeRetirementBaseline.getPresentFailed());
        assertEquals("Ten-chapter traversal observed a lifecycle retirement",
                0L, nativeRetirementFinal.getLifecycleRetired()
                        - nativeRetirementBaseline.getLifecycleRetired());
        assertEquals("Ten-chapter traversal observed an unknown native retirement",
                0L, nativeRetirementFinal.getUnknown()
                        - nativeRetirementBaseline.getUnknown());
        assertEquals("Ten-chapter traversal observed a renderer fatal",
                0L, nativeRetirementFinal.getRendererFatal()
                        - nativeRetirementBaseline.getRendererFatal());
        assertEquals("Ten-chapter traversal recreated the native renderer",
                0L, nativeRetirementFinal.getRecreate()
                        - nativeRetirementBaseline.getRecreate());
        assertEquals("Ten-chapter traversal fell back from the native renderer",
                0L, nativeRetirementFinal.getPermanentFallback()
                        - nativeRetirementBaseline.getPermanentFallback());

        Runtime.getRuntime().gc();
        waitForLongSessionPhaseIdle(reader, 10000L);
        Debug.MemoryInfo finalMemory = new Debug.MemoryInfo();
        Debug.getMemoryInfo(finalMemory);
        long totalPssGrowthKb = finalMemory.getTotalPss() - baselineMemory.getTotalPss();
        long settledPssGrowthKb = pssCheckpointsKb[3] - pssCheckpointsKb[1];
        long latePssGrowthKb = pssCheckpointsKb[3] - pssCheckpointsKb[2];
        long minimumCheckpointPssKb = pssCheckpointsKb[0];
        long maximumCheckpointPssKb = pssCheckpointsKb[0];
        for (long pssCheckpointKb : pssCheckpointsKb) {
            minimumCheckpointPssKb = Math.min(minimumCheckpointPssKb, pssCheckpointKb);
            maximumCheckpointPssKb = Math.max(maximumCheckpointPssKb, pssCheckpointKb);
        }
        long checkpointPssRangeKb = maximumCheckpointPssKb - minimumCheckpointPssKb;
        Log.i(TAG, "tenChapterMemory baselinePssKb=" + baselineMemory.getTotalPss()
                + ",finalPssKb=" + finalMemory.getTotalPss()
                + ",growthKb=" + totalPssGrowthKb
                + ",settledGrowthKb=" + settledPssGrowthKb
                + ",lateGrowthKb=" + latePssGrowthKb
                + ",checkpointRangeKb=" + checkpointPssRangeKb);
        assertTrue("Ten-chapter PSS exceeded the existing 192MiB envelope; growthKb="
                        + totalPssGrowthKb,
                totalPssGrowthKb <= 196608L);
        assertTrue("Ten-chapter PSS did not converge after six transitions; growthKb="
                        + settledPssGrowthKb,
                settledPssGrowthKb <= 65536L);
        assertTrue("Ten-chapter late PSS kept growing; growthKb=" + latePssGrowthKb,
                latePssGrowthKb <= 65536L);
        assertTrue("Ten-chapter canonical PSS range exceeded 64MiB; rangeKb="
                        + checkpointPssRangeKb,
                checkpointPssRangeKb <= 65536L);
    }

    @Test
    public void onePiece1186PhysicalTailCrossesInto1187() throws Exception {
        LiveNetworkAssume.assumeEnabled();
        launchOnePieceEpisodes();

        UiDevice device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation());
        UiObject2 episodeRow = findEpisodeRowByDescription(device, "1186화", 90000L);
        assertNotNull("Expected One Piece 1186 episode row", episodeRow);
        episodeRow.click();
        assertNotNull("Expected NTK One Piece reader surface",
                device.wait(Until.findObject(By.res(PACKAGE_NAME, "strip")), 90000L));
        ReaderV2Activity reader = resumedReader();
        assertNotNull("Expected resumed One Piece reader", reader);
        Manga current = reader.testEpisodeInCurrentSequence("1186화");
        if (current == null) current = reader.testEpisode("1186화");
        Manga next = reader.testEpisodeInCurrentSequence("1187화");
        if (next == null) next = reader.testEpisode("1187화");
        assertNotNull("Expected exact One Piece 1186 metadata", current);
        assertNotNull("Expected exact One Piece 1187 metadata", next);

        long readyDeadline = SystemClock.elapsedRealtime() + 90000L;
        while (SystemClock.elapsedRealtime() < readyDeadline &&
                !reader.testHasFullyReadyEpisode(current)) {
            SystemClock.sleep(16L);
        }
        assertTrue("One Piece 1186 did not become fully ready",
                reader.testHasFullyReadyEpisode(current));
        String transitioned = scrollEntireEpisodePhysicallyIntoNext(
                device,
                reader,
                current,
                90000L);
        assertEquals("Physical 1186 tail did not enter exact 1187",
                next.getNtkEpisodePath(), transitioned);
    }

    @Test
    public void onePiece1187PhysicalTailCrossesInto1188() throws Exception {
        LiveNetworkAssume.assumeEnabled();
        launchOnePieceEpisodes();

        UiDevice device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation());
        UiObject2 episodeRow = findEpisodeRowByDescription(device, "1187화", 90000L);
        assertNotNull("Expected One Piece 1187 episode row", episodeRow);
        episodeRow.click();
        assertNotNull("Expected NTK One Piece reader surface",
                device.wait(Until.findObject(By.res(PACKAGE_NAME, "strip")), 90000L));
        ReaderV2Activity reader = resumedReader();
        assertNotNull("Expected resumed One Piece reader", reader);
        Manga current = reader.testEpisodeInCurrentSequence("1187화");
        if (current == null) current = reader.testEpisode("1187화");
        Manga next = reader.testEpisodeInCurrentSequence("1188화");
        if (next == null) next = reader.testEpisode("1188화");
        assertNotNull("Expected exact One Piece 1187 metadata", current);
        assertNotNull("Expected exact One Piece 1188 metadata", next);

        long readyDeadline = SystemClock.elapsedRealtime() + 90000L;
        while (SystemClock.elapsedRealtime() < readyDeadline &&
                !reader.testHasFullyReadyEpisode(current)) {
            SystemClock.sleep(16L);
        }
        assertTrue("One Piece 1187 did not become fully ready",
                reader.testHasFullyReadyEpisode(current));
        ViewerTelemetry.NativeFrameStatsSnapshot before =
                ViewerTelemetry.nativeFrameStatsSnapshot();
        assertNotNull("Expected native telemetry before focused 1187 traversal", before);
        String transitioned = scrollEntireEpisodePhysicallyIntoNext(
                device, reader, current, 90000L);
        assertEquals("Physical 1187 tail did not enter exact 1188",
                next.getNtkEpisodePath(), transitioned);
        ViewerTelemetry.NativeFrameStatsSnapshot after =
                ViewerTelemetry.nativeFrameStatsSnapshot();
        assertNotNull("Expected native telemetry after focused 1187 traversal", after);
        assertEquals("Native telemetry generation changed during focused 1187 traversal",
                before.getGeneration(), after.getGeneration());
        long intervals = after.getScrollIntervals() - before.getScrollIntervals();
        long intervalNanos = after.getScrollIntervalNanos() - before.getScrollIntervalNanos();
        assertTrue("Focused 1187 traversal lacks native cadence evidence; intervals=" + intervals,
                intervals >= 100L && intervalNanos > 0L);
        Log.i(TAG, "focused1187NativeCadence intervals=" + intervals
                + ",fps=" + (intervals * 1_000_000_000.0 / intervalNanos)
                + ",slow=" + (after.getSlowIntervals() - before.getSlowIntervals())
                + ",details=" + after.getSlowIntervalDetails());
    }

    @Test
    public void onePiece1186TenHomeRoundTripsDoNotDegradeNativeCadence() throws Exception {
        LiveNetworkAssume.assumeEnabled();
        launchOnePieceEpisodes();

        UiDevice device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation());
        UiObject2 episodeRow = findEpisodeRowByDescription(device, "1186화", 90000L);
        assertNotNull("Expected One Piece 1186 episode row", episodeRow);
        episodeRow.click();
        assertNotNull("Expected NTK One Piece reader surface",
                device.wait(Until.findObject(By.res(PACKAGE_NAME, "strip")), 90000L));
        ReaderV2Activity reader = resumedReader();
        assertNotNull("Expected resumed One Piece reader", reader);
        Manga current = reader.testEpisodeInCurrentSequence("1186화");
        if (current == null) current = reader.testEpisode("1186화");
        assertNotNull("Expected exact One Piece 1186 metadata", current);

        long readyDeadline = SystemClock.elapsedRealtime() + 90000L;
        while (SystemClock.elapsedRealtime() < readyDeadline &&
                !reader.testHasFullyReadyEpisode(current)) {
            SystemClock.sleep(16L);
        }
        assertTrue("One Piece 1186 did not become fully ready",
                reader.testHasFullyReadyEpisode(current));
        waitForLongSessionPhaseIdle(reader, 10000L);
        ReaderV2Activity.CleanPhysicalSourceSnapshot physical =
                waitForCleanPhysicalEpisode(reader, current.getNtkEpisodePath(), 0L, 15000L);
        assertNotNull("One Piece 1186 never produced a clean initial frame", physical);

        double firstCadenceSum = 0.0;
        double lastCadenceSum = 0.0;
        for (int round = 0; round < 10; round++) {
            ViewerTelemetry.NativeFrameStatsSnapshot before =
                    ViewerTelemetry.nativeFrameStatsSnapshot();
            assertNotNull("Missing native cadence before HOME round " + round, before);
            physical = performStrictHomeRoundTrip(
                    device,
                    reader,
                    current,
                    118600 + round,
                    physical.getPresentedUptimeNanos());
            ViewerTelemetry.NativeFrameStatsSnapshot after =
                    ViewerTelemetry.nativeFrameStatsSnapshot();
            assertNotNull("Missing native cadence after HOME round " + round, after);
            assertEquals("Native generation changed during HOME round " + round,
                    before.getGeneration(), after.getGeneration());
            long intervals = after.getScrollIntervals() - before.getScrollIntervals();
            long intervalNanos = after.getScrollIntervalNanos()
                    - before.getScrollIntervalNanos();
            assertTrue("HOME round lacks native cadence evidence; round=" + round
                            + ",intervals=" + intervals,
                    intervals >= 20L && intervalNanos > 0L);
            double fps = intervals * 1_000_000_000.0 / intervalNanos;
            if (round < 3) firstCadenceSum += fps;
            if (round >= 7) lastCadenceSum += fps;
            Log.i(TAG, "repeatedHomeCadence round=" + round
                    + ",intervals=" + intervals
                    + ",fps=" + fps
                    + ",path=" + reader.testCurrentNtkEpisodePath());
        }
        double firstFps = firstCadenceSum / 3.0;
        double lastFps = lastCadenceSum / 3.0;
        Log.i(TAG, "repeatedHomeCadenceSummary firstFps=" + firstFps
                + ",lastFps=" + lastFps);
        assertTrue("Ten HOME round-trips degraded same-episode native cadence; firstFps="
                        + firstFps + ",lastFps=" + lastFps,
                lastFps >= firstFps * 0.85);
    }

    @Test
    public void onePiece1181PhysicalTailCadenceStaysBelowOneHundredMs() throws Exception {
        LiveNetworkAssume.assumeEnabled();
        launchOnePieceEpisodes();

        UiDevice device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation());
        UiObject2 episodeRow = findEpisodeRowByDescription(device, "1181화", 90000L);
        assertNotNull("Expected One Piece 1181 episode row", episodeRow);
        episodeRow.click();
        assertNotNull("Expected NTK One Piece reader surface",
                device.wait(Until.findObject(By.res(PACKAGE_NAME, "strip")), 90000L));
        ReaderV2Activity reader = resumedReader();
        assertNotNull("Expected resumed One Piece reader", reader);
        Manga current = reader.testEpisodeInCurrentSequence("1181화");
        if (current == null) current = reader.testEpisode("1181화");
        Manga next = reader.testEpisodeInCurrentSequence("1182화");
        if (next == null) next = reader.testEpisode("1182화");
        assertNotNull("Expected exact One Piece 1181 metadata", current);
        assertNotNull("Expected exact One Piece 1182 metadata", next);

        long readyDeadline = SystemClock.elapsedRealtime() + 90000L;
        while (SystemClock.elapsedRealtime() < readyDeadline &&
                !reader.testHasFullyReadyEpisode(current)) {
            SystemClock.sleep(16L);
        }
        assertTrue("One Piece 1181 did not become fully ready",
                reader.testHasFullyReadyEpisode(current));
        runOnMain(reader::testResetFrameStatsSnapshot);
        ViewerTelemetry.NativeFrameStatsSnapshot before =
                ViewerTelemetry.nativeFrameStatsSnapshot();
        assertNotNull("Expected native telemetry before focused 1181 traversal", before);
        String transitioned = scrollEntireEpisodePhysicallyIntoNext(
                device,
                reader,
                current,
                90000L,
                SUSTAINED_READING_SWIPE_STEPS);
        assertEquals("Physical 1181 tail did not enter exact 1182",
                next.getNtkEpisodePath(), transitioned);
        ReaderV2Activity.CleanPhysicalSourceSnapshot clean = scrollIntoCleanPhysicalEpisode(
                device, reader, transitioned, 0L, 30000L);
        assertNotNull("Focused 1181 traversal never committed a clean successor frame", clean);
        ViewerTelemetry.NativeFrameStatsSnapshot after =
                ViewerTelemetry.nativeFrameStatsSnapshot();
        assertNotNull("Expected native telemetry after focused 1181 traversal", after);
        assertEquals("Native telemetry generation changed during focused 1181 traversal",
                before.getGeneration(), after.getGeneration());
        long worst = after.getMaxRecordedSlowIntervalDurationSince(before.getSlowIntervals());
        Log.i(TAG, "focused1181NativeCadence intervals="
                + (after.getScrollIntervals() - before.getScrollIntervals())
                + ",slow=" + (after.getSlowIntervals() - before.getSlowIntervals())
                + ",worstMs=" + (worst / 1_000_000.0)
                + ",details=" + after.getSlowIntervalDetails());
        assertTrue("Focused physical 1181 traversal contained a 100ms native frame; worstMs="
                        + (worst / 1_000_000.0),
                worst < 100_000_000L);
    }

    @Test
    public void onePiece1181And1182PhysicalTailsNeverStallOrRollBack() throws Exception {
        LiveNetworkAssume.assumeEnabled();
        launchOnePieceEpisodes();

        UiDevice device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation());
        UiObject2 episodeRow = findEpisodeRowByDescription(device, "1181화", 90000L);
        assertNotNull("Expected One Piece 1181 episode row", episodeRow);
        episodeRow.click();
        assertNotNull("Expected NTK One Piece reader surface",
                device.wait(Until.findObject(By.res(PACKAGE_NAME, "strip")), 90000L));
        ReaderV2Activity reader = resumedReader();
        assertNotNull("Expected resumed One Piece reader", reader);
        Manga first = reader.testEpisodeInCurrentSequence("1181화");
        if (first == null) first = reader.testEpisode("1181화");
        Manga second = reader.testEpisodeInCurrentSequence("1182화");
        if (second == null) second = reader.testEpisode("1182화");
        Manga third = reader.testEpisodeInCurrentSequence("1183화");
        if (third == null) third = reader.testEpisode("1183화");
        assertNotNull("Expected exact One Piece 1181 metadata", first);
        assertNotNull("Expected exact One Piece 1182 metadata", second);
        assertNotNull("Expected exact One Piece 1183 metadata", third);

        long readyDeadline = SystemClock.elapsedRealtime() + 90000L;
        while (SystemClock.elapsedRealtime() < readyDeadline &&
                !reader.testHasFullyReadyEpisode(first)) {
            SystemClock.sleep(16L);
        }
        assertTrue("One Piece 1181 did not become fully ready",
                reader.testHasFullyReadyEpisode(first));
        String enteredSecond = scrollEntireEpisodePhysicallyIntoNext(
                device, reader, first, 90000L, SUSTAINED_READING_SWIPE_STEPS);
        assertEquals("Physical 1181 tail did not enter exact 1182",
                second.getNtkEpisodePath(), enteredSecond);
        String enteredThird = scrollEntireEpisodePhysicallyIntoNext(
                device, reader, second, 120000L, SUSTAINED_READING_SWIPE_STEPS);
        assertEquals("Physical 1182 tail did not enter exact 1183",
                third.getNtkEpisodePath(), enteredThird);
    }

    @Test
    public void appended1185ContinuesImmediatelyAfterHomeRoundTrip() throws Exception {
        LiveNetworkAssume.assumeEnabled();
        launchOnePieceEpisodes();

        UiDevice device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation());
        UiObject2 episodeRow = findEpisodeRowByDescription(device, "1184화", 90000L);
        assertNotNull("Expected One Piece 1184 episode row", episodeRow);
        episodeRow.click();
        assertNotNull("Expected NTK One Piece reader surface",
                device.wait(Until.findObject(By.res(PACKAGE_NAME, "strip")), 90000L));
        ReaderV2Activity reader = resumedReader();
        assertNotNull("Expected resumed One Piece reader", reader);
        Manga chapter1184 = reader.testEpisodeInCurrentSequence("1184화");
        if (chapter1184 == null) chapter1184 = reader.testEpisode("1184화");
        Manga chapter1185 = reader.testEpisodeInCurrentSequence("1185화");
        if (chapter1185 == null) chapter1185 = reader.testEpisode("1185화");
        Manga chapter1186 = reader.testEpisodeInCurrentSequence("1186화");
        if (chapter1186 == null) chapter1186 = reader.testEpisode("1186화");
        assertNotNull("Expected exact One Piece 1184 metadata", chapter1184);
        assertNotNull("Expected exact One Piece 1185 metadata", chapter1185);
        assertNotNull("Expected exact One Piece 1186 metadata", chapter1186);

        long readyDeadline = SystemClock.elapsedRealtime() + 90000L;
        while (SystemClock.elapsedRealtime() < readyDeadline &&
                !reader.testHasFullyReadyEpisode(chapter1184)) {
            SystemClock.sleep(16L);
        }
        assertTrue("One Piece 1184 did not become fully ready",
                reader.testHasFullyReadyEpisode(chapter1184));
        assertEquals(chapter1185.getNtkEpisodePath(),
                scrollEntireEpisodePhysicallyIntoNext(
                        device, reader, chapter1184, 90000L, SUSTAINED_READING_SWIPE_STEPS));
        ReaderV2Activity.CleanPhysicalSourceSnapshot entered1185 =
                scrollIntoCleanPhysicalEpisode(
                        device, reader, chapter1185.getNtkEpisodePath(), 0L, 30000L);
        assertNotNull("Appended One Piece 1185 never committed clean pixels; pipeline="
                + reader.testRenderPipelineDiagnosticSnapshot(), entered1185);
        performStrictHomeRoundTrip(
                device,
                reader,
                chapter1185,
                1185,
                entered1185.getPresentedUptimeNanos());

        assertEquals("Appended physical 1185 tail stalled after HOME",
                chapter1186.getNtkEpisodePath(),
                scrollEntireEpisodePhysicallyIntoNext(
                        device, reader, chapter1185, 90000L, SUSTAINED_READING_SWIPE_STEPS));
    }

    @Test
    public void appended1186TailCrossesAfterHomeRoundTrip() throws Exception {
        LiveNetworkAssume.assumeEnabled();
        launchOnePieceEpisodes();

        UiDevice device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation());
        UiObject2 episodeRow = findEpisodeRowByDescription(device, "1185화", 90000L);
        assertNotNull("Expected One Piece 1185 episode row", episodeRow);
        episodeRow.click();
        assertNotNull("Expected NTK One Piece reader surface",
                device.wait(Until.findObject(By.res(PACKAGE_NAME, "strip")), 90000L));
        ReaderV2Activity reader = resumedReader();
        assertNotNull("Expected resumed One Piece reader", reader);
        Manga chapter1185 = reader.testEpisodeInCurrentSequence("1185화");
        if (chapter1185 == null) chapter1185 = reader.testEpisode("1185화");
        Manga chapter1186 = reader.testEpisodeInCurrentSequence("1186화");
        if (chapter1186 == null) chapter1186 = reader.testEpisode("1186화");
        Manga chapter1187 = reader.testEpisodeInCurrentSequence("1187화");
        if (chapter1187 == null) chapter1187 = reader.testEpisode("1187화");
        assertNotNull("Expected exact One Piece 1185 metadata", chapter1185);
        assertNotNull("Expected exact One Piece 1186 metadata", chapter1186);
        assertNotNull("Expected exact One Piece 1187 metadata", chapter1187);

        long readyDeadline = SystemClock.elapsedRealtime() + 90000L;
        while (SystemClock.elapsedRealtime() < readyDeadline &&
                !reader.testHasFullyReadyEpisode(chapter1185)) {
            SystemClock.sleep(16L);
        }
        assertEquals(chapter1186.getNtkEpisodePath(),
                scrollEntireEpisodePhysicallyIntoNext(
                        device, reader, chapter1185, 90000L));
        ReaderV2Activity.CleanPhysicalSourceSnapshot entered1186 =
                scrollIntoCleanPhysicalEpisode(
                        device, reader, chapter1186.getNtkEpisodePath(), 0L, 30000L);
        assertNotNull("Appended One Piece 1186 never committed clean pixels; pipeline="
                + reader.testRenderPipelineDiagnosticSnapshot(), entered1186);
        performStrictHomeRoundTrip(
                device,
                reader,
                chapter1186,
                1186,
                entered1186.getPresentedUptimeNanos());
        readyDeadline = SystemClock.elapsedRealtime() + 90000L;
        while (SystemClock.elapsedRealtime() < readyDeadline &&
                !reader.testHasFullyReadyEpisode(chapter1186)) {
            SystemClock.sleep(16L);
        }
        assertTrue("Appended One Piece 1186 did not become fully ready",
                reader.testHasFullyReadyEpisode(chapter1186));
        assertEquals("Appended physical 1186 tail did not enter exact 1187 after HOME",
                chapter1187.getNtkEpisodePath(),
                scrollEntireEpisodePhysicallyIntoNext(
                        device, reader, chapter1186, 90000L));
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

        // The completion ledger survives physical cache turnover, so its historical "complete"
        // bit does not prove that p0-p8 are live and scrollable in this Surface.  Bind setup to
        // the actual current runway and a clean committed viewport before moving to page eight.
        long readyDeadline = SystemClock.elapsedRealtime() + 90000L;
        ReaderSurfaceView.VisibleCoverageSnapshot initialCoverage = null;
        while (SystemClock.elapsedRealtime() < readyDeadline) {
            initialCoverage = firstReader.testVisibleCoverageSnapshot();
            boolean cleanInitialCoverage = initialCoverage != null &&
                    initialCoverage.getDrawablePx() > 0 &&
                    initialCoverage.getMissingPx() == 0 &&
                    initialCoverage.getPlaceholderPx() == 0 &&
                    initialCoverage.getVisibleLoading() == 0 &&
                    initialCoverage.getVisibleErrors() == 0 &&
                    !firstReader.testNativeSurfaceRevealPending();
            if (firstReader.testHasReadyEpisodeRunway(current, 9) && cleanInitialCoverage) break;
            SystemClock.sleep(16L);
        }
        assertTrue("Current chapter must expose a live nine-page runway before bookmark re-entry",
                firstReader.testHasReadyEpisodeRunway(current, 9));
        assertNotNull("Current chapter lost its physical setup viewport", initialCoverage);
        assertTrue("Current chapter setup viewport is not clean; coverage=" + initialCoverage,
                initialCoverage.getDrawablePx() > 0 &&
                        initialCoverage.getMissingPx() == 0 &&
                        initialCoverage.getPlaceholderPx() == 0 &&
                        initialCoverage.getVisibleLoading() == 0 &&
                        initialCoverage.getVisibleErrors() == 0 &&
                        !firstReader.testNativeSurfaceRevealPending());

        // Move the live reader to a real middle page before opening Continue. ReaderV2 saves its
        // current progress synchronously in onPause; writing a synthetic bookmark while the old
        // reader is still on page zero would correctly be overwritten by that lifecycle save.
        int width = device.getDisplayWidth();
        int height = device.getDisplayHeight();
        int x = width / 2;
        int forwardFrom = Math.min(height - 160, height * 3 / 4);
        int forwardTo = Math.max(120, height / 4);
        // UiDevice gesture injection can take several seconds per swipe while the emulator uploads
        // the freshly completed full scene and prepares the adjacent manifest. This is setup for
        // the resume contract, not its latency oracle, so allow enough time to reach the same
        // physical middle page without weakening any post-resume motion deadline.
        long middlePageDeadline = SystemClock.elapsedRealtime() + 45000L;
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
        UiObject2 replacementActual = device.wait(
                Until.findObject(By.descStartsWith("actual:")),
                90000L);
        assertNotNull(
                "Replacement reader must physically commit its resumed page",
                replacementActual);
        String beforeReversePhysicalDescription = String.valueOf(
                replacementActual.getContentDescription());
        ReaderV2Activity.CleanPhysicalSourceSnapshot beforeReversePhysical =
                waitForCleanPhysicalSourceSnapshot(reader, 15000L);
        assertNotNull(
                "Replacement reader never exposed a clean physical source snapshot",
                beforeReversePhysical);
        assertTrue(
                "Replacement reader exposed an invalid clean physical source; source="
                        + beforeReversePhysical.getSourcePage()
                        + ",presented=" + beforeReversePhysical.getPresentedUptimeNanos(),
                beforeReversePhysical.getSourcePage() >= 0 &&
                        beforeReversePhysical.getPresentedUptimeNanos() > 0L);

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

        // Reverse input is intentionally allowed to enter a not-yet-resident historical page
        // while Surface keeps the last clean pixels. On the throttled live fixture that body can
        // take well over five seconds, so prove that the lowered source floor produces a new clean
        // physical frame before measuring the separate forward-input deadline. This remains a
        // finite liveness oracle: a lost demand or decode wake still times out here.
        UiObject2 reversePhysical = waitForFreshCleanPhysicalReaderNode(
                device,
                reader,
                beforeReversePhysicalDescription,
                beforeReversePhysical,
                90000L);
        assertNotNull(
                "Immediate reverse never installed a fresh clean historical frame; coverage="
                        + reader.testVisibleCoverageSnapshot()
                        + ",initialSource=" + beforeReversePhysical.getSourcePage()
                        + ",lastPhysical=" + cleanPhysicalSourceSnapshot(reader)
                        + ",pipeline=" + reader.testRenderPipelineDiagnosticSnapshot(),
                reversePhysical);

        // device.swipe() returns after the injected samples, not after the resulting inertial
        // motion has stopped. The early `reversed` snapshot above proves that reverse input was
        // admitted, but it is not the coordinate owned by the next ACTION_DOWN: while the
        // historical drawable is being rehydrated the reverse fling may legitimately continue.
        // Compare the direction change against the coordinate immediately before the new gesture
        // so this remains a strict user-motion oracle instead of comparing against a stale point
        // from the middle of the preceding fling.
        ReaderSurfaceView.ScrollPositionSnapshot beforeForward =
                reader.testCurrentScrollPositionSnapshot();
        assertNotNull("Clean historical frame lost its pre-forward scroll position", beforeForward);
        device.swipe(x, forwardFrom, x, forwardTo, 18);
        ReaderSurfaceView.ScrollPositionSnapshot forwarded = null;
        long forwardDeadline = SystemClock.elapsedRealtime() + 5000L;
        while (SystemClock.elapsedRealtime() < forwardDeadline) {
            forwarded = reader.testCurrentScrollPositionSnapshot();
            if (forwarded != null && forwarded.getScrollOffset() >
                    beforeForward.getScrollOffset() + 100) break;
            SystemClock.sleep(16L);
        }
        assertNotNull("Forward motion after immediate reverse lost the scroll position", forwarded);
        assertTrue("Reader remained motion-locked after the initial reverse gesture; earlyReverse="
                        + reversed.getScrollOffset() + ",beforeForward="
                        + beforeForward.getScrollOffset() + ",forwarded="
                        + forwarded.getScrollOffset() + ",pipeline="
                        + reader.testRenderPipelineDiagnosticSnapshot(),
                forwarded.getScrollOffset() > beforeForward.getScrollOffset() + 100);

        String pathBeforeHome = reader.testCurrentNtkEpisodePath();
        // UiDevice reports the key-injection result here, not whether Launcher actually won
        // focus. Some API-35 builds return false after a successful HOME transition, so assert
        // the observable window state instead of rejecting a real background/resume cycle.
        device.pressHome();
        assertTrue("Expected HOME to background the reader",
                device.wait(Until.gone(By.pkg(PACKAGE_NAME)), 5000L));
        SystemClock.sleep(1200L);
        ReaderV2Activity resumed = reopenExistingReaderFromPixelLauncherRecents(device);
        assertNotNull("HOME return did not resume the existing reader", resumed);
        assertTrue("HOME return created a replacement reader instead of resuming the task",
                resumed == reader);
        assertEquals("HOME return did not foreground the MangaView package",
                PACKAGE_NAME, device.getCurrentPackageName());
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
    public void onePieceToolbarPreviousAndNextButtonsKeepExactPixels() throws Exception {
        LiveNetworkAssume.assumeEnabled();
        launchOnePieceEpisodes();

        UiDevice device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation());
        UiObject2 episodeRow = findEpisodeRowByDescription(device, "1184화", 90000L);
        assertNotNull("Expected One Piece 1184 episode row", episodeRow);
        episodeRow.click();
        assertNotNull(
                "Expected NTK One Piece reader surface",
                device.wait(Until.findObject(By.res(PACKAGE_NAME, "strip")), 90000L));

        ReaderV2Activity reader = resumedReader();
        assertNotNull("Expected resumed One Piece reader", reader);
        Manga selected = reader.testEpisodeInCurrentSequence("1184화");
        assertNotNull("Expected 1184 in the production episode picker sequence", selected);
        String selectedPath = selected.getNtkEpisodePath();
        assertNotNull("Expected exact 1184 episode path", selectedPath);
        Manga expectedNext = reader.testEpisodeInCurrentSequence("1185화");
        assertNotNull("Expected 1185 after manually selecting 1184", expectedNext);
        String expectedNextPath = expectedNext.getNtkEpisodePath();
        assertNotNull("Expected exact 1185 path", expectedNextPath);
        ReaderV2Activity.CleanPhysicalSourceSnapshot initial = null;
        long initialReadyDeadline = SystemClock.elapsedRealtime() + 90000L;
        while (SystemClock.elapsedRealtime() < initialReadyDeadline) {
            initial = cleanPhysicalEpisodeSnapshot(reader, selectedPath, 0L);
            if (initial != null || reader.testHasFullyReadyEpisode(selected)) break;
            SystemClock.sleep(16L);
        }
        if (initial == null) {
            // Reading history is intentionally preserved across these UX tests. A real selection
            // can therefore resume on the final source with the already-prepared next divider in
            // view. Move backward through the UI until this episode owns a clean body frame,
            // rather than assuming a synthetic page-zero launch or waiting for a stationary
            // transition card to disappear on its own.
            initial = scrollBackwardIntoCleanPhysicalEpisode(
                    device, reader, selectedPath, 30000L);
        }
        assertNotNull("Initial 1184 never produced clean physical pixels; pipeline="
                + reader.testRenderPipelineDiagnosticSnapshot(), initial);
        int selectedPageCount = reader.testCurrentNtkImageCount();
        assertTrue("Expected a multi-page 1184 episode; count=" + selectedPageCount,
                selectedPageCount >= 4);

        // Establish the resume state exactly as a person does: physically read to the final image,
        // then leave through the toolbar. No bookmark mutation, direct scroll hook, or page jump is
        // allowed here. This makes the later previous-button transition exercise a nonzero resume
        // page and the short-tail/divider/next-p0 composition that originally stalled forever.
        int width = device.getDisplayWidth();
        int height = device.getDisplayHeight();
        int x = width / 2;
        int fromY = Math.min(height - 160, height * 2 / 3);
        int toY = Math.max(160, height / 3);
        long tailDeadline = SystemClock.elapsedRealtime() + 60000L;
        ReaderSurfaceView.ScrollPositionSnapshot readPosition = null;
        while (SystemClock.elapsedRealtime() < tailDeadline) {
            readPosition = reader.testCurrentScrollPositionSnapshot();
            if (readPosition != null && readPosition.getPage() >= selectedPageCount - 1) break;
            assertEquals("Physical preparation crossed out of 1184 before its final image",
                    selectedPath, reader.testCurrentNtkEpisodePath());
            device.swipe(x, fromY, x, toY, 16);
            SystemClock.sleep(80L);
        }
        assertNotNull("Physical reading lost its scroll position", readPosition);
        assertTrue("Physical reading never reached the final 1184 image; page="
                        + readPosition.getPage() + ",count=" + selectedPageCount
                        + ",pipeline=" + reader.testRenderPipelineDiagnosticSnapshot(),
                readPosition.getPage() >= selectedPageCount - 1);
        SystemClock.sleep(1800L);

        UiObject2 nextButton = revealToolbarButton(device, "toolbar_next", 10000L);
        assertNotNull("Reader tap did not reveal the next-episode button", nextButton);
        nextButton.click();

        ReaderV2Activity.CleanPhysicalSourceSnapshot nextPhysical =
                waitForCleanPhysicalEpisode(
                        reader,
                        expectedNextPath,
                        initial.getPresentedUptimeNanos(),
                        90000L);
        assertNotNull(
                "Toolbar next changed metadata but never drew exact 1185 pixels",
                nextPhysical);
        assertEquals("Toolbar next selected the wrong episode", expectedNextPath,
                reader.testCurrentNtkEpisodePath());

        UiObject2 previousButton = revealToolbarButton(device, "toolbar_previous", 10000L);
        assertNotNull("Reader tap did not reveal the previous-episode button", previousButton);
        previousButton.click();

        ReaderV2Activity.CleanPhysicalSourceSnapshot manuallySelected =
                waitForCleanPhysicalEpisode(
                        reader,
                        selectedPath,
                        nextPhysical.getPresentedUptimeNanos(),
                        90000L);
        assertNotNull(
                "Toolbar previous changed metadata but never drew resumed exact 1184 pixels; pipeline="
                        + reader.testRenderPipelineDiagnosticSnapshot(),
                manuallySelected);
        assertTrue("Toolbar previous discarded the physically read 1184 resume page; start="
                        + reader.testSessionInitialStartPage(),
                reader.testSessionInitialStartPage() >= selectedPageCount - 1);
        assertEquals("Toolbar previous selected the wrong episode", selectedPath,
                reader.testCurrentNtkEpisodePath());

        nextButton = revealToolbarButton(device, "toolbar_next", 10000L);
        assertNotNull("Reader tap did not reveal next after resumed previous", nextButton);
        nextButton.click();
        ReaderV2Activity.CleanPhysicalSourceSnapshot finalNext =
                waitForCleanPhysicalEpisode(
                        reader,
                        expectedNextPath,
                        manuallySelected.getPresentedUptimeNanos(),
                        90000L);
        assertNotNull("Second toolbar next never drew exact 1185 pixels", finalNext);
        assertEquals("Second toolbar next selected the wrong episode", expectedNextPath,
                reader.testCurrentNtkEpisodePath());
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
        String activePath = reader.testCurrentNtkEpisodePath();
        assertNotNull("Expected active One Piece exact path", activePath);
        Manga current = reader.testEpisodeByPath(activePath);
        Manga next = reader.testEpisodeInCurrentSequence("1186화");
        Manga following = reader.testEpisodeInCurrentSequence("1187화");
        Manga afterFollowing = reader.testEpisodeInCurrentSequence("1188화");
        assertNotNull("Expected One Piece 1185 metadata", current);
        assertNotNull("Expected One Piece 1186 metadata", next);
        assertNotNull("Expected One Piece 1187 metadata", following);
        assertNotNull("Expected One Piece 1188 metadata", afterFollowing);
        String currentPath = current.getNtkEpisodePath();
        String nextPath = next.getNtkEpisodePath();
        String followingPath = following.getNtkEpisodePath();
        assertNotNull("Expected One Piece 1185 exact path", currentPath);
        assertNotNull("Expected One Piece 1186 exact path", nextPath);
        assertNotNull("Expected One Piece 1187 exact path", followingPath);
        assertTrue("Long-session launch must be the selected 1185 episode: " + activePath,
                current.getName() != null && current.getName().contains("1185화"));
        assertEquals("Active reader path and current metadata diverged",
                activePath, currentPath);
        assertTrue("Long-session episode paths must be distinct",
                !currentPath.equals(nextPath) && !nextPath.equals(followingPath));
        Log.i(TAG, "longReaderPaths current=" + currentPath
                + ",next=" + nextPath + ",following=" + followingPath);

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

        // The assertion below owns long-session accumulation, not one-time discovery/JIT cost.
        // Warm the exact B -> C -> B retained-history circuit once before resetting telemetry so
        // the four measured phases exercise identical resident identities. Without this pass the
        // first phase alone includes 1187 document discovery, native texture prewarm, and JIT/GC,
        // while the remaining phases are warm and cannot form a meaningful accumulation series.
        assertEquals(
                "Long-session warm-up did not enter exact 1187",
                followingPath,
                scrollPhysicallyForwardUntilEpisodeChanges(
                        device, reader, nextPath, 90000L));
        long followingReadyDeadline = SystemClock.elapsedRealtime() + 90000L;
        while (SystemClock.elapsedRealtime() < followingReadyDeadline &&
                !reader.testHasFullyReadyEpisode(following)) {
            SystemClock.sleep(16L);
        }
        assertTrue("Following chapter must be fully drawable before accumulation sampling; "
                        + reader.testRenderPipelineDiagnosticSnapshot(),
                reader.testHasFullyReadyEpisode(following));

        // A first adjacent body is not enough to establish the production one-predecessor
        // steady state. Move far enough into C for a clean physical source-2 frame, then stop
        // input until A is actually pruned. Otherwise the measured loop can accidentally retain
        // three episode structures or reverse through A depending on host timing.
        int warmX = device.getDisplayWidth() / 2;
        int warmForwardFrom = Math.min(device.getDisplayHeight() - 160,
                device.getDisplayHeight() * 3 / 4);
        int warmForwardTo = Math.max(120, device.getDisplayHeight() / 4);
        long warmFollowingPhysicalDeadline = SystemClock.elapsedRealtime() + 90000L;
        ReaderV2Activity.CleanPhysicalSourceSnapshot warmFollowingPhysical =
                cleanPhysicalSourceSnapshot(reader);
        while (SystemClock.elapsedRealtime() < warmFollowingPhysicalDeadline &&
                (warmFollowingPhysical == null ||
                        !followingPath.equals(
                                warmFollowingPhysical.getPhysicalEpisodePath()) ||
                        warmFollowingPhysical.getFirstVisibleSourcePage() < 2)) {
            assertTrue("Unable to advance the long-session warm circuit into exact 1187",
                    device.swipe(warmX, warmForwardFrom, warmX, warmForwardTo, 8));
            warmFollowingPhysical = cleanPhysicalSourceSnapshot(reader);
        }
        assertNotNull("Long-session warm-up lost clean physical identity",
                warmFollowingPhysical);
        assertEquals("Long-session warm-up did not physically remain in exact 1187",
                followingPath, warmFollowingPhysical.getPhysicalEpisodePath());
        assertTrue("Long-session warm-up did not physically reach 1187 source 2; source=" +
                        warmFollowingPhysical.getFirstVisibleSourcePage(),
                warmFollowingPhysical.getFirstVisibleSourcePage() >= 2);
        long afterFollowingRunwayDeadline = SystemClock.elapsedRealtime() + 90000L;
        while (SystemClock.elapsedRealtime() < afterFollowingRunwayDeadline &&
                !reader.testHasReadyEpisodeRunway(afterFollowing, 4)) {
            SystemClock.sleep(16L);
        }
        assertTrue("Long-session baseline must include the bounded 1188 adjacent runway",
                reader.testHasReadyEpisodeRunway(afterFollowing, 4));
        waitForLongSessionPhaseIdle(reader, 10000L);
        long pruneDeadline = SystemClock.elapsedRealtime() + 15000L;
        while (SystemClock.elapsedRealtime() < pruneDeadline &&
                reader.testHasLoadedEpisode(current)) {
            SystemClock.sleep(16L);
        }
        assertTrue("Long-session warm-up never established one-predecessor retention",
                !reader.testHasLoadedEpisode(current));

        int warmReverseFrom = Math.max(120, device.getDisplayHeight() / 4);
        int warmReverseTo = Math.min(device.getDisplayHeight() - 160,
                device.getDisplayHeight() * 3 / 4);
        long warmReturnDeadline = SystemClock.elapsedRealtime() + 90000L;
        ReaderSurfaceView.ScrollPositionSnapshot warmReturn =
                reader.testCurrentScrollPositionSnapshot();
        String warmReturnPath = reader.testCurrentNtkEpisodePath();
        while (SystemClock.elapsedRealtime() < warmReturnDeadline &&
                !(nextPath.equals(warmReturnPath) && warmReturn != null &&
                        warmReturn.getScrollOffset() <= 2)) {
            device.swipe(warmX, warmReverseFrom, warmX, warmReverseTo, 8);
            warmReturn = reader.testCurrentScrollPositionSnapshot();
            warmReturnPath = reader.testCurrentNtkEpisodePath();
        }
        assertNotNull("Long-session warm-up lost its retained scroll state", warmReturn);
        assertEquals("Long-session warm-up did not return to exact retained 1186",
                nextPath, warmReturnPath);
        assertTrue("Long-session warm-up did not reach retained 1186 start; offset=" +
                        warmReturn.getScrollOffset(),
                warmReturn.getScrollOffset() <= 2);

        waitForLongSessionPhaseIdle(reader, 10000L);
        ReaderV2Activity.CleanPhysicalSourceSnapshot longSessionPhysicalBaseline =
                waitForLongSessionCanonicalPhysical(
                        reader, nextPath, -1, 0L, 15000L);
        assertNotNull("Long-session baseline did not expose a clean retained-start frame",
                longSessionPhysicalBaseline);
        int longSessionCanonicalFirstSource =
                longSessionPhysicalBaseline.getFirstVisibleSourcePage();
        long longSessionLastPhysicalFrameToken = longSessionPhysicalBaseline.getFrameToken();
        Runtime.getRuntime().gc();
        waitForLongSessionPhaseIdle(reader, 10000L);
        Debug.MemoryInfo baselineMemory = new Debug.MemoryInfo();
        Debug.getMemoryInfo(baselineMemory);
        Log.i(TAG, "longReaderMemory baselinePssKb=" + baselineMemory.getTotalPss()
                + ",javaUsedKb=" + ((Runtime.getRuntime().totalMemory() -
                Runtime.getRuntime().freeMemory()) / 1024L)
                + ",nativeHeapKb=" + (Debug.getNativeHeapAllocatedSize() / 1024L));
        runOnMain(reader::testResetFrameStatsSnapshot);
        ViewerTelemetry.NativeFrameStatsSnapshot nativeFrameBaseline =
                ViewerTelemetry.nativeFrameStatsSnapshot();
        assertNotNull("Expected native cadence evidence before long-session stress",
                nativeFrameBaseline);
        ReaderSurfaceView.NativeRetirementStatsSnapshot nativeRetirementBaseline =
                reader.testNativeRetirementStatsSnapshot();
        List<ReaderSurfaceView.FrameStatsSnapshot> frameSegments = new ArrayList<>();
        int[] phaseFrameSamples = new int[4];
        int[] phaseMissedIntervals = new int[4];
        float[] phaseWorstP95 = new float[4];
        int[] phaseNoCanvas = new int[4];
        int[] phaseCoalesced = new int[4];
        int[] phasePipelineFrames = new int[4];
        int[] phaseFunctionalSubmissionSamples = new int[4];
        int[] phaseFunctionalSubmissionEligiblePairs = new int[4];
        int[] phaseFunctionalSubmissionInvalidPairs = new int[4];
        int[] phaseFunctionalInputGestures = new int[4];
        int[] phaseFunctionalGesturesWithValidPair = new int[4];
        int[] phaseCadenceQualificationFailedSegments = new int[4];

        int width = device.getDisplayWidth();
        int height = device.getDisplayHeight();
        int x = width / 2;
        int upFrom = Math.min(height - 160, height * 3 / 4);
        int upTo = Math.max(120, height / 4);
        boolean forward = true;
        long[] pssCheckpointsKb = new long[4];
        int[] followingTurns = new int[4];
        int[] retainedStartTurns = new int[4];
        ViewerTelemetry.NativeFrameStatsSnapshot[] nativeFramePhaseStarts =
                new ViewerTelemetry.NativeFrameStatsSnapshot[4];
        ViewerTelemetry.NativeFrameStatsSnapshot[] nativeFrameCheckpoints =
                new ViewerTelemetry.NativeFrameStatsSnapshot[4];
        ReaderSurfaceView.NativeRetirementStatsSnapshot[] nativeRetirementCheckpoints =
                new ReaderSurfaceView.NativeRetirementStatsSnapshot[4];
        for (int swipe = 0; swipe < 260; swipe++) {
            ReaderSurfaceView.ScrollPositionSnapshot position =
                    reader.testCurrentScrollPositionSnapshot();
            assertNotNull("Reader lost its scroll state during long-session stress", position);
            String stressPath = reader.testCurrentNtkEpisodePath();
            assertNotNull("Reader lost its episode identity during long-session stress",
                    stressPath);
            int phaseIndex = swipe / 65;
            if (swipe % 65 == 0) {
                nativeFramePhaseStarts[phaseIndex] =
                        ViewerTelemetry.nativeFrameStatsSnapshot();
                assertNotNull("Native cadence start evidence disappeared at phase " + phaseIndex,
                        nativeFramePhaseStarts[phaseIndex]);
            }
            assertTrue("Long-session stress escaped its fixed B/C circuit; path=" + stressPath,
                    nextPath.equals(stressPath) || followingPath.equals(stressPath));
            // Keep this a deterministic fixed-content stress around 1186. The global max-scroll
            // grows whenever 1188+ is prepared, so using it as the turn-around point silently
            // changes the workload with network timing. Crossing into exact 1187 turns the test
            // back. Product history intentionally retains only the current episode and its one
            // immediate predecessor, so after 1187 prunes 1185 the physical start of retained
            // 1186—not the no-longer-present 1185 identity—is the reverse turn-around point.
            if (followingPath.equals(stressPath)) {
                if (forward) {
                    followingTurns[phaseIndex]++;
                    Log.i(TAG, "longReaderStress boundary=following,direction=reverse"
                            + ",swipe=" + swipe
                            + ",path=" + stressPath
                            + ",offset=" + position.getScrollOffset());
                }
                forward = false;
            } else if (nextPath.equals(stressPath) &&
                    position.getScrollOffset() <= height) {
                if (!forward) {
                    retainedStartTurns[phaseIndex]++;
                    Log.i(TAG, "longReaderStress boundary=retained-start,direction=forward"
                            + ",swipe=" + swipe
                            + ",path=" + stressPath
                            + ",page=" + position.getPage()
                            + ",offset=" + position.getScrollOffset());
                }
                forward = true;
            }
            if (forward) {
                assertTrue("Long-session forward swipe injection failed at " + swipe,
                        device.swipe(x, upFrom, x, upTo, 8));
            } else {
                assertTrue("Long-session reverse swipe injection failed at " + swipe,
                        device.swipe(x, upTo, x, upFrom, 8));
            }
            if ((swipe + 1) % 65 == 0) {
                int checkpointIndex = swipe / 65;
                // The 65th fast fling can still own kinetic motion when the loop reaches this
                // checkpoint. Waiting for idle without another physical input lets a healthy,
                // fully prepared D runway carry the reader beyond the intentionally fixed B/C
                // accumulation circuit. End every phase with the same real reverse gesture. This
                // adds four inputs to the stress (260 + 4), preserves equal phase workload, and
                // cancels momentum through the public touch path rather than a test-only scroll
                // coordinate or internal boundary callback.
                assertTrue("Long-session checkpoint momentum fence failed; phase=" +
                                checkpointIndex,
                        device.swipe(x, upTo, x, upFrom, 8));
                forward = false;
                // Close the fixed 65-input cadence interval before endpoint canonicalization.
                // The latter may require a host-dependent number of extra reverse gestures; mixing
                // those into the phase delta compares different workloads (10/10/10/15 in the
                // captured regression) and can manufacture an apparent late-session slowdown.
                waitForLongSessionPhaseIdle(reader, 10000L);
                nativeFrameCheckpoints[checkpointIndex] =
                        ViewerTelemetry.nativeFrameStatsSnapshot();
                assertNotNull("Native cadence end evidence disappeared at phase " +
                                checkpointIndex,
                        nativeFrameCheckpoints[checkpointIndex]);
                long canonicalEndpointDeadline = SystemClock.elapsedRealtime() + 90000L;
                ReaderSurfaceView.ScrollPositionSnapshot canonicalPosition =
                        reader.testCurrentScrollPositionSnapshot();
                String canonicalPath = reader.testCurrentNtkEpisodePath();
                int canonicalizingSwipes = 0;
                while (SystemClock.elapsedRealtime() < canonicalEndpointDeadline &&
                        !(nextPath.equals(canonicalPath) && canonicalPosition != null &&
                                canonicalPosition.getScrollOffset() <= 2)) {
                    assertTrue("Long-session checkpoint escaped B/C while canonicalizing; path=" +
                                    canonicalPath,
                            nextPath.equals(canonicalPath) || followingPath.equals(canonicalPath));
                    assertTrue("Long-session checkpoint reverse swipe injection failed; phase=" +
                                    checkpointIndex + ",count=" + canonicalizingSwipes,
                            device.swipe(x, upTo, x, upFrom, 8));
                    canonicalizingSwipes++;
                    canonicalPosition = reader.testCurrentScrollPositionSnapshot();
                    canonicalPath = reader.testCurrentNtkEpisodePath();
                }
                assertNotNull("Long-session checkpoint lost scroll state; phase=" +
                        checkpointIndex, canonicalPosition);
                assertEquals("Long-session checkpoint did not return to exact retained 1186; phase=" +
                        checkpointIndex, nextPath, canonicalPath);
                assertTrue("Long-session checkpoint did not reach the retained start; phase=" +
                                checkpointIndex + ",offset=" +
                                canonicalPosition.getScrollOffset(),
                        canonicalPosition.getScrollOffset() <= 2);
                retainedStartTurns[checkpointIndex]++;
                forward = true;
                Log.i(TAG, "longReaderStress canonical phase=" + checkpointIndex
                        + ",extraReverseSwipes=" + canonicalizingSwipes
                        + ",offset=" + canonicalPosition.getScrollOffset());
                waitForLongSessionPhaseIdle(reader, 10000L);
                ReaderV2Activity.CleanPhysicalSourceSnapshot canonicalPhysical =
                        waitForLongSessionCanonicalPhysical(
                                reader,
                                nextPath,
                                longSessionCanonicalFirstSource,
                                longSessionLastPhysicalFrameToken,
                                15000L);
                assertNotNull("Long-session checkpoint did not physically commit the canonical " +
                        "retained start; phase=" + checkpointIndex
                        + ",position=" + reader.testCurrentScrollPositionSnapshot()
                        + ",latestPhysical=" + cleanPhysicalSourceSnapshot(reader)
                        + ",coverage=" + reader.testVisibleCoverageSnapshot()
                        + ",afterFrameToken=" + longSessionLastPhysicalFrameToken,
                        canonicalPhysical);
                longSessionLastPhysicalFrameToken = canonicalPhysical.getFrameToken();
                nativeRetirementCheckpoints[checkpointIndex] =
                        reader.testNativeRetirementStatsSnapshot();
                assertNotNull("Native cadence evidence disappeared at swipe " + (swipe + 1),
                        nativeFrameCheckpoints[checkpointIndex]);
                List<ReaderSurfaceView.FrameStatsSnapshot> completedPhase =
                        reader.testTakeFrameStatsSnapshots();
                frameSegments.addAll(completedPhase);
                for (ReaderSurfaceView.FrameStatsSnapshot segment : completedPhase) {
                    phaseFrameSamples[checkpointIndex] += segment.getSamples();
                    phaseMissedIntervals[checkpointIndex] += segment.getMissedIntervals();
                    phaseWorstP95[checkpointIndex] = Math.max(
                            phaseWorstP95[checkpointIndex], segment.getTotalP95());
                    phaseNoCanvas[checkpointIndex] += segment.getNoCanvas();
                    phaseCoalesced[checkpointIndex] += segment.getCoalesced();
                    phasePipelineFrames[checkpointIndex] += segment.getPipelineFrames();
                    phaseFunctionalSubmissionSamples[checkpointIndex] +=
                            segment.getFunctionalSubmissionSamples();
                    phaseFunctionalSubmissionEligiblePairs[checkpointIndex] +=
                            segment.getFunctionalSubmissionEligiblePairs();
                    phaseFunctionalSubmissionInvalidPairs[checkpointIndex] +=
                            segment.getFunctionalSubmissionInvalidPairs();
                    phaseFunctionalInputGestures[checkpointIndex] +=
                            segment.getFunctionalInputGestures();
                    phaseFunctionalGesturesWithValidPair[checkpointIndex] +=
                            segment.getFunctionalGesturesWithValidPair();
                    if (segment.getCadenceQualificationFailed()) {
                        phaseCadenceQualificationFailedSegments[checkpointIndex]++;
                    }
                }
                Runtime.getRuntime().gc();
                waitForLongSessionPhaseIdle(reader, 10000L);
                Debug.MemoryInfo checkpoint = new Debug.MemoryInfo();
                Debug.getMemoryInfo(checkpoint);
                pssCheckpointsKb[checkpointIndex] = checkpoint.getTotalPss();
                Log.i(TAG, "longReaderStress swipes=" + (swipe + 1)
                        + ",pssKb=" + checkpoint.getTotalPss()
                        + ",page=" + reader.testCurrentPage()
                        + ",path=" + reader.testCurrentNtkEpisodePath());
            }
        }
        waitForLongSessionPhaseIdle(reader, 10000L);
        nativeRetirementCheckpoints[3] = reader.testNativeRetirementStatsSnapshot();

        List<ReaderSurfaceView.FrameStatsSnapshot> trailingSegments =
                reader.testTakeFrameStatsSnapshots();
        frameSegments.addAll(trailingSegments);
        for (ReaderSurfaceView.FrameStatsSnapshot segment : trailingSegments) {
            phaseFrameSamples[3] += segment.getSamples();
            phaseMissedIntervals[3] += segment.getMissedIntervals();
            phaseWorstP95[3] = Math.max(phaseWorstP95[3], segment.getTotalP95());
            phaseNoCanvas[3] += segment.getNoCanvas();
            phaseCoalesced[3] += segment.getCoalesced();
            phasePipelineFrames[3] += segment.getPipelineFrames();
            phaseFunctionalSubmissionSamples[3] +=
                    segment.getFunctionalSubmissionSamples();
            phaseFunctionalSubmissionEligiblePairs[3] +=
                    segment.getFunctionalSubmissionEligiblePairs();
            phaseFunctionalSubmissionInvalidPairs[3] +=
                    segment.getFunctionalSubmissionInvalidPairs();
            phaseFunctionalInputGestures[3] += segment.getFunctionalInputGestures();
            phaseFunctionalGesturesWithValidPair[3] +=
                    segment.getFunctionalGesturesWithValidPair();
            if (segment.getCadenceQualificationFailed()) {
                phaseCadenceQualificationFailedSegments[3]++;
            }
        }
        assertTrue("Expected bounded long-session frame evidence", !frameSegments.isEmpty());
        for (int phase = 0; phase < followingTurns.length; phase++) {
            assertTrue("Long-session phase never crossed into following episode; phase=" + phase,
                    followingTurns[phase] >= 1);
            assertTrue("Long-session phase never returned to retained start; phase=" + phase,
                    retainedStartTurns[phase] >= 1);
        }
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
        assertTrue("Long-session main/render callback p95 regressed: " +
                        worstSegmentP95,
                worstSegmentP95 < STRICT_SCROLL_P95_MS);
        double missedPercent = missedIntervals * 100.0 / Math.max(1, frameSamples);
        assertTrue("Long-session callback jank grew above 1%; percent=" +
                        missedPercent,
                missedPercent < STRICT_SCROLL_MISSED_PERCENT);
        for (int phase = 0; phase < phaseFrameSamples.length; phase++) {
            assertTrue("Long-session surface phase lacked evidence; phase=" + phase +
                            ",samples=" + phaseFrameSamples[phase],
                    phaseFrameSamples[phase] >= 100);
            double phaseMissedPercent = phaseMissedIntervals[phase] * 100.0 /
                    Math.max(1, phaseFrameSamples[phase]);
            Log.i(TAG, "longReaderSurfaceCadence phase=" + phase
                    + ",samples=" + phaseFrameSamples[phase]
                    + ",missedPercent=" + phaseMissedPercent
                    + ",worstP95=" + phaseWorstP95[phase]
                    + ",noCanvas=" + phaseNoCanvas[phase]
                    + ",coalesced=" + phaseCoalesced[phase]
                    + ",pipeline=" + phasePipelineFrames[phase]
                    + ",functionalSamples=" + phaseFunctionalSubmissionSamples[phase]
                    + ",functionalEligible=" +
                            phaseFunctionalSubmissionEligiblePairs[phase]
                    + ",functionalInvalid=" + phaseFunctionalSubmissionInvalidPairs[phase]
                    + ",functionalGestures=" + phaseFunctionalInputGestures[phase]
                    + ",functionalGesturesWithPair=" +
                            phaseFunctionalGesturesWithValidPair[phase]
                    + ",cadenceQualificationFailedSegments=" +
                            phaseCadenceQualificationFailedSegments[phase]);
        }
        double firstPhaseMissedPercent = phaseMissedIntervals[0] * 100.0 /
                Math.max(1, phaseFrameSamples[0]);
        double lastPhaseMissedPercent = phaseMissedIntervals[3] * 100.0 /
                Math.max(1, phaseFrameSamples[3]);
        assertTrue("Long-session render work accumulated; firstP95=" + phaseWorstP95[0] +
                        ",lastP95=" + phaseWorstP95[3],
                phaseWorstP95[3] <= Math.max(16.0f, phaseWorstP95[0] + 2.0f));
        assertTrue("Long-session callback gaps accumulated; firstPercent=" +
                        firstPhaseMissedPercent + ",lastPercent=" + lastPhaseMissedPercent,
                lastPhaseMissedPercent <= firstPhaseMissedPercent + 1.0);

        ReaderSurfaceView.NativeRetirementStatsSnapshot nativeRetirementFinal =
                nativeRetirementCheckpoints[3];
        long mailboxRetirements = nativeRetirementFinal.getMailboxSuperseded() -
                nativeRetirementBaseline.getMailboxSuperseded();
        long presentFailures = nativeRetirementFinal.getPresentFailed() -
                nativeRetirementBaseline.getPresentFailed();
        long lifecycleRetirements = nativeRetirementFinal.getLifecycleRetired() -
                nativeRetirementBaseline.getLifecycleRetired();
        long unknownRetirements = nativeRetirementFinal.getUnknown() -
                nativeRetirementBaseline.getUnknown();
        long rendererFatals = nativeRetirementFinal.getRendererFatal() -
                nativeRetirementBaseline.getRendererFatal();
        long rendererRecreates = nativeRetirementFinal.getRecreate() -
                nativeRetirementBaseline.getRecreate();
        long permanentFallbacks = nativeRetirementFinal.getPermanentFallback() -
                nativeRetirementBaseline.getPermanentFallback();
        Log.i(TAG, "longReaderNativeRetirement mailbox=" + mailboxRetirements
                + ",presentFailed=" + presentFailures
                + ",lifecycle=" + lifecycleRetirements
                + ",unknown=" + unknownRetirements
                + ",fatal=" + rendererFatals
                + ",recreate=" + rendererRecreates
                + ",fallback=" + permanentFallbacks);
        assertEquals("Active stress retired a frame through a lifecycle race",
                0L, lifecycleRetirements);
        assertEquals("Active stress observed an unknown native retirement reason",
                0L, unknownRetirements);
        // Recovery deliberately destroys or replaces native residency. That policy is covered by
        // its focused lifecycle test; accepting it here would make the PSS slope incomparable.
        assertEquals("Long-session accumulation observed a native presentation failure",
                0L, presentFailures);
        assertEquals("Long-session accumulation observed a renderer fatal",
                0L, rendererFatals);
        assertEquals("Long-session accumulation recreated the native renderer",
                0L, rendererRecreates);
        assertEquals("Long-session accumulation fell back from the native renderer",
                0L, permanentFallbacks);

        double[] phaseFps = new double[4];
        double[] phaseSlowPercent = new double[4];
        boolean nativeFallbackDuringStress = permanentFallbacks > 0L;
        for (int phase = 0; phase < nativeFrameCheckpoints.length; phase++) {
            ViewerTelemetry.NativeFrameStatsSnapshot previousNative =
                    nativeFramePhaseStarts[phase];
            ViewerTelemetry.NativeFrameStatsSnapshot currentNative =
                    nativeFrameCheckpoints[phase];
            assertNotNull("Native cadence start evidence disappeared during phase " + phase,
                    previousNative);
            assertNotNull("Native cadence end evidence disappeared during phase " + phase,
                    currentNative);
            assertEquals("Native telemetry generation changed during long-session phase " + phase,
                    previousNative.getGeneration(), currentNative.getGeneration());
            long phaseIntervals = currentNative.getScrollIntervals()
                    - previousNative.getScrollIntervals();
            long phaseIntervalNanos = currentNative.getScrollIntervalNanos()
                    - previousNative.getScrollIntervalNanos();
            long phaseSlowIntervals = currentNative.getSlowIntervals()
                    - previousNative.getSlowIntervals();
            boolean fallbackReached = nativeRetirementCheckpoints[phase]
                    .getPermanentFallback() >
                    nativeRetirementBaseline.getPermanentFallback();
            if (fallbackReached) {
                Log.i(TAG, "longReaderCadence phase=" + phase
                        + ",nativeFallback=true,intervals=" + phaseIntervals);
                break;
            }
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
        }
        if (!nativeFallbackDuringStress) {
            assertTrue("Native presentation cadence degraded over the long session; firstFps="
                            + phaseFps[0] + ",lastFps=" + phaseFps[3],
                    phaseFps[3] >= phaseFps[0] * 0.85);
            assertTrue("Native slow-interval rate grew over the long session; firstPercent="
                            + phaseSlowPercent[0] + ",lastPercent=" + phaseSlowPercent[3],
                    phaseSlowPercent[3] <= phaseSlowPercent[0] + 5.0);
        }

        Runtime.getRuntime().gc();
        waitForLongSessionPhaseIdle(reader, 10000L);
        ReaderSurfaceView.NativeRetirementStatsSnapshot postFinalizationRetirement =
                reader.testNativeRetirementStatsSnapshot();
        assertEquals("Native presentation failed while finalizing the memory sample",
                nativeRetirementFinal.getPresentFailed(),
                postFinalizationRetirement.getPresentFailed());
        assertEquals("Lifecycle retirement occurred while finalizing the memory sample",
                nativeRetirementFinal.getLifecycleRetired(),
                postFinalizationRetirement.getLifecycleRetired());
        assertEquals("Unknown retirement occurred while finalizing the memory sample",
                nativeRetirementFinal.getUnknown(),
                postFinalizationRetirement.getUnknown());
        assertEquals("Renderer fatal occurred while finalizing the memory sample",
                nativeRetirementFinal.getRendererFatal(),
                postFinalizationRetirement.getRendererFatal());
        assertEquals("Renderer recreation occurred while finalizing the memory sample",
                nativeRetirementFinal.getRecreate(),
                postFinalizationRetirement.getRecreate());
        assertEquals("Renderer fallback occurred while finalizing the memory sample",
                nativeRetirementFinal.getPermanentFallback(),
                postFinalizationRetirement.getPermanentFallback());
        assertEquals("Native attach generation changed while finalizing the memory sample",
                nativeRetirementFinal.getAttachEpoch(),
                postFinalizationRetirement.getAttachEpoch());
        Debug.MemoryInfo finalMemory = new Debug.MemoryInfo();
        Debug.getMemoryInfo(finalMemory);
        long warmedBaselineGrowthKb =
                finalMemory.getTotalPss() - baselineMemory.getTotalPss();
        // Phase zero is the fixed-content residency warm-up. The accumulation gate compares the
        // remaining three identical B/C circuits instead of charging first-touch bitmap/native
        // residency against a test that is specifically about continued growth.
        long totalPssGrowthKb = finalMemory.getTotalPss() - pssCheckpointsKb[0];
        long settledPssGrowthKb = pssCheckpointsKb[3] - pssCheckpointsKb[1];
        long latePssGrowthKb = pssCheckpointsKb[3] - pssCheckpointsKb[2];
        long minimumCanonicalPssKb = pssCheckpointsKb[0];
        long maximumCanonicalPssKb = pssCheckpointsKb[0];
        for (long checkpointPssKb : pssCheckpointsKb) {
            minimumCanonicalPssKb = Math.min(minimumCanonicalPssKb, checkpointPssKb);
            maximumCanonicalPssKb = Math.max(maximumCanonicalPssKb, checkpointPssKb);
        }
        long canonicalPssRangeKb = maximumCanonicalPssKb - minimumCanonicalPssKb;
        Log.i(TAG, "longReaderMemory finalPssKb=" + finalMemory.getTotalPss()
                + ",growthKb=" + totalPssGrowthKb
                + ",warmedBaselineGrowthKb=" + warmedBaselineGrowthKb
                + ",settledGrowthKb=" + settledPssGrowthKb
                + ",canonicalRangeKb=" + canonicalPssRangeKb
                + ",lateGrowthKb=" + latePssGrowthKb
                + ",javaUsedKb=" + ((Runtime.getRuntime().totalMemory() -
                Runtime.getRuntime().freeMemory()) / 1024L)
                + ",nativeHeapKb=" + (Debug.getNativeHeapAllocatedSize() / 1024L));
        assertTrue("Long-session PSS exceeded the warmed fixed-content envelope; growthKb="
                        + warmedBaselineGrowthKb,
                warmedBaselineGrowthKb <= 196608L);
        // Checkpoint zero is a second independent baseline after one measured circuit. Keeping
        // both fences prevents a one-time first phase from hiding an already-growing warm state.
        assertTrue("Long-session PSS exceeded the complete two-episode residency envelope; growthKb="
                        + totalPssGrowthKb,
                totalPssGrowthKb <= 196608L);
        // One 64 MiB last-phase fence alone accepts a linear 50 MiB/phase leak. Canonical B-start
        // endpoints let the same envelope cover the final two complete phases as a convergence
        // proof without comparing different episode/window residency.
        assertTrue("Long-session PSS did not converge across canonical endpoints; settledGrowthKb="
                        + settledPssGrowthKb,
                settledPssGrowthKb <= 65536L);
        assertTrue("Long-session PSS range kept expanding across canonical endpoints; rangeKb="
                        + canonicalPssRangeKb,
                canonicalPssRangeKb <= 65536L);
        assertTrue("Long-session PSS kept growing after content residency stabilized; lateGrowthKb="
                        + latePssGrowthKb,
                latePssGrowthKb <= 65536L);

        // The stress intentionally evicts old decoded pixels. Canonical completion ownership must
        // survive that LRU activity, and an early silent [current]-only episode-list miss must not
        // poison the later physical boundary. First return to exact 1186: a final stress gesture
        // may legitimately have stopped in either boundary chapter, and already prepared 1188+
        // must not make the 1186 -> 1187 oracle compare against the wrong starting identity.
        long restoreNextDeadline = SystemClock.elapsedRealtime() + 30000L;
        String restoredPath = reader.testCurrentNtkEpisodePath();
        while (SystemClock.elapsedRealtime() < restoreNextDeadline &&
                !nextPath.equals(restoredPath)) {
            boolean restoreForward = currentPath.equals(restoredPath);
            if (restoreForward) {
                device.swipe(x, upFrom, x, upTo, 8);
            } else {
                // Exact 1187 and any later already-appended chapter are physically after 1186.
                device.swipe(x, upTo, x, upFrom, 8);
            }
            restoredPath = reader.testCurrentNtkEpisodePath();
        }
        Log.i(TAG, "longReaderStress restoreTarget=" + nextPath
                + ",restoredPath=" + restoredPath
                + ",page=" + reader.testCurrentPage());
        assertEquals("Long-session stress did not return to exact 1186 before boundary proof",
                nextPath, restoredPath);

        // Verify the following runway and the actual edge transition without a Home round-trip or
        // a second reader launch.
        long followingDeadline = SystemClock.elapsedRealtime() + 90000L;
        while (SystemClock.elapsedRealtime() < followingDeadline &&
                !reader.testHasPreparedEpisodeRunway(following, 4)) {
            SystemClock.sleep(100L);
        }
        assertTrue("Long-session eviction lost the following episode's exact recoverable runway",
                reader.testHasPreparedEpisodeRunway(following, 4));
        assertEquals(
                "Physical bottom did not attach the following episode after long scrolling",
                followingPath,
                scrollPhysicallyForwardUntilEpisodeChanges(
                        device, reader, nextPath, 30000L));
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
                        !reader.testHasPreparedEpisodeRunway(next, 4))) {
            SystemClock.sleep(100L);
        }
        assertTrue("Current chapter must be drawable before entering split screen",
                reader.testHasFullyReadyEpisode(current));
        assertTrue("Next chapter must have an exact recoverable runway before split screen",
                reader.testHasPreparedEpisodeRunway(next, 4));

        UiObject2 beforeSplitActual = device.wait(
                Until.findObject(By.descStartsWith("actual:")),
                30000L);
        assertNotNull("Reader never produced its pre-split physical frame", beforeSplitActual);
        String beforeSplitDescription = String.valueOf(
                beforeSplitActual.getContentDescription());

        enterPixelLauncherSplitScreen(device);
        long splitDeadline = SystemClock.elapsedRealtime() + 8000L;
        while (SystemClock.elapsedRealtime() < splitDeadline && !reader.isInMultiWindowMode()) {
            SystemClock.sleep(16L);
        }
        assertTrue("Reader did not enter Android split-screen multi-window mode",
                reader.isInMultiWindowMode());

        UiObject2 splitStrip = waitForFreshPhysicalReaderNode(
                device,
                reader,
                beforeSplitDescription,
                false,
                30000L);
        assertNotNull(
                "Split reader never produced a fresh clean physical frame",
                splitStrip);
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
        long nextLiveDeadline = SystemClock.elapsedRealtime() + 30000L;
        while (SystemClock.elapsedRealtime() < nextLiveDeadline &&
                !reader.testHasReadyEpisodeRunway(next, 4)) {
            SystemClock.sleep(16L);
        }
        assertTrue("Entered split-screen successor did not restore four live pages",
                reader.testHasReadyEpisodeRunway(next, 4));

        UiObject2 beforeLandscapeActual = device.findObject(By.descStartsWith("actual:"));
        assertNotNull("Split reader lost its physical frame before rotation",
                beforeLandscapeActual);
        String beforeLandscapeDescription = String.valueOf(
                beforeLandscapeActual.getContentDescription());

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

        UiObject2 landscapeStrip = waitForFreshPhysicalReaderNode(
                device,
                reader,
                beforeLandscapeDescription,
                true,
                30000L);
        assertNotNull(
                "Landscape split reader never produced a fresh clean physical frame",
                landscapeStrip);
        Rect landscapeBounds = landscapeStrip.getVisibleBounds();
        assertTrue("Landscape split reader has no usable width: " + landscapeBounds,
                landscapeBounds.width() > 200);
        assertTrue("Landscape split reader has no usable height: " + landscapeBounds,
                landscapeBounds.height() > 200);
        assertTrue(
                "Reader did not adopt a side-by-side split window: " + landscapeBounds,
                landscapeBounds.width() < device.getDisplayWidth() * 3 / 4);
        String landscapeDescription = String.valueOf(
                landscapeStrip.getContentDescription());

        long followingReadyDeadline = SystemClock.elapsedRealtime() + 90000L;
        while (SystemClock.elapsedRealtime() < followingReadyDeadline &&
                !reader.testHasPreparedEpisodeRunway(following, 4)) {
            SystemClock.sleep(100L);
        }
        assertTrue(
                "Following chapter lost its exact recoverable p0-p3 runway across split rotation",
                reader.testHasPreparedEpisodeRunway(following, 4));
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

        // Episode metadata can advance on the boundary card before a following body has reached
        // the compositor. Require the immutable p0 identity from a clean physical frame; if its
        // callback is not already visible, one small continued gesture supplies progress without
        // jumping straight to an artificial max-scroll position.
        long followingP0Deadline = SystemClock.elapsedRealtime() + 2000L;
        while (SystemClock.elapsedRealtime() < followingP0Deadline &&
                !reader.testHasPhysicallyPresentedEpisodeSource(following, 0)) {
            SystemClock.sleep(16L);
        }
        if (!reader.testHasPhysicallyPresentedEpisodeSource(following, 0)) {
            int inset = Math.min(120, Math.max(24, landscapeBounds.height() / 8));
            device.swipe(
                    landscapeBounds.centerX(),
                    landscapeBounds.bottom - inset,
                    landscapeBounds.centerX(),
                    landscapeBounds.top + inset,
                    12);
        }
        followingP0Deadline = SystemClock.elapsedRealtime() + 30000L;
        while (SystemClock.elapsedRealtime() < followingP0Deadline &&
                !reader.testHasPhysicallyPresentedEpisodeSource(following, 0)) {
            SystemClock.sleep(16L);
        }
        assertTrue("Following p0 never entered a clean physical viewport identity",
                reader.testHasPhysicallyPresentedEpisodeSource(following, 0));

        long followingLiveDeadline = SystemClock.elapsedRealtime() + 90000L;
        while (SystemClock.elapsedRealtime() < followingLiveDeadline &&
                !reader.testHasReadyEpisodeRunway(following, 4)) {
            SystemClock.sleep(16L);
        }
        assertTrue("Entered following chapter did not restore four live pages",
                reader.testHasReadyEpisodeRunway(following, 4));
        UiObject2 followingStrip = waitForFreshPhysicalReaderNode(
                device,
                reader,
                landscapeDescription,
                true,
                30000L);
        assertNotNull("Following chapter never produced a fresh clean physical body frame",
                followingStrip);
        ReaderSurfaceView.VisibleCoverageSnapshot followingCoverage =
                reader.testVisibleCoverageSnapshot();
        assertNotNull("Following chapter has no physical coverage snapshot", followingCoverage);
        assertTrue("Following chapter physical frame has no drawable pixels",
                followingCoverage.getDrawablePx() > 0);
        assertEquals("Following chapter physical frame has missing pixels", 0,
                followingCoverage.getMissingPx());
        assertEquals("Following chapter physical frame exposed placeholders", 0,
                followingCoverage.getPlaceholderPx());
        assertEquals("Following chapter clean proof stopped on a transition card", 0,
                followingCoverage.getVisibleCards());
        assertTrue("Following live runway was trimmed before its clean physical frame",
                reader.testHasReadyEpisodeRunway(following, 4));
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

        ReaderV2Activity reader = resumedReader();
        assertNotNull("Expected resumed cellular webtoon reader", reader);
        runOnMain(reader::testResetFrameStatsSnapshot);
        // Loading all 86 offscreen originals is neither necessary nor sufficient for reading UX.
        // Keep the same eight-second post-runway deadline, but spend it on the real contract: p12
        // must become clean and physically reachable while download/decode is still streaming.
        int[] progress = scrollForwardUntilPage(device, 12, 8000L);
        assertTrue("Expected cellular NTK webtoon to keep progressing after the initial runway; current="
                        + progress[0] + ",count=" + progress[1],
                progress[0] >= 12 && progress[1] > 12);
        assertViewportRemainsStationaryThroughDeferredGeometrySettlement(reader);
        ReaderSurfaceView.VisibleCoverageSnapshot coverage = reader.testVisibleCoverageSnapshot();
        assertNotNull("Webtoon p12 traversal lost visible coverage", coverage);
        assertTrue("Webtoon p12 traversal has no drawable pixels", coverage.getDrawablePx() > 0);
        assertEquals("Webtoon p12 traversal exposed missing pixels", 0, coverage.getMissingPx());
        assertEquals("Webtoon p12 traversal exposed placeholders", 0, coverage.getPlaceholderPx());
        assertEquals("Webtoon p12 traversal exposed loading rows", 0, coverage.getVisibleLoading());
        List<ReaderSurfaceView.FrameStatsSnapshot> frameSegments =
                reader.testTakeFrameStatsSnapshots();
        long samples = 0L;
        long missedIntervals = 0L;
        int droppedFrames = 0;
        int missingPx = 0;
        float worstP95 = 0f;
        for (ReaderSurfaceView.FrameStatsSnapshot segment : frameSegments) {
            samples += segment.getSamples();
            missedIntervals += segment.getMissedIntervals();
            droppedFrames += segment.getDroppedFrames();
            missingPx = Math.max(missingPx, segment.getMaxMissingPx());
            worstP95 = Math.max(worstP95, segment.getTotalP95());
        }
        assertTrue("Webtoon p12 traversal lacks physical frame evidence; samples=" + samples,
                samples >= 8L);
        assertEquals("Webtoon p12 traversal dropped a frame", 0, droppedFrames);
        assertEquals("Webtoon p12 traversal exposed a renderer gap", 0, missingPx);
        assertTrue("Webtoon p12 traversal p95 exceeded 16ms; p95=" + worstP95,
                worstP95 < STRICT_SCROLL_P95_MS);
        assertTrue("Webtoon p12 traversal missed intervals reached 1%; missed="
                        + missedIntervals + ",samples=" + samples,
                missedIntervals * 100.0 / Math.max(1L, samples)
                        < STRICT_SCROLL_MISSED_PERCENT);
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

        long runwayDeadline = SystemClock.elapsedRealtime() + 15000L;
        while (SystemClock.elapsedRealtime() < runwayDeadline &&
                !firstReader.testHasReadyEpisodeRunway(resume, 4)) {
            SystemClock.sleep(16L);
        }
        assertTrue("Webtoon did not expose four clean pages before HOME within 15 seconds",
                firstReader.testHasReadyEpisodeRunway(resume, 4));

        ReaderV2Activity.CleanPhysicalSourceSnapshot beforeHome =
                waitForCleanPhysicalSourceSnapshot(firstReader, 15000L);
        assertNotNull("Webtoon never produced a clean physical frame before HOME", beforeHome);
        ReaderV2Activity.CleanPhysicalSourceSnapshot afterHome = performStrictHomeRoundTrip(
                device,
                firstReader,
                resume,
                -1,
                beforeHome.getPresentedUptimeNanos());
        assertTrue("Webtoon HOME did not produce a newer physical presentation",
                afterHome.getPresentedUptimeNanos() > beforeHome.getPresentedUptimeNanos());

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
        ReaderV2Activity reader = resumedReader();
        assertNotNull("Expected resumed manhwa reader", reader);
        assertEquals("/manhwa/10073/238729", reader.testCurrentNtkEpisodePath());

        Manga current = reader.testEpisodeByPath("/manhwa/10073/238729");
        assertNotNull("Expected exact current-volume metadata", current);
        // Poll product state directly rather than hammering Accessibility every 100 ms. The latter
        // filled logcat with thousands of selector misses and obscured whether a 30-second failure
        // came from transport, decode, or publication. The timeout and all-176 invariant remain
        // unchanged.
        long currentReadyStartedAt = SystemClock.elapsedRealtime();
        while (SystemClock.elapsedRealtime() - currentReadyStartedAt < 30000L &&
                !reader.testHasFullyReadyEpisode(current)) {
            SystemClock.sleep(16L);
        }
        assertTrue(
                "Expected all 176 current-episode images to be drawable; elapsedMs="
                        + (SystemClock.elapsedRealtime() - currentReadyStartedAt)
                        + ",readiness=" + reader.testPageReadinessSnapshot(),
                reader.testHasFullyReadyEpisode(current));
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

        // Traverse the complete 176-image volume with production touch input. An older diagnostic
        // jumped the internal scroll coordinate to Float.MAX_VALUE and invoked the boundary
        // callback directly. That bypassed the physical-gesture revision and therefore asked the
        // product to violate its clean-tail ownership fence. The near-tail continuation test below
        // owns the <=500 ms preattached-boundary SLA; this test owns full physical traversal and
        // exact two-volume source ordering.
        String transitioned = scrollEntireEpisodePhysicallyIntoNext(
                device,
                reader,
                current,
                180000L);
        assertEquals("Forward boundary must never select the previous volume",
                "/manhwa/10073/238730", transitioned);

        // Keep reading instead of demanding that 175 invisible bodies download while the user is
        // parked on p0. The bounded pipeline is intentionally viewport-driven. A second complete
        // physical traversal proves that /238730 wins foreground scheduling and that its exact
        // tail selects /238731 without reusing the already-consumed /238729 source.
        Manga following = reader.testEpisodeByPath("/manhwa/10073/238731");
        assertNotNull("Expected following volume metadata", following);
        String followingTransition = scrollEntireEpisodePhysicallyIntoNext(
                device,
                reader,
                expectedNext,
                180000L);
        assertEquals(
                "The following boundary must retain /238731 instead of reusing /238729",
                following.getNtkEpisodePath(),
                followingTransition);
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

    private UiObject2 revealToolbarButton(
            UiDevice device,
            String resourceName,
            long timeoutMs
    ) {
        long deadline = SystemClock.elapsedRealtime() + timeoutMs;
        while (SystemClock.elapsedRealtime() < deadline) {
            UiObject2 button = device.findObject(By.res(PACKAGE_NAME, resourceName));
            if (button != null) return button;
            // Use the same center tap as a reader. Do not call the Activity's test-only toolbar
            // entry point: this test owns the production touch routing and visible controls.
            device.click(device.getDisplayWidth() / 2, device.getDisplayHeight() / 2);
            button = device.wait(
                    Until.findObject(By.res(PACKAGE_NAME, resourceName)),
                    750L);
            if (button != null) return button;
        }
        return null;
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
                CharSequence description;
                try {
                    description = row.getContentDescription();
                } catch (StaleObjectException ignored) {
                    // A network refresh can replace RecyclerView holders between the query and
                    // this read. Re-query the authoritative list instead of failing the run.
                    continue;
                }
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
                CharSequence description;
                try {
                    description = row.getContentDescription();
                } catch (StaleObjectException ignored) {
                    // The adapter may publish a refreshed episode snapshot while UiAutomator is
                    // iterating. The next pass obtains a holder from the new snapshot.
                    continue;
                }
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
    ) {
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
    ) throws Exception {
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
        int swipes = 0;
        while (SystemClock.elapsedRealtime() < deadline) {
            assertTrue("Physical forward swipe injection failed; source=" + previousPath
                            + ",swipe=" + swipes,
                    device.swipe(x, fromY, x, toY, 8));
            swipes++;
            currentPath = reader.testCurrentNtkEpisodePath();
            if (currentPath != null && !currentPath.equals(previousPath)) {
                Log.i(TAG, "physicalEpisodeTransition source=" + previousPath
                        + ",target=" + currentPath
                        + ",swipes=" + swipes);
                return currentPath;
            }
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

    private UiObject2 waitForFreshPhysicalReaderNode(
            UiDevice device,
            ReaderV2Activity reader,
            String previousDescription,
            boolean sideBySide,
            long timeoutMs
    ) {
        long deadline = SystemClock.elapsedRealtime() + timeoutMs;
        while (SystemClock.elapsedRealtime() < deadline) {
            ReaderSurfaceView.VisibleCoverageSnapshot coverage =
                    reader.testVisibleCoverageSnapshot();
            boolean cleanCoverage = coverage != null
                    && coverage.getDrawablePx() > 0
                    && coverage.getMissingPx() == 0
                    && coverage.getPlaceholderPx() == 0
                    && coverage.getVisibleCards() == 0
                    && !reader.testNativeSurfaceRevealPending();
            if (cleanCoverage) {
                for (UiObject2 candidate :
                        device.findObjects(By.descStartsWith("actual:"))) {
                    CharSequence description = candidate.getContentDescription();
                    if (description == null ||
                            description.toString().equals(previousDescription)) {
                        continue;
                    }
                    Rect bounds = candidate.getVisibleBounds();
                    if (bounds.width() <= 200 || bounds.height() <= 200) continue;
                    boolean adoptedSplitBounds = sideBySide
                            ? bounds.width() < device.getDisplayWidth() * 3 / 4
                            : bounds.height() < device.getDisplayHeight() * 3 / 4;
                    if (adoptedSplitBounds) return candidate;
                }
            }
            SystemClock.sleep(16L);
        }
        return null;
    }

    private ReaderV2Activity reopenExistingReaderFromPixelLauncherRecents(UiDevice device)
            throws Exception {
        boolean appEverForegrounded = false;
        for (int attempt = 0; attempt < 2; attempt++) {
            // The key-injection return value is not a window-state oracle. Bind the click to the
            // settled Pixel Launcher carousel so a stale MangaView home icon cannot satisfy it.
            device.pressRecentApps();
            UiObject2 overview = device.wait(
                    Until.findObject(By.res(PIXEL_LAUNCHER_PACKAGE, "overview_panel")),
                    8000L);
            if (overview == null) return null;
            UiObject2 mangaTask = waitForStableMangaRecentTask(device, 8000L);
            if (mangaTask == null) return null;
            try {
                mangaTask.click();
            } catch (StaleObjectException staleTask) {
                return null;
            }

            long resumeDeadline = SystemClock.elapsedRealtime() + 15000L;
            long launcherHomeStableSince = -1L;
            boolean missedIntoLauncherHome = false;
            while (SystemClock.elapsedRealtime() < resumeDeadline) {
                String foregroundPackage = device.getCurrentPackageName();
                if (PACKAGE_NAME.equals(foregroundPackage)) {
                    appEverForegrounded = true;
                }
                ReaderV2Activity candidate = resumedReader();
                if (candidate != null) {
                    appEverForegrounded = true;
                    return candidate;
                }

                boolean launcherHome = PIXEL_LAUNCHER_PACKAGE.equals(foregroundPackage)
                        && !device.hasObject(
                                By.res(PIXEL_LAUNCHER_PACKAGE, "overview_panel"))
                        && device.hasObject(By.res(PIXEL_LAUNCHER_PACKAGE, "workspace"));
                if (!appEverForegrounded && launcherHome) {
                    if (launcherHomeStableSince < 0L) {
                        launcherHomeStableSince = SystemClock.elapsedRealtime();
                    } else if (SystemClock.elapsedRealtime() - launcherHomeStableSince >= 750L) {
                        missedIntoLauncherHome = true;
                        break;
                    }
                } else {
                    launcherHomeStableSince = -1L;
                }
                SystemClock.sleep(16L);
            }

            // Never turn a real app foreground/resume failure into a second launch. One bounded
            // retry is reserved solely for the confirmed Launcher Overview -> Home miss.
            if (appEverForegrounded || !missedIntoLauncherHome || attempt > 0) return null;
        }
        return null;
    }

    private UiObject2 waitForStableMangaRecentTask(UiDevice device, long timeoutMs) {
        long deadline = SystemClock.elapsedRealtime() + timeoutMs;
        Rect stableBounds = null;
        long stableSince = -1L;
        while (SystemClock.elapsedRealtime() < deadline) {
            try {
                UiObject2 overview = device.findObject(
                        By.res(PIXEL_LAUNCHER_PACKAGE, "overview_panel"));
                UiObject2 candidate = overview == null ? null : overview.findObject(
                        By.res(PIXEL_LAUNCHER_PACKAGE, "task")
                                .descContains("MangaView")
                                .clickable(true));
                if (candidate != null && candidate.isClickable() && candidate.isEnabled()) {
                    Rect bounds = candidate.getVisibleBounds();
                    if (bounds.width() > 200 && bounds.height() > 200) {
                        long now = SystemClock.elapsedRealtime();
                        if (stableBounds != null && stableBounds.equals(bounds)) {
                            if (stableSince >= 0L && now - stableSince >= 300L) {
                                // Return this iteration's object, not the node captured before the
                                // carousel settled; click() then targets the task itself.
                                return candidate;
                            }
                        } else {
                            stableBounds = new Rect(bounds);
                            stableSince = now;
                        }
                        SystemClock.sleep(50L);
                        continue;
                    }
                }
            } catch (StaleObjectException ignored) {
                // Launcher is still replacing carousel nodes; reacquire under overview_panel.
            }
            stableBounds = null;
            stableSince = -1L;
            SystemClock.sleep(50L);
        }
        return null;
    }

    private UiObject2 waitForFreshCleanPhysicalReaderNode(
            UiDevice device,
            ReaderV2Activity reader,
            String previousDescription,
            ReaderV2Activity.CleanPhysicalSourceSnapshot previousPhysical,
            long timeoutMs
    ) {
        long deadline = SystemClock.elapsedRealtime() + timeoutMs;
        while (SystemClock.elapsedRealtime() < deadline) {
            ReaderSurfaceView.VisibleCoverageSnapshot coverage =
                    reader.testVisibleCoverageSnapshot();
            boolean cleanCoverage = coverage != null
                    && coverage.getDrawablePx() > 0
                    && coverage.getMissingPx() == 0
                    && coverage.getPlaceholderPx() == 0
                    && coverage.getVisibleLoading() == 0
                    && coverage.getVisibleErrors() == 0
                    && coverage.getVisibleCards() == 0
                    && !reader.testNativeSurfaceRevealPending();
            ReaderV2Activity.CleanPhysicalSourceSnapshot currentPhysical =
                    cleanPhysicalSourceSnapshot(reader);
            boolean freshLowerSource = currentPhysical != null
                    && currentPhysical.getSourcePage() >= 0
                    && currentPhysical.getSourcePage() < previousPhysical.getSourcePage()
                    && currentPhysical.getPresentedUptimeNanos() >
                            previousPhysical.getPresentedUptimeNanos();
            if (cleanCoverage && freshLowerSource) {
                for (UiObject2 candidate :
                        device.findObjects(By.descStartsWith("actual:"))) {
                    CharSequence description = candidate.getContentDescription();
                    if (description == null ||
                            description.toString().equals(previousDescription)) {
                        continue;
                    }
                    Rect bounds = candidate.getVisibleBounds();
                    if (bounds.width() > 200 && bounds.height() > 200) return candidate;
                }
            }
            SystemClock.sleep(16L);
        }
        return null;
    }

    private ReaderV2Activity.CleanPhysicalSourceSnapshot waitForCleanPhysicalSourceSnapshot(
            ReaderV2Activity reader,
            long timeoutMs
    ) {
        long deadline = SystemClock.elapsedRealtime() + timeoutMs;
        while (SystemClock.elapsedRealtime() < deadline) {
            ReaderSurfaceView.VisibleCoverageSnapshot coverage =
                    reader.testVisibleCoverageSnapshot();
            ReaderV2Activity.CleanPhysicalSourceSnapshot physical =
                    cleanPhysicalSourceSnapshot(reader);
            if (physical != null
                    && physical.getSourcePage() >= 0
                    && physical.getPresentedUptimeNanos() > 0L
                    && coverage != null
                    && coverage.getDrawablePx() > 0
                    && coverage.getMissingPx() == 0
                    && coverage.getPlaceholderPx() == 0
                    && coverage.getVisibleLoading() == 0
                    && coverage.getVisibleErrors() == 0
                    && coverage.getVisibleCards() == 0
                    && !reader.testNativeSurfaceRevealPending()) {
                return physical;
            }
            SystemClock.sleep(16L);
        }
        return null;
    }

    private ReaderV2Activity.CleanPhysicalSourceSnapshot waitForCleanPhysicalEpisode(
            ReaderV2Activity reader,
            String expectedPath,
            long afterPresentedUptimeNanos,
            long timeoutMs
    ) {
        long deadline = SystemClock.elapsedRealtime() + timeoutMs;
        while (SystemClock.elapsedRealtime() < deadline) {
            ReaderV2Activity.CleanPhysicalSourceSnapshot physical = cleanPhysicalEpisodeSnapshot(
                    reader,
                    expectedPath,
                    afterPresentedUptimeNanos);
            if (physical != null) return physical;
            SystemClock.sleep(16L);
        }
        return null;
    }

    private ReaderV2Activity.CleanPhysicalSourceSnapshot cleanPhysicalEpisodeSnapshot(
            ReaderV2Activity reader,
            String expectedPath,
            long afterPresentedUptimeNanos
    ) {
        AtomicReference<ReaderV2Activity.CleanPhysicalSourceSnapshot> physicalResult =
                new AtomicReference<>();
        runOnMain(() -> physicalResult.set(
                reader.testCleanPhysicalSourceSnapshot(expectedPath)));
        ReaderV2Activity.CleanPhysicalSourceSnapshot physical = physicalResult.get();
        if (physical == null
                || !expectedPath.equals(physical.getPhysicalEpisodePath())
                || physical.getFirstVisibleSourcePage() < 0
                || physical.getPresentedUptimeNanos() <= afterPresentedUptimeNanos
                || physical.getPhysicalViewportPx() <= 0
                || physical.getDrawablePx() < physical.getPhysicalViewportPx()
                || physical.getMissingPx() != 0
                || physical.getPlaceholderPx() != 0
                || physical.getVisibleLoading() != 0
                || physical.getVisibleErrors() != 0
                || (physical.getVisibleCards() != 0
                    && !(physical.getVisibleCards() == 1
                        && physical.getQualifiedTransitionCard()))
                || physical.getWidthFillFailures() != 0
                || physical.getLowResolutionItems() != 0
                || physical.getNativeSurfaceRevealPending()) {
            return null;
        }
        return physical;
    }

    private ReaderV2Activity.CleanPhysicalSourceSnapshot scrollIntoCleanPhysicalEpisode(
            UiDevice device,
            ReaderV2Activity reader,
            String expectedPath,
            long afterPresentedUptimeNanos,
            long timeoutMs
    ) {
        int x = device.getDisplayWidth() / 2;
        int fromY = Math.min(device.getDisplayHeight() - 160,
                device.getDisplayHeight() * 3 / 4);
        int toY = Math.max(120, device.getDisplayHeight() / 4);
        long deadline = SystemClock.elapsedRealtime() + timeoutMs;
        int swipes = 0;
        while (SystemClock.elapsedRealtime() < deadline) {
            ReaderV2Activity.CleanPhysicalSourceSnapshot physical =
                    cleanPhysicalEpisodeSnapshot(
                            reader,
                            expectedPath,
                            afterPresentedUptimeNanos);
            if (physical != null) {
                Log.i(TAG, "physicalEpisodeBody path=" + expectedPath
                        + ",source=" + physical.getFirstVisibleSourcePage()
                        + ",swipesAfterBoundary=" + swipes);
                return physical;
            }
            assertTrue("Physical body-entry swipe injection failed; path=" + expectedPath
                            + ",swipe=" + swipes,
                    device.swipe(x, fromY, x, toY, 8));
            swipes++;
        }
        return null;
    }

    private ReaderV2Activity.CleanPhysicalSourceSnapshot scrollBackwardIntoCleanPhysicalEpisode(
            UiDevice device,
            ReaderV2Activity reader,
            String expectedPath,
            long timeoutMs
    ) {
        int x = device.getDisplayWidth() / 2;
        int fromY = Math.max(120, device.getDisplayHeight() / 4);
        int toY = Math.min(device.getDisplayHeight() - 160,
                device.getDisplayHeight() * 3 / 4);
        long deadline = SystemClock.elapsedRealtime() + timeoutMs;
        int swipes = 0;
        while (SystemClock.elapsedRealtime() < deadline) {
            ReaderV2Activity.CleanPhysicalSourceSnapshot physical =
                    cleanPhysicalEpisodeSnapshot(reader, expectedPath, 0L);
            if (physical != null) {
                Log.i(TAG, "physicalEpisodeReverseBody path=" + expectedPath
                        + ",source=" + physical.getFirstVisibleSourcePage()
                        + ",swipes=" + swipes);
                return physical;
            }
            assertEquals("Reverse setup escaped the selected episode",
                    expectedPath, reader.testCurrentNtkEpisodePath());
            assertTrue("Physical reverse swipe injection failed; path=" + expectedPath
                            + ",swipe=" + swipes,
                    device.swipe(x, fromY, x, toY, 18));
            swipes++;
        }
        return null;
    }

    private String scrollEntireEpisodePhysicallyIntoNext(
            UiDevice device,
            ReaderV2Activity reader,
            Manga current,
            long timeoutMs
    ) {
        return scrollEntireEpisodePhysicallyIntoNext(
                device,
                reader,
                current,
                timeoutMs,
                FAST_PHYSICAL_SWIPE_STEPS);
    }

    private String scrollEntireEpisodePhysicallyIntoNext(
            UiDevice device,
            ReaderV2Activity reader,
            Manga current,
            long timeoutMs,
            int ordinaryGestureSteps
    ) {
        String currentPath = current.getNtkEpisodePath();
        assertNotNull("Physical full-episode traversal requires an exact source path", currentPath);
        int x = device.getDisplayWidth() / 2;
        int fromY = Math.min(device.getDisplayHeight() - 160,
                device.getDisplayHeight() * 3 / 4);
        int toY = Math.max(120, device.getDisplayHeight() / 4);
        int tailToY = Math.max(toY,
                fromY - Math.max(96, device.getDisplayHeight() / 16));
        long deadline = SystemClock.elapsedRealtime() + timeoutMs;
        int expectedSourceCount = 0;
        int largestCleanSource = -1;
        int swipes = 0;
        boolean fullyReady = false;
        boolean cleanTailPresented = false;
        while (SystemClock.elapsedRealtime() < deadline) {
            fullyReady = reader.testHasFullyReadyEpisode(current);
            int observedSourceCount = reader.testCanonicalEpisodeSourceCount(current);
            if (observedSourceCount > 0) {
                expectedSourceCount = Math.max(expectedSourceCount, observedSourceCount);
            }
            ReaderV2Activity.CleanPhysicalSourceSnapshot physical =
                    cleanPhysicalEpisodeSnapshot(reader, currentPath, 0L);
            if (physical != null) {
                largestCleanSource = Math.max(largestCleanSource, physical.getSourcePage());
                if (fullyReady && expectedSourceCount > 0 &&
                        physical.getSourcePage() >= expectedSourceCount - 1) {
                    cleanTailPresented = true;
                }
            }

            String visiblePath = reader.testCurrentNtkEpisodePath();
            if (!currentPath.equals(visiblePath)) {
                assertTrue("Physical scrolling escaped before the complete episode was ready; "
                                + "path=" + currentPath + ",ready=" + fullyReady
                                + ",sourceCount=" + expectedSourceCount,
                        fullyReady && expectedSourceCount > 0);
                assertTrue("Physical scrolling entered the next episode without presenting the "
                                + "clean tail of " + currentPath + ";largestSource="
                                + largestCleanSource + ",expectedLast="
                                + (expectedSourceCount - 1),
                        cleanTailPresented);
                Log.i(TAG, "physicalFullEpisode source=" + currentPath
                        + ",target=" + visiblePath
                        + ",sources=" + expectedSourceCount
                        + ",largestCleanSource=" + largestCleanSource
                        + ",swipes=" + swipes);
                return visiblePath;
            }

            boolean approachingTail = fullyReady && expectedSourceCount > 0
                    && largestCleanSource >= expectedSourceCount - 2
                    && !cleanTailPresented;
            // A full-height fling can legitimately jump from a short penultimate image straight
            // into the next episode without ever latching the final source. Near the tail, use a
            // slower, shorter physical drag with more MOVE events. This strengthens coverage: the
            // final source still has to commit cleanly, and the extra input/frames remain inside
            // every unchanged cadence and memory assertion.
            int gestureToY = approachingTail ? tailToY : toY;
            // The caller selects either an eight-step fast physical fling for burst/runway stress
            // or a 64-step reading drag when the test also owns sustained frame-cadence evidence.
            // Both paths inject real touch positions and require the same exact clean-tail proof.
            // Keep the final drag conservative so neither profile can jump over the last source.
            int gestureSteps = approachingTail ? 16 : ordinaryGestureSteps;
            ReaderSurfaceView.LifecycleViewportAnchorSnapshot beforeGesture =
                    reader.testCurrentCommittedViewportAnchorSnapshot();
            assertNotNull("Physical forward gesture has no committed viewport anchor; path="
                    + currentPath + ",swipe=" + swipes, beforeGesture);
            assertTrue("Physical full-episode swipe injection failed; path=" + currentPath
                            + ",swipe=" + swipes + ",tail=" + approachingTail,
                    performMonitoredForwardSwipe(
                            device,
                            reader,
                            x,
                            fromY,
                            gestureToY,
                            gestureSteps,
                            currentPath,
                            swipes));
            ReaderSurfaceView.LifecycleViewportAnchorSnapshot afterGesture =
                    reader.testCurrentCommittedViewportAnchorSnapshot();
            assertNotNull("Physical forward gesture lost its committed viewport anchor; path="
                    + currentPath + ",swipe=" + swipes, afterGesture);
            if (currentPath.equals(reader.testCurrentNtkEpisodePath())) {
                assertNoForwardCommittedViewportRollback(
                        currentPath,
                        swipes,
                        beforeGesture,
                        afterGesture);
            }
            swipes++;
            if (swipes % 100 == 0) {
                Log.i(TAG, "physicalFullEpisodeWait path=" + currentPath
                        + ",ready=" + fullyReady
                        + ",sources=" + expectedSourceCount
                        + ",largestCleanSource=" + largestCleanSource
                        + ",cleanTailPresented=" + cleanTailPresented
                        + ",scroll=" + reader.testCurrentScrollPositionSnapshot()
                        + ",readiness=" + reader.testPageReadinessSnapshot()
                        + ",pipeline=" + reader.testRenderPipelineDiagnosticSnapshot());
            }
        }
        throw new AssertionError("Physical scrolling did not traverse the complete episode; path="
                + currentPath + ",ready=" + fullyReady
                + ",sourceCount=" + expectedSourceCount
                + ",largestCleanSource=" + largestCleanSource
                + ",cleanTailPresented=" + cleanTailPresented
                + ",swipes=" + swipes
                + ",scroll=" + reader.testCurrentScrollPositionSnapshot()
                + ",readiness=" + reader.testPageReadinessSnapshot()
                + ",pipeline=" + reader.testRenderPipelineDiagnosticSnapshot());
    }

    private ReaderV2Activity.CleanPhysicalSourceSnapshot
    waitForLongSessionCanonicalPhysical(
            ReaderV2Activity reader,
            String expectedPath,
            int expectedFirstSource,
            long afterFrameToken,
            long timeoutMs
    ) {
        long deadline = SystemClock.elapsedRealtime() + timeoutMs;
        while (SystemClock.elapsedRealtime() < deadline) {
            ReaderSurfaceView.VisibleCoverageSnapshot coverage =
                    reader.testVisibleCoverageSnapshot();
            ReaderV2Activity.CleanPhysicalSourceSnapshot physical =
                    cleanPhysicalSourceSnapshot(reader);
            boolean expectedSource = expectedFirstSource < 0 ||
                    (physical != null &&
                            physical.getFirstVisibleSourcePage() == expectedFirstSource);
            if (physical != null
                    && expectedPath.equals(physical.getPhysicalEpisodePath())
                    && physical.getFirstVisibleSourcePage() >= 0
                    && expectedSource
                    // SurfaceControl completion callbacks can arrive out of timestamp order.
                    // ReaderV2's latest-presentation ledger is producer-ordered by frameToken, so
                    // require a genuinely newer submitted frame instead of a larger callback time.
                    && physical.getFrameToken() > afterFrameToken
                    && coverage != null
                    && coverage.getDrawablePx() > 0
                    && coverage.getMissingPx() == 0
                    && coverage.getPlaceholderPx() == 0
                    && coverage.getVisibleLoading() == 0
                    && coverage.getVisibleErrors() == 0
                    && coverage.getVisibleCards() <= 1
                    && !reader.testNativeSurfaceRevealPending()) {
                return physical;
            }
            SystemClock.sleep(16L);
        }
        return null;
    }

    private ReaderV2Activity.CleanPhysicalSourceSnapshot cleanPhysicalSourceSnapshot(
            ReaderV2Activity reader
    ) {
        AtomicReference<ReaderV2Activity.CleanPhysicalSourceSnapshot> result =
                new AtomicReference<>();
        runOnMain(() -> result.set(reader.testLatestCleanPhysicalPresentationSnapshot()));
        return result.get();
    }

    private PhysicalViewportEvidence physicalViewportEvidence(ReaderV2Activity reader) {
        AtomicReference<PhysicalViewportEvidence> result = new AtomicReference<>();
        runOnMain(() -> result.set(new PhysicalViewportEvidence(
                reader.testCurrentScrollPositionSnapshot(),
                reader.testLatestCleanPhysicalPresentationSnapshot(),
                reader.testVisibleCoverageSnapshot(),
                reader.testCurrentCommittedViewportAnchorSnapshot(),
                reader.testNativeSurfaceRevealPending())));
        return result.get();
    }

    private boolean isCurrentCommittedViewport(PhysicalViewportEvidence evidence) {
        if (evidence == null || evidence.position == null || evidence.physical == null) {
            return false;
        }
        float committedScroll = evidence.physical.getCommittedScrollOffsetPx();
        return Float.isFinite(committedScroll)
                && Math.abs(committedScroll - evidence.position.getScrollOffset())
                <= COMMITTED_SCROLL_COHERENCE_PX;
    }

    private ReaderV2Activity.CleanPhysicalSourceSnapshot performStrictHomeRoundTrip(
            UiDevice device,
            ReaderV2Activity reader,
            Manga expectedEpisode,
            int chapter,
            long afterPresentedUptimeNanos
    ) throws Exception {
        String expectedPath = expectedEpisode.getNtkEpisodePath();
        assertNotNull("HOME round-trip requires an exact episode path", expectedPath);
        assertEquals("HOME round-trip started from the wrong episode; chapter=" + chapter,
                expectedPath, reader.testCurrentNtkEpisodePath());

        // A real user can press HOME while cache compaction or adjacent network work is active.
        // Wait only for the physical gesture to settle and for a clean visible presentation;
        // deliberately leave unrelated maintenance running so lifecycle synchronization is
        // exercised under the production overlap instead of behind a global-idle test fence.
        int stableMotionSamples = 0;
        ReaderSurfaceView.ScrollPositionSnapshot beforeHome = null;
        ReaderV2Activity.CleanPhysicalSourceSnapshot beforePhysical = null;
        long stableDeadline = SystemClock.elapsedRealtime() + 5000L;
        while (SystemClock.elapsedRealtime() < stableDeadline && stableMotionSamples < 5) {
            PhysicalViewportEvidence evidence = physicalViewportEvidence(reader);
            ReaderSurfaceView.ScrollPositionSnapshot position = evidence.position;
            ReaderV2Activity.CleanPhysicalSourceSnapshot physical = evidence.physical;
            if (position != null && !position.getBusy()
                    && physical != null
                    && expectedPath.equals(physical.getPhysicalEpisodePath())
                    && isCurrentCommittedViewport(evidence)
                    && physical.getMissingPx() == 0
                    && physical.getPlaceholderPx() == 0
                    && physical.getVisibleLoading() == 0
                    && physical.getVisibleErrors() == 0
                    && !evidence.revealPending) {
                stableMotionSamples++;
                beforeHome = position;
                beforePhysical = physical;
            } else {
                stableMotionSamples = 0;
                beforeHome = null;
                beforePhysical = null;
            }
            if (stableMotionSamples >= 5) break;
            SystemClock.sleep(50L);
        }
        assertTrue("HOME round-trip never reached a stable clean viewport; chapter=" + chapter,
                stableMotionSamples >= 5);
        assertNotNull("HOME round-trip lost its pre-background position; chapter=" + chapter,
                beforeHome);
        assertNotNull("HOME round-trip has no clean pre-background presentation; chapter="
                + chapter, beforePhysical);
        long preHomePresentationFloor = Math.max(
                afterPresentedUptimeNanos,
                beforePhysical.getPresentedUptimeNanos());

        device.pressHome();
        assertTrue("HOME did not background the ten-chapter reader; chapter=" + chapter,
                device.wait(Until.gone(By.pkg(PACKAGE_NAME)), 5000L));
        // UiAutomator may wait for an input-idle boundary before KEYCODE_HOME is delivered. A
        // blocked physical fling can legitimately resume from newly arrived pixels during that
        // interval, so the sample taken before pressHome() is not necessarily what the user saw
        // at the real lifecycle edge. onUserLeaveHint has now frozen and restored the exact
        // compositor anchor. Observe that backgrounded public viewport and compare resume to it;
        // this still fails if lifecycle capture/restoration moves even one source or exposes a
        // non-clean frame, while avoiding a false comparison with a pre-key snapshot.
        PhysicalViewportEvidence backgroundEvidence = physicalViewportEvidence(reader);
        assertTrue("HOME background edge lost its exact clean viewport; chapter=" + chapter,
                backgroundEvidence != null
                        && backgroundEvidence.position != null
                        && backgroundEvidence.physical != null
                        && expectedPath.equals(
                                backgroundEvidence.physical.getPhysicalEpisodePath())
                        && isCurrentCommittedViewport(backgroundEvidence)
                        && backgroundEvidence.physical.getMissingPx() == 0
                        && backgroundEvidence.physical.getPlaceholderPx() == 0
                        && backgroundEvidence.physical.getVisibleLoading() == 0
                        && backgroundEvidence.physical.getVisibleErrors() == 0);
        beforeHome = backgroundEvidence.position;
        beforePhysical = backgroundEvidence.physical;
        long presentationFloor = Math.max(
                preHomePresentationFloor,
                beforePhysical.getPresentedUptimeNanos());
        SystemClock.sleep(1200L);
        ReaderV2Activity resumed = reopenExistingReaderFromPixelLauncherRecents(device);
        assertNotNull("HOME return did not resume the existing reader; chapter=" + chapter,
                resumed);
        assertTrue("HOME return replaced the reader Activity; chapter=" + chapter,
                resumed == reader);
        assertEquals("HOME return changed the exact episode; chapter=" + chapter,
                expectedPath, resumed.testCurrentNtkEpisodePath());

        ReaderV2Activity.CleanPhysicalSourceSnapshot resumedPhysical = null;
        ReaderSurfaceView.ScrollPositionSnapshot afterHome = null;
        long cleanDeadline = SystemClock.elapsedRealtime() + 15000L;
        while (SystemClock.elapsedRealtime() < cleanDeadline) {
            PhysicalViewportEvidence evidence = physicalViewportEvidence(resumed);
            ReaderV2Activity.CleanPhysicalSourceSnapshot candidate = evidence.physical;
            ReaderSurfaceView.VisibleCoverageSnapshot coverage = evidence.coverage;
            if (candidate != null
                    && expectedPath.equals(candidate.getPhysicalEpisodePath())
                    && candidate.getPresentedUptimeNanos() > presentationFloor
                    && isCurrentCommittedViewport(evidence)
                    && candidate.getPhysicalViewportPx() > 0
                    && candidate.getDrawablePx() >= candidate.getPhysicalViewportPx()
                    && candidate.getMissingPx() == 0
                    && candidate.getPlaceholderPx() == 0
                    && candidate.getVisibleLoading() == 0
                    && candidate.getVisibleErrors() == 0
                    && candidate.getVisibleCards() == 0
                    && candidate.getWidthFillFailures() == 0
                    && candidate.getLowResolutionItems() == 0
                    && !candidate.getNativeSurfaceRevealPending()
                    && coverage != null
                    && coverage.getMissingPx() == 0
                    && coverage.getPlaceholderPx() == 0
                    && coverage.getVisibleLoading() == 0
                    && coverage.getVisibleErrors() == 0
                    && !evidence.revealPending) {
                resumedPhysical = candidate;
                afterHome = evidence.position;
                break;
            }
            SystemClock.sleep(16L);
        }
        assertNotNull("HOME return never committed a fresh clean physical viewport; chapter="
                + chapter + ",pipeline=" + resumed.testRenderPipelineDiagnosticSnapshot(),
                resumedPhysical);

        assertNotNull("HOME return lost its restored position; chapter=" + chapter, afterHome);
        assertEquals("HOME return changed the visible original page; chapter=" + chapter
                        + ",beforePage=" + beforeHome.getPage()
                        + ",afterPage=" + afterHome.getPage()
                        + ",beforeOffset=" + beforeHome.getOffset()
                        + ",afterOffset=" + afterHome.getOffset()
                        + ",beforeAbsolute=" + beforeHome.getScrollOffset()
                        + ",afterAbsolute=" + afterHome.getScrollOffset()
                        + ",beforeFirstSource=" + beforePhysical.getFirstVisibleSourcePage()
                        + ",afterFirstSource=" + resumedPhysical.getFirstVisibleSourcePage(),
                beforePhysical.getFirstVisibleSourcePage(),
                resumedPhysical.getFirstVisibleSourcePage());
        assertTrue("HOME return changed the page-local restored offset; chapter=" + chapter
                        + ",beforePage=" + beforeHome.getPage()
                        + ",afterPage=" + afterHome.getPage()
                        + ",beforeOffset=" + beforeHome.getOffset()
                        + ",afterOffset=" + afterHome.getOffset()
                        + ",beforeAbsolute=" + beforeHome.getScrollOffset()
                        + ",afterAbsolute=" + afterHome.getScrollOffset(),
                Math.abs(afterHome.getOffset() - beforeHome.getOffset()) <= 4);
        ReaderSurfaceView.LifecycleViewportAnchorSnapshot resumedCommittedAnchor =
                resumed.testCurrentCommittedViewportAnchorSnapshot();
        assertNotNull("HOME return lost its committed content anchor; chapter=" + chapter,
                resumedCommittedAnchor);

        runOnMain(resumed::testResetFrameStatsSnapshot);
        int x = device.getDisplayWidth() / 2;
        // A full fling from an episode's exact final viewport legitimately exposes the structural
        // transition card. CleanPhysicalSourceSnapshot intentionally excludes that mixed frame,
        // so using it as the sole motion oracle mislabels a successful 2,000 px move as locked.
        // Exercise the required slow-scroll path instead and qualify the real committed identity
        // and page top while allowing the visible transition card itself (never missing pixels).
        int centerY = device.getDisplayHeight() / 2;
        int slowDragHalfDistance = Math.min(120, device.getDisplayHeight() / 12);
        int fromY = centerY + slowDragHalfDistance;
        int toY = centerY - slowDragHalfDistance;
        assertTrue("HOME return swipe injection failed; chapter=" + chapter,
                device.swipe(x, fromY, x, toY, 60));
        Manga exactNextEpisode = resumed.testEpisode(chapter + 1);
        String exactNextPath = exactNextEpisode == null
                ? null
                : exactNextEpisode.getNtkEpisodePath();
        ReaderSurfaceView.ScrollPositionSnapshot moved = null;
        ReaderV2Activity.CleanPhysicalSourceSnapshot movedPhysical = null;
        ReaderSurfaceView.LifecycleViewportAnchorSnapshot movedCommittedAnchor = null;
        boolean committedForwardMotion = false;
        long motionDeadline = SystemClock.elapsedRealtime() + 5000L;
        while (SystemClock.elapsedRealtime() < motionDeadline) {
            PhysicalViewportEvidence evidence = physicalViewportEvidence(resumed);
            ReaderV2Activity.CleanPhysicalSourceSnapshot candidate = evidence.physical;
            ReaderSurfaceView.LifecycleViewportAnchorSnapshot candidateCommittedAnchor =
                    evidence.committedAnchor;
            moved = evidence.position;
            boolean stayedInExpectedEpisode = candidate != null
                    && expectedPath.equals(candidate.getPhysicalEpisodePath())
                    && candidate.getPresentedUptimeNanos()
                    > resumedPhysical.getPresentedUptimeNanos()
                    && candidate.getMissingPx() == 0
                    && candidate.getPlaceholderPx() == 0
                    && candidate.getVisibleLoading() == 0
                    && candidate.getVisibleErrors() == 0
                    && isCurrentCommittedViewport(evidence)
                    && (candidate.getFirstVisibleSourcePage()
                        > resumedPhysical.getFirstVisibleSourcePage()
                        || (candidate.getFirstVisibleSourcePage()
                            == resumedPhysical.getFirstVisibleSourcePage()
                            && candidate.getFirstVisiblePageTopPx()
                            < resumedPhysical.getFirstVisiblePageTopPx() - 100f));
            boolean enteredExactNextEpisode = candidate != null
                    && exactNextEpisode != null
                    && exactNextPath != null
                    && exactNextPath.equals(candidate.getPhysicalEpisodePath())
                    && exactNextPath.equals(resumed.testCurrentNtkEpisodePath())
                    && resumed.testHasCanonicalEpisodeOrder(exactNextEpisode)
                    && candidate.getPresentedUptimeNanos()
                    > resumedPhysical.getPresentedUptimeNanos()
                    && candidate.getFirstVisibleSourcePage() >= 0
                    && candidate.getMissingPx() == 0
                    && candidate.getPlaceholderPx() == 0
                    && candidate.getVisibleLoading() == 0
                    && candidate.getVisibleErrors() == 0
                    && isCurrentCommittedViewport(evidence);
            boolean semanticForwardMotion = stayedInExpectedEpisode || enteredExactNextEpisode;
            ReaderSurfaceView.CommittedPageIdentity beforeCommittedIdentity =
                    resumedCommittedAnchor.getIdentity();
            ReaderSurfaceView.CommittedPageIdentity candidateCommittedIdentity =
                    candidateCommittedAnchor == null
                            ? null
                            : candidateCommittedAnchor.getIdentity();
            committedForwardMotion = candidateCommittedIdentity != null
                    && expectedPath.equals(
                        candidateCommittedIdentity.getNormalizedEpisodePath())
                    && expectedPath.equals(resumed.testCurrentNtkEpisodePath())
                    && evidence.coverage != null
                    && evidence.coverage.getMissingPx() == 0
                    && evidence.coverage.getPlaceholderPx() == 0
                    && evidence.coverage.getVisibleLoading() == 0
                    && evidence.coverage.getVisibleErrors() == 0
                    && !evidence.revealPending
                    && (candidateCommittedIdentity.getSourcePageIndex()
                        > beforeCommittedIdentity.getSourcePageIndex()
                        || (candidateCommittedIdentity.getSourcePageIndex()
                            == beforeCommittedIdentity.getSourcePageIndex()
                            && candidateCommittedAnchor.getPageTopInViewportPx()
                            < resumedCommittedAnchor.getPageTopInViewportPx() - 100f));
            if (semanticForwardMotion || committedForwardMotion) {
                movedPhysical = candidate;
                movedCommittedAnchor = candidateCommittedAnchor;
                break;
            }
            SystemClock.sleep(16L);
        }
        assertNotNull("HOME return lost scroll motion state; chapter=" + chapter, moved);
        // Forward-history pruning intentionally removes consumed display rows and rebases the
        // absolute document coordinate. Verify a newer clean physical source/local position,
        // which is both rebase-safe and stronger than observing Java scrollOffset alone.
        assertTrue("HOME return remained motion-locked; chapter=" + chapter
                        + ",beforeAbsolute=" + afterHome.getScrollOffset()
                        + ",afterAbsolute=" + moved.getScrollOffset()
                        + ",beforeSource=" + resumedPhysical.getFirstVisibleSourcePage()
                        + ",beforeCommitted=" + resumedCommittedAnchor
                        + ",afterCommitted=" + movedCommittedAnchor,
                movedPhysical != null || committedForwardMotion);
        SystemClock.sleep(80L);

        List<ReaderSurfaceView.FrameStatsSnapshot> segments =
                resumed.testTakeFrameStatsSnapshots();
        assertTrue("HOME return produced no frame evidence; chapter=" + chapter,
                segments != null && !segments.isEmpty());
        long samples = 0L;
        long missedIntervals = 0L;
        int droppedFrames = 0;
        int missingPx = 0;
        float worstP95 = 0f;
        for (ReaderSurfaceView.FrameStatsSnapshot segment : segments) {
            samples += segment.getSamples();
            missedIntervals += segment.getMissedIntervals();
            droppedFrames += segment.getDroppedFrames();
            missingPx = Math.max(missingPx, segment.getMaxMissingPx());
            worstP95 = Math.max(worstP95, segment.getTotalP95());
        }
        double missedPercent = missedIntervals * 100.0 / Math.max(1L, samples);
        assertTrue("HOME return lacks physical scroll samples; chapter=" + chapter
                        + ",samples=" + samples,
                samples >= 8L);
        assertEquals("HOME return dropped a frame; chapter=" + chapter,
                0, droppedFrames);
        assertEquals("HOME return exposed missing pixels; chapter=" + chapter,
                0, missingPx);
        assertTrue("HOME return p95 exceeded 16ms; chapter=" + chapter
                        + ",p95=" + worstP95,
                worstP95 < STRICT_SCROLL_P95_MS);
        assertTrue("HOME return missed intervals reached 1%; chapter=" + chapter
                        + ",missed=" + missedIntervals + ",samples=" + samples
                        + ",percent=" + missedPercent,
                missedPercent < STRICT_SCROLL_MISSED_PERCENT);
        assertEquals("HOME return swipe changed the exact episode; chapter=" + chapter,
                expectedPath, resumed.testCurrentNtkEpisodePath());
        assertTrue("HOME return corrupted canonical episode order; chapter=" + chapter,
                resumed.testHasCanonicalEpisodeOrder(expectedEpisode));
        Log.i(TAG, "tenChapterHomeRoundTrip chapter=" + chapter
                + ",path=" + expectedPath
                + ",samples=" + samples
                + ",missedPercent=" + missedPercent
                + ",p95=" + worstP95
                + ",presented=" + resumedPhysical.getPresentedUptimeNanos());
        return resumedPhysical;
    }

    private void waitForLongSessionPhaseIdle(
            ReaderV2Activity reader,
            long timeoutMs
    ) {
        long deadline = SystemClock.elapsedRealtime() + timeoutMs;
        int stableSamples = 0;
        ReaderSurfaceView.ScrollPositionSnapshot latest = null;
        while (SystemClock.elapsedRealtime() < deadline) {
            latest = reader.testCurrentScrollPositionSnapshot();
            boolean idle = latest != null && !latest.getBusy()
                    && reader.testIsNativePipelineQuiescent()
                    && !reader.testNativeSurfaceRevealPending();
            if (idle) {
                stableSamples++;
                // Kotlin can have handed the last prewarm tile to the native EGL owner already.
                // Keep the state continuously idle for roughly one second, longer than the
                // measured host upload/swap tail, before sampling PSS or phase counters.
                if (stableSamples >= 10) return;
            } else {
                stableSamples = 0;
            }
            SystemClock.sleep(100L);
        }
        throw new AssertionError("Long-session phase never became physically quiescent; page="
                + (latest == null ? -1 : latest.getPage())
                + ",offset=" + (latest == null ? -1 : latest.getScrollOffset())
                + ",busy=" + (latest != null && latest.getBusy())
                + ",path=" + reader.testCurrentNtkEpisodePath()
                + ",pipeline=" + reader.testRenderPipelineDiagnosticSnapshot());
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

    private List<ReaderSurfaceView.FrameStatsSnapshot> requireCompletedPhysicalFrameStats(
            ReaderV2Activity reader,
            int chapter
    ) {
        // Atomically take every completed segment plus the active tail. A single latest snapshot
        // discards the earlier intervals whenever a long chapter crosses the bounded stats window,
        // which can make hundreds of real physical frames disappear from the aggregate oracle.
        // Do not inject fallback swipes here: they would measure the newly entered chapter.
        SystemClock.sleep(80L);
        List<ReaderSurfaceView.FrameStatsSnapshot> frames =
                reader.testTakeFrameStatsSnapshots();
        assertTrue("Expected native frame samples for physical One Piece chapter " + chapter,
                frames != null && !frames.isEmpty());
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
        ForwardScrollSample progress = resumedReaderForwardScrollSample();
        while(progress.currentPage < minimumPage && SystemClock.elapsedRealtime() < deadline) {
            device.swipe(x, fromY, x, toY, 12);
            ForwardScrollSample next = resumedReaderForwardScrollSample();
            assertNoForwardSemanticRollback(progress, next);
            progress = next;
        }
        return new int[] {progress.currentPage, progress.pageCount};
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

    private ForwardScrollSample resumedReaderForwardScrollSample() {
        AtomicReference<ForwardScrollSample> sample = new AtomicReference<>(
                new ForwardScrollSample(-1, -1, null));
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
            for(Activity activity : ActivityLifecycleMonitorRegistry.getInstance()
                    .getActivitiesInStage(Stage.RESUMED)) {
                if(activity instanceof ReaderV2Activity) {
                    ReaderV2Activity reader = (ReaderV2Activity)activity;
                    sample.set(new ForwardScrollSample(
                            reader.testCurrentPage(),
                            reader.testPageCount(),
                            reader.testCurrentScrollPositionSnapshot()));
                    return;
                }
            }
        });
        return sample.get();
    }

    private void assertNoForwardSemanticRollback(
            ForwardScrollSample previous,
            ForwardScrollSample current
    ) {
        if(previous == null || current == null) return;
        ReaderSurfaceView.ScrollPositionSnapshot before = previous.position;
        ReaderSurfaceView.ScrollPositionSnapshot after = current.position;
        boolean readerPageRolledBack = previous.currentPage >= 0 &&
                current.currentPage < previous.currentPage;
        boolean semanticPositionRolledBack = before != null && after != null &&
                (after.getPage() < before.getPage() ||
                        (after.getPage() == before.getPage() &&
                                after.getOffset() > before.getOffset()));
        assertFalse(
                "Forward input moved the semantic viewport backward; previousPage="
                        + previous.currentPage + ",currentPage=" + current.currentPage
                        + ",before=" + before + ",after=" + after,
                readerPageRolledBack || semanticPositionRolledBack);
    }

    /**
     * The reader intentionally waits for a long input-quiet interval before merging deferred exact
     * heights. Observe past that interval instead of merely checking the coordinate immediately
     * after the final swipe. A changed absolute scroll value is valid when earlier page geometry is
     * reprojected; the visible page and its page-local offset must remain bit-for-bit stationary.
     */
    private void assertViewportRemainsStationaryThroughDeferredGeometrySettlement(
            ReaderV2Activity reader
    ) {
        ReaderSurfaceView.ScrollPositionSnapshot baseline = null;
        long idleDeadline = SystemClock.elapsedRealtime() + 2000L;
        while(SystemClock.elapsedRealtime() < idleDeadline) {
            baseline = reader.testCurrentScrollPositionSnapshot();
            if(baseline != null && !baseline.getBusy()) break;
            SystemClock.sleep(16L);
        }
        assertNotNull("Reader never exposed a semantic viewport before idle settlement", baseline);
        assertFalse("Reader was still physically moving before idle settlement", baseline.getBusy());
        ReaderSurfaceView.LifecycleViewportAnchorSnapshot committedBaseline =
                reader.testCurrentCommittedViewportAnchorSnapshot();
        assertNotNull("Reader never exposed a committed viewport before idle settlement",
                committedBaseline);

        int samples = 0;
        long settleDeadline = SystemClock.elapsedRealtime() + 7200L;
        while(SystemClock.elapsedRealtime() < settleDeadline) {
            ReaderSurfaceView.ScrollPositionSnapshot current =
                    reader.testCurrentScrollPositionSnapshot();
            assertNotNull("Reader lost its semantic viewport during idle settlement", current);
            assertFalse("Reader started moving without physical input during idle settlement",
                    current.getBusy());
            assertEquals("Deferred geometry changed the stationary visible page; baseline="
                            + baseline + ",current=" + current,
                    baseline.getPage(), current.getPage());
            assertEquals("Deferred geometry moved the stationary page-local viewport; baseline="
                            + baseline + ",current=" + current,
                    baseline.getOffset(), current.getOffset());
            ReaderSurfaceView.LifecycleViewportAnchorSnapshot committed =
                    reader.testCurrentCommittedViewportAnchorSnapshot();
            assertNotNull("Reader lost its committed viewport during idle settlement", committed);
            assertEquals("Deferred geometry changed the stationary source identity",
                    committedBaseline.getIdentity(), committed.getIdentity());
            assertEquals("Deferred geometry moved the committed source in the viewport",
                    committedBaseline.getPageTopInViewportPx(),
                    committed.getPageTopInViewportPx(),
                    0.5f);
            samples++;
            SystemClock.sleep(16L);
        }
        assertTrue("Idle geometry settlement was not observed at frame cadence; samples=" + samples,
                samples >= 300);
    }

    private void assertNoForwardCommittedViewportRollback(
            String expectedPath,
            int swipe,
            ReaderSurfaceView.LifecycleViewportAnchorSnapshot before,
            ReaderSurfaceView.LifecycleViewportAnchorSnapshot after
    ) {
        ReaderSurfaceView.CommittedPageIdentity beforeIdentity = before.getIdentity();
        ReaderSurfaceView.CommittedPageIdentity afterIdentity = after.getIdentity();
        assertEquals("Forward gesture started from an unexpected committed episode; swipe="
                        + swipe,
                expectedPath, beforeIdentity.getNormalizedEpisodePath());
        assertEquals("Forward gesture changed committed episode without reader transition; swipe="
                        + swipe,
                expectedPath, afterIdentity.getNormalizedEpisodePath());
        assertTrue("Forward gesture moved to an earlier original page; swipe=" + swipe
                        + ",before=" + before + ",after=" + after,
                afterIdentity.getSourcePageIndex() >= beforeIdentity.getSourcePageIndex());
        if (afterIdentity.getSourcePageIndex() == beforeIdentity.getSourcePageIndex()) {
            assertTrue("Forward gesture moved the same original page backward; swipe=" + swipe
                            + ",before=" + before + ",after=" + after,
                    after.getPageTopInViewportPx() <= before.getPageTopInViewportPx() + 0.5f);
        }
    }

    /**
     * UiDevice.swipe is synchronous, so the old before/after assertion could miss a compositor
     * rollback that happened between injected MOVE samples and was corrected before swipe()
     * returned. Inject on a worker while the instrumentation thread samples the exact committed
     * source identity and page top. A forward gesture may advance to a new source/episode, but a
     * source already on screen may never move downward and an episode already left may not return.
     */
    private boolean performMonitoredForwardSwipe(
            UiDevice device,
            ReaderV2Activity reader,
            int x,
            int fromY,
            int toY,
            int steps,
            String expectedStartPath,
            int swipe
    ) {
        AtomicBoolean finished = new AtomicBoolean(false);
        AtomicBoolean injected = new AtomicBoolean(false);
        AtomicReference<Throwable> injectionFailure = new AtomicReference<>();
        Thread injector = new Thread(() -> {
            try {
                injected.set(device.swipe(x, fromY, x, toY, steps));
            } catch (Throwable failure) {
                injectionFailure.set(failure);
            } finally {
                finished.set(true);
            }
        }, "reader-monitored-forward-swipe");

        ReaderSurfaceView.LifecycleViewportAnchorSnapshot previous =
                reader.testCurrentCommittedViewportAnchorSnapshot();
        assertNotNull("Monitored forward swipe has no starting committed anchor; path="
                + expectedStartPath + ",swipe=" + swipe, previous);
        String activePath = previous.getIdentity().getNormalizedEpisodePath();
        assertEquals("Monitored forward swipe started from the wrong episode; swipe=" + swipe,
                expectedStartPath, activePath);
        LinkedHashSet<String> leftPaths = new LinkedHashSet<>();
        injector.start();
        long deadline = SystemClock.elapsedRealtime() + 10000L;
        int samples = 0;
        while (SystemClock.elapsedRealtime() < deadline) {
            ReaderSurfaceView.LifecycleViewportAnchorSnapshot current =
                    reader.testCurrentCommittedViewportAnchorSnapshot();
            if (current != null) {
                String currentPath = current.getIdentity().getNormalizedEpisodePath();
                if (!currentPath.equals(activePath)) {
                    leftPaths.add(activePath);
                    assertFalse("Forward gesture returned to an episode it already left; swipe="
                                    + swipe + ",left=" + leftPaths + ",current=" + current,
                            leftPaths.contains(currentPath));
                    activePath = currentPath;
                    previous = current;
                } else {
                    ReaderSurfaceView.CommittedPageIdentity beforeIdentity = previous.getIdentity();
                    ReaderSurfaceView.CommittedPageIdentity afterIdentity = current.getIdentity();
                    // This public lifecycle snapshot deliberately combines the newest
                    // Activity-delivered stable identity with the live layout coordinate. During
                    // a busy frame, listener delivery can replace p11 with p10 while the numeric
                    // viewport remains exactly unchanged. That is metadata convergence, not
                    // visible motion. Qualify movement only while the same physical source owns
                    // both samples; this is the condition that caught the real 8px backstep.
                    if (afterIdentity.getSourcePageIndex()
                            == beforeIdentity.getSourcePageIndex()) {
                        assertTrue("Forward gesture rolled back between MOVE frames; swipe=" + swipe
                                        + ",sample=" + samples + ",before=" + previous
                                        + ",after=" + current,
                                current.getPageTopInViewportPx()
                                        <= previous.getPageTopInViewportPx() + 0.5f);
                    }
                    previous = current;
                }
            }
            samples++;
            if (finished.get()) {
                ReaderSurfaceView.ScrollPositionSnapshot position =
                        reader.testCurrentScrollPositionSnapshot();
                if (position == null || !position.getBusy()) break;
            }
            SystemClock.sleep(4L);
        }
        try {
            injector.join(1000L);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new AssertionError("Interrupted while monitoring forward swipe", interrupted);
        }
        Throwable failure = injectionFailure.get();
        if (failure != null) throw new AssertionError("Forward swipe injection crashed", failure);
        assertTrue("Monitored forward swipe did not finish; swipe=" + swipe, finished.get());
        assertTrue("Monitored forward swipe produced too few samples; swipe=" + swipe
                        + ",samples=" + samples,
                samples >= 2);
        return injected.get();
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
