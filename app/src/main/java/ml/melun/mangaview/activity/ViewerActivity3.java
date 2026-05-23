package ml.melun.mangaview.activity;
import android.annotation.SuppressLint;

import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.res.Configuration;
import android.graphics.Color;
import com.google.android.material.appbar.AppBarLayout;

import androidx.annotation.Nullable;
import androidx.core.view.ViewCompat;
import androidx.viewpager.widget.ViewPager;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import android.os.Bundle;
import androidx.appcompat.widget.Toolbar;
import android.text.InputType;
import android.text.TextUtils;
import android.view.KeyEvent;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ImageButton;
import android.widget.TextView;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;

import java.util.List;

import ml.melun.mangaview.R;
import ml.melun.mangaview.Utils;
import ml.melun.mangaview.adapter.CustomSpinnerAdapter;
import ml.melun.mangaview.adapter.ViewerPagerAdapter;
import ml.melun.mangaview.glide.ViewerPreloadPolicy;
import ml.melun.mangaview.glide.ViewerWarmupManager;
import ml.melun.mangaview.interfaces.PageInterface;

import ml.melun.mangaview.mangaview.Manga;
import ml.melun.mangaview.mangaview.Title;
import ml.melun.mangaview.repository.MangaRepository;
import ml.melun.mangaview.runtime.AppDispatchers;
import ml.melun.mangaview.runtime.PerformanceMonitor;
import ml.melun.mangaview.ui.CustomSpinner;

import static ml.melun.mangaview.MainApplication.p;
import static ml.melun.mangaview.Utils.getScreenSize;
import static ml.melun.mangaview.Utils.hideSpinnerDropDown;
import static ml.melun.mangaview.Utils.queueOfflineDownload;
import static ml.melun.mangaview.Utils.showCaptchaPopup;
import static ml.melun.mangaview.activity.CaptchaActivity.RESULT_CAPTCHA;
import static ml.melun.mangaview.mangaview.Title.LOAD_CAPTCHA;

public class ViewerActivity3 extends AppCompatActivity {
    List<String> imgs;
    Manga manga;
    Context context;
    ViewPager viewPager;
    boolean dark;
    ImageButton next, prev;
    TextView toolbarTitle;
    AppBarLayout appbar, appbarBottom;
    Toolbar toolbar;
    Button cut, pageBtn;
    ImageButton saveBtn;
    int width;
    Intent intent;
    Title title;
    String name;
    boolean captchaChecked = false;
    int id;
    int viewerBookmark = 0;
    int seed;
    ViewerPagerAdapter pageAdapter;
    int index;
    Intent result;
    List<Manga> eps;
    boolean toolbarshow = true;
    ViewPager.OnPageChangeListener listener;
    CustomSpinner spinner;
    CustomSpinnerAdapter spinnerAdapter;
    LoadImages imageLoad;
    boolean startCurrentEpisodeAtFirstPage = false;
    MissingEpisodeNavigator.PromptState missingEpisodePromptState = new MissingEpisodeNavigator.PromptState();


