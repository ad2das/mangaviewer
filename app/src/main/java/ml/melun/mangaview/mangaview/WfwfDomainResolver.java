package ml.melun.mangaview.mangaview;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.ExecutorCompletionService;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import okhttp3.Call;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

public class WfwfDomainResolver {
    private static final Pattern WFWF_PATTERN = Pattern.compile("^https?://wfwf(\\d+)\\.com(?:/cm)?/?$");
    private static final Pattern NUMBERED_DOMAIN_PATTERN = Pattern.compile("^https?://(wfwf|ntk)(\\d+)\\.com(?:/(?:cm|manhwa))?/?$");
    private static final Pattern NUMBERED_ROOT_PATTERN = Pattern.compile("https?://(?:www\\.)?(wfwf|ntk)(\\d+)\\.com", Pattern.CASE_INSENSITIVE);
    private static final int DEFAULT_NUMBER = 450;
    private static final int DEFAULT_NTK_NUMBER = 1;
    private static final int FORWARD_SCAN_LIMIT = 300;
    private static final int BACKWARD_SCAN_LIMIT = 30;
    private static final int NEARBY_SCAN_LIMIT = 30;
    private static final long RESOLVE_TIMEOUT_MS = 6_000L;
    private static final int PARALLEL_PROBE_COUNT = 12;
    private static volatile long suppressDomainScanUntilMs = 0L;

    public static String resolve(OkHttpClient client, String currentUrl, Map<String, String> headers) {
        return resolve(client, currentUrl, headers, null);
    }

    public static String resolve(OkHttpClient client, String currentUrl, Map<String, String> headers, CustomHttpClient.RequestGroup requestGroup) {
        Domain domain = parseDomain(currentUrl);
        if(domain == null)
            domain = new Domain("wfwf", DEFAULT_NUMBER, 0);

        OkHttpClient probeClient = client.newBuilder()
                .connectTimeout(1, TimeUnit.SECONDS)
                .readTimeout(2, TimeUnit.SECONDS)
                .callTimeout(2500, TimeUnit.MILLISECONDS)
                .build();

        String currentRoot = domain.root();
        String resolved = resolveCandidate(probeClient, currentRoot, headers, requestGroup);
        if(resolved != null)
            return resolved;
        if(isDomainScanSuppressed()) {
            android.util.Log.d("PerfTrace", "wfwf_domain_scan_skipped=network_unavailable");
            return null;
        }

        return findAliveCandidate(probeClient, candidates(domain), headers, requestGroup, System.currentTimeMillis() + RESOLVE_TIMEOUT_MS);
    }

    public static String resolveReplacement(OkHttpClient client, String currentUrl, Map<String, String> headers, CustomHttpClient.RequestGroup requestGroup) {
        Domain domain = parseDomain(currentUrl);
        if(domain == null)
            domain = new Domain("wfwf", DEFAULT_NUMBER, 0);

        OkHttpClient probeClient = client.newBuilder()
                .connectTimeout(1, TimeUnit.SECONDS)
                .readTimeout(2, TimeUnit.SECONDS)
                .callTimeout(2500, TimeUnit.MILLISECONDS)
                .build();

        if(isDomainScanSuppressed()) {
            android.util.Log.d("PerfTrace", "wfwf_domain_scan_skipped=network_unavailable");
            return null;
        }
        return findAliveCandidate(probeClient, candidates(domain), headers, requestGroup, System.currentTimeMillis() + RESOLVE_TIMEOUT_MS);
    }

    public static boolean isWfwfUrl(String url) {
        return getNumber(url) > 0;
    }

    public static boolean isSupportedNumberedUrl(String url) {
        return parseDomain(url) != null;
    }

    public static String toRoot(String url) {
        if(url == null)
            return "";
        String trimmed = trimTrailingSlash(url);
        if(trimmed.endsWith("/cm"))
            return trimmed.substring(0, trimmed.length() - 3);
        if(trimmed.endsWith("/manhwa"))
            return trimmed.substring(0, trimmed.length() - 7);
        return trimmed;
    }

