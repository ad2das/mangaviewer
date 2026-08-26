package ml.melun.mangaview.activity;

import android.annotation.TargetApi;
import android.content.Context;
import android.net.http.HttpEngine;
import android.net.http.HttpException;
import android.net.http.ConnectionMigrationOptions;
import android.net.http.QuicOptions;
import android.net.http.UrlRequest;
import android.net.http.UrlResponseInfo;
import android.net.http.UploadDataProvider;
import android.net.http.UploadDataSink;
import android.os.Build;

import java.io.ByteArrayOutputStream;
import java.net.ProtocolException;
import java.net.URI;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;

@TargetApi(34)
public final class NtkQuicFetcher {
    private static volatile Boolean runtimeAvailable;
    private static final long EXECUTOR_SHUTDOWN_GRACE_MS = 5_000L;
    private static final long EXACT_IDENTITY_RESUME_HARD_TIMEOUT_MS = 3_000L;
    private static final long MAX_EXACT_IDENTITY_IMAGE_BYTES = 64L * 1024L * 1024L;
    // A 108-page cold Wi-Fi trace left 81,473 bytes on its final body at the hard wall.
    // 128 KiB keeps that already-progressing stream eligible without extending a distant tail.
    private static final long EXACT_IDENTITY_TAIL_GRACE_BYTES = 128L * 1024L;
    private static final long EXACT_IDENTITY_TAIL_PROGRESS_PROBE_MS = 250L;
    private static final long EXACT_IDENTITY_TAIL_PROGRESS_GRACE_MS = 750L;
    private static final long DIRECT_WIFI_DRAIN_CLOSE_RETRY_MS = 250L;
    private static final int DIRECT_WIFI_DRAIN_CLOSE_MAX_ATTEMPTS = 20;
    private static final int DIRECT_READ_BUFFER_POOL_CAPACITY = 8;
    private static final int DIRECT_READ_BUFFER_TINY_BYTES = 256;
    private static final int DIRECT_READ_BUFFER_PARTIAL_BYTES = 1024;
    private static final int DIRECT_READ_BUFFER_CONTROL_BYTES = 4 * 1024;
    private static final int DIRECT_READ_BUFFER_DOCUMENT_BYTES = 112 * 1024;
    private static final int DIRECT_READ_BUFFER_BODY_BYTES = 128 * 1024;
    private static final ArrayBlockingQueue<ByteBuffer> DIRECT_TINY_READ_BUFFERS =
            new ArrayBlockingQueue<>(DIRECT_READ_BUFFER_POOL_CAPACITY);
    private static final ArrayBlockingQueue<ByteBuffer> DIRECT_PARTIAL_READ_BUFFERS =
            new ArrayBlockingQueue<>(DIRECT_READ_BUFFER_POOL_CAPACITY);
    private static final ArrayBlockingQueue<ByteBuffer> DIRECT_CONTROL_READ_BUFFERS =
            new ArrayBlockingQueue<>(DIRECT_READ_BUFFER_POOL_CAPACITY);
    private static final ArrayBlockingQueue<ByteBuffer> DIRECT_DOCUMENT_READ_BUFFERS =
            new ArrayBlockingQueue<>(DIRECT_READ_BUFFER_POOL_CAPACITY);
    private static final ArrayBlockingQueue<ByteBuffer> DIRECT_BODY_READ_BUFFERS =
            new ArrayBlockingQueue<>(DIRECT_READ_BUFFER_POOL_CAPACITY);
    private static final ScheduledExecutorService EXECUTOR_CLOSER =
            Executors.newSingleThreadScheduledExecutor(runnable -> {
                Thread thread = new Thread(runnable, "ntk-quic-executor-closer");
                thread.setDaemon(true);
                return thread;
            });

    private NtkQuicFetcher() {
    }

    /**
     * HttpEngine requires a direct buffer for every outstanding read. ART allocation sampling
     * showed the per-request allocateDirect call in the live chapter-scroll allocation tree.
     * These five capacities are the protocol's exact read cadences; pooling only those known
     * shapes preserves every observer boundary while avoiding a fresh non-movable array and
     * NativeAlloc registration for each document/image request.
     */
    private static ByteBuffer acquireDirectReadBuffer(int capacity) {
        ArrayBlockingQueue<ByteBuffer> pool = directReadBufferPool(capacity);
        ByteBuffer buffer = pool == null ? null : pool.poll();
        if(buffer == null)
            buffer = ByteBuffer.allocateDirect(capacity);
        buffer.clear();
        return buffer;
    }

    private static void releaseDirectReadBuffer(ByteBuffer buffer) {
        if(buffer == null || !buffer.isDirect())
            return;
        ArrayBlockingQueue<ByteBuffer> pool = directReadBufferPool(buffer.capacity());
        if(pool == null)
            return;
        buffer.clear();
        pool.offer(buffer);
    }

    private static ArrayBlockingQueue<ByteBuffer> directReadBufferPool(int capacity) {
        switch(capacity) {
            case DIRECT_READ_BUFFER_TINY_BYTES:
                return DIRECT_TINY_READ_BUFFERS;
            case DIRECT_READ_BUFFER_PARTIAL_BYTES:
                return DIRECT_PARTIAL_READ_BUFFERS;
            case DIRECT_READ_BUFFER_CONTROL_BYTES:
                return DIRECT_CONTROL_READ_BUFFERS;
            case DIRECT_READ_BUFFER_DOCUMENT_BYTES:
                return DIRECT_DOCUMENT_READ_BUFFERS;
            case DIRECT_READ_BUFFER_BODY_BYTES:
                return DIRECT_BODY_READ_BUFFERS;
            default:
                return null;
        }
    }

    public interface PartialTextObserver {
        void onPartialText(String text);
    }

    public interface EarlyTextObserver {
        boolean onPartialText(int code, Map<String, List<String>> headers, String text);
    }

    public interface ResponseStartedObserver {
        void onResponseStarted(int code, Map<String, List<String>> headers);
    }

    public interface EarlyResponseStartedObserver {
        boolean onResponseStarted(int code, Map<String, List<String>> headers);
    }

    public interface PartialBytesObserver {
        boolean onPartialBytes(int code, Map<String, List<String>> headers, byte[] bytes);
    }

    /**
     * Non-cancelling cumulative-prefix observer for one exact-owned response. Returning true stops
     * further prefix copies but never stops the physical request; EOF remains mandatory.
     */
    public interface ExactResponseObserver {
        void onResponseStarted(int code, Map<String, List<String>> headers) throws Exception;
        /**
         * The buffer is a synchronous, non-owning view and may contain unused capacity after
         * {@code length}. Callers must neither retain nor mutate it. This keeps a compact document
         * stream from cloning its entire cumulative body at every observation boundary.
         */
        boolean onBodyPrefix(byte[] bytes, int length) throws Exception;

