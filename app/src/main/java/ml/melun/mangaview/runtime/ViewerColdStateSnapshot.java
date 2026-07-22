package ml.melun.mangaview.runtime;

import android.content.Context;

import java.io.File;
import java.util.ArrayDeque;

import ml.melun.mangaview.MainApplication;
import ml.melun.mangaview.repository.CacheFileStore;

/** Earliest-process snapshot used to prove app-owned viewer caches are cold. */
public final class ViewerColdStateSnapshot {
    private static final int MAX_EMPTY_DIRECTORY_PROBES = 32;
    private static final String READER_CACHE = "reader_image_cache_v1";
    private static final String READER_STRICT_SPOOL = "reader_strict_spool_v1";
    private static final String GLIDE_CACHE = "image_manager_disk_cache";
    private static final String CONTENT_CACHE = "structured_cache";

    public final int memoryCacheEntries;
    public final long diskCacheFiles;
    public final long diskCacheBytes;
    public final int contentCacheEntries;
    public final int activeRequests;
    public final int activeDecodes;
    public final String clientState;

    private ViewerColdStateSnapshot(
            int memoryCacheEntries,
            long diskCacheFiles,
            long diskCacheBytes,
            int contentCacheEntries,
            int activeRequests,
            int activeDecodes,
            String clientState) {
        this.memoryCacheEntries = memoryCacheEntries;
        this.diskCacheFiles = diskCacheFiles;
        this.diskCacheBytes = diskCacheBytes;
        this.contentCacheEntries = contentCacheEntries;
        this.activeRequests = activeRequests;
        this.activeDecodes = activeDecodes;
        this.clientState = clientState;
    }

    /**
     * Must run before creating Glide or the viewer HTTP stack. Qualification only needs an exact
     * empty/non-empty answer. Never inventory a warm user's complete cache on the startup main
     * thread: stop at the first file and fail closed if an empty-directory probe is inconclusive.
     */
    public static ViewerColdStateSnapshot captureAtProcessStart(Context context) {
        Context appContext = context.getApplicationContext();
        File cacheRoot = appContext.getCacheDir();
        FileStats reader = stats(new File(cacheRoot, READER_CACHE));
        FileStats glide = stats(new File(cacheRoot, GLIDE_CACHE));
        // In-flight strict bodies are deliberately staged outside cacheDir so Android cache
        // eviction cannot truncate them. They are still app-owned disk state and must reject a
        // formal cold claim until pm-clear/StrictFresh proves this no-backup tree empty.
        FileStats strictSpool = stats(new File(
                appContext.getNoBackupFilesDir(), READER_STRICT_SPOOL));
        FileStats viewerDisk = combine(reader, glide, strictSpool);
        FileStats content = stats(new File(cacheRoot, CONTENT_CACHE));
        return new ViewerColdStateSnapshot(
                CacheFileStore.memoryEntryCount(),
                viewerDisk.files,
                viewerDisk.bytes,
                safeInt(content.files + CacheFileStore.pendingWriteCount()),
                ViewerTelemetry.activeRequestCount(),
                ViewerTelemetry.activeDecodeCount(),
                MainApplication.httpClient == null ? "not_created" : "already_created");
    }

    public void record() {
        ViewerTelemetry.coldState(
                memoryCacheEntries,
                diskCacheFiles,
                diskCacheBytes,
                contentCacheEntries,
                activeRequests,
                activeDecodes,
                clientState);
    }

    private static FileStats stats(File root) {
        if(root == null || !root.exists())
            return FileStats.EMPTY;
        ArrayDeque<File> pending = new ArrayDeque<>();
        pending.add(root);
        int probedDirectories = 0;
        while(!pending.isEmpty()) {
            if(++probedDirectories > MAX_EMPTY_DIRECTORY_PROBES)
                return FileStats.NON_EMPTY_OR_UNKNOWN;
            File next = pending.removeFirst();
            File[] children = next.listFiles();
            if(children == null)
                return FileStats.NON_EMPTY_OR_UNKNOWN;
            for(File child : children) {
                if(child.isDirectory()) {
                    pending.addLast(child);
                } else {
                    // A single file is enough to reject a cold claim. The byte value is a lower
                    // bound and is never presented as a warm-cache inventory.
                    return new FileStats(1L, Math.max(0L, child.length()));
                }
            }
        }
        return FileStats.EMPTY;
    }

    private static int safeInt(long value) {
        return value > Integer.MAX_VALUE ? Integer.MAX_VALUE : (int) Math.max(0L, value);
    }

    static boolean isCacheTreeEmptyForTest(File root) {
        return stats(root).files == 0L;
    }

    static long[] combinedCacheStatsForTest(File... roots) {
        FileStats[] values = new FileStats[roots.length];
        for(int index = 0; index < roots.length; index++)
            values[index] = stats(roots[index]);
        FileStats combined = combine(values);
        return new long[] { combined.files, combined.bytes };
    }

    private static FileStats combine(FileStats... values) {
        long files = 0L;
        long bytes = 0L;
        for(FileStats value : values) {
            files += value.files;
            bytes += value.bytes;
        }
        return new FileStats(files, bytes);
    }

    private static final class FileStats {
        static final FileStats EMPTY = new FileStats(0L, 0L);
        static final FileStats NON_EMPTY_OR_UNKNOWN = new FileStats(1L, 0L);
        final long files;
        final long bytes;

        FileStats(long files, long bytes) {
            this.files = files;
            this.bytes = bytes;
        }
    }
}
