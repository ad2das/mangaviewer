package ml.melun.mangaview.fragment;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.res.ColorStateList;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.os.SystemClock;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.PopupMenu;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;
import androidx.core.view.ViewCompat;
import androidx.fragment.app.Fragment;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.tabs.TabLayout;
import com.google.android.material.button.MaterialButton;
import ml.melun.mangaview.interfaces.MainActivityCallback;
import ml.melun.mangaview.R;
import ml.melun.mangaview.Preference;
import ml.melun.mangaview.Utils;
import ml.melun.mangaview.activity.MainActivity;
import ml.melun.mangaview.activity.TagSearchActivity;
import ml.melun.mangaview.adapter.MainAdapter;
import ml.melun.mangaview.adapter.MainWebtoonAdapter;
import ml.melun.mangaview.interfaces.UrlUpdateCallback;
import ml.melun.mangaview.mangaview.Manga;
import ml.melun.mangaview.mangaview.MainPageWebtoon;
import ml.melun.mangaview.mangaview.Title;
import ml.melun.mangaview.runtime.PerformanceMonitor;
import ml.melun.mangaview.ui.NpaLinearLayoutManager;

import static ml.melun.mangaview.MainApplication.p;
import static ml.melun.mangaview.MainApplication.getHttpClient;
import static ml.melun.mangaview.Utils.episodeIntent;
import static ml.melun.mangaview.Utils.openViewer;
import static ml.melun.mangaview.activity.CaptchaActivity.RESULT_CAPTCHA;
import static ml.melun.mangaview.mangaview.MTitle.base_comic;
import static ml.melun.mangaview.mangaview.MTitle.base_webtoon;

public class MainMain extends Fragment{

    RecyclerView mainRecycler;
    RecyclerView webtoonRecycler;
    RecyclerView comicRecycler;
    MainWebtoonAdapter mainComicAdapter;
    MainWebtoonAdapter mainWebtoonAdapter;
    MainAdapter.onItemClick homeClickListener;
    Fragment fragment;
    boolean wait = false;
    UrlUpdateCallback callback;
    MainActivityCallback mainActivityCallback;
    TabLayout mainTabLayout;
    TextView modeWebtoon;
    TextView modeComic;
    View homeLoadStatus;
    TextView homeLoadStatusText;
    View homeRetryButton;
    int selectedBaseMode = base_webtoon;

    final static int FOR_YOU_TAB = 0;
    final static int POPULAR_TAB = 1;
    final static int NEW_TAB = 2;
    final static int GENRES_TAB = 3;
    final static int HOME_FETCH_IDLE = 0;
    final static int HOME_FETCH_LOADING = 1;
    final static int HOME_FETCH_PARTIAL = 2;
    final static int HOME_FETCH_COMPLETE = 3;
    final static int HOME_FETCH_FAILED = 4;

    boolean fragmentActive = false;
    int comicFetchState = HOME_FETCH_IDLE;
    int webtoonFetchState = HOME_FETCH_IDLE;
    boolean viewStarted = false;
    int scrollRequestVersion = 0;
    long lastDestinationLaunchAt = 0L;
    long homeCreatedAt = 0L;
    private static final long DESTINATION_LAUNCH_DEBOUNCE_MS = 1500L;
    int inactivePrefetchRequestVersion = 0;
    Preference.LocalChangeListener localChangeListener;

    public void setWait(Boolean wait){
        this.wait = wait;
    }


    public static MainMain newInstance(){
        MainMain frag = new MainMain();
        frag.initializeCallback();
        return frag;
    }

    public MainMain(){

    }

