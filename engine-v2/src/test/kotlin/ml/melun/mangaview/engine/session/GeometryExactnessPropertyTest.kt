package ml.melun.mangaview.engine.session

import java.math.BigInteger
import java.util.Random
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Exactness proof for the Long fast path: every operation is compared against a frozen
 * BigInteger-backed reference over a seeded mix of extremes, fractions, and huge values.
 */
class GeometryExactnessPropertyTest {
    @Test
    fun arithmeticMatchesTheBigIntegerReferenceOverHalfAMillionChecks() {
        val rng = Random(0x5EED_5EED_2026L)
        var operations = 0
        repeat(50_000) {
            val a = randomPair(rng)
            val b = randomPair(rng)
            val actualA = BigRational.of(a.first, a.second)
            val actualB = BigRational.of(b.first, b.second)
            val expectedA = ReferenceRational.of(a.first, a.second)
            val expectedB = ReferenceRational.of(b.first, b.second)
            assertSame("$a + $b", expectedA + expectedB, actualA + actualB)
            assertSame("$a - $b", expectedA - expectedB, actualA - actualB)
            assertSame("$a * $b", expectedA * expectedB, actualA * actualB)
            if (b.first.signum() != 0) {
                assertSame("$a / $b", expectedA / expectedB, actualA / actualB)
            }
            assertEquals(
                "compare $a $b",
                Integer.signum(expectedA.compareTo(expectedB)),
                Integer.signum(actualA.compareTo(actualB)),
            )
            assertSame("negate $a", -expectedA, -actualA)
            assertEquals("trunc $a", expectedA.truncToLong(), actualA.truncToLong())
            assertEquals("floor $a", expectedA.floorToLong(), actualA.floorToLong())
            assertEquals("ceil $a", expectedA.ceilToLong(), actualA.ceilToLong())
            assertEquals("signum $a", expectedA.signum(), actualA.signum())
            assertEquals("isZero $a", expectedA.isZero(), actualA.isZero())
            assertSame("nonNegative $a", expectedA.nonNegative(), actualA.nonNegative())
            assertEquals("equals $a $b", expectedA == expectedB, actualA == actualB)
            assertEquals("hash $a", expectedA.hashCode(), actualA.hashCode())
            assertEquals("hash $b", expectedB.hashCode(), actualB.hashCode())
            assertEquals("string $a", expectedA.toString(), actualA.toString())
            operations += 12
        }
        assertTrue("checked $operations operations", operations >= 500_000)
    }

    @Test
    fun longAndBigIntegerFactoriesMatchTheReference() {
        val values = mutableListOf(
            Long.MIN_VALUE, Long.MIN_VALUE + 1L, Long.MAX_VALUE, Long.MAX_VALUE - 1L,
            -1L, 0L, 1L, 2L, -2L, 1L shl 62, -(1L shl 62), (1L shl 62) + 1L, 4_294_967_296L,
        )
        val rng = Random(0xFACADE00L)
        repeat(2_000) { values += rng.nextLong() }
        values.forEach { value ->
            assertSame("of($value)", ReferenceRational.of(value), BigRational.of(value))
        }
        val bigValues = mutableListOf(
            BigInteger.ONE.shiftLeft(63), BigInteger.ONE.shiftLeft(63).negate(),
            BigInteger.ONE.shiftLeft(127).add(BigInteger.valueOf(12345L)),
            BigInteger.TEN.pow(60), BigInteger.TEN.pow(60).negate(),
        )
        repeat(1_000) { bigValues += BigInteger(rng.nextInt(160) + 1, rng) }
        bigValues.forEach { value ->
            assertSame("of($value)", ReferenceRational.of(value), BigRational.of(value))
        }
    }

