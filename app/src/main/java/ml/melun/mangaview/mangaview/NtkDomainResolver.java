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
            "https://sbxh9.com",
            "https://newtoki1.org",
            "https://sbxh8.com",
            "https://sbxh7.com",
            CHANNEL_URL
    };
    private static final long RESOLVE_TIMEOUT_MS = 8_000L;
    private static final int SBXH_FUTURE_PROBE_WINDOW = 3;
    private static final int SBXH_PAST_PROBE_WINDOW = 8;

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
                if(isCandidate(root, link.text()))
                    addPriorityRootCandidate(roots, root);
            }
            for(String token : text.split("\\s+")) {
                String root = normalizeRoot(cleanAddressToken(token));
                if(isCandidate(root, token))
                    addPriorityRootCandidate(roots, root);
            }
        }
        return roots;
    }

    public static List<String> generatedSbxhRoots(String... seedRoots) {
        ArrayList<String> roots = new ArrayList<>();
        int max = 0;
        if(seedRoots != null) {
            for(String seed : seedRoots)
                max = Math.max(max, sbxhNumber(seed));
        }
        if(max <= 0)
            max = 9;
        int upper = max + SBXH_FUTURE_PROBE_WINDOW;
        int lower = Math.max(1, max - SBXH_PAST_PROBE_WINDOW);
        for(int number = upper; number >= lower; number--)
            addRootCandidate(roots, "https://sbxh" + number + ".com");
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
        String lower = compact.toLowerCase(Locale.ROOT);
        return compact.contains("\uD604\uC7AC\uC8FC\uC18C")
                || compact.contains("\uC811\uC18D\uC8FC\uC18C")
                || compact.contains("\uC2E4\uC2DC\uAC04\uC811\uC18D\uC8FC\uC18C")
                || compact.contains("\uCD5C\uC2E0\uC8FC\uC18C")
                || compact.contains("\uC0C8\uC8FC\uC18C")
                || compact.contains("\uACF5\uC2DD\uC8FC\uC18C")
                || lower.contains("newtoki")
                || lower.contains("sbxh");
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

    private static void addPriorityRootCandidate(List<String> roots, String root) {
        root = normalizeRoot(root);
        if(root == null || root.length() == 0)
            return;
        roots.remove(root);
        roots.add(0, root);
    }

    private static String cleanAddressToken(String token) {
        if(token == null)
            return "";
        String cleaned = token.replace("`", "").trim();
        while(cleaned.length() > 0) {
            char first = cleaned.charAt(0);
            if(first == '(' || first == '[' || first == '<' || first == '"' || first == '\'')
                cleaned = cleaned.substring(1);
            else
                break;
        }
        while(cleaned.length() > 0) {
            char last = cleaned.charAt(cleaned.length() - 1);
            if(last == ')' || last == ']' || last == '>' || last == '"' || last == '\''
                    || last == '.' || last == ',' || last == ';' || last == ':')
                cleaned = cleaned.substring(0, cleaned.length() - 1);
            else
                break;
        }
        return cleaned;
    }

    private static int sbxhNumber(String root) {
        try {
            root = normalizeRoot(root);
            if(root == null || root.length() == 0)
                return 0;
            String host = URI.create(root).getHost();
            if(host == null)
                return 0;
            host = host.toLowerCase(Locale.ROOT);
            if(host.startsWith("www."))
                host = host.substring(4);
            if(!host.startsWith("sbxh") || !host.endsWith(".com"))
                return 0;
            String number = host.substring(4, host.length() - 4);
            if(number.length() == 0)
                return 0;
            for(int i = 0; i < number.length(); i++)
                if(!Character.isDigit(number.charAt(i)))
                    return 0;
            return Integer.parseInt(number);
        } catch (Exception e) {
            return 0;
        }
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
