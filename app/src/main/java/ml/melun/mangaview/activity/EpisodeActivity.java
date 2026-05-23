package ml.melun.mangaview.activity;

import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.net.Uri;
import com.google.android.material.floatingactionbutton.FloatingActionButton;
import androidx.annotation.NonNull;
import androidx.appcompat.app.ActionBar;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;

import android.os.Bundle;
import android.util.Log;

import androidx.appcompat.widget.LinearLayoutCompat;
import androidx.appcompat.widget.Toolbar;
import androidx.lifecycle.ViewModelProvider;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import androidx.recyclerview.widget.SimpleItemAnimator;

import android.view.Menu;
import android.view.MenuInflater;
import android.view.MenuItem;
import android.view.View;
import android.widget.PopupMenu;
import android.widget.Toast;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import ml.melun.mangaview.ui.NpaLinearLayoutManager;
import ml.melun.mangaview.R;
import ml.melun.mangaview.adapter.EpisodeAdapter;
import ml.melun.mangaview.adapter.TagAdapter;
import ml.melun.mangaview.glide.ViewerWarmupManager;
import ml.melun.mangaview.mangaview.Manga;
import ml.melun.mangaview.mangaview.Title;
import ml.melun.mangaview.model.EpisodeLoadResult;
import ml.melun.mangaview.repository.CacheFileStore;
import ml.melun.mangaview.repository.CachePolicy;
import ml.melun.mangaview.repository.EpisodeSnapshotCache;
import ml.melun.mangaview.repository.MangaRepository;
import ml.melun.mangaview.repository.OfflineStore;
import ml.melun.mangaview.runtime.AppDispatchers;
import ml.melun.mangaview.runtime.PerfTrace;
import ml.melun.mangaview.runtime.PerformanceMonitor;
import ml.melun.mangaview.runtime.PrefetchCoordinator;
import ml.melun.mangaview.state.UiState;
import ml.melun.mangaview.viewmodel.EpisodeViewModel;
import ml.melun.mangaview.mangaview.CustomHttpClient;

import static ml.melun.mangaview.MainApplication.p;
import static ml.melun.mangaview.MainApplication.getHttpClient;
import static ml.melun.mangaview.Utils.openViewerPrepared;
import static ml.melun.mangaview.Utils.queueOfflineDownload;
import static ml.melun.mangaview.Utils.safeGet;
import static ml.melun.mangaview.Utils.showCaptchaPopup;
import static ml.melun.mangaview.Utils.showErrorPopup;
import static ml.melun.mangaview.Utils.toViewerMangaJson;
import static ml.melun.mangaview.Utils.toViewerTitleJson;
import static ml.melun.mangaview.activity.CaptchaActivity.RESULT_CAPTCHA;
import static ml.melun.mangaview.mangaview.Title.LOAD_CAPTCHA;
import static ml.melun.mangaview.mangaview.Title.LOAD_ERROR;


public class EpisodeActivity extends AppCompatActivity {
    private static final long DESTINATION_LAUNCH_DEBOUNCE_MS = 1500L;
    private static final long VIEWER_PAGE_CACHE_TTL_MS = 5 * 60 * 1000L;
    private static final long VISIBLE_EPISODE_WARMUP_IDLE_DELAY_MS = 360L;
    private static final long EPISODE_REFRESH_AFTER_CACHE_PROBE_MS = 160L;
    private static final long INITIAL_VIEWER_TARGET_WARMUP_DELAY_MS = 0L;
    private static final long INITIAL_VISIBLE_EPISODE_WARMUP_DELAY_MS = 800L;
    private static final long NTK_INITIAL_VISIBLE_EPISODE_WARMUP_DELAY_MS = 800L;
    private static final long MAX_EPISODE_CACHE_FILE_BYTES = 2 * 1024 * 1024L;
    private static final int MEMORY_CACHE_MAIN_THREAD_PARSE_MAX_CHARS = 256 * 1024;
    private static final int VISIBLE_EPISODE_WARMUP_AHEAD = 1;
    //global variables
    Title title;
    EpisodeAdapter episodeAdapter;
    Context context = this;
    RecyclerView episodeList;
    boolean favoriteResult = false;
    boolean recentResult = false;
    int position;
    int bookmarkId = -1;
    int bookmarkIndex = -1;
    List<Manga> episodes;
    boolean dark, online=true;
    Intent viewer;
    ActionBar actionBar;
    String homeDir;
    int mode = 0;
    FloatingActionButton resumefab;
    boolean loaded = false;
    LinearLayoutCompat fab_container;
    EpisodeViewModel episodeViewModel;
    long firstContentStartedAt;
    boolean firstContentLogged = false;
    boolean ntkLoadTimeoutHandled = false;
    boolean ntkCaptchaLaunchInFlight = false;
    boolean visibleEpisodeWarmupScheduled = false;
    boolean destroyed = false;
    boolean compatibleCacheLookupInFlight = false;
    boolean pendingLoadErrorAfterCacheLookup = false;
    Runnable ntkEpisodeLoadWatchdogRunnable;
    Runnable episodeRefreshRunnable;
    String originalTitleName = "";
    final Set<Integer> requestedVisibleWarmups = new HashSet<>();
    final Runnable initialEpisodeWarmupRunnable = () -> {
        if(!isUiAlive())
            return;
        if(episodeList != null && episodeList.getScrollState() != RecyclerView.SCROLL_STATE_IDLE) {
            scheduleInitialEpisodeWarmups(VISIBLE_EPISODE_WARMUP_IDLE_DELAY_MS);
            return;
        }
        warmupInitialViewerTargets();
    };
    final Runnable visibleEpisodeWarmupRunnable = () -> {
        visibleEpisodeWarmupScheduled = false;
        if(!isUiAlive())
            return;
        warmupVisibleEpisodeRows();
    };


    public boolean onOptionsItemSelected(MenuItem item){
        switch (item.getItemId()) {
            case android.R.id.home:
                finish();
                return true;
            case R.id.episode_favorite:
                toggleFavorite();
                return true;
            case R.id.episode_download:
                Intent download = new Intent(context, DownloadActivity.class);
                download.putExtra("title", toViewerTitleJson(title, true));
                startActivity(download);
                return true;
            case R.id.episode_more:
                showMoreMenu(findViewById(R.id.episode_more));
                return true;
        }
        return super.onOptionsItemSelected(item);
    }

