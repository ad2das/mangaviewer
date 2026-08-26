package ml.melun.mangaview.reader

import java.util.IdentityHashMap

/**
 * Allocation-light identity reference counts owned by an external lock.
 *
 * Equality is deliberately JVM identity rather than [Any.equals]. Bitmap ownership is tied to
 * the exact Java object handed to JNI; two pixel-equal Bitmap instances are different resources.
 * This class is not synchronized because ReaderSurfaceView mutates and queries it only while
 * holding its state lock.
 */
internal class IdentityReferenceLedger<T : Any> {
    private val counts = IdentityHashMap<T, Int>()

    fun retain(value: T?) {
        if (value == null) return
        counts[value] = (counts[value] ?: 0) + 1
    }

    fun release(value: T?) {
        if (value == null) return
        val count = counts[value] ?: return
        if (count <= 1) counts.remove(value) else counts[value] = count - 1
    }

    fun retainAll(values: Iterable<T>) {
        values.forEach(::retain)
    }

    fun releaseAll(values: Iterable<T>) {
        values.forEach(::release)
    }

    fun replace(previous: T?, next: T?) {
        if (previous === next) return
        release(previous)
        retain(next)
    }

    fun replaceAll(previous: Iterable<T>, next: Iterable<T>) {
        if (previous === next) return
        releaseAll(previous)
        retainAll(next)
    }

    fun references(value: T): Boolean = counts.containsKey(value)

    internal fun referenceCountForTest(value: T): Int = counts[value] ?: 0
}
