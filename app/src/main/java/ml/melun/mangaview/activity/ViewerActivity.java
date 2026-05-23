package ml.melun.mangaview.activity;

import android.content.Context;
import android.content.Intent;
import android.content.res.Configuration;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.text.TextUtils;
import android.util.Log;
import android.util.TypedValue;

import com.google.android.material.appbar.AppBarLayout;

import com.bumptech.glide.Priority;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;

import android.os.Bundle;

import androidx.constraintlayout.widget.ConstraintLayout;
import androidx.core.content.ContextCompat;
import androidx.core.view.ViewCompat;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import android.text.InputType;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.Menu;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.TextView;


import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;

import java.util.ArrayList;
import java.util.List;

import ml.melun.mangaview.R;
import ml.melun.mangaview.glide.ViewerPreloadPolicy;
import ml.melun.mangaview.glide.ViewerWarmupManager;
import ml.melun.mangaview.interfaces.StringCallback;
import ml.melun.mangaview.ui.StripLayoutManager;
import ml.melun.mangaview.Utils;
import ml.melun.mangaview.adapter.StripAdapter;
import ml.melun.mangaview.mangaview.Manga;
import ml.melun.mangaview.mangaview.Title;
import ml.melun.mangaview.model.PageItem;
import ml.melun.mangaview.repository.CacheFileStore;
import ml.melun.mangaview.repository.CachePolicy;
import ml.melun.mangaview.repository.EpisodeSnapshotCache;
import ml.melun.mangaview.repository.MangaRepository;
import ml.melun.mangaview.runtime.AppDispatchers;
import ml.melun.mangaview.runtime.PerfTrace;
import ml.melun.mangaview.runtime.PreparedViewerLaunch;
import ml.melun.mangaview.runtime.PerformanceMonitor;
import ml.melun.mangaview.runtime.ViewerPreparationCoordinator;

import static ml.melun.mangaview.MainApplication.p;
import static ml.melun.mangaview.MainApplication.getHttpClient;
import static ml.melun.mangaview.Utils.getScreenWidth;
import static ml.melun.mangaview.Utils.queueOfflineDownload;
import static ml.melun.mangaview.Utils.showCaptchaPopup;
import static ml.melun.mangaview.Utils.showPopup;
import static ml.melun.mangaview.activity.CaptchaActivity.RESULT_CAPTCHA;
import static ml.melun.mangaview.mangaview.Title.LOAD_CAPTCHA;
import static ml.melun.mangaview.mangaview.Title.LOAD_OK;

public class ViewerActivity extends AppCompatActivity {
    private static final String TAG = "ViewerPerf";
    private static final long EPISODE_PICKER_REFRESH_DELAY_MS = 180L;
    public static final String EXTRA_EXACT_EPISODE = "ml.melun.mangaview.EXTRA_EXACT_EPISODE";
    public static final String EXTRA_START_AT_FIRST_PAGE = "ml.melun.mangaview.EXTRA_START_AT_FIRST_PAGE";
    public static final String EXTRA_RETURN_EPISODE_SOURCE_SWITCHED = "ml.melun.mangaview.EXTRA_RETURN_EPISODE_SOURCE_SWITCHED";
    public static final String EXTRA_RETURN_EPISODE_TITLE = "ml.melun.mangaview.EXTRA_RETURN_EPISODE_TITLE";

    private enum ViewerLoadPolicy {
        RESUME,
        EXACT,
        EXACT_FIRST_PAGE
    }

    Manga manga;
    Title title;
    RecyclerView strip;
    Context context = this;
    StripAdapter stripAdapter;
    androidx.appcompat.widget.Toolbar toolbar;
    boolean toolbarshow = true;
    TextView toolbarTitle;
    AppBarLayout appbar, appbarBottom;
    StripLayoutManager manager;
    ImageButton next, prev;
    Button cut, pageBtn;
    List<Manga> eps;

    boolean autoCut = false;
    List<String> imgs;
    boolean dark;
    Intent result;
    ImageButton saveBtn;
    int width=0;
    Intent intent;
    String returnEpisodeTitleJson;
    boolean captchaChecked = false;
    ImageButton episodeButton;
    AlertDialog episodePickerDialog;
    MissingEpisodeNavigator.PromptState missingEpisodePromptState = new MissingEpisodeNavigator.PromptState();
    InfiniteScrollCallback infiniteScrollCallback;
    LoadImagesJob loader;
    PrefetchImagesJob nextPrefetcher;
    final Handler mainHandler = new Handler(Looper.getMainLooper());
    private boolean destroyed = false;
    int episodeLoaderGeneration = 0;
    int nextPrefetchEpisodeId = -1;
    int nextPrefetchBaseMode = -1;
    boolean previousEpisodeBoundaryLoading = false;
    boolean nextEpisodeBoundaryLoading = false;
    boolean previousEpisodeBoundaryJumpPending = false;
    boolean nextEpisodeBoundaryJumpPending = false;
    long lastBoundaryCheckMs = 0L;
    long suppressBoundaryLoadUntilMs = 0L;
    boolean suppressBoundaryLoadUntilUserScroll = false;
    private static final int INITIAL_PRELOAD_AHEAD_COUNT = 24;
    private static final int NEXT_EPISODE_ATTACH_THRESHOLD = 22;
    private static final int DATA_SAVE_NEXT_EPISODE_ATTACH_THRESHOLD = 12;
    private static final int NEXT_EPISODE_PREFETCH_CHAIN_DEPTH = 3;
    private static final int DATA_SAVE_NEXT_EPISODE_PREFETCH_CHAIN_DEPTH = 1;
    private static final int PREVIOUS_EPISODE_PULL_THRESHOLD_DP = 36;
    private static final long SCROLL_BOOKMARK_SAVE_DELAY_MS = 350L;
    private static final long BOUNDARY_LOAD_IDLE_DELAY_MS = 450L;
    private static final int BUSY_SCROLL_ANCHOR_MIN_ITEM_DELTA = 4;
    private static final long BUSY_SCROLL_ANCHOR_MIN_INTERVAL_MS = 180L;
    private boolean scrollBookmarkSavePending = false;
    final Runnable delayedScrollBookmarkSave = () -> {
        scrollBookmarkSavePending = false;
        saveCurrentScrollBookmark();
    };
    final Runnable delayedBoundaryLoad = this::loadEpisodeAtBoundaryIfNeeded;
    private PageItem pendingInitialResumePage;
    private int pendingInitialResumeOffset;
    private boolean initialResumeRestorePending = false;
    private boolean userScrolledAfterInitialResume = false;
    private boolean openedWithResumePagePosition = false;
    private boolean userDraggedAfterViewerPositionPrepared = false;
    private final Runnable clearInitialResumeRestore = this::clearInitialResumeRestore;
    private Manga initialToolbarGuardManga = null;
    private boolean initialToolbarGuardActive = false;
    private final Runnable clearInitialToolbarGuard = this::clearInitialToolbarGuard;
    private final Runnable syncToolbarToFocusedPage = () -> syncToolbarToFocusedPage(null);
    private int lastViewerScrollDirection = 1;
    private int lastBusyScrollAnchorPosition = RecyclerView.NO_POSITION;
    private long lastBusyScrollAnchorAtMs = 0L;
    float topPullStartY = 0;
    boolean topPullTriggered = false;
    boolean topPullEligible = false;
    boolean topPullInProgress = false;
    int pendingPreviousJumpPosition = RecyclerView.NO_POSITION;
    Manga pendingPreviousJumpManga = null;


    @Override
    protected void onSaveInstanceState(Bundle outState) {
        Utils.saveMangaState(outState, manga);
        super.onSaveInstanceState(outState);
    }
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        Utils.cancelPendingViewerLaunches(this);
        dark = p.getDarkTheme();
        super.onCreate(savedInstanceState);
        PerformanceMonitor.attach(this);
        PerformanceMonitor.screen("viewer");
        ViewerWarmupManager.suppressVisibleContinueWarmups(3000L);
        ViewerWarmupManager.suppressVisibleContinueWarmupsWhileViewerActive(true);
        setContentView(R.layout.activity_viewer);

        next = this.findViewById(R.id.toolbar_next);
        prev = this.findViewById(R.id.toolbar_previous);
        toolbar = this.findViewById(R.id.viewerToolbar);
        appbar = this.findViewById(R.id.viewerAppbar);
        toolbarTitle = this.findViewById(R.id.toolbar_title);
        appbarBottom = this.findViewById(R.id.viewerAppbarBottom);
        cut = this.findViewById(R.id.viewerBtn2);
        updateAutoCutButtonState();
        pageBtn = this.findViewById(R.id.viewerBtn1);
        pageBtn.setText("-/-");
        saveBtn = this.findViewById(R.id.viewerSaveButton);
        episodeButton = this.findViewById(R.id.toolbar_spinner);
        applyViewerChromeTheme();
        width = getScreenWidth(getWindowManager().getDefaultDisplay());

        //initial padding setup
        appbar.setPadding(0, 0,0,0);
        getWindow().getDecorView().setBackgroundColor(Color.BLACK);


        ViewCompat.setOnApplyWindowInsetsListener(getWindow().getDecorView(), (view, windowInsetsCompat) -> {
            //This is where you get DisplayCutoutCompat
            int statusBarHeight = windowInsetsCompat.getSystemWindowInsetTop();
            int ci;
            if(windowInsetsCompat.getDisplayCutout() == null) ci = 0;
            else ci = windowInsetsCompat.getDisplayCutout().getSafeInsetTop();

            appbar.setPadding(0, Math.max(ci, statusBarHeight),0,0);
            view.setPadding(windowInsetsCompat.getStableInsetLeft(),0,windowInsetsCompat.getStableInsetRight(),windowInsetsCompat.getStableInsetBottom());
            return windowInsetsCompat;
        });

        infiniteScrollCallback = new InfiniteScrollCallback() {
            @Override
            public Manga prevEp(InfiniteLoadCallback callback, Manga curm) {
                p.removeViewerBookmark(curm);
                Manga target = previousEpisodeCandidate(curm);
                if(target != null) {
                    cancelActiveEpisodeLoader();
                    cancelNextPrefetcher(target);
                    previousEpisodeBoundaryLoading = true;
                    int generation = episodeLoaderGeneration;
                    loader = new LoadImagesJob(target, m -> {
                        if(!isActiveEpisodeLoader(generation) || m == null || !isPreviousTargetStillExpected(m)) {
                            if(isActiveEpisodeLoader(generation)) {
                                previousEpisodeBoundaryLoading = false;
                                previousEpisodeBoundaryJumpPending = false;
                            }
                            return;
                        }
                        if (MangaRepository.imageUrls(m, context).size() > 0) {
                            insertMangaWhenIdle(m, ViewerActivity.this::isPreviousTargetStillExpected, () -> callback.prevLoaded(m));
                        } else {
                            callback.prevLoaded(m);
                        }
                    }, false, ViewerLoadPolicy.EXACT_FIRST_PAGE);
                    loader.start();
                    return target;
                }else{
                    callback.prevLoaded(null);
                    return null;
                }
            }

            @Override
            public Manga nextEp(InfiniteLoadCallback callback, Manga curm) {
                p.removeViewerBookmark(curm);
                Manga target = nextEpisodeCandidate(curm);
                if(target != null) {
                    Title currentTitle = title != null ? title : target.getTitle();
                    if(hasLoadedImages(target) && !needsFullEpisodeList(currentTitle, target)) {
                        appendMangaWhenIdle(target, ViewerActivity.this::isNextTargetStillExpected, () -> {
                            callback.nextLoaded(target);
                        });
                        return target;
                    }
                    cancelActiveEpisodeLoader();
                    cancelNextPrefetcher(target);
                    nextEpisodeBoundaryLoading = true;
                    int generation = episodeLoaderGeneration;
                    loader = new LoadImagesJob(target, m -> {
                        if(!isActiveEpisodeLoader(generation) || m == null || !isNextTargetStillExpected(m)) {
                            if(isActiveEpisodeLoader(generation)) {
                                nextEpisodeBoundaryLoading = false;
                                nextEpisodeBoundaryJumpPending = false;
                            }
                            return;
                        }
                        if (MangaRepository.imageUrls(m, context).size() > 0) {
                            appendMangaWhenIdle(m, ViewerActivity.this::isNextTargetStillExpected, () -> {
                                callback.nextLoaded(m);
                            });
                        } else {
                            callback.nextLoaded(m);
                        }
                    }, false, ViewerLoadPolicy.EXACT_FIRST_PAGE);
                    loader.start();
                    return target;
                }else{
                    callback.nextLoaded(null);
                    return null;
                }
            }

            @Override
            public void updateInfo(Manga m) {
                if(shouldIgnoreInitialToolbarUpdate(m))
                    return;
                manga = m;
                updateIntent(m);
                refreshToolbar(m);
            }
        };

        this.findViewById(R.id.backButton).setOnClickListener(view -> onBackPressed());
        saveBtn.setOnClickListener(view -> saveCurrentEpisodeOffline());

        try {
            intent = getIntent();
            boolean exactEpisode = intent.getBooleanExtra(EXTRA_EXACT_EPISODE, false);
            boolean startAtFirstPage = intent.getBooleanExtra(EXTRA_START_AT_FIRST_PAGE, false);
            ViewerLoadPolicy initialLoadPolicy = exactEpisode
                    ? (startAtFirstPage ? ViewerLoadPolicy.EXACT_FIRST_PAGE : ViewerLoadPolicy.EXACT)
                    : ViewerLoadPolicy.RESUME;
            title = new Gson().fromJson(intent.getStringExtra("title"), new TypeToken<Title>() {
            }.getType());
            if(savedInstanceState == null) {
                manga = new Gson().fromJson(intent.getStringExtra("manga"), new TypeToken<Manga>() {
                }.getType());
            }else{
                manga = Utils.restoreMangaState(savedInstanceState, title);
                if(manga == null)
                    manga = new Gson().fromJson(intent.getStringExtra("manga"), new TypeToken<Manga>() {
                    }.getType());
            }
            if(manga == null) {
                finish();
                return;
            }

            toolbarTitle.setText(manga.getName());
            toolbarTitle.setSelected(true);

            strip = this.findViewById(R.id.strip);
            manager = new StripLayoutManager(this);
            manager.setOrientation(LinearLayoutManager.VERTICAL);
            manager.setInitialPrefetchItemCount(viewerInitialPrefetchItemCount(p.getDataSave()));
            strip.setItemViewCacheSize(viewerItemViewCacheSize(manga, p.getDataSave()));
            strip.setHasFixedSize(true);
            strip.setLayoutManager(manager);
            strip.setItemAnimator(null);
            strip.setOverScrollMode(View.OVER_SCROLL_NEVER);
            strip.setNestedScrollingEnabled(false);

            if(intent.getBooleanExtra("recent",false)){
                Intent resultIntent = new Intent();
                setResult(RESULT_OK,resultIntent);
            }

            if(!manga.isOnline()){
                saveBtn.setVisibility(View.GONE);
            }
            
            loadManga(manga, initialLoadPolicy);
            if(initialLoadPolicy == ViewerLoadPolicy.RESUME)
                strip.post(this::hideToolbarImmediately);
            strip.setOnScrollListener(new RecyclerView.OnScrollListener() {
                @Override
                public void onScrollStateChanged(RecyclerView recyclerView, int newState) {
                    super.onScrollStateChanged(recyclerView, newState);
                    PerformanceMonitor.phase(newState == RecyclerView.SCROLL_STATE_IDLE ? "idle" : "scrolling");
                    if(stripAdapter != null) {
                        stripAdapter.setScrollState(newState);
                        dispatchScrollAnchorToAdapter(newState != RecyclerView.SCROLL_STATE_IDLE);
                    }
                    if(strip.getLayoutManager().getItemCount()>0 && newState == RecyclerView.SCROLL_STATE_DRAGGING && toolbarshow) {
                        hideToolbarImmediately();
                    }
                    if(newState == RecyclerView.SCROLL_STATE_DRAGGING) {
                        userDraggedAfterViewerPositionPrepared = true;
                        markUserScrolledAfterInitialResume();
                    }
                    if(newState == RecyclerView.SCROLL_STATE_IDLE)
                        scheduleBoundaryLoadAfterIdle();
                    else
                        cancelDelayedBoundaryLoad();
                    if(newState == RecyclerView.SCROLL_STATE_IDLE) {
                        scrollBookmarkSavePending = false;
                        mainHandler.removeCallbacks(delayedScrollBookmarkSave);
                        saveCurrentScrollBookmark();
                    }
                    if(newState == RecyclerView.SCROLL_STATE_IDLE)
                        PerformanceMonitor.reportNow("viewer_scroll_idle");
                }

                @Override
                public void onScrolled(RecyclerView recyclerView, int dx, int dy) {
                    super.onScrolled(recyclerView, dx, dy);
                    if(dy != 0)
                        lastViewerScrollDirection = dy < 0 ? -1 : 1;
                    if(dy != 0 && manager != null)
                        manager.setScrollDirection(dy);
                    if(dy != 0 && initialToolbarGuardActive)
                        clearInitialToolbarGuard();
                    int scrollState = recyclerView.getScrollState();
                    if(dy != 0 && scrollState != RecyclerView.SCROLL_STATE_IDLE)
                        suppressBoundaryLoadUntilUserScroll = false;
                    dispatchScrollAnchorToAdapter(scrollState != RecyclerView.SCROLL_STATE_IDLE);
                    if(scrollState != RecyclerView.SCROLL_STATE_IDLE) {
                        cancelDelayedBoundaryLoad();
                        return;
                    }
                    loadEpisodeAtBoundaryIfNeededThrottled();
                    scheduleScrollBookmarkSave();
                }
            });
            strip.addOnItemTouchListener(new RecyclerView.SimpleOnItemTouchListener() {
                @Override
                public boolean onInterceptTouchEvent(RecyclerView rv, MotionEvent event) {
                    handlePreviousEpisodePull(event);
                    return false;
                }

                @Override
                public void onTouchEvent(RecyclerView rv, MotionEvent event) {
                    handlePreviousEpisodePull(event);
                }
            });
        }catch(Exception e){
            ml.melun.mangaview.report.CrashReporter.record(e);
        }

