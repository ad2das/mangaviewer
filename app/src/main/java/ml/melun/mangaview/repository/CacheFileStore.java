package ml.melun.mangaview.repository;

import android.content.Context;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.security.MessageDigest;
import java.nio.charset.StandardCharsets;

public final class CacheFileStore {
    private static final String DIR_NAME = "structured_cache";
    private static final char[] HEX = "0123456789abcdef".toCharArray();

    private CacheFileStore() {
    }

    public static String read(Context context, String key) {
        if(context == null || key == null)
            return "";
        File file = file(context, key);
        if(!file.exists())
            return "";
        try (FileInputStream stream = new FileInputStream(file)) {
            return readUtf8Text(stream);
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
        return new File(new File(context.getCacheDir(), DIR_NAME), fileNameForKey(key));
    }

    static String readUtf8TextForTest(InputStream input) throws Exception {
        return readUtf8Text(input);
    }

    static String fileNameForKeyForTest(String key) {
        return fileNameForKey(key);
    }

    private static String readUtf8Text(InputStream input) throws Exception {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        int read;
        while((read = input.read(buffer)) > 0)
            output.write(buffer, 0, read);
        return output.toString(StandardCharsets.UTF_8.name());
    }

    private static String fileNameForKey(String key) {
        String safeKey = key == null ? "" : key.replaceAll("[^A-Za-z0-9_.-]", "_");
        if(safeKey.length() > 48)
            safeKey = safeKey.substring(0, 48);
        return safeKey + "_" + sha256(key == null ? "" : key) + ".json";
    }

    private static String sha256(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(value.getBytes(StandardCharsets.UTF_8));
            char[] hex = new char[hash.length * 2];
            for(int i = 0; i < hash.length; i++) {
                int valueByte = hash[i] & 0xff;
                hex[i * 2] = HEX[valueByte >>> 4];
                hex[i * 2 + 1] = HEX[valueByte & 0x0f];
            }
            return new String(hex);
        } catch (Exception e) {
            return Integer.toHexString(value.hashCode());
        }
    }
}
