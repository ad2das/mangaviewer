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
    private static final int DEFAULT_NUMBER = 449;
    private static final int FORWARD_SCAN_LIMIT = 300;
    private static final int BACKWARD_SCAN_LIMIT = 5;
    private static final long RESOLVE_TIMEOUT_MS = 15_000L;

    public static String resolve(OkHttpClient client, String currentUrl, Map<String, String> headers) {
        return resolve(client, currentUrl, headers, null);
    }

    public static String resolve(OkHttpClient client, String currentUrl, Map<String, String> headers, CustomHttpClient.RequestGroup requestGroup) {
        int current = getNumber(currentUrl);
        if(current <= 0)
            current = DEFAULT_NUMBER;

        OkHttpClient probeClient = client.newBuilder()
                .connectTimeout(2, TimeUnit.SECONDS)
                .readTimeout(3, TimeUnit.SECONDS)
                .callTimeout(4, TimeUnit.SECONDS)
                .build();

        String currentRoot = "https://wfwf" + current + ".com";
        if(isAlive(probeClient, currentRoot, headers, requestGroup))
            return currentRoot;

        return findAliveCandidate(probeClient, candidates(current), headers, requestGroup, System.currentTimeMillis() + RESOLVE_TIMEOUT_MS);
    }

    public static boolean isWfwfUrl(String url) {
        return getNumber(url) > 0;
    }

    public static String toRoot(String url) {
        if(url == null)
            return "";
        String trimmed = trimTrailingSlash(url);
        if(trimmed.endsWith("/cm"))
            return trimmed.substring(0, trimmed.length() - 3);
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

    private static List<Integer> candidates(int current) {
        ArrayList<Integer> numbers = new ArrayList<>();
        Set<Integer> seen = new HashSet<>();
        for(int i = 1; i <= FORWARD_SCAN_LIMIT; i++)
            add(numbers, seen, current + i);
        add(numbers, seen, DEFAULT_NUMBER);
        for(int i = 1; i <= FORWARD_SCAN_LIMIT; i++)
            add(numbers, seen, DEFAULT_NUMBER + i);
        for(int i = 1; i <= BACKWARD_SCAN_LIMIT; i++)
            add(numbers, seen, current - i);
        return numbers;
    }

    private static String findAliveCandidate(OkHttpClient client, List<Integer> candidates, Map<String, String> headers, CustomHttpClient.RequestGroup requestGroup, long deadlineMs) {
        for(Integer number : candidates) {
            if(requestGroup != null && requestGroup.isCancelled())
                return null;
            if(deadlineMs - System.currentTimeMillis() <= 0)
                return null;
            String root = "https://wfwf" + number + ".com";
            if(isAlive(client, root, headers, requestGroup))
                return root;
        }
        return null;
    }

    private static void add(List<Integer> numbers, Set<Integer> seen, int number) {
        if(number > 0 && seen.add(number))
            numbers.add(number);
    }

    private static boolean isAlive(OkHttpClient client, String root, Map<String, String> headers, CustomHttpClient.RequestGroup requestGroup) {
        return probe(client, root + "/ing", headers, requestGroup) || probe(client, root + "/cm", headers, requestGroup);
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
}
