package ml.melun.mangaview.activity;
import android.annotation.SuppressLint;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.res.Configuration;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.drawable.Drawable;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.bumptech.glide.load.model.GlideUrl;
import com.bumptech.glide.load.model.LazyHeaders;
import com.google.android.material.appbar.AppBarLayout;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.view.ViewCompat;

import android.os.Bundle;
import android.text.InputType;
import android.text.TextUtils;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.TextView;
import android.widget.Toast;

import com.bumptech.glide.Glide;
import com.bumptech.glide.Priority;
import com.bumptech.glide.load.DataSource;
import com.bumptech.glide.load.engine.GlideException;
import com.bumptech.glide.load.engine.DiskCacheStrategy;
import com.bumptech.glide.load.resource.bitmap.DownsampleStrategy;
import com.bumptech.glide.request.RequestListener;
import com.bumptech.glide.request.RequestOptions;
import com.bumptech.glide.request.target.CustomTarget;
import com.bumptech.glide.request.target.Target;
import com.bumptech.glide.request.transition.Transition;
import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;

import java.util.List;

import ml.melun.mangaview.R;
import ml.melun.mangaview.Utils;
import ml.melun.mangaview.adapter.CustomSpinnerAdapter;
import ml.melun.mangaview.glide.ViewerPreloadPolicy;
import ml.melun.mangaview.glide.ViewerWarmupManager;
import ml.melun.mangaview.mangaview.Decoder;
import ml.melun.mangaview.mangaview.Manga;
import ml.melun.mangaview.mangaview.Title;
import ml.melun.mangaview.model.PageItem;
import ml.melun.mangaview.repository.MangaRepository;
import ml.melun.mangaview.runtime.AppDispatchers;
import ml.melun.mangaview.runtime.PerformanceMonitor;
import ml.melun.mangaview.ui.CustomSpinner;

import static ml.melun.mangaview.MainApplication.p;
import static ml.melun.mangaview.Utils.getGlideUrl;
import static ml.melun.mangaview.Utils.getScreenSize;
import static ml.melun.mangaview.Utils.hideSpinnerDropDown;
import static ml.melun.mangaview.Utils.queueOfflineDownload;
import static ml.melun.mangaview.Utils.showCaptchaPopup;
import static ml.melun.mangaview.activity.CaptchaActivity.RESULT_CAPTCHA;
import static ml.melun.mangaview.mangaview.Title.LOAD_CAPTCHA;

public class ViewerActivity2 extends AppCompatActivity {
    Boolean dark, toolbarshow=true, reverse, touch=true, stretch, leftRight;
    Context context = this;
    String name;
    int id;
    Manga manga;
    ImageButton next, prev, saveBtn;
    androidx.appcompat.widget.Toolbar toolbar;
    Button pageBtn, nextPageBtn, prevPageBtn, touchToggleBtn;
    AppBarLayout appbar, appbarBottom;
    TextView toolbarTitle;
    int viewerBookmark = 0;
    List<String> imgs;
    List<Integer> types;
    List<Manga> eps;
    int index;
    Title title;
    ImageView frame; // left
    ImageView frame2; // right
    int type=-1;
    Bitmap imgCache, preloadImg;
    Intent result;
    AlertDialog.Builder alert;
    int swidth = 0;
    Intent intent;
    boolean captchaChecked = false;
    ImageButton toolbar_toggleBtn;
    CustomSpinner spinner;
    CustomSpinnerAdapter spinnerAdapter;
    Decoder d;
    boolean nextEpisodeVisible = false;
    View nextEpisode;
    boolean split = false;
    boolean dirty = false;
    TextView info;
    int imageLoadGeneration = 0;
    loadImages activeImageLoad;
    boolean startCurrentEpisodeAtFirstPage = false;

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
        super.onCreate(savedInstanceState);
        PerformanceMonitor.attach(this);
        PerformanceMonitor.screen("viewer");
        setContentView(R.layout.activity_viewer2);

        info = this.findViewById(R.id.viewer2_info);
        info.setOnClickListener(v -> info.setVisibility(View.GONE));

