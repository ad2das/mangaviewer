package ml.melun.mangaview.report;

import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.os.Process;
import android.util.Log;

import java.io.File;
import java.io.FileOutputStream;
import java.io.PrintWriter;
import java.io.StringWriter;

import ml.melun.mangaview.activity.CrashReportActivity;

public class CrashReporter implements Thread.UncaughtExceptionHandler {
    public static final String CRASH_REPORT_FILE = "last_crash_report.txt";

    private final Context context;
    private final Thread.UncaughtExceptionHandler defaultHandler;

    private CrashReporter(Context context, Thread.UncaughtExceptionHandler defaultHandler) {
        this.context = context.getApplicationContext();
        this.defaultHandler = defaultHandler;
    }

    public static void install(Context context) {
        Thread.UncaughtExceptionHandler current = Thread.getDefaultUncaughtExceptionHandler();
        if(current instanceof CrashReporter)
            return;
        Thread.setDefaultUncaughtExceptionHandler(new CrashReporter(context, current));
    }

    public static void record(Throwable throwable) {
        if(throwable == null)
            return;
        Log.e("MangaView", stackTrace(throwable));
    }

    @Override
    public void uncaughtException(Thread thread, Throwable throwable) {
        try {
            writeCrashReport(thread, throwable);
            Intent intent = new Intent(context, CrashReportActivity.class);
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
            context.startActivity(intent);
        } catch (Exception ignored) {
            if(defaultHandler != null) {
                defaultHandler.uncaughtException(thread, throwable);
                return;
            }
        }
        Process.killProcess(Process.myPid());
        System.exit(10);
    }

    private void writeCrashReport(Thread thread, Throwable throwable) throws Exception {
        File file = new File(context.getFilesDir(), CRASH_REPORT_FILE);
        try(FileOutputStream stream = new FileOutputStream(file);
            PrintWriter writer = new PrintWriter(stream)) {
            PackageInfo packageInfo = packageInfo();
            writer.println("App version: " + (packageInfo == null ? "unknown" : packageInfo.versionName));
            writer.println("Version code: " + versionCode(packageInfo));
            writer.println("Android: " + android.os.Build.VERSION.RELEASE + " (SDK " + android.os.Build.VERSION.SDK_INT + ")");
            writer.println("Device: " + android.os.Build.MANUFACTURER + " " + android.os.Build.MODEL);
            writer.println("Thread: " + thread.getName());
            writer.println();
            writer.println(stackTrace(throwable));
        }
    }

    private PackageInfo packageInfo() {
        try {
            return context.getPackageManager().getPackageInfo(context.getPackageName(), 0);
        } catch (PackageManager.NameNotFoundException e) {
            return null;
        }
    }

    private long versionCode(PackageInfo packageInfo) {
        if(packageInfo == null)
            return -1;
        if(android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.P)
            return packageInfo.getLongVersionCode();
        return packageInfo.versionCode;
    }

    public static String stackTrace(Throwable throwable) {
        StringWriter stringWriter = new StringWriter();
        PrintWriter writer = new PrintWriter(stringWriter);
        writer.println(throwable);
        for(StackTraceElement element : throwable.getStackTrace())
            writer.println("\tat " + element);
        Throwable cause = throwable.getCause();
        while(cause != null) {
            writer.println("Caused by: " + cause);
            for(StackTraceElement element : cause.getStackTrace())
                writer.println("\tat " + element);
            cause = cause.getCause();
        }
        return stringWriter.toString();
    }
}
