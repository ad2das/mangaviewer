package ml.melun.mangaview.reader

/** Keeps offscreen exact-body publication independent from the visible page-table owner. */
internal object NtkAdjacentDescriptorWakePolicy {
    fun shouldWakeRemainder(firstActualFramePresented: Boolean): Boolean =
        firstActualFramePresented
}
