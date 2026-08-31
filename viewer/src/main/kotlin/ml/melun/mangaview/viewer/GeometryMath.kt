package ml.melun.mangaview.viewer

import java.math.BigInteger

internal fun saturatingAdd(left: Long, right: Long): Long = when {
    right > 0L && left > Long.MAX_VALUE - right -> Long.MAX_VALUE
    right < 0L && left < Long.MIN_VALUE - right -> Long.MIN_VALUE
    else -> left + right
}

internal fun saturatingSubtract(left: Long, right: Long): Long = when {
    right > 0L && left < Long.MIN_VALUE + right -> Long.MIN_VALUE
    right < 0L && left > Long.MAX_VALUE + right -> Long.MAX_VALUE
    else -> left - right
}

internal fun saturatingMultiplyNonNegative(value: Long, factor: Int): Long {
    require(value >= 0L && factor >= 0)
    if (value == 0L || factor == 0) return 0L
    return if (value > Long.MAX_VALUE / factor) Long.MAX_VALUE else value * factor
}

internal fun multiplyDivideFloorExact(value: Long, multiplier: Int, divisor: Int): Long =
    multiplyDivideFloorExact(value, multiplier.toLong(), divisor.toLong())

internal fun multiplyDivideFloorExact(value: Long, multiplier: Long, divisor: Long): Long {
    require(value >= 0L && multiplier >= 0L && divisor > 0L)
    if (value == 0L || multiplier == 0L) return 0L
    if (value <= Long.MAX_VALUE / multiplier) return value * multiplier / divisor
    return bigProduct(value, multiplier).divide(BigInteger.valueOf(divisor)).longValueExact()
}

internal fun multiplyDivideCeilExact(value: Long, multiplier: Int, divisor: Int): Long =
    multiplyDivideCeilExact(value, multiplier.toLong(), divisor.toLong())

internal fun multiplyDivideCeilExact(value: Long, multiplier: Long, divisor: Long): Long {
    require(value >= 0L && multiplier >= 0L && divisor > 0L)
    if (value == 0L || multiplier == 0L) return 0L
    val division = if (value <= Long.MAX_VALUE / multiplier) {
        val product = value * multiplier
        product / divisor to product % divisor
    } else {
        val parts = bigProduct(value, multiplier).divideAndRemainder(BigInteger.valueOf(divisor))
        parts[0].longValueExact() to parts[1].longValueExact()
    }
    return if (division.second == 0L) division.first else Math.addExact(division.first, 1L)
}

internal fun midpoint(start: Long, end: Long): Long {
    require(start <= end)
    return start + (end - start) / 2L
}

private fun bigProduct(left: Long, right: Long): BigInteger =
    BigInteger.valueOf(left).multiply(BigInteger.valueOf(right))
