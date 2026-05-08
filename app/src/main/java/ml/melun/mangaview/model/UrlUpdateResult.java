package ml.melun.mangaview.model;

public final class UrlUpdateResult {
    private final boolean success;
    private final String url;

    public UrlUpdateResult(boolean success, String url) {
        this.success = success;
        this.url = url;
    }

    public boolean getSuccess() {
        return success;
    }

    public String getUrl() {
        return url;
    }
}
