package ml.melun.mangaview.reader;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class ReaderPipelinePolicyTest {
    @Test
    public void busyScrollKeepsWorkWindowSmall() {
        assertEquals(1, ReaderPipelinePolicy.windowBefore(true));
        assertEquals(3, ReaderPipelinePolicy.windowAfter(true));
        assertEquals(1, ReaderPipelinePolicy.decodeParallelism(true));
        assertEquals(320, ReaderPipelinePolicy.BUSY_DECODE_WIDTH);
    }

    @Test
    public void idleWindowCanFillAheadWithoutFanout() {
        assertTrue(ReaderPipelinePolicy.windowAfter(false) > ReaderPipelinePolicy.windowAfter(true));
        assertEquals(2, ReaderPipelinePolicy.decodeParallelism(false));
    }
}