        /** Preferred first cumulative prefix size; EOF ownership is always unchanged. */
        default int initialBodyPrefixBytes() {
            return 112 * 1024;
        }
    }

    public enum TerminalKind {
        UNKNOWN,
        SUCCEEDED,
        FAILED,
        CANCELED,
        CANCELED_BY_INTERNAL_TIMEOUT,
        EARLY
    }

    public enum TailProbeOutcome {
        NOT_ATTEMPTED,
        TERMINAL_IN_PROBE,
        NO_PROGRESS,
        TERMINAL_IN_GRACE,
        GRACE_EXHAUSTED
    }

    /**
     * Linearizes an HttpEngine request with the foreground viewer generation that owns it.
     * Registration happens before {@link UrlRequest#start()}, so a retired owner can reject the
     * request without putting any bytes on the wire. Implementations must make cancel idempotent.
     */
    public interface RequestOwner {
        boolean register(UrlRequest request);
        void unregister(UrlRequest request);
        default boolean isStillAdmitted() {
            return true;
        }
    }

    public static boolean isAvailable() {
        if(Build.VERSION.SDK_INT < 34)
            return false;
        Boolean cached = runtimeAvailable;
        if(cached != null)
            return cached;
        boolean available;
        try {
            Class.forName("android.net.http.HttpEngine", false, NtkQuicFetcher.class.getClassLoader());
            Class.forName("android.net.http.UrlRequest", false, NtkQuicFetcher.class.getClassLoader());
            available = true;
        } catch (Throwable ignored) {
            available = false;
        }
        runtimeAvailable = available;
        return available;
    }

    public static Result fetch(Context context, String url, String userAgent, String cookieHeader, long timeoutMs) {
        return fetch(context, url, userAgent, cookieHeader, Collections.emptyMap(), timeoutMs);
    }

    public static Result fetch(Context context, String url, String userAgent, String cookieHeader,
                        Map<String, String> requestHeaders, long timeoutMs) {
        return fetch(context, url, userAgent, cookieHeader, requestHeaders, "GET", null, timeoutMs);
    }

    public static Result fetch(Context context, String url, String userAgent, String cookieHeader,
                        Map<String, String> requestHeaders, String method, byte[] body, long timeoutMs) {
        return fetch(context, url, userAgent, cookieHeader, requestHeaders, method, body, timeoutMs, null);
    }

    public static Result fetch(Context context, String url, String userAgent, String cookieHeader,
                        Map<String, String> requestHeaders, String method, byte[] body, long timeoutMs,
                        PartialTextObserver partialTextObserver) {
        return fetchWithTransport(context, url, userAgent, cookieHeader, requestHeaders,
                method, body, timeoutMs, true, partialTextObserver);
    }

    public static Result fetchHttp2Only(Context context, String url, String userAgent, String cookieHeader,
                        Map<String, String> requestHeaders, String method, byte[] body, long timeoutMs) {
        return fetchWithTransport(context, url, userAgent, cookieHeader, requestHeaders,
                method, body, timeoutMs, false, null);
    }

    public static Session newHttp2Session(Context context, String userAgent) {
        if(!isAvailable())
            return null;
        try {
            return new Session(context, userAgent, false, null);
        } catch (Throwable throwable) {
            return null;
        }
    }

    public static Session newQuicSession(Context context, String userAgent, String host) {
        return newQuicSession(context, userAgent, host, 1);
    }

    public static Session newQuicSession(Context context, String userAgent, String host,
                                         int callbackThreadCount) {
        if(!isAvailable())
            return null;
        try {
            return new Session(context, userAgent, true, host, callbackThreadCount);
        } catch (Throwable throwable) {
            return null;
        }
    }

    /**
     * Creates the host-scoped QUIC engine used only by direct-Wi-Fi image pools. Disabling both
     * default-network and degraded-path migration prevents an admitted Wi-Fi request from being
     * moved onto cellular during a bearer handoff. Callers still recheck the captured Network at
     * request registration and on every response callback.
     */
    public static Session newDirectWifiQuicSession(Context context, String userAgent, String host,
                                                    int callbackThreadCount) {
        if(!isAvailable())
            return null;
        try {
            return new Session(context, userAgent, true, host, callbackThreadCount, true);
        } catch (Throwable throwable) {
            return null;
        }
    }

    /**
     * One cancellation-aware exact GET for immutable image origins whose TCP/TLS route is reset
     * before HTTP response headers. The request keeps its original URL and never follows a
     * redirect, so the strict reader can apply the same byte/header identity checks as an OkHttp
     * response. Keeping android.net.http ownership here also isolates it behind the API-34 gate.
     */
    public static final class CancelableExactRequest {
        private final AtomicBoolean cancelled = new AtomicBoolean(false);
        private final AtomicReference<UrlRequest> activeRequest = new AtomicReference<>();
        private final Session sharedSession;

        public CancelableExactRequest() {
            this(null);
        }

        /**
         * Reuses one host-scoped engine when a finite immutable image wave owns many exact GETs.
         * Cancellation remains request-local; the pool that supplied the session owns its close.
         */
        public CancelableExactRequest(Session sharedSession) {
            this.sharedSession = sharedSession;
        }

        public Result fetch(Context context, String url, String userAgent, String cookieHeader,
                            Map<String, String> requestHeaders, long timeoutMs) {
            return fetch(context, url, userAgent, cookieHeader, requestHeaders, timeoutMs, null);
        }

        public Result fetch(Context context, String url, String userAgent, String cookieHeader,
                            Map<String, String> requestHeaders, long timeoutMs,
                            BooleanSupplier continuationCheck) {
            if(cancelled.get())
                return Result.error(new InterruptedException("Exact QUIC request cancelled"));
            if(!isAvailable())
                return Result.error(new UnsupportedOperationException("HttpEngine requires API 34"));
            String host;
            try {
                host = URI.create(url).getHost();
            } catch (Throwable throwable) {
                return Result.error(throwable);
            }
            if(host == null || host.length() == 0)
                return Result.error(new IllegalArgumentException("Missing host: " + url));
            boolean ownsSession = sharedSession == null;
            Session session = ownsSession
                    ? newQuicSession(context, userAgent, host)
                    : sharedSession;
            if(session == null)
                return Result.error(new UnsupportedOperationException("QUIC session unavailable"));
            RequestOwner owner = new RequestOwner() {
                @Override
                public boolean register(UrlRequest request) {
                    if(!isStillAdmitted())
                        return false;
                    activeRequest.set(request);
                    if(!isStillAdmitted() && activeRequest.compareAndSet(request, null)) {
                        request.cancel();
                        return false;
                    }
                    return true;
                }

                @Override
                public void unregister(UrlRequest request) {
                    activeRequest.compareAndSet(request, null);
                }

                @Override
                public boolean isStillAdmitted() {
                    if(cancelled.get())
                        return false;
                    if(continuationCheck == null)
                        return true;
                    try {
                        return continuationCheck.getAsBoolean();
                    } catch (Throwable ignored) {
                        return false;
                    }
                }
            };
            try {
                return session.fetchExactOwned(url, userAgent, cookieHeader, requestHeaders,
                        "GET", null, timeoutMs, owner);
            } finally {
                if(ownsSession)
                    session.close();
            }
        }