        next = this.findViewById(R.id.toolbar_next);
        prev = this.findViewById(R.id.toolbar_previous);
        toolbar = this.findViewById(R.id.viewerToolbar);
        appbar = this.findViewById(R.id.viewerAppbar);
        toolbarTitle = this.findViewById(R.id.toolbar_title);
        appbarBottom = this.findViewById(R.id.viewerAppbarBottom);
        reverse = p.getReverse();
        pageBtn = this.findViewById(R.id.viewerBtn1);
        toolbar_toggleBtn = this.findViewById(R.id.toolbar_toggleBtn);
        pageBtn.setText("-/-");
        leftRight = p.getLeftRight();
        spinner = this.findViewById(R.id.toolbar_spinner);
        nextEpisode = this.findViewById(R.id.viewerNextEpisode);
        if(p.getDoublepReverse()){
            frame = this.findViewById(R.id.viewer_image2);
            frame2 = this.findViewById(R.id.viewer_image);
        }else{
            frame = this.findViewById(R.id.viewer_image);
            frame2 = this.findViewById(R.id.viewer_image2);
        }



        nextEpisode.setVisibility(View.GONE);

        //initial padding setup
        appbar.setPadding(0, 0,0,0);
        getWindow().getDecorView().setBackgroundColor(Color.BLACK);

//        Display display  = getWindowManager().getDefaultDisplay();
//        Point size = new Point();
//        display.getSize(size);
//        split = size.x > size.y;

        split = p.getDoublep();


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

        this.findViewById(R.id.backButton).setOnClickListener(view -> onBackPressed());
        spinnerAdapter = new CustomSpinnerAdapter(context);
        spinnerAdapter.setListener((m, i) -> {
            lockUi(true);
            spinner.setSelection(m);
            index = i;
            manga = m;
            startCurrentEpisodeAtFirstPage = true;
            viewerBookmark = 0;
            if(title != null)
                manga.setTitle(title);
            hideSpinnerDropDown(spinner);
            loadManga(m);
        });
        spinner.setAdapter(spinnerAdapter);

        if(leftRight){
            // button reverse
            nextPageBtn = this.findViewById(R.id.leftButton);
            prevPageBtn = this.findViewById(R.id.rightButton);
        }else{
            nextPageBtn = this.findViewById(R.id.rightButton);
            prevPageBtn = this.findViewById(R.id.leftButton);
        }

        refreshPageControlButton();

        touchToggleBtn = this.findViewById(R.id.viewerBtn2);
        touchToggleBtn.setText("입력 제한");
        saveBtn = this.findViewById(R.id.viewerSaveButton);
        saveBtn.setOnClickListener(view -> saveCurrentEpisodeOffline());
        stretch = p.getStretch();

        //refreshBtn = this.findViewById(R.id.refreshButton);
        if(stretch) frame.setScaleType(ImageView.ScaleType.FIT_XY);
        swidth = getScreenSize(getWindowManager().getDefaultDisplay());

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
            dirty = true;
        }
        if(title != null)
            manga.setTitle(title);

        name = manga.getName();
        id = manga.getId();

        toolbarTitle.setText(name);

