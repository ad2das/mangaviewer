package ml.melun.mangaview.mangaview;

import static org.junit.Assert.assertTrue;

import android.content.Context;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.util.Log;

import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import ml.melun.mangaview.LiveNetworkAssume;
import ml.melun.mangaview.MainApplication;

@RunWith(AndroidJUnit4.class)
public class NtkNetworkDiagnosticInstrumentedTest {
    private static final String TAG = "ViewerPerf";

    @Before
    public void requireLiveNetworkOptIn() {
        LiveNetworkAssume.assumeEnabled();
    }

    @Test
    public void sbxh4NetworkDiagnosticReportIncludesNtkProbeResults() {
        Context context = ApplicationProvider.getApplicationContext();
        MainApplication.p.init(context);
        String siteRoot = InstrumentationRegistry.getArguments()
                .getString("ntkSiteRoot", CustomHttpClient.NTK_WEBTOON_URL);
        MainApplication.p.setNtkSitePreset(siteRoot);
        MainApplication.p.setBaseMode(MTitle.base_comic);

        String report = MainApplication.getHttpClient()
                .buildNtkNetworkDiagnosticReport(networkSummary(context));
        for(String line : report.split("\\n"))
            Log.d(TAG, "ntk_network_diag " + line);

        assertTrue(report.contains("root: " + siteRoot));
        assertTrue(report.contains("ntk_quic_sni:"));
        assertTrue(report.contains("ntk_api_direct:"));
        assertTrue(report.contains("interpretation:"));
    }

    private static String networkSummary(Context context) {
        try {
            ConnectivityManager manager = (ConnectivityManager)
                    context.getSystemService(Context.CONNECTIVITY_SERVICE);
            Network network = manager == null ? null : manager.getActiveNetwork();
            NetworkCapabilities caps = network == null ? null : manager.getNetworkCapabilities(network);
            if(caps == null)
                return "unknown";
            StringBuilder builder = new StringBuilder();
            if(caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR))
                builder.append("cellular");
            if(caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI))
                append(builder, "wifi");
            if(caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN))
                append(builder, "vpn");
            append(builder, "validated=" + caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED));
            append(builder, "internet=" + caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET));
            return builder.toString();
        } catch (Exception e) {
            return "unknown:" + e.getClass().getSimpleName();
        }
    }

    private static void append(StringBuilder builder, String value) {
        if(builder.length() > 0)
            builder.append(',');
        builder.append(value);
    }
}
