package ml.melun.mangaview.activity;

import android.content.Intent;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

import ml.melun.mangaview.mangaview.Manga;
import ml.melun.mangaview.mangaview.Title;

/** Same-process activity handoff with a compact, process-safe reader fallback. */
public final class ReaderLaunchPayloadStore {
    public static final String EXTRA_READER_KEY = "readerLaunchPayloadKey";
    public static final String EXTRA_EPISODE_KEY = "episodeLaunchPayloadKey";
    private static final String COMPACT_PREFIX = "readerCompact.";
    private static final String EXTRA_COMPACT_READER = COMPACT_PREFIX + "present";
    private static final String EXTRA_MANGA_ID = COMPACT_PREFIX + "manga.id";
    private static final String EXTRA_MANGA_NAME = COMPACT_PREFIX + "manga.name";
    private static final String EXTRA_MANGA_DATE = COMPACT_PREFIX + "manga.date";
    private static final String EXTRA_MANGA_BASE_MODE = COMPACT_PREFIX + "manga.baseMode";
    private static final String EXTRA_MANGA_MODE = COMPACT_PREFIX + "manga.mode";
    private static final String EXTRA_MANGA_TITLE_ID = COMPACT_PREFIX + "manga.titleId";
    private static final String EXTRA_MANGA_THUMB = COMPACT_PREFIX + "manga.thumb";
    private static final String EXTRA_MANGA_OFFLINE_PATH = COMPACT_PREFIX + "manga.offlinePath";
    private static final String EXTRA_MANGA_NTK_PATH = COMPACT_PREFIX + "manga.ntkPath";
    private static final String EXTRA_MANGA_NTK_EPISODE_ID = COMPACT_PREFIX + "manga.ntkEpisodeId";
    private static final String EXTRA_MANGA_NTK_WORK_ID = COMPACT_PREFIX + "manga.ntkWorkId";
    private static final String EXTRA_MANGA_NTK_PAYLOAD_HINT = COMPACT_PREFIX + "manga.ntkPayloadHint";
    private static final String EXTRA_MANGA_NTK_IMAGE_COUNT = COMPACT_PREFIX + "manga.ntkImageCount";
    private static final String EXTRA_TITLE_PRESENT = COMPACT_PREFIX + "title.present";
    private static final String EXTRA_TITLE_ID = COMPACT_PREFIX + "title.id";
    private static final String EXTRA_TITLE_NAME = COMPACT_PREFIX + "title.name";
    private static final String EXTRA_TITLE_THUMB = COMPACT_PREFIX + "title.thumb";
    private static final String EXTRA_TITLE_AUTHOR = COMPACT_PREFIX + "title.author";
    private static final String EXTRA_TITLE_RELEASE = COMPACT_PREFIX + "title.release";
    private static final String EXTRA_TITLE_BASE_MODE = COMPACT_PREFIX + "title.baseMode";
    private static final String EXTRA_TITLE_PATH = COMPACT_PREFIX + "title.path";
    private static final String EXTRA_TITLE_SOURCE = COMPACT_PREFIX + "title.source";
    private static final String EXTRA_TITLE_NTK_STATUS = COMPACT_PREFIX + "title.ntkStatus";
    private static final String EXTRA_TITLE_RESUME_PATH = COMPACT_PREFIX + "title.resumePath";
    private static final String EXTRA_TITLE_RESUME_NEXT_PATH =
            COMPACT_PREFIX + "title.resumeNextPath";
    private static final String EXTRA_TITLE_RESUME_NEXT_ID =
            COMPACT_PREFIX + "title.resumeNextId";
    private static final String EXTRA_TITLE_RESUME_NEXT_NAME =
            COMPACT_PREFIX + "title.resumeNextName";
    private static final String EXTRA_TITLE_RESUME_NEXT_IMAGE_WORK_ID =
            COMPACT_PREFIX + "title.resumeNextImageWorkId";
    private static final String EXTRA_TITLE_RESUME_NEXT_IMAGE_EPISODE_ID =
            COMPACT_PREFIX + "title.resumeNextImageEpisodeId";
    private static final String EXTRA_TITLE_RESUME_NEXT_IMAGE_COUNT =
            COMPACT_PREFIX + "title.resumeNextImageCount";
    private static final String EXTRA_TITLE_BOOKMARK_ID = COMPACT_PREFIX + "title.bookmarkId";
    private static final String EXTRA_TITLE_BOOKMARK_INDEX = COMPACT_PREFIX + "title.bookmarkIndex";
    private static final String EXTRA_TITLE_EPISODE_COUNT = COMPACT_PREFIX + "title.episodeCount";
    private static final String EXTRA_NTK_EPISODE_METADATA = COMPACT_PREFIX + "ntk.episodeMetadata";
    private static final long TTL_MS = 30_000L;
    private static final AtomicLong IDS = new AtomicLong();
    private static final ConcurrentHashMap<String, Entry> ENTRIES = new ConcurrentHashMap<>();

