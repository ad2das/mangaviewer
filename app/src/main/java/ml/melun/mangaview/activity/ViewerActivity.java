package ml.melun.mangaview.activity;

import android.content.Context;
import android.content.Intent;
import android.content.res.Configuration;
import android.graphics.Color;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.util.TypedValue;

import com.google.android.material.appbar.AppBarLayout;

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
import ml.melun.mangaview.repository.MangaRepository;
import ml.melun.mangaview.runtime.AppDispatchers;
import ml.melun.mangaview.runtime.PrefetchCoordinator;

import static ml.melun.mangaview.MainApplication.p;
import static ml.melun.mangaview.Utils.getScreenSize;
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
    ImageButton saveBtn;
    int width=0;
    Intent intent;
    boolean captchaChecked = false;
    ImageButton episodeButton;
    AlertDialog episodePickerDialog;
    InfiniteScrollCallback infiniteScrollCallback;
    LoadImagesJob loader;
    PrefetchImagesJob nextPrefetcher;
    final Handler mainHandler = new Handler(Looper.getMainLooper());
    int episodeLoaderGeneration = 0;
    int nextPrefetchEpisodeId = -1;
    int nextPrefetchBaseMode = -1;
    boolean previousEpisodeBoundaryLoading = false;
    boolean nextEpisodeBoundaryLoading = false;
    boolean previousEpisodeBoundaryJumpPending = false;
    boolean nextEpisodeBoundaryJumpPending = false;
    long lastBoundaryCheckMs = 0L;
    private static final int INITIAL_PRELOAD_AHEAD_COUNT = 18;
    private static final int NEXT_EPISODE_ATTACH_THRESHOLD = 22;
    private static final int DATA_SAVE_NEXT_EPISODE_ATTACH_THRESHOLD = 12;
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
        Utils.cancelPendingViewerLaunches(this);
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
        saveBtn = this.findViewById(R.id.viewerSaveButton);
        episodeButton = this.findViewById(R.id.toolbar_spinner);
        width = getScreenSize(getWindowManager().getDefaultDisplay());

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
            toolbarTitle.setSelected(true);

            strip = this.findViewById(R.id.strip);
            manager = new StripLayoutManager(this);
            manager.setOrientation(LinearLayoutManager.VERTICAL);
            strip.setItemViewCacheSize(p.getDataSave() ? 6 : 12);
            strip.setLayoutManager(manager);

            if(intent.getBooleanExtra("recent",false)){
                Intent resultIntent = new Intent();
                setResult(RESULT_OK,resultIntent);
            }

            if(!manga.isOnline()){
                saveBtn.setVisibility(View.GONE);
            }
            
            loadManga(manga);
            strip.setItemAnimator(null);
            strip.setOverScrollMode(View.OVER_SCROLL_NEVER);
            strip.setOnScrollListener(new RecyclerView.OnScrollListener() {
                @Override
                public void onScrollStateChanged(RecyclerView recyclerView, int newState) {
                    super.onScrollStateChanged(recyclerView, newState);
                    if(stripAdapter != null)
                        stripAdapter.setScrollBusy(newState != RecyclerView.SCROLL_STATE_IDLE);
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
                    loadEpisodeAtBoundaryIfNeededThrottled();
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
            ml.melun.mangaview.report.CrashReporter.record(e);
        }

        next.setOnClickListener(v -> loadManga(nextEpisodeCandidate(manga)));
        prev.setOnClickListener(v -> loadManga(previousEpisodeCandidate(manga)));
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
                    int page = Integer.parseInt(input.getText().toString());
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

    private void showEpisodePicker() {
        List<Manga> data = currentEpisodeList();
        if(data == null || data.size() == 0)
            return;
        int selected = findEpisodeIndex(data, manga);
        RecyclerView episodeList = new RecyclerView(context);
        int maxHeight = Math.min(dp(520), getResources().getDisplayMetrics().heightPixels - dp(160));
        episodeList.setLayoutParams(new ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, maxHeight));
        episodeList.setClipToPadding(false);
        episodeList.setPadding(0, dp(4), 0, dp(4));
        LinearLayoutManager pickerManager = new LinearLayoutManager(context);
        episodeList.setLayoutManager(pickerManager);
        episodeList.setItemAnimator(null);

        EpisodePickerAdapter adapter = new EpisodePickerAdapter(data, selected, selectedManga -> {
            if(episodePickerDialog != null)
                episodePickerDialog.dismiss();
            lockUi(true);
            loadManga(selectedManga);
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
        loadFullEpisodeListForPicker(adapter, episodeList, pickerManager);
    }

    private List<Manga> currentEpisodeList() {
        return episodeListFor(manga);
    }

    private List<Manga> episodeListFor(Manga current) {
        List<Manga> data = null;
        if(current != null)
            data = largerEpisodeList(data, Utils.snapshotEpisodes(current));
        data = largerEpisodeList(data, eps);
        Title currentTitle = title != null ? title : (current == null ? null : current.getTitle());
        if(currentTitle != null)
            currentTitle.ensureProgressEpisodes(current);
        if(currentTitle != null)
            data = largerEpisodeList(data, Utils.snapshotEpisodes(currentTitle));
        return data;
    }

    private Manga nextEpisodeCandidate(Manga current) {
        if(current == null)
            return null;
        Manga candidate = current.nextEp();
        if(candidate != null)
            return prepareEpisodeCandidate(candidate, current);
        List<Manga> data = episodeListFor(current);
        int index = findEpisodeIndex(data, current);
        if(index > 0)
            return prepareEpisodeCandidate(Utils.safeGet(data, index - 1), current);
        return null;
    }

    private Manga previousEpisodeCandidate(Manga current) {
        if(current == null)
            return null;
        Manga candidate = current.prevEp();
        if(candidate != null)
            return prepareEpisodeCandidate(candidate, current);
        List<Manga> data = episodeListFor(current);
        int index = findEpisodeIndex(data, current);
        if(data != null && index >= 0 && index < data.size() - 1)
            return prepareEpisodeCandidate(Utils.safeGet(data, index + 1), current);
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
        List<Manga> existingEpisodes = Utils.snapshotEpisodes(currentTitle);
        if(existingEpisodes.size() >= (current == null ? 0 : current.size()) && existingEpisodes.size() > 3)
            return;
        AppDispatchers.submitIo(() -> {
            try {
                int result = MangaRepository.fetchEpisodes(currentTitle);
                if(result == LOAD_CAPTCHA || isFinishing())
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
            return (((long)item.getBaseMode()) << 48)
                    ^ (((long)item.getTitleId() & 0xffffL) << 32)
                    ^ (item.getId() & 0xffffffffL);
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
            setManga(m);
        }
    }


    public void setManga(Manga m){
        try {
            manga = m;
            lockUi(false);
            if(MangaRepository.imageUrls(m, context) == null || MangaRepository.imageUrls(m, context).size()==0) {
                showViewerImagesUnavailable(m);
                return;
            }
            releaseStripAdapter();
            stripAdapter = new StripAdapter(context, m, autoCut, width,title, infiniteScrollCallback);
            preloadInitialViewerPages(m);

            refreshAdapter();
            prepareInitialViewerPosition(m);
            bookmarkRefresh(m);
            scheduleFocusedPagePreload();
            refreshToolbar(m);
            updateIntent(m);
            prefetchNextEpisode(m);
            prefetchPreviousEpisode(m);
            PrefetchCoordinator.prefetchAdjacentEpisode(context, m, title, width, autoCut, p.getReverse());

        }catch (Exception e){
            Utils.showCaptchaPopup(m.getUrl(), context, e, p);
            ml.melun.mangaview.report.CrashReporter.record(e);
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
        PageItem page = getFocusedVisiblePage();
        if(page == null || page.manga == null || stripAdapter == null)
            return;
        autoCut = !autoCut;
        updateAutoCutButtonState();
        releaseStripAdapter();
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
        MangaRepository.Cancellation cancellation = MangaRepository.cancellation();
        AppDispatchers.TaskHandle handle;
        volatile boolean cancelled = false;
        Runnable timeoutGuard;

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
                timeoutGuard = () -> {
                    if(loader != this || cancelled || isFinishing())
                        return;
                    ViewerWarmupManager.logMetric("viewer_initial_load_timeout", m == null ? 0 : m.getId());
                    cancel();
                    showViewerImagesUnavailable(m);
                };
                mainHandler.postDelayed(timeoutGuard, 12000);
            }
            handle = AppDispatchers.submitUserAction(() -> {
                int result = LOAD_OK;
                try {
                    if(m.isOnline()) {
                        result = prepareEpisodeIdentity(m);
                        int firstPage = m.useBookmark() ? p.getViewerBookmark(m) : 0;
                        if(result == LOAD_OK && shouldResolveResumeBeforeDirectFetch(m)) {
                            result = ensureEpisodeListLoaded(m);
                            PreparedManga prepared = result == LOAD_OK
                                    ? prepareFirstAvailableManga(m, firstPage, cancellation)
                                    : new PreparedManga(null, result);
                            result = prepared.result;
                            if(prepared.manga != null)
                                m = prepared.manga;
                        } else if(result == LOAD_OK) {
                            result = ViewerWarmupManager.prepareFirstFrame(context, m, title, firstPage, width, autoCut, p.getReverse(), cancellation);
                        }
                        if(result == ViewerWarmupManager.LOAD_EMPTY_IMAGES || !hasLoadedImages(m)) {
                            result = ensureEpisodeListLoaded(m);
                            PreparedManga prepared = result == LOAD_OK
                                    ? prepareFirstAvailableManga(m, firstPage, cancellation)
                                    : new PreparedManga(null, result);
                            result = prepared.result;
                            if(prepared.manga != null)
                                m = prepared.manga;
                        }
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

        void finish(Integer res) {
            if(cancelled || isFinishing())
                return;
            if(loader != this)
                return;
            if(timeoutGuard != null)
                mainHandler.removeCallbacks(timeoutGuard);
            loader = null;
            if(res == LOAD_CAPTCHA){
                //캡차 처리 팝업
                if(lockui) lockUi(false);
                resetOnBackPressed();
                showTokiCaptchaPopup(context, p);
                return;
            }
            if(res == ViewerWarmupManager.LOAD_EMPTY_IMAGES || !hasLoadedImages(m)) {
                if(lockui) lockUi(false);
                resetOnBackPressed();
                showViewerImagesUnavailable(m);
                return;
            }

            if(lockui) lockUi(false);
            if (title == null)
                title = m.getTitle();
            resetOnBackPressed();
            preloadInitialRequestWindow(m);
            callback.post(m);
            if(lockui)
                hydrateEpisodeListAfterFirstFrame(m);
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
        long startedAtMs;

        PrefetchImagesJob(Manga target) {
            this.target = target;
        }

        void start() {
            startedAtMs = android.os.SystemClock.elapsedRealtime();
            handle = AppDispatchers.submitImageWarmup(() -> {
                int result = LOAD_OK;
                try {
                    if(target != null && target.isOnline()) {
                        result = ensureEpisodeListLoaded(target);
                    }
                    if(target != null && target.isOnline() && result == LOAD_OK)
                        result = ViewerWarmupManager.prepareFirstFrame(context, target, title, 0, width, autoCut, p.getReverse(), cancellation);
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
            ViewerWarmupManager.logMetric("viewer_next_episode_ready_ms", android.os.SystemClock.elapsedRealtime() - startedAtMs);
            int attachThreshold = p.getDataSave() ? DATA_SAVE_NEXT_EPISODE_ATTACH_THRESHOLD : NEXT_EPISODE_ATTACH_THRESHOLD;
            if(stripAdapter != null && manager != null && manager.findLastVisibleItemPosition() >= manager.getItemCount() - attachThreshold)
                attachNextEpisode(false);
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
        if(m.useBookmark()) {
            int pageIndex = p.getViewerBookmark(m);
            List<String> images = MangaRepository.imageUrls(m, context);
            if(images != null && images.size() > 0 && pageIndex >= images.size())
                pageIndex = images.size() - 1;
            if(pageIndex < 0)
                pageIndex = 0;
            PageItem page = new PageItem(pageIndex, "", m);
            if(stripAdapter != null && stripAdapter.findPagePosition(page) != RecyclerView.NO_POSITION)
                manager.scrollToPageWithOffset(page, p.getViewerBookmarkOffset(m));
            else
                manager.scrollToPage(new PageItem(0,"",m));
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
        restoreTitleEpisodes(currentTitle, target);
        if(needsFullEpisodeList(currentTitle, target)) {
            int result = MangaRepository.fetchEpisodes(currentTitle);
            if(result == LOAD_CAPTCHA)
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
        int lastResult = ViewerWarmupManager.LOAD_EMPTY_IMAGES;
        Title currentTitle = title != null ? title : target == null ? null : target.getTitle();
        for(Manga candidate : ViewerResumeResolver.candidates(target, currentTitle, shouldResolveResumeBeforeDirectFetch(target))) {
            if(candidate == null)
                continue;
            int page = ViewerResumeResolver.sameManga(candidate, target) ? firstPage : 0;
            int result = ViewerWarmupManager.prepareFirstFrame(context, candidate, title, page, width, autoCut, p.getReverse(), cancellation);
            if(result == LOAD_CAPTCHA)
                return new PreparedManga(null, result);
            lastResult = result;
            if(result == LOAD_OK && hasLoadedImages(candidate)) {
                if(!ViewerResumeResolver.sameManga(candidate, target))
                    ViewerWarmupManager.logMetric("viewer_resume_episode_fallback", candidate.getId());
                return new PreparedManga(candidate, LOAD_OK);
            }
        }
        return new PreparedManga(null, lastResult);
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
        return "episodeSnapshotV1_" + (targetTitle == null ? 0 : targetTitle.getBaseMode()) + "_" + (targetTitle == null ? 0 : targetTitle.getId());
    }

    private static class CachedEpisodes {
        long savedAt;
        ArrayList<Manga> episodes;
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
                    int result = MangaRepository.fetchEpisodes(currentTitle);
                    if(result == LOAD_CAPTCHA || isFinishing())
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
            Title bookmarkTitle = titleForProgress(page.manga);
            if(title == null)
                title = bookmarkTitle;
            p.setBookmark(bookmarkTitle, page.manga.getId());
            return;
        }
    }

    private void prepareInitialViewerPosition(Manga target) {
        if(stripAdapter == null || manager == null || target == null)
            return;
        int pageIndex = target.useBookmark() ? p.getViewerBookmark(target) : 0;
        if(pageIndex < 0)
            pageIndex = 0;
        PageItem page = new PageItem(pageIndex, "", target);
        int position = stripAdapter.findPagePosition(page);
        if(position == RecyclerView.NO_POSITION)
            return;
        int offset = target.useBookmark() ? p.getViewerBookmarkOffset(target) : 0;
        manager.scrollToPositionWithOffset(position, offset);
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
        List<String> images = MangaRepository.imageUrls(target, context);
        if(images == null || images.size() == 0)
            return;
        if(pageIndex < 0 || pageIndex >= images.size())
            pageIndex = 0;
        stripAdapter.preloadInitialAroundPage(new PageItem(pageIndex, "", target));
    }

    private void preloadInitialRequestWindow(Manga target) {
        if(target == null || !target.isOnline())
            return;
        List<String> images = MangaRepository.imageUrls(target, context);
        if(images == null || images.size() == 0)
            return;
        int pageIndex = target.useBookmark() ? p.getViewerBookmark(target) : 0;
        if(pageIndex < 0 || pageIndex >= images.size())
            pageIndex = 0;
        ViewerWarmupManager.preloadWindow(context, target, pageIndex, width, autoCut, p.getReverse(), ViewerPreloadPolicy.firstFrameWindow(p.getDataSave()));
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

    private void loadEpisodeAtBoundaryIfNeededThrottled() {
        long now = android.os.SystemClock.uptimeMillis();
        if(now - lastBoundaryCheckMs < 80)
            return;
        lastBoundaryCheckMs = now;
        loadEpisodeAtBoundaryIfNeeded();
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
        Manga target = nextEpisodeCandidate(page.manga);
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
        if(current == null || !current.isOnline())
            return;
        Manga target = nextEpisodeCandidate(current);
        if(target == null)
            return;
        if(nextPrefetcher != null
                && nextPrefetchEpisodeId == target.getId()
                && nextPrefetchBaseMode == target.getBaseMode())
            return;
        if(hasLoadedImages(target)) {
            preloadFirstPages(target);
            if(stripAdapter != null && manager != null && manager.findLastVisibleItemPosition() >= manager.getItemCount() - NEXT_EPISODE_ATTACH_THRESHOLD)
                attachNextEpisode(false);
            return;
        }
        if(nextPrefetcher != null)
            nextPrefetcher.cancel();
        nextPrefetchEpisodeId = target.getId();
        nextPrefetchBaseMode = target.getBaseMode();
        nextPrefetcher = new PrefetchImagesJob(target);
        nextPrefetcher.start();
    }

    private void prefetchPreviousEpisode(Manga current) {
        if(current == null || !current.isOnline())
            return;
        Manga target = previousEpisodeCandidate(current);
        if(target == null)
            return;
        if(hasLoadedImages(target)) {
            ViewerWarmupManager.preloadWindow(context, target, 0, width, autoCut, p.getReverse(), ViewerPreloadPolicy.nextEpisodeWindow(p.getDataSave()));
            return;
        }
        ViewerWarmupManager.warmup(context, target, title);
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
            List<String> loadedImages = MangaRepository.imageUrls(target, context);
            return loadedImages != null && loadedImages.size() > 0;
        } catch (Exception e) {
            return false;
        }
    }

    private boolean needsFullEpisodeList(Title currentTitle, Manga target) {
        List<Manga> titleEpisodes = currentTitle == null ? null : Utils.snapshotEpisodes(currentTitle);
        List<Manga> targetEpisodes = target == null ? null : Utils.snapshotEpisodes(target);
        int titleCount = titleEpisodes == null ? 0 : titleEpisodes.size();
        int targetCount = targetEpisodes == null ? 0 : targetEpisodes.size();
        return !containsEpisode(titleEpisodes, target) || Math.max(titleCount, targetCount) <= 3;
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
        return first != null && sameManga(first.manga != null ? previousEpisodeCandidate(first.manga) : null, target);
    }

    private boolean isNextTargetStillExpected(Manga target) {
        PageItem last = getLastVisiblePage();
        return last != null && sameManga(last.manga != null ? nextEpisodeCandidate(last.manga) : null, target);
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
            refresh();
        }
    }

    @Override
    protected void onDestroy() {
        Utils.cancelPendingViewerLaunches(this);
        if(loader != null)
            loader.cancel();
        cancelNextPrefetcher();
        releaseStripAdapter();
        ViewerWarmupManager.clearDecodedWork(context);
        super.onDestroy();
    }

    private void releaseStripAdapter() {
        if(stripAdapter != null)
            stripAdapter.release();
    }

    private void showViewerImagesUnavailable(Manga target) {
        ViewerWarmupManager.logMetric("viewer_empty_images", target == null ? -1 : target.getId());
        showPopup(context, "오류", "회차 이미지를 불러오지 못했습니다.", (dialog, which) -> {
            if(!openEpisodeListIfRequested())
                ViewerActivity.this.finish();
        }, dialog -> {
            if(!openEpisodeListIfRequested())
                ViewerActivity.this.finish();
        });
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
