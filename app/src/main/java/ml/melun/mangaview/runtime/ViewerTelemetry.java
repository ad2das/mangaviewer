package ml.melun.mangaview.runtime;

import ml.melun.mangaview.reader.NtkStrictEpisodeDiscoveryCoordinator;
import android.os.Handler;
import android.os.Debug;
import android.os.Looper;
import android.os.SystemClock;
import android.util.Log;
import android.view.View;
import android.view.accessibility.AccessibilityEvent;

import java.util.Locale;
import java.util.ArrayList;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Production viewer observability. This class never starts or delays content work; it only
 * records work that the normal viewer path has already admitted.
 */
public final class ViewerTelemetry {
    private static final String TAG = "ViewerTelemetry";
    private static final AtomicLong NEXT_GENERATION = new AtomicLong();
    private static final AtomicLong NEXT_OPERATION = new AtomicLong();
    private static final AtomicInteger NEXT_COOKIE = new AtomicInteger(1);
    private static final AtomicReference<Session> SESSION = new AtomicReference<>();
    private static final ConcurrentHashMap<Long, Operation> REQUESTS = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<Long, Operation> DECODES = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<Integer, Long> RESPONSE_COMPLETED_NANOS =
            new ConcurrentHashMap<>();
    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static final AtomicLong CURRENT_BITMAP_BYTES = new AtomicLong();
    private static final AtomicLong PEAK_BITMAP_BYTES = new AtomicLong();
    private static final long CLOSE_DRAIN_TIMEOUT_NANOS = TimeUnit.SECONDS.toNanos(2L);
    private static final long CLOSE_DRAIN_POLL_MS = 25L;
    private static final ScheduledExecutorService MEMORY_SAMPLER =
            Executors.newSingleThreadScheduledExecutor(runnable -> {
                Thread thread = new Thread(runnable, "viewer-memory-sampler");
                thread.setDaemon(true);
                thread.setPriority(Thread.MIN_PRIORITY);
                return thread;
            });

    private ViewerTelemetry() {
    }

    public static synchronized long viewerOpen(String workId, String episodeId, String mode) {
        Session previous = SESSION.getAndSet(null);
        if(previous != null) {
            // Linearize a global viewer-generation replacement with the old strict flight's short
            // publication fence. An ACK/document cookie commit can finish before this call, or be
            // rejected after retirement, but can never complete inside the new viewer generation.
            NtkStrictEpisodeDiscoveryCoordinator.retireViewerOwnership(
                    previous.episodeId,
                    previous.generation,
                    "viewer_superseded");
            closeOutstandingOperationsAsCancelled(REQUESTS, "superseded");
            closeOutstandingOperationsAsCancelled(DECODES, "superseded");
            closeSession(previous, "superseded", false);
        }
        closeOutstandingOperations(REQUESTS);
        closeOutstandingOperations(DECODES);
        RESPONSE_COMPLETED_NANOS.clear();
        CURRENT_BITMAP_BYTES.set(0L);
        PEAK_BITMAP_BYTES.set(0L);
        long generation = NEXT_GENERATION.incrementAndGet();
        int cookie = nextCookie();
        int scrollCookie = nextCookie();
        int allImagesReadyCookie = nextCookie();
        Session session = new Session(
                generation,
                safe(workId, "unknown"),
                safe(episodeId, "unknown"),
                safe(mode, "unknown"),
                SystemClock.elapsedRealtimeNanos(),
                cookie,
                scrollCookie,
                allImagesReadyCookie);
        SESSION.set(session);
        startMemorySampling(session);
        PerfTrace.beginAsync("ViewerOpen", cookie);
        PerfTrace.beginAsync("ViewerAllImagesReady", allImagesReadyCookie);
        PerfTrace.counter("ViewerActiveRequests", 0L);
        PerfTrace.counter("ViewerActiveDecodes", 0L);
        PerfTrace.counter("ViewerBitmapBytes", 0L);
        PerfTrace.counter("ViewerPeakBitmapBytes", 0L);
        PerformanceMonitor.viewerStarted(session.workId, session.episodeId, session.mode);
        event("viewer_open", session, "phase=click");
        return generation;
    }

    public static void viewerClosed(String reason) {
        Session session = SESSION.get();
        if(session != null)
            viewerClosedAfterDrain(reason, session.generation);
    }

    /**
     * Keeps the generation alive while cancelled Calls/decodes report their terminal events.
     * Viewer close is emitted only after a zero-operation boundary (or after outstanding work is
     * explicitly converted to cancellation at the bounded teardown timeout).
     */
    public static void viewerClosedAfterDrain(String reason, long generation) {
        Session session = SESSION.get();
        if(session == null || session.generation != generation ||
                !session.closeRequested.compareAndSet(false, true))
            return;
        attemptCloseAfterDrain(
                session,
                safe(reason, "closed"),
                SystemClock.elapsedRealtimeNanos() + CLOSE_DRAIN_TIMEOUT_NANOS);
    }

    public static long pageListRequestStarted() {
        return operationStarted("PageListRequest", -1, "manifest", 0);
    }

    public static void pageListRequestFinished(long operationId, String outcome) {
        operationFinished(operationId, REQUESTS, safe(outcome, "success"), 0L);
    }

    public static long imageRequestStarted(String sourceKeyHash, int pageIndex, int priority) {
        return imageRequestStarted(sourceKeyHash, "unknown", pageIndex, priority);
    }

    public static long imageRequestStarted(
            String sourceKeyHash, String urlHost, int pageIndex, int priority) {
        return operationStarted(
                "ImageRequest", pageIndex, safe(sourceKeyHash, "unknown"),
                safe(urlHost, "unknown"), priority);
    }

    /**
     * Records a request only after a speculative numeric candidate has returned real image
     * headers, while retaining the physical request's original start timestamp. This keeps
     * bounded post-click 404 discovery outside the canonical image failure counters without
     * making the successful image transfer look artificially shorter in traces.
     */
    public static long imageRequestStartedAt(
            String sourceKeyHash, String urlHost, int pageIndex, int priority,
            long startedAtNanos) {
        return operationStarted(
                "ImageRequest", pageIndex, safe(sourceKeyHash, "unknown"),
                safe(urlHost, "unknown"), priority, startedAtNanos);
    }

    public static void imageRequestFinished(long operationId, long responseBytes) {
        Operation operation = operationFinished(
                operationId, REQUESTS, "success", Math.max(0L, responseBytes));
        if(operation != null && operation.pageIndex >= 0)
            RESPONSE_COMPLETED_NANOS.put(operation.pageIndex, SystemClock.elapsedRealtimeNanos());
    }

