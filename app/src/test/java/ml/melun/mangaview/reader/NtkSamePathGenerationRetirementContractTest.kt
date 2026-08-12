package ml.melun.mangaview.reader

import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Regression contracts for reopening the same episode path after the previous viewer retires.
 *
 * The runtime assertion protects the immutable reader hand-off. The source-shape assertions are
 * intentional architecture tests: the registry and coordinator are Android process singletons,
 * and constructing their real source actors in a local JVM test would replace the very ownership
 * boundaries this test is meant to inspect with mocks.
 */
class NtkSamePathGenerationRetirementContractTest {
    private val path = "/manhwa/33727/1692251"
    private val assets = listOf(
        "https://images.example/page-001.jpg",
        "https://images.example/page-002.jpg",
    )
    private val headers = NtkStripDigests.sha256Tokens("ntk-selected-headers-v1")

    @Test
    fun strictExactLaunchSealBindsDiscoveryGenerationAndAuthorityProofDigest() {
        val first = authority(
            generation = 71L,
            proofSalt = "first",
        )
        val sameSealDifferentProof = authority(
            generation = 71L,
            proofSalt = "replacement",
        )
        assertEquals(first.seal, sameSealDifferentProof.seal)
        assertFalse(
            first.proof.proofDigestSha256 ==
                sameSealDifferentProof.proof.proofDigestSha256
        )

        val launchSeal = StrictExactLaunchSeal.from(first)

        assertEquals(first.proof.discoveryGeneration, launchSeal.discoveryGeneration)
        assertEquals(first.proof.proofDigestSha256, launchSeal.proofDigestSha256)
        assertTrue(launchSeal.hasSameAuthority(first))
        assertFalse(
            "An identical manifest seal must not substitute for a different authority proof",
            launchSeal.hasSameAuthority(sameSealDifferentProof),
        )
    }

    @Test
    fun registryRetirementIsQualifiedByExactPathAndDiscoveryGeneration() {
        val registry = readSource(
            "reader",
            "NtkSourceSpoolRegistry.kt",
        )
        val method = sourceFunction(
            registry,
            "fun retireDiscoveryGenerationForReplacement(",
            "fun isDiscoveryActive(",
        )

        assertTrue(method.contains("normalizedPath(path)"))
        assertTrue(method.contains("discoveryGeneration <= 0L"))
        assertTrue(method.contains("synchronized(mutationLock("))
        assertTrue(method.contains("entries["))
        assertTrue(method.contains("entry.lease.generation.value"))
        assertTrue(
            "The active entry must be rejected unless its exact generation matches",
            Regex(
                "entry\\.lease\\.generation\\.value\\s*(!=|==)\\s*discoveryGeneration",
            ).containsMatchIn(method),
        )
        assertTrue(
            "Retirement must act through the generation-qualified entry/lease",
            method.contains("entry.lease") &&
                (method.contains("failClosedLocked(") ||
                    method.contains("retireDiscoveryForReplacement(") ||
                    method.contains("retireEntryForReplacementLocked(")),
        )
        val exactGenerationGuard = method.indexOf(
            "if (entry.lease.generation.value != discoveryGeneration)",
        )
        val retirementMutation = method.indexOf(
            "retireEntryForReplacementLocked(entry, cause)",
        )
        assertTrue(exactGenerationGuard >= 0)
        assertTrue(
            "A newer same-path generation must be rejected before any retirement mutation",
            retirementMutation > exactGenerationGuard,
        )

        val activity = readSource(
            "activity",
            "ReaderV2Activity.kt",
        )
        val onDestroy = sourceFunction(
            activity,
            "override fun onDestroy()",
            "private fun publishStrictTelemetryBeforeClose()",
        )
        val adjacent = sourceFunction(
            activity,
            "private fun beginStrictAdjacentEpisodeTransition(",
            "private fun ",
        )
        assertGenerationQualifiedReaderRetirement(onDestroy)
        assertGenerationQualifiedReaderRetirement(adjacent)
    }

