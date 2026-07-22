package ml.melun.mangaview.fragment;

final class HomeStartupPolicy {
    private HomeStartupPolicy() {
    }

    static long inactiveInitialRowsDelayMs(boolean ntk) {
        return ntk ? 1800L : 1200L;
    }

    static long activeFetchDelayMs(boolean ntk) {
        return ntk ? 320L : 220L;
    }

    static long autoCaptchaDelayMs(boolean ntk) {
        // Keep startup navigation usable before an optional full-screen network
        // challenge. The Home fetch can continue; Library/Recent never requires it.
        return ntk ? 5_000L : 0L;
    }

    static long inactiveInitialRowsDelayMsForTest(boolean ntk) {
        return inactiveInitialRowsDelayMs(ntk);
    }

    static long activeFetchDelayMsForTest(boolean ntk) {
        return activeFetchDelayMs(ntk);
    }

    static long autoCaptchaDelayMsForTest(boolean ntk) {
        return autoCaptchaDelayMs(ntk);
    }
}
