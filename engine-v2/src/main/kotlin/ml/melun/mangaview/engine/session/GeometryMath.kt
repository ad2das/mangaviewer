package ml.melun.mangaview.engine.session

import java.math.BigInteger

internal const val Q32_PER_PIXEL_LONG: Long = 4_294_967_296L
internal const val SCREEN_UNITS_PER_PIXEL_LONG: Long = 1_024L

internal val Q32_PER_PIXEL: BigInteger = BigInteger.valueOf(Q32_PER_PIXEL_LONG)
internal val SCREEN_UNITS_PER_PIXEL: BigInteger = BigInteger.valueOf(SCREEN_UNITS_PER_PIXEL_LONG)

/**
 * A small exact rational used for source coordinates and screen distances.
 *
 * Values whose reduced numerator and denominator both fit a Long stay on a Long fast path whose
 * common case allocates at most the result object; arithmetic that overflows falls back to
 * BigInteger for that operation and re-narrows the reduced result. Normalization (sign in the
 * numerator, reduced, positive denominator) keeps equals, hashCode, and compareTo canonical, and
 * the hash of a Long-backed value matches the BigInteger hash the reference implementation used.
 */
internal class BigRational private constructor(
    private val num: Long,
    private val den: Long,
    private val wide: Array<BigInteger>?,
) : Comparable<BigRational> {
    val numerator: BigInteger
        get() = wide?.get(0) ?: BigInteger.valueOf(num)
    val denominator: BigInteger
        get() = wide?.get(1) ?: BigInteger.valueOf(den)

    private val bigNum: BigInteger get() = wide?.get(0) ?: BigInteger.valueOf(num)
    private val bigDen: BigInteger get() = wide?.get(1) ?: BigInteger.valueOf(den)

    operator fun plus(other: BigRational): BigRational {
        if (wide == null && other.wide == null) {
            try {
                val n = Math.addExact(Math.multiplyExact(num, other.den), Math.multiplyExact(other.num, den))
                return canonicalLong(n, Math.multiplyExact(den, other.den))
            } catch (_: ArithmeticException) {
                // Wider than a Long; the exact BigInteger path below always applies.
            }
        }
        return of(bigNum * other.bigDen + other.bigNum * bigDen, bigDen * other.bigDen)
    }

    operator fun minus(other: BigRational): BigRational {
        if (wide == null && other.wide == null) {
            try {
                val n = Math.subtractExact(Math.multiplyExact(num, other.den), Math.multiplyExact(other.num, den))
                return canonicalLong(n, Math.multiplyExact(den, other.den))
            } catch (_: ArithmeticException) {
                // Wider than a Long; the exact BigInteger path below always applies.
            }
        }
        return of(bigNum * other.bigDen - other.bigNum * bigDen, bigDen * other.bigDen)
    }

    operator fun times(other: BigRational): BigRational {
        if (wide == null && other.wide == null) {
            try {
                // Cross-cancel first so the common conversion stays inside Long.
                val g1 = gcdLong(num, other.den)
                val g2 = gcdLong(other.num, den)
                val n = Math.multiplyExact(num / g1, other.num / g2)
                return canonicalLong(n, Math.multiplyExact(den / g2, other.den / g1))
            } catch (_: ArithmeticException) {
                // Wider than a Long; the exact BigInteger path below always applies.
            }
        }
        return of(bigNum * other.bigNum, bigDen * other.bigDen)
    }

    operator fun div(other: BigRational): BigRational {
        require(other.signum() != 0) { "A rational denominator cannot be zero" }
        if (wide == null && other.wide == null && num != Long.MIN_VALUE && other.num != Long.MIN_VALUE) {
            try {
                val g1 = gcdLong(num, other.num)
                val g2 = gcdLong(den, other.den)
                var n = Math.multiplyExact(num / g1, other.den / g2)
                var d = Math.multiplyExact(den / g2, other.num / g1)
                if (d < 0L) {
                    if (d == Long.MIN_VALUE) throw ArithmeticException("fraction wider than a Long")
                    n = Math.negateExact(n)
                    d = -d
                }
                return canonicalLong(n, d)
            } catch (_: ArithmeticException) {
                // Wider than a Long; the exact BigInteger path below always applies.
            }
        }
        return of(bigNum * other.bigDen, bigDen * other.bigNum)
    }

    operator fun unaryMinus(): BigRational {
        if (wide == null) {
            if (num == 0L) return ZERO
            if (num != Long.MIN_VALUE) return BigRational(-num, den, null)
        }
        return of(bigNum.negate(), bigDen)
    }

    override fun compareTo(other: BigRational): Int {
        if (wide == null && other.wide == null) {
            if (den == other.den) return num.compareTo(other.num)
            try {
                return Math.multiplyExact(num, other.den).compareTo(Math.multiplyExact(other.num, den))
            } catch (_: ArithmeticException) {
                // Cross products wider than a Long; compare exactly below.
            }
        }
        return (bigNum * other.bigDen).compareTo(other.bigNum * bigDen)
    }

    fun signum(): Int = if (wide == null) java.lang.Long.signum(num) else wide[0].signum()

    fun isZero(): Boolean = if (wide == null) num == 0L else wide[0].signum() == 0

    fun nonNegative(): BigRational = if (signum() < 0) ZERO else this

    fun truncToLong(): Long = if (wide == null) num / den else saturatingLong(wide[0].divide(wide[1]))

    fun floorToLong(): Long {
        if (wide != null) return saturatingLong(floorInteger(wide[0], wide[1]))
        val quotient = num / den
        return if (num < 0L && num % den != 0L) quotient - 1L else quotient
    }

    fun ceilToLong(): Long {
        if (wide != null) return saturatingLong(ceilInteger(wide[0], wide[1]))
        val quotient = num / den
        return if (num > 0L && num % den != 0L) quotient + 1L else quotient
    }

    override fun equals(other: Any?): Boolean {
        if (other !is BigRational) return false
        val mine = wide
        val theirs = other.wide
        if (mine == null || theirs == null) return mine === theirs && num == other.num && den == other.den
        return mine[0] == theirs[0] && mine[1] == theirs[1]
    }

    override fun hashCode(): Int {
        val values = wide
        return if (values == null) 31 * longHash(num) + longHash(den)
        else 31 * values[0].hashCode() + values[1].hashCode()
    }

    override fun toString(): String = "$numerator/$denominator"

    companion object {
        val ZERO: BigRational = BigRational(0L, 1L, null)
        val ONE: BigRational = BigRational(1L, 1L, null)

        fun of(value: Long): BigRational = if (value == 0L) ZERO else BigRational(value, 1L, null)

        fun of(value: BigInteger): BigRational {
            if (value.signum() == 0) return ZERO
            if (fitsLong(value)) return BigRational(value.toLong(), 1L, null)
            return BigRational(0L, 1L, arrayOf(value, BigInteger.ONE))
        }

        fun of(numerator: BigInteger, denominator: BigInteger): BigRational {
            require(denominator.signum() != 0) { "A rational denominator cannot be zero" }
            if (numerator.signum() == 0) return ZERO
            val positiveDenominator = if (denominator.signum() < 0) denominator.negate() else denominator
            val positiveNumerator = if (denominator.signum() < 0) numerator.negate() else numerator
            val divisor = positiveNumerator.abs().gcd(positiveDenominator)
            return reducedBig(positiveNumerator.divide(divisor), positiveDenominator.divide(divisor))
        }

        private fun reducedBig(numerator: BigInteger, denominator: BigInteger): BigRational =
            if (fitsLong(numerator) && fitsLong(denominator)) {
                BigRational(numerator.toLong(), denominator.toLong(), null)
            } else {
                BigRational(0L, 1L, arrayOf(numerator, denominator))
            }

        private fun fitsLong(value: BigInteger): Boolean = value.bitLength() <= 63

        /** Long construction for values already reduced with a positive denominator. */
        fun longValue(numerator: Long, denominator: Long): BigRational =
            BigRational(numerator, denominator, null)
    }
}