    @Test
    fun successfulCoordinatorFlightRemainsOwnedUntilExplicitRetirement() {
        val coordinator = readSource(
            "reader",
            "NtkStrictEpisodeDiscoveryCoordinator.kt",
        )
        val flight = sourceFunction(
            coordinator,
            "private class Flight(",
            "private val flights =",
        )
        val runFlight = sourceFunction(
            coordinator,
            "private fun runFlight(",
            "private fun requireDiscoveryOwnership(",
        )
        val retireOwner = sourceFunction(
            coordinator,
            "fun retireViewerOwnership(",
            "private fun runFlight(",
        )
        val completeOwnedFlight = sourceFunction(
            coordinator,
            "private fun completeOwnedFlight(",
            "private inline fun <T> tracePageListPhysicalRequest(",
        )

        assertTrue(flight.contains("AtomicBoolean"))
        assertTrue(flight.contains("completed"))

        val ownershipComplete = completeOwnedFlight.indexOf(
            "requireDiscoveryOwnership(flight, \"discovery_complete\")",
        )
        val completedPublication = completeOwnedFlight.indexOf("flight.completed.set(true)")
        val completionCall = runFlight.indexOf("completeOwnedFlight(")
        val finallyBlock = runFlight.indexOf("finally")
        val conditionalRemoval = runFlight.indexOf("if (!flight.completed.get()", finallyBlock)
        val flightRemoval = runFlight.indexOf("flights.remove(path, flight)", conditionalRemoval)
        assertTrue(ownershipComplete >= 0)
        assertTrue(completedPublication > ownershipComplete)
        assertTrue(completionCall >= 0)
        assertTrue(finallyBlock > completionCall)
        assertTrue(conditionalRemoval > finallyBlock)
        assertTrue(flightRemoval > conditionalRemoval)
        assertTrue(runFlight.contains("flight.retirement.isRetired()"))
        val incompleteInstalledGuard = runFlight.indexOf(
            "else if (exactInstalled && !flight.completed.get())",
        )
        val incompleteInstalledRetirement = runFlight.indexOf(
            "retireDiscoveryForReplacement(",
            incompleteInstalledGuard,
        )
        assertTrue(incompleteInstalledGuard > completionCall)
        assertTrue(incompleteInstalledRetirement > incompleteInstalledGuard)

        assertTrue(retireOwner.contains("retirement.retire("))
        assertTrue(retireOwner.contains("retireDiscoveryForReplacement("))
        assertTrue(retireOwner.contains("flights.remove(ownedPath, owned)"))
    }

    @Test
    fun strictSamePathReconfigurationRetiresOneShotSourceBeforeRestart() {
        val activity = readSource("activity", "ReaderV2Activity.kt")
        val toggle = sourceFunction(
            activity,
            "private fun toggleAutoCut()",
            "private fun updateAutoCutButton()",
        )
        val restart = sourceFunction(
            activity,
            "private fun restartStrictSessionWithFreshAuthority(",
            "private fun updateAutoCutButton()",
        )

        assertTrue(toggle.contains("restartStrictSessionWithFreshAuthority("))
        assertTrue(restart.contains("saveCurrentReadingProgress()"))
        val coordinatorRetire = restart.indexOf("retireViewerOwnership(")
        val generationRetire = restart.indexOf("retireDiscoveryGenerationForReplacement(")
        val sessionCancel = restart.indexOf("activeSession.cancel()")
        val restartWait = restart.indexOf("startStrictReaderSessionWhenExactReady(")
        assertTrue(coordinatorRetire >= 0)
        assertTrue(generationRetire > coordinatorRetire)
        assertTrue(sessionCancel > generationRetire)
        assertTrue(restartWait > sessionCancel)
        assertTrue(restart.contains("clearViewImmediately = false"))
    }

