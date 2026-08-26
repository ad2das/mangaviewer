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
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
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
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewStub;
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
import java.util.concurrent.ExecutorCompletionService;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.net.HttpURLConnection;
import java.net.URL;

import ml.melun.mangaview.ui.NpaLinearLayoutManager;
import ml.melun.mangaview.ui.EpisodeToolbarView;
import ml.melun.mangaview.R;
import ml.melun.mangaview.adapter.EpisodeAdapter;
import ml.melun.mangaview.adapter.TagAdapter;
import ml.melun.mangaview.glide.ViewerWarmupManager;
import ml.melun.mangaview.mangaview.Manga;
import ml.melun.mangaview.mangaview.Title;
import ml.melun.mangaview.model.EpisodeLoadResult;
import ml.melun.mangaview.reader.ReaderImageCache;
import ml.melun.mangaview.reader.NtkInlineReaderController;
import ml.melun.mangaview.reader.ReaderPreparedStore;
import ml.melun.mangaview.reader.ReaderWarmupCoordinator;
import ml.melun.mangaview.reader.ReaderWindowViewport;
import ml.melun.mangaview.repository.CacheFileStore;
import ml.melun.mangaview.repository.CachePolicy;
import ml.melun.mangaview.repository.EpisodeSnapshotCache;
import ml.melun.mangaview.repository.MangaRepository;
import ml.melun.mangaview.repository.OfflineStore;
import ml.melun.mangaview.runtime.AppDispatchers;
import ml.melun.mangaview.runtime.PerfTrace;
import ml.melun.mangaview.runtime.PerformanceMonitor;
import ml.melun.mangaview.runtime.PrefetchCoordinator;
import ml.melun.mangaview.runtime.ViewerTelemetry;
import ml.melun.mangaview.state.UiState;
import ml.melun.mangaview.viewmodel.EpisodeViewModel;
import ml.melun.mangaview.mangaview.CustomHttpClient;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

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

    @Override
    public boolean dispatchTouchEvent(MotionEvent event) {
        NtkInlineReaderController controller = ntkInlineReaderController;
        if(controller != null && controller.dispatchActivePhysicalInput(event)) return true;
        return super.dispatchTouchEvent(event);
    }
    private static final long NTK_FOREGROUND_TARGET_REFRESH_DELAY_MS = 5_000L;
    private static final long INITIAL_VIEWER_TARGET_WARMUP_DELAY_MS = EpisodeWarmupPolicy.INITIAL_VIEWER_TARGET_DELAY_MS;
    private static final int NTK_DIRECT_INITIAL_PREFETCH_PAGES = 12;
    private static final int NTK_DIRECT_INITIAL_FOREGROUND_VISIBLE_AHEAD = 11;
    private static final long NTK_DIRECT_INITIAL_NON_VISIBLE_DELAY_MS = 1250L;
    private static final long NTK_VISIBLE_WINDOW_SETTLE_MS = 48L;
    private static final int NTK_VISIBLE_FULL_PREPARE_LIMIT = 2;
    private static final int NTK_VISIBLE_HEAD_PREPARE_LIMIT = 4;
    private static final class NtkInlineStageHandlerHolder {
        private static final Handler INSTANCE =
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.P
                        ? Handler.createAsync(Looper.getMainLooper())
                        : new Handler(Looper.getMainLooper());
    }

    private static Handler ntkInlineStageHandler() {
        return NtkInlineStageHandlerHolder.INSTANCE;
    }
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
    final long readerImageCacheProducerGeneration = ReaderImageCache.cacheGenerationForProducer();
    Intent viewer;
    ActionBar actionBar;
    String homeDir;
    int mode = 0;
    FloatingActionButton resumefab;
    boolean loaded = false;
    LinearLayoutCompat fab_container;
    ViewStub fabStub;
    ViewStub episodeEmptyStub;
    EpisodeToolbarView episodeLightToolbar;
    NtkInlineReaderController ntkInlineReaderController;
    NtkInlineReaderController.StageTicket ntkInlineStageTicket;
    long ntkActivityCreateNanos;
    long ntkDiscoveryStartNanos;
    long ntkSetContentViewNanos;
    long ntkEpisodeShellFirstDrawNanos;
    volatile boolean ntkEpisodeShellFrameCommitted;
    volatile long ntkEpisodeShellFrameCommitNanos;
    String ntkPressEligiblePath = "";
    volatile String ntkUserDemandPath = "";
    ReaderPreparedStore.Entry ntkInlineStageEntry;
    ReaderPreparedStore.Listener ntkInlineStageListener;
    String ntkInlineStageKey = "";
    final AtomicBoolean ntkInlineStagePostPending = new AtomicBoolean(false);
    View.OnLayoutChangeListener ntkInlineStageLayoutListener;
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
    boolean ntkViewerSelectionInProgress = false;
    String pendingInlineLaunchToken = "";
    String pendingInlineLaunchPreparedKey = "";
    Manga pendingInlineLaunchManga;
    Title pendingInlineLaunchTitle;
    boolean pendingInlinePressActivated;
    String pendingInlinePressActivatedPath = "";
    int pendingBindingSelectionPosition = -1;
    Manga pendingBindingSelection;
    boolean episodeRefreshSuspendedForReader = false;
    boolean episodeListUserScrolled = false;
    Runnable ntkEpisodeLoadWatchdogRunnable;
    Runnable episodeRefreshRunnable;
    String pendingBrowserOwnedViewerLaunchPath = "";
    final Set<String> preparedNtkReaderSurfacePaths = Collections.newSetFromMap(new ConcurrentHashMap<>());
    long lastNtkReaderSurfacePrepareMs = 0L;
    final Set<String> acceptedCanonicalDirectPaths = Collections.newSetFromMap(new ConcurrentHashMap<>());
    final Set<String> rejectedCanonicalDirectPaths = Collections.newSetFromMap(new ConcurrentHashMap<>());
    final Set<String> speculativeCanonicalDirectPrefetchPaths = Collections.newSetFromMap(new ConcurrentHashMap<>());
    final Set<String> activeNtkSlugManifestPrefetchPaths = Collections.newSetFromMap(new ConcurrentHashMap<>());
    final ConcurrentHashMap<String, Long> activeNtkSlugManifestPrefetchStartedAt = new ConcurrentHashMap<>();
    final ConcurrentHashMap<String, ArrayList<String>> acceptedCanonicalDirectImages = new ConcurrentHashMap<>();
    final ConcurrentHashMap<String, Manga> activeVisibleNtkWarmMangas = new ConcurrentHashMap<>();
    final Set<String> activeVisibleNtkFullWarmPaths = Collections.newSetFromMap(new ConcurrentHashMap<>());
    String lastVisibleNtkWindowSignature = "";
    String lastFocusedVisibleNtkSlugPath = "";
    long lastFocusedVisibleNtkSlugMs = 0L;
    String lastEagerNtkReaderPath = "";
    String lastEagerNtkReaderKey = "";
    final Runnable ntkCenterVisiblePrefetchRunnable =
            () -> warmFocusedVisibleNtkEpisode("center-visible");
    final Runnable ntkVisibleWindowWarmupRunnable = this::warmVisibleNtkWindowIfIdle;
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
        if(ntkUserDemandPath.length() > 0
                && (ntkInlineReaderController == null || !ntkInlineReaderController.isActive())) {
            String returnedPath = ntkUserDemandPath;
            ml.melun.mangaview.MainApplication.clearNtkForegroundViewerPath(returnedPath);
            clearNtkUserDemand(returnedPath);
            ntkViewerSelectionInProgress = false;
            pendingBindingSelectionPosition = -1;
            pendingBindingSelection = null;
        }
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
            if(resumefab != null) {
                if(bookmarkId>-1)
                    resumefab.show();
                else
                    resumefab.hide();
            }
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
        ntkActivityCreateNanos = System.nanoTime();
        dark = p.getDarkTheme();
        if(dark) setTheme(R.style.AppThemeDarkNoTitle);
        super.onCreate(savedInstanceState);
        overridePendingTransition(0, 0);
        PerformanceMonitor.attach(this);
        PerformanceMonitor.screen("episode");
        Intent intent = getIntent();
        ReaderLaunchPayloadStore.Entry launchPayload = ReaderLaunchPayloadStore.take(
                intent.getStringExtra(ReaderLaunchPayloadStore.EXTRA_EPISODE_KEY));
        title = launchPayload == null ? null : launchPayload.getTitle();
        if(title == null)
            title = parseIntentTitle(intent.getStringExtra("title"));
        if(title == null) {
            Toast.makeText(this, "작품 정보를 열 수 없습니다.", Toast.LENGTH_SHORT).show();
            finish();
            return;
        }
        originalTitleName = title.getName();
        switchToTitleSourceSite();
        online = intent.getBooleanExtra("online", true);
        if(online)
            mode = 0;
        ntkDiscoveryStartNanos = System.nanoTime();
        startResumeNtkDiscoveryBeforeContent();
        if(launchProtectedNumericManhwaResumeBeforeContent())
            return;
        boolean lightweightNtkShell = isNtkTitle();
        if(lightweightNtkShell && Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            // The NTK shell contains no editable fields. Exclude the decor itself, not only the
            // content root: AutofillManager otherwise performs its payment/password hint scan in
            // ViewRoot input dispatch before EpisodeRowView receives ACTION_DOWN.
            getWindow().getDecorView().setImportantForAutofill(
                    View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS);
        }
        setContentView(lightweightNtkShell ? R.layout.activity_episode_ntk : R.layout.activity_episode);
        ntkSetContentViewNanos = System.nanoTime();
        if(lightweightNtkShell) {
            registerNtkEpisodeShellFrameCommit();
            ntkInlineReaderController = NtkInlineReaderController.attach(
                    this,
                    new NtkInlineReaderController.Callbacks() {
                        @Override
                        public void onStageReady(@NonNull NtkInlineReaderController controller,
                                                 @NonNull String path,
                                                 @NonNull String preparedKey) {
                            NtkInlineReaderController.StageTicket ticket = controller.getStageTicket();
                            if(ticket == null || !path.equals(ticket.getPath())
                                    || !preparedKey.equals(ticket.getPreparedKey())) {
                                publishNtkPressEligibility(null);
                                return;
                            }
                            if(ntkInlineReaderController != controller
                                    || controller.getStageTicket() != ticket) {
                                publishNtkPressEligibility(null);
                                return;
                            }
                            publishNtkPressEligibility(ticket);
                            Log.d("ViewerPerf", "ntk_inline_stage_ticket_published path=" + path
                                    + ",authority=" + ticket.getAuthority()
                                    + ",pages=" + ticket.getPageCount()
                                    + ",mainImmediate=true");
                        }

                        @Override
                        public void onActivated(@NonNull NtkInlineReaderController controller,
                                                @NonNull String path,
                                                long activationEpoch) {
                            clearNtkInlineStageObserver();
                            ntkViewerSelectionInProgress = true;
                            Manga active = controller.getActiveManga();
                            if(active != null) {
                                ml.melun.mangaview.MainApplication.rememberLastNtkViewerPath(
                                        path,
                                        active.getNtkImageWorkId(),
                                        active.getNtkImageEpisodeId());
                                ml.melun.mangaview.MainApplication.noteNtkForegroundViewerPath(path);
                            }
                            if(pendingBindingSelection != null
                                    && path.equals(pendingBindingSelection.getNtkEpisodePath())) {
                                saveSelectedEpisodeProgress(
                                        pendingBindingSelectionPosition,
                                        pendingBindingSelection);
                            }
                            pendingBindingSelectionPosition = -1;
                            pendingBindingSelection = null;
                            Log.d("ViewerPerf", "ntk_inline_reader_activated path=" + path
                                    + ",epoch=" + activationEpoch
                                    + ",focused=" + controller.wasWindowFocusedAtActivation()
                                    + ",attached=" + controller.wasHostAttachedAtActivation());
                        }

                        @Override
                        public void onStageFailed(@NonNull NtkInlineReaderController controller,
                                                  @NonNull String path,
                                                  @NonNull String reason) {
                            publishNtkPressEligibility(null);
                            clearNtkUserDemand(path);
                            pendingBindingSelectionPosition = -1;
                            pendingBindingSelection = null;
                            ntkViewerSelectionInProgress = false;
                            applyEpisodeWindowChrome();
                            Log.d("ViewerPerf", "ntk_inline_reader_stage_failed path=" + path
                                    + ",reason=" + reason);
                        }

                        @Override
                        public void onExited(@NonNull NtkInlineReaderController controller,
                                             @NonNull String path,
                                             @NonNull String reason) {
                            ml.melun.mangaview.MainApplication.clearNtkForegroundViewerPath(path);
                            clearNtkUserDemand(path);
                            ntkViewerSelectionInProgress = false;
                            applyEpisodeWindowChrome();
                            lastEagerNtkReaderPath = "";
                            lastEagerNtkReaderKey = "";
                            // Exiting to the episode list should warm the next real reopen,
                            // but an Activity teardown in the same event-loop turn must not
                            // allocate another complete tile batch that nobody can consume.
                            // A zero-delay lifecycle gate preserves the production back-to-list
                            // behavior without a test-specific branch or viewer delay.
                            getWindow().getDecorView().post(() -> {
                                if(isFinishing() || isDestroyed()
                                        || !getLifecycle().getCurrentState().isAtLeast(
                                        androidx.lifecycle.Lifecycle.State.RESUMED)
                                        || !hasWindowFocus()
                                        || ntkInlineReaderController == null
                                        || ntkInlineReaderController.isActive())
                                    return;
                                startEagerNtkReaderPreparation("inline-exit");
                            });
                            Log.d("ViewerPerf", "ntk_inline_reader_exited path=" + path
                                    + ",reason=" + reason);
                        }

                        @Override
                        public void onProgressChanged(
                                @NonNull NtkInlineReaderController controller,
                                @NonNull String path,
                                @NonNull ml.melun.mangaview.reader.ReaderSurfaceView.ProgressPosition progress) {
                            Manga active = controller.getActiveManga();
                            if(active != null && p != null) {
                                p.setViewerBookmark(
                                        active,
                                        progress.getPage(),
                                        progress.getOffset());
                            }
                        }

                        @Override
                        public void onFatalReaderError(
                                @NonNull NtkInlineReaderController controller,
                                @NonNull String path,
                                @NonNull String reason,
                                Manga manga) {
                            clearNtkInlineStageObserver();
                            discardPendingInlineLaunch();
                            ntkViewerSelectionInProgress = false;
                            pendingBrowserOwnedViewerLaunchPath = "";
                            ml.melun.mangaview.MainApplication.clearNtkForegroundViewerPath(path);
                            clearNtkUserDemand(path);
                            if(episodeAdapter != null)
                                episodeAdapter.setBookmark(bookmarkIndex);
                            applyEpisodeWindowChrome();
                            Log.e("ViewerPerf", "ntk_inline_reader_fail_closed path=" + path
                                    + ",reason=" + reason
                                    + ",active=" + controller.isActive());
                        }
                    });
            if(ntkEpisodeShellFrameCommitted && ntkInlineReaderController != null) {
                ntkInlineReaderController.onEpisodeShellFrameCommitted(
                        ntkEpisodeShellFrameCommitNanos);
            }
        }
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
        episodeEmptyStub = this.findViewById(R.id.episode_empty_stub);
        applyEpisodeWindowChrome();
        episodeList.setLayoutManager(new NpaLinearLayoutManager(this));
        episodeList.setHasFixedSize(true);
        episodeList.setItemViewCacheSize(2);
        episodeList.setItemAnimator(null);
        episodeList.setOverScrollMode(View.OVER_SCROLL_NEVER);
        episodeList.addOnScrollListener(new RecyclerView.OnScrollListener() {
            @Override
            public void onScrolled(@NonNull RecyclerView recyclerView, int dx, int dy) {
                super.onScrolled(recyclerView, dx, dy);
                if(episodeListUserScrolled && recyclerView.getScrollState() == RecyclerView.SCROLL_STATE_IDLE)
                    scheduleVisibleNtkWindowWarmup();
            }

            @Override
            public void onScrollStateChanged(@NonNull RecyclerView recyclerView, int newState) {
                super.onScrollStateChanged(recyclerView, newState);
                if(newState == RecyclerView.SCROLL_STATE_DRAGGING)
                    episodeListUserScrolled = true;
                PerformanceMonitor.phase(newState == RecyclerView.SCROLL_STATE_IDLE ? "idle" : "scrolling");
                if(newState == RecyclerView.SCROLL_STATE_IDLE) {
                    PerformanceMonitor.reportNow("episode_scroll_idle");
                    if(episodeListUserScrolled)
                        scheduleVisibleNtkWindowWarmup();
                } else {
                    recyclerView.removeCallbacks(ntkVisibleWindowWarmupRunnable);
                    lastVisibleNtkWindowSignature = "";
                }
            }
        });
        homeDir = p.getHomeDir();
        fabStub = findViewById(R.id.episode_fab_stub);
        if(!online)
            ensureEpisodeFabControls();

        if(episodeList.getItemAnimator() instanceof SimpleItemAnimator)
            ((SimpleItemAnimator) episodeList.getItemAnimator()).setSupportsChangeAnimations(false);
        if(recentResult){
            Intent resultIntent = new Intent();
            setResult(RESULT_OK,resultIntent);
        }


        Toolbar toolbar = findViewById(R.id.toolbar);
        if(toolbar != null) {
            setSupportActionBar(toolbar);
            actionBar = getSupportActionBar();
            if(actionBar!=null){
                actionBar.setTitle("");
                actionBar.setDisplayHomeAsUpEnabled(true);
            }
        } else {
            episodeLightToolbar = findViewById(R.id.episode_light_toolbar);
            if(episodeLightToolbar != null) {
                episodeLightToolbar.setFavorite(p.findFavorite(title) > -1);
                episodeLightToolbar.setActions(new EpisodeToolbarView.Actions() {
                    @Override public void onBack() { finish(); }
                    @Override public void onFavorite() { toggleFavorite(); }
                    @Override public void onDownload() {
                        Intent download = new Intent(context, DownloadActivity.class);
                        download.putExtra("title", toViewerTitleJson(title, true));
                        startActivity(download);
                    }
                    @Override public void onMore(View anchor) { showMoreMenu(anchor); }
                });
            }
        }

        if(online) {
            mode = 0;
            boolean renderedCachedEpisodes = showProvidedEpisodesFromIntent();
            if(!renderedCachedEpisodes)
                renderedCachedEpisodes = showCachedEpisodesFromMemory();
            if(shouldLoadDiskEpisodeCacheAsyncForTest(renderedCachedEpisodes))
                loadCachedEpisodesAsync();
            episodeViewModel = new ViewModelProvider(this).get(EpisodeViewModel.class);
            episodeViewModel.state().observe(this, this::renderEpisodeState);
            if(shouldRefreshEpisodesAfterCache(renderedCachedEpisodes)) {
                if(renderedCachedEpisodes) {
                    if(isNtkTitle()
                            && ml.melun.mangaview.MainApplication.isNtkForegroundViewerPathActive())
                        scheduleEpisodeRefreshAfterForegroundTarget();
                    else
                        startEpisodeRefresh(false);
                }
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

    private void ensureEpisodeFabControls() {
        if(fab_container != null)
            return;
        View controls = fabStub == null ? findViewById(R.id.fab_container) : fabStub.inflate();
        fabStub = null;
        fab_container = controls instanceof LinearLayoutCompat
                ? (LinearLayoutCompat) controls
                : findViewById(R.id.fab_container);
        resumefab = findViewById(R.id.resumefab);
    }

    private EpisodeAdapter createEpisodeAdapter(List<Manga> data) {
        EpisodeAdapter adapter = new EpisodeAdapter(context, data, title, mode) {
            @Override
            public void onBindViewHolder(
                    RecyclerView.ViewHolder holder,
                    int adapterPosition,
                    @NonNull List<Object> payloads) {
                super.onBindViewHolder(holder, adapterPosition, payloads);
                Manga boundEpisode = episodeAtAdapterPosition(adapterPosition);
                if(EpisodeActivity.this.isExactOnlineNtkEpisode(boundEpisode)) {
                    // Cold NTK rows are user actions, not readiness indicators. Binding must
                    // never wait for a manifest, decoded bitmap, EGL target, or stage ticket.
                    holder.itemView.setEnabled(true);
                }
            }
        };
        adapter.setNtkPressEligiblePath(ntkPressEligiblePath);
        return adapter;
    }

    private void publishNtkPressEligibility(NtkInlineReaderController.StageTicket ticket) {
        ntkInlineStageTicket = ticket;
        ntkPressEligiblePath = ticket == null ? "" : ticket.getPath();
        if(episodeAdapter != null)
            episodeAdapter.setNtkPressEligiblePath(ntkPressEligiblePath);
        refreshVisibleNtkPressEligibility();
        if(episodeList != null && (!episodeList.isLaidOut()
                || episodeList.getWidth() <= 0 || episodeList.getHeight() <= 0))
            episodeList.requestLayout();
        // EpisodeAdapter updates every visible row synchronously and posts a payload only for an
        // off-screen match. One traversal is required when the first row has not been laid out;
        // the former post + postOnAnimation scans were redundant work left directly ahead of the
        // user's ACTION_DOWN.
    }

    private void refreshVisibleNtkPressEligibility() {
        if(episodeList == null || episodes == null)
            return;
        for(int childIndex = 0; childIndex < episodeList.getChildCount(); childIndex++) {
            View child = episodeList.getChildAt(childIndex);
            int adapterPosition = episodeList.getChildAdapterPosition(child);
            if(adapterPosition <= 0 || adapterPosition > episodes.size())
                continue;
            Manga episode = episodes.get(adapterPosition - 1);
            if(episode == null)
                continue;
            episode.ensureNtkEpisodePathFromIdentity();
            String path = episode.getNtkEpisodePath();
            child.setEnabled(isExactOnlineNtkEpisode(episode)
                    || (path != null && path.trim().equalsIgnoreCase(ntkPressEligiblePath)));
        }
    }

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
        startEagerNtkReaderPreparation("episodes-ready");
        episodeAdapter.setFavorite(p.findFavorite(title)>-1);
        int initialAnchor = bookmarkIndex > 0
                ? bookmarkIndex
                : (title != null && title.isNtkEpisodeListConfirmedEmpty()
                    && episodeAdapter != null && episodeAdapter.getItemCount() > 1 ? 1 : 0);
        RecyclerView.LayoutManager initialLayoutManager = episodeList.getLayoutManager();
        if(initialAnchor > 0 && initialLayoutManager instanceof LinearLayoutManager)
            ((LinearLayoutManager) initialLayoutManager).scrollToPositionWithOffset(initialAnchor, 0);
        episodeList.setAdapter(episodeAdapter);
        episodeList.post(this::refreshVisibleNtkPressEligibility);
        if(!online) {
            ensureEpisodeFabControls();
            View upfab = findViewById(R.id.upfab);
            View downfab = findViewById(R.id.downfab);
            if(upfab != null)
                upfab.setOnClickListener(v -> episodeList.scrollToPosition(0));
            if(downfab != null)
                downfab.setOnClickListener(v -> {
                    if(episodes == null)
                        return;
                    episodeList.scrollToPosition(episodes.size()); //헤더가 0이기 때문
                });
            if(resumefab != null) {
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
            }
        }

        episodeAdapter.setClickListener(new EpisodeAdapter.ItemClickListener() {

            @Override
            public void onItemClick(int position, Manga selected) {
                if(isExactOnlineNtkEpisode(selected)) {
                    enterPressedNtkEpisode(position, selected);
                    return;
                }
                discardPendingInlineLaunch();
                //add local images to manga
                saveSelectedEpisodeProgress(position, selected);
                openViewer(selected,0, true);
            }
            @Override
            public void onItemPress(int position, Manga selected) {
                warmPressedNtkEpisode(position, selected);
            }
            @Override
            public void onItemPressCancelled(int position, Manga selected) {
                if(pendingInlinePressActivated)
                    rollbackInlineSelection("press_cancelled", selected);
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
                rollbackInlineSelection("row_action", m);
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
                discardPendingInlineLaunch();
                Manga target = quickReadEpisode();
                if(target != null) {
                    if(isExactOnlineNtkEpisode(target)) {
                        int targetIndex = episodes == null ? 0 : Math.max(0, episodes.indexOf(target));
                        enterPressedNtkEpisode(targetIndex, target);
                    } else {
                        openViewer(target,0, true);
                    }
                }
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
        }
        bookmarkIndex = progressIndex;
        title.setReadingProgress(progressId, progressIndex, episodes.size());
        // Rendering a cached episode list must not rewrite and re-index the complete recent
        // history on the first traversal. Selection and reader progress are the mutation points.
        if(progressIndex > 0 && episodeAdapter != null)
            episodeAdapter.setBookmark(progressIndex);
    }

    private void warmupInitialViewerTargets() {
        if(!isUiAlive())
            return;
        if(!online || episodes == null || episodes.size() == 0)
            return;
        if(isNtkTitle() && ntkUserDemandPath.length() == 0) {
            Log.d("EpisodeActivity", "ntk_preclick_warmup_skip reason=no_user_demand");
            return;
        }
        if(isWfwfTitle()) {
            warmupLikelyWfwfViewerPage();
            return;
        }
        if(isNtkTitle()) {
            warmupLikelyNtkViewerPage();
            AppDispatchers.io().execute(() ->
                    PrefetchCoordinator.prefetchEpisodeList(context, title, episodes, bookmarkIndex, mode));
        } else {
            PrefetchCoordinator.prefetchEpisodeList(context, title, episodes, bookmarkIndex, mode);
            warmupLikelyNtkViewerPage();
        }
    }

    private void scheduleEpisodeRefreshAfterCacheProbe() {
        if(episodeList == null)
            return;
        cancelEpisodeRefresh();
        episodeRefreshRunnable = () -> startEpisodeRefresh(true);
        episodeList.postDelayed(episodeRefreshRunnable, episodeRefreshAfterCacheProbeMsForTest());
    }

    private void scheduleEpisodeRefreshAfterForegroundTarget() {
        if(episodeList == null)
            return;
        cancelEpisodeRefresh();
        episodeRefreshRunnable = () -> startEpisodeRefresh(false);
        episodeList.postDelayed(episodeRefreshRunnable, NTK_FOREGROUND_TARGET_REFRESH_DELAY_MS);
        Log.d("EpisodeActivity", "ntk_episode_refresh_deferred_for_foreground_target ms="
                + NTK_FOREGROUND_TARGET_REFRESH_DELAY_MS);
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
        if(isNtkTitle() && ntkUserDemandPath.length() == 0)
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
        Manga target = quickReadEpisode();
        if(target == null)
            return;
        target.setMode(mode);
        target.setTitle(title);
        target.setTitleId(title == null ? target.getTitleId() : title.getId());
        target.ensureNtkEpisodePathFromIdentity();
        if(!isNtkUserDemandAuthorized(target)) {
            Log.d("EpisodeActivity", "ntk_likely_warmup_skip reason=no_user_demand,path="
                    + target.getNtkEpisodePath());
            return;
        }
        if(!p.isNtkSite() && !getHttpClient().isNtk())
            return;
        prioritizeResumeNtkViewerPath(target, "likely");
        if(isNtkWebtoonSlugEpisodePath(target.getNtkEpisodePath())) {
            startNtkSlugManifestPrefetch(target, "likely");
            Log.d("EpisodeActivity", "ntk_likely_slug_browser_session_skip reason=avoid_list_open_contention,path="
                    + target.getNtkEpisodePath());
            return;
        }
        if(EpisodeWarmupPolicy.shouldDirectWarmupNtkViewerPage(p.isNtkSite(), getHttpClient().isNtk(),
                target.getNtkEpisodePath())) {
            scheduleNtkReaderSurfacePrepare(target, "likely");
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
        if(!isNtkUserDemandAuthorized(target)) {
            Log.d("EpisodeActivity", "ntk_resume_warmup_skip reason=no_user_demand,path=" + path);
            return;
        }
        if(isNtkWebtoonSlugEpisodePath(path)) {
            startNtkSlugManifestPrefetch(target, "resume");
            Log.d("EpisodeActivity", "ntk_resume_slug_browser_session_skip_no_hidden_warm path=" + path);
            return;
        }
        scheduleNtkReaderSurfacePrepare(target, "resume");
    }

    private void startEagerNtkReaderPreparation(String reason) {
        if(!online || title == null || !isNtkTitle())
            return;
        Manga target = eagerNtkReaderTarget();
        if(target == null)
            return;
        target.setMode(mode);
        target.setTitle(title);
        target.setTitleId(title.getId());
        target.ensureNtkEpisodePathFromIdentity();
        String path = target.getNtkEpisodePath();
        if(path == null || (!path.startsWith("/manhwa/") && !path.startsWith("/webtoon/")))
            return;
        if(!isNtkUserDemandAuthorized(path)) {
            Log.d("EpisodeActivity", "ntk_eager_reader_prepare_skip reason=no_user_demand,path="
                    + path + ",source=" + reason);
            return;
        }
        if(path.equals(lastEagerNtkReaderPath)) {
            Log.d("EpisodeActivity", "ntk_episode_eager_reader_prepare_join path=" + path
                    + ",reason=" + reason);
            return;
        }
        lastEagerNtkReaderPath = path;
        prioritizeResumeNtkViewerPath(target, reason);
        String key = NtkInlineReaderController.strictPreparedKey(path);
        target.startNtkEarlyViewerApiPrefetch(getHttpClient());
        NtkInlineReaderController.StageResult planned =
                ntkInlineReaderController == null
                        ? NtkInlineReaderController.StageResult.PREPARED_NOT_READY
                        : ntkInlineReaderController.planStrictEpisode(
                                target, title, key, 0, 0);
        Log.d("EpisodeActivity", "ntk_episode_eager_reader_prepare path=" + path
                + ",key=" + (key.length() > 0)
                + ",state=" + planned
                + ",reason=" + reason);
        if(key.length() == 0
                || planned == NtkInlineReaderController.StageResult.DESTROYED
                || planned == NtkInlineReaderController.StageResult.WRONG_THREAD) {
            lastEagerNtkReaderPath = "";
            lastEagerNtkReaderKey = "";
        } else {
            lastEagerNtkReaderKey = key;
        }
    }

    private void observeNtkInlineReaderStage(Manga target, String key) {
        clearNtkInlineStageObserver();
        if(ntkInlineReaderController == null || target == null || key == null || key.length() == 0)
            return;
        if(!isNtkUserDemandAuthorized(target))
            return;
        String targetPath = target.getNtkEpisodePath();
        if(ntkInlineReaderController.isStaged()
                && key.equals(ntkInlineReaderController.getStagedKey())
                && targetPath != null
                && targetPath.equals(ntkInlineReaderController.getStagedPath())) {
            NtkInlineReaderController.StageTicket ticket =
                    ntkInlineReaderController.getStageTicket();
            if(ticket != null
                    && targetPath.equals(ticket.getPath())
                    && key.equals(ticket.getPreparedKey())) {
                publishNtkPressEligibility(ticket);
                Log.d("ViewerPerf", "ntk_inline_stage_ticket_published path=" + targetPath
                        + ",authority=" + ticket.getAuthority()
                        + ",pages=" + ticket.getPageCount()
                        + ",alreadyStaged=true");
                // The exact immutable Store lease and attached-root surface are already staged.
                // Re-registering a listener here snapshots every tile map and synchronously calls
                // stageProgressiveRunway() again on ACTION_DOWN, delaying the real ACTION_UP.
                return;
            }
        }
        publishNtkPressEligibility(null);
        ReaderPreparedStore.Entry entry = ReaderPreparedStore.get(key);
        if(entry == null)
            return;
        ntkInlineStageEntry = entry;
        ntkInlineStageKey = key;
        ntkInlineStageListener = new ReaderPreparedStore.Listener() {
            @Override
            public void onUrlsReady(@NonNull List<String> images, int startPage) {
                scheduleNtkInlineReaderStage(target, key);
            }

            @Override
            public void onBitmapReady(int index, @NonNull android.graphics.Bitmap bitmap) {
                scheduleNtkInlineReaderStage(target, key);
            }

            @Override
            public void onBitmapBatchReady(
                    @NonNull java.util.Map<Integer, android.graphics.Bitmap> bitmaps) {
                scheduleNtkInlineReaderStage(target, key);
            }

            @Override
            public void onTilePageBatchReady(
                    @NonNull java.util.Map<Integer, ReaderPreparedStore.PreparedTilePage> tilePages) {
                scheduleNtkInlineReaderStage(target, key);
            }

            @Override
            public void onFailed() {
                runOnUiThread(EpisodeActivity.this::clearNtkInlineStageObserver);
            }
        };
        entry.addListener(ntkInlineStageListener);
        scheduleNtkInlineReaderStage(target, key);
    }

    private void scheduleNtkInlineReaderStage(Manga target, String key) {
        if(!isNtkUserDemandAuthorized(target))
            return;
        if(!ntkInlineStagePostPending.compareAndSet(false, true))
            return;
        Runnable stage = () -> {
            ntkInlineStagePostPending.set(false);
            if(!isUiAlive() || ntkInlineReaderController == null
                    || !key.equals(ntkInlineStageKey)
                    || !key.equals(lastEagerNtkReaderKey))
                return;
            NtkInlineReaderController.StageResult result =
                    ntkInlineReaderController.stageProgressiveRunway(target, title, key, 0, 0);
            if(result == NtkInlineReaderController.StageResult.STAGED
                    || result == NtkInlineReaderController.StageResult.ALREADY_STAGED
                    || result == NtkInlineReaderController.StageResult.ACTIVE) {
                Log.d("ViewerPerf", "ntk_inline_reader_staged path="
                        + target.getNtkEpisodePath() + ",result=" + result);
                clearNtkInlineStageObserver();
                return;
            }
            if(result == NtkInlineReaderController.StageResult.HOST_NOT_LAID_OUT
                    || result == NtkInlineReaderController.StageResult.HOST_NOT_ATTACHED) {
                View host = ntkInlineReaderController.getHostView();
                if(ntkInlineStageLayoutListener == null) {
                    ntkInlineStageLayoutListener = (view, left, top, right, bottom,
                                                     oldLeft, oldTop, oldRight, oldBottom) -> {
                        if(right > left && bottom > top) {
                            view.removeOnLayoutChangeListener(ntkInlineStageLayoutListener);
                            ntkInlineStageLayoutListener = null;
                            scheduleNtkInlineReaderStage(target, key);
                        }
                    };
                    host.addOnLayoutChangeListener(ntkInlineStageLayoutListener);
                }
                View surface = ntkInlineReaderController.getRenderView();
                if(host.isAttachedToWindow() && surface.isAttachedToWindow()
                        && host.getWidth() > 0 && host.getHeight() > 0) {
                    host.removeOnLayoutChangeListener(ntkInlineStageLayoutListener);
                    ntkInlineStageLayoutListener = null;
                    scheduleNtkInlineReaderStage(target, key);
                }
            }
        };
        if(Looper.myLooper() == Looper.getMainLooper())
            stage.run();
        else
            ntkInlineStageHandler().post(stage);
    }

    private void clearNtkInlineStageObserver() {
        ReaderPreparedStore.Entry entry = ntkInlineStageEntry;
        ReaderPreparedStore.Listener listener = ntkInlineStageListener;
        if(entry != null && listener != null)
            entry.removeListener(listener);
        ntkInlineStageEntry = null;
        ntkInlineStageListener = null;
        ntkInlineStageKey = "";
        ntkInlineStagePostPending.set(false);
        if(ntkInlineStageLayoutListener != null && ntkInlineReaderController != null) {
            ntkInlineReaderController.getHostView()
                    .removeOnLayoutChangeListener(ntkInlineStageLayoutListener);
            ntkInlineStageLayoutListener = null;
        }
    }

    /** A resume candidate is still speculative until the user actually selects it. */
    private void prioritizeResumeNtkViewerPath(Manga target, String reason) {
        if(target == null || title == null)
            return;
        String resumePath = title.getResumeNtkEpisodePath();
        String path = target.getNtkEpisodePath();
        if(resumePath == null || path == null || !resumePath.equals(path))
            return;
        Log.d("EpisodeActivity", "ntk_resume_target_speculative_priority path=" + path
                + ",reason=" + reason);
    }

    private Manga eagerNtkReaderTarget() {
        String resumePath = title == null ? "" : title.getResumeNtkEpisodePath();
        List<Manga> candidates = episodes;
        if((candidates == null || candidates.size() == 0) && title != null)
            candidates = title.getEps();
        if(candidates != null && candidates.size() > 0) {
            if(resumePath != null && resumePath.length() > 0) {
                for(Manga candidate : candidates) {
                    if(candidate == null)
                        continue;
                    candidate.setTitle(title);
                    candidate.setTitleId(title.getId());
                    candidate.ensureNtkEpisodePathFromIdentity();
                    if(resumePath.equals(candidate.getNtkEpisodePath()))
                        return candidate;
                }
            }
            int initialVisibleIndex = initialVisibleEagerEpisodeIndexForTest(
                    candidates.size(), bookmarkIndex);
            Manga likely = safeGet(candidates, initialVisibleIndex);
            if(likely != null)
                return likely;
            for(Manga candidate : candidates) {
                if(candidate != null)
                    return candidate;
            }
        }
        if(resumePath == null || resumePath.length() == 0)
            return null;
        return ntkEpisodeFromPathForWarm(resumePath);
    }

    private Manga ntkEpisodeFromPathForWarm(String path) {
        try {
            String[] parts = path.split("/");
            if(parts.length < 4)
                return null;
            String episodeToken = parts[3];
            String workId = parts.length > 2 ? parts[2] : "";
            String imageEpisodeId = episodeToken;
            if(episodeToken.startsWith("kp-")) {
                String[] kpParts = episodeToken.split("-");
                if(kpParts.length >= 3) {
                    workId = kpParts[1];
                    imageEpisodeId = kpParts[2];
                }
            }
            int episodeId = Integer.parseInt(imageEpisodeId);
            Manga target = new Manga(episodeId, episodeId + "화", "", title.getBaseMode());
            target.setMode(mode);
            target.setTitle(title);
            target.setTitleId(title.getId());
            target.setNtkEpisodePath(path);
            target.setNtkImageWorkId(workId);
            target.setNtkImageEpisodeId(imageEpisodeId);
            return target;
        } catch(Exception ignored) {
            return null;
        }
    }

    private void warmPressedNtkEpisode(int position, Manga selected) {
        // ACTION_DOWN is deliberately metadata-only. Starting discovery, ACK, manifest, image,
        // decode, or EGL work here would move content work ahead of the user's committed click.
        if(isExactOnlineNtkEpisode(selected))
            Log.d("ViewerPerf", "ntk_inline_press_observed path=" + selected.getNtkEpisodePath());
    }

    private void enterPressedNtkEpisode(int adapterPosition, Manga selected) {
        if(!isExactOnlineNtkEpisode(selected))
            return;
        if(ntkViewerSelectionInProgress) {
            Log.d("ViewerPerf", "ntk_cold_reader_click_join path=" + selected.getNtkEpisodePath());
            return;
        }

        discardPendingInlineLaunch();
        clearNtkInlineStageObserver();
        selected.setMode(mode);
        selected.setTitle(title);
        selected.setTitleId(title == null ? selected.getTitleId() : title.getId());
        selected.ensureNtkEpisodePathFromIdentity();
        String path = selected.getNtkEpisodePath();
        if(path == null || path.length() == 0)
            return;

        // This is the first content-demand boundary. Telemetry is opened before discovery so the
        // manifest request, ACK work and first actual native presentation share one generation.
        ntkUserDemandPath = path;
        ntkViewerSelectionInProgress = true;
        pendingBindingSelectionPosition = adapterPosition;
        pendingBindingSelection = selected;
        lastEagerNtkReaderPath = path;
        lastEagerNtkReaderKey = "";
        ViewerTelemetry.viewerOpen(ntkTelemetryWorkId(selected), path, String.valueOf(mode));

        // Retire the detail/list request group before the strict viewer owns its first socket.
        // Domain discovery can fan out across many compatibility roots; cancelling it after
        // startColdRolling lets those calls compete with the exact RSC/ACK requests during the
        // only latency-critical cold window. This cancellation performs no viewer preparation
        // and starts no network work.
        cancelEpisodeRefresh();
        if(episodeViewModel != null)
            episodeViewModel.cancelActiveLoad();
        noteNtkForegroundViewer(selected);

        // The immutable native strip can only activate after sealing the whole episode.  Exact
        // cold launches therefore use ReaderV2's production rolling ReaderSurfaceView session:
        // the Activity transition happens immediately and no prepared key/image collection is
        // consulted before it owns the foreground.
        boolean launched = ml.melun.mangaview.Utils.openColdExactNtkViewer(
                context, selected, 0, title);
        Log.d("ViewerPerf", "ntk_cold_rolling_reader_enter path=" + path
                + ",launched=" + launched + ",prepared=false");
        if(launched) {
            pendingBindingSelectionPosition = -1;
            pendingBindingSelection = null;
            pendingBrowserOwnedViewerLaunchPath = "";
            pendingInlinePressActivated = false;
            pendingInlinePressActivatedPath = "";
            ntkLoadTimeoutHandled = true;
            cancelNtkEpisodeLoadWatchdog();
            saveSelectedEpisodeProgress(adapterPosition, selected);
            return;
        }

        long failedViewerGeneration = ViewerTelemetry.activeGeneration();
        ml.melun.mangaview.reader.NtkStrictEpisodeDiscoveryCoordinator.retireViewerOwnership(
                path, failedViewerGeneration, "cold_activity_launch_failed");
        ViewerTelemetry.viewerClosed("cold_activity_launch_failed");
        clearNtkUserDemand(path);
        rollbackInlineSelection("cold_activity_launch_failed", selected);
    }

    private String ntkTelemetryWorkId(Manga selected) {
        String workId = selected == null ? "" : selected.getNtkImageWorkId();
        if(workId != null && workId.length() > 0)
            return workId;
        if(title != null && title.getId() > 0)
            return String.valueOf(title.getId());
        return selected == null ? "unknown" : String.valueOf(selected.getTitleId());
    }

    private static boolean exactInlineLaunchMatches(
            Manga selected,
            ReaderLaunchPayloadStore.Entry launch,
            String expectedKey) {
        if(selected == null || launch == null || launch.getManga() == null)
            return false;
        return exactInlineLaunchMatchesForTest(
                launch.getManga(),
                selected,
                launch.getPreparedKey(),
                expectedKey);
    }

    private boolean isExactOnlineNtkEpisode(Manga manga) {
        if(!online || manga == null || !isNtkTitle())
            return false;
        String path = manga.getNtkEpisodePath();
        String normalized = path == null
                ? ""
                : path.trim().toLowerCase(java.util.Locale.ROOT);
        return normalized.startsWith("/manhwa/") || normalized.startsWith("/webtoon/");
    }

    private boolean isNtkUserDemandAuthorized(Manga manga) {
        if(manga == null)
            return false;
        manga.ensureNtkEpisodePathFromIdentity();
        return isNtkUserDemandAuthorized(manga.getNtkEpisodePath());
    }

    private boolean isNtkUserDemandAuthorized(String path) {
        return path != null && path.length() > 0 && path.equals(ntkUserDemandPath);
    }

    private void clearNtkUserDemand(String path) {
        if(path == null || path.length() == 0 || path.equals(ntkUserDemandPath))
            ntkUserDemandPath = "";
    }

    private void discardPendingInlineLaunch() {
        ReaderLaunchPayloadStore.discard(pendingInlineLaunchToken);
        pendingInlineLaunchToken = "";
        pendingInlineLaunchPreparedKey = "";
        pendingInlineLaunchManga = null;
        pendingInlineLaunchTitle = null;
    }

    private void rollbackInlineSelection(String reason, Manga selected) {
        boolean hadInlineSelection = pendingInlineLaunchToken.length() > 0
                || pendingInlineLaunchManga != null
                || isExactOnlineNtkEpisode(selected);
        boolean rollbackPressActivation = pendingInlinePressActivated
                && ntkInlineReaderController != null
                && pendingInlinePressActivatedPath.equals(
                        ntkInlineReaderController.getActivePath());
        pendingInlinePressActivated = false;
        pendingInlinePressActivatedPath = "";
        if(rollbackPressActivation)
            ntkInlineReaderController.cancelPressActivation();
        discardPendingInlineLaunch();
        if(!hadInlineSelection)
            return;
        boolean active = ntkInlineReaderController != null
                && ntkInlineReaderController.isActive();
        ntkViewerSelectionInProgress = active;
        pendingBrowserOwnedViewerLaunchPath = "";
        if(!active && selected != null)
            ml.melun.mangaview.MainApplication.clearNtkForegroundViewerPath(
                    selected.getNtkEpisodePath());
        if(!active && episodeAdapter != null)
            episodeAdapter.setBookmark(bookmarkIndex);
        if(!active)
            applyEpisodeWindowChrome();
        Log.d("ViewerPerf", "ntk_inline_reader_selection_rollback reason=" + reason
                + ",active=" + active);
    }

    static boolean exactInlineLaunchMatchesForTest(
            Manga launched,
            Manga selected,
            String launchedKey,
            String expectedKey) {
        return launched != null
                && selected != null
                && expectedKey != null
                && expectedKey.length() > 0
                && expectedKey.equals(launchedKey)
                && Manga.sameEpisodeIdentity(launched, selected);
    }

    private void scheduleVisibleNtkWindowWarmup() {
        if(ntkUserDemandPath.length() == 0)
            return;
        if(ntkViewerSelectionInProgress || !online || episodeList == null
                || episodes == null || episodes.size() == 0 || !isNtkTitle())
            return;
        episodeList.removeCallbacks(ntkVisibleWindowWarmupRunnable);
        if(episodeList.getScrollState() != RecyclerView.SCROLL_STATE_IDLE)
            return;
        episodeList.postDelayed(ntkVisibleWindowWarmupRunnable, NTK_VISIBLE_WINDOW_SETTLE_MS);
    }

    private void warmVisibleNtkWindowIfIdle() {
        if(ntkUserDemandPath.length() == 0)
            return;
        if(ntkViewerSelectionInProgress || !isUiAlive() || episodeList == null || episodes == null || episodes.size() == 0
                || !isNtkTitle() || episodeList.getScrollState() != RecyclerView.SCROLL_STATE_IDLE)
            return;
        RecyclerView.LayoutManager manager = episodeList.getLayoutManager();
        if(!(manager instanceof LinearLayoutManager))
            return;
        LinearLayoutManager layoutManager = (LinearLayoutManager) manager;
        int firstAdapterPosition = Math.max(1, layoutManager.findFirstVisibleItemPosition());
        int lastAdapterPosition = Math.min(episodes.size(), layoutManager.findLastVisibleItemPosition());
        if(firstAdapterPosition <= 0 || lastAdapterPosition < firstAdapterPosition)
            return;

        ArrayList<Manga> visible = new ArrayList<>();
        StringBuilder signature = new StringBuilder();
        for(int adapterPosition = firstAdapterPosition; adapterPosition <= lastAdapterPosition; adapterPosition++) {
            Manga candidate = safeGet(episodes, adapterPosition - 1);
            if(candidate == null)
                continue;
            candidate.setMode(mode);
            candidate.setTitle(title);
            candidate.setTitleId(title == null ? candidate.getTitleId() : title.getId());
            candidate.ensureNtkEpisodePathFromIdentity();
            String path = candidate.getNtkEpisodePath();
            if(path == null || (!path.startsWith("/manhwa/") && !path.startsWith("/webtoon/")))
                continue;
            visible.add(candidate);
            if(signature.length() > 0)
                signature.append('|');
            signature.append(path);
        }
        if(visible.isEmpty())
            return;
        String nextSignature = signature.toString();
        if(nextSignature.equals(lastVisibleNtkWindowSignature))
            return;
        lastVisibleNtkWindowSignature = nextSignature;

        Set<String> nextVisiblePaths = Collections.newSetFromMap(new ConcurrentHashMap<>());
        for(Manga candidate : visible)
            nextVisiblePaths.add(candidate.getNtkEpisodePath());
        for(Manga previous : new ArrayList<>(activeVisibleNtkWarmMangas.values())) {
            String previousPath = previous == null ? "" : previous.getNtkEpisodePath();
            if(previousPath.length() == 0 || nextVisiblePaths.contains(previousPath))
                continue;
            activeVisibleNtkWarmMangas.remove(previousPath);
            activeVisibleNtkFullWarmPaths.remove(previousPath);
            AppDispatchers.submitNtkViewerCritical(() -> {
                ReaderWarmupCoordinator.cancelAuthoritativeNtkEpisode(previousPath, true);
                ReaderImageCache.INSTANCE.cancelNtkEpisodeVolatile(previous);
            });
        }
        for(Manga candidate : visible)
            activeVisibleNtkWarmMangas.put(candidate.getNtkEpisodePath(), candidate);

        ArrayList<Manga> fullCandidates = new ArrayList<>(NTK_VISIBLE_FULL_PREPARE_LIMIT);
        addUniqueVisibleCandidate(fullCandidates, visible.get(visible.size() - 1));
        addUniqueVisibleCandidate(fullCandidates, visible.get(0));
        Set<String> nextFullPaths = Collections.newSetFromMap(new ConcurrentHashMap<>());
        for(Manga candidate : fullCandidates)
            nextFullPaths.add(candidate.getNtkEpisodePath());
        for(String previousFullPath : new ArrayList<>(activeVisibleNtkFullWarmPaths)) {
            if(!nextFullPaths.contains(previousFullPath)) {
                activeVisibleNtkFullWarmPaths.remove(previousFullPath);
                AppDispatchers.submitNtkViewerCritical(() ->
                        ReaderWarmupCoordinator.cancelAuthoritativeNtkEpisode(previousFullPath, true));
            }
        }
        for(Manga candidate : fullCandidates) {
            String path = candidate.getNtkEpisodePath();
            activeVisibleNtkFullWarmPaths.add(path);
            AppDispatchers.submitNtkViewerCritical(() -> {
                if(!activeVisibleNtkFullWarmPaths.contains(path))
                    return;
                int expected = Math.max(0, candidate.getNtkImageCount());
                if(expected > 0 && expected <= 8) {
                    String key = ReaderWarmupCoordinator.primeAuthoritativeNtkEpisode(
                            getApplicationContext(), candidate, title, true);
                    Log.d("EpisodeActivity", "ntk_visible_window_full_prepare path=" + path
                            + ",expected=" + expected + ",key=" + (key != null));
                } else if(expected > 0) {
                    prepareVisibleNtkHeadBytes(candidate, true);
                    Log.d("EpisodeActivity", "ntk_visible_window_large_head_prepare path=" + path
                            + ",expected=" + expected);
                } else {
                    prepareNtkReaderSurface(candidate, "visible-edge");
                    Log.d("EpisodeActivity", "ntk_visible_window_manifest_prepare path=" + path
                            + ",expected=" + expected);
                }
            });
        }

        int headPrepared = 0;
        int center = visible.size() / 2;
        for(int distance = 0; distance < visible.size() && headPrepared < NTK_VISIBLE_HEAD_PREPARE_LIMIT; distance++) {
            int[] indexes = distance == 0
                    ? new int[]{center}
                    : new int[]{center - distance, center + distance};
            for(int index : indexes) {
                if(index < 0 || index >= visible.size() || headPrepared >= NTK_VISIBLE_HEAD_PREPARE_LIMIT)
                    continue;
                Manga candidate = visible.get(index);
                String path = candidate.getNtkEpisodePath();
                if(nextFullPaths.contains(path))
                    continue;
                headPrepared++;
                AppDispatchers.submitImageWarmup(() -> prepareVisibleNtkHeadBytes(candidate, false));
            }
        }
        Log.d("EpisodeActivity", "ntk_visible_window_prepare first=" + firstAdapterPosition
                + ",last=" + lastAdapterPosition
                + ",visible=" + visible.size()
                + ",full=" + fullCandidates.size()
                + ",head=" + headPrepared);
    }

    private void addUniqueVisibleCandidate(ArrayList<Manga> candidates, Manga candidate) {
        if(candidate == null || candidates.size() >= NTK_VISIBLE_FULL_PREPARE_LIMIT)
            return;
        String path = candidate.getNtkEpisodePath();
        for(Manga existing : candidates) {
            if(path != null && path.equals(existing.getNtkEpisodePath()))
                return;
        }
        candidates.add(candidate);
    }

    private void prepareVisibleNtkHeadBytes(Manga candidate, boolean allowFullCandidate) {
        if(candidate == null || title == null)
            return;
        String path = candidate.getNtkEpisodePath();
        if(!isNtkUserDemandAuthorized(path))
            return;
        if(path == null || !activeVisibleNtkWarmMangas.containsKey(path)
                || (!allowFullCandidate && activeVisibleNtkFullWarmPaths.contains(path)))
            return;
        int expected = Math.max(0, candidate.getNtkImageCount());
        if(expected <= 0)
            expected = ntkViewerPayloadHintImageCount(candidate, path);
        if(expected <= 0)
            return;
        ArrayList<String> images = path.startsWith("/webtoon/")
                ? buildNtkWebtoonDirectManifest(candidate, path, expected)
                : buildNtkManhwaDirectManifest(path, expected);
        if(images == null || images.isEmpty())
            return;
        int start = ntkDirectManifestStartIndex(candidate, images, true);
        if(start < 0 || start >= images.size())
            return;
        try {
            ReaderImageCache.INSTANCE.getOrFetchFile(
                    getApplicationContext(), candidate, images.get(start), null);
            Log.d("EpisodeActivity", "ntk_visible_window_head_bytes path=" + path
                    + ",page=" + start);
        } catch(Throwable t) {
            Log.d("EpisodeActivity", "ntk_visible_window_head_bytes_error path=" + path + "," + t);
        }
    }

    private void warmVisibleNtkEpisode(int position, Manga selected) {
        if(!online || selected == null || !isNtkTitle())
            return;
        selected.ensureNtkEpisodePathFromIdentity();
        prepareNtkEpisodeForFastOpen(selected, "visible");
        warmNtkEpisodeNeighborhood(position, "visible-neighbor", 2);
    }

    private void warmFocusedVisibleNtkEpisode(String reason) {
        if(!online || episodes == null || episodes.size() == 0 || episodeList == null || !isNtkTitle())
            return;
        RecyclerView.LayoutManager manager = episodeList.getLayoutManager();
        if(!(manager instanceof LinearLayoutManager))
            return;
        LinearLayoutManager layoutManager = (LinearLayoutManager) manager;
        int first = layoutManager.findFirstVisibleItemPosition();
        int last = layoutManager.findLastVisibleItemPosition();
        int adapterPosition;
        if(first == RecyclerView.NO_POSITION || last == RecyclerView.NO_POSITION)
            adapterPosition = layoutManager.findFirstCompletelyVisibleItemPosition();
        else
            adapterPosition = Math.max(1, (Math.max(1, first) + Math.max(1, last)) / 2);
        if(adapterPosition <= 0)
            return;
        int index = adapterPosition - 1;
        if(index < 0 || index >= episodes.size())
            return;
        Manga selected = safeGet(episodes, index);
        if(selected == null)
            return;
        selected.ensureNtkEpisodePathFromIdentity();
        String path = selected.getNtkEpisodePath();
        if(!isNtkWebtoonSlugEpisodePath(path))
            return;
        long now = android.os.SystemClock.elapsedRealtime();
        if(path.equals(lastFocusedVisibleNtkSlugPath) && now - lastFocusedVisibleNtkSlugMs < 1200L)
            return;
        lastFocusedVisibleNtkSlugPath = path;
        lastFocusedVisibleNtkSlugMs = now;
        prepareNtkEpisodeForFastOpen(selected, "center-visible-" + reason);
    }

    private void warmNtkEpisodeNeighborhood(int adapterPosition, String reason, int radius) {
        if(!online || episodes == null || episodes.size() == 0 || !isNtkTitle())
            return;
        int center = Math.max(0, adapterPosition);
        int first = Math.max(0, center - Math.max(0, radius));
        int last = Math.min(episodes.size() - 1, center + Math.max(0, radius));
        for(int i = first; i <= last; i++) {
            Manga episode = safeGet(episodes, i);
            if(episode != null)
                prepareNtkEpisodeForFastOpen(episode, reason);
        }
    }

    private void prepareNtkEpisodeForFastOpen(Manga selected, String reason) {
        if(!online || selected == null || !isNtkTitle())
            return;
        selected.setMode(mode);
        selected.setTitle(title);
        selected.setTitleId(title == null ? selected.getTitleId() : title.getId());
        selected.ensureNtkEpisodePathFromIdentity();
        String path = selected.getNtkEpisodePath();
        if(!isNtkUserDemandAuthorized(path)) {
            Log.d("EpisodeActivity", "ntk_fast_open_prepare_skip reason=no_user_demand,path="
                    + path + ",source=" + reason);
            return;
        }
        if(ml.melun.mangaview.MainApplication.isNtkForegroundViewerPathActive()) {
            Log.d("EpisodeActivity", "ntk_visible_manifest_skip_foreground_active path=" + path);
            return;
        }
        if(isNtkWebtoonSlugEpisodePath(path)) {
            boolean started = startNtkSlugManifestPrefetch(selected, reason);
            if(started) {
                Log.d("EpisodeActivity", "ntk_visible_slug_browser_session_skip reason=api_manifest_only,"
                        + "source=" + reason + ",path=" + path);
            } else if(reason != null && (reason.contains("press") || reason.contains("open"))) {
                Log.d("EpisodeActivity", "ntk_visible_slug_browser_session_skip_no_manifest reason="
                        + reason + ",path=" + path);
            }
            return;
        }
        scheduleNtkReaderSurfacePrepare(selected, reason);
    }

    private boolean startNtkSlugManifestPrefetch(Manga target, String reason) {
        if(target == null)
            return false;
        String path = target.getNtkEpisodePath();
        if(!isNtkWebtoonSlugEpisodePath(path))
            return false;
        if(!isNtkUserDemandAuthorized(path))
            return false;
        boolean hardPriority = reason != null
                && (reason.contains("press") || reason.contains("open"));
        boolean visiblePriority = isNtkKpWebtoonSlugEpisodePath(path)
                && reason != null
                && (reason.contains("visible")
                || reason.contains("resume")
                || reason.contains("likely"));
        if(!hardPriority && !visiblePriority)
            return false;
        if(activeNtkSlugManifestPrefetchPaths.contains(path)) {
            Log.d("EpisodeActivity", "ntk_slug_manifest_prefetch_priority_join reason="
                    + reason + ",path=" + path);
            return false;
        }
        if(hardPriority) {
            activeNtkSlugManifestPrefetchPaths.clear();
            activeNtkSlugManifestPrefetchStartedAt.clear();
        }
        if(!activeNtkSlugManifestPrefetchPaths.add(path))
            return false;
        activeNtkSlugManifestPrefetchStartedAt.put(path, android.os.SystemClock.elapsedRealtime());
        try {
            if(isNtkKpWebtoonSlugEpisodePath(path)) {
                Log.d("EpisodeActivity", "ntk_slug_manifest_prefetch_kp_native_only reason="
                        + reason + ",path=" + path);
            }
            target.startNtkEarlyViewerApiPrefetch(getHttpClient());
            Log.d("EpisodeActivity", "ntk_slug_manifest_prefetch_start reason="
                    + reason + ",path=" + path);
            if(episodeList != null) {
                episodeList.postDelayed(() -> {
                    activeNtkSlugManifestPrefetchPaths.remove(path);
                    activeNtkSlugManifestPrefetchStartedAt.remove(path);
                }, 10_000L);
            }
            return true;
        } catch(Exception e) {
            Log.d("EpisodeActivity", "ntk_slug_manifest_prefetch_skip_reader_owned reason="
                    + reason + ",path=" + path + "," + e);
            activeNtkSlugManifestPrefetchPaths.remove(path);
            activeNtkSlugManifestPrefetchStartedAt.remove(path);
            return false;
        }
    }

    private void pruneVisibleNtkSlugManifestPrefetches(long maxAgeMs) {
        long now = android.os.SystemClock.elapsedRealtime();
        for(String activePath : new ArrayList<>(activeNtkSlugManifestPrefetchPaths)) {
            Long startedAt = activeNtkSlugManifestPrefetchStartedAt.get(activePath);
            if(startedAt == null || now - startedAt > maxAgeMs) {
                activeNtkSlugManifestPrefetchPaths.remove(activePath);
                activeNtkSlugManifestPrefetchStartedAt.remove(activePath);
                Log.d("EpisodeActivity", "ntk_slug_manifest_prefetch_prune_visible path="
                        + activePath + ",ageMs=" + (startedAt == null ? -1 : now - startedAt));
            }
        }
    }

    private void scheduleNtkReaderSurfacePrepare(Manga target, String reason) {
        if(!isUiAlive() || target == null || title == null || !isNtkTitle())
            return;
        if(!isNtkUserDemandAuthorized(target))
            return;
        AppDispatchers.submitNtkViewerCritical(() -> prepareNtkReaderSurface(target, reason));
    }

    private void publishPreparedNtkManifest(Manga target, String path, java.util.List<String> urls,
                                            String reason, long startedAt) {
        if(target == null || path == null || urls == null || urls.isEmpty())
            return;
        if(!isNtkUserDemandAuthorized(path))
            return;
        ArrayList<String> compacted = compactNtkGeneratedPreparedUrls(urls);
        if(compacted.isEmpty())
            return;
        int trustedCount = ReaderImageCache.INSTANCE.trustedNtkImageApiCount(
                path,
                Math.max(0L, startedAt - 1000L));
        int count = trustedCount > 0 && compacted.size() >= trustedCount
                ? trustedCount
                : compacted.size();
        ArrayList<String> finalUrls = new ArrayList<>(compacted.subList(0, Math.min(count, compacted.size())));
        target.setNtkImageCount(finalUrls.size());
        ReaderImageCache.rememberAuthoritativeNtkImageUrlsFromBrowser(
                path,
                finalUrls,
                "episode-visible-prepared-" + reason,
                readerImageCacheProducerGeneration);
        ReaderImageCache.rememberEarlyNtkImageUrls(
                path, finalUrls, readerImageCacheProducerGeneration);
        ml.melun.mangaview.activity.NtkBrowserSessionBroker.INSTANCE.primeImageUrls(
                path,
                finalUrls,
                "episode-visible-prepared-" + reason);
        startNtkDirectManifestInitialSurfaceFetches(
                target,
                finalUrls,
                true,
                "episode_prepared_" + reason);
        Log.d("EpisodeActivity", "ntk_reader_surface_prepare_manifest_ready path="
                + path + ",count=" + finalUrls.size()
                + ",raw=" + urls.size()
                + ",trusted=" + trustedCount
                + ",reason=" + reason
                + ",ms=" + (android.os.SystemClock.elapsedRealtime() - startedAt));
    }

    private static String ntkPreparedCanonicalImageWorkId(String body, String path, String currentWorkId) {
        if(body == null || path == null)
            return "";
        java.util.regex.Matcher pathMatcher = java.util.regex.Pattern
                .compile("^/webtoon/(\\d{1,12})/\\d{1,12}(?:[/?#].*)?$",
                        java.util.regex.Pattern.CASE_INSENSITIVE)
                .matcher(path.trim());
        if(!pathMatcher.find())
            return "";
        String pathWorkId = pathMatcher.group(1);
        String current = currentWorkId == null ? "" : currentWorkId.trim();
        if(current.length() > 0 && !current.equals(pathWorkId))
            return "";
        String normalized = body.replace("\\/", "/")
                .replace("\\u002F", "/")
                .replace("\\u002f", "/");
        String[] patterns = new String[]{
                "\"refId\"\\s*:\\s*\"?(\\d{1,12})\"?",
                "\"sourceWorkId\"\\s*:\\s*\"?(\\d{1,12})\"?",
                "\\\\\"refId\\\\\"\\s*:\\s*\\\\\"?(\\d{1,12})\\\\\"?",
                "\\\\\"sourceWorkId\\\\\"\\s*:\\s*\\\\\"?(\\d{1,12})\\\\\"?",
                "/(?:blacktoon/)?thumbs/(\\d{1,12})\\.(?:png|jpg|jpeg|webp)",
                "https?://(?:[^/]+\\.)?(?:g\\d+cm\\.net|scloud\\d+\\.com|vcloud\\d+\\.com|cloudfront\\.net)/(\\d{1,12})/[^\"'<>\\s]+\\.(?:png|jpg|jpeg|webp)"
        };
        for(String pattern : patterns) {
            java.util.regex.Matcher matcher = java.util.regex.Pattern
                    .compile(pattern, java.util.regex.Pattern.CASE_INSENSITIVE)
                    .matcher(normalized);
            while(matcher.find()) {
                String candidate = matcher.group(1);
                if(candidate != null && candidate.matches("\\d{1,12}") && !candidate.equals(pathWorkId))
                    return candidate;
            }
        }
        return "";
    }

    private static ArrayList<String> compactNtkGeneratedPreparedUrls(java.util.List<String> urls) {
        java.util.LinkedHashMap<String, String> byPage = new java.util.LinkedHashMap<>();
        java.util.regex.Pattern pagePattern = java.util.regex.Pattern.compile(
                "(?i)/(?:blacktoon|black|wt)/episodes/[^?#]+/p(\\d{3})\\.(?:jpg|jpeg|png|webp)");
        for(String url : urls) {
            if(url == null || url.length() == 0)
                continue;
            java.util.regex.Matcher matcher = pagePattern.matcher(url);
            String key = matcher.find() ? matcher.group(1) : url;
            if(!byPage.containsKey(key))
                byPage.put(key, url);
        }
        return new ArrayList<>(byPage.values());
    }

    private static String ntkViewerPayloadTokenForPrepare(String body) {
        if(body == null || body.length() == 0)
            return "";
        java.util.regex.Matcher matcher = java.util.regex.Pattern
                .compile("\"(?:imagesToken|token)\"\\s*:\\s*\"([^\"]+)\"")
                .matcher(body);
        if(matcher.find())
            return matcher.group(1);
        matcher = java.util.regex.Pattern
                .compile("\\\\\"(?:imagesToken|token)\\\\\"\\s*:\\s*\\\\\"([^\\\\\"]+)\\\\\"")
                .matcher(body);
        return matcher.find() ? matcher.group(1) : "";
    }

    private void prepareNtkReaderSurface(Manga target, String reason) {
        if(!isUiAlive() || target == null || title == null)
            return;
        target.setMode(mode);
        target.setTitle(title);
        target.setTitleId(title.getId());
        target.ensureNtkEpisodePathFromIdentity();
        String path = target.getNtkEpisodePath();
        if(path == null || (!path.startsWith("/webtoon/") && !path.startsWith("/manhwa/")))
            return;
        if(!isNtkUserDemandAuthorized(path)) {
            Log.d("EpisodeActivity", "ntk_reader_surface_prepare_skip reason=no_user_demand,path="
                    + path + ",source=" + reason);
            return;
        }
        if(ml.melun.mangaview.MainApplication.isNtkForegroundViewerPathActive())
            return;
        if(!preparedNtkReaderSurfacePaths.add(path))
            return;
        if(isModernProtectedNumericNtkEpisodePath(path)) {
            try {
                target.startNtkEarlyViewerApiPrefetch(getHttpClient());
                String key = NtkInlineReaderController.strictPreparedKey(path);
                if(ntkInlineReaderController != null && key.length() > 0) {
                    runOnUiThread(() -> {
                        if(!isUiAlive() || ntkInlineReaderController == null)
                            return;
                        lastEagerNtkReaderPath = path;
                        lastEagerNtkReaderKey = key;
                        NtkInlineReaderController.StageResult result =
                                ntkInlineReaderController.planStrictEpisode(
                                        target, title, key, 0, 0);
                        Log.d("EpisodeActivity",
                                "ntk_reader_surface_strict_plan path=" + path
                                        + ",result=" + result
                                        + ",reason=" + reason);
                    });
                }
            } catch(Throwable t) {
                preparedNtkReaderSurfacePaths.remove(path);
                Log.d("EpisodeActivity", "ntk_reader_surface_api_manifest_prefetch_error path="
                        + path + ",reason=" + reason + "," + t);
                return;
            }
            Log.d("EpisodeActivity", "ntk_reader_surface_api_manifest_only path="
                    + path + ",reason=" + reason);
            return;
        }
        int expected = Math.max(0, target.getNtkImageCount());
        if(expected <= 0)
            expected = ntkViewerPayloadHintImageCount(target, path);
        if(expected <= 0) {
            try {
                target.startNtkEarlyViewerApiPrefetch(getHttpClient());
            } catch(Exception ignored) {
            }
            preparedNtkReaderSurfacePaths.remove(path);
            Log.d("EpisodeActivity", "ntk_reader_surface_prepare_skip_unknown_count path="
                    + path + ",reason=" + reason);
            return;
        }
        ArrayList<String> images;
        if(path.startsWith("/webtoon/")) {
            images = buildNtkWebtoonDirectManifest(target, path, expected);
        } else {
            images = buildNtkManhwaDirectManifest(path, expected);
        }
        if(images == null || images.size() == 0) {
            preparedNtkReaderSurfacePaths.remove(path);
            Log.d("EpisodeActivity", "ntk_reader_surface_prepare_skip_no_manifest path="
                    + path + ",reason=" + reason + ",expected=" + expected);
            return;
        }
        ReaderImageCache.rememberEarlyNtkImageUrls(
                path, images, readerImageCacheProducerGeneration);
        ml.melun.mangaview.activity.NtkBrowserSessionBroker.INSTANCE.primeImageUrls(
                path,
                images,
                "episode-hidden-" + reason);
        startNtkDirectManifestInitialSurfaceFetches(target, images, true, "episode_prepared_" + reason);
        Log.d("EpisodeActivity", "ntk_reader_surface_prepare_native_only path="
                + path + ",count=" + images.size() + ",reason=" + reason);
    }

    private void noteNtkForegroundViewer(Manga selected) {
        if(selected == null || !isNtkTitle())
            return;
        selected.setMode(mode);
        selected.setTitle(title);
        selected.setTitleId(title == null ? selected.getTitleId() : title.getId());
        selected.ensureNtkEpisodePathFromIdentity();
        String path = selected.getNtkEpisodePath();
        ml.melun.mangaview.MainApplication.rememberLastNtkViewerPath(
                path,
                selected.getNtkImageWorkId(),
                selected.getNtkImageEpisodeId());
        ml.melun.mangaview.MainApplication.noteNtkForegroundViewerPath(path);
        try {
            ml.melun.mangaview.mangaview.NtkWebViewFallbackManager.quietForForegroundNativeReader(
                    getApplicationContext(), path, "episode_open_preflight");
        } catch(Throwable ignored) {
        }
        if(ViewerTelemetry.hasActiveSession() && ViewerTelemetry.isActiveEpisode(path)) {
            // A committed cold click must create the rolling source owner before any legacy
            // preflight can reserve a non-rolling discovery entry. This starts only metadata/ACK
            // work; image body admission remains source 0/1 until a physical draw is committed.
            boolean started = ml.melun.mangaview.reader.NtkStrictEpisodeDiscoveryCoordinator
                    .startColdRolling(getHttpClient(), selected);
            Log.d("EpisodeActivity", "ntk_foreground_viewer_cold_rolling_start path="
                    + path + ",started=" + started);
            return;
        }
        if(isNtkWebtoonSlugEpisodePath(path)) {
            boolean kpHybridOwner = isNtkKpWebtoonSlugEpisodePath(path);
            Log.d("EpisodeActivity", "ntk_foreground_viewer_slug_browser_owner path="
                    + path + ",kpHybrid=" + kpHybridOwner);
            try {
                if(kpHybridOwner) {
                    Log.d("EpisodeActivity", "ntk_foreground_viewer_kp_native_only path="
                            + path + ",reason=avoid_hidden_webview_on_open");
                    selected.startNtkKpAckReadyViewerPayloadPrefetch(
                            getHttpClient(),
                            path,
                            "episode-open");
                } else {
                    selected.startNtkEarlyViewerApiPrefetch(getHttpClient());
                }
                Log.d("EpisodeActivity", "ntk_foreground_viewer_preflight_start path=" + path
                        + ",kpHybrid=" + kpHybridOwner);
            } catch(Throwable t) {
                Log.d("EpisodeActivity", "ntk_foreground_viewer_preflight_error path=" + path + "," + t);
            }
            if(!kpHybridOwner) {
                ml.melun.mangaview.mangaview.NtkWebViewFallbackManager.quietForForegroundNativeReader(
                        getApplicationContext(), path, "episode_open");
                try {
                    NtkBrowserSessionBroker.INSTANCE.quietAllForNativeReader("episode_open:" + path);
                    NtkBrowserSessionBroker.INSTANCE.quietForNativeReader(path, "episode_open");
                } catch(Throwable ignored) {
                }
            }
            return;
        }
        try {
            selected.startNtkEarlyViewerApiPrefetch(getHttpClient());
            Log.d("EpisodeActivity", "ntk_foreground_viewer_preflight_start path=" + path);
        } catch(Throwable t) {
            Log.d("EpisodeActivity", "ntk_foreground_viewer_preflight_error path=" + path + "," + t);
        }
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

    static int initialVisibleEagerEpisodeIndexForTest(int episodeCount, int bookmarkIndex) {
        if(episodeCount <= 0)
            return -1;
        return bookmarkIndex > 0 && bookmarkIndex <= episodeCount ? bookmarkIndex - 1 : 0;
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
                episodeAdapter = createEpisodeAdapter(episodes);
                afterLoad();
                hideProgress();
                showConfirmedEmptyEpisodeState(true);
                loaded = true;
                ntkCaptchaRetryAfterVerifiedAttempted = false;
                if(fab_container != null)
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
        if(hasRenderedEpisodes()) {
            ntkLoadTimeoutHandled = true;
            episodeAdapter.replaceData(loadedEpisodes);
            episodes = loadedEpisodes;
            attachLoadedEpisodesToTitle(episodes);
            saveEpisodeCache(episodes);
            hideProgress();
            loaded = true;
            ntkCaptchaRetryAfterVerifiedAttempted = false;
            invalidateOptionsMenu();
            return;
        }
        episodes = loadedEpisodes;
        ntkLoadTimeoutHandled = true;
        attachLoadedEpisodesToTitle(episodes);
        saveEpisodeCache(episodes);
        warmupInitialViewerTargets();
        episodeAdapter = createEpisodeAdapter(episodes);
        afterLoad();
        hideProgress();
        loaded = true;
        ntkCaptchaRetryAfterVerifiedAttempted = false;
        if(fab_container != null)
            fab_container.setVisibility(View.GONE);
        invalidateOptionsMenu();
    }

    private void showConfirmedEmptyEpisodeState(boolean show) {
        if(show && episodeEmptyState == null && episodeEmptyStub != null) {
            episodeEmptyState = episodeEmptyStub.inflate();
            episodeEmptyStub = null;
            episodeEmptyTitle = episodeEmptyState.findViewById(R.id.episode_empty_title);
            episodeEmptyMessage = episodeEmptyState.findViewById(R.id.episode_empty_message);
        }
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
            // A slow but progressing demand request is not challenge evidence. The old watchdog
            // started a numbered-domain scan after three seconds even when the requested title
            // document had succeeded, opening unrelated DNS/TLS sockets before the viewer click.
            if(!getHttpClient().hasRecentCloudflareChallenge()
                    && !getHttpClient().hasRecentNtkHardBlock()) {
                Log.d("EpisodeActivity",
                        "NTK episode watchdog kept demand load active: no observed challenge");
                return;
            }
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
        if(p != null && p.isNtkSite()
                && !getHttpClient().hasRecentCloudflareChallenge()
                && !getHttpClient().hasRecentNtkHardBlock()) {
            Log.d("EpisodeActivity",
                    "skip direct NTK captcha launch: no response-observed challenge");
            handleLoadErrorWithCacheFallback();
            return;
        }
        Log.d("EpisodeActivity", "opening direct NTK captcha proof="
                + getHttpClient().hasNtkAccessProof()
                + " recent=" + getHttpClient().hasRecentNtkAccessVerification()
                + " titleUrl=" + (title == null ? null : title.getUrl()));
        ntkCaptchaLaunchInFlight = true;
        try {
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
        } finally {
            ntkCaptchaLaunchInFlight = false;
        }
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
        startProvidedResumeNtkPayloadPrefetch(intentEpisodes, "provided-intent");
        episodes = intentEpisodes;
        attachLoadedEpisodesToTitle(episodes);
        if(launchResumeNtkViewerFromProvidedEpisodes(episodes))
            return true;
        showConfirmedEmptyEpisodeState(false);
        if(!isNtkTitle())
            warmupInitialViewerTargets();
        episodeAdapter = createEpisodeAdapter(episodes);
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

    private boolean launchProtectedNumericManhwaResumeBeforeContent() {
        // Progressive inline activation requires the already attached Episode
        // ViewRoot and a physical ACTION_DOWN token. Pre-content auto-launch has
        // neither, so it must never bypass the visible episode list.
        return false;
    }

    private void startResumeNtkDiscoveryBeforeContent() {
        if(!online || title == null || !isNtkTitle())
            return;
        String resumePath = title.getResumeNtkEpisodePath();
        if(!isNtkUserDemandAuthorized(resumePath)) {
            Log.d("EpisodeActivity", "ntk_resume_discovery_skip reason=no_user_click,path="
                    + resumePath);
            return;
        }
        List<Manga> providedEpisodes = title.getEps();
        if(resumePath == null || providedEpisodes == null || providedEpisodes.isEmpty()
                || (!resumePath.startsWith("/manhwa/")
                && !resumePath.startsWith("/webtoon/")))
            return;
        CustomHttpClient client = getHttpClient();
        if(client == null || !client.isNtk())
            return;
        for(Manga episode : providedEpisodes) {
            if(episode == null)
                continue;
            episode.setMode(mode);
            episode.setTitle(title);
            episode.setTitleId(title.getId());
            episode.ensureNtkEpisodePathFromIdentity();
            if(!resumePath.equals(episode.getNtkEpisodePath()))
                continue;
            try {
                // Start StrictFresh discovery while ActivityThread still owns the launch
                // message. Its ACK sidecar can then enter the main queue before the first
                // full-screen SurfaceView/HWUI traversal instead of waiting behind it.
                episode.startNtkEarlyViewerApiPrefetch(client);
                Log.d("EpisodeActivity",
                        "ntk_resume_discovery_before_content path=" + resumePath);
            } catch(Throwable t) {
                Log.d("EpisodeActivity",
                        "ntk_resume_discovery_before_content_error path="
                                + resumePath + "," + t);
            }
            return;
        }
    }

    private void startProvidedResumeNtkPayloadPrefetch(List<Manga> providedEpisodes, String reason) {
        if(!online || title == null || providedEpisodes == null || providedEpisodes.size() == 0)
            return;
        if(!isNtkTitle())
            return;
        String resumePath = title.getResumeNtkEpisodePath();
        if(resumePath == null || resumePath.length() == 0)
            return;
        if(!isNtkUserDemandAuthorized(resumePath)) {
            Log.d("EpisodeActivity", "ntk_resume_payload_prefetch_skip reason=no_user_click,path="
                    + resumePath + ",source=" + reason);
            return;
        }
        if(!resumePath.startsWith("/webtoon/") && !resumePath.startsWith("/manhwa/"))
            return;
        CustomHttpClient client = getHttpClient();
        if(client == null || !client.isNtk())
            return;
        for(Manga episode : providedEpisodes) {
            if(episode == null)
                continue;
            episode.setMode(mode);
            episode.setTitle(title);
            episode.setTitleId(title.getId());
            episode.ensureNtkEpisodePathFromIdentity();
            if(!resumePath.equals(episode.getNtkEpisodePath()))
                continue;
            try {
                ml.melun.mangaview.MainApplication.rememberLastNtkViewerPath(
                        resumePath,
                        episode.getNtkImageWorkId(),
                        episode.getNtkImageEpisodeId());
                episode.startNtkEarlyViewerApiPrefetch(client);
                ml.melun.mangaview.Utils.primeNtkGeneratedPreparedHead(
                        getApplicationContext(), episode, title, reason);
                Log.d("EpisodeActivity", "ntk_resume_payload_prefetch_from_provided path="
                        + resumePath + ",reason=" + reason);
            } catch(Throwable t) {
                Log.d("EpisodeActivity", "ntk_resume_payload_prefetch_from_provided_error path="
                        + resumePath + ",reason=" + reason + "," + t);
            }
            return;
        }
    }

    private boolean launchResumeNtkViewerFromProvidedEpisodes(List<Manga> providedEpisodes) {
        // A restored resume path is useful for choosing the warmup target and
        // selected row, but is not a substitute for a new physical press token.
        // Always render the Episode list; never auto-reveal when binding finishes.
        return false;
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
        episodeAdapter = createEpisodeAdapter(episodes);
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
        if(isNtkTitle())
            ntkViewerSelectionInProgress = true;
        // The episode list is no longer the foreground destination.  Do not let
        // a speculative refresh parse and publish a large list on the UI thread
        // while the reader Activity is being created.
        cancelEpisodeRefresh();
        if(episodeViewModel != null)
            episodeViewModel.cancelActiveLoad();
        manga.setMode(mode);
        manga.setTitle(title);
        manga.setTitleId(title == null ? manga.getTitleId() : title.getId());
        manga.ensureNtkEpisodePathFromIdentity();
        String path = manga.getNtkEpisodePath();
        if(isExactOnlineNtkEpisode(manga)) {
            // Every online NTK reader launch requires the ACTION_DOWN payload consumed by
            // enterPressedNtkEpisode(). Header/resume/programmatic calls have no such authority
            // and must stay on the list instead of opening a second Activity later.
            rollbackInlineSelection("missing_physical_press_payload", manga);
            return;
        }
        if(isNtkTitle() && online && path != null
                && (path.startsWith("/manhwa/") || path.startsWith("/webtoon/"))) {
            if(hasWindowReadyInlineCandidate(path)) {
                Log.d("ViewerPerf", "ntk_inline_reader_prearm path=" + path
                        + ",key=true");
                launchViewerNow(manga, code, exactEpisode);
                return;
            }
            armBrowserOwnedViewerLaunch(manga, code, exactEpisode);
            return;
        }
        launchViewerNow(manga, code, exactEpisode);
    }

    private boolean hasWindowReadyInlineCandidate(String path) {
        if(path == null || path.length() == 0 || ntkInlineReaderController == null
                || lastEagerNtkReaderKey == null || lastEagerNtkReaderKey.length() == 0
                || !path.equals(lastEagerNtkReaderPath))
            return false;
        if(ntkInlineReaderController.isStaged()
                && path.equals(ntkInlineReaderController.getStagedPath())
                && lastEagerNtkReaderKey.equals(ntkInlineReaderController.getStagedKey()))
            return true;
        ReaderPreparedStore.Entry entry = ReaderPreparedStore.get(lastEagerNtkReaderKey);
        return entry != null
                && entry.snapshot().getStatus() == ReaderPreparedStore.Status.WINDOW_READY;
    }

    private void launchViewerNow(Manga manga, int code, boolean exactEpisode) {
        if(manga == null || title == null)
            return;
        if(isExactOnlineNtkEpisode(manga)) {
            rollbackInlineSelection("exact_inline_no_activity_fallback", manga);
            return;
        }
        if(!ml.melun.mangaview.Utils.consumeFocusedDestinationLaunch(this, DESTINATION_LAUNCH_DEBOUNCE_MS)) {
            ntkViewerSelectionInProgress = ntkInlineReaderController != null
                    && ntkInlineReaderController.isActive();
            return;
        }
        pendingBrowserOwnedViewerLaunchPath = "";
        ntkLoadTimeoutHandled = true;
        cancelNtkEpisodeLoadWatchdog();
        if(exactEpisode && !isNtkTitle())
            ReaderWarmupCoordinator.primeExactImmediate(context, manga, title);
        if(!exactEpisode && getHttpClient().isNtk())
            Log.d("EpisodeActivity", "ntk_launch_skip_viewer_warmup path=" + manga.getNtkEpisodePath());
        // Non-exact/other-source launches retain their existing Activity route.
        // The eager key is intentionally not consumed here: it is a scheduling
        // hint and only an atomic physical-press payload may authorize inline.
        if(isNtkTitle() && online)
            noteNtkForegroundViewer(manga);
        openViewerPrepared(context, manga, code, false, online, true, title,
                !manga.isOnline(), exactEpisode);
    }

    private void armBrowserOwnedViewerLaunch(Manga manga, int code, boolean exactEpisode) {
        if(manga == null || title == null)
            return;
        String path = manga.getNtkEpisodePath();
        if(path == null || path.length() == 0)
            return;
        if(path.startsWith("/manhwa/") && isModernProtectedNumericNtkEpisodePath(path)) {
            long minCreatedAt = android.os.SystemClock.elapsedRealtime() - 30_000L;
            int trustedCount = ReaderImageCache.INSTANCE.trustedNtkImageApiCount(path, minCreatedAt);
            List<String> readyUrls = ReaderImageCache.INSTANCE.earlyNtkImageUrls(path, minCreatedAt);
            boolean manifestReady = trustedCount > 0 && readyUrls != null
                    && readyUrls.size() >= trustedCount;
            if(manifestReady) {
                manga.setNtkImageCount(trustedCount);
            } else {
                try {
                    manga.startNtkEarlyViewerApiPrefetch(getHttpClient());
                } catch(Throwable t) {
                    Log.d("EpisodeActivity", "ntk_protected_numeric_manhwa_api_prefetch_error path="
                            + path + "," + t);
                }
            }
            Log.d("EpisodeActivity", "ntk_protected_numeric_manhwa_api_first_launch path="
                    + path + ",manifestReady=" + manifestReady + ",trusted=" + trustedCount);
            launchViewerNow(manga, code, exactEpisode);
            return;
        }
        if(isNtkWebtoonSlugEpisodePath(path)) {
            int trustedCount = ReaderImageCache.INSTANCE.trustedNtkImageApiCount(
                    path,
                    android.os.SystemClock.elapsedRealtime() - 30_000L);
            List<String> readyUrls = ReaderImageCache.INSTANCE.earlyNtkImageUrls(
                    path,
                    android.os.SystemClock.elapsedRealtime() - 30_000L);
            if(trustedCount > 0 && readyUrls != null && readyUrls.size() >= trustedCount) {
                ArrayList<String> launchImages = new ArrayList<>(
                        readyUrls.subList(0, Math.min(trustedCount, readyUrls.size())));
                manga.setNtkImageCount(launchImages.size());
                Log.d("EpisodeActivity", "ntk_kp_slug_ready_manifest_launch path="
                        + path + ",count=" + launchImages.size());
                prepareBrowserDirectManifestAndLaunch(
                        manga,
                        code,
                        exactEpisode,
                        path,
                        launchImages,
                        "episode-kp-slug-trusted-ready");
                return;
            }
            if(isNtkKpWebtoonSlugEpisodePath(path)) {
                Log.d("EpisodeActivity", "ntk_kp_slug_native_launch_no_browser_wait path="
                        + path + ",trusted=" + trustedCount
                        + ",ready=" + (readyUrls == null ? 0 : readyUrls.size()));
                launchViewerNow(manga, code, exactEpisode);
                return;
            }
        }
        int expected = Math.max(0, manga.getNtkImageCount());
        if(expected <= 0)
            expected = ntkViewerPayloadHintImageCount(manga, path);
        ArrayList<String> canonicalImages = buildNtkManhwaDirectManifest(path, expected);
        if(canonicalImages != null && canonicalImages.size() > 0) {
            Log.d("EpisodeActivity", "ntk_browser_owned_canonical_direct_manifest path="
                    + path + ",count=" + canonicalImages.size());
            prepareCanonicalDirectManifestAndLaunch(manga, code, exactEpisode, path, canonicalImages);
            return;
        }
        if(path.startsWith("/webtoon/")) {
            boolean unverifiedWebtoonCanonicalPathId =
                    isUnverifiedNtkWebtoonCanonicalPathId(manga, path);
            boolean validatedWebtoonDirect = hasAcceptedNtkWebtoonDirectManifest(path);
            ArrayList<String> webtoonImages = validatedWebtoonDirect
                    ? new ArrayList<>(acceptedCanonicalDirectImages.get(path))
                    : null;
            if(!validatedWebtoonDirect) {
                Log.d("EpisodeActivity", "ntk_webtoon_canonical_direct_manifest_skip_unvalidated path="
                        + path + ",expected=" + expected
                        + ",unverifiedPathId=" + unverifiedWebtoonCanonicalPathId);
            }
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
            if(expected <= 0 || unverifiedWebtoonCanonicalPathId) {
                pendingBrowserOwnedViewerLaunchPath = path;
                prepareUnknownNtkWebtoonDirectManifestAndLaunch(manga, code, exactEpisode, path);
                return;
            }
            pendingBrowserOwnedViewerLaunchPath = path;
            prepareUnknownNtkWebtoonDirectManifestAndLaunch(manga, code, exactEpisode, path);
            return;
        }
        if(expected <= 0 && shouldPreferCanonicalDirectManifest(path)) {
            prepareUnknownCanonicalDirectManifestAndLaunch(manga, code, exactEpisode, path);
            return;
        }
        Log.d("EpisodeActivity", "ntk_native_launch_no_browser_wait path=" + path
                + ",expected=" + expected);
        launchViewerNow(manga, code, exactEpisode);
    }

    private void prepareProtectedBrowserManifestAndLaunch(Manga manga, int code, boolean exactEpisode,
                                                          String path, ArrayList<String> images) {
        if(manga == null || title == null || path == null || images == null || images.size() == 0)
            return;
        final Manga launchManga = manga;
        final Title launchTitle = title;
        final ArrayList<String> launchImages = new ArrayList<>(images);
        final int width = ReaderWindowViewport.width(this, episodeList);
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
        final ArrayList<String> launchImages = preferCanonicalWebtoonJpgManifest(path, images);
        launchManga.setNtkImageCount(launchImages.size());
        acceptedCanonicalDirectPaths.add(path);
        acceptedCanonicalDirectImages.put(path, new ArrayList<>(launchImages));
        ReaderImageCache.rememberAuthoritativeNtkImageUrlsFromBrowser(
                path, launchImages, source, readerImageCacheProducerGeneration);
        Log.d("EpisodeActivity", "ntk_browser_owned_direct_launch_immediate path="
                + path + ",count=" + launchImages.size() + ",source=" + source);
        launchViewerNow(launchManga, code, exactEpisode);
        Log.d("EpisodeActivity", "ntk_browser_owned_postlaunch_fetch_skip path="
                + path + ",reason=reader_owns_visible_fetch_after_launch");
    }

    private int ntkDirectManifestStartIndex(Manga manga, ArrayList<String> images, boolean exactEpisode) {
        if(manga == null || images == null || images.size() == 0)
            return -1;
        int startPage = ReaderWarmupCoordinator.requestedLaunchStartPage(manga, exactEpisode);
        try {
            if(!exactEpisode && p != null && manga.useBookmark())
                startPage = Math.max(startPage, p.getViewerBookmark(manga));
        } catch(Throwable ignored) {
        }
        return Math.max(0, Math.min(images.size() - 1, startPage));
    }

    private void startNtkDirectManifestInitialSurfaceFetches(Manga manga, ArrayList<String> images,
                                                             boolean exactEpisode, String source) {
        if(manga == null || images == null || images.size() == 0)
            return;
        if(!isNtkUserDemandAuthorized(manga))
            return;
        int start = ntkDirectManifestStartIndex(manga, images, exactEpisode);
        if(start < 0)
            return;
        ArrayList<String> latest = authoritativeNtkDirectImages(manga, images);
        int last = Math.min(latest.size() - 1, start + 7);
        for(int page = start; page <= last; page++) {
            String image = latest.get(page);
            if(image == null || image.length() == 0)
                continue;
            startNtkDirectManifestForegroundFetch(
                    manga,
                    image,
                    page,
                    source + "_p" + (page + 1));
        }
    }

    private void startNtkDirectManifestAnchorFetchOnly(Manga manga, ArrayList<String> images,
                                                       boolean exactEpisode, String source) {
        if(manga == null || images == null || images.size() == 0)
            return;
        if(!isNtkUserDemandAuthorized(manga))
            return;
        int start = ntkDirectManifestStartIndex(manga, images, exactEpisode);
        if(start < 0)
            return;
        ArrayList<String> latest = authoritativeNtkDirectImages(manga, images);
        String image = latest.get(start);
        if(image == null || image.length() == 0)
            return;
        startNtkDirectManifestForegroundFetch(manga, image, start, source, true);
        startNtkDirectManifestAnchorExtensionHedge(manga, image, start, source);
    }

    private void startNtkDirectManifestInitialSurfaceTailAfterLaunch(Manga manga, ArrayList<String> images,
                                                                     boolean exactEpisode, String source) {
        if(manga == null || images == null || images.size() == 0)
            return;
        int start = ntkDirectManifestStartIndex(manga, images, exactEpisode);
        if(start < 0)
            return;
        int first = start;
        AppDispatchers.runIo(() -> {
            startNtkDirectManifestSurfaceCacheOnlyFetch(
                    manga,
                    images,
                    first,
                    images.size() - 1,
                    source + "_full_surface_forward");
            if(first > 0) {
                startNtkDirectManifestSurfaceCacheOnlyFetch(
                        manga,
                        images,
                        0,
                        first - 1,
                        source + "_full_surface_before");
            }
        });
    }

    private void startNtkDirectManifestFirstImageFetch(Manga manga, ArrayList<String> images, boolean exactEpisode) {
        if(manga == null || images == null || images.size() == 0)
            return;
        if(!isNtkUserDemandAuthorized(manga))
            return;
        int index = ntkDirectManifestStartIndex(manga, images, exactEpisode);
        if(index < 0)
            return;
        String lowerPath = manga.getNtkEpisodePath() == null
                ? ""
                : manga.getNtkEpisodePath().toLowerCase(java.util.Locale.ROOT);
        int immediateAhead = NTK_DIRECT_INITIAL_FOREGROUND_VISIBLE_AHEAD;
        int totalAhead = lowerPath.startsWith("/webtoon/") ? 8 : 7;
        int immediateLast = Math.min(images.size() - 1, index + immediateAhead);
        int last = Math.min(images.size() - 1, index + totalAhead);
        String anchorImage = images.get(index);
        if(anchorImage == null || anchorImage.length() == 0)
            return;
        startNtkDirectManifestForegroundFetch(manga, anchorImage, index, "primary_anchor", true);
        if(last <= index)
            return;
        startNtkDirectManifestTailFetch(manga, images, index + 1, last, immediateLast);
    }

    private void startNtkDirectManifestTailAfterAnchor(Manga manga, ArrayList<String> images,
                                                       boolean exactEpisode) {
        if(manga == null || images == null || images.size() == 0)
            return;
        int index = ntkDirectManifestStartIndex(manga, images, exactEpisode);
        if(index < 0)
            return;
        String lowerPath = manga.getNtkEpisodePath() == null
                ? ""
                : manga.getNtkEpisodePath().toLowerCase(java.util.Locale.ROOT);
        int immediateAhead = lowerPath.startsWith("/webtoon/") ? 8 : 7;
        int immediateLast = Math.min(images.size() - 1, index + immediateAhead);
        if(immediateLast <= index)
            return;
        int surfaceLast = Math.min(images.size() - 1, index + 3);
        AppDispatchers.runIoDelayed(
                () -> startNtkDirectManifestTailFetch(manga, images, surfaceLast + 1, immediateLast, surfaceLast),
                NTK_DIRECT_INITIAL_NON_VISIBLE_DELAY_MS);
    }

    private void runNtkDirectManifestAfterAnchorReady(Manga manga, ArrayList<String> images,
                                                      int start, Runnable runnable, String source) {
        if(manga == null || images == null || images.size() == 0 || runnable == null)
            return;
        int boundedStart = Math.max(0, Math.min(images.size() - 1, start));
        String anchor = images.get(boundedStart);
        if(anchor == null || anchor.length() == 0)
            return;
        try {
            boolean registered = ReaderImageCache.INSTANCE.runWhenNtkAnchorAssetReady(
                    context,
                    manga,
                    anchor,
                    runnable);
            Log.d("EpisodeActivity", "ntk_direct_manifest_after_anchor_ready path="
                    + manga.getNtkEpisodePath() + ",registered=" + registered
                    + ",source=" + source);
            if(!registered)
                runnable.run();
        } catch(Throwable t) {
            Log.d("EpisodeActivity", "ntk_direct_manifest_after_anchor_ready_error path="
                    + manga.getNtkEpisodePath() + ",source=" + source + "," + t);
        }
    }

    private void runNtkDirectManifestAfterInitialSurfaceReady(Manga manga, ArrayList<String> images,
                                                              int first, int last,
                                                              Runnable runnable, String source) {
        if(manga == null || images == null || images.size() == 0 || runnable == null)
            return;
        int boundedFirst = Math.max(0, Math.min(images.size() - 1, first));
        int boundedLast = Math.max(boundedFirst, Math.min(images.size() - 1, last));
        ArrayList<Integer> pages = new ArrayList<>();
        for(int page = boundedFirst; page <= boundedLast; page++) {
            String image = images.get(page);
            if(image == null || image.length() == 0)
                continue;
            pages.add(page);
        }
        if(pages.size() == 0) {
            runnable.run();
            return;
        }
        AtomicInteger pending = new AtomicInteger(pages.size());
        Runnable onceReady = () -> {
            if(pending.decrementAndGet() == 0) {
                Log.d("EpisodeActivity", "ntk_direct_manifest_surface_ready path="
                        + manga.getNtkEpisodePath() + ",source=" + source
                        + ",first=" + boundedFirst + ",last=" + boundedLast);
                runnable.run();
            }
        };
        try {
            for(Integer boxedPage : pages) {
                int page = boxedPage;
                String image = images.get(page);
                boolean registered = ReaderImageCache.INSTANCE.runWhenNtkInitialGeneratedAssetReady(
                        context,
                        manga,
                        image,
                        onceReady);
                Log.d("EpisodeActivity", "ntk_direct_manifest_after_surface_ready path="
                        + manga.getNtkEpisodePath() + ",registered=" + registered
                        + ",source=" + source + ",page=" + (page + 1));
                if(!registered)
                    onceReady.run();
            }
            if(pending.get() == 0)
                runnable.run();
        } catch(Throwable t) {
            Log.d("EpisodeActivity", "ntk_direct_manifest_after_surface_ready_error path="
                    + manga.getNtkEpisodePath() + ",source=" + source + "," + t);
            runnable.run();
        }
    }

    private void startNtkDirectManifestTailFetch(Manga manga, ArrayList<String> images,
                                                 int firstPage, int lastPage, int immediateLast) {
        if(manga == null || images == null || images.size() == 0)
            return;
        if(firstPage > lastPage)
            return;
        int boundedFirst = Math.max(0, Math.min(images.size() - 1, firstPage));
        int boundedLast = Math.max(boundedFirst, Math.min(images.size() - 1, lastPage));
        for(int page = boundedFirst; page <= boundedLast; page++) {
            ArrayList<String> latest = authoritativeNtkDirectImages(manga, images);
            String image = latest.get(page);
            if(image == null || image.length() == 0)
                continue;
            final int currentPage = page;
            final String currentImage = image;
            Runnable fetch = () -> startNtkDirectManifestForegroundFetch(
                    manga,
                    currentImage,
                    currentPage,
                    "anchor_ready_tail",
                    true);
            long delayMs = currentPage <= immediateLast
                    ? 0L
                    : NTK_DIRECT_INITIAL_NON_VISIBLE_DELAY_MS
                    + (long) (currentPage - immediateLast - 1) * 180L;
            if(delayMs <= 0L)
                fetch.run();
            else
                AppDispatchers.runIoDelayed(fetch, delayMs);
        }
    }

    private boolean isNtkManhwaPath(String path) {
        return path != null && path.toLowerCase(java.util.Locale.ROOT).startsWith("/manhwa/");
    }

    private ArrayList<String> ntkInitialManhwaDirectVariants(String image) {
        ArrayList<String> variants = new ArrayList<>();
        if(image == null || image.length() == 0)
            return variants;
        String lower = image.toLowerCase(java.util.Locale.ROOT);
        String[] extensions = new String[]{"jpeg", "jpg", "png", "webp"};
        for(String extension : extensions) {
            String candidate = lower.matches(".*\\.([a-z0-9]+)(\\?.*)?$")
                    ? image.replaceFirst("(?i)\\.([a-z0-9]+)(\\?.*)?$", "." + extension + "$2")
                    : image;
            if(!variants.contains(candidate))
                variants.add(candidate);
        }
        return variants;
    }

    private void startNtkDirectManifestAnchorExtensionHedge(Manga manga, String image,
                                                            int page, String source) {
        if(manga == null || image == null || image.length() == 0)
            return;
        String path = manga.getNtkEpisodePath();
        if(!isNtkManhwaPath(path))
            return;
        String lower = image.toLowerCase(java.util.Locale.ROOT);
        String hedge;
        if(lower.matches(".*\\.jpeg(\\?.*)?$")) {
            hedge = image.replaceFirst("(?i)\\.jpeg(\\?.*)?$", ".jpg$1");
        } else if(lower.matches(".*\\.jpg(\\?.*)?$")) {
            hedge = image.replaceFirst("(?i)\\.jpg(\\?.*)?$", ".jpeg$1");
        } else {
            return;
        }
        if(hedge.equals(image))
            return;
        startNtkDirectManifestForegroundFetch(
                manga,
                hedge,
                page,
                source + "_anchor_extension_hedge",
                true);
    }

    private ArrayList<String> authoritativeNtkDirectImages(Manga manga, ArrayList<String> fallback) {
        try {
            List<String> latest = ReaderImageCache.INSTANCE.earlyNtkImageUrls(manga.getNtkEpisodePath(), 0L);
            if(latest != null && latest.size() >= fallback.size())
                return new ArrayList<>(latest);
        } catch(Throwable ignored) {
        }
        return fallback;
    }

    private void startNtkDirectManifestForegroundFetch(Manga manga, String image, int page, String source) {
        startNtkDirectManifestForegroundFetch(manga, image, page, source, true);
    }

    private void startNtkDirectManifestForegroundFetch(Manga manga, String image, int page,
                                                       String source, boolean anchorHedge) {
        if(!isNtkUserDemandAuthorized(manga))
            return;
        try {
            boolean started = ml.melun.mangaview.reader.ReaderImageCache.INSTANCE.startForegroundStreamFetch(
                    context,
                    manga,
                    image,
                    null,
                    anchorHedge,
                    null,
                    page,
                    true,
                    false,
                    readerImageCacheProducerGeneration);
            Log.d("EpisodeActivity", "ntk_direct_manifest_first_fetch path="
                    + manga.getNtkEpisodePath() + ",started=" + started
                    + ",index=" + page
                    + ",source=" + source
                    + ",first=" + image.substring(Math.max(0, image.length() - 32)));
        } catch(Throwable t) {
            Log.d("EpisodeActivity", "ntk_direct_manifest_first_fetch_error path="
                    + manga.getNtkEpisodePath() + ",index=" + page
                    + ",source=" + source + "," + t);
        }
    }

    private void startNtkDirectManifestAnchorFastFetch(Manga manga, String image, ArrayList<String> images) {
        if(manga == null || image == null || image.length() == 0)
            return;
        final long cacheProducerGeneration = readerImageCacheProducerGeneration;
        AppDispatchers.submitNtkForegroundImage(() -> {
            long startedAt = android.os.SystemClock.elapsedRealtime();
            ExecutorService raceExecutor = null;
            try {
                ArrayList<String> candidates = ntkInitialManhwaDirectVariants(image);
                raceExecutor = Executors.newFixedThreadPool(Math.min(4, candidates.size()));
                ExecutorCompletionService<NtkAnchorCandidateResult> completion =
                        new ExecutorCompletionService<>(raceExecutor);
                ArrayList<Future<NtkAnchorCandidateResult>> futures = new ArrayList<>();
                OkHttpClient candidateClient = getHttpClient().ntkForegroundImageFastClient()
                        .newBuilder()
                        .connectTimeout(1200L, TimeUnit.MILLISECONDS)
                        .readTimeout(2400L, TimeUnit.MILLISECONDS)
                        .callTimeout(2600L, TimeUnit.MILLISECONDS)
                        .build();
                for(String candidate : candidates) {
                    futures.add(completion.submit(() -> fetchNtkAnchorCandidate(
                            manga,
                            candidate,
                            candidateClient,
                            startedAt)));
                }
                long deadlineAt = android.os.SystemClock.elapsedRealtime() + 2800L;
                for(int completed = 0; completed < futures.size(); completed++) {
                    long waitMs = Math.max(1L, deadlineAt - android.os.SystemClock.elapsedRealtime());
                    Future<NtkAnchorCandidateResult> future = completion.poll(waitMs, TimeUnit.MILLISECONDS);
                    if(future == null)
                        break;
                    NtkAnchorCandidateResult result = future.get();
                    if(result == null || result.bytes == null || result.bytes.length == 0)
                        continue;
                    if(result.bytes.length < 32768) {
                        Log.d("EpisodeActivity", "ntk_direct_manifest_anchor_fast_fetch_partial_skip path="
                                + manga.getNtkEpisodePath() + ",bytes=" + result.bytes.length
                                + ",candidate=" + result.candidate.substring(Math.max(0, result.candidate.length() - 32))
                                + ",ms=" + (android.os.SystemClock.elapsedRealtime() - startedAt));
                        continue;
                    }
                    boolean cached = ReaderImageCache.cacheTrustedNtkForegroundBytes(
                            context,
                            manga,
                            result.candidate,
                            result.bytes,
                            "episode_direct_anchor_fast",
                            cacheProducerGeneration);
                    if(cached && !result.candidate.equals(image))
                        publishNtkDirectManifestVariant(
                                manga, images, image, result.candidate, cacheProducerGeneration);
                    Log.d("EpisodeActivity", "ntk_direct_manifest_anchor_fast_fetch_hit path="
                            + manga.getNtkEpisodePath() + ",bytes=" + result.bytes.length
                            + ",cached=" + cached
                            + ",candidate=" + result.candidate.substring(Math.max(0, result.candidate.length() - 32))
                            + ",ms=" + (android.os.SystemClock.elapsedRealtime() - startedAt));
                    for(Future<NtkAnchorCandidateResult> pending : futures) {
                        pending.cancel(true);
                    }
                    return;
                }
                Log.d("EpisodeActivity", "ntk_direct_manifest_anchor_fast_fetch_miss_all path="
                        + manga.getNtkEpisodePath()
                        + ",ms=" + (android.os.SystemClock.elapsedRealtime() - startedAt));
            } catch(Throwable t) {
                Log.d("EpisodeActivity", "ntk_direct_manifest_anchor_fast_fetch_error path="
                        + manga.getNtkEpisodePath()
                        + ",ms=" + (android.os.SystemClock.elapsedRealtime() - startedAt)
                        + "," + t.getClass().getSimpleName());
            } finally {
                if(raceExecutor != null)
                    raceExecutor.shutdownNow();
            }
        });
    }

    private void startNtkDirectManifestSurfaceCacheOnlyFetch(Manga manga, ArrayList<String> images,
                                                             int firstPage, int lastPage, String source) {
        if(manga == null || images == null || images.size() == 0)
            return;
        if(!isNtkUserDemandAuthorized(manga))
            return;
        int first = Math.max(0, Math.min(images.size() - 1, firstPage));
        int last = Math.max(first, Math.min(images.size() - 1, lastPage));
        for(int page = first; page <= last; page++) {
            String image = images.get(page);
            if(image == null || image.length() == 0)
                continue;
            final int currentPage = page;
            final String currentImage = image;
            Runnable fetch = () -> {
                try {
                    boolean started = ReaderImageCache.INSTANCE.startForegroundStreamFetch(
                            context,
                            manga,
                            currentImage,
                            null,
                            false,
                            null,
                            currentPage,
                            false,
                            false,
                            readerImageCacheProducerGeneration);
                    Log.d("EpisodeActivity", "ntk_direct_manifest_surface_prefetch path="
                            + manga.getNtkEpisodePath() + ",page=" + (currentPage + 1)
                            + ",started=" + started + ",source=" + source);
                } catch(Throwable t) {
                    Log.d("EpisodeActivity", "ntk_direct_manifest_surface_prefetch_error path="
                            + manga.getNtkEpisodePath() + ",page=" + (currentPage + 1)
                            + ",source=" + source + "," + t.getClass().getSimpleName());
                }
            };
            fetch.run();
        }
    }

    private NtkAnchorCandidateResult fetchNtkAnchorCandidate(Manga manga, String candidate,
                                                             OkHttpClient candidateClient,
                                                             long startedAt) {
        try {
            Request.Builder builder = new Request.Builder()
                    .url(candidate)
                    .header("User-Agent", getHttpClient().agent)
                    .header("Referer", getHttpClient().getUrl(manga.getNtkEpisodePath()))
                    .header("X-MangaViewer-Foreground", "1")
                    .header("X-MangaViewer-Anchor-Hedge", "1");
            String cookie = getHttpClient()
                    .getNativeNtkViewerImageCookieHeaderForPath(manga.getNtkEpisodePath());
            if(cookie != null && cookie.length() > 0)
                builder.header("Cookie", cookie);
            try(Response response = candidateClient.newCall(builder.build()).execute()) {
                String actualUrl = response.request().url().toString();
                if(isNtkCloudflareAbuseImageUrl(actualUrl)) {
                    Log.d("EpisodeActivity", "ntk_direct_manifest_anchor_fast_fetch_reject_abuse path="
                            + manga.getNtkEpisodePath()
                            + ",candidate=" + candidate.substring(Math.max(0, candidate.length() - 32))
                            + ",ms=" + (android.os.SystemClock.elapsedRealtime() - startedAt));
                    return null;
                }
                if(!response.isSuccessful() || response.body() == null) {
                    Log.d("EpisodeActivity", "ntk_direct_manifest_anchor_fast_fetch_miss path="
                            + manga.getNtkEpisodePath() + ",code=" + response.code()
                            + ",candidate=" + candidate.substring(Math.max(0, candidate.length() - 32))
                            + ",ms=" + (android.os.SystemClock.elapsedRealtime() - startedAt));
                    return null;
                }
                return new NtkAnchorCandidateResult(candidate, CustomHttpClient.readBytes(response));
            }
        } catch(Throwable t) {
            Log.d("EpisodeActivity", "ntk_direct_manifest_anchor_fast_fetch_candidate_error path="
                    + manga.getNtkEpisodePath()
                    + ",candidate=" + candidate.substring(Math.max(0, candidate.length() - 32))
                    + ",ms=" + (android.os.SystemClock.elapsedRealtime() - startedAt)
                    + "," + t.getClass().getSimpleName());
            return null;
        }
    }

    private static boolean isNtkCloudflareAbuseImageUrl(String url) {
        return url != null && url.toLowerCase(java.util.Locale.ROOT)
                .contains("cloudflare-terms-of-service-abuse.com");
    }

    private static final class NtkAnchorCandidateResult {
        final String candidate;
        final byte[] bytes;

        NtkAnchorCandidateResult(String candidate, byte[] bytes) {
            this.candidate = candidate;
            this.bytes = bytes;
        }
    }

    private void publishNtkDirectManifestVariant(Manga manga, ArrayList<String> images,
                                                 String original, String replacement,
                                                 long producerGeneration) {
        if(manga == null || images == null || images.size() == 0)
            return;
        String originalExt = original.replaceFirst("(?i)^.*\\.([a-z0-9]+)(\\?.*)?$", "$1");
        String replacementExt = replacement.replaceFirst("(?i)^.*\\.([a-z0-9]+)(\\?.*)?$", "$1");
        if(originalExt.equals(replacementExt))
            return;
        ArrayList<String> variantImages = new ArrayList<>(images.size());
        for(String sourceImage : images) {
            if(sourceImage == null) {
                variantImages.add(null);
            } else {
                variantImages.add(sourceImage.replaceFirst(
                        "(?i)\\.(" + java.util.regex.Pattern.quote(originalExt) + ")(\\?.*)?$",
                        "." + replacementExt + "$2"));
            }
        }
        ReaderImageCache.rememberAuthoritativeNtkImageUrlsFromBrowser(
                manga.getNtkEpisodePath(),
                variantImages,
                "episode-direct-anchor-variant",
                producerGeneration);
    }

    private ArrayList<String> preferCanonicalWebtoonJpgManifest(String path, ArrayList<String> images) {
        ArrayList<String> result = new ArrayList<>(images);
        return result;
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
                "episode-canonical-direct",
                readerImageCacheProducerGeneration);
        if(!ml.melun.mangaview.MainApplication.isNtkForegroundViewerPath(path))
            primeCanonicalDirectPreparedWindow(manga, images, "episode-canonical-direct");
        Log.d("EpisodeActivity", "ntk_browser_owned_canonical_direct_manifest path="
                + path + ",count=" + images.size());
        return true;
    }

    private boolean installImmediateNtkWebtoonDirectManifest(Manga manga, String path, int expected,
                                                            boolean prepareInitialImages,
                                                            boolean prefetchInitialImages) {
        ArrayList<String> images = buildNtkWebtoonDirectManifest(manga, path, expected);
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
                "episode-webtoon-canonical-direct-warm",
                readerImageCacheProducerGeneration);
        boolean foreground = ml.melun.mangaview.MainApplication.isNtkForegroundViewerPath(path);
        if(prepareInitialImages && !foreground)
            primeCanonicalDirectPreparedWindow(manga, images, "episode-webtoon-canonical-direct-warm");
        if(prefetchInitialImages && !foreground)
            prefetchCanonicalDirectInitialImages(manga, images, "episode-webtoon-canonical-direct-warm");
        Log.d("EpisodeActivity", "ntk_webtoon_canonical_direct_warmup path="
                + path + ",count=" + images.size()
                + ",prepare=" + prepareInitialImages
                + ",prefetch=" + prefetchInitialImages
                + ",foreground=" + foreground);
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
                String[] extensions = new String[]{"jpeg", "jpg", "png", "webp"};
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
        return buildNtkManhwaDirectManifest(path, expected, "booktoki9.org", "jpg");
    }

    private ArrayList<String> buildNtkManhwaDirectManifest(String path, int expected,
                                                           String host, String extension) {
        if(path == null || expected <= 0)
            return null;
        if(isModernProtectedNumericNtkEpisodePath(path)) {
            Log.d("EpisodeActivity", "ntk_manhwa_generated_manifest_blocked_api_only path="
                    + path + ",expected=" + expected);
            return null;
        }
        if(rejectedCanonicalDirectPaths.contains(path))
            return null;
        if(host == null || host.length() == 0 || extension == null || extension.length() == 0)
            return null;
        String[] parts = path.split("/");
        if(parts.length < 4 || !"manhwa".equalsIgnoreCase(parts[1]))
            return null;
        String workId = parts[2];
        String episodeId = parts[3];
        if(!workId.matches("\\d{1,12}") || !episodeId.matches("\\d{1,12}"))
            return null;
        if(!host.matches("[A-Za-z0-9.-]+") || !extension.matches("(?i)jpe?g|png|webp"))
            return null;
        ArrayList<String> images = new ArrayList<>();
        for(int page = 1; page <= expected; page++) {
            images.add(String.format(java.util.Locale.ROOT,
                    "https://%s/manhwa/%s/%s/p%03d.%s",
                    host,
                    workId,
                    episodeId,
                    page,
                    extension.toLowerCase(java.util.Locale.ROOT)));
        }
        return images;
    }

    private ArrayList<String> buildNtkManhwaDirectManifestFromFirstImage(String path, int expected,
                                                                         String firstImage) {
        if(firstImage == null || firstImage.length() == 0)
            return null;
        try {
            URL url = new URL(firstImage);
            String filePath = url.getPath();
            java.util.regex.Matcher matcher = java.util.regex.Pattern
                    .compile("/p001\\.(jpe?g|png|webp)$", java.util.regex.Pattern.CASE_INSENSITIVE)
                    .matcher(filePath);
            if(!matcher.find())
                return null;
            return buildNtkManhwaDirectManifest(
                    path,
                    expected,
                    url.getHost(),
                    matcher.group(1).toLowerCase(java.util.Locale.ROOT));
        } catch(Exception ignored) {
            return null;
        }
    }

    private ArrayList<String> buildNtkWebtoonDirectManifest(Manga manga, String path, int expected) {
        if(path == null || expected <= 0)
            return null;
        if(isModernProtectedNumericNtkEpisodePath(path)) {
            Log.d("EpisodeActivity", "ntk_webtoon_generated_manifest_blocked_api_only path="
                    + path + ",expected=" + expected);
            return null;
        }
        if(rejectedCanonicalDirectPaths.contains(path))
            return null;
        String[] parts = path.split("/");
        if(parts.length < 4 || !"webtoon".equalsIgnoreCase(parts[1]))
            return null;
        String pathWorkId = parts[2];
        String workId = manga == null ? "" : manga.getNtkImageWorkId();
        if(!workId.matches("\\d{1,12}"))
            workId = pathWorkId;
        String episodeId = parts[3];
        if(!workId.matches("\\d{1,12}") || !episodeId.matches("\\d{1,12}"))
            return null;
        String firstImage = firstKnownNtkWebtoonDirectImage(path, workId, episodeId);
        String extension = "jpeg";
        String basePrefix = "https://fifa.worldcup73.xyz/black/episodes/" + workId + "/" + episodeId + "/";
        if(firstImage != null && firstImage.length() > 0) {
            try {
                URL parsed = new URL(firstImage);
                java.util.regex.Matcher fileMatcher = java.util.regex.Pattern
                        .compile("(?i)^(.*/)(?:p)?001\\.(jpe?g|png|webp)$")
                        .matcher(parsed.getPath());
                if(fileMatcher.find()) {
                    basePrefix = parsed.getProtocol() + "://" + parsed.getHost() + fileMatcher.group(1);
                    extension = fileMatcher.group(2).toLowerCase(java.util.Locale.ROOT);
                }
            } catch(Exception ignored) {
            }
        }
        ArrayList<String> images = new ArrayList<>();
        for(int page = 1; page <= expected; page++) {
            images.add(String.format(java.util.Locale.ROOT,
                    "%sp%03d.%s",
                    basePrefix,
                    page,
                    extension));
        }
        return images;
    }

    private boolean isModernProtectedNumericNtkEpisodePath(String path) {
        if(!isNumericNtkEpisodePath(path))
            return false;
        try {
            CustomHttpClient client = getHttpClient();
            boolean modernNtkRoot = client == null || client.isModernNtkGuardRootForPath(path);
            return !shouldBuildNtkSyntheticDirectManifestForTest(path, modernNtkRoot);
        } catch(Throwable ignored) {
            // A numeric modern NTK episode must never fall back to an invented CDN manifest
            // merely because root inspection failed. Native viewer API discovery remains available.
            return true;
        }
    }

    private static boolean isNumericNtkEpisodePath(String path) {
        return path != null && path.trim().matches(
                "(?i)^/(?:webtoon|manhwa)/\\d{1,12}/\\d{1,12}(?:[/?#].*)?$");
    }

    static boolean shouldBuildNtkSyntheticDirectManifestForTest(String path, boolean modernNtkRoot) {
        return !(modernNtkRoot && isNumericNtkEpisodePath(path));
    }

    private String firstKnownNtkWebtoonDirectImage(String path, String workId, String episodeId) {
        if(path == null || workId == null || episodeId == null)
            return null;
        long minCreatedAt = android.os.SystemClock.elapsedRealtime() - 30_000L;
        try {
            String found = firstMatchingNtkWebtoonDirectImage(
                    ReaderImageCache.INSTANCE.earlyNtkImageUrls(path, minCreatedAt),
                    workId,
                    episodeId);
            if(found != null)
                return found;
            found = firstMatchingNtkWebtoonDirectImage(
                    ReaderImageCache.INSTANCE.earlyNtkGeneratedSuccessImageUrls(path, minCreatedAt),
                    workId,
                    episodeId);
            if(found != null)
                return found;
        } catch(Throwable ignored) {
        }
        return firstReachableNtkWebtoonDirectImage(workId, episodeId, 1);
    }

    private static String firstMatchingNtkWebtoonDirectImage(List<String> urls, String workId, String episodeId) {
        if(urls == null || urls.isEmpty())
            return null;
        java.util.regex.Pattern pattern = java.util.regex.Pattern.compile(
                "(?i)^https?://[^/]+/(?:black|blacktoon|wt)/episodes/"
                        + java.util.regex.Pattern.quote(workId) + "/"
                        + java.util.regex.Pattern.quote(episodeId)
                        + "/p001\\.(?:jpe?g|png|webp)(?:[?#].*)?$");
        for(String url : urls) {
            if(url != null && pattern.matcher(url.trim()).matches())
                return url.trim();
        }
        return null;
    }

    private int ntkViewerPayloadHintImageCount(Manga manga, String path) {
        if(manga == null || path == null || path.length() == 0)
            return 0;
        try {
            String hint = manga.getNtkViewerPayloadHint();
            if(hint == null || hint.length() == 0)
                return 0;
            List<String> urls = Manga.ntkViewerPayloadImageUrls(hint, path);
            return urls == null ? 0 : urls.size();
        } catch(Throwable ignored) {
            return 0;
        }
    }

    private ArrayList<String> ntkViewerPayloadHintImageUrls(Manga manga, String path) {
        if(manga == null || path == null || path.length() == 0)
            return new ArrayList<>();
        try {
            String hint = manga.getNtkViewerPayloadHint();
            if(hint == null || hint.length() == 0)
                return new ArrayList<>();
            List<String> urls = Manga.ntkViewerPayloadImageUrls(hint, path);
            if(urls == null || urls.isEmpty())
                return new ArrayList<>();
            return new ArrayList<>(urls);
        } catch(Throwable ignored) {
            return new ArrayList<>();
        }
    }

    private boolean isLikelyNtkWebtoonDirectFirstImageReachable(String path) {
        return firstReachableNtkWebtoonDirectImage(path, 8) != null;
    }

    private ArrayList<String> resolveNtkWebtoonDirectManifestUnknown(String path, String preferredWorkId, int maxPages) {
        if(path == null || maxPages <= 0)
            return null;
        String[] parts = path.split("/");
        if(parts.length < 4 || !"webtoon".equalsIgnoreCase(parts[1]))
            return null;
        String workId = preferredWorkId == null ? "" : preferredWorkId.trim();
        if(!workId.matches("\\d{1,12}"))
            workId = parts[2];
        String episodeId = parts[3];
        if(!workId.matches("\\d{1,12}") || !episodeId.matches("\\d{1,12}"))
            return null;
        final String probeWorkId = workId;
        final String probeEpisodeId = episodeId;
        String first = firstReachableNtkWebtoonDirectImage(probeWorkId, probeEpisodeId, 8);
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
                        String candidate = firstReachableNtkWebtoonDirectImageCandidate(
                                probeWorkId,
                                probeEpisodeId,
                                currentPage);
                        return candidate;
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
            String candidate = firstReachableNtkWebtoonDirectImageCandidate(workId, episodeId, page);
            if(candidate != null)
                return candidate;
        }
        return null;
    }

    private String firstReachableNtkManhwaDirectImage(String path) {
        if(path == null)
            return null;
        String[] parts = path.split("/");
        if(parts.length < 4 || !"manhwa".equalsIgnoreCase(parts[1]))
            return null;
        final String workId = parts[2];
        final String episodeId = parts[3];
        if(!workId.matches("\\d{1,12}") || !episodeId.matches("\\d{1,12}"))
            return null;
        String[] hosts = new String[]{"booktoki9.org", "booktoki8.org", "mana.apihost93.com"};
        String[] extensions = new String[]{"jpeg", "jpg", "png", "webp"};
        java.util.ArrayList<String> candidates = new java.util.ArrayList<>();
        for(String host : hosts) {
            for(String extension : extensions) {
                candidates.add(String.format(java.util.Locale.ROOT,
                        "https://%s/manhwa/%s/%s/p001.%s",
                        host,
                        workId,
                        episodeId,
                        extension));
            }
        }
        java.util.concurrent.ExecutorService pool = java.util.concurrent.Executors.newFixedThreadPool(
                Math.min(12, candidates.size()),
                runnable -> {
                    Thread thread = new Thread(runnable, "NtkManhwaFirstProbe");
                    thread.setDaemon(true);
                    thread.setPriority(Thread.NORM_PRIORITY + 1);
                    return thread;
                });
        long startedAt = android.os.SystemClock.elapsedRealtime();
        try {
            java.util.concurrent.ExecutorCompletionService<String> completion =
                    new java.util.concurrent.ExecutorCompletionService<>(pool);
            for(String candidate : candidates) {
                completion.submit(() -> isCanonicalDirectImageReachable(candidate, "GET") ? candidate : null);
            }
            long deadline = startedAt + 1300L;
            for(int i = 0; i < candidates.size(); i++) {
                long remaining = deadline - android.os.SystemClock.elapsedRealtime();
                if(remaining <= 0)
                    break;
                java.util.concurrent.Future<String> future = completion.poll(
                        remaining,
                        java.util.concurrent.TimeUnit.MILLISECONDS);
                if(future == null)
                    break;
                String found = future.get();
                if(found != null) {
                    Log.d("EpisodeActivity", "ntk_manhwa_first_direct_probe_hit path="
                            + path + ",ms=" + (android.os.SystemClock.elapsedRealtime() - startedAt)
                            + ",first=" + found);
                    return found;
                }
            }
            Log.d("EpisodeActivity", "ntk_manhwa_first_direct_probe_miss path="
                    + path + ",ms=" + (android.os.SystemClock.elapsedRealtime() - startedAt));
            return null;
        } catch(Exception e) {
            Log.d("EpisodeActivity", "ntk_manhwa_first_direct_probe_error path="
                    + path + ",ms=" + (android.os.SystemClock.elapsedRealtime() - startedAt)
                    + "," + e.getClass().getSimpleName());
            return null;
        } finally {
            pool.shutdownNow();
        }
    }

    private String firstReachableNtkWebtoonDirectImage(String workId, String episodeId, int probePages) {
        if(workId == null || episodeId == null || probePages <= 0)
            return null;
        if(!workId.matches("\\d{1,12}") || !episodeId.matches("\\d{1,12}"))
            return null;
        for(int page = 1; page <= probePages; page++) {
            String candidate = firstReachableNtkWebtoonDirectImageCandidate(workId, episodeId, page);
            if(candidate != null)
                return candidate;
        }
        return null;
    }

    private String firstReachableNtkWebtoonDirectImageCandidate(String workId, String episodeId, int page) {
        String[] extensions = new String[]{"jpeg", "jpg", "webp", "png"};
        for(String extension : extensions) {
            String candidate = String.format(java.util.Locale.ROOT,
                    "https://fifa.worldcup73.xyz/black/episodes/%s/%s/p%03d.%s",
                    workId,
                    episodeId,
                    page,
                    extension);
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
        if(!workId.matches("\\d{1,12}") || !episodeId.matches("\\d{1,12}"))
            return null;
        String[] hosts = new String[]{"booktoki9.org", "booktoki8.org", "mana.apihost93.com"};
        String[] extensions = new String[]{"jpeg", "jpg", "png", "webp"};
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
        String[] extensions = new String[]{"jpeg", "jpg", "png", "webp"};
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
        int width = ReaderWindowViewport.width(this, episodeList);
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
        if(!isNtkUserDemandAuthorized(manga))
            return;
        String path = manga.getNtkEpisodePath();
        if(ml.melun.mangaview.MainApplication.isNtkForegroundViewerPath(path)) {
            Log.d("EpisodeActivity", "ntk_canonical_direct_prefetch_skip_foreground path="
                    + path + ",count=" + images.size() + ",reason=" + reason);
            return;
        }
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
        if(!isNtkUserDemandAuthorized(manga))
            return;
        String path = manga.getNtkEpisodePath();
        String key = path + ":initial:" + images.size() + ":" + reason;
        if(!speculativeCanonicalDirectPrefetchPaths.add(key))
            return;
        final Context appContext = getApplicationContext();
        final Manga prefetchManga = manga;
        int startIndex = reason != null && reason.contains("after-launch") ? 4 : 0;
        if(startIndex >= images.size())
            return;
        int limit = reason != null && reason.contains("after-launch")
                ? Math.min(images.size(), startIndex + 8)
                : Math.min(images.size(), NTK_DIRECT_INITIAL_PREFETCH_PAGES);
        final ArrayList<String> prefetchImages = new ArrayList<>(
                images.subList(startIndex, limit));
        AppDispatchers.submitIo(() -> {
            long startedAt = android.os.SystemClock.elapsedRealtime();
            java.util.concurrent.ExecutorService pool = java.util.concurrent.Executors.newFixedThreadPool(
                    Math.min(reason != null && reason.contains("after-launch") ? 1 : 4, prefetchImages.size()),
                    runnable -> {
                        Thread thread = new Thread(runnable, "NtkDirectInitialPrefetch");
                        thread.setDaemon(true);
                        thread.setPriority(reason != null && reason.contains("after-launch")
                                ? Thread.NORM_PRIORITY - 1
                                : Thread.NORM_PRIORITY);
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
        return path != null && path.matches("^/manhwa/\\d{1,12}/\\d{1,12}(?:[/?#].*)?$");
    }

    private boolean hasAcceptedNtkWebtoonDirectManifest(String path) {
        if(path == null || !path.startsWith("/webtoon/"))
            return false;
        if(!acceptedCanonicalDirectPaths.contains(path))
            return false;
        ArrayList<String> images = acceptedCanonicalDirectImages.get(path);
        return images != null && images.size() > 0
                && ReaderImageCache.INSTANCE.hasCompleteValidatedNativeDirectEarlyNtkImageUrls(
                path,
                images.size(),
                android.os.SystemClock.elapsedRealtime() - 30_000L);
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
        launchManga.setNtkImageCount(launchImages.size());
        acceptedCanonicalDirectPaths.add(path);
        acceptedCanonicalDirectImages.put(path, new ArrayList<>(launchImages));
        ReaderImageCache.rememberAuthoritativeNtkImageUrlsFromBrowser(
                path,
                launchImages,
                "episode-canonical-direct-immediate",
                readerImageCacheProducerGeneration);
        startNtkDirectManifestAnchorFetchOnly(
                launchManga,
                launchImages,
                exactEpisode,
                "prelaunch_anchor");
        Log.d("EpisodeActivity", "ntk_canonical_direct_launch_immediate path="
                + path + ",count=" + launchImages.size());
        launchViewerNow(launchManga, code, exactEpisode);
        Log.d("EpisodeActivity", "ntk_canonical_direct_postlaunch_fetch_skip path="
                + path + ",reason=reader_owns_visible_fetch_after_launch");
        Log.d("EpisodeActivity", "ntk_canonical_direct_background_resolve_skip_immediate path="
                + path + ",count=" + launchImages.size());
    }

    private void prepareUnknownCanonicalDirectManifestAndLaunch(Manga manga, int code, boolean exactEpisode,
                                                                String path) {
        if(manga == null || title == null || path == null || path.length() == 0)
            return;
        final Manga launchManga = manga;
        int hintedCount = Math.max(launchManga.getNtkImageCount(), 0);
        final int speculativeCount = hintedCount > 0
                ? Math.max(NTK_DIRECT_INITIAL_PREFETCH_PAGES, Math.min(hintedCount, 16))
                : 16;
        String firstDirectImage = null;
        ArrayList<String> speculativeImages = buildNtkManhwaDirectManifest(path, speculativeCount);
        if(speculativeImages == null || speculativeImages.size() == 0) {
            rejectedCanonicalDirectPaths.add(path);
            armBrowserOwnedViewerLaunch(launchManga, code, exactEpisode);
            return;
        }
        launchManga.setNtkImageCount(speculativeImages.size());
        ReaderImageCache.rememberAuthoritativeNtkImageUrlsFromBrowser(
                path,
                speculativeImages,
                "episode-canonical-direct-immediate-speculative",
                readerImageCacheProducerGeneration);
        Log.d("EpisodeActivity", "ntk_canonical_direct_unknown_launch_immediate path="
                + path + ",speculativeCount=" + speculativeImages.size()
                + ",first=" + (firstDirectImage == null ? "" : firstDirectImage));
        launchViewerNow(launchManga, code, exactEpisode);
        final ArrayList<String> initialSpeculativeImages = speculativeImages;
        Log.d("EpisodeActivity", "ntk_canonical_direct_unknown_native_probe_skip path="
                + path + ",count=" + initialSpeculativeImages.size());
        AppDispatchers.submitUserAction(() -> {
            ArrayList<String> launchImages = null;
            long startedAt = android.os.SystemClock.elapsedRealtime();
            try {
                launchImages = resolveNtkManhwaDirectManifestUnknown(path, 240);
                if(launchImages == null || launchImages.size() == 0) {
                    rejectedCanonicalDirectPaths.add(path);
                    return;
                }
                launchManga.setNtkImageCount(launchImages.size());
                acceptedCanonicalDirectPaths.add(path);
                acceptedCanonicalDirectImages.put(path, new ArrayList<>(launchImages));
                ReaderImageCache.rememberAuthoritativeNtkImageUrlsFromBrowser(
                        path,
                        launchImages,
                        "episode-canonical-direct-immediate-resolved",
                        readerImageCacheProducerGeneration);
            } catch(Exception e) {
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
        });
    }

    private void prepareUnknownNtkWebtoonDirectManifestAndLaunch(Manga manga, int code, boolean exactEpisode,
                                                                 String path) {
        if(manga == null || title == null || path == null || path.length() == 0)
            return;
        final Manga launchManga = manga;
        final int speculativeCount = 128;
        final int initialRunwayCount = 18;
        int knownCount = ntkViewerPayloadHintImageCount(launchManga, path);
        boolean unverifiedCanonicalPathId = isUnverifiedNtkWebtoonCanonicalPathId(launchManga, path);
        String[] pathParts = path.split("/");
        String pathWorkId = pathParts.length >= 4 ? pathParts[2] : "";
        String currentImageWorkId = launchManga.getNtkImageWorkId();
        boolean hasDistinctGeneratedImageWorkId = currentImageWorkId != null
                && currentImageWorkId.matches("\\d{1,12}")
                && pathWorkId.matches("\\d{1,12}")
                && !currentImageWorkId.equals(pathWorkId);
        final boolean protectedNumericApiOnly = isModernProtectedNumericNtkEpisodePath(path);
        final boolean apiFirstSlugWebtoon = protectedNumericApiOnly
                || isNtkWebtoonSlugEpisodePath(path)
                || (!hasAcceptedNtkWebtoonDirectManifest(path) && !hasDistinctGeneratedImageWorkId);
        ArrayList<String> payloadHintImages = ntkViewerPayloadHintImageUrls(launchManga, path);
        if(payloadHintImages.size() >= 4) {
            launchManga.setNtkImageCount(payloadHintImages.size());
            ReaderImageCache.rememberAuthoritativeNtkImageUrlsFromBrowser(
                    path,
                    payloadHintImages,
                    "episode-webtoon-payload-hint-initial",
                    readerImageCacheProducerGeneration);
            ReaderImageCache.rememberEarlyNtkImageUrls(
                    path, payloadHintImages, readerImageCacheProducerGeneration);
            startNtkDirectManifestAnchorFetchOnly(
                    launchManga,
                    payloadHintImages,
                    exactEpisode,
                    "webtoon_payload_hint_initial_anchor");
            startNtkDirectManifestInitialSurfaceFetches(
                    launchManga,
                    payloadHintImages,
                    exactEpisode,
                    "webtoon_payload_hint_initial");
            startNtkDirectManifestInitialSurfaceTailAfterLaunch(
                    launchManga,
                    payloadHintImages,
                    exactEpisode,
                    "webtoon_payload_hint_initial");
            Log.d("EpisodeActivity", "ntk_webtoon_payload_hint_launch_immediate path="
                    + path + ",count=" + payloadHintImages.size()
                    + ",apiFirstSlug=" + apiFirstSlugWebtoon
                    + ",distinctImageWorkId=" + hasDistinctGeneratedImageWorkId);
            launchViewerNow(launchManga, code, exactEpisode);
            return;
        }
        if(launchManga.getNtkImageCount() <= 0 && knownCount > 0
                && !unverifiedCanonicalPathId && !apiFirstSlugWebtoon)
            launchManga.setNtkImageCount(knownCount);
        if(unverifiedCanonicalPathId
                && currentImageWorkId != null
                && currentImageWorkId.matches("\\d{1,12}")
                && !currentImageWorkId.equals(pathWorkId)) {
            unverifiedCanonicalPathId = false;
        }
        boolean hasKnownImageCount = launchManga.getNtkImageCount() > 0;
        if(launchManga.getNtkImageCount() <= 0 && !unverifiedCanonicalPathId
                && !apiFirstSlugWebtoon)
            launchManga.setNtkImageCount(initialRunwayCount);
        ArrayList<String> initialImages = launchManga.getNtkImageCount() > 0
                && !unverifiedCanonicalPathId && !apiFirstSlugWebtoon
                ? buildNtkWebtoonDirectManifest(
                launchManga,
                path,
                Math.min(launchManga.getNtkImageCount(), hasKnownImageCount ? speculativeCount : initialRunwayCount))
                : null;
        if(initialImages != null && initialImages.size() > 0) {
            launchManga.setNtkImageCount(initialImages.size());
            acceptedCanonicalDirectPaths.add(path);
            acceptedCanonicalDirectImages.put(path, new ArrayList<>(initialImages));
            if(hasKnownImageCount) {
                ReaderImageCache.rememberAuthoritativeNtkImageUrlsFromBrowser(
                        path,
                        initialImages,
                        "episode-webtoon-canonical-direct-unknown-initial",
                        readerImageCacheProducerGeneration);
            }
            ReaderImageCache.rememberEarlyNtkImageUrls(
                    path, initialImages, readerImageCacheProducerGeneration);
            if(hasKnownImageCount && hasDistinctGeneratedImageWorkId) {
                startNtkDirectManifestAnchorFetchOnly(
                        launchManga,
                        initialImages,
                        exactEpisode,
                        "webtoon_canonical_direct_unknown_initial_anchor");
                startNtkDirectManifestInitialSurfaceTailAfterLaunch(
                        launchManga,
                        initialImages,
                        exactEpisode,
                        "webtoon_canonical_direct_unknown_initial_tail");
            } else {
                startNtkDirectManifestAnchorFastFetch(
                        launchManga,
                        initialImages.get(0),
                        initialImages);
                startNtkDirectManifestInitialSurfaceFetches(
                        launchManga,
                        initialImages,
                        exactEpisode,
                        "webtoon_canonical_direct_unknown_initial");
            }
        }
        Log.d("EpisodeActivity", "ntk_webtoon_canonical_direct_unknown_launch_immediate path="
                + path + ",count=" + launchManga.getNtkImageCount()
                    + ",knownCount=" + hasKnownImageCount
                    + ",unverifiedPathId=" + unverifiedCanonicalPathId
                    + ",apiFirstSlug=" + apiFirstSlugWebtoon
                    + ",protectedApiOnly=" + protectedNumericApiOnly
                    + ",distinctImageWorkId=" + hasDistinctGeneratedImageWorkId);
        launchViewerNow(launchManga, code, exactEpisode);
        if(apiFirstSlugWebtoon) {
            Log.d("EpisodeActivity", "ntk_webtoon_slug_api_prefetch_reader_owned path="
                    + path);
        }
        if(initialImages != null && initialImages.size() > 0
                && hasKnownImageCount && hasDistinctGeneratedImageWorkId) {
            Log.d("EpisodeActivity", "ntk_webtoon_canonical_direct_unknown_resolve_skip_seeded_manifest path="
                    + path + ",count=" + initialImages.size()
                    + ",imageWork=" + launchManga.getNtkImageWorkId());
            return;
        }
        final boolean finalUnverifiedCanonicalPathId = unverifiedCanonicalPathId;
        AppDispatchers.submitUserAction(() -> {
            ArrayList<String> launchImages = null;
            long startedAt = android.os.SystemClock.elapsedRealtime();
            try {
                if(apiFirstSlugWebtoon) {
                    Log.d("EpisodeActivity", "ntk_webtoon_canonical_direct_unknown_resolve_skip_api_first_slug path="
                            + path);
                    return;
                }
                if(finalUnverifiedCanonicalPathId) {
                    Log.d("EpisodeActivity", "ntk_webtoon_canonical_direct_unknown_resolve_skip_unverified_path_id path="
                            + path + ",imageWork=" + launchManga.getNtkImageWorkId());
                    return;
                }
                launchImages = resolveNtkWebtoonDirectManifestUnknown(
                        path,
                        launchManga.getNtkImageWorkId(),
                        speculativeCount);
                if(launchImages == null || launchImages.size() == 0) {
                    rejectedCanonicalDirectPaths.add(path);
                    return;
                }
                launchManga.setNtkImageCount(launchImages.size());
                acceptedCanonicalDirectPaths.add(path);
                acceptedCanonicalDirectImages.put(path, new ArrayList<>(launchImages));
                ReaderImageCache.rememberAuthoritativeNtkImageUrlsFromBrowser(
                        path,
                        launchImages,
                        "episode-webtoon-canonical-direct-unknown",
                        readerImageCacheProducerGeneration);
                final ArrayList<String> finalLaunchImages = launchImages;
                AppDispatchers.runOnMain(() -> {
                    if(destroyed || isFinishing())
                        return;
                    prefetchCanonicalDirectImages(launchManga, finalLaunchImages, "webtoon-canonical-direct-unknown");
                });
            } catch(Exception e) {
                Log.d("EpisodeActivity", "ntk_webtoon_canonical_direct_unknown_prepare_error path="
                        + path + "," + e);
                ml.melun.mangaview.report.CrashReporter.record(e);
            } finally {
                int count = launchImages == null ? 0 : launchImages.size();
                Log.d("EpisodeActivity", "ntk_webtoon_canonical_direct_unknown_prepare_done path="
                        + path + ",count=" + count
                        + ",ms=" + (android.os.SystemClock.elapsedRealtime() - startedAt));
            }
        });
    }

    private boolean isNtkWebtoonSlugEpisodePath(String path) {
        if(path == null)
            return false;
        java.util.regex.Matcher matcher = java.util.regex.Pattern
                .compile("^/webtoon/([^/?#]+)/([^/?#]+)(?:[/?#].*)?$")
                .matcher(path.trim());
        return matcher.matches()
                && (!matcher.group(1).matches("\\d{1,12}")
                || !matcher.group(2).matches("\\d{1,12}"));
    }

    private boolean isNtkKpWebtoonSlugEpisodePath(String path) {
        if(path == null)
            return false;
        java.util.regex.Matcher matcher = java.util.regex.Pattern
                .compile("^/webtoon/\\d{1,12}/(kp-[^/?#]+)(?:[/?#].*)?$",
                        java.util.regex.Pattern.CASE_INSENSITIVE)
                .matcher(path.trim());
        return matcher.matches();
    }

    private boolean isUnverifiedNtkWebtoonCanonicalPathId(Manga manga, String path) {
        if(manga == null || path == null)
            return false;
        java.util.regex.Matcher matcher = java.util.regex.Pattern
                .compile("^/webtoon/(\\d{1,12})/(\\d{1,12})(?:[/?#].*)?$")
                .matcher(path.trim());
        if(!matcher.matches())
            return false;
        String pathWorkId = matcher.group(1);
        String imageWorkId = manga.getNtkImageWorkId();
        if(imageWorkId == null || imageWorkId.trim().length() == 0)
            return ntkViewerPayloadHintImageCount(manga, path) <= 0;
        if(!pathWorkId.equals(imageWorkId.trim()))
            return false;
        return ntkViewerPayloadHintImageCount(manga, path) <= 0;
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
        final Title progressTitle = title;
        final int progressEpisodeId = episodeId;
        AppDispatchers.submitIo(() -> {
            p.updateRecentData(progressTitle);
            p.setBookmark(progressTitle, progressEpisodeId);
        });
        // The native reader is already visible and owns physical input. Invalidating the hidden
        // episode RecyclerView here rebuilds rows in the same ACTION_UP turn and can queue several
        // reader gestures behind work the user cannot see. The model above is authoritative and
        // the adapter rebinds it when the list becomes visible again.
        if(episodeAdapter != null && (ntkInlineReaderController == null
                || !ntkInlineReaderController.isActive()))
            episodeAdapter.setBookmark(episodeIndex);
    }

    static int selectedEpisodeIndexForProgressForTest(int adapterPosition, Manga manga, List<Manga> episodes) {
        return selectedEpisodeIndexForProgress(adapterPosition, manga, episodes);
    }

    private static int selectedEpisodeIndexForProgress(int adapterPosition, Manga manga, List<Manga> episodes) {
        if(episodes == null || episodes.size() == 0)
            return -1;
        int fromAdapter = adapterPosition;
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
        if(episodeLightToolbar != null)
            episodeLightToolbar.setFavorite(favorite);
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
        if(ntkInlineReaderController != null)
            ntkInlineReaderController.onHostResume();
        boolean inlineActive = ntkInlineReaderController != null
                && ntkInlineReaderController.isActive();
        boolean inlineOwned = ntkInlineReaderController != null
                && (inlineActive || ntkInlineReaderController.isBinding()
                || ntkInlineReaderController.isStaged());
        ntkViewerSelectionInProgress = inlineOwned;
        PerformanceMonitor.resume();
        if(episodeRefreshSuspendedForReader
                && !inlineActive
                && !ml.melun.mangaview.MainApplication.isNtkForegroundViewerPathActive()) {
            episodeRefreshSuspendedForReader = false;
            startEpisodeRefresh(false);
        }
        if(!inlineActive)
            startEagerNtkReaderPreparation("resumed");
    }

    private void registerNtkEpisodeShellFrameCommit() {
        final View root = findViewById(R.id.ntk_episode_root);
        if(root == null) return;
        final android.view.ViewTreeObserver observer = root.getViewTreeObserver();
        final android.view.ViewTreeObserver.OnDrawListener drawListener =
                new android.view.ViewTreeObserver.OnDrawListener() {
                    @Override
                    public void onDraw() {
                        if(ntkEpisodeShellFirstDrawNanos == 0L) {
                            ntkEpisodeShellFirstDrawNanos = System.nanoTime();
                        }
                        root.post(() -> {
                            android.view.ViewTreeObserver live = root.getViewTreeObserver();
                            if(live.isAlive()) live.removeOnDrawListener(this);
                        });
                    }
                };
        observer.addOnDrawListener(drawListener);
        if(Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            observer.registerFrameCommitCallback(this::publishNtkEpisodeShellFrameCommit);
        } else {
            root.getViewTreeObserver().addOnDrawListener(
                    new android.view.ViewTreeObserver.OnDrawListener() {
                        @Override
                        public void onDraw() {
                            root.post(() -> {
                                android.view.ViewTreeObserver live = root.getViewTreeObserver();
                                if(live.isAlive()) live.removeOnDrawListener(this);
                                publishNtkEpisodeShellFrameCommit();
                            });
                        }
                    });
        }
    }

    private void publishNtkEpisodeShellFrameCommit() {
        if(ntkEpisodeShellFrameCommitted) return;
        ntkEpisodeShellFrameCommitNanos = System.nanoTime();
        ntkEpisodeShellFrameCommitted = true;
        NtkInlineReaderController controller = ntkInlineReaderController;
        if(controller != null) {
            controller.onEpisodeShellFrameCommitted(ntkEpisodeShellFrameCommitNanos);
        }
        Log.d("ViewerPerf", "ntk_episode_shell_frame_committed"
                + " activityCreateNs=" + ntkActivityCreateNanos
                + ",discoveryStartNs=" + ntkDiscoveryStartNanos
                + ",setContentNs=" + ntkSetContentViewNanos
                + ",firstDrawNs=" + ntkEpisodeShellFirstDrawNanos
                + ",frameCommitNs=" + ntkEpisodeShellFrameCommitNanos);
    }

    @Override
    protected void onPause() {
        boolean inlineOwned = ntkInlineReaderController != null
                && (ntkInlineReaderController.isActive()
                || ntkInlineReaderController.isBinding()
                || ntkInlineReaderController.isStaged());
        if(!inlineOwned) {
            discardPendingInlineLaunch();
            ntkViewerSelectionInProgress = false;
        }
        if(ntkInlineReaderController != null)
            ntkInlineReaderController.onHostPause();
        if(isNtkTitle()
                && ml.melun.mangaview.MainApplication.isNtkForegroundViewerPathActive()) {
            cancelEpisodeRefresh();
            cancelNtkEpisodeLoadWatchdog();
            if(episodeViewModel != null)
                episodeViewModel.cancelActiveLoad();
            episodeRefreshSuspendedForReader = true;
            Log.d("EpisodeActivity", "ntk_episode_refresh_cancelled_for_reader");
        }
        PerformanceMonitor.pause();
        super.onPause();
        overridePendingTransition(0, 0);
    }

    @Override
    protected void onDestroy() {
        destroyed = true;
        String closingNtkPath = ntkUserDemandPath;
        discardPendingInlineLaunch();
        clearNtkInlineStageObserver();
        if(ntkInlineReaderController != null)
            ntkInlineReaderController.onHostDestroy();
        cancelEpisodeRefresh();
        if(episodeList != null) {
            episodeList.removeCallbacks(initialEpisodeWarmupRunnable);
            episodeList.removeCallbacks(ntkVisibleWindowWarmupRunnable);
        }
        cancelNtkEpisodeLoadWatchdog();
        if(episodeViewModel != null && !isChangingConfigurations())
            episodeViewModel.cancelActiveLoad();
        if(episodeList != null)
            episodeList.setAdapter(null);
        if(closingNtkPath.length() > 0)
            ml.melun.mangaview.MainApplication.clearNtkForegroundViewerPath(closingNtkPath);
        clearNtkUserDemand(closingNtkPath);
        PerformanceMonitor.detach();
        super.onDestroy();
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if(ntkInlineReaderController != null)
            ntkInlineReaderController.onHostWindowFocusChanged(hasFocus);
    }

    @Override
    public void onBackPressed() {
        if(ntkInlineReaderController != null && ntkInlineReaderController.handleBackPressed())
            return;
        discardPendingInlineLaunch();
        super.onBackPressed();
    }

    /** Read-only qualification/diagnostic access; it cannot stage, activate, or inject input. */
    public NtkInlineReaderController getNtkInlineReaderControllerForDiagnostics() {
        return ntkInlineReaderController;
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
                episodeAdapter = createEpisodeAdapter(episodes);
                showConfirmedEmptyEpisodeState(false);
                afterLoad();
            });
        });
    }

    private void applyEpisodeWindowChrome() {
        EpisodeWindowStyler.apply(this, dark, episodeList);
    }

}

