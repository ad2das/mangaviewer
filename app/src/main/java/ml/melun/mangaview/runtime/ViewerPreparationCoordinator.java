package ml.melun.mangaview.runtime;

import android.content.Context;
import android.os.SystemClock;

import java.util.List;

import ml.melun.mangaview.Utils;
import ml.melun.mangaview.activity.ViewerResumeResolver;
import ml.melun.mangaview.glide.ViewerWarmupManager;
import ml.melun.mangaview.mangaview.Manga;
import ml.melun.mangaview.mangaview.Title;
import ml.melun.mangaview.repository.MangaRepository;

import static ml.melun.mangaview.MainApplication.p;
import static ml.melun.mangaview.mangaview.Title.LOAD_CAPTCHA;
import static ml.melun.mangaview.mangaview.Title.LOAD_OK;

public final class ViewerPreparationCoordinator {
    public static final long CONTINUE_CLICK_WAIT_MS = 1800L;
    public static final long CONTINUE_CLICK_DATA_SAVE_WAIT_MS = 1200L;
    public static final long CONTINUE_POST_PREPARE_WAIT_MS = 2500L;
    public static final long CONTINUE_POST_PREPARE_DATA_SAVE_WAIT_MS = 1800L;

    private ViewerPreparationCoordinator() {
    }

    public static PreparedViewerLaunch prepareContinue(Context context, Manga manga, Title title,
                                                       boolean autoCut, boolean reverse,
                                                       MangaRepository.Cancellation cancellation) {
        if(context == null || manga == null)
            return PreparedViewerLaunch.failed(PreparedViewerLaunch.Status.ERROR, Title.LOAD_ERROR);
        if(!manga.isOnline())
            return PreparedViewerLaunch.offline(manga, title != null ? title : manga.getTitle());
        Title launchTitle = attachTitle(manga, title);
        Manga prepared = ViewerWarmupManager.usePreparedFirstFrame(context, manga, launchTitle, autoCut, reverse);
        if(prepared == null)
            prepared = ViewerWarmupManager.prepareClickFirstFrame(context, manga, launchTitle, autoCut, reverse, cancellation);
        if(prepared == null)
            prepared = ViewerWarmupManager.usePreparedFirstFrame(context, manga, launchTitle, autoCut, reverse);
        if(prepared == null)
            prepared = ViewerWarmupManager.usePreparedContinueImages(context, manga, launchTitle,
                    firstPageForContinue(manga));
        if(prepared == null)
            prepared = waitForPreparedFirstFrame(context, manga, launchTitle, autoCut, reverse,
                    postPrepareWaitMs(p != null && p.getDataSave()));
        if(prepared == null)
            prepared = ViewerWarmupManager.usePreparedContinueImages(context, manga, launchTitle,
                    firstPageForContinue(manga));
        if(prepared != null)
            return PreparedViewerLaunch.ready(prepared, launchTitle != null ? launchTitle : prepared.getTitle());
        if(ViewerResumeResolver.shouldBlockPathlessNtkResume(manga, launchTitle))
            return PreparedViewerLaunch.failed(PreparedViewerLaunch.Status.PATHLESS_NTK, ViewerWarmupManager.LOAD_EMPTY_IMAGES);
        return PreparedViewerLaunch.failed(PreparedViewerLaunch.Status.FIRST_FRAME_PENDING,
                ViewerWarmupManager.LOAD_FIRST_FRAME_PENDING);
    }

