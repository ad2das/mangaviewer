package ml.melun.mangaview.adapter;

final class HomeContinueWarmupPolicy {
    private HomeContinueWarmupPolicy() {
    }

    static int visibleContinueWarmupLimit(boolean dataSave,
                                          boolean ntkSite,
                                          boolean directWifiTransport) {
        // On the direct-Wi-Fi NTK path a continue card can resume near the tail, but this home
        // warmup starts at p001 and may attach a WebView fallback before the user clicks. Besides
        // fetching pages behind the saved position, that WebView can stall the home HWUI frame
        // and delay delivery of the click itself. ReaderV2 owns the saved-position-first strict
        // fetch after the committed click. Keep the cellular/SNI policy unchanged.
        if(ntkSite)
            return directWifiTransport ? 0 : 1;
        return dataSave ? 1 : 3;
    }

    static long visibleHomeWarmupDelayMs(boolean dataSave) {
        return 0L;
    }

    static int visibleContinueWarmupLimitForTest(boolean dataSave,
                                                 boolean ntkSite,
                                                 boolean directWifiTransport) {
        return visibleContinueWarmupLimit(dataSave, ntkSite, directWifiTransport);
    }

    static long visibleHomeWarmupDelayMsForTest(boolean dataSave) {
        return visibleHomeWarmupDelayMs(dataSave);
    }
}
