package ml.melun.mangaview.engine.session

import java.math.BigInteger

internal const val Q32_PER_PIXEL_LONG: Long = 4_294_967_296L
internal const val SCREEN_UNITS_PER_PIXEL_LONG: Long = 1_024L

internal val Q32_PER_PIXEL: BigInteger = BigInteger.valueOf(Q32_PER_PIXEL_LONG)
internal val SCREEN_UNITS_PER_PIXEL: BigInteger = BigInteger.valueOf(SCREEN_UNITS_PER_PIXEL_LONG)

/** A small exact rational used for source coordinates and screen distances. */
internal class BigRational private constructor(
    val numerator: BigInteger,
    val denominator: BigInteger,
) : Comparable<BigRational> {
    init {
        require(denominator.signum() > 0)
    }

    operator fun plus(other: BigRational): BigRational = of(
        numerator.multiply(other.denominator).add(other.numerator.multiply(denominator)),
        denominator.multiply(other.denominator),
    )

    operator fun minus(other: BigRational): BigRational = of(
        numerator.multiply(other.denominator).subtract(other.numerator.multiply(denominator)),
        denominator.multiply(other.denominator),
    )

    operator fun times(other: BigRational): BigRational = of(
        numerator.multiply(other.numerator), denominator.multiply(other.denominator),
    )

    operator fun div(other: BigRational): BigRational = of(
        numerator.multiply(other.denominator), denominator.multiply(other.numerator),
    )

    operator fun unaryMinus(): BigRational = of(numerator.negate(), denominator)

    override fun compareTo(other: BigRational): Int = numerator.multiply(other.denominator)
        .compareTo(other.numerator.multiply(denominator))

    fun signum(): Int = numerator.signum()

    fun isZero(): Boolean = numerator.signum() == 0

    fun nonNegative(): BigRational = if (signum() < 0) ZERO else this

    fun truncToLong(): Long = saturatingLong(numerator.divide(denominator))

    fun floorToLong(): Long = saturatingLong(floorInteger(numerator, denominator))

    fun ceilToLong(): Long = saturatingLong(ceilInteger(numerator, denominator))

    override fun equals(other: Any?): Boolean = other is BigRational &&
        numerator == other.numerator && denominator == other.denominator

    override fun hashCode(): Int = 31 * numerator.hashCode() + denominator.hashCode()

    override fun toString(): String = "$numerator/$denominator"

    companion object {
        val ZERO: BigRational = BigRational(BigInteger.ZERO, BigInteger.ONE)
        val ONE: BigRational = BigRational(BigInteger.ONE, BigInteger.ONE)

        fun of(value: Long): BigRational = BigRational(BigInteger.valueOf(value), BigInteger.ONE)

        fun of(value: BigInteger): BigRational = BigRational(value, BigInteger.ONE)

        fun of(numerator: BigInteger, denominator: BigInteger): BigRational {
            require(denominator.signum() != 0) { "A rational denominator cannot be zero" }
            if (numerator.signum() == 0) return ZERO
            val positiveDenominator = if (denominator.signum() < 0) denominator.negate() else denominator
            val positiveNumerator = if (denominator.signum() < 0) numerator.negate() else numerator
            val divisor = positiveNumerator.abs().gcd(positiveDenominator)
            return BigRational(positiveNumerator.divide(divisor), positiveDenominator.divide(divisor))
        }
    }
}

internal fun sourceToScreenUnits(
    sourceQ32: BigRational,
    pageWidthPx: Int,
    viewportWidthPx: Int,
): BigRational {
    val scale = BigRational.of(
        BigInteger.valueOf(pageWidthPx.toLong()).multiply(Q32_PER_PIXEL),
        BigInteger.valueOf(viewportWidthPx.toLong()).multiply(SCREEN_UNITS_PER_PIXEL),
    )
    return sourceQ32 * BigRational.ONE / scale
}

internal fun screenToSourceQ32(
    screenUnits: BigRational,
    pageWidthPx: Int,
    viewportWidthPx: Int,
): BigRational {
    val scale = BigRational.of(
        BigInteger.valueOf(pageWidthPx.toLong()).multiply(Q32_PER_PIXEL),
        BigInteger.valueOf(viewportWidthPx.toLong()).multiply(SCREEN_UNITS_PER_PIXEL),
    )
    return screenUnits * scale
}

internal fun pageScreenLength(
    pageWidthPx: Int,
    pageHeightPx: Int,
    viewportWidthPx: Int,
): BigRational = BigRational.of(
    BigInteger.valueOf(pageHeightPx.toLong())
        .multiply(BigInteger.valueOf(viewportWidthPx.toLong()))
        .multiply(SCREEN_UNITS_PER_PIXEL),
    BigInteger.valueOf(pageWidthPx.toLong()),
)

internal fun pageSourceExtent(heightPx: Int): BigInteger =
    BigInteger.valueOf(heightPx.toLong()).multiply(Q32_PER_PIXEL)

private fun floorInteger(numerator: BigInteger, denominator: BigInteger): BigInteger {
    val (quotient, remainder) = numerator.divideAndRemainder(denominator)
    return if (numerator.signum() < 0 && remainder.signum() != 0) quotient.subtract(BigInteger.ONE) else quotient
}

private fun ceilInteger(numerator: BigInteger, denominator: BigInteger): BigInteger {
    val (quotient, remainder) = numerator.divideAndRemainder(denominator)
    return if (numerator.signum() > 0 && remainder.signum() != 0) quotient.add(BigInteger.ONE) else quotient
}

internal fun saturatingLong(value: BigInteger): Long = when {
    value > BigInteger.valueOf(Long.MAX_VALUE) -> Long.MAX_VALUE
    value < BigInteger.valueOf(Long.MIN_VALUE) -> Long.MIN_VALUE
    else -> value.toLong()
}
