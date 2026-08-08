package ml.melun.mangaview.benchmark;

import android.content.Context;

/**
 * Non-benchmark variant stub. Release R8 inlines and removes both calls and this empty class.
 */
public final class BenchmarkAdjacentCommitSignal {
    private BenchmarkAdjacentCommitSignal() {
    }

    public static void initialize(Context context) {
    }

    public static boolean isEnabled() {
        return false;
    }

    public static void publish(
            String episodePath,
            int sourceIndex,
            long presentedAtNanos,
            long viewerGeneration) {
    }

    public static void publishSemanticCommit(
            String episodePath,
            int sourceIndex,
            long presentedAtNanos,
            long viewerGeneration) {
    }

    public static void publishRunwayReady(
            String episodePath,
            int pageCount,
            int totalPageCount,
            long readyAtNanos,
            long viewerGeneration) {
    }

    public static void publishPhysicalMotionIdle(
            long motionEndedAtNanos,
            long viewerGeneration) {
    }
}
