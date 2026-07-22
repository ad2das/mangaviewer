package ml.melun.mangaview.reader

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class NtkFixedPacingNativePreflightInstrumentedTest {
    @Test
    fun productionFixedPacingSelfTestsPassBeforePhysicalQualification() {
        assertNull(NtkFixedPacingNativePreflight.violation())
    }
}
