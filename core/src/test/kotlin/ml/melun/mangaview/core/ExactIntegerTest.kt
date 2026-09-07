package ml.melun.mangaview.core

import java.math.BigInteger
import java.util.Random
import org.junit.Assert.*
import org.junit.Test

class ExactIntegerTest {
    @Test fun signedLongBoundariesAreExactAndBothOverflowDirectionsFail() {
        listOf(Long.MIN_VALUE, Long.MIN_VALUE + 1, -1, 0, 1, Long.MAX_VALUE - 1, Long.MAX_VALUE).forEach {
            assertEquals(it, BigInteger.valueOf(it).toLongExact())
        }
        assertThrows(ArithmeticException::class.java) { BigInteger.valueOf(Long.MAX_VALUE).add(BigInteger.ONE).toLongExact() }
        assertThrows(ArithmeticException::class.java) { BigInteger.valueOf(Long.MIN_VALUE).subtract(BigInteger.ONE).toLongExact() }
    }

    @Test fun narrowingMatchesTheJdkReferenceForSignedValuesAcrossBitWidths() {
        val random = Random(72819)
        repeat(1000) {
            val unsigned = BigInteger(random.nextInt(130), random)
            val value = if (random.nextBoolean()) unsigned else unsigned.negate()
            val expected = runCatching { value.longValueExact() }
            if (expected.isSuccess) assertEquals(expected.getOrThrow(), value.toLongExact())
            else assertThrows(ArithmeticException::class.java) { value.toLongExact() }
        }
    }
}
