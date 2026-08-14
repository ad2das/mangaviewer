package ml.melun.mangaview.model;

public final class UrlUpdateResult {
    private final boolean success;
    private final String url;
    private final String requestUrl;

    public UrlUpdateResult(boolean success, String url) {
        this(success, url, null);
    }

    public UrlUpdateResult(boolean success, String url, String requestUrl) {
        this.success = success;
        this.url = url;
        this.requestUrl = requestUrl;
    }

    public boolean getSuccess() {
        return success;
    }

    public String getUrl() {
        return url;
    }

    public String getRequestUrl() {
        return requestUrl;
    }

    public boolean isForRequest(String expectedRequestUrl) {
        return expectedRequestUrl != null && expectedRequestUrl.equals(requestUrl);
    }
}
