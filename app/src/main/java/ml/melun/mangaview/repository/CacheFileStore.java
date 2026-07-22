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
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.ReentrantReadWriteLock;

public final class CacheFileStore {
    private static final String DIR_NAME = "structured_cache";
    private static final char[] HEX = "0123456789abcdef".toCharArray();
    private static final int MEMORY_CACHE_MAX_ENTRIES = 64;
    private static final int KEY_LOCKS_MAX_ENTRIES = 65536;
    private static final long MAX_READ_BYTES = 4L * 1024L * 1024L;
    private static final Object[] KEY_LOCKS = createKeyLocks();
    private static final ConcurrentHashMap<String, PendingWrite> PENDING_WRITES = new ConcurrentHashMap<>();
    private static final ReentrantReadWriteLock ADMISSION_LOCK = new ReentrantReadWriteLock(true);
    private static final AtomicLong STORE_GENERATION = new AtomicLong(0L);
    private static final ExecutorService DISK_WRITE_EXECUTOR =
            Executors.newSingleThreadExecutor(runnable -> {
                Thread thread = new Thread(runnable, "structured-cache-writer");
                thread.setDaemon(true);
                return thread;
            });
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
        ADMISSION_LOCK.readLock().lock();
        try {
            String cached = readMemoryInternal(key);
            if(cached != null)
                return cached;
            synchronized (lockForKey(key)) {
                cached = readMemoryInternal(key);
                if(cached != null)
                    return cached;
                logMainThreadAccess("cache_read_main_thread");
                File file = file(rootDir, key);
                if(!file.exists() || file.length() > MAX_READ_BYTES)
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
        } finally {
            ADMISSION_LOCK.readLock().unlock();
        }
    }

    public static void write(Context context, String key, String value) {
        if(context == null || key == null || value == null)
            return;
        write(fileRoot(context), key, value);
    }

    public static void writeAsync(Context context, String key, String value) {
        if(context == null || key == null || value == null)
            return;
        enqueueWrite(fileRoot(context), key, value);
    }

    public static void write(File rootDir, String key, String value) {
        if(rootDir == null || key == null || value == null)
            return;
        enqueueWrite(rootDir, key, value);
    }

    private static void enqueueWrite(File rootDir, String key, String value) {
        if(rootDir == null || key == null || value == null)
            return;
        ADMISSION_LOCK.readLock().lock();
        try {
            logMainThreadAccess("cache_write_main_thread");
            rememberMemory(key, value);
            long generation = STORE_GENERATION.get();
            PendingWrite pending = new PendingWrite(rootDir, value, generation);
            PendingWrite existing = PENDING_WRITES.putIfAbsent(key, pending);
            if(existing != null) {
                existing.state = new PendingWriteState(rootDir, value, generation);
                existing.dirty.set(true);
                return;
            }
            DISK_WRITE_EXECUTOR.execute(() -> drainPendingWrite(key, pending));
        } finally {
            ADMISSION_LOCK.readLock().unlock();
        }
    }

    private static void drainPendingWrite(String key, PendingWrite pending) {
        PendingWrite active = pending;
        while(active != null) {
            active.dirty.set(false);
            PendingWriteState state = active.state;
            writeDiskNow(state.rootDir, key, state.value, state.generation);
            if(!active.dirty.get() && PENDING_WRITES.remove(key, active))
                return;
            PendingWrite latest = PENDING_WRITES.get(key);
            active = latest == null ? null : latest;
        }
    }

    private static void writeDiskNow(File rootDir, String key, String value, long generation) {
        if(rootDir == null || key == null || value == null)
            return;
        ADMISSION_LOCK.readLock().lock();
        try {
            if(generation != STORE_GENERATION.get())
                return;
            synchronized (lockForKey(key)) {
                if(generation != STORE_GENERATION.get())
                    return;
                File file = file(rootDir, key);
                File dir = file.getParentFile();
                if(dir != null && !dir.exists() && !dir.mkdirs())
                    return;
                try {
                    writeUtf8Text(file, value);
                } catch (Exception e) {
                    ml.melun.mangaview.report.CrashReporter.record(e);
                }
            }
        } finally {
            ADMISSION_LOCK.readLock().unlock();
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
        ADMISSION_LOCK.readLock().lock();
        try {
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
        } finally {
            ADMISSION_LOCK.readLock().unlock();
        }
    }

    public static String readMemory(String key) {
        String cached = readMemoryInternal(key);
        return cached == null ? "" : cached;
    }

    public static int memoryEntryCount() {
        synchronized (MEMORY_CACHE) {
            return MEMORY_CACHE.size();
        }
    }

    public static int pendingWriteCount() {
        return PENDING_WRITES.size();
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

    /**
     * Process-local StrictFresh barrier used only by instrumentation. Waiting on the single
     * writer first prevents an older async write from recreating a deleted entry afterward.
     */
    public static int clearAllForTest(Context context) {
        if(context == null)
            return 0;
        File root = fileRoot(context);
        ADMISSION_LOCK.writeLock().lock();
        try {
            STORE_GENERATION.incrementAndGet();
            synchronized (MEMORY_CACHE) {
                MEMORY_CACHE.clear();
            }
            int deleted = deleteRecursivelyForTest(root);
            if(countFilesForTest(root) != 0)
                throw new IllegalStateException("Structured cache remained after StrictFresh clear: "
                        + root.getAbsolutePath());
            return deleted;
        } finally {
            ADMISSION_LOCK.writeLock().unlock();
        }
    }

    public static void clearKeyForTest(Context context, String key) {
        if(context == null || key == null)
            return;
        ADMISSION_LOCK.writeLock().lock();
        try {
            STORE_GENERATION.incrementAndGet();
            synchronized (lockForKey(key)) {
                File file = file(fileRoot(context), key);
                if(file.exists() && !file.delete())
                    throw new IllegalStateException("Structured cache key remained after StrictFresh clear: "
                            + key);
                forgetMemory(key);
            }
        } finally {
            ADMISSION_LOCK.writeLock().unlock();
        }
    }

    private static int deleteRecursivelyForTest(File file) {
        if(file == null || !file.exists())
            return 0;
        int deleted = 0;
        if(file.isDirectory()) {
            File[] children = file.listFiles();
            if(children != null) {
                for(File child : children)
                    deleted += deleteRecursivelyForTest(child);
            }
        }
        return file.delete() ? deleted + 1 : deleted;
    }

    private static int countFilesForTest(File file) {
        if(file == null || !file.exists())
            return 0;
        if(file.isFile())
            return 1;
        int count = 0;
        File[] children = file.listFiles();
        if(children != null) {
            for(File child : children)
                count += countFilesForTest(child);
        }
        return count;
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

    private static final class PendingWrite {
        volatile PendingWriteState state;
        final AtomicBoolean dirty = new AtomicBoolean(false);

        PendingWrite(File rootDir, String value, long generation) {
            state = new PendingWriteState(rootDir, value, generation);
        }
    }

    private static final class PendingWriteState {
        final File rootDir;
        final String value;
        final long generation;

        PendingWriteState(File rootDir, String value, long generation) {
            this.rootDir = rootDir;
            this.value = value;
            this.generation = generation;
        }
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
