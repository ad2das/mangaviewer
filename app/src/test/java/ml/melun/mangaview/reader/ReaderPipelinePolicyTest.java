package ml.melun.mangaview.reader;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class ReaderPipelinePolicyTest {
    @Test
    public void busyScrollKeepsAheadWindowDecoded() {
        assertEquals(1, ReaderPipelinePolicy.windowBefore(true));
        assertEquals(5, ReaderPipelinePolicy.windowAfter(true));
        assertEquals(2, ReaderPipelinePolicy.decodeParallelism(true));
        assertEquals(720, ReaderPipelinePolicy.BUSY_DECODE_WIDTH);
    }

    @Test
    public void idleWindowCanFillAheadWithoutFanout() {
        assertTrue(ReaderPipelinePolicy.windowAfter(false) > ReaderPipelinePolicy.windowAfter(true));
        assertEquals(2, ReaderPipelinePolicy.decodeParallelism(false));
        assertTrue(ReaderPipelinePolicy.IDLE_WINDOW_AFTER <= 8);
    }
}