    private ReaderLaunchPayloadStore() {
    }

    public static String put(Manga manga, Title title) {
        return put(manga, title, null);
    }

    /**
     * Stores one immutable reader handoff.  The prepared key belongs to the exact
     * manga/title selection captured with this token; callers must not reconstruct
     * it later from mutable Activity warmup fields.
     */
    public static String put(Manga manga, Title title, String preparedKey) {
        return putEntry(new Entry(
                manga,
                title,
                normalizePreparedKey(preparedKey),
                android.os.SystemClock.elapsedRealtime()));
    }

    public static String putEpisode(Title title) {
        return putEntry(new Entry(null, title, null, android.os.SystemClock.elapsedRealtime()));
    }

    /**
     * Attaches the zero-copy same-process payload and a small primitive fallback.
     *
     * <p>The fallback intentionally excludes image and episode collections. A
     * recreated process cannot use prepared bitmaps anyway; the canonical NTK
     * episode path and image identity are sufficient for the real reader
     * pipeline to resolve them again.</p>
     */
    public static void attachCompactReaderPayload(Intent intent, Manga manga, Title title) {
        if(intent == null || manga == null)
            return;
        intent.putExtra(EXTRA_READER_KEY, put(manga, title));
        intent.putExtra(EXTRA_COMPACT_READER, true);
        intent.putExtra(EXTRA_MANGA_ID, manga.getId());
        intent.putExtra(EXTRA_MANGA_NAME, manga.getName());
        intent.putExtra(EXTRA_MANGA_DATE, manga.getDate());
        intent.putExtra(EXTRA_MANGA_BASE_MODE, manga.getBaseMode());
        intent.putExtra(EXTRA_MANGA_MODE, manga.getMode());
        intent.putExtra(EXTRA_MANGA_TITLE_ID, manga.getTitleId());
        intent.putExtra(EXTRA_MANGA_THUMB, manga.getThumb());
        intent.putExtra(EXTRA_MANGA_OFFLINE_PATH, manga.getOfflinePath());
        intent.putExtra(EXTRA_MANGA_NTK_PATH, manga.getNtkEpisodePath());
        intent.putExtra(EXTRA_MANGA_NTK_EPISODE_ID, manga.getNtkImageEpisodeId());
        intent.putExtra(EXTRA_MANGA_NTK_WORK_ID, manga.getNtkImageWorkId());
        intent.putExtra(EXTRA_MANGA_NTK_PAYLOAD_HINT, manga.getNtkViewerPayloadHint());
        intent.putExtra(EXTRA_MANGA_NTK_IMAGE_COUNT, manga.getNtkImageCount());
        intent.putExtra(EXTRA_TITLE_PRESENT, title != null);
        if(title == null)
            return;
        intent.putExtra(EXTRA_TITLE_ID, title.getId());
        intent.putExtra(EXTRA_TITLE_NAME, title.getName());
        intent.putExtra(EXTRA_TITLE_THUMB, title.getThumb());
        intent.putExtra(EXTRA_TITLE_AUTHOR, title.getAuthor());
        intent.putExtra(EXTRA_TITLE_RELEASE, title.getRelease());
        intent.putExtra(EXTRA_TITLE_BASE_MODE, title.getBaseMode());
        intent.putExtra(EXTRA_TITLE_PATH, title.getPath());
        intent.putExtra(EXTRA_TITLE_SOURCE, title.getSourceSite());
        intent.putExtra(EXTRA_TITLE_NTK_STATUS, title.getNtkStatusLabel());
        intent.putExtra(EXTRA_TITLE_RESUME_PATH, title.getResumeNtkEpisodePath());
        intent.putExtra(EXTRA_TITLE_RESUME_NEXT_PATH, title.getResumeNtkNextEpisodePath());
        intent.putExtra(EXTRA_TITLE_RESUME_NEXT_ID, title.getResumeNtkNextEpisodeId());
        intent.putExtra(EXTRA_TITLE_RESUME_NEXT_NAME, title.getResumeNtkNextEpisodeName());
        intent.putExtra(
                EXTRA_TITLE_RESUME_NEXT_IMAGE_WORK_ID,
                title.getResumeNtkNextImageWorkId());
        intent.putExtra(
                EXTRA_TITLE_RESUME_NEXT_IMAGE_EPISODE_ID,
                title.getResumeNtkNextImageEpisodeId());
        intent.putExtra(
                EXTRA_TITLE_RESUME_NEXT_IMAGE_COUNT,
                title.getResumeNtkNextImageCount());
        intent.putExtra(EXTRA_TITLE_BOOKMARK_ID, title.getBookmarkEpisodeId());
        intent.putExtra(EXTRA_TITLE_BOOKMARK_INDEX, title.getBookmarkEpisodeIndex());
        intent.putExtra(EXTRA_TITLE_EPISODE_COUNT, title.getEpisodeCount());
    }

