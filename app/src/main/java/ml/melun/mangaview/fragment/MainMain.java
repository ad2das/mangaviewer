package ml.melun.mangaview.fragment;

import android.content.Intent;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;
import androidx.fragment.app.Fragment;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.tabs.TabLayout;
import com.bumptech.glide.Glide;

import ml.melun.mangaview.interfaces.MainActivityCallback;
import ml.melun.mangaview.R;
import ml.melun.mangaview.Preference;
import ml.melun.mangaview.UrlUpdater;
import ml.melun.mangaview.Utils;
import ml.melun.mangaview.activity.MainActivity;
import ml.melun.mangaview.activity.TagSearchActivity;
import ml.melun.mangaview.adapter.MainAdapter;
import ml.melun.mangaview.adapter.MainWebtoonAdapter;
import ml.melun.mangaview.mangaview.Manga;
import ml.melun.mangaview.mangaview.Title;
import ml.melun.mangaview.ui.NpaLinearLayoutManager;

import static ml.melun.mangaview.MainApplication.p;
import static ml.melun.mangaview.Utils.episodeIntent;
import static ml.melun.mangaview.Utils.openViewer;
import static ml.melun.mangaview.activity.CaptchaActivity.RESULT_CAPTCHA;
import static ml.melun.mangaview.mangaview.MTitle.base_comic;
import static ml.melun.mangaview.mangaview.MTitle.base_webtoon;

public class MainMain extends Fragment{

    RecyclerView mainRecycler;
    MainWebtoonAdapter mainComicAdapter;
    MainWebtoonAdapter mainWebtoonAdapter;
    Fragment fragment;
    boolean wait = false;
    UrlUpdater.UrlUpdaterCallback callback;
    MainActivityCallback mainActivityCallback;
    TabLayout mainTabLayout;
    TextView modeWebtoon;
    TextView modeComic;
    int selectedBaseMode = base_webtoon;

    final static int FOR_YOU_TAB = 0;
    final static int POPULAR_TAB = 1;
    final static int NEW_TAB = 2;
    final static int GENRES_TAB = 3;

