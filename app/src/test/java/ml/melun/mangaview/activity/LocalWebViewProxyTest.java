package ml.melun.mangaview.activity;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

import java.util.List;

public class LocalWebViewProxyTest {
    @Test
    public void ntkProxyTriesHostnameBeforeDirectIpFallback() {
        List<String> candidates = LocalWebViewProxy.proxyConnectCandidatesForTest(
                "sbxh3.com",
                "104.16.219.55");

        assertEquals("sbxh3.com", candidates.get(0));
        assertEquals("104.16.219.55", candidates.get(1));
    }
}
