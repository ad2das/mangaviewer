package ml.melun.mangaview.mangaview;

import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;

import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import okhttp3.Call;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

public class NtkDomainResolver {
    public static final String CHANNEL_URL = "https://t.me/s/newtoki_url";
    private static final String[] CHANNEL_URLS = new String[]{
            "https://sbxh8.com",
            "https://sbxh7.com",
            CHANNEL_URL
    };
    private static final long RESOLVE_TIMEOUT_MS = 8_000L;

    public static String resolve(OkHttpClient client, Map<String, String> headers) {
        return resolve(client, headers, null);
    }

    public static String resolve(OkHttpClient client, Map<String, String> headers, CustomHttpClient.RequestGroup requestGroup) {
        List<String> roots = resolveCandidates(client, headers, requestGroup);
        return roots.isEmpty() ? null : roots.get(0);
    }

    public static List<String> resolveCandidates(OkHttpClient client, Map<String, String> headers, CustomHttpClient.RequestGroup requestGroup) {
        ArrayList<String> roots = new ArrayList<>();
        ArrayList<String> guideUrls = new ArrayList<>();
        Response response = null;
        Call call = null;
        try {
            OkHttpClient resolveClient = client.newBuilder()
                    .connectTimeout(3, TimeUnit.SECONDS)
                    .readTimeout(5, TimeUnit.SECONDS)
                    .callTimeout(RESOLVE_TIMEOUT_MS, TimeUnit.MILLISECONDS)
                    .followRedirects(true)
                    .followSslRedirects(true)
                    .build();
            for(String channelUrl : CHANNEL_URLS) {
                Request.Builder builder = new Request.Builder().url(channelUrl).get();
                if(headers != null)
                    for(String key : headers.keySet())
                        builder.addHeader(key, headers.get(key));
                call = resolveClient.newCall(builder.build());
                if(requestGroup != null)
                    requestGroup.add(call);
                response = call.execute();
                if(response.code() >= 200 && response.code() < 400 && response.body() != null) {
                    String html = response.body().string();
                    for(String root : parseLatestRoots(html))
                        addRootCandidate(roots, root);
                    for(String guideUrl : parseAddressGuideUrls(html))
                        if(!guideUrls.contains(guideUrl))
                            guideUrls.add(guideUrl);
                }
                if(response != null) {
                    response.close();
                    response = null;
                }
                if(requestGroup != null && call != null) {
                    requestGroup.remove(call);
                    call = null;
                }
            }
            for(String guideUrl : guideUrls) {
                Request.Builder builder = new Request.Builder().url(guideUrl).get();
                if(headers != null)
                    for(String key : headers.keySet())
                        builder.addHeader(key, headers.get(key));
                call = resolveClient.newCall(builder.build());
                if(requestGroup != null)
                    requestGroup.add(call);
                response = call.execute();
                if(response.code() >= 200 && response.code() < 400 && response.body() != null)
                    for(String root : parseAddressGuideRoots(response.body().string()))
                        addRootCandidate(roots, root);
                if(response != null) {
                    response.close();
                    response = null;
                }
                if(requestGroup != null && call != null) {
                    requestGroup.remove(call);
                    call = null;
                }
            }
            return roots;
        } catch (Exception e) {
            return roots;
        } finally {
            if(requestGroup != null && call != null)
                requestGroup.remove(call);
            if(response != null)
                response.close();
        }
    }

    static String parseLatestRoot(String html) {
        List<String> roots = parseLatestRoots(html);
        return roots.isEmpty() ? null : roots.get(0);
    }

    static List<String> parseLatestRoots(String html) {
        ArrayList<String> roots = new ArrayList<>();
        if(html == null || html.length() == 0)
            return roots;
        Document doc = Jsoup.parse(html);
        for(Element message : doc.select(".tgme_widget_message_text")) {
            String text = message.text();
            if(text == null || !looksLikeCurrentAddressMessage(text))
                continue;
            for(Element link : message.select("a[href]")) {
                String root = normalizeRoot(link.attr("href"));
                if(isCandidate(root, link.text())) {
                    roots.remove(root);
                    roots.add(0, root);
                    break;
                }
            }
        }
        return roots;
    }

