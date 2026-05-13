package ml.melun.mangaview;

import org.junit.Test;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;

import static org.junit.Assert.assertEquals;

public class UtilsTest {
    @Test
    public void readTextStreamUsesUtf8() {
        String text = "{\"title\":\"데스러버\"}";

        assertEquals(text, Utils.readTextStreamForTest(new ByteArrayInputStream(text.getBytes(StandardCharsets.UTF_8))));
    }

    @Test
    public void documentNamesSortWithNullsLast() {
        assertEquals(0, Utils.compareDocumentNamesForTest(null, null));
        assertEquals(1, Utils.compareDocumentNamesForTest(null, "0001.title"));
        assertEquals(-1, Utils.compareDocumentNamesForTest("0001.title", null));
        assertEquals(-1, Utils.compareDocumentNamesForTest("0001.title", "0002.title"));
    }
}
