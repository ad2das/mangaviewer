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
            WorkDomain.UPLOAD -> acquireSingle(domain, uploadUsed, limits.uploads) {
                uploadUsed += 1
            }
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
            WorkDomain.DECODE -> decodeUsed -= 1
            WorkDomain.STORAGE -> storageUsed -= 1
            WorkDomain.UPLOAD -> uploadUsed -= 1
            WorkDomain.BROWSER -> browserUsed -= 1
        }
        check(networkUsed >= 0 && bodiesUsed >= 0 && backgroundNetworkUsed >= 0)
        check(decodeUsed >= 0 && storageUsed >= 0 && uploadUsed >= 0 && browserUsed >= 0)
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
        val limit = if (background && limits.decodes > 1) limits.decodes - 1 else limits.decodes
        if (decodeUsed >= limit) return null
        decodeUsed += 1
        return PermitClaim(domain, background = false)
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
