package ml.melun.mangaview.activity;

import android.annotation.TargetApi;
import android.content.Context;
import android.net.http.HttpEngine;
import android.net.http.HttpException;
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
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

@TargetApi(34)
public final class NtkQuicFetcher {
    private static volatile Boolean runtimeAvailable;
    private static final long EXECUTOR_SHUTDOWN_GRACE_MS = 5_000L;
    private static final ScheduledExecutorService EXECUTOR_CLOSER =
            Executors.newSingleThreadScheduledExecutor(runnable -> {
                Thread thread = new Thread(runnable, "ntk-quic-executor-closer");
                thread.setDaemon(true);
                return thread;
            });

    private NtkQuicFetcher() {
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
     * Linearizes an HttpEngine request with the foreground viewer generation that owns it.
     * Registration happens before {@link UrlRequest#start()}, so a retired owner can reject the
     * request without putting any bytes on the wire. Implementations must make cancel idempotent.
     */
    public interface RequestOwner {
        boolean register(UrlRequest request);
        void unregister(UrlRequest request);
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
        if(!isAvailable())
            return null;
        try {
            return new Session(context, userAgent, true, host);
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

        public Result fetch(Context context, String url, String userAgent, String cookieHeader,
                            Map<String, String> requestHeaders, long timeoutMs) {
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
            Session session = newQuicSession(context, userAgent, host);
            if(session == null)
                return Result.error(new UnsupportedOperationException("QUIC session unavailable"));
            RequestOwner owner = new RequestOwner() {
                @Override
                public boolean register(UrlRequest request) {
                    if(cancelled.get())
                        return false;
                    activeRequest.set(request);
                    if(cancelled.get() && activeRequest.compareAndSet(request, null)) {
                        request.cancel();
                        return false;
                    }
                    return true;
                }

                @Override
                public void unregister(UrlRequest request) {
                    activeRequest.compareAndSet(request, null);
                }
            };
            try {
                return session.fetchExactOwned(url, userAgent, cookieHeader, requestHeaders,
                        "GET", null, timeoutMs, owner);
            } finally {
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

        private Session(Context context, String userAgent, boolean enableQuic, String host) {
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
            engine = engineBuilder.build();
            executor = Executors.newSingleThreadExecutor();
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
                null, true);
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
        if(requestOwner == null)
            throw new IllegalArgumentException("Exact HttpEngine request owner is null");
        return fetchWithEngineInternal(engine, executor, url, userAgent, cookieHeader,
                requestHeaders, method, body, timeoutMs, null, null, null,
                null, null, requestOwner, false);
    }

    public static Result fetchWithEngineUntilText(HttpEngine engine, ExecutorService executor, String url, String userAgent,
                                                  String cookieHeader, Map<String, String> requestHeaders,
                                                  String method, byte[] body, long timeoutMs,
                                                  EarlyTextObserver earlyTextObserver) throws InterruptedException {
        return fetchWithEngineInternal(engine, executor, url, userAgent, cookieHeader, requestHeaders,
                method, body, timeoutMs, null, null, earlyTextObserver, null, null, null, true);
    }

    public static Result fetchWithEngineObserve(HttpEngine engine, ExecutorService executor, String url, String userAgent,
                                                String cookieHeader, Map<String, String> requestHeaders,
                                                String method, byte[] body, long timeoutMs,
                                                EarlyTextObserver earlyTextObserver,
                                                ResponseStartedObserver responseStartedObserver) throws InterruptedException {
        return fetchWithEngineInternal(engine, executor, url, userAgent, cookieHeader, requestHeaders,
                method, body, timeoutMs, null, null, earlyTextObserver, responseStartedObserver,
                null, null, true);
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
                null, true);
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
                                                  boolean followRedirects) throws InterruptedException {
        CountDownLatch done = new CountDownLatch(1);
        State state = new State();
        UrlRequest.Builder builder = engine.newUrlRequestBuilder(url, executor, new UrlRequest.Callback() {
                final ByteArrayOutputStream response = new ByteArrayOutputStream();
                boolean notifyPartialText;
                boolean notifyPartialBytes;

                @Override
                public void onRedirectReceived(UrlRequest request, UrlResponseInfo info, String newLocationUrl) {
                    if(!followRedirects) {
                        state.error = new ProtocolException(
                                "Exact HttpEngine request redirected to " + newLocationUrl);
                        done.countDown();
                        request.cancel();
                        return;
                    }
                    request.followRedirect();
                }

                @Override
                public void onResponseStarted(UrlRequest request, UrlResponseInfo info) {
                    state.code = info.getHttpStatusCode();
                    state.headers = new HashMap<>(info.getHeaders().getAsMap());
                    state.negotiatedProtocol = info.getNegotiatedProtocol();
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
                    int bufferBytes = partialTextObserver != null
                            ? 256
                            : (notifyPartialText || notifyPartialBytes ? 1024 : 128 * 1024);
                    request.read(ByteBuffer.allocateDirect(bufferBytes));
                }

                @Override
                public void onReadCompleted(UrlRequest request, UrlResponseInfo info, ByteBuffer byteBuffer) {
                    if(state.completedEarly)
                        return;
                    byteBuffer.flip();
                    byte[] bytes = new byte[byteBuffer.remaining()];
                    byteBuffer.get(bytes);
                    response.write(bytes, 0, bytes.length);
                    if(notifyPartialText) {
                        try {
                            String text = response.toString(StandardCharsets.UTF_8.name());
                            if(partialTextObserver != null)
                                partialTextObserver.onPartialText(text);
                            if(earlyTextObserver != null
                                    && earlyTextObserver.onPartialText(state.code, state.headers, text)) {
                                state.bodyBytes = response.toByteArray();
                                state.completedEarly = true;
                                done.countDown();
                                request.cancel();
                                return;
                            }
                        } catch (Exception ignored) {
                        }
                    }
                    if(notifyPartialBytes) {
                        try {
                            byte[] partial = response.toByteArray();
                            if(partialBytesObserver.onPartialBytes(state.code, state.headers, partial)) {
                                state.bodyBytes = partial;
                                state.completedEarly = true;
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
                    state.code = info.getHttpStatusCode();
                    state.headers = new HashMap<>(info.getHeaders().getAsMap());
                    state.negotiatedProtocol = info.getNegotiatedProtocol();
                    state.bodyBytes = response.toByteArray();
                    done.countDown();
                }

                @Override
                public void onFailed(UrlRequest request, UrlResponseInfo info, HttpException error) {
                    state.error = error;
                    if(info != null) {
                        try {
                            state.code = info.getHttpStatusCode();
                            state.headers = new HashMap<>(info.getHeaders().getAsMap());
                            state.negotiatedProtocol = info.getNegotiatedProtocol();
                        } catch (Exception ignored) {
                        }
                    }
                    done.countDown();
                }

                @Override
                public void onCanceled(UrlRequest request, UrlResponseInfo info) {
                    if(state.completedEarly)
                        return;
                    if(state.error == null)
                        state.error = new InterruptedException("cancelled");
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
            final boolean completed;
            try {
                completed = done.await(Math.max(1L, timeoutMs), TimeUnit.MILLISECONDS);
            } catch(InterruptedException e) {
                // Future.cancel(true) interrupts the waiting owner, but HttpEngine's request keeps
                // running unless it is explicitly cancelled. Letting that orphan continue retains
                // direct buffers and its callback executor across the reader hand-off.
                request.cancel();
                throw e;
            }
            if(!completed) {
                request.cancel();
                done.await(750, TimeUnit.MILLISECONDS);
                return Result.error(new java.net.SocketTimeoutException("QUIC fetch timed out"));
            }
            if(state.error != null)
                return Result.error(state.error);
            return new Result(state.code, state.bodyBytes == null ? new byte[0] : state.bodyBytes,
                    state.headers == null ? Collections.emptyMap() : state.headers,
                    state.negotiatedProtocol, null);
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
                && !"accept-encoding".equals(lower);
    }

    public static final class Result {
        public final int code;
        public final String body;
        public final byte[] bodyBytes;
        public final Map<String, List<String>> headers;
        public final String negotiatedProtocol;
        public final Throwable error;

        Result(int code, byte[] bodyBytes, Map<String, List<String>> headers, Throwable error) {
            this(code, bodyBytes, headers, "", error);
        }

        Result(int code, byte[] bodyBytes, Map<String, List<String>> headers,
               String negotiatedProtocol, Throwable error) {
            this.code = code;
            this.bodyBytes = bodyBytes == null ? new byte[0] : bodyBytes;
            this.headers = headers;
            this.negotiatedProtocol = negotiatedProtocol == null ? "" : negotiatedProtocol;
            this.body = shouldDecodeBodyAsText(headers)
                    ? new String(this.bodyBytes, StandardCharsets.UTF_8)
                    : "";
            this.error = error;
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

    private static final class State {
        int code;
        byte[] bodyBytes;
        Map<String, List<String>> headers;
        String negotiatedProtocol;
        Throwable error;
        boolean completedEarly;
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