    @Test
    fun lifecyclePathLockDoesNotCoverSessionConstructionOrThreadPrestart() {
        val registry = readSource("reader", "NtkSourceSpoolRegistry.kt")
        val reserve = sourceFunction(
            registry,
            "fun reserveDocumentPlan(",
            "private fun planEvidenceMatches(",
        )
        val firstLock = reserve.indexOf("val admission = synchronized(mutationLock(path))")
        val firstLockExit = reserve.indexOf("performCloseAction(closeAction)", firstLock)
        val bootstrap = reserve.indexOf("val bootstrap = awaitActorFuture(")
        val session = reserve.indexOf("NtkStrictSourceSession(", bootstrap)
        val finalLock = reserve.indexOf("val result = synchronized(mutationLock(path))", session)

        assertTrue(firstLock >= 0)
        assertTrue(firstLockExit > firstLock)
        assertTrue("Executor adoption must be outside the admission lock", bootstrap > firstLockExit)
        assertTrue("Page/session construction must be outside the admission lock", session > bootstrap)
        assertTrue("Only the final pointer commit reacquires the path lock", finalLock > session)
    }

    private fun assertGenerationQualifiedReaderRetirement(source: String) {
        assertTrue(source.contains("retireDiscoveryGenerationForReplacement("))
        assertTrue(source.contains("discoveryGeneration"))
        val retire = source.indexOf("retireDiscoveryGenerationForReplacement(")
        val sessionCancel = source.indexOf("session?.cancel()")
        if (sessionCancel >= 0) {
            assertTrue("Source ownership must retire before its session", retire < sessionCancel)
        }
    }

    private fun authority(
        generation: Long,
        proofSalt: String,
    ): NtkAuthoritativeManifest {
        val seal = NtkEpisodeManifestSeal.create(path, generation, assets)
        val responseBody = """{"ok":true,"images":[
            {"page":1,"src":"${assets[0]}"},
            {"page":2,"src":"${assets[1]}"}
        ]}""".trimIndent().toByteArray()
        val proof = NtkViewerImageApiManifestProof.create(
            episodePath = path,
            discoveryGeneration = generation,
            canonicalRequestUrl = "https://newtoki1.org/api/viewer-images",
            canonicalFinalUrl = "https://newtoki1.org/api/viewer-images",
            selectedHeadersDigestSha256 = headers,
            requestBody =
                """{"workId":"33727","episodeId":"1692251","token":"$proofSalt"}"""
                    .toByteArray(),
            responseBody = responseBody,
            documentPlanProofDigestSha256 =
                NtkStripDigests.sha256Tokens("plan", proofSalt),
            viewerImageRequestIdentityDigestSha256 =
                NtkStripDigests.sha256Tokens("identity", proofSalt),
            responseConsumedToEof = true,
            orderedAssetSelectionPolicyVersion =
                NtkViewerImageApiManifestProof.ORDERED_ASSET_SELECTION_POLICY_VERSION,
            orderedAssets = assets,
            seal = seal,
        )
        return NtkAuthoritativeManifest(seal, proof)
    }

    private fun sourceFunction(
        source: String,
        startToken: String,
        endToken: String,
    ): String {
        val start = source.indexOf(startToken)
        assertTrue("Missing source token: $startToken", start >= 0)
        val searchFrom = start + startToken.length
        val end = source.indexOf(endToken, searchFrom)
        assertTrue("Missing source boundary after $startToken: $endToken", end > start)
        return source.substring(start, end)
    }

    private fun readSource(packageLeaf: String, name: String): String =
        String(
            Files.readAllBytes(sourcePath(packageLeaf, name)),
            StandardCharsets.UTF_8,
        )

    private fun sourcePath(packageLeaf: String, name: String): Path {
        val relative = Paths.get(
            "src",
            "main",
            "java",
            "ml",
            "melun",
            "mangaview",
            packageLeaf,
            name,
        )
        if (Files.isRegularFile(relative)) return relative
        return Paths.get("app").resolve(relative)
    }
}
