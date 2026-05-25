package ml.melun.mangaview.contracts;

import java.util.Map;

import ml.melun.mangaview.mangaview.CustomHttpClient;
import okhttp3.Response;

public interface HttpDocumentClient {
    Response get(String url, Map<String, String> headers);

    CustomHttpClient.PageResponse getCachedPage(String url, long ttlMillis) throws Exception;

    String resolveUrl(String path);

    boolean resolveWfwfDomainNow();

    boolean resolveNtkDomainNow();

    void clearPageCache();

    void resetCookie();
}
