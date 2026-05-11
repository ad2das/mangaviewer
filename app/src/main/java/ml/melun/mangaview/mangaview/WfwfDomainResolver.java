package ml.melun.mangaview.mangaview;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import okhttp3.Call;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

public class WfwfDomainResolver {
    private static final Pattern WFWF_PATTERN = Pattern.compile("^https?://wfwf(\\d+)\\.com(?:/cm)?/?$");
    private static final Pattern NUMBERED_DOMAIN_PATTERN = Pattern.compile("^https?://(wfwf|ntk)(\\d+)\\.com(?:/cm)?/?$");
    private static final int DEFAULT_NUMBER = 450;
    private static final int DEFAULT_NTK_NUMBER = 1;
    private static final int FORWARD_SCAN_LIMIT = 300;
    private static final int BACKWARD_SCAN_LIMIT = 5;
    private static final long RESOLVE_TIMEOUT_MS = 15_000L;

    public static String resolve(OkHttpClient client, String currentUrl, Map<String, String> headers) {
        return resolve(client, currentUrl, headers, null);
    }

    public static String resolve(OkHttpClient client, String currentUrl, Map<String, String> headers, CustomHttpClient.RequestGroup requestGroup) {
        Domain domain = parseDomain(currentUrl);
        if(domain == null)
            domain = new Domain("wfwf", DEFAULT_NUMBER, 0);

        OkHttpClient probeClient = client.newBuilder()
                .connectTimeout(2, TimeUnit.SECONDS)
                .readTimeout(3, TimeUnit.SECONDS)
                .callTimeout(4, TimeUnit.SECONDS)
                .build();

        String currentRoot = domain.root();
        if(isAlive(probeClient, currentRoot, headers, requestGroup))
            return currentRoot;

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
        for(int i = 1; i <= FORWARD_SCAN_LIMIT; i++)
            add(roots, seen, domain, domain.number + i);
        add(roots, seen, domain, defaultNumber(domain.prefix));
        for(int i = 1; i <= FORWARD_SCAN_LIMIT; i++)
            add(roots, seen, domain, defaultNumber(domain.prefix) + i);
        for(int i = 1; i <= BACKWARD_SCAN_LIMIT; i++)
            add(roots, seen, domain, domain.number - i);
        return roots;
    }

    private static String findAliveCandidate(OkHttpClient client, List<String> candidates, Map<String, String> headers, CustomHttpClient.RequestGroup requestGroup, long deadlineMs) {
        for(String root : candidates) {
            if(requestGroup != null && requestGroup.isCancelled())
                return null;
            if(deadlineMs - System.currentTimeMillis() <= 0)
                return null;
            if(isAlive(client, root, headers, requestGroup))
                return root;
        }
        return null;
    }

    private static void add(List<String> roots, Set<Integer> seen, Domain domain, int number) {
        if(number > 0 && seen.add(number))
            roots.add(domain.root(number));
    }

    private static int defaultNumber(String prefix) {
        return "ntk".equals(prefix) ? DEFAULT_NTK_NUMBER : DEFAULT_NUMBER;
    }

    private static boolean isAlive(OkHttpClient client, String root, Map<String, String> headers, CustomHttpClient.RequestGroup requestGroup) {
        boolean ntk = root != null && (root.contains("://ntk") || root.contains("://newtoki") || root.contains("://sbxh") || root.contains("://www.sbxh"));
        String comicPath = ntk ? "/manhwa" : "/cm";
        return probe(client, root + "/ing", headers, requestGroup) || probe(client, root + comicPath, headers, requestGroup);
    }

    private static boolean probe(OkHttpClient client, String url, Map<String, String> headers, CustomHttpClient.RequestGroup requestGroup) {
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
            return code >= 200 && code < 500 && looksLikeWfwf(body);
        } catch (Exception e) {
            return false;
        } finally {
            if(requestGroup != null && call != null)
                requestGroup.remove(call);
            if(response != null)
                response.close();
        }
    }

    private static boolean looksLikeWfwf(String body) {
        if(body == null)
            return false;
        String lower = body.toLowerCase(Locale.ROOT);
        return lower.contains("webtoon-list")
                || lower.contains("toon=")
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
}
