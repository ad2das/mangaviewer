package ml.melun.mangaview.contracts;

import android.content.Context;

import com.bumptech.glide.Priority;

import ml.melun.mangaview.glide.ViewerWarmupManager;
import ml.melun.mangaview.mangaview.Manga;
import ml.melun.mangaview.mangaview.Title;

public final class LegacyPageWarmup implements PageWarmup {
    @Override
    public void warmup(Context context, Manga manga, Title title, int pageIndex) {
        ViewerWarmupManager.warmup(context, manga, title, pageIndex);
    }

    @Override
    public void warmupContinue(Context context, Manga manga, Title title) {
        ViewerWarmupManager.warmupContinue(context, manga, title);
    }

    @Override
    public boolean hasPreparedContinueSnapshot(Context context, Manga manga, Title title) {
        return ViewerWarmupManager.hasPreparedContinueSnapshot(context, manga, title);
    }

    @Override
    public Manga usePreparedFirstFrame(Context context, Manga manga, Title title, boolean autoCut, boolean reverse,
                                       int firstPage) {
        return ViewerWarmupManager.usePreparedFirstFrame(context, manga, title, autoCut, reverse, firstPage);
    }

    @Override
    public Manga usePreparedContinueImages(Context context, Manga manga, Title title, int firstPage) {
        return ViewerWarmupManager.usePreparedContinueImages(context, manga, title, firstPage);
    }

    @Override
    public void preloadLoadedImages(Context context, Manga manga, int pageIndex, int width, boolean autoCut,
                                    boolean reverse, int limit, Priority priority) {
        ViewerWarmupManager.preloadLoadedImages(context, manga, pageIndex, width, autoCut, reverse, limit, priority);
    }

    @Override
    public void clearDecodedWork(Context context) {
        ViewerWarmupManager.clearDecodedWork(context);
    }
}
