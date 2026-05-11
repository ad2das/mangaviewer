package ml.melun.mangaview.mangaview;

import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;

import java.net.URI;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import okhttp3.Call;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

public class NtkDomainResolver {
    public static final String CHANNEL_URL = "https://t.me/s/newtoki_url";
    private static final long RESOLVE_TIMEOUT_MS = 8_000L;

    public static String resolve(OkHttpClient client, Map<String, String> headers) {
        return resolve(client, headers, null);
    }

    public static String resolve(OkHttpClient client, Map<String, String> headers, CustomHttpClient.RequestGroup requestGroup) {
        Response response = null;
        Call call = null;
        try {
            OkHttpClient resolveClient = client.newBuilder()
                    .connectTimeout(3, TimeUnit.SECONDS)
                    .readTimeout(5, TimeUnit.SECONDS)
                    .callTimeout(RESOLVE_TIMEOUT_MS, TimeUnit.MILLISECONDS)
                    .build();
            Request.Builder builder = new Request.Builder().url(CHANNEL_URL).get();
            if(headers != null)
                for(String key : headers.keySet())
                    builder.addHeader(key, headers.get(key));
            call = resolveClient.newCall(builder.build());
            if(requestGroup != null)
                requestGroup.add(call);
            response = call.execute();
            if(response.code() < 200 || response.code() >= 400 || response.body() == null)
                return null;
            return parseLatestRoot(response.body().string());
        } catch (Exception e) {
            return null;
        } finally {
            if(requestGroup != null && call != null)
                requestGroup.remove(call);
            if(response != null)
                response.close();
        }
    }

    static String parseLatestRoot(String html) {
        if(html == null || html.length() == 0)
            return null;
        Document doc = Jsoup.parse(html);
        String latest = null;
        for(Element message : doc.select(".tgme_widget_message_text")) {
            String text = message.text();
            if(text == null || !looksLikeCurrentAddressMessage(text))
                continue;
            for(Element link : message.select("a[href]")) {
                String root = normalizeRoot(link.attr("href"));
                if(isCandidate(root, link.text())) {
                    latest = root;
                    break;
                }
            }
        }
        return latest;
    }

    public static String normalizeRoot(String sourceUrl) {
        try {
            if(sourceUrl == null || sourceUrl.trim().length() == 0)
                return null;
            String normalized = sourceUrl.trim();
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
        return text.contains("현재주소")
                || text.contains("현재 주소")
                || text.contains("접속 주소")
                || text.contains("실시간 주소")
                || text.contains("최신주소")
                || text.contains("최신 주소");
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
            String lowerLabel = label == null ? "" : label.toLowerCase(Locale.ROOT);
            if(host.contains("xn--") || root.contains("주소") || lowerLabel.contains("주소 안내") || lowerLabel.contains("안내페이지"))
                return false;
            return host.indexOf('.') > 0;
        } catch (Exception e) {
            return false;
        }
    }
}
