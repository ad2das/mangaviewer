package ml.melun.mangaview.reader

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NtkNativeReleaseHistoryArchitectureTest {
    private val nativeSource by lazy {
        File("src/main/cpp/ntk_strip_renderer.cpp").readText()
    }

    private fun body(start: String, end: String): String {
        val startIndex = nativeSource.indexOf(start)
        val endIndex = nativeSource.indexOf(end, startIndex + start.length)
        assertTrue("missing start seam: $start", startIndex >= 0)
        assertTrue("missing end seam: $end", endIndex > startIndex)
        return nativeSource.substring(startIndex, endIndex)
    }

    @Test
    fun physicalCompletionFreezesScalarProofAndDropsEpisodeSizedStorage() {
        assertFalse(nativeSource.contains("released_authorities_"))
        assertTrue(nativeSource.contains("int captured_resource_count = 0;"))
        assertTrue(nativeSource.contains("int released_resource_count = 0;"))

        val compaction = body(
            "void compact_release_tracker_storage(",
            "enum class NativeHandleMode",
        )
        assertTrue(compaction.contains("tracker.captured_resource_count ="))
        assertTrue(compaction.contains("tracker.released_resource_count ="))
        assertTrue(compaction.contains("discarded_storage.captured_resources.swap"))
        assertTrue(compaction.contains("discarded_storage.released_resources.swap"))
        assertTrue(compaction.contains("discarded_storage.scene.swap"))
        assertTrue(compaction.contains("discarded_storage.queued_uploads.swap"))
        assertTrue(compaction.contains("discarded_storage.ready_tiles.swap"))
        assertTrue(compaction.contains("discarded_storage.resource_deletes.swap"))
        assertTrue(compaction.contains("discarded_storage.preallocated_textures.swap"))
        assertTrue(compaction.contains("discarded_storage.prepared_bank.swap"))
        assertTrue(compaction.contains("discarded_storage.slot_specs.swap"))
        assertTrue(compaction.contains("discarded_storage.ordinal_keys.swap"))
        assertTrue(compaction.contains("discarded_storage.key_ordinals.swap"))
        assertTrue(compaction.contains("discarded_storage.resident_intervals.swap"))

        val process = body(
            "    bool process_release_tracker_once(JNIEnv* env)",
            "    void upload_loop(ResourceWorkerLaunch launch)",
        )
        val compactIndex =
            process.indexOf("compact_release_tracker_storage(*tracker, *discarded_storage)")
        val completeIndex = process.indexOf("tracker->physical_complete = true")
        assertTrue(compactIndex >= 0 && completeIndex > compactIndex)

        val ack = body(
            "    void enqueue_release_ack_if_ready(",
            "    bool process_release_tracker_once(JNIEnv* env)",
        )
        assertTrue(ack.contains("ack->captured_resource_count = tracker->captured_resource_count"))
        assertTrue(ack.contains("ack->released_resource_count = tracker->released_resource_count"))
        assertFalse(ack.contains("captured_resources.size()"))
        assertFalse(ack.contains("released_resources.size()"))

        val contextLoss = body(
            "    std::unique_ptr<RetiredBackendProofStore> retire_context_lost_on_detach(",
            "private:",
        )
        assertTrue(contextLoss.contains("tracker.captured_resource_count"))
        assertTrue(contextLoss.contains("tracker.released_resource_count"))
        assertFalse(contextLoss.contains("tracker.captured_resources.size()"))
        assertFalse(contextLoss.contains("tracker.released_resources.size()"))
    }

    @Test
    fun terminalHistoryUsesExactActiveGateAndDeferredSafeBarriers() {
        val release = body(
            "    bool release_authority(",
            "    std::array<std::int64_t, 21> debug_lifecycle_counters()",
        )
        assertTrue(release.contains("releaseClaimAllowed("))
        assertTrue(release.contains("active_authority_matches(key)"))
        assertTrue(release.contains("successor_closed_authority_matches_locked(key)"))
        assertFalse(release.contains("authority_ != authority"))

        val frame = body(
            "    void dispatch_frame_feedback(",
            "    FrameFeedback materialize_frame_feedback(",
        )
        val reliable = body(
            "    void dispatch_reliable_feedback(",
            "    void dispatch_authority_released(",
        )
        assertTrue(frame.contains("!callback_authority_open_locked(key)"))
        assertTrue(reliable.contains("!callback_authority_open_locked(callback_key)"))

        val purge = body(
            "    std::size_t purge_released_trackers_locked()",
            "    void block_input_and_presentation()",
        )
        assertTrue(purge.contains("release.physical_complete && release.ack_enqueued"))
        assertTrue(purge.contains("release_history::reclaimable("))
        assertTrue(purge.contains("resource_worker_owns("))
        assertTrue(nativeSource.contains("authority_closed_for_successor_"))
        assertTrue(nativeSource.contains("close_current_authority_for_successor_locked();"))

        val detach = body("    bool detach(", "    bool has_pending_context_loss(")
        assertTrue(detach.contains("if (reusable) purge_released_trackers_locked();"))
        val contextLoss = body(
            "    std::unique_ptr<RetiredBackendProofStore> retire_context_lost_on_detach(",
            "private:",
        )
        assertFalse(contextLoss.contains("purge_released_trackers_locked"))

        // One reusable-detach call plus both successful successor commit bodies.
        assertEquals(3, Regex.escape("purge_released_trackers_locked();").toRegex()
            .findAll(nativeSource).count())

        val terminalizer = body(
            "    void dispatch_authority_released(",
            "    void feedback_loop()",
        )
        assertTrue(terminalizer.contains("lifecycle = AuthorityLifecycle::RELEASED"))
        assertFalse(terminalizer.contains("release_trackers_.erase"))
    }

    @Test
    fun productionPolicyOwnsTheNativeHundredAuthorityChurnTest() {
        val header = File("src/main/cpp/ReleaseTrackerHistoryContract.h").readText()
        val nativeTest = File(
            "src/main/cpp/tests/ReleaseTrackerHistoryContractTest.cpp",
        ).readText()
        val cmake = File("src/main/cpp/CMakeLists.txt").readText()
        val gradle = File("build.gradle").readText()

        assertTrue(nativeSource.contains("#include \"ReleaseTrackerHistoryContract.h\""))
        assertTrue(header.contains("releaseClaimAllowed("))
        assertTrue(header.contains("callbackAllowed("))
        assertTrue(header.contains("reclaimable("))
        assertTrue(header.contains("contextLossSelection("))
        assertTrue(header.contains("normalChurnBound("))

        assertTrue(nativeTest.contains("generation <= 100"))
        assertTrue(nativeTest.contains("normalChurnBound("))
        assertTrue(nativeTest.contains("duplicate released claim was accepted"))
        assertTrue(nativeTest.contains("late released callback was accepted"))
        assertTrue(nativeTest.contains("out-of-order old unclaimed release was rejected"))
        assertTrue(nativeTest.contains("live resource-worker owner was purged"))
        assertTrue(nativeTest.contains("selected released context-loss proof was discarded"))
        assertTrue(cmake.contains("add_executable(ReleaseTrackerHistoryContractTest"))
        assertTrue(cmake.contains("add_dependencies(ntk_strip_renderer ReleaseTrackerHistoryContractTest)"))
        assertTrue(gradle.contains("\"ReleaseTrackerHistoryContractTest\""))
    }
}
