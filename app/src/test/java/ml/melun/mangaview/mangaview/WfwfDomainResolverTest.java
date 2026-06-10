package ml.melun.mangaview.mangaview;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class WfwfDomainResolverTest {
    @Test
    public void toRootReturnsEmptyForBlankUrl() {
        assertEquals("", WfwfDomainResolver.toRoot(null));
        assertEquals("", WfwfDomainResolver.toRoot(""));
        assertEquals("", WfwfDomainResolver.toRoot("   "));
    }

    @Test
    public void toRootStripsKnownPathSuffixes() {
        assertEquals("https://wfwf454.com", WfwfDomainResolver.toRoot("https://wfwf454.com/cm/"));
        assertEquals("https://ntk1.com", WfwfDomainResolver.toRoot("https://ntk1.com/manhwa/"));
    }

    @Test
    public void supportedNumberedUrlAcceptsNtkManhwaPath() {
        assertTrue(WfwfDomainResolver.isSupportedNumberedUrl("https://ntk1.com/manhwa/"));
    }

    @Test
    public void supportedNumberedUrlAcceptsCurrentNtkRoots() {
        assertTrue(WfwfDomainResolver.isSupportedNumberedUrl("https://sbxh5.com/manhwa/"));
        assertTrue(WfwfDomainResolver.isSupportedNumberedUrl("https://toonflix.app/manhwa/"));
    }

    @Test
    public void extractsUpdatedWolfAddressFromGuidePage() {
        String body = "<html><body>늑대닷컴 접속 주소 안내 <a href=\"https://wfwf451.com\">새로운 주소로 이동</a></body></html>";
        assertEquals("https://wfwf451.com", WfwfDomainResolver.extractUpdatedRootForTest(body));
    }

    @Test
    public void extractsUpdatedWolfAddressFromMinimalButtonGuidePage() {
        String body = "<html><body>https://wfwf451.com <a href=\"https://wfwf451.com\" class=\"main-btn\">go</a></body></html>";
        assertEquals("https://wfwf451.com", WfwfDomainResolver.extractUpdatedRootForTest(body));
    }

    @Test
    public void extractsCurrentNtkAddressFromGuidePage() {
        String body = "<html><body>updated <a class=\"main-btn\" href=\"https://sbxh5.com/manhwa\">go</a></body></html>";
        assertEquals("https://sbxh5.com", WfwfDomainResolver.extractUpdatedRootForTest(body));
    }

    @Test
    public void extractsToonflixAddressFromGuidePage() {
        String body = "<html><body>address changed https://toonflix.app/manhwa</body></html>";
        assertEquals("https://toonflix.app", WfwfDomainResolver.extractUpdatedRootForTest(body));
    }

    @Test
    public void guideAddressMustPointToDifferentSupportedRootBeforeVerification() {
        assertTrue(WfwfDomainResolver.shouldAcceptUpdatedRootForTest("https://wfwf450.com", "https://wfwf451.com"));
        assertTrue(WfwfDomainResolver.shouldAcceptUpdatedRootForTest("https://sbxh4.com", "https://sbxh5.com"));
        assertTrue(WfwfDomainResolver.shouldAcceptUpdatedRootForTest("https://sbxh5.com", "https://toonflix.app"));
        assertFalse(WfwfDomainResolver.shouldAcceptUpdatedRootForTest("https://wfwf451.com", "https://wfwf451.com"));
        assertFalse(WfwfDomainResolver.shouldAcceptUpdatedRootForTest("https://wfwf451.com", "https://example.com"));
    }

    @Test
    public void nearbyCandidatesInterleaveForwardAndBackward() {
        assertEquals("https://wfwf451.com", WfwfDomainResolver.candidatesForTest("https://wfwf450.com").get(0));
        assertEquals("https://wfwf449.com", WfwfDomainResolver.candidatesForTest("https://wfwf450.com").get(1));
        assertEquals("https://wfwf447.com", WfwfDomainResolver.candidatesForTest("https://wfwf450.com").get(5));
    }

    @Test
    public void domainScanSuppressionExpires() {
        WfwfDomainResolver.suppressDomainScanForTest(1000);
        assertTrue(WfwfDomainResolver.isDomainScanSuppressedForTest());
        WfwfDomainResolver.suppressDomainScanForTest(0);
        assertFalse(WfwfDomainResolver.isDomainScanSuppressedForTest());
    }
}
