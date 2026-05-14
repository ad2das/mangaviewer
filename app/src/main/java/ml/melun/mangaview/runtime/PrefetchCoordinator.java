package ml.melun.mangaview.runtime;

import android.content.Context;
import android.net.ConnectivityManager;
import android.net.NetworkCapabilities;

import com.bumptech.glide.Priority;

import java.util.ArrayList;
import java.util.List;

import ml.melun.mangaview.glide.ViewerPreloadPolicy;
import ml.melun.mangaview.glide.ViewerWarmupManager;
import ml.melun.mangaview.mangaview.Manga;
import ml.melun.mangaview.mangaview.Title;
import ml.melun.mangaview.repository.MangaRepository;

import static ml.melun.mangaview.MainApplication.getHttpClient;
import static ml.melun.mangaview.MainApplication.p;

public final class PrefetchCoordinator {
    private PrefetchCoordinator() {
    }

    public static boolean aggressiveAllowed(Context context) {
        return !p.getDataSave() && isWifi(context);
    }

    public static void prefetchEpisodeList(Context context, Title title, List<Manga> episodes, int bookmarkIndex, int mode) {
        if(context == null || title == null || episodes == null || episodes.size() == 0)
            return;
        if(shouldSkipNtkPrefetchForTest(title == null ? null : title.getSourceSite(), p != null && p.isNtkSite(), getHttpClient().isNtk()))
            return;
        Context appContext = context.getApplicationContext();
        List<Integer> targets = viewerTargets(episodes, bookmarkIndex, aggressiveAllowed(appContext) ? 3 : 2);
        int resumeIndex = bookmarkIndex > 0 && bookmarkIndex <= episodes.size() ? bookmarkIndex - 1 : -1;
        for(Integer index : targets) {
            Manga manga = episodes.get(index);
            if(manga == null)
                continue;
            manga.setMode(mode);
            manga.setTitle(title);
            manga.setTitleId(title.getId());
            if(index == resumeIndex)
                ViewerWarmupManager.warmup(appContext, manga, title);
            else
                ViewerWarmupManager.warmup(appContext, manga, title, 0);
        }
    }

    public static void prefetchAdjacentEpisode(Context context, Manga current, Title title, int width, boolean autoCut, boolean reverse) {
        if(context == null || current == null || !current.isOnline())
            return;
        if(shouldSkipNtkPrefetchForTest(title == null ? null : title.getSourceSite(), p != null && p.isNtkSite(), getHttpClient().isNtk()))
            return;
        Context appContext = context.getApplicationContext();
        warmAndPreload(appContext, current.nextEp(), title, width, autoCut, reverse);
    }

    public static void prefetchViewerWindow(Context context, Manga manga, int pageIndex, int width, boolean autoCut, boolean reverse) {
        if(context == null || manga == null)
            return;
        int window = aggressiveAllowed(context)
                ? ViewerPreloadPolicy.scrollAheadWindow(false).totalLimit
                : ViewerPreloadPolicy.scrollAheadWindow(p.getDataSave()).totalLimit;
        ViewerWarmupManager.preloadLoadedImages(context, manga, pageIndex, width, autoCut, reverse, window, Priority.HIGH);
    }

    private static void warmAndPreload(Context context, Manga manga, Title title, int width, boolean autoCut, boolean reverse) {
        if(manga == null)
            return;
        if(title != null)
            manga.setTitle(title);
        ViewerWarmupManager.warmup(context, manga, title, 0);
        List<String> imageUrls = MangaRepository.imageUrls(manga, context);
        if(imageUrls != null && imageUrls.size() > 0)
            ViewerWarmupManager.preloadWindow(context, manga, 0, width, autoCut, reverse, ViewerPreloadPolicy.nextEpisodeWindow(p.getDataSave()));
    }

    private static List<Integer> viewerTargets(List<Manga> episodes, int bookmarkIndex, int limit) {
        ArrayList<Integer> targets = new ArrayList<>();
        if(bookmarkIndex > 0 && bookmarkIndex <= episodes.size()) {
            int current = bookmarkIndex - 1;
            addTarget(targets, episodes, current, limit);
            addTarget(targets, episodes, current - 1, limit);
            addTarget(targets, episodes, current - 2, limit);
            return targets;
        }
        addTarget(targets, episodes, 0, limit);
        addTarget(targets, episodes, episodes.size() - 1, limit);
        for(int i = 1; i < episodes.size() && targets.size() < limit; i++)
            addTarget(targets, episodes, i, limit);
        return targets;
    }

    private static void addTarget(List<Integer> targets, List<Manga> episodes, int index, int limit) {
        if(index < 0 || index >= episodes.size() || targets.size() >= limit || targets.contains(index))
            return;
        Manga episode = episodes.get(index);
        if(episode == null || !episode.isOnline())
            return;
        targets.add(index);
    }

    private static boolean isWifi(Context context) {
        try {
            ConnectivityManager manager = (ConnectivityManager) context.getSystemService(Context.CONNECTIVITY_SERVICE);
            if(manager == null || manager.getActiveNetwork() == null)
                return false;
            NetworkCapabilities capabilities = manager.getNetworkCapabilities(manager.getActiveNetwork());
            return capabilities != null && capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI);
        } catch (Exception e) {
            return false;
        }
    }

    public static boolean shouldSkipNtkPrefetchForTest(String sourceSite, boolean ntkPreference, boolean ntkClient) {
        return (ntkPreference || ntkClient) && (sourceSite == null || sourceSite.trim().length() == 0);
    }
}
