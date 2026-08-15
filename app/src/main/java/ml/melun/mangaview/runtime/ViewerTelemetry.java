package ml.melun.mangaview.runtime;

import ml.melun.mangaview.benchmark.BenchmarkAdjacentCommitSignal;
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

    /**
     * Immutable presentation-cadence evidence for the active native viewer session.
     *
     * <p>The counters come from committed native frames, not Java UI callbacks. Keeping this
     * snapshot read-only lets instrumentation calculate exact before/after deltas without
     * resetting or otherwise changing production telemetry.</p>
     */
    public static final class NativeFrameStatsSnapshot {
        private final long generation;
        private final long scrollIntervals;
        private final long scrollIntervalNanos;
        private final long slowIntervals;
        private final long worstIntervalNanos;
        private final long maxConsecutiveSlowIntervals;
        private final long refreshPeriodNanos;
        private final String slowIntervalDetails;
        private final long recordedSlowIntervalFirstOrdinal;
        private final long[] recordedSlowIntervalDurationsNanos;

        private NativeFrameStatsSnapshot(
                long generation,
                long scrollIntervals,
                long scrollIntervalNanos,
                long slowIntervals,
                long worstIntervalNanos,
                long maxConsecutiveSlowIntervals,
                long refreshPeriodNanos,
                String slowIntervalDetails,
                long recordedSlowIntervalFirstOrdinal,
                long[] recordedSlowIntervalDurationsNanos) {
            this.generation = generation;
            this.scrollIntervals = scrollIntervals;
            this.scrollIntervalNanos = scrollIntervalNanos;
            this.slowIntervals = slowIntervals;
            this.worstIntervalNanos = worstIntervalNanos;
            this.maxConsecutiveSlowIntervals = maxConsecutiveSlowIntervals;
            this.refreshPeriodNanos = refreshPeriodNanos;
            this.slowIntervalDetails = slowIntervalDetails;
            this.recordedSlowIntervalFirstOrdinal = recordedSlowIntervalFirstOrdinal;
            this.recordedSlowIntervalDurationsNanos =
                    recordedSlowIntervalDurationsNanos.clone();
        }

        public long getGeneration() {
            return generation;
        }

        public long getScrollIntervals() {
            return scrollIntervals;
        }

        public long getScrollIntervalNanos() {
            return scrollIntervalNanos;
        }

        public long getSlowIntervals() {
            return slowIntervals;
        }

        public long getWorstIntervalNanos() {
            return worstIntervalNanos;
        }

        public long getMaxConsecutiveSlowIntervals() {
            return maxConsecutiveSlowIntervals;
        }

        public long getRefreshPeriodNanos() {
            return refreshPeriodNanos;
        }

        public String getSlowIntervalDetails() {
            return slowIntervalDetails;
        }

        /**
         * Returns the worst recorded slow interval added after a prior cumulative count.
         * A negative result means the requested boundary predates the bounded diagnostic ring.
         */
        public long getMaxRecordedSlowIntervalDurationSince(long priorSlowIntervalCount) {
            if(priorSlowIntervalCount < recordedSlowIntervalFirstOrdinal)
                return -1L;
            long firstOrdinal = Math.max(
                    priorSlowIntervalCount,
                    recordedSlowIntervalFirstOrdinal);
            long maxDuration = 0L;
            for(long ordinal = firstOrdinal; ordinal < slowIntervals; ordinal++) {
                int index = (int) (ordinal - recordedSlowIntervalFirstOrdinal);
                if(index >= 0 && index < recordedSlowIntervalDurationsNanos.length)
                    maxDuration = Math.max(
                            maxDuration,
                            recordedSlowIntervalDurationsNanos[index]);
            }
            return maxDuration;
        }
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
        Session session = SESSION.get();
        Operation operation = new Operation(
                id, cookie, "ImageDecode", pageIndex, safe(sourceKeyHash, "unknown"),
                "none", 0,
                SystemClock.elapsedRealtimeNanos(), session);
        DECODES.put(id, operation);
        PerfTrace.beginAsync("ImageDecode", cookie);
        PerfTrace.counter("ViewerActiveDecodes", DECODES.size());
        // Perfetto owns per-image timing. Serializing two JSON/logcat events for every page made
        // 100+ parallel cold decoders contend on logd exactly on the all-images critical path.
        // Keep human-readable detail for the first real page; failures are always logged below.
        if(pageIndex == 0)
            event("decode", session, "phase=start," + operation.metadata());
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
            String requestEpisodePath,
            String requestRole,
            String protocol,
            String connectionId,
            boolean connectionReused,
            String clientInstanceId,
            boolean clientInstanceMeasured) {
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
                        + ",requestEpisodePath=" + clean(requestEpisodePath)
                        + ",requestRole=" + clean(requestRole)
                        + ",protocol=" + clean(protocol)
                        + ",connectionId=" + clean(connectionId)
                        + ",connectionReused=" + connectionReused
                        + ",clientInstanceId=" + clean(clientInstanceId)
                        + ",clientInstanceMeasured=" + clientInstanceMeasured);
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
            float velocityPxPerSecond,
            long inputOldestNanos,
            long inputNewestNanos) {
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
                "responseToPresentMs",
                null,
                inputOldestNanos,
                inputNewestNanos);
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
        if(session != null) {
            session.endPhysicalScrollMotion();
            // Reader lifecycle transitions clear the accessibility node.  Let the first valid
            // frame after a gesture/lifecycle boundary republish its semantic identity without
            // returning to the old every-frame timestamp churn.
            session.actualStatePublicationGate.reset();
        }
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
                null,
                0L,
                0L);
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
                physicalEpisodeId,
                0L,
                0L);
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
            String physicalEpisodeId,
            long inputOldestNanos,
            long inputNewestNanos) {
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
                refreshRate,
                inputOldestNanos,
                inputNewestNanos);

        boolean firstActualFrame;
        synchronized(session) {
            firstActualFrame = session.firstActualFrame.compareAndSet(false, true);
            if(firstActualFrame) {
                session.firstActualFrameAtNanos = evidenceAtNanos > 0L
                        ? evidenceAtNanos
                        : SystemClock.elapsedRealtimeNanos();
                session.firstActualEpisodeId = physicalEpisodeId == null
                        || physicalEpisodeId.trim().isEmpty()
                        ? session.episodeId
                        : physicalEpisodeId.trim();
                session.firstActualSourcePage = firstVisiblePage;
            }
        }
        if(firstActualFrame) {
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
        // Keep the one-shot first-actual clock above for launch timing, but also publish the
        // timestamp of this exact identity-valid physical frame. Benchmark semantic proof must
        // bind its accessibility state to the matching compositor IPC rather than a prior frame.
        session.latestActualPresentedAtNanos = evidenceAtNanos > 0L
                ? evidenceAtNanos
                : SystemClock.elapsedRealtimeNanos();
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
                ? edge + adjacentTimingSuffix(session)
                : actual + ";edge=" + edge.substring("viewer-edge:".length()) + allReady
                    + adjacentTimingSuffix(session);
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
    public static void markAllImagesRenderReady(int pageCount) {
        if(pageCount <= 0)
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
    }

    public static void allImagesRenderReady(View hostView, int pageCount) {
        if(hostView == null || pageCount <= 0)
            return;
        markAllImagesRenderReady(pageCount);
        Session session = SESSION.get();
        if(session == null || !session.allImagesReady.get()
                || !session.allImagesReadyPresentationPublished.compareAndSet(false, true))
            return;
        publishImagePipelineSummary(session);
        event("network_pipeline_summary", session,
                "observationCount=" + session.networkObservationCount.get()
                        + ",connectionReusedCount=" + session.networkReusedCount.get()
                        + ",protocols=" + joinedKeys(session.networkProtocols));

        long completedAtNanos = session.allImagesReadyAtNanos;
        String actual = session.latestActualDescription;
        final String description = actual == null || actual.length() == 0
                ? "viewer-all-ready:" + clean(session.episodeId) + ':' + pageCount + ':'
                    + session.generation + ";allReadyAtNanos=" + completedAtNanos
                : actual + ";edge=middle;allReady=" + pageCount
                    + ";allReadyAtNanos=" + completedAtNanos;
        final String publishedDescription = description + adjacentTimingSuffix(session);
        Runnable publish = () -> {
            hostView.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_YES);
            hostView.setContentDescription(publishedDescription);
            hostView.sendAccessibilityEvent(AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED);
        };
        if(Looper.myLooper() == Looper.getMainLooper())
            publish.run();
        else
            MAIN.post(publish);
    }

    public static void adjacentWorkStarted(String sourceEpisodeId) {
        Session session = SESSION.get();
        if(session == null)
            return;
        long now = SystemClock.elapsedRealtimeNanos();
        synchronized(session) {
            if(session.adjacentWorkStartedAtNanos > 0L)
                return;
            session.adjacentWorkStartedAtNanos = now;
        }
        event("adjacent_work_started", session,
                "sourceEpisode=" + clean(sourceEpisodeId) + ",atNanos=" + now);
    }

    public static void adjacentRunwayReady(
            String targetEpisodeId, int pageCount, int totalPageCount) {
        Session session = SESSION.get();
        if(session == null || pageCount <= 0 || totalPageCount < pageCount)
            return;
        String targetEpisode = clean(targetEpisodeId);
        long now = SystemClock.elapsedRealtimeNanos();
        synchronized(session) {
            if(session.adjacentRunwayReadyAtNanos > 0L)
                return;
            session.adjacentRunwayReadyAtNanos = now;
            session.adjacentRunwayTargetEpisode = targetEpisode;
            session.adjacentRunwayPageCount = pageCount;
            session.adjacentTotalPageCount = totalPageCount;
        }
        BenchmarkAdjacentCommitSignal.publishRunwayReady(
                targetEpisode,
                pageCount,
                totalPageCount,
                session.adjacentWorkStartedAtNanos,
                now,
                session.generation);
        event("adjacent_runway_ready", session,
                "targetEpisode=" + targetEpisode
                        + ",pageCount=" + pageCount
                        + ",totalPageCount=" + totalPageCount
                        + ",atNanos=" + now);
    }

    public static void adjacentActualDrawCommitted(
            String physicalEpisodeId, long presentedAtNanos) {
        Session session = SESSION.get();
        if(session == null || physicalEpisodeId == null
                || physicalEpisodeId.trim().isEmpty()
                || physicalEpisodeId.trim().equals(session.episodeId))
            return;
        long now = SystemClock.elapsedRealtimeNanos();
        long actualAtNanos = presentedAtNanos > 0L ? presentedAtNanos : now;
        synchronized(session) {
            // Preserve the first physical adjacent pixels even if commit callbacks are delivered
            // out of order. The caller limits this marker to the direct-Wi-Fi UX policy.
            if(session.firstAdjacentActualAtNanos > 0L
                    && session.firstAdjacentActualAtNanos <= actualAtNanos)
                return;
            session.firstAdjacentActualAtNanos = actualAtNanos;
            session.firstAdjacentActualEpisode = clean(physicalEpisodeId);
        }
        event("first_adjacent_actual", session,
                "episode=" + clean(physicalEpisodeId) + ",atNanos=" + actualAtNanos);
    }

    public static void forwardBoundaryReached() {
        forwardBoundaryReached(0L);
    }

    public static void forwardBoundaryReached(long presentedAtNanos) {
        Session session = SESSION.get();
        if(session == null)
            return;
        long now = SystemClock.elapsedRealtimeNanos();
        long boundaryAtNanos = presentedAtNanos > 0L ? presentedAtNanos : now;
        synchronized(session) {
            // Surface commit callbacks can arrive out of order. Preserve the earliest physical
            // presentation of the launch tail instead of the callback delivery time.
            if(session.forwardBoundaryReachedAtNanos > 0L
                    && session.forwardBoundaryReachedAtNanos <= boundaryAtNanos)
                return;
            session.forwardBoundaryReachedAtNanos = boundaryAtNanos;
        }
        event("forward_boundary_reached", session, "atNanos=" + boundaryAtNanos);
    }

    /** Read-only benchmark evidence; it never participates in rendering or loading decisions. */
    public static long currentForwardBoundaryReachedAtNanos() {
        Session session = SESSION.get();
        if(session == null)
            return 0L;
        synchronized(session) {
            return Math.max(0L, session.forwardBoundaryReachedAtNanos);
        }
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

    public static NativeFrameStatsSnapshot nativeFrameStatsSnapshot() {
        Session session = SESSION.get();
        return session == null ? null : session.nativeFrameStatsSnapshot();
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
        Session session = SESSION.get();
        long nowNanos = SystemClock.elapsedRealtimeNanos();
        long boundedStartedAtNanos = startedAtNanos > 0L && startedAtNanos <= nowNanos
                ? startedAtNanos : nowNanos;
        Operation operation = new Operation(
                id, cookie, traceName, pageIndex, sourceKeyHash, urlHost, priority,
                boundedStartedAtNanos, session);
        REQUESTS.put(id, operation);
        if("ImageRequest".equals(traceName) && session != null)
            session.recordImageRequestStarted();
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
        Session session = operation.ownerSession;
        if("ImageRequest".equals(operation.traceName) && session != null) {
            session.recordImageRequestTerminal(phase, bytes);
        }
        boolean sampledImageSuccess = "ImageRequest".equals(operation.traceName)
                && phase.equals("end")
                && operation.pageIndex != 0;
        if(emitDetailedEvent && !sampledImageSuccess) {
            event(operation.traceName.equals("ImageDecode") ? "decode" :
                            operation.traceName.equals("PageListRequest")
                                    ? "page_list_request" : "image_request",
                    session,
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
        ImageRequestStatsSnapshot requests = session.imageRequestStatsSnapshot();
        event("image_pipeline_summary", session,
                "requestStarted=" + requests.started
                        + ",requestSucceeded=" + requests.succeeded
                        + ",requestCancelled=" + requests.cancelled
                        + ",requestFailed=" + requests.failed
                        + ",requestActive=" + requests.active
                        + ",requestPeakActive=" + requests.peakActive
                        + ",requestTerminalBalance=" + requests.terminalBalance
                        + ",responseBytes=" + requests.responseBytes
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
            finalizeCloseOnMain(session, reason, false, deadlineNanos);
            return;
        }
        if(SystemClock.elapsedRealtimeNanos() >= deadlineNanos) {
            // Do not manufacture a drained boundary by deleting telemetry operations. A timeout
            // is explicit failure evidence and preserves the real non-zero counts in viewer_closed.
            finalizeCloseOnMain(session, reason, true, deadlineNanos);
            return;
        }
        MEMORY_SAMPLER.schedule(
                () -> attemptCloseAfterDrain(session, reason, deadlineNanos),
                CLOSE_DRAIN_POLL_MS,
                TimeUnit.MILLISECONDS);
    }

    private static void finalizeCloseOnMain(
            Session session, String reason, boolean drainTimedOut, long deadlineNanos) {
        if(!session.closeFinalizing.compareAndSet(false, true))
            return;
        Runnable finish = () -> {
            if(SESSION.get() != session) {
                session.closeFinalizing.set(false);
                return;
            }
            // The sampler's empty observation and this main-thread runnable are not one atomic
            // step. A deferred image operation can publish valid headers in that gap while its
            // cancelled physical Call is still unwinding. Revalidate on the thread that emits
            // viewer_closed; otherwise drainTimedOut=false can be paired with activeRequests>0.
            if(!drainTimedOut && (!REQUESTS.isEmpty() || !DECODES.isEmpty())) {
                session.closeFinalizing.set(false);
                attemptCloseAfterDrain(session, reason, deadlineNanos);
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
        // Fixed delay prevents cached-process pauses from replaying a burst of missed samples
        // onto the UI/process as soon as Android makes the app runnable again.
        session.memorySampler = MEMORY_SAMPLER.scheduleWithFixedDelay(
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
        String allReadyKey = session.allImagesReady.get()
                ? session.allImagesReadyPageCount + ":" + session.allImagesReadyAtNanos
                : "pending";
        String adjacentKey = adjacentTimingSuffix(session);
        String semanticKey = clean(episodeId) + ':' + pageIndex + ':' + session.generation
                + ";allReady=" + allReadyKey + adjacentKey;
        // The presented timestamp changes at display refresh rate, but the accessibility state
        // does not. Rewriting contentDescription for every frame causes Android to allocate and
        // dispatch accessibility work indefinitely during a long scroll. Preserve exact timing
        // on the first frame for each semantic identity/milestone and keep native cadence in the
        // dedicated counters above.
        long publicationVersion = session.actualStatePublicationGate.claimVersion(semanticKey);
        if(publicationVersion == 0L)
            return;
        String description =
                "actual:" + clean(episodeId) + ':' + pageIndex + ':' + session.generation
                    + ";actualAtNanos=" + Math.max(0L, session.latestActualAtNanos)
                    + ";actualPresentedAtNanos="
                    + Math.max(0L, session.latestActualPresentedAtNanos)
                    // The main actual identity intentionally advances to the furthest visible
                    // canonical source. Preserve the first qualified physical identity separately
                    // so Continue can prove its exact restored anchor after that advance.
                    + ";firstActualEpisode=" + clean(session.firstActualEpisodeId)
                    + ";firstActualSourcePage="
                    + Math.max(-1, session.firstActualSourcePage);
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
        String publishedDescription =
                description + allReady + adjacentKey;
        Runnable publish = () -> {
            // Native strip presentation arrives off-main. Home/focus teardown can reset the
            // semantic gate after this Runnable was posted but before it runs; never let that
            // retired publication recreate an `actual:` node in the background. A newer semantic
            // claim likewise supersedes an older queued write instead of allowing callback order
            // to regress the observable physical identity.
            if(SESSION.get() != session ||
                    !session.actualStatePublicationGate.isCurrent(
                            semanticKey, publicationVersion))
                return;
            view.setContentDescription(publishedDescription);
        };
        if(Looper.myLooper() == Looper.getMainLooper())
            publish.run();
        else
            MAIN.post(publish);
    }

    private static String adjacentTimingSuffix(Session session) {
        if(session == null)
            return "";
        if(session.adjacentWorkStartedAtNanos <= 0L
                && session.adjacentRunwayReadyAtNanos <= 0L
                && session.forwardBoundaryReachedAtNanos <= 0L
                && session.firstAdjacentActualAtNanos <= 0L)
            return "";
        return ";adjacentWorkStartedAtNanos="
                    + Math.max(0L, session.adjacentWorkStartedAtNanos)
                + ";adjacentRunwayReadyAtNanos="
                    + Math.max(0L, session.adjacentRunwayReadyAtNanos)
                + ";adjacentRunwayTargetEpisode="
                    + clean(session.adjacentRunwayTargetEpisode)
                + ";adjacentRunwayPageCount="
                    + Math.max(0, session.adjacentRunwayPageCount)
                + ";adjacentTotalPageCount="
                    + Math.max(0, session.adjacentTotalPageCount)
                + ";forwardBoundaryReachedAtNanos="
                    + Math.max(0L, session.forwardBoundaryReachedAtNanos)
                + ";firstAdjacentActualAtNanos="
                    + Math.max(0L, session.firstAdjacentActualAtNanos)
                + ";firstAdjacentActualEpisode="
                    + clean(session.firstAdjacentActualEpisode);
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

    private static final class ImageRequestStatsSnapshot {
        final long started;
        final long succeeded;
        final long cancelled;
        final long failed;
        final long active;
        final long peakActive;
        final long terminalBalance;
        final long responseBytes;

        ImageRequestStatsSnapshot(
                long started, long succeeded, long cancelled, long failed,
                long active, long peakActive, long terminalBalance, long responseBytes) {
            this.started = started;
            this.succeeded = succeeded;
            this.cancelled = cancelled;
            this.failed = failed;
            this.active = active;
            this.peakActive = peakActive;
            this.terminalBalance = terminalBalance;
            this.responseBytes = responseBytes;
        }
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
        final AtomicBoolean allImagesReadyPresentationPublished = new AtomicBoolean(false);
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
        final AtomicLong imageRequestActive = new AtomicLong();
        final AtomicLong imageRequestPeakActive = new AtomicLong();
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
        final SemanticPublicationGate actualStatePublicationGate =
                new SemanticPublicationGate();
        volatile long firstActualFrameAtNanos;
        volatile String firstActualEpisodeId = "";
        volatile int firstActualSourcePage = -1;
        volatile long latestActualAtNanos;
        volatile long latestActualPresentedAtNanos;
        volatile long allImagesReadyAtNanos;
        volatile long adjacentWorkStartedAtNanos;
        volatile long adjacentRunwayReadyAtNanos;
        volatile long forwardBoundaryReachedAtNanos;
        volatile long firstAdjacentActualAtNanos;
        volatile String adjacentRunwayTargetEpisode = "";
        volatile String firstAdjacentActualEpisode = "";
        volatile int adjacentRunwayPageCount;
        volatile int adjacentTotalPageCount;
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
        static final int MAX_RECORDED_SLOW_INTERVALS = 32;
        final long[] nativeSlowIntervalEndNanos =
                new long[MAX_RECORDED_SLOW_INTERVALS];
        final long[] nativeSlowIntervalDurationsNanos =
                new long[MAX_RECORDED_SLOW_INTERVALS];

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

        synchronized void recordImageRequestStarted() {
            imageRequestStarted.incrementAndGet();
            long active = imageRequestActive.incrementAndGet();
            updateMax(imageRequestPeakActive, active);
        }

        synchronized void recordImageRequestTerminal(String phase, long bytes) {
            if(phase.equals("cancel"))
                imageRequestCancelled.incrementAndGet();
            else if(phase.equals("fail"))
                imageRequestFailed.incrementAndGet();
            else {
                imageRequestSucceeded.incrementAndGet();
                imageResponseBytes.addAndGet(Math.max(0L, bytes));
            }
            imageRequestActive.decrementAndGet();
        }

        synchronized ImageRequestStatsSnapshot imageRequestStatsSnapshot() {
            long started = imageRequestStarted.get();
            long succeeded = imageRequestSucceeded.get();
            long cancelled = imageRequestCancelled.get();
            long failed = imageRequestFailed.get();
            return new ImageRequestStatsSnapshot(
                    started,
                    succeeded,
                    cancelled,
                    failed,
                    imageRequestActive.get(),
                    imageRequestPeakActive.get(),
                    started - succeeded - cancelled - failed,
                    imageResponseBytes.get());
        }

        synchronized void recordQualifiedActualFrame(
                long actualFrameNanos,
                float velocityPxPerSecond,
                float refreshRate,
                long inputOldestNanos,
                long inputNewestNanos) {
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
            // Keep the raw interval distribution for diagnostics. Slow-frame qualification is
            // causal: sparse touch samples are not missed app frames, while queued input remains
            // visible through the native frame's oldest input timestamp.
            nativeScrollIntervalCount++;
            nativeScrollIntervalNanos += interval;
            nativeWorstIntervalNanos = Math.max(nativeWorstIntervalNanos, interval);
            if(ViewerFrameCadencePolicy.isDemandBackedSlowInterval(
                    interval,
                    refreshPeriod,
                    actualFrameNanos,
                    inputOldestNanos,
                    inputNewestNanos)) {
                nativeSlowIntervals++;
                int slot = (int) ((nativeSlowIntervals - 1L) %
                        MAX_RECORDED_SLOW_INTERVALS);
                nativeSlowIntervalEndNanos[slot] = actualFrameNanos;
                nativeSlowIntervalDurationsNanos[slot] = interval;
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

        synchronized NativeFrameStatsSnapshot nativeFrameStatsSnapshot() {
            StringBuilder slowDetails = new StringBuilder();
            long first = Math.max(0L,
                    nativeSlowIntervals - MAX_RECORDED_SLOW_INTERVALS);
            long[] recordedDurations = new long[(int) (nativeSlowIntervals - first)];
            for (long ordinal = first; ordinal < nativeSlowIntervals; ordinal++) {
                int slot = (int) (ordinal % MAX_RECORDED_SLOW_INTERVALS);
                recordedDurations[(int) (ordinal - first)] =
                        nativeSlowIntervalDurationsNanos[slot];
                if (slowDetails.length() > 0) slowDetails.append(';');
                slowDetails.append(nativeSlowIntervalEndNanos[slot])
                        .append(':')
                        .append(nativeSlowIntervalDurationsNanos[slot]);
            }
            return new NativeFrameStatsSnapshot(
                    generation,
                    nativeScrollIntervalCount,
                    nativeScrollIntervalNanos,
                    nativeSlowIntervals,
                    nativeWorstIntervalNanos,
                    nativeMaxConsecutiveSlowIntervals,
                    nativeRefreshPeriodNanos,
                    slowDetails.toString(),
                    first,
                    recordedDurations);
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

    /** Small thread-safe gate kept testable without Android UI machinery. */
    static final class SemanticPublicationGate {
        private String lastKey = "";
        private long version;

        synchronized boolean claim(String key) {
            return claimVersion(key) != 0L;
        }

        synchronized long claimVersion(String key) {
            String safeKey = key == null ? "" : key;
            if(safeKey.equals(lastKey))
                return 0L;
            lastKey = safeKey;
            version = nextVersion(version);
            return version;
        }

        synchronized boolean isCurrent(String key, long claimedVersion) {
            String safeKey = key == null ? "" : key;
            return claimedVersion > 0L && claimedVersion == version && safeKey.equals(lastKey);
        }

        synchronized void reset() {
            lastKey = "";
            version = nextVersion(version);
        }

        private static long nextVersion(long current) {
            return current == Long.MAX_VALUE ? 1L : current + 1L;
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
        final Session ownerSession;

        Operation(long id, int cookie, String traceName, int pageIndex, String sourceKeyHash,
                  String urlHost, int priority, long startedAtNanos, Session ownerSession) {
            this.id = id;
            this.cookie = cookie;
            this.traceName = traceName;
            this.pageIndex = pageIndex;
            this.sourceKeyHash = sourceKeyHash;
            this.urlHost = urlHost;
            this.priority = priority;
            this.startedAtNanos = startedAtNanos;
            this.ownerSession = ownerSession;
        }

        String metadata() {
            return String.format(Locale.US,
                    "operation=%d,pageIndex=%d,priority=%d,sourceKeyHash=%s,urlHost=%s",
                    id, pageIndex, priority, clean(sourceKeyHash), clean(urlHost));
        }
    }
}
