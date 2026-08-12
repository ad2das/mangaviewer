package ml.melun.mangaview.mangaview;

import org.junit.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import okhttp3.Call;
import okhttp3.OkHttpClient;
import okhttp3.Request;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/** Pure JVM race gates for the flight-owned strict physical-call registry. */
public final class NtkStrictCallRegistryTest {
    private static final String PATH = "/webtoon/work/episode";
    private static final String ORIGIN = "https://newtoki1.org";

    @Test
    public void cancellationOwnsRegisteredAndLateSubmittedCalls() {
        CustomHttpClient.NtkStrictCallRegistry registry = registry(7L);
        Call registered = newCall("registered");
        assertTrue(registry.register(registered));
        assertEquals(1, registry.activeCallCount());

        registry.cancelAll();
        assertTrue(registry.isCancelled());
        assertTrue(registered.isCanceled());
        assertEquals(0, registry.activeCallCount());

        Call late = newCall("late");
        assertFalse(registry.register(late));
        assertTrue(late.isCanceled());
        registry.cancelAll();
        assertEquals(0, registry.activeCallCount());
    }

    @Test
    public void detachClosesAdmissionBeforeOffMainCallCancellationRuns() {
        CustomHttpClient.NtkStrictCallRegistry registry = registry(8L);
        Call registered = newCall("detached");
        assertTrue(registry.register(registered));

        Runnable cancellationWork = registry.markCancelledAndDetachCalls();
        assertTrue(registry.isCancelled());
        assertEquals(0, registry.activeCallCount());
        assertFalse(registered.isCanceled());

        Call late = newCall("late-after-detach");
        assertFalse(registry.register(late));
        assertTrue(late.isCanceled());

        cancellationWork.run();
        assertTrue(registered.isCanceled());
    }

    @Test
    public void concurrentSubmitAndCancelAlwaysEndsCancelledAndEmpty() throws Exception {
        for(int iteration = 0; iteration < 100; iteration++) {
            CustomHttpClient.NtkStrictCallRegistry registry = registry(iteration + 1L);
            Call call = newCall("race-" + iteration);
            CountDownLatch start = new CountDownLatch(1);
            Thread submitter = new Thread(() -> {
                await(start);
                registry.register(call);
            }, "ntk-registry-submit");
            Thread canceller = new Thread(() -> {
                await(start);
                registry.cancelAll();
            }, "ntk-registry-cancel");
            submitter.start();
            canceller.start();
            start.countDown();
            submitter.join(TimeUnit.SECONDS.toMillis(2));
            canceller.join(TimeUnit.SECONDS.toMillis(2));

            assertFalse(submitter.isAlive());
            assertFalse(canceller.isAlive());
            assertTrue(registry.isCancelled());
            assertTrue(call.isCanceled());
            assertEquals(0, registry.activeCallCount());
        }
    }

    @Test
    public void cancellationFencesLaterStatePublication() {
        CustomHttpClient.NtkStrictCallRegistry registry = registry(41L);
        AtomicBoolean published = new AtomicBoolean(false);
        assertTrue(registry.publishIfActive(() -> published.set(true)));
        assertTrue(published.get());

        registry.cancelAll();
        published.set(false);
        assertFalse(registry.publishIfActive(() -> published.set(true)));
        assertFalse(published.get());
    }

    @Test
    public void ownershipBindsNormalizedPathAndViewerGeneration() {
        CustomHttpClient.NtkStrictCallRegistry registry = registry(19L);
        assertTrue(registry.owns("/webtoon/work/episode?x=1", 19L));
        assertFalse(registry.owns(PATH, 20L));
        assertFalse(registry.owns("/webtoon/work/other", 19L));
    }

    @Test
    public void routeSnapshotBindsOriginAndTransportForTheWholeFlight() {
        CustomHttpClient.NtkStrictCallRegistry registry = new CustomHttpClient.NtkStrictCallRegistry(
                PATH,
                29L,
                new CustomHttpClient.NtkStrictRouteSnapshot(ORIGIN, true, false, 101L));

        assertEquals(ORIGIN, registry.episodeOrigin());
        assertTrue(registry.cellularResilientTransport());
        assertFalse(registry.directWifiTransport());
        assertEquals(101L, registry.networkHandle());
        assertTrue(registry.ownsRequestOrigin(ORIGIN + "/api/nv-issue"));
        assertFalse(registry.ownsRequestOrigin("https://sbxh9.com/api/nv-issue"));
        assertFalse(registry.ownsRequestOrigin("not-a-url"));
        assertFalse(registry.ownsRequestOrigin("http://newtoki1.org/api/nv-issue"));
    }

    @Test
    public void routeSnapshotRejectsNonCanonicalOrAmbiguousOrigins() {
        assertInvalidOrigin("http://newtoki1.org");
        assertInvalidOrigin("https://user@newtoki1.org");
        assertInvalidOrigin("https://newtoki1.org:443");
        assertInvalidOrigin("https://newtoki1.org/path");
        assertInvalidOrigin("https://newtoki1.org/?query=1");
        try {
            new CustomHttpClient.NtkStrictRouteSnapshot(ORIGIN, true, true, 1L);
            throw new AssertionError("Ambiguous strict transport was accepted");
        } catch(IllegalArgumentException expected) {
            // Expected.
        }
    }

    @Test
    public void strictResponseIdentityDecodesUnicodeSlugFromOnWireUrl() {
        String expected = "/webtoon/책벌레의-하극상-제4부/895120";
        String encoded = "https://example.invalid/webtoon/"
                + "%EC%B1%85%EB%B2%8C%EB%A0%88%EC%9D%98-%ED%95%98%EA%B7%B9%EC%83%81-"
                + "%EC%A0%9C4%EB%B6%80/895120?transport=rsc#ignored";

        assertEquals(expected, CustomHttpClient.strictResponseEpisodePath(encoded));
        assertTrue(new CustomHttpClient.NtkStrictCallRegistry(expected, 23L, route())
                .owns(CustomHttpClient.strictResponseEpisodePath(encoded), 23L));
    }

    private static CustomHttpClient.NtkStrictCallRegistry registry(long generation) {
        return new CustomHttpClient.NtkStrictCallRegistry(PATH, generation, route());
    }

    private static CustomHttpClient.NtkStrictRouteSnapshot route() {
        return new CustomHttpClient.NtkStrictRouteSnapshot(ORIGIN, false, true, 100L);
    }

    private static void assertInvalidOrigin(String origin) {
        try {
            new CustomHttpClient.NtkStrictRouteSnapshot(origin, false, true, 100L);
            throw new AssertionError("Invalid strict origin was accepted: " + origin);
        } catch(IllegalArgumentException expected) {
            // Expected.
        }
    }

    private static Call newCall(String suffix) {
        Request request = new Request.Builder()
                .url("https://example.invalid/" + suffix)
                .build();
        return new OkHttpClient().newCall(request);
    }

    private static void await(CountDownLatch latch) {
        try {
            assertTrue(latch.await(2, TimeUnit.SECONDS));
        } catch(InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AssertionError(e);
        }
    }
}