    public static void imageRequestCancelled(long operationId, String reason) {
        operationFinished(operationId, REQUESTS, "cancelled:" + safe(reason, "unknown"), 0L);
    }

    public static void imageRequestFailed(long operationId, String reason) {
        operationFinished(operationId, REQUESTS, "failed:" + safe(reason, "unknown"), 0L);
    }

    public static long imageDecodeStarted(String sourceKeyHash, int pageIndex) {
        long id = NEXT_OPERATION.incrementAndGet();
        int cookie = nextCookie();
        Operation operation = new Operation(
                id, cookie, "ImageDecode", pageIndex, safe(sourceKeyHash, "unknown"),
                "none", 0,
                SystemClock.elapsedRealtimeNanos());
        DECODES.put(id, operation);
        PerfTrace.beginAsync("ImageDecode", cookie);
        PerfTrace.counter("ViewerActiveDecodes", DECODES.size());
        // Perfetto owns per-image timing. Serializing two JSON/logcat events for every page made
        // 100+ parallel cold decoders contend on logd exactly on the all-images critical path.
        // Keep human-readable detail for the first real page; failures are always logged below.
        if(pageIndex == 0)
            event("decode", SESSION.get(), "phase=start," + operation.metadata());
        return id;
    }

    public static void imageDecodeFinished(long operationId, long decodedBytes, String outcome) {
        String safeOutcome = safe(outcome, "success");
        Operation pending = DECODES.get(operationId);
        boolean emitDetailedEvent = pending == null
                || pending.pageIndex == 0
                || safeOutcome.startsWith("failed")
                || safeOutcome.startsWith("cancelled");
        Operation operation = operationFinished(
                operationId, DECODES, safeOutcome, Math.max(0L, decodedBytes), emitDetailedEvent);
        if(operation != null && decodedBytes > 0L) {
            updateMax(PEAK_BITMAP_BYTES, decodedBytes);
            PerfTrace.counter("ViewerPeakBitmapBytes", PEAK_BITMAP_BYTES.get());
        }
    }

    public static void setBitmapBytes(long bytes) {
        long safeBytes = Math.max(0L, bytes);
        CURRENT_BITMAP_BYTES.set(safeBytes);
        updateMax(PEAK_BITMAP_BYTES, safeBytes);
        PerfTrace.counter("ViewerBitmapBytes", safeBytes);
        PerfTrace.counter("ViewerPeakBitmapBytes", PEAK_BITMAP_BYTES.get());
    }

    public static void imageMetadata(
            String sourceKeyHash,
            String urlHost,
            int pageIndex,
            int width,
            int height,
            String format,
            long encodedBytes) {
        if(pageIndex < 0 || width <= 0 || height <= 0)
            return;
        Session session = SESSION.get();
        if(session != null) {
            session.imageMetadataCount.incrementAndGet();
            session.imageEncodedBytes.addAndGet(Math.max(0L, encodedBytes));
            session.imageWidthSum.addAndGet(width);
            session.imageHeightSum.addAndGet(height);
            updateMax(session.imageMaxWidth, width);
            updateMax(session.imageMaxHeight, height);
            session.imageFormats.put(clean(format), Boolean.TRUE);
            session.imageHosts.put(clean(urlHost), Boolean.TRUE);
        }
        // Exact aggregate counters retain every page. Keep one human-readable representative;
        // hundreds of JSON + Perfetto instant events otherwise contend with the cold body/decode
        // wave. Invalid metadata is rejected above and all request failures remain unsampled.
        if(pageIndex != 0)
            return;
        event("image_metadata", SESSION.get(),
                "sourceKeyHash=" + clean(sourceKeyHash)
                        + ",pageIndex=" + pageIndex
                        + ",width=" + width
                        + ",height=" + height
                        + ",format=" + clean(format)
                        + ",encodedBytes=" + Math.max(0L, encodedBytes)
                        + ",urlHost=" + clean(urlHost));
    }

    public static void imageMetadata(
            int pageIndex,
            int width,
            int height,
            String format,
            long encodedBytes,
            String urlHost) {
        imageMetadata("unknown", urlHost, pageIndex, width, height, format, encodedBytes);
    }

    public static void networkObservation(
            int pageIndex,
            String protocol,
            String connectionId,
            boolean connectionReused,
            String clientInstanceId) {
        Session session = SESSION.get();
        if(session != null) {
            session.networkObservationCount.incrementAndGet();
            if(connectionReused)
                session.networkReusedCount.incrementAndGet();
            session.networkProtocols.put(clean(protocol), Boolean.TRUE);
        }
        if(pageIndex != 0)
            return;
        event("network_observation", SESSION.get(),
                "pageIndex=" + pageIndex
                        + ",protocol=" + clean(protocol)
                        + ",connectionId=" + clean(connectionId)
                        + ",connectionReused=" + connectionReused
                        + ",clientInstanceId=" + clean(clientInstanceId));
    }

    /**
     * Records an identity-valid actual image draw after HWUI's frame-commit callback.
     *
     * <p>A frame commit proves that the validated draw was accepted by HWUI. It is deliberately
     * not described as a display-present/compositor-latch fence.</p>
     */
    public static void actualImageDrawCommitted(
            View renderView,
            long authority,
            int firstVisiblePage,
            int lastVisiblePage,
            long committedAtNanos,
            boolean viewportOriginalComplete,
            long firstVisibleGapPx,
            float velocityPxPerSecond) {
        recordQualifiedActualFrame(
                renderView,
                authority,
                firstVisiblePage,
                lastVisiblePage,
                committedAtNanos,
                viewportOriginalComplete,
                firstVisibleGapPx,
                velocityPxPerSecond,
                "actual_image_draw_commit",
                "hwui_frame_commit",
                "openToCommittedDrawMs",
                "responseToCommittedDrawMs");
    }

    /**
     * Publishes the immutable source identity of a page in a continuously appended episode.
     *
     * <p>The telemetry session remains owned by the original user click. Reopening it at a
     * continuous-reading boundary would turn scrolling into a fake viewer-open event and cancel
     * unrelated request/decode observations. The caller must validate this episode and source
     * range against its immutable page manifest before calling.</p>
     */
    public static void actualImageDrawCommittedForEpisode(
            View renderView,
            long authority,
            String physicalEpisodeId,
            int firstVisibleSourcePage,
            int lastVisibleSourcePage,
            long committedAtNanos,
            boolean viewportOriginalComplete,
            long firstVisibleGapPx,
            float velocityPxPerSecond) {
        recordQualifiedActualFrame(
                renderView,
                authority,
                firstVisibleSourcePage,
                lastVisibleSourcePage,
                committedAtNanos,
                viewportOriginalComplete,
                firstVisibleGapPx,
                velocityPxPerSecond,
                "actual_image_draw_commit",
                "hwui_frame_commit",
                "openToCommittedDrawMs",
                "responseToCommittedDrawMs",
                physicalEpisodeId);
    }

