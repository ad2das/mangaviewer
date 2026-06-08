package ml.melun.mangaview.mangaview;


import android.content.Context;
import android.content.SharedPreferences;
import android.net.Uri;
import android.net.http.HttpEngine;
import android.net.http.QuicOptions;
import android.util.Base64;
import android.util.Log;
import android.webkit.CookieManager;

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
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
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
import ml.melun.mangaview.repository.CacheFileStore;
import ml.melun.mangaview.runtime.PerfTrace;

import static ml.melun.mangaview.MainApplication.p;
public class CustomHttpClient {
    private static final String TAG = "ViewerPerf";
    public static final String DEFAULT_COMIC_URL = "https://wfwf455.com/cm";
    public static final String WEBTOON_URL = "https://wfwf455.com";
    public static final String NTK_COMIC_URL = "https://sbxh4.com/manhwa";
    public static final String NTK_WEBTOON_URL = "https://sbxh4.com";
    public static final String NTK_REACHABLE_FALLBACK_URL = "https://ntk01.com";
    private static final String NTK_HOST = "sbxh4.com";
    private static final String PREVIOUS_NTK_HOST = "sbxh3.com";
    private static final String OLDER_NTK_HOST = "sbxh2.com";
    private static final String OLDEST_NTK_HOST = "sbxh1.com";
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
    private static final long NTK_QUIC_IMAGE_TIMEOUT_MS = 15_000L;
    private static final long NTK_VIEWER_IMAGES_API_TIMEOUT_MS = 2_100L;
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
    private static final int NTK_ACK_CHALLENGE_RACE_REQUESTS = 2;
    private static final long NTK_ACK_RETRY_DELAY_MS = 250L;
    private static final long NTK_ACK_CHALLENGE_TIMEOUT_MS = 2_500L;
    private static final long NTK_ACK_CHALLENGE_HEDGE_DELAY_MS = 80L;
    private static final long NTK_ACK_CONFIRM_TIMEOUT_MS = 5_000L;
    private static final long NTK_ACK_CANARY_JOIN_TIMEOUT_MS = 350L;
    public static final String NTK_DESKTOP_DOCUMENT_UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) "
            + "AppleWebKit/537.36 (KHTML, like Gecko) Chrome/148.0.0.0 Safari/537.36";
    private static final java.util.Map<String, Long> NTK_ACK_CACHE = new java.util.concurrent.ConcurrentHashMap<>();
    private static final java.util.Map<String, Object> NTK_ACK_LOCKS = new java.util.concurrent.ConcurrentHashMap<>();
    private static final String PAGE_CACHE_PREFIX = "httpPageCacheV1_";
    private static final String NTK_DNS_CACHE_PREFIX = "ntkDnsCacheV1_";
    private static final String CLOUDFLARE_DOH_HOST = "cloudflare-dns.com";
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
            List<InetAddress> protectedAddresses = ipv4OnlyOrEmpty(lookupCachedNtkDns(hostname, true));
            if(!protectedAddresses.isEmpty())
                return protectedAddresses;
            List<InetAddress> systemAddresses = lookupSystemDns(hostname, false);
            if(!systemAddresses.isEmpty())
                return ipv4OnlyOrThrow(hostname, mergeIpv4First(hostname, systemAddresses, null, null));
            protectedAddresses = ipv4OnlyOrEmpty(lookupFallbackNtkDns(hostname));
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
        long startedAt = System.currentTimeMillis();
        Response response = null;
        try {
            HttpUrl url = HttpUrl.get("https://" + CLOUDFLARE_DOH_HOST + "/dns-query")
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
            ViewerWarmupManager.logMetric(metricName, System.currentTimeMillis() - startedAt);
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
        List<InetAddress> addresses = lookupCachedNtkDns(hostname, true);
        if(addresses.isEmpty()) {
            try {
                if(!lookupSystemDns(hostname, false).isEmpty())
                    return hostname;
            } catch (Exception ignored) {
            }
            addresses = lookupFallbackNtkDns(hostname);
        }
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
        String currentNtkHost = NTK_HOST.toLowerCase(Locale.ROOT);
        if(ntkQuicSniLooksBlocked(lower) && lower.contains("app_dns_" + currentNtkHost + ": ok"))
            return "DNS bypass works, but mobile route/TLS/SNI is still blocked before NTK responds. A VPN/WARP-style tunnel is required on this network.";
        if(lower.contains("system_dns_") && lower.contains("system_dns_" + currentNtkHost + ": fail")
                && lower.contains("app_dns_" + currentNtkHost + ": ok"))
            return "Carrier DNS appears blocked, app DNS bypass is working.";
        if(lower.contains("ntk_api_direct: fail") && lower.contains("app_dns_" + currentNtkHost + ": ok"))
            return "DNS bypass works, but route/TLS/SNI may still be blocked by the mobile network.";
        return "Check DNS/API lines above.";
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
    Map<String, String> cookies;
    Map<String, Long> cookieSyncAt;
    Map<String, CachedPage> pageCache;
    Map<String, PageLoadState> pageLoads;
    private String cookieHeaderCache;
    private final Object pageCacheLock = new Object();
    private final Object pageLoadsLock = new Object();
    private volatile String lastCloudflareChallengeUrl = null;
    private volatile long lastCloudflareChallengeAt = 0L;
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
    private long wfwfDomainLastForcedRetry = 0;
    private long wfwfDomainLastCanceledLog = 0;
    private long ntkDomainLastCheck = 0;
    private String ntkDomainLastCheckedRoot = "";
    private Context context;
    private final Object ntkQuicEngineLock = new Object();
    private final Map<String, HttpEngine> ntkQuicEngines = new HashMap<>();
    private final Map<String, ExecutorService> ntkQuicExecutors = new HashMap<>();
    private final Map<String, FutureTask<NtkQuicFetcher.Result>> ntkQuicImageFlights = new java.util.concurrent.ConcurrentHashMap<>();
    private final Map<String, Long> ntkWasmWarmCache = new HashMap<>();
    private final Object ntkViewerImageUrlCacheLock = new Object();
    private final Map<String, CachedViewerImages> ntkViewerImageUrlCache = new HashMap<>();
    private final Map<String, Long> ntkViewerImageUrlMissCache = new HashMap<>();
    private final Map<String, FutureTask<List<String>>> ntkViewerImageUrlFlights = new java.util.concurrent.ConcurrentHashMap<>();
    public String agent = "Mozilla/5.0 (Linux; Android 13; SM-G981B) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/116.0.0.0 Mobile Safari/537.36";

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

    public synchronized void setCookie(String k, String v){
        if(k == null || k.length() == 0)
            return;
        if(v == null) {
            cookies.remove(k);
            invalidateCookieHeaderCache();
            persistCookies();
            return;
        }
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
        boolean changed = !nextAgent.equals(agent);
        agent = nextAgent;
        context.getSharedPreferences("mangaView", Context.MODE_PRIVATE)
                .edit()
                .putString("httpUserAgent", agent)
                .apply();
        if(changed)
            clearUserAgentBoundAccessState();
    }
    public synchronized void removeCookie(String k) {
        cookies.remove(k);
        invalidateCookieHeaderCache();
        persistCookies();
    }

    private synchronized boolean removeNtkAckCookies() {
        boolean changed = false;
        for(String key : new String[]{"ad_ack", "ad_ack_c"}) {
            if(cookies.remove(key) != null)
                changed = true;
        }
        if(changed) {
            invalidateCookieHeaderCache();
            persistCookies();
        }
        return changed;
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

    public boolean hasCloudflareChallengeSince(long timestamp) {
        return lastCloudflareChallengeUrl != null
                && lastCloudflareChallengeUrl.length() > 0
                && lastCloudflareChallengeAt >= timestamp;
    }

    public void clearLastCloudflareChallenge() {
        lastCloudflareChallengeUrl = null;
        lastCloudflareChallengeAt = 0L;
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

    public void setCloudflareCaptchaActive(boolean active) {
        cloudflareCaptchaActive = active;
    }

    public void markNtkAccessVerified() {
        try {
            clearLastCloudflareChallenge();
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
                    .commit();
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
            cookies.put("cf_clearance", value);
            invalidateCookieHeaderCache();
            persistCookies();
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
        try {
            CookieManager manager = CookieManager.getInstance();
            if(urls == null)
                return;
            for(String url : urls) {
                if(url == null || url.length() == 0)
                    continue;
                manager.setCookie(url, "cf_clearance=; Max-Age=0; Path=/");
                manager.setCookie(url, "cf_clearance=; Max-Age=0; Path=/; Domain=" + NTK_HOST);
                manager.setCookie(url, "cf_clearance=; Max-Age=0; Path=/; Domain=." + NTK_HOST);
                manager.setCookie(url, "cf_clearance=; Max-Age=0; Path=/; Domain=" + PREVIOUS_NTK_HOST);
                manager.setCookie(url, "cf_clearance=; Max-Age=0; Path=/; Domain=." + PREVIOUS_NTK_HOST);
                manager.setCookie(url, "cf_clearance=; Max-Age=0; Path=/; Domain=" + OLDER_NTK_HOST);
                manager.setCookie(url, "cf_clearance=; Max-Age=0; Path=/; Domain=." + OLDER_NTK_HOST);
                manager.setCookie(url, "cf_clearance=; Max-Age=0; Path=/; Domain=" + OLDEST_NTK_HOST);
                manager.setCookie(url, "cf_clearance=; Max-Age=0; Path=/; Domain=." + OLDEST_NTK_HOST);
                manager.setCookie(url, "cf_clearance=; Max-Age=0; Path=/; Domain=" + LEGACY_NTK_HOST);
                manager.setCookie(url, "cf_clearance=; Max-Age=0; Path=/; Domain=." + LEGACY_NTK_HOST);
                manager.setCookie(url, "__cf_bm=; Max-Age=0; Path=/");
                manager.setCookie(url, "__cf_bm=; Max-Age=0; Path=/; Domain=" + NTK_HOST);
                manager.setCookie(url, "__cf_bm=; Max-Age=0; Path=/; Domain=." + NTK_HOST);
                manager.setCookie(url, "__cf_bm=; Max-Age=0; Path=/; Domain=" + PREVIOUS_NTK_HOST);
                manager.setCookie(url, "__cf_bm=; Max-Age=0; Path=/; Domain=." + PREVIOUS_NTK_HOST);
                manager.setCookie(url, "__cf_bm=; Max-Age=0; Path=/; Domain=" + OLDER_NTK_HOST);
                manager.setCookie(url, "__cf_bm=; Max-Age=0; Path=/; Domain=." + OLDER_NTK_HOST);
                manager.setCookie(url, "__cf_bm=; Max-Age=0; Path=/; Domain=" + OLDEST_NTK_HOST);
                manager.setCookie(url, "__cf_bm=; Max-Age=0; Path=/; Domain=." + OLDEST_NTK_HOST);
                manager.setCookie(url, "__cf_bm=; Max-Age=0; Path=/; Domain=" + LEGACY_NTK_HOST);
                manager.setCookie(url, "__cf_bm=; Max-Age=0; Path=/; Domain=." + LEGACY_NTK_HOST);
            }
            clearNtkAccessVerification();
            manager.flush();
            clearCloudflareCookies(false);
        } catch (Exception e) {
            ml.melun.mangaview.report.CrashReporter.record(e);
        }
    }
    public synchronized void resetCookie(){
        this.cookies = new HashMap<>();
        this.cookieSyncAt = new HashMap<>();
        NTK_ACK_CACHE.clear();
        synchronized (ntkViewerImageUrlCacheLock) {
            ntkViewerImageUrlCache.clear();
        }
        invalidateCookieHeaderCache();
        persistCookies();
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
        if(url == null || url.length() == 0 || key == null || key.length() == 0)
            return;
        try {
            CookieManager manager = CookieManager.getInstance();
            manager.setCookie(url, key + "=; Max-Age=0; Path=/");
            String host = Uri.parse(url).getHost();
            if(host != null && host.length() > 0)
                manager.setCookie(url, key + "=; Max-Age=0; Path=/; Domain=" + host);
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
        return value != null
                && value.trim().length() >= 20
                && !"deleted".equalsIgnoreCase(value.trim())
                && !"null".equalsIgnoreCase(value.trim());
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
            String saved = pref.getString("httpUserAgent", null);
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
        long now = System.currentTimeMillis();
        String currentRoot = WfwfDomainResolver.toRoot(getWebtoonUrl());
        if(!force && isCurrentDefaultNtkRoot(currentRoot))
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
        String trustedResolved = firstTrustedResolvedNtkRoot(resolvedRoots);
        String normalizedCurrent = NtkDomainResolver.normalizeRoot(currentRoot);
        if(trustedResolved != null && !trustedResolved.equals(normalizedCurrent))
            return trustedResolved;
        ArrayList<String> candidates = new ArrayList<>();
        if(resolvedRoots != null)
            for(String root : resolvedRoots)
                addNtkRootCandidate(candidates, root, true);
        addNtkRootCandidate(candidates, currentRoot);
        addNtkRootCandidate(candidates, "https://" + LEGACY_NTK_HOST);
        addNtkRootCandidate(candidates, NTK_WEBTOON_URL);
        for(String candidate : candidates)
            if(canReachNtkRoot(candidate, headers))
                return candidate;
        return resolvedRoots == null || resolvedRoots.isEmpty() ? null : NtkDomainResolver.normalizeRoot(resolvedRoots.get(0));
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
                    .build();
            Request.Builder builder = new Request.Builder().url(trimTrailingSlash(root) + "/").get();
            if(headers != null)
                for(String key : headers.keySet())
                    builder.addHeader(key, headers.get(key));
            call = probeClient.newCall(builder.build());
            response = call.execute();
            if(response == null)
                return false;
            int code = response.code();
            String location = response.header("location", "");
            String body = "";
            if(code == 403 || code >= 500) {
                try {
                    body = response.peekBody(256 * 1024L).string();
                } catch (Exception ignored) {
                }
            }
            return isReachableNtkProbeResponse(code, location, body);
        } catch (Exception e) {
            return false;
        } finally {
            if(call != null)
                call.cancel();
            if(response != null)
                response.close();
        }
    }

    private static boolean isReachableNtkProbeResponse(int code, String location, String body) {
        if(location != null && location.toLowerCase(Locale.ROOT).contains("t.me/"))
            return false;
        if(code <= 0 || code >= 500)
            return false;
        if(code == 403 && isCloudflareChallenge(code, body))
            return false;
        return true;
    }

    static boolean isReachableNtkProbeResponseForTest(int code, String location, String body) {
        return isReachableNtkProbeResponse(code, location, body);
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
                    if(!ntkTokenizedViewer && (fresh || shouldServeColdStartCachedPageImmediately(allowColdStartStale, fetchMode, true, fresh)))
                        return new PageResponse(cached.code, cached.body, true);
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
            if(!ntkTokenizedViewer && (fresh || shouldServeColdStartCachedPageImmediately(allowColdStartStale, fetchMode, true, fresh)))
                return new PageResponse(diskCached.code, diskCached.body, true);
            staleCached = diskCached;
        }
        if(fetchMode == FetchMode.CACHE_ONLY) {
            if(staleCached != null)
                return new PageResponse(staleCached.code, staleCached.body, true);
            throw new Exception("Cache miss: " + cacheKey);
        }
        if(shouldFastFailNtkPageForCaptcha(isNtk(), normalized, fetchMode,
                hasNtkAccessProof(), hasRecentNtkAccessVerification(), hasRecentCloudflareChallenge())) {
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
                            hasNtkAccessProof(), hasRecentNtkAccessVerification(), hasRecentCloudflareChallenge(), e)) {
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

    public PageResponse mgetNtkViewerPayloadPage(String url, long ttlMillis) throws Exception {
        String normalized = normalizePath(url);
        if(!isNtk() || !isNtkTokenizedViewerPath(normalized) || !NtkQuicFetcher.isAvailable())
            return mgetCachedPage(normalized, ttlMillis);
        long startedAt = System.currentTimeMillis();
        String baseUrl = getBaseUrl(normalized);
        Map<String, String> headers = new HashMap<>();
        headers.put("accept", "text/x-component");
        headers.put("rsc", "1");
        headers.put("next-url", normalized);
        headers.put("origin", baseUrl);
        headers.put("referer", baseUrl + normalized);
        try {
            NtkQuicFetcher.Result result = fetchNtkQuic(baseUrl, baseUrl + normalized,
                    getCookieHeaderForNtkPath(normalized), headers, "GET", null, 7000L);
            String body = result == null || result.body == null ? "" : result.body;
            Log.d(TAG, "ntk_rsc_payload path=" + normalized
                    + ",code=" + (result == null ? 0 : result.code)
                    + ",bytes=" + body.length()
                    + ",usable=" + isUsableNtkViewerPayload(body)
                    + ",ms=" + (System.currentTimeMillis() - startedAt)
                    + ",error=" + (result == null ? "" : result.error));
            if(result != null && result.error == null && result.code >= 200 && result.code < 400
                    && isUsableNtkViewerPayload(body))
                return new PageResponse(result.code, body, false);
        } catch(InterruptedException e) {
            Thread.currentThread().interrupt();
            throw e;
        } catch(Exception e) {
            Log.d(TAG, "ntk_rsc_payload_error path=" + normalized
                    + ",ms=" + (System.currentTimeMillis() - startedAt)
                    + "," + e);
        }
        return mgetCachedPage(normalized, ttlMillis);
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
            if(result != null && result.error == null && result.code > 0 && body.length() > 0
                    && isCloudflareChallengeResponse(result.code, body)) {
                markCloudflareChallenge(baseUrl + normalized);
                if(shouldAttemptNtkRscNativeAckRecovery(true, true, normalized, fetchMode)) {
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
        if(!isNtk() || !isNtkSearchPath(normalized))
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
        Log.d(TAG, "ntk_desktop_document path=" + normalized
                + ",transport=" + transport
                + ",code=" + code
                + ",bodyLen=" + (body == null ? 0 : body.length())
                + ",ms=" + (System.currentTimeMillis() - startedAt)
                + ",error=" + (error == null ? "" : error.getClass().getSimpleName()));
        if(isCloudflareChallenge(code, body)) {
            markCloudflareChallenge(baseUrl + normalized);
            throw new Exception(code == 403 ? "Cloudflare challenge" : "Cloudflare/server error");
        }
        if(shouldRejectNtkPageResponse(normalized, code, body))
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
        return lower.contains("cloudflare") || lower.contains("challenge");
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
        if(wolfDocument)
            ViewerWarmupManager.logMetric("wfwf_page_network_ms", System.currentTimeMillis() - startedAt);
        if(code >= 500 && staleCached != null)
            return new PageResponse(staleCached.code, staleCached.body, true);
        if(isCloudflareChallenge(code, body)) {
            markCloudflareChallenge(getBaseUrl(normalized) + normalized);
            throw new Exception(code == 403 ? "Cloudflare challenge" : "Cloudflare/server error");
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
        if(code >= 200 && code < 400 && body.length() > 0
                && !(isNtk() && isNtkTokenizedViewerPath(normalized))
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
        synchronized (pageCacheLock) {
            CachedPage cached = pageCache.get(cacheKey);
            if(!ntkTokenizedViewer && cached != null && isUsableCachedPage(cached) && isPageCacheFresh(cached.time, now, ttlMillis))
                return new PageResponse(cached.code, cached.body, true);
            if(cached != null && !isUsableCachedPage(cached))
                pageCache.remove(cacheKey);
            String currentCacheKey = pageCacheKey(normalized);
            if(!currentCacheKey.equals(cacheKey)) {
                cached = pageCache.get(currentCacheKey);
                if(!ntkTokenizedViewer && cached != null && isUsableCachedPage(cached) && isPageCacheFresh(cached.time, now, ttlMillis))
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
        return response;
    }

    static boolean shouldUseNtkDirectClientUrlForTest(String url) {
        return shouldUseNtkDirectClientUrl(url);
    }

    private static boolean shouldUseNtkDirectClientUrl(String url) {
        return isNtkUrlForTest(url) || shouldUseNtkQuicPrimaryUrl(url);
    }

    private Response getWithNtkQuicPrimaryUrl(String url, Map<String, String> headers) {
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
        if(!"GET".equalsIgnoreCase(request.method()) || context == null
                || !NtkQuicFetcher.isAvailable() || !shouldUseNtkQuicPrimaryUrl(url))
            return chain.proceed(request);
        try {
            HttpUrl parsed = request.url();
            String baseUrl = rootFromHttpUrl(parsed);
            Map<String, String> headers = requestHeadersMap(request);
            boolean foregroundPriority = "1".equals(headerValue(headers, "X-MangaViewer-Foreground"));
            removeHeaderIgnoreCase(headers, "X-MangaViewer-Foreground");
            if(foregroundPriority) {
                ViewerWarmupManager.logMetric("ntk_quic_image_foreground_skip", 1L);
                return chain.proceed(request);
            }
            if(headerValue(headers, "Cookie") == null)
                headers.put("Cookie", getCookieHeader());
            if(headerValue(headers, "User-Agent") == null)
                headers.put("User-Agent", agent);
            NtkQuicFetcher.Result result = fetchNtkQuicImage(baseUrl, url, headers, foregroundPriority);
            if(isUsableNtkQuicGetResult(result)) {
                applySetCookieHeaders(result.headers, baseUrl);
                if(isCloudflareChallenge(result.code, result.body))
                    markCloudflareChallenge(url);
                ViewerWarmupManager.logMetric("ntk_quic_image_code", result.code);
                ViewerWarmupManager.logMetric("ntk_quic_image_len", result.bodyBytes.length);
                return responseFromNtkQuic(request, result, "HttpEngine");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            InterruptedIOException interrupted = new InterruptedIOException("Interrupted during NTK QUIC image fetch");
            interrupted.initCause(e);
            throw interrupted;
        } catch (Exception e) {
            if(Log.isLoggable(TAG, Log.DEBUG))
                Log.d(TAG, "ntk_quic_image_failed url=" + safeLogUrl(url), e);
        }
        return chain.proceed(request);
    }

    private NtkQuicFetcher.Result fetchNtkQuicImage(String baseUrl, String url,
                                                    Map<String, String> headers,
                                                    boolean foregroundPriority) throws Exception {
        String cookieHeader = headerValue(headers, "Cookie");
        if(foregroundPriority) {
            ViewerWarmupManager.logMetric("ntk_quic_image_foreground_bypass", 1L);
            return fetchNtkQuic(baseUrl, url, cookieHeader, headers, "GET", null, NTK_QUIC_IMAGE_TIMEOUT_MS);
        }
        String key = ntkQuicImageFlightKey(url, cookieHeader);
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

    private static String ntkQuicImageFlightKey(String url, String cookieHeader) {
        return (url == null ? "" : url) + "\n" + (cookieHeader == null ? "" : cookieHeader);
    }

    private static boolean shouldUseNtkQuicPrimaryUrl(String url) {
        if(url == null || url.length() == 0)
            return false;
        HttpUrl parsed = HttpUrl.parse(url);
        if(parsed == null || !"https".equals(parsed.scheme()))
            return false;
        return isNtkDnsProtectedHost(parsed.host());
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
        if(!ntkUrl || fetchMode != FetchMode.ALLOW_SHARED_WEBVIEW || hasAccessProof || hasRecentVerification)
            return false;
        if(!isNtkWebViewFetchPath(path))
            return false;
        if(hasRecentChallenge)
            return true;
        String message = error == null ? null : error.getMessage();
        if(message == null)
            return false;
        String lower = message.toLowerCase(Locale.ROOT);
        return lower.contains("cloudflare") || lower.contains("challenge");
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
            synchronized (ntkQuicEngineLock) {
                HttpEngine cached = ntkQuicEngines.get(host);
                if(cached != null)
                    return cached;
                HttpEngine created = new HttpEngine.Builder(context.getApplicationContext())
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
                ntkQuicEngines.put(host, created);
                return created;
            }
        } catch (Exception e) {
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
        HttpEngine engine = getOrCreateNtkQuicEngine(baseUrl);
        if(engine == null)
            return NtkQuicFetcher.fetch(context, url, agent, cookieHeader, headers, method, body, timeoutMs);
        ExecutorService executor = getOrCreateNtkQuicExecutor(baseUrl);
        try {
            if(executor != null)
                return NtkQuicFetcher.fetchWithEngine(engine, executor, url, agent, cookieHeader, headers, method, body, timeoutMs);
            return NtkQuicFetcher.fetchWithEngine(engine, url, agent, cookieHeader, headers, method, body, timeoutMs);
        } catch (IllegalStateException e) {
            dropNtkQuicEngine(baseUrl);
            return NtkQuicFetcher.fetch(context, url, agent, cookieHeader, headers, method, body, timeoutMs);
        }
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
        String cacheKey = baseUrl + path + "|" + episodeId + "|" + imagesToken;
        Log.d(TAG, "ntk_images_api_start path=" + path
                + ",ackPath=" + cookiePath
                + ",endpoint=" + endpoint
                + ",tokenLen=" + imagesToken.length());
        List<String> cachedUrls = cachedNtkViewerImageUrls(cacheKey);
        if(!cachedUrls.isEmpty()) {
            if(areInitialNtkViewerImageUrlsReachable(cachedUrls, baseUrl, path)) {
                ViewerWarmupManager.logMetric("ntk_images_api_cache_hit", cachedUrls.size());
                return cachedUrls;
            }
            invalidateCachedNtkViewerImageUrls(cacheKey);
            NtkWebViewFallbackManager.get(context).dropCachedViewerImageUrls(kind, workId, episodeId, path);
            Log.d(TAG, "ntk_images_api_cache_invalid path=" + path + ",count=" + cachedUrls.size());
        }
        if(hasFreshNtkViewerImageUrlMiss(cacheKey)) {
            Log.d(TAG, "ntk_images_api_miss_cache_hit path=" + path);
            return urls;
        }
        FutureTask<List<String>> task = new FutureTask<>(() ->
                fetchNtkViewerImageUrlsUncached(kind, endpoint, baseUrl, path, cookiePath, segment,
                        workId, episodeId, imagesToken, cacheKey, viewerBody));
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

    private List<String> fetchNtkViewerImageUrlsUncached(String kind, String endpoint,
                                                         String baseUrl, String path,
                                                         String cookiePath,
                                                         String segment, String workId,
                                                         String episodeId, String imagesToken,
                                                         String cacheKey, String viewerBody) {
        return fetchNtkViewerImageUrlsUncached(kind, endpoint, baseUrl, path, cookiePath, null,
                segment, workId, episodeId, imagesToken, cacheKey, viewerBody);
    }

    private List<String> fetchNtkViewerImageUrlsUncached(String kind, String endpoint,
                                                         String baseUrl, String path,
                                                         String cookiePath, String refererPath,
                                                         String segment, String workId,
                                                         String episodeId, String imagesToken,
                                                         String cacheKey, String viewerBody) {
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
            if(looksLikeNtkWebViewViewerBody(viewerBody)) {
                Log.d(TAG, "ntk_images_api_webview_first path=" + path);
                urls.addAll(NtkWebViewFallbackManager.get(context).fetchViewerImageUrls(agent, baseUrl,
                        path, headers, kind, workId, episodeId, imagesToken, getCookieHeaderForNtkPath(cookiePath)));
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

            if(shouldTryNtkViewerImagesBeforeAck(kind, baseUrl, path, cookiePath)) {
                JSONObject payload = ntkViewerImagesPayload(workId, episodeId, imagesToken, nv);
                NtkQuicFetcher.Result result = fetchNtkViewerImagesApi(baseUrl, endpoint, path,
                        cookiePath, headers, payload);
                ViewerWarmupManager.logMetric("ntk_images_api_code", result == null ? 0 : result.code);
                if(appendReachableNtkViewerImages(urls, result, baseUrl, path)) {
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
                if(Thread.currentThread().isInterrupted())
                    return urls;
            }

            boolean nativeAckCompleted = performNtkNativeAckBypass(baseUrl, cookiePath, apiRefererPath);
            if(!nativeAckCompleted && Thread.currentThread().isInterrupted()) {
                Log.d(TAG, "ntk_images_api_pre_ack_interrupted_cleared path=" + path);
                Thread.interrupted();
                nativeAckCompleted = performNtkNativeAckBypass(baseUrl, cookiePath, apiRefererPath);
            }
            if(!nativeAckCompleted) {
                Log.d(TAG, "ntk_images_api_skip_unacked path=" + path);
                Log.d(TAG, "ntk_images_api_webview_start path=" + path);
                urls.addAll(NtkWebViewFallbackManager.get(context).fetchViewerImageUrls(agent, baseUrl,
                        path, headers, kind, workId, episodeId, imagesToken, getCookieHeaderForNtkPath(cookiePath)));
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
                if(Thread.currentThread().isInterrupted())
                    return urls;
                nativeAckCompleted = performNtkNativeAckBypassFresh(baseUrl, cookiePath, apiRefererPath);
                if(nativeAckCompleted) {
                    String nvAfterFreshAck = getCookie("nv");
                    if(!isNtkNvValid(nvAfterFreshAck)) {
                        issueNtkNvCookie(baseUrl);
                        nvAfterFreshAck = getCookie("nv");
                    }
                    if(isNtkNvValid(nvAfterFreshAck)) {
                        JSONObject payload = ntkViewerImagesPayload(workId, episodeId, imagesToken, nvAfterFreshAck);
                        NtkQuicFetcher.Result result = fetchNtkViewerImagesApi(baseUrl, endpoint, path,
                                cookiePath, headers, payload);
                        ViewerWarmupManager.logMetric("ntk_images_api_code", result == null ? 0 : result.code);
                        if(appendReachableNtkViewerImages(urls, result, baseUrl, path)) {
                            cacheNtkViewerImageUrls(cacheKey, urls);
                            return urls;
                        }
                        if(ntkViewerImagesCount(result) > 0)
                            return urls;
                    }
                }
                return urls;
            }

            String nvAfterAck = getCookie("nv");
            if(!isNtkNvValid(nvAfterAck)) {
                issueNtkNvCookie(baseUrl);
                nvAfterAck = getCookie("nv");
            }
            if(isNtkNvValid(nvAfterAck))
                nv = nvAfterAck;
            JSONObject payload = ntkViewerImagesPayload(workId, episodeId, imagesToken, nv);
            NtkQuicFetcher.Result result = fetchNtkViewerImagesApi(baseUrl, endpoint, path,
                    cookiePath, headers, payload);
            boolean hardForbidden = ntkViewerImagesHardForbidden(result);
            ViewerWarmupManager.logMetric("ntk_images_api_code", result == null ? 0 : result.code);
            if(appendReachableNtkViewerImages(urls, result, baseUrl, path)) {
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
                    result = fetchNtkViewerImagesApi(baseUrl, endpoint, path, cookiePath, headers, payload);
                    hardForbidden = hardForbidden || ntkViewerImagesHardForbidden(result);
                    ViewerWarmupManager.logMetric("ntk_images_api_code", result == null ? 0 : result.code);
                    if(appendReachableNtkViewerImages(urls, result, baseUrl, path)) {
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
                result = fetchNtkViewerImagesApi(baseUrl, endpoint, path, cookiePath, headers, payload);
                ViewerWarmupManager.logMetric("ntk_images_api_code", result == null ? 0 : result.code);
                if(appendReachableNtkViewerImages(urls, result, baseUrl, path)) {
                    cacheNtkViewerImageUrls(cacheKey, urls);
                    return urls;
                }
                if(ntkViewerImagesCount(result) > 0)
                    return urls;
                hardForbidden = hardForbidden || ntkViewerImagesHardForbidden(result);
            }

            Log.d(TAG, "ntk_images_api_webview_start path=" + path + ",hardForbidden=" + hardForbidden);
            urls.addAll(NtkWebViewFallbackManager.get(context).fetchViewerImageUrls(agent, baseUrl,
                    path, headers, kind, workId, episodeId, imagesToken, getCookieHeaderForNtkPath(cookiePath)));
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
        JSONObject payload = new JSONObject();
        payload.put("workId", workId);
        payload.put("episodeId", episodeId);
        payload.put("token", imagesToken);
        payload.put("nonce", nonce);
        payload.put("proof", proof);
        return payload;
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
        byte[] body = payload.toString().getBytes(StandardCharsets.UTF_8);
        for(int attempt = 0; attempt < 2; attempt++) {
            try {
                String cookieHeader = getCookieHeaderForNtkPath(cookiePath);
                NtkQuicFetcher.Result result = fetchNtkQuic(baseUrl, baseUrl + endpoint,
                        cookieHeader, headers, "POST", body, NTK_VIEWER_IMAGES_API_TIMEOUT_MS);
                Log.d(TAG, "ntk_images_api_primary endpoint=" + endpoint
                        + ",attempt=" + attempt
                        + ",code=" + (result == null ? 0 : result.code)
                        + ",error=" + (result == null ? "" : result.error)
                        + ",imageCount=" + ntkViewerImagesCount(result)
                        + ",ackRequired=" + ntkViewerImagesAckRequired(result));
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

    private boolean shouldTryNtkViewerImagesBeforeAck(String kind, String baseUrl, String path, String cookiePath) {
        return shouldTryNtkViewerImagesBeforeAck(kind, path,
                hasNtkAdAckCookieForPath(cookiePath), false);
    }

    static boolean shouldTryNtkViewerImagesBeforeAckForTest(String kind, String path,
                                                            boolean hasAckCookie, boolean ackInFlight) {
        return shouldTryNtkViewerImagesBeforeAck(kind, path, hasAckCookie, ackInFlight);
    }

    private static boolean shouldTryNtkViewerImagesBeforeAck(String kind, String path,
                                                             boolean hasAckCookie, boolean ackInFlight) {
        return false;
    }

    private static boolean ntkViewerImagesHardForbidden(NtkQuicFetcher.Result result) {
        return result != null && result.code == 403 && !ntkViewerImagesAckRequired(result);
    }

    private static boolean appendNtkViewerImages(List<String> urls, NtkQuicFetcher.Result result) {
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
                if(src.length() > 0)
                    urls.add(src);
            }
            return urls.size() > before;
        } catch(Exception ignored) {
            return false;
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
            NtkQuicFetcher.Result result = fetchNtkQuic(baseUrl, baseUrl + "/api/nv-issue",
                    getCookieHeader(), headers, "POST", new byte[0], 15000L);
            applySetCookieHeaders(result.headers, baseUrl);
        } catch (Exception e) {
            ml.melun.mangaview.report.CrashReporter.record(e);
        }
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
        if(baseUrl == null || path == null || baseUrl.length() == 0 || path.length() == 0)
            return false;
        String ackPath = ntkNativeAckScopePath(path);
        String ackRefererPath = refererPath != null && refererPath.length() > 0 ? refererPath : path;
        String cacheKey = baseUrl + ackPath;
        String flightKey = ntkNativeAckFlightKey(ackPath);
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
            if(removeNtkAckCookies()) {
                Log.d(TAG, "ntk_native_ack_stale_cookie_removed path=" + path);
            }
            return performNtkNativeAckBypassLocked(baseUrl, ackPath, cacheKey, ackRefererPath);
        }
    }

    private boolean performNtkNativeAckBypassLocked(String baseUrl, String path, String cacheKey) {
        return performNtkNativeAckBypassLocked(baseUrl, path, cacheKey, path);
    }

    private boolean performNtkNativeAckBypassLocked(String baseUrl, String path, String cacheKey, String refererPath) {
        long startedMs = System.currentTimeMillis();
        long phaseStartedMs = startedMs;
        try {
            HttpEngine engine = getOrCreateNtkQuicEngine(baseUrl);
            if(engine == null)
                return false;
            ExecutorService executor = getOrCreateNtkQuicExecutor(baseUrl);
            if(executor == null)
                return false;
            phaseStartedMs = logNtkNativeAckPhase("engine", path, startedMs, phaseStartedMs);
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
            for(int attempt = 0; attempt < NTK_ACK_REQUEST_ATTEMPTS; attempt++) {
                if(attempt > 0) Thread.sleep(NTK_ACK_RETRY_DELAY_MS * attempt);
                challenge = fetchNtkAckChallengeRace(engine, executor, baseUrl, path, h, challengeBytes, attempt);
                Log.d(TAG, "ntk_native_ack_challenge_code=" + (challenge == null ? "null" : challenge.code)
                        + ",attempt=" + attempt
                        + ",path=" + path);
                if(challenge != null && challenge.error == null && challenge.code == 200
                        && looksLikeJsonObject(challenge.body))
                    break;
                if(isNtkAckHardBlocked(challenge))
                    break;
            }
            if(challenge == null || challenge.error != null || challenge.code != 200 || challenge.body == null)
                return false;
            if(!looksLikeJsonObject(challenge.body)) {
                Log.d(TAG, "ntk_native_ack_challenge_non_json path=" + path
                        + ",body=" + challenge.body.substring(0, Math.min(120, challenge.body.length())));
                return false;
            }
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
            String cookiesAfterChallenge = getCookieHeader();
            String nvAfterChallenge = getCookie("nv");
            Log.d(TAG, "ntk_native_ack_cookies_after_challenge nv=" + (nvAfterChallenge == null ? "null" : nvAfterChallenge.substring(0, Math.min(60, nvAfterChallenge.length()))) + " cookies_len=" + (cookiesAfterChallenge == null ? 0 : cookiesAfterChallenge.length()));
            Map<String, String> h2 = new HashMap<>(h);
            h2.put("referer", baseUrl + ntkNativeAckScopePath(challengePath));

            // Numeric and slug routes currently accept the challenge token directly; avoid WebView/WASM overhead.
            Log.d(TAG, "ntk_native_ack_vc_skipped token_len=" + token.length() + ",path=" + challengePath);

            Log.d(TAG, "ntk_native_ack_token_before_ack len=" + token.length() + ",path=" + challengePath);
            int slotCount = challengeObj.optInt("slotCount", 4);
            int minSeen = challengeObj.optInt("minSeen", 2);

            byte[] canaryBytes = ntkAckCanaryPayload(token, challengePath);
            Future<NtkAckCanaryResult> proactiveCanary = startProactiveNtkAckCanary(
                    executor, engine, baseUrl, challengePath, h2, canaryBytes, impressionUrls);

            // 2. GET the minimum required impression URLs and apply cookies.
            if(impressionUrls != null && impressionUrls.length() > 0) {
                fetchNtkAckImpressions(engine, executor, baseUrl, challengePath, impressionUrls, minSeen);
            }
            phaseStartedMs = logNtkNativeAckPhase("impressions", challengePath, startedMs, phaseStartedMs);
            NtkAckCanaryResult proactiveCanaryResult = collectProactiveNtkAckCanary(
                    proactiveCanary, baseUrl, challengePath);

            // 4. POST /api/ad/ack with challenge token
            // WebView sends additional metrics: total, visible, td, tp
            // tp is a proof computed by ad_guard.js; we leave it empty for now
            JSONObject ackPayload = new JSONObject();
            ackPayload.put("challengeToken", token);
            ackPayload.put("total", Math.max(slotCount, 28));
            ackPayload.put("visible", minSeen);
            ackPayload.put("path", challengePath);
            ackPayload.put("td", 0);
            ackPayload.put("tp", "");
            boolean ackBodyOk = false;
            String ackStatus = null;
            String ackError = null;
            NtkQuicFetcher.Result ack = null;
            byte[] ackBytes = ackPayload.toString().getBytes(StandardCharsets.UTF_8);
            boolean canaryFallbackAttempted = proactiveCanaryResult != null && proactiveCanaryResult.ok;
            boolean skipNextAckDelay = false;
            for(int attempt = 0; attempt < NTK_ACK_REQUEST_ATTEMPTS; attempt++) {
                if(attempt > 0 && !skipNextAckDelay)
                    Thread.sleep(NTK_ACK_RETRY_DELAY_MS * attempt);
                skipNextAckDelay = false;
                ack = fetchNtkAckControlPost(engine, executor, baseUrl, "/api/ad/ack", h2, ackBytes);
                Log.d(TAG, "ntk_native_ack_ack_code=" + (ack == null ? "null" : ack.code)
                        + ",attempt=" + attempt
                        + ",path=" + challengePath);
                if(ack != null) applySetCookieHeaders(ack.headers, baseUrl);
                ackBodyOk = false;
                ackStatus = null;
                ackError = null;
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
                if(ack != null && ack.code == 200 && ackBodyOk)
                    break;
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
            boolean ackSuccess = ack != null && ack.code == 200 && ackBodyOk;
            phaseStartedMs = logNtkNativeAckPhase("ack", challengePath, startedMs, phaseStartedMs);
            Log.d(TAG, "ntk_native_ack_final_success=" + ackSuccess
                    + ",path=" + challengePath
                    + ",cookieOk=" + hasNtkAdAckCookieForPath(challengePath)
                    + ",totalMs=" + (System.currentTimeMillis() - startedMs));
            if(ackSuccess) {
                NTK_ACK_CACHE.put(cacheKey, System.currentTimeMillis());
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

    private boolean isNtkAckHardBlocked(NtkQuicFetcher.Result result) {
        return result != null
                && result.error == null
                && result.code == 403
                && isCloudflareChallengeResponse(result.code, result.body);
    }

    private boolean performNtkNativeAckBypassFresh(String baseUrl, String path) {
        return performNtkNativeAckBypassFresh(baseUrl, path, path);
    }

    private boolean performNtkNativeAckBypassFresh(String baseUrl, String path, String refererPath) {
        if(baseUrl == null || path == null || baseUrl.length() == 0 || path.length() == 0)
            return false;
        String ackPath = ntkNativeAckScopePath(path);
        String cacheKey = baseUrl + ackPath;
        synchronized (ntkNativeAckLock(ntkNativeAckFlightKey(ackPath))) {
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

    private NtkQuicFetcher.Result fetchNtkAckChallengeRace(HttpEngine engine, ExecutorService executor,
                                                           String baseUrl, String path,
                                                           Map<String, String> headers,
                                                           byte[] challengeBytes,
                                                           int attempt) throws InterruptedException {
        int lanes = Math.max(1, NTK_ACK_CHALLENGE_RACE_REQUESTS);
        ExecutorCompletionService<NtkQuicFetcher.Result> completion =
                new ExecutorCompletionService<>(executor);
        List<Future<NtkQuicFetcher.Result>> futures = new ArrayList<>();
        int submitted = 0;
        submitNtkAckChallengeLane(completion, futures, engine, executor, baseUrl, path,
                headers, challengeBytes, attempt, submitted++);
        NtkQuicFetcher.Result fallback = null;
        try {
            for(int completed = 0; completed < lanes; completed++) {
                Future<NtkQuicFetcher.Result> completedFuture = completion.poll(
                        completed == 0 && submitted < lanes
                                ? NTK_ACK_CHALLENGE_HEDGE_DELAY_MS
                                : NTK_ACK_CHALLENGE_TIMEOUT_MS,
                        TimeUnit.MILLISECONDS);
                if(completedFuture == null) {
                    if(submitted < lanes) {
                        submitNtkAckChallengeLane(completion, futures, engine, executor, baseUrl, path,
                                headers, challengeBytes, attempt, submitted++);
                    }
                    completedFuture = completion.take();
                }
                NtkQuicFetcher.Result result = null;
                Throwable error = null;
                try {
                    result = completedFuture.get();
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
                        + ",code=" + (result == null ? "null" : result.code)
                        + ",error=" + (result == null ? error : result.error)
                        + ",valid=" + valid
                        + ",path=" + path);
                if(valid)
                    return result;
            }
            return fallback;
        } finally {
            for(Future<NtkQuicFetcher.Result> future : futures) {
                if(!future.isDone())
                    future.cancel(true);
            }
        }
    }

    private void submitNtkAckChallengeLane(ExecutorCompletionService<NtkQuicFetcher.Result> completion,
                                           List<Future<NtkQuicFetcher.Result>> futures,
                                           HttpEngine engine,
                                           ExecutorService executor,
                                           String baseUrl,
                                           String path,
                                           Map<String, String> headers,
                                           byte[] challengeBytes,
                                           int attempt,
                                           int lane) {
        futures.add(completion.submit(() -> {
            try {
                if(engine != null && executor != null) {
                    NtkQuicFetcher.Result result = NtkQuicFetcher.fetchWithEngine(engine, executor,
                            baseUrl + "/api/ad/challenge", agent, getCookieHeader(), headers,
                            "POST", challengeBytes, NTK_ACK_CHALLENGE_TIMEOUT_MS);
                    if(result != null && result.error == null && result.code > 0)
                        return result;
                }
            } catch(InterruptedException e) {
                Thread.currentThread().interrupt();
                return null;
            } catch(Exception ignored) {
            }
            return NtkQuicFetcher.fetch(context, baseUrl + "/api/ad/challenge", agent,
                    getCookieHeader(), headers, "POST", challengeBytes, NTK_ACK_CHALLENGE_TIMEOUT_MS);
        }));
        Log.d(TAG, "ntk_native_ack_challenge_race_start attempt=" + attempt
                + ",lane=" + lane
                + ",path=" + path);
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

    private synchronized boolean hasNtkAdAckCookieForPath(String path) {
        return ntkAdAckCookieMatchesPath(cookies.get("ad_ack"), path);
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
            return true;
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
                                                   String baseUrl, String refererPath) {
        if(urls == null)
            return false;
        if(!appendNtkViewerImages(urls, result))
            return false;
        Log.d(TAG, "ntk_images_api_trusted_result path=" + refererPath
                + ",code=" + (result == null ? 0 : result.code)
                + ",count=" + (result == null ? 0 : ntkViewerImagesCount(result)));
        return true;
    }

    private boolean areInitialNtkViewerImageUrlsReachable(List<String> urls, String baseUrl, String refererPath) {
        if(urls == null || urls.isEmpty())
            return false;
        int validationCount = ntkViewerImageInitialValidationCount(urls.size());
        for(int i = 0; i < validationCount; i++) {
            if(!isNtkViewerImageUrlReachable(urls.get(i), baseUrl, refererPath, i))
                return false;
        }
        return true;
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
        return impressionUrls != null && impressionUrls.length() > 0;
    }

    private byte[] ntkAckCanaryPayload(String token, String challengePath) throws Exception {
        JSONObject canaryPayload = new JSONObject();
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

    private int fetchNtkAckImpressions(HttpEngine engine, ExecutorService executor, String baseUrl,
                                       String challengePath, JSONArray impressionUrls, int minSeen) {
        if(engine == null || executor == null || impressionUrls == null || impressionUrls.length() == 0)
            return 0;
        ExecutorCompletionService<NtkImpressionResult> completion = new ExecutorCompletionService<>(executor);
        List<Future<NtkImpressionResult>> futures = new ArrayList<>();
        List<String> urls = new ArrayList<>();
        for(int i = 0; i < impressionUrls.length(); i++) {
            String rawUrl = impressionUrls.optString(i, "");
            if(rawUrl.length() == 0)
                continue;
            urls.add(rawUrl.startsWith("http") ? rawUrl : baseUrl + rawUrl);
        }
        if(urls.isEmpty())
            return 0;
        int targetSeen = Math.max(1, Math.min(minSeen <= 0 ? 2 : minSeen, urls.size()));
        int submitted = 0;
        int completed = 0;
        int initialSubmit = Math.min(targetSeen + 1, urls.size());
        while(submitted < initialSubmit) {
            submitNtkAckImpression(completion, futures, engine, executor, challengePath, urls.get(submitted), submitted);
            submitted++;
        }
        int seen = 0;
        while(completed < submitted) {
            try {
                Future<NtkImpressionResult> future = completion.poll(completed == 0 ? 850L : 220L, TimeUnit.MILLISECONDS);
                if(future == null) {
                    if(submitted < urls.size()) {
                        submitNtkAckImpression(completion, futures, engine, executor, challengePath,
                                urls.get(submitted), submitted);
                        submitted++;
                        continue;
                    }
                    break;
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
                NtkQuicFetcher.Result imp = NtkQuicFetcher.fetchWithEngine(engine, executor, impressionUrl, agent,
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

    private static String base64Url(byte[] bytes) {
        return Base64.encodeToString(bytes, Base64.URL_SAFE | Base64.NO_WRAP | Base64.NO_PADDING);
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
        return configureDispatcher(baseClient(builder), MAX_IMAGE_HTTP_REQUESTS, MAX_IMAGE_HTTP_REQUESTS_PER_HOST);
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
