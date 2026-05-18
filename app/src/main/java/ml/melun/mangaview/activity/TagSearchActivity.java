package ml.melun.mangaview.activity;

import android.content.Context;
import android.content.Intent;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.ActionBar;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;

import android.graphics.Rect;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import androidx.appcompat.widget.Toolbar;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.appcompat.widget.PopupMenu;
import androidx.recyclerview.widget.RecyclerView;
import android.view.MenuItem;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.ViewGroup;
import android.widget.TextView;

import com.google.gson.Gson;
import com.omadahealth.github.swipyrefreshlayout.library.SwipyRefreshLayout;
import com.omadahealth.github.swipyrefreshlayout.library.SwipyRefreshLayoutDirection;

import java.util.ArrayList;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import ml.melun.mangaview.ui.NpaLinearLayoutManager;
import ml.melun.mangaview.ui.StableScrollbarRecyclerView;
import ml.melun.mangaview.R;
import ml.melun.mangaview.Utils;
import ml.melun.mangaview.adapter.TitleAdapter;
import ml.melun.mangaview.adapter.UpdatedAdapter;
import ml.melun.mangaview.mangaview.Bookmark;
import ml.melun.mangaview.mangaview.Manga;
import ml.melun.mangaview.mangaview.Search;
import ml.melun.mangaview.mangaview.Title;
import ml.melun.mangaview.mangaview.UpdatedList;
import ml.melun.mangaview.mangaview.UpdatedManga;
import ml.melun.mangaview.repository.CacheFileStore;
import ml.melun.mangaview.repository.EpisodeSnapshotCache;
import ml.melun.mangaview.repository.MangaRepository;
import ml.melun.mangaview.runtime.BackgroundPrefetchBudget;
import ml.melun.mangaview.runtime.PerformanceMonitor;
import ml.melun.mangaview.runtime.AppDispatchers;
import ml.melun.mangaview.runtime.PerfTrace;

import static ml.melun.mangaview.MainApplication.getHttpClient;
import static ml.melun.mangaview.MainApplication.p;
import static ml.melun.mangaview.Utils.episodeIntent;
import static ml.melun.mangaview.Utils.openViewerPrepared;
import static ml.melun.mangaview.Utils.showCaptchaPopup;
import static ml.melun.mangaview.Utils.viewerIntent;
import static ml.melun.mangaview.activity.CaptchaActivity.RESULT_CAPTCHA;
import static ml.melun.mangaview.mangaview.MTitle.base_comic;

public class TagSearchActivity extends AppCompatActivity {
    private static final int THUMBNAIL_PRELOAD_AHEAD = 6;
    private static final int THUMBNAIL_PRELOAD_DELAY_MS = 80;
    private static final int EPISODE_SNAPSHOT_PREFETCH_AHEAD = 3;
    private static final int EPISODE_SNAPSHOT_PREFETCH_DELAY_MS = 260;
    private static final int EPISODE_SNAPSHOT_PREFETCH_ACTIVE_LIMIT = 2;
    private static final int EPISODE_SNAPSHOT_BACKGROUND_LIMIT = 24;
    private static final int LOAD_MORE_THRESHOLD = 18;
    RecyclerView searchResult;
    int mode;
    String query;
    TitleAdapter adapter;
    UpdatedAdapter uadapter;
    Context context;
    Search search;
    UpdatedList updated;
    TextView noresult;
    TextView resultMetaTitle;
    TextView resultMetaHint;
    View statusFilters;
    TextView filterAll;
    TextView filterOngoing;
    TextView filterCompleted;
    String resultLabel;
    String statusFilter = "";
    SwipyRefreshLayout swipe;
    Bookmark bookmark;
    int baseMode;
    LoadOperation loadTask;
    boolean destroyed = false;
    Runnable thumbnailPreloadRunnable;
    long searchFirstStartedAt = 0L;
    final Handler touchHandler = new Handler(Looper.getMainLooper());
    int touchSlop = 8;
    int listScrollState = RecyclerView.SCROLL_STATE_IDLE;
    float touchDownX = 0f;
    float touchDownY = 0f;
    long touchDownAt = 0L;
    boolean touchMoved = false;
    boolean touchLongPressed = false;
    boolean touchOnResume = false;
    int touchPosition = RecyclerView.NO_POSITION;
    View touchChild;
    View touchAnchor;
    Runnable touchLongPressRunnable;
    Runnable episodeSnapshotPreloadRunnable;
    final Set<String> requestedEpisodeSnapshots = new HashSet<>();
    final Deque<Title> episodeSnapshotQueue = new ArrayDeque<>();
    int activeEpisodeSnapshotPrefetches = 0;


