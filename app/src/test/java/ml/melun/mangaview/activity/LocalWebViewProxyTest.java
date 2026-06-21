package ml.melun.mangaview.activity;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.io.ByteArrayOutputStream;
import java.util.Arrays;
import java.util.List;

public class LocalWebViewProxyTest {
    @Test
    public void ntkProxyTriesHostnameBeforeDirectIpFallback() {
        List<String> candidates = LocalWebViewProxy.proxyConnectCandidatesForTest(
                "sbxh4.com",
                "203.0.113.10");

        assertEquals("sbxh4.com", candidates.get(0));
        assertEquals("203.0.113.10", candidates.get(1));
    }

    @Test
    public void webViewProxyRecognizesTlsClientHelloForFragmentation() {
        byte[] clientHello = new byte[] {
                0x16, 0x03, 0x01, 0x00, 0x2e, 0x01, 0x00, 0x00
        };

        assertTrue(LocalWebViewProxy.looksLikeTlsClientHelloForTest(clientHello));
    }

    @Test
    public void webViewProxyDoesNotFragmentPlainHttpBytes() {
        byte[] request = "GET / HTTP/1.1\r\n\r\n".getBytes(java.nio.charset.StandardCharsets.US_ASCII);

        assertFalse(LocalWebViewProxy.looksLikeTlsClientHelloForTest(request));
    }

    @Test
    public void webViewProxyFragmentsTlsClientHelloIntoSmallTlsRecords() throws Exception {
        byte[] clientHello = new byte[] {
                0x16, 0x03, 0x03, 0x00, 0x08,
                0x01, 0x00, 0x00, 0x04,
                0x10, 0x11, 0x12, 0x13,
                0x55, 0x66
        };
        ByteArrayOutputStream output = new ByteArrayOutputStream();

        assertTrue(LocalWebViewProxy.writeFragmentedClientHelloTlsRecordsForTest(output, clientHello));

        byte[] actual = output.toByteArray();
        byte[] expected = new byte[] {
                0x16, 0x03, 0x03, 0x00, 0x04,
                0x01, 0x00, 0x00, 0x04,
                0x16, 0x03, 0x03, 0x00, 0x04,
                0x10, 0x11, 0x12, 0x13,
                0x55, 0x66
        };
        assertTrue(Arrays.equals(expected, actual));
    }
}
