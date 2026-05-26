package ml.melun.mangaview.reader;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class ReaderPipelinePolicyTest {
    @Test
    public void busyScrollKeepsBothDirectionsDecoded() {
        assertEquals(6, ReaderPipelinePolicy.windowBefore(true));
        assertEquals(6, ReaderPipelinePolicy.windowAfter(true));
        assertEquals(2, ReaderPipelinePolicy.decodeParallelism(true));
        assertEquals(720, ReaderPipelinePolicy.BUSY_DECODE_WIDTH);
    }

    @Test
    public void idleWindowCanFillAroundAnchorWithoutFanout() {
        assertEquals(6, ReaderPipelinePolicy.windowBefore(false));
        assertTrue(ReaderPipelinePolicy.windowAfter(false) > ReaderPipelinePolicy.windowAfter(true));
        assertEquals(2, ReaderPipelinePolicy.decodeParallelism(false));
        assertTrue(ReaderPipelinePolicy.IDLE_WINDOW_AFTER <= 10);
    }
}
