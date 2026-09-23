package ml.melun.mangaview.engine.work

import ml.melun.mangaview.engine.api.WorkDomain
import ml.melun.mangaview.engine.api.WorkLimits
import ml.melun.mangaview.engine.api.WorkPriority

internal data class PermitClaim(
    val domain: WorkDomain,
    val background: Boolean,
)

internal class WorkAdmission(private val limits: WorkLimits) {
    private var networkUsed = 0
    private var bodiesUsed = 0
    private var backgroundNetworkUsed = 0
    private var decodeUsed = 0
    private var foregroundDecodeUsed = 0
    private var backgroundDecodeUsed = 0
    private var storageUsed = 0
    private var uploadUsed = 0
    private var browserUsed = 0

    fun tryAcquire(domain: WorkDomain, priority: WorkPriority): PermitClaim? {
        val background = priority.background
        return when (domain) {
            WorkDomain.CONTROL -> PermitClaim(domain, background = false)
            WorkDomain.NETWORK -> acquireNetwork(domain, background)
            WorkDomain.BODY -> acquireBody(domain, background)
            WorkDomain.DECODE -> acquireDecode(domain, background)
            WorkDomain.STORAGE -> acquireSingle(domain, storageUsed, limits.storage) {
                storageUsed += 1
            }
            WorkDomain.UPLOAD -> acquireUpload(domain, background)
            WorkDomain.BROWSER -> acquireSingle(domain, browserUsed, 1) { browserUsed += 1 }
        }
    }

    fun release(claim: PermitClaim) {
        when (claim.domain) {
            WorkDomain.CONTROL -> Unit
            WorkDomain.NETWORK -> {
                networkUsed -= 1
                if (claim.background) backgroundNetworkUsed -= 1
            }
            WorkDomain.BODY -> {
                networkUsed -= 1
                bodiesUsed -= 1
                if (claim.background) backgroundNetworkUsed -= 1
            }
            WorkDomain.DECODE -> if (claim.background) {
                backgroundDecodeUsed -= 1
            } else {
                decodeUsed -= 1
                foregroundDecodeUsed -= 1
            }
            WorkDomain.STORAGE -> storageUsed -= 1
            WorkDomain.UPLOAD -> uploadUsed -= 1
            WorkDomain.BROWSER -> browserUsed -= 1
        }
        check(networkUsed >= 0 && bodiesUsed >= 0 && backgroundNetworkUsed >= 0)
        check(decodeUsed >= 0 && backgroundDecodeUsed >= 0 && storageUsed >= 0 && uploadUsed >= 0 && browserUsed >= 0)
    }

    private fun acquireNetwork(domain: WorkDomain, background: Boolean): PermitClaim? {
        if (networkUsed >= limits.network) return null
        if (background && backgroundNetworkUsed >= limits.backgroundNetwork) return null
        networkUsed += 1
        if (background) backgroundNetworkUsed += 1
        return PermitClaim(domain, background)
    }

    private fun acquireBody(domain: WorkDomain, background: Boolean): PermitClaim? {
        if (networkUsed >= limits.network || bodiesUsed >= limits.bodies) return null
        if (background && backgroundNetworkUsed >= limits.backgroundNetwork) return null
        networkUsed += 1
        bodiesUsed += 1
        if (background) backgroundNetworkUsed += 1
        return PermitClaim(domain, background)
    }

    private fun acquireDecode(domain: WorkDomain, background: Boolean): PermitClaim? {
        // A speculative (read-ahead) decode runs on the worker thread that owns its tile record now,
        // not on a dedicated lane, so its concurrency is bounded here instead: a burst of horizon
        // tiles may park [backgroundDecodes] plumbing threads in a raster conversion at most, and
        // the rest stay free for the completion handoffs that carry every record. A visible decode
        // stays capped by [decodes]; the boundary that matters — speculation never reaching
        // residency ahead of visible work — is still enforced where residency is created, the
        // upload below.
        if (background) {
            if (backgroundDecodeUsed >= limits.backgroundDecodes) return null
            backgroundDecodeUsed += 1
            return PermitClaim(domain, background = true)
        }
        if (foregroundDecodeUsed >= limits.decodes) return null
        foregroundDecodeUsed += 1
        decodeUsed += 1
        return PermitClaim(domain, background = false)
    }

    /**
     * A speculative upload is refused while any visible or interactive decode is still in flight, so a
     * read-ahead tile cannot become resident before the visible set it is meant to follow. Every
     * upload still shares the single upload slot.
     */
    private fun acquireUpload(domain: WorkDomain, background: Boolean): PermitClaim? {
        if (background && foregroundDecodeUsed > 0) return null
        return acquireSingle(domain, uploadUsed, limits.uploads) { uploadUsed += 1 }
    }

    private inline fun acquireSingle(
        domain: WorkDomain,
        used: Int,
        limit: Int,
        reserve: () -> Unit,
    ): PermitClaim? {
        if (used >= limit) return null
        reserve()
        return PermitClaim(domain, background = false)
    }
}
