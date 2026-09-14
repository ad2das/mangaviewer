package ml.melun.mangaview.viewer

/** Frozen stage plan and bounds for the engine scroll qualification capture. */
internal object EngineScrollQualificationPolicy {
    const val SCHEMA_VERSION = 3

    const val TARGET_MOVING_DISPLAY_FRAMES = 1_000
    const val MIN_DIRECTIONAL_TRANSITIONS = 4
    const val LOADING_STAGE_BUDGET_MILLIS = 20_000L
    const val STREAMING_STAGE_BUDGET_MILLIS = 60_000L
    const val STREAMING_STAGE_MAX_GESTURES = 300
    // case05f: READY_ROUND_TRIP reached 870/1000 and FAST_REVERSE 755/1000 inside the old
    // 120 s / 600-gesture bound; ENDPOINT_START passed at 598/600. The measured rate on this
    // emulator is ~1.3-2.0 raw frames per gesture, so the bounds carry ~60 % headroom.
    // w5: with the acquire-fence re-attached the raw DISPLAY_PRESENT rate dropped to ~0.98-1.08
    // frames per gesture (READY 880/900, FAST 817/900 at the 900-gesture cap), so the gesture
    // bound is widened to keep >= 40 % headroom over the 1000-frame target at ~1.0/gesture; the
    // 200 s budget still bounds the stage (~12.6 gestures/s -> ~127 s for 1600 gestures).
    const val STEADY_STAGE_BUDGET_MILLIS = 200_000L
    const val STEADY_STAGE_MAX_GESTURES = 1_600
    const val ENDPOINT_STAGE_BUDGET_MILLIS = 160_000L
    const val ENDPOINT_STAGE_MAX_GESTURES = 800
    const val BOUNDARY_STAGE_BUDGET_MILLIS = 150_000L
    const val BOUNDARY_STAGE_MAX_GESTURES = 700
    const val STOPPED_SETTLE_MILLIS = 1_500L

    /** Deliberate reversal blocks, small enough to avoid drifting into start/next clamps. */
    const val REVERSAL_BLOCK_GESTURES = 3

    const val LOADING = "LOADING"
    const val STREAMING = "STREAMING"
    const val READY_ROUND_TRIP = "READY_ROUND_TRIP"
    const val FAST_REVERSE = "FAST_REVERSE"
    const val ENDPOINT_END = "ENDPOINT_END"
    const val NEXT_BOUNDARY = "NEXT_BOUNDARY"
    const val ENDPOINT_START = "ENDPOINT_START"

    enum class GestureMode { NONE, ROUND_TRIP, REVERSE_BLOCKS, FORWARD_UNTIL, REVERSE_UNTIL }

    data class StagePlan(
        val name: String,
        val mode: GestureMode,
        val measuredFromStart: Boolean,
        val targetMovingFrames: Int,
        val budgetMillis: Long,
        val maxGestures: Int,
    )

    val STAGES: List<StagePlan> = listOf(
        StagePlan(LOADING, GestureMode.ROUND_TRIP, false, 0,
            LOADING_STAGE_BUDGET_MILLIS, 200),
        StagePlan(STREAMING, GestureMode.ROUND_TRIP, true, 0,
            STREAMING_STAGE_BUDGET_MILLIS, STREAMING_STAGE_MAX_GESTURES),
        StagePlan(READY_ROUND_TRIP, GestureMode.ROUND_TRIP, true, TARGET_MOVING_DISPLAY_FRAMES,
            STEADY_STAGE_BUDGET_MILLIS, STEADY_STAGE_MAX_GESTURES),
        StagePlan(FAST_REVERSE, GestureMode.REVERSE_BLOCKS, true, TARGET_MOVING_DISPLAY_FRAMES,
            STEADY_STAGE_BUDGET_MILLIS, STEADY_STAGE_MAX_GESTURES),
        StagePlan(ENDPOINT_END, GestureMode.FORWARD_UNTIL, false, TARGET_MOVING_DISPLAY_FRAMES,
            ENDPOINT_STAGE_BUDGET_MILLIS, ENDPOINT_STAGE_MAX_GESTURES),
        StagePlan(NEXT_BOUNDARY, GestureMode.FORWARD_UNTIL, false, TARGET_MOVING_DISPLAY_FRAMES,
            BOUNDARY_STAGE_BUDGET_MILLIS, BOUNDARY_STAGE_MAX_GESTURES),
        StagePlan(ENDPOINT_START, GestureMode.REVERSE_UNTIL, false, TARGET_MOVING_DISPLAY_FRAMES,
            ENDPOINT_STAGE_BUDGET_MILLIS, ENDPOINT_STAGE_MAX_GESTURES),
    )

    /** Stages that must each accumulate 1000 measured moving display frames per run. */
    val TARGETED_STAGES: List<String> = listOf(
        READY_ROUND_TRIP, FAST_REVERSE, ENDPOINT_END, NEXT_BOUNDARY, ENDPOINT_START,
    )

    fun stage(name: String): StagePlan = STAGES.first { it.name == name }
}