    private static int getNumber(String url) {
        if(url == null)
            return -1;
        Matcher matcher = WFWF_PATTERN.matcher(trimTrailingSlash(url));
        if(!matcher.matches())
            return -1;
        try {
            return Integer.parseInt(matcher.group(1));
        } catch (Exception e) {
            return -1;
        }
    }

    private static List<String> candidates(Domain domain) {
        ArrayList<String> roots = new ArrayList<>();
        Set<Integer> seen = new HashSet<>();
        for(int i = 1; i <= NEARBY_SCAN_LIMIT; i++) {
            add(roots, seen, domain, domain.number + i);
            add(roots, seen, domain, domain.number - i);
        }
        int defaultNumber = defaultNumber(domain.prefix);
        add(roots, seen, domain, defaultNumber);
        for(int i = 1; i <= NEARBY_SCAN_LIMIT; i++) {
            add(roots, seen, domain, defaultNumber + i);
            add(roots, seen, domain, defaultNumber - i);
        }
        for(int i = NEARBY_SCAN_LIMIT + 1; i <= FORWARD_SCAN_LIMIT; i++)
            add(roots, seen, domain, domain.number + i);
        for(int i = NEARBY_SCAN_LIMIT + 1; i <= BACKWARD_SCAN_LIMIT; i++)
            add(roots, seen, domain, domain.number - i);
        return roots;
    }

    private static String findAliveCandidate(OkHttpClient client, List<String> candidates, Map<String, String> headers, CustomHttpClient.RequestGroup requestGroup, long deadlineMs) {
        if(candidates == null || candidates.isEmpty())
            return null;
        ExecutorService executor = Executors.newFixedThreadPool(PARALLEL_PROBE_COUNT);
        ExecutorCompletionService<String> completion = new ExecutorCompletionService<>(executor);
        ArrayList<Future<String>> futures = new ArrayList<>();
        int submitted = 0;
        int completed = 0;
        try {
            while(submitted < candidates.size() && submitted < PARALLEL_PROBE_COUNT && deadlineMs - System.currentTimeMillis() > 0) {
                futures.add(submitProbe(completion, client, candidates.get(submitted++), headers, requestGroup));
            }
            while(completed < submitted) {
                if(requestGroup != null && requestGroup.isCancelled())
                    return null;
                long remaining = deadlineMs - System.currentTimeMillis();
                if(remaining <= 0)
                    return null;
                Future<String> future = completion.poll(remaining, TimeUnit.MILLISECONDS);
                if(future == null)
                    return null;
                completed++;
                String resolved = future.get();
                if(resolved != null)
                    return resolved;
                if(submitted < candidates.size() && deadlineMs - System.currentTimeMillis() > 0)
                    futures.add(submitProbe(completion, client, candidates.get(submitted++), headers, requestGroup));
            }
        } catch (Exception ignored) {
            return null;
        } finally {
            for(Future<String> future : futures)
                future.cancel(true);
            executor.shutdownNow();
        }
        return null;
    }

    private static Future<String> submitProbe(ExecutorCompletionService<String> completion, OkHttpClient client,
                                              String root, Map<String, String> headers,
                                              CustomHttpClient.RequestGroup requestGroup) {
        return completion.submit(() -> resolveCandidate(client, root, headers, requestGroup));
    }

    private static void add(List<String> roots, Set<Integer> seen, Domain domain, int number) {
        if(number > 0 && seen.add(number))
            roots.add(domain.root(number));
    }

    private static int defaultNumber(String prefix) {
        return "ntk".equals(prefix) ? DEFAULT_NTK_NUMBER : DEFAULT_NUMBER;
    }

