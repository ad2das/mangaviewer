package ml.melun.mangaview;

import android.content.Context;
import android.content.Intent;
import android.util.Log;
import android.webkit.WebView;

import java.util.Locale;

import static ml.melun.mangaview.MainApplication.getHttpClient;

public final class NtkDeviceIdentityManager {
    private static final String[] MODELS = {
            "SM-G981B", "SM-S928N", "SM-G998N", "SM-F946N", "SM-X910",
            "SM-A546N", "SM-N986N", "SM-M546B", "SM-G975N", "SM-N971N", "SM-A736B"
    };

    private NtkDeviceIdentityManager() {
    }

    public static String changeDeviceInfo(Context context, boolean restartApp) {
        String newAgent = generateRandomUserAgent(context);
        getHttpClient().resetCookie();
        getHttpClient().clearAllWebViewData();
        getHttpClient().setNtkDeviceIdentityUserAgent(newAgent);
        Log.d("NtkDeviceIdentity", "changedUserAgent=" + newAgent + ",restart=" + restartApp);
        if(restartApp)
            restartApp(context);
        return newAgent;
    }

    public static String resetDeviceInfo(Context context, boolean restartApp) {
        String defaultAgent = defaultUserAgent(context);
        getHttpClient().resetCookie();
        getHttpClient().clearAllWebViewData();
        getHttpClient().setNtkDeviceIdentityUserAgent(defaultAgent);
        Log.d("NtkDeviceIdentity", "resetUserAgent=" + defaultAgent + ",restart=" + restartApp);
        if(restartApp)
            restartApp(context);
        return defaultAgent;
    }

    public static boolean isTrash0607Block(String body) {
        return body != null && body.toLowerCase(Locale.ROOT).contains("trash0607");
    }

    private static void restartApp(Context context) {
        if(context == null)
            return;
        Intent intent = context.getPackageManager().getLaunchIntentForPackage(context.getPackageName());
        if(intent != null) {
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
            context.startActivity(intent);
        }
        android.os.Process.killProcess(android.os.Process.myPid());
    }

    private static String generateRandomUserAgent(Context context) {
        String model = MODELS[(int)(Math.random() * MODELS.length)];
        try {
            if(context != null) {
                WebView webView = new WebView(context);
                String defaultUA = webView.getSettings().getUserAgentString();
                webView.destroy();
                if(defaultUA != null && defaultUA.length() > 0) {
                    String cleaned = defaultUA
                            .replace("; wv", "")
                            .replace(" wv", "")
                            .replace("Version/4.0 ", "");
                    int androidIdx = cleaned.indexOf("Android ");
                    int modelEnd = cleaned.indexOf(")", androidIdx);
                    if(androidIdx >= 0 && modelEnd > androidIdx) {
                        int modelStart = cleaned.lastIndexOf(";", modelEnd);
                        if(modelStart > androidIdx)
                            return cleaned.substring(0, modelStart + 1) + " " + model + cleaned.substring(modelEnd);
                    }
                    return cleaned;
                }
            }
        } catch (Exception e) {
            ml.melun.mangaview.report.CrashReporter.record(e);
        }
        return "Mozilla/5.0 (Linux; Android 13; " + model + ") AppleWebKit/537.36 (KHTML, like Gecko) Chrome/116.0.0.0 Mobile Safari/537.36";
    }

    private static String defaultUserAgent(Context context) {
        try {
            if(context != null) {
                WebView webView = new WebView(context);
                String defaultUA = webView.getSettings().getUserAgentString();
                webView.destroy();
                if(defaultUA != null && defaultUA.trim().length() > 0)
                    return defaultUA.trim();
            }
        } catch (Exception e) {
            ml.melun.mangaview.report.CrashReporter.record(e);
        }
        return "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/116.0.0.0 Mobile Safari/537.36";
    }
}