    public static PreparedViewerLaunch prepareExact(Context context, Manga manga, Title title, int pageIndex,
                                                    int width, boolean autoCut, boolean reverse,
                                                    MangaRepository.Cancellation cancellation, long waitMs,
                                                    boolean allowForegroundFallback) {
        if(context == null || manga == null)
            return PreparedViewerLaunch.failed(PreparedViewerLaunch.Status.ERROR, Title.LOAD_ERROR);
        if(!manga.isOnline())
            return PreparedViewerLaunch.offline(manga, title != null ? title : manga.getTitle());
        Title launchTitle = attachTitle(manga, title);
        int normalizedPage = Math.max(0, pageIndex);
        Manga prepared = ViewerWarmupManager.usePreparedExactFirstFrame(context, manga, launchTitle,
                autoCut, reverse, normalizedPage);
        if(prepared != null)
            return PreparedViewerLaunch.ready(prepared, launchTitle != null ? launchTitle : prepared.getTitle());
        try {
            int result = ViewerWarmupManager.prepareFirstFrameDirectOnly(context, manga, launchTitle, normalizedPage,
                    width, autoCut, reverse, cancellation);
            if(result == LOAD_CAPTCHA)
                return PreparedViewerLaunch.failed(PreparedViewerLaunch.Status.CAPTCHA, result);
            if(result != LOAD_OK)
                return statusForResult(result);
            boolean ready = ViewerWarmupManager.waitForFirstDecodedFrame(context, manga, normalizedPage, width,
                    autoCut, reverse, waitMs);
            prepared = ViewerWarmupManager.usePreparedExactFirstFrame(context, manga, launchTitle,
                    autoCut, reverse, normalizedPage);
            if(ready && prepared != null)
                return PreparedViewerLaunch.ready(prepared, launchTitle != null ? launchTitle : prepared.getTitle());
            if(allowForegroundFallback) {
                prepared = ViewerWarmupManager.prepareClickFirstFrame(context, manga, launchTitle,
                        autoCut, reverse, cancellation);
                if(prepared != null)
                    return PreparedViewerLaunch.ready(prepared, launchTitle != null ? launchTitle : prepared.getTitle());
            }
        } catch (Exception e) {
            ml.melun.mangaview.report.CrashReporter.record(e);
            return PreparedViewerLaunch.failed(PreparedViewerLaunch.Status.ERROR, Title.LOAD_ERROR);
        }
        return PreparedViewerLaunch.failed(PreparedViewerLaunch.Status.FIRST_FRAME_PENDING,
                ViewerWarmupManager.LOAD_FIRST_FRAME_PENDING);
    }

    public static PreparedViewerLaunch prepareFirstReadyCandidate(Context context, Manga target, Title title,
                                                                  int firstPage, int width, boolean autoCut,
                                                                  boolean reverse,
                                                                  MangaRepository.Cancellation cancellation,
                                                                  boolean skipTarget, long waitMs) throws Exception {
        if(context == null || target == null)
            return PreparedViewerLaunch.failed(PreparedViewerLaunch.Status.ERROR, Title.LOAD_ERROR);
        if(!target.isOnline())
            return PreparedViewerLaunch.offline(target, title != null ? title : target.getTitle());
        Title currentTitle = attachTitle(target, title);
        int lastResult = ViewerWarmupManager.LOAD_EMPTY_IMAGES;
        List<Manga> candidates = ViewerResumeResolver.candidates(target, currentTitle, skipTarget);
        if(candidates.size() == 0 && ViewerResumeResolver.shouldUseTargetAsLastResort(target, currentTitle))
            candidates.add(target);
        long startedAt = SystemClock.elapsedRealtime();
        for(Manga candidate : candidates) {
            if(candidate == null)
                continue;
            attachTitle(candidate, currentTitle);
            int page = ViewerResumeResolver.sameManga(candidate, target) ? Math.max(0, firstPage) : 0;
            int result = ViewerWarmupManager.prepareFirstFrameReady(context, candidate, currentTitle, page, width,
                    autoCut, reverse, cancellation, remainingWaitMs(waitMs, startedAt));
            if(result == LOAD_CAPTCHA)
                return PreparedViewerLaunch.failed(PreparedViewerLaunch.Status.CAPTCHA, result);
            lastResult = result;
            if(result == LOAD_OK || shouldLaunchLoadedCandidate(result, candidate, context)) {
                if(!ViewerResumeResolver.sameManga(candidate, target))
                    ViewerWarmupManager.logMetric("viewer_resume_episode_fallback", candidate.getId());
                if(result == ViewerWarmupManager.LOAD_FIRST_FRAME_PENDING)
                    ViewerWarmupManager.logMetric("viewer_resume_pending_urls_ready", candidate.getId());
                return PreparedViewerLaunch.ready(candidate, currentTitle != null ? currentTitle : candidate.getTitle());
            }
        }
        return statusForResult(lastResult);
    }

