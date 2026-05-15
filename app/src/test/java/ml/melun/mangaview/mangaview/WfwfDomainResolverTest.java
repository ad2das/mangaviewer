package ml.melun.mangaview.mangaview;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
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
        assertEquals("https://wfwf450.com", WfwfDomainResolver.toRoot("https://wfwf450.com/cm/"));
        assertEquals("https://ntk1.com", WfwfDomainResolver.toRoot("https://ntk1.com/manhwa/"));
    }

    @Test
    public void supportedNumberedUrlAcceptsNtkManhwaPath() {
        assertTrue(WfwfDomainResolver.isSupportedNumberedUrl("https://ntk1.com/manhwa/"));
    }

    @Test
    public void extractsUpdatedWolfAddressFromGuidePage() {
        String body = "<html><body>늑대닷컴 접속 주소 안내 <a href=\"https://wfwf451.com\">새로운 주소로 이동</a></body></html>";
        assertEquals("https://wfwf451.com", WfwfDomainResolver.extractUpdatedRootForTest(body));
    }

    @Test
    public void nearbyCandidatesInterleaveForwardAndBackward() {
        assertEquals("https://wfwf451.com", WfwfDomainResolver.candidatesForTest("https://wfwf450.com").get(0));
        assertEquals("https://wfwf449.com", WfwfDomainResolver.candidatesForTest("https://wfwf450.com").get(1));
        assertEquals("https://wfwf447.com", WfwfDomainResolver.candidatesForTest("https://wfwf450.com").get(5));
    }
}
