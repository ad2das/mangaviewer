package ml.melun.mangaview.reader

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

class NtkDirectWifiClientTopologyArchitectureTest {
    @Test
    fun directWifiUsesSharedMetadataAndBodyPoolsBeforeCarrierShards() {
        val cache = File(
            "src/main/java/ml/melun/mangaview/reader/ReaderImageCache.kt",
        ).readText()
        val start = cache.indexOf("fun prepareClickOwnedManhwaClientTopology()")
        val end = cache.indexOf("private fun strictInstrumentedClient", start)
        val topology = cache.substring(start, end)

        val wifiBranch = topology.indexOf("if (directWifiNetwork != null)")
        val sharedProbe = topology.indexOf("clickOwnedMixedFormatProbeClient(", wifiBranch)
        val sharedBody = topology.indexOf("clickOwnedDirectWifiOrdinaryBodyClient(", sharedProbe)
        val wifiReturn = topology.indexOf("return", sharedBody)
        val shardLoop = topology.indexOf(
            "repeat(NtkClickOwnedManhwaWavePolicy.CONNECTION_SHARDS)",
            wifiReturn,
        )

        assertTrue(wifiBranch >= 0)
        assertTrue(sharedProbe > wifiBranch)
        assertTrue(sharedBody > sharedProbe)
        assertTrue(wifiReturn > sharedBody)
        assertTrue(shardLoop > wifiReturn)
    }

    @Test
    fun directWifiRouteDoesNotMaterializeUnusedCarrierFallback() {
        val cache = File(
            "src/main/java/ml/melun/mangaview/reader/ReaderImageCache.kt",
        ).readText()
        val start = cache.indexOf("private fun clickOwnedDirectWifiOrdinaryRouteFactory(")
        val end = cache.indexOf("internal fun selectDirectWifiOrdinaryNetworkBoundH1", start)
        val route = cache.substring(start, end)

        val selection = route.indexOf("val selected = if (useNetworkBoundH1)")
        val fallback = route.indexOf("clickOwnedManhwaClient(shared, pageIndex)", selection)

        assertTrue(selection >= 0)
        assertTrue(fallback > selection)
        assertTrue(route.substring(0, selection).contains(
            "clickOwnedDirectWifiOrdinaryBodyClient(shared, directWifiNetwork)",
        ))
        assertTrue(!route.substring(0, selection).contains(
            "clickOwnedManhwaClient(shared, pageIndex)",
        ))
    }
}
