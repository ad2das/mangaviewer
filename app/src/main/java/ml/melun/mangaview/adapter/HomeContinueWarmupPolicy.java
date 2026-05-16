package ml.melun.mangaview.adapter;

final class HomeContinueWarmupPolicy {
    private HomeContinueWarmupPolicy() {
    }

    static int visibleContinueWarmupLimit(boolean dataSave) {
        return 1;
    }

    static long visibleHomeWarmupDelayMs(boolean dataSave) {
        return dataSave ? 1500L : 1100L;
    }

    static int visibleContinueWarmupLimitForTest(boolean dataSave) {
        return visibleContinueWarmupLimit(dataSave);
    }

    static long visibleHomeWarmupDelayMsForTest(boolean dataSave) {
        return visibleHomeWarmupDelayMs(dataSave);
    }
}
