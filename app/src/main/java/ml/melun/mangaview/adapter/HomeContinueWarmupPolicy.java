package ml.melun.mangaview.adapter;

final class HomeContinueWarmupPolicy {
    private HomeContinueWarmupPolicy() {
    }

    static int visibleContinueWarmupLimit(boolean dataSave) {
        return 1;
    }

    static long visibleHomeWarmupDelayMs(boolean dataSave) {
        return dataSave ? 900L : 360L;
    }

    static int visibleContinueWarmupLimitForTest(boolean dataSave) {
        return visibleContinueWarmupLimit(dataSave);
    }

    static long visibleHomeWarmupDelayMsForTest(boolean dataSave) {
        return visibleHomeWarmupDelayMs(dataSave);
    }
}
