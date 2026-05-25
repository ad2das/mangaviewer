package ml.melun.mangaview.runtime;

import android.content.Context;
import android.os.SystemClock;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import ml.melun.mangaview.Utils;
import ml.melun.mangaview.activity.ViewerResumeResolver;
import ml.melun.mangaview.glide.ViewerWarmupManager;
import ml.melun.mangaview.mangaview.MTitle;
import ml.melun.mangaview.mangaview.Manga;
import ml.melun.mangaview.mangaview.Title;
import ml.melun.mangaview.reader.ReaderWarmupCoordinator;

import static ml.melun.mangaview.MainApplication.p;

public final class ContinueReadinessCoordinator {
    private static final int COLD_START_LIMIT = 3;
    private static final int DATA_SAVE_COLD_START_LIMIT = 1;
    private static final long SUBMIT_DEDUPE_MS = 2000L;
    private static final int SUBMITTED_LIMIT = 160;
    private static final Map<String, Long> submitted = new LinkedHashMap<>(64, 0.75f, true);
    private static volatile boolean coldStartPrimed = false;

    private ContinueReadinessCoordinator() {
    }

    public enum State {
        UNSEEN,
        EPISODES_READY,
        IMAGES_READY,
        FIRST_FRAME_READY,
        FAILED
    }

    public static void primeColdStart(Context context) {
        if(context == null || p == null || coldStartPrimed)
            return;
        coldStartPrimed = true;
        primeSavedContinues(context, coldStartLimit(p.getDataSave()));
    }

    public static void primeSavedContinues(Context context, int limit) {
        if(context == null || p == null || limit <= 0)
            return;
        List<MTitle> recent = Utils.snapshotList(p.getRecent());
        if(recent == null || recent.size() == 0)
            return;
        int primed = 0;
        long startedAt = SystemClock.elapsedRealtime();
        for(MTitle item : recent) {
            Title title = item instanceof Title ? (Title) item : new Title(item);
            if(shouldSkipNtkContinuePrefetchForTest(title.getSourceSite(), p.isNtkSite()))
                continue;
            Manga manga = resumeManga(title);
            if(manga == null)
                continue;
            primeVisible(context, manga, title);
            primed++;
            if(primed >= limit)
                break;
        }
        ViewerWarmupManager.logMetric("continue_prime_start", primed);
        ViewerWarmupManager.logMetric("continue_prime_submit_ms", SystemClock.elapsedRealtime() - startedAt);
    }

    public static void primeVisible(Context context, Manga manga, Title title) {
        prime(context, manga, title, true, false);
    }

    public static void primeImmediate(Context context, Manga manga, Title title) {
        prime(context, manga, title, false, true);
    }

    public static boolean isFirstFrameReady(Context context, Manga manga, Title title) {
        return state(context, manga, title) == State.FIRST_FRAME_READY;
    }

    public static State state(Context context, Manga manga, Title title) {
        if(context == null || manga == null || !manga.isOnline())
            return State.UNSEEN;
        if(ViewerWarmupManager.hasPreparedContinueFirstFrame(context, manga, title, false,
                p != null && p.getReverse()))
            return State.FIRST_FRAME_READY;
        if(ViewerWarmupManager.hasPreparedContinueSnapshot(context, manga, title))
            return State.IMAGES_READY;
        if(title != null && Utils.snapshotEpisodes(title).size() > 1)
            return State.EPISODES_READY;
        return State.UNSEEN;
    }