    static List<String> parseAddressGuideUrls(String html) {
        ArrayList<String> guideUrls = new ArrayList<>();
        if(html == null || html.length() == 0)
            return guideUrls;
        Document doc = Jsoup.parse(html);
        for(Element link : doc.select("a[href]")) {
            String href = link.attr("abs:href");
            if(href == null || href.length() == 0)
                href = link.attr("href");
            String normalized = normalizeRoot(href);
            if(!isAddressGuideRoot(normalized))
                continue;
            String url = normalized + "/";
            if(!guideUrls.contains(url))
                guideUrls.add(url);
        }
        return guideUrls;
    }

    static List<String> parseAddressGuideRoots(String html) {
        ArrayList<String> roots = new ArrayList<>();
        if(html == null || html.length() == 0)
            return roots;
        Document doc = Jsoup.parse(html);
        for(Element link : doc.select("a[href]")) {
            String root = normalizeRoot(link.attr("href"));
            String text = link.text();
            if(isCandidate(root, text))
                addRootCandidate(roots, root);
        }
        for(String token : doc.text().split("\\s+")) {
            String root = normalizeRoot(token.replace("`", ""));
            if(isCandidate(root, token))
                addRootCandidate(roots, root);
        }
        return roots;
    }

    public static String normalizeRoot(String sourceUrl) {
        try {
            if(sourceUrl == null || sourceUrl.trim().length() == 0)
                return null;
            String normalized = sourceUrl.trim().replace(',', '.');
            if(!normalized.startsWith("http://") && !normalized.startsWith("https://"))
                normalized = "https://" + normalized;
            URI uri = URI.create(normalized);
            String host = uri.getHost();
            if(host == null || host.length() == 0)
                return null;
            String scheme = uri.getScheme() == null ? "https" : uri.getScheme().toLowerCase(Locale.ROOT);
            return scheme + "://" + host.toLowerCase(Locale.ROOT);
        } catch (Exception e) {
            return null;
        }
    }

    private static boolean looksLikeCurrentAddressMessage(String text) {
        if(text == null)
            return false;
        String compact = text.replaceAll("\\s+", "");
        return compact.contains("\uD604\uC7AC\uC8FC\uC18C")
                || compact.contains("\uC811\uC18D\uC8FC\uC18C")
                || compact.contains("\uC2E4\uC2DC\uAC04\uC811\uC18D\uC8FC\uC18C")
                || compact.contains("\uCD5C\uC2E0\uC8FC\uC18C")
                || compact.contains("\uC0C8\uC8FC\uC18C")
                || compact.contains("\uACF5\uC2DD\uC8FC\uC18C");
    }

    private static boolean isCandidate(String root, String label) {
        if(root == null || root.length() == 0)
            return false;
        try {
            String host = URI.create(root).getHost();
            if(host == null)
                return false;
            host = host.toLowerCase(Locale.ROOT);
            if(host.startsWith("www."))
                host = host.substring(4);
            if(host.equals("t.me")
                    || host.endsWith(".telegram.org")
                    || host.endsWith(".telesco.pe")
                    || host.endsWith(".telegram-cdn.org"))
                return false;
            String compactLabel = (label == null ? "" : label.toLowerCase(Locale.ROOT)).replaceAll("\\s+", "");
            if(host.contains("xn--")
                    || root.contains("\uC8FC\uC18C")
                    || compactLabel.contains("\uC8FC\uC18C\uC548\uB0B4")
                    || compactLabel.contains("\uC548\uB0B4\uD398\uC774\uC9C0"))
                return false;
            return host.indexOf('.') > 0;
        } catch (Exception e) {
            return false;
        }
    }

    private static void addRootCandidate(List<String> roots, String root) {
        root = normalizeRoot(root);
        if(root != null && root.length() > 0 && !roots.contains(root))
            roots.add(root);
    }

    private static boolean isAddressGuideRoot(String root) {
        if(root == null || root.length() == 0)
            return false;
        try {
            String host = URI.create(root).getHost();
            if(host == null)
                return false;
            host = host.toLowerCase(Locale.ROOT);
            if(host.startsWith("www."))
                host = host.substring(4);
            return host.contains("xn--") || host.contains("newtoki") || root.contains("\uC8FC\uC18C");
        } catch (Exception e) {
            return false;
        }
    }
}
