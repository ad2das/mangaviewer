package ml.melun.mangaview.adapter;

final class HomeContinueWarmupPolicy {
    private HomeContinueWarmupPolicy() {
    }

    static int visibleContinueWarmupLimit(boolean dataSave, boolean ntkSite) {
        // One NTK episode already fills the native reader's useful preparation window.
        // Preparing adjacent continue cards launches full manifest/image work (and, on an
        // explicit server challenge, a WebView fallback) that competes with the episode the
        // user is actually opening. Keep the selected/top continue immediately ready and do
        // not spend the same cold-start budget on off-screen NTK episodes.
        if(ntkSite)
            return 1;
        return dataSave ? 1 : 3;
    }

    static long visibleHomeWarmupDelayMs(boolean dataSave) {
        return 0L;
    }

    static int visibleContinueWarmupLimitForTest(boolean dataSave, boolean ntkSite) {
        return visibleContinueWarmupLimit(dataSave, ntkSite);
    }

    static long visibleHomeWarmupDelayMsForTest(boolean dataSave) {
        return visibleHomeWarmupDelayMs(dataSave);
    }
}
