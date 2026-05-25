package ml.melun.mangaview.activity;

import android.content.Context;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.nio.charset.StandardCharsets;

import ml.melun.mangaview.mangaview.Title;
import ml.melun.mangaview.repository.CachePolicy;

final class EpisodeCompatibleCacheFinder {
    private static final long MAX_EPISODE_CACHE_FILE_BYTES = 2 * 1024 * 1024L;

    private EpisodeCompatibleCacheFinder() {
    }

    static EpisodeCompatibleCachedEpisodes find(Context cacheContext, Title target, String stableName) {
        String matchName = stableName != null && stableName.trim().length() > 0
                ? stableName
                : target == null ? "" : target.getName();
        if(cacheContext == null || target == null || matchName.trim().length() == 0)
            return null;
        File dir = new File(cacheContext.getCacheDir(), "structured_cache");
        File[] files = dir.listFiles();
        if(files == null || files.length == 0)
            return null;
        String targetSource = EpisodeCachePolicy.normalizeSource(target.getSourceSite());
        EpisodeCompatibleCachedEpisodes best = null;
        Gson gson = new Gson();
        for(File file : files) {
            CacheFileMeta meta = cacheFileMeta(file == null ? "" : file.getName());
            if(meta == null || meta.baseMode != target.getBaseMode())
                continue;
            if(!EpisodeCachePolicy.isCompatibleCacheSource(targetSource, meta.sourceSite))
                continue;
            if(meta.titleId == target.getId() && meta.sourceSite.equals(target.getSourceSite()))
                continue;
            try {
                String json = readUtf8(file);
                if(json.length() == 0)
                    continue;
                EpisodeCachedEpisodes cached = gson.fromJson(json, new TypeToken<EpisodeCachedEpisodes>(){}.getType());
                if(cached == null || cached.episodes == null || cached.episodes.size() == 0)
                    continue;
                if(!CachePolicy.isFresh(cached.savedAt, CachePolicy.EPISODE_TTL_MS)
                        && !CachePolicy.isReusableForColdStart(cached.savedAt))
                    continue;
                int matchScore = EpisodeCachePolicy.cachedEpisodeTitleMatchScore(matchName, cached.episodes);
                if(matchScore <= 0)
                    continue;
                EpisodeCompatibleCachedEpisodes candidate = new EpisodeCompatibleCachedEpisodes();
                candidate.cached = cached;
                candidate.sourceSite = meta.sourceSite;
                candidate.titleId = meta.titleId;
                candidate.episodeCount = cached.episodes.size();
                candidate.score = matchScore + ("ntk".equals(meta.sourceSite) ? 1000 : 0);
                if(best == null || candidate.score > best.score || (candidate.score == best.score
                        && candidate.cached.savedAt > best.cached.savedAt))
                    best = candidate;
            } catch(Exception e) {
                ml.melun.mangaview.report.CrashReporter.record(e);
            }
        }
        return best;
    }

    static CacheFileMeta cacheFileMeta(String fileName) {
        if(fileName == null || !fileName.startsWith("episodeSnapshotV2_"))
            return null;
        String[] parts = fileName.split("_", 5);
        if(parts.length < 5)
            return null;
        try {
            CacheFileMeta meta = new CacheFileMeta();
            meta.sourceSite = parts[1];
            meta.baseMode = Integer.parseInt(parts[2]);
            meta.titleId = Integer.parseInt(parts[3]);
            return meta;
        } catch(Exception e) {
            return null;
        }
    }

    private static String readUtf8(File file) throws Exception {
        if(file == null || !file.exists() || !file.isFile())
            return "";
        if(file.length() <= 0 || file.length() > MAX_EPISODE_CACHE_FILE_BYTES)
            return "";
        try (FileInputStream input = new FileInputStream(file);
             ByteArrayOutputStream output = new ByteArrayOutputStream((int) file.length())) {
            byte[] buffer = new byte[8192];
            int read;
            while((read = input.read(buffer)) > 0)
                output.write(buffer, 0, read);
            return output.toString(StandardCharsets.UTF_8.name());
        }
    }

    static final class CacheFileMeta {
        String sourceSite;
        int baseMode;
        int titleId;
    }
}

