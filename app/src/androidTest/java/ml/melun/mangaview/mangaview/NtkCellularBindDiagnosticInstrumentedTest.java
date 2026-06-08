package ml.melun.mangaview.mangaview;

import static org.junit.Assert.assertTrue;

import android.content.Context;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.NetworkRequest;
import android.util.Log;

import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import ml.melun.mangaview.LiveNetworkAssume;
import ml.melun.mangaview.MainApplication;

@RunWith(AndroidJUnit4.class)
public class NtkCellularBindDiagnosticInstrumentedTest {
    private static final String TAG = "ViewerPerf";

    @Before
    public void requireLiveNetworkOptIn() {
        LiveNetworkAssume.assumeEnabled();
    }

    @Test
    public void appCanDiagnoseWhetherCellularNotVpnBindingBypassesNtkBlock() throws Exception {
        Context context = ApplicationProvider.getApplicationContext();
        MainApplication.p.init(context);
        MainApplication.p.setNtkSitePreset(CustomHttpClient.NTK_WEBTOON_URL);
        MainApplication.p.setBaseMode(MTitle.base_comic);

        ConnectivityManager manager = (ConnectivityManager)
                context.getSystemService(Context.CONNECTIVITY_SERVICE);
        assertTrue("ConnectivityManager required", manager != null);

        Network before = manager.getBoundNetworkForProcess();
        CountDownLatch available = new CountDownLatch(1);
        AtomicReference<Network> cellular = new AtomicReference<>();
        ConnectivityManager.NetworkCallback callback = new ConnectivityManager.NetworkCallback() {
            @Override
            public void onAvailable(Network network) {
                cellular.compareAndSet(null, network);
                available.countDown();
            }
        };
        NetworkRequest request = new NetworkRequest.Builder()
                .addTransportType(NetworkCapabilities.TRANSPORT_CELLULAR)
                .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                .addCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN)
                .build();
        try {
            try {
                manager.requestNetwork(request, callback);
            } catch (SecurityException e) {
                Log.d(TAG, "ntk_cellular_bind unavailable: missing network request permission: " + e);
                return;
            }
            boolean found = available.await(10, TimeUnit.SECONDS);
            Log.d(TAG, "ntk_cellular_bind found=" + found + ",network=" + cellular.get());
            if(found && cellular.get() != null) {
                boolean bound = manager.bindProcessToNetwork(cellular.get());
                Log.d(TAG, "ntk_cellular_bind bound=" + bound);
                String report = MainApplication.getHttpClient()
                        .buildNtkNetworkDiagnosticReport("bound_cellular_not_vpn=" + bound);
                for(String line : report.split("\\n"))
                    Log.d(TAG, "ntk_cellular_bind_diag " + line);
            }
        } finally {
            manager.bindProcessToNetwork(before);
            try {
                manager.unregisterNetworkCallback(callback);
            } catch (Exception ignored) {
            }
        }

        assertTrue(true);
    }
}
