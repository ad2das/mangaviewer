package ml.melun.mangaview.contracts;

import android.content.Context;

import com.bumptech.glide.Priority;

import ml.melun.mangaview.mangaview.Manga;
import ml.melun.mangaview.mangaview.Title;

public interface PageWarmup {
    void warmup(Context context, Manga manga, Title title, int pageIndex);

    void warmupContinue(Context context, Manga manga, Title title);

    boolean hasPreparedContinueSnapshot(Context context, Manga manga, Title title);

    Manga usePreparedFirstFrame(Context context, Manga manga, Title title, boolean autoCut, boolean reverse, int firstPage);

    Manga usePreparedContinueImages(Context context, Manga manga, Title title, int firstPage);

    void preloadLoadedImages(Context context, Manga manga, int pageIndex, int width, boolean autoCut, boolean reverse,
                             int limit, Priority priority);

    void clearDecodedWork(Context context);
}
