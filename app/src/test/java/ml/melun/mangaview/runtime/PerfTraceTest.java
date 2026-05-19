package ml.melun.mangaview.runtime;

import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class PerfTraceTest {
    @Test
    public void traceLoggingStaysOffWhenDebugTagIsNotLoggable() {
        assertFalse(PerfTrace.shouldLogForTest(false));
        assertFalse(PerfTrace.shouldLogForTest(false, false));
    }

    @Test
    public void traceLoggingCanBeEnabledByDebugTag() {
        assertTrue(PerfTrace.shouldLogForTest(true));
        assertTrue(PerfTrace.shouldLogForTest(false, true));
    }

    @Test
    public void traceLoggingStillRequiresTagInDebugBuilds() {
        assertFalse(PerfTrace.shouldLogForTest(true, false));
    }
}
