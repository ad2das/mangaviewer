package ml.melun.mangaview.viewer.runtime

internal object ReleaseFenceWatcherProbe {
    init { System.loadLibrary("viewer_native") }
    external fun hasLooper(): Boolean
    external fun currentTid(): Int
    external fun arm(fd: Int): Boolean
    external fun disarm(): Boolean
    external fun armed(): Boolean
    external fun wakeCount(): Int
    external fun wakeInputCount(): Int
    external fun wakeFaultCount(): Int
    external fun wakeTid(): Int
}