    /**
     * Attaches only the primitive, process-safe identity needed by a committed cold viewer
     * launch.  In particular, this deliberately drops the process-local entry because that
     * entry retains the caller's {@link Manga} object and could therefore carry an old image
     * collection into a supposedly cold exact-episode session.
     *
     * <p>The receiving reader must resolve the authoritative image manifest after it is visible;
     * no prepared key, bitmap, image URL collection, page count, or decoded state crosses this
     * boundary. A metadata-only NTK episode list may cross when the normally opened title screen
     * already proved its latest episode, so the reader does not repeat that network request merely
     * to discover the next path or prove that the current episode is terminal.</p>
     */
    public static void attachColdExactReaderPayload(Intent intent, Manga manga, Title title) {
        attachCompactReaderPayload(intent, manga, title);
        if(intent == null)
            return;
        String processKey = intent.getStringExtra(EXTRA_READER_KEY);
        discard(processKey);
        intent.removeExtra(EXTRA_READER_KEY);
        // Exact cold launches carry identity only.  These two compact fields can contain an
        // image URL/page-count result discovered by an earlier reader session, so retaining
        // either would make the handoff depend on stale prepared content.
        intent.removeExtra(EXTRA_MANGA_NTK_PAYLOAD_HINT);
        intent.removeExtra(EXTRA_MANGA_NTK_IMAGE_COUNT);
        String episodeMetadata = compactAuthoritativeNtkEpisodeMetadata(manga, title);
        if(episodeMetadata.length() > 0)
            intent.putExtra(EXTRA_NTK_EPISODE_METADATA, episodeMetadata);
        else
            intent.removeExtra(EXTRA_NTK_EPISODE_METADATA);
    }