        next.setOnClickListener(v -> loadAdjacentEpisode(true));
        prev.setOnClickListener(v -> loadAdjacentEpisode(false));
        episodeButton.setOnClickListener(v -> showEpisodePicker());
        cut.setOnClickListener(v -> toggleAutoCut());

        pageBtn.setOnClickListener(v -> {
            PageItem current = getFocusedVisiblePage();
            if(current == null)
                return;
            AlertDialog.Builder alert;
            if(dark) alert = new AlertDialog.Builder(context,R.style.darkDialog);
            else alert = new AlertDialog.Builder(context);

            alert.setTitle("페이지 선택\n(1~"+MangaRepository.imageUrls(current.manga, context).size()+")");
            final EditText input = new EditText(context);
            input.setInputType(InputType.TYPE_CLASS_NUMBER);
            input.setRawInputType(Configuration.KEYBOARD_12KEY);
            alert.setView(input);
            alert.setPositiveButton("이동", (dialog, button) -> {
                //이동 시
                if (input.getText().length() > 0) {
                    int page;
                    try {
                        page = Integer.parseInt(input.getText().toString());
                    } catch(NumberFormatException e) {
                        ml.melun.mangaview.report.CrashReporter.record(e);
                        return;
                    }
                    if (page < 1) page = 1;
                    if (page > MangaRepository.imageUrls(current.manga, context).size())
                        page = MangaRepository.imageUrls(current.manga, context).size();
                    manager.scrollToPage(new PageItem(page - 1, "", current.manga));
                    pageBtn.setText(page + "/" + MangaRepository.imageUrls(current.manga, context).size());
                }
            });

            alert.setNegativeButton("취소", (dialog, button) -> {
                //취소 시
            });
            alert.show();
        });

    }

    void refresh(){
        loadManga(manga);
    }

    void refreshExactEpisode(){
        if(!isUiAlive())
            return;
        loadManga(manga, ViewerLoadPolicy.EXACT);
    }

    private void showEpisodePicker() {
        long pickerStartedAt = PerfTrace.start("viewer_episode_picker_open_ms");
        Manga current = focusedManga();
        List<Manga> data = episodeListFor(current);
        if(data == null || data.size() == 0) {
            PerfTrace.end("viewer_episode_picker_open_ms", pickerStartedAt);
            return;
        }
        int selected = findEpisodeIndex(data, current);
        RecyclerView episodeList = new RecyclerView(context);
        int maxHeight = Math.min(dp(520), getResources().getDisplayMetrics().heightPixels - dp(160));
        episodeList.setLayoutParams(new ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, maxHeight));
        episodeList.setHasFixedSize(true);
        episodeList.setItemViewCacheSize(24);
        episodeList.setOverScrollMode(View.OVER_SCROLL_NEVER);
        episodeList.setClipToPadding(false);
        episodeList.setPadding(0, dp(4), 0, dp(4));
        LinearLayoutManager pickerManager = new LinearLayoutManager(context);
        episodeList.setLayoutManager(pickerManager);
        episodeList.setItemAnimator(null);

        EpisodePickerAdapter adapter = new EpisodePickerAdapter(data, selected, selectedManga -> {
            if(episodePickerDialog != null)
                episodePickerDialog.dismiss();
            lockUi(true);
            loadManga(selectedManga, ViewerLoadPolicy.EXACT_FIRST_PAGE);
        });
        episodeList.setAdapter(adapter);

        AlertDialog.Builder builder = dark
                ? new AlertDialog.Builder(context, R.style.darkDialog)
                : new AlertDialog.Builder(context);
        episodePickerDialog = builder
                .setTitle("회차 선택")
                .setView(episodeList)
                .create();
        episodePickerDialog.setOnShowListener(dialog -> centerEpisodePicker(episodeList, pickerManager, selected));
        episodePickerDialog.setOnDismissListener(dialog -> {
            if(episodePickerDialog == dialog)
                episodePickerDialog = null;
        });
        episodePickerDialog.show();
        PerfTrace.end("viewer_episode_picker_open_ms", pickerStartedAt);
        episodeList.postDelayed(() -> loadFullEpisodeListForPicker(adapter, episodeList, pickerManager),
                EPISODE_PICKER_REFRESH_DELAY_MS);
    }

    private List<Manga> currentEpisodeList() {
        return episodeListFor(focusedManga());
    }

    private List<Manga> episodeListFor(Manga current) {
        List<Manga> data = null;
        if(current != null)
            data = largerEpisodeList(data, current.getEps());
        data = largerEpisodeList(data, eps);
        Title currentTitle = title != null ? title : (current == null ? null : current.getTitle());
        if(currentTitle != null)
            currentTitle.ensureProgressEpisodes(current);
        if(currentTitle != null)
            data = largerEpisodeList(data, currentTitle.getEps());
        return data;
    }

    private Manga nextEpisodeCandidate(Manga current) {
        if(current == null)
            return null;
        Manga candidate;
        List<Manga> data = episodeListFor(current);
        int index = findEpisodeIndex(data, current);
        for(int i = index - 1; i >= 0; i--) {
            candidate = prepareEpisodeCandidate(Utils.safeGet(data, i), current);
            if(candidate != null && !sameManga(candidate, current))
                return candidate;
        }
        candidate = current.nextEp();
        if(candidate != null) {
            candidate = prepareEpisodeCandidate(candidate, current);
            if(!sameManga(candidate, current))
                return candidate;
        }
        return null;
    }

    private Manga previousEpisodeCandidate(Manga current) {
        if(current == null)
            return null;
        Manga candidate;
        List<Manga> data = episodeListFor(current);
        int index = findEpisodeIndex(data, current);
        if(data != null && index >= 0)
            for(int i = index + 1; i < data.size(); i++) {
                candidate = prepareEpisodeCandidate(Utils.safeGet(data, i), current);
                if(candidate != null && !sameManga(candidate, current))
                    return candidate;
            }
        candidate = current.prevEp();
        if(candidate != null) {
            candidate = prepareEpisodeCandidate(candidate, current);
            if(!sameManga(candidate, current))
                return candidate;
        }
        return null;
    }

    private Manga prepareEpisodeCandidate(Manga candidate, Manga source) {
        if(candidate == null)
            return null;
        Title currentTitle = title != null ? title : (source == null ? null : source.getTitle());
        if(currentTitle != null) {
            candidate.setTitle(currentTitle);
            candidate.setTitleId(currentTitle.getId());
            List<Manga> episodes = episodeListFor(source);
            if(episodes != null && episodes.size() > 0)
                candidate.setEps(episodes);
        }
        return candidate;
    }

    private void loadAdjacentEpisode(boolean nextDirection) {
        Manga source = focusedManga();
        Manga target = nextDirection ? nextEpisodeCandidate(source) : previousEpisodeCandidate(source);
        logViewerEpisode("viewer_adjacent_source", source);
        logViewerEpisode("viewer_adjacent_target", target);
        if(target != null) {
            if(nextDirection && maybePromptMissingNextEpisode(source, target,
                    () -> loadManga(target, ViewerLoadPolicy.EXACT_FIRST_PAGE)))
                return;
            loadManga(target, ViewerLoadPolicy.EXACT_FIRST_PAGE);
            return;
        }
        if(source == null || !source.isOnline())
            return;
        lockUi(true);
        AppDispatchers.submitUserAction(() -> {
            int result = LOAD_OK;
            try {
                result = ensureEpisodeListLoaded(source);
            } catch (Exception e) {
                ml.melun.mangaview.report.CrashReporter.record(e);
            }
            Manga resolved = result == LOAD_OK
                    ? (nextDirection ? nextEpisodeCandidate(source) : previousEpisodeCandidate(source))
                    : null;
            int finalResult = result;
            mainHandler.post(() -> {
                lockUi(false);
                if(isFinishing())
                    return;
                if(finalResult == LOAD_CAPTCHA) {
                    showViewerCaptchaRequired(source);
                    return;
                }
                if(resolved != null) {
                    if(nextDirection && maybePromptMissingNextEpisode(source, resolved,
                            () -> loadManga(resolved, ViewerLoadPolicy.EXACT_FIRST_PAGE)))
                        return;
                    loadManga(resolved, ViewerLoadPolicy.EXACT_FIRST_PAGE);
                } else
                    refreshToolbar(source);
            });
        });
    }

    private boolean maybePromptMissingNextEpisode(Manga source, Manga target, Runnable skipAction) {
        return MissingEpisodeNavigator.maybePromptNextEpisode(this, dark, source, target, missingEpisodePromptState,
                missingEpisodeHost(), skipAction);
    }

    private static boolean shouldPromptMissingEpisodeAtBoundary(boolean jumpToEpisode, boolean missingGap) {
        return jumpToEpisode && missingGap;
    }

    static boolean shouldPromptMissingEpisodeAtBoundaryForTest(boolean jumpToEpisode, boolean missingGap) {
        return shouldPromptMissingEpisodeAtBoundary(jumpToEpisode, missingGap);
    }

    private MissingEpisodeNavigator.Host missingEpisodeHost() {
        return new MissingEpisodeNavigator.Host() {
            @Override
            public void lockUi(boolean lock) {
                ViewerActivity.this.lockUi(lock);
            }

            @Override
            public void openAlternateEpisode(Title alternateTitle, Manga episode) {
                title = alternateTitle;
                if(episode != null && alternateTitle != null) {
                    episode.setTitle(alternateTitle);
                    episode.setTitleId(alternateTitle.getId());
                }
                markReturnEpisodeListTitle(alternateTitle);
                loadManga(episode, ViewerLoadPolicy.EXACT_FIRST_PAGE);
            }

            @Override
            public void showCaptcha(Manga episode) {
                showCaptchaPopup(episode == null ? null : Manga.safeUrl(episode), ViewerActivity.this, RESULT_CAPTCHA, p);
            }

            @Override
            public void onPromptCancelled() {
                suppressBoundaryLoadsUntilNextScroll();
            }
        };
    }

    private Manga focusedManga() {
        PageItem page = getFocusedVisiblePage();
        return page != null && page.manga != null ? page.manga : manga;
    }

    private List<Manga> largerEpisodeList(List<Manga> current, List<Manga> candidate) {
        if(candidate == null || candidate.size() == 0)
            return current;
        if(current == null || candidate.size() > current.size())
            return candidate;
        return current;
    }

    private void loadFullEpisodeListForPicker(EpisodePickerAdapter adapter, RecyclerView list, LinearLayoutManager layoutManager) {
        Title currentTitle = title != null ? title : (manga == null ? null : manga.getTitle());
        if(currentTitle == null || manga == null || !manga.isOnline())
            return;
        List<Manga> current = currentEpisodeList();
        List<Manga> existingEpisodes = currentTitle.getEps();
        int existingSize = existingEpisodes == null ? 0 : existingEpisodes.size();
        if(existingSize >= (current == null ? 0 : current.size()) && existingSize > 3)
            return;
        AppDispatchers.submitIo(() -> {
            try {
                int result = MangaRepository.fetchEpisodesForeground(currentTitle);
                if(result != LOAD_OK || isFinishing())
                    return;
                List<Manga> loaded = Utils.snapshotEpisodes(currentTitle);
                if(loaded == null || loaded.size() == 0)
                    return;
                for(Manga episode : loaded) {
                    if(episode != null) {
                        episode.setTitle(currentTitle);
                        episode.setTitleId(currentTitle.getId());
                    }
                }
                manga.setTitle(currentTitle);
                manga.setTitleId(currentTitle.getId());
                manga.setEps(loaded);
                title = currentTitle;
                mainHandler.post(() -> {
                    if(isFinishing() || episodePickerDialog == null || adapter == null)
                        return;
                    int selected = findEpisodeIndex(loaded, manga);
                    adapter.replaceData(loaded, selected);
                    centerEpisodePicker(list, layoutManager, selected);
                    refreshToolbar(manga);
                });
            } catch (Exception e) {
                if(!isFinishing())
                    ml.melun.mangaview.report.CrashReporter.record(e);
            }
        });
    }

    private int findEpisodeIndex(List<Manga> data, Manga current) {
        if(data == null || current == null)
            return RecyclerView.NO_POSITION;
        for(int i = 0; i < data.size(); i++) {
            Manga candidate = data.get(i);
            if(candidate != null && sameManga(candidate, current))
                return i;
        }
        return RecyclerView.NO_POSITION;
    }

    private void centerEpisodePicker(RecyclerView list, LinearLayoutManager layoutManager, int selected) {
        if(selected == RecyclerView.NO_POSITION || list == null || layoutManager == null)
            return;
        list.post(() -> {
            if(list.getHeight() <= 0) {
                list.postDelayed(() -> centerEpisodePicker(list, layoutManager, selected), 32);
                return;
            }
            int offset = Math.max(0, (list.getHeight() - dp(52)) / 2);
            layoutManager.scrollToPositionWithOffset(selected, offset);
        });
    }

    private int dp(float value) {
        return (int) (value * getResources().getDisplayMetrics().density + 0.5f);
    }

    private class EpisodePickerAdapter extends RecyclerView.Adapter<EpisodePickerAdapter.EpisodeHolder> {
        private List<Manga> data;
        private int selected;
        private final EpisodeClickListener listener;

        EpisodePickerAdapter(List<Manga> data, int selected, EpisodeClickListener listener) {
            this.data = data;
            this.selected = selected;
            this.listener = listener;
            setHasStableIds(true);
        }

        void replaceData(List<Manga> data, int selected) {
            int oldSize = this.data == null ? 0 : this.data.size();
            this.data = data;
            this.selected = selected;
            int newSize = getItemCount();
            if(oldSize == newSize) {
                if(newSize > 0)
                    notifyItemRangeChanged(0, newSize);
            } else {
                if(oldSize > 0)
                    notifyItemRangeRemoved(0, oldSize);
                if(newSize > 0)
                    notifyItemRangeInserted(0, newSize);
            }
        }

        @Override
        @NonNull
        public EpisodeHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            TextView text = new TextView(parent.getContext());
            text.setLayoutParams(new RecyclerView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(52)));
            text.setGravity(Gravity.CENTER_VERTICAL);
            text.setPadding(dp(16), 0, dp(16), 0);
            text.setSingleLine(true);
            text.setEllipsize(TextUtils.TruncateAt.END);
            text.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
            return new EpisodeHolder(text);
        }

        @Override
        public void onBindViewHolder(@NonNull EpisodeHolder holder, int position) {
            if(!isValidEpisodePickerPosition(data, position)) {
                holder.text.setText("");
                holder.text.setSelected(false);
                holder.text.setOnClickListener(null);
                return;
            }
            Manga item = data.get(position);
            boolean isSelected = position == selected;
            holder.text.setText(item == null ? "" : item.getName());
            holder.text.setTextColor(isSelected
                    ? ContextCompat.getColor(context, R.color.appAccent)
                    : dark ? Color.WHITE : ContextCompat.getColor(context, R.color.appText));
            holder.text.setBackgroundColor(isSelected
                    ? dark ? Color.rgb(40, 48, 64) : ContextCompat.getColor(context, R.color.appAccentLight)
                    : Color.TRANSPARENT);
            holder.text.setSelected(true);
            holder.text.setOnClickListener(v -> {
                if(listener != null && item != null)
                    listener.onClick(item);
            });
        }

        @Override
        public int getItemCount() {
            return data == null ? 0 : data.size();
        }

        @Override
        public long getItemId(int position) {
            Manga item = data == null || position < 0 || position >= data.size() ? null : data.get(position);
            if(item == null)
                return RecyclerView.NO_ID;
            return fastEpisodeStableId(item, position);
        }

        class EpisodeHolder extends RecyclerView.ViewHolder {
            TextView text;

            EpisodeHolder(@NonNull View itemView) {
                super(itemView);
                text = (TextView) itemView;
            }
        }
    }

    private interface EpisodeClickListener {
        void onClick(Manga manga);
    }

    static long fastEpisodeStableIdForTest(Manga item, int position) {
        return fastEpisodeStableId(item, position);
    }

    private static long fastEpisodeStableId(Manga item, int position) {
        if(item == null)
            return RecyclerView.NO_ID;
        long titleId = item.getTitleId() > 0 ? item.getTitleId() : 0;
        long id = item.getId();
        if(id >= 0) {
            long stable = (((long) item.getBaseMode() & 0xffffL) << 48)
                    ^ ((titleId & 0xffffL) << 32)
                    ^ (id & 0xffffffffL);
            return stable == RecyclerView.NO_ID ? Long.MIN_VALUE : stable;
        }
        String fallback = (item.getNtkEpisodePath() == null ? "" : item.getNtkEpisodePath())
                + ":" + (item.getName() == null ? "" : item.getName()) + ":" + position;
        long hash = 1125899906842597L;
        for(int i = 0; i < fallback.length(); i++)
            hash = 31L * hash + fallback.charAt(i);
        return hash == RecyclerView.NO_ID ? Long.MIN_VALUE : hash;
    }

    private static boolean isValidEpisodePickerPosition(List<?> data, int position) {
        return data != null && position >= 0 && position < data.size();
    }

    static boolean isValidEpisodePickerPositionForTest(List<?> data, int position) {
        return isValidEpisodePickerPosition(data, position);
    }

    void loadManga(Manga m, LoadMangaCallback callback){
        loadManga(m, callback, ViewerLoadPolicy.RESUME);
    }

    void loadManga(Manga m, LoadMangaCallback callback, ViewerLoadPolicy policy){
        if(m == null)
            return;
        if(policy == null)
            policy = ViewerLoadPolicy.RESUME;
        if(title != null)
            m.setTitle(title);
        logViewerEpisode("viewer_load_manga_request", m);
        this.manga = m;
        if(loader != null)
            loader.cancel();
        loader = new LoadImagesJob(m, callback, true, policy);
        loader.start();
    }

    void loadManga(Manga m){
        loadManga(m, ViewerLoadPolicy.RESUME);
    }

    void loadManga(Manga m, ViewerLoadPolicy policy){
        if(m == null){
            showPopup(context, "오류", "만화를 불러 오던중 오류가 발생했습니다.", (dialog, which) -> ViewerActivity.this.finish(), dialog -> ViewerActivity.this.finish());
            return;
        }
        if(policy == null)
            policy = ViewerLoadPolicy.RESUME;
        cancelActiveEpisodeLoader();
        promoteOrCancelNextPrefetcher(m);
        if(m.isOnline()) {
            ViewerLoadPolicy finalPolicy = policy;
            loadManga(m, m1 -> {
                manga = m1;
                setManga(m1, finalPolicy);
            }, policy);
        }else{
            //offline
            eps = Utils.snapshotEpisodes(title);
            if(eps.size() == 0 || !eps.contains(m)) {
                showPopup(context, "오류", "저장된 회차 정보를 불러오지 못했습니다.", (dialog, which) -> ViewerActivity.this.finish(), dialog -> ViewerActivity.this.finish());
                return;
            }
//            for(int i=0; i<eps.size(); i++){
//                eps.get(i).setNextEp(i>0 ? eps.get(i-1) : null);
//                eps.get(i).setPrevEp(i<eps.size()-1 ? eps.get(i+1) : null);
//            }
            m = eps.get(eps.indexOf(m));
            setManga(m, policy);
        }
    }


    public void setManga(Manga m){
        setManga(m, ViewerLoadPolicy.RESUME);
    }

    public void setManga(Manga m, ViewerLoadPolicy policy){
        try {
            if(policy == null)
                policy = ViewerLoadPolicy.RESUME;
            logViewerEpisode("viewer_set_manga", m);
            manga = m;
            lockUi(false);
            if(MangaRepository.imageUrls(m, context) == null || MangaRepository.imageUrls(m, context).size()==0) {
                showViewerImagesUnavailable(m);
                return;
            }
            releaseStripAdapter();
            beginInitialToolbarGuard(m);
            stripAdapter = new StripAdapter(context, m, autoCut, width,title, infiniteScrollCallback);

            refreshAdapter();
            prepareInitialViewerPosition(m, policy);
            bookmarkRefresh(m, policy);
            scheduleInitialViewerPreload(m, policy);
            scheduleFocusedPagePreload();
            refreshToolbar(m);
            updateIntent(m);
            scheduleNextEpisodePrefetch(m);

        }catch (Exception e){
            Utils.showCaptchaPopup(Manga.safeUrl(m), context, e, p);
            ml.melun.mangaview.report.CrashReporter.record(e);
        }
    }


    @Override
    public boolean dispatchKeyEvent(KeyEvent event) {
        int keyCode = event.getKeyCode();
        if(keyCode == p.getPrevPageKey() || keyCode == p.getNextPageKey()) {
            if(manager == null || manager.getItemCount() <= 0)
                return true;
            int index = manager.findFirstVisibleItemPosition();
            if(index == RecyclerView.NO_POSITION)
                return true;
            if (keyCode == p.getNextPageKey()) {
                if (event.getAction() == KeyEvent.ACTION_UP) {
                    manager.scrollToPosition(Math.min(index + 1, manager.getItemCount() - 1));
                }
            } else if (keyCode == p.getPrevPageKey()) {
                if (event.getAction() == KeyEvent.ACTION_UP) {
                    manager.scrollToPosition(Math.max(index - 1, 0));
                }
            }
            if(toolbarshow) toggleToolbar();
            return true;
        }
        return super.dispatchKeyEvent(event);
    }
    @Override
    protected void onResume() {
        super.onResume();
        ViewerWarmupManager.suppressVisibleContinueWarmupsWhileViewerActive(true);
        PerformanceMonitor.resume();
        if(toolbarshow) getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_VISIBLE);
        else getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_FULLSCREEN);
    }

    @Override
    protected void onPause() {
        mainHandler.removeCallbacks(delayedScrollBookmarkSave);
        saveCurrentScrollBookmark();
        PerformanceMonitor.pause();
        ViewerWarmupManager.suppressVisibleContinueWarmupsWhileViewerActive(false);
        super.onPause();
    }

    @Override
    protected void onStop() {
        mainHandler.removeCallbacks(delayedScrollBookmarkSave);
        saveCurrentScrollBookmark();
        if(isFinishing())
            releaseStripAdapter();
        super.onStop();
    }

    public void toggleToolbar(){
        //attrs = getWindow().getAttributes();
        if(toolbarshow){
            appbar.animate().translationY(-appbar.getHeight());
            appbarBottom.animate().translationY(+appbarBottom.getHeight());
            toolbarshow=false;
            getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_FULLSCREEN);
        }
        else {
            PageItem item = getFocusedVisiblePage();
            if(item != null) {
                pageBtn.setText(item.index+1 + "/" + MangaRepository.imageUrls(item.manga, context).size());
                toolbarTitle.setText(item.manga.getName());
                toolbarTitle.setSelected(true);
                appbar.animate().translationY(0);
                appbarBottom.animate().translationY(0);
                toolbarshow = true;
                getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_VISIBLE);
            }

        }
        //getWindow().setAttributes(attrs);
    }

    public void toggleAutoCut(){
        PageItem page = autoCutToggleAnchor();
        if(page == null || page.manga == null || stripAdapter == null)
            return;
        int offset = autoCutToggleOffset(page, visibleOffset(page));
        autoCut = !autoCut;
        updateAutoCutButtonState();
        suppressBoundaryLoadUntilMs = android.os.SystemClock.uptimeMillis() + 1200L;
        suppressBoundaryLoadUntilUserScroll = true;
        releaseStripAdapter();
        stripAdapter = new StripAdapter(context, page.manga, autoCut, width,title, infiniteScrollCallback);
        refreshAdapter();
        PageItem scrollPage = new PageItem(page.index, "", page.manga);
        manager.scrollToPageWithOffset(scrollPage, offset);
        manga = page.manga;
        updateIntent(manga);
        strip.post(() -> refreshToolbar(manga));
    }

    private PageItem autoCutToggleAnchor() {
        PageItem focused = getFocusedVisiblePage();
        PageItem displayed = displayedToolbarPage(focused);
        return displayed != null ? displayed : focused;
    }

    private PageItem displayedToolbarPage(PageItem fallback) {
        Manga displayedManga = displayedToolbarManga(fallback);
        if(displayedManga == null || pageBtn == null)
            return null;
        int index = displayedPageIndex(pageBtn.getText());
        if(index < 0)
            return null;
        List<String> images = MangaRepository.imageUrls(displayedManga, context);
        if(images == null || images.size() == 0)
            return null;
        if(index >= images.size())
            index = images.size() - 1;
        int side = fallback != null && fallback.index == index && sameManga(fallback.manga, displayedManga)
                ? fallback.side
                : PageItem.FIRST;
        return new PageItem(index, "", displayedManga, side);
    }

    private Manga displayedToolbarManga(PageItem fallback) {
        CharSequence text = toolbarTitle == null ? null : toolbarTitle.getText();
        String displayedName = text == null ? "" : text.toString();
        if(fallback != null && fallback.manga != null && displayedName.equals(fallback.manga.getName()))
            return fallback.manga;
        if(manga != null && displayedName.equals(manga.getName()))
            return manga;
        Manga matched = findEpisodeByName(displayedName, eps);
        if(matched != null)
            return matched;
        if(title != null) {
            matched = findEpisodeByName(displayedName, Utils.snapshotEpisodes(title));
            if(matched != null)
                return matched;
        }
        if(fallback != null && fallback.manga != null) {
            matched = findEpisodeByName(displayedName, Utils.snapshotEpisodes(fallback.manga));
            if(matched != null)
                return matched;
        }
        return manga;
    }

    private Manga findEpisodeByName(String name, List<Manga> episodes) {
        if(name == null || name.length() == 0 || episodes == null)
            return null;
        for(Manga episode : episodes)
            if(episode != null && name.equals(episode.getName()))
                return episode;
        return null;
    }

    private static int displayedPageIndex(CharSequence text) {
        if(text == null)
            return -1;
        String value = text.toString().trim();
        int slash = value.indexOf('/');
        if(slash <= 0)
            return -1;
        try {
            int page = Integer.parseInt(value.substring(0, slash).trim());
            return page <= 0 ? -1 : page - 1;
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    static int displayedPageIndexForTest(CharSequence text) {
        return displayedPageIndex(text);
    }

    private int visibleOffset(PageItem page) {
        if(page == null || manager == null || stripAdapter == null)
            return 0;
        int position = stripAdapter.findPagePosition(page);
        if(position == RecyclerView.NO_POSITION)
            return 0;
        View view = manager.findViewByPosition(position);
        return view == null ? 0 : view.getTop();
    }

    private int autoCutToggleOffset(PageItem page, int offset) {
        if(page == null || page.manga == null)
            return 0;
        List<String> images = MangaRepository.imageUrls(page.manga, context);
        if(images == null || images.size() == 0)
            return 0;
        if(page.index >= images.size() - 1)
            return 0;
        return offset;
    }

    private void updateAutoCutButtonState() {
        if(cut == null)
            return;
        cut.setSelected(autoCut);
        cut.setText(autoCut ? "분할 켜짐" : "분할 꺼짐");
        cut.setContentDescription(autoCut ? "자동분할 켜짐" : "자동분할 꺼짐");
        cut.setBackgroundTintList(null);
        if(dark) {
            int fill = autoCut ? R.color.appAccent : R.color.colorDarkSurfaceElevated;
            int text = autoCut ? android.R.color.white : R.color.colorDarkText;
            ViewCompat.setBackground(cut, roundedBackground(fill, R.color.colorDarkDivider, 8));
            cut.setTextColor(ContextCompat.getColor(this, text));
            return;
        }
        ViewCompat.setBackground(cut, ContextCompat.getDrawable(this,
                autoCut ? R.drawable.viewer_autocut_on_bg : R.drawable.viewer_autocut_off_bg));
        cut.setTextColor(ContextCompat.getColor(this, autoCut ? android.R.color.white : R.color.appText));
    }

    private void applyViewerChromeTheme() {
        int surface = ContextCompat.getColor(this, dark ? R.color.colorDarkSurface : R.color.appCard);
        int text = ContextCompat.getColor(this, dark ? R.color.colorDarkText : R.color.appText);
        int chip = dark ? R.color.colorDarkSurfaceElevated : R.color.appCard;
        if(toolbar != null)
            toolbar.setBackgroundColor(surface);
        if(appbar != null)
            appbar.setBackgroundColor(surface);
        if(appbarBottom != null)
            appbarBottom.setBackgroundColor(surface);
        View topContent = findViewById(R.id.viewerToolbarContent);
        View bottomContent = findViewById(R.id.viewerToolbarBottomContent);
        if(topContent != null)
            topContent.setBackgroundColor(surface);
        if(bottomContent != null)
            bottomContent.setBackgroundColor(surface);
        getWindow().setStatusBarColor(surface);
        getWindow().setNavigationBarColor(Color.BLACK);
        if(toolbarTitle != null)
            toolbarTitle.setTextColor(text);
        if(pageBtn != null) {
            pageBtn.setBackground(roundedBackground(dark ? R.color.colorDarkSurfaceElevated : R.color.appCard,
                    dark ? R.color.colorDarkDivider : R.color.appDivider, 8));
            pageBtn.setTextColor(text);
        }
        if(cut != null)
            updateAutoCutButtonState();
        styleViewerIconButton(findViewById(R.id.backButton), chip, dark ? R.color.colorDarkText : R.color.appText);
        styleViewerIconButton(saveBtn, chip, R.color.appAccent);
        styleViewerIconButton(prev, R.color.appAccent, dark ? R.color.colorDarkText : R.color.appCard);
        styleViewerIconButton(next, R.color.appAccent, dark ? R.color.colorDarkText : R.color.appCard);
        styleViewerIconButton(episodeButton, R.color.appAccent, dark ? R.color.colorDarkText : R.color.appCard);
    }

    private void styleViewerIconButton(ImageButton button, int fillColorRes, int iconColorRes) {
        if(button == null)
            return;
        button.setBackground(roundedBackground(fillColorRes, dark ? R.color.colorDarkDivider : R.color.appCard, 8));
        button.setColorFilter(ContextCompat.getColor(this, iconColorRes));
    }

    private GradientDrawable roundedBackground(int fillColorRes, int strokeColorRes, int radiusDp) {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setColor(ContextCompat.getColor(this, fillColorRes));
        drawable.setCornerRadius(dp(radiusDp));
        drawable.setStroke(dp(1), ContextCompat.getColor(this, strokeColorRes));
        return drawable;
    }


//    public boolean dispatchTouchEvent(MotionEvent ev) {
//        return imageZoomHelper.onDispatchTouchEvent(ev) || super.dispatchTouchEvent(ev);
//    }


    @Override
    public void onBackPressed() {
        mainHandler.removeCallbacks(delayedScrollBookmarkSave);
        saveCurrentScrollBookmark();
        Utils.cancelPendingViewerLaunches(this);
        cancelActiveEpisodeLoader();
        cancelNextPrefetcher();
        if(onBack != null) {
            onBack.run();
            return;
        }
        if(openEpisodeListIfRequested())
            return;
        super.onBackPressed();
    }

    private boolean openEpisodeListIfRequested() {
        if(getIntent() == null || !getIntent().getBooleanExtra("returnToEpisodes", false))
            return false;
        Title targetTitle = title != null ? title : (manga == null ? null : manga.getTitle());
        if(targetTitle == null)
            return false;
        try {
            Intent episodeIntent = Utils.episodeIntent(context, targetTitle);
            episodeIntent.putExtra("online", true);
            if(!Utils.safeStartActivity(context, episodeIntent))
                return false;
            finish();
            return true;
        } catch (Exception e) {
            ml.melun.mangaview.report.CrashReporter.record(e);
            return false;
        }
    }

    Runnable onBack;

    void setOnBackPressed(Runnable onBackPressed){
        this.onBack = onBackPressed;
    }
    void resetOnBackPressed(){
        this.onBack = null;
    }



    private class LoadImagesJob {
        boolean lockui;
        LoadMangaCallback callback;
        Manga m;
        Manga requestedManga;
        int requestedStartPage;
        ViewerLoadPolicy policy;
        MangaRepository.Cancellation cancellation = MangaRepository.cancellation();
        AppDispatchers.TaskHandle handle;
        volatile boolean cancelled = false;
        volatile boolean displayedEarly = false;
        long startedAtMs = 0L;
        Runnable timeoutGuard;

        public LoadImagesJob(Manga m, LoadMangaCallback callback, boolean lockui, ViewerLoadPolicy policy){
            this.lockui = lockui;
            this.m = m;
            this.callback = callback;
            this.policy = policy == null ? ViewerLoadPolicy.RESUME : policy;
            this.requestedManga = snapshotRequestedManga(m);
            this.requestedStartPage = initialPageIndex(this.requestedManga == null ? m : this.requestedManga, this.policy);
        }

        void start() {
            startedAtMs = SystemClock.elapsedRealtime();
            if(lockui) lockUi(true);
            if(lockui) {
                setOnBackPressed(() -> {
                    cancel();
                    if(!openEpisodeListIfRequested())
                        ViewerActivity.this.finish();
                });
                timeoutGuard = () -> {
                    if(loader != this || cancelled || isFinishing())
                        return;
                    ViewerWarmupManager.logMetric("viewer_initial_load_timeout", m == null ? 0 : m.getId());
                    cancel();
                    showViewerImagesUnavailable(m);
                };
                mainHandler.postDelayed(timeoutGuard, 12000);
            }
            if(lockui && canDisplayLoadedImagesImmediately(m, policy))
                displayLoadedImagesEarly();
            handle = AppDispatchers.submitNavigation(() -> {
                int result = LOAD_OK;
                try {
                    if(m.isOnline()) {
                        result = prepareEpisodeIdentity(m);
                        if(result == LOAD_OK && needsConcreteWfwfEpisodeList(title != null ? title : m.getTitle(), m))
                            result = ensureEpisodeListLoaded(m);
                        if(result == LOAD_OK)
                            m = resolvedEpisode(m);
                        if(result == LOAD_OK && needsResolvedNtkEpisodePath(m)) {
                            result = ensureEpisodeListLoaded(m);
                            if(result == LOAD_OK)
                                m = resolvedEpisode(m);
                        }
                        int firstPage = initialPageIndex(m, policy);
                        if(!displayedEarly && allowsResumeFallback(policy) && result == LOAD_OK && shouldResolveResumeBeforeDirectFetch(m)) {
                            result = ensureEpisodeListLoaded(m);
                            PreparedManga prepared = result == LOAD_OK
                                    ? prepareFirstAvailableManga(m, firstPage, cancellation)
                                    : new PreparedManga(null, result);
                            result = prepared.result;
                            if(prepared.manga != null)
                                m = prepared.manga;
                        } else if(result == LOAD_OK && displayedEarly && hasLoadedImages(m)) {
                            ViewerWarmupManager.preloadLoadedImages(context, m, firstPage, width, autoCut,
                                    p.getReverse(), p.getDataSave() ? 3 : 6, Priority.IMMEDIATE, 1);
                        } else if(result == LOAD_OK) {
                            result = isExactLoadPolicy(policy)
                                    ? ViewerWarmupManager.prepareFirstFrameBackgroundDirectOnly(context, m, title, firstPage,
                                    width, autoCut, p.getReverse(), cancellation)
                                    : ViewerWarmupManager.prepareFirstFrameReady(context, m, title, firstPage, width,
                                    autoCut, p.getReverse(), cancellation, initialFirstFrameWaitMs(policy));
                        }
                        if(allowsResumeFallback(policy) && (isBlockingLoadFailure(result) || !hasLoadedImages(m))) {
                            result = ensureEpisodeListLoaded(m);
                            PreparedManga prepared = result == LOAD_OK
                                    ? prepareFirstAvailableManga(m, firstPage, cancellation)
                                    : new PreparedManga(null, result);
                            result = prepared.result;
                            if(prepared.manga != null)
                                m = prepared.manga;
                        }
                        if(isBlockingLoadFailure(result) || (result == LOAD_OK && !hasLoadedImages(m)))
                            result = retryTransientEmptyFirstFrame(result, firstPage);
                    }
                } catch (Exception e) {
                    if(!cancelled && !isFinishing())
                        ml.melun.mangaview.report.CrashReporter.record(e);
                }
                int finalResult = result;
                mainHandler.post(() -> finish(finalResult));
            });
            if(handle == null)
                finish(LOAD_OK);
        }

        private boolean canDisplayLoadedImagesImmediately(Manga target, ViewerLoadPolicy policy) {
            return target != null
                    && target.isOnline()
                    && hasLoadedImages(target)
                    && !needsCanonicalEpisodeBeforeDisplay(target)
                    && !needsResolvedNtkEpisodePath(target);
        }

        private int retryTransientEmptyFirstFrame(int result, int firstPage) throws Exception {
            if(m == null || !m.isOnline() || cancelled || cancellation.isCancelled())
                return result;
            waitForTransientViewerImages();
            if(cancelled || cancellation.isCancelled())
                return result;
            Manga preparedImages = ViewerWarmupManager.usePreparedContinueImages(context, m, title, firstPage);
            if(preparedImages != null && hasLoadedImages(preparedImages)) {
                m = preparedImages;
                preloadImmediateDisplayImages(m, policy);
                ViewerWarmupManager.logMetric("viewer_empty_retry_snapshot_recovered", m.getId());
                return LOAD_OK;
            }
            int retry = ViewerWarmupManager.prepareFirstFrameReady(context, m, title, firstPage, width, autoCut,
                    p.getReverse(), cancellation, initialFirstFrameWaitMs(policy));
            if(retry == LOAD_OK || hasLoadedImages(m))
                ViewerWarmupManager.logMetric("viewer_empty_retry_recovered", m.getId());
            if(isBlockingLoadFailure(retry) && hasLoadedImages(m)
                    && ViewerWarmupManager.hasDecodedFirstFrame(context, m, firstPage, width, autoCut, p.getReverse()))
                return LOAD_OK;
            return retry;
        }

        private void waitForTransientViewerImages() {
            long deadline = SystemClock.elapsedRealtime() + transientEmptyFirstFrameRetryDelayMs();
            while(!cancelled && !cancellation.isCancelled() && !hasLoadedImages(m)
                    && !ViewerWarmupManager.hasPreparedContinueSnapshot(context, m, title)
                    && SystemClock.elapsedRealtime() < deadline) {
                try {
                    Thread.sleep(50L);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                }
            }
        }

        private void displayLoadedImagesEarly() {
            if(cancelled || isFinishing() || loader != this || displayedEarly)
                return;
            displayedEarly = true;
            if(lockui)
                lockUi(false);
            if(title == null)
                title = m.getTitle();
            resetOnBackPressed();
            preloadImmediateDisplayImages(m, policy);
            cacheLoadedContinueSnapshot();
            ViewerWarmupManager.logMetric("viewer_open_to_set_manga_ms", SystemClock.elapsedRealtime() - startedAtMs);
            callback.post(m);
            hydrateEpisodeListAfterFirstFrame(m);
        }

        private void preloadImmediateDisplayImages(Manga target, ViewerLoadPolicy policy) {
            if(target == null || !target.isOnline())
                return;
            int firstPage = initialPageIndex(target, policy);
            ViewerPreloadPolicy.Window window = ViewerPreloadPolicy.immediateDisplayWindow(p.getDataSave());
            ViewerWarmupManager.preloadLoadedImages(context, target, firstPage, width, autoCut,
                    p.getReverse(), window.totalLimit, Priority.IMMEDIATE, window.decodedLimit);
        }

        void finish(Integer res) {
            if(cancelled || isFinishing())
                return;
            if(loader != this)
                return;
            if(timeoutGuard != null)
                mainHandler.removeCallbacks(timeoutGuard);
            loader = null;
            if(displayedEarly) {
                if(res == LOAD_CAPTCHA)
                    showViewerCaptchaRequired(m);
                return;
            }
            int result = res == null ? LOAD_OK : res;
            boolean loadedImages = hasLoadedImages(m);
            if(shouldRecoverEmptyLoadResult(result, loadedImages)) {
                ViewerWarmupManager.logMetric("viewer_empty_recovered", m == null ? -1 : m.getId());
                result = LOAD_OK;
            }
            if(result == LOAD_CAPTCHA){
                if(shouldSuppressBoundaryLoadError(lockui, hasViewerContent())) {
                    ViewerWarmupManager.logMetric("viewer_boundary_captcha_suppressed", m == null ? -1 : m.getId());
                    if(callback != null)
                        callback.post(null);
                    return;
                }
                //캡차 처리 팝업
                if(lockui) lockUi(false);
                resetOnBackPressed();
                showViewerCaptchaRequired(m);
                return;
            }
            if(isBlockingLoadFailure(result) || !loadedImages) {
                if(shouldSuppressBoundaryLoadError(lockui, hasViewerContent())) {
                    ViewerWarmupManager.logMetric("viewer_boundary_empty_suppressed", m == null ? -1 : m.getId());
                    if(callback != null)
                        callback.post(null);
                    return;
                }
                if(lockui) lockUi(false);
                resetOnBackPressed();
                showViewerImagesUnavailable(m);
                return;
            }

            if(lockui) lockUi(false);
            if (title == null)
                title = m.getTitle();
            resetOnBackPressed();
            cacheLoadedContinueSnapshot();
            callback.post(m);
            if(lockui)
                hydrateEpisodeListAfterFirstFrame(m);
        }

        private void cacheLoadedContinueSnapshot() {
            if(m == null || !m.isOnline() || !hasLoadedImages(m))
                return;
            Title currentTitle = title != null ? title : m.getTitle();
            int loadedPage = initialPageIndex(m, policy);
            ViewerWarmupManager.cacheLoadedContinueSnapshot(context, requestedManga, m, currentTitle,
                    requestedStartPage, loadedPage);
        }

        void cancel() {
            cancelled = true;
            cancellation.cancel();
            if(handle != null)
                handle.cancel();
            if(timeoutGuard != null)
                mainHandler.removeCallbacks(timeoutGuard);
            if(loader == this) {
                loader = null;
                if(lockui) lockUi(false);
                resetOnBackPressed();
            }
        }
    }

    private class PrefetchImagesJob {
        Manga target;
        MangaRepository.Cancellation cancellation = MangaRepository.cancellation();
        AppDispatchers.TaskHandle handle;
        volatile boolean cancelled = false;
        volatile boolean promotedToForeground = false;
        long startedAtMs;

        int remainingChainDepth;

        PrefetchImagesJob(Manga target, int remainingChainDepth) {
            this.target = target;
            this.remainingChainDepth = Math.max(0, remainingChainDepth);
        }

        void start() {
            startedAtMs = android.os.SystemClock.elapsedRealtime();
            handle = AppDispatchers.submitImageWarmup(() -> {
                int result = LOAD_OK;
                try {
                    if(target != null && target.isOnline()) {
                        result = ensureEpisodeListLoaded(target);
                        if(result == LOAD_OK)
                            target = resolvedEpisode(target);
                    }
                    if(target != null && target.isOnline() && result == LOAD_OK)
                        result = ViewerWarmupManager.prepareFirstFrameDirectOnly(context, target, title, initialPageIndex(target, ViewerLoadPolicy.EXACT_FIRST_PAGE), width, autoCut, p.getReverse(), cancellation);
                } catch (Exception e) {
                    if(!cancelled && !isFinishing())
                        ml.melun.mangaview.report.CrashReporter.record(e);
                }
                int finalResult = result;
                mainHandler.post(() -> finish(finalResult));
            });
            if(handle == null)
                finish(LOAD_OK);
        }

        void finish(Integer result) {
            if(nextPrefetcher != this)
                return;
            nextPrefetcher = null;
            if(cancelled || isFinishing() || result == LOAD_CAPTCHA || !hasLoadedImages(target))
                return;
            preloadFirstPages(target);
            ViewerWarmupManager.cacheLoadedContinueSnapshot(context, target, target, title, 0, 0);
            ViewerWarmupManager.logMetric("viewer_next_episode_ready_ms", android.os.SystemClock.elapsedRealtime() - startedAtMs);
            scheduleChainedNextEpisodePrefetch(target, remainingChainDepth);
            int attachThreshold = p.getDataSave() ? DATA_SAVE_NEXT_EPISODE_ATTACH_THRESHOLD : NEXT_EPISODE_ATTACH_THRESHOLD;
            if(!promotedToForeground
                    && stripAdapter != null
                    && manager != null
                    && manager.findLastVisibleItemPosition() >= manager.getItemCount() - attachThreshold)
                attachNextEpisode(false);
        }

        void promoteToForeground() {
            promotedToForeground = true;
        }

        void cancel() {
            cancelled = true;
            cancellation.cancel();
            if(handle != null)
                handle.cancel();
            if(nextPrefetcher == this)
                nextPrefetcher = null;
        }
    }

    public void bookmarkRefresh(Manga m){
        bookmarkRefresh(m, ViewerLoadPolicy.RESUME);
    }

    public void bookmarkRefresh(Manga m, ViewerLoadPolicy policy){
        if(policy == null)
            policy = ViewerLoadPolicy.RESUME;
        if(m.useBookmark()) {
            if (m.isOnline()) {
                // if manga is online or has title.gson
                if (title == null) title = m.getTitle();
                p.addRecent(title);
                if (m!=null && m.getId()>0) p.setBookmark(title, m.getId());
            }
        }
    }

    private int ensureEpisodeListLoaded(Manga target) {
        if(target == null || !target.isOnline())
            return LOAD_OK;
        Title currentTitle = title != null ? title : target.getTitle();
        if(currentTitle == null)
            return LOAD_OK;
        restoreTitleEpisodes(currentTitle, target);
        if(needsFullEpisodeList(currentTitle, target)) {
            int result = MangaRepository.fetchEpisodesForeground(currentTitle);
            if(result != LOAD_OK)
                return result;
        }
        currentTitle.ensureProgressEpisodes(target);
        attachEpisodeList(currentTitle, target);
        return LOAD_OK;
    }

    private int prepareEpisodeIdentity(Manga target) {
        if(target == null || !target.isOnline())
            return LOAD_OK;
        Title currentTitle = title != null ? title : target.getTitle();
        if(currentTitle == null)
            return LOAD_OK;
        restoreTitleEpisodes(currentTitle, target);
        currentTitle.ensureProgressEpisodes(target);
        attachEpisodeList(currentTitle, target);
        return LOAD_OK;
    }

    private PreparedManga prepareFirstAvailableManga(Manga target, int firstPage, MangaRepository.Cancellation cancellation) throws Exception {
        Title currentTitle = title != null ? title : target == null ? null : target.getTitle();
        PreparedViewerLaunch prepared = ViewerPreparationCoordinator.prepareFirstReadyCandidate(context, target,
                currentTitle, firstPage, width, autoCut, p.getReverse(), cancellation,
                shouldResolveResumeBeforeDirectFetch(target), initialFirstFrameWaitMs());
        return new PreparedManga(prepared.getManga(), prepared.canLaunch() ? LOAD_OK : prepared.getResultCode());
    }

    private List<Manga> resumeCandidates(Manga target, boolean skipTarget) {
        ArrayList<Manga> candidates = new ArrayList<>();
        if(!skipTarget)
            addResumeCandidate(candidates, target);
        Title currentTitle = title != null ? title : target == null ? null : target.getTitle();
        List<Manga> episodes = currentTitle == null ? null : Utils.snapshotEpisodes(currentTitle);
        if(episodes == null || episodes.size() == 0)
            return candidates;

        int exactIndex = -1;
        if(target != null) {
            for(int i = 0; i < episodes.size(); i++) {
                Manga episode = episodes.get(i);
                if(episode != null && episode.getId() == target.getId() && episode.getBaseMode() == target.getBaseMode()) {
                    exactIndex = i;
                    addResumeCandidate(candidates, episode);
                    break;
                }
            }
        }

        int progressIndex = currentTitle.getBookmarkEpisodeIndex() - 1;
        if(progressIndex >= 0 && progressIndex < episodes.size())
            addResumeCandidate(candidates, episodes.get(progressIndex));
        int computedIndex = currentTitle.getBookmarkIndex() - 1;
        if(computedIndex >= 0 && computedIndex < episodes.size())
            addResumeCandidate(candidates, episodes.get(computedIndex));

        int center = exactIndex >= 0 ? exactIndex : progressIndex;
        for(int distance = 1; center >= 0 && distance <= 2; distance++) {
            addEpisodeAt(candidates, episodes, center - distance);
            addEpisodeAt(candidates, episodes, center + distance);
        }
        return candidates;
    }

    private boolean shouldResolveResumeBeforeDirectFetch(Manga target) {
        if(target == null || !target.isOnline())
            return false;
        Title currentTitle = title != null ? title : target.getTitle();
        if(currentTitle == null)
            return false;
        return ViewerResumeResolver.shouldResolveBeforeDirectFetch(target, currentTitle);
    }

    private void addEpisodeAt(List<Manga> candidates, List<Manga> episodes, int index) {
        if(episodes == null || index < 0 || index >= episodes.size())
            return;
        addResumeCandidate(candidates, episodes.get(index));
    }

    private void addResumeCandidate(List<Manga> candidates, Manga candidate) {
        if(candidate == null || !candidate.isOnline())
            return;
        Title currentTitle = title != null ? title : candidate.getTitle();
        if(currentTitle != null) {
            candidate.setTitle(currentTitle);
            candidate.setTitleId(currentTitle.getId());
            List<Manga> episodes = Utils.snapshotEpisodes(currentTitle);
            if(episodes.size() > 0)
                candidate.setEps(episodes);
        }
        for(Manga existing : candidates)
            if(existing != null && existing.getId() == candidate.getId() && existing.getBaseMode() == candidate.getBaseMode())
                return;
        candidates.add(candidate);
    }

    private static class PreparedManga {
        final Manga manga;
        final int result;

        PreparedManga(Manga manga, int result) {
            this.manga = manga;
            this.result = result;
        }
    }

    private void restoreTitleEpisodes(Title currentTitle, Manga target) {
        if(currentTitle == null || target == null)
            return;
        List<Manga> targetEpisodes = Utils.snapshotEpisodes(target);
        List<Manga> currentEpisodes = Utils.snapshotEpisodes(currentTitle);
        if(targetEpisodes != null && targetEpisodes.size() > 1
                && !containsEpisode(currentEpisodes, target)
                && currentEpisodes.size() < targetEpisodes.size())
            currentTitle.setEps(targetEpisodes);
        if(Utils.snapshotEpisodes(currentTitle).size() <= 1)
            restoreCachedEpisodes(currentTitle);
        attachEpisodeList(currentTitle, target);
    }

    private void restoreCachedEpisodes(Title currentTitle) {
        try {
            String json = CacheFileStore.read(context, episodeCacheKey(currentTitle));
            if(json == null || json.length() == 0)
                json = CacheFileStore.read(context, EpisodeSnapshotCache.legacyKey(currentTitle));
            if(json == null || json.length() == 0)
                return;
            CachedEpisodes cached = new Gson().fromJson(json, new TypeToken<CachedEpisodes>(){}.getType());
            if(cached == null || !CachePolicy.isFresh(cached.savedAt, CachePolicy.EPISODE_TTL_MS)
                    || cached.episodes == null || cached.episodes.size() == 0)
                return;
            currentTitle.setEps(cached.episodes);
        } catch (Exception e) {
            ml.melun.mangaview.report.CrashReporter.record(e);
        }
    }

    private String episodeCacheKey(Title targetTitle) {
        return EpisodeSnapshotCache.key(targetTitle, p != null && p.isNtkSite());
    }

    private static class CachedEpisodes {
        long savedAt;
        ArrayList<Manga> episodes;
    }

    private Manga snapshotRequestedManga(Manga source) {
        if(source == null)
            return null;
        Manga copy = new Manga(source.getId(), source.getName(), source.getDate(), source.getBaseMode());
        copy.setMode(source.getMode());
        copy.setTitleId(source.getTitleId());
        if(source.hasExplicitNtkEpisodePath())
            copy.setNtkEpisodePath(source.getNtkEpisodePath());
        Title sourceTitle = source.getTitle();
        if(sourceTitle != null) {
            Title titleCopy = new Title(sourceTitle.minimize());
            copy.setTitle(titleCopy);
            copy.setTitleId(titleCopy.getId());
        }
        return copy;
    }

    private void hydrateEpisodeListAfterFirstFrame(Manga target) {
        if(target == null || !target.isOnline())
            return;
        Title currentTitle = title != null ? title : target.getTitle();
        if(currentTitle == null || !needsFullEpisodeList(currentTitle, target))
            return;
        mainHandler.postDelayed(() -> {
            AppDispatchers.submitIo(() -> {
                try {
                    int result = MangaRepository.fetchEpisodesForeground(currentTitle);
                    if(result != LOAD_OK || isFinishing())
                        return;
                    attachEpisodeList(currentTitle, target);
                    p.addRecent(currentTitle);
                    p.setBookmark(currentTitle, target.getId());
                    mainHandler.post(() -> {
                        if(!isFinishing() && manga != null && manga.getId() == target.getId()) {
                            refreshToolbar(target);
                            if(stripAdapter != null)
                                stripAdapter.refreshInfoItems();
                            loadEpisodeAtBoundaryIfNeeded();
                        }
                    });
                } catch (Exception e) {
                    if(!isFinishing())
                        ml.melun.mangaview.report.CrashReporter.record(e);
                }
            });
        }, 350);
    }

    private void saveCurrentScrollBookmark() {
        if(shouldHoldBookmarkSaveForInitialRestore())
            return;
        if(strip == null || manager == null || stripAdapter == null)
            return;
        PageItem page = getFocusedVisiblePage();
        if(page == null)
            page = getFirstVisiblePage();
        if(page == null || page.manga == null || !page.manga.useBookmark())
            return;
        int position = stripAdapter.findPagePosition(page);
        if(position == RecyclerView.NO_POSITION)
            return;
        View view = manager.findViewByPosition(position);
        if(view == null)
            return;
        Title bookmarkTitle = titleForProgress(page.manga);
        if(bookmarkTitle != null) {
            p.ensureSourceSiteForTitle(bookmarkTitle);
            page.manga.setTitle(bookmarkTitle);
            page.manga.setTitleId(bookmarkTitle.getId());
        }
        if(title == null)
            title = bookmarkTitle;
        int offset = view.getTop() - strip.getPaddingTop();
        if(shouldSkipInitialTopBookmarkOverwrite(page))
            return;
        p.setViewerBookmark(page.manga, page.index, offset, page.side);
        p.setBookmark(bookmarkTitle, page.manga.getId());
    }

    private void scheduleScrollBookmarkSave() {
        if(strip != null && !shouldScheduleScrollBookmarkSave(strip.getScrollState()))
            return;
        if(scrollBookmarkSavePending)
            return;
        scrollBookmarkSavePending = true;
        mainHandler.removeCallbacks(delayedScrollBookmarkSave);
        mainHandler.postDelayed(delayedScrollBookmarkSave, SCROLL_BOOKMARK_SAVE_DELAY_MS);
    }

    static boolean shouldScheduleScrollBookmarkSaveForTest(int scrollState) {
        return shouldScheduleScrollBookmarkSave(scrollState);
    }

    private static boolean shouldScheduleScrollBookmarkSave(int scrollState) {
        return scrollState == RecyclerView.SCROLL_STATE_IDLE;
    }

    private void prepareInitialViewerPosition(Manga target, ViewerLoadPolicy policy) {
        if(stripAdapter == null || manager == null || target == null)
            return;
        PageItem page = initialPageItem(target, policy);
        int offset = initialPageOffset(target, policy);
        openedWithResumePagePosition = hasInitialResumePosition(page, offset);
        userDraggedAfterViewerPositionPrepared = false;
        if(hasInitialResumePosition(page, offset))
            hideToolbarImmediately();
        restoreInitialViewerPosition(page, offset);
        scheduleInitialResumeRestores(target, policy, page, offset);
    }

    private boolean hasInitialResumePosition(PageItem page, int offset) {
        return page != null && (page.index > 0 || offset != 0 || page.side != PageItem.FIRST);
    }

    private boolean shouldSkipInitialTopBookmarkOverwrite(PageItem page) {
        if(page == null || page.manga == null)
            return false;
        return shouldSkipInitialTopBookmarkOverwrite(openedWithResumePagePosition,
                userDraggedAfterViewerPositionPrepared,
                page.index,
                page.side,
                p.getViewerBookmark(page.manga),
                p.getViewerBookmarkOffset(page.manga),
                p.getViewerBookmarkSide(page.manga));
    }

    private static boolean shouldSkipInitialTopBookmarkOverwrite(boolean openedWithResumePosition,
                                                                boolean userDragged,
                                                                int pageIndex,
                                                                int side,
                                                                int savedIndex,
                                                                int savedOffset,
                                                                int savedSide) {
        if(!openedWithResumePosition || userDragged)
            return false;
        if(pageIndex > 0 || side != PageItem.FIRST)
            return false;
        return savedIndex > 0 || savedOffset != 0 || savedSide != PageItem.FIRST;
    }

    static boolean shouldSkipInitialTopBookmarkOverwriteForTest(boolean openedWithResumePosition,
                                                               boolean userDragged,
                                                               int pageIndex,
                                                               int side,
                                                               int savedIndex,
                                                               int savedOffset,
                                                               int savedSide) {
        return shouldSkipInitialTopBookmarkOverwrite(openedWithResumePosition, userDragged,
                pageIndex, side, savedIndex, savedOffset, savedSide);
    }

    private void hideToolbarImmediately() {
        if(appbar == null || appbarBottom == null)
            return;
        appbar.animate().cancel();
        appbarBottom.animate().cancel();
        appbar.setTranslationY(-appbar.getHeight());
        appbarBottom.setTranslationY(appbarBottom.getHeight());
        toolbarshow = false;
        getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_FULLSCREEN);
    }

    private boolean restoreInitialViewerPosition(PageItem page, int offset) {
        if(stripAdapter == null || manager == null || page == null)
            return false;
        PageItem target = page;
        int position = exactInitialPagePosition(target);
        if(position == RecyclerView.NO_POSITION && target.side != PageItem.FIRST) {
            target = new PageItem(target.index, "", target.manga, PageItem.FIRST);
            position = exactInitialPagePosition(target);
        }
        if(position == RecyclerView.NO_POSITION)
            return false;
        manager.scrollToPositionWithOffset(position, offset);
        return true;
    }

    private void scheduleInitialResumeRestores(Manga target, ViewerLoadPolicy policy, PageItem page, int offset) {
        clearInitialResumeRestore();
        if(target == null || !target.useBookmark() || page == null)
            return;
        if(page.index <= 0 && offset == 0 && page.side == PageItem.FIRST)
            return;
        pendingInitialResumePage = page;
        pendingInitialResumeOffset = offset;
        initialResumeRestorePending = true;
        userScrolledAfterInitialResume = false;
        scheduleInitialResumeRestoreAt(80);
        scheduleInitialResumeRestoreAt(240);
        scheduleInitialResumeRestoreAt(700);
        scheduleInitialResumeRestoreAt(1500);
        mainHandler.postDelayed(clearInitialResumeRestore, 2200);
    }

    private void scheduleInitialResumeRestoreAt(long delayMs) {
        mainHandler.postDelayed(() -> restorePendingInitialResumePosition(false), delayMs);
    }

    private void restorePendingInitialResumePosition(boolean fromDisplayedPage) {
        if(!initialResumeRestorePending || userScrolledAfterInitialResume || pendingInitialResumePage == null)
            return;
        if(isFinishing())
            return;
        if(fromDisplayedPage || sameManga(pendingInitialResumePage.manga, manga))
        {
            if(hasInitialResumePosition(pendingInitialResumePage, pendingInitialResumeOffset))
                hideToolbarImmediately();
            restoreInitialViewerPosition(pendingInitialResumePage, pendingInitialResumeOffset);
        }
    }

    public void onViewerPageDisplayed(PageItem item) {
        if(!initialResumeRestorePending || userScrolledAfterInitialResume || pendingInitialResumePage == null)
            return;
        if(sameInitialResumePage(pendingInitialResumePage, item))
            mainHandler.post(() -> restorePendingInitialResumePosition(true));
    }

    public void onViewerPageAttached(PageItem item) {
        if(strip == null || item == null || item.manga == null)
            return;
        mainHandler.removeCallbacks(syncToolbarToFocusedPage);
        strip.post(() -> {
            mainHandler.removeCallbacks(syncToolbarToFocusedPage);
            syncToolbarToFocusedPage(item);
        });
    }

    private void syncToolbarToFocusedPage(PageItem fallback) {
        if(isFinishing())
            return;
        PageItem page = getFocusedVisiblePage();
        if(page == null || page.manga == null)
            return;
        if(shouldIgnoreInitialToolbarUpdate(page.manga))
            return;
        manga = page.manga;
        updateIntent(manga);
        refreshToolbar(manga);
    }

    private boolean shouldHoldBookmarkSaveForInitialRestore() {
        return initialResumeRestorePending && !userScrolledAfterInitialResume;
    }

    private void markUserScrolledAfterInitialResume() {
        if(!initialResumeRestorePending)
            return;
        userScrolledAfterInitialResume = true;
        clearInitialResumeRestore();
    }

    private void clearInitialResumeRestore() {
        mainHandler.removeCallbacks(clearInitialResumeRestore);
        initialResumeRestorePending = false;
        pendingInitialResumePage = null;
        pendingInitialResumeOffset = 0;
        userScrolledAfterInitialResume = false;
    }

    private void beginInitialToolbarGuard(Manga target) {
        mainHandler.removeCallbacks(clearInitialToolbarGuard);
        initialToolbarGuardManga = target;
        initialToolbarGuardActive = target != null;
        mainHandler.postDelayed(clearInitialToolbarGuard, 1200);
    }

    private boolean shouldIgnoreInitialToolbarUpdate(Manga candidate) {
        return initialToolbarGuardActive
                && initialToolbarGuardManga != null
                && candidate != null
                && !sameManga(initialToolbarGuardManga, candidate);
    }

    private void clearInitialToolbarGuard() {
        mainHandler.removeCallbacks(clearInitialToolbarGuard);
        initialToolbarGuardActive = false;
        initialToolbarGuardManga = null;
    }

    private boolean sameInitialResumePage(PageItem a, PageItem b) {
        return a != null && b != null
                && a.index == b.index
                && a.side == b.side
                && sameManga(a.manga, b.manga);
    }

    private Title titleForProgress(Manga target) {
        Title source = title != null ? title : (target == null ? null : target.getTitle());
        if(source == null || target == null)
            return source;
        List<Manga> episodes = Utils.snapshotEpisodes(target);
        if(Utils.snapshotEpisodes(source).size() <= 1 && episodes != null && episodes.size() > 1)
            source.setEps(episodes);
        target.setTitle(source);
        target.setTitleId(source.getId());
        return source;
    }

    public void updateIntent(Manga m){
        this.manga = m;
        result = new Intent();
        result.putExtra("id", m.getId());
        addReturnEpisodeListResult(result, returnEpisodeTitleJson);
        setResult(RESULT_OK, result);
    }

    private void markReturnEpisodeListTitle(Title targetTitle) {
        returnEpisodeTitleJson = returnEpisodeListTitleJson(targetTitle);
        if(result != null) {
            addReturnEpisodeListResult(result, returnEpisodeTitleJson);
            setResult(RESULT_OK, result);
        }
    }

    static String returnEpisodeListTitleJson(Title targetTitle) {
        if(targetTitle == null)
            return null;
        try {
            return Utils.toViewerTitleJson(targetTitle, true);
        } catch(Exception e) {
            ml.melun.mangaview.report.CrashReporter.record(e);
            return null;
        }
    }

    static void addReturnEpisodeListResult(Intent target, String titleJson) {
        if(target == null || titleJson == null || titleJson.trim().length() == 0)
            return;
        target.putExtra(EXTRA_RETURN_EPISODE_SOURCE_SWITCHED, true);
        target.putExtra(EXTRA_RETURN_EPISODE_TITLE, titleJson);
    }

    private void dispatchScrollAnchorToAdapter(boolean busy) {
        if(stripAdapter == null || manager == null)
            return;
        int anchor = lastViewerScrollDirection >= 0
                ? manager.findLastVisibleItemPosition()
                : manager.findFirstVisibleItemPosition();
        if(anchor == RecyclerView.NO_POSITION)
            anchor = manager.findFirstVisibleItemPosition();
        if(anchor == RecyclerView.NO_POSITION)
            return;
        if(busy) {
            long now = android.os.SystemClock.uptimeMillis();
            if(!shouldDispatchBusyScrollAnchor(lastBusyScrollAnchorPosition, anchor, now - lastBusyScrollAnchorAtMs))
                return;
            lastBusyScrollAnchorPosition = anchor;
            lastBusyScrollAnchorAtMs = now;
        } else {
            lastBusyScrollAnchorPosition = RecyclerView.NO_POSITION;
            lastBusyScrollAnchorAtMs = 0L;
        }
        stripAdapter.onScrollAnchor(anchor, lastViewerScrollDirection, busy);
    }

    static boolean shouldDispatchBusyScrollAnchorForTest(int previousPosition, int nextPosition, long elapsedMs) {
        return shouldDispatchBusyScrollAnchor(previousPosition, nextPosition, elapsedMs);
    }

    private static boolean shouldDispatchBusyScrollAnchor(int previousPosition, int nextPosition, long elapsedMs) {
        if(nextPosition == RecyclerView.NO_POSITION)
            return false;
        if(previousPosition == RecyclerView.NO_POSITION)
            return true;
        if(Math.abs(nextPosition - previousPosition) >= BUSY_SCROLL_ANCHOR_MIN_ITEM_DELTA)
            return true;
        return elapsedMs >= BUSY_SCROLL_ANCHOR_MIN_INTERVAL_MS;
    }

    public void refreshAdapter(){
        strip.stopScroll();
        strip.setAdapter(null);
        strip.getRecycledViewPool().clear();
        strip.setAdapter(stripAdapter);
        stripAdapter.setClickListener(this::toggleToolbar);
    }

    private void preloadInitialViewerPages(Manga target, ViewerLoadPolicy policy) {
        if(stripAdapter == null || target == null)
            return;
        int pageIndex = initialPageIndex(target, policy);
        List<String> images = MangaRepository.imageUrls(target, context);
        if(images == null || images.size() == 0)
            return;
        if(pageIndex < 0 || pageIndex >= images.size())
            pageIndex = 0;
        stripAdapter.preloadInitialAroundPage(new PageItem(pageIndex, "", target));
    }

    private void scheduleInitialViewerPreload(Manga target, ViewerLoadPolicy policy) {
        if(strip == null || target == null)
            return;
        strip.postDelayed(() -> {
            if(isUiAlive() && manga != null && manga.getId() == target.getId())
                preloadInitialViewerPages(target, policy);
        }, initialViewerPreloadDelayMs());
    }

    private static long initialViewerPreloadDelayMs() {
        return 24L;
    }

    static long initialViewerPreloadDelayMsForTest() {
        return initialViewerPreloadDelayMs();
    }

    private void scheduleFocusedPagePreload() {
        if(strip == null)
            return;
        strip.postDelayed(this::preloadFocusedPages, 40);
    }

    private void preloadFocusedPages() {
        if(stripAdapter == null || !isUiAlive())
            return;
        PageItem page = getFocusedVisiblePage();
        if(page == null)
            page = getFirstVisiblePage();
        if(page != null)
            stripAdapter.preloadAroundPage(page, INITIAL_PRELOAD_AHEAD_COUNT);
    }

    private void loadEpisodeAtBoundaryIfNeeded() {
        if(strip == null || manager == null || stripAdapter == null || manager.getItemCount() == 0)
            return;
        if(suppressBoundaryLoadUntilUserScroll)
            return;
        if(android.os.SystemClock.uptimeMillis() < suppressBoundaryLoadUntilMs)
            return;
        int first = manager.findFirstVisibleItemPosition();
        int last = manager.findLastVisibleItemPosition();
        int total = manager.getItemCount();
        int attachThreshold = p.getDataSave() ? DATA_SAVE_NEXT_EPISODE_ATTACH_THRESHOLD : NEXT_EPISODE_ATTACH_THRESHOLD;
        // Previous episodes are loaded by the explicit top-pull gesture. Loading
        // them just because the viewer opens at the top can move resume backward.
        if(last != RecyclerView.NO_POSITION && last >= total - attachThreshold)
            attachNextEpisode(false);
        if(last != RecyclerView.NO_POSITION && isAtViewerBottom())
            attachNextEpisode(true);
    }

    private boolean isAtViewerBottom() {
        return strip != null && !strip.canScrollVertically(1);
    }

    private void loadEpisodeAtBoundaryIfNeededThrottled() {
        if(strip == null || !shouldCheckBoundaryDuringScrollState(strip.getScrollState()))
            return;
        long now = android.os.SystemClock.uptimeMillis();
        if(now - lastBoundaryCheckMs < 80)
            return;
        lastBoundaryCheckMs = now;
        scheduleBoundaryLoadAfterIdle();
    }

    private void scheduleBoundaryLoadAfterIdle() {
        if(mainHandler == null || strip == null || strip.getScrollState() != RecyclerView.SCROLL_STATE_IDLE)
            return;
        mainHandler.removeCallbacks(delayedBoundaryLoad);
        mainHandler.postDelayed(delayedBoundaryLoad, boundaryLoadIdleDelayMs());
    }

    private void cancelDelayedBoundaryLoad() {
        if(mainHandler != null)
            mainHandler.removeCallbacks(delayedBoundaryLoad);
    }

    static boolean shouldCheckBoundaryDuringScrollStateForTest(int scrollState) {
        return shouldCheckBoundaryDuringScrollState(scrollState);
    }

    private static boolean shouldCheckBoundaryDuringScrollState(int scrollState) {
        return scrollState == RecyclerView.SCROLL_STATE_IDLE;
    }

    private void suppressBoundaryLoadsUntilNextScroll() {
        suppressBoundaryLoadUntilMs = android.os.SystemClock.uptimeMillis() + boundaryLoadFailureCooldownMs();
        suppressBoundaryLoadUntilUserScroll = true;
    }

    static long boundaryLoadFailureCooldownMsForTest() {
        return boundaryLoadFailureCooldownMs();
    }

    static long boundaryLoadIdleDelayMsForTest() {
        return boundaryLoadIdleDelayMs();
    }

    private static long boundaryLoadIdleDelayMs() {
        return BOUNDARY_LOAD_IDLE_DELAY_MS;
    }

    private static long boundaryLoadFailureCooldownMs() {
        return 2200L;
    }

    private void handlePreviousEpisodePull(MotionEvent event) {
        if(strip == null || manager == null || stripAdapter == null)
            return;
        switch(event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                clearPendingPreviousJump();
                topPullStartY = event.getY();
                topPullTriggered = false;
                topPullEligible = isAtViewerTop();
                topPullInProgress = true;
                break;
            case MotionEvent.ACTION_MOVE:
                if(topPullTriggered || previousEpisodeBoundaryLoading)
                    return;
                if(!topPullEligible && isAtViewerTop()) {
                    topPullEligible = true;
                }
                if(!topPullEligible)
                    return;
                float threshold = PREVIOUS_EPISODE_PULL_THRESHOLD_DP * getResources().getDisplayMetrics().density;
                if(event.getY() - topPullStartY >= threshold) {
                    topPullTriggered = true;
                }
                break;
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                float releaseThreshold = PREVIOUS_EPISODE_PULL_THRESHOLD_DP * getResources().getDisplayMetrics().density;
                boolean releasePulledFromTop = event.getY() - topPullStartY >= releaseThreshold && isAtViewerTop();
                boolean shouldLoadPrevious = (topPullTriggered || releasePulledFromTop) && !previousEpisodeBoundaryLoading;
                topPullInProgress = false;
                topPullTriggered = false;
                topPullEligible = false;
                if(shouldLoadPrevious)
                    attachPreviousEpisode(true);
                else
                    clearPendingPreviousJump();
                break;
        }
    }

    private PageItem getFirstVisiblePage() {
        if(manager == null || stripAdapter == null)
            return null;
        int first = manager.findFirstVisibleItemPosition();
        int last = manager.findLastVisibleItemPosition();
        if(first == RecyclerView.NO_POSITION || last == RecyclerView.NO_POSITION)
            return null;
        for(int i = Math.max(0, first); i <= last && i < stripAdapter.getItemCount(); i++) {
            PageItem page = stripAdapter.getPageAtPosition(i);
            if(page != null)
                return page;
        }
        return null;
    }

    private PageItem getLastVisiblePage() {
        if(manager == null || stripAdapter == null)
            return null;
        int first = manager.findFirstVisibleItemPosition();
        int last = manager.findLastVisibleItemPosition();
        if(first == RecyclerView.NO_POSITION || last == RecyclerView.NO_POSITION)
            return null;
        for(int i = Math.min(last, stripAdapter.getItemCount() - 1); i >= first && i >= 0; i--) {
            PageItem page = stripAdapter.getPageAtPosition(i);
            if(page != null)
                return page;
        }
        return null;
    }

    private PageItem getFocusedVisiblePage() {
        if(strip == null || manager == null || stripAdapter == null)
            return stripAdapter != null ? stripAdapter.getCurrentVisiblePage() : null;
        int first = manager.findFirstVisibleItemPosition();
        int last = manager.findLastVisibleItemPosition();
        if(first == RecyclerView.NO_POSITION || last == RecyclerView.NO_POSITION)
            return stripAdapter.getCurrentVisiblePage();

        int viewportTop = strip.getPaddingTop();
        int viewportBottom = strip.getHeight() - strip.getPaddingBottom();
        int center = (viewportTop + viewportBottom) / 2;
        PageItem bestPage = null;
        int bestVisible = -1;

        for(int i = Math.max(0, first); i <= last && i < stripAdapter.getItemCount(); i++) {
            PageItem page = stripAdapter.getPageAtPosition(i);
            if(page == null)
                continue;
            View view = manager.findViewByPosition(i);
            if(view == null) {
                if(bestPage == null)
                    bestPage = page;
                continue;
            }
            if(view.getTop() <= center && view.getBottom() >= center)
                return page;
            int visible = Math.max(0, Math.min(view.getBottom(), viewportBottom) - Math.max(view.getTop(), viewportTop));
            if(visible > bestVisible) {
                bestVisible = visible;
                bestPage = page;
            }
        }
        return bestPage != null ? bestPage : stripAdapter.getCurrentVisiblePage();
    }

    private boolean isAtViewerTop() {
        if(strip == null)
            return false;
        if(!strip.canScrollVertically(-1))
            return true;
        if(stripAdapter == null || manager == null)
            return false;
        PageItem page = getFirstVisiblePage();
        if(page == null || page.manga == null || page.index != 0)
            return false;
        int firstPagePosition = stripAdapter.findFirstPagePosition(page.manga);
        if(firstPagePosition == RecyclerView.NO_POSITION)
            return false;
        View firstPage = manager.findViewByPosition(firstPagePosition);
        return firstPage != null && firstPage.getTop() >= 0;
    }

    private void attachPreviousEpisode(boolean jumpToEpisode) {
        PageItem page = getFirstVisiblePage();
        if(page == null || page.manga == null || !page.manga.isOnline())
            return;
        Manga target = previousEpisodeCandidate(page.manga);
        if(target == null)
            return;
        int loadedPosition = stripAdapter.findLastPagePosition(target);
        if(loadedPosition != RecyclerView.NO_POSITION) {
            if(jumpToEpisode) {
                schedulePreviousJump(target, loadedPosition);
            }
            return;
        }
        if(previousEpisodeBoundaryLoading)
        {
            if(jumpToEpisode)
                previousEpisodeBoundaryJumpPending = true;
            return;
        }
        previousEpisodeBoundaryJumpPending = jumpToEpisode;
        previousEpisodeBoundaryLoading = true;
        infiniteScrollCallback.prevEp(new InfiniteLoadCallback() {
            @Override
            public void prevLoaded(Manga m) {
                previousEpisodeBoundaryLoading = false;
                boolean shouldJump = jumpToEpisode || previousEpisodeBoundaryJumpPending;
                previousEpisodeBoundaryJumpPending = false;
                if(m == null) {
                    suppressBoundaryLoadsUntilNextScroll();
                    return;
                }
                if(strip == null || stripAdapter == null || isFinishing())
                    return;
                strip.post(() -> {
                    int position = stripAdapter.findLastPagePosition(m);
                    if(shouldJump && position != RecyclerView.NO_POSITION) {
                        schedulePreviousJump(m, position);
                    }
                });
            }

            @Override
            public void nextLoaded(Manga m) {
            }
        }, page.manga);
    }

    private void schedulePreviousJump(Manga m, int position) {
        pendingPreviousJumpManga = m;
        pendingPreviousJumpPosition = position;
        if(!topPullInProgress)
            runPendingPreviousJump();
    }

    private void runPendingPreviousJump() {
        if(strip == null || pendingPreviousJumpManga == null || pendingPreviousJumpPosition == RecyclerView.NO_POSITION)
            return;
        Manga target = pendingPreviousJumpManga;
        pendingPreviousJumpManga = null;
        pendingPreviousJumpPosition = RecyclerView.NO_POSITION;
        strip.postDelayed(() -> {
            if(strip != null && manager != null && stripAdapter != null && !isFinishing()) {
                if(strip.getScrollState() != RecyclerView.SCROLL_STATE_IDLE)
                    return;
                int position = stripAdapter.findLastPagePosition(target);
                if(position == RecyclerView.NO_POSITION || stripAdapter.getItemCount() == 0)
                    return;
                strip.stopScroll();
                int boundaryPosition = Math.max(0, Math.min(position + 1, stripAdapter.getItemCount() - 1));
                manager.scrollToPositionWithOffset(boundaryPosition, strip.getHeight());
                manga = target;
                updateIntent(target);
                refreshToolbar(target);
                clearProgrammaticPreviousJumpBookmark(target);
            }
        }, 80);
    }

    private void clearPendingPreviousJump() {
        pendingPreviousJumpManga = null;
        pendingPreviousJumpPosition = RecyclerView.NO_POSITION;
        previousEpisodeBoundaryJumpPending = false;
    }

    private void clearProgrammaticPreviousJumpBookmark(Manga m) {
        if(m == null || strip == null)
            return;
        strip.postDelayed(() -> {
            if(!isFinishing())
                p.removeViewerBookmark(m);
        }, 250);
    }

    private void attachNextEpisode(boolean jumpToEpisode) {
        clearPendingPreviousJump();
        PageItem page = getLastVisiblePage();
        if(page == null || page.manga == null || !page.manga.isOnline())
            return;
        Manga target = nextEpisodeCandidate(page.manga);
        if(target == null) {
            ensureEpisodeListThenAttachNext(page.manga, jumpToEpisode);
            return;
        }
        boolean missingGap = MissingEpisodeNavigator.hasMissingNextEpisodeGap(page.manga, target);
        if(missingGap && !shouldPromptMissingEpisodeAtBoundary(jumpToEpisode, true))
            return;
        if(maybePromptMissingNextEpisode(page.manga, target, () -> attachNextEpisode(jumpToEpisode)))
            return;
        int loadedPosition = stripAdapter.findFirstPagePosition(target);
        if(loadedPosition != RecyclerView.NO_POSITION) {
            if(jumpToEpisode) {
                manga = target;
                updateIntent(target);
                refreshToolbar(target);
                manager.scrollToPositionWithOffset(loadedPosition, strip.getPaddingTop());
            }
            return;
        }
        if(nextEpisodeBoundaryLoading)
        {
            if(jumpToEpisode)
                nextEpisodeBoundaryJumpPending = true;
            return;
        }
        nextEpisodeBoundaryJumpPending = jumpToEpisode;
        nextEpisodeBoundaryLoading = true;
        infiniteScrollCallback.nextEp(new InfiniteLoadCallback() {
            @Override
            public void prevLoaded(Manga m) {
            }

            @Override
            public void nextLoaded(Manga m) {
                boolean shouldJump = jumpToEpisode || nextEpisodeBoundaryJumpPending;
                nextEpisodeBoundaryLoading = false;
                nextEpisodeBoundaryJumpPending = false;
                if(m == null) {
                    suppressBoundaryLoadsUntilNextScroll();
                    return;
                }
                if(strip == null || stripAdapter == null || isFinishing())
                    return;
                strip.post(() -> {
                    int position = stripAdapter.findFirstPagePosition(m);
                    if(position == RecyclerView.NO_POSITION)
                        return;
                    if(shouldJump) {
                        manga = m;
                        updateIntent(m);
                        refreshToolbar(m);
                        manager.scrollToPositionWithOffset(position, strip.getPaddingTop());
                    }
                });
            }
        }, page.manga);
    }

    private void ensureEpisodeListThenAttachNext(Manga source, boolean jumpToEpisode) {
        if(source == null || !source.isOnline() || nextEpisodeBoundaryLoading)
            return;
        nextEpisodeBoundaryJumpPending = jumpToEpisode;
        nextEpisodeBoundaryLoading = true;
        AppDispatchers.submitUserAction(() -> {
            int result = LOAD_OK;
            try {
                result = ensureEpisodeListLoaded(source);
            } catch (Exception e) {
                ml.melun.mangaview.report.CrashReporter.record(e);
            }
            int finalResult = result;
            mainHandler.post(() -> {
                boolean shouldJump = jumpToEpisode || nextEpisodeBoundaryJumpPending;
                nextEpisodeBoundaryLoading = false;
                nextEpisodeBoundaryJumpPending = false;
                if(isFinishing())
                    return;
                if(finalResult == LOAD_CAPTCHA) {
                    showViewerCaptchaRequired(source);
                    return;
                }
                if(nextEpisodeCandidate(source) != null)
                    attachNextEpisode(shouldJump);
                else
                    refreshToolbar(source);
            });
        });
    }

    public void refreshToolbar(Manga m){
        //spinner
        eps = Utils.snapshotEpisodes(m);
        if((eps == null || eps.size() == 0) && title != null){
            //backup plan
            eps = Utils.snapshotEpisodes(title);
        }
        boolean hasEpisodes = eps != null && eps.size() > 0;
        episodeButton.setEnabled(hasEpisodes);
        episodeButton.setAlpha(hasEpisodes ? 1f : 0.38f);

        //top toolbar
        toolbarTitle.setText(m.getName());
        toolbarTitle.setSelected(true);

        if(nextEpisodeCandidate(m) == null){
            next.setEnabled(false);
            next.clearColorFilter();
            next.setAlpha(0.38f);
        }
        else {
            next.setEnabled(true);
            next.clearColorFilter();
            next.setAlpha(1f);
        }
        if(previousEpisodeCandidate(m) == null) {
            prev.setEnabled(false);
            prev.clearColorFilter();
            prev.setAlpha(0.38f);
        }
        else {
            prev.setEnabled(true);
            prev.clearColorFilter();
            prev.setAlpha(1f);
        }
        PageItem page = getFocusedVisiblePage();
        if(page!=null)
            pageBtn.setText(page.index+1+"/"+MangaRepository.imageUrls(page.manga, context).size());
    }

    private void prefetchNextEpisode(Manga current) {
        prefetchNextEpisode(current, nextEpisodePrefetchChainDepth(p.getDataSave()));
    }

    private void prefetchNextEpisode(Manga current, int chainDepth) {
        if(current == null || !current.isOnline())
            return;
        Manga target = nextEpisodeCandidate(current);
        if(target == null)
            return;
        if(MissingEpisodeNavigator.hasMissingNextEpisodeGap(current, target))
            return;
        if(nextPrefetcher != null
                && nextPrefetchEpisodeId == target.getId()
                && nextPrefetchBaseMode == target.getBaseMode())
            return;
        boolean loadedImages = hasLoadedImages(target);
        if(loadedImages) {
            preloadFirstPages(target);
            if(stripAdapter != null && manager != null && manager.findLastVisibleItemPosition() >= manager.getItemCount() - NEXT_EPISODE_ATTACH_THRESHOLD)
                attachNextEpisode(false);
            return;
        }
        if(shouldSkipBackgroundNextEpisodeFetch(title == null ? null : title.getSourceSite(), p.isNtkSite(), getHttpClient().isNtk(), loadedImages))
            return;
        if(nextPrefetcher != null)
            nextPrefetcher.cancel();
        nextPrefetchEpisodeId = target.getId();
        nextPrefetchBaseMode = target.getBaseMode();
        nextPrefetcher = new PrefetchImagesJob(target, Math.max(0, chainDepth));
        nextPrefetcher.start();
    }

    private void scheduleChainedNextEpisodePrefetch(Manga prepared, int remainingDepth) {
        if(remainingDepth <= 0 || prepared == null || !prepared.isOnline())
            return;
        Manga next = nextEpisodeCandidate(prepared);
        if(next == null || MissingEpisodeNavigator.hasMissingNextEpisodeGap(prepared, next))
            return;
        mainHandler.postDelayed(() -> {
            if(!isUiAlive() || nextPrefetcher != null)
                return;
            prefetchNextEpisode(prepared, remainingDepth - 1);
        }, nextEpisodeChainPrefetchDelayMs());
    }

    private void scheduleNextEpisodePrefetch(Manga target) {
        if(strip == null || target == null)
            return;
        strip.postDelayed(() -> {
            if(isFinishing() || manga == null || !sameManga(manga, target))
                return;
            prefetchNextEpisode(target);
        }, initialNextEpisodePrefetchDelayMs());
    }

    private static long initialNextEpisodePrefetchDelayMs() {
        return 0L;
    }

    static long initialNextEpisodePrefetchDelayMsForTest() {
        return initialNextEpisodePrefetchDelayMs();
    }

    private static long nextEpisodeChainPrefetchDelayMs() {
        return 80L;
    }

    static int nextEpisodePrefetchChainDepthForTest(boolean dataSave) {
        return nextEpisodePrefetchChainDepth(dataSave);
    }

    private static int nextEpisodePrefetchChainDepth(boolean dataSave) {
        return dataSave ? DATA_SAVE_NEXT_EPISODE_PREFETCH_CHAIN_DEPTH : NEXT_EPISODE_PREFETCH_CHAIN_DEPTH;
    }

    private static long transientEmptyFirstFrameRetryDelayMs() {
        return 650L;
    }

    static long transientEmptyFirstFrameRetryDelayMsForTest() {
        return transientEmptyFirstFrameRetryDelayMs();
    }

    private void cancelNextPrefetcher() {
        if(nextPrefetcher != null)
            nextPrefetcher.cancel();
        nextPrefetchEpisodeId = -1;
        nextPrefetchBaseMode = -1;
    }

    private void promoteOrCancelNextPrefetcher(Manga target) {
        if(target != null
                && nextPrefetcher != null
                && nextPrefetchEpisodeId == target.getId()
                && nextPrefetchBaseMode == target.getBaseMode()
                && sameManga(nextPrefetcher.target, target)) {
            nextPrefetcher.promoteToForeground();
            return;
        }
        cancelNextPrefetcher();
    }

    private void cancelNextPrefetcher(Manga target) {
        if(target == null || nextPrefetcher == null)
            return;
        if(nextPrefetchEpisodeId == target.getId() && nextPrefetchBaseMode == target.getBaseMode())
            cancelNextPrefetcher();
    }

    private boolean hasLoadedImages(Manga target) {
        try {
            if(target == null)
                return false;
            List<String> loadedImages = MangaRepository.imageUrls(target, context);
            return loadedImages != null && loadedImages.size() > 0;
        } catch (Exception e) {
            return false;
        }
    }

    private boolean needsCanonicalEpisodeBeforeDisplay(Manga target) {
        if(target == null
                || !target.isOnline()
                || Manga.visibleEpisodeNumberKey(target.getName()).length() == 0)
            return false;
        Manga canonical = findCanonicalEpisode(target);
        if(isNtkEpisode(target))
            return !target.hasExplicitNtkEpisodePath()
                    || (canonical != null && !sameExactViewerEpisode(canonical, target));
        if(!isWfwfEpisode(target))
            return false;
        return canonical != null && !sameExactViewerEpisode(canonical, target);
    }

    private boolean sameExactViewerEpisode(Manga canonical, Manga target) {
        if(canonical == target)
            return true;
        if(canonical == null || target == null)
            return false;
        String canonicalNumber = Manga.visibleEpisodeNumberKey(canonical.getName());
        String targetNumber = Manga.visibleEpisodeNumberKey(target.getName());
        if(canonical.getId() != target.getId())
            return false;
        if(canonicalNumber.length() > 0 && targetNumber.length() > 0 && !canonicalNumber.equals(targetNumber))
            return false;
        if(isNtkEpisode(target)) {
            String canonicalPath = canonical.getNtkEpisodePath();
            String targetPath = target.getNtkEpisodePath();
            if(canonicalPath != null && canonicalPath.length() > 0 && targetPath != null && targetPath.length() > 0)
                return canonicalPath.equals(targetPath);
        }
        return true;
    }

    private boolean hasViewerContent() {
        return stripAdapter != null && stripAdapter.getItemCount() > 0;
    }

    static boolean shouldSuppressBoundaryLoadErrorForTest(boolean lockui, boolean hasViewerContent) {
        return shouldSuppressBoundaryLoadError(lockui, hasViewerContent);
    }

    private static boolean shouldSuppressBoundaryLoadError(boolean lockui, boolean hasViewerContent) {
        return !lockui && hasViewerContent;
    }

    static boolean shouldRecoverEmptyLoadResultForTest(int result, boolean hasLoadedImages) {
        return shouldRecoverEmptyLoadResult(result, hasLoadedImages);
    }

    private static boolean shouldRecoverEmptyLoadResult(int result, boolean hasLoadedImages) {
        return hasLoadedImages
                && (result == ViewerWarmupManager.LOAD_EMPTY_IMAGES
                || result == ViewerWarmupManager.LOAD_FIRST_FRAME_PENDING);
    }

    private static boolean isBlockingLoadFailure(int result) {
        return result == ViewerWarmupManager.LOAD_EMPTY_IMAGES
                || result == ViewerWarmupManager.LOAD_FIRST_FRAME_PENDING;
    }

    private long initialFirstFrameWaitMs() {
        return initialFirstFrameWaitMs(ViewerLoadPolicy.RESUME);
    }

    private long initialFirstFrameWaitMs(ViewerLoadPolicy policy) {
        if(isExactLoadPolicy(policy))
            return p != null && p.getDataSave() ? 120L : 180L;
        return p != null && p.getDataSave() ? 180L : 260L;
    }

    private static boolean isExactLoadPolicy(ViewerLoadPolicy policy) {
        return policy == ViewerLoadPolicy.EXACT || policy == ViewerLoadPolicy.EXACT_FIRST_PAGE;
    }

    static boolean shouldSkipBackgroundNextEpisodeFetchForTest(String sourceSite, boolean ntkPreference, boolean ntkClient, boolean hasLoadedImages) {
        return shouldSkipBackgroundNextEpisodeFetch(sourceSite, ntkPreference, ntkClient, hasLoadedImages);
    }

    private static boolean shouldSkipBackgroundNextEpisodeFetch(String sourceSite, boolean ntkPreference, boolean ntkClient, boolean hasLoadedImages) {
        if(hasLoadedImages)
            return false;
        String source = sourceSite == null ? "" : sourceSite.trim();
        return (ntkPreference || ntkClient) && source.length() == 0;
    }

    static int viewerItemViewCacheSizeForTest(String sourceSite, boolean dataSave) {
        return viewerItemViewCacheSize(sourceSite, dataSave);
    }

    static int viewerInitialPrefetchItemCountForTest(boolean dataSave) {
        return viewerInitialPrefetchItemCount(dataSave);
    }

    private static int viewerItemViewCacheSize(Manga manga, boolean dataSave) {
        String sourceSite = "";
        if(manga != null && manga.getTitle() != null)
            sourceSite = manga.getTitle().getSourceSite();
        return viewerItemViewCacheSize(sourceSite, dataSave);
    }

    private static int viewerItemViewCacheSize(String sourceSite, boolean dataSave) {
        if(dataSave)
            return 12;
        return "wfwf".equalsIgnoreCase(sourceSite == null ? "" : sourceSite.trim()) ? 36 : 40;
    }

    private static int viewerInitialPrefetchItemCount(boolean dataSave) {
        return dataSave ? 4 : 12;
    }

    private boolean needsFullEpisodeList(Title currentTitle, Manga target) {
        List<Manga> titleEpisodes = currentTitle == null ? null : Utils.snapshotEpisodes(currentTitle);
        List<Manga> targetEpisodes = target == null ? null : Utils.snapshotEpisodes(target);
        int titleCount = titleEpisodes == null ? 0 : titleEpisodes.size();
        int targetCount = targetEpisodes == null ? 0 : targetEpisodes.size();
        if(canFetchWfwfProgressEpisodeDirectly(target))
            return false;
        if(needsConcreteWfwfEpisodeList(currentTitle, target))
            return true;
        if(needsResolvedNtkEpisodePath(target))
            return true;
        return !containsEpisode(titleEpisodes, target) || Math.max(titleCount, targetCount) <= 3;
    }

    private boolean canFetchWfwfProgressEpisodeDirectly(Manga target) {
        return target != null
                && target.isOnline()
                && isWfwfEpisode(target)
                && target.getTitleId() > 0
                && target.getId() > 0
                && Manga.visibleEpisodeNumberKey(target.getName()).length() == 0;
    }

    private boolean needsConcreteWfwfEpisodeList(Title currentTitle, Manga target) {
        if(target == null
                || !target.isOnline()
                || !isWfwfEpisode(target)
                || target.getTitleId() <= 0
                || Manga.visibleEpisodeNumberKey(target.getName()).length() == 0)
            return false;
        List<Manga> titleEpisodes = currentTitle == null ? null : Utils.snapshotEpisodes(currentTitle);
        List<Manga> targetEpisodes = Utils.snapshotEpisodes(target);
        return !hasConcreteWfwfEpisodes(titleEpisodes) && !hasConcreteWfwfEpisodes(targetEpisodes);
    }

    private boolean hasConcreteWfwfEpisodes(List<Manga> episodes) {
        if(episodes == null || episodes.size() == 0)
            return false;
        for(Manga episode : episodes) {
            if(episode == null)
                continue;
            String date = episode.getDate();
            if(date != null && date.trim().length() > 0)
                return true;
            String episodeNumber = Manga.visibleEpisodeNumberKey(episode.getName());
            if(episodeNumber.matches("\\d+")) {
                try {
                    if(Integer.parseInt(episodeNumber) != episode.getId())
                        return true;
                } catch (NumberFormatException ignored) {
                }
            }
        }
        return false;
    }

    private boolean needsResolvedNtkEpisodePath(Manga target) {
        return target != null
                && target.isOnline()
                && isNtkEpisode(target)
                && target.getTitleId() > 0
                && target.getNtkEpisodePath().length() == 0;
    }

    private Manga resolvedEpisode(Manga target) {
        Manga episode = findCanonicalEpisode(target);
        return episode == null ? target : prepareEpisodeCandidate(episode, target);
    }

    private Manga findCanonicalEpisode(Manga target) {
        if(target == null)
            return null;
        Title currentTitle = title != null ? title : target.getTitle();
        List<Manga> episodes = currentTitle == null ? null : Utils.snapshotEpisodes(currentTitle);
        if(episodes == null)
            return null;
        Manga exact = findExactCanonicalEpisode(episodes, target);
        if(exact != null)
            return exact;
        for(Manga episode : episodes) {
            if(sameManga(episode, target)
                    && (!isNtkEpisode(target) || episode.getNtkEpisodePath().length() > 0))
                return episode;
        }
        return null;
    }

    private Manga findExactCanonicalEpisode(List<Manga> episodes, Manga target) {
        if(episodes == null || target == null)
            return null;
        for(Manga episode : episodes)
            if(episode == target)
                return episode;
        for(Manga episode : episodes) {
            if(episode == null || episode.getId() != target.getId() || episode.getBaseMode() != target.getBaseMode())
                continue;
            String episodeNumber = Manga.visibleEpisodeNumberKey(episode.getName());
            String targetNumber = Manga.visibleEpisodeNumberKey(target.getName());
            if(episodeNumber.length() > 0 && targetNumber.length() > 0 && !episodeNumber.equals(targetNumber))
                continue;
            return episode;
        }
        return null;
    }

    private void attachEpisodeList(Title currentTitle, Manga target) {
        if(currentTitle == null)
            return;
        currentTitle.ensureProgressEpisodes(target);
        List<Manga> episodes = Utils.snapshotEpisodes(currentTitle);
        if(episodes != null)
            for(Manga episode : episodes) {
                if(episode != null) {
                    episode.setTitle(currentTitle);
                    episode.setTitleId(currentTitle.getId());
                }
            }
        if(target != null) {
            target.setTitle(currentTitle);
            target.setTitleId(currentTitle.getId());
            if(episodes != null && episodes.size() > 0
                    && containsEpisode(episodes, target)
                    && !containsSameInstance(episodes, target)
                    && (Utils.snapshotEpisodes(target).size() == 0 || episodes.size() >= Utils.snapshotEpisodes(target).size()))
                target.setEps(episodes);
        }
        title = currentTitle;
    }

    private boolean containsSameInstance(List<Manga> episodes, Manga target) {
        if(episodes == null || target == null)
            return false;
        for(Manga episode : episodes) {
            if(episode == target)
                return true;
        }
        return false;
    }

    private boolean containsEpisode(List<Manga> episodes, Manga target) {
        if(episodes == null || target == null)
            return false;
        for(Manga episode : episodes) {
            if(sameManga(episode, target))
                return true;
        }
        return false;
    }

    private void preloadFirstPages(Manga target) {
        if(target == null || !target.isOnline())
            return;
        List<String> images = MangaRepository.imageUrls(target, context);
        ViewerWarmupManager.preloadWindow(context, target, 0, width, autoCut, p.getReverse(), ViewerPreloadPolicy.nextEpisodeWindow(p.getDataSave()));
    }

    private interface EpisodeExpectation {
        boolean isStillExpected(Manga target);
    }

    private void appendMangaWhenIdle(Manga target, EpisodeExpectation expectation, Runnable afterAppend) {
        if(target == null || strip == null)
            return;
        strip.post(() -> {
            runStripMutationWhenReady(() -> {
                if(stripAdapter == null || isFinishing())
                    return;
                if(expectation != null && !expectation.isStillExpected(target)) {
                    if(afterAppend != null)
                        afterAppend.run();
                    return;
                }
                if(!stripAdapter.hasMangaLoaded(target))
                    stripAdapter.appendManga(target);
                if(!stripAdapter.hasMangaLoaded(target)) {
                    if(afterAppend != null)
                        afterAppend.run();
                    return;
                }
                if(afterAppend != null)
                    afterAppend.run();
            }, 0);
        });
    }

    private void insertMangaWhenIdle(Manga target, EpisodeExpectation expectation, Runnable afterInsert) {
        if(target == null || strip == null)
            return;
        strip.post(() -> {
            runStripMutationWhenReady(() -> {
                if(stripAdapter == null || isFinishing())
                    return;
                if(expectation != null && !expectation.isStillExpected(target)) {
                    if(afterInsert != null)
                        afterInsert.run();
                    return;
                }
                if(!stripAdapter.hasMangaLoaded(target))
                    stripAdapter.insertManga(target);
                if(!stripAdapter.hasMangaLoaded(target)) {
                    if(afterInsert != null)
                        afterInsert.run();
                    return;
                }
                if(afterInsert != null)
                    afterInsert.run();
            }, 0);
        });
    }

    private void runStripMutationWhenReady(Runnable mutation, int attempts) {
        if(strip == null || isFinishing())
            return;
        if(strip.isComputingLayout() || strip.getScrollState() != RecyclerView.SCROLL_STATE_IDLE) {
            if(attempts < 40)
                strip.postDelayed(() -> runStripMutationWhenReady(mutation, attempts + 1), 32);
            return;
        }
        mutation.run();
    }

    private void cancelActiveEpisodeLoader() {
        episodeLoaderGeneration++;
        if(loader != null)
            loader.cancel();
        previousEpisodeBoundaryLoading = false;
        nextEpisodeBoundaryLoading = false;
        previousEpisodeBoundaryJumpPending = false;
        nextEpisodeBoundaryJumpPending = false;
    }

    private boolean isActiveEpisodeLoader(int generation) {
        return generation == episodeLoaderGeneration && !isFinishing();
    }

    private boolean isPreviousTargetStillExpected(Manga target) {
        PageItem first = getFirstVisiblePage();
        return first != null && sameManga(first.manga != null ? previousEpisodeCandidate(first.manga) : null, target);
    }

    private boolean isNextTargetStillExpected(Manga target) {
        PageItem last = getLastVisiblePage();
        return last != null && sameManga(last.manga != null ? nextEpisodeCandidate(last.manga) : null, target);
    }

    private boolean sameManga(Manga a, Manga b) {
        return Manga.sameEpisodeIdentity(a, b);
    }

    private boolean allowsResumeFallback(ViewerLoadPolicy policy) {
        return policy == null || policy == ViewerLoadPolicy.RESUME;
    }

    private int initialPageIndex(Manga target, ViewerLoadPolicy policy) {
        if(target == null || !usesInitialViewerBookmark(target, policy))
            return 0;
        return p.getViewerBookmark(target);
    }

    private int initialPageOffset(Manga target, ViewerLoadPolicy policy) {
        if(target == null || !usesInitialViewerBookmark(target, policy))
            return 0;
        return p.getViewerBookmarkOffset(target);
    }

    private int initialPageSide(Manga target, ViewerLoadPolicy policy) {
        if(target == null || !usesInitialViewerBookmark(target, policy))
            return PageItem.FIRST;
        return p.getViewerBookmarkSide(target) == PageItem.SECOND ? PageItem.SECOND : PageItem.FIRST;
    }

    private boolean usesInitialViewerBookmark(Manga target, ViewerLoadPolicy policy) {
        return target != null && target.useBookmark() && policy != ViewerLoadPolicy.EXACT_FIRST_PAGE;
    }

    private PageItem initialPageItem(Manga target, ViewerLoadPolicy policy) {
        int pageIndex = initialPageIndex(target, policy);
        if(pageIndex < 0)
            pageIndex = 0;
        return new PageItem(pageIndex, "", target, initialPageSide(target, policy));
    }

    private int exactInitialPagePosition(PageItem page) {
        if(stripAdapter == null || page == null)
            return RecyclerView.NO_POSITION;
        return stripAdapter.findExactPagePosition(page);
    }

    @Override
    public boolean onMenuOpened(int featureId, Menu menu) {
        return super.onMenuOpened(featureId, menu);
    }

    void lockUi(boolean lock){
        saveBtn.setEnabled(!lock);
        next.setEnabled(!lock);
        prev.setEnabled(!lock);
        pageBtn.setEnabled(!lock);
        cut.setEnabled(!lock);
        strip.setEnabled(!lock);
        episodeButton.setEnabled(!lock);
    }

    private void saveCurrentEpisodeOffline() {
        PageItem page = getFocusedVisiblePage();
        Manga target = page != null && page.manga != null ? page.manga : manga;
        if(target == null)
            return;
        Title targetTitle = title != null ? title : target.getTitle();
        if(targetTitle == null)
            targetTitle = manga != null ? manga.getTitle() : null;
        if(targetTitle != null)
            target.setTitle(targetTitle);
        queueOfflineDownload(context, targetTitle, target);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, @Nullable Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (resultCode == RESULT_CAPTCHA) {
            if(MissingEpisodeNavigator.retryPendingAfterCaptcha(this, missingEpisodePromptState, missingEpisodeHost()))
                return;
            AppDispatchers.runUserAction(() -> {
                getHttpClient().syncCookiesFromWebView(p.getWebtoonUrl(), true);
                getHttpClient().syncCookiesFromWebView(p.getUrl(), true);
                AppDispatchers.runOnMain(() -> {
                    if(isUiAlive())
                        refreshExactEpisode();
                });
            });
        }
    }

    @Override
    protected void onDestroy() {
        destroyed = true;
        Utils.cancelPendingViewerLaunches(this);
        mainHandler.removeCallbacksAndMessages(null);
        if(loader != null)
            loader.cancel();
        missingEpisodePromptState.dismiss();
        cancelNextPrefetcher();
        releaseStripAdapter();
        ViewerWarmupManager.clearDecodedWork(context);
        super.onDestroy();
    }

    private boolean isUiAlive() {
        return !destroyed && !isFinishing() && !isDestroyed();
    }

    static int initialPreloadAheadCountForTest() {
        return INITIAL_PRELOAD_AHEAD_COUNT;
    }

    private void releaseStripAdapter() {
        StripAdapter adapter = stripAdapter;
        if(adapter == null)
            return;
        adapter.release();
        if(strip != null && strip.getAdapter() == adapter) {
            strip.stopScroll();
            strip.setAdapter(null);
            strip.getRecycledViewPool().clear();
        }
        stripAdapter = null;
    }

    private void showViewerImagesUnavailable(Manga target) {
        ViewerWarmupManager.logMetric("viewer_empty_images", target == null ? -1 : target.getId());
        Log.d(TAG, "viewer_empty_detail id=" + (target == null ? -1 : target.getId())
                + ",name=" + safeMangaName(target)
                + ",titleId=" + (target == null ? -1 : target.getTitleId())
                + ",baseMode=" + (target == null ? -1 : target.getBaseMode())
                + ",source=" + safeSourceSite(target)
                + ",url=" + Manga.safeUrl(target)
                + ",ntkPath=" + (target == null ? "" : target.getNtkEpisodePath())
                + ",images=" + safeImageCount(target));
        showPopup(context, "오류", "회차 이미지를 불러오지 못했습니다.", (dialog, which) -> {
            if(!openEpisodeListIfRequested())
                ViewerActivity.this.finish();
        }, dialog -> {
            if(!openEpisodeListIfRequested())
                ViewerActivity.this.finish();
        });
    }

    private String safeMangaName(Manga target) {
        if(target == null || target.getName() == null)
            return "";
        return target.getName();
    }

    private String safeSourceSite(Manga target) {
        if(target == null || target.getTitle() == null || target.getTitle().getSourceSite() == null)
            return "";
        return target.getTitle().getSourceSite();
    }

    private int safeImageCount(Manga target) {
        try {
            List<String> images = target == null ? null : target.getImgs(context);
            return images == null ? 0 : images.size();
        } catch (Exception ignored) {
            return -1;
        }
    }

    private boolean isNtkEpisode(Manga target) {
        if(target != null && target.getTitle() != null && "ntk".equals(target.getTitle().getSourceSite()))
            return true;
        return p != null && p.isNtkSite();
    }

    private void logViewerEpisode(String event, Manga target) {
        if(!Log.isLoggable(TAG, Log.DEBUG))
            return;
        Log.d(TAG, event
                + " id=" + (target == null ? -1 : target.getId())
                + ",name=" + safeMangaName(target)
                + ",titleId=" + (target == null ? -1 : target.getTitleId())
                + ",source=" + safeSourceSite(target)
                + ",ntkPath=" + (target == null ? "" : target.getNtkEpisodePath())
                + ",explicitNtkPath=" + (target != null && target.hasExplicitNtkEpisodePath())
                + ",images=" + safeImageCount(target));
    }

    private boolean isWfwfEpisode(Manga target) {
        return target != null && target.getTitle() != null && "wfwf".equals(target.getTitle().getSourceSite());
    }

    private void showViewerCaptchaRequired(Manga target) {
        ViewerWarmupManager.logMetric("viewer_ntk_captcha_required", target == null ? -1 : target.getId());
        showCaptchaPopup(Manga.safeUrl(target), this, RESULT_CAPTCHA, p);
    }

    public interface InfiniteScrollCallback{
        Manga nextEp(InfiniteLoadCallback callback, Manga curm);
        Manga prevEp(InfiniteLoadCallback callback, Manga curm);
        void updateInfo(Manga m);
    }
    public interface LoadMangaCallback {
        void post(Manga m);
    }
    public interface InfiniteLoadCallback{
        void prevLoaded(Manga m);
        void nextLoaded(Manga m);
    }

}
