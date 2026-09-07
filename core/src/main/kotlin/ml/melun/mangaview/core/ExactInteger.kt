package ml.melun.mangaview.core

import java.math.BigInteger

/** Checked narrowing using APIs available on every supported Android version. */
fun BigInteger.toLongExact(): Long {
    if (bitLength() > 63) throw ArithmeticException("BigInteger out of long range")
    return toLong()
}
