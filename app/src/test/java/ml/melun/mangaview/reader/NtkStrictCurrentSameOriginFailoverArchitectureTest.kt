package ml.melun.mangaview.reader

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

class NtkStrictCurrentSameOriginFailoverArchitectureTest {
    private val coordinator = File(
        "src/main/java/ml/melun/mangaview/reader/NtkStrictEpisodeDiscoveryCoordinator.kt",
    ).readText()
    private val client = File(
        "src/main/java/ml/melun/mangaview/mangaview/CustomHttpClient.java",
    ).readText()

    @Test
    fun directWifiCurrentScopeIsFrozenAtFlightConstruction() {
        val start = coordinator.substringAfter("private fun startInternal(")
            .substringBefore("val ackRoute = try")

        assertTrue(start.contains("val directWifiCurrentViewer = !adjacentOwned"))
        assertTrue(start.contains("transportState.first && !transportState.second"))
        assertTrue(start.contains("directWifiCurrentViewer,"))
    }

    @Test
    fun sameOriginFailoverSkipsOnlyResolverAndKeepsRetireRestartPath() {
        val recovery = coordinator.substringAfter("private fun recoverStrictRouteAndRestart(")
            .substringBefore("private fun requireDiscoveryOwnership(")

        assertTrue(recovery.contains("if (skipDomainResolution)"))
        assertTrue(recovery.contains("client.resolveNtkDomainAfterRouteFailure()"))
        assertTrue(recovery.indexOf("NtkSourceSpoolRegistry.retireDiscoveryForReplacement(") <
            recovery.indexOf("val restarted = startInternal("))
        assertTrue(recovery.contains("if (skipDomainResolution) 0 else 1"))
        assertTrue(recovery.contains("failedFlight.sameOriginFallbackConsumed || skipDomainResolution"))
        assertTrue(coordinator.contains("flight.sameOriginFallbackConsumed,"))
    }

    @Test
    fun restartedDocumentExplicitlyBypassesSharedHttpEngineRace() {
        assertTrue(coordinator.contains("flight.sameOriginFallbackConsumed,"))
        assertTrue(client.contains("boolean forceOkHttp"))
        assertTrue(
            client.contains(
                "if(!forceOkHttp && sharedEngine != null && sharedExecutor != null",
            ),
        )
    }
}
