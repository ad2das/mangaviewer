package ml.melun.mangaview.glide;

public final class ViewerPreloadPolicy {
    public static final int TIER_DECODED = 0;
    public static final int TIER_IMMEDIATE = 1;
    public static final int TIER_HIGH = 2;
    public static final int TIER_NORMAL = 3;

    private ViewerPreloadPolicy() {
    }

    public static Window firstFrameWindow(boolean dataSave) {
        return dataSave
                ? new Window(1, 2, 6, 6)
                : new Window(3, 6, 12, 12);
    }

    public static Window episodeListWarmupWindow(boolean dataSave) {
        return dataSave
                ? new Window(0, 1, 2, 2)
                : new Window(1, 2, 4, 4);
    }

    public static Window episodeEntryWarmupWindow(boolean dataSave) {
        return dataSave
                ? new Window(1, 1, 2, 2)
                : new Window(2, 5, 8, 8);
    }

    public static Window immediateDisplayWindow(boolean dataSave) {
        return dataSave
                ? new Window(1, 1, 2, 2)
                : new Window(2, 5, 8, 8);
    }

    public static Window initialScrollWindow(boolean dataSave) {
        return firstFrameWindow(dataSave);
    }

    public static Window scrollAheadWindow(boolean dataSave) {
        return dataSave
                ? new Window(1, 2, 6, 6)
                : new Window(2, 5, 12, 12);
    }

    public static Window scrollBusyWindow(boolean dataSave) {
        return dataSave
                ? new Window(0, 1, 3, 3)
                : new Window(1, 2, 4, 4);
    }

    public static Window nextEpisodeWindow(boolean dataSave) {
        return dataSave
                ? new Window(1, 2, 6, 6)
                : new Window(2, 4, 8, 8);
    }

    public static int tierForOffset(Window window, int offset) {
        if(window == null)
            return TIER_NORMAL;
        if(offset < window.decodedLimit)
            return TIER_DECODED;
        if(offset < window.immediateLimit)
            return TIER_IMMEDIATE;
        if(offset < window.highLimit)
            return TIER_HIGH;
        return TIER_NORMAL;
    }

    public static final class Window {
        public final int decodedLimit;
        public final int immediateLimit;
        public final int highLimit;
        public final int totalLimit;

        public Window(int decodedLimit, int immediateLimit, int highLimit, int totalLimit) {
            this.totalLimit = Math.max(1, totalLimit);
            this.decodedLimit = clamp(decodedLimit, 0, this.totalLimit);
            this.immediateLimit = clamp(Math.max(immediateLimit, this.decodedLimit), this.decodedLimit, this.totalLimit);
            this.highLimit = clamp(Math.max(highLimit, this.immediateLimit), this.immediateLimit, this.totalLimit);
        }

        private static int clamp(int value, int min, int max) {
            return Math.max(min, Math.min(value, max));
        }
    }
}