    @Test
    fun conversionsMatchTheReferenceFormula() {
        val rng = Random(0xC0FFEE_2026L)
        repeat(20_000) {
            val source = randomValue(rng)
            val width = 1 + rng.nextInt(8_192)
            val viewport = 1 + rng.nextInt(8_192)
            val actualScreen = sourceToScreenUnits(BigRational.of(source), width, viewport)
            val expectedScreen = ReferenceRational.sourceToScreenUnits(
                ReferenceRational.of(source), width, viewport,
            )
            assertSame("sourceToScreenUnits($source, $width, $viewport)", expectedScreen, actualScreen)
            val actualRoundTrip = screenToSourceQ32(actualScreen, width, viewport)
            val expectedRoundTrip = ReferenceRational.screenToSourceQ32(expectedScreen, width, viewport)
            assertSame("screenToSourceQ32 round trip", expectedRoundTrip, actualRoundTrip)
            val height = 1 + rng.nextInt(40_000)
            assertSame(
                "pageScreenLength($width, $height, $viewport)",
                ReferenceRational.pageScreenLength(width, height, viewport),
                pageScreenLength(width, height, viewport),
            )
        }
    }

    private fun assertSame(message: String, expected: ReferenceRational, actual: BigRational) {
        assertEquals("$message numerator", expected.numerator, actual.numerator)
        assertEquals("$message denominator", expected.denominator, actual.denominator)
        assertEquals("$message hash", expected.hashCode(), actual.hashCode())
        assertEquals("$message string", expected.toString(), actual.toString())
    }

    private fun randomPair(rng: Random): Pair<BigInteger, BigInteger> {
        val numerator = randomValue(rng)
        var denominator = randomValue(rng)
        if (denominator.signum() == 0) denominator = BigInteger.ONE
        // Half the pairs keep a fraction denominator to exercise reduction on every path.
        if (rng.nextBoolean()) denominator = denominator.add(BigInteger.ONE)
        if (denominator.signum() == 0) denominator = BigInteger.valueOf(-2L)
        return numerator to denominator
    }

    private fun randomValue(rng: Random): BigInteger = when (rng.nextInt(14)) {
        0 -> BigInteger.ZERO
        1 -> BigInteger.ONE
        2 -> BigInteger.valueOf(-1L)
        3 -> BigInteger.valueOf(rng.nextLong())
        4 -> BigInteger.valueOf(rng.nextLong() shr 32)
        5 -> BigInteger.valueOf(rng.nextInt().toLong())
        6 -> BigInteger.valueOf(rng.nextInt(1 shl 16).toLong())
        7 -> EDGE_VALUES[rng.nextInt(EDGE_VALUES.size)]
        8 -> BigInteger.ONE.shiftLeft(rng.nextInt(63))
        9 -> BigInteger.ONE.shiftLeft(rng.nextInt(63)).negate()
        10 -> BigInteger(rng.nextInt(80) + 1, rng)
        11 -> BigInteger(rng.nextInt(80) + 1, rng).negate()
        12 -> BigInteger(rng.nextInt(4) + 1, rng).multiply(BigInteger.valueOf(4_294_967_296L))
        else -> BigInteger(rng.nextInt(4) + 1, rng).multiply(BigInteger.valueOf(4_294_967_296L)).negate()
    }

    private companion object {
        val EDGE_VALUES = listOf(
            BigInteger.valueOf(Long.MAX_VALUE), BigInteger.valueOf(Long.MIN_VALUE),
            BigInteger.valueOf(Long.MAX_VALUE - 1L), BigInteger.valueOf(Long.MIN_VALUE + 1L),
            BigInteger.valueOf(Long.MAX_VALUE / 2L + 1L), BigInteger.valueOf(Long.MIN_VALUE / 2L),
            BigInteger.ONE.shiftLeft(63), BigInteger.ONE.shiftLeft(63).subtract(BigInteger.ONE),
            BigInteger.ONE.shiftLeft(64), BigInteger.ONE.shiftLeft(64).negate(),
            BigInteger.valueOf(4_294_967_296L), BigInteger.valueOf(1_099_511_627_776L),
        )
    }
}
