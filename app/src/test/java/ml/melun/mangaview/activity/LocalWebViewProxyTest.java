package ml.melun.mangaview.activity;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

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
}
