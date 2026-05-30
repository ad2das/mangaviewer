package ml.melun.mangaview.mangaview;


import android.content.Context;
import android.content.SharedPreferences;
import android.net.http.HttpEngine;
import android.net.http.QuicOptions;
import android.util.Base64;
import android.util.Log;
import android.webkit.CookieManager;

import com.google.gson.Gson;
import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.InterruptedIOException;
import java.net.Inet4Address;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.URI;
import java.net.UnknownHostException;
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
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
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
    public static final String NTK_COMIC_URL = "https://sbxh3.com/manhwa";
    public static final String NTK_WEBTOON_URL = "https://sbxh3.com";
    public static final String NTK_REACHABLE_FALLBACK_URL = "https://ntk01.com";
    private static final String NTK_HOST = "sbxh3.com";
    private static final String PREVIOUS_NTK_HOST = "sbxh2.com";
    private static final String OLDER_NTK_HOST = "sbxh1.com";
    private static final String LEGACY_NTK_HOST = "ntk01.com";
    private static final long WFWF_DOMAIN_CHECK_INTERVAL_MS = 6 * 60 * 60 * 1000L;
    private static final long WFWF_DOMAIN_FORCE_RETRY_INTERVAL_MS = 5 * 1000L;
    private static final long WFWF_FAILED_ROOT_RECHECK_INTERVAL_MS = 30 * 60 * 1000L;
    private static final long WFWF_DOMAIN_CANCELED_LOG_INTERVAL_MS = 2 * 1000L;
    private static final long WFWF_DOMAIN_WAIT_TIMEOUT_MS = 6 * 1000L;
    private static final long NTK_DOMAIN_CHECK_INTERVAL_MS = 15 * 60 * 1000L;
    private static final long NTK_PAGE_DIRECT_TIMEOUT_MS = 3_500L;
    private static final long WFWF_PAGE_CONNECT_TIMEOUT_MS = 2_500L;
    private static final long WFWF_PAGE_READ_TIMEOUT_MS = 7_000L;
    private static final long WFWF_PAGE_CALL_TIMEOUT_MS = 8_000L;
    private static final long WFWF_SEARCH_CONNECT_TIMEOUT_MS = 800L;
    private static final long WFWF_SEARCH_READ_TIMEOUT_MS = 1_500L;
    private static final long WFWF_SEARCH_CALL_TIMEOUT_MS = 1_700L;
    private static final long NTK_DOH_TIMEOUT_MS = 1_500L;
    private static final long NTK_DNS_CACHE_DEFAULT_TTL_MS = 5 * 60 * 1000L;
    private static final long NTK_DNS_CACHE_MAX_TTL_MS = 30 * 60 * 1000L;
    private static final long NTK_DNS_FALLBACK_MEMORY_TTL_MS = 30 * 1000L;
    private static final long NTK_DOH_FAILURE_BACKOFF_MS = 10 * 60 * 1000L;
    private static final long NTK_DNS_DISK_STALE_TTL_MS = 7 * 24 * 60 * 60 * 1000L;
    private static final long COOKIE_SYNC_INTERVAL_MS = 30 * 1000L;
    private static final long PAGE_CACHE_COLD_START_TTL_MS = 7 * 24 * 60 * 60 * 1000L;
    private static final int PAGE_CACHE_MAX_ENTRIES = 200;
    private static final int MAX_HTTP_REQUESTS = 8;
    private static final int MAX_HTTP_REQUESTS_PER_HOST = 4;
    private static final int MAX_IMAGE_HTTP_REQUESTS = 32;
    private static final int MAX_IMAGE_HTTP_REQUESTS_PER_HOST = 12;
    private static final boolean DUMP_NTK_ACK_DEBUG_ARTIFACTS = false;
    private static final long NTK_ACK_CACHE_TTL_MS = 5 * 60 * 1000L;
    private static final java.util.Map<String, Long> NTK_ACK_CACHE = new java.util.concurrent.ConcurrentHashMap<>();
    private static final String PAGE_CACHE_PREFIX = "httpPageCacheV1_";
    private static final String NTK_DNS_CACHE_PREFIX = "ntkDnsCacheV1_";
    private static final String CLOUDFLARE_DOH_HOST = "cloudflare-dns.com";
    private static final String NTK_EDGE_IP = "104.16.219.55";
    private static final Gson GSON = new Gson();
    private static final ConnectionPool SHARED_CONNECTION_POOL = new ConnectionPool(12, 5, TimeUnit.MINUTES);
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
        warmNtkDohAsync(hostname);
        List<InetAddress> fallback = ntkFallbackAddresses(hostname);
        if(!fallback.isEmpty()) {
            writeMemoryCachedNtkDns(hostname, fallback, System.currentTimeMillis() + NTK_DNS_FALLBACK_MEMORY_TTL_MS);
            ViewerWarmupManager.logMetric("ntk_dns_fallback_count", fallback.size());
        }
        return fallback;
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

    private static List<InetAddress> ntkFallbackAddresses(String hostname) {
        ArrayList<InetAddress> addresses = new ArrayList<>();
        if(!isNtkDnsProtectedHost(hostname))
            return addresses;
        try {
            addAddressIfMissing(addresses, parseIpv4Address(hostname, NTK_EDGE_IP));
        } catch (Exception ignored) {
        }
        return addresses;
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

    static List<InetAddress> ntkFallbackAddressesForTest(String hostname) {
        return ntkFallbackAddresses(hostname);
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
        if(lower.contains("system_dns_") && lower.contains("system_dns_" + currentNtkHost + ": fail")
                && lower.contains("app_dns_" + currentNtkHost + ": ok"))
            return "Carrier DNS appears blocked, app DNS bypass is working.";
        if(lower.contains("ntk_api_direct: fail") && lower.contains("app_dns_" + currentNtkHost + ": ok"))
            return "DNS bypass works, but route/TLS/SNI may still be blocked by the mobile network.";
        return "Check DNS/API lines above.";
    }

    public OkHttpClient client;
    public OkHttpClient imageClient;
    private OkHttpClient unsafeFallbackClient;
    private OkHttpClient ntkPageFastClient;
    private OkHttpClient unsafeNtkPageFastClient;
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
        this.imageClient = imageClient(new OkHttpClient.Builder()).build();
        this.unsafeFallbackClient = baseClient(getUnsafeOkHttpClient())
                .protocols(ntkTlsFallbackProtocolsForTest())
                .build();
        this.ntkPageFastClient = fastNtkPageClient(new OkHttpClient.Builder()).build();
        this.unsafeNtkPageFastClient = fastNtkPageClient(getUnsafeOkHttpClient())
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
        cookies.put(k, v);
        invalidateCookieHeaderCache();
        persistCookies();
        if("cf_clearance".equalsIgnoreCase(k) && v.length() > 0)
            saveClearanceToDisk();
    }

    public synchronized void setUserAgent(String userAgent) {
        if(userAgent == null || userAgent.trim().length() == 0)
            return;
        agent = userAgent.trim();
        context.getSharedPreferences("mangaView", Context.MODE_PRIVATE)
                .edit()
                .putString("httpUserAgent", agent)
                .apply();
    }
    public synchronized void removeCookie(String k) {
        cookies.remove(k);
        invalidateCookieHeaderCache();
        persistCookies();
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
        return hasCloudflareClearance();
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
        clearCloudflareCookies();
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
                    .apply();
        } catch (Exception e) {
            ml.melun.mangaview.report.CrashReporter.record(e);
        }
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
        boolean changed = false;
        boolean keepRecentNtkClearance = isNtk() && hasRecentNtkAccessVerification();
        for(String key : new ArrayList<>(cookies.keySet())) {
            String lower = key.toLowerCase(Locale.ROOT);
            if(keepRecentNtkClearance && "cf_clearance".equals(lower))
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
        if(!keepRecentNtkClearance) {
            context.getSharedPreferences("mangaView", Context.MODE_PRIVATE)
                    .edit()
                    .remove("cfClearanceValue")
                    .remove("cfClearanceExpireAt")
                    .apply();
        }
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
                manager.setCookie(url, "cf_clearance=; Max-Age=0; Path=/; Domain=" + LEGACY_NTK_HOST);
                manager.setCookie(url, "cf_clearance=; Max-Age=0; Path=/; Domain=." + LEGACY_NTK_HOST);
                manager.setCookie(url, "__cf_bm=; Max-Age=0; Path=/");
                manager.setCookie(url, "__cf_bm=; Max-Age=0; Path=/; Domain=" + NTK_HOST);
                manager.setCookie(url, "__cf_bm=; Max-Age=0; Path=/; Domain=." + NTK_HOST);
                manager.setCookie(url, "__cf_bm=; Max-Age=0; Path=/; Domain=" + PREVIOUS_NTK_HOST);
                manager.setCookie(url, "__cf_bm=; Max-Age=0; Path=/; Domain=." + PREVIOUS_NTK_HOST);
                manager.setCookie(url, "__cf_bm=; Max-Age=0; Path=/; Domain=" + OLDER_NTK_HOST);
                manager.setCookie(url, "__cf_bm=; Max-Age=0; Path=/; Domain=." + OLDER_NTK_HOST);
                manager.setCookie(url, "__cf_bm=; Max-Age=0; Path=/; Domain=" + LEGACY_NTK_HOST);
                manager.setCookie(url, "__cf_bm=; Max-Age=0; Path=/; Domain=." + LEGACY_NTK_HOST);
            }
            manager.flush();
            clearCloudflareCookies();
            clearNtkAccessVerification();
        } catch (Exception e) {
            ml.melun.mangaview.report.CrashReporter.record(e);
        }
    }
    public synchronized void resetCookie(){
        this.cookies = new HashMap<>();
        this.cookieSyncAt = new HashMap<>();
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

            if(ntkSite)
                appendNtkApiDiagnostic(report, root);
            else
                appendWfwfHttpDiagnostic(report, root);
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
        OkHttpClient primaryClient = fastNtkPageDirect ? ntkPageFastClient
                : fastWolfSearchDirect ? wolfSearchFastClient
                : fastWolfPageDirect ? wolfPageFastClient : this.client;
        OkHttpClient fallbackClient = fastNtkPageDirect ? unsafeNtkPageFastClient
                : fastWolfSearchDirect ? unsafeWolfSearchFastClient
                : fastWolfPageDirect ? unsafeWolfPageFastClient : this.unsafeFallbackClient;
        try {
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
                if(!allowUnsafeFallback(url))
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
        String cacheKey = getBaseUrl(normalized) + normalized;
        long now = System.currentTimeMillis();
        FetchMode fetchMode = effectiveFetchMode(FetchMode.ALLOW_SHARED_WEBVIEW);
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
        if(isNtk() || shouldResolveWfwfBeforeCachedPage(normalized, staleCached != null, fetchMode))
            ensureNumberedDomain(false);
        synchronized (pageLoadsLock) {
            activeLoad = pageLoads.get(cacheKey);
            if(activeLoad == null)
                pageLoads.put(cacheKey, new PageLoadState());
        }
        if(activeLoad != null)
            return waitForCachedPage(normalized, cacheKey, activeLoad, ttlMillis, staleCached);
        String loadKey = cacheKey;

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

    public boolean warmupCachedPageDirect(String url, long ttlMillis) {
        try {
            String normalized = normalizePath(url);
            if(isNtk() || shouldResolveWfwfBeforeCachedPage(normalized, false, FetchMode.DIRECT_ONLY))
                ensureNumberedDomain(false);
            String cacheKey = getBaseUrl(normalized) + normalized;
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
            lastCloudflareChallengeUrl = getBaseUrl(normalized) + normalized;
            lastCloudflareChallengeAt = System.currentTimeMillis();
            if(code == 403)
                clearCloudflareCookies();
            throw new Exception(code == 403 ? "Cloudflare challenge" : "Cloudflare/server error");
        }
        if(isNtk())
            clearLastCloudflareChallenge();
        if(shouldRejectWfwfPageBody(normalized, code, body)) {
            if(staleCached != null)
                return new PageResponse(staleCached.code, staleCached.body, true);
            throw new Exception("Unusable WFWF page: " + normalized);
        }
        if(code >= 200 && code < 400 && body.length() > 0
                && !(isNtk() && isNtkTokenizedViewerPath(normalized))
                && shouldStoreNetworkPageBody(normalized, body)) {
            String cacheKey = getBaseUrl(normalized) + normalized;
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
            String currentCacheKey = getBaseUrl(normalized) + normalized;
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
            return 2;
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
        return path.matches("^/(manhwa|webtoon)/\\d+/[^/?#%]+/?(?:[?#].*)?$");
    }

    private static boolean isNtkTokenizedViewerCacheKey(String cacheKey, String body) {
        if(cacheKey == null || body == null)
            return false;
        String lower = cacheKey.toLowerCase(Locale.ROOT);
        if(!lower.matches("^https?://[^/]+/(manhwa|webtoon)/\\d+/[^/?#%]+/?(?:[?#].*)?$"))
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
        boolean fastNtkPageDirect = shouldUseFastNtkPageDirect(ntkBaseUrl, url, fetchMode);
        boolean wolfWebViewFallbackAllowed = allowsWolfWebViewFallback();
        RequestGroup requestGroup = currentRequestGroup.get();
        boolean prioritizeWolfWebView = requestGroup != null
                && requestGroup.prioritizesWebViewFallback()
                && !ntkBaseUrl
                && shouldUseWolfWebViewFallback(ntkBaseUrl, true, url, fetchMode, true);
        boolean prioritizeNtkEpisodeWebView = ntkBaseUrl
                && fetchMode == FetchMode.ALLOW_SHARED_WEBVIEW
                && isNtkEpisodeDocumentPath(url)
                && MainApplication.currentActivity != null;
        Response response = (prioritizeWolfWebView || prioritizeNtkEpisodeWebView)
                ? getWithNtkWebViewFallback(baseUrl, url, headers) : null;
        if(response == null)
            response = get(baseUrl + url, headers, fastNtkPageDirect);
        if(ntkBaseUrl && shouldRetryWithResolvedDomain(response)) {
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
            fastNtkPageDirect = shouldUseFastNtkPageDirect(ntkBaseUrl, url, fetchMode);
            response = get(baseUrl + url, headers, fastNtkPageDirect);
        }
        if(shouldUseNtkWebViewFallback(ntkBaseUrl,
                response == null || isNtkWebViewFallbackCandidate(response, url), url, fetchMode)) {
            if(response != null) {
                rememberCloudflareChallengeIfPresent(response, baseUrl, url);
                response.close();
            }
            response = getWithNtkQuicFallback(baseUrl, url, headers);
            if(response != null)
                return response;
            response = getWithNtkWebViewFallback(baseUrl, url, headers);
        }
        if(!ntkBaseUrl && allowWfwfDomainRetry && shouldRetryWithResolvedDomain(response)) {
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
            fastNtkPageDirect = shouldUseFastNtkPageDirect(ntkBaseUrl, url, fetchMode);
            response = get(baseUrl + url, headers, fastNtkPageDirect);
        }
        if(shouldUseWolfWebViewFallback(ntkBaseUrl, response == null, url, fetchMode, wolfWebViewFallbackAllowed)) {
            response = getWithNtkWebViewFallback(baseUrl, url, headers);
        }
        return response;
    }

    private Response getWithNtkQuicFallback(String baseUrl, String path, Map<String, String> headers) {
        if(context == null || !isNtkUrl(baseUrl) || !NtkQuicFetcher.isAvailable())
            return null;
        try {
            String url = baseUrl + path;
            String cookieHeader = headers == null ? "" : headers.get("Cookie");
            NtkQuicFetcher.Result result = NtkQuicFetcher.fetch(context, url, agent,
                    cookieHeader == null ? "" : cookieHeader, headers, 12_000L);
            if(result == null || result.code <= 0 || result.bodyBytes == null || result.bodyBytes.length == 0)
                return null;
            if(isCloudflareChallenge(result.code, result.body))
                return null;
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
            Request request = new Request.Builder().url(url).build();
            return new Response.Builder()
                    .request(request)
                    .protocol(Protocol.HTTP_2)
                    .code(result.code)
                    .message("HttpEngine")
                    .body(ResponseBody.create(result.bodyBytes, MediaType.parse(result.contentType())))
                    .build();
        } catch (Exception e) {
            if(Log.isLoggable(TAG, Log.DEBUG))
                Log.d(TAG, "ntk_quic_fallback_failed path=" + path, e);
            return null;
        }
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
                lastCloudflareChallengeUrl = (baseUrl == null ? "" : baseUrl) + (path == null ? "" : path);
                lastCloudflareChallengeAt = System.currentTimeMillis();
                if(code == 403)
                    clearCloudflareCookies();
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

    private Map<String, String> buildHeaders(String baseUrl, Boolean useDefaultCookies, Map<String, String> customCookie) {
        Map<String, String> cookie = new HashMap<>();
        if(Boolean.TRUE.equals(useDefaultCookies)) {
            if(!shouldSkipWebViewCookieSync(baseUrl))
                syncCookiesFromWebView(baseUrl);
            synchronized (this) {
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

    private void putClientHintHeaders(Map<String, String> headers) {
        int chromeMajor = chromeMajorVersion(agent);
        String version = chromeMajor > 0 ? String.valueOf(chromeMajor) : "147";
        headers.put("sec-ch-ua", "\"Chromium\";v=\"" + version + "\", \"Android WebView\";v=\"" + version + "\", \"Not A(Brand\";v=\"24\"");
        headers.put("sec-ch-ua-mobile", "?1");
        headers.put("sec-ch-ua-platform", "\"Android\"");
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

    private boolean shouldRetryWithResolvedDomain(Response response) {
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
            return shouldRetryNtkWithResolvedDomain(code, body);
        }
        return code == 301 || code == 302 || code == 403 || code == 404 || code >= 500;
    }

    private static boolean shouldRetryNtkWithResolvedDomain(int code, String body) {
        if(code == 301 || code == 302 || code == 403 || code == 404 || code >= 500)
            return true;
        return false;
    }

    static boolean shouldRetryNtkWithResolvedDomainForTest(int code, String body) {
        return shouldRetryNtkWithResolvedDomain(code, body);
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
                && !lower.contains("just a moment")
                && !lower.contains("challenges.cloudflare.com")
                && !lower.contains("cf-challenge")
                && !lower.contains("cf_chl")
                && !lower.contains("cf-mitigated")
                && !lower.contains("turnstile")
                && !looksLikeUnrenderedNtkDocument(null, 200, body);
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
        String lower = body.toLowerCase(Locale.ROOT);
        if(!looksLikeNtkNextShell(lower))
            return false;
        return !hasRenderedNtkDocumentContent(lower);
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

    private static boolean hasRenderedNtkDocumentContent(String lowerBody) {
        if(lowerBody == null || lowerBody.length() == 0)
            return false;
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

    private static boolean isCloudflareChallenge(int code, String body) {
        if(body == null)
            return false;
        if(code >= 500) {
            String lower = body.toLowerCase(Locale.ROOT);
            return lower.contains("cloudflare")
                    || lower.contains("error code 522")
                    || lower.contains("connection timed out");
        }
        if(code != 403)
            return false;
        String lower = body.toLowerCase(Locale.ROOT);
        if(looksLikeNtkNormalPage(lower))
            return false;
        return lower.contains("cf-mitigated")
                || lower.contains("challenges.cloudflare.com")
                || lower.contains("cf-challenge")
                || lower.contains("cf-turnstile")
                || lower.contains("cf-please-wait")
                || lower.contains("turnstile")
                || lower.contains("__cf_bm")
                || lower.contains("checking your browser")
                || lower.contains("just a moment");
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

    public Response post(String url, RequestBody body, Map<String,String> headers){
        return post(url,body,headers,false);
    }

    public List<String> fetchNtkViewerImageUrls(String segment, String workId, String episodeId,
                                                String imagesToken, String viewerBody) {
        List<String> urls = new ArrayList<>();
        if(context == null || !isNtk() || !NtkQuicFetcher.isAvailable()
                || segment == null || workId == null || episodeId == null || imagesToken == null
                || imagesToken.length() == 0)
            return urls;
        String kind = "webtoon".equals(segment) ? "webtoon" : "manhwa";
        String endpoint = "webtoon".equals(kind) ? "/api/webtoon-images" : "/api/manhwa-images";
        String baseUrl = getBaseUrl("/" + kind + "/" + workId + "/" + episodeId);
        String path = "/" + kind + "/" + workId + "/" + episodeId;
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
            String nonce = base64Url(randomBytes(24));
            String proof = hmacSha256Base64Url(nv, imagesToken + "." + nonce + "." + agent);
            JSONObject payload = new JSONObject();
            payload.put("workId", workId);
            payload.put("episodeId", episodeId);
            payload.put("token", imagesToken);
            payload.put("nonce", nonce);
            payload.put("proof", proof);
            Map<String, String> headers = new HashMap<>();
            headers.put("content-type", "application/json");
            headers.put("accept", "application/json");
            headers.put("x-images-client", "viewer-v1");
            headers.put("origin", baseUrl);
            headers.put("referer", baseUrl + "/" + kind + "/" + workId + "/" + episodeId);
            // Attempt native ACK bypass first for cold-start speed
            boolean nativeAckCompleted = performNtkNativeAckBypass(baseUrl, path);
            if(nativeAckCompleted) {
                if(Log.isLoggable(TAG, Log.DEBUG))
                    Log.d(TAG, "ntk_native_ack_bypass_success path=" + path);
                // Re-fetch nv after ACK success; ACK may have refreshed session cookies
                String nvAfterAck = getCookie("nv");
                if(!isNtkNvValid(nvAfterAck)) {
                    issueNtkNvCookie(baseUrl);
                    nvAfterAck = getCookie("nv");
                }
                if(isNtkNvValid(nvAfterAck) && !nvAfterAck.equals(nv)) {
                    nv = nvAfterAck;
                    proof = hmacSha256Base64Url(nv, imagesToken + "." + nonce + "." + agent);
                    payload.put("proof", proof);
                    if(Log.isLoggable(TAG, Log.DEBUG))
                        Log.d(TAG, "ntk_native_ack_nv_refreshed proof_recomputed");
                }
                NtkQuicFetcher.Result result = NtkQuicFetcher.fetch(context, baseUrl + endpoint, agent,
                        getCookieHeader(), headers, "POST",
                        payload.toString().getBytes(StandardCharsets.UTF_8), 30000L);
                ViewerWarmupManager.logMetric("ntk_images_api_code", result.code);
                if(Log.isLoggable(TAG, Log.DEBUG)) {
                    Log.d(TAG, "ntk_native_images_code=" + (result == null ? "null" : result.code)
                            + ",error=" + (result == null || result.error == null ? "null" : result.error));
                    if(result != null && result.body != null)
                        Log.d(TAG, "ntk_native_images_body=" + result.body.substring(0, Math.min(1200, result.body.length())));
                }
                if(result.error == null && result.code >= 200 && result.code < 300 && result.body != null) {
                    JSONObject response = new JSONObject(result.body);
                    if(response.optBoolean("ad_ack_required", false)) {
                        ViewerWarmupManager.logMetric("ntk_native_ack_false_positive", 1);
                        if(Log.isLoggable(TAG, Log.DEBUG))
                            Log.d(TAG, "ntk_native_ack_false_positive path=" + path);
                        // Skip to WebView fallback instead of treating as success
                    } else if(response.optBoolean("ok", false)) {
                        JSONArray images = response.optJSONArray("images");
                        if(images != null) {
                            for(int i = 0; i < images.length(); i++) {
                                JSONObject image = images.optJSONObject(i);
                                String src = image == null ? "" : image.optString("src", "");
                                if(src.length() > 0)
                                    urls.add(src);
                            }
                            if(urls.size() > 0)
                                return urls;
                        }
                    }
                }
            }
            // Fall back to WebView if native ACK bypass failed or returned no images
            if(MainApplication.currentActivity != null) {
                urls.addAll(NtkWebViewFallbackManager.get(context).fetchViewerImageUrls(agent, baseUrl,
                        path, headers, kind, workId, episodeId, imagesToken));
                if(urls.size() > 0)
                    return urls;
            }
            // Last-resort native fallback without ACK
            NtkQuicFetcher.Result result = NtkQuicFetcher.fetch(context, baseUrl + endpoint, agent,
                    getCookieHeader(), headers, "POST",
                    payload.toString().getBytes(StandardCharsets.UTF_8), 30000L);
            ViewerWarmupManager.logMetric("ntk_images_api_code", result.code);
            if(result.error != null || result.code < 200 || result.code >= 300 || result.body == null)
                return urls;
            JSONObject response = new JSONObject(result.body);
            if(!response.optBoolean("ok", false))
                return urls;
            JSONArray images = response.optJSONArray("images");
            if(images == null)
                return urls;
            for(int i = 0; i < images.length(); i++) {
                JSONObject image = images.optJSONObject(i);
                String src = image == null ? "" : image.optString("src", "");
                if(src.length() > 0)
                    urls.add(src);
            }
        } catch (Exception e) {
            ml.melun.mangaview.report.CrashReporter.record(e);
        }
        return urls;
    }

    private static boolean isNtkNvValid(String nv) {
        if(nv == null || nv.length() == 0)
            return false;
        String[] parts = nv.split("\\.");
        return parts.length >= 1 && parts[0].length() >= 40;
    }

    private void issueNtkNvCookie(String baseUrl) {
        try {
            Map<String, String> headers = new HashMap<>();
            headers.put("accept", "application/json");
            headers.put("origin", baseUrl);
            headers.put("referer", baseUrl + "/");
            NtkQuicFetcher.Result result = NtkQuicFetcher.fetch(context, baseUrl + "/api/nv-issue", agent,
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
                webView.loadDataWithBaseURL("https://sbxh3.com", jsHtml, "text/html", "UTF-8", null);
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
        if(baseUrl == null || path == null || baseUrl.length() == 0 || path.length() == 0)
            return false;
        String cacheKey = baseUrl + path;
        Long cached = NTK_ACK_CACHE.get(cacheKey);
        if(cached != null && System.currentTimeMillis() - cached < NTK_ACK_CACHE_TTL_MS) {
            if(Log.isLoggable(TAG, Log.DEBUG))
                Log.d(TAG, "ntk_native_ack_cache_hit path=" + path);
            return true;
        }
        HttpEngine engine = null;
        try {
            String host = URI.create(baseUrl).getHost();
            engine = new HttpEngine.Builder(context.getApplicationContext())
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
            Map<String, String> h = new HashMap<>();
            h.put("origin", baseUrl);
            h.put("referer", baseUrl + path);
            h.put("accept", "*/*");

            if(DUMP_NTK_ACK_DEBUG_ARTIFACTS) {
                // Debug-only artifact dumps are expensive and must not run during normal image loading.
                dumpNtkAckDebugArtifacts(baseUrl, path);
            }

            // Fetch encrypted ad_guard_bg.wasm (JS will decrypt it internally)
            byte[] wasmBytes = null;
            try {
                Map<String, String> wasmHeaders = new HashMap<>();
                wasmHeaders.put("referer", baseUrl + path);
                NtkQuicFetcher.Result wasmResult = NtkQuicFetcher.fetchWithEngine(engine, baseUrl + "/wasm/ad-guard/ad_guard_bg.wasm", agent,
                        getCookieHeader(), wasmHeaders, "GET", null, 30000L);
                int wasmCode = wasmResult == null ? 0 : wasmResult.code;
                Log.e(TAG, "ntk_wasm_fetch_code=" + wasmCode);
                if(wasmResult != null && wasmResult.bodyBytes != null && wasmResult.bodyBytes.length > 100) {
                    wasmBytes = wasmResult.bodyBytes;
                    Log.e(TAG, "ntk_wasm_body_len=" + wasmBytes.length);
                }
            } catch(Exception ignored) {}

            // 1. POST /api/ad/challenge
            JSONObject challengePayload = new JSONObject();
            challengePayload.put("path", path);
            NtkQuicFetcher.Result challenge = NtkQuicFetcher.fetchWithEngine(engine, baseUrl + "/api/ad/challenge", agent,
                    getCookieHeader(), h, "POST",
                    challengePayload.toString().getBytes(StandardCharsets.UTF_8), 15000L);
            Log.d(TAG, "ntk_native_ack_challenge_code=" + (challenge == null ? "null" : challenge.code));
            if(challenge == null || challenge.error != null || challenge.code != 200 || challenge.body == null)
                return false;
            applySetCookieHeaders(challenge.headers, baseUrl);
            JSONObject challengeJson = new JSONObject(challenge.body);
            if(!challengeJson.optBoolean("ok", false))
                return false;
            JSONObject challengeObj = challengeJson.optJSONObject("challenge");
            if(challengeObj == null)
                return false;
            String token = challengeObj.optString("token", "");
            JSONArray impressionUrls = challengeObj.optJSONArray("impressionUrls");
            Log.d(TAG, "ntk_native_ack_challenge_token_len=" + token.length() + ",impressions=" + (impressionUrls == null ? 0 : impressionUrls.length()));
            String scope = extractNtkChallengeScope(token);
            String challengePath = scope.length() > 0 ? scope : path;
            Log.d(TAG, "ntk_native_ack_scope=" + scope + ",challengePath=" + challengePath);
            String cookiesAfterChallenge = getCookieHeader();
            String nvAfterChallenge = getCookie("nv");
            Log.d(TAG, "ntk_native_ack_cookies_after_challenge nv=" + (nvAfterChallenge == null ? "null" : nvAfterChallenge.substring(0, Math.min(60, nvAfterChallenge.length()))) + " cookies_len=" + (cookiesAfterChallenge == null ? 0 : cookiesAfterChallenge.length()));
            Map<String, String> h2 = new HashMap<>(h);
            h2.put("referer", baseUrl + challengePath);

            // 2. GET impression URLs (ad tracking pixels) and apply cookies
            if(impressionUrls != null && impressionUrls.length() > 0) {
                final android.net.http.HttpEngine engineFinal = engine;
                java.util.concurrent.ExecutorService impExecutor = java.util.concurrent.Executors.newFixedThreadPool(Math.min(4, impressionUrls.length()));
                java.util.List<java.util.concurrent.Future<?>> impFutures = new java.util.ArrayList<>();
                for(int i = 0; i < impressionUrls.length(); i++) {
                    final int idx = i;
                    String url = impressionUrls.optString(i, "");
                    if(url.length() == 0) continue;
                    if(!url.startsWith("http")) url = baseUrl + url;
                    final String impUrl = url;
                    impFutures.add(impExecutor.submit(() -> {
                        try {
                            NtkQuicFetcher.Result imp = NtkQuicFetcher.fetchWithEngine(engineFinal, impUrl, agent,
                                    getCookieHeader(), Collections.emptyMap(), "GET", null, 10000L);
                            Log.d(TAG, "ntk_native_ack_imp i=" + idx + ",code=" + (imp == null ? "null" : imp.code));
                            if(imp != null) applySetCookieHeaders(imp.headers, baseUrl);
                        } catch(Exception e) {
                            Log.d(TAG, "ntk_native_ack_imp_error i=" + idx + "," + e);
                        }
                    }));
                }
                for(java.util.concurrent.Future<?> f : impFutures) {
                    try { f.get(12000L, java.util.concurrent.TimeUnit.MILLISECONDS); } catch(Exception ignored) {}
                }
                impExecutor.shutdownNow();
            }

            // 2.5 Transform challenge token using ad_guard WASM via WebView
            // OPTIMIZATION: skip WASM/JS fetch and _vc WebView execution;
            // the original challenge token works directly for canary/ack.
            // Saves ~2-4s of WebView overhead per ACK.
            Log.d(TAG, "ntk_native_ack_vc_skipped token_len=" + token.length());

            // 3. POST /api/ad/canary with challenge token
            Log.d(TAG, "ntk_native_ack_token_before_canary len=" + token.length());
            int slotCount = challengeObj.optInt("slotCount", 4);
            int minSeen = challengeObj.optInt("minSeen", 2);
            JSONObject canaryPayload = new JSONObject();
            canaryPayload.put("adAckCanary", true);
            canaryPayload.put("challengeToken", token);
            canaryPayload.put("token", token);
            canaryPayload.put("path", challengePath);
            NtkQuicFetcher.Result canary = NtkQuicFetcher.fetchWithEngine(engine, baseUrl + "/api/ad/canary", agent,
                    getCookieHeader(), h2, "POST",
                    canaryPayload.toString().getBytes(StandardCharsets.UTF_8), 10000L);
            Log.d(TAG, "ntk_native_ack_canary_code=" + (canary == null ? "null" : canary.code));
            if(canary != null && canary.body != null) {
                Log.d(TAG, "ntk_native_ack_canary_body=" + canary.body.substring(0, Math.min(300, canary.body.length())));
            }
            if(canary != null) applySetCookieHeaders(canary.headers, baseUrl);

            // 4. POST /api/ad/ack with challenge token
            // WebView sends additional metrics: total, visible, td, tp
            // tp is a proof computed by ad_guard.js; we leave it empty for now
            JSONObject ackPayload = new JSONObject();
            ackPayload.put("challengeToken", token);
            ackPayload.put("total", slotCount);
            ackPayload.put("visible", minSeen);
            ackPayload.put("path", challengePath);
            ackPayload.put("td", 0);
            ackPayload.put("tp", "");
            NtkQuicFetcher.Result ack = NtkQuicFetcher.fetchWithEngine(engine, baseUrl + "/api/ad/ack", agent,
                    getCookieHeader(), h2, "POST",
                    ackPayload.toString().getBytes(StandardCharsets.UTF_8), 10000L);
            Log.d(TAG, "ntk_native_ack_ack_code=" + (ack == null ? "null" : ack.code));
            if(ack != null) applySetCookieHeaders(ack.headers, baseUrl);
            boolean ackBodyOk = false;
            String ackStatus = null;
            String ackError = null;
            if(ack != null && ack.body != null) {
                String bodyPreview = ack.body.substring(0, Math.min(500, ack.body.length()));
                Log.d(TAG, "ntk_native_ack_ack_body=" + bodyPreview);
                try {
                    JSONObject ackJson = new JSONObject(ack.body);
                    ackBodyOk = ackJson.optBoolean("ok", false);
                    ackStatus = ackJson.optString("status", null);
                    ackError = ackJson.optString("error", null);
                    if(!ackBodyOk && ackStatus == null && ackError == null) {
                        // Some endpoints return {acked:true} instead of {ok:true}
                        ackBodyOk = ackJson.optBoolean("acked", false);
                    }
                    Log.d(TAG, "ntk_native_ack_parsed ok=" + ackBodyOk + ",status=" + ackStatus + ",error=" + ackError);
                } catch(Exception parseEx) {
                    Log.d(TAG, "ntk_native_ack_ack_parse_error=" + parseEx);
                }
            }
            boolean ackSuccess = ack != null && ack.code == 200 && ackBodyOk;
            Log.d(TAG, "ntk_native_ack_final_success=" + ackSuccess);
            if(ackSuccess) {
                NTK_ACK_CACHE.put(cacheKey, System.currentTimeMillis());
            }
            return ackSuccess;
        } catch(Exception e) {
            Log.d(TAG, "ntk_native_ack_bypass_exception=" + e);
            return false;
        } finally {
            if(engine != null)
                engine.shutdown();
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
                    manager.flush();
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
        return e instanceof InterruptedIOException
                || Thread.currentThread().isInterrupted()
                || "Canceled".equals(e.getMessage());
    }

    public interface RequestWork<T> {
        T run() throws Exception;
    }

    public static class RequestGroup {
        private final Set<Call> calls = java.util.Collections.synchronizedSet(new java.util.HashSet<>());
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
        return baseClient(builder)
                .connectTimeout(NTK_PAGE_DIRECT_TIMEOUT_MS, TimeUnit.MILLISECONDS)
                .readTimeout(NTK_PAGE_DIRECT_TIMEOUT_MS, TimeUnit.MILLISECONDS)
                .callTimeout(NTK_PAGE_DIRECT_TIMEOUT_MS, TimeUnit.MILLISECONDS);
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
