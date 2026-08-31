package ml.melun.mangaview.viewer

import androidx.test.core.app.ActivityScenario
import java.util.concurrent.atomic.AtomicReference
import ml.melun.mangaview.activity.ViewerActivity

internal enum class TelemetryDirection { FORWARD, REVERSE }

internal class ViewerUxTelemetryVerifier(
    private val violations: MutableList<String>,
) {
    fun captureAndVerify(
        scenario: ActivityScenario<ViewerActivity>,
        stage: String,
        expectedEpisode: LiveEpisode,
        previous: ViewerTelemetrySnapshot? = null,
        direction: TelemetryDirection? = null,
    ): ViewerTelemetrySnapshot {
        val current = snapshot(scenario)
        verifySnapshot(current, stage, expectedEpisode, previous, direction)
        return current
    }

    fun verifySnapshot(
        current: ViewerTelemetrySnapshot,
        stage: String,
        expectedEpisode: LiveEpisode,
        previous: ViewerTelemetrySnapshot? = null,
        direction: TelemetryDirection? = null,
    ) {
        verifyEpisodeIdentity(current, expectedEpisode, stage)
        verifyManifestAndPixels(current, stage)
        if (previous != null && direction != null) verifyGesture(previous, current, direction, stage)
    }

    fun snapshotOrNull(scenario: ActivityScenario<ViewerActivity>): ViewerTelemetrySnapshot? {
        val result = AtomicReference<ViewerTelemetrySnapshot?>()
        scenario.onActivity { activity -> result.set(activity.viewerTelemetrySnapshot()) }
        return result.get()
    }

    fun verifyHomeAnchor(before: ViewerTelemetrySnapshot, after: ViewerTelemetrySnapshot) {
        if (before.anchor != after.anchor) {
            violations += "HOME changed the reading anchor: ${before.anchor} -> ${after.anchor}"
        }
    }

    private fun snapshot(scenario: ActivityScenario<ViewerActivity>): ViewerTelemetrySnapshot =
        snapshotOrNull(scenario) ?: error("Viewer telemetry was unavailable after a real image frame")

    private fun verifyEpisodeIdentity(
        current: ViewerTelemetrySnapshot,
        expected: LiveEpisode,
        stage: String,
    ) {
        val opened = current.manifests.firstOrNull()?.id
        val identityMatches = opened != null &&
            opened.seriesId.sourceId.value == expected.sourceId &&
            opened.seriesId.remoteKey == expected.seriesKey && opened.remoteKey == expected.episodeKey
        if (!identityMatches) {
            violations += "Wrong episode at $stage: expected=" +
                "${expected.sourceId}/${expected.seriesKey}/${expected.episodeKey}, actual=$opened"
        }
    }

    private fun verifyManifestAndPixels(current: ViewerTelemetrySnapshot, stage: String) {
        val firstImageStage = stage == "first frame"
        val manifests = current.manifests
        val manifestIds = manifests.map { it.id }
        if (manifestIds.distinct().size != manifestIds.size) {
            violations += "Duplicate manifests at $stage"
        }
        manifests.zipWithNext().forEach { (from, to) ->
            if (from.nextEpisodeId != to.id) {
                violations += "Broken manifest chain at $stage: ${from.id} -> ${to.id}"
            }
        }
        val declaredPages = manifests.flatMap { it.pages }.mapTo(mutableSetOf()) { it.id }
        if (current.anchor.pageId !in declaredPages) {
            violations += "Anchor points at an undeclared page at $stage: ${current.anchor.pageId}"
        }
        if (current.visiblePages.isEmpty()) violations += "No visible page telemetry at $stage"
        current.visiblePages.forEach { page ->
            if (page.pageId !in declaredPages) violations += "Wrong page at $stage: ${page.pageId}"
            if (firstImageStage && page.visualCoveredUnits != page.visibleUnits) {
                violations += "Blank visual pixels at $stage: ${page.pageId} " +
                    "visual=${page.visualCoveredUnits}/${page.visibleUnits}"
            }
            if (!firstImageStage && (!page.presented || page.coveredUnits != page.visibleUnits)) {
                violations += "Blank pixels at $stage: ${page.pageId} " +
                    "covered=${page.coveredUnits}/${page.visibleUnits} presented=${page.presented}"
            }
            if (page.overlappingUnits != 0L) {
                violations += "Overlapping pixels at $stage: ${page.pageId}=${page.overlappingUnits}"
            }
        }
        if (firstImageStage && current.visuallyUncoveredViewportUnits != 0L) {
            violations += "Visually uncovered viewport at $stage: " +
                "${current.visuallyUncoveredViewportUnits} units"
        }
        if (!firstImageStage && current.uncoveredViewportUnits != 0L) {
            violations += "Uncovered viewport at $stage: ${current.uncoveredViewportUnits} units"
        }
        if (firstImageStage && current.visiblePages.none { it.coveredUnits > 0L }) {
            violations += "No real image pixels were visible at $stage"
        }
        if (current.overlappingViewportUnits != 0L) {
            violations += "Overlapping viewport at $stage: ${current.overlappingViewportUnits} units"
        }
    }

    private fun verifyGesture(
        previous: ViewerTelemetrySnapshot,
        current: ViewerTelemetrySnapshot,
        direction: TelemetryDirection,
        stage: String,
    ) {
        val ordinalComparison = current.anchorOrdinal.compareTo(previous.anchorOrdinal)
        val offsetComparison = logicalAnchorOffset(current).compareTo(logicalAnchorOffset(previous))
        val comparison = if (ordinalComparison != 0) ordinalComparison else offsetComparison
        val reversed = direction == TelemetryDirection.FORWARD && comparison < 0
        val advanced = direction == TelemetryDirection.REVERSE && comparison > 0
        if (reversed || advanced) {
            violations += "Scroll moved against the real $direction gesture at $stage: " +
                "${previous.anchor} -> ${current.anchor}"
        }
        if (comparison == 0) {
            violations += "Real $direction gesture produced zero visual displacement at $stage"
        }
        if (current.userInputRevision <= previous.userInputRevision) {
            violations += "Real $direction gesture did not advance user-input proof at $stage"
        }
        val displacement = kotlin.math.abs(current.scrollOffsetUnits - previous.scrollOffsetUnits)
        val viewport = maxOf(previous.viewportHeightUnits, current.viewportHeightUnits)
        val maximum = saturatingMultiply(viewport, MAXIMUM_GESTURE_VIEWPORTS)
        if (displacement > maximum) {
            violations += "Real $direction gesture moved $displacement units at $stage; " +
                "maximum bounded displacement is $maximum"
        }
    }

    private fun logicalAnchorOffset(snapshot: ViewerTelemetrySnapshot): Long =
        snapshot.anchor.offsetInPageUnits - snapshot.anchor.viewportOffsetUnits

    private fun saturatingMultiply(value: Long, factor: Long): Long = runCatching {
        Math.multiplyExact(value, factor)
    }.getOrDefault(Long.MAX_VALUE)

    private companion object {
        const val MAXIMUM_GESTURE_VIEWPORTS = 8L
    }
}
