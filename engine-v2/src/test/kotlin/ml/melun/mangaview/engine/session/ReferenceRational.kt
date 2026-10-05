package ml.melun.mangaview.engine.session

import java.math.BigInteger

/**
 * Frozen copy of the BigInteger-backed rational that predates the Long fast path. The exactness
 * property test derives every expected value from this implementation, never from the new one.
 */
internal class ReferenceRational private constructor(
    val numerator: BigInteger,
    val denominator: BigInteger,
) : Comparable<ReferenceRational> {
    init {
        require(denominator.signum() > 0)
    }

    operator fun plus(other: ReferenceRational): ReferenceRational = of(
        numerator.multiply(other.denominator).add(other.numerator.multiply(denominator)),
        denominator.multiply(other.denominator),
    )

    operator fun minus(other: ReferenceRational): ReferenceRational = of(
        numerator.multiply(other.denominator).subtract(other.numerator.multiply(denominator)),
        denominator.multiply(other.denominator),
    )

    operator fun times(other: ReferenceRational): ReferenceRational = of(
        numerator.multiply(other.numerator), denominator.multiply(other.denominator),
    )

    operator fun div(other: ReferenceRational): ReferenceRational = of(
        numerator.multiply(other.denominator), denominator.multiply(other.numerator),
    )

    operator fun unaryMinus(): ReferenceRational = of(numerator.negate(), denominator)

    override fun compareTo(other: ReferenceRational): Int = numerator.multiply(other.denominator)
        .compareTo(other.numerator.multiply(denominator))

    fun signum(): Int = numerator.signum()

    fun isZero(): Boolean = numerator.signum() == 0

    fun nonNegative(): ReferenceRational = if (signum() < 0) ZERO else this

    fun truncToLong(): Long = saturatingLong(numerator.divide(denominator))

    fun floorToLong(): Long = saturatingLong(floorInteger(numerator, denominator))

    fun ceilToLong(): Long = saturatingLong(ceilInteger(numerator, denominator))

    override fun equals(other: Any?): Boolean = other is ReferenceRational &&
        numerator == other.numerator && denominator == other.denominator

    override fun hashCode(): Int = 31 * numerator.hashCode() + denominator.hashCode()

    override fun toString(): String = "$numerator/$denominator"

    companion object {
        val ZERO: ReferenceRational = ReferenceRational(BigInteger.ZERO, BigInteger.ONE)
        val ONE: ReferenceRational = ReferenceRational(BigInteger.ONE, BigInteger.ONE)

        fun of(value: Long): ReferenceRational = ReferenceRational(BigInteger.valueOf(value), BigInteger.ONE)

        fun of(value: BigInteger): ReferenceRational = ReferenceRational(value, BigInteger.ONE)

        fun of(numerator: BigInteger, denominator: BigInteger): ReferenceRational {
            require(denominator.signum() != 0) { "A rational denominator cannot be zero" }
            if (numerator.signum() == 0) return ZERO
            val positiveDenominator = if (denominator.signum() < 0) denominator.negate() else denominator
            val positiveNumerator = if (denominator.signum() < 0) numerator.negate() else numerator
            val divisor = positiveNumerator.abs().gcd(positiveDenominator)
            return ReferenceRational(positiveNumerator.divide(divisor), positiveDenominator.divide(divisor))
        }

        fun sourceToScreenUnits(
            sourceQ32: ReferenceRational,
            pageWidthPx: Int,
            viewportWidthPx: Int,
        ): ReferenceRational {
            val scale = of(
                BigInteger.valueOf(pageWidthPx.toLong()).multiply(Q32_PER_PIXEL),
                BigInteger.valueOf(viewportWidthPx.toLong()).multiply(SCREEN_UNITS_PER_PIXEL),
            )
            return sourceQ32 * ONE / scale
        }

        fun screenToSourceQ32(
            screenUnits: ReferenceRational,
            pageWidthPx: Int,
            viewportWidthPx: Int,
        ): ReferenceRational {
            val scale = of(
                BigInteger.valueOf(pageWidthPx.toLong()).multiply(Q32_PER_PIXEL),
                BigInteger.valueOf(viewportWidthPx.toLong()).multiply(SCREEN_UNITS_PER_PIXEL),
            )
            return screenUnits * scale
        }

        fun pageScreenLength(
            pageWidthPx: Int,
            pageHeightPx: Int,
            viewportWidthPx: Int,
        ): ReferenceRational = of(
            BigInteger.valueOf(pageHeightPx.toLong())
                .multiply(BigInteger.valueOf(viewportWidthPx.toLong()))
                .multiply(SCREEN_UNITS_PER_PIXEL),
            BigInteger.valueOf(pageWidthPx.toLong()),
        )
    }
}

private fun floorInteger(numerator: BigInteger, denominator: BigInteger): BigInteger {
    val (quotient, remainder) = numerator.divideAndRemainder(denominator)
    return if (numerator.signum() < 0 && remainder.signum() != 0) quotient.subtract(BigInteger.ONE) else quotient
}

private fun ceilInteger(numerator: BigInteger, denominator: BigInteger): BigInteger {
    val (quotient, remainder) = numerator.divideAndRemainder(denominator)
    return if (numerator.signum() > 0 && remainder.signum() != 0) quotient.add(BigInteger.ONE) else quotient
}

private fun saturatingLong(value: BigInteger): Long = when {
    value > BigInteger.valueOf(Long.MAX_VALUE) -> Long.MAX_VALUE
    value < BigInteger.valueOf(Long.MIN_VALUE) -> Long.MIN_VALUE
    else -> value.toLong()
}
