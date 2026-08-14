package ml.melun.mangaview.runtime;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public final class ViewerTelemetrySemanticPublicationGateTest {
    @Test
    public void repeatedFrameTimestampCannotRepublishTheSameSemanticState() {
        ViewerTelemetry.SemanticPublicationGate gate =
                new ViewerTelemetry.SemanticPublicationGate();

        assertTrue(gate.claim("episode-a:7:3;allReady=pending"));
        assertFalse(gate.claim("episode-a:7:3;allReady=pending"));
        assertFalse(gate.claim("episode-a:7:3;allReady=pending"));
    }

    @Test
    public void identityAndMilestoneTransitionsRemainObservable() {
        ViewerTelemetry.SemanticPublicationGate gate =
                new ViewerTelemetry.SemanticPublicationGate();

        assertTrue(gate.claim("episode-a:7:3;allReady=pending"));
        assertTrue(gate.claim("episode-a:8:3;allReady=pending"));
        assertTrue(gate.claim("episode-b:0:3;allReady=pending"));
        assertTrue(gate.claim("episode-b:0:3;allReady=12:99"));
        assertTrue(gate.claim("episode-b:0:3;allReady=12:99;adjacent=p0"));
    }

    @Test
    public void lifecycleResetAllowsTheSameIdentityToBeRepublishedOnce() {
        ViewerTelemetry.SemanticPublicationGate gate =
                new ViewerTelemetry.SemanticPublicationGate();

        assertTrue(gate.claim("episode-a:7:3"));
        assertFalse(gate.claim("episode-a:7:3"));
        gate.reset();
        assertTrue(gate.claim("episode-a:7:3"));
        assertFalse(gate.claim("episode-a:7:3"));
    }

    @Test
    public void lifecycleResetInvalidatesAnAlreadyQueuedPublication() {
        ViewerTelemetry.SemanticPublicationGate gate =
                new ViewerTelemetry.SemanticPublicationGate();

        long queued = gate.claimVersion("episode-a:7:3");
        assertTrue(queued > 0L);
        assertTrue(gate.isCurrent("episode-a:7:3", queued));

        gate.reset();

        assertFalse(gate.isCurrent("episode-a:7:3", queued));
        long resumed = gate.claimVersion("episode-a:7:3");
        assertTrue(resumed > 0L);
        assertTrue(gate.isCurrent("episode-a:7:3", resumed));
    }

    @Test
    public void newerSemanticClaimInvalidatesAnOlderQueuedPublication() {
        ViewerTelemetry.SemanticPublicationGate gate =
                new ViewerTelemetry.SemanticPublicationGate();

        long oldPage = gate.claimVersion("episode-a:7:3");
        long newPage = gate.claimVersion("episode-a:8:3");

        assertFalse(gate.isCurrent("episode-a:7:3", oldPage));
        assertTrue(gate.isCurrent("episode-a:8:3", newPage));
    }
}