//        refreshbtn.setOnClickListener(new View.OnClickListener() {
//            @Override
//            public void onClick(View v) {
//                refreshImage();
//            }
//        });


        if(intent.getBooleanExtra("recent",false)){
            Intent resultIntent = new Intent();
            setResult(RESULT_OK,resultIntent);
        }
        if(!manga.isOnline()) {
            reloadManga();
            saveBtn.setVisibility(View.GONE);
        }else{
            //if online
            //fetch imgs
            refresh();
        }

        nextPageBtn.setOnClickListener(v -> {
            if(touch) nextPage();
        });
        prevPageBtn.setOnClickListener(v -> {
            if(touch) prevPage();
        });

        toolbar_toggleBtn.setOnClickListener(view -> toggleToolbar());

        touchToggleBtn.setOnClickListener(v -> {
            if(touch) {
                touch = false;
                touchToggleBtn.setBackgroundResource(R.drawable.app_selected_button_bg);
            }
            else{
                touch = true;
                touchToggleBtn.setBackgroundResource(R.drawable.app_outline_button_bg);
            }
        });

        pageBtn.setOnClickListener(v -> {
            if(imgs == null || imgs.size() == 0) {
                showViewerImagesUnavailable();
                return;
            }
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
                    refreshImage();
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
                openEpisode(target);
            } else
                Utils.safeToast(context, "마지막화 입니다", Toast.LENGTH_SHORT);

        });
        prev.setOnClickListener(v -> {
            Manga target = previousEpisodeCandidate();
            if(target != null)
                openEpisode(target);
        });

        View.OnLongClickListener tbToggle = v -> {
            //touched = true;
            toggleToolbar();
            return true;
        };
        nextPageBtn.setOnLongClickListener(tbToggle);
        prevPageBtn.setOnLongClickListener(tbToggle);

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
        loadManga(manga);
    }

    void refreshPageControlButton(){
        if(p.getPageControlButtonOffset()!= -1){
            Button left = this.findViewById(R.id.leftButton);
            ViewGroup.LayoutParams params = left.getLayoutParams();
            params.width = (int)(p.getPageControlButtonOffset() * Utils.getScreenWidth(getWindowManager().getDefaultDisplay()));
            left.setLayoutParams(params);
        }
    }


    void nextPage(){
        if(imgs == null || imgs.size() == 0) {
            showViewerImagesUnavailable();
            return;
        }
        //refreshbtn.setVisibility(View.VISIBLE);
        if(split) {
            if(viewerBookmark+type-1 == imgs.size()-1){
                if(nextEpisodeVisible) {
                    next.performClick();
                }
                toggleNextEpisode();
            }else {
                viewerBookmark += type;
                if(viewerBookmark < imgs.size()) {
                    refreshImage();
                }
            }


        }else if(viewerBookmark==imgs.size()-1 && (type==-1 || type==1)){
            //end of manga
            //refreshbtn.setVisibility(View.INVISIBLE);
            // 다음화 로드
            if(nextEpisodeVisible) {
                next.performClick();
            }
            toggleNextEpisode();

        }else if(type==0){
            //is two page, current pos: right
            //dont add page
            //only change type
            //refreshbtn.setVisibility(View.INVISIBLE);
            type = 1;
            int width = imgCache.getWidth();
            int height = imgCache.getHeight();
            if(reverse) frame.setImageBitmap(Bitmap.createBitmap(imgCache, width/2, 0, width / 2, height));
            else frame.setImageBitmap(Bitmap.createBitmap(imgCache, 0, 0, width / 2, height));

        }else{
            //is single page OR unidentified
            //add page
            //has to check if twopage
            viewerBookmark++;
            try {
                Object image = pageImageAt(viewerBookmark);
                if(image == null) {
                    viewerBookmark = Math.max(0, viewerBookmark - 1);
                    updatePageIndex();
                    return;
                }

                //placeholder
                frame.setImageDrawable(null);
                Glide.with(context)
                        .asBitmap()
                        .priority(Priority.IMMEDIATE)
                        .apply(viewerImageOptions())
                        .load(image)
                        .into(new CustomTarget<Bitmap>() {
                            @Override
                            public void onLoadCleared(@Nullable Drawable placeholder) {
                                //
                            }

                            @Override
                            public void onResourceReady(Bitmap bitmap,
                                                        Transition<? super Bitmap> transition) {
                                //refreshbtn.setVisibility(View.INVISIBLE);
                                bitmap = d.decode(bitmap, swidth);
                                int width = bitmap.getWidth();
                                int height = bitmap.getHeight();
                                if(width>height){
                                    imgCache = bitmap;
                                    type=0;
                                    if(reverse) frame.setImageBitmap(Bitmap.createBitmap(imgCache,0,0,width/2,height));
                                    else frame.setImageBitmap(Bitmap.createBitmap(imgCache,width/2,0,width/2,height));
                                }else{
                                    type=-1;
                                    frame.setImageBitmap(bitmap);
                                }
                                preload();
                            }
                            @Override
                            public void onLoadFailed(@Nullable Drawable errorDrawable) {
                                viewerBookmark = Math.max(0, viewerBookmark - 1);
                                frame.setImageDrawable(null);
                                updatePageIndex();
                            }
                        });

            }catch (Exception e){
                ml.melun.mangaview.report.CrashReporter.record(e);
                viewerBookmark--;
            }
        }
        if(manga.useBookmark()) {
            p.setViewerBookmark(manga, viewerBookmark);
            if (imgs.size() - 1 == viewerBookmark) p.removeViewerBookmark(manga);
            p.setBookmark(title, manga.getId());
        }
        updatePageIndex();
    }

    void prevPage(){
        if(imgs == null || imgs.size() == 0) {
            showViewerImagesUnavailable();
            return;
        }
        //refreshbtn.setVisibility(View.VISIBLE);
        if(nextEpisodeVisible){
            toggleNextEpisode();
        } else if(split){
            //첫페이지가 아닐 경우
            if(viewerBookmark>0){
                viewerBookmark--;
                frame.setVisibility(View.GONE);
                frame2.setVisibility(View.VISIBLE);
                frame.setImageDrawable(null);
                frame2.setImageDrawable(null);
                //오른쪽 부터 로드
                try {
                    Object image = pageImageAt(viewerBookmark);
                    if(image == null)
                        return;
                    //placeholder
                    Glide.with(context)
                            .asBitmap()
                            .priority(Priority.IMMEDIATE)
                            .apply(viewerImageOptions())
                            .load(image)
                            .into(new CustomTarget<Bitmap>() {
                                @Override
                                public void onLoadCleared(@Nullable Drawable placeholder) {

                                }

                                @Override
                                public void onResourceReady(Bitmap bitmap, Transition<? super Bitmap> transition) {
                                    bitmap = d.decode(bitmap, swidth);
                                    int width = bitmap.getWidth();
                                    int height = bitmap.getHeight();
                                    frame2.setImageBitmap(bitmap);
                                    type=1;
                                    if (width < height) {
                                        //portrait
                                        type = 1;
                                        if(viewerBookmark > 0){
                                            //이전 페이지 로드하고 landscape 인지 확인, portrait일 경우에만 보여주기
                                            Object image2 = pageImageAt(viewerBookmark - 1);
                                            if(image2 == null)
                                                return;
                                            Glide.with(context)
                                                    .asBitmap()
                                                    .priority(Priority.HIGH)
                                                    .apply(viewerImageOptions())
                                                    .load(image2)
                                                    .into(new CustomTarget<Bitmap>() {
                                                        @Override
                                                        public void onResourceReady(@NonNull Bitmap bitmap1, @Nullable Transition<? super Bitmap> transition) {
                                                            bitmap1 = d.decode(bitmap1, swidth);
                                                            int width = bitmap1.getWidth();
                                                            int height = bitmap1.getHeight();
                                                            if(width<height){
                                                                //second is portrait
                                                                type = 2;
                                                                frame.setVisibility(View.VISIBLE);
                                                                frame.setImageBitmap(bitmap1);
                                                                viewerBookmark--;
                                                                updatePageIndex();
                                                            }
                                                        }

                                                        @Override
                                                        public void onLoadCleared(@Nullable Drawable placeholder) {

                                                        }
                                                    });
                                        }
                                    }
                                    preload();
                                }
                                @Override
                                public void onLoadFailed(@Nullable Drawable errorDrawable) {
                                    frame.setImageDrawable(null);
                                }
                            });
                }catch(Exception e) {
                    ml.melun.mangaview.report.CrashReporter.record(e);
                    Utils.showCaptchaPopup(manga.getUrl(), context, e, p);
                }
                //일단 왼쪽거 냅두다가, 오른쪽이 landscape일 경우, GONE 처리
            }
        } else if(viewerBookmark==0 && (type==-1 || type==0)){
            //start of manga
            //refreshbtn.setVisibility(View.INVISIBLE);
        } else if(type==1){
            //is two page, current pos: left
            //refreshbtn.setVisibility(View.INVISIBLE);
            type = 0;
            int width = imgCache.getWidth();
            int height = imgCache.getHeight();
            if(reverse) frame.setImageBitmap(Bitmap.createBitmap(imgCache, 0, 0, width / 2, height));
            else frame.setImageBitmap(Bitmap.createBitmap(imgCache, width/2, 0, width / 2, height));
        }else{
            //is single page OR unidentified
            //decrease page
            //has to check if twopage
            viewerBookmark--;
            try {
                Object image = pageImageAt(viewerBookmark);
                if(image == null) {
                    viewerBookmark = Math.min(imgs.size() - 1, viewerBookmark + 1);
                    updatePageIndex();
                    return;
                }

                //placeholder
                frame.setImageDrawable(null);
                Glide.with(context)
                        .asBitmap()
                        .priority(Priority.IMMEDIATE)
                        .apply(viewerImageOptions())
                        .load(image)
                        .into(new CustomTarget<Bitmap>() {
                            @Override
                            public void onResourceReady(Bitmap bitmap, Transition<? super Bitmap> transition) {
                                bitmap = d.decode(bitmap, swidth);
                                //refreshbtn.setVisibility(View.INVISIBLE);
                                int width = bitmap.getWidth();
                                int height = bitmap.getHeight();
                                if(width>height){
                                    imgCache = bitmap;
                                    type=1;
                                    if(reverse) frame.setImageBitmap(Bitmap.createBitmap(imgCache, width/2, 0, width / 2, height));
                                    else frame.setImageBitmap(Bitmap.createBitmap(imgCache,0,0,width/2,height));
                                }else{
                                    type=-1;
                                    frame.setImageBitmap(bitmap);
                                }
                            }

                            @Override
                            public void onLoadCleared(@Nullable Drawable placeholder) {

                            }

                            @Override
                            public void onLoadFailed(@Nullable Drawable errorDrawable) {
                                viewerBookmark = Math.min(imgs.size() - 1, viewerBookmark + 1);
                                frame.setImageDrawable(null);
                                updatePageIndex();
                            }
                        });
            }catch (Exception e){
                ml.melun.mangaview.report.CrashReporter.record(e);
                viewerBookmark++;
            }
        }
        if(manga.useBookmark()) {
            p.setViewerBookmark(manga, viewerBookmark);
            if (0 == viewerBookmark) p.removeViewerBookmark(manga);
            p.setBookmark(title, manga.getId());
        }
        updatePageIndex();

    }



    void refreshImage(){
        if(imgs == null || imgs.size() == 0) {
            showViewerImagesUnavailable();
            return;
        }
        viewerBookmark = Utils.clampIndex(viewerBookmark, imgs.size());
        if(viewerBookmark < 0)
            return;
        int requestGeneration = ++imageLoadGeneration;
        int requestMangaId = manga == null ? -1 : manga.getId();
        int requestBookmark = viewerBookmark;
        Decoder requestDecoder = d == null ? new Decoder(manga == null ? 0 : manga.getSeed(), requestMangaId) : d;
        frame.setVisibility(View.VISIBLE);
        frame2.setVisibility(View.GONE);
        frame.setImageDrawable(null);
        if(split) frame2.setImageDrawable(null);
        //refreshbtn.setVisibility(View.VISIBLE);
        try {
            Bitmap cached = decodedPageFromWarmup(viewerBookmark);
            if(cached != null && !cached.isRecycled()) {
                renderPrimaryBitmap(cached, requestGeneration, requestMangaId, requestBookmark, requestDecoder, true);
                return;
            }
            Object image = pageImageAt(viewerBookmark);
            if(image == null)
                return;
            //placeholder
            //frame.setImageDrawable(null);
            Glide.with(context)
                    .asBitmap()
                    .priority(Priority.IMMEDIATE)
                    .apply(viewerImageOptions())
                    .load(image)
                    .into(new CustomTarget<Bitmap>() {
                        @Override
                        public void onLoadCleared(@Nullable Drawable placeholder) {

                        }

                        @Override
                        public void onResourceReady(Bitmap bitmap, Transition<? super Bitmap> transition) {
                            renderPrimaryBitmap(bitmap, requestGeneration, requestMangaId, requestBookmark, requestDecoder, false);
                        }
                        @Override
                        public void onLoadFailed(@Nullable Drawable errorDrawable) {
                            if(!isActiveImageRequest(requestGeneration, requestMangaId, requestBookmark))
                                return;
                            frame.setImageDrawable(null);
                        }
                    });
        }catch(Exception e) {
            ml.melun.mangaview.report.CrashReporter.record(e);
            Utils.showCaptchaPopup(manga.getUrl(), context, e, p);
        }
    }

    private Bitmap decodedPageFromWarmup(int pageIndex) {
        if(manga == null || imgs == null || pageIndex < 0 || pageIndex >= imgs.size())
            return null;
        String url = Utils.safeGet(imgs, pageIndex);
        if(url == null)
            return null;
        return ViewerWarmupManager.getDecodedBitmap(new PageItem(pageIndex, url, manga), false, reverse, swidth);
    }

    private void renderPrimaryBitmap(Bitmap bitmap, int requestGeneration, int requestMangaId, int requestBookmark,
                                     Decoder requestDecoder, boolean alreadyDecoded) {
        if(!isActiveImageRequest(requestGeneration, requestMangaId, requestBookmark))
            return;
        if(!alreadyDecoded)
            bitmap = requestDecoder.decode(bitmap, swidth);
        int width = bitmap.getWidth();
        int height = bitmap.getHeight();
        if (width > height) {
            if(split){
                type = 1;
                frame2.setVisibility(View.GONE);
                frame.setImageBitmap(bitmap);
            } else {
                imgCache = bitmap;
                type = 0;
                if (reverse) {
                    frame.setImageBitmap(Bitmap.createBitmap(imgCache, 0, 0, width / 2, height));
                } else {
                    frame.setImageBitmap(Bitmap.createBitmap(imgCache, width / 2, 0, width / 2, height));
                }
            }
        } else {
            type = -1;
            frame.setImageBitmap(bitmap);
            if(split){
                frame2.setVisibility(View.GONE);
                type = 1;
                loadSecondSplitPage(requestGeneration, requestMangaId, requestBookmark, requestDecoder);
            }
        }
        preload();
    }

    private void loadSecondSplitPage(int requestGeneration, int requestMangaId, int requestBookmark, Decoder requestDecoder) {
        if(viewerBookmark + 1 >= imgs.size())
            return;
        Bitmap cached = decodedPageFromWarmup(viewerBookmark + 1);
        if(cached != null && !cached.isRecycled()) {
            maybeShowSecondSplitPage(cached, requestGeneration, requestMangaId, requestBookmark, true, requestDecoder);
            return;
        }
        Object image2 = pageImageAt(viewerBookmark + 1);
        if(image2 == null)
            return;
        frame2.setImageDrawable(null);
        Glide.with(context)
                .asBitmap()
                .priority(Priority.HIGH)
                .apply(viewerImageOptions())
                .load(image2)
                .into(new CustomTarget<Bitmap>() {
                    @Override
                    public void onResourceReady(@NonNull Bitmap bitmap1, @Nullable Transition<? super Bitmap> transition) {
                        maybeShowSecondSplitPage(bitmap1, requestGeneration, requestMangaId, requestBookmark, false, requestDecoder);
                    }

                    @Override
                    public void onLoadCleared(@Nullable Drawable placeholder) {

                    }
                });
    }

    private void maybeShowSecondSplitPage(Bitmap bitmap, int requestGeneration, int requestMangaId, int requestBookmark,
                                          boolean alreadyDecoded, Decoder requestDecoder) {
        if(!isActiveImageRequest(requestGeneration, requestMangaId, requestBookmark))
            return;
        if(!alreadyDecoded)
            bitmap = requestDecoder.decode(bitmap, swidth);
        int width = bitmap.getWidth();
        int height = bitmap.getHeight();
        if (width < height) {
            frame2.setVisibility(View.VISIBLE);
            type = 2;
            frame2.setImageBitmap(bitmap);
        }
    }

    void preload(){
        if(manga != null && manga.isOnline()) {
            ViewerWarmupManager.preloadWindow(context, manga, viewerBookmark + 1, swidth, false, reverse, ViewerPreloadPolicy.scrollAheadWindow(p.getDataSave()));
            return;
        }
        int limit = p.getDataSave() ? 6 : 18;
        int preloaded = 0;
        for(int i = viewerBookmark + 1; manga != null && imgs != null && i < imgs.size() && preloaded < limit; i++, preloaded++) {
            Object image = pageImageAt(i);
            if(image == null)
                continue;
            Glide.with(context)
                    .asBitmap()
                    .priority(preloaded < 2 ? Priority.HIGH : Priority.NORMAL)
                    .apply(viewerImageOptions())
                    .load(image)
                    .addListener(new RequestListener<Bitmap>() {
                        @Override
                        public boolean onLoadFailed(@Nullable GlideException e, Object model, Target<Bitmap> target, boolean isFirstResource) {
                            return false;
                        }

                        @Override
                        public boolean onResourceReady(Bitmap resource, Object model, Target<Bitmap> target, DataSource dataSource, boolean isFirstResource) {
                            return false;
                        }
                    })
                    .preload();
        }
    }
    private RequestOptions viewerImageOptions() {
        return new RequestOptions()
                .diskCacheStrategy(DiskCacheStrategy.DATA)
                .downsample(DownsampleStrategy.AT_MOST)
                .override(Math.max(swidth, 1), Target.SIZE_ORIGINAL);
    }
    void updatePageIndex(){
        if(imgs == null || imgs.size() == 0) {
            pageBtn.setText("-/-");
            return;
        }
        viewerBookmark = Utils.clampIndex(viewerBookmark, imgs.size());
        pageBtn.setText(viewerBookmark+1+"/"+imgs.size());
        boolean lastPage = viewerBookmark == imgs.size()-1;
        boolean firstPage = viewerBookmark == 0;
        if(toolbarshow && !lastPage)
            toggleToolbar();
        else if(lastPage && !toolbarshow)
            toggleToolbar();
    }

    void toggleNextEpisode(){
        if(nextEpisodeVisible) {
            nextEpisodeVisible = false;
            nextEpisode.setVisibility(View.GONE);
        }else{
            nextEpisodeVisible = true;
            nextEpisode.setVisibility(View.VISIBLE);
        }
    }

    public void toggleToolbar(){
        //attrs = getWindow().getAttributes();
        if(toolbarshow){
            //hide toolbar
            appbar.animate().translationY(-appbar.getHeight());
            appbarBottom.animate().translationY(+appbarBottom.getHeight());
            toolbarshow=false;
            //toolbar_toggleBtn.setVisibility(View.VISIBLE);
            getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_FULLSCREEN);
        }
        else {
            //show toolbar
            appbar.animate().translationY(0);
            appbarBottom.animate().translationY(0);
            toolbarshow=true;
            //toolbar_toggleBtn.setVisibility(View.GONE);
            getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_VISIBLE);
        }
        //getWindow().setAttributes(attrs);
    }


    @Override
    public boolean dispatchKeyEvent(KeyEvent event) {
        int keyCode = event.getKeyCode();
        if (keyCode == p.getNextPageKey() ) {
            if(event.getAction() == KeyEvent.ACTION_UP)
                nextPage();
            return true;
        } else if(keyCode == p.getPrevPageKey()) {
            if(event.getAction() == KeyEvent.ACTION_UP)
                prevPage();
            return true;
        }
        return super.dispatchKeyEvent(event);
    }

    private class loadImages {
        AppDispatchers.TaskHandle handle;
        MangaRepository.Cancellation cancellation = MangaRepository.cancellation();
        volatile boolean cancelled;
        final boolean allowResumeFallback;
        final boolean startAtFirstPage;

        loadImages(boolean allowResumeFallback, boolean startAtFirstPage) {
            this.allowResumeFallback = allowResumeFallback;
            this.startAtFirstPage = startAtFirstPage;
        }

        private void postProgress(String value) {
        }

        void start() {
            handle = AppDispatchers.submitIo(() -> {
                Integer result = load();
                AppDispatchers.runOnMain(() -> finishLoad(result));
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
                    res = ViewerWarmupManager.prepareFirstFrame(context, manga, title, firstPage, swidth, false, reverse, cancellation);
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

        private void finishLoad(Integer res) {
            if(cancelled || isFinishing())
                return;
            if(activeImageLoad == this)
                activeImageLoad = null;

            if(res == LOAD_CAPTCHA) {
                //캡차 처리 팝업
                showCaptchaPopup(manga.getUrl(), context, RESULT_CAPTCHA, p);
                return;
            }

            if(res == ViewerWarmupManager.LOAD_EMPTY_IMAGES || !hasLoadedImages()) {
                showViewerImagesUnavailable();
                return;
            }

            reloadManga(startAtFirstPage);

            //show info overlay
            if(!dirty) {
                dirty = true;
                info.setVisibility(View.VISIBLE);
                info.setAlpha(1f);
                info.animate()
                        .alpha(0f)
                        .setDuration(4000)
                        .setListener(new AnimatorListenerAdapter() {
                            @Override
                            public void onAnimationEnd(Animator animation) {
                                info.setVisibility(View.GONE);
                            }
                        });
            }
        }

        void cancel() {
            cancelled = true;
            cancellation.cancel();
            if(handle != null)
                handle.cancel();
            if(activeImageLoad == this)
                activeImageLoad = null;
        }
    }

    private int prepareFirstAvailableManga(int firstPage, boolean skipTarget, MangaRepository.Cancellation cancellation) throws Exception {
        int lastResult = ViewerWarmupManager.LOAD_EMPTY_IMAGES;
        Title currentTitle = title != null ? title : manga == null ? null : manga.getTitle();
        for(Manga candidate : ViewerResumeResolver.candidates(manga, currentTitle, skipTarget)) {
            int page = ViewerResumeResolver.sameManga(candidate, manga) ? firstPage : 0;
            int result = ViewerWarmupManager.prepareFirstFrame(context, candidate, currentTitle, page, swidth, false, reverse, cancellation);
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

    private boolean isActiveImageRequest(int generation, int mangaId, int bookmark) {
        return generation == imageLoadGeneration
                && manga != null
                && manga.getId() == mangaId
                && viewerBookmark == bookmark
                && !isFinishing();
    }

    private int ensureEpisodeListLoaded(Manga target) {
        if(target == null || !target.isOnline())
            return 0;
        Title currentTitle = title != null ? title : target.getTitle();
        if(currentTitle == null)
            return 0;
        if(Utils.snapshotEpisodes(currentTitle).size() <= 1) {
            int result = MangaRepository.fetchEpisodes(currentTitle);
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
        if(activeImageLoad != null) {
            activeImageLoad.cancel();
            activeImageLoad = null;
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
            if(!Utils.safeStartActivity(context, episodeIntent))
                return false;
            finish();
            return true;
        } catch (Exception e) {
            ml.melun.mangaview.report.CrashReporter.record(e);
            return false;
        }
    }

    public void reloadManga(){
        reloadManga(consumeStartAtFirstPage());
    }

    public void reloadManga(boolean startAtFirstPage){
        try{
            lockUi(false);
            imgs = MangaRepository.imageUrls(manga, context);
            if(imgs == null || imgs.size()==0) {
                showViewerImagesUnavailable();
                return;
            }
            d = new Decoder(manga.getSeed(), manga.getId());
            bookmarkRefresh(startAtFirstPage);
            ViewerWarmupManager.preloadWindow(context, manga, viewerBookmark, swidth, false, reverse, ViewerPreloadPolicy.firstFrameWindow(p.getDataSave()));
            refreshToolbar();
            updateIntent();
            refreshImage();
            prewarmAdjacentEpisodes();
        }catch (Exception e){
            ml.melun.mangaview.report.CrashReporter.record(e);
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
    }

    public void updateIntent(){
        result = new Intent();
        result.putExtra("id", id);
        setResult(RESULT_OK, result);
    }

    public void loadManga(Manga m){
        if(m!=null) {
            if(title != null)
                m.setTitle(title);
            manga = m;
            id = manga.getId();
            if (m.isOnline())
                refresh();
            else
                reloadManga();
        }
    }

    public void refresh(){
        refresh(true);
    }

    public void refresh(boolean allowResumeFallback){
        captchaChecked = false;
        if(activeImageLoad != null)
            activeImageLoad.cancel();
        activeImageLoad = new loadImages(allowResumeFallback, consumeStartAtFirstPage());
        activeImageLoad.start();
    }

    private boolean consumeStartAtFirstPage() {
        boolean result = startCurrentEpisodeAtFirstPage;
        startCurrentEpisodeAtFirstPage = false;
        return result;
    }

    private boolean hasLoadedImages() {
        List<String> images = MangaRepository.imageUrls(manga, context);
        return images != null && images.size() > 0;
    }

    private void showViewerImagesUnavailable() {
        ViewerWarmupManager.logMetric("viewer_empty_images", manga == null ? -1 : manga.getId());
        Utils.showPopup(context, "오류", "회차 이미지를 불러오지 못했습니다.", (dialog, which) -> {
            if(!openEpisodeListIfRequested())
                ViewerActivity2.this.finish();
        }, dialog -> {
            if(!openEpisodeListIfRequested())
                ViewerActivity2.this.finish();
        });
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
        //refresh spinner
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

    private Object pageImageAt(int pageIndex) {
        String url = Utils.safeGet(imgs, pageIndex);
        if(url == null || manga == null)
            return null;
        return manga.isOnline() ? getGlideUrl(url, manga.getBaseMode()) : url;
    }

    private void prewarmAdjacentEpisodes() {
        if(eps == null || eps.size() == 0 || title == null)
            return;
        Manga nextEpisode = nextEpisodeCandidate();
        if(nextEpisode != null)
            ViewerWarmupManager.warmup(context, nextEpisode, title, 0);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, @Nullable Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (resultCode == RESULT_CAPTCHA) {
            refresh(false);
        }
    }

//    @Override
//    public void onConfigurationChanged(Configuration newConfig) {
//        super.onConfigurationChanged(newConfig);
//        //window orientation change
//        Display display  = getWindowManager().getDefaultDisplay();
//        Point size = new Point();
//        display.getSize(size);
//        if(imgs != null && viewerBookmark < imgs.size() && (split != size.x > size.y)) {
//            //needs update
//            split = size.x > size.y;
//            refreshImage();
//        }
//        refreshPageControlButton();
//    }

    void lockUi(Boolean lock){
        toolbar_toggleBtn.setEnabled(!lock);
        saveBtn.setEnabled(!lock);
        next.setEnabled(!lock);
        prev.setEnabled(!lock);
        pageBtn.setEnabled(!lock);
        touchToggleBtn.setEnabled(!lock);
        nextPageBtn.setEnabled(!lock);
        prevPageBtn.setEnabled(!lock);
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
    protected void onDestroy() {
        Utils.cancelPendingViewerLaunches(this);
        if(activeImageLoad != null) {
            activeImageLoad.cancel();
            activeImageLoad = null;
        }
        super.onDestroy();
    }


}
