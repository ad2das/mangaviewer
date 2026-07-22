package ml.melun.mangaview.reader

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.InterruptedIOException
import java.io.IOException
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths

class NtkStrictSourceRouteOwnershipTest {
    @Test
    fun exactOpenSessionOwnsSealedRouteBeforeCompatibilityMirror() {
        val session = readSource("NtkStrictSourceSession.kt")
        val registry = readSource("NtkSourceSpoolRegistry.kt")
        val cache = readSource("ReaderImageCache.kt")
        val compactSession = session.replace(Regex("\\s+"), " ")

        assertTrue(session.contains("private fun beginExactOperationActor("))
        assertTrue(session.contains("manifest: NtkAuthoritativeManifest"))
        assertTrue(session.contains("phase = SessionPhase.ExactOpen("))
        assertTrue(
            compactSession.contains(
                "PRODUCTION_NTK_STRICT_SOURCE_ROUTE_RESOLVER.resolve( " +
                    "manga, manifest.seal, pageIndex, canonicalAsset"
            )
        )
        assertTrue(session.contains("ReaderImageCache.resolveStrictSourceRoute("))

        val commitStart = registry.indexOf("entry.authoritative = incoming")
        val activation = registry.indexOf(
            "activeSession.enqueueActivateExactPublication(",
            commitStart
        )
        val activationAwait = registry.indexOf(
            "awaitActorFuture(checkNotNull(activationFuture))",
            activation
        )
        val ownedPreclaim = registry.indexOf(
            "entry.state = NtkSourceState.OWNED_PRECLAIM",
            activationAwait
        )
        val pendingClear = registry.indexOf("entry.pendingPromotion = null", ownedPreclaim)
        val publication = registry.indexOf(
            "authoritativeManifestChannel.publish(path, incoming)",
            pendingClear
        )
        assertTrue(commitStart >= 0)
        assertTrue(activation > commitStart)
        assertTrue(activationAwait > activation)
        assertTrue(ownedPreclaim > activationAwait)
        assertTrue(pendingClear > ownedPreclaim)
        assertTrue(publication > pendingClear)

        val routeStart = cache.indexOf("fun resolveStrictSourceRoute(")
        val routeEnd = cache.indexOf("private fun strictInstrumentedClient(", routeStart)
        assertTrue(routeStart >= 0)
        assertTrue(routeEnd > routeStart)
        val route = cache.substring(routeStart, routeEnd)

        assertTrue(route.contains("manifestSeal.isStructurallyComplete"))
        assertTrue(route.contains("manifestSeal.normalizedCanonicalAssets[pageIndex] == asset"))
        assertTrue(route.contains("httpClient.ntkDemandBoundExactImageFactory()"))
        assertTrue(route.contains("ntk-demand-bound-exact-image"))
        assertFalse(route.contains("isAuthoritativeNativeDirectManifestImage"))
        assertFalse(route.contains("httpClient.imageClient"))
    }

    @Test
    fun physicalCompletionSeparatesRecoverablePageFailureFromTerminalSessionFailure() {
        val session = readSource("NtkStrictSourceSession.kt")
        val completionStart = session.indexOf("private fun completePhysicalActor(")
        val completionEnd = session.indexOf(
            "private fun adoptAllSealedBodiesActor()", completionStart
        )

        assertTrue(completionStart >= 0)
        assertTrue(completionEnd > completionStart)
        val completion = session.substring(completionStart, completionEnd)
        val failureStart = completion.indexOf("val failure = result.exceptionOrNull()")
        val resultStart = completion.indexOf("when (val value = result.getOrThrow())")
        assertTrue(failureStart >= 0)
        assertTrue(resultStart > failureStart)
        val failureBranch = completion.substring(failureStart, resultStart)

        assertTrue(failureBranch.contains("if (closeRequested.get())"))
        assertTrue(failureBranch.contains("maybeFinishClosedActor()"))
        assertTrue(failureBranch.contains("isRecoverablePhysicalFailure("))
        assertTrue(failureBranch.contains("page.primaryStarted = false"))
        assertTrue(failureBranch.contains("refillLanesActor()"))
        assertTrue(failureBranch.contains("failSessionActor(failure, page)"))
        assertTrue(
            failureBranch.indexOf("maybeFinishClosedActor()") <
                failureBranch.indexOf("isRecoverablePhysicalFailure(") &&
                failureBranch.indexOf("isRecoverablePhysicalFailure(") <
                failureBranch.indexOf("failSessionActor(failure, page)")
        )
    }

    @Test
    fun transientIoRetriesAreBoundedWhileAuthorityFailuresStayTerminal() {
        assertTrue(
            NtkStrictSourceFailurePolicy.isRecoverablePhysicalFailure(
                InterruptedIOException("cancelled or timed out"),
                1
            )
        )
        assertTrue(
            NtkStrictSourceFailurePolicy.isRecoverablePhysicalFailure(
                RuntimeException(IOException("socket reset")),
                2
            )
        )
        assertFalse(
            NtkStrictSourceFailurePolicy.isRecoverablePhysicalFailure(
                IOException("attempt budget exhausted"),
                NtkStrictSourceFailurePolicy.MAX_PHYSICAL_ATTEMPTS
            )
        )
        assertFalse(
            NtkStrictSourceFailurePolicy.isRecoverablePhysicalFailure(
                NtkSourceIdentityException("authority changed"),
                1
            )
        )
        assertFalse(
            NtkStrictSourceFailurePolicy.isRecoverablePhysicalFailure(
                IllegalStateException("program invariant"),
                1
            )
        )
    }

    @Test
    fun residentBodyRetiresExactOperationBeforeTheLaneCanRefill() {
        val session = readSource("NtkStrictSourceSession.kt")
        val retirementStart = session.indexOf("private fun completeResidentOperationActor(")
        val retirementEnd = session.indexOf(
            "private fun acceptSeededExactBodiesActor()",
            retirementStart
        )

        assertTrue(retirementStart >= 0)
        assertTrue(retirementEnd > retirementStart)
        val retirement = session.substring(retirementStart, retirementEnd)
        assertTrue(retirement.contains("assertActorThread()"))
        assertTrue(retirement.contains("context.operationLease.complete("))
        assertFalse(retirement.contains("physicalLanes["))
        assertFalse(retirement.contains("adoptionInFlightByLane["))

        val commitStart = session.indexOf("private fun completeResidentAdoptionActor(")
        val commitEnd = session.indexOf("private fun completeResidentOperationActor(", commitStart)
        val commit = session.substring(commitStart, commitEnd)
        assertTrue(commit.contains("acceptExactBody(page, published)"))
        assertTrue(commit.contains("completeResidentOperationActor(body, published, acceptedContext)"))
        assertTrue(
            commit.indexOf("acceptExactBody(page, published)") <
                commit.indexOf("completeResidentOperationActor(body, published, acceptedContext)")
        )
    }

    private fun readSource(name: String): String =
        String(Files.readAllBytes(sourcePath(name)), StandardCharsets.UTF_8)

    private fun sourcePath(name: String): Path {
        val appRelative = Paths.get(
            "src", "main", "java", "ml", "melun", "mangaview", "reader", name
        )
        if (Files.isRegularFile(appRelative)) return appRelative
        return Paths.get(
            "app", "src", "main", "java", "ml", "melun", "mangaview", "reader", name
        )
    }
}