    private static String resolveCandidate(OkHttpClient client, String root, Map<String, String> headers, CustomHttpClient.RequestGroup requestGroup) {
        boolean ntk = root != null && (root.contains("://ntk") || root.contains("://newtoki") || root.contains("://sbxh") || root.contains("://www.sbxh"));
        String comicPath = ntk ? "/manhwa" : "/cm";
        ProbeResult ing = probe(client, root + "/ing", headers, requestGroup);
        String hinted = normalizeVerifiedRoot(ing.updatedRoot);
        if(hinted != null && !hinted.equals(root))
            return hinted;
        if(ing.alive)
            return root;
        ProbeResult comic = probe(client, root + comicPath, headers, requestGroup);
        hinted = normalizeVerifiedRoot(comic.updatedRoot);
        if(hinted != null && !hinted.equals(root))
            return hinted;
        if(comic.alive)
            return root;
        return null;
    }

    private static String verifyUpdatedRoot(OkHttpClient client, String currentRoot, String updatedRoot,
                                            Map<String, String> headers, CustomHttpClient.RequestGroup requestGroup,
                                            Set<String> visited, int depth) {
        String root = normalizeVerifiedRoot(updatedRoot);
        if(root == null || root.equals(currentRoot) || depth > 3 || !visited.add(root))
            return null;
        boolean ntk = root.contains("://ntk") || root.contains("://newtoki") || root.contains("://sbxh") || root.contains("://www.sbxh");
        String comicPath = ntk ? "/manhwa" : "/cm";
        ProbeResult ing = probe(client, root + "/ing", headers, requestGroup);
        if(ing.alive)
            return root;
        String nested = verifyUpdatedRoot(client, root, ing.updatedRoot, headers, requestGroup, visited, depth + 1);
        if(nested != null)
            return nested;
        ProbeResult comic = probe(client, root + comicPath, headers, requestGroup);
        if(comic.alive)
            return root;
        return verifyUpdatedRoot(client, root, comic.updatedRoot, headers, requestGroup, visited, depth + 1);
    }

    static boolean shouldAcceptUpdatedRootForTest(String currentRoot, String updatedRoot) {
        String root = normalizeVerifiedRoot(updatedRoot);
        return root != null && !root.equals(currentRoot);
    }

    private static String normalizeVerifiedRoot(String updatedRoot) {
        if(updatedRoot == null)
            return null;
        String root = toRoot(updatedRoot);
        return root.length() == 0 || !isSupportedNumberedUrl(root) ? null : root;
    }

    private static ProbeResult probe(OkHttpClient client, String url, Map<String, String> headers, CustomHttpClient.RequestGroup requestGroup) {
        Response response = null;
        Call call = null;
        try {
            Request.Builder builder = new Request.Builder().url(url).get();
            if(headers != null)
                for(String key : headers.keySet())
                    builder.addHeader(key, headers.get(key));
            call = client.newCall(builder.build());
            if(requestGroup != null)
                requestGroup.add(call);
            response = call.execute();
            int code = response.code();
            String body = response.body() == null ? "" : response.body().string();
            if(code < 200 || code >= 500) {
                logProbe(url, code, body.length(), false, null);
                return ProbeResult.empty();
            }
            String updatedRoot = extractUpdatedRoot(body);
            if(updatedRoot != null && !updatedRoot.equals(rootFromUrl(url)))
                return logProbe(url, code, body.length(), false, updatedRoot);
            return logProbe(url, code, body.length(), looksLikeWfwf(body), null);
        } catch (Exception e) {
            android.util.Log.d("PerfTrace", "wfwf_probe_error url=" + url + ",error=" + e.getClass().getSimpleName());
            return ProbeResult.empty();
        } finally {
            if(requestGroup != null && call != null)
                requestGroup.remove(call);
            if(response != null)
                response.close();
        }
    }

    private static ProbeResult logProbe(String url, int code, int bodyLen, boolean alive, String updatedRoot) {
        android.util.Log.d("PerfTrace", "wfwf_probe url=" + url
                + ",code=" + code
                + ",len=" + bodyLen
                + ",alive=" + alive
                + ",updated=" + (updatedRoot == null ? "" : updatedRoot));
        return new ProbeResult(alive, updatedRoot);
    }

