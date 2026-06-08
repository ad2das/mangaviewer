package ml.melun.mangaview.report;

import android.content.Context;
import android.util.Log;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

import ml.melun.mangaview.runtime.AppDispatchers;

public final class DiagnosticLog {
    private static final String FILE_NAME = "runtime_diagnostics.log";
    private static final int MAX_BYTES = 256 * 1024;
    private static final int TRIM_BYTES = 192 * 1024;
    private static final Object LOCK = new Object();

    private DiagnosticLog() {
    }

    public static void record(Context context, String tag, String message) {
        if(context == null || message == null)
            return;
        Context app = context.getApplicationContext();
        String line = timestamp() + " " + safe(tag) + " " + sanitize(message) + "\n";
        AppDispatchers.runIo(() -> append(app, line));
    }

    public static String readRecent(Context context) {
        if(context == null)
            return "";
        synchronized (LOCK) {
            try {
                File file = logFile(context.getApplicationContext());
                if(!file.exists())
                    return "";
                try(FileInputStream stream = new FileInputStream(file)) {
                    ByteArrayOutputStream out = new ByteArrayOutputStream();
                    byte[] buffer = new byte[8192];
                    int read;
                    while((read = stream.read(buffer)) != -1)
                        out.write(buffer, 0, read);
                    return out.toString("UTF-8");
                }
            } catch (Exception e) {
                Log.d("DiagnosticLog", "read failed", e);
                return "";
            }
        }
    }

    private static void append(Context context, String line) {
        synchronized (LOCK) {
            try {
                File file = logFile(context);
                File parent = file.getParentFile();
                if(parent != null && !parent.exists())
                    parent.mkdirs();
                trimIfNeeded(file);
                try(FileOutputStream stream = new FileOutputStream(file, true)) {
                    stream.write(line.getBytes(StandardCharsets.UTF_8));
                }
            } catch (Exception e) {
                Log.d("DiagnosticLog", "append failed", e);
            }
        }
    }

    private static void trimIfNeeded(File file) throws Exception {
        if(!file.exists() || file.length() <= MAX_BYTES)
            return;
        byte[] bytes;
        try(FileInputStream stream = new FileInputStream(file)) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buffer = new byte[8192];
            int read;
            while((read = stream.read(buffer)) != -1)
                out.write(buffer, 0, read);
            bytes = out.toByteArray();
        }
        int keep = Math.min(TRIM_BYTES, bytes.length);
        try(FileOutputStream stream = new FileOutputStream(file, false)) {
            stream.write(bytes, bytes.length - keep, keep);
        }
    }

    private static File logFile(Context context) {
        File root = context.getExternalFilesDir(null);
        if(root == null)
            root = context.getFilesDir();
        return new File(new File(root, "diagnostics"), FILE_NAME);
    }

    private static String timestamp() {
        return new SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US).format(new Date());
    }

    private static String safe(String value) {
        return value == null ? "unknown" : sanitize(value);
    }

    private static String sanitize(String value) {
        String sanitized = value.replace('\n', ' ').replace('\r', ' ').replace('\t', ' ');
        if(sanitized.length() > 1200)
            return sanitized.substring(0, 1200) + "...";
        return sanitized;
    }
}