    @Override
    protected void onResume() {
        super.onResume();
        PerformanceMonitor.resume();
        if(toolbarshow) getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_VISIBLE);
        else getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_FULLSCREEN);
    }

    @Override
    protected void onPause() {
        PerformanceMonitor.pause();
        super.onPause();
    }

    @Override
    protected void onSaveInstanceState(Bundle outState) {
        Utils.saveMangaState(outState, manga);
        super.onSaveInstanceState(outState);
    }
    @Override
    @SuppressLint("WrongViewCast")
    protected void onCreate(Bundle savedInstanceState) {
        Utils.cancelPendingViewerLaunches(this);
        dark = p.getDarkTheme();
        if(dark) setTheme(R.style.AppThemeDarkNoTitle);
        super.onCreate(savedInstanceState);
        PerformanceMonitor.attach(this);
        PerformanceMonitor.screen("viewer");

        setContentView(R.layout.activity_viewer3);
        context = this;
        next = this.findViewById(R.id.toolbar_next);
        prev = this.findViewById(R.id.toolbar_previous);
        toolbar = this.findViewById(R.id.viewerToolbar);
        appbar = this.findViewById(R.id.viewerAppbar);
        toolbarTitle = this.findViewById(R.id.toolbar_title);
        appbarBottom = this.findViewById(R.id.viewerAppbarBottom);
        cut = this.findViewById(R.id.viewerBtn2);
        this.findViewById(R.id.backButton).setOnClickListener(view -> onBackPressed());

        //initial padding setup
        appbar.setPadding(0, 0,0,0);
        getWindow().getDecorView().setBackgroundColor(Color.BLACK);

        ViewCompat.setOnApplyWindowInsetsListener(getWindow().getDecorView(), (view, windowInsetsCompat) -> {
            //This is where you get DisplayCutoutCompat
            int statusBarHeight = windowInsetsCompat.getSystemWindowInsetTop();
            int ci;
            if(windowInsetsCompat.getDisplayCutout() == null) ci = 0;
            else ci = windowInsetsCompat.getDisplayCutout().getSafeInsetTop();

            //System.out.println(ci + " : " + statusBarHeight);
            appbar.setPadding(0,ci > statusBarHeight ? ci : statusBarHeight,0,0);
            view.setPadding(windowInsetsCompat.getStableInsetLeft(),0,windowInsetsCompat.getStableInsetRight(),windowInsetsCompat.getStableInsetBottom());
            return windowInsetsCompat;
        });


        cut.setText("자동 분할");
        cut.setVisibility(View.GONE);

        pageBtn = this.findViewById(R.id.viewerBtn1);
        pageBtn.setText("-/-");
        saveBtn = this.findViewById(R.id.viewerSaveButton);
        saveBtn.setOnClickListener(view -> saveCurrentEpisodeOffline());
        width = getScreenSize(getWindowManager().getDefaultDisplay());
        viewPager = this.findViewById(R.id.viewerPager);
        viewPager.setOffscreenPageLimit(p.getDataSave() ? 1 : 3);
        spinner = this.findViewById(R.id.toolbar_spinner);

        spinnerAdapter = new CustomSpinnerAdapter(context);
        spinnerAdapter.setListener((m, i) -> {
            lockUi(true);
            spinner.setSelection(m);
            manga = m;
            startCurrentEpisodeAtFirstPage = true;
            viewerBookmark = 0;
            if(title != null)
                manga.setTitle(title);
            id = m.getId();
            index = i;
            hideSpinnerDropDown(spinner);
            if(manga.isOnline())
                refresh();
            else
                reloadManga();
        });
        spinner.setAdapter(spinnerAdapter);
        //adapter
        pageAdapter = new ViewerPagerAdapter(getSupportFragmentManager(), width, context, () -> toggleToolbar());
        listener = new ViewPager.OnPageChangeListener() {
            @Override
            public void onPageScrolled(int position, float positionOffset, int positionOffsetPixels) {
                int pageSize = pageAdapter.getCount();
                int pos = p.getPageRtl() ? pageSize - position - 1 : position;
                if(viewerBookmark != pos) {
                    viewerBookmark = pos;
                    pageBtn.setText(viewerBookmark + 1 + "/" + imgs.size());
                    if(manga.useBookmark()) {
                        if (viewerBookmark == pageSize - 1 || viewerBookmark == 0) {
                            p.removeViewerBookmark(manga);
                        } else p.setViewerBookmark(manga, viewerBookmark);
                        p.setBookmark(title, manga.getId());
                    }

                    boolean lastPage = viewerBookmark == pageSize - 1;
                    boolean firstPage = viewerBookmark == 0;
                    if (toolbarshow && !lastPage)
                        toggleToolbar();
                    else if (lastPage && !toolbarshow)
                        toggleToolbar();
                    preloadAroundCurrentPage();
                }
            }

            @Override
            public void onPageSelected(int position) {

            }

            @Override
            public void onPageScrollStateChanged(int state) {

            }
        };

        try {
            intent = getIntent();
            startCurrentEpisodeAtFirstPage = savedInstanceState == null
                    && intent.getBooleanExtra(ViewerActivity.EXTRA_START_AT_FIRST_PAGE, false);
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

            if(title == null)
                title = manga.getTitle();
            if(title != null)
                manga.setTitle(title);

            name = manga.getName();
            id = manga.getId();

            toolbarTitle.setText(name);
            if(manga.useBookmark() && !startCurrentEpisodeAtFirstPage)
                viewerBookmark = p.getViewerBookmark(manga);

            if(manga.useBookmark()){
                result = new Intent();
                result.putExtra("id", id);
                setResult(RESULT_OK,result);
            }
            if(!manga.isOnline()){
                //load local imgs
                saveBtn.setVisibility(View.GONE);
                reloadManga();
            }else {
                refresh();
            }
        }catch(Exception e){
            ml.melun.mangaview.report.CrashReporter.record(e);
        }

        pageBtn.setOnClickListener(v -> {
            if(imgs == null || imgs.size() == 0) {
                showViewerImagesUnavailable();
                return;
            }
            AlertDialog.Builder alert;
            if(dark) alert = new AlertDialog.Builder(context,R.style.darkDialog);
            else alert = new AlertDialog.Builder(context);

            alert.setTitle("페이지 선택\n(1~"+imgs.size()+")");
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
                    if (page > imgs.size()) page = imgs.size();
                    viewerBookmark = page - 1;
                    goPage(viewerBookmark, false);
                    pageBtn.setText(viewerBookmark + 1 + "/" + imgs.size());
                }
            });
            alert.setNegativeButton("취소", (dialog, button) -> {
                //취소 시
            });
            Utils.safeShowDialog(alert);
        });
        next.setOnClickListener(v -> {
            Manga target = nextEpisodeCandidate();
            if(target != null) {
                if(maybePromptMissingNextEpisode(manga, target, () -> openEpisode(target)))
                    return;
                openEpisode(target);
            }
        });
        prev.setOnClickListener(v -> {
            Manga target = previousEpisodeCandidate();
            if(target != null)
                openEpisode(target);
        });
    }

    private Manga nextEpisodeCandidate() {
        Manga candidate = manga == null ? null : manga.nextEp();
        if(candidate != null)
            return candidate;
        if(eps != null && index > 0)
            return Utils.safeGet(eps, index - 1);
        return null;
    }

    private Manga previousEpisodeCandidate() {
        Manga candidate = manga == null ? null : manga.prevEp();
        if(candidate != null)
            return candidate;
        if(eps != null && index >= 0 && index < eps.size() - 1)
            return Utils.safeGet(eps, index + 1);
        return null;
    }

    private void openEpisode(Manga target) {
        if(target == null)
            return;
        lockUi(true);
        if(title != null)
            target.setTitle(title);
        manga = target;
        id = manga.getId();
        name = manga.getName();
        startCurrentEpisodeAtFirstPage = true;
        viewerBookmark = 0;
        if(manga.isOnline())
            refresh();
        else
            reloadManga();
    }

    private boolean maybePromptMissingNextEpisode(Manga source, Manga target, Runnable skipAction) {
        return MissingEpisodeNavigator.maybePromptNextEpisode(this, dark, source, target, missingEpisodePromptState,
                new MissingEpisodeNavigator.Host() {
                    @Override
                    public void lockUi(boolean lock) {
                        ViewerActivity3.this.lockUi(lock);
                    }

                    @Override
                    public void openAlternateEpisode(Title alternateTitle, Manga episode) {
                        title = alternateTitle;
                        if(episode != null && alternateTitle != null) {
                            episode.setTitle(alternateTitle);
                            episode.setTitleId(alternateTitle.getId());
                        }
                        openEpisode(episode);
                    }

                    @Override
                    public void showCaptcha(Manga episode) {
                        showCaptchaPopup(Manga.safeUrl(episode == null ? source : episode), ViewerActivity3.this, RESULT_CAPTCHA, p);
                    }

                    @Override
                    public void onPromptCancelled() {
                    }
                }, skipAction);
    }

    void refresh(){
        refresh(true);
    }

    void refresh(boolean allowResumeFallback){
        captchaChecked = false;
        if(imageLoad != null)
            imageLoad.cancel();
        imageLoad = new LoadImages(allowResumeFallback, consumeStartAtFirstPage());
        imageLoad.start();
    }

    private boolean consumeStartAtFirstPage() {
        boolean result = startCurrentEpisodeAtFirstPage;
        startCurrentEpisodeAtFirstPage = false;
        return result;
    }


    @Override
    public boolean dispatchKeyEvent(KeyEvent event) {
        int keyCode = event.getKeyCode();
        if(keyCode == p.getNextPageKey()){
            if(event.getAction() == KeyEvent.ACTION_UP && viewerBookmark<pageAdapter.getCount()-1)
                goPage(viewerBookmark+1, false);
            return true;
        }else if(keyCode == p.getPrevPageKey()){
            if(event.getAction() == KeyEvent.ACTION_UP && viewerBookmark>0)
                goPage(viewerBookmark-1, false);
            return true;
        }
        return super.dispatchKeyEvent(event);
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
            pageBtn.setText(viewerBookmark+1+"/"+imgs.size());
            appbar.animate().translationY(0);
            appbarBottom.animate().translationY(0);
            toolbarshow=true;
            getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_VISIBLE);
        }
        //getWindow().setAttributes(attrs);
    }

    private class LoadImages {
        AppDispatchers.TaskHandle handle;
        MangaRepository.Cancellation cancellation = MangaRepository.cancellation();
        volatile boolean cancelled;
        final boolean allowResumeFallback;
        final boolean startAtFirstPage;

        LoadImages(boolean allowResumeFallback, boolean startAtFirstPage) {
            this.allowResumeFallback = allowResumeFallback;
            this.startAtFirstPage = startAtFirstPage;
        }

        private void postProgress(String value) {
        }

        void start() {
            handle = AppDispatchers.submitIo(() -> {
                Integer result = load();
                AppDispatchers.runOnMain(() -> finish(result));
            });
        }

        private Integer load() {
            manga.setListener(this::postProgress);
            int res = ensureEpisodeListLoaded(manga);
            if(res == LOAD_CAPTCHA)
                return res;
            try {
                int firstPage = startAtFirstPage ? 0 : (manga.useBookmark() ? p.getViewerBookmark(manga) : viewerBookmark);
                if(!startAtFirstPage && allowResumeFallback && ViewerResumeResolver.shouldResolveBeforeDirectFetch(manga, title)) {
                    res = prepareFirstAvailableManga(firstPage, true, cancellation);
                } else {
                    res = ViewerWarmupManager.prepareFirstFrame(context, manga, title, firstPage, width, false, p.getReverse(), cancellation);
                    if(!startAtFirstPage && allowResumeFallback && (res == ViewerWarmupManager.LOAD_EMPTY_IMAGES || !hasLoadedImages()))
                        res = prepareFirstAvailableManga(firstPage, false, cancellation);
                }
                if(title == null)
                    title = manga.getTitle();
            } catch (Exception e) {
                if(!cancelled)
                    ml.melun.mangaview.report.CrashReporter.record(e);
            }
            return res;
        }

        private void finish(Integer res) {
            if(cancelled || isFinishing())
                return;
            if(imageLoad == this)
                imageLoad = null;
            if(res == LOAD_CAPTCHA){
                //캡차 처리 팝업
                showCaptchaPopup(Manga.safeUrl(manga), context, RESULT_CAPTCHA, p);
                return;
            }
            if(res == ViewerWarmupManager.LOAD_EMPTY_IMAGES || !hasLoadedImages()) {
                showViewerImagesUnavailable();
                return;
            }
            reloadManga(startAtFirstPage);
        }

        void cancel() {
            cancelled = true;
            cancellation.cancel();
            if(handle != null)
                handle.cancel();
            if(imageLoad == this)
                imageLoad = null;
        }
    }

    private int prepareFirstAvailableManga(int firstPage, boolean skipTarget, MangaRepository.Cancellation cancellation) throws Exception {
        int lastResult = ViewerWarmupManager.LOAD_EMPTY_IMAGES;
        Title currentTitle = title != null ? title : manga == null ? null : manga.getTitle();
        for(Manga candidate : ViewerResumeResolver.candidates(manga, currentTitle, skipTarget)) {
            int page = ViewerResumeResolver.sameManga(candidate, manga) ? firstPage : 0;
            int result = ViewerWarmupManager.prepareFirstFrame(context, candidate, currentTitle, page, width, false, p.getReverse(), cancellation);
            if(result == LOAD_CAPTCHA)
                return result;
            lastResult = result;
            List<String> images = MangaRepository.imageUrls(candidate, context);
            if(result == 0 && images != null && images.size() > 0) {
                if(!ViewerResumeResolver.sameManga(candidate, manga))
                    ViewerWarmupManager.logMetric("viewer_resume_episode_fallback", candidate.getId());
                manga = candidate;
                id = candidate.getId();
                name = candidate.getName();
                if(candidate.getTitle() != null)
                    title = candidate.getTitle();
                return 0;
            }
        }
        return lastResult;
    }

    private int ensureEpisodeListLoaded(Manga target) {
        if(target == null || !target.isOnline())
            return 0;
        Title currentTitle = title != null ? title : target.getTitle();
        if(currentTitle == null)
            return 0;
        if(Utils.snapshotEpisodes(currentTitle).size() <= 1) {
            int result = MangaRepository.fetchEpisodesForeground(currentTitle);
            if(result == LOAD_CAPTCHA)
                return result;
        }
        currentTitle.ensureProgressEpisodes(target);
        target.setTitle(currentTitle);
        target.setTitleId(currentTitle.getId());
        List<Manga> episodes = Utils.snapshotEpisodes(currentTitle);
        if(episodes.size() > 0)
            for(Manga episode : episodes) {
                if(episode != null) {
                    episode.setTitle(currentTitle);
                    episode.setTitleId(currentTitle.getId());
                }
            }
        if(episodes.size() > 0)
            target.setEps(episodes);
        title = currentTitle;
        return 0;
    }

    @Override
    public void onBackPressed() {
        Utils.cancelPendingViewerLaunches(this);
        if(imageLoad != null) {
            imageLoad.cancel();
            imageLoad = null;
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

    public void goPage(int item, boolean smoothScroll) {
        int count = pageAdapter == null ? 0 : pageAdapter.getCount();
        int target = Utils.clampIndex(item, count);
        if(target < 0)
            return;
        viewPager.setCurrentItem(p.getPageRtl() ? count - target - 1 : target, smoothScroll);
    }

    public void reloadManga(){
        reloadManga(consumeStartAtFirstPage());
    }

    public void reloadManga(boolean startAtFirstPage){
        try {
            lockUi(false);
            imgs = MangaRepository.imageUrls(manga, context);
            if(imgs == null || imgs.size()==0) {
                showViewerImagesUnavailable();
                return;
            }
            refreshAdapter();
            bookmarkRefresh(startAtFirstPage);
            refreshToolbar();
            updateIntent();
            preloadAroundCurrentPage();
            prewarmAdjacentEpisodes();
            viewPager.addOnPageChangeListener(listener);

        }catch (Exception e){
            ml.melun.mangaview.report.CrashReporter.record(e);
            Utils.showCaptchaPopup(Manga.safeUrl(manga), context, e, p);
        }
    }

    public void bookmarkRefresh(){
        bookmarkRefresh(false);
    }

    public void bookmarkRefresh(boolean startAtFirstPage){
        if(manga.useBookmark() && !startAtFirstPage)
            viewerBookmark = p.getViewerBookmark(manga);
        else
            viewerBookmark = 0;
        if(manga.useBookmark() && manga.isOnline()) {
            p.addRecent(title);
            p.setBookmark(title, id);
        }
        if(imgs != null && imgs.size() > 0)
            viewerBookmark = Utils.clampIndex(viewerBookmark, imgs.size());
        goPage(viewerBookmark, false);
    }

    public void updateIntent(){
        result = new Intent();
        result.putExtra("id", id);
        setResult(RESULT_OK, result);
    }

    public void refreshAdapter(){
        //adapter
        viewPager.removeOnPageChangeListener(listener);
        pageAdapter.setManga(manga);
        viewPager.setAdapter(pageAdapter);
    }

    public void refreshToolbar(){
        eps = Utils.snapshotEpisodes(manga);
        if(eps == null || eps.size() == 0){
            eps = Utils.snapshotEpisodes(title);
        }
        if(eps == null)
            eps = new java.util.ArrayList<>();
        index = -1;
        for(int i=0; i<eps.size(); i++){
            Manga episode = Utils.safeGet(eps, i);
            if(episode != null && episode.equals(manga)){
                index = i;
                break;
            }
        }
        spinnerAdapter.setData(eps, manga);
        spinner.setSelection(manga);

        toolbarTitle.setText(manga.getName());
        toolbarTitle.setEllipsize(TextUtils.TruncateAt.MARQUEE);
        toolbarTitle.setMarqueeRepeatLimit(-1);
        toolbarTitle.setSingleLine(true);
        toolbarTitle.setSelected(true);

        if(index <= 0){
            next.setEnabled(false);
            next.clearColorFilter();
            next.setAlpha(0.38f);
        }
        else {
            next.setEnabled(true);
            next.clearColorFilter();
            next.setAlpha(1f);
        }
        if(index < 0 || index==eps.size()-1) {
            prev.setEnabled(false);
            prev.clearColorFilter();
            prev.setAlpha(0.38f);
        }
        else {
            prev.setEnabled(true);
            prev.clearColorFilter();
            prev.setAlpha(1f);
        }
        pageBtn.setText(imgs == null || imgs.size() == 0 ? "-/-" : viewerBookmark+1+"/"+imgs.size());
    }

    private boolean hasLoadedImages() {
        List<String> images = MangaRepository.imageUrls(manga, context);
        return images != null && images.size() > 0;
    }

    private void showViewerImagesUnavailable() {
        ViewerWarmupManager.logMetric("viewer_empty_images", manga == null ? -1 : manga.getId());
        Utils.showPopup(context, "오류", "회차 이미지를 불러오지 못했습니다.", (dialog, which) -> {
            if(!openEpisodeListIfRequested())
                ViewerActivity3.this.finish();
        }, dialog -> {
            if(!openEpisodeListIfRequested())
                ViewerActivity3.this.finish();
        });
    }

    private void preloadAroundCurrentPage() {
        if(manga == null || imgs == null || imgs.size() == 0 || !manga.isOnline())
            return;
        ViewerWarmupManager.preloadWindow(context, manga, viewerBookmark, width, false, p.getReverse(), ViewerPreloadPolicy.scrollAheadWindow(p.getDataSave()));
    }

    private void prewarmAdjacentEpisodes() {
        if(eps == null || eps.size() == 0 || title == null)
            return;
        Manga nextEpisode = nextEpisodeCandidate();
        if(nextEpisode != null && !MissingEpisodeNavigator.hasMissingNextEpisodeGap(manga, nextEpisode))
            ViewerWarmupManager.warmup(context, nextEpisode, title, 0);
    }

    void lockUi(boolean lock){
        saveBtn.setEnabled(!lock);
        next.setEnabled(!lock);
        prev.setEnabled(!lock);
        pageBtn.setEnabled(!lock);
        cut.setEnabled(!lock);
        spinner.setEnabled(!lock);
    }

    private void saveCurrentEpisodeOffline() {
        if(manga == null)
            return;
        if(title == null)
            title = manga.getTitle();
        if(title != null)
            manga.setTitle(title);
        queueOfflineDownload(context, title, manga);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, @Nullable Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (resultCode == RESULT_CAPTCHA) {
            refresh(false);
        }
    }

    @Override
    protected void onDestroy() {
        Utils.cancelPendingViewerLaunches(this);
        if(imageLoad != null) {
            imageLoad.cancel();
            imageLoad = null;
        }
        missingEpisodePromptState.dismiss();
        if(viewPager != null) {
            viewPager.removeOnPageChangeListener(listener);
            viewPager.setAdapter(null);
        }
        if(pageAdapter != null) {
            pageAdapter.release();
            pageAdapter = null;
        }
        ViewerWarmupManager.clearDecodedWork(context);
        super.onDestroy();
    }
}
