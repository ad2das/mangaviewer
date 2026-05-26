package ml.melun.mangaview.runtime;

import android.content.Context;
import android.net.ConnectivityManager;
import android.net.NetworkCapabilities;

import androidx.recyclerview.widget.RecyclerView;

import com.bumptech.glide.Priority;

import java.util.ArrayList;
import java.util.List;

import ml.melun.mangaview.glide.ViewerPreloadPolicy;
import ml.melun.mangaview.glide.ViewerWarmupManager;
import ml.melun.mangaview.mangaview.Manga;
import ml.melun.mangaview.mangaview.Title;
import ml.melun.mangaview.reader.ReaderWarmupCoordinator;

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
        if(shouldSkipWolfBackgroundPrefetchForTest(title == null ? null : title.getSourceSite(), p != null && p.isNtkSite(), getHttpClient().isNtk()))
            return;
        Context appContext = context.getApplicationContext();
        String sourceSite = title == null ? null : title.getSourceSite();
        boolean wfwfContext = isWfwfPrefetchContext(sourceSite);
        int limit = episodePrefetchLimitForTest(p.getDataSave(), aggressiveAllowed(appContext),
                isNtkPrefetchContext(sourceSite), wfwfContext);
        List<Integer> targets = viewerTargets(episodes, bookmarkIndex, limit,
                wfwfContext);
        int resumeIndex = bookmarkIndex > 0 && bookmarkIndex <= episodes.size() ? bookmarkIndex - 1 : -1;
        warmTargets(appContext, title, episodes, targets, mode, resumeIndex);
    }

    public static void prefetchEpisodeIndexes(Context context, Title title, List<Manga> episodes, List<Integer> indexes, int mode) {
        if(context == null || title == null || episodes == null || episodes.size() == 0 || indexes == null || indexes.size() == 0)
            return;
        if(shouldSkipNtkPrefetchForTest(title == null ? null : title.getSourceSite(), p != null && p.isNtkSite(), getHttpClient().isNtk()))
            return;
        if(shouldSkipWolfBackgroundPrefetchForTest(title == null ? null : title.getSourceSite(), p != null && p.isNtkSite(), getHttpClient().isNtk()))
            return;
        warmTargets(context.getApplicationContext(), title, episodes, indexes, mode, -1, true);
    }

    private static void warmTargets(Context appContext, Title title, List<Manga> episodes, List<Integer> targets, int mode, int resumeIndex) {
        warmTargets(appContext, title, episodes, targets, mode, resumeIndex, false);
    }

    private static void warmTargets(Context appContext, Title title, List<Manga> episodes, List<Integer> targets, int mode,
                                    int resumeIndex, boolean lightOnly) {
        if(appContext == null || title == null || episodes == null || targets == null)
            return;
        int entryIndex = resumeIndex >= 0 ? resumeIndex : firstTarget(targets);
        boolean wfwfContext = isWfwfPrefetchContext(title.getSourceSite());
        for(Integer index : targets) {
            if(index == null || index < 0 || index >= episodes.size())
                continue;
            Manga manga = episodes.get(index);
            if(manga == null)
                continue;
            manga.setMode(mode);
            manga.setTitle(title);
            manga.setTitleId(title.getId());
            if(index == resumeIndex)
                ReaderWarmupCoordinator.primeImmediate(appContext, manga, title);
            else if(index == entryIndex)
                ReaderWarmupCoordinator.primeExactImmediate(appContext, manga, title);
            else
                ReaderWarmupCoordinator.primeExactVisible(appContext, manga, title);
            if(wfwfContext) {
                continue;
            } else if(lightOnly)
                ViewerWarmupManager.warmupLight(appContext, manga, title, 0);
            else if(index == resumeIndex)
                ViewerWarmupManager.warmupEntry(appContext, manga, title);
            else if(index == entryIndex)
                ViewerWarmupManager.warmupEntry(appContext, manga, title, 0);
            else
                ViewerWarmupManager.warmupLight(appContext, manga, title, 0);
        }
    }

    private static int firstTarget(List<Integer> targets) {
        if(targets == null)
            return -1;
        for(Integer target : targets)
            if(target != null)
                return target;
        return -1;
    }

    public static void prefetchAdjacentEpisode(Context context, Manga current, Title title, int width, boolean autoCut, boolean reverse) {
        if(context == null || current == null || !current.isOnline())
            return;
        if(shouldSkipNtkPrefetchForTest(title == null ? null : title.getSourceSite(), p != null && p.isNtkSite(), getHttpClient().isNtk()))
            return;
        if(shouldSkipWolfBackgroundPrefetchForTest(title == null ? null : title.getSourceSite(), p != null && p.isNtkSite(), getHttpClient().isNtk()))
            return;
        Context appContext = context.getApplicationContext();
        warmAndPreload(appContext, current.prevEp(), title, width, autoCut, reverse);
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
        ReaderWarmupCoordinator.primeAdjacent(context, manga, title);
    }

    private static List<Integer> viewerTargets(List<Manga> episodes, int bookmarkIndex, int limit,
                                               boolean preferScreenTop) {
        ArrayList<Integer> targets = new ArrayList<>();
        if(bookmarkIndex > 0 && bookmarkIndex <= episodes.size()) {
            int current = bookmarkIndex - 1;
            addTarget(targets, episodes, current, limit);
            addTarget(targets, episodes, current - 1, limit);
            addTarget(targets, episodes, current + 1, limit);
            addTarget(targets, episodes, firstEpisodeIndex(episodes), limit);
            addTarget(targets, episodes, 0, limit);
            addTarget(targets, episodes, current - 2, limit);
            addTarget(targets, episodes, current + 2, limit);
            return targets;
        }
        int firstEpisode = firstEpisodeIndex(episodes);
        addTarget(targets, episodes, firstEpisode, limit);
        if(preferScreenTop) {
            addTarget(targets, episodes, 0, limit);
            addTarget(targets, episodes, 1, limit);
            addTarget(targets, episodes, 2, limit);
            return targets;
        }
        addTarget(targets, episodes, 0, limit);
        addTarget(targets, episodes, firstEpisode - 1, limit);
        addTarget(targets, episodes, 1, limit);
        return targets;
    }

    public static int firstEpisodeIndex(List<Manga> episodes) {
        return episodes == null || episodes.size() == 0 ? -1 : episodes.size() - 1;
    }

    static int episodePrefetchLimitForTest(boolean dataSave, boolean aggressiveAllowed) {
        return episodePrefetchLimitForTest(dataSave, aggressiveAllowed, false);
    }

    static int episodePrefetchLimitForTest(boolean dataSave, boolean aggressiveAllowed, boolean ntkSite) {
        return episodePrefetchLimitForTest(dataSave, aggressiveAllowed, ntkSite, false);
    }

    static int episodePrefetchLimitForTest(boolean dataSave, boolean aggressiveAllowed, boolean ntkSite, boolean wfwfSite) {
        if(dataSave)
            return 1;
        if(wfwfSite)
            return 1;
        return aggressiveAllowed ? 3 : 2;
    }

    public static List<Integer> visibleEpisodeTargets(List<Manga> episodes, int firstAdapterPosition,
                                                      int lastAdapterPosition, int ahead, int limit) {
        ArrayList<Integer> targets = new ArrayList<>();
        if(episodes == null || episodes.size() == 0 || limit <= 0)
            return targets;
        if(firstAdapterPosition == RecyclerView.NO_POSITION || lastAdapterPosition == RecyclerView.NO_POSITION)
            return targets;
        int firstEpisode = Math.max(0, firstAdapterPosition - 1);
        int lastEpisode = Math.max(firstEpisode, lastAdapterPosition - 1 + Math.max(0, ahead));
        lastEpisode = Math.min(lastEpisode, episodes.size() - 1);
        for(int i = firstEpisode; i <= lastEpisode && targets.size() < limit; i++)
            addTarget(targets, episodes, i, limit);
        if(targets.size() < limit && firstEpisode > 0)
            addTarget(targets, episodes, firstEpisode - 1, limit);
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

    private static boolean isNtkPrefetchContext(String sourceSite) {
        if(p != null && p.isNtkSite())
            return true;
        if(getHttpClient() != null && getHttpClient().isNtk())
            return true;
        return sourceSite != null && "ntk".equals(sourceSite.trim().toLowerCase(java.util.Locale.ROOT));
    }

    private static boolean isWfwfPrefetchContext(String sourceSite) {
        return sourceSite != null && "wfwf".equals(sourceSite.trim().toLowerCase(java.util.Locale.ROOT));
    }

    public static boolean shouldSkipNtkPrefetchForTest(String sourceSite, boolean ntkPreference, boolean ntkClient) {
        return (ntkPreference || ntkClient) && (sourceSite == null || sourceSite.trim().length() == 0);
    }

    public static boolean shouldSkipWolfBackgroundPrefetchForTest(String sourceSite, boolean ntkPreference, boolean ntkClient) {
        if(ntkPreference || ntkClient)
            return false;
        return false;
    }
}