    private void showMoreMenu(View anchor) {
        if(anchor == null || title == null)
            return;
        PopupMenu popup = new PopupMenu(context, anchor);
        popup.getMenu().add(0, 1, 0, "브라우저에서 열기");
        popup.getMenu().add(0, 2, 1, "공유");
        popup.getMenu().add(0, 3, 2, "오프라인 저장");
        popup.setOnMenuItemClickListener(menuItem -> {
            String url = getTitleWebUrl();
            switch (menuItem.getItemId()) {
                case 1:
                    if(url.length() == 0) {
                        Toast.makeText(context, "열 수 있는 주소가 없습니다", Toast.LENGTH_SHORT).show();
                        return true;
                    }
                    startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url)));
                    return true;
                case 2:
                    Intent share = new Intent(Intent.ACTION_SEND);
                    share.setType("text/plain");
                    share.putExtra(Intent.EXTRA_SUBJECT, title.getName());
                    share.putExtra(Intent.EXTRA_TEXT, title.getName() + (url.length() > 0 ? "\n" + url : ""));
                    startActivity(Intent.createChooser(share, "공유"));
                    return true;
                case 3:
                    Intent download = new Intent(context, DownloadActivity.class);
                    download.putExtra("title", toViewerTitleJson(title, true));
                    startActivity(download);
                    return true;
            }
            return false;
        });
        popup.show();
    }

    private String getTitleWebUrl() {
        try {
            String path = title == null ? "" : title.getUrl();
            if(path == null || path.length() == 0)
                return "";
            if(path.startsWith("http://") || path.startsWith("https://"))
                return path;
            return MangaRepository.resolveUrl(path);
        } catch (Exception e) {
            return "";
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if(resultCode== RESULT_OK){
            if(data == null)
                return;
            String switchedTitleJson = data.getStringExtra(ViewerActivity.EXTRA_RETURN_EPISODE_TITLE);
            if(shouldSwitchEpisodeListForViewerResult(
                    data.getBooleanExtra(ViewerActivity.EXTRA_RETURN_EPISODE_SOURCE_SWITCHED, false),
                    switchedTitleJson)) {
                restartWithViewerResultTitle(switchedTitleJson);
                return;
            }
            int newid = data.getIntExtra("id", -1);
            if(newid>0 && newid!=bookmarkId){
                bookmarkId = newid;
                //find index of bookmark;
                if(episodes != null)
                    for(int i=0; i< episodes.size(); i++){
                            Manga episode = safeGet(episodes, i);
                            if(episode != null && episode.getId()==bookmarkId){
                                bookmarkIndex = i+1;
                                if(episodeAdapter != null)
                                    episodeAdapter.setBookmark(bookmarkIndex);
                                break;
                            }
                    }
            }
            if(bookmarkId>-1)
                resumefab.show();
            else
                resumefab.hide();
        }else if(resultCode == RESULT_CAPTCHA){
            //captcha Checked
            Intent restartIntent = new Intent(this, EpisodeActivity.class);
            restartIntent.addFlags(Intent.FLAG_ACTIVITY_NO_ANIMATION);
            Intent currentIntent = getIntent();
            if(currentIntent != null) {
                if(currentIntent.getExtras() != null)
                    restartIntent.putExtras(new Bundle(currentIntent.getExtras()));
                restartIntent.setData(currentIntent.getData());
            }
            finish();
            startActivity(restartIntent);
            overridePendingTransition(0, 0);
        }
    }

    private void restartWithViewerResultTitle(String titleJson) {
        Intent restartIntent = new Intent(this, EpisodeActivity.class);
        restartIntent.addFlags(Intent.FLAG_ACTIVITY_NO_ANIMATION);
        Intent currentIntent = getIntent();
        if(currentIntent != null) {
            if(currentIntent.getExtras() != null)
                restartIntent.putExtras(new Bundle(currentIntent.getExtras()));
            restartIntent.setData(currentIntent.getData());
        }
        restartIntent.putExtra("title", titleJson);
        restartIntent.putExtra("online", true);
        finish();
        startActivity(restartIntent);
        overridePendingTransition(0, 0);
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        dark = p.getDarkTheme();
        if(dark) setTheme(R.style.AppThemeDarkNoTitle);
        super.onCreate(savedInstanceState);
        overridePendingTransition(0, 0);
        PerformanceMonitor.attach(this);
        PerformanceMonitor.screen("episode");
        setContentView(R.layout.activity_episode);
        applyEpisodeWindowChrome();
        Intent intent = getIntent();
        title = parseIntentTitle(intent.getStringExtra("title"));
        if(title == null) {
            Toast.makeText(this, "작품 정보를 열 수 없습니다.", Toast.LENGTH_SHORT).show();
            finish();
            return;
        }
        originalTitleName = title.getName();
        switchToTitleSourceSite();
        firstContentStartedAt = PerfTrace.start("episode_first_content_ms");
        online = intent.getBooleanExtra("online", true);
        if(title.useBookmark())
            bookmarkId = restoredBookmarkId(title);
        position = intent.getIntExtra("position",0);
        favoriteResult = intent.getBooleanExtra("favorite",false);
        recentResult = intent.getBooleanExtra("recent",false);
        episodeList = this.findViewById(R.id.EpisodeList);
        applyEpisodeWindowChrome();
        episodeList.setLayoutManager(new NpaLinearLayoutManager(this));
        episodeList.setHasFixedSize(true);
        episodeList.setItemViewCacheSize(20);
        episodeList.setItemAnimator(null);
        episodeList.setOverScrollMode(View.OVER_SCROLL_NEVER);
        episodeList.addOnScrollListener(new RecyclerView.OnScrollListener() {
            @Override
            public void onScrolled(@NonNull RecyclerView recyclerView, int dx, int dy) {
                super.onScrolled(recyclerView, dx, dy);
                if(dy != 0)
                    cancelVisibleEpisodeWarmup();
            }

            @Override
            public void onScrollStateChanged(@NonNull RecyclerView recyclerView, int newState) {
                super.onScrollStateChanged(recyclerView, newState);
                PerformanceMonitor.phase(newState == RecyclerView.SCROLL_STATE_IDLE ? "idle" : "scrolling");
                if(newState == RecyclerView.SCROLL_STATE_IDLE) {
                    PerformanceMonitor.reportNow("episode_scroll_idle");
                    scheduleVisibleEpisodeWarmup(VISIBLE_EPISODE_WARMUP_IDLE_DELAY_MS);
                } else
                    cancelVisibleEpisodeWarmup();
            }
        });
        homeDir = p.getHomeDir();
        resumefab = this.findViewById(R.id.resumefab);
        fab_container = findViewById(R.id.fab_container);

        if(episodeList.getItemAnimator() instanceof SimpleItemAnimator)
            ((SimpleItemAnimator) episodeList.getItemAnimator()).setSupportsChangeAnimations(false);
        if(recentResult){
            Intent resultIntent = new Intent();
            setResult(RESULT_OK,resultIntent);
        }


        Toolbar toolbar = (Toolbar) findViewById(R.id.toolbar);
        setSupportActionBar(toolbar);
        actionBar = getSupportActionBar();
        if(actionBar!=null){
            actionBar.setTitle("");
            actionBar.setDisplayHomeAsUpEnabled(true);
        }

        if(online) {
            mode = 0;
            fab_container.setVisibility(View.GONE);
            boolean renderedCachedEpisodes = showCachedEpisodesFromMemory();
            if(shouldLoadDiskEpisodeCacheAsyncForTest(renderedCachedEpisodes))
                loadCachedEpisodesAsync();
            episodeViewModel = new ViewModelProvider(this).get(EpisodeViewModel.class);
            episodeViewModel.state().observe(this, this::renderEpisodeState);
            if(shouldRefreshEpisodesAfterCache(renderedCachedEpisodes)) {
                if(renderedCachedEpisodes)
                    startEpisodeRefresh(false);
                else
                    scheduleEpisodeRefreshAfterCacheProbe();
            }
        }else{
            loadOfflineEpisodesAsync();
        }
    }

//    @Override
//    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
//        super.onActivityResult(requestCode, resultCode, data);
//        if(requestCode==0){
//            if(bookmarkId != p.getBookmark()){
//                bookmarkId = p.getBookmark();
//                episodeAdapter.setBookmark(bookmarkId);
//            }
//        }
//    }

    public void afterLoad(){
        requestedVisibleWarmups.clear();
        if(fab_container != null)
            fab_container.setVisibility(View.GONE);
        //find bookmark
        actionBar = getSupportActionBar();
        if(actionBar!=null){
            actionBar.setTitle("");
            actionBar.setDisplayHomeAsUpEnabled(true);
        }
        if(bookmarkId>-1){
            if(episodes != null)
                for(int i=0; i< episodes.size(); i++){
                    Manga episode = safeGet(episodes, i);
                    if(episode != null && episode.getId()==bookmarkId){
                        bookmarkIndex=i+1;
                        episodeAdapter.setBookmark(bookmarkIndex);
                        break;
                    }
                }
        }
        if(bookmarkIndex < 0 && title.getBookmarkEpisodeIndex() > 0 && episodes != null
                && title.getBookmarkEpisodeIndex() <= episodes.size()) {
            bookmarkIndex = title.getBookmarkEpisodeIndex();
            Manga episode = safeGet(episodes, bookmarkIndex - 1);
            if(episode != null) {
                bookmarkId = episode.getId();
                episodeAdapter.setBookmark(bookmarkIndex);
            } else {
                bookmarkIndex = -1;
                bookmarkId = -1;
            }
        }
        episodeAdapter.setFavorite(p.findFavorite(title)>-1);
        episodeList.setAdapter(episodeAdapter);
        if(bookmarkIndex>8) {
            episodeList.scrollToPosition(bookmarkIndex);
        }
        findViewById(R.id.upfab).setOnClickListener(v -> episodeList.scrollToPosition(0));
        findViewById(R.id.downfab).setOnClickListener(v -> {
            if(episodes == null)
                return;
            episodeList.scrollToPosition(episodes.size()); //헤더가 0이기 때문
        });
        if(bookmarkIndex>-1)
            resumefab.show();
        else
            resumefab.hide();
        resumefab.setOnClickListener(v -> {
            if(episodes == null || bookmarkIndex <= 0 || bookmarkIndex > episodes.size())
                return;
            Manga episode = safeGet(episodes, bookmarkIndex - 1);
            if(episode != null)
                openViewer(episode,0);
        });

        episodeAdapter.setClickListener(new EpisodeAdapter.ItemClickListener() {

            @Override
            public void onItemClick(int position, Manga selected) {
                //add local images to manga
                openViewer(selected,0, true);
            }
            @Override
            public void onItemPress(int position, Manga selected) {
                // Keep episode taps responsive; visible-row warmup runs after the screen settles.
            }
            @Override
            public void onStarClick(){
                toggleFavorite();
            }

            @Override
            public void onAuthorClick() {
                if(title.getAuthor().length()>0){
                    Intent i = new Intent(context, TagSearchActivity.class);
                    i.putExtra("query",title.getAuthor());
                    i.putExtra("mode",1);
                    startActivity(i);
                }
            }

            @Override
            public void onEpisodeTabClick() {
                if(episodeList != null && episodeAdapter != null && episodeAdapter.getItemCount() > 1)
                    episodeList.smoothScrollToPosition(1);
            }

            @Override
            public void onDownloadClick(int position, Manga m) {
                if(!online) {
                    confirmDeleteOfflineEpisode(position, m);
                    return;
                }
                if(m != null)
                    m.setTitle(title);
                queueOfflineDownload(context, title, m);
            }

            @Override
            public void onFirstClick(){
                Manga target = quickReadEpisode();
                if(target != null)
                    openViewer(target,0, true);
            }
        });
        episodeAdapter.setTagClickListener(tag -> {
            Intent i = new Intent(context, TagSearchActivity.class);
            i.putExtra("query",tag);
            i.putExtra("mode",2);
            startActivity(i);
        });
        markFirstContent();
        scheduleInitialEpisodeWarmups(initialViewerTargetWarmupDelayMs());
    }

    private void warmupInitialViewerTargets() {
        if(!isUiAlive())
            return;
        if(!online || episodes == null || episodes.size() == 0)
            return;
        PrefetchCoordinator.prefetchEpisodeList(context, title, episodes, bookmarkIndex, mode);
        warmupLikelyNtkViewerPage();
    }

    private void scheduleEpisodeRefreshAfterCacheProbe() {
        if(episodeList == null)
            return;
        cancelEpisodeRefresh();
        episodeRefreshRunnable = () -> startEpisodeRefresh(true);
        episodeList.postDelayed(episodeRefreshRunnable, episodeRefreshAfterCacheProbeMsForTest());
    }

    private void startEpisodeRefresh(boolean showLoading) {
        if(!isUiAlive() || episodeViewModel == null || title == null)
            return;
        cancelEpisodeRefresh();
        episodeViewModel.loadEpisodes(title, showLoading);
        scheduleNtkEpisodeLoadWatchdog();
    }

    private void cancelEpisodeRefresh() {
        if(episodeList != null && episodeRefreshRunnable != null)
            episodeList.removeCallbacks(episodeRefreshRunnable);
        episodeRefreshRunnable = null;
    }

    private void scheduleInitialEpisodeWarmups(long delayMs) {
        if(!online || episodeList == null || episodes == null || episodes.size() == 0)
            return;
        episodeList.removeCallbacks(initialEpisodeWarmupRunnable);
        episodeList.postDelayed(initialEpisodeWarmupRunnable, Math.max(0L, delayMs));
    }

    private long initialViewerTargetWarmupDelayMs() {
        return initialViewerTargetWarmupDelayMsForTest(p.isNtkSite() || getHttpClient().isNtk());
    }

    private long initialVisibleEpisodeWarmupDelayMs() {
        return initialVisibleEpisodeWarmupDelayMsForTest(p.isNtkSite() || getHttpClient().isNtk());
    }

    private void scheduleVisibleEpisodeWarmup(long delayMs) {
        if(!online || episodeList == null || episodes == null || episodes.size() == 0)
            return;
        episodeList.removeCallbacks(visibleEpisodeWarmupRunnable);
        visibleEpisodeWarmupScheduled = true;
        episodeList.postDelayed(visibleEpisodeWarmupRunnable, Math.max(0L, delayMs));
    }

    private void cancelVisibleEpisodeWarmup() {
        if(episodeList != null)
            episodeList.removeCallbacks(visibleEpisodeWarmupRunnable);
        visibleEpisodeWarmupScheduled = false;
    }

    private void warmupVisibleEpisodeRows() {
        if(!online || episodeList == null || episodes == null || episodes.size() == 0 || title == null)
            return;
        RecyclerView.LayoutManager manager = episodeList.getLayoutManager();
        if(!(manager instanceof LinearLayoutManager))
            return;
        LinearLayoutManager layoutManager = (LinearLayoutManager) manager;
        int first = layoutManager.findFirstVisibleItemPosition();
        int last = layoutManager.findLastVisibleItemPosition();
        int limit = visibleEpisodeWarmupLimitForTest(p.getDataSave(), PrefetchCoordinator.aggressiveAllowed(context),
                p.isNtkSite() || getHttpClient().isNtk());
        List<Integer> targets = PrefetchCoordinator.visibleEpisodeTargets(episodes, first, last,
                VISIBLE_EPISODE_WARMUP_AHEAD, limit);
        if(targets.size() == 0)
            return;
        ArrayList<Integer> fresh = new ArrayList<>();
        for(Integer index : targets) {
            Manga episode = index == null ? null : safeGet(episodes, index);
            if(episode == null)
                continue;
            int key = episode.getId() >= 0 ? episode.getId() : (String.valueOf(episode.getName()) + ":" + index).hashCode();
            if(requestedVisibleWarmups.add(key))
                fresh.add(index);
        }
        if(fresh.size() == 0)
            return;
        ViewerWarmupManager.logMetric("episode_visible_warmup_count", fresh.size());
        PrefetchCoordinator.prefetchEpisodeIndexes(context, title, episodes, fresh, mode);
    }

    static int visibleEpisodeWarmupLimitForTest(boolean dataSave, boolean aggressiveAllowed) {
        return visibleEpisodeWarmupLimitForTest(dataSave, aggressiveAllowed, false);
    }

    static int visibleEpisodeWarmupLimitForTest(boolean dataSave, boolean aggressiveAllowed, boolean ntkSite) {
        if(dataSave)
            return 1;
        if(ntkSite)
            return aggressiveAllowed ? 3 : 2;
        return aggressiveAllowed ? 3 : 2;
    }

    static long visibleEpisodeWarmupIdleDelayMsForTest() {
        return VISIBLE_EPISODE_WARMUP_IDLE_DELAY_MS;
    }

    static long initialVisibleEpisodeWarmupDelayMsForTest() {
        return INITIAL_VISIBLE_EPISODE_WARMUP_DELAY_MS;
    }

    static long episodeRefreshAfterCacheProbeMsForTest() {
        return EPISODE_REFRESH_AFTER_CACHE_PROBE_MS;
    }

    static long initialViewerTargetWarmupDelayMsForTest(boolean ntkSite) {
        return ntkSite ? NTK_INITIAL_VISIBLE_EPISODE_WARMUP_DELAY_MS : INITIAL_VIEWER_TARGET_WARMUP_DELAY_MS;
    }

    static long initialVisibleEpisodeWarmupDelayMsForTest(boolean ntkSite) {
        return ntkSite ? NTK_INITIAL_VISIBLE_EPISODE_WARMUP_DELAY_MS : INITIAL_VISIBLE_EPISODE_WARMUP_DELAY_MS;
    }

    private void warmupLikelyNtkViewerPage() {
        Manga target = quickReadEpisode();
        if(target == null)
            return;
        target.setMode(mode);
        target.setTitle(title);
        target.setTitleId(title == null ? target.getTitleId() : title.getId());
        ViewerWarmupManager.warmupUserSelectedEpisode(context, target, title, 0);
        if(!shouldDirectWarmupNtkViewerPageForTest(p.isNtkSite(), getHttpClient().isNtk(), target == null ? null : target.getNtkEpisodePath()))
            return;
        String path = target.getNtkEpisodePath();
        Context appContext = context.getApplicationContext();
        Title currentTitle = title;
        int width = Math.max(1, getResources().getDisplayMetrics().widthPixels);
        AppDispatchers.submitImageWarmup(() -> {
            boolean warmed = getHttpClient().warmupCachedPageDirect(path, VIEWER_PAGE_CACHE_TTL_MS);
            if(!shouldPreloadNtkFirstFrameAfterDirectWarmupForTest(warmed))
                return;
            try {
                ViewerWarmupManager.prepareFirstFrameBackgroundDirectOnly(appContext, target, currentTitle, 0, width,
                        false, p.getReverse(), MangaRepository.cancellation());
            } catch (Exception e) {
                ml.melun.mangaview.report.CrashReporter.record(e);
            }
        });
    }

    static boolean shouldDirectWarmupNtkViewerPageForTest(boolean ntkPreference, boolean ntkClient, String episodePath) {
        return (ntkPreference || ntkClient)
                && episodePath != null
                && episodePath.trim().length() > 0;
    }

    static boolean shouldPreloadNtkFirstFrameAfterDirectWarmupForTest(boolean directWarmupSucceeded) {
        return directWarmupSucceeded;
    }

    private void confirmDeleteOfflineEpisode(int position, Manga manga) {
        if(online || manga == null || manga.getOfflinePath() == null || manga.getOfflinePath().length() == 0)
            return;
        DialogInterface.OnClickListener listener = (dialog, which) -> {
            if(which == DialogInterface.BUTTON_POSITIVE)
                deleteOfflineEpisode(position, manga);
        };
        AlertDialog.Builder builder = dark
                ? new AlertDialog.Builder(context, R.style.darkDialog)
                : new AlertDialog.Builder(context);
        builder.setMessage(manga.getName() + " 을(를) 저장됨에서 삭제하시겠습니까?")
                .setPositiveButton("네", listener)
                .setNegativeButton("아니오", listener)
                .show();
    }

    private void deleteOfflineEpisode(int position, Manga manga) {
        if(deleteOfflineEpisodeAsync(position, manga))
            return;
        boolean deleted = OfflineStore.deleteEpisode(context, manga);
        if(!deleted) {
            Toast.makeText(context, "삭제를 실패했습니다.", Toast.LENGTH_SHORT).show();
            return;
        }

        p.removeViewerBookmark(manga);
        if(position >= 0 && episodes != null && position < episodes.size()) {
            if(episodes.get(position) == manga)
                episodeAdapter.removeEpisode(position);
            else {
                episodes.remove(manga);
                episodeAdapter.notifyItemRangeChanged(0, episodeAdapter.getItemCount());
            }
        }
        Toast.makeText(context, "삭제가 완료되었습니다.", Toast.LENGTH_SHORT).show();
        if(episodes == null || episodes.size() == 0) {
            deleteEmptyOfflineTitle();
            finish();
        }
    }

    private void deleteEmptyOfflineTitle() {
        if(title == null || title.getPath() == null || title.getPath().length() == 0)
            return;
        try {
            OfflineStore.deleteTitle(context, title);
        } catch (Exception e) {
            ml.melun.mangaview.report.CrashReporter.record(e);
        }
    }

    private int restoredBookmarkId(Title title) {
        if(title == null)
            return -1;
        int bookmark = p.getBookmark(title);
        if(bookmark > 0)
            return bookmark;
        if(title.getBookmark() > 0)
            return title.getBookmark();
        if(title.getBookmarkEpisodeId() > 0)
            return title.getBookmarkEpisodeId();
        return -1;
    }

    private void switchToTitleSourceSite() {
        if(title == null || p == null)
            return;
        String source = title.getSourceSite();
        if(source == null || source.length() == 0)
            return;
        boolean targetNtk = "ntk".equals(source);
        if(p.isNtkSite() == targetNtk)
            return;
        if(targetNtk)
            p.setSitePreset(CustomHttpClient.NTK_COMIC_URL, CustomHttpClient.NTK_WEBTOON_URL);
        else
            p.setSitePreset(CustomHttpClient.DEFAULT_COMIC_URL, CustomHttpClient.WEBTOON_URL);
    }

    private Manga quickReadEpisode() {
        if(episodes == null || episodes.size() == 0)
            return null;
        if(bookmarkIndex > 0 && bookmarkIndex <= episodes.size()) {
            Manga episode = safeGet(episodes, bookmarkIndex - 1);
            if(episode != null)
                return episode;
        }
        int restoredId = restoredBookmarkId(title);
        if(restoredId > 0) {
            for(int i = 0; i < episodes.size(); i++) {
                Manga episode = safeGet(episodes, i);
                if(episode != null && episode.getId() == restoredId) {
                    bookmarkId = restoredId;
                    bookmarkIndex = i + 1;
                    if(episodeAdapter != null)
                        episodeAdapter.setBookmark(bookmarkIndex);
                    return episode;
                }
            }
        }
        int firstEpisodeIndex = firstReadableEpisodeIndexForTest(episodes);
        Manga episode = safeGet(episodes, firstEpisodeIndex);
        return episode != null ? episode : safeGet(episodes, 0);
    }

    static int firstReadableEpisodeIndexForTest(List<Manga> episodes) {
        return PrefetchCoordinator.firstEpisodeIndex(episodes);
    }

    @SuppressWarnings("unchecked")
    private void renderEpisodeState(UiState<EpisodeLoadResult> state) {
        if(!isUiAlive())
            return;
        if(state instanceof UiState.Loading) {
            hideProgress();
            return;
        }
        if(state instanceof UiState.Error) {
            ntkLoadTimeoutHandled = true;
            cancelNtkEpisodeLoadWatchdog();
            hideProgress();
            if(hasRenderedEpisodes())
                return;
            handleLoadErrorWithCacheFallback();
            return;
        }
        if(!(state instanceof UiState.Content))
            return;
        EpisodeLoadResult result = ((UiState.Content<EpisodeLoadResult>) state).getValue();
        if(result == null) {
            hideProgress();
            return;
        }
        if(result.getResultCode() == LOAD_CAPTCHA){
            ntkLoadTimeoutHandled = true;
            cancelNtkEpisodeLoadWatchdog();
            if(p != null && p.isNtkSite())
                openNtkCaptchaDirect();
            else
                showCaptchaPopup(title == null ? "" : title.getUrl(), context, RESULT_CAPTCHA, p);
            return;
        }
        if(result.getResultCode() == LOAD_ERROR){
            ntkLoadTimeoutHandled = true;
            cancelNtkEpisodeLoadWatchdog();
            handleLoadErrorWithCacheFallback();
            return;
        }
        ArrayList<Manga> loadedEpisodes = normalizeEpisodeSnapshot(result.getEpisodes(), title);
        if(loadedEpisodes.size()==0){
            if(this.episodes == null || this.episodes.size() == 0) {
                ntkLoadTimeoutHandled = true;
                cancelNtkEpisodeLoadWatchdog();
                if(title != null)
                    showCaptchaPopup(title.getUrl(), context, p);
            }
            return;
        }
        cancelNtkEpisodeLoadWatchdog();
        if(sameEpisodeIdentityList(episodes, loadedEpisodes) && hasRenderedEpisodes()) {
            ntkLoadTimeoutHandled = true;
            mergeFreshEpisodeMetadata(episodes, loadedEpisodes);
            attachLoadedEpisodesToTitle(episodes);
            saveEpisodeCache(episodes);
            hideProgress();
            loaded = true;
            invalidateOptionsMenu();
            return;
        }
        episodes = loadedEpisodes;
        ntkLoadTimeoutHandled = true;
        attachLoadedEpisodesToTitle(episodes);
        saveEpisodeCache(episodes);
        episodeAdapter = new EpisodeAdapter(context, episodes, title, mode);
        afterLoad();
        hideProgress();
        loaded = true;
        fab_container.setVisibility(View.GONE);
        invalidateOptionsMenu();
    }

    private void scheduleNtkEpisodeLoadWatchdog() {
        if(!online || p == null || !p.isNtkSite() || episodeList == null)
            return;
        cancelNtkEpisodeLoadWatchdog();
        ntkEpisodeLoadWatchdogRunnable = () -> {
            if(!isUiAlive() || loaded || ntkLoadTimeoutHandled)
                return;
            if(episodeAdapter != null && episodeAdapter.getItemCount() > 0)
                return;
            ntkLoadTimeoutHandled = true;
            hideProgress();
            openNtkCaptchaDirect();
        };
        episodeList.postDelayed(ntkEpisodeLoadWatchdogRunnable, 3000L);
    }

    private void cancelNtkEpisodeLoadWatchdog() {
        if(episodeList != null && ntkEpisodeLoadWatchdogRunnable != null)
            episodeList.removeCallbacks(ntkEpisodeLoadWatchdogRunnable);
        ntkEpisodeLoadWatchdogRunnable = null;
    }

    private void openNtkCaptchaDirect() {
        if(!isUiAlive() || ntkCaptchaLaunchInFlight)
            return;
        ntkCaptchaLaunchInFlight = true;
        AppDispatchers.runUserAction(() -> {
            if(p != null && p.isNtkSite())
                getHttpClient().resolveNtkDomainNow();
            AppDispatchers.runOnMain(() -> {
                ntkCaptchaLaunchInFlight = false;
                if(!isUiAlive())
                    return;
                Intent captchaIntent = new Intent(context, CaptchaActivity.class);
                String url = title == null ? null : title.getUrl();
                if(url != null && url.startsWith("/"))
                    url = getHttpClient().getUrl(url) + url;
                if(url == null || url.length() == 0)
                    url = p == null ? CustomHttpClient.NTK_WEBTOON_URL : p.getWebtoonUrl();
                captchaIntent.putExtra("url", url);
                startActivityForResult(captchaIntent, RESULT_CAPTCHA);
            });
        });
    }

    private boolean showCachedEpisodesFromMemory() {
        try {
            String json = CacheFileStore.readMemory(episodeCacheKey());
            if(json == null || json.length() == 0)
                json = CacheFileStore.read(getApplicationContext(), episodeCacheKey());
            if(json == null || json.length() == 0)
                return false;
            if(shouldParseMemoryCacheOnMain(json.length()))
                return showCachedEpisodesJson(json);
            String cacheJson = json;
            AppDispatchers.submitIo(() -> {
                CachedEpisodes cached = parseCachedEpisodesJson(cacheJson);
                if(cached == null)
                    return;
                AppDispatchers.runOnMain(() -> {
                    if(isUiAlive())
                        showCachedEpisodes(cached);
                });
            });
            return false;
        } catch (Exception e) {
            ml.melun.mangaview.report.CrashReporter.record(e);
            return false;
        }
    }

    private boolean deleteOfflineEpisodeAsync(int position, Manga manga) {
        if(manga == null)
            return true;
        Context appContext = getApplicationContext();
        AppDispatchers.submitIo(() -> {
            boolean deleted = OfflineStore.deleteEpisode(appContext, manga);
            AppDispatchers.runOnMain(() -> {
                if(!isUiAlive())
                    return;
                if(!deleted) {
                    Toast.makeText(context, "삭제를 실패했습니다.", Toast.LENGTH_SHORT).show();
                    return;
                }
                p.removeViewerBookmark(manga);
                if(position >= 0 && episodes != null && position < episodes.size() && episodeAdapter != null) {
                    if(episodes.get(position) == manga)
                        episodeAdapter.removeEpisode(position);
                    else {
                        episodes.remove(manga);
                        episodeAdapter.notifyItemRangeChanged(0, episodeAdapter.getItemCount());
                    }
                }
                Toast.makeText(context, "삭제가 완료되었습니다.", Toast.LENGTH_SHORT).show();
                if(episodes == null || episodes.size() == 0) {
                    deleteEmptyOfflineTitleAsync();
                    finish();
                }
            });
        });
        return true;
    }

    private void deleteEmptyOfflineTitleAsync() {
        if(title == null || title.getPath() == null || title.getPath().length() == 0)
            return;
        Context appContext = getApplicationContext();
        Title currentTitle = title;
        AppDispatchers.submitIo(() -> {
            try {
                OfflineStore.deleteTitle(appContext, currentTitle);
            } catch (Exception e) {
                ml.melun.mangaview.report.CrashReporter.record(e);
            }
        });
    }

    private void loadCachedEpisodesAsync() {
        Context appContext = getApplicationContext();
        String cacheKey = episodeCacheKey();
        AppDispatchers.submitIo(() -> {
            long startedAt = PerfTrace.start("episode_cache_async_load_ms");
            CachedEpisodes cached = readCachedEpisodes(appContext, cacheKey);
            PerfTrace.end("episode_cache_async_load_ms", startedAt);
            if(cached == null)
                return;
            AppDispatchers.runOnMain(() -> {
                if(isUiAlive())
                    showCachedEpisodes(cached);
            });
        });
    }

    private CachedEpisodes readCachedEpisodes(Context cacheContext, String cacheKey) {
        try {
            String json = CacheFileStore.read(cacheContext, cacheKey);
            if(json == null || json.length() == 0)
                return null;
            CachedEpisodes cached = parseCachedEpisodesJson(json);
            if(cached == null)
                return null;
            return cached;
        } catch (Exception e) {
            ml.melun.mangaview.report.CrashReporter.record(e);
            return null;
        }
    }

    private boolean showCachedEpisodesJson(String json) {
        if(json == null || json.length() == 0)
            return false;
        CachedEpisodes cached = new Gson().fromJson(json, new TypeToken<CachedEpisodes>(){}.getType());
        return showCachedEpisodes(cached);
    }

    private CachedEpisodes parseCachedEpisodesJson(String json) {
        if(json == null || json.length() == 0)
            return null;
        CachedEpisodes cached = new Gson().fromJson(json, new TypeToken<CachedEpisodes>(){}.getType());
        return isUsableCachedEpisodes(cached) ? cached : null;
    }

    private static boolean shouldParseMemoryCacheOnMain(int jsonLength) {
        return jsonLength > 0 && jsonLength <= MEMORY_CACHE_MAIN_THREAD_PARSE_MAX_CHARS;
    }

    static boolean shouldParseMemoryCacheOnMainForTest(int jsonLength) {
        return shouldParseMemoryCacheOnMain(jsonLength);
    }

    private boolean showCachedEpisodes(CachedEpisodes cached) {
        if(!isUsableCachedEpisodes(cached))
            return false;
        if(hasRenderedEpisodes())
            return false;
        episodes = normalizeEpisodeSnapshot(cached.episodes, title);
        if(episodes.size() == 0)
            return false;
        attachLoadedEpisodesToTitle(episodes);
        episodeAdapter = new EpisodeAdapter(context, episodes, title, mode);
        afterLoad();
        ntkLoadTimeoutHandled = true;
        loaded = true;
        hideProgress();
        invalidateOptionsMenu();
        return true;
    }

    private boolean isUsableCachedEpisodes(CachedEpisodes cached) {
        return cached != null
                && cached.episodes != null
                && cached.episodes.size() > 0
                && (CachePolicy.isFresh(cached.savedAt, CachePolicy.EPISODE_TTL_MS)
                || CachePolicy.isReusableForColdStart(cached.savedAt));
    }

    private void handleLoadErrorWithCacheFallback() {
        if(hasRenderedEpisodes()) {
            hideProgress();
            return;
        }
        if(compatibleCacheLookupInFlight) {
            pendingLoadErrorAfterCacheLookup = true;
            return;
        }
        compatibleCacheLookupInFlight = true;
        pendingLoadErrorAfterCacheLookup = true;
        Context appContext = getApplicationContext();
        Title target = title;
        String stableName = originalTitleName;
        AppDispatchers.submitIo(() -> {
            CompatibleCachedEpisodes compatible = findCompatibleCachedEpisodes(appContext, target, stableName);
            AppDispatchers.runOnMain(() -> {
                compatibleCacheLookupInFlight = false;
                if(!isUiAlive())
                    return;
                if(applyCompatibleCachedEpisodes(compatible)) {
                    pendingLoadErrorAfterCacheLookup = false;
                    return;
                }
                if(pendingLoadErrorAfterCacheLookup && !hasRenderedEpisodes()) {
                    pendingLoadErrorAfterCacheLookup = false;
                    ntkLoadTimeoutHandled = true;
                    cancelNtkEpisodeLoadWatchdog();
                    hideProgress();
                    showErrorPopup(context, "정보를 불러오는데 실패하였습니다.", null, false);
                }
            });
        });
    }

    private boolean applyCompatibleCachedEpisodes(CompatibleCachedEpisodes compatible) {
        if(compatible == null || title == null)
            return false;
        if(hasRenderedEpisodes() || !isUsableCachedEpisodes(compatible.cached))
            return false;
        if(compatible.sourceSite != null && compatible.sourceSite.length() > 0)
            title.setSourceSite(compatible.sourceSite);
        if(compatible.titleId > 0)
            title.setId(compatible.titleId);
        if(originalTitleName != null && originalTitleName.trim().length() > 0)
            title.setName(originalTitleName);
        if(compatible.episodeCount > 0)
            title.setReadingProgress(title.getBookmarkEpisodeId(), title.getBookmarkEpisodeIndex(), compatible.episodeCount);
        boolean shown = showCachedEpisodes(compatible.cached);
        if(shown) {
            cancelNtkEpisodeLoadWatchdog();
            Log.d("ViewerPerf", "episode_cache_compatible_hit source=" + compatible.sourceSite
                    + ",id=" + compatible.titleId + ",name=" + title.getName());
        }
        return shown;
    }

    private void mergeFreshEpisodeMetadata(List<Manga> current, List<Manga> fresh) {
        if(current == null || fresh == null)
            return;
        int count = Math.min(current.size(), fresh.size());
        for(int i = 0; i < count; i++) {
            Manga target = current.get(i);
            Manga source = fresh.get(i);
            if(target == null || source == null)
                continue;
            if(target.getId() != source.getId() || target.getBaseMode() != source.getBaseMode())
                continue;
            target.copyViewerStateFrom(source);
            String freshPath = source.getNtkEpisodePath();
            if(freshPath != null && freshPath.length() > 0)
                target.setNtkEpisodePath(freshPath);
        }
    }

    private static CompatibleCachedEpisodes findCompatibleCachedEpisodes(Context cacheContext, Title target, String stableName) {
        String matchName = stableName != null && stableName.trim().length() > 0
                ? stableName
                : target == null ? "" : target.getName();
        if(cacheContext == null || target == null || matchName.trim().length() == 0)
            return null;
        File dir = new File(cacheContext.getCacheDir(), "structured_cache");
        File[] files = dir.listFiles();
        if(files == null || files.length == 0)
            return null;
        String normalizedName = normalizeCachedTitleName(matchName);
        String targetSource = normalizeCacheSource(target.getSourceSite());
        CompatibleCachedEpisodes best = null;
        Gson gson = new Gson();
        for(File file : files) {
            CacheFileMeta meta = cacheFileMeta(file == null ? "" : file.getName());
            if(meta == null || meta.baseMode != target.getBaseMode())
                continue;
            if(!isCompatibleCacheSource(targetSource, meta.sourceSite))
                continue;
            if(meta.titleId == target.getId() && meta.sourceSite.equals(target.getSourceSite()))
                continue;
            try {
                String json = readUtf8(file);
                if(json.length() == 0)
                    continue;
                CachedEpisodes cached = gson.fromJson(json, new TypeToken<CachedEpisodes>(){}.getType());
                if(cached == null || cached.episodes == null || cached.episodes.size() == 0)
                    continue;
                if(!CachePolicy.isFresh(cached.savedAt, CachePolicy.EPISODE_TTL_MS)
                        && !CachePolicy.isReusableForColdStart(cached.savedAt))
                    continue;
                int matchScore = cachedEpisodeTitleMatchScore(normalizedName, cached.episodes);
                if(matchScore <= 0)
                    continue;
                CompatibleCachedEpisodes candidate = new CompatibleCachedEpisodes();
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

    private static boolean isCompatibleCacheSource(String targetSource, String candidateSource) {
        if(targetSource == null || targetSource.length() == 0)
            return true;
        return targetSource.equals(normalizeCacheSource(candidateSource));
    }

    static boolean isCompatibleCacheSourceForTest(String targetSource, String candidateSource) {
        return isCompatibleCacheSource(normalizeCacheSource(targetSource), candidateSource);
    }

    static int cachedEpisodeTitleMatchScoreForTest(String titleName, List<Manga> episodes) {
        return cachedEpisodeTitleMatchScore(normalizeCachedTitleName(titleName), episodes);
    }

    private static int cachedEpisodeTitleMatchScore(String normalizedTitleName, List<Manga> episodes) {
        if(normalizedTitleName == null || normalizedTitleName.length() == 0 || episodes == null)
            return 0;
        int matches = 0;
        int checked = 0;
        for(Manga episode : episodes) {
            if(episode == null)
                continue;
            checked++;
            String episodeName = normalizeCachedTitleName(episode.getName());
            if(episodeName.startsWith(normalizedTitleName))
                matches++;
            if(checked >= 20)
                break;
        }
        if(matches >= 2)
            return matches;
        return matches == 1 && episodes.size() == 1 ? 1 : 0;
    }

    private static String normalizeCachedTitleName(String value) {
        return value == null ? "" : value.toLowerCase(java.util.Locale.ROOT).replaceAll("\\s+", "");
    }

    private static String normalizeCacheSource(String value) {
        return value == null ? "" : value.trim().toLowerCase(java.util.Locale.ROOT);
    }

    private static CacheFileMeta cacheFileMeta(String fileName) {
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

    private boolean hasRenderedEpisodes() {
        return loaded
                && episodes != null
                && episodes.size() > 0
                && episodeAdapter != null
                && episodeAdapter.getItemCount() > 0;
    }

    private void attachLoadedEpisodesToTitle(List<Manga> loadedEpisodes) {
        if(title == null || loadedEpisodes == null || loadedEpisodes.size() == 0)
            return;
        for(Manga episode : loadedEpisodes) {
            if(episode == null)
                continue;
            episode.setTitle(title);
            episode.setTitleId(title.getId());
        }
        title.setEps(loadedEpisodes);
    }

    private static ArrayList<Manga> normalizeEpisodeSnapshot(List<Manga> loadedEpisodes, Title title) {
        ArrayList<Manga> normalized = Title.orderedEpisodeSnapshot(loadedEpisodes);
        if(normalized == null)
            normalized = new ArrayList<>();
        if(title == null)
            return normalized;
        for(Manga episode : normalized) {
            if(episode == null)
                continue;
            episode.setTitle(title);
            episode.setTitleId(title.getId());
        }
        return normalized;
    }

    private void hideProgress() {
        // Loading indicators are intentionally not shown on the episode screen.
    }

    private void saveEpisodeCache(List<Manga> episodes) {
        if(title == null || episodes == null || episodes.size() == 0)
            return;
        Context appContext = getApplicationContext();
        String cacheKey = episodeCacheKey();
        ArrayList<Manga> episodeSnapshot = episodeCacheSnapshot(episodes);
        AppDispatchers.submitIo(() -> {
            CachedEpisodes cached = new CachedEpisodes();
            cached.savedAt = System.currentTimeMillis();
            cached.episodes = episodeSnapshot;
            CacheFileStore.write(appContext, cacheKey, new Gson().toJson(cached));
        });
    }

    private static ArrayList<Manga> episodeCacheSnapshot(List<Manga> episodes) {
        ArrayList<Manga> snapshot = new ArrayList<>();
        if(episodes == null)
            return snapshot;
        ArrayList<Manga> orderedEpisodes = Title.orderedEpisodeSnapshot(episodes);
        if(orderedEpisodes == null)
            return snapshot;
        for(Manga episode : orderedEpisodes) {
            if(episode == null)
                continue;
            Manga copy = new Manga(episode.getId(), episode.getName(), episode.getDate(), episode.getBaseMode());
            copy.addThumb(episode.getThumb());
            copy.setMode(episode.getMode());
            copy.setTitleId(episode.getTitleId());
            copy.setNtkEpisodePath(episode.getNtkEpisodePath());
            copy.setOfflinePath(episode.getOfflinePath());
            snapshot.add(copy);
        }
        return snapshot;
    }

    private String episodeCacheKey() {
        return EpisodeSnapshotCache.key(title, isNtkTitle());
    }

    private boolean isNtkTitle() {
        if(title != null && title.getSourceSite() != null)
            return "ntk".equals(title.getSourceSite().trim().toLowerCase(java.util.Locale.ROOT));
        return p != null && p.isNtkSite();
    }

    private void markFirstContent() {
        if(firstContentLogged)
            return;
        firstContentLogged = true;
        PerfTrace.end("episode_first_content_ms", firstContentStartedAt);
    }

    static boolean sameEpisodeIdentityList(List<Manga> current, List<Manga> fresh) {
        if(current == null || fresh == null || current.size() != fresh.size())
            return false;
        for(int i = 0; i < current.size(); i++) {
            Manga left = current.get(i);
            Manga right = fresh.get(i);
            if(left == null || right == null) {
                if(left != right)
                    return false;
                continue;
            }
            if(left.getId() != right.getId() || left.getBaseMode() != right.getBaseMode())
                return false;
            String leftPath = left.getNtkEpisodePath();
            String rightPath = right.getNtkEpisodePath();
            if(leftPath != null && leftPath.length() > 0 && rightPath != null && rightPath.length() > 0
                    && !leftPath.equals(rightPath))
                return false;
        }
        return true;
    }

    private static class CachedEpisodes {
        long savedAt;
        ArrayList<Manga> episodes;
    }

    private static class CompatibleCachedEpisodes {
        CachedEpisodes cached;
        String sourceSite;
        int titleId;
        int episodeCount;
        int score;
    }

    private static class CacheFileMeta {
        String sourceSite;
        int baseMode;
        int titleId;
    }

    public void openViewer(Manga manga, int code){
        openViewer(manga, code, false);
    }

    public void openViewer(Manga manga, int code, boolean exactEpisode){
        if(manga == null || title == null)
            return;
        if(!ml.melun.mangaview.Utils.consumeFocusedDestinationLaunch(this, DESTINATION_LAUNCH_DEBOUNCE_MS))
            return;
        manga.setMode(mode);
        manga.setTitle(title);
        manga.setTitleId(title == null ? manga.getTitleId() : title.getId());
        if(!exactEpisode && getHttpClient().isNtk())
            ViewerWarmupManager.warmup(context, manga, title);
        openViewerPrepared(context, manga, code, false, online, true, title, !manga.isOnline(), exactEpisode);
    }

    private void warmupUserSelectedEpisode(Manga manga) {
        if(!online || manga == null || title == null)
            return;
        manga.setMode(mode);
        manga.setTitle(title);
        manga.setTitleId(title.getId());
        ViewerWarmupManager.warmupUserSelectedEpisode(context, manga, title, 0);
    }

    private boolean shouldRefreshEpisodesAfterCache(boolean renderedCachedEpisodes) {
        return !renderedCachedEpisodes || isNtkTitle() || getHttpClient().isNtk();
    }

    static boolean shouldLoadDiskEpisodeCacheAsyncForTest(boolean renderedMemoryCache) {
        return !renderedMemoryCache;
    }

    static ArrayList<Manga> episodeCacheSnapshotForTest(List<Manga> episodes) {
        return episodeCacheSnapshot(episodes);
    }

    static ArrayList<Manga> normalizeEpisodeSnapshotForTest(List<Manga> episodes) {
        return normalizeEpisodeSnapshot(episodes, null);
    }

    static Title parseIntentTitleForTest(String json) {
        return parseIntentTitle(json);
    }

    static boolean shouldSwitchEpisodeListForViewerResultForTest(boolean sourceSwitched, String titleJson) {
        return shouldSwitchEpisodeListForViewerResult(sourceSwitched, titleJson);
    }

    private static Title parseIntentTitle(String json) {
        if(json == null || json.trim().length() == 0)
            return null;
        try {
            return new Gson().fromJson(json, new TypeToken<Title>(){}.getType());
        } catch(Exception e) {
            return null;
        }
    }

    private static boolean shouldSwitchEpisodeListForViewerResult(boolean sourceSwitched, String titleJson) {
        return sourceSwitched && parseIntentTitle(titleJson) != null;
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        super.onCreateOptionsMenu(menu);
        MenuInflater inflater = getMenuInflater();
        if(loaded)
            inflater.inflate(R.menu.episode_menu, menu);
        return true;
    }

    @Override
    public boolean onPrepareOptionsMenu(Menu menu) {
        boolean result = super.onPrepareOptionsMenu(menu);
        MenuItem favorite = menu.findItem(R.id.episode_favorite);
        if(favorite != null)
            favorite.setIcon(p.findFavorite(title) > -1 ? R.drawable.ic_favorite : R.drawable.ic_favorite_border);
        return result;
    }

    private void toggleFavorite() {
        if(title == null)
            return;
        boolean favorite = p.toggleFavorite(title, position);
        if(episodeAdapter != null)
            episodeAdapter.setFavorite(favorite);
        invalidateOptionsMenu();
        if(favoriteResult){
            Intent resultIntent = new Intent();
            resultIntent.putExtra("favorite", favorite);
            setResult(RESULT_OK, resultIntent);
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        PerformanceMonitor.resume();
    }

    @Override
    protected void onPause() {
        PerformanceMonitor.pause();
        super.onPause();
        overridePendingTransition(0, 0);
    }

    @Override
    protected void onDestroy() {
        destroyed = true;
        cancelEpisodeRefresh();
        if(episodeList != null)
            episodeList.removeCallbacks(initialEpisodeWarmupRunnable);
        cancelVisibleEpisodeWarmup();
        cancelNtkEpisodeLoadWatchdog();
        if(episodeViewModel != null && !isChangingConfigurations())
            episodeViewModel.cancelActiveLoad();
        if(episodeList != null)
            episodeList.setAdapter(null);
        super.onDestroy();
    }

    private boolean isUiAlive() {
        return !destroyed && !isFinishing() && !isDestroyed();
    }

    private void loadOfflineEpisodesAsync() {
        Context appContext = getApplicationContext();
        Title currentTitle = title;
        AppDispatchers.submitIo(() -> {
            OfflineStore.OfflineEpisodes offlineEpisodes = OfflineStore.loadEpisodes(appContext, currentTitle);
            AppDispatchers.runOnMain(() -> {
                if(!isUiAlive())
                    return;
                episodes = normalizeEpisodeSnapshot(offlineEpisodes.episodes, title);
                attachLoadedEpisodesToTitle(episodes);
                mode = offlineEpisodes.mode;
                episodeAdapter = new EpisodeAdapter(context, episodes, title, mode);
                afterLoad();
            });
        });
    }

    private void applyEpisodeWindowChrome() {
        View root = findViewById(android.R.id.content);
        View appBar = findViewById(R.id.episode_toolbar);
        View toolbar = findViewById(R.id.toolbar);
        int surface = ContextCompat.getColor(this, dark ? R.color.colorDarkWindowBackground : R.color.appSurface);
        int chrome = ContextCompat.getColor(this, dark ? R.color.colorDarkSurface : R.color.appSurface);
        getWindow().setStatusBarColor(chrome);
        getWindow().setNavigationBarColor(surface);
        if(root != null)
            root.setBackgroundColor(surface);
        if(appBar != null)
            appBar.setBackgroundColor(chrome);
        if(toolbar != null)
            toolbar.setBackgroundColor(chrome);
        if(episodeList != null)
            episodeList.setBackgroundColor(surface);
        if(dark) {
            getWindow().getDecorView().setSystemUiVisibility(0);
            return;
        }
        getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR);
    }

}
