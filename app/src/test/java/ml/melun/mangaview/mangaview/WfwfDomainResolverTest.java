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
}
