package ml.melun.mangaview.fragment;

final class HomeInactivePrefetchPolicy {
    private HomeInactivePrefetchPolicy() {
    }

    static boolean shouldSchedule(boolean ntk, int selectedFetchState, boolean waiting) {
        if(!ntk || waiting)
            return false;
        return selectedFetchState == MainMain.HOME_FETCH_PARTIAL
                || selectedFetchState == MainMain.HOME_FETCH_COMPLETE;
    }

    static long delayMs(int selectedFetchState) {
        return selectedFetchState == MainMain.HOME_FETCH_COMPLETE ? 900L : 1600L;
    }

    static boolean shouldScheduleForTest(boolean ntk, int selectedFetchState, boolean waiting) {
        return shouldSchedule(ntk, selectedFetchState, waiting);
    }

    static long delayMsForTest(int selectedFetchState) {
        return delayMs(selectedFetchState);
    }
}
