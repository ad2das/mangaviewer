package ml.melun.mangaview.adapter;

final class HomeSectionFetchFailurePolicy {
    private HomeSectionFetchFailurePolicy() {
    }

    static boolean shouldAbort(Throwable failure, boolean cancelled) {
        return cancelled || failure instanceof InterruptedException;
    }

    static boolean shouldAbortForTest(Throwable failure, boolean cancelled) {
        return shouldAbort(failure, cancelled);
    }
}