        public void cancel() {
            cancelled.set(true);
            UrlRequest request = activeRequest.getAndSet(null);
            if(request != null)
                request.cancel();
        }

        public boolean isCancelled() {
            return cancelled.get();
        }
    }

    private static Result fetchWithTransport(Context context, String url, String userAgent, String cookieHeader,
                        Map<String, String> requestHeaders, String method, byte[] body, long timeoutMs,
                        boolean enableQuic, PartialTextObserver partialTextObserver) {
        if(!isAvailable())
            return Result.error(new UnsupportedOperationException("HttpEngine requires API 34"));
        try {
            String host = URI.create(url).getHost();
            if(host == null || host.length() == 0)
                return Result.error(new IllegalArgumentException("Missing host: " + url));
            HttpEngine.Builder engineBuilder = new HttpEngine.Builder(context.getApplicationContext())
                    .setEnableHttp2(true)
                    .setEnableQuic(enableQuic)
                    .setEnableBrotli(true)
                    .setUserAgent(userAgent);
            if(enableQuic) {
                engineBuilder.setQuicOptions(new QuicOptions.Builder()
                                .addAllowedQuicHost(host)
                                .setHandshakeUserAgent(userAgent)
                                .build())
                        .addQuicHint(host, 443, 443);
            }
            HttpEngine engine = engineBuilder.build();
            ExecutorService executor = Executors.newSingleThreadExecutor();
            try {
                return fetchWithEngine(engine, executor, url, userAgent, cookieHeader, requestHeaders,
                        method, body, timeoutMs);
            } finally {
                shutdownEngineAndExecutor(engine, executor);
            }
        } catch (Throwable throwable) {
            return Result.error(throwable);
        }
    }

    public static final class Session implements AutoCloseable {
        private final HttpEngine engine;
        private final ExecutorService executor;
        private final AtomicBoolean boundedDrainCloseStarted = new AtomicBoolean(false);

        private Session(Context context, String userAgent, boolean enableQuic, String host) {
            this(context, userAgent, enableQuic, host, 1);
        }

        private Session(Context context, String userAgent, boolean enableQuic, String host,
                        int callbackThreadCount) {
            this(context, userAgent, enableQuic, host, callbackThreadCount, false);
        }

        private Session(Context context, String userAgent, boolean enableQuic, String host,
                        int callbackThreadCount, boolean disableConnectionMigration) {
            if(callbackThreadCount <= 0)
                throw new IllegalArgumentException("callbackThreadCount must be positive");
            HttpEngine.Builder engineBuilder = new HttpEngine.Builder(context.getApplicationContext())
                    .setEnableHttp2(true)
                    .setEnableQuic(enableQuic)
                    .setEnableBrotli(true)
                    .setUserAgent(userAgent);
            if(enableQuic && host != null && host.length() > 0) {
                engineBuilder.setQuicOptions(new QuicOptions.Builder()
                                .addAllowedQuicHost(host)
                                .setHandshakeUserAgent(userAgent)
                                .build())
                        .addQuicHint(host, 443, 443);
            }
            if(disableConnectionMigration) {
                engineBuilder.setConnectionMigrationOptions(
                        new ConnectionMigrationOptions.Builder()
                                .setDefaultNetworkMigration(
                                        ConnectionMigrationOptions.MIGRATION_OPTION_DISABLED)
                                .setPathDegradationMigration(
                                        ConnectionMigrationOptions.MIGRATION_OPTION_DISABLED)
                                .setAllowNonDefaultNetworkUsage(
                                        ConnectionMigrationOptions.MIGRATION_OPTION_DISABLED)
                                .build());
            }
            engine = engineBuilder.build();
            executor = callbackThreadCount == 1
                    ? Executors.newSingleThreadExecutor()
                    : Executors.newFixedThreadPool(callbackThreadCount);
        }

        public Result fetch(String url, String userAgent, String cookieHeader,
                            Map<String, String> requestHeaders, String method, byte[] body,
                            long timeoutMs) {
            try {
                return fetchWithEngine(engine, executor, url, userAgent, cookieHeader, requestHeaders,
                        method, body, timeoutMs);
            } catch (Throwable throwable) {
                return Result.error(throwable);
            }
        }

        public Result fetchExactOwned(String url, String userAgent, String cookieHeader,
                                      Map<String, String> requestHeaders, String method, byte[] body,
                                      long timeoutMs, RequestOwner requestOwner) {
            try {
                return fetchWithEngineExactOwned(engine, executor, url, userAgent, cookieHeader,
                        requestHeaders, method, body, timeoutMs, requestOwner);
            } catch (Throwable throwable) {
                return Result.error(throwable);
            }
        }

        @Override
        public void close() {
            shutdownEngineAndExecutor(engine, executor);
        }

        /**
         * Closes a one-shot direct-Wi-Fi session without leaking its engine if a timed-out request's
         * terminal cancellation callback arrives after the fetch fence. Existing pooled and
         * carrier/SNI sessions retain their normal close contract and never call this method.
         */
        public boolean closeAfterBoundedRequestDrain(Runnable completion) {
            if(completion == null)
                throw new IllegalArgumentException("completion must not be null");
            if(!boundedDrainCloseStarted.compareAndSet(false, true))
                return false;
            shutdownEngineAndExecutorAfterBoundedDrain(engine, executor, 0, completion);
            return true;
        }
    }

    private static void shutdownEngineAndExecutorAfterBoundedDrain(HttpEngine engine,
                                                                    ExecutorService executor,
                                                                    int attempt,
                                                                    Runnable completion) {
        boolean shutdown = false;
        try {
            engine.shutdown();
            shutdown = true;
        } catch (Throwable ignored) {
        }
        if(shutdown || attempt + 1 >= DIRECT_WIFI_DRAIN_CLOSE_MAX_ATTEMPTS) {
            shutdownExecutorAfterGrace(executor);
            completion.run();
            return;
        }
        try {
            EXECUTOR_CLOSER.schedule(
                    () -> shutdownEngineAndExecutorAfterBoundedDrain(
                            engine, executor, attempt + 1, completion),
                    DIRECT_WIFI_DRAIN_CLOSE_RETRY_MS,
                    TimeUnit.MILLISECONDS);
        } catch (Throwable ignored) {
            shutdownExecutorAfterGrace(executor);
            completion.run();
        }
    }

