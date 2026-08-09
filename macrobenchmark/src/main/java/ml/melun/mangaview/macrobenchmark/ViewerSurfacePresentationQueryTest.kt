package ml.melun.mangaview.macrobenchmark

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ViewerSurfacePresentationQueryTest {
    @Test
    fun queryMapsChangedSubmissionToTheSameSurfaceFramePresentFence() {
        val sql = ViewerSurfacePresentationQuery.build("ml.melun.mangaview")

        assertTrue(sql.contains("surface_presents.frame_number = mapped_queues.frame_number"))
        assertTrue(sql.contains("surface_presents.layer_name = mapped_queues.layer_name"))
        assertTrue(sql.contains(") AS present_candidate_rank"))
        assertTrue(sql.contains("WHERE present_candidate_rank = 1"))
        assertTrue(sql.contains("surface_queues.queue_ts >= physical_submissions.submit_ts"))
        assertTrue(sql.contains("PARTITION BY input_segment_start_ts"))
        assertTrue(sql.contains("PARTITION BY physical_submissions.submission_id"))
        assertTrue(sql.contains("start_ts - previous_bound_end_ts <= refresh.period_ns"))
        assertTrue(sql.contains("physical_submissions.next_submit_ts IS NOT NULL"))
        assertTrue(sql.contains("later_present.present_ts > mapped_queues.queue_ts"))
        assertTrue(sql.contains("intermediate_submission.submit_ts >"))
        assertTrue(sql.contains("candidate_intervals.previous_submit_ts"))
        assertTrue(sql.contains("intermediate_present.present_ts >"))
        assertTrue(sql.contains("ACTION_UP%"))
        assertTrue(sql.contains("ACTION_CANCEL%"))
        assertTrue(sql.contains("MAX(target_input_events.event_ts)"))
        assertTrue(sql.contains("FROM target_input_events AS input_boundary"))
        assertFalse(sql.contains("PARTITION BY bound_id ORDER BY present_ts"))
    }

    @Test
    fun queryUsesOnlyActualSystemIntervalsAboveTwentyFiveMillisecondsAsSlow() {
        val sql = ViewerSurfacePresentationQuery.build("ml.melun.mangaview")

        assertTrue(sql.contains("FROM frame_slice"))
        assertTrue(sql.contains("name = 'PresentFenceSignaled'"))
        assertTrue(sql.contains("WHERE gap_ns > 25000000"))
        assertFalse(sql.contains("period_ns * 1.5"))
    }

    @Test
    fun parserAcceptsCoalescedAttemptsAndMultipleFramesInOneMotionBound() {
        assertTrue(
            SurfacePresentationParserPolicy.isValid(
                SurfacePresentationParseCounts(
                    activeBounds = 163,
                    attemptedBounds = 163,
                    submissionAttempts = 166,
                    coalescedSubmissionAttempts = 2,
                    mappedBounds = 163,
                    mappedQueues = 164,
                    uniqueQueueFrames = 164,
                    surfaceFenceRows = 190,
                    presentedFrames = 164,
                    supersededQueueFrames = 0,
                    inputEvents = 26,
                    inputSegmentCount = 26,
                    candidateIntervals = 138,
                    intervals = 113,
                )
            )
        )
    }

    @Test
    fun fencesWithoutAnIntervalAreParserInvalidAndCannotAuthorizeFallback() {
        assertFalse(
            SurfacePresentationParserPolicy.isValid(
                SurfacePresentationParseCounts(
                    activeBounds = 163,
                    attemptedBounds = 163,
                    submissionAttempts = 163,
                    coalescedSubmissionAttempts = 0,
                    mappedBounds = 163,
                    mappedQueues = 163,
                    uniqueQueueFrames = 163,
                    surfaceFenceRows = 190,
                    presentedFrames = 163,
                    supersededQueueFrames = 0,
                    inputEvents = 163,
                    inputSegmentCount = 163,
                    candidateIntervals = 0,
                    intervals = 0,
                )
            )
        )
    }

    @Test
    fun duplicateOrMissingFrameMappingFailsClosed() {
        assertFalse(
            SurfacePresentationParserPolicy.isValid(
                SurfacePresentationParseCounts(
                    activeBounds = 163,
                    attemptedBounds = 163,
                    submissionAttempts = 163,
                    coalescedSubmissionAttempts = 0,
                    mappedBounds = 162,
                    mappedQueues = 163,
                    uniqueQueueFrames = 162,
                    surfaceFenceRows = 190,
                    presentedFrames = 162,
                    supersededQueueFrames = 0,
                    inputEvents = 26,
                    inputSegmentCount = 13,
                    candidateIntervals = 149,
                    intervals = 149,
                )
            )
        )
    }

    @Test
    fun parserAcceptsOnlyExplicitlyCoalescedAttemptsAndSupersededQueues() {
        assertTrue(
            SurfacePresentationParserPolicy.isValid(
                SurfacePresentationParseCounts(
                    activeBounds = 75,
                    attemptedBounds = 73,
                    submissionAttempts = 83,
                    coalescedSubmissionAttempts = 23,
                    mappedBounds = 56,
                    mappedQueues = 60,
                    uniqueQueueFrames = 60,
                    surfaceFenceRows = 75,
                    presentedFrames = 60,
                    supersededQueueFrames = 0,
                    inputEvents = 18,
                    inputSegmentCount = 12,
                    candidateIntervals = 48,
                    intervals = 23,
                )
            )
        )
        assertTrue(
            SurfacePresentationParserPolicy.isValid(
                SurfacePresentationParseCounts(
                    activeBounds = 58,
                    attemptedBounds = 58,
                    submissionAttempts = 195,
                    coalescedSubmissionAttempts = 27,
                    mappedBounds = 53,
                    mappedQueues = 168,
                    uniqueQueueFrames = 168,
                    surfaceFenceRows = 173,
                    presentedFrames = 165,
                    supersededQueueFrames = 3,
                    inputEvents = 18,
                    inputSegmentCount = 11,
                    candidateIntervals = 154,
                    intervals = 125,
                )
            )
        )
    }

    @Test
    fun unmatchedFinalSubmissionOrUnexplainedMissingPresentFailsClosed() {
        val unexplainedSubmission = SurfacePresentationParseCounts(
            activeBounds = 75,
            attemptedBounds = 73,
            submissionAttempts = 83,
            coalescedSubmissionAttempts = 22,
            mappedBounds = 56,
            mappedQueues = 60,
            uniqueQueueFrames = 60,
            surfaceFenceRows = 75,
            presentedFrames = 60,
            supersededQueueFrames = 0,
            inputEvents = 18,
            inputSegmentCount = 12,
            candidateIntervals = 48,
            intervals = 23,
        )
        val unexplainedPresent = unexplainedSubmission.copy(
            coalescedSubmissionAttempts = 23,
            presentedFrames = 59,
            supersededQueueFrames = 0,
            candidateIntervals = 47,
        )

        assertFalse(SurfacePresentationParserPolicy.isValid(unexplainedSubmission))
        assertFalse(SurfacePresentationParserPolicy.isValid(unexplainedPresent))
        assertFalse(
            SurfacePresentationParserPolicy.isValid(
                unexplainedSubmission.copy(
                    coalescedSubmissionAttempts = 23,
                    inputEvents = 0,
                )
            )
        )
    }
}
