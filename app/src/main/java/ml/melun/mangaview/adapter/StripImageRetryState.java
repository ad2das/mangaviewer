package ml.melun.mangaview.adapter;

import java.util.Map;
import java.util.Set;
import java.util.HashMap;

final class StripImageRetryState {
    private final int limit;
    private final Map<String, Integer> attemptsByPage = new HashMap<>();

    StripImageRetryState(int limit) {
        this.limit = limit;
    }

    void clearSuccess(String pageKey) {
        attemptsByPage.remove(pageKey);
    }

    int nextAttempt(String pageKey, boolean released) {
        int attempts = attemptsByPage.containsKey(pageKey) ? attemptsByPage.get(pageKey) : 0;
        if(!shouldRetry(released, pageKey, attempts, limit))
            return -1;
        int nextAttempt = attempts + 1;
        attemptsByPage.put(pageKey, nextAttempt);
        return nextAttempt;
    }

    void retain(Set<String> activePageKeys) {
        attemptsByPage.keySet().retainAll(activePageKeys);
    }

    void clear() {
        attemptsByPage.clear();
    }

    static boolean shouldRetry(boolean released, String pageKey, int attempts, int limit) {
        return !released
                && pageKey != null
                && pageKey.length() > 0
                && attempts < limit;
    }

    static long retryDelayMs(int nextAttempt) {
        if(nextAttempt <= 1)
            return 220L;
        if(nextAttempt == 2)
            return 650L;
        return 1200L;
    }
}