    @Override
    protected void onCreate(Bundle savedInstanceState) {
        if(p.getDarkTheme()) setTheme(R.style.AppThemeDark);
        super.onCreate(savedInstanceState);
        PerformanceMonitor.attach(this);
        PerformanceMonitor.screen("search");
        setContentView(R.layout.activity_tag_search);
        if(!p.getDarkTheme()) {
            getWindow().setStatusBarColor(ContextCompat.getColor(this, R.color.appSurface));
            getWindow().setNavigationBarColor(ContextCompat.getColor(this, android.R.color.white));
            int flags = View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR;
            if(Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                flags |= View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR;
            getWindow().getDecorView().setSystemUiVisibility(flags);
        }
        context = this;
        Toolbar toolbar = this.findViewById(R.id.tagSearchToolbar);
        setSupportActionBar(toolbar);
        searchResult = this.findViewById(R.id.tagSearchResult);
        noresult = this.findViewById(R.id.tagSearchNoResult);
        resultMetaTitle = this.findViewById(R.id.tagSearchMetaTitle);
        resultMetaHint = this.findViewById(R.id.tagSearchMetaHint);
        statusFilters = this.findViewById(R.id.tagSearchStatusFilters);
        filterAll = this.findViewById(R.id.tagSearchFilterAll);
        filterOngoing = this.findViewById(R.id.tagSearchFilterOngoing);
        filterCompleted = this.findViewById(R.id.tagSearchFilterCompleted);
        LinearLayoutManager lm = new NpaLinearLayoutManager(context);
        searchResult.setLayoutManager(lm);
        searchResult.setHasFixedSize(true);
        searchResult.setItemViewCacheSize(12);
        searchResult.setItemAnimator(null);
        searchResult.setOverScrollMode(View.OVER_SCROLL_NEVER);
        touchSlop = ViewConfiguration.get(context).getScaledTouchSlop();
        searchResult.addOnItemTouchListener(new RecyclerView.SimpleOnItemTouchListener() {
            @Override
            public boolean onInterceptTouchEvent(@NonNull RecyclerView rv, @NonNull MotionEvent e) {
                handleTitleTouch(e);
                return false;
            }

            @Override
            public void onTouchEvent(@NonNull RecyclerView rv, @NonNull MotionEvent e) {
                handleTitleTouch(e);
            }
        });
        searchResult.addOnScrollListener(new RecyclerView.OnScrollListener() {
            @Override
            public void onScrollStateChanged(@NonNull RecyclerView recyclerView, int newState) {
                super.onScrollStateChanged(recyclerView, newState);
                listScrollState = newState;
                if(newState != RecyclerView.SCROLL_STATE_IDLE)
                    cancelTitleTouchClick();
                if(isFinishing() || destroyed)
                    return;
                PerformanceMonitor.phase(newState == RecyclerView.SCROLL_STATE_IDLE ? "idle" : "scrolling");
                if(newState == RecyclerView.SCROLL_STATE_IDLE)
                    PerformanceMonitor.reportNow("tag_search_scroll_idle");
                scheduleThumbnailPreload();
                scheduleEpisodeSnapshotPreload();
                maybeLoadMoreSearchResults();
            }

            @Override
            public void onScrolled(@NonNull RecyclerView recyclerView, int dx, int dy) {
                super.onScrolled(recyclerView, dx, dy);
                if(isFinishing() || destroyed)
                    return;
                scheduleThumbnailPreload();
                maybeLoadMoreSearchResults();
            }
        });
        Intent i = getIntent();
        query = i.getStringExtra("query");
        mode = i.getIntExtra("mode",0);
        String title = i.getStringExtra("title");
        swipe = this.findViewById(R.id.tagSearchSwipe);
        baseMode = i.getIntExtra("baseMode", base_comic);

        ActionBar ab = getSupportActionBar();
        switch(mode){
            case 0:
                break;
            case 1:
                resultLabel = "작가: " + query;
                ab.setTitle(resultLabel);
                break;
            case 2:
                resultLabel = "태그: " + query;
                ab.setTitle(resultLabel);
                break;
            case 3:
            case 4:
                resultLabel = "검색 결과";
                ab.setTitle(resultLabel);
                break;
            case 5:
                resultLabel = "최근 추가됨";
                ab.setTitle(resultLabel);
                break;
            case 6:
                resultLabel = "검색 결과";
                ab.setTitle(resultLabel);
                break;
            case 7:
                resultLabel = "북마크";
                ab.setTitle(resultLabel);
                break;
        }

        if(mode == 8) {
            resultLabel = title == null ? "분류 결과" : title;
            ab.setTitle(resultLabel);
        }
        setupStatusFilters();
        updateResultMeta();
        ab.setDisplayHomeAsUpEnabled(true);
        swipe.setRefreshing(true);

        if(mode == 5) {
            uadapter = new UpdatedAdapter(context);
            updated = new UpdatedList(p.getBaseMode());
            startLoad(new getUpdated());
            swipe.setOnRefreshListener(direction -> {
                 if (!updated.isLast()) {
                    startLoad(new getUpdated());
                } else swipe.setRefreshing(false);
            });

        }else if(mode == 7){
            adapter = new TitleAdapter(context);
            bookmark = new Bookmark();
            startLoad(new getBookmarks());
            swipe.setOnRefreshListener(direction -> {
                if (bookmark.isLast()) {
                    startLoad(new getBookmarks());
                } else swipe.setRefreshing(false);
            });

        }else {
            adapter = new TitleAdapter(context);
            adapter.setDeferThumbnails(true);
            search = MangaRepository.createSearch(query,mode,baseMode);
            searchFirstStartedAt = PerfTrace.start("tag_search_first_result_ms");
            startLoad(new searchManga());
            swipe.setOnRefreshListener(direction -> {
                if (!search.isLast()) {
                    startLoad(new searchManga());
                } else swipe.setRefreshing(false);
            });
        }
    }

    private void setupStatusFilters() {
        boolean show = isNtkCombinedGenreResult();
        if(statusFilters != null)
            statusFilters.setVisibility(show ? View.VISIBLE : View.GONE);
        View meta = findViewById(R.id.tagSearchMeta);
        if(meta != null) {
            ViewGroup.LayoutParams params = meta.getLayoutParams();
            params.height = dp(show ? 74 : 48);
            meta.setLayoutParams(params);
        }
        if(!show)
            return;
        filterAll.setOnClickListener(v -> applyStatusFilter(""));
        filterOngoing.setOnClickListener(v -> applyStatusFilter("연재"));
        filterCompleted.setOnClickListener(v -> applyStatusFilter("완결"));
        updateStatusFilterChips();
    }

    private boolean isNtkCombinedGenreResult() {
        return mode == 8 && query != null && query.startsWith("/ntk-genre?");
    }

    private int dp(int value) {
        return (int) (value * getResources().getDisplayMetrics().density + 0.5f);
    }

    private void applyStatusFilter(String value) {
        statusFilter = value == null ? "" : value;
        if(adapter != null)
            adapter.setNtkStatusFilter(statusFilter);
        updateStatusFilterChips();
        updateResultMeta();
        if(noresult != null && adapter != null)
            noresult.setVisibility(adapter.getItemCount() > 0 ? View.GONE : View.VISIBLE);
    }

    private void updateStatusFilterChips() {
        updateStatusFilterChip(filterAll, statusFilter.length() == 0);
        updateStatusFilterChip(filterOngoing, "연재".equals(statusFilter));
        updateStatusFilterChip(filterCompleted, "완결".equals(statusFilter));
    }

    private void updateStatusFilterChip(TextView view, boolean selected) {
        if(view == null)
            return;
        view.setTextColor(ContextCompat.getColor(context, selected ? R.color.appAccent : R.color.appTextSecondary));
    }

    private void handleTitleTouch(MotionEvent event) {
        if(event == null || searchResult == null || adapter == null)
            return;
        switch(event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                beginTitleTouch(event);
                break;
            case MotionEvent.ACTION_MOVE:
                if(touchPosition != RecyclerView.NO_POSITION && movedPastTouchSlop(event))
                    cancelTitleTouchClick();
                break;
            case MotionEvent.ACTION_UP:
                finishTitleTouch(event);
                break;
            case MotionEvent.ACTION_CANCEL:
                resetTitleTouch();
                break;
        }
    }

    private void beginTitleTouch(MotionEvent event) {
        resetTitleTouch();
        touchDownX = event.getX();
        touchDownY = event.getY();
        touchDownAt = SystemClock.uptimeMillis();
        touchChild = searchResult.findChildViewUnder(touchDownX, touchDownY);
        if(touchChild == null)
            return;
        touchPosition = searchResult.getChildAdapterPosition(touchChild);
        if(touchPosition == RecyclerView.NO_POSITION) {
            resetTitleTouch();
            return;
        }
        View resume = resumeButtonFor(touchChild);
        touchOnResume = isTouchInsideDescendant(touchChild, resume, touchDownX, touchDownY);
        touchAnchor = touchOnResume && resume != null ? resume : touchChild;
        scheduleTitleLongPress(touchPosition, touchAnchor);
    }

    private void finishTitleTouch(MotionEvent event) {
        touchHandler.removeCallbacksAndMessages(null);
        if(touchPosition == RecyclerView.NO_POSITION) {
            resetTitleTouch();
            return;
        }
        boolean moved = touchMoved || movedPastTouchSlop(event);
        boolean longPressWindow = SystemClock.uptimeMillis() - touchDownAt >= ViewConfiguration.getLongPressTimeout();
        int position = touchPosition;
        boolean resumeTap = touchOnResume;
        boolean canClick = !moved
                && !touchLongPressed
                && !longPressWindow
                && listScrollState == RecyclerView.SCROLL_STATE_IDLE;
        resetTitleTouchStateOnly();
        if(!canClick)
            return;
        if(resumeTap)
            adapter.performResumeClick(position);
        else
            adapter.performItemClick(position);
    }

    private void scheduleTitleLongPress(int position, View anchor) {
        touchLongPressRunnable = () -> {
            if(touchMoved
                    || touchLongPressed
                    || listScrollState != RecyclerView.SCROLL_STATE_IDLE
                    || touchPosition != position
                    || adapter == null)
                return;
            touchLongPressed = adapter.performItemLongClick(anchor == null ? searchResult : anchor, position);
        };
        touchHandler.postDelayed(touchLongPressRunnable, ViewConfiguration.getLongPressTimeout());
    }

    private void cancelTitleTouchClick() {
        touchMoved = true;
        touchHandler.removeCallbacksAndMessages(null);
    }

    private void resetTitleTouch() {
        touchHandler.removeCallbacksAndMessages(null);
        resetTitleTouchStateOnly();
    }

    private void resetTitleTouchStateOnly() {
        touchMoved = false;
        touchLongPressed = false;
        touchOnResume = false;
        touchPosition = RecyclerView.NO_POSITION;
        touchChild = null;
        touchAnchor = null;
        touchLongPressRunnable = null;
        touchDownAt = 0L;
    }

    private boolean movedPastTouchSlop(MotionEvent event) {
        if(event == null)
            return false;
        return Math.abs(event.getX() - touchDownX) > touchSlop
                || Math.abs(event.getY() - touchDownY) > touchSlop;
    }

    private View resumeButtonFor(View child) {
        return child == null ? null : child.findViewById(R.id.epsButton);
    }

    private boolean isTouchInsideDescendant(View child, View descendant, float recyclerX, float recyclerY) {
        if(child == null || descendant == null || descendant.getVisibility() != View.VISIBLE)
            return false;
        if(!(child instanceof ViewGroup))
            return false;
        Rect rect = new Rect(0, 0, descendant.getWidth(), descendant.getHeight());
        ((ViewGroup) child).offsetDescendantRectToMyCoords(descendant, rect);
        int childX = Math.round(recyclerX - child.getLeft());
        int childY = Math.round(recyclerY - child.getTop());
        return rect.contains(childX, childY);
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

    private void startLoad(LoadOperation task) {
        if(loadTask != null) {
            swipe.setRefreshing(true);
            return;
        }
        loadTask = task;
        swipe.setRefreshing(true);
        task.start();
    }

    private boolean prepareLoadResult(LoadOperation task) {
        if(loadTask != task || destroyed || isFinishing())
            return false;
        loadTask = null;
        return true;
    }

    private void clearLoad(LoadOperation task) {
        if(loadTask == task)
            loadTask = null;
        if(swipe != null)
            swipe.setRefreshing(false);
    }

    private interface LoadOperation {
        void start();
        void cancel();
    }

    public boolean onOptionsItemSelected(MenuItem item){
        if (item.getItemId() == android.R.id.home) {
            finish();
            return true;
        }
        return super.onOptionsItemSelected(item);
    }


    private class getBookmarks implements LoadOperation {
        private MangaRepository.Cancellation cancellation;
        private AppDispatchers.TaskHandle handle;
        private volatile boolean cancelled;

        public void start() {
            handle = AppDispatchers.submitUserAction(() -> {
                Integer result = load();
                AppDispatchers.runOnMain(() -> finish(result));
            });
        }

        private void finish(Integer integer) {
            if(cancelled)
                return;
            if(!prepareLoadResult(this))
                return;
            if(integer != 0){
                showCaptchaPopup(context, p);
            }
            if(adapter.getItemCount()==0) {
                adapter.addData(bookmark.getResult());
                searchResult.setAdapter(adapter);
                adapter.setClickListener(new TitleAdapter.ItemClickListener() {
                    @Override
                    public void onResumeClick(int position, int id) {
                        Title title = adapter.getItem(position);
                        Manga manga = new Manga(id,"","", title == null ? baseMode : title.getBaseMode());
                        if(title != null) {
                            manga.setTitle(title);
                            manga.setTitleId(title.getId());
                            ArrayList<Manga> episodes = Utils.snapshotEpisodes(title);
                            if(episodes.size() > 0)
                                manga.setEps(episodes);
                        }
                        openViewerPrepared(context, manga, 0, false, true, false, title, true);
                    }

                    @Override
                    public void onItemClick(int position) {
                        // start intent : Episode viewer
                        Title selected = adapter.getItem(position);
                        enqueueEpisodeSnapshot(selected, true);
                        drainEpisodeSnapshotQueue();
                        Intent episodeView = episodeIntent(context, selected);
                        episodeView.putExtra("online", true);
                        startActivity(episodeView);
                    }

                    @Override
                    public void onLongClick(View view, int position) {
                        popup(view, position, adapter.getItem(position), 0);
                    }
                });
            }else{
                adapter.addData(bookmark.getResult());
            }

            if(adapter.getItemCount()>0) {
                noresult.setVisibility(View.GONE);
                finishInitialSearchTraceIfNeeded();
                releaseDeferredSearchThumbnails();
            }else{
                noresult.setVisibility(View.VISIBLE);
                if(adapter != null)
                    adapter.setDeferThumbnails(false);
            }
            updateResultMeta();
            scheduleThumbnailPreload();
            swipe.setRefreshing(false);
            searchResult.post(TagSearchActivity.this::maybeLoadMoreSearchResults);
        }

        private Integer load() {
            cancellation = MangaRepository.cancellation();
            try {
                return MangaRepository.fetchBookmark(bookmark, cancellation);
            } catch (Exception e) {
                if(!cancelled)
                    ml.melun.mangaview.report.CrashReporter.record(e);
                return 1;
            }
        }

        public void cancel() {
            cancelled = true;
            if(cancellation != null)
                cancellation.cancel();
            if(handle != null)
                handle.cancel();
            clearLoad(this);
        }
    }


    private class searchManga implements LoadOperation {
        private MangaRepository.Cancellation cancellation;
        private AppDispatchers.TaskHandle handle;
        private volatile boolean cancelled;
        private Exception searchFailure;
        private long loadStartedAt;

        public void start(){
            long queuedAt = PerfTrace.start("tag_search_task_queue_ms");
            handle = AppDispatchers.submitSearch(() -> {
                PerfTrace.end("tag_search_task_queue_ms", queuedAt);
                Integer result = load();
                AppDispatchers.runOnMain(() -> finish(result));
            });
        }

        private Integer load(){
            cancellation = MangaRepository.cancellation();
            loadStartedAt = System.currentTimeMillis();
            try {
                return MangaRepository.search(search, cancellation);
            } catch (Exception e) {
                searchFailure = e;
                if(!cancelled && MangaRepository.shouldReportSearchFailure(e))
                    ml.melun.mangaview.report.CrashReporter.record(e);
                return 1;
            }
        }

        private void finish(Integer res){
            long finishStartedAt = PerfTrace.start("tag_search_finish_main_ms");
            if(cancelled)
                return;
            if(!prepareLoadResult(this))
                return;
            if(res == null)
                res = 1;
            if(shouldOpenCaptchaAfterSearchFailure(res, searchFailure, loadStartedAt)){
                showCaptchaPopup(context, p);
            }
            long adapterStartedAt = PerfTrace.start("tag_search_adapter_main_ms");
            if(adapter.getItemCount()==0) {
                adapter.addData(search.getResult());
                searchResult.setAdapter(adapter);
                adapter.setClickListener(new TitleAdapter.ItemClickListener() {
                    @Override
                    public void onResumeClick(int position, int id) {
                        Title title = adapter.getItem(position);
                        Manga manga = new Manga(id,"","", search.getBaseMode());
                        if(title != null) {
                            manga.setTitle(title);
                            manga.setTitleId(title.getId());
                            ArrayList<Manga> episodes = Utils.snapshotEpisodes(title);
                            if(episodes.size() > 0)
                                manga.setEps(episodes);
                        }
                        openViewerPrepared(context, manga, 0, false, true, false, title, true);
                    }

                    @Override
                    public void onItemClick(int position) {
                        // start intent : Episode viewer
                        Title selected = adapter.getItem(position);
                        Intent episodeView = episodeIntent(context, selected);
                        episodeView.putExtra("online", true);
                        startActivity(episodeView);
                    }

                    @Override
                    public void onLongClick(View view, int position) {
                        popup(view, position, adapter.getItem(position), 0);
                    }
                });
            }else{
                adapter.addData(search.getResult());
            }
            PerfTrace.end("tag_search_adapter_main_ms", adapterStartedAt);
            if(statusFilter.length() > 0)
                adapter.setNtkStatusFilter(statusFilter);

            long chromeStartedAt = PerfTrace.start("tag_search_chrome_main_ms");
            if(adapter.getItemCount()>0) {
                noresult.setVisibility(View.GONE);
                finishInitialSearchTraceIfNeeded();
                releaseDeferredSearchThumbnails();
            }else{
                if(res != 0 && !shouldOpenCaptchaAfterSearchFailure(res, searchFailure, loadStartedAt))
                    noresult.setText("\uacb0\uacfc\ub97c \ubd88\ub7ec\uc624\uc9c0 \ubabb\ud588\uc2b5\ub2c8\ub2e4.\n\ub124\ud2b8\uc6cc\ud06c \ub610\ub294 \uc0ac\uc774\ud2b8 \uc8fc\uc18c\ub97c \ud655\uc778\ud55c \ub4a4 \ub2e4\uc2dc \uc2dc\ub3c4\ud574 \uc8fc\uc138\uc694.");
                else
                    noresult.setText("\uac80\uc0c9 \uacb0\uacfc\uac00 \uc5c6\uc2b5\ub2c8\ub2e4");
                noresult.setVisibility(View.VISIBLE);
                adapter.setDeferThumbnails(false);
            }
            updateVirtualScrollbar();
            updateResultMeta();
            scheduleThumbnailPreload();
            scheduleEpisodeSnapshotPreload();
            swipe.setRefreshing(false);
            PerfTrace.end("tag_search_chrome_main_ms", chromeStartedAt);
            PerfTrace.end("tag_search_finish_main_ms", finishStartedAt);
        }

        public void cancel() {
            cancelled = true;
            if(cancellation != null)
                cancellation.cancel();
            if(handle != null)
                handle.cancel();
            clearLoad(this);
        }
    }

    private static boolean shouldOpenCaptchaAfterSearchFailure(int result, Exception failure, long loadStartedAt) {
        if(result == 0)
            return false;
        if(failure != null)
            return MangaRepository.shouldReportSearchFailure(failure);
        return getHttpClient().hasCloudflareChallengeSince(loadStartedAt);
    }

    private class getUpdated implements LoadOperation {
        private MangaRepository.Cancellation cancellation;
        private AppDispatchers.TaskHandle handle;
        private volatile boolean cancelled;

        public void start(){
            handle = AppDispatchers.submitUserAction(() -> {
                String result = load();
                AppDispatchers.runOnMain(() -> finish(result));
            });
        }

        private String load(){
            cancellation = MangaRepository.cancellation();
            try {
                MangaRepository.loadUpdates(updated, cancellation);
                return null;
            } catch (Exception e) {
                if(!cancelled)
                    ml.melun.mangaview.report.CrashReporter.record(e);
                return null;
            }
        }

        private void finish(String res){
            if(cancelled)
                return;
            if(!prepareLoadResult(this))
                return;
            ArrayList<UpdatedManga> result = updated.getResult();
            if(result == null)
                result = new ArrayList<>();
            if(result.size() == 0 && uadapter.getItemCount() == 0){
                //error
                showCaptchaPopup(context, p);
            }
            if(uadapter.getItemCount()==0) {
                uadapter.addData(result);
                searchResult.setAdapter(uadapter);
                uadapter.setOnClickListener(new UpdatedAdapter.onclickListener() {
                    @Override
                    public void onEpsClick(Title t) {
                        Intent eps = episodeIntent(context, t);
                        eps.putExtra("online", true);
                        startActivity(eps);
                    }

                    @Override
                    public void onClick(Manga m) {
                        //open viewer
                        openViewerPrepared(context, m, 0, false, true, false, m == null ? null : m.getTitle(), true);
                    }
                });
            }else{
                uadapter.addData(result);
            }

            if(uadapter.getItemCount()>0) {
                noresult.setVisibility(View.GONE);
            }else{
                noresult.setVisibility(View.VISIBLE);
            }
            updateResultMeta();
            scheduleThumbnailPreload();
            scheduleEpisodeSnapshotPreload();
            swipe.setRefreshing(false);
        }

        public void cancel() {
            cancelled = true;
            if(cancellation != null)
                cancellation.cancel();
            if(handle != null)
                handle.cancel();
            clearLoad(this);
        }
    }
    void popup(View view, final int position, final Title title, final int m){
        PopupMenu popup = new PopupMenu(TagSearchActivity.this, view);
        //Inflating the Popup using xml file
        popup.getMenuInflater()
                .inflate(R.menu.title_options, popup.getMenu());
        popup.getMenu().removeItem(R.id.del);
        popup.getMenu().findItem(R.id.favAdd).setVisible(true);
        popup.getMenu().findItem(R.id.favDel).setVisible(true);
        if(p.findFavorite(title)>-1) popup.getMenu().removeItem(R.id.favAdd);
        else popup.getMenu().removeItem(R.id.favDel);


        //registering popup with OnMenuItemClickListener
        popup.setOnMenuItemClickListener(item -> {
            switch(item.getItemId()){
                case R.id.del:
                    break;
                case R.id.favAdd:
                case R.id.favDel:
                    //toggle favorite
                    p.toggleFavorite(title,0);
                    break;
            }
            return true;
        });
        popup.show(); //showing popup menu
    }

    private void scheduleThumbnailPreload() {
        if(searchResult == null)
            return;
        if(thumbnailPreloadRunnable != null)
            searchResult.removeCallbacks(thumbnailPreloadRunnable);
        thumbnailPreloadRunnable = this::preloadVisibleThumbnails;
        searchResult.postDelayed(thumbnailPreloadRunnable, THUMBNAIL_PRELOAD_DELAY_MS);
    }

    private void preloadVisibleThumbnails() {
        if(searchResult == null || destroyed || isFinishing())
            return;
        if(searchResult.getScrollState() == RecyclerView.SCROLL_STATE_SETTLING)
            return;
        RecyclerView.LayoutManager manager = searchResult.getLayoutManager();
        if(!(manager instanceof LinearLayoutManager))
            return;
        LinearLayoutManager layoutManager = (LinearLayoutManager) manager;
        int first = layoutManager.findFirstVisibleItemPosition();
        int last = layoutManager.findLastVisibleItemPosition();
        if(first == RecyclerView.NO_POSITION)
            first = 0;
        int visibleCount = last >= first ? last - first + 1 : 8;
        int ahead = searchResult.getScrollState() == RecyclerView.SCROLL_STATE_IDLE ? THUMBNAIL_PRELOAD_AHEAD : Math.min(2, THUMBNAIL_PRELOAD_AHEAD);
        int preloadCount = visibleCount + ahead;
        if(adapter != null)
            adapter.preloadThumbnails(first, preloadCount);
        if(uadapter != null)
            uadapter.preloadThumbnails(first, preloadCount);
    }

    private void scheduleEpisodeSnapshotPreload() {
        if(searchResult == null || adapter == null || mode != 8)
            return;
        if(episodeSnapshotPreloadRunnable != null)
            searchResult.removeCallbacks(episodeSnapshotPreloadRunnable);
        episodeSnapshotPreloadRunnable = () -> {
            enqueueVisibleEpisodeSnapshots();
            enqueueNearbyEpisodeSnapshots();
            drainEpisodeSnapshotQueue();
        };
        searchResult.postDelayed(episodeSnapshotPreloadRunnable, EPISODE_SNAPSHOT_PREFETCH_DELAY_MS);
    }

    private void enqueueVisibleEpisodeSnapshots() {
        if(searchResult == null || adapter == null || destroyed || isFinishing())
            return;
        if(searchResult.getScrollState() != RecyclerView.SCROLL_STATE_IDLE)
            return;
        RecyclerView.LayoutManager manager = searchResult.getLayoutManager();
        if(!(manager instanceof LinearLayoutManager))
            return;
        LinearLayoutManager layoutManager = (LinearLayoutManager) manager;
        int first = layoutManager.findFirstVisibleItemPosition();
        int last = layoutManager.findLastVisibleItemPosition();
        if(first == RecyclerView.NO_POSITION)
            first = 0;
        if(last < first)
            last = first + EPISODE_SNAPSHOT_PREFETCH_AHEAD;
        int end = Math.min(adapter.getItemCount() - 1, last + EPISODE_SNAPSHOT_PREFETCH_AHEAD);
        for(int i = end; i >= first; i--)
            enqueueEpisodeSnapshot(adapter.getItem(i), true);
    }

    private void enqueueNearbyEpisodeSnapshots() {
        if(searchResult == null || adapter == null || destroyed || isFinishing())
            return;
        RecyclerView.LayoutManager manager = searchResult.getLayoutManager();
        int anchor = 0;
        if(manager instanceof LinearLayoutManager) {
            int first = ((LinearLayoutManager) manager).findFirstVisibleItemPosition();
            if(first != RecyclerView.NO_POSITION)
                anchor = Math.max(0, first);
        }
        int count = adapter.getItemCount();
        int end = Math.min(count, anchor + EPISODE_SNAPSHOT_BACKGROUND_LIMIT);
        for(int i = anchor; i < end; i++)
            enqueueEpisodeSnapshot(adapter.getItem(i), false);
    }

    private boolean enqueueEpisodeSnapshot(Title item, boolean priority) {
        if(item == null || item.getId() <= 0)
            return false;
        p.ensureSourceSiteForTitle(item);
        if(!shouldPrefetchEpisodeSnapshot(item.getSourceSite()))
            return false;
        String key = episodeSnapshotRequestKey(item);
        boolean alreadyRequested;
        synchronized (requestedEpisodeSnapshots) {
            alreadyRequested = requestedEpisodeSnapshots.contains(key);
            if(!alreadyRequested) {
                requestedEpisodeSnapshots.add(key);
                trimRequestedEpisodeSnapshots();
            }
        }
        if(alreadyRequested && !priority)
            return false;
        synchronized (episodeSnapshotQueue) {
            Title queued = new Title(item);
            if(priority) {
                removeQueuedEpisodeSnapshotLocked(key);
                episodeSnapshotQueue.addFirst(queued);
            } else
                episodeSnapshotQueue.addLast(new Title(item));
        }
        return !alreadyRequested;
    }

    private void removeQueuedEpisodeSnapshotLocked(String key) {
        if(key == null || key.length() == 0)
            return;
        for(java.util.Iterator<Title> iterator = episodeSnapshotQueue.iterator(); iterator.hasNext();) {
            Title queued = iterator.next();
            if(queued != null && key.equals(episodeSnapshotRequestKey(queued))) {
                iterator.remove();
                return;
            }
        }
    }

    private void drainEpisodeSnapshotQueue() {
        if(destroyed || isFinishing())
            return;
        while(activeEpisodeSnapshotPrefetches < EPISODE_SNAPSHOT_PREFETCH_ACTIVE_LIMIT) {
            Title target;
            synchronized (episodeSnapshotQueue) {
                target = episodeSnapshotQueue.pollFirst();
            }
            if(target == null)
                return;
            String budgetKey = episodeSnapshotRequestKey(target);
            if(BackgroundPrefetchBudget.isNonCriticalPrefetchSuppressed()) {
                synchronized (episodeSnapshotQueue) {
                    episodeSnapshotQueue.addFirst(target);
                }
                return;
            }
            if(!BackgroundPrefetchBudget.tryAcquireEpisodeSnapshot(budgetKey)) {
                synchronized (episodeSnapshotQueue) {
                    episodeSnapshotQueue.addFirst(target);
                }
                return;
            }
            activeEpisodeSnapshotPrefetches++;
            Context appContext = getApplicationContext();
            boolean scheduled = AppDispatchers.tryRunIo(() -> {
                try {
                    fetchAndStoreEpisodeSnapshot(appContext, target);
                } finally {
                    BackgroundPrefetchBudget.releaseEpisodeSnapshot(budgetKey);
                    AppDispatchers.runOnMain(() -> {
                        activeEpisodeSnapshotPrefetches = Math.max(0, activeEpisodeSnapshotPrefetches - 1);
                        drainEpisodeSnapshotQueue();
                    });
                }
            });
            if(!scheduled) {
                BackgroundPrefetchBudget.releaseEpisodeSnapshot(budgetKey);
                activeEpisodeSnapshotPrefetches = Math.max(0, activeEpisodeSnapshotPrefetches - 1);
                synchronized (episodeSnapshotQueue) {
                    episodeSnapshotQueue.addFirst(target);
                }
                return;
            }
        }
    }

    private void fetchAndStoreEpisodeSnapshot(Context appContext, Title target) {
        if(appContext == null || target == null)
            return;
        try {
            int result = MangaRepository.fetchEpisodesBackground(target);
            List<Manga> episodes = Utils.snapshotEpisodes(target);
            if(result == Title.LOAD_OK && episodes != null && episodes.size() > 0)
                CacheFileStore.write(appContext, episodeSnapshotKey(target), new Gson().toJson(new EpisodeSnapshot(episodes)));
            else if(result == Title.LOAD_ERROR)
                BackgroundPrefetchBudget.recordEpisodeSnapshotFailure();
        } catch (Exception e) {
            BackgroundPrefetchBudget.recordEpisodeSnapshotFailure();
            ml.melun.mangaview.report.CrashReporter.record(e);
        }
    }

    private boolean shouldPrefetchEpisodeSnapshot(String sourceSite) {
        if(sourceSite == null || sourceSite.trim().length() == 0)
            return true;
        String source = sourceSite.trim().toLowerCase(Locale.ROOT);
        boolean ntk = p.isNtkSite();
        if("ntk".equals(source))
            return ntk;
        if("wfwf".equals(source) || source.startsWith("wolf"))
            return !ntk;
        return true;
    }

    private String episodeSnapshotRequestKey(Title title) {
        return title.getSourceSite() + ":" + title.getBaseMode() + ":" + title.getId() + ":" + title.getPath();
    }

    private void trimRequestedEpisodeSnapshots() {
        if(requestedEpisodeSnapshots.size() > 96)
            requestedEpisodeSnapshots.clear();
    }

    private String episodeSnapshotKey(Title title) {
        return EpisodeSnapshotCache.key(title, p != null && p.isNtkSite());
    }

    private static class EpisodeSnapshot {
        long savedAt;
        ArrayList<Manga> episodes;

        EpisodeSnapshot(List<Manga> episodes) {
            this.savedAt = System.currentTimeMillis();
            this.episodes = new ArrayList<>(episodes);
        }
    }

    private void maybeLoadMoreSearchResults() {
        if(mode != 8 || search == null || searchResult == null || loadTask != null || destroyed || isFinishing())
            return;
        if(search.isLast())
            return;
        RecyclerView.LayoutManager manager = searchResult.getLayoutManager();
        if(!(manager instanceof LinearLayoutManager))
            return;
        int count = adapter == null ? 0 : adapter.getItemCount();
        if(count == 0)
            return;
        LinearLayoutManager layoutManager = (LinearLayoutManager) manager;
        int lastVisible = layoutManager.findLastVisibleItemPosition();
        if(lastVisible == RecyclerView.NO_POSITION)
            lastVisible = 0;
        if(lastVisible >= count - LOAD_MORE_THRESHOLD)
            startLoad(new searchManga());
    }

    private void updateVirtualScrollbar() {
        if(!(searchResult instanceof StableScrollbarRecyclerView) || search == null)
            return;
        ((StableScrollbarRecyclerView) searchResult).setVirtualItemCount(search.getVirtualResultCount());
    }

    private void finishInitialSearchTraceIfNeeded() {
        if(searchFirstStartedAt <= 0)
            return;
        PerfTrace.end("tag_search_first_result_ms", searchFirstStartedAt);
        searchFirstStartedAt = 0L;
    }

    private void releaseDeferredSearchThumbnails() {
        if(searchResult == null || adapter == null)
            return;
        searchResult.post(() -> {
            if(!destroyed && !isFinishing() && adapter != null)
                adapter.releaseDeferredThumbnails(searchResult);
        });
    }

    private void updateResultMeta() {
        if(resultMetaTitle == null || resultMetaHint == null)
            return;
        int loaded = adapter != null ? adapter.getItemCount() : (uadapter != null ? uadapter.getItemCount() : 0);
        int total = search == null ? 0 : search.getVirtualResultCount();
        String label;
        if(resultLabel != null) {
            label = resultLabel;
        } else if(mode == 5) {
            label = "최근 추가됨";
        } else if(mode == 7) {
            label = "북마크";
        } else {
            label = "검색 결과";
        }
        boolean combinedGenre = isNtkCombinedGenreResult();
        resultMetaTitle.setVisibility(combinedGenre ? View.GONE : View.VISIBLE);
        resultMetaTitle.setText(combinedGenre ? "" : label);
        if(combinedGenre && adapter != null) {
            total = search == null ? 0 : search.getNtkStatusTotalCount(statusFilter);
            if(total <= 0)
                total = statusFilter.length() > 0 ? adapter.getNtkStatusCount(statusFilter) : adapter.getUnfilteredItemCount();
            String prefix = statusFilter.length() > 0 ? statusFilter : "전체";
            if(total > loaded)
                resultMetaHint.setText(prefix + " " + loaded + "/" + total + "개 표시");
            else if(loaded > 0)
                resultMetaHint.setText(prefix + " " + loaded + "개 표시");
            else
                resultMetaHint.setText("결과 준비 중");
            return;
        }
        if(total > loaded)
            resultMetaHint.setText(loaded + "/" + total + "개 표시");
        else if(loaded > 0)
            resultMetaHint.setText(loaded + "개 표시");
        else
            resultMetaHint.setText("결과 준비 중");
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, @Nullable Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if(resultCode == RESULT_CAPTCHA){
            //captcha
            Intent restartIntent = new Intent(this, TagSearchActivity.class);
            Intent currentIntent = getIntent();
            if(currentIntent != null) {
                if(currentIntent.getExtras() != null)
                    restartIntent.putExtras(new Bundle(currentIntent.getExtras()));
                restartIntent.setData(currentIntent.getData());
            }
            if(Utils.safeStartActivity(context, restartIntent))
                finish();
        }
    }

    @Override
    protected void onDestroy() {
        destroyed = true;
        if(loadTask != null) {
            loadTask.cancel();
            loadTask = null;
        }
        synchronized (episodeSnapshotQueue) {
            episodeSnapshotQueue.clear();
        }
        if(searchResult != null && thumbnailPreloadRunnable != null)
            searchResult.removeCallbacks(thumbnailPreloadRunnable);
        if(searchResult != null && episodeSnapshotPreloadRunnable != null)
            searchResult.removeCallbacks(episodeSnapshotPreloadRunnable);
        super.onDestroy();
    }
}
