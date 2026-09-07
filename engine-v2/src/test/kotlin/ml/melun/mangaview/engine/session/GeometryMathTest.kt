package ml.melun.mangaview.engine.session

import java.math.BigInteger
import org.junit.Assert.assertEquals
import org.junit.Test

class GeometryMathTest {
    @Test
    fun screenUnitsMapToOneSourcePixelAtEqualWidths() {
        assertEquals(
            BigRational.of(4_294_967_296L),
            screenToSourceQ32(BigRational.of(1_024L), pageWidthPx = 100, viewportWidthPx = 100),
        )
    }

    @Test
    fun screenUnitsScaleWithPageWidth() {
        assertEquals(
            BigRational.of(8_589_934_592L),
            screenToSourceQ32(BigRational.of(1_024L), pageWidthPx = 200, viewportWidthPx = 100),
        )
    }

    @Test
    fun oneSourcePixelMapsToExpectedScreenUnits() {
        assertEquals(
            BigRational.of(512L),
            sourceToScreenUnits(
                BigRational.of(4_294_967_296L),
                pageWidthPx = 200,
                viewportWidthPx = 100,
            ),
        )
    }

    @Test
    fun sourceAndScreenConversionsRoundTripExactlyWith650By720() {
        val source = BigRational.of(BigInteger.valueOf(650L), BigInteger.valueOf(720L))
        val screen = sourceToScreenUnits(source, pageWidthPx = 650, viewportWidthPx = 720)

        assertEquals(source, screenToSourceQ32(screen, pageWidthPx = 650, viewportWidthPx = 720))
    }
}
