package ml.melun.mangaview.adapter;

final class HomeContinueWarmupPolicy {
    private HomeContinueWarmupPolicy() {
    }

    static int visibleContinueWarmupLimit(boolean dataSave) {
        return dataSave ? 1 : 2;
    }

    static int visibleContinueWarmupLimitForTest(boolean dataSave) {
        return visibleContinueWarmupLimit(dataSave);
    }
}