    boolean fragmentActive = false;
    boolean comicFetched = false;
    boolean webtoonFetched = false;
    boolean viewStarted = false;
    Runnable pendingInitialFetch;
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
            comicFetched = false;
            webtoonFetched = false;
            if(fragmentActive)
                fetchSelected();
        };
    }

    public UrlUpdater.UrlUpdaterCallback getCallback(){
        return callback;
    }

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
        ViewGroup rootView = (ViewGroup) inflater.inflate(R.layout.content_main , container, false);


        mainTabLayout = rootView.findViewById(R.id.mainTab);
        modeWebtoon = rootView.findViewById(R.id.modeWebtoon);
        modeComic = rootView.findViewById(R.id.modeComic);

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
        mainRecycler = rootView.findViewById(R.id.main_recycler);
        NpaLinearLayoutManager lm = new NpaLinearLayoutManager(getContext());
        mainRecycler.setLayoutManager(lm);
        mainRecycler.setHasFixedSize(true);
        mainRecycler.setItemViewCacheSize(8);
        mainRecycler.setItemAnimator(null);
        mainRecycler.setOverScrollMode(View.OVER_SCROLL_NEVER);
        mainRecycler.addOnScrollListener(new RecyclerView.OnScrollListener() {
            @Override
            public void onScrollStateChanged(@NonNull RecyclerView recyclerView, int newState) {
                super.onScrollStateChanged(recyclerView, newState);
                if(getContext() == null)
                    return;
                if(newState == RecyclerView.SCROLL_STATE_IDLE)
                    Glide.with(MainMain.this).resumeRequests();
                else
                    Glide.with(MainMain.this).pauseRequests();
            }
        });
        localChangeListener = scope -> {
            if(!"recent".equals(scope) && !"bookmark".equals(scope))
                return;
            if(mainRecycler != null)
                mainRecycler.post(this::refreshHomeLocalState);
        };
        p.addLocalChangeListener(localChangeListener);


        MainAdapter.onItemClick listener = new MainAdapter.onItemClick() {

            @Override
            public void clickedTitle(Title t) {
                startActivity(episodeIntent(getContext(), t));
            }

            @Override
            public void clickedManga(Manga m) {
                //mget title from manga m and start intent for manga m
                //getTitleFromManga intentStarter = new getTitleFromManga();
                //intentStarter.execute(m);
                openViewer(getContext(), m,-1);
            }

            @Override
            public void clickedGenre(String t) {
                Intent i = new Intent(getContext(), TagSearchActivity.class);
                i.putExtra("query",t);
                i.putExtra("mode",2);
                i.putExtra("baseMode", p.getBaseMode());
                startActivity(i);
            }

            @Override
            public void clickedName(String t) {
                Intent i = new Intent(getContext(), TagSearchActivity.class);
                i.putExtra("query",t);
                i.putExtra("mode",3);
                i.putExtra("baseMode", p.getBaseMode());
                startActivity(i);
            }

            @Override
            public void clickedRelease(String t) {
                Intent i = new Intent(getContext(), TagSearchActivity.class);
                i.putExtra("query",t);
                i.putExtra("mode",4);
                i.putExtra("baseMode", p.getBaseMode());
                startActivity(i);
            }

            @Override
            public void clickedMoreUpdated() {
                Intent i = new Intent(getContext(), TagSearchActivity.class);
                i.putExtra("mode",5);
                startActivity(i);
            }

            @Override
            public void captchaCallback() {
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
                Intent i = new Intent(getContext(), TagSearchActivity.class);
                i.putExtra("query", path);
                i.putExtra("title", title);
                i.putExtra("mode", 8);
                i.putExtra("baseMode", p.getBaseMode());
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
        };

        mainComicAdapter = new MainWebtoonAdapter(getContext(), base_comic);
        mainComicAdapter.setListener(listener);
        mainComicAdapter.setAnchorRecycler(mainRecycler);

        mainWebtoonAdapter = new MainWebtoonAdapter(getContext());
        mainWebtoonAdapter.setListener(listener);
        mainWebtoonAdapter.setAnchorRecycler(mainRecycler);

        selectedBaseMode = p.getBaseMode() == base_comic ? base_comic : base_webtoon;
        modeWebtoon.setOnClickListener(v -> {
            switchBaseMode(base_webtoon);
            showInitialHomeRows();
            scheduleInitialFetch();
        });
        modeComic.setOnClickListener(v -> {
            switchBaseMode(base_comic);
            showInitialHomeRows();
            scheduleInitialFetch();
        });
        switchBaseMode(selectedBaseMode);

        showInitialHomeRows();
        if(!wait)
            scheduleInitialFetch();
        return rootView;
    }

    private MainWebtoonAdapter getSelectedAdapter() {
        return selectedBaseMode == base_comic ? mainComicAdapter : mainWebtoonAdapter;
    }

    private int getSelectedTabPosition() {
        if(mainTabLayout == null || mainTabLayout.getSelectedTabPosition() < 0)
            return FOR_YOU_TAB;
        return mainTabLayout.getSelectedTabPosition();
    }

    private void switchBaseMode(int baseMode) {
        cancelScheduledInitialFetch();
        cancelInactiveFetch(baseMode);
        selectedBaseMode = baseMode;
        p.setBaseMode(baseMode);
        if(mainRecycler != null)
            mainRecycler.setAdapter(getSelectedAdapter());
        updateModeToggle();
        applySelectedHomeTab();
        scrollToSelectedTab();
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
        view.setBackgroundResource(selected ? R.drawable.app_accent_button_bg : android.R.color.transparent);
        view.setTextColor(ContextCompat.getColor(getContext(), selected ? android.R.color.white : R.color.appTextSecondary));
    }

    public void scrollToSelectedTab() {
        if(mainRecycler == null)
            return;
        MainWebtoonAdapter adapter = getSelectedAdapter();
        if(adapter == null)
            return;
        int target = adapter.getScrollPositionForHomeTab(getSelectedTabPosition());
        mainRecycler.stopScroll();
        mainRecycler.post(() -> {
            RecyclerView.LayoutManager manager = mainRecycler.getLayoutManager();
            if(manager instanceof LinearLayoutManager)
                ((LinearLayoutManager) manager).scrollToPositionWithOffset(target, 0);
            else if(manager != null)
                manager.scrollToPosition(target);
        });
    }

    private void selectHomeTab(int position) {
        if(mainTabLayout == null || position < 0 || position >= mainTabLayout.getTabCount())
            return;
        TabLayout.Tab tab = mainTabLayout.getTabAt(position);
        if(tab != null)
            tab.select();
    }

    private void fetchSelected() {
        if(selectedBaseMode == base_comic)
            fetchComic();
        else
            fetchWebtoon();
    }

    private void showInitialHomeRows() {
        MainWebtoonAdapter adapter = getSelectedAdapter();
        if(adapter != null)
            adapter.showInitialRows();
    }

    private void scheduleInitialFetch() {
        if(mainRecycler == null) {
            fetchSelected();
            return;
        }
        cancelScheduledInitialFetch();
        final int targetBaseMode = selectedBaseMode;
        pendingInitialFetch = () -> {
            pendingInitialFetch = null;
            if(mainRecycler != null && isAdded() && !wait && selectedBaseMode == targetBaseMode)
                fetchSelected();
        };
        mainRecycler.postDelayed(pendingInitialFetch, 250);
    }

    private void cancelScheduledInitialFetch() {
        if(mainRecycler != null && pendingInitialFetch != null)
            mainRecycler.removeCallbacks(pendingInitialFetch);
        pendingInitialFetch = null;
    }

    private void cancelInactiveFetch(int activeBaseMode) {
        if(activeBaseMode == base_comic) {
            if(mainWebtoonAdapter != null && mainWebtoonAdapter.isFetching()) {
                mainWebtoonAdapter.cancelFetch();
                webtoonFetched = false;
            }
        } else {
            if(mainComicAdapter != null && mainComicAdapter.isFetching()) {
                mainComicAdapter.cancelFetch();
                comicFetched = false;
            }
        }
    }

    private void fetchComic() {
        if(mainComicAdapter != null && !comicFetched) {
            comicFetched = true;
            mainComicAdapter.fetch();
        }
    }

    private void fetchWebtoon() {
        if(mainWebtoonAdapter != null && !webtoonFetched) {
            webtoonFetched = true;
            mainWebtoonAdapter.fetch();
        }
    }

    private void refreshHomeLocalState() {
        if(mainComicAdapter != null && comicFetched)
            mainComicAdapter.refreshLocalState();
        if(mainWebtoonAdapter != null && webtoonFetched)
            mainWebtoonAdapter.refreshLocalState();
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
    }

    @Override
    public void onStop() {
        super.onStop();
        fragmentActive = false;
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        cancelScheduledInitialFetch();
        if(mainComicAdapter != null)
            mainComicAdapter.cancelFetch();
        if(mainWebtoonAdapter != null)
            mainWebtoonAdapter.cancelFetch();
        if(localChangeListener != null) {
            p.removeLocalChangeListener(localChangeListener);
            localChangeListener = null;
        }
        mainRecycler = null;
        mainComicAdapter = null;
        mainWebtoonAdapter = null;
        comicFetched = false;
        webtoonFetched = false;
    }

    @Override
    public void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if(resultCode == RESULT_CAPTCHA) {
            if(p.getBaseMode() == base_comic)
                comicFetched = false;
            else
                webtoonFetched = false;
            fetchSelected();
        }
    }
}
