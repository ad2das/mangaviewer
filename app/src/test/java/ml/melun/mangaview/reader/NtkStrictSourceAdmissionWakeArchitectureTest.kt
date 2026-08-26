package ml.melun.mangaview.reader

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NtkStrictSourceAdmissionWakeArchitectureTest {
    private val registry = File(
        "src/main/java/ml/melun/mangaview/reader/NtkStrictSourceOwnershipRegistry.kt",
    ).readText()
    private val session = File(
        "src/main/java/ml/melun/mangaview/reader/NtkStrictSourceSession.kt",
    ).readText()
    private val spool = File(
        "src/main/java/ml/melun/mangaview/reader/NtkSourceSpoolRegistry.kt",
    ).readText()

    @Test
    fun finalPhysicalOperationOwnsAnOutOfLockOneShotDispatch() {
        val finish = functionBody(registry, "private fun finishOperation(")
        val removal = finish.indexOf("record.operations.remove(tag.operationId)")
        val collect = finish.indexOf("takeReadyOperationAdmissionWaitersLocked(")
        val synchronizedEnd = finish.indexOf(
            "dispatchOperationAdmissionWaiters(readyAdmissionWaiters)",
        )

        assertTrue(removal >= 0)
        assertTrue(collect > removal)
        assertTrue(synchronizedEnd > collect)
        val dispatch = functionBody(registry, "private fun dispatchOperationAdmissionWaiters(")
        assertTrue(dispatch.contains("runCatching { waiter.callback(waiter.wake) }"))
        assertTrue(dispatch.contains("onFailure { waiter.wake.close() }"))
        assertTrue(dispatch.contains("operationAdmissionDispatchQueue"))
        assertTrue(dispatch.contains("operationAdmissionDispatching"))
    }

    @Test
    fun oneGrantedOwnerLinearizesTheEmptyGateUntilItsFirstOperation() {
        val observe = functionBody(registry, "internal fun observeOperationAdmission(")
        val begin = functionBody(registry, "internal fun beginOperationWithAdmissionWake(")
        val collect = functionBody(
            registry,
            "private fun takeReadyOperationAdmissionWaitersLocked(",
        )

        assertTrue(observe.contains("operationAdmissionGrant == null"))
        assertTrue(observe.contains("operationAdmissionGrant = wake"))
        assertTrue(collect.contains("if (operationAdmissionGrant != null) return"))
        assertTrue(collect.contains("operationAdmissionGrant = wake"))
        assertTrue(collect.contains("continue"))
        assertTrue(collect.contains("break"))
        val consume = begin.indexOf("grant.consumeGranted()")
        val publish = begin.indexOf("record.operations[tag.operationId] = ActiveOperation(")
        assertTrue(consume >= 0 && publish > consume)
        assertTrue(begin.contains("check(allowBlocking)"))
    }

    @Test
    fun sourceActorParksBothAdmissionSeamsOnOneIdentityWake() {
        val helper = functionBody(
            session,
            "private fun canBeginOrAwaitOperationAdmissionActor(",
        )
        val refill = functionBody(session, "private fun refillLanesActor(")
        val recovery = functionBody(
            session,
            "private fun serviceCurrentWebtoonRecoveryProofOwnerActor(",
        )
        assertTrue(refill.contains("canBeginOrAwaitOperationAdmissionActor(exactOpen.owner)"))
        assertTrue(recovery.contains("canBeginOrAwaitOperationAdmissionActor(exactOpen.owner)"))
        assertTrue(helper.contains("observeOperationAdmission(owner)"))
        assertTrue(helper.contains("executeActor(onRejected = { wake.close() }) {"))
        assertTrue(helper.contains("operationAdmissionWake !== wake"))
        assertTrue(helper.contains("existing.isGranted() -> return operationAdmissionWakeConsumable"))
        assertTrue(helper.contains("operationAdmissionWakeConsumable = true"))
        assertTrue(helper.indexOf("operationAdmissionWakeConsumable = true") <
            helper.indexOf("resumeGrantedOperationAdmissionActor(wake)"))
        assertTrue(helper.contains("operationAdmissionWakeConsumable = false"))
        val resume = functionBody(session, "private fun resumeGrantedOperationAdmissionActor(")
        assertTrue(resume.contains("drivePendingExactBindingInstallActor()"))
        assertTrue(resume.contains("adoptAllSealedBodiesActor()"))
        assertTrue(resume.contains("refillLanesActor()"))
        assertFalse(helper.contains("postDelayed"))
        assertFalse(helper.contains("Thread.sleep"))
    }

    @Test
    fun everyActorExactOperationIsGuardedByTheNonBlockingAdmissionDriver() {
        val installDriver = functionBody(session, "private fun drivePendingExactBindingInstallActor(")
        val resident = functionBody(session, "private fun adoptResidentBodyActor(")
        val file = functionBody(session, "private fun scheduleAdoptionActor(")
        val refill = functionBody(session, "private fun refillLanesActor(")
        val begin = functionBody(session, "private fun beginExactOperationActor(")

        assertTrue(installDriver.contains("canBeginOrAwaitOperationAdmissionActor(pending.owner)"))
        assertTrue(resident.contains("canBeginOrAwaitOperationAdmissionActor(exactOpen.owner)"))
        assertTrue(file.contains("canBeginOrAwaitOperationAdmissionActor(exactOpen.owner)"))
        val restore = refill.indexOf("preGeometryPendingPages.addFirst(page.pageIndex)")
        assertTrue(restore >= 0)
        assertTrue(refill.indexOf("return", restore) > restore)
        assertTrue(begin.contains("allowBlocking = false"))
        assertTrue(resident.contains("page.adoptedExactContext = context"))
        assertTrue(resident.contains("syntheticContext?.takeIf"))
        assertTrue(resident.contains("owned.operationLease.complete()"))

        val fileResult = file.indexOf("val result = runCatching {")
        val fileDispatch = file.indexOf("executeActor(", fileResult)
        assertTrue(fileResult >= 0 && fileDispatch > fileResult)
        assertTrue(
            file.indexOf("publishResidentBodyForRender(adoption.published)", fileResult) in
                (fileResult + 1) until fileDispatch,
        )
        val physical = functionBody(session, "private fun executePhysical(")
        val physicalResult = physical.indexOf("val result = runCatching {")
        val physicalEnqueue = physical.indexOf("enqueuePhysicalCompletion(work, result)")
        assertTrue(physicalResult >= 0 && physicalEnqueue > physicalResult)
        assertTrue(
            physical.indexOf("publishResidentBodyForRender", physicalResult) in
                (physicalResult + 1) until physicalEnqueue,
        )
    }

    @Test
    fun wakeCancellationIsRegistryLinearizedAndNotifiesBlockingCallers() {
        val wake = functionBody(registry, "class OperationAdmissionWake internal constructor(")
        val cancel = functionBody(registry, "private fun cancelOperationAdmissionWake(")
        val authorize = functionBody(registry, "fun authorizeRollingLateAdmissions(")

        assertTrue(wake.contains("cancelOperationAdmissionWake(this)"))
        assertFalse(wake.substringAfter("override fun close()").contains("state.getAndSet"))
        assertTrue(wake.contains("check(Thread.holdsLock(globalLock))"))
        assertTrue(cancel.contains("wake.cancelFromRegistry()"))
        assertTrue(cancel.contains("globalLock.notifyAll()"))
        assertTrue(authorize.contains("takeReadyOperationAdmissionWaitersLocked(ready)"))
        assertTrue(authorize.contains("dispatchOperationAdmissionWaiters(ready)"))
    }

    @Test
    fun blockedPromotionFailureAndSameLaneAdoptionsKeepAContinuation() {
        val driver = functionBody(session, "private fun drivePendingExactBindingInstallActor(")
        val install = functionBody(session, "private fun installExactBindingActor(")
        val validate = functionBody(session, "private fun validateMonotonicPromotionPartition(")
        val completion = functionBody(session, "private fun completePhysicalActor(")
        val adoptionCompletion = functionBody(session, "private fun completeAdoptionActor(")
        val adoptAll = functionBody(session, "private fun adoptAllSealedBodiesActor(")
        val sealedLane = functionBody(
            session,
            "private fun hasPendingSealedAdoptionForLaneActor(",
        )

        assertTrue(driver.contains("isPromotionDeferredRetryPageActor(page)"))
        assertTrue(install.contains("promotion-quarantine-failure"))
        val deferredBegin = install.indexOf("deferredFailureContexts += page to")
        val deferredComplete = install.indexOf("deferredFailureContexts.forEach")
        assertTrue(deferredBegin >= 0 && deferredComplete > deferredBegin)
        assertTrue(install.contains("val failedPromotionLane = deferred.laneIndex"))
        assertTrue(install.contains("beginNextExactOperationActor(failedPromotionWork"))
        assertTrue(install.contains("protocol = \"promotion-quarantine-failure\""))
        assertTrue(validate.contains("isPromotionDeferredRetryPageActor(page)"))
        assertTrue(completion.contains("val preparedPromotion = phase as?"))
        assertTrue(completion.contains("work.pageIndex in preparedPromotion.snapshot.activePageIndexes"))

        val resultWhen = completion.indexOf("when (val value = result.getOrThrow())")
        val quarantined = completion.indexOf("is PhysicalResult.Quarantined ->", resultWhen)
        val resident = completion.indexOf("is PhysicalResult.ResidentAdopted ->", quarantined)
        assertTrue(quarantined >= 0 && resident > quarantined)
        val quarantinedBranch = completion.substring(quarantined, resident)
        assertTrue(quarantinedBranch.contains("adoptAllSealedBodiesActor()"))
        assertFalse(quarantinedBranch.contains("scheduleAdoptionActor("))
        val continueAdoption = adoptionCompletion.indexOf("adoptAllSealedBodiesActor()")
        val refill = adoptionCompletion.indexOf("refillLanesActor()", continueAdoption)
        assertTrue(continueAdoption >= 0 && refill > continueAdoption)
        assertTrue(adoptAll.contains("for (contextOwnedPass in listOf(true, false))"))
        assertTrue(adoptAll.contains("activeWorks[laneIndex] != null"))
        assertTrue(adoptAll.contains("hasExactContextOwnerForLaneActor(laneIndex)"))
        assertTrue(sealedLane.contains("phase !is SessionPhase.ExactOpen"))
    }

    @Test
    fun quarantineAttemptsStartASeparateExactProducerLedger() {
        val next = functionBody(session, "private fun beginNextExactOperationActor(")
        val begin = functionBody(session, "private fun beginExactOperationActor(")
        val install = functionBody(session, "private fun installExactBindingActor(")
        val resident = functionBody(session, "private fun adoptResidentBodyActor(")
        val file = functionBody(session, "private fun scheduleAdoptionActor(")

        assertTrue(session.contains("var exactLedgerAttemptOrdinal: Int = 0"))
        assertTrue(next.contains("val next = previous + 1"))
        assertTrue(next.contains("page.exactLedgerAttemptOrdinal = next"))
        assertTrue(begin.contains("exactAttemptOrdinal: Int"))
        assertTrue(begin.contains("exactAttemptOrdinal,"))
        assertTrue(begin.contains("attempt = exactAttemptOrdinal"))
        assertFalse(begin.contains("attempt = work.attemptOrdinal"))
        assertTrue(install.contains("beginNextExactOperationActor(work, manifest)"))
        assertTrue(resident.contains("beginNextExactOperationActor(synthetic, manifest)"))
        assertTrue(file.contains("beginNextExactOperationActor(synthetic, manifest)"))
    }

    @Test
    fun closeControlAndInstallCommitHandshakeCannotLoseTheOwner() {
        val gate = functionBody(session, "fun admitClose(")
        val requestClose = functionBody(session, "fun requestClose(cause: Throwable?)")
        val finishClose = functionBody(session, "private fun maybeFinishClosedActor(")
        val performClose = functionBody(spool, "private fun performCloseAction(")

        assertTrue(gate.indexOf("pendingCallbacks++") < gate.indexOf("publishCloseRequested()"))
        assertTrue(gate.contains("closePublished.await()"))
        assertTrue(requestClose.contains("actorCallbackGate.admitClose("))
        assertTrue(finishClose.contains("phase !is SessionPhase.Closing"))
        assertTrue(performClose.contains("installFuture.whenComplete"))
        assertTrue(performClose.contains("installFailure == null"))
        assertTrue(performClose.contains("return@whenComplete"))
        assertTrue(performClose.contains("if (installFuture == null)"))
        assertTrue(
            performClose.indexOf("installFuture.whenComplete") <
                performClose.indexOf("action.session?.requestClose(action.cause)"),
        )
        assertTrue(
            performClose.indexOf("if (action.allowPendingTokenRollback)") <
                performClose.indexOf("rollbackPendingExactAuthority(it)"),
        )
        assertTrue(
            performClose.indexOf("rollbackPendingExactAuthority(it)") <
                performClose.indexOf("action.session?.requestClose(action.cause)"),
        )
        assertTrue(
            performClose.indexOf("action.session?.requestClose(action.cause)") <
                performClose.indexOf("action.promotionResult?.complete(failedExactResult())"),
        )
    }

    @Test
    fun closeAndFailureCancelTheActorOwnedWake() {
        val close = functionBody(session, "private fun closeSessionActor(")
        val fail = functionBody(session, "private fun failSessionActor(")
        val clear = functionBody(session, "private fun clearOperationAdmissionWakeActor(")

        assertTrue(close.contains("clearOperationAdmissionWakeActor()"))
        assertTrue(fail.contains("clearOperationAdmissionWakeActor()"))
        assertTrue(clear.contains("operationAdmissionWake?.close()"))
        assertTrue(clear.contains("operationAdmissionWake = null"))
    }

    private fun functionBody(source: String, signature: String): String {
        val start = source.indexOf(signature)
        check(start >= 0) { "Missing function: $signature" }
        val open = source.indexOf('{', start)
        check(open >= 0) { "Missing opening brace: $signature" }
        var depth = 0
        for (index in open until source.length) {
            when (source[index]) {
                '{' -> depth++
                '}' -> {
                    depth--
                    if (depth == 0) return source.substring(start, index + 1)
                }
            }
        }
        error("Unterminated function: $signature")
    }
}
