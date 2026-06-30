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
import android.widget.FrameLayout;
import android.widget.PopupMenu;
import android.widget.TextView;
import android.widget.Toast;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.net.HttpURLConnection;
import java.net.URL;

import ml.melun.mangaview.ui.NpaLinearLayoutManager;
import ml.melun.mangaview.R;
import ml.melun.mangaview.adapter.EpisodeAdapter;
import ml.melun.mangaview.adapter.TagAdapter;
import ml.melun.mangaview.glide.ViewerWarmupManager;
import ml.melun.mangaview.mangaview.Manga;
import ml.melun.mangaview.mangaview.Title;
import ml.melun.mangaview.model.EpisodeLoadResult;
import ml.melun.mangaview.reader.ReaderImageCache;
import ml.melun.mangaview.reader.ReaderWarmupCoordinator;
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
    private static final long EPISODE_REFRESH_AFTER_CACHE_PROBE_MS = EpisodeWarmupPolicy.REFRESH_AFTER_CACHE_PROBE_MS;
    private static final long INITIAL_VIEWER_TARGET_WARMUP_DELAY_MS = EpisodeWarmupPolicy.INITIAL_VIEWER_TARGET_DELAY_MS;
    private static final int NTK_DIRECT_INITIAL_PREFETCH_PAGES = 12;
    private static final String CONFIRMED_EMPTY_EPISODE_TITLE = "\uD68C\uCC28\uAC00 \uC544\uC9C1 \uC5C6\uC2B5\uB2C8\uB2E4";
    private static final String CONFIRMED_EMPTY_EPISODE_MESSAGE =
            "NTK\uAC00 \uC774 \uC791\uD488\uC758 \uD68C\uCC28 \uBAA9\uB85D\uC744 0\uAC1C\uB85C \uC751\uB2F5\uD588\uC2B5\uB2C8\uB2E4. \uC5C5\uB370\uC774\uD2B8\uB418\uBA74 \uB2E4\uC2DC \uD45C\uC2DC\uB429\uB2C8\uB2E4.";
    //global variables
    Title title;
    EpisodeAdapter episodeAdapter;
    Context context = this;
    RecyclerView episodeList;
    View episodeEmptyState;
    TextView episodeEmptyTitle;
    TextView episodeEmptyMessage;
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
    boolean ntkCaptchaRetryAfterVerifiedAttempted = false;
    boolean destroyed = false;
    boolean compatibleCacheLookupInFlight = false;
    boolean captchaCacheLookupInFlight = false;
    boolean pendingLoadErrorAfterCacheLookup = false;
    Runnable ntkEpisodeLoadWatchdogRunnable;
    Runnable episodeRefreshRunnable;
    String pendingBrowserOwnedViewerLaunchPath = "";
    final Set<String> acceptedCanonicalDirectPaths = Collections.newSetFromMap(new ConcurrentHashMap<>());
    final Set<String> rejectedCanonicalDirectPaths = Collections.newSetFromMap(new ConcurrentHashMap<>());
    final Set<String> speculativeCanonicalDirectPrefetchPaths = Collections.newSetFromMap(new ConcurrentHashMap<>());
    final ConcurrentHashMap<String, ArrayList<String>> acceptedCanonicalDirectImages = new ConcurrentHashMap<>();
    String originalTitleName = "";
    final Runnable initialEpisodeWarmupRunnable = () -> {
        if(!isUiAlive())
            return;
        if(episodeList != null && episodeList.getScrollState() != RecyclerView.SCROLL_STATE_IDLE) {
            scheduleInitialEpisodeWarmups(EpisodeWarmupPolicy.VIEWER_TARGET_IDLE_DELAY_MS);
            return;
        }
        warmupInitialViewerTargets();
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
            String switchedTitleJson = data.getStringExtra(ViewerIntentContract.EXTRA_RETURN_EPISODE_TITLE);
            if(shouldSwitchEpisodeListForViewerResult(
                    data.getBooleanExtra(ViewerIntentContract.EXTRA_RETURN_EPISODE_SOURCE_SWITCHED, false),
                    switchedTitleJson)) {
                restartWithViewerResultTitle(switchedTitleJson);
                return;
            }
            int newid = data.getIntExtra("id", -1);
            if(newid>0 && newid!=bookmarkId){
                int matchedIndex = -1;
                if(episodes != null && episodes.size() > 0) {
                    for(int i=0; i< episodes.size(); i++){
                            Manga episode = safeGet(episodes, i);
                            if(episode != null && episode.getId()==newid){
                                matchedIndex = i+1;
                                break;
                            }
                    }
                    if(matchedIndex > 0) {
                        bookmarkId = newid;
                        bookmarkIndex = matchedIndex;
                        if(episodeAdapter != null)
                            episodeAdapter.setBookmark(bookmarkIndex);
                    }
                } else {
                    bookmarkId = newid;
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
        Intent intent = getIntent();
        title = parseIntentTitle(intent.getStringExtra("title"));
        if(title == null) {
            Toast.makeText(this, "작품 정보를 열 수 없습니다.", Toast.LENGTH_SHORT).show();
            finish();
            return;
        }
        originalTitleName = title.getName();
        switchToTitleSourceSite();
        online = intent.getBooleanExtra("online", true);
        setContentView(R.layout.activity_episode);
        warmResumeNtkViewerPageEarly();
        applyEpisodeWindowChrome();
        firstContentStartedAt = PerfTrace.start("episode_first_content_ms");
        if(title.useBookmark())
            bookmarkId = restoredBookmarkId(title);
        position = intent.getIntExtra("position",0);
        favoriteResult = intent.getBooleanExtra("favorite",false);
        recentResult = intent.getBooleanExtra("recent",false);
        episodeList = this.findViewById(R.id.EpisodeList);
        episodeEmptyState = this.findViewById(R.id.episode_empty_state);
        episodeEmptyTitle = this.findViewById(R.id.episode_empty_title);
        episodeEmptyMessage = this.findViewById(R.id.episode_empty_message);
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
            }

            @Override
            public void onScrollStateChanged(@NonNull RecyclerView recyclerView, int newState) {
                super.onScrollStateChanged(recyclerView, newState);
                PerformanceMonitor.phase(newState == RecyclerView.SCROLL_STATE_IDLE ? "idle" : "scrolling");
                if(newState == RecyclerView.SCROLL_STATE_IDLE) {
                    PerformanceMonitor.reportNow("episode_scroll_idle");
                }
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
            boolean renderedCachedEpisodes = showProvidedEpisodesFromIntent();
            if(!renderedCachedEpisodes)
                renderedCachedEpisodes = showCachedEpisodesFromMemory();
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
        syncLoadedEpisodeProgress();
        episodeAdapter.setFavorite(p.findFavorite(title)>-1);
        episodeList.setAdapter(episodeAdapter);
        if(title != null && title.isNtkEpisodeListConfirmedEmpty()
                && episodeAdapter != null && episodeAdapter.getItemCount() > 1) {
            episodeList.post(() -> {
                if(episodeList != null)
                    episodeList.scrollToPosition(1);
            });
        }
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
                warmSelectedNtkEpisodeForOpen(selected);
                saveSelectedEpisodeProgress(position, selected);
                openViewer(selected,0, true);
            }
            @Override
            public void onItemPress(int position, Manga selected) {
                if(selected != null && isNtkTitle()) {
                    selected.setMode(mode);
                    selected.setTitle(title);
                    selected.setTitleId(title == null ? selected.getTitleId() : title.getId());
                    selected.ensureNtkEpisodePathFromIdentity();
                    installImmediateNtkWebtoonDirectManifest(
                            selected,
                            selected.getNtkEpisodePath(),
                            Math.max(0, selected.getNtkImageCount()),
                            true,
                            false);
                    ml.melun.mangaview.MainApplication.warmNtkBrowserSessionForEpisode(
                            EpisodeActivity.this, selected, title);
                }
            }
            @Override
            public void onItemVisible(int position, Manga selected) {
                warmVisibleNtkEpisode(selected);
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
        warmupInitialViewerTargets();
    }

    private void syncLoadedEpisodeProgress() {
        if(p == null || title == null || episodes == null || episodes.size() == 0)
            return;
        int progressId = bookmarkId;
        int progressIndex = bookmarkIndex;
        int storedIndex = title.getBookmarkEpisodeIndex();
        boolean progressIdMatched = false;
        if(progressId <= 0)
            progressId = restoredBookmarkId(title);
        if(progressIndex <= 0 && progressId > 0) {
            for(int i = 0; i < episodes.size(); i++) {
                Manga episode = safeGet(episodes, i);
                if(episode != null && episode.getId() == progressId) {
                    progressIndex = i + 1;
                    progressIdMatched = true;
                    break;
                }
            }
        }
        if(progressIndex <= 0 && title.getResumeNtkEpisodePath().length() > 0) {
            String resumePath = title.getResumeNtkEpisodePath();
            for(int i = 0; i < episodes.size(); i++) {
                Manga episode = safeGet(episodes, i);
                if(episode != null && resumePath.equals(episode.getNtkEpisodePath())) {
                    progressIndex = i + 1;
                    progressId = episode.getId();
                    progressIdMatched = true;
                    break;
                }
            }
        }
        if(progressIndex <= 0)
            progressIndex = storedIndex;
        if(progressIndex > episodes.size())
            progressIndex = -1;
        if((progressId <= 0 || !progressIdMatched) && progressIndex > 0) {
            Manga episode = safeGet(episodes, progressIndex - 1);
            if(episode != null) {
                progressId = episode.getId();
                String ntkPath = episode.getNtkEpisodePath();
                if(isNtkTitle() && ntkPath != null && ntkPath.length() > 0)
                    title.setResumeNtkEpisodePath(ntkPath);
            }
        }
        if(progressId > 0) {
            title.setBookmark(progressId);
            bookmarkId = progressId;
            p.setBookmark(title, progressId);
        }
        bookmarkIndex = progressIndex;
        title.setReadingProgress(progressId, progressIndex, episodes.size());
        p.updateRecentData(title);
        if(progressIndex > 0 && episodeAdapter != null)
            episodeAdapter.setBookmark(progressIndex);
    }

    private void warmupInitialViewerTargets() {
        if(!isUiAlive())
            return;
        if(!online || episodes == null || episodes.size() == 0)
            return;
        if(isWfwfTitle()) {
            warmupLikelyWfwfViewerPage();
            return;
        }
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

    static long episodeRefreshAfterCacheProbeMsForTest() {
        return EPISODE_REFRESH_AFTER_CACHE_PROBE_MS;
    }

    static long initialViewerTargetWarmupDelayMsForTest(boolean ntkSite) {
        return EpisodeWarmupPolicy.initialViewerTargetDelay(ntkSite);
    }

    private void warmupLikelyNtkViewerPage() {
        if(!p.isNtkSite() && !getHttpClient().isNtk())
            return;
        Manga target = quickReadEpisode();
        if(target == null)
            return;
        target.setMode(mode);
        target.setTitle(title);
        target.setTitleId(title == null ? target.getTitleId() : title.getId());
        target.ensureNtkEpisodePathFromIdentity();
        installImmediateNtkManhwaDirectManifest(
                target,
                target.getNtkEpisodePath(),
                Math.max(0, target.getNtkImageCount()));
        installImmediateNtkWebtoonDirectManifest(
                target,
                target.getNtkEpisodePath(),
                Math.max(0, target.getNtkImageCount()),
                true,
                false);
        ml.melun.mangaview.MainApplication.warmNtkBrowserSessionForEpisode(this, target, title);
        if(EpisodeWarmupPolicy.shouldDirectWarmupNtkViewerPage(p.isNtkSite(), getHttpClient().isNtk(),
                target.getNtkEpisodePath())) {
            ReaderWarmupCoordinator.primeExactImmediate(context, target, title);
        } else {
            ViewerWarmupManager.logMetric("ntk_initial_viewer_warmup_skipped", 1L);
        }
    }

    private void warmResumeNtkViewerPageEarly() {
        if(!online || title == null || !isNtkTitle())
            return;
        String path = title.getResumeNtkEpisodePath();
        if(path == null || path.length() == 0)
            return;
        if(!path.startsWith("/manhwa/") && !path.startsWith("/webtoon/"))
            return;
        Manga target = ntkEpisodeFromPathForWarm(path);
        if(target == null)
            return;
        ml.melun.mangaview.MainApplication.warmNtkBrowserSessionForEpisode(this, target, title);
    }

    private Manga ntkEpisodeFromPathForWarm(String path) {
        try {
            String[] parts = path.split("/");
            if(parts.length < 4)
                return null;
            int episodeId = Integer.parseInt(parts[3]);
            Manga target = new Manga(episodeId, episodeId + "화", "", title.getBaseMode());
            target.setMode(mode);
            target.setTitle(title);
            target.setTitleId(title.getId());
            target.setNtkEpisodePath(path);
            return target;
        } catch(Exception ignored) {
            return null;
        }
    }

    private void warmVisibleNtkEpisode(Manga selected) {
        if(!online || selected == null || !isNtkTitle())
            return;
        selected.setMode(mode);
        selected.setTitle(title);
        selected.setTitleId(title == null ? selected.getTitleId() : title.getId());
        selected.ensureNtkEpisodePathFromIdentity();
        installImmediateNtkManhwaDirectManifest(
                selected,
                selected.getNtkEpisodePath(),
                Math.max(0, selected.getNtkImageCount()));
        installImmediateNtkWebtoonDirectManifest(
                selected,
                selected.getNtkEpisodePath(),
                Math.max(0, selected.getNtkImageCount()),
                false,
                false);
        ml.melun.mangaview.MainApplication.warmVisibleNtkBrowserSessionForEpisode(this, selected, title);
    }

    private void warmSelectedNtkEpisodeForOpen(Manga selected) {
        if(!online || selected == null || !isNtkTitle())
            return;
        selected.setMode(mode);
        selected.setTitle(title);
        selected.setTitleId(title == null ? selected.getTitleId() : title.getId());
        selected.ensureNtkEpisodePathFromIdentity();
        installImmediateNtkWebtoonDirectManifest(
                selected,
                selected.getNtkEpisodePath(),
                Math.max(0, selected.getNtkImageCount()),
                true,
                false);
    }

    private void warmupLikelyWfwfViewerPage() {
        if(!isWfwfTitle())
            return;
        Manga target = quickReadEpisode();
        if(target != null && target.getId() > 1 && title != null && title.getId() > 0) {
            Manga firstEpisode = new Manga(1, "1", "", target.getBaseMode());
            firstEpisode.setMode(mode);
            firstEpisode.setTitle(title);
            firstEpisode.setTitleId(title.getId());
            target = firstEpisode;
        }
        if(target == null)
            return;
        target.setMode(mode);
        target.setTitle(title);
        target.setTitleId(title == null ? target.getTitleId() : title.getId());
        ReaderWarmupCoordinator.primeExactImmediate(context, target, title);
    }

    static boolean shouldDirectWarmupNtkViewerPageForTest(boolean ntkPreference, boolean ntkClient, String episodePath) {
        return EpisodeWarmupPolicy.shouldDirectWarmupNtkViewerPage(ntkPreference, ntkClient, episodePath);
    }

    static boolean shouldPreloadNtkFirstFrameAfterDirectWarmupForTest(boolean directWarmupSucceeded) {
        return EpisodeWarmupPolicy.shouldPreloadNtkFirstFrameAfterDirectWarmup(directWarmupSucceeded);
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

    private boolean isWfwfTitle() {
        String source = title == null ? "" : title.getSourceSite();
        String normalized = source == null ? "" : source.trim().toLowerCase(java.util.Locale.ROOT);
        return "wfwf".equals(normalized) || (normalized.length() == 0 && !getHttpClient().isNtk());
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
            showConfirmedEmptyEpisodeState(false);
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
            showConfirmedEmptyEpisodeState(false);
            return;
        }
        Log.d("EpisodeActivity", "episode load result code=" + result.getResultCode()
                + " eps=" + (result.getEpisodes() == null ? 0 : result.getEpisodes().size())
                + " ntk=" + (p != null && p.isNtkSite())
                + " proof=" + getHttpClient().hasNtkAccessProof()
                + " recent=" + getHttpClient().hasRecentNtkAccessVerification()
                + " titleUrl=" + (title == null ? null : title.getUrl()));
        if(result.getResultCode() == LOAD_CAPTCHA){
            ntkLoadTimeoutHandled = true;
            cancelNtkEpisodeLoadWatchdog();
            Log.d("EpisodeActivity", "LOAD_CAPTCHA received ntk="
                    + (p != null && p.isNtkSite())
                    + " proof=" + getHttpClient().hasNtkAccessProof()
                    + " recent=" + getHttpClient().hasRecentNtkAccessVerification()
                    + " retryAttempted=" + ntkCaptchaRetryAfterVerifiedAttempted
                    + " titleUrl=" + (title == null ? null : title.getUrl()));
            if(retryNtkEpisodeLoadAfterRecentCaptcha())
                return;
            if(handleCaptchaWithCacheFallback())
                return;
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
            if(title != null && title.isNtkEpisodeListConfirmedEmpty()) {
                episodes = loadedEpisodes;
                ntkLoadTimeoutHandled = true;
                cancelNtkEpisodeLoadWatchdog();
                attachLoadedEpisodesToTitle(episodes);
                episodeAdapter = new EpisodeAdapter(context, episodes, title, mode);
                afterLoad();
                hideProgress();
                showConfirmedEmptyEpisodeState(true);
                loaded = true;
                ntkCaptchaRetryAfterVerifiedAttempted = false;
                fab_container.setVisibility(View.GONE);
                invalidateOptionsMenu();
                Log.d("EpisodeActivity", "empty NTK episode list rendered titleUrl=" + title.getUrl());
                return;
            }
            if(this.episodes == null || this.episodes.size() == 0) {
                ntkLoadTimeoutHandled = true;
                cancelNtkEpisodeLoadWatchdog();
                if(p != null && p.isNtkSite() && getHttpClient().hasNtkAccessProof()) {
                    Log.d("EpisodeActivity", "empty NTK episode result after verified captcha; using error fallback");
                    handleLoadErrorWithCacheFallback();
                } else if(title != null) {
                    showCaptchaPopup(title.getUrl(), context, p);
                }
            }
            return;
        }
        cancelNtkEpisodeLoadWatchdog();
        showConfirmedEmptyEpisodeState(false);
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
        warmupInitialViewerTargets();
        episodeAdapter = new EpisodeAdapter(context, episodes, title, mode);
        afterLoad();
        hideProgress();
        loaded = true;
        ntkCaptchaRetryAfterVerifiedAttempted = false;
        fab_container.setVisibility(View.GONE);
        invalidateOptionsMenu();
    }

    private void showConfirmedEmptyEpisodeState(boolean show) {
        if(episodeEmptyState == null)
            return;
        episodeEmptyState.setVisibility(show ? View.VISIBLE : View.GONE);
        if(!show)
            return;
        episodeEmptyState.bringToFront();
        if(episodeEmptyTitle != null)
            episodeEmptyTitle.setText(CONFIRMED_EMPTY_EPISODE_TITLE);
        if(episodeEmptyMessage != null)
            episodeEmptyMessage.setText(CONFIRMED_EMPTY_EPISODE_MESSAGE);
    }

    private boolean retryNtkEpisodeLoadAfterRecentCaptcha() {
        if(!online || title == null || episodeViewModel == null || ntkCaptchaRetryAfterVerifiedAttempted) {
            Log.d("EpisodeActivity", "skip NTK captcha retry gate online=" + online
                    + " hasTitle=" + (title != null)
                    + " hasViewModel=" + (episodeViewModel != null)
                    + " retryAttempted=" + ntkCaptchaRetryAfterVerifiedAttempted);
            return false;
        }
        if(p == null || !p.isNtkSite()) {
            Log.d("EpisodeActivity", "skip NTK captcha retry: not ntk source");
            return false;
        }
        boolean hasProof = getHttpClient().hasNtkAccessProof();
        boolean hasRecentVerification = getHttpClient().hasRecentNtkAccessVerification();
        if(!hasProof) {
            Log.d("EpisodeActivity", "skip NTK captcha retry: no clearance proof");
            return false;
        }
        Log.d("EpisodeActivity", "retrying NTK episode load after captcha proof="
                + hasProof + " recent=" + hasRecentVerification);
        ntkCaptchaRetryAfterVerifiedAttempted = true;
        getHttpClient().clearNtkTransientLoads();
        startEpisodeRefresh(true);
        return true;
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
            if(retryNtkEpisodeLoadAfterRecentCaptcha())
                return;
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
        if(!isUiAlive() || ntkCaptchaLaunchInFlight) {
            Log.d("EpisodeActivity", "skip direct NTK captcha launch alive=" + isUiAlive()
                    + " inFlight=" + ntkCaptchaLaunchInFlight);
            return;
        }
        if(p != null && p.isNtkSite() && getHttpClient().hasNtkAccessProof()) {
            Log.d("EpisodeActivity", "skip direct NTK captcha launch: clearance already verified proof="
                    + getHttpClient().hasNtkAccessProof()
                    + " recent=" + getHttpClient().hasRecentNtkAccessVerification());
            if(!retryNtkEpisodeLoadAfterRecentCaptcha())
                handleLoadErrorWithCacheFallback();
            return;
        }
        Log.d("EpisodeActivity", "opening direct NTK captcha proof="
                + getHttpClient().hasNtkAccessProof()
                + " recent=" + getHttpClient().hasRecentNtkAccessVerification()
                + " titleUrl=" + (title == null ? null : title.getUrl()));
        ntkCaptchaLaunchInFlight = true;
        AppDispatchers.runUserAction(() -> {
            if(p != null && p.isNtkSite())
                getHttpClient().resolveNtkDomainNow();
            AppDispatchers.runOnMain(() -> {
                ntkCaptchaLaunchInFlight = false;
                if(!isUiAlive())
                    return;
                if(loaded || hasRenderedEpisodes()
                        || (episodeAdapter != null && episodeAdapter.getItemCount() > 0)) {
                    Log.d("EpisodeActivity", "skip stale direct NTK captcha launch: episodes already loaded");
                    return;
                }
                Intent captchaIntent = new Intent(context, CaptchaActivity.class);
                String url = title == null ? null : title.getUrl();
                if(url != null && url.startsWith("/"))
                    url = getHttpClient().getUrl(url) + url;
                else if(url != null && url.length() > 0 && !getHttpClient().isNtkUrl(url))
                    url = null;
                if(url == null || url.length() == 0)
                    url = p == null ? CustomHttpClient.NTK_WEBTOON_URL : p.getWebtoonUrl();
                Log.d("EpisodeActivity", "direct NTK captcha url=" + url);
                captchaIntent.putExtra("url", url);
                startActivityForResult(captchaIntent, RESULT_CAPTCHA);
            });
        });
    }

    private boolean showCachedEpisodesFromMemory() {
        try {
            String json = CacheFileStore.readMemory(episodeCacheKey());
            if(json == null || json.length() == 0)
                return false;
            if(shouldParseMemoryCacheOnMain(json.length()))
                return showCachedEpisodesJson(json);
            String cacheJson = json;
            AppDispatchers.submitIo(() -> {
                EpisodeCachedEpisodes cached = parseCachedEpisodesJson(cacheJson);
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
            EpisodeCachedEpisodes cached = readCachedEpisodes(appContext, cacheKey);
            PerfTrace.end("episode_cache_async_load_ms", startedAt);
            if(cached == null)
                return;
            AppDispatchers.runOnMain(() -> {
                if(isUiAlive())
                    showCachedEpisodes(cached);
            });
        });
    }

    private EpisodeCachedEpisodes readCachedEpisodes(Context cacheContext, String cacheKey) {
        try {
            String json = CacheFileStore.read(cacheContext, cacheKey);
            if(json == null || json.length() == 0)
                return null;
            EpisodeCachedEpisodes cached = parseCachedEpisodesJson(json);
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
        EpisodeCachedEpisodes cached = new Gson().fromJson(json, new TypeToken<EpisodeCachedEpisodes>(){}.getType());
        return showCachedEpisodes(cached);
    }

    private EpisodeCachedEpisodes parseCachedEpisodesJson(String json) {
        if(json == null || json.length() == 0)
            return null;
        EpisodeCachedEpisodes cached = new Gson().fromJson(json, new TypeToken<EpisodeCachedEpisodes>(){}.getType());
        return isUsableCachedEpisodes(cached) ? cached : null;
    }

    private boolean showProvidedEpisodesFromIntent() {
        if(title == null || hasRenderedEpisodes())
            return false;
        List<Manga> provided = title.getEps();
        if(provided == null || provided.size() == 0)
            return false;
        ArrayList<Manga> intentEpisodes = normalizeEpisodeSnapshot(provided, title);
        if(intentEpisodes.size() == 0)
            return false;
        episodes = intentEpisodes;
        attachLoadedEpisodesToTitle(episodes);
        showConfirmedEmptyEpisodeState(false);
        warmupInitialViewerTargets();
        episodeAdapter = new EpisodeAdapter(context, episodes, title, mode);
        afterLoad();
        ntkLoadTimeoutHandled = true;
        loaded = true;
        hideProgress();
        invalidateOptionsMenu();
        Log.d("EpisodeActivity", "episode_intent_provided_rendered count=" + episodes.size()
                + ",titleUrl=" + title.getUrl()
                + ",resumePath=" + title.getResumeNtkEpisodePath());
        return true;
    }

    private static boolean shouldParseMemoryCacheOnMain(int jsonLength) {
        return EpisodeCachePolicy.shouldParseMemoryCacheOnMain(jsonLength);
    }

    static boolean shouldParseMemoryCacheOnMainForTest(int jsonLength) {
        return shouldParseMemoryCacheOnMain(jsonLength);
    }

    private boolean showCachedEpisodes(EpisodeCachedEpisodes cached) {
        if(!isUsableCachedEpisodes(cached))
            return false;
        if(hasRenderedEpisodes())
            return false;
        episodes = normalizeEpisodeSnapshot(cached.episodes, title);
        if(episodes.size() == 0)
            return false;
        attachLoadedEpisodesToTitle(episodes);
        showConfirmedEmptyEpisodeState(false);
        warmupInitialViewerTargets();
        episodeAdapter = new EpisodeAdapter(context, episodes, title, mode);
        afterLoad();
        ntkLoadTimeoutHandled = true;
        loaded = true;
        hideProgress();
        invalidateOptionsMenu();
        return true;
    }

    private boolean isUsableCachedEpisodes(EpisodeCachedEpisodes cached) {
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
            EpisodeCompatibleCachedEpisodes compatible = findCompatibleCachedEpisodes(appContext, target, stableName);
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

    private boolean handleCaptchaWithCacheFallback() {
        if(hasRenderedEpisodes())
            return true;
        if(showCachedEpisodesFromMemory())
            return true;
        if(title == null || captchaCacheLookupInFlight)
            return false;
        captchaCacheLookupInFlight = true;
        Context appContext = getApplicationContext();
        Title target = title;
        String stableName = originalTitleName;
        String cacheKey = episodeCacheKey();
        AppDispatchers.submitIo(() -> {
            EpisodeCachedEpisodes exact = readCachedEpisodes(appContext, cacheKey);
            EpisodeCompatibleCachedEpisodes compatible = exact == null
                    ? findCompatibleCachedEpisodes(appContext, target, stableName)
                    : null;
            AppDispatchers.runOnMain(() -> {
                captchaCacheLookupInFlight = false;
                if(!isUiAlive())
                    return;
                if(showCachedEpisodes(exact))
                    return;
                if(applyCompatibleCachedEpisodes(compatible))
                    return;
                if(hasRenderedEpisodes())
                    return;
                if(p != null && p.isNtkSite())
                    openNtkCaptchaDirect();
                else
                    showCaptchaPopup(title == null ? "" : title.getUrl(), context, RESULT_CAPTCHA, p);
            });
        });
        return true;
    }

    private boolean applyCompatibleCachedEpisodes(EpisodeCompatibleCachedEpisodes compatible) {
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

    private static EpisodeCompatibleCachedEpisodes findCompatibleCachedEpisodes(Context cacheContext, Title target, String stableName) {
        return EpisodeCompatibleCacheFinder.find(cacheContext, target, stableName);
    }

    private static boolean isCompatibleCacheSource(String targetSource, String candidateSource) {
        return EpisodeCachePolicy.isCompatibleCacheSource(targetSource, candidateSource);
    }

    static boolean isCompatibleCacheSourceForTest(String targetSource, String candidateSource) {
        return EpisodeCachePolicy.isCompatibleCacheSource(targetSource, candidateSource);
    }

    static int cachedEpisodeTitleMatchScoreForTest(String titleName, List<Manga> episodes) {
        return EpisodeCachePolicy.cachedEpisodeTitleMatchScore(titleName, episodes);
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
        preWarmEpisodeAcks(loadedEpisodes);
    }

    private void preWarmEpisodeAcks(List<Manga> episodes) {
        if(episodes == null || episodes.isEmpty())
            return;
        if(!isNtkTitle())
            return;
        AppDispatchers.submitIo(() -> {
            CustomHttpClient client = getHttpClient();
            if(client == null || !client.isNtk())
                return;
            android.util.Log.d("EpisodeActivity", "ntk_prewarm_episode_list count=" + episodes.size());
            java.util.concurrent.ExecutorService pool = java.util.concurrent.Executors.newFixedThreadPool(5, r -> {
                Thread t = new Thread(r, "ntk-ack-episode-list");
                t.setDaemon(true);
                return t;
            });
            for(Manga ep : episodes) {
                if(ep == null)
                    continue;
                ep.setTitle(title);
                ep.setTitleId(title.getId());
                ep.ensureNtkEpisodePathFromIdentity();
                String epPath = ep.getNtkEpisodePath();
                if(epPath == null || epPath.length() == 0)
                    continue;
                pool.submit(() -> {
                    try {
                        Thread.sleep(30);
                        client.preStartNtkAckForPath(epPath);
                    } catch(InterruptedException e) {
                        Thread.currentThread().interrupt();
                    } catch(Exception e) {
                        android.util.Log.d("EpisodeActivity", "ntk_prewarm_episode_error path=" + epPath + "," + e);
                    }
                });
            }
            pool.shutdown();
        });
    }

    private static ArrayList<Manga> normalizeEpisodeSnapshot(List<Manga> loadedEpisodes, Title title) {
        return EpisodeCachePolicy.normalizeEpisodeSnapshot(loadedEpisodes, title);
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
            EpisodeCachedEpisodes cached = new EpisodeCachedEpisodes();
            cached.savedAt = System.currentTimeMillis();
            cached.episodes = episodeSnapshot;
            CacheFileStore.write(appContext, cacheKey, new Gson().toJson(cached));
        });
    }

    private static ArrayList<Manga> episodeCacheSnapshot(List<Manga> episodes) {
        return EpisodeCachePolicy.episodeCacheSnapshot(episodes);
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
        return EpisodeCachePolicy.sameEpisodeIdentityList(current, fresh);
    }

    public void openViewer(Manga manga, int code){
        openViewer(manga, code, false);
    }

    public void openViewer(Manga manga, int code, boolean exactEpisode){
        if(manga == null || title == null)
            return;
        manga.setMode(mode);
        manga.setTitle(title);
        manga.setTitleId(title == null ? manga.getTitleId() : title.getId());
        manga.ensureNtkEpisodePathFromIdentity();
        String path = manga.getNtkEpisodePath();
        if(isNtkTitle() && online && path != null
                && (path.startsWith("/manhwa/") || path.startsWith("/webtoon/"))) {
            armBrowserOwnedViewerLaunch(manga, code, exactEpisode);
            return;
        }
        launchViewerNow(manga, code, exactEpisode);
    }

    private void launchViewerNow(Manga manga, int code, boolean exactEpisode) {
        if(manga == null || title == null)
            return;
        if(!ml.melun.mangaview.Utils.consumeFocusedDestinationLaunch(this, DESTINATION_LAUNCH_DEBOUNCE_MS))
            return;
        pendingBrowserOwnedViewerLaunchPath = "";
        ntkLoadTimeoutHandled = true;
        cancelNtkEpisodeLoadWatchdog();
        if(exactEpisode && !isNtkTitle())
            ReaderWarmupCoordinator.primeExactImmediate(context, manga, title);
        if(!exactEpisode && getHttpClient().isNtk())
            ViewerWarmupManager.warmup(context, manga, title);
        openViewerPrepared(context, manga, code, false, online, true, title, !manga.isOnline(), exactEpisode);
    }

    private void armBrowserOwnedViewerLaunch(Manga manga, int code, boolean exactEpisode) {
        if(manga == null || title == null)
            return;
        String path = manga.getNtkEpisodePath();
        if(path == null || path.length() == 0)
            return;
        int expected = Math.max(0, manga.getNtkImageCount());
        ArrayList<String> canonicalImages = buildNtkManhwaDirectManifest(path, expected);
        if(canonicalImages != null && canonicalImages.size() > 0) {
            Log.d("EpisodeActivity", "ntk_browser_owned_canonical_direct_manifest path="
                    + path + ",count=" + canonicalImages.size());
            prepareCanonicalDirectManifestAndLaunch(manga, code, exactEpisode, path, canonicalImages);
            return;
        }
        if(path.startsWith("/webtoon/")) {
            ArrayList<String> webtoonImages = buildNtkWebtoonDirectManifest(path, expected);
            if(webtoonImages != null && webtoonImages.size() > 0) {
                pendingBrowserOwnedViewerLaunchPath = path;
                Log.d("EpisodeActivity", "ntk_webtoon_canonical_direct_manifest path="
                        + path + ",count=" + webtoonImages.size());
                prepareBrowserDirectManifestAndLaunch(
                        manga,
                        code,
                        exactEpisode,
                        path,
                        webtoonImages,
                        "episode-webtoon-canonical-direct");
                return;
            }
            if(expected <= 0 && isLikelyNtkWebtoonDirectFirstImageReachable(path)) {
                pendingBrowserOwnedViewerLaunchPath = path;
                prepareUnknownNtkWebtoonDirectManifestAndLaunch(manga, code, exactEpisode, path);
                return;
            }
        }
        if(expected <= 0 && path.startsWith("/manhwa/")) {
            prepareUnknownCanonicalDirectManifestAndLaunch(manga, code, exactEpisode, path);
            return;
        }
        final int browserExpected = rejectedCanonicalDirectPaths.contains(path) ? 0 : expected;
        if(ml.melun.mangaview.activity.NtkBrowserSessionBroker.INSTANCE.isAllDecodedReady(path, browserExpected)) {
            launchViewerNow(manga, code, exactEpisode);
            return;
        }
        if(path.equals(pendingBrowserOwnedViewerLaunchPath)) {
            Log.d("EpisodeActivity", "ntk_browser_owned_launch_already_pending path=" + path);
            return;
        }
        FrameLayout parent = findViewById(android.R.id.content);
        if(parent == null) {
            launchViewerNow(manga, code, exactEpisode);
            return;
        }
        pendingBrowserOwnedViewerLaunchPath = path;
        CustomHttpClient client = getHttpClient();
        String baseUrl = client.getUrl(path);
        ml.melun.mangaview.activity.NtkBrowserSessionBroker.INSTANCE.attach(
                this,
                parent,
                baseUrl,
                path,
                client.agent,
                java.util.Collections.emptyMap(),
                false,
                new ml.melun.mangaview.activity.NtkBrowserSessionBroker.Listener() {
                    @Override
                    public void onState(@NonNull String statePath, boolean cloudflare,
                                        @NonNull String pageTitle, @NonNull String bodySample) {
                    }

                    @Override
                    public void onFirstDrawable(@NonNull String drawablePath) {
                        if(!path.equals(drawablePath))
                            return;
                    }

                    @Override
                    public void onViewportReady(@NonNull String readyPath) {
                        if(!path.equals(readyPath))
                            return;
                        if(shouldPreferCanonicalDirectManifest(path)) {
                            Log.d("EpisodeActivity", "ntk_browser_owned_viewport_wait_canonical_direct path="
                                    + path + ",expected=" + browserExpected);
                            return;
                        }
                        if(browserExpected > 0) {
                            Log.d("EpisodeActivity", "ntk_browser_owned_viewport_wait_manifest path="
                                    + path + ",expected=" + browserExpected);
                            return;
                        }
                        runOnUiThread(() -> {
                            if(destroyed || isFinishing())
                                return;
                            if(!path.equals(pendingBrowserOwnedViewerLaunchPath))
                                return;
                            launchViewerNow(manga, code, exactEpisode);
                        });
                    }

                    @Override
                    public void onImages(@NonNull ml.melun.mangaview.activity.NtkBrowserSessionBroker.ImageSnapshot snapshot) {
                        if(!path.equals(snapshot.getPath()))
                            return;
                        List<String> images = snapshot.getImages();
                        if(images == null || images.size() == 0)
                            return;
                        boolean browserProtected = false;
                        for(String image : images) {
                            if(image != null && image.toLowerCase(java.util.Locale.ROOT).contains("/api/m/i?")) {
                                browserProtected = true;
                                break;
                            }
                        }
                        if(browserProtected) {
                            if(shouldPreferCanonicalDirectManifest(path)) {
                                Log.d("EpisodeActivity", "ntk_browser_owned_protected_manifest_wait_canonical_direct path="
                                        + path + ",count=" + images.size()
                                        + ",expected=" + browserExpected);
                                return;
                            }
                            if(images.size() < Math.max(1, browserExpected))
                                return;
                            if(browserExpected <= 0 && images.size() < 4)
                                return;
                            Log.d("EpisodeActivity", "ntk_browser_owned_protected_manifest_launch path="
                                    + path + ",count=" + images.size() + ",expected=" + browserExpected);
                            runOnUiThread(() -> {
                                if(destroyed || isFinishing())
                                    return;
                                if(!path.equals(pendingBrowserOwnedViewerLaunchPath))
                                    return;
                                launchViewerNow(manga, code, exactEpisode);
                            });
                            return;
                        }
                        if(shouldPreferCanonicalDirectManifest(path)) {
                            Log.d("EpisodeActivity", "ntk_browser_owned_direct_manifest_wait_canonical_direct path="
                                    + path + ",count=" + images.size()
                                    + ",expected=" + browserExpected);
                            return;
                        }
                        Log.d("EpisodeActivity", "ntk_browser_owned_direct_manifest_prepare path="
                                + path + ",count=" + images.size() + ",expected=" + expected);
                        prepareBrowserDirectManifestAndLaunch(
                                manga,
                                code,
                                exactEpisode,
                                path,
                                new ArrayList<>(images),
                                "episode-browser-direct-" + snapshot.getSource());
                    }

                    @Override
                    public void onScroll(@NonNull ml.melun.mangaview.activity.NtkBrowserSessionBroker.ScrollSnapshot snapshot) {
                    }

                    @Override
                    public void onCoverage(@NonNull ml.melun.mangaview.activity.NtkBrowserSessionBroker.VisibleCoverageSnapshot snapshot) {
                    }

                    @Override
                    public void onError(@NonNull String errorPath, @NonNull String message) {
                        Log.d("EpisodeActivity", "ntk_browser_owned_prepare_error path="
                                + errorPath + "," + message);
                    }

                    @Override
                    public void onNeedsUserVerification(@NonNull String verificationPath) {
                        Log.d("EpisodeActivity", "ntk_browser_owned_prepare_verification path="
                                + verificationPath);
                    }
                },
                browserExpected);
        Log.d("EpisodeActivity", "ntk_browser_owned_launch_armed path=" + path
                + ",expected=" + browserExpected + ",metadataExpected=" + expected);
    }

    private void prepareProtectedBrowserManifestAndLaunch(Manga manga, int code, boolean exactEpisode,
                                                          String path, ArrayList<String> images) {
        if(manga == null || title == null || path == null || images == null || images.size() == 0)
            return;
        final Manga launchManga = manga;
        final Title launchTitle = title;
        final ArrayList<String> launchImages = new ArrayList<>(images);
        final int width = Math.max(1, getResources().getDisplayMetrics().widthPixels);
        final AtomicBoolean launched = new AtomicBoolean(false);
        AppDispatchers.submitUserAction(() -> {
            String preparedKey = null;
            long startedAt = android.os.SystemClock.elapsedRealtime();
            try {
                preparedKey = ReaderWarmupCoordinator.prepareKnownUrlsBlocking(
                        getApplicationContext(),
                        launchManga,
                        launchTitle,
                        width,
                        true,
                        launchImages,
                        0,
                        launchImages.size(),
                        true);
            } catch(Exception e) {
                ml.melun.mangaview.report.CrashReporter.record(e);
            }
            final String finalPreparedKey = preparedKey;
            Log.d("EpisodeActivity", "ntk_browser_owned_protected_prepare_done path="
                    + path + ",count=" + launchImages.size()
                    + ",prepared=" + (finalPreparedKey != null)
                    + ",ms=" + (android.os.SystemClock.elapsedRealtime() - startedAt));
            AppDispatchers.runOnMain(() -> {
                if(destroyed || isFinishing())
                    return;
                if(!path.equals(pendingBrowserOwnedViewerLaunchPath))
                    return;
                if(finalPreparedKey == null)
                    return;
                launchViewerNow(launchManga, code, exactEpisode);
            });
        });
    }

    private void prepareBrowserDirectManifestAndLaunch(Manga manga, int code, boolean exactEpisode,
                                                       String path, ArrayList<String> images, String source) {
        if(manga == null || title == null || path == null || images == null || images.size() == 0)
            return;
        final Manga launchManga = manga;
        final Title launchTitle = title;
        final ArrayList<String> launchImages = new ArrayList<>(images);
        final int width = Math.max(1, getResources().getDisplayMetrics().widthPixels);
        final AtomicBoolean launched = new AtomicBoolean(false);
        AppDispatchers.submitUserAction(() -> {
            String preparedKey = null;
            long startedAt = android.os.SystemClock.elapsedRealtime();
            try {
                launchManga.setNtkImageCount(launchImages.size());
                acceptedCanonicalDirectPaths.add(path);
                acceptedCanonicalDirectImages.put(path, new ArrayList<>(launchImages));
                ReaderImageCache.rememberAuthoritativeNtkImageUrlsFromBrowser(
                        path,
                        launchImages,
                        source);
                int startPage = ReaderWarmupCoordinator.requestedLaunchStartPage(launchManga, exactEpisode);
                preparedKey = ReaderWarmupCoordinator.prepareKnownUrlsViewportBlocking(
                        getApplicationContext(),
                        launchManga,
                        launchTitle,
                        width,
                        true,
                        launchImages,
                        startPage,
                        canonicalDirectLaunchDecodeLimit(launchImages.size()),
                        1.0f);
            } catch(Exception e) {
                ml.melun.mangaview.report.CrashReporter.record(e);
            }
            final String finalPreparedKey = preparedKey;
            Log.d("EpisodeActivity", "ntk_browser_owned_direct_prepare_done path="
                    + path + ",count=" + launchImages.size()
                    + ",prepared=" + (finalPreparedKey != null)
                    + ",ms=" + (android.os.SystemClock.elapsedRealtime() - startedAt));
            AppDispatchers.runOnMain(() -> {
                if(destroyed || isFinishing())
                    return;
                if(!path.equals(pendingBrowserOwnedViewerLaunchPath))
                    return;
                if(finalPreparedKey == null)
                    return;
                launched.set(true);
                launchViewerNow(launchManga, code, exactEpisode);
                if(path.startsWith("/webtoon/"))
                    prefetchCanonicalDirectInitialImages(launchManga, launchImages, "browser-direct");
                else
                    prefetchCanonicalDirectImages(launchManga, launchImages, "browser-direct");
            });
        });
    }

    private boolean installImmediateNtkManhwaDirectManifest(Manga manga, String path, int expected) {
        ArrayList<String> images = buildNtkManhwaDirectManifest(path, expected);
        if(manga == null || images == null || images.size() == 0)
            return false;
        if(!acceptedCanonicalDirectPaths.contains(path)) {
            Log.d("EpisodeActivity", "ntk_canonical_direct_warmup_skip_unvalidated path="
                    + path + ",count=" + images.size());
            return false;
        }
        ArrayList<String> validated = acceptedCanonicalDirectImages.get(path);
        if(validated == null || validated.size() == 0) {
            validated = resolveNtkManhwaDirectManifest(path, images.size());
            if(validated == null || validated.size() == 0) {
                acceptedCanonicalDirectPaths.remove(path);
                rejectedCanonicalDirectPaths.add(path);
                Log.d("EpisodeActivity", "ntk_canonical_direct_warmup_reject_missing_validated path="
                        + path + ",expected=" + expected);
                return false;
            }
            acceptedCanonicalDirectImages.put(path, new ArrayList<>(validated));
        }
        images = new ArrayList<>(validated);
        manga.setNtkImageCount(images.size());
        ReaderImageCache.rememberAuthoritativeNtkImageUrlsFromBrowser(
                path,
                images,
                "episode-canonical-direct");
        primeCanonicalDirectPreparedWindow(manga, images, "episode-canonical-direct");
        Log.d("EpisodeActivity", "ntk_browser_owned_canonical_direct_manifest path="
                + path + ",count=" + images.size());
        return true;
    }

    private boolean installImmediateNtkWebtoonDirectManifest(Manga manga, String path, int expected,
                                                            boolean prepareInitialImages,
                                                            boolean prefetchInitialImages) {
        ArrayList<String> images = buildNtkWebtoonDirectManifest(path, expected);
        if(manga == null || images == null || images.size() == 0)
            return false;
        if(rejectedCanonicalDirectPaths.contains(path))
            return false;
        manga.setNtkImageCount(images.size());
        acceptedCanonicalDirectPaths.add(path);
        acceptedCanonicalDirectImages.put(path, new ArrayList<>(images));
        ReaderImageCache.rememberAuthoritativeNtkImageUrlsFromBrowser(
                path,
                images,
                "episode-webtoon-canonical-direct-warm");
        if(prepareInitialImages)
            primeCanonicalDirectPreparedWindow(manga, images, "episode-webtoon-canonical-direct-warm");
        if(prefetchInitialImages)
            prefetchCanonicalDirectInitialImages(manga, images, "episode-webtoon-canonical-direct-warm");
        Log.d("EpisodeActivity", "ntk_webtoon_canonical_direct_warmup path="
                + path + ",count=" + images.size()
                + ",prepare=" + prepareInitialImages
                + ",prefetch=" + prefetchInitialImages);
        return true;
    }

    private void prefetchLikelyNtkManhwaDirectCandidates(Manga manga, String path, int expected, String reason) {
        if(manga == null || context == null || path == null || expected <= 0)
            return;
        String[] parts = path.split("/");
        if(parts.length < 4 || !"manhwa".equalsIgnoreCase(parts[1]))
            return;
        if(!speculativeCanonicalDirectPrefetchPaths.add(path + ":" + expected))
            return;
        final Manga prefetchManga = manga;
        final Context appContext = getApplicationContext();
        final String workId = parts[2];
        final String episodeId = parts[3];
        final int count = expected;
        AppDispatchers.submitIo(() -> {
            java.util.concurrent.ExecutorService pool = java.util.concurrent.Executors.newFixedThreadPool(
                    Math.min(16, Math.max(1, count * 4)),
                    runnable -> {
                        Thread thread = new Thread(runnable, "NtkDirectSpeculativePrefetch");
                        thread.setDaemon(true);
                        thread.setPriority(Thread.NORM_PRIORITY + 1);
                        return thread;
                    });
            try {
                ArrayList<Integer> order = new ArrayList<>();
                order.add(1);
                if(count > 1)
                    order.add(count);
                int middle = Math.max(1, count / 2);
                if(!order.contains(middle))
                    order.add(middle);
                for(int page = 2; page <= count; page++) {
                    if(!order.contains(page))
                        order.add(page);
                }
                String[] hosts = new String[]{"booktoki9.org", "booktoki8.org"};
                String[] extensions = new String[]{"png", "jpg", "webp", "jpeg"};
                for(Integer pageValue : order) {
                    final int page = pageValue;
                    for(String host : hosts) {
                        for(String extension : extensions) {
                            final String candidate = String.format(java.util.Locale.ROOT,
                                    "https://%s/manhwa/%s/%s/p%03d.%s",
                                    host,
                                    workId,
                                    episodeId,
                                    page,
                                    extension);
                            pool.submit(() -> {
                                try {
                                    if(!isCanonicalDirectFirstImageReachable(candidate))
                                        return;
                                    ReaderImageCache.INSTANCE.getOrFetchFileForeground(
                                            appContext,
                                            prefetchManga,
                                            candidate,
                                            null,
                                            false);
                                } catch(Throwable ignored) {
                                }
                            });
                        }
                    }
                }
            } finally {
                pool.shutdown();
                try {
                    pool.awaitTermination(25, java.util.concurrent.TimeUnit.SECONDS);
                } catch(InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    pool.shutdownNow();
                }
                Log.d("EpisodeActivity", "ntk_canonical_direct_speculative_prefetch_done path="
                        + path + ",count=" + count + ",reason=" + reason);
            }
        });
        Log.d("EpisodeActivity", "ntk_canonical_direct_speculative_prefetch_start path="
                + path + ",count=" + expected + ",reason=" + reason);
    }

    private ArrayList<String> buildNtkManhwaDirectManifest(String path, int expected) {
        if(path == null || expected <= 0)
            return null;
        if(rejectedCanonicalDirectPaths.contains(path))
            return null;
        String[] parts = path.split("/");
        if(parts.length < 4 || !"manhwa".equalsIgnoreCase(parts[1]))
            return null;
        String workId = parts[2];
        String episodeId = parts[3];
        if(workId.length() == 0 || episodeId.length() == 0)
            return null;
        ArrayList<String> images = new ArrayList<>();
        for(int page = 1; page <= expected; page++) {
            images.add(String.format(java.util.Locale.ROOT,
                    "https://booktoki9.org/manhwa/%s/%s/p%03d.png",
                    workId,
                    episodeId,
                    page));
        }
        return images;
    }

    private ArrayList<String> buildNtkWebtoonDirectManifest(String path, int expected) {
        if(path == null || expected <= 0)
            return null;
        if(rejectedCanonicalDirectPaths.contains(path))
            return null;
        String[] parts = path.split("/");
        if(parts.length < 4 || !"webtoon".equalsIgnoreCase(parts[1]))
            return null;
        String workId = parts[2];
        String episodeId = parts[3];
        if(!workId.matches("\\d{1,12}") || !episodeId.matches("\\d{1,12}"))
            return null;
        ArrayList<String> images = new ArrayList<>();
        for(int page = 1; page <= expected; page++) {
            images.add(String.format(java.util.Locale.ROOT,
                    "https://fifa.worldcup73.xyz/black/episodes/%s/%s/p%03d.jpg",
                    workId,
                    episodeId,
                    page));
        }
        return images;
    }

    private boolean isLikelyNtkWebtoonDirectFirstImageReachable(String path) {
        return firstReachableNtkWebtoonDirectImage(path, 8) != null;
    }

    private ArrayList<String> resolveNtkWebtoonDirectManifestUnknown(String path, int maxPages) {
        if(path == null || maxPages <= 0)
            return null;
        String[] parts = path.split("/");
        if(parts.length < 4 || !"webtoon".equalsIgnoreCase(parts[1]))
            return null;
        String workId = parts[2];
        String episodeId = parts[3];
        if(!workId.matches("\\d{1,12}") || !episodeId.matches("\\d{1,12}"))
            return null;
        String first = firstReachableNtkWebtoonDirectImage(path, 8);
        if(first == null) {
            Log.d("EpisodeActivity", "ntk_webtoon_canonical_direct_unknown_miss_first path=" + path);
            return null;
        }
        long startedAt = android.os.SystemClock.elapsedRealtime();
        final int parallelism = Math.min(16, Math.max(1, maxPages));
        final int chunkSize = Math.max(16, parallelism * 3);
        java.util.concurrent.ExecutorService pool = java.util.concurrent.Executors.newFixedThreadPool(
                parallelism,
                runnable -> {
                    Thread thread = new Thread(runnable, "NtkWebtoonDirectProbe");
                    thread.setDaemon(true);
                    thread.setPriority(Thread.NORM_PRIORITY + 1);
                    return thread;
                });
        try {
            ArrayList<String> images = new ArrayList<>();
            int emptyChunksAfterHit = 0;
            for(int chunkStart = 1; chunkStart <= maxPages; chunkStart += chunkSize) {
                int chunkEnd = Math.min(maxPages, chunkStart + chunkSize - 1);
                ArrayList<java.util.concurrent.Future<String>> futures = new ArrayList<>();
                for(int page = chunkStart; page <= chunkEnd; page++) {
                    final int currentPage = page;
                    futures.add(pool.submit(() -> {
                        String candidate = String.format(java.util.Locale.ROOT,
                                "https://fifa.worldcup73.xyz/black/episodes/%s/%s/p%03d.jpg",
                                workId,
                                episodeId,
                                currentPage);
                        return isCanonicalDirectFirstImageReachable(candidate) ? candidate : null;
                    }));
                }
                int addedInChunk = 0;
                for(int i = 0; i < futures.size(); i++) {
                    String found = futures.get(i).get(1600, java.util.concurrent.TimeUnit.MILLISECONDS);
                    if(found != null) {
                        images.add(found);
                        addedInChunk++;
                    }
                }
                if(addedInChunk == 0 && images.size() > 0) {
                    emptyChunksAfterHit++;
                    if(emptyChunksAfterHit >= 1) {
                        Log.d("EpisodeActivity", "ntk_webtoon_canonical_direct_unknown_tail path="
                                + path + ",count=" + images.size()
                                + ",chunkStart=" + chunkStart
                                + ",ms=" + (android.os.SystemClock.elapsedRealtime() - startedAt));
                        return images;
                    }
                } else if(addedInChunk > 0) {
                    emptyChunksAfterHit = 0;
                }
            }
            Log.d("EpisodeActivity", "ntk_webtoon_canonical_direct_unknown_max path="
                    + path + ",count=" + images.size()
                    + ",ms=" + (android.os.SystemClock.elapsedRealtime() - startedAt));
            return images;
        } catch(Exception e) {
            Log.d("EpisodeActivity", "ntk_webtoon_canonical_direct_unknown_error path="
                    + path + "," + e);
            return null;
        } finally {
            pool.shutdownNow();
        }
    }

    private String firstReachableNtkWebtoonDirectImage(String path, int probePages) {
        if(path == null || probePages <= 0)
            return null;
        String[] parts = path.split("/");
        if(parts.length < 4 || !"webtoon".equalsIgnoreCase(parts[1]))
            return null;
        String workId = parts[2];
        String episodeId = parts[3];
        if(!workId.matches("\\d{1,12}") || !episodeId.matches("\\d{1,12}"))
            return null;
        for(int page = 1; page <= probePages; page++) {
            String candidate = String.format(java.util.Locale.ROOT,
                    "https://fifa.worldcup73.xyz/black/episodes/%s/%s/p%03d.jpg",
                    workId,
                    episodeId,
                    page);
            if(isCanonicalDirectFirstImageReachable(candidate))
                return candidate;
        }
        return null;
    }

    private ArrayList<String> resolveNtkManhwaDirectManifest(String path, int expected) {
        if(path == null || expected <= 0)
            return null;
        String[] parts = path.split("/");
        if(parts.length < 4 || !"manhwa".equalsIgnoreCase(parts[1]))
            return null;
        String workId = parts[2];
        String episodeId = parts[3];
        if(workId.length() == 0 || episodeId.length() == 0)
            return null;
        String[] hosts = new String[]{"booktoki9.org", "booktoki8.org", "mana.apihost93.com"};
        String[] extensions = new String[]{"png", "jpg", "webp", "jpeg"};
        ArrayList<String> images = new ArrayList<>(Collections.nCopies(expected, null));
        long startedAt = android.os.SystemClock.elapsedRealtime();
        java.util.concurrent.ExecutorService pool = java.util.concurrent.Executors.newFixedThreadPool(
                Math.min(16, expected),
                runnable -> {
                    Thread thread = new Thread(runnable, "NtkDirectManifestResolve");
                    thread.setDaemon(true);
                    thread.setPriority(Thread.NORM_PRIORITY + 1);
                    return thread;
                });
        try {
            ArrayList<java.util.concurrent.Future<String>> futures = new ArrayList<>();
            for(int page = 1; page <= expected; page++) {
                final int currentPage = page;
                futures.add(pool.submit(() -> {
                    for(String host : hosts) {
                        for(String extension : extensions) {
                            String candidate = String.format(java.util.Locale.ROOT,
                                    "https://%s/manhwa/%s/%s/p%03d.%s",
                                    host,
                                    workId,
                                    episodeId,
                                    currentPage,
                                    extension);
                            if(isCanonicalDirectFirstImageReachable(candidate))
                                return candidate;
                        }
                    }
                    return null;
                }));
            }
            for(int i = 0; i < futures.size(); i++) {
                String found = futures.get(i).get(4, java.util.concurrent.TimeUnit.SECONDS);
                if(found == null) {
                    if(i > 0 && images.get(0) != null) {
                        ArrayList<String> prefix = new ArrayList<>();
                        for(int j = 0; j < i; j++) {
                            String image = images.get(j);
                            if(image == null)
                                break;
                            prefix.add(image);
                        }
                        if(prefix.size() > 0) {
                            Log.d("EpisodeActivity", "ntk_canonical_direct_resolve_trim path="
                                    + path + ",expected=" + expected + ",count=" + prefix.size()
                                    + ",missingPage=" + (i + 1) + ",ms="
                                    + (android.os.SystemClock.elapsedRealtime() - startedAt)
                                    + ",first=" + prefix.get(0));
                            return prefix;
                        }
                    }
                    Log.d("EpisodeActivity", "ntk_canonical_direct_resolve_miss path="
                            + path + ",page=" + (i + 1) + ",ms="
                            + (android.os.SystemClock.elapsedRealtime() - startedAt));
                    return null;
                }
                images.set(i, found);
            }
        } catch(Exception e) {
            Log.d("EpisodeActivity", "ntk_canonical_direct_resolve_error path="
                    + path + ",ms=" + (android.os.SystemClock.elapsedRealtime() - startedAt)
                    + "," + e);
            return null;
        } finally {
            pool.shutdownNow();
        }
        Log.d("EpisodeActivity", "ntk_canonical_direct_resolved path="
                + path + ",count=" + images.size() + ",ms="
                + (android.os.SystemClock.elapsedRealtime() - startedAt)
                + ",first=" + images.get(0));
        return images;
    }

    private ArrayList<String> resolveNtkManhwaDirectManifestUnknown(String path, int maxPages) {
        if(path == null || maxPages <= 0)
            return null;
        String[] parts = path.split("/");
        if(parts.length < 4 || !"manhwa".equalsIgnoreCase(parts[1]))
            return null;
        String workId = parts[2];
        String episodeId = parts[3];
        if(workId.length() == 0 || episodeId.length() == 0)
            return null;
        String[] hosts = new String[]{"booktoki9.org", "booktoki8.org", "mana.apihost93.com"};
        String[] extensions = new String[]{"jpg", "png", "webp", "jpeg"};
        long startedAt = android.os.SystemClock.elapsedRealtime();
        String firstImage = null;
        String selectedHost = null;
        String selectedExtension = null;
        for(String host : hosts) {
            for(String extension : extensions) {
                String candidate = String.format(java.util.Locale.ROOT,
                        "https://%s/manhwa/%s/%s/p001.%s",
                        host,
                        workId,
                        episodeId,
                        extension);
                if(isCanonicalDirectFirstImageReachable(candidate)) {
                    firstImage = candidate;
                    selectedHost = host;
                    selectedExtension = extension;
                    break;
                }
            }
            if(firstImage != null)
                break;
        }
        if(firstImage == null) {
            Log.d("EpisodeActivity", "ntk_canonical_direct_unknown_resolve_miss_first path="
                    + path + ",ms=" + (android.os.SystemClock.elapsedRealtime() - startedAt));
            return null;
        }
        final int parallelism = Math.min(16, Math.max(1, maxPages - 1));
        final int chunkSize = Math.max(16, parallelism * 3);
        java.util.concurrent.ExecutorService pool = java.util.concurrent.Executors.newFixedThreadPool(
                parallelism,
                runnable -> {
                    Thread thread = new Thread(runnable, "NtkDirectManifestProbe");
                    thread.setDaemon(true);
                    thread.setPriority(Thread.NORM_PRIORITY + 1);
                    return thread;
                });
        try {
            final String manifestHost = selectedHost;
            final String manifestExtension = selectedExtension;
            ArrayList<String> images = new ArrayList<>();
            images.add(firstImage);
            for(int chunkStart = 2; chunkStart <= maxPages; chunkStart += chunkSize) {
                int chunkEnd = Math.min(maxPages, chunkStart + chunkSize - 1);
                ArrayList<java.util.concurrent.Future<String>> futures = new ArrayList<>();
                for(int page = chunkStart; page <= chunkEnd; page++) {
                    final int currentPage = page;
                    futures.add(pool.submit(() -> {
                        String candidate = String.format(java.util.Locale.ROOT,
                                "https://%s/manhwa/%s/%s/p%03d.%s",
                                manifestHost,
                                workId,
                                episodeId,
                                currentPage,
                                manifestExtension);
                        if(isCanonicalDirectFirstImageReachable(candidate))
                            return candidate;
                        return null;
                    }));
                }
                for(int i = 0; i < futures.size(); i++) {
                    String found = futures.get(i).get(1600, java.util.concurrent.TimeUnit.MILLISECONDS);
                    if(found == null) {
                        for(java.util.concurrent.Future<String> future : futures)
                            future.cancel(true);
                        Log.d("EpisodeActivity", "ntk_canonical_direct_unknown_tail path="
                                + path + ",count=" + images.size()
                                + ",missingPage=" + (chunkStart + i)
                                + ",ms=" + (android.os.SystemClock.elapsedRealtime() - startedAt));
                        return images;
                    }
                    images.add(found);
                }
            }
            if(images.size() == 0)
                return null;
            Log.d("EpisodeActivity", "ntk_canonical_direct_unknown_resolved path="
                    + path + ",count=" + images.size() + ",ms="
                    + (android.os.SystemClock.elapsedRealtime() - startedAt)
                    + ",host=" + selectedHost
                    + ",extension=" + selectedExtension
                    + ",first=" + images.get(0));
            return images;
        } catch(Exception e) {
            Log.d("EpisodeActivity", "ntk_canonical_direct_unknown_resolve_error path="
                    + path + ",ms=" + (android.os.SystemClock.elapsedRealtime() - startedAt)
                    + "," + e);
            return null;
        } finally {
            pool.shutdownNow();
        }
    }

    private void primeCanonicalDirectPreparedWindow(Manga manga, ArrayList<String> images, String reason) {
        if(manga == null || images == null || images.size() == 0 || context == null)
            return;
        int width = Math.max(1, getResources().getDisplayMetrics().widthPixels);
        int decodeLimit = canonicalDirectLaunchDecodeLimit(images.size());
        int startPage = ReaderWarmupCoordinator.requestedLaunchStartPage(manga, false);
        String key = ReaderWarmupCoordinator.primeKnownUrls(
                getApplicationContext(),
                manga,
                title,
                width,
                true,
                images,
                startPage,
                decodeLimit);
        Log.d("EpisodeActivity", "ntk_canonical_direct_prepare_prime path="
                + manga.getNtkEpisodePath()
                + ",count=" + images.size()
                + ",decodeLimit=" + decodeLimit
                + ",startPage=" + startPage
                + ",key=" + (key != null)
                + ",reason=" + reason);
    }

    private void prefetchCanonicalDirectImages(Manga manga, ArrayList<String> images, String reason) {
        if(manga == null || images == null || images.size() == 0 || context == null)
            return;
        final Context appContext = getApplicationContext();
        final Manga prefetchManga = manga;
        final ArrayList<String> prefetchImages = new ArrayList<>(images);
        AppDispatchers.submitIo(() -> {
            java.util.concurrent.ExecutorService pool = java.util.concurrent.Executors.newFixedThreadPool(
                    Math.min(8, prefetchImages.size()),
                    runnable -> {
                        Thread thread = new Thread(runnable, "NtkDirectImagePrefetch");
                        thread.setDaemon(true);
                        thread.setPriority(Thread.NORM_PRIORITY + 1);
                        return thread;
                    });
            long startedAt = android.os.SystemClock.elapsedRealtime();
            try {
                ArrayList<Integer> order = new ArrayList<>();
                order.add(0);
                int last = prefetchImages.size() - 1;
                if(last > 0)
                    order.add(last);
                int middle = Math.max(0, last / 2);
                if(!order.contains(middle))
                    order.add(middle);
                for(int i = 1; i < prefetchImages.size(); i++) {
                    if(!order.contains(i))
                        order.add(i);
                }
                ArrayList<java.util.concurrent.Future<?>> futures = new ArrayList<>();
                for(Integer indexValue : order) {
                    final int index = indexValue;
                    futures.add(pool.submit(() -> {
                        try {
                            ReaderImageCache.INSTANCE.getOrFetchFileForeground(
                                    appContext,
                                    prefetchManga,
                                    prefetchImages.get(index),
                                    null,
                                    false);
                        } catch(Throwable ignored) {
                        }
                    }));
                }
                for(java.util.concurrent.Future<?> future : futures) {
                    try {
                        future.get(6, java.util.concurrent.TimeUnit.SECONDS);
                    } catch(Exception ignored) {
                    }
                }
            } finally {
                pool.shutdownNow();
                Log.d("EpisodeActivity", "ntk_canonical_direct_prefetch_done path="
                        + prefetchManga.getNtkEpisodePath()
                        + ",count=" + prefetchImages.size()
                        + ",ms=" + (android.os.SystemClock.elapsedRealtime() - startedAt)
                        + ",reason=" + reason);
            }
        });
        Log.d("EpisodeActivity", "ntk_canonical_direct_prefetch_start path="
                + manga.getNtkEpisodePath()
                + ",count=" + images.size()
                + ",reason=" + reason);
    }

    private void prefetchCanonicalDirectInitialImages(Manga manga, ArrayList<String> images, String reason) {
        if(manga == null || images == null || images.size() == 0 || context == null)
            return;
        String path = manga.getNtkEpisodePath();
        String key = path + ":initial:" + images.size() + ":" + reason;
        if(!speculativeCanonicalDirectPrefetchPaths.add(key))
            return;
        final Context appContext = getApplicationContext();
        final Manga prefetchManga = manga;
        final ArrayList<String> prefetchImages = new ArrayList<>(
                images.subList(0, Math.min(images.size(), NTK_DIRECT_INITIAL_PREFETCH_PAGES)));
        AppDispatchers.submitIo(() -> {
            long startedAt = android.os.SystemClock.elapsedRealtime();
            java.util.concurrent.ExecutorService pool = java.util.concurrent.Executors.newFixedThreadPool(
                    Math.min(6, prefetchImages.size()),
                    runnable -> {
                        Thread thread = new Thread(runnable, "NtkDirectInitialPrefetch");
                        thread.setDaemon(true);
                        thread.setPriority(Thread.NORM_PRIORITY + 1);
                        return thread;
                    });
            try {
                ArrayList<java.util.concurrent.Future<?>> futures = new ArrayList<>();
                for(int i = 0; i < prefetchImages.size(); i++) {
                    final int index = i;
                    futures.add(pool.submit(() -> {
                        try {
                            ReaderImageCache.INSTANCE.getOrFetchFileForeground(
                                    appContext,
                                    prefetchManga,
                                    prefetchImages.get(index),
                                    null,
                                    false);
                        } catch(Throwable ignored) {
                        }
                    }));
                }
                for(java.util.concurrent.Future<?> future : futures) {
                    try {
                        future.get(4, java.util.concurrent.TimeUnit.SECONDS);
                    } catch(Exception ignored) {
                    }
                }
            } finally {
                pool.shutdownNow();
                Log.d("EpisodeActivity", "ntk_canonical_direct_initial_prefetch_done path="
                        + prefetchManga.getNtkEpisodePath()
                        + ",count=" + prefetchImages.size()
                        + ",ms=" + (android.os.SystemClock.elapsedRealtime() - startedAt)
                        + ",reason=" + reason);
            }
        });
        Log.d("EpisodeActivity", "ntk_canonical_direct_initial_prefetch_start path="
                + path
                + ",count=" + prefetchImages.size()
                + ",reason=" + reason);
    }

    private int canonicalDirectLaunchDecodeLimit(int imageCount) {
        if(imageCount <= 0)
            return 0;
        return Math.min(4, imageCount);
    }

    private boolean shouldPreferCanonicalDirectManifest(String path) {
        return path != null && path.startsWith("/manhwa/");
    }

    private void prepareCanonicalDirectManifestAndLaunch(Manga manga, int code, boolean exactEpisode,
                                                         String path, ArrayList<String> images) {
        if(manga == null || title == null || path == null || images == null || images.size() == 0)
            return;
        if(path.equals(pendingBrowserOwnedViewerLaunchPath)) {
            Log.d("EpisodeActivity", "ntk_canonical_direct_prepare_already_pending path=" + path);
            return;
        }
        pendingBrowserOwnedViewerLaunchPath = path;
        final Manga launchManga = manga;
        final Title launchTitle = title;
        final ArrayList<String> launchImages = new ArrayList<>(images);
        final int width = Math.max(1, getResources().getDisplayMetrics().widthPixels);
        final AtomicBoolean launched = new AtomicBoolean(false);
        final AtomicBoolean launchSuppressed = new AtomicBoolean(false);
        AppDispatchers.submitUserAction(() -> {
            long startedAt = android.os.SystemClock.elapsedRealtime();
            try {
                ArrayList<String> resolvedImages = null;
                boolean firstLaunchImageReachable = isCanonicalDirectFirstImageReachable(launchImages.get(0));
                if(firstLaunchImageReachable)
                    resolvedImages = resolveNtkManhwaDirectManifest(path, launchImages.size());
                if(resolvedImages != null && resolvedImages.size() > 0) {
                    launchImages.clear();
                    launchImages.addAll(resolvedImages);
                    launchManga.setNtkImageCount(launchImages.size());
                    firstLaunchImageReachable = true;
                }
                if(!firstLaunchImageReachable) {
                    ArrayList<String> recoveredImages = resolveNtkManhwaDirectManifestUnknown(path, 240);
                    if(recoveredImages != null && recoveredImages.size() > 0) {
                        launchImages.clear();
                        launchImages.addAll(recoveredImages);
                        launchManga.setNtkImageCount(launchImages.size());
                        Log.d("EpisodeActivity", "ntk_canonical_direct_recovered_unknown path="
                                + path + ",count=" + launchImages.size()
                                + ",first=" + launchImages.get(0));
                    } else {
                        rejectedCanonicalDirectPaths.add(path);
                        Log.d("EpisodeActivity", "ntk_canonical_direct_rejected path="
                                + path + ",first=" + launchImages.get(0));
                        AppDispatchers.runOnMain(() -> {
                            if(destroyed || isFinishing())
                                return;
                            if(!path.equals(pendingBrowserOwnedViewerLaunchPath))
                                return;
                            pendingBrowserOwnedViewerLaunchPath = "";
                            armBrowserOwnedViewerLaunch(launchManga, code, exactEpisode);
                        });
                        return;
                    }
                }
                acceptedCanonicalDirectPaths.add(path);
                acceptedCanonicalDirectImages.put(path, new ArrayList<>(launchImages));
                ReaderImageCache.rememberAuthoritativeNtkImageUrlsFromBrowser(
                        path,
                        launchImages,
                        "episode-canonical-direct");
                int startPage = ReaderWarmupCoordinator.requestedLaunchStartPage(launchManga, exactEpisode);
                String preparedKey = ReaderWarmupCoordinator.prepareKnownUrlsViewportBlocking(
                        getApplicationContext(),
                        launchManga,
                        launchTitle,
                        width,
                        true,
                        launchImages,
                        startPage,
                        canonicalDirectLaunchDecodeLimit(launchImages.size()),
                        1.0f);
                if(preparedKey == null) {
                    launchSuppressed.set(true);
                    Log.d("EpisodeActivity", "ntk_canonical_direct_prepare_incomplete path="
                            + path + ",count=" + launchImages.size());
                    return;
                }
                AppDispatchers.runOnMain(() -> {
                    if(destroyed || isFinishing())
                        return;
                    if(!path.equals(pendingBrowserOwnedViewerLaunchPath))
                        return;
                    launched.set(true);
                    launchViewerNow(launchManga, code, exactEpisode);
                    prefetchCanonicalDirectImages(launchManga, launchImages, "tap-validated");
                });
            } catch(Exception e) {
                launchSuppressed.set(true);
                Log.d("EpisodeActivity", "ntk_canonical_direct_prepare_error_suppressed path="
                        + path + ",count=" + launchImages.size()
                        + ",message=" + e.getClass().getSimpleName());
                ml.melun.mangaview.report.CrashReporter.record(e);
            }
            Log.d("EpisodeActivity", "ntk_canonical_direct_prepare_done path="
                    + path + ",count=" + launchImages.size()
                    + ",ms=" + (android.os.SystemClock.elapsedRealtime() - startedAt));
            AppDispatchers.runOnMain(() -> {
                if(launched.get())
                    return;
                if(launchSuppressed.get())
                    return;
                if(destroyed || isFinishing())
                    return;
                if(!path.equals(pendingBrowserOwnedViewerLaunchPath))
                    return;
                launchViewerNow(launchManga, code, exactEpisode);
            });
        });
    }

    private void prepareUnknownCanonicalDirectManifestAndLaunch(Manga manga, int code, boolean exactEpisode,
                                                                String path) {
        if(manga == null || title == null || path == null || path.length() == 0)
            return;
        if(path.equals(pendingBrowserOwnedViewerLaunchPath)) {
            Log.d("EpisodeActivity", "ntk_canonical_direct_unknown_already_pending path=" + path);
            return;
        }
        pendingBrowserOwnedViewerLaunchPath = path;
        final Manga launchManga = manga;
        final AtomicBoolean launched = new AtomicBoolean(false);
        final AtomicBoolean launchSuppressed = new AtomicBoolean(false);
        AppDispatchers.submitUserAction(() -> {
            ArrayList<String> launchImages = null;
            long startedAt = android.os.SystemClock.elapsedRealtime();
            try {
                launchImages = resolveNtkManhwaDirectManifestUnknown(path, 240);
                if(launchImages == null || launchImages.size() == 0) {
                    rejectedCanonicalDirectPaths.add(path);
                    AppDispatchers.runOnMain(() -> {
                        if(destroyed || isFinishing())
                            return;
                        if(!path.equals(pendingBrowserOwnedViewerLaunchPath))
                            return;
                        pendingBrowserOwnedViewerLaunchPath = "";
                        armBrowserOwnedViewerLaunch(launchManga, code, exactEpisode);
                    });
                    return;
                }
                launchManga.setNtkImageCount(launchImages.size());
                acceptedCanonicalDirectPaths.add(path);
                acceptedCanonicalDirectImages.put(path, new ArrayList<>(launchImages));
                ReaderImageCache.rememberAuthoritativeNtkImageUrlsFromBrowser(
                        path,
                        launchImages,
                        "episode-canonical-direct-unknown");
                int startPage = ReaderWarmupCoordinator.requestedLaunchStartPage(launchManga, exactEpisode);
                String preparedKey = ReaderWarmupCoordinator.prepareKnownUrlsViewportBlocking(
                        getApplicationContext(),
                        launchManga,
                        title,
                        Math.max(1, getResources().getDisplayMetrics().widthPixels),
                        true,
                        launchImages,
                        startPage,
                        canonicalDirectLaunchDecodeLimit(launchImages.size()),
                        1.0f);
                if(preparedKey == null) {
                    launchSuppressed.set(true);
                    Log.d("EpisodeActivity", "ntk_canonical_direct_unknown_prepare_incomplete path="
                            + path + ",count=" + launchImages.size());
                    return;
                }
                final ArrayList<String> finalLaunchImages = launchImages;
                AppDispatchers.runOnMain(() -> {
                    if(destroyed || isFinishing())
                        return;
                    if(!path.equals(pendingBrowserOwnedViewerLaunchPath))
                        return;
                    launched.set(true);
                    launchViewerNow(launchManga, code, exactEpisode);
                    prefetchCanonicalDirectImages(launchManga, finalLaunchImages, "tap-unknown-validated");
                });
            } catch(Exception e) {
                launchSuppressed.set(true);
                int errorCount = launchImages == null ? 0 : launchImages.size();
                Log.d("EpisodeActivity", "ntk_canonical_direct_unknown_prepare_error_suppressed path="
                        + path + ",count=" + errorCount
                        + ",message=" + e.getClass().getSimpleName());
                ml.melun.mangaview.report.CrashReporter.record(e);
            }
            final int finalCount = launchImages == null ? 0 : launchImages.size();
            Log.d("EpisodeActivity", "ntk_canonical_direct_unknown_prepare_done path="
                    + path + ",count=" + finalCount
                    + ",ms=" + (android.os.SystemClock.elapsedRealtime() - startedAt));
            AppDispatchers.runOnMain(() -> {
                if(launched.get())
                    return;
                if(launchSuppressed.get())
                    return;
                if(destroyed || isFinishing())
                    return;
                if(!path.equals(pendingBrowserOwnedViewerLaunchPath))
                    return;
                launchViewerNow(launchManga, code, exactEpisode);
            });
        });
    }

    private void prepareUnknownNtkWebtoonDirectManifestAndLaunch(Manga manga, int code, boolean exactEpisode,
                                                                 String path) {
        if(manga == null || title == null || path == null || path.length() == 0)
            return;
        final Manga launchManga = manga;
        final Title launchTitle = title;
        final int width = Math.max(1, getResources().getDisplayMetrics().widthPixels);
        final AtomicBoolean launched = new AtomicBoolean(false);
        final AtomicBoolean launchSuppressed = new AtomicBoolean(false);
        AppDispatchers.submitUserAction(() -> {
            ArrayList<String> launchImages = null;
            long startedAt = android.os.SystemClock.elapsedRealtime();
            try {
                launchImages = resolveNtkWebtoonDirectManifestUnknown(path, 240);
                if(launchImages == null || launchImages.size() == 0) {
                    launchSuppressed.set(true);
                    rejectedCanonicalDirectPaths.add(path);
                    return;
                }
                launchManga.setNtkImageCount(launchImages.size());
                acceptedCanonicalDirectPaths.add(path);
                acceptedCanonicalDirectImages.put(path, new ArrayList<>(launchImages));
                ReaderImageCache.rememberAuthoritativeNtkImageUrlsFromBrowser(
                        path,
                        launchImages,
                        "episode-webtoon-canonical-direct-unknown");
                int startPage = ReaderWarmupCoordinator.requestedLaunchStartPage(launchManga, exactEpisode);
                String preparedKey = ReaderWarmupCoordinator.prepareKnownUrlsViewportBlocking(
                        getApplicationContext(),
                        launchManga,
                        launchTitle,
                        width,
                        true,
                        launchImages,
                        startPage,
                        canonicalDirectLaunchDecodeLimit(launchImages.size()),
                        1.0f);
                if(preparedKey == null) {
                    launchSuppressed.set(true);
                    Log.d("EpisodeActivity", "ntk_webtoon_canonical_direct_unknown_prepare_incomplete path="
                            + path + ",count=" + launchImages.size());
                    return;
                }
                final ArrayList<String> finalLaunchImages = launchImages;
                AppDispatchers.runOnMain(() -> {
                    if(destroyed || isFinishing())
                        return;
                    if(!path.equals(pendingBrowserOwnedViewerLaunchPath))
                        return;
                    launched.set(true);
                    launchViewerNow(launchManga, code, exactEpisode);
                    prefetchCanonicalDirectImages(launchManga, finalLaunchImages, "webtoon-canonical-direct");
                });
            } catch(Exception e) {
                launchSuppressed.set(true);
                Log.d("EpisodeActivity", "ntk_webtoon_canonical_direct_unknown_prepare_error path="
                        + path + "," + e);
                ml.melun.mangaview.report.CrashReporter.record(e);
            } finally {
                int count = launchImages == null ? 0 : launchImages.size();
                Log.d("EpisodeActivity", "ntk_webtoon_canonical_direct_unknown_prepare_done path="
                        + path + ",count=" + count
                        + ",ms=" + (android.os.SystemClock.elapsedRealtime() - startedAt));
            }
            AppDispatchers.runOnMain(() -> {
                if(launched.get() || launchSuppressed.get())
                    return;
                if(destroyed || isFinishing())
                    return;
                if(!path.equals(pendingBrowserOwnedViewerLaunchPath))
                    return;
                launchViewerNow(launchManga, code, exactEpisode);
            });
        });
    }

    private boolean isCanonicalDirectFirstImageReachable(String imageUrl) {
        if(imageUrl == null || imageUrl.length() == 0)
            return false;
        if(isCanonicalDirectImageReachable(imageUrl, "HEAD"))
            return true;
        return isCanonicalDirectImageReachable(imageUrl, "GET");
    }

    private boolean isCanonicalDirectImageReachable(String imageUrl, String method) {
        HttpURLConnection connection = null;
        try {
            URL url = new URL(imageUrl);
            connection = (HttpURLConnection) url.openConnection();
            connection.setRequestMethod(method);
            connection.setRequestProperty("User-Agent", getHttpClient().agent);
            connection.setRequestProperty("Accept", "image/avif,image/webp,image/apng,image/svg+xml,image/*,*/*;q=0.8");
            if("GET".equals(method))
                connection.setRequestProperty("Range", "bytes=0-0");
            connection.setConnectTimeout(1200);
            connection.setReadTimeout(1200);
            connection.setInstanceFollowRedirects(false);
            int code = connection.getResponseCode();
            return code >= 200 && code < 300;
        } catch(Exception ignored) {
            return false;
        } finally {
            if(connection != null)
                connection.disconnect();
        }
    }

    private void saveSelectedEpisodeProgress(int adapterPosition, Manga manga) {
        if(p == null || title == null || manga == null || episodes == null || episodes.size() == 0)
            return;
        int episodeIndex = selectedEpisodeIndexForProgress(adapterPosition, manga, episodes);
        if(episodeIndex <= 0)
            return;
        Manga selected = safeGet(episodes, episodeIndex - 1);
        int episodeId = selected != null && selected.getId() > 0 ? selected.getId() : manga.getId();
        if(episodeId <= 0)
            return;
        if(isNtkTitle()) {
            String ntkPath = selected == null ? null : selected.getNtkEpisodePath();
            if(ntkPath == null || ntkPath.length() == 0)
                ntkPath = manga.getNtkEpisodePath();
            if(ntkPath != null && ntkPath.length() > 0)
                title.setResumeNtkEpisodePath(ntkPath);
        }
        title.setBookmark(episodeId);
        bookmarkId = episodeId;
        bookmarkIndex = episodeIndex;
        title.setReadingProgress(episodeId, episodeIndex, episodes.size());
        p.updateRecentData(title);
        p.setBookmark(title, episodeId);
        if(episodeAdapter != null)
            episodeAdapter.setBookmark(episodeIndex);
    }

    static int selectedEpisodeIndexForProgressForTest(int adapterPosition, Manga manga, List<Manga> episodes) {
        return selectedEpisodeIndexForProgress(adapterPosition, manga, episodes);
    }

    private static int selectedEpisodeIndexForProgress(int adapterPosition, Manga manga, List<Manga> episodes) {
        if(episodes == null || episodes.size() == 0)
            return -1;
        int fromAdapter = adapterPosition - 1;
        if(fromAdapter >= 0 && fromAdapter < episodes.size())
            return fromAdapter + 1;
        if(manga == null)
            return -1;
        String path = manga.getNtkEpisodePath();
        String number = Manga.visibleEpisodeNumberKey(manga.getName());
        for(int i = 0; i < episodes.size(); i++) {
            Manga episode = episodes.get(i);
            if(episode == null)
                continue;
            if(manga.getId() > 0 && manga.getId() == episode.getId())
                return i + 1;
            if(path != null && path.length() > 0 && path.equals(episode.getNtkEpisodePath()))
                return i + 1;
            if(number != null && number.length() > 0
                    && number.equals(Manga.visibleEpisodeNumberKey(episode.getName())))
                return i + 1;
        }
        return -1;
    }

    private void warmupUserSelectedEpisode(Manga manga) {
        if(!online || manga == null || title == null)
            return;
        if(isNtkTitle())
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

    public boolean testHasLoadedEpisodes() {
        return loaded && episodeAdapter != null && episodeAdapter.getItemCount() > 1;
    }

    public int testEpisodeAdapterPositionFor(Manga target) {
        if(target == null || episodes == null || episodes.size() == 0)
            return -1;
        String targetPath = target.getNtkEpisodePath();
        String targetNumber = Manga.visibleEpisodeNumberKey(target.getName());
        for(int i = 0; i < episodes.size(); i++) {
            Manga episode = episodes.get(i);
            if(episode == null)
                continue;
            if(target.getId() > 0 && target.getId() == episode.getId())
                return i + 1;
            String episodePath = episode.getNtkEpisodePath();
            if(targetPath != null && targetPath.length() > 0 && targetPath.equals(episodePath))
                return i + 1;
            if(targetNumber.length() > 0
                    && targetNumber.equals(Manga.visibleEpisodeNumberKey(episode.getName())))
                return i + 1;
        }
        return -1;
    }

    public void testScrollToEpisode(Manga target) {
        if(episodeList == null)
            return;
        int position = testEpisodeAdapterPositionFor(target);
        if(position > 0)
            episodeList.scrollToPosition(position);
    }

    public void testOpenViewerFromPress(Manga manga) {
        if(manga == null)
            return;
        warmSelectedNtkEpisodeForOpen(manga);
        saveSelectedEpisodeProgress(testEpisodeAdapterPositionFor(manga), manga);
        openViewer(manga, 0, true);
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
                showConfirmedEmptyEpisodeState(false);
                afterLoad();
            });
        });
    }

    private void applyEpisodeWindowChrome() {
        EpisodeWindowStyler.apply(this, dark, episodeList);
    }

}