    private static void shutdownEngineAndExecutor(HttpEngine engine, ExecutorService executor) {
        try {
            engine.shutdown();
        } catch (Throwable ignored) {
        }
        shutdownExecutorAfterGrace(executor);
    }

    private static void shutdownExecutorAfterGrace(ExecutorService executor) {
        EXECUTOR_CLOSER.schedule(() -> {
            executor.shutdown();
            try {
                executor.awaitTermination(2_500, TimeUnit.MILLISECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }, EXECUTOR_SHUTDOWN_GRACE_MS, TimeUnit.MILLISECONDS);
    }

    public static Result fetchWithEngine(HttpEngine engine, String url, String userAgent,
                                          String cookieHeader, Map<String, String> requestHeaders,
                                          String method, byte[] body, long timeoutMs) throws InterruptedException {
        return fetchWithEngine(engine, url, userAgent, cookieHeader, requestHeaders, method, body,
                timeoutMs, null);
    }

    public static Result fetchWithEngine(HttpEngine engine, String url, String userAgent,
                                          String cookieHeader, Map<String, String> requestHeaders,
                                          String method, byte[] body, long timeoutMs,
                                          PartialTextObserver partialTextObserver) throws InterruptedException {
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            return fetchWithEngine(engine, executor, url, userAgent, cookieHeader, requestHeaders,
                    method, body, timeoutMs, partialTextObserver);
        } finally {
            shutdownExecutorAfterGrace(executor);
        }
    }

    public static Result fetchWithEngine(HttpEngine engine, ExecutorService executor, String url, String userAgent,
                                          String cookieHeader, Map<String, String> requestHeaders,
                                          String method, byte[] body, long timeoutMs) throws InterruptedException {
        return fetchWithEngine(engine, executor, url, userAgent, cookieHeader, requestHeaders,
                method, body, timeoutMs, null);
    }

    public static Result fetchWithEngine(HttpEngine engine, ExecutorService executor, String url, String userAgent,
                                          String cookieHeader, Map<String, String> requestHeaders,
                                          String method, byte[] body, long timeoutMs,
                                          PartialTextObserver partialTextObserver) throws InterruptedException {
        return fetchWithEngine(engine, executor, url, userAgent, cookieHeader, requestHeaders,
                method, body, timeoutMs, partialTextObserver, null);
    }

    public static Result fetchWithEngine(HttpEngine engine, ExecutorService executor, String url, String userAgent,
                                          String cookieHeader, Map<String, String> requestHeaders,
                                          String method, byte[] body, long timeoutMs,
                                          PartialTextObserver partialTextObserver,
                                          PartialBytesObserver partialBytesObserver) throws InterruptedException {
        return fetchWithEngineInternal(engine, executor, url, userAgent, cookieHeader, requestHeaders,
                method, body, timeoutMs, partialTextObserver, partialBytesObserver, null, null, null,
                null, true, null);
    }

    /**
     * Executes one exact, non-redirecting request on an existing shared HttpEngine session.
     * This is the strict viewer transport: no retry, hedge, redirect, or detached request is
     * permitted, and the complete response is consumed before returning.
     */
    public static Result fetchWithEngineExactOwned(
            HttpEngine engine,
            ExecutorService executor,
            String url,
            String userAgent,
            String cookieHeader,
            Map<String, String> requestHeaders,
            String method,
            byte[] body,
            long timeoutMs,
            RequestOwner requestOwner
    ) throws InterruptedException {
        return fetchWithEngineExactOwned(engine, executor, url, userAgent, cookieHeader,
                requestHeaders, method, body, timeoutMs, requestOwner, null);
    }

    public static Result fetchWithEngineExactOwned(
            HttpEngine engine,
            ExecutorService executor,
            String url,
            String userAgent,
            String cookieHeader,
            Map<String, String> requestHeaders,
            String method,
            byte[] body,
            long timeoutMs,
            RequestOwner requestOwner,
            ExactResponseObserver exactResponseObserver
    ) throws InterruptedException {
        if(requestOwner == null)
            throw new IllegalArgumentException("Exact HttpEngine request owner is null");
        return fetchWithEngineInternal(engine, executor, url, userAgent, cookieHeader,
                requestHeaders, method, body, timeoutMs, null, null, null,
                null, null, requestOwner, false, exactResponseObserver);
    }

    public static Result fetchWithEngineUntilText(HttpEngine engine, ExecutorService executor, String url, String userAgent,
                                                  String cookieHeader, Map<String, String> requestHeaders,
                                                  String method, byte[] body, long timeoutMs,
                                                  EarlyTextObserver earlyTextObserver) throws InterruptedException {
        return fetchWithEngineInternal(engine, executor, url, userAgent, cookieHeader, requestHeaders,
                method, body, timeoutMs, null, null, earlyTextObserver, null, null, null, true, null);
    }

    public static Result fetchWithEngineObserve(HttpEngine engine, ExecutorService executor, String url, String userAgent,
                                                String cookieHeader, Map<String, String> requestHeaders,
                                                String method, byte[] body, long timeoutMs,
                                                EarlyTextObserver earlyTextObserver,
                                                ResponseStartedObserver responseStartedObserver) throws InterruptedException {
        return fetchWithEngineInternal(engine, executor, url, userAgent, cookieHeader, requestHeaders,
                method, body, timeoutMs, null, null, earlyTextObserver, responseStartedObserver,
                null, null, true, null);
    }

    public static Result fetchWithEngineUntilResponseStarted(HttpEngine engine, ExecutorService executor,
                                                             String url, String userAgent,
                                                             String cookieHeader,
                                                             Map<String, String> requestHeaders,
                                                             String method, byte[] body,
                                                             long timeoutMs,
                                                             EarlyResponseStartedObserver earlyResponseStartedObserver)
            throws InterruptedException {
        return fetchWithEngineInternal(engine, executor, url, userAgent, cookieHeader, requestHeaders,
                method, body, timeoutMs, null, null, null, null, earlyResponseStartedObserver,
                null, true, null);
    }

    private static Result fetchWithEngineInternal(HttpEngine engine, ExecutorService executor, String url, String userAgent,
                                                  String cookieHeader, Map<String, String> requestHeaders,
                                                  String method, byte[] body, long timeoutMs,
                                                  PartialTextObserver partialTextObserver,
                                                  PartialBytesObserver partialBytesObserver,
                                                  EarlyTextObserver earlyTextObserver,
                                                  ResponseStartedObserver responseStartedObserver,
                                                  EarlyResponseStartedObserver earlyResponseStartedObserver,
                                                  RequestOwner requestOwner,
                                                  boolean followRedirects,
                                                  ExactResponseObserver exactResponseObserver)
            throws InterruptedException {
        final boolean exactIdentityRequest =
                "1".equalsIgnoreCase(headerValue(
                        requestHeaders, "X-MangaViewer-Exact-Identity"));
        CountDownLatch done = new CountDownLatch(1);
        State state = new State();
        UrlRequest.Builder builder = engine.newUrlRequestBuilder(url, executor, new UrlRequest.Callback() {
                boolean notifyPartialText;
                boolean notifyPartialBytes;
                boolean notifyExactPrefix = exactResponseObserver != null;
                final int exactPrefixObservationBytes = exactResponseObserver == null
                        ? 112 * 1024
                        : Math.max(4 * 1024, exactResponseObserver.initialBodyPrefixBytes());
                int nextExactPrefixObservation = exactPrefixObservationBytes;
                int lastExactPrefixObservation = 0;
                ByteBuffer readBuffer;
                final AtomicBoolean readBufferReleased = new AtomicBoolean(false);

                private void releaseReadBuffer() {
                    if(readBufferReleased.compareAndSet(false, true)) {
                        releaseDirectReadBuffer(readBuffer);
                        readBuffer = null;
                    }
                }

                private boolean failExactObserver(UrlRequest request, Throwable failure) {
                    state.error = failure;
                    state.terminalKind = TerminalKind.FAILED;
                    releaseReadBuffer();
                    done.countDown();
                    request.cancel();
                    return false;
                }

                private boolean notifyExactResponseStarted(UrlRequest request) {
                    if(exactResponseObserver == null)
                        return true;
                    try {
                        exactResponseObserver.onResponseStarted(state.code, state.headers);
                        return true;
                    } catch (Throwable failure) {
                        return failExactObserver(request, failure);
                    }
                }

                private boolean notifyExactBodyPrefix(UrlRequest request, boolean terminal) {
                    if(!notifyExactPrefix || exactResponseObserver == null)
                        return true;
                    int available = state.responseSize();
                    if(!terminal && available < nextExactPrefixObservation)
                        return true;
                    if(available == lastExactPrefixObservation)
                        return true;
                    try {
                        boolean complete = exactResponseObserver.onBodyPrefix(
                                state.responseBackingArray(), available);
                        lastExactPrefixObservation = available;
                        if(complete) {
                            notifyExactPrefix = false;
                        } else {
                            nextExactPrefixObservation += exactPrefixObservationBytes;
                        }
                        return true;
                    } catch (Throwable failure) {
                        return failExactObserver(request, failure);
                    }
                }

                private boolean cancelIfOwnerRetired(UrlRequest request) {
                    if(requestOwner == null || requestOwner.isStillAdmitted())
                        return false;
                    state.error = new InterruptedException("Exact request owner retired");
                    state.terminalKind = TerminalKind.CANCELED;
                    releaseReadBuffer();
                    done.countDown();
                    request.cancel();
                    return true;
                }

                @Override
                public void onRedirectReceived(UrlRequest request, UrlResponseInfo info, String newLocationUrl) {
                    if(cancelIfOwnerRetired(request))
                        return;
                    if(!followRedirects) {
                        state.error = new ProtocolException(
                                "Exact HttpEngine request redirected to " + newLocationUrl);
                        state.terminalKind = TerminalKind.FAILED;
                        done.countDown();
                        request.cancel();
                        return;
                    }
                    request.followRedirect();
                }

                @Override
                public void onResponseStarted(UrlRequest request, UrlResponseInfo info) {
                    if(cancelIfOwnerRetired(request))
                        return;
                    state.code = info.getHttpStatusCode();
                    state.headers = new HashMap<>(info.getHeaders().getAsMap());
                    state.updateExactIdentityResponseInvariant();
                    state.prepareResponseCapacity(
                            exactIdentityRequest
                                    ? (int) MAX_EXACT_IDENTITY_IMAGE_BYTES
                                    : exactResponseObserver != null ? 1024 * 1024 : 0);
                    state.negotiatedProtocol = info.getNegotiatedProtocol();
                    if(!notifyExactResponseStarted(request))
                        return;
                    if(responseStartedObserver != null) {
                        try {
                            responseStartedObserver.onResponseStarted(state.code, state.headers);
                        } catch (Exception ignored) {
                        }
                    }
                    if(earlyResponseStartedObserver != null) {
                        try {
                            if(earlyResponseStartedObserver.onResponseStarted(state.code, state.headers)) {
                                state.bodyBytes = new byte[0];
                                state.completedEarly = true;
                                state.terminalKind = TerminalKind.EARLY;
                                done.countDown();
                                request.cancel();
                                return;
                            }
                        } catch (Exception ignored) {
                        }
                    }
                    notifyPartialText = (partialTextObserver != null || earlyTextObserver != null)
                            && Result.shouldDecodeBodyAsText(state.headers);
                    notifyPartialBytes = partialBytesObserver != null
                            && !Result.shouldDecodeBodyAsText(state.headers);
                    // The viewer token lives near the final RSC chunk (roughly 104 KiB in the
                    // measured 118 KiB documents, with the complete request seed ending around
                    // 113.5 KiB). Small cumulative reads could not expose a usable seed earlier
                    // but amplified a reused H3 response into 4-15 executor callbacks. A 112-KiB
                    // first read contains the measured seed and leaves only one short terminal
                    // read, preserving prefix overlap and exact EOF validation with two callbacks.
                    int bufferBytes = exactResponseObserver != null
                            ? exactPrefixObservationBytes
                            : partialTextObserver != null
                            ? 256
                            : (notifyPartialText || notifyPartialBytes ? 1024 : 128 * 1024);
                    try {
                        readBuffer = acquireDirectReadBuffer(bufferBytes);
                        request.read(readBuffer);
                    } catch (Throwable failure) {
                        state.error = failure;
                        state.terminalKind = TerminalKind.FAILED;
                        releaseReadBuffer();
                        done.countDown();
                        request.cancel();
                    }
                }

                @Override
                public void onReadCompleted(UrlRequest request, UrlResponseInfo info, ByteBuffer byteBuffer) {
                    if(cancelIfOwnerRetired(request))
                        return;
                    if(state.completedEarly)
                        return;
                    byteBuffer.flip();
                    state.appendResponse(byteBuffer);
                    if(!notifyExactBodyPrefix(request, false))
                        return;
                    if(notifyPartialText) {
                        try {
                            String text = state.responseText();
                            if(partialTextObserver != null)
                                partialTextObserver.onPartialText(text);
                            if(earlyTextObserver != null
                                    && earlyTextObserver.onPartialText(state.code, state.headers, text)) {
                                state.bodyBytes = state.responseSnapshot();
                                state.completedEarly = true;
                                state.terminalKind = TerminalKind.EARLY;
                                releaseReadBuffer();
                                done.countDown();
                                request.cancel();
                                return;
                            }
                        } catch (Exception ignored) {
                        }
                    }
                    if(notifyPartialBytes) {
                        try {
                            byte[] partial = state.responseSnapshot();
                            if(partialBytesObserver.onPartialBytes(state.code, state.headers, partial)) {
                                state.bodyBytes = partial;
                                state.completedEarly = true;
                                state.terminalKind = TerminalKind.EARLY;
                                releaseReadBuffer();
                                done.countDown();
                                request.cancel();
                                return;
                            }
                        } catch (Exception ignored) {
                        }
                    }
                    byteBuffer.clear();
                    request.read(byteBuffer);
                }

                @Override
                public void onSucceeded(UrlRequest request, UrlResponseInfo info) {
                    if(cancelIfOwnerRetired(request))
                        return;
                    state.code = info.getHttpStatusCode();
                    state.headers = new HashMap<>(info.getHeaders().getAsMap());
                    state.updateExactIdentityResponseInvariant();
                    state.negotiatedProtocol = info.getNegotiatedProtocol();
                    if(!notifyExactBodyPrefix(request, true))
                        return;
                    state.bodyBytes = state.takeResponseBytes();
                    state.terminalKind = TerminalKind.SUCCEEDED;
                    releaseReadBuffer();
                    done.countDown();
                }

                @Override
                public void onFailed(UrlRequest request, UrlResponseInfo info, HttpException error) {
                    state.error = error;
                    state.terminalKind = TerminalKind.FAILED;
                    if(info != null) {
                        try {
                            state.code = info.getHttpStatusCode();
                            state.headers = new HashMap<>(info.getHeaders().getAsMap());
                            state.updateExactIdentityResponseInvariant();
                            state.negotiatedProtocol = info.getNegotiatedProtocol();
                        } catch (Exception ignored) {
                        }
                    }
                    releaseReadBuffer();
                    done.countDown();
                }

                @Override
                public void onCanceled(UrlRequest request, UrlResponseInfo info) {
                    if(state.completedEarly)
                        return;
                    if(state.error == null)
                        state.error = new InterruptedException("cancelled");
                    state.terminalKind = TerminalKind.CANCELED;
                    releaseReadBuffer();
                    done.countDown();
                }
        });
        String normalizedMethod = method == null || method.length() == 0
                ? "GET" : method.toUpperCase(Locale.ROOT);
        if(!"GET".equals(normalizedMethod))
            builder.setHttpMethod(normalizedMethod);
        if(body != null && body.length > 0)
            builder.setUploadDataProvider(new ByteArrayUploadDataProvider(body), executor);
        addForwardedHeaders(builder, requestHeaders);
        // HttpEngine owns Accept-Encoding and explicitly ignores application overrides. The
        // direct-Wi-Fi exact marker remains internal; response Content-Encoding is still checked
        // before a timed-out prefix can be reused.
        if(userAgent != null && userAgent.length() > 0)
            builder.addHeader("User-Agent", userAgent);
        if(shouldInjectSyntheticUploadContentType(requestHeaders, body))
            builder.addHeader("Content-Type", "text/plain;charset=UTF-8");
        if(!hasHeader(requestHeaders, "Accept"))
            builder.addHeader("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8");
        if(!hasHeader(requestHeaders, "Accept-Language"))
            builder.addHeader("Accept-Language", Locale.getDefault().toLanguageTag() + ",ko-KR;q=0.9,ko;q=0.8,en-US;q=0.7,en;q=0.6");
        if(cookieHeader != null && cookieHeader.length() > 0)
            builder.addHeader("Cookie", cookieHeader);
        UrlRequest request = builder.build();
        boolean registered = false;
        if(requestOwner != null) {
            registered = requestOwner.register(request);
            if(!registered) {
                request.cancel();
                return Result.error(new InterruptedException("Exact request owner retired"));
            }
        }
        try {
            request.start();
            boolean completed;
            TailProbeOutcome tailProbeOutcome = TailProbeOutcome.NOT_ATTEMPTED;
            long tailProbeBeforeBytes = -1L;
            long tailProbeAfterBytes = -1L;
            long tailProbeExpectedBytes = -1L;
            try {
                long boundedTimeoutMs = Math.max(1L, timeoutMs);
                completed = done.await(boundedTimeoutMs, TimeUnit.MILLISECONDS);
                // Focused traces proved that even a progressing tail can stall again and make
                // the unconditional grace slower than an exact Range. Stop at the hard wall;
                // the caller may preserve this terminally fenced prefix through a proven 206.
            } catch(InterruptedException e) {
                // Future.cancel(true) interrupts the waiting owner, but HttpEngine's request keeps
                // running unless it is explicitly cancelled. Letting that orphan continue retains
                // direct buffers and its callback executor across the reader hand-off.
                request.cancel();
                throw e;
            }
            if(!completed) {
                request.cancel();
                boolean terminalAfterCancel = done.await(750, TimeUnit.MILLISECONDS);
                if(!terminalAfterCancel)
                    return Result.error(
                            new java.net.SocketTimeoutException("QUIC fetch timed out"));
                // Partial response identity is meaningful only for the explicitly marked strict
                // image request. Legacy carrier/SNI metadata and ACK callers must retain their
                // original timeout contract (code=0, no headers/body), otherwise a timed-out 2xx
                // prefix can be mistaken for a successful impression or cookie-bearing response.
                if(!exactIdentityRequest)
                    return Result.error(
                            new java.net.SocketTimeoutException("QUIC fetch timed out"));
                // Preserve every byte already delivered by this exact request. Callers still see
                // a timeout through result.error; the strict Wi-Fi image path may additionally
                // prove the immutable validator and declared length, then resume only the
                // untouched suffix instead of downloading the whole body again.
                return new Result(
                        state.code,
                        state.responseSnapshot(),
                        state.headers == null ? Collections.emptyMap() : state.headers,
                        state.negotiatedProtocol,
                        new java.net.SocketTimeoutException("QUIC fetch timed out"),
                        state.terminalKind == TerminalKind.CANCELED
                                ? TerminalKind.CANCELED_BY_INTERNAL_TIMEOUT
                                : state.terminalKind,
                        exactIdentityRequest,
                        tailProbeOutcome,
                        tailProbeBeforeBytes,
                        tailProbeAfterBytes,
                        tailProbeExpectedBytes
                );
            }
            if(state.error != null)
                return Result.error(state.error);
            return new Result(state.code, state.bodyBytes == null ? new byte[0] : state.bodyBytes,
                    state.headers == null ? Collections.emptyMap() : state.headers,
                    state.negotiatedProtocol, null, state.terminalKind,
                    exactIdentityRequest || exactResponseObserver != null,
                    tailProbeOutcome, tailProbeBeforeBytes,
                    tailProbeAfterBytes, tailProbeExpectedBytes);
        } finally {
            if(registered)
                requestOwner.unregister(request);
        }
    }

    private static void addForwardedHeaders(UrlRequest.Builder builder, Map<String, String> headers) {
        if(headers == null)
            return;
        for(String key : headers.keySet()) {
            if(!shouldForwardHeader(key))
                continue;
            String value = headers.get(key);
            if(value == null || value.length() == 0)
                continue;
            try {
                builder.addHeader(key, value);
            } catch (Exception ignored) {
            }
        }
    }

    private static boolean hasHeader(Map<String, String> headers, String name) {
        if(headers == null || name == null)
            return false;
        for(String key : headers.keySet()) {
            if(name.equalsIgnoreCase(key))
                return true;
        }
        return false;
    }

    private static String headerValue(Map<String, String> headers, String name) {
        if(headers == null || name == null)
            return null;
        for(String key : headers.keySet()) {
            if(name.equalsIgnoreCase(key))
                return headers.get(key);
        }
        return null;
    }

    static boolean shouldInjectSyntheticUploadContentTypeForTest(Map<String, String> requestHeaders, byte[] body) {
        return shouldInjectSyntheticUploadContentType(requestHeaders, body);
    }

    private static boolean shouldInjectSyntheticUploadContentType(Map<String, String> requestHeaders, byte[] body) {
        return body != null && body.length > 0 && !hasHeader(requestHeaders, "Content-Type");
    }

    private static boolean shouldForwardHeader(String name) {
        if(name == null)
            return false;
        String lower = name.toLowerCase(Locale.ROOT);
        return !"user-agent".equals(lower)
                && !"cookie".equals(lower)
                && !"host".equals(lower)
                && !"connection".equals(lower)
                && !"content-length".equals(lower)
                && !"accept-encoding".equals(lower)
                && !"x-mangaviewer-exact-identity".equals(lower);
    }

    public static final class Result {
        public final int code;
        public final String body;
        public final byte[] bodyBytes;
        public final Map<String, List<String>> headers;
        public final String negotiatedProtocol;
        public final Throwable error;
        public final TerminalKind terminalKind;
        public final TailProbeOutcome tailProbeOutcome;
        public final long tailProbeBeforeBytes;
        public final long tailProbeAfterBytes;
        public final long tailProbeExpectedBytes;

        Result(int code, byte[] bodyBytes, Map<String, List<String>> headers, Throwable error) {
            this(code, bodyBytes, headers, "", error);
        }

        Result(int code, byte[] bodyBytes, Map<String, List<String>> headers,
               String negotiatedProtocol, Throwable error) {
            this(code, bodyBytes, headers, negotiatedProtocol, error, TerminalKind.UNKNOWN);
        }

        Result(int code, byte[] bodyBytes, Map<String, List<String>> headers,
               String negotiatedProtocol, Throwable error, TerminalKind terminalKind) {
            this(code, bodyBytes, headers, negotiatedProtocol, error, terminalKind,
                    false, TailProbeOutcome.NOT_ATTEMPTED, -1L, -1L, -1L);
        }

        Result(int code, byte[] bodyBytes, Map<String, List<String>> headers,
               String negotiatedProtocol, Throwable error, TerminalKind terminalKind,
               boolean exactIdentityBinaryBody, TailProbeOutcome tailProbeOutcome,
               long tailProbeBeforeBytes, long tailProbeAfterBytes,
               long tailProbeExpectedBytes) {
            this.code = code;
            this.bodyBytes = bodyBytes == null ? new byte[0] : bodyBytes;
            this.headers = headers;
            this.negotiatedProtocol = negotiatedProtocol == null ? "" : negotiatedProtocol;
            this.body = !exactIdentityBinaryBody && shouldDecodeBodyAsText(headers)
                    ? new String(this.bodyBytes, StandardCharsets.UTF_8)
                    : "";
            this.error = error;
            this.terminalKind = terminalKind == null ? TerminalKind.UNKNOWN : terminalKind;
            this.tailProbeOutcome = tailProbeOutcome == null
                    ? TailProbeOutcome.NOT_ATTEMPTED : tailProbeOutcome;
            this.tailProbeBeforeBytes = tailProbeBeforeBytes;
            this.tailProbeAfterBytes = tailProbeAfterBytes;
            this.tailProbeExpectedBytes = tailProbeExpectedBytes;
        }

        private static boolean shouldDecodeBodyAsText(Map<String, List<String>> headers) {
            if(headers == null || headers.isEmpty())
                return true;
            for(String key : headers.keySet()) {
                if(!"content-type".equalsIgnoreCase(key))
                    continue;
                List<String> values = headers.get(key);
                if(values == null || values.isEmpty())
                    return true;
                String value = values.get(0);
                if(value == null)
                    return true;
                String lower = value.toLowerCase(Locale.ROOT);
                return lower.startsWith("text/")
                        || lower.contains("json")
                        || lower.contains("javascript")
                        || lower.contains("xml")
                        || lower.contains("x-component");
            }
            return true;
        }

        public static Result error(Throwable error) {
            return new Result(0, new byte[0], Collections.emptyMap(), error);
        }

        public static Result fromBytes(int code, byte[] bodyBytes, Map<String, List<String>> headers) {
            return new Result(code, bodyBytes, headers == null ? Collections.emptyMap() : headers, null);
        }

        public boolean isUsableHtml() {
            return error == null && code >= 200 && code < 500 && bodyBytes.length > 0;
        }

        public String contentType() {
            for(String key : headers.keySet()) {
                if("content-type".equalsIgnoreCase(key)) {
                    List<String> values = headers.get(key);
                    if(values != null && values.size() > 0)
                        return values.get(0);
                }
            }
            return "text/html; charset=utf-8";
        }

        public List<String> setCookies() {
            for(String key : headers.keySet()) {
                if("set-cookie".equalsIgnoreCase(key)) {
                    List<String> values = headers.get(key);
                    return values == null ? Collections.emptyList() : values;
                }
            }
            return Collections.emptyList();
        }
    }

    private static final class ExposedByteArrayOutputStream extends ByteArrayOutputStream {
        byte[] backingArray() {
            return buf;
        }

        void ensureCapacityFor(int expectedSize) {
            if(expectedSize <= buf.length)
                return;
            int grown = Math.max(expectedSize, Math.max(32, buf.length << 1));
            if(grown < 0)
                grown = Integer.MAX_VALUE;
            buf = Arrays.copyOf(buf, grown);
        }

        void appendFrom(ByteBuffer source) {
            int bytes = source.remaining();
            if(bytes <= 0)
                return;
            int expectedSize = count + bytes;
            if(expectedSize < count)
                throw new OutOfMemoryError("Response body exceeds integer capacity");
            ensureCapacityFor(expectedSize);
            source.get(buf, count, bytes);
            count = expectedSize;
        }

        byte[] takeExactArray() {
            return count == buf.length ? buf : Arrays.copyOf(buf, count);
        }
    }

    private static final class State {
        private final ExposedByteArrayOutputStream response =
                new ExposedByteArrayOutputStream();
        private final byte[] responsePrefix = new byte[4];
        private int responsePrefixLength;
        volatile int code;
        byte[] bodyBytes;
        volatile Map<String, List<String>> headers;
        volatile String negotiatedProtocol;
        Throwable error;
        boolean completedEarly;
        volatile TerminalKind terminalKind = TerminalKind.UNKNOWN;
        private boolean exactIdentityResponseInvariant;
        private long exactIdentityExpectedLength = -1L;

        synchronized void appendResponse(ByteBuffer bytes) {
            int copyCount = Math.min(
                    bytes.remaining(),
                    responsePrefix.length - responsePrefixLength);
            if(copyCount > 0) {
                int position = bytes.position();
                for(int index = 0; index < copyCount; index++)
                    responsePrefix[responsePrefixLength + index] = bytes.get(position + index);
                responsePrefixLength += copyCount;
            }
            response.appendFrom(bytes);
        }

        synchronized byte[] responseSnapshot() {
            return response.toByteArray();
        }

        synchronized byte[] responseBackingArray() {
            return response.backingArray();
        }

        synchronized byte[] takeResponseBytes() {
            return response.takeExactArray();
        }

        synchronized void prepareResponseCapacity(int maximumBytes) {
            if(maximumBytes <= 0 || response.size() != 0 || headers == null)
                return;
            List<String> lengths = responseHeaderValues("Content-Length");
            if(lengths.size() != 1 || !isAsciiDigits(lengths.get(0).trim()))
                return;
            try {
                long expected = Long.parseLong(lengths.get(0).trim());
                if(expected > 0L && expected <= maximumBytes)
                    response.ensureCapacityFor((int) expected);
            } catch(NumberFormatException ignored) {
            }
        }

        synchronized int responseSize() {
            return response.size();
        }

        synchronized String responseText() {
            return new String(response.toByteArray(), StandardCharsets.UTF_8);
        }

        synchronized void updateExactIdentityResponseInvariant() {
            exactIdentityResponseInvariant = false;
            exactIdentityExpectedLength = -1L;
            if(code != 200 || headers == null)
                return;
            if(!responseHeaderValues("Content-Range").isEmpty()
                    || !responseHeaderValues("Transfer-Encoding").isEmpty())
                return;
            List<String> lengths = responseHeaderValues("Content-Length");
            if(lengths.size() != 1 || !isAsciiDigits(lengths.get(0).trim()))
                return;
            final long expectedLength;
            try {
                expectedLength = Long.parseLong(lengths.get(0).trim());
            } catch(NumberFormatException ignored) {
                return;
            }
            if(expectedLength <= 0L || expectedLength > MAX_EXACT_IDENTITY_IMAGE_BYTES)
                return;
            List<String> encodings = responseHeaderValues("Content-Encoding");
            if(encodings.size() > 1
                    || (encodings.size() == 1
                        && encodings.get(0) != null
                        && encodings.get(0).trim().length() > 0
                        && !"identity".equalsIgnoreCase(encodings.get(0).trim())))
                return;
            exactIdentityExpectedLength = expectedLength;
            exactIdentityResponseInvariant = true;
        }

        synchronized long exactIdentityTailCandidateReceivedBytes() {
            long receivedBytes = response.size();
            if(!exactIdentityResponseInvariant
                    || !responseLooksLikeImage()
                    || receivedBytes <= 0L
                    || exactIdentityExpectedLength <= receivedBytes
                    || exactIdentityExpectedLength - receivedBytes
                        > EXACT_IDENTITY_TAIL_GRACE_BYTES)
                return -1L;
            return receivedBytes;
        }

        synchronized long exactIdentityReceivedBytes() {
            return response.size();
        }

        synchronized long exactIdentityExpectedLength() {
            return exactIdentityExpectedLength;
        }


        private boolean isAsciiDigits(String value) {
            if(value == null || value.length() == 0)
                return false;
            for(int index = 0; index < value.length(); index++) {
                char character = value.charAt(index);
                if(character < '0' || character > '9')
                    return false;
            }
            return true;
        }

        private List<String> responseHeaderValues(String name) {
            List<String> values = new java.util.ArrayList<>();
            for(Map.Entry<String, List<String>> entry : headers.entrySet()) {
                if(!name.equalsIgnoreCase(entry.getKey()) || entry.getValue() == null)
                    continue;
                values.addAll(entry.getValue());
            }
            return values;
        }

        private boolean responseLooksLikeImage() {
            if(responsePrefixLength < 4)
                return false;
            int b0 = responsePrefix[0] & 0xff;
            int b1 = responsePrefix[1] & 0xff;
            int b2 = responsePrefix[2] & 0xff;
            int b3 = responsePrefix[3] & 0xff;
            return (b0 == 0xff && b1 == 0xd8)
                    || (b0 == 0x89 && b1 == 0x50 && b2 == 0x4e && b3 == 0x47)
                    || (b0 == 0x52 && b1 == 0x49 && b2 == 0x46 && b3 == 0x46)
                    || (b0 == 0x47 && b1 == 0x49 && b2 == 0x46 && b3 == 0x38);
        }
    }

    private static final class ByteArrayUploadDataProvider extends UploadDataProvider {
        private final byte[] bytes;
        private int offset;

        ByteArrayUploadDataProvider(byte[] bytes) {
            this.bytes = bytes == null ? new byte[0] : bytes;
        }

        @Override
        public long getLength() {
            return bytes.length;
        }

        @Override
        public void read(UploadDataSink uploadDataSink, ByteBuffer byteBuffer) {
            int count = Math.min(byteBuffer.remaining(), bytes.length - offset);
            if(count > 0) {
                byteBuffer.put(bytes, offset, count);
                offset += count;
            }
            uploadDataSink.onReadSucceeded(false);
        }

        @Override
        public void rewind(UploadDataSink uploadDataSink) {
            offset = 0;
            uploadDataSink.onRewindSucceeded();
        }
    }
}
