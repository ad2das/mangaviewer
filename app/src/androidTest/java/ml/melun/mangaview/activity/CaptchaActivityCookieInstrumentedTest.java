package ml.melun.mangaview.activity;

import static org.junit.Assert.assertTrue;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;

import androidx.test.core.app.ActivityScenario;
import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry;
import androidx.test.runner.lifecycle.Stage;
import androidx.test.uiautomator.By;
import androidx.test.uiautomator.UiDevice;
import androidx.test.uiautomator.Until;

import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.Collection;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import ml.melun.mangaview.MainApplication;
import ml.melun.mangaview.mangaview.MTitle;

@RunWith(AndroidJUnit4.class)
public class CaptchaActivityCookieInstrumentedTest {
    private static final String PACKAGE_NAME = "ml.melun.mangaview";
    private static final String CLEARANCE = "abcdefghijklmnopqrstuvwxyz1234567890";

    @Test
    public void clearanceCookieClosesCaptchaAndSyncsHttpClient() throws Exception {
        TestServer server = new TestServer();
        server.start();
        try {
            String root = "http://127.0.0.1:" + server.port();
            Context context = ApplicationProvider.getApplicationContext();
            context.getSharedPreferences("mangaView", Context.MODE_PRIVATE).edit().clear().commit();
            MainApplication.p.init(context);
            MainApplication.p.setNtkSitePreset(root);
            MainApplication.p.setBaseMode(MTitle.base_comic);
            MainApplication.getHttpClient().resetCookie();

            Intent intent = new Intent(context, CaptchaActivity.class);
            intent.putExtra("url", root + "/");

            try(ActivityScenario<CaptchaActivity> ignored = ActivityScenario.launch(intent)) {
                UiDevice device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation());
                long visibleDeadline = System.currentTimeMillis() + 10000L;
                while(System.currentTimeMillis() < visibleDeadline
                        && !device.hasObject(By.res(PACKAGE_NAME, "captchaContainer"))
                        && !MainApplication.getHttpClient().hasCloudflareClearance()
                        && activityVisible()) {
                    Thread.sleep(100L);
                }
                long deadline = System.currentTimeMillis() + 20000L;
                while(System.currentTimeMillis() < deadline && activityVisible()) {
                    if(MainApplication.getHttpClient().hasCloudflareClearance())
                        break;
                    Thread.sleep(250L);
                }
                assertTrue("Expected cf_clearance to sync into app HTTP client",
                        MainApplication.getHttpClient().hasCloudflareClearance());
                assertTrue("Expected CaptchaActivity to finish after verified clearance",
                        device.wait(Until.gone(By.res(PACKAGE_NAME, "captchaContainer")), 10000L));
            }
        } finally {
            server.close();
        }
    }

    private boolean activityVisible() {
        final boolean[] found = new boolean[1];
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
            Collection<Activity> activities = ActivityLifecycleMonitorRegistry.getInstance()
                    .getActivitiesInStage(Stage.RESUMED);
            for(Activity activity : activities) {
                if(activity instanceof CaptchaActivity) {
                    found[0] = true;
                    return;
                }
            }
        });
        return found[0];
    }

    private static final class TestServer {
        private final ExecutorService executor = Executors.newCachedThreadPool();
        private ServerSocket socket;
        private volatile boolean closed;

        void start() throws Exception {
            socket = new ServerSocket(0, 50, InetAddress.getByName("127.0.0.1"));
            executor.execute(() -> {
                while(!closed) {
                    try {
                        Socket client = socket.accept();
                        executor.execute(() -> handle(client));
                    } catch (Exception ignored) {
                    }
                }
            });
        }

        int port() {
            return socket.getLocalPort();
        }

        void close() {
            closed = true;
            try {
                socket.close();
            } catch (Exception ignored) {
            }
            executor.shutdownNow();
        }

        private void handle(Socket client) {
            try(Socket c = client) {
                BufferedReader reader = new BufferedReader(new InputStreamReader(c.getInputStream(), StandardCharsets.US_ASCII));
                String request = reader.readLine();
                while(true) {
                    String line = reader.readLine();
                    if(line == null || line.length() == 0)
                        break;
                }
                String path = "/";
                if(request != null && request.startsWith("GET ")) {
                    String[] parts = request.split(" ");
                    if(parts.length > 1)
                        path = parts[1];
                }
                if(path.startsWith("/api/manhwa-list")) {
                    write(c.getOutputStream(), "application/json", "{\"data\":[{\"sourceWorkId\":\"demo\",\"title\":\"demo\"}],\"total\":1}", false);
                } else {
                    write(c.getOutputStream(), "text/html; charset=utf-8",
                            "<!doctype html><html><body><main><a href=\"/manhwa/demo\">demo</a><a href=\"/webtoon/demo\">demo</a></main></body></html>",
                            true);
                }
            } catch (Exception ignored) {
            }
        }

        private void write(OutputStream output, String type, String body, boolean cookie) throws Exception {
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            String headers = "HTTP/1.1 200 OK\r\n"
                    + "Content-Type: " + type + "\r\n"
                    + "Content-Length: " + bytes.length + "\r\n"
                    + (cookie ? "Set-Cookie: cf_clearance=" + CLEARANCE + "; Path=/; Max-Age=3600\r\n" : "")
                    + "Connection: close\r\n\r\n";
            output.write(headers.getBytes(StandardCharsets.US_ASCII));
            output.write(bytes);
            output.flush();
        }
    }
}
