package ml.melun.mangaview.viewer

@JvmInline
value class FixedPx(val units: Long) : Comparable<FixedPx> {
    operator fun plus(other: FixedPx): FixedPx = FixedPx(saturatingAdd(units, other.units))

    operator fun minus(other: FixedPx): FixedPx = FixedPx(saturatingSubtract(units, other.units))

    operator fun unaryMinus(): FixedPx = FixedPx(saturatingSubtract(0L, units))

    override fun compareTo(other: FixedPx): Int = units.compareTo(other.units)

    fun coerceIn(minimum: FixedPx, maximum: FixedPx): FixedPx =
        FixedPx(units.coerceIn(minimum.units, maximum.units))

    fun toPixels(): Double = units.toDouble() / UNITS_PER_PIXEL

    companion object {
        const val UNITS_PER_PIXEL: Long = 1_024L
        val ZERO = FixedPx(0L)

        fun fromPixels(pixels: Int): FixedPx = FixedPx(Math.multiplyExact(pixels.toLong(), UNITS_PER_PIXEL))

        fun fromPixels(pixels: Double): FixedPx {
            require(pixels.isFinite()) { "Pixel coordinate must be finite" }
            val scaled = pixels * UNITS_PER_PIXEL
            require(scaled in Long.MIN_VALUE.toDouble()..Long.MAX_VALUE.toDouble()) {
                "Pixel coordinate is outside the fixed-point range"
            }
            return FixedPx(kotlin.math.round(scaled).toLong())
        }
    }
}

data class Viewport(
    val width: FixedPx,
    val height: FixedPx,
) {
    init {
        require(width > FixedPx.ZERO) { "Viewport width must be positive" }
        require(height > FixedPx.ZERO) { "Viewport height must be positive" }
    }
}
