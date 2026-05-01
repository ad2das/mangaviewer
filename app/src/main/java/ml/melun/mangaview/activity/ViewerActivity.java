package ml.melun.mangaview.activity;

import android.content.Context;
import android.content.Intent;
import android.content.res.Configuration;
import android.graphics.Color;
import android.os.Handler;
import android.os.Looper;

import com.bumptech.glide.Glide;
import com.bumptech.glide.load.engine.DiskCacheStrategy;
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

import static ml.melun.mangaview.MainApplication.httpClient;
import static ml.melun.mangaview.MainApplication.p;
import static ml.melun.mangaview.Utils.getScreenSize;
import static ml.melun.mangaview.Utils.hideSpinnerDropDown;
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
    private static final int NEXT_EPISODE_PRELOAD_LIMIT = 12;
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
        pageBtn = this.findViewById(R.id.viewerBtn1);
        pageBtn.setText("-/-");
        commentBtn = this.findViewById(R.id.commentButton);
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
                            insertMangaWhenIdle(m, () -> callback.prevLoaded(m));
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
                        preloadFirstPages(target);
                        appendMangaWhenIdle(target, () -> {
                            callback.nextLoaded(target);
                            prefetchNextEpisode(target);
                        });
                        return target;
                    }
                    cancelActiveEpisodeLoader();
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
                            appendMangaWhenIdle(m, () -> {
                                callback.nextLoaded(m);
                                prefetchNextEpisode(m);
                            });
                        } else {
                            callback.nextLoaded(m);
                            prefetchNextEpisode(m);
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

        this.findViewById(R.id.backButton).setOnClickListener(view -> finish());

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
            }
            
            loadManga(manga);
            strip.setItemAnimator(null);
            strip.setOnScrollListener(new RecyclerView.OnScrollListener() {
                @Override
                public void onScrollStateChanged(RecyclerView recyclerView, int newState) {
                    super.onScrollStateChanged(recyclerView, newState);
                    if(strip.getLayoutManager().getItemCount()>0 && newState == RecyclerView.SCROLL_STATE_DRAGGING && toolbarshow) {
                        toggleToolbar();
                    }
                    if(newState == RecyclerView.SCROLL_STATE_IDLE)
                        loadEpisodeAtBoundaryIfNeeded();
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

            refreshAdapter();
            bookmarkRefresh(m);
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
        if(autoCut){
            autoCut = false;
            cut.setBackgroundResource(R.drawable.button_bg);
            //viewerBookmark /= 2;
        } else{
            autoCut = true;
            cut.setBackgroundResource(R.drawable.button_bg_on);
            //viewerBookmark *= 2;
        }
        stripAdapter.removeAll();
        stripAdapter = new StripAdapter(context, page.manga, autoCut, width,title, infiniteScrollCallback);
        stripAdapter.preloadAll();
        strip.setAdapter(stripAdapter);
        stripAdapter.setClickListener(() -> {
            // show/hide toolbar
            toggleToolbar();
        });
        manager.scrollToPage(page);
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
        super.onBackPressed();
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
                    ViewerActivity.this.finish();
                });
            }
            try {
                future = imageLoadExecutor.submit(() -> {
                    int result = LOAD_OK;
                    try {
                        if(m.isOnline())
                            result = httpClient.runWithRequestGroup(requestGroup, () -> m.fetch(httpClient));
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
                            result = httpClient.runWithRequestGroup(requestGroup, () -> target.fetch(httpClient));
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
            if(stripAdapter != null && manager != null && manager.findLastVisibleItemPosition() >= manager.getItemCount() - 12)
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
                manager.scrollToPage(page);
            }
            if (m.useBookmark()) {
                // if manga is online or has title.gson
                if (title == null) title = m.getTitle();
                p.addRecent(title);
                if (m!=null && m.getId()>0) p.setBookmark(title, m.getId());
            }
        }else{
            manager.scrollToPage(new PageItem(0,"",m));
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

    private void loadEpisodeAtBoundaryIfNeeded() {
        if(strip == null || manager == null || stripAdapter == null || manager.getItemCount() == 0)
            return;
        int first = manager.findFirstVisibleItemPosition();
        int last = manager.findLastVisibleItemPosition();
        int total = manager.getItemCount();
        if(first <= 0 && !previousEpisodeBoundaryLoading)
            attachPreviousEpisode(false);
        if(last >= total - 12)
            attachNextEpisode(false);
        if(last >= total - 2 || !strip.canScrollVertically(1))
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
            next.setColorFilter(Color.BLACK);
        }
        else {
            next.setEnabled(true);
            next.setColorFilter(null);
        }
        if(m.prevEp() == null) {
            prev.setEnabled(false);
            prev.setColorFilter(Color.BLACK);
        }
        else {
            prev.setEnabled(true);
            prev.setColorFilter(null);
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
        int limit = Math.min(NEXT_EPISODE_PRELOAD_LIMIT, images.size());
        for(int i = 0; i < limit; i++)
            Glide.with(context)
                    .asBitmap()
                    .diskCacheStrategy(DiskCacheStrategy.DATA)
                    .load(Utils.getGlideUrl(images.get(i), target.getBaseMode()))
                    .preload();
    }

    private void appendMangaWhenIdle(Manga target, Runnable afterAppend) {
        if(target == null || strip == null)
            return;
        strip.post(() -> {
            runStripMutationWhenReady(() -> {
                if(stripAdapter == null || isFinishing())
                    return;
                if(!stripAdapter.hasMangaLoaded(target))
                    stripAdapter.appendManga(target);
                if(!stripAdapter.hasMangaLoaded(target))
                    return;
                if(afterAppend != null)
                    afterAppend.run();
            }, 0);
        });
    }

    private void insertMangaWhenIdle(Manga target, Runnable afterInsert) {
        if(target == null || strip == null)
            return;
        strip.post(() -> {
            runStripMutationWhenReady(() -> {
                if(stripAdapter == null || isFinishing())
                    return;
                if(!stripAdapter.hasMangaLoaded(target))
                    stripAdapter.insertManga(target);
                if(!stripAdapter.hasMangaLoaded(target))
                    return;
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
                && a.getBaseMode() == b.getBaseMode();
    }

    @Override
    public boolean onMenuOpened(int featureId, Menu menu) {
        return super.onMenuOpened(featureId, menu);
    }

    void lockUi(boolean lock){
        commentBtn.setEnabled(!lock);
        next.setEnabled(!lock);
        prev.setEnabled(!lock);
        pageBtn.setEnabled(!lock);
        cut.setEnabled(!lock);
        strip.setEnabled(!lock);
        spinner.setEnabled(!lock);
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
