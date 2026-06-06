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
import java.net.URI;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

@TargetApi(34)
public final class NtkQuicFetcher {
    private NtkQuicFetcher() {
    }

    public static boolean isAvailable() {
        return Build.VERSION.SDK_INT >= 34;
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
        if(!isAvailable())
            return Result.error(new UnsupportedOperationException("HttpEngine requires API 34"));
        try {
            String host = URI.create(url).getHost();
            if(host == null || host.length() == 0)
                return Result.error(new IllegalArgumentException("Missing host: " + url));
            HttpEngine engine = new HttpEngine.Builder(context.getApplicationContext())
                    .setEnableHttp2(true)
                    .setEnableQuic(true)
                    .setEnableBrotli(true)
                    .setUserAgent(userAgent)
                    .setQuicOptions(new QuicOptions.Builder()
                            .addAllowedQuicHost(host)
                            .setHandshakeUserAgent(userAgent)
                            .build())
                    .addQuicHint(host, 443, 443)
                    .build();
            try {
                return fetchWithEngine(engine, url, userAgent, cookieHeader, requestHeaders,
                        method, body, timeoutMs);
            } finally {
                engine.shutdown();
            }
        } catch (Throwable throwable) {
            return Result.error(throwable);
        }
    }

    public static Result fetchWithEngine(HttpEngine engine, String url, String userAgent,
                                          String cookieHeader, Map<String, String> requestHeaders,
                                          String method, byte[] body, long timeoutMs) throws InterruptedException {
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            return fetchWithEngine(engine, executor, url, userAgent, cookieHeader, requestHeaders,
                    method, body, timeoutMs);
        } finally {
            executor.shutdown();
            executor.awaitTermination(2_500, TimeUnit.MILLISECONDS);
        }
    }

    public static Result fetchWithEngine(HttpEngine engine, ExecutorService executor, String url, String userAgent,
                                          String cookieHeader, Map<String, String> requestHeaders,
                                          String method, byte[] body, long timeoutMs) throws InterruptedException {
        CountDownLatch done = new CountDownLatch(1);
        State state = new State();
        UrlRequest.Builder builder = engine.newUrlRequestBuilder(url, executor, new UrlRequest.Callback() {
                final ByteArrayOutputStream response = new ByteArrayOutputStream();

                @Override
                public void onRedirectReceived(UrlRequest request, UrlResponseInfo info, String newLocationUrl) {
                    request.followRedirect();
                }

                @Override
                public void onResponseStarted(UrlRequest request, UrlResponseInfo info) {
                    state.code = info.getHttpStatusCode();
                    state.headers = info.getHeaders().getAsMap();
                    request.read(ByteBuffer.allocateDirect(32 * 1024));
                }

                @Override
                public void onReadCompleted(UrlRequest request, UrlResponseInfo info, ByteBuffer byteBuffer) {
                    byteBuffer.flip();
                    byte[] bytes = new byte[byteBuffer.remaining()];
                    byteBuffer.get(bytes);
                    response.write(bytes, 0, bytes.length);
                    byteBuffer.clear();
                    request.read(byteBuffer);
                }

                @Override
                public void onSucceeded(UrlRequest request, UrlResponseInfo info) {
                    state.code = info.getHttpStatusCode();
                    state.headers = info.getHeaders().getAsMap();
                    state.bodyBytes = response.toByteArray();
                    done.countDown();
                }

                @Override
                public void onFailed(UrlRequest request, UrlResponseInfo info, HttpException error) {
                    state.error = error;
                    if(info != null) {
                        try {
                            state.code = info.getHttpStatusCode();
                            state.headers = info.getHeaders().getAsMap();
                        } catch (Exception ignored) {
                        }
                    }
                    done.countDown();
                }

                @Override
                public void onCanceled(UrlRequest request, UrlResponseInfo info) {
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
        request.start();
        if(!done.await(Math.max(1L, timeoutMs), TimeUnit.MILLISECONDS)) {
            request.cancel();
            done.await(750, TimeUnit.MILLISECONDS);
            return Result.error(new java.net.SocketTimeoutException("QUIC fetch timed out"));
        }
        if(state.error != null)
            return Result.error(state.error);
        return new Result(state.code, state.bodyBytes == null ? new byte[0] : state.bodyBytes,
                state.headers == null ? Collections.emptyMap() : state.headers, null);
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
        public final Throwable error;

        Result(int code, byte[] bodyBytes, Map<String, List<String>> headers, Throwable error) {
            this.code = code;
            this.bodyBytes = bodyBytes == null ? new byte[0] : bodyBytes;
            this.body = new String(this.bodyBytes, StandardCharsets.UTF_8);
            this.headers = headers;
            this.error = error;
        }

        static Result error(Throwable error) {
            return new Result(0, new byte[0], Collections.emptyMap(), error);
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
        Throwable error;
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
