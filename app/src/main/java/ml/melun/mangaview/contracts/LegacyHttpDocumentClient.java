package ml.melun.mangaview.contracts;

import java.util.Map;

import ml.melun.mangaview.mangaview.CustomHttpClient;
import okhttp3.Response;

public final class LegacyHttpDocumentClient implements HttpDocumentClient {
    private final CustomHttpClient client;

    public LegacyHttpDocumentClient(CustomHttpClient client) {
        this.client = client;
    }

    @Override
    public Response get(String url, Map<String, String> headers) {
        return client.get(url, headers);
    }

    @Override
    public CustomHttpClient.PageResponse getCachedPage(String url, long ttlMillis) throws Exception {
        return client.mgetCachedPage(url, ttlMillis);
    }

    @Override
    public String resolveUrl(String path) {
        return client.getUrl(path);
    }

    @Override
    public boolean resolveWfwfDomainNow() {
        return client.resolveWfwfDomainNow();
    }

    @Override
    public boolean resolveNtkDomainNow() {
        return client.resolveNtkDomainNow();
    }

    @Override
    public void clearPageCache() {
        client.clearPageCache();
    }

    @Override
    public void resetCookie() {
        client.resetCookie();
    }
}
