package ml.melun.mangaview;

import androidx.test.platform.app.InstrumentationRegistry;

import org.junit.Assume;

public final class LiveNetworkAssume {
    private LiveNetworkAssume() {
    }

    public static void assumeEnabled() {
        String enabled = InstrumentationRegistry.getArguments().getString("runLiveNetworkTests");
        Assume.assumeTrue("Live network smoke tests require -Pandroid.testInstrumentationRunnerArguments.runLiveNetworkTests=true",
                "true".equalsIgnoreCase(enabled));
    }
}
