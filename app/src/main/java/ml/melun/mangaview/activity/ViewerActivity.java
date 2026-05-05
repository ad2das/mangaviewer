package ml.melun.mangaview.activity;

import android.content.Context;
import android.content.Intent;
import android.content.res.Configuration;
import android.graphics.Color;
import android.os.Handler;
import android.os.Looper;

import com.bumptech.glide.Glide;
import com.bumptech.glide.load.engine.DiskCacheStrategy;
import com.bumptech.glide.load.resource.bitmap.DownsampleStrategy;
import com.bumptech.glide.request.RequestOptions;
import com.bumptech.glide.request.target.Target;
import com.google.android.material.appbar.AppBarLayout;

import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;

import android.os.Bundle;

import androidx.constraintlayout.widget.ConstraintLayout;
import androidx.core.view.ViewCompat;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import android.text.InputType;
import android.view.KeyEvent;
import android.view.Menu;
import android.view.MotionEvent;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.TextView;


import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;

import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;

import ml.melun.mangaview.R;
import ml.melun.mangaview.interfaces.StringCallback;
import ml.melun.mangaview.ui.StripLayoutManager;
import ml.melun.mangaview.Utils;
import ml.melun.mangaview.adapter.CustomSpinnerAdapter;
import ml.melun.mangaview.adapter.StripAdapter;
import ml.melun.mangaview.ui.CustomSpinner;
import ml.melun.mangaview.mangaview.CustomHttpClient;
import ml.melun.mangaview.mangaview.Manga;
import ml.melun.mangaview.mangaview.Title;
import ml.melun.mangaview.model.PageItem;

import static ml.melun.mangaview.MainApplication.getHttpClient;
import static ml.melun.mangaview.MainApplication.p;
import static ml.melun.mangaview.Utils.getScreenSize;
import static ml.melun.mangaview.Utils.hideSpinnerDropDown;
import static ml.melun.mangaview.Utils.queueOfflineDownload;
import static ml.melun.mangaview.Utils.showCaptchaPopup;
import static ml.melun.mangaview.Utils.showPopup;
import static ml.melun.mangaview.Utils.showTokiCaptchaPopup;
import static ml.melun.mangaview.activity.CaptchaActivity.RESULT_CAPTCHA;
import static ml.melun.mangaview.mangaview.Title.LOAD_CAPTCHA;
import static ml.melun.mangaview.mangaview.Title.LOAD_OK;

