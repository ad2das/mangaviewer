package ml.melun.mangaview.engine.session

import ml.melun.mangaview.engine.api.BoundaryProof
import ml.melun.mangaview.engine.api.DocumentBoundary
import ml.melun.mangaview.engine.api.InputOutcome
import ml.melun.mangaview.engine.api.InputReceipt
import ml.melun.mangaview.engine.api.InputSample
import java.util.Collections

internal data class PendingInput(
    val sample: InputSample,
    val acceptedAt: Long,
    var remaining: BigRational,
    var applied: BigRational = BigRational.ZERO,
    var blocker: GeometryBlocker? = null,
)

internal fun <T> immutableList(values: Collection<T>): List<T> =
    Collections.unmodifiableList(values.toList())

internal fun <T> immutableSet(values: Collection<T>): Set<T> =
    Collections.unmodifiableSet(values.toSet())

internal fun deferredReceipt(
    sample: InputSample,
    acceptedAtNanos: Long,
    appliedScreenUnits: Long,
    geometryRevision: Long,
): InputReceipt = InputReceipt(
    sample = sample,
    acceptedAtNanos = acceptedAtNanos,
    resolvedAtNanos = null,
    appliedScreenUnits = appliedScreenUnits,
    outcome = InputOutcome.DEFERRED,
    geometryRevision = geometryRevision,
)

internal fun appliedReceipt(
    sample: InputSample,
    acceptedAtNanos: Long,
    clockNanos: () -> Long,
    geometryRevision: Long,
): InputReceipt = InputReceipt(
    sample = sample,
    acceptedAtNanos = acceptedAtNanos,
    resolvedAtNanos = maxOf(clockNanos(), acceptedAtNanos),
    appliedScreenUnits = sample.deltaScreenUnits,
    outcome = InputOutcome.APPLIED,
    geometryRevision = geometryRevision,
)

internal fun clampedReceipt(
    sample: InputSample,
    acceptedAtNanos: Long,
    appliedScreenUnits: Long,
    clockNanos: () -> Long,
    geometryRevision: Long,
    boundary: DocumentBoundary,
    boundaryPage: ml.melun.mangaview.core.PageId,
): InputReceipt = InputReceipt(
    sample = sample,
    acceptedAtNanos = acceptedAtNanos,
    resolvedAtNanos = maxOf(clockNanos(), acceptedAtNanos),
    appliedScreenUnits = appliedScreenUnits,
    outcome = InputOutcome.CLAMPED,
    geometryRevision = geometryRevision,
    boundary = BoundaryProof(boundary, boundaryPage, geometryRevision),
)

internal fun cancelledReceipt(
    sample: InputSample,
    acceptedAtNanos: Long,
    appliedScreenUnits: Long,
    clockNanos: () -> Long,
    geometryRevision: Long,
): InputReceipt = InputReceipt(
    sample = sample,
    acceptedAtNanos = acceptedAtNanos,
    resolvedAtNanos = maxOf(clockNanos(), acceptedAtNanos),
    appliedScreenUnits = appliedScreenUnits,
    outcome = InputOutcome.CANCELLED,
    geometryRevision = geometryRevision,
)

internal fun deferredReceipt(pending: PendingInput, geometryRevision: Long): InputReceipt =
    deferredReceipt(
        pending.sample, pending.acceptedAt, pending.applied.truncToLong(), geometryRevision,
    )

internal fun appliedReceipt(
    pending: PendingInput,
    clockNanos: () -> Long,
    geometryRevision: Long,
): InputReceipt = appliedReceipt(pending.sample, pending.acceptedAt, clockNanos, geometryRevision)

internal fun clampedReceipt(
    pending: PendingInput,
    clockNanos: () -> Long,
    geometryRevision: Long,
    boundary: DocumentBoundary,
    boundaryPage: ml.melun.mangaview.core.PageId,
): InputReceipt = clampedReceipt(
    pending.sample,
    pending.acceptedAt,
    pending.applied.truncToLong(),
    clockNanos,
    geometryRevision,
    boundary,
    boundaryPage,
)

internal fun cancelledReceipt(
    sample: InputSample,
    acceptedAtNanos: Long,
    applied: BigRational,
    clockNanos: () -> Long,
    geometryRevision: Long,
): InputReceipt = cancelledReceipt(
    sample,
    acceptedAtNanos,
    applied.truncToLong(),
    clockNanos,
    geometryRevision,
)