    /** Called only when the native SurfaceControl path has a real compositor-latch proof. */
    public static void actualFramePresented(
            View renderView,
            long authority,
            int firstVisiblePage,
            int lastVisiblePage,
            long presentedAtNanos,
            boolean viewportOriginalComplete,
            long firstVisibleGapPx,
            float velocityPxPerSecond) {
        recordQualifiedActualFrame(
                renderView,
                authority,
                firstVisiblePage,
                lastVisiblePage,
                presentedAtNanos,
                viewportOriginalComplete,
                firstVisibleGapPx,
                velocityPxPerSecond,
                "present",
                "surfacecontrol_compositor_latch",
                "openToPresentMs",
                "responseToPresentMs");
    }

    /**
     * Separates physical gestures without fabricating an idle image submission between them.
     * A screenshot, pause, or stationary finger-up interval is not active-scroll frame latency;
     * once this gesture has produced two moving commits, every later active interval is retained.
     */
    public static void physicalScrollGestureStarted() {
        Session session = SESSION.get();
        if(session != null)
            session.startPhysicalScrollGesture();
    }

    /** Ends cadence accounting when rotation/background tears down the interactive Surface. */
    public static void physicalScrollMotionEnded() {
        Session session = SESSION.get();
        if(session != null)
            session.endPhysicalScrollMotion();
    }

    private static void recordQualifiedActualFrame(
            View renderView,
            long authority,
            int firstVisiblePage,
            int lastVisiblePage,
            long evidenceAtNanos,
            boolean viewportOriginalComplete,
            long firstVisibleGapPx,
            float velocityPxPerSecond,
            String eventName,
            String evidenceKind,
            String openDurationField,
            String responseDurationField) {
        recordQualifiedActualFrame(
                renderView,
                authority,
                firstVisiblePage,
                lastVisiblePage,
                evidenceAtNanos,
                viewportOriginalComplete,
                firstVisibleGapPx,
                velocityPxPerSecond,
                eventName,
                evidenceKind,
                openDurationField,
                responseDurationField,
                null);
    }

    private static void recordQualifiedActualFrame(
            View renderView,
            long authority,
            int firstVisiblePage,
            int lastVisiblePage,
            long evidenceAtNanos,
            boolean viewportOriginalComplete,
            long firstVisibleGapPx,
            float velocityPxPerSecond,
            String eventName,
            String evidenceKind,
            String openDurationField,
            String responseDurationField,
            String physicalEpisodeId) {
        Session session = SESSION.get();
        if(session == null || !viewportOriginalComplete || firstVisibleGapPx >= 0L ||
                firstVisiblePage < 0 || lastVisiblePage < firstVisiblePage)
            return;

        float refreshRate = renderView != null && renderView.getDisplay() != null
                ? renderView.getDisplay().getRefreshRate()
                : 60.0f;
        session.recordQualifiedActualFrame(
                evidenceAtNanos > 0L ? evidenceAtNanos : SystemClock.elapsedRealtimeNanos(),
                velocityPxPerSecond,
                refreshRate);

        if(session.firstActualFrame.compareAndSet(false, true)) {
            session.firstActualFrameAtNanos = evidenceAtNanos > 0L
                    ? evidenceAtNanos
                    : SystemClock.elapsedRealtimeNanos();
            PerfTrace.endAsync("ViewerOpen", session.openCookie);
            if(session.scrollTraceOpen.compareAndSet(false, true))
                PerfTrace.beginAsync("ViewerScrollSession", session.scrollCookie);
            Long responseNanos = RESPONSE_COMPLETED_NANOS.get(firstVisiblePage);
            long responseToEvidenceMs = responseNanos == null
                    ? -1L
                    : Math.max(0L, (session.firstActualFrameAtNanos - responseNanos) / 1_000_000L);
            long openToEvidenceMs = Math.max(
                    0L, (session.firstActualFrameAtNanos - session.openedAtNanos) / 1_000_000L);
            event(eventName, session,
                    "actual=true,authority=" + authority
                            + ",pageIndex=" + firstVisiblePage
                            + ",evidenceKind=" + evidenceKind
                            + ',' + openDurationField + '=' + openToEvidenceMs
                            + ',' + responseDurationField + '=' + responseToEvidenceMs);
            PerformanceMonitor.phase("scroll_ready");
        }
        // Lifecycle transitions deliberately clear semantics. Every later identity-valid commit
        // republishes it without changing the one-shot first-presentation timing event.
        // A continuously appended episode can end in a valid full-width separator only a few
        // pixels tall. Its previous page therefore remains the first visible item at max scroll.
        // Publish the furthest physically visible canonical source for that episode while keeping
        // the full first/last range below for frame-state accounting.
        int actualStatePage = physicalEpisodeId == null || physicalEpisodeId.trim().isEmpty()
                ? firstVisiblePage
                : lastVisiblePage;
        long actualStateAtNanos = session.firstActualFrameAtNanos > 0L
                ? session.firstActualFrameAtNanos
                : evidenceAtNanos > 0L
                    ? evidenceAtNanos
                    : SystemClock.elapsedRealtimeNanos();
        session.latestActualAtNanos = actualStateAtNanos;
        publishActualState(renderView, session, physicalEpisodeId, actualStatePage);

        String direction = velocityPxPerSecond > 25f
                ? "forward"
                : velocityPxPerSecond < -25f ? "reverse" : "idle";
        PerformanceMonitor.frameState(
                firstVisiblePage,
                direction,
                velocityPxPerSecond,
                firstVisiblePage,
                lastVisiblePage,
                REQUESTS.size(),
                DECODES.size(),
                CURRENT_BITMAP_BYTES.get());
    }

