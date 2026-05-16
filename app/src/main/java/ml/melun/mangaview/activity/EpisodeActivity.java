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
    private static final long VIEWER_PAGE_CACHE_TTL_MS = 5 * 60 * 1000L;
    private static final int VISIBLE_EPISODE_WARMUP_AHEAD = 2;
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
    final Set<Integer> requestedVisibleWarmups = new HashSet<>();
    final Runnable visibleEpisodeWarmupRunnable = () -> {
        visibleEpisodeWarmupScheduled = false;
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
                download.putExtra("title", new Gson().toJson(title));
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
                    download.putExtra("title", new Gson().toJson(title));
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
            finish();
            startActivity(getIntent());
        }
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        dark = p.getDarkTheme();
        if(dark) setTheme(R.style.AppThemeDarkNoTitle);
        super.onCreate(savedInstanceState);
        PerformanceMonitor.attach(this);
        PerformanceMonitor.screen("episode");
        setContentView(R.layout.activity_episode);
        applyEpisodeWindowChrome();
        Intent intent = getIntent();
        title = new Gson().fromJson(intent.getStringExtra("title"),new TypeToken<Title>(){}.getType());
        switchToTitleSourceSite();
        firstContentStartedAt = PerfTrace.start("episode_first_content_ms");
        online = intent.getBooleanExtra("online", true);
        if(title.useBookmark())
            bookmarkId = restoredBookmarkId(title);
        position = intent.getIntExtra("position",0);
        favoriteResult = intent.getBooleanExtra("favorite",false);
        recentResult = intent.getBooleanExtra("recent",false);
        episodeList = this.findViewById(R.id.EpisodeList);
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
                    scheduleVisibleEpisodeWarmup(120L);
            }

            @Override
            public void onScrollStateChanged(@NonNull RecyclerView recyclerView, int newState) {
                super.onScrollStateChanged(recyclerView, newState);
                PerformanceMonitor.phase(newState == RecyclerView.SCROLL_STATE_IDLE ? "idle" : "scrolling");
                if(newState == RecyclerView.SCROLL_STATE_IDLE) {
                    PerformanceMonitor.reportNow("episode_scroll_idle");
                    scheduleVisibleEpisodeWarmup(0L);
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
            boolean renderedCachedEpisodes = showCachedEpisodes();
            episodeViewModel = new ViewModelProvider(this).get(EpisodeViewModel.class);
            episodeViewModel.state().observe(this, this::renderEpisodeState);
            if(shouldRefreshEpisodesAfterCache(renderedCachedEpisodes)) {
                episodeViewModel.loadEpisodes(title, !renderedCachedEpisodes);
                scheduleNtkEpisodeLoadWatchdog();
            }
        }else{
            OfflineStore.OfflineEpisodes offlineEpisodes = OfflineStore.loadEpisodes(context, title);
            episodes = offlineEpisodes.episodes;
            mode = offlineEpisodes.mode;
            episodeAdapter = new EpisodeAdapter(context, episodes, title, mode);
            afterLoad();
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
        warmupInitialViewerTargets();
        markFirstContent();
    }

    private void warmupInitialViewerTargets() {
        if(!online || episodes == null || episodes.size() == 0)
            return;
        PrefetchCoordinator.prefetchEpisodeList(context, title, episodes, bookmarkIndex, mode);
        warmupLikelyNtkViewerPage();
        scheduleVisibleEpisodeWarmup(80L);
    }

    private void scheduleVisibleEpisodeWarmup(long delayMs) {
        if(!online || episodeList == null || episodes == null || episodes.size() == 0)
            return;
        episodeList.removeCallbacks(visibleEpisodeWarmupRunnable);
        visibleEpisodeWarmupScheduled = true;
        episodeList.postDelayed(visibleEpisodeWarmupRunnable, Math.max(0L, delayMs));
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
        int limit = p.getDataSave() ? 3 : 5;
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

    private void warmupLikelyNtkViewerPage() {
        Manga target = quickReadEpisode();
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
                ViewerWarmupManager.prepareFirstFrameDirectOnly(appContext, target, currentTitle, 0, width,
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
        if(state instanceof UiState.Loading) {
            hideProgress();
            return;
        }
        if(state instanceof UiState.Error) {
            hideProgress();
            showCaptchaPopup(title.getUrl(), context, p);
            return;
        }
        if(!(state instanceof UiState.Content))
            return;
        EpisodeLoadResult result = ((UiState.Content<EpisodeLoadResult>) state).getValue();
        if(result.getResultCode() == LOAD_CAPTCHA){
            ntkLoadTimeoutHandled = true;
            if(p != null && p.isNtkSite())
                openNtkCaptchaDirect();
            else
                showCaptchaPopup(title.getUrl(), context, RESULT_CAPTCHA, p);
            return;
        }
        if(result.getResultCode() == LOAD_ERROR){
            if(hasRenderedEpisodes()) {
                hideProgress();
                return;
            }
            ntkLoadTimeoutHandled = true;
            hideProgress();
            showErrorPopup(context, "정보를 불러오는데 실패하였습니다.", null, false);
            return;
        }
        episodes = result.getEpisodes();
        if(episodes == null || episodes.size()==0){
            if(this.episodes == null || this.episodes.size() == 0) {
                ntkLoadTimeoutHandled = true;
                showCaptchaPopup(title.getUrl(), context, p);
            }
            return;
        }
        ntkLoadTimeoutHandled = true;
        saveEpisodeCache(episodes);
        episodeAdapter = new EpisodeAdapter(context, episodes, title, mode);
        afterLoad();
        hideProgress();
        loaded = true;
        fab_container.setVisibility(View.GONE);
        invalidateOptionsMenu();
    }

    private void scheduleNtkEpisodeLoadWatchdog() {
        if(!online || p == null || !p.isNtkSite())
            return;
        episodeList.postDelayed(() -> {
            if(isFinishing() || loaded || ntkLoadTimeoutHandled)
                return;
            if(episodeAdapter != null && episodeAdapter.getItemCount() > 0)
                return;
            ntkLoadTimeoutHandled = true;
            hideProgress();
            openNtkCaptchaDirect();
        }, 3000L);
    }

    private void openNtkCaptchaDirect() {
        if(isFinishing() || ntkCaptchaLaunchInFlight)
            return;
        ntkCaptchaLaunchInFlight = true;
        AppDispatchers.runUserAction(() -> {
            if(p != null && p.isNtkSite())
                getHttpClient().resolveNtkDomainNow();
            AppDispatchers.runOnMain(() -> {
                ntkCaptchaLaunchInFlight = false;
                if(isFinishing())
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

    private boolean showCachedEpisodes() {
        try {
            String json = CacheFileStore.read(context, episodeCacheKey());
            if(json == null || json.length() == 0)
                return false;
            CachedEpisodes cached = new Gson().fromJson(json, new TypeToken<CachedEpisodes>(){}.getType());
            if(cached == null || cached.episodes == null || cached.episodes.size() == 0)
                return false;
            if(!CachePolicy.isFresh(cached.savedAt, CachePolicy.EPISODE_TTL_MS)
                    && !CachePolicy.isReusableForColdStart(cached.savedAt))
                return false;
            episodes = cached.episodes;
            episodeAdapter = new EpisodeAdapter(context, episodes, title, mode);
            afterLoad();
            ntkLoadTimeoutHandled = true;
            loaded = true;
            hideProgress();
            return true;
        } catch (Exception e) {
            ml.melun.mangaview.report.CrashReporter.record(e);
            return false;
        }
    }

    private boolean hasRenderedEpisodes() {
        return loaded
                && episodes != null
                && episodes.size() > 0
                && episodeAdapter != null
                && episodeAdapter.getItemCount() > 0;
    }

    private void hideProgress() {
        // Loading indicators are intentionally not shown on the episode screen.
    }

    private void saveEpisodeCache(List<Manga> episodes) {
        if(title == null || episodes == null || episodes.size() == 0)
            return;
        CachedEpisodes cached = new CachedEpisodes();
        cached.savedAt = System.currentTimeMillis();
        cached.episodes = new ArrayList<>(episodes);
        CacheFileStore.write(context, episodeCacheKey(), new Gson().toJson(cached));
    }

    private String episodeCacheKey() {
        String source = title == null ? "" : title.getSourceSite();
        if((source == null || source.length() == 0) && p != null)
            source = p.isNtkSite() ? "ntk" : "wfwf";
        return "episodeSnapshotV2_" + (source == null ? "" : source) + "_" + (title == null ? 0 : title.getBaseMode()) + "_" + (title == null ? 0 : title.getId());
    }

    private void markFirstContent() {
        if(firstContentLogged)
            return;
        firstContentLogged = true;
        PerfTrace.end("episode_first_content_ms", firstContentStartedAt);
    }

    private static class CachedEpisodes {
        long savedAt;
        ArrayList<Manga> episodes;
    }

    public void openViewer(Manga manga, int code){
        openViewer(manga, code, false);
    }

    public void openViewer(Manga manga, int code, boolean exactEpisode){
        manga.setMode(mode);
        manga.setTitle(title);
        manga.setTitleId(title == null ? manga.getTitleId() : title.getId());
        if(!exactEpisode && getHttpClient().isNtk())
            ViewerWarmupManager.warmup(context, manga, title);
        openViewerPrepared(context, manga, code, false, online, true, title, !manga.isOnline(), exactEpisode);
    }

    private boolean shouldRefreshEpisodesAfterCache(boolean renderedCachedEpisodes) {
        return !renderedCachedEpisodes || p.isNtkSite() || getHttpClient().isNtk();
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
    }

    @Override
    protected void onDestroy() {
        if(episodeViewModel != null)
            episodeViewModel.cancelActiveLoad();
        super.onDestroy();
    }

    private void applyEpisodeWindowChrome() {
        if(dark)
            return;
        getWindow().setStatusBarColor(ContextCompat.getColor(this, R.color.appSurface));
        getWindow().setNavigationBarColor(ContextCompat.getColor(this, R.color.appSurface));
        getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR);
    }

}
