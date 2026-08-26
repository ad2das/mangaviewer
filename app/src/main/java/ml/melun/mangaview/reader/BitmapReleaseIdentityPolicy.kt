package ml.melun.mangaview.reader

import java.util.IdentityHashMap

/** Identity-only filtering for delayed Bitmap retirement. */
internal object BitmapReleaseIdentityPolicy {
    fun <T : Any> uniqueCandidatesExcludingRetained(
        candidates: Iterable<T>,
        retained: Iterable<T>,
    ): List<T> {
        val retainedIdentities = java.util.Collections.newSetFromMap(
            IdentityHashMap<T, Boolean>(),
        )
        for (identity in retained) retainedIdentities.add(identity)
        val emitted = java.util.Collections.newSetFromMap(
            IdentityHashMap<T, Boolean>(),
        )
        return candidates.filter { candidate ->
            candidate !in retainedIdentities && emitted.add(candidate)
        }
    }
}