    @Override
    public void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        this.mainActivityCallback = (MainActivity)getActivity();
    }

    public void initializeCallback(){
        callback = success -> {
            wait = false;
            comicFetchState = HOME_FETCH_IDLE;
            webtoonFetchState = HOME_FETCH_IDLE;
            if(mainComicAdapter != null)
                mainComicAdapter.resetForSiteChange();
            if(mainWebtoonAdapter != null)
                mainWebtoonAdapter.resetForSiteChange();
            if(!canUseHomeUi())
                return;
            applySelectedHomeTab();
            scrollToSelectedTab();
            inactivePrefetchRequestVersion++;
            if(fragmentActive)
                fetchSelected();
        };
    }

    public UrlUpdateCallback getCallback(){
        return callback;
    }

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
        ViewGroup rootView = (ViewGroup) inflater.inflate(R.layout.content_main , container, false);
        homeCreatedAt = SystemClock.uptimeMillis();


        mainTabLayout = rootView.findViewById(R.id.mainTab);
        modeWebtoon = rootView.findViewById(R.id.modeWebtoon);
        modeComic = rootView.findViewById(R.id.modeComic);
        homeLoadStatus = rootView.findViewById(R.id.homeLoadStatus);
        homeLoadStatusText = rootView.findViewById(R.id.homeLoadStatusText);
        homeRetryButton = rootView.findViewById(R.id.homeRetryButton);
        if(homeRetryButton != null)
            homeRetryButton.setOnClickListener(view -> retrySelectedHome());
        applyHomeTheme(rootView);

        TabLayout.Tab forYouTab = mainTabLayout.newTab().setText("홈");
        TabLayout.Tab popularTab = mainTabLayout.newTab().setText("인기");
        TabLayout.Tab newTab = mainTabLayout.newTab().setText("신작");
        TabLayout.Tab genresTab = mainTabLayout.newTab().setText("장르");
        mainTabLayout.addTab(forYouTab);
        mainTabLayout.addTab(popularTab);
        mainTabLayout.addTab(newTab);
        mainTabLayout.addTab(genresTab);
        forYouTab.select();

        mainTabLayout.addOnTabSelectedListener(new TabLayout.OnTabSelectedListener() {
            @Override
            public void onTabSelected(TabLayout.Tab tab) {
                applySelectedHomeTab();
                scrollToSelectedTab();
            }

            @Override
            public void onTabUnselected(TabLayout.Tab tab) {

            }

            @Override
            public void onTabReselected(TabLayout.Tab tab) {
                applySelectedHomeTab();
                scrollToSelectedTab();
            }
        });

        fragment = this;
        //main content
        // 최근 추가된 만화
        webtoonRecycler = rootView.findViewById(R.id.main_recycler);
        comicRecycler = rootView.findViewById(R.id.main_comic_recycler);
        selectedBaseMode = p.getBaseMode() == base_comic ? base_comic : base_webtoon;
        configureHomeRecycler(getSelectedRecycler());
        mainRecycler = null;
        localChangeListener = scope -> {
            if(!"recent".equals(scope) && !"bookmark".equals(scope))
                return;
            RecyclerView target = mainRecycler;
            if(target != null)
                target.post(this::refreshHomeLocalState);
        };
        p.addLocalChangeListener(localChangeListener);


        homeClickListener = new MainAdapter.onItemClick() {

            @Override
            public void clickedTitle(Title t) {
                if(!canLaunchDestination())
                    return;
                cancelHomeFetches();
                startActivity(episodeIntent(getContext(), t));
            }

            @Override
            public void clickedManga(Manga m) {
                if(!canLaunchDestination())
                    return;
                //mget title from manga m and start intent for manga m
                //getTitleFromManga intentStarter = new getTitleFromManga();
                //intentStarter.execute(m);
                cancelHomeFetches();
                Utils.openContinueViewer(getContext(), m, -1);
            }

            @Override
            public void clickedGenre(String t) {
                if(!canLaunchDestination())
                    return;
                cancelHomeFetches();
                Intent i = new Intent(getContext(), TagSearchActivity.class);
                i.putExtra("query",t);
                i.putExtra("mode",2);
                i.putExtra("baseMode", p.getBaseMode());
                startActivity(i);
            }

            @Override
            public void clickedName(String t) {
                if(!canLaunchDestination())
                    return;
                cancelHomeFetches();
                Intent i = new Intent(getContext(), TagSearchActivity.class);
                i.putExtra("query",t);
                i.putExtra("mode",3);
                i.putExtra("baseMode", p.getBaseMode());
                startActivity(i);
            }

            @Override
            public void clickedRelease(String t) {
                if(!canLaunchDestination())
                    return;
                cancelHomeFetches();
                Intent i = new Intent(getContext(), TagSearchActivity.class);
                i.putExtra("query",t);
                i.putExtra("mode",4);
                i.putExtra("baseMode", p.getBaseMode());
                startActivity(i);
            }

            @Override
            public void clickedMoreUpdated() {
                if(!canLaunchDestination())
                    return;
                cancelHomeFetches();
                Intent i = new Intent(getContext(), TagSearchActivity.class);
                i.putExtra("mode",5);
                startActivity(i);
            }

            @Override
            public void captchaCallback() {
                if(shouldSuppressAutoCaptcha())
                    return;
                Utils.showCaptchaPopup(getActivity(), 3, fragment, p);
            }

            @Override
            public void clickedSearch(String query) {
                mainActivityCallback.search(query);
            }

            @Override
            public void clickedRetry() {
                fetchSelected();
            }

            @Override
            public void clickedCategoryPath(String title, String path) {
                if(!canLaunchDestination())
                    return;
                boolean ntkSite = getHttpClient().isNtk();
                String currentSitePath = MainPageWebtoon.resolveCurrentSiteFilterPath(title, p.getBaseMode(), ntkSite);
                String launchPath = currentSitePath.length() > 0 ? currentSitePath : path;
                if(!MainPageWebtoon.isFilterPathForSite(launchPath, ntkSite)) {
                    Toast.makeText(getContext(), "사이트 전환 중입니다. 다시 눌러주세요.", Toast.LENGTH_SHORT).show();
                    if(callback != null)
                        callback.callback(true);
                    return;
                }
                Intent i = new Intent(getContext(), TagSearchActivity.class);
                i.putExtra("query", launchPath);
                i.putExtra("title", title);
                i.putExtra("mode", 8);
                i.putExtra("baseMode", p.getBaseMode());
                cancelHomeFetches();
                startActivity(i);
            }

            @Override
            public void clickedHomeAction(int action) {
                if(mainActivityCallback == null)
                    return;
                if(action == MainWebtoonAdapter.ACTION_UPDATES)
                    selectHomeTab(NEW_TAB);
                else if(action == MainWebtoonAdapter.ACTION_BOOKMARKS)
                    mainActivityCallback.navigateToTab(2);
                else if(action == MainWebtoonAdapter.ACTION_DOWNLOADS)
                    mainActivityCallback.navigateToTab(2);
                else if(action == MainWebtoonAdapter.ACTION_GENRES) {
                    TabLayout.Tab tab = mainTabLayout == null ? null : mainTabLayout.getTabAt(GENRES_TAB);
                    if(tab != null)
                        tab.select();
                }
            }

            @Override
            public void longClickedContinue(View view, Title title) {
                showContinuePopup(view, title);
            }
        };

        ensureHomeAdapter(selectedBaseMode);
        updateFirstContentMetricTargets();
        showInitialHomeRows(selectedBaseMode);
        modeWebtoon.setOnClickListener(v -> {
            switchBaseMode(base_webtoon);
        });
        modeComic.setOnClickListener(v -> {
            switchBaseMode(base_comic);
        });
        switchBaseMode(selectedBaseMode);

        RecyclerView selectedRecycler = getSelectedRecycler();
        if(selectedRecycler != null) {
            final int initialBaseMode = selectedBaseMode;
            selectedRecycler.postDelayed(() -> {
                if(!isAdded())
                    return;
                if(maybeOpenNtkCaptcha())
                    return;
                if(!wait)
                    fetchSelected();
                int inactiveBaseMode = initialBaseMode == base_comic ? base_webtoon : base_comic;
                selectedRecycler.postDelayed(() -> {
                    if(isAdded() && initialBaseMode == selectedBaseMode)
                        showInitialHomeRows(inactiveBaseMode);
                }, HomeStartupPolicy.inactiveInitialRowsDelayMs(getHttpClient().isNtk()));
            }, HomeStartupPolicy.activeFetchDelayMs(getHttpClient().isNtk()));
        }
        return rootView;
    }

    private boolean canLaunchDestination() {
        Activity activity = getActivity();
        if(!(isAdded()
                && isResumed()
                && activity != null
                && !activity.isFinishing()
                && !activity.isDestroyed()
                && activity.hasWindowFocus()))
            return false;
        long now = SystemClock.uptimeMillis();
        if(now - lastDestinationLaunchAt < DESTINATION_LAUNCH_DEBOUNCE_MS)
            return false;
        lastDestinationLaunchAt = now;
        return true;
    }

    private void configureHomeRecycler(RecyclerView recyclerView) {
        if(recyclerView == null)
            return;
        if(recyclerView.getLayoutManager() != null)
            return;
        NpaLinearLayoutManager lm = new NpaLinearLayoutManager(getContext());
        recyclerView.setLayoutManager(lm);
        recyclerView.setHasFixedSize(true);
        recyclerView.setItemViewCacheSize(18);
        recyclerView.setItemAnimator(null);
        recyclerView.setOverScrollMode(View.OVER_SCROLL_NEVER);
        recyclerView.addOnScrollListener(new RecyclerView.OnScrollListener() {
            @Override
            public void onScrollStateChanged(@NonNull RecyclerView recyclerView, int newState) {
                super.onScrollStateChanged(recyclerView, newState);
                if(getContext() == null || !isAdded())
                    return;
                PerformanceMonitor.phase(newState == RecyclerView.SCROLL_STATE_IDLE ? "idle" : "scrolling");
                if(newState == RecyclerView.SCROLL_STATE_IDLE)
                    PerformanceMonitor.reportNow("home_scroll_idle");
            }
        });
    }

    private void registerRevealObserver(MainWebtoonAdapter adapter, RecyclerView recyclerView, int baseMode) {
        if(adapter == null || recyclerView == null)
            return;
        adapter.registerAdapterDataObserver(new RecyclerView.AdapterDataObserver() {
            @Override
            public void onChanged() {
                revealIfSelectedAndReady();
            }

            @Override
            public void onItemRangeInserted(int positionStart, int itemCount) {
                revealIfSelectedAndReady();
            }

            private void revealIfSelectedAndReady() {
                if(selectedBaseMode != baseMode || mainRecycler != recyclerView || !adapter.hasDisplayContent())
                    return;
                recyclerView.post(() -> {
                    if(selectedBaseMode == baseMode && mainRecycler == recyclerView && adapter.hasDisplayContent() && recyclerView.getAlpha() < 1f) {
                        showSelectedRecycler(getOtherRecycler(recyclerView), recyclerView);
                        scrollHomeToTop();
                    }
                });
            }
        });
    }

    private MainWebtoonAdapter ensureHomeAdapter(int baseMode) {
        Context context = getContext();
        if(context == null || !isAdded())
            return baseMode == base_comic ? mainComicAdapter : mainWebtoonAdapter;
        configureHomeRecycler(getRecyclerForBaseMode(baseMode));
        if(baseMode == base_comic) {
            if(mainComicAdapter == null) {
                mainComicAdapter = new MainWebtoonAdapter(context, base_comic);
                mainComicAdapter.setListener(homeClickListener);
                mainComicAdapter.setFetchStateListener(this::onHomeFetchFinished);
            mainComicAdapter.setAnchorRecycler(comicRecycler);
            if(comicRecycler != null)
                comicRecycler.setAdapter(mainComicAdapter);
            registerRevealObserver(mainComicAdapter, comicRecycler, base_comic);
        }
        mainComicAdapter.setFirstContentMetricEnabled(selectedBaseMode == base_comic);
        return mainComicAdapter;
        }
        if(mainWebtoonAdapter == null) {
            mainWebtoonAdapter = new MainWebtoonAdapter(context);
            mainWebtoonAdapter.setListener(homeClickListener);
            mainWebtoonAdapter.setFetchStateListener(this::onHomeFetchFinished);
            mainWebtoonAdapter.setAnchorRecycler(webtoonRecycler);
            if(webtoonRecycler != null)
                webtoonRecycler.setAdapter(mainWebtoonAdapter);
            registerRevealObserver(mainWebtoonAdapter, webtoonRecycler, base_webtoon);
        }
        mainWebtoonAdapter.setFirstContentMetricEnabled(selectedBaseMode == base_webtoon);
        return mainWebtoonAdapter;
    }

    private void updateFirstContentMetricTargets() {
        if(mainWebtoonAdapter != null)
            mainWebtoonAdapter.setFirstContentMetricEnabled(selectedBaseMode == base_webtoon);
        if(mainComicAdapter != null)
            mainComicAdapter.setFirstContentMetricEnabled(selectedBaseMode == base_comic);
    }

    private MainWebtoonAdapter getSelectedAdapter() {
        ensureHomeAdapter(selectedBaseMode);
        return selectedBaseMode == base_comic ? mainComicAdapter : mainWebtoonAdapter;
    }

    private boolean canUseHomeUi() {
        return isAdded() && getContext() != null && webtoonRecycler != null && comicRecycler != null;
    }

    private int getSelectedTabPosition() {
        if(mainTabLayout == null || mainTabLayout.getSelectedTabPosition() < 0)
            return FOR_YOU_TAB;
        return mainTabLayout.getSelectedTabPosition();
    }

    private void switchBaseMode(int baseMode) {
        if(!canUseHomeUi())
            return;
        Log.d("ViewerPerf", "ntk_home_mode_switch_request from=" + selectedBaseMode
                + ",to=" + baseMode
                + ",resumed=" + isResumed()
                + ",wait=" + wait);
        RecyclerView previousRecycler = mainRecycler;
        RecyclerView targetRecycler = baseMode == base_comic ? comicRecycler : webtoonRecycler;
        if(selectedBaseMode == baseMode && mainRecycler == targetRecycler) {
            if(shouldRetrySelectedHomeOnReselect(selectedFetchState()))
                retrySelectedHome();
            return;
        }
        hideHomeLoadStatus();
        boolean initialAttach = mainRecycler == null;
        selectedBaseMode = baseMode;
        p.setBaseMode(baseMode);
        PerformanceMonitor.updateSiteMode();
        ensureHomeAdapter(baseMode);
        updateFirstContentMetricTargets();
        updateModeToggle();
        MainWebtoonAdapter selectedAdapter = getSelectedAdapter();
        boolean alreadyHasRows = selectedAdapter != null && selectedAdapter.hasDisplayContent();
        if(selectedAdapter != null && !alreadyHasRows)
            selectedAdapter.showPlaceholderIfEmpty();
        mainRecycler = targetRecycler;
        if(mainRecycler != null) {
            if(previousRecycler != null)
                previousRecycler.stopScroll();
            mainRecycler.stopScroll();
            showSelectedRecycler(previousRecycler, mainRecycler);
            prepareSelectedHomeAfterSwitch(baseMode, initialAttach && alreadyHasRows);
        }
        Log.d("ViewerPerf", "ntk_home_mode_switch_rendered selected=" + selectedBaseMode
                + ",hasRows=" + alreadyHasRows
                + ",webtoonVisible=" + (webtoonRecycler != null && webtoonRecycler.isShown())
                + ",comicVisible=" + (comicRecycler != null && comicRecycler.isShown()));
    }

    private void prepareSelectedHomeAfterSwitch(int baseMode, boolean initialRowsAlreadyShown) {
        RecyclerView recyclerView = getSelectedRecycler();
        if(recyclerView == null)
            return;
        final int requestBaseMode = baseMode;
        recyclerView.post(() -> {
            if(!isAdded() || requestBaseMode != selectedBaseMode)
                return;
            applySelectedHomeTab();
            MainWebtoonAdapter adapter = getSelectedAdapter();
            if(adapter != null && !initialRowsAlreadyShown)
                adapter.showInitialRows();
            scrollHomeToTop();
            if(adapter == null || !adapter.hasDisplayContent())
                fetchSelected();
            else if(!adapter.hasCompleteHomeSections())
                recyclerView.postDelayed(() -> {
                    if(isAdded() && requestBaseMode == selectedBaseMode)
                        fetchSelected();
                }, 350);
            else
                scheduleInactivePrefetchIfReady();
        });
    }

    private RecyclerView getSelectedRecycler() {
        return getRecyclerForBaseMode(selectedBaseMode);
    }

    private RecyclerView getRecyclerForBaseMode(int baseMode) {
        return baseMode == base_comic ? comicRecycler : webtoonRecycler;
    }

    private RecyclerView getOtherRecycler(RecyclerView recyclerView) {
        return recyclerView == comicRecycler ? webtoonRecycler : comicRecycler;
    }

    private void showSelectedRecycler(RecyclerView previousRecycler, RecyclerView selectedRecycler) {
        if(selectedRecycler == null)
            return;
        selectedRecycler.setVisibility(View.VISIBLE);
        selectedRecycler.setAlpha(1f);
        selectedRecycler.setEnabled(true);
        selectedRecycler.setClickable(true);
        selectedRecycler.bringToFront();
        RecyclerView inactiveRecycler = selectedRecycler == comicRecycler ? webtoonRecycler : comicRecycler;
        if(inactiveRecycler != null && inactiveRecycler != selectedRecycler) {
            inactiveRecycler.setAlpha(0f);
            inactiveRecycler.setEnabled(false);
            inactiveRecycler.setClickable(false);
            inactiveRecycler.setVisibility(View.GONE);
        }
        if(previousRecycler != null && previousRecycler != selectedRecycler) {
            previousRecycler.setAlpha(0f);
            previousRecycler.setEnabled(false);
            previousRecycler.setClickable(false);
            previousRecycler.setVisibility(View.GONE);
        }
    }

    private void applySelectedHomeTab() {
        MainWebtoonAdapter adapter = getSelectedAdapter();
        if(adapter != null)
            adapter.setHomeTab(getSelectedTabPosition());
    }

    private void updateModeToggle() {
        if(getContext() == null || modeWebtoon == null || modeComic == null)
            return;
        boolean webtoon = selectedBaseMode == base_webtoon;
        styleModeButton(modeWebtoon, webtoon);
        styleModeButton(modeComic, !webtoon);
    }

    private void styleModeButton(TextView view, boolean selected) {
        view.setSelected(selected);
        ViewCompat.setStateDescription(view, selected ? "선택됨" : "선택 안 됨");
        if(view instanceof MaterialButton) {
            MaterialButton button = (MaterialButton)view;
            int background = ContextCompat.getColor(getContext(), selected ? R.color.appAccent : android.R.color.transparent);
            int stroke = ContextCompat.getColor(getContext(), selected ? R.color.appAccent : R.color.appDivider);
            button.setChecked(selected);
            button.setBackgroundTintList(ColorStateList.valueOf(background));
            button.setStrokeColor(ColorStateList.valueOf(stroke));
            button.setTextColor(ContextCompat.getColor(getContext(), selected ? android.R.color.white : R.color.appTextSecondary));
            return;
        }
        if(p.getDarkTheme() && selected) {
            GradientDrawable background = new GradientDrawable();
            background.setColor(ContextCompat.getColor(getContext(), R.color.appAccent));
            background.setCornerRadius(dp(10));
            view.setBackground(background);
        } else {
            view.setBackgroundResource(selected ? R.drawable.app_accent_button_bg : android.R.color.transparent);
        }
        view.setTextColor(ContextCompat.getColor(getContext(),
                selected ? android.R.color.white : (p.getDarkTheme() ? R.color.colorDarkTextSecondary : R.color.appTextSecondary)));
    }

    private void applyHomeTheme(ViewGroup rootView) {
        if(getContext() == null || !p.getDarkTheme())
            return;
        int background = ContextCompat.getColor(getContext(), R.color.colorDarkWindowBackground);
        int text = ContextCompat.getColor(getContext(), R.color.colorDarkText);
        int secondary = ContextCompat.getColor(getContext(), R.color.colorDarkTextSecondary);
        rootView.setBackgroundColor(background);
        if(mainTabLayout != null) {
            mainTabLayout.setBackgroundColor(background);
            mainTabLayout.setTabTextColors(secondary, ContextCompat.getColor(getContext(), R.color.appAccent));
        }
        if(webtoonRecycler != null)
            webtoonRecycler.setBackgroundColor(background);
        if(comicRecycler != null)
            comicRecycler.setBackgroundColor(background);
        tintText(rootView, R.id.homeTitle, text);
        tintText(rootView, R.id.homeSubtitle, secondary);
        GradientDrawable toggle = new GradientDrawable();
        toggle.setColor(ContextCompat.getColor(getContext(), R.color.colorDarkSurface));
        toggle.setStroke(dp(1), ContextCompat.getColor(getContext(), R.color.colorDarkDivider));
        toggle.setCornerRadius(dp(12));
        View modeToggle = rootView.findViewById(R.id.mainModeToggle);
        if(modeToggle != null)
            modeToggle.setBackground(toggle);
        if(homeLoadStatus != null)
            homeLoadStatus.setBackgroundColor(ContextCompat.getColor(getContext(), R.color.colorDarkSurface));
        if(homeLoadStatusText != null)
            homeLoadStatusText.setTextColor(secondary);
    }

    private void tintText(View rootView, int id, int color) {
        View view = rootView.findViewById(id);
        if(view instanceof TextView)
            ((TextView)view).setTextColor(color);
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    public void scrollToSelectedTab() {
        if(mainRecycler == null)
            return;
        MainWebtoonAdapter adapter = getSelectedAdapter();
        if(adapter == null)
            return;
        int target = adapter.getScrollPositionForHomeTab(getSelectedTabPosition());
        int requestVersion = ++scrollRequestVersion;
        int requestBaseMode = selectedBaseMode;
        int requestTab = getSelectedTabPosition();
        mainRecycler.stopScroll();
        scrollRecyclerToPosition(target);
        mainRecycler.post(() -> {
            if(mainRecycler == null || requestVersion != scrollRequestVersion || requestBaseMode != selectedBaseMode || requestTab != getSelectedTabPosition())
                return;
            scrollRecyclerToPosition(target);
        });
        mainRecycler.postDelayed(() -> {
            if(mainRecycler == null || requestVersion != scrollRequestVersion || requestBaseMode != selectedBaseMode || requestTab != getSelectedTabPosition())
                return;
            scrollRecyclerToPosition(target);
        }, 120);
        mainRecycler.postDelayed(() -> {
            if(mainRecycler == null || requestVersion != scrollRequestVersion || requestBaseMode != selectedBaseMode || requestTab != getSelectedTabPosition())
                return;
            scrollRecyclerToPosition(target);
        }, 300);
    }

    private void scrollHomeToTop() {
        if(mainRecycler == null)
            return;
        scrollRecyclerToPosition(0);
        mainRecycler.post(() -> {
            if(mainRecycler != null)
                scrollRecyclerToPosition(0);
        });
    }

    private void scrollRecyclerToPosition(int position) {
        if(mainRecycler == null)
            return;
        RecyclerView.LayoutManager manager = mainRecycler.getLayoutManager();
        if(manager instanceof LinearLayoutManager)
            ((LinearLayoutManager) manager).scrollToPositionWithOffset(position, 0);
        else if(manager != null)
            manager.scrollToPosition(position);
    }

    private void selectHomeTab(int position) {
        if(mainTabLayout == null || position < 0 || position >= mainTabLayout.getTabCount())
            return;
        TabLayout.Tab tab = mainTabLayout.getTabAt(position);
        if(tab != null)
            tab.select();
    }

    private void fetchSelected() {
        boolean captchaStarted = maybeOpenNtkCaptcha();
        Log.d("ViewerPerf", "ntk_home_fetch_selected mode=" + selectedBaseMode
                + ",captchaStarted=" + captchaStarted
                + ",wait=" + wait
                + ",tab=" + getSelectedTabPosition());
        if(captchaStarted)
            return;
        if(selectedBaseMode == base_comic)
            fetchComic();
        else
            fetchWebtoon();
    }

    private boolean maybeOpenNtkCaptcha() {
        if(!isAdded() || getActivity() == null)
            return false;
        if(shouldSuppressAutoCaptcha())
            return false;
        return Utils.startNtkTurnstileCaptchaIfNeeded(getActivity(), 3, this, p);
    }

    private boolean shouldSuppressAutoCaptcha() {
        if(!getHttpClient().isNtk())
            return false;
        if(homeCreatedAt <= 0L)
            return false;
        return SystemClock.uptimeMillis() - homeCreatedAt < HomeStartupPolicy.autoCaptchaDelayMs(true);
    }

    private void showInitialHomeRows(int baseMode) {
        MainWebtoonAdapter adapter = ensureHomeAdapter(baseMode);
        if(adapter != null)
            adapter.showInitialRows();
    }

    private int selectedFetchState() {
        return selectedBaseMode == base_comic ? comicFetchState : webtoonFetchState;
    }

    private void scheduleInactivePrefetchIfReady() {
        if(!HomeInactivePrefetchPolicy.shouldSchedule(getHttpClient().isNtk(), selectedFetchState(), wait))
            return;
        RecyclerView targetRecycler = mainRecycler != null ? mainRecycler : getSelectedRecycler();
        if(targetRecycler == null)
            return;
        final int visibleBaseMode = selectedBaseMode;
        final int requestVersion = ++inactivePrefetchRequestVersion;
        long delayMs = HomeInactivePrefetchPolicy.delayMs(selectedFetchState());
        targetRecycler.postDelayed(() -> {
            if(!isAdded() || wait)
                return;
            if(requestVersion != inactivePrefetchRequestVersion)
                return;
            if(visibleBaseMode != selectedBaseMode)
                return;
            if(maybeOpenNtkCaptcha())
                return;
            if(visibleBaseMode == base_comic)
                fetchWebtoon();
            else
                fetchComic();
        }, delayMs);
    }

    private void fetchComic() {
        ensureHomeAdapter(base_comic);
        if(mainComicAdapter == null)
            return;
        if(mainComicAdapter.isFetching()) {
            comicFetchState = HOME_FETCH_LOADING;
            return;
        }
        if(comicFetchState == HOME_FETCH_COMPLETE && mainComicAdapter.hasCompleteHomeSections())
            return;
        comicFetchState = HOME_FETCH_LOADING;
        if(selectedBaseMode == base_comic)
            hideHomeLoadStatus();
        mainComicAdapter.fetch();
    }

    private void fetchWebtoon() {
        ensureHomeAdapter(base_webtoon);
        if(mainWebtoonAdapter == null)
            return;
        if(mainWebtoonAdapter.isFetching()) {
            webtoonFetchState = HOME_FETCH_LOADING;
            return;
        }
        if(webtoonFetchState == HOME_FETCH_COMPLETE && mainWebtoonAdapter.hasCompleteHomeSections())
            return;
        webtoonFetchState = HOME_FETCH_LOADING;
        if(selectedBaseMode == base_webtoon)
            hideHomeLoadStatus();
        mainWebtoonAdapter.fetch();
    }

    private void onHomeFetchFinished(int baseMode, boolean success) {
        MainWebtoonAdapter adapter = baseMode == base_comic ? mainComicAdapter : mainWebtoonAdapter;
        int state;
        if(success && adapter != null && adapter.hasCompleteHomeSections())
            state = HOME_FETCH_COMPLETE;
        else if(adapter != null && adapter.hasDisplayContent())
            state = HOME_FETCH_PARTIAL;
        else
            state = HOME_FETCH_FAILED;
        if(baseMode == base_comic)
            comicFetchState = state;
        else if(baseMode == base_webtoon)
            webtoonFetchState = state;
        if(baseMode == selectedBaseMode) {
            updateHomeLoadStatus(adapter, state);
            scheduleInactivePrefetchIfReady();
        }
        Log.d("ViewerPerf", "ntk_home_fetch_finished mode=" + baseMode
                + ",selected=" + selectedBaseMode
                + ",success=" + success
                + ",state=" + state
                + ",hasDisplay=" + (adapter != null && adapter.hasDisplayContent())
                + ",complete=" + (adapter != null && adapter.hasCompleteHomeSections()));
        if(success && state != HOME_FETCH_COMPLETE)
            scheduleIncompleteHomeRetry(baseMode);
    }

    private void updateHomeLoadStatus(MainWebtoonAdapter adapter, int state) {
        if(homeLoadStatus == null)
            return;
        boolean show = state == HOME_FETCH_FAILED && (adapter == null || !adapter.hasDisplayContent());
        homeLoadStatus.setVisibility(show ? View.VISIBLE : View.GONE);
        if(show && homeLoadStatusText != null)
            homeLoadStatusText.setText(R.string.home_load_failed_message);
    }

    private void hideHomeLoadStatus() {
        if(homeLoadStatus != null)
            homeLoadStatus.setVisibility(View.GONE);
    }

    private void retrySelectedHome() {
        if(!canUseHomeUi() || wait)
            return;
        hideHomeLoadStatus();
        if(selectedBaseMode == base_comic)
            fetchComic();
        else
            fetchWebtoon();
    }

    static boolean shouldRetrySelectedHomeOnReselect(int fetchState) {
        return fetchState == HOME_FETCH_FAILED;
    }

    private void refreshHomeLocalState() {
        if(mainComicAdapter != null)
            mainComicAdapter.refreshLocalState();
        if(mainWebtoonAdapter != null)
            mainWebtoonAdapter.refreshLocalState();
    }

    private void scheduleIncompleteHomeRetry(int baseMode) {
        RecyclerView target = baseMode == base_comic ? comicRecycler : webtoonRecycler;
        if(target == null)
            target = mainRecycler;
        if(target == null)
            return;
        target.postDelayed(() -> {
            if(!isAdded() || wait)
                return;
            MainWebtoonAdapter adapter = baseMode == base_comic ? mainComicAdapter : mainWebtoonAdapter;
            if(adapter == null || adapter.isFetching() || adapter.hasCompleteHomeSections())
                return;
            if(baseMode == base_comic)
                fetchComic();
            else
                fetchWebtoon();
        }, 350);
    }

    private void showContinuePopup(View view, Title title) {
        if(getContext() == null || view == null || title == null)
            return;
        PopupMenu popup = new PopupMenu(getContext(), view);
        popup.getMenuInflater().inflate(R.menu.title_options, popup.getMenu());
        popup.getMenu().findItem(R.id.del).setVisible(true);
        popup.setOnMenuItemClickListener(item -> {
            if(item.getItemId() == R.id.del) {
                p.removeRecent(title);
                refreshHomeLocalState();
                return true;
            }
            return false;
        });
        popup.show();
    }

    @Override
    public void onStart() {
        super.onStart();
        fragmentActive = true;
    }

    @Override
    public void onResume() {
        super.onResume();
        if(viewStarted)
            refreshHomeLocalState();
        viewStarted = true;
        maybeOpenNtkCaptcha();
    }

    @Override
    public void onStop() {
        super.onStop();
        fragmentActive = false;
        cancelHomeFetches();
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        cancelHomeFetches();
        if(localChangeListener != null) {
            p.removeLocalChangeListener(localChangeListener);
            localChangeListener = null;
        }
        mainRecycler = null;
        webtoonRecycler = null;
        comicRecycler = null;
        mainComicAdapter = null;
        mainWebtoonAdapter = null;
        homeLoadStatus = null;
        homeLoadStatusText = null;
        homeRetryButton = null;
        homeClickListener = null;
        comicFetchState = HOME_FETCH_IDLE;
        webtoonFetchState = HOME_FETCH_IDLE;
    }

    public void cancelHomeFetches() {
        if(mainComicAdapter != null)
            mainComicAdapter.cancelFetch();
        if(mainWebtoonAdapter != null)
            mainWebtoonAdapter.cancelFetch();
    }

    @Override
    public void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if(resultCode == RESULT_CAPTCHA) {
            getHttpClient().syncCookiesFromWebView(p.getWebtoonUrl(), true);
            getHttpClient().syncCookiesFromWebView(p.getUrl(), true);
            if(p.getBaseMode() == base_comic)
                comicFetchState = HOME_FETCH_IDLE;
            else
                webtoonFetchState = HOME_FETCH_IDLE;
            fetchSelected();
        }
    }
}
