package ml.melun.mangaview.repository;

import android.content.Context;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;

public final class CacheFileStore {
    private static final String DIR_NAME = "structured_cache";

    private CacheFileStore() {
    }

    public static String read(Context context, String key) {
        if(context == null || key == null)
            return "";
        File file = file(context, key);
        if(!file.exists())
            return "";
        try (FileInputStream stream = new FileInputStream(file)) {
            byte[] data = new byte[(int) file.length()];
            int read = stream.read(data);
            if(read <= 0)
                return "";
            return new String(data, 0, read, StandardCharsets.UTF_8);
        } catch (Exception e) {
            ml.melun.mangaview.report.CrashReporter.record(e);
            return "";
        }
    }

    public static void write(Context context, String key, String value) {
        if(context == null || key == null || value == null)
            return;
        File file = file(context, key);
        File dir = file.getParentFile();
        if(dir != null && !dir.exists() && !dir.mkdirs())
            return;
        try (FileOutputStream stream = new FileOutputStream(file, false)) {
            stream.write(value.getBytes(StandardCharsets.UTF_8));
            stream.flush();
        } catch (Exception e) {
            ml.melun.mangaview.report.CrashReporter.record(e);
        }
    }

    public static void delete(Context context, String key) {
        if(context == null || key == null)
            return;
        try {
            File file = file(context, key);
            if(file.exists())
                file.delete();
        } catch (Exception e) {
            ml.melun.mangaview.report.CrashReporter.record(e);
        }
    }

    private static File file(Context context, String key) {
        String safeKey = key.replaceAll("[^A-Za-z0-9_.-]", "_");
        return new File(new File(context.getCacheDir(), DIR_NAME), safeKey + ".json");
    }
}
