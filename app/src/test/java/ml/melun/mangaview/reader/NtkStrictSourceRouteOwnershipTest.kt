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
        assertTrue(session.contains("val resolvedRoute: ReaderImageCache.NtkResolvedSourceRoute?"))
        assertTrue(session.contains("val route = checkNotNull(work.resolvedRoute)"))
        val launchStart = session.indexOf("private fun launchPrimaryFullBodyActor(")
        val launchEnd = session.indexOf("private fun createQuarantineWorkActor(", launchStart)
        val launch = session.substring(launchStart, launchEnd)
        assertFalse(launch.contains("PRODUCTION_NTK_STRICT_SOURCE_ROUTE_RESOLVER.resolve("))
        assertTrue(
            compactSession.contains(
                "work.exactContext = beginExactOperationActor(work, manifest, route)"
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
        val routeEnd = cache.indexOf("fun resolveQuarantineSourceRoute(", routeStart)
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
    fun untaggedNtkCallsRemainBlockedExceptForTheBoundedUserDrivenAdjacentWindow() {
        val cache = readSource("ReaderImageCache.kt")
        val trackedStart = cache.indexOf("private fun newTrackedNtkEpisodeCall(")
        val trackedEnd = cache.indexOf("private fun imageTelemetrySourceKey(", trackedStart)
        assertTrue(trackedStart >= 0)
        assertTrue(trackedEnd > trackedStart)
        val tracked = cache.substring(trackedStart, trackedEnd)

        assertTrue(tracked.contains("requestedAdjacentViewerCall"))
        assertTrue(tracked.contains("isAuthorizedAdjacentForegroundViewerPath(path)"))
        assertTrue(tracked.contains("!requestedAdjacentViewerCall"))
        assertTrue(tracked.contains("requestedAdjacentViewerCall -> true"))
        assertTrue(tracked.contains("throw LegacySourceCallSuppressedException(path)"))

        val authorizationStart =
            cache.indexOf("private fun isAuthorizedAdjacentForegroundViewerPath(")
        val authorizationEnd =
            cache.indexOf("@JvmStatic", authorizationStart)
        assertTrue(authorizationStart >= 0)
        assertTrue(authorizationEnd > authorizationStart)
        val authorization = cache.substring(authorizationStart, authorizationEnd)
        assertTrue(
            authorization.contains(
                "NtkStrictSourceOwnershipRegistry.owner(key) != null"
            )
        )
        assertFalse(authorization.contains("legacySourceOperationAllowed(key)"))
        assertFalse(authorization.contains("MainApplication.isNtkForegroundViewerPathActive()"))
        assertTrue(authorization.contains("hasActiveAdjacentNtkForegroundViewerGrant(key)"))

        val grantStart =
            cache.indexOf("internal fun hasActiveAdjacentNtkForegroundViewerGrant(")
        val grantEnd =
            cache.indexOf("private fun isAuthorizedAdjacentForegroundViewerPath(", grantStart)
        assertTrue(grantStart >= 0)
        assertTrue(grantEnd > grantStart)
        val grant = cache.substring(grantStart, grantEnd)
        assertTrue(grant.contains("adjacentForegroundViewerPaths[key]"))
        assertTrue(grant.contains("allowedUntil > now"))
        assertTrue(grant.contains("adjacentForegroundViewerPaths.remove(key, allowedUntil)"))

        val publicationStart = cache.indexOf("private fun strictManifestPublicationAllowed(")
        val publicationEnd = cache.indexOf("@JvmStatic", publicationStart)
        assertTrue(publicationStart >= 0)
        assertTrue(publicationEnd > publicationStart)
        val publication = cache.substring(publicationStart, publicationEnd)
        assertTrue(
            publication.contains(
                "strictEpisode && isAuthorizedAdjacentForegroundViewerPath(path)"
            )
        )
        assertTrue(
            publication.contains(
                "NtkSourceSpoolRegistry.isDiscoveryActive(path)"
            )
        )
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
        assertTrue(
            NtkStrictSourceFailurePolicy.isRetryableTransportFailure(
                IOException("transport errors remain classifiable")
            )
        )
        assertFalse(
            NtkStrictSourceFailurePolicy.shouldRetryPhysicalFailure(
                IOException("attempt budget exhausted"),
                NtkStrictSourceFailurePolicy.MAX_PHYSICAL_ATTEMPTS,
                NtkStrictSourceFailurePolicy.MAX_PHYSICAL_RECOVERY_CYCLES
            )
        )
        assertTrue(
            NtkStrictSourceFailurePolicy.retryDelayMs(
                NtkStrictSourceFailurePolicy.MAX_PHYSICAL_ATTEMPTS,
                1
            ) >= 500L
        )
        assertFalse(
            NtkStrictSourceFailurePolicy.isRecoverablePhysicalFailure(
                NtkSourceIdentityException("authority changed"),
                1
            )
        )
        assertFalse(
            NtkStrictSourceFailurePolicy.isRetryableTransportFailure(
                NtkSourceIdentityException("authority changed")
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
    fun aPrematurelyClosedWebtoonBodyIsRetriedAsTransportFailure() {
        val cache = readSource("ReaderImageCache.kt")
        val bodyStart = cache.indexOf("private class NtkStalledReplicaResponseBody(")
        val bodyEnd = cache.indexOf(
            "private class NtkReplicaFailoverCallFactory(",
            bodyStart,
        )

        assertTrue(bodyStart >= 0)
        assertTrue(bodyEnd > bodyStart)
        val body = cache.substring(bodyStart, bodyEnd)
        assertTrue(body.contains("retryClosedBodyAsTransportFailure"))
        assertTrue(body.contains("throw IOException(\"Webtoon replica response body closed\")"))
        assertTrue(
            body.indexOf("throw IOException(\"Webtoon replica response body closed\")") <
                body.indexOf("check(!closed) { \"closed\" }")
        )
    }

    @Test
    fun aRecoveredLogicalCallDoesNotInheritItsPhysicalHeaderLoserCancellation() {
        val cache = readSource("ReaderImageCache.kt")
        val callStart = cache.indexOf("private class NtkReplicaFailoverCall(")
        val callEnd = cache.indexOf(
            "private data class NtkManhwaRangeSegment(",
            callStart,
        )

        assertTrue(callStart >= 0)
        assertTrue(callEnd > callStart)
        val call = cache.substring(callStart, callEnd)
        assertTrue(call.contains("override fun isCanceled(): Boolean = cancelled.get()"))
        assertFalse(call.contains("active.get()?.isCanceled()"))
        assertFalse(call.contains("activeExactQuicRecovery.get()?.isCancelled"))
    }

    @Test
    fun tinyReplicaBodyProbeRestoresThePhysicalSourcesTimeoutState() {
        val cache = readSource("ReaderImageCache.kt")
        val callStart = cache.indexOf("private class NtkReplicaFailoverCall(")
        val callEnd = cache.indexOf(
            "private data class NtkManhwaRangeSegment(",
            callStart,
        )

        assertTrue(callStart >= 0)
        assertTrue(callEnd > callStart)
        val call = cache.substring(callStart, callEnd)
        val probeStart = call.indexOf("private fun peekTinyBodyWithinWebtoonFirstByteDeadline(")
        val probeEnd = call.indexOf("private fun shouldTryCarrierManhwaQuicRecovery(", probeStart)
        assertTrue(probeStart >= 0)
        assertTrue(probeEnd > probeStart)
        val probe = call.substring(probeStart, probeEnd)
        assertTrue(probe.contains("NTK_WEBTOON_BODY_FIRST_BYTE_DEADLINE_MS"))
        assertTrue(probe.contains("originalTimeoutNanos"))
        assertTrue(probe.contains("originalDeadlineNanos"))
        assertTrue(probe.contains("sourceTimeout.clearTimeout()"))
        assertTrue(probe.contains("sourceTimeout.clearDeadline()"))
        assertTrue(probe.indexOf("return response.peekBody(byteCount).bytes()") < probe.indexOf("} finally {"))
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
        assertTrue(
            commit.contains(
                "acceptExactBody(page, published, releaseAdjacentRunway = false)"
            )
        )
        assertTrue(commit.contains("completeResidentOperationActor(body, published, acceptedContext)"))
        assertTrue(
            commit.contains(
                "maybeReleaseAdjacentPrefetchAfterRunwayActor(\"resident_body_published\")"
            )
        )
        assertTrue(
            commit.indexOf(
                "acceptExactBody(page, published, releaseAdjacentRunway = false)"
            ) < commit.indexOf(
                "completeResidentOperationActor(body, published, acceptedContext)"
            ) && commit.indexOf(
                "completeResidentOperationActor(body, published, acceptedContext)"
            ) < commit.indexOf(
                "maybeReleaseAdjacentPrefetchAfterRunwayActor(\"resident_body_published\")"
            )
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