    /** Restores the compact payload after the process-local entry is unavailable. */
    public static Entry restoreCompactReaderPayload(Intent intent) {
        if(intent == null || !intent.getBooleanExtra(EXTRA_COMPACT_READER, false))
            return null;
        int mangaId = intent.getIntExtra(EXTRA_MANGA_ID, -1);
        if(mangaId <= 0)
            return null;
        int mangaBaseMode = intent.getIntExtra(EXTRA_MANGA_BASE_MODE, 1);
        Manga manga = new Manga(
                mangaId,
                stringExtra(intent, EXTRA_MANGA_NAME),
                stringExtra(intent, EXTRA_MANGA_DATE),
                mangaBaseMode);
        manga.setMode(intent.getIntExtra(EXTRA_MANGA_MODE, 0));
        manga.setTitleId(intent.getIntExtra(EXTRA_MANGA_TITLE_ID, -1));
        manga.addThumb(stringExtra(intent, EXTRA_MANGA_THUMB));
        manga.setOfflinePath(stringExtra(intent, EXTRA_MANGA_OFFLINE_PATH));
        manga.setNtkEpisodePath(stringExtra(intent, EXTRA_MANGA_NTK_PATH));
        manga.setNtkImageEpisodeId(stringExtra(intent, EXTRA_MANGA_NTK_EPISODE_ID));
        manga.setNtkImageWorkId(stringExtra(intent, EXTRA_MANGA_NTK_WORK_ID));
        manga.setNtkViewerPayloadHint(stringExtra(intent, EXTRA_MANGA_NTK_PAYLOAD_HINT));
        manga.setNtkImageCount(intent.getIntExtra(EXTRA_MANGA_NTK_IMAGE_COUNT, 0));

        Title title = null;
        if(intent.getBooleanExtra(EXTRA_TITLE_PRESENT, false)) {
            int titleBaseMode = intent.getIntExtra(EXTRA_TITLE_BASE_MODE, mangaBaseMode);
            title = new Title(
                    stringExtra(intent, EXTRA_TITLE_NAME),
                    stringExtra(intent, EXTRA_TITLE_THUMB),
                    stringExtra(intent, EXTRA_TITLE_AUTHOR),
                    Collections.emptyList(),
                    stringExtra(intent, EXTRA_TITLE_RELEASE),
                    intent.getIntExtra(EXTRA_TITLE_ID, manga.getTitleId()),
                    titleBaseMode);
            title.setPath(stringExtra(intent, EXTRA_TITLE_PATH));
            title.setSourceSite(stringExtra(intent, EXTRA_TITLE_SOURCE));
            title.setNtkStatusLabel(stringExtra(intent, EXTRA_TITLE_NTK_STATUS));
            title.setResumeNtkEpisodePath(stringExtra(intent, EXTRA_TITLE_RESUME_PATH));
            title.setResumeNtkNextEpisodeIdentity(
                    stringExtra(intent, EXTRA_TITLE_RESUME_NEXT_PATH),
                    intent.getIntExtra(EXTRA_TITLE_RESUME_NEXT_ID, -1),
                    stringExtra(intent, EXTRA_TITLE_RESUME_NEXT_NAME),
                    stringExtra(intent, EXTRA_TITLE_RESUME_NEXT_IMAGE_WORK_ID),
                    stringExtra(intent, EXTRA_TITLE_RESUME_NEXT_IMAGE_EPISODE_ID),
                    intent.getIntExtra(EXTRA_TITLE_RESUME_NEXT_IMAGE_COUNT, 0));
            title.setReadingProgress(
                    intent.getIntExtra(EXTRA_TITLE_BOOKMARK_ID, -1),
                    intent.getIntExtra(EXTRA_TITLE_BOOKMARK_INDEX, -1),
                    intent.getIntExtra(EXTRA_TITLE_EPISODE_COUNT, 0));
            manga.setTitle(title);
            manga.setTitleId(title.getId());
        }
        restoreAuthoritativeNtkEpisodeMetadata(intent, manga, title);
        return new Entry(manga, title, null, android.os.SystemClock.elapsedRealtime());
    }

    private static String compactAuthoritativeNtkEpisodeMetadata(Manga current, Title title) {
        if(current == null || title == null)
            return "";
        String currentPath = cleanNtkEpisodePath(current.getNtkEpisodePath());
        int slash = currentPath.lastIndexOf('/');
        if(slash <= 0)
            return "";
        String workPrefix = currentPath.substring(0, slash + 1);
        List<Manga> source = title.getEps();
        if(source == null || source.isEmpty())
            source = current.getEps();
        if(source == null || source.isEmpty())
            return "";
        boolean foundCurrent = false;
        JSONArray items = new JSONArray();
        try {
            for(Manga episode : new ArrayList<>(source)) {
                if(episode == null)
                    continue;
                String path = cleanNtkEpisodePath(episode.getNtkEpisodePath());
                if(path.length() == 0 || !path.startsWith(workPrefix))
                    continue;
                if(path.equalsIgnoreCase(currentPath)
                        || Manga.sameEpisodeIdentity(current, episode))
                    foundCurrent = true;
                JSONObject item = new JSONObject();
                item.put("id", episode.getId());
                item.put("name", episode.getName());
                item.put("date", episode.getDate());
                item.put("path", path);
                items.put(item);
            }
            // This collection came from the normally opened episode screen in the same cold
            // launch and contains the selected exact path. It is therefore stronger adjacency
            // evidence than refetching the title document after the reader is already visible.
            if(!foundCurrent || items.length() == 0)
                return "";
            JSONObject root = new JSONObject();
            root.put("version", 1);
            root.put("currentPath", currentPath);
            root.put("items", items);
            android.util.Log.d(
                    "ViewerPerf",
                    "reader_launch_ntk_episode_metadata_attached path=" + currentPath
                            + ",episodes=" + items.length());
            return root.toString();
        } catch(Exception ignored) {
            return "";
        }
    }

