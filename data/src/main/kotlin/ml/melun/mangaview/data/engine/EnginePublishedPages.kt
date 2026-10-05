package ml.melun.mangaview.data.engine

import java.util.concurrent.ConcurrentHashMap
import ml.melun.mangaview.data.db.EnginePageEntity

/**
 * Process-local mirror of every committed `engine_pages` row plus the last-access hints that have
 * not reached Room yet.
 *
 * [get] and [recordTouch] are the lookup fast path and run without the storage mutex; every
 * structural mutation ([put], [remove], [replaceAll], [drainTouches], [confirmTouches]) is only
 * legal while the caller holds the storage mutex, which already serializes publication, eviction
 * and invalidation. ConcurrentHashMap is the right shape here: readers never contend on the
 * writer's mutex or on each other and batches mutate in place; a volatile immutable map would
 * rebuild the whole table on every commit and eviction for no additional read guarantee.
 */
internal class EnginePublishedPages {
    private val rows = ConcurrentHashMap<Pair<String, String>, EnginePageEntity>()
    private val touches = ConcurrentHashMap<Pair<String, String>, Long>()

    fun get(cacheKey: String, revision: String): EnginePageEntity? = rows[cacheKey to revision]

    /**
     * The newest access time this process knows for [entity]: its mirrored row or a hint that has
     * not been flushed to Room yet.
     */
    fun lastAccess(entity: EnginePageEntity): Long {
        val pending = touches[entity.cacheKey to entity.contentRevision]
        return if (pending != null) maxOf(pending, entity.lastAccessEpochMillis) else entity.lastAccessEpochMillis
    }

    /**
     * Remembers that [entity] was just used, at most once per [TOUCH_INTERVAL_MILLIS]. Runs without
     * the mutex: a lost race can only add one redundant hint for the same window, which the flush
     * collapses to the newest value.
     */
    fun recordTouch(entity: EnginePageEntity, nowMillis: Long) {
        val key = entity.cacheKey to entity.contentRevision
        if (nowMillis - lastAccess(entity) >= TOUCH_INTERVAL_MILLIS) touches[key] = nowMillis
    }

    /** A committed row becomes visible to lock-free readers here; the caller holds the mutex. */
    fun put(entity: EnginePageEntity) {
        rows[entity.cacheKey to entity.contentRevision] = entity
    }

    /** Drops an evicted or invalidated row together with any hint that targeted it. */
    fun remove(entity: EnginePageEntity) {
        val key = entity.cacheKey to entity.contentRevision
        rows.remove(key)
        touches.remove(key)
    }

    /** Rebuilds the mirror from Room after recovery; hints for gone rows are dropped by [drainTouches]. */
    fun replaceAll(entities: List<EnginePageEntity>) {
        rows.clear()
        for (entity in entities) rows[entity.cacheKey to entity.contentRevision] = entity
    }

    /** JVM-test observability: the keys the mirror currently serves. */
    fun keys(): Set<Pair<String, String>> = rows.keys.toSet()

    /**
     * Under the mutex: the newest last-access value per hinted row, as full rows ready for one
     * batched write. Touch values are pinned into the pending map first so [confirmTouches] removes
     * exactly what was flushed while a concurrent touch for the same row survives. Hints whose row
     * was evicted before the flush are moot and dropped.
     */
    fun drainTouches(): List<EnginePageEntity> {
        val flushed = ArrayList<EnginePageEntity>(touches.size)
        for (key in touches.keys.toList()) {
            val pending = touches[key] ?: continue
            val row = rows[key]
            if (row == null) {
                touches.remove(key, pending)
                continue
            }
            val value = maxOf(pending, row.lastAccessEpochMillis)
            touches[key] = value
            flushed += row.copy(lastAccessEpochMillis = value)
        }
        return flushed
    }

    /**
     * Under the mutex, after the flushed rows reached Room: publish their values in memory too, so
     * the 30 s gate and the LRU order see them without another write. A touch recorded while the
     * flush ran replaced its pending value and stays pending for the next flush.
     */
    fun confirmTouches(flushed: List<EnginePageEntity>) {
        for (row in flushed) {
            val key = row.cacheKey to row.contentRevision
            touches.remove(key, row.lastAccessEpochMillis)
            rows.computeIfPresent(key) { _, current ->
                if (row.lastAccessEpochMillis > current.lastAccessEpochMillis) {
                    current.copy(lastAccessEpochMillis = row.lastAccessEpochMillis)
                } else {
                    current
                }
            }
        }
    }

    private companion object {
        /** LRU write granularity; hot pages keep a timestamp younger than this without a row write. */
        const val TOUCH_INTERVAL_MILLIS = 30_000L
    }
}
