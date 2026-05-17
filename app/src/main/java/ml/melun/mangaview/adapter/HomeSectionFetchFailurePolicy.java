package ml.melun.mangaview.adapter;

final class HomeSectionFetchFailurePolicy {
    private HomeSectionFetchFailurePolicy() {
    }

    static boolean shouldAbort(Throwable failure, boolean cancelled) {
        return cancelled || failure instanceof InterruptedException;
    }

    static boolean shouldReport(Throwable failure) {
        if(failure == null)
            return false;
        String message = failure.getMessage();
        if(message != null && message.startsWith("Request failed:"))
            return false;
        if("Cloudflare challenge".equals(message))
            return false;
        return failure instanceof RuntimeException;
    }

    static boolean shouldAbortForTest(Throwable failure, boolean cancelled) {
        return shouldAbort(failure, cancelled);
    }

    static boolean shouldReportForTest(Throwable failure) {
        return shouldReport(failure);
    }
}
