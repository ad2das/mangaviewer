package ml.melun.mangaview.reader

import java.util.Locale

/**
 * Keeps WFWF navigation-only rows out of forward episode chronology while still allowing a
 * reader opened through the "first episode" shortcut to continue through server episode ids.
 */
internal object WfwfAdjacentEpisodePolicy {
    fun isDirectionallyConsistent(sourceEpisodeId: Int, candidateEpisodeId: Int, direction: Int): Boolean {
        if (sourceEpisodeId <= 0 || candidateEpisodeId <= 0 || direction == 0) return false
        return if (direction > 0) {
            candidateEpisodeId > sourceEpisodeId
        } else {
            candidateEpisodeId < sourceEpisodeId
        }
    }

    fun isImmediateNumericCandidate(sourceEpisodeId: Int, candidateEpisodeId: Int, direction: Int): Boolean {
        if (sourceEpisodeId <= 0 || candidateEpisodeId <= 0 || direction == 0) return false
        val step = if (direction > 0) 1L else -1L
        return candidateEpisodeId.toLong() == sourceEpisodeId.toLong() + step
    }

    fun isImmediateVisibleCandidate(sourceKey: String?, candidateKey: String?, direction: Int): Boolean {
        if (direction == 0) return false
        val source = sourceKey?.trim()?.toDoubleOrNull() ?: return false
        val candidate = candidateKey?.trim()?.toDoubleOrNull() ?: return false
        val expected = source + if (direction > 0) 1.0 else -1.0
        return kotlin.math.abs(candidate - expected) <= 0.0001
    }

    fun isFirstEpisodeShortcut(episodeId: Int, episodeName: String?): Boolean {
        if (episodeId != 1) return false
        val normalized = episodeName
            .orEmpty()
            .lowercase(Locale.ROOT)
            .replace(Regex("\\s+"), "")
        return normalized == "첫화보기" ||
            normalized == "첫화부터" ||
            normalized == "첫화부터정주행"
    }

    fun syntheticCandidateIds(
        sourceEpisodeId: Int,
        direction: Int,
        limit: Int,
    ): List<Int> {
        if (sourceEpisodeId <= 0 || direction == 0 || limit <= 0) return emptyList()
        // The visible list may contain navigation-only rows or end at a pagination boundary even
        // though the opened episode is present. Numeric ids are the provider's viewer keys, so a
        // bounded sequence remains the last recovery authority; the normal viewer request still
        // has to prove that each candidate exists.
        val step = if (direction > 0) 1 else -1
        return buildList(limit) {
            for (distance in 1..limit) {
                val candidate = sourceEpisodeId.toLong() + step.toLong() * distance
                if (candidate <= 0L || candidate > Int.MAX_VALUE) continue
                add(candidate.toInt())
            }
        }
    }
}