/** Reduced Long construction; callers guarantee denominator > 0. A zero numerator returns ZERO. */
private fun canonicalLong(n: Long, d: Long): BigRational {
    if (n == 0L) return BigRational.ZERO
    if (n == Long.MIN_VALUE) {
        // |n| = 2^63 cannot be negated, but its only common factor with d is a power of two.
        val shift = d.countTrailingZeroBits()
        return BigRational.longValue(Long.MIN_VALUE shr shift, d shr shift)
    }
    var a = if (n < 0L) -n else n
    var b = d
    while (b != 0L) {
        val remainder = a % b
        a = b
        b = remainder
    }
    return BigRational.longValue(n / a, d / a)
}

/** gcd of two signed Longs; 2^63 participates only through a shared power of two. */
private fun gcdLong(a: Long, b: Long): Long {
    if (a == Long.MIN_VALUE) return powerOfTwoDivisor(b)
    if (b == Long.MIN_VALUE) return powerOfTwoDivisor(a)
    var x = if (a < 0L) -a else a
    var y = if (b < 0L) -b else b
    while (y != 0L) {
        val remainder = x % y
        x = y
        y = remainder
    }
    return x
}

private fun powerOfTwoDivisor(other: Long): Long {
    val magnitude = if (other < 0L) -other else other
    return 1L shl magnitude.countTrailingZeroBits()
}

/** Matches BigInteger.hashCode() of the same Long value so existing hash values stay stable. */
private fun longHash(value: Long): Int {
    if (value == 0L) return 0
    val negative = value < 0L
    val magnitude = if (negative && value != Long.MIN_VALUE) -value else value
    var hash = (magnitude ushr 32).toInt()
    hash = 31 * hash + magnitude.toInt()
    return if (negative) -hash else hash
}

internal fun sourceToScreenUnits(
    sourceQ32: BigRational,
    pageWidthPx: Int,
    viewportWidthPx: Int,
): BigRational = sourceQ32 * sourceToScreenScale(pageWidthPx, viewportWidthPx)

internal fun screenToSourceQ32(
    screenUnits: BigRational,
    pageWidthPx: Int,
    viewportWidthPx: Int,
): BigRational = screenUnits * screenToSourceScale(pageWidthPx, viewportWidthPx)

internal fun sourceToScreenScale(pageWidthPx: Int, viewportWidthPx: Int): BigRational = BigRational.of(
    BigInteger.valueOf(viewportWidthPx.toLong()).multiply(SCREEN_UNITS_PER_PIXEL),
    BigInteger.valueOf(pageWidthPx.toLong()).multiply(Q32_PER_PIXEL),
)

internal fun screenToSourceScale(pageWidthPx: Int, viewportWidthPx: Int): BigRational = BigRational.of(
    BigInteger.valueOf(pageWidthPx.toLong()).multiply(Q32_PER_PIXEL),
    BigInteger.valueOf(viewportWidthPx.toLong()).multiply(SCREEN_UNITS_PER_PIXEL),
)

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