    private static String rootFromUrl(String url) {
        if(url == null)
            return "";
        Matcher matcher = NUMBERED_ROOT_PATTERN.matcher(url);
        if(!matcher.find())
            return toRoot(url);
        return "https://" + matcher.group(1).toLowerCase(Locale.ROOT) + matcher.group(2) + ".com";
    }

    static String extractUpdatedRootForTest(String body) {
        return extractUpdatedRoot(body);
    }

    static List<String> candidatesForTest(String currentUrl) {
        Domain domain = parseDomain(currentUrl);
        return domain == null ? new ArrayList<>() : candidates(domain);
    }

    static void suppressDomainScanForTest(long durationMs) {
        suppressDomainScanUntilMs = System.currentTimeMillis() + Math.max(0L, durationMs);
    }

    static boolean isDomainScanSuppressedForTest() {
        return isDomainScanSuppressed();
    }

    private static boolean isDomainScanSuppressed() {
        return suppressDomainScanUntilMs > System.currentTimeMillis();
    }

    private static String extractUpdatedRoot(String body) {
        if(body == null)
            return null;
        String lower = body.toLowerCase(Locale.ROOT);
        if(lower.contains("main-btn") || lower.contains("window.location.href")) {
            Matcher directMatcher = NUMBERED_ROOT_PATTERN.matcher(body);
            if(directMatcher.find())
                return "https://" + directMatcher.group(1).toLowerCase(Locale.ROOT)
                        + directMatcher.group(2) + ".com";
        }
        if(!lower.contains("주소") && !lower.contains("address") && !lower.contains("새로운") && !lower.contains("updated"))
            return null;
        Matcher matcher = NUMBERED_ROOT_PATTERN.matcher(body);
        if(!matcher.find())
            return null;
        String prefix = matcher.group(1).toLowerCase(Locale.ROOT);
        String digits = matcher.group(2);
        return "https://" + prefix + digits + ".com";
    }

    private static boolean looksLikeWfwf(String body) {
        if(body == null)
            return false;
        String lower = body.toLowerCase(Locale.ROOT);
        return lower.contains("webtoon-list")
                || lower.contains("/webtoon/")
                || lower.contains("/manhwa/")
                || lower.contains("/view?toon=")
                || lower.contains("/list?toon=")
                || lower.contains("/cv?toon=")
                || lower.contains("/cl?toon=");
    }

    private static String trimTrailingSlash(String url){
        String trimmed = url.trim();
        while(trimmed.endsWith("/"))
            trimmed = trimmed.substring(0, trimmed.length() - 1);
        return trimmed;
    }

    private static Domain parseDomain(String url) {
        if(url == null)
            return null;
        Matcher matcher = NUMBERED_DOMAIN_PATTERN.matcher(trimTrailingSlash(url));
        if(!matcher.matches())
            return null;
        try {
            String digits = matcher.group(2);
            return new Domain(matcher.group(1), Integer.parseInt(digits), digits.length());
        } catch (Exception e) {
            return null;
        }
    }

    private static class Domain {
        final String prefix;
        final int number;
        final int width;

        Domain(String prefix, int number, int width) {
            this.prefix = prefix;
            this.number = number;
            this.width = width;
        }

        String root() {
            return root(number);
        }

        String root(int value) {
            String digits = width > 1 ? String.format(Locale.ROOT, "%0" + width + "d", value) : String.valueOf(value);
            return "https://" + prefix + digits + ".com";
        }
    }

    private static class ProbeResult {
        final boolean alive;
        final String updatedRoot;

        ProbeResult(boolean alive, String updatedRoot) {
            this.alive = alive;
            this.updatedRoot = updatedRoot;
        }

        static ProbeResult empty() {
            return new ProbeResult(false, null);
        }
    }
}