    private static void restoreAuthoritativeNtkEpisodeMetadata(
            Intent intent,
            Manga current,
            Title title) {
        if(intent == null || current == null || title == null)
            return;
        String encoded = stringExtra(intent, EXTRA_NTK_EPISODE_METADATA);
        if(encoded.length() == 0)
            return;
        String currentPath = cleanNtkEpisodePath(current.getNtkEpisodePath());
        try {
            JSONObject root = new JSONObject(encoded);
            if(root.optInt("version", 0) != 1
                    || !currentPath.equalsIgnoreCase(
                            cleanNtkEpisodePath(root.optString("currentPath"))))
                return;
            JSONArray items = root.optJSONArray("items");
            if(items == null || items.length() == 0 || items.length() > 2000)
                return;
            ArrayList<Manga> episodes = new ArrayList<>(items.length());
            boolean foundCurrent = false;
            for(int i = 0; i < items.length(); i++) {
                JSONObject item = items.optJSONObject(i);
                if(item == null)
                    continue;
                String path = cleanNtkEpisodePath(item.optString("path"));
                if(path.length() == 0)
                    continue;
                Manga episode;
                if(path.equalsIgnoreCase(currentPath)) {
                    episode = current;
                    foundCurrent = true;
                } else {
                    int id = item.optInt("id", -1);
                    if(id <= 0)
                        continue;
                    episode = new Manga(
                            id,
                            item.optString("name"),
                            item.optString("date"),
                            current.getBaseMode());
                    episode.setMode(current.getMode());
                    episode.setTitle(title);
                    episode.setTitleId(title.getId());
                    episode.setNtkEpisodePath(path);
                }
                episodes.add(episode);
            }
            if(!foundCurrent || episodes.isEmpty())
                return;
            title.setEps(episodes);
            current.setEps(title.getEps());
            android.util.Log.d(
                    "ViewerPerf",
                    "reader_launch_ntk_episode_metadata_restored path=" + currentPath
                            + ",episodes=" + episodes.size());
        } catch(Exception ignored) {
            // Invalid optional adjacency metadata must never block the exact reader launch.
        }
    }

    private static String cleanNtkEpisodePath(String path) {
        if(path == null)
            return "";
        String value = path.trim();
        return value.startsWith("/webtoon/") || value.startsWith("/manhwa/") ? value : "";
    }

    private static String stringExtra(Intent intent, String key) {
        String value = intent.getStringExtra(key);
        return value == null ? "" : value;
    }

    private static String putEntry(Entry entry) {
        long now = android.os.SystemClock.elapsedRealtime();
        for(Map.Entry<String, Entry> candidate : ENTRIES.entrySet()) {
            if(now - candidate.getValue().createdAtMs > TTL_MS)
                ENTRIES.remove(candidate.getKey(), candidate.getValue());
        }
        String key = Long.toHexString(now) + "-" + Long.toHexString(IDS.incrementAndGet());
        ENTRIES.put(key, entry);
        return key;
    }

    public static Entry take(String key) {
        if(key == null || key.length() == 0)
            return null;
        Entry entry = ENTRIES.remove(key);
        if(entry == null)
            return null;
        if(android.os.SystemClock.elapsedRealtime() - entry.createdAtMs > TTL_MS)
            return null;
        return entry;
    }

    /** Discards an unconsumed handoff, for example after a drag or ACTION_CANCEL. */
    public static void discard(String key) {
        if(key == null || key.length() == 0)
            return;
        ENTRIES.remove(key);
    }

    private static String normalizePreparedKey(String preparedKey) {
        if(preparedKey == null)
            return null;
        String value = preparedKey.trim();
        return value.length() == 0 ? null : value;
    }

    public static final class Entry {
        private final Manga manga;
        private final Title title;
        private final String preparedKey;
        private final long createdAtMs;

        private Entry(Manga manga, Title title, String preparedKey, long createdAtMs) {
            this.manga = manga;
            this.title = title;
            this.preparedKey = preparedKey;
            this.createdAtMs = createdAtMs;
        }

        public Manga getManga() {
            return manga;
        }

        public Title getTitle() {
            return title;
        }

        public String getPreparedKey() {
            return preparedKey;
        }
    }
}
