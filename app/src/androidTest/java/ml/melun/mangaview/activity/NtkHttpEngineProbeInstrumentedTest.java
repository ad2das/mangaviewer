package ml.melun.mangaview.activity;

import static org.junit.Assert.assertTrue;

import android.content.Context;
import android.net.http.HttpEngine;
import android.net.http.QuicOptions;
import android.net.http.UrlRequest;
import android.net.http.UrlResponseInfo;
import android.os.Build;

import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;

import org.junit.Assume;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import ml.melun.mangaview.LiveNetworkAssume;
import ml.melun.mangaview.mangaview.CustomHttpClient;

@RunWith(AndroidJUnit4.class)
public class NtkHttpEngineProbeInstrumentedTest {
    @Before
    public void requireLiveNetworkOptIn() {
        LiveNetworkAssume.assumeEnabled();
        Assume.assumeTrue(Build.VERSION.SDK_INT >= 34);
    }

    @Test
    public void platformHttpEngineFetchesCurrentNtkRootOverQuic() throws Exception {
        Context context = ApplicationProvider.getApplicationContext();
        String ntkRoot = CustomHttpClient.NTK_WEBTOON_URL;
        String ntkHost = java.net.URI.create(ntkRoot).getHost();
        String userAgent = CaptchaActivity.captchaUserAgentForTest(
                "Mozilla/5.0 (Linux; Android 15; sdk_gphone64_x86_64 Build/AE3A.240806.036; wv) AppleWebKit/537.36 (KHTML, like Gecko) Version/4.0 Chrome/124.0.6367.219 Mobile Safari/537.36");
        HttpEngine engine = new HttpEngine.Builder(context)
                .setEnableHttp2(true)
                .setEnableQuic(true)
                .setUserAgent(userAgent)
                .setQuicOptions(new QuicOptions.Builder()
                        .addAllowedQuicHost(ntkHost)
                        .setHandshakeUserAgent(userAgent)
                        .build())
                .addQuicHint(ntkHost, 443, 443)
                .build();
        Executor executor = Executors.newSingleThreadExecutor();
        CountDownLatch done = new CountDownLatch(1);
        AtomicInteger code = new AtomicInteger(0);
        AtomicReference<String> body = new AtomicReference<>("");
        AtomicReference<Throwable> error = new AtomicReference<>();

        UrlRequest request = engine.newUrlRequestBuilder(ntkRoot + "/", executor, new UrlRequest.Callback() {
            final StringBuilder response = new StringBuilder();

            @Override
            public void onRedirectReceived(UrlRequest request, UrlResponseInfo info, String newLocationUrl) {
                request.followRedirect();
            }

            @Override
            public void onResponseStarted(UrlRequest request, UrlResponseInfo info) {
                code.set(info.getHttpStatusCode());
                request.read(ByteBuffer.allocateDirect(32 * 1024));
            }

            @Override
            public void onReadCompleted(UrlRequest request, UrlResponseInfo info, ByteBuffer byteBuffer) {
                byteBuffer.flip();
                byte[] bytes = new byte[byteBuffer.remaining()];
                byteBuffer.get(bytes);
                response.append(new String(bytes, StandardCharsets.UTF_8));
                byteBuffer.clear();
                request.read(byteBuffer);
            }

            @Override
            public void onSucceeded(UrlRequest request, UrlResponseInfo info) {
                code.set(info.getHttpStatusCode());
                body.set(response.toString());
                done.countDown();
            }

            @Override
            public void onFailed(UrlRequest request, UrlResponseInfo info, android.net.http.HttpException e) {
                error.set(e);
                done.countDown();
            }

            @Override
            public void onCanceled(UrlRequest request, UrlResponseInfo info) {
                done.countDown();
            }
        })
                .addHeader("User-Agent", userAgent)
                .build();
        request.start();

        assertTrue("HttpEngine request timed out", done.await(30, TimeUnit.SECONDS));
        assertTrue("HttpEngine failed: " + error.get(), error.get() == null);
        assertTrue("Expected HTTP response, code=" + code.get(), code.get() > 0);
        String lower = body.get().toLowerCase(java.util.Locale.ROOT);
        assertTrue("Expected challenge or normal NTK HTML, code=" + code.get() + ", body=" + body.get(),
                lower.contains("cloudflare")
                        || lower.contains("turnstile")
                        || lower.contains("verify you are human")
                        || lower.contains("newtoki")
                        || lower.contains("/manhwa")
                        || lower.contains("/webtoon"));
    }
}