    private static void prime(Context context, Manga manga, Title title, boolean visible, boolean force) {
        if(context == null || manga == null || !manga.isOnline())
            return;
        String sourceSite = title != null ? title.getSourceSite()
                : manga.getTitle() == null ? null : manga.getTitle().getSourceSite();
        if(shouldSkipNtkContinuePrefetchForTest(sourceSite, p != null && p.isNtkSite()))
            return;
        if(title != null) {
            if(p != null)
                p.ensureSourceSiteForTitle(title);
            manga.setTitle(title);
            manga.setTitleId(title.getId());
            List<Manga> episodes = Utils.snapshotEpisodes(title);
            if(episodes.size() > 0)
                manga.setEps(episodes);
            manga.ensureNtkEpisodePathFromIdentity();
        } else {
            title = manga.getTitle();
        }
        State current = state(context, manga, title);
        if(current == State.FIRST_FRAME_READY) {
            ViewerWarmupManager.logMetric("continue_prime_already_ready", manga.getId());
            return;
        }
        if(ViewerResumeResolver.shouldBlockPathlessNtkResume(manga, title)) {
            Manga resolved = ViewerResumeResolver.concreteNtkResumeCandidate(manga, title);
            if(resolved == null)
                return;
            manga = resolved;
            manga.setTitle(title);
            manga.setTitleId(title.getId());
            List<Manga> episodes = Utils.snapshotEpisodes(title);
            if(episodes.size() > 0)
                manga.setEps(episodes);
        }
        String key = submitKey(manga, title);
        if(!force && !markSubmitted(key, SystemClock.uptimeMillis()))
            return;
        if(force)
            markSubmitted(key, SystemClock.uptimeMillis());
        if(visible) {
            ReaderWarmupCoordinator.primeVisible(context, manga, title);
            ViewerWarmupManager.warmupVisibleContinue(context, manga, title);
        } else if(force) {
            ReaderWarmupCoordinator.primeImmediate(context, manga, title);
            ViewerWarmupManager.warmupUserSelectedContinue(context, manga, title);
        } else {
            ReaderWarmupCoordinator.primeImmediate(context, manga, title);
            ViewerWarmupManager.warmupContinueImmediate(context, manga, title);
        }
    }

    private static Manga resumeManga(Title title) {
        if(title == null || p == null || title.getId() <= 0)
            return null;
        p.ensureSourceSiteForTitle(title);
        int bookmark = p.getBookmark(title);
        if(bookmark <= 0)
            bookmark = title.getBookmark();
        if(bookmark <= 0)
            bookmark = title.getBookmarkEpisodeId();
        if(bookmark <= 0)
            return null;
        title.setBookmark(bookmark);
        Manga manga = new Manga(bookmark, "", "", title.getBaseMode());
        manga.setTitle(title);
        manga.setTitleId(title.getId());
        List<Manga> episodes = Utils.snapshotEpisodes(title);
        if(episodes.size() > 0)
            manga.setEps(episodes);
        manga.ensureNtkEpisodePathFromIdentity();
        return manga;
    }

    private static synchronized boolean markSubmitted(String key, long now) {
        Long last = submitted.get(key);
        if(last != null && now - last < SUBMIT_DEDUPE_MS)
            return false;
        submitted.put(key, now);
        while(submitted.size() > SUBMITTED_LIMIT) {
            String first = submitted.keySet().iterator().next();
            submitted.remove(first);
        }
        return true;
    }

    private static String submitKey(Manga manga, Title title) {
        String source = title == null ? "" : title.getSourceSite();
        if(source == null)
            source = "";
        source = source.trim().toLowerCase(Locale.ROOT);
        int titleId = title == null ? manga.getTitleId() : title.getId();
        int page = p != null && manga.useBookmark() ? p.getViewerBookmark(manga) : 0;
        if(page < 0)
            page = 0;
        return source + ":" + manga.getBaseMode() + ":" + titleId + ":" + manga.getId() + ":" + page;
    }

    private static boolean sourceMatchesCurrentSite(Title title) {
        if(p == null)
            return true;
        String source = title == null ? "" : title.getSourceSite();
        if(source == null || source.length() == 0)
            return true;
        boolean ntk = "ntk".equals(source.trim().toLowerCase(Locale.ROOT));
        return p.isNtkSite() == ntk;
    }

    static int coldStartLimitForTest(boolean dataSave) {
        return coldStartLimit(dataSave);
    }

    static long submitDedupeMsForTest() {
        return SUBMIT_DEDUPE_MS;
    }

    public static boolean shouldSkipNtkContinuePrefetchForTest(String sourceSite, boolean ntkPreference) {
        return ntkPreference && (sourceSite == null || sourceSite.trim().length() == 0);
    }

    private static int coldStartLimit(boolean dataSave) {
        return dataSave ? DATA_SAVE_COLD_START_LIMIT : COLD_START_LIMIT;
    }

    static State stateForTest(boolean firstFrame, boolean images, boolean episodes, boolean failed) {
        if(failed)
            return State.FAILED;
        if(firstFrame)
            return State.FIRST_FRAME_READY;
        if(images)
            return State.IMAGES_READY;
        if(episodes)
            return State.EPISODES_READY;
        return State.UNSEEN;
    }
}
