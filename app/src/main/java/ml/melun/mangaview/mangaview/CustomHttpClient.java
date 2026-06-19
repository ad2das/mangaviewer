package ml.melun.mangaview.mangaview;


import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.content.SharedPreferences;
import android.net.Uri;
import android.net.http.HttpEngine;
import android.net.http.QuicOptions;
import android.util.Base64;
import android.util.Log;
import android.webkit.CookieManager;
import android.webkit.WebStorage;
import android.webkit.WebViewDatabase;

import com.google.gson.Gson;
import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InterruptedIOException;
import java.io.OutputStream;
import java.net.Inet4Address;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.URI;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.SocketAddress;
import java.net.SocketException;
import java.net.URL;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.net.UnknownHostException;
import java.nio.channels.SocketChannel;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorCompletionService;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.FutureTask;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLException;
import javax.net.ssl.SSLSocketFactory;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;

import okhttp3.Call;
import okhttp3.ConnectionPool;
import okhttp3.ConnectionSpec;
import okhttp3.Dispatcher;
import okhttp3.Dns;
import okhttp3.Headers;
import okhttp3.HttpUrl;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Protocol;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import okhttp3.ResponseBody;

import ml.melun.mangaview.glide.ViewerWarmupManager;
import ml.melun.mangaview.activity.NtkQuicFetcher;
import ml.melun.mangaview.MainApplication;
import ml.melun.mangaview.NtkDeviceIdentityManager;
import ml.melun.mangaview.reader.ReaderImageCache;
import ml.melun.mangaview.repository.CacheFileStore;
import ml.melun.mangaview.runtime.PerfTrace;

import static ml.melun.mangaview.MainApplication.p;
public class CustomHttpClient {
    public interface NtkViewerImageUrlsCallback {
        void onUrls(List<String> urls);
    }

    private static final String TAG = "ViewerPerf";
    private static final java.math.BigInteger P256_ORDER = new java.math.BigInteger(
            "FFFFFFFF00000000FFFFFFFFFFFFFFFFBCE6FAADA7179E84F3B9CAC2FC632551", 16);
    private static final java.math.BigInteger P256_HALF_ORDER = P256_ORDER.shiftRight(1);
    public static final String DEFAULT_COMIC_URL = "https://wfwf455.com/cm";
    public static final String WEBTOON_URL = "https://wfwf455.com";
    public static final String NTK_COMIC_URL = "https://sbxh8.com/manhwa";
    public static final String NTK_WEBTOON_URL = "https://sbxh8.com";
    public static final String NTK_REACHABLE_FALLBACK_URL = "https://ntk01.com";
    private static final String NTK_HOST = "sbxh8.com";
    private static final String PREVIOUS_NTK_HOST = "sbxh7.com";
    private static final String OLDER_NTK_HOST = "sbxh6.com";
    private static final String OLDEST_NTK_HOST = "sbxh5.com";
    private static final String LEGACY_NTK_HOST = "ntk01.com";
    private static final long WFWF_DOMAIN_CHECK_INTERVAL_MS = 6 * 60 * 60 * 1000L;
    private static final long WFWF_DOMAIN_FORCE_RETRY_INTERVAL_MS = 5 * 1000L;
    private static final long WFWF_FAILED_ROOT_RECHECK_INTERVAL_MS = 30 * 60 * 1000L;
    private static final long WFWF_DOMAIN_CANCELED_LOG_INTERVAL_MS = 2 * 1000L;
    private static final long WFWF_DOMAIN_WAIT_TIMEOUT_MS = 6 * 1000L;
    private static final long NTK_DOMAIN_CHECK_INTERVAL_MS = 15 * 60 * 1000L;
    private static final long NTK_PAGE_DIRECT_TIMEOUT_MS = 3_500L;
    private static final long NTK_API_DIRECT_TIMEOUT_MS = 1_200L;
    private static final long NTK_QUIC_GET_TIMEOUT_MS = 4_500L;
    private static final long NTK_QUIC_IMAGE_TIMEOUT_MS = 3_000L;
    private static final long NTK_FOREGROUND_IMAGE_RACE_TIMEOUT_MS = 1_800L;
    private static final long NTK_FOREGROUND_IMAGE_BACKUP_DELAY_MS = 180L;
    private static final long NTK_FOREGROUND_IMAGE_GENERATED_HEDGE_DELAY_MS = 320L;
    private static final int NTK_FOREGROUND_PARTIAL_IMAGE_BYTES = 8192;
    private static final int NTK_FOREGROUND_PARTIAL_IMAGE_RETRY_BYTES = 64 * 1024;
    private static final String NTK_NO_QUIC_HEADER = "X-MangaViewer-No-Quic";
    private static final long NTK_VIEWER_RSC_PAYLOAD_TIMEOUT_MS = 2_200L;
    private static final long NTK_VIEWER_IMAGES_API_TIMEOUT_MS = 2_100L;
    private static final long NTK_VIEWER_IMAGES_API_HEDGE_DELAY_MS = 0L;
    private static final long NTK_VIEWER_IMAGES_RECENT_CACHE_TRUST_MS = 5_000L;
    private static final long WFWF_PAGE_CONNECT_TIMEOUT_MS = 2_500L;
    private static final long WFWF_PAGE_READ_TIMEOUT_MS = 7_000L;
    private static final long WFWF_PAGE_CALL_TIMEOUT_MS = 8_000L;
    private static final long WFWF_SEARCH_CONNECT_TIMEOUT_MS = 800L;
    private static final long WFWF_SEARCH_READ_TIMEOUT_MS = 1_500L;
    private static final long WFWF_SEARCH_CALL_TIMEOUT_MS = 1_700L;
    private static final long NTK_DOH_TIMEOUT_MS = 1_500L;
    private static final long NTK_DNS_CACHE_DEFAULT_TTL_MS = 5 * 60 * 1000L;
    private static final long NTK_DNS_CACHE_MAX_TTL_MS = 30 * 60 * 1000L;
    private static final long NTK_DOH_FAILURE_BACKOFF_MS = 10 * 60 * 1000L;
    private static final long NTK_DNS_DISK_STALE_TTL_MS = 7 * 24 * 60 * 60 * 1000L;
    private static final long COOKIE_SYNC_INTERVAL_MS = 30 * 1000L;
    private static final long PAGE_CACHE_COLD_START_TTL_MS = 7 * 24 * 60 * 60 * 1000L;
    private static final int PAGE_CACHE_MAX_ENTRIES = 200;
    private static final int MAX_HTTP_REQUESTS = 8;
    private static final int MAX_HTTP_REQUESTS_PER_HOST = 4;
    private static final int MAX_IMAGE_HTTP_REQUESTS = 32;
    private static final int MAX_IMAGE_HTTP_REQUESTS_PER_HOST = 24;
    private static final int NTK_QUIC_CALLBACK_THREADS_PER_HOST = MAX_IMAGE_HTTP_REQUESTS_PER_HOST;
    private static final boolean DUMP_NTK_ACK_DEBUG_ARTIFACTS = false;
    private static final long NTK_ACK_CACHE_TTL_MS = 5 * 60 * 1000L;
    private static final long NTK_VIEWER_IMAGE_URL_CACHE_TTL_MS = 5 * 60 * 1000L;
    private static final long NTK_VIEWER_IMAGE_URL_MISS_CACHE_TTL_MS = 30 * 1000L;
    private static final long NTK_WASM_WARM_CACHE_TTL_MS = 30 * 60 * 1000L;
    private static final int NTK_ACK_REQUEST_ATTEMPTS = 3;
    private static final int NTK_ACK_CHALLENGE_RACE_REQUESTS = 1;
    private static final boolean NTK_ACK_CHALLENGE_OKHTTP_RACE = true;
    private static final boolean NTK_ACK_CHALLENGE_FRESH_RACE = true;
    private static final boolean NTK_ACK_CHALLENGE_BACKUP_RACE = false;
    private static final boolean NTK_ACK_CHALLENGE_HTTP2_RACE = false;
    private static final long NTK_ACK_RETRY_DELAY_MS = 80L;
    private static final long NTK_ACK_CHALLENGE_FIRST_TIMEOUT_MS = 1_200L;
    private static final long NTK_ACK_CHALLENGE_TIMEOUT_MS = 1_200L;
    private static final long NTK_ACK_CHALLENGE_HEDGE_DELAY_MS = 80L;
    private static final long NTK_ACK_CHALLENGE_BACKUP_DELAY_MS = 350L;
    private static final long NTK_ACK_CONFIRM_TIMEOUT_MS = 2_000L;
    private static final long NTK_ACK_CONTROL_HEDGE_DELAY_MS = 120L;
    private static final long NTK_ACK_CONTROL_MAX_WAIT_MS = 1_250L;
    private static final long NTK_RSC_ACK_CHALLENGE_WAIT_MS = 700L;
    private static final long NTK_RSC_ACK_CHALLENGE_EARLY_RELEASE_MS = 40L;
    private static final long NTK_ACK_CHALLENGE_RESULT_TTL_MS = 5_000L;
    private static final long NTK_ACK_PROOF_REQUIRED_TTL_MS = 12_000L;
    private static final long NTK_ACK_CLEARANCE_RESET_COOLDOWN_MS = 75_000L;
    private static boolean NTK_ACK_HARDBLOCK_AUTO_CLEARANCE_RESET = false;
    private static final long NTK_ACK_CANARY_JOIN_TIMEOUT_MS = 150L;
    private static final long NTK_ACK_PROACTIVE_JOIN_TIMEOUT_MS = 900L;
    private static final long NTK_ACK_IMPRESSION_MIN_WAIT_MS = 1_200L;
    public static final String NTK_DESKTOP_DOCUMENT_UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) "
            + "AppleWebKit/537.36 (KHTML, like Gecko) Chrome/148.0.0.0 Safari/537.36";
    private static final java.util.Map<String, Long> NTK_ACK_CACHE = new java.util.concurrent.ConcurrentHashMap<>();
    private static final java.util.Map<String, Object> NTK_ACK_LOCKS = new java.util.concurrent.ConcurrentHashMap<>();
    private static final java.util.Map<String, Long> NTK_ACK_IN_FLIGHT = new java.util.concurrent.ConcurrentHashMap<>();
    private static final java.util.Map<String, Long> NTK_ACK_CHALLENGE_IN_FLIGHT = new java.util.concurrent.ConcurrentHashMap<>();
    private static final java.util.Map<String, Long> NTK_ACK_CHALLENGE_OKS = new java.util.concurrent.ConcurrentHashMap<>();
    private static final java.util.Map<String, Long> NTK_ACK_CHALLENGE_HARDBLOCKS = new java.util.concurrent.ConcurrentHashMap<>();
    private static final java.util.Map<String, Long> NTK_ACK_PROOF_REQUIREDS = new java.util.concurrent.ConcurrentHashMap<>();
    private static final java.util.Map<String, Long> NTK_ACK_CLEARANCE_RESETS = new java.util.concurrent.ConcurrentHashMap<>();
    private static final java.util.Map<String, Long> NTK_WEBVIEW_ACK_FLIGHTS = new java.util.concurrent.ConcurrentHashMap<>();
    private static final Object NTK_WEBVIEW_ACK_PREFLIGHT_LOCK = new Object();
    private static final long NTK_WEBVIEW_ACK_PREFLIGHT_STALE_MS = 75_000L;
    private static final long NTK_WEBVIEW_ACK_STRICT_PROOF_WAIT_MS = 16_000L;
    private static final ExecutorService NTK_ACK_CHALLENGE_CALLBACK_EXECUTOR =
            Executors.newFixedThreadPool(2, runnable -> {
                Thread thread = new Thread(runnable, "ntk-ack-challenge-callback");
                thread.setDaemon(true);
                thread.setPriority(Math.min(Thread.MAX_PRIORITY, Thread.NORM_PRIORITY + 1));
                return thread;
            });
    private static final String PAGE_CACHE_PREFIX = "httpPageCacheV1_";
    private static final String NTK_DNS_CACHE_PREFIX = "ntkDnsCacheV1_";
    private static final String CLOUDFLARE_DOH_HOST = "cloudflare-dns.com";
    private static final String GOOGLE_DOH_HOST = "dns.google";
    private static final Gson GSON = new Gson();
    private static final ConnectionPool SHARED_CONNECTION_POOL = new ConnectionPool(12, 5, TimeUnit.MINUTES);
    private static final javax.net.SocketFactory SNI_FRAGMENTING_SOCKET_FACTORY =
            new SniFragmentingSocketFactory(javax.net.SocketFactory.getDefault());
    private static final Object NTK_DNS_CACHE_LOCK = new Object();
    private static final Map<String, CachedDns> NTK_DNS_CACHE = new HashMap<>();
    private static final Set<String> NTK_DNS_WARMING = new java.util.HashSet<>();
    private static final Map<String, Long> NTK_DOH_RETRY_AFTER = new HashMap<>();
    private static final ExecutorService NTK_DNS_EXECUTOR = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, "ntk-doh-warm");
        thread.setDaemon(true);
        return thread;
    });
    private static final Dns DOH_BOOTSTRAP_DNS = hostname -> {
        if(CLOUDFLARE_DOH_HOST.equalsIgnoreCase(hostname)) {
            return Arrays.asList(
                    address(hostname, 1, 1, 1, 1),
                    address(hostname, 1, 0, 0, 1));
        }
        if(GOOGLE_DOH_HOST.equalsIgnoreCase(hostname)) {
            return Arrays.asList(
                    address(hostname, 8, 8, 8, 8),
                    address(hostname, 8, 8, 4, 4));
        }
        return Dns.SYSTEM.lookup(hostname);
    };
    private static final OkHttpClient DOH_CLIENT = new OkHttpClient.Builder()
            .dns(DOH_BOOTSTRAP_DNS)
            .connectTimeout(NTK_DOH_TIMEOUT_MS, TimeUnit.MILLISECONDS)
            .readTimeout(NTK_DOH_TIMEOUT_MS, TimeUnit.MILLISECONDS)
            .callTimeout(NTK_DOH_TIMEOUT_MS, TimeUnit.MILLISECONDS)
            .build();
    private static final Dns NETWORK_RESILIENT_DNS = CustomHttpClient::lookupNetworkResilientDns;
    private static volatile File dnsCacheRoot;

    private static class CachedDns {
        final List<InetAddress> addresses;
        final long expiresAt;

        CachedDns(List<InetAddress> addresses, long expiresAt) {
            this.addresses = addresses;
            this.expiresAt = expiresAt;
        }
    }

    private static class DnsAnswer {
        final List<InetAddress> addresses;
        final long ttlMs;

        DnsAnswer(List<InetAddress> addresses, long ttlMs) {
            this.addresses = addresses;
            this.ttlMs = ttlMs;
        }
    }

    private static class DnsCacheEntry {
        final List<InetAddress> addresses;
        final boolean stale;

        DnsCacheEntry(List<InetAddress> addresses, boolean stale) {
            this.addresses = addresses;
            this.stale = stale;
        }
    }

    private static class PersistedDns {
        ArrayList<String> addresses;
        long savedAt;
        long expiresAt;
    }

    private static void addAddressIfMissing(List<InetAddress> addresses, InetAddress candidate) {
        if(candidate == null || addresses.contains(candidate))
            return;
        addresses.add(candidate);
    }

    private static List<InetAddress> lookupNetworkResilientDns(String hostname) throws UnknownHostException {
        if(isNtkDnsProtectedHost(hostname)) {
            List<InetAddress> protectedAddresses = ipv4OnlyOrEmpty(lookupCachedNtkDns(hostname, false));
            if(!protectedAddresses.isEmpty())
                return protectedAddresses;
            List<InetAddress> systemAddresses = lookupSystemDns(hostname, false);
            if(!systemAddresses.isEmpty())
                return ipv4OnlyOrThrow(hostname, mergeIpv4First(hostname, systemAddresses, null, null));
            protectedAddresses = ipv4OnlyOrEmpty(lookupFallbackNtkDns(hostname));
            if(!protectedAddresses.isEmpty())
                return protectedAddresses;
            protectedAddresses = ipv4OnlyOrEmpty(lookupCachedNtkDns(hostname, true));
            if(!protectedAddresses.isEmpty())
                return protectedAddresses;
            throw new UnknownHostException(hostname);
        }
        return selectNetworkResilientAddresses(hostname, lookupSystemDns(hostname, true));
    }

    private static List<InetAddress> lookupCachedOrFallbackNtkDns(String hostname) {
        List<InetAddress> cached = lookupCachedNtkDns(hostname, true);
        if(!cached.isEmpty())
            return cached;
        return lookupFallbackNtkDns(hostname);
    }

    private static List<InetAddress> lookupCachedNtkDns(String hostname, boolean allowStale) {
        DnsCacheEntry cached = readFreshCachedNtkDns(hostname);
        if(cached != null && cached.addresses != null && !cached.addresses.isEmpty())
            return cached.addresses;
        DnsCacheEntry stale = readDiskCachedNtkDns(hostname, allowStale);
        if(stale != null && stale.addresses != null && !stale.addresses.isEmpty()) {
            warmNtkDohAsync(hostname);
            ViewerWarmupManager.logMetric("ntk_dns_disk_stale_count", stale.addresses.size());
            return stale.addresses;
        }
        return new ArrayList<>();
    }

    private static List<InetAddress> lookupFallbackNtkDns(String hostname) {
        List<InetAddress> doh = ipv4OnlyOrEmpty(lookupNtkDohFresh(hostname, "ntk_dns_doh_fallback_ms"));
        if(!doh.isEmpty())
            return doh;
        return new ArrayList<>();
    }

    private static void warmNtkDohAsync(String hostname) {
        String key = normalizeDnsHost(hostname);
        long now = System.currentTimeMillis();
        synchronized (NTK_DNS_CACHE_LOCK) {
            CachedDns cached = NTK_DNS_CACHE.get(key);
            if(cached != null && cached.expiresAt > now)
                return;
            if(!shouldStartNtkDohWarm(NTK_DOH_RETRY_AFTER.get(key), now))
                return;
            if(NTK_DNS_WARMING.contains(key))
                return;
            NTK_DNS_WARMING.add(key);
        }
        NTK_DNS_EXECUTOR.execute(() -> {
            boolean success = false;
            try {
                success = !lookupNtkDohFresh(hostname, "ntk_dns_doh_warm_ms").isEmpty();
            } finally {
                synchronized (NTK_DNS_CACHE_LOCK) {
                    NTK_DNS_WARMING.remove(key);
                    updateNtkDohRetryAfterLocked(key, success, System.currentTimeMillis());
                }
            }
        });
    }

    static boolean shouldStartNtkDohWarmForTest(long retryAfterMs, long nowMs) {
        return shouldStartNtkDohWarm(retryAfterMs <= 0 ? null : retryAfterMs, nowMs);
    }

    static long nextNtkDohRetryAfterForTest(boolean success, long nowMs) {
        return nextNtkDohRetryAfter(success, nowMs);
    }

    private static boolean shouldStartNtkDohWarm(Long retryAfterMs, long nowMs) {
        return retryAfterMs == null || retryAfterMs <= nowMs;
    }

    private static void updateNtkDohRetryAfterLocked(String key, boolean success, long nowMs) {
        long retryAfter = nextNtkDohRetryAfter(success, nowMs);
        if(retryAfter <= 0)
            NTK_DOH_RETRY_AFTER.remove(key);
        else
            NTK_DOH_RETRY_AFTER.put(key, retryAfter);
    }

    private static long nextNtkDohRetryAfter(boolean success, long nowMs) {
        return success ? 0L : nowMs + NTK_DOH_FAILURE_BACKOFF_MS;
    }

    private static List<InetAddress> lookupSystemDns(String hostname, boolean throwOnFailure) throws UnknownHostException {
        long startedAt = System.currentTimeMillis();
        try {
            List<InetAddress> addresses = Dns.SYSTEM.lookup(hostname);
            if(isNtkDnsProtectedHost(hostname))
                ViewerWarmupManager.logMetric("ntk_dns_system_ms", System.currentTimeMillis() - startedAt);
            return addresses;
        } catch (UnknownHostException e) {
            if(isNtkDnsProtectedHost(hostname))
                ViewerWarmupManager.logMetric("ntk_dns_system_ms", System.currentTimeMillis() - startedAt);
            if(throwOnFailure)
                throw e;
            return new ArrayList<>();
        }
    }

    private static List<InetAddress> lookupNtkDoh(String hostname) {
        List<InetAddress> cached = readCachedNtkDns(hostname);
        if(cached != null)
            return cached;
        return lookupNtkDohFresh(hostname, "ntk_dns_doh_ms");
    }

    private static List<InetAddress> lookupNtkDohFresh(String hostname, String metricName) {
        List<InetAddress> cloudflare = lookupNtkDohFresh(hostname, CLOUDFLARE_DOH_HOST,
                "/dns-query", metricName, metricName + "_cloudflare");
        if(!cloudflare.isEmpty())
            return cloudflare;
        return lookupNtkDohFresh(hostname, GOOGLE_DOH_HOST,
                "/resolve", metricName, metricName + "_google");
    }

    private static List<InetAddress> lookupNtkDohFresh(String hostname, String providerHost,
                                                       String path, String totalMetricName,
                                                       String providerMetricName) {
        long startedAt = System.currentTimeMillis();
        Response response = null;
        try {
            HttpUrl url = HttpUrl.get("https://" + providerHost + path)
                    .newBuilder()
                    .addQueryParameter("name", hostname)
                    .addQueryParameter("type", "A")
                    .build();
            Request request = new Request.Builder()
                    .url(url)
                    .header("Accept", "application/dns-json")
                    .get()
                    .build();
            response = DOH_CLIENT.newCall(request).execute();
            if(response.code() < 200 || response.code() >= 400 || response.body() == null)
                return new ArrayList<>();
            DnsAnswer answer = parseDohAnswer(hostname, response.body().string());
            if(!answer.addresses.isEmpty())
                writeCachedNtkDns(hostname, answer.addresses, answer.ttlMs);
            return answer.addresses;
        } catch (Exception e) {
            return new ArrayList<>();
        } finally {
            if(response != null)
                response.close();
            long elapsed = System.currentTimeMillis() - startedAt;
            ViewerWarmupManager.logMetric(providerMetricName, elapsed);
            ViewerWarmupManager.logMetric(totalMetricName, elapsed);
        }
    }

    private static DnsAnswer parseDohAnswer(String hostname, String body) throws Exception {
        ArrayList<InetAddress> addresses = new ArrayList<>();
        long minTtlMs = NTK_DNS_CACHE_DEFAULT_TTL_MS;
        JSONObject json = new JSONObject(body);
        JSONArray answers = json.optJSONArray("Answer");
        if(answers == null)
            return new DnsAnswer(addresses, minTtlMs);
        for(int i = 0; i < answers.length(); i++) {
            JSONObject answer = answers.optJSONObject(i);
            if(answer == null || answer.optInt("type") != 1)
                continue;
            String data = answer.optString("data", "");
            InetAddress address = parseIpv4Address(hostname, data);
            if(address == null)
                continue;
            addAddressIfMissing(addresses, address);
            long ttlMs = Math.max(30_000L, answer.optLong("TTL", 300L) * 1000L);
            minTtlMs = Math.min(minTtlMs, ttlMs);
        }
        return new DnsAnswer(addresses, Math.min(minTtlMs, NTK_DNS_CACHE_MAX_TTL_MS));
    }

    private static InetAddress parseIpv4Address(String hostname, String data) {
        try {
            if(data == null || !data.matches("\\d+\\.\\d+\\.\\d+\\.\\d+"))
                return null;
            String[] parts = data.split("\\.");
            int a = Integer.parseInt(parts[0]);
            int b = Integer.parseInt(parts[1]);
            int c = Integer.parseInt(parts[2]);
            int d = Integer.parseInt(parts[3]);
            if(a < 0 || a > 255 || b < 0 || b > 255 || c < 0 || c > 255 || d < 0 || d > 255)
                return null;
            return address(hostname, a, b, c, d);
        } catch (Exception e) {
            return null;
        }
    }

    private static List<InetAddress> readCachedNtkDns(String hostname) {
        DnsCacheEntry cached = readFreshCachedNtkDns(hostname);
        return cached == null ? null : cached.addresses;
    }

    private static DnsCacheEntry readFreshCachedNtkDns(String hostname) {
        long now = System.currentTimeMillis();
        synchronized (NTK_DNS_CACHE_LOCK) {
            CachedDns cached = NTK_DNS_CACHE.get(normalizeDnsHost(hostname));
            if(cached != null && cached.expiresAt > now)
                return new DnsCacheEntry(new ArrayList<>(cached.addresses), false);
        }
        return readDiskCachedNtkDns(hostname, false);
    }

    private static void writeCachedNtkDns(String hostname, List<InetAddress> addresses, long ttlMs) {
        if(addresses == null || addresses.isEmpty())
            return;
        long now = System.currentTimeMillis();
        long expiresAt = now + Math.max(30_000L, ttlMs);
        writeMemoryCachedNtkDns(hostname, addresses, expiresAt);
        writeDiskCachedNtkDns(hostname, addresses, now, expiresAt);
    }

    private static void writeMemoryCachedNtkDns(String hostname, List<InetAddress> addresses, long expiresAt) {
        if(addresses == null || addresses.isEmpty())
            return;
        synchronized (NTK_DNS_CACHE_LOCK) {
            NTK_DNS_CACHE.put(normalizeDnsHost(hostname),
                    new CachedDns(new ArrayList<>(addresses), expiresAt));
        }
    }

    private static DnsCacheEntry readDiskCachedNtkDns(String hostname, boolean allowStale) {
        File cacheRoot = dnsCacheRoot;
        if(cacheRoot == null)
            return null;
        String key = ntkDnsCacheKey(hostname);
        try {
            String json = CacheFileStore.read(cacheRoot, key);
            if(json == null || json.length() == 0)
                return null;
            PersistedDns persisted = GSON.fromJson(json, PersistedDns.class);
            long now = System.currentTimeMillis();
            if(persisted == null || persisted.savedAt > now) {
                CacheFileStore.delete(cacheRoot, key);
                return null;
            }
            boolean fresh = isPersistedNtkDnsFresh(persisted.expiresAt, now);
            boolean stale = isPersistedNtkDnsStale(persisted.savedAt, persisted.expiresAt, now);
            if(!fresh && !(allowStale && stale)) {
                if(!stale)
                    CacheFileStore.delete(cacheRoot, key);
                return null;
            }
            ArrayList<InetAddress> addresses = parsePersistedDnsAddresses(hostname, persisted.addresses);
            if(addresses.isEmpty()) {
                CacheFileStore.delete(cacheRoot, key);
                return null;
            }
            if(fresh)
                writeMemoryCachedNtkDns(hostname, addresses, persisted.expiresAt);
            return new DnsCacheEntry(addresses, stale);
        } catch (Exception e) {
            CacheFileStore.delete(cacheRoot, key);
            return null;
        }
    }

    private static void writeDiskCachedNtkDns(String hostname, List<InetAddress> addresses, long savedAt, long expiresAt) {
        File cacheRoot = dnsCacheRoot;
        if(cacheRoot == null || addresses == null || addresses.isEmpty())
            return;
        PersistedDns persisted = new PersistedDns();
        persisted.savedAt = savedAt;
        persisted.expiresAt = expiresAt;
        persisted.addresses = new ArrayList<>();
        for(InetAddress address : addresses)
            if(address instanceof Inet4Address)
                persisted.addresses.add(address.getHostAddress());
        if(persisted.addresses.isEmpty())
            return;
        CacheFileStore.write(cacheRoot, ntkDnsCacheKey(hostname), GSON.toJson(persisted));
    }

    private static ArrayList<InetAddress> parsePersistedDnsAddresses(String hostname, List<String> persistedAddresses) {
        ArrayList<InetAddress> addresses = new ArrayList<>();
        if(persistedAddresses == null)
            return addresses;
        for(String value : persistedAddresses) {
            InetAddress address = parseIpv4Address(hostname, value);
            if(address != null)
                addAddressIfMissing(addresses, address);
        }
        return addresses;
    }

    private static String ntkDnsCacheKey(String hostname) {
        return NTK_DNS_CACHE_PREFIX + normalizeDnsHost(hostname);
    }

    private static boolean isPersistedNtkDnsUsable(long savedAt, long expiresAt, long now, boolean allowStale) {
        return savedAt <= now
                && (isPersistedNtkDnsFresh(expiresAt, now)
                || (allowStale && isPersistedNtkDnsStale(savedAt, expiresAt, now)));
    }

    private static boolean isPersistedNtkDnsFresh(long expiresAt, long now) {
        return expiresAt > now;
    }

    private static boolean isPersistedNtkDnsStale(long savedAt, long expiresAt, long now) {
        return expiresAt <= now && savedAt <= now && now - savedAt <= NTK_DNS_DISK_STALE_TTL_MS;
    }

    private static List<InetAddress> mergeIpv4First(String hostname, List<InetAddress> preferred,
                                                    List<InetAddress> secondary,
                                                    List<InetAddress> fallback) {
        ArrayList<InetAddress> sorted = new ArrayList<>();
        addIpv4ThenOthers(sorted, preferred);
        addIpv4ThenOthers(sorted, secondary);
        addIpv4ThenOthers(sorted, fallback);
        if(isNtkDnsProtectedHost(hostname) && (preferred == null || preferred.isEmpty())
                && (secondary == null || secondary.isEmpty()) && fallback != null && !fallback.isEmpty())
            ViewerWarmupManager.logMetric("ntk_dns_fallback_count", fallback.size());
        return sorted;
    }

    private static void addIpv4ThenOthers(List<InetAddress> target, List<InetAddress> source) {
        if(source == null)
            return;
        for(InetAddress address : source)
            if(address instanceof Inet4Address)
                addAddressIfMissing(target, address);
        for(InetAddress address : source)
            if(!(address instanceof Inet4Address))
                addAddressIfMissing(target, address);
    }

    private static List<InetAddress> ipv4OnlyOrEmpty(List<InetAddress> addresses) {
        ArrayList<InetAddress> ipv4 = new ArrayList<>();
        if(addresses != null) {
            for(InetAddress address : addresses)
                if(address instanceof Inet4Address)
                    addAddressIfMissing(ipv4, address);
        }
        return ipv4;
    }

    private static List<InetAddress> ipv4OnlyOrThrow(String hostname, List<InetAddress> addresses) throws UnknownHostException {
        List<InetAddress> ipv4 = ipv4OnlyOrEmpty(addresses);
        if(!ipv4.isEmpty())
            return ipv4;
        throw new UnknownHostException((hostname == null ? "" : hostname) + " has no IPv4 DNS answers");
    }

    public static String resolveDirectHostForNtkProxy(String hostname) {
        if(!isNtkDnsProtectedHost(hostname))
            return hostname;
        List<InetAddress> addresses = lookupCachedNtkDns(hostname, false);
        if(addresses.isEmpty()) {
            try {
                List<InetAddress> systemAddresses = lookupSystemDns(hostname, false);
                if(!systemAddresses.isEmpty())
                    return hostname;
            } catch (Exception ignored) {
            }
            addresses = lookupFallbackNtkDns(hostname);
        }
        if(addresses.isEmpty())
            addresses = lookupCachedNtkDns(hostname, true);
        if(addresses.isEmpty())
            return hostname;
        return addresses.get(0).getHostAddress();
    }

    static boolean isNtkDnsProtectedHostForTest(String hostname) {
        return isNtkDnsProtectedHost(hostname);
    }

    static boolean isPersistedNtkDnsUsableForTest(long savedAt, long expiresAt, long now, boolean allowStale) {
        return isPersistedNtkDnsUsable(savedAt, expiresAt, now, allowStale);
    }

    static boolean isPersistedNtkDnsStaleForTest(long savedAt, long expiresAt, long now) {
        return isPersistedNtkDnsStale(savedAt, expiresAt, now);
    }

    static List<InetAddress> mergeIpv4FirstForTest(String hostname, List<InetAddress> preferred,
                                                   List<InetAddress> secondary,
                                                   List<InetAddress> fallback) {
        return mergeIpv4First(hostname, preferred, secondary, fallback);
    }

    static List<InetAddress> ipv4OnlyOrThrowForTest(String hostname, List<InetAddress> addresses) throws UnknownHostException {
        return ipv4OnlyOrThrow(hostname, addresses);
    }

    static List<InetAddress> selectNetworkResilientAddressesForTest(String hostname, List<InetAddress> addresses) throws UnknownHostException {
        return selectNetworkResilientAddresses(hostname, addresses);
    }

    static boolean prefersIpv6ForWfwfHostForTest(String hostname) {
        return prefersIpv6ForWfwfHost(hostname);
    }

    private static List<InetAddress> selectNetworkResilientAddresses(String hostname, List<InetAddress> addresses) throws UnknownHostException {
        if(requiresIpv4OnlyDns(hostname))
            return ipv4OnlyOrThrow(hostname, addresses);
        List<InetAddress> sorted = mergeIpv4First(hostname, addresses, null, null);
        if(!sorted.isEmpty())
            return sorted;
        throw new UnknownHostException(hostname == null ? "" : hostname);
    }

    private static List<InetAddress> ipv6FirstThenIpv4(String hostname, List<InetAddress> addresses) throws UnknownHostException {
        ArrayList<InetAddress> sorted = new ArrayList<>();
        if(addresses != null) {
            for(InetAddress address : addresses)
                if(address instanceof Inet6Address)
                    addAddressIfMissing(sorted, address);
            for(InetAddress address : addresses)
                if(address instanceof Inet4Address)
                    addAddressIfMissing(sorted, address);
            for(InetAddress address : addresses)
                if(!(address instanceof Inet6Address) && !(address instanceof Inet4Address))
                    addAddressIfMissing(sorted, address);
        }
        if(!sorted.isEmpty())
            return sorted;
        throw new UnknownHostException(hostname == null ? "" : hostname);
    }

    private static boolean prefersIpv6ForWfwfHost(String hostname) {
        return !requiresIpv4OnlyDns(hostname);
    }

    private static boolean requiresIpv4OnlyDns(String hostname) {
        String normalized = normalizeDnsHost(hostname);
        return isNtkDnsProtectedHost(normalized)
                || normalized.matches("wfwf\\d+\\.com")
                || normalized.contains("imgcloud")
                || normalized.endsWith("v12st.com")
                || normalized.endsWith(".v12st.com");
    }

    private static boolean isNtkDnsProtectedHost(String hostname) {
        String normalized = normalizeDnsHost(hostname);
        return isNtkProtectedHostSuffix(normalized, NTK_HOST)
                || isNtkProtectedHostSuffix(normalized, PREVIOUS_NTK_HOST)
                || isNtkProtectedHostSuffix(normalized, OLDER_NTK_HOST)
                || isNtkProtectedHostSuffix(normalized, OLDEST_NTK_HOST)
                || normalized.matches("(?:[a-z0-9-]+\\.)?sbxh\\d+\\.com")
                || normalized.matches("(?:[a-z0-9-]+\\.)?newto(?:ki)?\\d*\\.com")
                || isNtkProtectedHostSuffix(normalized, "toonflix.app")
                || isConfiguredNtkHost(normalized)
                || normalized.equals(LEGACY_NTK_HOST)
                || normalized.endsWith("." + LEGACY_NTK_HOST);
    }

    private static boolean isNtkProtectedHostSuffix(String normalized, String host) {
        return normalized.equals(host) || normalized.endsWith("." + host);
    }

    private static String normalizeDnsHost(String hostname) {
        if(hostname == null)
            return "";
        String normalized = hostname.trim().toLowerCase(Locale.ROOT);
        if(normalized.startsWith("www."))
            normalized = normalized.substring(4);
        return normalized;
    }

    private static InetAddress address(String hostname, int a, int b, int c, int d) throws UnknownHostException {
        return InetAddress.getByAddress(hostname, new byte[] {(byte)a, (byte)b, (byte)c, (byte)d});
    }

    private void appendSystemDnsDiagnostic(StringBuilder report, String hostname) {
        long startedAt = System.currentTimeMillis();
        try {
            List<InetAddress> addresses = lookupSystemDns(hostname, true);
            appendDiagnosticLine(report, "system_dns_" + hostname,
                    "ok " + (System.currentTimeMillis() - startedAt) + "ms " + formatAddresses(addresses));
        } catch (Exception e) {
            appendDiagnosticLine(report, "system_dns_" + hostname,
                    "fail " + (System.currentTimeMillis() - startedAt) + "ms " + exceptionSummary(e));
        }
    }

    private void appendAppDnsDiagnostic(StringBuilder report, String hostname) {
        long startedAt = System.currentTimeMillis();
        try {
            List<InetAddress> addresses = lookupNetworkResilientDns(hostname);
            appendDiagnosticLine(report, "app_dns_" + hostname,
                    "ok " + (System.currentTimeMillis() - startedAt) + "ms " + formatAddresses(addresses));
        } catch (Exception e) {
            appendDiagnosticLine(report, "app_dns_" + hostname,
                    "fail " + (System.currentTimeMillis() - startedAt) + "ms " + exceptionSummary(e));
        }
    }

    private void appendDohDiagnostic(StringBuilder report, String hostname) {
        long startedAt = System.currentTimeMillis();
        try {
            List<InetAddress> addresses = lookupNtkDohFreshForDiagnostic(hostname);
            appendDiagnosticLine(report, "cloudflare_doh_" + hostname,
                    (addresses.isEmpty() ? "fail " : "ok ")
                            + (System.currentTimeMillis() - startedAt) + "ms " + formatAddresses(addresses));
        } catch (Exception e) {
            appendDiagnosticLine(report, "cloudflare_doh_" + hostname,
                    "fail " + (System.currentTimeMillis() - startedAt) + "ms " + exceptionSummary(e));
        }
    }

    private void appendProxyDiagnostic(StringBuilder report, String hostname) {
        long startedAt = System.currentTimeMillis();
        try {
            appendDiagnosticLine(report, "webview_proxy_target_" + hostname,
                    resolveDirectHostForNtkProxy(hostname) + " " + (System.currentTimeMillis() - startedAt) + "ms");
        } catch (Exception e) {
            appendDiagnosticLine(report, "webview_proxy_target_" + hostname, "fail " + exceptionSummary(e));
        }
    }

    private void appendNtkQuicDiagnostic(StringBuilder report, String root) {
        if(context == null || !NtkQuicFetcher.isAvailable()) {
            appendDiagnosticLine(report, "ntk_quic_sni", "unavailable");
            return;
        }
        long startedAt = System.currentTimeMillis();
        try {
            Map<String, String> headers = buildHeaders(root, true, null);
            NtkQuicFetcher.Result result = fetchNtkQuic(root, trimTrailingSlash(root) + "/",
                    headers.get("Cookie"), headers, "GET", null, 5_000L);
            String body = result == null || result.body == null ? "" : result.body;
            boolean challenge = result != null && isCloudflareChallengeResponse(result.code, body);
            if(challenge)
                markCloudflareChallenge(trimTrailingSlash(root) + "/");
            appendDiagnosticLine(report, "ntk_quic_sni",
                    "code=" + (result == null ? 0 : result.code)
                            + ",ms=" + (System.currentTimeMillis() - startedAt)
                            + ",body_len=" + body.length()
                            + ",challenge=" + challenge
                            + ",error=" + (result == null ? "" : throwableSummary(result.error)));
        } catch (Exception e) {
            appendDiagnosticLine(report, "ntk_quic_sni",
                    "fail " + (System.currentTimeMillis() - startedAt) + "ms " + exceptionSummary(e));
        }
    }

    private void appendNtkApiDiagnostic(StringBuilder report, String root) {
        String path = "/api/manhwa-list?page=1&pageSize=1&withTotal=1";
        long startedAt = System.currentTimeMillis();
        Response response = null;
        try {
            OkHttpClient probeClient = client.newBuilder()
                    .connectTimeout(4, TimeUnit.SECONDS)
                    .readTimeout(6, TimeUnit.SECONDS)
                    .callTimeout(8, TimeUnit.SECONDS)
                    .dns(NETWORK_RESILIENT_DNS)
                    .followRedirects(false)
                    .followSslRedirects(false)
                    .build();
            Map<String, String> headers = buildHeaders(root, true, null);
            applyNtkApiHeaders(headers, root, path);
            Request.Builder builder = new Request.Builder()
                    .url(trimTrailingSlash(root) + path)
                    .get();
            for(String key : headers.keySet())
                builder.addHeader(key, headers.get(key));
            response = probeClient.newCall(builder.build()).execute();
            int code = response.code();
            String body = response.body() == null ? "" : response.body().string();
            boolean challenge = isCloudflareChallenge(code, body);
            if(challenge) {
                markCloudflareChallenge(trimTrailingSlash(root) + path);
                appendDiagnosticLine(report, "ntk_clearance_invalidated", "true");
            }
            String result = "code=" + code
                    + ",ms=" + (System.currentTimeMillis() - startedAt)
                    + ",body_len=" + body.length()
                    + ",challenge=" + challenge
                    + ",type=" + response.header("content-type", "");
            appendDiagnosticLine(report, "ntk_api_direct", result);
            if(body.length() > 0)
                appendDiagnosticLine(report, "ntk_api_body_head", abbreviate(body.replace('\n', ' '), 180));
        } catch (Exception e) {
            appendDiagnosticLine(report, "ntk_api_direct",
                    "fail " + (System.currentTimeMillis() - startedAt) + "ms " + exceptionSummary(e));
        } finally {
            if(response != null)
                response.close();
        }
    }

    private void appendWfwfHttpDiagnostic(StringBuilder report, String root) {
        appendWfwfPageDiagnostic(report, "wfwf_cm_direct", root, "/cm");
        appendWfwfPageDiagnostic(report, "wfwf_episode_direct", root, "/cl?toon=10007");
    }

    private void appendWfwfPageDiagnostic(StringBuilder report, String key, String root, String path) {
        long startedAt = System.currentTimeMillis();
        Response response = null;
        try {
            OkHttpClient probeClient = client.newBuilder()
                    .connectTimeout(4, TimeUnit.SECONDS)
                    .readTimeout(6, TimeUnit.SECONDS)
                    .callTimeout(8, TimeUnit.SECONDS)
                    .dns(NETWORK_RESILIENT_DNS)
                    .followRedirects(false)
                    .followSslRedirects(false)
                    .build();
            Map<String, String> headers = buildHeaders(root, true, null);
            Request.Builder builder = new Request.Builder()
                    .url(trimTrailingSlash(root) + path)
                    .get();
            for(String headerKey : headers.keySet())
                builder.addHeader(headerKey, headers.get(headerKey));
            response = probeClient.newCall(builder.build()).execute();
            int code = response.code();
            String body = response.body() == null ? "" : response.body().string();
            String result = "code=" + code
                    + ",ms=" + (System.currentTimeMillis() - startedAt)
                    + ",body_len=" + body.length()
                    + ",cacheable=" + looksCacheable(body)
                    + ",type=" + response.header("content-type", "");
            appendDiagnosticLine(report, key, result);
            if(body.length() > 0)
                appendDiagnosticLine(report, key + "_body_head", abbreviate(body.replace('\n', ' '), 180));
        } catch (Exception e) {
            appendDiagnosticLine(report, key,
                    "fail " + (System.currentTimeMillis() - startedAt) + "ms " + exceptionSummary(e));
        } finally {
            if(response != null)
                response.close();
        }
    }

    private static List<InetAddress> lookupNtkDohFreshForDiagnostic(String hostname) {
        return lookupNtkDohFresh(hostname, "ntk_dns_doh_diagnostic_ms");
    }

    private static void appendDiagnosticLine(StringBuilder builder, String key, String value) {
        builder.append(key).append(": ").append(value == null ? "" : value).append('\n');
    }

    private static String formatAddresses(List<InetAddress> addresses) {
        if(addresses == null || addresses.isEmpty())
            return "-";
        StringBuilder builder = new StringBuilder();
        for(InetAddress address : addresses) {
            if(builder.length() > 0)
                builder.append(',');
            builder.append(address.getHostAddress());
        }
        return builder.toString();
    }

    private static String hostOf(String url) {
        try {
            URI uri = URI.create(url);
            String host = uri.getHost();
            return host == null || host.length() == 0 ? normalizeDnsHost(url) : host;
        } catch (Exception e) {
            return normalizeDnsHost(url);
        }
    }

    private static String emptyToUnknown(String value) {
        return value == null || value.trim().length() == 0 ? "unknown" : value.trim();
    }

    private static String exceptionSummary(Exception e) {
        if(e == null)
            return "unknown";
        return throwableSummary(e);
    }

    private static String throwableSummary(Throwable e) {
        if(e == null)
            return "";
        String message = e.getMessage();
        return e.getClass().getSimpleName() + (message == null || message.length() == 0 ? "" : "(" + abbreviate(message, 120) + ")");
    }

    private static String abbreviate(String value, int maxLength) {
        if(value == null)
            return "";
        String trimmed = value.trim();
        if(trimmed.length() <= maxLength)
            return trimmed;
        return trimmed.substring(0, Math.max(0, maxLength - 3)) + "...";
    }

    private static String diagnosticInterpretation(String report) {
        String lower = report == null ? "" : report.toLowerCase(Locale.ROOT);
        if(lower.contains("active_site: wfwf")) {
            if(lower.contains("wfwf_cm_direct: code=2") || lower.contains("wfwf_episode_direct: code=2"))
                return "OK: app route can reach WFWF.";
            if(lower.contains("app_dns_") && lower.contains(": ok ")
                    && (lower.contains("wfwf_cm_direct: fail") || lower.contains("wfwf_episode_direct: fail")))
                return "DNS resolved, but mobile route/TLS is still blocked before WFWF responds.";
            if(lower.contains("system_dns_") && lower.contains(": fail") && lower.contains("app_dns_") && lower.contains(": ok "))
                return "Carrier DNS appears blocked, app DNS bypass is working.";
            return "Check WFWF DNS/direct lines above.";
        }
        if(lower.contains("ntk_api_direct: code=2"))
            return "OK: app route can reach NTK.";
        if(lower.contains("ntk_api_direct: code=403") || lower.contains("challenge=true"))
            return "Cloudflare challenge/cookie issue. Open NTK captcha once.";
        if(ntkQuicSniLooksBlocked(lower) && containsNtkProtectedDnsState(lower, "app_dns_", ": ok"))
            return "DNS bypass works, but mobile route/TLS/SNI is still blocked before NTK responds. A VPN/WARP-style tunnel is required on this network.";
        if(containsNtkProtectedDnsState(lower, "system_dns_", ": fail")
                && containsNtkProtectedDnsState(lower, "app_dns_", ": ok"))
            return "Carrier DNS appears blocked, app DNS bypass is working.";
        if(lower.contains("ntk_api_direct: fail") && containsNtkProtectedDnsState(lower, "app_dns_", ": ok"))
            return "DNS bypass works, but route/TLS/SNI may still be blocked by the mobile network.";
        return "Check DNS/API lines above.";
    }

    private static boolean containsNtkProtectedDnsState(String lowerReport, String prefix, String state) {
        if(lowerReport == null || prefix == null || state == null)
            return false;
        String normalizedState = state.trim();
        while(normalizedState.startsWith(":"))
            normalizedState = normalizedState.substring(1).trim();
        Pattern pattern = Pattern.compile("(?m)^" + Pattern.quote(prefix) + "([^:\\s]+):\\s*"
                + Pattern.quote(normalizedState));
        Matcher matcher = pattern.matcher(lowerReport);
        while(matcher.find()) {
            String host = matcher.group(1);
            if(isNtkDnsProtectedHost(host))
                return true;
        }
        return false;
    }

    private static boolean ntkQuicSniLooksBlocked(String lowerReport) {
        if(lowerReport == null || !lowerReport.contains("ntk_quic_sni:"))
            return false;
        return lowerReport.contains("err_connection_closed")
                || lowerReport.contains("internalerrorcode=-100")
                || lowerReport.contains("net_error=-100")
                || lowerReport.contains("networkexceptionwrapper")
                || lowerReport.contains("ntk_quic_sni: fail")
                || lowerReport.contains("ntk_quic_sni: code=0");
    }

    static String diagnosticInterpretationForTest(String report) {
        return diagnosticInterpretation(report);
    }

    public OkHttpClient client;
    public OkHttpClient imageClient;
    private OkHttpClient unsafeFallbackClient;
    private OkHttpClient ntkPageFastClient;
    private OkHttpClient unsafeNtkPageFastClient;
    private OkHttpClient ntkApiFastClient;
    private OkHttpClient unsafeNtkApiFastClient;
    private OkHttpClient wolfPageFastClient;
    private OkHttpClient unsafeWolfPageFastClient;
    private OkHttpClient wolfSearchFastClient;
    private OkHttpClient unsafeWolfSearchFastClient;
    private static final ConcurrentHashMap<String, CachedPage> NTK_VIEWER_PAYLOAD_CACHE = new ConcurrentHashMap<>();
    Map<String, String> cookies;
    Map<String, Long> cookieSyncAt;
    private final Set<String> rejectedCloudflareClearances = Collections.synchronizedSet(new LinkedHashSet<>());
    Map<String, CachedPage> pageCache;
    Map<String, PageLoadState> pageLoads;
    private String cookieHeaderCache;
    private final Object pageCacheLock = new Object();
    private final Object pageLoadsLock = new Object();
    private volatile String lastCloudflareChallengeUrl = null;
    private volatile long lastCloudflareChallengeAt = 0L;
    private volatile String lastNtkHardBlockUrl = null;
    private volatile long lastNtkHardBlockAt = 0L;
    private volatile boolean cloudflareCaptchaActive = false;
    private final ThreadLocal<RequestGroup> currentRequestGroup = requestGroupLocal();
    private final ThreadLocal<FetchMode> currentFetchMode = new ThreadLocal<>();
    private final ThreadLocal<SiteOverride> currentSiteOverride = new ThreadLocal<>();

    private ThreadLocal<RequestGroup> requestGroupLocal() {
        return new
                ThreadLocal<>();
    }
    private final Object wfwfDomainLock = new Object();
    private DomainResolveState wfwfDomainResolveState;
    private DomainResolveState ntkDomainResolveState;
    private volatile boolean ntkDomainAutoResolveDisabledForTest = false;
    private long wfwfDomainLastForcedRetry = 0;
    private long wfwfDomainLastCanceledLog = 0;
    private long ntkDomainLastCheck = 0;
    private String ntkDomainLastCheckedRoot = "";
    private Context context;
    private final Object ntkQuicEngineLock = new Object();
    private final Map<String, HttpEngine> ntkQuicEngines = new HashMap<>();
    private final Map<String, FutureTask<HttpEngine>> ntkQuicEngineTasks = new HashMap<>();
    private final Map<String, ExecutorService> ntkQuicExecutors = new HashMap<>();
    private final Map<String, FutureTask<NtkQuicFetcher.Result>> ntkQuicImageFlights = new java.util.concurrent.ConcurrentHashMap<>();
    private final Map<String, Long> ntkWasmWarmCache = new HashMap<>();
    private final Object ntkViewerImageUrlCacheLock = new Object();
    private final Map<String, CachedViewerImages> ntkViewerImageUrlCache = new HashMap<>();
    private final Map<String, Long> ntkViewerImageUrlMissCache = new HashMap<>();
    private final Map<String, FutureTask<List<String>>> ntkViewerImageUrlFlights = new java.util.concurrent.ConcurrentHashMap<>();
    public String agent = "Mozilla/5.0 (Linux; Android 13; SM-G981B) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/116.0.0.0 Mobile Safari/537.36";
    private java.security.KeyPair ntkViewerBrowserKeyPair;
    private String ntkViewerBrowserKeyId = "";
    private long ntkViewerBrowserKeyExpiresAt = 0L;
    private long ntkViewerBrowserKeyServerTimeOffsetMs = 0L;

    public enum FetchMode {
        CACHE_ONLY,
        DIRECT_ONLY,
        SEARCH_NO_WEBVIEW,
        ALLOW_SHARED_WEBVIEW
    }

    private static class SiteOverride {
        String comicUrl;
        String webtoonUrl;

        SiteOverride(String comicUrl, String webtoonUrl) {
            this.comicUrl = comicUrl;
            this.webtoonUrl = webtoonUrl;
        }
    }

    public CustomHttpClient(Context context){
        this.context = context.getApplicationContext();
        dnsCacheRoot = CacheFileStore.fileRoot(this.context);
        this.cookies = new HashMap<>();
        this.cookieSyncAt = new HashMap<>();
        this.pageLoads = new HashMap<>();
        this.pageCache = new LinkedHashMap<String, CachedPage>(PAGE_CACHE_MAX_ENTRIES, 0.75f, true) {
            @Override
            protected boolean removeEldestEntry(Map.Entry<String, CachedPage> eldest) {
                return size() > PAGE_CACHE_MAX_ENTRIES;
            }
        };
        loadSavedUserAgent();
        loadSavedCookies();
        restoreClearanceFromDisk();
        this.client = baseClient(new OkHttpClient.Builder()).build();
        this.imageClient = imageClient(new OkHttpClient.Builder()
                .socketFactory(SNI_FRAGMENTING_SOCKET_FACTORY)
                .addInterceptor(this::interceptNtkImageWithQuic)).build();
        this.unsafeFallbackClient = baseClient(getUnsafeOkHttpClient())
                .protocols(ntkTlsFallbackProtocolsForTest())
                .build();
        this.ntkPageFastClient = fastNtkPageClient(new OkHttpClient.Builder()).build();
        this.unsafeNtkPageFastClient = fastNtkPageClient(getUnsafeOkHttpClient())
                .protocols(ntkTlsFallbackProtocolsForTest())
                .build();
        this.ntkApiFastClient = fastNtkApiClient(new OkHttpClient.Builder()).build();
        this.unsafeNtkApiFastClient = fastNtkApiClient(getUnsafeOkHttpClient())
                .protocols(ntkTlsFallbackProtocolsForTest())
                .build();
        this.wolfPageFastClient = fastWolfPageClient(new OkHttpClient.Builder()).build();
        this.unsafeWolfPageFastClient = fastWolfPageClient(getUnsafeOkHttpClient())
                .protocols(ntkTlsFallbackProtocolsForTest())
                .build();
        this.wolfSearchFastClient = fastWolfSearchClient(new OkHttpClient.Builder()).build();
        this.unsafeWolfSearchFastClient = fastWolfSearchClient(getUnsafeOkHttpClient())
                .protocols(ntkTlsFallbackProtocolsForTest())
                .build();

        //this.cfc = new HashMap<>();
        //this.client = new OkHttpClient.Builder().build();
    }

    public Context getContext() {
        return context;
    }

    public synchronized void setCookie(String k, String v){
        if(k == null || k.length() == 0)
            return;
        if(v == null) {
            cookies.remove(k);
            invalidateCookieHeaderCache();
            persistCookies();
            return;
        }
        if("cf_clearance".equalsIgnoreCase(k) && !canAcceptWebViewClearance(v))
            return;
        if(isNtkAckCookieName(k) && isExpiredNtkAckCookie(v)) {
            cookies.remove(k);
            invalidateCookieHeaderCache();
            persistCookies();
            return;
        }
        cookies.put(k, v);
        invalidateCookieHeaderCache();
        persistCookies();
        if("cf_clearance".equalsIgnoreCase(k) && v.length() > 0)
            saveClearanceToDisk();
    }

    public synchronized void setUserAgent(String userAgent) {
        if(userAgent == null || userAgent.trim().length() == 0)
            return;
        String nextAgent = userAgent.trim();
        String deviceIdentityAgent = savedNtkDeviceIdentityUserAgent();
        if(shouldPreserveNtkDeviceIdentityUserAgent(deviceIdentityAgent, nextAgent)) {
            Log.d(TAG, "ntk_device_identity_preserve_ua ignored=" + nextAgent
                    + ",kept=" + deviceIdentityAgent);
            agent = deviceIdentityAgent;
            context.getSharedPreferences("mangaView", Context.MODE_PRIVATE)
                    .edit()
                    .putString("httpUserAgent", agent)
                    .commit();
            return;
        }
        boolean changed = !nextAgent.equals(agent);
        agent = nextAgent;
        context.getSharedPreferences("mangaView", Context.MODE_PRIVATE)
                .edit()
                .putString("httpUserAgent", agent)
                .commit();
        if(changed)
            clearUserAgentBoundAccessState();
    }

    public synchronized void setNtkDeviceIdentityUserAgent(String userAgent) {
        if(userAgent == null || userAgent.trim().length() == 0)
            return;
        String nextAgent = userAgent.trim();
        boolean changed = !nextAgent.equals(agent);
        agent = nextAgent;
        if(changed)
            clearUserAgentBoundAccessState();
        context.getSharedPreferences("mangaView", Context.MODE_PRIVATE)
                .edit()
                .putString("ntkDeviceIdentityUserAgent", nextAgent)
                .putString("httpUserAgent", nextAgent)
                .commit();
    }

    private String savedNtkDeviceIdentityUserAgent() {
        try {
            SharedPreferences pref = context.getSharedPreferences("mangaView", Context.MODE_PRIVATE);
            String saved = pref.getString("ntkDeviceIdentityUserAgent", null);
            return saved == null ? "" : saved.trim();
        } catch (Exception e) {
            return "";
        }
    }

    private static boolean shouldPreserveNtkDeviceIdentityUserAgent(String savedAgent, String nextAgent) {
        if(savedAgent == null || savedAgent.length() == 0 || nextAgent == null || nextAgent.length() == 0)
            return false;
        if(savedAgent.equals(nextAgent))
            return false;
        return looksLikeEmulatorDefaultUserAgent(nextAgent) && !looksLikeEmulatorDefaultUserAgent(savedAgent);
    }

    private static boolean looksLikeEmulatorDefaultUserAgent(String userAgent) {
        String lower = userAgent == null ? "" : userAgent.toLowerCase(Locale.ROOT);
        return lower.contains("sdk_gphone")
                || lower.contains("generic")
                || lower.contains("emulator")
                || lower.contains("ranchu");
    }
    public synchronized void removeCookie(String k) {
        cookies.remove(k);
        invalidateCookieHeaderCache();
        persistCookies();
    }

    private static final String[] NTK_STRICT_FRESH_AUTH_COOKIES = new String[]{
            "ad_ack", "ad_ack_c", "ntk_ve"
    };

    private synchronized boolean removeNtkAckCookies() {
        boolean changed = false;
        for(String key : NTK_STRICT_FRESH_AUTH_COOKIES) {
            if(cookies.remove(key) != null)
                changed = true;
        }
        if(changed) {
            invalidateCookieHeaderCache();
            persistCookies();
        }
        return changed;
    }

    public synchronized void clearNtkAckStateForTest(String baseUrl) {
        clearNtkAckStateForTest(baseUrl, true);
    }

    public synchronized void clearNtkAckStateForTest(String baseUrl, boolean clearWebViewCookies) {
        removeNtkAckCookies();
        NTK_ACK_CACHE.clear();
        NTK_ACK_CHALLENGE_OKS.clear();
        NTK_ACK_CHALLENGE_HARDBLOCKS.clear();
        NTK_ACK_PROOF_REQUIREDS.clear();
        NTK_ACK_CLEARANCE_RESETS.clear();
        NTK_WEBVIEW_ACK_FLIGHTS.clear();
        NtkWebViewFallbackManager.clearRecentServerAckSuccessForTest(baseUrl);
        synchronized (ntkViewerImageUrlCacheLock) {
            ntkViewerImageUrlCache.clear();
            ntkViewerImageUrlMissCache.clear();
        }
        ntkViewerImageUrlFlights.clear();
        if(context != null) {
            NtkWebViewFallbackManager manager = NtkWebViewFallbackManager.get(context);
            manager.cancelAll();
            manager.clearCachedViewerImageUrlsForTest();
        }
        String url = baseUrl != null && baseUrl.length() > 0 ? baseUrl : NTK_WEBTOON_URL;
        LinkedHashSet<String> clearRoots = new LinkedHashSet<>();
        clearRoots.add(url);
        clearRoots.add(NTK_WEBTOON_URL);
        clearRoots.add(NTK_COMIC_URL);
        clearRoots.add("https://toonflix.app");
        try {
            Uri uri = Uri.parse(url);
            String scheme = uri.getScheme();
            String host = uri.getHost();
            if(scheme != null && host != null && scheme.length() > 0 && host.length() > 0) {
                String root = scheme + "://" + host;
                clearRoots.add(root);
                String path = uri.getPath();
                if(path != null && path.length() > 0) {
                    String normalizedPath = path.startsWith("/") ? path : "/" + path;
                    String[] parts = normalizedPath.split("/");
                    if(parts.length > 1 && parts[1].length() > 0)
                        clearRoots.add(root + "/" + parts[1]);
                    if(parts.length > 2 && parts[1].length() > 0 && parts[2].length() > 0)
                        clearRoots.add(root + "/" + parts[1] + "/" + parts[2]);
                }
            }
        } catch (Exception ignored) {
        }
        if(clearWebViewCookies) {
            try {
                CookieManager manager = CookieManager.getInstance();
                for(String root : clearRoots) {
                    for(String key : NTK_STRICT_FRESH_AUTH_COOKIES)
                        expireWebViewCookie(manager, root, key, false);
                }
                manager.flush();
            } catch (Exception e) {
                ml.melun.mangaview.report.CrashReporter.record(e);
            }
        }
        Log.d(TAG, "ntk_ack_state_clear_for_test url=" + url
                + ",roots=" + clearRoots
                + ",webViewCookies=" + clearWebViewCookies);
    }

    public synchronized boolean hasCloudflareClearance() {
        for(String key : cookies.keySet()) {
            if(!"cf_clearance".equalsIgnoreCase(key))
                continue;
            String value = cookies.get(key);
            if(value != null && value.length() > 0)
                return true;
        }
        return false;
    }

    public synchronized boolean hasNtkAccessProof() {
        return hasCloudflareClearance() && hasRecentNtkAccessVerification();
    }

    public String getLastCloudflareChallengeUrl() {
        return lastCloudflareChallengeUrl;
    }

    public boolean hasRecentCloudflareChallenge() {
        return lastCloudflareChallengeUrl != null
                && lastCloudflareChallengeUrl.length() > 0
                && System.currentTimeMillis() - lastCloudflareChallengeAt < 5 * 60 * 1000L;
    }

    public boolean hasRecentNtkHardBlock() {
        return lastNtkHardBlockUrl != null
                && lastNtkHardBlockUrl.length() > 0
                && System.currentTimeMillis() - lastNtkHardBlockAt < 5 * 60 * 1000L;
    }

    public boolean hasCloudflareChallengeSince(long timestamp) {
        return lastCloudflareChallengeUrl != null
                && lastCloudflareChallengeUrl.length() > 0
                && lastCloudflareChallengeAt >= timestamp;
    }

    public void clearLastCloudflareChallenge() {
        lastCloudflareChallengeUrl = null;
        lastCloudflareChallengeAt = 0L;
    }

    public void clearLastNtkHardBlock() {
        lastNtkHardBlockUrl = null;
        lastNtkHardBlockAt = 0L;
    }

    public void markCloudflareChallenge(String url) {
        if(url == null || url.length() == 0)
            url = getWebtoonUrl();
        lastCloudflareChallengeUrl = url;
        lastCloudflareChallengeAt = System.currentTimeMillis();
        if(isNtk()) {
            clearNtkAccessVerification();
            clearCloudflareCookies(false);
        } else {
            clearCloudflareCookies();
        }
    }

    public void markNtkHardBlock(String url) {
        markNtkHardBlock(url, null);
    }

    public void markNtkHardBlock(String url, String body) {
        if(url == null || url.length() == 0)
            url = getWebtoonUrl();
        lastNtkHardBlockUrl = url;
        lastNtkHardBlockAt = System.currentTimeMillis();
        if(NtkDeviceIdentityManager.isTrash0607Block(body)) {
            Log.d(TAG, "ntk_trash0607_device_change url=" + url);
            NtkDeviceIdentityManager.changeDeviceInfo(context, false);
        }
        markCloudflareChallenge(url);
    }

    private boolean markNtkHardBlockPreservingClearance(String url) {
        if(url == null || url.length() == 0)
            url = getWebtoonUrl();
        url = ntkPageUrlForCookieReset(url);
        lastNtkHardBlockUrl = url;
        lastNtkHardBlockAt = System.currentTimeMillis();
        lastCloudflareChallengeUrl = url;
        lastCloudflareChallengeAt = System.currentTimeMillis();
        boolean recentVerification = hasRecentNtkAccessVerification();
        boolean freshClearance = hasFreshCloudflareClearance();
        if(!recentVerification) {
            if(!NTK_ACK_HARDBLOCK_AUTO_CLEARANCE_RESET || freshClearance) {
                Log.d(TAG, "ntk_native_ack_hardblock_clearance_preserved url=" + url
                        + ",fresh=" + freshClearance);
                return false;
            }
            String resetKey = ntkAckClearanceResetKey(url);
            long now = System.currentTimeMillis();
            synchronized (NTK_ACK_CLEARANCE_RESETS) {
                Long lastResetAt = NTK_ACK_CLEARANCE_RESETS.get(resetKey);
                if(isNtkWebViewAckInFlightForScope(url)) {
                    Log.d(TAG, "ntk_native_ack_hardblock_stale_clearance_reset_suppressed_inflight url=" + url
                            + ",fresh=" + freshClearance
                            + ",ageMs=" + (lastResetAt == null ? -1L : now - lastResetAt));
                    return false;
                }
                if(lastResetAt != null && now - lastResetAt <= NTK_ACK_CLEARANCE_RESET_COOLDOWN_MS) {
                    Log.d(TAG, "ntk_native_ack_hardblock_stale_clearance_reset_suppressed url=" + url
                            + ",fresh=" + freshClearance
                            + ",ageMs=" + (now - lastResetAt));
                    return false;
                }
                NTK_ACK_CLEARANCE_RESETS.put(resetKey, now);
                clearNtkAccessVerification();
                clearCloudflareCookies(false);
                clearCloudflareWebViewCookies(url);
                Log.d(TAG, "ntk_native_ack_hardblock_stale_clearance_reset url=" + url
                        + ",fresh=" + freshClearance);
            }
            return true;
        }
        return false;
    }

    private static boolean isNtkWebViewAckInFlightForScope(String url) {
        if(url == null || url.length() == 0)
            return false;
        try {
            Uri uri = Uri.parse(url);
            String scheme = uri.getScheme();
            String host = uri.getHost();
            String path = uri.getPath();
            if(path == null || path.length() == 0)
                return false;
            String ackPath = ntkNativeAckScopePath(path);
            String baseUrl = (scheme != null && host != null) ? scheme + "://" + host : NTK_WEBTOON_URL;
            return isNtkWebViewAckInFlight(baseUrl, ackPath)
                    || isNtkWebViewAckInFlight(NTK_WEBTOON_URL, ackPath);
        } catch (Exception ignored) {
            return false;
        }
    }

    private static String ntkAckClearanceResetKey(String url) {
        if(url == null || url.length() == 0)
            return NTK_WEBTOON_URL;
        try {
            Uri uri = Uri.parse(url);
            String path = uri.getPath();
            if(path != null && path.length() > 0)
                return ntkNativeAckScopePath(path);
        } catch (Exception ignored) {
        }
        return url;
    }

    private static String ntkPageUrlForCookieReset(String url) {
        if(url == null || url.length() == 0)
            return NTK_WEBTOON_URL;
        try {
            Uri uri = Uri.parse(url);
            String host = uri.getHost();
            String path = uri.getPath();
            if(host != null && isNtkGeneratedImageCdnUrl(url)) {
                return NTK_WEBTOON_URL + (path == null || path.length() == 0 ? "" : path);
            }
        } catch (Exception ignored) {
        }
        return url;
    }

    public void setCloudflareCaptchaActive(boolean active) {
        cloudflareCaptchaActive = active;
    }

    public void markNtkAccessVerified() {
        try {
            clearLastCloudflareChallenge();
            clearLastNtkHardBlock();
            context.getSharedPreferences("mangaView", Context.MODE_PRIVATE)
                    .edit()
                    .putLong("ntkAccessVerifiedAt", System.currentTimeMillis())
                    .apply();
        } catch (Exception e) {
            ml.melun.mangaview.report.CrashReporter.record(e);
        }
    }

    public boolean hasRecentNtkAccessVerification() {
        try {
            SharedPreferences pref = context.getSharedPreferences("mangaView", Context.MODE_PRIVATE);
            long verifiedAt = pref.getLong("ntkAccessVerifiedAt", 0);
            return verifiedAt > 0 && System.currentTimeMillis() - verifiedAt < 10 * 60 * 1000L;
        } catch (Exception e) {
            return false;
        }
    }

    public void clearNtkAccessVerification() {
        try {
            context.getSharedPreferences("mangaView", Context.MODE_PRIVATE)
                    .edit()
                    .remove("ntkAccessVerifiedAt")
                    .apply();
        } catch (Exception e) {
            ml.melun.mangaview.report.CrashReporter.record(e);
        }
    }

    public synchronized void rejectCloudflareClearance(String value) {
        if(value == null || value.trim().length() == 0)
            return;
        String rejected = value.trim();
        rejectedCloudflareClearances.add(rejected);
        if(rejectedCloudflareClearances.size() > 16) {
            java.util.Iterator<String> it = rejectedCloudflareClearances.iterator();
            if(it.hasNext()) {
                it.next();
                it.remove();
            }
        }
        boolean changed = false;
        String current = cookies.get("cf_clearance");
        if(rejected.equals(current)) {
            cookies.remove("cf_clearance");
            changed = true;
        }
        if(changed) {
            invalidateCookieHeaderCache();
            persistCookies();
        }
        try {
            SharedPreferences pref = context.getSharedPreferences("mangaView", Context.MODE_PRIVATE);
            String saved = pref.getString("cfClearanceValue", null);
            if(rejected.equals(saved)) {
                pref.edit()
                        .remove("cfClearanceValue")
                        .remove("cfClearanceExpireAt")
                        .remove("ntkAccessVerifiedAt")
                        .apply();
            }
        } catch (Exception e) {
            ml.melun.mangaview.report.CrashReporter.record(e);
        }
    }

    private synchronized void clearUserAgentBoundAccessState() {
        boolean changed = false;
        for(String key : new ArrayList<>(cookies.keySet())) {
            String lower = key.toLowerCase(Locale.ROOT);
            if(lower.startsWith("cf_") || "__cf_bm".equals(lower)) {
                cookies.remove(key);
                changed = true;
            }
        }
        if(changed) {
            invalidateCookieHeaderCache();
            persistCookies();
        }
        context.getSharedPreferences("mangaView", Context.MODE_PRIVATE)
                .edit()
                .remove("cfClearanceValue")
                .remove("cfClearanceExpireAt")
                .remove("ntkAccessVerifiedAt")
                .apply();
        clearLastCloudflareChallenge();
        clearLastNtkHardBlock();
        clearPageCache();
    }

    public synchronized boolean hasFreshCloudflareClearance() {
        if(!hasCloudflareClearance())
            return false;
        long remaining = cloudflareClearanceRemainingMs();
        return remaining > 5 * 60 * 1000L;
    }

    private long cloudflareClearanceRemainingMs() {
        try {
            SharedPreferences pref = context.getSharedPreferences("mangaView", Context.MODE_PRIVATE);
            long expireAt = pref.getLong("cfClearanceExpireAt", 0);
            return expireAt - System.currentTimeMillis();
        } catch (Exception e) {
            return 0;
        }
    }

    public void saveClearanceToDisk() {
        try {
            String clearance = cookies.get("cf_clearance");
            if(clearance == null || clearance.length() == 0)
                return;
            long expireAt = System.currentTimeMillis() + 45 * 60 * 1000L; // 45 minutes
            context.getSharedPreferences("mangaView", Context.MODE_PRIVATE)
                    .edit()
                    .putString("cfClearanceValue", clearance)
                    .putLong("cfClearanceExpireAt", expireAt)
                    .apply();
        } catch (Exception e) {
            ml.melun.mangaview.report.CrashReporter.record(e);
        }
    }

    public synchronized void restoreClearanceFromDisk() {
        try {
            SharedPreferences pref = context.getSharedPreferences("mangaView", Context.MODE_PRIVATE);
            String value = pref.getString("cfClearanceValue", null);
            long expireAt = pref.getLong("cfClearanceExpireAt", 0);
            if(!shouldApplyRestoredClearance(cookies.get("cf_clearance"), value, expireAt, System.currentTimeMillis()))
                return;
            if(!canAcceptWebViewClearance(value))
                return;
            cookies.put("cf_clearance", value);
            invalidateCookieHeaderCache();
            persistCookies();
            Log.d(TAG, "ntk_clearance_restored_from_disk remainingMs=" + (expireAt - System.currentTimeMillis()));
        } catch (Exception e) {
            ml.melun.mangaview.report.CrashReporter.record(e);
        }
    }

    static boolean shouldApplyRestoredClearanceForTest(String currentValue, String restoredValue, long expireAt, long now) {
        return shouldApplyRestoredClearance(currentValue, restoredValue, expireAt, now);
    }

    private static boolean shouldApplyRestoredClearance(String currentValue, String restoredValue, long expireAt, long now) {
        if(restoredValue == null || restoredValue.length() == 0 || expireAt <= now)
            return false;
        return !restoredValue.equals(currentValue);
    }

    public boolean isClearanceExpired() {
        try {
            SharedPreferences pref = context.getSharedPreferences("mangaView", Context.MODE_PRIVATE);
            long expireAt = pref.getLong("cfClearanceExpireAt", 0);
            if(expireAt == 0)
                return true;
            return expireAt <= System.currentTimeMillis();
        } catch (Exception e) {
            return true;
        }
    }
    public synchronized void clearCloudflareCookies() {
        clearCloudflareCookies(true);
    }

    private synchronized void clearCloudflareCookies(boolean preserveFreshNtkClearance) {
        boolean changed = false;
        boolean keepNtkClearance = shouldKeepNtkClearanceOnChallenge(isNtk(),
                hasRecentNtkAccessVerification(), hasFreshCloudflareClearance(),
                preserveFreshNtkClearance);
        for(String key : new ArrayList<>(cookies.keySet())) {
            String lower = key.toLowerCase(Locale.ROOT);
            if(keepNtkClearance && "cf_clearance".equals(lower))
                continue;
            if(lower.startsWith("cf_") || "__cf_bm".equals(lower)) {
                cookies.remove(key);
                changed = true;
            }
        }
        if(changed) {
            invalidateCookieHeaderCache();
                persistCookies();
        }
        if(!keepNtkClearance) {
            context.getSharedPreferences("mangaView", Context.MODE_PRIVATE)
                    .edit()
                    .remove("cfClearanceValue")
                    .remove("cfClearanceExpireAt")
                    .apply();
        }
    }

    static boolean shouldKeepNtkClearanceOnChallengeForTest(boolean ntk, boolean recentVerification,
                                                            boolean freshClearance,
                                                            boolean preserveFreshClearance) {
        return shouldKeepNtkClearanceOnChallenge(ntk, recentVerification, freshClearance,
                preserveFreshClearance);
    }

    private static boolean shouldKeepNtkClearanceOnChallenge(boolean ntk, boolean recentVerification,
                                                             boolean freshClearance,
                                                             boolean preserveFreshClearance) {
        return ntk && (recentVerification || preserveFreshClearance && freshClearance);
    }

    public void clearCloudflareWebViewCookies(String... urls) {
        clearCloudflareWebViewCookiesInternal(false, urls);
    }

    public void clearCloudflareWebViewCookiesAggressively(String... urls) {
        clearCloudflareWebViewCookiesInternal(true, urls);
    }

    private void clearCloudflareWebViewCookiesInternal(boolean resetStoreIfStillPresent,
                                                      String... urls) {
        try {
            CookieManager manager = CookieManager.getInstance();
            if(urls == null)
                return;
            LinkedHashSet<String> clearUrls = new LinkedHashSet<>();
            for(String url : urls)
                addCloudflareCookieClearUrls(clearUrls, url);
            LinkedHashSet<String> names = new LinkedHashSet<>();
            names.add("cf_clearance");
            names.add("__cf_bm");
            names.add("cf_chl_rc_i");
            names.add("cf_chl_rc_ni");
            names.add("cf_chl_rc_m");
            if(resetStoreIfStillPresent) {
                manager.removeSessionCookies(null);
                manager.removeAllCookies(null);
                manager.flush();
                clearCloudflareCookies(false);
                clearNtkAccessVerification();
                Log.d(TAG, "ntk_cookie_expire_webview_cf urls=" + clearUrls.size()
                        + ",names=" + names
                        + ",beforeCf=skipped"
                        + ",afterCf=skipped"
                        + ",storeReset=true"
                        + ",fastReset=true"
                        + ",unchecked=true");
                return;
            }
            for(String url : clearUrls) {
                String cookieHeader = safeWebViewCookieHeader(manager, url);
                if(cookieHeader == null || cookieHeader.length() == 0)
                    continue;
                Map<String, String> webViewCookies = parseCookieHeader(cookieHeader);
                for(String key : webViewCookies.keySet()) {
                    String lower = key == null ? "" : key.toLowerCase(Locale.ROOT);
                    if(lower.startsWith("cf_") || "__cf_bm".equals(lower))
                        names.add(key);
                }
            }
            boolean beforeCf = false;
            for(String url : clearUrls) {
                if(hasCookieName(safeWebViewCookieHeader(manager, url), "cf_clearance")) {
                    beforeCf = true;
                    break;
                }
            }
            if(beforeCf && resetStoreIfStillPresent) {
                manager.removeSessionCookies(null);
                manager.removeAllCookies(null);
                manager.flush();
                clearCloudflareCookies(false);
                clearNtkAccessVerification();
                boolean afterCf = false;
                for(String url : clearUrls) {
                    if(hasCookieName(safeWebViewCookieHeader(manager, url), "cf_clearance")) {
                        afterCf = true;
                        break;
                    }
                }
                Log.d(TAG, "ntk_cookie_expire_webview_cf urls=" + clearUrls.size()
                        + ",names=" + names
                        + ",beforeCf=" + beforeCf
                        + ",afterCf=" + afterCf
                        + ",storeReset=true"
                        + ",fastReset=true");
                return;
            }
            for(String url : clearUrls) {
                for(String name : names)
                    expireWebViewCookie(manager, url, name, false);
            }
            manager.flush();
            clearCloudflareCookies(false);
            clearNtkAccessVerification();
            boolean afterCf = false;
            for(String url : clearUrls) {
                if(hasCookieName(safeWebViewCookieHeader(manager, url), "cf_clearance")) {
                    afterCf = true;
                    break;
                }
            }
            boolean storeReset = false;
            if(afterCf && resetStoreIfStillPresent) {
                try {
                    manager.removeSessionCookies(null);
                    manager.removeAllCookies(null);
                    manager.flush();
                    storeReset = true;
                    afterCf = false;
                    for(String url : clearUrls) {
                        if(hasCookieName(safeWebViewCookieHeader(manager, url), "cf_clearance")) {
                            afterCf = true;
                            break;
                        }
                    }
                } catch (Exception e) {
                    ml.melun.mangaview.report.CrashReporter.record(e);
                }
            }
            Log.d(TAG, "ntk_cookie_expire_webview_cf urls=" + clearUrls.size()
                    + ",names=" + names
                    + ",beforeCf=" + beforeCf
                    + ",afterCf=" + afterCf
                    + ",storeReset=" + storeReset);
        } catch (Exception e) {
            ml.melun.mangaview.report.CrashReporter.record(e);
        }
    }

    private static String safeWebViewCookieHeader(CookieManager manager, String url) {
        if(manager == null || url == null || url.length() == 0)
            return "";
        try {
            String value = manager.getCookie(url);
            return value == null ? "" : value;
        } catch (Exception ignored) {
            return "";
        }
    }

    private static void addCloudflareCookieClearUrls(LinkedHashSet<String> out, String url) {
        if(out == null || url == null || url.length() == 0)
            return;
        out.add(url);
        try {
            Uri uri = Uri.parse(url);
            String scheme = uri.getScheme();
            String host = uri.getHost();
            if(scheme == null || host == null || scheme.length() == 0 || host.length() == 0)
                return;
            String root = scheme + "://" + host;
            out.add(root);
            String path = uri.getPath();
            if(path != null && path.length() > 0) {
                String normalizedPath = path.startsWith("/") ? path : "/" + path;
                while(normalizedPath.length() > 1) {
                    out.add(root + normalizedPath);
                    int slash = normalizedPath.lastIndexOf('/');
                    if(slash <= 0)
                        break;
                    normalizedPath = normalizedPath.substring(0, slash);
                }
            }
        } catch (Exception ignored) {
        }
    }

    private static boolean hasCookieName(String cookieHeader, String name) {
        if(cookieHeader == null || cookieHeader.length() == 0 || name == null || name.length() == 0)
            return false;
        Map<String, String> parsed = parseCookieHeader(cookieHeader);
        for(String key : parsed.keySet()) {
            if(name.equalsIgnoreCase(key))
                return true;
        }
        return false;
    }

    private void expireCloudflareWebViewCookie(CookieManager manager, String url, String name) {
        String[] paths = new String[]{"/", "/webtoon", "/manhwa", "/comic"};
        String[] domains = new String[]{
                null,
                NTK_HOST, "." + NTK_HOST,
                PREVIOUS_NTK_HOST, "." + PREVIOUS_NTK_HOST,
                OLDER_NTK_HOST, "." + OLDER_NTK_HOST,
                OLDEST_NTK_HOST, "." + OLDEST_NTK_HOST,
                LEGACY_NTK_HOST, "." + LEGACY_NTK_HOST
        };
        for(String path : paths) {
            for(String domain : domains) {
                String cookie = name
                        + "=; Max-Age=0; Expires=Thu, 01 Jan 1970 00:00:00 GMT; Path="
                        + path;
                if(domain != null)
                    cookie += "; Domain=" + domain;
                manager.setCookie(url, cookie);
                manager.setCookie(url, cookie + "; Secure");
                manager.setCookie(url, cookie + "; Secure; SameSite=None");
            }
        }
    }

    public synchronized void resetCookie(){
        this.cookies = new HashMap<>();
        this.cookieSyncAt = new HashMap<>();
        this.rejectedCloudflareClearances.clear();
        NTK_ACK_CACHE.clear();
        NTK_ACK_PROOF_REQUIREDS.clear();
        NTK_WEBVIEW_ACK_FLIGHTS.clear();
        synchronized (ntkViewerImageUrlCacheLock) {
            ntkViewerImageUrlCache.clear();
        }
        invalidateCookieHeaderCache();
        persistCookies();
    }

    public void clearAllWebViewData() {
        try {
            NtkWebViewFallbackManager.get(context).cancelAll();
            NtkWebViewFallbackManager.instanceRef = null;
        } catch (Exception e) {
            ml.melun.mangaview.report.CrashReporter.record(e);
        }
        try {
            CookieManager.getInstance().removeAllCookies(null);
            CookieManager.getInstance().flush();
        } catch (Exception e) {
            ml.melun.mangaview.report.CrashReporter.record(e);
        }
        try {
            WebStorage.getInstance().deleteAllData();
        } catch (Exception e) {
            ml.melun.mangaview.report.CrashReporter.record(e);
        }
        try {
            WebViewDatabase db = WebViewDatabase.getInstance(context);
            db.clearHttpAuthUsernamePassword();
            db.clearFormData();
        } catch (Exception e) {
            ml.melun.mangaview.report.CrashReporter.record(e);
        }
        try {
            android.webkit.WebView webView = new android.webkit.WebView(context);
            webView.clearCache(true);
            webView.clearHistory();
            webView.destroy();
        } catch (Exception e) {
            ml.melun.mangaview.report.CrashReporter.record(e);
        }
        try {
            clearPageCache();
        } catch (Exception e) {
            ml.melun.mangaview.report.CrashReporter.record(e);
        }
        try {
            context.deleteDatabase("webview.db");
            context.deleteDatabase("webviewCache.db");
            context.deleteDatabase("Web Data");
            context.deleteDatabase("Cookies");
        } catch (Exception e) {
            ml.melun.mangaview.report.CrashReporter.record(e);
        }
        try {
            java.io.File appWebView = new java.io.File(context.getDataDir(), "app_webview");
            if(appWebView.exists() && appWebView.isDirectory()) {
                deleteDir(appWebView);
            }
        } catch (Exception e) {
            ml.melun.mangaview.report.CrashReporter.record(e);
        }
        try {
            java.io.File httpCache = new java.io.File(context.getCacheDir(), "http");
            if(httpCache.exists() && httpCache.isDirectory()) {
                deleteDir(httpCache);
            }
        } catch (Exception e) {
            ml.melun.mangaview.report.CrashReporter.record(e);
        }
        try {
            context.getSharedPreferences("mangaView", Context.MODE_PRIVATE)
                    .edit()
                    .remove("cfClearanceValue")
                    .remove("cfClearanceExpireAt")
                    .remove("ntkAccessVerifiedAt")
                    .remove("httpUserAgent")
                    .remove("httpCookies")
                    .commit();
        } catch (Exception e) {
            ml.melun.mangaview.report.CrashReporter.record(e);
        }
    }

    private static void deleteDir(java.io.File dir) {
        java.io.File[] files = dir.listFiles();
        if(files != null) {
            for(java.io.File file : files) {
                if(file.isDirectory())
                    deleteDir(file);
                else
                    file.delete();
            }
        }
        dir.delete();
    }

    public void syncCookiesFromWebView(String url){
        syncCookiesFromWebView(url, false);
    }

    public void syncCookiesFromWebView(String url, boolean force){
        long startedAt = System.currentTimeMillis();
        boolean ntkUrl = isNtkUrl(url);
        try {
            if(url == null || url.length() == 0)
                return;
            long now = System.currentTimeMillis();
            synchronized (this) {
                Long lastSync = cookieSyncAt.get(url);
                if(!force && lastSync != null && now - lastSync < COOKIE_SYNC_INTERVAL_MS)
                    return;
                cookieSyncAt.put(url, now);
            }

            String cookieStr = CookieManager.getInstance().getCookie(url);
            if(cookieStr == null || cookieStr.length() == 0)
                return;
            Map<String, String> webViewCookies = parseCookieHeader(cookieStr);
            if(webViewCookies.size() == 0)
                return;

            synchronized (this) {
                boolean changed = false;
                boolean clearanceChanged = false;
                for(String key : webViewCookies.keySet()) {
                    String value = webViewCookies.get(key);
                    if(isNtkAckCookieName(key) && isExpiredNtkAckCookie(value)) {
                        if(cookies.remove(key) != null)
                            changed = true;
                        expireWebViewCookie(url, key);
                        continue;
                    }
                    if("cf_clearance".equalsIgnoreCase(key) && !canAcceptWebViewClearance(value))
                        continue;
                    if(!value.equals(cookies.get(key))) {
                        cookies.put(key, value);
                        changed = true;
                        if("cf_clearance".equalsIgnoreCase(key))
                            clearanceChanged = true;
                    }
                }
                if(changed) {
                    invalidateCookieHeaderCache();
                    persistCookies();
                }
                if(clearanceChanged)
                    saveClearanceToDisk();
            }
        } catch (Exception e) {
            ml.melun.mangaview.report.CrashReporter.record(e);
        } finally {
            if(ntkUrl)
                ViewerWarmupManager.logMetric("ntk_cookie_sync_ms", System.currentTimeMillis() - startedAt);
        }
    }

    static Map<String, String> parseCookieHeaderForTest(String cookieStr) {
        return parseCookieHeader(cookieStr);
    }

    private void expireWebViewCookie(String url, String key) {
        expireWebViewCookie(CookieManager.getInstance(), url, key, true);
    }

    private void expireWebViewCookie(CookieManager manager, String url, String key, boolean flush) {
        if(url == null || url.length() == 0 || key == null || key.length() == 0)
            return;
        try {
            ArrayList<String> pathList = new ArrayList<>();
            pathList.add("/");
            pathList.add("/manhwa");
            pathList.add("/webtoon");
            pathList.add("/api");
            String urlPath = Uri.parse(url).getPath();
            if(urlPath != null && urlPath.length() > 0) {
                String normalizedPath = urlPath.startsWith("/") ? urlPath : "/" + urlPath;
                while(normalizedPath.length() > 1) {
                    if(!pathList.contains(normalizedPath))
                        pathList.add(normalizedPath);
                    int slash = normalizedPath.lastIndexOf('/');
                    if(slash <= 0)
                        break;
                    normalizedPath = normalizedPath.substring(0, slash);
                }
            }
            String[] expires = new String[]{
                    key + "=; Max-Age=0; Expires=Thu, 01 Jan 1970 00:00:00 GMT; Path=",
                    key + "=deleted; Max-Age=0; Expires=Thu, 01 Jan 1970 00:00:00 GMT; Path="
            };
            String host = Uri.parse(url).getHost();
            for(String path : pathList) {
                for(String expire : expires) {
                    manager.setCookie(url, expire + path);
                    if(host != null && host.length() > 0) {
                        manager.setCookie(url, expire + path + "; Domain=" + host);
                        manager.setCookie(url, expire + path + "; Domain=." + host);
                    }
                }
            }
            if(flush)
                manager.flush();
        } catch(Exception e) {
            if(Log.isLoggable(TAG, Log.DEBUG))
                Log.d(TAG, "ntk_cookie_expire_webview_error=" + e);
        }
    }

    private static Map<String, String> parseCookieHeader(String cookieStr) {
        Map<String, String> webViewCookies = new HashMap<>();
        if(cookieStr == null || cookieStr.length() == 0)
            return webViewCookies;
        int start = 0;
        while(start < cookieStr.length()) {
            int end = nextCookieSeparator(cookieStr, start);
            String part = cookieStr.substring(start, end).trim();
            int eq = part.indexOf("=");
            if(eq > 0)
                webViewCookies.put(part.substring(0, eq), part.substring(eq + 1));
            start = end + 1;
        }
        return webViewCookies;
    }

    private static int nextCookieSeparator(String cookieStr, int start) {
        int semi = cookieStr.indexOf(';', start);
        int newline = cookieStr.indexOf('\n', start);
        int carriage = cookieStr.indexOf('\r', start);
        int end = cookieStr.length();
        if(semi >= 0 && semi < end)
            end = semi;
        if(newline >= 0 && newline < end)
            end = newline;
        if(carriage >= 0 && carriage < end)
            end = carriage;
        return end;
    }

    private synchronized boolean canAcceptWebViewClearance(String value) {
        String trimmed = value == null ? null : value.trim();
        return trimmed != null
                && trimmed.length() >= 20
                && !"deleted".equalsIgnoreCase(trimmed)
                && !"null".equalsIgnoreCase(trimmed)
                && !rejectedCloudflareClearances.contains(trimmed);
    }

    private synchronized void loadSavedCookies(){
        try {
            SharedPreferences pref = context.getSharedPreferences("mangaView", Context.MODE_PRIVATE);
            String saved = pref.getString("httpCookies", "{}");
            JSONObject obj = new JSONObject(saved);
            for(java.util.Iterator<String> it = obj.keys(); it.hasNext();){
                String k = it.next();
                cookies.put(k, obj.getString(k));
            }
            if(removeExpiredNtkAckCookiesLocked())
                persistCookies();
            invalidateCookieHeaderCache();
        } catch (Exception e) {
            ml.melun.mangaview.report.CrashReporter.record(e);
        }
    }

    private synchronized void loadSavedUserAgent(){
        try {
            SharedPreferences pref = context.getSharedPreferences("mangaView", Context.MODE_PRIVATE);
            String identity = pref.getString("ntkDeviceIdentityUserAgent", null);
            String saved = pref.getString("httpUserAgent", null);
            if(identity != null && identity.trim().length() > 0
                    && shouldPreserveNtkDeviceIdentityUserAgent(identity.trim(), saved == null ? "" : saved.trim()))
                saved = identity;
            if(saved != null && saved.trim().length() > 0)
                agent = saved.trim();
        } catch (Exception e) {
            ml.melun.mangaview.report.CrashReporter.record(e);
        }
    }

    private synchronized void persistCookies(){
        try {
            JSONObject obj = new JSONObject();
            for(String k : cookies.keySet())
                obj.put(k, cookies.get(k));
            context.getSharedPreferences("mangaView", Context.MODE_PRIVATE)
                    .edit()
                    .putString("httpCookies", obj.toString())
                    .apply();
        } catch (Exception e) {
            ml.melun.mangaview.report.CrashReporter.record(e);
        }
    }

    private synchronized void storeResponseCookies(Response response){
        if(response == null)
            return;
        boolean changed = false;
        boolean clearanceChanged = false;
        for(String c : response.headers("Set-Cookie")){
            int eq = c.indexOf("=");
            int semi = c.indexOf(";");
            if(eq <= 0)
                continue;
            if(semi < 0)
                semi = c.length();
            String key = c.substring(0, eq);
            String value = c.substring(eq + 1, semi);
            if(isNtkAckCookieName(key) && isExpiredNtkAckCookie(value)) {
                if(cookies.remove(key) != null)
                    changed = true;
                continue;
            }
            if(!value.equals(cookies.get(key))) {
                cookies.put(key, value);
                changed = true;
                if("cf_clearance".equalsIgnoreCase(key))
                    clearanceChanged = true;
            }
        }
        if(changed) {
            invalidateCookieHeaderCache();
            persistCookies();
        }
        if(clearanceChanged)
            saveClearanceToDisk();
    }

    public synchronized String getCookie(String k){
        return cookies.get(k);
    }

    public synchronized String getCookieHeader() {
        if(removeExpiredNtkAckCookiesLocked())
            persistCookies();
        if(cookieHeaderCache != null)
            return cookieHeaderCache;
        StringBuilder builder = new StringBuilder();
        for(String key : cookies.keySet()) {
            if(builder.length() > 0)
                builder.append("; ");
            builder.append(key).append('=').append(cookies.get(key));
        }
        cookieHeaderCache = builder.toString();
        return cookieHeaderCache;
    }

    private synchronized String getCookieHeaderForNtkPath(String path) {
        if(removeExpiredNtkAckCookiesLocked())
            persistCookies();
        StringBuilder builder = new StringBuilder();
        for(String key : cookies.keySet()) {
            String value = cookies.get(key);
            if(isNtkAckCookieName(key) && !ntkAckCookieUsableForPath(key, value, path))
                continue;
            if(builder.length() > 0)
                builder.append("; ");
            builder.append(key).append('=').append(value);
        }
        return builder.toString();
    }

    private synchronized String getCookieHeaderForNtkPath(String path, Map<String, String> customCookie) {
        StringBuilder builder = new StringBuilder(getCookieHeaderForNtkPath(path));
        if(customCookie != null) {
            for(String key : customCookie.keySet()) {
                if(key == null || key.length() == 0)
                    continue;
                String value = customCookie.get(key);
                if(value == null)
                    continue;
                if(builder.length() > 0)
                    builder.append("; ");
                builder.append(key).append('=').append(value);
            }
        }
        return builder.toString();
    }

    private String getMergedNtkCookieHeaderForUrl(String baseUrl, String path) {
        String nativeHeader = getCookieHeaderForNtkPath(path);
        String webViewHeader = "";
        try {
            CookieManager manager = CookieManager.getInstance();
            String page = baseUrl + ntkNativeAckScopePath(path);
            webViewHeader = safeWebViewCookieHeader(manager, page);
            if(webViewHeader == null || webViewHeader.length() == 0)
                webViewHeader = safeWebViewCookieHeader(manager, baseUrl);
        } catch (Exception ignored) {
        }
        Map<String, String> merged = parseCookieHeader(nativeHeader);
        if(webViewHeader != null && webViewHeader.length() > 0)
            merged.putAll(parseCookieHeader(webViewHeader));
        String scopedAdAck = NtkWebViewFallbackManager.scopedAdAckForPath(path);
        if(scopedAdAck.length() > 0) {
            String previous = merged.get("ad_ack");
            if(previous == null || !previous.equals(scopedAdAck))
                Log.d(TAG, "ntk_images_api_scoped_ad_ack_restore path=" + path
                        + ",hadPrevious=" + (previous != null && previous.length() > 0));
            merged.put("ad_ack", scopedAdAck);
        }
        String scopedAdAckC = NtkWebViewFallbackManager.scopedAdAckCForPath(path);
        if(scopedAdAckC.length() > 0) {
            String previous = merged.get("ad_ack_c");
            if(previous == null || !previous.equals(scopedAdAckC))
                Log.d(TAG, "ntk_images_api_scoped_ad_ack_c_restore path=" + path
                        + ",hadPrevious=" + (previous != null && previous.length() > 0));
            merged.put("ad_ack_c", scopedAdAckC);
        }
        StringBuilder builder = new StringBuilder();
        for(String key : merged.keySet()) {
            String value = merged.get(key);
            if(key == null || key.length() == 0 || value == null)
                continue;
            if(isNtkAckCookieName(key) && !ntkAckCookieUsableForPath(key, value, path))
                continue;
            if(builder.length() > 0)
                builder.append("; ");
            builder.append(key).append('=').append(value);
        }
        return builder.toString();
    }

    public String getMergedNtkViewerImageCookieHeaderForPath(String path) {
        if(path == null || path.length() == 0)
            return getCookieHeader();
        String baseUrl = getUrl(path);
        if(baseUrl == null || baseUrl.length() == 0)
            return getCookieHeaderForNtkPath(path);
        return getMergedNtkCookieHeaderForUrl(baseUrl, path);
    }

    private void invalidateCookieHeaderCache() {
        cookieHeaderCache = null;
    }

    public <T> T runWithRequestGroup(RequestGroup requestGroup, RequestWork<T> work) throws Exception {
        RequestGroup previous = currentRequestGroup.get();
        currentRequestGroup.set(requestGroup);
        try {
            return work.run();
        } finally {
            if(previous == null)
                currentRequestGroup.remove();
            else
                currentRequestGroup.set(previous);
        }
    }

    public <T> T runWithFetchMode(FetchMode fetchMode, RequestWork<T> work) throws Exception {
        FetchMode previous = currentFetchMode.get();
        currentFetchMode.set(fetchMode == null ? FetchMode.ALLOW_SHARED_WEBVIEW : fetchMode);
        try {
            return work.run();
        } finally {
            if(previous == null)
                currentFetchMode.remove();
            else
                currentFetchMode.set(previous);
        }
    }

    public <T> T runWithSitePreset(String comicUrl, String webtoonUrl, RequestWork<T> work) throws Exception {
        SiteOverride previous = currentSiteOverride.get();
        currentSiteOverride.set(new SiteOverride(
                siteOverrideUrl(comicUrl, DEFAULT_COMIC_URL),
                siteOverrideUrl(webtoonUrl, WEBTOON_URL)));
        try {
            return work.run();
        } finally {
            if(previous == null)
                currentSiteOverride.remove();
            else
                currentSiteOverride.set(previous);
        }
    }

    public boolean isDirectOnlyFetchMode() {
        FetchMode mode = effectiveFetchMode(FetchMode.ALLOW_SHARED_WEBVIEW);
        return mode == FetchMode.DIRECT_ONLY || mode == FetchMode.CACHE_ONLY;
    }

    public String buildNtkNetworkDiagnosticReport(String networkSummary) {
        StringBuilder report = new StringBuilder();
        long startedAt = System.currentTimeMillis();
        try {
            String root = getRootUrl(getWebtoonUrl());
            String rootHost = hostOf(root);
            boolean ntkSite = p != null && p.isNtkSite();
            appendDiagnosticLine(report, "time", new java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(new Date()));
            appendDiagnosticLine(report, "network", emptyToUnknown(networkSummary));
            appendDiagnosticLine(report, "active_site", ntkSite ? "NTK" : "WFWF");
            appendDiagnosticLine(report, "webtoon_url", getWebtoonUrl());
            appendDiagnosticLine(report, "comic_url", getComicUrl());
            appendDiagnosticLine(report, "root", root);
            if(ntkSite) {
                appendDiagnosticLine(report, "has_cf_clearance", String.valueOf(hasCloudflareClearance()));
                appendDiagnosticLine(report, "recent_ntk_verified", String.valueOf(hasRecentNtkAccessVerification()));
            }
            report.append('\n');

            appendSystemDnsDiagnostic(report, rootHost);
            appendAppDnsDiagnostic(report, rootHost);
            appendDohDiagnostic(report, rootHost);
            if(ntkSite && !NTK_HOST.equalsIgnoreCase(rootHost)) {
                appendAppDnsDiagnostic(report, NTK_HOST);
                appendDohDiagnostic(report, NTK_HOST);
            }
            if(ntkSite) {
                appendProxyDiagnostic(report, rootHost);
                appendProxyDiagnostic(report, "img." + NTK_HOST);
            } else {
                appendAppDnsDiagnostic(report, "i1.imgcloud18.com");
                appendDohDiagnostic(report, "i1.imgcloud18.com");
            }
            report.append('\n');

            if(ntkSite) {
                appendNtkQuicDiagnostic(report, root);
                appendNtkApiDiagnostic(report, root);
            } else {
                appendWfwfHttpDiagnostic(report, root);
            }
            report.append('\n');
            appendDiagnosticLine(report, "elapsed_ms", String.valueOf(System.currentTimeMillis() - startedAt));
            appendDiagnosticLine(report, "interpretation", diagnosticInterpretation(report.toString()));
        } catch (Exception e) {
            appendDiagnosticLine(report, "fatal", exceptionSummary(e));
        }
        return report.toString();
    }

    String probeNtkDetailTransportVariantsForTest(String path) {
        String normalized = normalizePath(path);
        String baseUrl = getBaseUrl(normalized);
        StringBuilder report = new StringBuilder();
        appendDiagnosticLine(report, "path", normalized);
        appendDiagnosticLine(report, "base", baseUrl);
        try {
            ensureNumberedDomain(false);
            baseUrl = getBaseUrl(normalized);
        } catch (Exception e) {
            appendDiagnosticLine(report, "ensure_domain", exceptionSummary(e));
        }

        Map<String, String> mobileDocument = buildHeaders(baseUrl, true, null);
        Map<String, String> desktopDocument = buildHeaders(baseUrl, true, null);
        desktopDocument.put("User-Agent", NTK_DESKTOP_DOCUMENT_UA);
        desktopDocument.put("sec-ch-ua", "\"Chromium\";v=\"148\", \"Google Chrome\";v=\"148\", \"Not_A Brand\";v=\"24\"");
        desktopDocument.put("sec-ch-ua-mobile", "?0");
        desktopDocument.put("sec-ch-ua-platform", "\"Windows\"");
        desktopDocument.put("Sec-Fetch-Dest", "document");
        desktopDocument.put("Sec-Fetch-Mode", "navigate");
        desktopDocument.put("Sec-Fetch-Site", "same-origin");
        Map<String, String> desktopFromCategory = new HashMap<>(desktopDocument);
        desktopFromCategory.put("Referer", baseUrl + "/manhwa");
        Map<String, String> rscHeaders = new HashMap<>();
        rscHeaders.put("accept", "text/x-component");
        rscHeaders.put("rsc", "1");
        rscHeaders.put("next-url", normalized);
        rscHeaders.put("origin", baseUrl);
        rscHeaders.put("referer", baseUrl + normalized);

        probeNtkDetailOkHttpVariant(report, "okhttp_mobile_doc", baseUrl, normalized, mobileDocument);
        probeNtkDetailOkHttpVariant(report, "okhttp_desktop_doc", baseUrl, normalized, desktopDocument);
        probeNtkDetailOkHttpVariant(report, "okhttp_desktop_from_category", baseUrl, normalized, desktopFromCategory);
        probeNtkDetailOkHttpVariant(report, "okhttp_desktop_trailing_slash", baseUrl, normalized + "/", desktopFromCategory);
        probeNtkDetailHttp1Variant(report, "http1_desktop_from_category", baseUrl, normalized, desktopFromCategory);
        probeNtkDetailTls12Variant(report, "tls12_http1_desktop_from_category", baseUrl, normalized, desktopFromCategory);
        probeNtkDetailIpHostVariant(report, "ip_host_http1_desktop", baseUrl, normalized, desktopFromCategory);
        probeNtkDetailEngineVariant(report, "engine_quic_desktop", baseUrl, normalized, desktopDocument, true);
        probeNtkDetailEngineVariant(report, "engine_http2_desktop", baseUrl, normalized, desktopDocument, false);
        probeNtkDetailEngineVariant(report, "engine_quic_rsc", baseUrl, normalized, rscHeaders, true);
        return report.toString();
    }

    private void probeNtkDetailOkHttpVariant(StringBuilder report, String name, String baseUrl,
                                             String path, Map<String, String> headers) {
        long startedAt = System.currentTimeMillis();
        Response response = null;
        try {
            response = get(baseUrl + path, headers, true);
            int code = response == null ? 0 : response.code();
            String body = response == null || response.body() == null ? "" : response.body().string();
            appendDiagnosticLine(report, name, "code=" + code
                    + ",ms=" + (System.currentTimeMillis() - startedAt)
                    + ",len=" + body.length()
                    + ",sample=" + abbreviateLogSample(body, 120));
        } catch (Exception e) {
            appendDiagnosticLine(report, name, "fail "
                    + (System.currentTimeMillis() - startedAt) + "ms " + exceptionSummary(e));
        } finally {
            if(response != null)
                response.close();
        }
    }

    private void probeNtkDetailHttp1Variant(StringBuilder report, String name, String baseUrl,
                                            String path, Map<String, String> headers) {
        long startedAt = System.currentTimeMillis();
        Response response = null;
        try {
            OkHttpClient http1Client = baseClient(new OkHttpClient.Builder()
                    .socketFactory(SNI_FRAGMENTING_SOCKET_FACTORY))
                    .protocols(ntkTlsFallbackProtocolsForTest())
                    .connectTimeout(NTK_PAGE_DIRECT_TIMEOUT_MS, TimeUnit.MILLISECONDS)
                    .readTimeout(NTK_PAGE_DIRECT_TIMEOUT_MS, TimeUnit.MILLISECONDS)
                    .callTimeout(NTK_PAGE_DIRECT_TIMEOUT_MS, TimeUnit.MILLISECONDS)
                    .build();
            Request.Builder builder = new Request.Builder()
                    .url(baseUrl + path)
                    .get();
            if(headers != null) {
                for(String key : headers.keySet())
                    builder.addHeader(key, headers.get(key));
            }
            response = http1Client.newCall(builder.build()).execute();
            int code = response.code();
            String body = response.body() == null ? "" : response.body().string();
            appendDiagnosticLine(report, name, "code=" + code
                    + ",ms=" + (System.currentTimeMillis() - startedAt)
                    + ",len=" + body.length()
                    + ",sample=" + abbreviateLogSample(body, 120));
        } catch (Exception e) {
            appendDiagnosticLine(report, name, "fail "
                    + (System.currentTimeMillis() - startedAt) + "ms " + exceptionSummary(e));
        } finally {
            if(response != null)
                response.close();
        }
    }

    private void probeNtkDetailIpHostVariant(StringBuilder report, String name, String baseUrl,
                                             String path, Map<String, String> headers) {
        long startedAt = System.currentTimeMillis();
        Response response = null;
        try {
            String host = hostOf(baseUrl);
            List<InetAddress> addresses = NETWORK_RESILIENT_DNS.lookup(host);
            if(addresses == null || addresses.isEmpty()) {
                appendDiagnosticLine(report, name, "fail no_ip");
                return;
            }
            String ip = addresses.get(0).getHostAddress();
            OkHttpClient ipClient = baseClient(getUnsafeOkHttpClient()
                    .socketFactory(SNI_FRAGMENTING_SOCKET_FACTORY))
                    .protocols(ntkTlsFallbackProtocolsForTest())
                    .connectTimeout(NTK_PAGE_DIRECT_TIMEOUT_MS, TimeUnit.MILLISECONDS)
                    .readTimeout(NTK_PAGE_DIRECT_TIMEOUT_MS, TimeUnit.MILLISECONDS)
                    .callTimeout(NTK_PAGE_DIRECT_TIMEOUT_MS, TimeUnit.MILLISECONDS)
                    .build();
            Request.Builder builder = new Request.Builder()
                    .url("https://" + ip + path)
                    .get()
                    .header("Host", host);
            if(headers != null) {
                for(String key : headers.keySet()) {
                    if("Host".equalsIgnoreCase(key))
                        continue;
                    builder.header(key, headers.get(key));
                }
            }
            response = ipClient.newCall(builder.build()).execute();
            int code = response.code();
            String body = response.body() == null ? "" : response.body().string();
            appendDiagnosticLine(report, name, "ip=" + ip
                    + ",code=" + code
                    + ",ms=" + (System.currentTimeMillis() - startedAt)
                    + ",len=" + body.length()
                    + ",sample=" + abbreviateLogSample(body, 120));
        } catch (Exception e) {
            appendDiagnosticLine(report, name, "fail "
                    + (System.currentTimeMillis() - startedAt) + "ms " + exceptionSummary(e));
        } finally {
            if(response != null)
                response.close();
        }
    }

    private void probeNtkDetailTls12Variant(StringBuilder report, String name, String baseUrl,
                                            String path, Map<String, String> headers) {
        long startedAt = System.currentTimeMillis();
        Response response = null;
        try {
            ConnectionSpec tls12 = new ConnectionSpec.Builder(ConnectionSpec.MODERN_TLS)
                    .tlsVersions(okhttp3.TlsVersion.TLS_1_2)
                    .build();
            OkHttpClient tls12Client = baseClient(new OkHttpClient.Builder()
                    .socketFactory(SNI_FRAGMENTING_SOCKET_FACTORY))
                    .protocols(ntkTlsFallbackProtocolsForTest())
                    .connectionSpecs(java.util.Collections.singletonList(tls12))
                    .connectTimeout(NTK_PAGE_DIRECT_TIMEOUT_MS, TimeUnit.MILLISECONDS)
                    .readTimeout(NTK_PAGE_DIRECT_TIMEOUT_MS, TimeUnit.MILLISECONDS)
                    .callTimeout(NTK_PAGE_DIRECT_TIMEOUT_MS, TimeUnit.MILLISECONDS)
                    .build();
            Request.Builder builder = new Request.Builder()
                    .url(baseUrl + path)
                    .get();
            if(headers != null) {
                for(String key : headers.keySet())
                    builder.addHeader(key, headers.get(key));
            }
            response = tls12Client.newCall(builder.build()).execute();
            int code = response.code();
            String body = response.body() == null ? "" : response.body().string();
            appendDiagnosticLine(report, name, "code=" + code
                    + ",ms=" + (System.currentTimeMillis() - startedAt)
                    + ",len=" + body.length()
                    + ",sample=" + abbreviateLogSample(body, 120));
        } catch (Exception e) {
            appendDiagnosticLine(report, name, "fail "
                    + (System.currentTimeMillis() - startedAt) + "ms " + exceptionSummary(e));
        } finally {
            if(response != null)
                response.close();
        }
    }

    private void probeNtkDetailEngineVariant(StringBuilder report, String name, String baseUrl,
                                             String path, Map<String, String> headers,
                                             boolean quic) {
        long startedAt = System.currentTimeMillis();
        try {
            String cookieHeader = getCookieHeaderForNtkPath(path);
            String userAgent = headerValue(headers, "User-Agent", agent);
            NtkQuicFetcher.Result result = quic
                    ? NtkQuicFetcher.fetch(context, baseUrl + path, userAgent,
                            cookieHeader, headers, "GET", null, 4500L)
                    : NtkQuicFetcher.fetchHttp2Only(context, baseUrl + path, userAgent,
                            cookieHeader, headers, "GET", null, 4500L);
            String body = result == null || result.body == null ? "" : result.body;
            appendDiagnosticLine(report, name, "code=" + (result == null ? 0 : result.code)
                    + ",ms=" + (System.currentTimeMillis() - startedAt)
                    + ",len=" + body.length()
                    + ",error=" + (result == null || result.error == null ? "" : result.error.getClass().getSimpleName())
                    + ",sample=" + abbreviateLogSample(body, 120));
        } catch (Exception e) {
            appendDiagnosticLine(report, name, "fail "
                    + (System.currentTimeMillis() - startedAt) + "ms " + exceptionSummary(e));
        }
    }

    private static String headerValue(Map<String, String> headers, String key, String fallback) {
        if(headers != null && key != null) {
            for(String existing : headers.keySet()) {
                if(key.equalsIgnoreCase(existing)) {
                    String value = headers.get(existing);
                    if(value != null && value.length() > 0)
                        return value;
                }
            }
        }
        return fallback;
    }

    private FetchMode effectiveFetchMode(FetchMode defaultMode) {
        FetchMode mode = currentFetchMode.get();
        if(mode != null)
            return mode;
        return defaultMode == null ? FetchMode.ALLOW_SHARED_WEBVIEW : defaultMode;
    }

    public RequestGroup currentRequestGroup() {
        return currentRequestGroup.get();
    }

    private boolean allowsWolfWebViewFallback() {
        RequestGroup requestGroup = currentRequestGroup.get();
        return requestGroup != null && requestGroup.allowsWolfWebViewFallback();
    }

    public Response get(String url, Map<String, String> headers){
        return get(url, headers, false);
    }

    private Response get(String url, Map<String, String> headers, boolean fastNtkPageDirect){
        applyJitterIfNeeded(url);
        Response response;
        Call call = null;
        RequestGroup requestGroup = currentRequestGroup.get();
        boolean fastWolfPageDirect = shouldUseFastWolfPageDirectUrl(url);
        boolean fastWolfSearchDirect = shouldUseFastWolfSearchDirectUrl(url);
        boolean ntkDirectUrl = shouldUseNtkDirectClientUrl(url);
        boolean fastNtkApiDirect = ntkDirectUrl && shouldUseFastNtkApiDirectUrl(url);
        OkHttpClient primaryClient = fastNtkApiDirect ? ntkApiFastClient
                : (fastNtkPageDirect || ntkDirectUrl) ? ntkPageFastClient
                : fastWolfSearchDirect ? wolfSearchFastClient
                : fastWolfPageDirect ? wolfPageFastClient : this.client;
        OkHttpClient fallbackClient = fastNtkApiDirect ? unsafeNtkApiFastClient
                : (fastNtkPageDirect || ntkDirectUrl) ? unsafeNtkPageFastClient
                : fastWolfSearchDirect ? unsafeWolfSearchFastClient
                : fastWolfPageDirect ? unsafeWolfPageFastClient : this.unsafeFallbackClient;
        try {
            Response quicPrimary = getWithNtkQuicPrimaryUrl(url, headers);
            if(quicPrimary != null)
                return quicPrimary;
            Request.Builder builder = new Request.Builder()
                    .url(url)
                    .get();
            if(headers !=null)
                for(String k : headers.keySet()){
                    builder.addHeader(k, headers.get(k));
                }

            Request request = builder.build();
            call = primaryClient.newCall(request);
            if(requestGroup != null)
                requestGroup.add(call);
            try {
                response = call.execute();
            } catch (SSLException sslException) {
                if(!shouldUseUnsafeFallbackForUrl(url, fastNtkApiDirect))
                    throw sslException;
                if(requestGroup != null)
                    requestGroup.remove(call);
                call = fallbackClient.newCall(request);
                if(requestGroup != null)
                    requestGroup.add(call);
                response = call.execute();
            }
            storeResponseCookies(response);
        } catch (Exception e){
            rememberFailedWfwfRoot(url, e);
            rememberNtkAccessChallengeFailure(url, e);
            if(shouldRecordRequestFailure(url, e, requestGroup, fastNtkPageDirect))
                ml.melun.mangaview.report.CrashReporter.record(e);
            return null;
        } finally {
            if(requestGroup != null && call != null)
                requestGroup.remove(call);
        }
        return response;
    }

    static boolean shouldUseUnsafeFallbackForUrlForTest(String url, boolean fastNtkApiDirect) {
        return shouldUseUnsafeFallbackForUrl(url, fastNtkApiDirect);
    }

    private static boolean shouldUseUnsafeFallbackForUrl(String url, boolean fastNtkApiDirect) {
        if(fastNtkApiDirect)
            return false;
        return allowUnsafeFallback(url);
    }

    private void rememberNtkAccessChallengeFailure(String url, Exception e) {
        if(url == null || e == null || !isNtkUrlForTest(url))
            return;
        if(!(e instanceof SSLException)
                && !(e instanceof java.net.SocketException)
                && !(e instanceof java.net.SocketTimeoutException)
                && !(e instanceof java.net.ConnectException))
            return;
        markCloudflareChallenge(url);
    }

    private boolean shouldRecordRequestFailure(String url, Exception e, RequestGroup requestGroup, boolean fastNtkPageDirect) {
        return shouldRecordRequestFailureForState(url, e,
                requestGroup != null && requestGroup.isCancelled(),
                fastNtkPageDirect);
    }

    private void rememberFailedWfwfRoot(String url, Exception e) {
        if(!isLikelyStaleWfwfRootFailure(url, e))
            return;
        String root = numberedRootFromUrl(url);
        if(!WfwfDomainResolver.isSupportedNumberedUrl(root))
            return;
        try {
            context.getSharedPreferences("mangaView", Context.MODE_PRIVATE)
                    .edit()
                    .putString("wfwfDomainFailedRoot", root)
                    .putLong("wfwfDomainFailedAt", System.currentTimeMillis())
                    .apply();
            synchronized (wfwfDomainLock) {
                wfwfDomainLastForcedRetry = 0;
            }
            PerfTrace.mark("wfwf_domain_root_failed", "root=" + root + ",error=" + e.getClass().getSimpleName());
        } catch (Exception ignored) {
        }
    }

    private static boolean isLikelyStaleWfwfRootFailure(String url, Exception e) {
        String root = numberedRootFromUrl(url);
        if(!WfwfDomainResolver.isSupportedNumberedUrl(root))
            return false;
        return e instanceof SSLException ||
                e instanceof UnknownHostException ||
                e instanceof java.net.SocketTimeoutException ||
                e instanceof java.net.ConnectException ||
                e instanceof java.io.IOException;
    }

    private static String numberedRootFromUrl(String url) {
        if(url == null)
            return "";
        try {
            HttpUrl parsed = HttpUrl.parse(url);
            if(parsed != null)
                return parsed.scheme() + "://" + parsed.host();
        } catch (Exception ignored) {
        }
        return WfwfDomainResolver.toRoot(url);
    }

    private static boolean shouldRecordRequestFailureForState(String url, Exception e,
                                                              boolean requestCancelled,
                                                              boolean fastNtkPageDirect) {
        if(isInterruptedRequest(e) || requestCancelled)
            return false;
        if(isNtkUrlForTest(url) && e instanceof java.io.IOException)
            return false;
        if(shouldUseFastWolfPageDirectUrl(url) && e instanceof java.io.IOException)
            return false;
        return !(fastNtkPageDirect && e instanceof SSLException);
    }

    static boolean shouldRecordRequestFailureForTest(String url, Exception e,
                                                     boolean requestCancelled,
                                                     boolean fastNtkPageDirect) {
        return shouldRecordRequestFailureForState(url, e, requestCancelled, fastNtkPageDirect);
    }

    static boolean shouldUseFastWolfPageDirectUrlForTest(String url) {
        return shouldUseFastWolfPageDirectUrl(url);
    }

    static boolean shouldUseFastWolfSearchDirectUrlForTest(String url) {
        return shouldUseFastWolfSearchDirectUrl(url);
    }

    private static boolean shouldUseFastWolfPageDirectUrl(String url) {
        if(url == null)
            return false;
        String lower = url.toLowerCase(Locale.ROOT);
        return (lower.contains("://wfwf") || lower.contains("://wolf"))
                && (lower.contains("/cl?toon=")
                || lower.contains("/list?toon=")
                || lower.contains("/cv?toon=")
                || lower.contains("/view?toon=")
                || lower.contains("/cm")
                || lower.contains("/ing")
                || lower.contains("/end")
                || lower.contains("/webtoon")
                || lower.contains("/comic")
                || lower.contains("/search.html"));
    }

    private static boolean shouldUseFastWolfSearchDirectUrl(String url) {
        if(url == null)
            return false;
        String lower = url.toLowerCase(Locale.ROOT);
        return (lower.contains("://wfwf") || lower.contains("://wolf"))
                && lower.contains("/search.html");
    }

    private static boolean allowUnsafeFallback(String url) {
        if(url == null)
            return false;
        HttpUrl parsed = HttpUrl.parse(url);
        if(parsed == null)
            return false;
        String host = parsed.host().toLowerCase(Locale.ROOT);
        return host.startsWith("wfwf")
                || host.contains("wolf")
                || host.contains("ntk")
                || host.contains("newto")
                || host.contains("sbxh")
                || host.contains("toonflix")
                || isConfiguredNtkHost(host);
    }

    static boolean allowUnsafeFallbackForTest(String url) {
        return allowUnsafeFallback(url);
    }

    private void applyJitterIfNeeded(String url) {
        if(url == null)
            return;
        String lower = url.toLowerCase(Locale.ROOT);
        boolean isImage = lower.matches(".*\\.(jpg|jpeg|png|gif|webp|css|js|ico)(\\?.*)?$") || lower.contains("imgcloud") || lower.contains("stm.com") || lower.contains("cloud.com");
        if(isImage)
            return;
        boolean ntk = isNtkUrl(lower) || isNtk();
        if(!ntk)
            return;
        if(ntk && hasCloudflareClearance())
            return;
        try {
            Thread.sleep(30 + (int)(Math.random() * 60));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    public static String readBody(Response response) throws Exception {
        if(response == null)
            throw new Exception("Request failed");
        try {
            return response.body() == null ? "" : response.body().string();
        } finally {
            response.close();
        }
    }

    private String pageCacheKey(String normalized) {
        return pageCacheKey(getBaseUrl(normalized), normalized);
    }

    private String pageCacheKey(String baseUrl, String normalized) {
        String key = baseUrl + normalized;
        if(isNtkUrl(baseUrl))
            key += "|ua=" + (isDesktopUserAgent(agent) ? "desktop" : "mobile");
        return key;
    }

    public static byte[] readBytes(Response response) throws Exception {
        if(response == null)
            throw new Exception("Request failed");
        try {
            return response.body() == null ? new byte[0] : response.body().bytes();
        } finally {
            response.close();
        }
    }

    public boolean resolveWfwfDomainNow() {
        if(isNtk())
            return false;
        return ensureNumberedDomain(true);
    }

    public boolean resolveNtkDomainNow() {
        if(!isNtk())
            return false;
        return ensureNumberedDomain(true);
    }

    public void setNtkDomainAutoResolveDisabledForTest(boolean disabled) {
        ntkDomainAutoResolveDisabledForTest = disabled;
    }

    private boolean ensureWfwfDomainForRetry() {
        if(isNtk())
            return ensureNumberedDomain(true);
        RequestGroup requestGroup = currentRequestGroup.get();
        if(requestGroup != null && requestGroup.isCancelled()) {
            logWfwfDomainCanceledOnce();
            return false;
        }
        long now = System.currentTimeMillis();
        synchronized (wfwfDomainLock) {
            if(now - wfwfDomainLastForcedRetry < WFWF_DOMAIN_FORCE_RETRY_INTERVAL_MS)
                return false;
        }
        boolean changed = ensureNumberedDomain(true);
        if(requestGroup != null && requestGroup.isCancelled())
            return false;
        synchronized (wfwfDomainLock) {
            wfwfDomainLastForcedRetry = now;
        }
        return changed;
    }

    private boolean ensureNtkDomainForRetry() {
        if(!isNtk())
            return false;
        RequestGroup requestGroup = currentRequestGroup.get();
        if(requestGroup != null && requestGroup.isCancelled())
            return false;
        return ensureNumberedDomain(true);
    }

    private void logWfwfDomainCanceledOnce() {
        long now = System.currentTimeMillis();
        synchronized (wfwfDomainLock) {
            if(now - wfwfDomainLastCanceledLog < WFWF_DOMAIN_CANCELED_LOG_INTERVAL_MS)
                return;
            wfwfDomainLastCanceledLog = now;
        }
        PerfTrace.mark("wfwf_domain_resolve_skipped", "canceled");
    }

    private boolean ensureNumberedDomain(boolean force) {
        if(isNtk())
            return ensureNtkDomainIfNeeded(force);
        try {
            String webtoonUrl = getWebtoonUrl();
            String root = WfwfDomainResolver.toRoot(webtoonUrl);
            if(!WfwfDomainResolver.isSupportedNumberedUrl(root))
                return false;

            SharedPreferences pref = context.getSharedPreferences("mangaView", Context.MODE_PRIVATE);
            long now = System.currentTimeMillis();
            DomainResolveState resolveState;
            boolean shouldResolve = false;
            synchronized (wfwfDomainLock) {
                if(wfwfDomainResolveState != null) {
                    resolveState = wfwfDomainResolveState;
                } else {
                    long lastCheck = pref.getLong("wfwfDomainLastCheck", 0);
                    String lastRoot = pref.getString("wfwfDomainLastRoot", "");
                    String failedRoot = pref.getString("wfwfDomainFailedRoot", "");
                    long failedAt = pref.getLong("wfwfDomainFailedAt", 0);
                    if(shouldSkipRecentWfwfDomainCheck(force, root, lastRoot, lastCheck, failedRoot, failedAt, now))
                        return false;
                    wfwfDomainResolveState = new DomainResolveState();
                    pref.edit()
                            .putLong("wfwfDomainLastCheck", now)
                            .putString("wfwfDomainLastRoot", root)
                            .apply();
                    shouldResolve = true;
                    resolveState = wfwfDomainResolveState;
                }
            }

            if(!shouldResolve)
                return waitForWfwfDomainResolve(resolveState);

            boolean changed = false;
            try {
                Map<String, String> headers = new HashMap<>();
                headers.put("User-Agent", agent);
                headers.put("Referer", root);
                long started = System.currentTimeMillis();
                String resolved = force
                        ? WfwfDomainResolver.resolveReplacement(client, root, headers, currentRequestGroup.get())
                        : WfwfDomainResolver.resolve(client, root, headers, currentRequestGroup.get());
                if(resolved != null && !resolved.equals(root)) {
                    SiteOverride override = currentSiteOverride.get();
                    if(override != null) {
                        override.webtoonUrl = trimTrailingSlash(resolved);
                        override.comicUrl = trimTrailingSlash(resolved) + "/cm";
                    } else {
                        p.setWebtoonUrl(resolved);
                        p.setUrl(resolved + "/cm");
                        p.setDefUrl(resolved + "/cm");
                    }
                    resetCookie();
                    clearPageCache();
                    changed = true;
                }
                if(resolved != null) {
                    pref.edit()
                            .remove("wfwfDomainFailedRoot")
                            .remove("wfwfDomainFailedAt")
                            .apply();
                }
                PerfTrace.mark("wfwf_domain_resolve_ms", (System.currentTimeMillis() - started)
                        + ",force=" + force
                        + ",root=" + root
                        + ",resolved=" + (resolved == null ? "" : resolved)
                        + ",changed=" + changed);
                return changed;
            } finally {
                synchronized (wfwfDomainLock) {
                    resolveState.changed = changed;
                    if(wfwfDomainResolveState == resolveState)
                        wfwfDomainResolveState = null;
                }
                resolveState.done.countDown();
            }
        } catch (Exception e) {
            ml.melun.mangaview.report.CrashReporter.record(e);
            return false;
        }
    }

    private boolean ensureNtkDomainIfNeeded(boolean force) {
        if(ntkDomainAutoResolveDisabledForTest) {
            Log.d(TAG, "ntk_domain_resolve_disabled_for_test root=" + getWebtoonUrl()
                    + ",force=" + force);
            return false;
        }
        long now = System.currentTimeMillis();
        String currentRoot = WfwfDomainResolver.toRoot(getWebtoonUrl());
        if(!force && isCurrentDefaultNtkRoot(currentRoot)
                && hasRecentNtkAccessVerification()
                && !hasRecentCloudflareChallenge()
                && !hasRecentNtkHardBlock())
            return false;
        DomainResolveState activeResolve = null;
        synchronized (wfwfDomainLock) {
            if(ntkDomainResolveState != null)
                activeResolve = ntkDomainResolveState;
        }
        if(activeResolve != null)
            return waitForWfwfDomainResolve(activeResolve);
        synchronized (wfwfDomainLock) {
            if(shouldSkipRecentNtkDomainCheck(force, currentRoot, ntkDomainLastCheckedRoot, ntkDomainLastCheck, now))
                return false;
            ntkDomainLastCheck = now;
            ntkDomainLastCheckedRoot = currentRoot;
        }
        return ensureNtkDomain();
    }

    private static boolean isCurrentDefaultNtkRoot(String root) {
        String normalized = NtkDomainResolver.normalizeRoot(root);
        if(normalized == null || normalized.length() == 0)
            return false;
        return NTK_WEBTOON_URL.equalsIgnoreCase(normalized);
    }

    private static boolean shouldSkipRecentNtkDomainCheck(boolean force, String currentRoot,
                                                          String lastCheckedRoot, long lastCheck, long now) {
        if(force || lastCheck <= 0)
            return false;
        String current = NtkDomainResolver.normalizeRoot(currentRoot);
        String last = NtkDomainResolver.normalizeRoot(lastCheckedRoot);
        if(current == null || last == null || !current.equals(last))
            return false;
        return now - lastCheck < NTK_DOMAIN_CHECK_INTERVAL_MS;
    }

    static boolean shouldSkipRecentNtkDomainCheckForTest(boolean force, String currentRoot,
                                                         String lastCheckedRoot, long lastCheck, long now) {
        return shouldSkipRecentNtkDomainCheck(force, currentRoot, lastCheckedRoot, lastCheck, now);
    }

    private boolean ensureNtkDomain() {
        try {
            String currentRoot = WfwfDomainResolver.toRoot(getWebtoonUrl());
            if(!isNtkUrl(currentRoot))
                return false;

            DomainResolveState resolveState;
            boolean shouldResolve = false;
            synchronized (wfwfDomainLock) {
                if(ntkDomainResolveState == null) {
                    ntkDomainResolveState = new DomainResolveState();
                    shouldResolve = true;
                }
                resolveState = ntkDomainResolveState;
            }

            if(!shouldResolve)
                return waitForWfwfDomainResolve(resolveState);

            boolean changed = false;
            try {
                Map<String, String> headers = new HashMap<>();
                headers.put("User-Agent", agent);
                headers.put("Referer", NtkDomainResolver.CHANNEL_URL);
                List<String> resolvedRoots = NtkDomainResolver.resolveCandidates(client, headers, currentRequestGroup.get());
                String reachable = reachableNtkRoot(currentRoot, resolvedRoots, headers);
                if(shouldApplyResolvedNtkRoot(currentRoot, reachable, resolvedRoots)) {
                    SiteOverride override = currentSiteOverride.get();
                    if(override != null) {
                        String root = trimTrailingSlash(reachable);
                        override.webtoonUrl = root;
                        override.comicUrl = root + "/manhwa";
                    } else {
                        p.setNtkSitePreset(reachable);
                    }
                    resetCookie();
                    clearPageCache();
                    changed = true;
                }
                return changed;
            } finally {
                synchronized (wfwfDomainLock) {
                    resolveState.changed = changed;
                    if(ntkDomainResolveState == resolveState)
                        ntkDomainResolveState = null;
                }
                resolveState.done.countDown();
            }
        } catch (Exception e) {
            ml.melun.mangaview.report.CrashReporter.record(e);
            return false;
        }
    }

    private String reachableNtkRoot(String currentRoot, List<String> resolvedRoots, Map<String, String> headers) {
        ArrayList<String> candidates = new ArrayList<>();
        if(resolvedRoots != null)
            for(String root : resolvedRoots)
                addNtkRootCandidate(candidates, root, true);
        addNtkRootCandidate(candidates, "https://" + PREVIOUS_NTK_HOST);
        addNtkRootCandidate(candidates, NTK_WEBTOON_URL);
        addNtkRootCandidate(candidates, currentRoot);
        addNtkRootCandidate(candidates, "https://" + OLDER_NTK_HOST);
        addNtkRootCandidate(candidates, "https://" + OLDEST_NTK_HOST);
        addNtkRootCandidate(candidates, "https://" + LEGACY_NTK_HOST);
        for(String candidate : candidates)
            if(canReachNtkRoot(candidate, headers))
                return candidate;
        Log.d(TAG, "ntk_domain_reachable_none current=" + currentRoot
                + ",resolved=" + (resolvedRoots == null ? "[]" : resolvedRoots.toString())
                + ",candidates=" + candidates);
        return null;
    }

    private static String firstTrustedResolvedNtkRoot(List<String> resolvedRoots) {
        if(resolvedRoots == null)
            return null;
        for(String root : resolvedRoots) {
            String normalized = NtkDomainResolver.normalizeRoot(root);
            if(normalized != null && normalized.length() > 0)
                return normalized;
        }
        return null;
    }

    private static boolean shouldApplyResolvedNtkRoot(String currentRoot, String resolvedRoot, List<String> trustedRoots) {
        String normalized = NtkDomainResolver.normalizeRoot(resolvedRoot);
        String current = NtkDomainResolver.normalizeRoot(currentRoot);
        if(normalized == null || normalized.length() == 0 || normalized.equals(current))
            return false;
        if(trustedRoots != null && !trustedRoots.isEmpty())
            return containsTrustedResolvedNtkRoot(trustedRoots, normalized);
        return isNtkUrlForTest(normalized);
    }

    private static boolean containsTrustedResolvedNtkRoot(List<String> trustedRoots, String root) {
        String normalizedRoot = NtkDomainResolver.normalizeRoot(root);
        if(normalizedRoot == null || normalizedRoot.length() == 0 || trustedRoots == null)
            return false;
        for(String candidate : trustedRoots) {
            String normalizedCandidate = NtkDomainResolver.normalizeRoot(candidate);
            if(normalizedRoot.equals(normalizedCandidate))
                return true;
        }
        return false;
    }

    static String firstTrustedResolvedNtkRootForTest(List<String> resolvedRoots) {
        return firstTrustedResolvedNtkRoot(resolvedRoots);
    }

    static boolean shouldApplyResolvedNtkRootForTest(String currentRoot, String resolvedRoot, List<String> trustedRoots) {
        return shouldApplyResolvedNtkRoot(currentRoot, resolvedRoot, trustedRoots);
    }

    private void addNtkRootCandidate(List<String> candidates, String root) {
        addNtkRootCandidate(candidates, root, false);
    }

    private void addNtkRootCandidate(List<String> candidates, String root, boolean trustedResolvedRoot) {
        root = NtkDomainResolver.normalizeRoot(root);
        if(root == null || root.length() == 0 || candidates.contains(root))
            return;
        if(!trustedResolvedRoot && !isNtkUrl(root))
            return;
        candidates.add(root);
    }

    private boolean canReachNtkRoot(String root, Map<String, String> headers) {
        Response response = null;
        Call call = null;
        try {
            OkHttpClient probeClient = client.newBuilder()
                    .connectTimeout(2, TimeUnit.SECONDS)
                    .readTimeout(2, TimeUnit.SECONDS)
                    .callTimeout(3, TimeUnit.SECONDS)
                    .followRedirects(false)
                    .followSslRedirects(false)
                    .build();
            String normalizedRoot = trimTrailingSlash(root);
            Request.Builder builder = new Request.Builder()
                    .url(normalizedRoot + "/api/manhwa-list?page=1&pageSize=1&withTotal=1")
                    .get()
                    .header("Accept", "application/json,text/plain,*/*")
                    .header("Referer", normalizedRoot + "/");
            if(headers != null) {
                for(String key : headers.keySet()) {
                    if(key == null)
                        continue;
                    String lower = key.toLowerCase(Locale.ROOT);
                    if("accept".equals(lower) || "referer".equals(lower))
                        continue;
                    builder.header(key, headers.get(key));
                }
            }
            call = probeClient.newCall(builder.build());
            response = call.execute();
            if(response == null)
                return false;
            int code = response.code();
            String location = response.header("location", "");
            String contentType = response.header("content-type", "");
            String body = "";
            try {
                body = response.peekBody(256 * 1024L).string();
            } catch (Exception ignored) {
            }
            if(isReachableNtkProbeResponse(code, location, contentType, body))
                return true;
            response.close();
            response = null;
            call.cancel();

            builder = new Request.Builder()
                    .url(normalizedRoot + "/api/ad/challenge")
                    .get()
                    .header("Accept", "application/json,text/plain,*/*")
                    .header("Referer", normalizedRoot + "/");
            if(headers != null) {
                for(String key : headers.keySet()) {
                    if(key == null)
                        continue;
                    String lower = key.toLowerCase(Locale.ROOT);
                    if("accept".equals(lower) || "referer".equals(lower))
                        continue;
                    builder.header(key, headers.get(key));
                }
            }
            call = probeClient.newCall(builder.build());
            response = call.execute();
            if(response == null)
                return false;
            code = response.code();
            location = response.header("location", "");
            contentType = response.header("content-type", "");
            body = "";
            try {
                body = response.peekBody(64 * 1024L).string();
            } catch (Exception ignored) {
            }
            if(isReachableNtkChallengeTransportResponse(code, location, contentType, body))
                return true;
            response.close();
            response = null;
            call.cancel();

            OkHttpClient unsafeProbeClient = unsafeNtkApiFastClient == null
                    ? probeClient
                    : unsafeNtkApiFastClient.newBuilder()
                    .connectTimeout(2, TimeUnit.SECONDS)
                    .readTimeout(2, TimeUnit.SECONDS)
                    .callTimeout(3, TimeUnit.SECONDS)
                    .followRedirects(false)
                    .followSslRedirects(false)
                    .build();
            call = unsafeProbeClient.newCall(builder.build());
            response = call.execute();
            if(response == null)
                return false;
            code = response.code();
            location = response.header("location", "");
            contentType = response.header("content-type", "");
            body = "";
            try {
                body = response.peekBody(64 * 1024L).string();
            } catch (Exception ignored) {
            }
            return isReachableNtkChallengeTransportResponse(code, location, contentType, body);
        } catch (Exception e) {
            if(isNtkSslTrustFailure(e)) {
                Response unsafeResponse = null;
                Call unsafeCall = null;
                try {
                    OkHttpClient.Builder unsafeProbeBuilder = unsafeNtkApiFastClient == null
                            ? client.newBuilder()
                            : unsafeNtkApiFastClient.newBuilder();
                    OkHttpClient unsafeProbeClient = unsafeProbeBuilder
                            .connectTimeout(2, TimeUnit.SECONDS)
                            .readTimeout(2, TimeUnit.SECONDS)
                            .callTimeout(3, TimeUnit.SECONDS)
                            .followRedirects(false)
                            .followSslRedirects(false)
                            .build();
                    String normalizedRoot = trimTrailingSlash(root);
                    Request.Builder unsafeBuilder = new Request.Builder()
                            .url(normalizedRoot + "/api/ad/challenge")
                            .get()
                            .header("Accept", "application/json,text/plain,*/*")
                            .header("Referer", normalizedRoot + "/");
                    if(headers != null) {
                        for(String key : headers.keySet()) {
                            if(key == null)
                                continue;
                            String lower = key.toLowerCase(Locale.ROOT);
                            if("accept".equals(lower) || "referer".equals(lower))
                                continue;
                            unsafeBuilder.header(key, headers.get(key));
                        }
                    }
                    unsafeCall = unsafeProbeClient.newCall(unsafeBuilder.build());
                    unsafeResponse = unsafeCall.execute();
                    if(unsafeResponse == null)
                        return false;
                    String body = "";
                    try {
                        body = unsafeResponse.peekBody(64 * 1024L).string();
                    } catch (Exception ignored) {
                    }
                    return isReachableNtkChallengeTransportResponse(unsafeResponse.code(),
                            unsafeResponse.header("location", ""),
                            unsafeResponse.header("content-type", ""), body);
                } catch (Exception ignored) {
                    return false;
                } finally {
                    if(unsafeCall != null)
                        unsafeCall.cancel();
                    if(unsafeResponse != null)
                        unsafeResponse.close();
                }
            }
            return false;
        } finally {
            if(call != null)
                call.cancel();
            if(response != null)
                response.close();
        }
    }

    private static boolean isReachableNtkProbeResponse(int code, String location, String body) {
        return isReachableNtkProbeResponse(code, location, "", body);
    }

    private static boolean isReachableNtkProbeResponse(int code, String location, String contentType, String body) {
        if(location != null && location.toLowerCase(Locale.ROOT).contains("t.me/"))
            return false;
        if(code != 200)
            return false;
        String sample = body == null ? "" : body.trim();
        String type = contentType == null ? "" : contentType.toLowerCase(Locale.ROOT);
        if(type.contains("application/json") && sample.startsWith("{") && sample.contains("\"works\""))
            return true;
        return sample.startsWith("{") && sample.contains("\"works\"");
    }

    static boolean isReachableNtkProbeResponseForTest(int code, String location, String body) {
        return isReachableNtkProbeResponse(code, location, body);
    }

    private static boolean isReachableNtkChallengeTransportResponse(int code, String location,
                                                                    String contentType, String body) {
        if(location != null && location.toLowerCase(Locale.ROOT).contains("t.me/"))
            return false;
        String sample = body == null ? "" : body.trim().toLowerCase(Locale.ROOT);
        String type = contentType == null ? "" : contentType.toLowerCase(Locale.ROOT);
        if(code == 405 && (type.contains("application/json") || sample.contains("\"method\"")))
            return true;
        return code == 200 && (type.contains("application/json") || sample.startsWith("{"));
    }

    static boolean isReachableNtkChallengeTransportResponseForTest(int code, String location,
                                                                   String contentType, String body) {
        return isReachableNtkChallengeTransportResponse(code, location, contentType, body);
    }

    private static boolean isNtkSslTrustFailure(Throwable throwable) {
        if(throwable == null)
            return false;
        for(Throwable current = throwable; current != null; current = current.getCause()) {
            String message = String.valueOf(current.getMessage()).toLowerCase(Locale.ROOT);
            String type = current.getClass().getName().toLowerCase(Locale.ROOT);
            if(type.contains("sslhandshakeexception")
                    || message.contains("trust anchor")
                    || message.contains("cert_authority_invalid")
                    || message.contains("certpathvalidatorexception"))
                return true;
        }
        return false;
    }

    private static boolean shouldSkipRecentWfwfDomainCheck(boolean force, String currentRoot,
                                                           String lastCheckedRoot, long lastCheck, long now) {
        return shouldSkipRecentWfwfDomainCheck(force, currentRoot, lastCheckedRoot, lastCheck, "", 0, now);
    }

    private static boolean shouldSkipRecentWfwfDomainCheck(boolean force, String currentRoot,
                                                           String lastCheckedRoot, long lastCheck,
                                                           String failedRoot, long failedAt, long now) {
        if(force || lastCheck <= 0)
            return false;
        String current = WfwfDomainResolver.toRoot(currentRoot);
        String last = WfwfDomainResolver.toRoot(lastCheckedRoot);
        if(current.length() == 0 || !current.equals(last))
            return false;
        if(hasRecentFailedWfwfRoot(current, failedRoot, failedAt, now))
            return false;
        return now - lastCheck < WFWF_DOMAIN_CHECK_INTERVAL_MS;
    }

    static boolean shouldSkipRecentWfwfDomainCheckForTest(boolean force, String currentRoot,
                                                          String lastCheckedRoot, long lastCheck, long now) {
        return shouldSkipRecentWfwfDomainCheck(force, currentRoot, lastCheckedRoot, lastCheck, now);
    }

    static boolean shouldSkipRecentWfwfDomainCheckForTest(boolean force, String currentRoot,
                                                          String lastCheckedRoot, long lastCheck,
                                                          String failedRoot, long failedAt, long now) {
        return shouldSkipRecentWfwfDomainCheck(force, currentRoot, lastCheckedRoot, lastCheck, failedRoot, failedAt, now);
    }

    static boolean isLikelyStaleWfwfRootFailureForTest(String url, Exception e) {
        return isLikelyStaleWfwfRootFailure(url, e);
    }

    private static boolean hasRecentFailedWfwfRoot(String currentRoot, String failedRoot, long failedAt, long now) {
        if(failedAt <= 0 || now < failedAt || now - failedAt > WFWF_FAILED_ROOT_RECHECK_INTERVAL_MS)
            return false;
        String current = WfwfDomainResolver.toRoot(currentRoot);
        String failed = WfwfDomainResolver.toRoot(failedRoot);
        return current.length() > 0 && current.equals(failed);
    }

    private boolean waitForWfwfDomainResolve(DomainResolveState resolveState) {
        if(resolveState == null)
            return false;
        try {
            return resolveState.done.await(WFWF_DOMAIN_WAIT_TIMEOUT_MS, TimeUnit.MILLISECONDS)
                    && resolveState.changed;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    public Response mget(String url, Boolean useDefaultCookies){
        return mget(url, useDefaultCookies, new HashMap<>());
    }
    public Response mget(String url){
        return mget(url,true);
    }

    public PageResponse mgetCachedPage(String url, long ttlMillis) throws Exception {
        String normalized = normalizePath(url);
        boolean wolfDocument = !isNtk() && isWolfEpisodeDocumentPath(normalized);
        boolean ntkTokenizedViewer = isNtk() && isNtkTokenizedViewerPath(normalized);
        boolean allowFreshNtkTokenizedCache = shouldUseFreshNtkTokenizedViewerCache(normalized);
        String cacheKey = pageCacheKey(normalized);
        long now = System.currentTimeMillis();
        FetchMode fetchMode = effectiveFetchMode(FetchMode.ALLOW_SHARED_WEBVIEW);
        String loadKey = isNtk() ? cacheKey + "|fetch=" + fetchMode.name() : cacheKey;
        boolean allowColdStartStale = allowsColdStartStalePageCache(cacheKey);
        if(isNtk())
            ttlMillis = Math.max(ttlMillis * 5, 10 * 60 * 1000L);
        PageLoadState activeLoad = null;
        CachedPage staleCached = null;
        synchronized (pageCacheLock) {
            CachedPage cached = pageCache.get(cacheKey);
            if(cached != null) {
                if(!isUsableCachedPage(cached)) {
                    pageCache.remove(cacheKey);
                } else {
                    boolean fresh = isPageCacheFresh(cached.time, now, ttlMillis);
                    if((!ntkTokenizedViewer || allowFreshNtkTokenizedCache && fresh)
                            && (fresh || shouldServeColdStartCachedPageImmediately(allowColdStartStale, fetchMode, true, fresh))) {
                        if(ntkTokenizedViewer)
                            Log.d(TAG, "ntk_tokenized_viewer_fresh_cache_hit path=" + normalized
                                    + ",source=memory,serverAck=" + hasRecentNtkServerAckProof(normalized)
                                    + ",access=" + hasNtkAccessProof());
                        return new PageResponse(cached.code, cached.body, true);
                    }
                    staleCached = cached;
                }
            }
        }
        CachedPage diskCached = readDiskCachedPage(cacheKey, now, ttlMillis, allowColdStartStale);
        if(diskCached != null) {
            synchronized (pageCacheLock) {
                pageCache.put(cacheKey, diskCached);
            }
            boolean fresh = isPageCacheFresh(diskCached.time, now, ttlMillis);
            if((!ntkTokenizedViewer || allowFreshNtkTokenizedCache && fresh)
                    && (fresh || shouldServeColdStartCachedPageImmediately(allowColdStartStale, fetchMode, true, fresh))) {
                if(ntkTokenizedViewer)
                    Log.d(TAG, "ntk_tokenized_viewer_fresh_cache_hit path=" + normalized
                            + ",source=disk,serverAck=" + hasRecentNtkServerAckProof(normalized)
                            + ",access=" + hasNtkAccessProof());
                return new PageResponse(diskCached.code, diskCached.body, true);
            }
            staleCached = diskCached;
        }
        if(fetchMode == FetchMode.CACHE_ONLY) {
            if(staleCached != null)
                return new PageResponse(staleCached.code, staleCached.body, true);
            throw new Exception("Cache miss: " + cacheKey);
        }
        boolean ntkViewerAccessProof = hasRecentNtkViewerAccessProof(normalized);
        if(shouldFastFailNtkPageForCaptcha(isNtk(), normalized, fetchMode,
                ntkViewerAccessProof, hasRecentNtkAccessVerification(), hasRecentCloudflareChallenge())) {
            markCloudflareChallenge(getBaseUrl(normalized) + normalized);
            throw new Exception("Cloudflare challenge");
        }
        if(isNtk() || shouldResolveWfwfBeforeCachedPage(normalized, staleCached != null, fetchMode))
            ensureNumberedDomain(false);
        synchronized (pageLoadsLock) {
            activeLoad = pageLoads.get(loadKey);
            if(activeLoad == null)
                pageLoads.put(loadKey, new PageLoadState());
        }
        if(activeLoad != null)
            return waitForCachedPage(normalized, cacheKey, activeLoad, ttlMillis, staleCached);

        PageLoadState loadState;
        synchronized (pageLoadsLock) {
            loadState = pageLoads.get(loadKey);
        }
        try {
            PageResponse loaded = loadPageFromNetworkWithDomainRetry(normalized, now, staleCached);
            if(loadState != null)
                loadState.response = loaded;
            return loaded;
        } catch (Exception e) {
            if(isNtk() && isNtkWebViewFetchPath(normalized)
                    && !Thread.currentThread().isInterrupted()
                    && effectiveFetchMode(FetchMode.ALLOW_SHARED_WEBVIEW) == FetchMode.ALLOW_SHARED_WEBVIEW
                    && !shouldSkipNtkHiddenWebViewFallbackAfterPageError(isNtk(), normalized, fetchMode,
                            ntkViewerAccessProof, hasRecentNtkAccessVerification(), hasRecentCloudflareChallenge(), e)) {
                PageResponse fallback = loadNtkPageViaWebViewFallback(normalized, now);
                if(fallback != null) {
                    if(loadState != null)
                        loadState.response = fallback;
                    return fallback;
                }
            }
            if(loadState != null)
                loadState.error = e;
            if(staleCached != null)
                return new PageResponse(staleCached.code, staleCached.body, true);
            throw e;
        } finally {
            synchronized (pageLoadsLock) {
                pageLoads.remove(loadKey);
            }
            if(loadState != null)
            loadState.done.countDown();
        }
    }

    private boolean shouldUseFreshNtkTokenizedViewerCache(String normalized) {
        return isNtk()
                && isNtkTokenizedViewerPath(normalized)
                && (hasNtkAccessProof()
                || hasRecentNtkAccessVerification()
                || hasRecentNtkServerAckProof(normalized));
    }

    private boolean hasRecentNtkViewerAccessProof(String normalized) {
        if(!isNtk())
            return false;
        if(hasNtkAccessProof() || hasRecentNtkServerAckProof(normalized))
            return true;
        return isNtkTitleDocumentPath(normalized)
                && NtkWebViewFallbackManager.hasRecentStrictAdAckSuccessUnderTitlePath(normalized);
    }

    private PageResponse mgetCachedPageOnly(String url, long ttlMillis) {
        String normalized = normalizePath(url);
        boolean ntkTokenizedViewer = isNtk() && isNtkTokenizedViewerPath(normalized);
        String cacheKey = pageCacheKey(normalized);
        long now = System.currentTimeMillis();
        boolean allowColdStartStale = allowsColdStartStalePageCache(cacheKey);
        if(isNtk())
            ttlMillis = Math.max(ttlMillis * 5, 10 * 60 * 1000L);
        synchronized (pageCacheLock) {
            CachedPage cached = pageCache.get(cacheKey);
            if(cached != null) {
                if(!isUsableCachedPage(cached)) {
                    pageCache.remove(cacheKey);
                } else {
                    boolean fresh = isPageCacheFresh(cached.time, now, ttlMillis);
                    if(!ntkTokenizedViewer || fresh || allowColdStartStale)
                        return new PageResponse(cached.code, cached.body, true);
                }
            }
        }
        CachedPage diskCached = readDiskCachedPage(cacheKey, now, ttlMillis, allowColdStartStale);
        if(diskCached != null) {
            synchronized (pageCacheLock) {
                pageCache.put(cacheKey, diskCached);
            }
            return new PageResponse(diskCached.code, diskCached.body, true);
        }
        return new PageResponse(0, "", true);
    }

    public PageResponse mgetNtkViewerPayloadPage(String url, long ttlMillis) throws Exception {
        return mgetNtkViewerPayloadPage(url, ttlMillis, null);
    }

    public PageResponse mgetNtkViewerPayloadPage(String url, long ttlMillis,
                                                 NtkQuicFetcher.PartialTextObserver partialTextObserver) throws Exception {
        String normalized = normalizePath(url);
        if(!isNtk() || !isNtkTokenizedViewerPath(normalized) || !NtkQuicFetcher.isAvailable())
            return mgetCachedPage(normalized, ttlMillis);
        long startedAt = System.currentTimeMillis();
        String payloadKey = ntkViewerPayloadCacheKey(normalized);
        CachedPage sharedPayload = NTK_VIEWER_PAYLOAD_CACHE.get(payloadKey);
        if(sharedPayload != null && sharedPayload.body != null && sharedPayload.body.length() > 0
                && isUsableNtkViewerPayload(sharedPayload.body)) {
            Log.d(TAG, "ntk_rsc_payload_shared_cache_first path=" + normalized
                    + ",code=" + sharedPayload.code
                    + ",bytes=" + sharedPayload.body.length()
                    + ",ms=" + (System.currentTimeMillis() - startedAt));
            if(partialTextObserver != null)
                partialTextObserver.onPartialText(sharedPayload.body);
            return new PageResponse(sharedPayload.code, sharedPayload.body, true);
        }
        PageResponse cachedPayload = mgetCachedPageOnly(normalized, ttlMillis);
        if(cachedPayload != null && cachedPayload.code >= 200 && cachedPayload.code < 400
                && isUsableNtkViewerPayload(cachedPayload.body)) {
            Log.d(TAG, "ntk_rsc_payload_cached_first path=" + normalized
                    + ",code=" + cachedPayload.code
                    + ",bytes=" + (cachedPayload.body == null ? 0 : cachedPayload.body.length())
                    + ",ms=" + (System.currentTimeMillis() - startedAt));
            if(partialTextObserver != null)
                partialTextObserver.onPartialText(cachedPayload.body);
            return cachedPayload;
        }
        if(ReaderImageCache.isNtkAckRecoveryLaunchHeldForPath(normalized)
                && partialTextObserver == null) {
            Log.d(TAG, "ntk_rsc_payload_skip_launch_hold path=" + normalized
                    + ",ms=" + (System.currentTimeMillis() - startedAt));
            return mgetCachedPageOnly(normalized, ttlMillis);
        }
        if(ReaderImageCache.isNtkAckRecoveryLaunchHeldForPath(normalized)) {
            Log.d(TAG, "ntk_rsc_payload_partial_bypass_launch_hold path=" + normalized
                    + ",ms=" + (System.currentTimeMillis() - startedAt));
        }
        String baseUrl = getBaseUrl(normalized);
        Map<String, String> headers = new HashMap<>();
        headers.put("accept", "text/x-component");
        headers.put("rsc", "1");
        headers.put("next-url", normalized);
        headers.put("origin", baseUrl);
        headers.put("referer", baseUrl + normalized);
        try {
            waitForNtkAckPreflightBeforeRsc(baseUrl, normalized);
            if(hasRecentNtkHardBlock() || hasRecentNtkAckChallengeHardBlockForPath(baseUrl, normalized)) {
                Log.d(TAG, "ntk_rsc_payload_skip_hardblock path=" + normalized
                        + ",ms=" + (System.currentTimeMillis() - startedAt));
                return mgetCachedPage(normalized, ttlMillis);
            }
            if(partialTextObserver != null)
                startNtkRscAckPreflight(normalized);
            NtkQuicFetcher.Result result;
            if(partialTextObserver != null) {
                result = fetchNtkQuicUntilText(baseUrl, baseUrl + normalized,
                        getCookieHeaderForNtkPath(normalized), headers, "GET", null,
                        NTK_VIEWER_RSC_PAYLOAD_TIMEOUT_MS, (code, responseHeaders, text) -> {
                            partialTextObserver.onPartialText(text);
                            return isUsableNtkViewerPayload(text);
                        });
            } else {
                result = fetchNtkQuic(baseUrl, baseUrl + normalized,
                        getCookieHeaderForNtkPath(normalized), headers, "GET", null,
                        NTK_VIEWER_RSC_PAYLOAD_TIMEOUT_MS, null);
            }
            String body = result == null || result.body == null ? "" : result.body;
            Log.d(TAG, "ntk_rsc_payload path=" + normalized
                    + ",code=" + (result == null ? 0 : result.code)
                    + ",bytes=" + body.length()
                    + ",usable=" + isUsableNtkViewerPayload(body)
                    + ",ms=" + (System.currentTimeMillis() - startedAt)
                    + ",error=" + (result == null ? "" : result.error));
            if(result != null && result.error == null
                    && isCloudflareChallengeResponse(result.code, body)) {
                markCloudflareChallenge(baseUrl + normalized);
                Log.d(TAG, "ntk_rsc_payload_cloudflare_challenge path=" + normalized
                        + ",code=" + result.code
                        + ",ms=" + (System.currentTimeMillis() - startedAt));
                return mgetCachedPageOnly(normalized, ttlMillis);
            }
            if(result != null && result.error == null && result.code >= 200 && result.code < 400
                    && isUsableNtkViewerPayload(body)) {
                rememberFreshNtkTokenizedViewerPayload(normalized, result.code, body);
                if(partialTextObserver != null)
                    Log.d(TAG, "ntk_rsc_payload_early_token_return path=" + normalized
                            + ",bytes=" + body.length()
                            + ",ms=" + (System.currentTimeMillis() - startedAt));
                return new PageResponse(result.code, body, false);
            }
            if(result == null || result.error != null || result.code == 0) {
                Log.d(TAG, "ntk_rsc_payload_drop_engine path=" + normalized
                        + ",code=" + (result == null ? 0 : result.code)
                        + ",error=" + (result == null ? null : result.error));
                dropNtkQuicEngine(baseUrl);
                NtkQuicFetcher.Result fallback = fetchNtkViewerPayloadRscOkHttp(baseUrl, normalized, headers);
                String fallbackBody = fallback == null || fallback.body == null ? "" : fallback.body;
                Log.d(TAG, "ntk_rsc_payload_fallback path=" + normalized
                        + ",transport=" + ntkRscPayloadTransport(fallback)
                        + ",code=" + (fallback == null ? 0 : fallback.code)
                        + ",bytes=" + (fallback == null || fallback.bodyBytes == null ? 0 : fallback.bodyBytes.length)
                        + ",usable=" + isUsableNtkViewerPayload(fallbackBody)
                        + ",ms=" + (System.currentTimeMillis() - startedAt)
                        + ",error=" + (fallback == null ? null : fallback.error));
                if(fallback != null && fallback.error == null && fallback.code >= 200 && fallback.code < 400
                        && isUsableNtkViewerPayload(fallbackBody)) {
                    rememberFreshNtkTokenizedViewerPayload(normalized, fallback.code, fallbackBody);
                    return new PageResponse(fallback.code, fallbackBody, false);
                }
            }
        } catch(InterruptedException e) {
            Thread.currentThread().interrupt();
            throw e;
        } catch(Exception e) {
            Log.d(TAG, "ntk_rsc_payload_error path=" + normalized
                    + ",ms=" + (System.currentTimeMillis() - startedAt)
                    + "," + e);
            dropNtkQuicEngine(baseUrl);
        }
        return mgetCachedPage(normalized, ttlMillis);
    }

    private void rememberFreshNtkTokenizedViewerPayload(String normalized, int code, String body) {
        if(!isNtk() || !isNtkTokenizedViewerPath(normalized)
                || code < 200 || code >= 400
                || !isUsableNtkViewerPayload(body))
            return;
        CachedPage page = new CachedPage(code, body, System.currentTimeMillis());
        NTK_VIEWER_PAYLOAD_CACHE.put(ntkViewerPayloadCacheKey(normalized), page);
        String cacheKey = pageCacheKey(normalized);
        synchronized (pageCacheLock) {
            pageCache.put(cacheKey, page);
        }
        writeDiskCachedPage(cacheKey, page);
        Log.d(TAG, "ntk_tokenized_viewer_payload_cached path=" + normalized
                + ",code=" + code
                + ",bytes=" + body.length()
                + ",serverAck=" + hasRecentNtkServerAckProof(normalized)
                + ",access=" + hasNtkAccessProof());
    }

    public void rememberNtkViewerPageFromWebView(String url, int code, String body) {
        if(code < 200 || code >= 400 || body == null || body.length() == 0)
            return;
        String normalized = normalizeWebViewViewerPath(url);
        if(!isNtkTokenizedViewerPath(normalized))
            return;
        if(isUsableNtkViewerPayload(body)) {
            CachedPage page = new CachedPage(code, body, System.currentTimeMillis());
            NTK_VIEWER_PAYLOAD_CACHE.put(ntkViewerPayloadCacheKey(normalized), page);
            String cacheKey = pageCacheKey(normalized);
            synchronized (pageCacheLock) {
                pageCache.put(cacheKey, page);
            }
            writeDiskCachedPage(cacheKey, page);
            Log.d(TAG, "ntk_webview_viewer_payload_cached path=" + normalized
                    + ",code=" + code
                    + ",bytes=" + body.length()
                    + ",cacheKey=" + cacheKey
                    + ",serverAck=" + hasRecentNtkServerAckProof(normalized)
                    + ",access=" + hasNtkAccessProof()
                    + ",clientNtk=" + isNtk());
            return;
        }
        Log.d(TAG, "ntk_webview_viewer_page_no_payload path=" + normalized
                + ",code=" + code
                + ",bodyLen=" + body.length()
                + ",clientNtk=" + isNtk()
                + ",hasShellData=" + hasNtkViewerShellData(body.toLowerCase(Locale.ROOT)));
    }

    public boolean hasCachedNtkViewerPayload(String path) {
        String normalized = normalizePath(path);
        if(!isNtkTokenizedViewerPath(normalized))
            return false;
        CachedPage page = NTK_VIEWER_PAYLOAD_CACHE.get(ntkViewerPayloadCacheKey(normalized));
        return page != null && page.body != null && page.body.length() > 0
                && isUsableNtkViewerPayload(page.body);
    }

    private static String normalizeWebViewViewerPath(String url) {
        if(url == null || url.length() == 0)
            return "/";
        if(url.startsWith("/"))
            return url;
        try {
            URI uri = URI.create(url);
            String rawPath = uri.getRawPath();
            if(rawPath != null && rawPath.length() > 0)
                return rawPath;
        } catch(Exception ignored) {
        }
        return "/" + url;
    }

    private String ntkViewerPayloadCacheKey(String normalized) {
        return getBaseUrl(normalized) + normalized;
    }

    private void rememberNtkViewerDocumentProbe(String normalized, int code, String body) {
        if(context == null || normalized == null || body == null || body.length() == 0)
            return;
        try {
            java.io.File dir = new java.io.File(context.getCacheDir(), "ntk_page_probe");
            if(!dir.exists() && !dir.mkdirs())
                return;
            String name = Integer.toHexString((getBaseUrl(normalized) + normalized).hashCode()) + ".html";
            java.io.File file = new java.io.File(dir, name);
            byte[] bytes = body.getBytes(java.nio.charset.StandardCharsets.UTF_8);
            try(java.io.FileOutputStream output = new java.io.FileOutputStream(file)) {
                output.write(bytes);
            }
            Log.d(TAG, "ntk_viewer_document_probe_write name=" + name
                    + ",code=" + code
                    + ",len=" + bytes.length
                    + ",path=" + normalized
                    + ",hasPayload=" + isUsableNtkViewerPayload(body));
        } catch(Exception e) {
            Log.d(TAG, "ntk_viewer_document_probe_write_failed path=" + normalized + "," + e);
        }
    }

    private NtkQuicFetcher.Result fetchNtkViewerPayloadRscOkHttp(String baseUrl, String normalized,
                                                                 Map<String, String> headers) {
        Response response = null;
        try {
            Request.Builder builder = new Request.Builder()
                    .url(baseUrl + normalized)
                    .get()
                    .addHeader("User-Agent", agent);
            if(headers != null) {
                for(String key : headers.keySet()) {
                    String value = headers.get(key);
                    if(value != null && value.length() > 0)
                        builder.header(key, value);
                }
            }
            String cookieHeader = getCookieHeaderForNtkPath(normalized);
            if(cookieHeader != null && cookieHeader.length() > 0)
                builder.header("Cookie", cookieHeader);
            response = ntkPageFastClient.newCall(builder.build()).execute();
            byte[] bytes = response.body() == null ? new byte[0] : response.body().bytes();
            Map<String, List<String>> headersMap = new HashMap<>(response.headers().toMultimap());
            headersMap.put("x-mangaviewer-transport", Collections.singletonList("okhttp"));
            return NtkQuicFetcher.Result.fromBytes(response.code(), bytes, headersMap);
        } catch(Exception e) {
            return NtkQuicFetcher.Result.error(e);
        } finally {
            if(response != null)
                response.close();
        }
    }

    public void cancelNtkWebViewFallbacks() {
        try {
            if(context != null)
                NtkWebViewFallbackManager.get(context).cancelAll();
        } catch (Exception e) {
            ml.melun.mangaview.report.CrashReporter.record(e);
        }
    }

    private static String ntkRscPayloadTransport(NtkQuicFetcher.Result result) {
        if(result == null || result.headers == null)
            return "unknown";
        for(String key : result.headers.keySet()) {
            if("x-mangaviewer-transport".equalsIgnoreCase(key)) {
                List<String> values = result.headers.get(key);
                if(values != null && !values.isEmpty())
                    return values.get(0);
            }
        }
        return "httpengine";
    }

    public PageResponse mgetNtkRscPage(String url, long ttlMillis) throws Exception {
        String normalized = normalizePath(url);
        if(!isNtk() || !NtkQuicFetcher.isAvailable())
            return mgetCachedPage(normalized, ttlMillis);
        long now = System.currentTimeMillis();
        String baseUrl = getBaseUrl(normalized);
        String cacheKey = pageCacheKey(baseUrl, normalized) + "|rsc";
        FetchMode fetchMode = effectiveFetchMode(FetchMode.ALLOW_SHARED_WEBVIEW);
        if(ttlMillis > 0)
            ttlMillis = Math.max(ttlMillis * 5, 10 * 60 * 1000L);
        CachedPage cached = readDiskCachedPage(cacheKey, now, ttlMillis, true);
        if(cached != null && cached.body != null && cached.body.length() > 0)
            return new PageResponse(cached.code, cached.body, true);
        Map<String, String> headers = new HashMap<>();
        headers.put("accept", "text/x-component");
        headers.put("rsc", "1");
        headers.put("next-url", normalized);
        headers.put("origin", baseUrl);
        headers.put("referer", baseUrl + normalized);
        long startedAt = System.currentTimeMillis();
        try {
            NtkQuicFetcher.Result result = fetchNtkQuic(baseUrl, baseUrl + normalized,
                    getCookieHeaderForNtkPath(normalized), headers, "GET", null, 7000L);
            String body = result == null || result.body == null ? "" : result.body;
            Log.d(TAG, "ntk_rsc_generic path=" + normalized
                    + ",code=" + (result == null ? 0 : result.code)
                    + ",bytes=" + body.length()
                    + ",ms=" + (System.currentTimeMillis() - startedAt)
                    + ",error=" + (result == null ? "" : result.error));
            if(result != null && result.error == null
                    && isNtkHardBlockedResponse(normalized, result.code, body)) {
                markNtkHardBlock(baseUrl + normalized, body);
                return new PageResponse(result.code, body, false);
            }
            if(result != null && result.error == null && result.code > 0 && body.length() > 0
                    && isCloudflareChallengeResponse(result.code, body)) {
                markCloudflareChallenge(baseUrl + normalized);
                if(!isModernNtkGuardRoot(baseUrl)
                        && shouldAttemptNtkRscNativeAckRecovery(true, true, normalized, fetchMode)) {
                    long ackStartedAt = System.currentTimeMillis();
                    boolean nativeAckCompleted = performNtkNativeAckBypass(baseUrl, normalized);
                    Log.d(TAG, "ntk_rsc_native_ack_recover path=" + normalized
                            + ",mode=" + fetchMode
                            + ",success=" + nativeAckCompleted
                            + ",ms=" + (System.currentTimeMillis() - ackStartedAt));
                    if(nativeAckCompleted) {
                        NtkQuicFetcher.Result retry = fetchNtkQuic(baseUrl, baseUrl + normalized,
                                getCookieHeaderForNtkPath(normalized), headers, "GET", null, 7000L);
                        String retryBody = retry == null || retry.body == null ? "" : retry.body;
                        Log.d(TAG, "ntk_rsc_generic_after_ack path=" + normalized
                                + ",code=" + (retry == null ? 0 : retry.code)
                                + ",bytes=" + retryBody.length()
                                + ",error=" + (retry == null ? "" : retry.error));
                        if(retry != null && retry.error == null
                                && retry.code >= 200 && retry.code < 400
                                && retryBody.length() > 0) {
                            clearLastCloudflareChallenge();
                            CachedPage page = new CachedPage(retry.code, retryBody, now);
                            synchronized (pageCacheLock) {
                                pageCache.put(cacheKey, page);
                            }
                            writeDiskCachedPage(cacheKey, page);
                            return new PageResponse(retry.code, retryBody, false);
                        }
                        if(retry != null && retry.error == null && retry.code > 0 && retryBody.length() > 0)
                            return new PageResponse(retry.code, retryBody, false);
                    }
                }
                return new PageResponse(result.code, body, false);
            }
            if(result != null && result.error == null && result.code >= 200 && result.code < 400 && body.length() > 0) {
                clearLastCloudflareChallenge();
                CachedPage page = new CachedPage(result.code, body, now);
                synchronized (pageCacheLock) {
                    pageCache.put(cacheKey, page);
                }
                writeDiskCachedPage(cacheKey, page);
                return new PageResponse(result.code, body, false);
            }
            if(result != null && result.error == null && result.code > 0 && body.length() > 0)
                return new PageResponse(result.code, body, false);
        } catch(InterruptedException e) {
            Thread.currentThread().interrupt();
            throw e;
        } catch(Exception e) {
            Log.d(TAG, "ntk_rsc_generic_error path=" + normalized + "," + e);
        }
        return mgetCachedPage(normalized, ttlMillis);
    }

    public PageResponse mgetNtkStaticTextPage(String url, long ttlMillis) throws Exception {
        String normalized = normalizePath(url);
        if(!isNtk() || !NtkQuicFetcher.isAvailable())
            return mgetCachedPage(normalized, ttlMillis);
        String cacheKey = pageCacheKey(normalized);
        long now = System.currentTimeMillis();
        if(ttlMillis > 0)
            ttlMillis = Math.max(ttlMillis * 5, 10 * 60 * 1000L);
        CachedPage cached = readDiskCachedPage(cacheKey, now, ttlMillis, true);
        if(cached != null && cached.body != null && cached.body.length() > 0)
            return new PageResponse(cached.code, cached.body, true);
        String baseUrl = getBaseUrl(normalized);
        Map<String, String> headers = new HashMap<>();
        headers.put("accept", "application/javascript,text/javascript,*/*");
        headers.put("referer", baseUrl + "/");
        long startedAt = System.currentTimeMillis();
        try {
            NtkQuicFetcher.Result result = fetchNtkQuic(baseUrl, baseUrl + normalized,
                    getCookieHeaderForNtkPath(normalized), headers, "GET", null, 7000L);
            String body = result == null || result.body == null ? "" : result.body;
            Log.d(TAG, "ntk_static_text path=" + normalized
                    + ",code=" + (result == null ? 0 : result.code)
                    + ",bytes=" + body.length()
                    + ",ms=" + (System.currentTimeMillis() - startedAt)
                    + ",error=" + (result == null ? "" : result.error));
            if(result != null && result.error == null && result.code >= 200 && result.code < 400 && body.length() > 0) {
                CachedPage page = new CachedPage(result.code, body, now);
                synchronized (pageCacheLock) {
                    pageCache.put(cacheKey, page);
                }
                writeDiskCachedPage(cacheKey, page);
                return new PageResponse(result.code, body, false);
            }
        } catch(InterruptedException e) {
            Thread.currentThread().interrupt();
            throw e;
        } catch(Exception e) {
            Log.d(TAG, "ntk_static_text_error path=" + normalized + "," + e);
        }
        return mgetCachedPage(normalized, ttlMillis);
    }

    public PageResponse mgetNtkDesktopDocumentPage(String url, long ttlMillis) throws Exception {
        String normalized = normalizePath(url);
        if(!isNtk() || (!isNtkSearchPath(normalized)
                && !isNtkCategoryDocumentPath(normalized)
                && !isNtkTitleDocumentPath(normalized)))
            return mgetCachedPage(normalized, ttlMillis);
        long now = System.currentTimeMillis();
        String baseUrl = getBaseUrl(normalized);
        String cacheKey = baseUrl + normalized + "|desktop-document";
        if(ttlMillis > 0)
            ttlMillis = Math.max(ttlMillis * 5, 10 * 60 * 1000L);
        CachedPage cached = readDiskCachedPage(cacheKey, now, ttlMillis, true);
        if(cached != null && cached.body != null && cached.body.length() > 0)
            return new PageResponse(cached.code, cached.body, true);
        ensureNumberedDomain(false);
        baseUrl = getBaseUrl(normalized);
        Map<String, String> headers = buildHeaders(baseUrl, true, null);
        headers.put("User-Agent", NTK_DESKTOP_DOCUMENT_UA);
        headers.put("sec-ch-ua", "\"Chromium\";v=\"148\", \"Google Chrome\";v=\"148\", \"Not_A Brand\";v=\"24\"");
        headers.put("sec-ch-ua-mobile", "?0");
        headers.put("sec-ch-ua-platform", "\"Windows\"");
        headers.put("Sec-Fetch-Dest", "document");
        headers.put("Sec-Fetch-Mode", "navigate");
        headers.put("Sec-Fetch-Site", "same-origin");
        long startedAt = System.currentTimeMillis();
        Response response = get(baseUrl + normalized, headers, shouldUseFastNtkPageDirect(true, normalized, effectiveFetchMode(FetchMode.ALLOW_SHARED_WEBVIEW)));
        int code;
        String body;
        String transport = "okhttp";
        Throwable error = null;
        if(response != null) {
            code = response.code();
            body = readBody(response);
        } else {
            transport = "quic";
            NtkQuicFetcher.Result result = fetchNtkDesktopDocumentQuic(baseUrl, normalized, headers);
            code = result == null ? 0 : result.code;
            body = result == null || result.body == null ? "" : result.body;
            error = result == null ? null : result.error;
            if(result != null)
                applySetCookieHeaders(result.headers, baseUrl);
            if(result == null || error != null || code <= 0 || body.length() == 0)
                throw new Exception("Request failed: " + normalized + (error == null ? "" : " " + error));
        }
        if(code >= 400 && context != null && NtkQuicFetcher.isAvailable()) {
            String cookieHeader = headers.get("Cookie");
            NtkQuicFetcher.Result retry = NtkQuicFetcher.fetchHttp2Only(context, baseUrl + normalized,
                    NTK_DESKTOP_DOCUMENT_UA, cookieHeader == null ? "" : cookieHeader,
                    headers, "GET", null, 9000L);
            if(retry != null && retry.error == null && retry.code > 0 && retry.code < code
                    && retry.body != null && retry.body.length() > 0) {
                code = retry.code;
                body = retry.body;
                transport = "http2";
                applySetCookieHeaders(retry.headers, baseUrl);
            }
        }
        Log.d(TAG, "ntk_desktop_document path=" + normalized
                + ",transport=" + transport
                + ",code=" + code
                + ",bodyLen=" + (body == null ? 0 : body.length())
                + ",ms=" + (System.currentTimeMillis() - startedAt)
                + ",error=" + (error == null ? "" : error.getClass().getSimpleName()));
        if(isNtkHardBlockedResponse(normalized, code, body)) {
            markNtkHardBlock(baseUrl + normalized, body);
            throw new Exception("NTK hard block: " + normalized + " code=" + code);
        }
        if(isCloudflareChallenge(code, body)) {
            markCloudflareChallenge(baseUrl + normalized);
            throw new Exception(code == 403 ? "Cloudflare challenge" : "Cloudflare/server error");
        }
        if(!isNtkCategoryDocumentPath(normalized) && shouldRejectNtkPageResponse(normalized, code, body))
            throw new Exception("Unusable NTK page: " + normalized + " code=" + code);
        if(code >= 200 && code < 400 && body != null && body.length() > 0 && shouldStoreNetworkPageBody(normalized, body)) {
            CachedPage page = new CachedPage(code, body, now);
            synchronized (pageCacheLock) {
                pageCache.put(cacheKey, page);
            }
            writeDiskCachedPage(cacheKey, page);
            return new PageResponse(code, body, false);
        }
        return new PageResponse(code, body, false);
    }

    private NtkQuicFetcher.Result fetchNtkDesktopDocumentQuic(String baseUrl, String normalized,
                                                               Map<String, String> headers) {
        if(context == null || !NtkQuicFetcher.isAvailable())
            return null;
        String cookieHeader = headers == null ? "" : headers.get("Cookie");
        return NtkQuicFetcher.fetch(context, baseUrl + normalized, NTK_DESKTOP_DOCUMENT_UA,
                cookieHeader == null ? "" : cookieHeader, headers, "GET", null, 9000L);
    }

    private static boolean isUsableNtkViewerPayload(String body) {
        if(body == null || body.length() == 0)
            return false;
        String lower = body.toLowerCase(Locale.ROOT);
        return lower.contains("imagestoken") && lower.contains("imagemetas");
    }

    private PageResponse loadNtkPageViaWebViewFallback(String normalized, long now) {
        try {
            String baseUrl = getBaseUrl(normalized);
            Map<String, String> headers = buildHeaders(baseUrl, true, null);
            applyNtkApiHeaders(headers, baseUrl, normalized);
            Response response = getWithNtkWebViewFallback(baseUrl, normalized, headers);
            if(response == null)
                return null;
            int code = response.code();
            String body = readBody(response);
            if(code >= 200 && code < 400 && body.length() > 0) {
                if(looksLikeEmptyNtkRecoveredDocument(normalized, body)
                        || looksLikeUnrenderedNtkDocument(normalized, code, body)
                        || looksLikeNtkRecoverableErrorFallbackDocument(normalized, code, body)) {
                    Log.d(TAG, "ntk_page_webview_recover_reject path=" + normalized
                            + ",code=" + code
                            + ",bodyLen=" + body.length());
                    return null;
                }
                if(shouldStoreNetworkPageBody(normalized, body)) {
                    String cacheKey = pageCacheKey(normalized);
                    CachedPage cachedPage = new CachedPage(code, body, now);
                    synchronized (pageCacheLock) {
                        pageCache.put(cacheKey, cachedPage);
                    }
                    writeDiskCachedPage(cacheKey, cachedPage);
                }
                Log.d(TAG, "ntk_page_webview_recover path=" + normalized
                        + ",code=" + code
                        + ",bodyLen=" + body.length());
                return new PageResponse(code, body, false);
            }
        } catch (Exception e) {
            Log.d(TAG, "ntk_page_webview_recover_failed path=" + normalized + "," + e);
        }
        return null;
    }

    public boolean warmupCachedPageDirect(String url, long ttlMillis) {
        try {
            String normalized = normalizePath(url);
            if(isNtk() || shouldResolveWfwfBeforeCachedPage(normalized, false, FetchMode.DIRECT_ONLY))
                ensureNumberedDomain(false);
            String cacheKey = pageCacheKey(normalized);
            long now = System.currentTimeMillis();
            if(isNtk())
                ttlMillis = Math.max(ttlMillis * 5, 10 * 60 * 1000L);
            synchronized (pageCacheLock) {
                CachedPage cached = pageCache.get(cacheKey);
                if(cached != null && isUsableCachedPage(cached) && isPageCacheFresh(cached.time, now, ttlMillis))
                    return true;
                if(cached != null && !isUsableCachedPage(cached))
                    pageCache.remove(cacheKey);
            }
            Response response = mget(normalized, true, null, FetchMode.DIRECT_ONLY);
            if(response == null)
                return false;
            int code = response.code();
            String body = readBody(response);
            if(code >= 200 && code < 400 && body.length() > 0 && shouldStoreNetworkPageBody(normalized, body)) {
                synchronized (pageCacheLock) {
                    pageCache.put(cacheKey, new CachedPage(code, body, now));
                }
                writeDiskCachedPage(cacheKey, new CachedPage(code, body, now));
                return true;
            }
        } catch (Exception ignored) {
        }
        return false;
    }

    private PageResponse loadPageFromNetworkWithDomainRetry(String normalized, long now, CachedPage staleCached) throws Exception {
        Exception lastError = null;
        boolean ntk = isNtk();
        boolean wolfDocument = !ntk && isWolfEpisodeDocumentPath(normalized);
        int attempts = pageNetworkAttempts(ntk, normalized);
        for(int attempt = 0; attempt < attempts; attempt++) {
            try {
                return loadPageFromNetwork(normalized, now, staleCached);
            } catch (Exception error) {
                lastError = error;
                if(isInterruptedRequest(error))
                    throw error;
                if(shouldAbortNtkPageRetry(ntk, error, hasRecentCloudflareChallenge()))
                    throw error;
                if(ntk && attempt >= attempts - 1)
                    throw error;
                if(attempt == 0 && shouldForceResolveWfwfOnRetry(normalized, wolfDocument)) {
                    boolean changed = ensureWfwfDomainForRetry();
                    if(!changed)
                        break;
                }
                client.connectionPool().evictAll();
                unsafeFallbackClient.connectionPool().evictAll();
                sleepBeforeWfwfRetry(attempt);
            }
        }
        throw lastError;
    }

    static boolean shouldAbortNtkPageRetryForTest(boolean ntk, Exception error, boolean recentCloudflareChallenge) {
        return shouldAbortNtkPageRetry(ntk, error, recentCloudflareChallenge);
    }

    private static boolean shouldAbortNtkPageRetry(boolean ntk, Exception error, boolean recentCloudflareChallenge) {
        if(!ntk)
            return false;
        if(recentCloudflareChallenge)
            return true;
        String message = error == null ? null : error.getMessage();
        if(message == null)
            return false;
        String lower = message.toLowerCase(Locale.ROOT);
        return lower.contains("ntk hard block")
                || lower.contains("cloudflare")
                || lower.contains("challenge");
    }

    private void sleepBeforeWfwfRetry(int attempt) {
        try {
            Thread.sleep(180L + (attempt * 220L));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private PageResponse loadPageFromNetwork(String normalized, long now, CachedPage staleCached) throws Exception {
        boolean wolfDocument = !isNtk() && isWolfEpisodeDocumentPath(normalized);
        long startedAt = System.currentTimeMillis();
        Response response = mget(normalized, true, null);
        if(response == null)
            throw new Exception("Request failed: " + normalized);
        int code = response.code();
        String body = readBody(response);
        if(isNtk() && isNtkTokenizedViewerPath(normalized) && code >= 200 && code < 400)
            rememberNtkViewerDocumentProbe(normalized, code, body);
        if(wolfDocument)
            ViewerWarmupManager.logMetric("wfwf_page_network_ms", System.currentTimeMillis() - startedAt);
        if(code >= 500 && staleCached != null)
            return new PageResponse(staleCached.code, staleCached.body, true);
        if(isCloudflareChallenge(code, body)) {
            markCloudflareChallenge(getBaseUrl(normalized) + normalized);
            throw new Exception(code == 403 ? "Cloudflare challenge" : "Cloudflare/server error");
        }
        if(isNtk() && isNtkHardBlockedResponse(normalized, code, body)) {
            markNtkHardBlock(getBaseUrl(normalized) + normalized, body);
            throw new Exception("NTK hard block: " + normalized + " code=" + code);
        }
        if(isNtk())
            clearLastCloudflareChallenge();
        if(isNtk() && shouldRejectNtkPageResponse(normalized, code, body)) {
            if(staleCached != null)
                return new PageResponse(staleCached.code, staleCached.body, true);
            throw new Exception("Unusable NTK page: " + normalized + " code=" + code);
        }
        if(!isNtk() && shouldRejectWfwfPageBody(normalized, code, body)) {
            if(staleCached != null)
                return new PageResponse(staleCached.code, staleCached.body, true);
            throw new Exception("Unusable WFWF page: " + normalized);
        }
        if(isNtk() && isNtkTokenizedViewerPath(normalized)
                && code >= 200 && code < 400
                && isUsableNtkViewerPayload(body)) {
            rememberFreshNtkTokenizedViewerPayload(normalized, code, body);
        } else if(code >= 200 && code < 400 && body.length() > 0
                && shouldStoreNetworkPageBody(normalized, body)) {
            String cacheKey = pageCacheKey(normalized);
            CachedPage cachedPage = new CachedPage(code, body, now);
            synchronized (pageCacheLock) {
                pageCache.put(cacheKey, cachedPage);
            }
            writeDiskCachedPage(cacheKey, cachedPage);
        }
        return new PageResponse(code, body, false);
    }

    static boolean shouldRejectWfwfPageBodyForTest(String path, int code, String body) {
        return shouldRejectWfwfPageBody(path, code, body);
    }

    static boolean shouldRejectNtkPageResponseForTest(String path, int code, String body) {
        return shouldRejectNtkPageResponse(path, code, body);
    }

    static boolean isNtkHardBlockedResponseForTest(String path, int code, String body) {
        return isNtkHardBlockedResponse(path, code, body);
    }

    private static boolean isNtkHardBlockedResponse(String path, int code, String body) {
        if(path == null || body == null)
            return false;
        if(isNtkCategoryDocumentPath(path))
            return false;
        if(!isNtkWebViewFetchPath(path) && !isNtkApiPath(path) && !isNtkSearchPath(path))
            return false;
        String lower = body.toLowerCase(Locale.ROOT);
        if(NtkDeviceIdentityManager.isTrash0607Block(lower))
            return true;
        if(code != 403)
            return false;
        return lower.contains("<title>403 forbidden</title>")
                && lower.contains("<h1>403 forbidden</h1>")
                && lower.contains("nginx/");
    }

    private static boolean shouldRejectNtkPageResponse(String path, int code, String body) {
        if(path == null)
            return false;
        if(code == 301 || code == 302)
            return isNtkWebViewFetchPath(path);
        if(code < 200 || code >= 400 || body == null || body.length() == 0)
            return false;
        if(!isNtkWebViewFetchPath(path))
            return false;
        String lower = body.toLowerCase(Locale.ROOT);
        if(isNtkTokenizedViewerPath(path)
                && (isUsableNtkViewerPayload(body) || hasNtkViewerShellData(lower)))
            return false;
        return lower.contains("<title>document moved</title>")
                || lower.contains("<h1>object moved</h1>")
                || isCloudflareChallenge(code, body)
                || containsNtkBlockedDocumentMarker(lower);
    }

    private static boolean shouldRejectWfwfPageBody(String path, int code, String body) {
        if(path == null || code < 200 || code >= 400 || body == null || body.length() == 0)
            return false;
        if(!isWfwfDocumentPath(path))
            return false;
        if(isWfwfSearchPath(path))
            return !isCacheablePageBody(body);
        return !looksCacheable(body);
    }

    static boolean shouldStoreNetworkPageBodyForTest(String path, String body) {
        return shouldStoreNetworkPageBody(path, body);
    }

    private static boolean shouldStoreNetworkPageBody(String path, String body) {
        if(path != null && isWfwfSearchPath(path))
            return isCacheablePageBody(body);
        return looksCacheable(body);
    }

    private PageResponse waitForCachedPage(String normalized, String cacheKey, PageLoadState loadState, long ttlMillis, CachedPage staleCached) throws Exception {
        if(shouldWaitForActivePageLoad(staleCached != null)) {
            try {
                loadState.done.await(10, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw e;
            }
            if(loadState.response != null)
                return loadState.response;
        }
        long now = System.currentTimeMillis();
        boolean ntkTokenizedViewer = isNtk() && isNtkTokenizedViewerPath(normalized);
        boolean allowFreshNtkTokenizedCache = shouldUseFreshNtkTokenizedViewerCache(normalized);
        synchronized (pageCacheLock) {
            CachedPage cached = pageCache.get(cacheKey);
            if((!ntkTokenizedViewer || allowFreshNtkTokenizedCache)
                    && cached != null && isUsableCachedPage(cached) && isPageCacheFresh(cached.time, now, ttlMillis))
                return new PageResponse(cached.code, cached.body, true);
            if(cached != null && !isUsableCachedPage(cached))
                pageCache.remove(cacheKey);
            String currentCacheKey = pageCacheKey(normalized);
            if(!currentCacheKey.equals(cacheKey)) {
                cached = pageCache.get(currentCacheKey);
                if((!ntkTokenizedViewer || allowFreshNtkTokenizedCache)
                        && cached != null && isUsableCachedPage(cached) && isPageCacheFresh(cached.time, now, ttlMillis))
                    return new PageResponse(cached.code, cached.body, true);
                if(cached != null && !isUsableCachedPage(cached))
                    pageCache.remove(currentCacheKey);
            }
        }
        if(staleCached != null)
            return new PageResponse(staleCached.code, staleCached.body, true);
        if(loadState.error != null)
            throw loadState.error;
        throw new Exception("Request failed: " + cacheKey);
    }

    static int pageNetworkAttemptsForTest(boolean ntk, String path) {
        return pageNetworkAttempts(ntk, path);
    }

    private static int pageNetworkAttempts(boolean ntk, String path) {
        if(ntk)
            return isNtkEpisodeDocumentPath(path) ? 2 : 1;
        if(isWolfEpisodeDocumentPath(path))
            return 2;
        if(isWfwfSearchPath(path))
            return 2;
        if(isWfwfDocumentPath(path))
            return 1;
        return 3;
    }

    static boolean shouldResolveWolfDocumentBeforeNetworkForTest(boolean wolfDocument, boolean hasStaleCache, FetchMode fetchMode) {
        return shouldResolveWolfDocumentBeforeNetwork(wolfDocument, hasStaleCache, fetchMode);
    }

    private static boolean shouldResolveWolfDocumentBeforeNetwork(boolean wolfDocument, boolean hasStaleCache, FetchMode fetchMode) {
        return wolfDocument && !hasStaleCache && fetchMode != FetchMode.CACHE_ONLY;
    }

    static boolean shouldResolveWfwfBeforeCachedPageForTest(String path, boolean hasStaleCache, FetchMode fetchMode) {
        return shouldResolveWfwfBeforeCachedPage(path, hasStaleCache, fetchMode);
    }

    private static boolean shouldResolveWfwfBeforeCachedPage(String path, boolean hasStaleCache, FetchMode fetchMode) {
        if(fetchMode == FetchMode.CACHE_ONLY)
            return false;
        if(!isWfwfDocumentPath(path))
            return true;
        return !hasStaleCache;
    }

    static boolean shouldForceResolveWfwfOnRetryForTest(String path) {
        return shouldForceResolveWfwfOnRetry(path, isWolfEpisodeDocumentPath(path));
    }

    private static boolean shouldForceResolveWfwfOnRetry(String path, boolean wolfEpisodeDocumentPath) {
        return isWfwfDocumentPath(path);
    }

    private static boolean shouldResolveWfwfBeforeMget(String path) {
        return true;
    }

    static boolean shouldWaitForActivePageLoadForTest(boolean hasStaleCache) {
        return shouldWaitForActivePageLoad(hasStaleCache);
    }

    private static boolean shouldWaitForActivePageLoad(boolean hasStaleCache) {
        return PageCachePolicy.shouldWaitForActiveLoad(hasStaleCache);
    }

    static boolean isPageCacheFreshForTest(long cachedAt, long now, long ttlMillis) {
        return isPageCacheFresh(cachedAt, now, ttlMillis);
    }

    private static boolean isPageCacheFresh(long cachedAt, long now, long ttlMillis) {
        return PageCachePolicy.isFresh(cachedAt, now, ttlMillis);
    }

    static boolean isPageCacheUsableForColdStartForTest(long cachedAt, long now) {
        return isPageCacheUsableForColdStart(cachedAt, now);
    }

    private static boolean isPageCacheUsableForColdStart(long cachedAt, long now) {
        return PageCachePolicy.isUsableForColdStart(cachedAt, now, PAGE_CACHE_COLD_START_TTL_MS);
    }

    static boolean shouldServeColdStartCachedPageImmediatelyForTest(boolean allowColdStartStale, FetchMode fetchMode, boolean hasCachedPage, boolean fresh) {
        return shouldServeColdStartCachedPageImmediately(allowColdStartStale, fetchMode, hasCachedPage, fresh);
    }

    private static boolean shouldServeColdStartCachedPageImmediately(boolean allowColdStartStale, FetchMode fetchMode, boolean hasCachedPage, boolean fresh) {
        return PageCachePolicy.shouldServeColdStartImmediately(allowColdStartStale, fetchMode, hasCachedPage, fresh);
    }

    private CachedPage readDiskCachedPage(String cacheKey, long now, long ttlMillis, boolean allowColdStartStale) {
        if(cacheKey == null)
            return null;
        try {
            String json = CacheFileStore.read(context, PAGE_CACHE_PREFIX + cacheKey);
            if(json == null || json.length() == 0)
                return null;
            PersistedPage page = GSON.fromJson(json, PersistedPage.class);
            if(page == null || page.body == null || page.body.length() == 0)
                return null;
            if(!isCacheablePageBody(page.body)) {
                CacheFileStore.delete(context, PAGE_CACHE_PREFIX + cacheKey);
                return null;
            }
            boolean usable = isPageCacheFresh(page.time, now, ttlMillis)
                    || (allowColdStartStale && isPageCacheUsableForColdStart(page.time, now));
            if(!usable) {
                CacheFileStore.delete(context, PAGE_CACHE_PREFIX + cacheKey);
                return null;
            }
            return new CachedPage(page.code, page.body, page.time);
        } catch (Exception e) {
            ml.melun.mangaview.report.CrashReporter.record(e);
            return null;
        }
    }

    private void writeDiskCachedPage(String cacheKey, CachedPage page) {
        if(!shouldPersistDiskCachedPage(cacheKey, page))
            return;
        try {
            CacheFileStore.write(context, PAGE_CACHE_PREFIX + cacheKey, GSON.toJson(new PersistedPage(page)));
        } catch (Exception e) {
            ml.melun.mangaview.report.CrashReporter.record(e);
        }
    }

    private boolean allowsColdStartStalePageCache(String cacheKey) {
        return isNtk() || isWfwfCacheKey(cacheKey);
    }

    static boolean shouldPersistDiskCachedPageForTest(boolean ntk, String cacheKey, String body) {
        return shouldPersistDiskCachedPage(ntk, cacheKey, new CachedPage(200, body, 1L));
    }

    private boolean shouldPersistDiskCachedPage(String cacheKey, CachedPage page) {
        return shouldPersistDiskCachedPage(isNtk(), cacheKey, page);
    }

    private static boolean shouldPersistDiskCachedPage(boolean ntk, String cacheKey, CachedPage page) {
        return (ntk || isWfwfCacheKey(cacheKey))
                && cacheKey != null
                && page != null
                && page.body != null
                && page.body.length() > 0
                && !(ntk && isNtkTokenizedViewerCacheKey(cacheKey, page.body))
                && isCacheablePageBody(page.body);
    }

    private static boolean isNtkTokenizedViewerPath(String path) {
        if(path == null)
            return false;
        return path.matches("^/(manhwa|webtoon)/[^/?#%]+/[^/?#%]+/?(?:[?#].*)?$");
    }

    private static boolean isNtkTokenizedViewerCacheKey(String cacheKey, String body) {
        if(cacheKey == null || body == null)
            return false;
        String lower = cacheKey.toLowerCase(Locale.ROOT);
        if(!lower.matches("^https?://[^/]+/(manhwa|webtoon)/[^/?#%]+/[^/?#%]+/?(?:[?#].*)?$"))
            return false;
        String normalized = body.replace("\\\\\"", "\"").replace("\\\"", "\"");
        return normalized.contains("\"imagesToken\"") && normalized.contains("\"imageMetas\"");
    }

    private static boolean isWfwfCacheKey(String cacheKey) {
        if(cacheKey == null)
            return false;
        String lower = cacheKey.toLowerCase(Locale.ROOT);
        return lower.contains("://wfwf") || lower.contains("://wolf");
    }

    public void clearPageCache() {
        synchronized (pageCacheLock) {
            pageCache.clear();
        }
    }

    public void clearNtkTransientLoads() {
        clearPageCache();
        NTK_ACK_CACHE.clear();
        synchronized (ntkViewerImageUrlCacheLock) {
            ntkViewerImageUrlCache.clear();
        }
        shutdownNtkQuicEngines();
        synchronized (pageLoadsLock) {
            for(PageLoadState loadState : pageLoads.values()) {
                if(loadState != null) {
                    loadState.error = new Exception("NTK clearance changed");
                    loadState.done.countDown();
                }
            }
            pageLoads.clear();
        }
        if(context != null)
            NtkWebViewFallbackManager.get(context).cancelAll();
    }


    public String getUrl(){
        return getComicUrl();
    }

    public String getUrl(int baseMode){
        if(baseMode == MTitle.base_webtoon)
            return getWebtoonUrl();
        return getComicUrl();
    }

    public String getUrl(String path){
        return getBaseUrl(path);
    }

    private String getComicUrl(){
        SiteOverride override = currentSiteOverride.get();
        String url = override == null ? null : override.comicUrl;
        if(url == null || url.length() == 0)
            url = p.getUrl();
        if(url == null || url.length() == 0)
            url = DEFAULT_COMIC_URL;
        return trimTrailingSlash(url);
    }

    private String getWebtoonUrl(){
        SiteOverride override = currentSiteOverride.get();
        String url = override == null ? null : override.webtoonUrl;
        if(url == null || url.length() == 0)
            url = p.getWebtoonUrl();
        if(url == null || url.length() == 0)
            url = WEBTOON_URL;
        return trimTrailingSlash(url);
    }

    private String getBaseUrl(String path){
        if(isWebtoonPath(path))
            return getWebtoonUrl();
        return getRootUrl(getComicUrl());
    }

    public boolean isNtk() {
        return isNtkUrl(getWebtoonUrl()) || isNtkUrl(getComicUrl());
    }

    public boolean isNtkUrl(String url) {
        return isNtkUrlForTest(url) || isConfiguredNtkUrl(url);
    }

    static boolean isNtkUrlForTest(String url) {
        if(url == null)
            return false;
        String lower = url.toLowerCase(Locale.ROOT);
        return lower.contains("://ntk")
                || lower.contains("://newto")
                || lower.contains("://newtoki")
                || lower.contains("://toonflix")
                || lower.contains(".toonflix.app")
                || lower.contains("://" + NTK_HOST)
                || lower.contains("://www." + NTK_HOST)
                || lower.contains("://sbxh")
                || lower.contains("://www.sbxh")
                || lower.contains(".sbxh");
    }

    private boolean isConfiguredNtkUrl(String url) {
        SiteOverride override = currentSiteOverride.get();
        if(override != null)
            return isConfiguredNtkUrl(url, override);
        if(p == null || !p.isNtkSite())
            return false;
        String normalized = NtkDomainResolver.normalizeRoot(url);
        if(normalized == null || normalized.length() == 0)
            return false;
        String host = configuredHostOf(normalized);
        return isConfiguredNtkHost(host);
    }

    private boolean isConfiguredNtkUrl(String url, SiteOverride override) {
        if(override == null || (!isNtkUrlForTest(override.webtoonUrl) && !isNtkUrlForTest(override.comicUrl)))
            return false;
        String normalized = NtkDomainResolver.normalizeRoot(url);
        if(normalized == null || normalized.length() == 0)
            return false;
        String host = configuredHostOf(normalized);
        String webtoonHost = configuredHostOf(override.webtoonUrl);
        String comicHost = configuredHostOf(override.comicUrl);
        return hostMatches(normalizeDnsHost(host), webtoonHost) || hostMatches(normalizeDnsHost(host), comicHost);
    }

    private static boolean isConfiguredNtkHost(String host) {
        if(p == null || !p.isNtkSite())
            return false;
        String normalized = normalizeDnsHost(host);
        if(normalized.length() == 0)
            return false;
        String webtoonHost = configuredHostOf(p.getWebtoonUrl());
        String comicHost = configuredHostOf(p.getUrl());
        return hostMatches(normalized, webtoonHost) || hostMatches(normalized, comicHost);
    }

    private static boolean hostMatches(String host, String rootHost) {
        return rootHost != null && rootHost.length() > 0
                && (host.equals(rootHost) || host.endsWith("." + rootHost));
    }

    private static String configuredHostOf(String url) {
        try {
            String root = NtkDomainResolver.normalizeRoot(url);
            if(root == null || root.length() == 0)
                return "";
            String host = URI.create(root).getHost();
            if(host == null)
                return "";
            host = host.toLowerCase(Locale.ROOT);
            return host.startsWith("www.") ? host.substring(4) : host;
        } catch (Exception e) {
            return "";
        }
    }

    private boolean isWebtoonPath(String path){
        if(path == null)
            return false;
        return path.startsWith("/webtoon")
                || path.startsWith("webtoon")
                || path.startsWith("/ing")
                || path.startsWith("/end")
                || path.startsWith("/manhwa")
                || path.startsWith("/list?toon=")
                || path.startsWith("/view?toon=")
                || path.startsWith("/search.html")
                || path.contains("bo_table=webtoon");
    }

    private boolean isNtkPagePath(String path) {
        if(path == null)
            return false;
        return path.startsWith("/")
                && !path.matches(".*\\.(jpg|jpeg|png|gif|webp|css|js|ico)(\\?.*)?$");
    }

    private String getRootUrl(String url){
        String trimmed = trimTrailingSlash(url);
        if(trimmed.endsWith("/cm"))
            return trimmed.substring(0, trimmed.length() - 3);
        if(trimmed.endsWith("/manhwa"))
            return trimmed.substring(0, trimmed.length() - 7);
        return trimmed;
    }

    private String trimTrailingSlash(String url){
        while(url.endsWith("/"))
            url = url.substring(0, url.length() - 1);
        return url;
    }

    private String siteOverrideUrl(String url, String fallback) {
        if(url == null || url.length() == 0)
            url = fallback;
        return trimTrailingSlash(url);
    }


    public Response mget(String url, Boolean useDefaultCookies, Map<String, String> customCookie){
        return mget(url, useDefaultCookies, customCookie, FetchMode.ALLOW_SHARED_WEBVIEW);
    }

    private Response mget(String url, Boolean useDefaultCookies, Map<String, String> customCookie, FetchMode requestedFetchMode){
        FetchMode requested = requestedFetchMode == null ? FetchMode.ALLOW_SHARED_WEBVIEW : requestedFetchMode;
        FetchMode fetchMode = requested == FetchMode.ALLOW_SHARED_WEBVIEW
                ? effectiveFetchMode(requested)
                : requested;
        if(fetchMode == FetchMode.CACHE_ONLY)
            return null;
        if(customCookie==null)
            customCookie = new HashMap<>();
        url = normalizePath(url);
        boolean wolfEpisodeDocumentPath = isWolfEpisodeDocumentPath(url);
        boolean allowWfwfDomainRetry = shouldForceResolveWfwfOnRetry(url, wolfEpisodeDocumentPath);
        if(isNtk() || shouldResolveWfwfBeforeMget(url))
            ensureNumberedDomain(false);
        String baseUrl = getBaseUrl(url);
        Map<String, String> headers = buildHeaders(baseUrl, useDefaultCookies, customCookie);
        applyNtkApiHeaders(headers, baseUrl, url);

        boolean ntkBaseUrl = isNtkUrl(baseUrl);
        applyNtkScopedCookieHeader(headers, ntkBaseUrl, url, useDefaultCookies, customCookie);
        boolean fastNtkPageDirect = shouldUseFastNtkPageDirect(ntkBaseUrl, url, fetchMode);
        boolean wolfWebViewFallbackAllowed = allowsWolfWebViewFallback();
        RequestGroup requestGroup = currentRequestGroup.get();
        if(ntkBaseUrl
                && fetchMode == FetchMode.ALLOW_SHARED_WEBVIEW
                && requestGroup != null
                && isModernNtkGuardRoot(baseUrl)
                && isNtkEpisodeDocumentPath(url)) {
            requestGroup.clearWebViewFallbackPriority();
        }
        boolean prioritizeWolfWebView = requestGroup != null
                && requestGroup.prioritizesWebViewFallback()
                && !ntkBaseUrl
                && shouldUseWolfWebViewFallback(ntkBaseUrl, true, url, fetchMode, true);
        boolean prioritizeNtkEpisodeWebView = shouldPrioritizeNtkEpisodeWebView(
                ntkBaseUrl, url, fetchMode, requestGroup);
        if(ntkBaseUrl && isNtkEpisodeDocumentPath(url)) {
            Log.d(TAG, "ntk_mget_episode path=" + url
                    + ",mode=" + fetchMode
                    + ",base=" + baseUrl
                    + ",prioritizeWebView=" + prioritizeNtkEpisodeWebView
                    + ",activity=" + (MainApplication.currentActivity != null));
        }
        Response response = (prioritizeWolfWebView || prioritizeNtkEpisodeWebView)
                ? getWithNtkWebViewFallback(baseUrl, url, headers) : null;
        if(ntkBaseUrl && isNtkEpisodeDocumentPath(url) && prioritizeNtkEpisodeWebView) {
            Log.d(TAG, "ntk_mget_priority_webview_result path=" + url
                    + ",responseNull=" + (response == null)
                    + ",code=" + (response == null ? 0 : response.code()));
        }
        if(response == null)
            response = get(baseUrl + url, headers, fastNtkPageDirect);
        if(ntkBaseUrl
                && shouldRetryWithResolvedDomain(response, url)
                && !shouldSkipNtkResolvedDomainRetryAfterChallenge(fetchMode,
                response == null, hasRecentCloudflareChallenge(), url)) {
            boolean appliedRedirectRoot = applyNtkRedirectRoot(response, baseUrl);
            if(response != null) {
                rememberCloudflareChallengeIfPresent(response, baseUrl, url);
                response.close();
            }
            if(!appliedRedirectRoot)
                ensureNtkDomainForRetry();
            baseUrl = getBaseUrl(url);
            ntkBaseUrl = isNtkUrl(baseUrl);
            headers = buildHeaders(baseUrl, useDefaultCookies, customCookie);
            applyNtkApiHeaders(headers, baseUrl, url);
            applyNtkScopedCookieHeader(headers, ntkBaseUrl, url, useDefaultCookies, customCookie);
            fastNtkPageDirect = shouldUseFastNtkPageDirect(ntkBaseUrl, url, fetchMode);
            response = get(baseUrl + url, headers, fastNtkPageDirect);
        }
        if(ntkBaseUrl && NtkQuicFetcher.isAvailable()
                && !isModernNtkGuardRoot(baseUrl)
                && shouldAttemptNtkNativeAckPageRecovery(ntkBaseUrl, response, url, fetchMode)) {
            if(response != null) {
                rememberCloudflareChallengeIfPresent(response, baseUrl, url);
                response.close();
            }
            long ackStartedAt = System.currentTimeMillis();
            String ackPath = ntkNativeAckProbePath(url);
            boolean nativeAckCompleted = performNtkNativeAckBypass(baseUrl, ackPath, url);
            Log.d(TAG, "ntk_page_native_ack_recover path=" + url
                    + ",mode=" + fetchMode
                    + ",success=" + nativeAckCompleted
                    + ",ackPath=" + ackPath
                    + ",ms=" + (System.currentTimeMillis() - ackStartedAt));
            response = null;
            if(nativeAckCompleted) {
                headers = buildHeaders(baseUrl, useDefaultCookies, customCookie);
                applyNtkApiHeaders(headers, baseUrl, url);
                applyNtkScopedCookieHeader(headers, ntkBaseUrl, url, useDefaultCookies, customCookie);
                fastNtkPageDirect = shouldUseFastNtkPageDirect(ntkBaseUrl, url, fetchMode);
                response = get(baseUrl + url, headers, fastNtkPageDirect);
            }
        }
        boolean ntkFallbackCandidate = response == null || isNtkWebViewFallbackCandidate(response, url);
        boolean useNtkWebViewFallback = shouldUseNtkWebViewFallback(ntkBaseUrl,
                ntkFallbackCandidate, url, fetchMode);
        if(ntkBaseUrl && isNtkEpisodeDocumentPath(url)) {
            Log.d(TAG, "ntk_mget_fallback_decision path=" + url
                    + ",mode=" + fetchMode
                    + ",responseNull=" + (response == null)
                    + ",candidate=" + ntkFallbackCandidate
                    + ",use=" + useNtkWebViewFallback
                    + ",code=" + (response == null ? 0 : response.code()));
        }
        if(useNtkWebViewFallback) {
            if(response != null) {
                rememberCloudflareChallengeIfPresent(response, baseUrl, url);
                response.close();
            }
            response = getWithNtkQuicFallback(baseUrl, url, headers);
            if(response != null)
                return response;
            if(Thread.currentThread().isInterrupted())
                return null;
            response = getWithNtkWebViewFallback(baseUrl, url, headers);
        }
        if(!ntkBaseUrl && allowWfwfDomainRetry && shouldRetryWithResolvedDomain(response, url)) {
            if(response != null)
                response.close();
            ensureWfwfDomainForRetry();
            baseUrl = getBaseUrl(url);
            ntkBaseUrl = isNtkUrl(baseUrl);
            headers = buildHeaders(baseUrl, useDefaultCookies, customCookie);
            applyNtkApiHeaders(headers, baseUrl, url);
            fastNtkPageDirect = shouldUseFastNtkPageDirect(ntkBaseUrl, url, fetchMode);
            response = get(baseUrl + url, headers, fastNtkPageDirect);
            if(shouldUseNtkWebViewFallback(ntkBaseUrl,
                    response == null || isNtkWebViewFallbackCandidate(response, url), url, fetchMode)) {
                if(response != null) {
                    rememberCloudflareChallengeIfPresent(response, baseUrl, url);
                    response.close();
                }
                response = getWithNtkQuicFallback(baseUrl, url, headers);
                if(response != null)
                    return response;
                if(Thread.currentThread().isInterrupted())
                    return null;
                response = getWithNtkWebViewFallback(baseUrl, url, headers);
            }
        }
        if(allowWfwfDomainRetry
                && shouldUseWolfWebViewFallback(ntkBaseUrl, response == null, url, fetchMode, wolfWebViewFallbackAllowed)
                && ensureNumberedDomain(true)) {
            baseUrl = getBaseUrl(url);
            ntkBaseUrl = isNtkUrl(baseUrl);
            headers = buildHeaders(baseUrl, useDefaultCookies, customCookie);
            applyNtkApiHeaders(headers, baseUrl, url);
            applyNtkScopedCookieHeader(headers, ntkBaseUrl, url, useDefaultCookies, customCookie);
            fastNtkPageDirect = shouldUseFastNtkPageDirect(ntkBaseUrl, url, fetchMode);
            response = get(baseUrl + url, headers, fastNtkPageDirect);
        }
        if(shouldUseWolfWebViewFallback(ntkBaseUrl, response == null, url, fetchMode, wolfWebViewFallbackAllowed)) {
            if(Thread.currentThread().isInterrupted())
                return null;
            response = getWithNtkWebViewFallback(baseUrl, url, headers);
        }
        return rememberNtkEpisodeResponseBodyIfNeeded(ntkBaseUrl, url, response);
    }

    private Response rememberNtkEpisodeResponseBodyIfNeeded(boolean ntkBaseUrl, String path, Response response) {
        if(!ntkBaseUrl || response == null || !isNtkTokenizedViewerPath(path))
            return response;
        int code = response.code();
        if(code < 200 || code >= 400 || response.body() == null)
            return response;
        try {
            MediaType contentType = response.body().contentType();
            String body = response.body().string();
            if(isUsableNtkViewerPayload(body)) {
                rememberFreshNtkTokenizedViewerPayload(path, code, body);
            } else {
                Log.d(TAG, "ntk_episode_response_no_payload path=" + path
                        + ",code=" + code
                        + ",bodyLen=" + body.length()
                        + ",hasShellData=" + hasNtkViewerShellData(body.toLowerCase(Locale.ROOT)));
            }
            return response.newBuilder()
                    .body(ResponseBody.create(contentType, body))
                    .build();
        } catch(Exception e) {
            Log.d(TAG, "ntk_episode_response_probe_failed path=" + path + "," + e);
            return response;
        }
    }

    static boolean shouldUseNtkDirectClientUrlForTest(String url) {
        return shouldUseNtkDirectClientUrl(url);
    }

    private static boolean shouldUseNtkDirectClientUrl(String url) {
        return isNtkUrlForTest(url) || isTrustedNtkPrimaryImageUrl(url);
    }

    private Response getWithNtkQuicPrimaryUrl(String url, Map<String, String> headers) {
        if(headerValue(headers, NTK_NO_QUIC_HEADER) != null)
            return null;
        if(context == null || !NtkQuicFetcher.isAvailable() || !shouldUseNtkQuicPrimaryUrl(url))
            return null;
        try {
            HttpUrl parsed = HttpUrl.parse(url);
            if(parsed == null)
                return null;
            String baseUrl = rootFromHttpUrl(parsed);
            String path = encodedPathWithQuery(parsed);
            String cookieHeader = headerValue(headers, "Cookie");
            NtkQuicFetcher.Result result = fetchNtkQuic(baseUrl, url,
                    cookieHeader == null ? "" : cookieHeader, headers, "GET", null, ntkQuicGetTimeout(url));
            if(!isUsableNtkQuicGetResult(result))
                return null;
            applySetCookieHeaders(result.headers, baseUrl);
            if(isCloudflareChallenge(result.code, result.body))
                markCloudflareChallenge(url);
            ViewerWarmupManager.logMetric("ntk_quic_primary_code", result.code);
            ViewerWarmupManager.logMetric("ntk_quic_primary_len", result.bodyBytes.length);
            if(Log.isLoggable(TAG, Log.DEBUG))
                Log.d(TAG, "ntk_quic_primary_ok path=" + path
                        + ",code=" + result.code
                        + ",len=" + result.bodyBytes.length);
            return responseFromNtkQuic(new Request.Builder().url(url).build(), result, "HttpEngine");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return null;
        } catch (Exception e) {
            if(Log.isLoggable(TAG, Log.DEBUG))
                Log.d(TAG, "ntk_quic_primary_failed url=" + safeLogUrl(url), e);
            return null;
        }
    }

    private Response interceptNtkImageWithQuic(okhttp3.Interceptor.Chain chain) throws IOException {
        Request request = chain.request();
        String url = request.url().toString();
        if(request.header(NTK_NO_QUIC_HEADER) != null) {
            return chain.proceed(request.newBuilder().removeHeader(NTK_NO_QUIC_HEADER).build());
        }
        if(!"GET".equalsIgnoreCase(request.method()) || context == null
                || !NtkQuicFetcher.isAvailable() || !shouldUseNtkQuicPrimaryUrl(url))
            return chain.proceed(request);
        long startedAt = System.currentTimeMillis();
        boolean foregroundPriority = false;
        boolean anchorHedge = false;
        Map<String, String> headers = null;
        String cookieHeader = null;
        try {
            HttpUrl parsed = request.url();
            String baseUrl = rootFromHttpUrl(parsed);
            headers = requestHeadersMap(request);
            foregroundPriority = "1".equals(headerValue(headers, "X-MangaViewer-Foreground"));
            anchorHedge = "1".equals(headerValue(headers, "X-MangaViewer-Anchor-Hedge"));
            if(!foregroundPriority && isFvcdnImageUrl(url))
                return chain.proceed(request);
            removeHeaderIgnoreCase(headers, "X-MangaViewer-Foreground");
            removeHeaderIgnoreCase(headers, "X-MangaViewer-Anchor-Hedge");
            if(headerValue(headers, "Cookie") == null)
                headers.put("Cookie", getCookieHeader());
            if(headerValue(headers, "User-Agent") == null)
                headers.put("User-Agent", agent);
            cookieHeader = headerValue(headers, "Cookie");
            NtkQuicFetcher.Result result = normalizeNtkImageResult(fetchNtkQuicImage(baseUrl, url, headers, foregroundPriority, anchorHedge));
            if(isUsableNtkQuicGetResult(result)) {
                applySetCookieHeaders(result.headers, baseUrl);
                if(isCloudflareChallenge(result.code, result.body))
                    markCloudflareChallenge(url);
                ViewerWarmupManager.logMetric("ntk_quic_image_code", result.code);
                ViewerWarmupManager.logMetric("ntk_quic_image_len", result.bodyBytes.length);
                Log.d(TAG, "ntk_quic_image_result transport=" + ntkResultTransport(result, "httpengine")
                        + ",foreground=" + foregroundPriority
                        + ",code=" + result.code
                        + ",bytes=" + result.bodyBytes.length
                        + ",ms=" + (System.currentTimeMillis() - startedAt)
                        + ",url=" + safeLogUrl(url));
                return responseFromNtkQuic(request, result, "HttpEngine");
            }
            Log.d(TAG, "ntk_quic_image_result transport=fallback"
                    + ",foreground=" + foregroundPriority
                    + ",code=" + (result == null ? 0 : result.code)
                    + ",bytes=" + (result == null || result.bodyBytes == null ? 0 : result.bodyBytes.length)
                    + ",ms=" + (System.currentTimeMillis() - startedAt)
                    + ",error=" + (result == null ? null : result.error)
                    + ",url=" + safeLogUrl(url));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            InterruptedIOException interrupted = new InterruptedIOException("Interrupted during NTK QUIC image fetch");
            interrupted.initCause(e);
            throw interrupted;
        } catch (Exception e) {
            Log.d(TAG, "ntk_quic_image_failed foreground=" + foregroundPriority
                    + ",anchor=" + anchorHedge
                    + ",error=" + e.getClass().getSimpleName()
                    + ",ms=" + (System.currentTimeMillis() - startedAt)
                    + ",url=" + safeLogUrl(url), e);
        }
        if(foregroundPriority && isNtkGeneratedImageCdnUrl(url)) {
            Log.d(TAG, "ntk_quic_image_foreground_fallback_okhttp"
                    + ",ms=" + (System.currentTimeMillis() - startedAt)
                    + ",url=" + safeLogUrl(url));
            Request.Builder fallbackRequest = request.newBuilder()
                    .removeHeader("X-MangaViewer-Foreground")
                    .removeHeader("X-MangaViewer-Anchor-Hedge")
                    .removeHeader(NTK_NO_QUIC_HEADER);
            if(request.header("Cookie") == null && cookieHeader != null && cookieHeader.length() > 0)
                fallbackRequest.header("Cookie", cookieHeader);
            if(request.header("User-Agent") == null && agent != null && agent.length() > 0)
                fallbackRequest.header("User-Agent", agent);
            return chain.proceed(fallbackRequest.build());
        }
        return chain.proceed(request);
    }

    private NtkQuicFetcher.Result fetchNtkQuicImage(String baseUrl, String url,
                                                    Map<String, String> headers,
                                                    boolean foregroundPriority,
                                                    boolean anchorHedge) throws Exception {
        String cookieHeader = headerValue(headers, "Cookie");
        if(foregroundPriority) {
            if(anchorHedge)
                ViewerWarmupManager.logMetric("ntk_quic_image_anchor_hedge", 1L);
            else
                ViewerWarmupManager.logMetric("ntk_quic_image_foreground_race", 1L);
            return fetchNtkForegroundImageRace(baseUrl, url, cookieHeader, headers);
        }
        boolean independentHedge = anchorHedge && isNtkGeneratedImageCdnUrl(url);
        if(independentHedge)
            ViewerWarmupManager.logMetric("ntk_quic_image_background_anchor_hedge", 1L);
        String key = ntkQuicImageFlightKey(url, cookieHeader, independentHedge);
        FutureTask<NtkQuicFetcher.Result> task = new FutureTask<>(() ->
                fetchNtkQuic(baseUrl, url, cookieHeader, headers, "GET", null, NTK_QUIC_IMAGE_TIMEOUT_MS));
        FutureTask<NtkQuicFetcher.Result> running = ntkQuicImageFlights.putIfAbsent(key, task);
        if(running == null) {
            running = task;
            task.run();
        } else {
            ViewerWarmupManager.logMetric("ntk_quic_image_inflight_join", 1L);
        }
        try {
            return running.get();
        } catch (ExecutionException e) {
            Throwable cause = e.getCause();
            if(cause instanceof InterruptedException)
                throw (InterruptedException) cause;
            if(cause instanceof Exception)
                throw (Exception) cause;
            if(cause instanceof Error)
                throw (Error) cause;
            throw new IOException("NTK QUIC image fetch failed", cause);
        } finally {
            ntkQuicImageFlights.remove(key, running);
        }
    }

    private static String ntkQuicImageFlightKey(String url, String cookieHeader, boolean independentHedge) {
        return (url == null ? "" : url)
                + "\n" + (cookieHeader == null ? "" : cookieHeader)
                + "\nhedge=" + (independentHedge ? "1" : "0");
    }

    private static boolean shouldUseNtkQuicPrimaryUrl(String url) {
        if(url == null || url.length() == 0)
            return false;
        HttpUrl parsed = HttpUrl.parse(url);
        if(parsed == null || !"https".equals(parsed.scheme()))
            return false;
        String host = parsed.host() == null ? "" : parsed.host().toLowerCase(Locale.ROOT);
        String path = parsed.encodedPath() == null ? "" : parsed.encodedPath().toLowerCase(Locale.ROOT);
        if(host.contains("naver") || host.contains("pstatic"))
            return false;
        if(isDisallowedNtkPrimaryImagePath(path))
            return false;
        if(isNtkDnsProtectedHost(parsed.host()))
            return isTrustedNtkPrimaryImagePath(path) || looksLikeRootHashImage(path);
        if(host.matches("\\d{5,10}\\.com") || host.matches("flysky\\d*m\\.com")
                || "moamoabon.com".equals(host))
            return path.contains("/blacktoon/episodes/")
                    || path.contains("/black/episodes/")
                    || path.contains("/manhwa/")
                    || path.contains("/webtoon/")
                    || path.contains("/wt/episodes/");
        return host.matches("fvcdn\\d*\\.com")
                && (path.contains("/blacktoon/episodes/")
                || path.contains("/black/episodes/")
                || path.contains("/manhwa/")
                || path.contains("/webtoon/")
                || path.contains("/wt/episodes/")
                || path.contains("/board_uploads/")
                || path.contains("/webtoon_uploads/")
                || path.contains("/manhwa_uploads/")
                || path.contains("/comic_uploads/"));
    }

    private static boolean isTrustedNtkPrimaryImageUrl(String url) {
        if(url == null || url.length() == 0)
            return false;
        if(shouldUseNtkQuicPrimaryUrl(url))
            return true;
        HttpUrl parsed = HttpUrl.parse(url);
        if(parsed == null || !"https".equals(parsed.scheme()))
            return false;
        String host = parsed.host() == null ? "" : parsed.host().toLowerCase(Locale.ROOT);
        String path = parsed.encodedPath() == null ? "" : parsed.encodedPath().toLowerCase(Locale.ROOT);
        if(host.contains("naver") || host.contains("pstatic"))
            return false;
        if(isDisallowedNtkPrimaryImagePath(path))
            return false;
        return (host.matches("fvcdn\\d*\\.com")
                || host.matches("flysky\\d*m\\.com")
                || "moamoabon.com".equals(host)
                || host.matches("aws-cdn\\d*\\.site"))
                && (isTrustedNtkPrimaryImagePath(path) || looksLikeRootHashImage(path));
    }

    static boolean isTrustedNtkPrimaryImageUrlForTest(String url) {
        return isTrustedNtkPrimaryImageUrl(url);
    }

    private static boolean isTrustedNtkPrimaryImagePath(String path) {
        if(path == null || path.length() == 0 || isDisallowedNtkPrimaryImagePath(path))
            return false;
        return path.contains("/blacktoon/episodes/")
                || path.contains("/black/episodes/")
                || path.contains("/manhwa/")
                || path.contains("/webtoon/")
                || path.contains("/wt/episodes/")
                || path.contains("/board_uploads/")
                || path.contains("/webtoon_uploads/")
                || path.contains("/manhwa_uploads/")
                || path.contains("/comic_uploads/");
    }

    private static boolean looksLikeRootHashImage(String path) {
        if(path == null)
            return false;
        return path.matches("(?i)^/[a-z0-9_-]{16,}\\.(?:jpg|jpeg|png|webp)(?:[?#].*)?$");
    }

    private static boolean isDisallowedNtkPrimaryImagePath(String path) {
        if(path == null)
            return true;
        String lower = path.toLowerCase(Locale.ROOT);
        return lower.startsWith("/api/m/")
                || lower.startsWith("/api/ad/")
                || lower.startsWith("/cdn-cgi/")
                || lower.contains("/challenge")
                || lower.contains("/turnstile")
                || lower.contains("/cloudflare")
                || lower.contains("/verification")
                || lower.contains("/captcha")
                || lower.contains("/banner")
                || lower.contains("/advert")
                || lower.contains("/sponsor")
                || lower.contains("/popup")
                || lower.contains("/ads/")
                || lower.contains("/ad/")
                || Pattern.compile("(?i)(^|[-_/])(ad|ads|banner|advert|sponsor|popup)([-_/]|$)")
                .matcher(lower)
                .find();
    }

    private static boolean isFvcdnImageUrl(String url) {
        HttpUrl parsed = HttpUrl.parse(url);
        if(parsed == null)
            return false;
        String host = parsed.host() == null ? "" : parsed.host().toLowerCase(Locale.ROOT);
        return host.matches("fvcdn\\d*\\.com");
    }

    static boolean shouldUseNtkQuicPrimaryUrlForTest(String url) {
        return shouldUseNtkQuicPrimaryUrl(url);
    }

    private static boolean isUsableNtkQuicGetResult(NtkQuicFetcher.Result result) {
        return result != null
                && result.error == null
                && result.code > 0
                && result.bodyBytes != null
                && result.bodyBytes.length > 0;
    }

    private static long ntkQuicGetTimeout(String url) {
        return shouldUseFastNtkApiDirectUrl(url) ? Math.max(NTK_API_DIRECT_TIMEOUT_MS, 2_500L) : NTK_QUIC_GET_TIMEOUT_MS;
    }

    private Response getWithNtkQuicFallback(String baseUrl, String path, Map<String, String> headers) {
        if(context == null || !isNtkUrl(baseUrl) || !NtkQuicFetcher.isAvailable())
            return null;
        try {
            String url = baseUrl + path;
            String cookieHeader = headers == null ? "" : headers.get("Cookie");
            NtkQuicFetcher.Result result = fetchNtkQuic(baseUrl, url,
                    cookieHeader == null ? "" : cookieHeader, headers, "GET", null, 12_000L);
            if(result == null || result.code <= 0 || result.bodyBytes == null || result.bodyBytes.length == 0)
                return null;
            if(isCloudflareChallenge(result.code, result.body)) {
                markCloudflareChallenge(url);
                return null;
            }
            if(result.code >= 400 && isNtkWebViewFetchPath(path)) {
                if(Log.isLoggable(TAG, Log.DEBUG))
                    Log.d(TAG, "ntk_quic_fallback_reject_error path=" + path
                            + ",code=" + result.code
                            + ",len=" + result.bodyBytes.length
                            + ",sample=" + abbreviateLogSample(result.body, 240));
                return null;
            }
            if(looksLikeUnrenderedNtkDocument(path, result.code, result.body)) {
                if(Log.isLoggable(TAG, Log.DEBUG))
                    Log.d(TAG, "ntk_quic_fallback_unrendered path=" + path
                            + ",len=" + result.bodyBytes.length
                            + ",sample=" + abbreviateLogSample(result.body, 240));
                return null;
            }
            ViewerWarmupManager.logMetric("ntk_quic_fallback_code", result.code);
            ViewerWarmupManager.logMetric("ntk_quic_fallback_len", result.bodyBytes.length);
            if(Log.isLoggable(TAG, Log.DEBUG))
                Log.d(TAG, "ntk_quic_fallback_ok path=" + path
                        + ",len=" + result.bodyBytes.length
                        + ",sample=" + abbreviateLogSample(result.body, 240));
            return responseFromNtkQuic(new Request.Builder().url(url).build(), result, "HttpEngine");
        } catch (Exception e) {
            if(Log.isLoggable(TAG, Log.DEBUG))
                Log.d(TAG, "ntk_quic_fallback_failed path=" + path, e);
            return null;
        }
    }

    private static Response responseFromNtkQuic(Request request, NtkQuicFetcher.Result result, String message) {
        return new Response.Builder()
                .request(request)
                .protocol(Protocol.HTTP_2)
                .code(result.code)
                .message(message)
                .headers(headersFromNtkQuic(result.headers))
                .body(ResponseBody.create(result.bodyBytes, MediaType.parse(result.contentType())))
                .build();
    }

    private static Headers headersFromNtkQuic(Map<String, List<String>> headers) {
        Headers.Builder builder = new Headers.Builder();
        if(headers == null)
            return builder.build();
        for(String name : headers.keySet()) {
            if(name == null || name.length() == 0)
                continue;
            List<String> values = headers.get(name);
            if(values == null)
                continue;
            for(String value : values) {
                if(value == null)
                    continue;
                try {
                    builder.add(name, value);
                } catch (Exception ignored) {
                }
            }
        }
        return builder.build();
    }

    private static Map<String, String> requestHeadersMap(Request request) {
        Map<String, String> headers = new HashMap<>();
        if(request == null)
            return headers;
        for(String name : request.headers().names()) {
            String value = request.header(name);
            if(value != null)
                headers.put(name, value);
        }
        return headers;
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

    private static void removeHeaderIgnoreCase(Map<String, String> headers, String name) {
        if(headers == null || name == null)
            return;
        String matched = null;
        for(String key : headers.keySet()) {
            if(name.equalsIgnoreCase(key)) {
                matched = key;
                break;
            }
        }
        if(matched != null)
            headers.remove(matched);
    }

    private static String rootFromHttpUrl(HttpUrl url) {
        String root = url.scheme() + "://" + url.host();
        int port = url.port();
        if(("http".equals(url.scheme()) && port != 80) || ("https".equals(url.scheme()) && port != 443))
            root += ":" + port;
        return root;
    }

    private static String encodedPathWithQuery(HttpUrl url) {
        String path = url.encodedPath();
        String query = url.encodedQuery();
        return query == null || query.length() == 0 ? path : path + "?" + query;
    }

    private static String abbreviateLogSample(String value, int maxLength) {
        if(value == null || maxLength <= 0)
            return "";
        String sample = value.replace('\n', ' ').replace('\r', ' ');
        return sample.length() > maxLength ? sample.substring(0, maxLength) : sample;
    }

    private boolean applyNtkRedirectRoot(Response response, String currentBaseUrl) {
        if(response == null || !isNtk())
            return false;
        int code = response.code();
        if(code != 301 && code != 302)
            return false;
        String redirectedRoot = ntkRedirectRoot(response.header("location", ""));
        if(redirectedRoot == null || redirectedRoot.length() == 0)
            return false;
        String currentRoot = WfwfDomainResolver.toRoot(currentBaseUrl == null ? getWebtoonUrl() : currentBaseUrl);
        List<String> officialRoots = resolveOfficialNtkRoots();
        String officialRoot = officialNtkRootForRedirect(currentRoot, redirectedRoot, officialRoots);
        if(officialRoot == null || officialRoot.length() == 0) {
            if(Log.isLoggable(TAG, Log.DEBUG))
                Log.d(TAG, "ntk_redirect_root_ignored redirect=" + redirectedRoot + ",from=" + currentRoot);
            return false;
        }
        String normalizedCurrent = NtkDomainResolver.normalizeRoot(currentRoot);
        if(!officialRoot.equals(normalizedCurrent)) {
            p.setNtkSitePreset(officialRoot);
            resetCookie();
            clearPageCache();
        }
        if(Log.isLoggable(TAG, Log.DEBUG))
            Log.d(TAG, "ntk_redirect_official_root_applied root=" + officialRoot
                    + ",redirect=" + redirectedRoot + ",from=" + currentRoot);
        return true;
    }

    private List<String> resolveOfficialNtkRoots() {
        Map<String, String> headers = new HashMap<>();
        headers.put("User-Agent", agent);
        headers.put("Referer", NtkDomainResolver.CHANNEL_URL);
        return NtkDomainResolver.resolveCandidates(client, headers, currentRequestGroup.get());
    }

    private static String officialNtkRootForRedirect(String currentRoot, String redirectedRoot, List<String> officialRoots) {
        if(redirectedRoot == null || redirectedRoot.length() == 0)
            return null;
        String officialRoot = firstTrustedResolvedNtkRoot(officialRoots);
        if(officialRoot == null || officialRoot.length() == 0)
            return null;
        String normalizedCurrent = NtkDomainResolver.normalizeRoot(currentRoot);
        if(officialRoot.equals(normalizedCurrent))
            return officialRoot;
        return shouldApplyResolvedNtkRoot(currentRoot, officialRoot, officialRoots) ? officialRoot : null;
    }

    static String ntkRedirectRootForTest(String location) {
        return ntkRedirectRoot(location);
    }

    static String officialNtkRootForRedirectForTest(String currentRoot, String location, List<String> officialRoots) {
        return officialNtkRootForRedirect(currentRoot, ntkRedirectRoot(location), officialRoots);
    }

    private static String ntkRedirectRoot(String location) {
        if(location == null || location.trim().length() == 0)
            return null;
        String value = location.trim();
        String lower = value.toLowerCase(Locale.ROOT);
        if(lower.contains("t.me/") || lower.startsWith("/"))
            return null;
        HttpUrl url = HttpUrl.parse(value);
        if(url == null)
            return null;
        String scheme = url.scheme();
        String host = url.host();
        if(scheme == null || host == null || host.length() == 0)
            return null;
        int port = url.port();
        String root = scheme + "://" + host;
        if(("http".equals(scheme) && port != 80) || ("https".equals(scheme) && port != 443))
            root += ":" + port;
        return NtkDomainResolver.normalizeRoot(root);
    }

    private void rememberCloudflareChallengeIfPresent(Response response, String baseUrl, String path) {
        if(response == null)
            return;
        int code = response.code();
        if(code != 403 && code < 500)
            return;
        try {
            String body = response.peekBody(256 * 1024L).string();
            if(isCloudflareChallenge(code, body)) {
                markCloudflareChallenge((baseUrl == null ? "" : baseUrl) + (path == null ? "" : path));
            }
        } catch (Exception ignored) {
        }
    }

    private boolean isNtkWebViewFallbackCandidate(Response response, String path) {
        if(response == null || !isNtkWebViewFetchPath(path))
            return false;
        int code = response.code();
        String location = response.header("location", "");
        if((code == 301 || code == 302) && location != null && location.toLowerCase(Locale.ROOT).contains("t.me/"))
            return false;
        if(code == 301 || code == 302 || code == 403 || code == 404 || code >= 500)
            return true;
        if(code >= 200 && code < 400 && isNtkNavigableDocumentPath(path)) {
            try {
                String body = response.peekBody(256 * 1024L).string();
                return looksLikeUnrenderedNtkDocument(path, code, body)
                        || looksLikeNtkRecoverableErrorFallbackDocument(path, code, body);
            } catch (Exception ignored) {
            }
        }
        return false;
    }

    private Response getWithNtkWebViewFallback(String baseUrl, String path, Map<String, String> headers) {
        return NtkWebViewFallbackManager.get(context).fetch(agent, baseUrl, path, headers, currentRequestGroup.get());
    }

    static String buildNtkWebViewFetchScript(String path, Map<String, String> headers) {
        return buildNtkWebViewFetchScript(path, headers, null);
    }

    static String buildNtkWebViewFetchScript(String path, Map<String, String> headers, String token) {
        String accept = headers == null ? null : headers.get("Accept");
        StringBuilder js = new StringBuilder();
        js.append("(function(){");
        js.append("try{");
        js.append("var done=false;");
        if(token == null)
            js.append("function finish(v){if(done)return;done=true;window.NtkBridge.onResult(JSON.stringify(v));}");
        else
            js.append("function finish(v){if(done)return;done=true;window.NtkBridge.onFetchResult(").append(jsQuote(token)).append(",JSON.stringify(v));}");
        js.append("var x=new XMLHttpRequest();");
        js.append("x.open('GET',").append(jsQuote(path)).append(",true);");
        js.append("x.withCredentials=true;");
        js.append("x.timeout=8000;");
        if(accept != null && accept.length() > 0)
            js.append("x.setRequestHeader('Accept'," + jsQuote(accept) + ");");
        js.append("x.onreadystatechange=function(){if(x.readyState===4)finish({code:x.status,body:x.responseText||''});};");
        js.append("x.onerror=function(){finish({code:0,error:'error'});};");
        js.append("x.ontimeout=function(){finish({code:0,error:'timeout'});};");
        js.append("x.send(null);");
        js.append("}catch(e){var err=JSON.stringify({code:0,error:String(e)});");
        if(token == null)
            js.append("window.NtkBridge.onResult(err);");
        else
            js.append("window.NtkBridge.onFetchResult(").append(jsQuote(token)).append(",err);");
        js.append("}");
        js.append("})()");
        return js.toString();
    }

    static String buildNtkWebViewFetchScriptForTest(String path, String accept) {
        Map<String, String> headers = new HashMap<>();
        if(accept != null)
            headers.put("Accept", accept);
        return buildNtkWebViewFetchScript(path, headers);
    }

    private static String jsQuote(String value) {
        if(value == null)
            return "null";
        StringBuilder builder = new StringBuilder(value.length() + 2);
        builder.append('"');
        for(int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            switch(c) {
                case '\\':
                    builder.append("\\\\");
                    break;
                case '"':
                    builder.append("\\\"");
                    break;
                case '\n':
                    builder.append("\\n");
                    break;
                case '\r':
                    builder.append("\\r");
                    break;
                case '\t':
                    builder.append("\\t");
                    break;
                default:
                    builder.append(c);
                    break;
            }
        }
        builder.append('"');
        return builder.toString();
    }

    static Response responseFromWebViewFetch(String baseUrl, String path, String value) throws Exception {
        if(value == null || value.length() == 0 || "null".equals(value))
            return null;
        String clean = value;
        if(clean.startsWith("\"") && clean.endsWith("\""))
            clean = new JSONArray("[" + value + "]").getString(0);
        JSONObject json = new JSONObject(clean);
        int code = json.optInt("code", 0);
        if(code <= 0)
            return null;
        String body = json.optString("body", "");
        Request request = new Request.Builder().url(baseUrl + path).build();
        return new Response.Builder()
                .request(request)
                .protocol(Protocol.HTTP_1_1)
                .code(code)
                .message("WebView")
                .body(ResponseBody.create(body, MediaType.parse("text/plain; charset=utf-8")))
                .build();
    }

    static boolean shouldUseNtkWebViewFallbackForTest(boolean ntkUrl, boolean missingResponse, String path) {
        return shouldUseNtkWebViewFallbackForTest(ntkUrl, missingResponse, path, FetchMode.ALLOW_SHARED_WEBVIEW);
    }

    static boolean shouldUseNtkWebViewFallbackForTest(boolean ntkUrl, boolean missingResponse, String path, FetchMode fetchMode) {
        return shouldUseNtkWebViewFallback(ntkUrl, missingResponse, path, fetchMode);
    }

    static boolean shouldFastFailNtkPageForCaptchaForTest(boolean ntkUrl, String path, FetchMode fetchMode,
                                                          boolean hasAccessProof,
                                                          boolean hasRecentVerification,
                                                          boolean hasRecentChallenge) {
        return shouldFastFailNtkPageForCaptcha(ntkUrl, path, fetchMode, hasAccessProof,
                hasRecentVerification, hasRecentChallenge);
    }

    private static boolean shouldFastFailNtkPageForCaptcha(boolean ntkUrl, String path, FetchMode fetchMode,
                                                           boolean hasAccessProof,
                                                           boolean hasRecentVerification,
                                                           boolean hasRecentChallenge) {
        return ntkUrl
                && (fetchMode == FetchMode.ALLOW_SHARED_WEBVIEW
                || fetchMode == FetchMode.SEARCH_NO_WEBVIEW)
                && hasRecentChallenge
                && !hasAccessProof
                && !hasRecentVerification
                && isNtkWebViewFetchPath(path);
    }

    static boolean shouldSkipNtkHiddenWebViewFallbackAfterPageErrorForTest(boolean ntkUrl, String path,
                                                                           FetchMode fetchMode,
                                                                           boolean hasAccessProof,
                                                                           boolean hasRecentVerification,
                                                                           boolean hasRecentChallenge,
                                                                           Exception error) {
        return shouldSkipNtkHiddenWebViewFallbackAfterPageError(ntkUrl, path, fetchMode,
                hasAccessProof, hasRecentVerification, hasRecentChallenge, error);
    }

    private static boolean shouldSkipNtkHiddenWebViewFallbackAfterPageError(boolean ntkUrl, String path,
                                                                           FetchMode fetchMode,
                                                                           boolean hasAccessProof,
                                                                           boolean hasRecentVerification,
                                                                           boolean hasRecentChallenge,
                                                                           Exception error) {
        if(!ntkUrl || fetchMode != FetchMode.ALLOW_SHARED_WEBVIEW || hasAccessProof)
            return false;
        if(!isNtkWebViewFetchPath(path))
            return false;
        String message = error == null ? null : error.getMessage();
        String lower = message == null ? "" : message.toLowerCase(Locale.ROOT);
        if(hasRecentChallenge && !hasRecentVerification)
            return true;
        if(isNtkTitleDocumentPath(path)
                && lower.contains("unusable ntk page")
                && lower.contains("code=403"))
            return true;
        if(lower.contains("ntk hard block") && lower.contains("code=403"))
            return true;
        if(message == null)
            return false;
        return !hasRecentVerification && (lower.contains("cloudflare") || lower.contains("challenge"));
    }

    static boolean shouldPrioritizeNtkEpisodeWebViewForTest(boolean ntkUrl, String path,
                                                            FetchMode fetchMode,
                                                            RequestGroup requestGroup) {
        return shouldPrioritizeNtkEpisodeWebView(ntkUrl, path, fetchMode, requestGroup);
    }

    static boolean shouldAttemptNtkNativeAckPageRecoveryForTest(boolean ntkUrl, boolean missingResponse,
                                                                int code, String path,
                                                                FetchMode fetchMode) {
        return shouldAttemptNtkNativeAckPageRecovery(ntkUrl, missingResponse, code, path, fetchMode);
    }

    static boolean shouldAttemptNtkRscNativeAckRecoveryForTest(boolean ntkUrl, boolean challengeResponse,
                                                               String path, FetchMode fetchMode) {
        return shouldAttemptNtkRscNativeAckRecovery(ntkUrl, challengeResponse, path, fetchMode);
    }

    private static boolean shouldAttemptNtkNativeAckPageRecovery(boolean ntkUrl, Response response,
                                                                 String path, FetchMode fetchMode) {
        return shouldAttemptNtkNativeAckPageRecovery(ntkUrl, response == null,
                response == null ? 0 : response.code(), path, fetchMode);
    }

    private static boolean shouldAttemptNtkNativeAckPageRecovery(boolean ntkUrl, boolean missingResponse,
                                                                 int code, String path, FetchMode fetchMode) {
        if(!ntkUrl || path == null || fetchMode == FetchMode.CACHE_ONLY)
            return false;
        if(!isNtkEpisodeDocumentPath(path) || path.startsWith("/api/ad/"))
            return false;
        return missingResponse || code == 403 || code >= 500;
    }

    private static boolean shouldAttemptNtkRscNativeAckRecovery(boolean ntkUrl, boolean challengeResponse,
                                                                String path, FetchMode fetchMode) {
        if(!ntkUrl || !challengeResponse || path == null || fetchMode == FetchMode.CACHE_ONLY)
            return false;
        if(path.startsWith("/api/ad/"))
            return false;
        return isNtkNavigableDocumentPath(path);
    }

    private static boolean isModernNtkGuardRoot(String root) {
        root = root == null ? "" : root.toLowerCase(Locale.ROOT);
        return root.contains("sbxh") || root.contains("toonflix") || root.contains("newtoki");
    }

    public boolean isModernNtkGuardRootForPath(String path) {
        try {
            return isModernNtkGuardRoot(getUrl(path));
        } catch (Exception e) {
            return false;
        }
    }

    private NtkQuicFetcher.Result fetchNtkForegroundImageRace(String baseUrl, String url,
                                                              String cookieHeader,
                                                              Map<String, String> headers) throws Exception {
        ExecutorService raceExecutor = Executors.newFixedThreadPool(2, runnable -> {
            Thread thread = new Thread(runnable, "ntk-foreground-image-race");
            thread.setDaemon(true);
            return thread;
        });
        ExecutorCompletionService<NtkImageFetchResult> completion =
                new ExecutorCompletionService<>(raceExecutor);
        List<Future<NtkImageFetchResult>> futures = new ArrayList<>();
        long startedAt = System.currentTimeMillis();
        try {
            futures.add(completion.submit(() -> {
                try {
                    return new NtkImageFetchResult("httpengine",
                            fetchNtkForegroundImageHttpEngine(baseUrl, url, cookieHeader, headers));
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return new NtkImageFetchResult("httpengine", NtkQuicFetcher.Result.error(e));
                } catch (Exception e) {
                    return new NtkImageFetchResult("httpengine", NtkQuicFetcher.Result.error(e));
                }
            }));
            NtkQuicFetcher.Result fallback = null;
            boolean backupSubmitted = false;
            boolean generatedCdn = isNtkGeneratedImageCdnUrl(url);
            long deadlineAt = startedAt + NTK_FOREGROUND_IMAGE_RACE_TIMEOUT_MS;
            for(int completed = 0; completed < futures.size(); completed++) {
                long waitMs = Math.max(1L, deadlineAt - System.currentTimeMillis());
                if(!backupSubmitted)
                    waitMs = Math.min(waitMs, generatedCdn
                            ? NTK_FOREGROUND_IMAGE_GENERATED_HEDGE_DELAY_MS
                            : NTK_FOREGROUND_IMAGE_BACKUP_DELAY_MS);
                Future<NtkImageFetchResult> future = completion.poll(waitMs, TimeUnit.MILLISECONDS);
                if(future == null && !backupSubmitted) {
                    backupSubmitted = true;
                    futures.add(completion.submit(() -> {
                        String transport = generatedCdn ? "httpengine-hedge" : "okhttp-range";
                        try {
                            NtkQuicFetcher.Result result = generatedCdn
                                    ? fetchNtkForegroundImageHttpEngine(baseUrl, url, cookieHeader, headers)
                                    : fetchNtkForegroundImageOkHttpRange(url, cookieHeader, headers);
                            return new NtkImageFetchResult(transport, result);
                        } catch (Exception e) {
                            return new NtkImageFetchResult(transport,
                                    NtkQuicFetcher.Result.error(e));
                        }
                    }));
                    completed--;
                    continue;
                }
                if(future == null)
                    break;
                NtkImageFetchResult raceResult = future.get();
                NtkQuicFetcher.Result result = normalizeNtkImageResult(raceResult == null ? null : raceResult.result);
                String transport = raceResult == null ? "" : raceResult.transport;
                if(result != null && result.error == null && result.headers != null && raceResult != null)
                    result.headers.put("x-mangaviewer-transport",
                            Collections.singletonList(raceResult.transport));
                boolean accepted = isUsableNtkQuicGetResult(result)
                        && isLikelyPartialDecodableImage(result.headers, result.bodyBytes);
                Log.d(TAG, "ntk_foreground_image_race_done transport="
                        + (transport.length() == 0 ? "unknown" : transport)
                        + ",code=" + (result == null ? 0 : result.code)
                        + ",bytes=" + (result == null || result.bodyBytes == null ? 0 : result.bodyBytes.length)
                        + ",accepted=" + accepted
                        + ",block=" + ntkImageHardBlockReason(result)
                        + ",head=" + ntkShortBodyHead(result == null ? null : result.bodyBytes)
                        + ",error=" + (result == null ? null : result.error)
                        + ",ms=" + (System.currentTimeMillis() - startedAt)
                        + ",url=" + safeLogUrl(url));
                if(result != null && fallback == null)
                    fallback = result;
                if(isNtkDefinitiveImageMiss(result))
                    return result;
                if(accepted)
                    return result;
            }
            return null;
        } finally {
            for(Future<NtkImageFetchResult> future : futures) {
                if(future != null && !future.isDone())
                    future.cancel(true);
            }
            raceExecutor.shutdownNow();
        }
    }

    private NtkQuicFetcher.Result fetchNtkForegroundImageHttpEngine(String baseUrl, String url,
                                                                    String cookieHeader,
                                                                    Map<String, String> headers) throws Exception {
        return fetchNtkQuic(baseUrl, url, cookieHeader, headers, "GET", null,
                NTK_QUIC_IMAGE_TIMEOUT_MS);
    }

    private static String ntkShortBodyHead(byte[] bytes) {
        if(bytes == null || bytes.length == 0 || bytes.length > 512)
            return "";
        int count = Math.min(bytes.length, 48);
        byte[] head = Arrays.copyOf(bytes, count);
        return Base64.encodeToString(head, Base64.NO_WRAP);
    }

    private static NtkQuicFetcher.Result normalizeNtkImageResult(NtkQuicFetcher.Result result) {
        if(result == null || result.error != null || result.bodyBytes == null)
            return result;
        if(result.code == 200 && isNtkHtmlNotFoundBody(result.bodyBytes)) {
            Map<String, List<String>> headers = result.headers == null
                    ? new HashMap<>() : new HashMap<>(result.headers);
            headers.put("x-mangaviewer-normalized-status", Collections.singletonList("404"));
            return NtkQuicFetcher.Result.fromBytes(404, result.bodyBytes, headers);
        }
        String hardBlock = ntkImageHardBlockReason(result);
        if(hardBlock.length() > 0) {
            Map<String, List<String>> headers = result.headers == null
                    ? new HashMap<>() : new HashMap<>(result.headers);
            headers.put("x-mangaviewer-ntk-image-hard-block",
                    Collections.singletonList(hardBlock));
            return NtkQuicFetcher.Result.fromBytes(result.code, result.bodyBytes, headers);
        }
        return result;
    }

    private static String ntkImageHardBlockReason(NtkQuicFetcher.Result result) {
        if(result == null || result.error != null || result.bodyBytes == null)
            return "";
        String reason = ntkImageHardBlockReason(result.code, contentTypeHeader(result.headers),
                result.bodyBytes);
        if(reason.length() > 0)
            return reason;
        if(result.code == 403
                && contentTypeHeader(result.headers).toLowerCase(Locale.ROOT).contains("text/html")
                && hasNtkCloudflareResponseHeader(result.headers))
            return "cloudflare-html-403";
        return "";
    }

    private static String ntkImageHardBlockReason(int code, String contentType, byte[] bytes) {
        if(code != 403 || bytes == null || bytes.length == 0 || bytes.length > 16 * 1024)
            return "";
        String type = contentType == null ? "" : contentType.toLowerCase(Locale.ROOT);
        if(type.length() > 0 && !type.contains("text/html") && !type.contains("text/plain"))
            return "";
        String head = new String(bytes, 0, Math.min(bytes.length, 4096), StandardCharsets.UTF_8)
                .toLowerCase(Locale.ROOT);
        if(head.contains("website access blocked")
                && head.contains("cloudflare")
                && (head.contains("terms of service") || head.contains("tos")))
            return "cloudflare-tos";
        if(head.contains("www.cloudflare-terms-of-service-abuse.com"))
            return "cloudflare-tos";
        return "";
    }

    static String ntkImageHardBlockReasonForTest(int code, String contentType, String body) {
        return ntkImageHardBlockReason(code, contentType,
                body == null ? null : body.getBytes(StandardCharsets.UTF_8));
    }

    static String ntkImageHardBlockReasonForTest(int code, Map<String, List<String>> headers,
                                                 String body) {
        NtkQuicFetcher.Result result = NtkQuicFetcher.Result.fromBytes(code,
                body == null ? new byte[0] : body.getBytes(StandardCharsets.UTF_8), headers);
        return ntkImageHardBlockReason(result);
    }

    private static boolean hasNtkCloudflareResponseHeader(Map<String, List<String>> headers) {
        if(headers == null || headers.isEmpty())
            return false;
        for(String key : headers.keySet()) {
            if(key == null)
                continue;
            String lowerKey = key.toLowerCase(Locale.ROOT);
            List<String> values = headers.get(key);
            if("cf-ray".equals(lowerKey) || "cf-cache-status".equals(lowerKey))
                return true;
            if(!"server".equals(lowerKey) || values == null)
                continue;
            for(String value : values) {
                if(value != null && value.toLowerCase(Locale.ROOT).contains("cloudflare"))
                    return true;
            }
        }
        return false;
    }

    private static boolean isNtkHtmlNotFoundBody(byte[] bytes) {
        if(bytes == null || bytes.length == 0 || bytes.length > 1024)
            return false;
        String head = new String(bytes, 0, Math.min(bytes.length, 256), StandardCharsets.UTF_8)
                .toLowerCase(Locale.ROOT);
        return head.contains("<html") && head.contains("404 not found");
    }

    private static boolean isNormalizedNtkNotFound(NtkQuicFetcher.Result result) {
        if(result == null || result.headers == null || result.code != 404)
            return false;
        for(String key : result.headers.keySet()) {
            if(!"x-mangaviewer-normalized-status".equalsIgnoreCase(key))
                continue;
            List<String> values = result.headers.get(key);
            return values != null && values.contains("404");
        }
        return false;
    }

    private static boolean isNtkDefinitiveImageMiss(NtkQuicFetcher.Result result) {
        return result != null && result.error == null && result.code >= 400 && result.code < 500;
    }

    private NtkQuicFetcher.Result fetchNtkForegroundImageOkHttpRange(String url, String cookieHeader,
                                                                     Map<String, String> headers) {
        NtkQuicFetcher.Result first = fetchNtkForegroundImageOkHttpRangeBytes(
                url, cookieHeader, headers, NTK_FOREGROUND_PARTIAL_IMAGE_BYTES);
        if(isUsableNtkQuicGetResult(first)
                && isLikelyPartialDecodableImage(first.headers, first.bodyBytes))
            return first;
        if(first == null || first.code < 200 || first.code >= 300)
            return first;
        return fetchNtkForegroundImageOkHttpRangeBytes(
                url, cookieHeader, headers, NTK_FOREGROUND_PARTIAL_IMAGE_RETRY_BYTES);
    }

    private NtkQuicFetcher.Result fetchNtkForegroundImageOkHttpFull(String url, String cookieHeader,
                                                                    Map<String, String> headers) {
        Response response = null;
        try {
            Request.Builder builder = new Request.Builder()
                    .url(url)
                    .get()
                    .header("User-Agent", agent)
                    .header("Accept-Encoding", "identity")
                    .header(NTK_NO_QUIC_HEADER, "1");
            if(headers != null) {
                for(String key : headers.keySet()) {
                    String value = headers.get(key);
                    if(value == null || value.length() == 0)
                        continue;
                    if("Range".equalsIgnoreCase(key) || "Accept-Encoding".equalsIgnoreCase(key)
                            || "X-MangaViewer-Foreground".equalsIgnoreCase(key)
                            || "X-MangaViewer-Anchor-Hedge".equalsIgnoreCase(key)
                            || NTK_NO_QUIC_HEADER.equalsIgnoreCase(key))
                        continue;
                    builder.header(key, value);
                }
            }
            if(cookieHeader != null && cookieHeader.length() > 0)
                builder.header("Cookie", cookieHeader);
            response = ntkPageFastClient.newCall(builder.build()).execute();
            byte[] bytes = response.body() == null ? new byte[0] : response.body().bytes();
            Map<String, List<String>> responseHeaders = new HashMap<>(response.headers().toMultimap());
            return NtkQuicFetcher.Result.fromBytes(response.code(), bytes, responseHeaders);
        } catch(Exception e) {
            return NtkQuicFetcher.Result.error(e);
        } finally {
            if(response != null)
                response.close();
        }
    }

    private NtkQuicFetcher.Result fetchNtkForegroundImageOkHttpRangeBytes(String url, String cookieHeader,
                                                                          Map<String, String> headers,
                                                                          int byteCount) {
        Response response = null;
        try {
            Request.Builder builder = new Request.Builder()
                    .url(url)
                    .get()
                    .header("User-Agent", agent)
                    .header("Range", "bytes=0-" + (byteCount - 1))
                    .header("Accept-Encoding", "identity")
                    .header(NTK_NO_QUIC_HEADER, "1");
            if(headers != null) {
                for(String key : headers.keySet()) {
                    String value = headers.get(key);
                    if(value == null || value.length() == 0)
                        continue;
                    if("Range".equalsIgnoreCase(key) || "Accept-Encoding".equalsIgnoreCase(key)
                            || "X-MangaViewer-Foreground".equalsIgnoreCase(key)
                            || "X-MangaViewer-Anchor-Hedge".equalsIgnoreCase(key)
                            || NTK_NO_QUIC_HEADER.equalsIgnoreCase(key))
                        continue;
                    builder.header(key, value);
                }
            }
            if(cookieHeader != null && cookieHeader.length() > 0)
                builder.header("Cookie", cookieHeader);
            response = ntkPageFastClient.newCall(builder.build()).execute();
            byte[] bytes = response.body() == null ? new byte[0] : response.body().bytes();
            Map<String, List<String>> responseHeaders = new HashMap<>(response.headers().toMultimap());
            responseHeaders.put("x-mangaviewer-partial-image", Collections.singletonList("1"));
            return NtkQuicFetcher.Result.fromBytes(response.code(), bytes, responseHeaders);
        } catch(Exception e) {
            return NtkQuicFetcher.Result.error(e);
        } finally {
            if(response != null)
                response.close();
        }
    }

    public Response fetchNtkGeneratedImageRange(String url, Map<String, String> headers,
                                                long start, long end) throws IOException {
        Map<String, String> requestHeaders = new LinkedHashMap<>();
        if(headers != null) {
            for(String key : headers.keySet()) {
                String value = headers.get(key);
                if(value == null || value.length() == 0)
                    continue;
                if("X-MangaViewer-Foreground".equalsIgnoreCase(key)
                        || "X-MangaViewer-Anchor-Hedge".equalsIgnoreCase(key)
                        || NTK_NO_QUIC_HEADER.equalsIgnoreCase(key))
                    continue;
                requestHeaders.put(key, value);
            }
        }
        requestHeaders.put("User-Agent", agent);
        requestHeaders.put("Range", "bytes=" + start + "-" + end);
        requestHeaders.put("Accept-Encoding", "identity");
        if(headerValue(requestHeaders, "Cookie") == null) {
            String cookieHeader = getCookieHeader();
            if(cookieHeader != null && cookieHeader.length() > 0)
                requestHeaders.put("Cookie", cookieHeader);
        }
        return get(url, requestHeaders);
    }

    private static String ntkResultTransport(NtkQuicFetcher.Result result, String fallback) {
        if(result == null || result.headers == null)
            return fallback;
        for(String key : result.headers.keySet()) {
            if("x-mangaviewer-transport".equalsIgnoreCase(key)) {
                List<String> values = result.headers.get(key);
                if(values != null && !values.isEmpty())
                    return values.get(0);
            }
        }
        return fallback;
    }

    private static final class NtkImageFetchResult {
        final String transport;
        final NtkQuicFetcher.Result result;

        NtkImageFetchResult(String transport, NtkQuicFetcher.Result result) {
            this.transport = transport;
            this.result = result;
        }
    }

    private static boolean shouldPrioritizeNtkEpisodeWebView(boolean ntkUrl, String path,
                                                             FetchMode fetchMode,
                                                             RequestGroup requestGroup) {
        return ntkUrl
                && fetchMode == FetchMode.ALLOW_SHARED_WEBVIEW
                && requestGroup != null
                && requestGroup.prioritizesWebViewFallback()
                && isNtkEpisodeDocumentPath(path);
    }

    private static boolean shouldUseNtkWebViewFallback(boolean ntkUrl, boolean missingResponse, String path, FetchMode fetchMode) {
        return HttpDocumentPolicy.shouldUseNtkWebViewFallback(ntkUrl, missingResponse, path, fetchMode);
    }

    static boolean isNtkEpisodeDocumentPathForTest(String path) {
        return isNtkEpisodeDocumentPath(path);
    }

    private static boolean isNtkEpisodeDocumentPath(String path) {
        return HttpDocumentPolicy.isNtkEpisodeDocumentPath(path);
    }

    static boolean isNtkTitleDocumentPathForTest(String path) {
        return isNtkTitleDocumentPath(path);
    }

    private static boolean isNtkTitleDocumentPath(String path) {
        return HttpDocumentPolicy.isNtkTitleDocumentPath(path);
    }

    private static boolean isNtkNavigableDocumentPath(String path) {
        return HttpDocumentPolicy.isNtkNavigableDocumentPath(path);
    }

    private static boolean isNtkCategoryDocumentPath(String path) {
        return HttpDocumentPolicy.isNtkCategoryDocumentPath(path);
    }

    private static boolean isNtkWebViewFetchPath(String path) {
        return HttpDocumentPolicy.isNtkWebViewFetchPath(path);
    }

    private static boolean isNtkApiPath(String path) {
        return HttpDocumentPolicy.isNtkApiPath(path);
    }

    private static boolean isNtkSearchPath(String path) {
        return HttpDocumentPolicy.isNtkSearchPath(path);
    }

    private static String ntkNativeAckProbePath(String path) {
        if(path == null || path.length() == 0)
            return "/";
        if(path.startsWith("/api/manhwa-list"))
            return "/manhwa";
        if(path.startsWith("/api/works") || path.startsWith("/api/webtoon"))
            return "/webtoon";
        if(path.startsWith("/api/"))
            return "/manhwa";
        return path;
    }

    static boolean shouldUseSharedWebViewFallbackForTest(boolean ntkUrl, boolean missingResponse, String path, FetchMode fetchMode) {
        return shouldUseSharedWebViewFallback(ntkUrl, missingResponse, path, fetchMode);
    }

    private static boolean shouldUseSharedWebViewFallback(boolean ntkUrl, boolean missingResponse, String path, FetchMode fetchMode) {
        return shouldUseSharedWebViewFallback(ntkUrl, missingResponse, path, fetchMode, true);
    }

    static boolean shouldUseSharedWebViewFallbackForTest(boolean ntkUrl, boolean missingResponse, String path,
                                                        FetchMode fetchMode, boolean allowWolfWebViewFallback) {
        return shouldUseSharedWebViewFallback(ntkUrl, missingResponse, path, fetchMode, allowWolfWebViewFallback);
    }

    private static boolean shouldUseSharedWebViewFallback(boolean ntkUrl, boolean missingResponse, String path,
                                                         FetchMode fetchMode, boolean allowWolfWebViewFallback) {
        return HttpDocumentPolicy.shouldUseSharedWebViewFallback(ntkUrl, missingResponse, path, fetchMode, allowWolfWebViewFallback);
    }

    private static boolean shouldUseWolfWebViewFallback(boolean ntkUrl, boolean missingResponse, String path,
                                                       FetchMode fetchMode, boolean allowWolfWebViewFallback) {
        return HttpDocumentPolicy.shouldUseWolfWebViewFallback(ntkUrl, missingResponse, path, fetchMode,
                allowWolfWebViewFallback);
    }

    static boolean isWolfEpisodeDocumentPathForTest(String path) {
        return isWolfEpisodeDocumentPath(path);
    }

    static boolean isWolfEpisodeDocumentPath(String path) {
        return HttpDocumentPolicy.isWolfEpisodeDocumentPath(path);
    }

    static boolean shouldUseFastNtkPageDirectForTest(boolean ntkUrl, String path, FetchMode fetchMode) {
        return shouldUseFastNtkPageDirect(ntkUrl, path, fetchMode);
    }

    private static boolean shouldUseFastNtkPageDirect(boolean ntkUrl, String path, FetchMode fetchMode) {
        return HttpDocumentPolicy.shouldUseFastNtkPageDirect(ntkUrl, path, fetchMode);
    }

    static boolean shouldUseFastNtkApiDirectUrlForTest(String url) {
        return shouldUseFastNtkApiDirectUrl(url);
    }

    private static boolean shouldUseFastNtkApiDirectUrl(String url) {
        if(!isNtkUrlForTest(url))
            return false;
        try {
            HttpUrl parsed = HttpUrl.parse(url);
            String path = parsed == null ? "" : parsed.encodedPath();
            return isNtkApiPath(path) || isNtkSearchPath(path);
        } catch (Exception e) {
            return false;
        }
    }

    private Map<String, String> buildHeaders(String baseUrl, Boolean useDefaultCookies, Map<String, String> customCookie) {
        Map<String, String> cookie = new HashMap<>();
        if(Boolean.TRUE.equals(useDefaultCookies)) {
            if(!shouldSkipWebViewCookieSync(baseUrl))
                syncCookiesFromWebView(baseUrl);
            synchronized (this) {
                if(removeExpiredNtkAckCookiesLocked())
                    persistCookies();
                cookie.putAll(this.cookies);
            }
        }
        if(customCookie != null)
            cookie.putAll(customCookie);

        StringBuilder cbuilder = new StringBuilder();
        for(String key : cookie.keySet()){
            cbuilder.append(key);
            cbuilder.append('=');
            cbuilder.append(cookie.get(key));
            cbuilder.append("; ");
        }
        if(cbuilder.length()>2)
            cbuilder.delete(cbuilder.length()-2,cbuilder.length());

        Map<String, String> headers = new HashMap<>();
        headers.put("Cookie", cbuilder.toString());
        headers.put("User-Agent", agent);
        headers.put("Referer", baseUrl);
        if(isNtkUrl(baseUrl)) {
            headers.put("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,image/apng,*/*;q=0.8");
            headers.put("Accept-Language", "ko-KR,ko;q=0.9,en-US;q=0.8,en;q=0.7");
            headers.put("Cache-Control", "max-age=0");
            headers.put("Upgrade-Insecure-Requests", "1");
            headers.put("Sec-Fetch-Dest", "document");
            headers.put("Sec-Fetch-Mode", "navigate");
            headers.put("Sec-Fetch-Site", "same-origin");
            headers.put("Sec-Fetch-User", "?1");
            putClientHintHeaders(headers);
        }
        return headers;
    }

    private boolean shouldSkipWebViewCookieSync(String baseUrl) {
        return shouldSkipWebViewCookieSyncForState(isNtkUrl(baseUrl),
                hasCloudflareClearance(),
                hasRecentNtkAccessVerification(),
                hasFreshCloudflareClearance(),
                effectiveFetchMode(FetchMode.ALLOW_SHARED_WEBVIEW));
    }

    static boolean shouldSkipWebViewCookieSyncForTest(boolean ntkUrl, boolean hasClearance,
                                                      boolean hasRecentVerification, boolean hasFreshClearance,
                                                      FetchMode fetchMode) {
        return shouldSkipWebViewCookieSyncForState(ntkUrl, hasClearance, hasRecentVerification, hasFreshClearance, fetchMode);
    }

    private static boolean shouldSkipWebViewCookieSyncForState(boolean ntkUrl, boolean hasClearance,
                                                               boolean hasRecentVerification, boolean hasFreshClearance,
                                                               FetchMode fetchMode) {
        if(!ntkUrl)
            return false;
        if(hasFreshClearance || hasRecentVerification)
            return true;
        return fetchMode == FetchMode.DIRECT_ONLY || fetchMode == FetchMode.CACHE_ONLY;
    }

    private void applyNtkApiHeaders(Map<String, String> headers, String baseUrl, String path) {
        if(headers == null || !isNtkUrl(baseUrl) || path == null || !path.startsWith("/api/"))
            return;
        headers.put("Accept", "application/json,text/plain,*/*");
        headers.put("Sec-Fetch-Dest", "empty");
        headers.put("Sec-Fetch-Mode", "cors");
        headers.put("Sec-Fetch-Site", "same-origin");
        headers.remove("Upgrade-Insecure-Requests");
        headers.remove("Sec-Fetch-User");
    }

    private void applyNtkScopedCookieHeader(Map<String, String> headers, boolean ntkBaseUrl, String path,
                                            Boolean useDefaultCookies, Map<String, String> customCookie) {
        if(headers == null || !ntkBaseUrl || !Boolean.TRUE.equals(useDefaultCookies))
            return;
        headers.put("Cookie", getCookieHeaderForNtkPath(path, customCookie));
    }

    private void putClientHintHeaders(Map<String, String> headers) {
        headers.put("sec-ch-ua", clientHintUa(agent));
        headers.put("sec-ch-ua-mobile", clientHintMobile(agent));
        headers.put("sec-ch-ua-platform", clientHintPlatform(agent));
    }

    public static String clientHintUa(String userAgent) {
        int chromeMajor = chromeMajorVersion(userAgent);
        String version = chromeMajor > 0 ? String.valueOf(chromeMajor) : "147";
        if(isDesktopUserAgent(userAgent))
            return "\"Chromium\";v=\"" + version + "\", \"Google Chrome\";v=\"" + version + "\", \"Not_A Brand\";v=\"24\"";
        return "\"Chromium\";v=\"" + version + "\", \"Android WebView\";v=\"" + version + "\", \"Not A(Brand\";v=\"24\"";
    }

    public static String clientHintMobile(String userAgent) {
        return isDesktopUserAgent(userAgent) ? "?0" : "?1";
    }

    public static String clientHintPlatform(String userAgent) {
        return isDesktopUserAgent(userAgent) ? "\"Windows\"" : "\"Android\"";
    }

    public static boolean isDesktopUserAgent(String userAgent) {
        String value = userAgent == null ? "" : userAgent.toLowerCase(Locale.ROOT);
        return value.contains("windows nt") || value.contains("macintosh") || value.contains("x11; linux");
    }

    private static int chromeMajorVersion(String userAgent) {
        try {
            if(userAgent == null)
                return -1;
            java.util.regex.Matcher matcher = java.util.regex.Pattern.compile("Chrome/(\\d+)").matcher(userAgent);
            if(!matcher.find())
                return -1;
            return Integer.parseInt(matcher.group(1));
        } catch (Exception e) {
            return -1;
        }
    }

    private boolean shouldRetryWithResolvedDomain(Response response, String path) {
        if(response == null)
            return true;
        int code = response.code();
        if(isNtk()) {
            String body = "";
            if(code == 403 || code >= 500) {
                try {
                    body = response.peekBody(256 * 1024L).string();
                } catch (Exception ignored) {
                }
            }
            return shouldRetryNtkWithResolvedDomain(code, body, path);
        }
        return code == 301 || code == 302 || code == 403 || code == 404 || code >= 500;
    }

    private static boolean shouldRetryNtkWithResolvedDomain(int code, String body) {
        return shouldRetryNtkWithResolvedDomain(code, body, null);
    }

    private static boolean shouldRetryNtkWithResolvedDomain(int code, String body, String path) {
        if(code == 403 && isCloudflareChallenge(code, body))
            return false;
        if(code == 301 || code == 302 || code == 403 || code == 404 || code >= 500)
            return true;
        return false;
    }

    static boolean shouldRetryNtkWithResolvedDomainForTest(int code, String body) {
        return shouldRetryNtkWithResolvedDomain(code, body);
    }

    static boolean shouldSkipNtkResolvedDomainRetryAfterChallengeForTest(FetchMode fetchMode,
                                                                         boolean responseMissing,
                                                                         boolean recentCloudflareChallenge,
                                                                         String path) {
        return shouldSkipNtkResolvedDomainRetryAfterChallenge(fetchMode, responseMissing,
                recentCloudflareChallenge, path);
    }

    private static boolean shouldSkipNtkResolvedDomainRetryAfterChallenge(FetchMode fetchMode,
                                                                          boolean responseMissing,
                                                                          boolean recentCloudflareChallenge,
                                                                          String path) {
        return responseMissing
                && recentCloudflareChallenge
                && (fetchMode == FetchMode.SEARCH_NO_WEBVIEW || fetchMode == FetchMode.ALLOW_SHARED_WEBVIEW)
                && isNtkWebViewFetchPath(path);
    }

    private String normalizePath(String url) {
        if(url == null || url.length() == 0)
            return "/";
        return url.startsWith("/") ? url : "/" + url;
    }

    private static boolean looksCacheable(String body) {
        if(!isCacheablePageBody(body))
            return false;
        String lower = body.toLowerCase(Locale.ROOT);
        return lower.contains("webtoon-list")
                || lower.contains("searchitem")
                || lower.contains("toon=")
                || lower.contains("/webtoon/")
                || lower.contains("/manhwa/")
                || lower.contains("image-view")
                || lower.contains("webtoon-body")
                || lower.contains("miso-post-gallery")
                || lower.contains("post-row")
                || (lower.contains("\"works\"")
                && (lower.contains("\"sourceworkid\"") || lower.contains("\"thumbnailurl\"")));
    }

    private static boolean isWfwfDocumentPath(String path) {
        if(path == null)
            return false;
        return isWolfEpisodeDocumentPath(path)
                || path.startsWith("/cm")
                || path.startsWith("/ing")
                || path.startsWith("/end")
                || path.startsWith("/webtoon")
                || path.startsWith("/comic")
                || path.startsWith("/search.html");
    }

    private static boolean isWfwfSearchPath(String path) {
        return path != null && path.startsWith("/search.html");
    }

    static boolean isCacheablePageBodyForTest(String body) {
        return isCacheablePageBody(body);
    }

    static boolean looksCacheableForTest(String body) {
        return looksCacheable(body);
    }

    private static boolean isUsableCachedPage(CachedPage page) {
        return page != null && page.body != null && page.body.length() > 0 && isCacheablePageBody(page.body);
    }

    private static boolean isCacheablePageBody(String body) {
        if(body == null || body.length() == 0)
            return false;
        String lower = body.toLowerCase(Locale.ROOT);
        return !lower.contains("<title>webpage not available</title>")
                && !lower.contains("webpage not available")
                && !lower.contains("net::err_")
                && !lower.contains("err_connection_reset")
                && !lower.contains("err_name_not_resolved")
                && !lower.contains("err_timed_out")
                && !lower.contains("error code 522")
                && !lower.contains("connection timed out")
                && !lower.contains("warninge.kcopa.or.kr")
                && !lower.contains("domain parking")
                && !lower.contains("sedo domain parking")
                && !lower.contains("godaddy")
                && !lower.contains("window.location.href=\"/lander")
                && !lower.contains("window.location.href='/lander")
                && !containsCloudflareChallengeMarker(lower)
                && !containsNtkBlockedDocumentMarker(lower)
                && !looksLikeUnrenderedNtkAppShell(lower);
    }

    static boolean looksLikeUnrenderedNtkDocumentForTest(String path, int code, String body) {
        return looksLikeUnrenderedNtkDocument(path, code, body);
    }

    static boolean looksLikeNtkRecoverableErrorFallbackDocumentForTest(String path, int code, String body) {
        return looksLikeNtkRecoverableErrorFallbackDocument(path, code, body);
    }

    private static boolean looksLikeNtkRecoverableErrorFallbackDocument(String path, int code, String body) {
        if(code < 200 || code >= 400 || body == null || body.length() == 0)
            return false;
        if(!isNtkNavigableDocumentPath(path))
            return false;
        String lower = body.toLowerCase(Locale.ROOT);
        if(!(lower.contains("next_http_error_fallback") || lower.contains("id=\"__next_error__")
                || lower.contains("id='__next_error__")))
            return false;
        return lower.contains("/_next/static/") || lower.contains("self.__next_f")
                || lower.contains("id=\"__next\"") || lower.contains("id='__next'");
    }

    private static boolean looksLikeUnrenderedNtkDocument(String path, int code, String body) {
        if(code < 200 || code >= 400 || body == null || body.length() == 0)
            return false;
        if(path != null && !isNtkNavigableDocumentPath(path))
            return false;
        if(looksLikeEmptyNtkRecoveredDocument(path, body))
            return true;
        String lower = body.toLowerCase(Locale.ROOT);
        if(!looksLikeNtkNextShell(lower))
            return false;
        if(isNtkEpisodeDocumentPath(path))
            return !hasRenderedNtkEpisodeViewerContent(lower);
        return !hasRenderedNtkDocumentContent(lower);
    }

    private static boolean looksLikeEmptyNtkRecoveredDocument(String path, String body) {
        if(body == null)
            return true;
        if(path != null && !isNtkNavigableDocumentPath(path))
            return false;
        String trimmed = body.trim();
        if(trimmed.length() == 0)
            return true;
        String lower = trimmed.toLowerCase(Locale.ROOT);
        if(trimmed.length() < 160 && lower.contains("<html") && lower.contains("<body"))
            return true;
        return lower.matches("(?s)^\\s*<!?doctype[^>]*>\\s*<html[^>]*>\\s*<head[^>]*>\\s*</head>\\s*<body[^>]*>\\s*</body>\\s*</html>\\s*$")
                || lower.matches("(?s)^\\s*<html[^>]*>\\s*<head[^>]*>\\s*</head>\\s*<body[^>]*>\\s*</body>\\s*</html>\\s*$");
    }

    private static boolean looksLikeNtkNextShell(String lowerBody) {
        if(lowerBody == null)
            return false;
        return (lowerBody.contains("/_next/static/") || lowerBody.contains("self.__next_f")
                || lowerBody.contains("id=\"__next\"") || lowerBody.contains("id='__next'"))
                && (lowerBody.contains("%5bsourceworkid%5d") || lowerBody.contains("[sourceworkid]")
                || lowerBody.contains("%5bviewid%5d") || lowerBody.contains("[viewid]")
                || lowerBody.contains("next-route-announcer") || lowerBody.contains("app-router-announcer"));
    }

    private static boolean looksLikeUnrenderedNtkAppShell(String lowerBody) {
        return looksLikeNtkNextShell(lowerBody) && !hasRenderedNtkDocumentContent(lowerBody);
    }

    private static boolean hasNtkViewerShellData(String lowerBody) {
        if(lowerBody == null || lowerBody.length() == 0)
            return false;
        return lowerBody.contains("\"sourceworkid\"")
                || lowerBody.contains("\\\"sourceworkid\\\"")
                || lowerBody.contains("\"thumbnailurl\"")
                || lowerBody.contains("\\\"thumbnailurl\\\"")
                || lowerBody.contains("/blacktoon/episodes/")
                || lowerBody.contains("/wt/episodes/");
    }

    private static boolean hasRenderedNtkDocumentContent(String lowerBody) {
        if(lowerBody == null || lowerBody.length() == 0)
            return false;
        if(hasNtkViewerShellData(lowerBody))
            return true;
        if(lowerBody.contains("imagestoken") && lowerBody.contains("imagemetas"))
            return true;
        if(lowerBody.contains("vw-main") || lowerBody.contains("vw-imgs")
                || lowerBody.contains("viewer-content") || lowerBody.contains("toon-view")
                || lowerBody.contains("image-view") || lowerBody.contains("webtoon-body"))
            return true;
        if(lowerBody.contains("/webtoon_uploads/") || lowerBody.contains("/manhwa_uploads/")
                || lowerBody.contains("/comic_uploads/") || lowerBody.contains("/blacktoon/episodes/"))
            return true;
        Matcher matcher = Pattern.compile("href\\s*=\\s*['\"][^'\"]*/(?:webtoon|manhwa)/([^'\"/?#%]+)/([^'\"/?#%]+)").matcher(lowerBody);
        while(matcher.find()) {
            String titlePart = matcher.group(1);
            String episodePart = matcher.group(2);
            if(titlePart == null || episodePart == null)
                continue;
            if(titlePart.contains("%5b") || episodePart.contains("%5b") || episodePart.startsWith("page-"))
                continue;
            return true;
        }
        return false;
    }

    private static boolean hasRenderedNtkEpisodeViewerContent(String lowerBody) {
        if(lowerBody == null || lowerBody.length() == 0)
            return false;
        if(hasNtkViewerShellData(lowerBody))
            return true;
        if(lowerBody.contains("imagestoken") && lowerBody.contains("imagemetas"))
            return true;
        if(lowerBody.contains("vw-main") || lowerBody.contains("vw-imgs")
                || lowerBody.contains("viewer-content") || lowerBody.contains("toon-view")
                || lowerBody.contains("image-view") || lowerBody.contains("webtoon-body"))
            return true;
        return lowerBody.contains("/webtoon_uploads/")
                || lowerBody.contains("/manhwa_uploads/")
                || lowerBody.contains("/comic_uploads/")
                || lowerBody.contains("/blacktoon/episodes/")
                || lowerBody.contains("/wt/episodes/");
    }

    private static boolean isCloudflareChallenge(int code, String body) {
        if(body == null)
            return false;
        if(code >= 500) {
            String lower = body.toLowerCase(Locale.ROOT);
            return lower.contains("cloudflare")
                    || lower.contains("error code 522")
                    || lower.contains("connection timed out");
        }
        String lower = body.toLowerCase(Locale.ROOT);
        if(looksLikeNtkNormalPage(lower))
            return false;
        boolean challengeBody = containsCloudflareChallengeMarker(lower);
        return challengeBody && (code == 403 || code >= 200 && code < 400);
    }

    private static boolean containsCloudflareChallengeMarker(String lower) {
        if(lower == null || lower.length() == 0)
            return false;
        return lower.contains("cf-mitigated")
                || lower.contains("challenges.cloudflare.com")
                || lower.contains("/cdn-cgi/challenge-platform")
                || lower.contains("cf-challenge")
                || lower.contains("cf_chl")
                || lower.contains("cf-chl")
                || lower.contains("_cf_chl")
                || lower.contains("cf-turnstile")
                || lower.contains("cf-please-wait")
                || lower.contains("cf_clearance")
                || lower.contains("__cf_bm")
                || lower.contains("cf-ray")
                || lower.contains("turnstile")
                || lower.contains("checking your browser")
                || lower.contains("just a moment")
                || lower.contains("verifying you are human")
                || lower.contains("verify you are human")
                || (lower.contains("cloudflare") && lower.contains("security service"));
    }

    private static boolean containsNtkBlockedDocumentMarker(String lower) {
        if(lower == null || lower.length() == 0)
            return false;
        return lower.contains("개발자 도구 차단")
                || lower.contains("developer tools blocked")
                || lower.contains("developer tool blocked")
                || lower.contains("devtools blocked")
                || lower.contains("devtool blocked");
    }

    public boolean isCloudflareChallengeResponse(int code, String body) {
        return isCloudflareChallenge(code, body);
    }

    private static boolean looksLikeNtkNormalPage(String lowerBody) {
        if(lowerBody == null || lowerBody.length() < 500)
            return false;
        return (lowerBody.contains("newtoki") || lowerBody.contains("뉴토끼"))
                && (lowerBody.contains("실시간 웹툰 랭킹")
                || lowerBody.contains("실시간 만화 랭킹")
                || lowerBody.contains("/webtoon/")
                || lowerBody.contains("/manhwa/")
                || lowerBody.contains("webtoon-list"));
    }

    public static class PageResponse {
        public final int code;
        public final String body;
        public final boolean fromCache;

        PageResponse(int code, String body, boolean fromCache) {
            this.code = code;
            this.body = body;
            this.fromCache = fromCache;
        }
    }

    private static class CachedPage {
        final int code;
        final String body;
        final long time;

        CachedPage(int code, String body, long time) {
            this.code = code;
            this.body = body;
            this.time = time;
        }
    }

    private static class PersistedPage {
        int code;
        String body;
        long time;

        PersistedPage() {
        }

        PersistedPage(CachedPage page) {
            this.code = page.code;
            this.body = page.body;
            this.time = page.time;
        }
    }

    private static class DomainResolveState {
        final CountDownLatch done = new CountDownLatch(1);
        volatile boolean changed = false;
    }

    private static class PageLoadState {
        final CountDownLatch done = new CountDownLatch(1);
        volatile PageResponse response;
        volatile Exception error;
    }

    private static final class CachedViewerImages {
        final List<String> urls;
        final long cachedAt;

        CachedViewerImages(List<String> urls, long cachedAt) {
            this.urls = urls;
            this.cachedAt = cachedAt;
        }
    }

    private HttpEngine getOrCreateNtkQuicEngine(String baseUrl) {
        if(context == null || !NtkQuicFetcher.isAvailable() || baseUrl == null || baseUrl.length() == 0)
            return null;
        try {
            String host = URI.create(baseUrl).getHost();
            if(host == null || host.length() == 0)
                return null;
            FutureTask<HttpEngine> task;
            boolean owner = false;
            synchronized (ntkQuicEngineLock) {
                HttpEngine cached = ntkQuicEngines.get(host);
                if(cached != null)
                    return cached;
                task = ntkQuicEngineTasks.get(host);
                if(task == null) {
                    String engineHost = host;
                    task = new FutureTask<>(() -> buildNtkQuicEngine(engineHost));
                    ntkQuicEngineTasks.put(host, task);
                    owner = true;
                }
            }
            if(owner)
                task.run();
            HttpEngine created = task.get();
            synchronized (ntkQuicEngineLock) {
                HttpEngine cached = ntkQuicEngines.get(host);
                if(cached != null)
                    return cached;
                if(created != null)
                    ntkQuicEngines.put(host, created);
                ntkQuicEngineTasks.remove(host);
                return created;
            }
        } catch (Exception e) {
            try {
                String host = URI.create(baseUrl).getHost();
                if(host != null && host.length() > 0) {
                    synchronized (ntkQuicEngineLock) {
                        ntkQuicEngineTasks.remove(host);
                    }
                }
            } catch (Exception ignored) {
            }
            return null;
        }
    }

    private HttpEngine buildNtkQuicEngine(String host) {
        return new HttpEngine.Builder(context.getApplicationContext())
                .setEnableHttp2(true)
                .setEnableQuic(true)
                .setEnableBrotli(true)
                .setUserAgent(agent)
                .setQuicOptions(new QuicOptions.Builder()
                        .addAllowedQuicHost(host)
                        .setHandshakeUserAgent(agent)
                        .build())
                .addQuicHint(host, 443, 443)
                .build();
    }

    private HttpEngine getCachedNtkQuicEngine(String baseUrl) {
        if(baseUrl == null || baseUrl.length() == 0)
            return null;
        try {
            String host = URI.create(baseUrl).getHost();
            if(host == null || host.length() == 0)
                return null;
            synchronized (ntkQuicEngineLock) {
                return ntkQuicEngines.get(host);
            }
        } catch (Exception e) {
            return null;
        }
    }

    private FutureTask<HttpEngine> startNtkQuicEngineCreate(String baseUrl, String path) {
        FutureTask<HttpEngine> task = new FutureTask<>(() -> getOrCreateNtkQuicEngine(baseUrl));
        Thread thread = new Thread(task, "ntk-quic-engine-create");
        thread.setDaemon(true);
        thread.start();
        Log.d(TAG, "ntk_native_ack_engine_async_start path=" + path);
        return task;
    }

    private HttpEngine awaitNtkQuicEngine(FutureTask<HttpEngine> task, long timeoutMs, String path) {
        if(task == null)
            return null;
        try {
            HttpEngine engine = task.get(Math.max(1L, timeoutMs), TimeUnit.MILLISECONDS);
            Log.d(TAG, "ntk_native_ack_engine_async_done path=" + path
                    + ",ready=" + (engine != null));
            return engine;
        } catch(TimeoutException e) {
            Log.d(TAG, "ntk_native_ack_engine_async_timeout path=" + path);
            return null;
        } catch(InterruptedException e) {
            Thread.currentThread().interrupt();
            Log.d(TAG, "ntk_native_ack_engine_async_cancelled path=" + path);
            return null;
        } catch(Exception e) {
            Log.d(TAG, "ntk_native_ack_engine_async_error path=" + path + "," + e);
            return null;
        }
    }

    private boolean isNtkWasmWarm(String baseUrl) {
        String host = ntkHost(baseUrl);
        if(host.length() == 0)
            return false;
        synchronized (ntkQuicEngineLock) {
            Long warmedAt = ntkWasmWarmCache.get(host);
            if(warmedAt == null)
                return false;
            if(System.currentTimeMillis() - warmedAt >= NTK_WASM_WARM_CACHE_TTL_MS) {
                ntkWasmWarmCache.remove(host);
                return false;
            }
            return true;
        }
    }

    private void markNtkWasmWarm(String baseUrl) {
        String host = ntkHost(baseUrl);
        if(host.length() == 0)
            return;
        synchronized (ntkQuicEngineLock) {
            ntkWasmWarmCache.put(host, System.currentTimeMillis());
        }
    }

    private String ntkHost(String baseUrl) {
        if(baseUrl == null || baseUrl.length() == 0)
            return "";
        try {
            String host = URI.create(baseUrl).getHost();
            return host == null ? "" : host;
        } catch (Exception e) {
            return "";
        }
    }

    private ExecutorService getOrCreateNtkQuicExecutor(String baseUrl) {
        if(baseUrl == null || baseUrl.length() == 0)
            return null;
        try {
            String host = URI.create(baseUrl).getHost();
            if(host == null || host.length() == 0)
                return null;
            synchronized (ntkQuicEngineLock) {
                ExecutorService cached = ntkQuicExecutors.get(host);
                if(cached != null && !cached.isShutdown())
                    return cached;
                ExecutorService created = Executors.newFixedThreadPool(NTK_QUIC_CALLBACK_THREADS_PER_HOST);
                ntkQuicExecutors.put(host, created);
                return created;
            }
        } catch (Exception e) {
            return null;
        }
    }

    private void dropNtkQuicEngine(String baseUrl) {
        if(baseUrl == null || baseUrl.length() == 0)
            return;
        try {
            String host = URI.create(baseUrl).getHost();
            if(host == null || host.length() == 0)
                return;
            HttpEngine engine;
            ExecutorService executor;
            synchronized (ntkQuicEngineLock) {
                engine = ntkQuicEngines.remove(host);
                ntkQuicEngineTasks.remove(host);
                executor = ntkQuicExecutors.remove(host);
                ntkWasmWarmCache.remove(host);
            }
            if(engine != null)
                engine.shutdown();
            shutdownNtkQuicExecutor(executor);
        } catch (Exception ignored) {
        }
    }

    private void shutdownNtkQuicEngines() {
        List<HttpEngine> engines;
        List<ExecutorService> executors;
        synchronized (ntkQuicEngineLock) {
            engines = new ArrayList<>(ntkQuicEngines.values());
            executors = new ArrayList<>(ntkQuicExecutors.values());
            ntkQuicEngines.clear();
            ntkQuicEngineTasks.clear();
            ntkQuicExecutors.clear();
            ntkWasmWarmCache.clear();
        }
        for(HttpEngine engine : engines) {
            try {
                engine.shutdown();
            } catch (Exception ignored) {
            }
        }
        for(ExecutorService executor : executors)
            shutdownNtkQuicExecutor(executor);
    }

    private void shutdownNtkQuicExecutor(ExecutorService executor) {
        if(executor == null)
            return;
        try {
            executor.shutdown();
            executor.awaitTermination(2_500L, TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (Exception ignored) {
        }
    }

    private NtkQuicFetcher.Result fetchNtkQuic(String baseUrl, String url, String cookieHeader,
                                               Map<String, String> headers, String method, byte[] body,
                                               long timeoutMs) throws InterruptedException {
        return fetchNtkQuic(baseUrl, url, cookieHeader, headers, method, body, timeoutMs, null);
    }

    private NtkQuicFetcher.Result fetchNtkQuic(String baseUrl, String url, String cookieHeader,
                                               Map<String, String> headers, String method, byte[] body,
                                               long timeoutMs,
                                               NtkQuicFetcher.PartialTextObserver partialTextObserver) throws InterruptedException {
        return fetchNtkQuic(baseUrl, url, cookieHeader, headers, method, body, timeoutMs,
                partialTextObserver, null);
    }

    private NtkQuicFetcher.Result fetchNtkQuic(String baseUrl, String url, String cookieHeader,
                                               Map<String, String> headers, String method, byte[] body,
                                               long timeoutMs,
                                               NtkQuicFetcher.PartialTextObserver partialTextObserver,
                                               NtkQuicFetcher.PartialBytesObserver partialBytesObserver) throws InterruptedException {
        HttpEngine engine = getOrCreateNtkQuicEngine(baseUrl);
        if(engine == null)
            return NtkQuicFetcher.fetch(context, url, agent, cookieHeader, headers, method, body, timeoutMs,
                    partialTextObserver);
        ExecutorService executor = getOrCreateNtkQuicExecutor(baseUrl);
        try {
            if(executor != null)
                return NtkQuicFetcher.fetchWithEngine(engine, executor, url, agent, cookieHeader, headers, method, body, timeoutMs,
                        partialTextObserver, partialBytesObserver);
            return NtkQuicFetcher.fetchWithEngine(engine, url, agent, cookieHeader, headers, method, body, timeoutMs,
                    partialTextObserver);
        } catch (IllegalStateException e) {
            dropNtkQuicEngine(baseUrl);
            return NtkQuicFetcher.fetch(context, url, agent, cookieHeader, headers, method, body, timeoutMs,
                    partialTextObserver);
        }
    }

    private void startNtkRscAckPreflight(String path) {
        if(path == null || path.length() == 0 || context == null)
            return;
        if(!isNtkTokenizedViewerPath(path))
            return;
        if(NtkWebViewFallbackManager.hasRecentServerAckSuccess(path))
            return;
        if(ReaderImageCache.isNtkAckRecoveryLaunchHeldForPath(path)) {
            Log.d(TAG, "ntk_rsc_ack_preflight_skip_launch_hold path=" + path);
            return;
        }
        Thread thread = new Thread(() -> {
            long startedAt = System.currentTimeMillis();
            try {
                Log.d(TAG, "ntk_rsc_ack_preflight_start path=" + path);
                boolean ok = performNtkWebViewAckPreflight(path);
                Log.d(TAG, "ntk_rsc_ack_preflight_done path=" + path
                        + ",success=" + ok
                        + ",ms=" + (System.currentTimeMillis() - startedAt));
            } catch(Exception e) {
                Log.d(TAG, "ntk_rsc_ack_preflight_error path=" + path
                        + ",ms=" + (System.currentTimeMillis() - startedAt)
                        + "," + e);
            }
        }, "ntk-rsc-ack-preflight");
        thread.setDaemon(true);
        thread.start();
    }

    private NtkQuicFetcher.Result fetchNtkQuicUntilText(String baseUrl, String url,
                                                       String cookieHeader,
                                                       Map<String, String> headers,
                                                       String method, byte[] body,
                                                       long timeoutMs,
                                                       NtkQuicFetcher.EarlyTextObserver earlyTextObserver)
            throws InterruptedException {
        HttpEngine engine = getOrCreateNtkQuicEngine(baseUrl);
        if(engine == null)
            return NtkQuicFetcher.fetch(context, url, agent, cookieHeader, headers, method, body, timeoutMs,
                    text -> {
                        if(earlyTextObserver == null)
                            return;
                        earlyTextObserver.onPartialText(0, Collections.emptyMap(), text);
                    });
        ExecutorService executor = getOrCreateNtkQuicExecutor(baseUrl);
        try {
            if(executor != null)
                return NtkQuicFetcher.fetchWithEngineUntilText(engine, executor, url, agent,
                        cookieHeader, headers, method, body, timeoutMs, earlyTextObserver);
            return NtkQuicFetcher.fetchWithEngine(engine, url, agent, cookieHeader, headers,
                    method, body, timeoutMs, text -> {
                        if(earlyTextObserver == null)
                            return;
                        earlyTextObserver.onPartialText(0, Collections.emptyMap(), text);
                    });
        } catch (IllegalStateException e) {
            dropNtkQuicEngine(baseUrl);
            return NtkQuicFetcher.fetch(context, url, agent, cookieHeader, headers, method, body, timeoutMs,
                    text -> {
                        if(earlyTextObserver == null)
                            return;
                        earlyTextObserver.onPartialText(0, Collections.emptyMap(), text);
                    });
        }
    }

    public boolean isNtkImageHeaderReachable(String url, Map<String, String> headers, long timeoutMs) {
        return ntkImageHeaderReachability(url, headers, timeoutMs) > 0;
    }

    public int ntkImageHeaderReachability(String url, Map<String, String> headers, long timeoutMs) {
        if(isNtkGeneratedImageCdnUrl(url)) {
            int okhttpReachable = ntkImageHeaderReachabilityOkHttp(url, headers);
            if(okhttpReachable >= 0)
                return okhttpReachable;
        }
        if(context == null || url == null || url.length() == 0
                || !NtkQuicFetcher.isAvailable() || !shouldUseNtkQuicPrimaryUrl(url))
            return -1;
        HttpUrl parsed = HttpUrl.parse(url);
        if(parsed == null)
            return -1;
        String baseUrl = rootFromHttpUrl(parsed);
        Map<String, String> requestHeaders = headers == null
                ? new LinkedHashMap<>() : new LinkedHashMap<>(headers);
        if(headerValue(requestHeaders, "Cookie") == null)
            requestHeaders.put("Cookie", getCookieHeader());
        if(headerValue(requestHeaders, "User-Agent") == null)
            requestHeaders.put("User-Agent", agent);
        String cookieHeader = headerValue(requestHeaders, "Cookie");
        final boolean[] reachable = new boolean[]{false};
        long startedAt = System.currentTimeMillis();
        HttpEngine engine = getOrCreateNtkQuicEngine(baseUrl);
        if(engine == null)
            return -1;
        ExecutorService executor = getOrCreateNtkQuicExecutor(baseUrl);
        try {
            if(executor == null)
                return -1;
            NtkQuicFetcher.Result result = NtkQuicFetcher.fetchWithEngineUntilResponseStarted(
                    engine, executor, url, agent, cookieHeader, requestHeaders, "GET", null,
                    Math.max(1L, timeoutMs), (code, responseHeaders) -> {
                        String contentType = contentTypeHeader(responseHeaders);
                        reachable[0] = (code >= 200 && code < 300 || code == 206)
                                && contentType.toLowerCase(Locale.ROOT).startsWith("image/");
                        return true;
                    });
            Log.d(TAG, "ntk_image_header_probe code=" + (result == null ? 0 : result.code)
                    + ",reachable=" + reachable[0]
                    + ",error=" + (result == null ? null : result.error)
                    + ",ms=" + (System.currentTimeMillis() - startedAt)
                    + ",url=" + safeLogUrl(url));
            if(result == null || result.error != null)
                return -1;
            return reachable[0] ? 1 : 0;
        } catch(Exception e) {
            Log.d(TAG, "ntk_image_header_probe_error error=" + e.getClass().getSimpleName()
                    + ",ms=" + (System.currentTimeMillis() - startedAt)
                    + ",url=" + safeLogUrl(url));
            return -1;
        }
    }

    private int ntkImageHeaderReachabilityOkHttp(String url, Map<String, String> headers) {
        Response response = null;
        long startedAt = System.currentTimeMillis();
        try {
            Map<String, String> requestHeaders = headers == null
                    ? new LinkedHashMap<>() : new LinkedHashMap<>(headers);
            requestHeaders.put("Range", "bytes=0-31");
            if(headerValue(requestHeaders, "Accept-Encoding") == null)
                requestHeaders.put("Accept-Encoding", "identity");
            response = get(url, requestHeaders);
            int code = response == null ? 0 : response.code();
            String contentType = response == null || response.body() == null
                    ? "" : String.valueOf(response.body().contentType()).toLowerCase(Locale.ROOT);
            byte[] bytes = response == null || response.body() == null
                    ? new byte[0] : response.body().bytes();
            boolean reachable = (code >= 200 && code < 300 || code == 206)
                    && contentType.startsWith("image/")
                    && looksLikeNtkImageHeader(bytes);
            Log.d(TAG, "ntk_image_header_probe_okhttp code=" + code
                    + ",reachable=" + reachable
                    + ",bytes=" + bytes.length
                    + ",ms=" + (System.currentTimeMillis() - startedAt)
                    + ",url=" + safeLogUrl(url));
            if(reachable)
                return 1;
            if(code >= 200 && code < 300 || code == 206)
                return 0;
            return code == 404 || code == 403 || code == 410 ? 0 : -1;
        } catch(Exception e) {
            Log.d(TAG, "ntk_image_header_probe_okhttp_error error=" + e.getClass().getSimpleName()
                    + ",ms=" + (System.currentTimeMillis() - startedAt)
                    + ",url=" + safeLogUrl(url));
            return -1;
        } finally {
            if(response != null)
                response.close();
        }
    }

    private static boolean isNtkGeneratedImageCdnUrl(String url) {
        if(url == null)
            return false;
        try {
            HttpUrl parsed = HttpUrl.parse(url);
            if(parsed == null || parsed.host() == null)
                return false;
            String host = parsed.host().toLowerCase(Locale.ROOT);
            return host.equals("i.toonflix.app")
                    || host.endsWith(".toonflix.app")
                    || host.matches("flysky\\d*m\\.com")
                    || "moamoabon.com".equals(host);
        } catch(Exception ignored) {
            return false;
        }
    }

    private static boolean looksLikeNtkImageHeader(byte[] bytes) {
        if(bytes == null || bytes.length < 4)
            return false;
        int b0 = bytes[0] & 0xff;
        int b1 = bytes[1] & 0xff;
        int b2 = bytes[2] & 0xff;
        int b3 = bytes[3] & 0xff;
        if(b0 == 0xff && b1 == 0xd8)
            return true;
        if(bytes.length >= 8 && b0 == 0x89 && b1 == 0x50 && b2 == 0x4e && b3 == 0x47)
            return true;
        if(b0 == 0x52 && b1 == 0x49 && b2 == 0x46 && b3 == 0x46)
            return true;
        return b0 == 0x47 && b1 == 0x49 && b2 == 0x46;
    }

    private static String contentTypeHeader(Map<String, List<String>> headers) {
        if(headers == null)
            return "";
        for(String key : headers.keySet()) {
            if(!"content-type".equalsIgnoreCase(key))
                continue;
            List<String> values = headers.get(key);
            if(values != null && !values.isEmpty() && values.get(0) != null)
                return values.get(0);
            return "";
        }
        return "";
    }

    private static boolean isLikelyPartialDecodableImage(Map<String, List<String>> headers, byte[] bytes) {
        if(bytes == null || bytes.length < 16)
            return false;
        String contentType = "";
        if(headers != null) {
            for(String key : headers.keySet()) {
                if(!"content-type".equalsIgnoreCase(key))
                    continue;
                List<String> values = headers.get(key);
                if(values != null && !values.isEmpty() && values.get(0) != null)
                    contentType = values.get(0).toLowerCase(Locale.ROOT);
                break;
            }
        }
        boolean jpeg = (bytes[0] & 0xff) == 0xff && (bytes[1] & 0xff) == 0xd8;
        boolean png = bytes.length >= 8
                && (bytes[0] & 0xff) == 0x89
                && bytes[1] == 0x50
                && bytes[2] == 0x4e
                && bytes[3] == 0x47
                && bytes[4] == 0x0d
                && bytes[5] == 0x0a
                && bytes[6] == 0x1a
                && bytes[7] == 0x0a;
        boolean webp = bytes.length >= 12
                && bytes[0] == 0x52
                && bytes[1] == 0x49
                && bytes[2] == 0x46
                && bytes[3] == 0x46
                && bytes[8] == 0x57
                && bytes[9] == 0x45
                && bytes[10] == 0x42
                && bytes[11] == 0x50;
        if(!jpeg && !png && !webp)
            return false;
        if(!(contentType.length() == 0 || contentType.contains("image/")))
            return false;
        BitmapFactory.Options options = new BitmapFactory.Options();
        options.inJustDecodeBounds = true;
        BitmapFactory.decodeByteArray(bytes, 0, bytes.length, options);
        if(options.outWidth <= 0 || options.outHeight <= 0)
            return false;
        BitmapFactory.Options decodeOptions = new BitmapFactory.Options();
        decodeOptions.inPreferredConfig = Bitmap.Config.ARGB_8888;
        decodeOptions.inSampleSize = foregroundPartialDisplayProbeSampleSize(options.outWidth);
        Bitmap bitmap;
        try {
            bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.length, decodeOptions);
        } catch (OutOfMemoryError e) {
            return false;
        }
        if(bitmap == null)
            return false;
        bitmap.recycle();
        return true;
    }

    private static boolean shouldAcceptNtkForegroundPartialImage(int code, Map<String, List<String>> headers,
                                                                byte[] bytes) {
        if(!(code >= 200 && code < 300 || code == 206))
            return false;
        if(bytes == null || bytes.length < NTK_FOREGROUND_PARTIAL_IMAGE_BYTES)
            return false;
        return isLikelyPartialDecodableImage(headers, bytes);
    }

    private static boolean shouldMarkNtkForegroundPartialImage(NtkQuicFetcher.Result result) {
        if(!isUsableNtkQuicGetResult(result) || result.bodyBytes == null)
            return false;
        long contentLength = contentLengthHeader(result.headers);
        return contentLength <= 0L || result.bodyBytes.length < contentLength;
    }

    private static long contentLengthHeader(Map<String, List<String>> headers) {
        if(headers == null)
            return -1L;
        for(String key : headers.keySet()) {
            if(!"content-length".equalsIgnoreCase(key))
                continue;
            List<String> values = headers.get(key);
            if(values == null || values.isEmpty() || values.get(0) == null)
                return -1L;
            try {
                return Long.parseLong(values.get(0));
            } catch (NumberFormatException ignored) {
                return -1L;
            }
        }
        return -1L;
    }

    private static int foregroundPartialDisplayProbeSampleSize(int width) {
        int targetWidth = 1440;
        int maxDim = Math.max(width, targetWidth);
        int sample = 1;
        while(maxDim / (sample * 2) >= targetWidth)
            sample <<= 1;
        return sample;
    }

    private List<String> cachedNtkViewerImageUrls(String key) {
        if(key == null || key.length() == 0)
            return Collections.emptyList();
        long now = System.currentTimeMillis();
        synchronized (ntkViewerImageUrlCacheLock) {
            CachedViewerImages cached = ntkViewerImageUrlCache.get(key);
            if(cached == null)
                return Collections.emptyList();
            if(now - cached.cachedAt >= NTK_VIEWER_IMAGE_URL_CACHE_TTL_MS) {
                ntkViewerImageUrlCache.remove(key);
                return Collections.emptyList();
            }
            return new ArrayList<>(cached.urls);
        }
    }

    private List<String> recentlyCachedNtkViewerImageUrls(String key) {
        if(key == null || key.length() == 0)
            return Collections.emptyList();
        long now = System.currentTimeMillis();
        synchronized (ntkViewerImageUrlCacheLock) {
            CachedViewerImages cached = ntkViewerImageUrlCache.get(key);
            if(cached == null || now - cached.cachedAt >= NTK_VIEWER_IMAGES_RECENT_CACHE_TRUST_MS)
                return Collections.emptyList();
            return new ArrayList<>(cached.urls);
        }
    }

    private void cacheNtkViewerImageUrls(String key, List<String> urls) {
        if(key == null || key.length() == 0 || urls == null || urls.isEmpty())
            return;
        synchronized (ntkViewerImageUrlCacheLock) {
            ntkViewerImageUrlCache.put(key, new CachedViewerImages(new ArrayList<>(urls), System.currentTimeMillis()));
        }
    }

    private void invalidateCachedNtkViewerImageUrls(String key) {
        if(key == null || key.length() == 0)
            return;
        synchronized (ntkViewerImageUrlCacheLock) {
            ntkViewerImageUrlCache.remove(key);
            ntkViewerImageUrlMissCache.remove(key);
        }
    }

    private boolean hasFreshNtkViewerImageUrlMiss(String key) {
        if(key == null || key.length() == 0)
            return false;
        long now = System.currentTimeMillis();
        synchronized (ntkViewerImageUrlCacheLock) {
            Long missedAt = ntkViewerImageUrlMissCache.get(key);
            if(missedAt == null)
                return false;
            if(now - missedAt >= NTK_VIEWER_IMAGE_URL_MISS_CACHE_TTL_MS) {
                ntkViewerImageUrlMissCache.remove(key);
                return false;
            }
            return true;
        }
    }

    private void cacheNtkViewerImageUrlMiss(String key) {
        if(key == null || key.length() == 0)
            return;
        synchronized (ntkViewerImageUrlCacheLock) {
            ntkViewerImageUrlMissCache.put(key, System.currentTimeMillis());
        }
    }

    public Response post(String url, RequestBody body, Map<String,String> headers){
        return post(url,body,headers,false);
    }

    public List<String> fetchNtkViewerImageUrls(String segment, String workId, String episodeId,
                                                String imagesToken, String viewerBody) {
        return fetchNtkViewerImageUrls(segment, workId, episodeId, imagesToken, viewerBody, null);
    }

    public List<String> fetchNtkViewerImageUrls(String segment, String workId, String episodeId,
                                                String imagesToken, String viewerBody, String viewerPath) {
        return fetchNtkViewerImageUrls(segment, workId, episodeId, imagesToken, viewerBody, viewerPath, null);
    }

    public List<String> fetchNtkViewerImageUrls(String segment, String workId, String episodeId,
                                                String imagesToken, String viewerBody, String viewerPath,
                                                String ackScopePath) {
        return fetchNtkViewerImageUrls(segment, workId, episodeId, imagesToken, viewerBody,
                viewerPath, ackScopePath, null);
    }

    public List<String> fetchNtkViewerImageUrls(String segment, String workId, String episodeId,
                                                String imagesToken, String viewerBody, String viewerPath,
                                                String ackScopePath,
                                                NtkViewerImageUrlsCallback trustedUrlsCallback) {
        List<String> urls = new ArrayList<>();
        if(context == null || segment == null || workId == null || episodeId == null || imagesToken == null
                || imagesToken.length() == 0)
            return urls;
        String kind = "webtoon".equals(segment) ? "webtoon" : "manhwa";
        String endpoint = "webtoon".equals(kind) ? "/api/webtoon-images" : "/api/manhwa-images";
        String payloadPath = "/" + kind + "/" + workId + "/" + episodeId;
        String path = viewerPath != null && viewerPath.length() > 0 ? viewerPath : payloadPath;
        String cookiePath = ackScopePath != null && ackScopePath.length() > 0 ? ackScopePath : path;
        String baseUrl = getBaseUrl(path);
        String cacheKey = baseUrl + path + "|" + kind + "|" + workId + "|" + episodeId;
        List<String> cachedUrls = cachedNtkViewerImageUrls(cacheKey);
        if(!cachedUrls.isEmpty()) {
            List<String> recentUrls = recentlyCachedNtkViewerImageUrls(cacheKey);
            if(!recentUrls.isEmpty()) {
                ViewerWarmupManager.logMetric("ntk_images_api_recent_cache_hit", recentUrls.size());
                Log.d(TAG, "ntk_images_api_recent_cache_hit path=" + path
                        + ",count=" + recentUrls.size());
                notifyNtkViewerImageUrls(trustedUrlsCallback, recentUrls);
                return recentUrls;
            }
            if(areInitialNtkViewerImageUrlsReachable(cachedUrls, baseUrl, path)) {
                ViewerWarmupManager.logMetric("ntk_images_api_cache_hit", cachedUrls.size());
                notifyNtkViewerImageUrls(trustedUrlsCallback, cachedUrls);
                return cachedUrls;
            }
            invalidateCachedNtkViewerImageUrls(cacheKey);
            NtkWebViewFallbackManager.get(context).dropCachedViewerImageUrls(kind, workId, episodeId, path);
            Log.d(TAG, "ntk_images_api_cache_invalid path=" + path + ",count=" + cachedUrls.size());
        }
        if(hasFreshNtkViewerImageUrlMiss(cacheKey)) {
            if(hasRecentNtkViewerImageAckProof(baseUrl, path, cookiePath)) {
                invalidateCachedNtkViewerImageUrls(cacheKey);
                Log.d(TAG, "ntk_images_api_miss_cache_bypass_ack_proof path=" + path
                        + ",ackPath=" + cookiePath);
            } else {
                Log.d(TAG, "ntk_images_api_miss_cache_hit path=" + path);
                return urls;
            }
        }
        if(shouldSkipNtkViewerImageApiForHardBlock(baseUrl, path, cookiePath)) {
            Log.d(TAG, "ntk_images_api_skip_hardblock path=" + path
                    + ",ackPath=" + cookiePath);
            return urls;
        }
        if(isModernNtkGuardRoot(baseUrl) && isNumericNtkGeneratedEpisode(kind, workId, episodeId, path))
            Log.d(TAG, "ntk_images_api_allow_modern_numeric_probe path=" + path);
        Log.d(TAG, "ntk_images_api_start path=" + path
                + ",ackPath=" + cookiePath
                + ",endpoint=" + endpoint
                + ",tokenLen=" + imagesToken.length());
        FutureTask<List<String>> task = new FutureTask<>(() ->
                fetchNtkViewerImageUrlsUncached(kind, endpoint, baseUrl, path, cookiePath, segment,
                        workId, episodeId, imagesToken, cacheKey, viewerBody, trustedUrlsCallback));
        FutureTask<List<String>> running = ntkViewerImageUrlFlights.putIfAbsent(cacheKey, task);
        if(running == null) {
            running = task;
            task.run();
        } else {
            Log.d(TAG, "ntk_images_api_flight_join path=" + path);
        }
        try {
            List<String> result = running.get();
            urls = result == null ? urls : result;
        } catch(InterruptedException e) {
            Thread.currentThread().interrupt();
            return urls;
        } catch(Exception e) {
            ml.melun.mangaview.report.CrashReporter.record(e);
            return urls;
        } finally {
            ntkViewerImageUrlFlights.remove(cacheKey, running);
        }
        return urls;
    }

    public boolean performNtkWebViewAckPreflight(String path) {
        if(context == null || path == null || path.length() == 0)
            return false;
        Matcher matcher = Pattern.compile("^/(manhwa|webtoon)/([^/?#]+)/([^/?#]+)").matcher(path);
        if(!matcher.find())
            return false;
        long startedAt = System.currentTimeMillis();
        String kind = matcher.group(1);
        String workId = matcher.group(2);
        String episodeId = matcher.group(3);
        String baseUrl = ntkWebViewAckBaseUrl(path);
        String flightKey = ntkWebViewAckFlightKey(baseUrl, path);
        long ownerStamp = startedAt;
        boolean owner = false;
        synchronized (NTK_WEBVIEW_ACK_PREFLIGHT_LOCK) {
            Long existing = NTK_WEBVIEW_ACK_FLIGHTS.get(flightKey);
            if(existing != null && System.currentTimeMillis() - existing <= NTK_WEBVIEW_ACK_PREFLIGHT_STALE_MS) {
                Log.d(TAG, "ntk_webview_ack_preflight_join_existing path=" + path);
            } else {
                NTK_WEBVIEW_ACK_FLIGHTS.put(flightKey, ownerStamp);
                owner = true;
            }
        }
        if(!owner) {
            long deadline = System.currentTimeMillis() + NTK_WEBVIEW_ACK_PREFLIGHT_STALE_MS;
            while(System.currentTimeMillis() < deadline) {
                if(hasNtkWebViewAckPreflightReady(baseUrl, path)) {
                    Log.d(TAG, "ntk_webview_ack_preflight_join_done path=" + path
                            + ",success=true,ms=" + (System.currentTimeMillis() - startedAt));
                    return true;
                }
                if(!isNtkWebViewAckInFlight(baseUrl, path))
                    break;
                try {
                    Thread.sleep(120L);
                } catch(InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
            boolean ok = hasNtkWebViewAckPreflightReady(baseUrl, path);
            Log.d(TAG, "ntk_webview_ack_preflight_join_done path=" + path
                    + ",success=" + ok
                    + ",ms=" + (System.currentTimeMillis() - startedAt));
            return ok;
        }
        try {
            if(hasNtkWebViewAckPreflightReady(baseUrl, path)) {
                Log.d(TAG, "ntk_webview_ack_preflight_strict_hit path=" + path);
                return true;
            }
            {
                if(hasNtkWebViewAckPreflightReady(baseUrl, path)) {
                    Log.d(TAG, "ntk_webview_ack_preflight_strict_hit_after_wait path=" + path
                            + ",ms=" + (System.currentTimeMillis() - startedAt));
                    return true;
                }
                Map<String, String> headers = new HashMap<>();
                headers.put("content-type", "application/json");
                headers.put("accept", "application/json");
                headers.put("origin", baseUrl);
                headers.put("referer", baseUrl + ntkNativeAckScopePath(path));
                Log.d(TAG, "ntk_webview_ack_preflight_enter path=" + path
                        + ",waitMs=" + (System.currentTimeMillis() - startedAt));
                long stageAt = System.currentTimeMillis();
                restoreClearanceFromDisk();
                Log.d(TAG, "ntk_webview_ack_preflight_stage path=" + path
                        + ",stage=restore_clearance,ms=" + (System.currentTimeMillis() - stageAt)
                        + ",totalMs=" + (System.currentTimeMillis() - startedAt));
                stageAt = System.currentTimeMillis();
                syncCookiesFromWebView(baseUrl, true);
                syncCookiesFromWebView(baseUrl + path, true);
                Log.d(TAG, "ntk_webview_ack_preflight_stage path=" + path
                        + ",stage=sync_before,ms=" + (System.currentTimeMillis() - stageAt)
                        + ",totalMs=" + (System.currentTimeMillis() - startedAt));
                stageAt = System.currentTimeMillis();
                final long ackOnlyFetchStageStartedAt = stageAt;
                AtomicBoolean ackOnlyFetchDone = new AtomicBoolean(false);
                Thread ackOnlyFetchThread = new Thread(() -> {
                    try {
                        NtkWebViewFallbackManager.get(context).fetchViewerImageUrls(agent, baseUrl,
                                path, path, headers, kind, workId, episodeId, "__ack_only__",
                                getCookieHeaderForNtkPath(path), null, () -> {
                                    long prepareStageAt = System.currentTimeMillis();
                                    startNtkNativeAckChallengePrepare(baseUrl, path);
                                    Log.d(TAG, "ntk_webview_ack_preflight_stage path=" + path
                                            + ",stage=challenge_prepare_after_webview_post,ms="
                                            + (System.currentTimeMillis() - prepareStageAt)
                                            + ",totalMs=" + (System.currentTimeMillis() - startedAt)
                                            + ",ackOnlyPostedMs=" + (prepareStageAt - ackOnlyFetchStageStartedAt));
                                });
                    } finally {
                        ackOnlyFetchDone.set(true);
                    }
                }, "ntk-ack-only-preflight");
                ackOnlyFetchThread.setDaemon(true);
                ackOnlyFetchThread.start();
                boolean earlyStrictProof = false;
                long earlyDeadline = System.currentTimeMillis() + NTK_WEBVIEW_ACK_PREFLIGHT_STALE_MS;
                while(System.currentTimeMillis() < earlyDeadline) {
                    if(hasNtkWebViewAckPreflightReady(baseUrl, path)) {
                        earlyStrictProof = true;
                        break;
                    }
                    if(ackOnlyFetchDone.get())
                        break;
                    try {
                        Thread.sleep(80L);
                    } catch(InterruptedException e) {
                        Thread.currentThread().interrupt();
                        break;
                    }
                }
                if(!earlyStrictProof) {
                    try {
                        ackOnlyFetchThread.join(250L);
                    } catch(InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                }
                Log.d(TAG, "ntk_webview_ack_preflight_stage path=" + path
                        + ",stage=ack_only_fetch,ms=" + (System.currentTimeMillis() - stageAt)
                        + ",totalMs=" + (System.currentTimeMillis() - startedAt)
                        + ",done=" + ackOnlyFetchDone.get()
                        + ",earlyStrictProof=" + earlyStrictProof);
                stageAt = System.currentTimeMillis();
                syncCookiesFromWebView(baseUrl, true);
                syncCookiesFromWebView(baseUrl + path, true);
                Log.d(TAG, "ntk_webview_ack_preflight_stage path=" + path
                        + ",stage=sync_after,ms=" + (System.currentTimeMillis() - stageAt)
                        + ",totalMs=" + (System.currentTimeMillis() - startedAt));
                boolean ok = hasNtkWebViewAckPreflightReady(baseUrl, path);
                if(!ok)
                    ok = waitForNtkStrictAckProofAfterWebViewPreflight(baseUrl, path, startedAt);
                Log.d(TAG, "ntk_webview_ack_preflight_done path=" + path
                        + ",success=" + ok
                        + ",ms=" + (System.currentTimeMillis() - startedAt));
                return ok;
            }
        } catch(Exception e) {
            Log.d(TAG, "ntk_webview_ack_preflight_error path=" + path
                    + ",ms=" + (System.currentTimeMillis() - startedAt)
                    + "," + e);
            return false;
        } finally {
            NTK_WEBVIEW_ACK_FLIGHTS.remove(flightKey, ownerStamp);
        }
    }

    private boolean waitForNtkStrictAckProofAfterWebViewPreflight(String baseUrl, String path,
            long startedAt) {
        long deadline = System.currentTimeMillis() + NTK_WEBVIEW_ACK_STRICT_PROOF_WAIT_MS;
        boolean loggedWait = false;
        while(System.currentTimeMillis() < deadline) {
            syncCookiesFromWebView(baseUrl, true);
            syncCookiesFromWebView(baseUrl + path, true);
            if(hasNtkWebViewAckPreflightReady(baseUrl, path)) {
                Log.d(TAG, "ntk_webview_ack_preflight_strict_late path=" + path
                        + ",ms=" + (System.currentTimeMillis() - startedAt));
                return true;
            }
            if(!loggedWait) {
                loggedWait = true;
                Log.d(TAG, "ntk_webview_ack_preflight_strict_late_wait path=" + path
                        + ",ms=" + (System.currentTimeMillis() - startedAt));
            }
            try {
                Thread.sleep(120L);
            } catch(InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        return hasNtkWebViewAckPreflightReady(baseUrl, path);
    }

    private boolean hasNtkWebViewAckPreflightReady(String baseUrl, String path) {
        if(!NtkWebViewFallbackManager.hasRecentServerAckSuccess(path))
            return false;
        if(!isModernNtkGuardRoot(baseUrl))
            return true;
        return getCookie("ad_guard_l") != null
                && NtkWebViewFallbackManager.hasRecentStrictAdAckSuccess(path);
    }

    private static String ntkWebViewAckFlightKey(String baseUrl, String path) {
        return (baseUrl == null ? "" : baseUrl) + ntkNativeAckScopePath(path);
    }

    private String ntkWebViewAckBaseUrl(String path) {
        return getBaseUrl(path);
    }

    private static boolean isNtkWebViewAckInFlight(String baseUrl, String path) {
        String key = ntkWebViewAckFlightKey(baseUrl, path);
        Long startedAt = NTK_WEBVIEW_ACK_FLIGHTS.get(key);
        if(startedAt == null)
            return false;
        if(System.currentTimeMillis() - startedAt <= NTK_WEBVIEW_ACK_PREFLIGHT_STALE_MS)
            return true;
        NTK_WEBVIEW_ACK_FLIGHTS.remove(key);
        return false;
    }

    public boolean isNtkWebViewAckPreflightInFlight(String path) {
        if(path == null || path.length() == 0)
            return false;
        return isNtkWebViewAckInFlight(ntkWebViewAckBaseUrl(path), path);
    }

    public boolean waitForNtkWebViewAckPreflightProof(String path, long timeoutMs) {
        if(path == null || path.length() == 0)
            return false;
        String ackPath = ntkNativeAckScopePath(path);
        String baseUrl = ntkWebViewAckBaseUrl(ackPath);
        long startedAt = System.currentTimeMillis();
        long deadline = startedAt + Math.max(0L, timeoutMs);
        while(true) {
            syncCookiesFromWebView(baseUrl, true);
            syncCookiesFromWebView(baseUrl + ackPath, true);
            if((isModernNtkGuardRoot(baseUrl) && hasNtkWebViewAckPreflightReady(baseUrl, ackPath))
                    || (!isModernNtkGuardRoot(baseUrl)
                    && (hasNtkAdAckCookieForPath(ackPath)
                    || NtkWebViewFallbackManager.hasRecentServerAckSuccess(ackPath)))) {
                Log.d(TAG, "ntk_webview_ack_preflight_join_proof_hit path=" + ackPath
                        + ",ms=" + (System.currentTimeMillis() - startedAt));
                return true;
            }
            if(System.currentTimeMillis() >= deadline)
                break;
            if(!isNtkWebViewAckInFlight(baseUrl, ackPath)) {
                Thread.yield();
                if(!isNtkWebViewAckInFlight(baseUrl, ackPath))
                    break;
            }
            try {
                Thread.sleep(80L);
            } catch(InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        boolean ok = isModernNtkGuardRoot(baseUrl)
                ? hasNtkWebViewAckPreflightReady(baseUrl, ackPath)
                : hasNtkAdAckCookieForPath(ackPath)
                || NtkWebViewFallbackManager.hasRecentServerAckSuccess(ackPath);
        Log.d(TAG, "ntk_webview_ack_preflight_join_proof_done path=" + ackPath
                + ",success=" + ok
                + ",inFlight=" + isNtkWebViewAckInFlight(baseUrl, ackPath)
                + ",ms=" + (System.currentTimeMillis() - startedAt));
        return ok;
    }

    public boolean hasRecentNtkServerAckProof(String path) {
        if(path == null || path.length() == 0)
            return false;
        String ackPath = ntkNativeAckScopePath(path);
        return NtkWebViewFallbackManager.hasRecentServerAckSuccess(ackPath);
    }

    public boolean hasRecentStrictNtkAdAckProof(String path) {
        if(path == null || path.length() == 0)
            return false;
        String ackPath = ntkNativeAckScopePath(path);
        return NtkWebViewFallbackManager.hasRecentStrictAdAckSuccess(ackPath);
    }

    public void rememberExternalNtkServerAckSuccess(String path, String source) {
        if(path == null || path.length() == 0)
            return;
        NtkWebViewFallbackManager.rememberExternalServerAckSuccess(
                ntkNativeAckScopePath(path),
                source == null || source.length() == 0 ? "external" : source);
    }

    public void prepareNtkNativeAckChallengeForWebView(String path) {
        if(path == null || path.length() == 0)
            return;
        String ackPath = ntkNativeAckScopePath(path);
        startNtkNativeAckChallengePrepare(ntkWebViewAckBaseUrl(ackPath), ackPath);
    }

    public boolean hasRecentPreparedNtkNativeAckChallenge(String path) {
        if(path == null || path.length() == 0)
            return false;
        String ackPath = ntkNativeAckScopePath(path);
        return hasRecentNtkAckChallengeOk(ntkWebViewAckBaseUrl(ackPath) + ackPath,
                ntkNativeAckFlightKey(ackPath));
    }

    public boolean isPreparedNtkNativeAckChallengeInFlight(String path) {
        if(path == null || path.length() == 0)
            return false;
        String ackPath = ntkNativeAckScopePath(path);
        return isNtkAckChallengeInFlight(ntkWebViewAckBaseUrl(ackPath) + ackPath,
                ntkNativeAckFlightKey(ackPath));
    }

    public boolean performPreparedNtkNativeAck(String path) {
        if(path == null || path.length() == 0)
            return false;
        String ackPath = ntkNativeAckScopePath(path);
        String challengeBody = NtkWebViewFallbackManager.getRecentNativeAckChallenge(ackPath);
        if(challengeBody == null || challengeBody.length() == 0)
            return false;
        String baseUrl = ntkWebViewAckBaseUrl(ackPath);
        return performNtkNativeAckBypassLocked(baseUrl, ackPath, baseUrl + ackPath, path,
                null, null, null, challengeBody);
    }

    private static boolean isNumericNtkGeneratedEpisode(String kind, String workId,
                                                        String episodeId, String path) {
        if(kind == null || workId == null || episodeId == null || path == null)
            return false;
        if(!("manhwa".equals(kind) || "webtoon".equals(kind)))
            return false;
        if(!workId.matches("\\d+") || !episodeId.matches("\\d+"))
            return false;
        return path.matches("^/(manhwa|webtoon)/\\d+/\\d+(?:[/?#].*)?$");
    }

    private static boolean shouldPreferHiddenViewerImagesForSlug(String kind, String path) {
        if(!"webtoon".equals(kind) || path == null)
            return false;
        Matcher matcher = Pattern.compile("^/webtoon/\\d+/([^/?#]+)").matcher(path);
        return matcher.find() && !matcher.group(1).matches("\\d+");
    }

    private List<String> fetchNtkViewerImageUrlsUncached(String kind, String endpoint,
                                                         String baseUrl, String path,
                                                         String cookiePath,
                                                         String segment, String workId,
                                                         String episodeId, String imagesToken,
                                                         String cacheKey, String viewerBody) {
        return fetchNtkViewerImageUrlsUncached(kind, endpoint, baseUrl, path, cookiePath, null,
                segment, workId, episodeId, imagesToken, cacheKey, viewerBody, null);
    }

    private List<String> fetchNtkViewerImageUrlsUncached(String kind, String endpoint,
                                                         String baseUrl, String path,
                                                         String cookiePath,
                                                         String segment, String workId,
                                                         String episodeId, String imagesToken,
                                                         String cacheKey, String viewerBody,
                                                         NtkViewerImageUrlsCallback trustedUrlsCallback) {
        return fetchNtkViewerImageUrlsUncached(kind, endpoint, baseUrl, path, cookiePath, null,
                segment, workId, episodeId, imagesToken, cacheKey, viewerBody, trustedUrlsCallback);
    }

    private List<String> fetchNtkViewerImageUrlsUncached(String kind, String endpoint,
                                                         String baseUrl, String path,
                                                         String cookiePath, String refererPath,
                                                         String segment, String workId,
                                                         String episodeId, String imagesToken,
                                                         String cacheKey, String viewerBody,
                                                         NtkViewerImageUrlsCallback trustedUrlsCallback) {
        List<String> urls = new ArrayList<>();
        boolean ntkTarget = isNtk() || isNtkUrl(baseUrl);
        if(!ntkTarget || !NtkQuicFetcher.isAvailable()) {
            Log.d(TAG, "ntk_viewer_images_skip path=" + path
                    + ",ntk=" + ntkTarget
                    + ",quic=" + NtkQuicFetcher.isAvailable()
                    + ",context=" + (context != null));
            return urls;
        }
        try {
            // Native bypass path: cookies are already managed by applySetCookieHeaders
            // inside performNtkNativeAckBypass; skip WebView sync to save ~200-500ms.
            String nv = getCookie("nv");
            if(!isNtkNvValid(nv)) {
                issueNtkNvCookie(baseUrl);
                nv = getCookie("nv");
            }
            if(!isNtkNvValid(nv))
                return urls;
            Map<String, String> headers = new HashMap<>();
            headers.put("content-type", "application/json");
            headers.put("accept", "application/json");
            headers.put("x-images-client", "viewer-v1");
            headers.put("origin", baseUrl);
            String apiRefererPath = refererPath != null && refererPath.length() > 0 ? refererPath : cookiePath;
            headers.put("referer", baseUrl + ntkNativeAckScopePath(apiRefererPath));
            boolean modernGuardRoot = isModernNtkGuardRoot(baseUrl);
            boolean generatedDirectNumeric = isNumericNtkGeneratedEpisode(kind, workId, episodeId, path);

            urls.addAll(NtkWebViewFallbackManager.get(context).cachedViewerImageUrls(
                    kind, workId, episodeId, path));
            if(urls.size() > 0) {
                if(areInitialNtkViewerImageUrlsReachable(urls, baseUrl, path)) {
                    cacheNtkViewerImageUrls(cacheKey, urls);
                    return urls;
                }
                Log.d(TAG, "ntk_images_api_webview_cache_invalid path=" + path + ",count=" + urls.size());
                NtkWebViewFallbackManager.get(context).dropCachedViewerImageUrls(kind, workId, episodeId, path);
                urls.clear();
            }
            if(looksLikeNtkWebViewViewerBody(viewerBody) && !modernGuardRoot) {
                Log.d(TAG, "ntk_images_api_webview_first path=" + path);
                urls.addAll(NtkWebViewFallbackManager.get(context).fetchViewerImageUrls(agent, baseUrl,
                        path, cookiePath, headers, kind, workId, episodeId, imagesToken,
                        getCookieHeaderForNtkPath(cookiePath), viewerBody));
                Log.d(TAG, "ntk_images_api_webview_first_result path=" + path + ",count=" + urls.size());
                if(urls.size() > 0) {
                    if(areInitialNtkViewerImageUrlsReachable(urls, baseUrl, path)) {
                        cacheNtkViewerImageUrls(cacheKey, urls);
                        return urls;
                    }
                    Log.d(TAG, "ntk_images_api_webview_first_invalid path=" + path + ",count=" + urls.size());
                    NtkWebViewFallbackManager.get(context).dropCachedViewerImageUrls(kind, workId, episodeId, path);
                    urls.clear();
                }
                if(Thread.currentThread().isInterrupted())
                    return urls;
            }
            if(modernGuardRoot && generatedDirectNumeric) {
                Log.d(TAG, "ntk_images_api_try_modern_numeric path=" + path);
            }

            long ackStartedAt = System.currentTimeMillis();
            boolean nativeAckCompleted = hasNtkViewerImagesAckReady(baseUrl, cookiePath);
            String ackPath = ntkNativeAckScopePath(cookiePath);
            String ackFlightKey = baseUrl + ackPath;
            FutureTask<Boolean> nativeAckRace = null;
            Thread nativeAckThread = null;
            if(!nativeAckCompleted && !modernGuardRoot) {
                if(isNtkAckInFlight(ackFlightKey, ackPath)) {
                    nativeAckRace = new FutureTask<>(() ->
                            waitForNtkAckCookieFromExistingFlight(cookiePath, ackFlightKey, ackPath, 3_200L));
                    nativeAckThread = new Thread(nativeAckRace, "ntk-images-native-ack-join");
                    nativeAckThread.setDaemon(true);
                    nativeAckThread.start();
                    Log.d(TAG, "ntk_images_api_native_ack_join path=" + path);
                } else {
                    nativeAckRace = new FutureTask<>(() ->
                            performNtkNativeAckBypassFresh(baseUrl, cookiePath, apiRefererPath));
                    nativeAckThread = new Thread(nativeAckRace, "ntk-images-native-ack-race");
                    nativeAckThread.setDaemon(true);
                    nativeAckThread.start();
                }
            } else if(!nativeAckCompleted) {
                Log.d(TAG, "ntk_images_api_native_ack_skip_modern_guard path=" + path);
            }

            FutureTask<List<String>> webViewRace = null;
            Thread webViewRaceThread = null;
            FutureTask<Boolean> webViewAckPreflightRace = null;
            Thread webViewAckPreflightThread = null;
            boolean ackLaunchHeld = ReaderImageCache.isNtkAckRecoveryLaunchHeldForPath(cookiePath);
            boolean allowTokenizedSlugAckDuringLaunchHold = modernGuardRoot
                    && ackLaunchHeld
                    && imagesToken != null && imagesToken.length() > 0
                    && shouldPreferHiddenViewerImagesForSlug(kind, path);
            boolean webViewAckInFlight = modernGuardRoot
                    && isNtkWebViewAckInFlight(baseUrl, cookiePath);
            if(modernGuardRoot && !nativeAckCompleted
                    && (!ackLaunchHeld || allowTokenizedSlugAckDuringLaunchHold)) {
                webViewAckPreflightRace = new FutureTask<>(() -> performNtkWebViewAckPreflight(cookiePath));
                webViewAckPreflightThread = new Thread(webViewAckPreflightRace,
                        "ntk-images-webview-ack-preflight-race");
                webViewAckPreflightThread.setDaemon(true);
                webViewAckPreflightThread.start();
                Log.d(TAG, "ntk_images_api_webview_ack_preflight_race_start path=" + path
                        + ",phase=before_pre_ack"
                        + ",launchHeld=" + ackLaunchHeld
                        + ",tokenizedSlugOverride=" + allowTokenizedSlugAckDuringLaunchHold);
            } else if(modernGuardRoot && !nativeAckCompleted) {
                Log.d(TAG, "ntk_images_api_webview_ack_preflight_race_skip_launch_hold path=" + path);
            }

            boolean tryViewerImagesBeforeAck = shouldTryNtkViewerImagesBeforeAck(kind, baseUrl, path, cookiePath,
                    nativeAckRace != null);
            boolean tryModernNumericBeforeAck = false;
            if(tryViewerImagesBeforeAck || tryModernNumericBeforeAck) {
                JSONObject payload = ntkViewerImagesPayload(workId, episodeId, imagesToken, nv);
                Log.d(TAG, "ntk_images_api_pre_ack_try path=" + path
                        + ",modern=" + modernGuardRoot);
                NtkQuicFetcher.Result result = fetchNtkViewerImagesApi(baseUrl, endpoint, path,
                        cookiePath, headers, payload, trustedUrlsCallback);
                ViewerWarmupManager.logMetric("ntk_images_api_code", result == null ? 0 : result.code);
                if(appendReachableNtkViewerImages(urls, result, baseUrl, path,
                        kind, workId, episodeId, trustedUrlsCallback)) {
                    Log.d(TAG, "ntk_images_api_pre_ack_success path=" + path
                            + ",count=" + urls.size());
                    cacheNtkViewerImageUrls(cacheKey, urls);
                    return urls;
                }
                if(ntkViewerImagesCount(result) > 0)
                    return urls;
                Log.d(TAG, "ntk_images_api_pre_ack_miss path=" + path
                        + ",code=" + (result == null ? 0 : result.code)
                        + ",ackRequired=" + ntkViewerImagesAckRequired(result));
                if(tryModernNumericBeforeAck)
                    Log.d(TAG, "ntk_images_api_modern_numeric_continue_after_pre_ack_miss path=" + path);
                if(Thread.currentThread().isInterrupted())
                    return urls;
            }

            if(context != null) {
                webViewRace = new FutureTask<>(() -> NtkWebViewFallbackManager.get(context)
                        .fetchViewerImageUrls(agent, baseUrl, path, cookiePath, headers, kind,
                                workId, episodeId, imagesToken, getCookieHeaderForNtkPath(cookiePath),
                                viewerBody));
                webViewRaceThread = new Thread(webViewRace, "ntk-images-webview-race");
                webViewRaceThread.setDaemon(true);
                webViewRaceThread.start();
                Log.d(TAG, "ntk_images_api_webview_race_start path=" + path
                        + ",modernGuard=" + modernGuardRoot
                        + ",ackInFlight=" + webViewAckInFlight);
            } else if(modernGuardRoot) {
                Log.d(TAG, "ntk_images_api_webview_race_defer_until_ack path=" + path);
            }
            if(!nativeAckCompleted && nativeAckRace != null) {
                long raceDeadline = System.currentTimeMillis() + 3200L;
                while(System.currentTimeMillis() < raceDeadline) {
                    if(webViewRace != null && webViewRace.isDone()) {
                        List<String> racedUrls = webViewRace.get();
                        if(racedUrls != null && racedUrls.size() > 0) {
                            urls.addAll(racedUrls);
                            if(areInitialNtkViewerImageUrlsReachable(urls, baseUrl, path)) {
                                cacheNtkViewerImageUrls(cacheKey, urls);
                                Log.d(TAG, "ntk_images_api_webview_race_success path=" + path
                                        + ",count=" + urls.size()
                                        + ",ms=" + (System.currentTimeMillis() - ackStartedAt));
                                return urls;
                            }
                            Log.d(TAG, "ntk_images_api_webview_race_invalid path=" + path
                                    + ",count=" + urls.size());
                            NtkWebViewFallbackManager.get(context).dropCachedViewerImageUrls(kind, workId, episodeId, path);
                            urls.clear();
                        }
                        webViewRace = null;
                    }
                    if(nativeAckRace != null && nativeAckRace.isDone()) {
                        nativeAckCompleted = nativeAckRace.get();
                        break;
                    }
                if(Thread.currentThread().isInterrupted())
                    return urls;
                Thread.sleep(25L);
                }
                if(nativeAckRace != null && !nativeAckRace.isDone())
                    nativeAckCompleted = nativeAckRace.get(200L, TimeUnit.MILLISECONDS);
            }
            Log.d(TAG, "ntk_images_api_native_ack path=" + path
                    + ",success=" + nativeAckCompleted
                    + ",ms=" + (System.currentTimeMillis() - ackStartedAt));
            if(!nativeAckCompleted && modernGuardRoot && webViewRace != null) {
                long imageOnlyDeadline = System.currentTimeMillis() + 900L;
                while(!webViewRace.isDone() && System.currentTimeMillis() < imageOnlyDeadline) {
                    if(hasStrictNtkViewerImagesAckReady(baseUrl, cookiePath)) {
                        nativeAckCompleted = true;
                        break;
                    }
                    if(Thread.currentThread().isInterrupted())
                        return urls;
                    Thread.sleep(35L);
                }
                if(webViewRace.isDone()) {
                    List<String> racedUrls = webViewRace.get();
                    if(racedUrls != null && racedUrls.size() > 0) {
                        urls.addAll(racedUrls);
                        if(areInitialNtkViewerImageUrlsReachable(urls, baseUrl, path)) {
                            cacheNtkViewerImageUrls(cacheKey, urls);
                            Log.d(TAG, "ntk_images_api_modern_webview_pre_ack_success path=" + path
                                    + ",count=" + urls.size()
                                    + ",ms=" + (System.currentTimeMillis() - ackStartedAt));
                            return urls;
                        }
                        Log.d(TAG, "ntk_images_api_modern_webview_pre_ack_invalid path=" + path
                                + ",count=" + urls.size());
                        NtkWebViewFallbackManager.get(context).dropCachedViewerImageUrls(kind, workId, episodeId, path);
                        urls.clear();
                    }
                    webViewRace = null;
                } else {
                    Log.d(TAG, "ntk_images_api_modern_webview_pre_ack_timeout path=" + path
                            + ",ms=" + (System.currentTimeMillis() - ackStartedAt));
                }
            }
            if(!nativeAckCompleted && modernGuardRoot) {
                if(hasStrictNtkViewerImagesAckReady(baseUrl, cookiePath)) {
                    nativeAckCompleted = true;
                } else if(webViewAckPreflightRace != null) {
                    long preflightJoinDeadline = System.currentTimeMillis() + 450L;
                    while(!webViewAckPreflightRace.isDone()
                            && System.currentTimeMillis() < preflightJoinDeadline) {
                        if(hasStrictNtkViewerImagesAckReady(baseUrl, cookiePath)) {
                            nativeAckCompleted = true;
                            break;
                        }
                        if(Thread.currentThread().isInterrupted())
                            return urls;
                        Thread.sleep(25L);
                    }
                    if(!nativeAckCompleted && webViewAckPreflightRace.isDone())
                        nativeAckCompleted = webViewAckPreflightRace.get();
                    else if(!nativeAckCompleted)
                        Log.d(TAG, "ntk_images_api_webview_ack_preflight_race_pending path=" + path);
                    if(!nativeAckCompleted)
                        nativeAckCompleted = hasStrictNtkViewerImagesAckReady(baseUrl, cookiePath);
                } else if(!ackLaunchHeld || allowTokenizedSlugAckDuringLaunchHold) {
                    nativeAckCompleted = performNtkWebViewAckPreflight(cookiePath);
                } else {
                    Log.d(TAG, "ntk_images_api_webview_ack_preflight_join_skip_launch_hold path=" + path);
                }
                Log.d(TAG, "ntk_images_api_webview_ack_preflight_join path=" + path
                        + ",success=" + nativeAckCompleted
                        + ",ms=" + (System.currentTimeMillis() - ackStartedAt));
            }
            if(!nativeAckCompleted) {
                    Log.d(TAG, "ntk_images_api_skip_unacked path=" + path);
                if(modernGuardRoot) {
                    long ackProofDeadline = System.currentTimeMillis() + NTK_WEBVIEW_ACK_STRICT_PROOF_WAIT_MS;
                    while(!nativeAckCompleted && System.currentTimeMillis() < ackProofDeadline) {
                        nativeAckCompleted = hasStrictNtkViewerImagesAckReady(baseUrl, cookiePath);
                        if(nativeAckCompleted)
                            break;
                        if(Thread.currentThread().isInterrupted())
                            return urls;
                        Thread.sleep(25L);
                    }
                    if(nativeAckCompleted) {
                        if(webViewRace != null)
                            webViewRace.cancel(true);
                        if(shouldPreferHiddenViewerImagesForSlug(kind, path) && context != null
                                && (imagesToken == null || imagesToken.length() == 0)) {
                            ArrayList<String> slugWebViewUrls = NtkWebViewFallbackManager.get(context)
                                    .fetchViewerImageUrls(agent, baseUrl, path, cookiePath, headers,
                                            kind, workId, episodeId, imagesToken,
                                            getCookieHeaderForNtkPath(cookiePath), viewerBody);
                            Log.d(TAG, "ntk_images_api_slug_hidden_first_after_ack path=" + path
                                    + ",count=" + slugWebViewUrls.size()
                                    + ",ms=" + (System.currentTimeMillis() - ackStartedAt));
                            urls.addAll(slugWebViewUrls);
                            if(urls.size() > 0) {
                                if(areInitialNtkViewerImageUrlsReachable(urls, baseUrl, path)) {
                                    cacheNtkViewerImageUrls(cacheKey, urls);
                                    return urls;
                                }
                                Log.d(TAG, "ntk_images_api_slug_hidden_first_after_ack_invalid path="
                                        + path + ",count=" + urls.size());
                                NtkWebViewFallbackManager.get(context).dropCachedViewerImageUrls(
                                        kind, workId, episodeId, path);
                                urls.clear();
                            }
                        }
                        String nvAfterAckProof = getCookie("nv");
                        if(!isNtkNvValid(nvAfterAckProof)) {
                            issueNtkNvCookie(baseUrl);
                            nvAfterAckProof = getCookie("nv");
                        }
                        if(isNtkNvValid(nvAfterAckProof)) {
                            JSONObject payloadAfterAckProof = ntkViewerImagesPayload(
                                    workId, episodeId, imagesToken, nvAfterAckProof);
                            NtkQuicFetcher.Result proofRetryResult = fetchNtkViewerImagesApi(baseUrl,
                                    endpoint, path, cookiePath, headers, payloadAfterAckProof,
                                    trustedUrlsCallback);
                            ViewerWarmupManager.logMetric("ntk_images_api_code",
                                    proofRetryResult == null ? 0 : proofRetryResult.code);
                            if(appendReachableNtkViewerImages(urls, proofRetryResult, baseUrl, path,
                                    kind, workId, episodeId, trustedUrlsCallback)) {
                                cacheNtkViewerImageUrls(cacheKey, urls);
                                Log.d(TAG, "ntk_images_api_after_ack_proof_success path=" + path
                                        + ",count=" + urls.size()
                                        + ",ms=" + (System.currentTimeMillis() - ackStartedAt));
                                return urls;
                            }
                            Log.d(TAG, "ntk_images_api_after_ack_proof_miss path=" + path
                                    + ",code=" + (proofRetryResult == null ? 0 : proofRetryResult.code)
                                    + ",ackRequired=" + ntkViewerImagesAckRequired(proofRetryResult)
                                    + ",ms=" + (System.currentTimeMillis() - ackStartedAt));
                            String tokenEpisodeId = ntkViewerImagesTokenField(imagesToken, "e");
                            if(proofRetryResult != null && proofRetryResult.code == 403
                                    && tokenEpisodeId.length() > 0 && !tokenEpisodeId.equals(episodeId)) {
                                JSONObject tokenEpisodePayload = ntkViewerImagesPayload(
                                        workId, tokenEpisodeId, imagesToken, nvAfterAckProof);
                                NtkQuicFetcher.Result tokenEpisodeResult = fetchNtkViewerImagesApi(baseUrl,
                                        endpoint, path, cookiePath, headers, tokenEpisodePayload,
                                        trustedUrlsCallback);
                                ViewerWarmupManager.logMetric("ntk_images_api_code",
                                        tokenEpisodeResult == null ? 0 : tokenEpisodeResult.code);
                                if(appendReachableNtkViewerImages(urls, tokenEpisodeResult, baseUrl, path,
                                        kind, workId, tokenEpisodeId, trustedUrlsCallback)) {
                                    cacheNtkViewerImageUrls(cacheKey, urls);
                                    Log.d(TAG, "ntk_images_api_token_episode_success path=" + path
                                            + ",tokenEpisodeId=" + tokenEpisodeId
                                            + ",count=" + urls.size()
                                            + ",ms=" + (System.currentTimeMillis() - ackStartedAt));
                                    return urls;
                                }
                                Log.d(TAG, "ntk_images_api_token_episode_miss path=" + path
                                        + ",tokenEpisodeId=" + tokenEpisodeId
                                        + ",code=" + (tokenEpisodeResult == null ? 0 : tokenEpisodeResult.code)
                                        + ",ackRequired=" + ntkViewerImagesAckRequired(tokenEpisodeResult)
                                        + ",ms=" + (System.currentTimeMillis() - ackStartedAt));
                            }
                            String apiScopePath = "/" + kind + "/" + workId + "/" + episodeId;
                            if(proofRetryResult != null && proofRetryResult.code == 403
                                    && apiScopePath.length() > 0
                                    && !ntkNativeAckScopePath(apiScopePath).equals(
                                    ntkNativeAckScopePath(cookiePath))) {
                                boolean apiScopeAck = hasStrictNtkViewerImagesAckReady(baseUrl, apiScopePath);
                                if(!apiScopeAck) {
                                    apiScopeAck = modernGuardRoot
                                            ? performNtkWebViewAckPreflight(apiScopePath)
                                            : performNtkNativeAckBypassFresh(baseUrl, apiScopePath, path);
                                }
                                Log.d(TAG, "ntk_images_api_dual_scope_ack path=" + path
                                        + ",apiScope=" + apiScopePath
                                        + ",success=" + apiScopeAck
                                        + ",ms=" + (System.currentTimeMillis() - ackStartedAt));
                                if(apiScopeAck) {
                                    JSONObject apiScopePayload = ntkViewerImagesPayload(
                                            workId, episodeId, imagesToken, nvAfterAckProof);
                                    try {
                                        apiScopePayload.put("path", path);
                                        apiScopePayload.put("ackPath", apiScopePath);
                                    } catch (Exception ignored) {
                                    }
                                    Map<String, String> apiScopeHeaders = new HashMap<>(headers);
                                    apiScopeHeaders.put("referer", baseUrl + ntkNativeAckScopePath(path));
                                    NtkQuicFetcher.Result apiScopeRetryResult = fetchNtkViewerImagesApi(baseUrl,
                                            endpoint, path, apiScopePath, apiScopeHeaders, apiScopePayload,
                                            trustedUrlsCallback);
                                    ViewerWarmupManager.logMetric("ntk_images_api_code",
                                            apiScopeRetryResult == null ? 0 : apiScopeRetryResult.code);
                                    if(appendReachableNtkViewerImages(urls, apiScopeRetryResult, baseUrl, path,
                                            kind, workId, episodeId, trustedUrlsCallback)) {
                                        cacheNtkViewerImageUrls(cacheKey, urls);
                                        Log.d(TAG, "ntk_images_api_dual_scope_success path=" + path
                                                + ",apiScope=" + apiScopePath
                                                + ",count=" + urls.size()
                                                + ",ms=" + (System.currentTimeMillis() - ackStartedAt));
                                        return urls;
                                    }
                                    Log.d(TAG, "ntk_images_api_dual_scope_miss path=" + path
                                            + ",apiScope=" + apiScopePath
                                            + ",code=" + (apiScopeRetryResult == null ? 0 : apiScopeRetryResult.code)
                                            + ",ackRequired=" + ntkViewerImagesAckRequired(apiScopeRetryResult)
                                            + ",ms=" + (System.currentTimeMillis() - ackStartedAt));
                                }
                            }
                            if(context != null) {
                                ArrayList<String> signedWebViewUrls = NtkWebViewFallbackManager.get(context)
                                        .fetchViewerImageUrls(agent, baseUrl, path, cookiePath, headers,
                                                kind, workId, episodeId, imagesToken,
                                                getCookieHeaderForNtkPath(cookiePath), viewerBody);
                                Log.d(TAG, "ntk_images_api_after_ack_proof_signed_webview path=" + path
                                        + ",count=" + signedWebViewUrls.size()
                                        + ",ms=" + (System.currentTimeMillis() - ackStartedAt));
                                urls.addAll(signedWebViewUrls);
                                if(urls.size() > 0) {
                                    if(areInitialNtkViewerImageUrlsReachable(urls, baseUrl, path)) {
                                        cacheNtkViewerImageUrls(cacheKey, urls);
                                        return urls;
                                    }
                                    Log.d(TAG, "ntk_images_api_after_ack_proof_signed_webview_invalid path="
                                            + path + ",count=" + urls.size());
                                    NtkWebViewFallbackManager.get(context).dropCachedViewerImageUrls(
                                            kind, workId, episodeId, path);
                                    urls.clear();
                                }
                            }
                        }
                    } else {
                        Log.d(TAG, "ntk_images_api_ack_proof_wait_timeout path=" + path
                                + ",ms=" + (System.currentTimeMillis() - ackStartedAt));
                    }
                }
                Log.d(TAG, "ntk_images_api_webview_start path=" + path);
                boolean webViewAckCompleted = false;
                if(webViewRace != null) {
                    try {
                        if(modernGuardRoot) {
                            long ackProofDeadline = System.currentTimeMillis() + NTK_WEBVIEW_ACK_STRICT_PROOF_WAIT_MS;
                            while(!webViewAckCompleted && System.currentTimeMillis() < ackProofDeadline) {
                                webViewAckCompleted = hasStrictNtkViewerImagesAckReady(baseUrl, cookiePath);
                                if(webViewAckCompleted)
                                    break;
                                if(Thread.currentThread().isInterrupted())
                                    return urls;
                                Thread.sleep(35L);
                            }
                        } else {
                            long webViewWaitMs = 1600L;
                            long webViewDeadline = System.currentTimeMillis() + webViewWaitMs;
                            long remainingMs = Math.max(1L, webViewDeadline - System.currentTimeMillis());
                            urls.addAll(webViewRace.get(remainingMs, TimeUnit.MILLISECONDS));
                        }
                    } catch(TimeoutException e) {
                        webViewRace.cancel(true);
                    }
                } else if(!webViewAckInFlight) {
                    urls.addAll(NtkWebViewFallbackManager.get(context).fetchViewerImageUrls(agent, baseUrl,
                            path, cookiePath, headers, kind, workId, episodeId, imagesToken,
                            getCookieHeaderForNtkPath(cookiePath), viewerBody));
                } else {
                    Log.d(TAG, "ntk_images_api_webview_join_wait path=" + path);
                }
                Log.d(TAG, "ntk_images_api_webview_result path=" + path + ",count=" + urls.size());
                if(urls.size() > 0) {
                    if(areInitialNtkViewerImageUrlsReachable(urls, baseUrl, path)) {
                        cacheNtkViewerImageUrls(cacheKey, urls);
                        return urls;
                    }
                    Log.d(TAG, "ntk_images_api_webview_invalid path=" + path + ",count=" + urls.size());
                    NtkWebViewFallbackManager.get(context).dropCachedViewerImageUrls(kind, workId, episodeId, path);
                    urls.clear();
                }
                if(modernGuardRoot) {
                    webViewAckCompleted = webViewAckCompleted
                            || hasStrictNtkViewerImagesAckReady(baseUrl, cookiePath);
                } else {
                    syncCookiesFromWebView(baseUrl + cookiePath, true);
                    webViewAckCompleted = webViewAckCompleted || hasNtkAdAckCookieForPath(cookiePath);
                }
                if(!modernGuardRoot && !webViewAckCompleted && isNtkAckInFlight(ackFlightKey, ackPath))
                    webViewAckCompleted = waitForNtkAckCookieFromExistingFlight(
                            cookiePath, ackFlightKey, ackPath, 700L);
                long webViewAckDeadline = System.currentTimeMillis() + (webViewAckInFlight ? 12000L : 900L);
                while(!modernGuardRoot && !webViewAckCompleted && System.currentTimeMillis() < webViewAckDeadline) {
                    Thread.sleep(45L);
                    syncCookiesFromWebView(baseUrl + cookiePath, true);
                    webViewAckCompleted = hasNtkAdAckCookieForPath(cookiePath)
                            || NtkWebViewFallbackManager.hasRecentServerAckSuccess(cookiePath);
                }
                if(webViewAckCompleted) {
                    String nvAfterWebViewAck = getCookie("nv");
                    if(!isNtkNvValid(nvAfterWebViewAck)) {
                        issueNtkNvCookie(baseUrl);
                        nvAfterWebViewAck = getCookie("nv");
                    }
                    if(isNtkNvValid(nvAfterWebViewAck)) {
                        JSONObject payloadAfterWebViewAck = ntkViewerImagesPayload(
                                workId, episodeId, imagesToken, nvAfterWebViewAck);
                        NtkQuicFetcher.Result retryResult = fetchNtkViewerImagesApi(baseUrl,
                                endpoint, path, cookiePath, headers, payloadAfterWebViewAck,
                                trustedUrlsCallback);
                        ViewerWarmupManager.logMetric("ntk_images_api_code",
                                retryResult == null ? 0 : retryResult.code);
                        if(appendReachableNtkViewerImages(urls, retryResult, baseUrl, path,
                                kind, workId, episodeId, trustedUrlsCallback)) {
                            cacheNtkViewerImageUrls(cacheKey, urls);
                            Log.d(TAG, "ntk_images_api_after_webview_ack_success path=" + path
                                    + ",count=" + urls.size()
                                    + ",ms=" + (System.currentTimeMillis() - ackStartedAt));
                            return urls;
                        }
                        Log.d(TAG, "ntk_images_api_after_webview_ack_miss path=" + path
                                + ",code=" + (retryResult == null ? 0 : retryResult.code)
                                + ",ackRequired=" + ntkViewerImagesAckRequired(retryResult));
                    }
                }
                if(Thread.currentThread().isInterrupted())
                    return urls;
                return urls;
            }

            String nvAfterAck = getCookie("nv");
            if(!isNtkNvValid(nvAfterAck)) {
                issueNtkNvCookie(baseUrl);
                nvAfterAck = getCookie("nv");
            }
            if(isNtkNvValid(nvAfterAck))
                nv = nvAfterAck;
            if(modernGuardRoot && shouldPreferHiddenViewerImagesForSlug(kind, path) && context != null
                    && (imagesToken == null || imagesToken.length() == 0)) {
                ArrayList<String> slugWebViewUrls = NtkWebViewFallbackManager.get(context)
                        .fetchViewerImageUrls(agent, baseUrl, path, cookiePath, headers, kind,
                                workId, episodeId, imagesToken, getCookieHeaderForNtkPath(cookiePath),
                                viewerBody);
                Log.d(TAG, "ntk_images_api_slug_hidden_first path=" + path
                        + ",count=" + slugWebViewUrls.size()
                        + ",ms=" + (System.currentTimeMillis() - ackStartedAt));
                urls.addAll(slugWebViewUrls);
                if(urls.size() > 0) {
                    if(areInitialNtkViewerImageUrlsReachable(urls, baseUrl, path)) {
                        cacheNtkViewerImageUrls(cacheKey, urls);
                        return urls;
                    }
                    Log.d(TAG, "ntk_images_api_slug_hidden_first_invalid path="
                            + path + ",count=" + urls.size());
                    NtkWebViewFallbackManager.get(context).dropCachedViewerImageUrls(
                            kind, workId, episodeId, path);
                    urls.clear();
                }
            }
            JSONObject payload = ntkViewerImagesPayload(workId, episodeId, imagesToken, nv);
            NtkQuicFetcher.Result result = fetchNtkViewerImagesApi(baseUrl, endpoint, path,
                    cookiePath, headers, payload, trustedUrlsCallback);
            boolean hardForbidden = ntkViewerImagesHardForbidden(result);
            ViewerWarmupManager.logMetric("ntk_images_api_code", result == null ? 0 : result.code);
            if(appendReachableNtkViewerImages(urls, result, baseUrl, path,
                    kind, workId, episodeId, trustedUrlsCallback)) {
                cacheNtkViewerImageUrls(cacheKey, urls);
                return urls;
            }
            if(ntkViewerImagesCount(result) > 0)
                return urls;
            if(hardForbidden)
                Log.d(TAG, "ntk_images_api_hard_forbidden path=" + path
                        + ",code=" + (result == null ? 0 : result.code));
            if(ntkViewerImagesNeedsAckRefresh(result)) {
                Log.d(TAG, "ntk_images_api_ack_refresh path=" + path
                        + ",code=" + (result == null ? 0 : result.code)
                        + ",ackRequired=" + ntkViewerImagesAckRequired(result));
                nativeAckCompleted = performNtkNativeAckBypassFresh(baseUrl, cookiePath, apiRefererPath);
                if(nativeAckCompleted) {
                    nvAfterAck = getCookie("nv");
                    if(!isNtkNvValid(nvAfterAck)) {
                        issueNtkNvCookie(baseUrl);
                        nvAfterAck = getCookie("nv");
                    }
                    if(isNtkNvValid(nvAfterAck))
                        payload = ntkViewerImagesPayload(workId, episodeId, imagesToken, nvAfterAck);
                    result = fetchNtkViewerImagesApi(baseUrl, endpoint, path, cookiePath, headers, payload,
                            trustedUrlsCallback);
                    hardForbidden = hardForbidden || ntkViewerImagesHardForbidden(result);
                    ViewerWarmupManager.logMetric("ntk_images_api_code", result == null ? 0 : result.code);
                    if(appendReachableNtkViewerImages(urls, result, baseUrl, path,
                            kind, workId, episodeId, trustedUrlsCallback)) {
                        cacheNtkViewerImageUrls(cacheKey, urls);
                        return urls;
                    }
                    if(ntkViewerImagesCount(result) > 0)
                        return urls;
                }
            } else if(!hardForbidden && ntkViewerImagesHasRetryableResponse(result)) {
                nvAfterAck = getCookie("nv");
                if(!isNtkNvValid(nvAfterAck)) {
                    issueNtkNvCookie(baseUrl);
                    nvAfterAck = getCookie("nv");
                }
                if(!isNtkNvValid(nvAfterAck))
                    return urls;
                payload = ntkViewerImagesPayload(workId, episodeId, imagesToken, nvAfterAck);
                result = fetchNtkViewerImagesApi(baseUrl, endpoint, path, cookiePath, headers, payload,
                        trustedUrlsCallback);
                ViewerWarmupManager.logMetric("ntk_images_api_code", result == null ? 0 : result.code);
                if(appendReachableNtkViewerImages(urls, result, baseUrl, path,
                        kind, workId, episodeId, trustedUrlsCallback)) {
                    cacheNtkViewerImageUrls(cacheKey, urls);
                    return urls;
                }
                if(ntkViewerImagesCount(result) > 0)
                    return urls;
                hardForbidden = hardForbidden || ntkViewerImagesHardForbidden(result);
            }
            if(modernGuardRoot && hardForbidden && urls.size() == 0
                    && webViewAckPreflightRace != null && !hasStrictNtkViewerImagesAckReady(baseUrl, cookiePath)) {
                long guardDeadline = System.currentTimeMillis() + 24_000L;
                boolean guardReady = false;
                while(System.currentTimeMillis() < guardDeadline) {
                    if(hasStrictNtkViewerImagesAckReady(baseUrl, cookiePath)) {
                        guardReady = true;
                        break;
                    }
                    if(webViewAckPreflightRace.isDone()) {
                        try {
                            guardReady = webViewAckPreflightRace.get();
                        } catch (Exception ignored) {
                            guardReady = false;
                        }
                        if(guardReady || hasStrictNtkViewerImagesAckReady(baseUrl, cookiePath))
                            break;
                    }
                    if(Thread.currentThread().isInterrupted())
                        return urls;
                    Thread.sleep(80L);
                }
                syncCookiesFromWebView(baseUrl, true);
                syncCookiesFromWebView(baseUrl + cookiePath, true);
                guardReady = guardReady || hasStrictNtkViewerImagesAckReady(baseUrl, cookiePath);
                Log.d(TAG, "ntk_images_api_guard_preflight_join_before_hard_forbidden path=" + path
                        + ",success=" + guardReady
                        + ",adGuardL=" + (getCookie("ad_guard_l") != null)
                        + ",ms=" + (System.currentTimeMillis() - ackStartedAt));
                if(guardReady) {
                    nvAfterAck = getCookie("nv");
                    if(!isNtkNvValid(nvAfterAck)) {
                        issueNtkNvCookie(baseUrl);
                        nvAfterAck = getCookie("nv");
                    }
                    if(isNtkNvValid(nvAfterAck))
                        payload = ntkViewerImagesPayload(workId, episodeId, imagesToken, nvAfterAck);
                    result = fetchNtkViewerImagesApi(baseUrl, endpoint, path, cookiePath, headers, payload,
                            trustedUrlsCallback);
                    hardForbidden = ntkViewerImagesHardForbidden(result);
                    ViewerWarmupManager.logMetric("ntk_images_api_code", result == null ? 0 : result.code);
                    if(appendReachableNtkViewerImages(urls, result, baseUrl, path,
                            kind, workId, episodeId, trustedUrlsCallback)) {
                        cacheNtkViewerImageUrls(cacheKey, urls);
                        Log.d(TAG, "ntk_images_api_after_guard_preflight_success path=" + path
                                + ",count=" + urls.size()
                                + ",ms=" + (System.currentTimeMillis() - ackStartedAt));
                        return urls;
                    }
                    if(ntkViewerImagesCount(result) > 0)
                        return urls;
                    Log.d(TAG, "ntk_images_api_after_guard_preflight_miss path=" + path
                            + ",code=" + (result == null ? 0 : result.code)
                            + ",ackRequired=" + ntkViewerImagesAckRequired(result)
                            + ",ms=" + (System.currentTimeMillis() - ackStartedAt));
                }
            }

            Log.d(TAG, "ntk_images_api_webview_start path=" + path + ",hardForbidden=" + hardForbidden);
            urls.addAll(NtkWebViewFallbackManager.get(context).fetchViewerImageUrls(agent, baseUrl,
                    path, cookiePath, headers, kind, workId, episodeId, imagesToken,
                    getCookieHeaderForNtkPath(cookiePath), viewerBody));
            Log.d(TAG, "ntk_images_api_webview_result path=" + path + ",count=" + urls.size());
            if(urls.size() > 0) {
                if(areInitialNtkViewerImageUrlsReachable(urls, baseUrl, path)) {
                    cacheNtkViewerImageUrls(cacheKey, urls);
                    return urls;
                }
                Log.d(TAG, "ntk_images_api_webview_invalid path=" + path + ",count=" + urls.size());
                NtkWebViewFallbackManager.get(context).dropCachedViewerImageUrls(kind, workId, episodeId, path);
                urls.clear();
            }
            if(hardForbidden && urls.size() == 0)
                cacheNtkViewerImageUrlMiss(cacheKey);

        } catch (Exception e) {
            ml.melun.mangaview.report.CrashReporter.record(e);
        }
        return urls;
    }

    private boolean shouldSkipNtkViewerImageApiForHardBlock(String baseUrl, String path,
                                                            String cookiePath) {
        boolean hardBlocked = hasRecentNtkHardBlock()
                || hasRecentNtkAckChallengeHardBlockForPath(baseUrl, path)
                || hasRecentNtkAckChallengeHardBlockForPath(baseUrl, cookiePath);
        if(!hardBlocked)
            return false;
        if(hasRecentNtkViewerImageAckProof(baseUrl, path, cookiePath)) {
            Log.d(TAG, "ntk_images_api_hardblock_bypass_ack_proof path=" + path
                    + ",ackPath=" + cookiePath);
            return false;
        }
        if(isModernNtkGuardRoot(baseUrl) && isNtkWebViewAckInFlight(baseUrl, cookiePath)) {
            Log.d(TAG, "ntk_images_api_hardblock_bypass_ack_inflight path=" + path
                    + ",ackPath=" + cookiePath);
            return false;
        }
        return true;
    }

    private boolean hasRecentNtkViewerImageAckProof(String baseUrl, String path, String cookiePath) {
        if(isModernNtkGuardRoot(baseUrl))
            return hasStrictNtkViewerImagesAckReady(baseUrl, cookiePath);
        return hasNtkViewerImagesAckReady(baseUrl, cookiePath)
                || NtkWebViewFallbackManager.hasRecentServerAckSuccess(cookiePath)
                || NtkWebViewFallbackManager.hasRecentServerAckSuccess(path);
    }

    private static boolean ntkViewerImagesHasRetryableResponse(NtkQuicFetcher.Result result) {
        return result != null
                && result.error == null
                && result.code > 0
                && result.code != 403;
    }

    private JSONObject ntkViewerImagesPayload(String workId, String episodeId,
                                              String imagesToken, String nv) throws Exception {
        String nonce = base64Url(randomBytes(24));
        String proof = hmacSha256Base64Url(nv, imagesToken + "." + nonce + "." + agent);
        String tokenWorkId = ntkViewerImagesTokenField(imagesToken, "w");
        String tokenEpisodeId = ntkViewerImagesTokenField(imagesToken, "e");
        String payloadWorkId = tokenWorkId.length() > 0 ? tokenWorkId : workId;
        String payloadEpisodeId = tokenEpisodeId.length() > 0 ? tokenEpisodeId : episodeId;
        if((tokenWorkId.length() > 0 && !tokenWorkId.equals(workId))
                || (tokenEpisodeId.length() > 0 && !tokenEpisodeId.equals(episodeId))) {
            Log.d(TAG, "ntk_images_api_payload_token_ids workId=" + workId
                    + ",episodeId=" + episodeId
                    + ",tokenWorkId=" + tokenWorkId
                    + ",tokenEpisodeId=" + tokenEpisodeId);
        }
        JSONObject payload = new JSONObject();
        payload.put("workId", payloadWorkId);
        payload.put("episodeId", payloadEpisodeId);
        payload.put("token", imagesToken);
        payload.put("nonce", nonce);
        payload.put("proof", proof);
        return payload;
    }

    private boolean applyRecentNtkRequestKey(String cookiePath, Map<String, String> headers,
                                             JSONObject payload) {
        if(payload == null)
            return false;
        String requestKeyId = NtkWebViewFallbackManager.recentRequestKeyIdForScope(cookiePath);
        if(requestKeyId == null || requestKeyId.length() == 0)
            return false;
        try {
            if(headers != null)
                headers.put("x-ntk-key-id", requestKeyId);
            payload.put("requestKeyId", requestKeyId);
            Log.d(TAG, "ntk_images_api_request_key_header_attached path=" + cookiePath
                    + ",keyId=" + summarizeKeyId(requestKeyId));
            return true;
        } catch (Exception ignored) {
            return false;
        }
    }

    private static void clearNtkViewerSignatureHeaders(Map<String, String> headers) {
        if(headers == null)
            return;
        headers.remove("x-ntk-key-id");
        headers.remove("x-ntk-ts");
        headers.remove("x-ntk-nonce");
        headers.remove("x-ntk-sig");
    }

    private static boolean shouldPreferUnsignedNtkViewerImagesApi(String path, String endpoint) {
        return false;
    }

    private static boolean looksLikeNtkWebViewViewerBody(String body) {
        if(body == null)
            return false;
        String trimmed = body.trim();
        if(trimmed.length() == 0)
            return false;
        String head = trimmed.substring(0, Math.min(trimmed.length(), 768)).toLowerCase(Locale.ROOT);
        return head.startsWith("<!doctype html")
                || head.startsWith("<html")
                || head.contains("ntkviewerquicbridge")
                || head.contains("window.ntkquicbridge");
    }

    private NtkQuicFetcher.Result fetchNtkViewerImagesApi(String baseUrl, String endpoint, String path,
                                                          String cookiePath,
                                                          Map<String, String> headers,
                                                          JSONObject payload) {
        return fetchNtkViewerImagesApi(baseUrl, endpoint, path, cookiePath, headers, payload, null);
    }

    private NtkQuicFetcher.Result fetchNtkViewerImagesApi(String baseUrl, String endpoint, String path,
                                                          String cookiePath,
                                                          Map<String, String> headers,
                                                          JSONObject payload,
                                                          NtkViewerImageUrlsCallback trustedUrlsCallback) {
        Map<String, String> requestHeaders = headers == null
                ? new HashMap<>()
                : new HashMap<>(headers);
        JSONObject unsignedPayload = null;
        try {
            unsignedPayload = new JSONObject(payload.toString());
        } catch(Exception ignored) {
        }
        boolean preferUnsignedFirst = shouldPreferUnsignedNtkViewerImagesApi(path, endpoint);
        if(preferUnsignedFirst && unsignedPayload != null) {
            try {
                Map<String, String> unsignedHeaders = headers == null
                        ? new HashMap<>()
                        : new HashMap<>(headers);
                clearNtkViewerSignatureHeaders(unsignedHeaders);
                byte[] unsignedBody = unsignedPayload.toString().getBytes(StandardCharsets.UTF_8);
                String cookieHeader = getMergedNtkCookieHeaderForUrl(baseUrl, cookiePath);
                NtkQuicFetcher.Result unsignedResult = fetchNtkViewerImagesApiRace(baseUrl,
                        endpoint, cookieHeader, unsignedHeaders, unsignedBody, path,
                        trustedUrlsCallback);
                Log.d(TAG, "ntk_images_api_unsigned_first endpoint=" + endpoint
                        + ",code=" + (unsignedResult == null ? 0 : unsignedResult.code)
                        + ",error=" + (unsignedResult == null ? "" : unsignedResult.error)
                        + ",imageCount=" + ntkViewerImagesCount(unsignedResult));
                NtkWebViewFallbackManager.rememberViewerImageApiResponse(endpoint, path,
                        unsignedPayload, unsignedResult, "native-api-unsigned-first");
                if(unsignedResult != null && unsignedResult.code >= 200
                        && unsignedResult.code < 300)
                    return unsignedResult;
            } catch(InterruptedException e) {
                Log.d(TAG, "ntk_images_api_unsigned_first_interrupted endpoint=" + endpoint);
                Thread.interrupted();
                return null;
            } catch(Exception e) {
                Log.d(TAG, "ntk_images_api_unsigned_first_error endpoint=" + endpoint
                        + ",error=" + e);
            }
        }
        for(int attempt = 0; attempt < 2; attempt++) {
            Map<String, String> attemptHeaders = new HashMap<>(requestHeaders);
            JSONObject attemptPayload;
            try {
                attemptPayload = new JSONObject(payload.toString());
            } catch(org.json.JSONException e) {
                Log.d(TAG, "ntk_images_api_payload_clone_error endpoint=" + endpoint
                        + ",error=" + e);
                return null;
            }
            clearNtkViewerSignatureHeaders(attemptHeaders);
            String signatureFormat = attempt == 0 ? "p1363" : "der";
            boolean nativeSigned = applyNtkViewerImagesSignature(baseUrl, endpoint, path, cookiePath,
                    attemptHeaders, attemptPayload, signatureFormat);
            boolean recentKeyAttached = !nativeSigned
                    && applyRecentNtkRequestKey(cookiePath, attemptHeaders, attemptPayload);
            try {
                byte[] body = attemptPayload.toString().getBytes(StandardCharsets.UTF_8);
                String cookieHeader = getMergedNtkCookieHeaderForUrl(baseUrl, cookiePath);
                NtkQuicFetcher.Result result = fetchNtkViewerImagesApiRace(baseUrl, endpoint,
                        cookieHeader, attemptHeaders, body, path, trustedUrlsCallback);
                Log.d(TAG, "ntk_images_api_primary endpoint=" + endpoint
                        + ",attempt=" + attempt
                        + ",signed=" + nativeSigned
                        + ",recentKey=" + recentKeyAttached
                        + ",format=" + signatureFormat
                        + ",code=" + (result == null ? 0 : result.code)
                        + ",error=" + (result == null ? "" : result.error)
                        + ",imageCount=" + ntkViewerImagesCount(result)
                        + ",ackRequired=" + ntkViewerImagesAckRequired(result));
                NtkWebViewFallbackManager.rememberViewerImageApiResponse(endpoint, path, payload,
                        result, "native-api-primary");
                if(result != null && result.body != null && result.code >= 400)
                    Log.d(TAG, "ntk_images_api_primary_body="
                            + result.body.substring(0, Math.min(500, result.body.length())));
                if(result != null && attempt == 0
                        && (result.code == 403 || result.code == 428)) {
                    Log.d(TAG, "ntk_images_api_primary_retry_der endpoint=" + endpoint
                            + ",code=" + result.code
                            + ",path=" + path);
                    continue;
                }
                if(result != null && result.code == 403 && shouldPreferUnsignedNtkViewerImagesApi(path, endpoint)
                        && (nativeSigned || recentKeyAttached) && unsignedPayload != null) {
                    Map<String, String> unsignedHeaders = headers == null
                            ? new HashMap<>()
                            : new HashMap<>(headers);
                    clearNtkViewerSignatureHeaders(unsignedHeaders);
                    byte[] unsignedBody = unsignedPayload.toString().getBytes(StandardCharsets.UTF_8);
                    NtkQuicFetcher.Result unsignedResult = fetchNtkViewerImagesApiRace(baseUrl,
                            endpoint, cookieHeader, unsignedHeaders, unsignedBody, path,
                            trustedUrlsCallback);
                    Log.d(TAG, "ntk_images_api_unsigned_retry endpoint=" + endpoint
                            + ",code=" + (unsignedResult == null ? 0 : unsignedResult.code)
                            + ",error=" + (unsignedResult == null ? "" : unsignedResult.error)
                            + ",imageCount=" + ntkViewerImagesCount(unsignedResult)
                            + ",afterSigned403=true");
                    NtkWebViewFallbackManager.rememberViewerImageApiResponse(endpoint, path,
                            unsignedPayload, unsignedResult, "native-api-unsigned-retry");
                    if(unsignedResult != null && unsignedResult.code >= 200
                            && unsignedResult.code < 300)
                        return unsignedResult;
                }
                return result;
            } catch (InterruptedException e) {
                Log.d(TAG, "ntk_images_api_interrupted endpoint=" + endpoint
                        + ",attempt=" + attempt);
                Thread.interrupted();
                return null;
            }
        }
        return null;
    }

    private synchronized boolean applyNtkViewerImagesSignature(String baseUrl, String endpoint,
                                                              String path, String cookiePath,
                                                              Map<String, String> headers,
                                                              JSONObject payload,
                                                              String signatureFormat) {
        if(baseUrl == null || endpoint == null || path == null || payload == null || headers == null)
            return false;
        try {
            String recentRequestKeyId = NtkWebViewFallbackManager.recentRequestKeyIdForScope(cookiePath);
            if(recentRequestKeyId != null && recentRequestKeyId.length() > 0) {
                payload.put("requestKeyId", recentRequestKeyId);
                String recentBodyText = payload.toString();
                Map<String, String> recentHeaders = NtkWebViewFallbackManager.signRecentRequestKeyForScope(
                        cookiePath, "POST", endpoint, path, recentBodyText, signatureFormat);
                if(recentHeaders != null && !recentHeaders.isEmpty()) {
                    headers.putAll(recentHeaders);
                    Log.d(TAG, "ntk_images_api_recent_ack_key_signed path=" + cookiePath
                            + ",endpoint=" + endpoint
                            + ",keyId=" + summarizeKeyId(recentRequestKeyId)
                            + ",format=" + signatureFormat
                            + ",bodyLen=" + recentBodyText.length());
                    return true;
                }
            }
            String keyId = ensureNtkViewerBrowserKey(baseUrl, cookiePath);
            if(keyId.length() == 0 || ntkViewerBrowserKeyPair == null)
                return false;
            payload.put("requestKeyId", keyId);
            String bodyText = payload.toString();
            String timestamp = String.valueOf(Math.floorDiv(
                    System.currentTimeMillis() + ntkViewerBrowserKeyServerTimeOffsetMs, 1L));
            String nonce = base64Url(randomBytes(24));
            String bodyHash = sha256Base64Url(bodyText);
            String base = "ntk-brsig-v1\nPOST\n" + endpoint + "\n" + path + "\n"
                    + keyId + "\n" + timestamp + "\n" + nonce + "\n" + bodyHash;
            boolean der = "der".equalsIgnoreCase(signatureFormat);
            byte[] signature = der
                    ? signNtkViewerDer((java.security.interfaces.ECPrivateKey)
                            ntkViewerBrowserKeyPair.getPrivate(), base.getBytes(StandardCharsets.UTF_8))
                    : signNtkViewerP1363((java.security.interfaces.ECPrivateKey)
                            ntkViewerBrowserKeyPair.getPrivate(), base.getBytes(StandardCharsets.UTF_8));
            boolean lowSAdjusted = !der && normalizeP1363LowS(signature);
            headers.put("x-ntk-key-id", keyId);
            headers.put("x-ntk-ts", timestamp);
            headers.put("x-ntk-nonce", nonce);
            String signatureText = base64Url(signature);
            headers.put("x-ntk-sig", signatureText);
            Log.d(TAG, "ntk_images_api_native_signed path=" + path
                    + ",endpoint=" + endpoint
                    + ",keyId=" + summarizeKeyId(keyId)
                    + ",format=" + (der ? "der" : "p1363")
                    + ",sigLen=" + signature.length
                    + ",sigTextLen=" + signatureText.length()
                    + ",bodyLen=" + bodyText.length()
                    + ",bodyKey=true"
                    + ",serverOffsetMs=" + ntkViewerBrowserKeyServerTimeOffsetMs
                    + ",lowSAdjusted=" + lowSAdjusted);
            return true;
        } catch (Exception e) {
            Log.d(TAG, "ntk_images_api_native_sign_error path=" + path
                    + ",endpoint=" + endpoint
                    + ",format=" + signatureFormat
                    + ",error=" + e);
            return false;
        }
    }

    private synchronized String ensureNtkViewerBrowserKey(String baseUrl, String cookiePath) {
        try {
            long now = System.currentTimeMillis();
            long signedNow = now + ntkViewerBrowserKeyServerTimeOffsetMs;
            if(ntkViewerBrowserKeyPair != null && ntkViewerBrowserKeyId.length() > 0
                    && (ntkViewerBrowserKeyExpiresAt <= 0
                    || ntkViewerBrowserKeyExpiresAt - signedNow > 30_000L))
                return ntkViewerBrowserKeyId;
            java.security.KeyPairGenerator generator =
                    java.security.KeyPairGenerator.getInstance("EC");
            generator.initialize(new java.security.spec.ECGenParameterSpec("secp256r1"));
            java.security.KeyPair keyPair = generator.generateKeyPair();
            java.security.interfaces.ECPublicKey publicKey =
                    (java.security.interfaces.ECPublicKey) keyPair.getPublic();
            JSONObject jwk = new JSONObject();
            jwk.put("kty", "EC");
            jwk.put("crv", "P-256");
            jwk.put("ext", true);
            JSONArray keyOps = new JSONArray();
            keyOps.put("verify");
            jwk.put("key_ops", keyOps);
            jwk.put("x", base64Url(fixedUnsigned32(publicKey.getW().getAffineX())));
            jwk.put("y", base64Url(fixedUnsigned32(publicKey.getW().getAffineY())));
            JSONObject requestBody = new JSONObject();
            requestBody.put("publicKey", jwk);
            Map<String, String> headers = new HashMap<>();
            headers.put("content-type", "application/json");
            headers.put("accept", "application/json");
            String pageUrl = baseUrl + ntkNativeAckScopePath(cookiePath);
            headers.put("origin", baseUrl);
            headers.put("referer", pageUrl);
            headers.put("user-agent", agent);
            headers.put("sec-fetch-dest", "empty");
            headers.put("sec-fetch-mode", "cors");
            headers.put("sec-fetch-site", "same-origin");
            headers.put("priority", "u=1, i");
            String cookieHeader = getMergedNtkCookieHeaderForUrl(baseUrl, cookiePath);
            String fp = ntkCookieValue(cookieHeader, "ntk_fp");
            String pid = ntkCookieValue(cookieHeader, "ntk_pid");
            String vsid = ntkCookieValue(cookieHeader, "__vsid");
            String eventId = ntkCookieValue(cookieHeader, "__ntk_ev_id");
            boolean fpFromIdentity = false;
            if(fp.length() == 0) {
                fp = ntkTokenIdentity(ntkCookieValue(cookieHeader, "ad_ack_c"));
                if(fp.length() == 0)
                    fp = ntkTokenIdentity(ntkCookieValue(cookieHeader, "ad_ack"));
                fpFromIdentity = fp.length() > 0;
            }
            boolean fpGenerated = false;
            if(fp.length() == 0) {
                fp = generatedNtkFp(pageUrl, agent);
                fpGenerated = fp.length() > 0;
                if(fpGenerated) {
                    cookieHeader = appendCookieForRequest(cookieHeader, "ntk_fp", fp);
                    try {
                        cookies.put("ntk_fp", fp);
                        invalidateCookieHeaderCache();
                        persistCookies();
                        CookieManager manager = CookieManager.getInstance();
                        manager.setCookie(baseUrl,
                                "ntk_fp=" + fp + "; Path=/; Max-Age=31536000; SameSite=Lax; Secure");
                        manager.flush();
                    } catch (Exception ignored) {
                    }
                }
            }
            if(fp.length() > 0) {
                requestBody.put("fp", fp);
                requestBody.put("ntkFp", fp);
                requestBody.put("fingerprint", fp);
            }
            if(pid.length() > 0) {
                requestBody.put("pid", pid);
                requestBody.put("ntkPid", pid);
            }
            if(vsid.length() > 0)
                requestBody.put("vsid", vsid);
            if(eventId.length() > 0)
                requestBody.put("eventId", eventId);
            byte[] body = requestBody.toString().getBytes(StandardCharsets.UTF_8);
            NtkQuicFetcher.Result result = fetchNtkQuic(baseUrl,
                    baseUrl + "/api/client-key/register", cookieHeader, headers,
                    "POST", body, 4500L, null);
            String text = result == null || result.body == null ? "" : result.body;
            JSONObject response = new JSONObject(text.trim().startsWith("{") ? text : "{}");
            String keyId = response.optString("keyId", "");
            boolean ok = result != null && result.error == null && result.code == 200
                    && response.optBoolean("ok", false) && keyId.length() > 0;
            Log.d(TAG, "ntk_images_api_native_key_register path=" + cookiePath
                    + ",code=" + (result == null ? 0 : result.code)
                    + ",error=" + (result == null ? null : result.error)
                    + ",ok=" + ok
                    + ",keyId=" + summarizeKeyId(keyId)
                    + ",serverNow=" + response.optLong("serverNow", 0L)
                    + ",fpPresent=" + (fp.length() > 0)
                    + ",fpFromIdentity=" + fpFromIdentity
                    + ",fpGenerated=" + fpGenerated
                    + ",pidPresent=" + (pid.length() > 0)
                    + ",vsidPresent=" + (vsid.length() > 0)
                    + ",eventIdPresent=" + (eventId.length() > 0)
                    + ",uaLen=" + agent.length()
                    + ",body=" + summarizeBodyPrefix(text, 80));
            if(!ok)
                return "";
            ntkViewerBrowserKeyPair = keyPair;
            ntkViewerBrowserKeyId = keyId;
            long localNow = System.currentTimeMillis();
            long serverNow = response.optLong("serverNow", localNow);
            ntkViewerBrowserKeyServerTimeOffsetMs = serverNow - localNow;
            ntkViewerBrowserKeyExpiresAt = response.optLong("expiresAt", serverNow + 3_600_000L);
            return ntkViewerBrowserKeyId;
        } catch (Exception e) {
            Log.d(TAG, "ntk_images_api_native_key_register_error path=" + cookiePath
                    + ",error=" + e);
            return "";
        }
    }

    private static String ntkCookieValue(String cookieHeader, String name) {
        if(cookieHeader == null || name == null || name.length() == 0)
            return "";
        String[] parts = cookieHeader.split(";");
        for(String part : parts) {
            int eq = part.indexOf('=');
            if(eq <= 0)
                continue;
            String key = part.substring(0, eq).trim();
            if(name.equals(key))
                return part.substring(eq + 1).trim();
        }
        return "";
    }

    private static String appendCookieForRequest(String cookieHeader, String name, String value) {
        if(name == null || name.length() == 0 || value == null || value.length() == 0)
            return cookieHeader == null ? "" : cookieHeader;
        if(ntkCookieValue(cookieHeader, name).length() > 0)
            return cookieHeader == null ? "" : cookieHeader;
        StringBuilder builder = new StringBuilder(cookieHeader == null ? "" : cookieHeader.trim());
        if(builder.length() > 0)
            builder.append("; ");
        builder.append(name).append('=').append(value);
        return builder.toString();
    }

    private static String ntkTokenIdentity(String token) {
        if(token == null || token.length() == 0)
            return "";
        try {
            int dot = token.indexOf('.');
            String payload = dot > 0 ? token.substring(0, dot) : token;
            int padding = (4 - (payload.length() % 4)) % 4;
            StringBuilder padded = new StringBuilder(payload);
            for(int i = 0; i < padding; i++)
                padded.append('=');
            byte[] decoded = Base64.decode(padded.toString(), Base64.URL_SAFE | Base64.NO_WRAP);
            JSONObject json = new JSONObject(new String(decoded, StandardCharsets.UTF_8));
            return json.optString("identity", "");
        } catch (Exception e) {
            return "";
        }
    }

    private static String generatedNtkFp(String pageUrl, String requestUserAgent) {
        try {
            String seed = String.valueOf(requestUserAgent) + "|"
                    + Locale.getDefault().toLanguageTag() + "|"
                    + java.util.TimeZone.getDefault().getID() + "|"
                    + String.valueOf(pageUrl);
            byte[] digest = java.security.MessageDigest.getInstance("SHA-256")
                    .digest(seed.getBytes(StandardCharsets.UTF_8));
            StringBuilder builder = new StringBuilder(64);
            for(byte value : digest)
                builder.append(String.format(Locale.ROOT, "%02x", value & 0xff));
            return builder.substring(0, 32);
        } catch (Exception e) {
            return "";
        }
    }

    private static String summarizeBodyPrefix(String value, int limit) {
        if(value == null || value.length() == 0)
            return "";
        String compact = value.replace('\n', ' ').replace('\r', ' ');
        int max = Math.max(0, limit);
        return compact.length() <= max ? compact : compact.substring(0, max);
    }

    private NtkQuicFetcher.Result fetchNtkViewerImagesApiRace(String baseUrl, String endpoint,
                                                              String cookieHeader,
                                                              Map<String, String> headers,
                                                              byte[] body,
                                                              String refererPath,
                                                              NtkViewerImageUrlsCallback trustedUrlsCallback)
            throws InterruptedException {
        ExecutorService raceExecutor = Executors.newFixedThreadPool(2, runnable -> {
            Thread thread = new Thread(runnable, "ntk-images-api-race");
            thread.setDaemon(true);
            return thread;
        });
        ExecutorCompletionService<NtkImageApiResult> completion =
                new ExecutorCompletionService<>(raceExecutor);
        List<Future<NtkImageApiResult>> futures = new ArrayList<>();
        long startedAt = System.currentTimeMillis();
        int submitted = 0;
        boolean freshSubmitted = false;
        boolean signedRequest = headers != null && headers.containsKey("x-ntk-sig");
        AtomicInteger partialUrlNotifyCount = new AtomicInteger(0);
        NtkQuicFetcher.PartialTextObserver firstUrlObserver = partialText -> {
            if(trustedUrlsCallback == null)
                return;
            List<String> partialUrls = ntkViewerImageUrlsFromPartialApiBody(partialText, 12);
            int previousCount = partialUrlNotifyCount.get();
            if(partialUrls.size() <= previousCount)
                return;
            if(!partialUrlNotifyCount.compareAndSet(previousCount, partialUrls.size()))
                return;
            boolean reachable = areInitialNtkViewerImageUrlsReachable(partialUrls, baseUrl, refererPath);
            Log.d(TAG, "ntk_images_api_partial_urls path=" + refererPath
                    + ",count=" + partialUrls.size()
                    + ",previous=" + previousCount
                    + ",first=" + safeLogUrl(partialUrls.get(0))
                    + ",reachable=" + reachable
                    + ",partialLen=" + (partialText == null ? 0 : partialText.length())
                    + ",ms=" + (System.currentTimeMillis() - startedAt));
            if(!reachable)
                return;
            prepareNtkImageTransportForUrls(partialUrls, refererPath);
            notifyNtkViewerImageUrls(trustedUrlsCallback, partialUrls);
        };
        try {
            futures.add(completion.submit(() -> new NtkImageApiResult("shared",
                    fetchNtkQuic(baseUrl, baseUrl + endpoint, cookieHeader, headers,
                            "POST", signedRequest ? body : freshNtkViewerImagesRequestBody(body),
                            NTK_VIEWER_IMAGES_API_TIMEOUT_MS, firstUrlObserver))));
            submitted++;
            NtkQuicFetcher.Result fallback = null;
            for(int completed = 0; completed < 2; completed++) {
                Future<NtkImageApiResult> future = completion.poll(
                        completed == 0 && !freshSubmitted
                                ? NTK_VIEWER_IMAGES_API_HEDGE_DELAY_MS
                                : NTK_VIEWER_IMAGES_API_TIMEOUT_MS,
                        TimeUnit.MILLISECONDS);
                if(future == null) {
                    if(!freshSubmitted) {
                        futures.add(completion.submit(() -> new NtkImageApiResult("fresh",
                                NtkQuicFetcher.fetch(context, baseUrl + endpoint, agent, cookieHeader, headers,
                                        "POST", signedRequest ? body : freshNtkViewerImagesRequestBody(body), NTK_VIEWER_IMAGES_API_TIMEOUT_MS,
                                        firstUrlObserver))));
                        submitted++;
                        freshSubmitted = true;
                        Log.d(TAG, "ntk_images_api_hedge_start endpoint=" + endpoint
                                + ",ms=" + (System.currentTimeMillis() - startedAt));
                        continue;
                    }
                    break;
                }
                NtkImageApiResult raceResult;
                try {
                    raceResult = future.get();
                } catch(Exception e) {
                    Log.d(TAG, "ntk_images_api_race_done transport=unknown,code=0,error=" + e
                            + ",accepted=false,ms=" + (System.currentTimeMillis() - startedAt));
                    continue;
                }
                NtkQuicFetcher.Result result = raceResult == null ? null : raceResult.result;
                boolean accepted = result != null
                        && result.error == null
                        && result.code >= 200
                        && result.code < 300
                        && ntkViewerImagesCount(result) > 0
                        && !ntkViewerImagesAckRequired(result);
                Log.d(TAG, "ntk_images_api_race_done transport="
                        + (raceResult == null ? "unknown" : raceResult.transport)
                        + ",code=" + (result == null ? 0 : result.code)
                        + ",error=" + (result == null ? null : result.error)
                        + ",accepted=" + accepted
                        + ",count=" + ntkViewerImagesCount(result)
                        + ",ackRequired=" + ntkViewerImagesAckRequired(result)
                        + ",ms=" + (System.currentTimeMillis() - startedAt));
                if(result != null && fallback == null)
                    fallback = result;
                if(accepted)
                    return result;
                if(!freshSubmitted && completed + 1 >= submitted) {
                    futures.add(completion.submit(() -> new NtkImageApiResult("fresh",
                            NtkQuicFetcher.fetch(context, baseUrl + endpoint, agent, cookieHeader, headers,
                                    "POST", signedRequest ? body : freshNtkViewerImagesRequestBody(body), NTK_VIEWER_IMAGES_API_TIMEOUT_MS,
                                    firstUrlObserver))));
                    submitted++;
                    freshSubmitted = true;
                    Log.d(TAG, "ntk_images_api_hedge_start endpoint=" + endpoint
                            + ",ms=" + (System.currentTimeMillis() - startedAt));
                }
            }
            return fallback;
        } finally {
            raceExecutor.shutdown();
        }
    }

    private byte[] freshNtkViewerImagesRequestBody(byte[] originalBody) {
        if(originalBody == null || originalBody.length == 0)
            return originalBody;
        try {
            JSONObject original = new JSONObject(new String(originalBody, StandardCharsets.UTF_8));
            String workId = original.optString("workId", "");
            String episodeId = original.optString("episodeId", "");
            String token = original.optString("token", "");
            String nv = getCookie("nv");
            if(workId.length() == 0 || episodeId.length() == 0 || token.length() == 0
                    || !isNtkNvValid(nv))
                return originalBody;
            return ntkViewerImagesPayload(workId, episodeId, token, nv)
                    .toString().getBytes(StandardCharsets.UTF_8);
        } catch(Exception e) {
            Log.d(TAG, "ntk_images_api_body_refresh_error=" + e);
            return originalBody;
        }
    }

    private static final class NtkImageApiResult {
        final String transport;
        final NtkQuicFetcher.Result result;

        NtkImageApiResult(String transport, NtkQuicFetcher.Result result) {
            this.transport = transport;
            this.result = result;
        }
    }

    private static int ntkViewerImagesCount(NtkQuicFetcher.Result result) {
        if(result == null || result.body == null)
            return 0;
        try {
            JSONObject response = new JSONObject(result.body);
            JSONArray images = response.optJSONArray("images");
            return images == null ? 0 : images.length();
        } catch(Exception ignored) {
            return 0;
        }
    }

    private static boolean ntkViewerImagesAckRequired(NtkQuicFetcher.Result result) {
        if(result == null || result.body == null)
            return false;
        return ntkViewerImagesAckRequired(result.body);
    }

    private static boolean ntkViewerImagesAckRequired(String body) {
        if(body == null)
            return false;
        try {
            JSONObject response = new JSONObject(body);
            return response.optBoolean("ad_ack_required", false)
                    || "ad_ack_required".equals(response.optString("error", ""));
        } catch(Exception ignored) {
            return false;
        }
    }

    private static boolean ntkViewerImagesNeedsAckRefresh(NtkQuicFetcher.Result result) {
        return ntkViewerImagesAckRequired(result);
    }

    static boolean ntkViewerImagesNeedsAckRefreshForTest(int code, String body) {
        return ntkViewerImagesAckRequired(body);
    }

    private boolean shouldTryNtkViewerImagesBeforeAck(String kind, String baseUrl, String path,
                                                      String cookiePath, boolean ackInFlight) {
        if(isModernNtkGuardRoot(baseUrl))
            return false;
        return shouldTryNtkViewerImagesBeforeAck(kind, path,
                hasNtkAdAckCookieForPath(cookiePath), ackInFlight);
    }

    private boolean hasNtkViewerImagesAckReady(String baseUrl, String path) {
        if(isModernNtkGuardRoot(baseUrl)) {
            String ackPath = ntkNativeAckScopePath(path);
            return hasNtkWebViewAckPreflightReady(baseUrl, ackPath)
                    || NtkWebViewFallbackManager.hasRecentStrictAdAckSuccess(ackPath);
        }
        return hasNtkAdAckCookieForPath(path);
    }

    private boolean hasStrictNtkViewerImagesAckReady(String baseUrl, String path) {
        if(isModernNtkGuardRoot(baseUrl)) {
            String ackPath = ntkNativeAckScopePath(path);
            return hasNtkWebViewAckPreflightReady(baseUrl, ackPath)
                    || NtkWebViewFallbackManager.hasRecentStrictAdAckSuccess(ackPath);
        }
        return hasNtkAdAckCookieForPath(path);
    }

    static boolean shouldTryNtkViewerImagesBeforeAckForTest(String kind, String path,
                                                            boolean hasAckCookie, boolean ackInFlight) {
        return shouldTryNtkViewerImagesBeforeAck(kind, path, hasAckCookie, ackInFlight);
    }

    static boolean shouldTryNtkViewerImagesBeforeAckForTest(String kind, String baseUrl, String path,
                                                            boolean hasAckCookie, boolean ackInFlight) {
        if(isModernNtkGuardRoot(baseUrl))
            return false;
        return shouldTryNtkViewerImagesBeforeAck(kind, path, hasAckCookie, ackInFlight);
    }

    private static boolean shouldTryNtkViewerImagesBeforeAck(String kind, String path,
                                                             boolean hasAckCookie, boolean ackInFlight) {
        if(hasAckCookie)
            return false;
        if(path == null || path.length() == 0)
            return false;
        Matcher matcher = Pattern.compile("^/(?:manhwa|webtoon)/([^/?#]+)/([^/?#]+)").matcher(path);
        if(!matcher.find())
            return false;
        String workId = matcher.group(1);
        String episodeId = matcher.group(2);
        boolean generatedNumeric = workId.matches("\\d+") && episodeId.matches("\\d+");
        return !generatedNumeric || ackInFlight;
    }

    private static boolean ntkViewerImagesHardForbidden(NtkQuicFetcher.Result result) {
        return result != null && result.code == 403 && !ntkViewerImagesAckRequired(result);
    }

    private static boolean appendNtkViewerImages(List<String> urls, NtkQuicFetcher.Result result,
                                                 String kind, String workId, String episodeId) {
        if(urls == null || result == null || result.error != null
                || result.code < 200 || result.code >= 300 || result.body == null)
            return false;
        try {
            JSONObject response = new JSONObject(result.body);
            if(response.optBoolean("ad_ack_required", false))
                return false;
            if(!response.optBoolean("ok", false))
                return false;
            JSONArray images = response.optJSONArray("images");
            if(images == null)
                return false;
            int before = urls.size();
            for(int i = 0; i < images.length(); i++) {
                JSONObject image = images.optJSONObject(i);
                String src = image == null ? "" : image.optString("src", "");
                String normalized = normalizeNtkViewerApiImageSrc(src, kind, workId, episodeId);
                if(normalized.length() > 0 && isTrustedNtkPrimaryImageUrl(normalized))
                    urls.add(normalized);
            }
            return urls.size() > before;
        } catch(Exception ignored) {
            return false;
        }
    }

    private static String normalizeNtkViewerApiImageSrc(String src, String kind,
                                                        String workId, String episodeId) {
        if(src == null)
            return "";
        String trimmed = src.trim();
        if(trimmed.length() == 0)
            return "";
        String stableCdn = normalizeNtkVolatileCdnImageSrc(trimmed);
        if(stableCdn.length() > 0)
            return stableCdn;
        String canonicalLegacy = canonicalLegacyNtkViewerImageSrc(trimmed);
        if(canonicalLegacy.length() > 0)
            return canonicalLegacy;
        if(isTrustedNtkPrimaryImageUrl(trimmed))
            return trimmed;
        Matcher pageMatcher = Pattern.compile("(?i)^p(\\d{3})\\.(jpg|jpeg|png|webp)$")
                .matcher(trimmed);
        if(!pageMatcher.find() || workId == null || episodeId == null
                || !workId.matches("\\d+") || !episodeId.matches("\\d+"))
            return "";
        String extension = pageMatcher.group(2).toLowerCase(Locale.ROOT);
        String pageFile = "p" + pageMatcher.group(1) + "." + extension;
        if("webtoon".equals(kind))
            return String.format(Locale.ROOT,
                    "https://moamoabon.com/blacktoon/episodes/%s/%s/%s",
                    workId, episodeId, pageFile);
        return String.format(Locale.ROOT,
                "https://moamoabon.com/manhwa/%s/%s/%s",
                workId, episodeId, pageFile);
    }

    private static String canonicalLegacyNtkViewerImageSrc(String src) {
        if(src == null || src.length() == 0)
            return "";
        try {
            HttpUrl parsed = HttpUrl.parse(src);
            if(parsed == null)
                return "";
            String path = parsed.encodedPath();
            if(path == null)
                return "";
            Matcher matcher = Pattern.compile(
                    "^/black/episodes/(\\d+)/([^/?#]+)/((?:p)?\\d{1,5}\\.(?:jpg|jpeg|png|webp))$",
                    Pattern.CASE_INSENSITIVE).matcher(path);
            if(!matcher.find())
                return "";
            String query = parsed.encodedQuery();
            return "https://moamoabon.com/blacktoon/episodes/"
                    + matcher.group(1) + "/" + matcher.group(2) + "/" + matcher.group(3)
                    + (query == null || query.length() == 0 ? "" : "?" + query);
        } catch(Exception ignored) {
            return "";
        }
    }

    static String normalizeNtkViewerApiImageSrcForTest(String src, String kind,
                                                       String workId, String episodeId) {
        return normalizeNtkViewerApiImageSrc(src, kind, workId, episodeId);
    }

    private static String normalizeNtkVolatileCdnImageSrc(String src) {
        if(src == null || src.length() == 0)
            return "";
        HttpUrl parsed = HttpUrl.parse(src);
        if(parsed == null || !"https".equals(parsed.scheme()))
            return "";
        String host = parsed.host() == null ? "" : parsed.host().toLowerCase(Locale.ROOT);
        if(!host.matches("aws-cdn\\d*\\.site")
                && !host.matches("flysky\\d*m\\.com")
                && !"moamoabon.com".equals(host)
                && !host.matches("fvcdn\\d*\\.com"))
            return "";
        String path = parsed.encodedPath() == null ? "" : parsed.encodedPath();
        if(!isTrustedNtkPrimaryImagePath(path.toLowerCase(Locale.ROOT)))
            return "";
        String query = parsed.encodedQuery();
        String lowerPath = path.toLowerCase(Locale.ROOT);
        String canonicalPath = lowerPath.startsWith("/black/episodes/")
                ? "/blacktoon/episodes/" + path.substring("/black/episodes/".length())
                : path;
        return "https://moamoabon.com" + canonicalPath
                + (query == null || query.length() == 0 ? "" : "?" + query);
    }

    private static String firstNtkViewerImageUrlFromPartialApiBody(String body) {
        if(body == null || body.length() == 0)
            return "";
        List<String> urls = ntkViewerImageUrlsFromPartialApiBody(body, 1);
        return urls.isEmpty() ? "" : urls.get(0);
    }

    private static List<String> ntkViewerImageUrlsFromPartialApiBody(String body, int limit) {
        ArrayList<String> urls = new ArrayList<>();
        if(body == null || body.length() == 0 || limit <= 0)
            return urls;
        LinkedHashSet<String> ordered = new LinkedHashSet<>();
        collectNtkViewerImageUrlsFromPartialApiBody(ordered, body,
                Pattern.compile("\"src\"\\s*:\\s*\"([^\"]+)\""), limit);
        if(ordered.size() < limit)
            collectNtkViewerImageUrlsFromPartialApiBody(ordered, body,
                    Pattern.compile("\\\\\"src\\\\\"\\s*:\\s*\\\\\"([^\\\\\"]+)\\\\\""), limit);
        urls.addAll(ordered);
        return urls;
    }

    private static void collectNtkViewerImageUrlsFromPartialApiBody(Set<String> out, String body,
                                                                    Pattern pattern, int limit) {
        if(out == null || body == null || pattern == null || limit <= 0 || out.size() >= limit)
            return;
        Matcher matcher = pattern.matcher(body);
        while(matcher.find() && out.size() < limit) {
            String src = matcher.group(1);
            if(src == null || src.length() == 0)
                continue;
            src = src.replace("\\/", "/")
                    .replace("\\u002F", "/")
                    .replace("\\u002f", "/");
            String stable = normalizeNtkVolatileCdnImageSrc(src);
            if(stable.length() > 0)
                src = stable;
            if(isTrustedNtkPrimaryImageUrl(src))
                out.add(src);
        }
    }

    private static boolean isNtkNvValid(String nv) {
        if(nv == null || nv.length() == 0)
            return false;
        String[] parts = nv.split("\\.");
        return parts.length >= 1 && parts[0].length() >= 40;
    }

    private static boolean isNtkAckJsonOk(NtkQuicFetcher.Result result) {
        if(result == null || result.error != null || result.code != 200 || result.body == null)
            return false;
        try {
            JSONObject json = new JSONObject(result.body);
            return json.optBoolean("ok", false)
                    || json.optBoolean("acked", false)
                    || "ok".equals(json.optString("status", null))
                    || "acked".equals(json.optString("status", null));
        } catch(Exception ignored) {
            return false;
        }
    }

    private void issueNtkNvCookie(String baseUrl) {
        try {
            Map<String, String> headers = new HashMap<>();
            headers.put("accept", "application/json");
            headers.put("origin", baseUrl);
            headers.put("referer", baseUrl + "/");
            long startedAt = System.currentTimeMillis();
            NtkQuicFetcher.Result result = fetchNtkQuic(baseUrl, baseUrl + "/api/nv-issue",
                    getCookieHeader(), headers, "POST", new byte[0], 5000L);
            boolean accepted = applyNtkNvIssueResult(baseUrl, "quic", result, startedAt);
            if(!accepted) {
                long fallbackStartedAt = System.currentTimeMillis();
                NtkQuicFetcher.Result fallback = fetchNtkApiPostOkHttp(baseUrl, "/api/nv-issue",
                        headers, new byte[0], "application/json; charset=utf-8");
                applyNtkNvIssueResult(baseUrl, "okhttp", fallback, fallbackStartedAt);
            }
        } catch (Exception e) {
            Log.d(TAG, "ntk_nv_issue_error " + e);
            ml.melun.mangaview.report.CrashReporter.record(e);
        }
    }

    private boolean applyNtkNvIssueResult(String baseUrl, String transport, NtkQuicFetcher.Result result,
                                          long startedAt) {
        boolean transportOk = result != null && result.error == null && result.code >= 200 && result.code < 300;
        if(result != null && result.headers != null)
            applySetCookieHeaders(result.headers, baseUrl);
        String nv = getCookie("nv");
        boolean nvValid = isNtkNvValid(nv);
        Log.d(TAG, "ntk_nv_issue transport=" + transport
                + ",code=" + (result == null ? 0 : result.code)
                + ",transportOk=" + transportOk
                + ",nvValid=" + nvValid
                + ",setCookies=" + summarizeSetCookieNames(result == null ? null : result.headers)
                + ",body=" + summarizeNtkBody(result == null ? null : result.body)
                + ",error=" + (result == null || result.error == null ? "none" : result.error.getClass().getSimpleName())
                + ",ms=" + (System.currentTimeMillis() - startedAt));
        return transportOk && nvValid;
    }

    public static byte[] decryptAdGuardWasm(byte[] encryptedWasm) {
        try {
            int[][] M = {{82,71,192,87},{45,63,132,197},{77,64,253,242},{33,232,165,75}};
            int[][] H = {{248,84,63,18},{233,12,138,230},{115,147,80,3},{226,132,67,42},{184,152,154,50},{18,168,87,79},{33,144,180,203},{111,235,186,206}};
            int[] perm = {3,0,6,1,4,7,2,5};

            byte[] n = new byte[16];
            for(int t = 0; t < 4; t++) {
                int mask = (163 + 71*t) & 255;
                for(int r = 0; r < 4; r++) {
                    n[4*t + r] = (byte)(M[t][r] ^ mask);
                }
            }

            javax.crypto.Mac mac = javax.crypto.Mac.getInstance("HmacSHA256");
            mac.init(new javax.crypto.spec.SecretKeySpec(n, "HmacSHA256"));
            byte[] e_hmac = mac.doFinal("ntk-ad-guard-v2".getBytes(StandardCharsets.UTF_8));

            byte[] e = new byte[32];
            for(int r = 0; r < 8; r++) {
                int o = perm[r];
                int mask = (93 + 43*o + 17*r) & 255;
                for(int u = 0; u < 4; u++) {
                    e[4*o + u] = (byte)(H[r][u] ^ mask);
                }
            }

            byte[] f = new byte[32];
            for(int i = 0; i < 32; i++) {
                f[i] = (byte)(e[i] ^ e_hmac[i]);
            }
            StringBuilder fHex = new StringBuilder();
            for(byte b : f) fHex.append(String.format("%02x", b));
            Log.e(TAG, "ntk_wasm_f_hex=" + fHex.toString());

            byte[] iv = java.util.Arrays.copyOfRange(encryptedWasm, 0, 12);
            byte[] ciphertext = java.util.Arrays.copyOfRange(encryptedWasm, 12, encryptedWasm.length);

            javax.crypto.Cipher cipher = javax.crypto.Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(javax.crypto.Cipher.DECRYPT_MODE, new javax.crypto.spec.SecretKeySpec(f, "AES"),
                    new javax.crypto.spec.GCMParameterSpec(128, iv));
            byte[] decrypted = cipher.doFinal(ciphertext);

            for(int c = 0; c < decrypted.length; c++) {
                decrypted[c] ^= e_hmac[c % 32];
            }
            return decrypted;
        } catch(Exception ex) {
            Log.e(TAG, "ntk_wasm_decrypt_error=" + ex);
            return null;
        }
    }

    private String runWasmVcInWebView(byte[] wasmBytes, String adGuardJs, String token) {
        final String[] result = {null};
        final java.util.concurrent.CountDownLatch latch = new java.util.concurrent.CountDownLatch(1);
        android.os.Handler mainHandler = new android.os.Handler(android.os.Looper.getMainLooper());
        mainHandler.post(() -> {
            try {
                android.webkit.WebView webView = new android.webkit.WebView(context);
                webView.getSettings().setJavaScriptEnabled(true);
                webView.addJavascriptInterface(new Object() {
                    @android.webkit.JavascriptInterface
                    public void onVcResult(String value) {
                        Log.e(TAG, "ntk_wasm_js_result=" + value);
                        result[0] = value;
                        latch.countDown();
                    }
                }, "NtkWasmBridge");
                String wasmBase64 = android.util.Base64.encodeToString(wasmBytes, android.util.Base64.NO_WRAP);
                String jsBase64 = android.util.Base64.encodeToString(adGuardJs.getBytes(StandardCharsets.UTF_8), android.util.Base64.NO_WRAP);
                String jsHtml = "<html><body><script type='module'>" +
                        "let moduleExports=null;" +
                        "const wasmBase64='" + wasmBase64 + "';" +
                        "const jsCodeStr=atob('" + jsBase64 + "');" +
                        "const blob=new Blob([jsCodeStr],{type:'application/javascript'});" +
                        "const url=URL.createObjectURL(blob);" +
                        "import(url).then(m=>{" +
                        "  moduleExports=m;" +
                        "  return m.default({module_or_path:'data:application/wasm;base64,'+wasmBase64});" +
                        "}).then(()=>{" +
                        "  const r=moduleExports._vc('" + token + "');" +
                        "  window.NtkWasmBridge.onVcResult(String(r));" +
                        "}).catch(e=>{" +
                        "  window.NtkWasmBridge.onVcResult('error:'+e);" +
                        "});" +
                        "</script></body></html>";
                webView.loadDataWithBaseURL(NTK_WEBTOON_URL, jsHtml, "text/html", "UTF-8", null);
                mainHandler.postDelayed(() -> {
                    try { webView.destroy(); } catch(Exception ignored) {}
                    latch.countDown();
                }, 5000);
            } catch(Exception ex) {
                Log.e(TAG, "ntk_wasm_webview_error=" + ex);
                latch.countDown();
            }
        });
        try {
            latch.await(6000, java.util.concurrent.TimeUnit.MILLISECONDS);
        } catch(Exception ignored) {}
        return result[0];
    }

    private static String extractNtkChallengeScope(String token) {
        if(token == null || token.length() == 0)
            return "";
        String[] parts = token.split("\\.");
        if(parts.length < 2)
            return "";
        try {
            // NTK uses a 2-part token: payload.signature (not standard JWT)
            String payload = parts.length == 2 ? parts[0] : parts[1];
            int padding = 4 - (payload.length() % 4);
            if(padding != 4) {
                StringBuilder padded = new StringBuilder(payload);
                for(int i = 0; i < padding; i++) padded.append('=');
                payload = padded.toString();
            }
            payload = payload.replace('-', '+').replace('_', '/');
            byte[] decoded = Base64.decode(payload, Base64.DEFAULT);
            JSONObject json = new JSONObject(new String(decoded, StandardCharsets.UTF_8));
            return json.optString("scope", "");
        } catch(Exception e) {
            return "";
        }
    }

    public boolean performNtkNativeAckBypass(String baseUrl, String path) {
        return performNtkNativeAckBypass(baseUrl, path, path);
    }

    public boolean performNtkNativeAckBypass(String baseUrl, String path, String refererPath) {
        return performNtkNativeAckBypass(baseUrl, path, refererPath, false);
    }

    public boolean performNtkNativeAckBypassIgnoringWebViewInFlight(String baseUrl, String path, String refererPath) {
        return performNtkNativeAckBypass(baseUrl, path, refererPath, true);
    }

    private boolean performNtkNativeAckBypass(String baseUrl, String path, String refererPath,
                                             boolean ignoreWebViewInFlight) {
        if(baseUrl == null || path == null || baseUrl.length() == 0 || path.length() == 0)
            return false;
        if(!NtkQuicFetcher.isAvailable()) {
            Log.d(TAG, "ntk_native_ack_skip_quic_unavailable path=" + path);
            return false;
        }
        String ackPath = ntkNativeAckScopePath(path);
        String ackRefererPath = refererPath != null && refererPath.length() > 0 ? refererPath : path;
        String cacheKey = baseUrl + ackPath;
        String flightKey = ntkNativeAckFlightKey(ackPath);
        boolean webViewInFlight = isNtkWebViewAckInFlight(baseUrl, ackPath);
        boolean nativeInFlight = isNtkAckInFlight(cacheKey, flightKey);
        if((webViewInFlight && !ignoreWebViewInFlight) || nativeInFlight) {
            Log.d(TAG, "ntk_native_ack_skip_existing_inflight path=" + ackPath
                    + ",base=" + baseUrl
                    + ",referer=" + ackRefererPath
                    + ",webview=" + webViewInFlight
                    + ",native=" + nativeInFlight
                    + ",ignoreWebView=" + ignoreWebViewInFlight);
            return false;
        }
        ExecutorService earlyExecutor = getOrCreateNtkQuicExecutor(baseUrl);
        HttpEngine earlyReadyEngine = getCachedNtkQuicEngine(baseUrl);
        FutureTask<HttpEngine> earlyEngineTask = earlyReadyEngine == null
                ? startNtkQuicEngineCreate(baseUrl, ackPath)
                : null;
        Long cached = NTK_ACK_CACHE.get(cacheKey);
        if(cached != null && System.currentTimeMillis() - cached < NTK_ACK_CACHE_TTL_MS) {
            if(hasNtkAdAckCookieForPath(path)) {
                if(Log.isLoggable(TAG, Log.DEBUG))
                    Log.d(TAG, "ntk_native_ack_cache_hit path=" + path);
                return true;
            }
            NTK_ACK_CACHE.remove(cacheKey);
            Log.d(TAG, "ntk_native_ack_cache_cookie_miss path=" + path);
        }
        syncCookiesFromWebView(baseUrl + ackPath, true);
        if(hasNtkAdAckCookieForPath(path)) {
            NTK_ACK_CACHE.put(cacheKey, System.currentTimeMillis());
            Log.d(TAG, "ntk_native_ack_synced_cookie_hit path=" + path);
            return true;
        }
        if(hasRecentNtkAckProofRequired(cacheKey, flightKey)) {
            Log.d(TAG, "ntk_native_ack_proof_required_cached path=" + path);
            return false;
        }
        if(hasRecentNtkAckChallengeHardBlock(cacheKey, flightKey)) {
            Log.d(TAG, "ntk_native_ack_hardblock_cached path=" + path);
            return false;
        }
        Object flightLock = ntkNativeAckLock(flightKey);
        synchronized (flightLock) {
            cached = NTK_ACK_CACHE.get(cacheKey);
            if(cached != null && System.currentTimeMillis() - cached < NTK_ACK_CACHE_TTL_MS
                    && hasNtkAdAckCookieForPath(path)) {
                Log.d(TAG, "ntk_native_ack_locked_cache_hit path=" + path);
                return true;
            }
            if(hasNtkAdAckCookieForPath(path)) {
                NTK_ACK_CACHE.put(cacheKey, System.currentTimeMillis());
                Log.d(TAG, "ntk_native_ack_locked_cookie_hit path=" + path);
                return true;
            }
            if(hasRecentNtkAckProofRequired(cacheKey, flightKey)) {
                Log.d(TAG, "ntk_native_ack_locked_proof_required_cached path=" + path);
                return false;
            }
            if(hasRecentNtkAckChallengeHardBlock(cacheKey, flightKey)) {
                Log.d(TAG, "ntk_native_ack_locked_hardblock_cached path=" + path);
                return false;
            }
            if(removeNtkAckCookies()) {
                Log.d(TAG, "ntk_native_ack_stale_cookie_removed path=" + path);
            }
            NTK_ACK_IN_FLIGHT.put(cacheKey, System.currentTimeMillis());
            NTK_ACK_IN_FLIGHT.put(flightKey, System.currentTimeMillis());
            try {
                return performNtkNativeAckBypassLocked(baseUrl, ackPath, cacheKey, ackRefererPath,
                        earlyExecutor, earlyReadyEngine, earlyEngineTask, null);
            } finally {
                NTK_ACK_IN_FLIGHT.remove(cacheKey);
                NTK_ACK_IN_FLIGHT.remove(flightKey);
            }
        }
    }

    private void waitForNtkAckPreflightBeforeRsc(String baseUrl, String path) throws InterruptedException {
        if(baseUrl == null || baseUrl.length() == 0 || path == null || path.length() == 0)
            return;
        String ackPath = ntkNativeAckScopePath(path);
        String cacheKey = baseUrl + ackPath;
        if(hasNtkAdAckCookieForPath(ackPath))
            return;
        String flightKey = ntkNativeAckFlightKey(ackPath);
        if(hasRecentNtkAckChallengeHardBlock(cacheKey, flightKey))
            return;
        if(hasRecentNtkAckChallengeOk(cacheKey, flightKey))
            return;
        if(!isNtkAckChallengeInFlight(cacheKey, flightKey))
            return;
        long waitStartedAt = System.currentTimeMillis();
        Long challengeStartedAt = ntkAckChallengeStartedAt(cacheKey, flightKey);
        long deadline = (challengeStartedAt == null ? waitStartedAt : challengeStartedAt)
                + NTK_RSC_ACK_CHALLENGE_WAIT_MS;
        long earlyReleaseDeadline = waitStartedAt + NTK_RSC_ACK_CHALLENGE_EARLY_RELEASE_MS;
        while(true) {
            if(hasNtkAdAckCookieForPath(ackPath)
                    || hasRecentNtkAckChallengeOk(cacheKey, flightKey)
                    || hasRecentNtkAckChallengeHardBlock(cacheKey, flightKey)
                    || !isNtkAckChallengeInFlight(cacheKey, flightKey))
                break;
            if(System.currentTimeMillis() >= earlyReleaseDeadline) {
                Log.d(TAG, "ntk_rsc_ack_challenge_parallel_release path=" + path
                        + ",ms=" + (System.currentTimeMillis() - waitStartedAt));
                break;
            }
            if(System.currentTimeMillis() >= deadline) {
                Log.d(TAG, "ntk_rsc_ack_challenge_defer path=" + path
                        + ",ms=" + (System.currentTimeMillis() - waitStartedAt));
                deadline = System.currentTimeMillis() + 200L;
            }
            Thread.sleep(25L);
        }
        Log.d(TAG, "ntk_rsc_ack_challenge_wait path=" + path
                + ",cookie=" + hasNtkAdAckCookieForPath(ackPath)
                + ",challengeOk=" + hasRecentNtkAckChallengeOk(cacheKey, flightKey)
                + ",hardblock=" + hasRecentNtkAckChallengeHardBlock(cacheKey, flightKey)
                + ",challengeInFlight=" + isNtkAckChallengeInFlight(cacheKey, flightKey)
                + ",ackInFlight=" + isNtkAckInFlight(cacheKey, flightKey)
                + ",ms=" + (System.currentTimeMillis() - waitStartedAt));
    }

    private void startNtkNativeAckChallengePrepare(String baseUrl, String path) {
        if(baseUrl == null || path == null || baseUrl.length() == 0 || path.length() == 0)
            return;
        if(!NtkQuicFetcher.isAvailable()) {
            Log.d(TAG, "ntk_native_ack_prepare_skip_quic_unavailable path=" + path);
            return;
        }
        String ackPath = ntkNativeAckScopePath(path);
        String cacheKey = baseUrl + ackPath;
        String flightKey = ntkNativeAckFlightKey(ackPath);
        boolean modernGuardRoot = isModernNtkGuardRoot(baseUrl);
        boolean recentAckReady = modernGuardRoot
                ? NtkWebViewFallbackManager.hasRecentStrictAdAckSuccess(ackPath)
                : hasNtkAdAckCookieForPath(ackPath)
                || NtkWebViewFallbackManager.hasRecentServerAckSuccess(ackPath)
                || hasRecentNtkAckChallengeOk(cacheKey, flightKey);
        if(recentAckReady) {
            Log.d(TAG, "ntk_native_ack_prepare_skip_recent path=" + ackPath);
            return;
        }
        long now = System.currentTimeMillis();
        Long existingCache = NTK_ACK_CHALLENGE_IN_FLIGHT.putIfAbsent(cacheKey, now);
        if(existingCache != null) {
            Log.d(TAG, "ntk_native_ack_prepare_skip_inflight path=" + ackPath
                    + ",key=cache,ageMs=" + (now - existingCache));
            return;
        }
        Long existingFlight = NTK_ACK_CHALLENGE_IN_FLIGHT.putIfAbsent(flightKey, now);
        if(existingFlight != null) {
            NTK_ACK_CHALLENGE_IN_FLIGHT.remove(cacheKey);
            Log.d(TAG, "ntk_native_ack_prepare_skip_inflight path=" + ackPath
                    + ",key=flight,ageMs=" + (now - existingFlight));
            return;
        }
        NTK_ACK_CHALLENGE_CALLBACK_EXECUTOR.execute(() -> {
            try {
                prepareNtkNativeAckChallenge(baseUrl, ackPath);
            } finally {
                NTK_ACK_CHALLENGE_IN_FLIGHT.remove(cacheKey);
                NTK_ACK_CHALLENGE_IN_FLIGHT.remove(flightKey);
            }
        });
    }

    private void prepareNtkNativeAckChallenge(String baseUrl, String path) {
        long startedMs = System.currentTimeMillis();
        try {
            if(!NtkQuicFetcher.isAvailable()) {
                Log.d(TAG, "ntk_native_ack_prepare_skip_quic_unavailable path=" + path);
                return;
            }
            if(Thread.interrupted())
                Log.d(TAG, "ntk_native_ack_prepare_cleared_stale_interrupt path=" + path);
            syncCookiesFromWebView(baseUrl, true);
            syncCookiesFromWebView(baseUrl + path, true);
            ensureNtkNvCookieForAck(baseUrl, path, "prepare");
            if(!hasNtkAckGuardBootstrapForNativeChallenge(path)) {
                Log.d(TAG, "ntk_native_ack_prepare_skip_missing_guard_bootstrap path=" + path
                        + ",adGuardL=" + (getCookie("ad_guard_l") != null)
                        + ",adAckC=" + (getCookie("ad_ack_c") != null)
                        + ",cfClearance=" + hasCloudflareClearance()
                        + ",nv=" + isNtkNvValid(getCookie("nv"))
                        + ",ms=" + (System.currentTimeMillis() - startedMs));
                return;
            }
            ExecutorService executor = getOrCreateNtkQuicExecutor(baseUrl);
            if(executor == null)
                return;
            HttpEngine readyEngine = getCachedNtkQuicEngine(baseUrl);
            FutureTask<HttpEngine> engineTask = readyEngine == null ? startNtkQuicEngineCreate(baseUrl, path) : null;
            Map<String, String> h = new HashMap<>();
            h.put("origin", baseUrl);
            h.put("referer", baseUrl + ntkNativeAckScopePath(path));
            h.put("accept", "application/json");
            h.put("content-type", "application/json");
            JSONObject challengePayload = new JSONObject();
            challengePayload.put("path", path);
            byte[] challengeBytes = challengePayload.toString().getBytes(StandardCharsets.UTF_8);
            NtkQuicFetcher.Result challenge = null;
            boolean staleResetRetried = false;
            for(int attempt = 0; attempt < 3; attempt++) {
                if(attempt > 0) {
                    Thread.sleep(attempt == 1 ? 700L : 1200L);
                    syncCookiesFromWebView(baseUrl, true);
                    syncCookiesFromWebView(baseUrl + path, true);
                    readyEngine = getCachedNtkQuicEngine(baseUrl);
                    if(readyEngine == null && engineTask == null)
                        engineTask = startNtkQuicEngineCreate(baseUrl, path);
                }
                challenge = readyEngine != null
                        ? fetchNtkAckChallengeRace(readyEngine, executor, baseUrl, path, h, challengeBytes, attempt)
                        : fetchNtkAckChallengeRace(engineTask, executor, baseUrl, path, h, challengeBytes, attempt);
                Log.d(TAG, "ntk_native_ack_prepare_challenge_code=" + (challenge == null ? "null" : challenge.code)
                        + ",attempt=" + attempt
                        + ",path=" + path
                        + ",body=" + ntkAckShortBody(challenge == null ? null : challenge.body)
                        + ",ms=" + (System.currentTimeMillis() - startedMs));
                if(challenge != null && challenge.error == null && challenge.code == 200
                        && challenge.body != null && looksLikeJsonObject(challenge.body))
                    break;
                if(isNtkAckHardBlocked(challenge)) {
                    boolean reset = handleNtkAckHardBlock(baseUrl, path);
                    if(reset && !staleResetRetried) {
                        staleResetRetried = true;
                        syncCookiesFromWebView(baseUrl, true);
                        syncCookiesFromWebView(baseUrl + path, true);
                        continue;
                    }
                    break;
                }
                if(Thread.currentThread().isInterrupted())
                    throw new InterruptedException();
            }
            if(challenge == null || challenge.error != null || challenge.code != 200
                    || challenge.body == null || !looksLikeJsonObject(challenge.body))
                return;
            if(ntkChallengeIssuedAdAckCookie(challenge)) {
                rememberNtkNativeAckChallengeBody(path, challenge.body,
                        "prepare-challenge-cookie");
                NtkWebViewFallbackManager.rememberExternalServerAckSuccess(
                        path, "native-prepare-challenge-ad-ack-cookie-200");
                if(!isModernNtkGuardRoot(baseUrl))
                    markNtkAckChallengeOk(baseUrl + ntkNativeAckScopePath(path),
                            ntkNativeAckFlightKey(path));
                Log.d(TAG, "ntk_native_ack_prepare_challenge_cookie_success path=" + path
                        + ",ms=" + (System.currentTimeMillis() - startedMs));
                return;
            }
            JSONObject challengeJson = new JSONObject(challenge.body);
            if(!challengeJson.optBoolean("ok", false))
                return;
            JSONObject challengeObj = challengeJson.optJSONObject("challenge");
            if(challengeObj == null)
                return;
            String scope = extractNtkChallengeScope(challengeObj.optString("token", ""));
            String challengePath = scope.length() > 0 ? scope : path;
            NtkWebViewFallbackManager.rememberNativeAckChallenge(challengePath, challenge.body);
            markNtkAckChallengeOk(baseUrl + ntkNativeAckScopePath(challengePath),
                    ntkNativeAckFlightKey(challengePath));
        } catch(Exception e) {
            if(isInterruptedRequest(e)) {
                Log.d(TAG, "ntk_native_ack_prepare_cancelled path=" + path
                        + ",ms=" + (System.currentTimeMillis() - startedMs));
            } else {
                Log.d(TAG, "ntk_native_ack_prepare_exception=" + e
                        + ",path=" + path
                        + ",ms=" + (System.currentTimeMillis() - startedMs));
            }
        }
    }

    private static void rememberNtkNativeAckChallengeBody(String fallbackPath, String body,
                                                          String source) {
        if(body == null || body.length() == 0 || !looksLikeJsonObject(body))
            return;
        try {
            JSONObject challengeJson = new JSONObject(body);
            if(!challengeJson.optBoolean("ok", false))
                return;
            JSONObject challengeObj = challengeJson.optJSONObject("challenge");
            if(challengeObj == null)
                return;
            String scope = extractNtkChallengeScope(challengeObj.optString("token", ""));
            String challengePath = scope.length() > 0 ? scope : ntkNativeAckScopePath(fallbackPath);
            if(challengePath.length() == 0)
                return;
            NtkWebViewFallbackManager.rememberNativeAckChallenge(challengePath, body);
            Log.d(TAG, "ntk_native_ack_challenge_body_remembered path=" + challengePath
                    + ",source=" + source);
        } catch(Exception ignored) {
        }
    }

    private static String ntkAckShortBody(String body) {
        if(body == null || body.length() == 0)
            return "";
        String compact = body.replace('\n', ' ').replace('\r', ' ');
        return compact.substring(0, Math.min(80, compact.length()));
    }

    NtkQuicFetcher.Result submitNtkAdAckFromBridge(String url, Map<String, String> headers,
                                                   byte[] body) {
        return submitNtkAdAckFromBridge(url, headers, body, null);
    }

    NtkQuicFetcher.Result submitNtkAdAckFromBridge(String url, Map<String, String> headers,
                                                   byte[] body, String bridgeCookieHeader) {
        long startedMs = System.currentTimeMillis();
        try {
            if(!NtkQuicFetcher.isAvailable()) {
                Log.d(TAG, "ntk_native_ack_bridge_submit_skip_quic_unavailable");
                return null;
            }
            HttpUrl parsed = HttpUrl.parse(url);
            if(parsed == null || body == null || body.length == 0)
                return null;
            String baseUrl = parsed.scheme() + "://" + parsed.host();
            JSONObject request = new JSONObject(new String(body, StandardCharsets.UTF_8));
            String path = ntkNativeAckScopePath(request.optString("path", parsed.encodedPath()));
            syncCookiesFromWebView(baseUrl, true);
            syncCookiesFromWebView(baseUrl + path, true);
            ExecutorService executor = getOrCreateNtkQuicExecutor(baseUrl);
            if(executor == null)
                return null;
            HttpEngine readyEngine = getCachedNtkQuicEngine(baseUrl);
            FutureTask<HttpEngine> engineTask = readyEngine == null
                    ? startNtkQuicEngineCreate(baseUrl, path)
                    : null;
            HttpEngine engine = readyEngine != null ? readyEngine : awaitNtkQuicEngine(engineTask, 220L, path);
            Map<String, String> h = headers == null ? new HashMap<>() : new HashMap<>(headers);
            if(headerValue(h, "origin") == null)
                h.put("origin", baseUrl);
            if(headerValue(h, "referer") == null)
                h.put("referer", baseUrl + path);
            if(headerValue(h, "accept") == null)
                h.put("accept", "application/json");
            if(headerValue(h, "content-type") == null)
                h.put("content-type", "application/json");
            String effectiveCookieHeader = bridgeCookieHeader != null && bridgeCookieHeader.length() > 0
                    ? bridgeCookieHeader : getCookieHeader();
            long ackSubmitTimeoutMs = Math.max(NTK_ACK_CONFIRM_TIMEOUT_MS, 4_500L);
            NtkQuicFetcher.Result result = engine != null
                    ? NtkQuicFetcher.fetchWithEngine(engine, executor, baseUrl + "/api/ad/ack",
                            agent, effectiveCookieHeader, h, "POST", body, ackSubmitTimeoutMs)
                    : NtkQuicFetcher.fetch(context, baseUrl + "/api/ad/ack",
                            agent, effectiveCookieHeader, h, "POST", body, ackSubmitTimeoutMs);
            if(result != null)
                applySetCookieHeaders(result.headers, baseUrl);
            Log.d(TAG, "ntk_native_ack_bridge_submit code=" + (result == null ? "null" : result.code)
                    + ",path=" + path
                    + ",tpLen=" + request.optString("tp", "").length()
                    + ",apLen=" + request.optString("ap", "").length()
                    + ",bridgeCookies=" + (bridgeCookieHeader != null && bridgeCookieHeader.length() > 0)
                    + ",ms=" + (System.currentTimeMillis() - startedMs));
            return result;
        } catch(Exception e) {
            Log.d(TAG, "ntk_native_ack_bridge_submit_error=" + e
                    + ",ms=" + (System.currentTimeMillis() - startedMs));
            return null;
        }
    }

    private boolean performNtkNativeAckBypassLocked(String baseUrl, String path, String cacheKey) {
        return performNtkNativeAckBypassLocked(baseUrl, path, cacheKey, path,
                null, null, null, null);
    }

    private boolean waitForNtkAckCookieFromExistingFlight(String cookiePath, String cacheKey,
                                                         String flightKey, long timeoutMs)
            throws InterruptedException {
        long startedAt = System.currentTimeMillis();
        long deadline = startedAt + Math.max(0L, timeoutMs);
        while(System.currentTimeMillis() < deadline) {
            if(hasNtkAdAckCookieForPath(cookiePath)) {
                Log.d(TAG, "ntk_native_ack_join_cookie_hit path=" + cookiePath
                        + ",ms=" + (System.currentTimeMillis() - startedAt));
                return true;
            }
            if(!isNtkAckInFlight(cacheKey, flightKey))
                break;
            Thread.sleep(20L);
        }
        boolean cookie = hasNtkAdAckCookieForPath(cookiePath);
        Log.d(TAG, "ntk_native_ack_join_done path=" + cookiePath
                + ",cookie=" + cookie
                + ",ackInFlight=" + isNtkAckInFlight(cacheKey, flightKey)
                + ",ms=" + (System.currentTimeMillis() - startedAt));
        return cookie;
    }

    private static boolean isNtkAckInFlight(String cacheKey, String flightKey) {
        return (cacheKey != null && NTK_ACK_IN_FLIGHT.containsKey(cacheKey))
                || (flightKey != null && NTK_ACK_IN_FLIGHT.containsKey(flightKey));
    }

    private static boolean isNtkAckChallengeInFlight(String cacheKey, String flightKey) {
        return (cacheKey != null && NTK_ACK_CHALLENGE_IN_FLIGHT.containsKey(cacheKey))
                || (flightKey != null && NTK_ACK_CHALLENGE_IN_FLIGHT.containsKey(flightKey));
    }

    private static Long ntkAckChallengeStartedAt(String cacheKey, String flightKey) {
        Long startedAt = cacheKey == null ? null : NTK_ACK_CHALLENGE_IN_FLIGHT.get(cacheKey);
        return startedAt != null || flightKey == null ? startedAt : NTK_ACK_CHALLENGE_IN_FLIGHT.get(flightKey);
    }

    private static void markNtkAckChallengeOk(String cacheKey, String flightKey) {
        long now = System.currentTimeMillis();
        if(cacheKey != null && cacheKey.length() > 0) {
            NTK_ACK_CHALLENGE_HARDBLOCKS.remove(cacheKey);
            NTK_ACK_CHALLENGE_OKS.put(cacheKey, now);
        }
        if(flightKey != null && flightKey.length() > 0) {
            NTK_ACK_CHALLENGE_HARDBLOCKS.remove(flightKey);
            NTK_ACK_CHALLENGE_OKS.put(flightKey, now);
        }
    }

    private static boolean hasRecentNtkAckChallengeOk(String cacheKey, String flightKey) {
        long now = System.currentTimeMillis();
        Long cacheAt = cacheKey == null ? null : NTK_ACK_CHALLENGE_OKS.get(cacheKey);
        Long flightAt = flightKey == null ? null : NTK_ACK_CHALLENGE_OKS.get(flightKey);
        boolean cacheRecent = cacheAt != null && now - cacheAt <= NTK_ACK_CHALLENGE_RESULT_TTL_MS;
        boolean flightRecent = flightAt != null && now - flightAt <= NTK_ACK_CHALLENGE_RESULT_TTL_MS;
        if(cacheAt != null && !cacheRecent)
            NTK_ACK_CHALLENGE_OKS.remove(cacheKey);
        if(flightAt != null && !flightRecent)
            NTK_ACK_CHALLENGE_OKS.remove(flightKey);
        return cacheRecent || flightRecent;
    }

    private static void markNtkAckChallengeHardBlock(String cacheKey, String flightKey) {
        long now = System.currentTimeMillis();
        if(cacheKey != null && cacheKey.length() > 0)
            NTK_ACK_CHALLENGE_HARDBLOCKS.put(cacheKey, now);
        if(flightKey != null && flightKey.length() > 0)
            NTK_ACK_CHALLENGE_HARDBLOCKS.put(flightKey, now);
    }

    private static void clearNtkAckChallengeHardBlock(String cacheKey, String flightKey) {
        if(cacheKey != null && cacheKey.length() > 0)
            NTK_ACK_CHALLENGE_HARDBLOCKS.remove(cacheKey);
        if(flightKey != null && flightKey.length() > 0)
            NTK_ACK_CHALLENGE_HARDBLOCKS.remove(flightKey);
    }

    private static boolean hasRecentNtkAckChallengeHardBlock(String cacheKey, String flightKey) {
        long now = System.currentTimeMillis();
        Long cacheAt = cacheKey == null ? null : NTK_ACK_CHALLENGE_HARDBLOCKS.get(cacheKey);
        Long flightAt = flightKey == null ? null : NTK_ACK_CHALLENGE_HARDBLOCKS.get(flightKey);
        Long cacheOkAt = cacheKey == null ? null : NTK_ACK_CHALLENGE_OKS.get(cacheKey);
        Long flightOkAt = flightKey == null ? null : NTK_ACK_CHALLENGE_OKS.get(flightKey);
        if(cacheAt != null && cacheOkAt != null && cacheOkAt >= cacheAt) {
            NTK_ACK_CHALLENGE_HARDBLOCKS.remove(cacheKey);
            cacheAt = null;
        }
        if(flightAt != null && flightOkAt != null && flightOkAt >= flightAt) {
            NTK_ACK_CHALLENGE_HARDBLOCKS.remove(flightKey);
            flightAt = null;
        }
        boolean cacheRecent = cacheAt != null && now - cacheAt <= NTK_ACK_CHALLENGE_RESULT_TTL_MS;
        boolean flightRecent = flightAt != null && now - flightAt <= NTK_ACK_CHALLENGE_RESULT_TTL_MS;
        if(cacheAt != null && !cacheRecent)
            NTK_ACK_CHALLENGE_HARDBLOCKS.remove(cacheKey);
        if(flightAt != null && !flightRecent)
            NTK_ACK_CHALLENGE_HARDBLOCKS.remove(flightKey);
        return cacheRecent || flightRecent;
    }

    private static void markNtkAckProofRequired(String cacheKey, String flightKey) {
        long now = System.currentTimeMillis();
        if(cacheKey != null && cacheKey.length() > 0)
            NTK_ACK_PROOF_REQUIREDS.put(cacheKey, now);
        if(flightKey != null && flightKey.length() > 0)
            NTK_ACK_PROOF_REQUIREDS.put(flightKey, now);
    }

    private static boolean hasRecentNtkAckProofRequired(String cacheKey, String flightKey) {
        long now = System.currentTimeMillis();
        Long cacheAt = cacheKey == null ? null : NTK_ACK_PROOF_REQUIREDS.get(cacheKey);
        Long flightAt = flightKey == null ? null : NTK_ACK_PROOF_REQUIREDS.get(flightKey);
        boolean cacheRecent = cacheAt != null && now - cacheAt <= NTK_ACK_PROOF_REQUIRED_TTL_MS;
        boolean flightRecent = flightAt != null && now - flightAt <= NTK_ACK_PROOF_REQUIRED_TTL_MS;
        if(cacheAt != null && !cacheRecent)
            NTK_ACK_PROOF_REQUIREDS.remove(cacheKey);
        if(flightAt != null && !flightRecent)
            NTK_ACK_PROOF_REQUIREDS.remove(flightKey);
        return cacheRecent || flightRecent;
    }

    private static boolean hasRecentNtkAckChallengeHardBlockForPath(String baseUrl, String path) {
        if(baseUrl == null || baseUrl.length() == 0 || path == null || path.length() == 0)
            return false;
        String ackPath = ntkNativeAckScopePath(path);
        return hasRecentNtkAckChallengeHardBlock(baseUrl + ackPath, ntkNativeAckFlightKey(ackPath));
    }

    private boolean performNtkNativeAckBypassLocked(String baseUrl, String path, String cacheKey, String refererPath) {
        return performNtkNativeAckBypassLocked(baseUrl, path, cacheKey, refererPath,
                null, null, null, null);
    }

    private boolean performNtkNativeAckBypassLocked(String baseUrl, String path, String cacheKey,
                                                    String refererPath, ExecutorService earlyExecutor,
                                                    HttpEngine earlyReadyEngine,
                                                    FutureTask<HttpEngine> earlyEngineTask,
                                                    String preparedChallengeBody) {
        long startedMs = System.currentTimeMillis();
        long phaseStartedMs = startedMs;
        try {
            if(Thread.interrupted()) {
                Log.d(TAG, "ntk_native_ack_cleared_stale_interrupt path=" + path);
            }
            if((preparedChallengeBody == null || preparedChallengeBody.length() == 0)
                    && !hasNtkAckGuardBootstrapForNativeChallenge(path)) {
                Log.d(TAG, "ntk_native_ack_skip_missing_guard_bootstrap path=" + path
                        + ",cf=" + hasCloudflareClearance()
                        + ",adGuardL=" + (getCookie("ad_guard_l") != null)
                        + ",adAckC=" + (getCookie("ad_ack_c") != null)
                        + ",cfClearance=" + hasCloudflareClearance()
                        + ",nv=" + isNtkNvValid(getCookie("nv"))
                        + ",ms=" + (System.currentTimeMillis() - startedMs));
                return false;
            }
            ExecutorService executor = earlyExecutor != null ? earlyExecutor : getOrCreateNtkQuicExecutor(baseUrl);
            if(executor == null)
                return false;
            ensureNtkNvCookieForAck(baseUrl, path, "perform");
            HttpEngine readyEngine = earlyReadyEngine != null ? earlyReadyEngine : getCachedNtkQuicEngine(baseUrl);
            FutureTask<HttpEngine> engineTask = readyEngine == null
                    ? (earlyEngineTask != null ? earlyEngineTask : startNtkQuicEngineCreate(baseUrl, path))
                    : null;
            if(readyEngine != null)
                Log.d(TAG, "ntk_native_ack_engine_cached path=" + path);
            Map<String, String> h = new HashMap<>();
            h.put("origin", baseUrl);
            h.put("referer", baseUrl + ntkNativeAckScopePath(refererPath));
            h.put("accept", "application/json");
            h.put("content-type", "application/json");

            if(DUMP_NTK_ACK_DEBUG_ARTIFACTS) {
                // Debug-only artifact dumps are expensive and must not run during normal image loading.
                dumpNtkAckDebugArtifacts(baseUrl, path);
            }

            Log.d(TAG, "ntk_wasm_fetch_skipped_ack_fast path=" + path);
            phaseStartedMs = logNtkNativeAckPhase("wasm", path, startedMs, phaseStartedMs);

            // 1. POST /api/ad/challenge
            JSONObject challengePayload = new JSONObject();
            challengePayload.put("path", path);
            NtkQuicFetcher.Result challenge = null;
            byte[] challengeBytes = challengePayload.toString().getBytes(StandardCharsets.UTF_8);
            boolean retryChallengeImmediately = false;
            boolean staleResetRetried = false;
            String flightKey = ntkNativeAckFlightKey(path);
            if(preparedChallengeBody != null && preparedChallengeBody.length() > 0
                    && looksLikeJsonObject(preparedChallengeBody)) {
                challenge = NtkQuicFetcher.Result.fromBytes(200,
                        preparedChallengeBody.getBytes(StandardCharsets.UTF_8),
                        Collections.singletonMap("content-type",
                                Collections.singletonList("application/json")));
                Log.d(TAG, "ntk_native_ack_prepared_challenge_reuse bytes="
                        + preparedChallengeBody.length()
                        + ",path=" + path);
            } else {
                NTK_ACK_CHALLENGE_IN_FLIGHT.put(cacheKey, System.currentTimeMillis());
                NTK_ACK_CHALLENGE_IN_FLIGHT.put(flightKey, System.currentTimeMillis());
                try {
                    for(int attempt = 0; attempt < NTK_ACK_REQUEST_ATTEMPTS; attempt++) {
                        if(attempt > 0 && !retryChallengeImmediately)
                            Thread.sleep(NTK_ACK_RETRY_DELAY_MS * attempt);
                        retryChallengeImmediately = false;
                        challenge = readyEngine != null
                                ? fetchNtkAckChallengeRace(readyEngine, executor, baseUrl, path, h, challengeBytes, attempt)
                                : fetchNtkAckChallengeRace(engineTask, executor, baseUrl, path, h, challengeBytes, attempt);
                        Log.d(TAG, "ntk_native_ack_challenge_code=" + (challenge == null ? "null" : challenge.code)
                                + ",attempt=" + attempt
                                + ",path=" + path);
                        if(challenge != null && challenge.error == null && challenge.code == 200
                                && looksLikeJsonObject(challenge.body))
                            break;
                        if(isNtkAckHardBlocked(challenge)) {
                            boolean reset = handleNtkAckHardBlock(baseUrl, path);
                            if(reset && !staleResetRetried) {
                                staleResetRetried = true;
                                retryChallengeImmediately = true;
                                syncCookiesFromWebView(baseUrl, true);
                                syncCookiesFromWebView(baseUrl + path, true);
                                continue;
                            }
                            break;
                        }
                        retryChallengeImmediately = challenge == null;
                    }
                } finally {
                    NTK_ACK_CHALLENGE_IN_FLIGHT.remove(cacheKey);
                    NTK_ACK_CHALLENGE_IN_FLIGHT.remove(flightKey);
                }
            }
            if(challenge == null || challenge.error != null || challenge.code != 200 || challenge.body == null)
                return false;
            if(ntkChallengeIssuedAdAckCookie(challenge)) {
                rememberNtkNativeAckChallengeBody(path, challenge.body,
                        "native-challenge-cookie");
                NTK_ACK_CACHE.put(cacheKey, System.currentTimeMillis());
                NtkWebViewFallbackManager.rememberExternalServerAckSuccess(
                        path, "native-challenge-ad-ack-cookie-200");
                Log.d(TAG, "ntk_native_ack_challenge_cookie_success path=" + path
                        + ",totalMs=" + (System.currentTimeMillis() - startedMs));
                return true;
            }
            if(!looksLikeJsonObject(challenge.body)) {
                Log.d(TAG, "ntk_native_ack_challenge_non_json path=" + path
                        + ",body=" + challenge.body.substring(0, Math.min(120, challenge.body.length())));
                return false;
            }
            HttpEngine engine = readyEngine != null ? readyEngine : awaitNtkQuicEngine(engineTask, 80L, path);
            phaseStartedMs = logNtkNativeAckPhase("engine", path, startedMs, phaseStartedMs);
            JSONObject challengeJson = new JSONObject(challenge.body);
            if(!challengeJson.optBoolean("ok", false))
                return false;
            JSONObject challengeObj = challengeJson.optJSONObject("challenge");
            if(challengeObj == null) {
                if(challengeJson.optBoolean("trusted", false) && hasNtkAdAckCookieForPath(path)) {
                    NTK_ACK_CACHE.put(cacheKey, System.currentTimeMillis());
                    Log.d(TAG, "ntk_native_ack_trusted_success path=" + path
                            + ",totalMs=" + (System.currentTimeMillis() - startedMs));
                    return true;
                }
                if(challengeJson.optBoolean("trusted", false)) {
                    Log.d(TAG, "ntk_native_ack_trusted_without_cookie path=" + path);
                }
                return false;
            }
            phaseStartedMs = logNtkNativeAckPhase("challenge", path, startedMs, phaseStartedMs);
            String token = challengeObj.optString("token", "");
            JSONArray impressionUrls = challengeObj.optJSONArray("impressionUrls");
            Log.d(TAG, "ntk_native_ack_challenge_token_len=" + token.length()
                    + ",impressions=" + (impressionUrls == null ? 0 : impressionUrls.length())
                    + ",path=" + path);
            String scope = extractNtkChallengeScope(token);
            String challengePath = scope.length() > 0 ? scope : path;
            Log.d(TAG, "ntk_native_ack_scope=" + scope + ",challengePath=" + challengePath);
            NtkWebViewFallbackManager.rememberNativeAckChallenge(challengePath, challenge.body);
            String cookiesAfterChallenge = getCookieHeader();
            String nvAfterChallenge = getCookie("nv");
            Log.d(TAG, "ntk_native_ack_cookies_after_challenge nv=" + (nvAfterChallenge == null ? "null" : nvAfterChallenge.substring(0, Math.min(60, nvAfterChallenge.length()))) + " cookies_len=" + (cookiesAfterChallenge == null ? 0 : cookiesAfterChallenge.length()));
            if(hasNtkAdAckCookieForPath(challengePath)) {
                String challengeAdAckC = getCookie("ad_ack_c");
                if(ntkAckCookieUsableForPath("ad_ack_c", challengeAdAckC, challengePath))
                    NtkWebViewFallbackManager.rememberScopedAdAckC(
                            challengePath, challengeAdAckC, "native-challenge");
                NtkWebViewFallbackManager.rememberExternalServerAckSuccess(
                        challengePath, "native-challenge-ad-ack-cookie-200");
            }
            Map<String, String> h2 = new HashMap<>(h);
            h2.put("referer", baseUrl + ntkNativeAckScopePath(challengePath));

            // Numeric and slug routes currently accept the challenge token directly; avoid WebView/WASM overhead.
            Log.d(TAG, "ntk_native_ack_vc_skipped token_len=" + token.length() + ",path=" + challengePath);

            Log.d(TAG, "ntk_native_ack_token_before_ack len=" + token.length() + ",path=" + challengePath);
            int slotCount = challengeObj.optInt("slotCount", 4);
            int minSeen = challengeObj.optInt("minSeen", 2);

            JSONObject ackPayload = new JSONObject();
            ackPayload.put("challengeToken", token);
            int observedSlots = Math.max(slotCount, 28);
            ackPayload.put("total", observedSlots);
            ackPayload.put("visible", observedSlots);
            ackPayload.put("path", challengePath);
            ackPayload.put("td", 0);
            ackPayload.put("tp", "");
            byte[] ackBytes = ackPayload.toString().getBytes(StandardCharsets.UTF_8);

            byte[] canaryBytes = ntkAckCanaryPayload(token, challengePath);
            Future<NtkAckCanaryResult> proactiveCanary = startProactiveNtkAckCanary(
                    executor, engine, baseUrl, challengePath, h2, canaryBytes, impressionUrls);

            // 2. Fire impression URLs and wait briefly for the minimum visible slot. Submitting
            // /api/ad/ack too early intermittently returns missing_impression, which is slower
            // than this bounded wait because it consumes an extra ACK round trip.
            if(impressionUrls != null && impressionUrls.length() > 0) {
                int seen = fetchNtkAckImpressions(
                        engine, executor, baseUrl, challengePath, impressionUrls, minSeen);
                if(seen <= 0) {
                    Log.d(TAG, "ntk_native_ack_skip_ack_without_impression path=" + challengePath
                            + ",minSeen=" + minSeen);
                    return false;
                }
            }
            phaseStartedMs = logNtkNativeAckPhase("impressions", challengePath, startedMs, phaseStartedMs);
            NtkAckCanaryResult proactiveCanaryResult = null;
            if(proactiveCanary != null) {
                Log.d(TAG, "ntk_native_ack_canary_proactive_fire_and_forget path=" + challengePath);
                proactiveCanaryResult = collectProactiveNtkAckCanary(proactiveCanary, baseUrl, challengePath);
            }

            // 4. POST /api/ad/ack with challenge token
            // WebView sends additional metrics: total, visible, td, tp
            // tp is a proof computed by ad_guard.js. Submitting the native empty proof
            // consumes current server challenges and makes the later WebView proof ACK hit
            // challenge_used, so native stops here and lets the proof-capable path finish.
            if(ackPayload.optString("tp", "").length() == 0) {
                markNtkAckProofRequired(cacheKey, ntkNativeAckFlightKey(challengePath));
                Log.d(TAG, "ntk_native_ack_ack_skipped_without_proof path=" + challengePath
                        + ",canaryOk=" + (proactiveCanaryResult != null && proactiveCanaryResult.ok));
                return false;
            }
            boolean ackBodyOk = false;
            String ackStatus = null;
            String ackError = null;
            NtkQuicFetcher.Result ack = null;
            boolean ackCookieSalvaged = false;
            boolean canaryFallbackAttempted = false;
            boolean skipNextAckDelay = false;
            for(int attempt = 0; attempt < 3; attempt++) {
                if(ack != null && ack.code == 200 && ackBodyOk)
                    break;
                if(attempt > 0 && !skipNextAckDelay)
                    Thread.sleep(NTK_ACK_RETRY_DELAY_MS * attempt);
                skipNextAckDelay = false;
                ack = engine != null
                        ? fetchNtkAckControlPost(engine, executor, baseUrl, "/api/ad/ack", h2, ackBytes)
                        : fetchNtkAckControlPost(baseUrl, "/api/ad/ack", h2, ackBytes);
                Log.d(TAG, "ntk_native_ack_ack_code=" + (ack == null ? "null" : ack.code)
                        + ",attempt=" + attempt
                        + ",path=" + challengePath);
                if(ack != null) applySetCookieHeaders(ack.headers, baseUrl);
                NtkAckParsed parsed = parseNtkAckResponse(ack, challengePath);
                ackBodyOk = parsed.ok;
                ackStatus = parsed.status;
                ackError = parsed.error;
                if(!ackBodyOk && "challenge_used".equals(ackError)
                        && hasNtkAdAckCookieForPath(challengePath)) {
                    ackBodyOk = true;
                    ackCookieSalvaged = true;
                    Log.d(TAG, "ntk_native_ack_ack_challenge_used_cookie_success path=" + challengePath);
                }
                if(ack != null && (ack.code == 200 || ackCookieSalvaged) && ackBodyOk)
                    break;
                if("challenge_used".equals(ackError)) {
                    Log.d(TAG, "ntk_native_ack_ack_challenge_used_stop path=" + challengePath);
                    break;
                }
                if("ad_proof_required".equals(ackError)) {
                    markNtkAckProofRequired(cacheKey, ntkNativeAckFlightKey(challengePath));
                    ReaderImageCache.releaseNtkAckRecoveryAfterAckProofFailure(challengePath);
                    Log.d(TAG, "ntk_native_ack_ack_proof_required_stop path=" + challengePath);
                    break;
                }
                if(!canaryFallbackAttempted) {
                    canaryFallbackAttempted = true;
                    NtkAckCanaryResult canaryResult =
                            performNtkAckCanary(engine, executor, baseUrl, challengePath, h2, canaryBytes);
                    if(canaryResult.canary != null)
                        applySetCookieHeaders(canaryResult.canary.headers, baseUrl);
                    Log.d(TAG, "ntk_native_ack_canary_fallback ok=" + canaryResult.ok
                            + ",path=" + challengePath);
                    if(canaryResult.ok)
                        skipNextAckDelay = true;
                    if(!canaryResult.ok && isNtkAckHardBlocked(canaryResult.canary)
                            && ("missing_impression".equals(ackError)
                            || "missing_canary".equals(ackError))) {
                        Log.d(TAG, "ntk_native_ack_canary_hard_blocked path=" + challengePath
                                + ",ackError=" + ackError);
                        break;
                    }
                }
                if(isNtkAckHardBlocked(ack)) {
                    Log.d(TAG, "ntk_native_ack_ack_hard_blocked path=" + challengePath);
                    break;
                }
            }
            boolean ackSuccess = ack != null && (ack.code == 200 || ackCookieSalvaged) && ackBodyOk;
            phaseStartedMs = logNtkNativeAckPhase("ack", challengePath, startedMs, phaseStartedMs);
            Log.d(TAG, "ntk_native_ack_final_success=" + ackSuccess
                    + ",path=" + challengePath
                    + ",cookieOk=" + hasNtkAdAckCookieForPath(challengePath)
                    + ",totalMs=" + (System.currentTimeMillis() - startedMs));
            if(ackSuccess) {
                NTK_ACK_CACHE.put(cacheKey, System.currentTimeMillis());
                String finalAdAck = getCookie("ad_ack");
                if(ntkAckCookieUsableForPath("ad_ack", finalAdAck, challengePath))
                    NtkWebViewFallbackManager.rememberScopedAdAck(
                            challengePath, finalAdAck, "native-ack");
                String finalAdAckC = getCookie("ad_ack_c");
                if(ntkAckCookieUsableForPath("ad_ack_c", finalAdAckC, challengePath))
                    NtkWebViewFallbackManager.rememberScopedAdAckC(
                            challengePath, finalAdAckC, "native-ack");
                if(ack != null && ack.code == 200 && ackBodyOk)
                    NtkWebViewFallbackManager.rememberExternalServerAckSuccess(
                            challengePath, "native-ack-200");
            }
            return ackSuccess;
        } catch(Exception e) {
            if(isInterruptedRequest(e)) {
                Log.d(TAG, "ntk_native_ack_bypass_cancelled totalMs="
                        + (System.currentTimeMillis() - startedMs)
                        + ",path=" + path);
            } else {
                Log.d(TAG, "ntk_native_ack_bypass_exception=" + e
                        + ",totalMs=" + (System.currentTimeMillis() - startedMs)
                        + ",path=" + path);
            }
            return false;
        }
    }

    private static boolean ntkChallengeIssuedAdAckCookie(NtkQuicFetcher.Result result) {
        if(result == null || result.error != null || result.code != 200)
            return false;
        if(result.body == null || !looksLikeJsonObject(result.body))
            return false;
        try {
            JSONObject json = new JSONObject(result.body);
            if(!json.optBoolean("ok", false))
                return false;
        } catch (Exception e) {
            return false;
        }
        for(String cookie : result.setCookies()) {
            if(cookie == null)
                continue;
            int eq = cookie.indexOf('=');
            if(eq <= 0)
                continue;
            String name = cookie.substring(0, eq).trim();
            int end = cookie.indexOf(';', eq + 1);
            String value = cookie.substring(eq + 1, end >= 0 ? end : cookie.length()).trim();
            if("ad_ack_c".equals(name) && value.length() > 0)
                return true;
        }
        return false;
    }

    private boolean isNtkAckHardBlocked(NtkQuicFetcher.Result result) {
        return result != null
                && result.error == null
                && result.code == 403
                && isCloudflareChallengeResponse(result.code, result.body);
    }

    private boolean handleNtkAckHardBlock(String baseUrl, String path) {
        boolean reset = markNtkHardBlockPreservingClearance(baseUrl + path);
        if(reset) {
            String ackPath = ntkNativeAckScopePath(path);
            String flightKey = ntkNativeAckFlightKey(ackPath);
            clearNtkAckChallengeHardBlock(baseUrl + ackPath, flightKey);
            clearNtkAckChallengeHardBlock(NTK_WEBTOON_URL + ackPath, flightKey);
            Log.d(TAG, "ntk_native_ack_hardblock_retry_after_clearance_reset path=" + ackPath);
        }
        Log.d(TAG, "ntk_native_ack_hardblock_preserve_clearance path=" + path
                + ",proof=" + hasNtkAccessProof()
                + ",recent=" + hasRecentNtkAccessVerification()
                + ",fresh=" + hasFreshCloudflareClearance());
        return reset;
    }

    private void ensureNtkNvCookieForAck(String baseUrl, String path, String source) {
        try {
            String before = getCookie("nv");
            boolean beforeValid = isNtkNvValid(before);
            if(!beforeValid)
                issueNtkNvCookie(baseUrl);
            String after = getCookie("nv");
            Log.d(TAG, "ntk_native_ack_nv_bootstrap source=" + source
                    + ",path=" + path
                    + ",before=" + beforeValid
                    + ",after=" + isNtkNvValid(after));
        } catch (Exception e) {
            Log.d(TAG, "ntk_native_ack_nv_bootstrap_error source=" + source
                    + ",path=" + path
                    + "," + e);
        }
    }

    private boolean hasNtkAckGuardBootstrapForNativeChallenge(String path) {
        return hasCloudflareClearance()
                || getCookie("ad_guard_l") != null
                || getCookie("ad_ack_c") != null
                || hasCloudflareClearance()
                || hasNtkAdAckCookieForPath(path);
    }

    boolean performNtkNativeAckBypassFresh(String baseUrl, String path) {
        return performNtkNativeAckBypassFresh(baseUrl, path, path);
    }

    boolean performNtkNativeAckBypassFresh(String baseUrl, String path, String refererPath) {
        if(baseUrl == null || path == null || baseUrl.length() == 0 || path.length() == 0)
            return false;
        if(!NtkQuicFetcher.isAvailable()) {
            Log.d(TAG, "ntk_native_ack_fresh_skip_quic_unavailable path=" + path);
            return false;
        }
        String ackPath = ntkNativeAckScopePath(path);
        String cacheKey = baseUrl + ackPath;
        String flightKey = ntkNativeAckFlightKey(ackPath);
        if(hasRecentNtkAckProofRequired(cacheKey, flightKey)
                && !hasNtkAdAckCookieForPath(ackPath)) {
            Log.d(TAG, "ntk_native_ack_force_refresh_proof_required_cached path=" + path);
            return false;
        }
        synchronized (ntkNativeAckLock(flightKey)) {
            if(hasNtkAdAckCookieForPath(ackPath)) {
                NTK_ACK_CACHE.put(cacheKey, System.currentTimeMillis());
                Log.d(TAG, "ntk_native_ack_force_refresh_cookie_hit path=" + path);
                return true;
            }
            if(hasRecentNtkAckProofRequired(cacheKey, flightKey)) {
                Log.d(TAG, "ntk_native_ack_force_refresh_locked_proof_required_cached path=" + path);
                return false;
            }
            NTK_ACK_CACHE.remove(cacheKey);
            removeNtkAckCookies();
            Log.d(TAG, "ntk_native_ack_force_refresh path=" + path);
            return performNtkNativeAckBypassLocked(baseUrl, ackPath, cacheKey,
                    refererPath != null && refererPath.length() > 0 ? refererPath : path);
        }
    }

    private static String ntkNativeAckFlightKey(String ackPath) {
        return ntkNativeAckScopePath(ackPath);
    }

    private static Object ntkNativeAckLock(String flightKey) {
        Object lock = NTK_ACK_LOCKS.get(flightKey);
        if(lock != null)
            return lock;
        Object created = new Object();
        Object existing = NTK_ACK_LOCKS.putIfAbsent(flightKey, created);
        return existing != null ? existing : created;
    }

    private NtkQuicFetcher.Result fetchNtkAckChallengeRace(HttpEngine engine,
                                                           ExecutorService executor,
                                                           String baseUrl, String path,
                                                           Map<String, String> headers,
                                                           byte[] challengeBytes,
                                                           int attempt) throws InterruptedException {
        String ackPath = ntkNativeAckScopePath(path);
        String cacheKey = baseUrl + ackPath;
        String flightKey = ntkNativeAckFlightKey(ackPath);
        int sharedLanes = engine == null ? 0 : Math.max(0, NTK_ACK_CHALLENGE_RACE_REQUESTS);
        int lanes = sharedLanes
                + (NTK_ACK_CHALLENGE_OKHTTP_RACE ? 1 : 0)
                + (NTK_ACK_CHALLENGE_FRESH_RACE ? 1 : 0)
                + (NTK_ACK_CHALLENGE_BACKUP_RACE && engine != null ? 1 : 0)
                + (NTK_ACK_CHALLENGE_HTTP2_RACE ? 1 : 0);
        ExecutorService raceExecutor = Executors.newFixedThreadPool(Math.max(1, lanes), runnable -> {
            Thread thread = new Thread(runnable, "ntk-ack-challenge-race");
            thread.setDaemon(true);
            return thread;
        });
        ExecutorCompletionService<NtkAckChallengeResult> completion =
                new ExecutorCompletionService<>(raceExecutor);
        List<Future<NtkAckChallengeResult>> futures = new ArrayList<>();
        int submitted = 0;
        int submittedShared = 0;
        while(submittedShared < sharedLanes) {
            submitNtkAckChallengeLane(completion, futures, engine, executor, baseUrl, path,
                    headers, challengeBytes, attempt, submitted++);
            submittedShared++;
        }
        if(NTK_ACK_CHALLENGE_OKHTTP_RACE) {
            submitNtkAckChallengeOkHttpLane(completion, futures, baseUrl, path,
                    headers, challengeBytes, attempt, submitted++);
        }
        if(NTK_ACK_CHALLENGE_FRESH_RACE) {
            submitNtkAckChallengeFreshLane(completion, futures, baseUrl, path,
                    headers, challengeBytes, attempt, submitted++);
        }
        if(NTK_ACK_CHALLENGE_BACKUP_RACE && engine != null) {
            submitNtkAckChallengeLane(completion, futures, engine, executor, baseUrl, path,
                    headers, challengeBytes, attempt, submitted++, NTK_ACK_CHALLENGE_BACKUP_DELAY_MS);
        }
        if(NTK_ACK_CHALLENGE_HTTP2_RACE) {
            submitNtkAckChallengeHttp2Lane(completion, futures, baseUrl, path,
                    headers, challengeBytes, attempt, submitted++);
        }
        NtkQuicFetcher.Result fallback = null;
        NtkQuicFetcher.Result hardBlockFallback = null;
        try {
            for(int completed = 0; completed < lanes; completed++) {
                long challengeTimeoutMs = ntkAckChallengeTimeoutMs(attempt);
                Future<NtkAckChallengeResult> completedFuture = completion.poll(
                        completed == 0 && submitted < lanes
                                ? NTK_ACK_CHALLENGE_HEDGE_DELAY_MS
                                : challengeTimeoutMs,
                        TimeUnit.MILLISECONDS);
                if(completedFuture == null) {
                    if(submittedShared < sharedLanes) {
                        submitNtkAckChallengeLane(completion, futures, engine, executor, baseUrl, path,
                                headers, challengeBytes, attempt, submitted++);
                        submittedShared++;
                    }
                    completedFuture = completion.take();
                }
                NtkQuicFetcher.Result result = null;
                String transport = "unknown";
                Throwable error = null;
                try {
                    NtkAckChallengeResult raceResult = completedFuture.get();
                    if(raceResult != null) {
                        transport = raceResult.transport;
                        result = raceResult.result;
                    }
                } catch(InterruptedException e) {
                    throw e;
                } catch(Exception e) {
                    error = e;
                }
                if(result != null) {
                    applySetCookieHeaders(result.headers, baseUrl);
                    if(fallback == null)
                        fallback = result;
                }
                boolean valid = result != null && result.error == null
                        && result.code == 200 && looksLikeJsonObject(result.body);
                Log.d(TAG, "ntk_native_ack_challenge_race_done attempt=" + attempt
                        + ",completed=" + completed
                        + ",transport=" + transport
                        + ",code=" + (result == null ? "null" : result.code)
                        + ",error=" + (result == null ? error : result.error)
                        + ",valid=" + valid
                        + ",path=" + path);
                if(isNtkAckHardBlocked(result)) {
                    hardBlockFallback = result;
                    if(completed + 1 < lanes && hasPendingNtkAckChallengeLane(futures)) {
                        Log.d(TAG, "ntk_native_ack_challenge_hardblock_wait_pending attempt=" + attempt
                                + ",completed=" + completed
                                + ",transport=" + transport
                                + ",path=" + path);
                        continue;
                    }
                    markNtkAckChallengeHardBlock(cacheKey, flightKey);
                    return hardBlockFallback;
                }
                if(valid) {
                    markNtkAckChallengeOk(cacheKey, flightKey);
                    return result;
                }
                if(result == null && error == null) {
                    if(completed + 1 < lanes && hasPendingNtkAckChallengeLane(futures)) {
                        Log.d(TAG, "ntk_native_ack_challenge_wait_pending attempt=" + attempt
                                + ",completed=" + completed
                                + ",transport=" + transport
                                + ",path=" + path);
                        continue;
                    }
                    Log.d(TAG, "ntk_native_ack_challenge_fast_retry attempt=" + attempt
                            + ",completed=" + completed
                            + ",transport=" + transport
                            + ",path=" + path);
                    return null;
                }
            }
            if(isNtkAckHardBlocked(hardBlockFallback)) {
                markNtkAckChallengeHardBlock(cacheKey, flightKey);
                return hardBlockFallback;
            }
            return fallback;
        } finally {
            for(Future<NtkAckChallengeResult> future : futures) {
                if(future != null && !future.isDone())
                    future.cancel(true);
            }
            raceExecutor.shutdownNow();
        }
    }

    private NtkQuicFetcher.Result fetchNtkAckChallengeRace(FutureTask<HttpEngine> engineTask,
                                                           ExecutorService executor,
                                                           String baseUrl, String path,
                                                           Map<String, String> headers,
                                                           byte[] challengeBytes,
                                                           int attempt) throws InterruptedException {
        String ackPath = ntkNativeAckScopePath(path);
        String cacheKey = baseUrl + ackPath;
        String flightKey = ntkNativeAckFlightKey(ackPath);
        int sharedLanes = engineTask == null ? 0 : Math.max(0, NTK_ACK_CHALLENGE_RACE_REQUESTS);
        int lanes = sharedLanes
                + (NTK_ACK_CHALLENGE_OKHTTP_RACE ? 1 : 0)
                + (NTK_ACK_CHALLENGE_FRESH_RACE ? 1 : 0)
                + (NTK_ACK_CHALLENGE_BACKUP_RACE && engineTask != null ? 1 : 0)
                + (NTK_ACK_CHALLENGE_HTTP2_RACE ? 1 : 0);
        ExecutorService raceExecutor = Executors.newFixedThreadPool(Math.max(1, lanes), runnable -> {
            Thread thread = new Thread(runnable, "ntk-ack-challenge-race");
            thread.setDaemon(true);
            return thread;
        });
        ExecutorCompletionService<NtkAckChallengeResult> completion =
                new ExecutorCompletionService<>(raceExecutor);
        List<Future<NtkAckChallengeResult>> futures = new ArrayList<>();
        int submitted = 0;
        int submittedShared = 0;
        while(submittedShared < sharedLanes) {
            submitNtkAckChallengeLane(completion, futures, engineTask, executor, baseUrl, path,
                    headers, challengeBytes, attempt, submitted++);
            submittedShared++;
        }
        if(NTK_ACK_CHALLENGE_OKHTTP_RACE) {
            submitNtkAckChallengeOkHttpLane(completion, futures, baseUrl, path,
                    headers, challengeBytes, attempt, submitted++);
        }
        if(NTK_ACK_CHALLENGE_FRESH_RACE) {
            submitNtkAckChallengeFreshLane(completion, futures, baseUrl, path,
                    headers, challengeBytes, attempt, submitted++);
        }
        if(NTK_ACK_CHALLENGE_BACKUP_RACE && engineTask != null) {
            submitNtkAckChallengeLane(completion, futures, engineTask, executor, baseUrl, path,
                    headers, challengeBytes, attempt, submitted++, NTK_ACK_CHALLENGE_BACKUP_DELAY_MS);
        }
        if(NTK_ACK_CHALLENGE_HTTP2_RACE) {
            submitNtkAckChallengeHttp2Lane(completion, futures, baseUrl, path,
                    headers, challengeBytes, attempt, submitted++);
        }
        NtkQuicFetcher.Result fallback = null;
        NtkQuicFetcher.Result hardBlockFallback = null;
        try {
            for(int completed = 0; completed < lanes; completed++) {
                long challengeTimeoutMs = ntkAckChallengeTimeoutMs(attempt);
                long firstWaitMs = challengeTimeoutMs;
                Future<NtkAckChallengeResult> completedFuture = completion.poll(
                        completed == 0 && submitted < lanes
                                ? NTK_ACK_CHALLENGE_HEDGE_DELAY_MS
                                : firstWaitMs,
                        TimeUnit.MILLISECONDS);
                if(completedFuture == null) {
                    if(submittedShared < sharedLanes) {
                        submitNtkAckChallengeLane(completion, futures, engineTask, executor, baseUrl, path,
                                headers, challengeBytes, attempt, submitted++);
                        submittedShared++;
                    }
                    completedFuture = completion.take();
                }
                NtkQuicFetcher.Result result = null;
                String transport = "unknown";
                Throwable error = null;
                try {
                    NtkAckChallengeResult raceResult = completedFuture.get();
                    if(raceResult != null) {
                        transport = raceResult.transport;
                        result = raceResult.result;
                    }
                } catch(InterruptedException e) {
                    throw e;
                } catch(Exception e) {
                    error = e;
                }
                if(result != null) {
                    applySetCookieHeaders(result.headers, baseUrl);
                    if(fallback == null)
                        fallback = result;
                }
                boolean valid = result != null && result.error == null
                        && result.code == 200 && looksLikeJsonObject(result.body);
                Log.d(TAG, "ntk_native_ack_challenge_race_done attempt=" + attempt
                        + ",completed=" + completed
                        + ",transport=" + transport
                        + ",code=" + (result == null ? "null" : result.code)
                        + ",error=" + (result == null ? error : result.error)
                        + ",valid=" + valid
                        + ",path=" + path);
                if(isNtkAckHardBlocked(result)) {
                    hardBlockFallback = result;
                    if(completed + 1 < lanes && hasPendingNtkAckChallengeLane(futures)) {
                        Log.d(TAG, "ntk_native_ack_challenge_hardblock_wait_pending attempt=" + attempt
                                + ",completed=" + completed
                                + ",transport=" + transport
                                + ",path=" + path);
                        continue;
                    }
                    markNtkAckChallengeHardBlock(cacheKey, flightKey);
                    return hardBlockFallback;
                }
                if(valid) {
                    markNtkAckChallengeOk(cacheKey, flightKey);
                    return result;
                }
                if(result == null && error == null) {
                    if(completed + 1 < lanes && hasPendingNtkAckChallengeLane(futures)) {
                        Log.d(TAG, "ntk_native_ack_challenge_wait_pending attempt=" + attempt
                                + ",completed=" + completed
                                + ",transport=" + transport
                                + ",path=" + path);
                        continue;
                    }
                    Log.d(TAG, "ntk_native_ack_challenge_fast_retry attempt=" + attempt
                            + ",completed=" + completed
                            + ",transport=" + transport
                            + ",path=" + path);
                    return null;
                }
            }
            if(isNtkAckHardBlocked(hardBlockFallback)) {
                markNtkAckChallengeHardBlock(cacheKey, flightKey);
                return hardBlockFallback;
            }
            return fallback;
        } finally {
            for(Future<NtkAckChallengeResult> future : futures) {
                if(future != null && !future.isDone())
                    future.cancel(true);
            }
            raceExecutor.shutdownNow();
        }
    }

    private boolean hasPendingNtkAckChallengeLane(List<Future<NtkAckChallengeResult>> futures) {
        if(futures == null)
            return false;
        for(Future<NtkAckChallengeResult> future : futures) {
            if(future != null && !future.isDone())
                return true;
        }
        return false;
    }

    private void submitNtkAckChallengeLane(ExecutorCompletionService<NtkAckChallengeResult> completion,
                                           List<Future<NtkAckChallengeResult>> futures,
                                           HttpEngine engine,
                                           ExecutorService executor,
                                           String baseUrl,
                                           String path,
                                           Map<String, String> headers,
                                           byte[] challengeBytes,
                                           int attempt,
                                           int lane) {
        submitNtkAckChallengeLane(completion, futures, engine, executor, baseUrl, path,
                headers, challengeBytes, attempt, lane, 0L);
    }

    private static long ntkAckChallengeTimeoutMs(int attempt) {
        return attempt <= 0 ? NTK_ACK_CHALLENGE_FIRST_TIMEOUT_MS : NTK_ACK_CHALLENGE_TIMEOUT_MS;
    }

    private NtkQuicFetcher.Result fetchNtkAckChallengeDedicated(HttpEngine engine,
                                                                String baseUrl,
                                                                Map<String, String> headers,
                                                                byte[] challengeBytes,
                                                                int attempt) throws InterruptedException {
        if(engine == null)
            return null;
        return NtkQuicFetcher.fetchWithEngine(engine, NTK_ACK_CHALLENGE_CALLBACK_EXECUTOR,
                baseUrl + "/api/ad/challenge", agent, getCookieHeader(), headers,
                "POST", challengeBytes, ntkAckChallengeTimeoutMs(attempt));
    }

    private void submitNtkAckChallengeLane(ExecutorCompletionService<NtkAckChallengeResult> completion,
                                           List<Future<NtkAckChallengeResult>> futures,
                                           HttpEngine engine,
                                           ExecutorService executor,
                                           String baseUrl,
                                           String path,
                                           Map<String, String> headers,
                                           byte[] challengeBytes,
                                           int attempt,
                                           int lane,
                                           long delayMs) {
        futures.add(completion.submit(() -> {
            long laneStartedAt = System.currentTimeMillis();
            String stage = "engine_ready";
            try {
                if(delayMs > 0L) {
                    stage = "backup_delay";
                    Thread.sleep(delayMs);
                }
                if(engine != null && executor != null) {
                    stage = "challenge_fetch";
                    NtkQuicFetcher.Result result = fetchNtkAckChallengeDedicated(
                            engine, baseUrl, headers, challengeBytes, attempt);
                    if(result != null && result.error == null && result.code > 0)
                        return new NtkAckChallengeResult("shared", result);
                    Log.d(TAG, "ntk_native_ack_challenge_shared_miss attempt=" + attempt
                            + ",lane=" + lane
                            + ",stage=" + stage
                            + ",code=" + (result == null ? "null" : result.code)
                            + ",error=" + (result == null ? null : result.error)
                            + ",ms=" + (System.currentTimeMillis() - laneStartedAt)
                            + ",path=" + path);
                } else {
                    Log.d(TAG, "ntk_native_ack_challenge_shared_miss attempt=" + attempt
                            + ",lane=" + lane
                            + ",stage=engine_null"
                            + ",ms=" + (System.currentTimeMillis() - laneStartedAt)
                            + ",path=" + path);
                }
            } catch(InterruptedException e) {
                Thread.currentThread().interrupt();
                Log.d(TAG, "ntk_native_ack_challenge_shared_interrupted attempt=" + attempt
                        + ",lane=" + lane
                        + ",stage=" + stage
                        + ",ms=" + (System.currentTimeMillis() - laneStartedAt)
                        + ",path=" + path);
                return new NtkAckChallengeResult("shared", null);
            } catch(Exception e) {
                Log.d(TAG, "ntk_native_ack_challenge_shared_error attempt=" + attempt
                        + ",lane=" + lane
                        + ",stage=" + stage
                        + ",ms=" + (System.currentTimeMillis() - laneStartedAt)
                        + ",path=" + path
                        + ",error=" + e);
            }
            return new NtkAckChallengeResult("shared", null);
        }));
        Log.d(TAG, "ntk_native_ack_challenge_race_start attempt=" + attempt
                + ",lane=" + lane
                + (delayMs > 0L ? ",delayMs=" + delayMs : "")
                + ",path=" + path);
    }

    private void submitNtkAckChallengeLane(ExecutorCompletionService<NtkAckChallengeResult> completion,
                                           List<Future<NtkAckChallengeResult>> futures,
                                           FutureTask<HttpEngine> engineTask,
                                           ExecutorService executor,
                                           String baseUrl,
                                           String path,
                                           Map<String, String> headers,
                                           byte[] challengeBytes,
                                           int attempt,
                                           int lane) {
        submitNtkAckChallengeLane(completion, futures, engineTask, executor, baseUrl, path,
                headers, challengeBytes, attempt, lane, 0L);
    }

    private void submitNtkAckChallengeLane(ExecutorCompletionService<NtkAckChallengeResult> completion,
                                           List<Future<NtkAckChallengeResult>> futures,
                                           FutureTask<HttpEngine> engineTask,
                                           ExecutorService executor,
                                           String baseUrl,
                                           String path,
                                           Map<String, String> headers,
                                           byte[] challengeBytes,
                                           int attempt,
                                           int lane,
                                           long delayMs) {
        futures.add(completion.submit(() -> {
            long laneStartedAt = System.currentTimeMillis();
            String stage = "engine_wait";
            try {
                if(delayMs > 0L) {
                    stage = "backup_delay";
                    Thread.sleep(delayMs);
                }
                long timeoutMs = ntkAckChallengeTimeoutMs(attempt);
                HttpEngine engine = engineTask == null ? null
                        : engineTask.get(timeoutMs, TimeUnit.MILLISECONDS);
                if(engine != null && executor != null) {
                    stage = "challenge_fetch";
                    NtkQuicFetcher.Result result = fetchNtkAckChallengeDedicated(
                            engine, baseUrl, headers, challengeBytes, attempt);
                    if(result != null && result.error == null && result.code > 0)
                        return new NtkAckChallengeResult("shared", result);
                    Log.d(TAG, "ntk_native_ack_challenge_shared_miss attempt=" + attempt
                            + ",lane=" + lane
                            + ",stage=" + stage
                            + ",code=" + (result == null ? "null" : result.code)
                            + ",error=" + (result == null ? null : result.error)
                            + ",ms=" + (System.currentTimeMillis() - laneStartedAt)
                            + ",path=" + path);
                } else {
                    Log.d(TAG, "ntk_native_ack_challenge_shared_miss attempt=" + attempt
                            + ",lane=" + lane
                            + ",stage=engine_null"
                            + ",ms=" + (System.currentTimeMillis() - laneStartedAt)
                            + ",path=" + path);
                }
            } catch(InterruptedException e) {
                Thread.currentThread().interrupt();
                Log.d(TAG, "ntk_native_ack_challenge_shared_interrupted attempt=" + attempt
                        + ",lane=" + lane
                        + ",stage=" + stage
                        + ",ms=" + (System.currentTimeMillis() - laneStartedAt)
                        + ",path=" + path);
                return new NtkAckChallengeResult("shared", null);
            } catch(Exception e) {
                Log.d(TAG, "ntk_native_ack_challenge_shared_error attempt=" + attempt
                        + ",lane=" + lane
                        + ",stage=" + stage
                        + ",ms=" + (System.currentTimeMillis() - laneStartedAt)
                        + ",path=" + path
                        + ",error=" + e);
            }
            return new NtkAckChallengeResult("shared", null);
        }));
        Log.d(TAG, "ntk_native_ack_challenge_race_start attempt=" + attempt
                + ",lane=" + lane
                + (delayMs > 0L ? ",delayMs=" + delayMs : "")
                + ",transport=shared-async,path=" + path);
    }

    private void submitNtkAckChallengeOkHttpLane(ExecutorCompletionService<NtkAckChallengeResult> completion,
                                                 List<Future<NtkAckChallengeResult>> futures,
                                                 String baseUrl,
                                                 String path,
                                                 Map<String, String> headers,
                                                 byte[] challengeBytes,
                                                 int attempt,
                                                 int lane) {
        futures.add(completion.submit(() -> {
            Response response = null;
            try {
                RequestBody body = RequestBody.create(challengeBytes,
                        MediaType.parse("application/json; charset=utf-8"));
                Request.Builder builder = new Request.Builder()
                        .url(baseUrl + "/api/ad/challenge")
                        .post(body)
                        .addHeader("User-Agent", agent);
                if(headers != null) {
                    for(String key : headers.keySet()) {
                        String value = headers.get(key);
                        if(value != null && value.length() > 0)
                            builder.header(key, value);
                    }
                }
                String cookieHeader = getCookieHeader();
                if(cookieHeader != null && cookieHeader.length() > 0)
                    builder.header("Cookie", cookieHeader);
                Request request = builder.build();
                try {
                    response = ntkApiFastClient.newCall(request).execute();
                } catch(Exception firstError) {
                    if(!isNtkSslTrustFailure(firstError) || unsafeNtkApiFastClient == null)
                        throw firstError;
                    Log.d(TAG, "ntk_native_ack_challenge_okhttp_unsafe_retry path=" + path
                            + ",error=" + firstError.getClass().getSimpleName());
                    response = unsafeNtkApiFastClient.newCall(request).execute();
                }
                byte[] bytes = response.body() == null ? new byte[0] : response.body().bytes();
                return new NtkAckChallengeResult("okhttp",
                        NtkQuicFetcher.Result.fromBytes(response.code(), bytes, response.headers().toMultimap()));
            } catch(Exception e) {
                return new NtkAckChallengeResult("okhttp", NtkQuicFetcher.Result.error(e));
            } finally {
                if(response != null)
                    response.close();
            }
        }));
        Log.d(TAG, "ntk_native_ack_challenge_race_start attempt=" + attempt
                + ",lane=" + lane
                + ",transport=okhttp,path=" + path);
    }

    private void submitNtkAckChallengeFreshLane(ExecutorCompletionService<NtkAckChallengeResult> completion,
                                                List<Future<NtkAckChallengeResult>> futures,
                                                String baseUrl,
                                                String path,
                                                Map<String, String> headers,
                                                byte[] challengeBytes,
                                                int attempt,
                                                int lane) {
        futures.add(completion.submit(() -> new NtkAckChallengeResult("fresh",
                NtkQuicFetcher.fetch(context, baseUrl + "/api/ad/challenge", agent, getCookieHeader(), headers,
                        "POST", challengeBytes, NTK_ACK_CHALLENGE_TIMEOUT_MS))));
        Log.d(TAG, "ntk_native_ack_challenge_race_start attempt=" + attempt
                + ",lane=" + lane
                + ",transport=fresh,path=" + path);
    }

    private void submitNtkAckChallengeHttp2Lane(ExecutorCompletionService<NtkAckChallengeResult> completion,
                                                List<Future<NtkAckChallengeResult>> futures,
                                                String baseUrl,
                                                String path,
                                                Map<String, String> headers,
                                                byte[] challengeBytes,
                                                int attempt,
                                                int lane) {
        futures.add(completion.submit(() -> new NtkAckChallengeResult("http2",
                NtkQuicFetcher.fetchHttp2Only(context, baseUrl + "/api/ad/challenge", agent, getCookieHeader(), headers,
                        "POST", challengeBytes, NTK_ACK_CHALLENGE_TIMEOUT_MS))));
        Log.d(TAG, "ntk_native_ack_challenge_race_start attempt=" + attempt
                + ",lane=" + lane
                + ",transport=http2,path=" + path);
    }

    private static final class NtkAckChallengeResult {
        final String transport;
        final NtkQuicFetcher.Result result;

        NtkAckChallengeResult(String transport, NtkQuicFetcher.Result result) {
            this.transport = transport;
            this.result = result;
        }
    }

    private long logNtkNativeAckPhase(String phase, String path, long startedMs, long phaseStartedMs) {
        long now = System.currentTimeMillis();
        Log.d(TAG, "ntk_native_ack_phase phase=" + phase
                + ",phaseMs=" + (now - phaseStartedMs)
                + ",totalMs=" + (now - startedMs)
                + ",path=" + path);
        return now;
    }

    private static boolean looksLikeJsonObject(String body) {
        return body != null && body.trim().startsWith("{");
    }

    public synchronized boolean hasUsableNtkAdAckCookieForPath(String path) {
        return hasNtkAdAckCookieForPath(path);
    }

    private synchronized boolean hasNtkAdAckCookieForPath(String path) {
        return ntkAckCookieUsableForPath("ad_ack", cookies.get("ad_ack"), path)
                || ntkAckCookieUsableForPath("ad_ack_c", cookies.get("ad_ack_c"), path);
    }

    private synchronized boolean removeExpiredNtkAckCookiesLocked() {
        boolean changed = false;
        for(String key : new String[]{"ad_ack", "ad_ack_c"}) {
            String value = cookies.get(key);
            if(value != null && isExpiredNtkAckCookie(value)) {
                cookies.remove(key);
                changed = true;
            }
        }
        if(changed)
            invalidateCookieHeaderCache();
        if(changed)
            clearNtkAccessVerification();
        return changed;
    }

    private static boolean isNtkAckCookieName(String key) {
        return "ad_ack".equals(key) || "ad_ack_c".equals(key);
    }

    private static boolean ntkAckCookieUsableForPath(String key, String value, String path) {
        if(value == null || value.length() == 0)
            return false;
        if(isExpiredNtkAckCookie(value))
            return false;
        if("ad_ack".equals(key))
            return ntkAdAckCookieMatchesPath(value, path);
        if("ad_ack_c".equals(key))
            return ntkAdAckCookieMatchesPath(value, path);
        return true;
    }

    private static boolean isExpiredNtkAckCookie(String value) {
        long exp = ntkAckCookieExpiryMs(value);
        return exp > 0L && exp < System.currentTimeMillis();
    }

    private static String ntkAckCookieScope(String value) {
        JSONObject payload = ntkAckCookiePayload(value);
        return payload == null ? "" : payload.optString("scope", "");
    }

    private static long ntkAckCookieExpiryMs(String value) {
        JSONObject payload = ntkAckCookiePayload(value);
        if(payload == null)
            return 0L;
        long exp = payload.optLong("exp", 0L);
        if(exp <= 0L)
            return 0L;
        return exp < 100_000_000_000L ? exp * 1000L : exp;
    }

    private static JSONObject ntkAckCookiePayload(String value) {
        if(value == null || value.length() == 0)
            return null;
        try {
            String[] parts = value.split("\\.");
            if(parts.length < 1)
                return null;
            String payload = parts[0];
            int padding = (4 - (payload.length() % 4)) % 4;
            StringBuilder padded = new StringBuilder(payload);
            for(int i = 0; i < padding; i++)
                padded.append('=');
            byte[] decoded = Base64.decode(padded.toString(), Base64.URL_SAFE | Base64.NO_WRAP);
            return new JSONObject(new String(decoded, StandardCharsets.UTF_8));
        } catch(Exception ignored) {
            return null;
        }
    }

    private static String ntkViewerImagesTokenField(String token, String field) {
        if(token == null || token.length() == 0 || field == null || field.length() == 0)
            return "";
        try {
            String[] parts = token.split("\\.");
            if(parts.length < 1)
                return "";
            String payload = parts[0];
            int padding = (4 - (payload.length() % 4)) % 4;
            StringBuilder padded = new StringBuilder(payload);
            for(int i = 0; i < padding; i++)
                padded.append('=');
            byte[] decoded = Base64.decode(padded.toString(), Base64.URL_SAFE | Base64.NO_WRAP);
            JSONObject json = new JSONObject(new String(decoded, StandardCharsets.UTF_8));
            return json.optString(field, "");
        } catch(Exception ignored) {
            return "";
        }
    }

    private static boolean ntkAdAckCookieMatchesPath(String value, String path) {
        if(value == null || value.length() == 0 || path == null || path.length() == 0)
            return false;
        try {
            JSONObject json = ntkAckCookiePayload(value);
            if(json == null)
                return false;
            String scope = json.optString("scope", "");
            if(isExpiredNtkAckCookie(value))
                return false;
            return ntkPathScopesEqual(scope, path);
        } catch(Exception ignored) {
            return false;
        }
    }

    private boolean appendReachableNtkViewerImages(List<String> urls, NtkQuicFetcher.Result result,
                                                   String baseUrl, String refererPath,
                                                   String kind, String workId, String episodeId,
                                                   NtkViewerImageUrlsCallback trustedUrlsCallback) {
        if(urls == null)
            return false;
        notifyFirstNtkViewerImageUrl(trustedUrlsCallback, result, refererPath);
        if(!appendNtkViewerImages(urls, result, kind, workId, episodeId))
            return false;
        prepareNtkImageTransportForUrls(urls, refererPath);
        Log.d(TAG, "ntk_images_api_trusted_result path=" + refererPath
                + ",code=" + (result == null ? 0 : result.code)
                + ",count=" + (result == null ? 0 : ntkViewerImagesCount(result)));
        notifyNtkViewerImageUrls(trustedUrlsCallback, urls);
        return true;
    }

    private void notifyFirstNtkViewerImageUrl(NtkViewerImageUrlsCallback callback,
                                              NtkQuicFetcher.Result result,
                                              String refererPath) {
        if(callback == null || result == null || result.error != null
                || result.code < 200 || result.code >= 300 || result.body == null)
            return;
        try {
            JSONObject response = new JSONObject(result.body);
            if(response.optBoolean("ad_ack_required", false) || !response.optBoolean("ok", false))
                return;
            JSONArray images = response.optJSONArray("images");
            if(images == null || images.length() == 0)
                return;
            JSONObject firstImage = images.optJSONObject(0);
            String first = firstImage == null ? "" : firstImage.optString("src", "");
            String stableFirst = normalizeNtkVolatileCdnImageSrc(first);
            if(stableFirst.length() > 0)
                first = stableFirst;
            if(first.length() == 0 || !isTrustedNtkPrimaryImageUrl(first))
                return;
            Log.d(TAG, "ntk_images_api_first_url_early path=" + refererPath
                    + ",image=" + safeLogUrl(first));
            prepareNtkImageTransportForUrls(Collections.singletonList(first), refererPath);
            callback.onUrls(Collections.singletonList(first));
        } catch(Exception e) {
            Log.d(TAG, "ntk_images_api_first_url_early_error " + e);
        }
    }

    private void notifyNtkViewerImageUrls(NtkViewerImageUrlsCallback callback, List<String> urls) {
        if(callback == null || urls == null || urls.isEmpty())
            return;
        try {
            callback.onUrls(new ArrayList<>(urls));
        } catch(Exception e) {
            Log.d(TAG, "ntk_images_api_callback_error " + e);
        }
    }

    private void prepareNtkImageTransportForUrls(List<String> urls, String refererPath) {
        if(urls == null || urls.isEmpty() || context == null || !NtkQuicFetcher.isAvailable())
            return;
        String first = urls.get(0);
        if(first == null || first.length() == 0 || !shouldUseNtkQuicPrimaryUrl(first))
            return;
        try {
            HttpUrl parsed = HttpUrl.parse(first);
            if(parsed == null)
                return;
            String imageBaseUrl = rootFromHttpUrl(parsed);
            Thread thread = new Thread(() -> {
                long startedAt = System.currentTimeMillis();
                HttpEngine engine = getOrCreateNtkQuicEngine(imageBaseUrl);
                ExecutorService executor = getOrCreateNtkQuicExecutor(imageBaseUrl);
                Log.d(TAG, "ntk_image_transport_prepare path=" + refererPath
                        + ",host=" + parsed.host()
                        + ",ready=" + (engine != null && executor != null)
                        + ",ms=" + (System.currentTimeMillis() - startedAt));
            }, "ntk-image-transport-prepare");
            thread.setDaemon(true);
            thread.start();
        } catch(Exception ignored) {
        }
    }

    public void prepareNtkImageTransportForViewerUrls(List<String> urls, String refererPath) {
        prepareNtkImageTransportForUrls(urls, refererPath);
    }

    private boolean areInitialNtkViewerImageUrlsReachable(List<String> urls, String baseUrl, String refererPath) {
        if(urls == null || urls.isEmpty())
            return false;
        int validationCount = ntkViewerImageInitialValidationCount(urls.size());
        if(shouldTrustInitialNtkViewerImageUrlsWithoutProbe(refererPath)
                && areInitialNtkViewerImageUrlsTrustedCdn(urls, validationCount)) {
            Log.d(TAG, "ntk_images_api_trusted_cdn_urls path=" + refererPath
                    + ",count=" + urls.size()
                    + ",validated=" + validationCount);
            return true;
        }
        for(int i = 0; i < validationCount; i++) {
            if(!isNtkViewerImageUrlReachable(urls.get(i), baseUrl, refererPath, i))
                return false;
        }
        return true;
    }

    private static boolean areInitialNtkViewerImageUrlsTrustedCdn(List<String> urls, int validationCount) {
        if(urls == null || urls.isEmpty() || validationCount <= 0)
            return false;
        for(int i = 0; i < validationCount; i++) {
            if(i >= urls.size() || !isTrustedNtkViewerApiCdnImageUrl(urls.get(i)))
                return false;
        }
        return true;
    }

    private static boolean shouldTrustInitialNtkViewerImageUrlsWithoutProbe(String refererPath) {
        return false;
    }

    private static boolean isTrustedNtkViewerApiCdnImageUrl(String url) {
        if(url == null || url.length() == 0)
            return false;
        HttpUrl parsed = HttpUrl.parse(url);
        if(parsed == null || !"https".equals(parsed.scheme()))
            return false;
        String host = parsed.host() == null ? "" : parsed.host().toLowerCase(Locale.ROOT);
        String path = parsed.encodedPath() == null ? "" : parsed.encodedPath().toLowerCase(Locale.ROOT);
        if(isDisallowedNtkPrimaryImagePath(path))
            return false;
        return (host.matches("aws-cdn\\d*\\.site")
                || host.matches("flysky\\d*m\\.com")
                || "moamoabon.com".equals(host)
                || host.matches("fvcdn\\d*\\.com")
                || host.matches("\\d{5,10}\\.com"))
                && (isTrustedNtkPrimaryImagePath(path) || looksLikeRootHashImage(path));
    }

    static int ntkViewerImageInitialValidationCountForTest(int imageCount) {
        return ntkViewerImageInitialValidationCount(imageCount);
    }

    private static int ntkViewerImageInitialValidationCount(int imageCount) {
        if(imageCount <= 1)
            return imageCount;
        return Math.min(imageCount, 2);
    }

    private boolean isNtkViewerImageUrlReachable(String url, String baseUrl, String refererPath, int index) {
        if(url == null || url.length() == 0 || !url.startsWith("http"))
            return false;
        Response response = null;
        try {
            Map<String, String> headers = new HashMap<>();
            headers.put("accept", "image/avif,image/webp,image/apng,image/svg+xml,image/*,*/*;q=0.8");
            headers.put("User-Agent", agent);
            if(baseUrl != null && baseUrl.length() > 0)
                headers.put("referer", baseUrl + ntkNativeAckScopePath(refererPath));
            headers.put("range", "bytes=0-0");
            response = get(url, headers);
            if(isNtkViewerImageResponseReachable(response, url))
                return true;
            int firstCode = response == null ? 0 : response.code();
            if(response != null) {
                response.close();
                response = null;
            }
            if(firstCode == 403) {
                headers.remove("range");
                response = get(url, headers);
                if(isNtkViewerImageResponseReachable(response, url))
                    return true;
            }
            Log.d(TAG, "ntk_images_api_image_unreachable index=" + index
                    + ",code=" + (response == null ? firstCode : response.code())
                    + ",url=" + safeLogUrl(url));
            return false;
        } catch(Exception e) {
            Log.d(TAG, "ntk_images_api_image_unreachable_error index=" + index
                    + ",url=" + safeLogUrl(url)
                    + "," + e);
            return false;
        } finally {
            if(response != null)
                response.close();
        }
    }

    private static boolean isNtkViewerImageResponseReachable(Response response, String url) {
        if(response == null)
            return false;
        int code = response.code();
        String contentType = response.body() == null || response.body().contentType() == null
                ? "" : String.valueOf(response.body().contentType()).toLowerCase(Locale.ROOT);
        boolean imageLike = contentType.startsWith("image/")
                || contentType.startsWith("application/octet-stream") && looksLikeImageUrl(url);
        return (code >= 200 && code < 300 || code == 206) && imageLike;
    }

    private static boolean looksLikeImageUrl(String url) {
        if(url == null)
            return false;
        String lower = url.toLowerCase(Locale.ROOT);
        return lower.contains(".jpg") || lower.contains(".jpeg")
                || lower.contains(".png") || lower.contains(".webp")
                || lower.contains(".gif");
    }

    private static String safeLogUrl(String url) {
        if(url == null)
            return "";
        try {
            URI uri = URI.create(url);
            String host = uri.getHost();
            String path = uri.getPath();
            if(host == null || path == null)
                return "url";
            int slash = path.lastIndexOf('/');
            String file = slash >= 0 ? path.substring(slash + 1) : path;
            return host + "/" + file;
        } catch(Exception ignored) {
            return "url";
        }
    }

    private static String summarizeKeyId(String keyId) {
        if(keyId == null || keyId.length() == 0)
            return "";
        return keyId.length() <= 12 ? keyId : keyId.substring(0, 12);
    }

    private static boolean ntkPathScopesEqual(String scope, String path) {
        if(scope == null || path == null || scope.length() == 0 || path.length() == 0)
            return false;
        if(scope.equals(path))
            return true;
        String encodedPath = ntkNativeAckScopePath(path);
        if(scope.equals(encodedPath))
            return true;
        String encodedScope = ntkNativeAckScopePath(scope);
        if(encodedScope.equals(path) || encodedScope.equals(encodedPath))
            return true;
        return ntkDecodePath(scope).equals(ntkDecodePath(path));
    }

    private static String ntkTitleScopePath(String path) {
        if(path == null || path.length() == 0)
            return "";
        Matcher matcher = Pattern.compile("^/(manhwa|webtoon)/([^/?#]+)(?:/[^/?#]+)?").matcher(path);
        if(!matcher.find())
            return "";
        return "/" + matcher.group(1) + "/" + matcher.group(2);
    }

    private static String ntkNativeAckScopePath(String path) {
        if(path == null || path.length() == 0)
            return "";
        String normalized = path.trim();
        int scheme = normalized.indexOf("://");
        if(scheme >= 0) {
            int slash = normalized.indexOf('/', scheme + 3);
            normalized = slash >= 0 ? normalized.substring(slash) : "/";
        }
        int query = normalized.indexOf('?');
        String suffix = "";
        if(query >= 0) {
            suffix = normalized.substring(query);
            normalized = normalized.substring(0, query);
        }
        if(normalized.length() == 0 || normalized.charAt(0) != '/')
            normalized = "/" + normalized;
        String[] parts = normalized.split("/", -1);
        StringBuilder encoded = new StringBuilder(normalized.length() + 16);
        for(int i = 0; i < parts.length; i++) {
            if(i > 0)
                encoded.append('/');
            if(parts[i].length() == 0)
                continue;
            encoded.append(ntkEncodePathSegment(parts[i]));
        }
        return encoded.append(suffix).toString();
    }

    private static String ntkEncodePathSegment(String value) {
        try {
            String decoded = value.indexOf('%') >= 0 ? URLDecoder.decode(value, "UTF-8") : value;
            return URLEncoder.encode(decoded, "UTF-8").replace("+", "%20")
                    .replace("%21", "!")
                    .replace("%27", "'")
                    .replace("%28", "(")
                    .replace("%29", ")")
                    .replace("%7E", "~");
        } catch(Exception e) {
            return value;
        }
    }

    private static String ntkDecodePath(String value) {
        try {
            return URLDecoder.decode(value, "UTF-8");
        } catch(Exception e) {
            return value == null ? "" : value;
        }
    }

    private static boolean ntkPathContainsNonAscii(String value) {
        if(value == null)
            return false;
        for(int i = 0; i < value.length(); i++) {
            if(value.charAt(i) > 0x7f)
                return true;
        }
        return false;
    }

    private Future<NtkQuicFetcher.Result> startProactiveNtkAckSubmit(ExecutorService executor,
                                                                     HttpEngine engine,
                                                                     String baseUrl,
                                                                     String challengePath,
                                                                     Map<String, String> headers,
                                                                     byte[] ackBytes,
                                                                     JSONArray impressionUrls) {
        if(executor == null || impressionUrls == null || impressionUrls.length() == 0)
            return null;
        try {
            return executor.submit(() -> fetchNtkAckControlPost(engine, executor, baseUrl,
                    "/api/ad/ack", headers, ackBytes));
        } catch(Exception e) {
            Log.d(TAG, "ntk_native_ack_proactive_submit_error path=" + challengePath + "," + e);
            return null;
        }
    }

    private NtkQuicFetcher.Result collectProactiveNtkAckSubmit(Future<NtkQuicFetcher.Result> future,
                                                               String baseUrl,
                                                               String challengePath) {
        if(future == null)
            return null;
        try {
            NtkQuicFetcher.Result result = future.get(NTK_ACK_PROACTIVE_JOIN_TIMEOUT_MS, TimeUnit.MILLISECONDS);
            if(result != null)
                applySetCookieHeaders(result.headers, baseUrl);
            Log.d(TAG, "ntk_native_ack_proactive_collect code="
                    + (result == null ? "null" : result.code)
                    + ",path=" + challengePath);
            return result;
        } catch(InterruptedException e) {
            Thread.currentThread().interrupt();
            future.cancel(true);
            Log.d(TAG, "ntk_native_ack_proactive_cancelled path=" + challengePath);
            return null;
        } catch(TimeoutException e) {
            future.cancel(true);
            Log.d(TAG, "ntk_native_ack_proactive_timeout path=" + challengePath);
            return null;
        } catch(Exception e) {
            Log.d(TAG, "ntk_native_ack_proactive_error path=" + challengePath + "," + e);
            return null;
        }
    }

    private NtkAckParsed parseNtkAckResponse(NtkQuicFetcher.Result ack, String challengePath) {
        boolean ackBodyOk = false;
        String ackStatus = null;
        String ackError = null;
        if(ack != null && ack.body != null) {
            String bodyPreview = ack.body.substring(0, Math.min(500, ack.body.length()));
            Log.d(TAG, "ntk_native_ack_ack_body=" + bodyPreview + ",path=" + challengePath);
            try {
                JSONObject ackJson = new JSONObject(ack.body);
                ackBodyOk = ackJson.optBoolean("ok", false);
                ackStatus = ackJson.optString("status", null);
                ackError = ackJson.optString("error", null);
                if(!ackBodyOk && ackStatus == null && ackError == null) {
                    // Some endpoints return {acked:true} instead of {ok:true}
                    ackBodyOk = ackJson.optBoolean("acked", false);
                }
                Log.d(TAG, "ntk_native_ack_parsed ok=" + ackBodyOk
                        + ",status=" + ackStatus
                        + ",error=" + ackError
                        + ",path=" + challengePath);
            } catch(Exception parseEx) {
                Log.d(TAG, "ntk_native_ack_ack_parse_error=" + parseEx);
            }
        }
        return new NtkAckParsed(ackBodyOk, ackStatus, ackError);
    }

    private NtkAckCanaryResult performNtkAckCanary(HttpEngine engine, ExecutorService executor,
                                                   String baseUrl, String challengePath,
                                                   Map<String, String> headers, byte[] canaryBytes) {
        NtkQuicFetcher.Result canary = null;
        boolean canaryOk = false;
        try {
            for(int attempt = 0; attempt < NTK_ACK_REQUEST_ATTEMPTS; attempt++) {
                if(attempt > 0) Thread.sleep(NTK_ACK_RETRY_DELAY_MS * attempt);
                canary = fetchNtkAckControlPost(engine, executor, baseUrl, "/api/ad/canary", headers, canaryBytes);
                Log.d(TAG, "ntk_native_ack_canary_code=" + (canary == null ? "null" : canary.code)
                        + ",attempt=" + attempt
                        + ",parallel=true,path=" + challengePath);
                if(canary != null && canary.body != null) {
                    Log.d(TAG, "ntk_native_ack_canary_body=" + canary.body.substring(0, Math.min(300, canary.body.length()))
                            + ",path=" + challengePath);
                }
                canaryOk = isNtkAckJsonOk(canary);
                if(canaryOk)
                    break;
                if(isNtkAckHardBlocked(canary))
                    break;
            }
        } catch(Exception e) {
            if(isInterruptedRequest(e)) {
                Thread.currentThread().interrupt();
                Log.d(TAG, "ntk_native_ack_canary_cancelled path=" + challengePath);
            } else {
                Log.d(TAG, "ntk_native_ack_canary_error path=" + challengePath + "," + e);
            }
        }
        return new NtkAckCanaryResult(canary, canaryOk);
    }

    private Future<NtkAckCanaryResult> startProactiveNtkAckCanary(ExecutorService executor,
                                                                  HttpEngine engine,
                                                                  String baseUrl,
                                                                  String challengePath,
                                                                  Map<String, String> headers,
                                                                  byte[] canaryBytes,
                                                                  JSONArray impressionUrls) {
        if(!shouldStartProactiveNtkAckCanary(impressionUrls))
            return null;
        try {
            return executor.submit(() -> performNtkAckCanary(engine, executor, baseUrl,
                    challengePath, headers, canaryBytes));
        } catch(Exception e) {
            Log.d(TAG, "ntk_native_ack_canary_proactive_submit_error path=" + challengePath + "," + e);
            return null;
        }
    }

    private NtkAckCanaryResult collectProactiveNtkAckCanary(Future<NtkAckCanaryResult> future,
                                                            String baseUrl,
                                                            String challengePath) {
        if(future == null)
            return null;
        try {
            NtkAckCanaryResult result = future.get(NTK_ACK_CANARY_JOIN_TIMEOUT_MS, TimeUnit.MILLISECONDS);
            if(result != null && result.canary != null)
                applySetCookieHeaders(result.canary.headers, baseUrl);
            Log.d(TAG, "ntk_native_ack_canary_proactive ok=" + (result != null && result.ok)
                    + ",path=" + challengePath);
            return result;
        } catch(InterruptedException e) {
            Thread.currentThread().interrupt();
            future.cancel(true);
            Log.d(TAG, "ntk_native_ack_canary_proactive_cancelled path=" + challengePath);
            return null;
        } catch(TimeoutException e) {
            future.cancel(true);
            Log.d(TAG, "ntk_native_ack_canary_proactive_timeout path=" + challengePath);
            return null;
        } catch(Exception e) {
            Log.d(TAG, "ntk_native_ack_canary_proactive_error path=" + challengePath + "," + e);
            return null;
        }
    }

    static boolean shouldStartProactiveNtkAckCanaryForTest(int impressionCount) {
        JSONArray impressions = new JSONArray();
        for(int i = 0; i < impressionCount; i++)
            impressions.put("/api/ad/imp/" + i);
        return shouldStartProactiveNtkAckCanary(impressions);
    }

    private static boolean shouldStartProactiveNtkAckCanary(JSONArray impressionUrls) {
        return false;
    }

    private byte[] ntkAckCanaryPayload(String token, String challengePath) throws Exception {
        JSONObject canaryPayload = new JSONObject();
        canaryPayload.put("adGuardLoaded", true);
        canaryPayload.put("adAckCanary", true);
        canaryPayload.put("challengeToken", token);
        canaryPayload.put("token", token);
        canaryPayload.put("path", challengePath);
        return canaryPayload.toString().getBytes(StandardCharsets.UTF_8);
    }

    private NtkQuicFetcher.Result fetchNtkAckControlPost(String baseUrl, String endpoint,
                                                         Map<String, String> headers, byte[] body) {
        // ACK control endpoints match WebView bridge behavior: fresh engine plus the full
        // challenge cookie jar. Filtering ad_ack_c here can strip the challenge cookie before
        // /api/ad/canary or /api/ad/ack has a chance to validate it.
        return NtkQuicFetcher.fetch(context, baseUrl + endpoint, agent,
                getCookieHeader(), headers, "POST", body, NTK_ACK_CONFIRM_TIMEOUT_MS);
    }

    private NtkQuicFetcher.Result fetchNtkAckControlPost(HttpEngine engine, ExecutorService executor,
                                                         String baseUrl, String endpoint,
                                                         Map<String, String> headers, byte[] body) {
        if("/api/ad/ack".equals(endpoint) && engine != null && executor != null) {
            long startedAt = System.currentTimeMillis();
            try {
                NtkQuicFetcher.Result result = fetchNtkAckControlPostSharedEarly(
                        engine, executor, baseUrl, endpoint, headers, body);
                Log.d(TAG, "ntk_native_ack_control_single_done endpoint=" + endpoint
                        + ",transport=shared-single"
                        + ",code=" + (result == null ? 0 : result.code)
                        + ",accepted=" + isNtkAckControlAccepted(endpoint, result)
                        + ",error=" + (result == null ? null : result.error)
                        + ",ms=" + (System.currentTimeMillis() - startedAt));
                if(isNtkAckControlAccepted(endpoint, result))
                    return result;
                NtkQuicFetcher.Result okhttp = fetchNtkAckControlPostOkHttp(baseUrl, endpoint, headers, body);
                Log.d(TAG, "ntk_native_ack_control_single_done endpoint=" + endpoint
                        + ",transport=okhttp-fallback"
                        + ",code=" + (okhttp == null ? 0 : okhttp.code)
                        + ",accepted=" + isNtkAckControlAccepted(endpoint, okhttp)
                        + ",error=" + (okhttp == null ? null : okhttp.error)
                        + ",ms=" + (System.currentTimeMillis() - startedAt));
                if(isNtkAckControlAccepted(endpoint, okhttp))
                    return okhttp;
                NtkQuicFetcher.Result fresh = fetchNtkAckControlPost(baseUrl, endpoint, headers, body);
                Log.d(TAG, "ntk_native_ack_control_single_done endpoint=" + endpoint
                        + ",transport=fresh-fallback"
                        + ",code=" + (fresh == null ? 0 : fresh.code)
                        + ",accepted=" + isNtkAckControlAccepted(endpoint, fresh)
                        + ",error=" + (fresh == null ? null : fresh.error)
                        + ",ms=" + (System.currentTimeMillis() - startedAt));
                if(isNtkAckControlAccepted(endpoint, fresh))
                    return fresh;
                return okhttp != null ? okhttp : (fresh != null ? fresh : result);
            } catch(InterruptedException e) {
                Thread.currentThread().interrupt();
                return null;
            } catch(Exception e) {
                Log.d(TAG, "ntk_native_ack_control_single_done endpoint=" + endpoint
                        + ",transport=shared-single"
                        + ",code=0,accepted=false,error=" + e
                        + ",ms=" + (System.currentTimeMillis() - startedAt));
                return null;
            }
        }
        if(engine != null && executor != null) {
            try {
                NtkQuicFetcher.Result result = NtkQuicFetcher.fetchWithEngine(engine, executor,
                        baseUrl + endpoint, agent, getCookieHeader(), headers, "POST", body,
                        NTK_ACK_CONFIRM_TIMEOUT_MS);
                if(result != null && result.error == null && result.code > 0)
                    return result;
            } catch(InterruptedException e) {
                Thread.currentThread().interrupt();
                return null;
            } catch(Exception ignored) {
            }
        }
        return fetchNtkAckControlPost(baseUrl, endpoint, headers, body);
    }

    private NtkQuicFetcher.Result fetchNtkAckControlPostHedged(HttpEngine engine, ExecutorService executor,
                                                               String baseUrl, String endpoint,
                                                               Map<String, String> headers, byte[] body) {
        long startedAt = System.currentTimeMillis();
        long deadlineMs = startedAt + NTK_ACK_CONTROL_MAX_WAIT_MS;
        ExecutorService hedgeExecutor = Executors.newFixedThreadPool(2, runnable -> {
            Thread thread = new Thread(runnable, "ntk-ack-control-hedge");
            thread.setDaemon(true);
            return thread;
        });
        ExecutorCompletionService<NtkAckControlResult> completion =
                new ExecutorCompletionService<>(hedgeExecutor);
        List<Future<NtkAckControlResult>> futures = new ArrayList<>();
        boolean okhttpStarted = false;
        NtkQuicFetcher.Result fallback = null;
        try {
            futures.add(completion.submit(() -> new NtkAckControlResult("shared",
                    "/api/ad/ack".equals(endpoint)
                            ? fetchNtkAckControlPostSharedEarly(engine, executor, baseUrl, endpoint, headers, body)
                            : NtkQuicFetcher.fetchWithEngine(engine, executor, baseUrl + endpoint,
                                    agent, getCookieHeader(), headers, "POST", body,
                                    NTK_ACK_CONFIRM_TIMEOUT_MS))));
            Future<NtkAckControlResult> first =
                    completion.poll(Math.min(NTK_ACK_CONTROL_HEDGE_DELAY_MS,
                            Math.max(1L, deadlineMs - System.currentTimeMillis())), TimeUnit.MILLISECONDS);
            if(first == null) {
                okhttpStarted = true;
                futures.add(completion.submit(() -> new NtkAckControlResult("okhttp",
                        fetchNtkAckControlPostOkHttp(baseUrl, endpoint, headers, body))));
            } else {
                NtkAckControlResult controlResult = first.get();
                NtkQuicFetcher.Result result = controlResult == null ? null : controlResult.result;
                if(result != null)
                    fallback = result;
                if(isNtkAckControlAccepted(endpoint, result)) {
                    logNtkAckControlHedgeDone(endpoint, controlResult, true, startedAt);
                    return result;
                }
                logNtkAckControlHedgeDone(endpoint, controlResult, false, startedAt);
            }
            int remaining = okhttpStarted ? 2 : 1;
            for(int completed = first == null ? 0 : 1; completed < remaining; completed++) {
                long remainingWaitMs = deadlineMs - System.currentTimeMillis();
                if(remainingWaitMs <= 0L)
                    break;
                Future<NtkAckControlResult> future =
                        completion.poll(Math.min(NTK_ACK_CONFIRM_TIMEOUT_MS, remainingWaitMs),
                                TimeUnit.MILLISECONDS);
                if(future == null)
                    break;
                NtkAckControlResult controlResult = future.get();
                NtkQuicFetcher.Result result = controlResult == null ? null : controlResult.result;
                if(result != null && fallback == null)
                    fallback = result;
                boolean accepted = isNtkAckControlAccepted(endpoint, result);
                logNtkAckControlHedgeDone(endpoint, controlResult, accepted, startedAt);
                if(accepted)
                    return result;
            }
            return fallback;
        } catch(InterruptedException e) {
            Thread.currentThread().interrupt();
            return null;
        } catch(Exception e) {
            Log.d(TAG, "ntk_native_ack_control_hedge_done endpoint=" + endpoint
                    + ",transport=unknown,code=0,accepted=false,error=" + e
                    + ",ms=" + (System.currentTimeMillis() - startedAt));
            return fetchNtkAckControlPost(baseUrl, endpoint, headers, body);
        } finally {
            for(Future<NtkAckControlResult> future : futures) {
                if(future != null && !future.isDone())
                    future.cancel(true);
            }
            hedgeExecutor.shutdown();
        }
    }

    private NtkQuicFetcher.Result fetchNtkAckControlPostOkHttp(String baseUrl, String endpoint,
                                                               Map<String, String> headers, byte[] bodyBytes) {
        return fetchNtkApiPostOkHttp(baseUrl, endpoint, headers, bodyBytes,
                "application/json; charset=utf-8");
    }

    private NtkQuicFetcher.Result fetchNtkApiPostOkHttp(String baseUrl, String endpoint,
                                                        Map<String, String> headers, byte[] bodyBytes,
                                                        String mediaType) {
        Response response = null;
        try {
            RequestBody requestBody = RequestBody.create(bodyBytes == null ? new byte[0] : bodyBytes,
                    MediaType.parse(mediaType == null ? "application/json; charset=utf-8" : mediaType));
            Request.Builder builder = new Request.Builder()
                    .url(baseUrl + endpoint)
                    .post(requestBody)
                    .addHeader("User-Agent", agent);
            if(headers != null) {
                for(String key : headers.keySet()) {
                    String value = headers.get(key);
                    if(value != null && value.length() > 0)
                        builder.header(key, value);
                }
            }
            String cookieHeader = getCookieHeader();
            if(cookieHeader != null && cookieHeader.length() > 0)
                builder.header("Cookie", cookieHeader);
            Request request = builder.build();
            try {
                response = ntkApiFastClient.newCall(request).execute();
            } catch(Exception firstError) {
                if(!isNtkSslTrustFailure(firstError) || unsafeNtkApiFastClient == null)
                    throw firstError;
                Log.d(TAG, "ntk_native_ack_post_okhttp_unsafe_retry endpoint=" + endpoint
                        + ",error=" + firstError.getClass().getSimpleName());
                response = unsafeNtkApiFastClient.newCall(request).execute();
            }
            byte[] bytes = response.body() == null ? new byte[0] : response.body().bytes();
            return NtkQuicFetcher.Result.fromBytes(response.code(), bytes, response.headers().toMultimap());
        } catch(Exception e) {
            return NtkQuicFetcher.Result.error(e);
        } finally {
            if(response != null)
                response.close();
        }
    }

    private static String summarizeSetCookieNames(Map<String, List<String>> headers) {
        if(headers == null || headers.isEmpty())
            return "none";
        List<String> names = new ArrayList<>();
        for(String key : headers.keySet()) {
            if(!"set-cookie".equalsIgnoreCase(key))
                continue;
            List<String> values = headers.get(key);
            if(values == null)
                continue;
            for(String value : values) {
                if(value == null)
                    continue;
                int eq = value.indexOf('=');
                if(eq > 0)
                    names.add(value.substring(0, eq).trim());
            }
        }
        return names.isEmpty() ? "none" : names.toString();
    }

    private static String summarizeNtkBody(String body) {
        if(body == null || body.length() == 0)
            return "";
        String compact = body.replace('\n', ' ').replace('\r', ' ').trim();
        return compact.substring(0, Math.min(120, compact.length()));
    }

    private NtkQuicFetcher.Result fetchNtkAckControlPostSharedEarly(HttpEngine engine, ExecutorService executor,
                                                                    String baseUrl, String endpoint,
                                                                    Map<String, String> headers, byte[] body)
            throws InterruptedException {
        long headerStartedAt = System.currentTimeMillis();
        return NtkQuicFetcher.fetchWithEngineObserve(engine, executor,
                baseUrl + endpoint, agent, getCookieHeader(), headers, "POST", body,
                NTK_ACK_CONFIRM_TIMEOUT_MS,
                (code, responseHeaders, text) -> code == 200
                        && text != null
                        && (text.contains("\"ok\":true")
                        || text.contains("\"error\":\"challenge_used\"")
                        || text.contains("\"error\":\"missing_impression\"")
                        || text.contains("\"error\":\"missing_canary\"")),
                (code, responseHeaders) -> {
                    if(code == 200 && responseHeaders != null && !responseHeaders.isEmpty()) {
                        applySetCookieHeaders(responseHeaders, baseUrl);
                        Log.d(TAG, "ntk_native_ack_control_headers endpoint=" + endpoint
                                + ",code=" + code
                                + ",cookiePresent=" + (getCookie("ad_ack") != null)
                                + ",ms=" + (System.currentTimeMillis() - headerStartedAt));
                    }
                });
    }

    private boolean isNtkAckControlAccepted(String endpoint, NtkQuicFetcher.Result result) {
        return result != null && result.error == null && result.code == 200
                && (!"/api/ad/ack".equals(endpoint)
                || result.body != null && result.body.contains("\"ok\":true"));
    }

    private void logNtkAckControlHedgeDone(String endpoint, NtkAckControlResult controlResult,
                                           boolean accepted, long startedAt) {
        NtkQuicFetcher.Result result = controlResult == null ? null : controlResult.result;
        Log.d(TAG, "ntk_native_ack_control_hedge_done endpoint=" + endpoint
                + ",transport=" + (controlResult == null ? "unknown" : controlResult.transport)
                + ",code=" + (result == null ? 0 : result.code)
                + ",accepted=" + accepted
                + ",error=" + (result == null ? null : result.error)
                + ",ms=" + (System.currentTimeMillis() - startedAt));
    }

    private NtkQuicFetcher.Result fetchNtkAckControlPostSingle(HttpEngine engine, ExecutorService executor,
                                                               String baseUrl, String endpoint,
                                                               Map<String, String> headers, byte[] body) {
        long startedAt = System.currentTimeMillis();
        boolean ackEndpoint = "/api/ad/ack".equals(endpoint);
        try {
            NtkQuicFetcher.Result result = NtkQuicFetcher.fetchWithEngine(engine, executor,
                    baseUrl + endpoint, agent, getCookieHeader(), headers, "POST", body,
                    NTK_ACK_CONFIRM_TIMEOUT_MS);
            Log.d(TAG, "ntk_native_ack_control_single_done endpoint=" + endpoint
                    + ",transport=" + (ackEndpoint ? "shared-single" : "shared")
                    + ",code=" + (result == null ? 0 : result.code)
                    + ",error=" + (result == null ? null : result.error)
                    + ",ms=" + (System.currentTimeMillis() - startedAt));
            if(ackEndpoint && isNtkAckControlAccepted(endpoint, result))
                return result;
            if(result != null && result.error == null && result.code > 0)
                return result;
        } catch(InterruptedException e) {
            Thread.currentThread().interrupt();
            return null;
        } catch(Exception e) {
            Log.d(TAG, "ntk_native_ack_control_single_done endpoint=" + endpoint
                    + ",transport=shared,code=0,error=" + e
                    + ",ms=" + (System.currentTimeMillis() - startedAt));
        }
        NtkQuicFetcher.Result fallback = fetchNtkAckControlPost(baseUrl, endpoint, headers, body);
        Log.d(TAG, "ntk_native_ack_control_single_done endpoint=" + endpoint
                + ",transport=" + (ackEndpoint ? "fresh-fallback" : "fresh")
                + ",code=" + (fallback == null ? 0 : fallback.code)
                + ",error=" + (fallback == null ? null : fallback.error)
                + ",ms=" + (System.currentTimeMillis() - startedAt));
        return fallback;
    }

    private NtkQuicFetcher.Result fetchNtkAckControlPostQuicHedged(HttpEngine engine, ExecutorService executor,
                                                                   String baseUrl, String endpoint,
                                                                   Map<String, String> headers, byte[] body) {
        long startedAt = System.currentTimeMillis();
        ExecutorService hedgeExecutor = Executors.newFixedThreadPool(2, runnable -> {
            Thread thread = new Thread(runnable, "ntk-ack-control-quic-hedge");
            thread.setDaemon(true);
            return thread;
        });
        ExecutorCompletionService<NtkAckControlResult> completion =
                new ExecutorCompletionService<>(hedgeExecutor);
        int submitted = 0;
        boolean freshSubmitted = false;
        NtkQuicFetcher.Result fallback = null;
        try {
            completion.submit(() -> new NtkAckControlResult("shared-hedge",
                    NtkQuicFetcher.fetchWithEngine(engine, executor, baseUrl + endpoint,
                            agent, getCookieHeader(), headers, "POST", body,
                            NTK_ACK_CONFIRM_TIMEOUT_MS)));
            submitted++;
            for(int completed = 0; completed < submitted; completed++) {
                long waitMs = completed == 0 && !freshSubmitted
                        ? Math.min(120L, NTK_ACK_CONFIRM_TIMEOUT_MS)
                        : NTK_ACK_CONFIRM_TIMEOUT_MS;
                Future<NtkAckControlResult> future = completion.poll(waitMs, TimeUnit.MILLISECONDS);
                if(future == null) {
                    if(!freshSubmitted) {
                        completion.submit(() -> new NtkAckControlResult("fresh-hedge",
                                fetchNtkAckControlPost(baseUrl, endpoint, headers, body)));
                        submitted++;
                        freshSubmitted = true;
                        Log.d(TAG, "ntk_native_ack_control_hedge_start endpoint=" + endpoint
                                + ",ms=" + (System.currentTimeMillis() - startedAt));
                        continue;
                    }
                    break;
                }
                NtkAckControlResult controlResult = future.get();
                NtkQuicFetcher.Result result = controlResult == null ? null : controlResult.result;
                if(result != null && fallback == null)
                    fallback = result;
                boolean accepted = isNtkAckControlAccepted(endpoint, result);
                Log.d(TAG, "ntk_native_ack_control_quic_hedge_done endpoint=" + endpoint
                        + ",transport=" + (controlResult == null ? "unknown" : controlResult.transport)
                        + ",code=" + (result == null ? 0 : result.code)
                        + ",accepted=" + accepted
                        + ",error=" + (result == null ? null : result.error)
                        + ",ms=" + (System.currentTimeMillis() - startedAt));
                if(accepted)
                    return result;
            }
            return fallback;
        } catch(InterruptedException e) {
            Thread.currentThread().interrupt();
            return null;
        } catch(Exception e) {
            Log.d(TAG, "ntk_native_ack_control_quic_hedge_done endpoint=" + endpoint
                    + ",transport=unknown,code=0,accepted=false,error=" + e
                    + ",ms=" + (System.currentTimeMillis() - startedAt));
            return fetchNtkAckControlPost(baseUrl, endpoint, headers, body);
        } finally {
            hedgeExecutor.shutdown();
        }
    }

    private NtkQuicFetcher.Result fetchNtkAckControlPostRace(HttpEngine engine, ExecutorService executor,
                                                             String baseUrl, String endpoint,
                                                             Map<String, String> headers, byte[] body) {
        long startedAt = System.currentTimeMillis();
        ExecutorService raceExecutor = Executors.newFixedThreadPool(2, runnable -> {
            Thread thread = new Thread(runnable, "ntk-ack-control-race");
            thread.setDaemon(true);
            return thread;
        });
        ExecutorCompletionService<NtkAckControlResult> completion =
                new ExecutorCompletionService<>(raceExecutor);
        List<Future<NtkAckControlResult>> futures = new ArrayList<>();
        try {
            futures.add(completion.submit(() -> new NtkAckControlResult("shared",
                    NtkQuicFetcher.fetchWithEngine(engine, executor, baseUrl + endpoint,
                            agent, getCookieHeader(), headers, "POST", body,
                            NTK_ACK_CONFIRM_TIMEOUT_MS))));
            futures.add(completion.submit(() -> new NtkAckControlResult("fresh",
                    fetchNtkAckControlPost(baseUrl, endpoint, headers, body))));
            NtkQuicFetcher.Result fallback = null;
            for(int completed = 0; completed < futures.size(); completed++) {
                Future<NtkAckControlResult> future =
                        completion.poll(NTK_ACK_CONFIRM_TIMEOUT_MS, TimeUnit.MILLISECONDS);
                if(future == null)
                    break;
                NtkAckControlResult raceResult = future.get();
                NtkQuicFetcher.Result result = raceResult == null ? null : raceResult.result;
                boolean accepted = result != null && result.error == null && result.code == 200
                        && (!"/api/ad/ack".equals(endpoint)
                        || result.body != null && result.body.contains("\"ok\":true"));
                Log.d(TAG, "ntk_native_ack_control_race_done endpoint=" + endpoint
                        + ",transport=" + (raceResult == null ? "unknown" : raceResult.transport)
                        + ",code=" + (result == null ? 0 : result.code)
                        + ",accepted=" + accepted
                        + ",error=" + (result == null ? null : result.error)
                        + ",ms=" + (System.currentTimeMillis() - startedAt));
                if(result != null && fallback == null)
                    fallback = result;
                if(accepted)
                    return result;
            }
            return fallback;
        } catch(InterruptedException e) {
            Thread.currentThread().interrupt();
            return null;
        } catch(Exception e) {
            Log.d(TAG, "ntk_native_ack_control_race_done endpoint=" + endpoint
                    + ",transport=shared,code=0,error=" + e
                    + ",ms=" + (System.currentTimeMillis() - startedAt));
            return fetchNtkAckControlPost(baseUrl, endpoint, headers, body);
        } finally {
            for(Future<NtkAckControlResult> future : futures) {
                if(future != null && !future.isDone())
                    future.cancel(true);
            }
            raceExecutor.shutdown();
        }
    }

    private static final class NtkAckControlResult {
        final String transport;
        final NtkQuicFetcher.Result result;

        NtkAckControlResult(String transport, NtkQuicFetcher.Result result) {
            this.transport = transport;
            this.result = result;
        }
    }

    private int fetchNtkAckImpressions(HttpEngine engine, ExecutorService executor, String baseUrl,
                                       String challengePath, JSONArray impressionUrls, int minSeen) {
        if(executor == null || impressionUrls == null || impressionUrls.length() == 0)
            return 0;
        List<String> urls = new ArrayList<>();
        for(int i = 0; i < impressionUrls.length(); i++) {
            String rawUrl = impressionUrls.optString(i, "");
            if(rawUrl.length() == 0)
                continue;
            urls.add(rawUrl.startsWith("http") ? rawUrl : baseUrl + rawUrl);
        }
        if(urls.isEmpty())
            return 0;
        ExecutorService impressionExecutor = Executors.newFixedThreadPool(
                Math.max(2, Math.min(8, urls.size() * 2)), runnable -> {
            Thread thread = new Thread(runnable, "ntk-ack-impression");
            thread.setDaemon(true);
            return thread;
        });
        ExecutorCompletionService<NtkImpressionResult> completion =
                new ExecutorCompletionService<>(impressionExecutor);
        List<Future<NtkImpressionResult>> futures = new ArrayList<>();
        int targetSeen = Math.max(1, Math.min(minSeen <= 0 ? 2 : minSeen, urls.size()));
        int submitted = 0;
        int completed = 0;
        int initialSubmit = urls.size();
        while(submitted < initialSubmit) {
            submitNtkAckImpression(completion, futures, engine, executor, challengePath, urls.get(submitted), submitted);
            submitted++;
        }
        int seen = 0;
        boolean freshSubmitted = false;
        long deadlineMs = System.currentTimeMillis() + NTK_ACK_IMPRESSION_MIN_WAIT_MS;
        while(completed < submitted) {
            try {
                long remainingMs = deadlineMs - System.currentTimeMillis();
                if(remainingMs <= 0L)
                    break;
                Future<NtkImpressionResult> future = completion.poll(
                        Math.min(completed == 0 && targetSeen <= 1
                                ? 120L : (completed == 0 ? 420L : 80L), remainingMs),
                        TimeUnit.MILLISECONDS);
                if(future == null) {
                    if(!freshSubmitted) {
                        int baseIndex = urls.size();
                        for(int i = 0; i < urls.size(); i++) {
                            submitNtkAckImpressionFresh(completion, futures, baseUrl, challengePath,
                                    urls.get(i), baseIndex + i);
                            submitted++;
                        }
                        freshSubmitted = true;
                        Log.d(TAG, "ntk_native_ack_imp_fresh_fallback_start path=" + challengePath
                                + ",submitted=" + submitted);
                        continue;
                    }
                    if(submitted < urls.size()) {
                        submitNtkAckImpression(completion, futures, engine, executor, challengePath,
                                urls.get(submitted), submitted);
                        submitted++;
                        continue;
                    }
                    continue;
                }
                completed++;
                NtkImpressionResult result = future.get();
                int code = result.result == null ? 0 : result.result.code;
                Log.d(TAG, "ntk_native_ack_imp i=" + result.index
                        + ",attempt=0,code=" + code
                        + ",parallel=true,path=" + challengePath);
                if(result.result != null)
                    applySetCookieHeaders(result.result.headers, baseUrl);
                if(code >= 200 && code < 300)
                    seen++;
                if((code < 200 || code >= 300) && result.error != null) {
                    if(isInterruptedRequest(result.error)) {
                        Log.d(TAG, "ntk_native_ack_imp_cancelled i=" + result.index
                                + ",attempt=0,path=" + challengePath);
                    } else {
                        Log.d(TAG, "ntk_native_ack_imp_error i=" + result.index
                                + ",attempt=0,path=" + challengePath
                                + "," + result.error);
                    }
                }
                if(seen >= targetSeen)
                    break;
                if(completed >= submitted && submitted < urls.size()) {
                    submitNtkAckImpression(completion, futures, engine, executor, challengePath,
                            urls.get(submitted), submitted);
                    submitted++;
                }
            } catch(Exception e) {
                if(isInterruptedRequest(e)) {
                    Thread.currentThread().interrupt();
                    Log.d(TAG, "ntk_native_ack_imp_collect_cancelled path=" + challengePath);
                    break;
                }
                Log.d(TAG, "ntk_native_ack_imp_collect_error path=" + challengePath + "," + e);
                if(submitted < urls.size()) {
                    submitNtkAckImpression(completion, futures, engine, executor, challengePath,
                            urls.get(submitted), submitted);
                    submitted++;
                }
            }
        }
        if(seen >= targetSeen) {
            for(Future<NtkImpressionResult> future : futures) {
                if(future != null && !future.isDone())
                    future.cancel(true);
            }
            impressionExecutor.shutdownNow();
        } else {
            impressionExecutor.shutdown();
        }
        Log.d(TAG, "ntk_native_ack_imp_seen seen=" + seen
                + ",target=" + targetSeen
                + ",submitted=" + submitted
                + ",path=" + challengePath);
        return seen;
    }

    private void submitNtkAckImpression(ExecutorCompletionService<NtkImpressionResult> completion,
                                        List<Future<NtkImpressionResult>> futures,
                                        HttpEngine engine,
                                        ExecutorService executor,
                                        String challengePath,
                                        String impressionUrl,
                                        int index) {
        futures.add(completion.submit(() -> {
            try {
                NtkQuicFetcher.Result imp = null;
                if(engine != null) {
                    try {
                        imp = NtkQuicFetcher.fetchWithEngine(engine, executor, impressionUrl, agent,
                                getCookieHeader(), Collections.emptyMap(), "GET", null, NTK_ACK_CONFIRM_TIMEOUT_MS);
                    } catch(Exception e) {
                        Log.d(TAG, "ntk_native_ack_imp_shared_engine_error i=" + index
                                + ",path=" + challengePath
                                + "," + e);
                    }
                }
                if(imp == null || imp.error != null || imp.code <= 0) {
                    imp = NtkQuicFetcher.fetch(context, impressionUrl, agent,
                            getCookieHeader(), Collections.emptyMap(), "GET", null, NTK_ACK_CONFIRM_TIMEOUT_MS);
                }
                return new NtkImpressionResult(index, impressionUrl, imp, null);
            } catch(Exception e) {
                return new NtkImpressionResult(index, impressionUrl, null, e);
            }
        }));
    }

    private void submitNtkAckImpressionFresh(ExecutorCompletionService<NtkImpressionResult> completion,
                                             List<Future<NtkImpressionResult>> futures,
                                             String baseUrl,
                                             String challengePath,
                                             String impressionUrl,
                                             int index) {
        futures.add(completion.submit(() -> {
            try {
                NtkQuicFetcher.Result imp = NtkQuicFetcher.fetch(context, impressionUrl, agent,
                        getCookieHeader(), Collections.emptyMap(), "GET", null, NTK_ACK_CONFIRM_TIMEOUT_MS);
                return new NtkImpressionResult(index, impressionUrl, imp, null);
            } catch(Exception e) {
                return new NtkImpressionResult(index, impressionUrl, null, e);
            }
        }));
    }

    private static final class NtkAckCanaryResult {
        final NtkQuicFetcher.Result canary;
        final boolean ok;

        NtkAckCanaryResult(NtkQuicFetcher.Result canary, boolean ok) {
            this.canary = canary;
            this.ok = ok;
        }
    }

    private static final class NtkAckParsed {
        final boolean ok;
        final String status;
        final String error;

        NtkAckParsed(boolean ok, String status, String error) {
            this.ok = ok;
            this.status = status;
            this.error = error;
        }
    }

    private static final class NtkImpressionResult {
        final int index;
        final String url;
        final NtkQuicFetcher.Result result;
        final Exception error;

        NtkImpressionResult(int index, String url, NtkQuicFetcher.Result result, Exception error) {
            this.index = index;
            this.url = url;
            this.result = result;
            this.error = error;
        }
    }

    private void dumpNtkAckDebugArtifacts(String baseUrl, String path) {
        try {
            NtkQuicFetcher.Result jsResult = NtkQuicFetcher.fetch(context, baseUrl + "/wasm/ad-guard/ad_guard.js", agent,
                    getCookieHeader(), Collections.emptyMap(), "GET", null, 30000L);
            Log.d(TAG, "ntk_js_fetch_result=" + (jsResult == null ? "null"
                    : ("code=" + jsResult.code + ",len=" + (jsResult.bodyBytes == null ? 0 : jsResult.bodyBytes.length)
                    + ",err=" + jsResult.error)));
            if(jsResult != null && jsResult.bodyBytes != null && jsResult.bodyBytes.length > 100) {
                java.io.File dir = context.getExternalFilesDir(null);
                java.io.File out = new java.io.File(dir, "ntk_ad_guard_debug.js");
                java.io.FileOutputStream fos = new java.io.FileOutputStream(out);
                fos.write(jsResult.bodyBytes);
                fos.close();
                Log.d(TAG, "ntk_ad_guard_saved=" + jsResult.bodyBytes.length + ",path=" + out.getAbsolutePath());
            }
            NtkQuicFetcher.Result wasmResult = NtkQuicFetcher.fetch(context, baseUrl + "/wasm/ad-guard/ad_guard_bg.wasm", agent,
                    getCookieHeader(), Collections.emptyMap(), "GET", null, 30000L);
            Log.d(TAG, "ntk_wasm_fetch_result=" + (wasmResult == null ? "null"
                    : ("code=" + wasmResult.code + ",len=" + (wasmResult.bodyBytes == null ? 0 : wasmResult.bodyBytes.length)
                    + ",err=" + wasmResult.error)));
            if(wasmResult != null && wasmResult.bodyBytes != null && wasmResult.bodyBytes.length > 100) {
                java.io.File dir = context.getExternalFilesDir(null);
                java.io.File out = new java.io.File(dir, "ntk_ad_guard_bg_debug.wasm");
                java.io.FileOutputStream fos = new java.io.FileOutputStream(out);
                fos.write(wasmResult.bodyBytes);
                fos.close();
                Log.d(TAG, "ntk_wasm_saved=" + wasmResult.bodyBytes.length + ",path=" + out.getAbsolutePath());
            }
        } catch(Exception e) {
            Log.d(TAG, "ntk_js_wasm_fetch_exception=" + e);
        }

        try {
            NtkQuicFetcher.Result pageResult = NtkQuicFetcher.fetch(context, baseUrl + path, agent,
                    getCookieHeader(), Collections.emptyMap(), "GET", null, 30000L);
            if(pageResult != null && pageResult.bodyBytes != null && pageResult.bodyBytes.length > 100) {
                java.io.File dir = context.getExternalFilesDir(null);
                java.io.File out = new java.io.File(dir, "ntk_page_debug.html");
                java.io.FileOutputStream fos = new java.io.FileOutputStream(out);
                fos.write(pageResult.bodyBytes);
                fos.close();
                Log.d(TAG, "ntk_page_saved=" + pageResult.bodyBytes.length + ",path=" + out.getAbsolutePath());
            }
        } catch(Exception e) {
            Log.d(TAG, "ntk_page_fetch_exception=" + e);
        }

        try {
            String[] jsUrls = {
                    "/b1780036697285/_next/static/chunks/app/layout-c623dbde1bffd5e8.js",
                    "/b1780036697285/_next/static/chunks/app/manhwa/%5BsourceWorkId%5D/page-0fdb2189efc997b5.js",
                    "/b1780036697285/_next/static/chunks/app/manhwa/%5BsourceWorkId%5D/%5BepisodeId%5D/page-6f3569cfd55e262d.js"
            };
            for(String jsPath : jsUrls) {
                NtkQuicFetcher.Result r = NtkQuicFetcher.fetch(context, baseUrl + jsPath, agent,
                        getCookieHeader(), Collections.emptyMap(), "GET", null, 30000L);
                if(r != null && r.bodyBytes != null && r.bodyBytes.length > 100) {
                    java.io.File dir = context.getExternalFilesDir(null);
                    java.io.File out = new java.io.File(dir, jsPath.replaceAll("[^a-zA-Z0-9_-]", "_") + ".js");
                    java.io.FileOutputStream fos = new java.io.FileOutputStream(out);
                    fos.write(r.bodyBytes);
                    fos.close();
                    Log.d(TAG, "ntk_bundle_saved=" + r.bodyBytes.length + ",path=" + out.getAbsolutePath());
                }
            }
        } catch(Exception e) {
            Log.d(TAG, "ntk_bundle_fetch_exception=" + e);
        }
    }

    private synchronized void applySetCookieHeaders(Map<String, List<String>> headers, String url) {
        if(headers == null || headers.size() == 0)
            return;
        boolean changed = false;
        List<String> changedCookies = new ArrayList<>();
        for(String key : headers.keySet()) {
            if(!"set-cookie".equalsIgnoreCase(key))
                continue;
            List<String> values = headers.get(key);
            if(values == null)
                continue;
            for(String value : values) {
                if(value == null)
                    continue;
                int eq = value.indexOf('=');
                if(eq <= 0)
                    continue;
                int end = value.indexOf(';', eq + 1);
                String name = value.substring(0, eq).trim();
                String cookieValue = value.substring(eq + 1, end >= 0 ? end : value.length()).trim();
                if(isNtkAckCookieName(name) && isExpiredNtkAckCookie(cookieValue)) {
                    if(cookies.remove(name) != null)
                        changed = true;
                    expireWebViewCookie(url, name);
                    continue;
                }
                if(name.length() > 0 && cookieValue.length() > 0 && !cookieValue.equals(cookies.get(name))) {
                    cookies.put(name, cookieValue);
                    changed = true;
                    changedCookies.add(name + "=" + cookieValue);
                }
            }
        }
        if(changed) {
            invalidateCookieHeaderCache();
            persistCookies();
            if(url != null && url.length() > 0 && !changedCookies.isEmpty()) {
                try {
                    android.webkit.CookieManager manager = android.webkit.CookieManager.getInstance();
                    for(String cookie : changedCookies) {
                        manager.setCookie(url, cookie);
                    }
                } catch(Exception e) {
                    if(Log.isLoggable(TAG, Log.DEBUG))
                        Log.d(TAG, "ntk_cookie_push_to_webview_error=" + e);
                }
            }
        }
    }

    private static byte[] randomBytes(int length) {
        byte[] bytes = new byte[length];
        new SecureRandom().nextBytes(bytes);
        return bytes;
    }

    private static String hmacSha256Base64Url(String key, String value) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(key.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        return base64Url(mac.doFinal(value.getBytes(StandardCharsets.UTF_8)));
    }

    private static String sha256Base64Url(String value) throws Exception {
        java.security.MessageDigest digest = java.security.MessageDigest.getInstance("SHA-256");
        return base64Url(digest.digest((value == null ? "" : value).getBytes(StandardCharsets.UTF_8)));
    }

    private static String base64Url(byte[] bytes) {
        return Base64.encodeToString(bytes, Base64.URL_SAFE | Base64.NO_WRAP | Base64.NO_PADDING);
    }

    private static byte[] fixedUnsigned32(java.math.BigInteger value) {
        byte[] raw = value == null ? new byte[0] : value.toByteArray();
        byte[] out = new byte[32];
        int copy = Math.min(raw.length, 32);
        System.arraycopy(raw, raw.length - copy, out, 32 - copy, copy);
        return out;
    }

    private static byte[] signNtkViewerP1363(java.security.interfaces.ECPrivateKey privateKey,
                                             byte[] message) throws Exception {
        try {
            java.security.Signature signature =
                    java.security.Signature.getInstance("SHA256withECDSAinP1363Format");
            signature.initSign(privateKey);
            signature.update(message);
            return signature.sign();
        } catch (java.security.NoSuchAlgorithmException ignored) {
            return derToP1363(signNtkViewerDer(privateKey, message));
        }
    }

    private static byte[] signNtkViewerDer(java.security.interfaces.ECPrivateKey privateKey,
                                           byte[] message) throws Exception {
        java.security.Signature signature = java.security.Signature.getInstance("SHA256withECDSA");
        signature.initSign(privateKey);
        signature.update(message);
        return signature.sign();
    }

    private static byte[] derToP1363(byte[] der) throws Exception {
        if(der == null || der.length < 8 || der[0] != 0x30)
            throw new IllegalArgumentException("bad der signature");
        int index = 2;
        if((der[1] & 0xff) > 0x80)
            index = 2 + (der[1] & 0x7f);
        if(index >= der.length || der[index++] != 0x02)
            throw new IllegalArgumentException("bad der r");
        int rLen = der[index++] & 0xff;
        byte[] r = new byte[rLen];
        System.arraycopy(der, index, r, 0, rLen);
        index += rLen;
        if(index >= der.length || der[index++] != 0x02)
            throw new IllegalArgumentException("bad der s");
        int sLen = der[index++] & 0xff;
        byte[] s = new byte[sLen];
        System.arraycopy(der, index, s, 0, sLen);
        byte[] out = new byte[64];
        byte[] rr = fixedUnsigned32(new java.math.BigInteger(1, r));
        byte[] ss = fixedUnsigned32(new java.math.BigInteger(1, s));
        System.arraycopy(rr, 0, out, 0, 32);
        System.arraycopy(ss, 0, out, 32, 32);
        return out;
    }

    private static boolean normalizeP1363LowS(byte[] signature) {
        if(signature == null || signature.length != 64)
            return false;
        byte[] sBytes = new byte[32];
        System.arraycopy(signature, 32, sBytes, 0, 32);
        java.math.BigInteger s = new java.math.BigInteger(1, sBytes);
        if(s.compareTo(P256_HALF_ORDER) <= 0)
            return false;
        byte[] normalized = fixedUnsigned32(P256_ORDER.subtract(s));
        System.arraycopy(normalized, 0, signature, 32, 32);
        return true;
    }

    public Response post(String url, RequestBody body, Map<String,String> headers, boolean localCookies){
        if(headers == null)
            headers = new HashMap<>();

        if(localCookies)
            syncCookiesFromWebView(getBaseUrl(url));

        StringBuilder cs = new StringBuilder();
        //get cookies from headers
        if(headers.get("Cookie") != null)
            cs.append(headers.get("Cookie"));

        // add local cookies
        if(localCookies)
            synchronized (this) {
                for(String key : this.cookies.keySet()){
                    cs.append(key).append('=').append(this.cookies.get(key)).append("; ");
                }
            }

        headers.put("Cookie", cs.toString());

        Response response = null;
        Call call = null;
        RequestGroup requestGroup = currentRequestGroup.get();
        try {
            Request.Builder builder = new Request.Builder()
                    .addHeader("User-Agent", agent)
                    .url(url)
                    .post(body);

            for(String key: headers.keySet()){
                builder.addHeader(key, headers.get(key));
            }

            Request request = builder.build();
            call = this.client.newCall(request);
            if(requestGroup != null)
                requestGroup.add(call);
            response = call.execute();
            storeResponseCookies(response);
        }catch (Exception e){
            if(!isInterruptedRequest(e) && (requestGroup == null || !requestGroup.isCancelled()))
                ml.melun.mangaview.report.CrashReporter.record(e);
        } finally {
            if(requestGroup != null && call != null)
                requestGroup.remove(call);
        }
        return response;

    }

    private static boolean isInterruptedRequest(Exception e) {
        return e instanceof InterruptedException
                || e instanceof InterruptedIOException
                || Thread.currentThread().isInterrupted()
                || "Canceled".equals(e.getMessage());
    }

    static boolean isInterruptedRequestForTest(Exception e) {
        return isInterruptedRequest(e);
    }

    private static final class SniFragmentingSocketFactory extends javax.net.SocketFactory {
        private final javax.net.SocketFactory delegate;

        SniFragmentingSocketFactory(javax.net.SocketFactory delegate) {
            this.delegate = delegate;
        }

        @Override
        public Socket createSocket() throws IOException {
            return new SniFragmentingSocket(delegate.createSocket());
        }

        @Override
        public Socket createSocket(String host, int port) throws IOException {
            return new SniFragmentingSocket(delegate.createSocket(host, port));
        }

        @Override
        public Socket createSocket(String host, int port, InetAddress localHost, int localPort) throws IOException {
            return new SniFragmentingSocket(delegate.createSocket(host, port, localHost, localPort));
        }

        @Override
        public Socket createSocket(InetAddress host, int port) throws IOException {
            return new SniFragmentingSocket(delegate.createSocket(host, port));
        }

        @Override
        public Socket createSocket(InetAddress address, int port, InetAddress localAddress, int localPort) throws IOException {
            return new SniFragmentingSocket(delegate.createSocket(address, port, localAddress, localPort));
        }
    }

    private static final class SniFragmentingSocket extends Socket {
        private final Socket delegate;
        private OutputStream outputStream;

        SniFragmentingSocket(Socket delegate) {
            this.delegate = delegate;
        }

        @Override
        public void connect(SocketAddress endpoint) throws IOException {
            delegate.connect(endpoint);
            enableLowLatencyWrites();
        }

        @Override
        public void connect(SocketAddress endpoint, int timeout) throws IOException {
            delegate.connect(endpoint, timeout);
            enableLowLatencyWrites();
        }

        private void enableLowLatencyWrites() {
            try {
                delegate.setTcpNoDelay(true);
            } catch (Exception ignored) {
            }
        }

        @Override
        public void bind(SocketAddress bindpoint) throws IOException {
            delegate.bind(bindpoint);
        }

        @Override
        public InetAddress getInetAddress() {
            return delegate.getInetAddress();
        }

        @Override
        public InetAddress getLocalAddress() {
            return delegate.getLocalAddress();
        }

        @Override
        public int getPort() {
            return delegate.getPort();
        }

        @Override
        public int getLocalPort() {
            return delegate.getLocalPort();
        }

        @Override
        public SocketAddress getRemoteSocketAddress() {
            return delegate.getRemoteSocketAddress();
        }

        @Override
        public SocketAddress getLocalSocketAddress() {
            return delegate.getLocalSocketAddress();
        }

        @Override
        public SocketChannel getChannel() {
            return delegate.getChannel();
        }

        @Override
        public InputStream getInputStream() throws IOException {
            return delegate.getInputStream();
        }

        @Override
        public OutputStream getOutputStream() throws IOException {
            if(outputStream == null)
                outputStream = new FragmentingTlsOutputStream(delegate, delegate.getOutputStream());
            return outputStream;
        }

        @Override
        public void setTcpNoDelay(boolean on) throws SocketException {
            delegate.setTcpNoDelay(on);
        }

        @Override
        public boolean getTcpNoDelay() throws SocketException {
            return delegate.getTcpNoDelay();
        }

        @Override
        public void setSoLinger(boolean on, int linger) throws SocketException {
            delegate.setSoLinger(on, linger);
        }

        @Override
        public int getSoLinger() throws SocketException {
            return delegate.getSoLinger();
        }

        @Override
        public void sendUrgentData(int data) throws IOException {
            delegate.sendUrgentData(data);
        }

        @Override
        public void setOOBInline(boolean on) throws SocketException {
            delegate.setOOBInline(on);
        }

        @Override
        public boolean getOOBInline() throws SocketException {
            return delegate.getOOBInline();
        }

        @Override
        public synchronized void setSoTimeout(int timeout) throws SocketException {
            delegate.setSoTimeout(timeout);
        }

        @Override
        public synchronized int getSoTimeout() throws SocketException {
            return delegate.getSoTimeout();
        }

        @Override
        public synchronized void setSendBufferSize(int size) throws SocketException {
            delegate.setSendBufferSize(size);
        }

        @Override
        public synchronized int getSendBufferSize() throws SocketException {
            return delegate.getSendBufferSize();
        }

        @Override
        public synchronized void setReceiveBufferSize(int size) throws SocketException {
            delegate.setReceiveBufferSize(size);
        }

        @Override
        public synchronized int getReceiveBufferSize() throws SocketException {
            return delegate.getReceiveBufferSize();
        }

        @Override
        public void setKeepAlive(boolean on) throws SocketException {
            delegate.setKeepAlive(on);
        }

        @Override
        public boolean getKeepAlive() throws SocketException {
            return delegate.getKeepAlive();
        }

        @Override
        public void setTrafficClass(int tc) throws SocketException {
            delegate.setTrafficClass(tc);
        }

        @Override
        public int getTrafficClass() throws SocketException {
            return delegate.getTrafficClass();
        }

        @Override
        public void setReuseAddress(boolean on) throws SocketException {
            delegate.setReuseAddress(on);
        }

        @Override
        public boolean getReuseAddress() throws SocketException {
            return delegate.getReuseAddress();
        }

        @Override
        public void close() throws IOException {
            delegate.close();
        }

        @Override
        public void shutdownInput() throws IOException {
            delegate.shutdownInput();
        }

        @Override
        public void shutdownOutput() throws IOException {
            delegate.shutdownOutput();
        }

        @Override
        public String toString() {
            return delegate.toString();
        }

        @Override
        public boolean isConnected() {
            return delegate.isConnected();
        }

        @Override
        public boolean isBound() {
            return delegate.isBound();
        }

        @Override
        public boolean isClosed() {
            return delegate.isClosed();
        }

        @Override
        public boolean isInputShutdown() {
            return delegate.isInputShutdown();
        }

        @Override
        public boolean isOutputShutdown() {
            return delegate.isOutputShutdown();
        }

        @Override
        public void setPerformancePreferences(int connectionTime, int latency, int bandwidth) {
            delegate.setPerformancePreferences(connectionTime, latency, bandwidth);
        }
    }

    private static final class FragmentingTlsOutputStream extends OutputStream {
        private final Socket socket;
        private final OutputStream delegate;
        private boolean firstTlsRecord = true;

        FragmentingTlsOutputStream(Socket socket, OutputStream delegate) {
            this.socket = socket;
            this.delegate = delegate;
        }

        @Override
        public void write(int b) throws IOException {
            firstTlsRecord = false;
            delegate.write(b);
        }

        @Override
        public void write(byte[] b, int off, int len) throws IOException {
            if(firstTlsRecord && looksLikeTlsClientHello(b, off, len)) {
                firstTlsRecord = false;
                fragmentClientHello(b, off, len);
                return;
            }
            if(len > 0)
                firstTlsRecord = false;
            delegate.write(b, off, len);
        }

        private void fragmentClientHello(byte[] b, int off, int len) throws IOException {
            try {
                socket.setTcpNoDelay(true);
            } catch (Exception ignored) {
            }
            int first = Math.min(1, len);
            int second = Math.min(7, Math.max(0, len - first));
            int third = Math.min(64, Math.max(0, len - first - second));
            writeChunk(b, off, first);
            shortDelay();
            writeChunk(b, off + first, second);
            shortDelay();
            writeChunk(b, off + first + second, third);
            shortDelay();
            int written = first + second + third;
            if(written < len)
                delegate.write(b, off + written, len - written);
            delegate.flush();
        }

        private void writeChunk(byte[] b, int off, int len) throws IOException {
            if(len <= 0)
                return;
            delegate.write(b, off, len);
            delegate.flush();
        }

        private void shortDelay() throws IOException {
            try {
                Thread.sleep(8L);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                InterruptedIOException interrupted = new InterruptedIOException("Interrupted while fragmenting TLS ClientHello");
                interrupted.initCause(e);
                throw interrupted;
            }
        }

        @Override
        public void flush() throws IOException {
            delegate.flush();
        }

        @Override
        public void close() throws IOException {
            delegate.close();
        }
    }

    private static boolean looksLikeTlsClientHello(byte[] b, int off, int len) {
        if(b == null || len < 6 || off < 0 || off + len > b.length)
            return false;
        return (b[off] & 0xff) == 0x16
                && (b[off + 1] & 0xff) == 0x03
                && (b[off + 5] & 0xff) == 0x01;
    }

    public interface RequestWork<T> {
        T run() throws Exception;
    }

    public static class RequestGroup {
        private final Set<Call> calls = java.util.Collections.synchronizedSet(new java.util.HashSet<>());
        private final Set<RequestGroup> children = java.util.Collections.synchronizedSet(new java.util.HashSet<>());
        private volatile boolean cancelled = false;
        private volatile boolean wolfWebViewFallback = false;
        private volatile boolean priorityWebViewFallback = false;
        private volatile boolean userVisible = false;

        public RequestGroup allowWolfWebViewFallback() {
            wolfWebViewFallback = true;
            return this;
        }

        public boolean allowsWolfWebViewFallback() {
            return wolfWebViewFallback;
        }

        public RequestGroup prioritizeWebViewFallback() {
            wolfWebViewFallback = true;
            priorityWebViewFallback = true;
            return this;
        }

        public RequestGroup clearWebViewFallbackPriority() {
            priorityWebViewFallback = false;
            return this;
        }

        public boolean prioritizesWebViewFallback() {
            return priorityWebViewFallback;
        }

        public RequestGroup userVisible() {
            userVisible = true;
            return this;
        }

        public boolean isUserVisible() {
            return userVisible;
        }

        void add(Call call) {
            synchronized (calls) {
                if(cancelled) {
                    call.cancel();
                    return;
                }
                calls.add(call);
            }
        }

        void remove(Call call) {
            calls.remove(call);
        }

        RequestGroup child() {
            RequestGroup child = new RequestGroup();
            child.wolfWebViewFallback = wolfWebViewFallback;
            child.priorityWebViewFallback = priorityWebViewFallback;
            child.userVisible = userVisible;
            synchronized (children) {
                if(cancelled) {
                    child.cancel();
                } else {
                    children.add(child);
                }
            }
            return child;
        }

        void removeChild(RequestGroup child) {
            if(child != null)
                children.remove(child);
        }

        public boolean isCancelled() {
            return cancelled;
        }

        public void cancel() {
            cancelled = true;
            synchronized (calls) {
                for(Call call : calls)
                    call.cancel();
                calls.clear();
            }
            synchronized (children) {
                for(RequestGroup child : children)
                    child.cancel();
                children.clear();
            }
        }
    }


    public Response post(String url, RequestBody body){
//        if(!isloaded){
//            cloudflareDns.create();
//            isloaded = true;
//        }
        return post(url, body, new HashMap<>());
    }

    /*
    code source : https://gist.github.com/chalup/8706740
     */

    private static OkHttpClient.Builder getUnsafeOkHttpClient() {
        try {
            // Create a trust manager that does not validate certificate chains
            final TrustManager[] trustAllCerts = new TrustManager[]{
                    new X509TrustManager() {
                        @Override
                        public void checkClientTrusted(java.security.cert.X509Certificate[] chain,
                                                       String authType){
                        }

                        @Override
                        public void checkServerTrusted(java.security.cert.X509Certificate[] chain,
                                                       String authType){
                        }

                        @Override
                        public java.security.cert.X509Certificate[] getAcceptedIssuers() {
                            return new X509Certificate[0];
                        }
                    }
            };

            // Install the all-trusting trust manager
            final SSLContext sslContext = SSLContext.getInstance("SSL");
            sslContext.init(null, trustAllCerts, new java.security.SecureRandom());
            // Create an ssl socket factory with our all-trusting manager
            final SSLSocketFactory sslSocketFactory = sslContext.getSocketFactory();

            return new OkHttpClient.Builder()
                    .sslSocketFactory(sslSocketFactory, (X509TrustManager) trustAllCerts[0])
                    .hostnameVerifier((hostname, session) -> true);

        } catch (Exception e) {
            throw new RuntimeException(e);
        }

    }

    private static OkHttpClient.Builder configureDispatcher(OkHttpClient.Builder builder) {
        return configureDispatcher(builder, MAX_HTTP_REQUESTS, MAX_HTTP_REQUESTS_PER_HOST);
    }

    private static OkHttpClient.Builder configureDispatcher(OkHttpClient.Builder builder, int maxRequests, int maxRequestsPerHost) {
        Dispatcher dispatcher = new Dispatcher();
        dispatcher.setMaxRequests(maxRequests);
        dispatcher.setMaxRequestsPerHost(maxRequestsPerHost);
        return builder.dispatcher(dispatcher);
    }

    private static OkHttpClient.Builder baseClient(OkHttpClient.Builder builder) {
        OkHttpClient.Builder configured = configureDispatcher(builder)
                .followRedirects(false)
                .followSslRedirects(false)
                .connectTimeout(20, TimeUnit.SECONDS)
                .readTimeout(20, TimeUnit.SECONDS)
                .writeTimeout(20, TimeUnit.SECONDS)
                .callTimeout(25, TimeUnit.SECONDS)
                .connectionPool(SHARED_CONNECTION_POOL)
                .dns(NETWORK_RESILIENT_DNS);
        return configured;
    }

    private static OkHttpClient.Builder imageClient(OkHttpClient.Builder builder) {
        return configureDispatcher(baseClient(builder)
                .followRedirects(true)
                .followSslRedirects(true),
                MAX_IMAGE_HTTP_REQUESTS, MAX_IMAGE_HTTP_REQUESTS_PER_HOST);
    }

    private static OkHttpClient.Builder fastNtkPageClient(OkHttpClient.Builder builder) {
        return baseClient(builder.socketFactory(SNI_FRAGMENTING_SOCKET_FACTORY))
                .connectTimeout(NTK_PAGE_DIRECT_TIMEOUT_MS, TimeUnit.MILLISECONDS)
                .readTimeout(NTK_PAGE_DIRECT_TIMEOUT_MS, TimeUnit.MILLISECONDS)
                .callTimeout(NTK_PAGE_DIRECT_TIMEOUT_MS, TimeUnit.MILLISECONDS);
    }

    private static OkHttpClient.Builder fastNtkApiClient(OkHttpClient.Builder builder) {
        return baseClient(builder.socketFactory(SNI_FRAGMENTING_SOCKET_FACTORY))
                .connectTimeout(NTK_API_DIRECT_TIMEOUT_MS, TimeUnit.MILLISECONDS)
                .readTimeout(NTK_API_DIRECT_TIMEOUT_MS, TimeUnit.MILLISECONDS)
                .callTimeout(NTK_API_DIRECT_TIMEOUT_MS, TimeUnit.MILLISECONDS);
    }

    private static OkHttpClient.Builder fastWolfPageClient(OkHttpClient.Builder builder) {
        return baseClient(builder)
                .connectTimeout(WFWF_PAGE_CONNECT_TIMEOUT_MS, TimeUnit.MILLISECONDS)
                .readTimeout(WFWF_PAGE_READ_TIMEOUT_MS, TimeUnit.MILLISECONDS)
                .callTimeout(WFWF_PAGE_CALL_TIMEOUT_MS, TimeUnit.MILLISECONDS);
    }

    private static OkHttpClient.Builder fastWolfSearchClient(OkHttpClient.Builder builder) {
        return baseClient(builder)
                .connectTimeout(WFWF_SEARCH_CONNECT_TIMEOUT_MS, TimeUnit.MILLISECONDS)
                .readTimeout(WFWF_SEARCH_READ_TIMEOUT_MS, TimeUnit.MILLISECONDS)
                .callTimeout(WFWF_SEARCH_CALL_TIMEOUT_MS, TimeUnit.MILLISECONDS);
    }

    static long fastWolfSearchCallTimeoutMsForTest() {
        return WFWF_SEARCH_CALL_TIMEOUT_MS;
    }

    static long fastNtkApiDirectTimeoutMsForTest() {
        return NTK_API_DIRECT_TIMEOUT_MS;
    }

    static List<Protocol> ntkTlsFallbackProtocolsForTest() {
        return java.util.Collections.singletonList(Protocol.HTTP_1_1);
    }

    static boolean imageDispatcherIsWiderForTest() {
        return MAX_IMAGE_HTTP_REQUESTS > MAX_HTTP_REQUESTS
                && MAX_IMAGE_HTTP_REQUESTS_PER_HOST > MAX_HTTP_REQUESTS_PER_HOST;
    }

    static boolean clientsShareConnectionPoolForTest() {
        OkHttpClient page = fastWolfPageClient(new OkHttpClient.Builder()).build();
        OkHttpClient image = imageClient(new OkHttpClient.Builder()).build();
        return page.connectionPool() == image.connectionPool();
    }

}
