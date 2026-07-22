package ml.melun.mangaview.ntkack

import java.net.URI

/** Explicit IPC boundary for the only cookies that may enter or leave the ACK process. */
object NtkAckCookieBoundary {
    val seedNames = setOf(
        "cf_clearance", "__cf_bm", "nv", "ntk_fp", "ntk_pid", "__vsid", "__ntk_ev_id",
    )
    val grantNames = setOf(
        "ad_ack", "ad_ack_c", "ad_guard_l", "ntk_ve", "cf_clearance", "__cf_bm",
        "nv", "ntk_fp", "ntk_pid", "__vsid",
    )
    val strictFreshNames = setOf("ad_ack", "ad_ack_c", "ad_guard_l", "ntk_ve")

    fun validateSeeds(origin: String, episodePath: String, seeds: List<NtkAckCookie>): List<NtkAckCookie> {
        val scope = scope(origin, episodePath)
        require(seeds.map { it.name }.toSet().size == seeds.size) { "Duplicate ACK seed cookie" }
        seeds.forEach { cookie ->
            require(cookie.name in seedNames) { "Forbidden ACK seed cookie: ${cookie.name}" }
            require(cookie.name !in strictFreshNames) { "StrictFresh ACK cookie cannot be seeded" }
            require(cookie.value.isNotBlank()) { "Empty ACK seed cookie" }
            validateScope(scope, cookie, requireResponseEvidence = false)
        }
        return seeds.map(NtkAckCookie::copy)
    }

    fun validateGrants(origin: String, episodePath: String, grants: List<NtkAckCookie>): List<NtkAckCookie> {
        val scope = scope(origin, episodePath)
        require(grants.map { it.name }.toSet().size == grants.size) { "Duplicate ACK grant cookie" }
        grants.forEach { cookie ->
            require(cookie.name in grantNames) { "Forbidden ACK grant cookie: ${cookie.name}" }
            require(cookie.value.isNotBlank()) { "Empty ACK grant cookie" }
            validateScope(scope, cookie, requireResponseEvidence = true)
        }
        return grants.map(NtkAckCookie::copy)
    }

    private data class Scope(val scheme: String, val host: String, val episodePath: String)

    private fun scope(origin: String, episodePath: String): Scope {
        val uri = URI(origin)
        require(uri.scheme == "https" && !uri.host.isNullOrBlank() && uri.rawQuery == null && uri.rawFragment == null)
        require(origin == "https://${uri.host.lowercase()}") { "ACK origin must be normalized" }
        require(episodePath.matches(Regex("^/(?:manhwa|webtoon)/[^/?#]+/[^/?#]+$")))
        return Scope(uri.scheme, uri.host.lowercase(), episodePath)
    }

    private fun validateScope(scope: Scope, cookie: NtkAckCookie, requireResponseEvidence: Boolean) {
        val domain = cookie.domain.trim().removePrefix(".").lowercase()
        if (domain.isNotEmpty()) {
            require(scope.host == domain || scope.host.endsWith(".$domain")) { "ACK cookie domain mismatch" }
        }
        val path = cookie.path.ifBlank { "/" }
        require(path.startsWith('/') && scope.episodePath.startsWith(path)) { "ACK cookie path mismatch" }
        if (cookie.secure) require(scope.scheme == "https")
        if (requireResponseEvidence) {
            require(cookie.setCookieDigestSha256.isSha256()) { "ACK grant lacks Set-Cookie evidence" }
            val response = URI(cookie.responseUrl)
            require(response.scheme == scope.scheme && response.host?.lowercase() == scope.host) {
                "ACK grant response origin mismatch"
            }
        }
    }
}

private fun String.isSha256(): Boolean = matches(Regex("[0-9a-f]{64}"))
