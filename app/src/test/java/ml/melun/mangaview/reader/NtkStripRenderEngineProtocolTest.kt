package ml.melun.mangaview.reader

import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NtkStripRenderEngineProtocolTest {
    @Test
    fun detachWaitsForAdmittedPostJniBookkeepingBeforeFreeze() {
        val nativeReturned = CountDownLatch(1)
        val allowPostJniBookkeeping = CountDownLatch(1)
        val detachAdmissionClosed = CountDownLatch(1)
        val committed = AtomicBoolean(false)
        val frozenCommitted = AtomicBoolean(false)
        val protocol = liveProtocol(object : NtkProtocolDeterministicHooks {
            override fun afterNativeReturnBeforeBookkeeping(operation: String) {
                if (operation == "admitted-install") {
                    nativeReturned.countDown()
                    awaitGuard(allowPostJniBookkeeping, "post-JNI bookkeeping release")
                }
            }

            override fun onDetachAdmissionClosed() {
                detachAdmissionClosed.countDown()
            }
        })
        val workers = Executors.newFixedThreadPool(2)
        try {
            val admitted = workers.submit<Boolean> {
                protocol.runOperation(
                    operation = "admitted-install",
                    admission = NtkProtocolAdmission.LIVE,
                    rejected = false,
                    prepareLocked = { NtkPreparedOperation("install") },
                    nativeCall = NtkProtocolNativeAdapter { true },
                    completeLocked = { _, result ->
                        result.getOrThrow()
                        committed.set(true)
                        true
                    }
                )
            }
            awaitGuard(nativeReturned, "native return")

            val detach = workers.submit<Boolean> {
                val preparation = beginDetach(
                    protocol,
                    onAdmissionClosedLocked = {},
                    prepareQuiescentLocked = { "detach" }
                )
                frozenCommitted.set(preparation?.value == "detach" && committed.get())
                preparation != null
            }
            awaitGuard(detachAdmissionClosed, "detach admission close")

            assertFalse("detach crossed the active-operation barrier", detach.isDone)
            assertFalse("post-JNI bookkeeping ran before its exact barrier", committed.get())
            allowPostJniBookkeeping.countDown()

            assertTrue(getGuard(admitted, "admitted operation"))
            assertTrue(getGuard(detach, "detach"))
            assertTrue("freeze did not observe admitted post-JNI bookkeeping", frozenCommitted.get())
            assertEquals(ProtocolPhase.DETACH_CLOSING, protocol.phaseSnapshot())
        } finally {
            allowPostJniBookkeeping.countDown()
            workers.shutdownNow()
        }
    }

    @Test
    fun releaseAdmittedBeforeCloseCannotEscapeContextLossFreeze() {
        val releasedToken = goldenTokenTwo()
        val nativeReturned = CountDownLatch(1)
        val allowMetadataBookkeeping = CountDownLatch(1)
        val detachAdmissionClosed = CountDownLatch(1)
        val bindings = linkedMapOf(releasedToken.keyForTest() to releasedToken)
        val releasedDuringHandoff = linkedMapOf<TestAuthorityKey, NtkNativeAuthorityToken>()
        val frozen = AtomicReference<Map<TestAuthorityKey, NtkNativeAuthorityToken>>()
        val protocol = liveProtocol(object : NtkProtocolDeterministicHooks {
            override fun afterNativeReturnBeforeBookkeeping(operation: String) {
                if (operation == "release-before-close") {
                    nativeReturned.countDown()
                    awaitGuard(allowMetadataBookkeeping, "release metadata bookkeeping")
                }
            }

            override fun onDetachAdmissionClosed() {
                detachAdmissionClosed.countDown()
            }
        })
        val workers = Executors.newFixedThreadPool(2)
        try {
            val release = workers.submit<Boolean> {
                protocol.runOperation(
                    operation = "release-before-close",
                    admission = NtkProtocolAdmission.RELEASE,
                    rejected = false,
                    prepareLocked = {
                        bindings[releasedToken.keyForTest()]?.let { NtkPreparedOperation(it) }
                    },
                    nativeCall = NtkProtocolNativeAdapter { it },
                    completeLocked = { token, result ->
                        result.getOrThrow()
                        assertEquals(ProtocolPhase.DETACH_CLOSING, protocol.phaseLocked())
                        bindings.remove(token.keyForTest())
                        releasedDuringHandoff[token.keyForTest()] = token
                        true
                    }
                )
            }
            awaitGuard(nativeReturned, "release native return")

            val detach = workers.submit<Boolean> {
                val preparation = beginDetach(
                    protocol,
                    onAdmissionClosedLocked = { releasedDuringHandoff.clear() },
                    prepareQuiescentLocked = {
                        val exact = LinkedHashMap(bindings)
                        releasedDuringHandoff.forEach { (key, token) -> exact[key] = token }
                        frozen.set(exact.toMap())
                        "detach"
                    }
                ) ?: return@submit false
                protocol.withProtocolLock {
                    protocol.setPhaseLocked(ProtocolPhase.RETIRED_BLOCKED)
                }
                preparation.value == "detach"
            }
            awaitGuard(detachAdmissionClosed, "detach admission close")
            assertFalse("detach did not wait for admitted release bookkeeping", detach.isDone)
            allowMetadataBookkeeping.countDown()

            assertTrue(getGuard(release, "release admitted before close"))
            assertTrue(getGuard(detach, "context-loss freeze"))
            assertEquals(releasedToken, frozen.get()[releasedToken.keyForTest()])
            assertEquals(ProtocolPhase.RETIRED_BLOCKED, protocol.phaseSnapshot())
        } finally {
            allowMetadataBookkeeping.countDown()
            workers.shutdownNow()
        }
    }

    @Test
    fun releaseArrivingDuringDetachClosingClaimsOnlyRetiredProof() {
        val token = goldenTokenOne()
        val detachAdmissionClosed = CountDownLatch(1)
        val allowDetachToRetire = CountDownLatch(1)
        val releaseInvoked = CountDownLatch(1)
        val frozen = linkedMapOf<TestAuthorityKey, NtkNativeAuthorityToken>()
        val registrations = linkedMapOf<
            TestAuthorityKey,
            NtkReleaseRegistration<NtkNativeAuthorityToken, String>
        >()
        val completionCount = AtomicInteger(0)
        val completionRan = CountDownLatch(1)
        val observedClaimPhase = AtomicReference<ProtocolPhase>()
        val protocol = liveProtocol(object : NtkProtocolDeterministicHooks {
            override fun onDetachAdmissionClosed() {
                detachAdmissionClosed.countDown()
                awaitGuard(allowDetachToRetire, "retired-proof publication")
            }
        })
        val workers = Executors.newFixedThreadPool(2)
        try {
            val detach = workers.submit<Boolean> {
                beginDetach(
                    protocol,
                    onAdmissionClosedLocked = {},
                    prepareQuiescentLocked = { "detach" }
                ) ?: return@submit false
                protocol.withProtocolLock {
                    frozen[token.keyForTest()] = token
                    protocol.setPhaseLocked(ProtocolPhase.RETIRED_BLOCKED)
                }
                true
            }
            awaitGuard(detachAdmissionClosed, "detach admission close")

            val release = workers.submit<String> {
                releaseInvoked.countDown()
                protocol.runOperation(
                    operation = "release-during-close",
                    admission = NtkProtocolAdmission.RELEASE,
                    rejected = "rejected",
                    prepareLocked = {
                        observedClaimPhase.set(protocol.phaseLocked())
                        frozen[token.keyForTest()]?.let { claimed ->
                            registrations[token.keyForTest()] = NtkReleaseRegistration(claimed) {
                                completionCount.incrementAndGet()
                                completionRan.countDown()
                            }
                            NtkPreparedOperation(claimed)
                        }
                    },
                    nativeCall = NtkProtocolNativeAdapter { "retired-proof" },
                    completeLocked = { claimed, result ->
                        assertEquals(token, claimed)
                        val ack = result.getOrThrow()
                        val registration = checkNotNull(registrations[token.keyForTest()])
                        registration.stagedAck = ack
                        registration.nativeDispatchable = true
                        scheduleNtkReleaseCompletionLocked(
                            protocol,
                            registrations,
                            token.keyForTest(),
                            registration
                        )
                        ack
                    }
                )
            }
            awaitGuard(releaseInvoked, "release invocation during DETACH_CLOSING")
            assertFalse("release crossed DETACH_CLOSING", release.isDone)
            allowDetachToRetire.countDown()

            assertTrue(getGuard(detach, "retired-proof publication"))
            assertEquals("retired-proof", getGuard(release, "retired proof claim"))
            assertEquals(ProtocolPhase.RETIRED_BLOCKED, observedClaimPhase.get())
            assertNotEquals(ProtocolPhase.LIVE_ATTACHED, observedClaimPhase.get())
            assertEquals(0, completionCount.get())
            protocol.withProtocolLock {
                assertEquals(1, registrations.size)
                protocol.setPhaseLocked(ProtocolPhase.RETIRED_DISPATCHABLE)
                val registration = checkNotNull(registrations[token.keyForTest()])
                scheduleNtkReleaseCompletionLocked(
                    protocol,
                    registrations,
                    token.keyForTest(),
                    registration
                )
                protocol.awaitChangedUninterruptiblyLocked { registrations.isEmpty() }
            }
            awaitGuard(completionRan, "retired proof external completion")
            assertEquals(1, completionCount.get())
        } finally {
            allowDetachToRetire.countDown()
            workers.shutdownNow()
        }
    }

    @Test
    fun releasedDuringHandoffMetadataParticipatesInFrozenDigest() {
        val retained = goldenTokenOne()
        val released = goldenTokenTwo()
        val metadataReturned = CountDownLatch(1)
        val allowMetadataBookkeeping = CountDownLatch(1)
        val detachAdmissionClosed = CountDownLatch(1)
        val bindings = linkedMapOf(
            retained.keyForTest() to retained,
            released.keyForTest() to released
        )
        val releasedDuringHandoff = linkedMapOf<TestAuthorityKey, NtkNativeAuthorityToken>()
        val frozenDigest = AtomicReference<String>()
        val digestWithoutHandoffMetadata = AtomicReference<String>()
        val protocol = liveProtocol(object : NtkProtocolDeterministicHooks {
            override fun afterNativeReturnBeforeBookkeeping(operation: String) {
                if (operation == "handoff-metadata") {
                    metadataReturned.countDown()
                    awaitGuard(allowMetadataBookkeeping, "handoff metadata bookkeeping")
                }
            }

            override fun onDetachAdmissionClosed() {
                detachAdmissionClosed.countDown()
            }
        })
        val workers = Executors.newFixedThreadPool(2)
        try {
            val metadata = workers.submit<Boolean> {
                protocol.runOperation(
                    operation = "handoff-metadata",
                    admission = NtkProtocolAdmission.RELEASE,
                    rejected = false,
                    prepareLocked = { NtkPreparedOperation(released) },
                    nativeCall = NtkProtocolNativeAdapter { it },
                    completeLocked = { token, result ->
                        result.getOrThrow()
                        bindings.remove(token.keyForTest())
                        releasedDuringHandoff[token.keyForTest()] = token
                        true
                    }
                )
            }
            awaitGuard(metadataReturned, "native release metadata return")

            val detach = workers.submit<Boolean> {
                beginDetach(
                    protocol,
                    onAdmissionClosedLocked = { releasedDuringHandoff.clear() },
                    prepareQuiescentLocked = {
                        digestWithoutHandoffMetadata.set(
                            NtkRetiredAuthorityDigest.compute(bindings.values)
                        )
                        val exact = LinkedHashMap(bindings)
                        releasedDuringHandoff.forEach { (key, token) -> exact[key] = token }
                        frozenDigest.set(NtkRetiredAuthorityDigest.compute(exact.values))
                        "detach"
                    }
                ) ?: return@submit false
                protocol.withProtocolLock {
                    protocol.setPhaseLocked(ProtocolPhase.RETIRED_BLOCKED)
                }
                true
            }
            awaitGuard(detachAdmissionClosed, "detach admission close")
            allowMetadataBookkeeping.countDown()

            assertTrue(getGuard(metadata, "handoff metadata"))
            assertTrue(getGuard(detach, "digest freeze"))
            assertNotEquals(NtkRetiredAuthorityDigest.GOLDEN_SHA256,
                digestWithoutHandoffMetadata.get())
            assertEquals(NtkRetiredAuthorityDigest.GOLDEN_SHA256, frozenDigest.get())
        } finally {
            allowMetadataBookkeeping.countDown()
            workers.shutdownNow()
        }
    }

    @Test
    fun retiredAuthorityDigestGoldenVectorMatchesNative() {
        val forward = listOf(goldenTokenOne(), goldenTokenTwo())
        val reverse = forward.reversed()

        assertEquals(NtkRetiredAuthorityDigest.GOLDEN_SHA256,
            NtkRetiredAuthorityDigest.compute(forward))
        assertEquals(NtkRetiredAuthorityDigest.GOLDEN_SHA256,
            NtkRetiredAuthorityDigest.compute(reverse))

        val nativeSource = readRepositoryFile("app/src/main/cpp/ntk_strip_renderer.cpp")
        val nativeDigest = functionBody(nativeSource, "std::string retired_authority_digest(")
        assertOrdered(
            nativeDigest,
            "append_i64(token.key.engine_generation);",
            "append_i64(token.key.authority_generation);",
            "append_i64(token.key.authority);",
            "append_i64(token.manifest_revision);",
            "append_string(token.manifest_digest);",
            "append_string(token.geometry_digest);"
        )
        val nativeVectors = functionBody(nativeSource,
            "std::string retired_authority_digest_test_vectors()")
        assertTrue(nativeVectors.contains("AuthorityKey{7, authority_generation, authority}"))
        assertTrue(nativeVectors.contains(
            "3, 101, 11, std::string(64, '0'), std::string(64, '1')"))
        assertTrue(nativeVectors.contains(
            "4, 202, 12, std::string(64, 'a'), std::string(64, 'f')"))
    }

    @Test
    fun preparedResidentCallbackUsesPreparationGenerationInsteadOfSurfaceEpoch() {
        val nativeSource = readRepositoryFile("app/src/main/cpp/ntk_strip_renderer.cpp")
        val callback = functionBody(
            nativeSource,
            "void enqueue_prepared_tile_resident(const GpuReadyTile& tile, bool success)"
        )

        assertTrue(callback.contains(
            "record.surface_epoch = tile.preparation_generation;"))
        assertFalse(callback.contains(
            "record.surface_epoch = tile.surface_epoch;"))
    }

    @Test
    fun nativeInputFramesRequireACompleteCausalMutationBeforePreparation() {
        val nativeSource = readRepositoryFile("app/src/main/cpp/ntk_strip_renderer.cpp")
        val causeGate = functionBody(
            nativeSource,
            "bool pending_frame_cause_ready_for_preparation() const"
        )
        assertOrdered(
            causeGate,
            "work.kind == PendingFrameKind::STAGE",
            "work.input.input_watermark == 0",
            "work.input.ordered()",
            "work.visual_demand_epoch != 0",
            "work.gesture_generation != 0"
        )
        val schedulerSource = readRepositoryFile(
            "app/src/main/cpp/ntk_fixed_depth_one_scheduler.cpp"
        )
        val orderedInput = functionBody(schedulerSource, "bool InputEnvelope::ordered() const")
        assertOrdered(
            orderedInput,
            "input_watermark > 0",
            "event_oldest_ns > 0",
            "main_ingress_oldest_ns > 0",
            "receipt_oldest_ns > 0",
            "mutation_oldest_ns > 0"
        )
        val renderLoop = functionBody(nativeSource, "void render_loop()")
        assertTrue(renderLoop.countOccurrences(
            "pending_frame_cause_ready_for_preparation()"
        ) >= 2)
        val terminalControl = functionBody(nativeSource, "bool apply_control(const InputSample& input)")
        assertOrdered(
            terminalControl,
            "fixed_scheduler_.reduceControl(",
            "monotonic_now_ns()",
            "fold_reduction(reduction)"
        )
        val terminalReduction = functionBody(
            schedulerSource,
            "ReductionResult FixedDepthOneScheduler::reduceControl("
        )
        assertOrdered(
            terminalReduction,
            "(void)applyScroll(terminalMove, maximumScroll, mutationNanos);",
            "const bool releaseCrossesEdge = input.action == kUp",
            "if (releaseCrossesEdge)",
            "reducer_.unassigned_input.recordMutation(mutationNanos);",
            "reducer_.gesture_state = ReducerGestureState::IDLE;",
            "result.frame_cause = true;",
            "result.terminal = true;"
        )
        assertTrue(terminalReduction.contains(
            "(void)applyScroll(input, maximumScroll, mutationNanos);"
        ))
        assertTrue(terminalReduction.contains("recordInput(input);"))
    }

    @Test
    fun swappyOpportunityAdmissionWaitsForRendererCallbackObservation() {
        val swappySource = readRepositoryFile(
            "app/src/main/cpp/swappy/games-frame-pacing/common/SwappyCommon.cpp"
        )
        val publish = functionBody(
            swappySource,
            "bool SwappyCommon::publishClaimedFixedOpportunityIfJoinOpenLocked("
        )
        assertOrdered(
            publish,
            "if (callbackDispatchRequired) *callbackDispatchRequired = false;",
            "mFixedPublishedOpportunity = opportunity;",
            "if (callbackDispatchRequired) *callbackDispatchRequired = true;"
        )

        val commit = functionBody(
            swappySource,
            "SwappyCommon::commitPreparedFixedFrameForNtk("
        )
        assertOrdered(
            commit,
            "!mFixedClaimedCandidate.has_value()",
            "!mFixedPublishedOpportunity.has_value()",
            "!fixedOpportunityRendererObservedExact(",
            "identityInvalid = true;",
            "mFixedPreparedFrame->commitInFlight = true;"
        )
        val observed = functionBody(
            swappySource,
            "bool fixedOpportunityRendererObservedExact("
        )
        assertOrdered(
            observed,
            "notice.wakeDispatchNanos >= notice.opportunityPublishNanos",
            "notice.rendererCallbackObservedNanos >= notice.wakeDispatchNanos",
            "notice.rendererCallbackObservedNanos > 0"
        )

        val telemetryCopy = functionBody(swappySource, "void copyPlanToTelemetry(")
        assertTrue(telemetryCopy.contains(
            "plan.phaseMissProven ? plan.latestSwapStartExclusiveNanos"
        ))
        assertTrue(telemetryCopy.contains(": plan.plannedCutoffNanos;"))
    }

    @Test
    fun compositorLatchIsRecordedAsOwnFrameEvidenceAndNeverRearmsSuccessorAdmission() {
        val nativeSource = readRepositoryFile("app/src/main/cpp/ntk_strip_renderer.cpp")
        val latch = functionBody(
            nativeSource,
            "void apply_compositor_latch_event("
        )
        assertOrdered(
            latch,
            "present_backend_.consumeCompositorLatch(",
            "SwappyGL_recordExternalLatchObservationForNtk(",
            "slot->latchTerminalState =",
            "complete_evidence_capsule_if_joined("
        )
        assertFalse(latch.contains("rearm_prepared_opportunity_after_latch"))
        assertFalse(nativeSource.contains("void rearm_prepared_opportunity_after_latch()"))
    }

    @Test
    fun nativePresentPumpPrioritizesCommitAndSeparatesLifecycleForceDrain() {
        val nativeSource = readRepositoryFile("app/src/main/cpp/ntk_strip_renderer.cpp")
        val pump = functionBody(
            nativeSource,
            "PresentPumpResult drain_present_events_on_render_thread("
        )
        assertTrue(nativeSource.contains("enum class PresentDrainMode"))
        assertTrue(nativeSource.contains("NORMAL = 0"))
        assertTrue(nativeSource.contains("FORCE_DRAIN = 1"))
        assertTrue(pump.contains("kNormalPresentCleanupBudget"))
        assertTrue(nativeSource.contains("kDeferredPresentCleanupCapacity"))
        assertTrue(pump.contains("present_cleanup_event_should_defer(event)"))
        assertOrdered(
            pump,
            "try_commit_priority_present_lane();",
            "return commit;",
            "drain_fixed_retirement_events_on_render_thread();",
            "try_commit_priority_present_lane();",
            "return commit;",
            "while (drained < budget)",
            "drain_fixed_retirement_events_on_render_thread();",
            "try_commit_priority_present_lane();"
        )

        val lifecycle = functionBody(nativeSource, "bool wait_present_join_for_lifecycle()")
        val detach = functionBody(nativeSource, "void detach_window()")
        assertTrue(lifecycle.contains("PresentDrainMode::FORCE_DRAIN"))
        assertTrue(detach.countOccurrences("PresentDrainMode::FORCE_DRAIN") >= 2)

        val renderLoop = functionBody(nativeSource, "void render_loop()")
        assertTrue(renderLoop.countOccurrences("PresentDrainMode::NORMAL") >= 2)
        assertTrue(renderLoop.contains("postApplyPresentCut"))
        assertTrue(renderLoop.contains("normal_present_event_wake_is_actionable()"))
        assertFalse(nativeSource.contains("void drain_present_events_on_render_thread()"))
    }

    @Test
    fun CommitPreemptsQueuedCompleteAfterExactLatch() {
        val nativeSource = readRepositoryFile("app/src/main/cpp/ntk_strip_renderer.cpp")
        val pump = functionBody(
            nativeSource,
            "PresentPumpResult drain_present_events_on_render_thread("
        )
        val eventLoop = pump.substring(pump.indexOf("while (drained < budget)"))
        assertOrdered(
            eventLoop,
            "COMPOSITOR_LATCHED:",
            "apply_compositor_latch_event(event);",
            "drain_fixed_retirement_events_on_render_thread();",
            "try_commit_priority_present_lane();",
            "return commit;"
        )
    }

    @Test
    fun CompleteDequeuedBeforeCommitDoesNotDestroyOverlap() {
        val nativeSource = readRepositoryFile("app/src/main/cpp/ntk_strip_renderer.cpp")
        val pump = functionBody(
            nativeSource,
            "PresentPumpResult drain_present_events_on_render_thread("
        )
        val eventLoop = pump.substring(pump.indexOf("while (drained < budget)"))
        assertOrdered(
            eventLoop,
            "present_backend_.drainEvent(&event)",
            "is_deferrable_present_cleanup_event(event.kind)",
            "defer_present_cleanup_event(event)",
            "drain_fixed_retirement_events_on_render_thread();",
            "try_commit_priority_present_lane();",
            "return commit;",
            "continue;"
        )

        val backendSource = readRepositoryFile(
            "app/src/main/cpp/present/SurfaceControlPresentBackend.cpp"
        )
        val complete = functionBody(
            backendSource,
            "bool SurfaceControlPresentBackend::consumeTransactionCompleted("
        )
        val commit = functionBody(
            backendSource,
            "bool SurfaceControlPresentBackend::consumeCompositorLatch("
        )
        assertTrue(complete.contains("if (record.commitEventConsumed)"))
        assertTrue(commit.contains("if (record.completeEventConsumed)"))
    }

    @Test
    fun CleanupCannotOvertakeOwnedClosedOpportunity() {
        val nativeSource = readRepositoryFile("app/src/main/cpp/ntk_strip_renderer.cpp")
        val defer = functionBody(
            nativeSource,
            "bool defer_present_cleanup_event("
        )
        assertTrue(defer.contains("present_cleanup_event_should_defer(event)"))
        assertTrue(defer.contains("blockedWorkGeneration"))
        assertTrue(defer.contains("waitForNewerSubmission"))

        val overlap = functionBody(
            nativeSource,
            "bool complete_cleanup_requires_successor_submission("
        )
        assertTrue(overlap.contains("TRANSACTION_COMPLETED"))
        assertTrue(overlap.contains("!slot->capsule.prepared.terminal"))
        assertTrue(overlap.contains("!slot->capsule.prepared.stage_candidate"))
        assertTrue(overlap.contains("last_successfully_submitted_work_generation_ >"))

        val actionable = functionBody(
            nativeSource,
            "bool deferred_present_cleanup_front_is_actionable() const"
        )
        assertTrue(actionable.contains("record.blockedWorkGeneration"))
        assertTrue(actionable.contains("prepared_frame_work_->work_generation !="))
        assertTrue(actionable.contains("record.waitForNewerSubmission"))
        assertTrue(actionable.contains("last_successfully_submitted_work_generation_ >"))

        val pump = functionBody(
            nativeSource,
            "PresentPumpResult drain_present_events_on_render_thread("
        )
        assertOrdered(
            pump,
            "try_commit_priority_present_lane();",
            "pop_deferred_present_cleanup_event(&event, forceDrain)",
            "is_deferrable_present_cleanup_event(event.kind)",
            "defer_present_cleanup_event(event)",
            "switch (event.kind)"
        )
        assertTrue(nativeSource.contains("kDeferredPresentCleanupCapacity = 64"))
        assertTrue(nativeSource.contains("struct DeferredPresentCleanupRecord"))
    }

    @Test
    fun successorPreApplyRequiresExactPriorLatchClaim() {
        val nativeSource = readRepositoryFile("app/src/main/cpp/ntk_strip_renderer.cpp")
        val commit = functionBody(
            nativeSource,
            "PreparedCommitResult try_commit_prepared_frame("
        )
        assertTrue(commit.contains("swappy::fixedExternalClaimExact(claim, claim)"))
        assertTrue(commit.contains("swappy::fixedLatchObservationValid("))
        assertTrue(commit.contains("priorLatchMatchesPrevious"))
        assertTrue(commit.contains("priorLatchMatchesAdmission"))
        assertTrue(commit.contains("claim.priorLatchGateRequired == 0"))
        assertTrue(commit.contains("claim.priorLatchGateUsed == 0"))
        assertTrue(commit.contains("claim.priorLatchGateRequired == 1"))
        assertTrue(commit.contains("claim.priorLatchGateUsed == 1"))
        assertTrue(commit.contains("claim.priorCommitProofPendingAtClaim == 0"))
        assertFalse(commit.contains("noPredecessorLatchGate"))
        assertFalse(commit.contains("optionalPredecessorLatchExact"))
    }

    @Test
    fun externalCallbackWakeAdvancesRendererGenerationUnderTheWaitMutex() {
        val nativeSource = readRepositoryFile("app/src/main/cpp/ntk_strip_renderer.cpp")
        val signal = functionBody(nativeSource, "void signal_external_render_event() noexcept")
        val presentWake = functionBody(
            nativeSource,
            "static void wake_for_present_event(void* context) noexcept"
        )
        val swappyWake = functionBody(
            nativeSource,
            "static void swappy_fixed_state_changed("
        )
        val retirementWake = functionBody(
            nativeSource,
            "static void swappy_fixed_retirement_completed("
        )

        assertOrdered(
            signal,
            "std::lock_guard<std::mutex> lock(mutex_);",
            "++command_generation_;",
            "render_condition_.notify_one();"
        )
        assertTrue(presentWake.contains("renderer->signal_external_render_event();"))
        assertFalse(presentWake.contains("render_condition_.notify_one();"))
        assertTrue(swappyWake.contains("renderer->signal_external_render_event();"))
        assertFalse(swappyWake.contains("renderer->render_condition_.notify_one();"))
        assertTrue(retirementWake.contains("renderer->signal_external_render_event();"))
        assertFalse(retirementWake.contains("renderer->render_condition_.notify_one();"))
    }

    @Test
    fun pipelineAuthorityPortsSeparateDetachedPreparationFromSurfacePresentation() {
        val controller = readRepositoryFile(
            "app/src/main/java/ml/melun/mangaview/reader/NtkInlineReaderController.kt"
        )
        val stage = functionBody(controller, "private fun stageContinuousStrip(")
        val detached = functionBody(
            controller,
            "private fun createDetachedPreparationPort("
        )
        assertTrue(detached.contains("engine.openDetachedPreparation("))
        assertTrue(detached.contains("engine.installDetachedPrepared("))
        assertTrue(detached.contains("engine.closePreparationAdmissions("))
        assertFalse(detached.contains("stripRenderViewTarget"))
        assertTrue(stage.contains("override fun installSurfacePrepared("))
        assertTrue(stage.contains("target.installSurfacePrepared("))
        assertTrue(stage.contains("override fun adoptDetachedPreparation("))
        assertTrue(stage.contains("target.adoptDetachedPreparationToPublishedSurface("))
        assertFalse(stage.contains("openDetachedPreparation("))
        assertFalse(stage.contains("val pipelineTarget = stripRenderViewTarget"))
    }

    @Test
    fun physicalDeliveryWatermarkPublishesOnlyAfterTheFullPresentJoin() {
        val nativeSource = readRepositoryFile("app/src/main/cpp/ntk_strip_renderer.cpp")
        val compositorLatch = functionBody(
            nativeSource,
            "void apply_compositor_latch_event("
        )
        assertFalse(compositorLatch.contains("latest_delivered_latched_input_event_ns_"))

        val feedbackDelivery = functionBody(nativeSource, "bool drain_one_frame_feedback(")
        assertOrdered(
            feedbackDelivery,
            "dispatch_frame_feedback(env, evidence, frame);",
            "frame_feedback_delivered_sequence_.store(expected",
            "latest_delivered_latched_input_event_ns_.compare_exchange_weak("
        )
    }

    @Test
    fun productionPresentationThreadsUseUrgentDisplayPriorityWithSafeFallback() {
        val renderer = readRepositoryFile("app/src/main/cpp/ntk_strip_renderer.cpp")
        assertTrue(renderer.contains("constexpr int kUrgentDisplayNice = -8;"))
        assertTrue(renderer.contains("constexpr int kDisplayNiceFallback = -4;"))
        val rendererPriority = functionBody(
            renderer,
            "void request_urgent_display_priority(const char* role) noexcept"
        )
        assertOrdered(
            rendererPriority,
            "getpriority(PRIO_PROCESS, 0)",
            "before_nice <= kUrgentDisplayNice",
            "setpriority(PRIO_PROCESS, 0, kUrgentDisplayNice)",
            "setpriority(PRIO_PROCESS, 0, kDisplayNiceFallback)",
            "getpriority(PRIO_PROCESS, 0)",
            "effective_nice <= kUrgentDisplayNice"
        )
        assertFalse(rendererPriority.contains("engine_failed_"))
        assertTrue(functionBody(renderer, "void render_loop()").contains(
            "request_urgent_display_priority(\"render-owner\")"
        ))

        val choreographer = readRepositoryFile(
            "app/src/main/cpp/swappy/games-frame-pacing/common/ChoreographerThread.cpp"
        )
        val ndkLooper = functionBody(
            choreographer,
            "void NDKChoreographerThread::looperThread()"
        )
        assertTrue(ndkLooper.contains(
            "requestUrgentDisplayPriority(\"ndk-choreographer\")"
        ))
        val fallbackLooper = functionBody(
            choreographer,
            "void NoChoreographerThread::looperThread()"
        )
        assertTrue(fallbackLooper.contains("setpriority(PRIO_PROCESS, 0, -4)"))
        assertFalse(fallbackLooper.contains("requestUrgentDisplayPriority"))

        val filter = readRepositoryFile(
            "app/src/main/cpp/swappy/games-frame-pacing/common/ChoreographerFilter.cpp"
        )
        val filterWorker = functionBody(
            filter,
            "void ChoreographerFilter::threadMain(bool useAffinity, int32_t thread)"
        )
        assertTrue(filterWorker.contains("setpriority(PRIO_PROCESS, 0, -4)"))
        assertFalse(filterWorker.contains("requestUrgentDisplayPriority"))

        val rollingRenderer = readRepositoryFile(
            "app/src/main/cpp/ntk_rolling_surface_renderer.cpp"
        )
        assertTrue(rollingRenderer.contains("constexpr int kUrgentDisplayNice = -8;"))
        assertTrue(rollingRenderer.contains("constexpr int kDisplayNiceFallback = -4;"))
        val rollingPriority = functionBody(
            rollingRenderer,
            "void requestUrgentDisplayPriority() noexcept"
        )
        assertOrdered(
            rollingPriority,
            "getpriority(PRIO_PROCESS, 0)",
            "setpriority(PRIO_PROCESS, 0, kUrgentDisplayNice)",
            "setpriority(PRIO_PROCESS, 0, kDisplayNiceFallback)",
            "getpriority(PRIO_PROCESS, 0)"
        )
        assertTrue(functionBody(rollingRenderer, "void run() noexcept").contains(
            "requestUrgentDisplayPriority();"
        ))
        assertTrue(rollingRenderer.contains(
            "constexpr int kPausedForwardPrewarmPages = 16;"
        ))

        val rollingView = readRepositoryFile(
            "app/src/main/java/ml/melun/mangaview/reader/ReaderSurfaceView.kt"
        )
        assertTrue(functionBody(rollingView, "private fun startRenderThreadLocked()").contains(
            "Process.THREAD_PRIORITY_URGENT_DISPLAY"
        ))
    }

    @Test
    fun rollingTexturePoolRetainsOneCompleteThreeTileSourcePage() {
        val rollingRenderer = readRepositoryFile(
            "app/src/main/cpp/ntk_rolling_surface_renderer.cpp"
        )
        assertTrue(rollingRenderer.contains(
            "constexpr std::uint64_t kMaxPooledTextureBytes = 24ULL * 1024ULL * 1024ULL;"
        ))
        assertTrue(rollingRenderer.contains(
            "constexpr std::size_t kMaxPooledTextureCount = 12;"
        ))
    }

    @Test
    fun releaseCompletionThrowDoesNotPoisonOrStrandProof() {
        val protocol = NtkEngineProtocolCoordinator()
        protocol.withProtocolLock {
            protocol.setPhaseLocked(ProtocolPhase.RETIRED_DISPATCHABLE)
        }
        val registrations = linkedMapOf<Int, NtkReleaseRegistration<Unit, String>>()
        val completionCount = AtomicInteger(0)
        val registration = NtkReleaseRegistration(Unit) { ack: String ->
            assertEquals("terminal", ack)
            completionCount.incrementAndGet()
            throw DeliberateCompletionFailure()
        }.apply {
            stagedAck = "terminal"
            nativeDispatchable = true
        }
        protocol.withProtocolLock {
            registrations[1] = registration
            scheduleNtkReleaseCompletionLocked(protocol, registrations, 1, registration)
            protocol.awaitChangedUninterruptiblyLocked { registrations.isEmpty() }
        }
        assertEquals(1, completionCount.get())
        assertTrue(registration.delivered)
        assertFalse(registration.running)
        assertEquals(ProtocolPhase.RETIRED_DISPATCHABLE, protocol.phaseSnapshot())
        assertEquals(
            ProtocolPhase.RETIRED_DISPATCHABLE,
            protocol.beginCloseAndAwaitQuiescence(setOf(ProtocolPhase.RETIRED_DISPATCHABLE))
        )
        protocol.withProtocolLock { protocol.setPhaseLocked(ProtocolPhase.CLOSED) }
        assertEquals(ProtocolPhase.CLOSED, protocol.phaseSnapshot())
    }

    @Test
    fun queuedReleaseCompletionCannotCrossRetiredBlockedBoundary() {
        val protocol = NtkEngineProtocolCoordinator()
        val serialExecutorEntered = CountDownLatch(1)
        val releaseSerialExecutor = CountDownLatch(1)
        val completionCount = AtomicInteger(0)
        val registrations = linkedMapOf<Int, NtkReleaseRegistration<Unit, String>>()
        val registration = NtkReleaseRegistration(Unit) { ack: String ->
            assertEquals("terminal", ack)
            completionCount.incrementAndGet()
        }.apply {
            stagedAck = "terminal"
            nativeDispatchable = true
        }

        NtkReleaseCompletion.dispatch {
            serialExecutorEntered.countDown()
            awaitGuard(releaseSerialExecutor, "release completion serial barrier")
        }
        try {
            awaitGuard(serialExecutorEntered, "release completion serial admission")
            protocol.withProtocolLock {
                registrations[1] = registration
                scheduleNtkReleaseCompletionLocked(protocol, registrations, 1, registration)
                protocol.setPhaseLocked(ProtocolPhase.RETIRED_BLOCKED)
            }
            releaseSerialExecutor.countDown()
            protocol.withProtocolLock {
                protocol.awaitChangedUninterruptiblyLocked { !registration.scheduled }
                assertEquals(registration, registrations[1])
                assertFalse(registration.delivered)
                assertEquals(0, completionCount.get())
                protocol.setPhaseLocked(ProtocolPhase.RETIRED_DISPATCHABLE)
                scheduleNtkReleaseCompletionLocked(protocol, registrations, 1, registration)
                protocol.awaitChangedUninterruptiblyLocked { registrations.isEmpty() }
            }
            assertEquals(1, completionCount.get())
            assertTrue(registration.delivered)
        } finally {
            releaseSerialExecutor.countDown()
        }
    }

    @Test
    fun liveCloseDrainsQueuedReleaseBeforeDestroy() {
        val protocol = NtkEngineProtocolCoordinator()
        val serialExecutorEntered = CountDownLatch(1)
        val releaseSerialExecutor = CountDownLatch(1)
        val closeEnteredDrain = CountDownLatch(1)
        val destroyCount = AtomicInteger(0)
        val events = java.util.Collections.synchronizedList(mutableListOf<String>())
        val registrations = linkedMapOf<Int, NtkReleaseRegistration<Unit, String>>()
        val registration = NtkReleaseRegistration(Unit) { ack: String ->
            assertEquals("terminal", ack)
            events += "completion"
        }.apply {
            stagedAck = "terminal"
            nativeDispatchable = true
        }
        val workers = Executors.newSingleThreadExecutor()

        NtkReleaseCompletion.dispatch {
            serialExecutorEntered.countDown()
            awaitGuard(releaseSerialExecutor, "live close serial barrier")
        }
        try {
            awaitGuard(serialExecutorEntered, "live close serial admission")
            protocol.withProtocolLock {
                registrations[1] = registration
                scheduleNtkReleaseCompletionLocked(protocol, registrations, 1, registration)
            }
            val previous = protocol.beginCloseAndAwaitQuiescence(
                setOf(ProtocolPhase.LIVE_DETACHED)
            )
            assertEquals(ProtocolPhase.LIVE_DETACHED, previous)
            protocol.withProtocolLock {
                scheduleNtkReleaseCompletionLocked(protocol, registrations, 1, registration)
            }
            val close = workers.submit<Boolean> {
                protocol.withProtocolLock {
                    closeEnteredDrain.countDown()
                    protocol.awaitChangedUninterruptiblyLocked {
                        registrations.isEmpty() ||
                            protocol.phaseLocked() == ProtocolPhase.FAILED
                    }
                }
                destroyCount.incrementAndGet()
                events += "destroy"
                protocol.withProtocolLock { protocol.setPhaseLocked(ProtocolPhase.CLOSED) }
                true
            }
            awaitGuard(closeEnteredDrain, "live close release drain")
            assertEquals(0, destroyCount.get())
            assertFalse(close.isDone)
            val newReleaseNativeCalls = AtomicInteger(0)
            assertFalse(protocol.runOperation(
                operation = "release-after-close-admission",
                admission = NtkProtocolAdmission.RELEASE,
                rejected = false,
                prepareLocked = { NtkPreparedOperation(Unit) },
                nativeCall = NtkProtocolNativeAdapter {
                    newReleaseNativeCalls.incrementAndGet()
                    true
                },
                completeLocked = { _, result -> result.getOrDefault(false) }
            ))
            assertEquals(0, newReleaseNativeCalls.get())
            releaseSerialExecutor.countDown()
            assertTrue(getGuard(close, "live close after release completion"))
            assertEquals(1, destroyCount.get())
            assertEquals(listOf("completion", "destroy"), events.toList())
            assertTrue(registration.delivered)
            assertEquals(ProtocolPhase.CLOSED, protocol.phaseSnapshot())
        } finally {
            releaseSerialExecutor.countDown()
            workers.shutdownNow()
        }
    }

    @Test
    fun closeAdmissionDrainIsUninterruptibleAndCannotReopenLiveOwner() {
        val nativeReturned = CountDownLatch(1)
        val allowBookkeeping = CountDownLatch(1)
        val closePublished = CountDownLatch(1)
        val closeThread = AtomicReference<Thread>()
        val interruptedAfterDrain = AtomicBoolean(false)
        val protocol = NtkEngineProtocolCoordinator(object : NtkProtocolDeterministicHooks {
            override fun afterNativeReturnBeforeBookkeeping(operation: String) {
                if (operation == "close-interrupt-admitted") {
                    nativeReturned.countDown()
                    awaitGuard(allowBookkeeping, "interrupted close bookkeeping")
                }
            }
        })
        val workers = Executors.newFixedThreadPool(3)
        try {
            val admitted = workers.submit<Boolean> {
                protocol.runOperation(
                    operation = "close-interrupt-admitted",
                    admission = NtkProtocolAdmission.RELEASE,
                    rejected = false,
                    prepareLocked = { NtkPreparedOperation(Unit) },
                    nativeCall = NtkProtocolNativeAdapter { true },
                    completeLocked = { _, result -> result.getOrDefault(false) }
                )
            }
            awaitGuard(nativeReturned, "admitted operation native return")
            val phaseObserver = workers.submit {
                protocol.withProtocolLock {
                    protocol.awaitChangedUninterruptiblyLocked {
                        protocol.phaseLocked() == ProtocolPhase.CLOSING
                    }
                }
                closePublished.countDown()
            }
            val close = workers.submit<ProtocolPhase?> {
                closeThread.set(Thread.currentThread())
                val previous = protocol.beginCloseAndAwaitQuiescence(
                    setOf(ProtocolPhase.LIVE_DETACHED)
                )
                interruptedAfterDrain.set(Thread.currentThread().isInterrupted)
                previous
            }
            awaitGuard(closePublished, "uninterruptible CLOSING publication")
            checkNotNull(closeThread.get()).interrupt()
            assertEquals(ProtocolPhase.CLOSING, protocol.phaseSnapshot())
            assertFalse(close.isDone)
            allowBookkeeping.countDown()
            assertTrue(getGuard(admitted, "admitted bookkeeping after close interrupt"))
            assertEquals(ProtocolPhase.LIVE_DETACHED, getGuard(close, "interrupted close drain"))
            getGuard(phaseObserver, "close phase observer")
            assertTrue(interruptedAfterDrain.get())
            assertEquals(ProtocolPhase.CLOSING, protocol.phaseSnapshot())
            protocol.withProtocolLock { protocol.setPhaseLocked(ProtocolPhase.CLOSED) }
        } finally {
            allowBookkeeping.countDown()
            workers.shutdownNow()
        }
    }

    @Test
    fun releaseCompletionReentrantCloseUsesSeparateTeardownLane() {
        val protocol = NtkEngineProtocolCoordinator()
        val registrations = linkedMapOf<Int, NtkReleaseRegistration<Unit, String>>()
        val teardown = Executors.newSingleThreadExecutor()
        val closeReturnedFromCompletion = CountDownLatch(1)
        val teardownFuture = AtomicReference<Future<Boolean>>()
        val events = java.util.Collections.synchronizedList(mutableListOf<String>())
        val registration = NtkReleaseRegistration(Unit) { ack: String ->
            assertEquals("terminal", ack)
            events += "completion-enter"
            val previous = protocol.beginCloseAndAwaitQuiescence(
                setOf(ProtocolPhase.LIVE_DETACHED)
            )
            assertEquals(ProtocolPhase.LIVE_DETACHED, previous)
            teardownFuture.set(teardown.submit<Boolean> {
                protocol.withProtocolLock {
                    protocol.awaitChangedUninterruptiblyLocked { registrations.isEmpty() }
                }
                events += "destroy"
                protocol.withProtocolLock { protocol.setPhaseLocked(ProtocolPhase.CLOSED) }
                true
            })
            events += "completion-close-return"
            closeReturnedFromCompletion.countDown()
        }.apply {
            stagedAck = "terminal"
            nativeDispatchable = true
        }
        try {
            protocol.withProtocolLock {
                registrations[1] = registration
                scheduleNtkReleaseCompletionLocked(protocol, registrations, 1, registration)
            }
            awaitGuard(closeReturnedFromCompletion, "reentrant close handoff")
            assertTrue(getGuard(checkNotNull(teardownFuture.get()), "reentrant teardown"))
            assertEquals(
                listOf("completion-enter", "completion-close-return", "destroy"),
                events.toList()
            )
            assertTrue(registration.delivered)
            assertEquals(ProtocolPhase.CLOSED, protocol.phaseSnapshot())
        } finally {
            teardown.shutdownNow()
        }
    }

    @Test
    fun successorFactoryFailurePublishesFailedSlotBeforeOldUnblock() {
        val oldProtocol = NtkEngineProtocolCoordinator()
        oldProtocol.withProtocolLock {
            oldProtocol.setPhaseLocked(ProtocolPhase.RETIRED_BLOCKED)
        }
        val slot = AtomicReference<TestSuccessorSlot>(TestSuccessorSlot.Unpublished)
        val callbackObservedSlot = AtomicReference<TestSuccessorSlot>()
        val callbackRan = CountDownLatch(1)

        try {
            val successorSlot = try {
                throw DeliberateSuccessorFailure()
            } catch (failure: Throwable) {
                TestSuccessorSlot.Failed(8L, failure)
            }
            slot.set(successorSlot)
        } finally {
            oldProtocol.withProtocolLock {
                oldProtocol.setPhaseLocked(ProtocolPhase.RETIRED_DISPATCHABLE)
            }
            NtkReleaseCompletion.dispatch {
                callbackObservedSlot.set(slot.get())
                callbackRan.countDown()
            }
        }

        awaitGuard(callbackRan, "old-generation completion after successor failure")
        val failed = slot.get() as? TestSuccessorSlot.Failed
        assertNotNull(failed)
        assertEquals(8L, failed?.generation)
        assertTrue(failed?.cause is DeliberateSuccessorFailure)
        assertEquals(slot.get(), callbackObservedSlot.get())
        assertEquals(ProtocolPhase.RETIRED_DISPATCHABLE, oldProtocol.phaseSnapshot())

        // This is deliberately coupled to the production handoff, so the executable model above
        // cannot pass while SurfaceView publishes or unblocks in a different order.
        val surfaceSource = readRepositoryFile(
            "app/src/main/java/ml/melun/mangaview/reader/NtkStripSurfaceView.kt"
        )
        val handoffStart = surfaceSource.indexOf("val successorSlot = if (viewClosing)")
        assertTrue("production successor handoff is missing", handoffStart >= 0)
        val handoff = surfaceSource.substring(handoffStart)
        assertOrdered(
            handoff,
            "EngineSlot.Failed(successorGeneration, failure)",
            "AppDispatchers.runOnMain",
            "engineSlot.set(successorSlot)",
            "AppDispatchers.submitNtkSurfaceLifecycleStrict",
            "detachedEngine.finishContextLossHandoff()"
        )
    }

    private fun liveProtocol(
        hooks: NtkProtocolDeterministicHooks = NtkProtocolDeterministicHooks.None
    ): NtkEngineProtocolCoordinator {
        val protocol = NtkEngineProtocolCoordinator(hooks)
        val operation = checkNotNull(protocol.beginSurfaceAttach(SURFACE_KEY))
        assertTrue(protocol.completeSurfaceAttachReady(operation))
        assertTrue(protocol.publishSurface(SURFACE_KEY))
        return protocol
    }

    private fun <T> beginDetach(
        protocol: NtkEngineProtocolCoordinator,
        onAdmissionClosedLocked: () -> Unit,
        prepareQuiescentLocked: () -> T
    ): NtkPreparedOperation<T>? {
        val ticket = protocol.closeSurfaceAdmission(
            SURFACE_KEY,
            onAdmissionClosedLocked
        ) ?: return null
        return protocol.awaitDetachQuiescenceAndPrepare(ticket, prepareQuiescentLocked)
    }

    private data class TestAuthorityKey(
        val engineGeneration: Long,
        val authorityGeneration: Long,
        val authority: Long
    )

    private sealed class TestSuccessorSlot {
        object Unpublished : TestSuccessorSlot()
        data class Failed(val generation: Long, val cause: Throwable) : TestSuccessorSlot()
    }

    private class DeliberateCompletionFailure : RuntimeException()
    private class DeliberateSuccessorFailure : RuntimeException()

    private fun NtkNativeAuthorityToken.keyForTest() = TestAuthorityKey(
        engineGeneration,
        authorityGeneration,
        authority
    )

    private fun goldenTokenOne() = NtkNativeAuthorityToken(
        engineGeneration = 7L,
        authorityGeneration = 3L,
        authority = 101L,
        manifestRevision = 11L,
        manifestDigest = "00".repeat(32),
        geometryDigest = "11".repeat(32)
    )

    private fun goldenTokenTwo() = NtkNativeAuthorityToken(
        engineGeneration = 7L,
        authorityGeneration = 4L,
        authority = 202L,
        manifestRevision = 12L,
        manifestDigest = "aa".repeat(32),
        geometryDigest = "ff".repeat(32)
    )

    private fun awaitGuard(latch: CountDownLatch, description: String) {
        assertTrue("Timed out waiting for $description",
            latch.await(HANG_GUARD_SECONDS, TimeUnit.SECONDS))
    }

    private fun <T> getGuard(future: Future<T>, description: String): T = try {
        future.get(HANG_GUARD_SECONDS, TimeUnit.SECONDS)
    } catch (failure: Throwable) {
        throw AssertionError("Timed out or failed while waiting for $description", failure)
    }

    private fun readRepositoryFile(relativePath: String): String {
        var cursor: Path? = Paths.get("").toAbsolutePath().normalize()
        while (cursor != null) {
            val candidate = cursor.resolve(relativePath).normalize()
            if (Files.isRegularFile(candidate)) {
                return String(Files.readAllBytes(candidate), StandardCharsets.UTF_8)
            }
            cursor = cursor.parent
        }
        throw AssertionError("Unable to locate $relativePath")
    }

    private fun assertOrdered(source: String, vararg fragments: String) {
        var cursor = -1
        fragments.forEach { fragment ->
            val next = source.indexOf(fragment, cursor + 1)
            assertTrue("Missing or out-of-order fragment: $fragment", next > cursor)
            cursor = next
        }
    }

    private fun String.countOccurrences(fragment: String): Int {
        var count = 0
        var cursor = 0
        while (cursor <= length - fragment.length) {
            val next = indexOf(fragment, cursor)
            if (next < 0) break
            count++
            cursor = next + fragment.length
        }
        return count
    }

    private fun functionBody(source: String, marker: String): String {
        val markerIndex = source.indexOf(marker)
        assertTrue("Missing source marker: $marker", markerIndex >= 0)
        val open = source.indexOf('{', markerIndex + marker.length)
        assertTrue("Missing function body for: $marker", open >= 0)
        var depth = 0
        for (index in open until source.length) {
            when (source[index]) {
                '{' -> depth++
                '}' -> if (--depth == 0) return source.substring(open + 1, index)
            }
        }
        throw AssertionError("Unterminated function body for $marker")
    }

    companion object {
        private const val HANG_GUARD_SECONDS = 10L
        private val SURFACE_KEY = NtkSurfaceAttachKey(
            engineGeneration = 7L,
            attachGeneration = 1L,
            surfaceEpoch = 1L
        )
    }
}
