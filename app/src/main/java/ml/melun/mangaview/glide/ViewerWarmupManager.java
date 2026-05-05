package ml.melun.mangaview.glide;

import android.app.Activity;
import android.content.Context;
import android.util.DisplayMetrics;

import com.bumptech.glide.Glide;
import com.bumptech.glide.Priority;
import com.bumptech.glide.load.engine.DiskCacheStrategy;
import com.bumptech.glide.load.resource.bitmap.DownsampleStrategy;
import com.bumptech.glide.request.RequestOptions;
import com.bumptech.glide.request.target.Target;

import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import ml.melun.mangaview.Utils;
import ml.melun.mangaview.mangaview.Manga;
import ml.melun.mangaview.mangaview.Title;
import ml.melun.mangaview.model.PageItem;
import ml.melun.mangaview.task.LifecycleTask;

import static ml.melun.mangaview.MainApplication.getHttpClient;
import static ml.melun.mangaview.MainApplication.p;

public class ViewerWarmupManager {
    private static final int ACTIVE_LIMIT = 40;
    private static final Set<String> activeWarmups = new LinkedHashSet<>();

    public static void warmup(Context context, Manga manga, Title title) {
        if(context == null || manga == null || !manga.isOnline())
            return;
        if(title != null) {
            manga.setTitle(title);
            manga.setTitleId(title.getId());
        } else {
            title = manga.getTitle();
        }
        int width = viewerWidth(context);
        int pageIndex = manga.useBookmark() ? p.getViewerBookmark(manga) : 0;
        if(pageIndex < 0)
            pageIndex = 0;
        String key = warmupKey(manga, title, pageIndex, width);
        if(!markActive(key))
            return;
        Context appContext = context.getApplicationContext();
        int startPage = pageIndex;
        LifecycleTask.USER_ACTION_EXECUTOR.submit(() -> {
            try {
                if(manga.getImgs(appContext) == null || manga.getImgs(appContext).size() == 0)
                    manga.fetchForViewerInitial(getHttpClient());
                preloadLoadedImages(appContext, manga, startPage, width, false, p.getReverse(), p.getDataSave() ? 2 : 4, Priority.HIGH);
            } catch (Exception e) {
                e.printStackTrace();
            } finally {
                unmarkActive(key);
            }
        });
    }

    public static void preloadLoadedImages(Context context, Manga manga, int pageIndex, int width, boolean autoCut, boolean reverse, int limit, Priority priority) {
        if(context == null || manga == null || !manga.isOnline())
            return;
        List<String> images = manga.getImgs(context);
        if(images == null || images.size() == 0)
            return;
        if(pageIndex < 0 || pageIndex >= images.size())
            pageIndex = 0;
        int end = Math.min(images.size(), pageIndex + Math.max(1, limit));
        for(int i = pageIndex; i < end; i++) {
            PageItem page = new PageItem(i, images.get(i), manga);
            RequestOptions options = new RequestOptions()
                    .diskCacheStrategy(DiskCacheStrategy.DATA)
                    .downsample(DownsampleStrategy.AT_MOST)
                    .override(Math.max(width, 1), Target.SIZE_ORIGINAL)
                    .transform(new ViewerPageTransformation(page, autoCut, reverse, width));
            Glide.with(context)
                    .asBitmap()
                    .priority(priority)
                    .apply(options)
                    .load(Utils.getGlideUrl(images.get(i), manga.getBaseMode()))
                    .preload();
        }
    }

    private static int viewerWidth(Context context) {
        if(context instanceof Activity)
            return Utils.getScreenSize(((Activity) context).getWindowManager().getDefaultDisplay());
        DisplayMetrics metrics = context.getResources().getDisplayMetrics();
        return Math.min(Math.max(metrics.widthPixels, metrics.heightPixels), 3000);
    }

    private static String warmupKey(Manga manga, Title title, int pageIndex, int width) {
        int titleId = title == null ? manga.getTitleId() : title.getId();
        return manga.getBaseMode() + ":" + titleId + ":" + manga.getId() + ":" + pageIndex + ":" + p.getReverse() + ":" + width;
    }

    private static synchronized boolean markActive(String key) {
        if(activeWarmups.contains(key))
            return false;
        activeWarmups.add(key);
        while(activeWarmups.size() > ACTIVE_LIMIT) {
            Iterator<String> iterator = activeWarmups.iterator();
            if(!iterator.hasNext())
                break;
            iterator.next();
            iterator.remove();
        }
        return true;
    }

    private static synchronized void unmarkActive(String key) {
        activeWarmups.remove(key);
    }
}