    public static void coldState(
            int memoryCacheEntries,
            long diskCacheFiles,
            long diskCacheBytes,
            int contentCacheEntries,
            int activeRequests,
            int activeDecodes,
            String clientState) {
        Session session = SESSION.get();
        event("cold_state", session,
                "pid=" + android.os.Process.myPid()
                        + ",memoryCacheEntries=" + Math.max(0, memoryCacheEntries)
                        + ",diskCacheFiles=" + Math.max(0L, diskCacheFiles)
                        + ",diskCacheBytes=" + Math.max(0L, diskCacheBytes)
                        + ",contentCacheEntries=" + Math.max(0, contentCacheEntries)
                        + ",activeRequests=" + Math.max(0, activeRequests)
                        + ",activeDecodes=" + Math.max(0, activeDecodes)
                        + ",client=" + safe(clientState, "unknown"));
    }

    public static void viewerEdge(View hostView, boolean atTop, boolean atBottom) {
        if(hostView == null)
            return;
        final String edge = atBottom
                ? "viewer-edge:bottom"
                : atTop ? "viewer-edge:top" : "viewer-edge:middle";
        Session session = SESSION.get();
        String actual = session == null ? null : session.latestActualDescription;
        String allReady = session != null && session.allImagesReady.get()
                ? ";allReady=" + session.allImagesReadyPageCount
                    + ";allReadyAtNanos=" + session.allImagesReadyAtNanos
                : "";
        // Keep the exact committed-image identity and the physical edge on the same stable root
        // accessibility node. SurfaceView child events may be coalesced by Android, while this
        // root node remains queryable for the entire committed frame. This is observability only:
        // it does not start, delay, or alter image work.
        final String value = actual == null || actual.length() == 0
                ? edge
                : actual + ";edge=" + edge.substring("viewer-edge:".length()) + allReady;
        Runnable publish = () -> {
            CharSequence previous = hostView.getContentDescription();
            if(previous != null && value.contentEquals(previous))
                return;
            hostView.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_YES);
            hostView.setContentDescription(value);
            // A SurfaceView child mutation can be coalesced until a later unrelated UI update.
            // Publish this stable root's changed state immediately so external observation time
            // remains the physical committed-frame time, not accessibility polling latency.
            hostView.sendAccessibilityEvent(AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED);
        };
        if(Looper.myLooper() == Looper.getMainLooper())
            publish.run();
        else
            MAIN.post(publish);
    }

    /**
     * Records the first instant at which every canonical page has a full-quality decoded tile
     * page retained either by the Surface or its frame-paced install queue. This is observability
     * only: callers do not wait for this signal before accepting input or starting a scroll.
     */
    public static void allImagesRenderReady(View hostView, int pageCount) {
        if(hostView == null || pageCount <= 0)
            return;
        Session session = SESSION.get();
        if(session == null)
            return;
        long completedAtNanos = SystemClock.elapsedRealtimeNanos();
        synchronized(session) {
            if(session.allImagesReady.get())
                return;
            session.allImagesReadyAtNanos = completedAtNanos;
            session.allImagesReadyPageCount = pageCount;
            session.allImagesReady.set(true);
        }
        PerfTrace.endAsync("ViewerAllImagesReady", session.allImagesReadyCookie);
        long openToReadyMs = Math.max(
                0L, (completedAtNanos - session.openedAtNanos) / 1_000_000L);
        event("all_images_render_ready", session,
                "pageCount=" + pageCount + ",openToAllImagesReadyMs=" + openToReadyMs);
        publishImagePipelineSummary(session);
        event("network_pipeline_summary", session,
                "observationCount=" + session.networkObservationCount.get()
                        + ",connectionReusedCount=" + session.networkReusedCount.get()
                        + ",protocols=" + joinedKeys(session.networkProtocols));

        String actual = session.latestActualDescription;
        final String description = actual == null || actual.length() == 0
                ? "viewer-all-ready:" + clean(session.episodeId) + ':' + pageCount + ':'
                    + session.generation + ";allReadyAtNanos=" + completedAtNanos
                : actual + ";edge=middle;allReady=" + pageCount
                    + ";allReadyAtNanos=" + completedAtNanos;
        Runnable publish = () -> {
            hostView.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_YES);
            hostView.setContentDescription(description);
            hostView.sendAccessibilityEvent(AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED);
        };
        if(Looper.myLooper() == Looper.getMainLooper())
            publish.run();
        else
            MAIN.post(publish);
    }

    public static void frameSummary(
            long totalFrames, long jankyFrames, long worstFrameMs, String reason) {
        frameSummary(totalFrames, jankyFrames, worstFrameMs, 0L, 0L, reason);
    }

    public static void frameSummary(
            long totalFrames,
            long jankyFrames,
            long worstFrameMs,
            long totalFrameDurationUiNanos,
            long maxConsecutiveJankyFrames,
            String reason) {
        double jankPercent = totalFrames <= 0L
                ? 0.0d
                : (jankyFrames * 100.0d) / totalFrames;
        double averageFrameMs = totalFrames <= 0L
                ? 0.0d
                : (totalFrameDurationUiNanos / 1_000_000.0d) / totalFrames;
        double uiWorkEquivalentFps = averageFrameMs <= 0.0d
                ? 0.0d
                : 1000.0d / averageFrameMs;
        event("frame_summary", SESSION.get(),
                "reason=" + clean(reason)
                        + ",totalFrames=" + Math.max(0L, totalFrames)
                        + ",jankyFrames=" + Math.max(0L, jankyFrames)
                        + ",jankPercent=" + String.format(Locale.US, "%.4f", jankPercent)
                        + ",worstFrameMs=" + Math.max(0L, worstFrameMs)
                        + ",averageFrameMs=" + String.format(Locale.US, "%.4f", averageFrameMs)
                        + ",uiWorkEquivalentFps="
                        + String.format(Locale.US, "%.4f", uiWorkEquivalentFps)
                        + ",maxConsecutiveJankyFrames="
                        + Math.max(0L, maxConsecutiveJankyFrames));
    }

    public static void coverageSummary(
            long viewportDefectFrames,
            long runwayDefectFrames,
            long preSubmitViewportGaps,
            long identityInvalidFrames) {
        coverageSummary(
                viewportDefectFrames,
                runwayDefectFrames,
                preSubmitViewportGaps,
                identityInvalidFrames,
                0L);
    }

    public static void coverageSummary(
            long viewportDefectFrames,
            long runwayDefectFrames,
            long preSubmitViewportGaps,
            long identityInvalidFrames,
            long initialBlankFrames) {
        event("coverage_summary", SESSION.get(),
                "viewportDefectFrames=" + Math.max(0L, viewportDefectFrames)
                        + ",runwayDefectFrames=" + Math.max(0L, runwayDefectFrames)
                        + ",preSubmitViewportGaps=" + Math.max(0L, preSubmitViewportGaps)
                        + ",identityInvalidFrames=" + Math.max(0L, identityInvalidFrames)
                        + ",initialBlankFrames=" + Math.max(0L, initialBlankFrames));
    }

    public static void manifestSummary(int authoritativePageCount, String manifestDigest) {
        event("manifest_summary", SESSION.get(),
                "authoritativePageCount=" + Math.max(0, authoritativePageCount)
                        + ",manifestDigest=" + clean(manifestDigest));
    }

    public static void traversalSummary(
            String episodePath,
            String manifestDigest,
            int authoritativePageCount,
            int observedSourceCount,
            String committedSourceIndexes,
            String missingSourceIndexes,
            long structureEpoch,
            long validCommittedFrames,
            long invalidCommittedFrames,
            long initialBlankFrames) {
        event("traversal_summary", SESSION.get(),
                "episodePath=" + clean(episodePath)
                        + ",manifestDigest=" + clean(manifestDigest)
                        + ",authoritativePageCount=" + Math.max(0, authoritativePageCount)
                        + ",observedSourceCount=" + Math.max(0, observedSourceCount)
                        + ",committedSourceIndexes=" + clean(committedSourceIndexes)
                        + ",missingSourceIndexes=" + clean(missingSourceIndexes)
                        + ",structureEpoch=" + Math.max(0L, structureEpoch)
                        + ",validCommittedFrames=" + Math.max(0L, validCommittedFrames)
                        + ",invalidCommittedFrames=" + Math.max(0L, invalidCommittedFrames)
                        + ",initialBlankFrames=" + Math.max(0L, initialBlankFrames));
    }

    public static int activeRequestCount() {
        return REQUESTS.size();
    }

    public static int activeDecodeCount() {
        return DECODES.size();
    }

    public static boolean hasActiveSession() {
        return SESSION.get() != null;
    }

    public static boolean isActiveEpisode(String episodeId) {
        Session session = SESSION.get();
        return session != null && episodeId != null && episodeId.equals(session.episodeId);
    }

    public static void terminalImagePipelineSummary(String episodeId) {
        Session session = SESSION.get();
        if(session == null || episodeId == null || !episodeId.equals(session.episodeId))
            return;
        publishImagePipelineSummary(session);
    }

    public static long activeGeneration() {
        Session session = SESSION.get();
        return session == null ? 0L : session.generation;
    }

    private static long operationStarted(
            String traceName, int pageIndex, String sourceKeyHash, int priority) {
        return operationStarted(traceName, pageIndex, sourceKeyHash, "none", priority);
    }

    private static long operationStarted(
            String traceName, int pageIndex, String sourceKeyHash, String urlHost, int priority) {
        return operationStarted(
                traceName, pageIndex, sourceKeyHash, urlHost, priority,
                SystemClock.elapsedRealtimeNanos());
    }

    private static long operationStarted(
            String traceName, int pageIndex, String sourceKeyHash, String urlHost, int priority,
            long startedAtNanos) {
        long id = NEXT_OPERATION.incrementAndGet();
        int cookie = nextCookie();
        long nowNanos = SystemClock.elapsedRealtimeNanos();
        long boundedStartedAtNanos = startedAtNanos > 0L && startedAtNanos <= nowNanos
                ? startedAtNanos : nowNanos;
        Operation operation = new Operation(
                id, cookie, traceName, pageIndex, sourceKeyHash, urlHost, priority,
                boundedStartedAtNanos);
        REQUESTS.put(id, operation);
        Session session = SESSION.get();
        if("ImageRequest".equals(traceName) && session != null)
            session.imageRequestStarted.incrementAndGet();
        PerfTrace.beginAsync(traceName, cookie);
        PerfTrace.counter("ViewerActiveRequests", REQUESTS.size());
        if(traceName.equals("PageListRequest") || pageIndex == 0)
            event(traceName.equals("PageListRequest") ? "page_list_request" : "image_request",
                    session, "phase=start," + operation.metadata());
        return id;
    }

    private static Operation operationFinished(
            long operationId,
            ConcurrentHashMap<Long, Operation> operations,
            String outcome,
            long bytes) {
        return operationFinished(operationId, operations, outcome, bytes, true);
    }

    private static Operation operationFinished(
            long operationId,
            ConcurrentHashMap<Long, Operation> operations,
            String outcome,
            long bytes,
            boolean emitDetailedEvent) {
        Operation operation = operations.remove(operationId);
        if(operation == null)
            return null;
        PerfTrace.endAsync(operation.traceName, operation.cookie);
        if(operations == REQUESTS)
            PerfTrace.counter("ViewerActiveRequests", REQUESTS.size());
        else
            PerfTrace.counter("ViewerActiveDecodes", DECODES.size());
        long elapsedMs = Math.max(
                0L, (SystemClock.elapsedRealtimeNanos() - operation.startedAtNanos) / 1_000_000L);
        String phase = outcome.startsWith("cancelled")
                ? "cancel"
                : outcome.startsWith("failed") ? "fail" : "end";
        Session session = SESSION.get();
        if("ImageRequest".equals(operation.traceName) && session != null) {
            if(phase.equals("cancel"))
                session.imageRequestCancelled.incrementAndGet();
            else if(phase.equals("fail"))
                session.imageRequestFailed.incrementAndGet();
            else {
                session.imageRequestSucceeded.incrementAndGet();
                session.imageResponseBytes.addAndGet(Math.max(0L, bytes));
            }
        }
        boolean sampledImageSuccess = "ImageRequest".equals(operation.traceName)
                && phase.equals("end")
                && operation.pageIndex != 0;
        if(emitDetailedEvent && !sampledImageSuccess) {
            event(operation.traceName.equals("ImageDecode") ? "decode" :
                            operation.traceName.equals("PageListRequest")
                                    ? "page_list_request" : "image_request",
                    SESSION.get(),
                    "phase=" + phase + ',' + operation.metadata()
                            + ",outcome=" + clean(outcome)
                            + ",bytes=" + bytes
                            + ",elapsedMs=" + elapsedMs);
        }
        return operation;
    }

    private static void closeSession(Session session, String reason, boolean drainTimedOut) {
        if(!session.firstActualFrame.get())
            PerfTrace.endAsync("ViewerOpen", session.openCookie);
        if(!session.allImagesReady.get())
            PerfTrace.endAsync("ViewerAllImagesReady", session.allImagesReadyCookie);
        if(session.scrollTraceOpen.compareAndSet(true, false))
            PerfTrace.endAsync("ViewerScrollSession", session.scrollCookie);
        session.publishNativeFrameTraceCounters();
        event("native_frame_summary", session, session.nativeFrameSummary());
        stopMemorySampling(session);
        // A terminal source page cannot reach allImagesRenderReady(), but its completed sibling
        // bodies and failed/cancelled attempts are still essential evidence. Publish the same
        // aggregate on close so a partial 145/146 transfer is reported honestly instead of
        // falling back to the single detailed page-0 event.
        publishImagePipelineSummary(session);
        int activeRequests = REQUESTS.size();
        int activeDecodes = DECODES.size();
        event("viewer_closed", session,
                "reason=" + clean(reason)
                        + ",activeRequests=" + activeRequests
                        + ",activeDecodes=" + activeDecodes
                        + ",drainTimedOut=" + drainTimedOut);
        closeOutstandingOperations(REQUESTS);
        closeOutstandingOperations(DECODES);
        PerfTrace.counter("ViewerActiveRequests", 0L);
        PerfTrace.counter("ViewerActiveDecodes", 0L);
    }

    private static void publishImagePipelineSummary(Session session) {
        if(session == null || !session.imagePipelineSummaryPublished.compareAndSet(false, true))
            return;
        event("image_pipeline_summary", session,
                "requestStarted=" + session.imageRequestStarted.get()
                        + ",requestSucceeded=" + session.imageRequestSucceeded.get()
                        + ",requestCancelled=" + session.imageRequestCancelled.get()
                        + ",requestFailed=" + session.imageRequestFailed.get()
                        + ",responseBytes=" + session.imageResponseBytes.get()
                        + ",metadataCount=" + session.imageMetadataCount.get()
                        + ",encodedBytes=" + session.imageEncodedBytes.get()
                        + ",averageWidth=" + average(session.imageWidthSum, session.imageMetadataCount)
                        + ",averageHeight=" + average(session.imageHeightSum, session.imageMetadataCount)
                        + ",maxWidth=" + session.imageMaxWidth.get()
                        + ",maxHeight=" + session.imageMaxHeight.get()
                        + ",formats=" + joinedKeys(session.imageFormats)
                        + ",hosts=" + joinedKeys(session.imageHosts));
    }

    private static void attemptCloseAfterDrain(
            Session session, String reason, long deadlineNanos) {
        if(SESSION.get() != session)
            return;
        if(REQUESTS.isEmpty() && DECODES.isEmpty()) {
            finalizeCloseOnMain(session, reason, false);
            return;
        }
        if(SystemClock.elapsedRealtimeNanos() >= deadlineNanos) {
            // Do not manufacture a drained boundary by deleting telemetry operations. A timeout
            // is explicit failure evidence and preserves the real non-zero counts in viewer_closed.
            finalizeCloseOnMain(session, reason, true);
            return;
        }
        MEMORY_SAMPLER.schedule(
                () -> attemptCloseAfterDrain(session, reason, deadlineNanos),
                CLOSE_DRAIN_POLL_MS,
                TimeUnit.MILLISECONDS);
    }

    private static void finalizeCloseOnMain(
            Session session, String reason, boolean drainTimedOut) {
        if(!session.closeFinalizing.compareAndSet(false, true))
            return;
        Runnable finish = () -> {
            if(SESSION.get() != session) {
                session.closeFinalizing.set(false);
                return;
            }
            // viewerStopped emits its final frame summary synchronously on main while the
            // telemetry generation is still active. Clearing SESSION first would tag it gen=0.
            PerformanceMonitor.viewerStopped(reason);
            if(SESSION.compareAndSet(session, null)) {
                closeSession(session, reason, drainTimedOut);
                RESPONSE_COMPLETED_NANOS.clear();
            } else {
                session.closeFinalizing.set(false);
            }
        };
        if(Looper.myLooper() == Looper.getMainLooper())
            finish.run();
        else
            MAIN.post(finish);
    }

    private static void closeOutstandingOperationsAsCancelled(
            ConcurrentHashMap<Long, Operation> operations, String reason) {
        for(Long operationId : new ArrayList<>(operations.keySet()))
            operationFinished(operationId, operations, "cancelled:" + reason, 0L);
    }

    private static void closeOutstandingOperations(
            ConcurrentHashMap<Long, Operation> operations) {
        for(Operation operation : operations.values())
            PerfTrace.endAsync(operation.traceName, operation.cookie);
        operations.clear();
    }

    private static void startMemorySampling(Session session) {
        session.memorySampler = MEMORY_SAMPLER.scheduleAtFixedRate(
                () -> sampleMemory(session), 0L, 1L, TimeUnit.SECONDS);
    }

    private static void stopMemorySampling(Session session) {
        ScheduledFuture<?> sampler = session.memorySampler;
        if(sampler != null)
            sampler.cancel(false);
        long exitPssKb = safePssKb();
        session.entryPssKb.compareAndSet(0L, exitPssKb);
        updateMax(session.maxPssKb, exitPssKb);
        long currentGcCount = safeGcCount();
        session.entryGcCount.compareAndSet(-1L, currentGcCount);
        long gcBaseline = session.entryGcCount.get();
        long gcCount = Math.max(0L, currentGcCount - Math.max(0L, gcBaseline));
        event("memory_summary", session,
                "entryPssKb=" + session.entryPssKb.get()
                        + ",exitPssKb=" + exitPssKb
                        + ",maxPssKb=" + session.maxPssKb.get()
                        + ",bitmapBytes=" + CURRENT_BITMAP_BYTES.get()
                        + ",maxBitmapBytes=" + PEAK_BITMAP_BYTES.get()
                        + ",gcCount=" + gcCount);
    }

    private static void sampleMemory(Session session) {
        if(SESSION.get() != session)
            return;
        long pssKb = safePssKb();
        session.entryPssKb.compareAndSet(0L, pssKb);
        session.entryGcCount.compareAndSet(-1L, safeGcCount());
        updateMax(session.maxPssKb, pssKb);
    }

    private static long safePssKb() {
        try {
            return Math.max(0L, Debug.getPss());
        } catch(Throwable ignored) {
            return 0L;
        }
    }

    private static long safeGcCount() {
        try {
            String value = Debug.getRuntimeStat("art.gc.gc-count");
            return value == null ? 0L : Long.parseLong(value);
        } catch(Throwable ignored) {
            return 0L;
        }
    }

    private static void updateMax(AtomicLong target, long value) {
        long previous;
        do {
            previous = target.get();
            if(value <= previous)
                return;
        } while(!target.compareAndSet(previous, value));
    }

    private static long average(AtomicLong sum, AtomicLong count) {
        long divisor = count.get();
        return divisor <= 0L ? 0L : sum.get() / divisor;
    }

    private static String joinedKeys(ConcurrentHashMap<String, Boolean> values) {
        if(values.isEmpty())
            return "none";
        return clean(String.join(";", values.keySet()));
    }

    private static void publishActualState(View view, Session session, int pageIndex) {
        publishActualState(view, session, null, pageIndex);
    }

    private static void publishActualState(
            View view,
            Session session,
            String physicalEpisodeId,
            int pageIndex) {
        if(view == null)
            return;
        String episodeId = physicalEpisodeId == null || physicalEpisodeId.trim().isEmpty()
                ? session.episodeId
                : physicalEpisodeId.trim();
        String description =
                "actual:" + clean(episodeId) + ':' + pageIndex + ':' + session.generation
                    + ";actualAtNanos=" + Math.max(0L, session.latestActualAtNanos);
        session.latestActualDescription = description;
        // Once the launch episode is fully render-ready, keep that one-shot evidence attached
        // to every later physical commit. Continuous reading legitimately replaces the root
        // node's episode/page identity with an appended episode; dropping the suffix here made
        // the benchmark lose an already-observed completion and wait until timeout. This only
        // preserves telemetry on the accessibility node and does not gate drawing or input.
        String allReady = session.allImagesReady.get()
                ? ";allReady=" + session.allImagesReadyPageCount
                    + ";allReadyAtNanos=" + session.allImagesReadyAtNanos
                : "";
        String publishedDescription = description + allReady;
        Runnable publish = () -> view.setContentDescription(publishedDescription);
        if(Looper.myLooper() == Looper.getMainLooper())
            publish.run();
        else
            MAIN.post(publish);
    }

    private static void event(String name, Session session, String fields) {
        String prefix = session == null
                ? "generation=0,work=none,episode=none"
                : "generation=" + session.generation
                    + ",work=" + clean(session.workId)
                    + ",episode=" + clean(session.episodeId);
        PerfTrace.mark("viewer_event", "name=" + clean(name) + ',' + prefix + ',' + fields);
        StringBuilder json = new StringBuilder(256);
        json.append('{');
        appendJson(json, "event", clean(name), true);
        appendJson(json, "timestampNanos", Long.toString(SystemClock.elapsedRealtimeNanos()), false);
        appendJson(json, "generation", session == null ? "0" : Long.toString(session.generation), false);
        appendJson(json, "workId", session == null ? "none" : clean(session.workId), true);
        appendJson(json, "episodeId", session == null ? "none" : clean(session.episodeId), true);
        if(fields != null && fields.length() > 0) {
            String[] pairs = fields.split(",");
            for(String pair : pairs) {
                int separator = pair.indexOf('=');
                if(separator <= 0)
                    continue;
                String key = clean(pair.substring(0, separator));
                String value = clean(pair.substring(separator + 1));
                appendJson(json, key, value, !isJsonLiteral(value));
            }
        }
        json.append('}');
        Log.i(TAG, json.toString());
    }

    private static void appendJson(
            StringBuilder target, String key, String value, boolean quoted) {
        if(target.length() > 1)
            target.append(',');
        target.append('"').append(jsonEscape(key)).append('"').append(':');
        if(quoted)
            target.append('"').append(jsonEscape(value)).append('"');
        else
            target.append(value);
    }

    private static boolean isJsonLiteral(String value) {
        if("true".equals(value) || "false".equals(value) || "null".equals(value))
            return true;
        if(value.length() == 0)
            return false;
        int index = value.charAt(0) == '-' ? 1 : 0;
        if(index == value.length())
            return false;
        boolean dotSeen = false;
        for(; index < value.length(); index++) {
            char character = value.charAt(index);
            if(character == '.' && !dotSeen) {
                dotSeen = true;
                continue;
            }
            if(character < '0' || character > '9')
                return false;
        }
        return true;
    }

    private static String jsonEscape(String value) {
        return safe(value, "unknown")
                .replace("\\", "\\\\")
                .replace("\"", "\\\"");
    }

    private static int nextCookie() {
        int value = NEXT_COOKIE.incrementAndGet();
        if(value == Integer.MAX_VALUE) {
            NEXT_COOKIE.compareAndSet(Integer.MAX_VALUE, 1);
            value = NEXT_COOKIE.incrementAndGet();
        }
        return value;
    }

    private static String safe(String value, String fallback) {
        return value == null || value.length() == 0 ? fallback : value;
    }

    private static String clean(String value) {
        return safe(value, "unknown")
                .replace(',', '_')
                .replace('\n', '_')
                .replace('\r', '_');
    }

    private static final class Session {
        final long generation;
        final String workId;
        final String episodeId;
        final String mode;
        final long openedAtNanos;
        final int openCookie;
        final int scrollCookie;
        final int allImagesReadyCookie;
        final AtomicBoolean firstActualFrame = new AtomicBoolean(false);
        final AtomicBoolean allImagesReady = new AtomicBoolean(false);
        final AtomicBoolean scrollTraceOpen = new AtomicBoolean(false);
        final AtomicBoolean closeRequested = new AtomicBoolean(false);
        final AtomicBoolean closeFinalizing = new AtomicBoolean(false);
        final AtomicBoolean imagePipelineSummaryPublished = new AtomicBoolean(false);
        final AtomicLong entryPssKb = new AtomicLong();
        final AtomicLong entryGcCount = new AtomicLong(-1L);
        final AtomicLong maxPssKb = new AtomicLong();
        final AtomicLong imageRequestStarted = new AtomicLong();
        final AtomicLong imageRequestSucceeded = new AtomicLong();
        final AtomicLong imageRequestCancelled = new AtomicLong();
        final AtomicLong imageRequestFailed = new AtomicLong();
        final AtomicLong imageResponseBytes = new AtomicLong();
        final AtomicLong imageMetadataCount = new AtomicLong();
        final AtomicLong imageEncodedBytes = new AtomicLong();
        final AtomicLong imageWidthSum = new AtomicLong();
        final AtomicLong imageHeightSum = new AtomicLong();
        final AtomicLong imageMaxWidth = new AtomicLong();
        final AtomicLong imageMaxHeight = new AtomicLong();
        final AtomicLong networkObservationCount = new AtomicLong();
        final AtomicLong networkReusedCount = new AtomicLong();
        final ConcurrentHashMap<String, Boolean> imageFormats = new ConcurrentHashMap<>();
        final ConcurrentHashMap<String, Boolean> imageHosts = new ConcurrentHashMap<>();
        final ConcurrentHashMap<String, Boolean> networkProtocols = new ConcurrentHashMap<>();
        volatile long firstActualFrameAtNanos;
        volatile long latestActualAtNanos;
        volatile long allImagesReadyAtNanos;
        volatile int allImagesReadyPageCount;
        volatile String latestActualDescription;
        volatile ScheduledFuture<?> memorySampler;
        long lastNativeScrollPresentationNanos;
        long nativeScrollIntervalCount;
        long nativeScrollIntervalNanos;
        long nativeWorstIntervalNanos;
        long nativeSlowIntervals;
        long nativeConsecutiveSlowIntervals;
        long nativeMaxConsecutiveSlowIntervals;
        long nativeRefreshPeriodNanos;

        Session(long generation, String workId, String episodeId, String mode,
                long openedAtNanos, int openCookie, int scrollCookie, int allImagesReadyCookie) {
            this.generation = generation;
            this.workId = workId;
            this.episodeId = episodeId;
            this.mode = mode;
            this.openedAtNanos = openedAtNanos;
            this.openCookie = openCookie;
            this.scrollCookie = scrollCookie;
            this.allImagesReadyCookie = allImagesReadyCookie;
        }

        synchronized void recordQualifiedActualFrame(
                long actualFrameNanos,
                float velocityPxPerSecond,
                float refreshRate) {
            if(Math.abs(velocityPxPerSecond) <= 25.0f) {
                lastNativeScrollPresentationNanos = 0L;
                nativeConsecutiveSlowIntervals = 0L;
                return;
            }
            long refreshPeriod = (long) (1_000_000_000.0d /
                    Math.max(30.0d, Math.min(240.0d, refreshRate)));
            nativeRefreshPeriodNanos = refreshPeriod;
            long previous = lastNativeScrollPresentationNanos;
            lastNativeScrollPresentationNanos = actualFrameNanos;
            if(previous <= 0L || actualFrameNanos <= previous)
                return;
            long interval = actualFrameNanos - previous;
            // Idle callbacks above define gesture boundaries. Any active-to-active interval,
            // including a multi-second freeze, is real user-visible frame latency and must stay
            // in worst/slow qualification statistics.
            nativeScrollIntervalCount++;
            nativeScrollIntervalNanos += interval;
            nativeWorstIntervalNanos = Math.max(nativeWorstIntervalNanos, interval);
            if(interval > refreshPeriod + refreshPeriod / 2L) {
                nativeSlowIntervals++;
                nativeConsecutiveSlowIntervals++;
                nativeMaxConsecutiveSlowIntervals = Math.max(
                        nativeMaxConsecutiveSlowIntervals,
                        nativeConsecutiveSlowIntervals);
            } else {
                nativeConsecutiveSlowIntervals = 0L;
            }
        }

        synchronized void startPhysicalScrollGesture() {
            lastNativeScrollPresentationNanos = 0L;
            nativeConsecutiveSlowIntervals = 0L;
        }

        synchronized void endPhysicalScrollMotion() {
            lastNativeScrollPresentationNanos = 0L;
            nativeConsecutiveSlowIntervals = 0L;
        }

        synchronized String nativeFrameSummary() {
            double fps = nativeScrollIntervalNanos <= 0L
                    ? 0.0d
                    : nativeScrollIntervalCount * 1_000_000_000.0d /
                            nativeScrollIntervalNanos;
            double slowPercent = nativeScrollIntervalCount <= 0L
                    ? 0.0d
                    : nativeSlowIntervals * 100.0d / nativeScrollIntervalCount;
            return "scrollIntervals=" + nativeScrollIntervalCount
                    + ",scrollFps=" + String.format(Locale.US, "%.4f", fps)
                    + ",slowIntervals=" + nativeSlowIntervals
                    + ",slowIntervalPercent="
                    + String.format(Locale.US, "%.4f", slowPercent)
                    + ",worstIntervalMs="
                    + String.format(Locale.US, "%.4f", nativeWorstIntervalNanos / 1_000_000.0d)
                    + ",maxConsecutiveSlowIntervals=" + nativeMaxConsecutiveSlowIntervals
                    + ",refreshPeriodMs="
                    + String.format(Locale.US, "%.4f", nativeRefreshPeriodNanos / 1_000_000.0d);
        }

        synchronized void publishNativeFrameTraceCounters() {
            // SurfaceFlinger does not expose a stable child-layer slice name on every host-GPU
            // emulator. Publish the same production frame-commit callback evidence as counters so
            // Macrobenchmark can retain a trace-backed metric without falling back to UI frames.
            PerfTrace.counter("ViewerNativeScrollIntervalCount", nativeScrollIntervalCount);
            // ATrace counter payloads saturate near signed 32-bit range on some emulator builds.
            // Microseconds preserve sub-frame precision while a long traversal stays well below
            // that transport ceiling.
            PerfTrace.counter(
                    "ViewerNativeScrollIntervalMicros",
                    nativeScrollIntervalNanos / 1_000L);
            PerfTrace.counter("ViewerNativeScrollSlowIntervals", nativeSlowIntervals);
            PerfTrace.counter("ViewerNativeScrollWorstIntervalNanos", nativeWorstIntervalNanos);
            PerfTrace.counter(
                    "ViewerNativeScrollMaxConsecutiveSlowIntervals",
                    nativeMaxConsecutiveSlowIntervals);
            PerfTrace.counter("ViewerNativeScrollRefreshPeriodNanos", nativeRefreshPeriodNanos);
        }
    }

    private static final class Operation {
        final long id;
        final int cookie;
        final String traceName;
        final int pageIndex;
        final String sourceKeyHash;
        final String urlHost;
        final int priority;
        final long startedAtNanos;

        Operation(long id, int cookie, String traceName, int pageIndex, String sourceKeyHash,
                  String urlHost, int priority, long startedAtNanos) {
            this.id = id;
            this.cookie = cookie;
            this.traceName = traceName;
            this.pageIndex = pageIndex;
            this.sourceKeyHash = sourceKeyHash;
            this.urlHost = urlHost;
            this.priority = priority;
            this.startedAtNanos = startedAtNanos;
        }

        String metadata() {
            return String.format(Locale.US,
                    "operation=%d,pageIndex=%d,priority=%d,sourceKeyHash=%s,urlHost=%s",
                    id, pageIndex, priority, clean(sourceKeyHash), clean(urlHost));
        }
    }
}
