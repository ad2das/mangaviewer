package ml.melun.mangaview.repository;

import android.content.Context;
import android.os.Looper;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.security.MessageDigest;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;

public final class CacheFileStore {
    private static final String DIR_NAME = "structured_cache";
    private static final char[] HEX = "0123456789abcdef".toCharArray();
    private static final int MEMORY_CACHE_MAX_ENTRIES = 64;
    private static final int KEY_LOCKS_MAX_ENTRIES = 256;
    private static final long MAX_READ_BYTES = 4L * 1024L * 1024L;
    private static final Object[] KEY_LOCKS = createKeyLocks();
    private static final Map<String, String> MEMORY_CACHE = new LinkedHashMap<String, String>(MEMORY_CACHE_MAX_ENTRIES, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, String> eldest) {
            return size() > MEMORY_CACHE_MAX_ENTRIES;
        }
    };

    private CacheFileStore() {
    }

    public static String read(Context context, String key) {
        if(context == null || key == null)
            return "";
        return read(fileRoot(context), key);
    }

    public static String read(File rootDir, String key) {
        if(rootDir == null || key == null)
            return "";
        String cached = readMemoryInternal(key);
        if(cached != null)
            return cached;
        synchronized (lockForKey(key)) {
            cached = readMemoryInternal(key);
            if(cached != null)
                return cached;
            logMainThreadAccess("cache_read_main_thread");
            File file = file(rootDir, key);
            if(!file.exists())
                return "";
            if(file.length() > MAX_READ_BYTES)
                return "";
            try (FileInputStream stream = new FileInputStream(file)) {
                String value = readUtf8Text(stream);
                rememberMemory(key, value);
                return value;
            } catch (Exception e) {
                ml.melun.mangaview.report.CrashReporter.record(e);
                return "";
            }
        }
    }

    public static void write(Context context, String key, String value) {
        if(context == null || key == null || value == null)
            return;
        write(fileRoot(context), key, value);
    }

    public static void write(File rootDir, String key, String value) {
        if(rootDir == null || key == null || value == null)
            return;
        logMainThreadAccess("cache_write_main_thread");
        synchronized (lockForKey(key)) {
            File file = file(rootDir, key);
            File dir = file.getParentFile();
            if(dir != null && !dir.exists() && !dir.mkdirs())
                return;
            try {
                writeUtf8Text(file, value);
                rememberMemory(key, value);
            } catch (Exception e) {
                ml.melun.mangaview.report.CrashReporter.record(e);
            }
        }
    }

    public static void delete(Context context, String key) {
        if(context == null || key == null)
            return;
        delete(fileRoot(context), key);
    }

    public static void delete(File rootDir, String key) {
        if(rootDir == null || key == null)
            return;
        logMainThreadAccess("cache_delete_main_thread");
        synchronized (lockForKey(key)) {
            try {
                File file = file(rootDir, key);
                if(file.exists())
                    file.delete();
                forgetMemory(key);
            } catch (Exception e) {
                ml.melun.mangaview.report.CrashReporter.record(e);
            }
        }
    }

    public static String readMemory(String key) {
        String cached = readMemoryInternal(key);
        return cached == null ? "" : cached;
    }

    public static File fileRoot(Context context) {
        if(context == null)
            return null;
        return new File(context.getCacheDir(), DIR_NAME);
    }

    private static File file(File rootDir, String key) {
        return new File(rootDir, fileNameForKey(key));
    }

    static String readUtf8TextForTest(InputStream input) throws Exception {
        return readUtf8Text(input);
    }

    static String fileNameForKeyForTest(String key) {
        return fileNameForKey(key);
    }

    static void writeUtf8TextForTest(File file, String value) throws Exception {
        writeUtf8Text(file, value);
    }

    static void clearMemoryForTest() {
        synchronized (MEMORY_CACHE) {
            MEMORY_CACHE.clear();
        }
    }

    static void rememberMemoryForTest(String key, String value) {
        rememberMemory(key, value);
    }

    static String readMemoryForTest(String key) {
        return readMemoryInternal(key);
    }

    static int keyLockCountForTest() {
        return KEY_LOCKS.length;
    }

    static int keyLocksMaxEntriesForTest() {
        return KEY_LOCKS_MAX_ENTRIES;
    }

    static long maxReadBytesForTest() {
        return MAX_READ_BYTES;
    }

    private static void rememberMemory(String key, String value) {
        if(key == null || value == null)
            return;
        synchronized (MEMORY_CACHE) {
            MEMORY_CACHE.put(key, value);
        }
    }

    private static String readMemoryInternal(String key) {
        if(key == null)
            return null;
        synchronized (MEMORY_CACHE) {
            return MEMORY_CACHE.get(key);
        }
    }

    private static Object lockForKey(String key) {
        String lockKey = key == null ? "" : key;
        int index = (lockKey.hashCode() & 0x7fffffff) % KEY_LOCKS.length;
        return KEY_LOCKS[index];
    }

    private static Object[] createKeyLocks() {
        Object[] locks = new Object[KEY_LOCKS_MAX_ENTRIES];
        for(int i = 0; i < locks.length; i++)
            locks[i] = new Object();
        return locks;
    }

    private static void forgetMemory(String key) {
        if(key == null)
            return;
        synchronized (MEMORY_CACHE) {
            MEMORY_CACHE.remove(key);
        }
    }

    private static void logMainThreadAccess(String metric) {
        try {
            if(Looper.myLooper() == Looper.getMainLooper())
                ml.melun.mangaview.glide.ViewerWarmupManager.logMetric(metric, 1L);
        } catch (RuntimeException ignored) {
            // Plain JVM tests do not provide Android Looper internals.
        }
    }

    private static String readUtf8Text(InputStream input) throws Exception {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        int read;
        while((read = input.read(buffer)) > 0)
            output.write(buffer, 0, read);
        return output.toString(StandardCharsets.UTF_8.name());
    }

    private static void writeUtf8Text(File file, String value) throws Exception {
        File dir = file.getParentFile();
        File temp = File.createTempFile(file.getName(), ".tmp", dir);
        File backup = new File(file.getAbsolutePath() + ".bak");
        try {
            try (FileOutputStream stream = new FileOutputStream(temp, false)) {
                stream.write(value.getBytes(StandardCharsets.UTF_8));
                stream.flush();
            }
            if(backup.exists())
                backup.delete();
            boolean hadExisting = file.exists();
            if(hadExisting && !file.renameTo(backup))
                throw new IllegalStateException("Failed to backup cache file");
            if(!temp.renameTo(file)) {
                if(hadExisting)
                    backup.renameTo(file);
                throw new IllegalStateException("Failed to replace cache file");
            }
            if(backup.exists())
                backup.delete();
        } finally {
            if(temp.exists())
                temp.delete();
        }
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