public class ViewerActivity extends AppCompatActivity {

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
    ImageButton commentBtn;
    ImageButton saveBtn;
    int width=0;
    Intent intent;
    boolean captchaChecked = false;
    CustomSpinner spinner;
    CustomSpinnerAdapter spinnerAdapter;
    InfiniteScrollCallback infiniteScrollCallback;
    LoadImagesJob loader;
    PrefetchImagesJob nextPrefetcher;
    final Handler mainHandler = new Handler(Looper.getMainLooper());
    final ExecutorService imageLoadExecutor = Executors.newFixedThreadPool(2);
    int episodeLoaderGeneration = 0;
    int nextPrefetchEpisodeId = -1;
    int nextPrefetchBaseMode = -1;
    boolean previousEpisodeBoundaryLoading = false;
    boolean nextEpisodeBoundaryLoading = false;
    boolean previousEpisodeBoundaryJumpPending = false;
    boolean nextEpisodeBoundaryJumpPending = false;
    private static final int NEXT_EPISODE_PRELOAD_LIMIT = 8;
    private static final int DATA_SAVE_NEXT_EPISODE_PRELOAD_LIMIT = 6;
    private static final int INITIAL_PRELOAD_AHEAD_COUNT = 12;
    private static final int NEXT_EPISODE_ATTACH_THRESHOLD = 16;
    private static final int DATA_SAVE_NEXT_EPISODE_ATTACH_THRESHOLD = 10;
    private static final int PREVIOUS_EPISODE_PULL_THRESHOLD_DP = 36;
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
        dark = p.getDarkTheme();
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_viewer);

        next = this.findViewById(R.id.toolbar_next);
        prev = this.findViewById(R.id.toolbar_previous);
        toolbar = this.findViewById(R.id.viewerToolbar);
        appbar = this.findViewById(R.id.viewerAppbar);
        toolbarTitle = this.findViewById(R.id.toolbar_title);
        appbarBottom = this.findViewById(R.id.viewerAppbarBottom);
        cut = this.findViewById(R.id.viewerBtn2);
        cut.setText("자동 분할");
        updateAutoCutButtonState();
        pageBtn = this.findViewById(R.id.viewerBtn1);
        pageBtn.setText("-/-");
        commentBtn = this.findViewById(R.id.commentButton);
        saveBtn = this.findViewById(R.id.viewerSaveButton);
        spinner = this.findViewById(R.id.toolbar_spinner);
        width = getScreenSize(getWindowManager().getDefaultDisplay());

        //initial padding setup
        appbar.setPadding(0, getStatusBarHeight(),0,0);
        getWindow().getDecorView().setBackgroundColor(Color.BLACK);


        ViewCompat.setOnApplyWindowInsetsListener(getWindow().getDecorView(), (view, windowInsetsCompat) -> {
            //This is where you get DisplayCutoutCompat
            int statusBarHeight = getStatusBarHeight();
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
                Manga target = curm.prevEp();
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
                        if (m.getImgs(context).size() > 0) {
                            insertMangaWhenIdle(m, ViewerActivity.this::isPreviousTargetStillExpected, () -> callback.prevLoaded(m));
                        } else {
                            callback.prevLoaded(m);
                        }
                    },false);
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
                Manga target = curm.nextEp();
                if(target != null) {
                    if(hasLoadedImages(target)) {
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
                        if (m.getImgs(context).size() > 0) {
                            appendMangaWhenIdle(m, ViewerActivity.this::isNextTargetStillExpected, () -> {
                                callback.nextLoaded(m);
                            });
                        } else {
                            callback.nextLoaded(m);
                        }
                    },false);
                    loader.start();
                    return target;
                }else{
                    callback.nextLoaded(null);
                    return null;
                }
            }

            @Override
            public void updateInfo(Manga m) {
                manga = m;
                updateIntent(m);
                refreshToolbar(m);
            }
        };

        this.findViewById(R.id.backButton).setOnClickListener(view -> onBackPressed());
        saveBtn.setOnClickListener(view -> saveCurrentEpisodeOffline());

        try {
            intent = getIntent();
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

            toolbarTitle.setText(manga.getName());

            strip = this.findViewById(R.id.strip);
            manager = new StripLayoutManager(this);
            manager.setOrientation(LinearLayoutManager.VERTICAL);
            strip.setItemViewCacheSize(4);
            spinnerAdapter = new CustomSpinnerAdapter(context);
            spinnerAdapter.setListener((m, i) -> {
                lockUi(true);
                spinner.setSelection(m);
                hideSpinnerDropDown(spinner);
                loadManga(m);

            });
            spinner.setAdapter(spinnerAdapter);
            strip.setLayoutManager(manager);

            if(intent.getBooleanExtra("recent",false)){
                Intent resultIntent = new Intent();
                setResult(RESULT_OK,resultIntent);
            }

            if(!manga.isOnline()){
                commentBtn.setVisibility(View.GONE);
                saveBtn.setVisibility(View.GONE);
            }
            
            loadManga(manga);
            strip.setItemAnimator(null);
            strip.setOverScrollMode(View.OVER_SCROLL_NEVER);
            strip.setOnScrollListener(new RecyclerView.OnScrollListener() {
                @Override
                public void onScrollStateChanged(RecyclerView recyclerView, int newState) {
                    super.onScrollStateChanged(recyclerView, newState);
                    if(strip.getLayoutManager().getItemCount()>0 && newState == RecyclerView.SCROLL_STATE_DRAGGING && toolbarshow) {
                        toggleToolbar();
                    }
                    if(newState == RecyclerView.SCROLL_STATE_IDLE)
                        loadEpisodeAtBoundaryIfNeeded();
                    if(newState == RecyclerView.SCROLL_STATE_IDLE)
                        saveCurrentScrollBookmark();
                }

                @Override
                public void onScrolled(RecyclerView recyclerView, int dx, int dy) {
                    super.onScrolled(recyclerView, dx, dy);
                    loadEpisodeAtBoundaryIfNeeded();
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
            strip.setOnTouchListener((view, event) -> {
                handlePreviousEpisodePull(event);
                return false;
            });

        }catch(Exception e){
            e.printStackTrace();
        }

        next.setOnClickListener(v -> loadManga(manga.nextEp()));
        prev.setOnClickListener(v -> loadManga(manga.prevEp()));
        cut.setOnClickListener(v -> toggleAutoCut());

        pageBtn.setOnClickListener(v -> {
            PageItem current = getFocusedVisiblePage();
            if(current == null)
                return;
            AlertDialog.Builder alert;
            if(dark) alert = new AlertDialog.Builder(context,R.style.darkDialog);
            else alert = new AlertDialog.Builder(context);

            alert.setTitle("페이지 선택\n(1~"+current.manga.getImgs(context).size()+")");
            final EditText input = new EditText(context);
            input.setInputType(InputType.TYPE_CLASS_NUMBER);
            input.setRawInputType(Configuration.KEYBOARD_12KEY);
            alert.setView(input);
            alert.setPositiveButton("이동", (dialog, button) -> {
                //이동 시
                if (input.getText().length() > 0) {
                    int page = Integer.parseInt(input.getText().toString());
                    if (page < 1) page = 1;
                    if (page > current.manga.getImgs(context).size())
                        page = current.manga.getImgs(context).size();
                    manager.scrollToPage(new PageItem(page - 1, "", current.manga));
                    pageBtn.setText(page + "/" + current.manga.getImgs(context).size());
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

    void loadManga(Manga m, LoadMangaCallback callback){
        if(m == null)
            return;
        if(title != null)
            m.setTitle(title);
        this.manga = m;
        if(loader != null)
            loader.cancel();
        loader = new LoadImagesJob(m, callback,true);
        loader.start();
    }

    void loadManga(Manga m){
        if(m == null){
            showPopup(context, "오류", "만화를 불러 오던중 오류가 발생했습니다.", (dialog, which) -> ViewerActivity.this.finish(), dialog -> ViewerActivity.this.finish());
            return;
        }
        cancelActiveEpisodeLoader();
        cancelNextPrefetcher();
        if(stripAdapter!=null) stripAdapter.removeAll();
        if(m.isOnline()) {
            if(hasLoadedImages(m)) {
                setManga(m);
            } else {
                loadManga(m, m1 -> {
                    manga = m1;
                    setManga(m1);
                });
            }
        }else{
            //offline
            eps = title.getEps();
//            for(int i=0; i<eps.size(); i++){
//                eps.get(i).setNextEp(i>0 ? eps.get(i-1) : null);
//                eps.get(i).setPrevEp(i<eps.size()-1 ? eps.get(i+1) : null);
//            }
            m = eps.get(eps.indexOf(m));
            setManga(m);
        }
    }


    public void setManga(Manga m){
        try {
            lockUi(false);
            if(m.getImgs(context) == null || m.getImgs(context).size()==0) {
                showCaptchaPopup(m.getUrl(), context, p);
                return;
            }
            stripAdapter = new StripAdapter(context, m, autoCut, width,title, infiniteScrollCallback);
            preloadInitialViewerPages(m);

            refreshAdapter();
            bookmarkRefresh(m);
            scheduleFocusedPagePreload();
            refreshToolbar(m);
            updateIntent(m);
            prefetchNextEpisode(m);

        }catch (Exception e){
            Utils.showCaptchaPopup(m.getUrl(), context, e, p);
            e.printStackTrace();
        }
    }


    @Override
    public boolean dispatchKeyEvent(KeyEvent event) {
        int keyCode = event.getKeyCode();
        if(keyCode == p.getPrevPageKey() || keyCode == p.getNextPageKey()) {
            int index = manager.findFirstVisibleItemPosition();
            if (keyCode == p.getNextPageKey()) {
                if (event.getAction() == KeyEvent.ACTION_UP) {
                    manager.scrollToPosition(index+1);
                }
            } else if (keyCode == p.getPrevPageKey()) {
                if (event.getAction() == KeyEvent.ACTION_UP) {
                    manager.scrollToPosition(index-1);
                }
            }
            if(toolbarshow) toggleToolbar();
            return true;
        }
        return super.dispatchKeyEvent(event);
    }
    public int getStatusBarHeight() {
        int result = 0;
        int resourceId = getResources().getIdentifier("status_bar_height", "dimen", "android");
        if (resourceId > 0) {
            result = getResources().getDimensionPixelSize(resourceId);
        }
        return result;
    }


    @Override
    protected void onResume() {
        super.onResume();
        if(toolbarshow) getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_VISIBLE);
        else getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_FULLSCREEN);
    }

    @Override
    protected void onStop() {
        saveCurrentScrollBookmark();
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
                pageBtn.setText(item.index+1 + "/" + item.manga.getImgs(context).size());
                toolbarTitle.setText(item.manga.getName());
                commentBtn.setOnClickListener(v -> {
                    Intent commentActivity = new Intent(context, CommentsActivity.class);
                    //create gson and put extra
                    Gson gson = new Gson();
                    commentActivity.putExtra("comments", gson.toJson(item.manga.getComments()));
                    commentActivity.putExtra("bestComments", gson.toJson(item.manga.getBestComments()));
                    commentActivity.putExtra("id", item.manga.getId());
                    startActivity(commentActivity);
                });
                appbar.animate().translationY(0);
                appbarBottom.animate().translationY(0);
                toolbarshow = true;
                getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_VISIBLE);
            }

        }
        //getWindow().setAttributes(attrs);
    }

    public void toggleAutoCut(){
        PageItem page = getFocusedVisiblePage();
        if(page == null || page.manga == null || stripAdapter == null)
            return;
        autoCut = !autoCut;
        updateAutoCutButtonState();
        stripAdapter.removeAll();
        stripAdapter = new StripAdapter(context, page.manga, autoCut, width,title, infiniteScrollCallback);
        strip.setAdapter(stripAdapter);
        stripAdapter.setClickListener(() -> {
            // show/hide toolbar
            toggleToolbar();
        });
        manager.scrollToPage(new PageItem(page.index, "", page.manga));
    }

    private void updateAutoCutButtonState() {
        if(cut == null)
            return;
        cut.setBackgroundResource(autoCut ? R.drawable.app_selected_button_bg : R.drawable.app_outline_button_bg);
    }


//    public boolean dispatchTouchEvent(MotionEvent ev) {
//        return imageZoomHelper.onDispatchTouchEvent(ev) || super.dispatchTouchEvent(ev);
//    }


    @Override
    public void onBackPressed() {
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
            Intent episodeIntent = new Intent(context, EpisodeActivity.class);
            episodeIntent.putExtra("title", new Gson().toJson(new Title(targetTitle.minimize())));
            episodeIntent.putExtra("online", true);
            startActivity(episodeIntent);
            finish();
            return true;
        } catch (Exception e) {
            e.printStackTrace();
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
        CustomHttpClient.RequestGroup requestGroup = new CustomHttpClient.RequestGroup();
        Future<?> future;
        volatile boolean cancelled = false;

        public LoadImagesJob(Manga m, LoadMangaCallback callback, boolean lockui){
            this.lockui = lockui;
            this.m = m;
            this.callback = callback;
        }

        void start() {
            if(lockui) lockUi(true);
            if(lockui) {
                setOnBackPressed(() -> {
                    cancel();
                    if(!openEpisodeListIfRequested())
                        ViewerActivity.this.finish();
                });
            }
            try {
                future = imageLoadExecutor.submit(() -> {
                    int result = LOAD_OK;
                    try {
                        if(m.isOnline()) {
                            result = lockui ? prepareEpisodeIdentity(m) : ensureEpisodeListLoaded(m);
                            if(result == LOAD_OK && !hasLoadedImages(m)) {
                                result = getHttpClient().runWithRequestGroup(requestGroup, () -> m.fetchForViewerInitial(getHttpClient()));
                                if(result == LOAD_OK && !cancelled && !hasLoadedImages(m))
                                    result = getHttpClient().runWithRequestGroup(requestGroup, () -> m.fetchForViewerInitial(getHttpClient()));
                            }
                        }
                    } catch (Exception e) {
                        if(!cancelled && !isFinishing())
                            e.printStackTrace();
                    }
                    int finalResult = result;
                    mainHandler.post(() -> finish(finalResult));
                });
            } catch (RejectedExecutionException e) {
                if(!cancelled && !isFinishing())
                    e.printStackTrace();
                finish(LOAD_OK);
            }
        }

        void finish(Integer res) {
            if(cancelled || isFinishing())
                return;
            if(loader != this)
                return;
            loader = null;
            if(res == LOAD_CAPTCHA){
                //캡차 처리 팝업
                if(lockui) lockUi(false);
                resetOnBackPressed();
                showTokiCaptchaPopup(context, p);
                return;
            }

            if(lockui) lockUi(false);
            if (title == null)
                title = m.getTitle();
            resetOnBackPressed();
            callback.post(m);
            if(lockui)
                hydrateEpisodeListAfterFirstFrame(m);
            if(lockui)
                hydrateCommentsAfterFirstFrame(m);
        }

        void cancel() {
            cancelled = true;
            requestGroup.cancel();
            if(future != null)
                future.cancel(true);
            if(loader == this) {
                loader = null;
                if(lockui) lockUi(false);
                resetOnBackPressed();
            }
        }
    }

    private class PrefetchImagesJob {
        Manga target;
        CustomHttpClient.RequestGroup requestGroup = new CustomHttpClient.RequestGroup();
        Future<?> future;
        volatile boolean cancelled = false;

        PrefetchImagesJob(Manga target) {
            this.target = target;
        }

        void start() {
            try {
                future = imageLoadExecutor.submit(() -> {
                    int result = LOAD_OK;
                    try {
                        if(target != null && target.isOnline() && !hasLoadedImages(target))
                            result = getHttpClient().runWithRequestGroup(requestGroup, () -> target.fetchForViewerInitial(getHttpClient()));
                    } catch (Exception e) {
                        if(!cancelled && !isFinishing())
                            e.printStackTrace();
                    }
                    int finalResult = result;
                    mainHandler.post(() -> finish(finalResult));
                });
            } catch (RejectedExecutionException e) {
                if(!cancelled && !isFinishing())
                    e.printStackTrace();
                finish(LOAD_OK);
            }
        }

        void finish(Integer result) {
            if(nextPrefetcher != this)
                return;
            nextPrefetcher = null;
            if(cancelled || isFinishing() || result == LOAD_CAPTCHA || !hasLoadedImages(target))
                return;
            preloadFirstPages(target);
            int attachThreshold = p.getDataSave() ? DATA_SAVE_NEXT_EPISODE_ATTACH_THRESHOLD : NEXT_EPISODE_ATTACH_THRESHOLD;
            if(stripAdapter != null && manager != null && manager.findLastVisibleItemPosition() >= manager.getItemCount() - attachThreshold)
                attachNextEpisode(false);
        }

        void cancel() {
            cancelled = true;
            requestGroup.cancel();
            if(future != null)
                future.cancel(true);
            if(nextPrefetcher == this)
                nextPrefetcher = null;
        }
    }

    public void bookmarkRefresh(Manga m){
        if(m.useBookmark()) {
            PageItem page = new PageItem(p.getViewerBookmark(m), "", m);
            if (page.index > -1) {
                manager.scrollToPageWithOffset(page, p.getViewerBookmarkOffset(m));
            }
            if (m.isOnline()) {
                // if manga is online or has title.gson
                if (title == null) title = m.getTitle();
                p.addRecent(title);
                if (m!=null && m.getId()>0) p.setBookmark(title, m.getId());
            }
        }else{
            manager.scrollToPage(new PageItem(0,"",m));
        }
    }

    private int ensureEpisodeListLoaded(Manga target) {
        if(target == null || !target.isOnline())
            return LOAD_OK;
        Title currentTitle = title != null ? title : target.getTitle();
        if(currentTitle == null)
            return LOAD_OK;
        if(currentTitle.getEps() == null || currentTitle.getEps().size() <= 1) {
            int result = currentTitle.fetchEps(getHttpClient());
            if(result == LOAD_CAPTCHA)
                return result;
        }
        target.setTitle(currentTitle);
        target.setTitleId(currentTitle.getId());
        if(currentTitle.getEps() != null)
            for(Manga episode : currentTitle.getEps()) {
                if(episode != null) {
                    episode.setTitle(currentTitle);
                    episode.setTitleId(currentTitle.getId());
                }
            }
        title = currentTitle;
        return LOAD_OK;
    }

    private int prepareEpisodeIdentity(Manga target) {
        if(target == null || !target.isOnline())
            return LOAD_OK;
        Title currentTitle = title != null ? title : target.getTitle();
        if(currentTitle == null)
            return LOAD_OK;
        target.setTitle(currentTitle);
        target.setTitleId(currentTitle.getId());
        if(currentTitle.getEps() != null)
            for(Manga episode : currentTitle.getEps()) {
                if(episode != null) {
                    episode.setTitle(currentTitle);
                    episode.setTitleId(currentTitle.getId());
                }
            }
        title = currentTitle;
        return LOAD_OK;
    }

    private void hydrateEpisodeListAfterFirstFrame(Manga target) {
        if(target == null || !target.isOnline())
            return;
        Title currentTitle = title != null ? title : target.getTitle();
        if(currentTitle == null || (currentTitle.getEps() != null && currentTitle.getEps().size() > 1))
            return;
        mainHandler.postDelayed(() -> {
            try {
                imageLoadExecutor.submit(() -> {
                    try {
                        int result = currentTitle.fetchEps(getHttpClient());
                        if(result == LOAD_CAPTCHA || isFinishing())
                            return;
                        target.setTitle(currentTitle);
                        target.setTitleId(currentTitle.getId());
                        if(currentTitle.getEps() != null)
                            for(Manga episode : currentTitle.getEps()) {
                                if(episode != null) {
                                    episode.setTitle(currentTitle);
                                    episode.setTitleId(currentTitle.getId());
                                }
                            }
                        if(currentTitle.getEps() != null && currentTitle.getEps().size() > 0)
                            target.setEps(currentTitle.getEps());
                        mainHandler.post(() -> {
                            if(!isFinishing() && manga != null && manga.getId() == target.getId())
                                refreshToolbar(target);
                        });
                    } catch (Exception e) {
                        if(!isFinishing())
                            e.printStackTrace();
                    }
                });
            } catch (RejectedExecutionException e) {
                if(!isFinishing())
                    e.printStackTrace();
            }
        }, 350);
    }

    private void hydrateCommentsAfterFirstFrame(Manga target) {
        if(target == null || !target.isOnline() || target.areCommentsLoaded())
            return;
        mainHandler.postDelayed(() -> {
            try {
                imageLoadExecutor.submit(() -> {
                    try {
                        target.fetchComments(getHttpClient());
                    } catch (Exception e) {
                        if(!isFinishing())
                            e.printStackTrace();
                    }
                });
            } catch (RejectedExecutionException e) {
                if(!isFinishing())
                    e.printStackTrace();
            }
        }, 700);
    }

    private void saveCurrentScrollBookmark() {
        if(strip == null || manager == null || stripAdapter == null)
            return;
        int first = manager.findFirstVisibleItemPosition();
        int last = manager.findLastVisibleItemPosition();
        if(first == RecyclerView.NO_POSITION || last == RecyclerView.NO_POSITION)
            return;
        for(int i = Math.max(0, first); i <= last && i < stripAdapter.getItemCount(); i++) {
            PageItem page = stripAdapter.getPageAtPosition(i);
            if(page == null || page.manga == null || !page.manga.useBookmark())
                continue;
            View view = manager.findViewByPosition(i);
            if(view == null)
                continue;
            int offset = view.getTop() - strip.getPaddingTop();
            p.setViewerBookmark(page.manga, page.index, offset);
            if(title == null)
                title = page.manga.getTitle();
            p.setBookmark(title, page.manga.getId());
            return;
        }
    }

    public void updateIntent(Manga m){
        this.manga = m;
        result = new Intent();
        result.putExtra("id", m.getId());
        setResult(RESULT_OK, result);
    }

    public void refreshAdapter(){
        strip.setAdapter(stripAdapter);
        // show/hide toolbar
        stripAdapter.setClickListener(this::toggleToolbar);
    }

    private void preloadInitialViewerPages(Manga target) {
        if(stripAdapter == null || target == null)
            return;
        int pageIndex = target.useBookmark() ? p.getViewerBookmark(target) : 0;
        List<String> images = target.getImgs(context);
        if(images == null || images.size() == 0)
            return;
        if(pageIndex < 0 || pageIndex >= images.size())
            pageIndex = 0;
        stripAdapter.preloadInitialAroundPage(new PageItem(pageIndex, "", target));
    }

    private void scheduleFocusedPagePreload() {
        if(strip == null)
            return;
        strip.postDelayed(this::preloadFocusedPages, 40);
    }

    private void preloadFocusedPages() {
        if(stripAdapter == null || isFinishing())
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
        int first = manager.findFirstVisibleItemPosition();
        int last = manager.findLastVisibleItemPosition();
        int total = manager.getItemCount();
        int attachThreshold = p.getDataSave() ? DATA_SAVE_NEXT_EPISODE_ATTACH_THRESHOLD : NEXT_EPISODE_ATTACH_THRESHOLD;
        if(first <= 0 && !previousEpisodeBoundaryLoading)
            attachPreviousEpisode(false);
        if(last != RecyclerView.NO_POSITION && last >= total - attachThreshold)
            attachNextEpisode(false);
        if(last != RecyclerView.NO_POSITION && (last >= total - 2 || !strip.canScrollVertically(1)))
            attachNextEpisode(true);
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
        Manga target = page.manga.prevEp();
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
                if(m == null || strip == null || stripAdapter == null || isFinishing())
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
        Manga target = page.manga.nextEp();
        if(target == null)
            return;
        int loadedPosition = stripAdapter.findFirstPagePosition(target);
        if(loadedPosition != RecyclerView.NO_POSITION) {
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
                nextEpisodeBoundaryLoading = false;
                nextEpisodeBoundaryJumpPending = false;
                if(m == null || strip == null || stripAdapter == null || isFinishing())
                    return;
            }
        }, page.manga);
    }

    public void refreshToolbar(Manga m){
        //spinner
        eps = m.getEps();
        if(eps == null || eps.size() == 0){
            //backup plan
            eps = title.getEps();
        }
        spinnerAdapter.setData(eps, m);
        spinner.setSelection(m);

        //top toolbar
        toolbarTitle.setText(m.getName());
        toolbarTitle.setSelected(true);

        if(m.nextEp() == null){
            next.setEnabled(false);
            next.clearColorFilter();
            next.setAlpha(0.38f);
        }
        else {
            next.setEnabled(true);
            next.clearColorFilter();
            next.setAlpha(1f);
        }
        if(m.prevEp() == null) {
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
            pageBtn.setText(page.index+1+"/"+page.manga.getImgs(context).size());
    }

    private void prefetchNextEpisode(Manga current) {
        if(current == null || !current.isOnline())
            return;
        Manga target = current.nextEp();
        if(target == null)
            return;
        if(nextPrefetcher != null
                && nextPrefetchEpisodeId == target.getId()
                && nextPrefetchBaseMode == target.getBaseMode())
            return;
        if(hasLoadedImages(target))
            return;
        if(nextPrefetcher != null)
            nextPrefetcher.cancel();
        nextPrefetchEpisodeId = target.getId();
        nextPrefetchBaseMode = target.getBaseMode();
        nextPrefetcher = new PrefetchImagesJob(target);
        nextPrefetcher.start();
    }

    private void cancelNextPrefetcher() {
        if(nextPrefetcher != null)
            nextPrefetcher.cancel();
        nextPrefetchEpisodeId = -1;
        nextPrefetchBaseMode = -1;
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
            List<String> loadedImages = target.getImgs(context);
            return loadedImages != null && loadedImages.size() > 0;
        } catch (Exception e) {
            return false;
        }
    }

    private void preloadFirstPages(Manga target) {
        if(target == null || !target.isOnline())
            return;
        List<String> images = target.getImgs(context);
        int preloadLimit = p.getDataSave() ? DATA_SAVE_NEXT_EPISODE_PRELOAD_LIMIT : NEXT_EPISODE_PRELOAD_LIMIT;
        int limit = Math.min(preloadLimit, images.size());
        for(int i = 0; i < limit; i++)
            Glide.with(context)
                    .asBitmap()
                    .apply(viewerPreloadOptions())
                    .load(Utils.getGlideUrl(images.get(i), target.getBaseMode()))
                    .preload();
    }

    private RequestOptions viewerPreloadOptions() {
        return new RequestOptions()
                .diskCacheStrategy(DiskCacheStrategy.DATA)
                .downsample(DownsampleStrategy.AT_MOST)
                .override(Math.max(width, 1), Target.SIZE_ORIGINAL);
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
        if(strip.isComputingLayout()) {
            if(attempts < 20)
                strip.postDelayed(() -> runStripMutationWhenReady(mutation, attempts + 1), 50);
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
        return first != null && sameManga(first.manga != null ? first.manga.prevEp() : null, target);
    }

    private boolean isNextTargetStillExpected(Manga target) {
        PageItem last = getLastVisiblePage();
        return last != null && sameManga(last.manga != null ? last.manga.nextEp() : null, target);
    }

    private boolean sameManga(Manga a, Manga b) {
        return a != null && b != null
                && a.getId() == b.getId()
                && a.getTitleId() == b.getTitleId()
                && a.getBaseMode() == b.getBaseMode();
    }

    @Override
    public boolean onMenuOpened(int featureId, Menu menu) {
        return super.onMenuOpened(featureId, menu);
    }

    void lockUi(boolean lock){
        commentBtn.setEnabled(!lock);
        saveBtn.setEnabled(!lock);
        next.setEnabled(!lock);
        prev.setEnabled(!lock);
        pageBtn.setEnabled(!lock);
        cut.setEnabled(!lock);
        strip.setEnabled(!lock);
        spinner.setEnabled(!lock);
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
            refresh();
        }
    }

    @Override
    protected void onDestroy() {
        if(loader != null)
            loader.cancel();
        cancelNextPrefetcher();
        imageLoadExecutor.shutdownNow();
        super.onDestroy();
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