    public static PreparedViewerLaunch statusForResult(int result) {
        if(result == LOAD_OK)
            return PreparedViewerLaunch.failed(PreparedViewerLaunch.Status.FIRST_FRAME_PENDING,
                    ViewerWarmupManager.LOAD_FIRST_FRAME_PENDING);
        if(result == LOAD_CAPTCHA)
            return PreparedViewerLaunch.failed(PreparedViewerLaunch.Status.CAPTCHA, result);
        if(result == ViewerWarmupManager.LOAD_FIRST_FRAME_PENDING)
            return PreparedViewerLaunch.failed(PreparedViewerLaunch.Status.FIRST_FRAME_PENDING, result);
        if(result == ViewerWarmupManager.LOAD_EMPTY_IMAGES)
            return PreparedViewerLaunch.failed(PreparedViewerLaunch.Status.EMPTY_IMAGES, result);
        return PreparedViewerLaunch.failed(PreparedViewerLaunch.Status.ERROR, result);
    }

    public static long continueClickWaitMs(boolean dataSave) {
        return dataSave ? CONTINUE_CLICK_DATA_SAVE_WAIT_MS : CONTINUE_CLICK_WAIT_MS;
    }

    public static long postPrepareWaitMs(boolean dataSave) {
        return dataSave ? CONTINUE_POST_PREPARE_DATA_SAVE_WAIT_MS : CONTINUE_POST_PREPARE_WAIT_MS;
    }

    private static long remainingWaitMs(long waitMs, long startedAt) {
        if(waitMs <= 0)
            return 0L;
        long elapsed = SystemClock.elapsedRealtime() - startedAt;
        return Math.max(0L, waitMs - elapsed);
    }

    private static int firstPageForContinue(Manga manga) {
        int firstPage = manga != null && manga.useBookmark() && p != null ? p.getViewerBookmark(manga) : 0;
        return Math.max(0, firstPage);
    }

    private static Manga waitForPreparedFirstFrame(Context context, Manga manga, Title title,
                                                   boolean autoCut, boolean reverse, long waitMs) {
        long deadline = SystemClock.elapsedRealtime() + Math.max(0L, waitMs);
        Manga prepared = ViewerWarmupManager.usePreparedFirstFrame(context, manga, title, autoCut, reverse);
        while(prepared == null && SystemClock.elapsedRealtime() < deadline) {
            try {
                Thread.sleep(40L);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return null;
            }
            prepared = ViewerWarmupManager.usePreparedFirstFrame(context, manga, title, autoCut, reverse);
        }
        return prepared;
    }

    private static Title attachTitle(Manga manga, Title title) {
        if(manga == null)
            return title;
        Title currentTitle = title != null ? title : manga.getTitle();
        if(currentTitle != null) {
            manga.setTitle(currentTitle);
            manga.setTitleId(currentTitle.getId());
            List<Manga> episodes = Utils.snapshotEpisodes(currentTitle);
            if(episodes.size() > 0)
                manga.setEps(episodes);
        }
        return currentTitle;
    }

    private static boolean hasLoadedImages(Manga manga, Context context) {
        try {
            List<String> images = MangaRepository.imageUrls(manga, context);
            return images != null && images.size() > 0;
        } catch (Exception e) {
            return false;
        }
    }

    private static boolean shouldLaunchLoadedCandidate(int result, Manga manga, Context context) {
        return (result == ViewerWarmupManager.LOAD_FIRST_FRAME_PENDING
                || result == ViewerWarmupManager.LOAD_EMPTY_IMAGES)
                && hasLoadedImages(manga, context);
    }
}
