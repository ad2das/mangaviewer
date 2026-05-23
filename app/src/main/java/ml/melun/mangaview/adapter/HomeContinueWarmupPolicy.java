package ml.melun.mangaview.adapter;

final class HomeContinueWarmupPolicy {
    private HomeContinueWarmupPolicy() {
    }

    static int visibleContinueWarmupLimit(boolean dataSave) {
        return dataSave ? 1 : 2;
    }

    static long visibleHomeWarmupDelayMs(boolean dataSave) {
        return dataSave ? 1200L : 650L;
    }

    static int visibleContinueWarmupLimitForTest(boolean dataSave) {
        return visibleContinueWarmupLimit(dataSave);
    }

    static long visibleHomeWarmupDelayMsForTest(boolean dataSave) {
        return visibleHomeWarmupDelayMs(dataSave);
    }
}
