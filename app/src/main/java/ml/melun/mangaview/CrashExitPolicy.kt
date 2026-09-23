package ml.melun.mangaview

/**
 * Which process-exit records count as abnormal deaths worth offering to the user. Kept free of
 * Android types so the rule stays unit-testable; the constants mirror ApplicationExitInfo and
 * ActivityManager.RunningAppProcessInfo.
 */
internal object CrashExitPolicy {
    /** ApplicationExitInfo.REASON_LOW_MEMORY. */
    const val REASON_LOW_MEMORY = 3

    /** ApplicationExitInfo.REASON_OTHER. */
    const val REASON_OTHER = 13

    /** IMPORTANCE_CACHED (400) and the gone state above it: nothing of the app was on screen. */
    const val IMPORTANCE_CACHED = 400

    /**
     * A cached process the system reclaimed is normal background cleanup: nothing was visible and
     * nothing crashed, so a low-memory or unattributed kill there is not an abnormal death. A kill
     * of a process the user could still see, and every crash, ANR or signal, stays reportable.
     */
    fun abnormal(reason: Int, importance: Int): Boolean = when (reason) {
        REASON_LOW_MEMORY, REASON_OTHER -> importance < IMPORTANCE_CACHED
        else -> true
    }
}
